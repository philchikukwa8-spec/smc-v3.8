package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.fib.FibEngine;
import com.smcscanner.engine.fib.FibReplay;
import com.smcscanner.engine.mie.DataCheck;
import com.smcscanner.engine.mie.Learning;
import com.smcscanner.engine.mie.Row;

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

/** The Fib pullback strategy: scans, records, learns and alerts on its own channel. Shares the research database. */
public final class FibRunner {
    private FibRunner() {}

    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public String symbol, label, dataNote = "";
        public long at;
        public List<FibEngine.Setup> setups = new ArrayList<>();
        public List<String> memory = new ArrayList<>();
        /** the last M15 and H4 candles for the chart window */
        public Candle[] m15, h4;
    }

    private static final Object LOCK = new Object();

    public static ArrayList<Item> load(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "fib.bin");
            if (!f.exists()) return new ArrayList<>();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                @SuppressWarnings("unchecked") ArrayList<Item> l = (ArrayList<Item>) in.readObject();
                return l;
            } catch (Exception e) { return new ArrayList<>(); }
        }
    }

    private static void save(Context c, List<Item> items) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "fib.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(new ArrayList<>(items)); } catch (Exception ignored) { }
        }
    }

    private static final long KEEP_EXPIRED = 24 * 3600L;

    /** Expired entries stay at the bottom of the list (stamped ENTRY EXPIRED) for a day or until you remove them; logged or removed ones are never shown. */
    private static List<FibEngine.Setup> keepExpired(Context c, List<FibEngine.Setup> now, List<Item> prevItems, String symbol, long t) {
        List<FibEngine.Setup> live = new ArrayList<>(), dead = new ArrayList<>();
        Item prev = null;
        for (Item pi : prevItems) if (pi.symbol.equals(symbol)) prev = pi;
        for (FibEngine.Setup s : now) {
            if (Store.setupFlag(c, 'R', s.id) || Store.setupFlag(c, 'L', s.id)) continue;
            if (s.state.equals(FibEngine.S_EXPIRED)) {
                long first = 0;
                if (prev != null) for (FibEngine.Setup ps : prev.setups) if (ps.id.equals(s.id) && ps.expiredAt > 0) first = ps.expiredAt;
                s.expiredAt = first > 0 ? first : t;
                if (t - s.expiredAt > KEEP_EXPIRED) continue;
                dead.add(s);
            } else live.add(s);
        }
        if (prev != null) for (FibEngine.Setup ps : prev.setups) {
            if (dead.size() >= 4) break;
            if (Store.setupFlag(c, 'R', ps.id) || Store.setupFlag(c, 'L', ps.id)) continue;
            boolean present = false;
            for (FibEngine.Setup q : live) if (q.id.equals(ps.id) || (q.side.equals(ps.side) && q.bosEpoch[0] == ps.bosEpoch[0])) present = true;
            for (FibEngine.Setup q : dead) if (q.id.equals(ps.id)) present = true;
            if (present) continue;
            if (ps.state.equals(FibEngine.S_EXPIRED)) { if (t - ps.expiredAt > KEEP_EXPIRED) continue; }
            else if (ps.actionable()) {
                ps.state = FibEngine.S_EXPIRED; ps.expiredAt = t;
                ps.conflicts.add("ENTRY EXPIRED: The setup no longer holds (price moved on or the structure changed).");
                ps.headline = ps.label + ": Fib pullback " + (ps.isBuy() ? "buy" : "sell") + " (ENTRY EXPIRED)";
                ps.explanation = "ENTRY EXPIRED. The setup no longer holds (price moved on or the structure changed). Do not place this order. It stays at the bottom of the list until you remove it.";
            } else continue;
            dead.add(ps);
        }
        live.addAll(dead);
        return live;
    }

    private static Candle[] closed(DerivClient client, String sym, long gran, int count, long now) throws Exception {
        return Smc.closedOnly(DataCheck.clean(client.get(sym, gran, count)), gran, now);
    }

    /** returns the number of alerts sent */
    public static int run(Context c, DerivClient client, long now) {
        SqliteResearchStore store = SqliteResearchStore.get(c);
        Scan.Config cfg = Store.config(c);
        FibEngine.Options o = Store.fibOptions(c);
        Set<String> seen = Store.fibSeen(c);
        List<Row> past = new ArrayList<>();
        try { past = store.all(); } catch (Throwable ignored) { }
        Set<String> blocked = Store.learnBlock(c) ? Learning.blocked(past) : new java.util.HashSet<String>();
        List<Item> items = new ArrayList<>();
        List<Item> prevItems = load(c);
        int sent = 0;
        for (Scan.Market m : cfg.markets) {
            try {
                Candle[] h4 = closed(client, m.symbol, 14400, 300, now);
                Candle[] m15 = closed(client, m.symbol, 900, 1500, now);
                Item it = new Item();
                it.symbol = m.symbol; it.label = m.label; it.at = now;
                DataCheck.Report r4 = DataCheck.check(h4, 14400, now), r15 = DataCheck.check(m15, 900, now);
                if (!r4.usable() || !r15.usable()) { it.dataNote = "Data check: " + (r4.usable() ? r15.note : r4.note); items.add(it); continue; }
                it.h4 = java.util.Arrays.copyOfRange(h4, Math.max(0, h4.length - 90), h4.length);
                it.m15 = java.util.Arrays.copyOfRange(m15, Math.max(0, m15.length - 220), m15.length);
                it.setups = keepExpired(c, FibEngine.analyse(m.symbol, m.label, h4, m15, now, o), prevItems, m.symbol, now);
                for (FibEngine.Setup s : it.setups) {
                    Row probe = Row.fromFib(s, o, now, FibEngine.VERSION, r15.status);
                    it.memory.add(s.actionable() ? Learning.memoryLine(past, "FIB", probe.scenario) : "");
                    if (!s.actionable()) continue;
                    Row old = store.get(s.id);
                    if (old == null) { probe.source = "live"; store.upsert(probe); }
                    else if (!old.state.equals(s.state) && old.outcome.equals("pending")) { old.state = s.state; store.upsert(old); }
                    // one alert when the order is ready, and one more when price reaches the entry level
                    String alertId = s.id + (s.state.equals(FibEngine.S_ENTRY) ? "|E" : "");
                    if (seen.contains(alertId)) continue;
                    seen.add(alertId);
                    if (blocked.contains(Learning.key(probe))) {
                        it.dataNote = (it.dataNote.isEmpty() ? "" : it.dataNote + " ") + "Learning: \"" + s.key + "\" lost money over " + Learning.MIN_TRADES + "+ trades, so no alert is sent.";
                        continue;
                    }
                    String title = "Fib " + m.label + ": " + s.side + (s.state.equals(FibEngine.S_ENTRY) ? " entry level reached" : " order ready");
                    String body = FibEngine.line(s, o);
                    Notifier.alertFib(c, title, body, s.explanation);
                    Store.addAlert(c, "fib", title, body, s.explanation);
                    sent++;
                }
                items.add(it);
            } catch (Exception e) { /* try this market again next scan */ }
        }
        if (!items.isEmpty()) save(c, items);
        Store.setFibSeen(c, seen);
        try { MieRunner.resolve(c, client, store, now); } catch (Throwable ignored) { }
        return sent;
    }

    /** Lab: replay the strategy over history for one market; returns a one-line result */
    public static String replay(Context c, DerivClient client, Scan.Market m, int mult, java.util.Map<String, Candle[]> cache) throws Exception {
        SqliteResearchStore store = SqliteResearchStore.get(c);
        Candle[] m15 = MieRunner.cachedHistory(cache, client, m.symbol, 900, mult);
        Candle[] h4 = MieRunner.cachedHistory(cache, client, m.symbol, 14400, mult);
        FibReplay.Report rep = FibReplay.run(m.symbol, m.label, m15, h4, 4, Store.fibOptions(c));
        store.upsertAll(rep.rows);
        return m.label + " (Fib): " + rep.windows + " windows, " + rep.rows.size() + " setups recorded.\n" + (rep.all == null ? "" : rep.all.line());
    }

    public static String formatAge(long at, long now) {
        long m = Math.max(0, (now - at) / 60);
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : String.format(Locale.US, "%.1f h ago", m / 60.0);
    }
}
