# CON-05/06 外部 ADB 服务控制器：窄范围合成 UI 回归

日期：2026-10-12（UTC）。结果：**189 条控制器断言 / 17 组情境通过，另 25 条精确对象绑定断言通过，共 214 条。最终版本连续独立编译运行 5 轮通过。**

这是普通、内存 UI stand-ins 驱动的控制器逻辑回归。没有运行 Android VM、原 APK、真实 ADB server/device、外部地址、真实 daemon 停止、刷写或账号操作。没有运行受限第二轮安全审查、路径替换、链接或私有文件 fixture。不代表 Android 像素、实际服务、API 兼容、原版功能一致或 CON-05/06 全面验收。

## 运行与产物

```sh
bash tools/test-host-ui.sh
```

依赖：Bash、Python 3、JDK 编译器，无 Android SDK、Gradle 或 ADB executable。验证环境为 OpenJDK 21.0.12.1，`javac -source 8 -target 8`。

- `results.txt`：最后一轮完整输出。
- `repeated-results.txt`：最终版本连续 5 轮输出。
- `source-hashes.txt`：每轮编译前记录 controller、runtime 及相关 core 的 SHA-256。
- 临时 class 与提取源放在 `/tmp/devicebridge-hostui.*`，退出后清理。
- harness 源仅在 `tests/hostui`；唯一工具入口 `tools/test-host-ui.sh`。没有修改生产源码或 MainActivity、同级 hotfix 工程、冻结 candidate19-v9 或 delivery。

## 实际执行与替代边界

直接编译并执行当前 `ExternalAdbController.java`，没有复制其逻辑。连接、快照、设备选择、停止结果、资源生命周期使用实际 core `HostServerClient`、`AdbSession`、`OperationScope`、`AdbServerEndpoint`。

仅以下组件是内存 stand-ins：

- Android `Activity`、`Dialog`、`AlertDialog`、`SharedPreferences`：捕获文字、按钮和生命周期 flags，无窗口、像素、Android dispatch。
- `Ui`：捕获 prompt/confirm/message 和动作；取消状态是测试模型，不代表执行生产 `Ui` 内部实现。
- `Host`：控制工作执行和完成回调的次序；故意仍向 controller 递送过期成功回调，直接验证 controller 的防护。已取消 scope 直接交给生产 job，由生产 controller/core 拒绝，不由测试 Host 预先短路。
- `BridgeRuntime`：模拟选中 server 的 CAS 与关闭回调；CLOSERS 同步运行，以便确定性检查 core 资源。没有运行生产 Application 或其 Android 线程环境。

合成 server 只监听字面值 `127.0.0.1` 的操作系统分配端口，提供 `host:track-devices-l`、`host:devices-l`、`host-transport-id:<id>:features`、`host:kill` 的小范围回复。`host:kill` 仅在合成进程内返回字符串/EOF，从不执行命令或停止 daemon。没有 shell/SYNC/device 操作实现；其他请求会使测试失败。不重复已有 HostServerTests 99 条协议矩阵。

停止被拒绝时，实际 controller 将真实 `ServerFailure` 交回 Host。测试断言失败类别、拒绝原因保留、未调用成功回调、原 client 仍可用；`操作未完成` 错误窗口由 Host stand-in 按 Host 契约模拟。因此这部分**不能宣称已动态验证 MainActivity.workScoped 的错误窗口或取消后状态报告**。

## 控制器覆盖

1. 刷新菜单明确区分 USB 与外部 server，选择和取消各自正确；弹窗归属登记。
2. 地址默认端口 5037、非法 URL 拒绝、具体合成 endpoint；确认先于连接，取消零请求。
3. 清楚说明“已运行的服务器”、可信端点、设备授权由 server 管理、smart-socket 没有 TLS 身份保证；不声称扫描、配对或自动启动。
4. 在线、offline、unauthorized、no permissions、缺失 ID 均真实列出状态。不可选行拒绝创建 session，说明稳定 transport_id 要求。
5. 同 serial 不同 transport_id 可区分；选第二行实际请求 `host-transport-id:18:features`，不会改选第一行或假报链路 TLS。
6. 空列表与旧 server 失效明确说明；刷新实际发出一次 devices-l，不自动重连旧 server。
7. Home 失效、Activity finishing/destroyed 不打开新 UI；过期地址/确认/设备列表/管理菜单不启动后续工作。
8. 新 server 连接被拒绝时保留旧选择、旧 endpoint 和旧连接，失败候选 socket 清零，不停止任何 server。
9. 新连接完成后 home 失效或 CAS 失败，candidate 被关闭、不保存 endpoint、不弹出旧列表。
10. 成功更换（runtime 模型）关闭旧 core client 和其旧设备绑定，保留新选择，不发送停止旧 server 请求。
11. 过期刷新回复不重画旧 home；已关闭 client 不把旧快照显示为可用；过期选中结果关闭 session，不调用 Host.open。
12. 预取消 connect/refresh/select/stop job 零相应协议请求。这里不重做进行中各握手取消的 99 条 core 回归。
13. 没有外部 server 时准确描述本应用会话重置、没有本机 native server 可重启；取消或过期确认不重置。
14. 管理菜单“重新连接（不重启）”说明正确；“断开本应用”确认范围准确，实际只关本应用 client、不发 kill。
15. 停止确认绑定具体 endpoint、说明所有客户端/会话受影响、操作中断、远端协议不能 start-server、需在宿主启动且不是一键重启；取消/过期确认零请求。
16. 停止三种结局分开：OKAY 仅显示接受停止且尚未启动/连接；FAIL 保留拒绝原因而不成功回调；EOF 显示结果未知、可能已停止、不要自动重试。三者均只发一次 kill，不隐式重连、不声称“已重启/重启成功”。
17. 已执行的停止返回后若 home 过期，不刷新过期 home、不显示旧结果窗口。

## 新增运行时精确对象绑定小回归

`extract-runtime.py` 每次从当前 `BridgeRuntime.java` 提取 `select(AdbSession)` 与 `disconnect(AdbSession)` **原方法原文**，只包装最小内存 sessions/map/active/executor。提取签名不唯一或消失会失败，避免静默测旧副本。

25 条断言覆盖：

- 相同 target 字符串、不同对象的旧卡片，不能选择或断开映射中的新 session。
- 旧/空/已移除对象不改变当前映射与 active，不排队关闭。
- 精确对象可选择；精确对象断开后只移除该对象，当前 active 被清空。
- 关闭通过 executor 排队，执行后只关闭精确对象一次，重复断开不再排队。
- 断开非 active 的精确对象时保留其他 active。

这仅验证两个生产方法的对象绑定逻辑，不代表完整 BridgeRuntime、真实 session 线程、MainActivity 卡片事件或 Android 生命周期已运行。

## 结果与剩余边界

没有发现需修改生产实现的确定 bug。编写 harness 时一条测试对空列表文案过度锁词（期待“服务器”，实际为“服务的宿主”），已把断言改为所需含义“宿主”，没有更改生产文案；最终 5 轮均通过。

真实 Android UI/取消/销毁、实际 AOSP server 的兼容性、真实停止后宿主启动和重新选择、USB 与 raw 直连状态，以及 CON-06 原版本机 kill+start 语义仍须在各自允许范围内独立验收。该 harness 不能替代这些验证。
