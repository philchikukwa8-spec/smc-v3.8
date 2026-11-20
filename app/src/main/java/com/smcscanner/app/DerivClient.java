package com.smcscanner.app;

import com.smcscanner.engine.Candle;
import com.smcscanner.engine.Scan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Deriv public market-data connection (no login, no token). One socket, one request at a time per call,
 * every request has a timeout, and it reconnects by itself on the next request if the socket dropped.
 */
public class DerivClient implements Scan.Candles {
    /** Deriv's new public market-data socket first (no login), the older one as a fallback. The one that works is remembered. */
    public static final String[] URLS = {
        "wss://api.derivws.com/trading/v1/options/ws/public",
        "wss://ws.derivws.com/websockets/v3?app_id=1089"
    };
    private volatile int urlIndex = 0;

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String result;
        volatile String error;
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Subscription> subs = new ConcurrentHashMap<>();

    /** Receives live candles. Methods are called on a network thread. */
    public interface CandleStream {
        void onHistory(Candle[] candles);
        /** the forming candle updated, or a new one opened (compare its epoch with the last one you have) */
        void onCandle(Candle candle);
        void onDisconnected(String why);
    }

    public final class Subscription {
        final int reqId;
        final CandleStream listener;
        volatile String subId;
        Subscription(int reqId, CandleStream l) { this.reqId = reqId; this.listener = l; }
        public void close() {
            subs.remove(reqId);
            String id = subId;
            WebSocket ws = socket;
            if (id != null && ws != null && open) {
                try { ws.send(new JSONObject().put("forget", id).toString()); } catch (Exception ignored) { }
            }
        }
    }
    private final AtomicInteger ids = new AtomicInteger(1);
    private WebSocket socket;
    private volatile boolean open;

