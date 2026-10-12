package io.github.devicebridge.server;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import io.github.devicebridge.core.AndroidFileAccess;

/** Independently authored one-shot ADB helper. Does not use an original app's code/assets.
 * Final-name exclusion is the kernel's O_EXCL operation, never shell test/rename heuristics.
 * There is no atomic visibility guarantee while copying: other readers may see a partial new
 * file. Failures after creation retain that possibly partial target instead of risking a
 * lstat-to-unlink race deleting another process's replacement. No overwrite is
 * attempted and the client must label a lost acknowledgement UNKNOWN, not safe-to-retry.
 */
public final class FileCommitServer {
    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        int exit = 1;
        try {
            result.put("gbft", 1);
            Receipt receipt;
            if (android.os.Build.VERSION.SDK_INT < 19) throw new IOException("Verified file transfer helper requires Android 4.4 (API 19) or newer");
            Fs fs = android.os.Build.VERSION.SDK_INT >= 21 ? new AndroidFs() : new LegacyAndroidFs();
            if (args.length == 2 && "cleanup".equals(args[0])) receipt = cleanupArtifacts(args[1]);
            else if (args.length == 2 && "cleanup-screen".equals(args[0])) receipt = cleanupScreenArtifacts(args[1]);
            else if (args.length == 2 && "hash".equals(args[0])) receipt = hash(fs, path(args[1]));
            else if (args.length == 5 && "commit".equals(args[0])) {
                String stage = path(args[1]);
                if (!stage.matches("/data/local/tmp/goldenbeetle-transfer-[0-9a-f-]{36}/payload"))
                    throw new IOException("Unowned staging path");
                receipt = commit(fs, stage, path(args[2]), args[3], Long.parseLong(args[4]));
            } else throw new IOException("Expected hash path, or commit stage destination sha256 bytes");
            result.put("ok", true).put("bytes", receipt.bytes).put("sha256", receipt.sha256)
                    .put("snapshot", receipt.snapshot);
            exit = 0;
        } catch (Exception e) {
            try {
                result.put("gbft", 1).put("ok", false).put("error", e.getMessage())
                        .put("outcome", e instanceof CommitFailure ? ((CommitFailure)e).outcome : "NOT_COMMITTED");
            } catch (Exception ignored) { }
        }
        System.out.println(result.toString());
        System.out.flush();
        System.exit(exit);
    }

    static final class Receipt {
        final long bytes; final String sha256, snapshot;
        Receipt(long bytes, String sha256, String snapshot) { this.bytes=bytes; this.sha256=sha256; this.snapshot=snapshot; }
    }
    /** Whitelisted runtime byproducts only; descriptor anchors prevent directory-link traversal. */
    private static Receipt cleanupArtifacts(String workspace) throws IOException {
        if (!workspace.matches("/data/local/tmp/goldenbeetle-(transfer|metadata)-[0-9a-f-]{36}"))
            throw new IOException("Unowned cleanup workspace");
        return cleanupArtifacts(workspace, false);
    }
    private static Receipt cleanupScreenArtifacts(String workspace) throws IOException {
        if (!workspace.matches("/data/local/tmp/goldenbeetle-screen-[0-9a-f-]{36}"))
            throw new IOException("Unowned screen cleanup workspace");
        return cleanupArtifacts(workspace, true);
    }
    private static Receipt cleanupArtifacts(String workspace, boolean screen) throws IOException {
        String[] stems = screen ? new String[]{"metadata-server", "scrcpy-server"} : new String[]{"metadata-server"};
        try (CleanupDirectory root = new CleanupDirectory(workspace)) {
            if (root.exists("oat")) {
                try (CleanupDirectory oat = root.directory("oat")) {
                    for (String abi : new String[]{"arm", "arm64", "x86", "x86_64"}) {
                        if (!oat.exists(abi)) continue;
                        try (CleanupDirectory arch = oat.directory(abi)) {
                            for (String stem : stems) for (String extension : new String[]{"odex", "vdex", "art"}) {
                                String name = stem + "." + extension;
                                if (arch.exists(name)) arch.remove(name);
                            }
                        }
                        oat.remove(abi); // Empty directories only; unknown files cause an error.
                    }
                }
                root.remove("oat");
            }
            // All runtime classes are already mapped. Keep the separate cleanup jar until
            // artifact deletion succeeds; unlink it last, through this same directory FD.
            if (screen) {
                if (root.exists("scrcpy-server.jar")) root.remove("scrcpy-server.jar");
                if (root.exists("metadata-server.jar")) root.remove("metadata-server.jar");
            }
        }
        return new Receipt(0, hex(digest().digest()), "runtime-artifacts-cleaned");
    }
    private static final class CleanupDirectory implements Closeable {
        private final AndroidFileAccess fs = new AndroidFileAccess();
        private final android.os.ParcelFileDescriptor directory;
        CleanupDirectory(String path) throws IOException {
            FileDescriptor fd = fs.openDirectory(path);
            try {
                AndroidFileAccess.Stat opened = fs.fstat(fd);
                if (!opened.directoryToken().equals(fs.lstat(path).directoryToken())) throw new IOException("Cleanup directory replaced");
                directory = android.os.ParcelFileDescriptor.dup(fd);
            } finally { fs.close(fd); }
        }
        String path(String name) throws IOException {
            if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals("..")) throw new IOException("Invalid cleanup name");
            return "/proc/self/fd/" + directory.getFd() + "/" + name;
        }
        boolean exists(String name) throws IOException {
            try { fs.lstat(path(name)); return true; } catch (FileNotFoundException absent) { return false; }
        }
        CleanupDirectory directory(String name) throws IOException { return new CleanupDirectory(path(name)); }
        void remove(String name) throws IOException { fs.remove(path(name)); }
        public void close() throws IOException { directory.close(); }
    }
    static final class CommitFailure extends IOException {
        final String outcome;
        CommitFailure(IOException cause, String outcome, String cleanup) {
            super(cause.getMessage() + (cleanup.isEmpty() ? "" : "; cleanup: " + cleanup), cause); this.outcome = outcome;
        }
    }
    /** Portable metadata/FS boundary exists solely so ownership races can be tested on the host. */
    static final class Stat {
        final long dev, ino, size; final boolean regular; final String revision;
        Stat(long dev,long ino,long size,boolean regular,String revision) {
            this.dev=dev; this.ino=ino; this.size=size; this.regular=regular; this.revision=revision;
        }
        boolean sameFile(Stat other) { return other != null && dev == other.dev && ino == other.ino; }
        String token() { return dev+":"+ino+":"+size+":"+regular+":"+revision; }
    }
    interface Fs {
        FileDescriptor readOnly(String path) throws IOException;
        FileDescriptor createExclusive(String path) throws IOException;
        Stat fstat(FileDescriptor fd) throws IOException;
        Stat lstat(String path) throws IOException;
        int read(FileDescriptor fd, byte[] data) throws IOException;
        int write(FileDescriptor fd, byte[] data, int off, int count) throws IOException;
        void fsync(FileDescriptor fd) throws IOException;
        void close(FileDescriptor fd) throws IOException;
    }

    /** Hash an opened, regular, non-symlink file; reject replacement or mutation while hashing. */
    static Receipt hash(Fs fs, String source) throws IOException {
        FileDescriptor fd = fs.readOnly(source);
        try {
            Stat before = fs.fstat(fd);
            regular(before);
            if (!before.sameFile(fs.lstat(source))) throw new IOException("Source replaced before hashing");
            MessageDigest digest = digest();
            long bytes = 0; byte[] data = new byte[65536]; int n;
            while ((n = fs.read(fd, data)) != -1) {
                if (n == 0) throw new IOException("Source made no progress");
                bytes += n; digest.update(data,0,n);
            }
            stable(fs, fd, source, before, bytes);
            return new Receipt(bytes, hex(digest.digest()), before.token());
        } finally { fs.close(fd); }
    }

    /** Kernel-exclusive creation plus verified copy and fsync. Failed final copies are retained. */
    static Receipt commit(Fs fs, String stage, String destination, String expectedSha, long expectedBytes) throws IOException {
        return commit(fs, stage, destination, expectedSha, expectedBytes, null);
    }
    /** A copy plan may additionally bind the source's already-inspected identity/revision. */
    static Receipt commit(Fs fs, String stage, String destination, String expectedSha, long expectedBytes, String expectedSnapshot) throws IOException {
        path(stage); path(destination);
        if (stage.equals(destination) || expectedBytes < 0 || expectedSha == null || !expectedSha.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid commit arguments");
        Receipt staged = hash(fs, stage);
        if (staged.bytes != expectedBytes || !staged.sha256.equals(expectedSha)
                || (expectedSnapshot != null && !expectedSnapshot.equals(staged.snapshot)))
            throw new CommitFailure(new IOException("Staged SHA-256/length mismatch"), "NOT_COMMITTED", "");
        FileDescriptor input = null, output = null;
        Stat owned = null;
        Receipt receipt = null;
        IOException failure = null;
        boolean created = false;
        try {
            input = fs.readOnly(stage);
            Stat before = fs.fstat(input);
            regular(before);
            if (!staged.snapshot.equals(before.token()) || !before.sameFile(fs.lstat(stage)))
                throw new IOException("Staged source changed before commit");
            output = fs.createExclusive(destination); // O_CREAT|O_EXCL at the ACTUAL final path.
            created = true;
            owned = fs.fstat(output); // Identity used to validate the completed destination, never to authorize path deletion.
            regular(owned);
            MessageDigest sha = digest(); long bytes = 0;
            byte[] buffer = new byte[65536]; int n;
            while ((n = fs.read(input, buffer)) != -1) {
                if (n == 0 || n > expectedBytes - bytes) throw new IOException("Staged source size changed");
                int offset = 0;
                while (offset < n) {
                    int written = fs.write(output, buffer, offset, n-offset);
                    if (written <= 0 || written > n-offset) throw new IOException("Destination made no write progress");
                    offset += written;
                }
                sha.update(buffer,0,n); bytes += n;
            }
            String actual = hex(sha.digest());
            if (bytes != expectedBytes || !actual.equals(expectedSha)) throw new IOException("Copy SHA-256/length mismatch");
            stable(fs, input, stage, before, bytes);
            fs.fsync(output);
            Stat finalFd = fs.fstat(output), finalPath = fs.lstat(destination);
            if (!owned.sameFile(finalFd) || !owned.sameFile(finalPath) || finalFd.size != expectedBytes || !finalPath.regular)
                throw new IOException("Destination replaced or changed during commit");
            Receipt persisted = hash(fs,destination);
            if (!persisted.sha256.equals(expectedSha) || persisted.bytes != expectedBytes
                    || !persisted.snapshot.equals(finalFd.token()) || !finalFd.token().equals(fs.fstat(output).token()))
                throw new IOException("Persisted destination SHA-256/identity mismatch");
            receipt = new Receipt(bytes, actual, finalFd.token());
        } catch (IOException e) { failure=e; }
          catch (RuntimeException e) { failure=new IOException("Unexpected commit failure",e); }
        finally {
            if (output != null) try { fs.close(output); } catch (IOException e) { if (failure == null) failure=e; else failure.addSuppressed(e); }
            if (input != null) try { fs.close(input); } catch (IOException e) { if (failure == null) failure=e; else failure.addSuppressed(e); }
        }
        if (failure == null) return receipt;
        // There is no atomic "unlink only if inode still matches" Android path API.
        // Even lstat(dev,ino)==owned followed by unlink has a replacement race. Never delete
        // from a shared final directory on rollback; report the possible partial target.
        String cleanup = created ? "New destination may be partial; retained for inspection: " + destination : "";
        String outcome = created ? "UNKNOWN" : "NOT_COMMITTED";
        throw new CommitFailure(failure,outcome,cleanup);
    }
    private static void stable(Fs fs,FileDescriptor fd,String path,Stat before,long bytes) throws IOException {
        Stat after = fs.fstat(fd), present = fs.lstat(path);
        if (bytes != before.size || !before.token().equals(after.token()) || !before.token().equals(present.token()))
            throw new IOException("Source changed during transfer/hash");
    }
    private static void regular(Stat stat) throws IOException { if (!stat.regular) throw new IOException("Only regular files are supported"); }
    private static String path(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.equals("/") || path.indexOf('\0') >= 0)
            throw new IOException("Invalid absolute file path");
        try {
            if (StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(path)).remaining() > 1024) throw new IOException("File path exceeds UTF-8 byte limit");
        } catch (CharacterCodingException e) { throw new IOException("Malformed Unicode file path",e); }
        return path;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(); for (byte v:bytes) b.append("0123456789abcdef".charAt((v&255)>>>4)).append("0123456789abcdef".charAt(v&15)); return b.toString();
    }
    /** Isolate API-27 fields so Android 8.0's verifier never resolves missing StructStat members. */
    private static final class Api27 {
        static String revision(StructStat s) {
            return s.st_mtim.tv_sec+":"+s.st_mtim.tv_nsec+":"+s.st_ctim.tv_sec+":"+s.st_ctim.tv_nsec;
        }
    }
    private static final class AndroidFs implements Fs {
        public FileDescriptor readOnly(String path) throws IOException {
            try { return Os.open(path,OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0); }
            catch (ErrnoException e) { throw io(e,path); }
        }
        public FileDescriptor createExclusive(String path) throws IOException {
            try { return Os.open(path,OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_WRONLY|OsConstants.O_CLOEXEC,0644); }
            catch (ErrnoException e) { throw io(e,path); }
        }
        public Stat fstat(FileDescriptor fd) throws IOException { try { return stat(Os.fstat(fd)); } catch (ErrnoException e) { throw io(e,""); } }
        public Stat lstat(String path) throws IOException { try { return stat(Os.lstat(path)); } catch (ErrnoException e) { throw io(e,path); } }
        public int read(FileDescriptor fd,byte[] data) throws IOException {
            try { int n=Os.read(fd,data,0,data.length); return n==0?-1:n; } catch (ErrnoException e) { throw io(e,""); }
        }
        public int write(FileDescriptor fd,byte[] data,int off,int count) throws IOException { try { return Os.write(fd,data,off,count); } catch (ErrnoException e) { throw io(e,""); } }
        public void fsync(FileDescriptor fd) throws IOException { try { Os.fsync(fd); } catch (ErrnoException e) { throw io(e,""); } }
        public void close(FileDescriptor fd) throws IOException { try { Os.close(fd); } catch (ErrnoException e) { throw io(e,""); } }
        private static Stat stat(StructStat s) {
            String revision = android.os.Build.VERSION.SDK_INT >= 27 ? Api27.revision(s) : s.st_mtime+":"+s.st_ctime;
            return new Stat(s.st_dev,s.st_ino,s.st_size,OsConstants.S_ISREG(s.st_mode),revision);
        }
        private static IOException io(ErrnoException e,String path) {
            if (e.errno==OsConstants.ENOENT) return new FileNotFoundException(path+": "+e.getMessage());
            if (e.errno==OsConstants.EEXIST) return new IOException("Destination already exists: "+path);
            return new IOException(e.getMessage(),e);
        }
    }
    /** KitKat's libcore has the same kernel primitives, before their public API-21 move. */
    static final class LegacyAndroidFs implements Fs {
        private final AndroidFileAccess fs;
        LegacyAndroidFs() throws IOException { fs = new AndroidFileAccess(); }
        public FileDescriptor readOnly(String path) throws IOException { return fs.readOnly(path); }
        public FileDescriptor createExclusive(String path) throws IOException { return fs.createExclusive(path); }
        public Stat fstat(FileDescriptor fd) throws IOException { return convert(fs.fstat(fd)); }
        public Stat lstat(String path) throws IOException { return convert(fs.lstat(path)); }
        public int read(FileDescriptor fd, byte[] bytes) throws IOException { return fs.read(fd, bytes); }
        public int write(FileDescriptor fd, byte[] bytes, int offset, int count) throws IOException { return fs.write(fd, bytes, offset, count); }
        public void fsync(FileDescriptor fd) throws IOException { fs.fsync(fd); }
        public void close(FileDescriptor fd) throws IOException { fs.close(fd); }
        private Stat convert(AndroidFileAccess.Stat s) { return new Stat(s.dev, s.ino, s.size, s.regular, s.revision); }
    }
}
