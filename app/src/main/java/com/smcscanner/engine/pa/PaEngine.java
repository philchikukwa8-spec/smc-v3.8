package com.smcscanner.engine.pa;

import com.smcscanner.engine.Advisor;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Patterns;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.mie.CandleBehaviour;
import com.smcscanner.engine.mie.Mie;
import com.smcscanner.engine.mie.TargetPicker;
import com.smcscanner.engine.mie.Trendlines;
import com.smcscanner.engine.mie.Vol;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Price-action strategy: chart patterns (double top/bottom, head and shoulders, triangles, rising/falling wedges),
 * trendline bounces and breaks, and support/resistance rejections and break-retests, all on CLOSED 1H candles with the
 * 4H trend as context. It does not use order blocks, FVGs or liquidity sweeps: that is the SMC engine's job, so the
 * two can be compared. Every signal is a limit order at a level with a stop beyond it and a target from real structure.
 * R:R is a label, never a filter. Nothing here predicts: how often each scenario works is measured by the learning layer.
 */
public final class PaEngine {
    private PaEngine() {}

    public static final String VERSION = "pa-3";

    public static final class Signal implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id = "", symbol = "", label = "";
        /** e.g. "Falling wedge breakout" */
        public String scenario = "";
        /** Pattern, Trendline or Level */
        public String family = "";
        /** the chart it was found on: 1H, 30m or 15m */
        public String tf = "1H";
        public String side = "BUY";
        /** WATCH, QUALIFIED or ENTRY-REACHED */
        public String state = Mie.S_WATCH;
        /** learning key: scenario, side and the 4H context */
        public String key = "";
        /** with 4H, against 4H or 4H unclear */
        public String ctx = "4H unclear";
        public double entry, stop, target, rr, level, zoneLow, zoneHigh, price;
        public String rrBand = "";
        public int score;
        public long refEpoch;
        public String headline = "", explanation = "";
        /** the same idea seen on other charts (merged into this one so it alerts once), e.g. "30m, 15m" */
        public String also = "";
        public final List<String> evidence = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        public List<double[]> lines = new ArrayList<>();
        /** double confirmation: 0 = not available (no lower chart), 1 = waiting for it, 2 = confirmed */
        public int dc = 0;
        /** what the second confirmation is (or what it is waiting for), the chart it is read on, and where it happened */
        public String dcText = "", dcTf = "";
        public long dcEpoch;
        public double dcPrice;
        /** a reversal idea (allowed against the higher-timeframe trend) */
        public boolean reversal;
        /** the action plan in plain words: identified, waiting for, place order, invalid if */
        public String plan = "";
        /** entry expired: the order can no longer be taken. Kept at the bottom of the list until removed. */
        public boolean expired;
        public long expiredAt;
        public String expiredWhy = "";
        public boolean actionable() { return state.equals(Mie.S_QUALIFIED) || state.equals(Mie.S_ENTRY); }
        public boolean isBuy() { return "BUY".equals(side); }
        /** chart rank: the bigger chart is the primary when the same idea shows on several */
        public int frameRank() { return tf.equals("1H") ? 3 : tf.equals("30m") ? 2 : 1; }
    }

    private static final class Ctx {
        String symbol, label, trend4, tf, trendTf;
        long gran;
        /** h1 is the chart being analysed (1H, 30m or 15m). t4/t1/t30 are the real charts used for targets. */
        Candle[] h4, h1, t4, t1, t30;
        /** the lower chart used for the second (double) confirmation, and its name */
        Candle[] lower;
        String lowerTf = "";
        double a, price;
        int n;
        List<Patterns.Zone> zones;
    }

    public static List<Signal> analyse(String symbol, String label, Candle[] h4, Candle[] h1, long now) {
        return analyseFrame(symbol, label, "1H", 3600, h1, h4, "4H", h4, h1, null, now);
    }

    /**
     * Same scenarios on another chart. `p` is the chart analysed (gran seconds per candle), `trend` its higher-timeframe
     * context (named trendTf), and t4/t1/t30 the real charts that targets and opposing structure are read from.
     */
    public static List<Signal> analyseFrame(String symbol, String label, String tf, long gran, Candle[] p, Candle[] trend, String trendTf,
                                            Candle[] t4, Candle[] t1, Candle[] t30, long now) {
        return analyseFrame(symbol, label, tf, gran, p, trend, trendTf, t4, t1, t30, null, "", now);
    }

    /** the same, plus a lower chart (`lower`, named lowerTf) that provides the second confirmation of each entry */
    public static List<Signal> analyseFrame(String symbol, String label, String tf, long gran, Candle[] p, Candle[] trend, String trendTf,
                                            Candle[] t4, Candle[] t1, Candle[] t30, Candle[] lower, String lowerTf, long now) {
        List<Signal> out = new ArrayList<>();
        Candle[] h1 = p, h4 = trend;
        if (h1 == null || h1.length < 80) return out;
        Ctx x = new Ctx();
        x.symbol = symbol; x.label = label; x.h4 = h4; x.h1 = h1; x.n = h1.length;
        x.tf = tf; x.gran = gran; x.trendTf = trendTf; x.t4 = t4; x.t1 = t1; x.t30 = t30; x.lower = lower; x.lowerTf = lowerTf == null ? "" : lowerTf;
        x.a = Vol.atr(h1, 14);
        if (!(x.a > 0)) return out;
        x.price = h1[x.n - 1].close;
        x.trend4 = h4 != null && h4.length >= 40 ? Smc.structureBias(h4, 2, 120) : "";
        try { x.zones = Patterns.keyZones(h1, 8); } catch (RuntimeException e) { x.zones = new ArrayList<>(); }
        patterns(x, out);
        flags(x, out);
        channels(x, out);
        trendlines(x, out);
        levels(x, out);
        // best first: ready before watch, then by score; keep the three most useful and no duplicate ids
        Collections.sort(out, new Comparator<Signal>() {
            @Override public int compare(Signal p, Signal q) {
                int ap = p.actionable() ? 1 : 0, aq = q.actionable() ? 1 : 0;
                if (ap != aq) return aq - ap;
                return q.score - p.score;
            }
        });
        List<Signal> keep = new ArrayList<>();
        outer:
        for (Signal s : out) {
            for (Signal k : keep) if (k.id.equals(s.id)) continue outer;
            keep.add(s);
            if (keep.size() >= 3) break;
        }
        return keep;
    }

    /**
     * The same idea often shows on the 1H, 30m and 15m at once. Merge ideas on the same side whose entries are within `tol`
     * (a fraction of price) into the best one (ready before watch, bigger chart, higher score), and note where else it was seen.
     */
    public static List<Signal> merge(List<Signal> in, double tol) {
        List<Signal> sorted = new ArrayList<>(in);
        Collections.sort(sorted, new Comparator<Signal>() {
            @Override public int compare(Signal p, Signal q) {
                int ap = p.actionable() ? 1 : 0, aq = q.actionable() ? 1 : 0;
                if (ap != aq) return aq - ap;
                if (p.frameRank() != q.frameRank()) return q.frameRank() - p.frameRank();
                return q.score - p.score;
            }
        });
        List<Signal> keep = new ArrayList<>();
        outer:
        for (Signal s : sorted) {
            double ref = s.entry != 0 ? s.entry : s.level;
            for (Signal k : keep) {
                double kr = k.entry != 0 ? k.entry : k.level;
                if (!k.side.equals(s.side) || !k.symbol.equals(s.symbol)) continue;
                // the same idea on another chart rarely has exactly the same entry: also treat entries within 1.5 risks of each other as one idea
                double risk = Math.max(Math.abs(s.entry - s.stop), Math.abs(k.entry - k.stop));
                if (ref > 0 && kr > 0 && (Math.abs(ref - kr) / Math.abs(kr) <= tol || (risk > 0 && Math.abs(ref - kr) <= 1.5 * risk))) {
                    String add = s.tf + " " + s.scenario.replaceAll("\\s*\\(.*?\\)\\s*$", "");
                    if (!k.also.contains(s.tf)) k.also = k.also.isEmpty() ? add : k.also + "; " + add;
                    continue outer;
                }
            }
            keep.add(s);
        }
        return keep;
    }

    // ------------------------------------------------------------------ scenarios

    private static void patterns(Ctx x, List<Signal> out) {
        List<Patterns.Pattern> ps;
        try { ps = Patterns.detect(x.h1); } catch (RuntimeException e) { return; }
        for (Patterns.Pattern p : ps) {
            if (p.bias == null || p.bias.equals("NEUTRAL")) continue;
            boolean buy = p.isBuy();
            if (p.status.equals("confirmed")) {
                // the break has happened: wait for a retest of the broken line
                if (buy ? x.price > p.level + 3 * x.a : x.price < p.level - 3 * x.a) continue; // ran away, no retest to trade
                if (p.target > 0 && (buy ? x.price >= p.target : x.price <= p.target)) continue;
                double entry = p.level;
                // the guide: stop beyond the breakout candle at a minimum. Use the lowest/highest of the last 3 candles, never wider than before
                double ext = buy ? Math.min(x.h1[x.n - 1].low, Math.min(x.h1[x.n - 2].low, x.h1[x.n - 3].low)) - 0.1 * x.a : Math.max(x.h1[x.n - 1].high, Math.max(x.h1[x.n - 2].high, x.h1[x.n - 3].high)) + 0.1 * x.a;
                double stop = buy ? Math.max(p.level - 1.2 * x.a, Math.min(ext, p.level - 0.5 * x.a)) : Math.min(p.level + 1.2 * x.a, Math.max(ext, p.level + 0.5 * x.a));
                if (p.stopHint != 0) {
                    // the guide's stop for a rounded base: under the 61.8% level of the cup, never wider than 3 ATR
                    stop = buy ? Math.min(p.level - 0.5 * x.a, Math.max(p.stopHint, p.level - 3 * x.a)) : Math.max(p.level + 0.5 * x.a, Math.min(p.stopHint, p.level + 3 * x.a));
                }
                // sideways for several candles after the break: the retest is not coming, so join at the market with a stop under the pause
                boolean through = buy ? x.price > p.level + 0.3 * x.a : x.price < p.level - 0.3 * x.a;
                if (through) {
                    double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
                    for (int q = x.n - 4; q < x.n; q++) { hi = Math.max(hi, x.h1[q].high); lo = Math.min(lo, x.h1[q].low); }
                    if (hi - lo <= 1.2 * x.a) { entry = x.price; stop = buy ? lo - 0.1 * x.a : hi + 0.1 * x.a; }
                }
                Signal s = base(x, "Pattern", p.name + (entry == x.price && through ? " breakout (no retest)" : " breakout"), buy, entry, stop, p.target > 0 ? p.target : 0, Mie.S_QUALIFIED);
                s.level = p.level; s.refEpoch = p.startEpoch; s.lines = p.lines;
                s.id = sid(x, p.name, s.side, p.level);
                s.evidence.add("A " + p.name.toLowerCase(Locale.US) + " closed through " + Fmt.f(p.level) + " (confirmed).");
                s.evidence.add("Entry is a retest of the broken line, not a chase.");
                if (p.target > 0) s.evidence.add("Measured-move target " + Fmt.f(p.target) + " (a rule of thumb).");
                long baseLen = p.endEpoch > p.startEpoch ? (p.endEpoch - p.startEpoch) / x.gran : 0;
                if (baseLen >= 40) s.evidence.add("A long base (" + baseLen + " candles): the guide says the longer the consolidation, the bigger the breakout tends to be.");
                s.score += 1;
                if (finish(x, s, p.target > 0)) out.add(s);
            } else if (Math.abs(x.price - p.level) <= 1.5 * x.a) {
                // still forming and close to the breakout line: a heads-up, no order yet
                double entry = p.level, stop = buy ? p.level - 1.2 * x.a : p.level + 1.2 * x.a;
                Signal s = base(x, "Pattern", p.name + " forming", buy, entry, stop, p.target > 0 ? p.target : 0, Mie.S_WATCH);
                s.level = p.level; s.refEpoch = p.startEpoch; s.lines = p.lines;
                s.id = sid(x, p.name + " forming", s.side, p.level);
                s.evidence.add("A " + p.name.toLowerCase(Locale.US) + " is forming; price is within " + String.format(Locale.US, "%.1f", Math.abs(x.price - p.level) / x.a) + " ATR of the breakout line " + Fmt.f(p.level) + ".");
                s.evidence.add("No order yet: it needs a " + x.tf + " close through the line first.");
                if (finish(x, s, p.target > 0)) out.add(s);
            }
        }
    }

    private static void trendlines(Ctx x, List<Signal> out) {
        List<Trendlines.Line> ls;
        try { ls = Trendlines.find(x.h1, 150); } catch (RuntimeException e) { return; }
        Candle last = x.h1[x.n - 1];
        for (Trendlines.Line l : ls) {
            if (l.touches < 3) continue;
            double at = l.at(x.n - 1), next = at + l.slope;
            boolean support = l.role.equals("support");
            if (!l.broken) {
                boolean buy = support;
                double dist = Math.abs(x.price - at);
                if (dist > 1.0 * x.a) continue;
                boolean rej0 = buy ? last.low <= at + 0.3 * x.a && last.close > at && last.close > last.open
                              : last.high >= at - 0.3 * x.a && last.close < at && last.close < last.open;
                String sig = (buy ? last.low <= at + 0.5 * x.a && last.close >= at - 0.2 * x.a : last.high >= at - 0.5 * x.a && last.close <= at + 0.2 * x.a) ? signal(x.h1, buy) : null;
                boolean rej = rej0 || sig != null;
                // the guide: a trendline steeper than about 45 degrees breaks easily, and after five touches the break risk rises
                if (Math.abs(l.slope) > 0.5 * x.a) continue;
                double entry = next, stop = tightStop(x, buy, entry, buy ? at - 1.0 * x.a : at + 1.0 * x.a, 0.35);
                Signal s = base(x, "Trendline", (rising(l) ? "Rising " : "Falling ") + (support ? "support" : "resistance") + " trendline bounce", buy, entry, stop, 0, rej ? Mie.S_QUALIFIED : Mie.S_WATCH);
                s.level = at; s.refEpoch = x.h1[l.i1].epoch;
                s.lines.add(new double[]{x.h1[l.i1].epoch, l.p1, x.h1[x.n - 1].epoch, at});
                s.id = sid(x, "TLB", s.side, entry);
                s.evidence.add("A " + l.touches + "-touch " + (support ? "support" : "resistance") + " trendline is holding (not broken).");
                s.evidence.add(sig != null ? "Signal candle at the line: " + sig + "." : rej ? "The last " + x.tf + " candle rejected the line." : "Price is near the line; no rejection candle yet.");
                if (l.touches >= 4 && l.touches <= 5) s.score += 1;
                if (l.touches > 5) s.conflicts.add(l.touches + " touches: the guide warns a line tested more than five times breaks more often.");
                if (rej) s.score += 1;
                if (finish(x, s, false)) out.add(s);
            } else if (l.brokenAt >= x.n - 12 && l.brokenAt >= 0) {
                // broken: a support line broken down becomes resistance (sell the retest), and the reverse
                boolean buy = !support;
                double entry = next, stop = buy ? entry - 1.0 * x.a : entry + 1.0 * x.a;
                if (buy ? x.price < next - 0.3 * x.a : x.price > next + 0.3 * x.a) continue; // reclaimed the line: the break failed
                if (Math.abs(x.price - next) > 2.0 * x.a) continue;                          // too far for a retest
                Signal s = base(x, "Trendline", (support ? "Support" : "Resistance") + " trendline break and retest", buy, entry, stop, 0, Mie.S_QUALIFIED);
                s.level = next; s.refEpoch = x.h1[l.i1].epoch;
                s.lines.add(new double[]{x.h1[l.i1].epoch, l.p1, x.h1[x.n - 1].epoch, at});
                s.id = sid(x, "TLR", s.side, entry);
                s.evidence.add("A " + l.touches + "-touch " + (support ? "support" : "resistance") + " trendline broke " + (x.n - 1 - l.brokenAt) + " candles ago.");
                s.evidence.add("Entry is the retest of the broken line from the other side.");
                if (l.touches >= 4) s.score += 1;
                if (finish(x, s, false)) out.add(s);
            }
        }
    }

    private static boolean rising(Trendlines.Line l) { return l.slope > 0; }

    private static void levels(Ctx x, List<Signal> out) {
        Candle last = x.h1[x.n - 1];
        Signal best = null; int bestTouches = 0;
        for (Patterns.Zone z : x.zones) {
            if (z.touches < 3) continue;
            boolean below = z.high < x.price;       // the zone sits under price: a possible support
            boolean above = z.low > x.price;
            if (!below && !above) continue;
            boolean buy = below;
            double edge = buy ? z.high : z.low;
            if (Math.abs(x.price - edge) > 1.5 * x.a) continue;
            // did price cross this zone recently from the other side? then it is a break and retest
            boolean crossed = false;
            for (int k = Math.max(1, x.n - 8); k < x.n && !crossed; k++) {
                int other = 0;
                if (buy && x.h1[k].close > z.high + 0.3 * x.a) {
                    for (int j = Math.max(0, k - 12); j < k; j++) if (x.h1[j].close < z.high) other++;
                }
                if (!buy && x.h1[k].close < z.low - 0.3 * x.a) {
                    for (int j = Math.max(0, k - 12); j < k; j++) if (x.h1[j].close > z.low) other++;
                }
                if (other >= 3) crossed = true;
            }
            // a break and retest needs a stronger level (4+ tests) and price close to the edge
            if (crossed && (z.touches < 4 || Math.abs(x.price - edge) > 0.8 * x.a)) crossed = false;
            boolean rej = buy ? last.low <= z.high + 0.2 * x.a && last.close > z.high && last.close > last.open
                          : last.high >= z.low - 0.2 * x.a && last.close < z.low && last.close < last.open;
            String sig = !crossed && (buy ? last.low <= z.high + 0.3 * x.a : last.high >= z.low - 0.3 * x.a) ? signal(x.h1, buy) : null;
            if (sig != null) rej = true;
            double stop = (rej || crossed) ? tightStop(x, buy, edge, buy ? z.low - 0.6 * x.a : z.high + 0.6 * x.a, 0.35) : (buy ? z.low - 0.6 * x.a : z.high + 0.6 * x.a);
            String name = crossed ? (buy ? "Resistance break and retest (now support)" : "Support break and retest (now resistance)")
                                  : (buy ? "Support level rejection" : "Resistance level rejection");
            Signal s = base(x, "Level", name, buy, edge, stop, 0, crossed || rej ? Mie.S_QUALIFIED : Mie.S_WATCH);
            s.level = edge; s.zoneLow = z.low; s.zoneHigh = z.high; s.refEpoch = z.firstEpoch;
            s.id = sid(x, "LVL" + (crossed ? "R" : "J"), s.side, edge);
            s.evidence.add("A " + z.touches + "x tested " + (buy ? "support" : "resistance") + " zone " + Fmt.f(z.low) + " - " + Fmt.f(z.high) + ".");
            s.evidence.add(crossed ? "Price broke through it recently and is coming back to test it." : sig != null ? "Signal candle at the zone: " + sig + "." : rej ? "The last " + x.tf + " candle rejected the zone." : "Price is at the zone; no rejection candle yet.");
            if (z.touches >= 4) s.score += 1;
            if (crossed || rej) s.score += 1;
            if (finish(x, s, false)) {
                if (best == null || s.score > best.score || (s.score == best.score && z.touches > bestTouches)) { best = s; bestTouches = z.touches; }
            }
        }
        if (best != null) out.add(best);
    }


    // ------------------------------------------------------------------ candle signals (pin bar, engulfing, fakey)

    /** a confirming signal candle at the end of the chart: pin bar / engulfing on the last candle, or a fakey in the last two */
    public static String signal(Candle[] c, boolean buy) {
        int n = c.length;
        if (n < 20) return null;
        for (CandleBehaviour.Tag t : CandleBehaviour.at(c, n - 1)) {
            if (!t.bias.equals(buy ? "BUY" : "SELL")) continue;
            if (t.name.contains("rejection")) return "pin bar";
            if (t.name.contains("engulfing")) return "engulfing candle";
        }
        // fakey: an inside bar, then a false break of the mother bar that closes back inside
        for (int i = n - 1; i >= n - 2; i--) {
            if (i < 3) break;
            Candle m = c[i - 2], in = c[i - 1], k = c[i];
            if (!(in.high <= m.high && in.low >= m.low)) continue;
            if (buy && k.low < m.low && k.close > m.low && k.close > k.open) return "fakey (false break of the inside bar)";
            if (!buy && k.high > m.high && k.close < m.high && k.close < k.open) return "fakey (false break of the inside bar)";
        }
        return null;
    }

    /** stop just beyond the signal candles (last two), not tighter than minAtr ATR from the entry */
    private static double tightStop(Ctx x, boolean buy, double entry, double fallback, double minAtr) {
        Candle l = x.h1[x.n - 1], p = x.h1[x.n - 2];
        double s = buy ? Math.min(l.low, p.low) - 0.15 * x.a : Math.max(l.high, p.high) + 0.15 * x.a;
        if (buy ? s > entry - minAtr * x.a : s < entry + minAtr * x.a) s = buy ? entry - minAtr * x.a : entry + minAtr * x.a;
        // never wider than the old placement
        if (buy ? s < fallback : s > fallback) s = fallback;
        return s;
    }


    // ------------------------------------------------------------------ trend channels (the guide: fade an overshoot of the channel line)

    private static void channels(Ctx x, List<Signal> out) {
        List<Trendlines.Line> ls;
        try { ls = Trendlines.find(x.h1, 150); } catch (RuntimeException e) { return; }
        Candle[] c = x.h1;
        int n = x.n;
        Candle last = c[n - 1];
        for (Trendlines.Line l : ls) {
            if (l.touches < 3 || l.broken) continue;
            boolean up = l.role.equals("support") && l.slope > 0, down = l.role.equals("resistance") && l.slope < 0;
            if (!up && !down) continue;
            if (Math.abs(l.slope) > 0.5 * x.a) continue;
            // the parallel line goes through the most extreme candle on the other side since the first pivot
            double off = 0;
            for (int j = l.i1; j < n - 1; j++) off = Math.max(off, up ? c[j].high - l.at(j) : l.at(j) - c[j].low);
            if (off < 2.5 * x.a) continue;                                     // too narrow to be a channel
            int touches = 0, pierces = 0;
            for (int j = l.i1; j < n - 1; j++) {
                double d = up ? c[j].high - l.at(j) : l.at(j) - c[j].low;
                if (d >= off - 0.5 * x.a) touches++;
                double edge = l.at(j) + (up ? off : -off);
                if (up ? c[j].high > edge && c[j].close < edge && j >= n - 30 : c[j].low < edge && c[j].close > edge && j >= n - 30) pierces++;
            }
            if (touches < 2) continue;
            double edgeNow = l.at(n - 1) + (up ? off : -off);
            boolean over = up ? last.high > edgeNow && last.close < edgeNow : last.low < edgeNow && last.close > edgeNow;
            if (!over) continue;
            boolean buy = down;                                                // an up channel is faded at the top (sell), a down channel at the bottom (buy)
            double entry = last.close;
            double stop = buy ? last.low - 0.15 * x.a : last.high + 0.15 * x.a;
            if (buy ? stop > entry - 0.3 * x.a : stop < entry + 0.3 * x.a) stop = buy ? entry - 0.3 * x.a : entry + 0.3 * x.a;
            double mid = l.at(n - 1) + (up ? off : -off) / 2;
            Signal sg = base(x, "Trendline", "Channel overshoot fade (counter-trend)", buy, entry, stop, mid, Mie.S_QUALIFIED);
            sg.level = edgeNow; sg.refEpoch = c[l.i1].epoch;
            sg.lines.add(new double[]{c[l.i1].epoch, l.p1, last.epoch, l.at(n - 1)});
            sg.lines.add(new double[]{c[l.i1].epoch, l.p1 + (up ? off : -off), last.epoch, edgeNow});
            sg.id = sid(x, "CHAN", sg.side, edgeNow);
            sg.evidence.add("A " + (up ? "rising" : "falling") + " channel (" + l.touches + " touches on the base line, " + touches + " on the far line) was overshot and the candle closed back inside.");
            sg.evidence.add(pierces >= 1 ? "A second overshoot of the same line: the guide says the reversal from a second consecutive overshoot is more reliable." : "First overshoot of that line.");
            sg.conflicts.add("Counter-trend: the guide treats a channel-line fade as betting against the trend, so the stop is tight.");
            sg.score += pierces >= 1 ? 2 : 1;
            sg.evidence.add("Target is the middle of the channel " + Fmt.f(mid) + ".");
            if (finish(x, sg, true)) out.add(sg);
        }
    }

    // ------------------------------------------------------------------ flags and pennants (measured by the pole)

    private static void flags(Ctx x, List<Signal> out) {
        Candle[] c = x.h1;
        int n = x.n;
        for (int dir = 0; dir < 2; dir++) {
            boolean buy = dir == 0;
            // flag of length f candles ending now (or the break just happened), pole in the 3..12 candles before it
            boolean done = false;
            for (int k = 5; k >= 0 && !done; k--)       // the flag ended k candles ago (the break and its retest come after it)
            for (int f = 4; f <= 14 && !done; f += 2) {
                int fe = n - 1 - k, fs = fe - f + 1;   // flag candles fs..fe
                int pe = fs - 1;
                if (pe < 15) continue;
                // pole: the strongest swing in the 12 candles before the flag
                int ps = -1; double pole = 0;
                for (int j = Math.max(2, pe - 12); j < pe - 1; j++) {
                    double mv = buy ? c[pe].high - c[j].low : c[j].high - c[pe].low;
                    if (mv > pole) { pole = mv; ps = j; }
                }
                if (ps < 0 || pole < 3.0 * x.a) continue;
                // the flag: contained, drifting a little against the pole, no more than half the pole retraced
                double fh = Double.NEGATIVE_INFINITY, fl = Double.POSITIVE_INFINITY;
                for (int j = fs; j < fe; j++) { fh = Math.max(fh, c[j].high); fl = Math.min(fl, c[j].low); }
                if (fh - fl > 0.55 * pole || fh - fl < 0.5 * x.a) continue;
                // the guide's sloping-flag rule: a real flag drifts AGAINST the pole. One that slopes with it is exhaustion (a wedge), not a continuation.
                {
                    int half = Math.max(1, f / 2);
                    double m1 = 0, m2 = 0;
                    for (int j = fs; j < fs + half; j++) m1 += c[j].close;
                    for (int j = fe - half + 1; j <= fe; j++) m2 += c[j].close;
                    double drift = (m2 - m1) / half / Math.max(1, f - half);
                    if (buy ? drift > 0.03 * x.a : drift < -0.03 * x.a) continue;
                }
                double top = buy ? c[pe].high : c[ps].high, bot = buy ? c[ps].low : c[pe].low;
                if (buy ? fl < bot + 0.5 * pole : fh > top - 0.5 * pole) continue;
                // the pole must be the move that led here: the flag does not make a new extreme beyond the pole end
                if (buy ? fh > c[pe].high + 0.5 * x.a : fl < c[pe].low - 0.5 * x.a) continue;
                double bound = buy ? fh : fl;          // the flag boundary that has to break
                double last = c[n - 1].close;
                boolean broke = false;
                if (k == 0) broke = buy ? last > bound : last < bound;
                else {
                    // a close through the boundary after the flag, and no close back inside it since
                    int bi = -1;
                    for (int j = fe + 1; j < n; j++) if (buy ? c[j].close > bound : c[j].close < bound) { bi = j; break; }
                    if (bi < 0) continue;
                    for (int j = bi; j < n; j++) if (buy ? c[j].close < bound - 0.3 * x.a : c[j].close > bound + 0.3 * x.a) { bi = -1; break; }
                    if (bi < 0) continue;
                    broke = true;
                }
                Signal sg;
                if (broke) {
                    if (buy ? last > bound + 2.0 * x.a : last < bound - 2.0 * x.a) continue; // ran away, no retest left
                    double target = buy ? bound + pole : bound - pole;
                    double stop = buy ? Math.min(fl + 0.2 * (fh - fl), bound - 1.2 * x.a) : Math.max(fh - 0.2 * (fh - fl), bound + 1.2 * x.a);
                    double minStop = buy ? bound - 0.6 * x.a : bound + 0.6 * x.a;
                    if (buy ? stop > minStop : stop < minStop) stop = minStop;
                    sg = base(x, "Pattern", (buy ? "Bull" : "Bear") + " flag breakout", buy, bound, stop, target, Mie.S_QUALIFIED);
                    sg.id = sid(x, "FLAG", sg.side, bound);
                    sg.evidence.add("A " + f + "-candle " + (buy ? "bull" : "bear") + " flag after a " + Fmt.f(pole) + " pole closed through " + Fmt.f(bound) + ".");
                    sg.evidence.add("Entry is a retest of the flag boundary. Target is the pole height measured from the break (a rule of thumb).");
                } else if (Math.abs(last - bound) <= 1.0 * x.a) {
                    double target = buy ? bound + pole : bound - pole;
                    double stop = buy ? bound - 1.0 * x.a : bound + 1.0 * x.a;
                    sg = base(x, "Pattern", (buy ? "Bull" : "Bear") + " flag forming", buy, bound, stop, target, Mie.S_WATCH);
                    sg.id = sid(x, "FLAGW", sg.side, bound);
                    sg.evidence.add("A " + f + "-candle " + (buy ? "bull" : "bear") + " flag after a " + Fmt.f(pole) + " pole; price is within " + String.format(Locale.US, "%.1f", Math.abs(last - bound) / x.a) + " ATR of the boundary " + Fmt.f(bound) + ".");
                    sg.evidence.add("No order yet: it needs a " + x.tf + " close through the boundary first.");
                } else continue;
                sg.level = bound; sg.refEpoch = c[ps].epoch;
                sg.lines.add(new double[]{c[fs].epoch, buy ? fh : fl, c[fe].epoch, buy ? fh : fl});
                sg.lines.add(new double[]{c[fs].epoch, buy ? fl : fh, c[fe].epoch, buy ? fl : fh});
                sg.score += 1;
                if (finish(x, sg, true)) out.add(sg);
                done = true; // one flag per direction is enough
            }
        }
    }

    // ------------------------------------------------------------------ shared steps

    /** stable across scans so one situation alerts once: kind, side, a coarse price bucket and a 12-hour time bucket */
    private static String sid(Ctx x, String kind, String side, double level) {
        long pb = Math.round(level / (x.price * 0.002));
        long tb = x.h1[x.n - 1].epoch / (x.gran * 12);
        return "PA|" + x.symbol + "|" + x.tf + "|" + kind + "|" + side + "|" + pb + "|" + tb;
    }

    private static Signal base(Ctx x, String family, String scenario, boolean buy, double entry, double stop, double target, String state) {
        Signal s = new Signal();
        s.symbol = x.symbol; s.label = x.label; s.family = family; s.tf = x.tf; s.scenario = scenario + " (" + x.tf + ")"; s.side = buy ? "BUY" : "SELL";
        s.entry = entry; s.stop = stop; s.target = target; s.state = state; s.price = x.price;
        return s;
    }


    // ------------------------------------------------------------------ double confirmation, reversal test, action plan

    /** reversal ideas: turning-point patterns, channel fades and rejections at a level. Everything else is a continuation. */
    static boolean isReversal(Signal s) {
        String n = s.scenario.toLowerCase(Locale.US);
        return n.contains("double top") || n.contains("double bottom") || n.contains("head and shoulders") || n.contains("rounding") || n.contains("cup and handle")
            || n.contains("wedge") || n.contains("channel overshoot") || n.contains("rejection") || n.contains("fakey") || n.contains("pin bar") || n.contains("engulfing");
    }

    /**
     * The double confirmation. First confirmation = the structure itself (pattern, line or level, already part of the signal).
     * Second confirmation = a trigger on a lower chart once price is at the entry zone: a break of structure in the trade direction
     * or a rejection / engulfing candle. Both present = confirmed (adds to the confidence). Without lower-chart data it is simply "not available".
     */
    private static void doubleConfirm(Ctx x, Signal s, boolean buy) {
        Candle[] L = x.lower;
        s.dcTf = x.lowerTf;
        if (L == null || L.length < 40 || x.lowerTf.isEmpty()) { s.dc = 0; return; }
        double tol = 0.35 * x.a;
        int n = L.length, touch = -1;
        for (int j = Math.max(0, n - 30); j < n; j++) if (L[j].low <= s.entry + tol && L[j].high >= s.entry - tol) { touch = j; break; }
        String lt = x.lowerTf;
        if (touch < 0) {
            s.dc = 1;
            s.dcText = "price to reach the entry zone around " + Fmt.f(s.entry) + ", then a " + lt + " " + (buy ? "bullish" : "bearish") + " trigger (a " + lt + " break of structure or a rejection candle)";
            return;
        }
        List<Smc.Swing> sw = buy ? Smc.swingHighs(L, 2, Math.max(0, touch - 12), n - 1) : Smc.swingLows(L, 2, Math.max(0, touch - 12), n - 1);
        // the trigger: a break of structure in the trade direction on the lower chart AFTER price reached the entry zone, and still fresh (last 12 candles),
        // with price not having closed through the stop since. A lone rejection candle is not enough: it must be followed by the break.
        for (int i = Math.max(touch + 1, n - 12); i < n && s.dc != 2; i++) {
            Smc.Swing ref = null;
            for (Smc.Swing q : sw) if (q.idx + 2 <= i - 1) ref = q;
            if (ref == null) continue;
            boolean bos = buy ? L[i].close > ref.level && L[i - 1].close <= ref.level : L[i].close < ref.level && L[i - 1].close >= ref.level;
            if (!bos) continue;
            boolean broken = false;
            for (int k = touch; k < n; k++) if (buy ? L[k].close < s.stop : L[k].close > s.stop) broken = true;
            if (broken) continue;
            boolean rej = false;
            for (int k = touch; k < i && !rej; k++) for (CandleBehaviour.Tag t : CandleBehaviour.at(L, k)) if (t.bias.equals(s.side) && (t.name.contains("rejection") || t.name.contains("engulfing"))) rej = true;
            s.dc = 2; s.dcEpoch = L[i].epoch; s.dcPrice = L[i].close;
            s.dcText = lt + " break of structure " + (buy ? "above " : "below ") + Fmt.f(ref.level) + " after price reached the entry zone" + (rej ? ", preceded by a rejection candle" : "");
        }
        if (s.dc == 2) {
            s.evidence.add("DOUBLE CONFIRMATION: the structure signal and a " + s.dcText + ".");
        } else {
            s.dc = 1;
            double trig = 0;
            for (Smc.Swing q : sw) if (buy ? q.level > L[n - 1].close : q.level < L[n - 1].close) trig = q.level;
            s.dcText = "a " + lt + " " + (buy ? "close above " : "close below ") + (trig > 0 ? Fmt.f(trig) : Fmt.f(s.entry)) + " (break of structure) or a " + (buy ? "bullish" : "bearish") + " rejection candle at the entry zone";
        }
    }

    /** the card's action plan: what was identified, what it is waiting for, which order to place, when it is invalid */
    private static String plan(Ctx x, Signal s, boolean buy) {
        String order = (buy ? "BUY" : "SELL") + " LIMIT";
        StringBuilder sb = new StringBuilder();
        sb.append("SETUP IDENTIFIED: ").append(s.scenario).append(" on the ").append(s.tf).append(" chart, a ").append(buy ? "buy" : "sell").append(s.reversal ? " (reversal idea)" : " with the " + x.trendTf + " trend").append(".\n");
        boolean structure = s.actionable();
        if (!structure) sb.append("WAITING FOR (1): a ").append(s.tf).append(" candle close ").append(buy ? "above " : "below ").append(Fmt.f(s.level > 0 ? s.level : s.entry)).append(" to confirm the structure.\n");
        if (s.dc == 1) sb.append("WAITING FOR ").append(structure ? "(2)" : "(2)").append(": ").append(s.dcText).append(".\n");
        else if (s.dc == 2) sb.append("DOUBLE CONFIRMED: ").append(s.dcText).append(".\n");
        else sb.append("Second confirmation on a lower chart is not available for this setup.\n");
        sb.append("THEN: place a ").append(order).append(" at ").append(Fmt.f(s.entry)).append(", target ").append(Fmt.f(s.target)).append(" (").append(String.format(Locale.US, "%.1fR", s.rr)).append(").\n");
        sb.append("INVALID IF: a ").append(s.tf).append(" candle closes ").append(buy ? "below " : "above ").append(Fmt.f(s.stop)).append(". Stop at ").append(Fmt.f(s.stop)).append(".");
        return sb.toString();
    }

    /**
     * One side only. When both a buy and a sell are on the table for a market, keep the more convincing side and drop the other.
     * Strength: agrees with the higher chart (a reversal is allowed but ranks lower), then bigger chart, then double confirmation, then checks met.
     * Expired entries are history and are left alone.
     */
    public static List<Signal> resolve(List<Signal> in) {
        Signal bestBuy = null, bestSell = null;
        for (Signal s : in) {
            if (s.expired) continue;
            if (s.isBuy()) { if (bestBuy == null || strength(s) > strength(bestBuy)) bestBuy = s; }
            else if (bestSell == null || strength(s) > strength(bestSell)) bestSell = s;
        }
        List<Signal> out = new ArrayList<>();
        boolean buyWins = bestSell == null || (bestBuy != null && strength(bestBuy) >= strength(bestSell));
        for (Signal s : in) {
            if (s.expired) { out.add(s); continue; }
            if (bestBuy != null && bestSell != null && s.isBuy() != buyWins) { s.expiredWhy = "replaced"; continue; }
            out.add(s);
        }
        return out;
    }

    public static int strength(Signal s) {
        int align = s.ctx.startsWith("with") ? 4 : s.ctx.contains("unclear") ? 2 : 0;
        return align * 1000 + s.frameRank() * 100 + (s.dc == 2 ? 150 : 0) + s.score * 20 + (s.actionable() ? 10 : 0);
    }

    /** list order: still-valid ideas first, bigger charts first, then ready before watching, then double confirmed, then checks met; expired last */
    public static void sortForDisplay(List<Signal> l) {
        Collections.sort(l, new Comparator<Signal>() {
            @Override public int compare(Signal p, Signal q) {
                if (p.expired != q.expired) return p.expired ? 1 : -1;
                if (p.frameRank() != q.frameRank()) return q.frameRank() - p.frameRank();
                int ap = p.actionable() ? 1 : 0, aq = q.actionable() ? 1 : 0;
                if (ap != aq) return aq - ap;
                if ((p.dc == 2) != (q.dc == 2)) return q.dc == 2 ? 1 : -1;
                return q.score - p.score;
            }
        });
    }

    /** the guide's confluence checks: the entry on the 61.8% (or 50%) retracement of the last leg, and on the 200 moving average */
    private static void confluence(Ctx x, Signal s, boolean buy) {
        try {
            Candle[] c = x.h1;
            int from = Math.max(0, x.n - 140);
            java.util.List<Smc.Swing> hi = Smc.swingHighs(c, 3, from, x.n - 1), lo = Smc.swingLows(c, 3, from, x.n - 1);
            if (buy && !hi.isEmpty() && !lo.isEmpty()) {
                Smc.Swing h = hi.get(hi.size() - 1), l = null;
                for (Smc.Swing q : lo) if (q.idx < h.idx) l = q;
                if (l != null && h.level - l.level >= 2 * x.a && x.price < h.level) fibNote(x, s, h.level - 0.618 * (h.level - l.level), h.level - 0.5 * (h.level - l.level), "up");
            } else if (!buy && !hi.isEmpty() && !lo.isEmpty()) {
                Smc.Swing l = lo.get(lo.size() - 1), h = null;
                for (Smc.Swing q : hi) if (q.idx < l.idx) h = q;
                if (h != null && h.level - l.level >= 2 * x.a && x.price > l.level) fibNote(x, s, l.level + 0.618 * (h.level - l.level), l.level + 0.5 * (h.level - l.level), "down");
            }
            if (x.n >= 200) {
                double sum = 0;
                for (int k = x.n - 200; k < x.n; k++) sum += c[k].close;
                double ma = sum / 200;
                if (Math.abs(s.entry - ma) <= 0.5 * x.a) { s.evidence.add("Entry sits on the 200 moving average (" + Fmt.f(ma) + "): the guide's trendline-plus-average confluence."); s.score += 1; }
            }
        } catch (RuntimeException ignored) { }
    }

    private static void fibNote(Ctx x, Signal s, double r618, double r50, String leg) {
        if (Math.abs(s.entry - r618) <= 0.35 * x.a) { s.evidence.add("Entry sits on the 61.8% retracement of the last " + leg + " leg (" + Fmt.f(r618) + "): Fibonacci confluence."); s.score += 1; }
        else if (Math.abs(s.entry - r50) <= 0.3 * x.a) { s.evidence.add("Entry sits on the 50% retracement of the last " + leg + " leg (" + Fmt.f(r50) + ")."); s.score += 1; }
    }

    /** targets, reward:risk, context, state and the written explanation. Returns false when the idea is not tradeable. */
    private static boolean finish(Ctx x, Signal s, boolean hasOwnTarget) {
        boolean buy = s.isBuy();
        double risk = Math.abs(s.entry - s.stop);
        if (!(risk > 0) || (buy ? !(s.stop < s.entry) : !(s.stop > s.entry))) return false;
        // price already through the stop: nothing to trade
        if (buy ? x.price < s.stop : x.price > s.stop) return false;
        TargetPicker.Pick tp = TargetPicker.pick(buy, s.entry, s.stop, x.t4, x.t1, x.t30);
        double rrOwn = hasOwnTarget ? Math.abs(s.target - s.entry) / risk : 0;
        boolean ownOk = hasOwnTarget && rrOwn >= 1.0 && (buy ? s.target > s.entry : s.target < s.entry);
        if (!ownOk) {
            if (tp.target == null) return false;
            s.target = tp.target.level;
            s.evidence.add("Target from structure: " + tp.target + ".");
        }
        s.rr = Math.abs(s.target - s.entry) / risk;
        s.rrBand = Advisor.bandOf(s.rr);
        if (s.family.equals("Trendline") && s.rr < 2.0) s.conflicts.add("Reward is under the guide's 2:1 minimum for trendline trades.");
        confluence(x, s, buy);
        if (tp.obstruction != null && tp.obstruction.rMultiple < 1.0) s.conflicts.add("Opposing structure close to the entry: " + tp.obstruction + ".");
        else s.score += 1;
        // 4H context
        String with = buy ? "bull" : "bear", opp = buy ? "bear" : "bull";
        if (x.trend4.equals(with)) { s.ctx = "with " + x.trendTf; s.evidence.add("The " + x.trendTf + " trend agrees."); s.score += 1; }
        else if (x.trend4.equals(opp)) { s.ctx = "against " + x.trendTf; s.conflicts.add("Against the " + x.trendTf + " trend."); }
        else s.ctx = x.trendTf + " unclear";
        s.reversal = isReversal(s);
        // top-down: trade with the higher chart, unless this is a reversal idea
        if (s.ctx.startsWith("against") && !s.reversal) return false;
        if (s.ctx.startsWith("against")) s.evidence.add("Reversal idea: it is allowed against the " + x.trendTf + " trend because the pattern itself is a turning point.");
        // a key level near the entry adds weight to a pattern or trendline idea
        if (!s.family.equals("Level")) for (Patterns.Zone z : x.zones) {
            if (z.touches >= 3 && z.low - 0.5 * x.a <= s.entry && z.high + 0.5 * x.a >= s.entry) { s.evidence.add("Entry sits on a " + z.touches + "x " + (buy ? "support" : "resistance") + " level."); s.score += 1; break; }
        }
        if (s.state.equals(Mie.S_QUALIFIED) && (buy ? x.price <= s.entry + 0.15 * x.a : x.price >= s.entry - 0.15 * x.a)) s.state = Mie.S_ENTRY;
        doubleConfirm(x, s, buy);
        s.score = Math.max(1, Math.min(5, s.score + 1 + (s.dc == 2 ? 1 : 0)));
        s.key = s.scenario + " | " + s.side + " | " + s.ctx;
        String dir = buy ? "BUY" : "SELL";
        s.headline = s.state.equals(Mie.S_WATCH) ? dir + " watch: " + s.scenario.toLowerCase(Locale.US)
            : dir + " " + String.format(Locale.US, "%.1f", s.rr) + "R: " + s.scenario.toLowerCase(Locale.US);
        s.plan = plan(x, s, buy);
        StringBuilder sb = new StringBuilder(s.plan).append("\n\n");
        sb.append("What this is: ").append(s.scenario).append(" on the " + s.tf + " chart (price-action mode: no order blocks, FVGs or liquidity sweeps).\n");
        sb.append("Plan: ").append(s.state.equals(Mie.S_WATCH) ? "nothing to place yet. If it confirms, the plan would be " : "place a limit order: ")
          .append(dir.toLowerCase(Locale.US)).append(" at ").append(Fmt.f(s.entry)).append(", stop ").append(Fmt.f(s.stop)).append(", target ").append(Fmt.f(s.target))
          .append(" (").append(String.format(Locale.US, "%.1fR", s.rr)).append(", ").append(s.rrBand).append(").\n\n");
        sb.append("Why:\n"); for (String e : s.evidence) sb.append("+ ").append(e).append('\n');
        if (!s.conflicts.isEmpty()) { sb.append("\nAgainst it:\n"); for (String c : s.conflicts) sb.append("- ").append(c).append('\n'); }
        sb.append("\nInvalid if a " + s.tf + " candle closes ").append(buy ? "below " : "above ").append(Fmt.f(s.stop)).append(".\n");
        sb.append("Checks met: ").append(s.score).append("/5 (a summary, not a win probability). How this scenario has ended before is under the Bird's Eye tab.");
        s.explanation = sb.toString();
        return true;
    }
}
