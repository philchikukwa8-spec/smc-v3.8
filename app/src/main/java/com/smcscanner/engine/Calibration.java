package com.smcscanner.engine;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns backtest results into an honest "how often did trades like this win in the past" figure.
 * This is a measured historical hit rate (with its sample size and a range), NOT a prediction.
 */
public final class Calibration implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String[] MODE_NAMES = {"Edge of zone", "Middle of zone", "Deep in zone"};
    public static final String[] GRADES = {"A", "B", "C"};

    /** Results for one entry style. */
    public static final class ModeStat implements Serializable {
        private static final long serialVersionUID = 1L;
        public int setups, filled, wins, noFill;
        public double totalR;
        public final int[] gradeN = new int[3], gradeWins = new int[3];
        public final double[] gradeR = new double[3];
    }

    public final ModeStat[] modes = new ModeStat[]{new ModeStat(), new ModeStat(), new ModeStat()};
    public long builtAt;
    /** entry style with the highest total R on the tested history (middle if there is too little data) */
    public int bestMode = 1;

    /** Builds from the top-down backtest trades of each entry style (index 0, 1, 2). */
    public static Calibration build(List<List<Mtf.FactorTrade>> byMode, long builtAt) {
        Calibration c = new Calibration();
        c.builtAt = builtAt;
        for (int m = 0; m < 3; m++) {
            ModeStat s = c.modes[m];
            for (Mtf.FactorTrade t : byMode.get(m)) {
                s.setups++;
                if (!t.trade.decided()) { if (t.trade.outcome.equals("no_fill")) s.noFill++; continue; }
                s.filled++;
                boolean win = t.trade.outcome.equals("win");
                if (win) s.wins++;
                s.totalR += t.trade.r;
                int g = t.breakdown.grade.charAt(0) - 'A';
                if (g >= 0 && g < 3) { s.gradeN[g]++; if (win) s.gradeWins[g]++; s.gradeR[g] += t.trade.r; }
            }
        }
        double best = Double.NEGATIVE_INFINITY;
        c.bestMode = 1;
        for (int m = 0; m < 3; m++) {
            if (c.modes[m].filled >= 10 && c.modes[m].totalR > best) { best = c.modes[m].totalR; c.bestMode = m; }
        }
        return c;
    }

    public static final class Estimate implements Serializable {
        private static final long serialVersionUID = 1L;
        /** win rate in % */
        public double pct, lo, hi, breakEven;
        public int n, wins;
        /** where the number comes from, e.g. "grade A on this pair" */
        public String basis;
        /** true when the sample is big enough (30+ trades) to mean much */
        public boolean reliable;
    }

    /** Wilson 90% interval. */
    static double[] wilson(int wins, int n) {
        double z = 1.645, p = (double) wins / n, z2 = z * z;
        double centre = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    private static Estimate make(int wins, int n, double rr, String basis) {
        Estimate e = new Estimate();
        e.n = n; e.wins = wins; e.basis = basis;
        e.pct = n > 0 ? 100.0 * wins / n : 0;
        double[] w = n > 0 ? wilson(wins, n) : new double[]{0, 1};
        e.lo = 100 * w[0]; e.hi = 100 * w[1];
        e.breakEven = rr > 0 ? 100.0 / (1 + rr) : 0;
        e.reliable = n >= 30;
        return e;
    }

    /** Stored calibrations, one per market + idea timeframe + entry timeframe. */
    public static final class Book implements Serializable {
        private static final long serialVersionUID = 1L;
        public final HashMap<String, Calibration> items = new HashMap<>();

        public static String key(String market, String ideaTf, String entryTf) { return market + "|" + ideaTf + "|" + entryTf; }

        public int bestMode(String market, String ideaTf, String entryTf) {
            Calibration c = items.get(key(market, ideaTf, entryTf));
            return c != null ? c.bestMode : 1;
        }

        /**
         * Best available estimate for a trade of this grade. Uses this exact market and pair when it has
         * 30+ decided trades, otherwise pools every stored pair, otherwise reports the small sample plainly.
         */
        public Estimate estimate(String market, String ideaTf, String entryTf, int mode, String grade, double rr) {
            int g = grade.charAt(0) - 'A';
            if (g < 0 || g > 2) return null;
            Calibration own = items.get(key(market, ideaTf, entryTf));
            int ownN = own != null ? own.modes[mode].gradeN[g] : 0, ownW = own != null ? own.modes[mode].gradeWins[g] : 0;
            if (ownN >= 30) return make(ownW, ownN, rr, "grade " + grade + " on " + market + " " + ideaTf + ">" + entryTf);
            int poolN = 0, poolW = 0;
            for (Calibration c : items.values()) { poolN += c.modes[mode].gradeN[g]; poolW += c.modes[mode].gradeWins[g]; }
            if (poolN >= 30) return make(poolW, poolN, rr, "grade " + grade + " across all tested pairs");
            if (poolN > 0 && poolN >= ownN) return make(poolW, poolN, rr, "grade " + grade + " across all tested pairs (small sample)");
            if (ownN > 0) return make(ownW, ownN, rr, "grade " + grade + " on this pair (small sample)");
            return null;
        }
    }
}
