package io.github.devicebridge.core;

/** Synthetic-only lifetime checks: in-memory identities and numeric page indices. */
public final class RetainedModuleStateTests {
    private static int checks;

    public static void main(String[] args) {
        revisitKeepsViewsButExpiresWork();
        refreshIsLocal();
        identityAndRebind();
        invalidationAndClose();
        boundariesAndFailedCalls();
        longSyntheticNavigation();
        System.out.println("RETAINED MODULE STATE TESTS: " + checks + " passed");
    }

    private static void revisitKeepsViewsButExpiresWork() {
        RetainedModuleState<Object> model = new RetainedModuleState<>();
        Object session = new Object();
        RetainedModuleState.Snapshot<Object> first = model.bind(session, 3, 0);
        Object firstToken = first.pageToken();
        Object secondToken = model.pageToken(1);
        check(first.session() == session && first.page() == 0, "snapshot captures session and page");
        check(first.activation() > 0 && model.isCurrent(first), "initial activation is live");
        check(model.pageToken(0) == firstToken, "active page token is stable");
        check(model.pageToken(1) == secondToken && firstToken != secondToken, "lazy slots get distinct stable tokens");
        check(model.isCurrent(first), "lazy token creation does not activate another page");
        RetainedModuleState.Snapshot<Object> second = model.activate(1);
        check(second.pageToken() == secondToken, "activation uses retained token");
        check(second.activation() > first.activation(), "activation increases on page change");
        check(!model.isCurrent(first), "old callback rejected off page");
        check(model.snapshotForView(session, 0, firstToken) == null, "hidden view cannot start work");
        RetainedModuleState.Snapshot<Object> revisit = model.activate(0);
        check(revisit.pageToken() == firstToken, "returning to page retains its instance");
        check(!model.isCurrent(first) && !model.isCurrent(second), "revisit never revives old callbacks");
        check(revisit.activation() > second.activation(), "revisit advances activation");
        RetainedModuleState.Snapshot<Object> click = model.snapshotForView(session, 0, firstToken);
        check(click != null && model.isCurrent(click), "retained view click gets fresh authority");
        check(click.activation() == revisit.activation(), "fresh click uses current activation");
        check(model.snapshotForView(session, 1, firstToken) == null, "token cannot move to a different slot");
        check(model.snapshotForView(session, 0, secondToken) == null, "wrong page token cannot start work");
        RetainedModuleState.Snapshot<Object> repeated = model.activate(0);
        check(repeated.pageToken() == firstToken && repeated.activation() > revisit.activation(), "repeated activation preserves view and advances clock");
        check(!model.isCurrent(click) && model.isCurrent(repeated), "repeat activation expires previous click operation");
    }

    private static void refreshIsLocal() {
        RetainedModuleState<Object> model = new RetainedModuleState<>();
        Object session = new Object();
        RetainedModuleState.Snapshot<Object> first = model.bind(session, 4, 0);
        Object token0 = first.pageToken();
        Object token1 = model.pageToken(1);
        Object token2 = model.pageToken(2);
        Object replaced1 = model.refresh(1);
        check(replaced1 != token1 && model.pageToken(1) == replaced1, "inactive refresh replaces only requested slot");
        check(model.pageToken(0) == token0 && model.pageToken(2) == token2, "other retained pages survive refresh");
        check(model.currentPage() == 0 && model.isCurrent(first), "inactive refresh does not disturb active operation");
        RetainedModuleState.Snapshot<Object> next = model.activate(1);
        check(next.pageToken() == replaced1, "refreshed page activates its new instance");
        check(model.snapshotForView(session, 1, token1) == null, "old refreshed view click is rejected");
        check(model.snapshotForView(session, 1, replaced1) != null, "replacement view click is live");
        Object replacedAgain = model.refresh(1);
        RetainedModuleState.Snapshot<Object> refreshed = model.snapshot();
        check(replacedAgain != replaced1 && refreshed.pageToken() == replacedAgain, "active refresh replaces page");
        check(refreshed.activation() > next.activation(), "active refresh advances activation");
        check(!model.isCurrent(next) && model.isCurrent(refreshed), "active refresh rejects old callback");
        check(model.snapshotForView(session, 1, replaced1) == null, "active refresh rejects old view");
        check(model.pageToken(0) == token0 && model.pageToken(2) == token2, "active refresh preserves other pages");
        Object fresh3 = model.refresh(3);
        check(model.pageToken(3) == fresh3 && model.isCurrent(refreshed), "refresh of unvisited slot leaves active work live");
    }

