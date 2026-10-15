package android.hardware.usb;
public final class UsbInterface {
    private final int protocol;
    private final UsbEndpoint[] endpoints;
    public UsbInterface(int protocol, UsbEndpoint... endpoints) { this.protocol = protocol; this.endpoints = endpoints; }
    public int getInterfaceClass() { return 255; }
    public int getInterfaceSubclass() { return 66; }
    public int getInterfaceProtocol() { return protocol; }
    public int getEndpointCount() { return endpoints.length; }
    public UsbEndpoint getEndpoint(int index) { return endpoints[index]; }
}
