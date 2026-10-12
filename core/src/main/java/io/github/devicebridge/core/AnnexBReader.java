package io.github.devicebridge.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Bounded, streaming Annex B H.264 reader. Does not depend on Android or a server APK. */
public final class AnnexBReader {
    public static final int DEFAULT_MAX_NAL = 4 * 1024 * 1024;
    private final InputStream input;
    private final int maxNalBytes;
    private boolean started, eof;

    public AnnexBReader(InputStream input) { this(input, DEFAULT_MAX_NAL); }
    public AnnexBReader(InputStream input, int maxNalBytes) {
        if (input == null) throw new NullPointerException("input");
        if (maxNalBytes < 1 || maxNalBytes > 32 * 1024 * 1024)
            throw new IllegalArgumentException("NAL limit must be 1..33554432");
        this.input = new BufferedInputStream(input, 16384);
        this.maxNalBytes = maxNalBytes;
    }

    /** Returns a NAL including its header, without the start code; null means clean EOF. */
    public byte[] nextNal() throws IOException {
        if (eof) return null;
        if (!started) {
            int zeros = 0, searched = 0, b;
            while ((b = input.read()) != -1) {
                if (++searched > 65536) throw new IOException("H.264 start code missing (unsupported screenrecord output)");
                if (b == 1 && zeros >= 2) { started = true; break; }
                zeros = b == 0 ? zeros + 1 : 0;
            }
            if (!started) {
                eof = true;
                if (searched != 0) throw new EOFException("No Annex B H.264 start code");
                return null;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int zeros = 0;
        for (;;) {
            int b = input.read();
            if (b == -1) {
                eof = true;
                // Annex B permits trailing_zero_8bits after the final NAL.
                return checked(out);
            }
            if (b == 0) {
                if (++zeros > maxNalBytes) throw new IOException("H.264 NAL exceeds size limit");
                continue;
            }
            if (b == 1 && zeros >= 2) {
                zeros = 0;
                if (out.size() == 0) continue; // Consecutive delimiters.
                return checked(out);
            }
            if ((long) out.size() + zeros + 1 > maxNalBytes) throw new IOException("H.264 NAL exceeds size limit");
            while (zeros-- > 0) out.write(0);
            zeros = 0;
            out.write(b);
        }
    }

    private static byte[] checked(ByteArrayOutputStream out) throws IOException {
        if (out.size() == 0) return null;
        byte[] nal = out.toByteArray();
        if ((nal[0] & 0x80) != 0 || (nal[0] & 31) == 0) throw new IOException("Invalid H.264 NAL header");
        return nal;
    }

    public static byte[] withStartCode(byte[] nal) {
        byte[] bytes = new byte[nal.length + 4];
        bytes[3] = 1;
        System.arraycopy(nal, 0, bytes, 4, nal.length);
        return bytes;
    }

    /** Extracts cropped progressive/interlaced AVC frame size from a sequence parameter set. */
    public static int[] dimensions(byte[] sps) throws IOException {
        if (sps == null || sps.length < 4 || (sps[0] & 31) != 7) throw new IOException("Missing H.264 SPS");
        Bits bits = new Bits(sps);
        int profile = bits.read(8);
        bits.read(8); bits.read(8); bits.ue();
        int chroma = 1;
        boolean separateColorPlane = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128
                || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chroma = bits.ue();
            if (chroma > 3) throw new IOException("Invalid AVC chroma format");
            if (chroma == 3) separateColorPlane = bits.read(1) != 0;
            bits.ue(); bits.ue(); bits.read(1);
            if (bits.read(1) != 0) {
                int count = chroma == 3 ? 12 : 8;
                for (int i = 0; i < count; i++) if (bits.read(1) != 0) {
                    int last = 8, next = 8;
                    for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                        if (next != 0) next = (last + bits.se() + 256) & 255;
                        if (next != 0) last = next;
                    }
                }
            }
        }
        bits.ue();
        int poc = bits.ue();
        if (poc == 0) bits.ue();
        else if (poc == 1) {
            bits.read(1); bits.se(); bits.se();
            int count = bits.ue();
            if (count > 256) throw new IOException("Invalid AVC POC cycle");
            for (int i = 0; i < count; i++) bits.se();
        } else if (poc > 2) throw new IOException("Invalid AVC POC type");
        bits.ue(); bits.read(1);
        long width = ((long) bits.ue() + 1) * 16;
        long heightUnits = (long) bits.ue() + 1;
        int frameOnly = bits.read(1);
        if (frameOnly == 0) bits.read(1);
        bits.read(1);
        long height = heightUnits * 16 * (2 - frameOnly);
        if (bits.read(1) != 0) {
            long left = bits.ue(), right = bits.ue(), top = bits.ue(), bottom = bits.ue();
            int chromaArray = separateColorPlane ? 0 : chroma;
            int cropX = chromaArray == 1 || chromaArray == 2 ? 2 : 1;
            int cropY = (chromaArray == 1 ? 2 : 1) * (2 - frameOnly);
            width -= (left + right) * cropX;
            height -= (top + bottom) * cropY;
        }
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || width * height > 33554432)
            throw new IOException("Unsupported AVC dimensions");
        return new int[]{(int) width, (int) height};
    }

    public static int firstMacroblock(byte[] nal) throws IOException {
        if (nal == null || nal.length < 2) throw new EOFException("Truncated H.264 slice");
        int type = nal[0] & 31;
        if (type != 1 && type != 5) throw new IOException("Unsupported AVC slice type");
        return new Bits(nal).ue();
    }

    /** Groups ordinary AVC slices (types 1/5) into complete decoder access units. */
    public static final class AccessUnits {
        private final AnnexBReader reader;
        private byte[] pending;
        public AccessUnits(AnnexBReader reader) { this.reader = reader; }
        public byte[] next() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean hasSlice = false;
            for (;;) {
                byte[] nal = pending != null ? pending : reader.nextNal();
                pending = null;
                if (nal == null) return out.size() == 0 ? null : out.toByteArray();
                int type = nal[0] & 31;
                if (type == 2 || type == 3 || type == 4 || type == 20 || type == 21)
                    throw new IOException("Unsupported partitioned/extended AVC stream");
                boolean slice = type == 1 || type == 5;
                boolean boundary = hasSlice && ((slice && firstMacroblock(nal) == 0)
                        || type == 6 || type == 7 || type == 8 || type == 9);
                if (boundary) { pending = nal; return out.toByteArray(); }
                if ((long) out.size() + nal.length + 4 > 8 * 1024 * 1024)
                    throw new IOException("H.264 access unit exceeds size limit");
                out.write(withStartCode(nal));
                hasSlice |= slice;
            }
        }
    }

    private static final class Bits {
        private final byte[] bytes;
        private int bit;
        Bits(byte[] nal) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int zeros = 0;
            for (int i = 1; i < nal.length; i++) {
                int b = nal[i] & 255;
                if (zeros >= 2 && b == 3) {
                    if (i + 1 >= nal.length || (nal[i + 1] & 255) > 3)
                        throw new IOException("Malformed H.264 emulation prevention byte");
                    zeros = 0;
                    continue;
                }
                out.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            bytes = out.toByteArray();
        }
        int read(int n) throws IOException {
            if (n < 0 || n > 31 || (long) bit + n > (long) bytes.length * 8) throw new EOFException("Truncated H.264 bitstream");
            int v = 0;
            while (n-- > 0) { v = (v << 1) | ((bytes[bit >>> 3] >>> (7 - (bit & 7))) & 1); bit++; }
            return v;
        }
        int ue() throws IOException {
            int zeros = 0;
            while (read(1) == 0) if (++zeros > 30) throw new IOException("AVC Exp-Golomb integer too large");
            return (1 << zeros) - 1 + read(zeros);
        }
        int se() throws IOException { int n = ue(); return (n & 1) == 0 ? -(n / 2) : (n + 1) / 2; }
    }
}
