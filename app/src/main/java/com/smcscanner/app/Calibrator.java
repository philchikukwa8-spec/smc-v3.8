package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Backtest;
import com.smcscanner.engine.Calibration;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Mtf;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.Smc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Replays the strategy on history for each entry style to measure past win rates (feeds the confidence figure). */
public final class Calibrator {
    private Calibrator() {}

    public static final class Run {
        public int htfCount, ltfCount;
        public final List<List<Mtf.FactorTrade>> byMode = new ArrayList<>();
        public Calibration calibration;
        /** entry style "middle", with and without zooming into the finer charts */
        public Backtest.Result withZoom, withoutZoom;
        /** trades (middle style) that were refined, and how much the stop distance shrank on average (0..1) */
        public int refinedTrades, totalTrades;
        public double avgRiskCut;
        /** the same history under the Sniper rules (1D/4H/1H ideas, strict filters, zoom required) */
        public Backtest.Result sniper;
        public double days;
    }

    private static long granOf(Scan.Config cfg, String label) {
        for (Scan.Timeframe t : cfg.timeframes) if (t.label.equals(label)) return t.granularity;
        return 0;
    }

    private static Candle[] closedHistory(DerivClient client, String symbol, long gran, int n, Map<String, Candle[]> cache) throws Exception {
        String key = symbol + "|" + gran + "|" + n;
        if (cache != null && cache.containsKey(key)) return cache.get(key);
        Candle[] c = Smc.closedOnly(client.history(symbol, gran, n), gran, System.currentTimeMillis() / 1000);
        if (cache != null) cache.put(key, c);
        return c;
    }

    public static final class PeriodResult {
        public Backtest.Result result;
        public double days;
        public List<Backtest.Trade> trades = new ArrayList<>();
    }

    private static int need(double days, long gran, int cap) { return (int) Math.min(cap, Math.ceil(days * 86400.0 / gran)); }

    /**
     * Replays the last `days` days under either the normal rules or the Sniper rules, on freshly downloaded history.
     * Slow: background thread.
     */
    public static PeriodResult replay(DerivClient client, Scan.Config cfg, Scan.Market m, Scan.Pair pair, Map<String, Candle[]> cache, boolean sniper, int days) throws Exception {
        Scan.Config sn = Scan.Config.sniper();
        long hg = granOf(cfg, pair.setup), lg = granOf(cfg, pair.entry);
        Scan.Timeframe higher = null;
        for (Scan.Timeframe t : cfg.timeframes) if (t.granularity > hg && (higher == null || t.granularity < higher.granularity)) higher = t;
        // enough history for the period, plus a few days (and, for the idea chart, a few weeks) of lead-in so setups can form
        Candle[] ltf = closedHistory(client, m.symbol, lg, need(days + 5, lg, 60000), cache);
        Candle[] htf = closedHistory(client, m.symbol, hg, Math.max(300, need(days + 40, hg, 5000)), cache);
        Candle[] next = higher != null ? closedHistory(client, m.symbol, higher.granularity, 800, cache) : null;
        List<Mtf.Tier> tiers = new ArrayList<>();
        for (String label : pair.refine) {
            long g = granOf(cfg, label);
            if (g > 0) tiers.add(new Mtf.Tier(label, g, closedHistory(client, m.symbol, g, need(days + 5, g, 60000), cache)));
        }
        Mtf.Options o = new Mtf.Options();
        o.ltfGran = lg; o.entryMode = 1; o.refine = tiers; o.refineWaitSec = cfg.refineWaitSec;
        o.minScore = sniper ? sn.minScore : cfg.minScore;
        o.filters = new com.smcscanner.engine.Breakdowns.Filters();
        if (sniper) { o.filters.require = sn.require; o.filters.minQuality = sn.minQuality; o.filters.requireZoom = true; }
        if (next != null) { o.nextCandles = next; o.nextGran = higher.granularity; }
        PeriodResult r = new PeriodResult();
        long last = ltf.length > 0 ? ltf[ltf.length - 1].epoch : 0;
        long from = last - days * 86400L;
        for (Mtf.FactorTrade t : Mtf.backtestMtfTrades(htf, ltf, hg, cfg.strategy, o)) if (t.trade.setup.triggerEpoch >= from) r.trades.add(t.trade);
        r.result = Backtest.summarize(r.trades);
        r.days = days;
        return r;
    }

