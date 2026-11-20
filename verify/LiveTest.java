import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import com.smcscanner.engine.pa.*;
import java.nio.file.*;
import java.util.*;

public class LiveTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; if (fails < 20) System.out.println("FAIL: " + m); } }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int before = 0, after = 0, windows = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m15 = ZoomTest.agg(m5, 3), m30 = ZoomTest.agg(m5, 6), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
            for (int i = 3000; i < m15.length - 2; i += 97) {
                long now = m15[i].epoch + 900;
                Candle[] w15 = PaReplay.upToPublic(m15, 900, now, 300), w30 = PaReplay.upToPublic(m30, 1800, now, 300), w1 = PaReplay.upToPublic(h1, 3600, now, 300), w4 = PaReplay.upToPublic(h4, 14400, now, 200);
                if (w15 == null || w30 == null || w1 == null || w4 == null) continue;
                List<PaEngine.Signal> all = new ArrayList<>();
                all.addAll(PaEngine.analyse("S" + k, "S", w4, w1, now));
                all.addAll(PaEngine.analyseFrame("S" + k, "S", "30m", 1800, w30, w1, "1H", w4, w1, w30, now));
                all.addAll(PaEngine.analyseFrame("S" + k, "S", "15m", 900, w15, w1, "1H", w4, w1, w30, now));
                List<PaEngine.Signal> m = PaEngine.merge(all, 0.004);
                windows++; before += all.size(); after += m.size();
                ok("merge never adds", m.size() <= all.size());
                for (int x = 0; x < m.size(); x++) for (int y = x + 1; y < m.size(); y++) {
                    PaEngine.Signal p = m.get(x), q = m.get(y);
                    if (p.side.equals(q.side)) { double r1 = p.entry != 0 ? p.entry : p.level, r2 = q.entry != 0 ? q.entry : q.level; ok("no near-duplicate left", r1 <= 0 || r2 <= 0 || Math.abs(r1 - r2) / r2 > 0.004); }
                }
                // an actionable idea is never lost behind a watch idea
                boolean anyAct = false, keptAct = false;
                for (PaEngine.Signal s : all) anyAct |= s.actionable();
                for (PaEngine.Signal s : m) keptAct |= s.actionable();
                ok("actionable kept", !anyAct || keptAct);
            }
        }
        System.out.println("windows " + windows + ": signals before merge " + before + ", after " + after);
        // ---- live status
        long now = 1_700_000_000L;
        LiveTrack.Status s;
        s = LiveTrack.describe(true, 100, 98, 106, 103, 1, false, true, 0, now - 600, now);
        ok("waiting", s.code.equals("WAITING") && s.headline.contains("above the entry"));
        s = LiveTrack.describe(true, 100, 98, 106, 100, 1, false, true, 0, now - 600, now);
        ok("at entry", s.code.equals("AT_ENTRY"));
        s = LiveTrack.describe(true, 100, 98, 106, 97.5, 1, false, true, 0, now - 600, now);
        ok("invalidated before entry", s.code.equals("STOP_HIT") && s.headline.contains("Do not place"));
        s = LiveTrack.describe(true, 100, 98, 106, 107, 1, false, true, 0, now - 600, now);
        ok("missed", s.code.equals("MISSED"));
        s = LiveTrack.describe(true, 100, 98, 106, 101, 1, true, true, 0, now - 600, now);
        ok("in trade +0.5R", s.code.equals("IN_TRADE") && Math.abs(s.r - 0.5) < 1e-9 && s.tone == 1);
        s = LiveTrack.describe(false, 100, 102, 94, 99, 1, true, true, 0, now - 600, now);
        ok("sell in trade +0.5R", s.code.equals("IN_TRADE") && Math.abs(s.r - 0.5) < 1e-9);
        s = LiveTrack.describe(false, 100, 102, 94, 103, 1, true, true, 0, now - 600, now);
        ok("sell stop hit", s.code.equals("STOP_HIT"));
        s = LiveTrack.describe(true, 0, 0, 0, 101, 1, false, false, 105, now - 7200, now);
        ok("watching", s.code.equals("WATCHING") && s.headline.contains("ATR"));
        s = LiveTrack.describe(true, 100, 98, 106, 0, 1, false, true, 0, now, now);
        ok("no price handled", s.headline.contains("No live price"));
        // text changes as price changes
        String t1 = LiveTrack.describe(true, 100, 98, 106, 103, 1, false, true, 0, now, now).headline, t2 = LiveTrack.describe(true, 100, 98, 106, 102, 1, false, true, 0, now, now).headline;
        ok("description follows price", !t1.equals(t2));
        // manual trade
        Manage.Trade t = Manage.createManual("R_75", "Volatility 75", "1H", "1H", 3600, "BUY", 100, 98, 106, null, now, 0, "test");
        ok("manual trade open", t.status.equals("open") && t.isBuy() && t.stop0 == 98 && t.tp != null && t.tp == 106.0);
        Manage.Trade t2n = Manage.createManual("R_75", "Volatility 75", "1H", "1H", 3600, "SELL", 100, 102, 0, null, now, 0, "");
        ok("manual trade with no target", t2n.tp == null);
        System.out.println("LIVE TESTS: " + checks + " checks, " + fails + " failures");
    }
}
