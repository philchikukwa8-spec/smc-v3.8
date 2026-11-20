package com.smcscanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.smcscanner.engine.Calibration;
import com.smcscanner.engine.Lifecycle;
import com.smcscanner.engine.Manage;
import com.smcscanner.engine.SignalLog;
import com.smcscanner.engine.Scan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Everything the app remembers: settings, tracked ideas, alert history. */
public final class Store {
    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("smc", Context.MODE_PRIVATE);
    }

    // ---------- settings ----------
    public static boolean isMarketOn(Context c, String symbol) { return prefs(c).getBoolean("m_" + symbol, true); }
    public static void setMarketOn(Context c, String symbol, boolean on) { prefs(c).edit().putBoolean("m_" + symbol, on).apply(); }
    public static int minQuality(Context c) { return prefs(c).getInt("minQuality", 0); }
    public static void setMinQuality(Context c, int v) { prefs(c).edit().putInt("minQuality", v).apply(); }
    public static int minScore(Context c) { return prefs(c).getInt("minScore", 4); }
    public static void setMinScore(Context c, int v) { prefs(c).edit().putInt("minScore", v).apply(); }
    public static int intervalSec(Context c) { return prefs(c).getInt("interval", 60); }
    public static void setIntervalSec(Context c, int v) { prefs(c).edit().putInt("interval", v).apply(); }
    /** the user wants scanning on (also used to restart after a reboot) */
    public static boolean scanningWanted(Context c) { return prefs(c).getBoolean("wanted", false); }
    public static void setScanningWanted(Context c, boolean v) { prefs(c).edit().putBoolean("wanted", v).apply(); }
    public static String lastStatus(Context c) { return prefs(c).getString("status", "Not scanned yet"); }
    public static void setLastStatus(Context c, String s) { prefs(c).edit().putString("status", s).apply(); }

    /** -1 = best found by calibration, 0 edge, 1 middle, 2 deep */
    public static int entryMode(Context c) { return prefs(c).getInt("entryMode", -1); }
    public static void setEntryMode(Context c, int v) { prefs(c).edit().putInt("entryMode", v).apply(); }
    /** money risked per trade, for the money lines */
    /** money risked per trade: worked out from your account size when one is set, otherwise the fixed amount */
    public static int riskMoney(Context c) {
        int fromAccount = com.smcscanner.engine.Journal.riskFromAccount(accountSize(c), riskPercent(c));
        return fromAccount > 0 ? fromAccount : prefs(c).getInt("riskMoney", 10);
    }
    public static void setRiskMoney(Context c, int v) { prefs(c).edit().putInt("riskMoney", v).apply(); }

    /** minutes to wait for a precise (zoomed-in) entry before using the first entry zone; 0 = do not wait */
    public static int refineWaitMin(Context c) { return prefs(c).getInt("refineWait", 30); }
    public static void setRefineWaitMin(Context c, int v) { prefs(c).edit().putInt("refineWait", v).apply(); }

    /** alerts for major chart patterns (double top/bottom, head and shoulders, triangles, wedges) */
    public static java.util.Set<String> advisorSeen(Context c) { return new java.util.HashSet<>(prefs(c).getStringSet("advSeen", new java.util.HashSet<String>())); }
    public static void setAdvisorSeen(Context c, java.util.Set<String> s) {
        java.util.List<String> l = new java.util.ArrayList<>(s);
        if (l.size() > 300) l = l.subList(l.size() - 300, l.size());
        prefs(c).edit().putStringSet("advSeen", new java.util.HashSet<>(l)).apply();
    }
    /** also send a heads-up when price reaches a zone and the 15m confirmation is still to come */
    public static boolean advisorHeadsUp(Context c) { return prefs(c).getBoolean("advHeadsUp", true); }
    public static void setAdvisorHeadsUp(Context c, boolean v) { prefs(c).edit().putBoolean("advHeadsUp", v).apply(); }
    public static java.util.Set<String> mieSeen(Context c) { return new java.util.HashSet<>(prefs(c).getStringSet("mieSeen", new java.util.HashSet<String>())); }
    public static void setMieSeen(Context c, java.util.Set<String> s) {
        java.util.List<String> l = new java.util.ArrayList<>(s);
        if (l.size() > 300) l = l.subList(l.size() - 300, l.size());
        prefs(c).edit().putStringSet("mieSeen", new java.util.HashSet<>(l)).apply();
    }
    /** the older 1H > 30m > 15m Advisor, kept in the code but off by default now that the Market Intelligence Engine replaces it */
    public static boolean legacyAdvisor(Context c) { return prefs(c).getBoolean("legacyAdvisor", false); }
    public static boolean patternAlerts(Context c) { return prefs(c).getBoolean("patternAlerts", true); }
    public static void setPatternAlerts(Context c, boolean v) { prefs(c).edit().putBoolean("patternAlerts", v).apply(); }
    public static java.util.Set<String> patternSeen(Context c) { return new java.util.HashSet<>(prefs(c).getStringSet("patSeen", new java.util.HashSet<String>())); }
    public static void setPatternSeen(Context c, java.util.Set<String> s) {
        java.util.List<String> l = new java.util.ArrayList<>(s);
        if (l.size() > 400) l = l.subList(l.size() - 400, l.size());
        prefs(c).edit().putStringSet("patSeen", new java.util.HashSet<>(l)).apply();
    }
    public static long patternLast(Context c) { return prefs(c).getLong("patLast", 0); }
    public static void setPatternLast(Context c, long t) { prefs(c).edit().putLong("patLast", t).apply(); }

    public static boolean dark(Context c) { return prefs(c).getBoolean("dark", true); }
    public static void setDark(Context c, boolean v) { prefs(c).edit().putBoolean("dark", v).apply(); }

    public static int accountSize(Context c) { return prefs(c).getInt("account", 0); }
    public static void setAccountSize(Context c, int v) { prefs(c).edit().putInt("account", v).apply(); }
    /** risk per trade as a percentage of the account, stored in tenths of a percent (10 = 1%) */
    public static double riskPercent(Context c) { return prefs(c).getInt("riskPctX10", 10) / 10.0; }
    public static void setRiskPercentX10(Context c, int v) { prefs(c).edit().putInt("riskPctX10", v).apply(); }

    /** stop new-setup alerts after this many losing trades today (0 = never) */
    public static int maxLosses(Context c) { return prefs(c).getInt("maxLosses", 0); }
    public static void setMaxLosses(Context c, int v) { prefs(c).edit().putInt("maxLosses", v).apply(); }
    public static int quietFrom(Context c) { return prefs(c).getInt("quietFrom", 0); }
    public static int quietTo(Context c) { return prefs(c).getInt("quietTo", 0); }
    public static void setQuiet(Context c, int from, int to) { prefs(c).edit().putInt("quietFrom", from).putInt("quietTo", to).apply(); }

    /** why new-setup alerts are being held back right now, or null when they are on */
    public static String pauseReason(Context c) {
        if (snoozed(c)) return "Snoozed until " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(snoozeUntil(c)));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (com.smcscanner.engine.Journal.inQuietHours(cal.get(java.util.Calendar.HOUR_OF_DAY), quietFrom(c), quietTo(c))) return "Quiet hours";
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0);
        int max = maxLosses(c);
        if (max > 0 && com.smcscanner.engine.Journal.limitReached(loadTrades(c), cal.getTimeInMillis() / 1000, max)) return "Daily loss limit reached (" + max + (max == 1 ? " loss)" : " losses)");
        return null;
    }

    public static long snoozeUntil(Context c) { return prefs(c).getLong("snoozeUntil", 0); }
    public static void setSnoozeUntil(Context c, long ms) { prefs(c).edit().putLong("snoozeUntil", ms).apply(); }
    public static boolean snoozed(Context c) { return System.currentTimeMillis() < snoozeUntil(c); }

    public static boolean sniper(Context c) { return prefs(c).getBoolean("sniper", true); }
    public static void setSniper(Context c, boolean v) { prefs(c).edit().putBoolean("sniper", v).apply(); }

    /** also require the top timeframe (1D for a 1H idea) to trend the same way */
    public static boolean requireTop(Context c) { return prefs(c).getBoolean("requireTop", false); }
    public static void setRequireTop(Context c, boolean v) { prefs(c).edit().putBoolean("requireTop", v).apply(); }
    /** live-chart layer switches (setup, bos, ob, fvg, liq, trend, sr, pat, ideas); all on by default */
    /** the explaining text on the Chart tab (setup names and info box). Off by default so the entry is easy to see. */
    public static boolean chartNotes(Context c) { return prefs(c).getBoolean("chartNotes", false); }
    public static void setChartNotes(Context c, boolean v) { prefs(c).edit().putBoolean("chartNotes", v).apply(); }

    // ---- setups you logged or removed: a logged setup never shows as "expired"; a removed one is gone for good
    private static final String FLAGS = "setupFlags";
    public static boolean setupFlag(Context c, char kind, String id) { return prefs(c).getStringSet(FLAGS, new java.util.HashSet<String>()).contains(kind + "|" + id); }
    public static void setSetupFlag(Context c, char kind, String id) {
        java.util.Set<String> l = new java.util.HashSet<>(prefs(c).getStringSet(FLAGS, new java.util.HashSet<String>()));
        if (l.size() > 800) l.clear();
        l.add(kind + "|" + id);
        prefs(c).edit().putStringSet(FLAGS, l).apply();
    }

    public static boolean chartLayer(Context c, String k) { return prefs(c).getBoolean("layer_" + k, true); }
    public static void setChartLayer(Context c, String k, boolean v) { prefs(c).edit().putBoolean("layer_" + k, v).apply(); }
    /** which engines run: SMC (Advisor tab) and price action (Bird's Eye tab); either, or both */
    public static boolean modeSmc(Context c) { return prefs(c).getBoolean("modeSmc", false); }
    public static void setModeSmc(Context c, boolean v) { prefs(c).edit().putBoolean("modeSmc", v).apply(); }
    public static boolean modePa(Context c) { return prefs(c).getBoolean("modePa", true); }
    public static void setModePa(Context c, boolean v) { prefs(c).edit().putBoolean("modePa", v).apply(); }
    /** also look for opportunities on the 30m and 15m (SMC cascades from the 30m and 15m, and price-action scans on both) */
    public static boolean lowerFrames(Context c) { return prefs(c).getBoolean("lowerFrames", true); }
    public static void setLowerFrames(Context c, boolean v) { prefs(c).edit().putBoolean("lowerFrames", v).apply(); }
    /** learning: stop alerting for a situation that lost money over 30+ trades (live, validation or out-of-sample) */
    public static boolean learnBlock(Context c) { return prefs(c).getBoolean("learnBlock", true); }
    public static void setLearnBlock(Context c, boolean v) { prefs(c).edit().putBoolean("learnBlock", v).apply(); }
    public static java.util.Set<String> paSeen(Context c) { return new java.util.HashSet<>(prefs(c).getStringSet("paSeen", new java.util.HashSet<String>())); }
    public static void setPaSeen(Context c, java.util.Set<String> s) {
        java.util.List<String> l = new java.util.ArrayList<>(s);
        if (l.size() > 300) l = l.subList(l.size() - 300, l.size());
        prefs(c).edit().putStringSet("paSeen", new java.util.HashSet<>(l)).apply();
    }
    /**
     * price-action alerts already sent: "symbol|side|entry|time|risk" lines, so one idea does not alert again from another
     * chart, another family of the same idea, or a changed id. Same market and side counts as the same idea when the entries
     * are within 1.5 risks (or 0.6%), for 12 hours; and a hard cool-down of 3 hours per market and side stops bursts.
     */
    public static boolean paRecentlyAlerted(Context c, String symbol, String side, double entry, double risk, long now) {
        for (String l : prefs(c).getString("paAlerted", "").split("\n")) {
            String[] p = l.split("\\|");
            if (p.length < 4 || !p[0].equals(symbol) || !p[1].equals(side)) continue;
            try {
                double e = Double.parseDouble(p[2]); long t = Long.parseLong(p[3]);
                double r = p.length > 4 ? Double.parseDouble(p[4]) : 0;
                if (now - t < 3 * 3600) return true;
                double tol = Math.max(0.006 * e, 1.5 * Math.max(risk, r));
                if (now - t < 12 * 3600 && e > 0 && Math.abs(e - entry) <= tol) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }
    public static void paMarkAlerted(Context c, String symbol, String side, double entry, double risk, long now) {
        StringBuilder sb = new StringBuilder(symbol + "|" + side + "|" + entry + "|" + now + "|" + risk);
        int n = 1;
        for (String l : prefs(c).getString("paAlerted", "").split("\n")) {
            String[] p = l.split("\\|");
            if (p.length < 4) continue;
            try { if (now - Long.parseLong(p[3]) > 24 * 3600) continue; } catch (NumberFormatException e) { continue; }
            if (n++ > 60) break;
            sb.append('\n').append(l);
        }
        prefs(c).edit().putString("paAlerted", sb.toString()).apply();
    }

    // ---------- Fib pullback strategy ----------
    public static boolean modeFib(Context c) { return prefs(c).getBoolean("modeFib", true); }
    public static void setModeFib(Context c, boolean v) { prefs(c).edit().putBoolean("modeFib", v).apply(); }
    /** 50 (default) or 30: where the order goes on the M15 fib */
    public static int fibEntry(Context c) { return prefs(c).getInt("fibEntry", 50); }
    public static void setFibEntry(Context c, int v) { prefs(c).edit().putInt("fibEntry", v).apply(); }
    public static boolean fibTargetH4(Context c) { return prefs(c).getBoolean("fibTargetH4", false); }
    public static void setFibTargetH4(Context c, boolean v) { prefs(c).edit().putBoolean("fibTargetH4", v).apply(); }
    public static boolean fibBaseLow(Context c) { return prefs(c).getBoolean("fibBaseLow", false); }
    public static void setFibBaseLow(Context c, boolean v) { prefs(c).edit().putBoolean("fibBaseLow", v).apply(); }
    public static boolean fibRequireD1(Context c) { return prefs(c).getBoolean("fibRequireD1", false); }
    public static void setFibRequireD1(Context c, boolean v) { prefs(c).edit().putBoolean("fibRequireD1", v).apply(); }
    public static com.smcscanner.engine.fib.FibEngine.Options fibOptions(Context c) {
        com.smcscanner.engine.fib.FibEngine.Options o = new com.smcscanner.engine.fib.FibEngine.Options();
        o.entryFrac = fibEntry(c) / 100.0; o.targetH4 = fibTargetH4(c); o.baseLow = fibBaseLow(c);
        return o;
    }
    /** the alert sound for Fib setups: a sound uri as text, or "" for the phone's default notification sound */
    public static String fibSound(Context c) { return prefs(c).getString("fibSound", ""); }
    public static String fibSoundName(Context c) { return prefs(c).getString("fibSoundName", "Phone default"); }
    public static void setFibSound(Context c, String uri, String name) { prefs(c).edit().putString("fibSound", uri).putString("fibSoundName", name).apply(); }
    public static java.util.Set<String> fibSeen(Context c) { return new java.util.HashSet<>(prefs(c).getStringSet("fibSeen", new java.util.HashSet<String>())); }
    public static void setFibSeen(Context c, java.util.Set<String> s) {
        java.util.List<String> l = new java.util.ArrayList<>(s);
        if (l.size() > 300) l = l.subList(l.size() - 300, l.size());
        prefs(c).edit().putStringSet("fibSeen", new java.util.HashSet<>(l)).apply();
    }
    /** SMC entry refinement: order block inside order block */
    public static boolean refineOb(Context c) { return prefs(c).getBoolean("mieRefineOb", true); }
    public static void setRefineOb(Context c, boolean v) { prefs(c).edit().putBoolean("mieRefineOb", v).apply(); }
    /** Advisor rule: hold back only when 1H and 4H both clearly point the other way */
    public static boolean requireAligned(Context c) { return prefs(c).getBoolean("mieAligned", true); }
    public static void setRequireAligned(Context c, boolean v) { prefs(c).edit().putBoolean("mieAligned", v).apply(); }
    /** Advisor rule: opposing structure under 1R from entry keeps the setup on watch */
    public static boolean blockNearResistance(Context c) { return prefs(c).getBoolean("mieNearBlock", true); }
    public static void setBlockNearResistance(Context c, boolean v) { prefs(c).edit().putBoolean("mieNearBlock", v).apply(); }
    /** skip a trade when a confirmed chart pattern on the idea chart points the other way */
    public static boolean requirePatternNotAgainst(Context c) { return prefs(c).getBoolean("patNotAgainst", true); }
    public static void setRequirePatternNotAgainst(Context c, boolean v) { prefs(c).edit().putBoolean("patNotAgainst", v).apply(); }
    /** only alert when the entry sits on a key support/resistance area, or a chart pattern agrees */
    public static boolean requireConfluence(Context c) { return prefs(c).getBoolean("requireConf", false); }
    public static void setRequireConfluence(Context c, boolean v) { prefs(c).edit().putBoolean("requireConf", v).apply(); }

    public static String chartSymbol(Context c) { return prefs(c).getString("chartSym", "R_75"); }
    public static void setChartSymbol(Context c, String v) { prefs(c).edit().putString("chartSym", v).apply(); }
    public static String chartTf(Context c) { return prefs(c).getString("chartTf", "5m"); }
    public static void setChartTf(Context c, String v) { prefs(c).edit().putString("chartTf", v).apply(); }

    public static Scan.Config config(Context c) {
        Scan.Config cfg = sniper(c) ? Scan.Config.sniper() : Scan.Config.defaults();
        List<Scan.Market> on = new ArrayList<>();
        for (Scan.Market m : cfg.markets) if (isMarketOn(c, m.symbol)) on.add(m);
        cfg.markets = on;
        if (!sniper(c)) { cfg.minScore = minScore(c); cfg.minQuality = minQuality(c); }
        if (requireTop(c) && !cfg.require.contains("!topTrend")) cfg.require.add("!topTrend");
        if (requirePatternNotAgainst(c) && !cfg.require.contains("!pattern")) cfg.require.add("!pattern");
        cfg.entryMode = entryMode(c);
        cfg.riskMoney = riskMoney(c);
        cfg.book = book(c);
        cfg.refineWaitSec = refineWaitMin(c) * 60L;
        return cfg;
    }

    // ---------- tracked ideas (saved as a file) ----------
    private static final Object LOCK = new Object();

    public static Lifecycle.State loadState(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "state.bin");
            if (!f.exists()) return new Lifecycle.State();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                return (Lifecycle.State) in.readObject();
            } catch (Exception e) {
                return new Lifecycle.State(); // unreadable (e.g. after an app update): start fresh
            }
        }
    }

    public static void saveState(Context c, Lifecycle.State s) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "state.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) {
                out.writeObject(s);
            } catch (Exception ignored) { }
        }
    }

    // ---------- calibration (past win rates) ----------
    public static Calibration.Book book(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "calib.bin");
            if (!f.exists()) return new Calibration.Book();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                return (Calibration.Book) in.readObject();
            } catch (Exception e) { return new Calibration.Book(); }
        }
    }

    public static void putCalibration(Context c, String key, Calibration cal) {
        synchronized (LOCK) {
            Calibration.Book b = book(c);
            b.items.put(key, cal);
            File f = new File(c.getApplicationContext().getFilesDir(), "calib.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(b); } catch (Exception ignored) { }
        }
    }

    // ---------- record of every entry alert (for the Excel journal) ----------
    public static ArrayList<SignalLog.Record> loadSignals(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "signals.bin");
            if (!f.exists()) return new ArrayList<>();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                @SuppressWarnings("unchecked") ArrayList<SignalLog.Record> l = (ArrayList<SignalLog.Record>) in.readObject();
                return l;
            } catch (Exception e) { return new ArrayList<>(); }
        }
    }

    public static void saveSignals(Context c, List<SignalLog.Record> records) {
        synchronized (LOCK) {
            List<SignalLog.Record> keep = records;
            if (keep.size() > 5000) keep = keep.subList(keep.size() - 5000, keep.size());
            File f = new File(c.getApplicationContext().getFilesDir(), "signals.bin");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(new ArrayList<>(keep)); } catch (Exception ignored) { }
        }
    }

    // ---------- trades you are managing ----------
    public static ArrayList<Manage.Trade> loadTrades(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getApplicationContext().getFilesDir(), "trades.bin");
            if (!f.exists()) return new ArrayList<>();
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                @SuppressWarnings("unchecked") ArrayList<Manage.Trade> l = (ArrayList<Manage.Trade>) in.readObject();
                return l;
            } catch (Exception e) { return new ArrayList<>(); }
        }
    }

    private static void writeTrades(Context c, ArrayList<Manage.Trade> l) {
        File f = new File(c.getApplicationContext().getFilesDir(), "trades.bin");
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) { out.writeObject(l); } catch (Exception ignored) { }
    }

    public static void addTrade(Context c, Manage.Trade t) {
        synchronized (LOCK) { ArrayList<Manage.Trade> l = loadTrades(c); l.add(0, t); writeTrades(c, l); }
    }

    public static void removeTrade(Context c, String id) {
        synchronized (LOCK) {
            ArrayList<Manage.Trade> l = loadTrades(c);
            for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(id)) { l.remove(i); break; }
            writeTrades(c, l);
        }
    }

    /** Saves updated trades, but keeps any trade the user removed or added while the update was running. */
    public static void mergeTrades(Context c, List<Manage.Trade> updated) {
        synchronized (LOCK) {
            ArrayList<Manage.Trade> now = loadTrades(c);
            for (int i = 0; i < now.size(); i++) {
                for (Manage.Trade u : updated) {
                    if (u.id.equals(now.get(i).id) && !now.get(i).status.equals("closed")) { now.set(i, u); break; }
                }
            }
            writeTrades(c, now);
        }
    }

    // ---------- alert history ----------
    public static final class Alert {
        public long time;
        public String kind, title, body, text;
    }

    public static void addAlert(Context c, String kind, String title, String body, String text) {
        synchronized (LOCK) {
            try {
                JSONArray old = new JSONArray(prefs(c).getString("alerts", "[]"));
                JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("t", System.currentTimeMillis());
                o.put("k", kind); o.put("title", title); o.put("body", body); o.put("text", text);
                arr.put(o);
                for (int i = 0; i < old.length() && arr.length() < 100; i++) arr.put(old.get(i));
                prefs(c).edit().putString("alerts", arr.toString()).apply();
            } catch (Exception ignored) { }
        }
    }

    public static List<Alert> alerts(Context c) {
        List<Alert> out = new ArrayList<>();
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray(prefs(c).getString("alerts", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Alert a = new Alert();
                    a.time = o.getLong("t"); a.kind = o.getString("k"); a.title = o.getString("title");
                    a.body = o.getString("body"); a.text = o.getString("text");
                    out.add(a);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static void clearAlerts(Context c) { prefs(c).edit().remove("alerts").apply(); }
}
