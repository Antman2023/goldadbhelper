package io.github.devicebridge.android;

import android.content.Context;
import android.view.*;
import android.widget.*;
import io.github.devicebridge.core.ModuleSwipePolicy;

/** Folds the icon strip and owns a horizontal stream only after its axis is unambiguous. */
final class CollapsingRoot extends LinearLayout {
    interface Modules {
        boolean enabled(); int current(); int count(); long revision();
        void drag(float distance); void release(int page); void cancel();
    }
    private final ModuleSwipePolicy gesture;
    private View tabs, content;
    private Modules modules;
    private int page;
    private long revision;
    private boolean adjusted, owned, disallowed;
    CollapsingRoot(Context context) {
        super(context); setOrientation(VERTICAL);
        gesture = new ModuleSwipePolicy(ViewConfiguration.get(context).getScaledTouchSlop(),
                Ui.dp(context, 64), ViewConfiguration.getLongPressTimeout());
    }
    void tabs(View view) { tabs = view; }
    void modules(View view, Modules listener) { reset(); content = view; modules = listener; }
    private boolean enabled() { return modules != null && hasWindowFocus() && isShown() && modules.enabled(); }
    private boolean currentStream() { return enabled() && modules.current() == page && modules.revision() == revision; }
    private void reset() { finish(); if (modules != null) modules.cancel(); }
    private void finish() { gesture.cancel(); owned = false; adjusted = false; disallowed = false; }
    /** Observe before dispatch: ScrollView may suppress interception before the fold threshold. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (modules != null) { page = modules.current(); revision = modules.revision(); }
            boolean safe = enabled() && contains(content, event.getX(), event.getY())
                    && !sensitive(findFocus()) && !protectedAt(this, event.getX(), event.getY());
            gesture.begin(event.getX(), event.getY(), event.getEventTime(), event.getPointerCount(), safe);
        } else if (action == MotionEvent.ACTION_MOVE) {
            move(event);
            if (gesture.vertical() && tabs != null && !adjusted
                    && Math.abs(gesture.verticalDistance()) > Ui.dp(getContext(), 22)) {
                tabs.setVisibility(gesture.verticalDistance() < 0 ? GONE : VISIBLE); adjusted = true;
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) gesture.cancel();
        try {
            // Never consume or rewrite a child's vertical stream, even while folding the tabs.
            return super.dispatchTouchEvent(event);
        } finally {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                if (owned) reset(); else finish();
            }
        }
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !disallowed && gesture.horizontal()) {
            owned = true; modules.drag(gesture.horizontalDistance()); return true;
        }
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (!owned) {
            if (action == MotionEvent.ACTION_DOWN) return gesture.tracking() || super.onTouchEvent(event);
            if (action == MotionEvent.ACTION_MOVE) {
                onInterceptTouchEvent(event);
                if (!owned) return gesture.tracking() || super.onTouchEvent(event);
            } else { reset(); return super.onTouchEvent(event); }
        }
        if (action == MotionEvent.ACTION_MOVE) {
            move(event);
            if (gesture.horizontal() && currentStream()) modules.drag(gesture.horizontalDistance()); else reset();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) reset();
        else if (action == MotionEvent.ACTION_UP) {
            move(event); int next = gesture.destination(page, modules.count());
            boolean valid = gesture.horizontal() && currentStream(); finish();
            if (valid) modules.release(next); else modules.cancel();
        } else if (action == MotionEvent.ACTION_CANCEL) reset();
        return true;
    }
    private void move(MotionEvent event) {
        gesture.move(event.getX(), event.getY(), event.getEventTime(), event.getPointerCount(),
                currentStream() && !sensitive(findFocus()));
    }
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        if (disallow) {
            // Yield horizontal ownership for this entire stream; vertical observation is passive.
            disallowed = true;
            if (owned || gesture.horizontal()) {
                gesture.cancel(); owned = false;
                if (modules != null) modules.cancel();
            }
        }
        super.requestDisallowInterceptTouchEvent(disallow);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (!focused) reset();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE && gesture != null) reset();
    }
    @Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
    private static boolean sensitive(View view) {
        return view != null && view.isShown() && (view instanceof EditText || view instanceof TextView && ((TextView) view).isTextSelectable());
    }
    private static boolean contains(View child, float x, float y) {
        return child != null && child.getVisibility() == VISIBLE && x >= child.getLeft()
                && x < child.getRight() && y >= child.getTop() && y < child.getBottom();
    }
    private boolean protectedAt(View view, float x, float y) {
        if (view == tabs || sensitive(view) || view instanceof HorizontalScrollView
                || view instanceof SeekBar || view instanceof CompoundButton
                || view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            float localX = x + group.getScrollX(), localY = y + group.getScrollY();
            if (contains(child, localX, localY))
                return protectedAt(child, localX - child.getLeft(), localY - child.getTop());
        }
        return false;
    }
}
