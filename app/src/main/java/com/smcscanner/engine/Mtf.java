package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-down trading: a higher-timeframe (HTF) setup gives the idea and zone; a lower-timeframe (LTF)
 * setup of the same direction, formed after price returned to that zone, gives the entry and stop.
 * The target comes from the HTF.
 */
public final class Mtf {
    private Mtf() {}

    /** First HTF candle after the trigger that trades back into the zone (null = not yet). */
    public static Candle zoneTouch(Setup htf, Candle[] candles) {
        boolean buy = htf.isBuy();
        for (Candle c : candles) {
            if (c.epoch <= htf.triggerEpoch) continue;
            if (buy ? c.low <= htf.zoneHigh : c.high >= htf.zoneLow) return c;
        }
        return null;
    }

    /** Combined setup (LTF entry + stop, HTF target) if the LTF setup confirms the HTF idea, else null. */
    public static Setup confirmWithLtf(Setup htf, Setup ltf, double minRR) {
        if (!ltf.side.equals(htf.side)) return null;
        boolean buy = htf.isBuy();
        if (buy ? !(ltf.sweepLevel <= htf.zoneHigh) : !(ltf.sweepLevel >= htf.zoneLow)) return null;
        if (buy ? !(ltf.sl > htf.sl) : !(ltf.sl < htf.sl)) return null;
        if (buy ? !(ltf.entry < htf.tp) : !(ltf.entry > htf.tp)) return null;
        double risk = Math.abs(ltf.entry - ltf.sl);
        if (!(risk > 0)) return null;
        double rr = Math.abs(htf.tp - ltf.entry) / risk;
        if (rr < minRR - 1e-9) return null;
        Setup m = ltf.copy();
        m.tp = htf.tp;
        m.tpType = htf.tpType;
        m.rr = rr;
        m.score = Math.min(7, ltf.score + 1);
        return m;
    }

    /** Entry price for an entry style: 0 = edge of the zone (fills most often), 1 = middle, 2 = deep (smallest risk, fills least). */
    public static double entryPrice(Setup s, int mode) {
        boolean buy = s.isBuy();
        if (mode == 0) return buy ? s.zoneHigh : s.zoneLow;
        if (mode == 2) return buy ? s.zoneLow : s.zoneHigh;
        return (s.zoneLow + s.zoneHigh) / 2;
    }

    /**
     * Moves the entry inside the zone, keeping the same stop and target. If that would break the order
     * (entry beyond stop/target) or push reward:risk below the minimum, the middle entry is kept.
     */
    public static Setup applyEntryMode(Setup merged, int mode, double minRR) {
        if (mode == 1) return merged;
        double e = entryPrice(merged, mode);
        boolean buy = merged.isBuy();
        if (buy ? !(e > merged.sl && e < merged.tp) : !(e < merged.sl && e > merged.tp)) return merged;
        double risk = Math.abs(e - merged.sl);
        if (!(risk > 0)) return merged;
        double rr = Math.abs(merged.tp - e) / risk;
        if (rr < minRR - 1e-9) return merged;
        Setup m = merged.copy();
        m.entry = e;
        m.rr = rr;
        return m;
    }

    /** One finer timeframe to zoom into (candles must be closed ones only). */
    public static final class Tier {
        public final String label;
        public final long gran;
        public final Candle[] candles;
        public Tier(String label, long gran, Candle[] candles) { this.label = label; this.gran = gran; this.candles = candles; }
    }

    /** A successful zoom-in: the refined setup, which timeframe it came from, and the candle that confirmed it. */
    public static final class Step {
        public final Setup setup;
        public final String label;
        public final long gran;
        public final int idx;
        public final Candle[] candles;
        Step(Setup setup, String label, long gran, int idx, Candle[] candles) { this.setup = setup; this.label = label; this.gran = gran; this.idx = idx; this.candles = candles; }
    }

