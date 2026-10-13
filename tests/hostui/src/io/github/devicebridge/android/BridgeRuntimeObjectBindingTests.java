package io.github.devicebridge.android;
import io.github.devicebridge.android.BridgeRuntimeObjectBindingSubject.AdbSession;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
/** Ordinary memory objects, exercising extracted production method bodies only. */
public final class BridgeRuntimeObjectBindingTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        BridgeRuntimeObjectBindingSubject runtime = new BridgeRuntimeObjectBindingSubject();
        AdbSession stale = new AdbSession("same endpoint / serial #17");
        AdbSession current = new AdbSession("same endpoint / serial #17");
        runtime.sessions.put(current.target(), current); runtime.active = "another active session";
        check(!runtime.select((AdbSession) null), "null cannot select");
        check(!runtime.select(stale), "same target string does not authorize selecting a newer object");
        eq("another active session", runtime.active, "stale selection preserves current active target");
        eq(current, runtime.sessions.get(current.target()), "stale selection preserves registered new object");
        check(runtime.select(current), "exact registered object can select");
        eq(current.target(), runtime.active, "exact selection updates active target");
        check(!runtime.disconnect((AdbSession) null), "null cannot disconnect");
        check(!runtime.disconnect(stale), "old same-target card cannot disconnect newer session");
        eq(current, runtime.sessions.get(current.target()), "rejected disconnect keeps current object");
        eq(current.target(), runtime.active, "rejected disconnect preserves active target");
        eq(0, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "rejected disconnect queues no close");
        eq(0, current.closes + stale.closes, "rejected operation closes neither object");
        check(runtime.disconnect(current), "exact current object can disconnect");
        check(runtime.sessions.isEmpty(), "exact disconnect removes current mapping");
        eq(null, runtime.active, "disconnect of active object clears active target");
        eq(1, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "exact disconnect queues one close");
        eq(0, current.closes, "close waits for executor rather than occurring inline");
        BridgeRuntimeObjectBindingSubject.pendingClosers.remove(0).run();
        eq(1, current.closes, "queued close targets exact removed object");
        eq(0, stale.closes, "queued close never targets stale card object");
        check(!runtime.disconnect(current), "repeated disconnect rejected");
        check(!runtime.select(current), "removed session cannot be reselected");
        eq(0, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "repeated disconnect queues no second close");
        AdbSession background = new AdbSession("background");
        runtime.sessions.put(background.target(), background); runtime.active = "other";
        check(runtime.disconnect(background), "exact nonactive session can disconnect");
        eq("other", runtime.active, "disconnect of nonactive object keeps other active selection");
        BridgeRuntimeObjectBindingSubject.pendingClosers.remove(0).run();
        eq(1, background.closes, "nonactive exact object closed once");
        replacement(); targetDisconnect(); disconnectAllSnapshot(); blockedCleanup();
        System.out.println("RUNTIME OBJECT BINDING TESTS: " + checks + " assertions passed");
        System.out.println("Scope: exact extracted production selection/replacement/disconnect methods; memory sessions/state/executor only.");
    }

    private static void replacement() {
        BridgeRuntimeObjectBindingSubject runtime = new BridgeRuntimeObjectBindingSubject();
        AdbSession old = new AdbSession("same"), current = new AdbSession("same");
        runtime.add(old); runtime.add(current);
        eq(current, runtime.sessions.get("same"), "replacement immediately publishes new object");
        eq("same", runtime.active, "replacement immediately selects new object");
        eq(0, old.closes, "replacement does not close transport on caller");
        eq(1, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "replacement queues old object once");
        runtime.add(current);
        eq(1, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "adding same object does not close it");
        check(!runtime.disconnect(old), "stale sidebar object cannot disconnect replacement");
        BridgeRuntimeObjectBindingSubject.pendingClosers.remove(0).run();
        eq(1, old.closes, "replacement cleanup closes old object");
        eq(0, current.closes, "replacement cleanup preserves new object");
        eq(current, runtime.sessions.get("same"), "cleanup preserves current registration");
    }

    private static void targetDisconnect() {
        BridgeRuntimeObjectBindingSubject runtime = new BridgeRuntimeObjectBindingSubject();
        AdbSession old = new AdbSession("USB fixture"), current = new AdbSession("USB fixture");
        runtime.add(old); runtime.disconnect(old.target());
        check(runtime.sessions.isEmpty() && runtime.active == null, "USB target disconnect removes selection immediately");
        eq(0, old.closes, "target disconnect queues transport close");
        runtime.add(current);
        BridgeRuntimeObjectBindingSubject.pendingClosers.remove(0).run();
        eq(1, old.closes, "delayed target close captures removed object");
        eq(0, current.closes, "delayed target close preserves reconnection");
        runtime.disconnect("missing");
        eq("USB fixture", runtime.active, "missing target preserves selection");
        eq(0, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "missing target queues no close");
    }

    private static void disconnectAllSnapshot() {
        BridgeRuntimeObjectBindingSubject runtime = new BridgeRuntimeObjectBindingSubject();
        AdbSession one = new AdbSession("one"), two = new AdbSession("two");
        runtime.add(one); runtime.add(two);
        BridgeRuntimeObjectBindingSubject.HostServerClient oldServer = new BridgeRuntimeObjectBindingSubject.HostServerClient();
        runtime.externalServer = oldServer;
        runtime.disconnectAll();
        check(runtime.sessions.isEmpty() && runtime.active == null && runtime.externalServer == null,
                "disconnect all atomically detaches sessions, selection and server");
        check(oldServer.listener == null, "detached server stops delivering new callbacks");
        eq(0, one.closes + two.closes + oldServer.closes, "disconnect all does no synchronous network cleanup");
        eq(3, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "each detached resource has cleanup scheduled");
        runtime.disconnectAll();
        eq(3, BridgeRuntimeObjectBindingSubject.pendingClosers.size(), "repeated disconnect all schedules no duplicate cleanup");
        AdbSession next = new AdbSession("one"); runtime.add(next);
        BridgeRuntimeObjectBindingSubject.HostServerClient nextServer = new BridgeRuntimeObjectBindingSubject.HostServerClient();
        runtime.externalServer = nextServer;
        while (!BridgeRuntimeObjectBindingSubject.pendingClosers.isEmpty()) BridgeRuntimeObjectBindingSubject.pendingClosers.remove(0).run();
        check(one.closes == 1 && two.closes == 1 && oldServer.closes == 1, "snapshot closes each old resource");
        check(next.closes == 0 && nextServer.closes == 0, "snapshot preserves newly connected resources");
        eq(next, runtime.sessions.get("one"), "new session remains registered after old cleanup");
        eq(nextServer, runtime.externalServer, "new server remains selected after old cleanup");
    }

    private static void blockedCleanup() throws Exception {
        BridgeRuntimeObjectBindingSubject runtime = new BridgeRuntimeObjectBindingSubject();
        AdbSession old = new AdbSession("blocked"); runtime.add(old);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        old.closeHook = () -> {
            try {
                if (Thread.holdsLock(runtime)) throw new AssertionError("cleanup holds runtime lock");
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("cleanup release timed out");
            } catch (Throwable error) { failure.set(error); }
        };
        runtime.disconnectAll();
        Thread closer = new Thread(BridgeRuntimeObjectBindingSubject.pendingClosers.remove(0)); closer.setDaemon(true); closer.start();
        try {
            check(entered.await(2, TimeUnit.SECONDS), "background close enters blocked transport fixture");
            AdbSession next = new AdbSession("next"); runtime.add(next);
            check(runtime.select(next), "runtime remains selectable while old close is blocked");
            eq(0, next.closes, "blocked old cleanup does not affect new connection");
        } finally { release.countDown(); closer.join(2000); }
        check(!closer.isAlive() && failure.get() == null, "blocked cleanup completes without runtime monitor held");
    }
    private static void eq(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + " expected=" + expected + " actual=" + actual); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
