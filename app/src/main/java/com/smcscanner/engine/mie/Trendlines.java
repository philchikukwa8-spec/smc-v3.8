package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Trendlines from confirmed swing pivots, with touches, fit error and close-based breaks. */
public final class Trendlines {
    private Trendlines() {}

    public static final class Line implements Serializable {
        private static final long serialVersionUID = 1L;
        /** "support" (rising or falling line under price) or "resistance" */
        public String role;
        public int i1, i2;
        public double p1, p2, slope;
        public int touches;
        /** mean distance of touching pivots from the line, in ATR */
        public double fitErr;
        public int ageCandles;
        /** a candle CLOSED beyond the line by at least 0.25 ATR after the second pivot */
        public boolean broken;
        public int brokenAt = -1;
        public double at(int idx) { return p1 + slope * (idx - i1); }
        public boolean rising() { return slope > 0; }
    }

    /** best support line from swing lows and best resistance line from swing highs in the last `lookback` candles */
    public static List<Line> find(Candle[] c, int lookback) {
        List<Line> out = new ArrayList<>();
        if (c == null || c.length < 40) return out;
        double atr = Vol.atr(c, 14);
        if (atr <= 0) return out;
        int n = c.length, from = Math.max(0, n - lookback);
        Line s = best(c, Smc.swingLows(c, 3, from, n - 1), true, atr);
        Line r = best(c, Smc.swingHighs(c, 3, from, n - 1), false, atr);
        if (s != null) out.add(s);
        if (r != null) out.add(r);
        return out;
    }

    private static Line best(Candle[] c, List<Smc.Swing> piv, boolean support, double atr) {
        Line best = null;
        double bestScore = -1;
        int n = c.length;
        for (int a = 0; a < piv.size(); a++) {
            for (int b = a + 1; b < piv.size(); b++) {
                Smc.Swing x = piv.get(a), y = piv.get(b);
                if (y.idx - x.idx < 8) continue;
                double slope = (y.level - x.level) / (y.idx - x.idx);
                // no price may have closed through the line between the two pivots
                boolean clean = true;
                for (int k = x.idx; k <= y.idx && clean; k++) {
                    double lv = x.level + slope * (k - x.idx);
                    if (support ? c[k].close < lv - 0.2 * atr : c[k].close > lv + 0.2 * atr) clean = false;
                }
                if (!clean) continue;
                Line l = new Line();
                l.role = support ? "support" : "resistance";
                l.i1 = x.idx; l.i2 = y.idx; l.p1 = x.level; l.p2 = y.level; l.slope = slope;
                int touches = 0; double err = 0;
                for (Smc.Swing q : piv) {
                    if (q.idx < x.idx) continue;
                    double d = Math.abs(q.level - l.at(q.idx));
                    if (d <= 0.35 * atr) { touches++; err += d / atr; }
                }
                l.touches = touches; l.fitErr = touches == 0 ? 9 : err / touches;
                l.ageCandles = n - 1 - x.idx;
                for (int k = y.idx + 1; k < n; k++) {
                    double lv = l.at(k);
                    if (support ? c[k].close < lv - 0.25 * atr : c[k].close > lv + 0.25 * atr) { l.broken = true; l.brokenAt = k; break; }
                }
                // recent, well-touched, tight lines win
                double score = touches * 2 - l.fitErr - l.ageCandles / 400.0 + (l.broken ? -1 : 0);
                if (touches >= 2 && score > bestScore) { bestScore = score; best = l; }
            }
        }
        return best;
    }

    /** the line's value now and how far price is from it, in ATR (positive = price above the line) */
    public static double distanceAtr(Line l, Candle[] c) {
        double a = Vol.atr(c, 14);
        return a == 0 ? 0 : (c[c.length - 1].close - l.at(c.length - 1)) / a;
    }
}
