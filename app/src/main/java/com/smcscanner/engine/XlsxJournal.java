package com.smcscanner.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes the Excel journal: takes the ready-made workbook (dashboard, charts, reports, search) and fills in its Log and MyTrades
 * sheets. Everything else in the workbook is formulas, so Excel recalculates it when the file is opened.
 * Pure Java (java.util.zip), so there are no extra libraries to install.
 */
public final class XlsxJournal {
    private XlsxJournal() {}

    private static final String[] LOG_COLS = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "AA", "AB", "AC", "AD", "AE"};

    /** a cell value: number, text or empty */
    private static final class Row {
        final StringBuilder sb = new StringBuilder();
        final int n;
        Row(int n) { this.n = n; sb.append("<row r=\"").append(n).append("\">"); }
        void num(String col, String style, double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return;
            sb.append("<c r=\"").append(col).append(n).append("\" s=\"").append(style).append("\"><v>").append(fmtNum(v)).append("</v></c>");
        }
        void str(String col, String style, String v) {
            if (v == null || v.isEmpty()) return;
            sb.append("<c r=\"").append(col).append(n).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
        }
        void formula(String col, String style, String f) {
            sb.append("<c r=\"").append(col).append(n).append("\" s=\"").append(style).append("\"><f>").append(esc(f)).append("</f></c>");
        }
        String done() { return sb.append("</row>").toString(); }
    }

    static String fmtNum(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.US, "%.10g", v).replaceAll("0+(e|$)", "$1").replaceAll("\\.(e|$)", "$1");
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') b.append("&amp;");
            else if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '"') b.append("&quot;");
            else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            else b.append(c);
        }
        return b.toString();
    }

    /** Excel's date number for a calendar date */
    static double serial(LocalDate d) { return d.toEpochDay() + 25569; }

    static String outcomeLabel(Ledger.Rec r) {
        switch (r.outcome) {
            case Ledger.WIN: return "Win";
            case Ledger.LOSS: return "Loss";
            case Ledger.NO_FILL: return "No fill";
            case Ledger.EXPIRED: return "Expired";
            default: return "Open";
        }
    }

    static String style(Properties p, String key) {
        String s = p.getProperty(key);
        if (s == null) throw new IllegalStateException("Template is missing style " + key);
        return s;
    }

    private static String logRows(Properties p, List<Ledger.Rec> all, ZoneId zone) {
        List<Ledger.Rec> recs = new ArrayList<>(all);
        Collections.sort(recs, new Comparator<Ledger.Rec>() {
            @Override public int compare(Ledger.Rec a, Ledger.Rec b) { return Long.compare(a.time, b.time); }
        });
        if (recs.size() > Ledger.MAX_ROWS) recs = new ArrayList<>(recs.subList(recs.size() - Ledger.MAX_ROWS, recs.size()));
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE", Locale.US);
        DateTimeFormatter month = DateTimeFormatter.ofPattern("yyyy-MM", Locale.US);
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);
        String matchTpl = p.getProperty("matchFormula");
        StringBuilder out = new StringBuilder();
        double equity = 0;
        int i = 0;
        for (Ledger.Rec r : recs) {
            int rowNo = 2 + i;
            i++;
            ZonedDateTime t = Instant.ofEpochSecond(r.time).atZone(zone);
            Row row = new Row(rowNo);
            row.num("A", style(p, "log.A"), i);
            row.num("B", style(p, "log.B"), serial(t.toLocalDate()));
            row.str("C", style(p, "log.C"), t.format(hm));
            row.str("D", style(p, "log.D"), t.format(day));
            row.num("E", style(p, "log.E"), t.getHour());
            row.str("F", style(p, "log.F"), String.format(Locale.US, "%d-W%02d", t.get(IsoFields.WEEK_BASED_YEAR), t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
            row.str("G", style(p, "log.G"), t.format(month));
            row.str("H", style(p, "log.H"), r.market);
            row.str("I", style(p, "log.I"), r.side);
            row.str("J", style(p, "log.J"), r.ideaTf);
            row.str("K", style(p, "log.K"), r.entryTf);
            row.str("L", style(p, "log.L"), r.path);
            row.str("M", style(p, "log.M"), r.structure);
            row.str("N", style(p, "log.N"), r.zone);
            row.str("O", style(p, "log.O"), r.mode);
            row.str("P", style(p, "log.P"), r.grade);
            row.num("Q", style(p, "log.Q"), r.score);
            if (r.confPct >= 0) row.num("R", style(p, "log.R"), r.confPct / 100.0);
            row.str("S", style(p, "log.S"), Ledger.band(r.confPct));
            row.num("T", style(p, "log.T"), r.entry);
            row.num("U", style(p, "log.U"), r.stop);
            row.num("V", style(p, "log.V"), r.tp);
            row.num("W", style(p, "log.W"), r.rr);
            row.num("X", style(p, "log.X"), r.riskMoney);
            row.str("Y", style(p, "log.Y"), outcomeLabel(r));
            if (r.decided()) {
                row.num("Z", style(p, "log.Z"), r.resultR);
                row.num("AA", style(p, "log.AA"), r.resultR * r.riskMoney);
                equity += r.resultR;
            }
            row.num("AB", style(p, "log.AB"), Math.round(equity * 1e6) / 1e6);
            if (r.resolvedAt > 0 && !r.outcome.equals(Ledger.OPEN)) row.str("AC", style(p, "log.AC"), Instant.ofEpochSecond(r.resolvedAt).atZone(zone).format(stamp));
            row.formula("AD", style(p, "log.AD"), matchTpl.replace("H2=", "H" + rowNo + "=").replace("I2=", "I" + rowNo + "=").replace("J2=", "J" + rowNo + "=")
                .replace("K2=", "K" + rowNo + "=").replace("M2=", "M" + rowNo + "=").replace("N2=", "N" + rowNo + "=").replace("O2=", "O" + rowNo + "=")
                .replace("P2=", "P" + rowNo + "=").replace("Y2=", "Y" + rowNo + "=").replace("G2=", "G" + rowNo + "="));
            // running count of matches: the Search sheet finds the k-th match with MATCH(k, ...)
            row.formula("AE", style(p, "log.AE"), rowNo == 2 ? "AD2" : "AE" + (rowNo - 1) + "+AD" + rowNo);
            out.append(row.done());
        }
        return out.toString();
    }

    private static String tradeRows(Properties p, List<Manage.Trade> all, ZoneId zone) {
        List<Manage.Trade> ts = new ArrayList<>(all);
        Collections.sort(ts, new Comparator<Manage.Trade>() {
            @Override public int compare(Manage.Trade a, Manage.Trade b) { return Long.compare(a.startedAt, b.startedAt); }
        });
        if (ts.size() > Ledger.MAX_ROWS) ts = new ArrayList<>(ts.subList(ts.size() - Ledger.MAX_ROWS, ts.size()));
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (Manage.Trade t : ts) {
            i++;
            Row row = new Row(1 + i);
            boolean closed = t.status.equals("closed");
            row.num("A", style(p, "mt.A"), i);
            if (closed) row.num("B", style(p, "mt.B"), serial(Instant.ofEpochSecond(t.lastEpoch).atZone(zone).toLocalDate()));
            row.str("C", style(p, "mt.C"), t.label);
            row.str("D", style(p, "mt.D"), t.side);
            row.str("E", style(p, "mt.E"), t.ideaTf);
            row.str("F", style(p, "mt.F"), t.entryTf);
            row.num("G", style(p, "mt.G"), t.entry);
            row.num("H", style(p, "mt.H"), t.stop0);
            row.num("I", style(p, "mt.I"), t.tp1);
            if (closed) {
                row.num("J", style(p, "mt.J"), t.exitPrice);
                row.num("K", style(p, "mt.K"), t.resultR);
                row.num("L", style(p, "mt.L"), t.resultR * t.riskMoney);
            }
            row.num("M", style(p, "mt.M"), t.maxR);
            row.str("N", style(p, "mt.N"), closed ? t.closeReason : "");
            row.str("O", style(p, "mt.O"), closed ? "Closed" : "Open");
            out.append(row.done());
        }
        return out.toString();
    }

    private static byte[] inject(byte[] xml, String rows, String dimension) {
        String s = new String(xml, StandardCharsets.UTF_8);
        int end = s.indexOf("</sheetData>");
        if (end < 0) {
            int self = s.indexOf("<sheetData/>");
            if (self < 0) throw new IllegalStateException("Template sheet has no sheetData");
            s = s.substring(0, self) + "<sheetData>" + rows + "</sheetData>" + s.substring(self + "<sheetData/>".length());
        } else {
            s = s.substring(0, end) + rows + s.substring(end);
        }
        s = s.replaceFirst("<dimension ref=\"[^\"]*\"\\s*/>", "<dimension ref=\"" + dimension + "\"/>");
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes the finished workbook to out.
     * @param template the shipped workbook (journal_template.xlsx)
     * @param p the shipped journal_template.properties (sheet files and cell style numbers)
     */
    public static void write(InputStream template, Properties p, List<Ledger.Rec> recs, List<Manage.Trade> trades, TimeZone tz, OutputStream out) throws IOException {
        ZoneId zone = tz.toZoneId();
        String logRows = logRows(p, recs, zone);
        String tradeRows = tradeRows(p, trades, zone);
        int nLog = Math.min(recs.size(), Ledger.MAX_ROWS), nTr = Math.min(trades.size(), Ledger.MAX_ROWS);
        String logFile = p.getProperty("logSheet"), trFile = p.getProperty("myTradesSheet");
        ZipInputStream zin = new ZipInputStream(template);
        ZipOutputStream zout = new ZipOutputStream(out);
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                if (e.getName().equals(logFile)) data = inject(data, logRows, "A1:AE" + (1 + nLog));
                else if (e.getName().equals(trFile)) data = inject(data, tradeRows, "A1:O" + (1 + nTr));
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
            zout.finish();
        } finally {
            zin.close();
        }
    }
}
