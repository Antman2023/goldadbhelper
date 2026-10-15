package io.github.devicebridge.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class AdbSession implements Closeable {
    public enum State { NEW, CONNECTING, AUTHENTICATING, CONNECTED, CLOSED, FAILED }
    public interface Listener { void changed(State state, String detail); }
    private final Transport transport;
    private final AdbIdentity identity;
    private final HostServerClient hostServer;private final HostServerClient.Binding hostBinding;
    private volatile Listener listener;
    private final Map<Integer,AdbStream> streams=new ConcurrentHashMap<>();
    private final AtomicInteger ids=new AtomicInteger(1);
    private final Object writer=new Object();private final Object stateLock=new Object();private volatile boolean closedByCaller;
    private volatile State state=State.NEW;
    private volatile int maxPayload=4096;private volatile int peerVersion=0x01000001;
    private volatile boolean checksum=true;
    private volatile String banner="";private boolean requireEncryption;
    public AdbSession(Transport transport,AdbIdentity identity,Listener listener) {
        this.transport=transport;this.identity=identity;this.listener=listener;this.hostServer=null;this.hostBinding=null;
    }
    /** Creates an ordinary session facade backed by a selected external-server transport. */
    public AdbSession(HostServerClient server,HostServerClient.Device device,Listener listener) {
        if(server==null||device==null)throw new IllegalArgumentException("Missing ADB server selection");
        this.transport=null;this.identity=null;this.listener=listener;this.hostServer=server;
        this.hostBinding=server.binding(device,()->fail(new IOException("External ADB server or selected device disconnected; select again")));
    }
    public boolean isHostServer(){return hostServer!=null;}
    public String serverEndpoint(){return hostServer==null?"":hostServer.endpoint();}
    public AdbSession requireEncryption(){if(state!=State.NEW)throw new IllegalStateException("Connection already started");if(hostServer!=null)throw new IllegalStateException("External ADB server transport encryption cannot be verified by this client");requireEncryption=true;return this;}
    public boolean isEncrypted(){return transport instanceof TlsUpgradableTransport&&((TlsUpgradableTransport)transport).isTls();}
    public void setListener(Listener value){listener=value;}
    public State state(){return state;}
    public String target(){return hostBinding==null?transport.description():hostBinding.target();}
    public String banner(){return banner;}
    public boolean hasFeature(String feature){
        int pos=banner.indexOf("features=");if(pos<0)return false;
        String tail=banner.substring(pos+9).split("[;\\x00]",2)[0];
        return Arrays.asList(tail.split(",")).contains(feature);
    }
    private void notifyState(State s,String detail){
        Listener current=listener;
        if(current!=null&&state==s)try{current.changed(s,detail);}catch(RuntimeException ignored){/* Observers cannot prevent transport cleanup or reader startup. */}
    }
    private void setState(State s,String detail){synchronized(stateLock){if(closedByCaller&&s!=State.CLOSED)return;state=s;}notifyState(s,detail);}
    private void markConnected()throws IOException{
        synchronized(stateLock){if(closedByCaller||state==State.FAILED)throw new IOException("Connection was cancelled or invalidated");state=State.CONNECTED;}
    }
    private void requireConnected()throws IOException{if(state!=State.CONNECTED)throw new IOException("Connection was cancelled or invalidated");}
    public synchronized void connect() throws IOException {
        if(state!=State.NEW)throw new IOException("Session already used");
        setState(State.CONNECTING,target());
        if(state!=State.CONNECTING)throw new IOException("Connection was cancelled");
        if(hostBinding!=null){
            try{banner=hostBinding.connect(15000);markConnected();notifyState(State.CONNECTED,banner);requireConnected();return;}
            catch(IOException e){fail(e);throw e;}
        }
        try {
            checkInterrupted();
            send(new AdbPacket(AdbPacket.CNXN,0x01000001,AdbPacket.MAX_PAYLOAD,AdbPacket.text("host::features=shell_v2,cmd\0")));
            int authAttempts=0;
            while(true){
                AdbPacket p=AdbPacket.read(transport.input(),AdbPacket.MAX_PAYLOAD,false);
                if(p.command==AdbPacket.CNXN){
                    if(requireEncryption&&!isEncrypted())throw new IOException("This saved wireless target requires TLS; plaintext connection rejected");
                    if(p.arg1<4096 || p.arg1>AdbPacket.MAX_PAYLOAD)throw new IOException("Invalid negotiated ADB payload limit");
                    if(p.arg0<0x01000000)throw new IOException("Unsupported ADB protocol version");peerVersion=p.arg0;maxPayload=p.arg1;checksum=p.arg0<0x01000001;
                    banner=new String(p.payload,StandardCharsets.UTF_8);
                    transport.setReadTimeout(0);
                    markConnected();
                    Thread t=new Thread(this::readLoop,"devicebridge-adb-reader");t.setDaemon(true);t.start();
                    notifyState(State.CONNECTED,banner.replace("\0",""));requireConnected();return;
                }
                if(p.command==AdbPacket.STLS){
                    if(!(transport instanceof TlsUpgradableTransport))throw new IOException("Secure wireless ADB requires pairing/TLS, not a legacy TCP connection");
                    TlsUpgradableTransport tls=(TlsUpgradableTransport)transport;
                    if(tls.isTls()||p.arg0!=0x01000000||p.arg1!=0||p.payload.length!=0)throw new IOException("Invalid or repeated ADB TLS request");
                    if(identity==null)throw new IOException("ADB TLS requires a client identity");
                    send(new AdbPacket(AdbPacket.STLS,0x01000000,0,null));tls.upgradeToTls(identity);continue;
                }
                if(requireEncryption&&!isEncrypted())throw new IOException("TLS is required before ADB authentication");
                if(p.command!=AdbPacket.AUTH || p.arg0!=1 || p.payload.length!=20)throw new IOException("Unexpected ADB handshake packet");
                if(++authAttempts>2)throw new IOException("ADB authentication rejected; approve this client on the target");
                if(identity==null)throw new IOException("ADB authentication requires a client identity");
                setState(State.AUTHENTICATING,"Approve Device Bridge on the target device");
                byte[] answer=authAttempts==1?identity.signToken(p.payload):identity.adbPublicKey();
                send(new AdbPacket(AdbPacket.AUTH,authAttempts==1?2:3,0,answer));
            }
        }catch(GeneralSecurityException|IOException e){fail(new IOException("ADB connection failed: "+e.getMessage(),e));throw new IOException(e.getMessage(),e);}
    }
    public AdbStream open(String service,int timeoutMs)throws IOException{
        if(timeoutMs<=0)throw new IllegalArgumentException("ADB stream timeout must be positive");
        if(state!=State.CONNECTED)throw new IOException("No connected ADB target");
        if(service==null || service.isEmpty() || service.indexOf('\0')>=0 || AdbPacket.text(service).length>4095)throw new IOException("Invalid ADB service");
        checkInterrupted();
        int id=ids.getAndIncrement();if(id<=0)throw new IOException("Session stream identifiers exhausted");
        if(hostBinding!=null){
            Transport channel=hostBinding.open(service,timeoutMs);AdbStream stream=new AdbStream(this,id,timeoutMs,channel);
            try{register(id,stream);}catch(IOException error){stream.fail(error);throw error;}return stream;
        }
        AdbStream s=new AdbStream(this,id,timeoutMs);register(id,s);
        try{send(new AdbPacket(AdbPacket.OPEN,id,0,AdbPacket.text(service+"\0")));s.awaitOpen();return s;}
        catch(IOException e){
            // OPEN may already be acknowledged when cancellation or a write error wins.
            // Close before forgetting/failing, so that acknowledged remote channels are released.
            try{s.close();}catch(IOException closeError){e.addSuppressed(closeError);}
            s.fail(e);throw e;
        }
    }
    private void register(int id,AdbStream stream)throws IOException{
        // Registration and terminal detachment must be atomic with respect to each other.
        synchronized(stateLock){requireConnected();streams.put(id,stream);}
    }
    private static void checkInterrupted()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("ADB operation cancelled");
    }
    void send(AdbPacket p)throws IOException{
        synchronized(writer){
            // Closing a cancelled stream must still notify its peer on an interruptible
            // transport. Preserve the caller's cancellation after cleanup I/O.
            boolean interrupted=p.command==AdbPacket.CLSE&&Thread.interrupted();
            try{transport.output().write(p.encode());transport.output().flush();}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    int maxPayload(){return maxPayload;}
    boolean allowsLegacyZeroClose(){return peerVersion==0x01000000;}
    void forget(int id){streams.remove(id);}
    private void readLoop(){
        try{while(state==State.CONNECTED){
            AdbPacket p=AdbPacket.read(transport.input(),maxPayload,checksum);
            AdbStream s=streams.get(p.arg1);
            if(s==null){if(p.command!=AdbPacket.CLSE)send(new AdbPacket(AdbPacket.CLSE,p.arg1,p.arg0,null));continue;}
            if(p.command==AdbPacket.OKAY)s.onOkay(p.arg0);
            else if(p.command==AdbPacket.WRTE)s.onData(p.arg0,p.payload);
            else if(p.command==AdbPacket.CLSE)s.onClose(p.arg0);
            else throw new IOException("Unexpected stream command");
        }}catch(IOException e){if(state!=State.CLOSED)fail(e);}
    }
    private void closeTransport(){if(hostBinding!=null)hostBinding.close();else try{transport.close();}catch(IOException ignored){}}
    private void fail(IOException e){
        List<AdbStream> affected;
        synchronized(stateLock){if(closedByCaller||state==State.FAILED)return;state=State.FAILED;affected=new ArrayList<>(streams.values());streams.clear();}
        for(AdbStream s:affected)s.fail(e);closeTransport();notifyState(State.FAILED,e.getMessage());
    }
    public void close(){
        List<AdbStream> affected;
        synchronized(stateLock){if(closedByCaller)return;closedByCaller=true;state=State.CLOSED;affected=new ArrayList<>(streams.values());streams.clear();}
        IOException failure=new IOException("Session disconnected");
        for(AdbStream s:affected)s.fail(failure);closeTransport();notifyState(State.CLOSED,"Disconnected");
    }
}
