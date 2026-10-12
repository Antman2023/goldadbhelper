package io.github.devicebridge.core;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Independent builders for standard Android shell commands. No command is executed here. */
public final class DeviceCommands {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern CLASS = Pattern.compile("\\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    public enum PackageFilter { ALL, SYSTEM, THIRD_PARTY, ENABLED, DISABLED, INCLUDING_UNINSTALLED }
    public enum RebootMode { NORMAL, RECOVERY, BOOTLOADER }
    public enum NavigationKey {
        HOME(3), BACK(4), APP_SWITCH(187), MENU(82), POWER(26), WAKE(224), SLEEP(223),
        VOLUME_UP(24), VOLUME_DOWN(25), ENTER(66), DELETE(67);
        public final int code;
        NavigationKey(int code) { this.code = code; }
    }
    public enum Animation {
        WINDOW("window_animation_scale"), TRANSITION("transition_animation_scale"),
        DURATION("animator_duration_scale");
        public final String setting;
        Animation(String setting) { this.setting = setting; }
    }
    public enum FreezerMode { DEFAULT("device_default"), ENABLED("enabled"), DISABLED("disabled");
        public final String value;
        FreezerMode(String value) { this.value = value; }
    }
    private DeviceCommands() { }

    /** POSIX shell quoting. Newlines remain literal data; NUL cannot be a shell argument. */
    public static String quote(String value) {
        if (value == null || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Shell argument is null or contains NUL");
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    public static String packageName(String value) {
        if (value == null || value.length() > 255 || !PACKAGE.matcher(value).matches())
            throw new IllegalArgumentException("Invalid Android package identifier");
        return value;
    }
    public static String remotePath(String value) {
        if (value == null || !value.startsWith("/") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Remote path must be absolute and contain no NUL");
        return value;
    }
    public static String childPath(String directory, String name) {
        remotePath(directory);
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Name must be a single nonempty path component");
        return (directory.endsWith("/") ? directory : directory + "/") + name;
    }
    public static int userId(int userId) {
        if (userId < 0) throw new IllegalArgumentException("User ID must be nonnegative");
        return userId;
    }
    public static int port(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1–65535");
        return port;
    }
    private static String p(String path) { return quote(remotePath(path)); }
    private static String pkg(String name) { return quote(packageName(name)); }
    private static String absentGuard(String destination, String command) {
        String q = p(destination);
        // Use only a fixed literal with echo: old Android toolbox lacks printf, and echo
        // must not interpret path data as options or escape sequences.
        return "(if [ -e " + q + " ] || [ -L " + q + " ]; then echo 'Destination already exists'"
                + " >&2; exit 1; fi; " + command + ")";
    }
    // Validated absolute paths cannot be parsed as flags; old toolbox does not support --.
    public static String mkdir(String path) { return "mkdir " + p(path); }
    /** Does not truncate an existing file; explicitly refuses an already-present path. */
    public static String touch(String path) { return absentGuard(path, "touch -- " + p(path)); }
    /** Destination is the complete new path, not a container directory. */
    public static String move(String source, String destination) {
        return absentGuard(destination, "mv -n -- " + p(source) + " " + p(destination));
    }
    public static String copy(String source, String destination, boolean recursive) {
        return absentGuard(destination, "cp " + (recursive ? "-R " : "") + "-n -- " + p(source) + " " + p(destination));
    }
    public static String delete(String path, boolean recursive) {
        remotePath(path);
        boolean component = false;
        for (String part : path.split("/")) {
            if (part.equals("..")) throw new IllegalArgumentException("Deletion paths cannot contain '..'");
            if (!part.isEmpty() && !part.equals(".")) component = true;
        }
        if (!component) throw new IllegalArgumentException("Refusing to delete the device root");
        // The operand is validated absolute; removing unsupported -- does not turn it into an option.
        return "rm " + (recursive ? "-r " : "") + p(path);
    }
    /** Machine-readable numeric fields, independent of spaces/newlines in the name. */
    public static String stat(String path) { return "stat -c '%f|%s|%Y|%u|%g' -- " + p(path); }
    public static String readlink(String path) { return "readlink " + p(path); }
    /** Human-readable fallback only. Prefer ADB SYNC LIST for lossless filenames. */
    public static String listDirectory(String path, boolean hidden) {
        return "ls " + (hidden ? "-la" : "-l") + " -- " + p(path);
    }
    public static String listPackages(PackageFilter filter, int userId) {
        return listPackages(filter, userId, true);
    }
    /** Older Android package managers may not support the -U UID flag. */
    public static String listPackagesLegacy(PackageFilter filter, int userId) {
        return listPackages(filter, userId, false);
    }
    private static String listPackages(PackageFilter filter, int userId, boolean uid) {
        if (filter == null) throw new IllegalArgumentException("Package filter is required");
        String flag = "";
        switch (filter) {
            case SYSTEM: flag = " -s"; break;
            case THIRD_PARTY: flag = " -3"; break;
            case ENABLED: flag = " -e"; break;
            case DISABLED: flag = " -d"; break;
            case INCLUDING_UNINSTALLED: flag = " -u"; break;
            default: break;
        }
        return "pm list packages -f" + (uid ? " -U" : "") + flag + " --user " + userId(userId);
    }
    public static String packageDetails(String name) { return "dumpsys package " + pkg(name); }
    public static String packagePaths(String name, int userId) {
        return "pm path " + (userId(userId) == 0 ? "" : "--user " + userId + " ") + pkg(name);
    }
    public static String resolveLauncher(String name, int userId) {
        return "cmd package resolve-activity --brief --user " + userId(userId)
                + " -a 'android.intent.action.MAIN' -c 'android.intent.category.LAUNCHER' " + pkg(name);
    }
    public static String startComponent(String component, int userId) {
        if (component == null || component.indexOf('/') <= 0 || component.indexOf('/') != component.lastIndexOf('/'))
            throw new IllegalArgumentException("Activity must be package/class");
        int slash = component.indexOf('/');
        packageName(component.substring(0, slash));
        if (!CLASS.matcher(component.substring(slash + 1)).matches())
            throw new IllegalArgumentException("Invalid activity class");
        return "am start --user " + userId(userId) + " -n " + quote(component);
    }
    public static String forceStop(String name, int userId) { return "am force-stop --user " + userId(userId) + " " + pkg(name); }
    public static String enable(String name, int userId) { return "pm enable --user " + userId(userId) + " " + pkg(name); }
    public static String disable(String name, int userId) { return "pm disable-user --user " + userId(userId) + " " + pkg(name); }
    public static String clearData(String name, int userId) { return "pm clear --user " + userId(userId) + " " + pkg(name); }
    public static String uninstall(String name, int userId, boolean keepData) {
        return "pm uninstall " + (keepData ? "-k " : "") + "--user " + userId(userId) + " " + pkg(name);
    }
    /** KitKat has no --user uninstall; callers must explicitly confirm ALL users first. */
    static String uninstallAllUsersLegacy(String name, boolean keepData) {
        return "pm uninstall " + (keepData ? "-k " : "") + pkg(name);
    }
    /** KitKat has no --user install; callers must explicitly confirm ALL users first. */
    static String installSingleAllUsersLegacy(String apkPath, boolean replace) {
        return "pm install " + (replace ? "-r " : "") + p(apkPath);
    }
    public static String listSystemPackagesIncludingUninstalled(int userId) {
        return "pm list packages -s -u --user " + userId(userId);
    }
    public static String restorePackage(String name, int userId) {
        return "cmd package install-existing --user " + userId(userId) + " " + pkg(name);
    }
    public static String installSingle(String apkPath, int userId, boolean replace) {
        return "pm install " + (replace ? "-r " : "") + "--user " + userId(userId) + " " + p(apkPath);
    }
    public static String installCreate(int userId, boolean replace) {
        return "pm install-create " + (replace ? "-r " : "") + "--user " + userId(userId);
    }
    public static String installWrite(int session, String splitName, String apkPath) {
        if (splitName == null || !splitName.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") || splitName.length() > 255)
            throw new IllegalArgumentException("Invalid split name");
        return "pm install-write " + session(session) + " " + quote(splitName) + " " + p(apkPath);
    }
    public static String installCommit(int session) { return "pm install-commit " + session(session); }
    public static String installAbandon(int session) { return "pm install-abandon " + session(session); }
    private static int session(int session) {
        if (session < 0) throw new IllegalArgumentException("Invalid install session");
        return session;
    }
    public static String processes() { return "ps -A -o USER,PID,PPID,VSZ,RSS,NAME"; }
    public static String processesLegacy() { return "ps"; }
    public static String killProcess(int pid, boolean force) {
        if (pid <= 1) throw new IllegalArgumentException("PID must be greater than 1");
        return "kill " + (force ? "-9 " : "-15 ") + pid;
    }
    public static String properties() { return "getprop"; }
    public static String property(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid property name");
        return "getprop " + quote(name);
    }
    public static String battery() { return "dumpsys battery"; }
    public static String memory() { return "cat '/proc/meminfo'"; }
    public static String cpu() { return "cat '/proc/cpuinfo'"; }
    public static String network() { return "ip -o addr show"; }
    public static String wifi() { return "cmd wifi status"; }
    // KitKat toolbox treats -kP as a path, sometimes without a failing exit status.
    // Query the actual user-data filesystem without flags; the parser reads the units.
    public static String storage() { return "df"; }
    public static String displaySize() { return "wm size"; }
    public static String displayDensity() { return "wm density"; }
    public static String setDisplaySize(int width, int height) {
        bounded(width, 200, 16384, "Width"); bounded(height, 200, 16384, "Height");
        return "wm size " + quote(width + "x" + height);
    }
    public static String resetDisplaySize() { return "wm size reset"; }
    public static String setDisplayDensity(int density) { return "wm density " + bounded(density, 72, 1280, "Density"); }
    public static String resetDisplayDensity() { return "wm density reset"; }
    public static String animation(Animation animation) { return "settings get global " + quote(require(animation).setting); }
    public static String setAnimation(Animation animation, double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0 || scale > 10)
            throw new IllegalArgumentException("Animation scale must be 0–10");
        return "settings put global " + quote(require(animation).setting) + " "
                + quote(BigDecimal.valueOf(scale).stripTrailingZeros().toPlainString());
    }
    private static Animation require(Animation value) {
        if (value == null) throw new IllegalArgumentException("Animation type is required");
        return value;
    }
    public static String freezer() { return "settings get global 'cached_apps_freezer'"; }
    public static String setFreezer(FreezerMode mode) {
        if (mode == null) throw new IllegalArgumentException("Freezer mode is required");
        return "settings put global 'cached_apps_freezer' " + quote(mode.value);
    }
    /** These are ADB service destinations, NEVER shell commands. */
    public static String wirelessService(int port) { return "tcpip:" + port(port); }
    public static String usbService() { return "usb:"; }
    public static String rebootService(RebootMode mode) {
        if (mode == null) throw new IllegalArgumentException("Reboot mode is required");
        switch (mode) {
            case RECOVERY: return "reboot:recovery";
            case BOOTLOADER: return "reboot:bootloader";
            default: return "reboot:";
        }
    }
    public static String key(NavigationKey key) {
        if (key == null) throw new IllegalArgumentException("Navigation key is required");
        return "input keyevent " + key.code;
    }
    public static String tap(int x, int y) { return "input tap " + coordinate(x) + " " + coordinate(y); }
    public static String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return "input swipe " + coordinate(x1) + " " + coordinate(y1) + " " + coordinate(x2) + " "
                + coordinate(y2) + " " + bounded(durationMs, 1, 60000, "Duration");
    }
    /** Android input text supports printable ASCII; Unicode requires a separate IME/control protocol. */
    public static String inputText(String text) {
        if (text == null || text.isEmpty() || text.length() > 4096)
            throw new IllegalArgumentException("Text must contain 1–4096 printable ASCII characters");
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < 32 || text.charAt(i) > 126)
                throw new IllegalArgumentException("input text cannot reliably type Unicode or control characters");
        // Android replaces %s inside one argument with a space. Split literal occurrences to preserve them.
        String[] pieces = text.split("(?<=%)(?=s)", -1);
        StringBuilder command = new StringBuilder();
        for (String piece : pieces) {
            if (command.length() > 0) command.append(" && ");
            command.append("input text ").append(quote(piece.replace(" ", "%s")));
        }
        return command.toString();
    }
    public static String screenshot(String path) { return "screencap -p " + p(path); }
    /** Verified upstream user guide: https://shizuku.rikka.app/guide/setup/ (2026-10-12).
     * Version/device dependent. Running a script does not prove Shizuku is active. */
    public static String activateShizuku() {
        return "sh '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh'";
    }
    private static int coordinate(int value) { return bounded(value, 0, 65535, "Coordinate"); }
    private static int bounded(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be " + min + "–" + max);
        return value;
    }
}
