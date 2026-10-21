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
    }

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
        List<Target> targets = list.equals(BlockListSync.LIVE) ? getLive() : getChannels();
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
        }
        return cachedHiddenBytes;
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
        return addToggle(flyoutPanel, icon, BlockListSync.LIVE, channelName, cid,
                "morphe_block_live_flyout", "morphe_unblock_live_flyout", nextButtonIndex);
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
                Utils.showToastShort(str("morphe_block_list_added"));
                afterBlock(list, name);
            }
            BlockListFileSync.syncSoon();
            FlyoutUtils.dismissFlyout();
        }, index);
    }

    private static void afterBlock(String list, String name) {
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
            // The 3-dot menu is still closing; hide when the feed is touchable again.
            Utils.runOnMainThreadDelayed(() ->
                    app.morphe.extension.youtube.gestures.FeedItems.hideChannelOnScreen(name, live, words), 300);
        } catch (Exception ex) {
            Logger.printException(() -> "afterBlock failure", ex);
        }
    }

    @Nullable
    private static String findKey(String list, String name, String cid) {
        List<Target> targets = list.equals(BlockListSync.LIVE) ? getLive() : getChannels();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (Target t : targets) {
            if ((!cid.isEmpty() && cid.equals(t.cid)) || lower.equals(t.nameLower)) return t.key;
        }
        return null;
    }
}
