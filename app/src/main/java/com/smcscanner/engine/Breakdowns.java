package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.List;

import static com.smcscanner.engine.Fmt.d1;
import static com.smcscanner.engine.Fmt.d3;
import static com.smcscanner.engine.Fmt.f;

/** Builds the trade breakdown and renders it as text. */
public final class Breakdowns {
    private Breakdowns() {}

    /** Optional extra requirements on quality factors. */
    public static final class Filters {
        public List<String> require = new ArrayList<>();
        public int minQuality = 0;
        /** only alert when the entry was found on the 5m/1m (never fall back to the coarse entry zone) */
        public boolean requireZoom = false;
    }

    public static final class Input {
        public String ideaTf;
        /** lower timeframe label when trading top-down; null in single-timeframe mode */
        public String entryTf;
        public Setup idea;
        /** the setup that gives entry/stop: LTF setup merged with the idea target, or the idea itself */
        public Setup entry;
        public String nextTf;
        /** closed candles of the next timeframe above the idea timeframe (may be null) */
        public Candle[] nextCandles;
        public long nextGran;
        /** the timeframe two steps above the idea (for example the 1D for a 1H idea); used for the top-trend check */
        public String topTf;
        public Candle[] topCandles;
        public long topGran;
        /** closed candles of the idea timeframe, for the support/resistance and chart-pattern checks (null = skip them) */
        public Candle[] ideaCandles;
        public long ideaGran;
        /** use only next-timeframe candles that had closed by this epoch (keeps backtests free of look-ahead); 0 = off */
        public long asOf;
        /** which entry style was used (0 edge, 1 middle, 2 deep) */
        public int entryMode = 1;
        /** market symbol and stored calibrations, used for the confidence figure (both optional) */
        public String market;
        public Calibration.Book book;
        /** money the user risks per trade (0 = off) */
        public double riskMoney;
        /** zoom-in: the first entry zone (before zooming) and each finer step. entryTf is then the FINEST timeframe. */
        public Setup coarse;
        public String coarseTf;
        public List<Mtf.Step> steps;
    }

