package io.github.devicebridge.core;

import java.io.*;
import java.net.*;
import javax.net.ssl.SSLSocket;

/** Starts in plaintext and upgrades only after a valid ADB STLS negotiation. */
public final class TlsTcpTransport implements TlsUpgradableTransport {
    private final Socket raw;
    private final String host;
    private final int port;
    private volatile Socket current;
    private volatile boolean closed;
    private boolean upgraded;
    private volatile boolean tlsEstablished;

    public TlsTcpTransport(String host, int port, int timeoutMs) throws IOException {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535 || timeoutMs <= 0)
            throw new IllegalArgumentException("Invalid endpoint or timeout");
        this.host = host; this.port = port;
        raw = new Socket(); current = raw;
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setTcpNoDelay(true); raw.setSoTimeout(timeoutMs);
        } catch (IOException e) { close(); throw e; }
    }

    public void upgradeToTls(AdbIdentity identity) throws IOException {
        synchronized (this) {
            if (closed || upgraded) throw new IOException("TLS transport closed or already upgraded");
            upgraded = true;
        }
        try {
            SSLSocket ssl = TlsSupport.wrap(raw, host, port, identity);
            synchronized (this) {
                if (closed) { ssl.close(); throw new IOException("TLS transport closed"); }
                current = ssl;
            }
            ssl.startHandshake();
            TlsSupport.requireTls13(ssl);
            tlsEstablished = true;
        } catch (IOException e) { close(); throw e; }
    }
    public boolean isTls() { return tlsEstablished && !closed; }
    public void setReadTimeout(int ms) throws IOException { current.setSoTimeout(ms); }
    public InputStream input() throws IOException { return current.getInputStream(); }
    public OutputStream output() throws IOException { return current.getOutputStream(); }
    public String description() { return host + ":" + port; }
    public void close() throws IOException {
        closed = true;
        // Close the underlying socket first so cancellation interrupts TLS I/O immediately.
        raw.close();
        Socket active = current;
        if (active != raw) active.close();
    }
}
