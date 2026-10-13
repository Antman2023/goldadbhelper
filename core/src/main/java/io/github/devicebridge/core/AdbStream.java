package io.github.devicebridge.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class AdbStream implements Closeable {
    private static final int MAX_BUFFERED_BYTES=8*1024*1024;
    private static final int RESUME_BUFFERED_BYTES=MAX_BUFFERED_BYTES/2;
    private final AdbSession session;
    private final Transport direct;
    private final int local,timeout;
    private int remote;
    private boolean opened,closed,writeReady;
    private IOException failure;
    private final ArrayDeque<byte[]> incoming=new ArrayDeque<>();
    private int offset,buffered;
    private boolean receiveAckPending;
    AdbStream(AdbSession session,int local,int timeout){this(session,local,timeout,null);}
    AdbStream(AdbSession session,int local,int timeout,Transport direct){this.session=session;this.local=local;this.timeout=timeout;this.direct=direct;}
    private void waitForChange(long deadline)throws IOException{
        long left=deadline-System.nanoTime();if(left<=0)throw new java.net.SocketTimeoutException("ADB stream timed out");
        try{TimeUnit.NANOSECONDS.timedWait(this,left);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}
    }
    synchronized void awaitOpen()throws IOException{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(!opened&&!closed&&failure==null)waitForChange(end);check();if(!opened)throw new IOException("ADB service rejected");}
    private void check()throws IOException{if(failure!=null)throw failure;}
    void onOkay(int id)throws IOException{
        boolean closeLateOpen;
        synchronized(this){
            if(opened&&remote!=id)throw new IOException("ADB stream target mismatch");
            // The reader may hold this stream after another thread cancels and forgets it.
            // A late OPEN acknowledgement must be closed, never resurrect the stream.
            closeLateOpen=closed;
            if(!closeLateOpen){remote=id;opened=true;writeReady=true;notifyAll();}
        }
        if(closeLateOpen)session.send(new AdbPacket(AdbPacket.CLSE,local,id,null));
    }
    void onData(int id,byte[] bytes)throws IOException{
        boolean acknowledge,overflow;int remoteId;
        synchronized(this){if(!opened||remote!=id)throw new IOException("Unexpected ADB stream writer");if(closed)return;
            remoteId=remote;overflow=receiveAckPending||bytes.length>MAX_BUFFERED_BYTES-buffered;
            if(overflow){failure=new IOException("Stream receive buffer limit exceeded or peer ignored flow control");closed=true;clearIncoming();acknowledge=false;}
            else{
                if(bytes.length>0){incoming.add(bytes);buffered+=bytes.length;}
                // Classic ADB allows one WRTE per OKAY. Withhold the ACK near capacity,
                // leaving room for any negotiated packet; never block the shared reader.
                // AOSP adb.cpp likewise delays send_ready until enqueue can accept more.
                receiveAckPending=buffered>MAX_BUFFERED_BYTES-session.maxPayload();
                acknowledge=!receiveAckPending;
            }
            notifyAll();}
        if(overflow){session.forget(local);session.send(new AdbPacket(AdbPacket.CLSE,local,remoteId,null));}
        else if(acknowledge)session.send(new AdbPacket(AdbPacket.OKAY,local,remoteId,null));
    }
    void onClose(int id)throws IOException{boolean respond;synchronized(this){if(opened&&remote!=id&&!(id==0&&session.allowsLegacyZeroClose()))throw new IOException("ADB close target mismatch");respond=!closed;closed=true;notifyAll();}if(respond)session.send(new AdbPacket(AdbPacket.CLSE,local,id,null));session.forget(local);}
    private void clearIncoming(){incoming.clear();offset=0;buffered=0;receiveAckPending=false;}
    void fail(IOException e){synchronized(this){failure=e;closed=true;clearIncoming();notifyAll();}if(direct!=null)try{direct.close();}catch(IOException ignored){}}
    private void abort(IOException error){
        boolean release,send;int remoteId;
        synchronized(this){release=!closed;send=release&&opened;remoteId=remote;if(failure==null)failure=error;closed=true;clearIncoming();notifyAll();}
        session.forget(local);
        try{
            if(direct!=null){if(release)direct.close();}
            else if(send)session.send(new AdbPacket(AdbPacket.CLSE,local,remoteId,null));
        }catch(IOException closeError){if(closeError!=error)error.addSuppressed(closeError);}
    }
    public InputStream input(){return new InputStream(){
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException();if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();if(len==0)return 0;
            if(direct!=null){synchronized(AdbStream.this){check();if(closed)return -1;}return direct.input().read(b,off,len);}
            int n,ackRemote=0;
            synchronized(AdbStream.this){long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(incoming.isEmpty()&&!closed&&failure==null)waitForChange(end);check();if(incoming.isEmpty())return -1;
                byte[] head=incoming.peek();n=Math.min(len,head.length-offset);System.arraycopy(head,offset,b,off,n);offset+=n;buffered-=n;if(offset==head.length){incoming.remove();offset=0;}
                if(receiveAckPending&&!closed&&buffered<=RESUME_BUFFERED_BYTES){receiveAckPending=false;ackRemote=remote;}
            }
            // Transport writes must happen outside the stream monitor, so cancellation
            // and other channels can still progress if the underlying writer stalls.
            if(ackRemote!=0)try{session.send(new AdbPacket(AdbPacket.OKAY,local,ackRemote,null));}
            catch(IOException error){
                // The consumer cannot retry bytes already copied into its buffer.
                // Terminate this channel when its deferred ACK cannot be sent.
                abort(error);throw error;
            }
            return n;
        }
        public void close()throws IOException{AdbStream.this.close();}
    };}
    public OutputStream output(){return new OutputStream(){
        public void write(int v)throws IOException{write(new byte[]{(byte)v});}
        public void write(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException();if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
            if(len==0)return;
            try{
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
            if(direct!=null){synchronized(AdbStream.this){check();if(closed)throw new EOFException("ADB stream closed");}direct.output().write(b,off,len);return;}
            while(len>0){int n=Math.min(len,session.maxPayload());byte[] chunk=Arrays.copyOfRange(b,off,off+n);
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
                synchronized(AdbStream.this){long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(!writeReady&&!closed&&failure==null)waitForChange(end);check();if(closed)throw new EOFException("ADB stream closed");writeReady=false;}
                session.send(new AdbPacket(AdbPacket.WRTE,local,remote,chunk));off+=n;len-=n;
            }
            }catch(IOException error){abort(error);throw error;}
        }
        public void flush()throws IOException{
            try{synchronized(AdbStream.this){check();if(closed)throw new EOFException("ADB stream closed");}if(direct!=null)direct.output().flush();}
            catch(IOException error){abort(error);throw error;}
        }
        public void close()throws IOException{AdbStream.this.close();}
    };}
    public void close()throws IOException{
        boolean send;int remoteId;
        synchronized(this){send=!closed&&opened;remoteId=remote;closed=true;clearIncoming();notifyAll();}
        session.forget(local);
        if(direct!=null){direct.close();return;}
        if(send)session.send(new AdbPacket(AdbPacket.CLSE,local,remoteId,null));
    }
}
