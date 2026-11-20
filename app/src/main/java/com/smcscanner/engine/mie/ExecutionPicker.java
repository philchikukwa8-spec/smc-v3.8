package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the entry on the 5m or the 1m once the 15m has confirmed the setup. Both are evaluated when data exists.
 * 1M is chosen only when its trigger is valid, at least as well confirmed, its stop is not just noise and it clearly
 * improves reward:risk; otherwise the 5M is preferred. If neither gives a trigger there is no entry (WATCH).
 */
public final class ExecutionPicker {
    private ExecutionPicker() {}

    public static final class Cand implements Serializable {
        private static final long serialVersionUID = 1L;
        public String tf;
        public boolean evaluated, valid;
        public double entry, stop, rr, trigger;
        public int quality;
        public String evidence = "", note = "";
    }

    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;
        public Cand m5 = new Cand(), m1 = new Cand();
        public Cand chosen;
        public String why = "";
    }

    /**
     * @param zoneLow/zoneHigh the 15m-validated zone, stopFloor the structural stop from the 15m setup
     * @param target the target level already chosen from structure (used to compare reward:risk)
     * @param m15Atr ATR of the 15m chart (volatility sanity check for stop distance)
     */
    public static Result pick(boolean buy, double zoneLow, double zoneHigh, double stopFloor, double target, double m15Atr, Candle[] m5, Candle[] m1) {
        Result r = new Result();
        r.m5 = eval("5M", buy, zoneLow, zoneHigh, stopFloor, target, m15Atr, m5, 36);
        r.m1 = eval("1M", buy, zoneLow, zoneHigh, stopFloor, target, m15Atr, m1, 90);
        Cand a = r.m5, b = r.m1;
        if (a.valid && b.valid) {
            if (b.quality >= a.quality && b.rr >= 1.25 * a.rr) { r.chosen = b; r.why = "1M chosen: valid trigger, confirmation at least as good as 5M, and reward:risk " + d1(b.rr) + " vs " + d1(a.rr) + " on 5M."; }
            else { r.chosen = a; r.why = "5M preferred: " + (b.quality < a.quality ? "its confirmation is stronger than the 1M's" : "the 1M does not improve reward:risk enough (" + d1(b.rr) + " vs " + d1(a.rr) + ")") + "."; }
        } else if (a.valid) { r.chosen = a; r.why = "5M trigger valid" + (b.evaluated ? "; 1M gave no valid trigger (" + b.note + ")." : "; 1M not evaluated (" + b.note + ")."); }
        else if (b.valid) { r.chosen = b; r.why = "1M trigger valid; 5M gave none (" + a.note + ")."; }
        else r.why = "No valid 5M or 1M trigger yet. 5M: " + a.note + " 1M: " + b.note;
        return r;
    }

    private static String d1(double v) { return String.format(Locale.US, "%.1f", v); }

    private static Cand eval(String tf, boolean buy, double zl, double zh, double stopFloor, double target, double a15, Candle[] cRaw, int window) {
        Cand k = new Cand();
        k.tf = tf;
        if (cRaw == null || cRaw.length < window + 20) { k.note = "not enough " + tf + " data."; return k; }
        k.evaluated = true;
        Candle[] c = buy ? cRaw : Smc.mirror(cRaw);
        double mzl = buy ? zl : -zh, mzh = buy ? zh : -zl, mfloor = buy ? stopFloor : -stopFloor, mtarget = buy ? target : -target;
        int n = c.length, from = n - window;
        double atrX = Vol.atr(c, 14);
        if (atrX <= 0) { k.note = "no volatility reading."; return k; }
        // the lowest low in the window, and it must have been inside or below the zone's top
        int low = from;
        for (int i = from; i < n; i++) if (c[i].low <= c[low].low) low = i;
        if (c[low].low > mzh + 0.2 * a15) { k.note = "price has not reached the zone on this chart."; return k; }
        // counter-move high before that low (micro structure)
        double trig = Double.NaN;
        for (int j = Math.max(2, low - 12); j < low - 1; j++) {
            boolean ok = true;
            for (int q = 1; q <= 2 && ok; q++) if (!(c[j].high > c[j - q].high) || !(c[j].high >= c[j + q].high)) ok = false;
            if (ok) trig = c[j].high;
        }
        if (Double.isNaN(trig)) { trig = c[Math.max(0, low - 4)].high; for (int j = Math.max(0, low - 4); j < low; j++) trig = Math.max(trig, c[j].high); }
        int ti = -1;
        for (int i = low + 1; i < n; i++) if (c[i].close > trig) { ti = i; break; }
        if (ti < 0) { k.note = "no micro structure shift yet (needs a close above " + Fmt.f(buy ? trig : -trig) + ")."; return k; }
        k.trigger = buy ? trig : -trig;
        // entry candidates inside the leg low..ti: order block body top, fair value gap middle, half the leg
        double legHigh = c[low].high;
        for (int i = low; i <= ti; i++) legHigh = Math.max(legHigh, c[i].high);
        double half = (c[low].low + legHigh) / 2;
        double entry = half;
        String basis = "50% of the " + tf + " leg";
        int ob = -1;
        for (int i = low; i >= Math.max(0, low - 3); i--) if (c[i].close < c[i].open) { ob = i; break; }
        if (ob >= 0) { double top = Math.max(c[ob].open, c[ob].close); if (top > c[low].low && top < legHigh) { entry = top; basis = tf + " order block"; } }
        for (int i = low + 2; i <= ti; i++) if (c[i].low > c[i - 2].high) { double mid = (c[i].low + c[i - 2].high) / 2; if (mid < entry) { entry = mid; basis = tf + " fair value gap"; } break; }
        double px = c[n - 1].close;
        if (px < entry) { entry = px; basis = "current price (already at the zone)"; }
        // structural stop: just beyond the micro low that this chart sees (the 15m structure already defined the zone)
        double stop = c[low].low - 0.25 * atrX;
        double risk = entry - stop;
        if (risk <= 0) { k.note = "invalid stop."; return k; }
        if (risk < 0.3 * a15) { k.note = "stop only " + d1(risk / a15) + " of a 15m ATR: inside the noise."; return k; }
        if (risk > 3 * a15) { k.note = "stop is wider than 3 15m ATR."; return k; }
        // quality: sweep of a previous micro low, displacement candle, candle confirmation
        int q = 0;
        String ev = "";
        double prevLow = Double.NaN;
        for (int j = Math.max(2, low - 25); j < low - 2; j++) {
            boolean ok = true;
            for (int z = 1; z <= 2 && ok; z++) if (!(c[j].low < c[j - z].low) || !(c[j].low <= c[j + z].low)) ok = false;
            if (ok) prevLow = c[j].low;
        }
        if (!Double.isNaN(prevLow) && c[low].low < prevLow) { q++; ev += "micro sweep, "; }
        boolean disp = false;
        for (int i = low + 1; i <= ti; i++) if (c[i].close > c[i].open && c[i].close - c[i].open >= 1.3 * atrX) disp = true;
        if (disp) { q++; ev += "displacement, "; }
        String tag = CandleBehaviour.firstTag(c, true, 6);
        if (tag != null) { q++; ev += tag.toLowerCase(Locale.US) + ", "; }
        k.quality = q;
        k.valid = true;
        k.entry = buy ? entry : -entry;
        k.stop = buy ? stop : -stop;
        k.rr = Math.abs(mtarget - entry) / risk;
        k.evidence = tf + " closed above " + Fmt.f(k.trigger) + (ev.isEmpty() ? "" : " with " + ev.substring(0, ev.length() - 2)) + "; entry at " + basis;
        if (!(mtarget > entry)) { k.valid = false; k.note = "target is not beyond the entry."; }
        return k;
    }
}
