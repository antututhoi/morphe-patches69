/*
 * Merge logic for the block list, compatible with the Multitool Firefox add-on's
 * multitool-sync.json ("yt": {"channels": {...}, "live": {...}}).
 * Pure functions on org.json; no Android dependencies.
 */
package app.morphe.extension.youtube.blocklist;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public final class BlockListSync {

    public static final String SYNC_FILE_NAME = "multitool-sync.json";
    public static final String CHANNELS = "channels";
    public static final String LIVE = "live";

    private BlockListSync() {}

    /** Entry key: trimmed, lower case (so "@Handle" and "handle" lines of the add-on match). */
    public static String normalizeKey(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Newer {@code t} wins; on a tie the incoming entry wins (same rule as the add-on). */
    public static JSONObject mergeEntries(JSONObject a, JSONObject b) throws JSONException {
        JSONObject out = new JSONObject();
        if (a != null) copy(a, out);
        if (b != null) {
            for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject v = b.optJSONObject(k);
                if (v == null) continue;
                JSONObject cur = out.optJSONObject(k);
                if (cur == null || v.optDouble("t", 0) >= cur.optDouble("t", 0)) out.put(k, v);
            }
        }
        return out;
    }

    private static void copy(JSONObject from, JSONObject to) throws JSONException {
        for (Iterator<String> it = from.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject v = from.optJSONObject(k);
            if (v != null) to.put(k, v);
        }
    }

    /**
     * Merges the block lists of two sync documents. Everything outside "yt"
     * (marks, settings) is preserved from {@code remote} untouched so other tools' data is never lost.
     * @param local  {"channels":{},"live":{}}
     * @param remote full contents of multitool-sync.json (may be null/empty)
     * @return the full document to write back
     */
    public static JSONObject mergeIntoDocument(JSONObject local, JSONObject remote) throws JSONException {
        JSONObject doc = remote == null ? new JSONObject() : new JSONObject(remote.toString());
        if (!doc.has("v")) doc.put("v", 1);
        if (!doc.has("marks")) doc.put("marks", new JSONObject());
        JSONObject yt = doc.optJSONObject("yt");
        if (yt == null) yt = new JSONObject();
        for (String list : new String[]{CHANNELS, LIVE}) {
            yt.put(list, mergeEntries(yt.optJSONObject(list), local == null ? null : local.optJSONObject(list)));
        }
        doc.put("yt", yt);
        return doc;
    }

    /** Extracts {"channels","live"} from a sync document. */
    public static JSONObject extractLists(JSONObject doc) throws JSONException {
        JSONObject out = new JSONObject();
        JSONObject yt = doc == null ? null : doc.optJSONObject("yt");
        out.put(CHANNELS, yt != null && yt.optJSONObject(CHANNELS) != null ? yt.optJSONObject(CHANNELS) : new JSONObject());
        out.put(LIVE, yt != null && yt.optJSONObject(LIVE) != null ? yt.optJSONObject(LIVE) : new JSONObject());
        return out;
    }

    /**
     * Reads the add-on's plain-text block lists (settings.value.features: 'yt-block-channels',
     * 'yt-block-live', one name or @handle per line) as additions, so the Android side works
     * with an unmodified add-on. Entries get the settings timestamp; they never overwrite newer local entries.
     */
    public static JSONObject extractAddonFeatureLists(JSONObject doc) throws JSONException {
        JSONObject out = new JSONObject();
        out.put(CHANNELS, new JSONObject());
        out.put(LIVE, new JSONObject());
        JSONObject settings = doc == null ? null : doc.optJSONObject("settings");
        if (settings == null) return out;
        JSONObject value = settings.optJSONObject("value");
        JSONObject features = value == null ? null : value.optJSONObject("features");
        if (features == null) return out;
        double t = settings.optDouble("t", 0);
        String[][] map = {{"yt-block-channels", CHANNELS}, {"yt-block-live", LIVE}};
        for (String[] m : map) {
            String text = features.optString(m[0], "");
            for (String line : text.split("\\r?\\n")) {
                String key = normalizeKey(line);
                if (key.isEmpty()) continue;
                JSONObject e = new JSONObject();
                e.put("t", t);
                e.put("name", line.trim());
                out.getJSONObject(m[1]).put(key, e);
            }
        }
        return out;
    }
}
