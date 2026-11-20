import com.smcscanner.chart.*;
import com.smcscanner.engine.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Draws the real chart code to PNG files (Java2D stands in for the phone's canvas). */
public class ChartShot {
    static class J2D implements Gfx {
        final Graphics2D g; final float sx;
        J2D(Graphics2D g) { this.g = g; this.sx = 1; g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); }
        Color col(int a) { return new Color((a >> 16) & 255, (a >> 8) & 255, a & 255, (a >>> 24) & 255); }
        public void clip(float l, float t, float r, float b) { g.setClip((int) l, (int) t, (int) (r - l), (int) (b - t)); }
        public void unclip() { g.setClip(null); }
        public void fillRect(float l, float t, float r, float b, int c) { g.setColor(col(c)); g.fill(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t)); }
        public void strokeRect(float l, float t, float r, float b, float w, int c) { g.setColor(col(c)); g.setStroke(new BasicStroke(w)); g.draw(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t)); }
        public void fillRound(float l, float t, float r, float b, float rad, int c) { g.setColor(col(c)); g.fill(new java.awt.geom.RoundRectangle2D.Float(l, t, r - l, b - t, rad * 2, rad * 2)); }
        public void line(float x1, float y1, float x2, float y2, float w, int c, boolean dashed) {
            g.setColor(col(c));
            g.setStroke(dashed ? new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{8, 6}, 0) : new BasicStroke(w));
            g.draw(new java.awt.geom.Line2D.Float(x1, y1, x2, y2));
        }
        Font font(float size, boolean bold) { return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, Math.round(size)); }
        public void text(String s, float x, float y, float size, int c, boolean bold, int align) {
            g.setFont(font(size, bold)); g.setColor(col(c));
            float w = g.getFontMetrics().stringWidth(s);
            g.drawString(s, align == CENTER ? x - w / 2 : align == RIGHT ? x - w : x, y);
        }
        public float textWidth(String s, float size, boolean bold) { g.setFont(font(size, bold)); return g.getFontMetrics().stringWidth(s); }
    }

    public static void shot(ChartRenderer r, String file, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.draw(new J2D(g), w, h);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path d = Paths.get(a[0]); String outDir = a[1];
        Candle[] m5x = null; long at = 0;
        for (int k = 0; k < 6 && at == 0; k++) {
            Candle[] mm5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv")), mm1 = ZoomTest.split(mm5);
            Mtf.Options zoom = new Mtf.Options(); zoom.nextCandles = ZoomTest.agg(mm5, 48); zoom.nextGran = 14400; zoom.ltfGran = 300; zoom.refine.add(new Mtf.Tier("1m", 60, mm1)); zoom.refineWaitSec = 6 * 3600;
            for (Mtf.FactorTrade t : Mtf.backtestMtfTrades(ZoomTest.agg(mm5, 12), mm5, 3600, new SmcConfig(), zoom)) if (t.depth > 0) { at = t.trade.setup.triggerEpoch; m5x = mm5; break; }
        }
        final Candle[] m5 = m5x;
        final Candle[] m1 = ZoomTest.split(m5), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
        Scan.Config cfg = new Scan.Config();
        cfg.markets.add(new Scan.Market("X", "Test"));
        cfg.timeframes.add(new Scan.Timeframe("1m", 60)); cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600)); cfg.timeframes.add(new Scan.Timeframe("4H", 14400));
        cfg.pairs.add(new Scan.Pair("1H", "5m", "1m"));
        cfg.entryMode = 1; cfg.riskMoney = 10; cfg.refineWaitSec = 6 * 3600;
        Lifecycle.State state = new Lifecycle.State();
        final long[] now = {0};
        Scan.Candles client = (sym, gran, count) -> {
            Candle[] src = gran == 60 ? m1 : gran == 300 ? m5 : gran == 3600 ? h1 : h4;
            int lo = 0, hi = src.length;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (src[mid].epoch <= now[0]) lo = mid + 1; else hi = mid; }
            return Arrays.copyOfRange(src, Math.max(0, lo - count), lo);
        };
        long found = 0;
        for (long t = at - 30 * 3600; t < at + 8 * 3600 && found == 0; t += 300) {
            now[0] = t;
            for (Lifecycle.Msg m : Scan.scanAll(client, state, cfg, t).msgs) { m.apply.run(); if (m.kind.equals("entry")) found = t; }
        }
        System.out.println("entry alert at " + found);
        // log it as an open trade a little later and draw everything at that moment
        long later = found + 4 * 60;
        now[0] = later;
        List<Manage.Trade> trades = new ArrayList<>();
        for (Lifecycle.Tracked t : state.tracked.values()) if (t.entry != null) {
            Manage.Trade tr = Manage.create(t.entry, "X", "Test", "1H", t.finalTf, t.finalTf.equals("1m") ? 60 : 300, t.entry.entry, t.breakdown.levels.tp2, found, 10);
            int hi = 0; while (hi < m1.length && m1[hi].epoch <= later) hi++;
            Manage.update(tr, Arrays.copyOfRange(m1, Math.max(0, hi - 200), hi), later, new SmcConfig());
            trades.add(tr);
        }
        Overlay ov = OverlayBuilder.build(state, trades, "X", 10, 3, null);
        System.out.println("overlay: zones " + ov.zones.size() + ", levels " + ov.levels.size() + ", positions " + ov.positions.size());
        String[] names = {"1m", "5m", "1H"}; Candle[][] src = {m1, m5, h1}; long[] gr = {60, 300, 3600};
        for (int k = 0; k < 3; k++) {
            int hi = 0; while (hi < src[k].length && src[k][hi].epoch <= later) hi++;
            ChartRenderer r = new ChartRenderer();
            r.density = 2.6f;
            r.setCandles(Arrays.copyOfRange(src[k], Math.max(0, hi - 400), hi), gr[k]);
            r.title = "Test  " + names[k];
            r.overlay = ov; r.live = true; r.nowSec = later + 20;
            r.bw = k == 0 ? 11 : k == 1 ? 14 : 16;
            r.shift = 14;
            shot(r, outDir + "/chart_" + names[k] + ".png", 1080, 1500);
        }
        // crosshair example
        int hi = 0; while (hi < m5.length && m5[hi].epoch <= later) hi++;
        ChartRenderer r = new ChartRenderer(); r.density = 2.6f; r.setCandles(Arrays.copyOfRange(m5, Math.max(0, hi - 400), hi), 300); r.title = "Test  5m"; r.overlay = ov; r.live = true; r.nowSec = later; r.bw = 14; r.shift = 14;
        BufferedImage tmp = new BufferedImage(1080, 1500, BufferedImage.TYPE_INT_ARGB); r.draw(new J2D(tmp.createGraphics()), 1080, 1500); // lay out once
        r.setCrosshair(500, 700);
        shot(r, outDir + "/chart_crosshair.png", 1080, 1500);
        // light theme
        ChartRenderer lt = new ChartRenderer(); lt.density = 2.6f; lt.setDark(false); lt.setCandles(Arrays.copyOfRange(m5, Math.max(0, hi - 400), hi), 300); lt.title = "Test  5m"; lt.overlay = ov; lt.live = true; lt.nowSec = later; lt.bw = 14; lt.shift = 14;
        BufferedImage tmp2 = new BufferedImage(1080, 1500, BufferedImage.TYPE_INT_ARGB); lt.draw(new J2D(tmp2.createGraphics()), 1080, 1500);
        lt.setCrosshair(500, 700);
        shot(lt, outDir + "/chart_light.png", 1080, 1500);
    }
}
