package com.smcscanner.chart;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.mie.Mie;

import java.util.Arrays;

/** Builds a small picture of a Market Intelligence assessment: the 15m candles, the zone, the 15m trigger and the shaded risk and reward boxes. */
public final class MieChart {
    private MieChart() {}

    private static final int GREEN = 0xFF26A69A, RED = 0xFFEF5350, BLUE = 0xFF42A5F5, GOLD = 0xFFE3B341;

    private static int alpha(int c, int a) { return (a << 24) | (c & 0xFFFFFF); }

    /** `m15` are closed 15m candles, oldest first. `px` is the drawing width in pixels (so candle width can be set). */
    public static ChartRenderer build(Mie.Assessment a, Candle[] m15, boolean dark, float density, int px) { return build(a, m15, 900, dark, density, px); }

    /** `gran` = seconds per candle of the trigger chart (900 for the 1H cascade, 300 and 60 for the lower cascades) */
    public static ChartRenderer build(Mie.Assessment a, Candle[] m15, long gran, boolean dark, float density, int px) {
        ChartRenderer r = new ChartRenderer();
        r.setDark(dark);
        r.density = density;
        int keep = Math.min(m15.length, 64);
        Candle[] c = Arrays.copyOfRange(m15, m15.length - keep, m15.length);
        r.setCandles(c, (int) gran);
        r.nowSec = c[c.length - 1].epoch + gran;
        r.showOverlay = true;
        int rightPad = a.target > 0 ? 26 : 14;
        double plotW = px - 66 * density;
        r.bw = Math.max(2.5 * density, plotW / (keep + rightPad));
        r.shift = rightPad;
        r.title = a.label + " " + (gran >= 900 ? "15m" : gran >= 300 ? "5m" : "1m");
        Overlay o = new Overlay();
        addSetup(o, a, c[Math.max(0, c.length - 60)].epoch, c[c.length - 1].epoch, false, gran);
        r.overlay = o;
        return r;
    }

    /** adds the zone, 15m trigger and the shaded risk/reward boxes of an assessment to an overlay */
    public static void addSetup(Overlay o, Mie.Assessment a, long from, long lastEpoch, boolean labelled) { addSetup(o, a, from, lastEpoch, labelled, 900); }

    public static void addSetup(Overlay o, Mie.Assessment a, long from, long lastEpoch, boolean labelled, long gran) {
        boolean buy = a.isBuy();
        boolean hasPos = a.entry > 0 && a.stop > 0 && a.target > 0;
        if (a.zoneHigh > a.zoneLow && a.zoneLow > 0) {
            String zl = a.zoneDesc.isEmpty() ? "Zone" : a.zoneDesc.substring(0, a.zoneDesc.indexOf(' ', Math.max(0, a.zoneDesc.indexOf(' ') + 1)) > 0 ? a.zoneDesc.indexOf(' ', a.zoneDesc.indexOf(' ') + 1) : a.zoneDesc.length());
            o.zones.add(new Overlay.Zone(a.zoneLow, a.zoneHigh, from, hasPos && !labelled ? "" : zl + (buy ? " buy zone" : " sell zone"), alpha(GOLD, 70)));
        }
        if (a.trigger > 0) {
            o.levels.add(new Overlay.Level(a.trigger, hasPos && !labelled ? "" : (a.actionable() ? "Trigger " : "Needs close ") + (buy ? "above" : "below"), alpha(BLUE, 230), true, from));
        }
        if (a.entry > 0 && a.stop > 0 && a.target > 0) {
            Overlay.Position p = new Overlay.Position();
            p.buy = buy; p.entry = a.entry; p.stop = a.stop; p.tp1 = a.target;
            p.startEpoch = a.triggerEpoch > 0 ? a.triggerEpoch + gran : lastEpoch;
            p.title = (a.actionable() ? "" : "If triggered: ") + (buy ? "buy " : "sell ") + String.format(java.util.Locale.US, "%.1fR", a.rr);
            o.positions.add(p);
        }
    }
}
