package com.smcscanner.engine.mie;

import com.smcscanner.engine.Advisor;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Patterns;
import com.smcscanner.engine.Setup;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Market Intelligence Engine decision core. Deterministic: 4H sets the environment, 1H the direction, 30m finds the
 * zone, 15m validates, 5m or 1m picks the entry. Every answer carries reason codes and an explanation; "no trade"
 * is a valid answer. R:R is shown with a band label and never used to cancel a plan. This is decision support, not
 * a promise: a grade counts confluence GROUPS, it is not a win probability.
 */
public final class Mie {
    private Mie() {}

    public static final String VERSION = "mie-1";
    /** strategy parameters (set from the active strategy version) */
    public static boolean allowCountertrend = true, strongNeedsAligned = true;
    /** hold a setup back when the higher timeframes clearly point the other way (mixed or unclear is allowed) */
    public static boolean requireAligned = true;
    /** a setup with significant opposing structure under 1R from entry stays on watch */
    public static boolean blockNearResistance = true;
    /** look for an order block inside an order block (15m, 5m, 1m) for a tighter entry and stop */
    public static boolean refineOb = true;
    /** the old rule (1H and 4H must both agree); only used to compare against the realistic rule in tests */
    public static boolean strictAlign = false;

    public static final String S_DETECTED = "DETECTED", S_DEVELOPING = "DEVELOPING", S_WATCH = "WATCH", S_QUALIFIED = "QUALIFIED",
        S_ENTRY = "ENTRY-REACHED", S_EXPIRED = "EXPIRED", S_INVALIDATED = "INVALIDATED", S_COMPLETED = "COMPLETED", S_REJECTED = "REJECTED";

    public static final class Inputs {
        public String symbol = "", label = "";
        /** closed candles, oldest first; m5 and m1 may be null when not available */
        public Candle[] h4, h1, m30, m15, m5, m1;
        public long now;
        /** set by the caller from DataCheck when the main charts are stale or broken */
        public boolean stale;
        public String dataNote = "";
    }

    public static final class TfState implements Serializable {
        private static final long serialVersionUID = 1L;
        public String tf, state = "UNKNOWN", regime = Regime.UNKNOWN, evidence = "";
        public double quality;
    }

    public static final class Assessment implements Serializable {
        private static final long serialVersionUID = 1L;
        public long at;
        public String symbol = "", label = "", version = VERSION;
        public final List<TfState> tfs = new ArrayList<>();
        public String alignment = "UNCLEAR";
        public String side = "NONE";
        public boolean counterTrend;
        public String setupState = S_REJECTED;
        /** STRONG BUY, BUY, STRONG SELL, SELL, WATCH or REJECT */
        public String signal = "REJECT";
        public double price, entry, stop, target, rr, trigger;
        public String rrBand = "";
        public String execTf = "", execWhy = "", targetWhy = "";
        public double zoneLow, zoneHigh;
        public String zoneDesc = "";
        public int groupsMet, groupsMax = 4;
        public String grade = "C";
        public final List<String> evidence = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        public final List<String> reasons = new ArrayList<>();
        public String headline = "";
        public String explanation = "";
        public String id = "";
        /** the timeframe the direction comes from: 1H (default cascade), 30m or 15m (lower-timeframe cascades) */
        public String frame = "1H";
        public int tier;
        public long triggerEpoch;
        public Setup setup;
        public ExecutionPicker.Result exec;
        public ObRefiner.Result ob;
        public TargetPicker.Pick targets;
        public boolean isBuy() { return "BUY".equals(side); }
        public boolean actionable() { return setupState.equals(S_QUALIFIED) || setupState.equals(S_ENTRY); }
    }

