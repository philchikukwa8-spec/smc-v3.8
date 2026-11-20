package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Top-down trading advisor on the 1H, 30m and 15m charts. Pure Java, closed candles only (no look-ahead).
 *
 * 1H  decides the direction (market structure, and where price sits in the 1H range: discount or premium).
 * 30m + 1H supply the point of interest (an unfilled fair value gap or order block in that direction).
 * 15m decides the moment: price has come back into the zone, then a 15m candle closes through the last
 * counter-move high (low for sells), which is the change of character that says the pullback is over.
 *
 * Every market always gets a plain-language state, so the screen is never empty:
 *   TRIGGERED   - the 15m confirmation just happened: a full plan (entry, stop, target) is given
 *   READY       - price is inside the zone, waiting for the 15m close that confirms
 *   APPROACHING - price is heading for the zone
 *   WAIT        - nothing worth doing now
 * A grade (A/B/C) counts the confluences met. It is not a win probability.
 */
public final class Advisor {
    private Advisor() {}

    public static final String TRIGGERED = "TRIGGERED", READY = "READY", APPROACHING = "APPROACHING", WAIT = "WAIT";

    public static final class Plan implements Serializable {
        private static final long serialVersionUID = 1L;
        public String state = WAIT;
        /** BUY, SELL or NONE */
        public String side = "NONE";
        public String bias1h = "range", bias30 = "range";
        /** true when the 1H structure is ranging and the lean comes only from the position in the range */
        public boolean weak;
        public double price;
        public double zoneLow, zoneHigh;
        public String zoneTf = "", zoneType = "";
        /** the 15m level that has to close beyond (or just did) */
        public double trigger;
        public long triggerEpoch;
        public double entry, stop, target, rr;
        public int confluence, maxConfluence = 6;
        public String grade = "C";
        public String headline = "", text = "";
        public final List<String> checks = new ArrayList<>();
        public Setup setup;
        /** unique per market-state so the same thing is not announced twice */
        public String id = "";
        public boolean isBuy() { return "BUY".equals(side); }
        /** the reward:risk band, shown to you; it never cancels a plan */
        public String band() { return bandOf(rr); }
    }

    /** blueprint bands: under 2R weak, 2R to 3R watch, 3R and over qualified, 4R strong, 5R and over exceptional (labels only) */
    public static String bandOf(double rr) {
        if (rr <= 0) return "";
        if (rr < 2) return "UNDER 2R";
        if (rr < 3) return "2R-3R (WATCH)";
        if (rr < 4) return "QUALIFIED 3R+";
        if (rr < 5) return "STRONG 4R+";
        return "EXCEPTIONAL 5R+";
    }

    public static final double MIN_RR = 1.0;
    /** tuning (measured in AdvisorTest): where in the confirming move the limit entry sits (0 = at the low, 1 = at the broken level), and an optional cap on the target in R (0 = none) */
    public static double entryFrac = 0.5, capR = 0;

    // ------------------------------------------------------------ helpers
    private static double atr(Candle[] c, int n) {
        int len = c.length;
        if (len < 2) return 0;
        int from = Math.max(1, len - n);
        double sum = 0;
        for (int i = from; i < len; i++) {
            double tr = Math.max(c[i].high - c[i].low, Math.max(Math.abs(c[i].high - c[i - 1].close), Math.abs(c[i].low - c[i - 1].close)));
            sum += tr;
        }
        return sum / (len - from);
    }

    private static final class Poi {
        double low, high;
        String tf, type;
        boolean stacked;
        long epoch;
    }

