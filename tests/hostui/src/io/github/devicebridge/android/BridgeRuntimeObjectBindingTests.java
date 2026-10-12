package io.github.devicebridge.android;
import io.github.devicebridge.android.BridgeRuntimeObjectBindingSubject.AdbSession;
import java.util.Objects;
/** Ordinary memory objects, exercising extracted production method bodies only. */
public final class BridgeRuntimeObjectBindingTests {
    private static int checks;
    public static void main(String[] args) {
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
        System.out.println("RUNTIME OBJECT BINDING TESTS: " + checks + " assertions passed");
        System.out.println("Scope: exact extracted production select(AdbSession)/disconnect(AdbSession); memory sessions/state/executor only.");
    }
    private static void eq(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + " expected=" + expected + " actual=" + actual); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
