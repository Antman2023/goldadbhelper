# 第三方应用激活预设：独立验证记录

核验日期：2026-10-12。实现为 Java 8、无外部依赖的命令构建与结果解析；不运行命令、不安装应用、不下载脚本、不更改权限。核验的是公开官方说明，**不是实际设备兼容性测试**。

## 交付范围

- `core/src/main/java/io/github/devicebridge/core/ActivationPresets.java`
- `core/src/test/java/io/github/devicebridge/core/ActivationTests.java`
- 当前支持四项：Shizuku、冰箱普通 ADB、黑阈官方 bootstrap、小黑屋麦克斯韦妖
- 三项明确不可用：权限狗、安装狮设备所有者、Scene5
- 七项均保留可审查的名称、包名、条件、影响、来源、核验状态、历史差异；不可用项的 `command` 为空，`buildCommand()` 抛出异常，不会回退执行历史命令
- 固定路径仅适配主用户 0；工作资料、其他 Android 用户、无头系统用户设备需单独适配，不能根据另一个用户的安装结果执行这些路径

`OFFICIAL_DOCUMENTED` 只表示官方公开来源支持该命令结构；不能解释为“已在当前设备测试通过”。`UNSUPPORTED_NO_CURRENT_SOURCE` 表示权威证据不足，不能执行。

## 已支持预设

### Shizuku：`moe.shizuku.privileged.api`

设备端命令（ADB 通道内无需再加 `adb shell`）：

```sh
sh '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh'
```

