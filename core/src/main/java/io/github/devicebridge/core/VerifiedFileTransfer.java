package io.github.devicebridge.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Independent verified-transfer orchestration. Blocking: run off the UI thread.
 * No retry is automatic. A failed download's caller must remove its own partial output.
 * Temporary paths are registered BEFORE any push so partial SEND failures are cleaned.
 */
public class VerifiedFileTransfer {
    public enum Outcome { NOT_COMMITTED, COMMITTED, UNKNOWN }
    public interface Progress extends SyncClient.Progress { }
    public static final class Report {
        public final long bytes;
        public final String sha256;
        public final List<String> cleanupWarnings;
        private Report(long bytes, String sha256, List<String> warnings) {
            this.bytes = bytes; this.sha256 = sha256;
            this.cleanupWarnings = immutable(warnings);
        }
    }
    /** UNKNOWN means the final command may have run: inspect the destination, never blindly retry.
     * COMMITTED on cancellation means a verified commit acknowledgement was already received.
     */
    public static final class TransferException extends IOException {
        public final boolean cancelled;
        public final Outcome outcome;
        public final List<String> cleanupWarnings;
        public final String remotePath;
        TransferException(String remote, Throwable cause, Outcome outcome, List<String> warnings) {
            super((isCancelled(cause) ? "Transfer cancelled" : "Transfer failed") + ": "
                    + cause.getMessage() + " [" + outcome + "]"
                    + (warnings.isEmpty() ? "" : "; cleanup: " + warnings), cause);
            this.cancelled = isCancelled(cause); this.outcome = outcome;
            this.cleanupWarnings = immutable(warnings); this.remotePath = remote;
        }
    }
    /** Explicit error acknowledgement from the helper, distinct from a lost connection. */
    public static final class CommitException extends IOException {
        public final Outcome outcome;
        public CommitException(String message, Outcome outcome) { super(message); this.outcome = outcome; }
    }
    public static final class Fingerprint {
        public final long bytes;
        public final String sha256, snapshot;
        public Fingerprint(long bytes, String sha256, String snapshot) throws IOException {
            if (bytes < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                    || snapshot == null || snapshot.isEmpty()) throw new IOException("Invalid digest response");
            this.bytes = bytes; this.sha256 = sha256; this.snapshot = snapshot;
        }
    }
    /** Narrow injectable device adapter; cleanup methods MUST have bounded timeouts. */
    public interface Backend {
        void reserveDirectory(String directory) throws IOException;
        void deployHelper(String remoteHelper) throws IOException;
        long push(File local, long bytes, String stage, SyncClient.Progress progress) throws IOException;
        long pull(String remote, OutputStream output, long bytes, SyncClient.Progress progress) throws IOException;
        Fingerprint hash(String helper, String remote) throws IOException;
        Fingerprint commit(String helper, String stage, String destination, Fingerprint expected) throws IOException;
        void removeFile(String path) throws IOException;
        void removeDirectory(String path) throws IOException;
        default void cleanupArtifacts(String directory, String helper) throws IOException { }
    }
    private final Backend backend;
    protected VerifiedFileTransfer(Backend backend) {
        if (backend == null) throw new NullPointerException("backend"); this.backend = backend;
    }

    /** Creates a new destination atomically with O_EXCL; never replaces an existing path.
     * Validates the local source both before and after sending and the staged bytes before commit.
     * Caller owns the local file. Cleanup warnings do not invalidate an acknowledged verified copy.
     */
    public final Report upload(File local, String remote, SyncClient.Progress progress) throws IOException {
        validatePath(remote);
        Workspace work = new Workspace();
        Fingerprint expected = null;
        Outcome outcome = Outcome.NOT_COMMITTED;
        Throwable failure = null;
        try {
            checkCancelled();
            expected = fingerprint(local);
            work.open();
            String stage = work.directory + "/payload";
            work.files.add(stage); // Before SEND, including cancelled/partial SEND.
            long sent = backend.push(local, expected.bytes, stage, progress);
            if (sent != expected.bytes) throw new IOException("Incomplete staged upload");
            Fingerprint after = fingerprint(local);
            requireSame(expected, after, true);
            checkCancelled();
            outcome = Outcome.UNKNOWN; // From this point, a lost reply must not imply rollback.
            try {
                Fingerprint committed = backend.commit(work.helper, stage, remote, expected);
                requireSame(expected, committed, false);
                outcome = Outcome.COMMITTED;
            } catch (CommitException e) { outcome = e.outcome; throw e; }
            checkCancelled();
        } catch (IOException | RuntimeException e) { failure = e; }
        finally { work.cleanup(); }
        if (failure == null && Thread.currentThread().isInterrupted()) failure = new InterruptedIOException("Cancelled after verified transfer; cleanup was attempted");
        if (failure != null) throw new TransferException(remote, failure, outcome, work.warnings);
        return new Report(expected.bytes, expected.sha256, work.warnings);
    }

