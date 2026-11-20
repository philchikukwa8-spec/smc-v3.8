import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

public class ZoomSample {
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        final Candle[] m5 = BacktestParity.read(d.resolve("ltf_2.csv"));
        final Candle[] m1 = ZoomTest.split(m5), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
        Scan.Config cfg = new Scan.Config();
        cfg.markets.add(new Scan.Market("X", "Test"));
        cfg.timeframes.add(new Scan.Timeframe("1m", 60)); cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600)); cfg.timeframes.add(new Scan.Timeframe("4H", 14400));
        cfg.pairs.add(new Scan.Pair("1H", "5m", "1m"));
        cfg.entryMode = 1; cfg.riskMoney = 10; cfg.refineWaitSec = 3 * 3600;
        Lifecycle.State state = new Lifecycle.State();
        final long[] now = {0};
        Scan.Candles client = (sym, gran, count) -> {
            Candle[] src = gran == 60 ? m1 : gran == 300 ? m5 : gran == 3600 ? h1 : h4;
            int lo = 0, hi = src.length;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (src[mid].epoch <= now[0]) lo = mid + 1; else hi = mid; }
            return Arrays.copyOfRange(src, Math.max(0, lo - count), lo);
        };
        boolean printed = false;
        for (long t = h1[300].epoch; t < h1[1500].epoch; t += 300) {
            now[0] = t;
            for (Lifecycle.Msg m : Scan.scanAll(client, state, cfg, t).msgs) {
                m.apply.run();
                if (m.kind.equals("zone") || m.kind.equals("entry")) {
                    System.out.println(new java.text.SimpleDateFormat("d MMM HH:mm").format(new Date(t * 1000)) + "  [" + m.kind + "] " + m.title);
                    if (m.kind.equals("entry") && m.title.contains("1m") && !printed) { printed = true; System.out.println("\n" + m.body + "\n\n" + m.text + "\n"); }
                }
            }
        }
    }
}
