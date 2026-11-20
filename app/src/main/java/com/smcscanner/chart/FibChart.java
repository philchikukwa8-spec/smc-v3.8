package com.smcscanner.chart;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.fib.FibEngine;

import java.util.Arrays;
import java.util.Locale;

/** The Fib pullback picture: both fibs drawn automatically (H4 leg and M15 impulse), the three breaks of structure, the order and its targets. */
public final class FibChart {
    private FibChart() {}

    private static final int GREEN = 0xFF26A69A, RED = 0xFFEF5350, BLUE = 0xFF42A5F5, GOLD = 0xFFE3B341, GREY = 0xFF9AA4B2, PURPLE = 0xFFAB7DF6;

    private static int alpha(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }

    /** @param h4View true draws the H4 candles (the bigger leg), false the M15 candles (the entry) */
    public static ChartRenderer build(FibEngine.Setup s, Candle[] cs, boolean h4View, boolean dark, float density, int px) {
        ChartRenderer r = new ChartRenderer();
        r.setDark(dark);
        r.density = density;
        int keep = Math.min(cs.length, h4View ? 70 : 150);
        Candle[] c = Arrays.copyOfRange(cs, cs.length - keep, cs.length);
        long gran = h4View ? 14400 : 900;
        r.setCandles(c, (int) gran);
        r.nowSec = c[c.length - 1].epoch + gran;
        r.showOverlay = true;
        int rightPad = 18;
        double plotW = px - 66 * density;
        r.bw = Math.max(2.2 * density, plotW / (keep + rightPad));
        r.shift = rightPad;
        r.title = s.label + (h4View ? " H4 fib" : " M15 fib");
        Overlay o = new Overlay();
        boolean buy = s.isBuy();
        long first = c[0].epoch;
        long last = c[c.length - 1].epoch;
        double vlo = Double.POSITIVE_INFINITY, vhi = Double.NEGATIVE_INFINITY;
        for (Candle k : c) { vlo = Math.min(vlo, k.low); vhi = Math.max(vhi, k.high); }
        double span = Math.max(1e-9, vhi - vlo);
        // ---- H4 fib
        double z = s.h4Zero, h = s.h4Hundred, rg = h - z;
        double[] fr = {0, 0.3, 0.5, 0.618, 1};
        for (double f : fr) {
            double price = z + f * rg;
            String lab = "H4 " + (f == 0 ? "0%" : f == 1 ? "100%" : (int) Math.round(f * 1000) / 10.0 + "%");
            int col = f == 0 || f == 1 ? alpha(PURPLE, 230) : f == 0.5 ? alpha(PURPLE, 200) : alpha(GREY, 150);
            // on the M15 view a far-away H4 level would squash the candles: only draw the ones near the price
            if (!h4View && (price < vlo - 0.8 * span || price > vhi + 0.8 * span)) continue;
            if (!h4View && (f == 0.3 || f == 0.618)) continue;
            o.levels.add(new Overlay.Level(price, lab, col, f != 0 && f != 1, first));
        }
        double dl = Math.min(z, z + 0.5 * rg), dh = Math.max(z, z + 0.5 * rg);
        o.zones.add(new Overlay.Zone(dl, dh, first, buy ? "H4 discount (0 - 50%)" : "H4 premium (0 - 50%)", alpha(PURPLE, 38)));
        if (s.h4ZeroEpoch > 0 && s.h4HundredEpoch > 0) o.segs.add(new Overlay.Seg(s.h4ZeroEpoch, z, s.h4HundredEpoch, h, null, alpha(PURPLE, 200), true));
        if (s.h4BosEpoch > 0) o.markers.add(new Overlay.Marker(s.h4BosEpoch, buy ? h : h, "H4 BoS", alpha(PURPLE, 255), buy));
        // ---- M15 fib
        if (s.mHundred != 0 && s.mZero != 0) {
            double mz = s.mZero, mh = s.mHundred, mr = mh - mz;
            o.levels.add(new Overlay.Level(mz, "M15 0%", alpha(GOLD, 230), false, first));
            o.levels.add(new Overlay.Level(mz + 0.5 * mr, "M15 50%", alpha(GOLD, 200), true, first));
            o.levels.add(new Overlay.Level(mh, "M15 100%", alpha(GOLD, 230), false, first));
            if (s.mZeroEpoch > 0 && s.mHundredEpoch > 0) o.segs.add(new Overlay.Seg(s.mZeroEpoch, mz, s.mHundredEpoch, mh, null, alpha(GOLD, 220), true));
            for (int i = 0; i < 3; i++) if (s.bosEpoch[i] > 0) o.markers.add(new Overlay.Marker(s.bosEpoch[i], s.bosLevel[i], "BoS " + (i + 1), alpha(BLUE, 255), buy));
        } else {
            for (int i = 0; i < s.bosCount; i++) if (s.bosEpoch[i] > 0) o.markers.add(new Overlay.Marker(s.bosEpoch[i], s.bosLevel[i], "BoS " + (i + 1), alpha(BLUE, 255), buy));
        }
        // ---- the order
        if (s.entry != 0 && s.stop != 0 && !h4View) {
            Overlay.Position p = new Overlay.Position();
            p.buy = buy; p.entry = s.entry; p.stop = s.stop; p.tp1 = s.tp1; p.tp2 = s.tp2;
            p.startEpoch = last + gran;
            p.title = (s.actionable() ? "" : "If it forms: ") + (buy ? "buy " : "sell ") + String.format(Locale.US, "%.1fR / %.1fR", s.rr, s.rrH4);
            o.positions.add(p);
        }
        o.info.add(s.stage);
        r.overlay = o;
        return r;
    }
}
