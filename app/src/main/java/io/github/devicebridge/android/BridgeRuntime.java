package io.github.devicebridge.android;
import android.app.Application;import android.os.Handler;import android.os.Looper;import io.github.devicebridge.core.*;import java.io.*;import java.security.*;import java.util.*;import java.util.concurrent.*;
public final class BridgeRuntime extends Application {
    public static final java.util.concurrent.ExecutorService CLOSERS=java.util.concurrent.Executors.newCachedThreadPool(r->{Thread thread=new Thread(r,"goldenbeetle-resource-close");thread.setDaemon(true);return thread;});

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        androidx.multidex.MultiDex.install(this);
    }
    private static BridgeRuntime instance;
    public static final ExecutorService IO=Executors.newFixedThreadPool(6);
    public static final Handler UI=new Handler(Looper.getMainLooper());
    private final Map<String,AdbSession> sessions=new LinkedHashMap<>();
    private HostServerClient externalServer;
    public synchronized HostServerClient externalServer(){return externalServer;}
    /** CAS keeps a late connection result from replacing a newer selected server. */
    public boolean replaceExternalServer(HostServerClient expected,HostServerClient next){
        synchronized(this){if(externalServer!=expected)return false;externalServer=next;}
        if(expected!=null&&expected!=next){expected.setListener(null);CLOSERS.execute(expected::close);}
        return true;
    }
    private String active;private final List<String> pendingReports=new ArrayList<>();
    public synchronized void report(String message){pendingReports.add(message);}
    public synchronized String takeReports(){String s=String.join("\n\n",pendingReports);pendingReports.clear();return s;}
    private AdbIdentity identity;
    public static BridgeRuntime get(){return instance;}
    public void onCreate(){super.onCreate();instance=this;
        Thread.UncaughtExceptionHandler old=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{try(FileOutputStream o=openFileOutput("crash.log",MODE_PRIVATE)){String text=e.getClass().getName()+"\n";for(StackTraceElement s:e.getStackTrace())text+=s.toString()+"\n";o.write(text.getBytes("UTF-8"));}catch(Exception ignored){}if(old!=null)old.uncaughtException(t,e);});
    }
    public synchronized AdbIdentity identity()throws Exception{
        if(identity!=null)return identity;File directory=Compatibility.noBackupFilesDir(this);File pri=new File(directory,"adb-identity.pk8"),pub=new File(directory,"adb-identity.pub");
        if(pri.exists()&&pub.exists()){identity=AdbIdentity.restore(read(pri),read(pub));return identity;}
        identity=AdbIdentity.generate();write(pri,identity.privateEncoded());write(pub,identity.publicEncoded());return identity;
    }
    private static byte[] read(File f)throws IOException{try(FileInputStream i=new FileInputStream(f);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]b=new byte[4096];int n;while((n=i.read(b))>=0)o.write(b,0,n);return o.toByteArray();}}
    private static void write(File f,byte[] b)throws IOException{try(FileOutputStream o=new FileOutputStream(f)){o.write(b);o.getFD().sync();}f.setReadable(false,false);f.setWritable(false,false);f.setReadable(true,true);f.setWritable(true,true);}
    public void add(AdbSession s){
        AdbSession previous;synchronized(this){previous=sessions.put(s.target(),s);active=s.target();}
        if(previous!=null&&previous!=s)CLOSERS.execute(previous::close);
    }
    public synchronized void select(String target){active=target;}
    public synchronized boolean select(AdbSession session){if(session==null||sessions.get(session.target())!=session)return false;active=session.target();return true;}
    public boolean disconnect(AdbSession session){
        synchronized(this){if(session==null||sessions.get(session.target())!=session)return false;sessions.remove(session.target());if(session.target().equals(active))active=null;}
        CLOSERS.execute(session::close);return true;
    }

    public static AdbSession session(){BridgeRuntime app=get();synchronized(app){return app.sessions.get(app.active);}}
    public synchronized List<AdbSession> sessions(){return new ArrayList<>(sessions.values());}
    public void disconnect(String target){
        AdbSession s;synchronized(this){s=sessions.remove(target);if(target.equals(active))active=null;}
        if(s!=null)CLOSERS.execute(s::close);
    }
    public void disconnectAll(){
        List<AdbSession> removed;HostServerClient server;
        synchronized(this){removed=new ArrayList<>(sessions.values());sessions.clear();active=null;server=externalServer;externalServer=null;}
        for(AdbSession s:removed)CLOSERS.execute(s::close);
        if(server!=null){server.setListener(null);CLOSERS.execute(server::close);}
    }
}
