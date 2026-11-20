package com.smcscanner.engine.mie;

import java.io.Serializable;

/** One research record: every detected setup (accepted, watched, rejected, missed, expired, completed) with its evidence and outcome. */
public final class Row implements Serializable {
    private static final long serialVersionUID = 1L;
    public String id = "", symbol = "", label = "", side = "NONE";
    public long ts, resolvedTs;
    public String state = "", signal = "", execTf = "", band = "", strategyVersion = "", regime1h = "", alignment = "", reasons = "", grade = "";
    public String dataStatus = "", source = "live";
    /** live, in-sample, validation, out-of-sample or simulated (labels are shown in every report) */
    public String sample = "live";
    public double entry, stop, target, rr;
    public int groups;
    public boolean counterTrend;
    /** pending, win, loss, no_fill, expired, open */
    public String outcome = "pending";
    public double resultR, mfe, mae;
    public boolean filled;
    public long holdSec;
    public String explanation = "";
    /** zone kind such as "4H OB" or "30m FVG" */
    public String zone = "";
    /** which engine produced it: "SMC" (Advisor), "PA" (Price Action) or "FIB" (Fib pullback) */
    public String mode = "SMC";
    /** the situation that lined up, e.g. "Falling wedge breakout" (PA) or "30m FVG, aligned" (SMC) */
    public String scenario = "";
    /** the timeframe the direction came from: 1H, 30m or 15m */
    public String frame = "1H";

    public boolean actionable() { return state.equals(Mie.S_QUALIFIED) || state.equals(Mie.S_ENTRY); }
    public boolean decided() { return outcome.equals("win") || outcome.equals("loss"); }

    public static Row fromPa(com.smcscanner.engine.pa.PaEngine.Signal s, long ts, String version, String dataStatus) {
        Row r = new Row();
        r.id = s.id; r.symbol = s.symbol; r.label = s.label; r.side = s.side; r.ts = ts; r.mode = "PA";
        r.state = s.state; r.signal = s.side; r.band = s.rrBand; r.strategyVersion = version; r.execTf = s.tf; r.frame = s.tf;
        r.entry = s.entry; r.stop = s.stop; r.target = s.target; r.rr = s.rr; r.groups = s.score; r.grade = s.score >= 4 ? "A" : s.score == 3 ? "B" : "C";
        r.alignment = s.ctx; r.counterTrend = s.ctx.startsWith("against"); r.dataStatus = dataStatus; r.explanation = s.explanation;
        r.scenario = s.key; r.zone = s.family;
        return r;
    }

    public static Row fromFib(com.smcscanner.engine.fib.FibEngine.Setup s, com.smcscanner.engine.fib.FibEngine.Options o, long ts, String version, String dataStatus) {
        Row r = new Row();
        double tgt = s.target(o);
        double risk = Math.abs(s.entry - s.stop);
        r.id = s.id; r.symbol = s.symbol; r.label = s.label; r.side = s.side; r.ts = ts; r.mode = "FIB";
        r.state = s.state; r.signal = s.side; r.strategyVersion = version; r.execTf = "15m"; r.frame = "15m";
        r.entry = s.entry; r.stop = s.stop; r.target = tgt; r.rr = risk > 0 ? Math.abs(tgt - s.entry) / risk : 0;
        r.band = com.smcscanner.engine.Advisor.bandOf(r.rr);
        r.groups = s.score; r.grade = s.score >= 5 ? "A" : s.score >= 3 ? "B" : "C";
        r.alignment = s.d1; r.counterTrend = s.key.contains("D1 against"); r.dataStatus = dataStatus; r.explanation = s.explanation;
        r.scenario = s.key; r.zone = "Fib";
        return r;
    }

    public static Row from(Mie.Assessment a, String version, String dataStatus) {
        Row r = new Row();
        r.id = a.id; r.symbol = a.symbol; r.label = a.label; r.side = a.side; r.ts = a.at;
        r.state = a.setupState; r.signal = a.signal; r.execTf = a.execTf; r.band = a.rrBand; r.strategyVersion = version;
        r.regime1h = a.tfs.size() > 1 ? a.tfs.get(1).regime : ""; r.alignment = a.alignment;
        StringBuilder sb = new StringBuilder();
        for (String s : a.reasons) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        r.reasons = sb.toString();
        r.entry = a.entry; r.stop = a.stop; r.target = a.target; r.rr = a.rr; r.groups = a.groupsMet; r.grade = a.grade;
        r.zone = a.zoneDesc.isEmpty() ? "" : a.zoneDesc.split(" ").length >= 2 ? a.zoneDesc.split(" ")[0] + " " + a.zoneDesc.split(" ")[1] : a.zoneDesc;
        r.frame = a.frame;
        r.scenario = "[" + a.frame + "] " + (r.zone.isEmpty() ? "?" : r.zone) + ", " + a.alignment.toLowerCase(java.util.Locale.US);
        r.counterTrend = a.counterTrend; r.dataStatus = dataStatus; r.explanation = a.explanation;
        return r;
    }
}
