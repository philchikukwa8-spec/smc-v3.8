package com.smcscanner.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The "Snooze 1h" button on an alert: new-setup alerts are held back for an hour (they still appear in the Alerts tab). */
public class SnoozeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Store.setSnoozeUntil(c, System.currentTimeMillis() + 3600_000L);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancelAll();
    }
}
