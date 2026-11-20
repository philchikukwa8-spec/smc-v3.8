package com.smcscanner.chart;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.pa.PaEngine;

import java.util.Arrays;

/** Small picture of a price-action signal on 1H candles: the pattern or trendline, the level, and shaded risk and reward. */
public final class PaChart {
    private PaChart() {}

    private static final int GREEN = 0xFF26A69A, RED = 0xFFEF5350, BLUE = 0xFF42A5F5, GOLD = 0xFFE3B341;

    private static int alpha(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }

    public static ChartRenderer build(PaEngine.Signal s, Candle[] h1, boolean dark, float density, int px) {
        ChartRenderer r = new ChartRenderer();
        r.setDark(dark);
        r.density = density;
        int keep = Math.min(h1.length, 46);
        Candle[] c = Arrays.copyOfRange(h1, h1.length - keep, h1.length);
        long gran = s.tf.equals("15m") ? 900 : s.tf.equals("30m") ? 1800 : 3600;
        r.setCandles(c, (int) gran);
        r.nowSec = c[c.length - 1].epoch + gran;
        r.showOverlay = true;
        int rightPad = 16;
        double plotW = px - 66 * density;
        r.bw = Math.max(2.5 * density, plotW / (keep + rightPad));
        r.shift = rightPad;
        r.title = s.label + " " + s.tf;
        Overlay o = new Overlay();
        boolean buy = s.isBuy();
        int col = buy ? GREEN : RED;
        long last = c[c.length - 1].epoch;
        for (double[] ln : s.lines) o.segs.add(new Overlay.Seg((long) ln[0], ln[1], (long) ln[2], ln[3], null, alpha(GOLD, 230), false));
        if (s.zoneHigh > s.zoneLow && s.zoneLow > 0) o.zones.add(new Overlay.Zone(s.zoneLow, s.zoneHigh, c[Math.max(0, c.length - 60)].epoch, "", alpha(GOLD, 60)));
        if (s.level > 0) o.levels.add(new Overlay.Level(s.level, "", alpha(BLUE, 220), true, c[Math.max(0, c.length - 40)].epoch));
        if (s.dc == 2 && s.dcEpoch > 0) o.markers.add(new Overlay.Marker(s.dcEpoch, s.dcPrice, "2nd confirmation", alpha(GREEN, 255), !buy));
        Overlay.Position p = new Overlay.Position();
        p.buy = buy; p.entry = s.entry; p.stop = s.stop; p.tp1 = s.target;
        p.startEpoch = last + gran;
        p.title = (s.actionable() ? "" : "If it confirms: ") + (buy ? "buy " : "sell ") + String.format(java.util.Locale.US, "%.1fR", s.rr);
        o.positions.add(p);
        r.overlay = o;
        return r;
    }
}
