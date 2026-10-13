package io.github.devicebridge.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Memory-only resource ownership and cancellation; no file/device operations. */
public final class OperationScopeTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        closeQueuedCleanup(); rejectedExecutor(); concurrentCleanup(); lateCloseFailure();
        interruptedOwnership(); invalidExecutor(); identityOwnership(); cleanupFailuresContinue(); concurrentCancellation();
        System.out.println("OperationScopeTests: " + assertions + " assertions passed (in-memory only)");
    }

    private static void closeQueuedCleanup() throws Exception {
        OperationScope scope = new OperationScope();
        AtomicInteger closes = new AtomicInteger();
        List<Runnable> queued = new ArrayList<Runnable>();
        scope.own((Closeable)closes::incrementAndGet);
        scope.cancelAsync(queued::add);
        check(scope.isCancelled() && closes.get() == 0 && queued.size() == 1, "async cancel marks before executing cleanup");
        scope.close();
        check(closes.get() == 1, "explicit close can drain pending asynchronous cleanup");
        queued.get(0).run(); scope.close(); scope.cancelAsync(queued::add);
        check(closes.get() == 1 && queued.size() == 1, "delayed task and repeated cancellation cannot double-close resources");
    }

    private static void rejectedExecutor() throws Exception {
        OperationScope scope = new OperationScope();
        ExecutorService rejected = Executors.newSingleThreadExecutor(); rejected.shutdown();
        AtomicReference<Thread> closer = new AtomicReference<Thread>();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        Thread caller = Thread.currentThread();
        scope.own((Closeable)() -> {
            closer.set(Thread.currentThread()); closes.incrementAndGet(); started.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test cleanup did not unblock"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new InterruptedIOException("test cleanup interrupted"); }
            finally { finished.countDown(); }
        });
        try {
            scope.cancelAsync(rejected);
            check(scope.isCancelled(), "executor rejection does not undo cancellation");
            check(started.await(2, TimeUnit.SECONDS), "rejected task still releases owned resources");
            check(closer.get() != caller && closer.get().isDaemon(), "fallback cleanup never blocks the calling UI thread");
            scope.close(); scope.cancelAsync(rejected);
            check(closes.get() == 1, "concurrent explicit close does not duplicate active fallback cleanup");
        } finally { release.countDown(); check(finished.await(2, TimeUnit.SECONDS), "fallback cleanup completes"); scope.close(); }
    }

    private static void concurrentCleanup() throws Exception {
        OperationScope scope = new OperationScope();
        AtomicInteger closes = new AtomicInteger(); List<Runnable> queued = new ArrayList<Runnable>();
        scope.own((Closeable)closes::incrementAndGet); scope.cancelAsync(queued::add);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            Thread worker = new Thread(() -> {
                try { go.await(); queued.get(0).run(); scope.close(); }
                catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
            worker.setDaemon(true); workers.add(worker); worker.start();
        }
        go.countDown();
        for (Thread worker : workers) { worker.join(2000); check(!worker.isAlive(), "cleanup caller terminates"); }
        check(error.get() == null && closes.get() == 1, "concurrent queued and synchronous cleanup has one owner");
    }

    private static void lateCloseFailure() throws Exception {
        OperationScope scope = new OperationScope(); scope.close();
        IOException failure = new IOException("synthetic close failure");
        try { scope.own((Closeable)() -> { throw failure; }); throw new AssertionError("cancelled scope accepted a late resource"); }
        catch (InterruptedIOException cancelled) { check(cancelled.getSuppressed().length == 1 && cancelled.getSuppressed()[0] == failure, "late resource close failure is retained with cancellation"); }
        check(!scope.progressSummary().isEmpty(), "unconfirmed late cleanup is recorded");
    }

    private static void interruptedOwnership() throws Exception {
        OperationScope scope = new OperationScope(); AtomicInteger closes = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            try { scope.own((Closeable)closes::incrementAndGet); throw new AssertionError("interrupted owner acquired a resource"); }
            catch (InterruptedIOException expected) { assertions++; }
            check(Thread.currentThread().isInterrupted() && closes.get() == 1, "interrupted acquisition preserves flag and closes its resource");
        } finally { Thread.interrupted(); scope.close(); }
        check(closes.get() == 1, "interrupted acquisition is not registered for duplicate cleanup");
    }

    private static void invalidExecutor() throws Exception {
        OperationScope scope = new OperationScope(); AtomicInteger closes = new AtomicInteger();
        scope.own((Closeable)closes::incrementAndGet);
        try { scope.cancelAsync(null); throw new AssertionError("missing executor accepted"); }
        catch (NullPointerException expected) { assertions++; }
        check(!scope.isCancelled(), "invalid local argument does not detach resources");
        scope.close(); check(closes.get() == 1, "resource remains reachable after an invalid executor");
        OperationScope live = new OperationScope();
        try { live.own(null); throw new AssertionError("null resource accepted"); }
        catch (NullPointerException expected) { assertions++; }
        check(!live.isCancelled() && live.progressSummary().isEmpty(), "invalid ownership leaves scope usable"); live.close();
    }

    private static void identityOwnership() throws Exception {
        OperationScope scope = new OperationScope(); AtomicInteger closes = new AtomicInteger();
        Closeable one = new EqualResource(closes), two = new EqualResource(closes);
        scope.own(one); scope.own(one); scope.own(two); scope.close();
        check(closes.get() == 2, "distinct equal resources are closed once each");
        OperationScope live = new OperationScope(); live.own(one); live.release(one); live.close();
        check(closes.get() == 2, "released resource is not closed by the scope");
    }

    private static void cleanupFailuresContinue() throws Exception {
        OperationScope scope = new OperationScope(); AtomicInteger closes = new AtomicInteger();
        scope.own((Closeable)() -> { closes.incrementAndGet(); throw new IOException("synthetic checked failure"); });
        scope.own((Closeable)() -> { closes.incrementAndGet(); throw new IllegalStateException("synthetic runtime failure"); });
        scope.own((Closeable)closes::incrementAndGet); scope.close();
        check(closes.get() == 3, "cleanup continues after checked and runtime failures");
        check(scope.progressSummary().contains("IOException") && scope.progressSummary().contains("IllegalStateException"), "cleanup failures remain visible in progress report");
        scope.close(); check(closes.get() == 3, "failed cleanup is not silently repeated");
    }

    private static void concurrentCancellation() throws Exception {
        OperationScope scope = new OperationScope(); AtomicInteger closes = new AtomicInteger();
        Queue<Runnable> queued = new ConcurrentLinkedQueue<Runnable>();
        scope.own((Closeable)closes::incrementAndGet); CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<Thread>();
        for(int i=0;i<8;i++) {
            Thread worker = new Thread(() -> {
                try { go.await(); scope.cancelAsync(queued::add); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            worker.setDaemon(true); workers.add(worker); worker.start();
        }
        go.countDown();
        for(Thread worker:workers){worker.join(2000);check(!worker.isAlive(),"cancellation caller terminates");}
        check(scope.isCancelled()&&queued.size()==1&&closes.get()==0,"concurrent cancellation schedules exactly one cleanup");
        queued.remove().run();check(closes.get()==1,"concurrent cancellation preserves its owned resource");
    }

    private static final class EqualResource implements Closeable {
        final AtomicInteger closes; EqualResource(AtomicInteger closes) { this.closes = closes; }
        public void close() { closes.incrementAndGet(); }
        public boolean equals(Object value) { return value instanceof EqualResource; }
        public int hashCode() { return 1; }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
