package io.github.devicebridge.android;

import android.content.Context;
import io.github.devicebridge.core.*;
import java.io.*;
import java.util.UUID;

/** Read-only metadata fallback when older toolbox has no compatible stat utility. */
final class MetadataQuery {
    static DeviceParsers.FileStat stat(Context context, AdbSession target, String path) throws IOException {
        DeviceCommands.remotePath(path);
        String workspace = "/data/local/tmp/goldenbeetle-metadata-" + UUID.randomUUID();
        String helper = workspace + "/metadata-server.jar";
        ShellClient shell = new ShellClient(target);
        boolean reserved = false;
        try {
            require(shell.run("umask 077; mkdir " + DeviceCommands.quote(workspace), 10000));
            reserved = true;
            try (InputStream asset = context.getAssets().open("metadata-server.jar"); SyncClient sync = new SyncClient(target, 30000)) {
                sync.push(helper, asset, -1, 0600, System.currentTimeMillis() / 1000,
                        (done, total) -> !Thread.currentThread().isInterrupted());
            }
            require(shell.run("chmod 0400 " + DeviceCommands.quote(helper), 10000));
            ShellClient.Result result = shell.run("CLASSPATH=" + DeviceCommands.quote(helper)
                    + " app_process / io.github.devicebridge.server.MetadataServer stat " + DeviceCommands.quote(path), 30000);
            require(result);
            try { return DeviceParsers.stat(result.text()); }
            catch (IllegalArgumentException invalid) { throw new IOException("目标没有返回有效的文件属性", invalid); }
        } finally {
            if (reserved) for (String warning : FileTransferManager.cleanupWorkspace(target, workspace))
                BridgeRuntime.get().report("目标：" + target.target() + "\n属性组件清理提醒：" + warning);
        }
    }
    private static void require(ShellClient.Result result) throws IOException {
        if (!result.success()) throw new IOException(result.text() + result.error());
    }
}
