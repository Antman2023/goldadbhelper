package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated loopback smart-socket endpoint only. Never connects to adbd or runs adb. */
public final class HostServerTests {
    private static int assertions;
    private static final String A="SERIAL_A\tdevice product:p model:Pixel_Test device:d transport_id:7\n";
    private static final String B="SERIAL_B\tdevice product:q model:Second device:e transport_id:8\n";
    public static void main(String[] args)throws Exception {
        parsingAndSharedEnumeration();shellSyncAndStreamReuse();offlineAndEpochBinding();
        cancellationIsolationAndRetry();cancelHandshakeAndService();staleRefreshReplies();
        stopResults();malformedAndRejectedResponses();
        System.out.println("HostServerTests: "+assertions+" assertions passed (synthetic loopback only)");
    }
    private static void parsingAndSharedEnumeration()throws Exception {
        try(Fixture f=new Fixture(A+A+B+"NOAUTH\tunauthorized transport_id:9\nOLD\tdevice model:Old\n")){
            try(HostServerClient first=f.client();HostServerClient second=f.client()){
                first.connect(1000);second.connect(1000);
                eq(4,first.snapshot().devices.size(),"identical duplicate rows deduplicated");
                eq(first.snapshot().devices.get(1).serial,second.snapshot().devices.get(1).serial,"independent clients share server enumeration");
                check(!first.snapshot().devices.get(2).canConnect(),"unauthorized remains visible but not connectable");
                check(!first.snapshot().devices.get(3).canConnect(),"missing transport id does not fall back to serial");
                eq("Pixel_Test",first.snapshot().devices.get(0).model,"long metadata parsed");
                try(AdbSession selected=session(first,0)){
                    HostServerClient.Device old=first.snapshot().devices.get(0);
                    try(OperationScope scope=new OperationScope()){first.refresh(1000,scope);}
                    eq(AdbSession.State.CONNECTED,selected.state(),"refresh retains live selected transport");
                    try(AdbSession same=new AdbSession(first,old,null)){same.connect();check(same.isHostServer(),"valid selection survives identical refresh");}
                    expectIo(()->new AdbSession(second,old,null).connect(),"cross-client selection rejected");
                    eq(f.endpoint(),selected.serverEndpoint(),"server endpoint retained");
                    check(!selected.isEncrypted(),"does not claim TLS for server relay");
                }
                check(second.state()==HostServerClient.State.CONNECTED,"closing own session keeps other client tracker");
                check(!f.hasRequestPrefix("host:disconnect"),"no global disconnect on local close");
            }
        }
    }
    private static void shellSyncAndStreamReuse()throws Exception {
        try(Fixture f=new Fixture(A);HostServerClient c=f.client()){
            c.connect(1000);try(AdbSession s=session(c,0)){
                check(s.hasFeature("shell_v2"),"selected transport feature query is real");
                ShellClient.Result r=new ShellClient(s).run("echo fixture",1000);
                eq("fixture\n",r.text(),"existing shell-v2 reads server stream");eq(0,r.exitCode,"real shell-v2 exit frame");
                try(SyncClient sync=new SyncClient(s,1000)){SyncClient.Entry item=sync.stat("/synthetic");eq(42L,item.size,"existing SYNC accepts host service channel");}
                try(AdbStream stream=s.open("fixture:echo",1000)){
                    byte[] data="中文 full duplex".getBytes(StandardCharsets.UTF_8);stream.output().write(data);stream.output().flush();
                    check(Arrays.equals(data,AdbPacket.readExactly(stream.input(),data.length)),"raw bidirectional service data");
                }
                try(AdbStream one=s.open("fixture:hold",1000);AdbStream two=s.open("fixture:hold",1000)){
                    one.close();eq(AdbSession.State.CONNECTED,s.state(),"service close preserves sibling streams and session");
                    two.output().write(1);
                }
                expectIo(()->s.open("host:kill",1000),"device session cannot invoke a host-wide command");
                eq(0,f.count("host:kill"),"no host-wide request from service escape");
                check(f.hasRequestPrefix("host:transport-id:7"),"service sockets bind transport id");
                check(!f.hasRequestPrefix("host:transport:SERIAL"),"never silently falls back to serial");
            }
        }
    }
    private static void offlineAndEpochBinding()throws Exception {
        try(Fixture f=new Fixture(A);HostServerClient c=f.client()){
            c.connect(1000);HostServerClient.Device original=c.snapshot().devices.get(0);AdbSession s=session(c,0);
            AdbStream held=s.open("fixture:hold",10000);Async blocked=async(()->held.input().read());
            f.update("SERIAL_A\toffline transport_id:7\n");await(()->s.state()==AdbSession.State.FAILED,"offline invalidates existing session");
            blocked.awaitFailure();expectIo(()->new AdbSession(c,original,null).connect(),"offline stale selection rejected");
            f.update(A);await(()->c.snapshot().devices.get(0).canConnect(),"device returns online");
            expectIo(()->new AdbSession(c,original,null).connect(),"same id returning from offline has new incarnation");
            try(AdbSession again=session(c,0)){
                f.update("SERIAL_A\tdevice transport_id:22\n");await(()->again.state()==AdbSession.State.FAILED,"same serial new transport invalidates old binding");
            }
            HostServerClient.Device beforeRestart=c.snapshot().devices.get(0);AdbSession before=session(c,0);
            f.dropTrackers();await(()->c.state()==HostServerClient.State.FAILED,"server tracker EOF invalidates epoch");
            await(()->before.state()==AdbSession.State.FAILED,"server loss invalidates bound session");check(!c.snapshot().valid,"failed server keeps explicitly invalid snapshot");
            expectIo(()->c.connect(1000),"failed epoch cannot reconnect implicitly");
            try(HostServerClient replacement=f.client()){
                replacement.connect(1000);expectIo(()->new AdbSession(replacement,beforeRestart,null).connect(),"new epoch rejects prior selection even with identical ids");
                try(AdbSession fresh=session(replacement,0)){eq(AdbSession.State.CONNECTED,fresh.state(),"explicit fresh selection can retry");}
            }
            held.close();s.close();before.close();
        }
    }
    private static void cancellationIsolationAndRetry()throws Exception {
        try(Fixture f=new Fixture(A);HostServerClient c=f.client()){
            c.connect(1000);try(AdbSession s=session(c,0)){
                Gate refresh=new Gate();f.nextRefresh.set(refresh);OperationScope scope=new OperationScope();Async task=async(()->c.refresh(10000,scope));
                refresh.await();scope.close();task.awaitFailure();
                eq(HostServerClient.State.CONNECTED,c.state(),"cancel refresh does not close shared server client");
                eq(AdbSession.State.CONNECTED,s.state(),"cancel refresh preserves active device session");
                refresh.release.countDown();try(OperationScope retry=new OperationScope()){eq(1,c.refresh(1000,retry).devices.size(),"refresh retry succeeds");}
                try(OperationScope cancelled=new OperationScope()){cancelled.close();int before=f.count("host:devices-l");expectIo(()->c.refresh(1000,cancelled),"pre-cancelled refresh fails");eq(before,f.count("host:devices-l"),"pre-cancel zero requests");}
            }
        }
    }
    private static void cancelHandshakeAndService()throws Exception {
        try(Fixture f=new Fixture(A)){
            Gate track=new Gate();f.nextTracker.set(track);HostServerClient c=f.client();OperationScope scope=new OperationScope();scope.own(c);
            Async connect=async(()->{c.connect(10000);return null;});track.await();scope.close();connect.awaitFailure();track.release.countDown();
            eq(HostServerClient.State.CLOSED,c.state(),"close cancels tracker handshake without resurrection");
            try(HostServerClient retry=f.client()){
                retry.connect(1000);Gate features=new Gate();f.nextFeatures.set(features);AdbSession s=new AdbSession(retry,retry.snapshot().devices.get(0),null);
                Async selecting=async(()->{s.connect();return null;});features.await();s.close();selecting.awaitFailure();features.release.countDown();
                eq(AdbSession.State.CLOSED,s.state(),"cancel selected feature handshake stays closed");
                try(AdbSession actual=session(retry,0)){
                    Gate service=new Gate();f.nextTransport.set(service);Async opening=async(()->actual.open("fixture:echo",10000));service.await();actual.close();opening.awaitFailure();service.release.countDown();
                    eq(AdbSession.State.CLOSED,actual.state(),"cancel exact pending service socket");
                }
                eq(HostServerClient.State.CONNECTED,retry.state(),"session cancellation leaves tracker usable");
            }
        }
    }
    private static void staleRefreshReplies()throws Exception {
        try(Fixture f=new Fixture(A);HostServerClient c=f.client()){
            c.connect(1000);try(AdbSession s=session(c,0);OperationScope oldScope=new OperationScope()){
                Gate delayed=new Gate();f.nextRefresh.set(delayed);Async old=async(()->c.refresh(10000,oldScope));delayed.await();
                f.update(B);await(()->c.snapshot().devices.get(0).serial.equals("SERIAL_B"),"tracker publishes new list");
                delayed.release.countDown();old.awaitSuccess();eq("SERIAL_B",c.snapshot().devices.get(0).serial,"old refresh cannot overwrite newer tracker event");
                eq(AdbSession.State.FAILED,s.state(),"stale reply never revives invalidated session");
            }
            Gate first=new Gate();f.nextRefresh.set(first);try(OperationScope earlier=new OperationScope();OperationScope later=new OperationScope()){
                Async request=async(()->c.refresh(10000,earlier));first.await();f.list=A;
                c.refresh(1000,later);first.release.countDown();request.awaitSuccess();eq("SERIAL_A",c.snapshot().devices.get(0).serial,"older refresh cannot overwrite newer refresh");
            }
        }
    }
    private static void stopResults()throws Exception {
        try(Fixture f=new Fixture(A);HostServerClient c=f.client();OperationScope scope=new OperationScope()){
            c.connect(1000);AdbSession s=session(c,0);f.rejectKill=true;
            try{c.stopServer(1000,scope);throw new AssertionError("expected refusal");}catch(HostServerClient.ServerFailure e){assertions++;}
            eq(HostServerClient.State.CONNECTED,c.state(),"kill refusal keeps tracker usable");
            eq(AdbSession.State.CONNECTED,s.state(),"kill refusal preserves session");f.rejectKill=false;c.stopServer(1000,scope);
            eq(1,f.acceptedKills.get(),"one accepted kill request");eq(HostServerClient.State.FAILED,c.state(),"accepted stop does not pretend restart");eq(AdbSession.State.FAILED,s.state(),"stop invalidates bound session");
            expectIo(()->c.stopServer(1000,scope),"repeated stop on old epoch rejected");eq(2,f.count("host:kill"),"no automatic repeated kill");s.close();
        }
        try(Fixture f=new Fixture(A);HostServerClient c=f.client();OperationScope scope=new OperationScope()){
            c.connect(1000);f.dropKillResponse=true;
            try{c.stopServer(1000,scope);throw new AssertionError("expected unknown");}catch(HostServerClient.StopOutcomeUnknown e){assertions++;}
            eq(1,f.count("host:kill"),"unknown outcome not retried");eq(HostServerClient.State.FAILED,c.state(),"unknown stop invalidates epoch");
        }
    }
    private static void malformedAndRejectedResponses()throws Exception {
        for(String bad:new String[]{"broken\n","A device transport_id:0\n","A device transport_id:18446744073709551616\n","A device transport_id:7\nB device transport_id:7\n","A device transport_id:7 transport_id:8\n","A device\0\n"}){
            try(Fixture f=new Fixture(bad);HostServerClient c=f.client()){expectIo(()->c.connect(1000),"malformed enumeration rejected");eq(HostServerClient.State.FAILED,c.state(),"bad enumeration leaves no connected state");}
        }
        try(Fixture f=new Fixture("A device transport_id:18446744073709551615\n");HostServerClient c=f.client()){
            c.connect(1000);eq("18446744073709551615",c.snapshot().devices.get(0).transportId,"unsigned transport id not truncated");
        }
        for(byte[] reply:new byte[][]{"NOPE".getBytes(StandardCharsets.US_ASCII),"OKAYzzzz".getBytes(StandardCharsets.US_ASCII),"OKAY0008abc".getBytes(StandardCharsets.US_ASCII),new byte[]{'O','K','A','Y','0','0','0','1',(byte)0xff},"FAIL0007blocked".getBytes(StandardCharsets.US_ASCII)}){
            try(Fixture f=new Fixture(A);HostServerClient c=f.client()){f.rawTrackerReply=reply;expectIo(()->c.connect(1000),"malformed/truncated/FAIL response rejected");}
        }
        try(Fixture f=new Fixture(A);HostServerClient c=f.client()){
            Gate wait=new Gate();f.nextTracker.set(wait);expectIo(()->c.connect(50),"bounded handshake timeout");wait.release.countDown();eq(HostServerClient.State.FAILED,c.state(),"timeout terminal state");
        }
        try(Fixture f=new Fixture(A);HostServerClient c=f.client()){
            c.connect(1000);f.rejectTransport=true;try(AdbSession s=session(c,0)){expectIo(()->s.open("fixture:echo",1000),"transport FAIL propagated");check(!f.hasRequestPrefix("fixture:echo"),"rejected transport sends no device service");}
        }
    }
    private static AdbSession session(HostServerClient c,int index)throws IOException{AdbSession s=new AdbSession(c,c.snapshot().devices.get(index),null);s.connect();return s;}
    private interface Task{Object run()throws Exception;}
    private interface Throwing{void run()throws Exception;}
    private interface Condition{boolean yes();}
    private static final class Async{
        final AtomicReference<Throwable> error=new AtomicReference<>();final CountDownLatch done=new CountDownLatch(1);final Thread thread;
        Async(Task task){thread=new Thread(()->{try{task.run();}catch(Throwable t){error.set(t);}finally{done.countDown();}},"synthetic-host-test-task");thread.setDaemon(true);thread.start();}
        void awaitFailure()throws Exception{check(done.await(3,TimeUnit.SECONDS),"cancellation completes promptly");check(error.get() instanceof IOException,"operation fails with IOException");}
        void awaitSuccess()throws Exception{check(done.await(3,TimeUnit.SECONDS),"operation completed");if(error.get()!=null)throw new AssertionError("async failed",error.get());assertions++;}
    }
    private static Async async(Task task){return new Async(task);}
    private static void await(Condition condition,String label)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.yes()&&System.nanoTime()<until)Thread.sleep(5);check(condition.yes(),label);}
    private static void expectIo(Throwing call,String label)throws Exception{try{call.run();throw new AssertionError(label+": did not fail");}catch(IOException e){assertions++;}}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);assertions++;}
    private static void eq(Object a,Object b,String label){check(Objects.equals(a,b),label+" expected="+a+" actual="+b);}
    private static final class Gate{
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        void block()throws InterruptedException{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("fixture gate timeout");}
        void await()throws Exception{check(entered.await(3,TimeUnit.SECONDS),"fixture endpoint reached cancellation gate");}
    }
    private static final class Fixture implements Closeable{
        final ServerSocket server;final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<Socket>());
        final Set<Socket> trackers=Collections.synchronizedSet(new HashSet<Socket>());
        final List<String> requests=Collections.synchronizedList(new ArrayList<String>());
        final AtomicReference<Gate> nextTracker=new AtomicReference<>(),nextRefresh=new AtomicReference<>(),nextFeatures=new AtomicReference<>(),nextTransport=new AtomicReference<>();
        final AtomicInteger acceptedKills=new AtomicInteger();volatile String list;volatile boolean closed,rejectKill,dropKillResponse,rejectTransport;volatile byte[] rawTrackerReply;
        Fixture(String list)throws IOException{
            this.list=list;server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
            Thread accepting=new Thread(()->{while(!closed){try{final Socket socket=server.accept();sockets.add(socket);Thread worker=new Thread(()->handle(socket),"synthetic-host-endpoint");worker.setDaemon(true);worker.start();}catch(IOException e){if(!closed)throw new RuntimeException(e);}}},"synthetic-host-listener");accepting.setDaemon(true);accepting.start();
        }
        String endpoint(){return "127.0.0.1:"+server.getLocalPort();}
        HostServerClient client(){return new HostServerClient("127.0.0.1",server.getLocalPort());}
        int count(String command){int count=0;synchronized(requests){for(String s:requests)if(s.equals(command))count++;}return count;}
        boolean hasRequestPrefix(String command){synchronized(requests){for(String s:requests)if(s.startsWith(command))return true;}return false;}
        void update(String value)throws IOException{list=value;synchronized(trackers){for(Socket s:trackers)payload(s.getOutputStream(),value);}}
        void dropTrackers()throws IOException{synchronized(trackers){for(Socket s:trackers)s.close();trackers.clear();}}
        void gate(AtomicReference<Gate> next)throws InterruptedException{Gate g=next.getAndSet(null);if(g!=null)g.block();}
        void handle(Socket s){
            try{
                s.setSoTimeout(10000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();String request=request(in);requests.add(request);
                if(request.equals("host:track-devices-l")){
                    gate(nextTracker);if(rawTrackerReply!=null){out.write(rawTrackerReply);out.flush();return;}
                    synchronized(trackers){out.write(ascii("OKAY"));payload(out,list);trackers.add(s);}while(in.read()!=-1){}return;
                }
                if(request.equals("host:devices-l")){String captured=list;gate(nextRefresh);out.write(ascii("OKAY"));payload(out,captured);return;}
                if(request.startsWith("host-transport-id:")&&request.endsWith(":features")){gate(nextFeatures);out.write(ascii("OKAY"));payload(out,"shell_v2,cmd");return;}
                if(request.equals("host:kill")){if(dropKillResponse)return;if(rejectKill){out.write(ascii("FAIL"));payload(out,"stop refused");return;}acceptedKills.incrementAndGet();out.write(ascii("OKAY"));out.flush();dropTrackers();return;}
                if(request.startsWith("host:transport-id:")){
                    gate(nextTransport);if(rejectTransport){out.write(ascii("FAIL"));payload(out,"device offline");return;}out.write(ascii("OKAY"));out.flush();String service=request(in);requests.add(service);out.write(ascii("OKAY"));out.flush();
                    if(service.startsWith("shell,v2,raw:")){out.write(frame(1,ascii("fixture\n")));out.write(frame(3,new byte[]{0}));return;}
                    if(service.equals("sync:")){String id=new String(AdbPacket.readExactly(in,4),StandardCharsets.US_ASCII);int length=ByteBuffer.wrap(AdbPacket.readExactly(in,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();AdbPacket.readExactly(in,length);if(!id.equals("STAT"))throw new IOException("unexpected synthetic sync");out.write(ascii("STAT"));out.write(le(0100644));out.write(le(42));out.write(le(5));out.flush();while(in.read()!=-1){}return;}
                    if(service.equals("fixture:echo")){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){out.write(b,0,n);out.flush();}return;}
                    if(service.equals("fixture:hold")){while(in.read()!=-1){}return;}
                    throw new IOException("Unexpected synthetic service "+service);
                }
                throw new IOException("Unexpected synthetic host request "+request);
            }catch(IOException ignored){/* Cancellations intentionally reset these local sockets. */}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{trackers.remove(s);sockets.remove(s);try{s.close();}catch(IOException ignored){}}
        }
        public void close()throws IOException{closed=true;server.close();synchronized(sockets){for(Socket s:sockets)s.close();}for(AtomicReference<Gate> reference:Arrays.asList(nextTracker,nextRefresh,nextFeatures,nextTransport)){Gate g=reference.get();if(g!=null)g.release.countDown();}}
    }
    private static String request(InputStream in)throws IOException{int length=Integer.parseInt(new String(AdbPacket.readExactly(in,4),StandardCharsets.US_ASCII),16);return new String(AdbPacket.readExactly(in,length),StandardCharsets.UTF_8);}
    private static void payload(OutputStream out,String text)throws IOException{byte[] b=text.getBytes(StandardCharsets.UTF_8);out.write(String.format(Locale.ROOT,"%04x",b.length).getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();}
    private static byte[] ascii(String text){return text.getBytes(StandardCharsets.US_ASCII);}
    private static byte[] le(int n){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();}
    private static byte[] frame(int id,byte[] data)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(id);out.write(le(data.length));out.write(data);return out.toByteArray();}
}
