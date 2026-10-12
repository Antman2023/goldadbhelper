package io.github.devicebridge.server;

import java.io.*;
import java.util.UUID;
import org.json.JSONObject;

/** One-shot modern move plus exact postcondition verification. No legacy overwrite fallback. */
public final class FileMoveServer {
    public static void main(String[] args){
        String request=args.length>0?args[0]:"";FileMoveEngine.Result result;boolean evaluated=false;
        try {
            if(args.length!=3)throw new IOException("Expected request-id source destination");UUID.fromString(request);
            if(android.os.Build.VERSION.SDK_INT<23)throw new IOException("Old toolbox has no verified no-overwrite move capability; command not issued");
            final String source=FileCopyServer.path(args[1]),destination=FileCopyServer.path(args[2]);
            if(source.equals(destination))throw new IOException("Source and destination are identical");
            String canonical=new File(source).getCanonicalPath(),targetCanonical=new File(destination).getCanonicalPath();
            if(targetCanonical.equals(canonical)||targetCanonical.startsWith(canonical+"/"))throw new IOException("Cannot move into the same entry or its own subtree");
            final FileCommitServer.Fs fs=new FileCommitServer.LegacyAndroidFs();
            try(final FileCopyServer.BoundDirectory input=new FileCopyServer.BoundDirectory(parent(source),null,true);
                final FileCopyServer.BoundDirectory output=new FileCopyServer.BoundDirectory(parent(destination),null,true)){
                evaluated=true;result=FileMoveEngine.run(new FileMoveEngine.Backend(){
                    public FileCopyEngine.Info stat(boolean dest)throws IOException{return (dest?output:input).stat(name(dest?destination:source));}
                    public FileCommitServer.Receipt hash(boolean dest)throws IOException{return FileCommitServer.hash(fs,(dest?output:input).child(name(dest?destination:source)));}
                    public String move()throws IOException{
                        Process process=new ProcessBuilder("/system/bin/mv","-n","--",source,destination).redirectErrorStream(true).start();
                        ByteArrayOutputStream diagnostic=new ByteArrayOutputStream();
                        try(InputStream in=process.getInputStream()){
                            byte[] buffer=new byte[4096];int n;
                            while((n=in.read(buffer))!=-1)if(diagnostic.size()<65536)diagnostic.write(buffer,0,Math.min(n,65536-diagnostic.size()));
                            int code;
                            try{code=process.waitFor();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Move result interrupted");}
                            return "mv exit="+code+" "+new String(diagnostic.toByteArray(),"UTF-8");
                        }finally{process.destroy();}
                    }
                });
            }
        }catch(Exception failure){result=new FileMoveEngine.Result(evaluated?"UNKNOWN":"NOT_EXECUTED",String.valueOf(failure.getMessage()),null);}
        try {
            JSONObject reply=new JSONObject().put("gbmove",1).put("request",request).put("status",result.status).put("message",result.message);
            if(result.receipt!=null)reply.put("sha256",result.receipt.sha256).put("bytes",result.receipt.bytes);
            System.out.println(reply.toString());System.out.flush();
        }catch(Exception ignored){}
        System.exit(result.status.equals("VERIFIED")?0:1);
    }
    private static String parent(String path){int at=path.lastIndexOf('/');return at==0?"/":path.substring(0,at);}
    private static String name(String path){return path.substring(path.lastIndexOf('/')+1);}
}
