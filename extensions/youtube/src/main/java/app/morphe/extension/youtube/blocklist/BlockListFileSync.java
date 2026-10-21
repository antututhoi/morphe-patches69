/*
 * Two-way sync of the block list with a multitool-sync.json file the user picked
 * (e.g. in a Google Drive folder), shared with the Multitool Firefox add-on.
 * A damaged file is never overwritten.
 */
package app.morphe.extension.youtube.blocklist;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

public final class BlockListFileSync {

    private static final Object LOCK = new Object();
    private static volatile boolean pending;

    private BlockListFileSync() {}

    /** Runs a sync soon on a background thread (collapses bursts of changes). */
    public static void syncSoon() {
        if (Settings.BLOCK_LIST_SYNC_URI.get().isEmpty() || pending) return;
        pending = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            pending = false;
            syncNow();
        });
    }

    /** @return null on success, otherwise an error text. */
    public static String syncNow() {
        String uriString = Settings.BLOCK_LIST_SYNC_URI.get();
        if (uriString.isEmpty()) return "No sync file chosen";
        synchronized (LOCK) {
            try {
                Uri uri = Uri.parse(uriString);
                ContentResolver cr = Utils.getContext().getContentResolver();

                JSONObject remote = null;
                String text = read(cr, uri);
                if (!text.trim().isEmpty()) {
                    try {
                        remote = new JSONObject(text);
                    } catch (Exception ex) {
                        return "Sync file is damaged, not overwritten"; // never destroy it
                    }
                }

                // 1) Pull: yt maps, plus the add-on's plain text lists (additions only).
                JSONObject local = BlockList.getDocument();
                JSONObject remoteLists = BlockListSync.extractLists(remote);
                JSONObject addonLists = BlockListSync.extractAddonFeatureLists(remote);
                JSONObject merged = new JSONObject();
                for (String list : new String[]{BlockListSync.CHANNELS, BlockListSync.LIVE}) {
                    JSONObject m = BlockListSync.mergeEntries(addonLists.optJSONObject(list), remoteLists.optJSONObject(list));
                    // Add-on text lines can't express removals: a tombstone in yt maps must win over them.
                    m = BlockListSync.mergeEntries(m, local.optJSONObject(list));
                    merged.put(list, m);
                }
                // Note: only changes when entries differ.
                if (!merged.toString().equals(stripNonLists(local).toString())) BlockList.setDocument(merged);

                // 2) Push.
                JSONObject out = BlockListSync.mergeIntoDocument(merged, remote);
                if (!out.toString().equals(remote == null ? "" : remote.toString())) write(cr, uri, out.toString());
                return null;
            } catch (Exception ex) {
                Logger.printException(() -> "Block list sync failure", ex);
                return String.valueOf(ex.getMessage());
            }
        }
    }

    private static JSONObject stripNonLists(JSONObject doc) throws Exception {
        JSONObject o = new JSONObject();
        o.put(BlockListSync.CHANNELS, doc.optJSONObject(BlockListSync.CHANNELS) == null ? new JSONObject() : doc.optJSONObject(BlockListSync.CHANNELS));
        o.put(BlockListSync.LIVE, doc.optJSONObject(BlockListSync.LIVE) == null ? new JSONObject() : doc.optJSONObject(BlockListSync.LIVE));
        return o;
    }

    private static String read(ContentResolver cr, Uri uri) throws Exception {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Cannot open sync file");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(ContentResolver cr, Uri uri, String text) throws Exception {
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("Cannot write sync file");
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
