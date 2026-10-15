package io.github.devicebridge.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent synchronous Fastboot protocol client; no USB or Android dependency.
 * Source: https://android.googlesource.com/platform/system/core/+/refs/heads/main/fastboot/README.md
 * Implements the documented 4096-byte command / 256-byte status limits, while
 * accepting legacy 64-byte statuses. PacketTransport preserves status boundaries.
 *
 * <p>SAFETY CONTRACT: The caller must explicitly confirm the connected device,
 * partition, image identity and destructive/data-loss risk before flash/erase;
 * obtain authorization before download/boot/reboot too. This low-level API cannot
 * provide a UI confirmation and calling it is NOT a substitute for permission.
 * No unlock command, automatic retry, sparse-image expansion, partition inference
 * or flashing of additional partitions is performed. Never automatically retry a
 * failed destructive operation: the device may have partially completed it.
 *
 * <p>All methods block. close() may be called from another thread to abort I/O.
 * Cancellation or protocol/transport failure closes the transport. Device FAIL
 * is a terminal response and permits another explicitly requested command.
 */
public final class FastbootClient implements Closeable {
    public static final int MAX_COMMAND_BYTES = 4096;
    public static final int MAX_RESPONSE_BYTES = 256;
    public static final int DOWNLOAD_CHUNK_BYTES = 64 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 0xffffffffL;
    private static final int MAX_INTERMEDIATE_RESPONSES = 10000;

    /**
     * A packet is one command/status or data-phase segment. USB adapters must read
     * a complete status transfer (up to maxBytes), never join two status transfers,
     * and write all bytes or throw. TCP adapters provide the framing themselves.
     * A blocking read must have a finite timeout; close must unblock it.
     */
    public interface PacketTransport extends Closeable {
        byte[] readPacket(int maxBytes) throws IOException;
        void writePacket(byte[] bytes, int offset, int length) throws IOException;
        String description();
    }

    public interface Progress { boolean onProgress(long transferred, long total); }
    public interface MessageListener { void onMessage(Kind kind, String message); }
    public enum Kind { INFO, TEXT, OKAY, FAIL, DATA }

    public static final class Response {
        public final Kind kind;
        public final String message;
        /** The requested data length for DATA; -1 for other response types. */
        public final long dataSize;
        private Response(Kind kind, String message, long dataSize) {
            this.kind = kind; this.message = message; this.dataSize = dataSize;
        }
    }

    public static final class RemoteFailureException extends IOException {
        private static final long serialVersionUID = 1L;
        public RemoteFailureException(String message) { super("Fastboot failed: " + message); }
    }

    private final PacketTransport transport;
    private final MessageListener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean operationInProgress;
    private boolean downloaded;
    private long downloadedSize;

    public FastbootClient(PacketTransport transport) { this(transport, null); }
    public FastbootClient(PacketTransport transport, MessageListener listener) {
        if (transport == null) throw new NullPointerException("transport");
        this.transport = transport; this.listener = listener;
    }

    public String target() { return transport.description(); }
    public boolean isClosed() { return closed.get(); }

    /** getvar:all emits values through MessageListener and often returns empty text. */
    public synchronized String getvar(String variable) throws IOException {
        validateParameter(variable, "variable", true);
        return execute("getvar:" + variable);
    }

    /** Read the two reference-app diagnostics, preserving partial results on terminal FAIL.
     * OEM device-info is a vendor query, not universally implemented. INFO/TEXT details
     * remain streamed through the listener. A transport failure aborts the second query.
     * Public implementation evidence: android.googlesource.com/kernel/lk/+/9d6e28f24ba01e563d0b3801c6c6c95d756db623/app/aboot/aboot.c
     */
    public synchronized String deviceInfo() throws IOException {
        StringBuilder result = new StringBuilder();
        for (String command : new String[]{"getvar:all", "oem device-info"}) {
            if (result.length() > 0) result.append('\n');
            result.append(command).append(": ");
            try {
                String reply = execute(command);
                result.append(reply.isEmpty() ? "OKAY（详细数据见上方输出）" : reply);
            } catch (RemoteFailureException unsupported) {
                result.append("设备未提供此查询：").append(unsupported.getMessage());
            }
        }
        return result.toString();
    }

