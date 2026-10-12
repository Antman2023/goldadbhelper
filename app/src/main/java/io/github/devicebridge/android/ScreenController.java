package io.github.devicebridge.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import io.github.devicebridge.core.AdbSession;
import io.github.devicebridge.core.AdbStream;
import io.github.devicebridge.core.AnnexBReader;
import io.github.devicebridge.core.DeviceCommands;
import io.github.devicebridge.core.LegacyShellCommand;
import io.github.devicebridge.core.ScrcpyProtocol;
import io.github.devicebridge.core.ScreenshotCapture;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent ADB screen client. The bundled server must be built from official scrcpy v2.4. */
public final class ScreenController implements Closeable {
    public interface Listener {
        void onStatus(String status);
        void onFrame(Bitmap bitmap, int deviceWidth, int deviceHeight);
        void onVideoSize(int width, int height);
        void onError(String error);
    }
    public interface CaptureCallback { void complete(byte[] png, String error); }
    public static final class Options {
        public boolean compatibility, stretch, renderAllFrames, turnScreenOff, stayAwake;
        public int shortSide, maxDimension = 1920, bitRate = 8000000, refreshIntervalMs = 700;
        Options copy() {
            Options o = new Options();
            o.compatibility = compatibility; o.stretch = stretch; o.renderAllFrames = renderAllFrames;
            o.turnScreenOff = turnScreenOff; o.stayAwake = stayAwake; o.maxDimension = maxDimension;
            o.shortSide = shortSide; o.bitRate = bitRate; o.refreshIntervalMs = refreshIntervalMs;
            return o;
        }
        void validate() {
            if (shortSide < 0 || shortSide > 2160) throw new IllegalArgumentException("短边分辨率范围无效");
            if (maxDimension != 0 && (maxDimension < 128 || maxDimension > 8192)) throw new IllegalArgumentException("分辨率范围无效");
            if (bitRate < 100000 || bitRate > 50000000) throw new IllegalArgumentException("视频码率范围无效");
            if (refreshIntervalMs < 250 || refreshIntervalMs > 10000) throw new IllegalArgumentException("截图刷新间隔范围无效");
        }
    }
    private final Context context;
    private final AdbSession session;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService video = Executors.newSingleThreadExecutor(r -> new Thread(r, "jinjiachong-screen"));
    private final ExecutorService captureWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "jinjiachong-capture"));
    private final ExecutorService closer = Executors.newSingleThreadExecutor(r -> new Thread(r, "jinjiachong-screen-close"));
    private final ThreadPoolExecutor commands = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(256), r -> new Thread(r, "jinjiachong-control"));
    private volatile Run current;
    private volatile boolean closed;
    private volatile int captureGeneration;
    private final AtomicBoolean framePending = new AtomicBoolean();
    private final Set<ScreenshotCapture> captures = Collections.newSetFromMap(new ConcurrentHashMap<ScreenshotCapture, Boolean>());

    public ScreenController(Context context, AdbSession session, Listener listener) {
        this.context = context.getApplicationContext(); this.session = session; this.listener = listener;
    }
    public boolean running() { Run r = current; return r != null && r.alive; }
    public boolean normalMode() { Run r = current; return r != null && r.alive && !r.options.compatibility; }
    public void start(Options options, Surface surface) {
        if (closed) throw new IllegalStateException("Controller closed");
        options.validate(); stop();
        Run run = new Run(options.copy()); current = run;
        run.future = video.submit(() -> {
            try {
                if (run.options.compatibility) screenshots(run);
                else {
                    if (surface == null || !surface.isValid()) throw new IOException("视频显示区域尚未就绪");
                    streamVideo(run, surface);
                }
            } catch (Exception e) {
                if (isCurrent(run)) error(run, detail(e) + run.logSuffix());
            } finally {
                run.alive = false;
                run.cleanup();
            }
        });
    }
    public void stop() {
        if (closer.isShutdown()) return;
        Run run = current; current = null;
        captureGeneration++;
        for (ScreenshotCapture s : captures) closer.execute(() -> closeQuietly(s));
        commands.getQueue().clear();
        if (run != null) {
            run.alive = false;
            if (run.future != null) run.future.cancel(true);
            // Closing a stream can write to the transport; never block the UI thread.
            closer.execute(run::cleanup);
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; stop(); video.shutdownNow(); commands.shutdownNow(); captureWorker.shutdownNow(); closer.shutdown();
    }
    private boolean isCurrent(Run run) { return !closed && current == run && run.alive; }
    private void post(Run run, Runnable action) { main.post(() -> { if (isCurrent(run)) action.run(); }); }
    private void status(Run run, String text) { post(run, () -> listener.onStatus(text)); }
    private void error(Run run, String text) { main.post(() -> { if (!closed && current == run) listener.onError(text); }); }
    private static String detail(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }

    private AdbStream open(Run run, String service, int timeout) throws IOException {
        synchronized (run.streams) {
            if (!isCurrent(run)) throw new InterruptedIOException("已停止");
            // Cleanup must not finish while an in-flight OPEN can still launch a server.
            AdbStream stream = session.open(service, timeout);
            if (!isCurrent(run)) { closeQuietly(stream); throw new InterruptedIOException("已停止"); }
            run.streams.add(stream);
            return stream;
        }
    }
    private static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
            if ((long) bytes.size() + count > limit) throw new IOException("返回数据超过安全大小限制");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    private static int[] pngSize(byte[] png) throws IOException {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (png.length < 24 || !Arrays.equals(signature, Arrays.copyOf(png, 8))) {
            String reason = new String(png, 0, Math.min(png.length, 300), StandardCharsets.UTF_8).trim();
            throw new IOException("目标未返回 PNG 截图" + (reason.isEmpty() ? "" : "：" + reason));
        }
        ByteBuffer b = ByteBuffer.wrap(png); int width = b.getInt(16), height = b.getInt(20);
        ScrcpyProtocol.validateSize(width, height); return new int[]{width, height};
    }
    private byte[] screenshot(Run run) throws IOException {
        ScreenshotCapture capture = new ScreenshotCapture(session);
        synchronized (run.shots) {
            if (!isCurrent(run)) throw new InterruptedIOException("已停止");
            run.shots.add(capture);
        }
        try {
            ScreenshotCapture.Result result = capture.capture(15000, 32 * 1024 * 1024);
            if (result.usedLegacyFallback && !run.legacyScreenshotNotice) {
                run.legacyScreenshotNotice = true;
                status(run, "旧版目标：通过临时文件与 SYNC 读取截图，每帧刷新会较慢");
            }
            if (result.cleanupWarning != null) {
                // Stop repeated captures rather than accumulating orphan screenshots.
                throw new IOException(result.cleanupWarning);
            }
            pngSize(result.png); return result.png;
        } finally { run.shots.remove(capture); closeQuietly(capture); }
    }
    private void screenshots(Run run) throws IOException, InterruptedException {
        status(run, "兼容模式：截图刷新，支持单击、长按和单指滑动");
        while (isCurrent(run)) {
            byte[] bytes = screenshot(run); int[] size = pngSize(bytes);
            BitmapFactory.Options decode = new BitmapFactory.Options(); decode.inPreferredConfig = Bitmap.Config.RGB_565;
            int max = run.options.maxDimension == 0 ? 1920 : run.options.maxDimension;
            decode.inSampleSize = 1;
            while (Math.max(size[0], size[1]) / decode.inSampleSize > max * 2) decode.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
            if (bitmap == null) throw new IOException("PNG 解码失败");
            if (framePending.compareAndSet(false, true)) main.post(() -> {
                try {
                    if (isCurrent(run)) {
                        listener.onFrame(bitmap, size[0], size[1]);
                        if (run.legacyScreenshotNotice) listener.onStatus("兼容模式 · " + size[0] + " × " + size[1] + " · 旧版目标通过临时文件刷新，速度较慢");
                    } else bitmap.recycle();
                }
                finally { framePending.set(false); }
            }); else bitmap.recycle();
            Thread.sleep(run.options.refreshIntervalMs);
        }
    }
    public void capture(CaptureCallback callback) {
        final int token = captureGeneration;
        if (closed) return;
        captureWorker.execute(() -> {
            byte[] png = null; String error = null, warning = null;
            ScreenshotCapture capture = new ScreenshotCapture(session); captures.add(capture);
            try {
                if (token != captureGeneration) throw new InterruptedIOException("已取消截图");
                ScreenshotCapture.Result shot = capture.capture(15000, 32 * 1024 * 1024);
                png = shot.png; warning = shot.cleanupWarning; pngSize(png);
            } catch (Exception e) { error = detail(e); }
            finally { captures.remove(capture); closeQuietly(capture); }
            final byte[] result = png; final String reason = error, cleanup = warning;
            main.post(() -> {
                if (!closed && token == captureGeneration) {
                    callback.complete(result, reason);
                    if (cleanup != null) listener.onError(cleanup);
                }
            });
        });
    }
    private void streamVideo(Run run, Surface surface) throws Exception {
        status(run, "正在检查目标 Android 版本…");
        LegacyShellCommand probe = new LegacyShellCommand();
        AdbStream property = open(run, "shell:" + probe.wrap("getprop ro.build.version.sdk"), 10000);
        int sdk;
        try {
            LegacyShellCommand.Reply reply = probe.parse(new String(bounded(property.input(), 32768), StandardCharsets.UTF_8));
            if (reply.exitCode != 0) throw new IOException("无法读取目标 Android 版本，请尝试兼容模式");
            try { sdk = Integer.parseInt(reply.output.trim()); }
            catch (NumberFormatException e) { throw new IOException("目标 Android 版本无效，请尝试兼容模式", e); }
        } finally { run.streams.remove(property); closeQuietly(property); }
        if (sdk < 21) throw new IOException("正常模式需要目标 Android 5.0 / API 21 或更高；当前 API " + sdk + "，请启用兼容模式");
        String id = String.format(Locale.US, "%08x", new SecureRandom().nextInt() & 0x7fffffff);
        status(run, "正在启动正常模式（独立构建 scrcpy 2.4）…");
        if (run.options.shortSide > 0) {
            int[] physical = pngSize(screenshot(run));
            run.options.maxDimension = Math.min(8192, (int) ((long) Math.max(physical[0], physical[1]) * run.options.shortSide / Math.min(physical[0], physical[1])));
        }
        run.workspace.deploy((done, total) -> isCurrent(run));
        String command = "CLASSPATH=" + DeviceCommands.quote(run.workspace.serverJar()) + " app_process / com.genymobile.scrcpy.Server 2.4 scid=" + id
                + " log_level=warn audio=false video=true control=true tunnel_forward=true send_device_meta=false"
                + " send_frame_meta=true send_dummy_byte=true send_codec_meta=true clipboard_autosync=false cleanup=true"
                + " power_on=true video_codec=h264 max_size=" + run.options.maxDimension + " video_bit_rate=" + run.options.bitRate
                + " stay_awake=" + run.options.stayAwake;
        synchronized (run.streams) {
            if (!isCurrent(run)) throw new InterruptedIOException("已停止");
            run.serverLaunchAttempted = true;
            run.server = open(run, "shell:" + command, 600000);
            Thread logThread = new Thread(() -> {
                try {
                    byte[] b = new byte[1024]; int n;
                    while ((n = run.server.input().read(b)) != -1) run.appendLog(new String(b, 0, n, StandardCharsets.UTF_8));
                    run.serverExited = true;
                } catch (IOException ignored) { }
                finally { run.serverFinished.countDown(); }
            }, "jinjiachong-server-log"); logThread.setDaemon(true); logThread.start();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); IOException last = null;
        while (isCurrent(run) && System.nanoTime() < deadline) {
            try { run.videoStream = open(run, "localabstract:scrcpy_" + id, 15000); break; }
            catch (IOException e) { last = e; Thread.sleep(150); }
        }
        if (run.videoStream == null) throw new IOException("无法连接投屏服务" + (last == null ? "" : "：" + detail(last)));
        DataInputStream input = new DataInputStream(new BufferedInputStream(run.videoStream.input(), 65536));
        if (input.readUnsignedByte() != 0) throw new IOException("投屏协议握手错误");
        run.control = open(run, "localabstract:scrcpy_" + id, 15000);
        Thread controlDrain = new Thread(() -> {
            byte[] discard = new byte[4096];
            while (isCurrent(run)) {
                try { AdbStream stream = run.control; if (stream == null || stream.input().read(discard) == -1) return; }
                catch (java.net.SocketTimeoutException idle) { /* A quiet control channel is normal. */ }
                catch (IOException ended) { return; }
            }
        }, "jinjiachong-control-replies");
        controlDrain.setDaemon(true); controlDrain.start();
        int[] initialSize = ScrcpyProtocol.readVideoHeader(input);
        post(run, () -> listener.onVideoSize(initialSize[0], initialSize[1]));
        if (run.options.turnScreenOff) writeControl(run, new byte[]{10, 0});
        status(run, "正常模式：H.264 视频与多点触控；实际兼容性取决于目标系统");
        byte[] latestSps = null, latestPps = null;
        while (isCurrent(run)) {
            ScrcpyProtocol.Packet packet = ScrcpyProtocol.readPacket(input);
            if (packet.configuration) {
                AnnexBReader nals = new AnnexBReader(new ByteArrayInputStream(packet.bytes)); byte[] nal;
                while ((nal = nals.nextNal()) != null) { if ((nal[0] & 31) == 7) latestSps = nal; if ((nal[0] & 31) == 8) latestPps = nal; }
                if (latestSps == null || latestPps == null) continue;
                int[] size = AnnexBReader.dimensions(latestSps);
                if (run.decoder != null) run.decoder.close();
                run.decoder = new Decoder(run, surface, size, latestSps, latestPps);
                post(run, () -> listener.onVideoSize(size[0], size[1]));
            } else {
                if (run.decoder == null) throw new IOException("视频帧早于编码配置");
                run.decoder.queue(packet);
            }
        }
    }
    private void submit(Run run, IoAction action) {
        if (!isCurrent(run)) return;
        try { commands.execute(() -> {
            if (!isCurrent(run)) return;
            try { action.run(); } catch (Exception e) { if (isCurrent(run)) error(run, "控制失败：" + detail(e)); }
        }); } catch (RejectedExecutionException e) {
            error(run, "控制队列已满，已停止会话以避免滞留触摸；请重新连接");
            run.alive = false; closer.execute(run::cleanup);
        }
    }
    private interface IoAction { void run() throws IOException; }
    private void shell(Run run, String command) throws IOException {
        LegacyShellCommand frame = new LegacyShellCommand();
        AdbStream stream = open(run, "shell:" + frame.wrap(command), 10000);
        try {
            String result = new String(bounded(stream.input(), 32768), StandardCharsets.UTF_8);
            LegacyShellCommand.Reply reply = frame.parse(result);
            if (reply.exitCode != 0) throw new IOException("控制命令退出 " + reply.exitCode + ": " + reply.output.trim());
            String output = reply.output.trim();
            if (!output.isEmpty()) throw new IOException(output);
        } finally { run.streams.remove(stream); closeQuietly(stream); }
    }
    public void tap(int x, int y) { Run r = current; if (r != null) submit(r, () -> shell(r, "input tap " + Math.max(0, x) + " " + Math.max(0, y))); }
    public void swipe(int x1, int y1, int x2, int y2, int durationMs) {
        Run r = current; if (r != null) submit(r, () -> shell(r, "input swipe " + Math.max(0, x1) + " " + Math.max(0, y1) + " "
                + Math.max(0, x2) + " " + Math.max(0, y2) + " " + Math.max(1, Math.min(10000, durationMs))));
    }
    public void key(int code) {
        Run r = current; if (r == null) return;
        if (code < 0 || code > 1024) throw new IllegalArgumentException("无效按键");
        submit(r, () -> { if (r.options.compatibility) shell(r, "input keyevent " + code);
            else { writeControl(r, ScrcpyProtocol.key(0, code)); writeControl(r, ScrcpyProtocol.key(1, code)); } });
    }
    public void text(String text) {
        Run r = current; if (r == null) return;
        if (r.options.compatibility) { String command = ScrcpyProtocol.shellText(text); submit(r, () -> shell(r, command)); }
        else { byte[] bytes = ScrcpyProtocol.text(text); submit(r, () -> writeControl(r, bytes)); }
    }
    public void pasteText(String text) {
        Run r = current; if (r == null || r.options.compatibility) throw new IllegalStateException("粘贴仅适用于正常模式");
        byte[] bytes = ScrcpyProtocol.paste(text); submit(r, () -> writeControl(r, bytes));
    }
    public void screenPower(boolean on) {
        Run r = current; if (r == null || r.options.compatibility) throw new IllegalStateException("独立熄屏控制仅适用于正常模式");
        submit(r, () -> writeControl(r, new byte[]{10, (byte) (on ? 2 : 0)}));
    }
    public void rotateDevice() {
        Run r = current; if (r == null || r.options.compatibility) throw new IllegalStateException("目标旋转仅适用于正常模式");
        submit(r, () -> writeControl(r, new byte[]{11}));
    }
    public void touch(int action, long pointer, int x, int y, int width, int height, float pressure) {
        Run r = current; if (r == null || r.options.compatibility) return;
        byte[] bytes = ScrcpyProtocol.touch(action, pointer, x, y, width, height, pressure);
        submit(r, () -> {
            synchronized (r.controlLock) {
                writeControl(r, bytes);
                if (action == 1) r.pointers.remove(pointer);
                else r.pointers.put(pointer, new int[]{x, y, width, height});
            }
        });
    }
    private void writeControl(Run run, byte[] bytes) throws IOException {
        synchronized (run.controlLock) {
            if (run.control == null) throw new IOException("控制通道尚未就绪");
            run.control.output().write(bytes); run.control.output().flush();
        }
    }
    private static void closeQuietly(Closeable c) { if (c != null) try { c.close(); } catch (IOException ignored) { } }
    private final class Run {
        final Options options;
        final Set<AdbStream> streams = Collections.synchronizedSet(new HashSet<AdbStream>());
        final Set<ScreenshotCapture> shots = Collections.synchronizedSet(new HashSet<ScreenshotCapture>());
        final Object controlLock = new Object();
        final Map<Long, int[]> pointers = new HashMap<>();
        final StringBuilder log = new StringBuilder();
        final ScreenServerWorkspace workspace = new ScreenServerWorkspace(context, session);
        final CountDownLatch serverFinished = new CountDownLatch(1);
        volatile boolean alive = true;
        volatile Future<?> future;
        volatile AdbStream videoStream, control, server;
        volatile Decoder decoder;
        volatile boolean serverLaunchAttempted, serverExited;
        boolean cleaned;
        volatile boolean legacyScreenshotNotice;
        Run(Options options) { this.options = options; }
        void appendLog(String text) { synchronized (log) { log.append(text); if (log.length() > 4096) log.delete(0, log.length() - 4096); } }
        String logSuffix() { synchronized (log) { return log.length() == 0 ? "" : "\n服务端：" + log.toString().trim(); } }
        synchronized void cleanup() {
            if (cleaned) return;
            cleaned = true;
            alive = false;
            boolean interrupted = Thread.interrupted();
            try {
                synchronized (shots) { for (ScreenshotCapture shot : shots) closeQuietly(shot); shots.clear(); }
                synchronized (controlLock) {
                    if (control != null) {
                        for (Map.Entry<Long, int[]> entry : pointers.entrySet()) {
                            int[] p = entry.getValue();
                            try { writeControl(this, ScrcpyProtocol.touch(1, entry.getKey(), p[0], p[1], p[2], p[3], 0)); } catch (IOException ignored) { break; }
                        }
                        pointers.clear(); closeQuietly(control); control = null;
                    }
                }
                synchronized (streams) {
                    // Closing video/control makes an established server exit normally. Keep its
                    // shell open until EOF, so an ART cleanup cannot race its initial loading.
                    for (AdbStream s : streams) if (s != server) closeQuietly(s);
                    streams.clear();
                }
                if (decoder != null) { decoder.close(); decoder = null; }
                interrupted |= Thread.interrupted();
                if (server != null) {
                    try { serverFinished.await(3000, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException stopped) { interrupted = true; }
                }
                // Read the result BEFORE local close (which can itself make input() return EOF).
                boolean ended = !serverLaunchAttempted || serverExited;
                closeQuietly(server);
                interrupted |= Thread.interrupted();
                List<String> warnings = workspace.cleanup(ended);
                if (!warnings.isEmpty()) {
                    String warning = "目标：" + session.target() + "\n投屏临时文件清理提醒：" + String.join("\n", warnings);
                    BridgeRuntime.get().report(warning);
                    main.post(() -> { if (!closed && (current == null || current == this)) listener.onError(warning); });
                }
            } finally { interrupted |= Thread.interrupted(); if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private final class Decoder implements Closeable {
        private final MediaCodec codec;
        private final Thread drain;
        private volatile boolean active = true;
        private volatile IOException failure;
        Decoder(Run run, Surface surface, int[] size, byte[] sps, byte[] pps) throws IOException {
            codec = MediaCodec.createDecoderByType("video/avc");
            try {
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", size[0], size[1]);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(AnnexBReader.withStartCode(sps)));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(AnnexBReader.withStartCode(pps)));
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ScrcpyProtocol.MAX_PACKET);
                if (android.os.Build.VERSION.SDK_INT >= 30) format.setInteger("low-latency", 1);
                codec.configure(format, surface, null, 0); codec.start();
            } catch (RuntimeException e) { codec.release(); throw new IOException("无法初始化 H.264 解码器：" + detail(e), e); }
            drain = new Thread(() -> {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                try {
                    while (active && isCurrent(run)) {
                        int index = codec.dequeueOutputBuffer(info, 10000);
                        if (index >= 0) {
                            if (run.options.renderAllFrames) codec.releaseOutputBuffer(index, true);
                            else {
                                int next;
                                while ((next = codec.dequeueOutputBuffer(info, 0)) >= 0) { codec.releaseOutputBuffer(index, false); index = next; }
                                codec.releaseOutputBuffer(index, true);
                            }
                        }
                    }
                } catch (RuntimeException e) { if (active) failure = new IOException("视频解码失败：" + detail(e), e); }
            }, "jinjiachong-decoder"); drain.start();
        }
        void queue(ScrcpyProtocol.Packet packet) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (active && !Thread.currentThread().isInterrupted()) {
                if (failure != null) throw failure;
                int index = codec.dequeueInputBuffer(10000);
                if (index >= 0) {
                    ByteBuffer buffer = Compatibility.inputBuffer(codec, index);
                    if (buffer == null || buffer.capacity() < packet.bytes.length) throw new IOException("解码输入缓冲区不足");
                    buffer.clear(); buffer.put(packet.bytes);
                    codec.queueInputBuffer(index, 0, packet.bytes.length, packet.ptsUs, packet.keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0); return;
                }
                if (System.nanoTime() > deadline) throw new IOException("视频解码超时");
            }
            throw new InterruptedIOException("视频已停止");
        }
        @Override public void close() {
            active = false;
            try { drain.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { codec.stop(); } catch (RuntimeException ignored) { }
            try { codec.release(); } catch (RuntimeException ignored) { }
        }
    }
}
