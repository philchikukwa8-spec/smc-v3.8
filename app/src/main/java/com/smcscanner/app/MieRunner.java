package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.SignalLog;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.mie.DataCheck;
import com.smcscanner.engine.mie.Discovery;
import com.smcscanner.engine.mie.Learning;
import com.smcscanner.engine.mie.Mie;
import com.smcscanner.engine.mie.Profile;
import com.smcscanner.engine.mie.Row;
import com.smcscanner.engine.mie.Sim;
import com.smcscanner.engine.mie.Versions;
import com.smcscanner.engine.mie.WalkForward;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Runs the Market Intelligence Engine on every market: analyse, announce qualified setups, record everything in research.db, resolve outcomes. */
public final class MieRunner {
    private MieRunner() {}

    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public String symbol, label, dataStatus = "", dataNote = "", behaviour = "";
        public Mie.Assessment a;
        public long at;
        /** the last candles of the 15m chart (for the picture on the card) */
        public Candle[] chart;
        /** how this situation has ended before (from the recorded rows) */
        public String memory = "";
        /** 0 = 1H cascade, 1 = 30m cascade, 2 = 15m cascade */
        public int tier;
        /** seconds per candle of `chart` */
        public long chartGran = 900;
    }

    private static final Object LOCK = new Object();

    public static ArrayList<Item> load(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "mie.bin");
            if (!f.exists()) return new ArrayList<>();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                @SuppressWarnings("unchecked") ArrayList<Item> l = (ArrayList<Item>) in.readObject();
                return l;
            } catch (Exception e) { return new ArrayList<>(); }
        }
    }

    private static void save(Context c, List<Item> items) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "mie.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(new ArrayList<>(items)); } catch (Exception ignored) { }
        }
    }

    public static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    /** loads the active strategy version into the engine */
    public static Versions.Version prepare(Context c) {
        SqliteResearchStore s = SqliteResearchStore.get(c);
        Versions.ensure(s);
        Versions.Version v = Versions.active(s);
        Versions.apply(v);
        Mie.requireAligned = Store.requireAligned(c);
        Mie.blockNearResistance = Store.blockNearResistance(c);
        Mie.refineOb = Store.refineOb(c);
        return v;
    }

    private static Candle[] closed(DerivClient client, String sym, long gran, int count, long now) throws Exception {
        return Smc.closedOnly(DataCheck.clean(client.get(sym, gran, count)), gran, now);
    }

    /** returns the number of notifications sent */
    public static int run(Context c, DerivClient client, long now) {
        Versions.Version ver = prepare(c);
        SqliteResearchStore store = SqliteResearchStore.get(c);
        Scan.Config cfg = Store.config(c);
        Set<String> seen = Store.mieSeen(c);
        List<Item> items = new ArrayList<>();
        List<SignalLog.Record> recs = null;
        Set<String> have = new HashSet<>();
        boolean recsChanged = false;
        int sent = 0;
        for (Scan.Market m : cfg.markets) {
            try {
                Mie.Inputs in = new Mie.Inputs();
                in.symbol = m.symbol; in.label = m.label; in.now = now;
                in.h4 = closed(client, m.symbol, 14400, 200, now);
                in.h1 = closed(client, m.symbol, 3600, 300, now);
                in.m30 = closed(client, m.symbol, 1800, 300, now);
                in.m15 = closed(client, m.symbol, 900, 300, now);
                try { in.m5 = closed(client, m.symbol, 300, 300, now); } catch (Exception e) { in.m5 = null; }
                try { in.m1 = closed(client, m.symbol, 60, 300, now); } catch (Exception e) { in.m1 = null; }
                DataCheck.Report r15 = DataCheck.check(client.get(m.symbol, 900, 5), 900, now);
                DataCheck.Report r1h = DataCheck.check(in.h1, 3600, now);
                String status = r15.status.equals(DataCheck.LIVE) && r1h.status.equals(DataCheck.LIVE) ? DataCheck.LIVE : !r15.usable() ? r15.status : !r1h.usable() ? r1h.status : DataCheck.PROVISIONAL;
                if (!r15.usable() || !r1h.usable()) { in.stale = true; in.dataNote = !r15.usable() ? r15.note : r1h.note; }
                for (int tier = 0; tier <= (Store.lowerFrames(c) ? 2 : 0); tier++) {
                Mie.Assessment a = Mie.analyseTier(in, tier);
                Item it = new Item();
                Candle[] cs = tier == 0 ? in.m15 : tier == 1 ? in.m5 : in.m1;
                it.tier = tier; it.chartGran = tier == 0 ? 900 : tier == 1 ? 300 : 60;
                if (cs != null && cs.length > 5) it.chart = java.util.Arrays.copyOfRange(cs, Math.max(0, cs.length - 90), cs.length);
                it.symbol = m.symbol; it.label = m.label; it.a = a; it.at = now; it.dataStatus = status; it.dataNote = in.dataNote;
                // behaviour: how the latest 15m window compares with the discovered behaviour groups, when a model exists
                if (tier == 0) try {
                    String js = store.get("pattern", m.symbol + "|15M");
                    if (js != null) it.behaviour = Discovery.match(Discovery.fromJson(js), in.m15).describe();
                } catch (Exception ignored) { }
                items.add(it);

                boolean record = !Mie.noRecord(a.id);
                if (record) {
                    Row old = store.get(a.id);
                    if (old == null || (!old.actionable() && a.actionable())) {
                        Row row = Row.from(a, ver.id, status);
                        row.source = "live";
                        if (old != null) row.ts = old.ts;
                        store.upsert(row);
                    }
                }
                if (a.setup != null || a.actionable()) {
                    try {
                        List<Row> past = store.all();
                        Row probe = Row.from(a, ver.id, status);
                        it.memory = Learning.memoryLine(past, "SMC", probe.scenario);
                        if (a.actionable() && Store.learnBlock(c) && Learning.blocked(past).contains(Learning.key(probe))) {
                            a.reasons.add("LEARNED_BLOCK");
                            it.dataNote = (it.dataNote.isEmpty() ? "" : it.dataNote + " ") + "Learning: this situation lost money over " + Learning.MIN_TRADES + "+ trades, so no alert is sent.";
                            seen.add(a.id);
                        }
                    } catch (Throwable ignored) { }
                }
                if (a.actionable() && !seen.contains(a.id)) {
                    String title = m.label + ": " + a.signal + " (" + a.rrBand + ")";
                    String body = "Entry " + Fmt.f(a.entry) + " | stop " + Fmt.f(a.stop) + " | target " + Fmt.f(a.target) + " | " + String.format(Locale.US, "%.1fR", a.rr) + " | " + a.execTf;
                    Notifier.alert(c, title, body, a.explanation, true);
                    Store.addAlert(c, "mie", title, body, a.explanation);
                    seen.add(a.id);
                    sent++;
                    if (a.setup != null) {
                        if (recs == null) { recs = Store.loadSignals(c); for (SignalLog.Record rr : recs) have.add(rr.id); }
                        if (!have.contains(a.id)) {
                            String ex = a.execTf.equalsIgnoreCase("1M") ? "1m" : "5m";
                            SignalLog.Record rec = SignalLog.create(a.id, now, m.symbol, m.label, (tier == 0 ? "4H > 1H > 30m > 15m > " : tier == 1 ? "1H > 30m > 15m > 5m > " : "30m > 15m > 5m > 1m > ") + ex, tier == 0 ? "4H" : tier == 1 ? "1H" : "30m", ex, a.setup, null, false, -1, Store.riskMoney(c));
                            rec.grade = a.grade; rec.entryStyle = "MIE"; rec.qualityMet = a.groupsMet; rec.qualityOf = a.groupsMax;
                            recs.add(rec);
                            recsChanged = true;
                        }
                    }
                }
                }
            } catch (Exception e) { /* try this market again next scan */ }
        }
        if (recsChanged) Store.saveSignals(c, recs);
        if (!items.isEmpty()) save(c, items);
        Store.setMieSeen(c, seen);
        try { resolve(c, client, store, now); } catch (Throwable ignored) { }
        return sent;
    }

    /** works out win/loss for live qualified setups from the 5m candles after they were announced */
    static void resolve(Context c, DerivClient client, SqliteResearchStore store, long now) throws Exception {
        for (Row r : store.live()) {
            if (!r.actionable() || !(r.outcome.equals("pending") || r.outcome.equals("open"))) continue;
            // lower-cascade SMC trades have tight stops: follow them on 1m candles, everything else on 5m
            long g = r.mode.equals("SMC") && !r.frame.equals("1H") ? 60 : 300;
            int count = (int) Math.min(5000, Math.max(10, (now - r.ts) / g + 8));
            Candle[] cs = Smc.closedOnly(DataCheck.clean(client.get(r.symbol, g, count)), g, now);
            int from = -1;
            for (int i = 0; i < cs.length; i++) if (cs[i].epoch + g <= r.ts) from = i; else break;
            if (from < 0) continue;
            int wait = !r.mode.equals("SMC") ? (int) (86400 / g) : g == 60 ? 240 : 72, hold = !r.mode.equals("SMC") ? (int) (345600 / g) : g == 60 ? 1440 : 576;
            Sim.Out o = Sim.run(cs, from, "BUY".equals(r.side), r.entry, r.stop, r.target, wait, hold);
            boolean waiting = o.outcome.equals("no_fill") && now - r.ts < wait * g;
            if (waiting) continue;
            r.outcome = o.outcome; r.resultR = o.resultR; r.mfe = o.mfe; r.mae = o.mae; r.filled = o.filled;
            if (o.exitEpoch > 0) { r.resolvedTs = o.exitEpoch; r.holdSec = o.exitEpoch - r.ts; }
            store.upsert(r);
        }
    }

    // ------------------------------------------------------------------ research jobs (run from the Research screen)

    /** long history in pages (oldest first) */
    public static Candle[] history(DerivClient client, String sym, long gran, int pages) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        Candle[] all = Smc.closedOnly(DataCheck.clean(client.get(sym, gran, 5000)), gran, now);
        for (int p = 1; p < pages && all.length > 0; p++) {
            Candle[] older = client.candlesBefore(sym, gran, all[0].epoch - 1, 5000);
            if (older.length == 0) break;
            Candle[] merged = new Candle[older.length + all.length];
            System.arraycopy(older, 0, merged, 0, older.length);
            System.arraycopy(all, 0, merged, older.length, all.length);
            all = DataCheck.clean(merged);
        }
        return all;
    }

    /** replays the engine over real Deriv history (no look-ahead) and returns the report, labelled in-sample / validation / out-of-sample */
    public static WalkForward.Report walkForward(Context c, DerivClient client, Scan.Market m, int step) throws Exception { return walkForward(c, client, m, step, 0); }

    /** tier 0 = 1H cascade, tier 1 = 30m cascade */
    public static WalkForward.Report walkForward(Context c, DerivClient client, Scan.Market m, int step, int tier) throws Exception {
        return walkForward(c, client, m, step, tier, 1, null);
    }

    /** `mult` multiplies how much history is fetched (each unit is about 5000 candles per chart); `cache` shares downloads between steps */
    public static WalkForward.Report walkForward(Context c, DerivClient client, Scan.Market m, int step, int tier, int mult, java.util.Map<String, Candle[]> cache) throws Exception {
        Versions.Version ver = prepare(c);
        WalkForward.Data d = new WalkForward.Data();
        d.symbol = m.symbol; d.label = m.label;
        d.m15 = cachedHistory(cache, client, m.symbol, 900, mult);
        d.m30 = cachedHistory(cache, client, m.symbol, 1800, mult);
        d.h1 = cachedHistory(cache, client, m.symbol, 3600, mult);
        d.h4 = cachedHistory(cache, client, m.symbol, 14400, mult);
        d.m5 = cachedHistory(cache, client, m.symbol, 300, 3 * mult);
        d.m1 = null; // 1m history is too short to replay; live scans do use it
        WalkForward.Report rep = WalkForward.run(d, ver.id, step, 400, tier);
        SqliteResearchStore store = SqliteResearchStore.get(c);
        for (Row r : rep.rows) { r.id = "WF|" + r.id; r.source = "replay"; }
        store.upsertAll(rep.rows);
        return rep;
    }

    public static Candle[] cachedHistory(java.util.Map<String, Candle[]> cache, DerivClient client, String sym, long gran, int pages) throws Exception {
        if (cache == null) return history(client, sym, gran, pages);
        String k = sym + "|" + gran + "|" + pages;
        Candle[] v = cache.get(k);
        if (v == null) { v = history(client, sym, gran, pages); cache.put(k, v); }
        return v;
    }

    public static String profileText(DerivClient client, Scan.Market m) throws Exception {
        Candle[] h1 = history(client, m.symbol, 3600, 1);
        return Profile.build(m.symbol, "1H", h1).describe();
    }

    /** discovers recurring behaviour groups on 15m history, stores the definition, and returns a readable summary */
    public static String discover(Context c, DerivClient client, Scan.Market m) throws Exception {
        Candle[] m15 = history(client, m.symbol, 900, 1);
        Discovery.Model model = Discovery.discover(m.symbol, "15M", m15, 0.6, today());
        SqliteResearchStore.get(c).put("pattern", m.symbol + "|15M", Discovery.toJson(model));
        StringBuilder sb = new StringBuilder(m.label + " (15m, " + m15.length + " candles)\n");
        int validated = 0;
        for (Discovery.Cluster cl : model.clusters) {
            if (cl.status.equals("validated")) validated++;
            sb.append(String.format(Locale.US, "Group %d: %s%s - %d earlier occurrences, %d later. %s\n", cl.id, cl.status.toUpperCase(Locale.US), cl.tendency.equals("NONE") ? "" : " (" + cl.tendency.toLowerCase(Locale.US) + ")", cl.trainN, cl.valN, cl.note));
        }
        sb.append(validated == 0 ? "\nNo group held up on later data: nothing is used by the decision engine.\n" : "\n" + validated + " group(s) held up on later data. They are shown as context only.\n");
        List<String> drift = Discovery.drift(model, m15, 0.25);
        for (String dl : drift) sb.append("DRIFT: ").append(dl).append("\n");
        return sb.toString();
    }
}
