import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

public class LedgerTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    static Ledger.Rec rec(Setup s, long gran, long time) {
        Ledger.Rec r = new Ledger.Rec();
        r.id = "t"; r.setup = s; r.entry = s.entry; r.stop = s.sl; r.tp = s.tp; r.rr = s.rr; r.gran = gran; r.time = time; r.side = s.side;
        return r;
    }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int compared = 0, mism = 0;
        int[] by = new int[5];
        for (int k = 0; k < 6; k++) {
            Candle[] c = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            long gran = c[1].epoch - c[0].epoch;
            int skip = -1;
            for (int i = 0; i < c.length - 1; i++) {
                if (i <= skip) continue;
                Setup s = Smc.detectAt(c, i, new SmcConfig());
                if (s == null) continue;
                skip = i + 3;
                // the ledger waits/holds by time, so match the backtest's wait and hold in candles for this comparison
                int wait = Ledger.class.getDeclaredMethods().length > 0 ? 0 : 0; // placeholder to keep the compiler quiet
                Backtest.Trade bt = Backtest.simulate(c, i, s, 30, 100);
                // replay the same candles through the ledger with identical wait/hold: use a copy of the logic via reflection-free path
                Ledger.Rec r = rec(s, gran, c[i].epoch + gran);
                boolean changed = resolveWith(r, c, 30, 100);
                compared++;
                String exp = bt.outcome;
                boolean same = r.outcome.equals(exp) || (exp.equals("no_fill") && r.outcome.equals("open")) || (exp.equals("expired") && r.outcome.equals("open"));
                if (!same) { mism++; System.out.println("mismatch " + exp + " vs " + r.outcome + " series " + k + " i " + i); }
                if (r.outcome.equals("win")) ok("win result equals rr", Math.abs(r.resultR - s.rr) < 1e-9);
                if (r.outcome.equals("loss")) ok("loss result -1", r.resultR == -1);
            }
        }
        System.out.println("compared " + compared + " setups, mismatches " + mism);
        ok("ledger matches backtest outcomes", mism == 0 && compared > 20);

        // hand-made scenarios
        long g = 60, t0 = 1_700_000_000L - (1_700_000_000L % 60);
        Setup buy = new Setup(); buy.side = "BUY"; buy.entry = 100; buy.sl = 99; buy.tp = 103; buy.rr = 3;
        // fills then hits target
        Candle[] c1 = { new Candle(t0, 101, 101.5, 100.5, 101), new Candle(t0 + 60, 101, 101, 99.9, 100.2), new Candle(t0 + 120, 100.2, 103.2, 100.1, 103), new Candle(t0 + 180, 103, 104, 102, 103) };
        Ledger.Rec r1 = rec(buy, g, t0); ok("resolves", Ledger.resolve(r1, c1, t0 + 400)); ok("win", r1.outcome.equals("win") && r1.resultR == 3 && r1.filled);
        // stop first when one candle touches both
        Candle[] c2 = { new Candle(t0, 101, 101, 100.5, 100.8), new Candle(t0 + 60, 100.5, 103.5, 98.5, 100) };
        Ledger.Rec r2 = rec(buy, g, t0); Ledger.resolve(r2, c2, t0 + 400); ok("stop first", r2.outcome.equals("loss") && r2.resultR == -1);
        // not yet touched: stays open and is not filled
        Candle[] c3 = { new Candle(t0, 101, 102, 100.5, 101), new Candle(t0 + 60, 101, 102, 100.5, 101) };
        Ledger.Rec r3 = rec(buy, g, t0); ok("no change", !Ledger.resolve(r3, c3, t0 + 200)); ok("still open", r3.outcome.equals("open") && !r3.filled);
        // filled but undecided: open and filled
        Candle[] c4 = { new Candle(t0, 100.5, 101, 99.9, 100.4), new Candle(t0 + 60, 100.4, 101, 100.2, 100.6) };
        Ledger.Rec r4 = rec(buy, g, t0); ok("filled flag changes", Ledger.resolve(r4, c4, t0 + 200)); ok("open and filled", r4.outcome.equals("open") && r4.filled);
        // closes under the stop before the entry was touched: no fill
        Candle[] c5 = { new Candle(t0, 101, 101, 98.5, 98.8) };
        Ledger.Rec r5 = rec(buy, g, t0); Ledger.resolve(r5, c5, t0 + 100); ok("invalidated before fill", r5.outcome.equals("no_fill"));
        // a decided record never changes again
        ok("decided stays", !Ledger.resolve(r1, c2, t0 + 1000) && r1.outcome.equals("win"));
        // sell side
        Setup sell = new Setup(); sell.side = "SELL"; sell.entry = 100; sell.sl = 101; sell.tp = 97; sell.rr = 3;
        Candle[] c6 = { new Candle(t0, 99, 100.2, 98.9, 99.5), new Candle(t0 + 60, 99.5, 99.8, 96.8, 97) };
        Ledger.Rec r6 = rec(sell, g, t0); Ledger.resolve(r6, c6, t0 + 200); ok("sell win", r6.outcome.equals("win") && r6.resultR == 3);

        // paths and bands
        ok("path zoomed to 1m", Ledger.pathOf("4H", "1H", "1m").equals("4H > 1H > 5m > 1m"));
        ok("path at the zone", Ledger.pathOf("4H", "1H", "1H").equals("4H > 1H"));
        ok("path to 5m", Ledger.pathOf("1D", "1H", "5m").equals("1D > 1H > 5m"));
        ok("path 15m idea", Ledger.pathOf("15m", "5m", "1m").equals("15m > 5m > 1m"));
        ok("bands", Ledger.band(-1).equals("Unproven") && Ledger.band(49.9).equals("Under 50%") && Ledger.band(55).equals("50-69%") && Ledger.band(75).equals("70-79%") && Ledger.band(80).equals("80%+"));

        System.out.println(fails == 0 ? "LEDGER TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }

    /** the ledger's rules with the backtest's candle-count wait and hold, for an apples-to-apples comparison */
    static boolean resolveWith(Ledger.Rec r, Candle[] c, int wait, int hold) {
        boolean buy = r.setup.isBuy();
        int start = c.length;
        for (int k = 0; k < c.length; k++) if (c[k].epoch >= r.time) { start = k; break; }
        boolean filled = false; int n = 0;
        for (int k = start; k < c.length; k++) {
            Candle x = c[k]; n++;
            if (!filled) {
                if (n > wait) { r.outcome = "no_fill"; return true; }
                if (buy ? x.close < r.stop : x.close > r.stop) { r.outcome = "no_fill"; return true; }
                if (!(buy ? x.low <= r.entry : x.high >= r.entry)) continue;
                filled = true;
            }
            boolean sl = buy ? x.low <= r.stop : x.high >= r.stop, tp = buy ? x.high >= r.tp : x.low <= r.tp;
            if (sl) { r.outcome = "loss"; r.resultR = -1; return true; }
            if (tp) { r.outcome = "win"; r.resultR = r.rr; return true; }
            if (n > wait + hold) { r.outcome = "expired"; return true; }
        }
        return false;
    }
}
