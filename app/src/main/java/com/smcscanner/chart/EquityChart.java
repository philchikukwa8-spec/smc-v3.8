package com.smcscanner.chart;

import java.util.Locale;

/** Equity curve in R: cumulative result after each trade, with a zero line and the final value. Drawn through Gfx so it can be checked off-device. */
public final class EquityChart {
    private EquityChart() {}

    public static void draw(Gfx g, int w, int h, double[] cum, float d, boolean dark) {
        int bg = dark ? 0xFF0C2236 : 0xFFF1F5F8, grid = dark ? 0xFF1B3550 : 0xFFD5DEE6, dim = dark ? 0xFF8A9AA6 : 0xFF5B6B77;
        int green = 0xFF26A69A, red = 0xFFEF5350;
        g.fillRound(0, 0, w, h, 12 * d, bg);
        float padL = 10 * d, padR = 58 * d, padT = 14 * d, padB = 22 * d;
        float pw = w - padL - padR, ph = h - padT - padB;
        if (cum == null || cum.length < 2) {
            g.text("Not enough trades for a curve yet", w / 2f, h / 2f, 12 * d, dim, false, Gfx.CENTER);
            return;
        }
        double lo = 0, hi = 0;
        for (double v : cum) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi - lo < 1) { hi += 0.5; lo -= 0.5; }
        double pad = (hi - lo) * 0.08; hi += pad; lo -= pad;
        int n = cum.length;
        // grid and axis labels
        for (int i = 0; i <= 3; i++) {
            double v = lo + (hi - lo) * i / 3.0;
            float y = (float) (padT + ph * (1 - (v - lo) / (hi - lo)));
            g.line(padL, y, padL + pw, y, 1f, grid, false);
            g.text(String.format(Locale.US, "%+.0fR", v), w - padR + 6 * d, y + 4 * d, 10 * d, dim, false, Gfx.LEFT);
        }
        float zy = (float) (padT + ph * (1 - (0 - lo) / (hi - lo)));
        g.line(padL, zy, padL + pw, zy, 1.5f * d, dim, true);
        // the curve: green above zero, red below
        float px = 0, py = 0;
        for (int i = 0; i < n; i++) {
            float x = padL + pw * i / (n - 1f);
            float y = (float) (padT + ph * (1 - (cum[i] - lo) / (hi - lo)));
            if (i > 0) {
                boolean up = cum[i] >= 0 && cum[i - 1] >= 0;
                int col = cum[i] >= 0 ? green : red;
                // faint fill between the curve and zero
                g.line(x, y, x, zy, Math.max(1f, pw / n), (col & 0x00FFFFFF) | 0x22000000, false);
                g.line(px, py, x, y, 2f * d, up || cum[i] >= 0 ? green : red, false);
            }
            px = x; py = y;
        }
        double last = cum[n - 1];
        g.text(String.format(Locale.US, "Total %+.1fR", last), padL + 8 * d, padT + 18 * d, 14 * d, last >= 0 ? green : red, true, Gfx.LEFT);
        g.text("trade 1", padL, h - 6 * d, 10 * d, dim, false, Gfx.LEFT);
        g.text("trade " + n, padL + pw, h - 6 * d, 10 * d, dim, false, Gfx.RIGHT);
    }
}
