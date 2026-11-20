import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Replays a market walk with calibration switched on and prints the first entry alert. */
public class AlertTextDemo {
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        for (int k = 0; k < 6; k++) {
            final Candle[] ltf = BacktestParity.read(d.resolve("ltf_" + k + ".csv")), htf = BacktestParity.read(d.resolve("htf_" + k + ".csv")), next = BacktestParity.read(d.resolve("next_" + k + ".csv"));
            Calibration.Book book = new Calibration.Book();
            List<List<Mtf.FactorTrade>> by = new ArrayList<>();
            for (int m = 0; m < 3; m++) { Mtf.Options o = new Mtf.Options(); o.nextCandles = next; o.nextGran = 14400; o.ltfGran = 300; o.entryMode = m; by.add(Mtf.backtestMtfTrades(htf, ltf, 3600, new SmcConfig(), o)); }
            Calibration cal = Calibration.build(by, 1);
            book.items.put(Calibration.Book.key("X", "1H", "5m"), cal);
            Scan.Config cfg = new Scan.Config();
            cfg.markets.add(new Scan.Market("X", "Test"));
            cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600)); cfg.timeframes.add(new Scan.Timeframe("4H", 14400));
            cfg.pairs.add(new Scan.Pair("1H", "5m"));
            cfg.entryMode = -1; cfg.book = book; cfg.riskMoney = 10;
            Lifecycle.State state = new Lifecycle.State();
            final long[] now = {0};
            Scan.Candles client = (sym, gran, count) -> {
                Candle[] src = gran == 300 ? ltf : gran == 3600 ? htf : next;
                int n = 0; while (n < src.length && src[n].epoch <= now[0]) n++;
                return Arrays.copyOfRange(src, Math.max(0, n - count), n);
            };
            for (int i = 300; i < htf.length - 2; i++) {
                now[0] = htf[i].epoch + 1800;
                Scan.Result r = Scan.scanAll(client, state, cfg, now[0]);
                for (Lifecycle.Msg m : r.msgs) {
                    m.apply.run();
                    if (m.kind.equals("entry") && cal.bestMode >= 0) {
                        System.out.println("series " + k + " best mode " + Calibration.MODE_NAMES[cal.bestMode] + "\n--- notification ---\n" + m.title + "\n" + m.body + "\n--- full text ---\n" + m.text);
                        // log it as a trade and walk forward with the manager
                        for (Lifecycle.Tracked t : state.tracked.values()) if (t.entry != null) {
                            Manage.Trade tr = Manage.create(t.entry, "X", "Test", "1H", "5m", 300, t.entry.entry, t.breakdown.levels.tp2, now[0], 10);
                            int shown = 0;
                            for (int j = i; j < htf.length - 2 && tr.status.equals("open") && shown < 8; j++) {
                                long nw = ltf[Math.min(ltf.length - 1, 12 * (j + 1))].epoch;
                                int hi = 0; while (hi < ltf.length && ltf[hi].epoch <= nw) hi++;
                                for (Manage.Event e : Manage.update(tr, Arrays.copyOfRange(ltf, Math.max(0, hi - 200), hi), nw, new SmcConfig())) { System.out.println("MANAGE> " + e.title + " :: " + e.body); shown++; }
                            }
                            System.out.println("trade status: " + Manage.status(tr));
                            return;
                        }
                    }
                }
            }
        }
        System.out.println("no entry found");
    }
}
