package io.github.devicebridge.core;

/** Synthetic coordinates and literal addresses only. No Android, interface enumeration, or sockets. */
public final class ModuleNavigationTests {
    private static int checks;
    public static void main(String[] args) {
        ModuleSwipePolicy gesture = new ModuleSwipePolicy(8, 64, 500);
        begin(gesture); gesture.move(90, 204, 120, 1, true);
        check(gesture.horizontal() && gesture.destination(2, 7) == 3, "left moves to next");
        begin(gesture); gesture.move(300, 204, 120, 1, true);
        check(gesture.horizontal() && gesture.destination(2, 7) == 1, "right moves to previous");
        check(gesture.destination(0, 7) == 0, "first boundary stays put");
        begin(gesture); gesture.move(90, 200, 120, 1, true);
        check(gesture.destination(6, 7) == 6, "last boundary stays put");
        begin(gesture); gesture.move(204, 200, 110, 1, true);
        check(!gesture.horizontal() && gesture.destination(3, 7) == 3, "tap jitter untouched");
        begin(gesture); gesture.move(180, 200, 120, 1, true);
        check(gesture.horizontal() && gesture.destination(3, 7) == 3, "short drag does not navigate");
        begin(gesture); gesture.move(202, 220, 110, 1, true); gesture.move(60, 230, 150, 1, true);
        check(gesture.vertical() && gesture.destination(3, 7) == 3, "vertical scrolling never becomes pager");
        begin(gesture); gesture.move(180, 216, 110, 1, true); gesture.move(60, 215, 150, 1, true);
        check(!gesture.horizontal() && gesture.destination(3, 7) == 3, "ambiguous diagonal remains child gesture");
        begin(gesture); gesture.move(180, 200, 110, 1, true); gesture.move(100, 310, 140, 1, true);
        check(gesture.destination(3, 7) == 3, "vertical finish does not commit horizontal start");
        begin(gesture); gesture.move(90, 200, 120, 2, true); gesture.move(50, 200, 150, 1, true);
        check(gesture.destination(3, 7) == 3, "multi-touch permanently cancels stream");
        gesture.begin(200, 200, 100, 2, true); gesture.move(50, 200, 150, 1, true);
        check(gesture.destination(3, 7) == 3, "multi-touch down cannot become swipe");
        gesture.begin(200, 200, 100, 1, false); gesture.move(50, 200, 150, 1, true);
        check(gesture.destination(3, 7) == 3, "icon strip, text, editor, modal or busy start excluded");
        begin(gesture); gesture.move(150, 200, 120, 1, true); gesture.move(100, 200, 130, 1, false); gesture.move(40, 200, 150, 1, true);
        check(gesture.destination(3, 7) == 3, "session, generation or lifecycle change cancels permanently");
        begin(gesture); gesture.move(50, 200, 700, 1, true);
        check(gesture.destination(3, 7) == 3, "long press does not turn into swipe");
        begin(gesture); gesture.move(50, 200, 120, 1, true); gesture.cancel();
        check(gesture.destination(3, 7) == 3, "cancel or child disallow does not navigate");
        begin(gesture); gesture.move(50, 200, 120, 1, true);
        check(gesture.destination(3, 7) == 4, "fresh swipe allowed after prior cancel or completed progress");
        begin(gesture); gesture.move(Float.NaN, 200, 120, 1, true);
        check(gesture.destination(3, 7) == 3, "invalid coordinates rejected");
        check(Ipv4SubnetSuggestion.fromAddress("192.168.42.8").equals("192.168.42"), "private wifi prefix");
        check(Ipv4SubnetSuggestion.fromAddress("10.0.2.15").equals("10.0.2"), "private ethernet prefix");
        check(Ipv4SubnetSuggestion.fromAddress("172.16.8.1").equals("172.16.8"), "private lower boundary");
        check(Ipv4SubnetSuggestion.fromAddress("172.31.255.254").equals("172.31.255"), "private upper boundary");
        String[] rejected = {null, "", "192.168.1", "192.168.1.256", "192.168.-1.2", "192.168.01.2", "192.168.1.2.", " 192.168.1.2", "192.168.1.2:5555", "example.test", "::1", "fe80::1234", "127.0.0.1", "169.254.1.2", "0.0.0.0", "224.0.0.1", "255.255.255.255", "172.15.1.2", "172.32.1.2", "203.0.113.1"};
        for (String address : rejected) check(Ipv4SubnetSuggestion.fromAddress(address).isEmpty(), "no unsafe/default suggestion: " + address);
        System.out.println("MODULE NAVIGATION TESTS: " + checks + " passed");
    }
    private static void begin(ModuleSwipePolicy gesture) { gesture.begin(200, 200, 100, 1, true); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
