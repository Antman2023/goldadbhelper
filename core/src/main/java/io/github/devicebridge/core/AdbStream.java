package io.github.devicebridge.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class AdbStream implements Closeable {
    private final AdbSession session;
    private final Transport direct;
    private final int local,timeout;
    private int remote;
    private boolean opened,closed,writeReady;
    private IOException failure;
    private final ArrayDeque<byte[]> incoming=new ArrayDeque<>();
    private int offset,buffered;
    AdbStream(AdbSession session,int local,int timeout){this(session,local,timeout,null);}
    AdbStream(AdbSession session,int local,int timeout,Transport direct){this.session=session;this.local=local;this.timeout=timeout;this.direct=direct;}
    private void waitForChange(long deadline)throws IOException{
        long left=deadline-System.nanoTime();if(left<=0)throw new java.net.SocketTimeoutException("ADB stream timed out");
        try{TimeUnit.NANOSECONDS.timedWait(this,left);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}
    }
    synchronized void awaitOpen()throws IOException{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(!opened&&!closed&&failure==null)waitForChange(end);check();if(!opened)throw new IOException("ADB service rejected");}
    private void check()throws IOException{if(failure!=null)throw failure;}
    synchronized void onOkay(int id)throws IOException{if(opened&&remote!=id)throw new IOException("ADB stream target mismatch");remote=id;opened=true;writeReady=true;notifyAll();}
    void onData(int id,byte[] bytes)throws IOException{
        synchronized(this){if(!opened||remote!=id)throw new IOException("Unexpected ADB stream writer");if(closed)return;
            if(buffered+bytes.length>8*1024*1024){fail(new IOException("Stream receive buffer limit exceeded"));close();return;}
            if(bytes.length>0){incoming.add(bytes);buffered+=bytes.length;}notifyAll();}
        session.send(new AdbPacket(AdbPacket.OKAY,local,remote,null));
    }
    void onClose(int id)throws IOException{boolean respond;synchronized(this){if(opened&&remote!=id&&!(id==0&&session.allowsLegacyZeroClose()))throw new IOException("ADB close target mismatch");respond=!closed;closed=true;notifyAll();}if(respond)session.send(new AdbPacket(AdbPacket.CLSE,local,id,null));session.forget(local);}
    void fail(IOException e){synchronized(this){failure=e;closed=true;notifyAll();}if(direct!=null)try{direct.close();}catch(IOException ignored){}}
    public InputStream input(){return new InputStream(){
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException();if(off<0||len<0||off+len>b.length)throw new IndexOutOfBoundsException();if(len==0)return 0;
            if(direct!=null){synchronized(AdbStream.this){check();if(closed)return -1;}return direct.input().read(b,off,len);}
            synchronized(AdbStream.this){long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(incoming.isEmpty()&&!closed&&failure==null)waitForChange(end);check();if(incoming.isEmpty())return -1;
                byte[] head=incoming.peek();int n=Math.min(len,head.length-offset);System.arraycopy(head,offset,b,off,n);offset+=n;buffered-=n;if(offset==head.length){incoming.remove();offset=0;}return n;}
        }
        public void close()throws IOException{AdbStream.this.close();}
    };}
    public OutputStream output(){return new OutputStream(){
        public void write(int v)throws IOException{write(new byte[]{(byte)v});}
        public void write(byte[] b,int off,int len)throws IOException{
            if(off<0||len<0||off+len>b.length)throw new IndexOutOfBoundsException();
            if(direct!=null){synchronized(AdbStream.this){check();if(closed)throw new EOFException("ADB stream closed");}direct.output().write(b,off,len);return;}
            while(len>0){int n=Math.min(len,session.maxPayload());byte[] chunk=Arrays.copyOfRange(b,off,off+n);
                synchronized(AdbStream.this){long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);while(!writeReady&&!closed&&failure==null)waitForChange(end);check();if(closed)throw new EOFException("ADB stream closed");writeReady=false;}
                session.send(new AdbPacket(AdbPacket.WRTE,local,remote,chunk));off+=n;len-=n;
            }
        }
        public void flush()throws IOException{if(direct!=null)direct.output().flush();}
        public void close()throws IOException{AdbStream.this.close();}
    };}
    public void close()throws IOException{boolean send;synchronized(this){send=!closed;closed=true;notifyAll();}session.forget(local);if(direct!=null){direct.close();return;}if(send&&opened)session.send(new AdbPacket(AdbPacket.CLSE,local,remote,null));}
}
