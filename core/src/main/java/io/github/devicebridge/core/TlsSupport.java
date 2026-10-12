package io.github.devicebridge.core;

import java.io.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.net.ssl.*;

/** TLS 1.3 for the ADB protocol only. Never use this trust policy for web requests. */
public final class TlsSupport {
    private TlsSupport() { }

    public static boolean isAvailable() {
        try { provider(); return true; } catch (GeneralSecurityException e) { return false; }
    }

    static Provider provider() throws GeneralSecurityException {
        try {
            Class<?> cls = Class.forName("org.conscrypt.Conscrypt");
            return (Provider) cls.getMethod("newProvider").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new GeneralSecurityException("Bundled Conscrypt TLS provider unavailable", e);
        }
    }

    public static SSLContext context(AdbIdentity identity) throws GeneralSecurityException {
        return context(identity, provider());
    }

    // Provider injection is package-private for local protocol fixtures, never a hidden-API fallback.
    static SSLContext context(AdbIdentity identity, Provider provider) throws GeneralSecurityException {
        byte[] encoded = identity.privateEncoded();
        final PrivateKey key;
        try { key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded)); }
        finally { Arrays.fill(encoded, (byte) 0); }
        final X509Certificate cert = certificate(identity, key);
        SSLContext context = provider == null ? SSLContext.getInstance("TLSv1.3") : SSLContext.getInstance("TLSv1.3", provider);
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            public String[] getClientAliases(String type, Principal[] issuers) { return "RSA".equals(type) ? new String[]{"adb"} : null; }
            public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
                for (String type : types) if ("RSA".equals(type)) return "adb";
                return null;
            }
            public String chooseEngineClientAlias(String[] types, Principal[] issuers, SSLEngine engine) { return chooseClientAlias(types, issuers, null); }
            public String[] getServerAliases(String type, Principal[] issuers) { return getClientAliases(type, issuers); }
            public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return "RSA".equals(type) ? "adb" : null; }
            public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) { return chooseServerAlias(type, issuers, null); }
            public X509Certificate[] getCertificateChain(String alias) { return "adb".equals(alias) ? new X509Certificate[]{cert} : null; }
            public PrivateKey getPrivateKey(String alias) { return "adb".equals(alias) ? key : null; }
        };
        // AOSP adbd uses ephemeral self-signed server certificates unrelated to its pairing
        // certificate. Pairing is authenticated by SPAKE2 + TLS exporter. Connect authenticates
        // our client key at adbd; it does not provide persistent server identity authentication.
        X509TrustManager protocolTrust = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException { requireCertificate(chain); }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException { requireCertificate(chain); }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            private void requireCertificate(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing ADB peer certificate");
            }
        };
        context.init(new KeyManager[]{keys}, new TrustManager[]{protocolTrust}, new SecureRandom());
        return context;
    }

    static SSLSocket wrap(Socket raw, String host, int port, AdbIdentity identity) throws IOException {
        try {
            SSLSocket socket = (SSLSocket) context(identity).getSocketFactory().createSocket(raw, host, port, true);
            socket.setUseClientMode(true);
            socket.setEnabledProtocols(new String[]{"TLSv1.3"});
            socket.setSoTimeout(raw.getSoTimeout());
            return socket;
        } catch (GeneralSecurityException e) { throw new IOException("Unable to initialize ADB TLS", e); }
    }

    static void requireTls13(SSLSocket socket) throws SSLException {
        if (!"TLSv1.3".equals(socket.getSession().getProtocol())) throw new SSLException("ADB requires TLS 1.3");
    }

    static byte[] export(SSLSocket socket) throws IOException {
        try {
            // The terminating NUL is required: AOSP passes sizeof("adb-label").
            Method method = Class.forName("org.conscrypt.Conscrypt").getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class);
            byte[] exported = (byte[]) method.invoke(null, exporterSocket(socket), "adb-label\0", null, 64);
            if (exported == null || exported.length != 64) throw new SSLException("TLS exporter returned invalid key material");
            return exported;
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) { throw new SSLException("ADB TLS exporter unavailable", e); }
    }

    /** Conscrypt 2.5.3 wraps API19-21 sockets for platform integration, but its public exporter
     * accepts only the underlying Conscrypt socket. Read ONLY this pinned library adapter's
     * immutable delegate. This does not bypass any handshake/trust check or reflect Android
     * internals: the same already-negotiated channel is passed to the official exporter API.
     */
    private static SSLSocket exporterSocket(SSLSocket socket) throws ReflectiveOperationException, SSLException {
        if (!"org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter".equals(socket.getClass().getName())) return socket;
        Field delegate = socket.getClass().getDeclaredField("delegate");
        if (!Modifier.isFinal(delegate.getModifiers())) throw new SSLException("Unexpected Conscrypt adapter layout");
        delegate.setAccessible(true);
        Object inner = delegate.get(socket);
        if (!(inner instanceof SSLSocket) || inner == socket) throw new SSLException("Invalid Conscrypt adapter delegate");
        return (SSLSocket) inner;
    }

    /** Own-identity self-signed certificate, built with JCA and small DER encoding helpers. */
    static X509Certificate certificate(AdbIdentity identity, PrivateKey key) throws GeneralSecurityException {
        // sha256WithRSAEncryption OID, NULL parameters.
        byte[] algorithm = der(0x30, new byte[]{0x06,0x09,0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,0x01,0x01,0x0b,0x05,0x00});
        byte[] name = der(0x30, der(0x31, der(0x30, concat(new byte[]{0x06,0x03,0x55,0x04,0x03}, der(0x0c, "JinJiaChong ADB".getBytes(StandardCharsets.UTF_8))))));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, concat(time(new Date(now - 86400000L)), time(new Date(now + 315360000000L))));
        byte[] serial = new BigInteger(128, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = der(0x30, concat(der(0xa0, der(0x02, new byte[]{2})), der(0x02, serial), algorithm, name, validity, name, identity.publicEncoded()));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key); signature.update(tbs);
        byte[] certDer = der(0x30, concat(tbs, algorithm, der(0x03, concat(new byte[]{0}, signature.sign()))));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certDer));
        cert.verify(cert.getPublicKey());
        return cert;
    }
    private static byte[] time(Date value) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x18, f.format(value).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] concat(byte[]... values) {
        int len = 0; for (byte[] value : values) len += value.length;
        byte[] out = new byte[len]; int at = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, out, at, value.length); at += value.length; }
        return out;
    }
    private static byte[] der(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(tag);
        if (value.length < 128) out.write(value.length);
        else if (value.length < 256) { out.write(0x81); out.write(value.length); }
        else { out.write(0x82); out.write(value.length >>> 8); out.write(value.length); }
        out.write(value, 0, value.length); return out.toByteArray();
    }
}