    private static void identityAndRebind() {
        RetainedModuleState<NamedTarget> model = new RetainedModuleState<>();
        NamedTarget firstSession = new NamedTarget(7);
        NamedTarget equalSession = new NamedTarget(7);
        check(firstSession.equals(equalSession) && firstSession != equalSession, "synthetic equal names use distinct objects");
        RetainedModuleState.Snapshot<NamedTarget> first = model.bind(firstSession, 3, 0);
        Object token0 = first.pageToken();
        Object token1 = model.pageToken(1);
        RetainedModuleState.Snapshot<NamedTarget> same = model.bind(firstSession, 3, 1);
        check(same.pageToken() == token1 && model.pageToken(0) == token0, "same identity and count retain page instances");
        check(same.activation() > first.activation() && !model.isCurrent(first), "same-session bind still begins new activation");
        RetainedModuleState.Snapshot<NamedTarget> changed = model.bind(equalSession, 3, 0);
        check(changed.pageToken() != token0 && model.pageToken(1) != token1, "equal-valued new session clears all retained pages");
        check(!model.isCurrent(same), "session change expires old operation");
        check(model.snapshotForView(firstSession, 0, changed.pageToken()) == null, "session equals cannot authorize another identity");
        check(model.snapshotForView(equalSession, 0, token0) == null, "new session cannot revive previous page token");
        Object changedToken = changed.pageToken();
        RetainedModuleState.Snapshot<NamedTarget> resized = model.bind(equalSession, 2, 0);
        check(resized.pageToken() != changedToken && model.pageCount() == 2, "page count change resets retained pages");
        check(!model.isCurrent(changed), "resize expires old callback");
        RetainedModuleState.Snapshot<NamedTarget> returnSession = model.bind(firstSession, 3, 0);
        check(returnSession.pageToken() != token0, "switching back does not restore abandoned device views");
        check(!model.isCurrent(first), "switching back does not restore abandoned callback");
        RetainedModuleState<NamedTarget> otherModel = new RetainedModuleState<>();
        RetainedModuleState.Snapshot<NamedTarget> other = otherModel.bind(firstSession, 3, 0);
        check(!model.isCurrent(other) && !otherModel.isCurrent(returnSession), "same session cannot transfer snapshots between hosts");
    }

    private static void invalidationAndClose() {
        RetainedModuleState<Object> model = new RetainedModuleState<>();
        check(!model.isBound() && model.session() == null && model.currentPage() == -1 && model.pageCount() == 0, "fresh host has no session or page");
        check(model.snapshot() == null && !model.isCurrent(null), "unbound host has no valid operation");
        check(model.snapshotForView(null, -1, null) == null, "unbound invalid view safely rejected");
        Object session = new Object();
        RetainedModuleState.Snapshot<Object> first = model.bind(session, 2, 1);
        model.invalidate();
        check(!model.isBound() && model.session() == null && model.pageCount() == 0 && model.currentPage() == -1, "leaving clears session and all slots");
        check(!model.isCurrent(first) && model.snapshot() == null, "leave rejects pending callback");
        check(model.snapshotForView(session, 1, first.pageToken()) == null, "leave rejects retained view clicks");
        model.invalidate();
        RetainedModuleState.Snapshot<Object> rebound = model.bind(session, 2, 1);
        check(rebound.pageToken() != first.pageToken() && rebound.activation() > first.activation(), "reentry starts new page lifetime without resetting clock");
        check(!model.isCurrent(first), "reentry cannot revive previous callback");
        model.close();
        model.close();
        check(model.session() == null && model.pageCount() == 0 && model.currentPage() == -1, "close idempotently clears host");
        check(!model.isCurrent(rebound) && model.snapshotForView(session, 1, rebound.pageToken()) == null, "close expires operations and views");
        RetainedModuleState.Snapshot<Object> reopened = model.bind(session, 2, 1);
        check(reopened.pageToken() != rebound.pageToken() && reopened.activation() > rebound.activation(), "explicit bind after close creates a fresh lifetime");
        check(!model.isCurrent(rebound) && model.isCurrent(reopened), "reopen does not revive closed callback");
    }

