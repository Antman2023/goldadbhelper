package io.github.devicebridge.android;
import android.hardware.usb.*;import io.github.devicebridge.core.Transport;import java.io.*;
public final class UsbTransport implements Transport {
    private final UsbManager manager;private final UsbDevice device;private final UsbDeviceConnection connection;private final UsbInterface iface;private final UsbEndpoint in,out;private volatile boolean closed;private volatile int timeoutMs=20000;
    public static UsbInterface find(UsbDevice d,int protocol){for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);if(f.getInterfaceClass()==255&&f.getInterfaceSubclass()==66&&f.getInterfaceProtocol()==protocol)return f;}return null;}
    public UsbTransport(UsbManager m,UsbDevice d)throws IOException{
        manager=m;device=d;if(!m.hasPermission(d))throw new IOException("USB permission not granted");iface=find(d,1);if(iface==null)throw new IOException("Not an ADB USB interface");
        UsbEndpoint ri=null,wo=null;for(int i=0;i<iface.getEndpointCount();i++){UsbEndpoint e=iface.getEndpoint(i);if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){if(e.getDirection()==UsbConstants.USB_DIR_IN)ri=e;else wo=e;}}
        if(ri==null||wo==null)throw new IOException("Missing USB bulk endpoints");in=ri;out=wo;connection=m.openDevice(d);if(connection==null)throw new IOException("Unable to open USB device");if(!connection.claimInterface(iface,true)){connection.close();throw new IOException("Unable to claim USB interface");}
    }
    public InputStream input(){return new InputStream(){public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int off,int len)throws IOException{
        if(len==0)return 0;long started=System.nanoTime();while(!closed){if(timeoutMs>0&&(System.nanoTime()-started)/1000000>timeoutMs)throw new java.net.SocketTimeoutException("USB read timed out");if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");int n=connection.bulkTransfer(in,b,off,Math.min(len,16384),500);if(n>0)return n;if(!manager.getDeviceList().containsKey(device.getDeviceName()))throw new EOFException("USB device disconnected");}throw new IOException("USB transport closed");}};}
    public OutputStream output(){return new OutputStream(){public void write(int v)throws IOException{write(new byte[]{(byte)v});}public void write(byte[] b,int off,int len)throws IOException{while(len>0){if(closed)throw new IOException("USB transport closed");int n=connection.bulkTransfer(out,b,off,Math.min(len,16384),10000);if(n<=0)throw new IOException("USB write failed");off+=n;len-=n;}}};}
    public void setReadTimeout(int ms){timeoutMs=ms;}
    public String description(){return "USB "+device.getDeviceName();}
    public void close(){if(closed)return;closed=true;connection.releaseInterface(iface);connection.close();}
}
