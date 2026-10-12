package io.github.devicebridge.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Shell-v2 keeps stdout, stderr and exit code separate. One-shot legacy commands use explicit exit framing; streaming legacy output retains unknown status. */
public final class ShellClient {
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
        boolean v2=session.hasFeature("shell_v2");
        try(AdbStream stream=session.open((v2?"shell,v2,raw:":"shell:")+command,timeoutMs)){
            InputStream in=stream.input();
            if(!v2){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0){byte[] data=new byte[n];System.arraycopy(buffer,0,data,0,n);output.data(false,data);}return -1;}
            boolean exited=false;int exit=-1;
            while(true){int id=in.read();if(id<0)break;int len=ByteBuffer.wrap(AdbPacket.readExactly(in,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if(len<0||len>16*1024*1024)throw new IOException("Invalid shell-v2 frame length");byte[] data=AdbPacket.readExactly(in,len);
                if(id==1||id==2)output.data(id==2,data);
                else if(id==3){if(len!=1&&len!=4)throw new IOException("Invalid shell exit packet");exit=len==1?data[0]&255:ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();exited=true;}
                else if(id!=4&&id!=5)throw new IOException("Unexpected shell-v2 packet "+id);
            }
            if(!exited)throw new EOFException("Shell connection ended without an exit status");return exit;
        }
    }
    public byte[] execBytes(String command,int timeoutMs,int limit)throws IOException{
        try(AdbStream stream=session.open("exec:"+command,timeoutMs)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;
            while((n=stream.input().read(b))>=0){if(out.size()+n>limit)throw new IOException("Output size limit exceeded");out.write(b,0,n);}return out.toByteArray();
        }
    }
}
