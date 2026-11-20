import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

public class SniperTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        // 1. preset content
        Scan.Config sn = Scan.Config.sniper();
        ok("sniper pairs only 1D/4H/1H", sn.pairs.size() == 3 && sn.pairs.get(0).setup.equals("1D") && sn.pairs.get(1).setup.equals("4H") && sn.pairs.get(2).setup.equals("1H"));
        for (Scan.Pair p : sn.pairs) ok("every sniper pair zooms to 5m and 1m " + p.label(), p.refine.contains("5m") && p.refine.contains("1m"));
        ok("sniper requires zoom", sn.requireZoom && sn.minScore >= 6 && sn.minQuality >= 5);
        ok("normal does not require zoom", !Scan.Config.defaults().requireZoom);

        // 2. backtest: every sniper trade must be a zoomed one, and never more trades than the normal run
        int normalN = 0, sniperN = 0, zoomedNormal = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m1 = ZoomTest.split(m5), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48), d1 = ZoomTest.agg(m5, 288);
            for (int mode = 0; mode < 2; mode++) {
                Mtf.Options o = new Mtf.Options(); o.nextCandles = d1; o.nextGran = 86400; o.ltfGran = 3600; o.refineWaitSec = 6 * 3600;
                o.refine.add(new Mtf.Tier("5m", 300, m5)); o.refine.add(new Mtf.Tier("1m", 60, m1));
                if (mode == 1) { o.filters = new Breakdowns.Filters(); o.filters.requireZoom = true; }
                List<Mtf.FactorTrade> t = Mtf.backtestMtfTrades(h4, h1, 14400, new SmcConfig(), o);
                if (mode == 0) { normalN += t.size(); for (Mtf.FactorTrade f : t) if (f.depth > 0) zoomedNormal++; }
                else { sniperN += t.size(); for (Mtf.FactorTrade f : t) ok("sniper trade is zoomed", f.depth > 0); }
            }
        }
        ok("sniper never has more trades than normal", sniperN <= normalN);
        ok("sniper count equals the zoomed normal trades", sniperN == zoomedNormal);
        System.out.println("backtest 4H>1H>5m>1m: normal " + normalN + " trades (" + zoomedNormal + " zoomed), sniper-zoom " + sniperN);

        // 3. live: with zoom required there is never an entry alert that skipped the 1m; without it, coarse entries do come through
        int plainEntries = 0, plainCoarse = 0, snEntries = 0, snCoarse = 0, snZone = 0;
        for (int k = 0; k < 6; k++) {
            final Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            final Candle[] m1 = ZoomTest.split(m5), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48), d1 = ZoomTest.agg(m5, 288);
            for (int mode = 0; mode < 2; mode++) {
                Scan.Config cfg = new Scan.Config();
                cfg.markets.add(new Scan.Market("X", "Test"));
                cfg.timeframes.add(new Scan.Timeframe("1m", 60)); cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600));
                cfg.timeframes.add(new Scan.Timeframe("4H", 14400)); cfg.timeframes.add(new Scan.Timeframe("1D", 86400));
                cfg.pairs.add(new Scan.Pair("1H", "5m", "1m"));
                cfg.entryMode = 1; cfg.riskMoney = 10; cfg.refineWaitSec = 0;
                cfg.requireZoom = mode == 1;
                Lifecycle.State state = new Lifecycle.State();
                final long[] now = {0};
                Scan.Candles client = (sym, gran, count) -> {
                    Candle[] src = gran == 60 ? m1 : gran == 300 ? m5 : gran == 3600 ? h1 : gran == 14400 ? h4 : d1;
                    int lo = 0, hi = src.length;
                    while (lo < hi) { int mid = (lo + hi) >>> 1; if (src[mid].epoch <= now[0]) lo = mid + 1; else hi = mid; }
                    return Arrays.copyOfRange(src, Math.max(0, lo - count), lo);
                };
                for (long t = h1[300].epoch; t < h1[1500].epoch; t += 300) {
                    now[0] = t;
                    Scan.Result r = Scan.scanAll(client, state, cfg, t);
                    ok("no scan errors", r.errors.isEmpty());
                    for (Lifecycle.Msg m : r.msgs) {
                        m.apply.run();
                        boolean zoomed = m.title.contains("> 1m");
                        if (m.kind.equals("entry")) {
                            if (mode == 0) { plainEntries++; if (!zoomed) plainCoarse++; }
                            else { snEntries++; if (!zoomed) snCoarse++; }
                        }
                        if (mode == 1 && m.kind.equals("zone")) snZone++;
                    }
                }
            }
        }
        System.out.println("live: normal " + plainEntries + " entry alerts (" + plainCoarse + " without the 1m); sniper " + snEntries + " entry alerts (" + snCoarse + " without the 1m), zone-ready alerts " + snZone);
        ok("sniper live: no entry alert skipped the zoom", snCoarse == 0);
        ok("sniper live: no more entries than normal", snEntries <= plainEntries);
        System.out.println(fails == 0 ? "SNIPER TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }
}
