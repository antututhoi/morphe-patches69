/*
 * 69 mod: "Save to playlist" sheet for a Short: lists your playlists with a tick when the Short is in it.
 * Uses the same YouTube requests as the queue / playlist features of the patches.
 */
package app.morphe.extension.youtube.shorts69;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistRoutes;

public final class ShortsSaveDialog {
    private ShortsSaveDialog() {}

    private static final class Item {
        final String id;
        final String title;
        boolean contains;

        Item(String id, String title, boolean contains) {
            this.id = id;
            this.title = title;
            this.contains = contains;
        }
    }

    public static void show(Activity activity, String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            Utils.showToastShort("No video id for this Short yet");
            return;
        }
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showToastShort("Sign in to save to playlists");
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            List<Item> items = fetch(videoId);
            Utils.runOnMainThread(() -> {
                if (items == null || items.isEmpty()) {
                    Utils.showToastShort("Could not load your playlists");
                    return;
                }
                String[] names = new String[items.size()];
                boolean[] checked = new boolean[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    names[i] = items.get(i).title;
                    checked[i] = items.get(i).contains;
                }
                new AlertDialog.Builder(activity)
                        .setTitle("Save to…")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                            Item it = items.get(which);
                            Utils.runOnBackgroundThread(() -> {
                                boolean ok = edit(videoId, it.id, !isChecked);
                                Utils.showToastShort(ok
                                        ? (isChecked ? "Saved to " : "Removed from ") + it.title
                                        : "Failed – try again");
                            });
                        })
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private static JSONObject post(app.morphe.extension.shared.requests.Route.CompiledRoute route, byte[] body) throws Exception {
        HttpURLConnection c = PlaylistRoutes.getConnection(route, AuthUtils.getRequestHeader());
        c.setFixedLengthStreamingMode(body.length);
        c.getOutputStream().write(body);
        if (c.getResponseCode() != 200) {
            Logger.printDebug(() -> "Shorts save request failed");
            return null;
        }
        return Requester.parseJSONObject(c);
    }

    private static List<Item> fetch(String videoId) {
        try {
            JSONObject json = post(PlaylistRoutes.GET_PLAYLISTS, PlaylistRoutes.mod69PlaylistsForVideoBody(videoId));
            if (json == null) return null;
            JSONArray playlists = json.getJSONArray("contents").getJSONObject(0)
                    .getJSONObject("addToPlaylistRenderer").getJSONArray("playlists");
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject r = playlists.getJSONObject(i).optJSONObject("playlistAddToOptionRenderer");
                if (r == null) continue;
                String title = r.getJSONObject("title").getJSONArray("runs").getJSONObject(0).getString("text");
                boolean contains = "ALL".equals(r.optString("containsSelectedVideos"));
                out.add(new Item(r.getString("playlistId"), title, contains));
            }
            return out;
        } catch (Exception ex) {
            Logger.printException(() -> "Shorts save fetch failed", ex);
            return null;
        }
    }

    private static boolean edit(String videoId, String playlistId, boolean remove) {
        try {
            JSONObject json = post(PlaylistRoutes.EDIT_PLAYLIST, PlaylistRoutes.mod69EditBody(videoId, playlistId, remove));
            return json != null && json.optString("status").contains("SUCCEEDED");
        } catch (Exception ex) {
            Logger.printException(() -> "Shorts save edit failed", ex);
            return false;
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
