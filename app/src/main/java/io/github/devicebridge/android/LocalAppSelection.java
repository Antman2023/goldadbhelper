package io.github.devicebridge.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable PackageManager metadata for one explicitly selected local application. */
final class LocalAppSelection {
    final String packageName, label, versionName;
    final long versionCode, lastUpdateTime;
    final int uid;
    // Base first, then every split in PackageManager's order. No caller-supplied file paths.
    final List<String> apkPaths;

    LocalAppSelection(String packageName, String label, String versionName, long versionCode,
            long lastUpdateTime, int uid, List<String> apkPaths) throws IOException {
        if (packageName == null || packageName.isEmpty() || apkPaths == null || apkPaths.isEmpty())
            throw new IOException("本机应用没有可读取的 APK 信息，请刷新后重新选择。");
        List<String> copy = new ArrayList<>(apkPaths);
        for (String path : copy) {
            if (path == null || path.isEmpty() || !new File(path).isAbsolute())
                throw new IOException("系统未提供完整的本机 APK 路径，请刷新后重新选择。");
        }
        if (new HashSet<>(copy).size() != copy.size())
            throw new IOException("系统返回了重复的 APK 路径，无法确认完整分包集合。");
        this.packageName = packageName;
        this.label = label == null || label.isEmpty() ? packageName : label;
        this.versionName = versionName == null ? "" : versionName;
        this.versionCode = versionCode;
        this.lastUpdateTime = lastUpdateTime;
        this.uid = uid;
        this.apkPaths = Collections.unmodifiableList(copy);
    }

    void requireUnchanged(LocalAppSelection current) throws IOException {
        if (current == null || !packageName.equals(current.packageName)
                || versionCode != current.versionCode || !versionName.equals(current.versionName)
                || lastUpdateTime != current.lastUpdateTime || uid != current.uid
                || !apkPaths.equals(current.apkPaths))
            throw new IOException("本机应用在读取期间已更新、卸载或分包改变。没有上传或安装，请重新选择。");
    }

    void requireBaseArchive(String archivePackage, long archiveVersion) throws IOException {
        if (!Objects.equals(packageName, archivePackage) || versionCode != archiveVersion)
            throw new IOException("本机 APK 快照与所选应用的包名或版本不一致。没有上传或安装，请重新选择。");
    }
}
