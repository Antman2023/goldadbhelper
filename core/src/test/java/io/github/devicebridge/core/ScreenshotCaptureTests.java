package io.github.devicebridge.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-memory adbd/SYNC fixtures only. No real device, shell, socket or remote file is touched. */
public final class ScreenshotCaptureTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        fastPath(); legacyIntegrity(); failureBoundaries(); cancellation();
        System.out.println("ScreenshotCaptureTests: " + assertions + " assertions passed (in-memory adbd fixtures only)");
    }

    private static void fastPath() throws Exception {
        try (Device d = new Device(false)) {
            ScreenshotCapture.Result shot = new ScreenshotCapture(d.session).capture(1000, 100000);
            check(Arrays.equals(d.png, shot.png), "exec PNG retains every binary byte");
            check(!shot.usedLegacyFallback && shot.cleanupWarning == null, "fast path reports no staging");
            check(d.execOpens == 1 && d.mkdirs == 0 && d.syncOpens == 0, "fast path avoids remote files");
        }
        try (Device d = new Device(false)) {
            d.png = "screencap: permission denied".getBytes(StandardCharsets.UTF_8);
            fails(d, 100000);
            check(d.mkdirs == 0, "command output failure does not trigger a fallback");
        }
        try (Device d = new Device(false)) {
            fails(d, 40); check(d.mkdirs == 0, "exec size limit does not trigger a fallback");
        }
        try (Device d = new Device(false)) {
            d.block = "exec";
            fails(d, 100000, 60);
            check(d.mkdirs == 0, "exec read timeout does not trigger a fallback");
        }
    }

    private static void legacyIntegrity() throws Exception {
        try (Device d = new Device(true)) {
            ScreenshotCapture.Result first = new ScreenshotCapture(d.session).capture(1000, 100000);
            check(first.usedLegacyFallback && first.cleanupWarning == null, "legacy fallback is explicit");
            check(Arrays.equals(d.png, first.png), "SYNC preserves CRLF, CRCRLF, NUL, and arbitrary PNG bytes");
            check(d.execOpens == 1 && d.mkdirs == 1 && d.shots == 1 && d.syncOpens == 1 && d.cleanups == 1, "legacy complete operation count");
            check(d.secureMkdir && d.secureCapture && d.safeCleanup, "private exclusive reservation and non-recursive exact cleanup");
            String previous = d.directory;
            new ScreenshotCapture(d.session).capture(1000, 100000);
            check(d.execOpens == 1, "explicit service rejection is cached per session");
            check(!previous.equals(d.directory), "each fallback owns a new random directory");
            check(d.session.state() == AdbSession.State.CONNECTED, "capture preserves shared ADB session");
        }
    }

    private static void failureBoundaries() throws Exception {
        try (Device d = new Device(true)) {
            d.statSize = 100001; fails(d, 100000);
            check(d.recvs == 0 && d.cleanups == 1, "oversized STAT rejected before pull and cleaned");
        }
        try (Device d = new Device(true)) {
            d.statSize = 40; fails(d, 100000);
            check(d.recvs == 1 && d.cleanups == 1, "growing file rejected by strict pull size and cleaned");
        }
        try (Device d = new Device(true)) {
            d.statSize = 90000; fails(d, 100000);
            check(d.cleanups == 1, "truncated pull cleaned");
        }
        try (Device d = new Device(true)) {
            d.mode = 0120777; fails(d, 100000);
            check(d.recvs == 0 && d.cleanups == 1, "symlink not followed");
        }
        try (Device d = new Device(true)) {
            d.mkdirExit = 1; fails(d, 100000);
            check(d.shots == 0 && d.cleanups == 0, "failed exclusive mkdir never captures or removes an unowned path");
        }
        try (Device d = new Device(true)) {
            d.shotExit = 7;
            IOException error = fails(d, 100000);
            check(error.getMessage().contains("exit=7") && d.cleanups == 1 && d.syncOpens == 0, "verified legacy shell exit fails capture and cleans owned directory");
        }
        try (Device d = new Device(true)) {
            d.cleanupExit = 1;
            ScreenshotCapture.Result shot = new ScreenshotCapture(d.session).capture(1000, 100000);
            check(Arrays.equals(d.png, shot.png) && shot.cleanupWarning != null, "successful PNG retained with cleanup warning");
            check(shot.cleanupWarning.contains(d.directory), "warning identifies only owned residual path");
        }
        try (Device d = new Device(true)) {
            d.cleanupExit = 1; d.shotExit = 9;
            IOException error = fails(d, 100000);
            check(error instanceof ScreenshotCapture.Failure && ((ScreenshotCapture.Failure)error).cleanupWarning != null,
                    "primary capture error and cleanup error both retained");
            check(error.getMessage().contains("exit=9") && error.getMessage().contains(d.directory), "combined failure explains both results");
        }
        try (Device d = new Device(true)) {
            ScreenshotCapture capture = new ScreenshotCapture(d.session); capture.close();
            try { capture.capture(1000, 100000); throw new AssertionError("closed operation ran"); }
            catch (InterruptedIOException expected) { assertions++; }
            check(d.execOpens == 0 && d.mkdirs == 0, "cancel-before-start has no wire side effects");
        }
    }

    private static void cancellation() throws Exception {
        for (String phase : new String[]{"exec", "mkdir", "shot", "recv"}) {
            try (Device d = new Device(!phase.equals("exec"))) {
                d.block = phase;
                ScreenshotCapture capture = new ScreenshotCapture(d.session);
                AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
                Thread thread = new Thread(() -> {
                    try { capture.capture(30000, 100000); outcome.set(new AssertionError("cancel returned success")); }
                    catch (Throwable result) { outcome.set(result); }
                });
                thread.start(); check(d.blocked.await(2, TimeUnit.SECONDS), "capture blocked during " + phase);
                capture.close(); thread.join(2000);
                check(!thread.isAlive() && outcome.get() instanceof IOException, "close promptly cancels " + phase);
                check(d.session.state() == AdbSession.State.CONNECTED, "cancel preserves session during " + phase);
                check(d.closedLocals.contains(d.blockedLocal), "cancel explicitly closes the active ADB channel during " + phase);
                if (phase.equals("shot") || phase.equals("recv")) check(d.cleanups == 1, "cancel cleans owned directory during " + phase);
                if (phase.equals("mkdir")) {
                    check(d.cleanups == 0, "cancel does not delete an unverified mkdir result");
                    check(capture.cleanupWarning() != null, "unknown reservation has explicit warning");
                }
            }
        }
    }

    private static IOException fails(Device d, int limit) throws Exception { return fails(d, limit, 1000); }
    private static IOException fails(Device d, int limit, int timeout) throws Exception {
        try { new ScreenshotCapture(d.session).capture(timeout, limit); throw new AssertionError("expected capture failure"); }
        catch (IOException expected) { assertions++; return expected; }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static byte[] le32(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] join(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] bytes : arrays) out.write(bytes); return out.toByteArray();
    }
    private static byte[] png() {
        byte[] png = new byte[70000]; new Random(31).nextBytes(png);
        System.arraycopy(new byte[]{(byte)137,80,78,71,13,10,26,10}, 0, png, 0, 8);
        ByteBuffer.wrap(png).putInt(8, 13).putInt(12, 0x49484452).putInt(16, 80).putInt(20, 100);
        System.arraycopy(new byte[]{13,10,13,13,10,0,(byte)255}, 0, png, 100, 7); return png;
    }
    private static final class QueueInput extends InputStream {
        final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>(); int offset; boolean closed;
        synchronized void feed(byte[] bytes) { queue.add(bytes); notifyAll(); }
        public int read() throws IOException { byte[] b = new byte[1]; return read(b,0,1) < 0 ? -1 : b[0]&255; }
        public synchronized int read(byte[] b, int at, int count) throws IOException {
            if (count == 0) return 0;
            while (queue.isEmpty() && !closed) try { wait(); } catch (InterruptedException e) { throw new InterruptedIOException("fixture interrupted"); }
            if (queue.isEmpty()) return -1;
            byte[] head = queue.peek(); int n = Math.min(count, head.length - offset); System.arraycopy(head,offset,b,at,n); offset += n;
            if (offset == head.length) { queue.remove(); offset = 0; } return n;
        }
        public synchronized void close() { closed = true; notifyAll(); }
    }
    private static final class Device implements Transport {
        final QueueInput incoming = new QueueInput(); final AdbSession session;
        final Map<Integer, ByteArrayOutputStream> sync = new HashMap<Integer, ByteArrayOutputStream>();
        final Set<Integer> closedLocals = Collections.synchronizedSet(new HashSet<Integer>());
        final CountDownLatch blocked = new CountDownLatch(1);
        final boolean rejectExec; byte[] png = png(); long statSize = -1; int mode = 0100600;
        volatile int execOpens, mkdirs, shots, syncOpens, cleanups, recvs;
        int mkdirExit, shotExit, cleanupExit; volatile int blockedLocal; volatile String block, directory;
        boolean secureMkdir, secureCapture, safeCleanup;
        Device(boolean legacy) throws IOException {
            rejectExec = legacy; session = new AdbSession(this, null, null); session.connect();
        }
        public InputStream input() { return incoming; }
        public OutputStream output() { return new OutputStream() {
            public void write(int b) { throw new AssertionError("whole ADB packets required"); }
            public void write(byte[] b,int offset,int length) throws IOException {
                AdbPacket p = AdbPacket.read(new ByteArrayInputStream(b,offset,length),AdbPacket.MAX_PAYLOAD,true);
                if (p.command == AdbPacket.CNXN) send(new AdbPacket(AdbPacket.CNXN,0x01000000,4096,AdbPacket.text("device::\0")));
                else if (p.command == AdbPacket.OPEN) open(p);
                else if (p.command == AdbPacket.CLSE) closedLocals.add(p.arg0);
                else if (p.command == AdbPacket.WRTE) {
                    send(new AdbPacket(AdbPacket.OKAY,p.arg1,p.arg0,null));
                    ByteArrayOutputStream requests = sync.get(p.arg0);
                    if (requests != null) { requests.write(p.payload); parseSync(p.arg0, requests); }
                }
            }
        }; }
        void send(AdbPacket p) { incoming.feed(p.encode()); }
        void data(int local, byte[] bytes) {
            for (int off=0;off<bytes.length;off+=3071)
                send(new AdbPacket(AdbPacket.WRTE,local+100,local,Arrays.copyOfRange(bytes,off,Math.min(bytes.length,off+3071))));
        }
        void finish(int local) { send(new AdbPacket(AdbPacket.CLSE,local+100,local,null)); }
        boolean blocked(String phase, int local) { if (phase.equals(block)) { blockedLocal = local; blocked.countDown(); return true; } return false; }
        void open(AdbPacket p) throws IOException {
            String service = new String(p.payload,StandardCharsets.UTF_8).replace("\0", ""); int local = p.arg0;
            if (service.startsWith("exec:")) {
                execOpens++;
                if (rejectExec) { send(new AdbPacket(AdbPacket.CLSE,0,local,null)); return; }
            }
            send(new AdbPacket(AdbPacket.OKAY,local+100,local,null));
            if (service.equals("exec:screencap -p")) { if (!blocked("exec", local)) { data(local,png); finish(local); } return; }
            if (service.equals("sync:")) { syncOpens++; sync.put(local,new ByteArrayOutputStream()); return; }
            check(service.startsWith("shell:"), "only known services requested");
            Matcher marker = Pattern.compile("GB_STATUS_[0-9a-f]+").matcher(service);
            check(marker.find(), "legacy shell has verified random exit framing");
            String id = marker.group(); int status;
            if (service.contains("mkdir ")) {
                mkdirs++; status = mkdirExit;
                Matcher path = Pattern.compile("/data/local/tmp/jinjiachong-shot-[0-9a-f]{32}").matcher(service);
                check(path.find(), "owned path has safe random namespace"); directory = path.group();
                secureMkdir = service.contains("umask 077; mkdir ") && !service.contains("mkdir -p");
                if (blocked("mkdir", local)) return;
            } else if (service.contains("screencap -p ")) {
                shots++; status = shotExit; secureCapture = service.contains("umask 077; screencap -p ") && service.contains(directory+"/screen.png");
                if (blocked("shot", local)) return;
            } else {
                cleanups++; status = cleanupExit;
                safeCleanup = service.contains("rm -f ") && service.contains(directory+"/screen.png") && service.contains(" && rmdir ")
                        && !service.contains("rm -rf") && !service.contains("rm -r ");
                check(safeCleanup, "cleanup never recursively removes paths");
            }
            data(local,("\r\n"+id+":"+status+"\r\n").getBytes(StandardCharsets.UTF_8)); finish(local);
        }
        void parseSync(int local, ByteArrayOutputStream request) throws IOException {
            byte[] wire = request.toByteArray(); if (wire.length < 8) return;
            int length = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
            if (wire.length < 8+length) return;
            check(wire.length == 8+length, "one SYNC request at a time"); request.reset();
            String verb = new String(wire,0,4,StandardCharsets.US_ASCII);
            String path = new String(wire,8,length,StandardCharsets.UTF_8);
            check(path.equals(directory+"/screen.png"), "only owned screenshot is read");
            if (verb.equals("STAT")) {
                data(local,join(ascii("STAT"),le32(mode),le32((int)(statSize<0?png.length:statSize)),le32(123)));
            } else {
                check(verb.equals("RECV"), "only STAT and RECV issued"); recvs++; if (blocked("recv", local)) return;
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                for(int off=0;off<png.length;off+=32768) {
                    int n=Math.min(32768,png.length-off); response.write(ascii("DATA")); response.write(le32(n)); response.write(png,off,n);
                }
                response.write(ascii("DONE")); response.write(le32(0)); data(local,response.toByteArray());
            }
        }
        public String description() { return "in-memory screenshot fixture"; }
        public void setReadTimeout(int ms) { }
        public void close() { incoming.close(); }
    }
}
