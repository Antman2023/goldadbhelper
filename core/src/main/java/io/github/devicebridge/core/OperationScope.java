package io.github.devicebridge.core;
import java.io.*;import java.util.*;import java.util.concurrent.Executor;
/** Owns in-flight resources. Cancellation closes handshakes instead of merely hiding their UI. */
public final class OperationScope implements Closeable {
 private final Set<Closeable> resources=Collections.newSetFromMap(new IdentityHashMap<Closeable,Boolean>());
 private volatile boolean cancelled;private final List<String> progress=new ArrayList<>();
 private Cleanup pendingCleanup;
 public synchronized void record(String message){progress.add(message);}
 public synchronized String progressSummary(){StringBuilder s=new StringBuilder();for(String p:progress)s.append(p).append("\n");return s.toString();}
 public boolean isCancelled(){return cancelled;}
 public <T extends Closeable> T own(T resource)throws IOException{
  if(resource==null)throw new NullPointerException("resource");
  synchronized(this){if(!cancelled&&!Thread.currentThread().isInterrupted()){resources.add(resource);return resource;}}
  InterruptedIOException cancellation=new InterruptedIOException("Operation cancelled");
  try{resource.close();}catch(IOException|RuntimeException failure){cancellation.addSuppressed(failure);recordCloseFailure(failure);}
  throw cancellation;
 }
 public synchronized void release(Closeable resource){resources.remove(resource);}
 public void checkActive()throws InterruptedIOException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Operation cancelled");}
 private synchronized Cleanup detach(){
  cancelled=true;
  if(pendingCleanup==null&&!resources.isEmpty()){pendingCleanup=new Cleanup(new ArrayList<>(resources));resources.clear();}
  return pendingCleanup;
 }
 private void recordCloseFailure(Exception failure){record("Resource close was not confirmed: "+failure.getClass().getSimpleName());}
 private void releaseAll(List<Closeable> close){for(Closeable resource:close)try{resource.close();}catch(IOException|RuntimeException failure){recordCloseFailure(failure);}}
 private final class Cleanup implements Runnable {
  final List<Closeable> close;boolean scheduled,started;
  Cleanup(List<Closeable> close){this.close=close;}
  public void run(){
   synchronized(OperationScope.this){if(started)return;started=true;}
   try{releaseAll(close);}
   finally{synchronized(OperationScope.this){close.clear();if(pendingCleanup==this)pendingCleanup=null;}}
  }
 }
 /** Mark cancelled synchronously; transport shutdown must not send protocol frames on a UI thread. */
 public void cancelAsync(Executor executor){
  if(executor==null)throw new NullPointerException("executor");
  final Cleanup cleanup;
  synchronized(this){cleanup=detach();if(cleanup==null||cleanup.scheduled||cleanup.started)return;cleanup.scheduled=true;}
  try{executor.execute(cleanup);}
  catch(RuntimeException rejected){
   // Retain ownership until a worker claims it. A rejected closer must neither
   // lose the resources nor perform potentially blocking I/O on the caller.
   try{Thread fallback=new Thread(cleanup,"goldenbeetle-cancel-cleanup");fallback.setDaemon(true);fallback.start();}
   catch(RuntimeException unavailable){
    synchronized(this){if(!cleanup.started)cleanup.scheduled=false;}
    record("Resource cleanup could not be scheduled; resources remain pending for close/retry: "+unavailable.getClass().getSimpleName());
   }
  }
 }
 /** Drain queued cleanup once; an already-running background closer keeps ownership. */
 public void close(){Cleanup cleanup=detach();if(cleanup!=null)cleanup.run();}
}
