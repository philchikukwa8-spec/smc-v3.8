package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Key support/resistance zones and major chart patterns, found on CLOSED candles only (no look-ahead):
 * double top/bottom, head and shoulders (and inverse), ascending/descending/symmetrical triangles and rising/falling wedges.
 * A pattern is "forming" until a candle closes beyond its breakout line, then "confirmed". Pure Java so it can be tested.
 * Patterns are shapes in price, not predictions: how often they work is something to measure, not assume.
 */
public final class Patterns {
    private Patterns() {}

    public static final class Zone implements Serializable {
        private static final long serialVersionUID = 1L;
        public double low, high;
        public int touches;
        public long firstEpoch, lastEpoch;
        /** "support" when the zone is below the last close, "resistance" when above (or inside: "level") */
        public String kind = "level";
        public String label() { return touches + "x " + kind; }
    }

    public static final class Pattern implements Serializable {
        private static final long serialVersionUID = 1L;
        /** e.g. "Double top" */
        public String name;
        /** BUY, SELL or NEUTRAL (the direction the pattern points to) */
        public String bias;
        /** "forming" or "confirmed" */
        public String status;
        public String tf = "";
        /** price that has to close beyond for the pattern to confirm (the neckline or the broken trendline now) */
        public double level;
        /** measured move target (a rule of thumb, not a promise) */
        public double target;
        /** where the guide puts the stop for this pattern (0 = use the standard rule) */
        public double stopHint;
        public long startEpoch, endEpoch;
        /** trendlines to draw: {epoch1, price1, epoch2, price2}; they are extended to the right edge by the chart */
        public List<double[]> lines = new ArrayList<>();
        public String detail = "";
        public boolean major() { return true; }
        public boolean isBuy() { return "BUY".equals(bias); }
    }

    // ---------------------------------------------------------------- zones

