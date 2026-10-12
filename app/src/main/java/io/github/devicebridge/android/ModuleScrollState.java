package io.github.devicebridge.android;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.List;

/** Rotation snapshot with primitive positions only. Never retains a View or Activity. */
final class ModuleScrollState {
    private static final class Position {
        final String path; final boolean list; final int x, y;
        Position(String path, boolean list, int x, int y) { this.path = path; this.list = list; this.x = x; this.y = y; }
    }
    private final List<Position> positions = new ArrayList<>();
    static ModuleScrollState capture(View root) {
        ModuleScrollState state = new ModuleScrollState(); state.capture(root, ""); return state;
    }
    private void capture(View view, String path) {
        if (view instanceof AbsListView) {
            AbsListView list = (AbsListView) view; View first = list.getChildAt(0);
            positions.add(new Position(path, true, list.getFirstVisiblePosition(), first == null ? 0 : first.getTop()));
            return; // Row children are adapter-owned and do not have stable structural paths.
        }
        if (view instanceof ScrollView) positions.add(new Position(path, false, view.getScrollX(), view.getScrollY()));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) capture(group.getChildAt(i), path + "/" + i);
        }
    }
    void restore(View root) {
        for (Position position : positions) {
            View view = locate(root, position.path);
            if (position.list && view instanceof ListView) ((ListView) view).setSelectionFromTop(position.x, position.y);
            else if (!position.list && view instanceof ScrollView) view.scrollTo(position.x, position.y);
        }
    }
    private static View locate(View root, String path) {
        View view = root;
        for (String element : path.split("/")) if (!element.isEmpty()) {
            if (!(view instanceof ViewGroup)) return null;
            int index = Integer.parseInt(element); ViewGroup group = (ViewGroup) view;
            if (index >= group.getChildCount()) return null; view = group.getChildAt(index);
        }
        return view;
    }
}
