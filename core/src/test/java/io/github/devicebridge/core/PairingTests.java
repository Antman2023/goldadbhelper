package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import io.github.muntashirakon.crypto.spake2.Spake2Vectors;

/** Synthetic local endpoints only. No Android device or external target is used. */
public final class PairingTests {
    interface Checked { void run() throws Exception; }
    private static int passed;
    private static void check(boolean b, String s) { if (!b) throw new AssertionError(s); }
    private static void fail(Checked c) throws Exception { try { c.run(); } catch (IOException | GeneralSecurityException | IllegalArgumentException e) { return; } throw new AssertionError("Expected rejection"); }
    private static void test(String s, Checked c) throws Exception { c.run(); passed++; System.out.println("PASS " + s); }

    public static void main(String[] args) throws Exception {
        test("SPAKE2 matches four independent affine-curve vectors", Spake2Vectors::run);
        test("SPAKE2 random exchanges and AES-GCM authentication", () -> {
            for (int i = 0; i < 24; i++) {
                byte[] password = new byte[70]; new SecureRandom().nextBytes(password);
                try (PairingCrypto a = new PairingCrypto(true, password); PairingCrypto b = new PairingCrypto(false, password)) {
                    a.initialize(b.message()); b.initialize(a.message());
                    byte[] plain = new byte[8192]; new SecureRandom().nextBytes(plain);
                    check(Arrays.equals(plain,b.decrypt(a.encrypt(plain))), "client encrypted payload");
                    check(Arrays.equals(plain,a.decrypt(b.encrypt(plain))), "server encrypted payload");
                    byte[] forged = a.encrypt(plain); forged[33] ^= 1;
                    fail(() -> b.decrypt(forged));
                }
            }
        });
        test("Wrong pairing password fails GCM authentication", () -> {
            try (PairingCrypto a = new PairingCrypto(true,new byte[]{1}); PairingCrypto b = new PairingCrypto(false,new byte[]{2})) {
                a.initialize(b.message()); b.initialize(a.message()); fail(() -> b.decrypt(a.encrypt(new byte[8192])));
            }
        });
        test("SPAKE2 malformed point length rejected", () -> {
            try (PairingCrypto a = new PairingCrypto(true,new byte[]{1})) { fail(() -> a.initialize(new byte[31])); }
        });
        test("Destroyed pairing crypto cannot encrypt", () -> {
            PairingCrypto a = new PairingCrypto(true,new byte[]{1}); a.close(); fail(() -> a.encrypt(new byte[1]));
        });
        test("Pairing frame network byte order, bounds, version and truncation", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); PairingClient.writePacket(out,0,new byte[32]);
            byte[] frame = out.toByteArray(); check(frame[0]==1 && frame[1]==0 && frame[5]==32,"header");
            check(PairingClient.readPacket(new ByteArrayInputStream(frame),0,32).length==32,"read");
            frame[0]=2; fail(() -> PairingClient.readPacket(new ByteArrayInputStream(frame),0,32));
            frame[0]=1; frame[2]=(byte)0xff; fail(() -> PairingClient.readPacket(new ByteArrayInputStream(frame),0,32));
            fail(() -> PairingClient.readPacket(new ByteArrayInputStream(new byte[]{1,0,0}),0,32));
        });
        test("Peer GUID type, empty and unterminated info rejected", () -> {
            byte[] info = new byte[8192]; info[0]=1; info[1]='a';
            check(PairingClient.decodeGuid(info).equals("a"),"guid");
            info[0]=0; fail(() -> PairingClient.decodeGuid(info));
            info[0]=1; info[1]=0; fail(() -> PairingClient.decodeGuid(info));
            Arrays.fill(info,(byte)'x'); info[0]=1; fail(() -> PairingClient.decodeGuid(info));
        });
        final AdbIdentity identity = AdbIdentity.generate();
        test("Certificate uses the same fresh ADB key", () -> {
            SSLContext ctx=TlsSupport.context(identity,null);
            check(ctx!=null,"JCA TLS context");
            PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(identity.privateEncoded()));
            X509Certificate cert=TlsSupport.certificate(identity,key);
            check(Arrays.equals(cert.getPublicKey().getEncoded(),identity.publicEncoded()),"same identity");
            cert.checkValidity(); cert.verify(cert.getPublicKey());
        });
        test("Invalid/used/cancelled pairing consumes code without network success", () -> {
            PairingClient c=new PairingClient(identity); c.close();
            char[] code="123456".toCharArray(); fail(() -> c.pair("127.0.0.1",1,code,100));
            check(Arrays.equals(code,new char[6]),"code cleared");
            fail(() -> c.pair("127.0.0.1",1,"123456".toCharArray(),100));
        });
        if (TlsSupport.isAvailable()) {
            test("TLS1.3 exporter-bound pairing over loopback authenticates own key/GUID", () -> tlsPair(identity,false));
            test("Wrong six-digit code never reports paired over TLS", () -> tlsPair(identity,true));
            test("Total pairing timeout closes stalled TLS socket and clears code", () -> stalled(identity,false));
            test("Cancellation closes stalled TLS socket and clears code", () -> stalled(identity,true));
            test("ADB STLS upgrades and receives encrypted CNXN using same identity", () -> stls(identity));
        } else System.out.println("SKIP Conscrypt loopback tests: add official conscrypt-openjdk-uber to classpath");
        System.out.println("PAIRING TESTS: " + passed + " passed");
    }

    private static void tlsPair(AdbIdentity clientIdentity, boolean wrongCode) throws Exception {
        SSLContext serverContext=TlsSupport.context(AdbIdentity.generate());
        try (SSLServerSocket listener=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            listener.setEnabledProtocols(new String[]{"TLSv1.3"}); listener.setNeedClientAuth(true);
            FutureTask<Void> server=new FutureTask<>(() -> {
                try (SSLSocket socket=(SSLSocket)listener.accept()) {
                    socket.setSoTimeout(5000); socket.startHandshake();
                    check(Arrays.equals(socket.getSession().getPeerCertificates()[0].getPublicKey().getEncoded(),clientIdentity.publicEncoded()),"TLS key matches client identity");
                    byte[] password=new byte[70]; System.arraycopy("123456".getBytes(StandardCharsets.US_ASCII),0,password,0,6);
                    byte[] exported=TlsSupport.export(socket); System.arraycopy(exported,0,password,6,64); Arrays.fill(exported,(byte)0);
                    try (PairingCrypto crypto=new PairingCrypto(false,password)) {
                        byte[] clientMsg=PairingClient.readPacket(socket.getInputStream(),0,32);
                        PairingClient.writePacket(socket.getOutputStream(),0,crypto.message()); crypto.initialize(clientMsg);
                        byte[] payload=PairingClient.readPacket(socket.getInputStream(),1,8208);
                        if (wrongCode) { fail(() -> crypto.decrypt(payload)); return null; }
                        byte[] info=crypto.decrypt(payload);
                        check(info[0]==0,"client RSA key peer-info");
                        check(Arrays.equals(Arrays.copyOfRange(info,1,1+clientIdentity.adbPublicKey().length),clientIdentity.adbPublicKey()),"paired key same as AUTH key");
                        byte[] serverInfo=new byte[8192]; serverInfo[0]=1;
                        byte[] guid="adb-synthetic-test-guid".getBytes(StandardCharsets.US_ASCII); System.arraycopy(guid,0,serverInfo,1,guid.length);
                        PairingClient.writePacket(socket.getOutputStream(),1,crypto.encrypt(serverInfo));
                    } finally { Arrays.fill(password,(byte)0); }
                }
                return null;
            });
            Thread thread=new Thread(server,"pairing-fixture"); thread.setDaemon(true); thread.start();
            char[] code=(wrongCode?"654321":"123456").toCharArray();
            try (PairingClient client=new PairingClient(clientIdentity)) {
                if (wrongCode) fail(() -> client.pair("127.0.0.1",listener.getLocalPort(),code,5000));
                else check(client.pair("127.0.0.1",listener.getLocalPort(),code,5000).deviceGuid.equals("adb-synthetic-test-guid"),"authenticated GUID");
            } finally { server.get(6,TimeUnit.SECONDS); }
            check(Arrays.equals(code,new char[6]),"consumed code");
        }
    }

    private static void stalled(AdbIdentity identity, boolean cancel) throws Exception {
        try (ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress()); PairingClient client=new PairingClient(identity)) {
            char[] code="123456".toCharArray();
            FutureTask<Void> result=new FutureTask<>(() -> { fail(() -> client.pair("127.0.0.1",listener.getLocalPort(),code,cancel?5000:500)); return null; });
            Thread thread=new Thread(result,"pairing-cancel-fixture"); thread.setDaemon(true); thread.start();
            try (Socket socket=listener.accept()) {
                if (cancel) client.close(); result.get(3,TimeUnit.SECONDS);
                check(Arrays.equals(code,new char[6]),"code cleared after interruption");
            }
        }
    }

    private static void stls(AdbIdentity identity) throws Exception {
        SSLContext context=TlsSupport.context(AdbIdentity.generate());
        CountDownLatch done=new CountDownLatch(1);
        try (ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            FutureTask<Void> server=new FutureTask<>(() -> {
                try (Socket raw=listener.accept()) {
                    raw.setSoTimeout(5000);
                    check(AdbPacket.read(raw.getInputStream(),AdbPacket.MAX_PAYLOAD,false).command==AdbPacket.CNXN,"initial CNXN");
                    raw.getOutputStream().write(new AdbPacket(AdbPacket.STLS,0x01000000,0,null).encode()); raw.getOutputStream().flush();
                    check(AdbPacket.read(raw.getInputStream(),AdbPacket.MAX_PAYLOAD,false).command==AdbPacket.STLS,"STLS acknowledgment");
                    try (SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(raw,"localhost",raw.getPort(),true)) {
                        tls.setUseClientMode(false); tls.setEnabledProtocols(new String[]{"TLSv1.3"}); tls.setNeedClientAuth(true); tls.startHandshake();
                        check(Arrays.equals(tls.getSession().getPeerCertificates()[0].getPublicKey().getEncoded(),identity.publicEncoded()),"STLS identity");
                        tls.getOutputStream().write(new AdbPacket(AdbPacket.CNXN,0x01000001,4096,AdbPacket.text("device::features=shell_v2\0")).encode()); tls.getOutputStream().flush();
                        done.await(5,TimeUnit.SECONDS);
                    }
                }
                return null;
            });
            Thread t=new Thread(server,"stls-fixture"); t.setDaemon(true); t.start();
            try (TlsTcpTransport transport=new TlsTcpTransport("127.0.0.1",listener.getLocalPort(),5000); AdbSession session=new AdbSession(transport,identity,null)) {
                session.connect(); check(session.state()==AdbSession.State.CONNECTED && transport.isTls(),"encrypted ADB connected");
            } finally { done.countDown(); }
            server.get(6,TimeUnit.SECONDS);
        }
    }
}
