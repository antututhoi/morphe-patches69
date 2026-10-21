/*
 * Always shows a progress bar at the bottom of the video in fullscreen, like outside fullscreen.
 * It can be dragged to seek. Added on top of the activity, shown only in fullscreen.
 */
package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class FullscreenProgressBar {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final long UPDATE_MS = 250;
    private static SeekBar bar;
    private static boolean dragging;
    private static boolean running;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /** Injection point. Called when the main activity is created. */
    public static void initialize(Activity activity) {
        try {
            if (!isPatchIncluded() || !Settings.FULLSCREEN_PROGRESS_BAR.get()) return;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            float density = activity.getResources().getDisplayMetrics().density;

            SeekBar seekBar = new SeekBar(activity);
            seekBar.setMax(1000);
            seekBar.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
            int red = Color.parseColor("#FF0033");
            seekBar.setProgressTintList(ColorStateList.valueOf(red));
            seekBar.setThumbTintList(ColorStateList.valueOf(red));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(110, 255, 255, 255)));
            seekBar.setVisibility(View.GONE);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {}

                @Override
                public void onStartTrackingTouch(SeekBar s) { dragging = true; }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    dragging = false;
                    long length = VideoInformation.getVideoLength();
                    if (length > 0) VideoInformation.seekTo(length * s.getProgress() / 1000);
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (28 * density), Gravity.BOTTOM);
            decor.addView(seekBar, lp);
            bar = seekBar;

            app.morphe.extension.youtube.shared.PlayerType.getOnChange().addObserver(type -> {
                handler.post(FullscreenProgressBar::update);
                return kotlin.Unit.INSTANCE;
            });
            update();
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private static void update() {
        SeekBar b = bar;
        if (b == null) return;
        boolean show = Settings.FULLSCREEN_PROGRESS_BAR.get()
                && PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN;
        b.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            b.bringToFront();
            if (!dragging) {
                long length = VideoInformation.getVideoLength();
                long time = VideoInformation.getVideoTime();
                b.setProgress(length > 0 ? (int) (time * 1000 / length) : 0);
            }
            if (!running) {
                running = true;
                handler.postDelayed(FullscreenProgressBar::tick, UPDATE_MS);
            }
        }
    }

    private static void tick() {
        running = false;
        update();
    }
}
