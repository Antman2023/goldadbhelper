package io.github.devicebridge.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent, uncompressed ADB SYNC v1 client. All operations are blocking; call
 * them off the UI thread. Close from another thread to interrupt a blocked ADB
 * read. Caller-owned file streams are never closed. This client owns its SYNC
 * channel and becomes unusable after a failed/cancelled operation.
 *
 * <p>Wire reference: https://android.googlesource.com/platform/system/core.git/+/979f3d0116ee62b1461c94899ad7d82d2c00cd3e/adb/SYNC.TXT
 * <p>LIST's DONE is a complete 20-byte dent, not an 8-byte data header:
 * https://android.googlesource.com/platform/system/core/+/6acb240c533e405e8793537b2aef0d7ee3f527e0%5E2..6acb240c533e405e8793537b2aef0d7ee3f527e0/
 * <p>v1 stat/list size and timestamps are unsigned 32-bit values. Large-file
 * metadata may therefore be truncated by the server. v1 LIST also cannot reliably
 * distinguish an unreadable directory from an empty one. No recursive copy or
 * deletion is performed. A failed push can leave a partial remote file; callers
 * must obtain overwrite authorization and should stage/rename when appropriate.
 */
public final class SyncClient implements Closeable {
    public static final int MAX_DATA_BYTES = 64 * 1024;
    public static final int MAX_PATH_BYTES = 1024;
    private static final int MAX_NAME_BYTES = 255;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final int MAX_ENTRIES = 100000;
    private static final long UINT32_MAX = 0xffffffffL;
    private final InputStream input;
    private final OutputStream output;
    private final Closeable channel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean operationInProgress;

    /** Return false to cancel. total is -1 when not known. Runs on the caller's thread. */
    public interface Progress { boolean onProgress(long transferred, long total); }

    public static final class Entry {
        public final String path;
        public final String name;
        public final int mode;
        public final long size;
        /** Seconds since the Unix epoch (not milliseconds). */
        public final long mtime;
        public final boolean isDirectory;

        private Entry(String path, String name, int mode, long size, long mtime) {
            this.path = path;
            this.name = name;
            this.mode = mode;
            this.size = size;
            this.mtime = mtime;
            this.isDirectory = (mode & 0170000) == 0040000;
        }

        public boolean isSymbolicLink() { return (mode & 0170000) == 0120000; }
    }

