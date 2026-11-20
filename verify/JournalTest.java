import com.smcscanner.engine.*;
import java.util.*;

public class JournalTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    static Manage.Trade t(String status, double r, long end) {
        Manage.Trade x = new Manage.Trade();
        x.status = status; x.resultR = r; x.lastEpoch = end;
        return x;
    }

    public static void main(String[] a) {
        List<Manage.Trade> ts = new ArrayList<>();
        ts.add(t("closed", 2.0, 1000));
        ts.add(t("closed", -1.0, 1100));
        ts.add(t("closed", -1.0, 2100));
        ts.add(t("closed", 0.0, 2200));
        ts.add(t("closed", 3.0, 2300));
        ts.add(t("open", 0.4, 2400));
        Journal.Summary all = Journal.summary(ts, 0);
        ok("closed count", all.closed == 5);
        ok("open count", all.open == 1);
        ok("wins/losses/breakeven", all.wins == 2 && all.losses == 2 && all.breakeven == 1);
        ok("total R", Math.abs(all.totalR - 3.0) < 1e-9);
        ok("avg R", Math.abs(all.avgR - 0.6) < 1e-9);
        ok("win rate counts only decided trades", Math.abs(all.winRate - 50.0) < 1e-9);
        ok("best and worst", all.bestR == 3.0 && all.worstR == -1.0);
        Journal.Summary today = Journal.summary(ts, 2000);
        ok("today closed", today.closed == 3 && today.losses == 1 && today.wins == 1);
        ok("empty list is safe", Journal.summary(new ArrayList<Manage.Trade>(), 0).closed == 0);
        ok("loss limit off", !Journal.limitReached(ts, 0, 0));
        ok("loss limit not yet (1 of 2)", !Journal.limitReached(ts, 2000, 2));
        ok("loss limit reached (1 of 1)", Journal.limitReached(ts, 2000, 1));
        ok("loss limit counts the whole list from 0", Journal.limitReached(ts, 0, 2));
        ok("open trades do not count as losses", !Journal.limitReached(Arrays.asList(t("open", -1.0, 3000)), 0, 1));
        ok("quiet off when equal", !Journal.inQuietHours(3, 5, 5));
        ok("quiet inside", Journal.inQuietHours(10, 9, 17) && !Journal.inQuietHours(17, 9, 17) && !Journal.inQuietHours(8, 9, 17));
        ok("quiet wraps midnight", Journal.inQuietHours(23, 22, 6) && Journal.inQuietHours(2, 22, 6) && !Journal.inQuietHours(6, 22, 6) && !Journal.inQuietHours(12, 22, 6));
        ok("risk from account", Journal.riskFromAccount(1000, 1) == 10 && Journal.riskFromAccount(500, 0.25) == 1 && Journal.riskFromAccount(100, 0.25) == 1);
        ok("risk from account off", Journal.riskFromAccount(0, 1) == 0 && Journal.riskFromAccount(1000, 0) == 0);
        System.out.println(fails == 0 ? "JOURNAL TESTS OK: " + checks + " checks" : "FAILED " + fails + " of " + checks);
        if (fails != 0) System.exit(1);
    }
}
