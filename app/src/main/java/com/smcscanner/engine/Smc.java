package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * SMC rule engine (Java port of the tested TypeScript engine).
 *
 * Works ONLY on closed candles and never looks at candles after the one being evaluated,
 * so a signal can't appear and then vanish ("repaint").
 *
 * A BUY setup is "validated" on candle i when ALL of these are true:
 *   1. SWEEP         - a wick took out a confirmed swing low and the candle closed back above it
 *   2. STRUCTURE     - candle i closes above the most recent unbroken swing high formed before the sweep
 *   3. DISPLACEMENT  - candle i is a strong bullish candle (body >= N x average)
 *   4. ZONE          - the move left a fair value gap and/or an order block to trade from
 *   5. RISK/REWARD   - stop below the sweep, target at the next liquidity (or fixed), at least minRR
 * SELL setups are found by mirroring prices and running the same BUY logic.
 */
public final class Smc {
    private Smc() {}

    public static final class Swing {
        public final int idx;
        public final double level;
        Swing(int idx, double level) { this.idx = idx; this.level = level; }
    }

    private static final class Zone {
        final double low, high;
        Zone(double low, double high) { this.low = low; this.high = high; }
    }

    public static Candle[] mirror(Candle[] c) {
        Candle[] m = new Candle[c.length];
        for (int i = 0; i < c.length; i++) {
            Candle x = c[i];
            m[i] = new Candle(x.epoch, -x.open, -x.low, -x.high, -x.close);
        }
        return m;
    }

    /** Confirmed swing highs with idx + L <= upTo (so no lookahead). */
    public static List<Swing> swingHighs(Candle[] c, int L, int from, int upTo) {
        List<Swing> out = new ArrayList<>();
        for (int j = Math.max(L, from); j + L <= upTo; j++) {
            boolean ok = true;
            for (int k = 1; k <= L && ok; k++) {
                if (!(c[j].high > c[j - k].high) || !(c[j].high >= c[j + k].high)) ok = false;
            }
            if (ok) out.add(new Swing(j, c[j].high));
        }
        return out;
    }

    public static List<Swing> swingLows(Candle[] c, int L, int from, int upTo) {
        List<Swing> out = new ArrayList<>();
        for (int j = Math.max(L, from); j + L <= upTo; j++) {
            boolean ok = true;
            for (int k = 1; k <= L && ok; k++) {
                if (!(c[j].low < c[j - k].low) || !(c[j].low <= c[j + k].low)) ok = false;
            }
            if (ok) out.add(new Swing(j, c[j].low));
        }
        return out;
    }

