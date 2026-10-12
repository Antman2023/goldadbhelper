# 本机自管 ADB host server：只读可行性收敛

日期：2026-10-12。对象：金甲虫ADB助手的 CON-05/06。本文只新增设计文档，不修改冻结的 0.1.2 代码、APK 或现有交付物。本轮只读取既有工程、原版静态分析和官方公开资料；没有下载安装新软件、编译/运行新 native 源码、运行原 APK/native 资产、连接真实设备、改系统/网络设置或复验屏控。

## 结论

**有基于公开 AOSP 源码、按许可证自行构建 Android 宿主 ADB server 的可行路线；本轮尚未证明该移植能在 armv7/API19 和 arm64/API21 上编译、安装、启动。** 不能承诺“加一个官方现成二进制即可完成”，也不能据“远程 smart-socket 没有 start-server”断言 Android 本机做不到。

最小方案是：保留现有 smart-socket 客户端，增加一个薄的本机进程控制器，加上自行构建并随 APK 安装的 AOSP host server。进程控制器完成启动、确认就绪、完整停止后重建、5037–5047 有界端口退避；不重新发明 ADB server 协议。USB transport 接入和现代无线配对是明确的后续 native 集成工作，不能因空设备表 server 启动成功便算全部完成。

当前唯一应申请的下一步是**官方 NDK r25c 和一个隔离的小型 native 构建验证**，先证实 ABI/API、APK 包装和 server 启动门槛。若源码闭包超出已批准范围，报告精确新增依赖后再继续；不先下载巨型 AOSP，不改用来源不明的 Android adb 成品或第三方移植包。

## 1. 原规格与工程现状：已核实

- `../app_replication_analysis/analysis/interface_inventory.md:60–61` 定义 CON-05 刷新设备列表、CON-06 启动/重启 ADB 服务。
- `../app_replication_analysis/decompiled/com.didjdk.adbhelper.ConnectActivity.java:52`：初始 server 端口为 5037。
- `../app_replication_analysis/decompiled/c.c.a.p.java:35–55`：等待一次执行完成标记；匹配某个解码后的错误子串时，端口小于 5047 则加一，重建 `adb -P <port>` 前缀，执行 `kill-server` 后 `start-server`。本轮没有解密该子串，不能把它精确断言为所有 `EADDRINUSE` 情况；也没有重新做原版动态验证。
- `../app_replication_analysis/analysis/protocol_dependency_inventory.md` 和 `decompiled/c.c.a.s3.java`、`c.c.a.t3.java`：原版含 ARM32 adb、Java USB bulk 桥，5008 桥接监听存在；关键读写进入 native。该证据不能证明原版 fd 接入方式或全部运行兼容性。
- 现有 `core/.../HostServerClient.java` 只实现 server 客户端；`host:kill` 不产生后续 server 进程。`docs/EXTERNAL_ADB_SERVER.md` 已明确这一差距。
- `app/build.gradle` 与 manifest：minSdk 19、targetSdk 35。现有 `UsbTransport.java` 由 Java `UsbManager` 授权/open/claim/bulkTransfer 直接收发；这些已有会话不会自动进入新 AOSP server 的 transport 表。

因此原版“重启”包含本机进程生命周期，现有外部 server 的“停止”或直连会话 `disconnectAll` 均不等价。

## 2. 本机工具链：已核实与未核实

指定 SDK：`/workspace/shared/device_bridge_toolchain/android-sdk`。读取目录确认有 command-line tools、build-tools 35.0.0、platform 35、platform-tools 和若干已下载 system images，**没有 `ndk/` 或 `ndk-bundle/`**；对指定工具链及常见 `/usr`、`/opt`、`/workspace/shared` 的有限深度只读检查也未发现另一个 NDK。不是对整个机器做过无限范围搜索的声明。

对 `platform-tools/adb` 执行 `file` 的结果是 **ELF64 x86-64 GNU/Linux PIE，解释器 `/lib64/ld-linux-x86-64.so.2`**。它是开发电脑用工具，不是 Android ARM/bionic 可执行文件。不能放进 APK 后声称支持 arm32 或 arm64。JDK、Gradle 的既有安装批准不等于此 native 移植已具备 NDK；当前 PATH 检查未找到 cmake/ninja/clang，存在 `/usr/bin/readelf`。

