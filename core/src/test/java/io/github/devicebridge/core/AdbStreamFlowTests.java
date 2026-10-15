package io.github.devicebridge.core;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory and synthetic loopback flow control; no device or daemon is used. */
public final class AdbStreamFlowTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        slowConsumer(); overflowClosesChannel(); closeDropsBufferedData(); cancelPausedConsumer(); peerClosePreservesData();
        closeUnblocksReader(); invalidRanges(); deferredAckFailure(); multiplexedLargeTransfer();
        failedWrite(); missingWriteAck(); cancelledWrite(); interruptWaitingWriter(); directOutputFailure(false); directOutputFailure(true);
        System.out.println("AdbStreamFlowTests: " + assertions + " assertions passed (in-memory and synthetic loopback only)");
    }

    private static void slowConsumer() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        byte[] block = new byte[1024 * 1024];
        Arrays.fill(block, (byte) 31);
        for (int i = 0; i < 8; i++) stream.onData(107, block);
        check(f.count(AdbPacket.OKAY) == 7, "full receive buffer holds the last ACK instead of requesting more data");
        AdbStream other = f.open(8);
        other.onData(108, new byte[]{42});
        check(other.input().read() == 42, "slow consumer does not block another channel");
        byte[] read = new byte[1024 * 1024];
        InputStream input = stream.input();
        for (int i = 0; i < 4; i++) {
            check(input.read(read) == read.length && Arrays.equals(read, block), "buffered bytes remain intact");
            check(f.count(AdbPacket.OKAY) == (i == 3 ? 9 : 8), "ACK resumes only after sufficient buffer space is free");
        }
        // The producer resumes after the withheld ACK. Total received exceeds the buffer cap.
        for (int i = 0; i < 4; i++) stream.onData(107, block);
        check(f.count(AdbPacket.OKAY) == 12, "second fill also pauses the producer");
        for (int i = 0; i < 8; i++) check(input.read(read) == read.length && Arrays.equals(read, block), "resumed transfer keeps byte order");
        check(f.count(AdbPacket.OKAY) == 13, "each paused batch receives one deferred ACK");
        stream.onClose(107);
        check(input.read() == -1, "drained transfer ends normally");
    }

    private static void overflowClosesChannel() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        byte[] block = new byte[1024 * 1024];
        for (int i = 0; i < 8; i++) stream.onData(107, block);
        stream.onData(107, block); // Invalid peer ignores the withheld ACK.
        check(f.count(AdbPacket.CLSE) == 1, "overflow explicitly releases the remote channel");
        AdbPacket close = f.packets.get(f.packets.size() - 1);
        check(close.arg0 == 7 && close.arg1 == 107, "overflow closes only the affected channel");
        try { stream.input().read(); throw new AssertionError("overflow was hidden"); }
        catch (IOException expected) { assertions++; }
        check(f.streams().get(7) == null && f.session.state() == AdbSession.State.CONNECTED, "overflow forgets its stream and preserves the session");
    }

    private static void closeDropsBufferedData() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        stream.onData(107, new byte[]{1, 2, 3});
        stream.close();
        check(stream.input().read() == -1, "local cancellation does not deliver stale buffered data");
        int packets = f.packets.size();
        stream.close();
        check(f.packets.size() == packets, "repeated close sends no duplicate packet");
    }

    private static void peerClosePreservesData() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        stream.onData(107, new byte[]{1, 2, 3});
        stream.onClose(107);
        InputStream input = stream.input();
        check(input.read() == 1 && input.read() == 2 && input.read() == 3 && input.read() == -1,
                "peer EOF preserves final bytes already received");
    }

    private static void cancelPausedConsumer() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        byte[] block = new byte[1024 * 1024];
        for (int i = 0; i < 8; i++) stream.onData(107, block);
        int acks = f.count(AdbPacket.OKAY);
        stream.close();
        check(stream.input().read() == -1 && f.count(AdbPacket.OKAY) == acks,
                "cancelling a paused consumer discards pending data without resuming the producer");
        check(f.count(AdbPacket.CLSE) == 1, "paused channel is explicitly closed");
    }

    private static void closeUnblocksReader() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread reader = new Thread(() -> {
            try { if (stream.input().read() != -1) throw new AssertionError("expected cancellation EOF"); }
            catch (Throwable failure) { error.set(failure); }
        });
        reader.setDaemon(true); reader.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (reader.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield();
            check(reader.getState() == Thread.State.TIMED_WAITING, "reader is blocked on the stream");
            stream.close(); reader.join(2000);
            check(!reader.isAlive() && error.get() == null, "close promptly wakes blocked reader");
        } finally { stream.close(); reader.interrupt(); reader.join(2000); }
    }

    private static void invalidRanges() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        for (int[] range : new int[][]{{-1, 1}, {0, -1}, {1, Integer.MAX_VALUE}}) {
            try { stream.input().read(new byte[1], range[0], range[1]); throw new AssertionError("invalid read accepted"); }
            catch (IndexOutOfBoundsException expected) { assertions++; }
            try { stream.output().write(new byte[1], range[0], range[1]); throw new AssertionError("invalid write accepted"); }
            catch (IndexOutOfBoundsException expected) { assertions++; }
        }
        check(f.packets.isEmpty(), "invalid ranges have no wire side effects");
        stream.close();
    }

    private static void multiplexedLargeTransfer() throws Exception {
        try (LoopbackPeer peer = new LoopbackPeer();
             AdbSession session = new AdbSession(new TcpTransport("127.0.0.1", peer.server.getLocalPort(), 3000), null, null)) {
            session.connect();
            try (AdbStream large = session.open("fixture:large", 3000)) {
                check(peer.full.await(3, TimeUnit.SECONDS), "loopback producer fills the receive window");
                try (AdbStream small = session.open("fixture:small", 3000)) {
                    check(small.input().read() == 42 && small.input().read() == -1,
                            "shared reader serves another channel while large transfer is paused");
                }
                check(peer.sent == 8, "paused loopback producer receives no premature ACK");
                InputStream in = large.input();
                byte[] bytes = new byte[32768]; int count; long received = 0;
                while ((count = in.read(bytes)) != -1) {
                    for (int i = 0; i < count; i++) {
                        if ((bytes[i] & 255) != (int)((received + i) / (1024 * 1024)))
                            throw new AssertionError("large transfer byte order changed");
                    }
                    received += count;
                }
                check(received == 12L * 1024 * 1024 && peer.sent == 12,
                        "12 MiB transfer exceeds buffer capacity and retains every byte");
                check(session.state() == AdbSession.State.CONNECTED, "large transfer preserves the shared session");
            }
        }
    }

    private static void deferredAckFailure() throws Exception {
        Fixture f = new Fixture();
        AdbStream stream = f.open(7);
        byte[] block = new byte[1024 * 1024];
        for (int i = 0; i < 8; i++) stream.onData(107, block);
        f.ackFailure = new IOException("synthetic deferred ACK failure");
        InputStream input = stream.input();
        for (int i = 0; i < 3; i++) input.read(block);
        try { input.read(block); throw new AssertionError("failed ACK hidden"); }
        catch (IOException error) { check(error == f.ackFailure, "deferred ACK preserves original transport failure"); }
        check(f.count(AdbPacket.CLSE) == 1 && !f.streams().containsKey(7), "failed ACK releases the remote stream");
        try { input.read(); throw new AssertionError("failed ACK allowed a stream retry"); }
        catch (IOException error) { check(error == f.ackFailure, "failed ACK leaves the channel terminal"); }
    }

    private static void failedWrite() throws Exception {
        Fixture f = new Fixture(); AdbStream stream = f.open(7);
        f.writeFailure = new IOException("synthetic write failure");
        f.closeFailure = new IOException("synthetic close failure");
        try { stream.output().write(42); throw new AssertionError("write failure hidden"); }
        catch (IOException error) {
            check(error == f.writeFailure, "write preserves original failure");
            check(error.getSuppressed().length == 1 && error.getSuppressed()[0] == f.closeFailure,
                    "cleanup failure accompanies original write failure");
        }
        check(!f.streams().containsKey(7), "failed write removes stream even when close fails");
        try { stream.output().write(43); throw new AssertionError("failed stream reused"); }
        catch (IOException error) { check(error == f.writeFailure, "retry fails immediately with original error"); }
        check(f.writeAttempts == 1, "retry sends no further data");
    }

    private static void missingWriteAck() throws Exception {
        Fixture f = new Fixture(); AdbStream stream = f.open(7, 30);
        stream.output().write(1);
        try { stream.output().write(2); throw new AssertionError("missing ACK accepted"); }
        catch (SocketTimeoutException expected) { assertions++; }
        check(f.count(AdbPacket.CLSE) == 1 && !f.streams().containsKey(7), "ACK timeout releases channel");
        // A delayed ACK may arrive after cancellation, but must not reopen the channel.
        stream.onOkay(107);
        try { stream.output().write(3); throw new AssertionError("late ACK reopened failed stream"); }
        catch (SocketTimeoutException expected) { assertions++; }
        AdbStream sibling = f.open(8); sibling.output().write(9);
        check(f.count(AdbPacket.WRTE) == 2 && f.session.state() == AdbSession.State.CONNECTED,
                "ACK timeout preserves other channels on shared session");
        sibling.close();
    }

    private static void cancelledWrite() throws Exception {
        for (boolean awaitingAck : new boolean[]{false, true}) {
            Fixture f = new Fixture(); AdbStream stream = f.open(7);
            f.interruptibleWrites = true;
            if (awaitingAck) stream.output().write(1);
            Thread.currentThread().interrupt();
            try {
                try { stream.output().write(2); throw new AssertionError("cancelled write accepted"); }
                catch (InterruptedIOException expected) { assertions++; }
                check(Thread.currentThread().isInterrupted(), "write cancellation retains interrupt flag");
                check(f.count(AdbPacket.WRTE) == (awaitingAck ? 1 : 0), "cancelled writer sends no new data");
                check(f.count(AdbPacket.CLSE) == 1 && !f.streams().containsKey(7), "cancelled write releases channel");
            } finally { Thread.interrupted(); }
            AdbStream sibling = f.open(8); sibling.output().write(3);
            check(f.session.state() == AdbSession.State.CONNECTED, "interrupted channel cleanup preserves shared session");
            sibling.close();
        }
    }

    private static void interruptWaitingWriter() throws Exception {
        Fixture f = new Fixture(); AdbStream stream = f.open(7); stream.output().write(1);
        f.interruptibleWrites = true;
        AtomicReference<Throwable> result = new AtomicReference<Throwable>();
        Thread writer = new Thread(() -> {
            try { stream.output().write(2); result.set(new AssertionError("interrupted write succeeded")); }
            catch (InterruptedIOException expected) {
                if (!Thread.currentThread().isInterrupted()) result.set(new AssertionError("interrupt flag lost"));
            } catch (Throwable error) { result.set(error); }
        });
        writer.setDaemon(true); writer.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (writer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield();
            check(writer.getState() == Thread.State.TIMED_WAITING, "writer actually waits for peer ACK");
            writer.interrupt(); writer.join(2000);
            check(!writer.isAlive() && result.get() == null, "interrupt wakes ACK waiter with cancellation flag intact");
            check(f.count(AdbPacket.WRTE) == 1 && f.count(AdbPacket.CLSE) == 1 && !f.streams().containsKey(7),
                    "interrupted ACK waiter sends no further data and releases channel");
        } finally { writer.interrupt(); stream.close(); writer.join(2000); }
    }

    private static void directOutputFailure(final boolean flushing) throws Exception {
        Fixture f = new Fixture();
        final IOException failure = new IOException("synthetic host channel failure");
        final int[] closes = {0}, writes = {0};
        Transport channel = new Transport() {
            public InputStream input() { return new ByteArrayInputStream(new byte[0]); }
            public OutputStream output() { return new OutputStream() {
                public void write(int value) throws IOException { writes[0]++; throw failure; }
                public void flush() throws IOException { throw failure; }
            }; }
            public String description() { return "synthetic host channel"; }
            public void close() { closes[0]++; }
        };
        AdbStream stream = new AdbStream(f.session, 7, 1000, channel); f.streams().put(7, stream);
        try { if (flushing) stream.output().flush(); else stream.output().write(1); throw new AssertionError("direct error hidden"); }
        catch (IOException error) { check(error == failure, "direct output preserves original error"); }
        check(closes[0] == 1 && !f.streams().containsKey(7), "direct output failure closes owned transport and unregisters");
        try { stream.output().write(2); throw new AssertionError("direct failure reused"); }
        catch (IOException error) { check(error == failure, "direct retry remains failed"); }
        check(closes[0] == 1 && writes[0] == (flushing ? 0 : 1), "retry neither reuses transport nor repeats cleanup");
    }

    private static final class LoopbackPeer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch full = new CountDownLatch(1);
        final Thread worker;
        volatile Socket socket;
        volatile Throwable error;
        volatile int sent;
        int largeLocal;
        LoopbackPeer() throws IOException {
            worker = new Thread(() -> {
                try {
                    socket = server.accept(); socket.setSoTimeout(5000);
                    InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                    AdbPacket.read(in, AdbPacket.MAX_PAYLOAD, true);
                    send(out, new AdbPacket(AdbPacket.CNXN, 0x01000001, AdbPacket.MAX_PAYLOAD, AdbPacket.text("device::\0")));
                    while (true) {
                        AdbPacket p = AdbPacket.read(in, AdbPacket.MAX_PAYLOAD, true);
                        if (p.command == AdbPacket.OPEN) {
                            send(out, new AdbPacket(AdbPacket.OKAY, p.arg0 + 100, p.arg0, null));
                            if (new String(p.payload, "UTF-8").startsWith("fixture:large")) { largeLocal = p.arg0; sendBlock(out); }
                            else send(out, new AdbPacket(AdbPacket.WRTE, p.arg0 + 100, p.arg0, new byte[]{42}));
                        } else if (p.command == AdbPacket.OKAY) {
                            if (p.arg0 == largeLocal && sent < 12) sendBlock(out);
                            else send(out, new AdbPacket(AdbPacket.CLSE, p.arg1, p.arg0, null));
                        }
                    }
                } catch (EOFException | SocketException expected) { }
                catch (Throwable failure) { error = failure; }
            }, "synthetic-adb-flow-peer");
            worker.setDaemon(true); worker.start();
        }
        private void sendBlock(OutputStream out) throws IOException {
            byte[] bytes = new byte[1024 * 1024]; Arrays.fill(bytes, (byte)sent);
            send(out, new AdbPacket(AdbPacket.WRTE, largeLocal + 100, largeLocal, bytes));
            if (++sent == 8) full.countDown();
        }
        private static void send(OutputStream out, AdbPacket packet) throws IOException { out.write(packet.encode()); out.flush(); }
        public void close() throws Exception {
            if (socket != null) socket.close(); server.close(); worker.join(2000);
            check(!worker.isAlive(), "loopback peer terminates");
            if (error != null) throw new AssertionError("loopback peer failed", error);
        }
    }

    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static final class Fixture implements Transport {
        final List<AdbPacket> packets = new ArrayList<AdbPacket>();
        final AdbSession session;
        IOException ackFailure, writeFailure, closeFailure;
        int writeAttempts;
        Fixture() throws Exception {
            session = new AdbSession(this, null, null);
            Field state = AdbSession.class.getDeclaredField("state"); state.setAccessible(true); state.set(session, AdbSession.State.CONNECTED);
        }
        AdbStream open(int local) throws Exception {
            return open(local, 30000);
        }
        AdbStream open(int local, int timeout) throws Exception {
            AdbStream stream = new AdbStream(session, local, timeout);
            streams().put(local, stream); stream.onOkay(local + 100); return stream;
        }
        @SuppressWarnings("unchecked") Map<Integer, AdbStream> streams() throws Exception {
            Field field = AdbSession.class.getDeclaredField("streams"); field.setAccessible(true); return (Map<Integer, AdbStream>)field.get(session);
        }
        int count(int command) { int count = 0; for (AdbPacket packet : packets) if (packet.command == command) count++; return count; }
        boolean interruptibleWrites;
        public InputStream input() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream output() { return new OutputStream() {
            public void write(int value) { throw new AssertionError("whole packets required"); }
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (interruptibleWrites && Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Synthetic interruptible transport");
                AdbPacket packet = SyntheticAdbPackets.decode(bytes, offset, length);
                if (packet.command == AdbPacket.OKAY && ackFailure != null) throw ackFailure;
                if (packet.command == AdbPacket.WRTE) { writeAttempts++; if (writeFailure != null) throw writeFailure; }
                if (packet.command == AdbPacket.CLSE && closeFailure != null) throw closeFailure;
                packets.add(packet);
            }
        }; }
        public String description() { return "in-memory flow fixture"; }
        public void close() { }
    }
}
