package com.smcscanner.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts scanning after the phone reboots, if you had it switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Store.scanningWanted(context)) {
            try { ScannerService.start(context); } catch (Exception ignored) { }
        }
    }
}
