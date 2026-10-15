package io.github.devicebridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fastboot TCP v1: FB01 handshake and unsigned 8-byte big-endian packet lengths.
 * https://android.googlesource.com/platform/system/core/+/refs/heads/main/fastboot/README.md#tcp-protocol-v1
 * This transport does not authenticate/encrypt the peer. Use only a trusted network
 * and verify the target identity before authorizing any mutation.
 */
public final class FastbootTcpTransport implements FastbootClient.PacketTransport {
    public static final int DEFAULT_PORT = 5554;
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    private final Transport transport;
    private final InputStream input;
    private final OutputStream output;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object readLock = new Object();
    private final Object writeLock = new Object();

    public FastbootTcpTransport(String host, int port, int timeoutMs) throws IOException {
        this(connect(host, port, timeoutMs));
    }

    /** Owns transport even if the handshake fails. Its reads must have a finite timeout. */
    public FastbootTcpTransport(Transport transport) throws IOException {
        if (transport == null) throw new NullPointerException("transport");
        this.transport = transport;
        try {
            input = transport.input();
            output = transport.output();
            ensureOpen();
            output.write("FB01".getBytes(StandardCharsets.US_ASCII));
            ensureOpen();
            output.flush();
            ensureOpen();
            byte[] hello = SyncClient.readExactly(input, 4);
            ensureOpen();
            if (hello[0] != 'F' || hello[1] != 'B' || hello[2] < '0' || hello[2] > '9' ||
                hello[3] < '0' || hello[3] > '9' || (hello[2] == '0' && hello[3] == '0'))
                throw new IOException("Invalid or unsupported Fastboot TCP handshake");
            // We announce version 1. A valid peer >= 1 negotiates min(1, peer) == 1.
        } catch (IOException e) {
            closed.set(true);
            try { transport.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }

    public byte[] readPacket(int maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_FRAME_BYTES) throw new IllegalArgumentException("Invalid maximum frame size");
        synchronized (readLock) {
            try {
                ensureOpen();
                byte[] header = SyncClient.readExactly(input, 8);
                ensureOpen();
                long length = 0;
                // Checking before each shift rejects uint64 overflow without allocating.
                for (byte value : header) {
                    if (length > (maxBytes >>> 8)) throw new IOException("Fastboot TCP frame exceeds size limit");
                    length = (length << 8) | (value & 255);
                    if (length > maxBytes) throw new IOException("Fastboot TCP frame exceeds size limit");
                }
                byte[] packet = SyncClient.readExactly(input, (int) length);
                ensureOpen();
                return packet;
            } catch (IOException e) { throw invalidate(e); }
        }
    }

    public void writePacket(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (length > MAX_FRAME_BYTES) throw new IOException("Fastboot TCP frame exceeds size limit");
        synchronized (writeLock) {
            try {
                ensureOpen();
                byte[] header = new byte[8];
                long value = length;
                for (int i = 7; i >= 0; i--) { header[i] = (byte) value; value >>>= 8; }
                output.write(header);
                ensureOpen();
                output.write(bytes, offset, length);
                ensureOpen();
                output.flush();
                ensureOpen();
            } catch (IOException e) { throw invalidate(e); }
        }
    }

    public String description() { return "fastboot-tcp:" + transport.description(); }
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) transport.close();
    }
    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("Fastboot TCP transport is closed");
        SyncClient.checkInterrupted();
    }
    private IOException invalidate(IOException failure) {
        try { close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        return failure;
    }
    private static Transport connect(String host, int port, int timeoutMs) throws IOException {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Fastboot timeout must be positive");
        return new TcpTransport(host, port, timeoutMs);
    }
}
