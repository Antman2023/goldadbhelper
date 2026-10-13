# 未完成与待验边界

此清单区分实际缺口、未确认的原版范围和运行验收。150项详细映射见 feature-status.json，源码接入不等于完全一致。

## 仍有明确差异
- 权限狗、安装狮设备所有者、Scene5的当前官方启动/条件资料不足，三项预设暂未启用；小黑屋当前官方脚本模式与原版历史启动方式不同。
- 原版经静态核对是彩色文本转录，不要求完整VT。已补提示符/错误分色、clear末行、真实exit；PTY把stderr合流时不能可靠逐字分色，API19 v5 的普通显示/clear/exit 已实测，后续边界修改需同版回归。
- 递归复制独立helper已接入并通过49个普通断言，待同版设备回归；现代移动新增20个普通后置核验断言。旧API<23移动/重命名仍有明确兼容缺口，跨文件系统移动不能确认原对象身份时保持UNKNOWN。
- 连接会话由独立Java ADB协议客户端管理，不使用原版打包的外部ADB服务。刷新/重启实现机制与原版不同，多个外部ADB客户端共享服务的行为尚不具备。
- 仍须按19张原版有效运行截图逐页校准细节及未采集的弹窗；图标和品牌为独立绘制与新身份。

## 独立身份必须保留的边界
- 不使用原付费身份、解锁密钥、支付订单服务器和商业设备绑定。
- 不冒充原作者反馈渠道或收款码；新主体的外部支持与商业模式尚未配置。
- 原版未定义的未来功能和12项未充分证实的行为保持待证，不凭空补成原版功能。

## 当前实际测试
- API26隔离AVD真实ADB：信息读取、标签图标元数据、文件读取、131101字节上传/下载SHA与字节数、拒绝已有文件、提交前取消、持久PTY目录/变量/中断/退出通过。
- API26兼容截图流显示通过；正常H264因该软件模拟器系统编码库SIGILL无法验收，不能算应用正常投屏成功。
- API19 v4/v5已真实通过metadata标签图标、131101字节双向SHA/普通同名不覆盖/提交前取消、独占PNG+SYNC截图及持续PTY。v5原APK生产类默认JIT已通过TLS1.3互证/exporter/SPAKE/HKDF/AES-GCM，本机模拟配对不等于Android11真实adbd配对。
- API19 v5终端echo/clear/exit后重启/切页已通过；df/进程列表/目标framework可用内存已显示。v6改进旧ps状态列、stat helper、屏幕ART清理等需按具体包复验。
- API26 v2普通选择器文件/目录token与取消3项、终端旋转/切页/重启都通过，最终crash buffer为空。
- 第一轮13类安全问题已独立复核修复。第二轮检查发现本机选择后对象变化问题，已按安全快照、目录句柄绑定继续修复；第二轮独立审查未完成，不记为通过。

## 后续运行门槛
- 在最终同版候选复验最近终端边界/文件helper/屏幕workspace改动，不能把早期不同APK的通过结果自动套到最新版。
- API24 ARM空白AVD两轮均实际完成H264编码和多帧解码，并取得新生成画面；两轮控制验收均被官方SurfaceFlinger同一SIGSEGV/系统ANR阻塞，已停止重复，Back不记通过。普通workspace清理已通过，未知普通sidecar保留并报错；该API24未生成OAT，ART缓存分支需API26覆盖。
- 物理USB短读/拔线、厂商权限、真实Android11+无线配对、H264首帧/旋转/熄屏恢复仍需授权测试设备。
- 磁盘满、长时大文件、SAF权限撤回、设备离线和批次部分失败须保留逐项状态。
- Fastboot刷写/临时启动只通过模拟协议与镜像绑定测试，未对物理设备执行；须专用可恢复设备及具体操作授权。
- 开发APK签名有效仅说明打包/签名结构，不构成全功能、安全性或原版完全一致保证。

