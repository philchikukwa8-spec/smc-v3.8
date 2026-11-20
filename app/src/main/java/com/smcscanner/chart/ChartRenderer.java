package com.smcscanner.chart;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Fmt;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A TradingView-style candlestick chart: pan, pinch zoom, crosshair, price and time axes, a live last-price tag
 * with candle countdown, shaded zones, price lines and risk/reward boxes. Pure Java so it can be tested off-device.
 */
public final class ChartRenderer {
    /** dark theme colours; the live colours are the fields below (see setDark) */
    public static final int BG = 0xFF0F1720, GRID = 0xFF1B2733, TXT = 0xFF90A4AE, UP = 0xFF26A69A, DOWN = 0xFFEF5350, WHITE = 0xFFFFFFFF, INK = 0xFF0F1720;

    private int bgC = BG, gridC = GRID, txtC = TXT, headC = WHITE, zoneTxtC = 0xFFCFD8DC, entryFill = 0xFFECEFF1, entryInk = INK;

    /** switches the chart between the dark and the light look */
    public void setDark(boolean dark) {
        bgC = dark ? BG : 0xFFFFFFFF;
        gridC = dark ? GRID : 0xFFEEF2F5;
        txtC = dark ? TXT : 0xFF607D8B;
        headC = dark ? WHITE : 0xFF1B2733;
        zoneTxtC = dark ? 0xFFCFD8DC : 0xFF455A64;
        entryFill = dark ? 0xFFECEFF1 : 0xFF263238;
        entryInk = dark ? INK : WHITE;
    }

    private final ArrayList<Candle> c = new ArrayList<>();
    public long gran = 300;
    public String title = "";
    /** the explaining text (setup names, info box, pattern names); off keeps the picture clean so the entry is easy to see */
    public boolean showNotes = true;
    /** pixels per candle slot */
    public double bw = 7;
    /** empty candle slots to the right of the newest candle (negative = scrolled back into history) */
    public double shift = 8;
    public Overlay overlay = new Overlay();
    public boolean showOverlay = true;
    public long nowSec;
    public boolean live;
    public float density = 1f;
    public String status = "";

    private boolean crosshair;
    private float crossX, crossY;

    // layout of the last draw
    private float plotL, plotR, plotT, plotB;
    private double pMin, pMax;

    public int size() { return c.size(); }
    /** all candles except the one still forming */
    public Candle[] closedCandles() {
        int n = c.size();
        Candle[] out = new Candle[Math.max(0, n - 1)];
        for (int i = 0; i < out.length; i++) out[i] = c.get(i);
        return out;
    }

    /** which idea sits under this touch (a trade box or an idea zone), or null */
    public String hitIdea(float x, float y) {
        if (overlay == null || !showOverlay) return null;
        for (Overlay.Position p : overlay.positions) {
            if (p.id == null) continue;
            float x0 = xOfIdx(idxOfEpoch(p.startEpoch)), x1 = x0 + (float) (26 * bw);
            float ys = yOf(p.stop), ye = yOf(p.entry), yt = yOf(p.tp1);
            float top = Math.min(ys, Math.min(ye, yt)), bot = Math.max(ys, Math.max(ye, yt));
            if (p.tp2 != null) { float y2 = yOf(p.tp2); top = Math.min(top, y2); bot = Math.max(bot, y2); }
            float slop = 10 * density;
            if (x >= x0 - slop && x <= x1 + slop && y >= top - slop && y <= bot + slop) return p.id;
        }
        for (Overlay.Zone z : overlay.zones) {
            if (z.id == null) continue;
            float xs = Math.max(plotL, xOfIdx(idxOfEpoch(z.startEpoch)));
            float y0 = yOf(z.high), y1 = yOf(z.low);
            if (x >= xs && x <= plotR && y >= y0 - 6 * density && y <= y1 + 6 * density) return z.id;
        }
        return null;
    }

