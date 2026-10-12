package io.github.devicebridge.core;
import java.io.*;import java.lang.reflect.*;
/** KitKat observed CLSE(0, local) is allowed only after CNXN protocol 0x01000000. */
public final class LegacyCloseTests {
 private static int count;
 private static AdbSession session(int version)throws Exception{Transport t=new Transport(){final ByteArrayOutputStream out=new ByteArrayOutputStream();public InputStream input(){return new ByteArrayInputStream(new byte[0]);}public OutputStream output(){return out;}public String description(){return "synthetic";}public void close(){}};AdbSession s=new AdbSession(t,null,null);Field field=AdbSession.class.getDeclaredField("peerVersion");field.setAccessible(true);field.setInt(s,version);return s;}
 private static void close(int version,int remote,int closeId,boolean expected)throws Exception{AdbStream stream=new AdbStream(session(version),1,1000);stream.onOkay(remote);try{stream.onClose(closeId);if(!expected)throw new AssertionError("mismatched close accepted");if(stream.input().read()!=-1)throw new AssertionError("closed stream did not EOF");}catch(IOException e){if(expected)throw e;}count++;}
 public static void main(String[] args)throws Exception{close(0x01000000,182,0,true);close(0x01000000,182,182,true);close(0x01000000,182,183,false);close(0x01000001,182,0,false);close(0x01000001,182,182,true);close(0x01000001,182,183,false);close(0x01000002,182,0,false);System.out.println("LegacyCloseTests: "+count+" assertions passed");}
}
