package io.github.devicebridge.core;

import java.io.*;
import java.net.*;

public final class TcpTransport implements Transport {
    private final Socket socket;
    private final String label;
    public TcpTransport(String host, int port, int timeoutMs) throws IOException {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid host or port");
        label = host + ":" + port;
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
        } catch (IOException e) { socket.close(); throw e; }
    }
    public void setReadTimeout(int ms) throws SocketException { socket.setSoTimeout(ms); }
    public InputStream input() throws IOException { return socket.getInputStream(); }
    public OutputStream output() throws IOException { return socket.getOutputStream(); }
    public String description() { return label; }
    public void close() throws IOException { socket.close(); }
}
