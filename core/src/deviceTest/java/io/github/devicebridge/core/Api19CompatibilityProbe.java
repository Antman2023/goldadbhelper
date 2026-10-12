package io.github.devicebridge.core;

import java.io.*;
import java.security.*;
import java.util.*;

/** Explicitly invoked in an isolated emulator fixture; never launched by the app. */
public final class Api19CompatibilityProbe {
    private static int count;
    private static void ok(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message); count++;
    }
    private static byte[] read(AndroidFileAccess fs, String file) throws Exception {
        FileDescriptor fd = fs.readOnly(file);
        try {
            AndroidFileAccess.Stat before = fs.fstat(fd);
            ok(before.regular, "kernel fstat regular file");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
            while ((n = fs.read(fd, buffer)) != -1) out.write(buffer, 0, n);
            ok(before.token().equals(fs.lstat(file).token()), "kernel fstat/lstat identical inode and metadata");
            return out.toByteArray();
        } finally { fs.close(fd); }
    }
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        if (!dir.matches("/data/local/tmp/gb-compat-[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Unowned test directory");
        AndroidFileAccess fs = new AndroidFileAccess();
        String path = dir + "/probe-bytes";
        byte[] expected = new byte[8193]; new Random(1971).nextBytes(expected);
        FileDescriptor fd = fs.createExclusive(path);
        try {
            int at = 0;
            while (at < expected.length) { int n = fs.write(fd, expected, at, expected.length - at); if (n <= 0) throw new IOException("No progress"); at += n; }
            fs.fsync(fd);
        } finally { fs.close(fd); }
        ok(Arrays.equals(expected, read(fs, path)), "kernel round trip preserves exact binary bytes");
        boolean excluded = false;
        try { FileDescriptor unexpected = fs.createExclusive(path); fs.close(unexpected); }
        catch (IOException e) { excluded = true; }
        ok(excluded, "O_EXCL rejects an existing destination");
        ok(Arrays.equals(expected, read(fs, path)), "O_EXCL leaves existing destination unchanged");
        boolean linkRejected = false;
        try { FileDescriptor unexpected = fs.readOnly(dir + "/symlink"); fs.close(unexpected); }
        catch (IOException e) { linkRejected = true; }
        ok(linkRejected, "O_NOFOLLOW rejects an actual symlink");
        AdbIdentity identity = AdbIdentity.generate();
        ok(identity.adbPublicKey().length > 700, "RSA identity plus Java Base64 backport works");
        List<String> words = new ArrayList<>(Arrays.asList("b", "a"));
        words.sort(Comparator.comparing(String::toString));
        ok("a,b".equals(String.join(",", words)), "collection default methods, functions and String.join backport works");
        ok(Float.isFinite(1f) && !Float.isFinite(Float.NaN), "D8 Float.isFinite backport works");
        System.out.println("JNI_SEARCH_PATH " + System.getProperty("java.library.path"));
        try { TlsSupport.context(identity); }
        catch (Throwable failure) { failure.printStackTrace(System.out); throw failure; }
        ok(TlsSupport.isAvailable(), "bundled Conscrypt native provider loads");
        ok(Arrays.asList(TlsSupport.context(identity).getSupportedSSLParameters().getProtocols()).contains("TLSv1.3"), "TLS1.3 context available on API19");
        byte[] password = "synthetic pairing fixture only".getBytes("UTF-8");
        try (PairingCrypto client = new PairingCrypto(true, password); PairingCrypto server = new PairingCrypto(false, password)) {
            byte[] a = client.message(), b = server.message(); client.initialize(b); server.initialize(a);
            byte[] plain = "binary-safe pairing fixture".getBytes("UTF-8");
            ok(Arrays.equals(plain, server.decrypt(client.encrypt(plain))), "SPAKE2/HKDF/AES-GCM works on API19");
        }
        System.out.println("API19_COMPATIBILITY_PROBE_PASSED " + count);
    }
}
