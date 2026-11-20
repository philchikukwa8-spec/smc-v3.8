package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Order block inside order block. Top-down: the higher-timeframe zone (a 1H/30m/4H order block, FVG or level, found by the
 * Advisor) is the parent. Inside it we look for the last opposing candle on the 15m that was followed by a displacement
 * (an order block), then for one inside THAT on the 5m, then on the 1m. Each level down is a smaller, tighter box, so the
 * entry sits at the deepest block's near edge and the stop goes just beyond that block. The chain can start at any level:
 * a 5m block straight inside the 1H zone also counts (shorter chain, less refinement).
 *
 * Deterministic, closed candles only. It only proposes an entry and a stop; the target and every other check stay with Mie.
 */
public final class ObRefiner {
    private ObRefiner() {}

    public static final class Block implements Serializable {
        private static final long serialVersionUID = 1L;
        public String tf;
        /** the full candle range and the body range (buy orientation, real prices) */
        public double low, high, bodyLow, bodyHigh;
        public long epoch;
        public String toString() { return tf + " order block " + Fmt.f(low) + " - " + Fmt.f(high); }
    }

    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;
        public boolean valid;
        public final List<Block> chain = new ArrayList<>();
        public double entry, stop;
        public String note = "", basis = "";
        public int depth() { return chain.size(); }
        public String describe(String parentTf) {
            StringBuilder sb = new StringBuilder();
            for (int i = chain.size() - 1; i >= 0; i--) sb.append(chain.get(i).tf).append(" OB").append(" inside ");
            sb.append(parentTf).append(" zone");
            return sb.toString();
        }
    }

    /**
     * @param buy direction, zoneLow/zoneHigh the higher-timeframe zone, a15 the 15m ATR (scale for noise checks)
     * @param m15,m5,m1 closed candles (any may be null)
     */
    public static Result refine(boolean buy, double zoneLow, double zoneHigh, double a15, Candle[] m15, Candle[] m5, Candle[] m1) {
        Result r = new Result();
        if (!(a15 > 0)) { r.note = "no volatility reading."; return r; }
        double zl = buy ? zoneLow : -zoneHigh, zh = buy ? zoneHigh : -zoneLow;
        Candle[][] series = {m15, m5, m1};
        String[] names = {"15m", "5m", "1m"};
        int[] windows = {30, 60, 120};
        Block parent = null;
        double pl = zl, ph = zh;
        List<Block> chain = new ArrayList<>();
        double tol = 0.15 * a15;
        for (int s = 0; s < 3; s++) {
            Candle[] raw = series[s];
            if (raw == null || raw.length < 40) continue;
            Candle[] c = buy ? raw : Smc.mirror(raw);
            Block b = find(c, names[s], pl - tol, ph + tol, windows[s], 0);
            if (b == null) continue;
            chain.add(b);
            parent = b; pl = b.low; ph = b.high;
        }
        if (chain.isEmpty()) { r.note = "no order block inside the zone on the 15m, 5m or 1m yet."; return r; }
        // choose the deepest block whose stop is not just noise
        for (int k = chain.size() - 1; k >= 0; k--) {
            Block b = chain.get(k);
            Candle[] raw = null;
            for (int s = 0; s < 3; s++) if (names[s].equals(b.tf)) raw = series[s];
            Candle[] c = buy ? raw : Smc.mirror(raw);
            double atr = Vol.atr(c, 14);
            double entry = b.bodyHigh, stop = b.low - 0.15 * atr;
            double px = c[c.length - 1].close;
            if (px < entry) entry = px;
            double risk = entry - stop;
            if (risk <= 0) continue;
            if (risk < 0.12 * a15) continue;      // inside the noise
            if (risk > 2.5 * a15) continue;       // not tight any more
            r.valid = true;
            for (int j = 0; j <= k; j++) r.chain.add(chain.get(j));
            r.entry = buy ? entry : -entry;
            r.stop = buy ? stop : -stop;
            // present the blocks in real prices for a sell
            if (!buy) for (Block q : r.chain) { double lo = -q.high, hi = -q.low, bl = -q.bodyHigh, bh = -q.bodyLow; q.low = lo; q.high = hi; q.bodyLow = bl; q.bodyHigh = bh; }
            r.basis = (px < b.bodyHigh ? "current price (already inside the " + b.tf + " block)" : "near edge of the " + b.tf + " order block");
            return r;
        }
        r.note = "order blocks found, but none gives a usable stop (noise or too wide).";
        return r;
    }

    /** latest unmitigated bullish-orientation order block (last down candle before an up displacement) inside [lo, hi] */
    private static Block find(Candle[] c, String tf, double lo, double hi, int window, long ignored) {
        int n = c.length;
        double atr = Vol.atr(c, 14);
        if (!(atr > 0)) return null;
        for (int i = n - 3; i >= Math.max(15, n - window); i--) {
            Candle k = c[i];
            if (!(k.close < k.open)) continue;
            // the block's body has to sit inside the parent range (a wick may poke out)
            if (Math.max(k.open, k.close) > hi || Math.min(k.open, k.close) < lo) continue;
            // displacement: within 3 candles a candle closes above the block's high with a decent body
            boolean disp = false;
            int di = -1;
            for (int j = i + 1; j <= Math.min(n - 1, i + 3); j++) {
                if (c[j].close > k.high && c[j].close - c[j].open >= 0.8 * atr) { disp = true; di = j; break; }
            }
            if (!disp) continue;
            // unmitigated: nothing closed below the block's low since
            boolean broken = false;
            for (int j = i + 1; j < n; j++) if (c[j].close < k.low) { broken = true; break; }
            if (broken) continue;
            Block b = new Block();
            b.tf = tf; b.low = k.low; b.high = k.high; b.bodyLow = Math.min(k.open, k.close); b.bodyHigh = Math.max(k.open, k.close); b.epoch = k.epoch;
            return b;
        }
        return null;
    }

    public static String line(Result r, String parentTf) {
        if (!r.valid) return r.note;
        StringBuilder sb = new StringBuilder();
        sb.append(r.depth() == 1 ? "Order block in the zone: " : "Order block inside order block: ").append(r.describe(parentTf)).append(". ");
        Block d = r.chain.get(r.chain.size() - 1);
        sb.append(String.format(Locale.US, "Entry at the %s (%s), stop just beyond it at %s.", d.tf + " block", Fmt.f(r.entry), Fmt.f(r.stop)));
        return sb.toString();
    }
}