    static double atr(Candle[] c, int n) {
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

    /**
     * Price areas the market has turned from at least twice (swing highs and lows that sit close together).
     * At most `max` zones are returned, the strongest/nearest to the last close first.
     */
    public static List<Zone> keyZones(Candle[] c, int max) {
        List<Zone> out = new ArrayList<>();
        int n = c.length;
        if (n < 40) return out;
        double a = atr(c, 14);
        if (!(a > 0)) return out;
        int from = Math.max(0, n - 300);
        List<double[]> piv = new ArrayList<>(); // {price, epoch}
        for (Smc.Swing s : Smc.swingHighs(c, 3, from, n - 1)) piv.add(new double[]{s.level, c[s.idx].epoch});
        for (Smc.Swing s : Smc.swingLows(c, 3, from, n - 1)) piv.add(new double[]{s.level, c[s.idx].epoch});
        Collections.sort(piv, new Comparator<double[]>() { @Override public int compare(double[] x, double[] y) { return Double.compare(x[0], y[0]); } });
        double tol = 0.6 * a;
        int i = 0;
        while (i < piv.size()) {
            int j = i;
            double lo = piv.get(i)[0], hi = lo, sum = lo;
            long first = (long) piv.get(i)[1], last = first;
            while (j + 1 < piv.size() && piv.get(j + 1)[0] - lo <= tol) {
                j++;
                hi = piv.get(j)[0]; sum += hi;
                first = Math.min(first, (long) piv.get(j)[1]); last = Math.max(last, (long) piv.get(j)[1]);
            }
            int cnt = j - i + 1;
            if (cnt >= 2) {
                Zone z = new Zone();
                double pad = 0.15 * a;
                z.low = lo - pad; z.high = hi + pad; z.touches = cnt; z.firstEpoch = first; z.lastEpoch = last;
                out.add(z);
            }
            i = j + 1;
        }
        final double px = c[n - 1].close;
        for (Zone z : out) z.kind = px > z.high ? "support" : px < z.low ? "resistance" : "level";
        Collections.sort(out, new Comparator<Zone>() {
            @Override public int compare(Zone x, Zone y) {
                double sx = x.touches * 2.0 - dist(x, px) / 1e9, sy = y.touches * 2.0 - dist(y, px) / 1e9;
                return Double.compare(sy, sx);
            }
        });
        // keep the nearest ones to the price among the strongest
        List<Zone> top = new ArrayList<>(out.subList(0, Math.min(out.size(), Math.max(max * 2, max))));
        Collections.sort(top, new Comparator<Zone>() {
            @Override public int compare(Zone x, Zone y) { return Double.compare(dist(x, px), dist(y, px)); }
        });
        return new ArrayList<>(top.subList(0, Math.min(top.size(), max)));
    }

    private static double dist(Zone z, double px) { return px < z.low ? z.low - px : px > z.high ? px - z.high : 0; }

    /** true when the price range [a, b] overlaps (or is within `slack` of) the zone */
    public static boolean touchesZone(Zone z, double a, double b, double slack) {
        double lo = Math.min(a, b), hi = Math.max(a, b);
        return hi >= z.low - slack && lo <= z.high + slack;
    }

    // ------------------------------------------------------------- patterns

    /** All patterns present on the latest candles (each kind at most once, the most recent). */
    public static List<Pattern> detect(Candle[] c) {
        List<Pattern> out = new ArrayList<>();
        if (c.length < 60) return out;
        double a = atr(c, 14);
        if (!(a > 0)) return out;
        addIf(out, doubleTop(c, a));
        addIf(out, headShoulders(c, a));
        Candle[] m = Smc.mirror(c);
        addIf(out, unmirror(doubleTop(m, a), "Double bottom"));
        addIf(out, unmirror(headShoulders(m, a), "Inverse head and shoulders"));
        addIf(out, triangleOrWedge(c, a));
        addIf(out, roundedBottom(c, a));
        Pattern top = roundedBottom(m, a);
        if (top != null) {
            top.name = top.name.equals("Cup and handle") ? "Inverted cup and handle" : "Rounding top";
            top.bias = "SELL"; top.level = -top.level; top.target = -top.target; top.stopHint = -top.stopHint;
            for (double[] ln : top.lines) { ln[1] = -ln[1]; ln[3] = -ln[3]; }
            out.add(top);
        }
        return out;
    }

    /** y = c0 + c1 x + c2 x^2 by least squares on x in [0,1]; returns {c0, c1, c2, R squared} or null */
    private static double[] quad(double[] y) {
        int m = y.length;
        double s0 = m, s1 = 0, s2 = 0, s3 = 0, s4 = 0, t0 = 0, t1 = 0, t2 = 0;
        for (int i = 0; i < m; i++) {
            double x = (double) i / (m - 1);
            s1 += x; s2 += x * x; s3 += x * x * x; s4 += x * x * x * x;
            t0 += y[i]; t1 += x * y[i]; t2 += x * x * y[i];
        }
        double[][] A = {{s0, s1, s2, t0}, {s1, s2, s3, t1}, {s2, s3, s4, t2}};
        for (int i = 0; i < 3; i++) {
            int piv = i;
            for (int r = i + 1; r < 3; r++) if (Math.abs(A[r][i]) > Math.abs(A[piv][i])) piv = r;
            double[] tmp = A[i]; A[i] = A[piv]; A[piv] = tmp;
            if (Math.abs(A[i][i]) < 1e-12) return null;
            for (int r = 0; r < 3; r++) if (r != i) {
                double f = A[r][i] / A[i][i];
                for (int k = i; k < 4; k++) A[r][k] -= f * A[i][k];
            }
        }
        double c0 = A[0][3] / A[0][0], c1 = A[1][3] / A[1][1], c2 = A[2][3] / A[2][2];
        double mean = t0 / m, ssT = 0, ssR = 0;
        for (int i = 0; i < m; i++) {
            double x = (double) i / (m - 1), f = c0 + c1 * x + c2 * x * x;
            ssT += (y[i] - mean) * (y[i] - mean); ssR += (y[i] - f) * (y[i] - f);
        }
        return new double[]{c0, c1, c2, ssT > 0 ? 1 - ssR / ssT : 0};
    }

    /**
     * The guide's rounding bottom and cup and handle (bullish; the top versions are the mirror). A U-shaped base: left rim, a smooth low in the
     * middle, a right rim at about the same height. A short pullback below the right rim makes it a cup and handle. It confirms on a close
     * through the rim (the neckline); the target is the cup depth added above it; the stop sits under the 61.8% level of the cup (the guide).
     */
    private static Pattern roundedBottom(Candle[] c, double a) {
        int n = c.length;
        for (int len : new int[]{60, 90, 120}) {
            if (n < len + 4) continue;
            int s0 = n - len;
            int lo = s0;
            for (int k = s0; k < n; k++) if (c[k].low < c[lo].low) lo = k;
            if (lo - s0 < 0.25 * len || lo - s0 > 0.75 * len) continue;
            double leftRim = Double.NEGATIVE_INFINITY, rightRim = Double.NEGATIVE_INFINITY;
            int lrIdx = s0;
            for (int k = s0; k < s0 + len / 5; k++) if (c[k].high > leftRim) { leftRim = c[k].high; lrIdx = k; }
            int rrIdx = lo;
            int kBreak = n;
            for (int k = lo + 1; k < n; k++) if (c[k].close > leftRim + 0.5 * a) { kBreak = k; break; }
            for (int k = lo + 1; k < kBreak; k++) if (c[k].high > rightRim) { rightRim = c[k].high; rrIdx = k; }
            if (rrIdx == lo) continue;
            // the cup itself (left rim to right rim) has to be a smooth U: a parabola opening upward with its low in the middle part
            int m = rrIdx - s0 + 1;
            if (m < 20) continue;
            double[] cl = new double[m];
            for (int i = 0; i < m; i++) cl[i] = c[s0 + i].close;
            double[] q = quad(cl);
            if (q == null || q[2] <= 0 || q[3] < 0.8) continue;
            double vx = -q[1] / (2 * q[2]);
            if (vx < 0.25 || vx > 0.75) continue;
            double low = c[lo].low, depth = Math.min(leftRim, rightRim) - low;
            if (depth < 3 * a) continue;
            if (Math.abs(leftRim - rightRim) > 0.35 * depth) continue;     // rims at about the same height
            double neck = Math.max(leftRim, rightRim);
            // a handle: after the right rim the price drifts down a little (no more than half the cup) over 3 to 15 candles
            boolean handle = false;
            int hEnd = Math.min(n, kBreak) - 1;
            if (hEnd - rrIdx >= 3 && hEnd - rrIdx <= 25) {
                double hl = Double.POSITIVE_INFINITY;
                for (int k = rrIdx; k <= hEnd; k++) hl = Math.min(hl, c[k].low);
                if (rightRim - hl >= 0.12 * depth && rightRim - hl <= 0.5 * depth) handle = true;
            }
            boolean broken = false;
            for (int k = Math.max(rrIdx + 1, n - 8); k < n; k++) if (c[k].close > neck + 0.1 * a) broken = true;
            if (!broken && neck - c[n - 1].close > 1.5 * a) continue;     // still far from the neckline
            if (broken && c[n - 1].close < neck - 0.3 * a) continue;       // broke and failed
            Pattern p = new Pattern();
            p.name = handle ? "Cup and handle" : "Rounding bottom"; p.bias = "BUY"; p.status = broken ? "confirmed" : "forming";
            p.level = neck; p.target = neck + depth;
            p.stopHint = low + 0.618 * depth;
            p.startEpoch = c[lrIdx].epoch; p.endEpoch = c[n - 1].epoch;
            p.lines.add(new double[]{c[lrIdx].epoch, neck, c[n - 1].epoch, neck});
            // the arc, as short segments
            for (int i = 0; i < 8; i++) {
                int i1 = (int) Math.round((m - 1) * i / 8.0), i2 = (int) Math.round((m - 1) * (i + 1) / 8.0);
                double x1 = (double) i1 / (m - 1), x2 = (double) i2 / (m - 1);
                p.lines.add(new double[]{c[s0 + i1].epoch, q[0] + q[1] * x1 + q[2] * x1 * x1, c[s0 + i2].epoch, q[0] + q[1] * x2 + q[2] * x2 * x2});
            }
            p.detail = (handle ? "A cup with a handle" : "A rounded base") + ", depth " + Fmt.f(depth) + ", neckline " + Fmt.f(neck) + (broken ? " broken." : ": a close above it confirms.");
            return p;
        }
        return null;
    }

    private static void addIf(List<Pattern> l, Pattern p) { if (p != null) l.add(p); }

    private static Pattern unmirror(Pattern p, String name) {
        if (p == null) return null;
        p.name = name; p.bias = "BUY"; p.level = -p.level; p.target = -p.target;
        for (double[] ln : p.lines) { ln[1] = -ln[1]; ln[3] = -ln[3]; }
        return p;
    }

    private static Pattern doubleTop(Candle[] c, double a) {
        int n = c.length;
        List<Smc.Swing> hi = Smc.swingHighs(c, 3, Math.max(0, n - 200), n - 1);
        if (hi.size() < 2) return null;
        Smc.Swing h1 = hi.get(hi.size() - 2), h2 = hi.get(hi.size() - 1);
        if (n - 1 - h2.idx > 25 || h2.idx - h1.idx < 8 || h2.idx - h1.idx > 90) return null;
        if (Math.abs(h1.level - h2.level) > 0.6 * a) return null;
        double neck = Double.MAX_VALUE;
        for (int k = h1.idx; k <= h2.idx; k++) neck = Math.min(neck, c[k].low);
        double top = (h1.level + h2.level) / 2;
        if (top - neck < 2 * a) return null;
        double maxTop = Math.max(h1.level, h2.level);
        // the guide: a top forms after an extended move up, so nothing on the left may stand clearly higher
        double before = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, h1.idx - 40); k < h1.idx - 3; k++) before = Math.max(before, c[k].high);
        if (before > maxTop + 0.5 * a) return null;
        boolean broken = false;
        for (int k = h2.idx + 1; k < n; k++) {
            if (c[k].close > maxTop + 0.6 * a) return null; // failed: price made a new high
            if (!broken && c[k].close < neck) { broken = true; if (n - 1 - k > 8) return null; continue; }
            // the guide's safety net: a close back above the neckline after the break cancels the pattern
            if (broken && c[k].close > neck + 0.1 * a) return null;
        }
        Pattern p = new Pattern();
        p.name = "Double top"; p.bias = "SELL"; p.status = broken ? "confirmed" : "forming";
        p.level = neck; p.target = neck - (top - neck);
        p.startEpoch = c[h1.idx].epoch; p.endEpoch = c[h2.idx].epoch;
        p.lines.add(new double[]{c[h1.idx].epoch, h1.level, c[h2.idx].epoch, h2.level});
        p.lines.add(new double[]{c[h1.idx].epoch, neck, c[n - 1].epoch, neck});
        p.detail = "Two highs near " + Fmt.f(top) + ", neckline " + Fmt.f(neck) + (broken ? " broken." : ": a close below it confirms.");
        return p;
    }

    private static Pattern headShoulders(Candle[] c, double a) {
        int n = c.length;
        List<Smc.Swing> hi = Smc.swingHighs(c, 3, Math.max(0, n - 220), n - 1);
        if (hi.size() < 3) return null;
        Smc.Swing ls = hi.get(hi.size() - 3), hd = hi.get(hi.size() - 2), rs = hi.get(hi.size() - 1);
        if (n - 1 - rs.idx > 30) return null;
        int d1 = hd.idx - ls.idx, d2 = rs.idx - hd.idx;
        if (d1 < 5 || d2 < 5 || d1 > 80 || d2 > 80) return null;
        double ratio = (double) d1 / d2;
        if (ratio < 0.4 || ratio > 2.5) return null;
        if (hd.level < ls.level + 0.5 * a || hd.level < rs.level + 0.5 * a) return null;
        int t1, t2;
        t1 = argMinLow(c, ls.idx, hd.idx); t2 = argMinLow(c, hd.idx, rs.idx);
        double neckAtHead = lineAt(c[t1].epoch, c[t1].low, c[t2].epoch, c[t2].low, c[hd.idx].epoch);
        double height = hd.level - neckAtHead;
        if (height < 2 * a) return null;
        if (Math.abs(ls.level - rs.level) > 0.35 * height) return null;
        // the guide's rules: the neckline rises or is flat, never falls to the right; the shoulders share part of the same price band;
        // and the pattern comes after an extended move up (no higher swing on the left)
        if (c[t2].low < c[t1].low - 0.3 * a) return null;
        if (Math.min(ls.level, rs.level) <= Math.max(c[t1].low, c[t2].low)) return null;
        double before = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, ls.idx - 40); k < ls.idx - 3; k++) before = Math.max(before, c[k].high);
        if (before > hd.level) return null;
        boolean broken = false;
        double lastNeck = lineAt(c[t1].epoch, c[t1].low, c[t2].epoch, c[t2].low, c[n - 1].epoch);
        for (int k = rs.idx + 1; k < n; k++) {
            if (c[k].close > hd.level) return null;
            double nk = lineAt(c[t1].epoch, c[t1].low, c[t2].epoch, c[t2].low, c[k].epoch);
            if (!broken && c[k].close < nk) { broken = true; if (n - 1 - k > 8) return null; continue; }
            // safety net: back above the neckline after the break means the pattern failed
            if (broken && c[k].close > nk + 0.1 * a) return null;
        }
        Pattern p = new Pattern();
        p.name = "Head and shoulders"; p.bias = "SELL"; p.status = broken ? "confirmed" : "forming";
        p.level = lastNeck; p.target = lastNeck - height;
        p.startEpoch = c[ls.idx].epoch; p.endEpoch = c[rs.idx].epoch;
        p.lines.add(new double[]{c[ls.idx].epoch, ls.level, c[hd.idx].epoch, hd.level});
        p.lines.add(new double[]{c[hd.idx].epoch, hd.level, c[rs.idx].epoch, rs.level});
        p.lines.add(new double[]{c[t1].epoch, c[t1].low, c[n - 1].epoch, lastNeck});
        p.detail = "Head " + Fmt.f(hd.level) + " between two lower shoulders, neckline now " + Fmt.f(lastNeck) + (broken ? " broken." : ": a close below it confirms.");
        return p;
    }

    private static int argMinLow(Candle[] c, int from, int to) {
        int best = from;
        for (int k = from; k <= to; k++) if (c[k].low < c[best].low) best = k;
        return best;
    }

    private static double lineAt(long x1, double y1, long x2, double y2, long x) {
        if (x2 == x1) return y1;
        return y1 + (y2 - y1) * (double) (x - x1) / (double) (x2 - x1);
    }

    /** least squares line through (epoch, price) points: {slopePerSecond, intercept at epoch0} */
    private static double[] fit(List<Smc.Swing> s, Candle[] c, long epoch0) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int m = s.size();
        for (Smc.Swing p : s) {
            double x = c[p.idx].epoch - epoch0, y = p.level;
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double den = m * sxx - sx * sx;
        double slope = den == 0 ? 0 : (m * sxy - sx * sy) / den;
        double icpt = (sy - slope * sx) / m;
        return new double[]{slope, icpt};
    }

    private static Pattern triangleOrWedge(Candle[] c, double a) {
        int n = c.length;
        int from = Math.max(0, n - 80);
        List<Smc.Swing> hi = Smc.swingHighs(c, 3, from, n - 1), lo = Smc.swingLows(c, 3, from, n - 1);
        if (hi.size() < 3 || lo.size() < 3) return null;
        List<Smc.Swing> h = new ArrayList<>(hi.subList(hi.size() - 3, hi.size())), l = new ArrayList<>(lo.subList(lo.size() - 3, lo.size()));
        int firstIdx = Math.min(h.get(0).idx, l.get(0).idx), lastIdx = Math.max(h.get(2).idx, l.get(2).idx);
        if (lastIdx - firstIdx < 20 || n - 1 - lastIdx > 15) return null;
        long e0 = c[firstIdx].epoch;
        double[] fh = fit(h, c, e0), fl = fit(l, c, e0);
        double span = c[lastIdx].epoch - e0;
        // the guide: each side needs three real touches, so every swing must lie on its fitted line
        for (Smc.Swing q : h) if (Math.abs(q.level - (fh[1] + fh[0] * (c[q.idx].epoch - e0))) > 0.7 * a) return null;
        for (Smc.Swing q : l) if (Math.abs(q.level - (fl[1] + fl[0] * (c[q.idx].epoch - e0))) > 0.7 * a) return null;
        double chH = fh[0] * span, chL = fl[0] * span;
        double gap0 = fh[1] - fl[1], gap1 = (fh[1] + fh[0] * span) - (fl[1] + fl[0] * span);
        if (!(gap0 > 0) || !(gap1 > 0.15 * a) || gap1 > 0.75 * gap0) return null; // must be converging
        double flat = 0.7 * a;
        String name; String bias;
        boolean hFlat = Math.abs(chH) < flat, lFlat = Math.abs(chL) < flat;
        if (hFlat && chL > flat) { name = "Ascending triangle"; bias = "BUY"; }
        else if (lFlat && chH < -flat) { name = "Descending triangle"; bias = "SELL"; }
        else if (chH < -flat && chL > flat) { name = "Symmetrical triangle"; bias = "NEUTRAL"; }
        else if (chH > 1.5 * a && chL > 1.5 * a && gap1 <= 0.55 * gap0) { name = "Rising wedge"; bias = "SELL"; }
        else if (chH < -1.5 * a && chL < -1.5 * a && gap1 <= 0.55 * gap0) { name = "Falling wedge"; bias = "BUY"; }
        else return null;
        long eN = c[n - 1].epoch;
        double upNow = fh[1] + fh[0] * (eN - e0), loNow = fl[1] + fl[0] * (eN - e0);
        String status = "forming";
        int brokenAt = -1;
        char dir = 0;
        for (int k = lastIdx + 1; k < n; k++) {
            double up = fh[1] + fh[0] * (c[k].epoch - e0), dn = fl[1] + fl[0] * (c[k].epoch - e0);
            if (c[k].close > up) { brokenAt = k; dir = 'u'; break; }
            if (c[k].close < dn) { brokenAt = k; dir = 'd'; break; }
        }
        if (brokenAt >= 0) {
            if (n - 1 - brokenAt > 6) return null;
            status = "confirmed";
            bias = dir == 'u' ? "BUY" : "SELL";
        }
        Pattern p = new Pattern();
        p.name = name; p.bias = bias; p.status = status;
        double height = gap0;
        if (status.equals("confirmed")) { p.level = dir == 'u' ? upNow : loNow; p.target = dir == 'u' ? p.level + height : p.level - height; }
        else if (bias.equals("BUY")) { p.level = upNow; p.target = upNow + height; }
        else if (bias.equals("SELL")) { p.level = loNow; p.target = loNow - height; }
        else { p.level = (upNow + loNow) / 2; p.target = p.level; }
        p.startEpoch = e0; p.endEpoch = c[lastIdx].epoch;
        p.lines.add(new double[]{e0, fh[1], eN, upNow});
        p.lines.add(new double[]{e0, fl[1], eN, loNow});
        p.detail = name + " between " + Fmt.f(loNow) + " and " + Fmt.f(upNow) + (status.equals("confirmed") ? ": broke " + (dir == 'u' ? "up." : "down.") : ": a close outside the lines confirms the break.");
        return p;
    }

    /** one line for alerts */
    public static String describe(Pattern p) {
        return String.format(Locale.US, "%s (%s, %s) - %s Rule-of-thumb target %s.", p.name, p.tf, p.status, p.detail, Fmt.f(p.target));
    }
}
