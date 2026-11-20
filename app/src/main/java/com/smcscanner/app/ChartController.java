package com.smcscanner.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.smcscanner.chart.Overlay;
import com.smcscanner.chart.OverlayBuilder;
import com.smcscanner.engine.Candle;

/** Connects the chart to Deriv's live feed, reconnects when the connection drops, and keeps the overlays current. */
public final class ChartController {
    private final Context ctx;
    private final ChartView view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private DerivClient client;
    private DerivClient.Subscription sub;
    private String symbol;
    private long gran;
    private volatile boolean active;
    private boolean reconnectPending, loadingOlder;
    public volatile boolean showOverlay = true;
    /** the tapped idea (its structure, liquidity and order block are drawn), support/resistance zones, chart patterns */
    public volatile String selectedId;
    public volatile boolean showZones = true, showPatterns = true;
    /** smart-money layers and the current Advisor setup */
    public volatile boolean showPaSetup = true;
    private com.smcscanner.app.PaRunner.Item paItem; private long paAt;
    public volatile boolean showSetup = true, showBos = true, showOb = true, showFvg = true, showLiq = true, showTrend = true;
    /** one signal shown on its own chart: entry, the stop (where the idea is invalid), the target (where it is proven right) and its pattern lines */
    public static final class Focus {
        public String title = "", symbol = "", tf = "15m", note = "";
        public boolean buy, ready;
        public double entry, stop, target, validation;
        public String validationLabel = "Validation";
        public Double target2;
        /** where the second (lower-chart) confirmation happened, 0 = none */
        public long dcEpoch;
        public double dcPrice;
        public double zoneLow, zoneHigh;
        public String zoneLabel = "Entry zone";
        public final java.util.List<double[]> lines = new java.util.ArrayList<>();
        public final java.util.List<Overlay.Level> levels = new java.util.ArrayList<>();
        public long gran() { return tf.equals("1m") ? 60 : tf.equals("5m") ? 300 : tf.equals("15m") ? 900 : tf.equals("30m") ? 1800 : tf.equals("1H") ? 3600 : tf.equals("4H") || tf.equals("H4") ? 14400 : tf.equals("1D") ? 86400 : 900; }
    }
    /** when set, the chart shows only this signal (the chart tab clears it when you press "Show everything") */
    public volatile Focus focus;
    private Overlay cacheStruct = new Overlay();
    private long structKey = -1, setupAt = 0;
    private com.smcscanner.app.MieRunner.Item setupItem;
    /** call after changing a layer switch */
    public void layersChanged() { structKey = -1; refreshOverlay(); view.invalidate(); }
    private long cacheKey = -1;
    private com.smcscanner.engine.Patterns.Zone[] cacheZones = new com.smcscanner.engine.Patterns.Zone[0];
    private java.util.List<com.smcscanner.engine.Patterns.Pattern> cachePatterns = new java.util.ArrayList<>();

    public ChartController(Context ctx, ChartView view) {
        this.ctx = ctx.getApplicationContext(); this.view = view;
        view.tapListener = (x, y) -> {
            String hit = view.renderer.hitIdea(x, y);
            selectedId = hit != null && !hit.equals(selectedId) ? hit : null;
            refreshOverlay();
            view.invalidate();
        };
    }

