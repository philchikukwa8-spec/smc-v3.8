import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import java.nio.file.*;
import java.util.*;

public class TierTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; if (fails < 15) System.out.println("FAIL: " + m); } }
    static int upTo(Candle[] c, long gran, long asOf) { int n = 0; while (n < c.length && c[n].epoch + gran <= asOf) n++; return n; }
    static Candle[] win(Candle[] c, int n, int keep) { return Arrays.copyOfRange(c, Math.max(0, n - keep), n); }

    /** test-only: five 1m candles inside each 5m candle, walking open -> extreme -> extreme -> close */
    static Candle[] split(Candle[] m5) {
        Candle[] out = new Candle[m5.length * 5];
        for (int i = 0; i < m5.length; i++) {
            Candle c = m5[i];
            boolean bull = c.close >= c.open;
            double[] pts = bull ? new double[]{c.open, c.low, c.high, c.high, c.close, c.close} : new double[]{c.open, c.high, c.low, c.low, c.close, c.close};
            for (int k = 0; k < 5; k++) {
                double o = pts[k], cl = pts[k + 1];
                out[i * 5 + k] = new Candle(c.epoch + 60L * k, o, Math.max(o, cl), Math.min(o, cl), cl);
            }
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        for (int tier = 1; tier <= 2; tier++) {
            int windows = 0, ready = 0; Map<String, Integer> states = new TreeMap<>(), execs = new TreeMap<>();
            Set<String> ids = new HashSet<>();
            for (int k = 0; k < 6; k++) {
                Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
                Candle[] m1 = split(m5);
                Candle[] m15 = ZoomTest.agg(m5, 3), m30 = ZoomTest.agg(m5, 6), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
                for (int i = 400; i < m15.length; i += 4) {
                    long asOf = m15[i].epoch + 900;
                    Mie.Inputs in = new Mie.Inputs();
                    in.now = asOf;
                    in.m15 = win(m15, i + 1, 300);
                    in.m30 = win(m30, upTo(m30, 1800, asOf), 300);
                    in.h1 = win(h1, upTo(h1, 3600, asOf), 300);
                    in.h4 = win(h4, upTo(h4, 14400, asOf), 200);
                    in.m5 = win(m5, upTo(m5, 300, asOf), 300);
                    in.m1 = win(m1, upTo(m1, 60, asOf), 300);
                    Mie.Assessment as = Mie.analyseTier(in, tier);
                    windows++;
                    states.merge(as.setupState, 1, Integer::sum);
                    ok("frame", as.frame.equals(tier == 1 ? "30m" : "15m") && as.tier == tier);
                    ok("id prefix", as.id.startsWith("MIE|T" + tier + "|"));
                    String all = as.headline + as.explanation + as.execTf + as.execWhy + as.targetWhy + as.zoneDesc + as.evidence + as.conflicts;
                    ok("no 4H name leaks", !all.contains("4H") && !all.contains("4h"));
                    if (tier == 2) ok("no 1H name leaks", !all.contains("1H"));
                    for (Mie.TfState t : as.tfs) ok("tf names are mapped", !t.tf.equals("lower-timeframe") && !(tier == 1 && t.tf.equals("4H")) && !(tier == 2 && (t.tf.equals("4H") || t.tf.equals("1H"))));
                    if (as.actionable()) {
                        ready++;
                        boolean buy = as.isBuy();
                        ok("stop side", buy ? as.stop < as.entry : as.stop > as.entry);
                        ok("target side", buy ? as.target > as.entry : as.target < as.entry);
                        ok("rr", Math.abs(as.rr - Math.abs(as.target - as.entry) / Math.abs(as.entry - as.stop)) < 1e-6);
                        ok("explanation", as.explanation.contains("Invalid if"));
                        if (ids.add(as.id)) execs.merge(as.execTf, 1, Integer::sum);
                        Row r = Row.from(as, "t", "LIVE");
                        ok("row frame", r.frame.equals(as.frame) && r.scenario.startsWith("[" + as.frame + "]"));
                    }
                }
            }
            System.out.println("tier " + tier + ": windows " + windows + ", ready " + ready + ", distinct " + ids.size() + ", states " + states + ", exec " + execs);
        }
        System.out.println("TIER TESTS " + (fails == 0 ? "OK" : "FAILED") + ": " + checks + " checks, " + fails + " failures");
    }
}
