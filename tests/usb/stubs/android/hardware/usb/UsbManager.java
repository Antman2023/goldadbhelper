package android.hardware.usb;
import java.util.HashMap;
public final class UsbManager {
    public boolean permission = true;
    public int opens;
    public UsbDeviceConnection connection;
    public final HashMap<String, UsbDevice> devices = new HashMap<>();
    public boolean hasPermission(UsbDevice device) { return permission; }
    public UsbDeviceConnection openDevice(UsbDevice device) { opens++; return connection; }
    public HashMap<String, UsbDevice> getDeviceList() { return devices; }
}
