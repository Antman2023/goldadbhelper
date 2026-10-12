package io.github.devicebridge.core;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;

/** Ordinary loopback TLS and optional synthetic SPAKE2 test. Does not touch selected/user files. */
public final class Api19TlsProbe {
 public static void main(String[] args)throws Exception {
  System.out.println("JNI_SEARCH_PATH "+System.getProperty("java.library.path"));
  try {
   AdbIdentity identity=AdbIdentity.generate();
   final SSLContext context=TlsSupport.context(identity);
   System.out.println("PASS production TLS provider and context");
   final SSLServerSocket server=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
   server.setEnabledProtocols(new String[]{"TLSv1.3"});server.setNeedClientAuth(true);server.setSoTimeout(15000);
   final byte[][] peerExport=new byte[1][];final Throwable[] problem=new Throwable[1];
   Thread peer=new Thread(()->{try(SSLSocket socket=(SSLSocket)server.accept()){socket.setSoTimeout(15000);socket.startHandshake();TlsSupport.requireTls13(socket);peerExport[0]=TlsSupport.export(socket);socket.getOutputStream().write(31);}catch(Throwable error){problem[0]=error;}},"fixture-tls-peer");
   peer.start();byte[] clientExport;
   try(SSLSocket client=TlsSupport.wrap(new Socket("127.0.0.1",server.getLocalPort()),"127.0.0.1",server.getLocalPort(),identity)){
    client.setSoTimeout(15000);client.startHandshake();TlsSupport.requireTls13(client);clientExport=TlsSupport.export(client);
    if(client.getInputStream().read()!=31)throw new AssertionError("TLS application data");
   }finally{server.close();}
   peer.join(15000);if(peer.isAlive())throw new AssertionError("TLS peer timeout");if(problem[0]!=null)throw new Exception("TLS peer failed",problem[0]);
   if(clientExport.length!=64||!Arrays.equals(clientExport,peerExport[0]))throw new AssertionError("TLS exporter agreement");
   System.out.println("PASS TLS1.3 mutual-certificate loopback handshake, application bytes and matching 64-byte exporter");
   if(args.length>0&&"spake".equals(args[0])){
    byte[] password="synthetic pairing fixture only".getBytes("UTF-8");
    try(PairingCrypto a=new PairingCrypto(true,password);PairingCrypto b=new PairingCrypto(false,password)){
     byte[] am=a.message(),bm=b.message();a.initialize(bm);b.initialize(am);
     byte[] plain="synthetic plaintext".getBytes("UTF-8");if(!Arrays.equals(plain,b.decrypt(a.encrypt(plain))))throw new AssertionError("SPAKE2/AES-GCM");
    }
    System.out.println("PASS SPAKE2/HKDF/AES-GCM synthetic pairing");
   }
   System.out.println("API19_TLS_PROBE_PASSED "+(args.length>0?args[0]:"tls"));
  }catch(Throwable failure){failure.printStackTrace(System.out);throw failure;}
 }
}
