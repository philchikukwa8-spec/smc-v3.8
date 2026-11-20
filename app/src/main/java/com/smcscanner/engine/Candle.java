package com.smcscanner.engine;

import java.io.Serializable;

/** One price candle. epoch = open time in seconds. */
public final class Candle implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long epoch;
    public final double open, high, low, close;

    public Candle(long epoch, double open, double high, double low, double close) {
        this.epoch = epoch;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }
}
