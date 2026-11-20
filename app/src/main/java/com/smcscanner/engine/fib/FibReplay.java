package com.smcscanner.engine.fib;

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
 * Replays the Fib pullback engine over history, one M15 window at a time, with only the candles that had closed at each
 * moment. The outcome is simulated on the M15 candles that follow (stop counts first when one candle touches both).
 * Each setup is recorded once, the first time it becomes ready. Rows are labelled in-sample (first 60%), validation, out-of-sample.
 */
public final class FibReplay {
    private FibReplay() {}

    public static final class Report {
        public final List<Row> rows = new ArrayList<>();
        public int windows;
        public Kpi all;
    }

    static Candle[] upTo(Candle[] c, long gran, long now, int keep) {
        int lo = 0, hi = c.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (c[mid].epoch + gran <= now) lo = mid + 1; else hi = mid; }
        if (lo < 60) return null;
        return Arrays.copyOfRange(c, Math.max(0, lo - keep), lo);
    }

    public static Report run(String symbol, String label, Candle[] m15, Candle[] h4, int step, FibEngine.Options o) {
        Report rep = new Report();
        if (m15 == null || h4 == null || m15.length < 900) return rep;
        Set<String> seen = new HashSet<>();
        for (int i = 700; i < m15.length - 2; i += Math.max(1, step)) {
            Candle[] w = Arrays.copyOfRange(m15, i - 699, i + 1);
            long now = m15[i].epoch + 900;
            Candle[] wh = upTo(h4, 14400, now, 200);
            if (wh == null) continue;
            rep.windows++;
            for (FibEngine.Setup s : FibEngine.analyse(symbol, symbol, wh, w, now, o)) {
                if (!s.actionable() || !seen.add(s.id)) continue;
                Row r = Row.fromFib(s, o, now, FibEngine.VERSION, "REPLAY");
                r.source = "replay"; r.id = "FIBR|" + s.id + "|" + i;
                Sim.Out out = Sim.run(m15, i, s.isBuy(), s.entry, s.stop, s.target(o), 96, 384);
                r.outcome = out.outcome; r.resultR = out.resultR; r.mfe = out.mfe; r.mae = out.mae; r.filled = out.filled;
                if (out.exitEpoch > 0) { r.resolvedTs = out.exitEpoch; r.holdSec = out.exitEpoch - now; }
                r.label = label;
                rep.rows.add(r);
            }
        }
        long lo = Long.MAX_VALUE, hi = 0;
        for (Row r : rep.rows) { lo = Math.min(lo, r.ts); hi = Math.max(hi, r.ts); }
        for (Row r : rep.rows) {
            double pos = hi > lo ? (double) (r.ts - lo) / (hi - lo) : 0;
            r.sample = pos < 0.6 ? "in-sample" : pos < 0.8 ? "validation" : "out-of-sample";
        }
        rep.all = Kpi.compute(rep.rows);
        return rep;
    }
}
