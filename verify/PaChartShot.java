import com.smcscanner.chart.*;
import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import com.smcscanner.engine.pa.*;
import java.nio.file.*;
import java.util.*;

public class PaChartShot {
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        Set<String> want = new HashSet<>(Arrays.asList("Pattern", "Trendline", "Level"));
        for (int k = 0; k < 6 && !want.isEmpty(); k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
            for (int i = 300; i < h1.length && !want.isEmpty(); i += 3) {
                Candle[] w = Arrays.copyOfRange(h1, i - 299, i + 1);
                long now = h1[i].epoch + 3600;
                int j = 0; while (j < h4.length && h4[j].epoch + 14400 <= now) j++;
                Candle[] w4 = Arrays.copyOfRange(h4, Math.max(0, j - 200), j);
                for (PaEngine.Signal s : PaEngine.analyse("SIM", "Volatility 75", w4, w, now)) {
                    if (!s.actionable() || !want.contains(s.family) || s.rr < 1.5) continue;
                    if (s.family.equals("Pattern") && !s.scenario.contains("wedge") && !s.scenario.contains("Double")) continue;
                    want.remove(s.family);
                    ChartRenderer r = PaChart.build(s, w, true, 2f, 720);
                    ChartShot.shot(r, a[1] + "/pa_" + s.family.toLowerCase() + ".png", 720, 440);
                    System.out.println(s.family + ": " + s.headline + " | " + s.ctx + " | score " + s.score);
                }
            }
        }
    }
}
