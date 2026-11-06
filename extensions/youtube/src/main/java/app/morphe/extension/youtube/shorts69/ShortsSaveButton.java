/*
 * 69 mod: a "Save" button on the Shorts player, above YouTube's own buttons on the right side.
 * Tap = choose playlists for this Short (tick / untick), like the Save sheet of normal videos.
 */
package app.morphe.extension.youtube.shorts69;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class ShortsSaveButton {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<LinearLayout> buttonRef = new WeakReference<>(null);
    private static boolean running;

    private ShortsSaveButton() {}

    /** 69 mod: "Save to playlist" / "Download" rows in the Shorts 3-dot menu. */
    public static int addFlyoutButtons(Object panel, int index, String videoId, boolean shortFlyout) {
        try {
            if (!shortFlyout && !ShortsPlayerState.isOpen()) return index;
            if (Settings.SHORTS_SAVE_BUTTON.get()) {
                Drawable d = icon(new String[]{"yt_outline_experimental_bookmark_vd_theme_24", "yt_outline_bookmark_black_24"});
                index = app.morphe.extension.youtube.patches.utils.FlyoutUtils.addFlyoutButton(panel, d, "Save to playlist", v -> {
                    app.morphe.extension.youtube.patches.utils.FlyoutUtils.dismissFlyout();
                    Activity a = Utils.getActivity();
                    if (a != null) ShortsSaveDialog.show(a, videoId);
                }, index);
            }
            if (Settings.SHORTS_DOWNLOAD_BUTTON.get()) {
                Drawable d = icon(new String[]{"yt_outline_experimental_download_vd_theme_24", "yt_outline_download_black_24"});
                index = app.morphe.extension.youtube.patches.utils.FlyoutUtils.addFlyoutButton(panel, d, "Download", v -> {
                    app.morphe.extension.youtube.patches.utils.FlyoutUtils.dismissFlyout();
                    app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference
                            .launchExternalDownloader(videoId, v.getContext(), "https://youtube.com/shorts/" + videoId);
                }, index);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "ShortsSaveButton flyout failure", ex);
        }
        return index;
    }

    private static Drawable icon(String[] names) {
        for (String n : names) {
            Drawable d = ResourceUtils.getDrawable(n);
            if (d != null) return d;
        }
        return null;
    }

    /** Called when the Shorts player opens or closes. */
    public static void onShortsOpen(boolean open) {
        try {
            if (!Settings.SHORTS_SAVE_BUTTON.get() && !Settings.SHORTS_DOWNLOAD_BUTTON.get()
                    && !Settings.SHORTS_SKIP_GHOST.get() && !Settings.SHORTS_SKIP_BLOCKED.get()) return;
            ghostReset();
            Utils.runOnMainThread(() -> {
                if (open) {
                    if (!running) {
                        running = true;
                        handler.post(tick);
                    }
                } else {
                    running = false;
                    LinearLayout b = buttonRef.get();
                    if (b != null) b.setVisibility(View.GONE);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "ShortsSaveButton failure", ex);
        }
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                place();
            } catch (Exception ex) {
                Logger.printException(() -> "ShortsSaveButton place failure", ex);
            }
            handler.postDelayed(this, 400);
        }
    };

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static LinearLayout item(Activity a, String label, String[] icons, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(a);
        Drawable d = null;
        for (String name : icons) {
            d = ResourceUtils.getDrawable(name);
            if (d != null) break;
        }
        if (d != null) {
            d = d.mutate();
            d.setTint(Color.WHITE);
            icon.setImageDrawable(d);
        }
        int s = dp(a, 30);
        icon.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        TextView text = new TextView(a);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setShadowLayer(4, 0, 1, 0xAA000000);
        b.addView(icon);
        b.addView(text);
        b.setPadding(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6));
        b.setOnClickListener(click);
        return b;
    }

    /** Download (external downloader) and Save buttons, stacked. */
    private static LinearLayout button(Activity a) {
        LinearLayout b = buttonRef.get();
        if (b != null && b.getContext() == a && b.getParent() != null) return b;
        FrameLayout content = a.findViewById(android.R.id.content);
        if (content == null) return null;
        b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        if (Settings.SHORTS_DOWNLOAD_BUTTON.get()) {
            b.addView(item(a, "Download", new String[]{
                    "yt_outline_experimental_download_vd_theme_24", "yt_outline_download_black_24"
            }, v -> {
                String id = VideoInformation.getVideoId();
                if (id == null || id.isEmpty()) return;
                app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference
                        .launchExternalDownloader(id, v.getContext(), "https://youtube.com/shorts/" + id);
            }));
        }
        if (Settings.SHORTS_SAVE_BUTTON.get()) {
            b.addView(item(a, "Save", new String[]{
                    "yt_outline_experimental_bookmark_vd_theme_24", "yt_outline_bookmark_black_24"
            }, v -> ShortsSaveDialog.show(a, VideoInformation.getVideoId())));
        }
        b.setVisibility(View.GONE);
        content.addView(b, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonRef = new WeakReference<>(b);
        return b;
    }

    private static WeakReference<View> barRef = new WeakReference<>(null);

    // ---- 69 mod: skip "ghost" Shorts (no buttons) and Shorts of blocked channels ----
    private static boolean barSeen;
    private static long missingSince;
    private static String skippedId = "";

    private static void ghostReset() {
        barSeen = false;
        missingSince = 0;
    }

    private static void skip(String why) {
        String id = VideoInformation.getVideoId();
        if (id != null && id.equals(skippedId)) return;
        skippedId = id == null ? "" : id;
        Logger.printDebug(() -> "Shorts skip: " + why);
        app.morphe.extension.youtube.gestures.ShortsSkip.next();
    }

    /** Called when a new video's channel is known (also for Shorts). */
    public static void onChannel(String cid) {
        if (!Settings.SHORTS_SKIP_BLOCKED.get() || !ShortsPlayerState.isOpen()) return;
        if (!app.morphe.extension.youtube.blocklist.BlockList.isPatchIncluded()) return;
        String name = VideoInformation.getChannelName();
        String v = VideoInformation.getVideoId();
        boolean blocked = app.morphe.extension.youtube.blocklist.BlockList.isBlocked(
                app.morphe.extension.youtube.blocklist.BlockListSync.CHANNELS, name, cid)
                || app.morphe.extension.youtube.blocklist.BlockList.isBlocked(
                app.morphe.extension.youtube.blocklist.BlockListSync.SHORTS, name, cid);
        if (!blocked) return;
        // wait a moment: the Shorts pager may still be settling on this page
        handler.postDelayed(() -> {
            if (!ShortsPlayerState.isOpen()) return;
            String now = VideoInformation.getVideoId();
            if (v != null && !v.equals(now)) return;
            if (!cid.equals(VideoInformation.getChannelId())) return;
            skip("blocked channel " + name);
        }, 450);
    }

    private static boolean listening;

    private static void listen() {
        if (listening) return;
        listening = true;
        VideoInformation.onChannelIdChange.addObserver((String cid) -> {
            Utils.runOnMainThread(() -> onChannel(cid));
            return kotlin.Unit.INSTANCE;
        });
    }

    private static void checkGhost(@androidx.annotation.Nullable View bar) {
        if (!Settings.SHORTS_SKIP_GHOST.get()) return;
        long now = System.currentTimeMillis();
        if (bar != null && bar.getHeight() > 0) {
            barSeen = true; // proves the buttons can be found on this phone
            missingSince = 0;
            return;
        }
        if (!barSeen) return;
        if (!app.morphe.extension.youtube.shared.EngagementPanel.getCurrentOpenedPanels().isEmpty()) {
            missingSince = 0; // comments/description open
            return;
        }
        if (missingSince == 0) {
            missingSince = now;
        } else if (now - missingSince > 1600) {
            missingSince = 0;
            skip("no buttons");
        }
    }

    /** Puts the button just above YouTube's own right-side Shorts buttons of the Short on screen. */
    private static void place() {
        Activity a = Utils.getActivity();
        if (a == null || !ShortsPlayerState.isOpen()) return;
        listen();
        // 69 mod: reuse the bar found last time while it is still on screen (walking the whole
        // screen every tick made the app slow)
        View bar = barRef.get();
        Rect r = new Rect();
        int h = a.getResources().getDisplayMetrics().heightPixels;
        if (bar == null || !bar.isShown() || !bar.getGlobalVisibleRect(r) || r.top < 0 || r.top >= h) {
            int barId = ResourceUtils.getIdentifier(ResourceType.ID, "reel_right_dyn_bar");
            bar = barId == 0 ? null : findOnScreen(a.getWindow().getDecorView(), barId, r, 0);
            barRef = new WeakReference<>(bar);
        }
        checkGhost(bar);
        if (!Settings.SHORTS_SAVE_BUTTON.get() && !Settings.SHORTS_DOWNLOAD_BUTTON.get()) return;
        LinearLayout b = button(a);
        if (b == null) return;
        View parent = (View) b.getParent();
        if (b.getVisibility() != View.VISIBLE) b.setVisibility(View.VISIBLE);
        b.bringToFront();
        float x, y;
        if (bar == null || bar.getHeight() == 0) {
            // YouTube's own bar was not found: right side, a bit above the middle
            x = parent.getWidth() - b.getWidth() - dp(a, 6);
            y = parent.getHeight() * 0.38f - b.getHeight() / 2f;
        } else {
            int[] barLoc = new int[2];
            bar.getLocationInWindow(barLoc);
            int[] parentLoc = new int[2];
            parent.getLocationInWindow(parentLoc);
            x = barLoc[0] - parentLoc[0] + (bar.getWidth() - b.getWidth()) / 2f;
            y = barLoc[1] - parentLoc[1] - b.getHeight() - dp(a, 4);
        }
        if (Math.abs(b.getX() - x) > 1) b.setX(x);
        if (Math.abs(b.getY() - y) > 1) b.setY(y);
    }

    /** The visible copy of [id] that is on screen (the Shorts pager keeps other pages around). */
    private static View findOnScreen(View v, int id, Rect r, int depth) {
        if (depth > 60 || v.getVisibility() != View.VISIBLE) return null;
        if (v.getId() == id && v.isShown() && v.getGlobalVisibleRect(r) && r.height() > 0) {
            int h = v.getResources().getDisplayMetrics().heightPixels;
            if (r.top >= 0 && r.top < h) return v;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View f = findOnScreen(g.getChildAt(i), id, r, depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }
}
