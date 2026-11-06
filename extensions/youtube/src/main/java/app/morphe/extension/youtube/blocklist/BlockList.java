/*
 * Block channel / block channel's livestreams: store, matching and 3-dot menu items.
 */
package app.morphe.extension.youtube.blocklist;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class BlockList {

    /** A block target. Matches by channel id bytes or by name/@handle text. */
    public static final class Target {
        public final String key, name, cid;
        public final byte[] nameBytes, cidBytes;
        public final String nameLower;

        Target(String key, String name, String cid) {
            this.key = key;
            this.name = name;
            this.cid = cid;
            this.nameLower = name.toLowerCase(java.util.Locale.ROOT);
            // Very short names would match random buffer content.
            this.nameBytes = name.length() >= 3 ? name.getBytes(StandardCharsets.UTF_8) : null;
            this.cidBytes = cid.length() == FlyoutUtils.CHANNEL_ID_LENGTH
                    ? cid.getBytes(StandardCharsets.US_ASCII) : null;
        }
    }

    private static String cachedJson;
    private static JSONObject cachedDoc = new JSONObject();
    private static List<Target> cachedChannels = new ArrayList<>();
    private static List<Target> cachedLive = new ArrayList<>();
    private static List<Target> cachedPosts = new ArrayList<>();
    private static List<Target> cachedShorts = new ArrayList<>();

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    // ---------------- store ----------------

    private static synchronized void load() {
        String json = Settings.BLOCK_LIST.get();
        if (json.equals(cachedJson)) return;
        cachedJson = json;
        try {
            cachedDoc = json.isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            Logger.printInfo(() -> "Block list parse error, resetting", ex);
            cachedDoc = new JSONObject();
        }
        cachedChannels = targets(BlockListSync.CHANNELS);
        cachedLive = targets(BlockListSync.LIVE);
        cachedPosts = targets(BlockListSync.POSTS);
        cachedShorts = targets(BlockListSync.SHORTS);
        // 69 mod: one-pass matchers (fast scrolling)
        matchChannels = FastMatcher.of(cachedChannels);
        matchLive = FastMatcher.of(cachedLive);
        matchPosts = FastMatcher.of(cachedPosts);
        matchShorts = FastMatcher.of(cachedShorts);
    }

    private static FastMatcher matchChannels = FastMatcher.of(new ArrayList<>());
    private static FastMatcher matchLive = matchChannels, matchPosts = matchChannels, matchShorts = matchChannels;

    public static synchronized FastMatcher channelsMatcher() { load(); return matchChannels; }
    public static synchronized FastMatcher liveMatcher() { load(); return matchLive; }
    public static synchronized FastMatcher postsMatcher() { load(); return matchPosts; }
    public static synchronized FastMatcher shortsMatcher() { load(); return matchShorts; }

    private static List<Target> targets(String list) {
        List<Target> out = new ArrayList<>();
        JSONObject map = cachedDoc.optJSONObject(list);
        if (map == null) return out;
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject e = map.optJSONObject(key);
            if (e == null || e.optInt("d", 0) == 1) continue; // tombstone
            String name = e.optString("name", key);
            out.add(new Target(key, name.isEmpty() ? key : name, e.optString("cid", "")));
        }
        return out;
    }

    public static synchronized List<Target> getChannels() { load(); return cachedChannels; }
    public static synchronized List<Target> getLive() { load(); return cachedLive; }
    public static synchronized List<Target> getPosts() { load(); return cachedPosts; }
    public static synchronized List<Target> getShorts() { load(); return cachedShorts; }

    public static List<Target> getList(String list) {
        switch (list) {
            case BlockListSync.LIVE: return getLive();
            case BlockListSync.POSTS: return getPosts();
            case BlockListSync.SHORTS: return getShorts();
            default: return getChannels();
        }
    }

    /** Document in the sync format: {"channels":{key:{t,d?,name,cid?}},"live":{...}} including tombstones. */
    public static synchronized JSONObject getDocument() {
        load();
        try { return new JSONObject(cachedDoc.toString()); } catch (JSONException e) { return new JSONObject(); }
    }

    public static synchronized void setDocument(JSONObject doc) {
        Settings.BLOCK_LIST.save(doc.toString());
        load();
    }

    public static synchronized void add(String list, String name, @Nullable String cid) {
        String key = BlockListSync.normalizeKey(name.isEmpty() && cid != null ? cid : name);
        if (key.isEmpty()) return;
        try {
            JSONObject doc = getDocument();
            JSONObject map = doc.optJSONObject(list);
            if (map == null) { map = new JSONObject(); doc.put(list, map); }
            JSONObject e = new JSONObject();
            e.put("t", System.currentTimeMillis());
            e.put("name", name);
            if (cid != null && !cid.isEmpty()) e.put("cid", cid);
            map.put(key, e);
            setDocument(doc);
        } catch (JSONException ex) {
            Logger.printException(() -> "add failure", ex);
        }
    }

    /** Removing writes a tombstone so the removal propagates through sync. */
    public static synchronized void remove(String list, String key) {
        try {
            JSONObject doc = getDocument();
            JSONObject map = doc.optJSONObject(list);
            if (map == null || !map.has(key)) return;
            JSONObject e = new JSONObject();
            e.put("t", System.currentTimeMillis());
            e.put("d", 1);
            map.put(key, e);
            setDocument(doc);
        } catch (JSONException ex) {
            Logger.printException(() -> "remove failure", ex);
        }
    }

    public static boolean isBlocked(String list, String name, String cid) {
        List<Target> targets = getList(list);
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        for (Target t : targets) {
            if ((!cid.isEmpty() && cid.equals(t.cid)) || (!lower.isEmpty() && lower.equals(t.nameLower))) return true;
        }
        return false;
    }

    // ---------------- hidden single videos (swipe left) ----------------

    private static final int MAX_HIDDEN_TITLES = 500;
    private static String cachedHiddenRaw;
    private static List<byte[]> cachedHiddenBytes = new ArrayList<>();

    public static synchronized void hideVideoTitle(String title) {
        String raw = Settings.HIDDEN_VIDEO_TITLES.get();
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(raw.isEmpty() ? new String[0] : raw.split("\n")));
        String clean = title.replace('\n', ' ').trim();
        if (clean.length() < 4 || lines.contains(clean)) return;
        lines.add(clean);
        while (lines.size() > MAX_HIDDEN_TITLES) lines.remove(0);
        Settings.HIDDEN_VIDEO_TITLES.save(android.text.TextUtils.join("\n", lines));
    }

    /** UTF-8 bytes of the titles hidden by swiping. */
    public static synchronized List<byte[]> getHiddenTitleBytes() {
        String raw = Settings.HIDDEN_VIDEO_TITLES.get();
        if (!raw.equals(cachedHiddenRaw)) {
            List<byte[]> out = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line.trim().length() >= 4) out.add(line.trim().getBytes(StandardCharsets.UTF_8));
            }
            cachedHiddenBytes = out;
            cachedHiddenRaw = raw;
            hiddenMatcher = new FastMatcher(out, new java.util.HashSet<>());
        }
        return cachedHiddenBytes;
    }

    private static FastMatcher hiddenMatcher = new FastMatcher(new ArrayList<>(), new java.util.HashSet<>());

    /** 69 mod: titles hidden by swiping, all checked in one pass. */
    public static synchronized FastMatcher hiddenTitlesMatcher() {
        getHiddenTitleBytes();
        return hiddenMatcher;
    }

    // ---------------- 3-dot menu ----------------

    public static int addFlyoutButtons(Object flyoutPanel, int nextButtonIndex, boolean isMyTabHistoryFlyout) {
        if (isMyTabHistoryFlyout || !isPatchIncluded() || !Settings.BLOCK_LIST_FLYOUT_MENU.get()) {
            return nextButtonIndex;
        }
        String cid = !FlyoutUtils.getFlyoutChannelId().isEmpty()
                ? FlyoutUtils.getFlyoutChannelId() : VideoInformation.getChannelId();
        String name = !cid.isEmpty() ? FlyoutUtils.getFlyoutChannelName() : VideoInformation.getChannelName();
        if (cid.isEmpty() && (name == null || name.isEmpty())) return nextButtonIndex;
        final String channelName = name == null ? "" : name;

        Drawable icon = ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                ? "morphe_settings_screen_01_ads_bold" : "morphe_settings_screen_01_ads");

        nextButtonIndex = addToggle(flyoutPanel, icon, BlockListSync.CHANNELS, channelName, cid,
                "morphe_block_channel_flyout", "morphe_unblock_channel_flyout", nextButtonIndex);
        nextButtonIndex = addToggle(flyoutPanel, icon, BlockListSync.LIVE, channelName, cid,
                "morphe_block_live_flyout", "morphe_unblock_live_flyout", nextButtonIndex);
        nextButtonIndex = addToggle(flyoutPanel, icon, BlockListSync.SHORTS, channelName, cid,
                "morphe_block_shorts_flyout", "morphe_unblock_shorts_flyout", nextButtonIndex);
        return addToggle(flyoutPanel, icon, BlockListSync.POSTS, channelName, cid,
                "morphe_block_posts_flyout", "morphe_unblock_posts_flyout", nextButtonIndex);
    }

    /** 69 mod: block the author of a channel post or a comment (menus without a video). */
    public static int addAuthorButtons(Object flyoutPanel, int index, String name, String cid, boolean comment) {
        if (!isPatchIncluded() || !Settings.BLOCK_LIST_FLYOUT_MENU.get()) return index;
        if ((cid == null || cid.isEmpty()) && (name == null || name.isEmpty())) return index;
        String n = name == null ? "" : name;
        String id = cid == null ? "" : cid;
        Drawable icon = ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                ? "morphe_settings_screen_01_ads_bold" : "morphe_settings_screen_01_ads");
        index = addToggle(flyoutPanel, icon, BlockListSync.CHANNELS, n, id,
                "morphe_block_channel_flyout", "morphe_unblock_channel_flyout", index);
        if (!comment) {
            index = addToggle(flyoutPanel, icon, BlockListSync.POSTS, n, id,
                    "morphe_block_posts_flyout", "morphe_unblock_posts_flyout", index);
        }
        return index;
    }

    private static int addToggle(Object panel, Drawable icon, String list, String name, String cid,
                                 String blockKey, String unblockKey, int index) {
        final boolean blocked = isBlocked(list, name, cid);
        return FlyoutUtils.addFlyoutButton(panel, icon, str(blocked ? unblockKey : blockKey), v -> {
            if (blocked) {
                String key = findKey(list, name, cid);
                if (key != null) remove(list, key);
                Utils.showToastShort(str("morphe_block_list_removed"));
            } else {
                add(list, name, cid);
                // 69 mod: in Shorts also press YouTube's own "Don't recommend channel"
                if (app.morphe.extension.youtube.shared.ShortsPlayerState.isOpen()) showUndo(list, name, cid);
                if (dontRecommendInShorts(panel, list)) {
                    BlockListFileSync.syncSoon();
                    return;
                }
                afterBlock(list, name, cid);
            }
            BlockListFileSync.syncSoon();
            FlyoutUtils.dismissFlyout();
        }, index);
    }

    /** "Don't recommend channel" in many languages (YouTube's own menu text). */
    private static final java.util.regex.Pattern DONT_RECOMMEND = java.util.regex.Pattern.compile(
            "(?i)(recommend.*channel|channel.*recommend|đề xuất kênh|recomendar.*canal|recomendes.*canal|"
                    + "empfehl.*kanal|kanal.*empfehl|recommander.*chaîne|consigliare.*canale|kanał.*polec|"
                    + "polec.*kanał|рекомендовать.*канал|канал.*рекоменд|kanal.*öner|öner.*kanal|"
                    + "チャンネルをおすすめに表示しない|频道.*推荐|推荐.*频道|頻道.*推薦|推薦.*頻道|채널.*추천|"
                    + "jangan rekomendasikan saluran|อย่าแนะนำช่อง|kanaal.*aanbevelen|aanbevelen.*kanaal|"
                    + "nie polecaj kanału|não recomendar canal|no recomendar canal)");

    /**
     * Presses YouTube's own "Don't recommend channel" row of the open Shorts menu.
     * YouTube then stops showing the channel; afterwards the next Short starts.
     * Returns false when it is not a Shorts menu or the row is missing (then the normal block runs).
     */
    private static boolean dontRecommendInShorts(Object panel, String list) {
        try {
            if (!list.equals(BlockListSync.CHANNELS)) return false;
            if (!app.morphe.extension.youtube.shared.ShortsPlayerState.isOpen()) return false;
            if (!Settings.SHORTS_BLOCK_DONT_RECOMMEND.get()) return false;
            android.view.View row = FlyoutUtils.findFlyoutItem(panel, DONT_RECOMMEND);
            if (row == null) {
                Logger.printDebug(() -> "Don't recommend row not found");
                return false;
            }
            String before = app.morphe.extension.youtube.patches.VideoInformation.getVideoId();
            android.view.View target = clickable(row, 0);
            (target != null ? target : row).performClick();
            // YouTube may move on by itself; if it does not, go to the next Short.
            Utils.runOnMainThreadDelayed(() -> {
                String now = app.morphe.extension.youtube.patches.VideoInformation.getVideoId();
                if (before != null && before.equals(now)) {
                    FlyoutUtils.dismissFlyout();
                    app.morphe.extension.youtube.gestures.ShortsSkip.next();
                }
            }, 900);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "dontRecommendInShorts failure", ex);
            return false;
        }
    }

    private static android.view.View clickable(android.view.View v, int depth) {
        if (v.isClickable() && v.hasOnClickListeners()) return v;
        if (depth < 6 && v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View c = clickable(g.getChildAt(i), depth + 1);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static void afterBlock(String list, String name, String cid) {
        try {
            if (app.morphe.extension.youtube.shared.ShortsPlayerState.isOpen()) {
                if (Settings.SHORTS_SKIP_AFTER_BLOCK.get()) {
                    Utils.runOnMainThreadDelayed(app.morphe.extension.youtube.gestures.ShortsSkip::next, 400);
                }
                return;
            }
            boolean live = list.equals(BlockListSync.LIVE);
            List<String> words = new ArrayList<>();
            for (String w : Settings.BLOCK_LIVE_KEYWORDS.get().split("[\\r\\n,]+")) {
                w = w.trim().replace("\"", "");
                if (!w.isEmpty()) words.add(w);
            }
            String what = live ? "livestreams of " : list.equals(BlockListSync.SHORTS)
                    ? "Shorts of " : list.equals(BlockListSync.POSTS) ? "posts of " : "";
            String label = "Blocked " + what + (name.isEmpty() ? cid : name);
            Runnable undo = () -> {
                String key = findKey(list, name, cid);
                if (key != null) remove(list, key);
                BlockListFileSync.syncSoon();
                Utils.showToastShort("Unblocked " + (name.isEmpty() ? cid : name));
            };
            // 69 mod: the blocked items turn into a "Blocked X   UNDO" bar right where they were.
            // The 3-dot menu is still closing; do it when the feed is touchable again.
            Utils.runOnMainThreadDelayed(() -> {
                int n = app.morphe.extension.youtube.gestures.FeedItems.placeholderChannelOnScreen(
                        name, live, words, label, undo);
                if (n == 0) showUndo(list, name, cid);
            }, 300);
        } catch (Exception ex) {
            Logger.printException(() -> "afterBlock failure", ex);
        }
    }

    @Nullable
    private static String findKey(String list, String name, String cid) {
        List<Target> targets = getList(list);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (Target t : targets) {
            if ((!cid.isEmpty() && cid.equals(t.cid)) || lower.equals(t.nameLower)) return t.key;
        }
        return null;
    }

    /** 69 mod: "Blocked X · UNDO" bar for a few seconds after blocking. */
    private static void showUndo(String list, String name, String cid) {
        Utils.runOnMainThreadDelayed(() -> {
            try {
                android.app.Activity a = Utils.getActivity();
                if (a == null) {
                    Utils.showToastShort(str("morphe_block_list_added"));
                    return;
                }
                android.view.ViewGroup decor = (android.view.ViewGroup) a.getWindow().getDecorView();
                float d = a.getResources().getDisplayMetrics().density;
                android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
                bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xF0303030);
                bg.setCornerRadius(10 * d);
                bar.setBackground(bg);
                int p = (int) (14 * d);
                bar.setPadding(p, (int) (6 * d), (int) (6 * d), (int) (6 * d));
                android.widget.TextView t = new android.widget.TextView(a);
                String what = list.equals(BlockListSync.LIVE) ? "livestreams of " : list.equals(BlockListSync.SHORTS)
                        ? "Shorts of " : list.equals(BlockListSync.POSTS) ? "posts of " : "";
                t.setText("Blocked " + what + (name.isEmpty() ? cid : name));
                t.setTextColor(0xFFFFFFFF);
                t.setMaxLines(2);
                bar.addView(t, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
                android.widget.Button undo = new android.widget.Button(a);
                undo.setText("UNDO");
                undo.setTextColor(0xFF64B5F6);
                undo.setBackgroundColor(0);
                bar.addView(undo);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -2,
                        android.view.Gravity.BOTTOM);
                lp.setMargins((int) (12 * d), 0, (int) (12 * d), (int) (90 * d));
                decor.addView(bar, lp);
                Runnable remove = () -> { try { decor.removeView(bar); } catch (Exception ignored) { } };
                undo.setOnClickListener(v -> {
                    String key = findKey(list, name, cid);
                    if (key != null) remove(list, key);
                    BlockListFileSync.syncSoon();
                    Utils.showToastShort("Unblocked. Refresh the feed to see it again");
                    remove.run();
                });
                Utils.runOnMainThreadDelayed(remove, 6000);
            } catch (Exception ex) {
                Utils.showToastShort(str("morphe_block_list_added"));
            }
        }, 350);
    }
}
