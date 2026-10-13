package io.github.devicebridge.core;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One-shot shell-v2 completion, framing and bounded output; in-memory only. */
public final class ShellClientTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        exitWithoutClose(); chunkedOutput(); malformedFrames(); callbackFailure(); invalidArguments();
        System.out.println("ShellClientTests: " + assertions + " assertions passed (in-memory only)");
    }

    private static void exitWithoutClose() throws Exception {
        for (byte[] status : new byte[][]{new byte[]{7}, le32(258)}) {
            Fixture f = new Fixture(join(frame(1, ascii("done")), frame(2, ascii("warning")), frame(3, status)), false);
            ShellClient.Result result = new ShellClient(f.session).run("fixture", 100);
            check(result.text().equals("done") && result.error().equals("warning"), "stdout and stderr remain separate");
            check(result.exitCode == (status.length == 1 ? 7 : 258), "exit status completes without waiting for CLSE");
            check(f.closeCount == 1 && f.session.state() == AdbSession.State.CONNECTED, "completion releases channel and keeps session");
        }
    }

    private static void chunkedOutput() throws Exception {
        byte[] output = new byte[131101]; new Random(42).nextBytes(output);
        Fixture f = new Fixture(join(frame(4, new byte[0]), frame(1, output), frame(3, new byte[]{0})), false);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        int code = new ShellClient(f.session).stream("fixture", 1000, (stderr, bytes) -> {
            check(!stderr && bytes.length <= 8192, "stream callbacks use bounded stdout chunks");
            received.write(bytes);
        });
        check(code == 0 && Arrays.equals(output, received.toByteArray()), "chunking preserves exact bytes and exit status");
        check(f.closeCount == 1, "streaming success closes channel");
    }

    private static void malformedFrames() throws Exception {
        for (byte[] reply : new byte[][]{
                frame(3, new byte[0]), frame(3, new byte[2]), frame(4, new byte[]{1}), frame(5, new byte[0]),
                frame(99, new byte[0]), header(1, -1), header(1, 16 * 1024 * 1024 + 1),
                join(header(1, 4), ascii("ab")), frame(1, ascii("no exit")), new byte[0]}) {
            Fixture f = new Fixture(reply, true);
            try { new ShellClient(f.session).stream("fixture", 100, (stderr, bytes) -> {}); throw new AssertionError("invalid reply accepted"); }
            catch (IOException expected) { assertions++; }
            check(f.closeCount == 1 && f.session.state() == AdbSession.State.CONNECTED, "malformed shell closes only its channel");
        }
    }

    private static void callbackFailure() throws Exception {
        Fixture f = new Fixture(join(frame(1, ascii("output")), frame(3, new byte[]{0})), false);
        IOException failure = new IOException("consumer stopped");
        try { new ShellClient(f.session).stream("fixture", 100, (stderr, bytes) -> { throw failure; }); throw new AssertionError("callback failure hidden"); }
        catch (IOException expected) { check(expected == failure, "original consumer failure retained"); }
        check(f.closeCount == 1, "consumer failure releases channel");
    }

    private static void invalidArguments() throws Exception {
        Fixture f = new Fixture(new byte[0], false);
        ShellClient client = new ShellClient(f.session);
        for (String command : new String[]{null, "bad\0command"}) {
            try { client.stream(command, 100, (stderr, bytes) -> {}); throw new AssertionError("invalid command accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
            try { client.execBytes(command, 100, 1); throw new AssertionError("invalid exec accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        try { client.stream("fixture", 100, null); throw new AssertionError("missing callback accepted"); }
        catch (NullPointerException expected) { assertions++; }
        try { client.execBytes("fixture", 100, -1); throw new AssertionError("negative limit accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        check(f.openCount == 0, "invalid local arguments never open a remote command");
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] le32(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private static byte[] header(int id, int length) throws IOException { return join(new byte[]{(byte)id}, le32(length)); }
    private static byte[] frame(int id, byte[] value) throws IOException { return join(header(id, value.length), value); }
    private static byte[] join(byte[]... values) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] value : values) out.write(value); return out.toByteArray(); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }

    private static final class Fixture implements Transport {
        final AdbSession session;
        final byte[] reply;
        final boolean peerCloses;
        int openCount, closeCount;
        Fixture(byte[] reply, boolean peerCloses) throws Exception {
            this.reply = reply; this.peerCloses = peerCloses;
            session = new AdbSession(this, null, null);
            set("state", AdbSession.State.CONNECTED); set("banner", "device::features=shell_v2;");
        }
        private void set(String name, Object value) throws Exception { Field f = AdbSession.class.getDeclaredField(name); f.setAccessible(true); f.set(session, value); }
        @SuppressWarnings("unchecked") private AdbStream stream(int local) throws Exception {
            Field f = AdbSession.class.getDeclaredField("streams"); f.setAccessible(true);
            return ((Map<Integer, AdbStream>)f.get(session)).get(local);
        }
        public InputStream input() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream output() { return new OutputStream() {
            public void write(int value) { throw new AssertionError("whole packets required"); }
            public void write(byte[] bytes, int offset, int length) throws IOException {
                AdbPacket packet = SyntheticAdbPackets.decode(bytes, offset, length);
                if (packet.command == AdbPacket.CLSE) closeCount++;
                if (packet.command == AdbPacket.OPEN) {
                    openCount++;
                    try {
                        AdbStream s = stream(packet.arg0); s.onOkay(packet.arg0 + 100);
                        if (reply.length > 0) s.onData(packet.arg0 + 100, reply);
                        if (peerCloses) s.onClose(packet.arg0 + 100);
                    } catch (IOException e) { throw e; }
                    catch (Exception e) { throw new IOException(e); }
                }
            }
        }; }
        public String description() { return "in-memory shell fixture"; }
        public void close() { }
    }
}
