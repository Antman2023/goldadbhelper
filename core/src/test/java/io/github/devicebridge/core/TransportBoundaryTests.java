package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Fragmented/zero reads, interruption and local argument checks; no device. */
public final class TransportBoundaryTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        zeroReads(); zeroReadEof(); interruptedRead(); interruptionAfterZero(); finiteTcpTimeout(); fixturePreservesCancellation();
        System.out.println("TransportBoundaryTests: " + assertions + " assertions passed (in-memory and synthetic loopback only)");
    }

    private static void zeroReads() throws Exception {
        byte[] data = new byte[]{0, 1, (byte)255, 42};
        ZeroInput input = new ZeroInput(data);
        check(Arrays.equals(AdbPacket.readExactly(input, data.length), data), "zero bulk reads fall back without losing bytes");
        check(input.bulkReads == data.length && input.singleReads == data.length, "no repeated empty bulk read spin");
        InputStream noRead = new InputStream() { public int read() { throw new AssertionError("empty read touched input"); } };
        check(AdbPacket.readExactly(noRead, 0).length == 0, "empty read does not consume input");
        try { AdbPacket.readExactly(noRead, -1); throw new AssertionError("negative length accepted"); }
        catch (IOException expected) { assertions++; }
    }

    private static void zeroReadEof() throws Exception {
        ZeroInput input = new ZeroInput(new byte[0]);
        try { AdbPacket.readExactly(input, 1); throw new AssertionError("truncated stream accepted"); }
        catch (EOFException expected) { assertions++; }
        check(input.bulkReads == 1 && input.singleReads == 1, "zero-read EOF is detected promptly");
    }

    private static void interruptedRead() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        InputStream input = new InputStream() { public int read() { reads.incrementAndGet(); return 42; } };
        Thread.currentThread().interrupt();
        try {
            try { AdbPacket.readExactly(input, 1); throw new AssertionError("cancelled protocol read resumed"); }
            catch (InterruptedIOException expected) { assertions++; }
            check(Thread.currentThread().isInterrupted() && reads.get() == 0, "cancellation preserves flag and consumes no bytes");
        } finally { Thread.interrupted(); }
    }

    private static void interruptionAfterZero() throws Exception {
        AtomicInteger singleReads = new AtomicInteger();
        InputStream input = new InputStream() {
            public int read() { singleReads.incrementAndGet(); return 42; }
            public int read(byte[] bytes, int at, int length) { Thread.currentThread().interrupt(); return 0; }
        };
        try {
            try { AdbPacket.readExactly(input, 1); throw new AssertionError("interrupted empty read spun"); }
            catch (InterruptedIOException expected) { assertions++; }
            check(singleReads.get() == 0 && Thread.currentThread().isInterrupted(), "fallback checks cancellation before reading a byte");
        } finally { Thread.interrupted(); }
    }

    private static void finiteTcpTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            for (int timeout : new int[]{0, -1}) {
                try (TcpTransport transport = new TcpTransport("127.0.0.1", server.getLocalPort(), timeout)) {
                    throw new AssertionError("nonpositive connection timeout accepted");
                } catch (IllegalArgumentException expected) { assertions++; }
            }
            server.setSoTimeout(50);
            try (Socket unexpected = server.accept()) { throw new AssertionError("invalid arguments opened a socket"); }
            catch (SocketTimeoutException expected) { assertions++; }
        }
    }

    private static void fixturePreservesCancellation() throws Exception {
        byte[] bytes = new AdbPacket(AdbPacket.CLSE, 7, 107, null).encode();
        Thread.currentThread().interrupt();
        try {
            AdbPacket close = SyntheticAdbPackets.decode(bytes, 0, bytes.length);
            check(close.command == AdbPacket.CLSE && close.arg0 == 7 && close.arg1 == 107, "inline peer decodes the cancelled sender's cleanup packet");
            check(Thread.currentThread().isInterrupted(), "fixture decoding preserves the sender's cancellation flag");
        } finally { Thread.interrupted(); }
    }

    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static final class ZeroInput extends InputStream {
        final ByteArrayInputStream source;
        int bulkReads, singleReads;
        ZeroInput(byte[] bytes) { source = new ByteArrayInputStream(bytes); }
        public int read() { singleReads++; return source.read(); }
        public int read(byte[] bytes, int at, int length) {
            if (++bulkReads > 4) throw new AssertionError("protocol reader is spinning on zero bulk reads");
            return 0;
        }
    }
}
