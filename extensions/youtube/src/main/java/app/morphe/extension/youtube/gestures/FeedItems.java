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

    // ---------------- 69 mod: "Blocked X   UNDO" placeholder ----------------
    // Only plain View methods are used on the feed list: YouTube renames RecyclerView's own methods.

    private static final int TAG_PLACEHOLDER = 0x69690002;
    private static final java.util.Set<View> watched =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Channels blocked in this session: their items stay placeholders when scrolled back to. */
    private static final java.util.Map<String, Runnable> sessionBlocked = new java.util.LinkedHashMap<>();
    private static final java.util.Map<View, String> labelOf = new java.util.WeakHashMap<>();

    /** Shrinks the item and lays a bar "Blocked X   UNDO" over it; tapping the bar runs [onUndo]. */
    public static void placeholder(View item, String text, Runnable onUndo) {
        if (item.getTag(TAG_PLACEHOLDER) != null) return;
        Activity a = Utils.getActivity();
        if (a == null) return;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return;
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp == null) return;
        final int oldHeight = lp.height;
        float d = item.getResources().getDisplayMetrics().density;
        final int barH = (int) (52 * d);

        android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF212121);
        bar.setPadding((int) (16 * d), 0, (int) (8 * d), 0);
        bar.setClickable(true);
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xFFE0E0E0);
        t.setTextSize(14);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(t, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        TextView undo = new TextView(a);
        undo.setText("UNDO");
        undo.setTextColor(0xFF3EA6FF);
        undo.setTypeface(undo.getTypeface(), android.graphics.Typeface.BOLD);
        undo.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        bar.addView(undo);
        content.addView(bar, new android.widget.FrameLayout.LayoutParams(1, barH));

        final int[] loc = new int[2], base = new int[2];
        final android.view.ViewTreeObserver.OnPreDrawListener[] follow = new android.view.ViewTreeObserver.OnPreDrawListener[1];
        final Runnable[] restore = new Runnable[1];
        follow[0] = () -> {
            if (!item.isAttachedToWindow() || item.getTag(TAG_PLACEHOLDER) != restore[0]) {
                bar.setVisibility(View.GONE);
                return true;
            }
            item.getLocationInWindow(loc);
            content.getLocationInWindow(base);
            boolean shown = item.isShown() && item.getWidth() > 0;
            // only while fully inside the list (not over the top bar / bottom bar)
            if (shown && item.getParent() instanceof View) {
                View list = (View) item.getParent();
                int[] l = new int[2];
                list.getLocationInWindow(l);
                shown = loc[1] >= l[1] && loc[1] + barH <= l[1] + list.getHeight();
            }
            bar.setVisibility(shown ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams blp = bar.getLayoutParams();
            if (blp.width != item.getWidth()) {
                blp.width = item.getWidth();
                bar.setLayoutParams(blp);
            }
            float x = loc[0] - base[0], y = loc[1] - base[1];
            if (bar.getX() != x) bar.setX(x);
            if (bar.getY() != y) bar.setY(y);
            return true;
        };
        restore[0] = () -> {
            item.getViewTreeObserver().removeOnPreDrawListener(follow[0]);
            if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
            ViewGroup.LayoutParams p = item.getLayoutParams();
            if (p != null) {
                p.height = oldHeight;
                item.setLayoutParams(p);
            }
            item.setTag(TAG_PLACEHOLDER, null);
        };
        item.setTag(TAG_PLACEHOLDER, restore[0]);
        bar.setOnClickListener(v -> {
            restore[0].run();
            onUndo.run();
        });
        lp.height = barH;
        item.setLayoutParams(lp);
        item.getViewTreeObserver().addOnPreDrawListener(follow[0]);
        item.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                // recycled for another item: give the view back
                v.removeOnAttachStateChangeListener(this);
                if (v.getTag(TAG_PLACEHOLDER) == restore[0]) restore[0].run();
            }
        });
        View rv = item.getParent() instanceof View ? (View) item.getParent() : null;
        if (rv != null) watch(rv);
    }

    /** Items of channels blocked now become placeholders again when they come back on screen. */
    private static void watch(View rv) {
        if (!watched.add(rv) || !(rv instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) rv;
        rv.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (sessionBlocked.isEmpty() || !g.isAttachedToWindow()) return;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View child = g.getChildAt(i);
                if (child != null && child.getTag(TAG_PLACEHOLDER) == null) recheck(child);
            }
        });
    }

    private static void recheck(View child) {
        List<String> t = texts(child);
        if (t.isEmpty()) return;
        for (java.util.Map.Entry<String, Runnable> e : new ArrayList<>(sessionBlocked.entrySet())) {
            String name = e.getKey();
            for (String s : t) {
                if (s.toLowerCase(Locale.ROOT).contains(name)) {
                    labelOf.put(child, name);
                    View c = child;
                    // not during the layout pass
                    Utils.runOnMainThread(() -> placeholder(c, "Blocked " + name, e.getValue()));
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
            for (java.util.Map.Entry<View, String> e : new ArrayList<>(labelOf.entrySet())) {
                if (!name.equals(e.getValue())) continue;
                Object tag = e.getKey().getTag(TAG_PLACEHOLDER);
                if (tag instanceof Runnable) ((Runnable) tag).run();
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
}
