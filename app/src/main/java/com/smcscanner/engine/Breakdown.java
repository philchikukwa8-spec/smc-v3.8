package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * The full explanation of a trade: levels, why the entry is valid, a pass/fail checklist,
 * what to watch out for, and what would invalidate it.
 *
 * The grade is the share of "quality" factors met. It is NOT a probability of winning:
 * which factors really help can only be measured by the backtest.
 */
public final class Breakdown implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String[] QUALITY_KEYS = {"trend", "discount", "majorSweep", "strongDisp", "overlap", "rrGood"};

    /** group: "idea", "entry" or "quality". pass: TRUE met, FALSE not met, null can't be judged. */
    public static final class Factor implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String key, label, group, detail;
        public final Boolean pass;
        public Factor(String key, String label, String group, Boolean pass, String detail) {
            this.key = key; this.label = label; this.group = group; this.pass = pass; this.detail = detail;
        }
    }

    public static final class Levels implements Serializable {
        private static final long serialVersionUID = 1L;
        public double entry, stop, riskPts, riskPct, tp1, rr1;
        public String tp1Label, tp2Label;
        /** null when no higher-timeframe liquidity was found */
        public Double tp2, rr2;
    }

    /** one way to enter inside the zone */
    public static final class EntryOption implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public double entry, riskPts, rr1;
        public Double rr2;
        public boolean chosen;
    }

    /** one rung of the zoom-in ladder: the entry zone and each finer timeframe */
    public static final class Rung implements Serializable {
        private static final long serialVersionUID = 1L;
        public String label;
        public double entry, stop, riskPts, rr1;
    }

    /** e.g. "4H idea > 1H > 5m > 1m" (empty when there was no zoom-in) */
    public String path = "";
    public ArrayList<Rung> ladder = new ArrayList<>();

    public String side, ideaTf, entryTf, nextTf;
    public ArrayList<EntryOption> options = new ArrayList<>();
    /** past win rate of similar trades (null = not calibrated yet) */
    public Calibration.Estimate confidence;
    /** how much the user chose to risk per trade (0 = not set) */
    public double riskMoney;
    public int entryMode = 1;
    public Levels levels;
    public ArrayList<Factor> factors = new ArrayList<>();
    public int qualityMet, qualityOf;
    /** "A", "B" or "C" */
    public String grade;
    public ArrayList<String> story = new ArrayList<>();
    public ArrayList<String> risks = new ArrayList<>();
    public ArrayList<String> invalidation = new ArrayList<>();

    public Factor factor(String key) {
        for (Factor f : factors) if (f.key.equals(key)) return f;
        return null;
    }
}
