package io.github.devicebridge.core;

/** Host-only package command/confirmation policy tests; no device or shell execution. */
public final class PackageCommandStrategyTests {
    private static int assertions;
    public static void main(String[] args) {
        for (int sdk : new int[] {19, 20}) {
            PackageCommandStrategy old = PackageCommandStrategy.forSdk(sdk);
            equal(false, old.supportsSplitInstall());
            equal(false, old.supportsRestoreExisting());
            PackageCommandStrategy.InstallPlan install = old.install(1, 0, true);
            equal(PackageCommandStrategy.Scope.ALL_USERS_LEGACY, install.scope);
            equal("pm install -r '/data/local/tmp/app.apk'", install.singleCommand("/data/local/tmp/app.apk"));
            equal("pm install '/data/local/tmp/a b.apk'", old.install(1, 0, false).singleCommand("/data/local/tmp/a b.apk"));
            check(install.scopeDescription().contains("全部用户"), "Legacy install scope disclosed");
            check(install.scopeDescription().contains("无法仅限用户 0"), "Legacy install scope not described as user 0 only");
            rejected(() -> old.install(2, 0, true), "不支持分包");
            rejected(() -> old.install(1, 10, true), "指定用户");
            rejected(install::createCommand, "不允许");
            rejected(() -> install.requireApkCount(2), "集合已改变");
            install.requireApkCount(1); assertions++;
            PackageCommandStrategy.CommandPlan uninstall = old.uninstall("com.example.app", 0, false);
            equal("pm uninstall 'com.example.app'", uninstall.command);
            equal(PackageCommandStrategy.Scope.ALL_USERS_LEGACY, uninstall.scope);
            check(uninstall.scopeDescription().contains("全部用户"), "Legacy uninstall scope disclosed");
            equal("pm uninstall -k 'com.example.app'", old.uninstall("com.example.app", 0, true).command);
            rejected(() -> old.uninstall("com.example.app", 10, true), "指定用户");
            rejected(() -> old.restore("com.example.app", 0), "未执行恢复");
        }
        for (int sdk : new int[] {21, 23, 24, 25, 26, 35, 36}) {
            PackageCommandStrategy modern = PackageCommandStrategy.forSdk(sdk);
            equal(true, modern.supportsSplitInstall());
            PackageCommandStrategy.InstallPlan single = modern.install(1, 0, true);
            equal(PackageCommandStrategy.Scope.USER, single.scope);
            equal("pm install -r --user 0 '/data/local/tmp/app.apk'", single.singleCommand("/data/local/tmp/app.apk"));
            check(!single.scopeDescription().contains("全部用户"), "Modern plan must retain user bound");
            equal("pm uninstall -k --user 0 'com.example.app'", modern.uninstall("com.example.app", 0, true).command);
            equal("pm uninstall --user 10 'com.example.app'", modern.uninstall("com.example.app", 10, false).command);
            PackageCommandStrategy.InstallPlan split = modern.install(3, 0, true);
            equal("pm install-create -r --user 0", split.createCommand());
            equal(3, split.apkCount);
            rejected(() -> split.singleCommand("/data/local/tmp/base.apk"), "部分安装");
            rejected(() -> split.requireApkCount(1), "集合已改变");
            if (sdk < 26) rejected(() -> modern.restore("com.example.app", 0), "未执行恢复");
            else equal("cmd package install-existing --user 0 'com.example.app'", modern.restore("com.example.app", 0).command);
        }
        equal(19, PackageCommandStrategy.fromSdkOutput("19\r\n").sdk);
        equal(35, PackageCommandStrategy.fromSdkOutput(" 35 ").sdk);
        for (String output : new String[] {null, "", "Failure", "unknown\n19", "35\n19", "19; echo x", "-1", "0", "18", "1001", "99999"})
            rejected(() -> PackageCommandStrategy.fromSdkOutput(output), null);
        PackageCommandStrategy modern = PackageCommandStrategy.forSdk(35);
        rejected(() -> modern.install(0, 0, true), null);
        rejected(() -> modern.install(1, -1, true), null);
        rejected(() -> modern.uninstall("com.example.app", -1, false), null);
        rejected(() -> modern.restore("com.example.app", -1), null);
        rejected(() -> modern.uninstall("com.example.app; bad", 0, false), null);
        rejected(() -> PackageCommandStrategy.forSdk(19).uninstall("com.example.app; bad", 0, false), null);
        equal("pm list packages -s -u --user 0", DeviceCommands.listSystemPackagesIncludingUninstalled(0));
        System.out.println("PackageCommandStrategyTests: " + assertions + " assertions passed (host-only; no device actions)");
    }
    private static void equal(Object expected, Object actual) {
        check(expected == null ? actual == null : expected.equals(actual), "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejected(Runnable action, String message) {
        assertions++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) {
            if (message != null && !expected.getMessage().contains(message))
                throw new AssertionError("Wrong rejection: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection");
    }
}
