package io.github.devicebridge.server;

import android.os.ParcelFileDescriptor;
import io.github.devicebridge.core.AndroidFileAccess;
import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.UUID;
import org.json.JSONObject;

/** Device-local copy, independent of toolbox/toybox flags. New final files use O_EXCL.
 * The user's selected parent directories remain held by descriptors for the whole operation.
 * Root parent aliases (for example /sdcard) are resolved once at open, then pinned. Source
 * entries and newly created destination directories use no-follow opens. Copying does not
 * preserve ownership, permissions, xattrs, timestamps, hardlinks or sparse allocation.
 */
public final class FileCopyServer {
    public static void main(String[] args) {
        int exit=1; Events events=null; FileCopyEngine engine=null; boolean mutationStarted=false;
        try {
            if(args.length!=3)throw new IOException("Expected request-id source destination");
            UUID.fromString(args[0]);events=new Events(args[0]);
            String source=path(args[1]),destination=path(args[2]);
            String sourceCanonical=new File(source).getCanonicalPath();
            String destinationCanonical=new File(destination).getCanonicalPath();
            if(destinationCanonical.equals(sourceCanonical)||destinationCanonical.startsWith(sourceCanonical+"/"))
                throw new IOException("Cannot copy an entry into itself or its own subtree");
            FileCommitServer.Fs platform=new FileCommitServer.LegacyAndroidFs();
            FileCommitServer.Fs responsive=new ResponsiveFs(platform,events);
            engine=new FileCopyEngine(responsive,events);
            boolean complete;
            try(BoundDirectory input=new BoundDirectory(parent(source),null,true);
                BoundDirectory output=new BoundDirectory(parent(destination),null,true)){
                FileCopyEngine.Node root=engine.plan(input,name(source),source,destination,0);
                events.check();mutationStarted=true;
                complete=engine.copy(root,input,output,name(destination));
            }
            events.done(engine.nodes.size(),complete,"");exit=complete?0:1;
        } catch(Exception failure){
            if(events!=null)try{
                if(engine!=null&&!mutationStarted)engine.abortPlanning(failure.getMessage());
                // A stream failure after mutation is never labelled a clean no-op.
                events.failed(engine==null?0:engine.nodes.size(),mutationStarted,failure.getMessage());
            }catch(IOException ignored){}
        }
        System.out.flush();System.exit(exit);
    }
    static String path(String value)throws IOException {
        if(value==null||!value.startsWith("/")||value.equals("/")||value.endsWith("/")||value.indexOf('\0')>=0)throw new IOException("Expected a non-root absolute entry path");
        for(String component:value.split("/"))if(component.equals(".")||component.equals(".."))throw new IOException("Dot components are not accepted");
        try{if(StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()>1024)throw new IOException("Path exceeds UTF-8 byte limit");}
        catch(CharacterCodingException e){throw new IOException("Malformed Unicode path",e);}return value;
    }
    private static String parent(String path){int at=path.lastIndexOf('/');return at==0?"/":path.substring(0,at);}
    private static String name(String path){return path.substring(path.lastIndexOf('/')+1);}
    static final class BoundDirectory implements FileCopyEngine.Directory {
        private final AndroidFileAccess fs=new AndroidFileAccess();
        private final ParcelFileDescriptor fd;
        BoundDirectory(String path,String expected,boolean rootParent)throws IOException {
            // /sdcard is a legitimate user-facing alias. Only selected parent aliases are
            // resolved this way; recursive children are always opened with O_NOFOLLOW.
            String openedPath=rootParent?(path.endsWith("/")?path+".":path+"/."):path;
            FileDescriptor opened=fs.openDirectory(openedPath);
            try {
                AndroidFileAccess.Stat stat=fs.fstat(opened);
                if(!stat.directoryToken().equals(fs.lstat(openedPath).directoryToken())
                        ||(expected!=null&&!expected.equals(stat.directoryToken())))throw new IOException("Source directory changed");
                fd=ParcelFileDescriptor.dup(opened);
            }finally{fs.close(opened);}
        }
        public String child(String name)throws IOException{return "/proc/self/fd/"+fd.getFd()+"/"+FileCopyEngine.component(name);}
        public FileCopyEngine.Info stat(String name)throws IOException {
            AndroidFileAccess.Stat stat=fs.lstat(child(name));
            return new FileCopyEngine.Info(stat.token(),stat.directoryToken(),stat.regular,stat.directory);
        }
        public FileCopyEngine.Directory open(String name,String expected)throws IOException{return new BoundDirectory(child(name),expected,false);}
        public FileCopyEngine.Directory create(String name)throws IOException {
            String path=child(name);fs.mkdir(path);
            try{return new BoundDirectory(path,null,false);}
            catch(IOException failure){throw new FileCopyEngine.CreatedDirectoryFailure(failure);}
        }
        public String[] names()throws IOException {
            String[] names=new File("/proc/self/fd/"+fd.getFd()).list();
            if(names==null)throw new IOException("Cannot list opened source directory");return names;
        }
        public void close()throws IOException{fd.close();}
    }
    private static final class Events implements FileCopyEngine.Observer {
        final String request;long heartbeat;
        Events(String request){this.request=request;}
        private JSONObject base(String event)throws Exception{return new JSONObject().put("gbcopy",1).put("request",request).put("event",event);}
        private void send(JSONObject object)throws IOException {
            System.out.println(object.toString());System.out.flush();
            if(System.out.checkError())throw new InterruptedIOException("Copy result channel closed; further work stopped");
        }
        public void check()throws IOException {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Copy cancelled");
            if(System.currentTimeMillis()-heartbeat>500){
                try{send(base("heartbeat"));heartbeat=System.currentTimeMillis();}
                catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
            }
        }
        public void event(String event,FileCopyEngine.Node node,String status,String message,FileCommitServer.Receipt receipt)throws IOException {
            try {
                JSONObject json=base(event).put("source",node.source).put("destination",node.destination).put("status",status).put("message",message);
                if(receipt!=null)json.put("bytes",receipt.bytes).put("sha256",receipt.sha256);
                send(json);
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
        void done(int count,boolean complete,String message)throws IOException{terminal(count,complete,false,false,message);}
        void failed(int count,boolean mutation,String message)throws IOException{terminal(count,false,true,mutation,message);}
        private void terminal(int count,boolean complete,boolean failed,boolean uncertain,String message)throws IOException {
            try{send(base("done").put("count",count).put("complete",complete).put("failed",failed).put("uncertain",uncertain).put("message",message==null?"":message));}
            catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
    }
    /** Notices cooperative cancellation while hashing/copying; a lost channel is still UNKNOWN
     * to the client because the last output may have reached disk before the lost receipt. */
    private static final class ResponsiveFs implements FileCommitServer.Fs {
        final FileCommitServer.Fs fs;final Events events;
        ResponsiveFs(FileCommitServer.Fs fs,Events events){this.fs=fs;this.events=events;}
        public FileDescriptor readOnly(String path)throws IOException{events.check();return fs.readOnly(path);}
        public FileDescriptor createExclusive(String path)throws IOException{events.check();return fs.createExclusive(path);}
        public FileCommitServer.Stat fstat(FileDescriptor fd)throws IOException{return fs.fstat(fd);}
        public FileCommitServer.Stat lstat(String path)throws IOException{return fs.lstat(path);}
        public int read(FileDescriptor fd,byte[] b)throws IOException{events.check();return fs.read(fd,b);}
        public int write(FileDescriptor fd,byte[] b,int offset,int count)throws IOException{events.check();return fs.write(fd,b,offset,count);}
        public void fsync(FileDescriptor fd)throws IOException{fs.fsync(fd);}
        public void close(FileDescriptor fd)throws IOException{fs.close(fd);}
    }
}
