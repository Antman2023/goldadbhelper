package io.github.devicebridge.android;
import android.app.*;import android.content.*;import android.hardware.usb.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import android.database.Cursor;import android.view.*;import android.widget.*;import io.github.devicebridge.core.*;import java.io.*;import java.util.*;import java.util.concurrent.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.format.Formatter;

public final class FastbootActivity extends Activity {
 private static final int PAGE=0xfff3f6f7, SURFACE=0xffffffff, STROKE=0xffdce5e8;
 private static final int ACCENT=0xff006761, ACCENT_CONTAINER=0xffe0efed;
 private static final int DANGER=0xff9b2b28, DANGER_CONTAINER=0xfff9eae8;
 private static final String DEVICE_HINT="通过 OTG 连接已进入 Fastboot 模式的设备。";

 private FastbootClient client;
 private TextView deviceStatus, deviceDetail, imageLabel, imageDetails, output;
 private ScrollView pageScroll, outputScroll;
 private LinearLayout consoleSection;
 private int followOutputFrom=-1;
 private Button readInfo, flashButton, runCommand;
 private EditText partition, command;
 private volatile Future<?> running,imagePreparing;
 private File preparedImage;
 private String preparedImageName="",preparedImageSha="";
 private int imageGeneration;
 private volatile boolean destroyed;
 private String imagePickerId;