    /**
     * Zooms in: inside the current entry zone, looks on each finer timeframe (in order) for a same-direction
     * setup whose sweep is in that zone and whose stop is tighter, keeping the original higher-timeframe target.
     * A timeframe with no confirming setup, or with no data that far back, is skipped. `endEpoch` (0 = none)
     * stops the search at that time (used by backtests).
     */
    public static List<Step> refine(Setup start, long startGran, List<Tier> tiers, SmcConfig cfg, int minScore, long endEpoch) {
        List<Step> steps = new ArrayList<>();
        Setup cur = start;
        long curGran = startGran;
        for (Tier t : tiers) {
            if (t.candles.length == 0 || t.gran >= curGran) continue;
            long from = cur.triggerEpoch + curGran; // the parent candle has closed by then
            if (t.candles[0].epoch > from) continue; // no data this far back
            for (int j = 0; j < t.candles.length; j++) {
                Candle c = t.candles[j];
                if (c.epoch < from) continue;
                if (endEpoch != 0 && c.epoch >= endEpoch) break;
                Setup ls = Smc.detectAt(t.candles, j, cfg);
                if (ls == null || ls.score < minScore) continue;
                Setup merged = confirmWithLtf(cur, ls, cfg.minRR);
                if (merged == null) continue;
                steps.add(new Step(merged, t.label, t.gran, j, t.candles));
                cur = merged;
                curGran = t.gran;
                break;
            }
        }
        return steps;
    }

    public static Setup findLtfEntry(Setup htf, Candle[] ltfClosed, long fromEpoch, int lastN, SmcConfig cfg, int minScore) {
        int start = Math.max(0, ltfClosed.length - lastN);
        for (int j = start; j < ltfClosed.length; j++) {
            if (ltfClosed[j].epoch < fromEpoch) continue;
            Setup s = Smc.detectAt(ltfClosed, j, cfg);
            if (s == null || s.score < minScore) continue;
            Setup merged = confirmWithLtf(htf, s, cfg.minRR);
            if (merged != null) return merged;
        }
        return null;
    }

    public static final class Options {
        public int minScore = 4;
        public int waitHtfCandles = 30;
        /** 0 = work it out from the series */
        public long ltfGran = 0;
        public Candle[] nextCandles;
        public long nextGran;
        /** the timeframe two steps above the idea (for the top-trend check) */
        public Candle[] topCandles;
        public long topGran;
        public Breakdowns.Filters filters;
        /** entry style, see entryPrice (default: middle of the zone) */
        public int entryMode = 1;
        /** finer timeframes to zoom into after the entry zone is found (closest first). Empty = no zoom. */
        public List<Tier> refine = new ArrayList<>();
        /** how long after the entry zone forms to look for a more precise entry (matches the live scanner's wait) */
        public long refineWaitSec = 1800;
        /** move the stop to the entry after this many R of profit (0 = never) */
        public double breakEvenR = 0;
    }

    public static final class FactorTrade {
        public final Backtest.Trade trade;
        public final Breakdown breakdown;
        /** how many zoom-ins happened (0 = entered on the first entry zone) */
        public int depth;
        /** stop distance of the first entry zone, before zooming in */
        public double coarseRisk;
        /** what the simulation ran on, so the same trade can be replayed with other rules (for example break-even) */
        public Candle[] simCandles;
        public int simIdx, simWait, simHold;
        FactorTrade(Backtest.Trade t, Breakdown b) { trade = t; breakdown = b; }
    }

