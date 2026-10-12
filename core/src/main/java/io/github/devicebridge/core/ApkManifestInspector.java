package io.github.devicebridge.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded root-only AXML inspection for the legacy split gate, not APK validation. */
public final class ApkManifestInspector {
    private static final int LIMIT = 4 * 1024 * 1024;
    private ApkManifestInspector() { }
    public static boolean isSplit(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry manifest = null;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                if ("AndroidManifest.xml".equals(entry.getName())) {
                    if (manifest != null) throw bad();
                    manifest = entry;
                }
            }
            if (manifest == null || manifest.isDirectory() || manifest.getSize() > LIMIT) throw bad();
            try (InputStream in = zip.getInputStream(manifest)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] block = new byte[8192]; int n;
                while ((n = in.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("检查已取消");
                    if (bytes.size() + n > LIMIT) throw bad();
                    bytes.write(block, 0, n);
                }
                return isSplitManifest(bytes.toByteArray());
            }
        }
    }
    static boolean isSplitManifest(byte[] data) throws IOException {
        bounds(data, 0, 8, data.length);
        if (data.length > LIMIT || u16(data, 0) != 3 || u16(data, 2) != 8 || u32(data, 4) != data.length) throw bad();
        Pool pool = null;
        for (int offset = 8; offset < data.length;) {
            bounds(data, offset, 8, data.length);
            int type = u16(data, offset), header = u16(data, offset + 2), size = u32(data, offset + 4);
            if (header < 8 || size < header) throw bad();
            bounds(data, offset, size, data.length); int end = offset + size;
            if (type == 1) {
                if (pool != null) throw bad();
                pool = new Pool(data, offset, header, end);
            } else if (type == 0x102) {
                if (pool == null || header < 16) throw bad();
                int ext = offset + header; bounds(data, ext, 20, end);
                if (u32(data, ext) != -1 || !"manifest".equals(pool.string(u32(data, ext + 4)))) throw bad();
                int attrStart = u16(data, ext + 8), attrSize = u16(data, ext + 10), count = u16(data, ext + 12);
                if (attrStart < 20 || attrSize < 20) throw bad();
                bounds(data, ext + attrStart, (long) attrSize * count, end);
                boolean found = false, split = false;
                for (int i = 0, at = ext + attrStart; i < count; i++, at += attrSize) {
                    int ns = u32(data, at); String name = pool.string(u32(data, at + 4));
                    if (ns != -1) { pool.string(ns); continue; }
                    if (!"split".equals(name)) continue;
                    if (found || u16(data, at + 12) != 8) throw bad();
                    found = true; int raw = u32(data, at + 8);
                    if (raw != -1) split = !pool.string(raw).isEmpty();
                    else if ((data[at + 15] & 255) == 3) split = !pool.string(u32(data, at + 16)).isEmpty();
                    else throw bad();
                }
                return split;
            } else if (type != 0x180 && type != 0x100) throw bad();
            offset = end;
        }
        throw bad();
    }
    private static final class Pool {
        final byte[] data; final int offset, count, strings, end; final boolean utf8;
        Pool(byte[] data, int offset, int header, int end) throws IOException {
            if (header < 28) throw bad(); bounds(data, offset, header, end);
            this.data = data; this.offset = offset; this.end = end;
            count = u32(data, offset + 8); int styles = u32(data, offset + 12);
            int start = u32(data, offset + 20); utf8 = (u32(data, offset + 16) & 0x100) != 0;
            if (count < 0 || styles < 0 || start < header + 4L * (count + (long) styles)) throw bad();
            bounds(data, offset + header, 4L * count, end); bounds(data, offset, start, end);
            strings = offset + start;
        }
        String string(int index) throws IOException {
            if (index < 0 || index >= count) throw bad();
            int relative = u32(data, offset + u16(data, offset + 2) + index * 4);
            if (relative < 0 || relative > end - strings) throw bad();
            int at = strings + relative; int length, expectedCharacters;
            if (utf8) {
                int[] characters = length8(data, at, end), bytes = length8(data, characters[1], end);
                at = bytes[1]; length = bytes[0]; expectedCharacters = characters[0]; bounds(data, at, (long) length + 1, end);
                if (data[at + length] != 0) throw bad();
            } else {
                bounds(data, at, 2, end); int units = u16(data, at); at += 2;
                if ((units & 0x8000) != 0) { bounds(data, at, 2, end); units = ((units & 0x7fff) << 16) | u16(data, at); at += 2; }
                if (units < 0 || units > LIMIT / 2) throw bad();
                length = units * 2; expectedCharacters = units; bounds(data, at, (long) length + 2, end);
                if (u16(data, at + length) != 0) throw bad();
            }
            String decoded = Charset.forName(utf8 ? "UTF-8" : "UTF-16LE").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, at, length)).toString();
            if (decoded.length() != expectedCharacters) throw bad();
            return decoded;
        }
    }
    private static int[] length8(byte[] data, int at, int end) throws IOException {
        bounds(data, at, 1, end); int value = data[at++] & 255;
        if ((value & 0x80) != 0) { bounds(data, at, 1, end); value = ((value & 0x7f) << 8) | (data[at++] & 255); }
        return new int[] {value, at};
    }
    private static void bounds(byte[] data, int at, long length, int end) throws IOException {
        if (at < 0 || length < 0 || end > data.length || (long) at + length > end) throw bad();
    }
    private static int u16(byte[] data, int at) { return (data[at] & 255) | ((data[at + 1] & 255) << 8); }
    private static int u32(byte[] data, int at) { return u16(data, at) | (u16(data, at + 2) << 16); }
    private static IOException bad() { return new IOException("无法可靠判断 APK 是否为独立安装包：清单缺失、格式不支持或损坏。未上传，请选择完整的有效单 APK。"); }
}
