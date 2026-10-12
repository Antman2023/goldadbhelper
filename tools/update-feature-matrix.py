#!/usr/bin/env python3
"""Auditable implementation mapping. Do not promote runtime results from code presence."""
import json,pathlib,collections
root=pathlib.Path(__file__).resolve().parent.parent
source=root.parent/'app_replication_analysis/analysis/feature_matrix.json'
rows=json.loads(source.read_text())
runtime=json.loads((root/'docs/runtime-evidence.json').read_text()) if (root/'docs/runtime-evidence.json').exists() else {}
partial={
 'NAV-02':'冻结0.1.2候选已取得API26连续拖动、驻留、慢滑折叠和有限旋转证据；0.1.3新增首次邻页真实控件预创建，I/O延后提交，其实际Android待验收。精确动画、信息页读取时序及终端后台语义仍未完全对齐。',
 'CON-05':'后续源码已接入外部ADB server连续track/devices-l枚举、稳定transport_id选择及现有模块服务桥接，限显式可信端点；本地合成协议及0.1.2隔离API26自身AOSP server服务桥接已测，证据只对应原SHA；原本机daemon生命周期仍不同。',
 'CON-06':'外部服务管理已接入刷新/重连/仅断本应用及明确确认后的停止，并区分拒绝/接受停止/未知；公开协议不能远程启动，未实现原版本机kill+start。不得称一键重启一致。',
 'TOOLS-04':'当前官方确认4个预设，另3个保留阻塞原因，不能宣称全部一致。',
 'TOOLS-04D':'当前小黑屋官方模式与原版旧starter脚本不同，独立标示。',
 'TOOLS-01':'无线ADB协议动作已实现，当前入口对话框尚未对齐原版状态开关。',
 'FILE-09':'现代移动已增加精确对象/文件hash后置核验；API<23移动明确拒绝，跨文件系统结果保持UNKNOWN。',
 'FILE-11':'现代重命名加入精确后置核验；API<23仍有兼容缺口，不去掉无覆盖保护。',
}
blocked={
 'TOOLS-04E':'缺少当前官方权限狗激活方案，保留历史证据但不猜测执行。',
 'TOOLS-04F':'安装狮设备所有者授权缺完整官方命令/条件/撤销依据，禁止冒险执行。',
 'TOOLS-04G':'Scene5当前/历史版本启动脚本适配未取得充分官方核验。',
}
boundaries={
 'SET-01':'新应用不使用原付费身份/授权服务器；自行提供可实现功能。',
 'SET-07':'新开发主体没有已配置外部反馈渠道，不能冒充原开发者。',
 'SET-11':'未指定新主体收款信息，不复制原收款码。',
 'PRO-07':'原支付订单/授权后台不复用、不冒充，也不绕过原校验。',
 'PRO-08':'原商业设备授权绑定不复用，新应用拥有独立ADB身份。',
 'PRO-09':'未定义的未来功能不构成可验收实现。',
}
for r in rows:
 id=r['id'];prefix=id.split('-')[0]
 status='implemented';note='独立代码已接入；未由代码存在推导运行或完全一致。'
 if id in partial:status='partial';note=partial[id]
 if id in blocked:status='not_implemented';note=blocked[id]
 if id in boundaries:status='product_boundary';note=boundaries[id]
 if prefix=='UNC' or id=='CMD-07':status='reference_uncertain';note='静态证据不足或范围不明确；保留原验收说明，不臆造原版功能。'
 if id=='UNC-12':note='已有19张有效原版运行截图及XML，可作几何/流程对照；尚未完成全部画面与弹窗逐像素验收，原静态清单的无截图说明已由动态证据更新。'
 if id=='UNC-02':status='reference_resolved';note='原版有效运行截图11/12已确认动态九项工具；旧六项布局是残留，不能据此删掉三项功能。'
 if id=='CON-09':note='本机IPv4前缀建议+用户核对确认后探测/24端口5555已接入；真实LAN和取消负载尚未运行验收。'
 if id=='TOOLS-01':note='原版样式状态开关已补入后续源码，按目标实际端口属性显示；最终APK及取消流程待复验。'
 if id in {'APP-10','APP-13','APP-14'}:note='安装/卸载策略按目标SDK固定：API19/20明确全部用户；API21+限定用户0。分包在API<21拒绝，单split/无法判定manifest也不得在旧目标误当base安装。对应真实包变更需独立运行证据。'
 if id=='APP-14':note='0.1.1热修已整合：后台本机PackageManager列表/真实图标/搜索/选择、固定包版本和完整base/splits快照/hash；0.1.1及0.1.2候选01各有API26普通UI证据，0.1.2候选02只改触摸类未重跑选择器全套，API30/OEM权限仍受阻。'
 if id=='APP-15':note='已移除系统包恢复仅在API26+使用官方install-existing；低版本明确说明不可用，未改用猜测命令。'
 if prefix=='PRO' and id not in boundaries:note='独立应用提供对应能力，不人为加入原商业数量限制；原授权门槛不复用。'
 files=['app/src/main/java/io/github/devicebridge/android/MainActivity.java'];tests=[]
 if prefix=='INFO':files+=['app/src/main/java/io/github/devicebridge/android/DeviceInfoView.java']
 if prefix=='CON':files+=['core/src/main/java/io/github/devicebridge/core/AdbSession.java','app/src/main/java/io/github/devicebridge/android/UsbTransport.java'];tests=['CoreTests','UiSafetyTests'];
 if id in {'CON-05','CON-06'}:files+=['core/src/main/java/io/github/devicebridge/core/HostServerClient.java','core/src/main/java/io/github/devicebridge/core/AdbServerEndpoint.java','app/src/main/java/io/github/devicebridge/android/ExternalAdbController.java'];tests+=['HostServerTests','AdbServerEndpointTests']
 if id=='NAV-02':files+=['core/src/main/java/io/github/devicebridge/core/RetainedModuleState.java','app/src/main/java/io/github/devicebridge/android/RetainedModulePager.java','app/src/main/java/io/github/devicebridge/android/CollapsingRoot.java'];tests+=['RetainedModuleStateTests','RetainedPagerGestureTests','Nav02LifecycleTests','NeighborPreparationTests']
 if id in {'CON-07','CON-08'}:files+=['core/src/main/java/io/github/devicebridge/core/PairingClient.java'];tests+=['PairingTests']
 if prefix in {'INFO','TOOLS','APP','PROC'}:files+=['core/src/main/java/io/github/devicebridge/core/DeviceCommands.java','core/src/main/java/io/github/devicebridge/core/DeviceParsers.java'];tests=['DomainTests']
 if id in {'APP-10','APP-13','APP-14','APP-15'}:files+=['core/src/main/java/io/github/devicebridge/core/PackageCommandStrategy.java','core/src/main/java/io/github/devicebridge/core/ApkManifestInspector.java'];tests+=['PackageCommandStrategyTests','ApkManifestInspectorTests']
 if id.startswith('TOOLS-04'):files+=['core/src/main/java/io/github/devicebridge/core/ActivationPresets.java'];tests+=['ActivationTests']
 if prefix=='FILE':files+=['app/src/main/java/io/github/devicebridge/android/LocalFilesActivity.java','app/src/main/java/io/github/devicebridge/android/Compatibility.java','app/src/main/java/io/github/devicebridge/android/LocalSnapshot.java'];
 if prefix=='FILE':files+=['core/src/main/java/io/github/devicebridge/core/SyncClient.java','core/src/main/java/io/github/devicebridge/core/VerifiedFileTransfer.java','server/src/io/github/devicebridge/server/FileCommitServer.java'];tests=['ProtocolTests','UiSafetyTests','FileTransferTests','FileCommitServerTests']
 if id in {'FILE-08','FILE-09','FILE-11'}:files+=['app/src/main/java/io/github/devicebridge/android/FileCopyManager.java','server/src/io/github/devicebridge/server/FileCopyEngine.java','server/src/io/github/devicebridge/server/FileMoveEngine.java'];tests+=['FileCopyEngineTests','FileMoveEngineTests']
 if prefix=='SCR':files=['app/src/main/java/io/github/devicebridge/android/ScreenActivity.java','app/src/main/java/io/github/devicebridge/android/ScreenController.java','core/src/main/java/io/github/devicebridge/core/ScrcpyProtocol.java'];tests=['ScreenProtocolTests','ScreenshotCaptureTests']
 if prefix=='FB':files=['app/src/main/java/io/github/devicebridge/android/FastbootActivity.java','core/src/main/java/io/github/devicebridge/core/FastbootClient.java'];tests=['ProtocolTests','UiSafetyTests']
 if prefix=='CMD':files+=['core/src/main/java/io/github/devicebridge/core/InteractiveShell.java'];tests=['CoreTests','InteractiveShellTests','LegacyShellTests']
 if prefix=='SET':files=['app/src/main/java/io/github/devicebridge/android/SettingsActivity.java'];tests=[]
 r.update(implementation_status=status,implementation_note=note,implementation_sources=files,related_mock_suites=tests,runtime_status='not-run',physical_device_status='not-run',reference_parity='not-confirmed')
for r in rows:
 evidence=[]
 for run in runtime.get('runs',[runtime]):
  if r['id'] in run.get('passed_partial',{}):
   evidence.append({'candidate_sha256':run['candidate_sha256'],'scope':run['passed_partial'][r['id']],'report':run['report'],'run':run.get('name','legacy')})
 if evidence:
  r['runtime_status']='tested-on-emulator-partial'
  r['runtime_evidence']=evidence
(root/'docs/feature-status.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
counts=collections.Counter(r['implementation_status'] for r in rows)
(root/'docs/feature-summary.json').write_text(json.dumps({'total':len(rows),'status_counts':counts,'warning':'Implemented means source integrated only. Test-suite association does not prove every feature accepted. APK is a development build; full parity is not established.'},ensure_ascii=False,indent=2)+'\n')
print(dict(counts),len(rows))
