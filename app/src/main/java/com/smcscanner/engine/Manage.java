package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static com.smcscanner.engine.Fmt.d1;
import static com.smcscanner.engine.Fmt.f;

/**
 * Trade management once you are in a trade. Walks the candles that closed since you entered and tells you
 * when to move the stop to break-even, where to trail it, when to take profit, when to extend the target,
 * and when the trade has been stopped out or reached its target. Rules are mechanical and the same
 * every time; they are guidance, not a guarantee.
 */
public final class Manage {
    private Manage() {}

    public static final class Trade implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id, symbol, label, ideaTf, entryTf, side;
        public long entryGran;
        public double entry, stop0, tp1;
        /** next-higher-timeframe target (null = none) */
        public Double tp2;
        /** where your stop should be NOW (starts at the planned stop, then follows the advice) */
        public double stop;
        /** where your take-profit should be NOW (null = no target left, just trail) */
        public Double tp;
        /** 0 = open, 1 = stop at break-even, 2 = target 1 reached */
        public int stage;
        /** epoch seconds when you logged the trade; only candles after this count */
        public long startedAt;
        public long lastEpoch;
        /** "open" or "closed" */
        public String status = "open";
        public String closeReason = "";
        public double exitPrice, resultR, maxR, lastPrice, lastR, riskMoney;
        public Double liqWarned;
        public boolean stallWarned;
        public final ArrayList<String> log = new ArrayList<>();
        /** where the trade came from: "Price action: Double bottom (15m)", "Fib pullback", "SMC", or "" when typed in by hand */
        public String origin = "";
        /** the setup state when you entered ("QUALIFIED", "WATCH"...) so the record shows whether it met every condition */
        public String originState = "";

