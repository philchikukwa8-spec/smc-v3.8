package com.smcscanner.engine;

import java.util.List;

/** Your own results from the trades you logged, and the daily limits built on them. Pure Java so it can be tested. */
public final class Journal {
    private Journal() {}

    public static final class Summary {
        public int closed, wins, losses, breakeven, open;
        public double totalR, avgR, winRate, bestR, worstR;
    }

    private static boolean isClosed(Manage.Trade t) { return t.status.equals("closed"); }

    /** closed trades that finished at or after sinceEpoch (seconds; 0 = all of them) */
    public static Summary summary(List<Manage.Trade> trades, long sinceEpoch) {
        Summary s = new Summary();
        boolean first = true;
        for (Manage.Trade t : trades) {
            if (!isClosed(t)) { s.open++; continue; }
            if (t.lastEpoch < sinceEpoch) continue;
            s.closed++;
            s.totalR += t.resultR;
            if (t.resultR > 0.05) s.wins++;
            else if (t.resultR < -0.05) s.losses++;
            else s.breakeven++;
            if (first || t.resultR > s.bestR) s.bestR = t.resultR;
            if (first || t.resultR < s.worstR) s.worstR = t.resultR;
            first = false;
        }
        s.avgR = s.closed > 0 ? s.totalR / s.closed : 0;
        int decided = s.wins + s.losses;
        s.winRate = decided > 0 ? 100.0 * s.wins / decided : 0;
        return s;
    }

    /** losing trades closed since the day started */
    public static int lossesSince(List<Manage.Trade> trades, long dayStartEpoch) {
        return summary(trades, dayStartEpoch).losses;
    }

    /** true when you have already taken maxLosses losing trades today (0 = no limit) */
    public static boolean limitReached(List<Manage.Trade> trades, long dayStartEpoch, int maxLosses) {
        return maxLosses > 0 && lossesSince(trades, dayStartEpoch) >= maxLosses;
    }

    /** hour 0..23. from == to means no quiet hours; the window may wrap past midnight (22 to 6). */
    public static boolean inQuietHours(int hour, int from, int to) {
        if (from == to) return false;
        if (from < to) return hour >= from && hour < to;
        return hour >= from || hour < to;
    }

    /** money to risk from your account size and a risk percentage, at least 1 */
    public static int riskFromAccount(double account, double percent) {
        if (account <= 0 || percent <= 0) return 0;
        return (int) Math.max(1, Math.round(account * percent / 100.0));
    }
}
