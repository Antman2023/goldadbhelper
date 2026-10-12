package io.github.devicebridge.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Small API19 pager. Adjacent real views are prepared; only a committed page is activated. */
final class RetainedModulePager extends FrameLayout {
    interface Pages {
        View view(int index);
        boolean canNavigate();
        void committed(int index);
    }
    private final int count;
    private final Pages pages;
    private int selected = -1, preview = -1;
    private float offset;
    private ValueAnimator animator;
    private boolean disposed;

    RetainedModulePager(Context context, int count, Pages pages) {
        super(context); this.count = count; this.pages = pages;
        setClipChildren(true); setClipToPadding(true);
        setFocusable(true); setFocusableInTouchMode(true);
    }
    boolean settling() { return animator != null; }
    int selected() { return selected; }
    private View attach(int index) {
        View view = pages.view(index);
        if (view.getParent() == null) addView(view, new FrameLayout.LayoutParams(-1, -1));
        return view;
    }
    void show(int index) {
        cancelMotion(); selected = index; attach(index); normalize();
    }
    void replace(int index) {
        cancelMotion();
        // The owner replaced its slot; remove detached lifetime views before attaching its new view.
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getTag() instanceof Integer && ((Integer) child.getTag()).intValue() == index) removeViewAt(i);
        }
        if (selected == index) { attach(index); normalize(); }
    }
    void select(int index, boolean animated) {
        if (disposed || index < 0 || index >= count || !pages.canNavigate()) return;
        cancelMotion();
        if (selected < 0 || !animated || getWidth() == 0) {
            selected = index; attach(index); normalize(); pages.committed(index); return;
        }
        if (index == selected) return;
        preview = index; attach(index); apply(0);
        settle(index);
    }
    void drag(float distance) {
        if (disposed || selected < 0 || settling() || !pages.canNavigate()) { cancelMotion(); return; }
        int next = selected + (distance < 0 ? 1 : -1);
        preview = distance == 0 || next < 0 || next >= count ? -1 : next;
        if (preview >= 0) attach(preview);
        float width = Math.max(1, getWidth());
        apply(Math.max(-width, Math.min(width, preview < 0 ? distance * .22f : distance)));
    }
    void release(int index) {
        if (disposed || selected < 0 || !pages.canNavigate()) { cancelMotion(); return; }
        if (index != selected && (index != preview || Math.abs(index - selected) != 1)) index = selected;
        settle(index);
    }
    private void settle(final int destination) {
        if (getWidth() == 0) { cancelMotion(); return; }
        final int origin = selected;
        float end = destination == origin ? 0 : (destination > origin ? -getWidth() : getWidth());
        final ValueAnimator animation = ValueAnimator.ofFloat(offset, end);
        animator = animation;
        animation.setDuration(Math.max(100, Math.min(260, (long) (260 * Math.abs(end - offset) / getWidth()))));
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> {
            if (animator != animation) return;
            if (!pages.canNavigate()) { cancelMotion(); return; }
            apply((Float) value.getAnimatedValue());
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ignored) {
                if (animator != animation) return;
                animator = null;
                if (disposed || selected != origin || !pages.canNavigate()) { normalize(); return; }
                selected = destination; normalize();
                if (destination != origin) pages.committed(destination);
            }
        });
        animation.start();
    }
    private void apply(float value) {
        offset = value;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int index = ((Integer) child.getTag()).intValue();
            boolean current = index == selected, neighbor = index == preview;
            child.setVisibility(current || neighbor ? VISIBLE : GONE);
            child.setImportantForAccessibility(current ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            child.setTranslationX(current ? offset : neighbor ? offset + (preview > selected ? getWidth() : -getWidth()) : 0);
        }
    }
    private void normalize() {
        preview = -1;
        if (!disposed && selected >= 0) {
            if (selected > 0) attach(selected - 1);
            if (selected + 1 < count) attach(selected + 1);
        }
        apply(0);
    }
    void cancelMotion() {
        ValueAnimator old = animator; animator = null;
        if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
        normalize();
    }
    void dispose() { disposed = true; cancelMotion(); removeAllViews(); selected = -1; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); if (w != oldw || h != oldh) cancelMotion();
    }
    @Override protected void onDetachedFromWindow() { cancelMotion(); super.onDetachedFromWindow(); }
}