    // ---------------------------------------------------------------- timeframe states
    public static TfState tfState(String tf, Candle[] c) {
        TfState t = new TfState();
        t.tf = tf;
        if (c == null || c.length < 60) { t.evidence = "not enough candles"; return t; }
        Regime.Result r = Regime.classify(c);
        t.regime = r.label;
        t.quality = r.quality;
        t.evidence = r.features();
        switch (r.label) {
            case Regime.BULL: t.state = "BULLISH"; break;
            case Regime.BEAR: t.state = "BEARISH"; break;
            case Regime.RANGE: case Regime.COMPRESSION: t.state = r.structure.equals("range") ? "RANGING" : (r.structure.equals("bull") ? "BULLISH" : "BEARISH"); break;
            case Regime.EXPANSION: t.state = r.structure.equals("bull") ? "BULLISH" : r.structure.equals("bear") ? "BEARISH" : "TRANSITIONAL"; break;
            case Regime.TRANSITION: t.state = "TRANSITIONAL"; break;
            default: t.state = "UNKNOWN";
        }
        return t;
    }

    private static String align(boolean buy, TfState h4, TfState h1, TfState m30) {
        String want = buy ? "BULLISH" : "BEARISH", opp = buy ? "BEARISH" : "BULLISH";
        int agree = 0, against = 0;
        for (TfState t : new TfState[]{h4, h1}) { if (t.state.equals(want)) agree++; else if (t.state.equals(opp)) against++; }
        if (agree == 2) return "ALIGNED";
        if (against >= 1 && agree == 0) return "CONFLICTING";
        if (agree >= 1 || against >= 1) return "MIXED";
        return "UNCLEAR";
    }

