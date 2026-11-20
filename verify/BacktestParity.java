import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Checks Java backtest, top-down backtest, breakdown factors and factor report against TypeScript output. */
public class BacktestParity {
    static int fails = 0, checks = 0;
    static void eq(String w, double a, double b) { checks++; if (!(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b)))) { fails++; if (fails < 20) System.out.println("  MISMATCH " + w + ": java=" + a + " ts=" + b); } }
    static void eqs(String w, String a, String b) { checks++; if (!a.equals(b)) { fails++; if (fails < 20) System.out.println("  MISMATCH " + w + ": java=" + a + " ts=" + b); } }
    public static Candle[] read(Path p) throws Exception {
        List<Candle> o = new ArrayList<>();
        for (String l : Files.readAllLines(p)) { if (l.isEmpty()) continue; String[] t = l.split(","); o.add(new Candle(Long.parseLong(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]))); }
        return o.toArray(new Candle[0]);
    }
    static void res(String w, Backtest.Result r, String[] t) {
        eq(w + " setups", r.setups, Double.parseDouble(t[2])); eq(w + " filled", r.filled, Double.parseDouble(t[3]));
        eq(w + " wins", r.wins, Double.parseDouble(t[4])); eq(w + " losses", r.losses, Double.parseDouble(t[5]));
        eq(w + " winRate", r.winRate, Double.parseDouble(t[6])); eq(w + " avgR", r.avgR, Double.parseDouble(t[7]));
        eq(w + " totalR", r.totalR, Double.parseDouble(t[8])); eq(w + " streak", r.maxLosingStreak, Double.parseDouble(t[9]));
    }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        List<String[]> exp = new ArrayList<>();
        for (String l : Files.readAllLines(d.resolve("expected_bt.csv"))) if (!l.isEmpty()) exp.add(l.split(","));
        SmcConfig cfg = new SmcConfig();
        int trades = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] ltf = read(d.resolve("ltf_" + k + ".csv")), htf = read(d.resolve("htf_" + k + ".csv")), next = read(d.resolve("next_" + k + ".csv"));
            List<String[]> mine = new ArrayList<>();
            for (String[] t : exp) if (Integer.parseInt(t[1]) == k) mine.add(t);
            for (String[] t : mine) if (t[0].equals("single")) res("single " + k, Backtest.backtest(htf, cfg), t);
            Mtf.Options o = new Mtf.Options(); o.nextCandles = next; o.nextGran = 14400; o.ltfGran = 300;
            List<Mtf.FactorTrade> ft = Mtf.backtestMtfTrades(htf, ltf, 3600, cfg, o);
            List<Backtest.Trade> bt = new ArrayList<>(); for (Mtf.FactorTrade f : ft) bt.add(f.trade);
            for (String[] t : mine) if (t[0].equals("mtf")) res("mtf " + k, Backtest.summarize(bt), t);
            int idx = 0;
            for (String[] t : mine) {
                if (!t[0].equals("trade")) continue;
                if (idx >= ft.size()) { fails++; System.out.println("  MISSING trade " + idx + " in series " + k); continue; }
                Mtf.FactorTrade f = ft.get(idx++); trades++;
                String w = "trade " + k + "/" + idx;
                eq(w + " trigger", f.trade.setup.triggerEpoch, Double.parseDouble(t[2]));
                eqs(w + " outcome", f.trade.outcome, t[3]); eq(w + " r", f.trade.r, Double.parseDouble(t[4]));
                eqs(w + " grade", f.breakdown.grade, t[5]);
                StringBuilder q = new StringBuilder();
                for (Breakdown.Factor x : f.breakdown.factors) if (x.group.equals("quality")) q.append(x.pass == null ? 'n' : x.pass ? '1' : '0');
                eqs(w + " factors", q.toString(), t[8]);
                if (t[9].equals("null")) { checks++; if (f.breakdown.levels.tp2 != null) { fails++; System.out.println("  MISMATCH " + w + " tp2 should be null"); } }
                else { checks++; if (f.breakdown.levels.tp2 == null) { fails++; System.out.println("  MISMATCH " + w + " tp2 missing"); } else eq(w + " tp2", f.breakdown.levels.tp2, Double.parseDouble(t[9])); }
                eq(w + " entry", f.breakdown.levels.entry, Double.parseDouble(t[10])); eq(w + " stop", f.breakdown.levels.stop, Double.parseDouble(t[11]));
            }
            eq("trade count " + k, ft.size(), idx);
            Factors.Report rep = Factors.report(ft, 5);
            int fi = 0, gi = 0;
            for (String[] t : mine) {
                if (t[0].equals("factor")) { Factors.Row r = rep.factors.get(fi++); eqs("factor key", r.key, t[2]); eq("factor withN", r.withN, Double.parseDouble(t[3])); eq("factor withAvg", r.withAvgR, Double.parseDouble(t[4])); eq("factor woN", r.withoutN, Double.parseDouble(t[5])); eq("factor woAvg", r.withoutAvgR, Double.parseDouble(t[6])); eq("factor edge", r.edge, Double.parseDouble(t[7])); eqs("factor enough", String.valueOf(r.enough), t[8]); }
                if (t[0].equals("grade")) { Factors.GradeRow g = rep.grades.get(gi++); eqs("grade", g.grade, t[2]); eq("grade n", g.n, Double.parseDouble(t[3])); eq("grade avg", g.avgR, Double.parseDouble(t[4])); eq("grade win", g.win, Double.parseDouble(t[5])); }
            }
        }
        System.out.println(fails == 0 ? "BACKTEST PARITY OK: " + checks + " checks, " + trades + " top-down trades identical" : "FAILED: " + fails + " mismatches of " + checks);
        if (fails != 0) System.exit(1);
    }
}
