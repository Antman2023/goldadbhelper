package io.github.devicebridge.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Shell-v2 keeps stdout, stderr and exit code separate. One-shot legacy commands use explicit exit framing; streaming legacy output retains unknown status. */
public final class ShellClient {
    private static final int MAX_FRAME=16*1024*1024;
    private static final int OUTPUT_CHUNK=8192;
    /** Output chunks are at most 8192 bytes; UTF-8 and shell frames may span callbacks. */
    public interface Output { void data(boolean stderr, byte[] data) throws IOException; }
    public static final class Result {
        public final byte[] stdout,stderr; public final int exitCode;
        Result(byte[] stdout,byte[] stderr,int exitCode){this.stdout=stdout;this.stderr=stderr;this.exitCode=exitCode;}
        public String text(){return new String(stdout,StandardCharsets.UTF_8);}
        public String error(){return new String(stderr,StandardCharsets.UTF_8);}
        public boolean success(){return exitCode==0;}
    }
    private final AdbSession session;
    public ShellClient(AdbSession session){this.session=session;}
    public Result run(String command,int timeoutMs)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
        LegacyShellCommand framing=session.hasFeature("shell_v2")?null:new LegacyShellCommand();
        int code=stream(framing==null?command:framing.wrap(command),timeoutMs,(stderr,data)->{ByteArrayOutputStream dest=stderr?err:out;if(dest.size()+data.length>16*1024*1024)throw new IOException("Shell output exceeds 16 MiB; use streaming mode");dest.write(data);});
        if(framing!=null){LegacyShellCommand.Reply parsed=framing.parse(new String(out.toByteArray(),StandardCharsets.UTF_8));return new Result(parsed.output.getBytes(StandardCharsets.UTF_8),err.toByteArray(),parsed.exitCode);}
        return new Result(out.toByteArray(),err.toByteArray(),code);
    }
    public int stream(String command,int timeoutMs,Output output)throws IOException{
        if(command==null||command.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid command");
        if(output==null)throw new NullPointerException("output");
        boolean v2=session.hasFeature("shell_v2");
        try(AdbStream stream=session.open((v2?"shell,v2,raw:":"shell:")+command,timeoutMs)){
            InputStream in=stream.input();
            if(!v2){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0){byte[] data=new byte[n];System.arraycopy(buffer,0,data,0,n);output.data(false,data);}return -1;}
            byte[] buffer=new byte[OUTPUT_CHUNK];
            while(true){int id=in.read();if(id<0)break;int len=ByteBuffer.wrap(AdbPacket.readExactly(in,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if(len<0||len>MAX_FRAME)throw new IOException("Invalid shell-v2 frame length");
                if(id==1||id==2){
                    int remaining=len;
                    while(remaining>0){int n=SyncClient.readSome(in,buffer,Math.min(buffer.length,remaining));if(n<0)throw new EOFException("Truncated shell-v2 output frame");remaining-=n;output.data(id==2,java.util.Arrays.copyOf(buffer,n));}
                }
                else if(id==3){
                    if(len!=1&&len!=4)throw new IOException("Invalid shell exit packet");
                    byte[] data=AdbPacket.readExactly(in,len);
                    // Exit is terminal; older peers need not send CLSE promptly afterwards.
                    return len==1?data[0]&255:ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                }
                else if(id!=4||len!=0)throw new IOException("Unexpected shell-v2 packet "+id);
            }
            throw new EOFException("Shell connection ended without an exit status");
        }
    }
    public byte[] execBytes(String command,int timeoutMs,int limit)throws IOException{
        if(command==null||command.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid command");
        if(limit<0)throw new IllegalArgumentException("Invalid output limit");
        try(AdbStream stream=session.open("exec:"+command,timeoutMs)){
            InputStream in=stream.input();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;
            while((n=in.read(b))>=0){if(n>limit-out.size())throw new IOException("Output size limit exceeded");out.write(b,0,n);}return out.toByteArray();
        }
    }
}
