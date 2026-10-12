# NAV-03：首次邻页真实结构预创建（普通合成验证）

日期：2026-10-12。生产变更仅 `MainActivity.java`、`RetainedModulePager.java`。
原 `build/followup-v10/candidate02`、delivery、hotfix 没有改写；未构建/签名新 APK。

## 结论

首次相邻页由标题占位改为该模块真实视图结构，提交后使用同一个已建视图。设备信息先呈现未提供值的真实信息卡结构；工具和远程设置先呈现完整本地控件；文件有路径栏、列表和 FAB；应用有列表和 FAB；进程有内存/列表结构；终端有输入与输出控件。首次未读的列表没有伪造条目。

邻页预创建不启动设备读取、元数据 helper 上传、PTY、屏控或写操作。信息、无线状态、目录、应用、进程的首次工作登记到其页对象，只有该页正式成为当前页才执行。已访问页继续保留原内容与控件。

这修正了已证实的首次标题占位差异；不声称首次数据已后台预读，不声称精确动画或全部原版生命周期一致。

## 原版证据与界限

输入：`app_replication_analysis/extracted/classes.dex`，SHA256：
`bde576a4e5918a732af5964c4df83fbe4c3a445a847d7587845c4d0a6f400875`。
只用已有反编译文本及官方 SDK dexdump 静态读取 DEX，没有运行原 APK。

`original-pager-evidence.txt` 保存精简反汇编片段与原 dump 行号/DEX 地址：
- ViewPager 构造器 `0e5394`，`0e53f8` 装载整数 1，`0e53fa` 写字段 x；`getOffscreenPageLimit` 的 `0e625c` 返回同字段。
- `q(I)` 的 `0e7574` 起，用 x 计算 current−x/current+x 的边界；`a(II)` 从 MainActivity.H.a 取实际 fragment，并 add/attach。
- setter `0e7fec` 将小于 1 的值归为 1。
- 能确定随包 ViewPager 默认 offscreen limit=1。不能由此断言原 Activity 的最终运行值；`decompiled/com.didjdk.adbhelper.MainActivity.java:42` 的 onCreate 为 native，当前静态材料不能排除 native 配置覆写。

以下行号基于现有 `app_replication_analysis/decompiled/`：
- `c.c.a.k3.java:14–41`：按七个模块创建 fragment 对象列表。
- `com.didjdk.adbhelper.MainActivity$c.java:2–13`：fragment pager adapter 继承 b.m.b.w。
- `c.c.a.v3.g0.java:7–24`：V 初始为 true，I0 清为 false 并绑定 Activity 目标。
- `c.c.a.n3.java:21–35`：onPageSelected 更新 z，对 V 且 B() 的页调用 I0。
- 工具 `c.c.a.v3.m0.z.java:110–155`：P 创建 RecyclerView/各工具行；z==1 才 I0。
- 文件 `c.c.a.v3.k0.t.java:296–339`：P 建真实控件与 adapter；z==3 才 I0。
- 应用 `c.c.a.v3.i0.r.java:177–195`：P 建列表/进度；z==4 才 I0。
- 进程 `c.c.a.v3.l0.l.java:51–65`：P 建刷新列表；z==5 才 I0。
- 终端 `c.c.a.v3.f0.java:158–175`：P 建输入、输出、按钮；z==6 才 super.I0/M0。
- 信息页是例外：`c.c.a.v3.j0.d.java:258–272` 的 P 无条件 I0。当前实现为避免扩大首次邻页工作，信息读取仍延后提交；这项时序差异明确保留。
- 远程页 `c.c.a.v3.h0.java:95–160` 创建完整设置且调用基础 I0，并启动 `c.c.a.v3.o` 等待设备版本后更新本地 UI；并无据此提前开启屏控的必要。本实现不提前屏控。

已有运行材料 `build/api26-followup-v10/RESULT.md` 证明上一候选首次是轻量占位、已访问页有连续内容。原版历史截图/XML不包含充分的首次慢横滑中间帧，因此不能用它们证明精确预载完成时刻、动画时长/曲线或所有内存驻留策略。

## 最小实现

