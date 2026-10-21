/*
 * "Download post images" in the 3-dot menu of a channel post.
 * Image addresses are read from the post while it is laid out (feed filter), and matched to
 * the post that was touched by its texts. Saved to
 *   <download folder>/<channel>/<yymmdd>_<post start>.jpg            (one image)
 *   <download folder>/<channel>/<yymmdd>_<post start>/1.jpg, 2.jpg … (several images)
 */
package app.morphe.extension.youtube.download;

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
    private static final int MAX_POSTS = 40;

    /** buffer of the post -> image base addresses (in order). */
    private static final Map<byte[], List<String>> posts = new LinkedHashMap<byte[], List<String>>(MAX_POSTS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<byte[], List<String>> eldest) {
            return size() > MAX_POSTS;
        }
    };

    private PostImages() {}

    /** Called by the feed filter for every post laid out. */
    public static void onPostBuffer(byte[] buffer, String ascii) {
        if (!Settings.DOWNLOAD_POST_IMAGES_FLYOUT.get()) return;
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
            if (size.find() && Integer.parseInt(size.group(1)) < 200 && !params.contains("fcrop")) continue;
            bases.add(base);
        }
        if (bases.isEmpty()) return;
        synchronized (posts) {
            posts.put(buffer, new ArrayList<>(bases));
        }
    }

    private static List<String> findImagesOfTouchedPost() {
        List<String> texts = FeedGestures.getLastTouchedTexts();
        synchronized (posts) {
            for (Map.Entry<byte[], List<String>> e : posts.entrySet()) {
                int hits = 0;
                for (String t : texts) {
                    if (t.length() < 8 || t.length() > 300) continue;
                    String probe = t.length() > 60 ? t.substring(0, 60) : t;
                    if (FlyoutUtils.byteIndexOf(e.getKey(), probe.getBytes(StandardCharsets.UTF_8)) >= 0) hits++;
                    if (hits >= 1) return e.getValue();
                }
            }
        }
        return null;
    }

    public static int addFlyoutButtons(Object flyoutPanel, int index) {
        if (!Settings.DOWNLOAD_POST_IMAGES_FLYOUT.get()) return index;
        final List<String> images = findImagesOfTouchedPost();
        if (images == null || images.isEmpty()) return index;
        final List<String> texts = new ArrayList<>(FeedGestures.getLastTouchedTexts());
        Drawable icon = Utils.getContext().getResources().getDrawable(android.R.drawable.stat_sys_download, null);
        return FlyoutUtils.addFlyoutButton(flyoutPanel, icon,
                "Download post images (" + images.size() + ")", v -> {
                    FlyoutUtils.dismissFlyout();
                    download(images, texts);
                }, index);
    }

    private static void download(List<String> bases, List<String> texts) {
        Utils.showToastShort("Downloading " + bases.size() + " image(s)…");
        Utils.runOnBackgroundThread(() -> {
            StringBuilder log = new StringBuilder();
            // Post header: channel name first, then the time, then the text.
            String channel = texts.isEmpty() ? "Posts" : texts.get(0);
            String body = "";
            for (String t : texts) {
                if (t.length() > body.length() && t.length() < 2000 && !t.equals(channel)) body = t;
            }
            String date = new SimpleDateFormat("yyMMdd", Locale.US).format(new Date());
            String start = body.isEmpty() ? "post" : (body.length() > 40 ? body.substring(0, 40) : body);
            String name = FileSaver.sanitize(date + "_" + start);
            String folder = FileSaver.sanitize(channel);
            if (bases.size() > 1) folder = folder + "/" + name;
            int saved = 0;
            String where = "";
            for (int i = 0; i < bases.size(); i++) {
                String base = bases.get(i);
                byte[] data = null;
                for (String url : new String[]{base + "=s0", base + "=s2048", base}) {
                    try {
                        data = get(url);
                        if (data.length < 2048) throw new java.io.IOException("too small: " + data.length + " bytes");
                        log.append("OK ").append(url).append('\n');
                        break;
                    } catch (Exception ex) {
                        log.append("FAIL ").append(url).append(": ").append(ex.getMessage()).append('\n');
                        data = null;
                    }
                }
                if (data == null) continue;
                String[] type = imageType(data);
                String file = (bases.size() > 1 ? String.valueOf(i + 1) : name) + type[0];
                try {
                    where = FileSaver.save(folder, file, type[1], data);
                    saved++;
                } catch (Exception ex) {
                    log.append("SAVE FAIL ").append(file).append(": ").append(ex).append('\n');
                }
            }
            if (saved == bases.size()) {
                String msg = saved + " image(s) saved to " + where.substring(0, where.lastIndexOf('/'));
                Utils.runOnMainThread(() -> Utils.showToastLong(msg));
                DownloadNotifier.post("Post images saved", msg, false);
            } else {
                log.insert(0, "Saved " + saved + " of " + bases.size() + "\n");
                Logger.printInfo(() -> "Post image download failed:\n" + log);
                DownloadNotifier.post("Post image download failed", log.toString(), true);
                Utils.runOnMainThread(() -> Utils.showToastLong("Post image download failed. See notification"));
            }
        });
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