    /** Leaves output open. Pre/post stable helper hashes and the received SHA-256 must all match.
     * On ANY exception the caller must remove its newly created partial local/SAF file; no success
     * is returned for a changed source, digest mismatch, interrupted transfer, or uncertain result.
     */
    public final Report download(String remote, OutputStream output, SyncClient.Progress progress) throws IOException {
        validatePath(remote);
        if (output == null) throw new NullPointerException("output");
        Workspace work = new Workspace();
        Fingerprint before = null;
        Outcome outcome = Outcome.NOT_COMMITTED;
        Throwable failure = null;
        try {
            checkCancelled(); work.open();
            before = backend.hash(work.helper, remote);
            MessageDigest digest = sha256();
            CountingOutputStream counted = new CountingOutputStream(output, digest);
            long copied = backend.pull(remote, counted, before.bytes, progress);
            checkCancelled();
            Fingerprint after = backend.hash(work.helper, remote);
            requireSame(before, after, true);
            if (copied != counted.count || copied != before.bytes
                    || !before.sha256.equals(hex(digest.digest())))
                throw new IOException("Downloaded bytes do not match the device SHA-256");
            output.flush(); checkCancelled();
            outcome = Outcome.COMMITTED;
        } catch (IOException | RuntimeException e) { failure = e; }
        finally { work.cleanup(); }
        if (failure == null && Thread.currentThread().isInterrupted()) failure = new InterruptedIOException("Cancelled after verified transfer; cleanup was attempted");
        if (failure != null) throw new TransferException(remote, failure, outcome, work.warnings);
        return new Report(before.bytes, before.sha256, work.warnings);
    }

