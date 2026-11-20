package com.smcscanner.chart;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.mie.Trendlines;
import com.smcscanner.engine.mie.Vol;

import java.util.ArrayList;
import java.util.List;

/**
 * Smart-money layers for the live chart, calculated from the closed candles being shown: break of structure (BOS), change of
 * character (CHoCH), order blocks, fair value gaps, liquidity pools and trendlines. Pure Java (no Android).
 * Everything is limited to the few most relevant items so the chart stays readable.
 */
public final class StructureLayers {
    private StructureLayers() {}

    public static final class Flags {
        public boolean bos = true, ob = true, fvg = true, liq = true, trend = true;
    }

    private static final int GREEN = 0xFF26A69A, RED = 0xFFEF5350, ORANGE = 0xFFFFA726, BLUE = 0xFF42A5F5, GREY = 0xFF90A4AE;
    private static int alpha(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }

    private static final class Event {
        boolean bull, choch;
        int fromIdx, atIdx;
        double level;
    }

    /** adds the layers into `o`. `closed` are closed candles, oldest first (at least 40). */
    public static void add(Overlay o, Candle[] c, Flags f) {
        if (c == null || c.length < 40) return;
        int n = c.length;
        double atr = Vol.atr(c, 14);
        if (atr <= 0) return;
        List<Event> ev = events(c);
        List<Event> recent = new ArrayList<>();
        for (Event e : ev) if (e.atIdx >= n - 90) recent.add(e);
        while (recent.size() > 5) recent.remove(0);

        if (f.bos) {
            for (Event e : recent) {
                o.segs.add(new Overlay.Seg(c[e.fromIdx].epoch, e.level, c[e.atIdx].epoch, e.level, e.choch ? "CHoCH" : "BOS",
                        e.choch ? ORANGE : (e.bull ? GREEN : RED), e.choch));
            }
        }
        if (f.ob) {
            int shown = 0;
            for (int k = recent.size() - 1; k >= 0 && shown < 3; k--) {
                Event e = recent.get(k);
                int m = e.fromIdx;
                for (int i = e.fromIdx; i <= e.atIdx; i++) if (e.bull ? c[i].low < c[m].low : c[i].high > c[m].high) m = i;
                int ob = -1;
                for (int i = Math.min(n - 1, m + 1); i >= Math.max(0, m - 3); i--) {
                    if (e.bull ? c[i].close < c[i].open : c[i].close > c[i].open) { ob = i; break; }
                }
                if (ob < 0) continue;
                boolean mitigated = false;
                for (int i = e.atIdx; i < n; i++) if (e.bull ? c[i].close < c[ob].low : c[i].close > c[ob].high) { mitigated = true; break; }
                if (mitigated) continue;
                o.zones.add(new Overlay.Zone(c[ob].low, c[ob].high, c[ob].epoch, e.bull ? "Bull OB" : "Bear OB", alpha(e.bull ? GREEN : RED, 60)));
                shown++;
            }
        }
        if (f.fvg) {
            List<double[]> gaps = new ArrayList<>(); // low, high, epoch, bull(1/0)
            for (int i = Math.max(2, n - 140); i < n; i++) {
                if (c[i].low > c[i - 2].high && c[i].low - c[i - 2].high >= 0.1 * atr) {
                    boolean filled = false;
                    for (int k = i + 1; k < n; k++) if (c[k].low <= c[i - 2].high) { filled = true; break; }
                    if (!filled) gaps.add(new double[]{c[i - 2].high, c[i].low, c[i - 1].epoch, 1});
                }
                if (c[i].high < c[i - 2].low && c[i - 2].low - c[i].high >= 0.1 * atr) {
                    boolean filled = false;
                    for (int k = i + 1; k < n; k++) if (c[k].high >= c[i - 2].low) { filled = true; break; }
                    if (!filled) gaps.add(new double[]{c[i].high, c[i - 2].low, c[i - 1].epoch, 0});
                }
            }
            while (gaps.size() > 4) gaps.remove(0);
            for (double[] g : gaps) o.zones.add(new Overlay.Zone(g[0], g[1], (long) g[2], g[3] == 1 ? "Bull FVG" : "Bear FVG", alpha(g[3] == 1 ? BLUE : ORANGE, 55)));
        }
        if (f.liq) liquidity(o, c, atr);
        if (f.trend) {
            for (Trendlines.Line l : Trendlines.find(c, 150)) {
                if (l.broken) continue;
                o.segs.add(new Overlay.Seg(c[l.i1].epoch, l.p1, c[n - 1].epoch, l.at(n - 1), l.role.equals("support") ? "Trend support" : "Trend resistance",
                        alpha(GREY, 230), false));
            }
        }
    }

