package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;

import java.util.ArrayList;
import java.util.List;

/** Candle measurements and a few named behaviours. Only meaningful at a location or inside a setup. */
public final class CandleBehaviour {
    private CandleBehaviour() {}

    public static final class Tag {
        public final String name;
        /** BUY, SELL or NEUTRAL */
        public final String bias;
        public Tag(String name, String bias) { this.name = name; this.bias = bias; }
        @Override public String toString() { return name + " (" + bias + ")"; }
    }

    public static double body(Candle c) { return Math.abs(c.close - c.open); }
    public static double range(Candle c) { return c.high - c.low; }
    public static double upperWick(Candle c) { return c.high - Math.max(c.open, c.close); }
    public static double lowerWick(Candle c) { return Math.min(c.open, c.close) - c.low; }
    /** 0 = closed on the low, 1 = closed on the high */
    public static double closeLocation(Candle c) { double r = range(c); return r == 0 ? 0.5 : (c.close - c.low) / r; }

    /** candle at index i, tagged using only candles up to and including i */
    public static List<Tag> at(Candle[] c, int i) {
        List<Tag> t = new ArrayList<>();
        if (i < 15 || i >= c.length) return t;
        Candle k = c[i], p = c[i - 1];
        double avg = Vol.atrAt(c, i - 1, 14);
        if (avg <= 0 || range(k) <= 0) return t;
        boolean bull = k.close > k.open, pbull = p.close > p.open;
        // engulfing: the body covers the previous opposite body
        if (bull && !pbull && k.close >= p.open && k.open <= p.close && body(k) > body(p)) t.add(new Tag("Bullish engulfing", "BUY"));
        if (!bull && pbull && k.close <= p.open && k.open >= p.close && body(k) > body(p)) t.add(new Tag("Bearish engulfing", "SELL"));
        // pin bar: a long wick and a small body, closing back away from the wick
        if (lowerWick(k) >= 2 * Math.max(body(k), 0.05 * range(k)) && lowerWick(k) >= 0.55 * range(k) && closeLocation(k) >= 0.6) t.add(new Tag("Bullish rejection (long lower wick)", "BUY"));
        if (upperWick(k) >= 2 * Math.max(body(k), 0.05 * range(k)) && upperWick(k) >= 0.55 * range(k) && closeLocation(k) <= 0.4) t.add(new Tag("Bearish rejection (long upper wick)", "SELL"));
        // inside bar
        if (k.high <= p.high && k.low >= p.low) t.add(new Tag("Inside bar", "NEUTRAL"));
        // momentum candle: body at least 1.3x the recent average range and closing near its extreme
        if (body(k) >= 1.3 * avg && bull && closeLocation(k) >= 0.75) t.add(new Tag("Bullish momentum candle", "BUY"));
        if (body(k) >= 1.3 * avg && !bull && closeLocation(k) <= 0.25) t.add(new Tag("Bearish momentum candle", "SELL"));
        return t;
    }

    /** true when any of the last `lookback` candles carries a tag pointing the given way */
    public static boolean confirms(Candle[] c, boolean buy, int lookback) {
        for (int i = c.length - 1; i >= Math.max(15, c.length - lookback); i--)
            for (Tag t : at(c, i)) if (t.bias.equals(buy ? "BUY" : "SELL")) return true;
        return false;
    }

    public static String firstTag(Candle[] c, boolean buy, int lookback) {
        for (int i = c.length - 1; i >= Math.max(15, c.length - lookback); i--)
            for (Tag t : at(c, i)) if (t.bias.equals(buy ? "BUY" : "SELL")) return t.name;
        return null;
    }
}
