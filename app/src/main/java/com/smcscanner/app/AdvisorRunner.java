package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Advisor;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.SignalLog;
import com.smcscanner.engine.Smc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs the top-down Advisor (1H > 30m > 15m) on every market, announces new plans and records them for the journal. */
public final class AdvisorRunner {
    private AdvisorRunner() {}

    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public String symbol, label;
        public Advisor.Plan plan;
        public long at;
    }

    private static final Object LOCK = new Object();

    public static ArrayList<Item> load(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "advisor.bin");
            if (!f.exists()) return new ArrayList<>();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                @SuppressWarnings("unchecked") ArrayList<Item> l = (ArrayList<Item>) in.readObject();
                return l;
            } catch (Exception e) { return new ArrayList<>(); }
        }
    }

    private static void save(Context c, List<Item> items) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "advisor.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(new ArrayList<>(items)); } catch (Exception ignored) { }
        }
    }

    /** returns how many notifications were sent */
    public static int run(Context c, DerivClient client, long now) {
        Scan.Config cfg = Store.config(c);
        List<Item> items = new ArrayList<>();
        Set<String> seen = Store.advisorSeen(c);
        List<SignalLog.Record> recs = null;
        Set<String> have = new HashSet<>();
        int sent = 0;
        boolean recsChanged = false;
        for (Scan.Market m : cfg.markets) {
            try {
                Candle[] h1 = Smc.closedOnly(client.get(m.symbol, 3600, 300), 3600, now);
                Candle[] m30 = Smc.closedOnly(client.get(m.symbol, 1800, 300), 1800, now);
                Candle[] m15 = Smc.closedOnly(client.get(m.symbol, 900, 300), 900, now);
                Advisor.Plan p = Advisor.analyse(h1, m30, m15);
                Item it = new Item();
                it.symbol = m.symbol; it.label = m.label; it.plan = p; it.at = now;
                items.add(it);
                boolean notify = p.state.equals(Advisor.TRIGGERED) || (p.state.equals(Advisor.READY) && Store.advisorHeadsUp(c));
                if (!notify) continue;
                String key = m.symbol + "|" + p.id;
                if (seen.contains(key)) continue;
                String title = m.label + ": " + p.headline;
                String body = p.state.equals(Advisor.TRIGGERED)
                    ? "Entry " + com.smcscanner.engine.Fmt.f(p.entry) + " | stop " + com.smcscanner.engine.Fmt.f(p.stop) + " | target " + com.smcscanner.engine.Fmt.f(p.target)
                    : "Zone " + com.smcscanner.engine.Fmt.f(p.zoneLow) + " - " + com.smcscanner.engine.Fmt.f(p.zoneHigh) + " | wait for the 15m close";
                StringBuilder full = new StringBuilder(title + "\n\n");
                for (String ch : p.checks) full.append(ch).append("\n");
                full.append("\n").append(p.text).append("\n\nConfluences ").append(p.confluence).append("/").append(p.maxConfluence)
                    .append(" (grade ").append(p.grade).append("; not a win probability)");
                boolean shown = Notifier.alert(c, title, body, full.toString(), true);
                Store.addAlert(c, "advisor", title, body, full.toString());
                seen.add(key);
                sent++;
                if (p.state.equals(Advisor.TRIGGERED) && p.setup != null) {
                    if (recs == null) { recs = Store.loadSignals(c); for (SignalLog.Record r : recs) have.add(r.id); }
                    String rid = key;
                    if (!have.contains(rid)) {
                        SignalLog.Record r = SignalLog.create(rid, now, m.symbol, m.label, "1H > 30m > 15m", "1H", "15m", p.setup, null, false, -1, Store.riskMoney(c));
                        r.grade = p.grade; r.entryStyle = "Advisor"; r.qualityMet = p.confluence; r.qualityOf = p.maxConfluence;
                        recs.add(r);
                        recsChanged = true;
                    }
                }
            } catch (Exception e) { /* try this market again next scan */ }
        }
        if (recsChanged) Store.saveSignals(c, recs);
        if (!items.isEmpty()) save(c, items);
        Store.setAdvisorSeen(c, seen);
        return sent;
    }
}
