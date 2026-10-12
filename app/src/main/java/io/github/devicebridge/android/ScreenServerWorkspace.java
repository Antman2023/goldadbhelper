package io.github.devicebridge.android;

import android.content.Context;
import io.github.devicebridge.core.AdbSession;
import io.github.devicebridge.core.DeviceCommands;
import io.github.devicebridge.core.ShellClient;
import io.github.devicebridge.core.SyncClient;
import java.io.*;
import java.util.*;

/** One attempt's private screen-server workspace. All methods run off the UI thread.
 * The separate metadata helper remains available after scrcpy unlinks its own jar.
 * Cleanup and deployment are serialized, so cancellation cannot recreate a cleaned file.
 */
final class ScreenServerWorkspace {
    private final Context context;
    private final AdbSession session;
    private final ShellClient shell;
    private final String directory = "/data/local/tmp/goldenbeetle-screen-" + UUID.randomUUID();
    private boolean reservationAttempted, owned, helperReady, closed;
    private List<String> cleanupWarnings = Collections.emptyList();

    ScreenServerWorkspace(Context context, AdbSession session) {
        this.context = context.getApplicationContext(); this.session = session; shell = new ShellClient(session);
    }
    String serverJar() { return directory + "/scrcpy-server.jar"; }

    synchronized void deploy(SyncClient.Progress progress) throws IOException {
        checkActive(progress);
        if (reservationAttempted) throw new IOException("屏幕工作目录已使用");
        // Validate both bundled assets before creating anything on the target.
        byte[] helper = asset("metadata-server.jar", 4 * 1024 * 1024);
        byte[] server = asset("scrcpy-server.jar", 8 * 1024 * 1024);
        checkActive(progress);
        reservationAttempted = true;
        requireSuccess(shell.run("umask 077; mkdir " + DeviceCommands.quote(directory), 10000), "无法预留屏幕工作目录");
        owned = true;
        checkActive(progress);
        push("metadata-server.jar", helper, progress);
        helperReady = true;
        checkActive(progress);
        push("scrcpy-server.jar", server, progress);
        checkActive(progress);
    }
    private void push(String name, byte[] bytes, SyncClient.Progress progress) throws IOException {
        String path = directory + "/" + name;
        try (SyncClient sync = new SyncClient(session, 30000)) {
            sync.push(path, new ByteArrayInputStream(bytes), bytes.length, 0600, System.currentTimeMillis() / 1000,
                    (done, total) -> !Thread.currentThread().isInterrupted() && (progress == null || progress.onProgress(done, total)));
        }
        requireSuccess(shell.run("chmod 0400 " + DeviceCommands.quote(path), 10000), "无法保护屏幕组件");
    }
    private byte[] asset(String name, int limit) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消屏幕组件部署");
                if ((long) out.size() + n > limit) throw new IOException("屏幕组件超过大小限制");
                out.write(buffer, 0, n);
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw new IOException("屏幕组件不是有效 JAR/APK：" + name);
            return bytes;
        }
    }
    private void checkActive(SyncClient.Progress progress) throws IOException {
        if (closed || Thread.currentThread().isInterrupted() || (progress != null && !progress.onProgress(0, -1)))
            throw new InterruptedIOException("已停止屏幕组件部署");
    }
    private static void requireSuccess(ShellClient.Result result, String message) throws IOException {
        if (!result.success()) throw new IOException(message + "：" + result.text() + result.error());
    }

    /** Without a complete helper or confirmed process exit, retain the directory for inspection.
     * Never fall back to recursive shell deletion or sweep other attempts' temporary files.
     */
    synchronized List<String> cleanup(boolean processEnded) {
        if (closed) return cleanupWarnings;
        closed = true;
        List<String> warnings = new ArrayList<>();
        if (reservationAttempted) {
            if (!owned) warnings.add("工作目录创建未确认，已保留：" + directory);
            else if (!helperReady) warnings.add("清理组件部署未完成，已保留：" + directory);
            else if (!processEnded) warnings.add("屏幕服务退出未确认，已保留：" + directory);
            else if (session.state() != AdbSession.State.CONNECTED) warnings.add("设备已断开，屏幕工作目录清理未确认：" + directory);
            else warnings.addAll(FileTransferManager.cleanupScreenWorkspace(session, directory));
        }
        cleanupWarnings = Collections.unmodifiableList(warnings);
        return cleanupWarnings;
    }
}
