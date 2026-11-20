package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Patterns;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Hybrid target selection: candidates come from real structure (swing liquidity, support/resistance zones, confirmed
 * pattern projections). The target is never moved further away to reach a reward:risk. Levels closer than 1R are
 * reported as obstructions instead.
 */
public final class TargetPicker {
    private TargetPicker() {}

    public static final class Candidate implements Serializable {
        private static final long serialVersionUID = 1L;
        public double level;
        public String type;
        /** structural weight: 4H = 3, 1H or strong zone or confirmed pattern = 2, 30m = 1 */
        public int weight;
        public double rMultiple;
        @Override public String toString() { return type + " " + com.smcscanner.engine.Fmt.f(level) + " (" + String.format(java.util.Locale.US, "%.1f", rMultiple) + "R)"; }
    }

    public static final class Pick implements Serializable {
        private static final long serialVersionUID = 1L;
        public Candidate target;
        public Candidate obstruction;
        public List<Candidate> all = new ArrayList<>();
        public String why = "";
    }

    /** all numbers are in real price terms; `buy` selects the direction. Any of h4/h1/m30 may be null. */
    public static Pick pick(boolean buy, double entry, double stop, Candle[] h4, Candle[] h1, Candle[] m30) {
        Pick p = new Pick();
        double risk = Math.abs(entry - stop);
        if (risk <= 0) { p.why = "Invalid stop distance."; return p; }
        List<Candidate> cs = new ArrayList<>();
        addLiq(cs, buy, entry, h4, "4H swing liquidity", 3, 100);
        addLiq(cs, buy, entry, h1, "1H swing liquidity", 2, 150);
        addLiq(cs, buy, entry, m30, "30m swing liquidity", 1, 150);
        addZones(cs, buy, entry, h4, "4H", 3);
        addZones(cs, buy, entry, h1, "1H", 2);
        addPatterns(cs, buy, entry, h4, "4H");
        addPatterns(cs, buy, entry, h1, "1H");
        for (Candidate c : cs) c.rMultiple = Math.abs(c.level - entry) / risk;
        Collections.sort(cs, new Comparator<Candidate>() { @Override public int compare(Candidate a, Candidate b) { return Double.compare(a.rMultiple, b.rMultiple); } });
        p.all = cs;
        Candidate near = null;
        for (Candidate c : cs) if (c.rMultiple < 1.0 && c.weight >= 2) near = c;
        p.obstruction = near;
        Candidate strong = null, any = null;
        for (Candidate c : cs) {
            if (c.rMultiple < 1.0) continue;
            if (any == null) any = c;
            if (strong == null && c.weight >= 2) strong = c;
        }
        p.target = strong != null ? strong : any;
        if (p.target == null) p.why = "No realistic structure beyond 1R in the trade direction.";
        else p.why = "Nearest " + (p.target.weight >= 2 ? "significant " : "") + "level at least 1R away: " + p.target;
        return p;
    }

    private static void addLiq(List<Candidate> out, boolean buy, double entry, Candle[] c, String type, int w, int look) {
        if (c == null || c.length < 30) return;
        Double t = Smc.nextLiquidity(c, buy, entry, 2, look);
        if (t != null) { Candidate k = new Candidate(); k.level = t; k.type = type; k.weight = w; out.add(k); }
    }

    private static void addZones(List<Candidate> out, boolean buy, double entry, Candle[] c, String tf, int w) {
        if (c == null || c.length < 40) return;
        for (Patterns.Zone z : Patterns.keyZones(c, 8)) {
            if (buy && z.low > entry) { Candidate k = new Candidate(); k.level = z.low; k.type = tf + " resistance zone (" + z.touches + "x)"; k.weight = z.touches >= 2 ? w : 1; out.add(k); }
            if (!buy && z.high < entry) { Candidate k = new Candidate(); k.level = z.high; k.type = tf + " support zone (" + z.touches + "x)"; k.weight = z.touches >= 2 ? w : 1; out.add(k); }
        }
    }

    private static void addPatterns(List<Candidate> out, boolean buy, double entry, Candle[] c, String tf) {
        if (c == null || c.length < 60) return;
        for (Patterns.Pattern pt : Patterns.detect(c)) {
            if (!"confirmed".equals(pt.status) || pt.target <= 0) continue;
            if (buy && pt.isBuy() && pt.target > entry) { Candidate k = new Candidate(); k.level = pt.target; k.type = tf + " " + pt.name + " projection"; k.weight = 2; out.add(k); }
            if (!buy && "SELL".equals(pt.bias) && pt.target < entry) { Candidate k = new Candidate(); k.level = pt.target; k.type = tf + " " + pt.name + " projection"; k.weight = 2; out.add(k); }
        }
    }
}
