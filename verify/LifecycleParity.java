import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

/** Replays the same market walk as the TypeScript scanner and compares what it would alert and track. */
public class LifecycleParity {
    static Candle[] read(Path p) throws Exception {
        List<Candle> o = new ArrayList<>();
        for (String l : Files.readAllLines(p)) { if (l.isEmpty()) continue; String[] t = l.split(","); o.add(new Candle(Long.parseLong(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]))); }
        return o.toArray(new Candle[0]);
    }
    static String num(double v) { return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v); }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        List<String> mine = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            final Candle[] ltf = read(d.resolve("ltf_" + k + ".csv")), htf = read(d.resolve("htf_" + k + ".csv")), next = read(d.resolve("next_" + k + ".csv"));
            for (String mode : new String[]{"top", "single"}) {
                Scan.Config cfg = new Scan.Config();
                cfg.markets.add(new Scan.Market("X", "Test"));
                cfg.timeframes.add(new Scan.Timeframe("5m", 300)); cfg.timeframes.add(new Scan.Timeframe("1H", 3600)); cfg.timeframes.add(new Scan.Timeframe("4H", 14400));
                cfg.pairs.add(new Scan.Pair("1H", mode.equals("top") ? "5m" : null));
                Lifecycle.State state = new Lifecycle.State();
                final long[] now = {0};
                Scan.Candles client = (sym, gran, count) -> {
                    Candle[] src = gran == 300 ? ltf : gran == 3600 ? htf : next;
                    int n = 0; while (n < src.length && src[n].epoch <= now[0]) n++;
                    return Arrays.copyOfRange(src, Math.max(0, n - count), n);
                };
                for (int i = 300; i < 1500; i++) {
                    now[0] = htf[i].epoch + 1800;
                    Scan.Result r = Scan.scanAll(client, state, cfg, now[0]);
                    for (Lifecycle.Msg m : r.msgs) m.apply.run();
                    StringBuilder kinds = new StringBuilder();
                    for (Lifecycle.Msg m : r.msgs) { if (kinds.length() > 0) kinds.append('+'); kinds.append(m.kind); }
                    List<String> tr = new ArrayList<>();
                    for (Lifecycle.Tracked t : state.tracked.values())
                        tr.add(t.setup.id + ":" + t.status + ":" + (t.entry != null ? num(t.entry.entry) : "-") + ":" + (t.breakdown != null ? (t.breakdown.levels.tp2 != null ? num(t.breakdown.levels.tp2) : "n") : "-"));
                    Collections.sort(tr);
                    if (kinds.length() > 0 || i % 50 == 0) mine.add(k + "|" + mode + "|" + i + "|" + kinds + "|" + r.ok + "|" + r.failed + "|" + String.join(";", tr));
                }
            }
        }
        List<String> exp = new ArrayList<>();
        for (String l : Files.readAllLines(d.resolve("expected_lc.txt"))) if (!l.isEmpty()) exp.add(l);
        int bad = 0;
        for (int i = 0; i < Math.max(exp.size(), mine.size()); i++) {
            String e = i < exp.size() ? exp.get(i) : "(none)", m = i < mine.size() ? mine.get(i) : "(none)";
            if (!e.equals(m)) { bad++; if (bad <= 5) System.out.println("DIFF line " + i + "\n  ts:   " + e + "\n  java: " + m); }
        }
        System.out.println(bad == 0 ? "LIFECYCLE PARITY OK: " + exp.size() + " steps identical" : "FAILED: " + bad + " differing steps");
        if (bad != 0) System.exit(1);
    }
}
