package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Walk-forward simulation of setups. */
public final class Backtest {
    private Backtest() {}

    public static final class Trade implements Serializable {
        private static final long serialVersionUID = 1L;
        public final Setup setup;
        /** "win", "loss", "no_fill" or "expired" */
        public final String outcome;
        /** win = +rr, loss = -1 */
        public final double r;
        Trade(Setup s, String o, double r) { setup = s; outcome = o; this.r = r; }
        public boolean decided() { return outcome.equals("win") || outcome.equals("loss"); }
    }

    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;
        public int setups, filled, wins, losses, maxLosingStreak;
        public double winRate, avgR, totalR;
    }

    /**
     * The limit entry fills when price trades back to the entry; a setup is cancelled if price closes beyond
     * the stop first. If a candle touches both stop and target the STOP is assumed first (pessimistic).
     */
    public static Trade simulate(Candle[] c, int triggerIdx, Setup s, int maxWait, int maxHold) {
        return simulate(c, triggerIdx, s, maxWait, maxHold, 0);
    }

    /**
     * Same as above, but with beR > 0 the stop moves to the entry once a candle has reached beR times the risk in profit
     * (from the next candle on, so a candle that reaches 1R and also hits the old stop still counts as a loss).
     * A trade stopped at the entry afterwards is "be" (0R).
     */
    public static Trade simulate(Candle[] c, int triggerIdx, Setup s, int maxWait, int maxHold, double beR) {
        boolean buy = s.isBuy();
        boolean filled = false;
        double risk = Math.abs(s.entry - s.sl);
        double stop = s.sl;
        boolean atBe = false;
        for (int k = triggerIdx + 1; k < c.length; k++) {
            Candle x = c[k];
            if (!filled) {
                if (k - triggerIdx > maxWait) return new Trade(s, "no_fill", 0);
                boolean invalid = buy ? x.close < s.sl : x.close > s.sl;
                if (invalid) return new Trade(s, "no_fill", 0);
                boolean touched = buy ? x.low <= s.entry : x.high >= s.entry;
                if (!touched) continue;
                filled = true;
            }
            boolean hitSl = buy ? x.low <= stop : x.high >= stop;
            boolean hitTp = buy ? x.high >= s.tp : x.low <= s.tp;
            if (hitSl) return atBe ? new Trade(s, "be", 0) : new Trade(s, "loss", -1);
            if (hitTp) return new Trade(s, "win", s.rr);
            if (beR > 0 && !atBe && risk > 0 && (buy ? x.high >= s.entry + beR * risk : x.low <= s.entry - beR * risk)) { atBe = true; stop = s.entry; }
            if (k - triggerIdx > maxWait + maxHold) return new Trade(s, "expired", 0);
        }
        return new Trade(s, filled ? "expired" : "no_fill", 0);
    }

    public static Result backtest(Candle[] c, SmcConfig cfg) {
        List<Trade> trades = new ArrayList<>();
        int skipUntil = -1;
        for (int i = 0; i < c.length - 1; i++) {
            if (i <= skipUntil) continue;
            Setup s = Smc.detectAt(c, i, cfg);
            if (s == null) continue;
            trades.add(simulate(c, i, s, 30, 100));
            skipUntil = i + 3;
        }
        return summarize(trades);
    }

    public static Result summarize(List<Trade> trades) {
        int n = 0, wins = 0, streak = 0, maxStreak = 0;
        double totalR = 0;
        for (Trade t : trades) {
            if (!t.decided()) continue;
            n++;
            totalR += t.r;
            if (t.outcome.equals("win")) { wins++; streak = 0; }
            else { streak++; maxStreak = Math.max(maxStreak, streak); }
        }
        Result r = new Result();
        r.setups = trades.size();
        r.filled = n;
        r.wins = wins;
        r.losses = n - wins;
        r.winRate = n > 0 ? (wins * 100.0) / n : 0;
        r.avgR = n > 0 ? totalR / n : 0;
        r.totalR = totalR;
        r.maxLosingStreak = maxStreak;
        return r;
    }

    public static Result merge(List<Result> rs) {
        int wins = 0, losses = 0, setups = 0, streak = 0;
        double totalR = 0;
        for (Result x : rs) { wins += x.wins; losses += x.losses; totalR += x.totalR; setups += x.setups; streak = Math.max(streak, x.maxLosingStreak); }
        int filled = wins + losses;
        Result r = new Result();
        r.setups = setups; r.filled = filled; r.wins = wins; r.losses = losses;
        r.winRate = filled > 0 ? (wins * 100.0) / filled : 0;
        r.avgR = filled > 0 ? totalR / filled : 0;
        r.totalR = totalR; r.maxLosingStreak = streak;
        return r;
    }
}
