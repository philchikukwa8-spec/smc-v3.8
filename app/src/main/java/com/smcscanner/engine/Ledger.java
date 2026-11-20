package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The permanent record of every entry alert, and whether it went on to win or lose.
 * An alert is resolved from the candles after it: the entry has to be touched first, then the first of
 * stop or target decides it (stop first if one candle touches both, the same pessimistic rule as the backtest).
 * Pure Java so it can be tested.
 */
public final class Ledger {
    private Ledger() {}

    public static final String OPEN = "open", WIN = "win", LOSS = "loss", NO_FILL = "no_fill", EXPIRED = "expired";
    /** the workbook holds this many rows; older ones are dropped from the export (not from the phone) */
    public static final int MAX_ROWS = 4999;

    public static final class Rec implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id, symbol, market, side, ideaTf, entryTf, path, structure, zone, grade, mode;
        /** epoch seconds when the entry alert was sent */
        public long time, gran;
        public int score, maxScore;
        /** measured past win rate in % when it was proven (30+ trades), else -1 */
        public double confPct = -1;
        public int confN;
        public double entry, stop, tp, rr, riskMoney;
        /** open, win, loss, no_fill or expired */
        public String outcome = OPEN;
        public boolean filled;
        /** +rr for a win, -1 for a loss, 0 otherwise */
        public double resultR;
        public long resolvedAt;
        public Setup setup;

