# 金甲虫 ADB 助手 · GoldenBeetle

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Android: API 19+](https://img.shields.io/badge/Android-API%2019%2B-green.svg)](app/build.gradle)
[![Version: 0.1.3-dev](https://img.shields.io/badge/version-0.1.3--dev-orange.svg)](app/build.gradle)

在 Android 手机上管理另一台 Android 设备：连接 ADB、浏览和传输文件、管理应用与进程、运行终端命令，以及投屏和 Fastboot 操作。

项目使用独立的 Java ADB 协议实现，提供 TCP、USB 和 Android 11+ 无线调试配对入口。应用名称、图标、包名和开发签名均为独立身份。

**当前发布的是 `0.1.3-dev` 开发候选源码。** 功能代码接入不代表已通过所有设备验收；不同 Android 版本、品牌、USB/Fastboot 实机和正常 H.264 投屏仍有待验证项。本候选的模块导航调整尚未完成 Android 运行验收，历史版本的验证记录不能直接证明新候选。完整状态见 [功能矩阵](docs/feature-status.json) 和 [待验清单](docs/REMAINING_VALIDATION.md)。

## 功能概览

| 模块 | 已接入的能力 |
| --- | --- |
| 设备连接 | TCP/USB ADB、独立 RSA 身份、连接历史、无线调试配对；外部 ADB server 设备列表与会话选择 |
| 设备信息 | 系统、设备、存储与运行状态读取 |
| 实用工具 | DPI、分辨率、动画、应用冻结和重启等操作 |
| 远程控制 | 基于 scrcpy 2.4 的 H.264/MediaCodec 投屏与多点触控；独立截图兼容模式 |
| 文件管理 | 目录导航、多选、上传下载、SHA-256 校验、复制/移动、取消与部分结果报告 |
| 应用管理 | 名称和图标读取、筛选搜索、启动、启停、卸载、清除数据、APK/分包安装及导出 |
| 进程管理 | 读取设备进程并按应用分组 |
| 命令终端 | 持久 PTY、命令历史、快捷命令、Ctrl-C、退出和重新启动 |
| Fastboot | USB 协议、getvar、镜像传输、刷写、启动和重启 |

设备页包含七个模块。`0.1.3-dev` 会预创建相邻模块的真实控件，首次设备读取在提交选中该模块后开始。终端离开页面时关闭 PTY，返回后重新连接并保留显示与未提交输入；详见 [邻页预创建记录](tests/navigation/neighbor/RESULT.md)。

## 环境与兼容性

- **运行应用的手机：** Android 4.4 / API 19 起，`targetSdk` 为 35；包名为 `io.github.devicebridge.goldenbeetle`。
- **无线调试配对的目标：** Android 11 或更高版本，开启开发者选项与无线调试。
- **USB 连接：** 应用所在手机需要 USB Host/OTG 支持，目标设备需要开启 USB 调试并确认授权。
- **设备能力：** 命令、权限、编码器和文件操作的可用性取决于目标 Android 版本及系统实现。
- **本地访问：** 文件功能需要相应存储权限；应用列表受到系统权限与包可见性规则影响。

API 19 兼容层使用 D8/L8 库回移植和 AndroidX MultiDex。历史安装与运行结果按其 APK 哈希记录，详见 [旧版 Android 兼容性](docs/API19_COMPATIBILITY.md)。

## 开始使用

当前仓库提供源码和构建输入，尚未发布经过完整验收的正式安装包。可按下文构建开发 APK。

1. 在你拥有或获准管理的目标设备上开启开发者选项和相应调试方式。
2. 安装本应用，打开连接页，选择一种连接方式：
   - **TCP ADB：** 输入已启用 ADB 的目标地址和端口。
   - **USB ADB：** 通过 OTG 连接目标设备，批准本机 USB 权限及目标设备的调试授权。
   - **Android 11+ 无线调试：** 在目标设备打开“使用配对码配对设备”，输入配对地址、配对端口和六位码。成功后回到无线调试主页面，使用那里显示的**连接端口**手动连接。
   - **外部 ADB server：** 通过“刷新设备列表”的入口配置已运行的 server，再选择在线设备。详见 [外部服务说明](docs/EXTERNAL_ADB_SERVER.md)。
3. 核对当前目标，进入设备信息、文件、应用或运行命令等模块。

无线配对端口与连接端口通常不同。外部 ADB server 客户端可以断开自身连接或请求停止共享服务，无法远程启动已经停止的 daemon；停止服务会影响其所有客户端。

删除文件、清除应用数据、更改系统设置、重启和 Fastboot 刷写前，请核对目标与影响。取消不能撤销已经完成的设备修改；上传中断可能留下部分文件，结果为 `UNKNOWN` 时应先检查设备状态再重试。

## 从源码构建

### 脚本构建

现有脚本面向 Linux Bash 环境；Windows 可在 WSL 中准备独立的 Linux JDK 和 Android SDK。WSL 内的脚本不能直接使用 Windows 版 SDK 工具。

需要带 `jdk.compiler` 模块的 JDK、Android SDK Platform 35、Build Tools 35.0.0、Python 3，以及 Bash、`find`、`sort`、`tee`、`sha256sum` 等工具。将 `java` 和 `keytool` 加入 `PATH`。固定版本第三方 JAR/AAR、JNI 库、源码和许可证已随仓库保留。

```bash
git clone https://github.com/Antman2023/goldadbhelper.git
cd goldadbhelper

# 改成当前构建环境中实际的 SDK 路径。
export ANDROID_HOME=/path/to/android-sdk

# 重建元数据 helper 和 scrcpy 服务端。
bash tools/build-servers.sh

# 构建、开发签名并校验 APK。
bash tools/build-apk.sh
```

| 主要输出 | 内容 |
| --- | --- |
| `build/outputs/金甲虫ADB助手-0.1.3-dev.apk` | 开发签名 APK |
| `build/outputs/构建来源.json` | APK、源码归档和服务端资源的哈希记录 |
| `build/金甲虫ADB助手-对应源码及许可.zip` | 此次构建的对应源码与许可 |
| `build/source-inputs.json` | 构建输入文件及其 SHA-256 |

脚本通过 `tools/snapshot-build.py` 固定构建输入，并在编译后检查输入是否变化。可通过 `BUILD_DIR` 指定隔离输出目录；开发签名仍使用本地 `build/debug.keystore`，用于保持同一工作区内的升级签名一致。

**开发密钥仅用于测试，不作为正式发布密钥，且不提交到仓库。** 使用其他签名覆盖安装可能需要先卸载旧版本，卸载会丢失应用私有数据和 ADB 身份。

### Gradle 工程

仓库同时提供 `app` 和 `core` 的 Gradle 工程，Android Gradle Plugin 版本为 `8.7.3`。可用 Android Studio 打开项目，或在已配置兼容 Gradle/JDK 的环境中执行：

```bash
gradle :app:assembleDebug
```

当前未提供 Gradle Wrapper，Gradle 构建路径尚需单独验证。Gradle 不会自动重建 `app/src/main/assets/` 中的服务端 JAR；修改服务端源码后应先运行 `tools/build-servers.sh`。

## 测试与验证

在上述 Bash/JDK/SDK 环境中运行：

```bash
# 核心协议、文件传输、命令、配对及本地模型等测试。
bash tools/test-all.sh

# 外部 ADB server、模块状态、导航和宿主 UI 合成回归。
bash tools/test-followup-navigation-server.sh
```

也可以单独运行：

```bash
bash tools/test-core.sh
bash tools/test-host-server.sh

# 带真实 TLS/exporter 的本机合成配对测试。
CONSCRYPT_TEST_JAR="$PWD/vendor/conscrypt-openjdk-uber-2.5.2.jar" \
  bash tools/test-pairing.sh
```

测试输出主要位于 `build/test-results/` 和各回归脚本的 `build/` 子目录；宿主 UI 脚本会更新 `tests/hostui/` 中的结果与源码哈希记录。

JVM 协议测试使用合成数据或本机 loopback 端点，导航测试使用 Android 控件替身或抽取的生产代码片段。这些结果用于验证相应逻辑，不能替代真实 Android 输入、渲染、生命周期、USB/Fastboot 和 OEM 兼容性验收。设备测试脚本另行保留在 `tools/` 中，运行前需要配置其设备参数。

历史运行记录见 [runtime-evidence.json](docs/runtime-evidence.json)。解读记录时应检查版本、APK 哈希和实际测试范围，不将旧包记录转记为当前候选已通过。

## 项目结构

```text
app/          Android 界面、设备交互、资源和运行时依赖
core/         ADB、配对、SYNC、shell、Fastboot 和状态模型
server/       远端元数据、文件提交、复制和移动 helper
server-power/ scrcpy 显示电源控制补充代码
tests/        本地模型、模块导航和宿主 UI 回归
tools/        构建、测试、兼容性检查与源码快照脚本
docs/         功能状态、实现说明与历史验证记录
third_party/  第三方来源、修改说明、许可与回移植输入
vendor/       固定版本上游源码及依赖归档
licenses/     第三方许可证全文
```

## 更多文档

- [功能范围与状态](docs/feature-status.json) / [状态统计](docs/feature-summary.json)
- [尚未完成与待验边界](docs/REMAINING_VALIDATION.md)
- [Android 11+ 无线配对](docs/WIRELESS_PAIRING.md)
- [文件传输与提交验证](docs/VERIFIED_FILE_TRANSFERS.md) / [复制与移动兼容性](docs/FILE_COPY_COMPATIBILITY.md)
- [终端行为](docs/TERMINAL_BEHAVIOR.md)
- [外部 ADB server](docs/EXTERNAL_ADB_SERVER.md)
- [API 19 兼容性](docs/API19_COMPATIBILITY.md) / [包管理命令兼容性](docs/PACKAGE_COMMAND_COMPATIBILITY.md)
- [模块导航实现记录](docs/NAV02_RETAINED_PAGER_DRAFT.md) / [首次邻页预创建](tests/navigation/neighbor/RESULT.md)

部分文档包含历史工作区路径、冻结候选名与外部参考材料索引，用于记录当时的验证上下文；这些引用不表示相关产物或参考材料已包含在本仓库。

## 参与开发

欢迎通过 [Issues](https://github.com/Antman2023/goldadbhelper/issues) 反馈问题或提交 Pull Request。报告兼容性问题时，请提供应用版本或提交号、宿主与目标 Android 版本、连接方式、复现步骤和脱敏日志；请勿上传配对码、私钥或私人设备数据。

修改协议或状态逻辑时运行对应回归。报告 Android 运行结果时注明被测 APK 的 SHA-256，说明实际覆盖范围。新增或修改第三方组件时保留来源、版权、许可与修改记录。

## 许可证与第三方组件

项目独立代码采用 [Apache-2.0](LICENSE)。第三方组件按各自许可提供，不能将整个仓库视为仅有一种许可证。

| 组件 | 固定版本 / 来源 | 许可与记录 |
| --- | --- | --- |
| scrcpy 服务端 | Genymobile scrcpy 2.4，上游源码自编 | Apache-2.0；[源码与许可](vendor/scrcpy-2.4/) |
| Conscrypt | Android 2.5.3；JVM 测试使用 OpenJDK 2.5.2 | Apache-2.0 及其所含第三方许可；[来源与许可](third_party/conscrypt/) |
| 纯 Java SPAKE2 | 固定上游提交及已记录修改 | LGPL-3.0 和 CC0 组成；[来源、修改及哈希](third_party/spake2-java/) |
| Java 库回移植 | desugar_jdk_libs_nio 2.1.5 | GPL-2.0 with Classpath Exception；配置采用 Apache-2.0；[来源说明](third_party/desugar/README.md) |
| AndroidX MultiDex | 2.0.1 | Apache-2.0；[来源说明](third_party/desugar/README.md) |

应用关于页包含可离线读取的第三方许可。分发 APK 时应同时提供与其对应的完整源码、构建脚本和许可证，保留 LGPL 组件的替换与重新链接能力。开发者可替换兼容源码、重建并用自己的密钥签名安装。

本项目不使用参考应用的专有 helper、商业 native 库、账号、后台或签名密钥，不代表其原作者。协议和第三方实现来源详见仓库内对应记录。