    /** unfilled bullish fair value gaps and order blocks in the last `look` candles (buy side; sells are found by mirroring) */
    private static List<Poi> pois(Candle[] c, String tf, int look) {
        List<Poi> out = new ArrayList<>();
        int n = c.length;
        double a = atr(c, 20);
        for (int i = Math.max(2, n - look); i < n; i++) {
            // fair value gap: the third candle's low is above the first candle's high
            if (c[i].low > c[i - 2].high && c[i - 1].close > c[i - 1].open) {
                Poi p = new Poi();
                p.low = c[i - 2].high; p.high = c[i].low; p.tf = tf; p.type = "FVG"; p.epoch = c[i - 1].epoch;
                if (p.high - p.low < 0.1 * a) continue;
                // the order block: the last down candle before the move
                for (int j = i - 2; j >= Math.max(0, i - 6); j--) {
                    if (c[j].close < c[j].open) { if (c[j].low <= p.high && c[j].high >= p.low - a) p.stacked = true; break; }
                }
                // still unfilled: nothing has closed below the gap since
                boolean dead = false;
                for (int k = i + 1; k < n; k++) if (c[k].close < p.low) { dead = true; break; }
                if (!dead) out.add(p);
            }
            // order block: a down candle followed by a strong close above its high
            if (c[i].close > c[i].open && i >= 1 && c[i - 1].close < c[i - 1].open && c[i].close > c[i - 1].high && (c[i].close - c[i].open) >= 1.2 * a * 0.6) {
                Poi p = new Poi();
                p.low = c[i - 1].low; p.high = Math.max(c[i - 1].open, c[i - 1].close); p.tf = tf; p.type = "OB"; p.epoch = c[i - 1].epoch;
                if (p.high - p.low < 0.1 * a) continue;
                boolean dead = false;
                for (int k = i + 1; k < n; k++) if (c[k].close < p.low) { dead = true; break; }
                if (!dead) out.add(p);
            }
        }
        return out;
    }

    private static Double lastSwingHighBefore(Candle[] c, int L, int beforeIdx) {
        Double best = null;
        for (int j = L; j < beforeIdx && j + L < c.length; j++) {
            boolean ok = true;
            for (int k = 1; k <= L && ok; k++) if (!(c[j].high > c[j - k].high) || !(c[j].high >= c[j + k].high)) ok = false;
            if (ok) best = c[j].high;
        }
        return best;
    }

    // ------------------------------------------------------------ buy-side analysis on (possibly mirrored) candles
    private static final class Raw {
        String state = WAIT;
        double zl, zh, trigger, entry, stop, target, rr, price;
        long trigEpoch;
        String tf = "", type = "";
        boolean weak, discount, stacked, swept, m30ok;
        String note = "";
    }

