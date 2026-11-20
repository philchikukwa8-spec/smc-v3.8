package com.smcscanner.app;

import android.content.Context;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.mie.Row;
import com.smcscanner.engine.mie.WalkForward;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs the tests chosen on the Strategy Lab screen and stores the rows (source "replay"). */
public final class LabRunner {
    private LabRunner() {}

    public static final class Options {
        public boolean smc = true, pa = true, fib = false, f1h = true, f30 = true, f15 = true, replace = true;
        /** history multiplier: 1 = standard, 2 = longer (twice as much, slower) */
        public int mult = 1;
        /** symbols to test; empty means all */
        public Set<String> markets = new HashSet<>();
    }

    public interface Progress { void step(int done, int total, String what); }

    /** returns a short plain summary of what was run (details are shown by the results view) */
    public static String run(Context c, DerivClient client, Options o, Progress p) {
        Scan.Config cfg = Store.config(c);
        List<Scan.Market> ms = new ArrayList<>();
        for (Scan.Market m : cfg.markets) if (o.markets.isEmpty() || o.markets.contains(m.symbol)) ms.add(m);
        int per = 0;
        if (o.smc) per += (o.f1h ? 1 : 0) + (o.f30 ? 1 : 0);
        if (o.pa) per += (o.f1h ? 1 : 0) + (o.f30 ? 1 : 0) + (o.f15 ? 1 : 0);
        if (o.fib) per += 1;
        int total = Math.max(1, per * ms.size()), done = 0;
        SqliteResearchStore store = SqliteResearchStore.get(c);
        StringBuilder notes = new StringBuilder();
        int failed = 0;
        for (Scan.Market m : ms) {
            Map<String, Candle[]> cache = new HashMap<>();
            if (o.smc) {
                for (int tier = 0; tier <= 1; tier++) {
                    if (tier == 0 && !o.f1h) continue;
                    if (tier == 1 && !o.f30) continue;
                    String frame = tier == 0 ? "1H" : "30m";
                    p.step(done, total, m.label + ": SMC " + frame + " cascade");
                    try {
                        if (o.replace) clear(store, "SMC", m.symbol, frame);
                        WalkForward.Report rep = MieRunner.walkForward(c, client, m, 4, tier, o.mult, cache);
                        notes.append(m.label).append(" SMC ").append(frame).append(": ").append(rep.rows.size()).append(" setups\n");
                    } catch (Throwable t) { failed++; notes.append(m.label).append(" SMC ").append(frame).append(" failed: ").append(t.getMessage()).append('\n'); }
                    done++;
                }
            }
            if (o.pa) {
                String[] tfs = {"1H", "30m", "15m"};
                boolean[] on = {o.f1h, o.f30, o.f15};
                for (int i = 0; i < 3; i++) {
                    if (!on[i]) continue;
                    p.step(done, total, m.label + ": price action " + tfs[i]);
                    try {
                        if (o.replace) clear(store, "PA", m.symbol, tfs[i]);
                        PaRunner.replayFrames(c, client, m, i == 0, i == 1, i == 2, o.mult, cache, null);
                        notes.append(m.label).append(" price action ").append(tfs[i]).append(": done\n");
                    } catch (Throwable t) { failed++; notes.append(m.label).append(" price action ").append(tfs[i]).append(" failed: ").append(t.getMessage()).append('\n'); }
                    done++;
                }
            }
        }
        if (o.fib) {
            for (Scan.Market m : ms) {
                p.step(done, total, m.label + ": Fib pullback");
                try {
                    if (o.replace) clear(store, "FIB", m.symbol, "15m");
                    notes.append(FibRunner.replay(c, client, m, o.mult, new HashMap<String, Candle[]>())).append('\n');
                } catch (Throwable t) { failed++; notes.append(m.label).append(" Fib failed: ").append(t.getMessage()).append('\n'); }
                done++;
            }
        }
        p.step(total, total, "Finished");
        if (o.smc && o.f15) notes.append("SMC 15m cascade is not replayable (Deriv keeps only a few days of 1m candles); it learns from live signals.\n");
        return (failed == 0 ? "Test finished." : "Test finished with " + failed + " step(s) failed (usually a connection drop; run again).") + "\n" + notes.toString().trim();
    }

    private static void clear(SqliteResearchStore store, String mode, String symbol, String frame) {
        List<String> ids = new ArrayList<>();
        for (Row r : store.bySource("replay")) if (r.mode.equals(mode) && r.symbol.equals(symbol) && r.frame.equals(frame)) ids.add(r.id);
        if (!ids.isEmpty()) store.deleteRows(ids);
    }
}
