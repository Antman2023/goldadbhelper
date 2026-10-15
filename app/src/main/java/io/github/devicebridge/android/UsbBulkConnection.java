package io.github.devicebridge.android;

import android.hardware.usb.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared ownership and bulk I/O boundaries for ADB streams and Fastboot packets. */
final class UsbBulkConnection implements Closeable {
    static final int MAX_TRANSFER_BYTES = 16384;
    private final UsbManager manager;
    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private final UsbEndpoint in, out;
    private final AtomicBoolean closed = new AtomicBoolean();

    static UsbInterface find(UsbDevice device, int protocol) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 255 && iface.getInterfaceSubclass() == 66
                    && iface.getInterfaceProtocol() == protocol) return iface;
        }
        return null;
    }

    UsbBulkConnection(UsbManager manager, UsbDevice device, int protocol) throws IOException {
        if (manager == null || device == null) throw new NullPointerException("USB manager/device");
        this.manager = manager; this.device = device;
        checkInterrupted();
        if (!manager.hasPermission(device)) throw new IOException("USB permission not granted");
        iface = find(device, protocol);
        if (iface == null) throw new IOException("No matching USB interface");
        UsbEndpoint read = null, write = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = iface.getEndpoint(i);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) read = endpoint;
                else write = endpoint;
            }
        }
        if (read == null || write == null) throw new IOException("Missing USB bulk endpoints");
        in = read; out = write;
        checkInterrupted();
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Unable to open USB device");
        boolean claimed = false;
        try {
            checkInterrupted();
            claimed = connection.claimInterface(iface, true);
            if (!claimed) throw new IOException("Unable to claim USB interface");
            checkInterrupted();
        } catch (IOException | RuntimeException failure) {
            if (claimed) try { connection.releaseInterface(iface); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            try { connection.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    int read(byte[] buffer, int offset, int length, int timeout) throws IOException {
        return transfer(in, buffer, offset, length, timeout);
    }
    int write(byte[] buffer, int offset, int length, int timeout) throws IOException {
        return transfer(out, buffer, offset, length, timeout);
    }
    private int transfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout) throws IOException {
        ensureOpen();
        final int count;
        try { count = connection.bulkTransfer(endpoint, buffer, offset, length, timeout); }
        catch (RuntimeException failure) { throw new IOException("USB bulk transfer failed", failure); }
        // Native I/O may finish with buffered bytes while close or interruption is racing it.
        ensureOpen();
        if (count > length) throw new IOException("USB transfer returned an invalid byte count");
        return count;
    }
    boolean isAttached() { return manager.getDeviceList().containsKey(device.getDeviceName()); }
    String description() { return "USB " + device.getDeviceName(); }
    void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("USB transport closed");
        checkInterrupted();
    }
    static void checkBounds(byte[] buffer, int offset, int length) {
        if (buffer == null) throw new NullPointerException("buffer");
        if (offset < 0 || length < 0 || offset > buffer.length - length) throw new IndexOutOfBoundsException();
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("USB transfer cancelled");
    }
    IOException invalidate(IOException failure) {
        try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        return failure;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // No I/O lock: closing must be able to interrupt an active bulk transfer.
        try { connection.releaseInterface(iface); } finally { connection.close(); }
    }
}
