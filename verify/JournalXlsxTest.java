import com.smcscanner.engine.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Builds a sample journal workbook from a realistic synthetic ledger and writes the expected numbers for checking against Excel's own recalculation. */
public class JournalXlsxTest {
    static final String[] MARKETS = {"Volatility 75", "Volatility 50", "Volatility 25"};
    static final String[] SYMBOLS = {"R_75", "R_50", "R_25"};
    static final String[][] PATHS = {{"1D", "1H", "5m > 1m"}, {"4H", "1H", "5m > 1m"}, {"1H", "15m", "5m > 1m"}, {"15m", "5m", "1m"}, {"5m", "1m", "1m"}};

    public static void main(String[] a) throws Exception {
        String tplDir = a[0], out = a[1], expected = a[2];
        int n = a.length > 3 ? Integer.parseInt(a[3]) : 420;
        Random rnd = new Random(42);
        TimeZone tz = TimeZone.getTimeZone("Africa/Harare");
        long start = 1_764_547_200L; // 2025-12-01 00:00 UTC
        List<Ledger.Rec> recs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Ledger.Rec r = new Ledger.Rec();
            int m = rnd.nextInt(3), p = rnd.nextInt(5);
            r.id = "k" + i; r.symbol = SYMBOLS[m]; r.market = MARKETS[m];
            r.time = start + (long) (rnd.nextDouble() * 150 * 86400);
            r.side = rnd.nextBoolean() ? "BUY" : "SELL";
            r.ideaTf = PATHS[p][0];
            String[] tail = PATHS[p][2].split(" > ");
            r.entryTf = rnd.nextInt(6) == 0 ? PATHS[p][1] : tail[tail.length - 1];
            r.path = Ledger.pathOf(r.ideaTf, PATHS[p][1], r.entryTf);
            r.structure = rnd.nextBoolean() ? "BOS" : "CHoCH";
            r.zone = rnd.nextInt(3) == 0 ? "OB" : "FVG";
            r.mode = rnd.nextInt(4) == 0 ? "Sniper" : "Normal";
            r.grade = new String[]{"A", "B", "C"}[rnd.nextInt(3)];
            r.maxScore = 7; r.score = 4 + rnd.nextInt(4);
            r.confPct = rnd.nextInt(3) == 0 ? -1 : 35 + rnd.nextInt(55);
            r.confN = r.confPct < 0 ? rnd.nextInt(20) : 30 + rnd.nextInt(200);
            double px = new double[]{1285, 402, 118}[m] * (0.9 + rnd.nextDouble() * 0.2);
            double risk = px * (0.0005 + rnd.nextDouble() * 0.002);
            r.rr = Math.round((2 + rnd.nextDouble() * 3) * 10) / 10.0;
            r.entry = px; r.stop = r.side.equals("BUY") ? px - risk : px + risk; r.tp = r.side.equals("BUY") ? px + risk * r.rr : px - risk * r.rr;
            r.riskMoney = 10;
            double u = rnd.nextDouble();
            // better grades win a bit more, so the grade table has something to show
            double pw = 0.38 + (r.grade.equals("A") ? 0.12 : r.grade.equals("B") ? 0.04 : -0.03) + (r.mode.equals("Sniper") ? 0.08 : 0);
            if (u < 0.05) { r.outcome = Ledger.OPEN; }
            else if (u < 0.11) { r.outcome = Ledger.NO_FILL; r.resolvedAt = r.time + 3600; }
            else if (u < 0.15) { r.outcome = Ledger.EXPIRED; r.resolvedAt = r.time + 172800; }
            else if (rnd.nextDouble() < pw) { r.outcome = Ledger.WIN; r.resultR = r.rr; r.resolvedAt = r.time + 600 + rnd.nextInt(40000); }
            else { r.outcome = Ledger.LOSS; r.resultR = -1; r.resolvedAt = r.time + 300 + rnd.nextInt(20000); }
            r.filled = !r.outcome.equals(Ledger.NO_FILL);
            recs.add(r);
        }
        List<Manage.Trade> trades = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Manage.Trade t = new Manage.Trade();
            t.id = "m" + i; t.label = MARKETS[i % 3]; t.symbol = SYMBOLS[i % 3]; t.side = i % 2 == 0 ? "BUY" : "SELL"; t.ideaTf = "4H"; t.entryTf = "1m";
            t.entry = 100 + i; t.stop0 = t.entry - 1; t.tp1 = t.entry + 3; t.startedAt = start + i * 86400L * 9;
            t.riskMoney = 10;
            if (i < 10) { t.status = "closed"; t.lastEpoch = t.startedAt + 7200; t.resultR = i % 3 == 0 ? -1 : i % 3 == 1 ? 2.4 : 0.0; t.exitPrice = t.entry + t.resultR; t.maxR = Math.max(0, t.resultR); t.closeReason = i % 3 == 0 ? "Stop hit" : "Target reached"; }
            else t.status = "open";
            trades.add(t);
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(tplDir + "/tpl.properties")) { p.load(in); }
        try (InputStream tpl = new FileInputStream(tplDir + "/tpl.xlsx"); OutputStream os = new FileOutputStream(out)) {
            XlsxJournal.write(tpl, p, recs, trades, tz, os);
        }
        // expected numbers (what Excel must also compute)
        Ledger.Tally t = Ledger.tally(recs);
        StringBuilder sb = new StringBuilder();
        sb.append("alerts=").append(t.total).append("\nwins=").append(t.wins).append("\nlosses=").append(t.losses).append("\nopen=").append(t.open).append("\nother=").append(t.other)
          .append("\ntotalR=").append(String.format(Locale.US, "%.6f", t.totalR)).append("\n");
        for (String tf : new String[]{"1D", "4H", "1H", "15m", "5m"}) {
            int w = 0, l = 0; double rr = 0, c = 0;
            for (Ledger.Rec r : recs) if (r.ideaTf.equals(tf)) { c++; if (r.outcome.equals(Ledger.WIN)) { w++; rr += r.resultR; } if (r.outcome.equals(Ledger.LOSS)) { l++; rr += r.resultR; } }
            sb.append("idea.").append(tf).append("=").append((int) c).append(",").append(w).append(",").append(l).append(",").append(String.format(Locale.US, "%.6f", rr)).append("\n");
        }
        for (String g : new String[]{"A", "B", "C"}) {
            int w = 0, l = 0;
            for (Ledger.Rec r : recs) if (r.grade.equals(g)) { if (r.outcome.equals(Ledger.WIN)) w++; if (r.outcome.equals(Ledger.LOSS)) l++; }
            sb.append("grade.").append(g).append("=").append(w).append(",").append(l).append("\n");
        }
        // per calendar month (phone time)
        Map<String, double[]> months = new TreeMap<>();
        java.time.ZoneId z = tz.toZoneId();
        for (Ledger.Rec r : recs) {
            java.time.ZonedDateTime zt = java.time.Instant.ofEpochSecond(r.time).atZone(z);
            String key = String.format("%d-%02d", zt.getYear(), zt.getMonthValue());
            double[] v = months.computeIfAbsent(key, k -> new double[4]);
            v[0]++; if (r.outcome.equals(Ledger.WIN)) v[1]++; if (r.outcome.equals(Ledger.LOSS)) v[2]++; if (r.decided()) v[3] += r.resultR;
        }
        for (Map.Entry<String, double[]> e : months.entrySet()) sb.append("month.").append(e.getKey()).append("=").append((int) e.getValue()[0]).append(",").append((int) e.getValue()[1]).append(",").append((int) e.getValue()[2]).append(",").append(String.format(Locale.US, "%.6f", e.getValue()[3])).append("\n");
        int sniper = 0; for (Ledger.Rec r : recs) if (r.mode.equals("Sniper")) sniper++;
        sb.append("sniper=").append(sniper).append("\n");
        Files.write(Paths.get(expected), sb.toString().getBytes("UTF-8"));
        System.out.println("wrote " + out + " with " + recs.size() + " alerts, " + trades.size() + " trades");
    }
}