    /**
     * Downloads exactly size bytes into device memory, without flashing them.
     * Does not close source or consume bytes beyond size. Short source, mismatched
     * DATA size, cancellation or malformed response aborts the transport.
     */
    public synchronized long download(InputStream source, long size, Progress progress) throws IOException {
        if (source == null) throw new NullPointerException("source");
        if (size < 0 || size > MAX_DOWNLOAD_BYTES) throw new IllegalArgumentException("Fastboot size must fit unsigned 32 bits");
        enterOperation();
        downloaded = false;
        try {
            ensureOpen();
            report(progress, 0, size);
            sendCommand(String.format(Locale.US, "download:%08x", size));
            Response ready = readTerminal();
            if (ready.kind == Kind.FAIL) throw new RemoteFailureException(ready.message);
            if (ready.kind != Kind.DATA || ready.dataSize != size)
                throw new IOException("Fastboot DATA size does not match requested download");
            long transferred = 0;
            byte[] buffer = new byte[DOWNLOAD_CHUNK_BYTES];
            while (transferred < size) {
                int wanted = (int) Math.min(buffer.length, size - transferred);
                int count = SyncClient.readSome(source, buffer, wanted);
                ensureOpen();
                if (count < 0) throw new EOFException("Source ended before declared Fastboot download size");
                transport.writePacket(buffer, 0, count);
                ensureOpen();
                transferred += count;
                report(progress, transferred, size);
            }
            expectOkay(readTerminal());
            ensureOpen();
            downloaded = true;
            downloadedSize = size;
            return transferred;
        } catch (RemoteFailureException e) { throw e; }
          catch (IOException e) { throw invalidate(e); }
          catch (RuntimeException e) { invalidateQuietly(); throw e; }
          finally { operationInProgress = false; }
    }

    public synchronized boolean hasDownloadedImage() { return downloaded && !closed.get(); }
    public synchronized long downloadedSize() { return hasDownloadedImage() ? downloadedSize : 0; }

    /** Requires explicit destructive-operation confirmation before invocation. */
    public synchronized String flash(String partition) throws IOException {
        validateParameter(partition, "partition", false);
        requireDownloadedImage();
        try { return execute("flash:" + partition); }
        finally { downloaded = false; }
    }

    /** Requires explicit destructive-operation confirmation before invocation. */
    public synchronized String erase(String partition) throws IOException {
        validateParameter(partition, "partition", false);
        downloaded = false;
        return execute("erase:" + partition);
    }

    /** Boots the downloaded image; caller must obtain authorization first. */
    public synchronized String boot() throws IOException {
        requireDownloadedImage();
        try { return execute("boot"); }
        finally { downloaded = false; }
    }

    /** Reboots the named connected target; caller must obtain authorization first. */
    public synchronized String reboot() throws IOException {
        downloaded = false;
        return execute("reboot");
    }

    public synchronized String rebootBootloader() throws IOException {
        downloaded = false;
        return execute("reboot-bootloader");
    }

