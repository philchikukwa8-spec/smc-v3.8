import com.smcscanner.engine.*;
import com.smcscanner.engine.fib.*;
import com.smcscanner.engine.mie.*;
import java.nio.file.*;
import java.util.*;

public class FibReplayTest {
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int tot = 0; List<Row> all = new ArrayList<>(); int fails = 0, checks = 0;
        FibEngine.Options o = new FibEngine.Options();
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m15 = ZoomTest.agg(m5, 3), h4 = ZoomTest.agg(m5, 48);
            FibReplay.Report r = FibReplay.run("S" + k, "Sim " + k, m15, h4, 4, o);
            System.out.println("series " + k + ": m15=" + m15.length + " windows=" + r.windows + " setups=" + r.rows.size() + (r.all != null ? "  " + r.all.line() : ""));
            all.addAll(r.rows);
        }
        for (Row r : all) {
            checks++; if (!(r.mode.equals("FIB") && r.frame.equals("15m") && r.actionable() && !r.sample.isEmpty())) fails++;
            checks++; if (r.side.equals("BUY") ? !(r.stop < r.entry && r.target > r.entry) : !(r.stop > r.entry && r.target < r.entry)) { fails++; System.out.println("bad levels " + r.id); }
            checks++; if (r.decided() && !(r.resultR > -1.5 && r.resultR < 50)) { fails++; System.out.println("odd R " + r.resultR); }
        }
        System.out.println("ALL " + Kpi.compute(all).line());
        o.targetH4 = true;
        List<Row> h = new ArrayList<>();
        for (int k = 0; k < 6; k++) { Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv")); h.addAll(FibReplay.run("S" + k, "Sim " + k, ZoomTest.agg(m5, 3), ZoomTest.agg(m5, 48), 4, o).rows); }
        System.out.println("H4 target " + Kpi.compute(h).line());
        System.out.println("FIB REPLAY TESTS: " + checks + " checks, " + fails + " failures");
    }
}
