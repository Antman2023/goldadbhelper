package io.github.devicebridge.android;
import java.io.*;
import java.util.Arrays;
import io.github.devicebridge.core.AndroidFileAccess;

/** Isolated emulator fixtures for bound local files/directories, never app-started. */
public final class CompatibilityFileProbe {
 private static int tests;
 private static void ok(boolean value,String text){if(!value)throw new AssertionError(text);System.out.println("PASS "+text);tests++;}
 private static void write(File f,String text)throws Exception{try(OutputStream out=Compatibility.createExclusive(f)){out.write(text.getBytes("UTF-8"));}}
 private static byte[] read(File f)throws Exception{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]);if(!root.getPath().matches("/data/local/tmp/gb-compat-[a-zA-Z0-9-]+"))throw new IllegalArgumentException();
  File source=new File(root,"source");write(source,"source bytes 中文");String sourceToken=Compatibility.sourceToken(source);
  File cache=new File(root,"private-cache");Compatibility.CopyResult result=Compatibility.copyVerifiedSource(source,cache,sourceToken);
  ok(result.bytes==read(source).length&&Arrays.equals(read(source),read(cache)),"verified source copies exact bytes into new private file");
  ok(result.sha256.length()==64&&result.sourceToken.equals(sourceToken),"copy returns confirmed source token and SHA256");
  boolean rejected=false;try{Compatibility.copyVerifiedSource(source,cache,sourceToken);}catch(IOException e){rejected=true;}ok(rejected,"private snapshot never overwrites existing file");
  File renamed=new File(root,"old-source");ok(source.renameTo(renamed),"source replacement fixture prepared");write(source,"replacement bytes");
  rejected=false;try{Compatibility.copyVerifiedSource(source,new File(root,"must-not-exist"),sourceToken);}catch(IOException e){rejected=true;}ok(rejected&&!new File(root,"must-not-exist").exists(),"source replaced after selection rejected before writing");
  rejected=false;try{Compatibility.sourceToken(new File(root,"source-link"));}catch(IOException e){rejected=true;}ok(rejected,"file-selection token rejects symlink");
  File picked=new File(root,"picked");ok(picked.mkdir(),"directory fixture prepared");String token=Compatibility.directoryToken(picked);
  try(Compatibility.Directory bound=Compatibility.bindDirectory(picked,token)){
   try(OutputStream out=bound.createExclusive("first")){out.write(7);}
   ok(token.equals(Compatibility.directoryToken(picked)),"directory identity remains stable after child addition");
   File moved=new File(root,"picked-moved");ok(picked.renameTo(moved)&&picked.mkdir(),"selected-directory replacement fixture prepared");
   try(OutputStream out=bound.createExclusive("pinned")){out.write(9);}
   ok(new File(moved,"pinned").isFile()&&!new File(picked,"pinned").exists(),"descriptor anchoring defeats selected-path replacement");
   rejected=false;try(Compatibility.Directory ignored=Compatibility.bindDirectory(picked,token)){}catch(IOException e){rejected=true;}ok(rejected,"stale directory token rejects replacement directory");
   try(Compatibility.Directory child=bound.createDirectory("child")){try(OutputStream out=child.createExclusive("nested")){out.write(3);}}
   ok(new File(moved,"child/nested").isFile(),"nested destination stays inside anchored directory");
   rejected=false;try(OutputStream ignored=bound.createExclusive("../escape")){}catch(IOException e){rejected=true;}ok(rejected&&!new File(root,"escape").exists(),"directory output rejects traversal filename");
  }
  rejected=false;try{Compatibility.directoryToken(new File(root,"directory-link"));}catch(IOException e){rejected=true;}ok(rejected,"directory selection rejects final symlink");
  AndroidFileAccess fs=new AndroidFileAccess();FileDescriptor fd=fs.openDirectory(picked.getAbsolutePath());try{ok(fs.fstat(fd).directory,"kernel directory predicate works without O_DIRECTORY constant");}finally{fs.close(fd);}
  System.out.println("LOCAL_FILE_COMPATIBILITY_PROBE_PASSED "+tests);
 }
}
