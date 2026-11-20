import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import java.nio.file.*;
import java.util.*;

public class RefineCompare {
    static List<Row> run(Path d, int tier) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            WalkForward.Data wd = new WalkForward.Data(); wd.symbol = "S" + k; wd.label = "Sim " + k;
            wd.m15 = ZoomTest.agg(m5, 3); wd.m30 = ZoomTest.agg(m5, 6); wd.h1 = ZoomTest.agg(m5, 12); wd.h4 = ZoomTest.agg(m5, 48); wd.m5 = m5;
            rows.addAll(WalkForward.run(wd, "t", 8, 400, tier).rows);
        }
        return rows;
    }
    static String line(String n, List<Row> rows) {
        List<Row> act = new ArrayList<>(); double risk = 0; int nr = 0;
        for (Row r : rows) if (r.actionable()) { act.add(r); if (r.entry != r.stop) { risk += Math.abs(r.entry - r.stop) / r.entry * 100; nr++; } }
        Kpi k = Kpi.compute(act);
        return String.format("%-28s ready %4d  trades %4d  win %5.1f%%  exp %+.2fR  net %+7.1fR  noFill %3d  avg stop %.3f%% of price", n, act.size(), k.completed, k.winRate, k.expectancy, k.netR, k.unfilled, nr == 0 ? 0 : risk / nr);
    }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        for (int tier = 0; tier <= 1; tier++) {
            System.out.println("== tier " + tier);
            Mie.strictAlign = true; Mie.refineOb = false; System.out.println(line("old (strict, no OB refine)", run(d, tier)));
            Mie.strictAlign = true; Mie.refineOb = true; System.out.println(line("OB refine, strict align", run(d, tier)));
            Mie.strictAlign = false; Mie.refineOb = true; System.out.println(line("OB refine + relaxed (new)", run(d, tier)));
        }
    }
}
