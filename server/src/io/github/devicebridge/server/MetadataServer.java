package io.github.devicebridge.server;
import android.content.Context;import android.content.pm.*;import android.graphics.*;import android.graphics.drawable.Drawable;import android.util.Base64;import org.json.JSONObject;import java.io.*;import java.lang.reflect.*;import java.util.*;
/** Independent metadata helper executed with an explicitly authorized ADB shell. */
public final class MetadataServer {
 public static void main(String[] args)throws Exception{
  if(args.length==2&&"stat".equals(args[0])){if(!args[1].startsWith("/")||args[1].indexOf('\0')>=0)throw new IllegalArgumentException("Absolute file path required");io.github.devicebridge.core.AndroidFileAccess.Stat stat=new io.github.devicebridge.core.AndroidFileAccess().lstat(args[1]);System.out.println(Long.toHexString(stat.mode)+"|"+stat.size+"|"+stat.modifiedSeconds+"|"+stat.uid+"|"+stat.gid);return;}
  if(android.os.Looper.myLooper()==null)android.os.Looper.prepare();
  Class<?> c=Class.forName("android.app.ActivityThread");Object thread=c.getMethod("systemMain").invoke(null);Context context=(Context)c.getMethod("getSystemContext").invoke(thread);PackageManager pm=context.getPackageManager();
  Long frameworkAvailable=null;
  try{android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);if(memory.availMem>=0)frameworkAvailable=memory.availMem;}catch(Exception ignored){}
  for(PackageInfo p:pm.getInstalledPackages(PackageManager.GET_DISABLED_COMPONENTS)){
   try{ApplicationInfo a=p.applicationInfo;JSONObject j=new JSONObject();j.put("packageName",p.packageName);j.put("label",String.valueOf(pm.getApplicationLabel(a)));j.put("enabled",a.enabled);j.put("system",(a.flags&ApplicationInfo.FLAG_SYSTEM)!=0);j.put("versionName",p.versionName);j.put("versionCode",android.os.Build.VERSION.SDK_INT>=28?Api28.version(p):p.versionCode);j.put("sourceDir",a.sourceDir);j.put("firstInstallTime",p.firstInstallTime);j.put("lastUpdateTime",p.lastUpdateTime);j.put("targetSdk",a.targetSdkVersion);if(android.os.Build.VERSION.SDK_INT>=24)j.put("minSdk",Api24.minSdk(a));else j.put("minSdkUnavailable",true);j.put("size",new File(a.sourceDir).length());
    if(frameworkAvailable!=null)j.put("frameworkAvailableBytes",frameworkAvailable);
    try{android.content.Intent launcher=pm.getLaunchIntentForPackage(p.packageName);if(launcher!=null&&launcher.getComponent()!=null)j.put("launchComponent",launcher.getComponent().flattenToShortString());}catch(Exception ignored){}
    if(args.length>0&&"icons".equals(args[0])){try{Drawable d=pm.getApplicationIcon(a);Bitmap b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);d.setBounds(0,0,64,64);d.draw(canvas);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);j.put("icon",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));b.recycle();}catch(Exception ignored){}}
    System.out.println(j.toString());
   }catch(Exception e){JSONObject j=new JSONObject();j.put("packageName",p.packageName);j.put("error",e.getClass().getSimpleName());j.put("metadataUnavailable",true);j.put("label",p.packageName);System.out.println(j.toString());}
  }
 }
 private static final class Api24 { static int minSdk(ApplicationInfo a){return a.minSdkVersion;} }
 private static final class Api28 { static long version(PackageInfo p){return p.getLongVersionCode();} }
}
