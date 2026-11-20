package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Discovers recurring price behaviours without naming them in advance. Windows of closed candles become ATR-normalised
 * feature vectors, k-means groups them on the TRAINING part of history only, and each group is then judged on LATER unseen
 * data against the chance baseline with a multiple-testing correction. Frequency is not prediction: a group is only
 * VALIDATED when the later data agrees. Everything is price-derived and reproducible from the stored definition.
 */
public final class Discovery {
    private Discovery() {}

    public static final String SCHEMA = "win6-v1";
    public static final int W = 6, H = 12, K = 8, DIM = W * 4 + 2, MIN_N = 30;
    /** two-sided z for alpha 0.05 split over K groups (Bonferroni) */
    public static final double Z_CORRECTED = 2.73;
    public static final double UP = 1.5, DOWN = 1.5;

    public static final class Cluster implements Serializable {
        private static final long serialVersionUID = 1L;
        public int id;
        public double[] centroid;
        public int trainN, trainUp, trainDown, valN, valUp, valDown;
        public double trainRate, valRate, baseTrain, baseVal, avgMfe, avgMae;
        /** BULLISH or BEARISH tendency (which side the training data favoured) */
        public String tendency = "NONE";
        /** candidate, under test, validated, inconclusive, degraded, retired */
        public String status = "inconclusive";
        public String note = "";
        public long lastSeen;
    }

    public static final class Model implements Serializable {
        private static final long serialVersionUID = 1L;
        public String symbol = "", tf = "", schema = SCHEMA, discovered = "";
        public int window = W, horizon = H, k = K;
        public double[] mean = new double[DIM], std = new double[DIM];
        public long trainFrom, trainTo, valFrom, valTo;
        public double medianDist;
        public final List<Cluster> clusters = new ArrayList<>();
    }

    // ------------------------------------------------------------ features (use only candles up to and including `end`)
    static double[] features(Candle[] c, int end, double atr) {
        double[] f = new double[DIM];
        int s = end - W + 1;
        for (int j = 0; j < W; j++) {
            Candle k = c[s + j];
            double r = k.high - k.low;
            f[j * 4] = (k.close - k.open) / atr;
            f[j * 4 + 1] = (k.high - Math.max(k.open, k.close)) / atr;
            f[j * 4 + 2] = (Math.min(k.open, k.close) - k.low) / atr;
            f[j * 4 + 3] = r <= 0 ? 0.5 : (k.close - k.low) / r;
        }
        f[W * 4] = (c[end].close - c[s].open) / atr;
        double path = 0;
        for (int j = s; j <= end; j++) path += Math.abs(c[j].close - c[j].open);
        f[W * 4 + 1] = path == 0 ? 0 : Math.abs(c[end].close - c[s].open) / path;
        return f;
    }

