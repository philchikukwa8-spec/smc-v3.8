package com.smcscanner.engine;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A record of every entry alert and how it turned out, kept so the strategy can be judged on data.
 * The outcome is worked out automatically from the candles after the alert (same rules as the backtest:
 * a limit entry, stop assumed first when one candle touches both). It does not depend on you taking the trade.
 * Pure Java so it can be tested.
 */
public final class SignalLog {
    private SignalLog() {}

    /** how long (in seconds) to wait for the entry to fill, and how long to hold a filled trade before calling it expired */
    public static final long WAIT_SEC = 6 * 3600L, HOLD_SEC = 3 * 86400L;

    public static final class Record implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id, symbol, market, side, path, ideaTf, entryTf, grade, entryStyle;
        public long alertEpoch;
        public int score, maxScore, qualityMet, qualityOf, confN;
        public Setup setup;
        public double riskMoney;
        /** past win rate in % when the alert fired; -1 = unproven */
        public double confPct = -1;
        public boolean sniper;
        /** "pending" (waiting to fill), "open" (filled), "win", "loss", "no_fill" or "expired" */
        public String outcome = "pending";
        public double resultR;
        public long closedEpoch;
        // progress, so each scan only needs the newest candles
        public boolean filled;
        public int seen;
        public long lastEpoch;
        // your own trade, if you logged one for this alert
        public boolean taken, hasMyR;
        public double myR;

