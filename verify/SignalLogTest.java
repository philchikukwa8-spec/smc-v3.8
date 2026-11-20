import com.smcscanner.engine.*;
import java.nio.file.*;
import java.util.*;

public class SignalLogTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        int compared = 0, decided = 0, unfinished = 0;
        List<SignalLog.Record> all = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Candle[] c = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            long gran = 300;
            int waitN = (int) (SignalLog.WAIT_SEC / gran), holdN = (int) (SignalLog.HOLD_SEC / gran);
            int skip = -1;
            for (int i = 0; i < c.length - 1; i++) {
                if (i <= skip) continue;
                Setup s = Smc.detectAt(c, i, new SmcConfig());
                if (s == null) continue;
                skip = i + 3;
                Backtest.Trade ref = Backtest.simulate(c, i, s, waitN, holdN);
                SignalLog.Record r = SignalLog.create("t" + k + "_" + i, c[i].epoch + gran, "X", "Test", "1H > 5m", "1H", "5m", s, null, false, 1, 10);
                Candle[] after = Arrays.copyOfRange(c, i + 1, c.length);
                SignalLog.resolve(r, after, gran);
                compared++;
                if (r.finished()) {
                    ok("outcome matches the backtest " + k + "/" + i + " (" + r.outcome + " vs " + ref.outcome + ")", r.outcome.equals(ref.outcome));
                    ok("result R matches", Math.abs(r.resultR - ref.r) < 1e-9);
                    if (r.decided()) decided++;
                } else {
                    unfinished++;
                    ok("unfinished only when the data ran out", ref.outcome.equals("expired") || ref.outcome.equals("no_fill"));
                }
                // feeding the same candles in small pieces gives the same answer
                SignalLog.Record r2 = SignalLog.create("u", c[i].epoch + gran, "X", "Test", "1H > 5m", "1H", "5m", s, null, false, 1, 10);
                for (int p = 0; p < after.length && !r2.finished(); p += 7) SignalLog.resolve(r2, Arrays.copyOfRange(after, 0, Math.min(after.length, p + 7)), gran);
                ok("incremental = all at once", r2.outcome.equals(r.outcome) && r2.closedEpoch == r.closedEpoch && Math.abs(r2.resultR - r.resultR) < 1e-9);
                if (all.size() < 40) all.add(r);
            }
        }
        System.out.println("compared " + compared + " alerts (" + decided + " decided win/loss, " + unfinished + " still open at the end of the data)");
        ok("compared something", compared > 5 && decided > 0);

        // summary maths
        SignalLog.Summary sm = SignalLog.summary(all, 0);
        ok("summary counts add up", sm.alerts == all.size() && sm.wins + sm.losses == sm.resolved && sm.resolved + sm.open + sm.noFill + sm.expired == sm.alerts);
        double r = 0; for (SignalLog.Record x : all) if (x.decided()) r += x.resultR;
        ok("summary total R", Math.abs(sm.totalR - r) < 1e-9);

        // CSV: every row has as many fields as the header, quoting works, nothing is lost
        String csv = SignalLog.toCsv(all);
        String[] lines = csv.split("\n");
        ok("one line per record plus header", lines.length == all.size() + 1);
        for (String line : lines) ok("field count " + SignalLog.COLUMNS.length, fields(line) == SignalLog.COLUMNS.length);
        SignalLog.Record q = all.get(0);
        q.path = "4H > 1H, \"odd\" > 5m";
        String one = SignalLog.toCsv(Collections.singletonList(q)).split("\n")[1];
        ok("quoted field survives", one.contains("\"4H > 1H, \"\"odd\"\" > 5m\"") && fields(one) == SignalLog.COLUMNS.length);
        ok("header names", lines[0].startsWith("Time,Market,Side,Path,Idea TF") && lines[0].endsWith("My result R,Source"));

        // linking to your own trades
        SignalLog.Record lr = SignalLog.create("l", 1_000_000, "R_75", "Volatility 75", "1H > 5m", "1H", "5m", all.get(0).setup, null, false, 1, 10);
        Manage.Trade t = new Manage.Trade();
        t.symbol = "R_75"; t.side = lr.side; t.startedAt = 1_000_600; t.status = "open";
        SignalLog.linkTrades(Collections.singletonList(lr), Collections.singletonList(t));
        ok("trade linked", lr.taken && !lr.hasMyR);
        t.status = "closed"; t.resultR = 1.5;
        SignalLog.linkTrades(Collections.singletonList(lr), Collections.singletonList(t));
        ok("trade result copied", lr.taken && lr.hasMyR && Math.abs(lr.myR - 1.5) < 1e-9);
        Manage.Trade other = new Manage.Trade(); other.symbol = "R_50"; other.side = lr.side; other.startedAt = 1_000_600; other.status = "open";
        SignalLog.Record lr2 = SignalLog.create("l2", 1_000_000, "R_75", "Volatility 75", "1H > 5m", "1H", "5m", all.get(0).setup, null, false, 1, 10);
        SignalLog.linkTrades(Collections.singletonList(lr2), Collections.singletonList(other));
        ok("other market not linked", !lr2.taken);

        System.out.println(fails == 0 ? "SIGNAL LOG TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }

    static int fields(String line) {
        int n = 1; boolean inq = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') inq = !inq;
            else if (ch == ',' && !inq) n++;
        }
        return n;
    }
}
