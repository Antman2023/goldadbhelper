package io.github.devicebridge.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One persistent PTY per target: cd, variables and shell jobs survive successive input lines. */
public final class InteractiveShell implements Closeable {
    public interface Listener {
        /** Called on the reader thread. Each chunk is at most 8192 bytes; UTF-8 may span chunks. */
        void output(boolean stderr, byte[] data);
        /** Called once, after the stream is closed. Legacy shells have an unknown (-1) exit code. */
        void ended(int exitCode, IOException error);
    }

    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final int OUTPUT_CHUNK = 8192;
    private final InputStream input;
    private final OutputStream output;
    private final Closeable channel;
    private final Listener listener;
    private final boolean v2;
    private final Object writer = new Object();
    private volatile boolean closed;

    public InteractiveShell(AdbSession session, Listener listener) throws IOException {
        if (session == null || listener == null) throw new NullPointerException();
        this.listener = listener;
        v2 = session.hasFeature("shell_v2");
        AdbStream stream = session.open(v2 ? "shell,v2,pty:" : "shell:", 24 * 60 * 60 * 1000);
        channel = stream;
        input = stream.input();
        output = stream.output();
        startReader();
    }

    /** Stream seam for deterministic protocol tests; the supplied channel owns both streams. */
    InteractiveShell(InputStream input, OutputStream output, Closeable channel, boolean v2, Listener listener) {
        if (input == null || output == null || channel == null || listener == null) throw new NullPointerException();
        this.input = input;
        this.output = output;
        this.channel = channel;
        this.v2 = v2;
        this.listener = listener;
        startReader();
    }

    private void startReader() {
        Thread thread = new Thread(this::readLoop, "goldenbeetle-terminal");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isClosed() { return closed; }

    public void line(String line) throws IOException {
        if (line == null || line.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid terminal input");
        write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Send the PTY interrupt character; the target's termios configuration handles it. */
    public void interrupt() throws IOException { write(new byte[]{3}); }

    /** Send the PTY EOF character (Ctrl-D), without closing the ADB session or other streams. */
    public void eof() throws IOException { write(new byte[]{4}); }

    public void resize(int columns, int rows) throws IOException {
        if (columns < 1 || rows < 1 || columns > 10000 || rows > 10000)
            throw new IllegalArgumentException("Invalid terminal size");
        synchronized (writer) {
            checkOpen();
            // The legacy shell service has no separate window-size control channel.
            if (v2) writeFrame(5, (rows + "x" + columns + ",0x0\0").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Terminal closed");
    }

    private void write(byte[] data) throws IOException {
        synchronized (writer) {
            checkOpen();
            if (v2) writeFrame(0, data);
            else writeBytes(data);
        }
    }

    private void writeFrame(int id, byte[] data) throws IOException {
        if (data.length > MAX_FRAME) throw new IOException("Terminal input exceeds frame limit");
        ByteBuffer frame = ByteBuffer.allocate(5 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) id).putInt(data.length).put(data);
        writeBytes(frame.array());
    }

    private void writeBytes(byte[] data) throws IOException {
        try {
            output.write(data);
            output.flush();
        } catch (IOException error) {
            // A partial shell-v2 frame cannot be resumed safely, including on thread cancellation.
            close();
            throw error;
        }
    }

    private void readLoop() {
        int exit = -1;
        IOException error = null;
        try {
            if (v2) exit = readV2();
            else readLegacy();
        } catch (IOException failure) {
            if (!closed) error = failure;
        } catch (RuntimeException failure) {
            if (!closed) error = new IOException("Terminal output listener failed", failure);
        } finally {
            close();
            listener.ended(exit, error);
        }
    }

    private int readV2() throws IOException {
        byte[] buffer = new byte[OUTPUT_CHUNK];
        while (!closed) {
            int id = input.read();
            if (id < 0) {
                if (closed) return -1;
                throw new EOFException("Terminal connection ended without an exit status");
            }
            int length = ByteBuffer.wrap(AdbPacket.readExactly(input, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (length < 0 || length > MAX_FRAME) throw new IOException("Invalid terminal frame length");
            if (id == 1 || id == 2) {
                int remaining = length;
                while (remaining > 0 && !closed) {
                    int n = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (n < 0) throw new EOFException("Truncated terminal output frame");
                    if (n == 0) continue;
                    remaining -= n;
                    if (!closed) listener.output(id == 2, Arrays.copyOf(buffer, n));
                }
            } else if (id == 3) {
                if (length != 1 && length != 4) throw new IOException("Invalid terminal exit status");
                byte[] data = AdbPacket.readExactly(input, length);
                // Exit is terminal; do not wait indefinitely for a subsequent ADB CLSE packet.
                return length == 1 ? data[0] & 255 : ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
            } else if (id != 4 || length != 0) {
                throw new IOException("Unexpected terminal frame " + id);
            }
        }
        return -1;
    }

    private void readLegacy() throws IOException {
        byte[] buffer = new byte[OUTPUT_CHUNK];
        while (!closed) {
            int n = input.read(buffer);
            if (n < 0) return;
            if (n > 0 && !closed) listener.output(false, Arrays.copyOf(buffer, n));
        }
    }

    /** Cancels a blocked reader/writer. Does not wait for or hold the writer lock. */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try { channel.close(); } catch (IOException ignored) { }
    }
}