    /** Local descriptor only; does not assert the remote path exists. */
    public static Entry syntheticEntry(String path,int mode,long size,long mtime) {
        if(path==null||!path.startsWith("/")||path.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid path");
        return new Entry(path,path.substring(path.lastIndexOf('/')+1),mode,size,mtime);
    }

    public SyncClient(AdbSession session, int timeoutMs) throws IOException {
        if (session == null) throw new NullPointerException("session");
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        AdbStream stream = session.open("sync:", timeoutMs);
        input = stream.input();
        output = stream.output();
        channel = stream;
    }

    /** Already-open SYNC channel constructor, useful for adapters and synthetic tests. */
    public SyncClient(final InputStream input, final OutputStream output) {
        if (input == null || output == null) throw new NullPointerException("SYNC streams");
        this.input = input;
        this.output = output;
        channel = new Closeable() {
            public void close() throws IOException {
                try { input.close(); } finally { output.close(); }
            }
        };
    }

    /** Excludes the synthetic dot and dot-dot directory entries. */
    public synchronized List<Entry> list(String remotePath) throws IOException {
        byte[] path = pathBytes(remotePath);
        enterOperation();
        try {
            ensureOpen();
            request("LIST", path);
            List<Entry> entries = new ArrayList<Entry>();
            int received = 0;
            while (true) {
                String id = readId();
                if ("FAIL".equals(id)) throw remoteFailure();
                if (!"DENT".equals(id) && !"DONE".equals(id)) throw unexpected(id);
                int mode = (int) readUint32();
                long size = readUint32();
                long mtime = readUint32();
                long nameLength = readUint32();
                if ("DONE".equals(id)) {
                    if (nameLength != 0) throw new IOException("Invalid SYNC LIST terminator");
                    ensureOpen();
                    return Collections.unmodifiableList(entries);
                }
                if (++received > MAX_ENTRIES) throw new IOException("SYNC directory entry limit exceeded");
                int length = boundedLength(nameLength, MAX_NAME_BYTES, "file name");
                if (length == 0) throw new IOException("Empty SYNC file name");
                String name = decodeUtf8(readExactly(input, length));
                if (name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
                    throw new IOException("Unsafe SYNC directory entry name");
                if (".".equals(name) || "..".equals(name)) continue;
                String fullPath = remotePath + (remotePath.endsWith("/") ? "" : "/") + name;
                entries.add(new Entry(fullPath, name, mode, size, mtime));
            }
        } catch (IOException e) { throw invalidate(e); }
          catch (RuntimeException e) { invalidateQuietly(); throw e; }
          finally { operationInProgress = false; }
    }

    /** Legacy STAT is lstat-like: symbolic-link metadata describes the link. */
    public synchronized Entry stat(String remotePath) throws IOException {
        byte[] path = pathBytes(remotePath);
        enterOperation();
        try {
            ensureOpen();
            request("STAT", path);
            String id = readId();
            if ("FAIL".equals(id)) throw remoteFailure();
            if (!"STAT".equals(id)) throw unexpected(id);
            int mode = (int) readUint32();
            long size = readUint32();
            long mtime = readUint32();
            if (mode == 0) throw new FileNotFoundException("Remote path is missing or inaccessible: " + remotePath);
            String cleaned = remotePath;
            while (cleaned.length() > 1 && cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
            String name = cleaned.substring(cleaned.lastIndexOf('/') + 1);
            ensureOpen();
            return new Entry(remotePath, name, mode, size, mtime);
        } catch (IOException e) { throw invalidate(e); }
          catch (RuntimeException e) { invalidateQuietly(); throw e; }
          finally { operationInProgress = false; }
    }

    public long pull(String remotePath, OutputStream destination, Progress progress) throws IOException {
        return pull(remotePath, destination, -1, progress);
    }

    /** expectedSize is a strict length check, or -1 for unknown (recommended for v1 large files). */
    public synchronized long pull(String remotePath, OutputStream destination, long expectedSize,
                                  Progress progress) throws IOException {
        if (destination == null) throw new NullPointerException("destination");
        if (expectedSize < -1) throw new IllegalArgumentException("Invalid expected size");
        byte[] path = pathBytes(remotePath);
        enterOperation();
        try {
            ensureOpen();
            report(progress, 0, expectedSize);
            request("RECV", path);
            long transferred = 0;
            int emptyChunks = 0;
            while (true) {
                String id = readId();
                if ("FAIL".equals(id)) throw remoteFailure();
                long length = readUint32();
                if ("DONE".equals(id)) {
                    if (expectedSize >= 0 && transferred != expectedSize)
                        throw new EOFException("SYNC file length differs from expected size");
                    ensureOpen();
                    destination.flush();
                    ensureOpen();
                    return transferred;
                }
                if (!"DATA".equals(id)) throw unexpected(id);
                int count = boundedLength(length, MAX_DATA_BYTES, "data");
                if (count == 0) {
                    if (++emptyChunks > 1024) throw new IOException("SYNC stream made no transfer progress");
                } else { emptyChunks = 0; }
                if (transferred > Long.MAX_VALUE - count ||
                    (expectedSize >= 0 && count > expectedSize - transferred))
                    throw new IOException("SYNC file exceeds expected size");
                byte[] data = readExactly(input, count);
                ensureOpen();
                destination.write(data);
                transferred += count;
                report(progress, transferred, expectedSize);
            }
        } catch (IOException e) { throw invalidate(e); }
          catch (RuntimeException e) { invalidateQuietly(); throw e; }
          finally { operationInProgress = false; }
    }

    /**
     * Pushes exactly size bytes, or reads to EOF when size == -1. Leaves excess
     * source bytes unread. mode is a regular-file mode or Unix permission bits;
     * mtime is unsigned 32-bit epoch seconds. Never closes source. A cancelled or
     * failed transfer is not rolled back and must not be retried automatically.
     */
    public synchronized long push(String remotePath, InputStream source, long size, int mode,
                                  long mtime, Progress progress) throws IOException {
        if (source == null) throw new NullPointerException("source");
        if (size < -1) throw new IllegalArgumentException("Invalid source size");
        if (mtime < 0 || mtime > UINT32_MAX) throw new IllegalArgumentException("mtime outside SYNC v1 range");
        if ((mode & ~07777) != 0 && (mode & ~07777) != 0100000)
            throw new IllegalArgumentException("Only regular-file modes are supported");
        pathBytes(remotePath);
        byte[] pathAndMode = pathBytes(remotePath + "," + mode);
        enterOperation();
        try {
            ensureOpen();
            report(progress, 0, size);
            request("SEND", pathAndMode);
            byte[] data = new byte[MAX_DATA_BYTES];
            long transferred = 0;
            while (size < 0 || transferred < size) {
                int wanted = size < 0 ? data.length : (int) Math.min(data.length, size - transferred);
                int count = readSome(source, data, wanted);
                if (count < 0) {
                    if (size >= 0) throw new EOFException("Source ended before declared SYNC file size");
                    break;
                }
                if (transferred > Long.MAX_VALUE - count) throw new IOException("SYNC byte count overflow");
                writeHeader("DATA", count);
                output.write(data, 0, count);
                output.flush();
                transferred += count;
                report(progress, transferred, size);
            }
            writeHeader("DONE", mtime);
            output.flush();
            String id = readId();
            if ("FAIL".equals(id)) throw remoteFailure();
            if (!"OKAY".equals(id)) throw unexpected(id);
            readUint32(); // SYNC v1 OKAY's second word is ignored by the specification.
            ensureOpen();
            return transferred;
        } catch (IOException e) { throw invalidate(e); }
          catch (RuntimeException e) { invalidateQuietly(); throw e; }
          finally { operationInProgress = false; }
    }

    public boolean isClosed() { return closed.get(); }

    /** Closing an AdbSession-backed client interrupts its blocked stream operation. */
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) channel.close();
    }

    private void request(String id, byte[] path) throws IOException {
        writeHeader(id, path.length);
        output.write(path);
        output.flush();
    }

    private void writeHeader(String id, long value) throws IOException {
        ensureOpen();
        byte[] header = new byte[8];
        for (int i = 0; i < 4; i++) {
            header[i] = (byte) id.charAt(i);
            header[i + 4] = (byte) (value >>> (8 * i));
        }
        output.write(header);
    }

    private String readId() throws IOException { return new String(readExactly(input, 4), StandardCharsets.US_ASCII); }
    private long readUint32() throws IOException {
        byte[] bytes = readExactly(input, 4);
        return (bytes[0] & 255L) | ((bytes[1] & 255L) << 8) |
               ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
    }

    private IOException remoteFailure() throws IOException {
        int length = boundedLength(readUint32(), MAX_ERROR_BYTES, "failure message");
        return new IOException("SYNC failed: " + decodeUtf8(readExactly(input, length)));
    }

    private static int boundedLength(long length, int maximum, String description) throws IOException {
        if (length > maximum) throw new IOException("Invalid SYNC " + description + " length: " + length);
        return (int) length;
    }

    private static byte[] pathBytes(String path) throws IOException {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Remote path must be nonempty and contain no NUL");
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(path));
        } catch (CharacterCodingException e) { throw new IOException("Invalid Unicode remote path", e); }
        if (encoded.remaining() > MAX_PATH_BYTES) throw new IOException("SYNC path exceeds 1024 UTF-8 bytes");
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static String decodeUtf8(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8 SYNC text", e); }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("SYNC channel is closed");
        checkInterrupted();
    }

