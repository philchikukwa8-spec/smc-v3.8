package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Candle data validation: order, duplicates, gaps, a still-forming last candle, staleness. */
public final class DataCheck {
    private DataCheck() {}

    public static final String LIVE = "LIVE", PROVISIONAL = "PROVISIONAL", STALE = "STALE", BAD = "BAD", EMPTY = "EMPTY";

    public static final class Report implements Serializable {
        private static final long serialVersionUID = 1L;
        public String status = EMPTY;
        public int count, duplicates, outOfOrder, gaps, missingCandles;
        public boolean lastForming;
        public long lastEpoch, ageSec;
        public String note = "";
        public boolean usable() { return status.equals(LIVE) || status.equals(PROVISIONAL); }
    }

    /** sorted by time, duplicates removed (the later copy wins) */
    public static Candle[] clean(Candle[] c) {
        if (c == null || c.length == 0) return new Candle[0];
        Candle[] s = Arrays.copyOf(c, c.length);
        Arrays.sort(s, new Comparator<Candle>() { @Override public int compare(Candle a, Candle b) { return Long.compare(a.epoch, b.epoch); } });
        List<Candle> out = new ArrayList<>();
        for (Candle x : s) {
            if (!out.isEmpty() && out.get(out.size() - 1).epoch == x.epoch) out.set(out.size() - 1, x);
            else out.add(x);
        }
        return out.toArray(new Candle[0]);
    }

    /** `now` is epoch seconds. A candle is forming while epoch + gran > now. */
    public static Report check(Candle[] c, long gran, long now) {
        Report r = new Report();
        if (c == null || c.length == 0) { r.note = "No candles."; return r; }
        r.count = c.length;
        for (int i = 1; i < c.length; i++) {
            if (c[i].epoch == c[i - 1].epoch) r.duplicates++;
            else if (c[i].epoch < c[i - 1].epoch) r.outOfOrder++;
        }
        Candle[] k = clean(c);
        for (int i = 1; i < k.length; i++) {
            long d = k[i].epoch - k[i - 1].epoch;
            if (d > gran) { r.gaps++; r.missingCandles += (int) (d / gran) - 1; }
        }
        for (Candle x : k) if (!(x.high >= x.low) || x.high < Math.max(x.open, x.close) - 1e-9 || x.low > Math.min(x.open, x.close) + 1e-9) { r.note = "Impossible candle shape at " + x.epoch + ". "; r.status = BAD; }
        Candle last = k[k.length - 1];
        r.lastEpoch = last.epoch;
        r.lastForming = last.epoch + gran > now;
        long lastClose = r.lastForming ? last.epoch : last.epoch + gran;
        r.ageSec = Math.max(0, now - lastClose);
        if (r.status.equals(BAD)) return r;
        if (r.ageSec > 3 * gran + 120) { r.status = STALE; r.note += "Newest candle is " + r.ageSec / 60 + " min old."; return r; }
        if (r.gaps > 0 && r.missingCandles > Math.max(3, k.length / 20)) { r.status = BAD; r.note += r.missingCandles + " candles are missing."; return r; }
        r.status = r.lastForming ? PROVISIONAL : LIVE;
        if (r.gaps > 0) r.note += r.missingCandles + " missing candle(s). ";
        if (r.duplicates > 0) r.note += r.duplicates + " duplicate(s) removed. ";
        return r;
    }
}
