import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

public class AdvisorTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }
    static int upTo(Candle[] c, long gran, long asOf) { int n = 0; while (n < c.length && c[n].epoch + gran <= asOf) n++; return n; }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        if (a.length > 1) Advisor.entryFrac = Double.parseDouble(a[1]);
        if (a.length > 2) Advisor.capR = Double.parseDouble(a[2]);
        int trig = 0, ready = 0, near = 0, wait = 0, windows = 0, wins = 0, losses = 0, other = 0, buys = 0, sells = 0;
        double totalR = 0;
        Map<String, double[]> byGrade = new TreeMap<>();
        int days = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m15 = ZoomTest.agg(m5, 3), m30 = ZoomTest.agg(m5, 6), h1 = ZoomTest.agg(m5, 12);
            days += (int) ((m15[m15.length - 1].epoch - m15[0].epoch) / 86400);
            Set<String> seen = new HashSet<>();
            for (int i = 200; i < m15.length; i++) {
                long asOf = m15[i].epoch + 900;
                int n1 = upTo(h1, 3600, asOf), n30 = upTo(m30, 1800, asOf);
                Candle[] w15 = Arrays.copyOfRange(m15, Math.max(0, i - 299), i + 1);
                Candle[] w30 = Arrays.copyOfRange(m30, Math.max(0, n30 - 300), n30);
                Candle[] w1 = Arrays.copyOfRange(h1, Math.max(0, n1 - 300), n1);
                Advisor.Plan p = Advisor.analyse(w1, w30, w15);
                windows++;
                // invariants
                ok("state is known", Arrays.asList("TRIGGERED", "READY", "APPROACHING", "WAIT").contains(p.state));
                ok("headline present", p.headline != null && !p.headline.isEmpty());
                if (p.state.equals("TRIGGERED")) {
                    boolean buy = p.isBuy();
                    ok("stop on the right side", buy ? p.stop < p.entry : p.stop > p.entry);
                    ok("target on the right side", buy ? p.target > p.entry : p.target < p.entry);
                    ok("rr at least minimum", p.rr >= Advisor.MIN_RR - 1e-9); ok("band set", !p.band().isEmpty());
                    ok("setup rr matches", Math.abs(p.setup.rr - Math.abs(p.target - p.entry) / Math.abs(p.entry - p.stop)) < 1e-6);
                    ok("zone ordered", p.zoneLow < p.zoneHigh);
                    ok("entry fresh: trigger within 7 candles", m15[i].epoch - p.triggerEpoch <= 6 * 900);
                    if (seen.add(p.id)) {
                        trig++;
                        if (buy) buys++; else sells++;
                        Backtest.Trade t = Backtest.simulate(m15, i, p.setup, 24, 96);
                        double[] g = byGrade.computeIfAbsent(p.grade, x -> new double[3]);
                        if (t.outcome.equals("win")) { wins++; totalR += t.r; g[0]++; g[2] += t.r; }
                        else if (t.outcome.equals("loss")) { losses++; totalR -= 1; g[1]++; g[2] -= 1; }
                        else other++;
                    }
                } else if (p.state.equals("READY")) ready++;
                else if (p.state.equals("APPROACHING")) near++;
                else wait++;
            }
        }
        System.out.println("windows " + windows + " over ~" + days + " days of data: TRIGGERED " + trig + " (buys " + buys + ", sells " + sells + "), READY " + ready + ", APPROACHING " + near + ", WAIT " + wait);
        System.out.printf("outcomes: won %d lost %d unfilled/expired %d | win rate %.1f%% | total %.1fR | per day %.2f%n", wins, losses, other, wins + losses > 0 ? 100.0 * wins / (wins + losses) : 0, totalR, (double) trig / Math.max(1, days / 1.0));
        for (Map.Entry<String, double[]> e : byGrade.entrySet()) System.out.printf("grade %s: won %.0f lost %.0f total %.1fR%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        System.out.println((fails == 0 ? "ADVISOR TESTS OK: " : "ADVISOR TESTS FAILED: ") + checks + " checks, " + fails + " failures");
    }
}
