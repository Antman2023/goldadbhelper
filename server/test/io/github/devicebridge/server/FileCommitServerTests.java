package io.github.devicebridge.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.util.*;

/** Synthetic FS fault injection: no device or Android runtime required. */
public final class FileCommitServerTests {
    static final String STAGE="/data/local/tmp/goldenbeetle-transfer-01234567-0123-0123-0123-012345678901/payload";
    static final String DEST="/sdcard/中文 'name'\n.dat";
    static final byte[] DATA="independent content".getBytes(StandardCharsets.UTF_8);
    static int passed;
    public static void main(String[] args)throws Exception {
        Fake happy=new Fake(); FileCommitServer.Receipt receipt=commit(happy);
        eq(receipt.sha256,sha(DATA)); eq(receipt.bytes,(long)DATA.length); check(Arrays.equals(happy.files.get(DEST).data,DATA)); check(happy.fsynced); check(happy.handles.isEmpty()); passed++;

        Fake existing=new Fake(); Node other=existing.add(DEST,new byte[]{5},true);
        failure(existing,"NOT_COMMITTED"); check(existing.files.get(DEST)==other); check(other.data[0]==5); passed++;

        Fake link=new Fake(); Node symlink=link.add(DEST,new byte[]{6},false);
        failure(link,"NOT_COMMITTED"); check(link.files.get(DEST)==symlink); check(link.writeCalls==0); passed++;

        Fake racing=new Fake(); racing.raceCreate=true;
        failure(racing,"NOT_COMMITTED"); check(racing.files.get(DEST).data[0]==42); check(racing.writeCalls==0); passed++;

        Fake mismatch=new Fake();
        try { FileCommitServer.commit(mismatch,STAGE,DEST,sha(new byte[]{7}),DATA.length); throw new AssertionError(); }
        catch(FileCommitServer.CommitFailure e){eq(e.outcome,"NOT_COMMITTED");check(!mismatch.files.containsKey(DEST));} passed++;

        Fake diskFull=new Fake(); diskFull.failWrite=true;
        FileCommitServer.CommitFailure error=failure(diskFull,"UNKNOWN");
        check(diskFull.files.containsKey(DEST)); check(error.getMessage().contains("partial")); check(diskFull.unlinkCalls==0); passed++;

        Fake fsync=new Fake(); fsync.failFsync=true; failure(fsync,"UNKNOWN");
        check(fsync.files.containsKey(DEST));check(fsync.unlinkCalls==0);passed++;

        Fake replace=new Fake(); replace.replaceAtFsync=true; failure(replace,"UNKNOWN");
        check(replace.files.get(DEST).data[0]==88);check(replace.unlinkCalls==0);passed++;

        Fake lastMoment=new Fake(); lastMoment.failWrite=true;lastMoment.replaceBeforeUnlink=true;
        failure(lastMoment,"UNKNOWN");check(lastMoment.unlinkCalls==0);passed++;

        Fake changeDuringCopy=new Fake();changeDuringCopy.changeDuringCopy=true;failure(changeDuringCopy,"UNKNOWN");
        check(changeDuringCopy.unlinkCalls==0);passed++;

        Fake persistedCorrupt=new Fake(); persistedCorrupt.corruptAtFsync=true;failure(persistedCorrupt,"UNKNOWN");passed++;

        Fake changingHash=new Fake();changingHash.changeDuringHash=true;
        try{FileCommitServer.hash(changingHash,STAGE);throw new AssertionError();}catch(IOException expected){check(expected.getMessage().contains("changed"));}passed++;

        Fake replaceHash=new Fake();replaceHash.replaceSourceAtRead=true;
        try{FileCommitServer.hash(replaceHash,STAGE);throw new AssertionError();}catch(IOException expected){check(expected.getMessage().contains("changed"));}passed++;

        Fake shortWrite=new Fake();shortWrite.shortWrite=true;commit(shortWrite);
        check(shortWrite.writeCalls>1);check(Arrays.equals(shortWrite.files.get(DEST).data,DATA));passed++;

        Fake close=new Fake();close.failCloseOutput=true;failure(close,"UNKNOWN");check(close.unlinkCalls==0);passed++;

        try{FileCommitServer.commit(new Fake(),STAGE,"/bad\uD800",sha(DATA),DATA.length);throw new AssertionError();}catch(IOException expected){}passed++;
        try{FileCommitServer.commit(new Fake(),STAGE,"/bad\0name",sha(DATA),DATA.length);throw new AssertionError();}catch(IOException expected){}passed++;

        System.out.println("FILE COMMIT SERVER TESTS: "+passed+" passed");
    }
    static FileCommitServer.Receipt commit(Fake fs)throws Exception{return FileCommitServer.commit(fs,STAGE,DEST,sha(DATA),DATA.length);}
    static FileCommitServer.CommitFailure failure(Fake fs,String outcome)throws Exception {
        try{commit(fs);throw new AssertionError("Unexpected commit success");}
        catch(FileCommitServer.CommitFailure e){eq(e.outcome,outcome);check(fs.handles.isEmpty());return e;}
    }
    static final class Node {long ino,revision;byte[] data;boolean regular;Node(long ino,byte[] data,boolean regular){this.ino=ino;this.data=data.clone();this.regular=regular;}}
    static final class Handle {final Node node;int position;boolean output;Handle(Node node,boolean output){this.node=node;this.output=output;}}
    static final class Fake implements FileCommitServer.Fs {
        final Map<String,Node> files=new HashMap<String,Node>();final Map<FileDescriptor,Handle> handles=new IdentityHashMap<FileDescriptor,Handle>();long sequence;
        boolean raceCreate,failWrite,failFsync,replaceAtFsync,replaceBeforeUnlink,changeDuringCopy,corruptAtFsync,
                changeDuringHash,replaceSourceAtRead,shortWrite,failCloseOutput,fsynced;
        int writeCalls,unlinkCalls;
        Fake(){add(STAGE,DATA,true);}
        Node add(String path,byte[] bytes,boolean regular){Node node=new Node(++sequence,bytes,regular);files.put(path,node);return node;}
        public FileDescriptor readOnly(String path)throws IOException {Node node=files.get(path);if(node==null)throw new FileNotFoundException(path);if(!node.regular)throw new IOException("No symlinks");return handle(node,false);}
        public FileDescriptor createExclusive(String path)throws IOException {
            if(raceCreate)add(path,new byte[]{42},true);
            if(files.containsKey(path))throw new FileAlreadyExistsException(path);
            return handle(add(path,new byte[0],true),true);
        }
        FileDescriptor handle(Node node,boolean output){FileDescriptor fd=new FileDescriptor();handles.put(fd,new Handle(node,output));return fd;}
        public FileCommitServer.Stat fstat(FileDescriptor fd){return stat(handles.get(fd).node);}
        public FileCommitServer.Stat lstat(String path)throws IOException {Node node=files.get(path);if(node==null)throw new FileNotFoundException(path);return stat(node);}
        FileCommitServer.Stat stat(Node n){return new FileCommitServer.Stat(1,n.ino,n.data.length,n.regular,Long.toString(n.revision));}
        public int read(FileDescriptor fd,byte[] data){
            Handle h=handles.get(fd);if(h.position==h.node.data.length)return -1;
            int count=Math.min(data.length,h.node.data.length-h.position);System.arraycopy(h.node.data,h.position,data,0,count);h.position+=count;
            if(changeDuringHash){h.node.revision++;changeDuringHash=false;}
            if(replaceSourceAtRead){add(STAGE,DATA,true);replaceSourceAtRead=false;}
            return count;
        }
        public int write(FileDescriptor fd,byte[] data,int off,int count)throws IOException {
            writeCalls++;Handle h=handles.get(fd);int n=shortWrite?1:count;
            byte[] bytes=Arrays.copyOf(h.node.data,h.position+n);System.arraycopy(data,off,bytes,h.position,n);h.position+=n;h.node.data=bytes;h.node.revision++;
            if(changeDuringCopy){files.get(STAGE).revision++;changeDuringCopy=false;}
            if(failWrite)throw new IOException("simulated disk full");return n;
        }
        public void fsync(FileDescriptor fd)throws IOException {
            fsynced=true;if(failFsync)throw new IOException("simulated fsync failure");
            if(replaceAtFsync)add(DEST,new byte[]{88},true);
            if(corruptAtFsync){handles.get(fd).node.data[0]^=1;handles.get(fd).node.revision++;}
        }
        public void close(FileDescriptor fd)throws IOException {Handle h=handles.remove(fd);if(h.output&&failCloseOutput)throw new IOException("close failure");}
        public void unlink(String path){unlinkCalls++;if(replaceBeforeUnlink)add(path,new byte[]{77},true);files.remove(path);}
    }
    static String sha(byte[] data)throws Exception{StringBuilder b=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(data))b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
}