官方 NDK 修订记录明确 r26 移除 API19/20；r25 系列是最后支持 KitKat 的系列。具体固定版本可用 **r25c / 25.2.9519653**，官方旧版列表给出 Linux 包 531,118,193 bytes、SHA-1 `53af80a1cce9144025b81c78c8cd556bff42bd0e`。这是已停止维护的旧工具链，需要如实记录。当前官方发布页已有 r30（30.0.16248370），不能因为它较新就用来兑现 API19。

来源：[NDK 修订记录](https://developer.android.com/ndk/downloads/revision_history)、[官方旧版下载与校验值](https://github.com/android/ndk/wiki/unsupported-downloads#r25c)、[当前 r30 发布页](https://github.com/android/ndk/releases/tag/r30)。下载前仍应重新核对官方页面和下载结果，不把读取页面当作安装成功。

ABI/API 必须分别记账：

| APK/产物 | 最低宿主 Android API | 含义 |
|---|---:|---|
| APK manifest | 19 | 允许整个应用在 API19 安装，不证明每份 native 产物兼容 |
| armeabi-v7a server | 19 | r25c 可作为工具链候选，所有依赖也必须按 19 构建 |
| arm64-v8a server | 21 | 64 位 Android 从 21 起；不存在本项目所需的 arm64/API19 组合 |

可使用 r25c 的 `armv7a-linux-androideabi19-clang++` 与 `aarch64-linux-android21-clang++` 分开验证。r24/r25 不支持非 NEON 老 ARM；如果“API19”还要求极老非 NEON 设备，需另行限定 CPU 范围，或评估官方 r23c，不能隐含保证。64 位 API21 最低要求由 [NDK 维护者说明](https://github.com/android/ndk/issues/1751) 明确区分。交叉构建入口见 [NDK other build systems](https://developer.android.com/ndk/guides/other_build_systems)。

## 3. AOSP 源码能提供什么

### 直接证据

当前 AOSP `Android.bp` 将 `adb` 定义为 `cc_binary_host`、`libadb_host` 定义为 `cc_library_host_static`，以 `ADB_HOST=1` 编译。源码中虽然也有 Android 设备目标 `adbd`，后者是被控制端 daemon，不能拿它代替本项目需要的 host server。Soong 平台构建并不是可直接交给 NDK 的 CMake 工程。

`client/main.cpp` 有 `adb_server_main`、smart-socket listener、transport 初始化、认证、就绪 ACK 和清理顺序；`client/commandline.cpp` 支持 `server nodaemon`、`-L SOCKET` 和 `-P`。`client/adb_client.cpp` 明确只有本地 socket 才调用 `launch_server`；远程端点失败不会启动远端进程。其停止逻辑还等待 socket orderly shutdown，说明 `OKAY` 本身不等于旧 server 已退出。

来源：[当前构建图](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/Android.bp)、[server 入口](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/main.cpp)、[命令行](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/commandline.cpp)、[固定 Android14 client 实现](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-14.0.0_r27/client/adb_client.cpp)。

### 工程判断，尚待小编译证明

- ADB host 的 POSIX/Linux 部分原则上可交叉编译到 Android/bionic，在普通应用 UID 下监听高位 loopback 端口，不天然需要 root。这是源码结构和 Android 平台能力支持的移植判断，**不是当前源码在 NDK 下零修改可编译的证明**。
- 要创建独立构建清单、生成版本头、选择源码、修复平台内部头和新 API 的引用，并约束运行时路径、扫描行为、ABI 和依赖。依赖 `libbase`/`libcrypto` 等应随组件从公开源码构建，不能链接设备里同名的私有平台 `.so`。
- 若仅验证 lifecycle，可先做精简 runner，调用 AOSP server 入口并保留同一套 smart-socket/transport 核心，避免把完整 adb CLI 的安装优化、增量安装、压缩等依赖一并带入。裁减必须是显式编译配置和适配；仅设置环境变量不会自动删除链接依赖。
- 若追求与现有现代无线功能一致，应固定较新的 AOSP 提交，保留相应 pairing/TLS 依赖。先验证旧版较小源码集只能证明 native 路线，不应据此交付旧协议降级版。

本轮没有选定和下载一个可发布版本的完整 source lock；尚无成功 link map，故不声称已经拿到最小、完整、可重现的依赖闭包。

## 4. 源码与许可证门槛

以下为构建图可见的依赖族和最小化方向，不是已完成的逐文件许可证审计或最终 SBOM。

| 部分 | 官方源码/组件 | 约束与可裁减边界 |
|---|---|---|
| host server 核心 | `platform/packages/modules/adb`；较老版本在 `platform/system/core/adb` | AOSP ADB Apache-2.0；保留 NOTICE/版权、标注修改。固定提交 |
| Android C++ 工具/套接字/认证辅助 | libbase、libcutils 的所需子集、liblog、libcrypto_utils/android-pubkey，以及版本对应 libutils/diagnose_usb | 有些是平台内部目标，需独立构建或薄 NDK 适配，不能靠 Android 系统私库；逐文件保留许可 |
| RSA/TLS/SPAKE2 | AOSP external/boringssl 或匹配的官方 BoringSSL 版本；ADB crypto/tls/pairing_auth/pairing_connection | 不可一律宣称所有历史版本仅 Apache；旧 BoringSSL 有 OpenSSL/ISC 等历史条款，按锁定版本 LICENSE 和文件头汇总 |
| 现代配对/host 状态 | ADB proto + protobuf；视具体版本需 host `protoc` | 生成器版本、生成文件和运行库必须匹配；若新增生成器需列入授权与来源清单 |
| USB 原生后端（可选于第一阶段） | 官方 libusb + 自写 fd 导入适配 | libusb LGPL-2.1-or-later；静态链接有重链接材料/对象文件等合规要求；只放一个许可证文本不一定足够 |
| 自动发现（可从第一阶段排除） | mdnsresponder、OpenScreen discovery/platform 等 | 多仓库、各自许可；禁用发现要同时移除/适配链接引用，不只是 `ADB_MDNS=0` |
| 完整 CLI 才易带入 | libandroidfw、fastdeploy 生成物、ziparchive、protobuf-full、brotli/lz4/zstd/z 等 | 不是“启动一个空表 host server”的必要产品范围；若保留完整 CLI，依赖规模明显增加 |
| C++ runtime | NDK libc++ | 可静态链接进入独立 PIE，保留 NDK/LLVM 相应许可；所有 native 依赖仍需按目标 API 编译 |

公开来源：[ADB NOTICE](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/NOTICE)、[Android10 较小 host 构建图](https://android.googlesource.com/platform/system/core/+/android10-release/adb/Android.bp)、[官方 BoringSSL 当前 LICENSE](https://boringssl.googlesource.com/boringssl/+/HEAD/LICENSE)、[AOSP 历史 BoringSSL 文件许可例](https://android.googlesource.com/platform/external/boringssl/+/refs/heads/android11-mainline-media-swcodec-release/linux-aarch64/crypto/fipsmodule/sha512-armv8.S)、[官方 libusb 许可](https://github.com/libusb/libusb/blob/master/COPYING)。

许可证总体允许按条件自行修改、构建和分发；不需要原甲壳虫作者的专有二进制、JNI 或凭据。**最终“可合法分发”仍以锁定版本和链接方式完成合规清单为条件**，本文不是已做法律审定的声明。可以优先让第一阶段不含 libusb/mDNS，从而缩小构建验证范围；这不能被包装成 USB 已完成。

## 5. 最小进程包装及完整 restart

推荐只新增一个本机 `LocalAdbDaemonController` 类别的薄控制层，沿用现有 `HostServerClient` 进行列表/业务通信。此处名称是设计示意，未新增生产类。

1. 由用户点击启动，在工作线程创建受管进程，使用安装目录中的绝对 native 路径和参数数组。示意调用为 `<nativeLibraryDir>/libgoldenbeetle_adb.so -L tcp:127.0.0.1:5037 server nodaemon`。如果采用精简 runner，可保留等效入口，不必实现整套 CLI。`-L` 与 `-P/-H` 不混用，不经 `sh -c` 拼接。
2. `nodaemon` 的含义是“不自行脱离父进程”；它仍执行完整 ADB host server 角色。应用保留 Process 句柄、退出监视和 stderr/stdout 排空，避免再创建常驻 shell、启动器和孙进程。
3. 禁用不需要的 USB 全局枚举、模拟器端口扫描和 mDNS 自动发现。仅接入已由用户选择的端点/USB。设置版本对应的 app-private HOME/Android 配置目录、TMPDIR 和日志目录；源码实际读取哪个环境变量需按 source lock 验证。不要将 `ADB_VENDOR_KEYS` 当成控制全部密钥存储的万能开关。
4. 记录独占启动代次和实际成功端口；就绪必须由该子进程的 startup ACK/结构化状态和可用 smart socket 共同确认。仅探测“5037 能连”无法证明是本进程。建议对 runner 增加极小的结构化启动错误/实例标记，避免匹配本地化日志猜错误。
5. 端口只尝试 5037–5047（含端点，最多 11 个）。仅 bind 冲突进入下一个端口；缺 ABI、exec 被拒、缺依赖、API 符号错误、密钥路径不可写应直接失败并给出原因。不能靠预先 bind 再释放消除竞态，以实际 server bind 为准。
6. 只对本应用拥有且仍验证有效的 server 实例执行停止。**不得照抄向每个候选端口先 `kill-server` 的副作用，误杀其他应用的 adb。** 未知占用端口应跳过。此项保留端口退避结果语义，并明确安全收窄原静态脚本行为。
7. 完整重启顺序：阻止新操作 → 使旧 HostServerClient epoch/所有会话失效 → 请求旧 server 停止 → 观察旧 Process 真正退出并收回 transport/fd → 创建新 server → 确认新实例就绪 → 新建 client/tracker → 刷新列表。`host:kill` 的 OKAY、断开 Java session、刷新 UI 都不能代替进程退出。
8. 重复点击合并为单个进行中的操作；用户取消后不再启动；杀进程的强制回收仅作用于已拥有的 Process，不能 `killall adb`。失败/结果未知/启动成功分别展示。若进程被系统回收，显式变为断开，不能自动恢复危险的旧任务。

最终所有需要“共享 transport 表”的本机设备会话应进入该 server，现有 direct-USB/direct-TLS 和 daemon 后端的选择必须可辨认，不能将 Java 会话列表拼接成伪 server 表。普通 loopback TCP 也不是 Android 每应用隔离或认证通道；在已批准产品边界中如实说明这项性质，不以“127.0.0.1”宣称安全隔离。本轮未启动新的安全审查或攻击测试。

完整闭环比薄生命周期层多出三项实际工作：TCP 连接入口改为 server 的 `host:connect:<endpoint>` 等已选定版本支持的连接服务；USB 由授权 fd/薄桥注册到 server，移交原 Java transport 所有权；认证/配对、取消、设备选择和业务 stream 全部以新 server epoch 为准。停止旧 Java reader/writer、释放接口之后才允许另一套栈接管，同一接口不能并抢。当前 `HostServerClient` 尚无该 host:connect 集成，必须单列实现与测试。因此“native server 空表启动、重启成功”只完成生命周期关卡，不能宣称 CON-05/06 的统一共享枚举全部完成。Android10 的 [host service 实现](https://android.googlesource.com/platform/system/core/+/android10-release/adb/services.cpp) 已有 connect 与 track-devices-l，是可复用协议证据；当前项目仍未完成这些连接入口的迁移。

## 6. Android10 的 W^X 与 APK 安装

官方规则：target29+ 应用不能直接 exec 位于自身可写 home/files/cache 的文件。当前应用 target35，故“assets 中放 adb → 解压 files → chmod → exec”不是可接受的现代实现路线。改低 target 或借系统 linker 执行可写文件都不应成为绕过方案。[Android10 官方说明](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)

最小候选：分别构建真正的 ARM32/ARM64 **PIE 可执行 ELF**，以 native packaging 名称 `lib/armeabi-v7a/libgoldenbeetle_adb.so` 和 `lib/arm64-v8a/libgoldenbeetle_adb.so` 打包，让安装器提取至 `ApplicationInfo.nativeLibraryDir`，从该只读安装位置 exec。扩展名是包装约定，不能把一个无入口的普通 shared library 重命名后当可执行文件。使用 AGP 对应的 `jniLibs.useLegacyPackaging=true`/提取配置，核对实际生成 manifest 和安装文件；不能假设默认提取，也不能 exec `base.apk!/lib/...` 虚拟路径。

证据是官方提取配置文档和 AOSP 原生库安装器对 `lib/<abi>/lib*.so` 的筛选、提取可执行权限。**将 PIE 作为该包装项执行在本项目的具体系统矩阵上仍需实测，不是官方承诺任意 `.so` 都可执行。** 来源：[application native 提取](https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs)、[AOSP 安装器示例](https://android.googlesource.com/platform/frameworks/base/+/fd81041/core/jni/com_android_internal_content_NativeLibraryHelper.cpp)。

还需检查：ARM32 解释器 `/system/bin/linker`、ARM64 `/system/bin/linker64`；没有 glibc/桌面路径；无 TEXTREL；DT_NEEDED 仅公开系统库或自己打包的依赖；旧 Android 动态加载器兼容。不要照抄原 assets adb 的“静态 ELF/Android30 标记”来推导 API19 运行能力。现代 ARM64 的 16KB page-size 设备需额外 ELF 对齐与运行验证；旧 r25c 不会自动给出全部新兼容保证。[16KB 官方要求](https://developer.android.com/guide/practices/page-sizes)

## 7. Android12+ 的后台与子进程

AOSP Android12 有 phantom child process 跟踪/裁剪；已读源码按系统追踪列表与 `MAX_PHANTOM_PROCESSES` 比较，并可能清理子进程，异常情况下连父进程一起清理。相关常量的默认值为 32，**不是每个应用可保证拥有 32 个子进程的配额**；OEM/版本行为可以不同。后台高 CPU、内存压力和应用生命周期都需作为可恢复断开处理，前台通知不是“不死进程”保证。

来源：[Android12 PhantomProcessList](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/am/PhantomProcessList.java)、[AOSP 默认上限](https://android.googlesource.com/platform/frameworks/base/+/183f8d21c8c6/services/core/java/com/android/server/am/ActivityManagerConstants.java)。

最小生命周期实现先限定用户可见操作期运行；确需后台保持连接，再使用由可见界面启动的 foreground service，提供常驻通知和停止入口。target31+ 的后台发起 FGS 受限制；target34+ 还要匹配 service type 和权限。USB 场景的 `connectedDevice` 有 `UsbManager.requestPermission()` 条件；不能无条件认为当前 manifest 的 INTERNET 足够满足所有网络型 FGS 条件。当前工程尚未声明该 service。

来源：[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[connectedDevice 类型与前提](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)。不要求用户关系统保护、不改变 phantom-process 上限、SELinux 或电池系统策略来通过验收。

## 8. USB fd 无 root 接入：可行但需要适配

已证据支持的路径：

1. Android 应用用 `UsbManager.requestPermission` 取得用户对具体 USB 设备的授权，再 `openDevice`。
2. `UsbDeviceConnection.getFileDescriptor()` 自 API12 起是官方给 native code 使用的 fd 接口。
3. 同进程 native 可使用该 fd；独立 adb 子进程应通过 app-private Unix socket 的 `SCM_RIGHTS` 接收 fd。Android `LocalSocket.setFileDescriptorsForSend` 自 API1 起提供该能力。**不能把 Java 进程里的整数 fd 作为 CLI 参数，就假定另一进程拥有同一个 fd。** 进程创建也不能假定保留非标准 fd/CLOEXEC 状态。
4. 官方 libusb 的 `libusb_wrap_sys_device` 自 1.0.23 起可包装 Linux 已打开的设备 fd，官方文档明确 Android 无通用设备访问权限时可关闭全局设备发现。新旧 libusb 的初始化选项写法需匹配所选版本。保持原 fd 有效到 libusb handle 关闭之后，正确处理 detach/取消/close。

来源：[UsbDeviceConnection API](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#getFileDescriptor())、[LocalSocket fd 传递](https://developer.android.com/reference/android/net/LocalSocket#setFileDescriptorsForSend(java.io.FileDescriptor[]))、[libusb wrap_sys_device](https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html)、[libusb 官方 Android 支持](https://github.com/libusb/libusb/wiki/Android)。

**仍需独立开发：** AOSP USB backend 中导入此 handle 并注册 transport 的薄接口、USB ADB interface/端点描述、claim 的唯一所有者、断开清理和重新授权。stock adb 不会自动取得应用的 UsbManager 授权；直接枚举/open `/dev/bus/usb` 也不会继承 Java 授权能力。Java 与 native 不能同时消费同一个 bulk endpoint。本路径不需要 root，但 USB OTG/host 硬件支持、驱动/SELinux/ioctl 行为和 OEM 差异仍需自有真机验收。

原版 Java TCP5008 bridge 可证明“Android USB API 与 adb native 之间有桥”这一设计方向；它不能证明当前的 Java `UsbTransport` 无修改就能作为透明原始包桥。若采用该更接近原版的薄桥，仍需明确 framing、header/payload 边界、短包/零包和资源回收，并避免额外开放局域网端口。无需为此另造整套 ADB server。

设备侧“允许 USB 调试”/RSA 或无线配对仍是另一层授权；宿主 USB permission 不会绕过它。新 daemon 应生成和保存自己的主机身份；现有 Java 直连身份不会自动迁移成为 daemon 的身份，不复制原版密钥。新增持久认证信任必须在真实设备阶段取得相应用户授权。

### 首次空表启动也可能写入 adbkey

这不是仅到真实设备阶段才会发生的副作用。官方 `client/auth.cpp` 的 `adb_auth_init → load_userkey` 会检查 `adb_get_android_dir_path()/adbkey`，不存在就生成并写入密钥；server 初始化入口会调用该认证初始化。已读官方 `adb_utils.cpp` 版本通过 `HOME` 找宿主目录，再创建 `.android`；`ADB_VENDOR_KEYS` 是另加的密钥加载路径。来源：[官方认证初始化](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/client/auth.cpp)、[官方目录实现的固定提交](https://android.googlesource.com/platform/packages/modules/adb/+/d86f7f30f61e1242bb3b63002713e3c1ff4a98ec/adb_utils.cpp)。具体锁定版仍要确认目录行为。

本轮没有读任何现有主机密钥，也没有运行该初始化。后续只编译/ELF检查不会主动产生 ADB 主机密钥；**启动测试前还应取得对应的明确确认，允许在独立临时测试 HOME 中生成本次测试身份**，不从当前用户 HOME、原应用或生产应用载入身份，不授权任何真实设备，不上传或打印私钥。需要长期产品身份/真实配对时再按实际目标确认。不能把“NDK下载安装获批”或“空设备表”当成所有认证副作用均已获批。

## 9. 分级验收，不能互相替代

| 关卡 | 必须取得的证据 | 通过后可以说什么 |
|---|---|---|
| A：交叉编译 | 锁定源码/依赖/许可；ARMv7 API19、ARM64 API21 编译/链接记录；readelf 头、符号/API引用、DT_NEEDED、PIE、TEXTREL、对齐 | native 构建成立；还不能说可在应用里运行 |
| B：普通应用进程启动 | 自有最小 test APK、正常 app UID、正确 nativeLibraryDir；API19 ARM32、API21 ARM64、API29+ target35 执行和失败日志 | APK 部署/exec 路线成立；shell UID 下运行不能代替 |
| C：CON-06 生命周期 | 无设备也可测：5037 空闲、已占用、5047 终点、全占用、非端口错误、重复点击、取消、旧 PID 真退出、新就绪、无遗留进程/FD、未知占用服务不受影响 | 本机启动/完整重启/端口退避闭环 |
| D：CON-05 与 transport | 授权隔离 fixture 或测试端点出现到 server 表；track-devices-l、devices-l、transport_id；重启使旧会话失效，新 epoch 不复用旧选择 | server 列表与生命周期端到端成立 |
| E：真实 USB/TLS | 后续单独获批自有设备：USB授权/拒绝/拔插；fd传递/claim/短包；目标ADB确认；现代pair/TLS后同一daemon设备表可见 | 对列出的设备/系统覆盖通过；不能无限外推 |
| F：现代后台 | Android12+ 前后台切换、进程回收、通知停止、FGS启动限制；现代64位/16KB如在支持范围内 | 所声明的后台与现代系统矩阵覆盖成立 |

本轮 A–F 均未执行。现有 API19 x86 模拟器不证明 ARM32 native ABI；现有 ARM64 API25/27 或 ARM32 API24 镜像也不能替代最低 API21/19 的 ABI 运行证据。若没有对应最低 ABI 镜像/硬件，就诚实报告矩阵缺口，不补写“通过”。测试仅限获批 mock/隔离环境和今后授权的自有设备，不连接真实设备、不改系统安全，不重开受限第二轮审查，不复验屏控。

## 10. 精确下一步与授权需求

后续需要确认以下**有界构建验证**范围，不是批准完整 native 集成或真实设备访问：

1. 从 Android 官方旧版下载入口取得 **NDK r25c Linux / 25.2.9519653**，在独立工具链目录校验后解压/使用。官方链接入口见第2节；约 531MB 下载，解压空间另计。第一次下载不能用第三方镜像替代。
2. 从 `android.googlesource.com` / `boringssl.googlesource.com` 的官方仓库固定提交取得所选 **AOSP adb host + 已列出的最小依赖源码**，只构建隔离试验产物。先完成 source-lock/许可清单再运行源码构建脚本。候选起点为较小的官方 Android10 host 源码作生命周期编译探针，或直接锁定包含 TLS 的较新版本；二者都不能假称完整产品实现。若为减少反复换源而选较新版本，按第4节如实承担更多依赖。
3. 优先使用 NDK 自带 ndk-build 或已有构建工具、自写最小构建文件；本机目前未确认可用 CMake/Ninja。若依赖确需 CMake、Ninja、protoc、Go/Perl 等新工具，先给出**确切版本、官方来源、用途**再申请，不通过拉一个第三方“一键 adb-android build”脚本扩权。
4. 建议将首次源码移植设为**依赖下载就绪后的 90 分钟探索预算**，这是控制范围的建议，不是成功/工期保证；不进行无上限移植。遇到超出批准来源/工具、必须引入完整平台构建、或 API19 需要广泛兼容层时立即停下并给出证据。预算到达仍未得到可链接组件则报告已成功阶段、首批精确 blocker、候选补丁规模，交由用户决定下一轮。既有 0.1.2 全程冻结。
5. 第一个技术停止点：产出两种 ABI 的最小探针和可审查 ELF 信息；如无法链接，返回准确的缺失符号/内部API/依赖数量与补丁范围。第二个停止点：如已获模拟器验证和测试身份生成确认，使用独立 test APK 验证普通 app UID 下的 **ARM32/API19 server启动 → host:version/devices-l为空 → 受管停止并确认退出 → 再启动 → client epoch变化**，不连接真实设备。ARM64/API21 是独立覆盖项，不能由此推定通过。此时才决定是否投入 USB/TLS 集成。

本轮无需新增账号、付费服务、上传信息、授权真实 USB 设备或获取 root。完整 AOSP 平台构建和非官方端口均不是当前默认方案。**实际门槛是 native 交叉编译闭包、bionic/API19 兼容、APK 执行包装和 USB fd 后端适配；不是 ADB 协议本身禁止本机启动。**

## 11. 当前容量门槛

2026-10-12 12:46 UTC只读检查：共享32GB工作卷可用约2.4GB，既有Android SDK占约18GB，模拟器安装占约921MB。531MB NDK压缩包还需额外解压与编译空间，因此安装前必须先确认容量，不能仅因下载包小于剩余空间就承诺可装。/tmp为独立约4.9GB tmpfs，其容量占用内存，不应为安装而挤占后续VM内存。当前未删除任何冻结产物、工具链或测试证据；如需清理，只能先识别可再生自建中间物，保留交付与追溯证据。
