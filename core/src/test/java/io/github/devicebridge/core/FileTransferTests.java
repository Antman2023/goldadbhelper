package io.github.devicebridge.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import io.github.devicebridge.core.VerifiedFileTransfer.*;

/** Synthetic device adapter tests. No emulator, real device, or external app assets required. */
public final class FileTransferTests {
    private static int passed;
    private static final String DEST="/sdcard/目录/照片 'one'\n.bin";
    public static void main(String[] args) throws Exception {
        File file=File.createTempFile("goldenbeetle-transfer-test-",".bin");
        byte[] data="independent transfer data 金甲虫".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(),data);
        try {
            Fake ok=new Fake(); Report result=new Transfer(ok).upload(file,DEST,null);
            equal(result.bytes,(long)data.length); equal(result.sha256,sha(data)); check(result.cleanupWarnings.isEmpty());
            check(Arrays.equals(data,ok.files.get(DEST))); check(ok.onlyDestination()); passed++;

            Fake existing=new Fake(); existing.files.put(DEST,new byte[]{42});
            TransferException failure=failUpload(existing,file);
            equal(failure.outcome,Outcome.NOT_COMMITTED); check(existing.files.get(DEST)[0]==42); check(existing.onlyDestination()); passed++;

            Fake race=new Fake(); race.race=true;
            failure=failUpload(race,file); equal(failure.outcome,Outcome.NOT_COMMITTED); check(race.files.get(DEST)[0]==99); passed++;

            Fake partial=new Fake(); partial.partialPush=true;
            failure=failUpload(partial,file); equal(failure.outcome,Outcome.NOT_COMMITTED);
            check(partial.files.isEmpty()); check(partial.cleanedPayload); check(partial.directories.isEmpty()); passed++;

            Fake helperPartial=new Fake(); helperPartial.partialHelper=true;
            failure=failUpload(helperPartial,file); check(helperPartial.files.isEmpty()); check(helperPartial.cleanedHelper); passed++;

            Fake dirty=new Fake(); dirty.partialPush=true; dirty.cleanupFails=true;
            failure=failUpload(dirty,file); check(!failure.cleanupWarnings.isEmpty()); check(failure.getMessage().contains("cleanup")); passed++;

            Fake corrupt=new Fake(); corrupt.corruptStage=true;
            failure=failUpload(corrupt,file); equal(failure.outcome,Outcome.NOT_COMMITTED); check(!corrupt.files.containsKey(DEST)); passed++;

            Fake lost=new Fake(); lost.loseReply=true;
            failure=failUpload(lost,file); equal(failure.outcome,Outcome.UNKNOWN); check(lost.files.containsKey(DEST)); passed++;

            Fake cancelPush=new Fake(); cancelPush.interruptPush=true;
            failure=failUpload(cancelPush,file); check(failure.cancelled); equal(failure.outcome,Outcome.NOT_COMMITTED);
            check(Thread.currentThread().isInterrupted()); check(cancelPush.files.isEmpty()); check(!cancelPush.cleanupSawInterrupt); Thread.interrupted(); passed++;

            Fake cancelCommit=new Fake(); cancelCommit.interruptCommit=true;
            failure=failUpload(cancelCommit,file); check(failure.cancelled); equal(failure.outcome,Outcome.COMMITTED);
            check(cancelCommit.files.containsKey(DEST)); check(Thread.currentThread().isInterrupted()); Thread.interrupted(); passed++;

            Fake callback=new Fake();
            try { new Transfer(callback).upload(file,DEST,(done,total)->false); throw new AssertionError("Callback cancellation ignored"); }
            catch (TransferException expected) { check(expected.cancelled); check(callback.files.isEmpty()); } passed++;

            Fake changedLocal=new Fake(); changedLocal.changeLocal=true;
            failure=failUpload(changedLocal,file); equal(failure.outcome,Outcome.NOT_COMMITTED); check(!changedLocal.files.containsKey(DEST));
            Files.write(file.toPath(),data); passed++;

            Fake download=new Fake(); download.files.put(DEST,data);
            ByteArrayOutputStream output=new ByteArrayOutputStream(); result=new Transfer(download).download(DEST,output,null);
            check(Arrays.equals(data,output.toByteArray())); equal(result.sha256,sha(data)); check(download.onlyDestination()); passed++;

            Fake changed=new Fake(); changed.files.put(DEST,data); changed.changedHash=true;
            failure=failDownload(changed); equal(failure.outcome,Outcome.NOT_COMMITTED); check(changed.onlyDestination()); passed++;

            Fake bad=new Fake(); bad.files.put(DEST,data); bad.corruptPull=true;
            failure=failDownload(bad); check(failure.getMessage().contains("SHA-256")); passed++;

            Fake unable=new Fake(); unable.reservationFails=true;
            failure=failUpload(unable,file); check(!failure.cleanupWarnings.isEmpty()); check(unable.removed.isEmpty()); passed++;

            equal(VerifiedFileTransfer.validatePath(DEST),DEST);
            invalid("/x\uD800"); invalid("relative"); invalid("/x\0y"); invalid("/");
            StringBuilder large=new StringBuilder("/"); for(int i=0;i<342;i++)large.append('中'); invalid(large.toString()); passed++;

            Fake first=new Fake(),second=new Fake(); new Transfer(first).upload(file,DEST,null); new Transfer(second).upload(file,DEST,null);
            check(!first.directory.equals(second.directory)); check(first.directory.startsWith("/data/local/tmp/goldenbeetle-transfer-")); passed++;

            Fake cleanupCancel=new Fake(); cleanupCancel.interruptCleanup=true;
            failure=failUpload(cleanupCancel,file); check(failure.cancelled); equal(failure.outcome,Outcome.COMMITTED); check(Thread.currentThread().isInterrupted()); Thread.interrupted(); passed++;

            System.out.println("FILE TRANSFER TESTS: "+passed+" passed");
        } finally { Thread.interrupted(); Files.deleteIfExists(file.toPath()); }
    }
    static final class Transfer extends VerifiedFileTransfer { Transfer(Backend backend){super(backend);} }
    static final class Fake implements Backend {
        final Map<String,byte[]> files=new HashMap<String,byte[]>(); final Set<String> directories=new HashSet<String>();
        final List<String> removed=new ArrayList<String>(); String directory;
        boolean race,partialPush,partialHelper,cleanupFails,corruptStage,loseReply,interruptPush,interruptCommit,
                cleanupSawInterrupt,cleanedPayload,cleanedHelper,changedHash,corruptPull,reservationFails,changeLocal,interruptCleanup;
        int hashCalls;
        public void reserveDirectory(String path)throws IOException {directory=path;if(reservationFails)throw new IOException("reserve reply lost");check(directories.add(path));}
        public void deployHelper(String path)throws IOException {files.put(path,new byte[]{1});if(partialHelper)throw new IOException("partial helper");}
        public long push(File local,long length,String stage,SyncClient.Progress progress)throws IOException {
            check(directories.contains(directory)); byte[] data=Files.readAllBytes(local.toPath());files.put(stage,data);
            if(interruptPush){Thread.currentThread().interrupt();throw new InterruptedIOException("cancelled push");}
            if(progress!=null&&!progress.onProgress(0,length))throw new InterruptedIOException("callback cancelled");
            if(partialPush)throw new IOException("partial payload");
            if(corruptStage)files.put(stage,new byte[]{9});
            if(changeLocal)Files.write(local.toPath(),new byte[]{1,2,3});
            return data.length;
        }
        public long pull(String remote,OutputStream output,long bytes,SyncClient.Progress progress)throws IOException {
            byte[] data=files.get(remote).clone();if(corruptPull)data[0]^=1;output.write(data);return data.length;
        }
        public Fingerprint hash(String helper,String remote)throws IOException {
            byte[] data=files.get(remote);return fp(data,changedHash&&++hashCalls>1?"changed":"stable");
        }
        public Fingerprint commit(String helper,String stage,String remote,Fingerprint expected)throws IOException {
            if(race)files.put(remote,new byte[]{99});
            if(files.containsKey(remote))throw new CommitException("existing destination",Outcome.NOT_COMMITTED);
            Fingerprint staged=fp(files.get(stage),"stable");
            if(staged.bytes!=expected.bytes||!staged.sha256.equals(expected.sha256))throw new CommitException("stage digest",Outcome.NOT_COMMITTED);
            files.put(remote,files.get(stage));
            if(loseReply)throw new IOException("lost commit acknowledgement");
            if(interruptCommit)Thread.currentThread().interrupt();
            return staged;
        }
        public void removeFile(String path)throws IOException {
            cleanupSawInterrupt|=Thread.currentThread().isInterrupted();removed.add(path);
            if(path.endsWith("/payload"))cleanedPayload=true;if(path.endsWith("metadata-server.jar"))cleanedHelper=true;
            if(cleanupFails)throw new IOException("cleanup failed");files.remove(path);
            if(interruptCleanup){Thread.currentThread().interrupt();interruptCleanup=false;}
        }
        public void removeDirectory(String path)throws IOException {if(cleanupFails)throw new IOException("not empty");directories.remove(path);}
        boolean onlyDestination(){return files.size()==1&&files.containsKey(DEST)&&directories.isEmpty();}
    }
    static Fingerprint fp(byte[] bytes,String snapshot)throws IOException{return new Fingerprint(bytes.length,sha(bytes),snapshot);}
    static String sha(byte[] data)throws IOException {try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IOException(e);}}
    static TransferException failUpload(Fake fake,File local)throws Exception{try{new Transfer(fake).upload(local,DEST,null);throw new AssertionError("Upload unexpectedly succeeded");}catch(TransferException e){return e;}}
    static TransferException failDownload(Fake fake)throws Exception{try{new Transfer(fake).download(DEST,new ByteArrayOutputStream(),null);throw new AssertionError("Download unexpectedly succeeded");}catch(TransferException e){return e;}}
    static void invalid(String path)throws Exception{try{VerifiedFileTransfer.validatePath(path);throw new AssertionError("Invalid path accepted");}catch(IOException expected){}}
    static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void check(boolean condition){if(!condition)throw new AssertionError();}
}
