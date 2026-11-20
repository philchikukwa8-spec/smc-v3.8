import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import java.nio.file.*;
import java.util.*;

public class WfTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; if (fails < 15) System.out.println("FAIL: " + m); } }
    static Row mk(String id, String o, double r, long ts, long rs, double rr) { Row x = new Row(); x.id = id; x.state = Mie.S_QUALIFIED; x.outcome = o; x.resultR = r; x.ts = ts; x.resolvedTs = rs; x.rr = rr; x.filled = o.equals("win") || o.equals("loss"); return x; }

    public static void main(String[] a) throws Exception {
        // KPI hand check: 3 wins of 2R, 2 losses of 1R, one unfilled
        List<Row> rows = new ArrayList<>();
        rows.add(mk("1", "win", 2, 100, 200, 2)); rows.add(mk("2", "loss", -1, 300, 400, 2)); rows.add(mk("3", "win", 2, 500, 600, 2));
        rows.add(mk("4", "loss", -1, 700, 800, 2)); rows.add(mk("5", "win", 2, 900, 1000, 2)); rows.add(mk("6", "no_fill", 0, 1100, 0, 2));
        Kpi k = Kpi.compute(rows);
        ok("completed 5", k.completed == 5); ok("win rate 60", Math.abs(k.winRate - 60) < 1e-9);
        ok("avg win 2", Math.abs(k.avgWinR - 2) < 1e-9); ok("avg loss 1", Math.abs(k.avgLossR - 1) < 1e-9);
        ok("expectancy 0.6*2-0.4*1=0.8", Math.abs(k.expectancy - 0.8) < 1e-9);
        ok("profit factor 6/2=3", Math.abs(k.profitFactor - 3) < 1e-9);
        ok("net R 4", Math.abs(k.netR - 4) < 1e-9);
        ok("max drawdown 1", Math.abs(k.maxDrawdownR - 1) < 1e-9);
        ok("longest loss 1", k.longestLoss == 1); ok("longest win 1", k.longestWin == 1);
        ok("unfilled counted, not a trade", k.unfilled == 1 && k.completed == 5);
        ok("small sample flagged", k.reliability.startsWith("INSUFFICIENT"));
        double[] w = Kpi.wilson(8, 10); ok("wilson 8/10 ~ 0.49..0.94", w[0] > 0.48 && w[0] < 0.50 && w[1] > 0.93 && w[1] < 0.95);
        ok("empty KPI safe", Kpi.compute(new ArrayList<Row>()).reliability.equals("NO DATA"));
        // store
        ResearchStore st = new MemoryResearchStore(); st.upsert(rows.get(0)); st.put("version", "v1", "{}");
        ok("store get", st.get("1") != null && "{}".equals(st.get("version", "v1")) && st.keys("version").size() == 1);

        Path d = Paths.get(a[0]);
        int totalRows = 0, actionable = 0, mismatch = 0;
        for (int k2 = 0; k2 < 2; k2++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k2 + ".csv"));
            WalkForward.Data data = new WalkForward.Data();
            data.symbol = "R_75"; data.label = "Volatility 75";
            data.m5 = m5; data.m15 = ZoomTest.agg(m5, 3); data.m30 = ZoomTest.agg(m5, 6); data.h1 = ZoomTest.agg(m5, 12); data.h4 = ZoomTest.agg(m5, 48);
            WalkForward.Report rep = WalkForward.run(data, "mie-1", 3, 400);
            totalRows += rep.rows.size();
            for (Row r : rep.rows) {
                ok("sample label set", !r.sample.equals("live"));
                if (!r.actionable()) continue;
                actionable++;
                if (r.outcome.equals("win")) ok("win result equals planned rr", Math.abs(r.resultR - r.rr) < 1e-9);
                if (r.outcome.equals("loss")) ok("loss is -1R", r.resultR == -1);
                ok("mfe/mae non-negative", r.mfe >= 0 && r.mae >= 0);
                // parity with Backtest.simulate
                int i5 = 0; while (i5 < m5.length && m5[i5].epoch + 300 <= r.ts) i5++; i5--;
                Setup s = new Setup(); s.side = r.side; s.entry = r.entry; s.sl = r.stop; s.tp = r.target; s.rr = r.rr;
                Backtest.Trade t = Backtest.simulate(m5, i5, s, 72, 576);
                if (!t.outcome.equals(r.outcome) && !(t.outcome.equals("be"))) mismatch++;
            }
            if (k2 == 0) {
                System.out.println("windows " + rep.windows + " rows " + rep.rows.size() + " | ALL: " + rep.all.line());
                for (Map.Entry<String, Kpi> e : rep.bySample.entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue().line() + " [" + e.getValue().reliability + "]");
                for (int f = 0; f < rep.folds.size(); f++) System.out.println("  fold " + (f + 1) + ": " + rep.folds.get(f).line());
                Map<String, Kpi> byBand = Kpi.groupBy(rep.rows, r -> r.band);
                for (Map.Entry<String, Kpi> e : byBand.entrySet()) System.out.println("  band " + e.getKey() + ": " + e.getValue().line());
            }
        }
        ok("sim matches Backtest.simulate on every actionable row", mismatch == 0);
        System.out.println("rows " + totalRows + " actionable " + actionable + " mismatches " + mismatch);
        System.out.println((fails == 0 ? "WALK-FORWARD TESTS OK: " : "WALK-FORWARD TESTS FAILED: ") + checks + " checks, " + fails + " failures");
    }
}