    private static List<Event> events(Candle[] c) {
        int n = c.length;
        List<Smc.Swing> hi = Smc.swingHighs(c, 2, 0, n - 1), lo = Smc.swingLows(c, 2, 0, n - 1);
        List<Event> out = new ArrayList<>();
        int trend = 0, ih = 0, il = 0, hiIdx = -1, loIdx = -1;
        double hiLvl = 0, loLvl = 0;
        boolean hiBroken = true, loBroken = true;
        for (int i = 0; i < n; i++) {
            while (ih < hi.size() && hi.get(ih).idx + 2 <= i) { hiLvl = hi.get(ih).level; hiIdx = hi.get(ih).idx; hiBroken = false; ih++; }
            while (il < lo.size() && lo.get(il).idx + 2 <= i) { loLvl = lo.get(il).level; loIdx = lo.get(il).idx; loBroken = false; il++; }
            if (!hiBroken && c[i].close > hiLvl) {
                Event e = new Event(); e.bull = true; e.choch = trend == -1; e.fromIdx = hiIdx; e.atIdx = i; e.level = hiLvl;
                out.add(e); trend = 1; hiBroken = true;
            } else if (!loBroken && c[i].close < loLvl) {
                Event e = new Event(); e.bull = false; e.choch = trend == 1; e.fromIdx = loIdx; e.atIdx = i; e.level = loLvl;
                out.add(e); trend = -1; loBroken = true;
            }
        }
        return out;
    }

    private static void liquidity(Overlay o, Candle[] c, double atr) {
        int n = c.length;
        double price = c[n - 1].close;
        List<Smc.Swing> hi = Smc.swingHighs(c, 2, Math.max(0, n - 160), n - 1), lo = Smc.swingLows(c, 2, Math.max(0, n - 160), n - 1);
        List<double[]> up = new ArrayList<>(), dn = new ArrayList<>(); // level, count, epoch
        for (Smc.Swing s : hi) {
            if (s.level <= price) continue;
            boolean swept = false;
            for (int k = s.idx + 1; k < n; k++) if (c[k].high > s.level) { swept = true; break; }
            if (!swept) cluster(up, s.level, c[s.idx].epoch, 0.15 * atr);
        }
        for (Smc.Swing s : lo) {
            if (s.level >= price) continue;
            boolean swept = false;
            for (int k = s.idx + 1; k < n; k++) if (c[k].low < s.level) { swept = true; break; }
            if (!swept) cluster(dn, s.level, c[s.idx].epoch, 0.15 * atr);
        }
        pick(o, up, price, true);
        pick(o, dn, price, false);
    }

    private static void cluster(List<double[]> list, double level, long epoch, double tol) {
        for (double[] g : list) if (Math.abs(g[0] - level) <= tol) { g[1]++; g[0] = Math.max(g[0], level); return; }
        list.add(new double[]{level, 1, epoch});
    }

    private static void pick(Overlay o, List<double[]> list, double price, boolean above) {
        java.util.Collections.sort(list, (a, b) -> Double.compare(Math.abs(a[0] - price), Math.abs(b[0] - price)));
        int shown = 0;
        for (double[] g : list) {
            if (shown >= 2) break;
            String lab = g[1] >= 2 ? (above ? "EQH liquidity" : "EQL liquidity") : (above ? "Buy-side liquidity" : "Sell-side liquidity");
            o.levels.add(new Overlay.Level(g[0], lab, alpha(above ? RED : GREEN, 200), true, (long) g[2]));
            shown++;
        }
    }
}
