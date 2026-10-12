package io.github.devicebridge.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Independently sourced activation command descriptions. This class never runs commands,
 * installs apps, downloads scripts, changes settings, or decides that a service is active.
 * Official documentation was reviewed on 2026-10-12; it is not a device compatibility test.
 */
public final class ActivationPresets {
    public static final String REVIEWED_ON = "2026-10-12";
    public enum VerifiedStatus { OFFICIAL_DOCUMENTED, UNSUPPORTED_NO_CURRENT_SOURCE }
    public enum PackagePresence { INSTALLED, NOT_FOUND, UNKNOWN }
    public enum ScriptPresence { READABLE, MISSING_OR_INACCESSIBLE, UNKNOWN, NOT_REQUIRED }
    public enum ScriptRequirement { PREEXISTING, CREATED_BY_BOOTSTRAP, NONE }
    public enum ShellOutcome { COMPLETED_SERVICE_UNVERIFIED, FAILED, UNKNOWN }

    public static final class Preset {
        public final String id, name, packageName, command, consequence, sourceUrl;
        public final List<String> requiredConditions;
        public final VerifiedStatus verifiedStatus;
        public final String versionScope, scriptPath, verificationInstructions, unavailableReason;
        public final String historicalCommand, historicalScope;
        public final boolean deviceOwner;
        public final ScriptRequirement scriptRequirement;
        /** Empty means no authoritative, app-specific non-mutating service probe was found. */
        public final List<String> serviceVerificationCommands;

        private Preset(String id, String name, String packageName, String scriptPath,
                       ScriptRequirement scriptRequirement, String versionScope,
                       String consequence, String sourceUrl, String verificationInstructions,
                       String unavailableReason, boolean deviceOwner,
                       String historicalCommand, String... requiredConditions) {
            this.id = id;
            this.name = name;
            this.packageName = DeviceCommands.packageName(packageName);
            this.scriptPath = scriptPath;
            this.scriptRequirement = scriptRequirement;
            this.versionScope = versionScope;
            this.consequence = consequence;
            this.sourceUrl = sourceUrl;
            this.verificationInstructions = verificationInstructions;
            this.unavailableReason = unavailableReason;
            this.deviceOwner = deviceOwner;
            this.historicalCommand = historicalCommand;
            this.historicalScope = historicalCommand.isEmpty() ? "" :
                    "仅为甲壳虫ADB助手 1.3.1 (versionCode 17) 的静态接口记录；目标应用版本未知，不代表当前兼容。";
            this.requiredConditions = Collections.unmodifiableList(Arrays.asList(requiredConditions.clone()));
            this.serviceVerificationCommands = Collections.emptyList();
            this.verifiedStatus = unavailableReason.isEmpty()
                    ? VerifiedStatus.OFFICIAL_DOCUMENTED : VerifiedStatus.UNSUPPORTED_NO_CURRENT_SOURCE;
            this.command = !isSupported() ? "" : scriptRequirement == ScriptRequirement.CREATED_BY_BOOTSTRAP
                    ? breventBootstrap() : "sh " + DeviceCommands.quote(DeviceCommands.remotePath(scriptPath));
        }

        public boolean isSupported() { return verifiedStatus == VerifiedStatus.OFFICIAL_DOCUMENTED; }

        /** Validated, quoted command only. Does not authorize or execute a privileged change. */
        public String buildCommand() {
            if (!isSupported()) throw new IllegalStateException(unavailableReason);
            return command;
        }

        /**
         * Fail-closed UI helper. Observe on the same live target/session before requesting
         * execution approval. Conditions include the app version/mode and trusting its source.
         * A readable path alone is not proof of authenticity, compatibility, or service health.
         */
        public String buildCommand(PackagePresence installed, ScriptPresence script,
                                   boolean conditionsConfirmed) {
            buildCommand();
            if (installed != PackagePresence.INSTALLED)
                throw new IllegalStateException("未确认目标用户已安装该应用，请重新检查。");
            if (scriptRequirement == ScriptRequirement.PREEXISTING && script != ScriptPresence.READABLE)
                throw new IllegalStateException("未确认启动脚本可读；请打开目标应用的启动引导后重新检查。不要自动下载替代脚本。");
            if (!conditionsConfirmed)
                throw new IllegalStateException("请先核对版本、工作模式和所列设备条件。");
            return command;
        }

