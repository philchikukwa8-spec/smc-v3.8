import com.smcscanner.engine.*;
import com.smcscanner.engine.fib.*;
import com.smcscanner.chart.*;
import java.util.*;

public class FibShot {
    public static void main(String[] a) throws Exception {
        Random r = new Random(11);
        long t0 = 1_700_000_000L - (1_700_000_000L % 14400);
        double[][] wp = {{0,100},{200,103},{400,99},{600,104},{800,100.5},{1000,105},{1200,101},{1400,100.5},{1700,122},{1900,108},{1960,111.5},{1975,110.3},{2020,114},{2035,112.8},{2080,117.5},{2100,114.9}};
        Candle[] m15 = FibTest.path(wp, t0, r, 0.18);
        Candle[] h4 = FibTest.h4(m15);
        FibEngine.Setup s = null;
        for (FibEngine.Setup x : FibEngine.analyse("T", "Volatility 75", h4, m15, m15[m15.length - 1].epoch + 900, new FibEngine.Options())) if (x.isBuy()) s = x;
        for (int v = 0; v < 2; v++) {
            final boolean h4v = v == 1;
            ChartRenderer rd = FibChart.build(s, h4v ? h4 : m15, h4v, true, 2f, 760);
            ChartShot.shot(rd, a[0] + (h4v ? "/fib_h4.png" : "/fib_m15.png"), 760, 520);
        }
    }
}