        public boolean isBuy() { return "BUY".equals(side); }
        public double risk() { return Math.abs(entry - stop0); }
    }

    /** Something worth telling the user. */
    public static final class Event implements Serializable {
        private static final long serialVersionUID = 1L;
        /** "breakeven", "trail", "tp1", "tp_final", "stopped", "liquidity", "stall" */
        public final String kind;
        public final String title, body;
        public Event(String kind, String title, String body) { this.kind = kind; this.title = title; this.body = body; }
    }

    public static Trade create(Setup s, String symbol, String label, String ideaTf, String entryTf, long entryGran,
                               double actualEntry, Double tp2, long nowEpoch, double riskMoney) {
        Trade t = new Trade();
        t.id = symbol + "|" + entryTf + "|" + nowEpoch;
        t.symbol = symbol; t.label = label; t.ideaTf = ideaTf; t.entryTf = entryTf; t.entryGran = entryGran;
        t.side = s.side;
        t.entry = actualEntry;
        t.stop0 = s.sl; t.stop = s.sl;
        t.tp1 = s.tp; t.tp = s.tp; t.tp2 = tp2;
        t.startedAt = nowEpoch; t.lastEpoch = 0;
        t.riskMoney = riskMoney;
        t.lastPrice = actualEntry;
        t.log.add("Trade logged: entry " + f(actualEntry) + ", stop " + f(s.sl) + ", target " + f(s.tp));
        return t;
    }

    /** a trade the user entered by hand (from any setup card or typed in), whether or not it met every condition */
    public static Trade createManual(String symbol, String label, String ideaTf, String entryTf, long entryGran, String side,
                                     double entry, double stop, double tp, Double tp2, long nowEpoch, double riskMoney, String source) {
        Trade t = new Trade();
        t.id = symbol + "|" + entryTf + "|" + nowEpoch;
        t.symbol = symbol; t.label = label; t.ideaTf = ideaTf; t.entryTf = entryTf; t.entryGran = entryGran;
        t.side = side;
        t.entry = entry; t.stop0 = stop; t.stop = stop;
        t.tp1 = tp; t.tp = tp > 0 ? Double.valueOf(tp) : null; t.tp2 = tp2;
        t.startedAt = nowEpoch; t.lastEpoch = 0;
        t.riskMoney = riskMoney;
        t.lastPrice = entry;
        t.log.add("Trade logged by you" + (source.isEmpty() ? "" : " (" + source + ")") + ": entry " + f(entry) + ", stop " + f(stop) + (tp > 0 ? ", target " + f(tp) : ", no target"));
        return t;
    }

    private static double rOf(Trade t, double price) {
        double r = t.risk();
        if (!(r > 0)) return 0;
        return (t.isBuy() ? price - t.entry : t.entry - price) / r;
    }

    private static double avgRange(Candle[] c, int upTo) {
        int from = Math.max(0, upTo - 19);
        double sum = 0;
        for (int i = from; i <= upTo; i++) sum += c[i].high - c[i].low;
        return sum / (upTo - from + 1);
    }

    private static String pl(Trade t, double r) {
        String s = (r >= 0 ? "+" : "") + d1(r) + "R";
        if (t.riskMoney > 0) s += " (" + (r >= 0 ? "+$" : "-$") + String.format(java.util.Locale.US, "%.2f", Math.abs(r * t.riskMoney)) + ")";
        return s;
    }

    private static void close(Trade t, double price, String reason, long epoch) {
        t.status = "closed"; t.exitPrice = price; t.closeReason = reason;
        t.resultR = rOf(t, price); t.lastPrice = price; t.lastR = t.resultR;
        t.log.add(when(epoch) + "  Closed: " + reason + " at " + f(price) + " (" + pl(t, t.resultR) + ")");
    }

    private static String when(long epoch) {
        return new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(new java.util.Date(epoch * 1000));
    }

    private static Event add(List<Event> out, Trade t, String kind, String title, String body, long epoch) {
        Event e = new Event(kind, title, body);
        out.add(e);
        t.log.add(when(epoch) + "  " + title + ": " + body);
        return e;
    }

    /**
     * Brings the trade up to date with the latest candles of the entry timeframe (raw, the last may be forming).
     * Returns the events to show. Changes the trade in place.
     */
    public static List<Event> update(Trade t, Candle[] raw, long now, SmcConfig cfg) {
        List<Event> out = new ArrayList<>();
        if (!t.status.equals("open") || raw.length == 0) return out;
        final boolean buy = t.isBuy();
        final double risk = t.risk();
        if (!(risk > 0)) return out;
        Candle[] closed = Smc.closedOnly(raw, t.entryGran, now);
        String head = (buy ? "BUY " : "SELL ") + t.label;

        // first closed candle that belongs to the trade
        int startIdx = closed.length;
        for (int i = 0; i < closed.length; i++) if (closed[i].epoch >= t.startedAt) { startIdx = i; break; }

        for (int i = startIdx; i < closed.length && t.status.equals("open"); i++) {
            Candle c = closed[i];
            if (c.epoch <= t.lastEpoch) continue;
            boolean stopHit = buy ? c.low <= t.stop : c.high >= t.stop;
            boolean tpHit = t.tp != null && (buy ? c.high >= t.tp : c.low <= t.tp);
            if (stopHit) { // stop is assumed first when both are touched in one candle
                double r = rOf(t, t.stop);
                String why = t.stage == 0 ? "stopped out" : "stopped out in profit (trailing stop)";
                close(t, t.stop, why, c.epoch);
                add(out, t, "stopped", head + ": " + why, "Price touched your stop " + f(t.stop) + ". Result " + pl(t, r) + ". The trade is over.", c.epoch);
                break;
            }
            if (tpHit) {
                double hit = t.tp;
                if (t.stage < 2) {
                    t.stage = 2;
                    double be = t.entry;
                    boolean raise = buy ? be > t.stop : be < t.stop;
                    if (raise) t.stop = be;
                    boolean more = t.tp2 != null && (buy ? t.tp2 > hit : t.tp2 < hit);
                    t.tp = more ? t.tp2 : null;
                    String adv = "Target 1 " + f(hit) + " reached (" + pl(t, rOf(t, hit)) + "). Take partial profit (for example half). "
                        + "Keep your stop at " + f(t.stop) + " (break-even or better). "
                        + (more ? "Move the remaining target to " + f(t.tp2) + " (next-timeframe liquidity)." : "No higher target found: keep trailing the stop and let it run.");
                    add(out, t, "tp1", head + ": target 1 reached", adv, c.epoch);
                } else {
                    close(t, hit, "target reached", c.epoch);
                    add(out, t, "tp_final", head + ": final target reached", "Price reached " + f(hit) + " (" + pl(t, t.resultR) + "). Close the rest.", c.epoch);
                    break;
                }
            }
            double fav = buy ? (c.high - t.entry) / risk : (t.entry - c.low) / risk;
            if (fav > t.maxR) t.maxR = fav;
            // 1R in profit: stop to break-even
            if (t.stage == 0 && t.maxR >= 1.0) {
                t.stage = 1;
                boolean raise = buy ? t.entry > t.stop : t.entry < t.stop;
                if (raise) {
                    t.stop = t.entry;
                    add(out, t, "breakeven", head + ": move stop to break-even",
                        "Price has gone 1R in your favour. Move your stop to your entry " + f(t.entry) + ". You can no longer lose on this trade.", c.epoch);
                }
            }
            // trail behind the latest confirmed swing once at break-even
            if (t.stage >= 1) {
                List<Smc.Swing> sw = buy ? Smc.swingLows(closed, 3, startIdx, i) : Smc.swingHighs(closed, 3, startIdx, i);
                if (!sw.isEmpty()) {
                    double level = sw.get(sw.size() - 1).level;
                    double buf = cfg.slBuffer * avgRange(closed, i);
                    double cand = buy ? level - buf : level + buf;
                    boolean better = buy ? cand > t.stop + 0.1 * risk && cand < c.close : cand < t.stop - 0.1 * risk && cand > c.close; // ignore tiny nudges
                    if (better) {
                        t.stop = cand;
                        add(out, t, "trail", head + ": trail your stop",
                            "Move your stop to " + f(cand) + ", just " + (buy ? "under" : "over") + " the latest swing " + (buy ? "low" : "high") + " (" + f(level) + "). That locks in " + pl(t, rOf(t, cand)) + ".", c.epoch);
                    }
                }
            }
            t.lastEpoch = c.epoch;
        }

        if (t.status.equals("open")) {
            Candle last = raw[raw.length - 1];
            boolean forming = last.epoch + t.entryGran > now;
            // a wick through the stop/target in the candle that is still forming is real: an order would have filled
            if (forming && last.epoch >= t.startedAt) {
                boolean stopHit = buy ? last.low <= t.stop : last.high >= t.stop;
                boolean tpHit = t.tp != null && (buy ? last.high >= t.tp : last.low <= t.tp);
                if (stopHit) {
                    double r = rOf(t, t.stop);
                    close(t, t.stop, "stopped out", now);
                    add(out, t, "stopped", head + ": stopped out", "Price touched your stop " + f(t.stop) + ". Result " + pl(t, r) + ".", now);
                } else if (tpHit && t.stage >= 2) {
                    close(t, t.tp, "target reached", now);
                    add(out, t, "tp_final", head + ": final target reached", "Price reached " + f(t.exitPrice) + " (" + pl(t, t.resultR) + "). Close the rest.", now);
                } else if (tpHit) {
                    double hit = t.tp;
                    t.stage = 2;
                    boolean raise = buy ? t.entry > t.stop : t.entry < t.stop;
                    if (raise) t.stop = t.entry;
                    boolean more = t.tp2 != null && (buy ? t.tp2 > hit : t.tp2 < hit);
                    t.tp = more ? t.tp2 : null;
                    add(out, t, "tp1", head + ": target 1 reached", "Target 1 " + f(hit) + " reached. Take partial profit and keep your stop at " + f(t.stop)
                        + (more ? ". Move the remaining target to " + f(t.tp2) + "." : ". No higher target found: keep trailing."), now);
                }
            }
        }

        if (t.status.equals("open")) {
            double price = raw[raw.length - 1].close;
            t.lastPrice = price;
            t.lastR = rOf(t, price);
            int bars = 0;
            for (int i = startIdx; i < closed.length; i++) bars++;
            // stalled: lots of candles and still barely moved
            if (!t.stallWarned && bars >= 40 && t.maxR < 0.5) {
                t.stallWarned = true;
                add(out, t, "stall", head + ": trade is stalling",
                    "After " + bars + " candles price has not moved " + "even half the risk in your favour (best so far " + d1(t.maxR) + "R). Momentum is not following through: consider closing near break-even or at least do not add.", now);
            }
            // opposing liquidity between price and target
            if (t.tp != null && closed.length > 20) {
                Double liq = Smc.nextLiquidity(closed, buy, price, 3, 100);
                if (liq != null) {
                    boolean beforeTp = buy ? liq < t.tp : liq > t.tp;
                    double room = Math.abs(liq - price) / risk;
                    boolean fresh = t.liqWarned == null || Math.abs(t.liqWarned - liq) > 1e-9;
                    if (beforeTp && room >= 0.5 && fresh) {
                        t.liqWarned = liq;
                        add(out, t, "liquidity", head + ": liquidity before your target",
                            "There is a " + (buy ? "swing high" : "swing low") + " at " + f(liq) + " between price and your target " + f(t.tp)
                                + ". Price often reacts there. Consider taking some profit near " + f(liq) + " or tightening your stop.", now);
                    }
                }
            }
        }
        return out;
    }

    /** One line saying what to do right now. */
    public static String status(Trade t) {
        if (t.status.equals("closed")) return "Closed: " + t.closeReason + " (" + pl(t, t.resultR) + ")";
        String stage = t.stage == 0 ? "Stop at your original level until price reaches +1R."
            : t.stage == 1 ? "Stop is at break-even or better; it will trail behind swings."
            : "Target 1 done; trailing the rest.";
        return "Now " + pl(t, t.lastR) + ". " + stage;
    }
}
