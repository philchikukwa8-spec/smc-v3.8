package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.mie.DataCheck;
import com.smcscanner.engine.mie.Learning;
import com.smcscanner.engine.mie.Row;
import com.smcscanner.engine.pa.PaEngine;
import com.smcscanner.engine.pa.PaReplay;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The Market's Bird's Eye View: price-action mode. Runs beside the SMC mode or on its own; shares the research database. */
public final class PaRunner {
    private PaRunner() {}

    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public String symbol, label, dataStatus = "", dataNote = "";
        public long at;
        public List<PaEngine.Signal> signals = new ArrayList<>();
        /** one line per signal: how this situation has ended before */
        public List<String> memory = new ArrayList<>();
        /** the last 1H candles for the picture */
        public Candle[] chart;
        /** the last candles of the 30m and 15m charts, for signals found on them (null when not fetched) */
        public Candle[] chart30, chart15;

        public Candle[] chartFor(String tf) { return tf.equals("30m") ? chart30 : tf.equals("15m") ? chart15 : chart; }
    }

    private static final Object LOCK = new Object();

    public static ArrayList<Item> load(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "pa.bin");
            if (!f.exists()) return new ArrayList<>();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                @SuppressWarnings("unchecked") ArrayList<Item> l = (ArrayList<Item>) in.readObject();
                return l;
            } catch (Exception e) { return new ArrayList<>(); }
        }
    }

    private static void save(Context c, List<Item> items) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "pa.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(new ArrayList<>(items)); } catch (Exception ignored) { }
        }
    }

    private static final long KEEP_EXPIRED = 24 * 3600L;

    /** keeps ready setups that vanished (and ones already expired, for a day) at the end of the list, unless you logged or removed them */
    private static void carryExpired(Context c, List<PaEngine.Signal> prev, List<PaEngine.Signal> now, double px, long t) {
        int kept = 0;
        for (PaEngine.Signal p : prev) {
            if (kept >= 6) break;
            if (Store.setupFlag(c, 'R', p.id) || Store.setupFlag(c, 'L', p.id)) continue;
            boolean still = false;
            for (PaEngine.Signal q : now) {
                if (q.expired) continue;
                double risk = Math.max(1e-9, Math.abs(p.entry - p.stop));
                if (q.tf.equals(p.tf) && q.side.equals(p.side) && Math.abs(q.entry - p.entry) <= 1.5 * risk) { still = true; break; }
            }
            if (still) continue;
            if (p.expired) {
                if (t - p.expiredAt > KEEP_EXPIRED) continue;
            } else {
                if (!p.actionable()) continue;
                p.expired = true; p.expiredAt = t;
                boolean buy = p.isBuy();
                if (px > 0 && (buy ? px <= p.stop : px >= p.stop)) p.expiredWhy = "Price went through the stop (the idea is invalid).";
                else if (px > 0 && p.target > 0 && (buy ? px >= p.target : px <= p.target)) p.expiredWhy = "Price already reached the target without the entry being taken.";
                else p.expiredWhy = "The setup no longer holds (it was replaced, the pattern failed or price moved away).";
            }
            for (PaEngine.Signal q : now) if (!q.expired && !q.side.equals(p.side)) p.expiredWhy = "Replaced by a stronger " + q.side.toLowerCase(Locale.US) + " setup (the app shows one direction per market).";
            now.add(p);
            kept++;
        }
    }

    private static Candle[] closed(DerivClient client, String sym, long gran, int count, long now) throws Exception {
        return Smc.closedOnly(DataCheck.clean(client.get(sym, gran, count)), gran, now);
    }

    /** returns the number of notifications sent */
    public static int run(Context c, DerivClient client, long now) {
        SqliteResearchStore store = SqliteResearchStore.get(c);
        Scan.Config cfg = Store.config(c);
        Set<String> seen = Store.paSeen(c);
        List<Row> past = new ArrayList<>();
        try { past = store.all(); } catch (Throwable ignored) { }
        Set<String> blocked = Store.learnBlock(c) ? Learning.blocked(past) : new java.util.HashSet<String>();
        List<Item> items = new ArrayList<>();
        List<Item> prevItems = load(c);
        int sent = 0;
        for (Scan.Market m : cfg.markets) {
            try {
                Candle[] h4 = null;
                try { h4 = closed(client, m.symbol, 14400, 200, now); } catch (Exception e) { h4 = null; }
                Candle[] h1 = closed(client, m.symbol, 3600, 300, now);
                DataCheck.Report r1h = DataCheck.check(h1, 3600, now);
                Item it = new Item();
                it.symbol = m.symbol; it.label = m.label; it.at = now;
                it.dataStatus = r1h.status; it.dataNote = r1h.usable() ? "" : r1h.note;
                List<PaEngine.Signal> all = new ArrayList<>();
                // the lower charts that give the second (double) confirmation: 15m for 1H ideas, 5m for 30m and 15m ideas
                Candle[] lo15 = null, lo5 = null;
                try { lo15 = closed(client, m.symbol, 900, 300, now); } catch (Exception e) { lo15 = null; }
                try { lo5 = closed(client, m.symbol, 300, 300, now); } catch (Exception e) { lo5 = null; }
                if (r1h.usable()) {
                    it.chart = java.util.Arrays.copyOfRange(h1, Math.max(0, h1.length - 90), h1.length);
                    all.addAll(PaEngine.analyseFrame(m.symbol, m.label, "1H", 3600, h1, h4, "4H", h4, h1, null, lo15, "15m", now));
                }
                if (Store.lowerFrames(c)) {
                    Candle[] m30 = null, m15 = null;
                    try { m30 = closed(client, m.symbol, 1800, 300, now); } catch (Exception e) { m30 = null; }
                    try { m15 = closed(client, m.symbol, 900, 300, now); } catch (Exception e) { m15 = null; }
                    if (m30 != null && DataCheck.check(m30, 1800, now).usable()) {
                        it.chart30 = java.util.Arrays.copyOfRange(m30, Math.max(0, m30.length - 90), m30.length);
                        all.addAll(PaEngine.analyseFrame(m.symbol, m.label, "30m", 1800, m30, h1, "1H", h4, h1, m30, lo5, "5m", now));
                    }
                    if (m15 != null && DataCheck.check(m15, 900, now).usable()) {
                        it.chart15 = java.util.Arrays.copyOfRange(m15, Math.max(0, m15.length - 90), m15.length);
                        all.addAll(PaEngine.analyseFrame(m.symbol, m.label, "15m", 900, m15, h1, "1H", h4, h1, m30, lo5, "5m", now));
                    }
                }
                // one side only: when a buy and a sell are both on the table, keep the more convincing side
                all = PaEngine.resolve(all);
                // entries that were ready last scan and are gone now are kept at the bottom as "entry expired" until you remove them
                Item prev = null;
                for (Item pi : prevItems) if (pi.symbol.equals(m.symbol)) prev = pi;
                double lastPx = it.chart != null && it.chart.length > 0 ? it.chart[it.chart.length - 1].close : 0;
                if (prev != null) carryExpired(c, prev.signals, all, lastPx, now);
                PaEngine.sortForDisplay(all);
                it.signals = all.size() > 14 ? new ArrayList<>(all.subList(0, 14)) : all;
                for (PaEngine.Signal s : it.signals) {
                    if (s.expired) { it.memory.add(""); continue; }
                    Row probe = Row.fromPa(s, now, PaEngine.VERSION, it.dataStatus);
                    it.memory.add(Learning.memoryLine(past, "PA", probe.scenario));
                    Row old = store.get(s.id);
                    if (old == null || (!old.actionable() && s.actionable())) {
                        probe.source = "live";
                        if (old != null) probe.ts = old.ts;
                        store.upsert(probe);
                    }
                    if (s.actionable() && !seen.contains(s.id)) {
                        seen.add(s.id);
                        if (blocked.contains(Learning.key(probe))) {
                            it.dataNote = (it.dataNote.isEmpty() ? "" : it.dataNote + " ") + "Learning: \"" + s.scenario + "\" lost money over " + Learning.MIN_TRADES + "+ trades, so no alert is sent.";
                            continue;
                        }
                        // one alert per setup. The quiet period is kept per market, side AND chart, so a 15m and a 1H setup never silence each other,
                        // but the same setup does not repeat when its id shifts a little between scans.
                        String key = m.symbol + "@" + s.tf;
                        double risk = Math.abs(s.entry - s.stop);
                        if (Store.paRecentlyAlerted(c, key, s.side, s.entry, risk, now)) continue;
                        Store.paMarkAlerted(c, key, s.side, s.entry, risk, now);
                        String title = "Price action " + m.label + " " + s.tf + ": " + s.side + " (" + s.rrBand + ")";
                        String body = s.scenario + " | entry " + Fmt.f(s.entry) + " | stop " + Fmt.f(s.stop) + " | target " + Fmt.f(s.target) + " | " + String.format(Locale.US, "%.1fR", s.rr);
                        Notifier.alert(c, title, body, s.explanation, true);
                        Store.addAlert(c, "pa", title, body, s.explanation);
                        sent++;
                    }
                }
                items.add(it);
            } catch (Exception e) { /* try this market again next scan */ }
        }
        if (!items.isEmpty()) save(c, items);
        Store.setPaSeen(c, seen);
        try { MieRunner.resolve(c, client, store, now); } catch (Throwable ignored) { }
        return sent;
    }

    /** replays price action over real Deriv history (no look-ahead); rows are labelled in-sample, validation and out-of-sample */
    public static String replay(Context c, DerivClient client, Scan.Market m) throws Exception {
        return replayFrames(c, client, m, true, Store.lowerFrames(c), Store.lowerFrames(c), 1, null, null);
    }

    /** one line per chart. `report` (nullable) receives the rows' KPIs by chart name. */
    public static String replayFrames(Context c, DerivClient client, Scan.Market m, boolean f1h, boolean f30, boolean f15, int mult,
                                      java.util.Map<String, Candle[]> cache, java.util.Map<String, PaReplay.Report> report) throws Exception {
        SqliteResearchStore store = SqliteResearchStore.get(c);
        Candle[] h1 = MieRunner.cachedHistory(cache, client, m.symbol, 3600, mult);
        Candle[] h4 = MieRunner.cachedHistory(cache, client, m.symbol, 14400, mult);
        Candle[] m30 = f30 || f15 ? MieRunner.cachedHistory(cache, client, m.symbol, 1800, mult) : null;
        StringBuilder sb = new StringBuilder();
        if (f1h) {
            PaReplay.Report rep = PaReplay.run(m.symbol, m.label, h4, h1, 4, PaEngine.VERSION);
            store.upsertAll(rep.rows);
            if (report != null) report.put("1H", rep);
            sb.append(m.label).append(" (1H): ").append(rep.windows).append(" windows, ").append(rep.rows.size()).append(" signals recorded.\n").append(rep.all.line()).append('\n');
        }
        if (f30) {
            PaReplay.Report r30 = PaReplay.runFrame(m.symbol, m.label, "30m", 1800, m30, h1, 3600, "1H", h4, h1, m30, 14400, 3600, 1800, 4, PaEngine.VERSION);
            store.upsertAll(r30.rows);
            if (report != null) report.put("30m", r30);
            sb.append(m.label).append(" (30m): ").append(r30.windows).append(" windows, ").append(r30.rows.size()).append(" signals recorded.\n").append(r30.all.line()).append('\n');
        }
        if (f15) {
            Candle[] m15 = MieRunner.cachedHistory(cache, client, m.symbol, 900, mult);
            PaReplay.Report r15 = PaReplay.runFrame(m.symbol, m.label, "15m", 900, m15, h1, 3600, "1H", h4, h1, m30, 14400, 3600, 1800, 8, PaEngine.VERSION);
            store.upsertAll(r15.rows);
            if (report != null) report.put("15m", r15);
            sb.append(m.label).append(" (15m): ").append(r15.windows).append(" windows, ").append(r15.rows.size()).append(" signals recorded.\n").append(r15.all.line()).append('\n');
        }
        return sb.toString().trim();
    }
}
