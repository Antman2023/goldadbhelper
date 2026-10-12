package io.github.devicebridge.android;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Read-only local PackageManager access. Does not send or persist the application inventory. */
final class LocalApps {
    private LocalApps() { }

    static final class Entry {
        final String packageName, label;
        Entry(String packageName, String label) { this.packageName = packageName; this.label = label; }
        String display() { return label + "\n" + packageName; }
    }
    static final class Catalog {
        final List<Entry> entries;
        final String failure;
        Catalog(List<Entry> entries, String failure) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.failure = failure;
        }
    }

    static Catalog load(PackageManager pm) throws InterruptedIOException {
        List<Entry> entries = new ArrayList<>();
        checkActive();
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            if (apps == null) return new Catalog(entries, "系统没有返回应用列表。");
            for (ApplicationInfo app : apps) {
                checkActive();
                if (app == null || app.packageName == null || app.packageName.isEmpty()) continue;
                entries.add(new Entry(app.packageName, label(pm, app)));
            }
            entries.sort(Comparator.comparing((Entry e) -> e.label, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(e -> e.packageName));
            return new Catalog(entries, "");
        } catch (SecurityException blocked) {
            return new Catalog(Collections.emptyList(), "系统拒绝读取本机应用列表。请检查应用信息中的权限设置，允许后返回重试。");
        } catch (RuntimeException unavailable) {
            return new Catalog(Collections.emptyList(), "系统应用服务读取失败（" + unavailable.getClass().getSimpleName()
                    + "）。请稍后重试；仍然失败可查看权限说明。");
        }
    }

    static List<Entry> filter(List<Entry> entries, String query) {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.label.toLowerCase(Locale.ROOT).contains(term)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(term)) result.add(entry);
        }
        return result;
    }

    static LocalAppSelection read(PackageManager pm, String packageName) throws IOException {
        checkActive();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            ApplicationInfo app = info.applicationInfo;
            if (app == null || !packageName.equals(info.packageName) || !packageName.equals(app.packageName))
                throw new IOException("系统未返回所选本机应用的完整信息，请刷新后重新选择。");
            List<String> paths = new ArrayList<>();
            paths.add(app.sourceDir);
            String[] splits = Compatibility.splitSourceDirs(app);
            if (splits != null) Collections.addAll(paths, splits);
            return new LocalAppSelection(info.packageName, label(pm, app), info.versionName,
                    versionCode(info), info.lastUpdateTime, app.uid, paths);
        } catch (PackageManager.NameNotFoundException missing) {
            throw new IOException("所选本机应用已卸载或系统限制了可见性，请刷新应用列表。", missing);
        } catch (SecurityException denied) {
            throw new IOException("系统拒绝读取所选本机应用。请打开“本机应用权限说明”，在系统应用信息中检查权限后重试。", denied);
        } catch (RuntimeException unavailable) {
            throw new IOException("系统无法读取所选本机应用（" + unavailable.getClass().getSimpleName() + "），请刷新后重试。", unavailable);
        }
    }

    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? Api28.versionCode(info) : ((long) info.versionCode & 0xffffffffL);
    }
    private static String label(PackageManager pm, ApplicationInfo app) {
        try {
            CharSequence label = pm.getApplicationLabel(app);
            return label == null || label.length() == 0 ? app.packageName : label.toString();
        } catch (RuntimeException unavailable) { return app.packageName; }
    }
    private static void checkActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("读取本机应用已取消");
    }
    private static final class Api28 {
        static long versionCode(PackageInfo info) { return info.getLongVersionCode(); }
    }
}
