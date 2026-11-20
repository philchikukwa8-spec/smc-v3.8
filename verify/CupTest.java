import com.smcscanner.engine.*;
import java.util.*;
public class CupTest {
    static int fail = 0, n = 0;
    static void ck(boolean ok, String m) { n++; if (!ok) { fail++; System.out.println("FAIL " + m); } }
    static Candle[] series(boolean flip, boolean handle, boolean breakout) {
        List<Candle> l = new ArrayList<>();
        Random r = new Random(5);
        double p = 100;
        for (int i = 0; i < 40; i++) { p += 0.02 * Math.sin(i / 3.0) + (r.nextDouble() - 0.5) * 0.15; l.add(mk(l.size(), p, 0.2)); }
        // drop then rounded cup of 80 candles: left rim 100, low 92, right rim 100
        for (int i = 0; i < 80; i++) {
            double x = i / 79.0;
            double y = 92 + 8 * Math.pow(2 * x - 1, 2) + (r.nextDouble() - 0.5) * 0.12;
            l.add(mk(l.size(), y, 0.25));
        }
        if (handle) for (int i = 0; i < 8; i++) l.add(mk(l.size(), 100 - 1.6 * Math.sin(Math.PI * (i + 1) / 9.0) * 1.0, 0.25));
        if (breakout) for (int i = 0; i < 3; i++) l.add(mk(l.size(), 102.6 + 0.6 * i, 0.25));
        Candle[] c = l.toArray(new Candle[0]);
        if (flip) c = Smc.mirror(c);
        return c;
    }
    static Candle mk(int i, double close, double rng) {
        double o = close - 0.02, h = Math.max(o, close) + rng, lo = Math.min(o, close) - rng;
        return new Candle(1700000000L + i * 3600L, o, h, lo, close);
    }
    public static void main(String[] a) {
        for (int pass = 0; pass < 2; pass++) {
            boolean flip = pass == 1;
            List<Patterns.Pattern> ps = Patterns.detect(series(flip, true, true));
            Patterns.Pattern got = null;
            for (Patterns.Pattern p : ps) if (p.name.contains("up") || p.name.contains("Cup") || p.name.contains("ounding")) got = p;
            ck(got != null, "cup found flip=" + flip);
            if (got != null) {
                ck(got.bias.equals(flip ? "SELL" : "BUY"), "bias flip=" + flip + " " + got.bias);
                ck(got.status.equals("confirmed"), "confirmed flip=" + flip + " " + got.status);
                ck(flip ? got.target < got.level : got.target > got.level, "target side flip=" + flip);
                ck(flip ? got.stopHint > got.level : got.stopHint < got.level, "stop hint beyond the line flip=" + flip + " " + got.stopHint);
                ck(got.name.contains("handle") || got.name.contains("ounding") || got.name.contains("Cup"), "name");
                System.out.println((flip ? "mirror: " : "") + got.name + " " + got.status + " level " + got.level + " target " + got.target + " stopHint " + got.stopHint);
            }
            // no cup in a straight line
        }
        Candle[] flat = new Candle[200];
        for (int i = 0; i < 200; i++) flat[i] = mk(i, 100 + 0.05 * i, 0.3);
        boolean any = false;
        for (Patterns.Pattern p : Patterns.detect(flat)) if (p.name.contains("up") || p.name.contains("ounding")) any = true;
        ck(!any, "no cup in a steady trend");
        System.out.println("CupTest checks " + n + " failures " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
