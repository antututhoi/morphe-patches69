package app.morphe.extension.youtube.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import app.morphe.extension.shared.Utils;

/** Status bar notifications for finished/failed downloads, with the error text so it can be reported. */
public final class DownloadNotifier {

    private static final String CHANNEL_ID = "morphe_69_downloads";
    private static int nextId = 6900;

    private DownloadNotifier() {}

    public static void post(String title, String text, boolean error) {
        try {
            Context c = Utils.getContext();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "69 downloads", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(c, CHANNEL_ID);
            } else {
                b = new Notification.Builder(c);
            }
            b.setSmallIcon(error ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(firstLine(text))
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            nm.notify(nextId++, b.build());
        } catch (Exception ignored) {
            // Notifications may be blocked by the user; the toast still shows.
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