    /** Raw user-authored command. Caller must confirm its exact target and effects. */
    public synchronized String executeCommand(String command) throws IOException {
        if(command==null||command.length()==0||command.length()>MAX_COMMAND_BYTES)throw new IllegalArgumentException("Invalid command length");
        for(int i=0;i<command.length();i++)if(command.charAt(i)<32||command.charAt(i)>126)throw new IllegalArgumentException("Command must contain printable ASCII only");
        if(command.startsWith("download:")||command.equals("upload"))throw new IllegalArgumentException("Use the explicit image-transfer workflow");
        if(command.startsWith("flash:"))return flash(command.substring(6));
        if(command.equals("boot"))return boot();
        downloaded=false;return execute(command);
    }

    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) transport.close();
    }

    /** Strict, bounded parsing independent of transport, also useful in packet tests. */
    public static Response parseResponse(byte[] packet) throws IOException {
        if (packet == null || packet.length < 4 || packet.length > MAX_RESPONSE_BYTES)
            throw new IOException("Invalid Fastboot response length");
        String id = new String(packet, 0, 4, StandardCharsets.US_ASCII);
        Kind kind;
        try { kind = Kind.valueOf(id); }
        catch (IllegalArgumentException e) { throw new IOException("Unknown Fastboot response: " + id, e); }
        if (kind == Kind.DATA) {
            if (packet.length != 12) throw new IOException("Fastboot DATA response must contain eight hex digits");
            long length = 0;
            for (int i = 4; i < 12; i++) {
                int ch = packet[i] & 255;
                int digit = ch >= '0' && ch <= '9' ? ch - '0' :
                        ch >= 'a' && ch <= 'f' ? ch - 'a' + 10 :
                        ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
                if (digit < 0) throw new IOException("Invalid Fastboot DATA hexadecimal length");
                length = (length << 4) | digit;
            }
            return new Response(kind, "", length);
        }
        int end = packet.length;
        // TEXT is specified to be NUL terminated; never display its terminator.
        if (kind == Kind.TEXT) for (int i = 4; i < end; i++) if (packet[i] == 0) { end = i; break; }
        return new Response(kind, new String(packet, 4, end - 4, StandardCharsets.UTF_8), -1);
    }

    private String execute(String command) throws IOException {
        enterOperation();
        try {
            ensureOpen();
            sendCommand(command);
            return expectOkay(readTerminal());
        } catch (RemoteFailureException e) { throw e; }
          catch (IOException e) { throw invalidate(e); }
          catch (RuntimeException e) { invalidateQuietly(); throw e; }
          finally { operationInProgress = false; }
    }

    private String expectOkay(Response response) throws IOException {
        if (response.kind == Kind.FAIL) throw new RemoteFailureException(response.message);
        if (response.kind != Kind.OKAY) throw new IOException("Unexpected Fastboot data phase");
        return response.message;
    }

    private void sendCommand(String command) throws IOException {
        byte[] bytes = command.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0 || bytes.length > MAX_COMMAND_BYTES)
            throw new IOException("Fastboot command length exceeded");
        ensureOpen();
        transport.writePacket(bytes, 0, bytes.length);
        ensureOpen();
    }

    private Response readTerminal() throws IOException {
        for (int count = 0; count < MAX_INTERMEDIATE_RESPONSES; count++) {
            ensureOpen();
            byte[] packet = transport.readPacket(MAX_RESPONSE_BYTES);
            // A transport may return buffered bytes after close or interruption.
            // Do not report success, retain a reusable FAIL, or deliver INFO after cancellation.
            ensureOpen();
            Response response = parseResponse(packet);
            if (response.kind != Kind.INFO && response.kind != Kind.TEXT) return response;
            if (listener != null) listener.onMessage(response.kind, response.message);
        }
        throw new IOException("Fastboot informational response limit exceeded");
    }

    private static void validateParameter(String parameter, String description, boolean allowColon) {
        if (parameter == null || parameter.isEmpty()) throw new IllegalArgumentException("Empty Fastboot " + description);
        for (int i = 0; i < parameter.length(); i++) {
            char ch = parameter.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                  (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.' ||
                  (allowColon && ch == ':')))
                throw new IllegalArgumentException("Invalid Fastboot " + description);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("Fastboot transport is closed");
        SyncClient.checkInterrupted();
    }
    private void enterOperation() {
        if (operationInProgress) throw new IllegalStateException("Do not issue Fastboot commands inside callbacks");
        operationInProgress = true;
    }
    private void requireDownloadedImage() throws IOException {
        ensureOpen();
        if (!downloaded) throw new IOException("Download an explicitly selected image before flash or boot");
    }
    private void report(Progress progress, long transferred, long total) throws IOException {
        ensureOpen();
        if (progress != null && !progress.onProgress(transferred, total))
            throw new InterruptedIOException("Fastboot download cancelled; reconnect before continuing");
        ensureOpen();
    }
    private IOException invalidate(IOException failure) {
        downloaded = false;
        try { close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        return failure;
    }
    private void invalidateQuietly() {
        downloaded = false;
        try { close(); } catch (IOException ignored) { }
    }
}
