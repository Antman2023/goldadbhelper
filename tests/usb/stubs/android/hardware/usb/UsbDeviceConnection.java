package android.hardware.usb;
public abstract class UsbDeviceConnection {
    public abstract boolean claimInterface(UsbInterface iface, boolean force);
    public abstract boolean releaseInterface(UsbInterface iface);
    public abstract void close();
    public abstract int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout);
    public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        return bulkTransfer(endpoint, buffer, 0, length, timeout);
    }
}
