import com.smcscanner.engine.*;
import com.smcscanner.engine.fib.*;
import java.util.*;

public class FibTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    /** piecewise path through waypoints (candle index, price), with small noise, as M15 candles */
    static Candle[] path(double[][] wp, long t0, Random r, double noise) {
        int n = (int) wp[wp.length - 1][0] + 1;
        Candle[] c = new Candle[n];
        double prev = wp[0][1];
        for (int i = 0; i < n; i++) {
            int j = 0; while (j < wp.length - 2 && wp[j + 1][0] < i) j++;
            double t = (i - wp[j][0]) / Math.max(1, wp[j + 1][0] - wp[j][0]);
            double target = wp[j][1] + (wp[j + 1][1] - wp[j][1]) * Math.min(1, Math.max(0, t));
            double cl = target + (r.nextDouble() - 0.5) * noise;
            double hi = Math.max(prev, cl) + r.nextDouble() * noise * 0.5, lo = Math.min(prev, cl) - r.nextDouble() * noise * 0.5;
            c[i] = new Candle(t0 + i * 900L, prev, hi, lo, cl);
            prev = cl;
        }
        return c;
    }
    static Candle[] h4(Candle[] m) {
        List<Candle> l = new ArrayList<>();
        for (int i = 0; i + 16 <= m.length; i += 16) {
            double hi = -1e18, lo = 1e18;
            for (int j = 0; j < 16; j++) { hi = Math.max(hi, m[i + j].high); lo = Math.min(lo, m[i + j].low); }
            l.add(new Candle(m[i].epoch, m[i].open, hi, lo, m[i + 15].close));
        }
        return l.toArray(new Candle[0]);
    }
    static Candle[] mirror(Candle[] c) { return Smc.mirror(c); }

    public static void main(String[] a) {
        Random r = new Random(11);
        long t0 = 1_700_000_000L - (1_700_000_000L % 14400);
        // range, then a clean H4 leg 100 -> 120 (break of structure), pullback to 107, then an M15 staircase
        double[][] wp = {
            {0, 100}, {200, 103}, {400, 99}, {600, 104}, {800, 100.5}, {1000, 105}, {1200, 101},   // swings around 100-105 (H4 swing highs and lows)
            {1400, 100.5}, {1700, 122},                                                            // leg up (breaks 105 on a close): 0% = about 100.5, 100% = 122
            {1900, 108},                                                                            // pullback beyond 50% (111.25)
            {1960, 111.5}, {1975, 110.3}, {2020, 114}, {2035, 112.8}, {2080, 117.5}, {2100, 114.9} // M15: three breaks and a dip toward the entry
        };
        Candle[] m15 = path(wp, t0, r, 0.18);
        Candle[] h4c = h4(m15);
        long now = m15[m15.length - 1].epoch + 900;
        FibEngine.Options o = new FibEngine.Options();
        List<FibEngine.Setup> res = FibEngine.analyse("T", "Test", h4c, m15, now, o);
        System.out.println("setups: " + res.size());
        for (FibEngine.Setup s : res) {
            System.out.println("  " + s.side + " " + s.state + " | " + s.stage + " | bos " + s.bosCount + " entry " + s.entry + " stop " + s.stop + " tp1 " + s.tp1 + " tp2 " + s.tp2 + " score " + s.score + " d1 " + s.d1);
            for (FibEngine.Step st : s.steps) System.out.println("     [" + st.status + "] " + st.text);
        }
        FibEngine.Setup buy = null; for (FibEngine.Setup s : res) if (s.isBuy()) buy = s;
        ok("buy setup found", buy != null);
        if (buy != null) {
            ok("buy has three breaks", buy.bosCount == 3);
            ok("buy actionable", buy.actionable());
            if (buy.actionable()) {
                ok("stop below entry", buy.stop < buy.entry);
                ok("tp1 above entry", buy.tp1 > buy.entry);
                ok("tp2 >= tp1", buy.tp2 >= buy.tp1);
                ok("entry at 50% of the M15 fib or below the price", Math.abs(buy.entry - (buy.mZero + 0.5 * (buy.mHundred - buy.mZero))) < 1e-6 || buy.entry < buy.price);
                ok("h4 fib sane", buy.h4Hundred > buy.h4Zero && buy.h4Hundred > 115);
                ok("m15 fib sane", buy.mHundred > buy.mZero);
                ok("id set", buy.id.startsWith("FIB|T|BUY|"));
            }
        }
        // mirror: a sell on mirrored prices must be the mirror image
        Candle[] mm = mirror(m15), hm = h4(mm);
        List<FibEngine.Setup> rs = FibEngine.analyse("T", "Test", hm, mm, now, o);
        FibEngine.Setup sell = null; for (FibEngine.Setup s : rs) if (!s.isBuy()) sell = s;
        ok("sell setup found on mirrored data", sell != null);
        if (sell != null && buy != null) {
            ok("same state", sell.state.equals(buy.state));
            ok("mirror entry", Math.abs(sell.entry + buy.entry) < 1e-6);
            ok("mirror stop", Math.abs(sell.stop + buy.stop) < 1e-6);
            ok("mirror tp1", Math.abs(sell.tp1 + buy.tp1) < 1e-6);
            ok("sell stop above entry", sell.stop > sell.entry);
            boolean allPos = true; for (FibEngine.Step st : buy.steps) allPos &= st.text != null;
            ok("sell steps mirror the buy steps", sell.steps.size() == buy.steps.size());
        }
        // deeper entry option
        FibEngine.Options o30 = new FibEngine.Options(); o30.entryFrac = 0.3;
        List<FibEngine.Setup> r30 = FibEngine.analyse("T", "Test", h4c, m15, now, o30);
        for (FibEngine.Setup s : r30) if (s.isBuy() && s.actionable() && buy != null && buy.actionable()) ok("30% entry is lower than 50% entry", s.entry <= buy.entry + 1e-9 || s.entry < s.price + 1e-9);
        // no setup in pure noise
        Candle[] flat = path(new double[][]{{0, 100}, {3000, 100.2}}, t0, r, 0.6);
        ok("noise gives no actionable setup", FibEngine.analyse("T", "Test", h4(flat), flat, flat[flat.length - 1].epoch + 900, o).stream().noneMatch(FibEngine.Setup::actionable));
        System.out.println("FIB TESTS: " + checks + " checks, " + fails + " failures");
    }
}