        public boolean decided() { return outcome.equals("win") || outcome.equals("loss"); }
        public boolean finished() { return !(outcome.equals("pending") || outcome.equals("open")); }
    }

    /** Creates a record from an entry alert. */
    public static Record create(String id, long alertEpoch, String symbol, String market, String path, String ideaTf, String entryTf,
                                Setup entry, Breakdown bd, boolean sniper, int entryMode, double riskMoney) {
        Record r = new Record();
        r.id = id; r.alertEpoch = alertEpoch; r.symbol = symbol; r.market = market; r.path = path;
        r.ideaTf = ideaTf; r.entryTf = entryTf; r.setup = entry; r.side = entry.side;
        r.sniper = sniper; r.riskMoney = riskMoney;
        r.entryStyle = entryMode >= 0 && entryMode < Calibration.MODE_NAMES.length ? Calibration.MODE_NAMES[entryMode] : "Auto";
        r.score = entry.score; r.maxScore = entry.maxScore;
        if (bd != null) {
            r.grade = bd.grade; r.qualityMet = bd.qualityMet; r.qualityOf = bd.qualityOf;
            if (bd.confidence != null) { r.confN = bd.confidence.n; r.confPct = bd.confidence.reliable ? bd.confidence.pct : -1; }
        }
        return r;
    }

    /**
     * Moves a record forward using closed candles of the entry timeframe (any amount of history; already-seen candles
     * are skipped). Returns true if anything changed.
     */
    public static boolean resolve(Record r, Candle[] closed, long gran) {
        if (r.finished() || gran <= 0) return false;
        int waitN = (int) (WAIT_SEC / gran), holdN = (int) (HOLD_SEC / gran);
        Setup s = r.setup;
        boolean buy = s.isBuy();
        boolean changed = false;
        for (Candle x : closed) {
            if (x.epoch < r.alertEpoch || x.epoch <= r.lastEpoch) continue;
            r.lastEpoch = x.epoch;
            r.seen++;
            changed = true;
            if (!r.filled) {
                if (r.seen > waitN) { finish(r, "no_fill", 0, x.epoch + gran); break; }
                boolean invalid = buy ? x.close < s.sl : x.close > s.sl;
                if (invalid) { finish(r, "no_fill", 0, x.epoch + gran); break; }
                boolean touched = buy ? x.low <= s.entry : x.high >= s.entry;
                if (!touched) continue;
                r.filled = true;
                r.outcome = "open";
            }
            boolean hitSl = buy ? x.low <= s.sl : x.high >= s.sl;
            boolean hitTp = buy ? x.high >= s.tp : x.low <= s.tp;
            if (hitSl) { finish(r, "loss", -1, x.epoch + gran); break; }
            if (hitTp) { finish(r, "win", s.rr, x.epoch + gran); break; }
            if (r.seen > waitN + holdN) { finish(r, "expired", 0, x.epoch + gran); break; }
        }
        return changed;
    }

    private static void finish(Record r, String outcome, double rr, long closedEpoch) {
        r.outcome = outcome; r.resultR = rr; r.closedEpoch = closedEpoch;
    }

    /** marks alerts you took (a trade you logged on the same market and side shortly after) and copies its result */
    public static void linkTrades(List<Record> records, List<Manage.Trade> trades) {
        for (Record r : records) {
            if (r.taken && r.hasMyR) continue;
            for (Manage.Trade t : trades) {
                if (!t.symbol.equals(r.symbol) || !t.side.equals(r.side)) continue;
                if (t.startedAt < r.alertEpoch - 300 || t.startedAt > r.alertEpoch + WAIT_SEC) continue;
                r.taken = true;
                if (t.status.equals("closed")) { r.hasMyR = true; r.myR = t.resultR; }
                break;
            }
        }
    }

    public static final class Summary {
        public int alerts, resolved, wins, losses, open, noFill, expired;
        public double winRate, totalR, avgR;
    }

    /** alerts raised at or after sinceEpoch (0 = all) */
    public static Summary summary(List<Record> records, long sinceEpoch) {
        Summary s = new Summary();
        for (Record r : records) {
            if (r.alertEpoch < sinceEpoch) continue;
            s.alerts++;
            if (r.outcome.equals("win")) { s.wins++; s.resolved++; s.totalR += r.resultR; }
            else if (r.outcome.equals("loss")) { s.losses++; s.resolved++; s.totalR += r.resultR; }
            else if (r.outcome.equals("no_fill")) s.noFill++;
            else if (r.outcome.equals("expired")) s.expired++;
            else s.open++;
        }
        s.winRate = s.resolved > 0 ? 100.0 * s.wins / s.resolved : 0;
        s.avgR = s.resolved > 0 ? s.totalR / s.resolved : 0;
        return s;
    }

    public static final String[] COLUMNS = {
        "Time", "Market", "Side", "Path", "Idea TF", "Entry TF", "Grade", "Score", "Quality", "Zone", "Structure", "Entry", "Stop", "Target", "Planned R:R",
        "Risk $", "Confidence %", "Confidence sample", "Mode", "Entry style", "Outcome", "Result R", "Result $", "Closed", "Taken", "My result R", "Source"
    };

    private static String q(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String num(double v) {
        String s = String.format(Locale.US, "%.5f", v);
        if (s.indexOf('.') >= 0) { s = s.replaceAll("0+$", ""); if (s.endsWith(".")) s = s.substring(0, s.length() - 1); }
        return s;
    }

    private static String time(long epoch) {
        return epoch <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(epoch * 1000));
    }

    /** One CSV (comma separated, dot decimals) that opens in Excel and pastes into the journal workbook. Oldest first. */
    public static String toCsv(List<Record> records) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COLUMNS.length; i++) { if (i > 0) sb.append(','); sb.append(COLUMNS[i]); }
        sb.append("\n");
        List<Record> sorted = new ArrayList<>(records);
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(a.alertEpoch, b.alertEpoch));
        for (Record r : sorted) {
            String outcome = r.outcome.equals("pending") || r.outcome.equals("open") ? "open" : r.outcome;
            boolean resolved = r.decided();
            String[] f = {
                time(r.alertEpoch), r.market, r.side, r.path, r.ideaTf, r.entryTf, r.grade == null ? "" : r.grade,
                r.score + "/" + r.maxScore, r.qualityOf > 0 ? r.qualityMet + "/" + r.qualityOf : "",
                r.setup.zoneType == null ? "" : r.setup.zoneType, r.setup.structure == null ? "" : r.setup.structure,
                num(r.setup.entry), num(r.setup.sl), num(r.setup.tp), num(r.setup.rr),
                num(r.riskMoney), r.confPct >= 0 ? num(r.confPct) : "", r.confN > 0 ? String.valueOf(r.confN) : "",
                r.sniper ? "Sniper" : "Normal", r.entryStyle == null ? "" : r.entryStyle, outcome,
                resolved ? num(r.resultR) : "", resolved && r.riskMoney > 0 ? num(r.resultR * r.riskMoney) : "",
                r.finished() ? time(r.closedEpoch) : "", r.taken ? "Yes" : "No", r.hasMyR ? num(r.myR) : "", "APP"
            };
            for (int i = 0; i < f.length; i++) { if (i > 0) sb.append(','); sb.append(q(f[i])); }
            sb.append("\n");
        }
        return sb.toString();
    }
}
