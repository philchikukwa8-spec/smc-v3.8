package com.smcscanner.chart;

import com.smcscanner.engine.Breakdown;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Patterns;
import com.smcscanner.engine.Lifecycle;
import com.smcscanner.engine.Manage;
import com.smcscanner.engine.Setup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.smcscanner.engine.Fmt.f;

/** Turns tracked ideas and your open trades into chart overlays. */
public final class OverlayBuilder {
    private OverlayBuilder() {}

    public static final int GREEN = 0xFF26A69A, RED = 0xFFEF5350, BLUE = 0xFF42A5F5, GOLD = 0xFFFFCA28, GREY = 0xFF90A4AE;

    public static long tfSeconds(String label) {
        if (label == null) return 0;
        switch (label) {
            case "1m": return 60;
            case "5m": return 300;
            case "15m": return 900;
            case "1H": return 3600;
            case "4H": return 14400;
            case "1D": return 86400;
            default: return 0;
        }
    }

    /** the idea an open trade came from: same side, entry formed before the trade started, the latest such */
    private static String tradeIdea(List<Lifecycle.Tracked> ts, Manage.Trade x) {
        Lifecycle.Tracked best = null;
        for (Lifecycle.Tracked t : ts) {
            if (t.entry == null || !t.entry.side.equals(x.side) || x.startedAt < t.entry.triggerEpoch) continue;
            if (best == null || t.entry.triggerEpoch > best.entry.triggerEpoch) best = t;
        }
        return best != null ? best.key : null;
    }

    /** what the idea is made of: the liquidity that was swept, the structure break, the order block and the target liquidity */
    private static void addDetails(Overlay o, Lifecycle.Tracked t, Setup s) {
        boolean buy = s.isBuy();
        long gran = Math.max(t.gran, 60);
        String struct = s.structure != null ? s.structure : "BOS";
        long from = s.sweepEpoch > 0 ? s.sweepEpoch - 2 * gran : s.triggerEpoch - 10 * gran;
        o.levels.add(new Overlay.Level(s.sweepLevel, "Liquidity pool swept " + f(s.sweepLevel), alpha(GOLD, 230), true, from));
        if (s.sweepEpoch > 0) o.markers.add(new Overlay.Marker(s.sweepEpoch, s.sweepExtreme, "Sweep", GOLD, !buy));
        o.levels.add(new Overlay.Level(s.brokenLevel, struct + " level " + f(s.brokenLevel), alpha(BLUE, 230), false, from));
        o.markers.add(new Overlay.Marker(s.triggerEpoch, s.brokenLevel, struct, BLUE, buy));
        if (s.obLow != null && s.obHigh != null) o.zones.add(new Overlay.Zone(s.obLow, s.obHigh, s.triggerEpoch - 6 * gran, "Order block", alpha(0xFFAB47BC, 70)));
        o.levels.add(new Overlay.Level(s.tp, "Liquidity pool target " + f(s.tp), alpha(GREEN, 230), true, s.triggerEpoch));
        o.info.add(t.tf + " " + (buy ? "BUY" : "SELL") + ": " + struct + (struct.equals("CHoCH") ? " reversal" : " continuation") + ", sweep " + f(s.sweepLevel) + (s.majorSweep ? " (extreme)" : ""));
        if (t.breakdown != null) {
            for (String key : new String[]{"srZone", "pattern", "topTrend"}) {
                Breakdown.Factor fc = t.breakdown.factor(key);
                if (fc != null && fc.pass != null) o.info.add((fc.pass.booleanValue() ? "+ " : "- ") + fc.label);
            }
        }
        o.info.add(s.zoneType + " " + f(s.zoneLow) + "-" + f(s.zoneHigh) + (s.obLow != null ? "  OB " + f(s.obLow) + "-" + f(s.obHigh) : "") + "  target " + f(s.tp));
    }

    private static int alpha(int argb, int a) { return (a << 24) | (argb & 0x00FFFFFF); }

    /**
     * @param maxIdeas how many of the most recent ideas to draw (open trades are always drawn)
     */
    public static Overlay build(Lifecycle.State state, List<Manage.Trade> trades, String symbol, double riskMoney, int maxIdeas, Double price) {
        return build(state, trades, symbol, riskMoney, maxIdeas, price, null);
    }

