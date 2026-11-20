package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Instrument and timeframe behavioural profile, calculated only from the candles supplied. Small samples say INSUFFICIENT DATA. */
public final class Profile implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String VERSION = "profile-1";
    public static final int MIN_SAMPLES = 30;

    public String symbol = "", tf = "", source = "Deriv candles supplied to the app";
    public int candles;
    public long from, to;
    public int regimeObservations;
    public int bull, bear, range, expansion, compression, transition, unknown;
    public double avgTrendCandles, avgRangeCandles;
    public int legs;
    public double avgRetracePct, medRetracePct, avgRetraceCandles;
    public double medRangeRel, p90RangeRel, avgBodyRatio, avgUpperWick, avgLowerWick;
    public int breakouts, falseBreakouts;
    public double falseBreakoutRate;
    public String status = "INSUFFICIENT DATA";

    public static Profile build(String symbol, String tf, Candle[] c) {
        Profile p = new Profile();
        p.symbol = symbol; p.tf = tf;
        if (c == null || c.length < 200) return p;
        p.candles = c.length; p.from = c[0].epoch; p.to = c[c.length - 1].epoch;
        // regime sequence, sampled every 5 candles over a 120-candle window
        List<String> seq = new ArrayList<>();
        for (int e = 120; e < c.length; e += 5) {
            Regime.Result r = Regime.classify(java.util.Arrays.copyOfRange(c, e - 119, e + 1));
            seq.add(r.label);
            switch (r.label) {
                case Regime.BULL: p.bull++; break; case Regime.BEAR: p.bear++; break; case Regime.RANGE: p.range++; break;
                case Regime.EXPANSION: p.expansion++; break; case Regime.COMPRESSION: p.compression++; break;
                case Regime.TRANSITION: p.transition++; break; default: p.unknown++;
            }
        }
        p.regimeObservations = seq.size();
        double trendRun = 0, rangeRun = 0; int trendN = 0, rangeN = 0;
        int run = 1;
        for (int i = 1; i <= seq.size(); i++) {
            if (i < seq.size() && seq.get(i).equals(seq.get(i - 1))) { run++; continue; }
            String l = seq.get(i - 1);
            if (l.equals(Regime.BULL) || l.equals(Regime.BEAR)) { trendRun += run * 5; trendN++; }
            else if (l.equals(Regime.RANGE)) { rangeRun += run * 5; rangeN++; }
            run = 1;
        }
        p.avgTrendCandles = trendN == 0 ? 0 : trendRun / trendN; p.avgRangeCandles = rangeN == 0 ? 0 : rangeRun / rangeN;
        // swing legs and retracement depth
        List<Smc.Swing> hi = Smc.swingHighs(c, 3, 0, c.length - 1), lo = Smc.swingLows(c, 3, 0, c.length - 1);
        List<double[]> piv = new ArrayList<>(); // {idx, level, +1 high / -1 low}
        for (Smc.Swing s : hi) piv.add(new double[]{s.idx, s.level, 1});
        for (Smc.Swing s : lo) piv.add(new double[]{s.idx, s.level, -1});
        Collections.sort(piv, new java.util.Comparator<double[]>() { @Override public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); } });
        List<double[]> alt = new ArrayList<>();
        for (double[] q : piv) {
            if (!alt.isEmpty() && alt.get(alt.size() - 1)[2] == q[2]) { double[] l = alt.get(alt.size() - 1); if ((q[2] > 0 && q[1] > l[1]) || (q[2] < 0 && q[1] < l[1])) alt.set(alt.size() - 1, q); }
            else alt.add(q);
        }
        List<Double> ret = new ArrayList<>(); double retC = 0;
        for (int i = 2; i < alt.size(); i++) {
            double leg1 = Math.abs(alt.get(i - 1)[1] - alt.get(i - 2)[1]), leg2 = Math.abs(alt.get(i)[1] - alt.get(i - 1)[1]);
            if (leg1 > 0) { ret.add(100.0 * leg2 / leg1); retC += alt.get(i)[0] - alt.get(i - 1)[0]; }
        }
        p.legs = ret.size();
        if (!ret.isEmpty()) {
            double s = 0; for (double v : ret) s += v; p.avgRetracePct = s / ret.size();
            List<Double> sorted = new ArrayList<>(ret); Collections.sort(sorted); p.medRetracePct = sorted.get(sorted.size() / 2);
            p.avgRetraceCandles = retC / ret.size();
        }
        // candle distributions
        List<Double> rel = new ArrayList<>(); double body = 0, up = 0, lw = 0; int n = 0;
        for (Candle k : c) {
            double r = k.high - k.low; if (r <= 0 || k.close == 0) continue;
            rel.add(r / k.close); body += Math.abs(k.close - k.open) / r; up += (k.high - Math.max(k.open, k.close)) / r; lw += (Math.min(k.open, k.close) - k.low) / r; n++;
        }
        Collections.sort(rel);
        if (n > 0) { p.medRangeRel = rel.get(rel.size() / 2); p.p90RangeRel = rel.get((int) (rel.size() * 0.9)); p.avgBodyRatio = body / n; p.avgUpperWick = up / n; p.avgLowerWick = lw / n; }
        // breakouts of swing highs/lows and how often they fail (close back inside within 3 candles)
        for (Smc.Swing s : hi) countBreak(c, s, true, p);
        for (Smc.Swing s : lo) countBreak(c, s, false, p);
        p.falseBreakoutRate = p.breakouts == 0 ? 0 : 100.0 * p.falseBreakouts / p.breakouts;
        p.status = p.legs >= MIN_SAMPLES && p.breakouts >= MIN_SAMPLES && p.regimeObservations >= MIN_SAMPLES ? "OK (" + p.candles + " candles)" : "INSUFFICIENT DATA";
        return p;
    }

    private static void countBreak(Candle[] c, Smc.Swing s, boolean high, Profile p) {
        for (int k = s.idx + 4; k < Math.min(c.length - 4, s.idx + 60); k++) {
            boolean broke = high ? c[k].close > s.level : c[k].close < s.level;
            if (!broke) continue;
            p.breakouts++;
            for (int j = k + 1; j <= k + 3; j++) if (high ? c[j].close < s.level : c[j].close > s.level) { p.falseBreakouts++; break; }
            return;
        }
    }

    public String describe() {
        if (candles == 0) return symbol + " " + tf + ": no history.";
        StringBuilder sb = new StringBuilder();
        sb.append(symbol).append(' ').append(tf).append(": ").append(status).append(" (").append(candles).append(" candles, ").append(date(from)).append(" to ").append(date(to)).append(")\n");
        if (status.startsWith("INSUFFICIENT")) { sb.append("Not enough observations for confident numbers (need ").append(MIN_SAMPLES).append("+ of each).\n"); }
        sb.append(String.format(Locale.US, "Regimes seen: bull %d, bear %d, range %d, expansion %d, compression %d, transition %d, unknown %d (of %d)\n", bull, bear, range, expansion, compression, transition, unknown, regimeObservations));
        sb.append(String.format(Locale.US, "Trend lasts about %.0f candles, ranges about %.0f candles\n", avgTrendCandles, avgRangeCandles));
        sb.append(String.format(Locale.US, "Retracement: median %.0f%% of the prior leg (average %.0f%%, %.0f candles), %d legs\n", medRetracePct, avgRetracePct, avgRetraceCandles, legs));
        sb.append(String.format(Locale.US, "Candle range: median %.3f%% of price, 90th percentile %.3f%%; body %.0f%%, upper wick %.0f%%, lower wick %.0f%% of range\n", medRangeRel * 100, p90RangeRel * 100, avgBodyRatio * 100, avgUpperWick * 100, avgLowerWick * 100));
        sb.append(String.format(Locale.US, "Breakouts of swing levels: %d, failed within 3 candles: %.0f%%", breakouts, falseBreakoutRate));
        return sb.toString();
    }

    private static String date(long e) { return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(e * 1000)); }
}
