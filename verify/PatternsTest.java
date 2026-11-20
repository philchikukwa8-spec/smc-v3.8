import com.smcscanner.engine.*;
import java.util.*;

public class PatternsTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    /** waypoints {index, price}; candles interpolate between them, a little wick on every candle; a lead-in of flat candles first */
    static Candle[] series(double[][] w, int lead, boolean mirror) {
        int last = (int) w[w.length - 1][0];
        List<Candle> out = new ArrayList<>();
        double start = w[0][1];
        double prev = start;
        for (int i = 0; i < lead; i++) { out.add(new Candle(i * 3600L, prev, prev + 0.4, prev - 0.4, prev)); }
        for (int i = 0; i <= last; i++) {
            double p = w[0][1];
            for (int k = 0; k + 1 < w.length; k++) {
                if (i >= w[k][0] && i <= w[k + 1][0]) { double t = (i - w[k][0]) / (w[k + 1][0] - w[k][0]); p = w[k][1] + t * (w[k + 1][1] - w[k][1]); }
            }
            double o = prev;
            out.add(new Candle((lead + i) * 3600L, o, Math.max(o, p) + 0.5, Math.min(o, p) - 0.5, p));
            prev = p;
        }
        Candle[] c = out.toArray(new Candle[0]);
        if (mirror) for (int i = 0; i < c.length; i++) { Candle x = c[i]; c[i] = new Candle(x.epoch, 200 - x.open, 200 - x.low, 200 - x.high, 200 - x.close); }
        return c;
    }
    static Patterns.Pattern find(List<Patterns.Pattern> l, String name) { for (Patterns.Pattern p : l) if (p.name.equals(name)) return p; return null; }

    public static void main(String[] a) {
        double[][] dt = {{0, 60}, {10, 100}, {20, 60}, {30, 101}, {40, 80}};
        List<Patterns.Pattern> r = Patterns.detect(series(dt, 25, false));
        Patterns.Pattern p = find(r, "Double top");
        ok("double top found", p != null);
        if (p != null) { ok("double top forming + SELL", p.status.equals("forming") && p.bias.equals("SELL")); ok("double top neckline ~60", Math.abs(p.level - 59.5) < 1.5); ok("double top target below neckline", p.target < p.level); }
        double[][] dtc = {{0, 60}, {10, 100}, {20, 60}, {30, 101}, {40, 80}, {47, 55}};
        p = find(Patterns.detect(series(dtc, 25, false)), "Double top");
        ok("double top confirmed after neckline close", p != null && p.status.equals("confirmed"));
        p = find(Patterns.detect(series(dt, 25, true)), "Double bottom");
        ok("double bottom found (mirror)", p != null && p.bias.equals("BUY") && p.status.equals("forming") && p.level > p.target - 1000);
        if (p != null) ok("double bottom target above neckline", p.target > p.level);

        double[][] hs = {{0, 60}, {10, 90}, {15, 70}, {22, 110}, {30, 70}, {38, 90}, {46, 75}};
        p = find(Patterns.detect(series(hs, 25, false)), "Head and shoulders");
        ok("head and shoulders found", p != null && p.status.equals("forming") && p.bias.equals("SELL"));
        if (p != null) ok("H&S neckline near 70", Math.abs(p.level - 70) < 3 && p.target < p.level);
        double[][] hsc = {{0, 60}, {10, 90}, {15, 70}, {22, 110}, {30, 70}, {38, 90}, {46, 75}, {52, 55}};
        p = find(Patterns.detect(series(hsc, 25, false)), "Head and shoulders");
        ok("H&S confirmed after neckline close", p != null && p.status.equals("confirmed"));
        p = find(Patterns.detect(series(hs, 25, true)), "Inverse head and shoulders");
        ok("inverse H&S found (mirror)", p != null && p.bias.equals("BUY"));

        double[][] at = {{0, 70}, {5, 60}, {15, 100}, {25, 70}, {35, 100}, {45, 80}, {55, 100}, {60, 92}};
        p = find(Patterns.detect(series(at, 25, false)), "Ascending triangle");
        ok("ascending triangle found", p != null && p.bias.equals("BUY") && p.status.equals("forming"));
        p = find(Patterns.detect(series(at, 25, true)), "Descending triangle");
        ok("descending triangle found (mirror)", p != null && p.bias.equals("SELL"));
        double[][] atb = {{0, 70}, {5, 60}, {15, 100}, {25, 70}, {35, 100}, {45, 80}, {55, 100}, {60, 92}, {64, 112}};
        p = find(Patterns.detect(series(atb, 25, false)), "Ascending triangle");
        ok("triangle confirmed on a close above the line", p != null && p.status.equals("confirmed") && p.bias.equals("BUY"));

        // zones: a range between 100 and 150 touched several times
        double[][] rg = {{0, 125}, {6, 100}, {14, 150}, {22, 100}, {30, 150}, {38, 101}, {46, 149}, {54, 125}};
        Candle[] c = series(rg, 40, false);
        List<Patterns.Zone> z = Patterns.keyZones(c, 6);
        boolean low = false, high = false;
        for (Patterns.Zone x : z) { if (x.low < 102 && x.high > 99 && x.touches >= 2) low = true; if (x.high > 148 && x.low < 151 && x.touches >= 2) high = true; ok("zone low < high", x.low < x.high); }
        ok("support zone near 100 and resistance near 150 found", low && high);

        // random data: never crashes, values are finite and ordered
        Random rnd = new Random(7);
        int found = 0;
        for (int s = 0; s < 200; s++) {
            Candle[] x = new Candle[400];
            double px = 1000;
            for (int i = 0; i < x.length; i++) {
                double o = px; px += rnd.nextGaussian() * 3;
                x[i] = new Candle(i * 3600L, o, Math.max(o, px) + rnd.nextDouble() * 2, Math.min(o, px) - rnd.nextDouble() * 2, px);
            }
            for (int cut = 80; cut <= 400; cut += 40) {
                Candle[] y = Arrays.copyOf(x, cut);
                for (Patterns.Pattern q : Patterns.detect(y)) {
                    found++;
                    ok("finite level/target", !Double.isNaN(q.level) && !Double.isNaN(q.target) && !Double.isInfinite(q.level));
                    ok("status valid", q.status.equals("forming") || q.status.equals("confirmed"));
                    ok("pattern ends before the last candle", q.endEpoch <= y[y.length - 1].epoch);
                }
                for (Patterns.Zone q : Patterns.keyZones(y, 6)) ok("zone valid", q.low < q.high && q.touches >= 2);
            }
        }
        System.out.println("random series: " + found + " patterns seen across 1,200 windows");
        System.out.println(fails == 0 ? "PATTERN TESTS OK: " + checks + " checks" : "PATTERN TESTS FAILED: " + fails + " of " + checks);
    }
}
