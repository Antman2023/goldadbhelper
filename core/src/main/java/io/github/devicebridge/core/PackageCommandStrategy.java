package io.github.devicebridge.core;

/**
 * Immutable package-command policy selected from the TARGET device SDK before confirmation.
 * API 19/20 pm install/uninstall operate on all users. Never infer an all-user fallback
 * from a failed modern --user command. Source evidence: docs/PACKAGE_COMMAND_COMPATIBILITY.md.
 */
public final class PackageCommandStrategy {
    public enum Scope { USER, ALL_USERS_LEGACY }
    public final int sdk;
    private PackageCommandStrategy(int sdk) { this.sdk = sdk; }

    public static PackageCommandStrategy forSdk(int sdk) {
        if (sdk < 19 || sdk > 1000) throw new IllegalArgumentException("目标 Android API 无法确认或不受支持：" + sdk);
        return new PackageCommandStrategy(sdk);
    }
    public static PackageCommandStrategy fromSdkOutput(String output) {
        String value = output == null ? "" : output.trim();
        if (!value.matches("[0-9]{1,4}"))
            throw new IllegalArgumentException("无法确认目标 Android API；未选择安装或卸载命令。");
        return forSdk(Integer.parseInt(value));
    }
    public boolean supportsSplitInstall() { return sdk >= 21; }
    public boolean supportsRestoreExisting() { return sdk >= 26; }

    public InstallPlan install(int apkCount, int userId, boolean replace) {
        if (apkCount < 1) throw new IllegalArgumentException("未选择 APK");
        DeviceCommands.userId(userId);
        if (apkCount > 1 && !supportsSplitInstall())
            throw new UnsupportedOperationException("目标 Android API " + sdk
                    + " 不支持分包 APK 安装（需要 API 21 或更高版本）。没有上传或安装任何分包；请选择完整的单 APK。");
        Scope scope = scopeFor(userId);
        return new InstallPlan(sdk, scope, userId, apkCount, replace);
    }
    public CommandPlan uninstall(String packageName, int userId, boolean keepData) {
        Scope scope = scopeFor(userId);
        String command = scope == Scope.ALL_USERS_LEGACY
                ? DeviceCommands.uninstallAllUsersLegacy(packageName, keepData)
                : DeviceCommands.uninstall(packageName, userId, keepData);
        return new CommandPlan(sdk, scope, userId, command);
    }
    public void requireRestoreExisting() {
        if (!supportsRestoreExisting())
            throw new UnsupportedOperationException("目标 Android API " + sdk
                    + " 不提供此应用已验证的 install-existing 恢复命令（需要 API 26 或更高版本）。未执行恢复；启用被停用的应用请使用“启用应用”。");
    }
    public CommandPlan restore(String packageName, int userId) {
        requireRestoreExisting();
        return new CommandPlan(sdk, Scope.USER, DeviceCommands.userId(userId),
                DeviceCommands.restorePackage(packageName, userId));
    }
    private Scope scopeFor(int userId) {
        DeviceCommands.userId(userId);
        if (sdk < 21) {
            // A non-owner selection must not silently expand to another user's data.
            if (userId != 0) throw new UnsupportedOperationException("此旧版系统无法限定到指定用户；未生成命令。");
            return Scope.ALL_USERS_LEGACY;
        }
        return Scope.USER;
    }
    private static String scopeDescription(int sdk, Scope scope, int userId) {
        return scope == Scope.ALL_USERS_LEGACY
                ? "目标 Android API：" + sdk + "\n影响范围：全部用户。此旧版 pm 安装/卸载不支持 --user，无法仅限用户 0。"
                : "目标 Android API：" + sdk + "\n目标用户：" + userId;
    }
    public static final class CommandPlan {
        public final int sdk, userId;
        public final Scope scope;
        public final String command;
        private CommandPlan(int sdk, Scope scope, int userId, String command) {
            this.sdk = sdk; this.scope = scope; this.userId = userId; this.command = command;
        }
        public String scopeDescription() { return PackageCommandStrategy.scopeDescription(sdk, scope, userId); }
    }
    public static final class InstallPlan {
        public final int sdk, userId, apkCount;
        public final Scope scope;
        public final boolean replace;
        private InstallPlan(int sdk, Scope scope, int userId, int apkCount, boolean replace) {
            this.sdk = sdk; this.scope = scope; this.userId = userId;
            this.apkCount = apkCount; this.replace = replace;
        }
        public String scopeDescription() { return PackageCommandStrategy.scopeDescription(sdk, scope, userId); }
        public void requireApkCount(int actual) {
            if (actual != apkCount) throw new IllegalStateException("已确认的 APK 集合已改变，请重新选择并确认。");
        }
        public String singleCommand(String path) {
            if (apkCount != 1) throw new IllegalStateException("分包集合不能作为单个 APK 部分安装");
            return scope == Scope.ALL_USERS_LEGACY
                    ? DeviceCommands.installSingleAllUsersLegacy(path, replace)
                    : DeviceCommands.installSingle(path, userId, replace);
        }
        public String createCommand() {
            if (apkCount < 2 || sdk < 21 || scope != Scope.USER)
                throw new IllegalStateException("此确认不允许创建分包安装会话");
            return DeviceCommands.installCreate(userId, replace);
        }
    }
}
