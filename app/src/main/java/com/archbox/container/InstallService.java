package com.archbox.container;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs the download / extraction as a foreground service, so it keeps going when the app is
 * minimised or the screen is off. The UI (OnboardingActivity) only observes {@link #S}.
 * Plain Android classes only: no Termux code is loaded in this path.
 */
public class InstallService extends Service {
    static final String ACTION_START = "com.archbox.container.INSTALL_START";
    static final String ACTION_CANCEL = "com.archbox.container.INSTALL_CANCEL";
    private static final String CHANNEL = "install";
    private static final int ID = 1, ID_DONE = 2;

    /** Shared progress state. Written by the worker thread, read by the UI. */
    static final class State {
        volatile boolean running, done;
        volatile String error;
        volatile String phase = "";
        volatile long cur, total;
        private final StringBuilder log = new StringBuilder();

        synchronized void begin() {
            running = true; done = false; error = null; phase = "Starting..."; cur = 0; total = -1;
            log.setLength(0);
        }
        synchronized void reset() { running = false; done = false; error = null; log.setLength(0); }
        synchronized void append(String s) {
            log.append(s);
            if (log.length() > 24000) log.delete(0, log.length() - 16000);
        }
        synchronized String log() { return log.toString(); }
    }
    static final State S = new State();

    interface Watcher { void onChange(); }
    private static final List<Watcher> WATCHERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static void watch(Watcher w) { if (!WATCHERS.contains(w)) WATCHERS.add(w); }
    static void unwatch(Watcher w) { WATCHERS.remove(w); }
    private static void changed() { MAIN.post(() -> { for (Watcher w : WATCHERS) w.onChange(); }); }

    private static volatile Bootstrap current;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private long lastNotify;

    /** Called from the UI: marks the install as running right away, then starts the service. */
    static void start(Context c, Bootstrap.Request r) {
        S.begin();
        Intent i = new Intent(c, InstallService.class).setAction(ACTION_START)
                .putExtra("kind", r.kind.name()).putExtra("keep", r.keep);
        if (r.file != null) i.putExtra("file", r.file.toString());
        if (r.export != null) i.putExtra("export", r.export.toString());
        c.startForegroundService(i);
    }

    static void cancel(Context c) {
        c.startService(new Intent(c, InstallService.class).setAction(ACTION_CANCEL));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Installation", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && ACTION_CANCEL.equals(in.getAction())) {
            Bootstrap b = current;
            if (b != null) b.cancel();
            return START_NOT_STICKY;
        }
        // Must happen within 5 s of startForegroundService(), whatever else follows.
        Notification n = notification("Archbox", "Starting...", 0, -1, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);

        if (in == null || !ACTION_START.equals(in.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;   // already installing

        final Bootstrap.Request req = new Bootstrap.Request(
                Bootstrap.Request.Kind.valueOf(in.getStringExtra("kind")),
                in.hasExtra("file") ? Uri.parse(in.getStringExtra("file")) : null,
                in.hasExtra("export") ? Uri.parse(in.getStringExtra("export")) : null,
                in.getBooleanExtra("keep", true));
        S.begin();
        changed();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "archbox:install");
        wakeLock.acquire(3 * 60 * 60 * 1000L);

        final Bootstrap bs = new Bootstrap(getFilesDir());
        current = bs;
        worker = new Thread(() -> {
            String failure = null;
            try {
                bs.run(req, getContentResolver(), new Bootstrap.Listener() {
                    @Override public void log(String s) { S.append(s); changed(); }
                    @Override public void progress(String phase, long done, long total) {
                        S.phase = phase; S.cur = done; S.total = total;
                        long now = System.currentTimeMillis();
                        if (now - lastNotify > 1000) {
                            lastNotify = now;
                            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID,
                                    notification("Archbox: " + phase, mib(done) + (total > 0 ? " / " + mib(total) : ""), done, total, true));
                        }
                        changed();
                    }
                });
                S.done = true;
            } catch (Throwable t) {
                failure = t instanceof Bootstrap.Cancelled ? "Cancelled" : String.valueOf(t);
                S.error = failure;
                S.append("\n" + failure + "\n");
            } finally {
                current = null;
                S.running = false;
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                changed();
                stopForeground(true);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_DONE,
                        notification(failure == null ? "Archbox is ready" : "Install failed",
                                failure == null ? "Tap to open the terminal" : failure, 0, -1, false));
                stopSelf();
            }
        }, "archbox-install");
        worker.start();
        return START_NOT_STICKY;
    }

    static String mib(long b) {
        return b >= (1L << 30) ? String.format("%.1f GiB", b / (double) (1L << 30)) : (b >> 20) + " MiB";
    }

    private Notification notification(String title, String text, long cur, long total, boolean ongoing) {
        Intent open = new Intent(this, ongoing ? OnboardingActivity.class : MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setOngoing(ongoing).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (ongoing) {
            if (total > 0) b.setProgress(1000, (int) (cur * 1000 / total), false);
            else b.setProgress(0, 0, true);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, InstallService.class).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "Cancel", stop).build());
        } else {
            b.setAutoCancel(true);
        }
        return b.build();
    }
}
