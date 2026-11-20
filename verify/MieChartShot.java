import com.smcscanner.chart.*;
import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import java.nio.file.*;
import java.util.*;

/** Draws the Advisor mini charts from real engine output (Java2D stands in for the phone canvas). */
public class MieChartShot {
    static int upTo(Candle[] c, long g, long asOf) { int n = 0; while (n < c.length && c[n].epoch + g <= asOf) n++; return n; }
    static Candle[] win(Candle[] c, int n, int keep) { return Arrays.copyOfRange(c, Math.max(0, n - keep), n); }
    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path d = Paths.get(a[0]); String out = a[1]; boolean dark = a.length < 3 || !a[2].equals("light");
        Set<String> done = new HashSet<>();
        for (int k = 0; k < 6 && done.size() < 3; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m15 = ZoomTest.agg(m5, 3), m30 = ZoomTest.agg(m5, 6), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
            for (int i = 400; i < m15.length && done.size() < 3; i++) {
                long asOf = m15[i].epoch + 900;
                Mie.Inputs in = new Mie.Inputs(); in.now = asOf; in.symbol = "R_" + k; in.label = "Volatility " + (k == 0 ? 75 : k == 1 ? 50 : 25);
                in.m15 = win(m15, i + 1, 300); in.m30 = win(m30, upTo(m30, 1800, asOf), 300); in.h1 = win(h1, upTo(h1, 3600, asOf), 300); in.h4 = win(h4, upTo(h4, 14400, asOf), 200);
                int n5 = upTo(m5, 300, asOf); in.m5 = win(m5, n5, 200);
                Mie.Assessment x = Mie.analyse(in);
                String kind = x.actionable() && x.rr >= 3 ? (x.isBuy() ? "buy" : "sell") : x.setupState.equals("WATCH") && x.reasons.contains("NO_CONFIRMATION") ? "watch" : "";
                if (kind.isEmpty() || !done.add(kind)) continue;
                ChartRenderer r = MieChart.build(x, in.m15, dark, 2f, 720);
                ChartShot.shot(r, out + "/mie_" + kind + (dark ? "" : "_light") + ".png", 720, 440);
                System.out.println(kind + ": " + x.headline);
            }
        }
    }
}
