# CON-05/06：外部 ADB server 客户端实现与未闭环边界

状态：2026-10-12，独立 core 实现和本地合成协议验证完成。尚未在 Android 实机或真实 AOSP server 验证；尚未作为用户交付验收。v9 与 delivery 冻结产物未修改。此文件不将本次 core 进度视为 CON-05/06 全功能完成。

## 原版证据与当前差异

只读静态来源位于同级 `app_replication_analysis`：

- `analysis/interface_inventory.md` CON-05、CON-06：刷新设备列表，启动/重启 ADB 服务。
- `decompiled/com.didjdk.adbhelper.ConnectActivity.java` 构造器：server 端口初始化为 5037。菜单动作本体为 native，不能从入口可见推断已动态验证。
- `decompiled/c.c.a.p.java`：有 `adb -P <port> kill-server` 与 `start-server` 调用；特定启动错误下端口递增至 5047。设备列表解析关注状态和 transport_id；设备对象 `c.c.a.u3.a.java` 同时提取 serial/model。列表更新会重新组织可用设备及所选设备。
- `analysis/protocol_dependency_inventory.md`：ADB client → server、设备列表和 `-t <id>` 证据。
- `analysis/runtime_verification_matrix.md`：菜单入口已观察；刷新/重启动作没有原版动态验证。

此前独立实现的“刷新”只重绘应用持有的 AdbSession 并查看 USB，“重启”只 disconnectAll。它们没有查询 server 的跨客户端设备表，也没有执行 daemon 生命周期，不能称原版功能一致。

原版共享 server 进程的 client 可以看到共同 transport 表，属于 ADB 拓扑语义。静态证据并不能证明原版存在“任意远端 server 地址设置”界面。本次显式外部 server 客户端是独立协议实现的集成入口，不据此补写未经验证的原版 UI 行为。

## 公开协议依据

