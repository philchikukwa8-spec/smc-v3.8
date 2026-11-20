package com.smcscanner.engine.mie;

import com.smcscanner.engine.Fmt;

import java.util.Locale;

/**
 * Turns a plan (entry, stop, target) and the CURRENT price into a plain sentence about where the idea stands right now.
 * Used on every setup card and refreshed every few seconds, so the description follows the market instead of the moment it was found.
 * It only describes; it never changes a setup's recorded state.
 */
public final class LiveTrack {
    private LiveTrack() {}

    public static final class Status {
        /** WAITING, AT_ENTRY, IN_TRADE, TARGET_HIT, STOP_HIT, MISSED, WATCHING */
        public String code = "WAITING";
        public String headline = "", detail = "";
        /** open result in R when in a trade, else 0 */
        public double r;
        /** +1 good, 0 neutral, -1 bad: for colouring */
        public int tone;
    }

    private static String ago(long secs) {
        if (secs < 90) return "just now";
        if (secs < 3600) return (secs / 60) + " min ago";
        if (secs < 86400) return String.format(Locale.US, "%.1f h ago", secs / 3600.0);
        return String.format(Locale.US, "%.1f days ago", secs / 86400.0);
    }

    /**
     * @param entered true when the engine already saw the entry level reached (state ENTRY-REACHED)
     * @param hasPlan false for a watch-stage idea that has no entry/stop/target yet; boundary is then the level price is waiting for
     * @param atr volatility of the chart the idea is on (for "how far" in plain terms)
     */
    public static Status describe(boolean buy, double entry, double stop, double target, double price, double atr, boolean entered,
                                  boolean hasPlan, double boundary, long identifiedAt, long now) {
        Status s = new Status();
        String since = identifiedAt > 0 ? " Identified " + ago(now - identifiedAt) + "." : "";
        if (!(price > 0)) { s.headline = "No live price yet."; s.detail = since.trim(); return s; }
        double a = atr > 0 ? atr : Math.abs(price) * 0.001;
        if (!hasPlan) {
            s.code = "WATCHING";
            if (boundary > 0) {
                double d = buy ? boundary - price : price - boundary;     // positive = price still has to travel to the boundary
                s.headline = "Watching. Price " + Fmt.f(price) + (d > 0 ? String.format(Locale.US, " is %.1f ATR from the level %s.", d / a, Fmt.f(boundary)) : " has gone through the level " + Fmt.f(boundary) + " (waiting for a candle close and a retest).");
            } else s.headline = "Watching. Price " + Fmt.f(price) + ".";
            s.detail = since.trim();
            return s;
        }
        double risk = Math.abs(entry - stop);
        boolean stopHit = buy ? price <= stop : price >= stop;
        boolean targetHit = target > 0 && (buy ? price >= target : price <= target);
        double dEntry = buy ? price - entry : entry - price;      // positive = price still has to move toward the entry to fill (limit order)
        if (entered) {
            double r = risk > 0 ? (buy ? price - entry : entry - price) / risk : 0;
            s.r = r;
            if (stopHit) { s.code = "STOP_HIT"; s.tone = -1; s.headline = "Stop level " + Fmt.f(stop) + " has been reached. The idea has failed (about -1R if you were filled)."; }
            else if (targetHit) { s.code = "TARGET_HIT"; s.tone = 1; s.headline = "Target " + Fmt.f(target) + " has been reached (about " + String.format(Locale.US, "%+.1fR", risk > 0 ? Math.abs(target - entry) / risk : 0) + " if you were filled)."; }
            else {
                s.code = "IN_TRADE"; s.tone = r > 0.05 ? 1 : r < -0.05 ? -1 : 0;
                double toT = target > 0 ? Math.abs(target - price) / a : 0, toS = Math.abs(price - stop) / a;
                s.headline = String.format(Locale.US, "Entry level reached. If you are in: %+.2fR at price %s.", r, Fmt.f(price));
                s.detail = String.format(Locale.US, "Stop is %.1f ATR away%s.", toS, target > 0 ? String.format(Locale.US, ", target %.1f ATR away", toT) : "") + since;
            }
            return s;
        }
        if (stopHit) {
            s.code = "STOP_HIT"; s.tone = -1;
            s.headline = "Invalidated: price " + Fmt.f(price) + " is through the stop " + Fmt.f(stop) + ". Do not place this order.";
            s.detail = since.trim();
            return s;
        }
        if (targetHit) {
            s.code = "MISSED"; s.tone = -1;
            s.headline = "Missed: price ran to the target " + Fmt.f(target) + " without filling the entry " + Fmt.f(entry) + ". The idea has expired.";
            s.detail = since.trim();
            return s;
        }
        if (dEntry <= 0) {
            s.code = "AT_ENTRY"; s.tone = 1;
            s.headline = "Price " + Fmt.f(price) + " has reached the entry level " + Fmt.f(entry) + ". A limit order there would be filled now.";
            s.detail = String.format(Locale.US, "Stop %s is %.1f ATR away.", Fmt.f(stop), Math.abs(price - stop) / a) + since;
            return s;
        }
        s.code = "WAITING";
        double pct = Math.abs(price) > 0 ? 100 * dEntry / Math.abs(price) : 0;
        s.headline = String.format(Locale.US, "Waiting. Price %s is %.1f ATR (%.2f%%) %s the entry %s.", Fmt.f(price), dEntry / a, pct, buy ? "above" : "below", Fmt.f(entry));
        s.detail = String.format(Locale.US, "Needs a %s of %s to fill. Stop %s, target %s.", buy ? "dip" : "rise", Fmt.f(dEntry), Fmt.f(stop), target > 0 ? Fmt.f(target) : "none") + since;
        return s;
    }
}
