package io.github.devicebridge.core;

/** Ordinary in-memory gestures and styled output only; no Android, I/O, or device operations. */
public final class RetainedPagerGestureTests {
    private static int checks;
    public static void main(String[] args) {
        ModuleSwipePolicy gesture = new ModuleSwipePolicy(8, 64, 500);
        gesture.begin(200, 100, 10, 1, true); gesture.move(190, 101, 20, 1, true);
        check(gesture.horizontal(), "horizontal ownership starts above slop");
        check(gesture.horizontalDistance() == -10, "first frame follows finger");
        gesture.move(100, 102, 2010, 1, true);
        check(gesture.horizontal() && gesture.horizontalDistance() == -100, "owned slow drag continues beyond long-press timeout");
        check(gesture.destination(2, 7) == 3, "slow owned drag commits one adjacent page");
        gesture.move(220, 102, 2050, 1, true);
        check(gesture.destination(2, 7) == 2, "reverse short drag settles back");
        gesture.move(300, 102, 2070, 1, true);
        check(gesture.destination(2, 7) == 1, "reversed owned gesture chooses previous neighbor");
        gesture.begin(200, 100, 10, 1, true); gesture.move(100, 101, 600, 1, true);
        check(!gesture.horizontal(), "stationary long-press never becomes pager");
        gesture.begin(200, 100, 10, 1, true); gesture.move(180, 101, 20, 1, true);
        gesture.move(100, 101, 2000, 2, true); gesture.move(0, 101, 2100, 1, true);
        check(!gesture.horizontal(), "multitouch cancels an already owned drag permanently");
        gesture.begin(200, 100, 10, 1, true); gesture.move(180, 101, 20, 1, true);
        gesture.move(100, 101, 100, 1, false); gesture.move(0, 101, 110, 1, true);
        check(!gesture.horizontal(), "lifecycle invalidation never regains ownership");
        gesture.begin(200, 100, 10, 1, true); gesture.move(180, 101, 20, 1, true); gesture.cancel();
        check(gesture.destination(2, 7) == 2, "cancel keeps selected module");
        gesture.begin(200, 100, 10, 1, true); gesture.move(-2000, 101, 20, 1, true);
        check(gesture.destination(2, 7) == 3, "oversized motion cannot skip multiple modules");
        check(gesture.destination(6, 7) == 6, "last page remains bounded");
        TerminalTranscript original = new TerminalTranscript(128);
        original.append(TerminalTranscript.Text.plain("literal output\n", TerminalTranscript.NORMAL));
        original.append(TerminalTranscript.Text.plain("example error\n", TerminalTranscript.ERROR));
        original.append(TerminalTranscript.Text.plain("shell@device:/ $ ", TerminalTranscript.NORMAL));
        TerminalTranscript.Text snapshot = original.snapshot();
        TerminalTranscript restored = new TerminalTranscript(128); restored.append(snapshot);
        check(original.text().equals(restored.text()), "rotation snapshot preserves literal output");
        for (int i = 0; i < original.length(); i++) check(original.styleAt(i) == restored.styleAt(i), "rotation snapshot preserves style " + i);
        original.reset();
        check(snapshot.length() > 0 && restored.length() == snapshot.length(), "snapshot independent of old renderer reset");
        System.out.println("RETAINED PAGER GESTURE TESTS: " + checks + " passed");
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
}
