import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Compares the current rules with the top-trend filter and the break-even rule on the same history. */
public class VariantTest {
    static double[] run(Candle[] idea, Candle[] ltf, long ideaGran, long ltfGran, Candle[] next, long nextGran, Candle[] top, long topGran,
                        Candle[] m5, Candle[] m1, boolean sniper, boolean topReq, double be) {
        Mtf.Options o = new Mtf.Options();
        o.nextCandles = next; o.nextGran = nextGran; o.topCandles = top; o.topGran = topGran; o.ltfGran = ltfGran; o.refineWaitSec = 6 * 3600; o.breakEvenR = be;
        o.refine.add(new Mtf.Tier("5m", 300, m5)); o.refine.add(new Mtf.Tier("1m", 60, m1));
        o.filters = new Breakdowns.Filters();
        if (sniper) { o.minScore = 6; o.filters.minQuality = 5; o.filters.requireZoom = true; o.filters.require.add("trend"); o.filters.require.add("majorSweep"); o.filters.require.add("strongDisp"); }
        else { o.filters.minQuality = 4; o.filters.require.add("trend"); }
        if (topReq) o.filters.require.add("topTrend");
        double w = 0, l = 0, b = 0, r = 0;
        for (Mtf.FactorTrade f : Mtf.backtestMtfTrades(idea, ltf, ideaGran, new SmcConfig(), o)) {
            String oc = f.trade.outcome;
            if (oc.equals("win")) { w++; r += f.trade.r; } else if (oc.equals("loss")) { l++; r -= 1; } else if (oc.equals("be")) b++;
        }
        return new double[]{w, l, b, r};
    }
    static void add(double[] a, double[] x) { for (int i = 0; i < 4; i++) a[i] += x[i]; }
    static String fmt(double[] a) {
        double n = a[0] + a[1] + a[2];
        return String.format("%3.0f trades  %3.0f won %3.0f lost %3.0f breakeven | win rate %5.1f%% (of won+lost) | total %6.1fR | avg %5.2fR", n, a[0], a[1], a[2],
            a[0] + a[1] > 0 ? 100 * a[0] / (a[0] + a[1]) : 0, a[3], n > 0 ? a[3] / n : 0);
    }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        String[] names = {"A current rules", "B + top-trend must agree", "C current + break-even at 1R", "D top-trend + break-even at 1R"};
        for (int sn = 1; sn >= 0; sn--) {
            double[][] tot = new double[4][4];
            for (int k = 0; k < 6; k++) {
                Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
                Candle[] m1 = ZoomTest.split(m5), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48), d1 = ZoomTest.agg(m5, 288);
                for (int v = 0; v < 4; v++) {
                    boolean topReq = v == 1 || v == 3; double be = v >= 2 ? 1.0 : 0;
                    // 1H ideas: next = 4H, top = 1D ; 4H ideas: next = 1D (no top, so the top check is not applicable)
                    add(tot[v], run(h1, ZoomTest.agg(m5, 3), 3600, 900, h4, 14400, d1, 86400, m5, m1, sn == 1, topReq, be));
                    add(tot[v], run(h4, h1, 14400, 3600, d1, 86400, null, 0, m5, m1, sn == 1, false, be));
                }
            }
            System.out.println(sn == 1 ? "\n== MASTER (sniper) rules ==" : "\n== LOOSER rules (score 4, quality 4) ==");
            for (int v = 0; v < 4; v++) System.out.println(names[v] + ": " + fmt(tot[v]));
        }
    }
}
