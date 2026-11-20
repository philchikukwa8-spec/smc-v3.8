import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Tests the 4H idea > 1H entry zone > 5m > 1m zoom-in, on a 1-minute series that rolls up exactly into the 5m series. */
public class ZoomTest {
    static int fails = 0, checks = 0;
    static void ok(String w, boolean c) { checks++; if (!c) { fails++; System.out.println("  FAIL " + w); } }

    /** splits each 5m candle into five 1m candles that stay inside it and keep its exact high, low, open and close */
    static Candle[] split(Candle[] five) {
        List<Candle> out = new ArrayList<>();
        for (Candle c : five) {
            double[] w = {c.open, c.close >= c.open ? c.low : c.high, c.close >= c.open ? c.high : c.low, c.close};
            double[] tt = {0, 1.5, 3.5, 5};
            for (int k = 0; k < 5; k++) {
                double a = at(w, tt, k), b = at(w, tt, k + 1), hi = Math.max(a, b), lo = Math.min(a, b);
                for (int q = 0; q < 4; q++) if (tt[q] > k && tt[q] < k + 1) { hi = Math.max(hi, w[q]); lo = Math.min(lo, w[q]); }
                out.add(new Candle(c.epoch + 60L * k, a, hi, lo, b));
            }
        }
        return out.toArray(new Candle[0]);
    }
    static double at(double[] w, double[] tt, double t) {
        for (int i = 0; i < 3; i++) if (t <= tt[i + 1]) return w[i] + (w[i + 1] - w[i]) * (t - tt[i]) / (tt[i + 1] - tt[i]);
        return w[3];
    }
    static Candle[] agg(Candle[] c, int k) {
        List<Candle> o = new ArrayList<>();
        for (int i = 0; i + k <= c.length; i += k) {
            double hi = -1e18, lo = 1e18;
            for (int j = i; j < i + k; j++) { hi = Math.max(hi, c[j].high); lo = Math.min(lo, c[j].low); }
            o.add(new Candle(c[i].epoch, c[i].open, hi, lo, c[i + k - 1].close));
        }
        return o.toArray(new Candle[0]);
    }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int trades = 0, zoomed = 0, deeper1m = 0; double cutSum = 0; int cutN = 0, tighter = 0;
        double totalZoom = 0, totalPlain = 0; int filledZoom = 0, filledPlain = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m1 = split(m5);
            Candle[] h1 = agg(m5, 12), h4 = agg(m5, 48), d1 = agg(m5, 288);
            // the rolled-up series must reproduce the 5m series exactly
            Candle[] back = agg(m1, 5);
            ok("1m rolls up into 5m " + k, back.length == m5.length && Math.abs(back[1234].high - m5[1234].high) < 1e-9 && Math.abs(back[777].low - m5[777].low) < 1e-9);
            // keep the 1m window short (about 90 days of 5m is plenty) so the test is quick
            Mtf.Options plain = new Mtf.Options(); plain.nextCandles = d1; plain.nextGran = 86400; plain.ltfGran = 3600;
            Mtf.Options zoom = new Mtf.Options(); zoom.nextCandles = d1; zoom.nextGran = 86400; zoom.ltfGran = 3600;
            zoom.refine.add(new Mtf.Tier("5m", 300, m5)); zoom.refine.add(new Mtf.Tier("1m", 60, m1));
            List<Mtf.FactorTrade> p = Mtf.backtestMtfTrades(h4, h1, 14400, new SmcConfig(), plain);
            List<Mtf.FactorTrade> z = Mtf.backtestMtfTrades(h4, h1, 14400, new SmcConfig(), zoom);
            ok("same number of trades " + k + " (" + p.size() + " vs " + z.size() + ")", p.size() == z.size());
            for (int i = 0; i < Math.min(p.size(), z.size()); i++) {
                Mtf.FactorTrade pt = p.get(i), zt = z.get(i);
                trades++;
                Setup ps = pt.trade.setup, zs = zt.trade.setup;
                ok("same side", ps.side.equals(zs.side));
                ok("target unchanged", Math.abs(ps.tp - zs.tp) < 1e-9);
                if (zt.depth > 0) {
                    zoomed++;
                    boolean buy = zs.isBuy();
                    ok("stop inside the first entry zone's stop", buy ? zs.sl > ps.sl - 1e-9 : zs.sl < ps.sl + 1e-9);
                    ok("coarse risk recorded", zt.coarseRisk > 0);
                    ok("ladder has depth+1 rungs", zt.breakdown.ladder.size() == zt.depth + 1);
                    ok("path text", !zt.breakdown.path.isEmpty());
                    ok("target in breakdown unchanged", Math.abs(zt.breakdown.levels.tp1 - pt.breakdown.levels.tp1) < 1e-9);
                    double risk = Math.abs(zs.entry - zs.sl);
                    cutSum += 1 - risk / zt.coarseRisk; cutN++;
                    if (risk < zt.coarseRisk) tighter++;
                    if (zt.depth == 2) deeper1m++;
                }
                if (pt.trade.decided()) { filledPlain++; totalPlain += pt.trade.r; }
                if (zt.trade.decided()) { filledZoom++; totalZoom += zt.trade.r; }
            }
        }
        // second ladder with more setups: 1H idea > 5m entry zone > 1m
        int t2 = 0, z2 = 0, tight2 = 0; double cut2 = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv")), m1 = split(m5), h1 = agg(m5, 12), h4 = agg(m5, 48);
            Mtf.Options plain = new Mtf.Options(); plain.nextCandles = h4; plain.nextGran = 14400; plain.ltfGran = 300;
            Mtf.Options zoom = new Mtf.Options(); zoom.nextCandles = h4; zoom.nextGran = 14400; zoom.ltfGran = 300; zoom.refine.add(new Mtf.Tier("1m", 60, m1));
            List<Mtf.FactorTrade> p = Mtf.backtestMtfTrades(h1, m5, 3600, new SmcConfig(), plain), z = Mtf.backtestMtfTrades(h1, m5, 3600, new SmcConfig(), zoom);
            ok("same trades 2nd ladder", p.size() == z.size());
            for (int i = 0; i < Math.min(p.size(), z.size()); i++) {
                t2++; Setup ps = p.get(i).trade.setup, zs = z.get(i).trade.setup;
                ok("2nd target unchanged", Math.abs(ps.tp - zs.tp) < 1e-9);
                if (z.get(i).depth > 0) {
                    z2++; boolean buy = zs.isBuy();
                    ok("2nd stop inside", buy ? zs.sl > ps.sl - 1e-9 : zs.sl < ps.sl + 1e-9);
                    ok("2nd entry zone sweep inside parent zone", buy ? zs.sweepLevel <= ps.zoneHigh : zs.sweepLevel >= ps.zoneLow);
                    double r = Math.abs(zs.entry - zs.sl); cut2 += 1 - r / z.get(i).coarseRisk; if (r < z.get(i).coarseRisk) tight2++;
                }
            }
        }
        System.out.println("1H idea > 5m > 1m: trades " + t2 + ", zoomed " + z2 + ", tighter " + tight2 + (z2 > 0 ? String.format(", average risk cut %.0f%%", 100 * cut2 / z2) : ""));
        System.out.println("Trades " + trades + ", zoomed in on " + zoomed + " (down to 1m: " + deeper1m + "), tighter risk in " + tighter + ", average risk cut " + (cutN > 0 ? String.format("%.0f%%", 100 * cutSum / cutN) : "n/a"));
        System.out.println("Plain 1H entries: filled " + filledPlain + ", total " + String.format("%.1f", totalPlain) + "R   Zoomed: filled " + filledZoom + ", total " + String.format("%.1f", totalZoom) + "R");

        // live replay (slow; skipped when a second argument is given)
        for (int k = 0; k < (a.length > 1 ? 0 : 6); k++) {
            final Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            final Candle[] m1 = split(m5), h1 = agg(m5, 12), h4 = agg(m5, 48), d1 = agg(m5, 288);
            Scan.Config cfg = new Scan.Config();
            cfg.markets.add(new Scan.Market("X", "Test"));
            cfg.timeframes.add(new Scan.Timeframe("1m", 60)); cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600));
            cfg.timeframes.add(new Scan.Timeframe("4H", 14400)); cfg.timeframes.add(new Scan.Timeframe("1D", 86400));
            cfg.pairs.add(new Scan.Pair("1H", "5m", "1m"));
            cfg.entryMode = 1; cfg.riskMoney = 10;
            Lifecycle.State state = new Lifecycle.State();
            final long[] now = {0};
            Scan.Candles client = (sym, gran, count) -> {
                Candle[] src = gran == 60 ? m1 : gran == 300 ? m5 : gran == 3600 ? h1 : gran == 14400 ? h4 : d1;
                int n = 0, lo = 0, hi = src.length;
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (src[mid].epoch <= now[0]) lo = mid + 1; else hi = mid; }
                n = lo;
                return Arrays.copyOfRange(src, Math.max(0, n - count), n);
            };
            Map<String, Integer> kinds = new TreeMap<>();
            String sample = null;
            // step every 5 minutes through ~2 months so 4H ideas, zones and entries can all appear
            for (long t = h1[300].epoch; t < h1[1500].epoch; t += 300) {
                now[0] = t;
                Scan.Result r = Scan.scanAll(client, state, cfg, t);
                ok("no scan errors", r.errors.isEmpty());
                for (Lifecycle.Msg m : r.msgs) {
                    m.apply.run();
                    kinds.merge(m.kind, 1, Integer::sum);
                    if (m.kind.equals("entry") && m.title.contains("1m") && sample == null) sample = m.title + "\n" + m.body + "\n\n" + m.text;
                }
            }
            System.out.println("series " + k + " alerts: " + kinds);
            if (sample != null) { System.out.println("\n===== sample precision entry alert =====\n" + sample); break; }
        }
        System.out.println(fails == 0 ? "ZOOM TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }
}
