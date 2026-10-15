package android.hardware.usb;
public final class UsbEndpoint {
    private final int type, direction;
    public UsbEndpoint(int type, int direction) { this.type = type; this.direction = direction; }
    public int getType() { return type; }
    public int getDirection() { return direction; }
}