    private static double dist(double[] a, double[] b) { double s = 0; for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; } return Math.sqrt(s); }

    /** +1 if price reaches +UP ATR before -DOWN ATR within H candles, -1 for the reverse, 0 if neither */
    private static int forward(Candle[] c, int end, double atr, double[] mfeMae) {
        double ref = c[end].close, up = ref + UP * atr, dn = ref - DOWN * atr, mf = 0, ma = 0;
        for (int k = end + 1; k <= end + H && k < c.length; k++) {
            mf = Math.max(mf, (c[k].high - ref) / atr); ma = Math.max(ma, (ref - c[k].low) / atr);
            boolean hu = c[k].high >= up, hd = c[k].low <= dn;
            if (hu && hd) { mfeMae[0] = mf; mfeMae[1] = ma; return 0; } // ambiguous inside one candle
            if (hu) { mfeMae[0] = mf; mfeMae[1] = ma; return 1; }
            if (hd) { mfeMae[0] = mf; mfeMae[1] = ma; return -1; }
        }
        mfeMae[0] = mf; mfeMae[1] = ma;
        return 0;
    }

    /** trainFrac of the windows (by time) train the clusters; the rest is held out and judged later */
    public static Model discover(String symbol, String tf, Candle[] c, double trainFrac, String today) {
        Model m = new Model();
        m.symbol = symbol; m.tf = tf; m.discovered = today;
        if (c == null || c.length < 600) return m;
        int first = 60, last = c.length - H - 1;
        List<Integer> ends = new ArrayList<>();
        for (int e = first; e <= last; e++) ends.add(e);
        int split = (int) (ends.size() * trainFrac);
        m.trainFrom = c[ends.get(0)].epoch; m.trainTo = c[ends.get(split - 1)].epoch;
        m.valFrom = c[ends.get(split)].epoch; m.valTo = c[ends.get(ends.size() - 1)].epoch;
        // training features, standardised with training statistics only
        List<double[]> raw = new ArrayList<>(); List<Double> atrs = new ArrayList<>();
        for (int i = 0; i < split; i++) { int e = ends.get(i); double atr = Vol.atrAt(c, e, 14); atrs.add(atr); raw.add(atr > 0 ? features(c, e, atr) : null); }
        int cnt = 0;
        for (double[] f : raw) if (f != null) { cnt++; for (int d = 0; d < DIM; d++) m.mean[d] += f[d]; }
        for (int d = 0; d < DIM; d++) m.mean[d] /= Math.max(1, cnt);
        for (double[] f : raw) if (f != null) for (int d = 0; d < DIM; d++) m.std[d] += (f[d] - m.mean[d]) * (f[d] - m.mean[d]);
        for (int d = 0; d < DIM; d++) { m.std[d] = Math.sqrt(m.std[d] / Math.max(1, cnt)); if (m.std[d] < 1e-9) m.std[d] = 1; }
        List<double[]> train = new ArrayList<>(); List<Integer> trainEnds = new ArrayList<>();
        for (int i = 0; i < split; i++) if (raw.get(i) != null) { train.add(norm(m, raw.get(i))); trainEnds.add(ends.get(i)); }
        double[][] cent = kmeans(train, K);
        // assign, then evaluate train and validation (non-overlapping occurrences: skip windows inside the last one of the same group)
        int[] lastTrain = new int[K], lastVal = new int[K];
        java.util.Arrays.fill(lastTrain, -1000); java.util.Arrays.fill(lastVal, -1000);
        for (int g = 0; g < K; g++) { Cluster cl = new Cluster(); cl.id = g; cl.centroid = cent[g]; m.clusters.add(cl); }
        double baseTU = 0, baseTD = 0, baseVU = 0, baseVD = 0;
        List<Double> dists = new ArrayList<>();
        double[] mm = new double[2];
        double[] mfeSum = new double[K], maeSum = new double[K]; int[] mfeN = new int[K];
        for (int i = 0; i < ends.size(); i++) {
            int e = ends.get(i);
            double atr = Vol.atrAt(c, e, 14);
            if (atr <= 0) continue;
            double[] f = norm(m, features(c, e, atr));
            int g = nearest(cent, f);
            Cluster cl = m.clusters.get(g);
            boolean tr = i < split;
            int outcome = forward(c, e, atr, mm);
            if (tr) { if (outcome > 0) baseTU++; else if (outcome < 0) baseTD++; dists.add(dist(f, cent[g])); }
            else { if (outcome > 0) baseVU++; else if (outcome < 0) baseVD++; }
            int[] lastArr = tr ? lastTrain : lastVal;
            if (e - lastArr[g] < W) continue;
            lastArr[g] = e;
            if (tr) { cl.trainN++; if (outcome > 0) cl.trainUp++; else if (outcome < 0) cl.trainDown++; }
            else { cl.valN++; if (outcome > 0) cl.valUp++; else if (outcome < 0) cl.valDown++; }
            mfeSum[g] += mm[0]; maeSum[g] += mm[1]; mfeN[g]++;
            cl.lastSeen = c[e].epoch;
        }
        java.util.Collections.sort(dists);
        m.medianDist = dists.isEmpty() ? 0 : dists.get(dists.size() / 2);
        double bt = baseTU + baseTD == 0 ? 0.5 : baseTU / (baseTU + baseTD), bv = baseVU + baseVD == 0 ? 0.5 : baseVU / (baseVU + baseVD);
        for (Cluster cl : m.clusters) {
            cl.baseTrain = bt; cl.baseVal = bv;
            cl.avgMfe = mfeN[cl.id] == 0 ? 0 : mfeSum[cl.id] / mfeN[cl.id]; cl.avgMae = mfeN[cl.id] == 0 ? 0 : maeSum[cl.id] / mfeN[cl.id];
            int tRes = cl.trainUp + cl.trainDown, vRes = cl.valUp + cl.valDown;
            cl.trainRate = tRes == 0 ? 0.5 : (double) cl.trainUp / tRes; cl.valRate = vRes == 0 ? 0.5 : (double) cl.valUp / vRes;
            if (tRes < MIN_N) { cl.status = "inconclusive"; cl.note = "only " + tRes + " resolved training occurrences (need " + MIN_N + ")"; continue; }
            double[] ci = wilson(cl.trainUp, tRes, Z_CORRECTED);
            if (ci[0] > bt) { cl.tendency = "BULLISH"; } else if (ci[1] < bt) { cl.tendency = "BEARISH"; }
            if (cl.tendency.equals("NONE")) { cl.status = "inconclusive"; cl.note = "training rate " + pct(cl.trainRate) + " is not distinguishable from chance " + pct(bt); continue; }
            cl.status = "candidate";
            if (vRes < MIN_N) { cl.status = "under test"; cl.note = "only " + vRes + " resolved later occurrences (need " + MIN_N + ")"; continue; }
            double[] vc = wilson(cl.valUp, vRes, Z_CORRECTED);
            boolean holds = cl.tendency.equals("BULLISH") ? vc[0] > bv : vc[1] < bv;
            cl.status = holds ? "validated" : "inconclusive";
            cl.note = holds ? "later data agrees: " + pct(cl.valRate) + " vs chance " + pct(bv) : "later data does not confirm it: " + pct(cl.valRate) + " vs chance " + pct(bv);
        }
        return m;
    }

    private static String pct(double v) { return String.format(Locale.US, "%.0f%%", v * 100); }

    static double[] wilson(int s, int n, double z) {
        double p = (double) s / n, z2 = z * z, den = 1 + z2 / n, centre = (p + z2 / (2 * n)) / den, half = z * Math.sqrt((p * (1 - p) + z2 / (4.0 * n)) / n) / den;
        return new double[]{centre - half, centre + half};
    }

    private static double[] norm(Model m, double[] f) { double[] o = new double[DIM]; for (int d = 0; d < DIM; d++) o[d] = (f[d] - m.mean[d]) / m.std[d]; return o; }

    private static int nearest(double[][] cent, double[] f) { int b = 0; double bd = Double.MAX_VALUE; for (int g = 0; g < cent.length; g++) { double d = dist(cent[g], f); if (d < bd) { bd = d; b = g; } } return b; }

    /** deterministic k-means with k-means++ seeding (fixed seed so a result can be reproduced) */
    static double[][] kmeans(List<double[]> x, int k) {
        Random rnd = new Random(42);
        double[][] c = new double[k][];
        c[0] = x.get(rnd.nextInt(x.size())).clone();
        double[] dmin = new double[x.size()];
        for (int g = 1; g < k; g++) {
            double tot = 0;
            for (int i = 0; i < x.size(); i++) { double d = Double.MAX_VALUE; for (int q = 0; q < g; q++) d = Math.min(d, dist(x.get(i), c[q])); dmin[i] = d * d; tot += dmin[i]; }
            double pick = rnd.nextDouble() * tot, acc = 0; int sel = x.size() - 1;
            for (int i = 0; i < x.size(); i++) { acc += dmin[i]; if (acc >= pick) { sel = i; break; } }
            c[g] = x.get(sel).clone();
        }
        int[] asg = new int[x.size()];
        for (int it = 0; it < 25; it++) {
            boolean changed = false;
            for (int i = 0; i < x.size(); i++) { int g = nearest(c, x.get(i)); if (g != asg[i]) { asg[i] = g; changed = true; } }
            double[][] sum = new double[k][DIM]; int[] n = new int[k];
            for (int i = 0; i < x.size(); i++) { n[asg[i]]++; for (int d = 0; d < DIM; d++) sum[asg[i]][d] += x.get(i)[d]; }
            for (int g = 0; g < k; g++) if (n[g] > 0) for (int d = 0; d < DIM; d++) c[g][d] = sum[g][d] / n[g];
            if (!changed) break;
        }
        return c;
    }

    // ------------------------------------------------------------ matching and storage
    public static final class Match {
        public Cluster cluster;
        public double distance;
        public boolean familiar;
        public String describe() {
            if (cluster == null) return "No discovered behaviour to compare with.";
            return String.format(Locale.US, "Behaviour group %d (%s%s): %d earlier occurrences, later-data check: %s", cluster.id, cluster.status.toUpperCase(Locale.US), cluster.tendency.equals("NONE") ? "" : ", " + cluster.tendency.toLowerCase(Locale.US) + " tendency", cluster.trainN + cluster.valN, cluster.note.isEmpty() ? "none" : cluster.note);
        }
    }

    /** the group the latest closed candles resemble most; `familiar` is false when the window is unlike anything seen in training */
    public static Match match(Model m, Candle[] closed) {
        Match r = new Match();
        if (m == null || m.clusters.isEmpty() || closed == null || closed.length < 60) return r;
        int e = closed.length - 1;
        double atr = Vol.atrAt(closed, e, 14);
        if (atr <= 0) return r;
        double[] f = norm(m, features(closed, e, atr));
        double[][] cent = new double[m.clusters.size()][];
        for (int g = 0; g < cent.length; g++) cent[g] = m.clusters.get(g).centroid;
        int g = nearest(cent, f);
        r.cluster = m.clusters.get(g); r.distance = dist(f, cent[g]); r.familiar = r.distance <= 2.0 * Math.max(1e-9, m.medianDist);
        return r;
    }

    /** machine-readable definition (stored in the research database) */
    public static String toJson(Model m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schema", m.schema); o.put("symbol", m.symbol); o.put("timeframe", m.tf); o.put("discovered", m.discovered);
        o.put("window", m.window); o.put("horizon", m.horizon); o.put("k", m.k);
        o.put("target_atr", UP); o.put("adverse_atr", DOWN); o.put("alpha_z", Z_CORRECTED);
        o.put("train", range(m.trainFrom, m.trainTo)); o.put("validation", range(m.valFrom, m.valTo));
        o.put("mean", m.mean); o.put("std", m.std); o.put("median_distance", m.medianDist);
        List<Object> cl = new ArrayList<>();
        for (Cluster c : m.clusters) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.id); x.put("status", c.status); x.put("tendency", c.tendency); x.put("note", c.note);
            x.put("train_n", c.trainN); x.put("train_up", c.trainUp); x.put("train_down", c.trainDown);
            x.put("val_n", c.valN); x.put("val_up", c.valUp); x.put("val_down", c.valDown);
            x.put("avg_mfe_atr", c.avgMfe); x.put("avg_mae_atr", c.avgMae); x.put("last_seen", c.lastSeen); x.put("centroid", c.centroid);
            cl.add(x);
        }
        o.put("clusters", cl);
        return MiniJson.write(o);
    }

    private static Map<String, Object> range(long a, long b) { Map<String, Object> r = new LinkedHashMap<>(); r.put("from", a); r.put("to", b); return r; }

    @SuppressWarnings("unchecked")
    public static Model fromJson(String json) throws MiniJson.ParseException {
        Map<String, Object> o = (Map<String, Object>) MiniJson.parse(json);
        Model m = new Model();
        m.schema = (String) o.get("schema"); m.symbol = (String) o.get("symbol"); m.tf = (String) o.get("timeframe"); m.discovered = (String) o.get("discovered");
        m.medianDist = ((Number) o.get("median_distance")).doubleValue();
        List<Object> mean = (List<Object>) o.get("mean"), std = (List<Object>) o.get("std");
        for (int i = 0; i < DIM; i++) { m.mean[i] = ((Number) mean.get(i)).doubleValue(); m.std[i] = ((Number) std.get(i)).doubleValue(); }
        Map<String, Object> tr = (Map<String, Object>) o.get("train"), va = (Map<String, Object>) o.get("validation");
        m.trainFrom = ((Number) tr.get("from")).longValue(); m.trainTo = ((Number) tr.get("to")).longValue();
        m.valFrom = ((Number) va.get("from")).longValue(); m.valTo = ((Number) va.get("to")).longValue();
        for (Object co : (List<Object>) o.get("clusters")) {
            Map<String, Object> x = (Map<String, Object>) co; Cluster c = new Cluster();
            c.id = ((Number) x.get("id")).intValue(); c.status = (String) x.get("status"); c.tendency = (String) x.get("tendency"); c.note = (String) x.get("note");
            c.trainN = ((Number) x.get("train_n")).intValue(); c.trainUp = ((Number) x.get("train_up")).intValue(); c.trainDown = ((Number) x.get("train_down")).intValue();
            c.valN = ((Number) x.get("val_n")).intValue(); c.valUp = ((Number) x.get("val_up")).intValue(); c.valDown = ((Number) x.get("val_down")).intValue();
            c.avgMfe = ((Number) x.get("avg_mfe_atr")).doubleValue(); c.avgMae = ((Number) x.get("avg_mae_atr")).doubleValue(); c.lastSeen = ((Number) x.get("last_seen")).longValue();
            List<Object> ce = (List<Object>) x.get("centroid"); c.centroid = new double[DIM]; for (int i = 0; i < DIM; i++) c.centroid[i] = ((Number) ce.get(i)).doubleValue();
            int tRes = c.trainUp + c.trainDown, vRes = c.valUp + c.valDown;
            c.trainRate = tRes == 0 ? 0.5 : (double) c.trainUp / tRes; c.valRate = vRes == 0 ? 0.5 : (double) c.valUp / vRes;
            m.clusters.add(c);
        }
        return m;
    }

    /** drift: re-judge each validated group on the most recent `recentFrac` of the supplied candles; flag when it no longer holds */
    public static List<String> drift(Model m, Candle[] c, double recentFrac) {
        List<String> alerts = new ArrayList<>();
        if (m == null || c == null || c.length < 400) return alerts;
        int from = (int) (c.length * (1 - recentFrac));
        double[][] cent = new double[m.clusters.size()][];
        for (int g = 0; g < cent.length; g++) cent[g] = m.clusters.get(g).centroid;
        int[] up = new int[cent.length], dn = new int[cent.length], last = new int[cent.length];
        java.util.Arrays.fill(last, -1000);
        double bu = 0, bd = 0; double[] mm = new double[2];
        for (int e = Math.max(60, from); e < c.length - H - 1; e++) {
            double atr = Vol.atrAt(c, e, 14); if (atr <= 0) continue;
            int g = nearest(cent, norm(m, features(c, e, atr)));
            int o = forward(c, e, atr, mm);
            if (o > 0) bu++; else if (o < 0) bd++;
            if (e - last[g] < W) continue; last[g] = e;
            if (o > 0) up[g]++; else if (o < 0) dn[g]++;
        }
        double base = bu + bd == 0 ? 0.5 : bu / (bu + bd);
        for (Cluster cl : m.clusters) {
            if (!cl.status.equals("validated")) continue;
            int n = up[cl.id] + dn[cl.id];
            if (n < 20) continue;
            double rate = (double) up[cl.id] / n;
            boolean holds = cl.tendency.equals("BULLISH") ? rate > base : rate < base;
            if (!holds) { cl.status = "degraded"; cl.note = "recent " + n + " occurrences: " + pct(rate) + " vs chance " + pct(base); alerts.add("Behaviour group " + cl.id + " (" + m.symbol + " " + m.tf + ") no longer holds: " + cl.note); }
        }
        return alerts;
    }
}