    private final class Workspace {
        final String directory = "/data/local/tmp/goldenbeetle-transfer-" + UUID.randomUUID().toString();
        final String helper = directory + "/metadata-server.jar";
        final List<String> files = new ArrayList<String>(), warnings = new ArrayList<String>();
        boolean owned, reservationAttempted, helperReady;
        void open() throws IOException {
            checkCancelled();
            reservationAttempted = true;
            backend.reserveDirectory(directory); // mkdir without -p: an existing directory is NOT ours.
            owned = true;
            files.add(helper); // Also track a partially pushed helper.
            backend.deployHelper(helper);
            helperReady = true;
            checkCancelled();
        }
        void cleanup() {
            // Cancellation does not prevent bounded cleanup, but its interrupt is always restored.
            boolean interrupted = Thread.interrupted();
            try {
                if (!owned) {
                    if (reservationAttempted) warnings.add("Temporary-directory creation was not acknowledged; ownership unconfirmed: " + directory);
                    return;
                }
                if (helperReady) {
                    try { backend.cleanupArtifacts(directory, helper); }
                    catch (IOException | RuntimeException e) { warnings.add("Runtime artifacts in " + directory + ": " + e.getMessage()); }
                    interrupted |= Thread.interrupted();
                }
                for (int i = files.size() - 1; i >= 0; i--) {
                    try { backend.removeFile(files.get(i)); }
                    catch (IOException | RuntimeException e) { warnings.add(files.get(i) + ": " + e.getMessage()); }
                    interrupted |= Thread.interrupted();
                }
                try { backend.removeDirectory(directory); }
                catch (IOException | RuntimeException e) { warnings.add(directory + ": " + e.getMessage()); }
            } finally {
                interrupted |= Thread.interrupted();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Strict UTF-8 and SYNC byte limit; literal Unicode, quotes and newlines remain valid data. */
    public static String validatePath(String path) throws IOException {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0 || path.equals("/"))
            throw new IOException("File path must be absolute, non-root, and contain no NUL");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(path));
            if (bytes.remaining() > SyncClient.MAX_PATH_BYTES) throw new IOException("Remote path exceeds SYNC UTF-8 byte limit");
        } catch (CharacterCodingException e) { throw new IOException("Malformed Unicode in remote path", e); }
        return path;
    }
    private static Fingerprint fingerprint(File file) throws IOException {
        if (file == null) throw new IOException("Missing local source");
        // The desugared NIO provider on old Android cannot guarantee NOFOLLOW/inode metadata.
        // Use the actual kernel operations there; retain the JVM NIO implementation for tests.
        if (AndroidFileAccess.isAndroid()) return androidFingerprint(file);
        Path path = file.toPath();
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Local source must be a regular file");
        MessageDigest digest = sha256(); long total = 0;
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = new byte[64 * 1024]; int n;
            while ((n = in.read(data)) != -1) { checkCancelled(); digest.update(data, 0, n); total += n; }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String snapshot = localSnapshot(before);
        if (total != before.size() || !snapshot.equals(localSnapshot(after)))
            throw new IOException("Local source changed during hashing");
        return new Fingerprint(total, hex(digest.digest()), snapshot);
    }
    private static Fingerprint androidFingerprint(File file) throws IOException {
        AndroidFileAccess fs = new AndroidFileAccess();
        String path = file.getAbsolutePath();
        FileDescriptor fd = fs.readOnly(path);
        try {
            AndroidFileAccess.Stat before = fs.fstat(fd);
            if (!before.regular || !before.token().equals(fs.lstat(path).token()))
                throw new IOException("Local source must be a stable regular file, not a symlink");
            MessageDigest digest = sha256(); long total = 0;
            byte[] bytes = new byte[65536]; int n;
            while ((n = fs.read(fd, bytes)) != -1) { checkCancelled(); digest.update(bytes, 0, n); total += n; }
            if (total != before.size || !before.token().equals(fs.fstat(fd).token())
                    || !before.token().equals(fs.lstat(path).token()))
                throw new IOException("Local source changed during hashing");
            return new Fingerprint(total, hex(digest.digest()), before.token());
        } finally { fs.close(fd); }
    }
    private static String localSnapshot(BasicFileAttributes stat) {
        return stat.fileKey() + ":" + stat.size() + ":" + stat.lastModifiedTime() + ":" + stat.creationTime() + ":" + stat.isRegularFile();
    }
    private static void requireSame(Fingerprint before, Fingerprint after, boolean snapshot) throws IOException {
        if (before.bytes != after.bytes || !before.sha256.equals(after.sha256)
                || (snapshot && !before.snapshot.equals(after.snapshot)))
            throw new IOException("Source changed during transfer or SHA-256 verification failed");
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    private static boolean isCancelled(Throwable e) {
        while (e != null) { if (e instanceof InterruptedIOException && !(e instanceof java.net.SocketTimeoutException)) return true; e = e.getCause(); }
        return Thread.currentThread().isInterrupted();
    }
    private static List<String> immutable(List<String> list) { return Collections.unmodifiableList(new ArrayList<String>(list)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2]; char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { chars[i * 2] = digits[(bytes[i] & 255) >>> 4]; chars[i * 2 + 1] = digits[bytes[i] & 15]; }
        return new String(chars);
    }
    private static final class CountingOutputStream extends OutputStream {
        final OutputStream output; final MessageDigest digest; long count;
        CountingOutputStream(OutputStream output, MessageDigest digest) { this.output = output; this.digest = digest; }
        public void write(int value) throws IOException { output.write(value); digest.update((byte) value); count++; }
        public void write(byte[] data, int offset, int length) throws IOException { output.write(data, offset, length); digest.update(data, offset, length); count += length; }
        public void flush() throws IOException { output.flush(); }
    }
}
