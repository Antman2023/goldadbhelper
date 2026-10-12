package io.github.devicebridge.android;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
final class ScreenshotStore {
 static boolean hasDirectory(Context c){return c.getSharedPreferences("settings",0).contains("screenshot_tree");}
 static Uri saveConfigured(Context c,byte[] png)throws IOException{
  String value=c.getSharedPreferences("settings",0).getString("screenshot_tree",null);
  if(value==null)throw new IOException("未设置截图目录");
  Uri target=Uri.parse(value);
  if("file".equals(target.getScheme())){
   File directory=new File(target.getPath());
   String expected=c.getSharedPreferences("settings",0).getString("screenshot_tree_token",null);
   if(expected==null)throw new IOException("截图目录身份尚未确认，请在设置中重新选择本机目录");
   String name="金甲虫ADB助手-"+System.currentTimeMillis()+"-"+java.util.UUID.randomUUID().toString().substring(0,8)+".png";
   File file=new File(directory,name);
   try(Compatibility.Directory bound=Compatibility.bindDirectory(directory,expected);OutputStream out=bound.createExclusive(name)){out.write(png);out.flush();}
   catch(IOException failure){throw new IOException("截图未确认保存；已创建的部分文件不会自动按路径删除，请检查原所选目录："+file.getAbsolutePath(),failure);}
   return Uri.fromFile(file);
  }
  if(!Compatibility.supportsDocumentTree())throw new IOException("Android 4.4 不支持系统目录授权，请改用本机目录或每次选择保存位置");
  return Api21.save(c,target,png);
 }
 private static final class Api21 {
  static Uri save(Context c,Uri tree,byte[] png)throws IOException{
   Uri doc=null;
   try{
    Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
    doc=DocumentsContract.createDocument(c.getContentResolver(),parent,"image/png","金甲虫ADB助手-"+System.currentTimeMillis()+".png");
    if(doc==null)throw new IOException("无法创建截图文件");
    try(OutputStream out=c.getContentResolver().openOutputStream(doc,"wt")){if(out==null)throw new IOException("目录不可写");out.write(png);out.flush();}
    return doc;
   }catch(Exception e){
    String cleanup="";if(doc!=null)try{if(!DocumentsContract.deleteDocument(c.getContentResolver(),doc))cleanup="\n部分截图清理未确认："+doc;}catch(Exception failure){cleanup="\n部分截图清理失败："+doc+" · "+failure.getMessage();}
    throw new IOException("截图未保存，请重新选择可写目录："+e.getMessage()+cleanup,e);
   }
  }
 }
}
