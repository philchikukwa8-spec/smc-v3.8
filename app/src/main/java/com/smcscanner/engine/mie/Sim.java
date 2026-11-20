package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;

/**
 * Outcome simulation for a limit entry. Same rules as Backtest.simulate (entry must be touched; a close beyond the stop
 * before the fill cancels it; if one candle touches both stop and target the STOP counts first), plus MFE/MAE in R,
 * fill and exit times. Costs are not modelled: results are before spread and slippage.
 */
public final class Sim {
    private Sim() {}

    public static final class Out {
        public String outcome = "no_fill";
        public double resultR, mfe, mae;
        public boolean filled;
        public long fillEpoch, exitEpoch;
    }

    /** candles AFTER index `from` are examined (the candle at `from` is the one that closed when the setup was known) */
    public static Out run(Candle[] c, int from, boolean buy, double entry, double stop, double target, int maxWait, int maxHold) {
        Out o = new Out();
        double risk = Math.abs(entry - stop);
        if (risk <= 0) return o;
        double rr = Math.abs(target - entry) / risk;
        boolean filled = false;
        double best = 0, worst = 0;
        for (int k = from + 1; k < c.length; k++) {
            Candle x = c[k];
            if (!filled) {
                if (k - from > maxWait) return o;
                boolean invalid = buy ? x.close < stop : x.close > stop;
                if (invalid) return o;
                boolean touched = buy ? x.low <= entry : x.high >= entry;
                if (!touched) continue;
                filled = true; o.filled = true; o.fillEpoch = x.epoch;
            }
            double fav = buy ? (x.high - entry) / risk : (entry - x.low) / risk;
            double adv = buy ? (entry - x.low) / risk : (x.high - entry) / risk;
            boolean hitSl = buy ? x.low <= stop : x.high >= stop;
            boolean hitTp = buy ? x.high >= target : x.low <= target;
            if (hitSl) { worst = Math.max(worst, adv); o.mae = Math.min(worst, 1.0); o.mfe = best; o.outcome = "loss"; o.resultR = -1; o.exitEpoch = x.epoch; return o; }
            best = Math.max(best, fav); worst = Math.max(worst, adv);
            if (hitTp) { o.mfe = Math.max(best, rr); o.mae = worst; o.outcome = "win"; o.resultR = rr; o.exitEpoch = x.epoch; return o; }
            if (k - from > maxWait + maxHold) { o.mfe = best; o.mae = worst; o.outcome = "expired"; o.exitEpoch = x.epoch; return o; }
        }
        o.mfe = best; o.mae = worst;
        o.outcome = filled ? "open" : "no_fill";
        return o;
    }
}
