package io.github.devicebridge.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Pure synthetic receipt parsing on the Android JSON implementation; no filesystem access. */
public final class FileCopyJournalProbe {
    private static final String REQUEST="01234567-0123-0123-0123-012345678901";
    private static final String SHA="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static int checks;
    public static void main(String[] args)throws Exception {
        FileCopyManager.Journal complete=new FileCopyManager.Journal(REQUEST,"/source/file","/target/file");
        send(complete,item("plan","PLANNED"));send(complete,item("start","STARTED"));send(complete,item("result","COPIED").put("bytes",0).put("sha256",SHA));
        send(complete,base("done").put("count",1).put("complete",true).put("failed",false).put("uncertain",false));
        FileCopyManager.Report good=complete.report("");ok(good.complete,"request-bound terminal acknowledgement completes file");ok(good.items.get(0).status.equals("COPIED"),"digest receipt preserved");

        FileCopyManager.Journal interrupted=new FileCopyManager.Journal(REQUEST,"/source/file","/target/file");
        send(interrupted,item("plan","PLANNED"));send(interrupted,item("start","STARTED"));
        FileCopyManager.Report cancelled=interrupted.report("cancelled");ok(!cancelled.complete,"cancelled stream is incomplete");ok(cancelled.items.get(0).status.equals("UNKNOWN"),"in-flight item is UNKNOWN");

        FileCopyManager.Journal missing=new FileCopyManager.Journal(REQUEST,"/source/file","/target/file");
        send(missing,item("plan","PLANNED"));send(missing,item("start","STARTED"));send(missing,item("result","COPIED").put("bytes",0).put("sha256",SHA));
        FileCopyManager.Report partial=missing.report("missing final ack");ok(!partial.complete,"missing final acknowledgement does not complete operation");ok(partial.items.get(0).status.equals("COPIED"),"known completed entry survives later disconnect");

        FileCopyManager.Journal neverStarted=new FileCopyManager.Journal(REQUEST,"/source/file","/target/file");
        send(neverStarted,item("plan","PLANNED"));send(neverStarted,item("result","NOT_EXECUTED"));
        send(neverStarted,base("done").put("count",1).put("complete",false).put("failed",true).put("uncertain",false));
        ok(neverStarted.report("").items.get(0).status.equals("NOT_EXECUTED"),"acknowledged never-started item remains distinct");

        FileCopyManager.Journal duplicate=new FileCopyManager.Journal(REQUEST,"/source/file","/target/file");send(duplicate,item("plan","PLANNED"));
        boolean rejected=false;try{send(duplicate,item("plan","PLANNED"));}catch(IOException expected){rejected=true;}ok(rejected,"duplicate plan rejected");
        FileCopyManager.Journal falseSuccess=new FileCopyManager.Journal(REQUEST,"/source/file","/target/file");send(falseSuccess,item("plan","PLANNED"));
        rejected=false;try{send(falseSuccess,base("done").put("count",1).put("complete",true).put("failed",false).put("uncertain",false));}catch(IOException expected){rejected=true;}ok(rejected,"success without per-entry result rejected");
        System.out.println("FILE_COPY_JOURNAL_PROBE_PASSED "+checks);
    }
    private static JSONObject base(String event)throws Exception{return new JSONObject().put("gbcopy",1).put("request",REQUEST).put("event",event);}
    private static JSONObject item(String event,String status)throws Exception{return base(event).put("source","/source/file").put("destination","/target/file").put("status",status).put("message","");}
    private static void send(FileCopyManager.Journal j,JSONObject json)throws Exception{
        byte[] bytes=(json.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        // Exercise arbitrary stream chunking, including UTF-8 boundaries in the production parser.
        for(byte b:bytes)j.accept(false,new byte[]{b});
    }
    private static void ok(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
}
