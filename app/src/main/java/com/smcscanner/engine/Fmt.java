package com.smcscanner.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Number formatting helpers. */
public final class Fmt {
    private Fmt() {}

    /** Up to 4 decimals, no trailing zeros (101.5, 0.0123, 100). */
    public static String f(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) return "-";
        String s = BigDecimal.valueOf(n).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s;
    }

    public static String d1(double v) { return String.format(Locale.US, "%.1f", v); }

    public static String d3(double v) { return String.format(Locale.US, "%.3f", v); }
}
