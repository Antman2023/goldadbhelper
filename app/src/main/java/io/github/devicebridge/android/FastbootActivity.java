package io.github.devicebridge.android;
import android.app.*;import android.content.*;import android.hardware.usb.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import android.database.Cursor;import android.view.*;import android.widget.*;import io.github.devicebridge.core.*;import java.io.*;import java.util.*;import java.util.concurrent.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.format.Formatter;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FastbootActivity extends Activity {
 private static final int PAGE=0xfff3f6f7, SURFACE=0xffffffff, STROKE=0xffdce5e8;
 private static final int ACCENT=0xff006761, ACCENT_CONTAINER=0xffe0efed;
 private static final int DANGER=0xff9b2b28, DANGER_CONTAINER=0xfff9eae8;
 private static final String DEVICE_HINT="通过 OTG 连接已进入 Fastboot 模式的设备。";

 private FastbootClient client;
 private Object sessionToken;
 private Operation operation;
 private boolean closingSession;
 private TextView deviceStatus, deviceDetail, imageLabel, imageDetails, output;
 private TextView operationTitle, operationDetail;
 private LinearLayout operationPanel;
 private ProgressBar progress;
 private ScrollView pageScroll, outputScroll;
 private LinearLayout consoleSection;
 private int followOutputFrom=-1;
 private Button chooseDevice, readInfo, selectImage, flashButton, runCommand;
 private IconView more;
 private Spinner choices;
 private EditText partition, command;
 private Future<?> imagePreparing;
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
    boolean interrupted=operation!=null;
    disconnect("USB 设备已拔出，请重新连接。");
    append(interrupted?"USB 设备已拔出，操作中断。请核对设备端结果。":"USB 设备已拔出。");
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
  more=new IconView(this,IconView.Kind.MORE,Ui.INK,"更多 Fastboot 选项",this::menu);
  more.setBackground(Compatibility.ripple(0x14006761,null,null));
  bar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
  root.addView(bar);
  root.addView(Ui.divider(this));
  LinearLayout.LayoutParams operationSize=new LinearLayout.LayoutParams(-1,-2);
  operationSize.setMargins(dp(16),dp(8),dp(16),0);
  root.addView(operationPanel(),operationSize);

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
  updateDeviceState(DEVICE_HINT);
 }

 private LinearLayout operationPanel(){
  operationPanel=card();
  operationTitle=heading("");
  addBlock(operationPanel,operationTitle,0);
  operationDetail=label("",13,Ui.MUTED);
  addBlock(operationPanel,operationDetail,6);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
  progress.setMax(1000);
  if(Build.VERSION.SDK_INT>=21){
   ColorStateList tint=ColorStateList.valueOf(ACCENT);
   progress.setProgressTintList(tint);
   progress.setIndeterminateTintList(tint);
  }
  addBlock(operationPanel,progress,8);
  addBlock(operationPanel,action("取消操作",this::cancelOperation,DANGER,DANGER_CONTAINER),8);
  operationPanel.setVisibility(View.GONE);
  return operationPanel;
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
  chooseDevice=action("选择 USB 设备",this::choose,SURFACE,ACCENT);
  addBlock(card,chooseDevice,14);
  readInfo=action("读取设备信息",()->run(client,"读取设备信息",(active,transfer)->active.deviceInfo()),ACCENT,ACCENT_CONTAINER);
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
  selectImage=action("选择镜像",this::pickImage,ACCENT,ACCENT_CONTAINER);
  addBlock(card,selectImage,8);

  addBlock(card,label("目标分区",13,Ui.MUTED),16);
  choices=new Spinner(this);
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
  command.setImeOptions(EditorInfo.IME_ACTION_GO);
  command.setOnEditorActionListener((view,actionId,event)->{
   if(actionId!=EditorInfo.IME_ACTION_GO)return false;
   if(runCommand.isEnabled())raw();
   return true;
  });
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

 private void updateDeviceState(String detail){
  boolean connected=client!=null&&!client.isClosed();
  deviceStatus.setText(closingSession?"正在断开":connected?"已连接":"未连接");
  deviceStatus.setTextColor(connected?ACCENT:Ui.MUTED);
  deviceStatus.setBackground(Ui.rounded(connected?ACCENT_CONTAINER:PAGE,dp(20)));
  deviceDetail.setText(detail);
  updateControls();
 }

 private boolean busy(){return operation!=null||closingSession;}

 private void updateControls(){
  boolean idle=!busy();
  boolean available=idle&&client!=null&&!client.isClosed();
  chooseDevice.setEnabled(idle);
  selectImage.setEnabled(idle);
  more.setEnabled(idle);
  more.setAlpha(idle?1f:.45f);
  partition.setEnabled(idle);
  choices.setEnabled(idle);
  command.setEnabled(available);
  readInfo.setEnabled(available);
  runCommand.setEnabled(available);
  flashButton.setEnabled(available&&preparedImage!=null);
 }

 private void pickImage(){
  if(busy()){
   Ui.message(this,"操作进行中","完成当前操作后才能更换镜像。");
   return;
  }
  imagePickerId=UUID.randomUUID().toString();
  startActivityForResult(LocalFilesActivity.intent(this,LocalFilesActivity.MODE_IMAGE,imagePickerId),1);
 }
 private void menu(){
  if(busy())return;
  new AlertDialog.Builder(this).setItems(new String[]{"清空输出","断开 USB 会话","免ROOT运行说明","重启设备","从镜像临时启动"},(d,w)->{
   if(busy())return;
   if(w==0){followOutputFrom=-1;output.setText("");outputScroll.scrollTo(0,0);}
   else if(w==1){disconnect(DEVICE_HINT);append("会话已重置，请重新选择设备");}
   else if(w==2)Ui.message(this,"免ROOT运行","通过Android USB Host接口直接使用Fastboot，不需要本机ROOT。仍需Android USB授权和目标设备自身的解锁/刷写权限。");
   else if(w==3)confirm("重启设备","设备会离开Fastboot模式。",target->run(target,"重启设备",(active,transfer)->active.reboot()));
   else boot();
  }).show();
 }
 private void choose(){
  if(busy()){
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
  if(busy()){
   append("忽略新设备授权回调，当前设备操作仍在进行");
   return;
  }
  FastbootClient previous=client;
  client=null;
  sessionToken=null;
  closeClient(previous);
  try{
   Object token=new Object();
   client=new FastbootClient(new UsbFastbootTransport((UsbManager)getSystemService(USB_SERVICE),device),
           (kind,message)->BridgeRuntime.UI.post(()->{
    if(sessionToken==token&&operation!=null)append(kind+" "+message);
   }));
   sessionToken=token;
   updateDeviceState(deviceLabel(device,client.target()));
   append("已连接："+client.target());
  }catch(Exception e){
   updateDeviceState("连接失败，请重新选择 USB 设备。");
   append("连接失败："+e.getMessage());
  }
 }
 private void confirm(String title,String detail,java.util.function.Consumer<FastbootClient> action){final FastbootClient bound=client;if(bound==null||bound.isClosed()){Ui.message(this,"未连接","请先选择Fastboot设备");return;}Ui.confirm(this,title,"目标："+bound.target()+"\n"+detail,()->{if(client!=bound||bound.isClosed()){Ui.message(this,"设备会话已改变","原确认已失效，请重新选择设备和操作。");return;}action.accept(bound);});}
 private void flash(){
  String part=partition.getText().toString().trim();
  if(!part.matches("[A-Za-z0-9_.-]{1,64}")){partition.setError("请输入有效分区名");return;}
  confirmImage("刷入镜像","刷写 "+part,
          "分区："+part+"\n此操作将覆盖分区，可能丢失数据或导致设备无法启动。请确认镜像适用于这台设备。",
          active->active.flash(part));
 }
 private void boot(){
  confirmImage("从镜像临时启动","临时启动","不主动刷写分区，但镜像启动后能修改设备数据。",FastbootClient::boot);
 }
 private interface ImageCommand {String execute(FastbootClient target)throws Exception;}
 private void confirmImage(String title,String operationTitle,String detail,ImageCommand command){
  File selected=preparedImage;
  if(selected==null){Ui.message(this,"未选择镜像","请先选择可读镜像文件");return;}
  String sha=preparedImageSha;
  confirm(title,"镜像："+preparedImageName+"\n大小："+selected.length()+" B\nSHA256："+sha+"\n"+detail,target->{
   if(preparedImage!=selected){Ui.message(this,"镜像已改变","请核对新镜像并重新确认。");return;}
   run(target,operationTitle,(active,transfer)->{
    verifyImage(selected,sha);
    try(InputStream in=new FileInputStream(selected)){
     active.download(in,selected.length(),transfer);
     return command.execute(active);
    }
   });
  });
 }
 private void raw(){
  String value=command.getText().toString().trim();
  if(value.isEmpty()){command.setError("请输入 Fastboot 命令");return;}
  confirm("发送Fastboot命令",value+"\n自定义命令可能擦除或修改设备。",
          target->run(target,value,(active,transfer)->active.executeCommand(value)));
 }
 private void prepareImage(Uri uri,String name,String expectedToken){
  int expected=++imageGeneration;
  if(imagePreparing!=null)imagePreparing.cancel(true);
  if(preparedImage!=null){LocalSnapshot.dispose(preparedImage);preparedImage=null;}
  updateControls();
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
     updateControls();
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
 private interface Action {String execute(FastbootClient target,FastbootClient.Progress transfer)throws Exception;}
 private static final class Operation {
  final String title;
  final CountDownLatch workerFinished=new CountDownLatch(1);
  private final AtomicBoolean workerClaimed=new AtomicBoolean();
  Future<?> task;
  long lastProgress;
  Operation(String title){this.title=title;}
  boolean begin(){return workerClaimed.compareAndSet(false,true);}
  void cancel(){
   task.cancel(true);
   // A task cancelled in the queue never enters its worker's finally block.
   if(begin())workerFinished.countDown();
  }
 }

 private void run(final FastbootClient bound,String title,Action action){
  if(bound==null||bound.isClosed()||client!=bound){Ui.message(this,"未连接","请先连接Fastboot设备");return;}
  if(busy()){Ui.message(this,"操作进行中","请等待当前操作结束");return;}
  Operation started=new Operation(title);
  operation=started;
  operationTitle.setText("正在执行 · "+title);
  operationDetail.setText(bound.target());
  progress.setProgress(0);
  progress.setIndeterminate(true);
  operationPanel.setVisibility(View.VISIBLE);
  View focus=getCurrentFocus();
  if(focus instanceof EditText){
   InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
   if(keyboard!=null)keyboard.hideSoftInputFromWindow(focus.getWindowToken(),0);
   focus.clearFocus();
  }
  updateControls();
  started.task=BridgeRuntime.IO.submit(()->{
   if(!started.begin())return;
   try{
    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("操作已取消");
    String result=action.execute(bound,(done,total)->publishProgress(started,done,total));
    BridgeRuntime.UI.post(()->finishOperation(started,"完成 · "+title,result));
   }catch(Exception e){
    String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
    BridgeRuntime.UI.post(()->finishOperation(started,"失败 · "+title,detail));
   }finally{
    started.workerFinished.countDown();
   }
  });
 }
 private boolean publishProgress(Operation expected,long done,long total){
  long now=SystemClock.elapsedRealtime();
  if(done==0||done==total||now-expected.lastProgress>=500){
   expected.lastProgress=now;
   BridgeRuntime.UI.post(()->{
    if(operation!=expected||destroyed)return;
    String size=Formatter.formatFileSize(this,done)+" / "+Formatter.formatFileSize(this,total);
    progress.setIndeterminate(done==total);
    if(done==total)operationDetail.setText("镜像已发送，等待设备响应");
    else{
     int scaled=(int)(1000*done/total);
     progress.setProgress(scaled);
     operationDetail.setText("传输 "+scaled/10+"% · "+size);
    }
    append("传输："+done+" / "+total+" B");
   });
  }
  return !Thread.currentThread().isInterrupted();
 }
 private void finishOperation(Operation expected,String result,String detail){
  if(operation!=expected||destroyed)return;
  operation=null;
  operationPanel.setVisibility(View.GONE);
  if(client.isClosed())updateDeviceState("USB 会话已结束，请重新选择设备。");
  else updateControls();
  append(detail.isEmpty()?result:result+"\n"+detail);
  Object finishedSession=sessionToken;
  pageScroll.post(()->{
   if(!destroyed&&!busy()&&sessionToken==finishedSession){
    pageScroll.smoothScrollTo(0,consoleSection.getTop()+outputScroll.getTop());
   }
  });
 }
 private void cancelOperation(){
  if(operation==null)return;
  String title=operation.title;
  disconnect("已请求取消，请重新选择 USB 设备。");
  append("已请求取消 · "+title+"\n设备收到的操作可能已经生效，请核对设备状态。");
 }
 private void append(String text){
  if(destroyed||output==null)return;
  followOutputFrom=!output.hasSelection()&&!outputScroll.canScrollVertically(1)?outputScroll.getScrollY():-1;
  if(output.length()>512000)output.setText(output.getText().subSequence(256000,output.length()));
  output.append(text+"\n");
 }
 private static void closeClient(FastbootClient target){
  if(target!=null)try{target.close();}catch(IOException|RuntimeException ignored){}
 }
 private void disconnect(String detail){
  Operation previous=operation;
  operation=null;
  if(previous!=null)previous.cancel();
  FastbootClient disconnected=client;
  client=null;
  sessionToken=null;
  if(disconnected!=null){
   closingSession=true;
   BridgeRuntime.CLOSERS.execute(()->{
    closeClient(disconnected);
    if(previous!=null){
     try{previous.workerFinished.await();}
     catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    BridgeRuntime.UI.post(()->{
     closingSession=false;
     if(!destroyed)updateDeviceState(deviceDetail.getText().toString());
    });
   });
  }
  if(!destroyed){
   operationPanel.setVisibility(View.GONE);
   updateDeviceState(detail);
  }
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
  if(busy()){
   Ui.message(this,"操作进行中","请在当前操作结束后重新选择镜像。");
   return;
  }
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
 @Override public void onBackPressed(){
  if(operation!=null){
   Ui.confirm(this,"中断Fastboot操作","正在传输或写入设备。离开会中断操作，已写入的数据无法自动恢复，设备可能无法启动。",
           ()->{disconnect(DEVICE_HINT);finish();});
  }else super.onBackPressed();
 }
 @Override protected void onDestroy(){
  destroyed=true;
  imageGeneration++;
  if(imagePreparing!=null)imagePreparing.cancel(true);
  disconnect(DEVICE_HINT);
  if(preparedImage!=null)LocalSnapshot.dispose(preparedImage);
  try{unregisterReceiver(usb);}catch(IllegalArgumentException ignored){}
  super.onDestroy();
 }
}
