package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Lifecycle;
import com.smcscanner.engine.Scan;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** One scan: fetch charts, look for setups, send notifications, save what happened. */
public final class Scanner {
    private Scanner() {}

    private static final Object RUN = new Object();

    /** Returns a one-line summary. Safe to call from any background thread; scans never overlap. */
    public static String runOnce(Context ctx, DerivClient client) {
        synchronized (RUN) {
            Context c = ctx.getApplicationContext();
            Lifecycle.State state = Store.loadState(c);
            long now = System.currentTimeMillis() / 1000;
            java.util.Set<String> entriesBefore = SignalTracker.entryKeys(state);
            Scan.Result r = Scan.scanAll(client, state, Store.config(c), now);
            int sent = 0;
            boolean blocked = false;
            for (Lifecycle.Msg m : r.msgs) {
                boolean shown = Notifier.alert(c, m.title, m.body, m.text, true);
                if (!shown) blocked = true;
                Store.addAlert(c, m.kind, m.title, m.body, m.text);
                m.apply.run(); // only after it was delivered/recorded, so nothing is lost or repeated
                sent++;
            }
            Lifecycle.prune(state, now, 3);
            Store.saveState(c, state);
            try { SignalTracker.update(c, client, state, entriesBefore, now); } catch (Throwable ignored) { }
            int patterns = 0;
            try { patterns = checkPatterns(c, client, now); sent += patterns; } catch (Throwable ignored) { }
            if (Store.modeSmc(c)) { try { sent += MieRunner.run(c, client, now); } catch (Throwable ignored) { } }
            if (Store.modePa(c)) { try { sent += PaRunner.run(c, client, now); } catch (Throwable ignored) { } }
            if (Store.modeFib(c)) { try { sent += FibRunner.run(c, client, now); } catch (Throwable ignored) { } }
            if (Store.legacyAdvisor(c)) { try { sent += AdvisorRunner.run(c, client, now); } catch (Throwable ignored) { } }
            int advice = 0;
            try { advice = TradeTracker.update(c, client); } catch (Throwable ignored) { }
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            String status = "Last scan " + time + ": " + r.ok + " charts read, " + r.failed + " failed, " + sent + " alert" + (sent == 1 ? "" : "s")
                + (advice > 0 ? ", " + advice + " trade update" + (advice == 1 ? "" : "s") : "");
            if (blocked) status += " (notifications are blocked: turn them on in Settings)";
            if (!r.errors.isEmpty()) status += "\n" + r.errors.get(0);
            Store.setLastStatus(c, status);
            return status;
        }
    }

    /** at most every 10 minutes: look for new major chart patterns on the 1H, 4H and 1D charts */
    private static int checkPatterns(Context c, DerivClient client, long now) {
        if (!Store.patternAlerts(c) || now - Store.patternLast(c) < 600) return 0;
        Store.setPatternLast(c, now);
        java.util.Set<String> seen = Store.patternSeen(c);
        java.util.List<com.smcscanner.engine.PatternWatch.Alert> as = com.smcscanner.engine.PatternWatch.check(client, Store.config(c).markets, now, seen);
        for (com.smcscanner.engine.PatternWatch.Alert a : as) {
            Notifier.alert(c, a.title, a.body, a.text, true);
            Store.addAlert(c, "pattern", a.title, a.body, a.text);
        }
        Store.setPatternSeen(c, seen);
        return as.size();
    }
}
