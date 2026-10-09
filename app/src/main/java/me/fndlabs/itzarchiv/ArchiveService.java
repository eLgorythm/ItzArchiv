package me.fndlabs.itzarchiv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArchiveService extends Service {

    public static final String ACTION_EXTRACT = "extract";
    public static final String ACTION_COMPRESS_FILES = "compress_files";
    public static final String ACTION_COMPRESS_FOLDER = "compress_folder";
    public static final String ACTION_CANCEL = "cancel";

    public static final String EXTRA_ACTION = "action";
    public static final String EXTRA_ARCHIVE_URI = "archive_uri";
    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_OUTPUT_URI = "output_uri";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_FILE_URIS = "file_uris";
    public static final String EXTRA_INCLUDE_HIDDEN = "include_hidden";

    private static final String CHANNEL_ID = "itzarchiv_work";
    private static final int NOTIF_ID = 1001;

    public static class Snapshot {
        public String progressLine = "";
        public String eventLine = "";
        public int pct = -1;
        public int done = 0;
        public int total = -1;
        public boolean running = false;
        public String actionLabel = "";
    }

    public interface Listener {
        void onUpdate(Snapshot snapshot);
    }

    public static final Snapshot snapshot = new Snapshot();
    public static Listener listener;
    private static volatile boolean cancelled = false;

    public static void requestCancel() {
        requestCancel(null);
    }

    public static void requestCancel(android.content.Context ctx) {
        cancelled = true;
        synchronized (snapshot) {
            snapshot.eventLine = ctx != null ? ctx.getString(R.string.cancelling_message) : "Cancelling...";
        }
        notifyListener();
    }

    private static void notifyListener() {
        Listener l = listener;
        if (l != null) {
            Snapshot copy;
            synchronized (snapshot) {
                copy = copyOf(snapshot);
            }
            l.onUpdate(copy);
        }
    }

    private static Snapshot copyOf(Snapshot s) {
        Snapshot c = new Snapshot();
        c.progressLine = s.progressLine;
        c.eventLine = s.eventLine;
        c.pct = s.pct;
        c.done = s.done;
        c.total = s.total;
        c.running = s.running;
        c.actionLabel = s.actionLabel;
        return c;
    }

    public static Snapshot getSnapshot() {
        synchronized (snapshot) { return copyOf(snapshot); }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private long lastNotifUi = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notification_channel_description));
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Snapshot s = getSnapshot();
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancelIntent = new Intent(this, ArchiveService.class);
        cancelIntent.putExtra(EXTRA_ACTION, ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(s.running)
                .setOnlyAlertOnce(true);
        if (s.total > 0 && s.pct >= 0) {
            b.setProgress(100, s.pct, false);
        } else if (s.running) {
            b.setProgress(0, 0, true);
        }
        if (s.running) {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), cancelPi);
        }
        return b.build();
    }

    private void updateNotification(String title) {
        Snapshot s = getSnapshot();
        String text;
        if (s.total > 0) {
            text = getString(R.string.notification_progress_known, s.done, s.total, s.pct);
        } else if (s.done > 0) {
            text = getString(R.string.notification_progress_unknown, s.done);
        } else {
            text = s.eventLine;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }

    private void startForegroundNow() {
        Notification n = buildNotification(getString(R.string.notification_working), getString(R.string.notification_preparing));
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ItzArchiv:work");
                wakeLock.setReferenceCounted(false);
                // batasi maksimal 6 jam biar gak nyangkut selamanya kalau ada yang macet
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getStringExtra(EXTRA_ACTION);
        if (ACTION_CANCEL.equals(action)) {
            requestCancel(this);
            updateNotification(getString(R.string.notification_cancelling));
            return START_NOT_STICKY;
        }
        if (snapshot.running) {
            // lagi jalan, jangan numpuk dua kerjaan
            synchronized (snapshot) { snapshot.eventLine = getString(R.string.busy_message); }
            notifyListener();
            return START_NOT_STICKY;
        }
        if (action == null) return START_NOT_STICKY;

        cancelled = false;
        String label = getString(ACTION_EXTRACT.equals(action) ? R.string.extract_label : R.string.compress_label);
        synchronized (snapshot) {
            snapshot.running = true;
            snapshot.pct = -1;
            snapshot.done = 0;
            snapshot.total = -1;
            snapshot.progressLine = "";
            snapshot.actionLabel = label;
            snapshot.eventLine = getString(R.string.starting_label, label.toLowerCase(java.util.Locale.ROOT));
        }
        startForegroundNow();
        acquireWakeLock();
        notifyListener();

        executor.execute(() -> runWork(intent, action, label));
        return START_NOT_STICKY;
    }

    private ArchiveEngine.Callback makeCallback(String label) {
        return new ArchiveEngine.Callback() {
            @Override public void onLog(String msg) {
                synchronized (snapshot) { snapshot.eventLine = msg; }
                notifyListener();
                updateNotification(getString(R.string.notification_title, label));
            }
            @Override public void onProgress(String current, int done, int total) {
                synchronized (snapshot) {
                    snapshot.done = done;
                    snapshot.total = total;
                    if (total > 0) {
                        snapshot.pct = (int) Math.round(done * 100.0 / total);
                        snapshot.progressLine = getString(R.string.progress_line_known, label, done, total, snapshot.pct, shortName(current));
                    } else {
                        snapshot.pct = -1;
                        snapshot.progressLine = getString(R.string.progress_line_unknown, label, done, shortName(current));
                    }
                }
                long now = System.currentTimeMillis();
                if (total <= 50 || done == total || now - lastNotifUi > 500) {
                    lastNotifUi = now;
                    notifyListener();
                    updateNotification(getString(R.string.notification_title, label));
                }
            }
            @Override public boolean isCancelled() { return cancelled; }
        };
    }

    private static String shortName(String path) {
        if (path == null || path.isEmpty()) return "-";
        return path.length() > 70 ? "..." + path.substring(path.length() - 67) : path;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Aplikasi di-swipe dari Recents BUKAN perintah berhenti.
        // Foreground service tetap jalan dan notifikasinya tetap jadi jalur
        // buat mantau progres + Batalin. Yang bisa membunuh total cuma
        // Force stop dari Pengaturan — itu di luar kuasa aplikasi mana pun.
        if (snapshot.running) updateNotification(getString(R.string.notification_title, snapshot.actionLabel));
        super.onTaskRemoved(rootIntent);
    }

    private void runWork(Intent intent, String action, String label) {
        boolean success = false;
        Uri outputUri = null;
        try {
            outputUri = uriExtra(intent, EXTRA_OUTPUT_URI);
            if (ACTION_EXTRACT.equals(action)) {
                Uri archive = uriExtra(intent, EXTRA_ARCHIVE_URI);
                Uri tree = uriExtra(intent, EXTRA_TREE_URI);
                String password = intent.getStringExtra(EXTRA_PASSWORD);
                ArchiveEngine.extract(this, archive, tree, password, makeCallback(label));
            } else if (ACTION_COMPRESS_FOLDER.equals(action)) {
                Uri tree = uriExtra(intent, EXTRA_TREE_URI);
                String format = intent.getStringExtra(EXTRA_FORMAT);
                boolean includeHidden = intent.getBooleanExtra(EXTRA_INCLUDE_HIDDEN, false);
                String compressPassword = intent.getStringExtra(EXTRA_PASSWORD);
                ArchiveEngine.compressFolder(this, tree, outputUri, format, makeCallback(label), includeHidden, compressPassword != null ? compressPassword : "");
            } else if (ACTION_COMPRESS_FILES.equals(action)) {
                String format = intent.getStringExtra(EXTRA_FORMAT);
                ArrayList<String> strs = intent.getStringArrayListExtra(EXTRA_FILE_URIS);
                List<Uri> uris = new ArrayList<>();
                if (strs != null) for (String s : strs) uris.add(Uri.parse(s));
                String compressPassword2 = intent.getStringExtra(EXTRA_PASSWORD);
                ArchiveEngine.compress(this, uris, outputUri, format, makeCallback(label), compressPassword2 != null ? compressPassword2 : "");
            }
            success = true;
            synchronized (snapshot) {
                snapshot.eventLine = ACTION_EXTRACT.equals(action) ? getString(R.string.success_extract) : getString(R.string.success_compress);
                snapshot.progressLine = "";
                snapshot.pct = 100;
                if (snapshot.total > 0) snapshot.done = snapshot.total;
            }
        } catch (Throwable e) {
            synchronized (snapshot) {
                if (cancelled) {
                    snapshot.eventLine = getString(R.string.cancelled_result);
                } else {
                    snapshot.eventLine = getString(R.string.failed_message, e.getMessage() != null ? e.getMessage() : e.toString());
                }
                snapshot.progressLine = "";
            }
        } finally {
            // Kompres yang gagal/dibatalkan: hapus arsip setengah jadi biar gak dikira bagus.
            // Ekstrak gak dihapus — file yang sudah keluar itu utuh per file.
            if (!success && outputUri != null && !ACTION_EXTRACT.equals(action)) {
                try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
                synchronized (snapshot) {
                    if (cancelled) {
                        snapshot.eventLine = getString(R.string.cancelled_archive_deleted);
                    }
                }
            }
            synchronized (snapshot) { snapshot.running = false; }
            notifyListener();
            // notifikasi final (gak ongoing) biar user tahu hasil walau lagi di aplikasi lain
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Snapshot s = getSnapshot();
            if (nm != null) {
                Notification finalNotif = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(success ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                        .setContentTitle(success ? getString(R.string.notification_finished_success) : getString(R.string.notification_finished))
                        .setContentText(s.eventLine)
                        .setAutoCancel(true)
                        .build();
                nm.notify(NOTIF_ID, finalNotif);
            }
            releaseWakeLock();
            stopForeground(true);
            // notifikasi final di atas bakal kehapus kalau stopForeground(true) nge-remove — kirim ulang setelah stop
            if (nm != null) {
                Snapshot fs = getSnapshot();
                Notification finalNotif = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(success ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                        .setContentTitle(success ? getString(R.string.notification_finished_success) : getString(R.string.notification_finished))
                        .setContentText(fs.eventLine)
                        .setContentIntent(PendingIntent.getActivity(this, 0,
                                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                        .setAutoCancel(true)
                        .build();
                nm.notify(NOTIF_ID + 1, finalNotif);
            }
            stopSelf();
        }
    }

    private static Uri uriExtra(Intent intent, String key) {
        String s = intent.getStringExtra(key);
        return s != null ? Uri.parse(s) : null;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        releaseWakeLock();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
