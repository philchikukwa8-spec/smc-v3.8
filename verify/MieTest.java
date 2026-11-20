import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import java.nio.file.*;
import java.util.*;

public class MieTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; if (fails < 15) System.out.println("FAIL: " + m); } }
    static int upTo(Candle[] c, long gran, long asOf) { int n = 0; while (n < c.length && c[n].epoch + gran <= asOf) n++; return n; }
    static Candle[] win(Candle[] c, int n, int keep) { return Arrays.copyOfRange(c, Math.max(0, n - keep), n); }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int step = a.length > 1 ? Integer.parseInt(a[1]) : 1;
        Map<String, Integer> states = new TreeMap<>(), signals = new TreeMap<>(), reasons = new TreeMap<>(), execs = new TreeMap<>();
        int windows = 0, wins = 0, losses = 0, other = 0, ctr = 0;
        double totalR = 0;
        Map<String, double[]> byBand = new TreeMap<>(), byExec = new TreeMap<>();
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m15 = ZoomTest.agg(m5, 3), m30 = ZoomTest.agg(m5, 6), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
            Set<String> seen = new HashSet<>();
            for (int i = 400; i < m15.length; i += step) {
                long asOf = m15[i].epoch + 900;
                Mie.Inputs in = new Mie.Inputs();
                in.now = asOf;
                in.m15 = win(m15, i + 1, 300);
                in.m30 = win(m30, upTo(m30, 1800, asOf), 300);
                in.h1 = win(h1, upTo(h1, 3600, asOf), 300);
                in.h4 = win(h4, upTo(h4, 14400, asOf), 200);
                in.m5 = win(m5, upTo(m5, 300, asOf), 200);
                Mie.Assessment as = Mie.analyse(in);
                windows++;
                states.merge(as.setupState, 1, Integer::sum);
                signals.merge(as.signal, 1, Integer::sum);
                for (String r : as.reasons) reasons.merge(r, 1, Integer::sum);
                ok("has headline", as.headline != null && !as.headline.isEmpty());
                ok("has id", as.id != null && !as.id.isEmpty());
                ok("4 timeframe states", as.tfs.size() >= 4);
                if (as.actionable()) {
                    boolean buy = as.isBuy();
                    ok("stop side", buy ? as.stop < as.entry : as.stop > as.entry);
                    ok("target side", buy ? as.target > as.entry : as.target < as.entry);
                    ok("rr matches", Math.abs(as.rr - Math.abs(as.target - as.entry) / Math.abs(as.entry - as.stop)) < 1e-6);
                    ok("rr is never used as a filter: band present", !as.rrBand.isEmpty());
                    ok("exec tf set", as.execTf.equals("5M") || as.execTf.equals("1M") || as.execTf.equals("15M"));
                    ok("explanation present", as.explanation.contains("Invalid if"));
                    ok("target not stretched: it is a listed structure candidate", as.targets != null && as.targets.target != null && Math.abs(as.targets.target.level - as.target) < 1e-9);
                    ok("target at least 1R away", as.rr >= 1.0 - 1e-9);
                    ok("setup carries rr", Math.abs(as.setup.rr - as.rr) < 1e-9);
                    ok("groups 1..4", as.groupsMet >= 1 && as.groupsMet <= 4);
                    if (as.counterTrend) ctr++;
                    if (seen.add(as.id)) {
                        execs.merge(as.execTf, 1, Integer::sum);
                        Backtest.Trade t = Backtest.simulate(m15, i, as.setup, 24, 96);
                        double r = t.outcome.equals("win") ? t.r : t.outcome.equals("loss") ? -1 : 0;
                        if (t.outcome.equals("win")) wins++; else if (t.outcome.equals("loss")) losses++; else other++;
                        totalR += r;
                        double[] g = byBand.computeIfAbsent(as.rrBand, x -> new double[3]);
                        double[] e = byExec.computeIfAbsent(as.execTf, x -> new double[3]);
                        if (t.outcome.equals("win")) { g[0]++; e[0]++; } else if (t.outcome.equals("loss")) { g[1]++; e[1]++; }
                        g[2] += r; e[2] += r;
                    }
                }
            }
        }
        System.out.println("windows " + windows + "\nstates " + states + "\nsignals " + signals + "\nreasons " + reasons + "\nexec tf " + execs + " countertrend windows " + ctr);
        System.out.printf("unique actionable plans %d: won %d lost %d unfilled/expired %d | win %.1f%% | total %.1fR%n", wins + losses + other, wins, losses, other, wins + losses > 0 ? 100.0 * wins / (wins + losses) : 0, totalR);
        for (Map.Entry<String, double[]> e : byBand.entrySet()) System.out.printf("band %-16s won %.0f lost %.0f total %.1fR%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        for (Map.Entry<String, double[]> e : byExec.entrySet()) System.out.printf("exec %-4s won %.0f lost %.0f total %.1fR%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        System.out.println((fails == 0 ? "MIE TESTS OK: " : "MIE TESTS FAILED: ") + checks + " checks, " + fails + " failures");
    }
}
