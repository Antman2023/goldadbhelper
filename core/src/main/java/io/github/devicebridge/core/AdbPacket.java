package io.github.devicebridge.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class AdbPacket {
    public static final int CNXN = command("CNXN"), AUTH = command("AUTH"), OPEN = command("OPEN"),
        OKAY = command("OKAY"), WRTE = command("WRTE"), CLSE = command("CLSE"), STLS = command("STLS");
    public static final int MAX_PAYLOAD = 1024 * 1024;
    public final int command, arg0, arg1;
    public final byte[] payload;
    public AdbPacket(int cmd, int arg0, int arg1, byte[] data) {
        this.command = cmd; this.arg0 = arg0; this.arg1 = arg1;
        payload = data == null ? new byte[0] : data;
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("ADB payload too large");
    }
    public static int command(String s) {
        if (s.length() != 4) throw new IllegalArgumentException("Command must be four bytes");
        return (s.charAt(0)&255) | ((s.charAt(1)&255)<<8) | ((s.charAt(2)&255)<<16) | ((s.charAt(3)&255)<<24);
    }
    public static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(checksum(payload)).putInt(command ^ -1).put(payload);
        return b.array();
    }
    public static AdbPacket read(InputStream in, int maxPayload, boolean checkChecksum) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(readExactly(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        int c=h.getInt(), a=h.getInt(), b=h.getInt(), n=h.getInt(), sum=h.getInt(), magic=h.getInt();
        if (magic != (c ^ -1)) throw new IOException("Invalid ADB packet magic");
        if (n < 0 || n > Math.min(maxPayload, MAX_PAYLOAD)) throw new IOException("Invalid ADB payload length: " + n);
        byte[] data = readExactly(in, n);
        if (checkChecksum && checksum(data) != sum) throw new IOException("ADB checksum mismatch");
        return new AdbPacket(c,a,b,data);
    }
    public static int checksum(byte[] bytes) { int sum=0; for (byte b:bytes) sum += b&255; return sum; }
    public static byte[] readExactly(InputStream in, int n) throws IOException {
        if (n<0) throw new IOException("Negative length");
        byte[] data=new byte[n]; int at=0;
        while(at<n) { int got=in.read(data,at,n-at); if(got<0) throw new EOFException("Truncated stream"); if(got==0) continue; at+=got; }
        return data;
    }
}
