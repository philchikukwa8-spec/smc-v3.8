package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.smcscanner.engine.Fmt.f;

/**
 * Life of one idea:  waiting (validated, price not back in the zone yet) -> armed (price in the zone,
 * hunting a lower-timeframe entry) -> done (entry sent, cancelled, or went stale).
 */
public final class Lifecycle {
    private Lifecycle() {}

    public static final class Tracked implements Serializable {
        private static final long serialVersionUID = 1L;
        public String key, market, label, tf;
        public long gran, createdAt;
        public Setup setup;
        /** "waiting", "armed" or "done" */
        public String status;
        public String ltfTf;
        public Setup entry;
        public Breakdown breakdown;
        /** zoom-in mode: when the first entry zone was announced (0 = not yet) */
        public long coarseSince;
        /** the timeframe the final entry came from (equals ltfTf when there was no zoom-in) */
        public String finalTf;
    }

    public static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        public LinkedHashMap<String, Tracked> tracked = new LinkedHashMap<>();
        public long lastHeartbeat;
        public int alertsSinceHeartbeat;
    }

    public static final class Series {
        public String market, label, tf;
        public long gran;
        /** raw from Deriv, last may be still forming */
        public Candle[] candles;
        public Series(String market, String label, String tf, long gran, Candle[] candles) {
            this.market = market; this.label = label; this.tf = tf; this.gran = gran; this.candles = candles;
        }
    }

    /** A timeframe whose candles are fetched only when actually needed. */
    public abstract static class Source {
        public final String tf;
        public final long gran;
        protected Source(String tf, long gran) { this.tf = tf; this.gran = gran; }
        public abstract Candle[] fetch() throws Exception;
    }

    public static final class Options {
        /** epoch seconds */
        public long now;
        public int minScore = 4, recentCandles = 4, waitCandles = 30, recentLtf = 12;
        public SmcConfig strategy = new SmcConfig();
        public Breakdowns.Filters filters = new Breakdowns.Filters();
        /** entry style 0 edge, 1 middle, 2 deep; -1 = use whichever the stored calibration found best */
        public int entryMode = 1;
        public Calibration.Book book;
        /** money risked per trade, for the money lines in the alert (0 = off) */
        public double riskMoney;
        /** finer timeframes to zoom into after the entry zone is found, closest first (empty = none) */
        public List<Source> refine = new ArrayList<>();
        /** how long to wait for a precise entry before using the first entry zone (seconds; 0 = do not wait) */
        public long refineWaitSec = 1800;
        /** the timeframe two steps above the idea, for the top-trend check (null = none) */
        public Source top;
    }

    /** A message to send. Call apply() only after it was sent successfully. */
    public static final class Msg implements Serializable {
        private static final long serialVersionUID = 1L;
        /** "validated", "retest", "entry" or "invalidated" */
        public final String kind;
        public final String title, body, text;
        public final transient Runnable apply;
        public Msg(String kind, String title, String body, String text, Runnable apply) {
            this.kind = kind; this.title = title; this.body = body; this.text = text; this.apply = apply;
        }
    }

    private static String validatedText(Series s, Setup st, double price, boolean inZone, String ltfTf) {
        boolean buy = st.isBuy();
        StringBuilder sb = new StringBuilder();
        sb.append(st.side).append(ltfTf != null ? " idea validated (" + s.tf + ")" : " setup validated").append("\n");
        sb.append(s.label).append(" - ").append(s.tf).append("\n\n");
        for (String r : st.reasons) sb.append("[OK] ").append(r).append("\n");
        sb.append("\nZone (").append(st.zoneType).append("): ").append(f(st.zoneLow)).append(" - ").append(f(st.zoneHigh)).append("\n");
        if (ltfTf != null) sb.append("Idea target: ").append(f(st.tp)).append("  idea stop: ").append(f(st.sl)).append("\n");
        else sb.append("Entry: ").append(f(st.entry)).append("\nStop: ").append(f(st.sl)).append("\nTarget: ").append(f(st.tp))
            .append(" (").append("liquidity".equals(st.tpType) ? "next liquidity" : "fixed").append(", R:R ").append(Fmt.d1(st.rr)).append(")\n");
        sb.append("\nPrice now: ").append(f(price)).append("\n");
        if (inZone) sb.append(ltfTf != null ? "Price is already in the zone. Now watching " + ltfTf + " for your entry.\n" : "Price has already reached the zone.\n");
        else sb.append("Waiting for price to ").append(buy ? "drop" : "rise").append(" back into the zone.")
            .append(ltfTf != null ? " Then I will watch the " + ltfTf + " chart for your entry.\n" : " I will alert you when it does.\n");
        sb.append("Confluences: ").append(st.score).append("/").append(st.maxScore).append(" (not a win probability)");
        return sb.toString();
    }

    private static String joinSteps(List<Mtf.Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Mtf.Step x : steps) { if (sb.length() > 0) sb.append(" > "); sb.append(x.label); }
        return sb.toString();
    }

    private static boolean anyCloseBeyondStop(Candle[] closed, Setup st) {
        boolean buy = st.isBuy();
        for (Candle c : closed) {
            if (c.epoch <= st.triggerEpoch) continue;
            if (buy ? c.close < st.sl : c.close > st.sl) return true;
        }
        return false;
    }

    private static int countAfter(Candle[] closed, long epoch) {
        int n = 0;
        for (Candle c : closed) if (c.epoch > epoch) n++;
        return n;
    }

    /**
     * Looks at one market on one idea timeframe (optionally with a lower entry timeframe) and returns
     * messages to send. The state changes only inside each message's apply().
     */
    public static List<Msg> processPair(final State state, final Series s, final Source ltf, final Options o, final Source next) {
        SmcConfig cfg = o.strategy;
        List<Msg> out = new ArrayList<>();
        if (s.candles.length < 50) return out;
        Candle[] closed = Smc.closedOnly(s.candles, s.gran, o.now);
        double price = s.candles[s.candles.length - 1].close;

        Candle[] ltfClosed = null;
        double ltfPrice = price;
        boolean nextTried = false;
        Candle[] nextClosed = null;
        boolean topTried = false;
        Candle[] topClosed = null;

        // 1. update setups already tracked
        for (final Tracked t : new ArrayList<>(state.tracked.values())) {
            if (!t.market.equals(s.market) || !t.tf.equals(s.tf) || t.status.equals("done")) continue;
            final Setup st = t.setup;
            boolean buy = st.isBuy();
            boolean invalid = anyCloseBeyondStop(closed, st);
            Candle touch = Mtf.zoneTouch(st, s.candles);
            boolean expired = countAfter(closed, st.triggerEpoch) > o.waitCandles;
            String kindWord = ltf != null ? "idea" : "setup";
            if (invalid) {
                out.add(new Msg("invalidated",
                    "CANCELLED " + st.side + " " + kindWord + " - " + s.label + " " + s.tf,
                    "Price closed beyond the stop (" + f(st.sl) + ") before " + (ltf != null ? "an entry formed" : "retesting") + ".",
                    st.side + " " + kindWord + " cancelled - " + s.label + " " + s.tf + "\nPrice closed beyond the stop (" + f(st.sl) + ") before "
                        + (ltf != null ? "an entry formed" : "retesting") + ". Ignore the earlier alert.",
                    new Runnable() { public void run() { t.status = "done"; } }));
                continue;
            }
            if (touch == null) {
                if (expired) t.status = "done";
                continue;
            }
            if (ltf == null) {
                out.add(new Msg("retest",
                    "Price in " + st.side + " zone - " + s.label + " " + s.tf,
                    "Zone " + f(st.zoneLow) + "-" + f(st.zoneHigh) + " | Entry " + f(st.entry) + " | SL " + f(st.sl) + " | TP " + f(st.tp),
                    s.label + " " + s.tf + ": price is in your " + st.side + " zone\nZone " + f(st.zoneLow) + " - " + f(st.zoneHigh) + " | Entry " + f(st.entry)
                        + "\nStop " + f(st.sl) + " | Target " + f(st.tp) + "\nPrice now: " + f(price),
                    new Runnable() { public void run() { t.status = "done"; } }));
                continue;
            }
            // top-down mode
            if (t.status.equals("waiting")) {
                out.add(new Msg("retest",
                    s.label + ": price in " + s.tf + " " + st.side + " zone",
                    "Zone " + f(st.zoneLow) + "-" + f(st.zoneHigh) + " | now watching " + ltf.tf + " for an entry",
                    s.label + ": price is in your " + s.tf + " " + st.side + " zone\nZone " + f(st.zoneLow) + " - " + f(st.zoneHigh)
                        + "\nNow watching the " + ltf.tf + " chart for your entry confirmation...\nPrice now: " + f(price),
                    new Runnable() { public void run() { if (t.status.equals("waiting")) t.status = "armed"; } }));
            }
            Setup entry = null;
            String finalTf = ltf.tf;
            long finalGran = ltf.gran;
            Setup coarse = null;
            List<Mtf.Step> steps = new ArrayList<>();
            try {
                if (ltfClosed == null) {
                    Candle[] raw = ltf.fetch();
                    ltfClosed = Smc.closedOnly(raw, ltf.gran, o.now);
                    if (raw.length > 0) ltfPrice = raw[raw.length - 1].close;
                }
                entry = Mtf.findLtfEntry(st, ltfClosed, touch.epoch, o.recentLtf, cfg, o.minScore);
            } catch (Exception e) { /* LTF fetch failed: try again on the next scan */ }
            if (entry != null && !o.refine.isEmpty()) {
                coarse = entry;
                List<Mtf.Tier> tiers = new ArrayList<>();
                for (Source r : o.refine) {
                    try { tiers.add(new Mtf.Tier(r.tf, r.gran, Smc.closedOnly(r.fetch(), r.gran, o.now))); } catch (Exception e) { /* skip this tier this time */ }
                }
                steps = Mtf.refine(coarse, ltf.gran, tiers, cfg, o.minScore, 0);
                if (!steps.isEmpty()) {
                    Mtf.Step last = steps.get(steps.size() - 1);
                    entry = last.setup; finalTf = last.label; finalGran = last.gran;
                } else if (o.refineWaitSec > 0 || o.filters.requireZoom) {
                    // no precise entry yet: tell the user the zone is ready, then wait a while for the finer charts
                    if (t.coarseSince == 0) {
                        Breakdowns.Input zi = new Breakdowns.Input();
                        zi.ideaTf = s.tf; zi.entryTf = ltf.tf; zi.idea = st; zi.entry = coarse; zi.riskMoney = o.riskMoney;
                        Breakdown zb = Breakdowns.build(zi);
                        final String zoneTitle = s.label + ": " + ltf.tf + " entry zone ready (" + s.tf + " idea)";
                        out.add(new Msg("zone", zoneTitle,
                            "Zone entry " + f(coarse.entry) + " | SL " + f(coarse.sl) + " | TP " + f(coarse.tp) + " | now zooming into the finer charts for a more precise entry",
                            zoneTitle + "\nEntry zone " + f(coarse.zoneLow) + " - " + f(coarse.zoneHigh) + "\nFirst entry " + f(coarse.entry) + "  stop " + f(coarse.sl) + "  target " + f(coarse.tp)
                                + "  (" + Fmt.d1(zb.levels.rr1) + "R)\nI am watching the finer timeframes for a more precise entry and will alert you when it forms.\nPrice now: " + f(price),
                            new Runnable() { public void run() { if (t.coarseSince == 0) t.coarseSince = o.now; } }));
                        entry = null;
                    } else if (o.filters.requireZoom || o.now - t.coarseSince < o.refineWaitSec) {
                        entry = null; // sniper mode: keep waiting for the 5m/1m, never use the coarse entry
                    } // else: waited long enough, use the first entry zone
                }
            }
            Breakdown bd = null;
            if (entry != null) {
                if (next != null && !nextTried) {
                    nextTried = true;
                    try { nextClosed = Smc.closedOnly(next.fetch(), next.gran, o.now); } catch (Exception e) { /* no second target */ }
                }
                if (o.top != null && !topTried) {
                    topTried = true;
                    try { topClosed = Smc.closedOnly(o.top.fetch(), o.top.gran, o.now); } catch (Exception e) { /* no top-trend check */ }
                }
                int mode = o.entryMode >= 0 ? o.entryMode : (o.book != null ? o.book.bestMode(s.market, s.tf, ltf.tf) : 1);
                entry = Mtf.applyEntryMode(entry, mode, cfg.minRR);
                Breakdowns.Input in = new Breakdowns.Input();
                in.entryMode = mode; in.market = s.market; in.book = o.book; in.riskMoney = o.riskMoney;
                in.ideaTf = s.tf; in.entryTf = finalTf; in.idea = st; in.entry = entry;
                if (!steps.isEmpty()) { in.coarse = coarse; in.coarseTf = ltf.tf; in.steps = steps; }
                in.nextTf = next != null ? next.tf : null; in.nextCandles = nextClosed; in.nextGran = next != null ? next.gran : 0;
                in.topTf = o.top != null ? o.top.tf : null; in.topCandles = topClosed; in.topGran = o.top != null ? o.top.gran : 0;
                in.ideaCandles = closed; in.ideaGran = s.gran;
                bd = Breakdowns.build(in);
                if (!Breakdowns.passes(bd, o.filters)) { entry = null; bd = null; }
            }
            if (entry != null && bd != null) {
                final Setup e2 = entry;
                final Breakdown b2 = bd;
                final String ft = finalTf;
                String path = steps.isEmpty() ? s.tf + " > " + ltf.tf : s.tf + " > " + ltf.tf + " > " + joinSteps(steps);
                out.add(new Msg("entry",
                    st.side + " ENTRY - " + s.label + " - " + path + " - Grade " + b2.grade,
                    Breakdowns.shortText(b2),
                    Breakdowns.fullText(b2, s.label, ltfPrice),
                    new Runnable() { public void run() { t.status = "done"; t.entry = e2; t.breakdown = b2; t.finalTf = ft; state.alertsSinceHeartbeat++; } }));
            } else if (expired) {
                t.status = "done";
            }
        }

        // 2. newly validated setups on the latest closed candles
        for (final Setup st : Smc.detectRecent(closed, o.recentCandles, cfg)) {
            if (st.score < o.minScore) continue;
            final String key = s.market + "|" + s.tf + "|" + st.id;
            if (state.tracked.containsKey(key)) continue;
            boolean dup = false;
            for (Tracked t : state.tracked.values()) {
                if (t.market.equals(s.market) && t.tf.equals(s.tf) && t.setup.side.equals(st.side)
                    && Math.abs(t.setup.triggerEpoch - st.triggerEpoch) <= 3 * s.gran) { dup = true; break; }
            }
            if (dup) continue;
            boolean buy = st.isBuy();
            boolean invalid = anyCloseBeyondStop(closed, st);
            boolean inZone = Mtf.zoneTouch(st, s.candles) != null;
            final Tracked tr = new Tracked();
            tr.key = key; tr.market = s.market; tr.label = s.label; tr.tf = s.tf; tr.gran = s.gran; tr.setup = st;
            tr.status = invalid ? "done" : inZone ? (ltf != null ? "armed" : "done") : "waiting";
            tr.createdAt = o.now; tr.ltfTf = ltf != null ? ltf.tf : null;
            if (invalid) { state.tracked.put(key, tr); continue; }
            Breakdown single = null;
            if (ltf == null) {
                if (next != null && !nextTried) {
                    nextTried = true;
                    try { nextClosed = Smc.closedOnly(next.fetch(), next.gran, o.now); } catch (Exception e) { /* no second target */ }
                }
                if (o.top != null && !topTried) {
                    topTried = true;
                    try { topClosed = Smc.closedOnly(o.top.fetch(), o.top.gran, o.now); } catch (Exception e) { /* no top-trend check */ }
                }
                Breakdowns.Input in = new Breakdowns.Input();
                in.ideaTf = s.tf; in.idea = st; in.entry = st;
                in.nextTf = next != null ? next.tf : null; in.nextCandles = nextClosed; in.nextGran = next != null ? next.gran : 0;
                in.topTf = o.top != null ? o.top.tf : null; in.topCandles = topClosed; in.topGran = o.top != null ? o.top.gran : 0;
                in.ideaCandles = closed; in.ideaGran = s.gran;
                in.riskMoney = o.riskMoney;
                single = Breakdowns.build(in);
                if (!Breakdowns.passes(single, o.filters)) continue;
                tr.breakdown = single;
            }
            String kindWord = ltf != null ? "idea" : "setup";
            String body = single != null ? Breakdowns.shortText(single)
                : "Zone " + f(st.zoneLow) + "-" + f(st.zoneHigh) + " | target " + f(st.tp) + " | I will watch " + ltf.tf + " for your entry";
            String text = single != null
                ? Breakdowns.fullText(single, s.label, price) + (inZone ? "\n\nPrice has already reached the zone."
                    : "\n\nWaiting for price to " + (buy ? "drop" : "rise") + " back into the zone. I will alert you when it does.")
                : validatedText(s, st, price, inZone, ltf != null ? ltf.tf : null);
            out.add(new Msg("validated",
                st.side + " " + kindWord + " validated - " + s.label + " " + s.tf, body, text,
                new Runnable() { public void run() { state.tracked.put(key, tr); state.alertsSinceHeartbeat++; } }));
        }
        return out;
    }

    /** Drop tracked setups older than `days` so the saved state stays small. */
    public static void prune(State state, long now, int days) {
        for (java.util.Iterator<Map.Entry<String, Tracked>> it = state.tracked.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().createdAt > days * 86400L) it.remove();
        }
    }
}