- [AOSP host/services 文档](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/services.md)：4 位十六进制字符串长度、OKAY/FAIL、devices-l、跟踪、选择 transport 后的设备服务、kill。
- [AOSP adb.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/adb.cpp)：transport-id、定向 features；host:kill 的接受/拒绝处理。
- [AOSP services.cpp 固定提交](https://android.googlesource.com/platform/packages/modules/adb/+/1641648f2186930ee28107686e75591cd47c1cee/services.cpp)：track-devices-l 的 LONG_TEXT 返回。
- [AOSP adb_client.cpp 固定标签](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-14.0.0_r27/client/adb_client.cpp)：start-server 由 client 本地启动进程；远端 host 无法借 server smart-socket 协议启动其已停止的 daemon。

没有使用原 APK 或商业 native/helper 字节，没有启动原二进制、VM 或真实设备连接。

## 已实现的独立组件

`HostServerClient` 是客户端，不监听或发现任何端点。连接地址必须由调用者显式提供。

- 4 位十六进制长度 framing，严格 OKAY/FAIL、UTF-8、长度、截断和超时检查。
- track-devices-l 连续连接作为一次 client epoch；devices-l 手动刷新；不可变快照和模型字段。
- 去掉完全重复的行；冲突重复 transport_id 拒绝；保留 unauthorized/offline/无 ID 设备用于说明。只允许 state=device 且有有效 transport_id 连接，不按 serial 猜测回退。
- transport_id 按无符号 64 位十进制字符串保存，不经有符号 long 截断。
- 完全相同在线 transport 刷新保留选择；消失、offline、同 serial 换 ID 使旧会话失效。重新出现也不恢复旧 incarnation。
- tracker EOF/协议错误/关闭使整个 epoch 与其所有会话失效。重试必须创建新 client 并显式选择新快照，旧快照不能跨 client 使用。
- 每个业务流新建 smart socket，固定 host:transport-id，再发送 shell/sync/exec 等服务；接入现有 AdbSession/AdbStream，沿用现有业务模块。
- host-wide 请求不能经设备 session.open 发送，防止设备服务入口误操作整个共享 server。
- 单个会话关闭只关自己的 socket。没有 host:disconnect、重新配对、授权身份复制或全局副作用。
- 每个连接 socket 在 connect/read 前已进入 OperationScope。取消手动刷新仅取消该请求；取消 session 关闭其握手与所有服务 socket；取消新 client 关闭 tracker 握手。
- 旧刷新响应不能覆盖已经发布的更新 tracker 快照或更新刷新请求。无自动设备重连、无取消后的复活。
- stopServer 发送一次 host:kill。FAIL 是明确拒绝；OKAY 仅表示接受停止；丢失确认是 StopOutcomeUnknown，不自动重试。接受停止在返回前关闭旧 session，即使 tracker EOF 抢先到达也如此。

AdbSession 的 raw CNXN/AUTH/STLS、多流 ADB 分支保留，host 分支不把 smart socket 包装成假 CNXN 流。

## Android 集成 API

下面是接口契约，不代表 UI 已集成验收：

1. `new HostServerClient(host, port)`，新连接前 `scope.own(client)`；后台 `client.connect(timeoutMs)`；成功转交 runtime 持有后 `scope.release(client)`。
2. `client.snapshot()` 返回 `Snapshot.devices/revision/valid`。`client.refresh(timeoutMs, scope)` 单独查询 devices-l；取消 scope 不关闭共享 client。
3. Device 公开 `serial/state/model/product/device/transportId` 与 `canConnect()/label()`。
4. `new AdbSession(client, device, listener)`，scope 先 own，后台 connect 成功后移交 runtime。AdbSession 增加 `isHostServer()/serverEndpoint()`。后端不可假报为直连 TLS；server → 设备的加密状态不由此客户端验证。
5. `setListener((state,snapshot,detail)->...)` 在工作线程回调；监听器不能持有已经销毁的 Activity，UI 必须在主线程更新。可 setListener(null)。
6. `stopServer(timeoutMs, scope)` 需要 UI 明确确认具体 server、影响所有共享客户端、无自动启动能力。状态必须区分拒绝、接受停止和结果未知。
7. `close()` 释放 client 和全部该 client 绑定的 session；单 session.close 不关闭 client。

## 确认仍然缺少的功能

- CON-06 原版的本机 kill + start 语义未闭环。没有自行编写/监听 ADB server，也没有嵌入可启动的 AOSP daemon。公开协议没有远程 start-server；停止后只能由 server 所在主机启动，再新建 client。不得把 stop 或 disconnectAll 标作“重启成功”。
- 原版默认 5037 和重试到 5047 的本机 daemon 生命周期未复制。外部端点不自动选择端口、扫描或尝试无关服务。
- Android USB 直连 transport 不自动发布到外部 ADB server，本应用的 raw 直连 session 也不自动变成其他 client 可见的共享设备。
- 本次没有实现 host:connect 建立新的设备连接。只使用外部 server 已有的枚举 transport；它们的授权归该 server 身份所有。
- ADB host TCP 通道本身无身份认证/加密保证。必须使用用户可信端点；不在 UI 推导其设备侧 TLS 安全属性。
- ADB 协议没有 server boot nonce。连续 tracker epoch、transport_id 和 incarnation 可阻止已观察到的断线、重启、离线和 serial 复用后的自动回绑，但不能提供恶意 server 的密码学身份保证，或排除尚未观察到 tracker EOF 时新进程极短 ID 复用窗口。不得据此声称绝对远端身份绑定。
- 跨不同 TCP 连接的 server 枚举没有全局顺序号；本地序列只拒绝已观察到的新事件之后的旧刷新响应。真实服务器跨连接竞争时的完整行为仍需实测。最终设备操作始终由 transport-id 再选择，失效选择不会按 serial 自动恢复。
- Android API19 编译/打包与真机生命周期、实际 server 版本兼容，以及原版动态对照均未在本次运行。服务器必须支持 track-devices-l、transport-id 与 features；不支持时明确失败，不偷偷降级到宽泛目标。

## 验证结果

命令：`bash tools/test-host-server.sh`。仅合成 loopback 端点和既有内存/原始协议 fixture，无真实 adb server/device。

HostServerTests 99 断言，另连续重复 10 轮通过。覆盖共享表、长列表解析、去重、unsigned ID、shell-v2/SYNC/双向流、离线/serial 复用/epoch、取消隔离、各握手取消、旧响应、kill 的三个结局、重复操作、畸形/截断/FAIL/超时。

原 wire 回归：CoreTests 12，DomainTests 244，ProtocolTests 230，ScreenProtocolTests 53，InteractiveShellTests 716，LegacyShellTests 52，LegacyCloseTests 7，ScreenshotCaptureTests 183，TerminalTranscriptTests 138035，均通过。没有运行受限第二轮安全审查、路径替换测试或 APK/VM 设备测试。

变更文件：

- core/src/main/java/io/github/devicebridge/core/HostServerClient.java（新）
- core/src/main/java/io/github/devicebridge/core/AdbSession.java
- core/src/main/java/io/github/devicebridge/core/AdbStream.java
- core/src/test/java/io/github/devicebridge/core/HostServerTests.java（新）
- tools/test-host-server.sh（新）
- docs/EXTERNAL_ADB_SERVER.md（新）

重复运行记录：最终 `test-host-server.sh` 整体通过。此前某次回归的既有 ScreenshotCaptureTests 曾在“cancel explicitly closes the active ADB channel during exec”时序断言失败；单独复跑及最终完整脚本复跑通过，本次未修改该截图测试或功能，也不将这一非稳定结果掩盖成所有轮次全通过。HostServerTests 在重复运行中发现并修正了停止 ACK 与 tracker EOF 的竞态：控制请求继续以独立可取消、有限超时 socket 读取结果，不被设备 epoch 清理抢先关闭。修正后再连续 10 轮通过。

## 0.1.2 后续集成（尚未交付）

0.1.1 本机应用热修已通过精确 v9 基线三方合并到主工程；热修独立目录及已交产物不变。MainActivity 连接页“刷新设备列表”现在可显式连接外部 server，后续刷新查询 devices-l；“重启ADB服务”保留原菜单位置，但进入的说明/管理不会把重置本应用或停止服务称为一键重启。

- ExternalAdbController 在主页 generation 与 Activity 生命周期内持有对话框；连接成功移交 Application 的 CAS 外部服务槽位。失效回调关闭返回的连接/会话，不覆盖新选择。
- 设备表明确显示状态和 transport_id，仅在线、有稳定 ID 的项可选。旧表选择由 core incarnation 再检查。
- 重连、更换、断开本应用、停止整个服务使用不同确认文本；停止明确影响所有共享客户端，并说明本应用不能远程启动已停 daemon。
- 直连/USB和外部会话的状态标签区分开；外部server不能被误标为已验证的设备TLS通道。
- MainActivity 会话卡片使用对象身份条件select/disconnect，旧同名卡片不能选中或关闭新对象。
- 地址解析34项与core HostServer99项在整合后的源码再次通过；模块驻留、元数据和既有wire协议也有独立有界普通回归。控制器合成测试、候选APK和Android运行验收分别记录，不能从本节推导通过。

公开协议的启动边界再次与Android14 AOSP adb_client.cpp核对：远程服务未运行时客户端报告不能远程启动；本机启动使用launch_server。仅host:kill接受不能证明启动或完成重启。来源：https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-14.0.0_r27/client/adb_client.cpp