    public static List<FactorTrade> backtestMtfTrades(Candle[] htf, Candle[] ltf, long htfGran, SmcConfig cfg, Options opts) {
        List<FactorTrade> trades = new ArrayList<>();
        if (ltf.length == 0) return trades;
        long ltfGran = opts.ltfGran != 0 ? opts.ltfGran : (ltf.length > 1 ? ltf[1].epoch - ltf[0].epoch : 0);
        long ltfStart = ltf[0].epoch;
        int skipUntil = -1;
        for (int i = 0; i < htf.length - 1; i++) {
            if (i <= skipUntil) continue;
            Setup setup = Smc.detectAt(htf, i, cfg);
            if (setup == null || setup.score < opts.minScore) continue;
            skipUntil = i + 3;
            if (setup.triggerEpoch < ltfStart) continue;
            boolean buy = setup.isBuy();
            Candle touch = null;
            long endEpoch = htf[Math.min(htf.length - 1, i + opts.waitHtfCandles)].epoch + htfGran;
            for (int k = i + 1; k < htf.length && k - i <= opts.waitHtfCandles; k++) {
                Candle c = htf[k];
                if (touch == null && (buy ? c.low <= setup.zoneHigh : c.high >= setup.zoneLow)) touch = c;
                if (buy ? c.close < setup.sl : c.close > setup.sl) { endEpoch = c.epoch + htfGran; break; }
            }
            if (touch == null) continue;
            int li = -1;
            for (int q = 0; q < ltf.length; q++) if (ltf[q].epoch >= touch.epoch) { li = q; break; }
            if (li < 0) continue;
            for (int j = li; j < ltf.length && ltf[j].epoch < endEpoch; j++) {
                Setup ls = Smc.detectAt(ltf, j, cfg);
                if (ls == null || ls.score < opts.minScore) continue;
                Setup merged = confirmWithLtf(setup, ls, cfg.minRR);
                if (merged == null) continue;
                Setup coarse = merged;
                Candle[] simC = ltf;
                int simIdx = j;
                long simGran = ltfGran;
                List<Step> steps = new ArrayList<>();
                if (!opts.refine.isEmpty() && opts.refineWaitSec > 0) {
                    long until = merged.triggerEpoch + ltfGran + opts.refineWaitSec;
                    if (endEpoch != 0 && endEpoch < until) until = endEpoch;
                    steps = refine(merged, ltfGran, opts.refine, cfg, opts.minScore, until);
                    if (!steps.isEmpty()) {
                        Step last = steps.get(steps.size() - 1);
                        merged = last.setup; simC = last.candles; simIdx = last.idx; simGran = last.gran;
                    }
                }
                if (opts.filters != null && opts.filters.requireZoom && steps.isEmpty()) continue;
                merged = applyEntryMode(merged, opts.entryMode, cfg.minRR);
                Breakdowns.Input in = new Breakdowns.Input();
                in.ideaTf = "idea"; in.entryTf = steps.isEmpty() ? "entry" : "precise"; in.idea = setup; in.entry = merged;
                if (!steps.isEmpty()) { in.coarse = coarse; in.coarseTf = "entry zone"; in.steps = steps; }
                in.nextTf = opts.nextCandles != null ? "next" : null;
                in.nextCandles = opts.nextCandles; in.nextGran = opts.nextGran;
                in.topTf = opts.topCandles != null ? "top" : null; in.topCandles = opts.topCandles; in.topGran = opts.topGran;
                in.ideaCandles = htf; in.ideaGran = htfGran;
                in.asOf = simC[simIdx].epoch + simGran;
                Breakdown bd = Breakdowns.build(in);
                if (!Breakdowns.passes(bd, opts.filters)) continue;
                int wait = 40, hold = 400;
                if (simGran > 0 && ltfGran > 0 && simGran < ltfGran) { wait = (int) (40 * ltfGran / simGran); hold = (int) (400 * ltfGran / simGran); }
                FactorTrade ft = new FactorTrade(Backtest.simulate(simC, simIdx, merged, wait, hold, opts.breakEvenR), bd);
                ft.depth = steps.size();
                ft.simCandles = simC; ft.simIdx = simIdx; ft.simWait = wait; ft.simHold = hold;
                ft.coarseRisk = Math.abs(coarse.entry - coarse.sl);
                trades.add(ft);
                break;
            }
        }
        return trades;
    }

    public static Backtest.Result backtestMtf(Candle[] htf, Candle[] ltf, long htfGran, SmcConfig cfg, Options opts) {
        List<Backtest.Trade> ts = new ArrayList<>();
        for (FactorTrade t : backtestMtfTrades(htf, ltf, htfGran, cfg, opts)) ts.add(t.trade);
        return Backtest.summarize(ts);
    }
}
