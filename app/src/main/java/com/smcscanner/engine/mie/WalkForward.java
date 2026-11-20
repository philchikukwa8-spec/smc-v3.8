package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Historical replay of the MIE using only data that was available at each point (closed candles up to that moment).
 * Rows are labelled in-sample / validation / out-of-sample by time (first 60%, next 20%, last 20%), so a result is never
 * reported without saying which part of history it came from.
 */
public final class WalkForward {
    private WalkForward() {}

    public static final class Data {
        public String symbol = "", label = "";
        public Candle[] h4, h1, m30, m15, m5, m1;
    }

    public static final class Report {
        public final List<Row> rows = new ArrayList<>();
        public final Map<String, Kpi> bySample = new TreeMap<>();
        public final List<Kpi> folds = new ArrayList<>();
        public Kpi all;
        public int windows;
        public long from, to;
        public String note = "";
    }

    private static int upTo(Candle[] c, long gran, long asOf) {
        if (c == null) return 0;
        int lo = 0, hi = c.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (c[mid].epoch + gran <= asOf) lo = mid + 1; else hi = mid; }
        return lo;
    }

    private static Candle[] win(Candle[] c, int n, int keep) { return Arrays.copyOfRange(c, Math.max(0, n - keep), n); }

    /** step = analyse every `step` 15m candles. warmup = candles skipped at the start of the 15m series. */
    public static Report run(Data d, String version, int step, int warmup) { return run(d, version, step, warmup, 0); }

    /** tier 0 = 1H cascade, tier 1 = 30m cascade (needs 5m history; 1m is not replayed). Tier 2 needs 1m history and cannot be replayed. */
    public static Report run(Data d, String version, int step, int warmup, int tier) {
        Report rep = new Report();
        if (d.m15 == null || d.m15.length <= warmup + 10) { rep.note = "Not enough 15m history."; rep.all = Kpi.compute(rep.rows); return rep; }
        Set<String> seen = new HashSet<>();
        for (int i = warmup; i < d.m15.length; i += step) {
            long asOf = d.m15[i].epoch + 900;
            Mie.Inputs in = new Mie.Inputs();
            in.symbol = d.symbol; in.label = d.label; in.now = asOf;
            in.m15 = win(d.m15, i + 1, 300);
            in.m30 = win(d.m30, upTo(d.m30, 1800, asOf), 300);
            in.h1 = win(d.h1, upTo(d.h1, 3600, asOf), 300);
            in.h4 = win(d.h4, upTo(d.h4, 14400, asOf), 200);
            int n5 = upTo(d.m5, 300, asOf);
            in.m5 = d.m5 == null || n5 < 60 ? null : win(d.m5, n5, tier > 0 ? 300 : 200);
            int n1 = upTo(d.m1, 60, asOf);
            in.m1 = d.m1 == null || n1 < 120 || d.m1[0].epoch > asOf - 3 * 3600 ? null : win(d.m1, n1, 200);
            Mie.Assessment a = Mie.analyseTier(in, tier);
            rep.windows++;
            if (Mie.noRecord(a.id)) continue;
            if (!seen.add(a.id)) continue;
            Row r = Row.from(a, version, "LIVE");
            r.source = "replay";
            if (a.actionable() && d.m5 != null) {
                int i5 = Math.max(0, n5 - 1);
                Sim.Out o = Sim.run(d.m5, i5, a.isBuy(), a.entry, a.stop, a.target, 72, 576);
                r.outcome = o.outcome; r.resultR = o.resultR; r.mfe = o.mfe; r.mae = o.mae; r.filled = o.filled;
                if (o.exitEpoch > 0) { r.resolvedTs = o.exitEpoch; r.holdSec = o.exitEpoch - a.at; }
            }
            rep.rows.add(r);
        }
        finish(rep);
        return rep;
    }

    /** label rows by chronological third/fifth and compute the KPIs per label and per fold */
    public static void finish(Report rep) {
        if (rep.rows.isEmpty()) { rep.all = Kpi.compute(rep.rows); return; }
        long lo = Long.MAX_VALUE, hi = 0;
        for (Row r : rep.rows) { lo = Math.min(lo, r.ts); hi = Math.max(hi, r.ts); }
        rep.from = lo; rep.to = hi;
        long span = Math.max(1, hi - lo);
        List<List<Row>> folds = new ArrayList<>();
        for (int i = 0; i < 4; i++) folds.add(new ArrayList<Row>());
        Map<String, List<Row>> by = new TreeMap<>();
        for (Row r : rep.rows) {
            double pos = (double) (r.ts - lo) / span;
            r.sample = pos < 0.6 ? "in-sample" : pos < 0.8 ? "validation" : "out-of-sample";
            List<Row> l = by.get(r.sample); if (l == null) { l = new ArrayList<>(); by.put(r.sample, l); } l.add(r);
            folds.get(Math.min(3, (int) (pos * 4))).add(r);
        }
        for (Map.Entry<String, List<Row>> e : by.entrySet()) rep.bySample.put(e.getKey(), kpiOfActionable(e.getValue()));
        for (List<Row> f : folds) rep.folds.add(kpiOfActionable(f));
        rep.all = kpiOfActionable(rep.rows);
    }

    private static Kpi kpiOfActionable(List<Row> rows) {
        List<Row> a = new ArrayList<>();
        for (Row r : rows) if (r.actionable()) a.add(r);
        return Kpi.compute(a);
    }
}
