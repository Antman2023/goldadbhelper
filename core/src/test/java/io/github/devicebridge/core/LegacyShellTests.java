package io.github.devicebridge.core;
import java.io.*;import java.nio.charset.StandardCharsets;
public final class LegacyShellTests {
 private static int assertions;
 private static void check(boolean good,String message){assertions++;if(!good)throw new AssertionError(message);}
 private static String output(String command)throws Exception{Process p=new ProcessBuilder("sh","-c",command).redirectErrorStream(true).start();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[256];int n;while((n=p.getInputStream().read(b))!=-1)out.write(b,0,n);check(p.waitFor()==0,"framing shell completed");return new String(out.toByteArray(),StandardCharsets.UTF_8);}
 private static void rejected(LegacyShellCommand frame,String result)throws Exception{try{frame.parse(result);throw new AssertionError("accepted invalid tail");}catch(IOException expected){assertions++;}}
 public static void main(String[] args)throws Exception{
  String[] commands={"printf '%s' '中文 空格 单引号 '\"'\"' double\"'","printf 'line1\\nline2\\n'","printf '%s' 'GB_STATUS_not_real:0'","exit 17","false","true","printf 'error\\n' >&2; exit 7","printf 'without-final-newline'"};
  int[] exits={0,0,0,17,1,0,7,0};
  for(int i=0;i<commands.length;i++){LegacyShellCommand f=new LegacyShellCommand();String wire=output(f.wrap(commands[i]));LegacyShellCommand.Reply r=f.parse(wire);check(r.exitCode==exits[i],"exact exit "+i);check(!r.output.contains("GB_STATUS_")||i==2,"marker stripped "+i);check(f.parse(wire.replace("\n","\r\n")).exitCode==exits[i],"PTY CRLF "+i);rejected(f,wire+"extra\n");rejected(new LegacyShellCommand(),wire);}
  LegacyShellCommand f=new LegacyShellCommand();String wire=output(f.wrap("printf 'keep\\n'"));check(f.parse(wire).output.equals("keep\n"),"original final LF retained");rejected(f,"keep\n");String trailer=wire.substring(wire.lastIndexOf(':'));rejected(f,wire.substring(0,wire.length()-trailer.length())+":999\n");
  System.out.println("LegacyShellTests: "+assertions+" assertions passed");
 }
}
