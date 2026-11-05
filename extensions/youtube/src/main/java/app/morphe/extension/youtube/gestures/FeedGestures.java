/*
 * Long-press a feed item (video, post) to open its 3-dot menu.
 * Swipe a feed item left to hide that video, right to block its channel.
 * Gets every touch of Litho views through the ComponentHost.dispatchTouchEvent hook.
 */
package app.morphe.extension.youtube.gestures;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v7.widget.RecyclerView;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.blocklist.BlockList;
import app.morphe.extension.youtube.blocklist.BlockListFileSync;
import app.morphe.extension.youtube.blocklist.BlockListSync;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class FeedGestures {

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static long lastEventTime = -1;
    private static int lastAction = -1;

    private static WeakReference<View> itemRef = new WeakReference<>(null);
    private static float downX, downY;
    private static long downTime;
    private static boolean longPressDone;
    private static boolean sendingCancel;
    private static Runnable longPress;
    // 69 mod: where the item was on screen at the touch, to tell a hold from a scroll
    private static int downItemY;
    private static boolean swipeAllowed;
    private static boolean swiping;

    /** Texts of the item touched last; used by the post image download. */
    private static volatile List<String> lastTouchedTexts = java.util.Collections.emptyList();

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    public static List<String> getLastTouchedTexts() {
        return lastTouchedTexts;
    }

    /** Injection point: ComponentHost.dispatchTouchEvent, first instruction. */
    public static void onComponentHostTouch(ComponentHost host, MotionEvent ev) {
        try {
            if (!isPatchIncluded() || sendingCancel) return;
            // Every host in the chain gets the same event; handle it once (outermost first).
            final int action = ev.getActionMasked();
            if (ev.getEventTime() == lastEventTime && action == lastAction) return;
            lastEventTime = ev.getEventTime();
            lastAction = action;

            switch (action) {
                case MotionEvent.ACTION_DOWN: onDown(host, ev); break;
                case MotionEvent.ACTION_MOVE: onMove(ev); break;
                case MotionEvent.ACTION_UP: onUp(ev); break;
                case MotionEvent.ACTION_CANCEL:
                    cancelLongPress();
                    resetSwipe(itemRef.get());
                    swiping = false;
                    itemRef = new WeakReference<>(null);
                    break;
                default: break;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostTouch failure", ex);
        }
    }

    private static boolean playerCoversFeed() {
        return PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static void onDown(ComponentHost host, MotionEvent ev) {
        cancelLongPress();
        longPressDone = false;
        View item = FeedItems.findItemRoot(host);
        itemRef = new WeakReference<>(item);
        if (item == null) return;
        lastTouchedTexts = FeedItems.texts(item);
        RecyclerView rv = FeedItems.parentList(item);
        // Not for full-screen pages such as the Shorts player, nor for comments under a video.
        if (rv == null || item.getHeight() > rv.getHeight() * 0.8f || playerCoversFeed()) {
            itemRef = new WeakReference<>(null);
            return;
        }
        downX = ev.getRawX();
        downY = ev.getRawY();
        downTime = ev.getEventTime();
        downItemY = screenY(item);
        swiping = false;
        // Swipe only from the outer part of the item, if that is set.
        int edge = parseInt(Settings.FEED_SWIPE_EDGE.get(), 0);
        if (edge <= 0) {
            swipeAllowed = true;
        } else {
            int[] loc = new int[2];
            item.getLocationOnScreen(loc);
            float xIn = ev.getRawX() - loc[0];
            float zone = item.getWidth() * edge / 100f;
            swipeAllowed = xIn <= zone || xIn >= item.getWidth() - zone;
        }
        if (Settings.FEED_LONG_PRESS_MENU.get()) {
            longPress = () -> {
                View i = itemRef.get();
                if (i == null) return;
                // A scroll moves the item even when the finger events stop reaching us.
                int slop = ViewConfiguration.get(Utils.getContext()).getScaledTouchSlop();
                if (Math.abs(screenY(i) - downItemY) > slop) return;
                longPressDone = true;
                i.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                openMenu(i);
            };
            handler.postDelayed(longPress, Math.max(300, parseInt(Settings.FEED_LONG_PRESS_TIME.get(), 600)));
        }
    }

    private static void onMove(MotionEvent ev) {
        View item = itemRef.get();
        if (item == null) return;
        int slop = ViewConfiguration.get(Utils.getContext()).getScaledTouchSlop();
        float dx = ev.getRawX() - downX;
        float dy = ev.getRawY() - downY;
        if (Math.abs(dx) > slop || Math.abs(dy) > slop) cancelLongPress();
        // Show the swipe: the item follows the finger and fades once far enough.
        if (Settings.FEED_SWIPE_GESTURES.get() && swipeAllowed && !longPressDone
                && (swiping || (Math.abs(dx) > slop * 2 && Math.abs(dy) < Math.abs(dx) * 0.5f))) {
            String action = dx < 0 ? Settings.FEED_SWIPE_LEFT_ACTION.get() : Settings.FEED_SWIPE_RIGHT_ACTION.get();
            if ("none".equals(action)) return;
            swiping = true;
            item.setTranslationX(dx * 0.6f);
            item.setAlpha(Math.abs(dx) >= minDistance(item) ? 0.45f : 1f - Math.min(0.4f, Math.abs(dx) / (item.getWidth() * 2f)));
        }
    }

    private static float minDistance(View item) {
        return Math.max(item.getWidth() * 0.3f, 120 * item.getResources().getDisplayMetrics().density);
    }

    private static void resetSwipe(View item) {
        if (item == null) return;
        item.animate().translationX(0f).alpha(1f).setDuration(150).start();
    }

    private static int screenY(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[1];
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void onUp(MotionEvent ev) {
        cancelLongPress();
        View item = itemRef.get();
        itemRef = new WeakReference<>(null);
        if (item == null) return;
        boolean wasSwiping = swiping;
        swiping = false;
        resetSwipe(item);
        if (longPressDone || !Settings.FEED_SWIPE_GESTURES.get() || !swipeAllowed) return;
        float dx = ev.getRawX() - downX;
        float dy = ev.getRawY() - downY;
        if (Math.abs(dx) < minDistance(item) || Math.abs(dy) > Math.abs(dx) * 0.5f
                || (!wasSwiping && ev.getEventTime() - downTime > 1000)) {
            return;
        }
        String action = dx < 0 ? Settings.FEED_SWIPE_LEFT_ACTION.get() : Settings.FEED_SWIPE_RIGHT_ACTION.get();
        switch (action) {
            case "hide": hideVideo(item); break;
            case "block": blockChannel(item); break;
            case "menu": openMenu(item); break;
            default: break;
        }
    }

    private static void cancelLongPress() {
        if (longPress != null) handler.removeCallbacks(longPress);
        longPress = null;
    }

    // ---------------- actions ----------------

    /** Clicks the item's 3-dot button: the right-most small clickable view in the item. */
    private static void openMenu(View item) {
        View button = findMenuButton(item);
        // Cancel the press so lifting the finger does not open the video.
        sendCancel(item);
        if (button == null) {
            Utils.showToastShort("3-dot button not found");
            return;
        }
        button.performClick();
    }

    @Nullable
    private static View findMenuButton(View item) {
        float density = item.getResources().getDisplayMetrics().density;
        int maxSize = (int) (72 * density);
        int[] itemLoc = new int[2];
        item.getLocationOnScreen(itemLoc);
        int rightZone = itemLoc[0] + (int) (item.getWidth() * 0.7f);
        View[] best = new View[1];
        int[] bestRight = {Integer.MIN_VALUE};
        search(item, item, maxSize, rightZone, best, bestRight, 0);
        return best[0];
    }

    private static void search(View root, View v, int maxSize, int rightZone, View[] best, int[] bestRight, int depth) {
        if (depth > 40 || v.getVisibility() != View.VISIBLE) return;
        if (v != root && v.hasOnClickListeners() && v.getWidth() > 0
                && v.getWidth() <= maxSize && v.getHeight() <= maxSize) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int right = loc[0] + v.getWidth();
            if (loc[0] >= rightZone && right >= bestRight[0]) {
                best[0] = v;
                bestRight[0] = right;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                search(root, g.getChildAt(i), maxSize, rightZone, best, bestRight, depth + 1);
            }
        }
    }

    private static void sendCancel(View item) {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        sendingCancel = true;
        try {
            item.dispatchTouchEvent(cancel);
        } finally {
            sendingCancel = false;
            cancel.recycle();
        }
    }

    private static void hideVideo(View item) {
        List<String> texts = FeedItems.texts(item);
        String channel = FeedItems.channelName(texts);
        String title = FeedItems.title(texts, channel);
        if (title == null) {
            Utils.showToastShort("Could not read the video title");
            return;
        }
        BlockList.hideVideoTitle(title);
        FeedItems.collapse(item);
        Utils.showToastShort("Video hidden");
    }

    private static void blockChannel(View item) {
        List<String> texts = FeedItems.texts(item);
        String channel = FeedItems.channelName(texts);
        if (channel == null || channel.isEmpty()) {
            Utils.showToastShort("Could not read the channel name");
            return;
        }
        BlockList.add(BlockListSync.CHANNELS, channel, null);
        BlockListFileSync.syncSoon();
        FeedItems.collapse(item);
        FeedItems.hideChannelOnScreen(channel, false, java.util.Collections.emptyList());
        Utils.showToastShort("Blocked " + channel);
    }
}