    private synchronized void ensureOpen() throws Exception {
        if (socket != null && open) return;
        StringBuilder why = new StringBuilder();
        for (int k = 0; k < URLS.length; k++) {
            int idx = (urlIndex + k) % URLS.length;
            final CountDownLatch opened = new CountDownLatch(1);
            final String[] failure = new String[1];
            final WebSocket created = http.newWebSocket(new Request.Builder().url(URLS[idx]).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response r) { open = true; opened.countDown(); }
                @Override public void onMessage(WebSocket ws, String text) { handle(text); }
                @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                    open = false;
                    failure[0] = (r != null ? "HTTP " + r.code() + " " : "") + (t.getMessage() != null ? t.getMessage() : t.toString());
                    opened.countDown();
                    failAll("Connection lost");
                }
                @Override public void onClosed(WebSocket ws, int code, String reason) { open = false; failAll("Connection closed"); }
            });
            socket = created;
            if (opened.await(12, TimeUnit.SECONDS) && open) { urlIndex = idx; return; }
            try { created.cancel(); } catch (Exception ignored) { }
            socket = null;
            open = false;
            if (why.length() > 0) why.append("; ");
            why.append(URLS[idx].replaceAll("^wss://([^/]+).*$", "$1")).append(": ").append(failure[0] != null ? failure[0] : "no answer");
        }
        throw new Exception("Could not connect to Deriv (" + why + ")");
    }

    private void failAll(String why) {
        for (Subscription sb : new java.util.ArrayList<>(subs.values())) {
            try { sb.listener.onDisconnected(why); } catch (Exception ignored) { }
        }
        subs.clear();
        for (Map.Entry<Integer, Pending> e : pending.entrySet()) {
            e.getValue().error = why;
            e.getValue().done.countDown();
        }
        pending.clear();
    }

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            int id = m.optInt("req_id", -1);
            Subscription sb = subs.get(id);
            if (sb == null && m.has("ohlc")) {
                String oid = m.getJSONObject("ohlc").optString("id", "");
                for (Subscription x : subs.values()) if (oid.equals(x.subId)) sb = x;
            }
            if (sb != null) {
                if (m.has("error")) {
                    subs.remove(sb.reqId);
                    sb.listener.onDisconnected(m.getJSONObject("error").optString("message", "error"));
                } else if (m.has("candles")) {
                    JSONObject si = m.optJSONObject("subscription");
                    if (si != null) sb.subId = si.optString("id", null);
                    sb.listener.onHistory(parse(m.getJSONArray("candles")).toArray(new Candle[0]));
                } else if (m.has("ohlc")) {
                    JSONObject o = m.getJSONObject("ohlc");
                    if (sb.subId == null) sb.subId = o.optString("id", null);
                    sb.listener.onCandle(new Candle(o.has("open_time") ? o.getLong("open_time") : o.getLong("epoch"), o.getDouble("open"), o.getDouble("high"), o.getDouble("low"), o.getDouble("close")));
                }
                return;
            }
            Pending p = pending.remove(id);
            if (p == null) return;
            if (m.has("error")) {
                JSONObject er = m.getJSONObject("error");
                p.error = er.optString("code", "Error") + ": " + er.optString("message", "unknown");
            } else {
                p.result = text;
            }
            p.done.countDown();
        } catch (Exception ignored) { }
    }

    private JSONObject request(JSONObject payload) throws Exception {
        ensureOpen();
        int id = ids.getAndIncrement();
        Pending p = new Pending();
        pending.put(id, p);
        payload.put("req_id", id);
        if (!socket.send(payload.toString())) {
            pending.remove(id);
            open = false;
            throw new Exception("Could not send request to Deriv");
        }
        if (!p.done.await(20, TimeUnit.SECONDS)) {
            pending.remove(id);
            throw new Exception("Deriv request timed out");
        }
        if (p.error != null) throw new Exception(p.error);
        return new JSONObject(p.result);
    }

    private static List<Candle> parse(JSONArray arr) throws Exception {
        List<Candle> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.getJSONObject(i);
            double o = x.getDouble("open"), h = x.getDouble("high"), l = x.getDouble("low"), c = x.getDouble("close");
            long e = x.getLong("epoch");
            if (Double.isNaN(o + h + l + c) || Double.isInfinite(o + h + l + c)) continue;
            out.add(new Candle(e, o, h, l, c));
        }
        return out;
    }

    /** Live candles: sends the history once, then every update of the forming candle. Call close() on the result when done. */
    public Subscription subscribeCandles(String symbol, long granularity, int count, CandleStream listener) throws Exception {
        ensureOpen();
        int id = ids.getAndIncrement();
        Subscription sb = new Subscription(id, listener);
        subs.put(id, sb);
        JSONObject req = new JSONObject();
        req.put("ticks_history", symbol);
        req.put("style", "candles");
        req.put("granularity", granularity);
        req.put("count", count);
        req.put("end", "latest");
        req.put("adjust_start_time", 1);
        req.put("subscribe", 1);
        req.put("req_id", id);
        if (!socket.send(req.toString())) {
            subs.remove(id);
            open = false;
            throw new Exception("Could not send request to Deriv");
        }
        return sb;
    }

    /** Older candles ending just before `endEpoch` (for scrolling back). Oldest first. */
    public Candle[] candlesBefore(String symbol, long granularity, long endEpoch, int count) throws Exception {
        JSONObject req = new JSONObject();
        req.put("ticks_history", symbol);
        req.put("style", "candles");
        req.put("granularity", granularity);
        req.put("count", count);
        req.put("end", endEpoch);
        JSONObject msg = request(req);
        return parse(msg.optJSONArray("candles")).toArray(new Candle[0]);
    }

    /** Latest candles, oldest first. The LAST one is usually still forming. */
    @Override
    public Candle[] get(String symbol, long granularity, int count) throws Exception {
        JSONObject req = new JSONObject();
        req.put("ticks_history", symbol);
        req.put("style", "candles");
        req.put("granularity", granularity);
        req.put("count", count);
        req.put("end", "latest");
        req.put("adjust_start_time", 1);
        JSONObject msg = request(req);
        if (!msg.has("candles")) throw new Exception("No candles in response");
        return parse(msg.getJSONArray("candles")).toArray(new Candle[0]);
    }

    /** Long history by paging backwards (Deriv returns at most 5000 candles per request). Oldest first. */
    public Candle[] history(String symbol, long granularity, int total) throws Exception {
        List<Candle> all = new ArrayList<>();
        Object end = "latest";
        while (all.size() < total) {
            int want = Math.min(5000, total - all.size());
            JSONObject req = new JSONObject();
            req.put("ticks_history", symbol);
            req.put("style", "candles");
            req.put("granularity", granularity);
            req.put("count", want);
            req.put("end", end);
            JSONObject msg = request(req);
            List<Candle> page = parse(msg.optJSONArray("candles"));
            if (page.isEmpty()) break;
            all.addAll(0, page);
            if (page.size() < want) break;
            end = Long.valueOf(page.get(0).epoch - 1);
        }
        Set<Long> seen = new HashSet<>();
        List<Candle> uniq = new ArrayList<>();
        for (Candle c : all) if (seen.add(c.epoch)) uniq.add(c);
        Collections.sort(uniq, new Comparator<Candle>() {
            @Override public int compare(Candle a, Candle b) { return Long.compare(a.epoch, b.epoch); }
        });
        return uniq.toArray(new Candle[0]);
    }

    public synchronized void close() {
        try { if (socket != null) socket.close(1000, "bye"); } catch (Exception ignored) { }
        socket = null;
        open = false;
    }
}
