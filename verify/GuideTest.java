import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import com.smcscanner.engine.pa.*;
import java.util.*;

/** synthetic charts built from the guide's descriptions: bull flag with a measured pole, and an order block inside an order block */
public class GuideTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }
    static List<Candle> base(int n, double p, long t0, long g, Random r) {
        List<Candle> l = new ArrayList<>();
        for (int i = 0; i < n; i++) { double o = p + (r.nextDouble() - 0.5) * 0.4, c = p + (r.nextDouble() - 0.5) * 0.4; l.add(new Candle(t0 + i * g, o, Math.max(o, c) + 0.3, Math.min(o, c) - 0.3, c)); p = c; }
        return l;
    }
    static void add(List<Candle> l, double o, double c, double up, double dn, long g) { long t = l.get(l.size() - 1).epoch + g; l.add(new Candle(t, o, Math.max(o, c) + up, Math.min(o, c) - dn, c)); }
    public static void main(String[] a) {
        long g = 3600;
        Random r = new Random(5);
        // ---- bull flag: noise, a pole of ~8 up in 5 candles, a 6-candle drifting flag, a breakout close, then a pullback to the boundary
        List<Candle> l = base(120, 100, 1_700_000_000L, g, r);
        double p = l.get(l.size() - 1).close;
        for (int i = 0; i < 5; i++) { add(l, p, p + 1.6, 0.1, 0.1, g); p += 1.6; }          // pole to ~108
        double top = p;
        double[] flag = {-0.25, -0.15, -0.3, -0.1, -0.2, -0.15};
        for (double d : flag) { add(l, p, p + d, 0.25, 0.25, g); p += d; }
        add(l, p, p + 1.4, 0.1, 0.1, g); p += 1.4;                                           // break
        add(l, p, p - 0.5, 0.1, 0.3, g); p -= 0.5;                                           // pullback toward the boundary
        Candle[] h1 = l.toArray(new Candle[0]);
        // 4H context made from the same series
        Candle[] h4 = new Candle[h1.length / 4];
        for (int i = 0; i < h4.length; i++) { double hi = -1e9, lo = 1e9; for (int j = 0; j < 4; j++) { hi = Math.max(hi, h1[i * 4 + j].high); lo = Math.min(lo, h1[i * 4 + j].low); } h4[i] = new Candle(h1[i * 4].epoch, h1[i * 4].open, hi, lo, h1[i * 4 + 3].close); }
        long now = h1[h1.length - 1].epoch + g;
        List<PaEngine.Signal> sg = PaEngine.analyse("T", "Test", h4, h1, now);
        boolean flagFound = false;
        for (PaEngine.Signal s : sg) {
            if (s.scenario.contains("flag")) {
                flagFound = true;
                ok("flag buy", s.isBuy());
                ok("flag stop below entry", s.stop < s.entry);
                ok("flag target above entry", s.target > s.entry);
                System.out.println("  flag: " + s.scenario + " " + s.state + " entry " + s.entry + " stop " + s.stop + " target " + s.target + " rr " + String.format("%.1f", s.rr));
            }
        }
        System.out.println("signals on the synthetic flag chart: " + sg.size());
        for (PaEngine.Signal s : sg) System.out.println("   " + s.scenario + " / " + s.state);
        ok("bull flag detected", flagFound);

        // ---- signal candles
        Candle[] c = new Candle[40];
        for (int i = 0; i < 40; i++) c[i] = new Candle(i * g, 100, 100.6, 99.4, 100.1);
        c[38] = new Candle(38 * g, 100.2, 100.4, 99.9, 100.0);            // inside bar of c[37]? make c[37] big
        c[37] = new Candle(37 * g, 100.4, 101.0, 99.5, 99.7);
        c[39] = new Candle(39 * g, 99.8, 100.3, 99.2, 100.2);             // false break of c[37]'s low 99.5, closes back inside and up
        ok("fakey buy", PaEngine.signal(c, true) != null);
        ok("no sell fakey", PaEngine.signal(c, false) == null);

        // ---- order block inside order block: zone 100..104; 15m OB inside it with displacement; 5m OB inside that
        long g15 = 900, g5 = 300;
        List<Candle> m15 = base(60, 102, 1_700_000_000L, g15, r);
        // down candle (OB) inside the zone, then an impulse above its high
        add(m15, 102.0, 101.4, 0.1, 0.1, g15);                                             // OB: body 101.4..102.0
        add(m15, 101.4, 103.0, 0.1, 0.1, g15);                                             // displacement
        add(m15, 103.0, 102.6, 0.2, 0.2, g15);
        List<Candle> m5 = base(80, 102, 1_700_000_000L, g5, r);
        add(m5, 101.9, 101.6, 0.05, 0.05, g5);                                             // 5m OB inside the 15m block
        add(m5, 101.6, 102.4, 0.05, 0.05, g5);                                             // displacement
        add(m5, 102.4, 102.2, 0.1, 0.1, g5);
        ObRefiner.Result res = ObRefiner.refine(true, 100, 104, 0.9, m15.toArray(new Candle[0]), m5.toArray(new Candle[0]), null);
        ok("OB chain found", res.valid);
        if (res.valid) {
            System.out.println("  " + ObRefiner.line(res, "1H"));
            ok("chain depth 2 (15m then 5m)", res.depth() == 2);
            ok("entry above stop (buy)", res.entry > res.stop);
            ok("stop beyond the deepest block low", res.stop < res.chain.get(res.depth() - 1).low);
            ok("blocks nested", res.chain.get(1).bodyHigh <= res.chain.get(0).high + 1e-9 && res.chain.get(1).low >= res.chain.get(0).low - 0.2);
            ok("tight: stop within 1.5 of entry", res.entry - res.stop < 1.5);
        }
        // sell mirror: mirrored inputs must give the mirrored answer
        Candle[] m15m = new Candle[m15.size()], m5m = new Candle[m5.size()];
        for (int i = 0; i < m15m.length; i++) { Candle k = m15.get(i); m15m[i] = new Candle(k.epoch, -k.open, -k.low, -k.high, -k.close); }
        for (int i = 0; i < m5m.length; i++) { Candle k = m5.get(i); m5m[i] = new Candle(k.epoch, -k.open, -k.low, -k.high, -k.close); }
        ObRefiner.Result rs = ObRefiner.refine(false, -104, -100, 0.9, m15m, m5m, null);
        ok("sell mirror valid", rs.valid);
        if (rs.valid && res.valid) { ok("mirror entry", Math.abs(rs.entry + res.entry) < 1e-9); ok("mirror stop", Math.abs(rs.stop + res.stop) < 1e-9); ok("sell stop above entry", rs.stop > rs.entry); }
        // a block that price has closed through is mitigated and must not be used
        List<Candle> broken = new ArrayList<>(m15);
        add(broken, 102.6, 100.9, 0.1, 0.1, g15);
        ObRefiner.Result rb = ObRefiner.refine(true, 100, 104, 0.9, broken.toArray(new Candle[0]), null, null);
        ok("mitigated block not used", !rb.valid);
        // outside the zone: nothing
        ObRefiner.Result ro = ObRefiner.refine(true, 110, 112, 0.9, m15.toArray(new Candle[0]), m5.toArray(new Candle[0]), null);
        ok("block outside the zone ignored", !ro.valid);
        System.out.println("GUIDE TESTS: " + checks + " checks, " + fails + " failures");
    }
}
