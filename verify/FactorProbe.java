import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Do the top-trend, support/resistance and pattern checks (and the break-even rule) improve the results on the same trades? */
public class FactorProbe {
    static class Acc { int n, w, l, be; double r;
        void add(String oc, double rr) { if (oc.equals("win")) { w++; r += rr; } else if (oc.equals("loss")) { l++; r -= 1; } else if (oc.equals("be")) be++; else return; n++; }
        String s() { return String.format("%4d trades | won %3d lost %3d be %3d | win rate %5.1f%% | total %7.1fR | avg %5.2fR", n, w, l, be, w + l > 0 ? 100.0 * w / (w + l) : 0, r, n > 0 ? r / n : 0); } }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        String[] keys = {"trend", "topTrend", "srZone", "pattern"};
        Map<String, Acc> with = new LinkedHashMap<>(), without = new LinkedHashMap<>(), none = new LinkedHashMap<>();
        for (String k : keys) { with.put(k, new Acc()); without.put(k, new Acc()); none.put(k, new Acc()); }
        Acc all = new Acc(), allBe = new Acc(), allBe15 = new Acc();
        Acc both = new Acc(), bothBe = new Acc();
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m15 = ZoomTest.agg(m5, 3), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48), d1 = ZoomTest.agg(m5, 288);
            for (int pass = 0; pass < 2; pass++) {
                Mtf.Options o = new Mtf.Options();
                o.filters = new Breakdowns.Filters(); o.filters.minQuality = 0;
                List<Mtf.FactorTrade> ts;
                if (pass == 0) { o.nextCandles = h4; o.nextGran = 14400; o.topCandles = d1; o.topGran = 86400; o.ltfGran = 900; ts = Mtf.backtestMtfTrades(h1, m15, 3600, new SmcConfig(), o); }
                else { o.nextCandles = d1; o.nextGran = 86400; o.ltfGran = 3600; ts = Mtf.backtestMtfTrades(h4, h1, 14400, new SmcConfig(), o); }
                for (Mtf.FactorTrade t : ts) {
                    all.add(t.trade.outcome, t.trade.r);
                    Backtest.Trade b = Backtest.simulate(t.simCandles, t.simIdx, t.trade.setup, t.simWait, t.simHold, 1.0);
                    allBe.add(b.outcome, b.r);
                    boolean sr = t.breakdown.factor("srZone") != null && Boolean.TRUE.equals(t.breakdown.factor("srZone").pass);
                    boolean pt = t.breakdown.factor("pattern") != null && Boolean.TRUE.equals(t.breakdown.factor("pattern").pass);
                    if (sr && pt) { both.add(t.trade.outcome, t.trade.r); bothBe.add(b.outcome, b.r); }
                    for (String key : keys) {
                        Breakdown.Factor f = t.breakdown.factor(key);
                        Acc dst = f == null || f.pass == null ? none.get(key) : f.pass ? with.get(key) : without.get(key);
                        dst.add(t.trade.outcome, t.trade.r);
                    }
                }
            }
        }
        System.out.println("ALL trades (1H and 4H ideas, rules: score 4+, no quality filter)\n  as is:             " + all.s() + "\n  break-even at 1R:  " + allBe.s());
        for (String key : keys) {
            System.out.println("\nFactor " + key + ":\n  met:     " + with.get(key).s() + "\n  not met: " + without.get(key).s() + "\n  unknown: " + none.get(key).s());
        }
        System.out.println("\nS/R area AND pattern both agree:\n  as is:            " + both.s() + "\n  break-even at 1R: " + bothBe.s());
    }
}