## 2026-10-13 稳定性修复（当前源码，尚未设备复验）

- 直接 ADB 通道接近 8 MiB 接收上限时暂缓 `OKAY`，消费至 4 MiB 后恢复发送；共享 reader 不等待该页面或文件消费者。遵循未启用 delayed-ack 扩展的经典 WRTE/OKAY 流控，依据 [AOSP 的 enqueue/send_ready 实现](https://android.googlesource.com/platform/system/core/+/6ac5d7dc56a016718d2a26803435505e983e5d80/adb/adb.cpp)。忽略流控的发送端触发通道错误和明确 `CLSE`，保留其他通道；发送恢复确认失败时该通道进入终态。
- 本地主动关闭/失败时释放缓存，不再交付取消前积压的数据；目标正常关闭时仍可读完已收到的尾部数据。流读写参数校验避免 `off + len` 整数溢出。
- 一次性 shell-v2 收到完整退出状态即完成并关闭自身通道，不继续等待目标 `CLSE`。stdout/stderr 每次回调最多 8192 字节，避免为大帧分配同等大小的临时数组；拒绝错误退出帧、非空 stdin-close 帧和目标侧 window-size 帧。持久 PTY 仍使用 `InteractiveShell`。
- 新增 `AdbStreamFlowTests` 76 条断言和 `ShellClientTests` 54 条断言，已接入两个现有聚合测试入口。12 MiB loopback 传输验证实际 session reader 的暂停/恢复、字节顺序和并行小通道；其他用例使用内存协议替身。
- 写入异常、等待 WRTE 确认超时或中断后，通道先进入失败终态并移出会话，再发送关闭包或关闭外部服务通道；后续写入/flush 不再复用失败通道。原始异常和清理异常共同保留，迟到确认不能恢复通道。新增 28 条流测试断言覆盖这些路径，包括真实线程等待后的中断；当前 25 个可运行 core 入口全部通过，Android 35 完整 Java 编译及 min-api 19 D8 转换再次通过（仍有下述 Conscrypt 警告），不代表设备运行验证。
- 会话状态监听器的 RuntimeException 不再阻止握手、reader 启动及关闭清理；reader 先启动再通知 CONNECTED，回调中可直接使用会话。关闭/失败与通道注册共用状态锁，终态先摘除通道并执行资源清理再通知监听器；回调中取消连接不会被报告为成功。无身份的 AUTH/TLS 握手明确失败并清理 transport。
- 非正 TCP/通道超时与空服务在网络操作前拒绝；预先取消的 OPEN 不发送服务命令，预先取消的 connect 关闭其 transport 且不发送握手。协议定长读取处理短读、零返回和中断，保留线程取消标记，避免零返回空转。内存设备替身独立解码发送者的完整清理包并恢复其取消标记，原有取消/CLSE 断言保留。
- 新增 `AdbSessionLifecycleTests` 42 条内存断言和 `TransportBoundaryTests` 15 条内存/loopback 断言，已接入两个聚合入口；`HostServerTests` 增至 103 条断言，额外验证外部服务会话的监听器异常与回调取消。
- `OperationScope` 在异步取消后保留待清理资源的所有权，显式 close 可接手尚未执行的任务；并发执行只允许一个清理者。执行器拒绝任务时使用后台 daemon 清理；后台线程无法启动时记录未确认清理并保留待重试资源，不在取消调用者上执行阻塞清理。取消/中断后的迟到资源立即关闭，关闭失败与取消异常共同保留。新增 `OperationScopeTests` 42 条内存断言，覆盖队列延迟、真实执行器拒绝、并发取消/清理、迟到资源、清理失败与身份去重。
- 文件复制会保留 shell-v2 返回的已知退出码，非零退出码不能使整体操作显示完成或清除选择剪贴板；已确认的逐文件成功和 SHA-256 回执保留。shell-v1 的未知退出码仍须依赖完整 helper 回执。新增 `FileCopyCompletionTests` 23 条生产 report 映射断言，已接入 `tools/test-file-copy.sh`；使用预填充的已解析回执，不验证 Android JSON 解析或实际文件复制。Android JSON probe 新增 3 条退出状态断言，已编译但未运行。
- 替换连接、按 USB 地址断开和断开全部时，运行时先在锁内摘除旧对象，再在后台关闭捕获的资源；不在界面调用者或运行时锁内执行传输关闭。侧栏断开改为精确会话对象，旧侧栏不能断开同地址的新连接。生产方法原文提取回归增至 53 条断言，覆盖延迟关闭、重连、重复断开及阻塞清理期间继续选择；外部服务 UI 189 条断言通过。此轮完整 Android Java 编译和 min-api 19 D8 转换通过，尚未做真实界面/USB 验收。
- SYNC 在进度回调返回后、读取返回后和操作成功返回前重新检查取消；回调关闭通道但返回 true、最后一段响应读取中取消、源读取返回时中断，以及目标 flush 时关闭通道，都不能继续发送或报告成功。调用者持有的文件流仍不由 SYNC 关闭。`ProtocolTests` 新增 41 条确定时序的内存断言，总计 271 条通过（含原 Fastboot 协议回归）；文件传输、截图、屏幕协议、终端、一次性 shell、基础 ADB 及流控七个相关入口通过。完整 Android Java 编译和 min-api 19 D8 转换通过；这些用例不证明真实设备断线、磁盘满或 SAF 撤权行为已验收。
- 当前 Windows JDK 25 宿主运行通过 25 个 core 测试入口（含上述五个新增入口及 Conscrypt TLS/exporter 配对）和独立 FieldArithmeticTests；文件 helper、pager/手势/邻页回归通过，最新取消源码下生命周期片段 76 条、外部服务 UI 189 条及会话对象绑定 25 条断言通过。Android 35 平台完整 app/core Java 编译及 `--min-api 19` 的 D8 转换通过；Conscrypt 两个隐藏系统 socket 适配器的缺失类型警告仍存在。这些结果不证明 API 19 运行通过，也不包含最终 APK 打包/签名或真机验收。
- 当前 Windows 环境不能完整运行 POSIX 文件模型、本机应用快照路径与 legacy shell 测试（POSIX 路径/文件名语义及 Git Bash 信号管道限制）。Python 发布测试也因临时目录访问限制未通过。未调整生产路径校验或删减这些测试来取得通过；仍需在仓库要求的 Linux Bash 环境运行 `bash tools/test-all.sh`、`bash tools/test-followup-navigation-server.sh` 与 `python3 -m unittest discover -s tests/release -v`。
- 上述新流控、会话生命周期、取消清理、shell 及复制完成状态仍需按同版 APK 哈希在 API 19/26 和实际 USB 连接复验，不沿用历史设备记录。

## 最后兼容收尾（源码状态，须看各APK哈希的运行记录）
- API19/20 pm 的安装/卸载语义已从官方源码确认是全部用户；最终策略必须在确认前固定目标SDK与作用范围。API21+继续生成带--user 0命令，不在失败后自动扩大范围。分包与恢复的低版本边界明确拒绝。
- Fastboot信息查询已补 getvar:all 与 oem device-info 两种入口对应的顺序查询，单个终态FAIL保留另一项结果；传输错误停止后续命令。ProtocolTests新增模拟检查通过，仍未连接Fastboot硬件。OEM查询依据公开LK实现：https://android.googlesource.com/kernel/lk/+/9d6e28f24ba01e563d0b3801c6c6c95d756db623/app/aboot/aboot.c 。
- v7 API26真实普通树复制、文件/目录同文件系统移动及同名保护已通过；实际 oat/x86/scrcpy-server.odex/vdex 生成后，生产屏幕workspace清理无警告且目录消失。这里只运行 list_displays，不将它算视频退出生命周期通过。
