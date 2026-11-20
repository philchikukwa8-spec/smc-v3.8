package com.smcscanner.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Looks for major chart patterns on the 1H, 4H and 1D charts and says which ones are new since the last look. Pure Java. */
public final class PatternWatch {
    private PatternWatch() {}

    public static final class Alert {
        public final String id, title, body, text;
        public Alert(String id, String title, String body, String text) { this.id = id; this.title = title; this.body = body; this.text = text; }
    }

    private static final String[] TF = {"1H", "4H", "1D"};
    private static final long[] GR = {3600, 14400, 86400};

    /** which patterns are worth an alert, per timeframe, so the phone does not buzz all day */
    public static boolean notifiable(Patterns.Pattern p, String tf) {
        boolean strong = p.name.startsWith("Double") || p.name.contains("Head and shoulders") || p.name.startsWith("Ascending") || p.name.startsWith("Descending");
        if (p.status.equals("confirmed")) return strong || tf.equals("4H") || tf.equals("1D");
        // still forming: only the clearest shapes, and only on the bigger charts
        return (p.name.startsWith("Double") || p.name.contains("Head and shoulders")) && (tf.equals("4H") || tf.equals("1D"));
    }

    public static String id(String market, String tf, Patterns.Pattern p) {
        return market + "|" + tf + "|" + p.name + "|" + p.endEpoch + "|" + p.status;
    }

    public static Alert make(String marketLabel, String tf, Patterns.Pattern p, String id, double price) {
        p.tf = tf;
        String dir = p.bias.equals("BUY") ? "bullish" : p.bias.equals("SELL") ? "bearish" : "either way";
        String title = p.name + " " + p.status + " - " + marketLabel + " " + tf;
        String body = (p.status.equals("confirmed") ? "Broke " + (p.bias.equals("BUY") ? "up" : p.bias.equals("SELL") ? "down" : "out") + " through " : "Line to watch ") + Fmt.f(p.level)
            + " | " + dir;
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");
        sb.append("PATTERN\n").append(p.detail).append("\n\n");
        sb.append("What it points to\n");
        if (p.bias.equals("NEUTRAL")) sb.append("It can break either way. Wait for a candle to close outside the lines.\n");
        else sb.append("A ").append(dir).append(" bias. Rule-of-thumb target ").append(Fmt.f(p.target)).append(" (the height of the pattern measured from the line).\n");
        sb.append("Price now ").append(Fmt.f(price)).append(".\n\n");
        sb.append("How to use it\n");
        if (p.bias.equals("BUY") || p.bias.equals("SELL")) sb.append("Treat it as a reason to look for a ").append(p.bias.equals("BUY") ? "BUY" : "SELL")
            .append(" idea on the lower timeframes. The app's alerts that agree with it say so in the confluence list.\n");
        sb.append("A pattern is a shape in price, not a prediction: many fail. Open the chart and tap Patterns to see the lines.");
        return new Alert(id, title, body, sb.toString());
    }

    /** New alerts since `seen` (ids already sent). Adds the new ids to `seen`. Errors on one chart do not stop the others. */
    public static List<Alert> check(Scan.Candles client, List<Scan.Market> markets, long now, Set<String> seen) {
        List<Alert> out = new ArrayList<>();
        for (Scan.Market m : markets) {
            for (int i = 0; i < TF.length; i++) {
                try {
                    Candle[] closed = Smc.closedOnly(client.get(m.symbol, GR[i], 300), GR[i], now);
                    if (closed.length < 60) continue;
                    double price = closed[closed.length - 1].close;
                    for (Patterns.Pattern p : Patterns.detect(closed)) {
                        if (!notifiable(p, TF[i])) continue;
                        String id = id(m.symbol, TF[i], p);
                        if (seen.contains(id)) continue;
                        // a confirmed pattern replaces its own "forming" alert, so mark that one as seen too
                        seen.add(id);
                        out.add(make(m.label, TF[i], p, id, price));
                    }
                } catch (Exception e) { /* try again next time */ }
            }
        }
        return out;
    }
}
