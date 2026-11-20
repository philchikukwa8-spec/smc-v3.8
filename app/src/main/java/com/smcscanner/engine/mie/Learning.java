package com.smcscanner.engine.mie;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Memory and comparison across both modes. Everything is calculated from the recorded rows (they are the memory: they
 * persist in the research database), so nothing is "remembered" that was not measured. Decisions that change behaviour
 * (suppressing a scenario) only use live, validation and out-of-sample rows, never in-sample ones, and need 30 completed trades.
 */
public final class Learning {
    private Learning() {}

    public static final int MIN_TRADES = 30;
    /** a pair of signals from the two modes on the same market and side within this window counts as agreeing */
    public static final long CONFLUENCE_SEC = 4 * 3600;

    public static String key(Row r) { return r.mode + " | " + (r.scenario.isEmpty() ? "(unlabelled)" : r.scenario); }

    /** rows that are allowed to drive a decision: not in-sample */
    public static boolean usable(Row r) { return !"in-sample".equals(r.sample) && !"simulated".equals(r.sample); }

    public static List<Row> actionable(List<Row> rows, String mode) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r.actionable() && (mode == null || mode.equals(r.mode))) out.add(r);
        return out;
    }

    /** scenario keys that lost money over MIN_TRADES or more completed trades (usable rows only) */
    public static Set<String> blocked(List<Row> rows) {
        Map<String, List<Row>> g = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.actionable() || !usable(r)) continue;
            List<Row> l = g.get(key(r)); if (l == null) { l = new ArrayList<>(); g.put(key(r), l); } l.add(r);
        }
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, List<Row>> e : g.entrySet()) {
            Kpi k = Kpi.compute(e.getValue());
            if (k.completed >= MIN_TRADES && k.expectancy < 0) out.add(e.getKey());
        }
        return out;
    }

    /** one plain sentence about how this scenario has ended before */
    public static String memoryLine(List<Row> rows, String mode, String scenario) {
        List<Row> mine = new ArrayList<>();
        for (Row r : rows) if (r.actionable() && r.mode.equals(mode) && r.scenario.equals(scenario)) mine.add(r);
        if (mine.isEmpty()) return "Not seen before: nothing recorded yet for this situation.";
        Kpi k = Kpi.compute(mine);
        int pending = 0; for (Row r : mine) if (r.outcome.equals("pending") || r.outcome.equals("open")) pending++;
        String base = String.format(Locale.US, "Seen %d times (live and replay): %d won, %d lost, %d never filled or expired, %d still open (net %+.1fR).",
            mine.size(), k.wins, k.losses, k.unfilled + k.expired, pending, k.netR);
        if (k.completed < MIN_TRADES) return base + " Too few completed trades (" + k.completed + " of " + MIN_TRADES + ") to judge.";
        return base + (k.expectancy < 0 ? " It has lost money on average." : " It has made money on average.");
    }

    /** the full comparison and the lessons, as readable text */
    public static String compare(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        List<Row> smc = actionable(rows, "SMC"), pa = actionable(rows, "PA");
        sb.append("MODE COMPARISON (trades that were actually triggered; replay rows are labelled by period)\n\n");
        sb.append("SMC mode: ").append(Kpi.compute(smc).line()).append('\n');
        sb.append("Price action mode: ").append(Kpi.compute(pa).line()).append("\n\n");

        for (String mode : new String[]{"SMC", "PA"}) {
            List<Row> m = actionable(rows, mode);
            Map<String, Kpi> by = Kpi.groupBy(m, r -> r.scenario.isEmpty() ? "(unlabelled)" : r.scenario);
            if (by.isEmpty()) continue;
            sb.append(mode.equals("SMC") ? "SMC scenarios\n" : "Price-action scenarios\n");
            for (Map.Entry<String, Kpi> e : by.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue().line()).append('\n');
            sb.append('\n');
        }

        // confluence: both modes signalling the same market and side close together in time
        List<Row> paAgree = new ArrayList<>(), paSolo = new ArrayList<>(), smcAgree = new ArrayList<>(), smcSolo = new ArrayList<>();
        int conflicts = 0;
        for (Row p : pa) {
            boolean agree = false, oppose = false;
            for (Row s : smc) {
                if (!s.symbol.equals(p.symbol) || Math.abs(s.ts - p.ts) > CONFLUENCE_SEC) continue;
                if (s.side.equals(p.side)) agree = true; else oppose = true;
            }
            if (agree) paAgree.add(p); else paSolo.add(p);
            if (oppose) conflicts++;
        }
        for (Row s : smc) {
            boolean agree = false;
            for (Row p : pa) if (s.symbol.equals(p.symbol) && s.side.equals(p.side) && Math.abs(s.ts - p.ts) <= CONFLUENCE_SEC) { agree = true; break; }
            if (agree) smcAgree.add(s); else smcSolo.add(s);
        }
        sb.append("When both modes agree (same market, same side, within 4 hours)\n");
        Kpi ka = Kpi.compute(paAgree), ks = Kpi.compute(paSolo), sa = Kpi.compute(smcAgree), ss = Kpi.compute(smcSolo);
        sb.append("  Price action, agreed with SMC: ").append(ka.line()).append('\n');
        sb.append("  Price action, on its own: ").append(ks.line()).append('\n');
        sb.append("  SMC, agreed with price action: ").append(sa.line()).append('\n');
        sb.append("  SMC, on its own: ").append(ss.line()).append('\n');
        sb.append("  Price-action signals that SMC contradicted at the time: ").append(conflicts).append("\n\n");

        sb.append("WHAT THE MEMORY SAYS\n");
        List<String> lessons = new ArrayList<>();
        Set<String> bad = blocked(rows);
        for (String b : bad) lessons.add("Switched off by learning (lost money over " + MIN_TRADES + "+ trades, out-of-sample or live): " + b);
        if (ka.completed >= MIN_TRADES && ks.completed >= MIN_TRADES) {
            lessons.add(ka.expectancy > ks.expectancy ? String.format(Locale.US, "Price-action trades that SMC also signalled did better (%+.2fR vs %+.2fR per trade). Treat agreement as extra weight.", ka.expectancy, ks.expectancy)
                : String.format(Locale.US, "Agreement with SMC has not helped price action so far (%+.2fR vs %+.2fR per trade).", ka.expectancy, ks.expectancy));
        } else lessons.add("Agreement between the modes cannot be judged yet: it needs " + MIN_TRADES + " completed trades on each side of the comparison (now " + ka.completed + " and " + ks.completed + ").");
        Kpi ksAll = Kpi.compute(smc), kpAll = Kpi.compute(pa);
        if (ksAll.completed >= MIN_TRADES && kpAll.completed >= MIN_TRADES)
            lessons.add(String.format(Locale.US, "Per trade so far: SMC %+.2fR, price action %+.2fR. A gap this size can still be chance: check the 95%% ranges above.", ksAll.expectancy, kpAll.expectancy));
        else lessons.add("Neither mode can be ranked yet: each needs " + MIN_TRADES + " completed trades (SMC " + ksAll.completed + ", price action " + kpAll.completed + "). Run the real-history replays on the Research and Bird's Eye tabs to build this up faster.");
        for (String l : lessons) sb.append("- ").append(l).append('\n');
        sb.append("\nSample sizes come first. Nothing here is a forecast, and costs (spread, slippage) are not included.");
        return sb.toString();
    }
}