    public static Breakdown build(Input in) {
        Setup idea = in.idea, e = in.entry;
        String ideaTf = in.ideaTf;
        String entryTf = in.entryTf;
        boolean buy = e.isBuy();
        String dir = buy ? "bullish" : "bearish";

        // ---------- levels ----------
        double risk = Math.abs(e.entry - e.sl);
        double rr1 = risk > 0 ? Math.abs(e.tp - e.entry) / risk : 0;
        Candle[] nextClosed = null;
        if (in.nextCandles != null && in.nextCandles.length > 0) {
            if (in.asOf != 0 && in.nextGran != 0) {
                List<Candle> keep = new ArrayList<>();
                for (Candle c : in.nextCandles) if (c.epoch + in.nextGran <= in.asOf) keep.add(c);
                nextClosed = keep.toArray(new Candle[0]);
            } else {
                nextClosed = in.nextCandles;
            }
        }
        Double tp2 = (nextClosed != null && nextClosed.length > 20) ? Smc.nextLiquidity(nextClosed, buy, e.tp, 3, 300) : null;
        Double rr2 = (tp2 != null && risk > 0) ? Double.valueOf(Math.abs(tp2 - e.entry) / risk) : null;

        Breakdown.Levels lv = new Breakdown.Levels();
        lv.entry = e.entry;
        lv.stop = e.sl;
        lv.riskPts = risk;
        lv.riskPct = e.entry != 0 ? (risk / Math.abs(e.entry)) * 100 : 0;
        lv.tp1 = e.tp;
        lv.tp1Label = "liquidity".equals(e.tpType) ? ideaTf + " liquidity" : "fixed target (2R of the " + ideaTf + " idea)";
        lv.rr1 = rr1;
        lv.tp2 = tp2;
        lv.tp2Label = in.nextTf != null ? in.nextTf + " liquidity" : "next higher timeframe";
        lv.rr2 = rr2;

        Breakdown b = new Breakdown();
        b.side = e.side;
        b.ideaTf = ideaTf;
        b.entryTf = entryTf;
        b.nextTf = in.nextTf;
        b.levels = lv;
        List<Breakdown.Factor> fs = b.factors;

        // ---------- rules ----------
        fs.add(new Breakdown.Factor("i_sweep", ideaTf + ": liquidity swept", "idea", Boolean.TRUE,
            "A wick took " + (buy ? "the swing low" : "the swing high") + " at " + f(idea.sweepLevel) + " (reached " + f(idea.sweepExtreme)
                + ") and the candle closed back " + (buy ? "above" : "below") + " it."));
        fs.add(new Breakdown.Factor("i_break", ideaTf + ": structure break (" + idea.structure + ")", "idea", Boolean.TRUE,
            "A candle closed " + (buy ? "above " : "below ") + f(idea.brokenLevel) + ", the swing " + (buy ? "high" : "low") + " that formed before the sweep."));
        fs.add(new Breakdown.Factor("i_disp", ideaTf + ": displacement", "idea", Boolean.TRUE,
            "The breaking candle's body was " + d1(idea.dispRatio) + "x the recent average."));
        fs.add(new Breakdown.Factor("i_zone", ideaTf + ": " + idea.zoneType + " zone to return to", "idea", Boolean.TRUE,
            "Zone " + f(idea.zoneLow) + " - " + f(idea.zoneHigh) + (idea.obLow != null ? " (an order block is also present)." : ".")));
        boolean zoomed = in.coarse != null && in.steps != null && !in.steps.isEmpty();
        if (zoomed) {
            fs.add(new Breakdown.Factor("m_zone", in.coarseTf + ": entry zone confirmed inside the " + ideaTf + " zone", "entry", Boolean.TRUE,
                "A " + in.coarseTf + " sweep and structure break inside the " + ideaTf + " zone gave the first entry zone " + f(in.coarse.zoneLow) + " - " + f(in.coarse.zoneHigh) + "."));
            String prev = in.coarseTf;
            int k = 0;
            for (Mtf.Step st : in.steps) {
                if (k < in.steps.size() - 1) {
                    fs.add(new Breakdown.Factor("z" + k, st.label + ": precision entry inside the " + prev + " zone", "entry", Boolean.TRUE,
                        "On " + st.label + ", price swept " + f(st.setup.sweepLevel) + " and broke structure, with a tighter stop at " + f(st.setup.sl) + "."));
                }
                prev = st.label;
                k++;
            }
        }
        if (entryTf != null) {
            fs.add(new Breakdown.Factor("e_sweep", entryTf + ": liquidity swept inside the zone", "entry", Boolean.TRUE,
                "On " + entryTf + ", a wick took " + f(e.sweepLevel) + " and closed back " + (buy ? "above" : "below") + " it, inside the " + ideaTf + " zone."));
            fs.add(new Breakdown.Factor("e_break", entryTf + ": structure break (" + e.structure + ")", "entry", Boolean.TRUE,
                entryTf + " closed " + (buy ? "above " : "below ") + f(e.brokenLevel) + ": short-term momentum has turned " + (buy ? "up." : "down.")));
            fs.add(new Breakdown.Factor("e_disp", entryTf + ": displacement", "entry", Boolean.TRUE,
                "The " + entryTf + " breaking candle's body was " + d1(e.dispRatio) + "x average."));
            fs.add(new Breakdown.Factor("e_zone", entryTf + ": " + e.zoneType + " entry zone", "entry", Boolean.TRUE,
                "Entry zone " + f(e.zoneLow) + " - " + f(e.zoneHigh) + "."));
        }

        // ---------- quality factors (candidates, measured by the backtest) ----------
        String bias = null;
        if (nextClosed != null && nextClosed.length > 20) bias = Smc.structureBias(nextClosed, 3, 150);
        String tfName = in.nextTf != null ? in.nextTf : "higher-timeframe";
        Boolean trendPass;
        String trendDetail;
        if (bias == null) {
            trendPass = null;
            trendDetail = "No higher timeframe above this idea to judge the trend.";
        } else if (bias.equals("range")) {
            trendPass = Boolean.FALSE;
            trendDetail = tfName + " structure is ranging (no clear higher highs/lows), so there is no trend to trade with.";
        } else {
            boolean with = bias.equals("bull") == buy;
            trendPass = Boolean.valueOf(with);
            trendDetail = tfName + " structure is " + (bias.equals("bull") ? "bullish (higher highs and lows)" : "bearish (lower highs and lows)")
                + (with ? ", the same direction as this trade." : ", against this trade.");
        }
        fs.add(new Breakdown.Factor("trend", in.nextTf != null ? "With the " + in.nextTf + " trend" : "With the higher-timeframe trend", "quality", trendPass, trendDetail));

        // the top timeframe must agree too (not counted in the grade, but can be required as a filter)
        Candle[] topClosed = null;
        if (in.topCandles != null && in.topCandles.length > 0) {
            if (in.asOf != 0 && in.topGran != 0) {
                List<Candle> keepTop = new ArrayList<>();
                for (Candle c : in.topCandles) if (c.epoch + in.topGran <= in.asOf) keepTop.add(c);
                topClosed = keepTop.toArray(new Candle[0]);
            } else topClosed = in.topCandles;
        }
        if (in.topTf != null) {
            String topBias = topClosed != null && topClosed.length > 20 ? Smc.structureBias(topClosed, 3, 150) : null;
            Boolean topPass;
            String topDetail;
            if (topBias == null) { topPass = null; topDetail = "Not enough " + in.topTf + " history to judge its trend."; }
            else if (topBias.equals("range")) { topPass = Boolean.FALSE; topDetail = in.topTf + " structure is ranging, so it does not confirm this direction."; }
            else {
                boolean agree = topBias.equals("bull") == buy;
                topPass = Boolean.valueOf(agree);
                topDetail = in.topTf + " structure is " + (topBias.equals("bull") ? "bullish" : "bearish") + (agree ? ", the same direction as this trade." : ", against this trade.");
            }
            fs.add(new Breakdown.Factor("topTrend", "With the " + in.topTf + " trend too", "alignment", topPass, topDetail));
        }

        // support/resistance and chart-pattern confluence (shown in the alert, not counted in the grade; can be required)
        if (in.ideaCandles != null && in.ideaCandles.length > 0) {
            Candle[] ic = in.ideaCandles;
            if (in.asOf != 0 && in.ideaGran != 0) {
                List<Candle> keepIdea = new ArrayList<>();
                for (Candle cc : in.ideaCandles) if (cc.epoch + in.ideaGran <= in.asOf) keepIdea.add(cc);
                ic = keepIdea.toArray(new Candle[0]);
            }
            if (ic.length >= 60) {
                double iatr = Patterns.atr(ic, 14);
                Patterns.Zone hitZone = null;
                for (Patterns.Zone z : Patterns.keyZones(ic, 8)) {
                    if (buy ? z.kind.equals("resistance") : z.kind.equals("support")) continue;
                    if (Patterns.touchesZone(z, e.zoneLow, e.zoneHigh, 0.3 * iatr)) { hitZone = z; break; }
                }
                fs.add(new Breakdown.Factor("srZone", "On a key " + (buy ? "support" : "resistance") + " area", "alignment", Boolean.valueOf(hitZone != null),
                    hitZone != null ? "The entry zone sits on a " + hitZone.touches + "-touch " + ideaTf + " level (" + f(hitZone.low) + " - " + f(hitZone.high) + ") that price has turned from before."
                        : "No repeatedly-tested " + ideaTf + " " + (buy ? "support" : "resistance") + " area at the entry."));
                Patterns.Pattern agree = null, against = null;
                for (Patterns.Pattern pt : Patterns.detect(ic)) {
                    if (pt.bias.equals(e.side)) agree = pt;
                    else if (!pt.bias.equals("NEUTRAL") && pt.status.equals("confirmed")) against = pt;
                }
                Boolean patPass = agree != null ? Boolean.TRUE : against != null ? Boolean.FALSE : null;
                String patDetail = agree != null ? "A " + agree.name + " (" + agree.status + ") on the " + ideaTf + " chart points the same way (line " + f(agree.level) + ")."
                    : against != null ? "A confirmed " + against.name + " on the " + ideaTf + " chart points the other way."
                    : "No major chart pattern on the " + ideaTf + " chart right now.";
                fs.add(new Breakdown.Factor("pattern", "Chart pattern agrees", "alignment", patPass, patDetail));
            }
        }

        boolean inDiscount = buy ? e.entry < idea.eq : e.entry > idea.eq;
        fs.add(new Breakdown.Factor("discount", "Entry in " + (buy ? "discount" : "premium"), "quality", Boolean.valueOf(inDiscount),
            "Entry " + f(e.entry) + " is " + (inDiscount ? "in the " : "NOT in the ") + (buy ? "lower" : "upper") + " half of the " + ideaTf + " range (midpoint " + f(idea.eq) + ")."));
        fs.add(new Breakdown.Factor("majorSweep", "Swept the range extreme", "quality", Boolean.valueOf(idea.majorSweep),
            idea.majorSweep
                ? "The " + ideaTf + " sweep took the " + (buy ? "lowest" : "highest") + " confirmed swing in the range, so more stops were resting there."
                : "The " + ideaTf + " sweep took a minor swing, not the " + (buy ? "lowest" : "highest") + " one in the range."));
        fs.add(new Breakdown.Factor("strongDisp", "Strong displacement", "quality", Boolean.valueOf(idea.dispRatio >= 2),
            ideaTf + " breaking candle was " + d1(idea.dispRatio) + "x average (strong = 2x or more)."));
        fs.add(new Breakdown.Factor("overlap", "FVG and order block overlap", "quality", Boolean.valueOf(e.overlap),
            e.overlap ? "The entry zone has both an imbalance and an order block stacked on top of each other." : "Only one zone type at the entry (or they do not overlap)."));
        fs.add(new Breakdown.Factor("rrGood", "Reward:risk of 3 or more", "quality", Boolean.valueOf(rr1 >= 3),
            "Reward:risk to the first target is " + d1(rr1) + "."));

        int judged = 0, met = 0;
        for (Breakdown.Factor x : fs) {
            if (!"quality".equals(x.group) || x.pass == null) continue;
            judged++;
            if (x.pass.booleanValue()) met++;
        }
        double share = judged > 0 ? (double) met / judged : 0;
        b.qualityMet = met;
        b.qualityOf = judged;
        b.grade = share >= 0.75 ? "A" : share >= 0.5 ? "B" : "C";
        b.entryMode = in.entryMode;
        if (zoomed) {
            StringBuilder path = new StringBuilder(ideaTf + " idea > " + in.coarseTf);
            Breakdown.Rung r0 = new Breakdown.Rung();
            r0.label = in.coarseTf; r0.entry = in.coarse.entry; r0.stop = in.coarse.sl; r0.riskPts = Math.abs(in.coarse.entry - in.coarse.sl);
            r0.rr1 = r0.riskPts > 0 ? Math.abs(in.coarse.tp - in.coarse.entry) / r0.riskPts : 0;
            b.ladder.add(r0);
            for (Mtf.Step st : in.steps) {
                Breakdown.Rung r = new Breakdown.Rung();
                r.label = st.label; r.entry = st.setup.entry; r.stop = st.setup.sl; r.riskPts = Math.abs(st.setup.entry - st.setup.sl);
                r.rr1 = r.riskPts > 0 ? Math.abs(st.setup.tp - st.setup.entry) / r.riskPts : 0;
                b.ladder.add(r);
                path.append(" > ").append(st.label);
            }
            b.path = path.toString();
        }
        b.riskMoney = in.riskMoney;
        if (entryTf != null) {
            for (int m = 0; m < 3; m++) {
                double px = Mtf.entryPrice(e, m);
                if (buy ? !(px > e.sl && px < e.tp) : !(px < e.sl && px > e.tp)) continue;
                double rk = Math.abs(px - e.sl);
                if (!(rk > 0)) continue;
                Breakdown.EntryOption o = new Breakdown.EntryOption();
                o.name = Calibration.MODE_NAMES[m];
                o.entry = px; o.riskPts = rk; o.rr1 = Math.abs(e.tp - px) / rk;
                o.rr2 = tp2 != null ? Double.valueOf(Math.abs(tp2 - px) / rk) : null;
                o.chosen = m == in.entryMode;
                b.options.add(o);
            }
        }
        if (in.book != null && in.market != null && entryTf != null) {
            b.confidence = in.book.estimate(in.market, in.ideaTf, in.entryTf, in.entryMode, b.grade, rr1);
        }

        // ---------- story ----------
        List<String> st = b.story;
        String trapped = buy ? "sellers were trapped" : "buyers were trapped";
        if (entryTf != null) {
            st.add("The idea: on " + ideaTf + ", " + trapped + " when price swept " + f(idea.sweepLevel) + ", then closed " + (buy ? "above " : "below ") + f(idea.brokenLevel)
                + " with a strong " + dir + " candle. That left " + ("OB".equals(idea.zoneType) ? "an " : "a ") + idea.zoneType + " at " + f(idea.zoneLow) + " - " + f(idea.zoneHigh) + " where " + (buy ? "buyers" : "sellers") + " are likely to defend.");
            st.add("Price has now returned to that " + ideaTf + " zone, which is the area we want to trade from.");
            if (zoomed) {
                Breakdown.Rung first = b.ladder.get(0);
                st.add("Zooming in: the " + in.coarseTf + " gave a first entry zone at " + f(first.entry) + " with a stop " + f(first.riskPts) + " points away. Instead of using it, the app looked inside it on the finer charts for a more precise entry.");
                for (int q = 1; q < b.ladder.size(); q++) {
                    Breakdown.Rung a = b.ladder.get(q - 1), c = b.ladder.get(q);
                    double cut = a.riskPts > 0 ? 100 * (1 - c.riskPts / a.riskPts) : 0;
                    st.add(c.label + " confirmed inside the " + a.label + " zone (sweep, structure break, displacement). Entry " + f(c.entry) + ", stop " + f(c.stop) + ", risk " + f(c.riskPts)
                        + " points (" + (cut >= 0 ? d1(cut) + "% less" : d1(-cut) + "% more") + " risk than the " + a.label + " entry). The target stays the " + ideaTf + " target.");
                }
            }
            st.add("The entry: inside the zone, " + entryTf + " swept " + f(e.sweepLevel) + " and then broke structure " + (buy ? "up" : "down")
                + " with displacement, so short-term momentum agrees with the " + ideaTf + " idea. The limit entry is " + f(e.entry) + ", in the " + entryTf + " " + e.zoneType + ".");
        } else {
            st.add("On " + ideaTf + ", " + trapped + " when price swept " + f(idea.sweepLevel) + ", then closed " + (buy ? "above " : "below ") + f(idea.brokenLevel)
                + " with a strong " + dir + " candle. The limit entry " + f(e.entry) + " is in the " + idea.zoneType + " left behind (" + f(idea.zoneLow) + " - " + f(idea.zoneHigh) + ").");
        }
        st.add("The stop " + f(e.sl) + " sits just " + (buy ? "below" : "above") + " the sweep " + (buy ? "low" : "high") + " (" + f(e.sweepExtreme)
            + "). If price gets there, the trapped-" + (buy ? "sellers" : "buyers") + " idea is wrong.");
        st.add("First target " + f(lv.tp1) + " is the " + lv.tp1Label + ", " + d1(rr1) + "R from your entry."
            + (tp2 != null ? " Second target " + f(tp2) + " is the next " + (in.nextTf != null ? in.nextTf : "higher-timeframe") + " liquidity (" + d1(rr2) + "R)." : ""));

        // ---------- risks ----------
        List<String> rk = b.risks;
        for (Breakdown.Factor x : fs) {
            if ("quality".equals(x.group) && x.pass != null && !x.pass.booleanValue()) rk.add("Not met: " + x.label + ". " + x.detail);
        }
        if (bias != null && !bias.equals("range") && bias.equals("bull") != buy) rk.add("This trade is against the " + in.nextTf + " trend, so it is a counter-trend trade.");
        if ("fixed".equals(e.tpType)) rk.add("No liquidity level was found for the target, so the first target is a fixed distance (2R of the idea), not a market level.");
        if (tp2 == null && in.nextTf != null) rk.add("No " + in.nextTf + " liquidity found beyond the first target, so there is no second target.");
        if (rr1 < 2.5) rk.add("Reward:risk to the first target is only " + d1(rr1) + "; there is little room for error.");
        rk.add("Stop distance is " + f(risk) + " points (" + d3(lv.riskPct) + "% of price). Size the trade so that losing this much is an amount you accept.");
        if (tp2 != null) rk.add("Consider taking some profit at the first target and moving the stop to entry before aiming for the second.");

        // ---------- invalidation ----------
        String tfx = entryTf != null ? entryTf : ideaTf;
        b.invalidation.add("Price touches " + f(e.sl) + " (your stop): the trade is wrong. Exit.");
        b.invalidation.add(tfx + " closes " + (buy ? "below " : "above ") + f(e.sl) + ": invalid even if you are not stopped yet. Exit.");
        if (entryTf != null) b.invalidation.add(ideaTf + " closes " + (buy ? "below " : "above ") + f(idea.sl) + ": the whole " + ideaTf + " idea is cancelled.");
        b.invalidation.add("Price runs most of the way to "+f(lv.tp1)+" without filling your entry: skip it, do not chase.");
        return b;
    }