        /** Read-only, exact-package result must be parsed; substring matches are insufficient. */
        public String packageProbeCommand() { return packageProbeCommand(0); }
        public String packageProbeCommand(int userId) {
            if (userId != 0) throw new IllegalArgumentException("固定启动路径仅支持已核对的设备主用户 0；其他用户/工作资料需单独适配。");
            return "pm list packages --user " + DeviceCommands.userId(userId) + " "
                    + DeviceCommands.quote(packageName);
        }

        /**
         * Pure shell test: never launches the app or changes a path. For Brevent this probes
         * the bootstrap output, not a precondition; the official bootstrap creates it.
         * Empty for unavailable presets so historical paths are not silently promoted.
         */
        public String scriptProbeCommand() {
            if (!isSupported() || scriptPath.isEmpty()) return "";
            String q = DeviceCommands.quote(DeviceCommands.remotePath(scriptPath));
            return "if [ -f " + q + " ] && [ -r " + q + " ]; then echo 'READABLE'; "
                    + "else echo 'MISSING_OR_INACCESSIBLE'; fi";
        }

        /** Neither a successful exit nor a script's success text proves service health. */
        public ShellOutcome assessShellResult(int exitCode) {
            return exitCode < 0 ? ShellOutcome.UNKNOWN : exitCode == 0
                    ? ShellOutcome.COMPLETED_SERVICE_UNVERIFIED : ShellOutcome.FAILED;
        }
    }

