/*
 * "Download post images" / "Download comment image" in the 3-dot menu.
 * Image addresses are read from posts and comments while they are laid out (feed filter), and
 * matched to the item that was touched by its texts.
 *   Posts:    <download folder>/<channel>/<yymmdd>_<post start>[_n].jpg
 *   Comments: <download folder>/<yymmdd>_comment_<comment start>[_n].jpg
 * Animated GIFs can be turned into MP4.
 */
package app.morphe.extension.youtube.download;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.gestures.FeedGestures;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class PostImages {
    private static final Pattern IMAGE_URL = Pattern.compile("https://yt3\\.ggpht\\.com/[A-Za-z0-9_\\-./]+(=[A-Za-z0-9_\\-]+)?");
    private static final Pattern SIZE = Pattern.compile("(?:^|-)s(\\d+)");
    private static final Pattern HANDLE = Pattern.compile("/@([A-Za-z0-9._\\-]{3,40})");
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_\\-]{22}");
    private static final int MAX_ITEMS = 40;

    /** A post or comment that was laid out. */
    private static final class Item {
        final byte[] buffer;
        final List<String> bases;
        final String handle;
        final String cid;
        Item(byte[] buffer, List<String> bases, String handle, String cid) {
            this.buffer = buffer; this.bases = bases; this.handle = handle; this.cid = cid;
        }
    }

    private static final Map<byte[], Item> posts = lru();
    private static final Map<byte[], Item> comments = lru();

    private static Map<byte[], Item> lru() {
        return new LinkedHashMap<byte[], Item>(MAX_ITEMS, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<byte[], Item> eldest) {
                return size() > MAX_ITEMS;
            }
        };
    }

    private PostImages() {}

    /** Called by the feed filter for every post laid out. */
    public static void onPostBuffer(byte[] buffer, String ascii) {
        lastPostMs = System.currentTimeMillis();
        remember(posts, buffer, ascii, false);
    }

    /** Called by the feed filter for every comment laid out. */
    public static void onCommentBuffer(byte[] buffer, String ascii) {
        lastCommentMs = System.currentTimeMillis();
        remember(comments, buffer, ascii, true);
    }

    private static void remember(Map<byte[], Item> into, byte[] buffer, String ascii, boolean comment) {
        Set<String> bases = new LinkedHashSet<>();
        Matcher m = IMAGE_URL.matcher(ascii);
        while (m.find()) {
            String url = m.group();
            if (url.contains("/ytc/")) continue; // channel avatar
            int eq = url.indexOf('=');
            String base = eq < 0 ? url : url.substring(0, eq);
            String params = eq < 0 ? "" : url.substring(eq + 1);
            if (params.contains("-c-k")) continue; // round avatar crops
            Matcher size = SIZE.matcher(params);
            if (size.find() && Integer.parseInt(size.group(1)) < (comment ? 120 : 200) && !params.contains("fcrop")) continue;
            bases.add(base);
        }
        // Kept even without images: the menu also offers blocking the author.
        Matcher h = HANDLE.matcher(ascii);
        String handle = h.find() ? h.group(1) : "";
        Matcher c = CHANNEL_ID.matcher(ascii);
        String cid = c.find() ? c.group() : "";
        synchronized (into) {
            into.put(buffer, new Item(buffer, new ArrayList<>(bases), handle, cid));
        }
    }

    /**
     * The item whose buffer holds the most of the touched texts. Longer texts count more,
     * so a shared short text ("2 days ago", the channel name) can't pick a neighbour post.
     */
    private static Item findTouched(Map<byte[], Item> in, List<String> texts) {
        return findTouched(in, texts, false);
    }

    private static Item findTouched(Map<byte[], Item> in, List<String> texts, boolean strict) {
        // 69 mod: only texts that are in exactly ONE remembered item count (a text shared by
        // several posts, like the channel name or "2 days ago", used to pick the wrong post)
        List<Item> items;
        synchronized (in) {
            items = new ArrayList<>(in.values());
        }
        if (items.isEmpty()) return null;
        int[] score = new int[items.size()];
        int[] anyScore = new int[items.size()];
        for (String t : texts) {
            if (t.length() < 6 || t.length() > 400) continue;
            String probe = t.length() > 60 ? t.substring(0, 60) : t;
            byte[] pb = probe.getBytes(StandardCharsets.UTF_8);
            int hits = 0, last = -1;
            for (int i = 0; i < items.size(); i++) {
                if (FlyoutUtils.byteIndexOf(items.get(i).buffer, pb) >= 0) {
                    hits++;
                    last = i;
                    anyScore[i] += Math.min(probe.length(), 60);
                }
            }
            if (hits == 1) score[last] += Math.min(probe.length(), 60);
        }
        // newest wins a tie (LinkedHashMap in access order: later = newer)
        int best = -1;
        for (int i = 0; i < items.size(); i++) {
            if (score[i] > 0 && (best < 0 || score[i] >= score[best])) best = i;
        }
        if (best >= 0 && score[best] >= 12) return items.get(best);
        if (strict) return null;
        // nothing unique: fall back to the old rule, but only when one item is clearly ahead
        best = -1;
        int second = 0;
        for (int i = 0; i < items.size(); i++) {
            if (best < 0 || anyScore[i] >= anyScore[best]) {
                if (best >= 0) second = Math.max(second, anyScore[best]);
                best = i;
            } else {
                second = Math.max(second, anyScore[i]);
            }
        }
        return best >= 0 && anyScore[best] >= 12 && anyScore[best] > second ? items.get(best) : null;
    }

    /** 69 mod: when comments were laid out after the last post, the menu is a comment's menu. */
    private static volatile long lastCommentMs, lastPostMs;

    public static int addFlyoutButtons(Object flyoutPanel, int index) {
        List<String> texts = new ArrayList<>(FeedGestures.getLastTouchedTexts());
        Drawable icon = Utils.getContext().getResources().getDrawable(android.R.drawable.stat_sys_download, null);
        Drawable delIcon = Utils.getContext().getResources().getDrawable(android.R.drawable.ic_menu_delete, null);

        // A comment first: then the post button never shows inside the comments.
        Item comment = findTouched(comments, texts);
        if (comment != null) {
            if (Settings.DOWNLOAD_COMMENT_IMAGES_FLYOUT.get() && !comment.bases.isEmpty()) {
                index = addButtons(flyoutPanel, index, icon, delIcon, "cmt:" + comment.bases.get(0), comment, texts, true);
            }
            return app.morphe.extension.youtube.blocklist.BlockList.addAuthorButtons(
                    flyoutPanel, index, authorName(comment, texts), comment.cid, true);
        }
        // 69 mod: in the comments (laid out after the post) a post is only picked when its own
        // text was touched, so the comment menu doesn't get "Download post images"
        Item post = findTouched(posts, texts, lastCommentMs > lastPostMs);
        if (post == null) return index;
        if (Settings.DOWNLOAD_POST_IMAGES_FLYOUT.get() && !post.bases.isEmpty()) {
            index = addButtons(flyoutPanel, index, icon, delIcon, "post:" + post.bases.get(0), post, texts, false);
        }
        return app.morphe.extension.youtube.blocklist.BlockList.addAuthorButtons(
                flyoutPanel, index, channelName(post, texts), post.cid, false);
    }

    private static int addButtons(Object panel, int index, Drawable icon, Drawable delIcon, String key,
                                  Item item, List<String> texts, boolean comment) {
        String what = comment ? "comment image" : "post image";
        int n = item.bases.size();
        boolean done = Downloaded.has(key);
        String label = (done ? "Redownload " : "Download ") + what + (n > 1 ? "s (" + n + ")" : "")
                + (done ? "  ✓ downloaded" : "");
        index = FlyoutUtils.addFlyoutButton(panel, icon, label, v -> {
            FlyoutUtils.dismissFlyout();
            download(key, item, texts, comment);
        }, index);
        if (done) {
            index = FlyoutUtils.addFlyoutButton(panel, delIcon, "Delete downloaded " + what + "s", v -> {
                FlyoutUtils.dismissFlyout();
                Utils.runOnBackgroundThread(() -> {
                    int removed = Downloaded.delete(key);
                    Utils.runOnMainThread(() -> Utils.showToastShort(removed + " file(s) deleted"));
                });
            }, index);
        }
        return index;
    }

    // ---------------- download ----------------

    private static final class Fetched {
        final byte[] data;
        final String ext, mime;
        Fetched(byte[] data, String ext, String mime) { this.data = data; this.ext = ext; this.mime = mime; }
    }

    private static void download(String key, Item item, List<String> texts, boolean comment) {
        final String channel = comment ? "" : channelName(item, texts);
        final String body = bodyText(texts, channel);
        final String date = new SimpleDateFormat("yyMMdd", Locale.US).format(new Date());
        final String start = body.isEmpty() ? (comment ? "comment" : "post") : (body.length() > 40 ? body.substring(0, 40) : body);
        final String name = FileSaver.sanitize(date + "_" + (comment ? "comment_" : "") + start);
        final String folder = comment ? "" : FileSaver.sanitize(channel);
        final List<String> bases = item.bases;

        DownloadJobs.start(key, comment ? "Comment image" : "Post images", job -> {
            StringBuilder log = new StringBuilder();
            List<Fetched> fetched = new ArrayList<>();
            for (int i = 0; i < bases.size(); i++) {
                job.check();
                job.progress("Downloading " + (i + 1) + " / " + bases.size());
                fetched.add(fetch(bases.get(i), log));
            }
            int ok = 0;
            for (Fetched f : fetched) if (f != null) ok++;
            if (ok == 0) throw new java.io.IOException("No image could be downloaded\n" + log);
            if (ok > 1) {
                // Let the user choose; saving happens after the window closes.
                List<ImagePicker.Item> items = new ArrayList<>();
                List<Integer> map = new ArrayList<>();
                for (int i = 0; i < fetched.size(); i++) {
                    Fetched f = fetched.get(i);
                    if (f == null) continue;
                    items.add(new ImagePicker.Item((i + 1) + "  " + f.ext.substring(1).toUpperCase(Locale.ROOT)
                            + "  " + (f.data.length / 1024) + " KB", preview(f.data)));
                    map.add(i);
                }
                Utils.runOnMainThread(() -> ImagePicker.show(start, items, picked ->
                        DownloadJobs.start("save:" + key, comment ? "Comment images" : "Post images", j2 -> {
                            List<Integer> chosen = new ArrayList<>();
                            for (int p : picked) chosen.add(map.get(p));
                            return save(j2, key, fetched, chosen, folder, name, true);
                        })));
                return null;
            }
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < fetched.size(); i++) if (fetched.get(i) != null) all.add(i);
            return save(job, key, fetched, all, folder, name, bases.size() > 1);
        });
    }

    private static String save(DownloadJobs.Job job, String key, List<Fetched> fetched, List<Integer> which,
                               String folder, String name, boolean numbered) throws Exception {
        String where = "";
        int saved = 0;
        for (int i : which) {
            job.check();
            Fetched f = fetched.get(i);
            byte[] data = f.data;
            String ext = f.ext, mime = f.mime;
            if (".gif".equals(ext) && Settings.DOWNLOAD_GIF_TO_MP4.get()) {
                job.progress("Converting GIF to video…");
                byte[] mp4 = GifToMp4.convert(data);
                if (mp4 != null && mp4.length < data.length) {
                    data = mp4;
                    ext = ".mp4";
                    mime = "video/mp4";
                }
            }
            if (job.thumb == null) {
                try { job.thumbnail(preview(f.data)); } catch (Exception ignored) {}
            }
            String file = name + (numbered ? "_" + (i + 1) : "") + ext;
            where = FileSaver.save(folder, file, mime, data);
            Downloaded.add(key, FileSaver.lastUri.get());
            saved++;
        }
        int slash = where.lastIndexOf('/');
        return saved + (saved == 1 ? " image" : " images") + " saved · " + (slash > 0 ? where.substring(0, slash) : where);
    }

    private static Fetched fetch(String base, StringBuilder log) {
        for (String url : new String[]{base + "=s0", base + "=s2048", base}) {
            try {
                byte[] data = get(url);
                if (data.length < 2048) throw new java.io.IOException("too small: " + data.length + " bytes");
                String[] type = imageType(data);
                return new Fetched(data, type[0], type[1]);
            } catch (Exception ex) {
                log.append("FAIL ").append(url).append(": ").append(ex.getMessage()).append('\n');
            }
        }
        Logger.printInfo(() -> "Image download failed:\n" + log);
        return null;
    }

    private static Bitmap preview(byte[] data) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = Math.max(1, Math.max(b.outWidth, b.outHeight) / 480);
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /**
     * The channel's display name: a short touched text that also appears in this post and
     * doesn't look like a time or a counter. Falls back to the @handle.
     */
    private static String channelName(Item item, List<String> texts) {
        for (String t : texts) {
            String s = t.trim();
            if (s.length() < 2 || s.length() > 50) continue;
            String low = s.toLowerCase(Locale.ROOT);
            if (low.matches(".*\\d.*(ago|trước|giờ|ngày|day|week|month|year|hour|minute|second).*")) continue;
            if (low.matches("[\\d.,kmb\\s]+")) continue;
            if (low.startsWith("@")) return s.substring(1);
            if (FlyoutUtils.byteIndexOf(item.buffer, s.getBytes(StandardCharsets.UTF_8)) >= 0) return s;
        }
        if (!item.handle.isEmpty()) return item.handle;
        return "Posts";
    }

    private static String authorName(Item item, List<String> texts) {
        for (String t : texts) {
            String s = t.trim();
            if (s.startsWith("@") && s.length() <= 40 && !s.contains(" ")) return s.substring(1);
        }
        return item.handle;
    }

    private static String bodyText(List<String> texts, String channel) {
        String body = "";
        for (String t : texts) {
            if (t.length() > body.length() && t.length() < 2000 && !t.equals(channel)) body = t;
        }
        return body.replace('\n', ' ').trim();
    }

    private static String[] imageType(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0x89 && d[1] == 'P') return new String[]{".png", "image/png"};
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[8] == 'W') return new String[]{".webp", "image/webp"};
        if (d.length > 3 && d[0] == 'G' && d[1] == 'I') return new String[]{".gif", "image/gif"};
        return new String[]{".jpg", "image/jpeg"};
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