        public boolean decided() { return outcome.equals(WIN) || outcome.equals(LOSS); }
    }

    /** "4H > 1H > 5m > 1m": the chain of timeframes from the idea down to the one the entry came from */
    public static String pathOf(String tf, String ltfTf, String finalTf) {
        List<String> chain = new ArrayList<>();
        for (Scan.Pair p : Scan.Config.defaults().pairs) {
            if (!p.setup.equals(tf)) continue;
            chain.add(p.setup);
            if (p.entry != null) chain.add(p.entry);
            chain.addAll(p.refine);
        }
        String last = finalTf != null ? finalTf : ltfTf;
        StringBuilder sb = new StringBuilder();
        if (chain.isEmpty() || last == null || !chain.contains(last)) {
            sb.append(tf);
            if (ltfTf != null) sb.append(" > ").append(ltfTf);
            if (finalTf != null && !finalTf.equals(ltfTf)) sb.append(" > ").append(finalTf);
            return sb.toString();
        }
        for (String s : chain) {
            if (sb.length() > 0) sb.append(" > ");
            sb.append(s);
            if (s.equals(last)) break;
        }
        return sb.toString();
    }

    /** Converts the phone's signal record into a workbook row (the signal record stays the single source of truth). */
    public static Rec fromSignal(SignalLog.Record r) {
        Rec x = new Rec();
        x.id = r.id; x.symbol = r.symbol; x.market = r.market; x.side = r.side;
        x.ideaTf = r.ideaTf; x.entryTf = r.entryTf; x.path = r.path;
        x.structure = r.setup == null || r.setup.structure == null ? "" : r.setup.structure;
        x.zone = r.setup == null || r.setup.zoneType == null ? "" : r.setup.zoneType;
        x.grade = r.grade == null ? "" : r.grade;
        x.mode = r.sniper ? "Sniper" : "Normal";
        x.time = r.alertEpoch; x.score = r.score; x.maxScore = r.maxScore;
        x.confPct = r.confPct; x.confN = r.confN; x.riskMoney = r.riskMoney;
        if (r.setup != null) { x.entry = r.setup.entry; x.stop = r.setup.sl; x.tp = r.setup.tp; x.rr = r.setup.rr; x.setup = r.setup; }
        x.outcome = r.outcome.equals("pending") ? OPEN : r.outcome;
        x.filled = r.filled; x.resultR = r.decided() ? r.resultR : 0;
        x.resolvedAt = r.finished() ? r.closedEpoch : 0;
        return x;
    }

    public static List<Rec> fromSignals(List<SignalLog.Record> in) {
        List<Rec> out = new ArrayList<>();
        for (SignalLog.Record r : in) out.add(fromSignal(r));
        return out;
    }

    public static String band(double confPct) {
        if (confPct < 0) return "Unproven";
        if (confPct < 50) return "Under 50%";
        if (confPct < 70) return "50-69%";
        if (confPct < 80) return "70-79%";
        return "80%+";
    }

    /** builds a record from an idea whose entry alert has been sent; null when it has no entry yet */
    public static Rec from(Lifecycle.Tracked t, long now, boolean sniper) {
        if (t.entry == null || t.setup == null) return null;
        Rec r = new Rec();
        r.id = t.key;
        r.time = now;
        r.symbol = t.market;
        r.market = t.label;
        r.side = t.entry.side;
        r.ideaTf = t.tf;
        r.entryTf = t.finalTf != null ? t.finalTf : t.ltfTf != null ? t.ltfTf : t.tf;
        r.path = pathOf(t.tf, t.ltfTf, t.finalTf);
        r.structure = t.setup.structure;
        r.zone = t.setup.zoneType;
        r.score = t.setup.score;
        r.maxScore = t.setup.maxScore;
        r.grade = t.breakdown != null && t.breakdown.grade != null ? t.breakdown.grade : "";
        if (t.breakdown != null && t.breakdown.confidence != null && t.breakdown.confidence.reliable) {
            r.confPct = t.breakdown.confidence.pct;
            r.confN = t.breakdown.confidence.n;
        } else if (t.breakdown != null && t.breakdown.confidence != null) {
            r.confN = t.breakdown.confidence.n;
        }
        r.entry = t.entry.entry; r.stop = t.entry.sl; r.tp = t.entry.tp; r.rr = t.entry.rr;
        r.riskMoney = t.breakdown != null ? t.breakdown.riskMoney : 0;
        r.mode = sniper ? "Sniper" : "Normal";
        // granularity of the chart the entry came from
        long g = t.gran;
        for (Scan.Timeframe tf : Scan.Config.defaults().timeframes) if (tf.label.equals(r.entryTf)) g = tf.granularity;
        r.gran = g;
        r.setup = t.entry;
        return r;
    }

    /** adds a record for every entry alert not yet in the ledger. Returns how many were added. */
    public static int sync(List<Rec> ledger, Lifecycle.State state, long now, boolean sniper) {
        Set<String> have = new HashSet<>();
        for (Rec r : ledger) have.add(r.id);
        int added = 0;
        for (Lifecycle.Tracked t : state.tracked.values()) {
            if (t.entry == null || have.contains(t.key)) continue;
            Rec r = from(t, now, sniper);
            if (r == null) continue;
            ledger.add(r);
            have.add(r.id);
            added++;
        }
        return added;
    }

    /** how many candles of the entry chart the entry may wait to be touched, and how long a filled trade may run */
    static int waitCandles(long gran) { return (int) Math.max(12, (6 * 3600) / Math.max(1, gran)); }
    static int holdCandles(long gran) { return (int) Math.max(48, (48 * 3600) / Math.max(1, gran)); }

    /**
     * Updates an open record from the candles of its entry chart (closed candles, oldest first).
     * Returns true when the record changed. An alert still waiting for a result stays open.
     */
    public static boolean resolve(Rec r, Candle[] c, long now) {
        if (!r.outcome.equals(OPEN) || r.setup == null || c.length == 0) return false;
        boolean changed = false;
        boolean buy = r.setup.isBuy();
        int start = c.length;
        for (int k = 0; k < c.length; k++) if (c[k].epoch >= r.time) { start = k; break; }
        int wait = waitCandles(r.gran), hold = holdCandles(r.gran);
        boolean filled = false;
        int n = 0; // candles examined since the alert
        for (int k = start; k < c.length; k++) {
            Candle x = c[k];
            n++;
            if (!filled) {
                if (n > wait) return finish(r, NO_FILL, 0, x.epoch + r.gran);
                boolean invalid = buy ? x.close < r.stop : x.close > r.stop;
                if (invalid) return finish(r, NO_FILL, 0, x.epoch + r.gran);
                boolean touched = buy ? x.low <= r.entry : x.high >= r.entry;
                if (!touched) continue;
                filled = true;
            }
            boolean hitSl = buy ? x.low <= r.stop : x.high >= r.stop;
            boolean hitTp = buy ? x.high >= r.tp : x.low <= r.tp;
            if (hitSl) return finish(r, LOSS, -1, x.epoch + r.gran);
            if (hitTp) return finish(r, WIN, r.rr, x.epoch + r.gran);
            if (n > wait + hold) return finish(r, EXPIRED, 0, x.epoch + r.gran);
        }
        if (filled && !r.filled) { r.filled = true; changed = true; }
        return changed;
    }

    private static boolean finish(Rec r, String outcome, double resultR, long at) {
        r.outcome = outcome; r.resultR = resultR; r.resolvedAt = at; r.filled = !outcome.equals(NO_FILL);
        return true;
    }

    /** counts for the app's own summary line */
    public static final class Tally {
        public int total, wins, losses, open, other;
        public double totalR;
        public double winRate() { return wins + losses > 0 ? 100.0 * wins / (wins + losses) : 0; }
    }

    public static Tally tally(List<Rec> ledger) {
        Tally t = new Tally();
        for (Rec r : ledger) {
            t.total++;
            if (r.outcome.equals(WIN)) { t.wins++; t.totalR += r.resultR; }
            else if (r.outcome.equals(LOSS)) { t.losses++; t.totalR += r.resultR; }
            else if (r.outcome.equals(OPEN)) t.open++;
            else t.other++;
        }
        return t;
    }
}