    /** BUY-side detection on candle i. Returns null when the rules are not all met. */
    public static Setup detectBuy(Candle[] c, int i, SmcConfig cfg) {
        int L = cfg.swingLen;
        int minIdx = cfg.avgLen + 2 * L + 3;
        if (i < minIdx || i >= c.length) return null;
        int from = Math.max(0, i - cfg.lookback);

        Candle trig = c[i];
        double sumBody = 0, sumRange = 0;
        for (int k = i - cfg.avgLen; k < i; k++) {
            sumBody += Math.abs(c[k].close - c[k].open);
            sumRange += c[k].high - c[k].low;
        }
        double avgBody = sumBody / cfg.avgLen;
        double avgRange = sumRange / cfg.avgLen;
        if (!(avgBody > 0) || !(avgRange > 0)) return null;

        // 3. displacement on the trigger candle
        double body = trig.close - trig.open;
        if (!(body > 0) || body < cfg.displacementMult * avgBody) return null;

        // 2. structure: most recent swing high not yet closed above, now closed above by candle i
        List<Swing> highs = swingHighs(c, L, from, i - 1);
        Swing broken = null;
        int brokenPos = -1;
        for (int h = highs.size() - 1; h >= 0; h--) {
            Swing sh = highs.get(h);
            boolean alreadyBroken = false;
            for (int k = sh.idx + 1; k < i; k++) {
                if (c[k].close > sh.level) { alreadyBroken = true; break; }
            }
            if (!alreadyBroken) { broken = sh; brokenPos = h; break; }
        }
        if (broken == null || !(trig.close > broken.level)) return null;

        // 1. sweep: after that swing high formed, a wick took a confirmed swing low and closed back above it
        List<Swing> lows = swingLows(c, L, from, i - 1);
        int sweepIdx = -1;
        double sweepLevel = Double.NaN;
        for (int k = Math.max(broken.idx + 1, i - cfg.sweepWindow); k < i; k++) {
            for (Swing sl : lows) {
                if (sl.idx + L > k) continue; // swing must be confirmed before the sweep
                if (!(c[k].low < sl.level && c[k].close > sl.level)) continue;
                boolean brokenBefore = false; // a close below it earlier means it's no longer resting liquidity
                for (int m = sl.idx + 1; m < k; m++) {
                    if (c[m].close < sl.level) { brokenBefore = true; break; }
                }
                if (brokenBefore) continue;
                // prefer the sweep that reached the lowest low
                if (sweepIdx == -1 || c[k].low < c[sweepIdx].low) { sweepIdx = k; sweepLevel = sl.level; }
            }
        }
        if (sweepIdx == -1) return null;

        // anchor = lowest low from the sweep through the trigger candle
        double anchor = Double.POSITIVE_INFINITY;
        for (int k = sweepIdx; k <= i; k++) anchor = Math.min(anchor, c[k].low);

        // 4a. fair value gap formed by the displacement (3-candle gap ending at i or i-1)
        Zone fvg = null;
        int[] ends = {i, i - 1};
        for (int m : ends) {
            if (m - 2 < sweepIdx - 1) continue;
            double gapLow = c[m - 2].high, gapHigh = c[m].low;
            if (gapHigh > gapLow && c[m - 1].close > c[m - 1].open) { fvg = new Zone(gapLow, gapHigh); break; }
        }
        // 4b. order block: last bearish candle before the move (searching back from i-1, no further than the sweep)
        Zone ob = null;
        for (int k = i - 1; k >= Math.max(sweepIdx, i - 8); k--) {
            if (c[k].close < c[k].open) { ob = new Zone(c[k].low, c[k].high); break; }
        }
        Zone zone = fvg != null ? fvg : ob;
        String zoneType = fvg != null ? "FVG" : "OB";
        if (zone == null) return null;
        if (!(zone.high < trig.close)) return null; // zone must be a retest area below price

        double entry = (zone.low + zone.high) / 2;
        double sl = anchor - cfg.slBuffer * avgRange;
        double risk = entry - sl;
        if (!(risk > 0)) return null;

        // 5. target: nearest unbroken swing high above price giving >= minRR, else fixed minRR
        double tp = entry + cfg.minRR * risk;
        String tpType = "fixed";
        List<Swing> above = new ArrayList<>();
        for (int idx = 0; idx < highs.size(); idx++) {
            Swing h = highs.get(idx);
            if (idx == brokenPos || !(h.level > trig.close)) continue;
            boolean taken = false;
            for (int k = h.idx + 1; k <= i; k++) {
                if (c[k].high > h.level) { taken = true; break; }
            }
            if (!taken) above.add(h);
        }
        above.sort(new Comparator<Swing>() {
            @Override public int compare(Swing a, Swing b) { return Double.compare(a.level, b.level); }
        });
        for (Swing h : above) {
            if ((h.level - entry) / risk >= cfg.minRR) { tp = h.level; tpType = "liquidity"; break; }
        }
        double rr = (tp - entry) / risk;
        if (rr < cfg.minRR - 1e-9) return null;

        // structure label: CHoCH if the broken high was a lower high (prior downtrend), else BOS
        Swing prevHigh = brokenPos > 0 ? highs.get(brokenPos - 1) : null;
        String structure = (prevHigh != null && broken.level < prevHigh.level) ? "CHoCH" : "BOS";

        // premium / discount: is the entry in the lower half of the recent dealing range?
        double rangeHigh = Double.NEGATIVE_INFINITY;
        for (int k = from; k <= i; k++) rangeHigh = Math.max(rangeHigh, c[k].high);
        double eq = (anchor + rangeHigh) / 2;
        boolean discount = entry < eq;
        boolean majorSweep = true;
        for (Swing l : lows) if (!(l.level >= sweepLevel - 1e-12)) { majorSweep = false; break; }
        boolean overlap = fvg != null && ob != null && ob.low <= fvg.high && ob.high >= fvg.low;

        int score = 3;
        if (fvg != null) score++;
        if (ob != null) score++;
        if (discount) score++;
        if (rr >= 3) score++;

        Setup s = new Setup();
        s.id = trig.epoch + "-BUY";
        s.side = "BUY";
        s.triggerEpoch = trig.epoch;
        s.structure = structure;
        s.sweepLevel = sweepLevel;
        s.brokenLevel = broken.level;
        s.zoneType = zoneType;
        s.zoneLow = zone.low;
        s.zoneHigh = zone.high;
        s.obLow = ob != null ? Double.valueOf(ob.low) : null;
        s.obHigh = ob != null ? Double.valueOf(ob.high) : null;
        s.entry = entry; s.sl = sl; s.tp = tp; s.tpType = tpType; s.rr = rr;
        s.discount = discount;
        s.sweepEpoch = c[sweepIdx].epoch;
        s.sweepExtreme = anchor;
        s.majorSweep = majorSweep;
        s.dispRatio = body / avgBody;
        s.overlap = overlap;
        s.rangeLow = anchor;
        s.rangeHigh = rangeHigh;
        s.eq = eq;
        s.avgRange = avgRange;
        s.score = score;
        s.maxScore = 7;
        s.reasons = reasons(s);
        return s;
    }

