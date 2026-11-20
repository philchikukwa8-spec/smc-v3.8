import com.smcscanner.engine.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Checks the Java engine against setups exported from the tested TypeScript engine. */
public class DetectParity {
    static Candle[] readSeries(Path p) throws IOException {
        List<Candle> out = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            if (line.isEmpty()) continue;
            String[] t = line.split(",");
            out.add(new Candle(Long.parseLong(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4])));
        }
        return out.toArray(new Candle[0]);
    }
    static int failures = 0;
    static void eq(String what, double a, double b) {
        if (!(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b)))) { failures++; if (failures < 15) System.out.println("  MISMATCH " + what + ": java=" + a + " ts=" + b); }
    }
    static void eqs(String what, Object a, Object b) {
        if (!String.valueOf(a).equals(String.valueOf(b))) { failures++; if (failures < 15) System.out.println("  MISMATCH " + what + ": java=" + a + " ts=" + b); }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        int totalExpected = 0, totalFound = 0;
        for (int k = 0; k < 6; k++) {
            Candle[] c = readSeries(dir.resolve("series_" + k + ".csv"));
            Map<Integer, String[]> exp = new HashMap<>();
            for (String line : Files.readAllLines(dir.resolve("expected_" + k + ".csv"))) {
                if (line.isEmpty()) continue;
                String[] t = line.split(",");
                exp.put(Integer.parseInt(t[0]), t);
            }
            SmcConfig cfg = new SmcConfig();
            Candle[] m = Smc.mirror(c);
            int found = 0;
            for (int i = 0; i < c.length; i++) {
                Setup s = Smc.detectAt(c, m, i, cfg);
                String[] e = exp.get(i);
                if (s == null && e == null) continue;
                if (s == null || e == null) { failures++; if (failures < 15) System.out.println("  PRESENCE series " + k + " idx " + i + " java=" + (s != null) + " ts=" + (e != null)); continue; }
                found++;
                String w = "s" + k + "@" + i + " ";
                eqs(w + "side", s.side, e[1]);
                eq(w + "entry", s.entry, Double.parseDouble(e[2]));
                eq(w + "sl", s.sl, Double.parseDouble(e[3]));
                eq(w + "tp", s.tp, Double.parseDouble(e[4]));
                eq(w + "rr", s.rr, Double.parseDouble(e[5]));
                eq(w + "score", s.score, Double.parseDouble(e[6]));
                eq(w + "zoneLow", s.zoneLow, Double.parseDouble(e[7]));
                eq(w + "zoneHigh", s.zoneHigh, Double.parseDouble(e[8]));
                eqs(w + "structure", s.structure, e[9]);
                eqs(w + "zoneType", s.zoneType, e[10]);
                eqs(w + "tpType", s.tpType, e[11]);
                eqs(w + "discount", s.discount, e[12]);
                eqs(w + "majorSweep", s.majorSweep, e[13]);
                eq(w + "dispRatio", s.dispRatio, Double.parseDouble(e[14]));
                eqs(w + "overlap", s.overlap, e[15]);
                eq(w + "eq", s.eq, Double.parseDouble(e[16]));
                eq(w + "sweepExtreme", s.sweepExtreme, Double.parseDouble(e[17]));
                eq(w + "sweepEpoch", s.sweepEpoch, Double.parseDouble(e[18]));
                eq(w + "brokenLevel", s.brokenLevel, Double.parseDouble(e[19]));
                eq(w + "sweepLevel", s.sweepLevel, Double.parseDouble(e[20]));
                eq(w + "rangeHigh", s.rangeHigh, Double.parseDouble(e[21]));
                eq(w + "rangeLow", s.rangeLow, Double.parseDouble(e[22]));
                eq(w + "avgRange", s.avgRange, Double.parseDouble(e[23]));
                eqs(w + "obLow", s.obLow == null ? "null" : String.valueOf(s.obLow), e[24]);
                if (s.obLow != null) { eq(w + "obLow", s.obLow, Double.parseDouble(e[24])); eq(w + "obHigh", s.obHigh, Double.parseDouble(e[25])); }
                eq(w + "triggerEpoch", s.triggerEpoch, Double.parseDouble(e[26]));
                eqs(w + "id", s.id, e[27]);
            }
            totalExpected += exp.size();
            totalFound += found;
            System.out.println("series " + k + ": " + found + "/" + exp.size() + " setups matched");
        }
        System.out.println(failures == 0 ? "PARITY OK: " + totalFound + " of " + totalExpected + " setups identical" : "PARITY FAILED: " + failures + " mismatches");
        System.exit(failures == 0 ? 0 : 1);
    }
}
