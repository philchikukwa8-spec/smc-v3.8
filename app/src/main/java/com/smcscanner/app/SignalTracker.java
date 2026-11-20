package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Lifecycle;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.SignalLog;
import com.smcscanner.engine.Smc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps the record of every entry alert and works out, from the charts, whether each one hit its target or its stop. */
final class SignalTracker {
    private SignalTracker() {}

    static Set<String> entryKeys(Lifecycle.State st) {
        Set<String> s = new HashSet<>();
        for (Lifecycle.Tracked t : st.tracked.values()) if (t.entry != null) s.add(t.key);
        return s;
    }

    private static long granOf(Scan.Config cfg, String label) {
        for (Scan.Timeframe t : cfg.timeframes) if (t.label.equals(label)) return t.granularity;
        return 0;
    }

    /** e.g. "4H > 1H > 5m > 1m": the chain for this idea, cut at the timeframe the entry came from */
    static String pathFor(Scan.Config cfg, Lifecycle.Tracked t, String finalTf) {
        List<String> chain = new ArrayList<>();
        for (Scan.Pair p : cfg.pairs) {
            if (!p.setup.equals(t.tf)) continue;
            chain.add(p.setup);
            if (p.entry != null) chain.add(p.entry);
            chain.addAll(p.refine);
        }
        if (chain.isEmpty()) { chain.add(t.tf); if (t.ltfTf != null) chain.add(t.ltfTf); }
        int at = chain.indexOf(finalTf);
        if (at >= 0) chain = chain.subList(0, at + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) { if (i > 0) sb.append(" > "); sb.append(chain.get(i)); }
        return sb.toString();
    }

    /** call after a scan. entriesBefore = entryKeys(state) taken BEFORE the scan, so only this scan's new entries are recorded. */
    static void update(Context c, DerivClient client, Lifecycle.State st, Set<String> entriesBefore, long now) {
        List<SignalLog.Record> recs = Store.loadSignals(c);
        Set<String> have = new HashSet<>();
        for (SignalLog.Record r : recs) have.add(r.id);
        Scan.Config cfg = Scan.Config.defaults();
        boolean changed = false;
        for (Lifecycle.Tracked t : st.tracked.values()) {
            if (t.entry == null || entriesBefore.contains(t.key) || have.contains(t.key)) continue;
            String finalTf = t.finalTf != null ? t.finalTf : (t.ltfTf != null ? t.ltfTf : t.tf);
            double risk = t.breakdown != null ? t.breakdown.riskMoney : Store.riskMoney(c);
            recs.add(SignalLog.create(t.key, now, t.market, t.label, pathFor(cfg, t, finalTf), t.tf, finalTf, t.entry, t.breakdown,
                Store.sniper(c), Store.entryMode(c), risk));
            changed = true;
        }
        Map<String, Candle[]> cache = new HashMap<>();
        for (SignalLog.Record r : recs) {
            if (r.finished()) continue;
            long gran = granOf(cfg, r.entryTf);
            if (gran <= 0) continue;
            long from = Math.max(r.alertEpoch, r.lastEpoch);
            int count = (int) Math.min(5000, Math.max(5, (now - from) / gran + 3));
            String key = r.symbol + "|" + gran + "|" + count;
            try {
                Candle[] closed = cache.get(key);
                if (closed == null) { closed = Smc.closedOnly(client.get(r.symbol, gran, count), gran, now); cache.put(key, closed); }
                if (SignalLog.resolve(r, closed, gran)) changed = true;
            } catch (Exception ignored) { /* try again next scan */ }
        }
        int takenBefore = 0, takenAfter = 0;
        for (SignalLog.Record r : recs) if (r.taken) takenBefore++;
        SignalLog.linkTrades(recs, Store.loadTrades(c));
        for (SignalLog.Record r : recs) if (r.taken) takenAfter++;
        if (changed || takenAfter != takenBefore) Store.saveSignals(c, recs);
    }
}
