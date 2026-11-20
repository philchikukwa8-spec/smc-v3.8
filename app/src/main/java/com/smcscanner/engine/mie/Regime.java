package com.smcscanner.engine.mie;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Smc;

import java.io.Serializable;
import java.util.Locale;

/** Market regime on one timeframe, from measurable features. UNKNOWN is a valid answer. */
public final class Regime {
    private Regime() {}

    public static final String VERSION = "regime-1";
    public static final String BULL = "BULL_TREND", BEAR = "BEAR_TREND", RANGE = "RANGE", EXPANSION = "VOL_EXPANSION",
        COMPRESSION = "VOL_COMPRESSION", TRANSITION = "TRANSITION", UNKNOWN = "UNKNOWN";

    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;
        public String label = UNKNOWN;
        public String structure = "range";
        public double efficiency, expansion, momentum, persistence;
        /** 0 to 1: how clearly the features agree. Not a probability. */
        public double quality;
        public long epoch;
        public String features() {
            return String.format(Locale.US, "structure %s, efficiency %.2f, ATR ratio %.2f, momentum %.1f ATR, persistence %.0f%%", structure, efficiency, expansion, momentum, persistence * 100);
        }
    }

    public static Result classify(Candle[] closed) {
        Result r = new Result();
        if (closed == null || closed.length < 60) { r.label = UNKNOWN; return r; }
        r.epoch = closed[closed.length - 1].epoch;
        r.structure = Smc.structureBias(closed, 2, 120);
        r.efficiency = Vol.efficiency(closed, 30);
        r.expansion = Vol.expansion(closed, 5, 30);
        r.momentum = Vol.momentum(closed, 30);
        r.persistence = Vol.persistence(closed, 20);
        boolean up = r.momentum > 0;
        if (r.efficiency >= 0.30 && Math.abs(r.momentum) >= 2.0) {
            boolean agree = (up && r.structure.equals("bull")) || (!up && r.structure.equals("bear"));
            if (agree) { r.label = up ? BULL : BEAR; r.quality = Math.min(1, 0.4 + r.efficiency); }
            else if (r.structure.equals("range")) { r.label = TRANSITION; r.quality = 0.4; }
            else { r.label = TRANSITION; r.quality = 0.5; }
        } else if (r.expansion >= 1.5) { r.label = EXPANSION; r.quality = Math.min(1, r.expansion / 2.5); }
        else if (r.expansion <= 0.65) { r.label = COMPRESSION; r.quality = Math.min(1, 0.65 / Math.max(0.2, r.expansion) / 1.5); }
        else if (r.efficiency < 0.22) { r.label = RANGE; r.quality = Math.min(1, 0.7 - r.efficiency); }
        else { r.label = TRANSITION; r.quality = 0.35; }
        if (r.quality < 0.3) r.label = UNKNOWN;
        return r;
    }
}
