package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One full pass over every market and pair. */
public final class Scan {
    private Scan() {}

    /** Anything that can fetch candles (the real Deriv client, or a fake in tests). */
    public interface Candles { Candle[] get(String symbol, long granularity, int count) throws Exception; }

    /** every Deriv volatility index: symbol, name. The three most used come first. */
    public static final String[][] ALL_VOLATILITY = {
        {"R_75", "Volatility 75"}, {"R_50", "Volatility 50"}, {"R_25", "Volatility 25"},
        {"R_100", "Volatility 100"}, {"R_10", "Volatility 10"},
        {"1HZ75V", "Volatility 75 (1s)"}, {"1HZ50V", "Volatility 50 (1s)"}, {"1HZ25V", "Volatility 25 (1s)"},
        {"1HZ100V", "Volatility 100 (1s)"}, {"1HZ10V", "Volatility 10 (1s)"},
        {"1HZ150V", "Volatility 150 (1s)"}, {"1HZ250V", "Volatility 250 (1s)"},
        {"1HZ90V", "Volatility 90 (1s)"}, {"1HZ30V", "Volatility 30 (1s)"}, {"1HZ15V", "Volatility 15 (1s)"}
    };

    public static final class Market {
        public final String symbol, label;
        public Market(String symbol, String label) { this.symbol = symbol; this.label = label; }
    }
    public static final class Timeframe {
        public final String label; public final long granularity;
        public Timeframe(String label, long granularity) { this.label = label; this.granularity = granularity; }
    }
    public static final class Pair {
        public final String setup, entry; // entry may be null
        /** finer timeframes to zoom into after the entry zone, closest first (may be empty) */
        public final List<String> refine = new ArrayList<>();
        public Pair(String setup, String entry, String... refine) {
            this.setup = setup; this.entry = entry;
            for (String r : refine) this.refine.add(r);
        }
        /** e.g. "4H > 1H > 5m > 1m" */
        public String label() {
            StringBuilder sb = new StringBuilder(setup);
            if (entry != null) sb.append(" > ").append(entry);
            for (String r : refine) sb.append(" > ").append(r);
            return sb.toString();
        }
    }

    public static final class Config {
        public List<Market> markets = new ArrayList<>();
        public List<Timeframe> timeframes = new ArrayList<>();
        public List<Pair> pairs = new ArrayList<>();
        public int minScore = 4, recentCandles = 4, recentLtf = 12, candleCount = 300, waitCandles = 30;
        public SmcConfig strategy = new SmcConfig();
        public List<String> require = new ArrayList<>();
        public int minQuality = 0;
        /** entry style 0 edge, 1 middle, 2 deep, -1 = best found by calibration */
        public int entryMode = 1;
        public Calibration.Book book;
        public double riskMoney;
        /** wait this long (seconds) for a precise entry before using the first entry zone; 0 = do not wait */
        public long refineWaitSec = 1800;
        /** sniper: alert only when the 5m/1m confirmed the entry */
        public boolean requireZoom = false;

        /** Sniper preset: only 1D/4H/1H ideas, every entry zoomed to the 5m/1m, strict quality filters. */
        public static Config sniper() {
            Config c = defaults();
            c.pairs.clear();
            c.pairs.add(new Pair("1D", "1H", "5m", "1m"));
            c.pairs.add(new Pair("4H", "1H", "5m", "1m"));
            c.pairs.add(new Pair("1H", "15m", "5m", "1m"));
            c.minScore = 6;
            c.minQuality = 5;
            c.require.add("trend");
            c.require.add("majorSweep");
            c.require.add("strongDisp");
            c.requireZoom = true;
            return c;
        }

        /** Volatility 75/50/25. Idea timeframes 1D/4H/1H/15m/5m, each with an entry zone on a lower timeframe that is then zoomed into on 5m and 1m. */
        public static Config defaults() {
            Config c = new Config();
            for (String[] m : ALL_VOLATILITY) c.markets.add(new Market(m[0], m[1]));
            c.timeframes.add(new Timeframe("1m", 60));
            c.timeframes.add(new Timeframe("5m", 300));
            c.timeframes.add(new Timeframe("15m", 900));
            c.timeframes.add(new Timeframe("30m", 1800));
            c.timeframes.add(new Timeframe("1H", 3600));
            c.timeframes.add(new Timeframe("4H", 14400));
            c.timeframes.add(new Timeframe("1D", 86400));
            c.pairs.add(new Pair("1D", "1H", "5m", "1m"));
            c.pairs.add(new Pair("4H", "1H", "5m", "1m"));
            c.pairs.add(new Pair("1H", "15m", "5m", "1m"));
            c.pairs.add(new Pair("15m", "5m", "1m"));
            c.pairs.add(new Pair("5m", "1m"));
            c.recentLtf = 20;
            return c;
        }
    }

