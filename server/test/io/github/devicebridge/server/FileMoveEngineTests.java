package io.github.devicebridge.server;

import java.io.*;

/** Ordinary command outcomes and synthetic metadata only; no replacement/link/escape fixture. */
public final class FileMoveEngineTests {
    static int assertions;
    public static void main(String[] args)throws Exception {
        Fake file=new Fake(false,"success");result(file,"VERIFIED");check(file.calls==1);check(file.hashes==2);
        Fake directory=new Fake(true,"success");result(directory,"VERIFIED");check(directory.hashes==0);
        Fake skipped=new Fake(false,"skipped");result(skipped,"UNKNOWN");check(skipped.source!=null);
        Fake noExactTarget=new Fake(false,"missing-target");result(noExactTarget,"UNKNOWN");check(noExactTarget.destination==null);
        Fake crossFilesystem=new Fake(false,"cross-fs");result(crossFilesystem,"UNKNOWN");check(crossFilesystem.hashes==1);
        Fake crossDirectory=new Fake(true,"cross-fs");result(crossDirectory,"UNKNOWN");
        Fake conflict=new Fake(false,"success");conflict.destination=info(true,"other",false);result(conflict,"NOT_EXECUTED");check(conflict.calls==0);
        Fake failed=new Fake(false,"command-failure");result(failed,"UNKNOWN");check(failed.calls==1);
        Fake wrongKind=new Fake(false,"wrong-kind");result(wrongKind,"UNKNOWN");
        Fake wrongContent=new Fake(false,"wrong-content");result(wrongContent,"UNKNOWN");
        Fake preflight=new Fake(false,"success");preflight.snapshot="unconfirmed-source-revision";result(preflight,"NOT_EXECUTED");check(preflight.calls==0);
        System.out.println("FILE MOVE ORDINARY TESTS: "+assertions+" assertions passed");
    }
    static final String SHA="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static FileCopyEngine.Info info(boolean destination,String identity,boolean directory){return new FileCopyEngine.Info(destination?"after":"before",identity,!directory,directory);}
    static final class Fake implements FileMoveEngine.Backend {
        FileCopyEngine.Info source,destination;final boolean directory;final String behavior;String snapshot="before";int calls,hashes;
        Fake(boolean directory,String behavior){this.directory=directory;this.behavior=behavior;source=info(false,"device:inode:"+directory,directory);}
        public FileCopyEngine.Info stat(boolean dest)throws IOException{FileCopyEngine.Info value=dest?destination:source;if(value==null)throw new FileNotFoundException("ordinary path absent");return value;}
        public FileCommitServer.Receipt hash(boolean dest){hashes++;return new FileCommitServer.Receipt(0,dest&&behavior.equals("wrong-content")?"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa":SHA,dest?"after":snapshot);}
        public String move()throws IOException{
            calls++;
            if(behavior.equals("command-failure"))throw new IOException("Command connection interrupted");
            if(behavior.equals("skipped"))return "exit=0; ordinary no-clobber skip";
            source=null;
            if(!behavior.equals("missing-target"))destination=info(true,behavior.equals("cross-fs")?"different-device:inode:"+directory:"device:inode:"+directory,behavior.equals("wrong-kind")?!directory:directory);
            return "exit=0";
        }
    }
    static void result(Fake fs,String status){FileMoveEngine.Result result=FileMoveEngine.run(fs);check(result.status.equals(status));}
    static void check(boolean condition){assertions++;if(!condition)throw new AssertionError("assertion "+assertions);}
}
