import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

public class SectionsTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int texts = 0;
        for (int k = 0; k < 6 && texts < 3; k++) {
            final Candle[] ltf = BacktestParity.read(d.resolve("ltf_" + k + ".csv")), htf = BacktestParity.read(d.resolve("htf_" + k + ".csv")), next = BacktestParity.read(d.resolve("next_" + k + ".csv"));
            Scan.Config cfg = new Scan.Config();
            cfg.markets.add(new Scan.Market("X", "Test"));
            cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600)); cfg.timeframes.add(new Scan.Timeframe("4H", 14400));
            cfg.pairs.add(new Scan.Pair("1H", "5m"));
            cfg.entryMode = 1; cfg.riskMoney = 10;
            Lifecycle.State state = new Lifecycle.State();
            final long[] now = {0};
            Scan.Candles client = (sym, gran, count) -> {
                Candle[] src = gran == 300 ? ltf : gran == 3600 ? htf : next;
                int n = 0; while (n < src.length && src[n].epoch <= now[0]) n++;
                return Arrays.copyOfRange(src, Math.max(0, n - count), n);
            };
            for (int i = 300; i < htf.length - 2 && texts < 3; i++) {
                now[0] = htf[i].epoch + 1800;
                for (Lifecycle.Msg m : Scan.scanAll(client, state, cfg, now[0]).msgs) {
                    m.apply.run();
                    if (!m.kind.equals("entry")) continue;
                    texts++;
                    List<String[]> secs = Breakdowns.sections(m.text);
                    StringBuilder titles = new StringBuilder();
                    for (String[] s : secs) titles.append("[").append(s[0]).append("] ");
                    System.out.println("sections: " + titles);
                    ok("has THE TRADE", titles.toString().contains("[The trade]"));
                    ok("has Risk and reward", titles.toString().contains("[Risk and reward]"));
                    ok("has Where the trade is invalidated", titles.toString().contains("Where the trade is invalidated"));
                    ok("at least 6 sections", secs.size() >= 6);
                    for (String[] s : secs) ok("section has a body: " + s[0], !s[1].isEmpty());
                    // nothing lost: all body text is still there
                    StringBuilder all = new StringBuilder();
                    for (String[] s : secs) all.append(s[1]);
                    String flat = m.text.replaceAll("\\s+", "");
                    String got = all.toString().replaceAll("\\s+", "");
                    String heads = "";
                    for (String[] s : secs) heads += s[0].replaceAll("\\s+", "").toUpperCase();
                    ok("no body text lost", flat.length() >= got.length() && flat.length() <= got.length() + heads.length() + 40);
                }
            }
        }
        ok("found alert texts to test", texts > 0);
        System.out.println(fails == 0 ? "SECTIONS TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }
}
