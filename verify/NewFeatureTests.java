import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Scenario tests for trade management, entry styles and the confidence figure. */
public class NewFeatureTests {
    static int fails = 0, checks = 0;
    static void ok(String w, boolean c) { checks++; if (!c) { fails++; System.out.println("  FAIL " + w); } }
    static void near(String w, double a, double b) { ok(w + " (got " + a + ", want " + b + ")", Math.abs(a - b) < 1e-6); }
    static final long G = 60, START = 100000;
    static final SmcConfig CFG = new SmcConfig();

    static Candle c(int i, double o, double h, double l, double cl) { return new Candle(START + i * G, o, h, l, cl); }
    static long NOW = START + 100000;

    static Manage.Trade buy() {
        Setup s = new Setup(); s.side = "BUY"; s.sl = 95; s.tp = 110;
        return Manage.create(s, "R_75", "Volatility 75", "1H", "1m", G, 100, 120.0, START, 10);
    }
    static Manage.Trade sell() {
        Setup s = new Setup(); s.side = "SELL"; s.sl = 105; s.tp = 90;
        return Manage.create(s, "R_75", "Volatility 75", "1H", "1m", G, 100, 80.0, START, 10);
    }
    static boolean has(List<Manage.Event> e, String kind) { for (Manage.Event x : e) if (x.kind.equals(kind)) return true; return false; }
    static Candle[] flat(int n, double lo, double hi) { Candle[] a = new Candle[n]; for (int i = 0; i < n; i++) a[i] = c(i, (lo + hi) / 2, hi, lo, (lo + hi) / 2); return a; }
    static Candle[] cat(Candle[] a, Candle... b) { Candle[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    public static void main(String[] args) throws Exception {
        // 1 stopped out
        { Manage.Trade t = buy(); List<Manage.Event> e = Manage.update(t, cat(flat(3, 99, 101), c(3, 100, 100.5, 94, 95)), NOW, CFG);
          ok("1 stopped event", has(e, "stopped")); ok("1 closed", t.status.equals("closed")); near("1 R", t.resultR, -1); }
        // 2 breakeven then stopped at breakeven
        { Manage.Trade t = buy(); Candle[] cs = cat(flat(3, 99, 101), c(3, 101, 105.5, 100.5, 105));
          List<Manage.Event> e = Manage.update(t, cs, NOW, CFG);
          ok("2 breakeven event", has(e, "breakeven")); near("2 stop at entry", t.stop, 100); ok("2 stage 1", t.stage == 1);
          cs = cat(cs, c(4, 105, 105.5, 99.5, 100));
          e = Manage.update(t, cs, NOW, CFG);
          ok("2 stopped at BE", has(e, "stopped") && t.status.equals("closed")); near("2 R zero", t.resultR, 0); }
        // 3 trail behind swing low
        { Manage.Trade t = buy();
          Candle[] cs = {c(0,100,101,99,100.5), c(1,100.5,106,104,105.5), c(2,105.5,108,104.5,107), c(3,107,108.5,105,108), c(4,108,109,103,104), // swing low 103 at idx4
                         c(5,104,106,104,105.5), c(6,105.5,107,105,106.5), c(7,106.5,108,105.5,107.5), c(8,107.5,108.5,106,108)};
          List<Manage.Event> e = Manage.update(t, cs, NOW, CFG);
          ok("3 breakeven first", t.stage >= 1); ok("3 trail event", has(e, "trail")); ok("3 stop raised above entry", t.stop > 100); ok("3 stop below swing low", t.stop < 103); ok("3 stop below price", t.stop < 108);
          double s1 = t.stop; List<Manage.Event> e2 = Manage.update(t, cs, NOW, CFG); ok("3 idempotent", e2.isEmpty()); near("3 stop unchanged", t.stop, s1); }
        // 4 tp1 then tp2
        { Manage.Trade t = buy(); Candle[] cs = cat(flat(3, 99, 101), c(3, 101, 110.5, 100.5, 110));
          List<Manage.Event> e = Manage.update(t, cs, NOW, CFG);
          ok("4 tp1 event", has(e, "tp1")); ok("4 stage 2", t.stage == 2); ok("4 target moved to tp2", t.tp != null && t.tp == 120.0); ok("4 stop >= entry", t.stop >= 100); ok("4 still open", t.status.equals("open"));
          cs = cat(cs, c(4, 110, 121, 109, 120));
          e = Manage.update(t, cs, NOW, CFG); ok("4 final event", has(e, "tp_final")); near("4 R", t.resultR, 4); }
        // 5 sell mirror: stop, tp1
        { Manage.Trade t = sell(); List<Manage.Event> e = Manage.update(t, cat(flat(3, 99, 101), c(3, 100, 106, 99.5, 105)), NOW, CFG);
          ok("5 sell stopped", has(e, "stopped")); near("5 R", t.resultR, -1);
          Manage.Trade u = sell(); e = Manage.update(u, cat(flat(3, 99, 101), c(3, 99, 99.5, 89.5, 90)), NOW, CFG);
          ok("5 sell tp1", has(e, "tp1")); ok("5 sell target 80", u.tp != null && u.tp == 80.0); ok("5 sell stop <= entry", u.stop <= 100); }
        // 6 both touched in one candle -> stop first
        { Manage.Trade t = buy(); List<Manage.Event> e = Manage.update(t, cat(flat(3, 99, 101), c(3, 100, 111, 94, 100)), NOW, CFG);
          ok("6 stop first", has(e, "stopped") && !has(e, "tp1")); }
        // 7 forming candle wick through the stop
        { Manage.Trade t = buy(); Candle[] cs = cat(flat(3, 99, 101), c(3, 100, 100.5, 94.5, 95));
          List<Manage.Event> e = Manage.update(t, cs, START + 3 * G + 30, CFG);
          ok("7 forming wick stops", has(e, "stopped")); }
        // 8 stall once
        { Manage.Trade t = buy(); Candle[] cs = flat(50, 99.8, 100.4);
          List<Manage.Event> e = Manage.update(t, cs, NOW, CFG); ok("8 stall", has(e, "stall"));
          ok("8 stall only once", !has(Manage.update(t, cs, NOW, CFG), "stall")); }
        // 9 liquidity before target
        { Manage.Trade t = buy(); List<Candle> l = new ArrayList<>();
          for (int i = 0; i < 25; i++) l.add(c(i, 100, 101, 99, 100));
          l.add(c(25, 100, 102, 99.5, 101)); l.add(c(26, 101, 104, 100.5, 103)); l.add(c(27, 103, 106.5, 102.5, 104)); // swing high 106.5 at 27
          l.add(c(28, 104, 105, 102.8, 103.5)); l.add(c(29, 103.5, 104.5, 102.5, 103)); l.add(c(30, 103, 104, 102.2, 102.8)); l.add(c(31, 102.8, 103.8, 102.3, 103));
          List<Manage.Event> e = Manage.update(t, l.toArray(new Candle[0]), NOW, CFG);
          ok("9 liquidity event", has(e, "liquidity")); ok("9 not repeated", !has(Manage.update(t, l.toArray(new Candle[0]), NOW, CFG), "liquidity")); }
        // 10 candles before the trade was logged are ignored
        { Manage.Trade t = buy(); t.startedAt = START + 5 * G; Candle[] cs = cat(flat(3, 99, 101), c(3, 100, 100.5, 90, 91));
          List<Manage.Event> e = Manage.update(t, cs, NOW, CFG); ok("10 old candle ignored", e.isEmpty() && t.status.equals("open")); }

        // entry styles
        { Setup s = new Setup(); s.side = "BUY"; s.zoneLow = 98; s.zoneHigh = 100; s.entry = 99; s.sl = 95; s.tp = 112; s.rr = 13.0 / 4;
          Setup a = Mtf.applyEntryMode(s, 0, 2), m = Mtf.applyEntryMode(s, 1, 2), d = Mtf.applyEntryMode(s, 2, 2);
          near("edge entry", a.entry, 100); near("mid entry", m.entry, 99); near("deep entry", d.entry, 98);
          near("deep rr", d.rr, 14.0 / 3); near("edge rr", a.rr, 12.0 / 5);
          ok("deep risk smaller", Math.abs(d.entry - d.sl) < Math.abs(a.entry - a.sl)); ok("original untouched", s.entry == 99);
          Setup t = s.copy(); t.tp = 100.5; near("rr floor keeps middle", Mtf.applyEntryMode(t, 0, 2).entry, 99);
          Setup se = new Setup(); se.side = "SELL"; se.zoneLow = 100; se.zoneHigh = 102; se.entry = 101; se.sl = 105; se.tp = 88;
          near("sell edge", Mtf.applyEntryMode(se, 0, 2).entry, 100); near("sell deep", Mtf.applyEntryMode(se, 2, 2).entry, 102); }

        // confidence figure
        { Calibration.Book bk = new Calibration.Book(); Calibration c1 = new Calibration();
          c1.modes[1].gradeN[0] = 40; c1.modes[1].gradeWins[0] = 16; bk.items.put(Calibration.Book.key("R_75", "4H", "15m"), c1);
          Calibration.Estimate e = bk.estimate("R_75", "4H", "15m", 1, "A", 3);
          near("pct", e.pct, 40); ok("range brackets pct", e.lo < 40 && e.hi > 40 && e.lo > 20 && e.hi < 62); near("break-even at 3R", e.breakEven, 25); ok("reliable", e.reliable);
          Calibration c2 = new Calibration(); c2.modes[1].gradeN[0] = 12; c2.modes[1].gradeWins[0] = 6; bk.items.put(Calibration.Book.key("R_50", "4H", "15m"), c2);
          Calibration c3 = new Calibration(); c3.modes[1].gradeN[0] = 5; c3.modes[1].gradeWins[0] = 1; bk.items.put(Calibration.Book.key("R_25", "1H", "5m"), c3);
          Calibration.Estimate p = bk.estimate("R_50", "4H", "15m", 1, "A", 3); ok("pooled when own too small", p.n == 57 && p.reliable && p.basis.contains("all tested pairs"));
          Calibration.Book small = new Calibration.Book(); small.items.put("k", c3);
          Calibration.Estimate q = small.estimate("R_25", "1H", "5m", 1, "A", 3); ok("small sample flagged", !q.reliable && q.n == 5);
          ok("no data gives null", small.estimate("R_25", "1H", "5m", 1, "B", 3) == null); }

        // calibration from real backtests of the three entry styles
        { Path d = Paths.get(args[0]);
          int[] filled = new int[3]; double[] total = new double[3];
          for (int k = 0; k < 6; k++) {
            Candle[] ltf = BacktestParity.read(d.resolve("ltf_" + k + ".csv")), htf = BacktestParity.read(d.resolve("htf_" + k + ".csv")), next = BacktestParity.read(d.resolve("next_" + k + ".csv"));
            List<List<Mtf.FactorTrade>> by = new ArrayList<>();
            for (int m = 0; m < 3; m++) {
              Mtf.Options o = new Mtf.Options(); o.nextCandles = next; o.nextGran = 14400; o.ltfGran = 300; o.entryMode = m;
              List<Mtf.FactorTrade> ts = Mtf.backtestMtfTrades(htf, ltf, 3600, CFG, o); by.add(ts);
              Backtest.Result r = Backtest.summarize(trades(ts)); filled[m] += r.filled; total[m] += r.totalR;
            }
            Calibration cal = Calibration.build(by, 1);
            ok("calibration best mode valid " + k, cal.bestMode >= 0 && cal.bestMode <= 2);
            int gn = 0; for (int g = 0; g < 3; g++) gn += cal.modes[1].gradeN[g]; ok("grade counts add up " + k, gn == cal.modes[1].filled);
          }
          System.out.println("Entry styles on test data (all 6 series): edge filled " + filled[0] + " totalR " + String.format("%.1f", total[0]) + " | middle " + filled[1] + " / " + String.format("%.1f", total[1]) + " | deep " + filled[2] + " / " + String.format("%.1f", total[2]));
        }
        System.out.println(fails == 0 ? "NEW FEATURE TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }
    static List<Backtest.Trade> trades(List<Mtf.FactorTrade> f) { List<Backtest.Trade> o = new ArrayList<>(); for (Mtf.FactorTrade t : f) o.add(t.trade); return o; }
}
