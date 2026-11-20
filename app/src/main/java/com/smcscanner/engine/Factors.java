package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.List;

/** Which quality factors actually improved results on the tested history. */
public final class Factors {
    private Factors() {}

    public static final class Row {
        public String key, label;
        public int withN, withoutN;
        public double withAvgR, withWin, withoutAvgR, withoutWin, edge;
        /** both groups have enough decided trades to mean something */
        public boolean enough;
    }

    public static final class GradeRow {
        public String grade;
        public int n;
        public double avgR, win;
    }

    public static final class Report {
        public List<Row> factors = new ArrayList<>();
        public List<GradeRow> grades = new ArrayList<>();
    }

    public static Report report(List<Mtf.FactorTrade> trades, int minN) {
        Report rep = new Report();
        for (String key : Breakdown.QUALITY_KEYS) {
            List<Backtest.Trade> w = new ArrayList<>(), wo = new ArrayList<>();
            String label = key;
            for (Mtf.FactorTrade t : trades) {
                if (!t.trade.decided()) continue;
                Breakdown.Factor f = t.breakdown.factor(key);
                if (f == null) continue;
                label = f.label;
                if (f.pass != null) { if (f.pass.booleanValue()) w.add(t.trade); else wo.add(t.trade); }
            }
            Backtest.Result a = Backtest.summarize(w), b = Backtest.summarize(wo);
            Row r = new Row();
            r.key = key; r.label = label;
            r.withN = a.filled; r.withAvgR = a.avgR; r.withWin = a.winRate;
            r.withoutN = b.filled; r.withoutAvgR = b.avgR; r.withoutWin = b.winRate;
            r.edge = a.avgR - b.avgR;
            r.enough = a.filled >= minN && b.filled >= minN;
            rep.factors.add(r);
        }
        for (String g : new String[]{"A", "B", "C"}) {
            List<Backtest.Trade> l = new ArrayList<>();
            for (Mtf.FactorTrade t : trades) if (t.breakdown.grade.equals(g)) l.add(t.trade);
            Backtest.Result r = Backtest.summarize(l);
            GradeRow gr = new GradeRow();
            gr.grade = g; gr.n = r.filled; gr.avgR = r.avgR; gr.win = r.winRate;
            rep.grades.add(gr);
        }
        return rep;
    }
}