    public static boolean passes(Breakdown b, Filters flt) {
        if (flt == null) return true;
        for (String k : flt.require) {
            // "!name" means: only fail when that factor is clearly AGAINST the trade (a missing or neutral reading is fine)
            if (k.startsWith("!")) {
                Breakdown.Factor n = b.factor(k.substring(1));
                if (n != null && n.pass != null && !n.pass.booleanValue()) return false;
                continue;
            }
            Breakdown.Factor x = b.factor(k);
            if (x == null || x.pass == null || !x.pass.booleanValue()) return false;
        }
        return !(flt.minQuality > 0 && b.qualityMet < flt.minQuality);
    }

    /**
     * Splits the full alert text into foldable sections: [title, body] pairs.
     * A heading is a capitalised line (optionally followed by a bracket) that comes after a blank line or at the start.
     * Anything before the first heading comes back with an empty title.
     */
    public static List<String[]> sections(String full) {
        List<String[]> out = new ArrayList<>();
        java.util.regex.Pattern head = java.util.regex.Pattern.compile("^([A-Z][A-Z \\-]{4,}[A-Z])( \\(.*\\))?$");
        String[] lines = full.split("\n", -1);
        String title = "";
        StringBuilder body = new StringBuilder();
        boolean prevBlank = true;
        for (String line : lines) {
            java.util.regex.Matcher m = head.matcher(line);
            if (prevBlank && m.matches()) {
                if (title.length() > 0 || body.toString().trim().length() > 0) out.add(new String[]{title, body.toString().trim()});
                String cap = m.group(1);
                title = cap.substring(0, 1) + cap.substring(1).toLowerCase(java.util.Locale.US) + (m.group(2) != null ? m.group(2) : "");
                body.setLength(0);
            } else {
                body.append(line).append("\n");
            }
            prevBlank = line.trim().isEmpty();
        }
        if (title.length() > 0 || body.toString().trim().length() > 0) out.add(new String[]{title, body.toString().trim()});
        return out;
    }