    /** Plain-English list of the confluences that were met (wording depends on BUY / SELL). */
    static ArrayList<String> reasons(Setup s) {
        boolean buy = s.isBuy();
        ArrayList<String> r = new ArrayList<>();
        r.add(buy ? "Sell-side liquidity sweep" : "Buy-side liquidity sweep");
        r.add((buy ? "Bullish " : "Bearish ") + s.structure + " (closed " + (buy ? "above " : "below ") + Fmt.f(s.brokenLevel) + ")");
        r.add(buy ? "Bullish displacement candle" : "Bearish displacement candle");
        if ("FVG".equals(s.zoneType)) r.add("Fair value gap left behind");
        if (s.obLow != null) r.add("Order block present");
        if (s.discount) r.add(buy ? "Entry in discount (lower half of range)" : "Entry in premium (upper half of range)");
        if (s.rr >= 3) r.add("Reward:risk " + Fmt.d1(s.rr));
        return r;
    }

    /** Turn a setup found in the mirrored market back into a real SELL setup. */
    private static Setup unmirror(Setup s) {
        Setup o = s.copy();
        o.id = s.id.replace("-BUY", "-SELL");
        o.side = "SELL";
        o.sweepLevel = -s.sweepLevel;
        o.brokenLevel = -s.brokenLevel;
        o.zoneLow = -s.zoneHigh;
        o.zoneHigh = -s.zoneLow;
        o.obLow = s.obHigh == null ? null : Double.valueOf(-s.obHigh);
        o.obHigh = s.obLow == null ? null : Double.valueOf(-s.obLow);
        o.entry = -s.entry;
        o.sl = -s.sl;
        o.tp = -s.tp;
        o.sweepExtreme = -s.sweepExtreme;
        o.rangeLow = -s.rangeHigh;
        o.rangeHigh = -s.rangeLow;
        o.eq = -s.eq;
        o.reasons = reasons(o);
        return o;
    }

    /** Evaluate candle i for both directions; returns the better setup or null. `mirrored` = mirror(c) (reuse it in loops). */
    public static Setup detectAt(Candle[] c, Candle[] mirrored, int i, SmcConfig cfg) {
        Setup buy = detectBuy(c, i, cfg);
        Setup m = detectBuy(mirrored, i, cfg);
        Setup sell = m != null ? unmirror(m) : null;
        if (buy != null && sell != null) return buy.score >= sell.score ? buy : sell;
        return buy != null ? buy : sell;
    }

    public static Setup detectAt(Candle[] c, int i, SmcConfig cfg) {
        return detectAt(c, mirror(c), i, cfg);
    }

    /** All setups triggered in the last n closed candles (oldest first). */
    public static List<Setup> detectRecent(Candle[] closed, int n, SmcConfig cfg) {
        List<Setup> out = new ArrayList<>();
        Candle[] m = mirror(closed);
        for (int i = Math.max(0, closed.length - n); i < closed.length; i++) {
            Setup s = detectAt(closed, m, i, cfg);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Deriv returns the still-forming candle last; drop it so rules only see closed candles. */
    public static Candle[] closedOnly(Candle[] c, long granularity, long nowEpoch) {
        if (c.length == 0) return c;
        Candle last = c[c.length - 1];
        return last.epoch + granularity > nowEpoch ? Arrays.copyOf(c, c.length - 1) : c;
    }

    /** Market-structure bias from the last two confirmed swing highs and lows: "bull", "bear" or "range". */
    public static String structureBias(Candle[] closed, int L, int lookback) {
        int n = closed.length;
        if (n < 2 * L + 8) return "range";
        int from = Math.max(0, n - lookback);
        List<Swing> hi = swingHighs(closed, L, from, n - 1);
        List<Swing> lo = swingLows(closed, L, from, n - 1);
        if (hi.size() < 2 || lo.size() < 2) return "range";
        boolean hh = hi.get(hi.size() - 1).level > hi.get(hi.size() - 2).level;
        boolean hl = lo.get(lo.size() - 1).level > lo.get(lo.size() - 2).level;
        if (hh && hl) return "bull";
        if (!hh && !hl) return "bear";
        return "range";
    }

    /**
     * Nearest resting liquidity beyond `beyond` in the trade direction: for BUY the nearest unbroken swing
     * high above that price, for SELL the nearest unbroken swing low below it. Returns null if none.
     */
    public static Double nextLiquidity(Candle[] closed, boolean buy, double beyond, int L, int lookback) {
        int n = closed.length;
        int from = Math.max(0, n - lookback);
        double best = Double.NaN;
        boolean found = false;
        if (buy) {
            for (Swing h : swingHighs(closed, L, from, n - 1)) {
                if (!(h.level > beyond)) continue;
                boolean taken = false;
                for (int k = h.idx + 1; k < n; k++) if (closed[k].high > h.level) { taken = true; break; }
                if (taken) continue;
                if (!found || h.level < best) { best = h.level; found = true; }
            }
        } else {
            for (Swing h : swingLows(closed, L, from, n - 1)) {
                if (!(h.level < beyond)) continue;
                boolean taken = false;
                for (int k = h.idx + 1; k < n; k++) if (closed[k].low < h.level) { taken = true; break; }
                if (taken) continue;
                if (!found || h.level > best) { best = h.level; found = true; }
            }
        }
        return found ? Double.valueOf(best) : null;
    }
}
