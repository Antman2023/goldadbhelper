package io.github.devicebridge.android;

import android.hardware.usb.*;
import io.github.devicebridge.core.FastbootClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual USB adapter with Android USB stand-ins; no attached devices or native I/O. */
public final class UsbTransportTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        adbBounds(); adbShortTransfers(); adbCancellation(); adbCloseAndConstruction();
        fastbootBoundsAndPackets(); fastbootCancellation(); failedTransfers(); constructionBoundaries();
        concurrentClose(); closeUnblocksRead();
        System.out.println("UsbTransportTests: " + assertions + " assertions passed (Android USB stand-ins only)");
    }

    private static void adbBounds() throws Exception {
        Fixture f = new Fixture(1);
        try (UsbTransport transport = f.adb()) {
            InputStream input = transport.input(); OutputStream output = transport.output();
            for (int[] range : new int[][]{{-1, 0}, {0, -1}, {3, 0}, {1, 2}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}}) {
                expect(IndexOutOfBoundsException.class, () -> input.read(new byte[2], range[0], range[1]));
                expect(IndexOutOfBoundsException.class, () -> output.write(new byte[2], range[0], range[1]));
            }
            expect(NullPointerException.class, () -> input.read(null, 0, 0));
            expect(NullPointerException.class, () -> output.write(null, 0, 0));
            expect(IllegalArgumentException.class, () -> transport.setReadTimeout(-1));
            equal(0, input.read(new byte[2], 2, 0), "zero read returns zero");
            output.write(new byte[2], 2, 0);
            equal(0, f.connection.calls, "invalid/empty ranges never reach USB");
            equal(0, f.connection.closes, "local validation leaves transport usable");
        }
    }

    private static void adbShortTransfers() throws Exception {
        Fixture f = new Fixture(1);
        try (UsbTransport transport = f.adb()) {
            byte[] target = new byte[9];
            f.connection.results.addAll(Arrays.asList(0, -1, 3));
            equal(3, transport.input().read(target, 2, 5), "empty/timeout poll followed by short read");
            check(target[1] == 0 && target[2] == 42 && target[4] == 42 && target[5] == 0, "read respects offset and short count");
            byte[] source = new byte[40004];
            for (int i = 0; i < source.length; i++) source[i] = (byte)i;
            f.connection.results.addAll(Arrays.asList(3, 16000));
            transport.output().write(source, 2, 40000);
            check(Arrays.equals(Arrays.copyOfRange(source, 2, 40002), f.connection.written.toByteArray()), "short writes preserve every byte in order");
            check(f.connection.maxLength <= 16384, "USB transfers fit legacy 16 KiB limit");
            transport.setReadTimeout(50);
            f.connection.hook = () -> check(f.connection.lastTimeout >= 1 && f.connection.lastTimeout <= 50, "poll honors short configured timeout");
            equal(1, transport.input().read(new byte[1]), "configured read timeout retains valid bytes");
        }
    }

    private static void adbCancellation() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            for (boolean read : new boolean[]{true, false}) {
                Fixture f = new Fixture(1); UsbTransport transport = f.adb();
                try {
                    f.connection.hook = () -> { if (interrupt) Thread.currentThread().interrupt(); else transport.close(); };
                    expect(IOException.class, () -> {
                        if (read) transport.input().read(new byte[1]); else transport.output().write(new byte[1]);
                    });
                    equal(1, f.connection.calls, "cancelled transfer never performs another bulk request");
                    equal(1, f.connection.closes, "cancellation closes USB connection once");
                    check(Thread.currentThread().isInterrupted() == interrupt, "USB cancellation preserves interrupt flag");
                } finally { Thread.interrupted(); transport.close(); }
            }
        }
        Fixture f = new Fixture(1); UsbTransport transport = f.adb();
        Thread.currentThread().interrupt();
        try {
            expect(InterruptedIOException.class, () -> transport.output().write(1));
            equal(0, f.connection.calls, "pre-interrupted write sends no USB bytes");
            check(Thread.currentThread().isInterrupted(), "pre-interrupted write retains flag");
        } finally { Thread.interrupted(); transport.close(); }
    }

    private static void adbCloseAndConstruction() throws Exception {
        Fixture f = new Fixture(1); UsbTransport transport = f.adb();
        f.connection.releaseFailure = new IllegalStateException("release failed");
        expect(IllegalStateException.class, transport::close);
        equal(1, f.connection.closes, "failed release still closes connection");
        transport.close(); equal(1, f.connection.releases, "close remains idempotent after release failure");
        Fixture denied = new Fixture(1); denied.manager.permission = false;
        expect(IOException.class, denied::adb); equal(0, denied.manager.opens, "no permission opens no device");
        Fixture rejected = new Fixture(1); rejected.connection.claimed = false;
        expect(IOException.class, rejected::adb); equal(1, rejected.connection.closes, "claim refusal closes opened device");
        Fixture thrown = new Fixture(1); thrown.connection.claimFailure = new SecurityException("permission revoked");
        expect(SecurityException.class, thrown::adb); equal(1, thrown.connection.closes, "throwing claim closes opened device");
    }

    private static void fastbootBoundsAndPackets() throws Exception {
        Fixture f = new Fixture(3);
        try (UsbFastbootTransport transport = f.fastboot()) {
            for (int max : new int[]{-1, 0, 257, Integer.MAX_VALUE})
                expect(IllegalArgumentException.class, () -> transport.readPacket(max));
            for (int[] range : new int[][]{{-1, 0}, {0, -1}, {3, 0}, {1, 2}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}})
                expect(IndexOutOfBoundsException.class, () -> transport.writePacket(new byte[2], range[0], range[1]));
            expect(NullPointerException.class, () -> transport.writePacket(null, 0, 0));
            transport.writePacket(new byte[2], 2, 0);
            equal(0, f.connection.calls, "invalid or empty Fastboot packet never reaches USB");
            f.connection.results.addAll(Arrays.asList(4, 8));
            equal(4, transport.readPacket(256).length, "first status is one USB transfer");
            equal(1, f.connection.calls, "short status does not read into next packet");
            equal(8, transport.readPacket(256).length, "next status remains separate");
            equal(2, f.connection.calls, "two statuses use two reads");
            byte[] image = new byte[40004];
            for (int i = 0; i < image.length; i++) image[i] = (byte)(i * 31);
            f.connection.results.addAll(Arrays.asList(3, 16000));
            transport.writePacket(image, 2, 40000);
            check(Arrays.equals(Arrays.copyOfRange(image, 2, 40002), f.connection.written.toByteArray()), "Fastboot short writes preserve bytes");
            check(f.connection.maxLength <= 16384, "Fastboot data fits legacy bulk limit");
        }
        Fixture integration = new Fixture(3);
        for (String packet : new String[]{"OKAYfixture", "DATA00000001", "OKAY", "OKAY"})
            integration.connection.packets.add(packet.getBytes(StandardCharsets.US_ASCII));
        try (FastbootClient client = new FastbootClient(integration.fastboot())) {
            check("fixture".equals(client.getvar("product")), "production Fastboot adapter carries a full client query");
            equal(1, (int)client.download(new ByteArrayInputStream(new byte[]{42}), 1, null), "production adapter carries image bytes");
            check(client.hasDownloadedImage(), "acknowledged image is ready for explicit operation");
            client.boot();
            check(!client.hasDownloadedImage(), "synthetic boot consumes image once");
            equal(4, integration.connection.readCalls, "status packets retained across complete client operations");
        }
    }

    private static void fastbootCancellation() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            for (boolean read : new boolean[]{true, false}) {
                Fixture f = new Fixture(3); UsbFastbootTransport transport = f.fastboot();
                try {
                    f.connection.hook = () -> { if (interrupt) Thread.currentThread().interrupt(); else transport.close(); };
                    expect(IOException.class, () -> {
                        if (read) transport.readPacket(256); else transport.writePacket(new byte[20000], 0, 20000);
                    });
                    equal(1, f.connection.calls, "Fastboot cancellation sends no further bulk request");
                    equal(1, f.connection.closes, "Fastboot cancellation closes once");
                    check(Thread.currentThread().isInterrupted() == interrupt, "Fastboot preserves interruption");
                } finally { Thread.interrupted(); transport.close(); }
            }
        }
    }

    private static void failedTransfers() throws Exception {
        for (boolean fastboot : new boolean[]{false, true}) {
            for (boolean read : new boolean[]{false, true}) {
                for (int count : read && !fastboot ? new int[]{2} : new int[]{-1, 0, 2}) {
                    Fixture f = new Fixture(fastboot ? 3 : 1);
                    try (Closeable transport = fastboot ? f.fastboot() : f.adb()) {
                        f.connection.results.add(count);
                        expect(IOException.class, () -> {
                            if (fastboot) {
                                if (read) ((UsbFastbootTransport)transport).readPacket(1);
                                else ((UsbFastbootTransport)transport).writePacket(new byte[1], 0, 1);
                            } else {
                                if (read) ((UsbTransport)transport).input().read(new byte[1]);
                                else ((UsbTransport)transport).output().write(42);
                            }
                        });
                        equal(1, f.connection.calls, "failed/invalid USB result cannot retry");
                        equal(1, f.connection.closes, "failed USB transfer closes connection");
                    }
                }
            }
        }
        Fixture detached = new Fixture(1);
        try (UsbTransport transport = detached.adb()) {
            detached.manager.devices.clear(); detached.connection.results.add(-1);
            expect(EOFException.class, () -> transport.input().read());
            equal(1, detached.connection.closes, "failed poll on detached device closes connection");
        }
        Fixture timed = new Fixture(1);
        try (UsbTransport transport = timed.adb()) {
            transport.setReadTimeout(1); timed.connection.results.add(-1);
            timed.connection.hook = () -> {
                equal(1, timed.connection.lastTimeout, "short read timeout is passed to native polling");
                try { Thread.sleep(5); } catch (InterruptedException error) { throw new AssertionError(error); }
            };
            expect(java.net.SocketTimeoutException.class, () -> transport.input().read());
            equal(1, timed.connection.closes, "timed-out read is terminal");
        }
        Fixture nativeFailure = new Fixture(1);
        try (UsbTransport transport = nativeFailure.adb()) {
            nativeFailure.connection.hook = () -> { throw new SecurityException("USB access revoked"); };
            expect(IOException.class, () -> transport.output().write(42));
            equal(1, nativeFailure.connection.closes, "native runtime failure closes connection");
        }
    }

    private static void constructionBoundaries() throws Exception {
        for (boolean fastboot : new boolean[]{false, true}) {
            Fixture f = new Fixture(fastboot ? 3 : 1);
            Thread.currentThread().interrupt();
            try {
                expect(InterruptedIOException.class, () -> { if (fastboot) f.fastboot(); else f.adb(); });
                equal(0, f.manager.opens, "pre-cancelled construction never opens USB");
                check(Thread.currentThread().isInterrupted(), "constructor preserves cancellation");
            } finally { Thread.interrupted(); }
            f.connection.claimHook = () -> Thread.currentThread().interrupt();
            try {
                expect(InterruptedIOException.class, () -> { if (fastboot) f.fastboot(); else f.adb(); });
                equal(1, f.connection.releases, "cancelled successful claim releases interface");
                equal(1, f.connection.closes, "cancelled claim closes device");
            } finally { Thread.interrupted(); }
        }
        Fixture wrong = new Fixture(3);
        expect(IOException.class, wrong::adb); equal(0, wrong.manager.opens, "ADB never opens a Fastboot-only device");
        Fixture missing = new Fixture(1); missing.manager.connection = null;
        expect(IOException.class, missing::adb);
    }

    private static void concurrentClose() throws Exception {
        for (boolean fastboot : new boolean[]{false, true}) {
            Fixture f = new Fixture(fastboot ? 3 : 1);
            Closeable transport = fastboot ? f.fastboot() : f.adb();
            CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            f.connection.releaseHook = () -> { entered.countDown(); await(resume); };
            Thread closer = new Thread(() -> { try { transport.close(); } catch (Throwable error) { failure.set(error); } });
            closer.start();
            try {
                check(entered.await(3, TimeUnit.SECONDS), "first closer entered interface release");
                transport.close();
                equal(1, f.connection.releases, "concurrent close cannot release interface twice");
            } finally { resume.countDown(); closer.join(3000); }
            check(!closer.isAlive() && failure.get() == null, "closer completes without failure");
            equal(1, f.connection.closes, "concurrent close closes device once");
        }
    }

    private static void closeUnblocksRead() throws Exception {
        for (boolean fastboot : new boolean[]{false, true}) {
            Fixture f = new Fixture(fastboot ? 3 : 1);
            Closeable transport = fastboot ? f.fastboot() : f.adb();
            CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            f.connection.closeHook = closed::countDown;
            f.connection.hook = () -> { entered.countDown(); await(closed); };
            Thread reader = new Thread(() -> {
                try {
                    if (fastboot) ((UsbFastbootTransport)transport).readPacket(256);
                    else ((UsbTransport)transport).input().read();
                    failure.set(new AssertionError("closed read reported success"));
                } catch (Throwable error) { failure.set(error); }
            });
            reader.start();
            try { check(entered.await(3, TimeUnit.SECONDS), "reader entered native transfer"); }
            finally { transport.close(); reader.join(3000); }
            check(!reader.isAlive() && failure.get() instanceof IOException, "close unblocks reader and rejects late bytes");
            equal(1, f.connection.closes, "reader cleanup cannot double-close connection");
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("synthetic USB wait timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private interface Throwing { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Throwing action) throws Exception {
        try { action.run(); } catch (Throwable error) { check(type.isInstance(error), "expected " + type.getSimpleName() + ", got " + error); return; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void equal(int expected, int actual, String message) { check(expected == actual, message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }

    private static final class Fixture {
        final FakeConnection connection = new FakeConnection();
        final UsbManager manager = new UsbManager();
        final UsbDevice device;
        Fixture(int protocol) {
            device = new UsbDevice(new UsbInterface(protocol, new UsbEndpoint(2, 128), new UsbEndpoint(2, 0)));
            manager.connection = connection; manager.devices.put(device.getDeviceName(), device);
        }
        UsbTransport adb() throws IOException { return new UsbTransport(manager, device); }
        UsbFastbootTransport fastboot() throws IOException { return new UsbFastbootTransport(manager, device); }
    }
    private static final class FakeConnection extends UsbDeviceConnection {
        final Queue<Integer> results = new ArrayDeque<>();
        final Queue<byte[]> packets = new ArrayDeque<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        int calls, readCalls, maxLength, lastTimeout, releases, closes;
        boolean claimed = true;
        RuntimeException claimFailure, releaseFailure;
        Runnable hook, claimHook, releaseHook, closeHook;
        public boolean claimInterface(UsbInterface iface, boolean force) { if (claimHook != null) claimHook.run(); if (claimFailure != null) throw claimFailure; return claimed; }
        public boolean releaseInterface(UsbInterface iface) { releases++; if (releaseHook != null) releaseHook.run(); if (releaseFailure != null) throw releaseFailure; return true; }
        public void close() { closes++; if (closeHook != null) closeHook.run(); }
        public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout) {
            if (calls++ > 100) throw new AssertionError("unexpected USB retry loop");
            maxLength = Math.max(maxLength, length); lastTimeout = timeout;
            boolean read = endpoint.getDirection() == 128;
            if (read) readCalls++;
            byte[] packet = read && !packets.isEmpty() ? packets.remove() : null;
            int result = packet != null ? packet.length : results.isEmpty() ? length : results.remove();
            if (result > 0 && result <= length) {
                if (packet != null) System.arraycopy(packet, 0, buffer, offset, result);
                else if (read) Arrays.fill(buffer, offset, offset + result, (byte)42);
                else written.write(buffer, offset, result);
            }
            if (hook != null) hook.run();
            return result; // Deliberately permits buffered completion after close/interruption.
        }
    }
}
