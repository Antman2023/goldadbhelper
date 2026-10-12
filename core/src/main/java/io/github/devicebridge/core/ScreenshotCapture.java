package io.github.devicebridge.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * One cancellable PNG capture, including old adbd without the exec service.
 * Legacy captures use a private, exclusively created directory and binary SYNC;
 * PNG bytes never pass through a PTY or newline conversion. Closing this object
 * cancels its channels, not the shared ADB session. Use a new instance per shot.
 */
public final class ScreenshotCapture implements Closeable {
    private static final Map<AdbSession, Boolean> LEGACY =
            Collections.synchronizedMap(new WeakHashMap<AdbSession, Boolean>());
    private static final byte[] PNG = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private final AdbSession session;
    private boolean started, cancelled, cleaning;
    private Thread worker;
    private Closeable channel;
    private volatile String cleanupWarning;

    public static final class Result {
        public final byte[] png;
        public final String cleanupWarning;
        public final boolean usedLegacyFallback;
        private Result(byte[] png, String warning, boolean legacy) {
            this.png = png; this.cleanupWarning = warning; this.usedLegacyFallback = legacy;
        }
    }

    /** A primary capture error, with any separately failed remote cleanup retained. */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final String cleanupWarning;
        private Failure(IOException cause, String warning) {
            super(cause.getMessage() + (warning == null ? "" : "\n" + warning), cause);
            cleanupWarning = warning;
        }
    }

    public ScreenshotCapture(AdbSession session) {
        if (session == null) throw new NullPointerException("session");
        this.session = session;
    }

    /** Blocking. timeoutMs bounds each ADB channel wait; limit bounds the complete PNG. */
    public Result capture(int timeoutMs, int limit) throws IOException {
        if (timeoutMs <= 0 || limit < 24) throw new IllegalArgumentException("Invalid screenshot limits");
        synchronized (this) {
            if (started) throw new IllegalStateException("Screenshot operation already used");
            started = true; worker = Thread.currentThread();
        }
        try {
            checkCancelled();
            if (!Boolean.TRUE.equals(LEGACY.get(session))) {
                AdbStream exec = null;
                try { exec = session.open("exec:screencap -p", timeoutMs); }
                catch (IOException failure) {
                    checkCancelled();
                    // Only OPEN rejection establishes missing service support. Command
                    // failures, malformed PNG, read timeouts and transport loss must surface.
                    if (!"ADB service rejected".equals(failure.getMessage())) throw failure;
                    LEGACY.put(session, Boolean.TRUE);
                }
                if (exec != null) {
                    track(exec);
                    try {
                        byte[] png = readBounded(exec.input(), limit);
                        checkCancelled(); validatePng(png);
                        return new Result(png, null, false);
                    } finally { release(exec); }
                }
            }
            return legacyCapture(timeoutMs, limit);
        } finally {
            synchronized (this) { worker = null; channel = null; }
        }
    }

    private Result legacyCapture(int timeoutMs, int limit) throws IOException {
        String directory = "/data/local/tmp/jinjiachong-shot-" + UUID.randomUUID().toString().replace("-", "");
        String path = directory + "/screen.png";
        boolean owned = false, reserveStarted = false;
        byte[] png = null;
        IOException failure = null;
        try {
            checkCancelled();
            reserveStarted = true;
            ShellClient.Result reserve = new ShellClient(session).run("umask 077; mkdir " + DeviceCommands.quote(directory), timeoutMs);
            if (!reserve.success()) {
                reserveStarted = false; // Definite failed mkdir: never remove a pre-existing path.
                throw commandFailure("无法创建截图临时目录", reserve);
            }
            owned = true;
            checkCancelled();
            ShellClient.Result shot = new ShellClient(session).run("umask 077; screencap -p " + DeviceCommands.quote(path), timeoutMs);
            if (!shot.success()) throw commandFailure("目标截图命令失败", shot);
            checkCancelled();
            SyncClient sync = new SyncClient(session, timeoutMs);
            track(sync);
            try {
                SyncClient.Entry info = sync.stat(path);
                if ((info.mode & 0170000) != 0100000) throw new IOException("截图临时文件不是普通文件");
                if (info.size < 24 || info.size > limit) throw new IOException("截图文件超过安全大小限制或内容为空");
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(info.size, 65536));
                OutputStream bounded = new OutputStream() {
                    public void write(int value) throws IOException {
                        checkCancelled(); if (bytes.size() == limit) throw new IOException("截图超过安全大小限制"); bytes.write(value);
                    }
                    public void write(byte[] data, int offset, int count) throws IOException {
                        checkCancelled(); if (count > limit - bytes.size()) throw new IOException("截图超过安全大小限制"); bytes.write(data, offset, count);
                    }
                };
                sync.pull(path, bounded, info.size, (done, total) -> !isCancelled());
                png = bytes.toByteArray(); checkCancelled(); validatePng(png);
            } finally { release(sync); }
        } catch (IOException error) { failure = error; }
        finally {
            if (owned) cleanup(directory, path);
            else if (reserveStarted) cleanupWarning = "未能确认临时目录是否创建；未删除未经确认的路径：" + directory;
        }
        if (failure != null) throw new Failure(failure, cleanupWarning);
        checkCancelled();
        return new Result(png, cleanupWarning, true);
    }

    private void cleanup(String directory, String path) {
        // Interrupted capture must still attempt bounded cleanup. Never recursively
        // delete, and never touch any path unless mkdir succeeded for this operation.
        synchronized (this) { cleaning = true; }
        boolean interrupted = Thread.interrupted();
        try {
            ShellClient.Result result = new ShellClient(session).run("rm -f " + DeviceCommands.quote(path)
                    + " && rmdir " + DeviceCommands.quote(directory), 2000);
            if (!result.success()) cleanupWarning = "截图临时文件清理失败：" + directory + "（" + commandDetail(result) + "）";
        } catch (IOException error) {
            cleanupWarning = "截图临时文件清理未确认：" + directory + "（" + error.getMessage() + "）";
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
            synchronized (this) { cleaning = false; }
        }
    }

    /** Available even after cancellation, when a normal Result could not be returned. */
    public String cleanupWarning() { return cleanupWarning; }

    private synchronized boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    private void checkCancelled() throws InterruptedIOException {
        if (isCancelled()) throw new InterruptedIOException("已取消截图");
    }
    private void track(Closeable value) throws IOException {
        synchronized (this) {
            if (!cancelled && !Thread.currentThread().isInterrupted()) { channel = value; return; }
        }
        value.close(); throw new InterruptedIOException("已取消截图");
    }
    private void release(Closeable value) throws IOException {
        synchronized (this) { if (channel == value) channel = null; }
        value.close();
    }
    @Override public void close() throws IOException {
        Closeable active;
        synchronized (this) {
            cancelled = true;
            // ShellClient.run owns its channel; interrupting the operation closes it
            // through try-with-resources, including while waiting for OPEN/response.
            if (!cleaning && worker != null) worker.interrupt();
            active = channel;
        }
        if (active != null) active.close();
    }
    private byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = SyncClient.readSome(input, buffer, buffer.length)) != -1) {
            checkCancelled();
            if (count > limit - bytes.size()) throw new IOException("截图超过安全大小限制");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    private static void validatePng(byte[] bytes) throws IOException {
        if (bytes.length < 24) throw new IOException("目标未返回完整 PNG 截图");
        for (int i = 0; i < PNG.length; i++) if (bytes[i] != PNG[i]) throw new IOException("目标未返回 PNG 截图");
        ByteBuffer header = ByteBuffer.wrap(bytes);
        ScrcpyProtocol.validateSize(header.getInt(16), header.getInt(20));
    }
    private static IOException commandFailure(String message, ShellClient.Result result) {
        return new IOException(message + "（" + commandDetail(result) + "）");
    }
    private static String commandDetail(ShellClient.Result result) {
        String text = (result.error() + " " + result.text()).trim();
        if (text.length() > 300) text = text.substring(0, 300);
        return "exit=" + result.exitCode + (text.length() == 0 ? "" : "：" + text);
    }
}
