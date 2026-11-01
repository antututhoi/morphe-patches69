package app.morphe.extension.youtube.download;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.shared.Utils;

/** 69 mod: remembers what was downloaded (per video / post / comment) so the menu can say so and delete it. */
public final class Downloaded {
    private Downloaded() {}

    private static SharedPreferences prefs() {
        return Utils.getContext().getSharedPreferences("morphe_69_downloaded", Context.MODE_PRIVATE);
    }

    public static boolean has(String key) {
        return prefs().contains(key);
    }

    public static int count(String key) {
        return prefs().getStringSet(key, new HashSet<>()).size();
    }

    public static synchronized void add(String key, String uri) {
        if (key == null || uri == null) return;
        Set<String> set = new HashSet<>(prefs().getStringSet(key, new HashSet<>()));
        set.add(uri);
        prefs().edit().putStringSet(key, set).apply();
    }

    /** Deletes the saved files. @return how many were removed. */
    public static synchronized int delete(String key) {
        Set<String> set = prefs().getStringSet(key, new HashSet<>());
        int n = 0;
        Context c = Utils.getContext();
        for (String u : set) {
            try {
                if (u.startsWith("content://")) {
                    Uri uri = Uri.parse(u);
                    boolean ok;
                    if (android.provider.DocumentsContract.isDocumentUri(c, uri)) {
                        ok = android.provider.DocumentsContract.deleteDocument(c.getContentResolver(), uri);
                    } else {
                        ok = c.getContentResolver().delete(uri, null, null) > 0;
                    }
                    if (ok) n++;
                } else if (new File(u).delete()) {
                    n++;
                }
            } catch (Exception ignored) {
                // Already gone or no permission: forget it anyway.
            }
        }
        prefs().edit().remove(key).apply();
        return n;
    }
}
