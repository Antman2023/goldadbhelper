package io.github.devicebridge.android;

import io.github.devicebridge.core.HostServerClient;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Four UI-facing smart-socket replies only. This never invokes an ADB daemon or device. */
final class SyntheticHostServer implements Closeable {
    enum StopReply { ACCEPT, REFUSE, UNKNOWN }
    private final ServerSocket listener;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
    final List<String> unexpected = Collections.synchronizedList(new ArrayList<String>());
    volatile String devices;
    volatile StopReply stopReply = StopReply.ACCEPT;
    volatile boolean rejectTracker;
    private volatile boolean closed;
    SyntheticHostServer(String devices) throws IOException {
        this.devices = devices;
        listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!closed) try {
                final Socket socket = listener.accept();
                sockets.add(socket);
                Thread worker = new Thread(() -> handle(socket), "hostui-synthetic-reply");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException error) {
                if (!closed) unexpected.add("accept: " + error.getMessage());
            }
        }, "hostui-loopback-listener");
        accept.setDaemon(true);
        accept.start();
    }
    String endpoint() { return "127.0.0.1:" + listener.getLocalPort(); }
    HostServerClient client() { return new HostServerClient("127.0.0.1", listener.getLocalPort()); }
    int count(String request) {
        int count = 0;
        synchronized (requests) { for (String actual : requests) if (request.equals(actual)) count++; }
        return count;
    }
    boolean allSocketsClosed() throws InterruptedException {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!sockets.isEmpty() && System.nanoTime() < limit) Thread.sleep(5);
        return sockets.isEmpty();
    }
    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            String request = request(in); requests.add(request);
            if (request.equals("host:track-devices-l")) {
                if (rejectTracker) { status(out, "FAIL"); payload(out, "synthetic connection refused"); return; }
                status(out, "OKAY"); payload(out, devices);
                socket.setSoTimeout(0); while (in.read() != -1) { }
            } else if (request.equals("host:devices-l")) {
                status(out, "OKAY"); payload(out, devices);
            } else if (request.matches("host-transport-id:[1-9][0-9]*:features")) {
                status(out, "OKAY"); payload(out, "shell_v2,cmd");
            } else if (request.equals("host:kill")) {
                // A string response from this fixture only: never stops a real process.
                if (stopReply == StopReply.UNKNOWN) return;
                if (stopReply == StopReply.REFUSE) { status(out, "FAIL"); payload(out, "synthetic stop refused"); }
                else status(out, "OKAY");
            } else unexpected.add(request);
        } catch (IOException ignored) {
            // Client cancellation and normal tracker teardown close these synthetic sockets.
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
            sockets.remove(socket);
        }
    }
    private static String request(InputStream in) throws IOException {
        int size;
        try { size = Integer.parseInt(new String(read(in, 4), StandardCharsets.US_ASCII), 16); }
        catch (NumberFormatException error) { throw new IOException(error); }
        if (size <= 0 || size > 4096) throw new IOException("Unexpected fixture request size");
        return new String(read(in, size), StandardCharsets.UTF_8);
    }
    private static byte[] read(InputStream in, int size) throws IOException {
        byte[] bytes = new byte[size]; int position = 0;
        while (position < size) { int n = in.read(bytes, position, size - position); if (n < 0) throw new EOFException(); position += n; }
        return bytes;
    }
    private static void status(OutputStream out, String value) throws IOException { out.write(value.getBytes(StandardCharsets.US_ASCII)); out.flush(); }
    private static void payload(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(String.format(Locale.ROOT, "%04x", bytes.length).getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }
    public void close() throws IOException {
        closed = true; listener.close();
        synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
}
