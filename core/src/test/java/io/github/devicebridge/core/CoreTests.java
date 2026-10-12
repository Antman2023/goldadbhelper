package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;

public final class CoreTests {
    private static int passed;
    interface Test {void run() throws Exception;}
    static void test(String name,Test f)throws Exception{f.run();passed++;System.out.println("PASS "+name);}
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    static void failure(Test f)throws Exception{try{f.run();throw new AssertionError("Expected failure");}catch(IOException|IllegalArgumentException e){}}
    public static void main(String[] args)throws Exception{
        test("ADB frame round trip and fragmented reads",()->{
            AdbPacket p=new AdbPacket(AdbPacket.WRTE,7,11,"中文 ADB".getBytes(StandardCharsets.UTF_8));
            InputStream in=new FilterInputStream(new ByteArrayInputStream(p.encode())){public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(n,2));}};
            AdbPacket q=AdbPacket.read(in,4096,true);check(q.arg0==7&&q.arg1==11&&Arrays.equals(p.payload,q.payload),"round trip");
        });
        test("ADB corrupt checksum rejected",()->{byte[] b=new AdbPacket(AdbPacket.WRTE,1,2,new byte[]{5}).encode();b[24]=7;failure(()->AdbPacket.read(new ByteArrayInputStream(b),4096,true));});
        test("ADB corrupt magic rejected",()->{byte[] b=new AdbPacket(AdbPacket.WRTE,1,2,null).encode();b[20]^=1;failure(()->AdbPacket.read(new ByteArrayInputStream(b),4096,true));});
        test("ADB oversized and negative lengths rejected",()->{for(int size:new int[]{-1,2097152}){byte[] b=new AdbPacket(AdbPacket.WRTE,1,2,null).encode();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(12,size);failure(()->AdbPacket.read(new ByteArrayInputStream(b),4096,true));}});
        test("ADB premature EOF rejected",()->failure(()->AdbPacket.read(new ByteArrayInputStream(new byte[8]),4096,true)));
        AdbIdentity id=AdbIdentity.generate();
        test("Fresh ADB identity restores and signs SHA1 digest correctly",()->{
            AdbIdentity copy=AdbIdentity.restore(id.privateEncoded(),id.publicEncoded());check(copy.fingerprint().equals(id.fingerprint()),"identity restore");
            byte[] token=new byte[20];new SecureRandom().nextBytes(token);byte[] sig=id.signToken(token);
            Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.DECRYPT_MODE,KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(id.publicEncoded())));
            byte[] message=rsa.doFinal(sig);check(message.length==35&&Arrays.equals(Arrays.copyOfRange(message,15,35),token),"sign raw digest");
        });
        test("ADB RSA public structure matches modulus and exponent",()->{
            String pub=new String(id.adbPublicKey(),StandardCharsets.US_ASCII);byte[] binary=Base64.getDecoder().decode(pub.split(" ")[0]);
            ByteBuffer b=ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);check(binary.length==524&&b.getInt()==64,"public layout");int n0=b.getInt();
            byte[] le=new byte[256];b.get(le);byte[] be=new byte[256];for(int i=0;i<256;i++)be[i]=le[255-i];BigInteger n=new BigInteger(1,be);
            RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(id.publicEncoded()));check(n.equals(key.getModulus()),"modulus");
            check(n.longValue()*Integer.toUnsignedLong(n0)%4294967296L==4294967295L || (int)(n.longValue()*Integer.toUnsignedLong(n0))==-1,"n0 inverse");
            b.position(520);check(b.getInt()==65537,"exponent");
        });
        test("TCP ADB authentication, multiplexed shell-v2 and close",()->{
            try(MockAdb mock=new MockAdb(false,false)){
                AdbSession session=new AdbSession(new TcpTransport("127.0.0.1",mock.port(),2000),id,null);session.connect();
                check(session.state()==AdbSession.State.CONNECTED&&session.hasFeature("shell_v2"),"connected");
                ExecutorService pool=Executors.newFixedThreadPool(2);
                Future<ShellClient.Result> a=pool.submit(()->new ShellClient(session).run("test-a",2000));
                Future<ShellClient.Result> b=pool.submit(()->new ShellClient(session).run("test-b",2000));
                check(a.get().text().contains("test-a")&&b.get().text().contains("test-b"),"multiplex target isolation");pool.shutdownNow();session.close();
                check(session.state()==AdbSession.State.CLOSED,"closed state");
            }
        });
        test("Authentication rejection surfaced",()->{try(MockAdb mock=new MockAdb(true,false)){AdbSession s=new AdbSession(new TcpTransport("127.0.0.1",mock.port(),1000),id,null);failure(s::connect);check(s.state()==AdbSession.State.FAILED,"failure state");}});
        test("Secure wireless port not treated as legacy ADB",()->{try(MockAdb mock=new MockAdb(false,true)){AdbSession s=new AdbSession(new TcpTransport("127.0.0.1",mock.port(),1000),id,null);failure(s::connect);}});
        test("Closed transport unblocks blocked reader",()->{
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
                TcpTransport t=new TcpTransport("127.0.0.1",server.getLocalPort(),1000);Socket remote=server.accept();t.setReadTimeout(0);
                CompletableFuture<Boolean> blocked=CompletableFuture.supplyAsync(()->{try{t.input().read();return false;}catch(IOException e){return true;}});
                t.close();check(blocked.get(2,TimeUnit.SECONDS),"close cancellation");remote.close();
            }
        });
        test("Refused TCP connection surfaced",()->{int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}final int p=port;failure(()->new TcpTransport("127.0.0.1",p,500));});
        System.out.println("CORE TESTS: "+passed+" passed");
    }
    static final class MockAdb implements AutoCloseable {
        final ServerSocket server;final Thread thread;volatile Socket socket;volatile Throwable error;
        MockAdb(boolean reject,boolean tls)throws IOException{
            server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());thread=new Thread(()->{try{
                socket=server.accept();socket.setSoTimeout(4000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
                AdbPacket.read(in,AdbPacket.MAX_PAYLOAD,true);
                if(tls){send(out,new AdbPacket(AdbPacket.STLS,0x01000000,0,null));return;}
                send(out,new AdbPacket(AdbPacket.AUTH,1,0,new byte[20]));AdbPacket sig=AdbPacket.read(in,4096,true);check(sig.arg0==2&&sig.payload.length==256,"auth signature");
                if(reject){send(out,new AdbPacket(AdbPacket.AUTH,1,0,new byte[20]));AdbPacket pub=AdbPacket.read(in,4096,true);check(pub.arg0==3,"public offer");send(out,new AdbPacket(AdbPacket.AUTH,1,0,new byte[20]));return;}
                send(out,new AdbPacket(AdbPacket.CNXN,0x01000001,4096,AdbPacket.text("device::features=shell_v2,cmd;\0")));
                while(true){AdbPacket p=AdbPacket.read(in,4096,true);if(p.command==AdbPacket.OPEN){
                    send(out,new AdbPacket(AdbPacket.OKAY,p.arg0+100,p.arg0,null));
                    byte[] txt=p.payload;ByteBuffer f=ByteBuffer.allocate(5+txt.length+6).order(ByteOrder.LITTLE_ENDIAN);f.put((byte)1).putInt(txt.length).put(txt).put((byte)3).putInt(1).put((byte)0);
                    send(out,new AdbPacket(AdbPacket.WRTE,p.arg0+100,p.arg0,f.array()));
                }else if(p.command==AdbPacket.OKAY)send(out,new AdbPacket(AdbPacket.CLSE,p.arg1,p.arg0,null));}
            }catch(EOFException|SocketException expected){}catch(Throwable e){error=e;}},"local-adb-simulator");thread.setDaemon(true);thread.start();
        }
        static void send(OutputStream out,AdbPacket p)throws IOException{out.write(p.encode());out.flush();}
        int port(){return server.getLocalPort();}
        public void close()throws Exception{if(socket!=null)socket.close();server.close();thread.join(2000);if(error!=null)throw new AssertionError("Simulator failed",error);}
    }
}
