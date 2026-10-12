package io.github.devicebridge.core;

/** Pure gesture policy. A stream locks its first axis and never gains eligibility later. */
public final class ModuleSwipePolicy {
    private enum Axis { BLOCKED, PENDING, HORIZONTAL, VERTICAL }
    private final float slop, distance;
    private final long maxDuration;
    private Axis axis = Axis.BLOCKED;
    private float startX, startY, dx, dy;
    private long started;

    public ModuleSwipePolicy(float slop, float distance, long maxDuration) {
        if (!(slop > 0) || !(distance >= slop) || maxDuration <= 0) throw new IllegalArgumentException();
        this.slop = slop; this.distance = distance; this.maxDuration = maxDuration;
    }
    public void begin(float x, float y, long time, int pointers, boolean eligible) {
        startX = x; startY = y; started = time; dx = dy = 0;
        axis = eligible && pointers == 1 && finite(x) && finite(y) ? Axis.PENDING : Axis.BLOCKED;
    }
    public void move(float x, float y, long time, int pointers, boolean eligible) {
        if (!eligible || pointers != 1 || !finite(x) || !finite(y) || time < started
                || axis == Axis.PENDING && time - started > maxDuration) { cancel(); return; }
        dx = x - startX; dy = y - startY;
        if (axis != Axis.PENDING || Math.max(Math.abs(dx), Math.abs(dy)) < slop) return;
        if (Math.abs(dy) >= Math.abs(dx)) axis = Axis.VERTICAL;
        else if (Math.abs(dx) >= Math.abs(dy) * 1.5f) axis = Axis.HORIZONTAL;
        else axis = Axis.BLOCKED;
    }
    public boolean horizontal() { return axis == Axis.HORIZONTAL; }
    public boolean vertical() { return axis == Axis.VERTICAL; }
    public boolean tracking() { return axis != Axis.BLOCKED; }
    public float verticalDistance() { return dy; }
    public float horizontalDistance() { return dx; }
    public int destination(int page, int count) {
        if (!horizontal() || Math.abs(dx) < distance || Math.abs(dx) < Math.abs(dy) * 1.5f
                || page < 0 || page >= count) return page;
        int next = page + (dx < 0 ? 1 : -1);
        return next >= 0 && next < count ? next : page;
    }
    public void cancel() { axis = Axis.BLOCKED; }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
