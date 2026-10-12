package io.github.devicebridge.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Independent client encoding of the public scrcpy v2.4 protocol (big endian). */
public final class ScrcpyProtocol {
    public static final String VERSION = "2.4";
    public static final long CONFIG = 1L << 63, KEY_FRAME = 1L << 62;
    public static final int MAX_PACKET = 8 * 1024 * 1024;
    private ScrcpyProtocol() { }
    public static int[] readVideoHeader(DataInputStream in) throws IOException {
        int codec = in.readInt();
        if (codec != 0x68323634) throw new IOException("目标未提供 H.264 视频（编码标识 " + codec + "）");
        int width = in.readInt(), height = in.readInt();
        validateSize(width, height);
        return new int[]{width, height};
    }
    public static Packet readPacket(DataInputStream in) throws IOException {
        long flags = in.readLong();
        int count = in.readInt();
        if (count <= 0 || count > MAX_PACKET) throw new IOException("视频包大小无效：" + count);
        byte[] bytes = new byte[count];
        in.readFully(bytes);
        return new Packet(flags, bytes);
    }
    public static final class Packet {
        public final long ptsUs;
        public final boolean configuration, keyFrame;
        public final byte[] bytes;
        Packet(long flags, byte[] bytes) {
            ptsUs = flags & ~(CONFIG | KEY_FRAME);
            configuration = (flags & CONFIG) != 0;
            keyFrame = (flags & KEY_FRAME) != 0;
            this.bytes = bytes;
        }
    }
    public static byte[] touch(int action, long pointer, int x, int y, int width, int height, float pressure) {
        if (action < 0 || action > 3 || pointer < 0 || width < 1 || height < 1 || width > 65535 || height > 65535
                || x < 0 || y < 0 || x >= width || y >= height || Float.isNaN(pressure))
            throw new IllegalArgumentException("无效触摸位置");
        int fixedPressure = (int) (Math.max(0, Math.min(1, pressure)) * 65536);
        fixedPressure = Math.min(65535, fixedPressure);
        return ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(pointer)
                .putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort((short) fixedPressure).putInt(0).putInt(0).array();
    }
    public static byte[] key(int action, int keyCode) {
        if ((action != 0 && action != 1) || keyCode < 0 || keyCode > 1024) throw new IllegalArgumentException("无效按键");
        return ByteBuffer.allocate(14).put((byte) 0).put((byte) action).putInt(keyCode).putInt(0).putInt(0).array();
    }
    public static byte[] text(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 300 || text.indexOf('\0') >= 0) throw new IllegalArgumentException("直接输入最多 300 字节");
        return ByteBuffer.allocate(5 + bytes.length).put((byte) 1).putInt(bytes.length).put(bytes).array();
    }
    public static byte[] paste(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536 || text.indexOf('\0') >= 0) throw new IllegalArgumentException("粘贴文本过长或包含空字符");
        return ByteBuffer.allocate(14 + bytes.length).put((byte) 9).putLong(0).put((byte) 1).putInt(bytes.length).put(bytes).array();
    }
    /** Maps fit-center/stretch content, rejecting letterbox bars. */
    public static int[] map(float x, float y, int viewWidth, int viewHeight, int imageWidth, int imageHeight, boolean stretch) {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 || !Float.isFinite(x) || !Float.isFinite(y)) return null;
        double scaleX = (double) viewWidth / imageWidth, scaleY = (double) viewHeight / imageHeight;
        if (!stretch) scaleX = scaleY = Math.min(scaleX, scaleY);
        double left = (viewWidth - imageWidth * scaleX) / 2, top = (viewHeight - imageHeight * scaleY) / 2;
        double px = (x - left) / scaleX, py = (y - top) / scaleY;
        if (px < 0 || py < 0 || px >= imageWidth || py >= imageHeight) return null;
        return new int[]{Math.min(imageWidth - 1, (int) px), Math.min(imageHeight - 1, (int) py)};
    }
    public static String shellText(String text) {
        if (text.isEmpty() || text.length() > 300) throw new IllegalArgumentException("输入长度必须为 1–300 个字符");
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 32 || text.charAt(i) > 126 || text.charAt(i) == '%')
            throw new IllegalArgumentException("兼容模式仅支持可打印英文字符（不含 %）；中文请使用正常模式粘贴");
        return "input text '" + text.replace(" ", "%s").replace("'", "'\\''") + "'";
    }
    public static void validateSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long) width * height > 33554432)
            throw new IOException("屏幕尺寸超出安全范围");
    }
}