    private static boolean zoneOverlapsLevel(Candle[] c, boolean buy, double zl, double zh) {
        if (c == null || c.length < 40) return false;
        for (Patterns.Zone z : Patterns.keyZones(c, 8)) {
            if (z.touches < 2) continue;
            boolean overlap = z.low <= zh && z.high >= zl;
            if (overlap) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- main
    private static final String[] NAMES = {"4H", "1H", "30m", "15m", "5m", "1m"};
    private static final java.util.regex.Pattern TF = java.util.regex.Pattern.compile("(?<![A-Za-z0-9.])(4H|1H|30[mM]|15[mM]|5[mM]|1[mM])(?![A-Za-z0-9])");

    /** the same engine one or two timeframes lower: tier 1 = 30m direction, 15m zone, 5m trigger, 1m entry; tier 2 = 15m, 5m, 1m */
    public static Assessment analyseTier(Inputs in, int tier) {
        if (tier <= 0) { Assessment a = analyse(in); a.frame = "1H"; return a; }
        Inputs s = new Inputs();
        s.symbol = in.symbol; s.label = in.label; s.now = in.now; s.stale = in.stale; s.dataNote = in.dataNote;
        Candle[][] chain = {in.h4, in.h1, in.m30, in.m15, in.m5, in.m1};
        Candle[][] sl = new Candle[6][];
        for (int j = 0; j < 6; j++) sl[j] = j + tier < 6 ? chain[j + tier] : null;
        s.h4 = sl[0]; s.h1 = sl[1]; s.m30 = sl[2]; s.m15 = sl[3]; s.m5 = sl[4]; s.m1 = sl[5];
        Assessment a = analyse(s);
        a.tier = tier; a.frame = NAMES[1 + tier];
        a.headline = relabel(a.headline, tier); a.explanation = relabel(a.explanation, tier);
        a.execTf = relabel(a.execTf, tier); a.execWhy = relabel(a.execWhy, tier); a.targetWhy = relabel(a.targetWhy, tier);
        a.zoneDesc = relabel(a.zoneDesc, tier);
        for (int i = 0; i < a.evidence.size(); i++) a.evidence.set(i, relabel(a.evidence.get(i), tier));
        for (int i = 0; i < a.conflicts.size(); i++) a.conflicts.set(i, relabel(a.conflicts.get(i), tier));
        for (java.util.Iterator<TfState> it = a.tfs.iterator(); it.hasNext(); ) {
            TfState t = it.next();
            String nm = relabel(t.tf, tier);
            if (nm.equals(LOWER)) { it.remove(); continue; }
            t.tf = nm; t.evidence = relabel(t.evidence, tier);
        }
        if (a.id.startsWith("MIE|")) a.id = "MIE|T" + tier + "|" + a.id.substring(4);
        return a;
    }

    private static final String LOWER = "lower-timeframe";

    /** ids of assessments that are not recorded or announced: no data, no structure, waiting, only detected, stale (any cascade) */
    public static boolean noRecord(String id) {
        if (id == null || id.isEmpty()) return true;
        String b = id.replaceFirst("^MIE\\|T\\d\\|", "MIE|");
        return b.equals("MIE|NODATA") || b.equals("MIE|NOSTRUCT") || b.equals("MIE|WAIT") || b.equals("MIE|DETECTED") || b.equals("MIE|STALE");
    }

    /** text written for the 4H/1H/30m/15m/5m/1m cascade, restated for a cascade `tier` steps lower */
    static String relabel(String text, int tier) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        java.util.regex.Matcher m = TF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group(1);
            int idx = g.equalsIgnoreCase("4H") ? 0 : g.equalsIgnoreCase("1H") ? 1 : g.startsWith("30") ? 2 : g.startsWith("15") ? 3 : g.startsWith("5") ? 4 : 5;
            int to = idx + tier;
            String r = to > 5 ? LOWER : NAMES[to];
            if (!r.equals(LOWER) && (g.endsWith("M") || g.endsWith("H"))) r = r.toUpperCase(java.util.Locale.US);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Assessment analyse(Inputs in) {
        Assessment a = new Assessment();
        a.at = in.now; a.symbol = in.symbol; a.label = in.label;
        a.tfs.add(tfState("4H", in.h4));
        a.tfs.add(tfState("1H", in.h1));
        a.tfs.add(tfState("30M", in.m30));
        a.tfs.add(tfState("15M", in.m15));
        if (in.m5 != null) a.tfs.add(tfState("5M", in.m5));
        if (in.m1 != null) a.tfs.add(tfState("1M", in.m1));
        if (in.stale) {
            a.reasons.add("STALE_DATA");
            a.headline = "Data is stale: no conclusion";
            a.explanation = "The candle data is old or broken, so no setup is judged. " + in.dataNote;
            a.id = "MIE|STALE";
            return a;
        }
        if (in.h1 == null || in.m30 == null || in.m15 == null || in.h1.length < 40 || in.m30.length < 40 || in.m15.length < 40) {
            a.reasons.add("INSUFFICIENT_DATA");
            a.headline = "Insufficient data";
            a.explanation = "Not enough 1H, 30M or 15M candles yet.";
            a.id = "MIE|NODATA";
            return a;
        }
        a.price = in.m15[in.m15.length - 1].close;
        Advisor.Plan core = Advisor.analyse(in.h4, in.h1, in.m30, in.m15);
        a.side = core.side;
        a.trigger = core.trigger;
        a.zoneLow = core.zoneLow; a.zoneHigh = core.zoneHigh;
        a.zoneDesc = core.zoneTf.isEmpty() ? "" : core.zoneTf + " " + core.zoneType + " " + Fmt.f(core.zoneLow) + " - " + Fmt.f(core.zoneHigh);
        if (core.side.equals("NONE")) {
            a.reasons.add("NO_STRUCTURE");
            a.headline = "No trade: no clear 1H structure";
            a.explanation = core.text;
            a.id = "MIE|NOSTRUCT";
            return a;
        }
        boolean buy = core.isBuy();
        TfState h4 = a.tfs.get(0), h1 = a.tfs.get(1), m30 = a.tfs.get(2);
        a.alignment = align(buy, h4, h1, m30);
        String opp4 = buy ? "BEARISH" : "BULLISH";
        a.counterTrend = h4.state.equals(opp4);
        String dir = buy ? "BUY" : "SELL";
        a.evidence.add(tag(true, "4H " + h4.state + ", 1H " + h1.state + ", 30M " + m30.state + " (" + a.alignment + ")"));
        if (a.alignment.equals("CONFLICTING") || a.alignment.equals("MIXED")) a.conflicts.add("Higher timeframes are " + a.alignment.toLowerCase(Locale.US) + " with a " + dir + ".");
        if (a.counterTrend) a.conflicts.add("Counter-trend: the 4H is " + h4.state + ".");

        // ---- states before the 15m has confirmed
        switch (core.state) {
            case Advisor.APPROACHING:
                a.setupState = S_DEVELOPING; a.reasons.add("NO_CONFIRMATION");
                a.headline = dir + " developing: price heading to the zone";
                a.explanation = core.text;
                a.id = "MIE|" + dir + "|DEV|" + core.zoneTf + "|" + Math.round(core.zoneLow * 100);
                a.signal = "WATCH";
                return a;
            case Advisor.READY:
                a.setupState = S_WATCH; a.reasons.add("NO_CONFIRMATION");
                a.headline = dir + " watch: price in the zone, waiting for the 15m";
                a.explanation = core.text;
                a.id = "MIE|" + dir + "|WATCH|" + core.zoneTf + "|" + Math.round(core.zoneLow * 100) + "|" + Math.round(core.zoneHigh * 100);
                a.signal = "WATCH";
                return a;
            case Advisor.WAIT:
                if (core.text.contains("failed")) { a.setupState = S_INVALIDATED; a.reasons.add("ZONE_FAILED"); }
                else if (core.text.contains("6 candles ago") || core.text.contains("run too far")) { a.setupState = S_EXPIRED; a.reasons.add("EXPIRED"); }
                else { a.setupState = S_DETECTED; a.reasons.add("NO_ZONE_NEAR"); a.signal = "WATCH"; }
                a.headline = a.setupState.equals(S_DETECTED) ? "No trade now" : dir + " " + a.setupState.toLowerCase(Locale.US);
                a.explanation = core.text;
                a.id = "MIE|" + a.setupState;
                if (a.setupState.equals(S_INVALIDATED) || a.setupState.equals(S_EXPIRED)) a.signal = "REJECT";
                return a;
            default: break;
        }

        // ---- the 15m has confirmed: choose the entry timeframe
        a.triggerEpoch = core.triggerEpoch;
        double coreEntry = core.entry, coreStop = core.stop;
        Double firstTarget = core.target > 0 ? Double.valueOf(core.target) : null;
        // a provisional target for the execution comparison (nearest real structure from the 15m plan)
        TargetPicker.Pick tp0 = TargetPicker.pick(buy, coreEntry, coreStop, in.h4, in.h1, in.m30);
        double tgtRef = tp0.target != null ? tp0.target.level : (firstTarget != null ? firstTarget : coreEntry);
        double a15 = Vol.atr(in.m15, 14);
        ExecutionPicker.Result ex = ExecutionPicker.pick(buy, core.zoneLow, core.zoneHigh, coreStop, tgtRef, a15, in.m5, in.m1);
        a.exec = ex;
        ObRefiner.Result ob = refineOb ? ObRefiner.refine(buy, core.zoneLow, core.zoneHigh, a15, in.m15, in.m5, in.m1) : null;
        a.ob = ob;
        double entry, stop;
        if (ex.chosen != null && ob != null && ob.valid && Math.abs(ob.entry - ob.stop) < 0.9 * Math.abs(ex.chosen.entry - ex.chosen.stop)
                && (buy ? ob.entry > coreStop : ob.entry < coreStop)) {
            entry = ob.entry; stop = ob.stop; a.execTf = ob.chain.get(ob.chain.size() - 1).tf.toUpperCase(Locale.US);
            a.execWhy = "Order block refinement: tighter than the " + ex.chosen.tf + " pick (risk " + Fmt.f(Math.abs(ob.entry - ob.stop)) + " vs " + Fmt.f(Math.abs(ex.chosen.entry - ex.chosen.stop)) + "). " + ex.why;
            a.evidence.add(tag(true, ObRefiner.line(ob, core.zoneTf.isEmpty() ? "higher-timeframe" : core.zoneTf)));
        } else if (ex.chosen != null) {
            entry = ex.chosen.entry; stop = ex.chosen.stop; a.execTf = ex.chosen.tf; a.execWhy = ex.why;
            a.evidence.add(tag(true, ex.chosen.evidence));
            if (ob != null && ob.valid) a.evidence.add(tag(true, ObRefiner.line(ob, core.zoneTf.isEmpty() ? "higher-timeframe" : core.zoneTf).replace("Entry at", "Could also enter at") ));
        } else if (ob != null && ob.valid) {
            entry = ob.entry; stop = ob.stop; a.execTf = ob.chain.get(ob.chain.size() - 1).tf.toUpperCase(Locale.US); a.execWhy = "No 5M/1M structure trigger, but an order block inside the zone gives the entry. " + ex.why;
        } else if (in.m5 == null && in.m1 == null) {
            entry = coreEntry; stop = coreStop; a.execTf = "15M"; a.execWhy = "5M and 1M data unavailable: 15M entry used. " + ex.why;
            a.conflicts.add("Entry is not refined on 5M or 1M (no data).");
        } else {
            a.setupState = S_WATCH; a.reasons.add("NO_EXEC_TRIGGER");
            a.headline = dir + " watch: 15m confirmed, waiting for a 5M or 1M trigger";
            a.explanation = "The 15m closed through " + Fmt.f(core.trigger) + ", but the entry timeframes have not triggered yet. " + ex.why;
            a.id = "MIE|" + dir + "|EXEC|" + core.triggerEpoch;
            a.signal = "WATCH";
            return a;
        }
        if (buy ? !(stop < entry) : !(stop > entry)) {
            a.setupState = S_REJECTED; a.reasons.add("INVALID_STOP"); a.signal = "REJECT";
            a.headline = dir + " rejected: invalid stop"; a.explanation = "The stop is not beyond the entry."; a.id = "MIE|BADSTOP|" + core.triggerEpoch;
            return a;
        }
        TargetPicker.Pick tp = TargetPicker.pick(buy, entry, stop, in.h4, in.h1, in.m30);
        a.targets = tp;
        if (tp.target == null) {
            a.setupState = S_WATCH; a.reasons.add("NO_TARGET"); a.signal = "WATCH";
            a.entry = entry; a.stop = stop;
            a.headline = dir + " watch: no realistic target"; a.explanation = tp.why; a.id = "MIE|" + dir + "|NOTARGET|" + core.triggerEpoch;
            return a;
        }
        a.entry = entry; a.stop = stop; a.target = tp.target.level; a.targetWhy = tp.why;
        a.rr = Math.abs(a.target - entry) / Math.abs(entry - stop);
        a.rrBand = Advisor.bandOf(a.rr);
        if (a.rr < 2) a.reasons.add("RR_UNDER_2"); else if (a.rr < 3) a.reasons.add("RR_2_TO_3");
        if (tp.obstruction != null) { a.conflicts.add("Opposing structure close to entry: " + tp.obstruction); a.reasons.add("OPPOSING_STRUCTURE_CLOSE"); }

        // ---- evidence groups (related evidence counts once)
        int groups = 0;
        boolean structureOk = true; // the 15m change of character is what got us here
        a.evidence.add(tag(true, "Structure: 15m closed " + (buy ? "above " : "below ") + Fmt.f(core.trigger) + (core.checks.toString().contains("[OK] 15m liquidity sweep") ? " after a liquidity sweep" : "")));
        groups++;
        boolean loc = false;
        StringBuilder locWhy = new StringBuilder();
        if (core.checks.toString().contains("[OK] Zone in")) { loc = true; locWhy.append(buy ? "discount" : "premium").append(" zone; "); }
        if (zoneOverlapsLevel(in.h4, buy, core.zoneLow, core.zoneHigh)) { loc = true; locWhy.append("4H support/resistance; "); }
        else if (zoneOverlapsLevel(in.h1, buy, core.zoneLow, core.zoneHigh)) { loc = true; locWhy.append("1H support/resistance; "); }
        for (Trendlines.Line l : Trendlines.find(in.m30, 150)) {
            if (l.broken) continue;
            boolean right = buy ? l.role.equals("support") : l.role.equals("resistance");
            double at = l.at(in.m30.length - 1), mid = (core.zoneLow + core.zoneHigh) / 2;
            if (right && Math.abs(at - mid) <= 0.8 * Vol.atr(in.m30, 14)) { loc = true; locWhy.append("30m trendline retest; "); }
        }
        // confirmed chart patterns on the 1H: one that agrees counts as location evidence, one against is a warning
        try {
            for (Patterns.Pattern pt : Patterns.detect(in.h1)) {
                if (!"confirmed".equals(pt.status)) continue;
                boolean ptBuy = pt.isBuy(), ptSell = "SELL".equals(pt.bias);
                if ((buy && ptBuy) || (!buy && ptSell)) { loc = true; locWhy.append("1H ").append(pt.name).append(" agrees; "); }
                else if ((buy && ptSell) || (!buy && ptBuy)) { a.conflicts.add("A confirmed 1H " + pt.name + " points the other way."); a.reasons.add("PATTERN_AGAINST"); }
            }
        } catch (RuntimeException ignored) { }
        a.evidence.add(tag(loc, "Location: " + (loc ? locWhy.toString().replaceAll("; $", "") : "no extra location evidence beyond the zone")));
        if (loc) groups++;
        boolean mom = CandleBehaviour.confirms(in.m15, buy, 4) || (ex.chosen != null && ex.chosen.quality >= 1);
        String ctag = CandleBehaviour.firstTag(in.m15, buy, 4);
        a.evidence.add(tag(mom, "Momentum: " + (mom ? (ctag != null ? ctag : (ex.chosen.tf + " displacement/rejection")) : "no confirming candle behaviour")));
        if (mom) groups++;
        boolean exhausted = Vol.exhaustion(in.m15) && ((Vol.momentum(in.m15, 12) > 0) != buy ? false : true);
        boolean ctx = a.alignment.equals("ALIGNED") && !exhausted;
        a.evidence.add(tag(ctx, "Context: higher timeframes " + a.alignment + (exhausted ? ", move looks exhausted" : "")));
        if (ctx) groups++;
        a.groupsMet = groups;
        a.grade = groups >= 4 ? "A" : groups == 3 ? "B" : "C";

        // ---- countertrend needs stricter evidence
        boolean sweep = core.checks.toString().contains("[OK] 15m liquidity sweep");
        boolean strictOk = true;
        if (a.counterTrend) {
            boolean atKey = zoneOverlapsLevel(in.h4, buy, core.zoneLow, core.zoneHigh) || zoneOverlapsLevel(in.h1, buy, core.zoneLow, core.zoneHigh);
            strictOk = atKey && sweep && mom;
            a.evidence.add(tag(strictOk, "Countertrend test: key 1H/4H area " + atKey + ", sweep " + sweep + ", momentum " + mom));
            if (!strictOk) a.reasons.add("COUNTERTREND_WEAK");
        }
        if (a.alignment.equals("CONFLICTING") && !a.counterTrend) a.reasons.add("CONFLICTING_HTF");

        // ---- state and classification
        boolean nearBlock = tp.obstruction != null && tp.obstruction.rMultiple < 1.0 && (blockNearResistance || tp.obstruction.weight >= 3);
        boolean notAligned = requireAligned && (strictAlign ? !a.alignment.equals("ALIGNED") : a.alignment.equals("CONFLICTING") && !a.counterTrend);
        if (notAligned) a.reasons.add("HTF_NOT_ALIGNED");
        boolean atEntry = buy ? a.price <= a.entry + 1e-9 : a.price >= a.entry - 1e-9;
        if (a.counterTrend && !allowCountertrend) { strictOk = false; a.reasons.add("COUNTERTREND_OFF"); }
        if (!strictOk || nearBlock || notAligned) {
            a.setupState = S_WATCH; a.signal = "WATCH";
            a.headline = dir + " watch (" + (a.counterTrend && !strictOk ? "countertrend evidence too weak" : nearBlock ? "opposing structure close to entry" : "the 1H and 4H both point the other way") + ")";
        } else {
            a.setupState = atEntry ? S_ENTRY : S_QUALIFIED;
            boolean strong = groups >= 4 && (!strongNeedsAligned || a.alignment.equals("ALIGNED")) && a.rr >= 3;
            a.signal = (strong ? "STRONG " : "") + dir;
            a.headline = a.signal + " " + String.format(Locale.US, "%.1f", a.rr) + "R (" + a.rrBand + ", grade " + a.grade + ")" + (a.counterTrend ? " countertrend" : "");
        }
        a.id = "MIE|" + dir + "|" + core.triggerEpoch;
        a.zoneDesc = a.zoneDesc;
        // setup record for the signal log
        Setup s = core.setup != null ? core.setup.copy() : new Setup();
        s.id = a.id; s.side = a.side; s.triggerEpoch = a.triggerEpoch; s.entry = a.entry; s.sl = a.stop; s.tp = a.target; s.rr = a.rr; s.tpType = "liquidity";
        s.score = groups; s.maxScore = 4;
        a.setup = s;
        a.explanation = explain(a, core);
        return a;
    }

    private static String tag(boolean ok, String s) { return (ok ? "[OK] " : "[--] ") + s; }

    private static String explain(Assessment a, Advisor.Plan core) {
        StringBuilder sb = new StringBuilder();
        sb.append(a.headline).append("\n\n");
        sb.append("Context: ");
        for (TfState t : a.tfs) if (t.tf.equals("4H") || t.tf.equals("1H") || t.tf.equals("30M") || t.tf.equals("15M")) sb.append(t.tf).append(" ").append(t.state).append(", ");
        sb.setLength(sb.length() - 2);
        sb.append(". Regime on the 1H: ").append(a.tfs.get(1).regime).append(".\n");
        sb.append("Zone: ").append(a.zoneDesc).append(".\n");
        sb.append("Entry ").append(Fmt.f(a.entry)).append(" (limit, ").append(a.execTf).append(")  Stop ").append(Fmt.f(a.stop)).append("  Target ").append(Fmt.f(a.target))
          .append("  ").append(String.format(Locale.US, "%.1f", a.rr)).append("R\n");
        sb.append("Why this timeframe: ").append(a.execWhy).append("\n");
        sb.append("Why this target: ").append(a.targetWhy).append("\n\n");
        for (String e : a.evidence) sb.append(e).append("\n");
        if (!a.conflicts.isEmpty()) { sb.append("\nAgainst it:\n"); for (String c : a.conflicts) sb.append("- ").append(c).append("\n"); }
        sb.append("\nInvalid if price closes ").append(a.isBuy() ? "below " : "above ").append(Fmt.f(a.stop)).append(".\n");
        sb.append("Evidence groups met: ").append(a.groupsMet).append("/").append(a.groupsMax).append(" (not a win probability). Historical comparison: none recorded yet for this setup type.");
        return sb.toString();
    }
}