    private static Raw analyseBuy(Candle[] h4, Candle[] h1, Candle[] m30, Candle[] m15, boolean weak) {
        Raw r = new Raw();
        int n15 = m15.length;
        r.price = m15[n15 - 1].close;
        r.weak = weak;
        // 1H dealing range
        List<Smc.Swing> hi = Smc.swingHighs(h1, 2, Math.max(0, h1.length - 120), h1.length - 1);
        List<Smc.Swing> lo = Smc.swingLows(h1, 2, Math.max(0, h1.length - 120), h1.length - 1);
        double rangeHigh = hi.isEmpty() ? Double.NaN : hi.get(hi.size() - 1).level;
        double rangeLow = lo.isEmpty() ? Double.NaN : lo.get(lo.size() - 1).level;
        double eq = (rangeHigh + rangeLow) / 2;
        boolean haveRange = !Double.isNaN(eq) && rangeHigh > rangeLow;

        List<Poi> cands = new ArrayList<>();
        cands.addAll(pois(m30, "30m", 80));
        cands.addAll(pois(h1, "1H", 60));
        if (h4 != null && h4.length >= 40) cands.addAll(pois(h4, "4H", 40));
        double a15 = atr(m15, 14), a1h = atr(h1, 14);
        Poi best = null;
        double bestScore = Double.MAX_VALUE;
        for (Poi p : cands) {
            // must be below price or contain it (a buy zone), and not further away than a few hours of movement
            if (p.low > r.price + 0.2 * a15) continue;
            double dist = r.price >= p.low && r.price <= p.high ? 0 : r.price - p.high;
            if (dist > (p.tf.equals("4H") ? 6 : 4) * a1h) continue;
            boolean disc = haveRange && (p.low + p.high) / 2 <= eq;
            double score = dist + (disc ? 0 : 2 * a1h) + (p.tf.equals("4H") ? -0.6 * a1h : p.tf.equals("1H") ? -0.3 * a1h : 0) + (p.stacked ? -0.2 * a1h : 0);
            if (score < bestScore) { bestScore = score; best = p; }
        }
        if (best == null) { r.note = "No unfilled buy zone near price."; return r; }
        r.zl = best.low; r.zh = best.high; r.tf = best.tf; r.type = best.type; r.stacked = best.stacked;
        r.discount = haveRange && (best.low + best.high) / 2 <= eq;
        double zoneMid = (r.zl + r.zh) / 2;

        // first 15m candle in the last 16 that traded into the zone (and the zone has not been closed through)
        int touch = -1;
        for (int i = Math.max(1, n15 - 16); i < n15; i++) {
            if (m15[i].close < r.zl - 0.1 * a15) { touch = -1; continue; } // closed through it: start looking again
            if (m15[i].low <= r.zh && touch < 0) touch = i;
        }
        if (m15[n15 - 1].close < r.zl - 0.1 * a15) { r.note = "Price closed below the zone: it failed."; return r; }
        if (touch < 0) {
            double dist = r.price - r.zh;
            r.state = dist <= 1.5 * a1h ? APPROACHING : WAIT;
            r.note = "Price is " + Fmt.f(dist) + " above the zone.";
            return r;
        }
        // lowest low since the touch, and the counter-move high just before it
        int lowIdx = touch;
        for (int i = touch; i < n15; i++) if (m15[i].low <= m15[lowIdx].low) lowIdx = i;
        Double sh = lastSwingHighBefore(m15, 2, lowIdx);
        double trig;
        if (sh != null) trig = sh;
        else { trig = m15[Math.max(0, lowIdx - 4)].high; for (int j = Math.max(0, lowIdx - 4); j < lowIdx; j++) trig = Math.max(trig, m15[j].high); }
        if (lowIdx - 1 >= 0) { for (int j = Math.max(0, lowIdx - 3); j < lowIdx; j++) if (m15[j].high > trig && sh == null) trig = m15[j].high; }
        if (trig <= m15[lowIdx].low) { r.note = "No clear 15m high to break yet."; r.state = READY; r.trigger = m15[lowIdx].high; return r; }
        r.trigger = trig;
        // did a 15m candle close above it after the low?
        int ti = -1;
        for (int i = lowIdx + 1; i < n15; i++) if (m15[i].close > trig) { ti = i; break; }
        // a sweep: the low went below the previous 15m swing low
        double prevLow = Double.NaN;
        for (int j = Math.max(2, lowIdx - 20); j < lowIdx - 2; j++) {
            boolean ok = true;
            for (int k = 1; k <= 2 && ok; k++) if (!(m15[j].low < m15[j - k].low) || !(m15[j].low <= m15[j + k].low)) ok = false;
            if (ok) prevLow = m15[j].low;
        }
        r.swept = !Double.isNaN(prevLow) && m15[lowIdx].low < prevLow;
        if (ti < 0) { r.state = READY; return r; }
        if (n15 - 1 - ti > 6) { r.state = WAIT; r.note = "The 15m confirmation was more than 6 candles ago; the move has probably started without you."; return r; }
        // the plan
        double lowV = m15[lowIdx].low;
        double entry = lowV + (trig - lowV) * entryFrac;
        double stop = lowV - 0.25 * a15;
        Double tp = Smc.nextLiquidity(h1, true, entry, 2, 150);
        if ((tp == null || (tp - entry) / (entry - stop) < MIN_RR) && haveRange && rangeHigh > entry) tp = Math.max(tp == null ? 0 : tp, rangeHigh);
        if (tp == null || !(tp > entry)) { r.state = READY; r.note = "No target liquidity above price."; return r; }
        double risk = entry - stop;
        if (risk <= 0) { r.state = READY; return r; }
        if (capR > 0 && (tp - entry) > capR * risk) tp = entry + capR * risk;
        double rr = (tp - entry) / risk;
        if (rr < MIN_RR) { r.state = READY; r.note = "Reward is too small (" + String.format(Locale.US, "%.1f", rr) + "R) for the stop needed."; return r; }
        // price must not already be past the entry by much, or beyond half the way to target
        if (r.price > entry + 0.5 * (tp - entry)) { r.state = WAIT; r.note = "Price has already run too far from the entry."; return r; }
        r.state = TRIGGERED; r.entry = entry; r.stop = stop; r.target = tp; r.rr = rr; r.trigEpoch = m15[ti].epoch;
        return r;
    }

