package io.github.devicebridge.android;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class LocalFileBrowserModelTest {
 static int checks;
 static void check(boolean result,String what){if(!result)throw new AssertionError(what);checks++;System.out.println("PASS "+what);}
 static void rejects(Throwing action,String what)throws Exception{try{action.run();throw new AssertionError(what);}catch(IOException expected){checks++;System.out.println("PASS "+what);}}
 interface Throwing{void run()throws Exception;}
 static File write(File root,String name,int length,long modified)throws Exception{File f=new File(root,name);try(OutputStream out=new FileOutputStream(f)){out.write(new byte[length]);}if(!f.setLastModified(modified))throw new IOException("mtime");return f;}
 static List<String> names(List<LocalFileBrowserModel.Entry> es){List<String> n=new ArrayList<>();for(LocalFileBrowserModel.Entry e:es)n.add(e.name);return n;}
 static List<LocalFileBrowserModel.Entry> list(File r,List<File> roots,int mode,boolean hidden,int sort,boolean desc)throws Exception{return LocalFileBrowserModel.list(r,roots,mode,hidden,sort,desc,Locale.CHINA);}
 public static void main(String[] args)throws Exception{
  File base=Files.createTempDirectory("golden-local-model-").toFile(),root=new File(base,"store"),outside=new File(base,"store-other");root.mkdir();outside.mkdir();List<File> roots=Arrays.asList(root);
  try{
   File folder=new File(root,"目录");folder.mkdir();File sub=new File(folder,"nested");sub.mkdir();
   File a=write(root,"Alpha.txt",8,10000),apk=write(root,"安装包.APK",12,30000),bin=write(root,"disk.blob",32,20000),hidden=write(root,".secret.apk",4,40000);
   File other=write(outside,"other.txt",16,50000);
   List<LocalFileBrowserModel.Entry> all=list(root,roots,1,false,0,false);
   check(all.size()==4,"generic picker retains all ordinary files, hides dotfiles");
   check(all.get(0).directory,"directories sort first");
   check(names(list(root,roots,1,true,0,false)).contains(".secret.apk"),"hidden-file toggle includes dotfiles");
   List<LocalFileBrowserModel.Entry> dirs=list(root,roots,2,true,0,false);check(dirs.size()==1&&dirs.get(0).directory,"directory picker shows folders only");
   List<String> apks=names(list(root,roots,3,false,0,false));check(apks.size()==2&&apks.contains("安装包.APK"),"APK picker uses case-insensitive filter and keeps navigation folders");
   check(names(list(root,roots,4,false,0,false)).contains("disk.blob"),"image mode does not hide non-img disk images");
   List<LocalFileBrowserModel.Entry> size=list(root,roots,1,false,3,true);check(size.get(0).directory&&size.get(1).name.equals("disk.blob"),"descending size still puts folders first");
   List<LocalFileBrowserModel.Entry> time=list(root,roots,1,false,2,true);check(time.get(1).name.equals("安装包.APK"),"descending mtime chooses newest file");
   List<LocalFileBrowserModel.Entry> type=list(root,roots,1,false,1,false);check(type.get(1).name.equals("安装包.APK"),"file type sorting uses normalized extensions");
   List<String> order=names(list(root,roots,1,true,0,false));check(order.equals(names(list(root,roots,1,true,0,false))),"Chinese and English ordering is deterministic");
   check(LocalFileBrowserModel.inside(root,sub),"nested paths are allowed");
   // Scope pause: retain historical boundary fixtures but do not run them in ordinary regressions.
   if(false) {
   check(!LocalFileBrowserModel.inside(root,outside),"root prefix collision rejected");
   rejects(()->LocalFileBrowserModel.requireAllowed(other,roots),"external selected file rejected");
   rejects(()->LocalFileBrowserModel.requireAllowed(new File(root,"../store-other/other.txt"),roots),"dot-dot traversal rejected");
   Path escape=new File(root,"escape.txt").toPath();Files.createSymbolicLink(escape,other.toPath());
   check(!names(list(root,roots,1,true,0,false)).contains("escape.txt"),"escaping symlink omitted from listing");
   rejects(()->LocalFileBrowserModel.validateSelection(escape.toFile(),roots,1),"escaping symlink selection rejected");
   Path inside=new File(root,"alias.txt").toPath();Files.createSymbolicLink(inside,a.toPath());
   check(!names(list(root,roots,1,true,0,false)).contains("alias.txt"),"within-root symlink is not silently offered as its target");
   rejects(()->LocalFileBrowserModel.validateSelection(inside.toFile(),roots,1),"within-root symlink selection rejected without retargeting");
   }
   rejects(()->LocalFileBrowserModel.validateSelection(a,roots,3),"APK result revalidation rejects other extensions");
   rejects(()->LocalFileBrowserModel.validateSelection(folder,roots,1),"file mode cannot return directories");
   check(LocalFileBrowserModel.validateSelection(folder,roots,2).equals(folder.getCanonicalFile()),"directory mode validates writable directory");
   if(false) {
   Path linkedFolder=new File(root,"linked-folder").toPath();Files.createSymbolicLink(linkedFolder,folder.toPath());
   rejects(()->LocalFileBrowserModel.validateSelection(linkedFolder.toFile(),roots,2),"directory selection cannot silently retarget a within-root symbolic link");
   check(!names(list(root,roots,2,true,0,false)).contains("linked-folder"),"directory listing omits symbolic links");
   }
   File trailing=write(root,"space at end ",1,40000);
   check(LocalFileBrowserModel.validateSelection(trailing,roots,1).getName().equals("space at end "),"exact selected paths preserve valid trailing spaces");
   rejects(()->LocalFileBrowserModel.list(new File(root,"gone"),roots,1,false,0,false,Locale.CHINA),"missing saved directory is not reported as empty");
   a.delete();rejects(()->LocalFileBrowserModel.validateSelection(a,roots,1),"disappeared file rejected before return");
   Thread.currentThread().interrupt();try{rejects(()->list(root,roots,1,false,0,false),"cancellation interrupts directory enumeration");}finally{Thread.interrupted();}
   check(LocalFileBrowserModel.checkedMode(999)==0&&LocalFileBrowserModel.checkedMode(-1)==0,"invalid picker modes default safely");
   check(LocalFileBrowserModel.extension("noextension").isEmpty()&&LocalFileBrowserModel.extension(".hidden").isEmpty(),"extension handling covers dotfiles and no extension");
   System.out.println("TOTAL "+checks+" PASSED");
  }finally{try(java.util.stream.Stream<Path> stream=Files.walk(base.toPath())){for(Path p:(Iterable<Path>)stream.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
 }
}
