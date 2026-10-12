package io.github.devicebridge.android;

import android.content.Context;
import io.github.devicebridge.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Per-session remote tree copy with an acknowledged, per-entry result journal.
 * Old shell-v1 needs no toolbox flags or inferred command exit code: only the helper's
 * request-bound final acknowledgement completes a copy. Lost/cancelled commands are not retried.
 */
final class FileCopyManager implements Closeable {
    static final class Item {
        final String source,destination;
        String status="PLANNED",message="",sha256="";long bytes;
        Item(String source,String destination){this.source=source;this.destination=destination;}
        boolean terminal(){return !status.equals("PLANNED")&&!status.equals("STARTED");}
        String summary(){
            String label;
            switch(status){
                case "COPIED":label="文件已验证复制";break;
                case "DIRECTORY_COPIED":label="目录已复制";break;
                case "PARTIAL":label="部分完成（已创建内容保留）";break;
                case "NOT_EXECUTED":label="未执行";break;
                case "FAILED":label="失败（此项未完成）";break;
                default:label="结果未知（请检查目标，勿盲目重试）";
            }
            return label+"："+source+"\n→ "+destination+(sha256.isEmpty()?"":"\nSHA256："+sha256)+(message.isEmpty()?"":"\n"+message);
        }
    }
    static final class Report {
        final List<Item> items;final boolean complete;final String warning;
        Report(Collection<Item> items,boolean complete,String warning){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.complete=complete;this.warning=warning;}
    }
    private final Context context;private final AdbSession session;private final ShellClient shell;
    private final String workspace="/data/local/tmp/goldenbeetle-transfer-"+UUID.randomUUID();
    private final String helper=workspace+"/metadata-server.jar";
    private boolean reserved,ready,closed;
    private final List<String> cleanupWarnings=new ArrayList<>();
    FileCopyManager(Context context,AdbSession session){if(session==null)throw new NullPointerException("session");this.context=context.getApplicationContext();this.session=session;this.shell=new ShellClient(session);}
    private void open()throws IOException {
        if(closed)throw new IOException("Copy manager closed");if(ready)return;
        checkActive();
        try{require(shell.run("umask 077; mkdir "+DeviceCommands.quote(workspace),10000));reserved=true;}
        catch(IOException e){cleanupWarnings.add("工作目录预留未确认，未进行不安全清理："+workspace);throw e;}
        try(InputStream input=context.getAssets().open("metadata-server.jar");SyncClient sync=new SyncClient(session,30000)){
            sync.push(helper,input,-1,0600,System.currentTimeMillis()/1000,(done,total)->!Thread.currentThread().isInterrupted());
        }
        require(shell.run("chmod 0400 "+DeviceCommands.quote(helper),10000));ready=true;checkActive();
    }
    Report copy(String source,String destination)throws IOException {
        VerifiedFileTransfer.validatePath(source);VerifiedFileTransfer.validatePath(destination);open();
        String request=UUID.randomUUID().toString();Journal journal=new Journal(request,source,destination);
        String command="CLASSPATH="+DeviceCommands.quote(helper)+" app_process / io.github.devicebridge.server.FileCopyServer "
                +DeviceCommands.quote(request)+" "+DeviceCommands.quote(source)+" "+DeviceCommands.quote(destination);
        String failure="";
        try {
            checkActive();
            shell.stream(command,120000,(stderr,data)->journal.accept(stderr,data));
            journal.end();
        }catch(IOException|RuntimeException e){failure=String.valueOf(e.getMessage());}
        return journal.report(failure);
    }
    static final class MoveReport {
        final String status,message,sha256;
        MoveReport(String status,String message,String sha256){this.status=status;this.message=message;this.sha256=sha256;}
        boolean verified(){return status.equals("VERIFIED");}
        String summary(String source,String destination){return (verified()?"移动已核验":status.equals("NOT_EXECUTED")?"未执行移动":"移动结果未知（远端可能仍在收尾，请检查两处路径，勿盲目重试）")+"："+source+"\n→ "+destination+"\n"+message+(sha256.isEmpty()?"":"\nSHA256："+sha256);}
    }
    MoveReport move(String source,String destination)throws IOException {
        VerifiedFileTransfer.validatePath(source);VerifiedFileTransfer.validatePath(destination);open();
        String request=UUID.randomUUID().toString();
        String command="CLASSPATH="+DeviceCommands.quote(helper)+" app_process / io.github.devicebridge.server.FileMoveServer "
                +DeviceCommands.quote(request)+" "+DeviceCommands.quote(source)+" "+DeviceCommands.quote(destination);
        try {
            checkActive();ShellClient.Result result=shell.run(command,120000);JSONObject found=null;
            for(String line:result.text().split("\\r?\\n")){
                if(!line.startsWith("{"))continue;JSONObject json=new JSONObject(line);
                if(json.optInt("gbmove",0)==1&&request.equals(json.optString("request"))){if(found!=null)throw new IOException("Ambiguous move acknowledgement");found=json;}
            }
            if(found==null)throw new IOException("No verified move acknowledgement: "+result.error());
            String status=found.getString("status");if(!Arrays.asList("VERIFIED","NOT_EXECUTED","UNKNOWN").contains(status))throw new IOException("Invalid move outcome");
            String sha=found.optString("sha256");if(!sha.isEmpty()&&(!sha.matches("[0-9a-f]{64}")||found.optLong("bytes",-1)<0))throw new IOException("Invalid move digest");
            if(status.equals("VERIFIED")&&!result.success())throw new IOException("Move receipt and process status disagree");
            return new MoveReport(status,found.optString("message"),sha);
        }catch(Exception failure){return new MoveReport("UNKNOWN",String.valueOf(failure.getMessage()),"");}
    }
    List<String> cleanupWarnings(){return Collections.unmodifiableList(new ArrayList<>(cleanupWarnings));}
    public void close(){
        if(closed)return;closed=true;
        if(reserved)cleanupWarnings.addAll(FileTransferManager.cleanupWorkspace(session,workspace));
    }
    private static void require(ShellClient.Result result)throws IOException{if(!result.success())throw new IOException(result.text()+result.error());}
    private static void checkActive()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Copy cancelled");}
    static final class Journal {
        final String request,source,destination;final Map<String,Item> items=new LinkedHashMap<>();
        final ByteArrayOutputStream line=new ByteArrayOutputStream();
        boolean done,complete,uncertain;String terminalMessage="";int terminalCount=-1;
        Journal(String request,String source,String destination){this.request=request;this.source=source;this.destination=destination;}
        void accept(boolean stderr,byte[] data)throws IOException {
            if(stderr)return;
            for(byte b:data){if(b=='\n'){parse(new String(line.toByteArray(),StandardCharsets.UTF_8));line.reset();}
                else{if(line.size()>=65536)throw new IOException("Copy acknowledgement exceeds line limit");line.write(b);}}
        }
        void end()throws IOException{if(line.size()>0){parse(new String(line.toByteArray(),StandardCharsets.UTF_8));line.reset();}}
        void parse(String line)throws IOException {
            if(!line.startsWith("{"))return; // ART diagnostics do not constitute acknowledgements.
            try {
                JSONObject json=new JSONObject(line);
                if(json.optInt("gbcopy",0)!=1||!request.equals(json.optString("request")))return;
                if(done)throw new IOException("Duplicate or trailing copy acknowledgement");
                String event=json.getString("event");if(event.equals("heartbeat"))return;
                if(event.equals("done")){
                    terminalCount=json.getInt("count");complete=json.getBoolean("complete");uncertain=json.getBoolean("uncertain");terminalMessage=json.optString("message");
                    if(terminalCount!=items.size())throw new IOException("Copy plan acknowledgement count mismatch");
                    for(Item item:items.values())if(!item.terminal()&&!uncertain)throw new IOException("Copy acknowledgement omitted planned entries");
                    if(complete){if(items.isEmpty())throw new IOException("Empty successful copy acknowledgement");for(Item item:items.values())if(!item.status.equals("COPIED")&&!item.status.equals("DIRECTORY_COPIED"))throw new IOException("Copy claimed success with incomplete entries");}
                    done=true;return;
                }
                String path=json.getString("source"),target=json.getString("destination");
                if(!(path.equals(source)||path.startsWith(source+"/"))||!(target.equals(destination)||target.startsWith(destination+"/")))throw new IOException("Copy acknowledgement is outside the requested subtree");
                if(!path.substring(source.length()).equals(target.substring(destination.length())))throw new IOException("Copy acknowledgement destination mismatch");
                Item item=items.get(path);
                if(event.equals("plan")){
                    if(item!=null||items.size()>=10000)throw new IOException("Duplicate or excessive copy plan");
                    items.put(path,new Item(path,target));return;
                }
                if(item==null||item.terminal())throw new IOException("Unexpected copy item transition");
                if(event.equals("start")){if(!item.status.equals("PLANNED"))throw new IOException("Duplicate copy start");item.status="STARTED";return;}
                if(!event.equals("result"))throw new IOException("Unknown copy event");
                String status=json.getString("status");
                if(!Arrays.asList("COPIED","DIRECTORY_COPIED","PARTIAL","NOT_EXECUTED","FAILED","UNKNOWN").contains(status))throw new IOException("Unknown copy outcome");
                if(status.equals("COPIED")){
                    String sha=json.getString("sha256");long bytes=json.getLong("bytes");
                    if(!sha.matches("[0-9a-f]{64}")||bytes<0)throw new IOException("Invalid copied-file digest");item.sha256=sha;item.bytes=bytes;
                }
                item.status=status;item.message=json.optString("message");
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Invalid copy acknowledgement",e);}
        }
        Report report(String failure){
            String warning=terminalMessage;
            if(!failure.isEmpty())warning+=(warning.isEmpty()?"":"\n")+failure;
            if(!done||uncertain){
                warning+=(warning.isEmpty()?"":"\n")+"未收到完整完成确认；未确认项可能已创建或仍在结束，请检查目标后再操作。";
                for(Item item:items.values())if(!item.terminal()){item.status="UNKNOWN";item.message="未收到此项终态；不推断已撤销或未执行";}
            }
            if(items.isEmpty()){
                Item item=new Item(source,destination);item.status=done&&!uncertain?"FAILED":"UNKNOWN";item.message=warning;items.put(source,item);
            }
            return new Report(items.values(),done&&complete&&failure.isEmpty(),warning);
        }
    }
}
