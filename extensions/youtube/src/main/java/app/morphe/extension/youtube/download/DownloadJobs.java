package app.morphe.extension.youtube.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Utils;

/**
 * 69 mod: one place for every download.
 * - the same item can't be downloaded twice at the same time (double taps),
 * - a running download shows a notification with Cancel,
 * - a failed download pops up a notification with Retry.
 */
@SuppressWarnings("unused")
public final class DownloadJobs {

    /** Thrown inside a job when the user tapped Cancel. */
    public static final class CancelledException extends Exception {
        public CancelledException() { super("Cancelled"); }
    }

    public interface Work {
        /** Runs on a background thread. Return the success message, or throw. */
        String run(Job job) throws Exception;
    }

    public static final class Job {
        final String key;
        final String title;
        final Work work;
        final int notificationId;
        volatile boolean cancelled;
        /** 69 mod: picture for the "done" notification. */
        volatile android.graphics.Bitmap thumb;

        public void thumbnail(android.graphics.Bitmap b) {
            if (thumb == null && b != null) thumb = b;
        }

        Job(String key, String title, Work work, int id) {
            this.key = key; this.title = title; this.work = work; this.notificationId = id;
        }

        /** Call between steps; stops the job if Cancel was tapped. */
        public void check() throws CancelledException {
            if (cancelled) throw new CancelledException();
        }

        public void progress(String text) {
            notifyProgress(this, text);
        }
    }

    private static final String CHANNEL_DONE = "morphe_69_downloads";
    private static final String CHANNEL_FAIL = "morphe_69_downloads_failed";
    private static final String CHANNEL_RUN = "morphe_69_downloads_running";
    private static final String ACTION_CANCEL = "app.morphe.69.DOWNLOAD_CANCEL";
    private static final String ACTION_RETRY = "app.morphe.69.DOWNLOAD_RETRY";

    private static final Map<String, Job> running = new HashMap<>();
    /** Failed jobs that can be retried from their notification. */
    private static final Map<String, Job> failed = new HashMap<>();
    private static int nextId = 6950;
    private static boolean receiverRegistered;

    private DownloadJobs() {}

    public static boolean isRunning(String key) {
        synchronized (running) {
            return running.containsKey(key);
        }
    }

    /** Starts the work unless the same key is already downloading. */
    public static void start(String key, String title, Work work) {
        Job job;
        synchronized (running) {
            if (running.containsKey(key)) {
                Utils.showToastShort("Already downloading");
                return;
            }
            job = new Job(key, title, work, nextId++);
            running.put(key, job);
            failed.remove(key);
        }
        ensureReceiver();
        final Job j = job;
        notifyProgress(j, "Starting…");
        Utils.runOnBackgroundThread(() -> {
            try {
                String msg = j.work.run(j);
                cancelNotification(j.notificationId);
                if (msg != null) {
                    Utils.runOnMainThread(() -> Utils.showToastLong(msg));
                    post(j.notificationId, CHANNEL_DONE, j.title + " – done", msg, false, null, j.thumb);
                }
            } catch (CancelledException ex) {
                cancelNotification(j.notificationId);
                Utils.runOnMainThread(() -> Utils.showToastShort("Download cancelled"));
            } catch (Exception ex) {
                synchronized (running) {
                    failed.put(j.key, j);
                }
                String text = String.valueOf(ex.getMessage() == null ? ex : ex.getMessage());
                post(j.notificationId, CHANNEL_FAIL, j.title + " – failed", text, true, j.key, null);
                Utils.runOnMainThread(() -> Utils.showToastLong("Download failed. Tap Retry in the notification"));
            } finally {
                synchronized (running) {
                    running.remove(j.key);
                }
            }
        });
    }

    public static void cancel(String key) {
        synchronized (running) {
            Job j = running.get(key);
            if (j != null) j.cancelled = true;
        }
    }

    private static void retry(String key) {
        Job j;
        synchronized (running) {
            j = failed.remove(key);
        }
        if (j != null) start(j.key, j.title, j.work);
    }

    // ---------------- notifications ----------------

    private static NotificationManager manager(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "69 downloads", NotificationManager.IMPORTANCE_DEFAULT));
            // HIGH so a failure pops up on screen.
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_FAIL, "69 failed downloads", NotificationManager.IMPORTANCE_HIGH));
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_RUN, "69 running downloads", NotificationManager.IMPORTANCE_LOW));
        }
        return nm;
    }

    private static PendingIntent action(Context c, String action, String key, int code) {
        Intent i = new Intent(action).setPackage(c.getPackageName()).putExtra("key", key);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, code, i, flags);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    static void notifyProgress(Job j, String text) {
        try {
            Context c = Utils.getContext();
            NotificationManager nm = manager(c);
            if (nm == null) return;
            Notification.Builder b = builder(c, CHANNEL_RUN)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(j.title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                            action(c, ACTION_CANCEL, j.key, j.notificationId));
            nm.notify(j.notificationId, b.build());
        } catch (Exception ignored) {
            // Notifications may be blocked.
        }
    }

    private static void post(int id, String channel, String title, String text, boolean error, String retryKey,
                             android.graphics.Bitmap thumb) {
        try {
            Context c = Utils.getContext();
            NotificationManager nm = manager(c);
            if (nm == null) return;
            String first = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
            Notification.Builder b = builder(c, channel)
                    .setSmallIcon(error ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(first)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true);
            if (thumb != null) {
                // like Continuum: the picture, with the count and folder under it
                b.setLargeIcon(thumb);
                b.setStyle(new Notification.BigPictureStyle().bigPicture(thumb).setSummaryText(text));
            }
            if (error) {
                b.setPriority(Notification.PRIORITY_HIGH);
                if (retryKey != null) {
                    b.addAction(android.R.drawable.ic_popup_sync, "Retry", action(c, ACTION_RETRY, retryKey, id));
                }
            }
            nm.notify(id, b.build());
        } catch (Exception ignored) {
            // Notifications may be blocked.
        }
    }

    private static void cancelNotification(int id) {
        try {
            NotificationManager nm = manager(Utils.getContext());
            if (nm != null) nm.cancel(id);
        } catch (Exception ignored) {
        }
    }

    private static synchronized void ensureReceiver() {
        if (receiverRegistered) return;
        try {
            Context c = Utils.getContext().getApplicationContext();
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String key = intent.getStringExtra("key");
                    if (key == null) return;
                    if (ACTION_CANCEL.equals(intent.getAction())) cancel(key);
                    if (ACTION_RETRY.equals(intent.getAction())) retry(key);
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_CANCEL);
            f.addAction(ACTION_RETRY);
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                c.registerReceiver(r, f);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }
}