    private static String mark(Breakdown.Factor x) {
        return x.pass == null ? "-" : x.pass.booleanValue() ? "[OK]" : "[X]";
    }

    private static String money(double v) { return String.format(java.util.Locale.US, "%.2f", v); }

    /** "41% win rate (17 of 41)" or "not calibrated" */
    public static String confidenceShort(Breakdown b) {
        Calibration.Estimate c = b.confidence;
        if (c == null) return "Confidence: not calibrated yet";
        if (!c.reliable) return "Past win rate unproven (only " + c.n + " similar trades)";
        return "Past win rate " + Math.round(c.pct) + "% (" + c.wins + "/" + c.n + ")";
    }

    /** One-line notification body. */
    public static String shortText(Breakdown b) {
        Breakdown.Levels L = b.levels;
        return "Entry " + f(L.entry) + " | SL " + f(L.stop) + " (risk " + f(L.riskPts) + ") | TP1 " + f(L.tp1) + " (" + d1(L.rr1) + "R)"
            + (L.tp2 != null ? " | TP2 " + f(L.tp2) + " (" + d1(L.rr2) + "R)" : "") + " | Grade " + b.grade
            + (b.confidence == null ? "" : b.confidence.reliable ? " | " + Math.round(b.confidence.pct) + "% past win rate (" + b.confidence.n + " trades)"
                : " | past win rate unproven (only " + b.confidence.n + " trades)");
    }

