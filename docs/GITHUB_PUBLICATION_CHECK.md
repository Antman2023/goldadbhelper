# GitHub 首次公开发布检查

检查日期：2026-10-13。候选源码版本：`0.1.3-dev`。

本次发布整理了 README、构建输出版本、文档索引、第三方许可说明和 Git 忽略规则；没有修改应用或协议实现，也没有生成或发布新的 APK。

## 本次实际运行

环境为 Windows PowerShell、OpenJDK 27。使用 Java 8 source/target 编译 `core/src/main/java` 与 `core/src/test/java`，编译通过；随后在 Windows JVM 上直接运行既有测试类，而非声称 Linux Bash 聚合脚本已通过。

以下 20 组测试通过：

- CoreTests、DomainTests、ProtocolTests、ScreenProtocolTests、UiSafetyTests。
- FileTransferTests、ActivationTests、InteractiveShellTests、LegacyCloseTests、ScreenshotCaptureTests。
- TerminalTranscriptTests、PackageCommandStrategyTests、ApkManifestInspectorTests、ModuleNavigationTests。
- RetainedModuleStateTests、RetainedPagerGestureTests、AdbServerEndpointTests、HostServerTests。
- FieldArithmeticTests：21,057 项独立检查。
- PairingTests：14 项测试，包括本机 loopback TLS 1.3/exporter、错误配对码、超时、取消和 ADB STLS 升级；使用仓库固定的 Conscrypt OpenJDK 2.5.2 JAR。

`LegacyShellTests` 在原始 PATH 中找不到 `sh`；加入 Git for Windows 的 `sh` 后，子进程仍未通过 framing shell 完成断言。这组测试未在其预期 Linux shell 环境中完成，不能记为通过。本次未修改测试或生产逻辑来绕过该结果。

README 的 31 个链接目标已检查，其中本地链接均存在；功能统计的各状态合计为 150；源码范围没有超过 50 MiB 的单文件。忽略规则排除了构建产物、开发签名密钥、本地配置和凭据，同时保留必要第三方 JAR/AAR、JNI、源码与许可证。

## 验证范围

本次没有执行 `tools/test-all.sh` 或 `tools/test-followup-navigation-server.sh` 的完整 Bash 流程，没有重新构建 Android APK，没有执行 Android UI、模拟器、真实设备、USB 或 Fastboot 测试。以上 JVM 结果只支持相应合成逻辑与本机协议回归，不改变既有设备验收状态。

历史源码、原始测试记录和许可证中的格式保持原样；新编写的发布文件单独执行 Git 空白检查。APK 发布、Gradle 构建和当前候选 Android 运行验收仍需按对应文档继续验证。
