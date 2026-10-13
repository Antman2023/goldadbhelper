package io.github.devicebridge.core;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic in-memory OPEN/close interleavings; no socket or real device. */
public final class AdbOpenCancellationTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        lateOkay(false);
        lateOkay(true);
        acknowledgedOpenFailure();
        interruptedOpen(false);
        interruptedOpen(true);
        System.out.println("AdbOpenCancellationTests: " + assertions + " assertions passed (in-memory interleavings)");
    }

    private static void lateOkay(boolean failed) throws Exception {
        Fixture fixture = new Fixture();
        AdbStream stream = new AdbStream(fixture.session, 7, 1000);
        // Models a reader that already obtained the stream while OPEN's caller cancels.
        if (failed) stream.fail(new InterruptedIOException("cancelled"));
        else stream.close();
        stream.onOkay(107);
        check(fixture.packets.size() == 1, "late OKAY must send a close for both cancellation paths");
        AdbPacket close = fixture.packets.get(0);
        check(close.command == AdbPacket.CLSE && close.arg0 == 7 && close.arg1 == 107,
                "late OKAY close identifies the correct channel");
        try { stream.output().write(1); throw new AssertionError("closed channel was resurrected"); }
        catch (IOException expected) { assertions++; }
    }

    private static void acknowledgedOpenFailure() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failAcknowledgedOpen = true;
        try { fixture.session.open("exec:fixture", 1000); throw new AssertionError("OPEN failure was swallowed"); }
        catch (IOException expected) { check(expected.getMessage().equals("synthetic OPEN write failure"), "original failure retained"); }
        check(fixture.packets.size() == 2, "acknowledged failed OPEN emits OPEN then CLSE");
        AdbPacket open = fixture.packets.get(0), close = fixture.packets.get(1);
        check(close.command == AdbPacket.CLSE && close.arg0 == open.arg0 && close.arg1 == 107, "failed OPEN releases remote channel");
        check(fixture.streams().isEmpty(), "failed OPEN is forgotten");
        check(fixture.session.state() == AdbSession.State.CONNECTED, "channel failure preserves shared session");
    }

    private static void interruptedOpen(boolean acknowledgeAfterCleanup) throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { fixture.session.open("exec:fixture", 30000); error.set(new AssertionError("OPEN unexpectedly returned")); }
            catch (Throwable result) { error.set(result); }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            check(fixture.openSent.await(2, TimeUnit.SECONDS), "worker sent OPEN");
            int local = fixture.packets.get(0).arg0;
            AdbStream stream = fixture.streams().get(local);
            awaitState(worker, Thread.State.TIMED_WAITING);
            if (acknowledgeAfterCleanup) {
                worker.interrupt();
                worker.join(2000);
                check(!worker.isAlive(), "cancelled OPEN completes before late ACK");
                stream.onOkay(107); // Reader retained this reference before map removal.
            } else {
                synchronized (stream) {
                    worker.interrupt();
                    awaitState(worker, Thread.State.BLOCKED);
                    stream.onOkay(107); // ACK wins before interrupted awaitOpen regains its lock.
                }
                worker.join(2000);
            }
            check(!worker.isAlive() && error.get() instanceof InterruptedIOException, "original cancellation retained");
            check(fixture.streams().isEmpty(), "cancelled OPEN is never re-registered");
            check(fixture.packets.size() == 2, "cancelled OPEN emits exactly one CLSE");
            AdbPacket close = fixture.packets.get(1);
            check(close.command == AdbPacket.CLSE && close.arg0 == local && close.arg1 == 107, "cancelled OPEN closes exact remote channel");
            check(fixture.session.state() == AdbSession.State.CONNECTED, "cancelled channel preserves session");
        } finally {
            worker.interrupt();
            fixture.session.close();
            worker.join(2000);
        }
    }

    private static void awaitState(Thread worker, Thread.State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.getState() != expected && System.nanoTime() < deadline) Thread.yield();
        check(worker.getState() == expected, "worker reaches " + expected);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Fixture implements Transport {
        final List<AdbPacket> packets = new ArrayList<AdbPacket>();
        final AdbSession session;
        final CountDownLatch openSent = new CountDownLatch(1);
        boolean failAcknowledgedOpen;
        Fixture() throws Exception {
            session = new AdbSession(this, null, null);
            Field state = AdbSession.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(session, AdbSession.State.CONNECTED);
        }
        @SuppressWarnings("unchecked") Map<Integer, AdbStream> streams() throws Exception {
            Field field = AdbSession.class.getDeclaredField("streams");
            field.setAccessible(true);
            return (Map<Integer, AdbStream>) field.get(session);
        }
        public InputStream input() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream output() {
            return new OutputStream() {
                public void write(int value) { throw new AssertionError("whole packets expected"); }
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    AdbPacket packet = SyntheticAdbPackets.decode(bytes, offset, length);
                    packets.add(packet);
                    if (packet.command == AdbPacket.OPEN) openSent.countDown();
                    if (packet.command == AdbPacket.OPEN && failAcknowledgedOpen) {
                        try { streams().get(packet.arg0).onOkay(107); }
                        catch (IOException error) { throw error; }
                        catch (Exception error) { throw new IOException(error); }
                        throw new IOException("synthetic OPEN write failure");
                    }
                }
            };
        }
        public String description() { return "in-memory OPEN cancellation fixture"; }
        public void close() { }
    }
}
