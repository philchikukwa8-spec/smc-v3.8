package com.smcscanner.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** A validated trade setup on one timeframe. BUY and SELL use identical rules (SELL = mirror image). */
public final class Setup implements Serializable {
    private static final long serialVersionUID = 1L;
    public String id;
    /** "BUY" or "SELL" */
    public String side;
    /** epoch (seconds) of the candle that validated the setup */
    public long triggerEpoch;
    /** "BOS" or "CHoCH" */
    public String structure;
    public double sweepLevel;
    public double brokenLevel;
    /** "FVG" or "OB" */
    public String zoneType;
    public double zoneLow, zoneHigh;
    /** order block range if one exists (null if none) */
    public Double obLow, obHigh;
    public double entry, sl, tp;
    /** "liquidity" or "fixed" */
    public String tpType;
    public double rr;
    public boolean discount;
    public long sweepEpoch;
    public double sweepExtreme;
    public boolean majorSweep;
    public double dispRatio;
    public boolean overlap;
    public double rangeLow, rangeHigh, eq;
    public double avgRange;
    /** confluences met out of maxScore. NOT a win probability. */
    public int score;
    public int maxScore = 7;
    public ArrayList<String> reasons = new ArrayList<>();

    public boolean isBuy() { return "BUY".equals(side); }

    public Setup copy() {
        Setup s = new Setup();
        s.id = id; s.side = side; s.triggerEpoch = triggerEpoch; s.structure = structure;
        s.sweepLevel = sweepLevel; s.brokenLevel = brokenLevel; s.zoneType = zoneType;
        s.zoneLow = zoneLow; s.zoneHigh = zoneHigh; s.obLow = obLow; s.obHigh = obHigh;
        s.entry = entry; s.sl = sl; s.tp = tp; s.tpType = tpType; s.rr = rr; s.discount = discount;
        s.sweepEpoch = sweepEpoch; s.sweepExtreme = sweepExtreme; s.majorSweep = majorSweep;
        s.dispRatio = dispRatio; s.overlap = overlap; s.rangeLow = rangeLow; s.rangeHigh = rangeHigh;
        s.eq = eq; s.avgRange = avgRange; s.score = score; s.maxScore = maxScore;
        s.reasons = new ArrayList<>(reasons);
        return s;
    }
}
