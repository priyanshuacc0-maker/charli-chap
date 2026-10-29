package com.charlichaap.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.charlichaap.app.core.CharlieServer;
import com.charlichaap.app.core.Store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Keeps the order desk alive while the phone is in a pocket or the screen is off.
 *
 * Android will otherwise freeze or kill a background app within minutes, so the
 * server runs inside a foreground service with a persistent notification and a
 * partial wake lock.
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.charlichaap.app.START";
    public static final String ACTION_STOP = "com.charlichaap.app.STOP";
    public static final int PORT = 3000;

    public static final String PREFS = "charlichaap";
    public static final String KEY_PASSWORD = "admin_password";

    private static final String TAG = "CharlieChaap";
    private static final String CHANNEL_ID = "order_desk";
    private static final int NOTIFICATION_ID = 1001;

    /** Read by the UI so it can show the address without binding to the service. */
    public static volatile boolean running = false;
    public static volatile String lastError = null;

    private CharlieServer server;
    private PowerManager.WakeLock wakeLock;
    private Store store;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting the order desk..."));

        if (running) {
            updateNotification("Order desk running on port " + PORT);
            return START_STICKY;
        }

        try {
            String password = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASSWORD, "");
            if (password == null || password.length() < 12) {
                lastError = "Set a staff password of at least 12 characters first.";
                updateNotification(lastError);
                stopSelf();
                return START_NOT_STICKY;
            }

            ensureWebAssets();

            File dataDir = new File(getFilesDir(), "data");
            File menuFile = new File(getFilesDir(), "web/menu.json");
            File indexFile = new File(getFilesDir(), "web/index.html");

            store = new Store(dataDir, menuFile, password);
            server = new CharlieServer(store, indexFile, PORT, message -> Log.i(TAG, message));
            server.start();

            acquireWakeLock();
            running = true;
            lastError = null;
            updateNotification("Order desk running on port " + PORT);
            Log.i(TAG, "Order desk started");
        } catch (Exception error) {
            lastError = "Could not start the order desk: " + error.getMessage();
            Log.e(TAG, "Start failed", error);
            running = false;
            updateNotification(lastError);
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    // ------------------------------------------------------------------ assets

    private void ensureWebAssets() throws IOException {
        File web = new File(getFilesDir(), "web");
        if (!web.exists() && !web.mkdirs()) throw new IOException("Cannot create the web folder");

        int versionCode = 1;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionCode = Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean current = prefs.getInt("assets_version", -1) == versionCode
                && new File(web, "index.html").exists()
                && new File(web, "customer.html").exists()
                && new File(web, "menu.json").exists();
        if (current) return;

        copyAsset("index.html", new File(web, "index.html"));
        copyAsset("customer.html", new File(web, "customer.html"));
        copyAsset("menu.json", new File(web, "menu.json"));
        prefs.edit().putInt("assets_version", versionCode).apply();
    }

    private void copyAsset(String name, File target) throws IOException {
        InputStream in = getAssets().open(name);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ power

    private void acquireWakeLock() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager == null) return;
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CharlieChaap::OrderDesk");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception error) {
            Log.w(TAG, "Wake lock unavailable: " + error.getMessage());
        }
    }

    // ------------------------------------------------------------------ notify

    private Notification buildNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pending)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);

        return builder.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private void shutdown() {
        running = false;
        try {
            if (server != null) server.stop();
        } catch (Exception ignored) {
        }
        server = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        Log.i(TAG, "Order desk stopped");
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
