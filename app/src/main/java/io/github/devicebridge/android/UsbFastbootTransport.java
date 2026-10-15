package io.github.devicebridge.android;

import android.hardware.usb.*;
import io.github.devicebridge.core.FastbootClient;
import java.io.*;
import java.util.Arrays;

/** Fastboot status transfers retain their USB packet boundaries. */
final class UsbFastbootTransport implements FastbootClient.PacketTransport {
    private final UsbBulkConnection usb;
    UsbFastbootTransport(UsbManager manager, UsbDevice device) throws IOException {
        usb = new UsbBulkConnection(manager, device, 3);
    }
    public byte[] readPacket(int maxBytes) throws IOException {
        if (maxBytes < 1 || maxBytes > FastbootClient.MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("Invalid maximum Fastboot status size");
        byte[] buffer = new byte[maxBytes];
        try {
            int count = usb.read(buffer, 0, buffer.length, 30000);
            if (count <= 0) throw new IOException("Fastboot USB read failed or timed out");
            return Arrays.copyOf(buffer, count);
        } catch (IOException failure) { throw usb.invalidate(failure); }
    }
    public void writePacket(byte[] buffer, int offset, int length) throws IOException {
        UsbBulkConnection.checkBounds(buffer, offset, length);
        try {
            while (length > 0) {
                int count = usb.write(buffer, offset, Math.min(length, UsbBulkConnection.MAX_TRANSFER_BYTES), 30000);
                if (count <= 0) throw new IOException("Fastboot USB write failed or timed out");
                offset += count; length -= count;
            }
        } catch (IOException failure) { throw usb.invalidate(failure); }
    }
    public String description() { return usb.description(); }
    public void close() { usb.close(); }
}
