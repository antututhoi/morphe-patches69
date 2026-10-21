/*
 * "Download thumbnails" item in the 3-dot menu. Saves every thumbnail of the video in the best
 * available quality to Download/<channel name>/<title>_<yymmdd>[_n].jpg
 */
package app.morphe.extension.youtube.download;

import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.VideoMetaRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ThumbnailDownloader {

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
            download(id);
        }, index);
    }

    public static void download(String videoId) {
        Utils.showToastShort("Downloading thumbnails…");
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
                String channel = FileSaver.sanitize(meta.author.isEmpty() ? "Unknown channel" : meta.author);
                String title = FileSaver.sanitize(meta.title.isEmpty() ? videoId : meta.title);
                String date = formatDate(meta.date);
                String baseName = title + "_" + date;

                // Main thumbnail: first quality that exists. Then the 3 auto-generated frames.
                List<String[]> jobs = new ArrayList<>();
                jobs.add(new String[]{"", "maxresdefault.jpg|sddefault.jpg|hq720.jpg|hqdefault.jpg"});
                jobs.add(new String[]{"_2", "maxres1.jpg|hq1.jpg"});
                jobs.add(new String[]{"_3", "maxres2.jpg|hq2.jpg"});
                jobs.add(new String[]{"_4", "maxres3.jpg|hq3.jpg"});

                int saved = 0;
                String where = "";
                for (String[] job : jobs) {
                    byte[] data = null;
                    for (String file : job[1].split("\\|")) {
                        String url = "https://i.ytimg.com/vi/" + videoId + "/" + file;
                        try {
                            data = get(url);
                            log.append("OK ").append(url).append(" (").append(data.length).append(" bytes)\n");
                            break;
                        } catch (Exception ex) {
                            log.append("FAIL ").append(url).append(": ").append(ex.getMessage()).append('\n');
                        }
                    }
                    if (data == null) continue;
                    try {
                        where = FileSaver.save(channel, baseName + job[0] + ".jpg", "image/jpeg", data);
                        saved++;
                    } catch (Exception ex) {
                        log.append("SAVE FAIL: ").append(ex).append('\n');
                    }
                }
                if (saved > 0) {
                    final String msg = saved + " thumbnail(s) saved to " + where.substring(0, where.lastIndexOf('/'));
                    Utils.runOnMainThread(() -> Utils.showToastLong(msg));
                    DownloadNotifier.post("Thumbnails saved", msg, false);
                } else {
                    fail(log, null);
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
