package com.smcscanner.chart;

import java.util.ArrayList;
import java.util.List;

/** Everything drawn on top of the candles: zones, price lines and risk/reward boxes. */
public final class Overlay {
    /** shaded price band, from startEpoch to the right edge */
    public static final class Zone {
        public double low, high;
        public long startEpoch;
        public String label;
        public int color;
        /** the idea this zone belongs to (tap it to select), or null */
        public String id;
        /** draw the label at the left edge (used for support/resistance so it stays clear of the price-line labels) */
        public boolean labelLeft;
        public Zone(double low, double high, long startEpoch, String label, int color) { this.low = low; this.high = high; this.startEpoch = startEpoch; this.label = label; this.color = color; }
    }

    /** horizontal price line with a tag on the price axis */
    public static final class Level {
        public double price;
        public String label;
        public int color;
        public boolean dashed;
        public long startEpoch;
        public Level(double price, String label, int color, boolean dashed, long startEpoch) { this.price = price; this.label = label; this.color = color; this.dashed = dashed; this.startEpoch = startEpoch; }
    }

    /** a trade drawn as a risk box (entry to stop) and reward box (entry to targets) */
    public static final class Position {
        public boolean buy;
        public double entry, stop, tp1;
        public Double tp2;
        public long startEpoch;
        /** money risked (0 = show points and R only) */
        public double riskMoney;
        public String title = "";
        /** the idea this trade belongs to (tap it to select), or null */
        public String id;
        /** price now, for an open trade: draws the running result */
        public Double now;
    }

    /** a line between two points on the chart (pattern trendlines, structure breaks) */
    public static final class Seg {
        public long epoch1, epoch2;
        public double price1, price2;
        public String label;
        public int color;
        public boolean dashed;
        public Seg(long e1, double p1, long e2, double p2, String label, int color, boolean dashed) { epoch1 = e1; price1 = p1; epoch2 = e2; price2 = p2; this.label = label; this.color = color; this.dashed = dashed; }
    }

    /** a small tag on a candle (liquidity sweep, break of structure) */
    public static final class Marker {
        public long epoch;
        public double price;
        public String label;
        public int color;
        /** true = tag sits above the price, false = below */
        public boolean above;
        public Marker(long epoch, double price, String label, int color, boolean above) { this.epoch = epoch; this.price = price; this.label = label; this.color = color; this.above = above; }
    }

    public final List<Seg> segs = new ArrayList<>();
    public final List<Marker> markers = new ArrayList<>();
    /** lines of text shown top-left: what the highlighted trade is made of */
    public final List<String> info = new ArrayList<>();
    /** the selected idea (its details are drawn), or null */
    public String selectedId;
    public final List<Zone> zones = new ArrayList<>();
    public final List<Level> levels = new ArrayList<>();
    public final List<Position> positions = new ArrayList<>();

    public boolean isEmpty() { return zones.isEmpty() && levels.isEmpty() && positions.isEmpty() && segs.isEmpty() && markers.isEmpty(); }
}
