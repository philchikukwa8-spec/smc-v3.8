package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.List;

/** Checks candle data before anything is concluded from it: order, duplicates, gaps, a still-forming candle, staleness. */
public final class DataCheck {
    private DataCheck() {}

    public static final class Report {
        public int count, duplicates, outOfOrder, gaps, badCandles;
        public boolean forming, stale;
        public long ageSec;
        public final List<String> issues = new ArrayList<>();
        /** true when the data is safe to analyse (stale or broken data is not) */
        public boolean ok() { return count > 0 && duplicates == 0 && outOfOrder == 0 && badCandles == 0 && !stale; }
        /** LIVE, PROVISIONAL (last candle still forming) or STALE */
        public String label() { return stale ? "STALE" : forming ? "PROVISIONAL" : "LIVE"; }
    }

    /** gran in seconds; now in epoch seconds; staleAfter is how many candle lengths of silence count as stale (2 by default) */
    public static Report check(Candle[] c, long gran, long now) {
        Report r = new Report();
        if (c == null || c.length == 0) { r.issues.add("No candles received"); r.stale = true; return r; }
        r.count = c.length;
        for (int i = 0; i < c.length; i++) {
            Candle k = c[i];
            if (!(k.high >= k.low) || k.high < Math.max(k.open, k.close) - 1e-9 || k.low > Math.min(k.open, k.close) + 1e-9 || Double.isNaN(k.close)) r.badCandles++;
            if (i > 0) {
                long d = k.epoch - c[i - 1].epoch;
                if (d == 0) r.duplicates++;
                else if (d < 0) r.outOfOrder++;
                else if (d > gran * 1.5) r.gaps++;
            }
        }
        Candle last = c[c.length - 1];
        r.forming = last.epoch + gran > now;
        r.ageSec = now - (last.epoch + gran);
        r.stale = (r.forming ? now - last.epoch : r.ageSec) > gran * 3;
        if (r.duplicates > 0) r.issues.add(r.duplicates + " duplicate candle(s)");
        if (r.outOfOrder > 0) r.issues.add(r.outOfOrder + " candle(s) out of order");
        if (r.badCandles > 0) r.issues.add(r.badCandles + " invalid candle(s) (high/low inconsistent)");
        if (r.gaps > 0) r.issues.add(r.gaps + " gap(s) in the series");
        if (r.stale) r.issues.add("Data is stale (last candle " + Math.max(0, r.ageSec / 60) + " min old)");
        if (r.forming) r.issues.add("Last candle is still forming (provisional)");
        return r;
    }
}