    private void enterOperation() {
        if (operationInProgress) throw new IllegalStateException("Do not issue SYNC operations inside a progress callback");
        operationInProgress = true;
    }

    private void report(Progress progress, long transferred, long total) throws IOException {
        ensureOpen();
        if (progress != null && !progress.onProgress(transferred, total))
            throw new InterruptedIOException("SYNC transfer cancelled");
        ensureOpen(); // A callback may close the channel or interrupt its caller while returning true.
    }

    private IOException invalidate(IOException failure) {
        try { close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        return failure;
    }
    private void invalidateQuietly() { try { close(); } catch (IOException ignored) { } }
    private static IOException unexpected(String id) { return new IOException("Unexpected SYNC response: " + id); }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer interrupted");
    }

    /** Handles legal short reads and defensive zero returns without a busy loop. */
    static int readSome(InputStream stream, byte[] buffer, int length) throws IOException {
        checkInterrupted();
        if (length == 0) return 0;
        int count = stream.read(buffer, 0, length);
        checkInterrupted();
        if (count == 0) {
            checkInterrupted();
            int value = stream.read();
            checkInterrupted();
            if (value < 0) return -1;
            buffer[0] = (byte) value;
            return 1;
        }
        return count;
    }

    static byte[] readExactly(InputStream stream, int length) throws IOException {
        if (length < 0) throw new IOException("Negative protocol length");
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            checkInterrupted();
            int count = stream.read(bytes, offset, length - offset);
            checkInterrupted();
            if (count < 0) throw new EOFException("Truncated protocol stream");
            if (count == 0) {
                int value = stream.read();
                checkInterrupted();
                if (value < 0) throw new EOFException("Truncated protocol stream");
                bytes[offset++] = (byte) value;
            } else { offset += count; }
        }
        return bytes;
    }
}
