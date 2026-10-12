# 三项未支持激活预设：有限官方资料复核

核验日期：2026-10-12（UTC）。本轮只读公开官网、开发者公开仓库与 Android 官方平台文档，并对照已有历史接口记录；不连接设备或账号，不安装应用，不下载或运行激活脚本，不设置设备所有者。唯一新增交付是本文件；生产代码、既有四项预设和冻结的 v9 交付不变。

## 结论先行

**本轮没有取得足以启用任一剩余预设的完整官方证据。三项继续不可执行。** 这不是断言应用不支持 ADB，而是本轮无法确认目标版本、精确入口及适用/恢复条件。

| 目标 | 新增核验结果 | 可批准的目标应用命令 | 结论信心 |
|---|---|---|---|
| 权限狗 `com.web1n.permissiondog` | 复核了 web1n 官网、官方小黑屋文档和执行器入口；仍缺权限狗自身的命令、版本及恢复说明 | 无；保持空值 | 高：证据不足，不能启用。对应用实际当前兼容性没有结论 |
| 安装狮 `com.modosa.apkinstaller` 设备所有者模式 | Android 平台语义可确认；没有建立应用开发者发布资料与具体组件/版本/解除管理流程的证据链 | 无；平台通用结构不等于安装狮适配 | 高：持久整机管理不能凭历史字符串启用。应用特定兼容性未知 |
| Scene5 `com.omarea.vtools` | 找到官方 `scene5` 分支，但固定提交自报 **5.0.0 Alpha / 202111261**，启动入口检查 ROOT；不是可确认的 Scene5 ADB 发布版依据 | 无；不能提升原助手的 `up.sh` 历史路径为当前支持 | 高：新增分支证据的版本身份；中：本轮有限检查仍未找到 ADB 入口，不能据此断言整个历史仓库从未有过该功能 |

官方资料发布了某个命令、目标包已安装、脚本可读、命令退出成功、目标服务确实可用，是五个不同事实。本轮只核验资料，没有进行设备兼容性测试。

## 1. 权限狗

### 官方来源与实际支持范围