    private static void boundariesAndFailedCalls() {
        RetainedModuleState<Object> model = new RetainedModuleState<>();
        expect(IllegalStateException.class, () -> model.activate(0), "unbound activation fails");
        expect(IllegalStateException.class, () -> model.pageToken(0), "unbound page creation fails");
        expect(IllegalStateException.class, () -> model.refresh(0), "unbound refresh fails");
        expect(IllegalArgumentException.class, () -> model.bind(null, 1, 0), "null session rejected");
        expect(IllegalArgumentException.class, () -> model.bind(new Object(), 0, 0), "zero pages rejected");
        expect(IllegalArgumentException.class, () -> model.bind(new Object(), -1, 0), "negative page count rejected");
        expect(IndexOutOfBoundsException.class, () -> model.bind(new Object(), 2, -1), "negative initial page rejected");
        expect(IndexOutOfBoundsException.class, () -> model.bind(new Object(), 2, 2), "initial page at count rejected");
        check(!model.isBound(), "failed initial calls never partially bind");
        Object session = new Object();
        RetainedModuleState.Snapshot<Object> active = model.bind(session, 1, 0);
        check(active.page() == 0 && model.pageCount() == 1, "single-page boundary supported");
        int[] invalid = {-1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int page : invalid) {
            expect(IndexOutOfBoundsException.class, () -> model.activate(page), "invalid activation rejected: " + page);
            expect(IndexOutOfBoundsException.class, () -> model.pageToken(page), "invalid token slot rejected: " + page);
            expect(IndexOutOfBoundsException.class, () -> model.refresh(page), "invalid refresh rejected: " + page);
            check(model.snapshotForView(session, page, active.pageToken()) == null, "invalid view index safely rejected: " + page);
            check(model.isCurrent(active), "invalid call leaves current operation live: " + page);
        }
        expect(IllegalArgumentException.class, () -> model.bind(null, 1, 0), "null rebind rejected");
        expect(IllegalArgumentException.class, () -> model.bind(new Object(), 0, 0), "invalid count rebind rejected");
        expect(IndexOutOfBoundsException.class, () -> model.bind(new Object(), 2, 2), "invalid start rebind rejected");
        check(model.isCurrent(active) && model.session() == session && model.pageToken(0) == active.pageToken(), "bad rebind leaves previous state unchanged");
        check(model.snapshotForView(new Object(), 0, active.pageToken()) == null, "unrelated session view rejected");
        check(model.snapshotForView(session, 0, null) == null && model.snapshotForView(session, 0, new Object()) == null, "missing and invented tokens rejected");
    }

    private static void longSyntheticNavigation() {
        RetainedModuleState<Object> model = new RetainedModuleState<>();
        Object session = new Object();
        RetainedModuleState.Snapshot<Object> previous = model.bind(session, 7, 0);
        Object[] tokens = new Object[7];
        for (int page = 0; page < tokens.length; page++) tokens[page] = model.pageToken(page);
        for (int i = 0; i < 512; i++) {
            int page = i % tokens.length;
            RetainedModuleState.Snapshot<Object> current = model.activate(page);
            check(current.activation() > previous.activation() && !model.isCurrent(previous), "synthetic activation clock never reverses: " + i);
            check(current.pageToken() == tokens[page] && model.isCurrent(current), "synthetic revisit retains its token: " + i);
            previous = current;
        }
    }

    private static void expect(Class<? extends RuntimeException> expected, Runnable action, String label) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            check(expected.isInstance(failure), label + " (wrong exception: " + failure + ")");
            return;
        }
        throw new AssertionError(label + " (no exception)");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static final class NamedTarget {
        private final int name;
        NamedTarget(int name) { this.name = name; }
        @Override public boolean equals(Object other) {
            return other instanceof NamedTarget && ((NamedTarget) other).name == name;
        }
        @Override public int hashCode() { return name; }
    }
}
