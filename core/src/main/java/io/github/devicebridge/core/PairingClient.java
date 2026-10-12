package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;

/** One-shot Android 11+ pairing client. Run pair() off the UI thread; close() cancels. */
public final class PairingClient implements Closeable {
    static final int PEER_INFO_SIZE = 8192;
    private final AdbIdentity identity;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Socket raw;
    private volatile boolean closed, timedOut;

    public PairingClient(AdbIdentity identity) {
        if (identity == null) throw new NullPointerException("identity");
        this.identity = identity;
    }

    /** Contains authenticated peer information, never a pairing code or session key. */
    public static final class Result {
        public final String host;
        public final int pairingPort;
        public final String deviceGuid;
        /** Diagnostic only. adbd's connect certificate is independent of its pairing certificate. */
        public final String pairingCertificateSha256;
        public final long pairedAtMillis;
        Result(String host, int port, String guid, String digest) {
            this.host = host; pairingPort = port; deviceGuid = guid;
            pairingCertificateSha256 = digest; pairedAtMillis = System.currentTimeMillis();
        }
    }

    /**
     * Pair with the six-digit code shown by Android's "Pair device with pairing code" dialog.
     * The supplied code array is consumed and zeroed even on failure. No preferences are written.
     * Success means authenticated peer-info exchange; connect separately using the connect port.
     */
    public Result pair(String host, int pairingPort, char[] code, int timeoutMs) throws IOException {
        byte[] password = null;
        Timer timer = null;
        SSLSocket tls = null;
        try {
            if (!started.compareAndSet(false, true)) throw new IOException("Pairing client already used");
            if (closed) throw new IOException("Pairing cancelled");
            if (host == null || host.trim().isEmpty() || pairingPort < 1 || pairingPort > 65535 || timeoutMs < 1)
                throw new IllegalArgumentException("Invalid pairing endpoint or timeout");
            if (code == null || code.length != 6) throw new IllegalArgumentException("Enter the six-digit pairing code");
            password = new byte[70];
            for (int i = 0; i < 6; i++) {
                if (code[i] < '0' || code[i] > '9') throw new IllegalArgumentException("Pairing code must contain six digits");
                password[i] = (byte) code[i];
            }
            Socket socket = new Socket();
            synchronized (this) {
                if (closed) { socket.close(); throw new IOException("Pairing cancelled"); }
                raw = socket;
            }
            timer = new Timer("adb-pairing-timeout", true);
            timer.schedule(new TimerTask() {
                public void run() { timedOut = true; close(); }
            }, timeoutMs);
            socket.connect(new InetSocketAddress(host, pairingPort), timeoutMs);
            socket.setSoTimeout(timeoutMs); socket.setTcpNoDelay(true);
            tls = TlsSupport.wrap(socket, host, pairingPort, identity);
            tls.startHandshake(); TlsSupport.requireTls13(tls);
            byte[] exported = TlsSupport.export(tls);
            try { System.arraycopy(exported, 0, password, 6, 64); }
            finally { Arrays.fill(exported, (byte) 0); }
            String guid = exchange(tls.getInputStream(), tls.getOutputStream(), identity.adbPublicKey(), password);
            if (closed) throw new IOException(timedOut ? "Pairing timed out" : "Pairing cancelled");
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(tls.getSession().getPeerCertificates()[0].getEncoded()));
            return new Result(host, pairingPort, guid, digest);
        } catch (GeneralSecurityException e) { throw new IOException("Pairing authentication failed; check the current code and pairing port", e); }
        catch (IOException e) {
            if (timedOut) throw new SocketTimeoutException("Pairing timed out; reopen the pairing dialog and try its current port/code");
            if (closed) throw new IOException("Pairing cancelled", e);
            throw e;
        } finally {
            if (code != null) Arrays.fill(code, '\0');
            if (password != null) Arrays.fill(password, (byte) 0);
            if (timer != null) timer.cancel();
            close();
            if (tls != null) try { tls.close(); } catch (IOException ignored) { }
        }
    }

    static String exchange(InputStream input, OutputStream output, byte[] publicKey, byte[] password)
            throws IOException, GeneralSecurityException {
        if (publicKey.length >= PEER_INFO_SIZE) throw new IOException("ADB public key too large");
        try (PairingCrypto crypto = new PairingCrypto(true, password)) {
            writePacket(output, 0, crypto.message());
            crypto.initialize(readPacket(input, 0, 32));
            byte[] peerInfo = new byte[PEER_INFO_SIZE];
            peerInfo[0] = 0; // ADB_RSA_PUB_KEY.
            System.arraycopy(publicKey, 0, peerInfo, 1, publicKey.length);
            writePacket(output, 1, crypto.encrypt(peerInfo));
            byte[] plaintext = crypto.decrypt(readPacket(input, 1, PEER_INFO_SIZE + 16));
            try { return decodeGuid(plaintext); }
            finally { Arrays.fill(plaintext, (byte) 0); }
        }
    }

    static String decodeGuid(byte[] info) throws IOException {
        // AOSP's actual ADB_DEVICE_GUID is 1 (libadb-android's private constant has a typo).
        if (info.length != PEER_INFO_SIZE || info[0] != 1) throw new IOException("Invalid pairing peer-info type/size");
        int end = 1;
        while (end < info.length && info[end] != 0) {
            int c = info[end] & 255;
            if (c < 0x21 || c > 0x7e) throw new IOException("Invalid device GUID encoding");
            end++;
        }
        if (end == 1 || end == info.length) throw new IOException("Missing or unterminated device GUID");
        return new String(info, 1, end - 1, StandardCharsets.US_ASCII);
    }

    static void writePacket(OutputStream output, int type, byte[] payload) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeByte(1); out.writeByte(type); out.writeInt(payload.length); out.write(payload); out.flush();
    }
    static byte[] readPacket(InputStream input, int expectedType, int expectedSize) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int version = in.readUnsignedByte(), type = in.readUnsignedByte(), size = in.readInt();
        if (version != 1 || type != expectedType || size != expectedSize || size <= 0 || size > 16384)
            throw new IOException("Invalid pairing packet header");
        byte[] payload = new byte[size]; in.readFully(payload); return payload;
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    public void close() {
        synchronized (this) { closed = true; }
        Socket socket = raw;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
