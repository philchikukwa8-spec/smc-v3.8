package com.smcscanner.engine;

import java.io.Serializable;

/** Strategy settings (same defaults as the tested TypeScript version). */
public final class SmcConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    /** candles on each side needed to confirm a swing high/low */
    public int swingLen = 3;
    /** how many candles back a liquidity sweep may be */
    public int sweepWindow = 15;
    /** break candle body must be >= this x the average body */
    public double displacementMult = 1.3;
    /** minimum reward:risk */
    public double minRR = 2;
    /** stop-loss buffer beyond the sweep, as a fraction of average candle range */
    public double slBuffer = 0.25;
    /** candles used for the average body / range */
    public int avgLen = 20;
    /** how far back to look for swing points / targets */
    public int lookback = 80;
}
