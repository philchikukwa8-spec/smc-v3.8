package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Manage;
import com.smcscanner.engine.SmcConfig;

import java.util.List;

/** Keeps every trade you logged up to date and notifies you when you should act. */
public final class TradeTracker {
    private TradeTracker() {}

    /** Returns the number of advice notifications sent. */
    public static int update(Context ctx, DerivClient client) {
        final Context c = ctx.getApplicationContext();
        List<Manage.Trade> trades = Store.loadTrades(c);
        boolean any = false;
        for (Manage.Trade t : trades) if (t.status.equals("open")) any = true;
        if (!any) return 0;
        int sent = 0;
        long now = System.currentTimeMillis() / 1000;
        for (Manage.Trade t : trades) {
            if (!t.status.equals("open")) continue;
            try {
                Candle[] raw = client.get(t.symbol, t.entryGran, 200);
                for (Manage.Event e : Manage.update(t, raw, now, new SmcConfig())) {
                    Notifier.alert(c, "MANAGE: " + e.title, e.body, e.body);
                    Store.addAlert(c, "manage", "MANAGE: " + e.title, e.body, e.title + "\n\n" + e.body);
                    sent++;
                }
            } catch (Exception ignored) { /* no data this time: try again next scan */ }
        }
        Store.mergeTrades(c, trades);
        return sent;
    }
}
