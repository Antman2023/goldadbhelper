package io.github.devicebridge.android;

import android.hardware.usb.*;
import io.github.devicebridge.core.Transport;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** ADB byte stream over USB. I/O failure is terminal; close interrupts native transfers. */
public final class UsbTransport implements Transport {
    private final UsbBulkConnection usb;
    private volatile int timeoutMs = 20000;

    public static UsbInterface find(UsbDevice device, int protocol) { return UsbBulkConnection.find(device, protocol); }
    public UsbTransport(UsbManager manager, UsbDevice device) throws IOException {
        usb = new UsbBulkConnection(manager, device, 1);
    }
    public InputStream input() {
        return new InputStream() {
            public int read() throws IOException {
                byte[] byteValue = new byte[1];
                return read(byteValue, 0, 1) < 0 ? -1 : byteValue[0] & 255;
            }
            public int read(byte[] buffer, int offset, int length) throws IOException {
                UsbBulkConnection.checkBounds(buffer, offset, length);
                if (length == 0) return 0;
                long started = System.nanoTime();
                try {
                    while (true) {
                        usb.ensureOpen();
                        int pollTimeout = 500, configuredTimeout = timeoutMs;
                        if (configuredTimeout > 0) {
                            long remaining = TimeUnit.MILLISECONDS.toNanos(configuredTimeout) - (System.nanoTime() - started);
                            if (remaining <= 0) throw new SocketTimeoutException("USB read timed out");
                            pollTimeout = (int)Math.max(1, Math.min(pollTimeout, (remaining + 999999L) / 1000000L));
                        }
                        int count = usb.read(buffer, offset, Math.min(length, UsbBulkConnection.MAX_TRANSFER_BYTES), pollTimeout);
                        if (count > 0) return count;
                        if (!usb.isAttached()) throw new EOFException("USB device disconnected");
                    }
                } catch (IOException failure) { throw usb.invalidate(failure); }
            }
            public void close() { UsbTransport.this.close(); }
        };
    }
    public OutputStream output() {
        return new OutputStream() {
            public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            public void write(byte[] buffer, int offset, int length) throws IOException {
                UsbBulkConnection.checkBounds(buffer, offset, length);
                try {
                    while (length > 0) {
                        int count = usb.write(buffer, offset, Math.min(length, UsbBulkConnection.MAX_TRANSFER_BYTES), 10000);
                        if (count <= 0) throw new IOException("USB write failed");
                        offset += count; length -= count;
                    }
                } catch (IOException failure) { throw usb.invalidate(failure); }
            }
            public void flush() throws IOException {
                try { usb.ensureOpen(); } catch (IOException failure) { throw usb.invalidate(failure); }
            }
            public void close() { UsbTransport.this.close(); }
        };
    }
    public void setReadTimeout(int milliseconds) {
        if (milliseconds < 0) throw new IllegalArgumentException("USB timeout must be nonnegative");
        timeoutMs = milliseconds;
    }
    public String description() { return usb.description(); }
    public void close() { usb.close(); }
}
