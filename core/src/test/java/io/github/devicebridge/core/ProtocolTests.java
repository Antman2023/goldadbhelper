package io.github.devicebridge.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline synthetic fixtures, independently constructed from the public wire
 * layouts. No sockets, USB devices, original application binaries, or shell
 * commands are used. Protocol references are linked on each implementation.
 * Run: java -ea -cp build/core-classes io.github.devicebridge.core.ProtocolTests
 */
public final class ProtocolTests {
    private static int assertions;
    private ProtocolTests() { }

    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        assertions = 0;
        syncListAndStat();
        syncPushAndPull();
        syncFailureAndCancellation();
        syncCancellationBoundaries();
        syncValidation();
        fastbootResponses();
        fastbootDownloadCommands();
        fastbootFailuresAndCancellation();
        fastbootTcpFraming();
        fastbootTcpMalformed();
        closeUnblocksFastboot();
        System.out.println("ProtocolTests: " + assertions + " assertions passed (synthetic only)");
    }

    private static void syncListAndStat() throws Exception {
        byte[] incoming = join(dent(".", 0040755, 0, 0), dent("..", 0040755, 0, 0),
                dent("中文 文件😀", 0100644, 0xffffffffL, 0x80000000L),
                dent("目录", 0040755, 123, 456), listDone(),
                join(ascii("STAT"), le32(0120777), le32(9), le32(0xffffffffL)));
        ShortInput input = new ShortInput(incoming, 1, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SyncClient sync = new SyncClient(input, output);
        List<SyncClient.Entry> entries = sync.list("/sdcard/");
        equal(2, entries.size(), "dot entries excluded");
        equal("中文 文件😀", entries.get(0).name, "Unicode name");
        equal("/sdcard/中文 文件😀", entries.get(0).path, "joined full path");
        equal(0xffffffffL, entries.get(0).size, "unsigned size");
        equal(0x80000000L, entries.get(0).mtime, "unsigned mtime");
        check(!entries.get(0).isDirectory && entries.get(1).isDirectory, "mode types");
        SyncClient.Entry entry = sync.stat("/sdcard/link");
        check(entry.isSymbolicLink(), "symbolic link metadata");
        equal(0xffffffffL, entry.mtime, "full LIST terminator consumed before STAT");
        equal("link", entry.name, "STAT basename");
        equalBytes(join(request("LIST", "/sdcard/"), request("STAT", "/sdcard/link")),
                output.toByteArray(), "request framing");
        sync.close();
        check(input.closed, "SYNC channel closed");
    }

    private static void syncPushAndPull() throws Exception {
        byte[] payload = patterned(150000);
        TrackInput source = new TrackInput(payload);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SyncClient push = new SyncClient(new ShortInput(header("OKAY", 0), 1, false), wire);
        List<Long> progress = new ArrayList<Long>();
        equal(payload.length, push.push("/sdcard/中文,a.bin", source, payload.length,
                0644, 0xfedcba98L, (done, total) -> { progress.add(done); return true; }), "pushed count");
        check(!source.closed, "source remains caller-owned");
        equal(0L, progress.get(0), "push progress starts at zero");
        equal((long) payload.length, progress.get(progress.size() - 1), "push progress finishes");
        ByteArrayInputStream recorded = new ByteArrayInputStream(wire.toByteArray());
        equal("SEND", readText(recorded, 4), "SEND command");
        equal("/sdcard/中文,a.bin,420", readUtf8(recorded, (int) readLe32(recorded)), "mode uses decimal and last comma");
        ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
        int chunks = 0;
        while (true) {
            String id = readText(recorded, 4);
            long value = readLe32(recorded);
            if (id.equals("DONE")) { equal(0xfedcba98L, value, "mtime wire value"); break; }
            equal("DATA", id, "push DATA");
            check(value > 0 && value <= 65536, "SYNC chunk bounded");
            reconstructed.write(read(recorded, (int) value));
            chunks++;
        }
        equal(3, chunks, "64 KiB chunking");
        equalBytes(payload, reconstructed.toByteArray(), "push exact bytes");
        equal(-1, recorded.read(), "no extra wire bytes");

        ByteArrayOutputStream incoming = new ByteArrayOutputStream();
        for (int offset = 0; offset < payload.length; offset += 32768) {
            int n = Math.min(32768, payload.length - offset);
            incoming.write(header("DATA", n)); incoming.write(payload, offset, n);
        }
        incoming.write(header("DONE", 0));
        TrackOutput destination = new TrackOutput();
        SyncClient pull = new SyncClient(new ShortInput(incoming.toByteArray(), 3, true), new ByteArrayOutputStream());
        equal(payload.length, pull.pull("/中文", destination, payload.length, (done, total) -> true), "pulled count");
        equalBytes(payload, destination.toByteArray(), "pull short-read fidelity");
        check(!destination.closed && destination.flushed, "destination flushed but not closed");

        ByteArrayOutputStream emptyWire = new ByteArrayOutputStream();
        SyncClient empty = new SyncClient(new ByteArrayInputStream(header("OKAY", 0)), emptyWire);
        equal(0L, empty.push("/empty", new ByteArrayInputStream(new byte[0]), -1, 0100644, 0, null), "unknown-size empty push");
        equalBytes(join(request("SEND", "/empty,33188"), header("DONE", 0)), emptyWire.toByteArray(), "empty push wire");
        SyncClient emptyPull = new SyncClient(new ByteArrayInputStream(header("DONE", 0)), new ByteArrayOutputStream());
        equal(0L, emptyPull.pull("/empty", new ByteArrayOutputStream(), 0, null), "empty pull");
    }

    private static void syncFailureAndCancellation() throws Exception {
        for (String operation : new String[]{"LIST", "STAT", "RECV", "SEND"}) {
            SyncClient sync = new SyncClient(new ByteArrayInputStream(failure("permission denied")), new ByteArrayOutputStream());
            IOException error = expect(IOException.class, () -> {
                if (operation.equals("LIST")) sync.list("/root");
                else if (operation.equals("STAT")) sync.stat("/root");
                else if (operation.equals("RECV")) sync.pull("/root", new ByteArrayOutputStream(), null);
                else sync.push("/root", new ByteArrayInputStream(new byte[0]), 0, 0644, 0, null);
            });
            check(error.getMessage().contains("permission denied"), operation + " propagates FAIL");
            check(sync.isClosed(), operation + " fails closed");
        }
        SyncClient truncated = new SyncClient(new ShortInput(join(ascii("STAT"), le32(1)), 1, false), new ByteArrayOutputStream());
        expect(EOFException.class, () -> truncated.stat("/file"));
        check(truncated.isClosed(), "short STAT closes");

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SyncClient cancelled = new SyncClient(new ByteArrayInputStream(new byte[0]), wire);
        expect(InterruptedIOException.class, () -> cancelled.push("/cancel", new ByteArrayInputStream(patterned(80000)),
                80000, 0644, 0, (done, total) -> done == 0));
        check(cancelled.isClosed(), "push cancel closes channel");
        equal(request("SEND", "/cancel,420").length + 8 + 65536, wire.size(), "cancel sends first chunk only, no DONE");

        ByteArrayOutputStream initialWire = new ByteArrayOutputStream();
        SyncClient initiallyCancelled = new SyncClient(new ByteArrayInputStream(new byte[0]), initialWire);
        expect(InterruptedIOException.class, () -> initiallyCancelled.pull("/cancel", new ByteArrayOutputStream(), (done, total) -> false));
        equal(0, initialWire.size(), "initial cancel sends no request");

        TrackOutput partial = new TrackOutput();
        SyncClient cancelPull = new SyncClient(new ByteArrayInputStream(join(header("DATA", 3), ascii("abc"), header("DONE", 0))),
                new ByteArrayOutputStream());
        expect(InterruptedIOException.class, () -> cancelPull.pull("/cancel", partial, (done, total) -> done == 0));
        equal("abc", new String(partial.toByteArray(), StandardCharsets.US_ASCII), "cancel preserves caller's partial output");
        check(!partial.closed, "cancel does not close caller output");

        SyncClient shortSource = new SyncClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        expect(EOFException.class, () -> shortSource.push("/file", new ByteArrayInputStream(new byte[]{1}), 2, 0644, 0, null));
        check(shortSource.isClosed(), "short source closes without DONE");

        SyncClient callbackFailure = new SyncClient(new ByteArrayInputStream(header("DONE", 0)), new ByteArrayOutputStream());
        expect(IllegalStateException.class, () -> callbackFailure.pull("/file", new ByteArrayOutputStream(), (done, total) -> {
            throw new IllegalStateException("UI disposed");
        }));
        check(callbackFailure.isClosed(), "throwing progress callback closes protocol");

        ByteArrayOutputStream reentrantWire = new ByteArrayOutputStream();
        SyncClient reentrant = new SyncClient(new ByteArrayInputStream(header("DONE", 0)), reentrantWire);
        expect(IllegalStateException.class, () -> reentrant.pull("/file", new ByteArrayOutputStream(), (done, total) -> {
            try { reentrant.stat("/nested"); } catch (IOException error) { throw new AssertionError(error); }
            return true;
        }));
        equal(0, reentrantWire.size(), "reentrant progress cannot interleave SYNC requests");
        check(reentrant.isClosed(), "reentrant callback fails closed");
    }

    private static void syncCancellationBoundaries() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            SyncClient sync = new SyncClient(new ByteArrayInputStream(header("DONE", 0)), wire);
            try {
                expect(IOException.class, () -> sync.pull("/file", new ByteArrayOutputStream(), (done, total) -> {
                    if (interrupt) Thread.currentThread().interrupt();
                    else try { sync.close(); } catch (IOException error) { throw new AssertionError(error); }
                    return true;
                }));
                equal(0, wire.size(), "callback cancellation sends no request even when returning true");
                check(sync.isClosed(), "callback cancellation leaves client closed");
                check(Thread.currentThread().isInterrupted() == interrupt, "callback interrupt flag preserved");
            } finally { Thread.interrupted(); sync.close(); }
        }
        // Cancel while the last bytes of each complete response are being returned.
        for (String operation : new String[]{"LIST", "STAT", "RECV", "SEND"}) {
            byte[] response = operation.equals("LIST") ? listDone() : operation.equals("STAT") ?
                    join(ascii("STAT"), le32(0100644), le32(0), le32(0)) : header(operation.equals("RECV") ? "DONE" : "OKAY", 0);
            for (boolean interrupt : new boolean[]{false, true}) {
                final SyncClient[] owner = new SyncClient[1];
                InputStream input = new ByteArrayInputStream(response) {
                    public synchronized int read(byte[] bytes, int offset, int length) {
                        int count = super.read(bytes, offset, length);
                        if (available() == 0) {
                            if (interrupt) Thread.currentThread().interrupt();
                            else try { owner[0].close(); } catch (IOException error) { throw new AssertionError(error); }
                        }
                        return count;
                    }
                };
                SyncClient sync = new SyncClient(input, new ByteArrayOutputStream()); owner[0] = sync;
                try {
                    expect(IOException.class, () -> {
                        if (operation.equals("LIST")) sync.list("/dir");
                        else if (operation.equals("STAT")) sync.stat("/file");
                        else if (operation.equals("RECV")) sync.pull("/file", new ByteArrayOutputStream(), null);
                        else sync.push("/file", new ByteArrayInputStream(new byte[0]), 0, 0644, 0, null);
                    });
                    check(sync.isClosed(), operation + " final-read cancellation cannot report success");
                    check(Thread.currentThread().isInterrupted() == interrupt, "terminal cancellation preserves flag");
                } finally { Thread.interrupted(); sync.close(); }
            }
        }
        for (boolean exact : new boolean[]{false, true}) {
            final int[] fallback = {0};
            InputStream source = new InputStream() {
                public int read(byte[] bytes, int offset, int length) { Thread.currentThread().interrupt(); return 0; }
                public int read() { fallback[0]++; return 1; }
            };
            try {
                expect(InterruptedIOException.class, () -> {
                    if (exact) SyncClient.readExactly(source, 1); else SyncClient.readSome(source, new byte[1], 1);
                });
                equal(0, fallback[0], "cancelled zero read does not enter another potentially blocking read");
            } finally { Thread.interrupted(); }
        }
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SyncClient push = new SyncClient(new ByteArrayInputStream(header("OKAY", 0)), wire);
        InputStream source = new ByteArrayInputStream(new byte[]{1}) {
            public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length); Thread.currentThread().interrupt(); return count;
            }
        };
        try {
            expect(InterruptedIOException.class, () -> push.push("/file", source, 1, 0644, 0, null));
            equalBytes(request("SEND", "/file,420"), wire.toByteArray(), "source-read cancellation sends neither DATA nor DONE");
            check(push.isClosed(), "source-read cancellation releases channel");
        } finally { Thread.interrupted(); push.close(); }
        final SyncClient[] owner = new SyncClient[1];
        TrackOutput destination = new TrackOutput() {
            public void flush() { super.flush(); try { owner[0].close(); } catch (IOException error) { throw new AssertionError(error); } }
        };
        owner[0] = new SyncClient(new ByteArrayInputStream(header("DONE", 0)), new ByteArrayOutputStream());
        expect(IOException.class, () -> owner[0].pull("/file", destination, null));
        check(destination.flushed && !destination.closed, "cancellation during final flush does not close caller-owned output");
    }

    private static void syncValidation() throws Exception {
        for (byte[] bad : new byte[][]{
                header("DATA", 65537), header("DATA", 0xffffffffL), header("FAIL", 65537),
                join(header("DATA", 3), ascii("a")), header("BOOM", 0)}) {
            SyncClient sync = new SyncClient(new ByteArrayInputStream(bad), new ByteArrayOutputStream());
            expect(IOException.class, () -> sync.pull("/file", new ByteArrayOutputStream(), null));
            check(sync.isClosed(), "malformed pull closed");
        }
        for (byte[] bad : new byte[][]{
                dent("../escape", 0100644, 0, 0), dent("bad\0name", 0100644, 0, 0),
                join(ascii("DENT"), le32(0100644), le32(0), le32(0), le32(0xffffffffL)),
                join(ascii("DENT"), le32(0100644), le32(0), le32(0), le32(2), new byte[]{(byte) 0xc3, 0x28}),
                join(ascii("DONE"), le32(0), le32(0), le32(0), le32(5))}) {
            SyncClient sync = new SyncClient(new ByteArrayInputStream(bad), new ByteArrayOutputStream());
            expect(IOException.class, () -> sync.list("/dir"));
        }
        SyncClient invalidPath = new SyncClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        expect(IllegalArgumentException.class, () -> invalidPath.list("/bad\0path"));
        expect(IOException.class, () -> invalidPath.list(repeat("中", 342)));
        expect(IOException.class, () -> invalidPath.list("/bad\ud800"));
        check(!invalidPath.isClosed(), "invalid local arguments do not send protocol bytes");
        SyncClient shortPull = new SyncClient(new ByteArrayInputStream(header("DONE", 0)), new ByteArrayOutputStream());
        expect(EOFException.class, () -> shortPull.pull("/file", new ByteArrayOutputStream(), 1, null));
        SyncClient longPull = new SyncClient(new ByteArrayInputStream(join(header("DATA", 2), ascii("ab"))), new ByteArrayOutputStream());
        expect(IOException.class, () -> longPull.pull("/file", new ByteArrayOutputStream(), 1, null));
        SyncClient missing = new SyncClient(new ByteArrayInputStream(join(ascii("STAT"), new byte[12])), new ByteArrayOutputStream());
        expect(java.io.FileNotFoundException.class, () -> missing.stat("/missing"));
        SyncClient interrupted = new SyncClient(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        Thread.currentThread().interrupt();
        try { expect(InterruptedIOException.class, () -> interrupted.stat("/file")); }
        finally { Thread.interrupted(); }

        ByteArrayOutputStream emptyChunks = new ByteArrayOutputStream();
        for (int i = 0; i < 1025; i++) emptyChunks.write(header("DATA", 0));
        SyncClient noProgress = new SyncClient(new ByteArrayInputStream(emptyChunks.toByteArray()), new ByteArrayOutputStream());
        expect(IOException.class, () -> noProgress.pull("/file", new ByteArrayOutputStream(), null));
        check(noProgress.isClosed(), "endless empty chunks rejected");
    }

    private static void fastbootResponses() throws Exception {
        equal(FastbootClient.Kind.OKAY, FastbootClient.parseResponse(ascii("OKAYvalue")).kind, "OKAY parsing");
        equal(0xffffffffL, FastbootClient.parseResponse(ascii("DATAfFfFfFfF")).dataSize, "unsigned DATA hex");
        equal("raw", FastbootClient.parseResponse(ascii("TEXTraw\0ignored")).message, "TEXT terminator");
        for (String malformed : new String[]{"", "OKA", "WHATx", "DATA0000000", "DATA000000000", "DATA0000000g", "DATA-0000001"})
            expect(IOException.class, () -> FastbootClient.parseResponse(ascii(malformed)));
        expect(IOException.class, () -> FastbootClient.parseResponse(ascii("OKAY" + repeat("x", 253))));

        ScriptPackets packets = new ScriptPackets("INFOhello", "TEXTworld\0", "OKAYserial-123", "FAILunknown variable", "OKAYnext");
        List<String> messages = new ArrayList<String>();
        FastbootClient fastboot = new FastbootClient(packets, (kind, message) -> messages.add(kind + ":" + message));
        equal("serial-123", fastboot.getvar("serialno"), "getvar result");
        equal(Arrays.asList("INFO:hello", "TEXT:world"), messages, "INFO and TEXT stay distinct");
        IOException error = expect(FastbootClient.RemoteFailureException.class, () -> fastboot.getvar("unknown"));
        check(error.getMessage().contains("unknown variable"), "FAIL body visible");
        check(!packets.closed, "terminal FAIL leaves command transport synchronized");
        equal("next", fastboot.getvar("version"), "command after terminal FAIL");
        equalBytes(ascii("getvar:serialno"), packets.writes.get(0), "getvar no trailing NUL");
        fastboot.close();
        check(packets.closed, "Fastboot owns packet transport");
        ScriptPackets infoPackets = new ScriptPackets("INFOproduct: fixture", "OKAY", "FAILunknown OEM command");
        FastbootClient info = new FastbootClient(infoPackets);
        String partial = info.deviceInfo();
        check(partial.contains("getvar:all: OKAY") && partial.contains("unknown OEM command"), "optional OEM failure preserves getvar success");
        equalBytes(ascii("getvar:all"), infoPackets.writes.get(0), "info first requests standard variables");
        equalBytes(ascii("oem device-info"), infoPackets.writes.get(1), "info requests exact non-unlock OEM query");
        check(!info.isClosed(), "unsupported optional OEM query retains synchronized transport");
        ScriptPackets brokenInfoPackets = new ScriptPackets("BOOMinvalid");
        FastbootClient brokenInfo = new FastbootClient(brokenInfoPackets);
        expect(IOException.class, brokenInfo::deviceInfo);
        equal(1, brokenInfoPackets.writes.size(), "transport failure never issues second diagnostic");

    }

    private static void fastbootDownloadCommands() throws Exception {
        byte[] payload = patterned(140000);
        ScriptPackets packets = new ScriptPackets("INFOready", "DATA000222e0", "INFOreceived", "OKAY", "INFOwriting", "OKAYflashed",
                "OKAYerased", "OKAYrebooting", "OKAYbootloader");
        FastbootClient fastboot = new FastbootClient(packets);
        TrackInput source = new TrackInput(payload);
        List<Long> progress = new ArrayList<Long>();
        equal(payload.length, fastboot.download(source, payload.length, (done, total) -> {
            equal(payload.length, total, "download progress total"); progress.add(done); return true;
        }), "Fastboot download length");
        check(fastboot.hasDownloadedImage(), "download success arms explicit flash");
        equal(payload.length, fastboot.downloadedSize(), "downloaded size");
        check(!source.closed, "Fastboot source remains open");
        equalBytes(ascii("download:000222e0"), packets.writes.get(0), "download command length hex");
        equal(4, packets.writes.size(), "command plus three data chunks");
        equalBytes(payload, join(packets.writes.get(1), packets.writes.get(2), packets.writes.get(3)), "Fastboot data exact");
        equal("flashed", fastboot.flash("boot_a"), "flash terminal OKAY");
        check(!fastboot.hasDownloadedImage(), "image cannot be unintentionally reused");
        equal("erased", fastboot.erase("cache"), "erase response");
        equal("rebooting", fastboot.reboot(), "reboot response");
        equal("bootloader", fastboot.rebootBootloader(), "reboot bootloader response");
        equalBytes(ascii("flash:boot_a"), packets.writes.get(4), "partition target unchanged");
        equalBytes(ascii("erase:cache"), packets.writes.get(5), "erase target unchanged");
        equalBytes(ascii("reboot"), packets.writes.get(6), "reboot wire");
        equalBytes(ascii("reboot-bootloader"), packets.writes.get(7), "reboot-bootloader wire");

        ScriptPackets empty = new ScriptPackets("DATA00000000", "OKAY", "OKAYbooted");
        FastbootClient boot = new FastbootClient(empty);
        equal(0L, boot.download(new ByteArrayInputStream(new byte[0]), 0, null), "zero-byte data phase");
        equal("booted", boot.boot(), "boot response");
        equal(2, empty.writes.size(), "zero-length data doesn't emit spurious packet");
        equalBytes(ascii("boot"), empty.writes.get(1), "boot wire");
    }

    private static void fastbootFailuresAndCancellation() throws Exception {
        for (String reply : new String[]{"DATA00000002", "OKAY", "DATA0000000g", "BOOM"}) {
            ScriptPackets packets = new ScriptPackets(reply);
            FastbootClient fastboot = new FastbootClient(packets);
            expect(IOException.class, () -> fastboot.download(new ByteArrayInputStream(new byte[]{1}), 1, null));
            check(packets.closed && !fastboot.hasDownloadedImage(), "invalid download handshake closes");
            equal(1, packets.writes.size(), "no data on invalid DATA response");
        }
        ScriptPackets shortPackets = new ScriptPackets("DATA00000002");
        FastbootClient shortSource = new FastbootClient(shortPackets);
        expect(EOFException.class, () -> shortSource.download(new ByteArrayInputStream(new byte[]{1}), 2, null));
        check(shortPackets.closed, "short Fastboot source closes");

        ScriptPackets cancelledPackets = new ScriptPackets("DATA00013880", "OKAY");
        FastbootClient cancelled = new FastbootClient(cancelledPackets);
        expect(InterruptedIOException.class, () -> cancelled.download(new ByteArrayInputStream(patterned(80000)), 80000,
                (done, total) -> done == 0));
        check(cancelledPackets.closed, "Fastboot cancellation closes");
        equal(2, cancelledPackets.writes.size(), "cancel after first chunk");
        equal(1, cancelledPackets.next, "cancellation does not consume terminal OKAY");

        ScriptPackets failedPackets = new ScriptPackets("DATA00000001", "FAILwrite rejected");
        FastbootClient failed = new FastbootClient(failedPackets);
        expect(FastbootClient.RemoteFailureException.class, () -> failed.download(new ByteArrayInputStream(new byte[]{1}), 1, null));
        check(!failed.hasDownloadedImage(), "failed download cannot arm flash");
        expect(IOException.class, () -> failed.flash("boot"));
        equal(2, failedPackets.writes.size(), "failed download sends no flash");

        ScriptPackets guards = new ScriptPackets();
        FastbootClient guarded = new FastbootClient(guards);
        expect(IOException.class, () -> guarded.flash("boot"));
        expect(IOException.class, () -> guarded.boot());
        expect(IllegalArgumentException.class, () -> guarded.erase("boot;reboot"));
        expect(IllegalArgumentException.class, () -> guarded.getvar("serialno\nreboot"));
        expect(IllegalArgumentException.class, () -> guarded.download(new ByteArrayInputStream(new byte[0]), 0x100000000L, null));
        expect(IllegalArgumentException.class, () -> guarded.download(new ByteArrayInputStream(new byte[0]), -1, null));
        equal(0, guards.writes.size(), "invalid arguments send no commands");
        expect(IOException.class, () -> guarded.getvar(repeat("a", 4096)));
        equal(0, guards.writes.size(), "oversized command not transmitted");

        ScriptPackets reentrantPackets = new ScriptPackets("INFOworking", "OKAY");
        final FastbootClient[] reentrant = new FastbootClient[1];
        reentrant[0] = new FastbootClient(reentrantPackets, (kind, text) -> {
            try { reentrant[0].getvar("nested"); } catch (IOException error) { throw new AssertionError(error); }
        });
        expect(IllegalStateException.class, () -> reentrant[0].getvar("version"));
        equal(1, reentrantPackets.writes.size(), "listener cannot interleave Fastboot commands");
        check(reentrantPackets.closed, "reentrant listener closes protocol");
    }

    private static void fastbootTcpFraming() throws Exception {
        MemoryTransport wire = new MemoryTransport(join(ascii("FB01"), frame(ascii("INFOstarting")), frame(ascii("OKAY0.4"))), 1, true);
        FastbootTcpTransport tcp = new FastbootTcpTransport(wire);
        FastbootClient fastboot = new FastbootClient(tcp);
        equal("0.4", fastboot.getvar("version"), "TCP short-read packet framing");
        equalBytes(join(ascii("FB01"), frame(ascii("getvar:version"))), wire.output.toByteArray(), "TCP handshake plus BE frame");
        fastboot.close();
        check(wire.closed, "TCP ownership close");

        MemoryTransport newer = new MemoryTransport(join(ascii("FB12"), frame(new byte[0])), 2, false);
        FastbootTcpTransport negotiated = new FastbootTcpTransport(newer);
        equal(0, negotiated.readPacket(256).length, "newer peer negotiates version one");
        negotiated.writePacket(ascii("xABCy"), 1, 3);
        equalBytes(join(ascii("FB01"), frame(ascii("ABC"))), newer.output.toByteArray(), "offset write packet");
        negotiated.close();
    }

    private static void fastbootTcpMalformed() throws Exception {
        for (String hello : new String[]{"FB00", "FBx1", "XY01", "FB0"}) {
            MemoryTransport wire = new MemoryTransport(ascii(hello), 1, false);
            expect(IOException.class, () -> new FastbootTcpTransport(wire));
            check(wire.closed, "invalid handshake closes underlying stream");
        }
        for (byte[] body : new byte[][]{
                be64(257), be64(-1L), be64(Long.MIN_VALUE),
                new byte[]{0, 0, 0}, join(be64(4), ascii("OKA"))}) {
            MemoryTransport wire = new MemoryTransport(join(ascii("FB01"), body), 2, true);
            FastbootTcpTransport tcp = new FastbootTcpTransport(wire);
            expect(IOException.class, () -> tcp.readPacket(256));
            check(wire.closed, "invalid/truncated TCP frame closes");
        }
        MemoryTransport wire = new MemoryTransport(ascii("FB01"), 4, false);
        FastbootTcpTransport tcp = new FastbootTcpTransport(wire);
        expect(IllegalArgumentException.class, () -> tcp.readPacket(-1));
        expect(IndexOutOfBoundsException.class, () -> tcp.writePacket(new byte[2], 1, 2));
        expect(IndexOutOfBoundsException.class, () -> tcp.writePacket(new byte[2], Integer.MAX_VALUE, 1));
        equalBytes(ascii("FB01"), wire.output.toByteArray(), "bad local bounds do not write frame");
        tcp.close();
    }

    private static void closeUnblocksFastboot() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        FastbootClient.PacketTransport blocking = new FastbootClient.PacketTransport() {
            public byte[] readPacket(int maxBytes) throws IOException {
                entered.countDown();
                try {
                    if (!closed.await(3, TimeUnit.SECONDS)) throw new IOException("test timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                throw new EOFException("closed");
            }
            public void writePacket(byte[] bytes, int offset, int length) { }
            public String description() { return "synthetic blocking transport"; }
            public void close() { closed.countDown(); }
        };
        FastbootClient client = new FastbootClient(blocking);
        AtomicReference<Throwable> result = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { client.getvar("serialno"); result.set(new AssertionError("Expected close failure")); }
            catch (Throwable e) { result.set(e); }
        }, "protocol-test-blocked-read");
        thread.start();
        check(entered.await(3, TimeUnit.SECONDS), "blocking read started");
        client.close();
        thread.join(3000);
        check(!thread.isAlive(), "concurrent close isn't blocked by synchronized command");
        check(result.get() instanceof IOException, "concurrent close produces IOException");
    }

    private interface Throwing { void run() throws Exception; }
    private static <T extends Throwable> T expect(Class<T> kind, Throwing action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            check(kind.isInstance(error), "expected " + kind.getSimpleName() + ", got " + error);
            return kind.cast(error);
        }
        throw new AssertionError("Expected " + kind.getSimpleName());
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        if (expected instanceof Number && actual instanceof Number)
            check(((Number) expected).longValue() == ((Number) actual).longValue(), message + ": expected " + expected + ", got " + actual);
        else check(expected.equals(actual), message + ": expected " + expected + ", got " + actual);
    }
    private static void equalBytes(byte[] expected, byte[] actual, String message) { check(Arrays.equals(expected, actual), message); }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] join(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }
    private static byte[] le32(long value) {
        return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
    private static byte[] be64(long value) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) { result[i] = (byte) value; value >>>= 8; }
        return result;
    }
    private static byte[] header(String id, long value) { return join(ascii(id), le32(value)); }
    private static byte[] request(String id, String path) { return join(header(id, utf8(path).length), utf8(path)); }
    private static byte[] failure(String message) { return join(header("FAIL", utf8(message).length), utf8(message)); }
    private static byte[] dent(String name, long mode, long size, long time) {
        return join(ascii("DENT"), le32(mode), le32(size), le32(time), le32(utf8(name).length), utf8(name));
    }
    private static byte[] listDone() { return join(ascii("DONE"), new byte[16]); }
    private static byte[] frame(byte[] payload) { return join(be64(payload.length), payload); }
    private static byte[] patterned(int size) { byte[] data = new byte[size]; for (int i = 0; i < size; i++) data[i] = (byte) (i * 31); return data; }
    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(value); return out.toString(); }
    private static byte[] read(InputStream input, int size) throws IOException {
        byte[] out = new byte[size]; int offset = 0;
        while (offset < size) { int n = input.read(out, offset, size - offset); if (n < 0) throw new EOFException(); offset += n; }
        return out;
    }
    private static String readText(InputStream input, int size) throws IOException { return new String(read(input, size), StandardCharsets.US_ASCII); }
    private static String readUtf8(InputStream input, int size) throws IOException { return new String(read(input, size), StandardCharsets.UTF_8); }
    private static long readLe32(InputStream input) throws IOException {
        byte[] bytes = read(input, 4); return (bytes[0] & 255L) | ((bytes[1] & 255L) << 8) | ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
    }

    private static final class ShortInput extends InputStream {
        private final ByteArrayInputStream source;
        private final int chunk;
        private final boolean zeroReads;
        private boolean returnZero;
        boolean closed;
        ShortInput(byte[] bytes, int chunk, boolean zeroReads) { source = new ByteArrayInputStream(bytes); this.chunk = chunk; this.zeroReads = zeroReads; }
        public int read() { return source.read(); }
        public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            returnZero = !returnZero;
            if (zeroReads && returnZero) return 0;
            return source.read(bytes, offset, Math.min(chunk, length));
        }
        public void close() { closed = true; }
    }
    private static final class TrackInput extends ByteArrayInputStream {
        boolean closed;
        TrackInput(byte[] bytes) { super(bytes); }
        public void close() { closed = true; }
    }
    private static class TrackOutput extends ByteArrayOutputStream {
        boolean closed, flushed;
        public void close() { closed = true; }
        public void flush() { flushed = true; }
    }
    private static final class ScriptPackets implements FastbootClient.PacketTransport {
        final List<byte[]> writes = new ArrayList<byte[]>();
        final String[] replies;
        int next;
        boolean closed;
        ScriptPackets(String... replies) { this.replies = replies; }
        public byte[] readPacket(int maxBytes) throws IOException {
            equal(256, maxBytes, "full current Fastboot status capacity");
            if (next >= replies.length) throw new EOFException("script exhausted");
            return ascii(replies[next++]);
        }
        public void writePacket(byte[] bytes, int offset, int length) throws IOException {
            if (closed) throw new IOException("closed");
            writes.add(Arrays.copyOfRange(bytes, offset, offset + length));
        }
        public String description() { return "synthetic packet transport"; }
        public void close() { closed = true; }
    }
    private static final class MemoryTransport implements Transport {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;
        MemoryTransport(byte[] incoming, int chunk, boolean zeroReads) { input = new ShortInput(incoming, chunk, zeroReads); }
        public InputStream input() { return input; }
        public OutputStream output() { return output; }
        public String description() { return "synthetic byte transport"; }
        public void close() throws IOException { closed = true; input.close(); output.close(); }
    }
}
