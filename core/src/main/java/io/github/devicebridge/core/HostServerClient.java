package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Client of an explicitly configured external ADB smart-socket server.
 * It never starts a daemon, listens, scans, pairs, or connects new devices.
 * Protocol: AOSP docs/dev/services.md, adb.cpp and services.cpp.
 * A continuous track-devices-l connection bounds one server epoch. A failed
 * client is terminal: create a new client and explicitly select a fresh device.
 */
public final class HostServerClient implements Closeable {
    public enum State { NEW, CONNECTING, CONNECTED, FAILED, CLOSED }
    public interface Listener { void changed(State state, Snapshot snapshot, String detail); }
    public static final class Device {
        public final String serial, state, model, product, device, transportId;
        private final HostServerClient owner;
        private final Object incarnation;
        private Device(HostServerClient owner, Parsed p, Object incarnation) {
            this.owner=owner;this.incarnation=incarnation;serial=p.serial;state=p.state;
            model=p.model;product=p.product;device=p.device;transportId=p.id;
        }
        public boolean canConnect(){return "device".equals(state)&&!transportId.isEmpty();}
        public String label(){return (model.isEmpty()?serial:model+" ("+serial+")")+" · "+state;}
    }
    public static final class Snapshot {
        public final List<Device> devices;
        public final long revision;
        public final boolean valid;
        private Snapshot(List<Device> devices,long revision,boolean valid){this.devices=Collections.unmodifiableList(devices);this.revision=revision;this.valid=valid;}
    }
    public static final class ServerFailure extends IOException {
        private static final long serialVersionUID=1L;
        public ServerFailure(String message){super(message);}
    }
    /** The server might already have stopped; do not automatically repeat kill. */
    public static final class StopOutcomeUnknown extends IOException {
        private static final long serialVersionUID=1L;
        StopOutcomeUnknown(IOException cause){super("ADB server stop outcome is unknown; the request may have reached the server. Check it before retrying.",cause);}
    }
    private final String host,endpoint;private final int port;
    private final OperationScope lifetime=new OperationScope();
    private final Set<Binding> bindings=Collections.newSetFromMap(new IdentityHashMap<Binding,Boolean>());
    private volatile State state=State.NEW;private volatile Listener listener;
    private Snapshot snapshot=new Snapshot(new ArrayList<Device>(),0,false);
    private long refreshSequence,trackSequence;private boolean stopping;private Connection pendingStop;
    private final CountDownLatch terminated=new CountDownLatch(1);private volatile Thread terminatingThread;

    public HostServerClient(String host,int port) {
        if(host==null||host.trim().isEmpty()||!host.equals(host.trim())||host.indexOf('\0')>=0||port<1||port>65535)throw new IllegalArgumentException("Invalid ADB server endpoint");
        this.host=host;this.port=port;endpoint=(host.indexOf(':')>=0?"["+host+"]":host)+":"+port;
    }
    public String endpoint(){return endpoint;}
    public State state(){return state;}
    public synchronized Snapshot snapshot(){return snapshot;}
    public void setListener(Listener listener){this.listener=listener;}
    private void notifyListener(String detail){Listener l=listener;if(l!=null)try{l.changed(state,snapshot(),detail);}catch(RuntimeException ignored){/* Observers cannot stop transport cleanup. */}}

