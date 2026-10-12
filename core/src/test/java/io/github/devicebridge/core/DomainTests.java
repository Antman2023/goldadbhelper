package io.github.devicebridge.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/** Standalone Java tests. Uses fixture output and a recording executor; never touches a device. */
public final class DomainTests {
    private static int assertions;
    private DomainTests() { }
    public static void main(String[] args) throws Exception { run(); }
    public static void run() throws Exception {
        assertions = 0;
        commands(); parsers(); operations(); splitInstalls();
        System.out.println("DomainTests: " + assertions + " assertions passed (no device actions)");
    }
    private static void commands() throws Exception {
        equal("''", DeviceCommands.quote(""));
        equal("'a b'", DeviceCommands.quote("a b"));
        equal("'a'\"'\"'b'", DeviceCommands.quote("a'b"));
        equal("'$(id); `echo x` & * \\ \"'", DeviceCommands.quote("$(id); `echo x` & * \\ \""));
        equal("'line\nnext'", DeviceCommands.quote("line\nnext"));
        rejects(() -> DeviceCommands.quote("a\0b"));
        rejects(() -> DeviceCommands.quote(null));
        equal("mkdir '/sdcard/新目录'", DeviceCommands.mkdir("/sdcard/新目录"));
        check(DeviceCommands.touch("/sdcard/new").contains("[ -L '/sdcard/new' ]"), "touch refuses dangling links");
        check(!DeviceCommands.touch("/sdcard/new").contains("printf"), "old Android guard needs no printf");
        check(DeviceCommands.touch("/sdcard/new").contains("touch -- '/sdcard/new'"), "touch command");
        check(DeviceCommands.move("/a", "/b").contains("mv -n -- '/a' '/b'"), "move quoted");
        check(DeviceCommands.copy("/a", "/b", true).contains("cp -R -n -- '/a' '/b'"), "recursive copy");
        equal("rm -r '/sdcard/x'", DeviceCommands.delete("/sdcard/x", true));
        equal("rm '/sdcard/-ordinary file'", DeviceCommands.delete("/sdcard/-ordinary file", false));
        rejects(() -> DeviceCommands.delete("/", true));
        rejects(() -> DeviceCommands.delete("/./", true));
        rejects(() -> DeviceCommands.delete("//", true));
        rejects(() -> DeviceCommands.delete("/sdcard/../", true));
        rejects(() -> DeviceCommands.mkdir("-rf"));
        rejects(() -> DeviceCommands.childPath("/sdcard", "../x"));
        equal("/sdcard/a'b", DeviceCommands.childPath("/sdcard/", "a'b"));
        equal("stat -c '%f|%s|%Y|%u|%g' -- '/a'", DeviceCommands.stat("/a"));
        equal("readlink '/a'", DeviceCommands.readlink("/a"));
        equal("pm list packages -f -U -s --user 10", DeviceCommands.listPackages(DeviceCommands.PackageFilter.SYSTEM, 10));
        equal("pm list packages -f -U -3 --user 0", DeviceCommands.listPackages(DeviceCommands.PackageFilter.THIRD_PARTY, 0));
        equal("pm list packages -f -U -d --user 0", DeviceCommands.listPackages(DeviceCommands.PackageFilter.DISABLED, 0));
        equal("pm uninstall -k --user 10 'com.test.app'", DeviceCommands.uninstall("com.test.app", 10, true));
        equal("pm uninstall -k 'com.test.app'", DeviceCommands.uninstallAllUsersLegacy("com.test.app", true));
        equal("pm install -r '/data/local/tmp/old app.apk'", DeviceCommands.installSingleAllUsersLegacy("/data/local/tmp/old app.apk", true));
        equal("pm list packages -s -u --user 0", DeviceCommands.listSystemPackagesIncludingUninstalled(0));
        equal("pm clear --user 0 'com.test.app'", DeviceCommands.clearData("com.test.app", 0));
        equal("pm install -r --user 0 '/data/local/tmp/my app.apk'", DeviceCommands.installSingle("/data/local/tmp/my app.apk", 0, true));
        equal("pm install-write 42 'split_0.apk' '/data/local/tmp/app.apk'", DeviceCommands.installWrite(42, "split_0.apk", "/data/local/tmp/app.apk"));
        rejects(() -> DeviceCommands.installWrite(-1, "base.apk", "/a"));
        rejects(() -> DeviceCommands.installWrite(1, "../a.apk", "/a"));
        rejects(() -> DeviceCommands.packageName("com.test; reboot"));
        rejects(() -> DeviceCommands.packageName("-x"));
        rejects(() -> DeviceCommands.packageName("com.test..app"));
        rejects(() -> DeviceCommands.packageName("com.1app"));
        equal("android", DeviceCommands.packageName("android"));
        rejects(() -> DeviceCommands.forceStop("com.test.app", -1));
        equal("am start --user 0 -n 'com.test/.Main$Nested'", DeviceCommands.startComponent("com.test/.Main$Nested", 0));
        rejects(() -> DeviceCommands.startComponent("com.test/.Main;reboot", 0));
        rejects(() -> DeviceCommands.startComponent("com.test/.Main/Other", 0));
        equal("kill -15 42", DeviceCommands.killProcess(42, false));
        rejects(() -> DeviceCommands.killProcess(1, true));
        equal("wm size '1080x1920'", DeviceCommands.setDisplaySize(1080, 1920));
        rejects(() -> DeviceCommands.setDisplaySize(0, 1920));
        rejects(() -> DeviceCommands.setDisplayDensity(1));
        equal("wm density reset", DeviceCommands.resetDisplayDensity());
        equal("df", DeviceCommands.storage());
        equal("settings put global 'window_animation_scale' '0.5'", DeviceCommands.setAnimation(DeviceCommands.Animation.WINDOW, 0.5));
        rejects(() -> DeviceCommands.setAnimation(DeviceCommands.Animation.WINDOW, Double.NaN));
        rejects(() -> DeviceCommands.setAnimation(DeviceCommands.Animation.WINDOW, Double.POSITIVE_INFINITY));
        rejects(() -> DeviceCommands.setAnimation(DeviceCommands.Animation.WINDOW, -0.5));
        equal("tcpip:5555", DeviceCommands.wirelessService(5555));
        equal("usb:", DeviceCommands.usbService());
        equal("reboot:recovery", DeviceCommands.rebootService(DeviceCommands.RebootMode.RECOVERY));
        rejects(() -> DeviceCommands.wirelessService(0));
        rejects(() -> DeviceCommands.wirelessService(65536));
        equal("input keyevent 4", DeviceCommands.key(DeviceCommands.NavigationKey.BACK));
        equal("input tap 12 34", DeviceCommands.tap(12, 34));
        equal("input swipe 0 1 2 3 400", DeviceCommands.swipe(0, 1, 2, 3, 400));
        rejects(() -> DeviceCommands.tap(-1, 2));
        rejects(() -> DeviceCommands.swipe(0, 0, 10, 10, 0));
        equal("input text 'a%sb'", DeviceCommands.inputText("a b"));
        equal("input text '%' && input text 's'", DeviceCommands.inputText("%s"));
        equal("input text 'a'\"'\"';$(id)'", DeviceCommands.inputText("a';$(id)"));
        rejects(() -> DeviceCommands.inputText("你好"));
        rejects(() -> DeviceCommands.inputText("a\nb"));
        equal("sh '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh'", DeviceCommands.activateShizuku());
    }
    private static void parsers() throws Exception {
        Map<String, String> props = DeviceParsers.properties("[ro.product.model]: [Pixel 8]\r\n[empty]: []\nmalformed\n[x]: [a]b]\n");
        equal("Pixel 8", props.get("ro.product.model")); equal("", props.get("empty")); equal("a]b", props.get("x"));
        equal(3, props.size());
        DeviceParsers.DeviceInfo device = DeviceParsers.deviceInfo("[ro.product.manufacturer]: [Example]\n[ro.build.version.sdk]: [35]\n[ro.boot.serialno]: [XYZ]\n");
        equal("Example", device.manufacturer); equal(35, device.apiLevel); equal("XYZ", device.serial); equal(null, device.model);
        equal(null, DeviceParsers.deviceInfo("[ro.build.version.sdk]: [unknown]").apiLevel);
        DeviceParsers.BatteryInfo battery = DeviceParsers.battery("Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  level: 45\n  scale: 50\n  temperature: 273\n  voltage: 4200\n");
        equal(90.0, battery.percentage); equal(27.3, battery.temperatureCelsius); equal(4200, battery.voltageMillivolts);
        equal(Boolean.TRUE, battery.usbPowered); equal(Boolean.FALSE, battery.acPowered); equal(null, battery.health);
        equal(null, DeviceParsers.battery("level: 1\nscale: 0").percentage);
        equal(null, DeviceParsers.battery("level: 101\nscale: 100").percentage);
        DeviceParsers.MemoryInfo memory = DeviceParsers.memory("MemTotal: 4096 kB\nMemAvailable: 1024 kB\nMemFree: 128 kB\nBuffers: bad\n");
        equal(4194304L, memory.totalBytes); equal(3145728L, memory.usedBytes); equal(131072L, memory.freeBytes);
        equal(null, DeviceParsers.memory("MemTotal: 2 kB\nMemFree: 1 kB").availableBytes);
        DeviceParsers.MemoryInfo framework = DeviceParsers.memory("MemTotal: 4096 kB\nMemFree: 128 kB").withFrameworkAvailable(1024L * 1024);
        equal(Long.valueOf(1024L * 1024), framework.availableBytes);
        equal(Long.valueOf(3072L * 1024), framework.usedBytes);
        check(framework.frameworkAvailability, "framework source is distinct from kernel MemAvailable");
        equal(memory.availableBytes, memory.withFrameworkAvailable(1L).availableBytes);
        equal(null, DeviceParsers.memory("MemTotal: 2 kB").withFrameworkAvailable(3000L).availableBytes);
        equal(null, DeviceParsers.memory("MemTotal: 2 kB").withFrameworkAvailable(-1L).availableBytes);
        equal("pm path 'com.test'", DeviceCommands.packagePaths("com.test", 0));
        equal("pm path --user 10 'com.test'", DeviceCommands.packagePaths("com.test", 10));
        equal(null, DeviceParsers.memory("MemTotal: 999999999999999999999 kB").totalBytes);
        List<DeviceParsers.PackageInfo> packages = DeviceParsers.packages("package:/data/app/~~aaa==/base.apk=com.test.app uid:10232\npackage:android\nnoise\npackage:com.test.app uid:10232\npackage:bad;name\n");
        equal(2, packages.size()); equal("com.test.app", packages.get(0).packageName); equal(10232, packages.get(0).uid);
        equal("/data/app/~~aaa==/base.apk", packages.get(0).apkPath);
        List<DeviceParsers.PackageInfo> inventory = DeviceParsers.packageInventory("package:android\npackage:com.test", "package:android", "package:com.test");
        equal(Boolean.TRUE, inventory.get(0).systemApp); equal(Boolean.TRUE, inventory.get(0).enabled);
        equal(Boolean.FALSE, inventory.get(1).systemApp); equal(Boolean.FALSE, inventory.get(1).enabled);
        equal(null, DeviceParsers.packageInventory("package:android", null, null).get(0).enabled);
        DeviceParsers.CpuInfo cpu = DeviceParsers.cpu("processor : 0\nmodel name : Example CPU\nflags : a b c\nprocessor : 1\n");
        equal(2, cpu.logicalProcessorCount); equal("Example CPU", cpu.model); equal("a b c", cpu.features);
        equal(null, DeviceParsers.cpu("").logicalProcessorCount);
        equal("/data/app/~~a==/base.apk", DeviceParsers.packages("package:/data/app/~~a==/base.apk=com.test").get(0).apkPath);
        equal(Arrays.asList("/data/app/base.apk", "/data/app/split.apk"), DeviceParsers.packagePaths("package:/data/app/base.apk\npackage:/data/app/split.apk\nError: no package"));
        DeviceParsers.PackageDetails details = DeviceParsers.packageDetails("com.test", "  versionCode=9223372036 minSdk=23 targetSdk=35\n versionName=2.1 beta\n codePath=/data/app/test\n pkgFlags=[ SYSTEM HAS_CODE ]\n firstInstallTime=2026-10-12 10:11:12\n");
        equal(9223372036L, details.versionCode); equal(23, details.minSdk); equal(35, details.targetSdk); equal("2.1 beta", details.versionName);
        equal(Boolean.TRUE, details.systemApp); equal("2026-10-12 10:11:12", details.firstInstallTime); equal(null, details.lastUpdateTime);
        equal("com.test/.MainActivity", DeviceParsers.launcherComponent("priority=0 preferredOrder=0\ncom.test/.MainActivity\n"));
        equal(null, DeviceParsers.launcherComponent("No activity found"));
        equal(1234, DeviceParsers.installSession("Success: created install session [1234]\n"));
        equal(null, DeviceParsers.installSession("Failure [1234]"));
        List<DeviceParsers.ProcessInfo> processes = DeviceParsers.processes("USER PID PPID VSZ RSS NAME\nu0_a5 812 123 40000 12000 com.test.app:worker\nroot 4 2 0 0 kworker/0:1\ninvalid row\n");
        equal(2, processes.size()); equal(812, processes.get(0).pid); equal("com.test.app:worker", processes.get(0).name);
        equal(12288000L, processes.get(0).residentBytes); equal(40960000L, processes.get(0).virtualBytes);
        equal("a process with args", DeviceParsers.processes("UID PID NAME\n2000 5 a process with args").get(0).name);
        equal("com.old.app", DeviceParsers.processes("USER PID PPID VSIZE RSS WCHAN ADDR S NAME\nu0_a1 456 1 8000 4000 ffffffff 00000000 S com.old.app").get(0).name);
        equal("com.old.app:worker", DeviceParsers.processes("USER PID PPID VSIZE RSS WCHAN PC NAME\nu0_a1 456 1 8000 4000 ffffffff 00000000 S com.old.app:worker").get(0).name);
        equal("R ordinary text", DeviceParsers.processes("USER PID PPID VSZ RSS NAME\nshell 50 1 8000 4000 R ordinary text").get(0).name);
        rejects(() -> DeviceParsers.processes("ps: unknown option -A"));
        DeviceParsers.DisplayInfo display = DeviceParsers.display("Physical size: 1080x2400\nOverride size: 720x1600\n", "Physical density: 420\nOverride density: 280\n");
        equal(1080, display.physicalWidth); equal(720, display.effectiveWidth()); equal(1600, display.effectiveHeight()); equal(280, display.effectiveDensity());
        equal(null, DeviceParsers.display("", "").effectiveDensity());
        equal(null, DeviceParsers.display("Physical size: 0x2", "").physicalWidth);
        List<DeviceParsers.StorageInfo> storage = DeviceParsers.storage("Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/block/dm-1 1000 200 800 20% /data\n/dev/long\n 2000 1000 1000 50% /storage/My Drive\n");
        equal(2, storage.size()); equal(1024000L, storage.get(0).totalBytes); equal("/storage/My Drive", storage.get(1).mountPoint); equal(50, storage.get(1).percentUsed);
        check(!storage.get(0).approximate, "POSIX block totals are not rounded human quantities");
        check(!storage.get(0).availableIncludesReserved, "POSIX Available excludes reserved blocks");
        equal(null, storage.get(0).reportedTotal);
        storage = DeviceParsers.storage("Filesystem Size Used Free Blksize\n/data 1.9G 76.6M 1.9G 4096\n");
        equal(1, storage.size()); equal("/data", storage.get(0).mountPoint); equal("/data", storage.get(0).filesystem);
        check(storage.get(0).approximate, "legacy byte counts must be marked estimates");
        check(storage.get(0).availableIncludesReserved, "legacy Free may include reserved blocks");
        equal("1.9G", storage.get(0).reportedTotal); equal("76.6M", storage.get(0).reportedUsed); equal("1.9G", storage.get(0).reportedAvailable);
        equal(null, storage.get(0).percentUsed); // Rounded totals must never invent a precise percentage.
        equal(2040109465L, storage.get(0).totalBytes); equal(80320921L, storage.get(0).usedBytes);
        storage = DeviceParsers.storage("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/data 1000 200 700 22% /data\n");
        equal(1, storage.size()); equal(1024000L, storage.get(0).totalBytes); equal(716800L, storage.get(0).availableBytes);
        equal(51200L, DeviceParsers.storage("Filesystem 512-blocks Used Available Capacity Mounted on\n/dev/data 100 20 70 22% /data").get(0).totalBytes);
        equal(0, DeviceParsers.storage("Filesystem unknown-blocks Used Available Capacity Mounted on\n/dev/data 100 20 70 22% /data").size());
        equal(0, DeviceParsers.storage("Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/data 9223372036854775807 20 70 22% /data").size());
        equal(0, DeviceParsers.storage("Filesystem Size Used Free Blksize\n/data NaN 1M 2M 4096\n/data 999999999T 1M 2M 4096\n/data -1G 1M 2M 4096\n/data 2G 1M 2M 0").size());
        storage = DeviceParsers.storage("Filesystem Size Used Free Blksize\n-kP: No such file or directory\n/data/a b 500B 1K 1.2M 4096");
        equal(1, storage.size()); equal("/data/a b", storage.get(0).mountPoint); equal(500L, storage.get(0).totalBytes); equal(1024L, storage.get(0).usedBytes);
        List<DeviceParsers.NetworkAddress> network = DeviceParsers.network("1: lo inet 127.0.0.1/8 scope host lo\n2: wlan0 inet 192.168.1.2/24 brd 192.168.1.255 scope global wlan0\n2: wlan0 inet6 fe80::abcd/64 scope link\n3: rmnet0 inet 10.0.0.1/999\n");
        equal(3, network.size()); equal("192.168.1.2", network.get(1).address); equal("inet6", network.get(2).family); equal("link", network.get(2).scope);
        DeviceParsers.FileStat stat = DeviceParsers.stat("81a4|1234|1600000000|1000|1000\n");
        check(stat.isRegularFile(), "regular file mode"); equal(1234L, stat.sizeBytes); equal(1600000000L, stat.modifiedEpochSeconds);
        check(DeviceParsers.stat("41ed|4096|1|0|0").isDirectory(), "directory mode");
        check(DeviceParsers.stat("a1ff|5|1|0|0").isSymbolicLink(), "link mode");
        rejects(() -> DeviceParsers.stat("-rw-r--r-- 1 user file"));
        rejects(() -> DeviceParsers.stat("81a4|-1|0|0|0"));
        equal(0.5, DeviceParsers.animationScale("0.5\n")); equal(null, DeviceParsers.animationScale("null")); equal(null, DeviceParsers.animationScale("NaN"));
    }
    private static void operations() throws Exception {
        Fake fake = new Fake(); DeviceOperations ops = new DeviceOperations("test-only fixture", fake);
        DeviceOperations.Operation deletion = ops.delete("/sdcard/test", true);
        equal(0, fake.calls.size());
        check(deletion.approval.describe().contains("test-only fixture"), "approval device");
        check(deletion.approval.describe().contains("/sdcard/test"), "approval target");
        check(deletion.approval.describe().contains("Permanently"), "approval consequence");
        check(ops.execute(deletion, false).isCancelled(), "cancel state"); equal(0, fake.calls.size());
        fake.add("", "Permission denied", 1);
        DeviceOperations.Result failure = ops.execute(deletion, true);
        check(!failure.isSuccess(), "failure preserved"); equal("Permission denied", failure.error); equal(1, fake.calls.size());
        rejectsState(() -> ops.execute(deletion, true)); equal(1, fake.calls.size());
        DeviceOperations other = new DeviceOperations("second", fake);
        rejects(() -> other.execute(ops.enable("com.test", 0), true)); equal(1, fake.calls.size());
        check(!new DeviceOperations.Result("Failure [DELETE_FAILED_INTERNAL_ERROR]", "", 0).isSuccess(), "pm semantic failure");
        check(!new DeviceOperations.Result("", "SecurityException: denied", 0).isSuccess(), "security failure");
        check(new DeviceOperations.Result("Success", "Warning: device-specific warning", 0).isSuccess(), "warning is not failure");
        check(!new DeviceOperations.Result("", "", -1).isSuccess(), "unknown exit is not success");
        fake.add("package:android\npackage:com.test", "", 0).add("package:android", "", 0).add("package:com.test", "", 0);
        DeviceOperations.Query<List<DeviceParsers.PackageInfo>> inventory = ops.readPackageInventory(0);
        check(inventory.isSuccess(), "inventory three reads"); equal(Boolean.FALSE, inventory.value.get(1).enabled);
        fake.add("Error: Unknown option -U", "", 1).add("package:com.old", "", 0);
        equal("com.old", ops.readPackages(DeviceCommands.PackageFilter.ALL, 0).value.get(0).packageName);
        check(fake.calls.get(fake.calls.size() - 1).startsWith("pm list packages -f --user"), "legacy list fallback omits UID flag");
        fake.add("[ro.product.model]: [Fixture]\n", "", 0);
        equal("Fixture", ops.readDeviceInfo().value.model);
        fake.add("", "disconnected", 1);
        DeviceOperations.Query<DeviceParsers.BatteryInfo> battery = ops.readBattery();
        check(!battery.isSuccess(), "read failure state"); equal(null, battery.value);
        fake.add("", "", 0);
        check(!ops.readDeviceInfo().isSuccess(), "empty properties not fake success");
        fake.add("invalid format", "", 0);
        DeviceOperations.Query<DeviceParsers.FileStat> stat = ops.readFileStat("/fixture");
        check(!stat.isSuccess(), "parse failure state"); check(stat.parseError != null, "parse error explained");
        fake.add("ps: unknown option -A", "", 1).add("USER PID NAME\nshell 12 sh", "", 0);
        equal(12, ops.readProcesses().value.get(0).pid);
        fake.add("USER PID PPID VSIZE RSS WCHAN PC NAME\n", "", 0).add("USER PID NAME\nshell 15 sh", "", 0);
        equal(15, ops.readProcesses().value.get(0).pid);
        equal(DeviceCommands.processesLegacy(), fake.calls.get(fake.calls.size() - 1));
        fake.add("Physical size: 1080x1920", "", 0).add("", "density permission denied", 1);
        DeviceOperations.Query<DeviceParsers.DisplayInfo> display = ops.readDisplay();
        check(!display.isSuccess(), "partial display failure retained"); equal(1080, display.value.physicalWidth); equal(null, display.value.physicalDensity);
        fake.add("/target with space \n", "", 0);
        equal("/target with space ", ops.readLink("/link").value);
        fake.add("restarting in TCP mode port: 5555", "", 0);
        DeviceOperations.Operation wireless = ops.enableWireless(5555);
        equal(DeviceOperations.Kind.ADB_SERVICE, wireless.kind); check(ops.execute(wireless, true).isSuccess(), "service response");
        equal("service:tcpip:5555", fake.calls.get(fake.calls.size() - 1));
        DeviceOperations.Operation shizuku = ops.activateShizuku(); equal(OperationApproval.Risk.SECURITY, shizuku.approval.risk);
        check(shizuku.approval.consequence.contains("cannot prove activation"), "activation is not fabricated success");
        rejects(() -> ops.terminal("\0"));
        DeviceOperations.Operation custom = ops.terminal("echo hello; id"); equal("echo hello; id", custom.command);
    }
    private static void splitInstalls() throws Exception {
        Fake fake = new Fake(); DeviceOperations ops = new DeviceOperations("fixture", fake);
        DeviceOperations.Operation plan = ops.installSplits(Arrays.asList("/data/local/tmp/base.apk", "/data/local/tmp/config arm.apk"), 0, true);
        equal(0, fake.calls.size()); check(ops.execute(plan, false).isCancelled(), "cancel split install"); equal(0, fake.calls.size());
        fake.add("Success: created install session [42]", "", 0).add("Success: streamed 123 bytes", "", 0)
                .add("Success: streamed 456 bytes", "", 0).add("Success", "", 0);
        check(ops.execute(plan, true).isSuccess(), "split install success"); equal(4, fake.calls.size());
        equal("pm install-write 42 'split_1.apk' '/data/local/tmp/config arm.apk'", fake.calls.get(2));
        equal("pm install-commit 42", fake.calls.get(3));
        Fake failed = new Fake(); DeviceOperations fails = new DeviceOperations("fixture", failed);
        failed.add("Success: created install session [8]", "", 0).add("Failure [INSTALL_FAILED_INVALID_APK]", "", 1).add("Success", "", 0);
        DeviceOperations.Result result = fails.execute(fails.installSplits(Arrays.asList("/bad.apk", "/unused.apk"), 0, false), true);
        check(!result.isSuccess(), "split write failure retained"); equal(3, failed.calls.size()); equal("pm install-abandon 8", failed.calls.get(2));
        check(!failed.calls.contains("pm install-commit 8"), "failed split never commits");
        Fake badSession = new Fake(); DeviceOperations unknown = new DeviceOperations("fixture", badSession);
        badSession.add("unrecognized session response", "", 0);
        result = unknown.execute(unknown.installSplits(Arrays.asList("/base.apk"), 0, false), true);
        check(!result.isSuccess(), "unrecognized session not success"); equal(1, badSession.calls.size());
        Fake badCommit = new Fake(); DeviceOperations commits = new DeviceOperations("fixture", badCommit);
        badCommit.add("Success: created install session [7]", "", 0).add("Success", "", 0)
                .add("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", "", 0).add("Success", "", 0);
        result = commits.execute(commits.installSplits(Arrays.asList("/base.apk"), 0, true), true);
        check(!result.isSuccess(), "zero-exit commit error retained"); equal("pm install-abandon 7", badCommit.calls.get(3));
        rejects(() -> ops.installSplits(new ArrayList<String>(), 0, false));
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        assertions++;
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void rejectsState(Checked action) throws Exception {
        assertions++;
        try { action.run(); throw new AssertionError("Expected IllegalStateException"); }
        catch (IllegalStateException expected) { }
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
    }
    private static final class Fake implements DeviceOperations.ShellExecutor {
        final List<String> calls = new ArrayList<String>();
        final Queue<DeviceOperations.Result> results = new LinkedList<DeviceOperations.Result>();
        Fake add(String output, String error, int code) { results.add(new DeviceOperations.Result(output, error, code)); return this; }
        @Override public DeviceOperations.Result execute(String command) throws IOException { return take(command); }
        @Override public DeviceOperations.Result executeAdbService(String service) throws IOException { return take("service:" + service); }
        private DeviceOperations.Result take(String command) throws IOException {
            calls.add(command);
            if (results.isEmpty()) throw new IOException("Missing fixture for command: " + command);
            return results.remove();
        }
    }
}