    public Candle last() { return c.isEmpty() ? null : c.get(c.size() - 1); }
    public Candle first() { return c.isEmpty() ? null : c.get(0); }

    public void setCandles(Candle[] cs, long granularity) {
        c.clear();
        for (Candle x : cs) c.add(x);
        gran = granularity;
        shift = 8;
    }

    /** Adds older candles at the left without moving what is on screen. */
    public void prepend(Candle[] older) {
        if (older.length == 0) return;
        long firstEpoch = c.isEmpty() ? Long.MAX_VALUE : c.get(0).epoch;
        List<Candle> add = new ArrayList<>();
        for (Candle x : older) if (x.epoch < firstEpoch) add.add(x);
        c.addAll(0, add);
    }

    /** Live update from the stream. Returns true if it started a new candle. */
    public boolean applyOhlc(Candle n) {
        if (c.isEmpty()) { c.add(n); return true; }
        Candle l = c.get(c.size() - 1);
        if (n.epoch == l.epoch) { c.set(c.size() - 1, n); return false; }
        if (n.epoch > l.epoch) {
            c.add(n);
            if (shift < 0.5 && shift > -0.5) { /* following the live edge */ } else if (shift < 0) shift -= 1; // keep a scrolled-back view still
            return true;
        }
        return false;
    }

    public void resetView() { shift = 8; if (bw < 4) bw = 7; }

    /** true when the newest candle is on screen */
    public boolean atLiveEdge() { return shift > -2; }

    public boolean needsOlder() {
        if (c.isEmpty() || plotR <= plotL) return false;
        double leftIdx = (c.size() - 1) + shift - (plotR - plotL) / bw;
        return leftIdx < 8;
    }

    // ---------- gestures ----------
    public void pan(float dxPx) {
        shift -= dxPx / bw;
        double lo = -(c.size() - 5), hi = 80;
        if (shift < lo) shift = lo;
        if (shift > hi) shift = hi;
    }

    /** scale > 1 zooms in, keeping the candle under focalX where it is */
    public void zoom(float scale, float focalX) {
        if (c.isEmpty()) return;
        double idxAtFocal = (c.size() - 1) + shift - (plotR - focalX) / bw;
        bw = Math.max(2.0, Math.min(40.0, bw * scale));
        shift = idxAtFocal - (c.size() - 1) + (plotR - focalX) / bw;
        double lo = -(c.size() - 5), hi = 80;
        if (shift < lo) shift = lo;
        if (shift > hi) shift = hi;
    }

    public void setCrosshair(float x, float y) { crosshair = true; crossX = x; crossY = y; }
    public void clearCrosshair() { crosshair = false; }
    public boolean hasCrosshair() { return crosshair; }

    // ---------- coordinates ----------
    private float xOfIdx(double i) { return (float) (plotR - ((c.size() - 1) + shift - i) * bw - bw / 2); }

    private double idxOfX(float x) { return (c.size() - 1) + shift - (plotR - x + bw / 2) / bw; }