    /** The caller must own this client before calling, so cancellation closes the handshake. */
    public void connect(int timeoutMs)throws IOException {
        synchronized(this){if(state!=State.NEW)throw new IOException("ADB server client already used; create a new client to retry");state=State.CONNECTING;}
        notifyListener("Connecting to ADB server "+endpoint);
        try {
            final Connection tracker=connection(lifetime,timeoutMs);
            send(tracker,"host:track-devices-l");status(tracker);
            List<Parsed> first=parseList(readString(tracker));
            synchronized(this){if(state!=State.CONNECTING)throw new InterruptedIOException("ADB server connection cancelled");state=State.CONNECTED;applyLocked(first);}
            tracker.setReadTimeout(0);
            notifyListener("ADB server devices ready");
            Thread thread=new Thread(()->{
                try {while(state==State.CONNECTED){List<Parsed> parsed=parseList(readString(tracker));publishTracked(parsed);}}
                catch(IOException e){if(state==State.CONNECTED)fail(e);}
            },"devicebridge-host-tracker");thread.setDaemon(true);thread.start();
        } catch(IOException e){fail(e);throw e;}
    }
    /** Read devices-l again. A newer tracker event or refresh wins over an older reply. */
    public Snapshot refresh(int timeoutMs,OperationScope scope)throws IOException {
        final long request,tracked;
        synchronized(this){requireConnected();request=++refreshSequence;tracked=trackSequence;}
        scope.checkActive();
        List<Parsed> result;
        try(Connection c=connection(scope,timeoutMs)){send(c,"host:devices-l");status(c);result=parseList(readString(c));scope.checkActive();}
        List<Binding> removed;
        synchronized(this){requireConnected();scope.checkActive();if(request!=refreshSequence||tracked!=trackSequence)return snapshot;removed=applyLocked(result);}
        invalidate(removed,"ADB server device is offline, removed, or changed");notifyListener("ADB server device list refreshed");return snapshot();
    }
    private void publishTracked(List<Parsed> parsed)throws IOException {
        List<Binding> removed;
        synchronized(this){if(state!=State.CONNECTED)throw new IOException("ADB server tracker closed");trackSequence++;removed=applyLocked(parsed);}
        invalidate(removed,"ADB server device is offline, removed, or changed");notifyListener("ADB server device list changed");
    }
    private List<Binding> applyLocked(List<Parsed> parsed) {
        List<Device> next=new ArrayList<>();
        for(Parsed p:parsed){Object incarnation=new Object();for(Device old:snapshot.devices)if(old.canConnect()&&"device".equals(p.state)&&old.serial.equals(p.serial)&&old.transportId.equals(p.id)){incarnation=old.incarnation;break;}next.add(new Device(this,p,incarnation));}
        snapshot=new Snapshot(next,snapshot.revision+1,true);
        List<Binding> removed=new ArrayList<>();for(Binding b:bindings)if(!currentLocked(b.selected))removed.add(b);
        for(Binding b:removed)bindings.remove(b);return removed;
    }
    private void requireConnected()throws IOException {if(state!=State.CONNECTED||stopping)throw new IOException("ADB server is not connected; refresh using a new server connection");}
    private boolean currentLocked(Device selected){if(selected==null||selected.owner!=this||!selected.canConnect()||!snapshot.valid)return false;for(Device d:snapshot.devices)if(d.incarnation==selected.incarnation&&d.canConnect())return true;return false;}
    private synchronized void requireCurrent(Device selected)throws IOException {requireConnected();if(!currentLocked(selected))throw new IOException("Device selection expired or is offline; refresh and select again");}
    Binding binding(Device selected,Runnable invalidated){return new Binding(selected,invalidated);}