    // ------------------------------------------------------------ public entry point
    /** All three arrays must be CLOSED candles, oldest first. Returns a plan for this market right now. */
    public static Plan analyse(Candle[] h1, Candle[] m30, Candle[] m15) { return analyse(null, h1, m30, m15); }

    /** h4 may be null; when given, unfilled 4H order blocks and gaps also count as zones */
    public static Plan analyse(Candle[] h4, Candle[] h1, Candle[] m30, Candle[] m15) {
        Plan p = new Plan();
        if (h1 == null || m30 == null || m15 == null || h1.length < 40 || m30.length < 40 || m15.length < 40) {
            p.headline = "Not enough history yet";
            p.text = "Waiting for enough 1H, 30m and 15m candles.";
            return p;
        }
        p.price = m15[m15.length - 1].close;
        p.bias1h = Smc.structureBias(h1, 2, 120);
        p.bias30 = Smc.structureBias(m30, 2, 120);
        boolean buy;
        boolean weak = false;
        if (p.bias1h.equals("bull")) buy = true;
        else if (p.bias1h.equals("bear")) buy = false;
        else {
            List<Smc.Swing> hi = Smc.swingHighs(h1, 2, Math.max(0, h1.length - 120), h1.length - 1);
            List<Smc.Swing> lo = Smc.swingLows(h1, 2, Math.max(0, h1.length - 120), h1.length - 1);
            if (hi.isEmpty() || lo.isEmpty()) { p.headline = "No clear 1H structure"; p.text = "The 1H chart has no clear swings to work with."; return p; }
            double eq = (hi.get(hi.size() - 1).level + lo.get(lo.size() - 1).level) / 2;
            buy = p.price < eq;
            weak = true;
        }
        Raw r;
        if (buy) r = analyseBuy(h4, h1, m30, m15, weak);
        else {
            Raw m = analyseBuy(h4 == null ? null : Smc.mirror(h4), Smc.mirror(h1), Smc.mirror(m30), Smc.mirror(m15), weak);
            r = m;
            // mirror the numbers back
            double zl = -m.zh, zh = -m.zl;
            r.zl = zl; r.zh = zh; r.trigger = -m.trigger; r.entry = -m.entry; r.stop = -m.stop; r.target = -m.target; r.price = -m.price;
        }
        p.side = buy ? "BUY" : "SELL";
        p.weak = weak;
        p.state = r.state;
        p.zoneLow = r.zl; p.zoneHigh = r.zh; p.zoneTf = r.tf; p.zoneType = r.type;
        p.trigger = r.trigger; p.triggerEpoch = r.trigEpoch;
        p.entry = r.entry; p.stop = r.stop; p.target = r.target; p.rr = r.rr;
        p.price = m15[m15.length - 1].close;

        // confluences
        boolean bias30Agrees = p.bias30.equals(buy ? "bull" : "bear");
        int score = 0;
        p.checks.add((weak ? "[--] " : "[OK] ") + "1H " + (weak ? "ranging; leaning " + (buy ? "buy from the discount half" : "sell from the premium half") : (buy ? "bullish" : "bearish") + " structure"));
        if (!weak) score++;
        p.checks.add((bias30Agrees ? "[OK] " : "[--] ") + "30m structure " + (p.bias30.equals("range") ? "ranging" : p.bias30.equals("bull") ? "bullish" : "bearish") + (bias30Agrees ? " (agrees)" : " (pullback in progress)"));
        if (bias30Agrees) score++;
        if (!r.tf.isEmpty()) {
            p.checks.add((r.discount ? "[OK] " : "[--] ") + "Zone in " + (buy ? "discount" : "premium") + " of the 1H range");
            if (r.discount) score++;
            p.checks.add((r.stacked || r.type.equals("OB") ? "[OK] " : "[--] ") + (r.stacked ? "Fair value gap stacked on an order block" : "Zone type: " + r.type));
            if (r.stacked) score++;
            if (!r.state.equals(WAIT) && !r.state.equals(APPROACHING)) {
                p.checks.add((r.swept ? "[OK] " : "[--] ") + "15m liquidity sweep inside the zone");
                if (r.swept) score++;
            }
        }
        if (r.state.equals(TRIGGERED)) {
            p.checks.add("[OK] 15m closed " + (buy ? "above " : "below ") + Fmt.f(r.trigger) + " (change of character)");
            p.checks.add((r.rr >= 2.5 ? "[OK] " : "[--] ") + "Reward:risk " + String.format(Locale.US, "%.1f", r.rr));
            if (r.rr >= 2.5) score++;
        }
        p.confluence = score;
        p.grade = score >= 5 ? "A" : score >= 3 ? "B" : "C";

        String dir = buy ? "BUY" : "SELL";
        String zone = r.tf.isEmpty() ? "" : r.tf + " " + r.type + " " + Fmt.f(p.zoneLow) + " - " + Fmt.f(p.zoneHigh);
        switch (p.state) {
            case TRIGGERED:
                p.headline = dir + " plan ready, " + String.format(Locale.US, "%.1f", p.rr) + "R (grade " + p.grade + ")";
                p.text = "1H says " + (weak ? "lean " : "") + (buy ? "up" : "down") + ". Price came back to the " + zone + " and the 15m "
                    + (buy ? "closed above " : "closed below ") + Fmt.f(p.trigger) + ".\nEntry " + Fmt.f(p.entry) + " (limit)  Stop " + Fmt.f(p.stop)
                    + "  Target " + Fmt.f(p.target) + "  (" + String.format(Locale.US, "%.1f", p.rr) + "R, " + p.band() + ")";
                p.id = "ADV|" + p.side + "|" + p.triggerEpoch;
                Setup s = new Setup();
                s.id = p.id; s.side = p.side; s.triggerEpoch = p.triggerEpoch; s.structure = "CHoCH";
                s.zoneType = p.zoneType; s.zoneLow = p.zoneLow; s.zoneHigh = p.zoneHigh;
                s.entry = p.entry; s.sl = p.stop; s.tp = p.target; s.tpType = "liquidity"; s.rr = p.rr;
                s.score = p.confluence; s.maxScore = p.maxConfluence;
                s.discount = r.discount; s.overlap = r.stacked; s.majorSweep = r.swept;
                p.setup = s;
                break;
            case READY:
                p.headline = dir + " zone reached - wait for the 15m";
                p.text = "1H says " + (weak ? "lean " : "") + (buy ? "up" : "down") + ". Price is in the " + zone + ". Do nothing until a 15m candle closes "
                    + (buy ? "above " : "below ") + Fmt.f(p.trigger) + "." + (r.note.isEmpty() ? "" : " " + r.note);
                p.id = "ADV|" + p.side + "|READY|" + p.zoneTf + "|" + Math.round(p.zoneLow * 100) + "|" + Math.round(p.zoneHigh * 100);
                break;
            case APPROACHING:
                p.headline = dir + " zone ahead";
                p.text = "1H says " + (weak ? "lean " : "") + (buy ? "up" : "down") + ". Price is moving back toward the " + zone + ". " + r.note + " No entry yet.";
                p.id = "ADV|" + p.side + "|NEAR|" + p.zoneTf + "|" + Math.round(p.zoneLow * 100) + "|" + Math.round(p.zoneHigh * 100);
                break;
            default:
                p.headline = "No trade now";
                p.text = "1H " + (p.bias1h.equals("range") ? "is ranging" : p.bias1h.equals("bull") ? "is bullish" : "is bearish") + "." + (r.note.isEmpty() ? "" : " " + r.note)
                    + (zone.isEmpty() ? "" : " Watching the " + zone + ".");
                p.id = "ADV|WAIT";
        }
        return p;
    }
}
