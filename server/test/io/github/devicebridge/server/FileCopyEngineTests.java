package io.github.devicebridge.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Ordinary synthetic trees only: no link, replacement, escape or redirection fixtures. */
public final class FileCopyEngineTests {
    static int assertions;
    public static void main(String[] args)throws Exception {
        Tree happy=tree();Log log=new Log();FileCopyEngine engine=new FileCopyEngine(happy,log);
        FileCopyEngine.Node root=engine.plan(happy.dir("/src"),"folder","/src/folder","/dest/folder",0);
        check(engine.copy(root,happy.dir("/src"),happy.dir("/dest"),"folder"));
        check(root.status.equals("DIRECTORY_COPIED"));check(log.plans==5);
        eq(happy.entries.get("/dest/folder/中文 'name'\n.dat").data,bytes("ordinary bytes 中文"));
        eq(happy.entries.get("/dest/folder/nested/empty").data,new byte[0]);check(happy.handles.isEmpty());
        check(log.copied==2);check(happy.syncs==2);check(log.finalResults==5);

        Tree conflict=tree();conflict.add("/dest/folder",1,bytes("keep"));Log conflicts=new Log();
        FileCopyEngine ce=new FileCopyEngine(conflict,conflicts);FileCopyEngine.Node cr=ce.plan(conflict.dir("/src"),"folder","/src/folder","/dest/folder",0);
        check(!ce.copy(cr,conflict.dir("/src"),conflict.dir("/dest"),"folder"));check(cr.status.equals("FAILED"));
        eq(conflict.entries.get("/dest/folder").data,bytes("keep"));check(conflicts.notExecuted==4);check(conflict.writes==0);

        Tree partial=tree();partial.failWriteSuffix="empty";Log pl=new Log();FileCopyEngine pe=new FileCopyEngine(partial,pl);
        FileCopyEngine.Node pr=pe.plan(partial.dir("/src"),"folder","/src/folder","/dest/folder",0);
        check(!pe.copy(pr,partial.dir("/src"),partial.dir("/dest"),"folder"));
        // Empty files do not call write, so inject a fsync failure to exercise retained output.
        check(pr.status.equals("PARTIAL"));check(partial.entries.containsKey("/dest/folder/nested/empty"));
        check(pl.unknown==1);check(pl.copied==1);eq(partial.entries.get("/dest/folder/中文 'name'\n.dat").data,bytes("ordinary bytes 中文"));
        check(partial.handles.isEmpty());

        Tree unsupported=tree();unsupported.add("/src/folder/special",3,new byte[0]);Log ul=new Log();
        FileCopyEngine ue=new FileCopyEngine(unsupported,ul);FileCopyEngine.Node ur=ue.plan(unsupported.dir("/src"),"folder","/src/folder","/dest/folder",0);
        check(!ue.copy(ur,unsupported.dir("/src"),unsupported.dir("/dest"),"folder"));check(ur.status.equals("PARTIAL"));
        check(!unsupported.entries.containsKey("/dest/folder/special"));check(ul.failed==1);check(ul.copied==2);

        Tree bounded=tree();Log bl=new Log();FileCopyEngine be=new FileCopyEngine(bounded,bl,2,64);
        try{be.plan(bounded.dir("/src"),"folder","/src/folder","/dest/folder",0);throw new AssertionError("limit accepted");}
        catch(IOException expected){be.abortPlanning(expected.getMessage());}
        check(bounded.writes==0);check(!bounded.entries.containsKey("/dest/folder"));check(bl.notExecuted==2);

        Tree cancelled=tree();Log cl=new Log();FileCopyEngine canc=new FileCopyEngine(cancelled,cl);
        FileCopyEngine.Node nr=canc.plan(cancelled.dir("/src"),"folder","/src/folder","/dest/folder",0);cl.cancel=true;
        try{canc.copy(nr,cancelled.dir("/src"),cancelled.dir("/dest"),"folder");throw new AssertionError("cancel ignored");}
        catch(InterruptedIOException expected){}
        check(cancelled.writes==0);check(!cancelled.entries.containsKey("/dest/folder"));check(nr.status.equals("PLANNED"));

        Tree interrupted=tree();Log il=new Log();FileCopyEngine ie=new FileCopyEngine(interrupted,il);
        FileCopyEngine.Node ir=ie.plan(interrupted.dir("/src"),"folder","/src/folder","/dest/folder",0);il.cancelAfterCopied=1;
        check(!ie.copy(ir,interrupted.dir("/src"),interrupted.dir("/dest"),"folder"));
        check(ir.status.equals("PARTIAL"));check(il.copied==1);check(il.notExecuted==1);
        check(interrupted.entries.containsKey("/dest/folder/nested/empty"));check(!interrupted.entries.containsKey("/dest/folder/中文 'name'\n.dat"));
        check(interrupted.handles.isEmpty());

        Tree ordinaryFile=tree();Log fl=new Log();FileCopyEngine fe=new FileCopyEngine(ordinaryFile,fl);
        FileCopyEngine.Node fn=fe.plan(ordinaryFile.dir("/src/folder"),"中文 'name'\n.dat","/src/folder/中文 'name'\n.dat","/dest/file",0);
        check(fe.copy(fn,ordinaryFile.dir("/src/folder"),ordinaryFile.dir("/dest"),"file"));check(fn.status.equals("COPIED"));
        eq(ordinaryFile.entries.get("/dest/file").data,bytes("ordinary bytes 中文"));
        System.out.println("FILE COPY ORDINARY TESTS: "+assertions+" assertions passed");
    }
    static Tree tree(){Tree t=new Tree();t.add("/src",2,null);t.add("/dest",2,null);t.add("/src/folder",2,null);t.add("/src/folder/nested",2,null);t.add("/src/folder/empty-directory",2,null);t.add("/src/folder/nested/empty",1,new byte[0]);t.add("/src/folder/中文 'name'\n.dat",1,bytes("ordinary bytes 中文"));return t;}
    static final class Log implements FileCopyEngine.Observer {
        int plans,copied,notExecuted,unknown,failed,finalResults,cancelAfterCopied;boolean cancel;
        public void check()throws IOException{if(cancel)throw new InterruptedIOException("Ordinary cancellation fixture");}
        public void event(String event,FileCopyEngine.Node n,String status,String message,FileCommitServer.Receipt receipt){
            if(event.equals("plan"))plans++;if(event.equals("result"))finalResults++;
            if(status.equals("COPIED")){copied++;FileCopyEngineTests.check(receipt!=null&&receipt.sha256.matches("[0-9a-f]{64}"));if(cancelAfterCopied>0&&copied>=cancelAfterCopied)cancel=true;}
            if(status.equals("NOT_EXECUTED"))notExecuted++;if(status.equals("UNKNOWN"))unknown++;if(status.equals("FAILED"))failed++;
        }
    }
    static final class Entry {final long ino;final int kind;byte[] data;long revision;Entry(long ino,int kind,byte[] data){this.ino=ino;this.kind=kind;this.data=data==null?new byte[0]:data.clone();}}
    static final class Handle {final Entry entry;final String path;int position;Handle(Entry e,String p){entry=e;path=p;}}
    static final class Tree implements FileCommitServer.Fs {
        final Map<String,Entry> entries=new LinkedHashMap<String,Entry>();final Map<FileDescriptor,Handle> handles=new IdentityHashMap<FileDescriptor,Handle>();
        long sequence;int writes,syncs;String failWriteSuffix="";
        void add(String path,int kind,byte[] data){entries.put(path,new Entry(++sequence,kind,data));}
        Dir dir(String path){return new Dir(this,path);}
        Entry entry(String path)throws IOException{Entry e=entries.get(path);if(e==null)throw new FileNotFoundException(path);return e;}
        FileCommitServer.Stat stat(Entry e){return new FileCommitServer.Stat(1,e.ino,e.data.length,e.kind==1,String.valueOf(e.revision));}
        public FileDescriptor readOnly(String p)throws IOException{Entry e=entry(p);if(e.kind!=1)throw new IOException("Not ordinary file");return handle(e,p);}
        public FileDescriptor createExclusive(String p)throws IOException{if(entries.containsKey(p))throw new IOException("Destination exists");add(p,1,new byte[0]);return handle(entry(p),p);}
        FileDescriptor handle(Entry e,String p){FileDescriptor fd=new FileDescriptor();handles.put(fd,new Handle(e,p));return fd;}
        public FileCommitServer.Stat fstat(FileDescriptor fd){return stat(handles.get(fd).entry);}
        public FileCommitServer.Stat lstat(String p)throws IOException{return stat(entry(p));}
        public int read(FileDescriptor fd,byte[] buffer){Handle h=handles.get(fd);if(h.position==h.entry.data.length)return -1;int n=Math.min(buffer.length,h.entry.data.length-h.position);System.arraycopy(h.entry.data,h.position,buffer,0,n);h.position+=n;return n;}
        public int write(FileDescriptor fd,byte[] buffer,int offset,int count)throws IOException{writes++;Handle h=handles.get(fd);h.entry.data=Arrays.copyOf(h.entry.data,h.position+count);System.arraycopy(buffer,offset,h.entry.data,h.position,count);h.position+=count;h.entry.revision++;return count;}
        public void fsync(FileDescriptor fd)throws IOException{syncs++;if(!failWriteSuffix.isEmpty()&&handles.get(fd).path.endsWith(failWriteSuffix))throw new IOException("Ordinary simulated fsync failure");}
        public void close(FileDescriptor fd){handles.remove(fd);}
    }
    static final class Dir implements FileCopyEngine.Directory {
        final Tree tree;final String path;Dir(Tree t,String p){tree=t;path=p;}
        public String child(String name)throws IOException{return FileCopyEngine.join(path,name);}
        public FileCopyEngine.Info stat(String name)throws IOException{Entry e=tree.entry(child(name));return new FileCopyEngine.Info(tree.stat(e).token(),"1:"+e.ino+":"+(e.kind==2),e.kind==1,e.kind==2);}
        public FileCopyEngine.Directory open(String name,String expected)throws IOException{FileCopyEngine.Info info=stat(name);if(!info.directory||!info.identity.equals(expected))throw new IOException("Directory unavailable");return tree.dir(child(name));}
        public FileCopyEngine.Directory create(String name)throws IOException{String p=child(name);if(tree.entries.containsKey(p))throw new IOException("Destination exists");tree.add(p,2,null);return tree.dir(p);}
        public String[] names(){List<String> names=new ArrayList<String>();for(String p:tree.entries.keySet())if(p.startsWith(path+"/")&&p.indexOf('/',path.length()+1)<0)names.add(p.substring(path.length()+1));return names.toArray(new String[0]);}
        public void close(){}
    }
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static void eq(byte[] a,byte[] b){check(Arrays.equals(a,b));}
    static void check(boolean condition){assertions++;if(!condition)throw new AssertionError("assertion "+assertions);}
}
