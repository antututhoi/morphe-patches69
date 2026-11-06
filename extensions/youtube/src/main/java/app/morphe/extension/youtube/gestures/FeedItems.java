/*
 * Helpers to read and hide feed items on screen. Feed items are Litho views
 * (ComponentHost) that are direct children of the feed RecyclerView.
 */
package app.morphe.extension.youtube.gestures;

import android.app.Activity;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.FeedPositionGuard;

public final class FeedItems {

    private FeedItems() {}

    /** @return the item view (child of a RecyclerView) that contains the view, or null. */
    @Nullable
    public static View findItemRoot(View view) {
        View v = view;
        for (int i = 0; i < 60 && v != null; i++) {
            ViewParent p = v.getParent();
            if (p instanceof RecyclerView) return v;
            if (!(p instanceof View)) return null;
            v = (View) p;
        }
        return null;
    }

    @Nullable
    public static RecyclerView parentList(View item) {
        ViewParent p = item.getParent();
        return p instanceof RecyclerView ? (RecyclerView) p : null;
    }

    /** All texts drawn inside the item, in drawing order. */
    public static List<String> texts(View item) {
        List<String> out = new ArrayList<>();
        collect(item, out, 0);
        return out;
    }

    private static void collect(View v, List<String> out, int depth) {
        if (depth > 40) return;
        try {
            if (v instanceof ComponentHost) {
                TextContent tc = ((ComponentHost) v).getTextContent();
                if (tc != null) {
                    for (CharSequence cs : tc.getTextItems()) {
                        if (cs != null && cs.toString().trim().length() > 0) out.add(cs.toString().trim());
                    }
                }
            } else if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.toString().trim().length() > 0) out.add(cs.toString().trim());
            }
            CharSequence desc = v.getContentDescription();
            if (desc != null && desc.length() > 0) out.add(desc.toString().trim());
        } catch (Exception ex) {
            Logger.printInfo(() -> "text read failed", ex);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collect(g.getChildAt(i), out, depth + 1);
        }
    }

    private static final String[] SEPARATORS = {" • ", " · ", " ・ ", " | "};

    /** Channel name of a feed video item: the part before the first separator of the subtitle line. */
    @Nullable
    public static String channelName(List<String> texts) {
        for (String t : texts) {
            for (String sep : SEPARATORS) {
                int i = t.indexOf(sep);
                if (i > 0 && i < 80) return t.substring(0, i).trim();
            }
        }
        return null;
    }

    /** Title of a feed video item: the longest text that is not a subtitle line or accessibility text. */
    @Nullable
    public static String title(List<String> texts, @Nullable String channel) {
        String best = null;
        for (String t : texts) {
            if (t.length() > 300) continue; // accessibility descriptions
            boolean subtitle = false;
            for (String sep : SEPARATORS) if (t.contains(sep)) subtitle = true;
            if (subtitle || t.equals(channel) || t.matches("[0-9:]+")) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    // ---------------- hiding right away ----------------

    private static final int TAG_KEY = 0x69690001;

    /** Collapses the item until its view is recycled for another item. */
    public static void collapse(View item) {
        if (item.getTag(TAG_KEY) != null) return;
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp == null) return;
        final int oldHeight = lp.height;
        final int oldVisibility = item.getVisibility();
        item.setTag(TAG_KEY, Boolean.TRUE);
        item.setVisibility(View.INVISIBLE);
        lp.height = 1;
        item.setLayoutParams(lp);
        item.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                // Recycled: give the view back its size for the next item.
                v.removeOnAttachStateChangeListener(this);
                ViewGroup.LayoutParams p = v.getLayoutParams();
                if (p != null) {
                    p.height = oldHeight;
                    v.setLayoutParams(p);
                }
                v.setVisibility(oldVisibility);
                v.setTag(TAG_KEY, null);
            }
        });
    }

    public interface ItemTest {
        boolean test(List<String> texts);
    }

    /** Collapses every item on screen whose texts pass the test. */
    public static int hideOnScreen(ItemTest test) {
        int hidden = 0;
        try {
            Activity a = Utils.getActivity();
            if (a == null) return 0;
            RecyclerView rv = FeedPositionGuard.findFeed(a);
            if (rv == null) return 0;
            for (int i = 0, n = rv.getChildCount(); i < n; i++) {
                View child = rv.getChildAt(i);
                if (child != null && test.test(texts(child))) {
                    collapse(child);
                    hidden++;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideOnScreen failure", ex);
        }
        return hidden;
    }

    /** Collapses items on screen that show this channel name. */
    public static int hideChannelOnScreen(String channelName, boolean onlyLive, List<String> liveWords) {
        if (channelName == null || channelName.isEmpty()) return 0;
        final String name = channelName.toLowerCase(Locale.ROOT);
        return hideOnScreen(texts -> {
            boolean channel = false, live = !onlyLive;
            for (String t : texts) {
                String l = t.toLowerCase(Locale.ROOT);
                if (l.contains(name)) channel = true;
                if (onlyLive && !live) {
                    if (t.equals("LIVE")) live = true;
                    for (String w : liveWords) if (!w.isEmpty() && t.contains(w)) live = true;
                }
            }
            return channel && live;
        });
    }

    // ---------------- 69 mod: "Blocked X · tap to undo" placeholder ----------------

    private static final int TAG_PLACEHOLDER = 0x69690002;
    private static final java.util.Map<View, Runnable> undoOf = new java.util.WeakHashMap<>();
    private static final java.util.Set<RecyclerView> watched =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Channels blocked in this session: their items stay placeholders when scrolled back to. */
    private static final java.util.Map<String, Runnable> sessionBlocked = new java.util.LinkedHashMap<>();

    /** A dark bar with the text, drawn over the shrunken item. */
    private static final class BarDrawable extends android.graphics.drawable.Drawable {
        final android.graphics.Paint bg = new android.graphics.Paint(), fg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final String text;
        final float d;

        BarDrawable(String text, float density) {
            this.text = text;
            this.d = density;
            bg.setColor(0xFF212121);
            fg.setColor(0xFFE0E0E0);
            fg.setTextSize(14 * density);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawRect(b, bg);
            float y = b.centerY() - (fg.descent() + fg.ascent()) / 2;
            c.drawText(text, b.left + 16 * d, y, fg);
            android.graphics.Paint u = new android.graphics.Paint(fg);
            u.setColor(0xFF3EA6FF);
            u.setFakeBoldText(true);
            String undo = "UNDO";
            c.drawText(undo, b.right - 16 * d - u.measureText(undo), y, u);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** Shrinks the item to a bar "Blocked X   UNDO"; tapping it runs [onUndo] and shows the item again. */
    public static void placeholder(View item, String text, Runnable onUndo) {
        if (item.getTag(TAG_PLACEHOLDER) != null) return;
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp == null) return;
        final int oldHeight = lp.height;
        float d = item.getResources().getDisplayMetrics().density;
        BarDrawable bar = new BarDrawable(text, d);
        Runnable restore = () -> {
            item.getOverlay().remove(bar);
            ViewGroup.LayoutParams p = item.getLayoutParams();
            if (p != null) {
                p.height = oldHeight;
                item.setLayoutParams(p);
            }
            item.setTag(TAG_PLACEHOLDER, null);
            undoOf.remove(item);
        };
        item.setTag(TAG_PLACEHOLDER, restore);
        lp.height = (int) (52 * d);
        item.setLayoutParams(lp);
        item.getOverlay().add(bar);
        View.OnLayoutChangeListener sizer = (v, l, t, r, b, ol, ot, or, ob) -> bar.setBounds(0, 0, r - l, b - t);
        item.addOnLayoutChangeListener(sizer);
        bar.setBounds(0, 0, item.getWidth(), (int) (52 * d));
        undoOf.put(item, () -> {
            restore.run();
            onUndo.run();
        });
        item.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                v.removeOnLayoutChangeListener(sizer);
                if (v.getTag(TAG_PLACEHOLDER) == restore) restore.run();
            }
        });
        RecyclerView rv = parentList(item);
        if (rv != null) watch(rv);
    }

    /** Taps on a placeholder undo; items of channels blocked now become placeholders again when they come back. */
    private static void watch(RecyclerView rv) {
        if (!watched.add(rv)) return;
        rv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            float x, y;

            @Override
            public boolean onInterceptTouchEvent(RecyclerView r, android.view.MotionEvent e) {
                if (undoOf.isEmpty()) return false;
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    x = e.getX();
                    y = e.getY();
                    View c = r.findChildViewUnder(x, y);
                    return c != null && undoOf.containsKey(c);
                }
                return false;
            }

            @Override
            public void onTouchEvent(RecyclerView r, android.view.MotionEvent e) {
                if (e.getActionMasked() != android.view.MotionEvent.ACTION_UP) return;
                float slop = 12 * r.getResources().getDisplayMetrics().density;
                if (Math.abs(e.getX() - x) > slop || Math.abs(e.getY() - y) > slop) return;
                View c = r.findChildViewUnder(e.getX(), e.getY());
                Runnable u = c == null ? null : undoOf.get(c);
                if (u != null) u.run();
            }

            @Override public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}
        });
        rv.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View child) {
                if (sessionBlocked.isEmpty()) return;
                // the item draws its content right after attaching
                child.postDelayed(() -> recheck(child), 60);
            }

            @Override public void onChildViewDetachedFromWindow(View child) {}
        });
    }

    private static void recheck(View child) {
        if (child.getParent() == null || child.getTag(TAG_PLACEHOLDER) != null) return;
        List<String> t = texts(child);
        for (java.util.Map.Entry<String, Runnable> e : new ArrayList<>(sessionBlocked.entrySet())) {
            String name = e.getKey();
            for (String s : t) {
                if (s.toLowerCase(Locale.ROOT).contains(name)) {
                    labelOf.put(child, name);
                    placeholder(child, "Blocked " + name, e.getValue());
                    return;
                }
            }
        }
    }

    /** Turns this channel's items on screen into placeholders (and the ones that come back later). */
    public static int placeholderChannelOnScreen(String channelName, boolean onlyLive, List<String> liveWords,
                                                 String label, Runnable onUndo) {
        if (channelName == null || channelName.isEmpty()) return 0;
        final String name = channelName.toLowerCase(Locale.ROOT);
        Runnable undoAll = () -> {
            synchronized (sessionBlocked) {
                sessionBlocked.remove(name);
            }
            onUndo.run();
            // show every placeholder of this channel again
            for (java.util.Map.Entry<View, Runnable> e : new ArrayList<>(undoOf.entrySet())) {
                Object tag = e.getKey().getTag(TAG_PLACEHOLDER);
                if (tag instanceof Runnable && labelOf.get(e.getKey()) != null && labelOf.get(e.getKey()).equals(name)) {
                    ((Runnable) tag).run();
                }
            }
        };
        if (!onlyLive) {
            synchronized (sessionBlocked) {
                sessionBlocked.put(name, undoAll);
            }
        }
        int n = 0;
        try {
            Activity a = Utils.getActivity();
            if (a == null) return 0;
            RecyclerView rv = FeedPositionGuard.findFeed(a);
            if (rv == null) return 0;
            watch(rv);
            for (int i = 0, c = rv.getChildCount(); i < c; i++) {
                View child = rv.getChildAt(i);
                if (child == null) continue;
                boolean channel = false, live = !onlyLive;
                for (String t : texts(child)) {
                    if (t.toLowerCase(Locale.ROOT).contains(name)) channel = true;
                    if (onlyLive && !live) {
                        if (t.equals("LIVE")) live = true;
                        for (String w : liveWords) if (!w.isEmpty() && t.contains(w)) live = true;
                    }
                }
                if (channel && live) {
                    labelOf.put(child, name);
                    placeholder(child, label, undoAll);
                    n++;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "placeholderChannelOnScreen failure", ex);
        }
        return n;
    }

    private static final java.util.Map<View, String> labelOf = new java.util.WeakHashMap<>();
}
