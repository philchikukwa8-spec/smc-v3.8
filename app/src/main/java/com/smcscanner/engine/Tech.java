package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Supporting measurements: candle behaviour, volatility, trendlines/channels and market regime. All closed-candle, deterministic. */
public final class Tech {
    private Tech() {}

    // ---------------------------------------------------------------- volatility
    public static double atr(Candle[] c, int end, int n) {
        int from = Math.max(1, end - n + 1);
        if (end < 1) return 0;
        double s = 0; int k = 0;
        for (int i = from; i <= end; i++) {
            s += Math.max(c[i].high - c[i].low, Math.max(Math.abs(c[i].high - c[i - 1].close), Math.abs(c[i].low - c[i - 1].close)));
            k++;
        }
        return k == 0 ? 0 : s / k;
    }

    // ---------------------------------------------------------------- candle behaviour
    /** Names of the behaviours shown by the candle at index i (needs i >= 1). Meaningful only at a location, never a signal alone. */
    public static List<String> candleBehaviour(Candle[] c, int i) {
        List<String> out = new ArrayList<>();
        if (i < 1) return out;
        Candle k = c[i], p = c[i - 1];
        double a = atr(c, i, 14);
        if (a <= 0) return out;
        double body = Math.abs(k.close - k.open), range = k.high - k.low;
        double up = k.high - Math.max(k.open, k.close), lo = Math.min(k.open, k.close) - k.low;
        boolean bull = k.close > k.open, bear = k.close < k.open;
        if (bull && p.close < p.open && k.close >= p.open && k.open <= p.close && body > Math.abs(p.close - p.open)) out.add("bullish engulfing");
        if (bear && p.close > p.open && k.close <= p.open && k.open >= p.close && body > Math.abs(p.close - p.open)) out.add("bearish engulfing");
        if (range > 0 && lo >= 2 * Math.max(body, 0.1 * range) && (k.close - k.low) / range >= 0.66 && range >= 0.6 * a) out.add("bullish rejection (long lower wick)");
        if (range > 0 && up >= 2 * Math.max(body, 0.1 * range) && (k.high - k.close) / range >= 0.66 && range >= 0.6 * a) out.add("bearish rejection (long upper wick)");
        if (k.high <= p.high && k.low >= p.low) out.add("inside bar");
        if (body >= 1.5 * a && range > 0 && body / range >= 0.65) out.add(bull ? "bullish momentum candle" : "bearish momentum candle");
        return out;
    }

    // ---------------------------------------------------------------- trendlines
    public static final class Line {
        public boolean rising, support;
        public int i1, i2;          // pivot indices
        public double p1, p2;       // pivot prices
        public int touches;         // independent reactions (pivots near the line)
        public double fitErrAtr;    // average distance of the touches from the line, in ATR
        public double slopePerBar;
        public boolean brokenByClose;
        public double valueAt(int idx) { return p1 + slopePerBar * (idx - i1); }
        public String describe() {
            return (support ? "support" : "resistance") + " trendline (" + (rising ? "rising" : "falling") + ", " + touches + " touches)";
        }
    }

