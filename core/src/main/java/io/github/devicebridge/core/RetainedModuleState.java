package io.github.devicebridge.core;

/**
 * Pure lifetime model for lazily retained module pages. Sessions and page tokens
 * are compared by identity. A page token lives across visits, while an operation
 * snapshot belongs to just one activation of that page.
 *
 * <p>This class owns no views, resources or device operations. Its synchronized
 * checks describe validity at the time of the check; callers must still arrange
 * for UI changes and any subsequent effects on their appropriate owner thread.</p>
 */
public final class RetainedModuleState<T> implements AutoCloseable {
    private T session;
    private Object[] pageTokens = new Object[0];
    private int currentPage = -1;
    private long activation;

    /** Immutable authority for work started during a single page activation. */
    public static final class Snapshot<T> {
        private final T session;
        private final int page;
        private final Object pageToken;
        private final long activation;

        private Snapshot(T session, int page, Object pageToken, long activation) {
            this.session = session;
            this.page = page;
            this.pageToken = pageToken;
            this.activation = activation;
        }

        public T session() { return session; }
        public int page() { return page; }
        public Object pageToken() { return pageToken; }
        public long activation() { return activation; }
    }

    /**
     * Binds and activates a page. Only the exact same session and page count may
     * retain existing pages. Every successful bind starts a new activation.
     * Invalid arguments leave the previous binding unchanged.
     */
    public synchronized Snapshot<T> bind(T nextSession, int count, int start) {
        if (nextSession == null) throw new IllegalArgumentException("Missing session");
        if (count <= 0) throw new IllegalArgumentException("Page count must be positive");
        requirePage(start, count);
        long nextActivation = nextActivation();
        if (nextSession != session || count != pageTokens.length) {
            Object[] nextTokens = new Object[count];
            session = nextSession;
            pageTokens = nextTokens;
        }
        currentPage = start;
        activation = nextActivation;
        ensureToken(start);
        return currentSnapshot();
    }

    /** Activates even an already selected page; older work cannot regain validity. */
    public synchronized Snapshot<T> activate(int page) {
        requireBoundPage(page);
        long nextActivation = nextActivation();
        ensureToken(page);
        currentPage = page;
        activation = nextActivation;
        return currentSnapshot();
    }

    /** Returns a stable page-instance token, creating it lazily when needed. */
    public synchronized Object pageToken(int page) {
        requireBoundPage(page);
        return ensureToken(page);
    }

    /**
     * Replaces only this page's token. Refreshing an inactive page does not
     * navigate or invalidate work on the current page. An active refresh also
     * starts a new activation. The returned token belongs to the replacement page.
     */
    public synchronized Object refresh(int page) {
        requireBoundPage(page);
        long nextActivation = page == currentPage ? nextActivation() : activation;
        Object replacement = new Object();
        pageTokens[page] = replacement;
        activation = nextActivation;
        return replacement;
    }

    /** Returns the current operation snapshot, or null when unbound. */
    public synchronized Snapshot<T> snapshot() {
        return session == null ? null : currentSnapshot();
    }

    /** Checks an operation without letting a revisited page revive old work. */
    public synchronized boolean isCurrent(Snapshot<T> candidate) {
        return candidate != null && session != null
                && candidate.session == session
                && candidate.page == currentPage
                && candidate.pageToken == pageTokens[currentPage]
                && candidate.activation == activation;
    }

    /**
     * Lets a retained view start fresh work after a revisit. The view must still
     * belong to the same session and page instance, and be the active page now.
     * Stale, refreshed, inactive or unrelated views return null.
     */
    public synchronized Snapshot<T> snapshotForView(T boundSession, int page, Object token) {
        if (session == null || boundSession != session || page != currentPage
                || token == null || token != pageTokens[currentPage]) return null;
        return currentSnapshot();
    }

    public synchronized T session() { return session; }
    public synchronized int pageCount() { return pageTokens.length; }
    public synchronized int currentPage() { return currentPage; }
    public synchronized boolean isBound() { return session != null; }

    /** Clears all page instances and the session; the activation clock is not reset. */
    public synchronized void invalidate() {
        session = null;
        pageTokens = new Object[0];
        currentPage = -1;
    }

    /** Equivalent to leaving the module host. A later bind may start a new lifetime. */
    @Override public synchronized void close() { invalidate(); }

    private Object ensureToken(int page) {
        if (pageTokens[page] == null) pageTokens[page] = new Object();
        return pageTokens[page];
    }

    private Snapshot<T> currentSnapshot() {
        return new Snapshot<>(session, currentPage, pageTokens[currentPage], activation);
    }

    private void requireBoundPage(int page) {
        if (session == null) throw new IllegalStateException("No bound session");
        requirePage(page, pageTokens.length);
    }

    private static void requirePage(int page, int count) {
        if (page < 0 || page >= count) throw new IndexOutOfBoundsException("Page: " + page);
    }

    private long nextActivation() {
        if (activation == Long.MAX_VALUE) throw new IllegalStateException("Activation limit reached");
        return activation + 1;
    }
}
