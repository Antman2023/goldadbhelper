package io.github.devicebridge.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Device-bound domain facade. Construction and operation planning never contact a device.
 * Callers must run I/O off the UI thread, present approvals, and display the actual result. */
public final class DeviceOperations {
    public interface ShellExecutor {
        Result execute(String shellCommand) throws IOException;
        /** Bridge to AdbSession.open(service), not shell:service. Override only if supported. */
        default Result executeAdbService(String service) throws IOException {
            throw new IOException("ADB service execution is not supported by this bridge: " + service);
        }
    }
    public static final class Result {
        public static final int UNKNOWN_EXIT = -1;
        public static final int CANCELLED = -2;
        public final String output, error;
        public final int exitCode;
        private static final Pattern FAILURE = Pattern.compile(
                "(?im)^\\s*(?:error(?::|\\s)|failure(?:\\s|\\[|:|$)|exception(?:\\s|:|$)|"
                + "securityexception(?::|\\s)|java\\.[\\w.]*exception(?::|\\s)|"
                + "unknown command(?::|\\s)|unable to find package(?:\\s|:))");
        public Result(String output, String error, int exitCode) {
            this.output = output == null ? "" : output;
            this.error = error == null ? "" : error;
            this.exitCode = exitCode;
        }
        /** Means the command reported success, not that its UI/device effect has been verified. */
        public boolean isSuccess() {
            return exitCode == 0 && !FAILURE.matcher(output).find() && !FAILURE.matcher(error).find();
        }
        public boolean isCancelled() { return exitCode == CANCELLED; }
        public String displayText() {
            return output + (error.isEmpty() ? "" : (output.endsWith("\n") || output.isEmpty() ? "" : "\n") + error)
                    + "\nExit code: " + exitCode;
        }
    }
    public static final class Query<T> {
        public final Result result;
        public final T value;
        public final String parseError;
        private Query(Result result, T value, String parseError) { this.result = result; this.value = value; this.parseError = parseError; }
        public boolean isSuccess() { return result.isSuccess() && parseError == null && value != null; }
    }
    public enum Kind { SHELL, ADB_SERVICE, SPLIT_INSTALL }
    public static final class Operation {
        public final Kind kind;
        /** Shell command, ADB service destination, or a human-readable split-install preview. */
        public final String command;
        public final OperationApproval approval;
        private final DeviceOperations owner;
        private final List<String> apks;
        private final int userId;
        private final boolean replace;
        private boolean dispatched;
        private Operation(DeviceOperations owner, Kind kind, String command, OperationApproval approval,
                          List<String> apks, int userId, boolean replace) {
            this.owner = owner; this.kind = kind; this.command = command; this.approval = approval;
            this.apks = apks; this.userId = userId; this.replace = replace;
        }
    }
    private interface Parser<T> { T parse(String output); }
    private final String deviceTarget;
    private final ShellExecutor executor;
    public DeviceOperations(String deviceTarget, ShellExecutor executor) {
        if (deviceTarget == null || deviceTarget.trim().isEmpty() || executor == null)
            throw new IllegalArgumentException("A device target and executor are required");
        this.deviceTarget = deviceTarget; this.executor = executor;
    }
    public String deviceTarget() { return deviceTarget; }
    private Result shell(String command) throws IOException {
        Result result = executor.execute(command);
        if (result == null) throw new IOException("Shell bridge returned no result");
        return result;
    }
    private <T> Query<T> read(String command, Parser<T> parser) throws IOException {
        Result result = shell(command);
        if (!result.isSuccess()) return new Query<T>(result, null, null);
        try {
            T value = parser.parse(result.output);
            return new Query<T>(result, value, value == null ? "Requested value was not present in device output" : null);
        } catch (IllegalArgumentException malformed) {
            return new Query<T>(result, null, malformed.getMessage());
        }
    }
    public Query<DeviceParsers.DeviceInfo> readDeviceInfo() throws IOException {
        return read(DeviceCommands.properties(), output -> {
            DeviceParsers.DeviceInfo info = DeviceParsers.deviceInfo(output);
            return info.properties.isEmpty() ? null : info;
        });
    }
    public Query<DeviceParsers.BatteryInfo> readBattery() throws IOException {
        return read(DeviceCommands.battery(), output -> {
            DeviceParsers.BatteryInfo info = DeviceParsers.battery(output);
            return info.raw.isEmpty() ? null : info;
        });
    }
    public Query<DeviceParsers.MemoryInfo> readMemory() throws IOException {
        return read(DeviceCommands.memory(), output -> {
            DeviceParsers.MemoryInfo info = DeviceParsers.memory(output);
            return info.fields.isEmpty() ? null : info;
        });
    }
    public Query<DeviceParsers.CpuInfo> readCpu() throws IOException {
        return read(DeviceCommands.cpu(), output -> {
            DeviceParsers.CpuInfo info = DeviceParsers.cpu(output);
            return info.fields.isEmpty() ? null : info;
        });
    }
    public Query<List<DeviceParsers.PackageInfo>> readPackages(DeviceCommands.PackageFilter filter, int userId) throws IOException {
        Query<List<DeviceParsers.PackageInfo>> modern = read(DeviceCommands.listPackages(filter, userId), DeviceParsers::packages);
        if (modern.isSuccess()) return modern;
        return read(DeviceCommands.listPackagesLegacy(filter, userId), DeviceParsers::packages);
    }
    /** Reads both independent filter dimensions. A failed dimension remains null in the items;
     * Query.result still exposes the failure, so the UI can label a partial inventory. */
    public Query<List<DeviceParsers.PackageInfo>> readPackageInventory(int userId) throws IOException {
        Query<List<DeviceParsers.PackageInfo>> all = readPackages(DeviceCommands.PackageFilter.ALL, userId);
        if (!all.isSuccess()) return all;
        Query<List<DeviceParsers.PackageInfo>> system = readPackages(DeviceCommands.PackageFilter.SYSTEM, userId);
        Query<List<DeviceParsers.PackageInfo>> disabled = readPackages(DeviceCommands.PackageFilter.DISABLED, userId);
        List<DeviceParsers.PackageInfo> items = DeviceParsers.packageInventory(all.result.output,
                system.isSuccess() ? system.result.output : null, disabled.isSuccess() ? disabled.result.output : null);
        return new Query<List<DeviceParsers.PackageInfo>>(combine(combine(all.result, system.result), disabled.result), items,
                system.parseError != null ? system.parseError : disabled.parseError);
    }
    public Query<DeviceParsers.PackageDetails> readPackageDetails(final String name) throws IOException {
        return read(DeviceCommands.packageDetails(name), output -> {
            DeviceParsers.PackageDetails details = DeviceParsers.packageDetails(name, output);
            return details.versionCode == null && details.codePath == null ? null : details;
        });
    }
    public Query<List<String>> readPackagePaths(String name, int userId) throws IOException {
        return read(DeviceCommands.packagePaths(name, userId), DeviceParsers::packagePaths);
    }
    public Query<String> resolveLauncher(String name, int userId) throws IOException {
        return read(DeviceCommands.resolveLauncher(name, userId), DeviceParsers::launcherComponent);
    }
    public Query<List<DeviceParsers.ProcessInfo>> readProcesses() throws IOException {
        Query<List<DeviceParsers.ProcessInfo>> modern = read(DeviceCommands.processes(), DeviceParsers::processes);
        if (modern.isSuccess() && !modern.value.isEmpty()) return modern;
        // Older Android/toolbox can return only a header with exit 0 for unsupported -A/-o.
        // An active device necessarily has processes; an empty parse needs the read-only fallback.
        return read(DeviceCommands.processesLegacy(), DeviceParsers::processes);
    }
    public Query<List<DeviceParsers.StorageInfo>> readStorage() throws IOException {
        return read(DeviceCommands.storage(), DeviceParsers::storage);
    }
    public Query<List<DeviceParsers.NetworkAddress>> readNetwork() throws IOException {
        return read(DeviceCommands.network(), DeviceParsers::network);
    }
    public Query<DeviceParsers.DisplayInfo> readDisplay() throws IOException {
        Result size = shell(DeviceCommands.displaySize()), density = shell(DeviceCommands.displayDensity());
        Result combined = combine(size, density);
        DeviceParsers.DisplayInfo info = DeviceParsers.display(size.isSuccess() ? size.output : "", density.isSuccess() ? density.output : "");
        return new Query<DeviceParsers.DisplayInfo>(combined, info,
                info.effectiveWidth() == null && info.effectiveDensity() == null ? "No display information returned" : null);
    }
    public Query<Double> readAnimation(DeviceCommands.Animation type) throws IOException {
        return read(DeviceCommands.animation(type), DeviceParsers::animationScale);
    }
    public Query<String> readProperty(String key) throws IOException {
        return read(DeviceCommands.property(key), output -> output.trim().isEmpty() ? null : output.trim());
    }
    public Query<DeviceParsers.FileStat> readFileStat(String path) throws IOException {
        return read(DeviceCommands.stat(path), DeviceParsers::stat);
    }
    public Query<String> readLink(String path) throws IOException {
        // Remove exactly the line terminator that readlink adds; preserve spaces and embedded newlines.
        return read(DeviceCommands.readlink(path), output -> output.endsWith("\r\n") ? output.substring(0, output.length() - 2)
                : output.endsWith("\n") ? output.substring(0, output.length() - 1) : output);
    }
    public Result listDirectoryText(String path, boolean hidden) throws IOException { return shell(DeviceCommands.listDirectory(path, hidden)); }
    public Result readCpuText() throws IOException { return shell(DeviceCommands.cpu()); }
    public Result readWifiText() throws IOException { return shell(DeviceCommands.wifi()); }
    public Result readFreezer() throws IOException { return shell(DeviceCommands.freezer()); }