    /**
     * Sends host:kill once. OKAY means the server accepted stopping, NOT that it
     * was restarted. This interrupts every client using that external server.
     * The public host protocol has no remote start-server operation.
     */
    public void stopServer(int timeoutMs,OperationScope scope)throws IOException {
        synchronized(this){requireConnected();stopping=true;}
        boolean submitted=false;
        try(Connection c=connection(scope,timeoutMs)) {
            synchronized(this){if(state!=State.CONNECTED)throw new IOException("ADB server disconnected before stop was sent");pendingStop=c;lifetime.release(c);}
            // Tracker EOF can race the kill OKAY. Let this bounded, separately
            // cancellable control socket read its result rather than closing it
            // during device-session cleanup.
            scope.checkActive();submitted=true;send(c,"host:kill");status(c);
            fail(new IOException("External ADB server accepted stop; all old selections are invalid. Start it on its host before reconnecting."));
        }catch(ServerFailure e){synchronized(this){stopping=false;}throw e;}
        catch(IOException e){synchronized(this){stopping=false;}if(submitted){fail(e);throw new StopOutcomeUnknown(e);}throw e;}
        finally{synchronized(this){pendingStop=null;}}
    }
    private void invalidate(List<Binding> list,String detail){for(Binding b:list)try{b.invalidate(detail);}catch(RuntimeException ignored){/* One observer must not leak sibling sockets. */}}
    private void fail(IOException e){terminate(State.FAILED,e.getMessage());}
    private void terminate(State target,String detail){
        List<Binding> old=null;
        synchronized(this){if(state!=State.CLOSED&&state!=State.FAILED){terminatingThread=Thread.currentThread();state=target;refreshSequence++;trackSequence++;snapshot=new Snapshot(new ArrayList<Device>(snapshot.devices),snapshot.revision+1,false);old=new ArrayList<>(bindings);bindings.clear();}}
        if(old==null){
            // In particular, stopServer's OKAY must not return before a racing
            // tracker EOF has closed every old session.
            if(Thread.currentThread()!=terminatingThread){boolean interrupted=false;while(true){try{terminated.await();break;}catch(InterruptedException e){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();}return;
        }
        try{lifetime.close();invalidate(old,detail);}finally{terminated.countDown();}
        notifyListener(detail);
    }
    public void close(){Connection stop;synchronized(this){stop=pendingStop;}if(stop!=null)try{stop.close();}catch(IOException ignored){}terminate(State.CLOSED,"External ADB server connection closed");}

    final class Binding implements Closeable {
        private final Device selected;private final Runnable invalidated;
        private final OperationScope channels=new OperationScope();
        private volatile boolean registered,closed;private volatile String failure="Host server session closed";
        Binding(Device selected,Runnable invalidated){this.selected=selected;this.invalidated=invalidated;}
        String target(){return "ADB server "+endpoint+" / "+selected.serial+" #"+selected.transportId;}
        String connect(int timeoutMs)throws IOException {
            synchronized(HostServerClient.this){requireCurrent(selected);if(closed)throw new IOException(failure);bindings.add(this);registered=true;}
            try(Connection c=connection(channels,timeoutMs)){
                send(c,"host-transport-id:"+selected.transportId+":features");status(c);String features=readString(c);
                if(!features.matches("[a-zA-Z0-9_,.-]*"))throw new IOException("Invalid ADB feature list");
                check();return "device::features="+features+";";
            }catch(IOException e){close();throw e;}
        }
        private void check()throws IOException {if(closed||!registered)throw new IOException(failure);channels.checkActive();requireCurrent(selected);}
        Transport open(String service,int timeoutMs)throws IOException {
            if(service.startsWith("host:")||service.startsWith("host-"))throw new IOException("Host-wide commands are not device services");
            check();Connection c=connection(channels,timeoutMs);
            try{send(c,"host:transport-id:"+selected.transportId);status(c);check();send(c,service);status(c);check();return c;}
            catch(IOException e){c.close();throw e;}
        }
        private void invalidate(String reason){if(closed)return;failure=reason;close();invalidated.run();}
        public void close(){closed=true;synchronized(HostServerClient.this){bindings.remove(this);}channels.close();}
    }

    /** Socket exists and is owned before connect/read begins, so close unblocks cancellation. */
    private Connection connection(OperationScope operation,int timeoutMs)throws IOException {
        if(timeoutMs<=0)throw new IllegalArgumentException("Positive timeout required");
        operation.checkActive();lifetime.checkActive();Connection c=new Connection(operation);
        try{lifetime.own(c);operation.own(c);c.socket.connect(new InetSocketAddress(host,port),timeoutMs);c.socket.setTcpNoDelay(true);c.socket.setSoTimeout(timeoutMs);operation.checkActive();lifetime.checkActive();return c;}
        catch(IOException|RuntimeException e){c.close();throw e;}
    }
    private final class Connection implements Transport {
        private final Socket socket=new Socket();private final OperationScope owner;
        Connection(OperationScope owner){this.owner=owner;}
        public InputStream input()throws IOException{return socket.getInputStream();}
        public OutputStream output()throws IOException{return socket.getOutputStream();}
        public String description(){return "ADB server "+endpoint;}
        public void setReadTimeout(int timeout)throws IOException{socket.setSoTimeout(timeout);}
        public void close()throws IOException{try{socket.close();}finally{owner.release(this);lifetime.release(this);}}
    }
    static void send(Transport c,String request)throws IOException {
        if(request==null||request.isEmpty()||request.indexOf('\0')>=0)throw new IOException("Invalid ADB host request");
        byte[] data=request.getBytes(StandardCharsets.UTF_8);if(data.length>65535)throw new IOException("ADB host request too long");
        String size=Integer.toHexString(data.length);StringBuilder prefix=new StringBuilder(4);for(int i=size.length();i<4;i++)prefix.append('0');prefix.append(size);
        OutputStream out=c.output();out.write(prefix.toString().getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
    }
    static void status(Transport c)throws IOException {
        String status=new String(AdbPacket.readExactly(c.input(),4),StandardCharsets.US_ASCII);
        if("OKAY".equals(status))return;if("FAIL".equals(status))throw new ServerFailure(readString(c));throw new IOException("Invalid ADB host status");
    }
    static String readString(Transport c)throws IOException {
        byte[] h=AdbPacket.readExactly(c.input(),4);int length=0;
        for(byte b:h){int v=b>='0'&&b<='9'?b-'0':b>='a'&&b<='f'?b-'a'+10:b>='A'&&b<='F'?b-'A'+10:-1;if(v<0)throw new IOException("Invalid ADB host length");length=(length<<4)|v;}
        byte[] data=AdbPacket.readExactly(c.input(),length);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();}
        catch(CharacterCodingException e){throw new IOException("Invalid UTF-8 from ADB server",e);}
    }
    private static final class Parsed {String serial,state,model="",product="",device="",id="";}
    private static List<Parsed> parseList(String value)throws IOException {
        LinkedHashMap<String,Parsed> result=new LinkedHashMap<>();Set<String> ids=new HashSet<>();
        if(value.indexOf('\0')>=0)throw new IOException("Invalid device list");
        for(String line:value.split("\n")){
            if(line.trim().isEmpty())continue;String[] parts=line.trim().split("\\s+");if(parts.length<2)throw new IOException("Malformed device list row");
            Parsed p=new Parsed();p.serial=parts[0];p.state=parts[1];
            if("no".equals(p.state)&&parts.length>2&&"permissions".equals(parts[2]))p.state="no permissions";
            for(int i=2;i<parts.length;i++){String token=parts[i];if(token.startsWith("model:"))p.model=token.substring(6);else if(token.startsWith("product:"))p.product=token.substring(8);else if(token.startsWith("device:"))p.device=token.substring(7);else if(token.startsWith("transport_id:")){if(!p.id.isEmpty())throw new IOException("Duplicate transport id field");p.id=token.substring(13);if(!validId(p.id))throw new IOException("Invalid device transport id");}}
            String key=p.id.isEmpty()?"serial:"+p.serial:"id:"+p.id;Parsed old=result.get(key);
            if(old!=null){if(!same(old,p))throw new IOException("Conflicting duplicate ADB device");continue;}
            if(!p.id.isEmpty()&&!ids.add(p.id))throw new IOException("Duplicate ADB transport id");result.put(key,p);
        }
        return new ArrayList<>(result.values());
    }
    private static boolean same(Parsed a,Parsed b){return a.serial.equals(b.serial)&&a.state.equals(b.state)&&a.model.equals(b.model)&&a.product.equals(b.product)&&a.device.equals(b.device)&&a.id.equals(b.id);}
    private static boolean validId(String id){if(!id.matches("[1-9][0-9]{0,19}"))return false;return id.length()<20||id.compareTo("18446744073709551615")<=0;}
}
