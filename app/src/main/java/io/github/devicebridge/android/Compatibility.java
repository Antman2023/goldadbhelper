package io.github.devicebridge.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaCodec;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.Switch;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.io.Closeable;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import android.os.ParcelFileDescriptor;
import io.github.devicebridge.core.AndroidFileAccess;
import java.nio.ByteBuffer;

/** API-level boundaries are isolated so KitKat never resolves newer framework signatures. */
final class Compatibility {
    private Compatibility() { }
    static void elevation(View view, float pixels) { if (Build.VERSION.SDK_INT >= 21) Api21.elevation(view, pixels); }
    static void clipToOutline(View view, boolean value) { if (Build.VERSION.SDK_INT >= 21) Api21.clipToOutline(view, value); }
    static void statusBarColor(Window window, int color) { if (Build.VERSION.SDK_INT >= 21) Api21.statusBarColor(window, color); }
    static void buttonTint(CompoundButton button, ColorStateList color) { if (Build.VERSION.SDK_INT >= 21) Api21.buttonTint(button, color); }
    static void switchShowText(Switch view, boolean value) { if (Build.VERSION.SDK_INT >= 21) Api21.switchShowText(view, value); }
    static String[] splitSourceDirs(ApplicationInfo info) { return Build.VERSION.SDK_INT >= 21 ? Api21.splitSourceDirs(info) : null; }
    static String usbProductName(android.hardware.usb.UsbDevice device) { return Build.VERSION.SDK_INT >= 21 ? Api21.usbProductName(device) : device.getDeviceName(); }
    static boolean supportsDocumentTree() { return Build.VERSION.SDK_INT >= 21; }
    static void inset(View root) {
        if (Build.VERSION.SDK_INT >= 21) Api21.inset(root);
        else root.setFitsSystemWindows(true);
    }
    static Drawable ripple(int color, Drawable content, Drawable mask) {
        if (Build.VERSION.SDK_INT >= 21) return Api21.ripple(color, content, mask);
        // State-list feedback is available on API 19. It does not pretend to animate a ripple.
        StateListDrawable states = new StateListDrawable();
        Drawable pressed = content == null ? new ColorDrawable(color)
                : new LayerDrawable(new Drawable[]{copy(content), new ColorDrawable(color)});
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, content == null ? new ColorDrawable(0) : content);
        return states;
    }
    private static Drawable copy(Drawable drawable) {
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? drawable : state.newDrawable().mutate();
    }
    static ByteBuffer inputBuffer(MediaCodec codec, int index) {
        return Build.VERSION.SDK_INT >= 21 ? Api21.inputBuffer(codec, index) : codec.getInputBuffers()[index];
    }
    static File noBackupFilesDir(Context context) throws IOException {
        if (Build.VERSION.SDK_INT >= 21) return Api21.noBackupFilesDir(context);
        // allowBackup=false applies on API 19 too; keep credentials in an app-private directory.
        File directory = new File(context.getFilesDir(), "no_backup");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private identity directory");
        return directory;
    }
    /** New files only. Never removes a possibly partial/replaced final path after failure. */
    static OutputStream createExclusive(File file) throws IOException {
        final AndroidFileAccess fs = new AndroidFileAccess();
        final FileDescriptor fd = fs.createExclusive(file.getAbsolutePath());
        return output(fs, fd);
    }
    private static OutputStream output(final AndroidFileAccess fs, final FileDescriptor fd) throws IOException {
        try { if (!fs.fstat(fd).regular) throw new IOException("Output is not a regular file"); }
        catch (IOException failure) { try { fs.close(fd); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); } throw failure; }
        return new OutputStream() {
            private boolean closed;
            public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
            public void write(byte[] data, int offset, int count) throws IOException {
                if (closed) throw new IOException("File is closed");
                if (offset < 0 || count < 0 || offset > data.length - count) throw new IndexOutOfBoundsException();
                while (count > 0) {
                    int n = fs.write(fd, data, offset, count);
                    if (n <= 0 || n > count) throw new IOException("File write made no progress");
                    offset += n; count -= n;
                }
            }
            public void flush() throws IOException { if (closed) throw new IOException("File is closed"); fs.fsync(fd); }
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                try { fs.fsync(fd); } finally { fs.close(fd); }
            }
        };
    }
    static String sourceToken(File file) throws IOException {
        AndroidFileAccess fs = new AndroidFileAccess();
        FileDescriptor fd = fs.readOnly(file.getAbsolutePath());
        try { return sourceStat(fs, fd, file.getAbsolutePath(), null).token(); }
        finally { fs.close(fd); }
    }
    private static AndroidFileAccess.Stat sourceStat(AndroidFileAccess fs, FileDescriptor fd, String path, String token) throws IOException {
        AndroidFileAccess.Stat stat = fs.fstat(fd);
        if (!stat.regular || !stat.token().equals(fs.lstat(path).token()) || (token != null && !token.equals(stat.token())))
            throw new IOException("源文件已改变、不是普通文件或是符号链接，请重新选择");
        return stat;
    }
    static final class CopyResult {
        final long bytes; final String sha256, sourceToken;
        CopyResult(long bytes, String sha256, String token) { this.bytes = bytes; this.sha256 = sha256; this.sourceToken = token; }
    }
    /** Copies to a new app-private staging file, checking one opened source twice. */
    static CopyResult copyVerifiedSource(File source, File privateDestination, String expectedToken) throws IOException {
        AndroidFileAccess fs = new AndroidFileAccess(); String path = source.getAbsolutePath();
        FileDescriptor input = fs.readOnly(path);
        try {
            AndroidFileAccess.Stat before = sourceStat(fs, input, path, expectedToken);
            MessageDigest first = sha256(); long bytes = 0;
            try (OutputStream output = createExclusive(privateDestination)) {
                byte[] data = new byte[65536]; int n;
                while ((n = fs.read(input, data)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("复制已取消");
                    if (n > before.size - bytes) throw new IOException("复制期间源文件增长");
                    first.update(data, 0, n); output.write(data, 0, n); bytes += n;
                }
                output.flush();
            }
            sourceStat(fs, input, path, before.token());
            if (bytes != before.size) throw new IOException("复制期间源文件大小改变");
            fs.rewind(input); MessageDigest second = sha256(); long reread = 0;
            byte[] data = new byte[65536]; int n;
            while ((n = fs.read(input, data)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("验证已取消");
                if (n > bytes - reread) throw new IOException("验证期间源文件增长");
                second.update(data, 0, n); reread += n;
            }
            sourceStat(fs, input, path, before.token());
            byte[] hash = first.digest();
            if (reread != bytes || !MessageDigest.isEqual(hash, second.digest())) throw new IOException("源文件在复制/验证期间改变");
            FileDescriptor staged = fs.readOnly(privateDestination.getAbsolutePath());
            try {
                AndroidFileAccess.Stat stagedBefore = sourceStat(fs, staged, privateDestination.getAbsolutePath(), null);
                MessageDigest persisted = sha256(); long persistedBytes = 0;
                while ((n = fs.read(staged, data)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("缓存验证已取消");
                    persisted.update(data, 0, n); persistedBytes += n;
                }
                sourceStat(fs, staged, privateDestination.getAbsolutePath(), stagedBefore.token());
                if (persistedBytes != bytes || !MessageDigest.isEqual(hash, persisted.digest())) throw new IOException("私有缓存摘要与所选源文件不一致");
            } finally { fs.close(staged); }
            return new CopyResult(bytes, hex(hash), before.token());
        } finally { fs.close(input); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte value : bytes) s.append("0123456789abcdef".charAt((value & 255) >>> 4)).append("0123456789abcdef".charAt(value & 15));
        return s.toString();
    }
    static String directoryToken(File directory) throws IOException {
        try (Directory bound = bindDirectory(directory, null)) { return bound.token(); }
    }
    static Directory bindDirectory(File directory, String expectedToken) throws IOException {
        return new Directory(directory.getAbsolutePath(), expectedToken);
    }
    /** Descriptor-anchored directory: replacements of the selected pathname cannot redirect IO. */
    static final class Directory implements Closeable {
        private final AndroidFileAccess fs = new AndroidFileAccess();
        private final ParcelFileDescriptor descriptor;
        private final String token;
        private boolean closed;
        Directory(String path, String expectedToken) throws IOException {
            FileDescriptor fd = fs.openDirectory(path);
            try {
                AndroidFileAccess.Stat stat = fs.fstat(fd);
                if (!stat.directory || !stat.directoryToken().equals(fs.lstat(path).directoryToken())
                        || (expectedToken != null && !expectedToken.equals(stat.directoryToken())))
                    throw new IOException("所选目录已改变，请重新选择");
                descriptor = ParcelFileDescriptor.dup(fd); token = stat.directoryToken();
            } finally { fs.close(fd); }
        }
        String token() { return token; }
        private String child(String name) throws IOException {
            if (closed) throw new IOException("目录句柄已关闭");
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
                throw new IOException("无效文件名");
            return "/proc/self/fd/" + descriptor.getFd() + "/" + name;
        }
        OutputStream createExclusive(String name) throws IOException { return output(fs, fs.createExclusive(child(name))); }
        Directory createDirectory(String name) throws IOException {
            String path = child(name); fs.mkdir(path);
            return new Directory(path, null);
        }
        public void close() throws IOException { if (!closed) { closed = true; descriptor.close(); } }
    }
    private static final class Api21 {
        static String usbProductName(android.hardware.usb.UsbDevice device) { return device.getProductName(); }
        static void elevation(View view, float pixels) { view.setElevation(pixels); }
        static void clipToOutline(View view, boolean value) { view.setClipToOutline(value); }
        static void statusBarColor(Window window, int color) { window.setStatusBarColor(color); }
        static void buttonTint(CompoundButton button, ColorStateList color) { button.setButtonTintList(color); }
        static void switchShowText(Switch view, boolean value) { view.setShowText(value); }
        static String[] splitSourceDirs(ApplicationInfo info) { return info.splitSourceDirs; }
        static File noBackupFilesDir(Context context) { return context.getNoBackupFilesDir(); }
        static ByteBuffer inputBuffer(MediaCodec codec, int index) { return codec.getInputBuffer(index); }
        static Drawable ripple(int color, Drawable content, Drawable mask) {
            return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color), content, mask);
        }
        static void inset(View root) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
        }
    }
}