    /** Full plain-text version (used in the alert history). */
    public static String fullText(Breakdown b, String market, double price) {
        Breakdown.Levels L = b.levels;
        StringBuilder sb = new StringBuilder();
        sb.append(b.side).append(" - ").append(market).append("\n");
        sb.append(b.path.isEmpty() ? b.ideaTf + (b.entryTf != null ? " idea -> " + b.entryTf + " entry" : " setup") : b.path)
            .append(" | Grade ").append(b.grade).append(" (").append(b.qualityMet).append("/").append(b.qualityOf).append(" quality factors)\n\n");
        sb.append("THE TRADE\n");
        sb.append("Entry (limit): ").append(f(L.entry)).append("\n");
        sb.append("Stop loss: ").append(f(L.stop)).append("  risk ").append(f(L.riskPts)).append(" pts (").append(d3(L.riskPct)).append("%)\n");
        sb.append("Target 1: ").append(f(L.tp1)).append("  ").append(L.tp1Label).append("  ").append(d1(L.rr1)).append("R\n");
        if (L.tp2 != null) sb.append("Target 2: ").append(f(L.tp2)).append("  ").append(L.tp2Label).append("  ").append(d1(L.rr2)).append("R\n");
        else sb.append("Target 2: none found\n");

        sb.append("\nRISK AND REWARD\n");
        sb.append("You risk ").append(f(L.riskPts)).append(" points to make ").append(f(Math.abs(L.tp1 - L.entry))).append(" at Target 1")
            .append(L.tp2 != null ? " or " + f(Math.abs(L.tp2 - L.entry)) + " at Target 2" : "").append(".\n");
        if (b.riskMoney > 0) {
            sb.append("If you risk $").append(money(b.riskMoney)).append(": lose $").append(money(b.riskMoney)).append(" at the stop, win $")
                .append(money(b.riskMoney * L.rr1)).append(" at Target 1");
            if (L.tp2 != null) sb.append(", $").append(money(b.riskMoney * L.rr2)).append(" at Target 2");
            sb.append(".\nSize the trade so that ").append(f(L.riskPts)).append(" points against you costs $").append(money(b.riskMoney)).append(".\n");
        } else sb.append("Set 'Risk per trade' in Settings to see this in money.\n");

        if (b.ladder.size() > 1) {
            sb.append("\nPRECISION ENTRY (").append(b.path).append(")\n");
            for (Breakdown.Rung r : b.ladder) sb.append(r == b.ladder.get(b.ladder.size() - 1) ? "> " : "  ").append(r.label).append(": entry ").append(f(r.entry))
                .append("  stop ").append(f(r.stop)).append("  risk ").append(f(r.riskPts)).append("  ").append(d1(r.rr1)).append("R\n");
            Breakdown.Rung f0 = b.ladder.get(0), fl = b.ladder.get(b.ladder.size() - 1);
            if (f0.riskPts > 0) sb.append("Zooming in changed the risk by ").append(d1(100 * (1 - fl.riskPts / f0.riskPts))).append("% versus the first entry zone. The target is still the ").append(b.ideaTf).append(" target.\n");
        }
        if (!b.options.isEmpty()) {
            sb.append("\nENTRY OPTIONS (same stop and target)\n");
            for (Breakdown.EntryOption o : b.options) {
                sb.append(o.chosen ? "> " : "  ").append(o.name).append(": ").append(f(o.entry)).append("  risk ").append(f(o.riskPts))
                    .append("  ").append(d1(o.rr1)).append("R");
                if (o.rr2 != null) sb.append(" / ").append(d1(o.rr2)).append("R");
                sb.append(o.chosen ? "   <- used\n" : "\n");
            }
            sb.append("A deeper entry risks less but fills less often. The backtest picks the style that earned most.\n");
        }

        sb.append("\nCONFIDENCE (past results, not a prediction)\n");
        Calibration.Estimate c = b.confidence;
        if (c == null) sb.append("Not calibrated yet. Open the Backtest tab and run it (or leave scanning on and it will calibrate itself).\n");
        else {
            sb.append("Trades like this (").append(c.basis).append(") won ").append(Math.round(c.pct)).append("% of ").append(c.n)
                .append(" past trades. Likely range ").append(Math.round(c.lo)).append("-").append(Math.round(c.hi)).append("%.\n");
            sb.append("At ").append(d1(L.rr1)).append("R you only need ").append(Math.round(c.breakEven)).append("% to break even.").append(c.reliable ? "" : "\n");
            if (c.reliable) sb.append(c.lo > c.breakEven ? " Past results beat that." : c.hi < c.breakEven ? " Past results did NOT beat that." : " Past results were close to that, so no clear edge.").append("\n");
            else sb.append("Only ").append(c.n).append(" similar trades so far: too few to trust. Treat the number as noise.\n");
            sb.append("Volatility indices are simulated, so past results may not repeat.\n");
        }

        sb.append("\nWHY THIS ENTRY\n");
        for (int i = 0; i < b.story.size(); i++) sb.append(i + 1).append(". ").append(b.story.get(i)).append("\n");
        sb.append("\nVALIDATION - RULES (all must pass)\n");
        for (Breakdown.Factor x : b.factors) if (!"quality".equals(x.group)) sb.append(mark(x)).append(" ").append(x.label).append("\n");
        sb.append("\nVALIDATION - QUALITY FACTORS\n");
        for (Breakdown.Factor x : b.factors) if ("quality".equals(x.group)) sb.append(mark(x)).append(" ").append(x.label).append("\n");
        sb.append("\nWATCH OUT\n");
        for (String r : b.risks) sb.append("- ").append(r).append("\n");
        sb.append("\nWHERE THE TRADE IS INVALIDATED\n");
        for (String r : b.invalidation) sb.append("- ").append(r).append("\n");
        sb.append("\nPrice now: ").append(f(price)).append("\nGrade = share of quality factors met. Not a win probability.");
        return sb.toString();
    }
}
