package com.smcscanner.engine.mie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Strategy Lab: filters recorded test rows (replay and live) and summarises them. Pure logic, no Android.
 * Only decided trades count, exactly as in Kpi. Filtering never changes a row; it only chooses which rows are shown.
 */
public final class Lab {
    private Lab() {}

    public static final class Filter {
        /** ALL, SMC or PA */
        public String mode = "ALL";
        /** ALL or a market symbol */
        public String market = "ALL";
        /** ALL, 1H, 30m or 15m (the chart the direction or pattern came from) */
        public String frame = "ALL";
        /** ALL, UNSEEN (validation + out-of-sample + live) or IN (in-sample only) */
        public String data = "ALL";
        /** ALL, BUY or SELL */
        public String side = "ALL";
        /** include live trades next to the replayed ones */
        public boolean includeLive = true;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(mode.equals("ALL") ? "all strategies" : modeName(mode));
            sb.append(", ").append(market.equals("ALL") ? "all markets" : market);
            sb.append(", ").append(frame.equals("ALL") ? "all charts" : frame + " chart");
            sb.append(", ").append(data.equals("ALL") ? "all periods" : data.equals("UNSEEN") ? "unseen periods only" : "in-sample only");
            if (!side.equals("ALL")) sb.append(", ").append(side.toLowerCase(Locale.US)).append("s only");
            if (!includeLive) sb.append(", replay only");
            return sb.toString();
        }
    }

    public static boolean matches(Row r, Filter f) {
        if (!f.mode.equals("ALL") && !f.mode.equals(r.mode)) return false;
        if (!f.market.equals("ALL") && !f.market.equals(r.symbol)) return false;
        if (!f.frame.equals("ALL") && !f.frame.equals(r.frame)) return false;
        if (!f.side.equals("ALL") && !f.side.equals(r.side)) return false;
        boolean live = "live".equals(r.source);
        if (live && !f.includeLive) return false;
        boolean inSample = "in-sample".equals(r.sample);
        if (f.data.equals("UNSEEN") && inSample) return false;
        if (f.data.equals("IN") && !inSample) return false;
        return true;
    }

    public static List<Row> apply(List<Row> rows, Filter f) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (matches(r, f)) out.add(r);
        return out;
    }

    public static final class Summary {
        public Kpi kpi;
        /** cumulative R after each decided trade, in completion order */
        public double[] curve = new double[0];
        public long from, to;
        public String verdict = "";
        /** 0 = neutral, 1 = good, -1 = bad */
        public int verdictTone;
    }

    public static Summary summarize(List<Row> rows) {
        Summary s = new Summary();
        List<Row> act = new ArrayList<>();
        for (Row r : rows) if (r.actionable()) act.add(r);
        s.kpi = Kpi.compute(act);
        List<Row> dec = new ArrayList<>();
        for (Row r : act) if (r.decided()) dec.add(r);
        Collections.sort(dec, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) { return Long.compare(a.resolvedTs > 0 ? a.resolvedTs : a.ts, b.resolvedTs > 0 ? b.resolvedTs : b.ts); }
        });
        s.curve = new double[dec.size()];
        double eq = 0;
        for (int i = 0; i < dec.size(); i++) {
            Row r = dec.get(i);
            eq += r.outcome.equals("win") ? r.resultR : -Math.abs(r.resultR);
            s.curve[i] = eq;
        }
        s.from = s.kpi.from; s.to = s.kpi.to;
        verdict(s);
        return s;
    }

    private static void verdict(Summary s) {
        Kpi k = s.kpi;
        if (k.completed == 0) { s.verdict = "No completed trades match these filters."; s.verdictTone = 0; return; }
        if (k.completed < Learning.MIN_TRADES) {
            s.verdict = "Too few trades to judge: " + k.completed + " completed, " + Learning.MIN_TRADES + " needed. Treat every number below as a hint, not a result.";
            s.verdictTone = 0; return;
        }
        double wLow = k.ciLow / 100.0;
        double expLow = wLow * k.avgWinR - (1 - wLow) * k.avgLossR;
        if (k.expectancy <= 0) {
            s.verdict = String.format(Locale.US, "Lost money on average: %+.2fR per trade over %d trades.", k.expectancy, k.completed);
            s.verdictTone = -1;
        } else if (expLow > 0) {
            s.verdict = String.format(Locale.US, "Made money on average (%+.2fR per trade over %d trades), and still positive at the low end of the 95%% win-rate range. Check it on new data before trusting it.", k.expectancy, k.completed);
            s.verdictTone = 1;
        } else {
            s.verdict = String.format(Locale.US, "Positive on average (%+.2fR per trade over %d trades), but the result is still within the range that chance could produce.", k.expectancy, k.completed);
            s.verdictTone = 0;
        }
    }

    /** display name of an engine */
    public static String modeName(String m) { return m.equals("PA") ? "Price action" : m.equals("FIB") ? "Fib pullback" : "SMC"; }

    public static final class Ranked {
        public String name;
        public Kpi kpi;
    }

    /** scenarios with at least `minTrades` completed trades, best expectancy first */
    public static List<Ranked> rank(List<Row> rows, int minTrades) {
        Map<String, Kpi> by = Kpi.groupBy(rows, new Kpi.Key() {
            @Override public String of(Row r) { return (modeName(r.mode) + ": ") + (r.scenario.isEmpty() ? "(unlabelled)" : r.scenario); }
        });
        List<Ranked> out = new ArrayList<>();
        for (Map.Entry<String, Kpi> e : by.entrySet()) {
            if (e.getValue().completed < minTrades) continue;
            Ranked r = new Ranked(); r.name = e.getKey(); r.kpi = e.getValue(); out.add(r);
        }
        Collections.sort(out, new Comparator<Ranked>() {
            @Override public int compare(Ranked a, Ranked b) { return Double.compare(b.kpi.expectancy, a.kpi.expectancy); }
        });
        return out;
    }

    /** a grouped table as readable text, largest groups first */
    public static String table(List<Row> rows, Kpi.Key key) {
        Map<String, Kpi> by = Kpi.groupBy(rows, key);
        List<Map.Entry<String, Kpi>> es = new ArrayList<>(by.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<String, Kpi>>() {
            @Override public int compare(Map.Entry<String, Kpi> a, Map.Entry<String, Kpi> b) { return b.getValue().completed - a.getValue().completed; }
        });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Kpi> e : es) sb.append(e.getKey()).append("\n  ").append(e.getValue().line()).append("\n\n");
        return sb.length() == 0 ? "Nothing to show for these filters." : sb.toString().trim();
    }

    public static String shareText(Filter f, Summary s, List<Row> rows) {
        StringBuilder sb = new StringBuilder("STRATEGY LAB RESULTS\n");
        sb.append("Filters: ").append(f.describe()).append("\n\n");
        sb.append(s.verdict).append("\n\n");
        sb.append(s.kpi.line()).append("\n\n");
        List<Ranked> rk = rank(rows, 10);
        if (!rk.isEmpty()) {
            sb.append("Best scenarios (10+ trades):\n");
            for (int i = 0; i < Math.min(3, rk.size()); i++) sb.append("  ").append(rk.get(i).name).append(": ").append(rk.get(i).kpi.line()).append('\n');
            sb.append("Weakest scenarios:\n");
            for (int i = rk.size() - 1; i >= Math.max(0, rk.size() - 3); i--) sb.append("  ").append(rk.get(i).name).append(": ").append(rk.get(i).kpi.line()).append('\n');
            sb.append('\n');
        }
        sb.append("Simulated fills: limit entry, stop counted first when one candle touches both, no spread or slippage. Not a forecast.");
        return sb.toString();
    }
}
