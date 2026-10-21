/*
 * "Download thumbnails" item in the 3-dot menu. Shows every thumbnail of the video,
 * lets you tick the ones you want (or download all) and saves them to
 * <download folder>/<channel name>/<title>_<yymmdd>[_n].jpg
 */
package app.morphe.extension.youtube.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.VideoMetaRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ThumbnailDownloader {

    /** A thumbnail with real image data. */
    private static final class Thumb {
        final String label;
        final byte[] data;
        final int width, height;
        final Bitmap preview;
        boolean selected = true;

        Thumb(String label, byte[] data, Bitmap preview, int w, int h) {
            this.label = label; this.data = data; this.preview = preview; this.width = w; this.height = h;
        }
    }

    // Smaller than this is an error page or an empty file, never a thumbnail.
    private static final int MIN_IMAGE_BYTES = 2048;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private ThumbnailDownloader() {}

    public static int addFlyoutButtons(Object flyoutPanel, int index, boolean isMyTabHistoryFlyout) {
        if (isMyTabHistoryFlyout || !isPatchIncluded() || !Settings.DOWNLOAD_THUMBNAILS_FLYOUT.get()) return index;
        String videoId = !FlyoutUtils.getFlyoutVideoId().isEmpty()
                ? FlyoutUtils.getFlyoutVideoId() : VideoInformation.getVideoId();
        if (videoId == null || videoId.isEmpty()) return index;
        final String id = videoId;
        Drawable icon = Utils.getContext().getResources().getDrawable(android.R.drawable.stat_sys_download, null);
        return FlyoutUtils.addFlyoutButton(flyoutPanel, icon, "Download thumbnails", v -> {
            FlyoutUtils.dismissFlyout();
            load(id);
        }, index);
    }

    private static void load(String videoId) {
        Utils.showToastShort("Loading thumbnails…");
        Utils.runOnBackgroundThread(() -> {
            StringBuilder log = new StringBuilder("videoId=" + videoId + "\n");
            try {
                VideoMetaRequest.Meta meta;
                try {
                    meta = VideoMetaRequest.fetch(videoId);
                } catch (Exception ex) {
                    log.append("Video info failed: ").append(ex).append('\n');
                    meta = new VideoMetaRequest.Meta();
                }
                final VideoMetaRequest.Meta m = meta;

                List<Thumb> thumbs = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                String[][] sources = {
                        {"Main", "maxresdefault.jpg|sddefault.jpg|hq720.jpg|hqdefault.jpg"},
                        {"Frame 1", "maxres1.jpg|hq1.jpg"},
                        {"Frame 2", "maxres2.jpg|hq2.jpg"},
                        {"Frame 3", "maxres3.jpg|hq3.jpg"},
                };
                for (String[] src : sources) {
                    for (String file : src[1].split("\\|")) {
                        String url = "https://i.ytimg.com/vi/" + videoId + "/" + file;
                        try {
                            byte[] data = get(url);
                            if (data.length < MIN_IMAGE_BYTES) {
                                log.append("SKIP too small ").append(url).append(" (").append(data.length).append(" bytes)\n");
                                continue;
                            }
                            BitmapFactory.Options bounds = new BitmapFactory.Options();
                            bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                            if (bounds.outWidth <= 0) {
                                log.append("SKIP not an image ").append(url).append('\n');
                                continue;
                            }
                            String hash = sha1(data);
                            if (!seen.add(hash)) {
                                log.append("SKIP duplicate ").append(url).append('\n');
                                break;
                            }
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inSampleSize = Math.max(1, bounds.outWidth / 640);
                            Bitmap preview = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                            thumbs.add(new Thumb(src[0], data, preview, bounds.outWidth, bounds.outHeight));
                            log.append("OK ").append(url).append(" (").append(data.length).append(" bytes)\n");
                            break;
                        } catch (Exception ex) {
                            log.append("FAIL ").append(url).append(": ").append(ex.getMessage()).append('\n');
                        }
                    }
                }
                if (thumbs.isEmpty()) {
                    fail(log, null);
                    return;
                }
                Utils.runOnMainThread(() -> showDialog(m, videoId, thumbs, log));
            } catch (Exception ex) {
                fail(log, ex);
            }
        });
    }

    private static void showDialog(VideoMetaRequest.Meta meta, String videoId, List<Thumb> thumbs, StringBuilder log) {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            fail(log.append("No activity for dialog\n"), null);
            return;
        }
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, 0);
        for (Thumb t : thumbs) {
            ImageView img = new ImageView(activity);
            img.setAdjustViewBounds(true);
            img.setImageBitmap(t.preview);
            CheckBox box = new CheckBox(activity);
            box.setChecked(true);
            box.setText(t.label + "  " + t.width + "x" + t.height + "  " + (t.data.length / 1024) + " KB");
            box.setOnCheckedChangeListener((b, checked) -> t.selected = checked);
            img.setOnClickListener(v -> box.toggle());
            list.addView(img, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            list.addView(box);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        String title = meta.title.isEmpty() ? "Thumbnails" : meta.title;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Download selected", (d, w) -> save(meta, videoId, thumbs, false))
                .setNeutralButton("Download all", (d, w) -> save(meta, videoId, thumbs, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void save(VideoMetaRequest.Meta meta, String videoId, List<Thumb> thumbs, boolean all) {
        Utils.runOnBackgroundThread(() -> {
            StringBuilder log = new StringBuilder("videoId=" + videoId + "\n");
            try {
                String channel = FileSaver.sanitize(meta.author.isEmpty() ? "Unknown channel" : meta.author);
                String title = FileSaver.sanitize(meta.title.isEmpty() ? videoId : meta.title);
                String baseName = title + "_" + formatDate(meta.date);
                int saved = 0, number = 0;
                String where = "";
                for (Thumb t : thumbs) {
                    number++;
                    if (!all && !t.selected) continue;
                    String name = baseName + (number == 1 ? "" : "_" + number) + ".jpg";
                    try {
                        where = FileSaver.save(channel, name, "image/jpeg", t.data);
                        saved++;
                    } catch (Exception ex) {
                        log.append("SAVE FAIL ").append(name).append(": ").append(ex).append('\n');
                    }
                }
                if (saved > 0 && log.indexOf("SAVE FAIL") < 0) {
                    final String msg = saved + " thumbnail(s) saved to " + where.substring(0, where.lastIndexOf('/'));
                    Utils.runOnMainThread(() -> Utils.showToastLong(msg));
                    DownloadNotifier.post("Thumbnails saved", msg, false);
                } else {
                    fail(log.append("Saved: ").append(saved).append('\n'), null);
                }
            } catch (Exception ex) {
                fail(log, ex);
            }
        });
    }

    private static void fail(StringBuilder log, Exception ex) {
        if (ex != null) log.append("ERROR: ").append(ex).append('\n');
        Logger.printInfo(() -> "Thumbnail download failed:\n" + log);
        DownloadNotifier.post("Thumbnail download failed", log.toString(), true);
        Utils.runOnMainThread(() -> Utils.showToastLong("Thumbnail download failed. See notification"));
    }

    private static String formatDate(String isoDate) {
        try {
            if (isoDate.length() == 10) {
                return isoDate.substring(2, 4) + isoDate.substring(5, 7) + isoDate.substring(8, 10);
            }
        } catch (Exception ignored) {
        }
        return new SimpleDateFormat("yyMMdd", Locale.US).format(new Date());
    }

    private static String sha1(byte[] data) throws Exception {
        return Arrays.toString(MessageDigest.getInstance("SHA-1").digest(data));
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
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