    /** candle index (fractional) for an epoch; extrapolates before the first and after the last candle */
    private double idxOfEpoch(long epoch) {
        int n = c.size();
        if (n == 0 || gran <= 0) return 0;
        if (epoch >= c.get(n - 1).epoch) return (n - 1) + (double) (epoch - c.get(n - 1).epoch) / gran;
        if (epoch <= c.get(0).epoch) return (double) (epoch - c.get(0).epoch) / gran;
        int lo = 0, hi = n - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (c.get(mid).epoch <= epoch) lo = mid; else hi = mid - 1; }
        return lo + (double) (epoch - c.get(lo).epoch) / gran;
    }

    private float yOf(double p) { return (float) (plotT + (pMax - p) / (pMax - pMin) * (plotB - plotT)); }

    private double priceOfY(float y) { return pMax - (y - plotT) / (plotB - plotT) * (pMax - pMin); }

    private static final long[] STEPS = {60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400, 172800, 604800, 2592000};

    /** spacing of time labels, in seconds, as a round multiple of the candle length */
    private long timeStep(long gran, double bw, float d) {
        double need = 80 * d / bw * gran;
        for (long q : STEPS) if (q >= need * 0.7 && q % Math.max(1, gran) == 0) return q;
        return STEPS[STEPS.length - 1];
    }

    private static boolean onStep(long epoch, long step) {
        long off = TimeZone.getDefault().getOffset(epoch * 1000) / 1000;
        return ((epoch + off) % step + step) % step == 0;
    }

    private static double niceStep(double raw) {
        double pow = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / pow;
        double nf = f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10;
        return nf * pow;
    }

    private static String fmtTime(long epoch, long gran, boolean forceDate) {
        Calendar cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US);
        cal.setTimeInMillis(epoch * 1000);
        int h = cal.get(Calendar.HOUR_OF_DAY), m = cal.get(Calendar.MINUTE);
        String[] mon = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        String date = cal.get(Calendar.DAY_OF_MONTH) + " " + mon[cal.get(Calendar.MONTH)];
        if (gran >= 86400) return date;
        if (forceDate || (h == 0 && m == 0)) return date;
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private static String money(double v) { return String.format(Locale.US, "%.2f", Math.abs(v)); }

    // ---------- drawing ----------
    public void draw(Gfx g, int w, int h) {
        float d = density;
        float axisW = 66 * d, timeH = 20 * d, topH = 36 * d;
        plotL = 0; plotR = w - axisW; plotT = topH; plotB = h - timeH;
        g.fillRect(0, 0, w, h, bgC);
        if (c.isEmpty()) {
            g.text(status.isEmpty() ? "Loading..." : status, w / 2f, h / 2f, 14 * d, txtC, false, Gfx.CENTER);
            return;
        }
        int n = c.size();
        int iMin = Math.max(0, (int) Math.floor(idxOfX(plotL)) - 1);
        int iMax = Math.min(n - 1, (int) Math.ceil(idxOfX(plotR)) + 1);
        if (iMin > iMax) { iMin = Math.max(0, n - 1); iMax = n - 1; }

        // price range of what is visible (plus overlay levels that are not far away)
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = iMin; i <= iMax; i++) { lo = Math.min(lo, c.get(i).low); hi = Math.max(hi, c.get(i).high); }
        double range = Math.max(hi - lo, Math.abs(hi) * 1e-6 + 1e-9);
        if (showOverlay) {
            double a = lo - 1.0 * range, b = hi + 1.0 * range;
            double lo2 = lo, hi2 = hi;
            for (Overlay.Position p : overlay.positions) for (double v : new double[]{p.entry, p.stop, p.tp1}) if (v > a - 2 * range && v < b + 2 * range && (v > a && v < b || v == p.entry || v == p.stop)) { lo2 = Math.min(lo2, v); hi2 = Math.max(hi2, v); }
            for (Overlay.Position p : overlay.positions) if (p.tp2 != null && p.tp2 > a && p.tp2 < b) { lo2 = Math.min(lo2, p.tp2); hi2 = Math.max(hi2, p.tp2); }
            for (Overlay.Level l : overlay.levels) if (l.price > a && l.price < b) { lo2 = Math.min(lo2, l.price); hi2 = Math.max(hi2, l.price); }
            for (Overlay.Zone z : overlay.zones) { if (z.high > a && z.low < b) { lo2 = Math.min(lo2, Math.max(z.low, a)); hi2 = Math.max(hi2, Math.min(z.high, b)); } }
            lo = lo2; hi = hi2;
        }
        double pad = (hi - lo) * (showOverlay && overlay != null && !overlay.positions.isEmpty() ? 0.15 : 0.07) + 1e-9;
        pMin = lo - pad; pMax = hi + pad;

        // grid + price labels
        double step = niceStep((pMax - pMin) / 7);
        g.clip(plotL, plotT, plotR, plotB);
        for (double p = Math.ceil(pMin / step) * step; p < pMax; p += step) {
            float y = yOf(p);
            g.line(plotL, y, plotR, y, 1f, gridC, false);
        }
        // vertical grid on round times
        long stepSec = timeStep(gran, bw, d);
        boolean anyOnStep = false;
        for (int i = Math.max(0, iMin); i <= iMax && !anyOnStep; i++) anyOnStep = onStep(c.get(i).epoch, stepSec);
        final int everyN = Math.max(1, (int) Math.round(80 * d / bw)); // fallback when candle times are not round
        for (int i = Math.max(0, iMin); i <= iMax; i++) {
            if (anyOnStep ? !onStep(c.get(i).epoch, stepSec) : i % everyN != 0) continue;
            float x = xOfIdx(i);
            g.line(x, plotT, x, plotB, 1f, gridC, false);
        }

        // zones
        if (showOverlay) {
            for (Overlay.Zone z : overlay.zones) {
                float x0 = Math.max(plotL, xOfIdx(idxOfEpoch(z.startEpoch)));
                if (x0 >= plotR) continue;
                float y0 = yOf(z.high), y1 = yOf(z.low);
                if (y1 - y0 < 1.5f) y1 = y0 + 1.5f;
                g.fillRect(x0, y0, plotR, y1, z.color);
                g.line(x0, y0, plotR, y0, 1f, (z.color & 0x00FFFFFF) | 0xAA000000, false);
                g.line(x0, y1, plotR, y1, 1f, (z.color & 0x00FFFFFF) | 0xAA000000, false);
                if (z.label != null && y1 > plotT && y0 < plotB) {
                    if (z.labelLeft) g.text(z.label, Math.max(x0, plotL) + 6 * d, Math.max(y0, plotT) + 11 * d, 9 * d, zoneTxtC, false, Gfx.LEFT);
                    else g.text(z.label, plotR - 6 * d, Math.max(y0, plotT) + 12 * d, 10 * d, zoneTxtC, false, Gfx.RIGHT);
                }
            }
        }

        // risk / reward boxes (drawn under the candles)
        if (showOverlay) for (Overlay.Position p : overlay.positions) drawPosition(g, p, d, 0);

        // candles
        float bodyHalf = (float) Math.max(0.6, bw * 0.36);
        for (int i = iMin; i <= iMax; i++) {
            Candle k = c.get(i);
            int col = k.close >= k.open ? UP : DOWN;
            float x = xOfIdx(i);
            g.line(x, yOf(k.high), x, yOf(k.low), Math.max(1f, d * 0.8f), col, false);
            float yo = yOf(k.open), yc = yOf(k.close);
            float top = Math.min(yo, yc), bot = Math.max(yo, yc);
            if (bot - top < 1f) bot = top + 1f;
            g.fillRect(x - bodyHalf, top, x + bodyHalf, bot, col);
        }

        if (showOverlay) for (Overlay.Position p : overlay.positions) drawPosition(g, p, d, 1);

        // price lines
        if (showOverlay) {
            for (Overlay.Level l : overlay.levels) {
                if (l.price < pMin || l.price > pMax) continue;
                float x0 = Math.max(plotL, xOfIdx(idxOfEpoch(l.startEpoch)));
                if (x0 >= plotR) continue;
                float y = yOf(l.price);
                g.line(x0, y, plotR, y, Math.max(1f, d), l.color, l.dashed);
                if (l.label != null) label(g, l.label, plotR, y - 4 * d, d, l.color | 0xFF000000);
            }
        }

        // chart-pattern lines and structure marks
        if (showOverlay) {
            for (Overlay.Seg sg : overlay.segs) {
                float xa = xOfIdx(idxOfEpoch(sg.epoch1)), xb = xOfIdx(idxOfEpoch(sg.epoch2));
                g.line(xa, yOf(sg.price1), xb, yOf(sg.price2), Math.max(1.4f, 1.4f * d), sg.color, sg.dashed);
                if (showNotes && sg.label != null && xb > plotL && xb < plotR + 40 * d) label(g, sg.label, xb - g.textWidth(sg.label, 10 * d, true) - 4 * d, yOf(sg.price2) - 5 * d, d, sg.color | 0xFF000000);
            }
            for (Overlay.Marker mk : overlay.markers) {
                float mx = xOfIdx(idxOfEpoch(mk.epoch)), my = yOf(mk.price);
                if (mx < plotL || mx > plotR) continue;
                g.fillRect(mx - 2 * d, my - 2 * d, mx + 2 * d, my + 2 * d, mk.color | 0xFF000000);
                label(g, mk.label, mx - g.textWidth(mk.label, 10 * d, true) / 2f, mk.above ? my - 8 * d : my + 19 * d, d, mk.color | 0xFF000000);
            }
            if (overlay.selectedId != null) {
                for (Overlay.Position p : overlay.positions) {
                    if (!overlay.selectedId.equals(p.id)) continue;
                    float x0 = xOfIdx(idxOfEpoch(p.startEpoch)), x1 = x0 + (float) (26 * bw);
                    float top = Math.min(yOf(p.stop), Math.min(yOf(p.entry), yOf(p.tp1))), bot = Math.max(yOf(p.stop), Math.max(yOf(p.entry), yOf(p.tp1)));
                    g.strokeRect(x0 - 2 * d, top - 2 * d, x1 + 2 * d, bot + 2 * d, 2f * d, 0xFFFFCA28);
                }
            }
        }

        // last price line
        Candle lastC = c.get(n - 1);
        int lastCol = lastC.close >= lastC.open ? UP : DOWN;
        float ly = yOf(lastC.close);
        if (ly >= plotT && ly <= plotB) g.line(plotL, ly, plotR, ly, 1f, lastCol, true);
        g.unclip();
        if (showNotes && showOverlay && overlay != null && !overlay.info.isEmpty()) {
            float iy = plotT + 6 * d;
            for (String line : overlay.info) {
                String ln = line;
                float maxW = plotR - plotL - 20 * d;
                while (ln.length() > 4 && g.textWidth(ln, 9 * d, false) + 12 * d > maxW) ln = ln.substring(0, ln.length() - 2);
                if (!ln.equals(line)) ln = ln.trim() + "..";
                float tw = g.textWidth(ln, 9 * d, false) + 12 * d;
                g.fillRound(plotL + 6 * d, iy, plotL + 6 * d + tw, iy + 14 * d, 4 * d, 0xCC0F1720);
                g.text(ln, plotL + 12 * d, iy + 10.5f * d, 9 * d, 0xFFECEFF1, false, Gfx.LEFT);
                iy += 16 * d;
            }
        }

        // price axis
        g.fillRect(plotR, 0, w, h, bgC);
        g.line(plotR, plotT, plotR, plotB, 1f, gridC, false);
        used.clear();
        if (ly >= plotT && ly <= plotB) {
            tagAt(g, ly, Fmt.f(lastC.close), lastCol, WHITE, d);
            used.add(ly);
            if (live && gran > 0 && nowSec > 0) {
                long left = Math.max(0, lastC.epoch + gran - nowSec);
                String cd = left >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60) : String.format(Locale.US, "%02d:%02d", left / 60, left % 60);
                g.fillRound(plotR + 2 * d, ly + 9 * d, w - 2 * d, ly + 23 * d, 3 * d, 0xFF263544);
                g.text(cd, plotR + 6 * d, ly + 20 * d, 10 * d, 0xFFECEFF1, false, Gfx.LEFT);
                used.add(ly + 16 * d);
            }
        }
        if (showOverlay) {
            for (Overlay.Position p : overlay.positions) {
                tagU(g, p.entry, Fmt.f(p.entry), entryFill, entryInk, d);
                tagU(g, p.stop, Fmt.f(p.stop), DOWN, WHITE, d);
                tagU(g, p.tp1, Fmt.f(p.tp1), UP, WHITE, d);
                if (p.tp2 != null) tagU(g, p.tp2, Fmt.f(p.tp2), 0xFF1E8E83, WHITE, d);
            }
            for (Overlay.Level l : overlay.levels) tagU(g, l.price, Fmt.f(l.price), (l.color & 0x00FFFFFF) | 0xFF000000, WHITE, d);
        }
        for (double p = Math.ceil(pMin / step) * step; p < pMax; p += step) {
            float y = yOf(p);
            if (y < plotT + 6 * d || y > plotB - 6 * d || near(y, 13 * d)) continue;
            g.text(Fmt.f(Math.round(p / step * 1000.0) / 1000.0 * step), plotR + 5 * d, y + 4 * d, 10 * d, txtC, false, Gfx.LEFT);
        }

        // time axis
        g.fillRect(0, plotB, w, h, bgC);
        for (int i = Math.max(0, iMin); i <= iMax; i++) {
            if (anyOnStep ? !onStep(c.get(i).epoch, stepSec) : i % everyN != 0) continue;
            float x = xOfIdx(i);
            if (x < 20 * d || x > plotR - 20 * d) continue;
            g.text(fmtTime(c.get(i).epoch, gran, stepSec >= 86400), x, h - 5 * d, 10 * d, txtC, false, Gfx.CENTER);
        }

        // header: symbol and OHLC of the candle under the crosshair (or the newest)
        int hi2 = n - 1;
        if (crosshair) hi2 = Math.max(0, Math.min(n - 1, (int) Math.round(idxOfX(crossX))));
        Candle hc = c.get(hi2);
        g.text(title, 8 * d, 15 * d, 13 * d, headC, true, Gfx.LEFT);
        g.text("O " + Fmt.f(hc.open) + "  H " + Fmt.f(hc.high) + "  L " + Fmt.f(hc.low) + "  C " + Fmt.f(hc.close), 8 * d, 30 * d, 10.5f * d, hc.close >= hc.open ? UP : DOWN, false, Gfx.LEFT);
        if (live) { g.fillRound(plotR - 56 * d, 6 * d, plotR - 6 * d, 22 * d, 8 * d, 0x3326A69A); g.text("● LIVE", plotR - 31 * d, 18 * d, 10 * d, UP, true, Gfx.CENTER); }
        else if (!status.isEmpty()) g.text(status, plotR - 6 * d, 18 * d, 10 * d, 0xFFFFCA28, false, Gfx.RIGHT);

        // crosshair
        if (crosshair && crossX >= plotL && crossX <= plotR && crossY >= plotT && crossY <= plotB) {
            int ci = Math.max(0, Math.min(n - 1, (int) Math.round(idxOfX(crossX))));
            float cx = xOfIdx(ci);
            g.line(cx, plotT, cx, plotB, 1f, 0xAA90A4AE, true);
            g.line(plotL, crossY, plotR, crossY, 1f, 0xAA90A4AE, true);
            tagAt(g, crossY, Fmt.f(priceOfY(crossY)), 0xFF37474F, WHITE, d);
            drawRuler(g, d);
            String tt = fmtTime(c.get(ci).epoch, gran, true) + (gran < 86400 ? " " + fmtTime(c.get(ci).epoch, gran, false) : "");
            float tw = g.textWidth(tt, 10 * d, false) + 10 * d;
            float tx = Math.max(tw / 2, Math.min(plotR - tw / 2, cx));
            g.fillRound(tx - tw / 2, plotB, tx + tw / 2, h, 3 * d, 0xFF37474F);
            g.text(tt, tx, h - 5 * d, 10 * d, WHITE, false, Gfx.CENTER);
        }
    }

    /** while the crosshair is on: how far this price is from the trade's entry, in points, R and money */
    private void drawRuler(Gfx g, float d) {
        if (!showOverlay || overlay == null || overlay.positions.isEmpty()) return;
        Overlay.Position p = overlay.positions.get(0);
        for (Overlay.Position q : overlay.positions) if (q.now != null) { p = q; break; }
        double risk = Math.abs(p.entry - p.stop);
        if (risk <= 0) return;
        double pts = (priceOfY(crossY) - p.entry) * (p.buy ? 1 : -1);
        double r = pts / risk;
        String s = (pts >= 0 ? "+" : "-") + Fmt.f(Math.abs(pts)) + " pts   " + (r >= 0 ? "+" : "-") + Fmt.d1(Math.abs(r)) + "R"
            + (p.riskMoney > 0 ? "   " + (r >= 0 ? "+$" : "-$") + String.format(java.util.Locale.US, "%.2f", Math.abs(r * p.riskMoney)) : "") + "  from entry";
        float w = g.textWidth(s, 11 * d, true) + 14 * d;
        float x = plotL + 6 * d;
        float y = crossY - 24 * d < plotT ? crossY + 8 * d : crossY - 24 * d;
        g.fillRound(x, y, x + w, y + 18 * d, 6 * d, 0xEE263340);
        g.text(s, x + 7 * d, y + 13 * d, 11 * d, r >= 0 ? UP : DOWN, true, Gfx.LEFT);
    }

    private final ArrayList<Float> used = new ArrayList<>();

    private boolean near(float y, float gap) {
        for (float u : used) if (Math.abs(u - y) < gap) return true;
        return false;
    }

    /** draws an axis tag unless another tag is already there */
    private void tagU(Gfx g, double price, String text, int fill, int ink, float d) {
        if (price < pMin || price > pMax) return;
        float y = yOf(price);
        if (near(y, 15 * d)) return;
        tagAt(g, y, text, fill, ink, d);
        used.add(y);
    }

    private void tagAt(Gfx g, float y, String text, int fill, int ink, float d) {
        g.fillRound(plotR + 2 * d, y - 8 * d, plotR + 64 * d, y + 8 * d, 3 * d, fill);
        g.text(text, plotR + 6 * d, y + 4 * d, 10 * d, ink, true, Gfx.LEFT);
    }

    private static float clampY(float y, float lo, float hi) { return Math.max(lo, Math.min(hi, y)); }

    private void label(Gfx g, String text, float x, float baseline, float d, int color) {
        float tw = g.textWidth(text, 10 * d, true);
        x = Math.max(plotL + 5 * d, Math.min(x, plotR - tw - 6 * d)); // keep the whole label on screen
        g.fillRound(x - 3 * d, baseline - 11 * d, x + tw + 3 * d, baseline + 3 * d, 3 * d, 0xB30F1720);
        g.text(text, x, baseline, 10 * d, color, true, Gfx.LEFT);
    }

    /** phase 0 = boxes and lines (under the candles), phase 1 = labels (over them) */
    private void drawPosition(Gfx g, Overlay.Position p, float d, int phase) {
        float x0 = xOfIdx(idxOfEpoch(p.startEpoch));
        float x1 = x0 + (float) (26 * bw);
        if (x1 < plotL || x0 > plotR) return;
        x0 = Math.max(plotL, x0); x1 = Math.min(plotR, x1);
        if (x1 - x0 < 2) return;
        double risk = Math.abs(p.entry - p.stop);
        if (!(risk > 0)) return;
        double r1 = Math.abs(p.tp1 - p.entry) / risk;
        float ye = yOf(p.entry), ys = yOf(p.stop), yt = yOf(p.tp1);
        if (phase == 0) {
        g.fillRect(x0, Math.min(ye, ys), x1, Math.max(ye, ys), 0x40EF5350);
        g.fillRect(x0, Math.min(ye, yt), x1, Math.max(ye, yt), 0x4026A69A);
        if (p.tp2 != null) {
            float y2 = yOf(p.tp2);
            g.fillRect(x0, Math.min(yt, y2), x1, Math.max(yt, y2), 0x2226A69A);
            g.line(x0, y2, x1, y2, 1f, 0xCC26A69A, true);
        }
        g.line(x0, ye, x1, ye, Math.max(1f, d), entryFill, false);
        g.line(x0, ys, x1, ys, Math.max(1f, d), DOWN, false);
        g.line(x0, yt, x1, yt, Math.max(1f, d), UP, false);
            return;
        }

        final float lo0 = plotT + 14 * d, hi0 = plotB - 6 * d;
        String riskTxt = "Risk " + Fmt.f(risk) + (p.riskMoney > 0 ? "  -$" + money(p.riskMoney) : "");
        String rewTxt = "TP1 " + Fmt.d1(r1) + "R" + (p.riskMoney > 0 ? "  +$" + money(p.riskMoney * r1) : "");
        float cx = x0 + 6 * d;
        float rowH = 13 * d;
        // labels sit in the visible part of each box, on a dark pill so candles do not hide them
        if (Math.abs(ye - ys) > rowH) label(g, riskTxt, cx, clampY((ye + ys) / 2f + 4 * d, lo0, hi0), d, 0xFFFFCDD2);
        if (Math.abs(ye - yt) > rowH) {
            float top = Math.max(Math.min(ye, yt), plotT), bot = Math.min(Math.max(ye, yt), plotB);
            label(g, rewTxt, cx, clampY((top + bot) / 2f + 4 * d, lo0, hi0), d, 0xFFB2DFDB);
        } else label(g, rewTxt, cx, clampY(p.buy ? yt - 4 * d : yt + 12 * d, lo0, hi0), d, 0xFFB2DFDB);
        if (p.tp2 != null) {
            double r2 = Math.abs(p.tp2 - p.entry) / risk;
            label(g, "TP2 " + Fmt.d1(r2) + "R" + (p.riskMoney > 0 ? "  +$" + money(p.riskMoney * r2) : ""), cx, clampY(yOf(p.tp2) + (p.buy ? 12 * d : -4 * d), lo0, hi0), d, 0xFFB2DFDB);
        }
        // R:R pill and title above the entry line
        String pill = (!showNotes || p.title.isEmpty() ? "" : p.title + "  ") + "R:R 1:" + Fmt.d1(r1);
        float pw = g.textWidth(pill, 10 * d, true) + 10 * d;
        float px = Math.max(plotL + 2 * d, Math.min(x0, plotR - pw - 4 * d));
        g.fillRound(px, ye - 9 * d, px + pw, ye + 9 * d, 4 * d, 0xFF263544);
        g.text(pill, px + 5 * d, ye + 4 * d, 10 * d, 0xFFECEFF1, true, Gfx.LEFT);
        if (p.now != null) {
            double pr = (p.buy ? p.now - p.entry : p.entry - p.now) / risk;
            String run = (pr >= 0 ? "+" : "") + Fmt.d1(pr) + "R" + (p.riskMoney > 0 ? "  " + (pr >= 0 ? "+$" : "-$") + money(p.riskMoney * pr) : "");
            float yn = yOf(p.now);
            g.line(x0, yn, x1, yn, 1f, pr >= 0 ? UP : DOWN, true);
            float rw = g.textWidth(run, 10 * d, true) + 10 * d;
            g.fillRound(x1 - rw, yn - 8 * d, x1, yn + 8 * d, 4 * d, pr >= 0 ? UP : DOWN);
            g.text(run, x1 - rw + 5 * d, yn + 4 * d, 10 * d, WHITE, true, Gfx.LEFT);
        }
    }
}