[Shizuku 官方中文用户手册](https://shizuku.rikka.app/zh-hans/guide/setup/) 明确给出 v11.2.0+ 路径。先打开目标应用核对其启动引导，分别确认安装与脚本可读。官方无线调试流程适用于 Android 11+；本预设仅构建脚本启动命令，不实现配对或更改调试设置。

影响：启动可让被用户授权的客户端借用 shell 权限调用系统 API 的服务。重启后需重新启动，OEM 后台或调试限制可能使服务退出。程序不会自动更改安全设置、关闭权限监控或授权其他应用。

完成验证：在 Shizuku 中确认服务运行。仅退出码为 0 不足以确认服务已启动或持续可用。

### 冰箱普通 ADB：`com.catchingnow.icebox`

```sh
sh '/sdcard/Android/data/com.catchingnow.icebox/files/start.sh'
```

[官方英文普通 ADB 指南](https://iceboxdoc.catchingnow.com/Active%20with%20simple%20ADB%20mode.html) 发布该路径；[中文普通 ADB 指南](https://iceboxdoc.catchingnow.com/%E4%B8%80%E9%94%AE%E6%BF%80%E6%B4%BB%E5%99%A8%EF%BC%88%E6%99%AE%E9%80%9A%20ADB%20%E6%A8%A1%E5%BC%8F%EF%BC%89) 要求版本 >= 3.14.0。用户先在冰箱中选择普通 ADB 模式。官方建议保持 USB 调试、仅充电模式；MIUI 另有 USB 调试安全设置，由用户按自己系统的说明处理。

影响：启动应用冻结服务。重启或某些调试/USB 设置变化会使服务失效，冻结应用可能在重新激活前不可用。没有账户删除或设备所有者授权。

完成验证：用户在冰箱中确认普通 ADB 模式工作，不通过冻结关键系统应用“测试成功”。切换模式/移除应用前应先按冰箱说明处理已冻结应用。

### 黑阈：`me.piebridge.brevent`

[黑阈官方激活网站](https://brevent.sh/) 当前发布的流程是读取 APK 路径、设置 CLASSPATH、调用 `me.piebridge.brevent.server.BreventServer bootstrap`，再运行 `/data/local/tmp/brevent.sh`。该来源还由[黑阈自己的字符串资源](https://github.com/brevent/Brevent/blob/translation/brevent/src/main/res/values/strings.xml) 引用，网站页面署名 Jianyu Studio。

本实现独立添加输入与失败检查，不复制网页实现：

1. `pm path` 的失败必须立即停止
2. 输出必须为单个 `package:/...` 路径；CR/LF、多 APK/split 输出均拒绝
3. CLASSPATH 通过双引号变量传递，永不 eval
4. APK 必须可读；bootstrap 非零退出时不能继续执行旧的临时脚本
5. 仅在 bootstrap 成功后运行固定、单引号保护的 `/data/local/tmp/brevent.sh`

官方页面未标版本范围，因此仍要求用户核对已安装黑阈的启动引导。该流程会通过已安装 APK 生成本地启动脚本；此脚本**启动前不是必要条件**。`scriptProbeCommand()` 可检查生成文件是否可读，但不能据此证明服务健康。

影响：以 shell 权限运行黑阈后台管理服务。以黑阈自己的服务响应状态为最终验证，不根据日志里的某个“success”字串判定。

### 小黑屋麦克斯韦妖：`web1n.stopapp`

```sh
sh '/sdcard/Android/data/web1n.stopapp/files/demon.sh'
```

权威链：

- [小黑屋官方文档仓库](https://github.com/web1n/Stopapp-Docs) 指向开发者网站 https.gs
- [开发者网站](https://https.gs/) 公告其在线激活工具；[当前执行器](https://adb.http.gs/) 也直接署名并链接 web1n
- 从该执行器 HTML 公开引用的 [JavaScript 资源](https://adb.http.gs/assets/index-B4cs1yqk.js) **只读检查**到了命令目录中“小黑屋麦克斯韦妖模式”、包名和上述 demon.sh 路径
- 该资源原始 bytes SHA-256：`ef54ce14808fdb864814ce91e51e642574066209be00229bac49b2d5a40ef994`

脚本资源未运行、未加入项目。实现只独立使用公开接口常量。官网没有给版本范围，必须与当前已安装应用的模式/路径匹配；缺少脚本时不自动切换到旧 `starter.sh`。

影响：以 shell 权限启动所选冻结服务，不设置设备所有者。按[官方 FAQ](https://github.com/web1n/Stopapp-Docs/blob/master/helparticle-online)，切换模式前应先解冻全部应用，卸载应使用小黑屋设置内入口并先解冻。完成状态仍需在小黑屋中确认。

## 明确不可用的预设

### 权限狗：`com.web1n.permissiondog`

未在开发者公开站点或其可访问官方文档中找到可核实的当前精确启动指令、版本条件与撤销说明。第三方下载站虽有 `starter.sh` 字串，也常存在缺空格等错误；不把它们或同名 PermissionDog 开源库当作该应用的官方证据。`sourceUrl` 仅为开发者网站参考，不声称该页发布了激活命令。

历史命令可能让应用改变 AppOps 等系统权限行为，具有实质影响，不能因为设备上存在同名文件就自动启用。

### 安装狮：`com.modosa.apkinstaller`，设备所有者

未取得安装狮开发者发布、可核实的当前组件名、版本要求及移除步骤。静态历史字符串不是足够的当前官方证据。`sourceUrl` 指向 Android 的 DPM 定义，**只证明平台语义，不证明安装狮组件**。

高影响警告：设备所有者是持久且广泛的整机管理能力，可能涉及应用安装/卸载及设备策略，并非普通脚本服务；重启不会自动撤销。

启用前必须解决的条件：

1. 确认用户有权对该设备进行完整管理，没有现有组织管理或其他设备所有者
2. 确认正确安装包、签名/版本和设备管理员组件
3. [Android 官方开发配置](https://developer.android.com/work/dpc/dedicated-devices/cookbook#development-setup) 要求安装正确 DPC，且设备没有账户；用户/配置文件/OEM 约束还需单独核对
4. 不自动删除账户、删除工作资料、解除现有管理或恢复出厂设置来“修复”失败
5. 先取得实际可用的官方解除管理与卸载方式，备份并评估数据风险，然后对具体设备、应用、管理权限逐次独立确认

移除限制：不能假定普通卸载或关闭设置开关就能撤销设备所有者。[Android 官方 DPM 文档](https://developer.android.com/tools/adb#dpm) 规定 `remove-active-admin` 只用于 manifest 声明 `android:testOnly` 的管理员；因此本实现不提供一个未经证明的“撤销”按钮。若需恢复出厂，必须先由用户备份并自行作出明确决定，绝不能作为自动重试。安装狮自身的可靠移除流程尚未核实，故命令不可用。

### Scene5：`com.omarea.vtools`

[当前官网](https://omarea.com/) 同时列出多个 Scene 大版本；[官方开源仓库](https://github.com/helloklf/vtools) 的主页说明是 root 工具，没有足够证据证明历史 Scene5 的 `up.sh` 对当前应用仍有效。官网的公开版本导航链接新版文档，但未能据此验证指定 Scene5 路径和适用范围。

ADB 模式启动与专业版购买/授权是不同概念；本实现不会接触购买、账户或授权接口，也不自动下载网上的 `scene_adb_init.sh`。

## 原始 1.3.1 静态兼容性记录

依据提供的静态接口清单 TOOLS-04A 至 TOOLS-04G 以及 `dex_strings.json`。分析对象：甲壳虫ADB助手 1.3.1、versionCode 17；目标第三方应用版本均未知。仅保留接口命令事实，不复用原应用专有实现、资源、脚本或二进制，不访问其后端。

| 应用 | 原版本记录 | 当前处理 |
|---|---|---|
| Shizuku | `sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh` | 官方指南使用 `/sdcard/...`，独立验证 |
| 冰箱 | `sh /sdcard/Android/data/com.catchingnow.icebox/files/start.sh` | 当前官方普通 ADB 文档相同路径 |
| 黑阈 | `sh /data/data/me.piebridge.brevent/brevent.sh` | 不默认使用旧路径，改为其当前官网 bootstrap |
| 小黑屋 | `sh /storage/emulated/0/Android/data/web1n.stopapp/files/starter.sh` | 当前作者执行器发布麦克斯韦妖 `demon.sh`，不同模式明确标注 |
| 权限狗 | `sh /storage/emulated/0/Android/data/com.web1n.permissiondog/files/starter.sh` | 历史展示；不能执行 |
| 安装狮 | `dpm set-device-owner com.modosa.apkinstaller/.receiver.AdminReceiver` | 高影响且缺官方证据；不能执行 |
| Scene5 | `sh /data/user/0/com.omarea.vtools/files/up.sh` | 历史展示；不能执行 |

`historicalScope` 明确注明原助手版本，而不是捏造第三方目标版本。没有启用“历史兼容执行”开关，避免未知版本被误当成受支持版本。

## 集成契约与安全边界

1. 从 `ActivationPresets.all()` 展示七项；保留不可用项及原因，不伪装成已实现
2. 当前目标设备连接稳定后，先单独执行 `packageProbeCommand()`，严格解析完整包名；不能子串匹配或仅根据退出码认定已安装
3. 对预存在脚本的三项执行 `scriptProbeCommand()`；不存在与不可访问统一提示 `MISSING_OR_INACCESSIBLE`，不能谎称一定未安装
4. 黑阈的脚本由 bootstrap 生成，不把“启动前脚本缺失”当作失败；安装包仍须确认
5. 界面显示版本/模式范围、全部 requiredConditions、完整 command、consequence 与目标设备；用户核对条件后使用 guarded `buildCommand(...)`
6. 真正发送命令必须走应用已有的单次明确确认，并绑定同一设备会话与导航代次；构建命令或通过探测不等于授予执行权限
7. 连接切换、断线、取消、旧回调或用户返回后，旧确认与预检不能重用；不得自动启动、后台重试或下载替代脚本
8. 展示 shell-v2 退出码、stdout/stderr 和目标应用自己的核验说明；退出码 0 只映射 `COMPLETED_SERVICE_UNVERIFIED`，非零为 `FAILED`，未知/legacy 退出码为 `UNKNOWN`
9. 安装探测、脚本可读、命令完成、应用服务实际运行是四个不同事实
10. 未找到权威、可靠的应用特定只读健康检查命令，故 `serviceVerificationCommands` 均为空；不要猜测 `pidof`/Binder 名称或把进程存在当作应用可用

`buildCommand()` 是只做静态验证的预览接口；可执行界面应使用带检测结果和条件确认的重载。所有元数据不可变，调用方无法构造自定义预设。read-only probe 只是本地构建的命令，不在此类执行。

## 独立测试

```sh
mkdir -p build/activation-classes
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -encoding UTF-8 -Xlint:all \
  -d build/activation-classes \
  core/src/main/java/io/github/devicebridge/core/DeviceCommands.java \
  core/src/main/java/io/github/devicebridge/core/ActivationPresets.java \
  core/src/test/java/io/github/devicebridge/core/ActivationTests.java
java -ea -cp build/activation-classes io.github.devicebridge.core.ActivationTests
```

覆盖：七项包名/来源/版本与历史标签、不可变元数据、四项命令、bootstrap 失败保护与路径引用、无下载/自动启动/所有者命令、精确安装检测、缺失/未知/受限脚本检测、工作资料拒绝、条件确认门控、不可用项禁止构建，以及所有退出码均不能声明服务已激活。测试仅使用纯 Java 字符串/fixture，不连接设备。
