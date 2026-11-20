import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import com.smcscanner.engine.pa.*;
import java.nio.file.*;
import java.util.*;

public class PaTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; if (fails < 15) System.out.println("FAIL: " + m); } }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        List<Row> all = new ArrayList<>();
        Map<String, Integer> sc = new TreeMap<>();
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
            PaReplay.Report r = PaReplay.run("SIM" + k, "Sim " + k, h4, h1, 4, PaEngine.VERSION);
            PaReplay.Report r2 = PaReplay.run("SIM" + k, "Sim " + k, h4, h1, 4, PaEngine.VERSION);
            ok("deterministic", r.rows.size() == r2.rows.size());
            all.addAll(r.rows);
            for (Row x : r.rows) {
                ok("id", x.id.startsWith("PAR|PA|"));
                ok("mode", x.mode.equals("PA"));
                ok("entry/stop side", x.side.equals("BUY") ? x.stop < x.entry : x.stop > x.entry);
                ok("target side", x.side.equals("BUY") ? x.target > x.entry : x.target < x.entry);
                ok("rr", x.rr > 0 && Math.abs(Math.abs(x.target - x.entry) / Math.abs(x.entry - x.stop) - x.rr) < 1e-6);
                ok("scenario key", !x.scenario.isEmpty() && x.scenario.contains("|"));
                ok("sample", x.sample.equals("in-sample") || x.sample.equals("validation") || x.sample.equals("out-of-sample"));
                if (x.actionable()) ok("outcome set", !x.outcome.equals("pending"));
                sc.merge(x.zone + ": " + x.scenario.split(" \\| ")[0] + (x.actionable() ? " (ready)" : " (watch)"), 1, Integer::sum);
            }
            Candle[] m30 = ZoomTest.agg(m5, 6), m15 = ZoomTest.agg(m5, 3);
            for (String tf : new String[]{"30m", "15m"}) {
                boolean is30 = tf.equals("30m");
                PaReplay.Report rf = PaReplay.runFrame("SIM" + k, "Sim " + k, tf, is30 ? 1800 : 900, is30 ? m30 : m15, h1, 3600, "1H", h4, h1, m30, 14400, 3600, 1800, is30 ? 4 : 8, PaEngine.VERSION);
                int ready = 0;
                for (Row x : rf.rows) {
                    ok("frame id", x.id.startsWith("PAR|PA|SIM" + k + "|" + tf + "|"));
                    ok("frame row", x.frame.equals(tf) && x.scenario.contains("(" + tf + ")") && x.mode.equals("PA"));
                    ok("frame entry/stop side", x.side.equals("BUY") ? x.stop < x.entry : x.stop > x.entry);
                    ok("frame target side", x.side.equals("BUY") ? x.target > x.entry : x.target < x.entry);
                    ok("frame ctx names 1H", x.alignment.contains("1H"));
                    if (x.actionable()) { ready++; ok("frame outcome set", !x.outcome.equals("pending")); }
                }
                all.addAll(rf.rows);
                System.out.println("  " + tf + ": windows " + rf.windows + ", rows " + rf.rows.size() + ", ready " + ready + ", " + rf.all.line());
            }
            System.out.println("series " + k + ": windows " + r.windows + ", rows " + r.rows.size() + ", " + r.all.line());
        }
        for (Map.Entry<String, Integer> e : sc.entrySet()) System.out.println("  " + e.getKey() + " x" + e.getValue());
        // learning on the rows, with some fake SMC rows to exercise the comparison
        List<Row> mix = new ArrayList<>(all);
        Random rnd = new Random(7);
        for (int i = 0; i < 80; i++) {
            Row s = new Row(); s.id = "S" + i; s.mode = "SMC"; s.symbol = "SIM" + (i % 6); s.side = i % 2 == 0 ? "BUY" : "SELL";
            s.state = Mie.S_QUALIFIED; s.ts = 1700000000L + i * 5000L; s.scenario = "30m FVG, aligned"; s.sample = "live";
            s.outcome = rnd.nextBoolean() ? "win" : "loss"; s.resultR = s.outcome.equals("win") ? 2 : -1; s.filled = true; mix.add(s);
        }
        String cmp = Learning.compare(mix);
        ok("compare text", cmp.contains("MODE COMPARISON") && cmp.contains("WHAT THE MEMORY SAYS"));
        System.out.println(cmp.substring(0, Math.min(cmp.length(), 2500)));
        Set<String> bl = Learning.blocked(mix);
        System.out.println("blocked: " + bl);
        for (String b : bl) { Row any = null; for (Row r : mix) if (Learning.key(r).equals(b)) any = r; ok("blocked rows usable", any != null && Learning.usable(any)); }
        ok("memory none", Learning.memoryLine(mix, "PA", "nothing").startsWith("Not seen"));
        System.out.println(Learning.memoryLine(mix, "SMC", "30m FVG, aligned"));
        // in-sample rows never drive a block
        List<Row> ins = new ArrayList<>();
        for (int i = 0; i < 40; i++) { Row s = new Row(); s.id = "I" + i; s.mode = "PA"; s.scenario = "X"; s.state = Mie.S_QUALIFIED; s.sample = "in-sample"; s.outcome = "loss"; s.resultR = -1; s.filled = true; ins.add(s); }
        ok("in-sample never blocks", Learning.blocked(ins).isEmpty());
        // signal sample print
        System.out.println("PA TESTS " + (fails == 0 ? "OK" : "FAILED") + ": " + checks + " checks, " + fails + " failures");
    }
}
