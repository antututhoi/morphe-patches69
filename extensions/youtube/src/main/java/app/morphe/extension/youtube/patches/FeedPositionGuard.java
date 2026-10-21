/*
 * Keeps the feed where it was when YouTube is left (other app, screen off, picture-in-picture)
 * and opened again. YouTube reloads the home feed and it jumps to the top. This remembers the
 * scroll offset while the feed is visible and puts it back if the feed was found at the top
 * shortly after returning. Works only with View APIs, so it needs no obfuscated names.
 */
package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings({"unused", "deprecation"})
public final class FeedPositionGuard {

    private static final long SAMPLE_INTERVAL_MS = 500;
    private static final long RESTORE_POLL_MS = 120;
    private static final long RESTORE_WINDOW_MS = 6000;
    private static final int MIN_OFFSET_TO_RESTORE = 400;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean registered;
    private static int savedOffset;          // vertical scroll offset in px of the feed
    private static boolean resumed;
    private static long restoreEndsAt;
    private static int expectedOffset = -1;
    private static boolean restoredOnce;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /** Injection point. Called when the main activity is created. */
    public static void initialize(Activity activity) {
        try {
            if (!isPatchIncluded() || registered) return;
            registered = true;
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(@NonNull Activity a) { onResume(a); }
                public void onActivityPaused(@NonNull Activity a) { onPause(a); }
                public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
                public void onActivityStarted(@NonNull Activity a) {}
                public void onActivityStopped(@NonNull Activity a) {}
                public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
                public void onActivityDestroyed(@NonNull Activity a) {}
            });
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private static boolean enabled() {
        return Settings.KEEP_FEED_POSITION.get();
    }

    /** The feed is what the user sees only when no video player covers it. */
    private static boolean feedVisible() {
        PlayerType t = PlayerType.getCurrent();
        return t == PlayerType.NONE || t == PlayerType.HIDDEN || t == PlayerType.WATCH_WHILE_MINIMIZED
                || t == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED;
    }

    private static void onPause(Activity activity) {
        try {
            resumed = false;
            handler.removeCallbacksAndMessages(null);
            if (enabled() && feedVisible()) sample(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "onPause failure", ex);
        }
    }

    private static void onResume(Activity activity) {
        try {
            resumed = true;
            handler.removeCallbacksAndMessages(null);
            if (!enabled()) return;
            if (savedOffset >= MIN_OFFSET_TO_RESTORE) {
                restoreEndsAt = System.currentTimeMillis() + RESTORE_WINDOW_MS;
                expectedOffset = -1;
                restoredOnce = false;
                handler.postDelayed(() -> restoreStep(activity), RESTORE_POLL_MS);
            } else {
                scheduleSample(activity);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onResume failure", ex);
        }
    }

    private static void scheduleSample(Activity activity) {
        handler.postDelayed(() -> {
            if (!resumed) return;
            if (enabled() && feedVisible()) sample(activity);
            scheduleSample(activity);
        }, SAMPLE_INTERVAL_MS);
    }

    private static void sample(Activity activity) {
        RecyclerView rv = findFeed(activity);
        if (rv == null) return;
        // Only a feed that can scroll up, or that sits at the very top, is a valid reading.
        savedOffset = rv.canScrollVertically(-1) ? Math.max(rv.computeVerticalScrollOffset(), 1) : 0;
    }

    private static void restoreStep(Activity activity) {
        if (!resumed || !enabled()) return;
        try {
            long now = System.currentTimeMillis();
            RecyclerView rv = findFeed(activity);
            if (rv != null) {
                int current = rv.canScrollVertically(-1) ? rv.computeVerticalScrollOffset() : 0;
                if (expectedOffset >= 0 && Math.abs(current - expectedOffset) > 250) {
                    Logger.printDebug(() -> "User scrolled, restore cancelled");
                    resumeSampling(activity);
                    return;
                }
                int delta = savedOffset - current;
                if (current < MIN_OFFSET_TO_RESTORE && delta > MIN_OFFSET_TO_RESTORE) {
                    Logger.printDebug(() -> "Feed jumped to top, restoring offset " + savedOffset);
                    rv.scrollBy(0, delta);
                    expectedOffset = rv.computeVerticalScrollOffset();
                    restoredOnce = true;
                    restoreEndsAt = Math.max(restoreEndsAt, now + 1500); // keep watching a moment more
                } else if (current >= MIN_OFFSET_TO_RESTORE && restoredOnce) {
                    // Check again whether YouTube pushes it back to the top.
                    expectedOffset = current;
                } else if (current >= MIN_OFFSET_TO_RESTORE) {
                    resumeSampling(activity); // never jumped
                    return;
                }
            }
            if (now < restoreEndsAt) {
                handler.postDelayed(() -> restoreStep(activity), RESTORE_POLL_MS);
            } else {
                resumeSampling(activity);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "restoreStep failure", ex);
        }
    }

    private static void resumeSampling(Activity activity) {
        expectedOffset = -1;
        scheduleSample(activity);
    }

    /** The largest visible vertically scrolling RecyclerView that fills a good part of the screen. */
    @Nullable
    private static RecyclerView findFeed(Activity activity) {
        View root = activity.getWindow().getDecorView();
        int screenHeight = root.getHeight();
        RecyclerView[] best = new RecyclerView[1];
        long[] bestArea = new long[1];
        search(root, screenHeight, best, bestArea, new Rect(), 0);
        return best[0];
    }

    private static void search(View v, int screenHeight, RecyclerView[] best, long[] bestArea, Rect tmp, int depth) {
        if (v.getVisibility() != View.VISIBLE || depth > 40) return;
        if (v instanceof RecyclerView) {
            if (v.getHeight() > screenHeight / 3 && v.getGlobalVisibleRect(tmp)) {
                long area = (long) tmp.width() * tmp.height();
                if (area > bestArea[0] && (v.canScrollVertically(1) || v.canScrollVertically(-1))) {
                    bestArea[0] = area;
                    best[0] = (RecyclerView) v;
                }
            }
            return; // Do not look into feed items.
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                search(g.getChildAt(i), screenHeight, best, bestArea, tmp, depth + 1);
            }
        }
    }
}