    private static String tfLabel(long g) {
        return g == 60 ? "1m" : g == 300 ? "5m" : g == 900 ? "15m" : g == 3600 ? "1H" : g == 1800 ? "30m" : g == 14400 ? "4H" : g == 86400 ? "1D" : g + "s";
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refreshOverlay();
            maybeLoadOlder();
            view.invalidate();
            ui.postDelayed(this, 3000);
        }
    };

    private final Runnable reconnect = new Runnable() {
        @Override public void run() { reconnectPending = false; if (active) connect(); }
    };

    public void start(String sym, long granularity, String title) {
        stop();
        symbol = sym; gran = granularity; active = true; selectedId = null; cacheKey = -1;
        view.renderer.title = title;
        view.renderer.live = false;
        view.renderer.status = "Connecting...";
        view.renderer.setCandles(new Candle[0], granularity);
        view.invalidate();
        client = new DerivClient();
        connect();
        ui.postDelayed(tick, 3000);
    }

    public void stop() {
        active = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(reconnect);
        reconnectPending = false;
        final DerivClient c = client;
        final DerivClient.Subscription s = sub;
        client = null; sub = null;
        if (c != null) new Thread(() -> { try { if (s != null) s.close(); } catch (Exception ignored) { } c.close(); }).start();
    }

    private void connect() {
        final DerivClient c = client;
        final String sym = symbol;
        final long g = gran;
        if (c == null) return;
        new Thread(() -> {
            try {
                DerivClient.Subscription s = c.subscribeCandles(sym, g, 500, new DerivClient.CandleStream() {
                    @Override public void onHistory(Candle[] candles) {
                        ui.post(() -> {
                            if (!active || g != gran || !sym.equals(symbol)) return;
                            view.renderer.setCandles(candles, g);
                            view.renderer.live = true;
                            view.renderer.status = "";
                            refreshOverlay();
                            view.invalidate();
                        });
                    }
                    @Override public void onCandle(Candle candle) {
                        ui.post(() -> {
                            if (!active || g != gran || !sym.equals(symbol)) return;
                            boolean fresh = view.renderer.applyOhlc(candle);
                            if (fresh) refreshOverlay();
                            view.invalidate();
                        });
                    }
                    @Override public void onDisconnected(String why) {
                        ui.post(() -> {
                            if (!active) return;
                            view.renderer.live = false;
                            view.renderer.status = "Reconnecting...";
                            view.invalidate();
                            scheduleReconnect();
                        });
                    }
                });
                sub = s;
            } catch (Exception e) {
                ui.post(() -> {
                    if (!active) return;
                    view.renderer.live = false;
                    view.renderer.status = "No connection";
                    view.invalidate();
                    scheduleReconnect();
                });
            }
        }).start();
    }

    private void scheduleReconnect() {
        if (reconnectPending || !active) return;
        reconnectPending = true;
        ui.postDelayed(reconnect, 3000);
    }

    private void focusOverlay(Focus f) {
        Candle last = view.renderer.last();
        Candle[] closed = view.renderer.closedCandles();
        long lastE = closed.length > 0 ? closed[closed.length - 1].epoch : (last != null ? last.epoch : 0);
        long firstE = closed.length > 0 ? closed[Math.max(0, closed.length - 80)].epoch : lastE;
        Overlay o = new Overlay();
        if (f.zoneHigh > f.zoneLow && f.zoneLow > 0) o.zones.add(new Overlay.Zone(f.zoneLow, f.zoneHigh, firstE, f.zoneLabel, 0x3CE3B341));
        for (double[] ln : f.lines) o.segs.add(new Overlay.Seg((long) ln[0], ln[1], (long) ln[2], ln[3], null, 0xE6E3B341, false));
        o.levels.addAll(f.levels);
        if (f.dcEpoch > 0) o.markers.add(new Overlay.Marker(f.dcEpoch, f.dcPrice, "2nd confirmation", 0xFF26A69A, !f.buy));
        if (f.validation > 0 && Math.abs(f.validation - f.entry) > 1e-9) o.levels.add(new Overlay.Level(f.validation, f.validationLabel, 0xFFE3B341, true, firstE));
        o.levels.add(new Overlay.Level(f.entry, "Entry", 0xFFFFFFFF, false, firstE));
        o.levels.add(new Overlay.Level(f.stop, "Invalidation (stop)", 0xFFEF5350, false, firstE));
        if (f.target > 0) o.levels.add(new Overlay.Level(f.target, "Target (validated)", 0xFF26A69A, false, firstE));
        Overlay.Position pp = new Overlay.Position();
        pp.buy = f.buy; pp.entry = f.entry; pp.stop = f.stop; pp.tp1 = f.target > 0 ? f.target : f.entry + (f.buy ? 1 : -1) * 2 * Math.abs(f.entry - f.stop);
        pp.tp2 = f.target2; pp.startEpoch = lastE; pp.title = f.title;
        o.positions.add(pp);
        o.info.add(f.title);
        if (!f.note.isEmpty()) o.info.add(f.note);
        view.renderer.overlay = o;
        view.renderer.showOverlay = true;
        view.renderer.showNotes = Store.chartNotes(ctx);
    }

    public void refreshOverlay() {
        if (symbol == null) return;
        Focus fo = focus;
        if (fo != null) { focusOverlay(fo); return; }
        Candle last = view.renderer.last();
        Overlay o = OverlayBuilder.build(Store.loadState(ctx), Store.loadTrades(ctx), symbol, Store.riskMoney(ctx), 3, last != null ? Double.valueOf(last.close) : null, selectedId);
        Candle[] closed = view.renderer.closedCandles();
        if (closed.length > 0) {
            long key = closed[closed.length - 1].epoch * 1000 + closed.length % 1000;
            if (key != cacheKey) {
                cacheKey = key;
                try {
                    cacheZones = com.smcscanner.engine.Patterns.keyZones(closed, 5).toArray(new com.smcscanner.engine.Patterns.Zone[0]);
                    cachePatterns = com.smcscanner.engine.Patterns.detect(closed);
                    for (com.smcscanner.engine.Patterns.Pattern pt : cachePatterns) pt.tf = tfLabel(gran);
                } catch (Exception e) { cacheZones = new com.smcscanner.engine.Patterns.Zone[0]; cachePatterns = new java.util.ArrayList<>(); }
            }
            OverlayBuilder.addMarketLayers(o, cacheZones, cachePatterns, closed[closed.length - 1].epoch, showZones, showPatterns);
        }
        if (closed.length >= 40) {
            long sk = (closed[closed.length - 1].epoch * 1000 + closed.length % 1000) * 64 + (showBos ? 1 : 0) + (showOb ? 2 : 0) + (showFvg ? 4 : 0) + (showLiq ? 8 : 0) + (showTrend ? 16 : 0);
            if (sk != structKey) {
                structKey = sk;
                cacheStruct = new Overlay();
                try {
                    com.smcscanner.chart.StructureLayers.Flags f = new com.smcscanner.chart.StructureLayers.Flags();
                    f.bos = showBos; f.ob = showOb; f.fvg = showFvg; f.liq = showLiq; f.trend = showTrend;
                    com.smcscanner.chart.StructureLayers.add(cacheStruct, closed, f);
                } catch (Exception e) { cacheStruct = new Overlay(); }
            }
            o.zones.addAll(cacheStruct.zones); o.levels.addAll(cacheStruct.levels); o.segs.addAll(cacheStruct.segs); o.markers.addAll(cacheStruct.markers);
        }
        if (showSetup && closed.length > 0 && Store.modeSmc(ctx)) {
            long now = System.currentTimeMillis();
            if (setupItem == null || now - setupAt > 15000 || !symbol.equals(setupItem.symbol)) {
                setupAt = now; setupItem = null;
                try { for (MieRunner.Item it : MieRunner.load(ctx)) if (it.symbol.equals(symbol) && (setupItem == null || (it.a.actionable() && !setupItem.a.actionable()))) setupItem = it; } catch (Exception ignored) { }
            }
            if (setupItem != null && setupItem.a != null && !setupItem.a.side.equals("NONE") && !setupItem.a.signal.equals("REJECT")) {
                com.smcscanner.chart.MieChart.addSetup(o, setupItem.a, closed[Math.max(0, closed.length - 60)].epoch, closed[closed.length - 1].epoch, true, setupItem.chartGran);
            }
        }
        if (showPaSetup && closed.length > 0) {
            long now = System.currentTimeMillis();
            if (paItem == null || now - paAt > 15000 || !symbol.equals(paItem.symbol)) {
                paAt = now; paItem = null;
                try { for (PaRunner.Item it : PaRunner.load(ctx)) if (it.symbol.equals(symbol)) paItem = it; } catch (Exception ignored) { }
            }
            if (paItem != null && !paItem.signals.isEmpty()) {
                com.smcscanner.engine.pa.PaEngine.Signal ps = paItem.signals.get(0);
                long lastE = closed[closed.length - 1].epoch;
                for (double[] ln : ps.lines) o.segs.add(new Overlay.Seg((long) ln[0], ln[1], (long) ln[2], ln[3], null, 0xE6E3B341, false));
                if (ps.zoneHigh > ps.zoneLow && ps.zoneLow > 0) o.zones.add(new Overlay.Zone(ps.zoneLow, ps.zoneHigh, closed[Math.max(0, closed.length - 60)].epoch, "Bird's Eye level", 0x3CE3B341));
                Overlay.Position pp = new Overlay.Position();
                pp.buy = ps.isBuy(); pp.entry = ps.entry; pp.stop = ps.stop; pp.tp1 = ps.target; pp.startEpoch = lastE;
                pp.title = "Bird's Eye " + (ps.actionable() ? "" : "(watch) ") + (ps.isBuy() ? "buy " : "sell ") + String.format(java.util.Locale.US, "%.1fR", ps.rr) + ": " + ps.scenario;
                o.positions.add(pp);
            }
        }
        while (o.info.size() > 6) o.info.remove(o.info.size() - 1);
        view.renderer.overlay = o;
        view.renderer.showNotes = Store.chartNotes(ctx);
        view.renderer.showOverlay = showOverlay;
    }

    private void maybeLoadOlder() {
        if (loadingOlder || client == null || !view.renderer.needsOlder() || view.renderer.size() >= 5000) return;
        final Candle first = view.renderer.first();
        if (first == null) return;
        loadingOlder = true;
        final DerivClient c = client;
        final String sym = symbol;
        final long g = gran;
        new Thread(() -> {
            try {
                Candle[] older = c.candlesBefore(sym, g, first.epoch - 1, 500);
                ui.post(() -> { if (active && g == gran && sym.equals(symbol)) { view.renderer.prepend(older); view.invalidate(); } loadingOlder = false; });
            } catch (Exception e) {
                ui.post(() -> loadingOlder = false);
            }
        }).start();
    }
}