    public static final class Result {
        public final List<Lifecycle.Msg> msgs = new ArrayList<>();
        public int ok, failed;
        public final List<String> errors = new ArrayList<>();
    }

    public static Result scanAll(final Candles client, Lifecycle.State state, Config cfg, long now) {
        Map<String, Long> tfs = new LinkedHashMap<>();
        for (Timeframe t : cfg.timeframes) tfs.put(t.label, t.granularity);
        List<Pair> pairs = new ArrayList<>();
        List<Pair> src = cfg.pairs.isEmpty() ? new ArrayList<Pair>() : cfg.pairs;
        if (src.isEmpty()) for (Timeframe t : cfg.timeframes) src.add(new Pair(t.label, null));
        for (Pair p : src) {
            boolean seen = false;
            for (Pair q : pairs) if (q.setup.equals(p.setup)) seen = true;
            if (!seen) pairs.add(p);
        }
        Result res = new Result();
        final int count = cfg.candleCount;
        for (final Market m : cfg.markets) {
            for (Pair p : pairs) {
                Long gran = tfs.get(p.setup);
                if (gran == null) { res.errors.add("Unknown timeframe \"" + p.setup + "\" in pairs"); res.failed++; continue; }
                Long lg = p.entry != null ? tfs.get(p.entry) : null;
                if (p.entry != null && lg == null) { res.errors.add("Unknown timeframe \"" + p.entry + "\" in pairs"); res.failed++; continue; }
                if (lg != null && lg >= gran) { res.errors.add("Entry timeframe " + p.entry + " must be lower than " + p.setup); res.failed++; continue; }
                try {
                    Candle[] candles = client.get(m.symbol, gran, count);
                    res.ok++;
                    Lifecycle.Source ltf = null;
                    if (lg != null) {
                        final long lgv = lg;
                        ltf = new Lifecycle.Source(p.entry, lgv) {
                            public Candle[] fetch() throws Exception { return client.get(m.symbol, lgv, count); }
                        };
                    }
                    List<Lifecycle.Source> refine = new ArrayList<>();
                    long prev = lg != null ? lg : gran;
                    for (String rl : p.refine) {
                        final Long rg = tfs.get(rl);
                        if (rg == null) { res.errors.add("Unknown timeframe \"" + rl + "\" in pairs"); continue; }
                        if (rg >= prev) { res.errors.add("Timeframe " + rl + " must be lower than the one before it in " + p.label()); continue; }
                        prev = rg;
                        refine.add(new Lifecycle.Source(rl, rg) {
                            public Candle[] fetch() throws Exception { return client.get(m.symbol, rg, count); }
                        });
                    }
                    Timeframe higher = null;
                    for (Timeframe t : cfg.timeframes) {
                        if (t.granularity > gran && (higher == null || t.granularity < higher.granularity)) higher = t;
                    }
                    Lifecycle.Source next = null;
                    if (higher != null) {
                        final long hg = higher.granularity;
                        next = new Lifecycle.Source(higher.label, hg) {
                            public Candle[] fetch() throws Exception { return client.get(m.symbol, hg, count); }
                        };
                    }
                    Timeframe top = null;
                    if (higher != null) {
                        for (Timeframe t : cfg.timeframes) {
                            if (t.granularity > higher.granularity && (top == null || t.granularity < top.granularity)) top = t;
                        }
                    }
                    Lifecycle.Source topSrc = null;
                    if (top != null) {
                        final long tg = top.granularity;
                        topSrc = new Lifecycle.Source(top.label, tg) {
                            public Candle[] fetch() throws Exception { return client.get(m.symbol, tg, count); }
                        };
                    }
                    Lifecycle.Options o = new Lifecycle.Options();
                    o.top = topSrc;
                    o.now = now; o.minScore = cfg.minScore; o.recentCandles = cfg.recentCandles; o.recentLtf = cfg.recentLtf;
                    o.waitCandles = cfg.waitCandles; o.strategy = cfg.strategy;
                    o.filters = new Breakdowns.Filters();
                    o.filters.require = cfg.require; o.filters.minQuality = cfg.minQuality; o.filters.requireZoom = cfg.requireZoom;
                    o.entryMode = cfg.entryMode; o.book = cfg.book; o.riskMoney = cfg.riskMoney;
                    o.refine = refine; o.refineWaitSec = cfg.refineWaitSec;
                    res.msgs.addAll(Lifecycle.processPair(state, new Lifecycle.Series(m.symbol, m.label, p.setup, gran, candles), ltf, o, next));
                } catch (Exception e) {
                    res.failed++;
                    res.errors.add(m.label + " " + p.setup + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                }
            }
        }
        return res;
    }
}
