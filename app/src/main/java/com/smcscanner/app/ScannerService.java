package com.smcscanner.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps scanning in the background (with a permanent notification, as Android requires). */
public class ScannerService extends Service {
    public static final String ACTION_STOP = "com.smcscanner.STOP";
    public static volatile boolean running = false;

    private Thread worker;
    private Thread calibrator;
    private volatile boolean stop = false;
    private final Object sleepLock = new Object();

    public static void start(Context c) {
        Intent i = new Intent(c, ScannerService.class);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        Store.setScanningWanted(c, false);
        c.stopService(new Intent(c, ScannerService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Store.setScanningWanted(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        Store.setScanningWanted(this, true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, Notifier.serviceNotification(this, Store.lastStatus(this)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, Notifier.serviceNotification(this, Store.lastStatus(this)));
        }
        if (worker == null || !worker.isAlive()) {
            stop = false;
            running = true;
            worker = new Thread(this::loop, "smc-scanner");
            worker.start();
        }
        if (calibrator == null || !calibrator.isAlive()) {
            calibrator = new Thread(this::calibrateLoop, "smc-calibrate");
            calibrator.setDaemon(true);
            calibrator.start();
        }
        return START_STICKY;
    }

    private void loop() {
        DerivClient client = new DerivClient();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smcscanner:scan");
        while (!stop) {
            String status;
            try {
                lock.acquire(3 * 60 * 1000L);
                status = Scanner.runOnce(this, client);
            } catch (Throwable t) {
                status = "Scan error: " + (t.getMessage() != null ? t.getMessage() : t.toString());
                Store.setLastStatus(this, status);
            } finally {
                if (lock.isHeld()) lock.release();
            }
            try {
                ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(1, Notifier.serviceNotification(this, status.split("\n")[0]));
            } catch (Throwable ignored) { }
            synchronized (sleepLock) {
                if (stop) break;
                try { sleepLock.wait(Math.max(20, Store.intervalSec(this)) * 1000L); } catch (InterruptedException e) { break; }
            }
        }
        client.close();
        running = false;
    }

    /** Measures past win rates (for the confidence figure) in the background, then again every few hours if stale. */
    private void calibrateLoop() {
        DerivClient client = new DerivClient();
        while (!stop) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smcscanner:calibrate");
            try {
                lock.acquire(30 * 60 * 1000L);
                Calibrator.refreshStale(this, client, Store.config(this), false);
            } catch (Throwable ignored) {
            } finally {
                if (lock.isHeld()) lock.release();
            }
            synchronized (sleepLock) {
                if (stop) break;
                try { sleepLock.wait(6L * 3600 * 1000); } catch (InterruptedException e) { break; }
            }
        }
        client.close();
    }

    @Override
    public void onDestroy() {
        stop = true;
        synchronized (sleepLock) { sleepLock.notifyAll(); }
        running = false;
        super.onDestroy();
    }
}
