package io.github.devicebridge.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Synthetic fixtures only: no sockets, device connections, external APKs, or media programs. */
public final class ScreenProtocolTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        annexSplits(); annexBounds(); sequenceHeaders(); accessUnits(); metadata(); controls(); geometry(); quoting();
        System.out.println("ScreenProtocolTests: " + assertions + " assertions passed (synthetic fixtures only)");
    }
    private static void check(boolean good, String message) { assertions++; if (!good) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked c, String message) throws Exception {
        assertions++; try { c.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static byte[] bytes(int... values) { byte[] b = new byte[values.length]; for (int i=0;i<b.length;i++) b[i]=(byte)values[i]; return b; }
    private static byte[] concat(byte[]... arrays) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); for(byte[] a:arrays)o.write(a); return o.toByteArray(); }
    private static void annexSplits() throws Exception {
        byte[] wire = bytes(0,0,0,1,0x67,0x42,0,0,3,1,0,0,1,0x68,0xce,0,0,0,1,0x65,0x80,0,0);
        InputStream shortReads = new ByteArrayInputStream(wire) { @Override public synchronized int read(byte[] b,int off,int len) { return super.read(b,off,Math.min(len,1)); } };
        AnnexBReader r = new AnnexBReader(shortReads);
        check(Arrays.equals(r.nextNal(), bytes(0x67,0x42,0,0,3,1)), "emulation prevention remains inside NAL");
        check(Arrays.equals(r.nextNal(), bytes(0x68,0xce)), "three byte start code");
        check(Arrays.equals(r.nextNal(), bytes(0x65,0x80)), "four byte start code and trailing zeros");
        check(r.nextNal()==null && r.nextNal()==null, "EOF is stable");
        r = new AnnexBReader(new ByteArrayInputStream(bytes(0,0,1,0,0,1,0x65,0x80)));
        check(Arrays.equals(r.nextNal(), bytes(0x65,0x80)), "empty delimiters skipped");
        check(new AnnexBReader(new ByteArrayInputStream(new byte[0])).nextNal()==null, "empty input");
    }
    private static void annexBounds() throws Exception {
        rejects(() -> new AnnexBReader(new ByteArrayInputStream(bytes(0,0,1,0x65,1,2,3)),2).nextNal(), "NAL oversize rejected");
        rejects(() -> new AnnexBReader(new ByteArrayInputStream("permission denied".getBytes(StandardCharsets.US_ASCII))).nextNal(), "text instead of H264 rejected");
        rejects(() -> new AnnexBReader(new ByteArrayInputStream(bytes(0,0,1,0xff,0x80))).nextNal(), "forbidden NAL bit rejected");
        rejects(() -> new AnnexBReader(new ByteArrayInputStream(new byte[65537])).nextNal(), "bounded start code search");
        rejects(() -> new AnnexBReader(new ByteArrayInputStream(bytes(0,0,1,0x65)),0), "invalid limit rejected");
        rejects(() -> AnnexBReader.firstMacroblock(bytes(0x65)), "truncated Golomb rejected");
        rejects(() -> AnnexBReader.firstMacroblock(bytes(0x65,0,0,3,0x04)), "invalid escape rejected");
    }
    private static byte[] sps(int widthMbs, int heightMbs, int cropBottom) {
        BitWriter b = new BitWriter(); b.bits(66,8); b.bits(0,8); b.bits(30,8); b.ue(0); b.ue(0); b.ue(0); b.ue(0);
        b.ue(1); b.bits(0,1); b.ue(widthMbs-1); b.ue(heightMbs-1); b.bits(1,1); b.bits(1,1);
        b.bits(cropBottom == 0 ? 0 : 1,1); if(cropBottom>0) { b.ue(0);b.ue(0);b.ue(0);b.ue(cropBottom); }
        b.bits(0,1); b.bits(1,1); return b.nal(0x67);
    }
    private static void sequenceHeaders() throws Exception {
        check(Arrays.equals(AnnexBReader.dimensions(sps(80,45,0)),new int[]{1280,720}), "SPS 720p");
        check(Arrays.equals(AnnexBReader.dimensions(sps(120,68,4)),new int[]{1920,1080}), "SPS bottom crop to 1080");
        check(Arrays.equals(AnnexBReader.dimensions(sps(45,80,0)),new int[]{720,1280}), "portrait SPS");
        rejects(() -> AnnexBReader.dimensions(bytes(0x67,0x42,0)), "truncated SPS rejected");
        rejects(() -> AnnexBReader.dimensions(sps(600,45,0)), "oversized dimensions rejected");
        rejects(() -> AnnexBReader.dimensions(sps(1,1,20)), "negative crop rejected");
    }
    private static void accessUnits() throws Exception {
        byte[] first = concat(AnnexBReader.withStartCode(sps(80,45,0)), AnnexBReader.withStartCode(bytes(0x68,0x80)), AnnexBReader.withStartCode(bytes(0x65,0x80)), AnnexBReader.withStartCode(bytes(0x65,0x40)));
        byte[] second = AnnexBReader.withStartCode(bytes(0x41,0x80));
        AnnexBReader.AccessUnits a = new AnnexBReader.AccessUnits(new AnnexBReader(new ByteArrayInputStream(concat(first,second))));
        check(Arrays.equals(a.next(),first), "multi-slice picture stays together"); check(Arrays.equals(a.next(),second), "next first macroblock starts picture"); check(a.next()==null,"access-unit EOF");
        rejects(() -> new AnnexBReader.AccessUnits(new AnnexBReader(new ByteArrayInputStream(bytes(0,0,1,0x62,0x80)))).next(), "unsupported data partition rejected");
    }
    private static void metadata() throws Exception {
        ByteBuffer b=ByteBuffer.allocate(12).putInt(0x68323634).putInt(1080).putInt(1920);
        check(Arrays.equals(ScrcpyProtocol.readVideoHeader(new DataInputStream(new ByteArrayInputStream(b.array()))),new int[]{1080,1920}), "codec metadata big endian");
        rejects(() -> ScrcpyProtocol.readVideoHeader(new DataInputStream(new ByteArrayInputStream(new byte[12]))), "disabled stream rejected");
        b=ByteBuffer.allocate(15).putLong(ScrcpyProtocol.CONFIG).putInt(3).put(bytes(1,2,3));
        ScrcpyProtocol.Packet p=ScrcpyProtocol.readPacket(new DataInputStream(new ByteArrayInputStream(b.array())));
        check(p.configuration&&!p.keyFrame&&p.ptsUs==0&&Arrays.equals(p.bytes,bytes(1,2,3)), "config flag unpacking");
        b=ByteBuffer.allocate(13).putLong(ScrcpyProtocol.KEY_FRAME|1234567L).putInt(1).put((byte)9);
        p=ScrcpyProtocol.readPacket(new DataInputStream(new ByteArrayInputStream(b.array())));
        check(p.keyFrame&&!p.configuration&&p.ptsUs==1234567L,"keyframe PTS unpacking");
        byte[] tooLarge=ByteBuffer.allocate(12).putLong(0).putInt(ScrcpyProtocol.MAX_PACKET+1).array();
        rejects(() -> ScrcpyProtocol.readPacket(new DataInputStream(new ByteArrayInputStream(tooLarge))), "packet allocation bounded");
        byte[] truncated=ByteBuffer.allocate(13).putLong(0).putInt(2).put((byte)9).array();
        rejects(() -> ScrcpyProtocol.readPacket(new DataInputStream(new ByteArrayInputStream(truncated))), "packet truncation rejected");
        rejects(() -> ScrcpyProtocol.validateSize(-1,720), "negative size rejected");
    }
    private static void controls() throws Exception {
        ByteBuffer b=ByteBuffer.wrap(ScrcpyProtocol.touch(0,0x0102030405060708L,20,40,1080,1920,1));
        check(b.remaining()==32&&b.get()==2&&b.get()==0,"v2.4 touch type and size"); check(b.getLong()==0x0102030405060708L,"64-bit stable pointer ID");
        check(b.getInt()==20&&b.getInt()==40&&(b.getShort()&65535)==1080&&(b.getShort()&65535)==1920,"touch geometry encoding");
        check((b.getShort()&65535)==65535&&b.getInt()==0&&b.getInt()==0,"touch pressure/buttons encoding");
        check(ScrcpyProtocol.touch(1,2,0,0,1,1,0)[23]==0,"release has zero pressure");
        byte[] key=ScrcpyProtocol.key(1,187); check(key.length==14&&key[1]==1&&ByteBuffer.wrap(key).getInt(2)==187,"key up encoding");
        byte[] txt=ScrcpyProtocol.text("中文"); check(txt.length==11&&ByteBuffer.wrap(txt).getInt(1)==6,"UTF8 text byte count");
        byte[] paste=ScrcpyProtocol.paste("中文"); check(paste[0]==9&&paste[9]==1&&ByteBuffer.wrap(paste).getLong(1)==0&&ByteBuffer.wrap(paste).getInt(10)==6,"explicit clipboard paste without ACK");
        rejects(() -> ScrcpyProtocol.touch(0,1,1080,0,1080,1920,1),"right bound rejected");
        rejects(() -> ScrcpyProtocol.text(new String(new char[301]).replace('\0','a')),"inject text maximum enforced");
    }
    private static void geometry() {
        check(Arrays.equals(ScrcpyProtocol.map(500,500,1000,1000,100,200,false),new int[]{50,100}),"letterbox center");
        check(ScrcpyProtocol.map(249,500,1000,1000,100,200,false)==null,"left bar excluded");
        check(ScrcpyProtocol.map(750,500,1000,1000,100,200,false)==null,"right edge excluded");
        check(Arrays.equals(ScrcpyProtocol.map(250,0,1000,1000,100,200,false),new int[]{0,0}),"top left content included");
        check(Arrays.equals(ScrcpyProtocol.map(500,250,1000,500,200,100,false),new int[]{100,50}),"landscape fit");
        check(Arrays.equals(ScrcpyProtocol.map(100,500,1000,1000,100,200,true),new int[]{10,100}),"stretch maps independently");
        check(ScrcpyProtocol.map(Float.NaN,0,100,100,10,10,false)==null,"NaN rejected");
        check(ScrcpyProtocol.map(1,1,0,100,10,10,false)==null,"zero viewport rejected");
    }
    private static void quoting() throws Exception {
        check(ScrcpyProtocol.shellText("hello world").equals("input text 'hello%sworld'"),"shell spaces encoded");
        check(ScrcpyProtocol.shellText("a'b;$(id)").equals("input text 'a'\\''b;$(id)'"),"shell syntax safely single-quoted");
        rejects(() -> ScrcpyProtocol.shellText("中文"),"unsupported shell unicode explicit");
        rejects(() -> ScrcpyProtocol.shellText("literal%s"),"percent substitution rejected");
        rejects(() -> ScrcpyProtocol.shellText("x\ny"),"shell newline rejected");
    }
    private static final class BitWriter {
        final StringBuilder bits=new StringBuilder();
        void bits(int n,int count) { for(int i=count-1;i>=0;i--)bits.append((n>>>i)&1); }
        void ue(int n) { int value=n+1; int length=32-Integer.numberOfLeadingZeros(value); for(int i=1;i<length;i++)bits.append('0'); bits(value,length); }
        byte[] nal(int header) { while(bits.length()%8!=0)bits.append('0'); ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);int zeros=0;
            for(int i=0;i<bits.length();i+=8){int v=Integer.parseInt(bits.substring(i,i+8),2);if(zeros>=2&&v<=3){out.write(3);zeros=0;}out.write(v);zeros=v==0?zeros+1:0;}return out.toByteArray(); }
    }
}