    private Operation shellOperation(String command, String action, String target, String consequence, OperationApproval.Risk risk) {
        return operation(Kind.SHELL, command, action, target, consequence, risk);
    }
    private Operation operation(Kind kind, String command, String action, String target, String consequence, OperationApproval.Risk risk) {
        return new Operation(this, kind, command, new OperationApproval(deviceTarget, action, target, consequence, risk), null, 0, false);
    }
    private static String userTarget(String name, int user) { return name + " (Android user " + user + ")"; }
    public Operation mkdir(String path) {
        return shellOperation(DeviceCommands.mkdir(path), "Create directory", path, "Creates a directory on this device; existing paths are not replaced.", OperationApproval.Risk.CHANGE);
    }
    public Operation touch(String path) {
        return shellOperation(DeviceCommands.touch(path), "Create empty file", path, "Creates an empty file; an already-present path is refused.", OperationApproval.Risk.CHANGE);
    }
    public Operation move(String source, String destination) {
        return shellOperation(DeviceCommands.move(source, destination), "Move or rename", source + " → " + destination,
                "Moves the source to the new path. The source path disappears after success; existing destination paths are refused.", OperationApproval.Risk.CHANGE);
    }
    public Operation copy(String source, String destination, boolean recursive) {
        return shellOperation(DeviceCommands.copy(source, destination, recursive), "Copy " + (recursive ? "directory recursively" : "file"),
                source + " → " + destination, "Writes a copy at the destination and consumes storage. Existing destinations are refused.", OperationApproval.Risk.CHANGE);
    }
    public Operation delete(String path, boolean recursive) {
        return shellOperation(DeviceCommands.delete(path, recursive), "Delete " + (recursive ? "recursively" : "file"), path,
                "Permanently removes " + (recursive ? "this path and all its contents" : "this file or symbolic link") + ". There is no device trash or undo.", OperationApproval.Risk.DESTRUCTIVE);
    }
    public Operation startComponent(String component, int userId) {
        return shellOperation(DeviceCommands.startComponent(component, userId), "Launch activity", userTarget(component, userId),
                "Opens the activity on the target device and may switch the foreground application.", OperationApproval.Risk.CHANGE);
    }
    public Operation forceStop(String name, int userId) {
        return shellOperation(DeviceCommands.forceStop(name, userId), "Force-stop app", userTarget(name, userId),
                "Stops this app and its background work; unsaved work and notifications may be interrupted.", OperationApproval.Risk.CHANGE);
    }
    public Operation enable(String name, int userId) {
        return shellOperation(DeviceCommands.enable(name, userId), "Enable app", userTarget(name, userId),
                "Allows the app and its background components to run again.", OperationApproval.Risk.CHANGE);
    }
    public Operation disable(String name, int userId) {
        return shellOperation(DeviceCommands.disable(name, userId), "Disable app", userTarget(name, userId),
                "Prevents the app from running for this user. Disabling system components can break device features.", OperationApproval.Risk.CHANGE);
    }
    public Operation clearData(String name, int userId) {
        return shellOperation(DeviceCommands.clearData(name, userId), "Clear app data", userTarget(name, userId),
                "Permanently deletes the app's local data, accounts and settings for this user and stops the app. There is no undo.", OperationApproval.Risk.DESTRUCTIVE);
    }
    public Operation uninstall(String name, int userId, boolean keepData) {
        return shellOperation(DeviceCommands.uninstall(name, userId, keepData), "Uninstall app", userTarget(name, userId),
                keepData ? "Removes the app for this user while requesting retained data. Retention is device-dependent and is not a backup."
                        : "Removes the app and its local data for this user. System-app removal can break device features; deleted data has no undo.", OperationApproval.Risk.DESTRUCTIVE);
    }
    public Operation restorePackage(String name, int userId) {
        return shellOperation(DeviceCommands.restorePackage(name, userId), "Restore existing system package", userTarget(name, userId),
                "Registers a package already available on the device for this user. This cannot restore deleted app data or download a missing APK.", OperationApproval.Risk.CHANGE);
    }
    public Operation installSingle(String path, int userId, boolean replace) {
        return shellOperation(DeviceCommands.installSingle(path, userId, replace), "Install APK", userTarget(path, userId),
                "Installs software from this staged device path" + (replace ? " and may replace an existing app" : "")
                        + ". Only continue for an APK you trust; this does not automatically grant runtime permissions.", OperationApproval.Risk.SECURITY);
    }
    public Operation installSplits(List<String> remoteApks, int userId, boolean replace) {
        DeviceCommands.userId(userId);
        if (remoteApks == null || remoteApks.isEmpty()) throw new IllegalArgumentException("At least one staged APK is required");
        List<String> paths = new ArrayList<String>();
        for (String path : remoteApks) paths.add(DeviceCommands.remotePath(path));
        String preview = DeviceCommands.installCreate(userId, replace) + "; install-write each APK into the returned session; install-commit";
        return new Operation(this, Kind.SPLIT_INSTALL, preview,
                new OperationApproval(deviceTarget, "Install APK set", userTarget(paths.toString(), userId),
                        "Installs all selected APK splits in a single package-manager session" + (replace ? ", replacing an existing app when allowed" : "")
                                + ". Only continue for trusted APKs belonging to the same package/version. Failed staging is abandoned; no unrelated sessions are changed.",
                        OperationApproval.Risk.SECURITY), Collections.unmodifiableList(paths), userId, replace);
    }
    public Operation killProcess(int pid, boolean force) {
        return shellOperation(DeviceCommands.killProcess(pid, force), force ? "Force-kill process" : "Terminate process", "PID " + pid,
                "Sends " + (force ? "SIGKILL" : "SIGTERM") + " to the current process with this PID. Unsaved work may be lost; the system may restart it. PID identity must be refreshed before confirmation.", OperationApproval.Risk.DESTRUCTIVE);
    }
    public Operation setDisplaySize(int width, int height) {
        return shellOperation(DeviceCommands.setDisplaySize(width, height), "Change display resolution", width + "×" + height,
                "Changes the target's display layout and can make controls difficult to reach. Record the old value; wm size reset restores the physical default.", OperationApproval.Risk.CHANGE);
    }
    public Operation resetDisplaySize() {
        return shellOperation(DeviceCommands.resetDisplaySize(), "Reset display resolution", "Display size override",
                "Removes the custom size override and returns to the physical default.", OperationApproval.Risk.CHANGE);
    }
    public Operation setDisplayDensity(int density) {
        return shellOperation(DeviceCommands.setDisplayDensity(density), "Change display density", density + " DPI",
                "Changes UI scaling and can make controls difficult to reach. Record the old value; wm density reset restores the default.", OperationApproval.Risk.CHANGE);
    }
    public Operation resetDisplayDensity() {
        return shellOperation(DeviceCommands.resetDisplayDensity(), "Reset display density", "Display density override",
                "Removes the custom density override and returns to the physical default.", OperationApproval.Risk.CHANGE);
    }
    public Operation setAnimation(DeviceCommands.Animation animation, double scale) {
        String command = DeviceCommands.setAnimation(animation, scale);
        return shellOperation(command, "Change animation scale", animation.setting + " = " + scale,
                "Changes this system animation duration globally. Save the previous value before applying.", OperationApproval.Risk.CHANGE);
    }
    public Operation setFreezer(DeviceCommands.FreezerMode mode) {
        String command = DeviceCommands.setFreezer(mode);
        return shellOperation(command, "Change cached-app freezer", mode.value,
                "Changes cached background-app freezing when supported. Background behavior may change; some Android versions require a restart.", OperationApproval.Risk.CHANGE);
    }
    public Operation enableWireless(int port) {
        return operation(Kind.ADB_SERVICE, DeviceCommands.wirelessService(port), "Enable legacy wireless ADB", "TCP port " + port,
                "Restarts adbd in TCP mode and may disconnect this session. Legacy TCP ADB is unencrypted; use only a trusted network. This is not Android 11+ secure pairing.", OperationApproval.Risk.SECURITY);
    }
    public Operation disableWireless() {
        return operation(Kind.ADB_SERVICE, DeviceCommands.usbService(), "Return ADB to USB mode", "Device ADB transport",
                "Restarts adbd in USB mode and disconnects legacy wireless ADB sessions. It does not remove Android 11+ pairing records.", OperationApproval.Risk.DISCONNECT);
    }
    public Operation reboot(DeviceCommands.RebootMode mode) {
        return operation(Kind.ADB_SERVICE, DeviceCommands.rebootService(mode), "Reboot device", mode.name(),
                "Restarts this device, interrupts apps and disconnects ADB. Save work first. Recovery/bootloader availability depends on the device; this command does not flash or unlock it.", OperationApproval.Risk.DISCONNECT);
    }
    public Operation key(DeviceCommands.NavigationKey key) {
        return shellOperation(DeviceCommands.key(key), "Send navigation key", key.name(),
                "Sends this key to the target device and may change its foreground app, volume or screen state.", OperationApproval.Risk.CHANGE);
    }
    public Operation tap(int x, int y) {
        return shellOperation(DeviceCommands.tap(x, y), "Tap screen", "(" + x + ", " + y + ")",
                "Taps the current foreground screen. Verify the current screen and coordinate mapping before sending.", OperationApproval.Risk.CHANGE);
    }
    public Operation swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return shellOperation(DeviceCommands.swipe(x1, y1, x2, y2, durationMs), "Swipe screen",
                "(" + x1 + ", " + y1 + ") → (" + x2 + ", " + y2 + ") in " + durationMs + " ms",
                "Injects a swipe into the foreground screen. Verify the current screen and coordinates first.", OperationApproval.Risk.CHANGE);
    }
    public Operation inputText(String text) {
        return shellOperation(DeviceCommands.inputText(text), "Type text", "Current focused input (" + (text == null ? 0 : text.length()) + " characters)",
                "Types the supplied text into the focused target-device app. Do not type secrets or personal data into an unverified field.", OperationApproval.Risk.CHANGE);
    }
    public Operation screenshot(String path) {
        return shellOperation(DeviceCommands.screenshot(path), "Capture screen", path,
                "Writes the current screen to this device path, replacing an existing file. The image may include private on-screen content.", OperationApproval.Risk.CHANGE);
    }
    public Operation activateShizuku() {
        return shellOperation(DeviceCommands.activateShizuku(), "Start Shizuku", "moe.shizuku.privileged.api on " + deviceTarget,
                "Runs the installed Shizuku startup script with ADB shell privileges. Confirm that you trust the installed app. Check success in Shizuku; this command alone cannot prove activation.", OperationApproval.Risk.SECURITY);
    }
    /** Deliberately separate from generated commands. This is the user's explicit terminal input. */
    public Operation terminal(String command) {
        if (command == null || command.trim().isEmpty() || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("A nonempty terminal command without NUL is required");
        return shellOperation(command, "Run custom shell command", command,
                "Executes the exact command as Android shell on this device. It may change or erase files, settings or apps; inspect the complete command before allowing it.", OperationApproval.Risk.DESTRUCTIVE);
    }
    /** Approval is scoped to this exact immutable plan and this device-bound facade.
     * Each plan dispatches once, preventing duplicate taps/retries from silently repeating a mutation. */
    public Result execute(Operation operation, boolean approved) throws IOException {
        if (operation == null || operation.owner != this) throw new IllegalArgumentException("Operation belongs to a different device session");
        if (!approved) return new Result("", "Cancelled before execution; device was not changed.", Result.CANCELLED);
        synchronized (operation) {
            if (operation.dispatched) throw new IllegalStateException("Operation was already dispatched; inspect its result before creating a new plan");
            operation.dispatched = true;
        }
        if (operation.kind == Kind.SPLIT_INSTALL) return executeSplitInstall(operation);
        Result result = operation.kind == Kind.ADB_SERVICE ? executor.executeAdbService(operation.command) : shell(operation.command);
        if (result == null) throw new IOException("ADB service bridge returned no result");
        return result;
    }
    private Result executeSplitInstall(Operation operation) throws IOException {
        Result create = shell(DeviceCommands.installCreate(operation.userId, operation.replace));
        if (!create.isSuccess()) return create;
        Integer session = DeviceParsers.installSession(create.output);
        if (session == null) return new Result(create.output, join(create.error, "Cannot identify the new install session; no APK was written or committed."), Result.UNKNOWN_EXIT);
        Result accumulated = create;
        boolean committed = false;
        try {
            for (int index = 0; index < operation.apks.size(); index++) {
                Result written = shell(DeviceCommands.installWrite(session, "split_" + index + ".apk", operation.apks.get(index)));
                accumulated = combine(accumulated, written);
                if (!written.isSuccess()) return appendCleanup(accumulated, session);
            }
            Result commit = shell(DeviceCommands.installCommit(session));
            accumulated = combine(accumulated, commit);
            committed = commit.isSuccess();
            return committed ? accumulated : appendCleanup(accumulated, session);
        } catch (IOException failure) {
            // Releasing our own uncommitted session is part of this exact installation operation.
            if (!committed) {
                try { shell(DeviceCommands.installAbandon(session)); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    private Result appendCleanup(Result result, int session) {
        try {
            Result cleanup = shell(DeviceCommands.installAbandon(session));
            return new Result(join(result.output, "Session cleanup: " + cleanup.output),
                    join(result.error, cleanup.isSuccess() ? cleanup.error : "Install session cleanup failed: " + cleanup.displayText()), result.exitCode);
        } catch (IOException failure) {
            return new Result(result.output, join(result.error, "Install session cleanup could not be confirmed: " + failure.getMessage()), result.exitCode);
        }
    }
    private static Result combine(Result one, Result two) {
        return new Result(join(one.output, two.output), join(one.error, two.error), one.isSuccess() ? two.exitCode : one.exitCode);
    }
    private static String join(String one, String two) {
        return one.isEmpty() ? two : two.isEmpty() ? one : one + (one.endsWith("\n") ? "" : "\n") + two;
    }
}
