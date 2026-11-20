package com.smcscanner.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import com.smcscanner.R;

/** Phone notifications. */
public final class Notifier {
    private Notifier() {}

    public static final String ALERTS = "alerts";
    public static final String SERVICE = "service";
    private static int nextId = 1000;

    public static void createChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel a = new NotificationChannel(ALERTS, "Trade alerts", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Setup validated, price in zone, entry, cancelled");
        nm.createNotificationChannel(a);
        NotificationChannel s = new NotificationChannel(SERVICE, "Scanner running", NotificationManager.IMPORTANCE_LOW);
        s.setDescription("Shows that the scanner is running in the background");
        nm.createNotificationChannel(s);
    }

    /**
     * Fib alerts have their own channel. On Android the sound belongs to the channel and cannot be changed once it exists,
     * so each chosen sound gets its own channel id (and the older Fib channels are removed).
     */
    public static String fibChannel(Context c) {
        String uri = Store.fibSound(c);
        String id = "fib_" + Integer.toHexString(uri.hashCode());
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(id) == null) {
            for (NotificationChannel ch : nm.getNotificationChannels()) if (ch.getId().startsWith("fib_")) nm.deleteNotificationChannel(ch.getId());
            NotificationChannel f = new NotificationChannel(id, "Fib pullback alerts", NotificationManager.IMPORTANCE_HIGH);
            f.setDescription("Fib pullback setups: order ready and entry level reached");
            f.enableVibration(true);
            if (!uri.isEmpty()) {
                f.setSound(Uri.parse(uri), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            nm.createNotificationChannel(f);
        }
        return id;
    }

    /** a Fib setup alert: its own channel, so its own sound */
    public static boolean alertFib(Context c, String title, String body, String fullText) {
        if (!allowed(c)) return false;
        if (Store.pauseReason(c) != null) return true;
        createChannels(c);
        int id = nextId++;
        Notification n = new Notification.Builder(c, fibChannel(c))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(fullText))
            .setContentIntent(openFib(c, id))
            .setAutoCancel(true)
            .build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
        return true;
    }

    public static final String EXTRA_OPEN_FIB = "openFib";

    private static PendingIntent openFib(Context c, int id) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EXTRA_OPEN_FIB, true);
        return PendingIntent.getActivity(c, id + 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static final String EXTRA_SYMBOL = "symbol";
    public static final String EXTRA_OPEN_CHART = "openChart";

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** opens the live chart on this market (symbol may be null) */
    private static PendingIntent openChart(Context c, String symbol, int id) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EXTRA_OPEN_CHART, true);
        if (symbol != null) i.putExtra(EXTRA_SYMBOL, symbol);
        return PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent snooze(Context c, int id) {
        Intent i = new Intent(c, SnoozeReceiver.class);
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** which market a title is about ("Volatility 75 ..." gives R_75), or null */
    static String symbolFor(String title) {
        String best = null; int len = 0;
        for (com.smcscanner.engine.Scan.Market m : com.smcscanner.engine.Scan.Config.defaults().markets) {
            if (title.contains(m.label) && m.label.length() > len) { best = m.symbol; len = m.label.length(); }
        }
        return best;
    }

    /** Shows a trade alert. Returns false if the phone would not let us (permission off). */
    public static boolean alert(Context c, String title, String body, String fullText) {
        return alert(c, title, body, fullText, false);
    }

    /**
     * respectSnooze: new-setup alerts are held back while snoozed, in quiet hours or after the daily loss limit (they are still saved in the Alerts tab).
     * Messages about a trade you are already in are never snoozed.
     */
    public static boolean alert(Context c, String title, String body, String fullText, boolean respectSnooze) {
        if (!allowed(c)) return false;
        if (respectSnooze && Store.pauseReason(c) != null) return true;
        createChannels(c);
        int id = nextId++;
        Notification n = new Notification.Builder(c, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(fullText))
            .setContentIntent(openApp(c))
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notify), "Open chart", openChart(c, symbolFor(title), id)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notify), "Snooze 1h", snooze(c, id)).build())
            .setAutoCancel(true)
            .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id, n);
        return true;
    }

    public static Notification serviceNotification(Context c, String text) {
        createChannels(c);
        return new Notification.Builder(c, SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("SMC Scanner is running")
            .setContentText(text)
            .setContentIntent(openApp(c))
            .setOngoing(true)
            .build();
    }
}
