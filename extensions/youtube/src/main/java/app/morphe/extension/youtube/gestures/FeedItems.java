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
}
