package io.github.muntashirakon.crypto.spake2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cross-checks against the independent test-only Python affine-curve oracle. */
public final class Spake2Vectors {
    public static void run() throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream("core/src/test/resources/spake2-vectors.properties")) { p.load(in); }
        byte[] client = "adb pair client\0".getBytes(StandardCharsets.UTF_8), server = "adb pair server\0".getBytes(StandardCharsets.UTF_8);
        for (int n = 0; n < 4; n++) {
            Spake2Context a = new Spake2Context(Spake2Role.Alice, client, server), b = new Spake2Context(Spake2Role.Bob, server, client);
            try {
                byte[] am = a.generateMessage(bytes(p.getProperty(n+".password")), bytes(p.getProperty(n+".a_seed")));
                byte[] bm = b.generateMessage(bytes(p.getProperty(n+".password")), bytes(p.getProperty(n+".b_seed")));
                equal(am, bytes(p.getProperty(n+".a_msg")), "Alice message " + n);
                equal(bm, bytes(p.getProperty(n+".b_msg")), "Bob message " + n);
                equal(a.processMessage(bm), bytes(p.getProperty(n+".key")), "Alice key " + n);
                equal(b.processMessage(am), bytes(p.getProperty(n+".key")), "Bob key " + n);
            } finally { a.destroy(); b.destroy(); }
        }
    }
    private static byte[] bytes(String s) {
        byte[] out = new byte[s.length()/2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i*2, i*2+2), 16);
        return out;
    }
    private static void equal(byte[] a, byte[] b, String stage) { if (!Arrays.equals(a,b)) throw new AssertionError("Independent SPAKE2 vector mismatch: " + stage); }
}
