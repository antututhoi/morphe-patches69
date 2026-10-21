/*
 * Moves the Shorts player to the next Short (used after blocking the channel of a Short).
 * The Shorts player is a vertical list where each page fills the list.
 */
package app.morphe.extension.youtube.gestures;

import android.app.Activity;
import android.graphics.Rect;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class ShortsSkip {

    private ShortsSkip() {}

    public static void next() {
        try {
            Activity a = Utils.getActivity();
            if (a == null) return;
            RecyclerView pager = findPager(a.getWindow().getDecorView(), new Rect(), 0);
            if (pager == null) {
                Logger.printDebug(() -> "Shorts pager not found");
                return;
            }
            pager.scrollBy(0, pager.getHeight());
        } catch (Exception ex) {
            Logger.printException(() -> "ShortsSkip failure", ex);
        }
    }

    /** A visible vertical list whose first child fills it. */
    private static RecyclerView findPager(View v, Rect tmp, int depth) {
        if (depth > 40 || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof RecyclerView) {
            RecyclerView rv = (RecyclerView) v;
            if (rv.getChildCount() > 0 && rv.getGlobalVisibleRect(tmp)
                    && rv.getChildAt(0).getHeight() >= rv.getHeight() * 0.9f
                    && (rv.canScrollVertically(1) || rv.canScrollVertically(-1))) {
                return rv;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                RecyclerView r = findPager(g.getChildAt(i), tmp, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }
}
