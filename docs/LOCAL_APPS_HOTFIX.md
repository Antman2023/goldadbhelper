# 本机应用选择热修说明

## 已确认的问题与尚未确认的原因

- 冻结 v9 已声明 `android.permission.QUERY_ALL_PACKAGES`。Android 官方将其标为 `normal`，不能通过常规 `requestPermissions` 弹窗申请。因此，没有伪造运行时授权弹窗，也没有要求无关的相册或所有文件访问权限。
- 原 `localAppPicker()` 在主线程读取整个应用列表并重复读取标签。任意查询/标签异常都缺少面向用户的恢复路径，空列表也没有明确提示。
- 原本机应用选择传入空 token map，但原 `Compatibility.sourceStat` 仅在 token 非 null 时进行预期值比较。空 token **不是已证实的读取拒绝根因**。
- 尚未获得用户手机品牌、系统策略和现场错误，不能宣称已定位或修复所有厂商的额外权限限制。设置引导只使用官方 `ACTION_APPLICATION_DETAILS_SETTINGS`，不使用猜测的厂商组件。

## 改动

1. `LocalApps` 在后台读取 PackageManager。单项标签失败回退包名，读取拒绝、服务错误、空列表分别提示，取消不再继续展示结果。应用列表只在内存与本机界面使用，不上传、不持久化。
2. `LocalAppPickerDialog` 使用全屏列表。依据原应用本机 `AppPickerActivity$a` 和 `decoded/res/layout/bj.xml` 的静态证据保留 42dp 图标、16dp 边距、18sp 名称、12sp 包名及分隔线。名称最多两行且行高自适应；点击选择后留在页面显示选中状态，通过固定底部“准备安装”继续。支持名称/包名搜索、长按查看完整名称包名、明确加载/失败/空/无搜索结果状态、浅色和深色配色。图标在后台缩小为至多 128×128，缓存限 2MiB。首次打开不自动弹键盘。
3. 点击准备时重新读取 PackageInfo，固定 package、64 位 versionCode、versionName、lastUpdateTime、UID、base APK 和完整 splits 路径集合；捕获全部源 token，在快照前后核对当前包元数据。沿用原有安全文件读取和私有只读快照，不开放任意私有文件读取。
4. 从固定基础 APK 解析包名/版本并与所选应用核对；可独立解析的分包也核对。确认页显示目标会话、固定包信息、文件大小与每个快照 SHA-256。上传前再次验证快照哈希。取消、页面失效、换目标或 Activity 销毁会关闭准备资源；已关闭的准备容器拒绝并清理迟到快照。

## 验证范围

- `bash tests/localapps/run.sh`：32 项普通合成 PackageManager/包元数据 fixture。覆盖明确拒绝/服务失败/空数据、单项标签回退、中文及包名搜索、取消、API19 与现代版本号路径、base/splits 一致性及不可变元数据。
- 本次工作区以 Android 35 官方 android.jar 对全部应用/核心 Java 源码运行 javac 编译（仅构建目录中的最小 R.java 编译占位，不生成 APK）。
- 新 Android API 仅 `PackageInfo.getLongVersionCode()`，隔离在 API28 nested helper 中；Java 8 函数与比较器依赖项目已有 desugar 配置。
- 本工作未构建/安装 APK、未启动 VM、未执行路径替换/链接/私有文件重定向或第二轮安全测试。
- 独立 API30+ 实际安装、真实屏幕截图、搜索/选中/返回/旋转/深色/快照确认测试由交付验收记录另行提供。这里的 host fixture 与编译不能替代真机或 OEM 兼容结论。
- 原本机选择页没有动态截图，不能声称像素完全匹配。界面参考是原本机布局静态证据，辅以原远端应用列表截图 `17_original_apps.png`。

## Android 官方依据（2026-10-12 核对）

- [QUERY_ALL_PACKAGES 为 normal permission](https://developer.android.com/reference/android/Manifest.permission#QUERY_ALL_PACKAGES)
- [Android 11+ 包可见性声明](https://developer.android.com/training/package-visibility/declaring)
- [ApplicationInfo.sourceDir 与 splitSourceDirs](https://developer.android.com/reference/android/content/pm/ApplicationInfo#sourceDir)
- [PackageInfo.getLongVersionCode](https://developer.android.com/reference/android/content/pm/PackageInfo#getLongVersionCode())
- [官方应用详情设置入口](https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS)