    private static final String COMMON = "这些固定路径只适用于设备主用户 0；其他用户/工作资料暂不支持。仅在本人拥有或明确获准管理的已连接设备上操作；目标应用必须来自可信来源，执行前逐次确认具体命令和影响。";
    private static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
        new Preset("shizuku", "Shizuku", "moe.shizuku.privileged.api",
            "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh", ScriptRequirement.PREEXISTING,
            "官方指南标注 Shizuku v11.2.0+；以已安装版本的启动引导为准。",
            "以 ADB shell 权限启动 Shizuku 服务；获得用户授权的应用可借此调用系统 API。重启后通常需要重新启动。不会自动授权其他应用。",
            "https://shizuku.rikka.app/zh-hans/guide/setup/",
            "在目标设备手动打开 Shizuku，确认其显示服务正在运行；shell 返回 0 只表示命令完成。已运行时无需重复启动。", "", false,
            "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh",
            COMMON, "先打开 Shizuku 并核对 v11.2.0+ 启动说明；安装包和启动脚本需分别检查。",
            "ADB 调试已由设备所有者授权；官方无线调试引导要求 Android 11+。不得自动放宽 OEM 安全设置。",
            "服务重启失效、后台限制或调试开关变化时，应回到 Shizuku 的官方引导处理。"),
        new Preset("icebox", "冰箱 · 普通 ADB 模式", "com.catchingnow.icebox",
            "/sdcard/Android/data/com.catchingnow.icebox/files/start.sh", ScriptRequirement.PREEXISTING,
            "官方普通 ADB 模式指南要求 Ice Box >= 3.14.0。",
            "以 ADB shell 权限启动冰箱普通 ADB 服务，允许其按用户设置冻结/解冻应用。重启或部分 USB 设置变化会使服务失效；冻结应用可能在重新激活前不可用。",
            "https://iceboxdoc.catchingnow.com/Active%20with%20simple%20ADB%20mode.html",
            "在冰箱内确认普通 ADB 模式已可用；不要通过冻结重要应用来测试。退出该模式前按冰箱说明解冻需要保留可用的应用。", "", false,
            "sh /sdcard/Android/data/com.catchingnow.icebox/files/start.sh",
            COMMON, "Ice Box >= 3.14.0；先手动打开并选择普通 ADB 模式，确认脚本存在且可读。",
            "官方建议保持 USB 调试开启及仅充电模式；MIUI 的独立 USB 调试安全设置应由用户按官方说明判断，程序不会自动更改。"),
        new Preset("brevent", "黑阈 · 官方 bootstrap", "me.piebridge.brevent",
            "/data/local/tmp/brevent.sh", ScriptRequirement.CREATED_BY_BOOTSTRAP,
            "2026-10-12 官方激活网站发布的 bootstrap 流程；官方页面未标注版本范围，须与已安装应用引导核对。",
            "以 ADB shell 权限运行已安装黑阈 APK 的 bootstrap，生成 /data/local/tmp/brevent.sh 并启动黑阈服务；服务可按黑阈设置限制后台应用。",
            "https://brevent.sh/",
            "手动打开黑阈检查服务是否响应；脚本退出或输出成功不能证明服务持续运行。bootstrap 后的脚本检查仅证明文件可读。", "", false,
            "sh /data/data/me.piebridge.brevent/brevent.sh",
            COMMON, "先安装并打开可信来源的黑阈；核对其启动引导支持官方 bootstrap。",
            "pm path 必须返回一个可读的 APK 路径；多 APK/split 输出会拒绝执行，需重新适配，不能拼接猜测。",
            "启动前不要求 /data/local/tmp/brevent.sh 已存在；它由本次官方 bootstrap 生成，bootstrap 失败则不继续执行。"),
        new Preset("stopapp", "小黑屋 · 麦克斯韦妖模式", "web1n.stopapp",
            "/sdcard/Android/data/web1n.stopapp/files/demon.sh", ScriptRequirement.PREEXISTING,
            "web1n 当前在线执行器的麦克斯韦妖模式；页面未给应用版本范围，须与已安装应用引导核对。",
            "以 ADB shell 权限启动小黑屋的麦克斯韦妖服务，允许其按用户设置冻结/解冻应用。此预设不会设置设备所有者。",
            "https://adb.http.gs/",
            "手动打开小黑屋核对麦克斯韦妖模式状态。切换工作模式或卸载前，先按官方文档解冻所有应用。", "", false,
            "sh /storage/emulated/0/Android/data/web1n.stopapp/files/starter.sh",
            COMMON, "手动打开小黑屋并选择麦克斯韦妖模式，核对 demon.sh 与应用启动引导一致且可读。",
            "旧 starter.sh 不等于当前 demon.sh；缺失时不回退到旧路径、不自动启动应用或下载脚本。"),
        new Preset("permissiondog", "权限狗 · 待核验", "com.web1n.permissiondog",
            "", ScriptRequirement.NONE, "目标应用版本未知；仅有原助手 1.3.1 的历史接口记录。",
            "历史方案会以 ADB shell 权限运行权限狗脚本，使其能够修改其他应用的 AppOps/权限行为。变更可能影响应用正常使用。",
            "https://https.gs/", "需在权限狗应用自己的当前官方说明中确认启动与撤销方式；暂不提供服务检查命令。",
            "尚未取得权限狗当前官方精确启动指令、版本条件及撤销说明；历史命令只供审核，不能执行。", false,
            "sh /storage/emulated/0/Android/data/com.web1n.permissiondog/files/starter.sh",
            COMMON, "必须补充该包当前版本的权威说明；第三方下载站及同名开源项目不足以验证。"),
        new Preset("apkinstaller", "安装狮 · 设备所有者（不可用）", "com.modosa.apkinstaller",
            "", ScriptRequirement.NONE, "未取得安装狮当前官方组件、兼容范围和撤销步骤。",
            "高影响权限：设备所有者具有广泛、持久的设备管理能力，可能管理应用安装/卸载、设备策略及安全设置，重启不会自动撤销。可能无法普通卸载，移除管理甚至可能需要清除设备数据。",
            "https://developer.android.com/tools/adb#dpm",
            "不执行。先取得安装狮当前版本的官方撤销说明；不得将普通卸载或 dpm remove-active-admin 当作普遍可用的回退。后者只支持 manifest 声明 testOnly 的管理员。",
            "缺少安装狮官方精确组件、设备要求和可验证移除步骤，设备所有者预设保持不可用。", true,
            "dpm set-device-owner com.modosa.apkinstaller/.receiver.AdminReceiver",
            COMMON, "仅适用于用户有权完全管理的设备，不能用于已有组织管理或其他设备所有者的设备。",
            "Android 官方开发配置要求已安装正确 DPC 且设备无账户；用户、配置文件和 OEM 限制也必须核对，不能自动删除账户或恢复出厂。",
            "必须先备份并确认可行的官方解除管理/卸载步骤；必要时恢复出厂会清除数据，不能作为自动重试。",
            "任何未来启用都必须为该设备、该应用、具体权限及后果单独取得明确确认。"),
        new Preset("scene5", "Scene5 · 待核验", "com.omarea.vtools",
            "", ScriptRequirement.NONE, "仅有原助手 1.3.1 的 Scene5 路径；当前官网还发布其他大版本，不能视为相同。",
            "历史方案会以 ADB shell 权限启动 Scene 服务，供性能监测及应用/系统管理使用；ADB 启动不代表购买或解锁专业版。",
            "https://omarea.com/", "在已安装 Scene 的 ADB 模式引导中核对具体版本与路径；暂不推测服务进程名称或成功标记。",
            "尚未在当前官方公开资料核实 Scene5 的精确启动路径和版本条件；旧路径不可作为新版命令。", false,
            "sh /data/user/0/com.omarea.vtools/files/up.sh",
            COMMON, "需要已安装 Scene 的精确大版本及官方 ADB 引导；不自动下载或执行互联网上的 scene_adb_init.sh。")
    ));

    private ActivationPresets() { }
    public static List<Preset> all() { return PRESETS; }
    public static Preset get(String id) {
        for (Preset preset : PRESETS) if (preset.id.equals(id)) return preset;
        throw new IllegalArgumentException("Unknown activation preset");
    }

    public static PackagePresence parsePackageProbe(Preset preset, int exitCode,
                                                    String stdout, String stderr) {
        if (preset == null) throw new IllegalArgumentException("preset is required");
        if (exitCode != 0 || stdout == null || hasText(stderr)) return PackagePresence.UNKNOWN;
        boolean found = false;
        for (String line : stdout.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            if (!line.startsWith("package:")) return PackagePresence.UNKNOWN;
            String name = line.substring(8);
            try { DeviceCommands.packageName(name); }
            catch (IllegalArgumentException ignored) { return PackagePresence.UNKNOWN; }
            if (name.equals(preset.packageName)) found = true;
        }
        return found ? PackagePresence.INSTALLED : PackagePresence.NOT_FOUND;
    }

    public static ScriptPresence parseScriptProbe(int exitCode, String stdout, String stderr) {
        if (exitCode != 0 || stdout == null || hasText(stderr)) return ScriptPresence.UNKNOWN;
        String marker = stdout.endsWith("\r\n") ? stdout.substring(0, stdout.length() - 2)
                : stdout.endsWith("\n") ? stdout.substring(0, stdout.length() - 1) : stdout;
        if (marker.equals("READABLE")) return ScriptPresence.READABLE;
        if (marker.equals("MISSING_OR_INACCESSIBLE")) return ScriptPresence.MISSING_OR_INACCESSIBLE;
        return ScriptPresence.UNKNOWN;
    }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private static String breventBootstrap() {
        // Original public command: pm path -> CLASSPATH -> app_process bootstrap -> shell.
        // Independently added fail-closed checks reject missing/multiple paths and a failed
        // bootstrap. Data from pm remains in quoted variables, never eval'd or interpolated.
        return "output=$(pm path 'me.piebridge.brevent') || exit $?; "
                + "case \"$output\" in *'\n'*|*'\r'*) exit 64 ;; package:/*) ;; *) exit 64 ;; esac; "
                + "export CLASSPATH=\"${output#package:}\"; "
                + "[ -r \"$CLASSPATH\" ] && app_process '/system/bin' "
                + "'me.piebridge.brevent.server.BreventServer' 'bootstrap' "
                + "&& /system/bin/sh '/data/local/tmp/brevent.sh'";
    }
}
