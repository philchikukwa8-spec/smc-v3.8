import com.smcscanner.engine.*;
import com.smcscanner.engine.pa.PaEngine;
import java.nio.file.*;
import java.util.*;
public class DcTest {
    static int fails = 0, checks = 0;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL " + m); } }
    static PaEngine.Signal sig(String side, String tf, String ctx, int score, int dc, boolean act, String name) {
        PaEngine.Signal s = new PaEngine.Signal();
        s.side = side; s.tf = tf; s.ctx = ctx; s.score = score; s.dc = dc; s.scenario = name; s.id = name + tf + side; s.entry = 100; s.stop = 99; s.target = 103;
        s.state = act ? "QUALIFIED" : "WATCH";
        return s;
    }
    public static void main(String[] a) throws Exception {
        // ---- resolve: only one side survives, the aligned one
        List<PaEngine.Signal> in = new ArrayList<>();
        in.add(sig("BUY", "15m", "with 1H", 4, 2, true, "Bull flag breakout"));
        in.add(sig("SELL", "1H", "against 4H", 5, 0, true, "Double top breakout"));
        in.add(sig("SELL", "30m", "4H unclear", 3, 0, false, "Support level rejection"));
        List<PaEngine.Signal> out = PaEngine.resolve(in);
        boolean buys = false, sells = false;
        for (PaEngine.Signal s : out) { if (s.side.equals("BUY")) buys = true; else sells = true; }
        ok("one side only", buys != sells);
        ok("aligned side wins over a reversal against the trend", buys && !sells);
        // an expired entry never takes part and is kept
        PaEngine.Signal ex = sig("SELL", "1H", "with 1H", 5, 2, true, "Old one"); ex.expired = true;
        in.add(ex);
        out = PaEngine.resolve(in);
        ok("expired kept", out.contains(ex));
        // ---- display order: bigger chart first, expired last
        List<PaEngine.Signal> l = new ArrayList<>();
        l.add(ex);
        l.add(sig("BUY", "15m", "with 1H", 5, 2, true, "a"));
        l.add(sig("BUY", "1H", "with 4H", 2, 0, false, "b"));
        l.add(sig("BUY", "30m", "with 1H", 4, 2, true, "c"));
        PaEngine.sortForDisplay(l);
        ok("1H first", l.get(0).tf.equals("1H"));
        ok("30m second", l.get(1).tf.equals("30m"));
        ok("expired last", l.get(3).expired);
        // ---- real series: top-down filter and double confirmation consistency
        Candle[] m5 = BacktestParity.read(Paths.get(a[0], "ltf_0.csv"));
        Candle[] m15 = ZoomTest.agg(m5, 3), m30 = ZoomTest.agg(m5, 6), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
        int seen = 0, d0 = 0, d1 = 0, d2 = 0, withLower = 0, against = 0, revAgainst = 0;
        Map<String, Integer> names = new TreeMap<>();
        for (int end = m5.length - 3000; end <= m5.length; end += 60) {
            long now = m5[end - 1].epoch + 300;
            Candle[] w5 = Arrays.copyOfRange(m5, 0, end);
            Candle[] w15 = ZoomTest.agg(w5, 3), w30 = ZoomTest.agg(w5, 6), w1 = ZoomTest.agg(w5, 12), w4 = ZoomTest.agg(w5, 48);
            if (w1.length < 90 || w4.length < 40) continue;
            for (PaEngine.Signal s : PaEngine.analyseFrame("S", "S", "15m", 900, w15, w1, "1H", w4, w1, w30, w5, "5m", now)) {
                seen++;
                ok("dc in range", s.dc >= 0 && s.dc <= 2);
                if (s.dc == 0) d0++; if (s.dc == 1) { d1++; ok("waiting text", !s.dcText.isEmpty()); } if (s.dc == 2) { d2++; ok("confirmed has epoch", s.dcEpoch > 0 && !s.dcText.isEmpty()); ok("confirmed evidence", s.evidence.toString().contains("DOUBLE CONFIRMATION")); }
                ok("plan present", s.plan.contains("SETUP IDENTIFIED") && s.plan.contains("INVALID IF") && s.plan.contains("THEN: place a"));
                ok("plan names the order", s.plan.contains(s.isBuy() ? "BUY LIMIT" : "SELL LIMIT"));
                ok("score in 1..5", s.score >= 1 && s.score <= 5);
                if (s.ctx.startsWith("against")) { against++; ok("against the trend only when it is a reversal", s.reversal); if (s.reversal) revAgainst++; }
                names.merge(s.scenario.split(" \\(")[0], 1, Integer::sum);
            }
            for (PaEngine.Signal s : PaEngine.analyseFrame("S", "S", "15m", 900, w15, w1, "1H", w4, w1, w30, now)) { ok("no lower chart = not available", s.dc == 0); withLower++; }
        }
        System.out.println("signals " + seen + " | double confirmation: confirmed " + d2 + ", waiting " + d1 + ", n/a " + d0 + " | against-trend (reversals) " + against);
        System.out.println(names);
        System.out.println("DC TESTS " + (fails == 0 ? "OK" : "FAILED") + ": " + checks + " checks, " + fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }
}
