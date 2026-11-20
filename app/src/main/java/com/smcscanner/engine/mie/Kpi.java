package com.smcscanner.engine.mie;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * KPI catalogue with fixed definitions. Only DECIDED trades (win or loss, entry filled) count as completed.
 * Open, unfilled and expired setups are never counted as trades. Same-bar stop/target ambiguity is already
 * resolved conservatively (stop first) by the outcome simulation.
 */
public final class Kpi implements Serializable {
    private static final long serialVersionUID = 1L;

    public int total, completed, wins, losses, filled, qualified, unfilled, expired, rejected, watched;
    public double winRate, lossRate, avgWinR, avgLossR, expectancy, grossProfit, grossLoss, profitFactor, netR, maxDrawdownR, avgHoldMin, avgPlannedR, avgRealisedR, avgMfe, avgMae;
    public int longestWin, longestLoss;
    public double ciLow, ciHigh;
    public long from, to;
    public double entryTriggerRate, missedRate;
    /** human label of how reliable the sample is */
    public String reliability = "NO DATA";

    public static Kpi compute(List<Row> rows) {
        Kpi k = new Kpi();
        k.total = rows.size();
        long min = Long.MAX_VALUE, max = 0;
        double plannedSum = 0; int plannedN = 0;
        double mfeS = 0, maeS = 0; int exN = 0;
        double holdS = 0; int holdN = 0;
        List<Row> dec = new ArrayList<>();
        for (Row r : rows) {
            min = Math.min(min, r.ts); max = Math.max(max, Math.max(r.ts, r.resolvedTs));
            if (r.actionable()) k.qualified++;
            if (r.state.equals(Mie.S_REJECTED) || r.state.equals(Mie.S_INVALIDATED)) k.rejected++;
            if (r.state.equals(Mie.S_WATCH) || r.state.equals(Mie.S_DEVELOPING) || r.state.equals(Mie.S_DETECTED)) k.watched++;
            if (r.actionable() && r.outcome.equals("no_fill")) k.unfilled++;
            if (r.actionable() && r.outcome.equals("expired")) k.expired++;
            if (r.filled) k.filled++;
            if (r.decided()) {
                dec.add(r);
                if (r.outcome.equals("win")) k.wins++; else k.losses++;
                if (r.rr > 0) { plannedSum += r.rr; plannedN++; }
                mfeS += r.mfe; maeS += r.mae; exN++;
                if (r.resolvedTs > r.ts) { holdS += r.resolvedTs - r.ts; holdN++; }
            }
        }
        k.from = min == Long.MAX_VALUE ? 0 : min; k.to = max;
        k.completed = dec.size();
        if (k.completed == 0) { k.reliability = k.total == 0 ? "NO DATA" : "INSUFFICIENT DATA (no completed trades)"; k.entryTriggerRate = rate(k); return k; }
        k.winRate = 100.0 * k.wins / k.completed;
        k.lossRate = 100.0 - k.winRate;
        double gp = 0, gl = 0;
        for (Row r : dec) { if (r.outcome.equals("win")) gp += r.resultR; else gl += Math.abs(r.resultR); }
        k.grossProfit = gp; k.grossLoss = gl;
        k.avgWinR = k.wins == 0 ? 0 : gp / k.wins;
        k.avgLossR = k.losses == 0 ? 0 : gl / k.losses;
        k.netR = gp - gl;
        k.profitFactor = gl == 0 ? (gp > 0 ? Double.POSITIVE_INFINITY : 0) : gp / gl;
        k.expectancy = (k.winRate / 100.0) * k.avgWinR - (k.lossRate / 100.0) * k.avgLossR;
        k.avgPlannedR = plannedN == 0 ? 0 : plannedSum / plannedN;
        k.avgRealisedR = k.netR / k.completed;
        k.avgMfe = exN == 0 ? 0 : mfeS / exN; k.avgMae = exN == 0 ? 0 : maeS / exN;
        k.avgHoldMin = holdN == 0 ? 0 : holdS / holdN / 60.0;
        // equity curve in completion order
        List<Row> ord = new ArrayList<>(dec);
        java.util.Collections.sort(ord, new java.util.Comparator<Row>() { @Override public int compare(Row a, Row b) { return Long.compare(a.resolvedTs > 0 ? a.resolvedTs : a.ts, b.resolvedTs > 0 ? b.resolvedTs : b.ts); } });
        double eq = 0, peak = 0, dd = 0; int ws = 0, ls = 0;
        for (Row r : ord) {
            eq += r.outcome.equals("win") ? r.resultR : -Math.abs(r.resultR);
            peak = Math.max(peak, eq); dd = Math.max(dd, peak - eq);
            if (r.outcome.equals("win")) { ws++; ls = 0; } else { ls++; ws = 0; }
            k.longestWin = Math.max(k.longestWin, ws); k.longestLoss = Math.max(k.longestLoss, ls);
        }
        k.maxDrawdownR = dd;
        double[] ci = wilson(k.wins, k.completed);
        k.ciLow = ci[0] * 100; k.ciHigh = ci[1] * 100;
        k.entryTriggerRate = rate(k);
        k.missedRate = k.qualified == 0 ? 0 : 100.0 * (k.unfilled + k.expired) / k.qualified;
        k.reliability = k.completed < 30 ? "INSUFFICIENT DATA (under 30 completed trades: inconclusive)" : k.completed < 100 ? "LIMITED (30 to 99 completed trades)" : "BETTER (100+ completed trades)";
        return k;
    }

    private static double rate(Kpi k) { return k.qualified == 0 ? 0 : 100.0 * k.filled / k.qualified; }

    /** 95% Wilson score interval for a proportion */
    public static double[] wilson(int wins, int n) {
        if (n == 0) return new double[]{0, 1};
        double z = 1.96, p = (double) wins / n, z2 = z * z;
        double den = 1 + z2 / n, centre = (p + z2 / (2 * n)) / den;
        double half = z * Math.sqrt((p * (1 - p) + z2 / (4.0 * n)) / n) / den;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    public interface Key { String of(Row r); }

    /** KPIs per group (instrument, band, execution timeframe, regime, ...), over the actionable rows only */
    public static Map<String, Kpi> groupBy(List<Row> rows, Key key) {
        Map<String, List<Row>> g = new TreeMap<>();
        for (Row r : rows) { if (!r.actionable()) continue; String k = key.of(r); if (k == null || k.isEmpty()) k = "(none)"; List<Row> l = g.get(k); if (l == null) { l = new ArrayList<>(); g.put(k, l); } l.add(r); }
        Map<String, Kpi> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Row>> e : g.entrySet()) out.put(e.getKey(), compute(e.getValue()));
        return out;
    }

    public String line() {
        if (completed == 0) return "no completed trades yet";
        return String.format(java.util.Locale.US, "%d trades, %.0f%% won (95%% range %.0f-%.0f%%), avg win %.2fR, avg loss %.2fR, expectancy %+.2fR, profit factor %s, net %+.1fR, max drawdown %.1fR",
            completed, winRate, ciLow, ciHigh, avgWinR, avgLossR, expectancy, Double.isInfinite(profitFactor) ? "n/a" : String.format(java.util.Locale.US, "%.2f", profitFactor), netR, maxDrawdownR);
    }
}
