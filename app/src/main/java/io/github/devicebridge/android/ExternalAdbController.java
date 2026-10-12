package io.github.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import io.github.devicebridge.core.AdbServerEndpoint;
import io.github.devicebridge.core.AdbSession;
import io.github.devicebridge.core.HostServerClient;
import io.github.devicebridge.core.OperationScope;
import java.util.List;
import java.util.function.Consumer;

/** Explicit opt-in front end for an external ADB smart-socket server. */
final class ExternalAdbController {
    interface Job<T>{T run(OperationScope scope)throws Exception;}
    interface Host {
        boolean current();
        <T> void run(String title,Job<T> job,Consumer<T> done);
        void open(AdbSession session);
        void refreshHome();
        void chooseUsb();
    }
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    ExternalAdbController(Activity activity,Host host){this.activity=activity;this.host=host;prefs=activity.getSharedPreferences("settings",Activity.MODE_PRIVATE);}
    private boolean current(){return !activity.isFinishing()&&!activity.isDestroyed()&&host.current();}
    private void own(Dialog dialog){if(activity instanceof Ui.DialogOwner)((Ui.DialogOwner)activity).ownDialog(dialog);}
    private boolean selected(HostServerClient client){return current()&&BridgeRuntime.get().externalServer()==client;}

    void refresh(){
        if(!current())return;
        HostServerClient client=BridgeRuntime.get().externalServer();
        if(client!=null&&client.state()==HostServerClient.State.CONNECTED){refresh(client);return;}
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(client==null?"刷新设备列表":"旧服务已失效，请重新选择")
            .setItems(new String[]{"检查USB设备","连接外部ADB服务"},(d,which)->{if(!current())return;if(which==0)host.chooseUsb();else configure();})
            .setNegativeButton("取消",null).show();
        own(dialog);
    }
    private void refresh(HostServerClient client){
        if(!selected(client))return;
        host.run("刷新外部ADB设备",scope->client.refresh(15000,scope),snapshot->{if(selected(client))showDevices(client,snapshot);});
    }
    void configure(){
        if(!current())return;
        final HostServerClient expected=BridgeRuntime.get().externalServer();
        String address=expected==null?prefs.getString("external_adb_server","127.0.0.1:5037"):expected.endpoint();
        Ui.prompt(activity,"外部ADB服务地址（默认端口5037）",address,value->{
            if(!current()||BridgeRuntime.get().externalServer()!=expected)return;
            final AdbServerEndpoint endpoint;
            try{endpoint=AdbServerEndpoint.parse(value);}catch(IllegalArgumentException e){Ui.message(activity,"服务地址无效",e.getMessage());return;}
            String detail="服务："+endpoint+"\n这是已运行的ADB服务器，不是设备无线调试端口。仅连接你管理且可信的服务。\n\n设备身份、授权与设备链路加密由该服务管理；本应用到该服务的smart-socket连接没有TLS身份保证。不会自动扫描、配对或启动服务器。";
            if(expected!=null)detail+="\n\n成功更换后会关闭金甲虫ADB助手在旧服务上的会话；不会停止旧服务器或其他客户端。";
            Ui.confirm(activity,"连接外部ADB服务",detail,()->{
                if(!current()||BridgeRuntime.get().externalServer()!=expected)return;
                host.run("连接ADB服务 "+endpoint,scope->{
                    HostServerClient client=scope.own(new HostServerClient(endpoint.host,endpoint.port));
                    client.connect(20000);scope.checkActive();scope.release(client);return client;
                },client->{
                    if(!current()||!BridgeRuntime.get().replaceExternalServer(expected,client)){
                        BridgeRuntime.CLOSERS.execute(client::close);return;
                    }
                    prefs.edit().putString("external_adb_server",endpoint.toString()).apply();
                    showDevices(client,client.snapshot());
                });
            });
        });
    }
    private void showDevices(HostServerClient client,HostServerClient.Snapshot snapshot){
        if(!selected(client))return;
        if(!snapshot.valid||client.state()!=HostServerClient.State.CONNECTED){Ui.message(activity,"服务连接已失效","请重新连接服务并选择设备；不会按旧序列号自动重绑。");return;}
        List<HostServerClient.Device> devices=snapshot.devices;
        String[] labels=new String[devices.size()];
        for(int i=0;i<labels.length;i++){
            HostServerClient.Device device=devices.get(i);
            labels[i]=device.label()+"\ntransport_id: "+(device.transportId.isEmpty()?"未提供，不能可靠选择":device.transportId);
        }
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle("外部ADB设备 · "+client.endpoint());
        if(devices.isEmpty())builder.setMessage("服务未列出设备。请在该服务的宿主上连接设备后刷新。");
        else builder.setItems(labels,(dialog,index)->{
            if(!selected(client))return;
            HostServerClient.Device device=devices.get(index);
            if(!device.canConnect()){
                Ui.message(activity,"设备不可选择","序列号："+device.serial+"\n状态："+device.state+"\n需要在线device状态和稳定transport_id；请在服务器宿主处理授权或更新ADB后刷新。");return;
            }
            host.run("选择 "+device.serial,scope->{
                AdbSession session=scope.own(new AdbSession(client,device,null));
                session.connect();scope.checkActive();scope.release(session);return session;
            },session->{
                if(!selected(client)){BridgeRuntime.CLOSERS.execute(session::close);return;}
                host.open(session);
            });
        });
        AlertDialog dialog=builder.setPositiveButton("刷新",(d,w)->{if(selected(client))refresh(client);})
            .setNeutralButton("更换服务",(d,w)->{if(selected(client))configure();})
            .setNegativeButton("关闭",(d,w)->{if(current())host.refreshHome();}).show();
        own(dialog);
    }
    void manage(){
        if(!current())return;
        final HostServerClient client=BridgeRuntime.get().externalServer();
        if(client==null){
            Ui.confirm(activity,"重置本应用ADB会话","将断开金甲虫ADB助手当前所有直连会话，正在执行的操作会中断。\n独立Java客户端没有需重启的本机native ADB服务器。",()->{if(current()&&BridgeRuntime.get().externalServer()==null){BridgeRuntime.get().disconnectAll();host.refreshHome();}});return;
        }
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("ADB服务 · "+client.endpoint())
            .setItems(new String[]{"刷新设备列表","重新连接服务（不重启）","断开本应用的服务连接","停止外部ADB服务器"},(d,w)->{
                if(!selected(client))return;
                if(w==0)refresh(client);
                else if(w==1)configure();
                else if(w==2)Ui.confirm(activity,"断开本应用",client.endpoint()+"\n仅关闭金甲虫ADB助手的服务连接和关联会话；不停止服务器，不影响其他客户端。",()->{
                    if(selected(client)&&BridgeRuntime.get().replaceExternalServer(client,null))host.refreshHome();
                });
                else stop(client);
            }).setNegativeButton("取消",null).show();
        own(dialog);
    }
    private void stop(HostServerClient client){
        if(!selected(client))return;
        Ui.confirm(activity,"停止外部ADB服务器","服务："+client.endpoint()+"\n将影响使用此服务器的所有客户端及设备会话，正在进行的操作会中断。\n\n公开host协议没有远程start-server；停止后须在服务器宿主重新启动，再回到金甲虫ADB助手重新连接。这不是一键重启。",()->{
            if(!selected(client))return;
            host.run("停止外部ADB服务",scope->{
                scope.checkActive();scope.record("目标服务："+client.endpoint()+"\n已开始停止请求。取消或断连不保证服务器未停止；结果未知时请先在宿主检查，不要盲目重试。");
                try{client.stopServer(15000,scope);return "服务器已接受停止请求。尚未启动或重新连接；请在宿主启动后重新选择设备。";}
                catch(HostServerClient.StopOutcomeUnknown e){return "停止结果未知，服务器可能已经停止。请先检查宿主状态，勿自动重试。\n"+e.getMessage();}
            },message->{if(current()){host.refreshHome();Ui.message(activity,"ADB服务停止结果",client.endpoint()+"\n"+message);}});
        });
    }
}