- [web1n 官网](https://https.gs/)：当前可读内容包含在线执行器更新公告，未提供本目标包的精确激活指南。
- [web1n 官方小黑屋文档 README](https://github.com/web1n/Stopapp-Docs/blob/master/README.md)：其下载入口指向 `https.gs`，用于确认该站与开发者仓库的关系；该文档对象是小黑屋，不能借此认证权限狗命令。此次读取的 README blob 为 `b0d5f9173590b679c38f64d41560e4e825bb99f3`。
- [同仓库 FAQ](https://github.com/web1n/Stopapp-Docs/blob/master/FAQ%20%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98.md) 与 [在线帮助](https://github.com/web1n/Stopapp-Docs/blob/master/helparticle-online)：此次可读正文未出现权限狗或 `permissiondog`，不能把小黑屋的模式切换、解冻/卸载步骤移植给权限狗。
- [官方署名在线执行器入口](https://adb.http.gs/)：只读 HTML 再次确认署名 web1n 与官网链接。入口的动态命令目录不能仅由 HTML 的应用宣传文字推导；本轮未下载或运行其 JavaScript。既有小黑屋 `demon.sh` 证据仍仅支持原来的小黑屋预设。

**确切可支持版本/模式：未找到。** 本轮未确认 `starter.sh` 对哪个权限狗版本、哪个 Android 版本/用户/模式有效。

### 命令、前置条件及恢复

- 可用命令：没有已核验的目标应用命令，保持 `command = ""`。
- 不把原助手静态字符串或第三方教程补写为“官方命令”；同名 `PermissionDog` 权限申请库也不是这个应用。
- 待补前置条件：开发者对该包的版本与签名来源说明、ADB 模式入口、由应用生成且与该版本匹配的路径、权限影响说明。
- 待补恢复依据：如何停止其 shell 服务，如何恢复由其改变的 AppOps/权限状态，以及哪些变更不会随进程退出或重启复原。**不能承诺重启或卸载会还原 AppOps。**
- 未找到官方、应用特定的只读服务健康检查或可靠成功标记，不猜测进程名、Binder 名、`pidof` 或日志关键词。

### 检索边界

已针对官网、已知官方文档仓库及精确包名做有限复核。GitHub 的 `user:web1n` 仓库搜索返回 422，错误同时涵盖资源不存在或不可访问，不能解释为“开发者没有仓库”。目标 Google Play 页面本轮工具无法读取，不能解释为已下架或当前不再支持。没有为这些不确定性转向第三方 APK 下载站取代官方证据。

## 2. 安装狮：设备所有者模式

### 可以确认的平台事实

- [Android 官方 ADB / DPM 文档](https://developer.android.com/tools/adb#dpm) 区分 `set-active-admin`、`set-profile-owner` 和 `set-device-owner`；最后一项会把管理员组件所在包设为设备所有者。普通“设备管理员”开关不等同于这个权限级别。
- [Android 官方专用设备开发配置](https://developer.android.com/work/dpc/dedicated-devices/cookbook#development-setup) 要求先安装正确的 DPC，并确认设备没有账户；例子中的管理员组件必须替换成应用真实组件。这是开发流程说明，不保证所有已投入使用设备、OEM、用户/资料配置均可采用。
- 同一官方 DPM 文档说明 `remove-active-admin` 要求应用 manifest 声明 `android:testOnly`，因此不能把它当作所有发布版 DPC 都能使用的撤销命令。

平台通用命令结构，仅作概念说明，**不是可运行的安装狮预设**：

```text
dpm set-device-owner [平台支持的选项] <已由该应用官方确认的管理员组件>
```

该结构本身不能证明 `com.modosa.apkinstaller/.receiver.AdminReceiver` 是某个可信发布版本的当前组件，也不能补足移除管理的依据。

### 应用特定证据缺口

- **确切可支持版本/模式：未找到。** 无法通过本轮官方资料核实安装狮发布版本、DPM 组件、是否设置 `testOnly` 及官方解除管理入口。
- 精确包名与开发者名的有限检索没有建立可复核的开发者发布页。Google Play 的目标页面本轮无法读取，故不推断其状态。
- 特别排除同名身份误判：[GitHub `modosa/ModosHouse`](https://github.com/modosa/ModosHouse) 的仓库元数据描述为 `Room reservation`，目录只有 `modoshouse_190210_0955_last.zip`。没有证据表明这个账号就是安装狮作者；仓库及 ZIP **不是安装狮官方证据**，未下载该 ZIP。
- 第三方网站广泛重复的命令、版本号与“安装狮-DPM”文字，不满足这次官方核验标准。

### 任何未来实现的必要门槛

1. 确认用户有权对指定设备完整管理，且没有组织管理/现有设备所有者；不能帮助替换未经授权的组织管理。
2. 确认可信安装包的真实版本、签名来源和管理员组件；不能仅凭包名或历史路径推定真实性。
3. 针对 Android 版本、目标用户、工作资料、账户和 OEM 条件做明确核验；只读诊断结果不能授权账户删除、资料删除或恢复出厂。
4. **先**取得应用官方、针对发布版可用的解除管理/卸载步骤及数据风险说明，再讨论启用；没有可靠恢复路径时不能提供自动撤销按钮。
5. 实际启用属于持久、广泛的设备管理能力，必须另行取得对具体设备、应用和后果的明确授权；本次研究不构成执行授权。

恢复状态：应用特定撤销仍未知；不提供未经证明的 `remove-active-admin`、普通卸载、关闭调试或重启“回退”。恢复出厂涉及删除设备数据，绝不能作为自动修复或自动重试。

## 3. Scene5

### 新增的固定版本官方证据

此前只看默认仓库说明不足以概括所有分支。本轮从[官方仓库分支列表](https://api.github.com/repos/helloklf/vtools/branches?per_page=100) 找到了 `scene5`，并固定到提交：

`cfd05478b322ba91c315555b56fea95865efec4d`

[提交元数据](https://github.com/helloklf/vtools/commit/cfd05478b322ba91c315555b56fea95865efec4d) 的提交时间为 2023-12-25；**这不是应用发布日期，也不能把仓库更新时间等同于应用版本。**

| 官方固定来源 | 可确认事实 | 不能据此推出 |
|---|---|---|
| [app/build.gradle](https://github.com/helloklf/vtools/blob/cfd05478b322ba91c315555b56fea95865efec4d/app/build.gradle) | `applicationId=com.omarea.vtools`，`versionName=5.0.0 Alpha`，`versionCode=202111261`，`minSdkVersion=23`，`targetSdkVersion=29`，ABI 配置 `arm64-v8a` | 所有 Scene5 正式版具有相同启动接口；Android 6+ 均支持其 ADB 模式 |
| [README](https://github.com/helloklf/vtools/blob/cfd05478b322ba91c315555b56fea95865efec4d/README.md) | 该快照说明 ROOT 与 Busybox 要求，并警示高级功能影响系统启动 | ADB 模式指南；当前版兼容性保证 |
| [ActivityStartSplash.kt](https://github.com/helloklf/vtools/blob/cfd05478b322ba91c315555b56fea95865efec4d/app/src/main/java/com/omarea/vtools/activities/ActivityStartSplash.kt) | 启动入口中调用 `checkRoot()`，并使用 `CheckRootStatus(...).forceGetRoot()`，另有 Busybox 安装分支 | 供外部 ADB 调用的 `up.sh` 创建/启动契约；不能执行这些内部流程作验证 |
| [完整树元数据](https://api.github.com/repos/helloklf/vtools/git/trees/cfd05478b322ba91c315555b56fea95865efec4d?recursive=1) | 返回 1881 个节点，`truncated=false`，该快照没有名为 `up.sh` 的路径 | 应用运行时不可能生成该文件；所有历史提交均不存在 ADB 支持 |
| [官方 4.7.3 发布记录](https://github.com/helloklf/vtools/releases/tag/4.7.3) | 明确属于 Scene4 的最后维护更新，后续转 Scene5 | 使用 Scene4 的源码/发布记录证明 Scene5 ADB 启动路径 |

这些事实强化了“必须绑定精确版本”的要求，不能将含 `scene5` 名字的分支自动认证为原助手对应的 Scene5 ADB 版本。

### 当前官网与边界

[当前 Scene 官网](https://omarea.com/index.html) 同时使用多个大版本名称，公开搜索可见的主页描述又区分免费 ROOT 版本与新版 ADB/基础模式。本轮只读入口 HTML；其主要正文依赖 JavaScript，未下载或运行网站脚本，也没有据其导航猜测 Scene5 文档地址。未把当前新版的免费 ADB 描述反推到所有历史 Scene5。

开发者名下公开仓库也做了有限目录检查：`helloklf/helloklf.github.io` 可见资料多为旧网站与通用技术文档，没有取得与 Scene5 发布版本匹配的官方 ADB 指令。未继续扩大到无关仓库、第三方网盘或无结果全网搜索。

### 命令、前置条件及恢复

- **确切可支持的 ADB 版本/模式：未找到。** 上述 `5.0.0 Alpha` 是核实到的源码版本身份，不是新增的 ADB 支持范围。
- 可用命令：无；原助手的 `/data/user/0/com.omarea.vtools/files/up.sh` 仍仅是历史路径，不能用于当前或其他大版本。
- 不猜测替代 `/sdcard/.../up.sh` 路径，不从第三方下载 `scene_adb_init.sh`，不自动采用私有数据目录访问绕行。
- 前置条件待补：目标应用精确版本、官方来源、该版本 ADB 模式操作指引、脚本产生时机/路径/用户范围、Android/OEM 可访问性及服务状态确认方式。
- 恢复说明待补：该版本服务停止/退出 ADB 模式的官方流程，以及应用已执行的性能、权限或应用管理改动如何恢复。停止服务不能概括为撤销所有系统改动。
- ADB 服务启动与购买/账号授权是不同事项；本轮没有接触付费授权、账户或后端。

## 4. 与原始助手证据的关系

已有 `ACTIVATION_PRESETS.md` 和 `ActivationPresets.java` 记录的是甲壳虫 ADB 助手 1.3.1（versionCode 17）的静态接口；三个目标应用当时的版本均未知。`../app_replication_analysis/analysis/interface_inventory.md` 的 TOOLS-04E/F/G、`dex_strings.json` 与相关静态材料只用于定位待核验功能，没有被提升为目标应用官方来源。

此次不重复复制危险的历史组件命令，不增加“历史兼容执行”开关，不更改任何 `UNSUPPORTED_NO_CURRENT_SOURCE` 状态，也不扩大既有四项可执行预设的授权范围。

## 5. 已达停止条件与重新开启条件

本轮已检查已知官方站点/仓库、Scene 历史分支及 Android 平台约束，没有建立三个应用所需的完整版本化契约，有限追加研究到此结束。

仅在获得以下新增证据后，才值得重新核验：

- 由开发者官网/已验证仓库链接的对应应用版本指南，含完整入口与适用条件；或可认证的目标版本应用内官方引导截图/文本与版本信息。
- 安装狮另需发布版管理员组件和可靠解除管理说明。只有平台 DPM 文档或同一句历史命令不够。
- Scene5 另需确认这是目标 Scene5 的 ADB 模式，而非同包新版、5.0.0 Alpha ROOT 快照或 Scene4。
- 完成上述证据核验后，仍需独立的实现、测试、具体设备前置条件检查及执行授权；本文件不是实机成功证明。

验证范围：仅新增文档并检查文本与来源；未重跑 Android 构建，未改生产代码，未执行命令、激活或设备所有者操作。
