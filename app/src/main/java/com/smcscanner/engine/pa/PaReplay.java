package com.smcscanner.engine.pa;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.mie.Kpi;
import com.smcscanner.engine.mie.Row;
import com.smcscanner.engine.mie.Sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replays the price-action engine over history, one 1H window at a time, using only candles that had closed at each
 * moment (no look-ahead). Outcomes are simulated on the 1H candles that follow, and when one candle touches both the
 * stop and the target the stop counts first, so results lean cautious. Rows are labelled in-sample (first 60% of time),
 * validation (next 20%) and out-of-sample (last 20%).
 */
public final class PaReplay {
    private PaReplay() {}

    public static final class Report {
        public final List<Row> rows = new ArrayList<>();
        public int windows;
        public Kpi all;
    }

    /** the last `keep` candles that had closed by `now` (candle epoch + gran <= now) */
    public static Candle[] upToPublic(Candle[] c, long gran, long now, int keep) { return upTo(c, gran, now, keep); }

    static Candle[] upTo(Candle[] c, long gran, long now, int keep) {
        if (c == null) return null;
        int lo = 0, hi = c.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (c[mid].epoch + gran <= now) lo = mid + 1; else hi = mid; }
        if (lo < 40) return null;
        return Arrays.copyOfRange(c, Math.max(0, lo - keep), lo);
    }

    /** h1 and h4 are closed candles, oldest first. `step` = windows advance by this many 1H candles. */
    public static Report run(String symbol, String label, Candle[] h4, Candle[] h1, int step, String version) {
        return runFrame(symbol, label, "1H", 3600, h1, h4, 14400, "4H", h4, h1, null, 14400, 3600, 1800, step, version);
    }

    /**
     * Replays one chart. p = the chart analysed (gran seconds), trend = its context chart (tg seconds per candle),
     * t4/t1/t30 = the charts targets come from (with their granularities). Only candles that had closed at each moment are used.
     */
    public static Report runFrame(String symbol, String label, String tf, long gran, Candle[] p, Candle[] trend, long tg, String trendTf,
                                  Candle[] t4, Candle[] t1, Candle[] t30, long g4, long g1, long g30, int step, String version) {
        Report rep = new Report();
        if (p == null || p.length < 400) return rep;
        Set<String> seen = new HashSet<>();
        for (int i = 300; i < p.length - 2; i += Math.max(1, step)) {
            Candle[] w = Arrays.copyOfRange(p, i - 299, i + 1);
            long now = p[i].epoch + gran;
            Candle[] wt = upTo(trend, tg, now, 200), w4 = upTo(t4, g4, now, 200), w1 = upTo(t1, g1, now, 300), w30 = upTo(t30, g30, now, 300);
            rep.windows++;
            for (PaEngine.Signal s : PaEngine.analyseFrame(symbol, label, tf, gran, w, wt, trendTf, w4, w1, w30, now)) {
                if (!seen.add(s.id + (s.actionable() ? "|A" : "|W"))) continue;
                Row r = Row.fromPa(s, now, version, "REPLAY");
                r.source = "replay"; r.id = "PAR|" + r.id + "|" + i;
                if (s.actionable()) {
                    Sim.Out o = Sim.run(p, i, s.isBuy(), s.entry, s.stop, s.target, 24, 96);
                    r.outcome = o.outcome; r.resultR = o.resultR; r.mfe = o.mfe; r.mae = o.mae; r.filled = o.filled;
                    if (o.exitEpoch > 0) { r.resolvedTs = o.exitEpoch; r.holdSec = o.exitEpoch - now; }
                }
                rep.rows.add(r);
            }
        }
        long lo = Long.MAX_VALUE, hi = 0;
        for (Row r : rep.rows) { lo = Math.min(lo, r.ts); hi = Math.max(hi, r.ts); }
        for (Row r : rep.rows) {
            double pos = hi > lo ? (double) (r.ts - lo) / (hi - lo) : 0;
            r.sample = pos < 0.6 ? "in-sample" : pos < 0.8 ? "validation" : "out-of-sample";
        }
        List<Row> act = new ArrayList<>();
        for (Row r : rep.rows) if (r.actionable()) act.add(r);
        rep.all = Kpi.compute(act);
        return rep;
    }
}