- `RetainedModulePager.normalize()` 提前挂接当前页两侧实际视图；仍保持相同拖动与 settle 算法，不新增动画曲线。
- `MainActivity.modulePage()` 直接构建页。各构建函数接受显式 `LinearLayout body`，不临时切换全局 content/page/generation。
- `initialModuleRead()` 只登记 callable；`startModuleRead()` 要求精确当前 slot、session、page token，且未完成/未加载，才沿原 `workScoped` 入口执行。
- session 在创建 view 时从 viewSession 捕获；信息/应用异步完成更新捕获的 body。wireless 完成按页 token 判断当前性，避免预建时 generation 导致错误拒绝。
- `workScoped` 原有 generation、activation、scope、initial-attempt 的完成/失败/取消门控未改；预览不会取消活页 scope、改变 generation 或激活页。
- 终端构建不 sendTerminal；仅 `activateModule(6)` 开新 PTY，离页关闭策略不变。
- 未激活、旧 token 或旧 session 的页根拒绝 touch/key/descendant-focus；pager 的预览无障碍隐藏保持。
- 失败/取消后下次正式重进复用实际 view，重新执行一次登记的初读；刷新仍替换 slot/token。旧回调不能清除较新的加载/ready 状态。

## 验证（全部最后生产代码）

原始日志：`build/nav03-neighbor-host-tests/validation.txt`。

- `tests/navigation/run-neighbor.sh`：34 PASS。
- `tests/navigation/run.sh`：23 PASS（将三条旧 lazy-count 断言更新为相邻预建事实）。
- `tests/navigation/run-lifecycle.sh`：76 PASS。
- `tests/navigation/run-gesture.sh`：79 PASS，包括上一轮慢首步折叠修复相关输入逻辑。
- ModuleNavigationTests：41 PASS。
- RetainedModuleStateTests：1114 PASS。
- RetainedPagerGestureTests：60 PASS。
- 全 app/core Java 对 Android 35 官方 android.jar 编译通过；仅既有 deprecated API note。

新增 34 项执行了未改写的生产 `modulePage`、`activateModule`、`startModuleRead`、`currentModule`、初读登记与 `workScoped` 片段，以及实际 RetainedModulePager。覆盖：
- 首次邻页已构建、随手指移动；取消/反向后零新增初读；提交仅一次初读。
- 预建文件/应用不改变当前 content/page/generation/activation，不取消活页加载。
- 进程页预建终端不 sendTerminal；非相邻提交与终端回访按原策略开始/关闭。
- 失败重进、队列中取消、旧取消完成晚到、旧 slot 失效、会话更换及销毁回调拒绝。
- 旧/未激活控件输入拒绝，提交后同一控件可用；驻留 view/结果不重复读取。

测试边界：Android view/animation plumbing 与具体 page builders 是显式内存 doubles；抽取脚本另检查真实 builders 只使用 body、绑定 viewSession、初读延后、终端无 sendTerminal、远程启动只在点击回调中。该 harness 验证生命周期/调用顺序和源码契约，不等于 Android 真机像素、原生输入/焦点、实际 shell/SYNC 或 helper 验证。

本轮未启动 VM、未跑原 APK、无网络/真实设备、无私有路径或符号链接 fixtures、无第二安全审查。后续需针对新候选实际首邻页中间帧、取消/提交、初读失败后导航、终端驻留、慢首步滚动做受影响 Android UI 复验；不能把旧候选 API26 结果转记为新代码已通过。

## 改动文件

- `app/src/main/java/io/github/devicebridge/android/MainActivity.java`
- `app/src/main/java/io/github/devicebridge/android/RetainedModulePager.java`
- `tests/navigation/src/io/github/devicebridge/android/RetainedModulePagerTests.java`
- `tests/navigation/run-neighbor.sh`
- `tests/navigation/neighbor/extract.py`
- `tests/navigation/neighbor/NeighborPreparationTests.java.template`
- `tests/navigation/neighbor/stubs/android/view/View.java`
- `tests/navigation/neighbor/stubs/android/view/inputmethod/InputMethodManager.java`
- 本说明及 `tests/navigation/neighbor/original-pager-evidence.txt`

本 worker 未修改总回归入口；父任务已将 `bash tests/navigation/run-neighbor.sh` 加入 `tools/test-followup-navigation-server.sh`，后续冻结/重放仍须保留该检查。版本、APK、签名、交付包由父任务管理。