    public static Overlay build(Lifecycle.State state, List<Manage.Trade> trades, String symbol, double riskMoney, int maxIdeas, Double price, String selectedId) {
        Overlay o = new Overlay();
        o.selectedId = selectedId;
        List<Lifecycle.Tracked> ts = new ArrayList<>();
        for (Lifecycle.Tracked t : state.tracked.values()) {
            if (!t.market.equals(symbol)) continue;
            if (t.status.equals("done") && t.entry == null) continue; // cancelled or stale
            ts.add(t);
        }
        Collections.sort(ts, new Comparator<Lifecycle.Tracked>() {
            @Override public int compare(Lifecycle.Tracked a, Lifecycle.Tracked b) { return Long.compare(b.createdAt, a.createdAt); }
        });
        List<Manage.Trade> open = new ArrayList<>();
        if (trades != null) for (Manage.Trade x : trades) if (x.symbol.equals(symbol) && x.status.equals("open")) open.add(x);

        int shown = 0;
        for (Lifecycle.Tracked t : ts) {
            if (shown++ >= maxIdeas) break;
            Setup s = t.setup;
            boolean buy = s.isBuy();
            int col = buy ? GREEN : RED;
            long start = s.triggerEpoch - 2 * t.gran;
            Overlay.Zone iz = new Overlay.Zone(s.zoneLow, s.zoneHigh, start, t.tf + " " + (buy ? "buy" : "sell") + " idea zone (" + s.zoneType + ")", alpha(col, 46));
            iz.id = t.key;
            o.zones.add(iz);
            if (t.key.equals(selectedId)) addDetails(o, t, s);
            if (t.entry == null) {
                o.levels.add(new Overlay.Level(s.tp, t.tf + " idea target " + f(s.tp), alpha(col, 200), true, start));
                o.levels.add(new Overlay.Level(s.sl, t.tf + " idea invalidation " + f(s.sl), alpha(RED, 200), true, start));
                continue;
            }
            Setup e = t.entry;
            String ft = t.finalTf != null ? t.finalTf : t.ltfTf;
            long egran = tfSeconds(ft);
            o.zones.add(new Overlay.Zone(e.zoneLow, e.zoneHigh, e.triggerEpoch - 2 * Math.max(egran, 60), ft + " entry zone", alpha(BLUE, 70)));
            // the zoom-in ladder: where each finer timeframe would have entered
            Breakdown b = t.breakdown;
            if (b != null && b.ladder.size() > 1) {
                for (int i = 0; i < b.ladder.size() - 1; i++) {
                    Breakdown.Rung r = b.ladder.get(i);
                    o.levels.add(new Overlay.Level(r.entry, r.label + " entry " + f(r.entry), alpha(GREY, 190), true, e.triggerEpoch - 2 * Math.max(egran, 60)));
                }
            }
            if (price != null && (buy ? price <= e.sl || price >= e.tp : price >= e.sl || price <= e.tp)) continue; // already stopped or at target: no longer a live plan
            boolean managed = false;
            for (Manage.Trade x : open) if (x.side.equals(e.side) && x.startedAt >= e.triggerEpoch) managed = true;
            if (managed) continue;
            Overlay.Position p = new Overlay.Position();
            p.buy = buy; p.entry = e.entry; p.stop = e.sl; p.tp1 = e.tp;
            p.tp2 = b != null ? b.levels.tp2 : null;
            p.startEpoch = e.triggerEpoch + Math.max(egran, 60);
            p.riskMoney = riskMoney;
            p.title = "Planned " + (buy ? "buy" : "sell");
            p.id = t.key;
            o.positions.add(p);
        }
        for (Manage.Trade x : open) {
            Overlay.Position p = new Overlay.Position();
            p.buy = x.isBuy(); p.entry = x.entry; p.stop = x.stop; p.tp1 = x.tp != null ? x.tp : x.tp1; p.tp2 = x.tp != null && x.tp2 != null && x.tp.doubleValue() != x.tp2.doubleValue() ? x.tp2 : null;
            p.startEpoch = x.startedAt;
            p.riskMoney = x.riskMoney > 0 ? x.riskMoney : riskMoney;
            p.title = "Your trade";
            p.id = tradeIdea(ts, x);
            p.now = x.lastPrice;
            o.positions.add(p);
            if (Math.abs(x.stop - x.stop0) > 1e-9) o.levels.add(new Overlay.Level(x.stop0, "original stop " + f(x.stop0), alpha(RED, 160), true, x.startedAt));
        }
        return o;
    }

    /** support/resistance zones and chart patterns found on the candles being shown */
    public static void addMarketLayers(Overlay o, Patterns.Zone[] zones, List<Patterns.Pattern> patterns, long lastEpoch, boolean showZones, boolean showPatterns) {
        if (showZones && zones != null) {
            for (Patterns.Zone z : zones) {
                int col = z.kind.equals("support") ? 0xFF4DB6AC : z.kind.equals("resistance") ? 0xFFE57373 : GREY;
                String name = (z.kind.equals("support") ? "Support" : z.kind.equals("resistance") ? "Resistance" : "S/R") + " " + z.touches + "x";
                Overlay.Zone oz = new Overlay.Zone(z.low, z.high, z.firstEpoch, name, alpha(col, 38));
                oz.labelLeft = true;
                o.zones.add(oz);
            }
        }
        if (showPatterns && patterns != null) {
            for (Patterns.Pattern p : patterns) {
                int col = p.bias.equals("BUY") ? 0xFF66BB6A : p.bias.equals("SELL") ? 0xFFEF5350 : 0xFFFFCA28;
                int k = 0;
                for (double[] ln : p.lines) {
                    String lab = k == p.lines.size() - 1 && p.lines.size() > 0 ? p.name + " (" + p.status + ")" : null;
                    o.segs.add(new Overlay.Seg((long) ln[0], ln[1], (long) ln[2], ln[3], lab, col, false));
                    k++;
                }
                o.levels.add(new Overlay.Level(p.level, p.name + (p.status.equals("confirmed") ? " broke here" : " breakout line") + " " + Fmt.f(p.level), alpha(col, 210), true, p.endEpoch));
                o.info.add(p.name + " - " + p.status + (p.bias.equals("NEUTRAL") ? "" : " - " + (p.bias.equals("BUY") ? "bullish" : "bearish")) + " - line " + Fmt.f(p.level));
            }
        }
    }
}