    /** Best trendline through confirmed swing pivots in the last `look` candles. Support uses swing lows, resistance swing highs. A wick through the line is not a break; a close is. */
    public static Line trendline(Candle[] c, boolean support, int look) {
        int n = c.length;
        if (n < 30) return null;
        int from = Math.max(2, n - look), to = n - 1;
        List<Smc.Swing> sw = support ? Smc.swingLows(c, 2, from, to) : Smc.swingHighs(c, 2, from, to);
        if (sw.size() < 2) return null;
        double a = atr(c, n - 1, 14);
        if (a <= 0) return null;
        Line best = null;
        int s0 = Math.max(0, sw.size() - 6);
        for (int x = s0; x < sw.size(); x++) for (int y = x + 1; y < sw.size(); y++) {
            Smc.Swing A = sw.get(x), B = sw.get(y);
            if (B.idx - A.idx < 5) continue;
            double slope = (B.level - A.level) / (B.idx - A.idx);
            Line l = new Line();
            l.support = support; l.i1 = A.idx; l.i2 = B.idx; l.p1 = A.level; l.p2 = B.level; l.slopePerBar = slope; l.rising = slope > 0;
            // direction sanity: supports that slope down and resistances that slope up are drawn too but ranked by touches
            boolean violated = false;
            for (int k = A.idx; k < n; k++) {
                double v = l.valueAt(k);
                if (support ? c[k].close < v - 0.15 * a : c[k].close > v + 0.15 * a) { violated = true; if (k < n - 1) break; l.brokenByClose = true; }
            }
            if (violated && !l.brokenByClose) continue;
            int touches = 0; double err = 0;
            for (Smc.Swing s : sw) {
                if (s.idx < A.idx) continue;
                double d = Math.abs(s.level - l.valueAt(s.idx));
                if (d <= 0.35 * a) { touches++; err += d / a; }
            }
            if (touches < 2) continue;
            l.touches = touches; l.fitErrAtr = err / touches;
            if (best == null || l.touches > best.touches || (l.touches == best.touches && l.fitErrAtr < best.fitErrAtr)) best = l;
        }
        return best;
    }

    /** true when support and resistance lines run nearly parallel (a channel) */
    public static String channel(Candle[] c, int look) {
        Line s = trendline(c, true, look), r = trendline(c, false, look);
        if (s == null || r == null) return null;
        double a = atr(c, c.length - 1, 14);
        if (a <= 0) return null;
        double dSlope = Math.abs(s.slopePerBar - r.slopePerBar) * look / a;
        if (dSlope < 0.6 && s.rising == r.rising) return (s.rising ? "rising" : "falling") + " channel";
        if (s.rising && !r.rising) return "converging (triangle-like)";
        return null;
    }

    // ---------------------------------------------------------------- regime
    public static final class Regime {
        public String trend = "UNKNOWN";   // BULLISH_TREND, BEARISH_TREND, RANGE, TRANSITION, UNKNOWN
        public String volatility = "NORMAL"; // EXPANSION, COMPRESSION, NORMAL
        public double efficiency, atrRatio;
        public int bars;
        public String label() { return trend.equals("UNKNOWN") ? "UNKNOWN" : trend + (volatility.equals("NORMAL") ? "" : " + " + volatility); }
        public String plain() {
            String t = trend.equals("BULLISH_TREND") ? "trending up" : trend.equals("BEARISH_TREND") ? "trending down" : trend.equals("RANGE") ? "ranging" : trend.equals("TRANSITION") ? "in transition" : "unclear";
            String v = volatility.equals("EXPANSION") ? ", volatility expanding" : volatility.equals("COMPRESSION") ? ", volatility compressing" : "";
            return t + v + String.format(Locale.US, " (efficiency %.2f)", efficiency);
        }
    }

    /** Efficiency ratio (net move divided by total path) and the short/long ATR ratio. Returns UNKNOWN with too little data. */
    public static Regime regime(Candle[] c, int end, int window) {
        Regime r = new Regime();
        if (end < window + 20) return r;
        r.bars = window;
        double path = 0;
        for (int i = end - window + 1; i <= end; i++) path += Math.abs(c[i].close - c[i - 1].close);
        double net = c[end].close - c[end - window].close;
        r.efficiency = path == 0 ? 0 : Math.abs(net) / path;
        double longA = atr(c, end, 50), shortA = atr(c, end, 10);
        r.atrRatio = longA == 0 ? 1 : shortA / longA;
        if (r.efficiency >= 0.30) r.trend = net > 0 ? "BULLISH_TREND" : "BEARISH_TREND";
        else if (r.efficiency <= 0.15) r.trend = "RANGE";
        else r.trend = "TRANSITION";
        r.volatility = r.atrRatio >= 1.4 ? "EXPANSION" : r.atrRatio <= 0.7 ? "COMPRESSION" : "NORMAL";
        return r;
    }
}
