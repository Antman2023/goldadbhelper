package io.github.devicebridge.core;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic in-memory session termination and observer failures; no device. */
public final class AdbSessionLifecycleTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        observerFailures(); terminalObserversSeeCleanup(); connectedObserverCanUseSession(); closeAtConnected();
        closeBeforeRegistration(); invalidOpenArguments(); preCancelledOpen(); preCancelledConnect(); missingIdentity();
        System.out.println("AdbSessionLifecycleTests: " + assertions + " assertions passed (in-memory only)");
    }

    private static void observerFailures() throws Exception {
        AdbIdentity identity = AdbIdentity.generate();
        for (AdbSession.State event : new AdbSession.State[]{AdbSession.State.CONNECTING, AdbSession.State.AUTHENTICATING,
                AdbSession.State.CONNECTED, AdbSession.State.FAILED, AdbSession.State.CLOSED}) {
            Fixture f = new Fixture(identity);
            AtomicInteger calls = new AtomicInteger();
            f.authenticate = event == AdbSession.State.AUTHENTICATING;
            f.badHandshake = event == AdbSession.State.FAILED;
            f.session.setListener((state, detail) -> { if (state == event) { calls.incrementAndGet(); throw new IllegalStateException("synthetic observer failure"); } });
            try {
                if (f.badHandshake) {
                    expectIo(f.session::connect, "handshake failure remains an IOException");
                    check(f.session.state() == AdbSession.State.FAILED && f.closed.get(), "failed connection closes transport despite observer exception");
                } else {
                    f.session.connect();
                    try (AdbStream stream = f.session.open("fixture:echo", 1000)) {
                        check(stream.input().read() == 42, "observer failure does not prevent the session reader from starting");
                    }
                    f.session.close();
                    check(f.session.state() == AdbSession.State.CLOSED && f.closed.get(), "close remains terminal despite observer exception");
                }
                check(calls.get() == 1, "observer receives the expected event exactly once");
            } finally { f.session.setListener(null); f.session.close(); }
        }
    }

    private static void terminalObserversSeeCleanup() throws Exception {
        for (boolean disconnect : new boolean[]{false, true}) {
            Fixture f = new Fixture(null); f.session.connect();
            AdbStream stream = f.session.open("fixture:hold", 1000);
            AtomicReference<Boolean> cleaned = new AtomicReference<Boolean>();
            CountDownLatch notified = new CountDownLatch(1);
            f.session.setListener((state, detail) -> {
                if (state == (disconnect ? AdbSession.State.CLOSED : AdbSession.State.FAILED)) {
                    try { cleaned.set(f.closed.get() && streams(f.session).isEmpty()); }
                    catch (Exception error) { cleaned.set(false); }
                    notified.countDown();
                }
            });
            try {
                if (disconnect) f.session.close();
                else { byte[] corrupt = new AdbPacket(AdbPacket.WRTE, 101, 1, new byte[]{1}).encode(); corrupt[20] ^= 1; f.input.feed(corrupt); }
                check(notified.await(2, TimeUnit.SECONDS), "terminal observer notified");
                check(Boolean.TRUE.equals(cleaned.get()), "terminal observer sees transport closed and stream registry empty");
                expectIo(() -> stream.input().read(), "terminal transition invalidates active stream");
            } finally { f.session.setListener(null); f.session.close(); }
        }
    }

    private static void closeAtConnected() throws Exception {
        Fixture f = new Fixture(null);
        f.session.setListener((state, detail) -> { if (state == AdbSession.State.CONNECTED) f.session.close(); });
        try {
            expectIo(f.session::connect, "connection cancelled by observer cannot report success");
            check(f.session.state() == AdbSession.State.CLOSED && f.closed.get(), "observer cancellation stays closed");
        } finally { f.session.close(); }
    }

    private static void connectedObserverCanUseSession() throws Exception {
        Fixture f = new Fixture(null);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        AtomicInteger response = new AtomicInteger(-1);
        f.session.setListener((state, detail) -> {
            if (state == AdbSession.State.CONNECTED) try (AdbStream stream = f.session.open("fixture:echo", 1000)) {
                response.set(stream.input().read());
            } catch (Throwable failure) { error.set(failure); }
        });
        try {
            f.session.connect();
            check(error.get() == null && response.get() == 42, "CONNECTED observer can use the already-running reader");
        } finally { f.session.setListener(null); f.session.close(); }
    }

    private static void closeBeforeRegistration() throws Exception {
        Fixture f = new Fixture(null); f.session.connect();
        Field field = AdbSession.class.getDeclaredField("stateLock"); field.setAccessible(true);
        Object stateLock = field.get(f.session);
        AtomicReference<Throwable> result = new AtomicReference<Throwable>();
        CountDownLatch started = new CountDownLatch(1);
        Thread opener = new Thread(() -> {
            started.countDown();
            try { f.session.open("fixture:hold", 30000); result.set(new AssertionError("closed session opened a stream")); }
            catch (Throwable failure) { result.set(failure); }
        });
        opener.setDaemon(true);
        try {
            synchronized (stateLock) {
                opener.start(); check(started.await(2, TimeUnit.SECONDS), "concurrent OPEN started");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (opener.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
                check(opener.getState() == Thread.State.BLOCKED, "stream registration shares the termination lock");
                f.session.close();
            }
            opener.join(2000);
            check(!opener.isAlive() && result.get() instanceof IOException, "OPEN terminates after concurrent close");
            check(f.opens.get() == 0 && streams(f.session).isEmpty(), "closed session sends no OPEN and retains no late stream");
        } finally { f.session.close(); opener.interrupt(); opener.join(2000); }
    }

    private static void invalidOpenArguments() throws Exception {
        Fixture f = new Fixture(null); f.session.connect();
        try {
            for (int timeout : new int[]{0, -1}) {
                try { f.session.open("fixture:echo", timeout); throw new AssertionError("invalid timeout accepted"); }
                catch (IllegalArgumentException expected) { assertions++; }
            }
            for (String service : new String[]{null, "", "bad\0service"}) expectIo(() -> f.session.open(service, 1000), "invalid service rejected locally");
            check(f.opens.get() == 0 && streams(f.session).isEmpty(), "invalid arguments have no remote side effects");
        } finally { f.session.close(); }
    }

    private static void missingIdentity() throws Exception {
        Fixture f = new Fixture(null); f.authenticate = true;
        try {
            expectIo(f.session::connect, "authentication without identity reports a connection error");
            check(f.session.state() == AdbSession.State.FAILED && f.closed.get(), "missing identity releases transport");
        } finally { f.session.close(); }
    }

    private static void preCancelledOpen() throws Exception {
        Fixture f = new Fixture(null); f.session.connect();
        Thread.currentThread().interrupt();
        try {
            expectIo(() -> f.session.open("fixture:echo", 1000), "pre-cancelled OPEN fails locally");
            check(f.opens.get() == 0 && streams(f.session).isEmpty(), "pre-cancelled OPEN sends no command and registers no stream");
            check(Thread.currentThread().isInterrupted() && f.session.state() == AdbSession.State.CONNECTED, "pre-cancelled OPEN preserves cancellation and shared session");
        } finally { Thread.interrupted(); f.session.close(); }
    }

    private static void preCancelledConnect() throws Exception {
        Fixture f = new Fixture(null);
        Thread.currentThread().interrupt();
        try {
            expectIo(f.session::connect, "pre-cancelled connection fails locally");
            check(f.handshakes.get() == 0 && f.closed.get() && f.session.state() == AdbSession.State.FAILED,
                    "pre-cancelled connection sends no handshake and closes its owned transport");
            check(Thread.currentThread().isInterrupted(), "connection cancellation retains the interrupt flag");
        } finally { Thread.interrupted(); f.session.close(); }
    }

    private interface Action { void run() throws Exception; }
    private static void expectIo(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch (IOException expected) { assertions++; }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    @SuppressWarnings("unchecked") private static Map<Integer, AdbStream> streams(AdbSession session) throws Exception {
        Field field = AdbSession.class.getDeclaredField("streams"); field.setAccessible(true);
        return (Map<Integer, AdbStream>)field.get(session);
    }

    private static final class Fixture implements Transport {
        final QueueInput input = new QueueInput();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger handshakes = new AtomicInteger();
        final AdbSession session;
        boolean authenticate, badHandshake;
        Fixture(AdbIdentity identity) { session = new AdbSession(this, identity, null); }
        public InputStream input() { return input; }
        public OutputStream output() { return new OutputStream() {
            public void write(int value) { throw new AssertionError("whole packets required"); }
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (closed.get()) throw new IOException("fixture transport closed");
                AdbPacket p = SyntheticAdbPackets.decode(bytes, offset, length);
                if (p.command == AdbPacket.CNXN) handshakes.incrementAndGet();
                if (p.command == AdbPacket.CNXN && authenticate) input.feed(new AdbPacket(AdbPacket.AUTH, 1, 0, new byte[20]).encode());
                else if (p.command == AdbPacket.CNXN || p.command == AdbPacket.AUTH)
                    input.feed(new AdbPacket(AdbPacket.CNXN, 0x01000001, badHandshake ? 0 : 4096, AdbPacket.text("device::features=shell_v2;\0")).encode());
                else if (p.command == AdbPacket.OPEN) {
                    opens.incrementAndGet(); input.feed(new AdbPacket(AdbPacket.OKAY, p.arg0 + 100, p.arg0, null).encode());
                    if (new String(p.payload, "UTF-8").startsWith("fixture:echo")) input.feed(new AdbPacket(AdbPacket.WRTE, p.arg0 + 100, p.arg0, new byte[]{42}).encode());
                }
            }
        }; }
        public String description() { return "in-memory lifecycle fixture"; }
        public void close() { closed.set(true); input.close(); }
    }

    private static final class QueueInput extends InputStream {
        final ArrayDeque<byte[]> packets = new ArrayDeque<byte[]>(); int offset; boolean closed;
        synchronized void feed(byte[] bytes) { packets.add(bytes); notifyAll(); }
        public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        public synchronized int read(byte[] bytes, int at, int length) throws IOException {
            if (length == 0) return 0;
            while (packets.isEmpty() && !closed) try { wait(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("fixture interrupted"); }
            if (packets.isEmpty()) return -1;
            byte[] head = packets.peek(); int n = Math.min(length, head.length - offset);
            System.arraycopy(head, offset, bytes, at, n); offset += n;
            if (offset == head.length) { packets.remove(); offset = 0; } return n;
        }
        public synchronized void close() { closed = true; notifyAll(); }
    }
}
