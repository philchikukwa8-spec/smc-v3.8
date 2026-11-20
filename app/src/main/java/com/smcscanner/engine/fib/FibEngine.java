package com.smcscanner.engine.fib;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.mie.CandleBehaviour;
import com.smcscanner.engine.mie.Vol;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Fib pullback strategy with double confirmation, as the trader describes it.
 *
 *  1. H4 bias (D1 as a second opinion): swing highs and lows, then the last break of structure (a CLOSE through the
 *     previous swing high for a buy, through the swing low for a sell).
 *  2. Fib the leg: 0% where the leg started, 100% at the current high. Wait for price to pull back below the 50% level
 *     (the 30% level is a deeper, better pullback).
 *  3. Switch to M15: wait for two consecutive breaks of structure and then a third higher high (lower low for a sell).
 *  4. Fib that M15 impulse: 100% at the current high, 0% at the high that made the first break (HH1). The order goes at
 *     the 50% level (or 30%), the stop on the 0%, the target on the M15 100% (quick) or the H4 100% (longer).
 *
 * No extra filters: no minimum leg or impulse size, no stop cushion, D1 never blocks (it is only shown). A setup whose entry
 * can no longer be taken (price ran to the target without filling, or closed through the stop) is kept and stamped
 * ENTRY EXPIRED. Closed candles only; SELL is found by mirroring prices.
 */
public final class FibEngine {
    private FibEngine() {}

    public static final String VERSION = "fib-1";
    public static final String S_WATCH = "WATCH", S_QUALIFIED = "QUALIFIED", S_ENTRY = "ENTRY-REACHED", S_EXPIRED = "EXPIRED";

    public static final class Options implements Serializable {
        private static final long serialVersionUID = 1L;
        /** where the order goes on the M15 fib, measured from the 0%: 0.5 normally, 0.3 for the deeper entry */
        public double entryFrac = 0.5;
        /** false: take profit on the M15 100%; true: on the H4 100% */
        public boolean targetH4 = false;
        /** false: 0% of the M15 fib is the high that made the first break (as described); true: the swing low under it (wider, safer) */
        public boolean baseLow = false;
        /** only call a setup ready when D1 does not point the other way */
        public boolean requireD1 = false;
        /** kept only so older saved settings still load: the stop sits exactly on the 0% */
        public double stopBuffer = 0;
    }

    /** one step of the checklist shown on the card */
    public static final class Step implements Serializable {
        private static final long serialVersionUID = 1L;
        public String text;
        /** DONE, NOW (being waited for) or TODO */
        public String status;
        Step(String t, String s) { text = t; status = s; }
    }

