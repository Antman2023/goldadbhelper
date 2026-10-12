# 终端转录对齐说明

本轮依据相邻只读分析目录中的原版静态 Java、布局和已有截图/XML；没有运行原 APK，也没有连接任何设备。本轮修改的宿主验证不等于 API19/API26 真机或模拟器复验。

## 原版实际行为

- `c.c.a.v3.f0.K0`：提示符 `#FF3AA23A`，错误消息分支 `#FFFF0000`。普通输出在 `c.c.a.v3.j` 直接 append，使用 `b8.xml` 中输出 EditText 的主题默认文字色。虽然 K0 有白色分支，静态路径不能证明普通 stdout 都是白色。
- 输出是可选取的 EditText 转录；没有看到 VT 网格、光标寻址或 ANSI SGR 渲染器。`j` 的真实交互分支直接 append。不能把“完整 VT”当成本轮原版一致性的前提。
- `f0.L0` 对输入 trim，移除开头的 `adb shell `；`clear` 忽略大小写，只保留实际输出末行。菜单 clear：交互分支同样留末行，非交互 fallback 清空后用 whoami / getprop / pwd 生成新提示符。
- 非交互 fallback 将命令退出码、用户、设备产品名、当前目录包含在独立标记回复中；真实交互分支使用远端实际输出。退出分支发送 exit，外层恢复逻辑可重新启动。`e0.run` 将输出滚到底部。
- `runtime/screenshots/19_original_commands.png` 与 `runtime/19_original_commands.xml` 仅证明初始 `shell@generic:/ $` 绿色提示符和界面。该次原版截图未验证后续命令、stderr、clear 或退出码。

## 独立实现

- 保持原有持久 PTY 和 `InteractiveShell` 协议实现不变；不回退成每条命令重新开 shell，也不注入额外的 whoami / pwd / 完成标记命令。
- `TerminalOutputDecoder` 保持原有有界 UTF-8、跨包解码和 ESC/OSC/DCS 过滤。OSC 超链接、剪贴板等仍只被丢弃，不执行。ANSI SGR 不被解释。
- `TerminalTranscript` 按真实流类型保存样式。普通输出用主题正文色；已知 stderr、连接错误和非零 shell 退出状态用红色；常见 Android 实际提示符前缀使用原版绿色。
- 提示符识别只是展示规则，不是“命令完成”判据。支持 `shell@generic:/ $`、`generic_x86:/ #`、`127|generic_x86:/data/local/tmp #` 等来自目标的实际文本；不改写用户/目录，也不把旧提示符拿来合成新状态。自定义 PS1 或特殊主机名不保证着色。
- PTY 通常把 stdout/stderr 合并在同一输出流。只能对协议明确标记的 stderr 着红色，不能可靠地从普通文本猜测哪些字是错误。legacy 无真实退出状态时明确显示“退出码未知”，不会把 -1 当成功或实际退出码。
- 输入 clear 或菜单清空输出时，先消费当前 32ms 合并队列，再仅保留真正的末行与样式。运行中的输出末行不是提示符时就保留该行；不会恢复更早的提示符。清空后新到的输出仍可继续显示。
- exit 发送给当前 PTY，等待实际结束结果；下一条命令可新开 shell。literal exit 正在退出时阻止额外输入被误投递，启动/写入失败会解除等待状态。重启/离开/换目标有 epoch、视图代次、会话和输出实例标记，旧实例迟到回调不写新视图。
- 回调只解码并排队；所有网络写入留在后台，UI 批量渲染仍为 32ms。pending 最多 64Ki 个 UTF-16 单元，scrollback 最多 1Mi 个 UTF-16 单元，截断不切开代理对。这里沿用先前界面字符上限，不声称是严格的 UTF-8 字节内存上限。
- 采用增量 span 更新，正常短输出只替换当前末行/新后缀；很长的单行不会每次重建整个已有行。输出追加后滚到末尾。
- 历史下箭头可回到空输入；clear/exit 纳入历史，输入 Enter/IME 运行行为对齐。

## 验证范围

- `TerminalTranscriptTests`：宿主纯 Java 138035 断言通过。包含 stdout/stderr 分色、任意 UTF-8/提示符切片、clear 当前目录/运行中末行/待显示队列、64Ki pending、1Mi scrollback、代理对截断、OSC 不执行、退出结果文案，以及 2000 轮增量 edit 与样式模型对照。
- 现有 `InteractiveShellTests`：宿主内存协议测试 716 断言通过，保留 UTF-8/大 OSC/独立流/并发写入/异常/关闭覆盖。
- 以 Android35 的 android.jar 和现有离线依赖编译完整 app/core 源通过；这是编译验证，不是 Android span/IME/滚动实测。
- `TerminalRenderer` 的 Android Editable、颜色显示、Enter/IME、clear/退出/重启及跨会话晚回调需以最终 APK 在 API19/API26 重新验收。本轮没有设备权限或设备操作，未宣称这些已复验。
- 父任务冻结的 v5 已含主实现。随后跨行旧 stderr span 前半段保留、异常后退出等待复位、Enter/IME 和滚动修改均是后续源码；不能把 v5 实测自动套用到后续 APK。

宿主结果：`build/test-results/TerminalTranscriptTests.txt`、`build/test-results/InteractiveShellTests-terminal-alignment.txt`。
