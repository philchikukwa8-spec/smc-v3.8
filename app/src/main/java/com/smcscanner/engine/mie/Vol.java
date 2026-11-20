package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;

/** Transparent volatility and momentum measurements. */
public final class Vol {
    private Vol() {}

    public static double atr(Candle[] c, int n) { return atrAt(c, c.length - 1, n); }

    /** average true range of the n candles ending at index `end` (inclusive) */
    public static double atrAt(Candle[] c, int end, int n) {
        if (end < 1) return 0;
        int from = Math.max(1, end - n + 1);
        double sum = 0;
        for (int i = from; i <= end; i++) sum += Math.max(c[i].high - c[i].low, Math.max(Math.abs(c[i].high - c[i - 1].close), Math.abs(c[i].low - c[i - 1].close)));
        return sum / (end - from + 1);
    }

    /** ATR as a fraction of price */
    public static double relAtr(Candle[] c, int n) {
        double p = c[c.length - 1].close;
        return p == 0 ? 0 : atr(c, n) / p;
    }

    /** short ATR / long ATR: above 1.4 is expansion, below 0.7 is compression */
    public static double expansion(Candle[] c, int shortN, int longN) {
        double l = atr(c, longN);
        return l == 0 ? 1 : atr(c, shortN) / l;
    }

    /** Kaufman efficiency ratio over n closes: 1 = straight line, 0 = pure chop */
    public static double efficiency(Candle[] c, int n) {
        int e = c.length - 1, s = Math.max(0, e - n);
        double path = 0;
        for (int i = s + 1; i <= e; i++) path += Math.abs(c[i].close - c[i - 1].close);
        return path == 0 ? 0 : Math.abs(c[e].close - c[s].close) / path;
    }

    /** net move over n closes in ATR units (positive = up) */
    public static double momentum(Candle[] c, int n) {
        int e = c.length - 1, s = Math.max(0, e - n);
        double a = atr(c, 14);
        return a == 0 ? 0 : (c[e].close - c[s].close) / a;
    }

    /** share of the last n candles that closed in the direction of the net move */
    public static double persistence(Candle[] c, int n) {
        int e = c.length - 1, s = Math.max(1, e - n + 1);
        double net = c[e].close - c[Math.max(0, s - 1)].close;
        int same = 0, tot = 0;
        for (int i = s; i <= e; i++) { tot++; if ((c[i].close - c[i].open) * net > 0) same++; }
        return tot == 0 ? 0 : (double) same / tot;
    }

    /** a stretched move that is slowing: strong momentum, shrinking bodies */
    public static boolean exhaustion(Candle[] c) {
        if (c.length < 30) return false;
        double m = momentum(c, 12);
        if (Math.abs(m) < 3.0) return false;
        int e = c.length - 1;
        double last3 = 0, prev3 = 0;
        for (int i = 0; i < 3; i++) { last3 += Math.abs(c[e - i].close - c[e - i].open); prev3 += Math.abs(c[e - 3 - i].close - c[e - 3 - i].open); }
        return last3 < 0.6 * prev3;
    }
}