    public static final class Setup implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id = "", symbol = "", label = "", side = "BUY", state = S_WATCH;
        public String d1 = "", key = "", headline = "", explanation = "", stage = "";
        public final List<Step> steps = new ArrayList<>();
        public final List<String> evidence = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        /** H4 fib: 0% (leg start), 100% (current extreme) and its epochs */
        public double h4Zero, h4Hundred;
        public long h4ZeroEpoch, h4HundredEpoch, h4BosEpoch;
        /** M15 fib: 0% (HH1 or base), 100% (HH3) and epochs; zero when the M15 part has not completed */
        public double mZero, mHundred;
        public long mZeroEpoch, mHundredEpoch;
        public long[] bosEpoch = new long[3];
        public double[] bosLevel = new double[3];
        public int bosCount;
        public double entry, stop, tp1, tp2, price, rr, rrH4;
        public int score;
        public long at;
        /** when the entry expired (0 = not expired) */
        public long expiredAt;
        public boolean actionable() { return state.equals(S_QUALIFIED) || state.equals(S_ENTRY); }
        public boolean isBuy() { return "BUY".equals(side); }
        /** the target the trader chose */
        public double target(Options o) { return o.targetH4 ? tp2 : tp1; }
    }

    // ------------------------------------------------------------------ public entry

    /** both directions; at most one setup per direction, best stage first */
    public static List<Setup> analyse(String symbol, String label, Candle[] h4, Candle[] m15, long now, Options o) {
        List<Setup> out = new ArrayList<>();
        if (h4 == null || m15 == null || h4.length < 60 || m15.length < 120) return out;
        Setup b = analyseBuy(h4, m15, o, now, true);
        Candle[] h4m = Smc.mirror(h4), m15m = Smc.mirror(m15);
        Setup s = analyseBuy(h4m, m15m, o, now, false);
        if (s != null) unmirror(s);
        for (Setup x : new Setup[]{b, s}) {
            if (x == null) continue;
            x.symbol = symbol; x.label = label; x.at = now;
            x.side = x == b ? "BUY" : "SELL";
            finish(x, o);
            out.add(x);
        }
        out.sort((p, q) -> {
            int ap = p.actionable() ? 1 : 0, aq = q.actionable() ? 1 : 0;
            if (ap != aq) return aq - ap;
            return q.bosCount - p.bosCount;
        });
        return out;
    }

    private static void unmirror(Setup s) {
        s.h4Zero = -s.h4Zero; s.h4Hundred = -s.h4Hundred; s.mZero = -s.mZero; s.mHundred = -s.mHundred;
        for (int i = 0; i < 3; i++) s.bosLevel[i] = -s.bosLevel[i];
        s.entry = -s.entry; s.stop = -s.stop; s.tp1 = -s.tp1; s.tp2 = -s.tp2; s.price = -s.price;
    }

    // ------------------------------------------------------------------ the buy logic (a sell runs on mirrored prices)

    static Setup analyseBuy(Candle[] h4, Candle[] m15, Options o, long now, boolean realBuy) {
        final double sg = realBuy ? 1 : -1;
        final String BULL = realBuy ? "bullish" : "bearish", HH = realBuy ? "higher high" : "lower low", ABOVE = realBuy ? "above" : "below", DIPS = realBuy ? "down" : "up";
        int n4 = h4.length;
        double a4 = Vol.atr(h4, 14), a15 = Vol.atr(m15, 14);
        if (!(a4 > 0) || !(a15 > 0)) return null;
        // ---- stage 1: H4 structure
        List<Smc.Swing> hi = Smc.swingHighs(h4, 2, 0, n4 - 1), lo = Smc.swingLows(h4, 2, 0, n4 - 1);
        if (hi.size() < 3 || lo.size() < 3) return null;
        // last break of structure up: the latest candle that closed above the most recent swing high before it
        int bosK = -1, bosSwing = -1;
        for (int k = n4 - 1; k >= 10 && bosK < 0; k--) {
            Smc.Swing ref = null;
            for (Smc.Swing s : hi) if (s.idx + 2 <= k - 1) ref = s; else break;
            if (ref == null) continue;
            if (h4[k].close > ref.level && (k == 0 || h4[k - 1].close <= ref.level)) { bosK = k; bosSwing = ref.idx; }
        }
        if (bosK < 0) return null;
        // the latest break the other way must be older than this one (the last structure break is up)
        int downK = -1;
        for (int k = n4 - 1; k >= 10 && downK < 0; k--) {
            Smc.Swing ref = null;
            for (Smc.Swing s : lo) if (s.idx + 2 <= k - 1) ref = s; else break;
            if (ref != null && h4[k].close < ref.level && h4[k - 1].close >= ref.level) downK = k;
        }
        if (downK > bosK) return null;
        // 0%: the swing low that started the leg (latest confirmed swing low before the break)
        Smc.Swing z = null;
        for (Smc.Swing s : lo) if (s.idx < bosK) z = s; else break;
        if (z == null) return null;
        double zero = z.level;
        int zeroIdx = z.idx;
        // 100%: the current high of the leg (highest high since the break)
        double hund = Double.NEGATIVE_INFINITY; int hundIdx = bosK;
        for (int i = bosK; i < n4; i++) if (h4[i].high > hund) { hund = h4[i].high; hundIdx = i; }
        double leg = hund - zero;
        if (!(leg > 0)) return null;
        double px = h4[n4 - 1].close;
        double f50 = zero + 0.5 * leg, f30 = zero + 0.3 * leg;
        double lowSince = Double.POSITIVE_INFINITY; int lowIdx = hundIdx;
        for (int i = hundIdx; i < n4; i++) if (h4[i].low < lowSince) { lowSince = h4[i].low; lowIdx = i; }
        boolean pulled = lowSince <= f50;                  // dipped to the 50% level or deeper
        double depth = (hund - lowSince) / leg;

        Setup s = new Setup();
        s.h4Zero = zero; s.h4Hundred = hund; s.h4ZeroEpoch = h4[zeroIdx].epoch; s.h4HundredEpoch = h4[hundIdx].epoch; s.h4BosEpoch = h4[bosK].epoch;
        s.price = m15[m15.length - 1].close;
        final String d1m = d1Bias(h4);
        s.d1 = realBuy ? d1m : d1m.equals("bull") ? "bear" : d1m.equals("bear") ? "bull" : "range";
        s.steps.add(new Step("H4 is " + BULL + ": the last break of structure was " + (realBuy ? "up" : "down") + " (close " + ABOVE + " " + F(sg, realBuy ? h4[bosSwing].high : -h4[bosSwing].low) + ").", "DONE"));
        s.steps.add(new Step("H4 fib drawn: 0% at " + F(sg, zero) + ", 100% at " + F(sg, hund) + ".", "DONE"));
        if (!pulled) {
            s.steps.add(new Step("Waiting for the " + (realBuy ? "pullback" : "bounce") + " to reach the 50% level (" + F(sg, f50) + "); the deeper 30% level is " + F(sg, f30) + ".", "NOW"));
            s.steps.add(new Step("Then M15: two breaks of structure and a third " + HH + ".", "TODO"));
            s.steps.add(new Step("Then the order goes at the M15 fib's 50%.", "TODO"));
            s.stage = "1 of 3: waiting for the H4 pullback";
            s.state = S_WATCH;
            s.evidence.add(String.format(Locale.US, "Price is %.0f%% of the way back along the leg (needs 50%%).", 100 * Math.max(0, (hund - px) / leg)));
            return s;
        }
        s.steps.add(new Step(String.format(Locale.US, "Pullback reached %.0f%% of the leg (the 50%% level is %s).", depth * 100, F(sg, f50)), "DONE"));
        s.evidence.add(String.format(Locale.US, "The pullback went %.0f%% of the way back along the H4 leg%s.", depth * 100, depth <= 0.786 ? "" : " (deep: past the 78.6% level, so the leg is weaker)"));

        // ---- stage 2: M15 double confirmation, starting from the lowest low since the H4 high
        long hEpoch = h4[hundIdx].epoch;
        int pb = -1; double pbLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < m15.length; i++) if (m15[i].epoch >= hEpoch && m15[i].low < pbLow) { pbLow = m15[i].low; pb = i; }
        if (pb < 0 || pb > m15.length - 6) {
            s.steps.add(new Step("M15 has not turned yet: waiting for two breaks of structure and a third " + HH + ".", "NOW"));
            s.steps.add(new Step("Then the order goes at the M15 fib's 50%.", "TODO"));
            s.stage = "2 of 3: waiting for the M15 confirmation (0 of 3 breaks)";
            return s;
        }
        List<Smc.Swing> sh = Smc.swingHighs(m15, 3, pb, m15.length - 1);
        int[] k = new int[3]; double[] lv = new double[3];
        int cnt = 0, lastK = pb; double lastLevel = Double.NEGATIVE_INFINITY;
        for (int i = pb + 1; i < m15.length && cnt < 3; i++) {
            Smc.Swing ref = null;
            for (Smc.Swing q : sh) if (q.idx + 3 <= i - 1 && q.idx > lastK - (cnt == 0 ? 1 : 0)) ref = q;
            if (ref == null) continue;
            if (ref.level > lastLevel && m15[i].close > ref.level && m15[i - 1].close <= ref.level) {
                k[cnt] = i; lv[cnt] = ref.level; cnt++; lastK = i; lastLevel = ref.level;
            }
        }
        s.bosCount = cnt;
        for (int i = 0; i < cnt; i++) { s.bosEpoch[i] = m15[k[i]].epoch; s.bosLevel[i] = lv[i]; }
        if (cnt < 3) {
            String[] nm = {"first", "second", "third"};
            s.steps.add(new Step("M15: " + cnt + " of 3 breaks so far (two consecutive breaks of structure, then a third " + HH + ").", cnt == 0 ? "NOW" : "NOW"));
            if (cnt > 0) s.evidence.add("M15 " + nm[cnt - 1] + " break of structure at " + F(sg, lv[cnt - 1]) + ".");
            s.steps.add(new Step("Then the order goes at the M15 fib's 50%.", "TODO"));
            s.stage = "2 of 3: M15 confirmation, " + cnt + " of 3 breaks";
            return s;
        }
        // ---- stage 3: the M15 fib
        // HH1 = the high that the second break closed through (the peak formed after the first break); HH3 = the running high now
        double hh1 = lv[1];
        double hh3 = Double.NEGATIVE_INFINITY; int hh3i = k[2];
        for (int i = k[2]; i < m15.length; i++) if (m15[i].high > hh3) { hh3 = m15[i].high; hh3i = i; }
        double zeroM = hh1; long zeroE = 0;
        for (Smc.Swing q : sh) if (Math.abs(q.level - hh1) < 1e-9) zeroE = m15[q.idx].epoch;
        if (o.baseLow) {
            // the swing low under the first break: wider and safer
            double bl = Double.POSITIVE_INFINITY; int bi = k[0];
            for (int i = pb; i <= k[0]; i++) if (m15[i].low < bl) { bl = m15[i].low; bi = i; }
            zeroM = bl; zeroE = m15[bi].epoch;
        }
        double range = hh3 - zeroM;
        if (!(range > 0)) return null;
        double entry = zeroM + o.entryFrac * range;
        double stop = zeroM;
        // never let the entry sit above the current price: it would not be a pullback order
        double last = m15[m15.length - 1].close;
        boolean touched = false;
        for (int i = hh3i + 1; i < m15.length; i++) if (m15[i].low <= entry) touched = true;
        if (m15[m15.length - 1].low <= entry) touched = true;
        // the order is dead if price closed through the stop after the third break
        boolean stoppedOut = false;
        for (int i = k[2]; i < m15.length; i++) if (m15[i].close < stop) stoppedOut = true;
        double tp1 = hh3;
        double tp2 = hund > hh3 ? hund : zeroM + 1.272 * range;
        // ran to the target without filling, or already through the target / the stop: the entry is no longer available
        String expiredWhy = null;
        if (stoppedOut) expiredWhy = "Price closed through the stop (the M15 0%) so the idea is invalid.";
        else if (!touched && last >= tp1) expiredWhy = "Price ran to the M15 100% without ever coming back to the entry.";
        else if (touched && last >= tp2) expiredWhy = "Price already reached the H4 100% target.";
        s.mZero = zeroM; s.mHundred = hh3; s.mZeroEpoch = zeroE; s.mHundredEpoch = m15[hh3i].epoch;
        s.entry = Math.min(entry, last); s.stop = stop; s.tp1 = tp1; s.tp2 = tp2;
        double risk = s.entry - s.stop;
        if (!(risk > 0)) return null;
        s.rr = (tp1 - s.entry) / risk; s.rrH4 = (tp2 - s.entry) / risk;
        s.state = expiredWhy != null ? S_EXPIRED : touched ? S_ENTRY : S_QUALIFIED;
        if (expiredWhy != null) s.conflicts.add("ENTRY EXPIRED: " + expiredWhy);
        s.steps.add(new Step("M15: three breaks of structure in a row (" + F(sg, lv[0]) + ", " + F(sg, lv[1]) + ", " + F(sg, lv[2]) + ").", "DONE"));
        s.steps.add(new Step(String.format(Locale.US, "M15 fib drawn: 0%% at %s (%s), 100%% at %s.", F(sg, zeroM), o.baseLow ? "base low" : (realBuy ? "the high that made the first break" : "the low that made the first break"), F(sg, hh3)), "DONE"));
        s.steps.add(new Step(touched ? "Price has reached the " + (int) Math.round(o.entryFrac * 100) + "% level: the order is live." : "Order waiting at the " + (int) Math.round(o.entryFrac * 100) + "% level: " + F(sg, s.entry) + ".", touched ? "DONE" : "NOW"));
        s.stage = touched ? "3 of 3: entry level reached" : "3 of 3: order ready, waiting for price to dip to the entry";
        // ---- evidence and score
        int score = 2;                                   // H4 break + M15 three breaks
        s.evidence.add("DOUBLE CONFIRMATION: the H4 structure break and pullback, and three consecutive M15 breaks of structure.");
        if (d1m.equals("bull")) { score++; s.evidence.add("D1 agrees (" + (realBuy ? "higher highs and higher lows" : "lower highs and lower lows") + ")."); }
        else if (d1m.equals("bear")) { score--; s.conflicts.add("D1 points the other way: this trade is against the daily trend."); }
        else s.evidence.add("D1 is a range: no daily confirmation either way.");
        if (depth >= 0.382 && depth <= 0.786) { score++; s.evidence.add("A healthy H4 pullback (between 38.2% and 78.6%)."); }
        if (s.entry <= f50) { score++; s.evidence.add("The M15 entry sits inside the H4 " + (realBuy ? "discount" : "premium") + " zone (" + (realBuy ? "below" : "above") + " the H4 50% level): both fibs agree."); }
        else s.conflicts.add("The M15 entry is on the wrong side of the H4 50% level, so it is not fully in the H4 " + (realBuy ? "discount" : "premium") + " zone.");
        if (range >= 4 * a15) { score++; s.evidence.add(String.format(Locale.US, "The M15 impulse is strong (%.1f ATR).", range / a15)); }
        if (n4 - bosK <= 30) { score++; s.evidence.add("The H4 break of structure is recent."); }
        String sig = touched ? signal(m15) : null;
        if (sig != null) { score++; s.evidence.add("Signal candle at the entry: " + sig + "."); }
        s.score = Math.max(0, score);
        s.id = "FIB|" + 0 + "|" + s.bosEpoch[0];   // symbol and side are added in finish()
        return s;
    }

    private static String signal(Candle[] c) {
        int n = c.length;
        if (n < 20) return null;
        for (CandleBehaviour.Tag t : CandleBehaviour.at(c, n - 1)) {
            if (!t.bias.equals("BUY")) continue;
            if (t.name.contains("rejection")) return "pin bar";
            if (t.name.contains("engulfing")) return "engulfing candle";
        }
        return null;
    }

    /** D1 built from the H4 candles (six per day): "bull", "bear" or "range" */
    static String d1Bias(Candle[] h4) {
        List<Candle> d = new ArrayList<>();
        long day = -1; double o = 0, hi = 0, lo = 0, cl = 0; long ep = 0;
        for (Candle c : h4) {
            long dd = c.epoch / 86400;
            if (dd != day) {
                if (day >= 0) d.add(new Candle(ep, o, hi, lo, cl));
                day = dd; o = c.open; hi = c.high; lo = c.low; ep = dd * 86400;
            } else { hi = Math.max(hi, c.high); lo = Math.min(lo, c.low); }
            cl = c.close;
        }
        // the last (open) day is left out
        if (d.size() < 12) return "range";
        return Smc.structureBias(d.toArray(new Candle[0]), 2, 60);
    }

    private static String F(double sg, double v) { return Fmt.f(sg * v); }

    // ------------------------------------------------------------------ finishing: ids, keys, words

    private static void finish(Setup s, Options o) {
        boolean buy = s.isBuy();
        String bias = buy ? "bullish" : "bearish";
        long anchor = s.actionable() || s.bosEpoch[0] > 0 ? s.bosEpoch[0] : s.h4BosEpoch;
        boolean m15Done = s.actionable() || s.state.equals(S_EXPIRED);
        s.id = (m15Done ? "FIB|" : "FIBW|") + s.symbol + "|" + s.side + "|" + (m15Done ? s.bosEpoch[0] : s.h4BosEpoch);
        String d1 = s.d1.equals("bull") ? (buy ? "D1 with" : "D1 against") : s.d1.equals("bear") ? (buy ? "D1 against" : "D1 with") : "D1 range";
        s.key = "Fib " + s.side.toLowerCase(Locale.US) + ", " + d1 + ", entry " + (int) Math.round(o.entryFrac * 100) + "%";
        if (!buy) {
            // sell wording uses the mirrored d1 labels
        }
        String sideWord = buy ? "buy" : "sell";
        if (s.state.equals(S_EXPIRED)) {
            s.headline = s.label + ": Fib pullback " + sideWord + " (ENTRY EXPIRED)";
            s.explanation = "ENTRY EXPIRED. " + (s.conflicts.isEmpty() ? "" : s.conflicts.get(s.conflicts.size() - 1).replace("ENTRY EXPIRED: ", "")) + " Do not place this order. It stays at the bottom of the list until you remove it.";
        } else if (s.actionable()) {
            s.headline = s.label + ": Fib pullback " + sideWord + (s.state.equals(S_ENTRY) ? " (entry level reached)" : " (order ready)");
            StringBuilder sb = new StringBuilder();
            sb.append("H4 is ").append(bias).append(" and pulled back into the 50% zone of its last leg. On the M15 price then made three consecutive breaks of structure, so the second fib was drawn on that impulse.\n");
            sb.append(String.format(Locale.US, "Entry %s (the %d%% level of the M15 fib), stop %s (its 0%%), target %s (M15 100%%, %.1fR) or %s (H4 100%%, %.1fR).\n", Fmt.f(s.entry), (int) Math.round(o.entryFrac * 100), Fmt.f(s.stop), Fmt.f(s.tp1), s.rr, Fmt.f(s.tp2), s.rrH4));
            for (String e : s.evidence) sb.append("+ ").append(e).append('\n');
            for (String c : s.conflicts) sb.append("! ").append(c).append('\n');
            sb.append("This is a pattern the engine measures, not a forecast: check the Lab for how it has ended on this market.");
            s.explanation = sb.toString();
        } else {
            s.headline = s.label + ": Fib pullback " + sideWord + " forming";
            s.explanation = "Stage " + s.stage + ". Nothing to place yet.";
        }
    }

    /** a short text of the idea, for notifications */
    public static String line(Setup s, Options o) {
        return String.format(Locale.US, "%s | entry %s | stop %s | target %s | %.1fR", s.isBuy() ? "BUY" : "SELL", Fmt.f(s.entry), Fmt.f(s.stop), Fmt.f(s.target(o)), (Math.abs(s.target(o) - s.entry) / Math.max(1e-9, Math.abs(s.entry - s.stop))));
    }
}
