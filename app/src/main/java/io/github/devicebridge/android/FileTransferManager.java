package io.github.devicebridge.android;

import android.content.Context;
import io.github.devicebridge.core.*;
import org.json.JSONObject;
import java.io.*;

/** Android/ADB adapter for independently authored, verified, no-overwrite file transfers.
 * See VerifiedFileTransfer for Report, TransferException, cancellation and output ownership.
 * Every operation deploys a fresh metadata-server.jar inside its own exclusive private directory.
 */
public final class FileTransferManager extends VerifiedFileTransfer {
    public FileTransferManager(Context context, AdbSession session) {
        super(new AdbBackend(context.getApplicationContext(),session));
    }
    /** For the metadata loader's exclusively reserved workspace with the same fixed helper name. */
    static java.util.List<String> cleanupWorkspace(AdbSession session, String workspace) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (workspace == null || !workspace.matches("/data/local/tmp/goldenbeetle-(transfer|metadata)-[0-9a-f-]{36}")) {
            warnings.add("Refused cleanup of an unowned workspace"); return warnings;
        }
        AdbBackend backend = new AdbBackend(null, session);
        String helper = workspace + "/metadata-server.jar";
        boolean interrupted = Thread.interrupted();
        try {
            try { backend.cleanupArtifacts(workspace, helper); } catch (IOException e) { warnings.add("Runtime artifact cleanup: " + e.getMessage()); }
            interrupted |= Thread.interrupted();
            try { backend.removeFile(helper); } catch (IOException e) { warnings.add("Helper cleanup: " + e.getMessage()); }
            interrupted |= Thread.interrupted();
            try { backend.removeDirectory(workspace); } catch (IOException e) { warnings.add("Workspace retained: " + e.getMessage()); }
        } finally { interrupted |= Thread.interrupted(); if (interrupted) Thread.currentThread().interrupt(); }
        return warnings;
    }
    /** Screen attempts keep a separate helper because upstream scrcpy unlinks its own jar.
     * The helper removes only its fixed screen jars and ART names through descriptor anchors.
     * Failed verification keeps the directory; no shell rm fallback is permitted here.
     */
    static java.util.List<String> cleanupScreenWorkspace(AdbSession session, String workspace) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (workspace == null || !workspace.matches("/data/local/tmp/goldenbeetle-screen-[0-9a-f-]{36}")) {
            warnings.add("Refused cleanup of an unowned screen workspace"); return warnings;
        }
        AdbBackend backend = new AdbBackend(null, session);
        boolean interrupted = Thread.interrupted();
        try {
            try {
                backend.runHelper(workspace + "/metadata-server.jar", "cleanup-screen", workspace);
                interrupted |= Thread.interrupted();
                backend.removeDirectory(workspace); // Empty only; retain all unknown entries.
            } catch (IOException | RuntimeException e) {
                warnings.add("屏幕组件清理未确认，工作目录已保留：" + workspace + " · " + e.getMessage());
            }
        } finally { interrupted |= Thread.interrupted(); if (interrupted) Thread.currentThread().interrupt(); }
        return warnings;
    }
    private static final class AdbBackend implements Backend {
        private final Context context;
        private final AdbSession session;
        private final ShellClient shell;
        AdbBackend(Context context,AdbSession session) {
            if (session==null) throw new NullPointerException("session");
            this.context=context;this.session=session;this.shell=new ShellClient(session);
        }
        public void reserveDirectory(String path) throws IOException {
            requireSuccess(run("umask " + DeviceCommands.quote("077") + "; mkdir " + DeviceCommands.quote(path),10000));
        }
        public void deployHelper(String helper) throws IOException {
            if (context == null) throw new IOException("No asset context");
            try (InputStream input=context.getAssets().open("metadata-server.jar"); SyncClient sync=new SyncClient(session,30000)) {
                // Unknown asset length: read through EOF. A failed partial helper is still registered for cleanup.
                sync.push(helper,input,-1,0600,epoch(),(done,total)->!Thread.currentThread().isInterrupted());
            }
            // Read-only dex also satisfies modern Android dynamic-code-loading requirements.
            requireSuccess(run("chmod " + DeviceCommands.quote("0400") + " " + DeviceCommands.quote(helper),10000));
        }
        public long push(File file,long bytes,String stage,SyncClient.Progress progress) throws IOException {
            try (InputStream input=new FileInputStream(file); SyncClient sync=new SyncClient(session,60000)) {
                return sync.push(stage,input,bytes,0600,epoch(),progress);
            }
        }
        public long pull(String remote,OutputStream output,long bytes,SyncClient.Progress progress) throws IOException {
            try (SyncClient sync=new SyncClient(session,60000)) { return sync.pull(remote,output,bytes,progress); }
        }
        public Fingerprint hash(String helper,String remote) throws IOException {
            return runHelper(helper,"hash",remote);
        }
        public Fingerprint commit(String helper,String stage,String destination,Fingerprint expected) throws IOException {
            return runHelper(helper,"commit",stage,destination,expected.sha256,Long.toString(expected.bytes));
        }
        private Fingerprint runHelper(String helper,String... args) throws IOException {
            StringBuilder command=new StringBuilder("CLASSPATH=").append(DeviceCommands.quote(helper))
                    .append(' ').append(DeviceCommands.quote("app_process")).append(' ').append(DeviceCommands.quote("/"))
                    .append(' ').append(DeviceCommands.quote("io.github.devicebridge.server.FileCommitServer"));
            for (String arg:args) command.append(' ').append(DeviceCommands.quote(arg));
            Reply result=run(command.toString(),120000);
            try {
                JSONObject found=null;
                for (String line:result.output.split("\\r?\\n")) {
                    if (!line.startsWith("{")) continue;
                    JSONObject json=new JSONObject(line);
                    if (json.optInt("gbft",0)==1) {
                        if (found!=null) throw new IOException("Ambiguous helper reply");
                        found=json;
                    }
                }
                if (found==null) throw new IOException("No verified helper acknowledgement: "+result.error);
                if (!found.getBoolean("ok")) {
                    Outcome outcome;
                    try { outcome=Outcome.valueOf(found.getString("outcome")); }
                    catch (Exception invalid) { outcome=Outcome.UNKNOWN; }
                    throw new CommitException(found.optString("error","Remote verification failed"),outcome);
                }
                requireSuccess(result);
                return new Fingerprint(found.getLong("bytes"),found.getString("sha256"),found.getString("snapshot"));
            } catch (IOException e) { throw e; }
              catch (Exception e) { throw new IOException("Invalid helper acknowledgement",e); }
        }
        public void removeFile(String path) throws IOException {
            requireSuccess(run("rm -f "+DeviceCommands.quote(path),5000));
        }
        public void removeDirectory(String path) throws IOException {
            // Never recursively delete. Only our registered helper and payload may be removed.
            requireSuccess(run("rmdir "+DeviceCommands.quote(path),5000));
        }
        public void cleanupArtifacts(String directory, String helper) throws IOException {
            runHelper(helper, "cleanup", directory);
        }
        private Reply run(String command, int timeout) throws IOException {
            ShellClient.Result result = shell.run(command, timeout);
            return new Reply(result.text(), result.error(), result.exitCode);
        }
        private static void requireSuccess(Reply result) throws IOException {
            if (result.exitCode != 0) throw new IOException("Device command failed ("+result.exitCode+"): "+result.output+result.error);
        }
        private static final class Reply {
            final String output, error; final int exitCode;
            Reply(String output, String error, int exitCode) { this.output = output; this.error = error; this.exitCode = exitCode; }
        }
        private static long epoch() { return Math.min(0xffffffffL,Math.max(0,System.currentTimeMillis()/1000)); }
    }
}
