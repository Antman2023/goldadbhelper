package io.github.devicebridge.core;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation at individual stream calls, including streams that tolerate close. */
public final class SyncIoCancellationTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        writeBoundaries(); readBoundaries(); sourceBoundaries();
        System.out.println("SyncIoCancellationTests: " + assertions + " assertions passed (in-memory only)");
    }

    private static void writeBoundaries() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            for (boolean onFlush : new boolean[]{false, true}) {
                for (int position = 1; position <= (onFlush ? 3 : 5); position++) {
                    final int stop = position;
                    SyncClient[] owner = new SyncClient[1];
                    AtomicInteger writes = new AtomicInteger(), flushes = new AtomicInteger(), reads = new AtomicInteger();
                    ByteArrayInputStream response = new ByteArrayInputStream(new byte[]{'O','K','A','Y',0,0,0,0}) {
                        public synchronized int read(byte[] bytes, int offset, int length) {
                            reads.incrementAndGet(); return super.read(bytes, offset, length);
                        }
                    };
                    ByteArrayOutputStream wire = new ByteArrayOutputStream() {
                        public synchronized void write(byte[] bytes, int offset, int length) {
                            super.write(bytes, offset, length);
                            if (writes.incrementAndGet() == stop && !onFlush) cancel(owner[0], interrupt);
                        }
                        public void flush() {
                            if (flushes.incrementAndGet() == stop && onFlush) cancel(owner[0], interrupt);
                        }
                    };
                    TrackSource source = new TrackSource();
                    SyncClient client = new SyncClient(response, wire); owner[0] = client;
                    try {
                        rejected(() -> client.push("/file", source, 1, 0600, 0, null));
                        int expectedWrites = onFlush ? (stop == 3 ? 5 : stop * 2) : stop;
                        int expectedFlushes = onFlush ? stop : stop <= 2 ? 0 : stop <= 4 ? 1 : 2;
                        equal(expectedWrites, writes.get(), "cancellation stops at exact write boundary");
                        equal(expectedFlushes, flushes.get(), "cancellation never adds a later flush");
                        equal(0, reads.get(), "cancelled upload never starts reading acknowledgement");
                        check(!source.closed, "upload source remains caller-owned");
                        check(client.isClosed(), "cancelled SYNC client stays closed");
                        check(Thread.currentThread().isInterrupted() == interrupt, "write preserves interrupt flag");
                    } finally { Thread.interrupted(); client.close(); }
                }
            }
        }
    }

    private static void readBoundaries() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            for (boolean zero : new boolean[]{false, true}) {
                for (String operation : new String[]{"LIST", "STAT", "RECV", "SEND"}) {
                    SyncClient[] owner = new SyncClient[1];
                    AtomicInteger bulk = new AtomicInteger(), single = new AtomicInteger();
                    InputStream response = new InputStream() {
                        public int read() { single.incrementAndGet(); return 'O'; }
                        public int read(byte[] bytes, int offset, int length) {
                            bulk.incrementAndGet(); bytes[offset] = 'O'; cancel(owner[0], interrupt);
                            return zero ? 0 : 1;
                        }
                    };
                    SyncClient client = new SyncClient(response, new ByteArrayOutputStream()); owner[0] = client;
                    try {
                        rejected(() -> {
                            if (operation.equals("LIST")) client.list("/dir");
                            else if (operation.equals("STAT")) client.stat("/file");
                            else if (operation.equals("RECV")) client.pull("/file", new ByteArrayOutputStream(), null);
                            else client.push("/file", new ByteArrayInputStream(new byte[0]), 0, 0600, 0, null);
                        });
                        equal(1, bulk.get(), operation + " cancellation stops fragmented read immediately");
                        equal(0, single.get(), operation + " cancelled zero-read cannot start fallback");
                        check(client.isClosed(), "read failure is terminal");
                        check(Thread.currentThread().isInterrupted() == interrupt, "read preserves interrupt flag");
                    } finally { Thread.interrupted(); client.close(); }
                }
            }
        }
    }

    private static void sourceBoundaries() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            for (int count : new int[]{-1, 0, 1}) {
                SyncClient[] owner = new SyncClient[1];
                AtomicInteger fallback = new AtomicInteger();
                TrackSource source = new TrackSource() {
                    public synchronized int read(byte[] bytes, int offset, int length) {
                        bytes[offset] = 42; cancel(owner[0], interrupt); return count;
                    }
                    public synchronized int read() { fallback.incrementAndGet(); return 42; }
                };
                ByteArrayOutputStream wire = new ByteArrayOutputStream();
                SyncClient client = new SyncClient(new ByteArrayInputStream(new byte[0]), wire); owner[0] = client;
                try {
                    rejected(() -> client.push("/file", source, -1, 0600, 0, null));
                    equal(0, fallback.get(), "cancelled source never starts fallback read");
                    equal(17, wire.size(), "only SEND header and path preceded source cancellation");
                    check(!source.closed, "cancelled caller source remains open");
                    check(Thread.currentThread().isInterrupted() == interrupt, "source preserves interrupt flag");
                } finally { Thread.interrupted(); client.close(); }
            }
        }
    }

    private static void cancel(SyncClient client, boolean interrupt) {
        if (interrupt) Thread.currentThread().interrupt();
        else try { client.close(); } catch (IOException error) { throw new AssertionError(error); }
    }
    private interface Action { void run() throws IOException; }
    private static void rejected(Action action) throws IOException {
        try { action.run(); throw new AssertionError("Cancelled operation succeeded"); }
        catch (IOException expected) { assertions++; }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void equal(int expected, int actual, String message) { check(expected == actual, message + ": expected " + expected + ", got " + actual); }
    private static class TrackSource extends ByteArrayInputStream {
        boolean closed;
        TrackSource() { super(new byte[]{42}); }
        public void close() { closed = true; }
    }
}
