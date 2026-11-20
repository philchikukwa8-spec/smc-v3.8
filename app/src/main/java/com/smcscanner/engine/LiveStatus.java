package com.smcscanner.engine;

import java.util.Locale;

/**
 * A plain-words reading of where price is relative to a setup RIGHT NOW. The UI calls this every few seconds with the
 * latest price, so a setup's description keeps changing with the market instead of staying as it was when first detected.
 */
public final class LiveStatus {
    private LiveStatus() {}

    public static final class Live {
        public String text = "";
        /** +1 good for the idea, -1 bad, 0 neutral */
        public int tone;
        /** a short machine label: far, near, at, inTrade, stop, target, watch */
        public String phase = "";
    }

    /**
     * @param touched true when the entry level has already been reached (state ENTRY-REACHED, or the trade was entered)
     * @param ready   true when the setup has every condition (QUALIFIED or ENTRY-REACHED); false while it is only being watched
     */
    public static Live describe(boolean buy, boolean ready, boolean touched, double entry, double stop, double target, double price) {
        Live l = new Live();
        if (!(price > 0) || !(entry > 0) || !(stop > 0)) { l.text = "Waiting for a live price."; l.phase = "watch"; return l; }
        double risk = Math.abs(entry - stop);
        if (!(risk > 0)) { l.text = "Live price " + Fmt.f(price) + "."; return l; }
        boolean beyondStop = buy ? price <= stop : price >= stop;
        boolean beyondTarget = target > 0 && (buy ? price >= target : price <= target);
        double away = buy ? price - entry : entry - price;          // > 0: price has not pulled back to the entry yet
        double awayR = away / risk;
        double progressR = (buy ? price - entry : entry - price) / risk; // R made if filled at the entry
        double pct = Math.abs(price - entry) / price * 100;
        String p = Fmt.f(price), e = Fmt.f(entry), s = Fmt.f(stop);
        if (beyondStop) {
            l.phase = "stop"; l.tone = -1;
            l.text = "Invalidated: price " + p + " is through the stop " + s + ". The idea has failed" + (touched ? "; a trade from it would be stopped out." : "; do not enter.");
            return l;
        }
        if (beyondTarget) {
            l.phase = "target"; l.tone = touched ? 1 : 0;
            l.text = touched ? "Target " + Fmt.f(target) + " reached: price is " + p + ". If you entered, take profit." : "Price " + p + " has already reached the target " + Fmt.f(target) + " without a fill. The move happened; do not chase it.";
            return l;
        }
        if (!ready) {
            l.phase = "watch";
            l.text = String.format(Locale.US, "Watching, no order yet. Price %s is %.1f%% (%.1fR) %s the planned entry %s.", p, pct, Math.abs(awayR), away > 0 ? "above" : "below", e);
            if (!buy) l.text = l.text.replace(away > 0 ? "above" : "below", away > 0 ? "below" : "above");
            return l;
        }
        if (touched || away <= 0) {
            l.phase = "inTrade";
            double toStopR = (buy ? price - stop : stop - price) / risk;
            double toTgtR = target > 0 ? Math.abs(target - price) / risk : 0;
            l.tone = progressR >= 0 ? 1 : (toStopR < 0.35 ? -1 : 0);
            l.text = String.format(Locale.US, "At or past the entry %s: price %s is %+.1fR from the entry%s. %.1fR to the stop%s.",
                e, p, progressR, progressR >= 0 ? " in your favour" : "", toStopR, target > 0 ? String.format(Locale.US, ", %.1fR to the target", toTgtR) : "");
            if (toStopR < 0.35) l.text += " Close to the stop.";
            return l;
        }
        if (awayR <= 0.3) { l.phase = "near"; l.tone = 1; l.text = String.format(Locale.US, "Almost there: price %s is only %.2fR (%.2f%%) from the entry %s. Have the limit order in place.", p, awayR, pct, e); return l; }
        l.phase = "far";
        l.text = String.format(Locale.US, "Waiting: price %s is %.1fR (%.2f%%) %s the entry %s. It needs to %s first.", p, awayR, pct, buy ? "above" : "below", e, buy ? "pull back" : "bounce up");
        return l;
    }
}