    /** Downloads history and tests all three entry styles for one market and pair. Slow: call from a background thread. */
    public static Run run(DerivClient client, Scan.Config cfg, Scan.Market m, Scan.Pair pair, Map<String, Candle[]> cache) throws Exception {
        long hg = granOf(cfg, pair.setup), lg = granOf(cfg, pair.entry);
        int ltfN = 20000;
        int htfN = (int) Math.min(5000, Math.max(300, ltfN * lg / hg));
        Scan.Timeframe higher = null;
        for (Scan.Timeframe t : cfg.timeframes) if (t.granularity > hg && (higher == null || t.granularity < higher.granularity)) higher = t;
        Candle[] ltf = closedHistory(client, m.symbol, lg, ltfN, cache);
        Candle[] htf = closedHistory(client, m.symbol, hg, htfN, cache);
        Candle[] next = higher != null ? closedHistory(client, m.symbol, higher.granularity, 800, cache) : null;
        List<Mtf.Tier> tiers = new ArrayList<>();
        for (String label : pair.refine) {
            long g = granOf(cfg, label);
            if (g > 0) tiers.add(new Mtf.Tier(label, g, closedHistory(client, m.symbol, g, 20000, cache)));
        }
        Run r = new Run();
        r.htfCount = htf.length; r.ltfCount = ltf.length;
        for (int mode = 0; mode < 3; mode++) {
            Mtf.Options o = new Mtf.Options();
            o.ltfGran = lg; o.entryMode = mode; o.refine = tiers; o.refineWaitSec = cfg.refineWaitSec;
            if (next != null) { o.nextCandles = next; o.nextGran = higher.granularity; }
            r.byMode.add(Mtf.backtestMtfTrades(htf, ltf, hg, cfg.strategy, o));
        }
        // how much did zooming in change things (middle entry style)?
        List<Backtest.Trade> with = new ArrayList<>();
        double cut = 0;
        for (Mtf.FactorTrade t : r.byMode.get(1)) {
            with.add(t.trade);
            r.totalTrades++;
            if (t.depth > 0 && t.coarseRisk > 0) { r.refinedTrades++; cut += 1 - Math.abs(t.trade.setup.entry - t.trade.setup.sl) / t.coarseRisk; }
        }
        r.avgRiskCut = r.refinedTrades > 0 ? cut / r.refinedTrades : 0;
        r.withZoom = Backtest.summarize(with);
        Mtf.Options plain = new Mtf.Options();
        plain.ltfGran = lg; plain.entryMode = 1;
        if (next != null) { plain.nextCandles = next; plain.nextGran = higher.granularity; }
        List<Backtest.Trade> wo = new ArrayList<>();
        for (Mtf.FactorTrade t : Mtf.backtestMtfTrades(htf, ltf, hg, cfg.strategy, plain)) wo.add(t.trade);
        r.withoutZoom = Backtest.summarize(wo);
        Scan.Config sn = Scan.Config.sniper();
        Mtf.Options so = new Mtf.Options();
        so.ltfGran = lg; so.entryMode = 1; so.refine = tiers; so.refineWaitSec = cfg.refineWaitSec; so.minScore = sn.minScore;
        so.filters = new com.smcscanner.engine.Breakdowns.Filters();
        so.filters.require = sn.require; so.filters.minQuality = sn.minQuality; so.filters.requireZoom = true;
        if (next != null) { so.nextCandles = next; so.nextGran = higher.granularity; }
        List<Backtest.Trade> sn2 = new ArrayList<>();
        for (Mtf.FactorTrade t : Mtf.backtestMtfTrades(htf, ltf, hg, cfg.strategy, so)) sn2.add(t.trade);
        r.sniper = Backtest.summarize(sn2);
        r.days = ltf.length > 1 ? (ltf[ltf.length - 1].epoch - ltf[0].epoch) / 86400.0 : 0;
        r.calibration = Calibration.build(r.byMode, System.currentTimeMillis());
        return r;
    }

    public static void runAndStore(Context c, DerivClient client, Scan.Config cfg, Scan.Market m, Scan.Pair pair, Map<String, Candle[]> cache) throws Exception {
        Run r = run(client, cfg, m, pair, cache);
        Store.putCalibration(c, Calibration.Book.key(m.symbol, pair.setup, pair.entry), r.calibration);
    }

    /** Calibrates every market/pair that has no result yet or whose result is older than 7 days. Returns how many it did. */
    public static int refreshStale(Context c, DerivClient client, Scan.Config cfg, boolean force) {
        int done = 0;
        Map<String, Candle[]> cache = new HashMap<>();
        long weekAgo = System.currentTimeMillis() - 7L * 86400 * 1000;
        for (Scan.Market m : cfg.markets) {
            for (Scan.Pair p : cfg.pairs) {
                Calibration old = Store.book(c).items.get(Calibration.Book.key(m.symbol, p.setup, p.entry));
                if (!force && old != null && old.builtAt > weekAgo) continue;
                try { runAndStore(c, client, cfg, m, p, cache); done++; } catch (Throwable t) { /* try again next pass */ }
                if (cache.size() > 12) cache.clear(); // keep memory small
            }
        }
        return done;
    }
}
