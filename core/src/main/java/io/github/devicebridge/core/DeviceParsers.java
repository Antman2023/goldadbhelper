package io.github.devicebridge.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tolerant, independent parsers. Missing device fields stay null rather than becoming fake values. */
public final class DeviceParsers {
    private DeviceParsers() { }
    private static String safe(String text) { return text == null ? "" : text; }
    private static String[] lines(String text) { return safe(text).split("\\r?\\n"); }
    public static Map<String, String> properties(String text) {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        Pattern pattern = Pattern.compile("^\\[([^]]+)\\]: \\[(.*)\\]$");
        for (String line : lines(text)) {
            Matcher matcher = pattern.matcher(line.trim());
            if (matcher.matches()) values.put(matcher.group(1), matcher.group(2));
        }
        return Collections.unmodifiableMap(values);
    }
    public static final class DeviceInfo {
        public final Map<String, String> properties;
        public final String manufacturer, model, product, androidVersion, securityPatch, fingerprint, serial, abis;
        public final Integer apiLevel;
        private DeviceInfo(Map<String, String> props) {
            properties = props;
            manufacturer = props.get("ro.product.manufacturer"); model = props.get("ro.product.model");
            product = props.get("ro.product.device"); androidVersion = props.get("ro.build.version.release");
            apiLevel = integer(props.get("ro.build.version.sdk")); securityPatch = props.get("ro.build.version.security_patch");
            fingerprint = props.get("ro.build.fingerprint");
            serial = first(props.get("ro.serialno"), props.get("ro.boot.serialno"));
            abis = first(props.get("ro.product.cpu.abilist"), props.get("ro.product.cpu.abi"));
        }
    }
    public static DeviceInfo deviceInfo(String text) { return new DeviceInfo(properties(text)); }
    public static final class BatteryInfo {
        public final Integer level, scale, status, health, voltageMillivolts;
        public final Double percentage, temperatureCelsius;
        public final Boolean acPowered, usbPowered, wirelessPowered, present;
        public final String technology;
        public final Map<String, String> raw;
        private BatteryInfo(Map<String, String> map) {
            raw = Collections.unmodifiableMap(map);
            level = integer(map.get("level")); scale = integer(map.get("scale"));
            status = integer(map.get("status")); health = integer(map.get("health"));
            voltageMillivolts = integer(map.get("voltage"));
            percentage = level != null && scale != null && scale > 0 && level >= 0 && level <= scale
                    ? level.doubleValue() * 100 / scale : null;
            Integer temperature = integer(map.get("temperature"));
            temperatureCelsius = temperature == null ? null : temperature.doubleValue() / 10;
            acPowered = bool(map.get("AC powered")); usbPowered = bool(map.get("USB powered"));
            wirelessPowered = bool(map.get("Wireless powered")); present = bool(map.get("present"));
            technology = map.get("technology");
        }
    }
    public static BatteryInfo battery(String text) {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (String line : lines(text)) {
            int colon = line.indexOf(':');
            if (colon >= 0 && colon < line.length() - 1)
                map.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return new BatteryInfo(map);
    }
    public static final class MemoryInfo {
        public final Long totalBytes, availableBytes, freeBytes, usedBytes;
        public final boolean frameworkAvailability;
        public final Map<String, Long> fields;
        private MemoryInfo(Map<String, Long> map) { this(map, null); }
        private MemoryInfo(Map<String, Long> map, Long frameworkAvailable) {
            fields = Collections.unmodifiableMap(map);
            totalBytes = map.get("MemTotal"); freeBytes = map.get("MemFree");
            // MemFree is not MemAvailable; do not mislabel cache or fabricate the missing field.
            Long measured = map.get("MemAvailable");
            frameworkAvailability = measured == null && frameworkAvailable != null && totalBytes != null
                    && frameworkAvailable >= 0 && frameworkAvailable <= totalBytes;
            availableBytes = frameworkAvailability ? frameworkAvailable : measured;
            usedBytes = totalBytes != null && availableBytes != null && availableBytes <= totalBytes
                    ? totalBytes - availableBytes : null;
        }
        /** A target ActivityManager measurement, not a host value or an estimate from MemFree. */
        public MemoryInfo withFrameworkAvailable(Long bytes) {
            return availableBytes != null ? this : new MemoryInfo(fields, bytes);
        }
    }
    public static MemoryInfo memory(String text) {
        Map<String, Long> map = new LinkedHashMap<String, Long>();
        Pattern pattern = Pattern.compile("^([^:]+):\\s*(\\d+)\\s*(kB|KB|B)?\\s*$");
        for (String line : lines(text)) {
            Matcher m = pattern.matcher(line.trim());
            if (m.matches()) {
                Long number = longNumber(m.group(2));
                Long value = number == null ? null : multiply(number, m.group(3) != null && !m.group(3).equals("B") ? 1024 : 1);
                if (value != null) map.put(m.group(1), value);
            }
        }
        return new MemoryInfo(map);
    }
    public static final class CpuInfo {
        public final Integer logicalProcessorCount;
        public final String hardware, model, features;
        public final Map<String, String> fields;
        private CpuInfo(int count, Map<String, String> values) {
            logicalProcessorCount = count == 0 ? null : count;
            hardware = values.get("Hardware"); model = first(values.get("model name"), values.get("Processor"));
            features = first(values.get("Features"), values.get("flags"));
            fields = Collections.unmodifiableMap(values);
        }
    }
    public static CpuInfo cpu(String text) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        int count = 0;
        for (String line : lines(text)) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
            if (key.equals("processor") && integer(value) != null) count++;
            if (!map.containsKey(key)) map.put(key, value);
        }
        return new CpuInfo(count, map);
    }
    public static final class PackageInfo {
        public final String packageName, apkPath;
        public final Integer uid;
        /** Null means the corresponding separate package query was unavailable. */
        public final Boolean systemApp, enabled;
        private PackageInfo(String name, String path, Integer uid) { this(name, path, uid, null, null); }
        private PackageInfo(String name, String path, Integer uid, Boolean systemApp, Boolean enabled) {
            packageName = name; apkPath = path; this.uid = uid; this.systemApp = systemApp; this.enabled = enabled;
        }
        @Override public String toString() { return packageName; }
    }
    public static List<PackageInfo> packages(String text) {
        Map<String, PackageInfo> values = new LinkedHashMap<String, PackageInfo>();
        for (String line : lines(text)) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String value = line.substring(8);
            Integer uid = null;
            Matcher uidMatch = Pattern.compile("\\s+uid:(\\d+)\\s*$").matcher(value);
            if (uidMatch.find()) { uid = integer(uidMatch.group(1)); value = value.substring(0, uidMatch.start()); }
            int separator = value.lastIndexOf('=');
            String name = separator >= 0 ? value.substring(separator + 1) : value;
            String path = separator >= 0 ? value.substring(0, separator) : null;
            try { DeviceCommands.packageName(name); }
            catch (IllegalArgumentException malformed) { continue; }
            PackageInfo previous = values.get(name);
            values.put(name, new PackageInfo(name, path == null && previous != null ? previous.apkPath : path,
                    uid == null && previous != null ? previous.uid : uid));
        }
        return Collections.unmodifiableList(new ArrayList<PackageInfo>(values.values()));
    }
    /** Combines successful all/system/disabled queries without losing dimensions or APK paths.
     * Pass null for an unavailable system/disabled query; its flags stay unknown. */
    public static List<PackageInfo> packageInventory(String all, String system, String disabled) {
        Map<String, Boolean> systemNames = new LinkedHashMap<String, Boolean>();
        Map<String, Boolean> disabledNames = new LinkedHashMap<String, Boolean>();
        for (PackageInfo item : packages(system)) systemNames.put(item.packageName, Boolean.TRUE);
        for (PackageInfo item : packages(disabled)) disabledNames.put(item.packageName, Boolean.TRUE);
        List<PackageInfo> result = new ArrayList<PackageInfo>();
        for (PackageInfo item : packages(all))
            result.add(new PackageInfo(item.packageName, item.apkPath, item.uid,
                    system == null ? null : systemNames.containsKey(item.packageName),
                    disabled == null ? null : !disabledNames.containsKey(item.packageName)));
        return Collections.unmodifiableList(result);
    }
    public static List<String> packagePaths(String text) {
        List<String> values = new ArrayList<String>();
        for (String line : lines(text)) {
            line = line.trim();
            if (line.startsWith("package:/")) values.add(line.substring(8));
        }
        return Collections.unmodifiableList(values);
    }
    public static final class PackageDetails {
        public final String packageName, versionName, codePath, dataDir, firstInstallTime, lastUpdateTime, installer;
        public final Long versionCode;
        public final Integer minSdk, targetSdk;
        public final Boolean systemApp;
        public final String raw;
        private PackageDetails(String name, String text) {
            packageName = name; raw = safe(text);
            versionName = field(text, "versionName"); codePath = field(text, "codePath"); dataDir = field(text, "dataDir");
            firstInstallTime = field(text, "firstInstallTime"); lastUpdateTime = field(text, "lastUpdateTime");
            installer = field(text, "installerPackageName");
            versionCode = longNumber(token(text, "versionCode"));
            minSdk = integer(token(text, "minSdk")); targetSdk = integer(token(text, "targetSdk"));
            Matcher flags = Pattern.compile("(?m)^\\s*(?:pkgFlags|flags)=\\[([^]]*)\\]").matcher(safe(text));
            systemApp = flags.find() ? Pattern.compile("(?:^|\\s)SYSTEM(?:\\s|$)").matcher(flags.group(1)).find() : null;
        }
    }
    public static PackageDetails packageDetails(String name, String text) {
        return new PackageDetails(DeviceCommands.packageName(name), text);
    }
    private static String field(String text, String field) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(field) + "=(.*)$").matcher(safe(text));
        return m.find() ? m.group(1).trim() : null;
    }
    private static String token(String text, String field) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(field) + "=([^\\s]+)").matcher(safe(text));
        return m.find() ? m.group(1) : null;
    }
    public static String launcherComponent(String text) {
        for (String line : lines(text)) {
            line = line.trim();
            try { DeviceCommands.startComponent(line, 0); return line; }
            catch (IllegalArgumentException ignored) { /* resolution may include a priority header */ }
        }
        return null;
    }
    public static Integer installSession(String text) {
        Matcher m = Pattern.compile("(?m)^Success: created install session \\[(\\d+)\\]\\s*$").matcher(safe(text));
        return m.find() ? integer(m.group(1)) : null;
    }
    public static final class ProcessInfo {
        public final String user, name;
        public final int pid;
        public final Integer parentPid;
        public final Long virtualBytes, residentBytes;
        private ProcessInfo(String user, int pid, Integer parentPid, Long virtualBytes, Long residentBytes, String name) {
            this.user = user; this.pid = pid; this.parentPid = parentPid;
            this.virtualBytes = virtualBytes; this.residentBytes = residentBytes; this.name = name;
        }
        @Override public String toString() { return pid + " " + name; }
    }
    public static List<ProcessInfo> processes(String text) {
        List<ProcessInfo> result = new ArrayList<ProcessInfo>();
        String[] header = null;
        boolean legacyUnlabelledState = false;
        int pid = -1, ppid = -1, user = -1, vsz = -1, rss = -1, name = -1;
        for (String line : lines(text)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (header == null) {
                header = line.split("\\s+");
                for (int i = 0; i < header.length; i++) {
                    if (header[i].equals("PID")) pid = i;
                    else if (header[i].equals("PPID")) ppid = i;
                    else if (header[i].equals("USER") || header[i].equals("UID")) user = i;
                    else if (header[i].equals("VSZ") || header[i].equals("VSIZE")) vsz = i;
                    else if (header[i].equals("RSS")) rss = i;
                    else if (header[i].equals("NAME") || header[i].equals("CMD") || header[i].equals("COMMAND") || header[i].equals("ARGS")) name = i;
                }
                if (pid < 0 || name < 0) throw new IllegalArgumentException("Unrecognized ps header");
                legacyUnlabelledState = Arrays.equals(header, new String[]{"USER", "PID", "PPID", "VSIZE", "RSS", "WCHAN", "PC", "NAME"});
                continue;
            }
            String[] columns = line.split("\\s+", header.length);
            if (columns.length < header.length) continue;
            Integer processId = integer(columns[pid]);
            if (processId == null || processId < 0) continue;
            String processName = columns[name];
            // KitKat toolbox prints an unlabelled task-state column between PC and NAME.
            if (legacyUnlabelledState && processName.matches("[RSDZTtXxKWIP]\\s+.+")) processName = processName.substring(1).trim();
            result.add(new ProcessInfo(at(columns, user), processId, integer(at(columns, ppid)),
                    kib(at(columns, vsz)), kib(at(columns, rss)), processName));
        }
        return Collections.unmodifiableList(result);
    }
    public static final class DisplayInfo {
        public final Integer physicalWidth, physicalHeight, overrideWidth, overrideHeight, physicalDensity, overrideDensity;
        private DisplayInfo(String size, String density) {
            int[] physical = dimensions(size, "Physical"); int[] override = dimensions(size, "Override");
            physicalWidth = physical == null ? null : physical[0]; physicalHeight = physical == null ? null : physical[1];
            overrideWidth = override == null ? null : override[0]; overrideHeight = override == null ? null : override[1];
            physicalDensity = density(density, "Physical"); overrideDensity = density(density, "Override");
        }
        public Integer effectiveWidth() { return overrideWidth == null ? physicalWidth : overrideWidth; }
        public Integer effectiveHeight() { return overrideHeight == null ? physicalHeight : overrideHeight; }
        public Integer effectiveDensity() { return overrideDensity == null ? physicalDensity : overrideDensity; }
    }
    public static DisplayInfo display(String size, String density) { return new DisplayInfo(size, density); }
    private static int[] dimensions(String text, String label) {
        Matcher m = Pattern.compile("(?m)^\\s*" + label + " size:\\s*(\\d+)x(\\d+)\\s*$").matcher(safe(text));
        if (!m.find()) return null;
        Integer width = integer(m.group(1)), height = integer(m.group(2));
        return width == null || height == null || width <= 0 || height <= 0 ? null : new int[] {width, height};
    }
    private static Integer density(String text, String label) {
        Matcher m = Pattern.compile("(?m)^\\s*" + label + " density:\\s*(\\d+)\\s*$").matcher(safe(text));
        if (!m.find()) return null;
        Integer value = integer(m.group(1));
        return value != null && value > 0 ? value : null;
    }
    public static final class StorageInfo {
        public final String filesystem, mountPoint;
        /** Byte counts are estimates when approximate is true; use the reported text in UI. */
        public final long totalBytes, usedBytes, availableBytes;
        public final Integer percentUsed;
        public final boolean approximate;
        /** Legacy Free is f_bfree, including reserved blocks, rather than f_bavail. */
        public final boolean availableIncludesReserved;
        /** Rounded, human-readable values from legacy toolbox, otherwise null. */
        public final String reportedTotal, reportedUsed, reportedAvailable;
        private StorageInfo(String filesystem, String mount, long total, long used, long available, Integer percent) {
            this(filesystem, mount, total, used, available, percent, null, null, null);
        }
        private StorageInfo(String filesystem, String mount, long total, long used, long available, Integer percent,
                String reportedTotal, String reportedUsed, String reportedAvailable) {
            this.filesystem = filesystem; mountPoint = mount; totalBytes = total; usedBytes = used;
            availableBytes = available; percentUsed = percent;
            this.reportedTotal = reportedTotal; this.reportedUsed = reportedUsed; this.reportedAvailable = reportedAvailable;
            approximate = reportedTotal != null;
            availableIncludesReserved = approximate;
        }
    }
    /** Parses POSIX/toybox df and legacy toolbox's rounded Size/Used/Free/Blksize table. */
    public static List<StorageInfo> storage(String text) {
        List<StorageInfo> result = new ArrayList<StorageInfo>();
        Pattern row = Pattern.compile("^(.+?)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)%\\s+(.+)$");
        Pattern legacyRow = Pattern.compile("^(/.+?)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)$");
        Pattern blocks = Pattern.compile("\\b(\\d+)([KMGT]?)-blocks\\b", Pattern.CASE_INSENSITIVE);
        String pending = null;
        Long blockBytes = 1024L; // Headerless input retains the df -kP parser contract.
        boolean legacy = false;
        for (String line : lines(text)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("Filesystem")) {
                pending = null;
                legacy = line.matches("Filesystem\\s+Size\\s+Used\\s+Free\\s+Blksize");
                Matcher units = blocks.matcher(line);
                blockBytes = units.find() ? storageQuantity(units.group(1) + units.group(2)) : null;
                continue;
            }
            if (legacy) {
                Matcher old = legacyRow.matcher(line);
                if (old.matches()) {
                    Long total = storageQuantity(old.group(2)), used = storageQuantity(old.group(3)), available = storageQuantity(old.group(4));
                    Long blockSize = storageQuantity(old.group(5));
                    if (total != null && used != null && available != null && blockSize != null && blockSize > 0)
                        result.add(new StorageInfo(old.group(1), old.group(1), total, used, available, null,
                                old.group(2), old.group(3), old.group(4)));
                }
                continue;
            }
            Matcher m = row.matcher(pending == null ? line : pending + " " + line);
            if (m.matches()) {
                Long total = storageBlocks(m.group(2), blockBytes), used = storageBlocks(m.group(3), blockBytes), available = storageBlocks(m.group(4), blockBytes);
                Integer percent = integer(m.group(5));
                if (total != null && used != null && available != null && percent != null)
                    result.add(new StorageInfo(m.group(1), m.group(6), total, used, available, percent));
                pending = null;
            } else { pending = line.indexOf(' ') < 0 ? line : null; }
        }
        return Collections.unmodifiableList(result);
    }
    private static Long storageBlocks(String count, Long blockBytes) {
        if (blockBytes == null || blockBytes <= 0) return null;
        try {
            long value = Long.parseLong(count);
            return value < 0 || value > Long.MAX_VALUE / blockBytes ? null : value * blockBytes;
        } catch (NumberFormatException invalid) { return null; }
    }
    private static Long storageQuantity(String text) {
        Matcher value = Pattern.compile("^(\\d+(?:\\.\\d+)?)([BKMGT]?)$", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!value.matches()) return null;
        String unit = value.group(2).toUpperCase(java.util.Locale.ROOT);
        int exponent = unit.isEmpty() || unit.equals("B") ? 0 : "KMGT".indexOf(unit) + 1;
        try {
            return new BigDecimal(value.group(1)).multiply(BigDecimal.valueOf(1024).pow(exponent))
                    .setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException invalid) { return null; }
    }
    public static final class NetworkAddress {
        public final String interfaceName, family, address, scope;
        public final int prefixLength;
        private NetworkAddress(String name, String family, String address, int prefix, String scope) {
            interfaceName = name; this.family = family; this.address = address; prefixLength = prefix; this.scope = scope;
        }
    }
    public static List<NetworkAddress> network(String text) {
        List<NetworkAddress> result = new ArrayList<NetworkAddress>();
        Pattern row = Pattern.compile("^\\s*\\d+:\\s+(\\S+)\\s+(inet6?)\\s+([^\\s/]+)/(\\d+)(.*)$");
        for (String line : lines(text)) {
            Matcher m = row.matcher(line);
            if (!m.matches()) continue;
            Integer prefix = integer(m.group(4));
            if (prefix == null || prefix < 0 || prefix > (m.group(2).equals("inet6") ? 128 : 32)) continue;
            Matcher scope = Pattern.compile("\\bscope\\s+(\\S+)").matcher(m.group(5));
            result.add(new NetworkAddress(m.group(1), m.group(2), m.group(3), prefix, scope.find() ? scope.group(1) : null));
        }
        return Collections.unmodifiableList(result);
    }
    public static final class FileStat {
        public final long mode, sizeBytes, modifiedEpochSeconds, uid, gid;
        private FileStat(long mode, long size, long modified, long uid, long gid) {
            this.mode = mode; sizeBytes = size; modifiedEpochSeconds = modified; this.uid = uid; this.gid = gid;
        }
        public boolean isDirectory() { return (mode & 0170000) == 0040000; }
        public boolean isSymbolicLink() { return (mode & 0170000) == 0120000; }
        public boolean isRegularFile() { return (mode & 0170000) == 0100000; }
    }
    public static FileStat stat(String text) {
        String[] values = safe(text).trim().split("\\|", -1);
        if (values.length != 5) throw new IllegalArgumentException("Unrecognized stat output");
        try {
            long mode = Long.parseLong(values[0], 16), size = Long.parseLong(values[1]);
            long modified = Long.parseLong(values[2]), uid = Long.parseLong(values[3]), gid = Long.parseLong(values[4]);
            if (mode < 0 || size < 0 || uid < 0 || gid < 0) throw new NumberFormatException("negative field");
            return new FileStat(mode, size, modified, uid, gid);
        } catch (NumberFormatException malformed) { throw new IllegalArgumentException("Invalid stat fields", malformed); }
    }
    public static Double animationScale(String text) {
        try {
            double value = Double.parseDouble(safe(text).trim());
            return Double.isNaN(value) || Double.isInfinite(value) || value < 0 ? null : value;
        } catch (NumberFormatException malformed) { return null; }
    }
    private static String at(String[] values, int index) { return index < 0 || index >= values.length ? null : values[index]; }
    private static String first(String one, String two) { return one == null || one.isEmpty() ? two : one; }
    private static Integer integer(String text) {
        try { return text == null ? null : Integer.valueOf(text.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static Long longNumber(String text) {
        try { return text == null ? null : Long.valueOf(text.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static Boolean bool(String text) {
        return "true".equalsIgnoreCase(text) ? Boolean.TRUE : "false".equalsIgnoreCase(text) ? Boolean.FALSE : null;
    }
    private static Long kib(String text) {
        Long value = longNumber(text);
        return value == null || value < 0 ? null : multiply(value, 1024);
    }
    private static Long multiply(long value, long factor) {
        return value > Long.MAX_VALUE / factor ? null : value * factor;
    }
}
