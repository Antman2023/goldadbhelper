package io.github.devicebridge.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic APK manifest tests only; APKs are never installed. */
public final class ApkManifestInspectorTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        for (boolean utf8 : new boolean[] {true, false}) {
            check(!ApkManifestInspector.isSplitManifest(manifest(null, utf8)), "Ordinary base");
            check(!ApkManifestInspector.isSplitManifest(manifest("", utf8)), "Empty split is base");
            check(ApkManifestInspector.isSplitManifest(manifest("config.arm64_v8a", utf8)), "Single split detected");
            byte[] split = manifest("config.en", utf8);
            rejects(Arrays.copyOf(split, split.length - 1));
            byte[] wrongSize = split.clone(); put(wrongSize, 4, Integer.MAX_VALUE); rejects(wrongSize);
            byte[] wrongIndex = split.clone(); put(wrongIndex, wrongIndex.length - 16, 10000); rejects(wrongIndex);
            byte[] wrongPool = split.clone(); put(wrongPool, 8 + 20, Integer.MAX_VALUE); rejects(wrongPool);
            File apk = File.createTempFile("package-manifest-check-", ".apk");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
                    zip.putNextEntry(new ZipEntry("AndroidManifest.xml")); zip.write(split); zip.closeEntry();
                }
                check(ApkManifestInspector.isSplit(apk), "ZIP manifest inspected");
            } finally { Files.deleteIfExists(apk.toPath()); }
        }
        rejects(new byte[0]); rejects("<manifest split='x'/>".getBytes("UTF-8"));
        byte[] noRoot = manifest(null, true); noRoot[0] = 0; rejects(noRoot);
        if (args.length > 0) check(!ApkManifestInspector.isSplit(new File(args[0])), "Actual self-built APK is base");
        System.out.println("ApkManifestInspectorTests: " + assertions + " assertions passed (no device actions)");
    }
    private static byte[] manifest(String split, boolean utf8) throws Exception {
        String[] strings = {"manifest", "split", split == null ? "" : split};
        ByteArrayOutputStream values = new ByteArrayOutputStream(); int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = values.size(); byte[] bytes = strings[i].getBytes(Charset.forName(utf8 ? "UTF-8" : "UTF-16LE"));
            if (utf8) { values.write(strings[i].length()); values.write(bytes.length); }
            else { values.write(strings[i].length()); values.write(0); }
            values.write(bytes); values.write(0); if (!utf8) values.write(0);
        }
        int poolSize = 28 + strings.length * 4 + values.size(), nodeSize = split == null ? 36 : 56;
        ByteBuffer b = ByteBuffer.allocate(8 + poolSize + nodeSize).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short)3).putShort((short)8).putInt(b.capacity());
        b.putShort((short)1).putShort((short)28).putInt(poolSize).putInt(strings.length).putInt(0)
                .putInt(utf8 ? 0x100 : 0).putInt(28 + strings.length * 4).putInt(0);
        for (int offset : offsets) b.putInt(offset); b.put(values.toByteArray());
        b.putShort((short)0x102).putShort((short)16).putInt(nodeSize).putInt(1).putInt(-1);
        b.putInt(-1).putInt(0).putShort((short)20).putShort((short)20).putShort((short)(split == null ? 0 : 1))
                .putShort((short)0).putShort((short)0).putShort((short)0);
        if (split != null) b.putInt(-1).putInt(1).putInt(2).putShort((short)8).put((byte)0).put((byte)3).putInt(2);
        return b.array();
    }
    private static void put(byte[] b, int offset, int value) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value); }
    private static void rejects(byte[] bytes) throws Exception {
        assertions++; try { ApkManifestInspector.isSplitManifest(bytes); }
        catch (IOException expected) { return; }
        throw new AssertionError("Malformed manifest accepted");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
