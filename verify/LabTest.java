import com.smcscanner.engine.*;
import com.smcscanner.engine.mie.*;
import com.smcscanner.engine.pa.*;
import com.smcscanner.chart.*;
import java.nio.file.*;
import java.util.*;

public class LabTest {
    static int checks, fails;
    static void ok(String m, boolean c) { checks++; if (!c) { fails++; if (fails < 15) System.out.println("FAIL: " + m); } }
    public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]);
        List<Row> rows = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Candle[] m5 = BacktestParity.read(d.resolve("ltf_" + k + ".csv"));
            Candle[] m30 = ZoomTest.agg(m5, 6), m15 = ZoomTest.agg(m5, 3), h1 = ZoomTest.agg(m5, 12), h4 = ZoomTest.agg(m5, 48);
            rows.addAll(PaReplay.run("S" + k, "Sim " + k, h4, h1, 4, PaEngine.VERSION).rows);
            rows.addAll(PaReplay.runFrame("S" + k, "Sim " + k, "30m", 1800, m30, h1, 3600, "1H", h4, h1, m30, 14400, 3600, 1800, 4, PaEngine.VERSION).rows);
            WalkForward.Data wd = new WalkForward.Data(); wd.symbol = "S" + k; wd.label = "Sim " + k; wd.m15 = m15; wd.m30 = m30; wd.h1 = h1; wd.h4 = h4; wd.m5 = m5;
            rows.addAll(WalkForward.run(wd, "t", 8, 400, 0).rows);
        }
        ok("have rows", rows.size() > 50);
        Lab.Filter f = new Lab.Filter();
        List<Row> all = Lab.apply(rows, f);
        Kpi k0 = Kpi.compute(all);
        Lab.Summary s0 = Lab.summarize(all);
        ok("summary kpi matches Kpi", s0.kpi.completed == k0.completed && Math.abs(s0.kpi.netR - k0.netR) < 1e-9);
        ok("curve length = decided trades", s0.curve.length == k0.completed);
        if (s0.curve.length > 0) ok("curve ends at net R", Math.abs(s0.curve[s0.curve.length - 1] - k0.netR) < 1e-6);
        // every filter only narrows, and parts add up
        f.mode = "SMC"; int smc = Lab.apply(rows, f).size();
        f.mode = "PA"; int pa = Lab.apply(rows, f).size();
        ok("modes partition rows", smc + pa == all.size());
        f.mode = "ALL"; f.side = "BUY"; int b = Lab.apply(rows, f).size();
        f.side = "SELL"; int sl = Lab.apply(rows, f).size();
        ok("sides partition", b + sl == all.size());
        f.side = "ALL"; f.market = "S0"; int m0 = Lab.apply(rows, f).size();
        ok("market narrows", m0 <= all.size() && m0 > 0);
        for (Row r : Lab.apply(rows, f)) ok("market filter exact", r.symbol.equals("S0"));
        f.market = "ALL"; f.frame = "30m";
        for (Row r : Lab.apply(rows, f)) ok("frame filter exact", r.frame.equals("30m"));
        f.frame = "ALL"; f.data = "UNSEEN"; int un = Lab.apply(rows, f).size();
        f.data = "IN"; int in = Lab.apply(rows, f).size();
        ok("unseen+in-sample <= all", un + in <= all.size());
        for (Row r : Lab.apply(rows, f)) ok("in-sample only", r.sample.equals("in-sample"));
        f.data = "ALL";
        ok("describe not empty", !f.describe().isEmpty());
        ok("empty summary safe", Lab.summarize(new ArrayList<Row>()).kpi.completed == 0);
        ok("share text", Lab.shareText(f, s0, all).length() > 40);
        ok("rank safe", Lab.rank(all, 10) != null);
        // chart
        final double[] cv = s0.curve;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(720, 380, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        EquityChart.draw(new ChartShot.J2D(g2), 720, 380, cv, 2f, true);
        g2.dispose();
        javax.imageio.ImageIO.write(img, "png", new java.io.File(a[1]));
        System.out.println("LabTest rows=" + rows.size() + " decided=" + k0.completed + " checks=" + checks + " fails=" + fails);
    }
}
