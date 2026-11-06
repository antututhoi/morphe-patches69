/*
 * 69 mod: a thin progress bar on the very bottom edge in fullscreen (like outside fullscreen).
 * Hidden while YouTube's own player controls are showing (they have their own bar).
 * Drag it to seek.
 */
package app.morphe.extension.youtube.patches;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerControlsVisibility;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class FullscreenProgressBar {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final long UPDATE_MS = 250;
    private static Bar bar;
    private static boolean running;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /** Draws the bar at the bottom of a taller (invisible) touch zone. */
    @SuppressLint("ViewConstructor")
    private static final class Bar extends View {
        final Paint track = new Paint(), played = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        float fraction;
        boolean dragging;

        Bar(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            track.setColor(Color.argb(90, 255, 255, 255));
            played.setColor(Color.parseColor("#FF0033"));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            // same thickness as YouTube's own bar; thicker with a dot while dragging
            float t = (dragging ? 6 : 4) * d;
            c.drawRect(0, h - t, w, h, track);
            c.drawRect(0, h - t, w * fraction, h, played);
            if (dragging) c.drawCircle(w * fraction, h - t / 2, 7 * d, played);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float f = Math.max(0, Math.min(1, e.getX() / Math.max(1, getWidth())));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    fraction = f;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    fraction = f;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    fraction = f;
                    dragging = false;
                    long length = VideoInformation.getVideoLength();
                    if (length > 0) VideoInformation.seekTo((long) (length * f));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    /** Injection point. Called when the main activity is created. */
    public static void initialize(Activity activity) {
        try {
            if (!isPatchIncluded() || !Settings.FULLSCREEN_PROGRESS_BAR.get()) return;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            float density = activity.getResources().getDisplayMetrics().density;
            Bar b = new Bar(activity);
            b.setVisibility(View.GONE);
            // 20dp touch zone; the bar itself is drawn on its bottom edge = the screen's bottom edge
            decor.addView(b, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (20 * density), Gravity.BOTTOM));
            bar = b;
            PlayerType.getOnChange().addObserver(type -> {
                handler.post(FullscreenProgressBar::update);
                return kotlin.Unit.INSTANCE;
            });
            PlayerControlsVisibility.getOnChange().addObserver(v -> {
                handler.post(FullscreenProgressBar::update);
                return kotlin.Unit.INSTANCE;
            });
            update();
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private static boolean controlsShowing() {
        PlayerControlsVisibility v = PlayerControlsVisibility.getCurrent();
        return v == PlayerControlsVisibility.PLAYER_CONTROLS_VISIBILITY_SHOWN
                || v == PlayerControlsVisibility.PLAYER_CONTROLS_VISIBILITY_WILL_SHOW;
    }

    private static void update() {
        Bar b = bar;
        if (b == null) return;
        boolean show = Settings.FULLSCREEN_PROGRESS_BAR.get()
                && PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                && (b.dragging || !controlsShowing());
        if ((b.getVisibility() == View.VISIBLE) != show) b.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        b.bringToFront();
        if (!b.dragging) {
            long length = VideoInformation.getVideoLength();
            long time = VideoInformation.getVideoTime();
            float f = length > 0 ? Math.min(1f, time / (float) length) : 0;
            if (Math.abs(f - b.fraction) > 0.0005f) {
                b.fraction = f;
                b.invalidate();
            }
        }
        if (!running) {
            running = true;
            handler.postDelayed(FullscreenProgressBar::tick, UPDATE_MS);
        }
    }

    private static void tick() {
        running = false;
        update();
    }
}
