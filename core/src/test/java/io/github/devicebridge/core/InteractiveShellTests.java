package io.github.devicebridge.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pure Java, entirely in-memory ADB/PTY fixtures. No socket, real shell or device is opened. */
public final class InteractiveShellTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        framing(); malformed(); legacy(); persistence(); inputValidation(); cancellation(); writeFailure();
        utf8(); ansi(); boundedDecoder(); concurrentWrites();
        System.out.println("InteractiveShellTests: " + assertions + " assertions passed (in-memory fixtures only)");
    }

    private static void framing() throws Exception {
        byte[] stdout = "中文\r\n$ ".getBytes(StandardCharsets.UTF_8);
        byte[] stderr = "oops\n".getBytes(StandardCharsets.UTF_8);
        byte[] wire = join(frame(1, stdout), frame(2, stderr), frame(1, new byte[0]), frame(4, new byte[0]), frame(3, new byte[]{(byte)255}));
        for (int fragment : new int[]{1, 2, 3, 7, 8192}) {
            Probe probe = new Probe(); TrackClose close = new TrackClose();
            InteractiveShell shell = fixture(shortInput(wire, fragment), true, probe, close);
            probe.await();
            equalBytes(stdout, probe.stdout.toByteArray(), "stdout exact across fragments");
            equalBytes(stderr, probe.stderr.toByteArray(), "stderr remains independent");
            check(probe.code == 255 && probe.error == null, "unsigned one-byte exit");
            check(shell.isClosed() && close.count.get() == 1 && probe.calls.get() == 1, "exit closes once");
        }
        QueueInput waiting = new QueueInput(); Probe exitOnly = new Probe();
        InteractiveShell shell = fixture(waiting, true, exitOnly, waiting);
        waiting.feed(frame(3, le32(12345))); // Deliberately never send transport EOF.
        exitOnly.await();
        check(exitOnly.code == 12345 && exitOnly.error == null && shell.isClosed(), "four-byte exit completes without ADB CLSE");
        byte[] big = new byte[40000]; Arrays.fill(big, (byte)'x');
        Probe chunks = new Probe();
        fixture(new ByteArrayInputStream(join(frame(1, big), frame(3, new byte[]{0}))), true, chunks, new TrackClose());
        chunks.await();
        equalBytes(big, chunks.stdout.toByteArray(), "large frame streamed intact");
        check(chunks.maximumChunk <= 8192 && chunks.outputCalls >= 5, "output callback allocation is bounded");
    }

    private static void malformed() throws Exception {
        byte[][] malformed = {
                new byte[0], new byte[]{1}, new byte[]{1, 1, 0},
                header(1, -1), header(1, 16 * 1024 * 1024 + 1),
                join(header(1, 2), new byte[]{1}), header(3, 0), frame(3, new byte[]{1,2}),
                join(header(3, 4), new byte[]{1,2}), frame(0, new byte[0]),
                frame(5, new byte[0]), frame(4, new byte[]{1}), frame(99, new byte[0])
        };
        for (byte[] wire : malformed) {
            Probe probe = new Probe(); TrackClose close = new TrackClose();
            InteractiveShell shell = fixture(shortInput(wire, 1), true, probe, close);
            probe.await();
            check(probe.error != null && probe.code == -1, "malformed protocol reports failure");
            check(shell.isClosed() && close.count.get() == 1 && probe.calls.get() == 1, "malformed protocol closes once");
        }
        Probe missingExit = new Probe();
        fixture(new ByteArrayInputStream(frame(1, new byte[]{42})), true, missingExit, new TrackClose());
        missingExit.await();
        check(missingExit.error instanceof EOFException, "v2 missing exit is explicit EOF error");
        check(missingExit.stdout.size() == 1, "output preceding lost exit is retained");
        Probe faulty = new Probe();
        fixture(new InputStream(){ public int read() throws IOException { throw new IOException("transport lost"); } }, true, faulty, new TrackClose());
        faulty.await();
        check(faulty.error != null && faulty.error.getMessage().contains("transport lost"), "transport error surfaced");
    }

    private static void legacy() throws Exception {
        Probe probe = new Probe(); byte[] text = "legacy 中文\r\n".getBytes(StandardCharsets.UTF_8);
        fixture(shortInput(text, 1), false, probe, new TrackClose()); probe.await();
        check(probe.code == -1 && probe.error == null, "legacy EOF has unknown exit and no false error");
        equalBytes(text, probe.stdout.toByteArray(), "legacy raw output retained");
        check(probe.stderr.size() == 0, "legacy does not invent stderr separation");
    }

    private static void persistence() throws Exception {
        for (boolean v2 : new boolean[]{true, false}) {
            try (MemoryAdb device = new MemoryAdb(v2)) {
                AdbSession session = new AdbSession(device, null, null); session.connect();
                Probe probe = new Probe(); InteractiveShell shell = new InteractiveShell(session, probe);
                shell.line("cd /sdcard"); shell.line("answer=中文"); shell.line("printf '%s' \"$answer\"");
                shell.interrupt(); shell.resize(132, 43); shell.eof();
                check(device.opens == 1, "successive input uses one persistent stream");
                equal(v2 ? "shell,v2,pty:\0" : "shell:\0", device.service, "explicit PTY service or legacy fallback");
                byte[] commands = "cd /sdcard\nanswer=中文\nprintf '%s' \"$answer\"\n".getBytes(StandardCharsets.UTF_8);
                if (v2) {
                    byte[] expected = join(frame(0, "cd /sdcard\n".getBytes(StandardCharsets.UTF_8)),
                            frame(0, "answer=中文\n".getBytes(StandardCharsets.UTF_8)),
                            frame(0, "printf '%s' \"$answer\"\n".getBytes(StandardCharsets.UTF_8)),
                            frame(0, new byte[]{3}), frame(5, "43x132,0x0\0".getBytes(StandardCharsets.US_ASCII)), frame(0, new byte[]{4}));
                    equalBytes(expected, device.stdin.toByteArray(), "stdin, Ctrl-C, resize, Ctrl-D v2 frame bytes");
                    device.remoteData(join(frame(1, "中文\r\n".getBytes(StandardCharsets.UTF_8)), frame(3, new byte[]{0})), 2);
                } else {
                    equalBytes(join(commands, new byte[]{3,4}), device.stdin.toByteArray(), "legacy stdin raw and resize no-op");
                    device.remoteData("中文\r\n".getBytes(StandardCharsets.UTF_8), 2);
                    device.remoteClose();
                }
                probe.await();
                check(probe.error == null && probe.code == (v2 ? 0 : -1), "real AdbSession adapter completes");
                equalBytes("中文\r\n".getBytes(StandardCharsets.UTF_8), probe.stdout.toByteArray(), "ADB fragmentation retained");
                check(session.state() == AdbSession.State.CONNECTED, "shell completion preserves parent ADB session");
                session.close();
            }
        }
    }

    private static void inputValidation() throws Exception {
        QueueInput input = new QueueInput(); Probe probe = new Probe(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveShell shell = new InteractiveShell(input, output, input, true, probe);
        rejects(IllegalArgumentException.class, () -> shell.line(null));
        rejects(IllegalArgumentException.class, () -> shell.line("bad\0line"));
        for (int[] size : new int[][]{{0,24},{80,0},{-1,24},{10001,24},{80,10001}})
            rejects(IllegalArgumentException.class, () -> shell.resize(size[0], size[1]));
        check(output.size() == 0, "invalid input never reaches wire");
        shell.line(""); shell.resize(1,10000);
        equalBytes(join(frame(0, new byte[]{10}), frame(5, "10000x1,0x0\0".getBytes(StandardCharsets.US_ASCII))), output.toByteArray(), "empty line and boundary resize accepted");
        shell.close(); probe.await();
        rejects(IOException.class, () -> shell.line("after close"));
        rejects(IOException.class, shell::interrupt);
        rejects(IOException.class, shell::eof);
        rejects(IOException.class, () -> shell.resize(80,24));
        check(probe.error == null && probe.calls.get() == 1, "user close is not a transport error");
    }

    private static void cancellation() throws Exception {
        for (boolean v2 : new boolean[]{true,false}) {
            QueueInput input = new QueueInput(); Probe probe = new Probe();
            InteractiveShell shell = fixture(input, v2, probe, input);
            check(input.readStarted.await(2,TimeUnit.SECONDS), "reader is blocked before cancellation");
            shell.close(); shell.close(); probe.await();
            check(probe.error == null && probe.code == -1 && probe.calls.get() == 1, "blocked reader cancelled once");
        }
        try (MemoryAdb device = new MemoryAdb(true)) {
            AdbSession session = new AdbSession(device, null, null); session.connect();
            Probe probe = new Probe(); InteractiveShell shell = new InteractiveShell(session, probe);
            device.autoAck = false;
            AtomicReference<Throwable> writeError = new AtomicReference<>(); CountDownLatch written = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                try { shell.line(repeat('x',12000)); } catch (Throwable error) { writeError.set(error); } finally { written.countDown(); }
            });
            writer.start(); check(device.firstWrite.await(2,TimeUnit.SECONDS), "writer waits on missing ADB ACK");
            shell.close();
            check(written.await(2,TimeUnit.SECONDS), "close unblocks writer without writer-lock deadlock");
            probe.await(); check(writeError.get() instanceof IOException, "cancelled writer sees IOException");
            check(session.state() == AdbSession.State.CONNECTED, "cancellation does not disconnect parent session");
            session.close(); writer.join(2000);
        }
        try (MemoryAdb device = new MemoryAdb(true)) {
            AdbSession session = new AdbSession(device, null, null); session.connect();
            Probe probe = new Probe(); InteractiveShell shell = new InteractiveShell(session, probe);
            device.autoAck = false; CountDownLatch written = new CountDownLatch(1); AtomicReference<Throwable> error = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try { shell.line(repeat('z',12000)); } catch (Throwable failure) { error.set(failure); } finally { written.countDown(); }
            });
            writer.start(); check(device.firstWrite.await(2,TimeUnit.SECONDS), "interrupt test writer entered flow control");
            writer.interrupt(); check(written.await(2,TimeUnit.SECONDS), "interrupted writer exits"); probe.await();
            check(error.get() instanceof InterruptedIOException && shell.isClosed(), "interrupted partial frame fails closed");
            session.close(); writer.join(2000);
        }
    }

    private static void writeFailure() throws Exception {
        QueueInput input = new QueueInput(); Probe probe = new Probe();
        OutputStream broken = new OutputStream(){ public void write(int b) throws IOException { throw new IOException("write failed"); } };
        InteractiveShell shell = new InteractiveShell(input, broken, input, true, probe);
        rejects(IOException.class, () -> shell.line("hello")); probe.await();
        check(shell.isClosed(), "partial write closes shell");
        Probe listenerFailure = new Probe(){ @Override public synchronized void output(boolean stderr,byte[] data) { throw new IllegalStateException("view disposed"); } };
        fixture(new ByteArrayInputStream(frame(1, new byte[]{1})), true, listenerFailure, new TrackClose());
        listenerFailure.await(); check(listenerFailure.error != null && listenerFailure.error.getCause() instanceof IllegalStateException, "callback exception closes and reaches ended");
    }

    private static void utf8() throws Exception {
        String text = "A中文🙂 café é\r\n\t末尾";
        byte[] wire = text.getBytes(StandardCharsets.UTF_8);
        for (int split=0; split<=wire.length; split++) {
            TerminalOutputDecoder decoder = new TerminalOutputDecoder();
            equal(text, decoder.accept(wire,0,split) + decoder.accept(wire,split,wire.length-split) + decoder.finish(), "UTF-8 arbitrary split");
        }
        TerminalOutputDecoder decoder = new TerminalOutputDecoder(); StringBuilder decoded = new StringBuilder();
        for(byte b:wire) decoded.append(decoder.accept(new byte[]{b})); decoded.append(decoder.finish());
        equal(text,decoded.toString(),"UTF-8 one byte at a time");
        byte[][] invalid = {new byte[]{(byte)0xe4}, new byte[]{(byte)0xe4,(byte)0xb8}, new byte[]{(byte)0xc0,(byte)0xaf},
                new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80}, new byte[]{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80}, new byte[]{(byte)0x80,65}};
        for(byte[] data:invalid) {
            decoder = new TerminalOutputDecoder(); decoded.setLength(0);
            for(byte b:data) decoded.append(decoder.accept(new byte[]{b})); decoded.append(decoder.finish());
            equal(new String(data,StandardCharsets.UTF_8),decoded.toString(),"malformed UTF-8 has deterministic replacement");
            equal("",decoder.finish(),"finish idempotent");
        }
        final TerminalOutputDecoder finished = decoder;
        rejects(IllegalStateException.class, () -> finished.accept(new byte[0]));
        Random random = new Random(50721L);
        for (int sample=0;sample<100;sample++) {
            byte[] malformed = new byte[257];
            for(int i=0;i<malformed.length;i++) malformed[i]=(byte)(0xa0+random.nextInt(96));
            TerminalOutputDecoder splitDecoder = new TerminalOutputDecoder(); StringBuilder result = new StringBuilder();
            for(byte b:malformed)result.append(splitDecoder.accept(new byte[]{b}));
            result.append(splitDecoder.finish());
            equal(new String(malformed,StandardCharsets.UTF_8),result.toString(),"fragmented malformed UTF-8 matches JDK replacement");
        }
        TerminalOutputDecoder first = new TerminalOutputDecoder(), second = new TerminalOutputDecoder();
        byte[] han = "中".getBytes(StandardCharsets.UTF_8);
        equal("",first.accept(han,0,1),"stdout stores partial code point");
        equal("err",second.accept("err".getBytes(StandardCharsets.UTF_8)),"stderr decoding independent");
        equal("中",first.accept(han,1,2),"stdout carry survives stderr packet");
    }

    private static void ansi() throws Exception {
        String wire = "\u001b[31m红色\u001b[0m\r\n\t" +
                "\u001b]0;secret title\u0007A\u001b]8;;https://invalid.example\u001b\\link\u001b]8;;\u001b\\" +
                "\u001b]52;c;clipboard\u0007B\u001bPpayload\u001b\\C\u001b_hidden\u001b\\D" +
                "\u001b(0E\u001b7F\u001b[2JG\u0000\u0007\b\u007f\u009b32mH\u009b0m" +
                "\u009dtitle\u009cI\u0090data\u009cJ";
        String expected = "红色\r\n\tAlinkBCDEFGHIJ";
        byte[] bytes=wire.getBytes(StandardCharsets.UTF_8);
        for(int split=0;split<=bytes.length;split++) {
            TerminalOutputDecoder d=new TerminalOutputDecoder();
            equal(expected,d.accept(bytes,0,split)+d.accept(bytes,split,bytes.length-split)+d.finish(),"split ANSI and control-string filtering");
        }
        TerminalOutputDecoder d = new TerminalOutputDecoder(); StringBuilder text = new StringBuilder();
        for(byte b:bytes) text.append(d.accept(new byte[]{b})); text.append(d.finish());
        equal(expected,text.toString(),"single-byte ANSI and UTF-8 fragmentation");
        for(String truncated:new String[]{"\u001b", "\u001b[31", "\u001b]title", "\u001bPdata\u001b"}) {
            d=new TerminalOutputDecoder();equal("ok",d.accept(("ok"+truncated).getBytes(StandardCharsets.UTF_8))+d.finish(),"truncated escape safely discarded");
        }
        d=new TerminalOutputDecoder();
        equal("a\rb\nc\td",d.accept("a\rb\nc\td".getBytes(StandardCharsets.UTF_8)),"CR LF tab unchanged");
        d=new TerminalOutputDecoder();
        equal("safe",d.accept("\u001b]aborted\u0018safe".getBytes(StandardCharsets.UTF_8)),"cancel escapes recovers text");
    }

    private static void boundedDecoder() throws Exception {
        TerminalOutputDecoder d=new TerminalOutputDecoder();
        String large=repeat('a',20000)+"中文🙂"+repeat('b',20000);
        equal(large,d.accept(large.getBytes(StandardCharsets.UTF_8))+d.finish(),"fixed-buffer decoder drains large calls");
        for(int pad=4093;pad<=4097;pad++) {
            String boundary=repeat('x',pad)+"中文🙂"+repeat('y',4096);
            TerminalOutputDecoder boundaryDecoder=new TerminalOutputDecoder();
            equal(boundary,boundaryDecoder.accept(boundary.getBytes(StandardCharsets.UTF_8))+boundaryDecoder.finish(),"UTF-8 straddles internal buffer boundary");
        }
        d=new TerminalOutputDecoder();equal("",d.accept(new byte[]{27,']'}),"OSC starts without text");
        byte[] chunk=new byte[8192];Arrays.fill(chunk,(byte)'x');
        for(int i=0;i<256;i++) check(d.accept(chunk).isEmpty(),"multi-megabyte OSC is not accumulated or displayed");
        equal("visible",d.accept("\u001b\\visible".getBytes(StandardCharsets.UTF_8))+d.finish(),"large OSC terminator recovers");
        d=new TerminalOutputDecoder();final TerminalOutputDecoder invalid=d;
        rejects(IndexOutOfBoundsException.class,()->invalid.accept(new byte[3],Integer.MAX_VALUE,1));
        rejects(IndexOutOfBoundsException.class,()->invalid.accept(new byte[3],1,Integer.MAX_VALUE));
    }

    private static void concurrentWrites() throws Exception {
        try(MemoryAdb device=new MemoryAdb(true)) {
            AdbSession session=new AdbSession(device,null,null);session.connect();
            Probe probe=new Probe();InteractiveShell shell=new InteractiveShell(session,probe);
            CountDownLatch start=new CountDownLatch(1),done=new CountDownLatch(2);AtomicReference<Throwable> error=new AtomicReference<>();
            Thread[] writers=new Thread[2];
            for(int i=0;i<2;i++) {
                final char value=(char)('a'+i);
                writers[i]=new Thread(()->{try{start.await();shell.line(repeat(value,12000));}catch(Throwable failure){error.set(failure);}finally{done.countDown();}});
                writers[i].start();
            }
            start.countDown();check(done.await(3,TimeUnit.SECONDS),"concurrent stdin writers finish");check(error.get()==null,"concurrent stdin writes succeed");
            byte[] actual=device.stdin.toByteArray();byte[] a=frame(0,(repeat('a',12000)+"\n").getBytes(StandardCharsets.UTF_8));
            byte[] b=frame(0,(repeat('b',12000)+"\n").getBytes(StandardCharsets.UTF_8));
            check(Arrays.equals(actual,join(a,b))||Arrays.equals(actual,join(b,a)),"large concurrent shell-v2 frames never interleave");
            shell.close();probe.await();session.close();for(Thread writer:writers)writer.join(2000);
        }
    }

    private static InteractiveShell fixture(InputStream in,boolean v2,Probe probe,Closeable close) {
        return new InteractiveShell(in,new ByteArrayOutputStream(),close,v2,probe);
    }
    private static class Probe implements InteractiveShell.Listener {
        final ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        final CountDownLatch ended=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();
        volatile int code=-99,maximumChunk,outputCalls;volatile IOException error;
        public synchronized void output(boolean err,byte[] data) {(err?stderr:stdout).write(data,0,data.length);maximumChunk=Math.max(maximumChunk,data.length);outputCalls++;}
        public void ended(int exit,IOException failure){code=exit;error=failure;calls.incrementAndGet();ended.countDown();}
        void await() throws Exception {check(ended.await(3,TimeUnit.SECONDS),"terminal ended promptly");}
    }
    private static final class TrackClose implements Closeable {final AtomicInteger count=new AtomicInteger();public void close(){count.incrementAndGet();}}
    private static final class QueueInput extends InputStream {
        final ArrayDeque<byte[]> queue=new ArrayDeque<>();final CountDownLatch readStarted=new CountDownLatch(1);int offset;boolean closed;
        synchronized void feed(byte[] bytes){if(bytes.length>0)queue.add(bytes);notifyAll();}
        public int read() throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        public synchronized int read(byte[] data,int at,int length) throws IOException {
            if(length==0)return 0;readStarted.countDown();
            while(queue.isEmpty()&&!closed)try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("cancelled");}
            if(queue.isEmpty())return -1;byte[] head=queue.peek();int n=Math.min(length,head.length-offset);System.arraycopy(head,offset,data,at,n);offset+=n;
            if(offset==head.length){queue.remove();offset=0;}return n;
        }
        public synchronized void close(){closed=true;queue.clear();offset=0;notifyAll();}
    }
    private static final class MemoryAdb implements Transport {
        final QueueInput incoming=new QueueInput();final ByteArrayOutputStream stdin=new ByteArrayOutputStream();
        final boolean v2;final CountDownLatch firstWrite=new CountDownLatch(1);volatile boolean autoAck=true;
        volatile int opens,local;volatile String service;
        MemoryAdb(boolean v2){this.v2=v2;}
        public InputStream input(){return incoming;}
        public OutputStream output(){return new OutputStream(){
            public void write(int b){throw new AssertionError("ADB must write complete packets");}
            public void write(byte[] b,int offset,int length)throws IOException{
                AdbPacket packet=AdbPacket.read(new ByteArrayInputStream(b,offset,length),AdbPacket.MAX_PAYLOAD,true);
                if(packet.command==AdbPacket.CNXN)send(new AdbPacket(AdbPacket.CNXN,0x01000001,4096,AdbPacket.text("device::features="+(v2?"shell_v2":"cmd")+";\0")));
                else if(packet.command==AdbPacket.OPEN){opens++;local=packet.arg0;service=new String(packet.payload,StandardCharsets.UTF_8);send(new AdbPacket(AdbPacket.OKAY,71,local,null));}
                else if(packet.command==AdbPacket.WRTE){stdin.write(packet.payload);firstWrite.countDown();if(autoAck)send(new AdbPacket(AdbPacket.OKAY,71,local,null));}
            }
        };}
        void send(AdbPacket packet){incoming.feed(packet.encode());}
        void remoteData(byte[] data,int fragment){for(int i=0;i<data.length;i+=fragment)send(new AdbPacket(AdbPacket.WRTE,71,local,Arrays.copyOfRange(data,i,Math.min(data.length,i+fragment))));}
        void remoteClose(){send(new AdbPacket(AdbPacket.CLSE,71,local,null));}
        public String description(){return "in-memory PTY fixture";}
        public void setReadTimeout(int timeout){}
        public void close(){incoming.close();}
    }
    private interface Checked {void run() throws Exception;}
    private static void rejects(Class<? extends Throwable> type,Checked work)throws Exception{
        assertions++;try{work.run();}catch(Throwable error){if(type.isInstance(error))return;throw new AssertionError("Expected "+type+", got "+error,error);}throw new AssertionError("Expected "+type);
    }
    private static void check(boolean good,String message){assertions++;if(!good)throw new AssertionError(message);}
    private static void equal(String expected,String actual,String message){check(expected.equals(actual),message+": expected "+expected+", got "+actual);}
    private static void equalBytes(byte[] expected,byte[] actual,String message){check(Arrays.equals(expected,actual),message);}
    private static byte[] le32(int n){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();}
    private static byte[] header(int id,int length)throws IOException{return join(new byte[]{(byte)id},le32(length));}
    private static byte[] frame(int id,byte[] data)throws IOException{return join(header(id,data.length),data);}
    private static byte[] join(byte[]... arrays)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] bytes:arrays)out.write(bytes);return out.toByteArray();}
    private static InputStream shortInput(byte[] bytes,final int size){return new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int o,int n){return super.read(b,o,Math.min(size,n));}};}
    private static String repeat(char c,int count){char[] text=new char[count];Arrays.fill(text,c);return new String(text);}
}
