package io.github.devicebridge.core;
import java.io.*;import java.util.*;
/** Owns in-flight resources. Cancellation closes handshakes instead of merely hiding their UI. */
public final class OperationScope implements Closeable {
 private final Set<Closeable> resources=Collections.newSetFromMap(new IdentityHashMap<Closeable,Boolean>());
 private volatile boolean cancelled;private final List<String> progress=new ArrayList<>();
 public synchronized void record(String message){progress.add(message);}
 public synchronized String progressSummary(){StringBuilder s=new StringBuilder();for(String p:progress)s.append(p).append("\n");return s.toString();}
 public boolean isCancelled(){return cancelled;}
 public <T extends Closeable> T own(T resource)throws IOException{
  synchronized(this){if(!cancelled){resources.add(resource);return resource;}}
  resource.close();throw new InterruptedIOException("Operation cancelled");
 }
 public synchronized void release(Closeable resource){resources.remove(resource);}
 public void checkActive()throws InterruptedIOException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Operation cancelled");}
 private synchronized List<Closeable> detach(){cancelled=true;List<Closeable> close=new ArrayList<>(resources);resources.clear();return close;}
 private void releaseAll(List<Closeable> close){for(Closeable resource:close)try{resource.close();}catch(IOException|RuntimeException failure){record("Resource close was not confirmed: "+failure.getClass().getSimpleName());}}
 /** Mark cancelled synchronously; transport shutdown must not send protocol frames on a UI thread. */
 public void cancelAsync(java.util.concurrent.Executor executor){List<Closeable> close=detach();if(!close.isEmpty())executor.execute(()->releaseAll(close));}
 public void close(){releaseAll(detach());}
}
