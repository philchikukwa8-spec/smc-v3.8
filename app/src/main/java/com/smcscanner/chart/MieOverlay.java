package com.smcscanner.chart;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.mie.Mie;

/** What the Advisor card draws on its mini chart: the zone, the entry, the stop and the target, shaded. */
public final class MieOverlay {
    private MieOverlay() {}

    private static int alpha(int argb, int a) { return (a << 24) | (argb & 0x00FFFFFF); }

    public static Overlay build(Mie.Assessment a, Candle[] chart, long gran) {
        Overlay o = new Overlay();
        if (chart == null || chart.length < 10) return o;
        long last = chart[chart.length - 1].epoch;
        boolean buy = a.isBuy();
        int col = buy ? 0xFF26A69A : 0xFFEF5350;
        // the zone price is waiting for / came back to
        if (a.zoneHigh > a.zoneLow) {
            o.zones.add(new Overlay.Zone(a.zoneLow, a.zoneHigh, last - 40 * gran, a.zoneDesc.isEmpty() ? "Zone" : a.zoneDesc, alpha(0xFFE3B341, 60)));
        }
        if (a.entry > 0 && a.stop > 0 && a.target > 0) {
            Overlay.Position p = new Overlay.Position();
            p.buy = buy; p.entry = a.entry; p.stop = a.stop; p.tp1 = a.target;
            p.startEpoch = last; p.title = a.signal;
            o.positions.add(p);
            o.levels.add(new Overlay.Level(a.target, "Target " + Fmt.f(a.target), alpha(0xFF26A69A, 230), true, last - 12 * gran));
            o.levels.add(new Overlay.Level(a.stop, "Stop " + Fmt.f(a.stop), alpha(0xFFEF5350, 230), true, last - 12 * gran));
        }
        return o;
    }
}