 private final BroadcastReceiver usb=new BroadcastReceiver(){
  public void onReceive(Context context,Intent intent){
   UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
   if(device==null)return;
   if("io.github.devicebridge.FASTBOOT_PERMISSION".equals(intent.getAction())){
    if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false))connect(device);
    else {
     if(client==null||client.isClosed())deviceDetail.setText("USB 授权被拒绝，请重新选择设备。");
     append("USB授权被拒绝");
    }
   }else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())
           &&client!=null&&client.target().equals("USB "+device.getDeviceName())){
    close();
    deviceDetail.setText("USB 设备已拔出，请重新连接。");
    append("USB设备已拔出，操作中止");
   }
  }
 };
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  buildContent();
  IntentFilter filter=new IntentFilter("io.github.devicebridge.FASTBOOT_PERMISSION");
  filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(usb,filter,Context.RECEIVER_NOT_EXPORTED);
  else registerReceiver(usb,filter);

  SharedPreferences prefs=getSharedPreferences("settings",MODE_PRIVATE);
  if(prefs.getBoolean("show_fastboot_warning",true)){
   CheckBox skip=new CheckBox(this);
   skip.setText("不再显示此说明");
   new AlertDialog.Builder(this).setTitle("Fastboot操作风险")
           .setMessage("刷入错误镜像可能使设备无法启动，擦除分区可能永久丢失数据。只用于你拥有且可以恢复的测试设备。每次刷写仍会单独确认设备、镜像和分区。")
           .setView(skip).setPositiveButton("我已了解",(d,w)->prefs.edit()
                   .putBoolean("show_fastboot_warning",!skip.isChecked()).apply()).show();
  }
 }

 private void buildContent(){
  LinearLayout root=Ui.col(this);
  root.setBackgroundColor(PAGE);
  root.setFocusableInTouchMode(true);
  Ui.inset(this,root);
  Compatibility.statusBarColor(getWindow(),PAGE);

  LinearLayout bar=Ui.row(this);
  bar.setGravity(Gravity.CENTER_VERTICAL);
  bar.setBackgroundColor(SURFACE);
  bar.setPadding(dp(4),dp(4),dp(4),dp(4));
  IconView back=new IconView(this,IconView.Kind.BACK,Ui.INK,"返回",this::onBackPressed);
  back.setBackground(Compatibility.ripple(0x14006761,null,null));
  bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
  TextView title=label("Fastboot",21,Ui.INK);
  title.setTypeface(null,Typeface.BOLD);
  Ui.expanded(bar,title);
  IconView more=new IconView(this,IconView.Kind.MORE,Ui.INK,"更多 Fastboot 选项",this::menu);
  more.setBackground(Compatibility.ripple(0x14006761,null,null));
  bar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
  root.addView(bar);
  root.addView(Ui.divider(this));

  pageScroll=new ScrollView(this);
  pageScroll.setFillViewport(true);
  LinearLayout body=Ui.col(this);
  body.setPadding(dp(16),dp(16),dp(16),dp(24));
  pageScroll.addView(body,new ScrollView.LayoutParams(-1,-2));
  Ui.fill(root,pageScroll);
  addBlock(body,deviceCard(),0);
  addBlock(body,imageCard(),12);
  consoleSection=consoleCard();
  addBlock(body,consoleSection,12);
  setContentView(root);
  root.requestFocus();
  updateDeviceState();
 }

 private LinearLayout deviceCard(){
  LinearLayout card=card();
  LinearLayout heading=Ui.row(this);
  heading.setGravity(Gravity.CENTER_VERTICAL);
  Ui.expanded(heading,heading("设备连接"));
  deviceStatus=label("",12,Ui.MUTED);
  deviceStatus.setPadding(dp(10),dp(4),dp(10),dp(4));
  heading.addView(deviceStatus,new LinearLayout.LayoutParams(-2,-2));
  addBlock(card,heading,0);
  deviceDetail=label(DEVICE_HINT,14,Ui.MUTED);
  addBlock(card,deviceDetail,8);
  addBlock(card,action("选择 USB 设备",this::choose,SURFACE,ACCENT),14);
  readInfo=action("读取设备信息",()->run(client,"设备信息",FastbootClient::deviceInfo),ACCENT,ACCENT_CONTAINER);
  addBlock(card,readInfo,8);
  return card;
 }

 private LinearLayout imageCard(){
  LinearLayout card=card();
  addBlock(card,heading("镜像与分区"),0);
  LinearLayout imageInfo=Ui.col(this);
  imageInfo.setPadding(dp(12),dp(10),dp(12),dp(10));
  imageInfo.setBackground(Ui.rounded(PAGE,dp(10)));
  imageLabel=label("未选择镜像",14,Ui.MUTED);
  imageInfo.addView(imageLabel);
  imageDetails=label("",12,Ui.MUTED);
  imageDetails.setTextIsSelectable(true);
  imageDetails.setVisibility(View.GONE);
  addBlock(imageInfo,imageDetails,6);
  addBlock(card,imageInfo,12);
  addBlock(card,action("选择镜像",this::pickImage,ACCENT,ACCENT_CONTAINER),8);

  addBlock(card,label("目标分区",13,Ui.MUTED),16);
  Spinner choices=new Spinner(this);
  choices.setContentDescription("分区预设");
  choices.setMinimumHeight(dp(48));
  ArrayAdapter<String> presets=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
          new String[]{"recovery","boot","自定义分区"});
  presets.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  choices.setAdapter(presets);
  addBlock(card,choices,4);
  partition=input("输入分区名");
  partition.setText("recovery");
  choices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
   public void onNothingSelected(AdapterView<?> parent){}
   public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
    partition.setText(position==0?"recovery":position==1?"boot":"");
   }
  });
  addBlock(card,partition,4);
  flashButton=action("刷入镜像",this::flash,DANGER,DANGER_CONTAINER);
  addBlock(card,flashButton,12);
  return card;
 }

 private LinearLayout consoleCard(){
  LinearLayout card=card();
  addBlock(card,heading("命令与输出"),0);
  command=input("例如 getvar:product");
  command.setContentDescription("Fastboot 命令");
  command.setTypeface(Typeface.MONOSPACE);
  addBlock(card,command,12);
  runCommand=action("运行命令",this::raw,ACCENT,ACCENT_CONTAINER);
  addBlock(card,runCommand,8);

  outputScroll=new ScrollView(this);
  if(Build.VERSION.SDK_INT>=21)outputScroll.setNestedScrollingEnabled(true);
  outputScroll.setBackground(Ui.rounded(0xff172426,dp(10)));
  outputScroll.setFillViewport(true);
  output=label("",12,0xffe9f2f2);
  output.setHint("暂无输出");
  output.setHintTextColor(0xff9baeb0);
  output.setTypeface(Typeface.MONOSPACE);
  output.setTextIsSelectable(true);
  output.setPadding(dp(12),dp(12),dp(12),dp(12));
  output.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
   if(followOutputFrom>=0&&outputScroll.getScrollY()==followOutputFrom&&!output.hasSelection()){
    outputScroll.scrollTo(0,bottom);
   }
   followOutputFrom=-1;
  });
  outputScroll.addView(output,new ScrollView.LayoutParams(-1,-2));
  LinearLayout.LayoutParams logSize=new LinearLayout.LayoutParams(-1,dp(192));
  logSize.topMargin=dp(12);
  card.addView(outputScroll,logSize);
  return card;
 }

 private LinearLayout card(){
  LinearLayout card=Ui.col(this);
  card.setPadding(dp(14),dp(14),dp(14),dp(14));
  card.setBackground(outlined(SURFACE,14));
  return card;
 }

 private TextView heading(String text){
  TextView heading=label(text,17,Ui.INK);
  heading.setTypeface(null,Typeface.BOLD);
  return heading;
 }

 private TextView label(String text,int size,int color){
  TextView label=new TextView(this);
  label.setText(text);
  label.setTextSize(size);
  label.setTextColor(color);
  return label;
 }

 private EditText input(String hint){
  EditText field=Ui.edit(this,hint);
  field.setTextColor(Ui.INK);
  field.setHintTextColor(Ui.MUTED);
  field.setPadding(dp(12),dp(8),dp(12),dp(8));
  field.setMinimumHeight(dp(48));
  GradientDrawable focused=outlined(SURFACE,10);
  focused.setStroke(dp(2),ACCENT);
  StateListDrawable background=new StateListDrawable();
  background.addState(new int[]{android.R.attr.state_focused},focused);
  background.addState(new int[]{},outlined(SURFACE,10));
  field.setBackground(background);
  return field;
 }

 private Button action(String text,Runnable click,int foreground,int background){
  Button button=new Button(this,null,android.R.attr.borderlessButtonStyle);
  button.setText(text);
  button.setAllCaps(false);
  button.setTextSize(14);
  button.setPadding(dp(12),dp(8),dp(12),dp(8));
  button.setMinimumHeight(dp(48));
  int[][] states={{-android.R.attr.state_enabled},{}};
  button.setTextColor(new ColorStateList(states,new int[]{Ui.MUTED,foreground}));
  StateListDrawable buttonBackground=new StateListDrawable();
  buttonBackground.addState(states[0],Ui.rounded(PAGE,dp(10)));
  buttonBackground.addState(states[1],Compatibility.ripple(0x22002624,Ui.rounded(background,dp(10)),null));
  button.setBackground(buttonBackground);
  button.setOnClickListener(view->click.run());
  return button;
 }

 private GradientDrawable outlined(int color,int radius){
  GradientDrawable background=Ui.rounded(color,dp(radius));
  background.setStroke(dp(1),STROKE);
  return background;
 }

 private void addBlock(LinearLayout parent,View child,int top){
  LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
  params.topMargin=dp(top);
  parent.addView(child,params);
 }

 private int dp(float value){return Ui.dp(this,value);}

 private void updateDeviceState(){
  boolean connected=client!=null&&!client.isClosed();
  deviceStatus.setText(connected?"已连接":"未连接");
  deviceStatus.setTextColor(connected?ACCENT:Ui.MUTED);
  deviceStatus.setBackground(Ui.rounded(connected?ACCENT_CONTAINER:PAGE,dp(20)));
  deviceDetail.setText(connected?client.target():DEVICE_HINT);
  readInfo.setEnabled(connected);
  runCommand.setEnabled(connected);
  updateFlashButton();
 }

 private void updateFlashButton(){
  flashButton.setEnabled(client!=null&&!client.isClosed()&&preparedImage!=null);
 }

 private void pickImage(){
  if(running!=null&&!running.isDone()){
   Ui.message(this,"操作进行中","完成当前操作后才能更换镜像。");
   return;
  }
  imagePickerId=UUID.randomUUID().toString();
  startActivityForResult(LocalFilesActivity.intent(this,LocalFilesActivity.MODE_IMAGE,imagePickerId),1);
 }
 private void menu(){new AlertDialog.Builder(this).setItems(new String[]{"清空输出","重启终端","免ROOT运行说明","重启设备","从镜像临时启动"},(d,w)->{if(w==0)output.setText("");else if(w==1){close();append("会话已重置，请重新选择设备");}else if(w==2)Ui.message(this,"免ROOT运行","通过Android USB Host接口直接使用Fastboot，不需要本机ROOT。仍需Android USB授权和目标设备自身的解锁/刷写权限。");else if(w==3)confirm("重启设备","设备会离开Fastboot模式。",target->run(target,"重启",active->active.reboot()));else boot();}).show();}
 private void choose(){
  if(running!=null&&!running.isDone()){
   Ui.message(this,"操作进行中","完成或明确取消当前操作后才能选择其他设备。");
   return;
  }
  UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
  List<UsbDevice> devices=new ArrayList<>();
  for(UsbDevice device:manager.getDeviceList().values())if(UsbTransport.find(device,3)!=null)devices.add(device);
  if(devices.isEmpty()){
   Ui.message(this,"Fastboot 设备","未发现 USB Fastboot 接口。请确认设备已进入 Fastboot 模式并通过 OTG 连接。");
   return;
  }
  String[] names=new String[devices.size()];
  for(int i=0;i<names.length;i++)names[i]=deviceLabel(devices.get(i),devices.get(i).getDeviceName());
  new AlertDialog.Builder(this).setTitle("选择 USB Fastboot 设备").setItems(names,(dialog,index)->{
   UsbDevice device=devices.get(index);
   if(manager.hasPermission(device))connect(device);
   else{
    Intent permission=new Intent("io.github.devicebridge.FASTBOOT_PERMISSION").setPackage(getPackageName());
    manager.requestPermission(device,PendingIntent.getBroadcast(this,1,permission,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
   }
  }).setNegativeButton("取消",null).show();
 }

 private String deviceLabel(UsbDevice device,String path){
  String product=Compatibility.usbProductName(device);
  return product==null||product.trim().isEmpty()?path:product+"\n"+path;
 }
 private void connect(UsbDevice device){
  if(running!=null&&!running.isDone()){
   append("忽略新设备授权回调，当前设备操作仍在进行");
   return;
  }
  close();
  try{
   client=new FastbootClient(new UsbFastbootTransport((UsbManager)getSystemService(USB_SERVICE),device),
           (kind,message)->append(kind+" "+message));
   updateDeviceState();
   deviceDetail.setText(deviceLabel(device,client.target()));
   append("已连接："+client.target());
  }catch(Exception e){
   deviceDetail.setText("连接失败，请重新选择 USB 设备。");
   append("连接失败："+e.getMessage());
  }
 }
 private void confirm(String title,String detail,java.util.function.Consumer<FastbootClient> action){final FastbootClient bound=client;if(bound==null||bound.isClosed()){Ui.message(this,"未连接","请先选择Fastboot设备");return;}Ui.confirm(this,title,"目标："+bound.target()+"\n"+detail,()->{if(client!=bound||bound.isClosed()){Ui.message(this,"设备会话已改变","原确认已失效，请重新选择设备和操作。");return;}action.accept(bound);});}
 private void flash(){if(preparedImage==null){Ui.message(this,"未选择镜像","请先选择可读镜像文件");return;}final String part=partition.getText().toString().trim();if(!part.matches("[A-Za-z0-9_.-]{1,64}")){Ui.message(this,"分区无效","请输入有效分区名");return;}final File selected=preparedImage;final String selectedSha=preparedImageSha;confirm("刷入镜像","镜像："+preparedImageName+"\n大小："+selected.length()+" B\nSHA256："+selectedSha+"\n分区："+part+"\n此操作将覆盖分区，可能丢失数据或导致设备无法启动。请确认镜像适用于这台设备。",target->run(target,"刷写 "+part,active->{verifyImage(selected,selectedSha);try(InputStream in=new FileInputStream(selected)){active.download(in,selected.length(),(done,total)->{appendProgress(done,total);return !Thread.currentThread().isInterrupted();});return active.flash(part);}}));}
 private void boot(){if(preparedImage==null){Ui.message(this,"未选择镜像","请先选择boot镜像");return;}File chosen=preparedImage;String chosenSha=preparedImageSha;confirm("从镜像临时启动","镜像："+preparedImageName+"\n大小："+chosen.length()+" B\nSHA256："+chosenSha+"\n不主动刷写分区，但镜像启动后能修改设备数据。",target->run(target,"临时启动",active->{verifyImage(chosen,chosenSha);try(InputStream in=new FileInputStream(chosen)){active.download(in,chosen.length(),(d,t)->!Thread.currentThread().isInterrupted());return active.boot();}}));}
 private void raw(){String c=command.getText().toString().trim();if(c.isEmpty())return;confirm("发送Fastboot命令",c+"\n自定义命令可能擦除或修改设备。",target->run(target,c,active->active.executeCommand(c)));}
 private void prepareImage(Uri uri,String name,String expectedToken){
  int expected=++imageGeneration;
  if(imagePreparing!=null)imagePreparing.cancel(true);
  if(preparedImage!=null){LocalSnapshot.dispose(preparedImage);preparedImage=null;}
  updateFlashButton();
  imageLabel.setText(name);
  imageLabel.setTextColor(Ui.INK);
  imageDetails.setVisibility(View.VISIBLE);
  imageDetails.setText("正在缓存并校验…");
  imagePreparing=BridgeRuntime.IO.submit(()->{
   File file=null;
   try{
    file=cache(uri,expectedToken);
    String hash=imageHash(file);
    file.setReadOnly();
    final File ready=file;
    BridgeRuntime.UI.post(()->{
     if(destroyed||imageGeneration!=expected){LocalSnapshot.dispose(ready);return;}
     preparedImage=ready;
     preparedImageName=name;
     preparedImageSha=hash;
     imageDetails.setText(Formatter.formatFileSize(this,ready.length())+"\nSHA-256\n"+hash);
     updateFlashButton();
    });
   }catch(Exception e){
    if(file!=null)LocalSnapshot.dispose(file);
    BridgeRuntime.UI.post(()->{
     if(destroyed||imageGeneration!=expected)return;
     imageDetails.setText("镜像准备失败，请重新选择。");
     append("镜像准备失败："+e.getMessage());
    });
   }
  });
 }
 private static String imageHash(File file)throws Exception{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");digest.update(b,0,n);}}StringBuilder result=new StringBuilder();for(byte v:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return result.toString();}
 private static void verifyImage(File file,String sha)throws Exception{if(!file.isFile()||!sha.equals(imageHash(file)))throw new IOException("镜像内容已改变，原确认失效。请重新选择镜像。");}
 private File cache(Uri uri,String expectedToken)throws IOException{LocalSnapshot snapshot=LocalSnapshot.create(this,uri,expectedToken);if(snapshot.bytes==0){LocalSnapshot.dispose(snapshot.file);throw new IOException("镜像为空");}return snapshot.file;}
 private interface Action {String execute(FastbootClient target)throws Exception;}
 private void run(final FastbootClient bound,String title,Action action){
  if(bound==null||bound.isClosed()||client!=bound){Ui.message(this,"未连接","请先连接Fastboot设备");return;}
  if(running!=null&&!running.isDone()){Ui.message(this,"操作进行中","请等待当前操作结束");return;}
  ProgressDialog dialog=new ProgressDialog(this);
  dialog.setMessage(title+"\n"+bound.target());
  dialog.setCancelable(false);
  dialog.setButton(DialogInterface.BUTTON_NEGATIVE,"取消",(d,w)->{
   if(running!=null)running.cancel(true);
   try{bound.close();}catch(IOException ignored){}
   updateDeviceState();
   deviceDetail.setText("会话已取消，请重新选择 USB 设备。");
   append("已取消；已写入设备的数据不能自动撤销");
  });
  dialog.show();
  running=BridgeRuntime.IO.submit(()->{
   try{
    if(client!=bound)throw new IOException("设备会话已改变");
    append("结果："+action.execute(bound));
   }catch(Exception e){
    append("失败："+e.getMessage());
   }finally{
    BridgeRuntime.UI.post(()->{
     if(destroyed)return;
     dialog.dismiss();
     if(client==bound){
      if(bound.isClosed())updateDeviceState();
      pageScroll.smoothScrollTo(0,consoleSection.getTop()+outputScroll.getTop());
     }
    });
   }
  });
 }
 private long lastProgress;private void appendProgress(long done,long total){long now=System.currentTimeMillis();if(now-lastProgress>1000||done==total){lastProgress=now;append("传输："+done+" / "+total);}}
 private void append(String text){
  BridgeRuntime.UI.post(()->{
   if(destroyed||output==null)return;
   followOutputFrom=!output.hasSelection()&&!outputScroll.canScrollVertically(1)?outputScroll.getScrollY():-1;
   if(output.length()>512000)output.setText(output.getText().subSequence(256000,output.length()));
   output.append(text+"\n");
  });
 }
 private void close(){
  if(running!=null)running.cancel(true);
  if(client!=null)try{client.close();}catch(IOException ignored){}
  client=null;
  if(!destroyed)updateDeviceState();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request!=1||data==null)return;
  if(data.hasExtra(LocalFilesActivity.EXTRA_REQUEST_ID)&&!Objects.equals(imagePickerId,data.getStringExtra(LocalFilesActivity.EXTRA_REQUEST_ID))){
   Ui.message(this,"镜像选择已失效","请重新选择镜像。");
   return;
  }
  if(result==LocalFilesActivity.RESULT_USE_SAF){
   startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),1);
   return;
  }
  Uri image=data.getData();
  if(result!=RESULT_OK||image==null)return;
  String name=image.toString();
  try{
   if("file".equals(image.getScheme()))name=new File(image.getPath()).getName();
   else try(Cursor cursor=getContentResolver().query(image,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
    if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
   }
  }catch(Exception metadata){append("镜像名称不可读取，将使用URI标识");}
  ArrayList<String> tokens=data.getStringArrayListExtra("local_picker_source_tokens");
  prepareImage(image,name,tokens!=null&&!tokens.isEmpty()?tokens.get(0):null);
 }
 public void onBackPressed(){if(running!=null&&!running.isDone()){Ui.confirm(this,"中断Fastboot操作","正在传输或写入设备。离开会中断操作，已写入的数据无法自动恢复，设备可能无法启动。",()->{close();finish();});}else super.onBackPressed();}
 protected void onDestroy(){destroyed=true;imageGeneration++;if(imagePreparing!=null)imagePreparing.cancel(true);close();if(preparedImage!=null)LocalSnapshot.dispose(preparedImage);try{unregisterReceiver(usb);}catch(Exception ignored){}super.onDestroy();}
}
