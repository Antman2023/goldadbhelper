package io.github.devicebridge.android;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Platform-independent, read-only rules for the local picker. Never traverses outside supplied roots. */
final class LocalFileBrowserModel {
    static final int MODE_FILE = 0, MODE_FILES = 1, MODE_DIRECTORY = 2, MODE_APK = 3, MODE_IMAGE = 4;
    static final int SORT_NAME = 0, SORT_TYPE = 1, SORT_TIME = 2, SORT_SIZE = 3;

    static final class Entry {
        final File file;
        final String name, path;
        final boolean directory;
        final long size, modified;

        Entry(File file) {
            this.file = file;
            path = file.getAbsolutePath();
            name = file.getName();
            directory = file.isDirectory();
            size = directory ? 0 : file.length();
            modified = file.lastModified();
        }
    }

    static int checkedMode(int mode) {
        return mode >= MODE_FILE && mode <= MODE_IMAGE ? mode : MODE_FILE;
    }

    static boolean accepts(File file, int mode, boolean hidden) {
        if (!hidden && (file.isHidden() || file.getName().startsWith("."))) return false;
        if (file.isDirectory()) return true;
        if (!file.isFile() || mode == MODE_DIRECTORY) return false;
        // A disk image is not necessarily named .img. Only installation filters by extension.
        return mode != MODE_APK || file.getName().toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    static boolean inside(File root, File file) throws IOException {
        String base = root.getCanonicalPath(), path = file.getCanonicalPath();
        return path.equals(base) || path.startsWith(base.endsWith(File.separator)
                ? base : base + File.separator);
    }

    static File requireAllowed(File file, List<File> roots) throws IOException {
        File canonical = file.getCanonicalFile();
        for (File root : roots) if (inside(root, canonical)) return canonical;
        throw new IOException("路径不属于可浏览的存储位置");
    }

    /** Null listFiles is an access/unmounted error, never an empty directory. */
    static List<Entry> list(File directory, List<File> roots, int mode, boolean hidden,
                            int sortMode, boolean descending, Locale locale) throws IOException {
        File parent = requireAllowed(directory, roots);
        if (!parent.isDirectory()) throw new IOException("目录已不存在或存储设备已移除");
        File[] files;
        try { files = parent.listFiles(); }
        catch (SecurityException error) { throw new IOException("无权读取此目录", error); }
        if (files == null) throw new IOException("无法读取此目录，请检查存储权限或改用系统选择器");
        List<Entry> result = new ArrayList<>();
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("读取已取消");
            try {
                // Keep the displayed path bound to this entry. Never turn a selected link into
                // another file silently, even when the link's target is within a storage root.
                if (!requireAllowed(file, roots).equals(file.getAbsoluteFile())) continue;
                if (accepts(file, mode, hidden)) result.add(new Entry(file));
            } catch (IOException | SecurityException ignored) { /* inaccessible/escaping entry */ }
        }
        sort(result, sortMode, descending, locale);
        return result;
    }

    static void sort(List<Entry> entries, final int mode, final boolean descending, Locale locale) {
        final Collator names = Collator.getInstance(locale == null ? Locale.getDefault() : locale);
        names.setStrength(Collator.SECONDARY);
        Collections.sort(entries, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                int order = 0;
                if (mode == SORT_TYPE) order = extension(left.name).compareTo(extension(right.name));
                else if (mode == SORT_TIME) order = compareLong(left.modified, right.modified);
                else if (mode == SORT_SIZE) order = compareLong(left.size, right.size);
                if (mode == SORT_NAME) order = names.compare(left.name, right.name);
                if (order != 0) return descending ? -Integer.signum(order) : Integer.signum(order);
                // Stable deterministic ties, including Chinese/English and case-equivalent names.
                order = names.compare(left.name, right.name);
                if (order == 0) order = left.name.compareTo(right.name);
                if (order == 0) order = left.path.compareTo(right.path);
                return order;
            }
        });
    }

    private static int compareLong(long left, long right) { return left < right ? -1 : left == right ? 0 : 1; }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot + 1 < name.length() ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Recheck existence, scope and permissions immediately before returning a selection. */
    static File validateSelection(File file, List<File> roots, int mode) throws IOException {
        File canonical = requireAllowed(file, roots);
        if (!canonical.equals(file.getAbsoluteFile()))
            throw new IOException("所选路径已变化或包含符号链接，请刷新后重新选择");
        if (mode == MODE_DIRECTORY) {
            if (!canonical.isDirectory() || !canonical.canRead() || !canonical.canWrite())
                throw new IOException("所选目录不存在或不可写，请重新选择");
        } else if (!canonical.isFile() || !canonical.canRead() || !accepts(file, mode, true)) {
            throw new IOException("所选文件已不存在、不可读或不符合文件类型：" + file.getName());
        }
        return canonical;
    }
}
