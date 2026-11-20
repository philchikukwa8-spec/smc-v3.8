package com.smcscanner.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.smcscanner.engine.Calibration;
import com.smcscanner.engine.Backtest;
import com.smcscanner.engine.Breakdowns;
import com.smcscanner.engine.Candle;
import java.util.HashMap;
import java.util.Map;
import com.smcscanner.engine.Factors;
import com.smcscanner.engine.Fmt;
import com.smcscanner.engine.Journal;
import com.smcscanner.engine.Lifecycle;
import com.smcscanner.engine.Manage;
import com.smcscanner.engine.Mtf;
import com.smcscanner.engine.Scan;
import com.smcscanner.engine.Setup;
import com.smcscanner.engine.pa.PaEngine;
import com.smcscanner.engine.Smc;
import com.smcscanner.engine.SmcConfig;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The whole app: Ideas, Chart, Alerts, Backtest and Settings tabs, built in code (no layout files to break). */
public class MainActivity extends Activity {
    // colours come from Theme (dark or light) and are refreshed in onCreate
    private static int BG, CARD, TXT, DIM, GREEN, RED, ACCENT;

    private static void syncColors() { BG = Theme.bg; CARD = Theme.card; TXT = Theme.txt; DIM = Theme.dim; GREEN = Theme.green; RED = Theme.red; ACCENT = Theme.accent; }
    private static final String[] TABS = {"Price Action", "Fib", "SMC", "Research", "Ideas", "Trades", "Chart", "Alerts", "Lab", "Settings"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DerivClient client = new DerivClient();
    private FrameLayout content;
    private LinearLayout tabBar;
    private FrameLayout rootFrame;
    private View scrim, drawer, handle;
    private TextView topTitle;
    private boolean drawerOpen;
    private float downX, downY;
    private boolean dragging;
    private int tab = 0;
    private Runnable refresher;

    // ---------- small view helpers ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(UiKit.round(CARD, dp(16)));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout page() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        return l;
    }

    private ScrollView scroll(View inner) {
        ScrollView s = new ScrollView(this);
        s.addView(inner);
        return s;
    }

    private Spinner spinner(List<String> items, int selected) {
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                ((TextView) v).setTextColor(TXT); // sits on the app background
                return v;
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                View v = super.getDropDownView(pos, cv, parent);
                ((TextView) v).setTextColor(Color.BLACK); // the popup list is always light
                return v;
            }
        });
        sp.setSelection(selected);
        return sp;
    }

    private void showText(String title, String body) {
        TextView t = text(body, 13, Color.BLACK, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll(t)).setPositiveButton("Close", null).show();
    }

    private void showTextWith(String title, String body, String actionLabel, final Runnable action) {
        TextView t = text(body, 13, Color.BLACK, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll(t))
            .setPositiveButton(actionLabel, (d, w) -> action.run())
            .setNegativeButton("Close", null).show();
    }

    private static String when(long ms) { return new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(ms)); }

    private static long granOf(String tf) {
        if (tf == null) return 900;
        switch (tf) { case "4H": case "H4": return 14400; case "1H": case "H1": return 3600; case "30m": return 1800; case "15m": return 900; case "5m": return 300; default: return 900; }
    }

    // ---------- side menu ----------
    private void setDrawer(boolean open) {
        drawerOpen = open;
        if (open) { drawer.setVisibility(View.VISIBLE); scrim.setVisibility(View.VISIBLE); handle.setVisibility(View.GONE); }
        else { scrim.setVisibility(View.GONE); handle.setVisibility(View.VISIBLE); }
        drawer.animate().translationX(open ? 0 : -dp(250)).setDuration(180).start();
        if (!open) ui.postDelayed(() -> { if (!drawerOpen) drawer.setVisibility(View.GONE); }, 200);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            downX = ev.getX(); downY = ev.getY();
            dragging = drawerOpen || downX < dp(26);
        } else if (a == android.view.MotionEvent.ACTION_MOVE && dragging) {
            float dx = ev.getX() - downX, dy = Math.abs(ev.getY() - downY);
            if (!drawerOpen && dx > dp(60) && dy < dx) { dragging = false; setDrawer(true); return true; }
            if (drawerOpen && dx < -dp(50) && dy < Math.abs(dx)) { dragging = false; setDrawer(false); return true; }
        } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            dragging = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---------- live tracking of setups ----------
    private interface LiveFn { com.smcscanner.engine.LiveStatus.Live at(double price); }
    private final Map<String, Double> livePrice = new HashMap<>();
    private final List<Object[]> liveBinds = new ArrayList<>();
    private Runnable liveTicker, relist;
    private long listStamp;

    /** a line that rewrites itself from the latest price (every few seconds) so the description follows the market */
    private TextView liveLine(String symbol, LiveFn fn) {
        TextView tv = text("", 13, TXT, true);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        tv.setLayoutParams(lp);
        Object[] b = {tv, symbol, fn};
        liveBinds.add(b);
        applyLive(b);
        return tv;
    }

    private void applyLive(Object[] b) {
        Double px = livePrice.get((String) b[1]);
        TextView tv = (TextView) b[0];
        com.smcscanner.engine.LiveStatus.Live l = ((LiveFn) b[2]).at(px == null ? 0 : px);
        tv.setText("LIVE  " + l.text);
        tv.setTextColor(l.tone > 0 ? GREEN : l.tone < 0 ? RED : TXT);
        tv.setBackground(UiKit.round(BG, dp(10)));
    }

    private long dataStamp() {
        long t = 0;
        for (String f : new String[]{"pa.bin", "fib.bin", "mie.bin"}) t += new java.io.File(getFilesDir(), f).lastModified();
        return t;
    }

    /** every 15 seconds: fetch the latest price of every market on screen and rewrite the live lines; reload the cards when a scan finished */
    private void startLive(final Runnable reload) {
        relist = reload;
        listStamp = dataStamp();
        if (liveTicker != null) ui.removeCallbacks(liveTicker);
        liveTicker = new Runnable() {
            @Override public void run() {
                final java.util.Set<String> syms = new java.util.HashSet<>();
                for (Object[] b : liveBinds) syms.add((String) b[1]);
                new Thread(() -> {
                    for (String sym : syms) {
                        try { Candle[] c = client.get(sym, 60, 3); if (c.length > 0) livePrice.put(sym, c[c.length - 1].close); } catch (Throwable ignored) { }
                    }
                    ui.post(() -> {
                        if (liveTicker != this) return;
                        long st = dataStamp();
                        if (st != listStamp && relist != null) { listStamp = st; liveBinds.clear(); relist.run(); }
                        else for (Object[] b : liveBinds) applyLive(b);
                        ui.postDelayed(this, 15000);
                    });
                }).start();
            }
        };
        ui.postDelayed(liveTicker, 1500);
    }

    private void stopLive() {
        if (liveTicker != null) ui.removeCallbacks(liveTicker);
        liveTicker = null; relist = null; liveBinds.clear();
    }

    // ---------- "I entered this trade": log a trade from any setup, met every condition or not ----------
    private void askManualEntry(final String symbol, final String label, final boolean buy, final double entry, final double stop, final double target,
                                final Double tp2, final String tfLabel, final long gran, final String origin, final String state, final String conditions, final String setupId) {
        final EditText eIn = new EditText(this), sIn = new EditText(this), tIn = new EditText(this);
        for (EditText e : new EditText[]{eIn, sIn, tIn}) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Double live = livePrice.get(symbol);
        eIn.setText(Fmt.f(live != null ? live : entry));
        sIn.setText(stop > 0 ? Fmt.f(stop) : "");
        tIn.setText(target > 0 ? Fmt.f(target) : "");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(label + "  " + (buy ? "BUY" : "SELL") + "  (" + origin + ")", 14, Color.BLACK, true));
        if (!conditions.isEmpty()) box.addView(text(conditions, 12, Color.DKGRAY, false));
        box.addView(text("You can log it anyway: the app only tracks and advises, it does not place orders.", 12, Color.DKGRAY, false));
        box.addView(text("Entry price you got (the current price is filled in)", 12, Color.BLACK, false)); box.addView(eIn);
        box.addView(text("Stop", 12, Color.BLACK, false)); box.addView(sIn);
        box.addView(text("Target (optional)", 12, Color.BLACK, false)); box.addView(tIn);
        new AlertDialog.Builder(this).setTitle("I entered this trade").setView(scroll(box))
            .setPositiveButton("Track it", (d, w) -> {
                double en, st, tg = 0;
                try { en = Double.parseDouble(eIn.getText().toString().trim()); st = Double.parseDouble(sIn.getText().toString().trim()); }
                catch (Exception ex) { showText("Not saved", "Type the entry and the stop as numbers."); return; }
                try { String t = tIn.getText().toString().trim(); if (!t.isEmpty()) tg = Double.parseDouble(t); } catch (Exception ex) { tg = 0; }
                if (buy ? !(st < en) : !(st > en)) { showText("Not saved", "For a " + (buy ? "buy" : "sell") + " the stop must be " + (buy ? "below" : "above") + " your entry."); return; }
                if (tg > 0 && (buy ? !(tg > en) : !(tg < en))) { showText("Not saved", "The target must be " + (buy ? "above" : "below") + " your entry (or leave it empty)."); return; }
                com.smcscanner.engine.Manage.Trade t = com.smcscanner.engine.Manage.createManual(symbol, label, tfLabel, tfLabel, gran, buy ? "BUY" : "SELL", en, st, tg,
                    tp2, System.currentTimeMillis() / 1000, Store.riskMoney(this), origin);
                t.origin = origin; t.originState = state;
                Store.addTrade(this, t);
                if (setupId != null && !setupId.isEmpty()) Store.setSetupFlag(this, 'L', setupId);
                select(5);
            })
            .setNegativeButton("Cancel", null).show();
    }

    /** the Trades tab: log a trade with no setup behind it */
    private void askFreeTrade() {
        final Scan.Config cfg = Store.config(this);
        final int[] mk = {0}, sd = {0};
        String[] ml = new String[cfg.markets.size()];
        for (int i = 0; i < ml.length; i++) ml[i] = cfg.markets.get(i).label;
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text("Market", 12, Color.BLACK, false));
        box.addView(singleChips(ml, mk, null));
        box.addView(text("Direction", 12, Color.BLACK, false));
        box.addView(singleChips(new String[]{"Buy", "Sell"}, sd, null));
        final EditText eIn = new EditText(this), sIn = new EditText(this), tIn = new EditText(this);
        for (EditText e : new EditText[]{eIn, sIn, tIn}) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(text("Entry price", 12, Color.BLACK, false)); box.addView(eIn);
        box.addView(text("Stop", 12, Color.BLACK, false)); box.addView(sIn);
        box.addView(text("Target (optional)", 12, Color.BLACK, false)); box.addView(tIn);
        new AlertDialog.Builder(this).setTitle("Log a trade you took").setView(scroll(box))
            .setPositiveButton("Track it", (d, w) -> {
                double en, st, tg = 0;
                try { en = Double.parseDouble(eIn.getText().toString().trim()); st = Double.parseDouble(sIn.getText().toString().trim()); }
                catch (Exception ex) { showText("Not saved", "Type the entry and the stop as numbers."); return; }
                try { String t = tIn.getText().toString().trim(); if (!t.isEmpty()) tg = Double.parseDouble(t); } catch (Exception ex) { tg = 0; }
                boolean buy = sd[0] == 0;
                if (buy ? !(st < en) : !(st > en)) { showText("Not saved", "For a " + (buy ? "buy" : "sell") + " the stop must be " + (buy ? "below" : "above") + " your entry."); return; }
                if (tg > 0 && (buy ? !(tg > en) : !(tg < en))) { showText("Not saved", "The target must be " + (buy ? "above" : "below") + " your entry (or leave it empty)."); return; }
                Scan.Market m = cfg.markets.get(mk[0]);
                com.smcscanner.engine.Manage.Trade t = com.smcscanner.engine.Manage.createManual(m.symbol, m.label, "15m", "15m", 900, buy ? "BUY" : "SELL", en, st, tg, null,
                    System.currentTimeMillis() / 1000, Store.riskMoney(this), "typed in by hand");
                t.origin = "Typed in by hand"; t.originState = "MANUAL";
                Store.addTrade(this, t);
                select(5);
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ---------- lifecycle ----------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.apply(Store.dark(this));
        syncColors();
        Notifier.createChannels(this);
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);
        rootFrame.setFitsSystemWindows(true);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        // top bar: the menu button and the name of the current screen
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(CARD);
        TextView burger = text("\u2630", 22, ACCENT, true);
        burger.setPadding(dp(16), dp(10), dp(16), dp(10));
        burger.setOnClickListener(v -> setDrawer(!drawerOpen));
        top.addView(burger);
        topTitle = text(TABS[0], 17, TXT, true);
        top.addView(topTitle);
        main.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootFrame.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // a small grip on the left edge: tap it or swipe from the edge to pull the menu out
        TextView grip = text("\u203A", 22, ACCENT, true);
        grip.setGravity(Gravity.CENTER);
        grip.setBackground(UiKit.round(CARD, dp(10)));
        grip.setOnClickListener(v -> setDrawer(true));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(18), dp(64), Gravity.START | Gravity.CENTER_VERTICAL);
        rootFrame.addView(grip, gp);
        handle = grip;
        scrim = new View(this);
        scrim.setBackgroundColor(0x88000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> setDrawer(false));
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout dr = new LinearLayout(this);
        dr.setOrientation(LinearLayout.VERTICAL);
        dr.setBackgroundColor(CARD);
        dr.setPadding(0, dp(8), 0, dp(8));
        TextView brand = text("Legacy Arc", 20, ACCENT, true);
        brand.setPadding(dp(20), dp(16), dp(20), dp(2));
        dr.addView(brand);
        TextView tag = text("Market intelligence", 11, DIM, false);
        tag.setPadding(dp(20), 0, dp(20), dp(12));
        dr.addView(tag);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 16, DIM, true);
            t.setPadding(dp(20), dp(14), dp(20), dp(14));
            t.setOnClickListener(v -> { setDrawer(false); select(idx); });
            tabBar.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView ds = new ScrollView(this);
        ds.addView(tabBar);
        dr.addView(ds, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer = dr;
        rootFrame.addView(dr, new FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));
        dr.setTranslationX(-dp(250));
        dr.setVisibility(View.GONE);
        setContentView(rootFrame);
        select(0);
        askNotificationPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    /** the "Open chart" button on an alert */
    private void handleIntent(Intent i) {
        if (i != null && i.getBooleanExtra(Notifier.EXTRA_OPEN_FIB, false)) {
            i.putExtra(Notifier.EXTRA_OPEN_FIB, false);
            tab = 1;
            select(1);
            return;
        }
        if (i == null || !i.getBooleanExtra(Notifier.EXTRA_OPEN_CHART, false)) return;
        i.putExtra(Notifier.EXTRA_OPEN_CHART, false);
        String sym = i.getStringExtra(Notifier.EXTRA_SYMBOL);
        if (sym != null) Store.setChartSymbol(this, sym);
        tab = 6;
        select(6);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onResume() { super.onResume(); select(tab); }

    @Override
    protected void onPause() {
        if (refresher != null) ui.removeCallbacks(refresher);
        stopLive();
        if (chartCtl != null) { chartCtl.stop(); chartCtl = null; }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        new Thread(client::close).start();
        super.onDestroy();
    }

    private void select(int i) {
        tab = i;
        inDetail = false;
        if (refresher != null) ui.removeCallbacks(refresher);
        refresher = null;
        stopLive();
        if (topTitle != null) topTitle.setText(TABS[Math.min(i, TABS.length - 1)]);
        if (chartCtl != null) { chartCtl.stop(); chartCtl = null; }
        for (int k = 0; k < tabBar.getChildCount(); k++) ((TextView) tabBar.getChildAt(k)).setTextColor(k == i ? ACCENT : DIM);
        content.removeAllViews();
        View v;
        switch (i) {
            case 0: v = birdsEyeTab(); break;
            case 1: v = fibTab(); break;
            case 2: v = advisorTab(); break;
            case 3: v = researchTab(); break;
            case 4: v = ideasTab(); break;
            case 5: v = tradesTab(); break;
            case 6: v = chartTab(); break;
            case 7: v = alertsTab(); break;
            case 8: v = labTab(); break;
            default: v = settingsTab(); break;
        }
        content.addView(v);
    }

    /** the Legacy Arc emblem with the app name, shown at the top of the main screens */
    private View brandHeader(String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int res = getResources().getIdentifier("emblem", "drawable", getPackageName());
        if (res != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(res);
            row.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        col.addView(text("LEGACY ARC", 20, 0xFFE8B923, true));
        col.addView(text(subtitle, 12, DIM, false));
        row.addView(col);
        row.setPadding(0, 0, 0, dp(8));
        return row;
    }

    // ---------- Advisor: Market Intelligence Engine (4H > 1H > 30m > 15m, entry on 5m or 1m) ----------
    private View advisorTab() {
        LinearLayout p = page();
        p.addView(UiKit.brandRow(this, "SMC", "OPTIONAL SECOND ENGINE"));
        LinearLayout onoff = card();
        CheckBox smcOn = new CheckBox(this);
        smcOn.setText("SMC engine on (scans, alerts and records trades)");
        smcOn.setTextColor(TXT);
        smcOn.setChecked(Store.modeSmc(this));
        smcOn.setOnCheckedChangeListener((b, on) -> { Store.setModeSmc(this, on); select(2); });
        onoff.addView(smcOn);
        if (!Store.modeSmc(this)) onoff.addView(text("SMC is off: it is not scanning or sending alerts. The Price Action tab is the main engine. Order blocks, FVGs and liquidity sweeps come from forex and stock theory about banks and sessions; Deriv volatility indices are algorithmic, so use the Lab to see whether SMC earns anything here before relying on it. Turn it on to scan and to see its results here.", 12, DIM, false));
        p.addView(onoff);
        p.addView(text("4H sets the environment, 1H the direction, 30m finds the zone, 15m validates, and the 5m or 1m picks the entry. R:R is shown on every plan; nothing is hidden because of it. A grade counts groups of evidence. It is not a win probability.", 12, DIM, false));
        final TextView status = text(Store.lastStatus(this), 12, DIM, false);
        final Button go = button("Update now", null);
        p.addView(go);
        p.addView(status);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        p.addView(list);
        go.setOnClickListener(v -> {
            go.setEnabled(false);
            status.setText("Reading the charts...");
            new Thread(() -> {
                String s;
                try { int n = MieRunner.run(this, client, System.currentTimeMillis() / 1000); s = n + " new alert" + (n == 1 ? "" : "s"); }
                catch (Throwable t) { s = "Could not update: " + t.getMessage(); }
                final String res = s;
                ui.post(() -> { go.setEnabled(true); status.setText(res); fillAdvisor(list); });
            }).start();
        });
        fillAdvisor(list);
        startLive(() -> fillAdvisor(list));
        refresher = new Runnable() {
            @Override public void run() { fillAdvisor(list); ui.postDelayed(this, 10000); }
        };
        ui.postDelayed(refresher, 10000);
        return scroll(p);
    }

    private static String bullets(List<String> l, String mark) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) sb.append(mark).append(' ').append(x).append('\n');
        return sb.toString().trim();
    }

    /** plain keep/drop reading for a setup type; small samples are never judged */
    private static String typeVerdict(com.smcscanner.engine.mie.Kpi k) {
        if (k.completed < 30) return "-> Too few completed trades (" + k.completed + " of 30 needed) to judge. Keep collecting.";
        if (k.expectancy < 0) return "-> Losing on average over " + k.completed + " trades. Consider switching this type off.";
        if (k.expectancy > 0.1) return "-> Positive over " + k.completed + " trades. Keep, and re-check on new data.";
        return "-> Mixed result over " + k.completed + " trades. No clear decision yet.";
    }

    private View tile(String label, String value, int col) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(UiKit.round(BG, dp(10)));
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        TextView l = text(label, 10, DIM, false); l.setGravity(Gravity.CENTER);
        TextView v = text(value, 14, col, true); v.setGravity(Gravity.CENTER);
        t.addView(l); t.addView(v);
        return t;
    }

    /** 0..5, from what the engine already found (a summary for the eye, not a win probability) */
    private static int quality(MieRunner.Item it) {
        com.smcscanner.engine.mie.Mie.Assessment a = it.a;
        if (a.side.equals("NONE")) return 0;
        int q = 0;
        if (!(a.alignment.equals("MIXED") || a.alignment.equals("UNCLEAR"))) q++;
        if (a.groupsMet >= 3) q++;
        if (a.groupsMet >= 4) q++;
        if (a.conflicts.isEmpty()) q++;
        if (it.dataStatus.equals("LIVE")) q++;
        return q;
    }

    private View qualityBar(int q, int col) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(8));
        for (int i = 0; i < 5; i++) {
            View seg = new View(this);
            seg.setBackground(UiKit.round(i < q ? col : BG, dp(3)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(7), 1f);
            lp.setMargins(0, 0, dp(4), 0);
            seg.setLayoutParams(lp);
            row.addView(seg);
        }
        return row;
    }

    private View tfStrip(com.smcscanner.engine.mie.Mie.Assessment a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        for (com.smcscanner.engine.mie.Mie.TfState t : a.tfs) {
            String st = t.state.toUpperCase(Locale.US);
            String icon; int col;
            if (st.startsWith("BULL")) { icon = "▲"; col = GREEN; }
            else if (st.startsWith("BEAR")) { icon = "▼"; col = RED; }
            else if (st.startsWith("RANG")) { icon = "◆"; col = DIM; }
            else if (st.startsWith("TRANS")) { icon = "⇄"; col = ACCENT; }
            else { icon = "?"; col = DIM; }
            TextView chip = UiKit.pill(this, " " + t.tf + " " + icon + " ", BG, col);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(5), 0);
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
        return row;
    }

    private static String clean(String s) {
        if (s.startsWith("[OK] ") || s.startsWith("[--] ")) return s.substring(5);
        return s;
    }

    private void fillAdvisor(LinearLayout list) {
        liveBinds.clear();
        list.removeAllViews();
        List<MieRunner.Item> items = new ArrayList<>(MieRunner.load(this));
        if (items.isEmpty()) {
            list.addView(text("No analysis yet. Press \"Update now\" (the scanner also updates it every few minutes while it runs).", 14, DIM, false));
            return;
        }
        java.util.Collections.sort(items, (x, y) -> {
            int ax = x.a.actionable() ? 1 : 0, ay = y.a.actionable() ? 1 : 0;
            if (ax != ay) return ay - ax;
            return quality(y) - quality(x);
        });
        list.addView(text("Sorted with ready setups first. Quality bar: how many of 5 checks agree (higher timeframes, evidence groups, no warnings, live data). It is a summary, not a win probability.", 11, DIM, false));
        for (final MieRunner.Item it : items) {
            final com.smcscanner.engine.mie.Mie.Assessment a = it.a;
            boolean act = a.actionable();
            boolean buy = a.isBuy();
            boolean hasSide = !a.side.equals("NONE");
            int sideCol = !hasSide ? DIM : (buy ? GREEN : RED);
            int q = quality(it);
            LinearLayout c = card();

            // 1. market name + direction
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(it.label, 19, TXT, true);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);
            if (hasSide) head.addView(UiKit.pill(this, " " + (buy ? "▲ BUY" : "▼ SELL") + " ", sideCol, Theme.onColor));
            c.addView(head);
            LinearLayout fr = new LinearLayout(this);
            fr.setOrientation(LinearLayout.HORIZONTAL);
            fr.setPadding(0, dp(4), 0, 0);
            fr.addView(UiKit.pill(this, " " + a.frame + " cascade ", BG, ACCENT));
            c.addView(fr);

            // 2. plain status
            String status = act ? "Ready: place a " + (buy ? "buy" : "sell") + " limit order at the entry price"
                : a.signal.equals("WATCH") ? "Getting close: waiting for the 15m to confirm"
                : "No trade right now";
            TextView st = text(status, 14, act ? sideCol : TXT, true);
            st.setPadding(0, dp(6), 0, dp(2));
            c.addView(st);
            String verdict = !hasSide ? "Nothing worth acting on." : act ? (q >= 4 ? "Strong setup" : q == 3 ? "Decent setup, read the warnings" : "Weak setup, take care") : "Not ready yet";
            c.addView(text(verdict + "  (" + q + "/5)   " + (it.dataStatus.equals("LIVE") ? "live data" : it.dataStatus.toLowerCase(Locale.US) + " data"), 12, DIM, false));
            c.addView(qualityBar(q, q >= 4 ? GREEN : q == 3 ? ACCENT : DIM));

            // 3. picture
            if (it.chart != null && it.chart.length > 5 && hasSide) {
                final MiniChartView mc = new MiniChartView(this);
                mc.setData(a, it.chart, it.chartGran);
                mc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
                mc.setOnClickListener(v -> { Store.setChartSymbol(this, it.symbol); select(6); });
                c.addView(mc);
                c.addView(text("Gold = zone to buy/sell in. Red = what you risk. Green = what you aim to make. Tap to open the live chart.", 11, DIM, false));
            }

            // 4. trade plan tiles
            if (a.entry > 0 && a.stop > 0 && a.target > 0) {
                LinearLayout nums = new LinearLayout(this);
                nums.setOrientation(LinearLayout.HORIZONTAL);
                nums.setPadding(0, dp(10), 0, dp(4));
                nums.addView(tile("ENTRY", Fmt.f(a.entry), TXT));
                nums.addView(tile("STOP", Fmt.f(a.stop), RED));
                nums.addView(tile("TARGET", Fmt.f(a.target), GREEN));
                nums.addView(tile("REWARD", String.format(Locale.US, "%.1fR", a.rr), ACCENT));
                c.addView(nums);
                c.addView(text(a.rrBand + "  ·  entry timeframe " + a.execTf, 12, DIM, false));
                final boolean fbuy = buy, fact = act;
                c.addView(liveLine(it.symbol, px -> com.smcscanner.engine.LiveStatus.describe(fbuy, fact, a.setupState.equals(com.smcscanner.engine.mie.Mie.S_ENTRY), a.entry, a.stop, a.target, px)));
            }

            // 5. timeframe strip
            c.addView(tfStrip(a));
            c.addView(text(a.headline, 13, TXT, false));

            // 6. checks and warnings
            if (!a.evidence.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String e : a.evidence) sb.append(e.startsWith("[--]") ? "– " : "✓ ").append(clean(e)).append('\n');
                TextView t = text(sb.toString().trim(), 12, TXT, false);
                t.setPadding(0, dp(8), 0, 0);
                c.addView(t);
            }
            if (!a.conflicts.isEmpty()) {
                StringBuilder sb = new StringBuilder("Warnings\n");
                for (String e : a.conflicts) sb.append("⚠ ").append(e).append('\n');
                TextView w = text(sb.toString().trim(), 12, TXT, false);
                w.setBackground(UiKit.round(BG, dp(10)));
                w.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(8), 0, 0);
                w.setLayoutParams(lp);
                c.addView(w);
            }

            // 7. details folded away
            StringBuilder d = new StringBuilder();
            if (!a.execWhy.isEmpty()) d.append(a.execWhy).append("\n\n");
            if (!a.targetWhy.isEmpty()) d.append(a.targetWhy).append("\n\n");
            if (!a.reasons.isEmpty()) d.append("Reason codes: ").append(String.join(", ", a.reasons)).append("\n\n");
            if (!it.memory.isEmpty()) d.append("Memory: ").append(it.memory).append("\n\n");
            if (!it.behaviour.isEmpty()) d.append(it.behaviour).append("\n\n");
            if (!it.dataNote.isEmpty()) d.append("Data: ").append(it.dataNote);
            if (d.length() > 0) c.addView(UiKit.section(this, "More details", d.toString().trim(), false));

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            Button why = button("Why?", v -> showText(it.label + ": " + a.signal, a.explanation));
            why.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            btns.addView(why);
            if (hasSide) {
                Button oc = button("Live chart (" + a.execTf + ")", v -> {
                    ChartController.Focus f = new ChartController.Focus();
                    f.symbol = it.symbol; f.tf = a.execTf; f.buy = a.isBuy(); f.ready = a.actionable();
                    f.entry = a.entry; f.stop = a.stop; f.target = a.target;
                    f.title = it.label + " " + a.execTf + ": SMC " + a.signal + (a.actionable() ? "  READY" : "  WATCH");
                    f.note = "Invalid if a " + a.execTf + " candle closes " + (a.isBuy() ? "below " : "above ") + Fmt.f(a.stop);
                    openFocusChart(it.symbol, f);
                });
                oc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                btns.addView(oc);
            }
            c.addView(btns);
            if (hasSide && a.entry > 0 && a.stop > 0) {
                final boolean fbuy2 = buy;
                c.addView(button("I entered this trade", v -> askManualEntry(it.symbol, it.label, fbuy2, a.entry, a.stop, a.target, null, a.execTf, granOf(a.execTf),
                    "SMC: " + a.signal, act ? "QUALIFIED" : "WATCH",
                    act ? "Setup status: ready." : "Setup status: not every condition is met (" + a.signal + "). You can log it anyway.", a.id)));
            }
            list.addView(c);
        }
    }

    // ---------- Market's Bird's Eye View (price-action mode) ----------
    private View birdsEyeTab() {
        LinearLayout p = page();
        p.addView(UiKit.brandRow(this, "Price Action", "THE MAIN ENGINE"));
        p.addView(text("The main engine, built from the Supreme price-action guide. Open the section below to see every strategy it looks for, how it enters, where the stop goes and how the target is set. Every chart (1H, 30m, 15m) keeps its own setups, and every volatility index has its own section. R:R is a label only. SMC is optional and off by default.", 12, DIM, false));
        p.addView(UiKit.section(this, "Every strategy this engine looks for (from the guide)",
            "TRADE ONLY WITH A TREND, AT A KEY LEVEL. Buy in uptrends, sell in downtrends. A signal that is not at a key support/resistance level, or on a chart with no trend (a 'dirty chart'), is passed over. Entries are mostly retests of a broken line, not chases.\n\n"
            + "1. SUPPORT / RESISTANCE. Zones tested 3+ times. Rejection candle at the zone, or a break-and-retest where old resistance becomes support (and the reverse). Stop just beyond the signal candles.\n\n"
            + "2. CANDLE SIGNALS at a level or trendline: pin bar, engulfing, fakey (false break of an inside bar).\n\n"
            + "3. TRENDLINES. Bounce (3+ touches, not steeper than about 45 degrees, flagged after 5 touches), break-and-retest of a short-term line, 2:1 reward minimum, and confluence with the 61.8%/50% Fibonacci retracement or the 200 moving average.\n\n"
            + "4. TREND CHANNELS. When price overshoots the channel line and closes back inside, the engine fades it (counter-trend, tight stop, target the middle of the channel). A second overshoot of the same line is more reliable.\n\n"
            + "5. BREAKOUT + RETEST of a range or wedge: enter on the retest, stop beyond the breakout candle, target the next level. If price goes sideways 3-4 candles after the break, it joins at the market with the stop under the pause. A long base means a bigger breakout.\n\n"
            + "6. HEAD AND SHOULDERS (and inverse). Needs an extended move first, a head above both shoulders, shoulders in the same price band, a neckline that rises or is flat (never falls). Confirmed only on a CLOSE through the neckline; enter on the retest; a close back through it cancels the idea. Target: head-to-neckline height measured from the break.\n\n"
            + "7. DOUBLE TOP / DOUBLE BOTTOM. After an extended move, two equal highs/lows, close through the neckline, enter on the retest, target the measured move.\n\n"
            + "8. ROUNDING BOTTOM / TOP and CUP AND HANDLE. A smooth U (or n) base with rims at the same height; the handle is a small pullback under the right rim. Enter when the neckline breaks (retest), stop under the 61.8% level of the cup, target the cup depth added above the neckline.\n\n"
            + "9. WEDGES (rising/falling). Three touches on each side, enter on the retest of the broken line, stop beyond the last swing, targets at the swing levels of the stair-steps.\n\n"
            + "10. FLAGS AND PENNANTS. A strong pole, then a flag that drifts AGAINST the pole (a flag sloping with the trend is exhaustion and is rejected). Enter on the retest of the flag boundary, target the pole height from the break.\n\n"
            + "11. ASCENDING / DESCENDING / SYMMETRICAL TRIANGLES. Trade the close beyond the line in the direction of the break; target the widest height of the triangle; stop at the recent swing.\n\n"
            + "Every setup shows its VALIDATION POINTS (what is already true, what is still needed), the INVALIDATION level (the stop) and the TARGET. 'Live chart' opens that setup on its own chart.", false));
        LinearLayout modes = card();
        modes.addView(text("Which engines run", 16, TXT, true));
        modes.addView(text("Price action is the backbone. Switch SMC on only if you want it alongside; both record every signal and how it ended, so you can compare them in the Lab.", 12, DIM, false));
        CheckBox smcCb = new CheckBox(this);
        smcCb.setText("SMC engine (the SMC tab). Off by default");
        smcCb.setTextColor(TXT);
        smcCb.setChecked(Store.modeSmc(this));
        smcCb.setOnCheckedChangeListener((b, on) -> Store.setModeSmc(this, on));
        modes.addView(smcCb);
        CheckBox paCb = new CheckBox(this);
        paCb.setText("Price action engine (this tab)");
        paCb.setTextColor(TXT);
        paCb.setChecked(Store.modePa(this));
        paCb.setOnCheckedChangeListener((b, on) -> Store.setModePa(this, on));
        modes.addView(paCb);
        CheckBox lbCb = new CheckBox(this);
        lbCb.setText("Learning: stop alerting for a situation that lost money over 30+ trades");
        lbCb.setTextColor(TXT);
        lbCb.setChecked(Store.learnBlock(this));
        lbCb.setOnCheckedChangeListener((b, on) -> Store.setLearnBlock(this, on));
        modes.addView(lbCb);
        CheckBox lfCb = new CheckBox(this);
        lfCb.setText("Also look for opportunities on the 30m and 15m (both modes; more alerts, more noise)");
        lfCb.setTextColor(TXT);
        lfCb.setChecked(Store.lowerFrames(this));
        lfCb.setOnCheckedChangeListener((b, on) -> Store.setLowerFrames(this, on));
        modes.addView(lfCb);
        p.addView(modes);
        final TextView status = text("", 12, DIM, false);
        final Button go = button("Update now", null);
        p.addView(go);
        p.addView(status);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        p.addView(list);
        go.setOnClickListener(v -> {
            go.setEnabled(false);
            status.setText("Reading the 1H and 4H charts...");
            new Thread(() -> {
                String s;
                try { int n = PaRunner.run(this, client, System.currentTimeMillis() / 1000); s = n + " new alert" + (n == 1 ? "" : "s"); }
                catch (Throwable t) { s = "Could not update: " + t.getMessage(); }
                final String res = s;
                ui.post(() -> { go.setEnabled(true); status.setText(res); fillBirds(list); });
            }).start();
        });
        fillBirds(list);
        startLive(() -> fillBirds(list));

        // compare and learn
        LinearLayout lc = card();
        lc.addView(text("Compare and learn", 16, TXT, true));
        lc.addView(text("Puts both modes side by side: results per scenario, what happens when both agree, and what the memory has learned. The replay gives it history to learn from straight away (several minutes).", 12, DIM, false));
        final TextView out = text("", 12, TXT, false);
        out.setTypeface(Typeface.MONOSPACE);
        final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(false);
        lc.addView(button("Compare SMC and price action", v -> runJob(busy, out, "Comparing...", () -> com.smcscanner.engine.mie.Learning.compare(SqliteResearchStore.get(this).all()))));
        lc.addView(button("Replay price action on real Deriv history", v -> runJob(busy, out, "Replaying real history for each market. Keep the app open...", () -> {
            StringBuilder sb = new StringBuilder("PRICE ACTION REPLAY (1H candles, no look-ahead; in-sample is the first 60% of time, validation the next 20%, out-of-sample the last 20%)\n\n");
            for (com.smcscanner.engine.Scan.Market m : Store.config(this).markets) sb.append(PaRunner.replay(this, client, m)).append("\n\n");
            sb.append("Now press \"Compare SMC and price action\". Only validation, out-of-sample and live rows can switch a scenario off.");
            return sb.toString();
        })));
        p.addView(lc);
        p.addView(out);
        return scroll(p);
    }

    private String paMarket = "";   // "" = overview of every market, otherwise a symbol
    private ChartController.Focus chartFocus;

    private static String ago(long at) { return FibRunner.formatAge(at, System.currentTimeMillis() / 1000); }

    private void fillBirds(final LinearLayout list) {
        liveBinds.clear();
        list.removeAllViews();
        final List<PaRunner.Item> items = PaRunner.load(this);
        for (PaRunner.Item pi : items) {
            java.util.Iterator<PaEngine.Signal> iter = pi.signals.iterator();
            while (iter.hasNext()) {
                PaEngine.Signal g = iter.next();
                if (Store.setupFlag(this, 'R', g.id) || (g.expired && Store.setupFlag(this, 'L', g.id))) iter.remove();
            }
        }
        if (items.isEmpty()) {
            list.addView(text("No analysis yet. Press \"Update now\" (the scanner also updates it every few minutes while it runs).", 14, DIM, false));
            return;
        }
        // ---- a button per market, with how many setups it has
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean found = false;
        for (PaRunner.Item it : items) if (it.symbol.equals(paMarket)) found = true;
        if (!found) paMarket = "";
        int total = 0;
        for (PaRunner.Item it : items) total += activeCount(it);
        TextView all = chip("Overview  " + total, paMarket.isEmpty());
        all.setOnClickListener(v -> { paMarket = ""; fillBirds(list); });
        row.addView(all);
        for (final PaRunner.Item it : items) {
            int ready = 0;
            for (PaEngine.Signal g : it.signals) if (g.actionable() && !g.expired) ready++;
            TextView ch = chip(it.label + "  " + activeCount(it) + (ready > 0 ? "  (" + ready + " ready)" : ""), it.symbol.equals(paMarket));
            ch.setOnClickListener(v -> { paMarket = it.symbol; fillBirds(list); });
            row.addView(ch);
        }
        list.addView(chipScroller(row));
        if (paMarket.isEmpty()) { paOverview(list, items); return; }
        PaRunner.Item cur = null;
        for (PaRunner.Item it : items) if (it.symbol.equals(paMarket)) cur = it;
        final PaRunner.Item it = cur;
        // ---- summary of this market
        int ready = 0, watch = 0, b1 = 0, b30 = 0, b15 = 0, buys = 0, sells = 0;
        int dcDone = 0, expiredN = 0;
        for (PaEngine.Signal g : it.signals) {
            if (g.expired) { expiredN++; continue; }
            if (g.actionable()) ready++; else watch++;
            if (g.dc == 2) dcDone++;
            if (g.tf.equals("1H")) b1++; else if (g.tf.equals("30m")) b30++; else b15++;
            if (g.isBuy()) buys++; else sells++;
        }
        LinearLayout sum = card();
        sum.addView(text(it.label, 20, TXT, true));
        sum.addView(text(activeCount(it) + " setup" + (activeCount(it) == 1 ? "" : "s") + " detected:  " + ready + " ready to trade,  " + watch + " being watched,  " + dcDone + " double confirmed" + (expiredN > 0 ? ",  " + expiredN + " expired (at the bottom)" : ""), 14, TXT, true));
        sum.addView(text("By chart:  1H " + b1 + "   |   30m " + b30 + "   |   15m " + b15 + "      By side:  " + buys + " buy,  " + sells + " sell", 12, DIM, false));
        sum.addView(text("Read " + ago(it.at) + "  |  data " + it.dataStatus.toLowerCase(Locale.US) + (Store.lowerFrames(this) ? "" : "  |  30m and 15m charts are off (Price Action tab, \"Also look for opportunities\")"), 12, DIM, false));
        if (!it.dataNote.isEmpty()) sum.addView(text(it.dataNote, 12, RED, false));
        sum.addView(text("Each setup below is its own trade idea with its own entry, stop and target. Order: bigger charts first (1H, then 30m, then 15m). Only one direction is shown per market: the more convincing side, one that agrees with the higher chart unless it is a reversal idea.", 11, DIM, false));
        list.addView(sum);
        if (activeCount(it) == 0) {
            list.addView(text("No price-action setup on " + it.label + " right now.", 13, DIM, false));
            if (it.signals.isEmpty()) return;
        }
        for (int k = 0; k < it.signals.size(); k++) paCard(list, it, it.signals.get(k), k < it.memory.size() ? it.memory.get(k) : "");
    }

    private static int activeCount(PaRunner.Item it) {
        int n = 0;
        for (PaEngine.Signal g : it.signals) if (!g.expired) n++;
        return n;
    }

    private void paOverview(final LinearLayout list, List<PaRunner.Item> items) {
        list.addView(text("Tap a market above (or a card here) to see every setup on it. Ready = an order can be placed; watching = close, not confirmed.", 12, DIM, false));
        for (final PaRunner.Item it : items) {
            int ready = 0;
            PaEngine.Signal best = null;
            for (PaEngine.Signal g : it.signals) { if (g.expired) continue; if (g.actionable()) ready++; if (best == null) best = g; }
            LinearLayout c = card();
            LinearLayout h = new LinearLayout(this);
            h.setOrientation(LinearLayout.HORIZONTAL);
            h.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = text(it.label, 18, TXT, true);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            h.addView(nm);
            h.addView(UiKit.pill(this, " " + activeCount(it) + " setup" + (activeCount(it) == 1 ? "" : "s") + " ", ready > 0 ? GREEN : BG, ready > 0 ? Theme.onColor : DIM));
            c.addView(h);
            c.addView(text(ready + " ready, " + (activeCount(it) - ready) + " watching   |   read " + ago(it.at) + "   |   data " + it.dataStatus.toLowerCase(Locale.US), 12, DIM, false));
            if (best != null) c.addView(text("Best: " + best.scenario + "  " + best.side + "  " + String.format(Locale.US, "%.1fR", best.rr) + "  (" + best.score + "/5)", 13, best.isBuy() ? GREEN : RED, true));
            else c.addView(text("Nothing worth acting on.", 13, DIM, false));
            c.setOnClickListener(v -> { paMarket = it.symbol; fillBirds(list); });
            list.addView(c);
        }
    }

    private ChartController.Focus focusOf(String symbol, String label, PaEngine.Signal s) {
        ChartController.Focus f = new ChartController.Focus();
        f.symbol = symbol; f.tf = s.tf; f.buy = s.isBuy(); f.ready = s.actionable();
        f.entry = s.entry; f.stop = s.stop; f.target = s.target;
        f.validation = s.level; f.validationLabel = "Validation: " + (s.family.equals("Pattern") ? "pattern line" : s.family.equals("Trendline") ? "trendline" : "level");
        f.zoneLow = s.zoneLow; f.zoneHigh = s.zoneHigh;
        f.dcEpoch = s.dc == 2 ? s.dcEpoch : 0; f.dcPrice = s.dcPrice;
        for (double[] ln : s.lines) f.lines.add(ln.clone());
        f.title = label + " " + s.tf + ": " + s.scenario.replaceAll("\\s*\\(.*?\\)\\s*$", "") + (s.actionable() ? "  READY" : "  WATCH");
        f.note = "Invalid if a " + s.tf + " candle closes " + (s.isBuy() ? "below " : "above ") + Fmt.f(s.stop) + (s.dc == 2 ? "   |   double confirmed" : s.dc == 1 ? "   |   2nd confirmation pending" : "");
        return f;
    }

    private void openFocusChart(String symbol, ChartController.Focus f) {
        chartFocus = f;
        Store.setChartSymbol(this, symbol);
        Store.setChartTf(this, f.tf);
        select(6);
    }

    /** a large picture of one setup in a pop-up, for reading it closely */
    private void showBigPa(PaRunner.Item it, PaEngine.Signal s) {
        Candle[] pcs = it.chartFor(s.tf);
        if (pcs == null || pcs.length < 6) return;
        PaChartView pc = new PaChartView(this);
        pc.setData(s, pcs);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(pc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        new AlertDialog.Builder(this).setTitle(it.label + "  " + s.tf + "  " + s.scenario).setView(box).setPositiveButton("Close", null).show();
    }

    private void paCard(LinearLayout list, final PaRunner.Item it, final PaEngine.Signal s, String mem) {
        final boolean buy = s.isBuy(), act = s.actionable() && !s.expired;
        int sideCol = buy ? GREEN : RED;
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(s.scenario, 17, TXT, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);
        head.addView(UiKit.pill(this, " " + (buy ? "▲ BUY" : "▼ SELL") + " ", sideCol, Theme.onColor));
        c.addView(head);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(6), 0, dp(4));
        chips.addView(UiKit.pill(this, " " + s.tf + " chart ", BG, ACCENT));
        chips.addView(UiKit.pill(this, " " + s.family + " ", BG, ACCENT));
        chips.addView(UiKit.pill(this, " " + s.ctx + " ", BG, s.ctx.startsWith("with") ? GREEN : s.ctx.startsWith("against") ? RED : DIM));
        if (s.reversal) chips.addView(UiKit.pill(this, " Reversal ", BG, ACCENT));
        if (s.dc == 2) chips.addView(UiKit.pill(this, " DOUBLE CONFIRMED ", GREEN, Theme.onColor));
        else if (s.dc == 1) chips.addView(UiKit.pill(this, " 2nd confirmation pending ", BG, ACCENT));
        c.addView(chips);
        if (s.expired) {
            c.addView(UiKit.pill(this, "  ENTRY EXPIRED  ", RED, Theme.onColor));
            c.addView(text(s.expiredWhy + " Do not place this order. It stays here until you remove it.", 13, RED, false));
            c.addView(button("Remove this setup", v -> { Store.setSetupFlag(this, 'R', s.id); it.signals.remove(s); fillBirds(listRef(c)); }));
        } else {
            TextView pl = text(s.plan, 13, TXT, false);
            pl.setBackground(UiKit.round(BG, dp(10)));
            pl.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMargins(0, dp(4), 0, dp(6));
            pl.setLayoutParams(plp);
            c.addView(pl);
        }
        String status = s.state.equals(com.smcscanner.engine.mie.Mie.S_ENTRY) ? "At the entry now: place the " + (buy ? "buy" : "sell") + " limit order"
            : act ? "Ready: place a " + (buy ? "buy" : "sell") + " limit order at the entry price"
            : "Watching: no order yet";
        c.addView(text(status, 15, act ? sideCol : TXT, true));
        c.addView(text("Confidence " + s.score + "/5" + (s.dc == 2 ? " (includes the double confirmation)" : "") + "   (" + s.rrBand + ")", 12, DIM, false));
        c.addView(qualityBar(s.score, s.score >= 4 ? GREEN : s.score == 3 ? ACCENT : DIM));
        Candle[] pcs = it.chartFor(s.tf);
        if (pcs != null && pcs.length > 5) {
            PaChartView pc = new PaChartView(this);
            pc.setData(s, pcs);
            pc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
            pc.setOnClickListener(v -> showBigPa(it, s));
            c.addView(pc);
            c.addView(text("Gold = the pattern, trendline or level. Red = invalidation (what you risk). Green = target. Tap the picture to enlarge it.", 11, DIM, false));
        }
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.setPadding(0, dp(10), 0, dp(4));
        nums.addView(tile("ENTRY", Fmt.f(s.entry), TXT));
        nums.addView(tile("STOP", Fmt.f(s.stop), RED));
        nums.addView(tile("TARGET", Fmt.f(s.target), GREEN));
        nums.addView(tile("REWARD", String.format(Locale.US, "%.1fR", s.rr), ACCENT));
        c.addView(nums);
        if (!s.expired) c.addView(liveLine(it.symbol, px -> com.smcscanner.engine.LiveStatus.describe(buy, act, false, s.entry, s.stop, s.target, px)));

        // ---- validation points: what is already true, what is still needed, what kills the idea
        StringBuilder v = new StringBuilder("VALIDATION POINTS\n");
        v.append(s.actionable() ? "✓ " : "○ ").append("Confirmation 1, structure: ").append(s.scenario).append(s.actionable() ? " is in place.\n" : " is forming, not confirmed yet.\n");
        if (s.dc == 2) v.append("✓ Confirmation 2, ").append(s.dcTf).append(" chart: ").append(s.dcText).append(".\n");
        else if (s.dc == 1) v.append("○ Confirmation 2, ").append(s.dcTf).append(" chart: waiting for ").append(s.dcText).append(".\n");
        else v.append("– Confirmation 2: no lower chart available for this setup.\n");
        for (String e : s.evidence) v.append("✓ ").append(e).append('\n');
        if (!act) v.append("○ Not yet: a ").append(s.tf).append(" candle has to close ").append(buy ? "above " : "below ").append(Fmt.f(s.level > 0 ? s.level : s.entry)).append(" and then retest it.\n");
        else v.append("○ Order: ").append(buy ? "buy" : "sell").append(" limit at ").append(Fmt.f(s.entry)).append(" and wait for the retest to be filled.\n");
        v.append("✕ Invalidated if a ").append(s.tf).append(" candle closes ").append(buy ? "below " : "above ").append(Fmt.f(s.stop)).append(" (the stop).\n");
        v.append("★ Proven right at the target ").append(Fmt.f(s.target)).append(" (").append(String.format(Locale.US, "%.1fR", s.rr)).append(").");
        TextView vt = text(v.toString(), 13, TXT, false);
        vt.setBackground(UiKit.round(BG, dp(10)));
        vt.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(0, dp(8), 0, dp(4));
        vt.setLayoutParams(vlp);
        c.addView(vt);
        if (!s.conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder("WARNINGS\n");
            for (String e : s.conflicts) sb.append("⚠ ").append(e).append('\n');
            TextView w = text(sb.toString().trim(), 13, TXT, false);
            w.setBackground(UiKit.round(BG, dp(10)));
            w.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            w.setLayoutParams(lp);
            c.addView(w);
        }
        // ---- the full explanation, shown in full (not behind a button)
        c.addView(UiKit.section(this, "Full explanation", s.explanation, true));
        if (!mem.isEmpty()) {
            TextView mt = text("Memory: " + mem, 12, DIM, false);
            mt.setPadding(0, dp(8), 0, 0);
            c.addView(mt);
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button oc = button("Live chart (" + s.tf + ")", v2 -> openFocusChart(it.symbol, focusOf(it.symbol, it.label, s)));
        oc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(oc);
        Button big = button("Enlarge", v2 -> showBigPa(it, s));
        big.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(big);
        c.addView(btns);
        Button ent = button("I entered this trade", v2 -> askManualEntry(it.symbol, it.label, buy, s.entry, s.stop, s.target, null, s.tf, granOf(s.tf),
            "Price action: " + s.scenario, act ? "QUALIFIED" : "WATCH",
            act ? "Setup status: ready, " + s.score + "/5 checks met." : "Setup status: still being watched, not every condition is met yet. You can log it anyway.", s.id));
        if (!s.expired) c.addView(ent);
        list.addView(c);
    }

    private LinearLayout listRef(View card) { return (LinearLayout) card.getParent(); }

    // ---------- Research Centre ----------
    private View researchTab() {
        LinearLayout p = page();
        p.addView(UiKit.brandRow(this, "Research Centre", "EVIDENCE, NOT OPINION"));
        p.addView(text("Everything here is calculated from recorded setups and Deriv candles. Small samples are marked inconclusive. Replay results are labelled in-sample, validation or out-of-sample; none of this is a promise of future results.", 12, DIM, false));
        final TextView out = text("", 12, TXT, false);
        out.setTypeface(Typeface.MONOSPACE);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(false);

        p.addView(button("Replay the engine on real Deriv history (several minutes)", v -> runJob(busy, out, "Replaying real history for each market. This takes a few minutes; keep the app open...", () -> {
            StringBuilder sb = new StringBuilder("REPLAY ON REAL DERIV HISTORY (no look-ahead; every row labelled by period)\n\n");
            for (com.smcscanner.engine.Scan.Market m : Store.config(this).markets) {
                for (int tier = 0; tier <= (Store.lowerFrames(this) ? 1 : 0); tier++) {
                com.smcscanner.engine.mie.WalkForward.Report rep = MieRunner.walkForward(this, client, m, 4, tier);
                sb.append(m.label).append(tier == 0 ? " (1H cascade)" : " (30m cascade)").append(": ").append(rep.windows).append(" windows, ").append(rep.rows.size()).append(" setups recorded\n");
                if (!rep.note.isEmpty()) sb.append(rep.note).append('\n');
                sb.append("ALL: ").append(rep.all.line()).append('\n');
                for (java.util.Map.Entry<String, com.smcscanner.engine.mie.Kpi> e : rep.bySample.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue().line()).append('\n');
                int i = 1;
                for (com.smcscanner.engine.mie.Kpi k : rep.folds) sb.append("period ").append(i++).append(": ").append(k.line()).append('\n');
                sb.append('\n');
                }
            }
            sb.append("The 15m cascade needs 1m history, which is too short to replay, so it learns from live signals only. Read sample sizes first. Under 30 completed trades is inconclusive.");
            return sb.toString();
        })));
        p.addView(button("Learn recurring market behaviour (discovery)", v -> runJob(busy, out, "Studying 15m history for each market...", () -> {
            StringBuilder sb = new StringBuilder("BEHAVIOUR DISCOVERY (grouped by similarity, then tested on later data)\n\n");
            for (com.smcscanner.engine.Scan.Market m : Store.config(this).markets) sb.append(MieRunner.discover(this, client, m)).append('\n');
            return sb.toString();
        })));
        p.addView(button("Instrument profiles (how each market behaves)", v -> runJob(busy, out, "Building profiles...", () -> {
            StringBuilder sb = new StringBuilder();
            for (com.smcscanner.engine.Scan.Market m : Store.config(this).markets) sb.append(MieRunner.profileText(client, m)).append("\n\n");
            return sb.toString();
        })));
        p.addView(out);
        p.addView(box);
        fillResearch(box);
        return scroll(p);
    }

    private interface Job { String run() throws Exception; }

    private void runJob(final java.util.concurrent.atomic.AtomicBoolean busy, final TextView out, String startMsg, final Job job) {
        if (!busy.compareAndSet(false, true)) { out.setText("Already running..."); return; }
        out.setText(startMsg);
        new Thread(() -> {
            String res;
            try { res = job.run(); } catch (Throwable t) { res = "Could not finish: " + t.getMessage(); }
            final String r = res;
            ui.post(() -> { out.setText(r); busy.set(false); });
        }).start();
    }

    private void fillResearch(LinearLayout box) {
        box.removeAllViews();
        SqliteResearchStore store = SqliteResearchStore.get(this);
        List<com.smcscanner.engine.mie.Row> live = new ArrayList<>();
        for (com.smcscanner.engine.mie.Row r0 : store.live()) if (r0.mode.equals("SMC")) live.add(r0);
        List<com.smcscanner.engine.mie.Row> act = new ArrayList<>();
        for (com.smcscanner.engine.mie.Row r : live) if (r.actionable() && r.mode.equals("SMC")) act.add(r);
        LinearLayout c = card();
        c.addView(text("Live record (since you installed this version)", 16, TXT, true));
        c.addView(text(live.size() + " setups recorded, " + act.size() + " qualified.", 12, DIM, false));
        c.addView(text(com.smcscanner.engine.mie.Kpi.compute(act).line(), 12, TXT, false));
        box.addView(c);
        String[] titles = {"By setup type (zone and higher-timeframe agreement)", "By market", "By reward:risk band", "By entry timeframe", "Counter-trend or with the trend"};
        com.smcscanner.engine.mie.Kpi.Key[] keys = {r -> (r.zone.isEmpty() ? "?" : r.zone) + ", " + r.alignment.toLowerCase(Locale.US), r -> r.label, r -> r.band, r -> r.execTf, r -> r.counterTrend ? "counter-trend" : "trend-aligned"};
        for (int i = 0; i < titles.length; i++) {
            if (act.isEmpty()) break;
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, com.smcscanner.engine.mie.Kpi> e : com.smcscanner.engine.mie.Kpi.groupBy(act, keys[i]).entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue().line()).append(i == 0 ? "\n" + typeVerdict(e.getValue()) : "").append("\n\n");
            box.addView(UiKit.section(this, titles[i], sb.toString().trim(), false));
        }
        StringBuilder hist = new StringBuilder();
        int shown = 0;
        for (int i = live.size() - 1; i >= 0 && shown < 30; i--, shown++) {
            com.smcscanner.engine.mie.Row r = live.get(i);
            hist.append(new java.text.SimpleDateFormat("d MMM HH:mm", Locale.US).format(new java.util.Date(r.ts * 1000))).append("  ").append(r.label).append("  ").append(r.side).append("  ").append(r.state)
                .append(r.actionable() ? "  " + String.format(Locale.US, "%.1fR", r.rr) + "  " + r.outcome : "").append(r.reasons.isEmpty() ? "" : "\n    " + r.reasons).append("\n");
        }
        box.addView(UiKit.section(this, "Setup history (accepted, watched, rejected, expired)", hist.length() == 0 ? "Nothing recorded yet. Press Update now on the Advisor tab." : hist.toString().trim(), false));

        // strategy versions
        LinearLayout v = card();
        v.addView(text("Strategy versions", 16, TXT, true));
        java.util.List<com.smcscanner.engine.mie.Versions.Version> vs;
        com.smcscanner.engine.mie.Versions.ensure(store);
        vs = com.smcscanner.engine.mie.Versions.list(store);
        com.smcscanner.engine.mie.Versions.Version active = com.smcscanner.engine.mie.Versions.active(store);
        for (final com.smcscanner.engine.mie.Versions.Version x : vs) {
            boolean isActive = active != null && active.id.equals(x.id);
            v.addView(text((isActive ? "ACTIVE  " : "") + x.id + "  " + x.name + "  [" + x.status + "]\n" + x.changelog + "\n" + x.params, 12, isActive ? TXT : DIM, false));
            if (!isActive) v.addView(button("Approve and make active: " + x.id, b -> {
                com.smcscanner.engine.mie.Versions.approve(store, x.id, MieRunner.today());
                MieRunner.prepare(this);
                fillResearch(box);
            }));
        }
        v.addView(button("Propose a challenger: no counter-trend setups", b -> {
            java.util.Map<String, String> pr = new java.util.LinkedHashMap<>();
            pr.put("allowCountertrend", "false"); pr.put("strongNeedsAligned", "true");
            com.smcscanner.engine.mie.Versions.propose(store, "No counter-trend", pr, "Test: only trade with the higher-timeframe direction.", MieRunner.today());
            fillResearch(box);
        }));
        v.addView(button("Roll back to the previous version", b -> {
            com.smcscanner.engine.mie.Versions.rollback(store);
            MieRunner.prepare(this);
            fillResearch(box);
        }));
        v.addView(text("A new version never becomes active by itself: replay it first, compare, then approve it here.", 11, DIM, false));
        box.addView(v);
    }

    // ---------- Ideas ----------
    private View ideasTab() {
        LinearLayout p = page();
        final TextView status = text(Store.lastStatus(this), 13, DIM, false);
        p.addView(text("Ideas", 24, TXT, true));
        p.addView(status);
        final Button scanBtn = button("Scan now", null);
        p.addView(scanBtn);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        p.addView(list);
        scanBtn.setOnClickListener(v -> {
            scanBtn.setEnabled(false);
            status.setText("Scanning...");
            new Thread(() -> {
                String s;
                try { s = Scanner.runOnce(this, client); } catch (Throwable t) { s = "Scan failed: " + t.getMessage(); }
                final String res = s;
                ui.post(() -> { scanBtn.setEnabled(true); status.setText(res); fillIdeas(list); });
            }).start();
        });
        fillIdeas(list);
        refresher = new Runnable() {
            @Override public void run() { status.setText(Store.lastStatus(MainActivity.this)); fillIdeas(list); ui.postDelayed(this, 5000); }
        };
        ui.postDelayed(refresher, 5000);
        return scroll(p);
    }

    private boolean inDetail;

    @Override
    public void onBackPressed() {
        if (drawerOpen) { setDrawer(false); return; }
        if (inDetail) { select(tab); return; }
        super.onBackPressed();
    }

    private static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private TextView heading(String s) {
        TextView t = text(s, 11, DIM, true);
        t.setPadding(dp(2), dp(6), 0, dp(6));
        return t;
    }

    /** idea timeframe, entry-zone timeframe, then the finer ones it zooms into */
    private List<String> chainFor(Lifecycle.Tracked t) {
        List<String> chain = new ArrayList<>();
        for (Scan.Pair p : Scan.Config.defaults().pairs) {
            if (!p.setup.equals(t.tf)) continue;
            chain.add(p.setup);
            if (p.entry != null) chain.add(p.entry);
            chain.addAll(p.refine);
        }
        if (chain.isEmpty()) { chain.add(t.tf); if (t.ltfTf != null) chain.add(t.ltfTf); }
        return chain;
    }

    private View ideaCard(final Lifecycle.Tracked t) {
        Setup s = t.entry != null ? t.entry : t.setup;
        boolean buy = t.setup.isBuy();
        int sideColor = buy ? GREEN : RED;
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.pill(this, buy ? "BUY" : "SELL", sideColor, Theme.onColor));
        TextView name = text("  " + t.label, 16, TXT, true);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (t.breakdown != null && t.breakdown.grade != null) top.addView(UiKit.pill(this, "Grade " + t.breakdown.grade, Theme.grey, TXT));
        c.addView(top);

        List<String> chain = chainFor(t);
        int confirmed;
        String badge; int badgeColor, badgeText = Theme.onColor;
        if (t.entry != null) {
            int at = t.finalTf != null ? chain.indexOf(t.finalTf) : -1;
            confirmed = at >= 0 ? at + 1 : chain.size();
            badge = "ENTRY"; badgeColor = sideColor;
        } else if (t.status.equals("armed") && t.coarseSince > 0) { confirmed = 2; badge = "Zooming in"; badgeColor = Theme.amber; }
        else if (t.status.equals("armed")) { confirmed = 2; badge = "Watching"; badgeColor = ACCENT; }
        else if (t.status.equals("waiting")) { confirmed = 1; badge = "Waiting"; badgeColor = Theme.grey; badgeText = TXT; }
        else { confirmed = 0; badge = "Finished"; badgeColor = Theme.grey; badgeText = TXT; }
        List<String> chips = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            String l = chain.get(i);
            if (i == 0) l += " idea";
            else if (i == 1) l += " zone";
            else if (t.entry != null && i == confirmed - 1) l += " entry";
            chips.add(l);
        }
        c.addView(UiKit.pathChips(this, chips, confirmed));

        if (t.entry != null) {
            double risk = Math.abs(s.entry - s.sl), reward = Math.abs(s.tp - s.entry);
            double rr = t.breakdown != null ? t.breakdown.levels.rr1 : (risk > 0 ? reward / risk : 0);
            c.addView(UiKit.keyGrid(this, new String[]{Fmt.f(s.entry), Fmt.f(s.sl), Fmt.f(s.tp), Fmt.d1(rr) + "R"}, new String[]{"ENTRY", "STOP", "TARGET", "REWARD"}));
            double rm = t.breakdown != null ? t.breakdown.riskMoney : 0;
            if (rm > 0) c.addView(UiKit.moneyRow(this, "Risk $" + String.format(Locale.US, "%.2f", rm), "Gain $" + String.format(Locale.US, "%.2f", rm * rr)));
            else c.addView(UiKit.moneyRow(this, "Risk " + Fmt.f(risk) + " pts", "Gain " + Fmt.f(reward) + " pts"));
        } else {
            c.addView(text("Zone " + Fmt.f(t.setup.zoneLow) + " - " + Fmt.f(t.setup.zoneHigh) + "    Target " + Fmt.f(t.setup.tp), 13, DIM, false));
        }
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(8), 0, 0);
        foot.addView(UiKit.pill(this, badge, badgeColor, badgeText));
        foot.addView(text("   " + when(t.createdAt * 1000), 12, DIM, false));
        c.addView(foot);
        c.setOnClickListener(v -> showIdeaDetail(t));
        return c;
    }

    private void fillIdeas(LinearLayout list) {
        list.removeAllViews();
        Lifecycle.State st = Store.loadState(this);
        List<Lifecycle.Tracked> ts = new ArrayList<>(st.tracked.values());
        Collections.sort(ts, new Comparator<Lifecycle.Tracked>() {
            @Override public int compare(Lifecycle.Tracked a, Lifecycle.Tracked b) { return Long.compare(b.createdAt, a.createdAt); }
        });

        // today at a glance
        long midnight = startOfToday();
        int entriesToday = 0, open = 0;
        double closedR = 0;
        for (Store.Alert a : Store.alerts(this)) if (a.time >= midnight && "entry".equals(a.kind)) entriesToday++;
        for (Manage.Trade tr : Store.loadTrades(this)) {
            if (tr.status.equals("open")) open++;
            else if (tr.lastEpoch * 1000 >= midnight) closedR += tr.resultR;
        }
        LinearLayout sum = card();
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        boolean scanning = ScannerService.running || Store.scanningWanted(this);
        badges.addView(UiKit.pill(this, scanning ? "Scanning" : "Scanning OFF", scanning ? GREEN : Theme.grey, scanning ? Theme.onColor : TXT));
        if (Store.sniper(this)) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.setMargins(dp(8), 0, 0, 0);
            badges.addView(UiKit.pill(this, "Master strategy", ACCENT, Theme.onColor), slp);
        }
        String paused = Store.pauseReason(this);
        if (paused != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMargins(dp(8), 0, 0, 0);
            badges.addView(UiKit.pill(this, "Alerts paused", Theme.amber, Theme.onColor), plp);
        }
        sum.addView(badges);
        if (paused != null) sum.addView(text(paused + ". New-setup alerts are held back but still saved in the Alerts tab.", 12, DIM, false));
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.setPadding(0, dp(12), 0, 0);
        String rText = (closedR >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", closedR) + "R";
        String[][] cells = {{String.valueOf(entriesToday), "entry alerts today"}, {String.valueOf(open), open == 1 ? "open trade" : "open trades"}, {rText, "closed today"}};
        for (int i = 0; i < cells.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            TextView v = text(cells[i][0], 22, i == 2 ? (closedR > 0 ? GREEN : closedR < 0 ? RED : TXT) : TXT, true);
            v.setGravity(Gravity.CENTER);
            cell.addView(v);
            TextView n = text(cells[i][1], 11, DIM, false);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            nums.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        sum.addView(nums);
        list.addView(sum);

        long dayAgo = System.currentTimeMillis() / 1000 - 86400;
        Lifecycle.Tracked best = null;
        for (Lifecycle.Tracked t : ts) if (t.entry != null && t.createdAt >= dayAgo) { best = t; break; }
        if (best != null) { list.addView(heading("BEST SETUP RIGHT NOW")); list.addView(ideaCard(best)); }
        boolean headed = false;
        for (Lifecycle.Tracked t : ts) {
            if (t == best || t.entry != null || !(t.status.equals("waiting") || t.status.equals("armed"))) continue;
            if (!headed) { list.addView(heading("WATCHING")); headed = true; }
            list.addView(ideaCard(t));
        }
        headed = false;
        int shown = 0;
        for (Lifecycle.Tracked t : ts) {
            if (t == best || (t.entry == null && (t.status.equals("waiting") || t.status.equals("armed")))) continue;
            if (shown >= 12) break;
            if (!headed) { list.addView(heading("EARLIER")); headed = true; }
            list.addView(ideaCard(t));
            shown++;
        }
        if (ts.isEmpty()) {
            list.addView(text("No ideas yet. Press Scan now, or switch scanning on in Settings. When your strategy validates on a chart it appears here and you get a notification.", 14, DIM, false));
        }
    }

    /** full-screen trade detail: key numbers first, then foldable sections */
    private void showIdeaDetail(final Lifecycle.Tracked t) {
        inDetail = true;
        if (refresher != null) ui.removeCallbacks(refresher);
        refresher = null;
        content.removeAllViews();
        LinearLayout p = page();
        p.addView(button("\u2039 Back", v -> select(tab)));
        p.addView(ideaCard(t));
        // the card above opens this screen again when tapped: make it inert here
        p.getChildAt(1).setOnClickListener(null);
        List<String[]> secs = Breakdowns.sections(ideaDetails(t));
        int idx = 0;
        for (String[] sec : secs) {
            String title = sec[0].isEmpty() ? "Summary" : sec[0];
            boolean open = idx < 2;
            p.addView(UiKit.section(this, title, sec[1], open));
            idx++;
        }
        if (t.entry != null) {
            Button b = button("I entered this trade", v -> askEntryPrice(t));
            p.addView(b);
        }
        content.addView(scroll(p));
    }

    private String ideaDetails(Lifecycle.Tracked t) {
        if (t.breakdown != null) return Breakdowns.fullText(t.breakdown, t.label, t.entry != null ? t.entry.entry : t.setup.entry);
        StringBuilder sb = new StringBuilder();
        sb.append(t.setup.side).append(" idea on ").append(t.tf).append(" - ").append(t.label).append("\n\n");
        for (String r : t.setup.reasons) sb.append("[OK] ").append(r).append("\n");
        sb.append("\nZone (").append(t.setup.zoneType).append("): ").append(Fmt.f(t.setup.zoneLow)).append(" - ").append(Fmt.f(t.setup.zoneHigh));
        sb.append("\nIdea target: ").append(Fmt.f(t.setup.tp)).append("\nIdea stop: ").append(Fmt.f(t.setup.sl));
        sb.append("\n\nThe full trade breakdown (entry, stop, targets, reasons, risks) is created when the entry appears on ").append(t.ltfTf != null ? t.ltfTf : t.tf).append(".");
        sb.append("\nConfluences: ").append(t.setup.score).append("/").append(t.setup.maxScore).append(" (not a win probability)");
        return sb.toString();
    }

    // ---------- log a trade + Trades tab ----------
    private void askEntryPrice(final Lifecycle.Tracked t) {
        final Setup s = t.entry;
        final EditText in = new EditText(this);
        in.setText(Fmt.f(s.entry));
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text("The price you actually entered at (the planned entry is filled in):", 13, Color.BLACK, false));
        box.addView(in);
        new AlertDialog.Builder(this).setTitle("Log this trade").setView(box)
            .setPositiveButton("Start managing", (d, w) -> {
                double px;
                try { px = Double.parseDouble(in.getText().toString().trim()); } catch (Exception e) { px = s.entry; }
                boolean buy = s.isBuy();
                if (buy ? !(px > s.sl && px < s.tp) : !(px < s.sl && px > s.tp)) {
                    showText("Entry price not accepted", "The entry must sit between your stop " + Fmt.f(s.sl) + " and your target " + Fmt.f(s.tp) + ".");
                    return;
                }
                Double tp2 = t.breakdown != null ? t.breakdown.levels.tp2 : null;
                long gran = 0;
                String ft = t.finalTf != null ? t.finalTf : t.ltfTf;
                for (Scan.Timeframe tf : Scan.Config.defaults().timeframes) if (tf.label.equals(ft)) gran = tf.granularity;
                Store.addTrade(this, Manage.create(s, t.market, t.label, t.tf, t.finalTf != null ? t.finalTf : t.ltfTf, gran, px, tp2, System.currentTimeMillis() / 1000, Store.riskMoney(this)));
                select(5);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private View tradesTab() {
        LinearLayout p = page();
        p.addView(text("Trades", 24, TXT, true));
        p.addView(text("Open an idea that has an ENTRY alert, tap it, and press 'I entered this trade'. The app then tells you when to move your stop to break-even, where to trail it, when to take profit and when to adjust the target. It sends a notification each time.", 13, DIM, false));
        final Button upd = button("Update now", null);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        upd.setOnClickListener(v -> {
            upd.setEnabled(false);
            new Thread(() -> {
                try { TradeTracker.update(this, client); } catch (Throwable ignored) { }
                ui.post(() -> { upd.setEnabled(true); fillTrades(list); });
            }).start();
        });
        p.addView(button("Log a trade I took (any market, any price)", v -> askFreeTrade()));
        p.addView(upd);
        p.addView(list);
        fillTrades(list);
        refresher = new Runnable() { @Override public void run() { fillTrades(list); ui.postDelayed(this, 5000); } };
        ui.postDelayed(refresher, 5000);
        return scroll(p);
    }

    private static final int REQ_EXPORT = 77, REQ_SOUND = 78;

    /** every entry alert, tracked automatically whether or not you took it, with the export for the Excel journal */
    private View signalCard() {
        List<com.smcscanner.engine.SignalLog.Record> recs = Store.loadSignals(this);
        com.smcscanner.engine.SignalLog.Summary all = com.smcscanner.engine.SignalLog.summary(recs, 0);
        com.smcscanner.engine.SignalLog.Summary wk = com.smcscanner.engine.SignalLog.summary(recs, System.currentTimeMillis() / 1000 - 7 * 86400L);
        LinearLayout sc = card();
        sc.addView(text("SIGNAL RECORD", 11, DIM, true));
        if (recs.isEmpty()) {
            sc.addView(text("Every entry alert is recorded here and tracked automatically: did it hit its target or its stop? Nothing recorded yet.", 13, DIM, false));
            return sc;
        }
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.setPadding(0, dp(8), 0, 0);
        String[][] cells = {{String.valueOf(all.alerts), "alerts"}, {all.resolved > 0 ? Fmt.d1(all.winRate) + "%" : "-", "hit target"},
            {(all.totalR >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", all.totalR) + "R", "total"}, {String.valueOf(all.open), "still open"}};
        for (int i = 0; i < cells.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            TextView v = text(cells[i][0], 18, i == 2 ? (all.totalR > 0 ? GREEN : all.totalR < 0 ? RED : TXT) : TXT, true);
            v.setGravity(Gravity.CENTER);
            cell.addView(v);
            TextView n = text(cells[i][1], 11, DIM, false);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            nums.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        sc.addView(nums);
        sc.addView(text("Last 7 days: " + wk.alerts + " alerts, " + wk.wins + " hit the target, " + wk.losses + " hit the stop"
            + (wk.resolved > 0 ? " (" + Fmt.d1(wk.winRate) + "%)" : "") + ". " + all.noFill + " never filled.", 12, DIM, false));
        sc.addView(text("This follows every alert on the chart, whether or not you took the trade. Small samples are not a forecast.", 12, DIM, false));
        sc.addView(button("Export Excel journal", v -> shareJournal()));
        sc.addView(button("Export raw rows (CSV)", v -> exportSignals()));
        return sc;
    }

    /** builds the Excel workbook (charts, weekly/monthly tables, search) and opens the share sheet */
    private void shareJournal() {
        showText("Building your journal", "This takes a few seconds. The share sheet will open when it is ready.");
        new Thread(() -> {
            try {
                final java.io.File f = JournalExport.build(this);
                runOnUiThread(() -> {
                    try { startActivity(JournalExport.shareIntent(this, f)); }
                    catch (Exception e) { showText("Could not share", "No app could take the file: " + e.getMessage()); }
                });
            } catch (Exception e) {
                runOnUiThread(() -> showText("Could not build the journal", String.valueOf(e.getMessage())));
            }
        }).start();
    }

    private void exportSignals() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "SMC_Signal_Log.csv");
        try { startActivityForResult(i, REQ_EXPORT); }
        catch (Exception e) { showText("Could not open the save screen", "Your phone has no file picker to save with. Install Files by Google and try again."); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SOUND && res == RESULT_OK && data != null) {
            Uri u = (Uri) data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (u == null) Store.setFibSound(this, "", "Phone default");
            else {
                String name = "Chosen sound";
                try { android.media.Ringtone rt = android.media.RingtoneManager.getRingtone(this, u); if (rt != null) name = rt.getTitle(this); } catch (Exception ignored) { }
                Store.setFibSound(this, u.toString(), name);
            }
            if (tab == 1) select(1);
            return;
        }
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            String csv = com.smcscanner.engine.SignalLog.toCsv(Store.loadSignals(this));
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) { out.write(csv.getBytes("UTF-8")); }
            showText("Saved", "Your signal record was saved. Open the SMC Journal workbook on your computer, go to the Log sheet, and paste the rows in (see the Guide sheet).");
        } catch (Exception e) {
            showText("Could not save", "Saving failed: " + e.getMessage());
        }
    }

    private void fillTrades(LinearLayout list) {
        list.removeAllViews();
        List<Manage.Trade> ts = Store.loadTrades(this);
        list.addView(signalCard());
        Journal.Summary js = Journal.summary(ts, 0);
        if (js.closed > 0) {
            LinearLayout rc = card();
            rc.addView(text("YOUR RESULTS", 11, DIM, true));
            LinearLayout nums = new LinearLayout(this);
            nums.setOrientation(LinearLayout.HORIZONTAL);
            nums.setPadding(0, dp(8), 0, 0);
            String[][] cells = {{String.valueOf(js.closed), "closed"}, {js.wins + js.losses > 0 ? Fmt.d1(js.winRate) + "%" : "-", "won"},
                {(js.totalR >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", js.totalR) + "R", "total"}, {(js.avgR >= 0 ? "+" : "") + String.format(Locale.US, "%.2f", js.avgR) + "R", "average"}};
            for (int i = 0; i < cells.length; i++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                TextView v = text(cells[i][0], 18, i == 2 ? (js.totalR > 0 ? GREEN : js.totalR < 0 ? RED : TXT) : TXT, true);
                v.setGravity(Gravity.CENTER);
                cell.addView(v);
                TextView n = text(cells[i][1], 11, DIM, false);
                n.setGravity(Gravity.CENTER);
                cell.addView(n);
                nums.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            rc.addView(nums);
            rc.addView(text(js.wins + " won, " + js.losses + " lost, " + js.breakeven + " at breakeven. Best " + String.format(Locale.US, "%.1f", js.bestR) + "R, worst " + String.format(Locale.US, "%.1f", js.worstR) + "R. This is your own record, not a forecast.", 12, DIM, false));
            list.addView(rc);
        }
        if (ts.isEmpty()) list.addView(text("No trades logged yet.", 14, DIM, false));
        for (final Manage.Trade t : ts) {
            LinearLayout c = card();
            boolean buy = t.isBuy();
            c.addView(text((buy ? "BUY  " : "SELL  ") + t.label + "  " + t.ideaTf + " > " + t.entryTf, 17, buy ? GREEN : RED, true));
            c.addView(text(Manage.status(t), 13, TXT, false));
            if (t.origin != null && !t.origin.isEmpty())
                c.addView(text("From: " + t.origin + ("WATCH".equals(t.originState) ? "  (entered before every condition was met)" : "MANUAL".equals(t.originState) ? "" : ""), 11, DIM, false));
            boolean open = t.status.equals("open");
            c.addView(text("Entry " + Fmt.f(t.entry) + (open ? "   Price " + Fmt.f(t.lastPrice) : "   Exit " + Fmt.f(t.exitPrice)), 13, DIM, false));
            if (open) {
                c.addView(text("Your stop should be at  " + Fmt.f(t.stop), 15, ACCENT, true));
                c.addView(text(t.tp != null ? "Your target should be at  " + Fmt.f(t.tp) : "No target left: trail the stop", 14, ACCENT, false));
                if (!t.log.isEmpty()) c.addView(text("Latest: " + t.log.get(t.log.size() - 1), 12, DIM, false));
            }
            c.setOnClickListener(v -> showTradeDialog(t));
            list.addView(c);
        }
    }

    private void showTradeDialog(final Manage.Trade t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.side).append(" ").append(t.label).append("  ").append(t.ideaTf).append(" > ").append(t.entryTf).append("\n\n");
        sb.append("Entry ").append(Fmt.f(t.entry)).append("\nOriginal stop ").append(Fmt.f(t.stop0)).append("   Original target ").append(Fmt.f(t.tp1)).append("\n");
        sb.append("Stop now ").append(Fmt.f(t.stop)).append("   Target now ").append(t.tp != null ? Fmt.f(t.tp) : "none (trail)").append("\n");
        sb.append("Best so far ").append(Fmt.d1(t.maxR)).append("R\n\n").append(Manage.status(t)).append("\n\nHISTORY\n");
        for (int i = t.log.size() - 1; i >= 0; i--) sb.append("- ").append(t.log.get(i)).append("\n");
        sb.append("\nThe app assumes you follow its stop moves. Move your real stop on your trading platform when it says so.");
        if (t.status.equals("open")) {
            showTextWith("Trade", sb.toString(), "I closed this trade", () -> {
                List<Manage.Trade> all = Store.loadTrades(this);
                for (Manage.Trade x : all) if (x.id.equals(t.id) && x.status.equals("open")) { x.status = "closed"; x.closeReason = "closed by you"; x.exitPrice = x.lastPrice; x.resultR = x.lastR; x.log.add("Closed by you at " + Fmt.f(x.lastPrice)); }
                Store.mergeTrades(this, all);
                select(5);
            });
        } else {
            showTextWith("Trade", sb.toString(), "Remove from list", () -> { Store.removeTrade(this, t.id); select(5); });
        }
    }

    // ---------- Chart (live) ----------
    private ChartController chartCtl;

    private View chartTab() {
        final Scan.Config cfg = Scan.Config.defaults();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(6), dp(6), dp(6), 0);
        final ChartController.Focus startFocus = chartFocus;
        chartFocus = null;
        List<String> ms = new ArrayList<>();
        int msel = 0;
        for (int i = 0; i < cfg.markets.size(); i++) { ms.add(cfg.markets.get(i).label); if (cfg.markets.get(i).symbol.equals(Store.chartSymbol(this))) msel = i; }
        final Spinner msp = spinner(ms, msel);
        final ChartView chart = new ChartView(this);
        chartCtl = new ChartController(this, chart);
        chartCtl.focus = startFocus;
        final String[] tfLabels = {"1m", "5m", "15m", "30m", "1H", "4H", "1D"};
        final TextView[] tfBtns = new TextView[tfLabels.length];
        final String[] curTf = {Store.chartTf(this)};

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(msp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button("Latest", v -> { chart.renderer.resetView(); chart.invalidate(); }));
        p.addView(row);
        // a banner when one signal is highlighted
        final LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackground(UiKit.round(CARD, dp(10)));
        banner.setPadding(dp(10), dp(8), dp(10), dp(8));
        final TextView bt = text("", 12, TXT, true);
        banner.addView(bt);
        final Button showAll = button("Show everything on this chart", null);
        banner.addView(showAll);
        banner.setVisibility(startFocus != null ? View.VISIBLE : View.GONE);
        if (startFocus != null) bt.setText("Highlighting one signal: " + startFocus.title + "\nEntry " + Fmt.f(startFocus.entry) + "   Invalidation " + Fmt.f(startFocus.stop) + (startFocus.target > 0 ? "   Target " + Fmt.f(startFocus.target) : "") + "\n" + startFocus.note);
        p.addView(banner);
        // layer switches: tap = show/hide, long-press = show only that layer
        final String[] lk = {"setup", "pasetup", "bos", "ob", "fvg", "liq", "trend", "sr", "pat", "ideas"};
        final String[] ln = {"SMC setup", "Bird's Eye setup", "BOS/CHoCH", "Order blocks", "FVG", "Liquidity", "Trendlines", "S/R", "Patterns", "Ideas"};
        final TextView[] chips = new TextView[lk.length];
        final Runnable applyLayers = () -> {
            chartCtl.showSetup = Store.chartLayer(this, "setup"); chartCtl.showPaSetup = Store.chartLayer(this, "pasetup"); chartCtl.showBos = Store.chartLayer(this, "bos");
            chartCtl.showOb = Store.chartLayer(this, "ob"); chartCtl.showFvg = Store.chartLayer(this, "fvg");
            chartCtl.showLiq = Store.chartLayer(this, "liq"); chartCtl.showTrend = Store.chartLayer(this, "trend");
            chartCtl.showZones = Store.chartLayer(this, "sr"); chartCtl.showPatterns = Store.chartLayer(this, "pat");
            chartCtl.showOverlay = Store.chartLayer(this, "ideas");
            for (int k = 0; k < lk.length; k++) {
                boolean on = Store.chartLayer(this, lk[k]);
                chips[k].setBackground(UiKit.round(on ? ACCENT : CARD, dp(14)));
                chips[k].setTextColor(on ? Theme.onColor : DIM);
            }
            chartCtl.layersChanged();
        };
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(4), 0, dp(4));
        for (int k = 0; k < lk.length; k++) {
            final String key = lk[k];
            TextView t = text(ln[k], 12, DIM, true);
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(6), 0);
            t.setLayoutParams(clp);
            t.setOnClickListener(v -> { Store.setChartLayer(this, key, !Store.chartLayer(this, key)); ((Runnable) v.getTag()).run(); });
            t.setOnLongClickListener(v -> { for (String o2 : lk) Store.setChartLayer(this, o2, o2.equals(key)); ((Runnable) v.getTag()).run(); return true; });
            t.setTag(applyLayers);
            chips[k] = t;
            chipRow.addView(t);
        }
        TextView allOn = text("Show all", 12, ACCENT, true);
        allOn.setPadding(dp(8), dp(7), dp(8), dp(7));
        allOn.setOnClickListener(v -> { for (String o2 : lk) Store.setChartLayer(this, o2, true); applyLayers.run(); });
        chipRow.addView(allOn);
        TextView allOff = text("Hide all", 12, ACCENT, true);
        allOff.setPadding(dp(8), dp(7), dp(8), dp(7));
        allOff.setOnClickListener(v -> { for (String o2 : lk) Store.setChartLayer(this, o2, false); applyLayers.run(); });
        chipRow.addView(allOff);
        final TextView notesChip = text(Store.chartNotes(this) ? "Notes: on" : "Notes: off", 12, ACCENT, true);
        notesChip.setPadding(dp(8), dp(7), dp(8), dp(7));
        notesChip.setOnClickListener(v -> {
            boolean nv = !Store.chartNotes(this);
            Store.setChartNotes(this, nv);
            notesChip.setText(nv ? "Notes: on" : "Notes: off");
            chartCtl.layersChanged();
        });
        chipRow.addView(notesChip);
        TextView keyView = text("Key view", 12, ACCENT, true);
        keyView.setPadding(dp(8), dp(7), dp(8), dp(7));
        keyView.setOnClickListener(v -> { for (String o2 : lk) Store.setChartLayer(this, o2, !o2.equals("ideas")); applyLayers.run(); });
        chipRow.addView(keyView);
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chipRow);
        p.addView(chipScroll);
        TextView hint = text("Tap a layer to show or hide it. Hold a layer to see only that one (for example, only order blocks). \"Key view\" resets to the key layers.", 11, DIM, false);
        hint.setPadding(dp(6), 0, dp(6), dp(2));
        p.addView(hint);

        final Runnable load = () -> {
            Scan.Market m = cfg.markets.get(msp.getSelectedItemPosition());
            if (chartCtl.focus != null && !chartCtl.focus.symbol.equals(m.symbol)) { chartCtl.focus = null; banner.setVisibility(View.GONE); }
            long g = 300;
            for (Scan.Timeframe t : cfg.timeframes) if (t.label.equals(curTf[0])) g = t.granularity;
            Store.setChartSymbol(this, m.symbol);
            Store.setChartTf(this, curTf[0]);
            chartCtl.start(m.symbol, g, m.label + "  " + curTf[0]);
        };
        LinearLayout tfRow = new LinearLayout(this);
        tfRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < tfLabels.length; i++) {
            final String lab = tfLabels[i];
            TextView t = text(lab, 14, lab.equals(curTf[0]) ? ACCENT : DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            tfBtns[i] = t;
            t.setOnClickListener(v -> {
                curTf[0] = lab;
                for (int k = 0; k < tfBtns.length; k++) tfBtns[k].setTextColor(tfLabels[k].equals(lab) ? ACCENT : DIM);
                load.run();
            });
            tfRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        p.addView(tfRow);
        p.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        showAll.setOnClickListener(v -> { chartCtl.focus = null; banner.setVisibility(View.GONE); chartCtl.layersChanged(); });
        applyLayers.run();
        p.addView(text("Layers: tap to show or hide; long-press one to show only that layer (for example only order blocks). Tap a trade box or idea zone to see its BOS/CHoCH, liquidity and order block. Drag to scroll, pinch to zoom, long-press for the crosshair, double-tap for the latest candle", 11, DIM, false));
        msp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { if (first) { first = false; } load.run(); }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        return p;
    }

    // ---------- Alerts ----------
    private View alertsTab() {
        LinearLayout p = page();
        p.addView(text("Alerts", 24, TXT, true));
        List<Store.Alert> as = Store.alerts(this);
        if (as.isEmpty()) p.addView(text("No alerts yet.", 14, DIM, false));
        for (final Store.Alert a : as) {
            LinearLayout c = card();
            c.addView(text(a.title, 15, TXT, true));
            c.addView(text(a.body, 13, DIM, false));
            c.addView(text(when(a.time), 12, DIM, false));
            c.setOnClickListener(v -> showText(a.title, a.text));
            p.addView(c);
        }
        if (!as.isEmpty()) p.addView(button("Clear history", v -> { Store.clearAlerts(this); select(7); }));
        return scroll(p);
    }

    // ---------- Backtest ----------
    // ---------- Fib pullback strategy (H4 bias + M15 double confirmation) ----------
    private int fibMarket = 0;
    private boolean fibH4View = false;

    private View fibTab() {
        final Scan.Config cfg = Store.config(this);
        LinearLayout p = page();
        p.addView(UiKit.brandRow(this, "Fib Pullback", "H4 BIAS + M15 DOUBLE CONFIRMATION"));
        p.addView(UiKit.section(this, "How this strategy works (in words)",
            "1. Trend. On the H4 (the D1 is a second opinion) mark the swing highs and swing lows. The market is bullish when the last break of structure was up: a candle CLOSED above the previous swing high (bearish is the mirror).\n\n"
            + "2. First fib. After that break, draw a Fibonacci on the leg: 0% where the leg started (the swing low), 100% at the current high. Wait for price to pull back below the 50% level. The 30% level is a deeper pullback that works in some cases but is not guaranteed to happen.\n\n"
            + "3. Double confirmation on the M15. Once price is in that zone, follow the same rules on the M15: the first break of structure, then a second one (another higher high), then a third higher high. Three in a row.\n\n"
            + "4. Second fib. Draw a new Fibonacci on that M15 impulse: 100% at the current high, 0% at the high that made the first break. Wait for price to dip back to the 50% level (or 30%). The order goes there, the stop on the 0%, and the target on the M15 100% for a faster exit or the H4 100% for a longer trade.\n\n"
            + "Sells are the mirror image.\n\n"
            + "How the app makes it sturdier: the H4 leg must be big enough to matter; a pullback that closes through the leg's 0% cancels the idea; the M15 breaks must be real steps (not tiny wiggles); an impulse smaller than 2 ATR is ignored as noise; a small buffer sits beyond the stop; D1 agreement, a healthy pullback depth, both fibs lining up and a signal candle at the entry all add to a score; and a setup that ran to its target without filling is dropped. Every setup is recorded and the Lab shows how this strategy has actually ended.\n\n"
            + "My reading of one line: 'the 0% at the HH that made the first BoS' is taken as the high that the first break produced (the level the second break closes through). If you meant the swing low under it, switch 'M15 fib 0% at' below to the base low (wider stop, safer).", false));

        // ---- settings
        LinearLayout set = card();
        set.addView(text("Settings", 16, TXT, true));
        CheckBox on = new CheckBox(this);
        on.setText("Fib engine on (scans, alerts and records trades)");
        on.setTextColor(TXT);
        on.setChecked(Store.modeFib(this));
        on.setOnCheckedChangeListener((b, v) -> Store.setModeFib(this, v));
        set.addView(on);
        set.addView(text("Order goes at the M15 fib level", 12, DIM, false));
        final int[] ent = {Store.fibEntry(this) == 30 ? 1 : 0};
        set.addView(singleChips(new String[]{"50% (standard)", "30% (deeper pullback)"}, ent, () -> Store.setFibEntry(this, ent[0] == 1 ? 30 : 50)));
        set.addView(text("Target used for alerts and Lab results", 12, DIM, false));
        final int[] tg = {Store.fibTargetH4(this) ? 1 : 0};
        set.addView(singleChips(new String[]{"M15 100% (quick)", "H4 100% (longer)"}, tg, () -> Store.setFibTargetH4(this, tg[0] == 1)));
        set.addView(text("M15 fib 0% at", 12, DIM, false));
        final int[] bs = {Store.fibBaseLow(this) ? 1 : 0};
        set.addView(singleChips(new String[]{"First-break high (as written)", "Base low (wider, safer)"}, bs, () -> Store.setFibBaseLow(this, bs[0] == 1)));
        // sound
        set.addView(text("Alert sound for Fib setups (separate from every other alert)", 12, DIM, false));
        set.addView(text("Now: " + Store.fibSoundName(this), 14, TXT, true));
        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        Button choose = button("Choose sound", v -> {
            Intent it = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
            it.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION | android.media.RingtoneManager.TYPE_ALARM);
            it.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            it.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
            it.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Fib alert sound");
            String cur = Store.fibSound(this);
            if (!cur.isEmpty()) it.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
            try { startActivityForResult(it, REQ_SOUND); } catch (Exception e) { showText("Sound picker", "This phone has no sound picker: " + e.getMessage()); }
        });
        choose.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sr.addView(choose);
        Button test = button("Test sound", v -> {
            try {
                String cur = Store.fibSound(this);
                Uri u = cur.isEmpty() ? android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(cur);
                android.media.Ringtone rt = android.media.RingtoneManager.getRingtone(this, u);
                if (rt != null) rt.play();
            } catch (Exception e) { showText("Sound", "Could not play it: " + e.getMessage()); }
        });
        test.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sr.addView(test);
        set.addView(sr);
        set.addView(text("Android ties a sound to a notification channel, so each new choice makes a fresh Fib channel. If you hear no change, check that Fib pullback alerts are not set to silent in the phone's notification settings.", 11, DIM, false));
        p.addView(set);

        // ---- update + chart window + setups
        final TextView status = text("", 12, DIM, false);
        final Button go = button("Update now", null);
        p.addView(go);
        p.addView(status);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        p.addView(box);
        go.setOnClickListener(v -> {
            go.setEnabled(false);
            status.setText("Reading the H4 and M15 charts...");
            new Thread(() -> {
                String res;
                try { int n = FibRunner.run(this, client, System.currentTimeMillis() / 1000); res = n + " new Fib alert" + (n == 1 ? "" : "s"); }
                catch (Throwable t) { res = "Update failed: " + t.getMessage(); }
                final String r = res;
                ui.post(() -> { go.setEnabled(true); status.setText(r); fillFib(box); });
            }).start();
        });
        fillFib(box);
        startLive(() -> fillFib(box));
        p.addView(fibBacktestCard());
        p.addView(button("Open the full Strategy Lab", v -> { labStrat = new boolean[]{false, false, true}; select(8); }));
        return scroll(p);
    }

    private String fibPlan(com.smcscanner.engine.fib.FibEngine.Setup s, String label, com.smcscanner.engine.fib.FibEngine.Options o) {
        boolean buy = s.isBuy();
        StringBuilder sb = new StringBuilder("SETUP IDENTIFIED: Fib pullback " + (buy ? "buy" : "sell") + " on " + label + " (" + s.stage + ").\n");
        String waiting = "";
        for (com.smcscanner.engine.fib.FibEngine.Step st : s.steps) if (st.status.equals("NOW")) { waiting = st.text; break; }
        if (s.actionable()) {
            sb.append(s.state.equals(com.smcscanner.engine.fib.FibEngine.S_ENTRY) ? "STATUS: price is at the entry level now.\n" : "WAITING FOR: price to dip back to the entry level.\n");
            sb.append("DOUBLE CONFIRMATION: in place. H4 structure and pullback, plus three consecutive M15 breaks of structure.\n");
            sb.append("THEN: place a ").append(buy ? "BUY" : "SELL").append(" LIMIT at ").append(Fmt.f(s.entry)).append(", target ").append(Fmt.f(o.targetH4 ? s.tp2 : s.tp1)).append(".\n");
            sb.append("INVALID IF: M15 closes ").append(buy ? "below " : "above ").append(Fmt.f(s.stop)).append(" (the 0% of the M15 fib).");
        } else {
            sb.append("WAITING FOR: ").append(waiting.isEmpty() ? "the next step below" : waiting).append("\n");
            sb.append("DOUBLE CONFIRMATION: needs the H4 break and pullback first, then three consecutive M15 breaks of structure.\n");
            sb.append("THEN: a ").append(buy ? "BUY" : "SELL").append(" LIMIT goes at the ").append((int) Math.round(o.entryFrac * 100)).append("% level of the M15 fib, stop on its 0%.");
        }
        return sb.toString();
    }

    private void showBigFib(com.smcscanner.engine.fib.FibEngine.Setup s, Candle[] cs) {
        if (cs == null || cs.length < 6) return;
        FibChartView v = new FibChartView(this);
        v.setData(s, cs, fibH4View);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.75f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        new AlertDialog.Builder(this).setTitle(s.label + (fibH4View ? "  H4 fib" : "  M15 fib")).setView(box).setPositiveButton("Close", null).show();
    }

    /** the Fib backtest, on the Fib tab: pick markets, run it on history, read the results right here */
    private final boolean[][] fibBtMarkets = new boolean[1][];
    private View fibBacktestCard() {
        final Scan.Config cfg = Store.config(this);
        LinearLayout c = card();
        c.addView(text("Backtest the Fib strategy", 18, TXT, true));
        c.addView(text("Replays the Fib pullback rules over past Deriv candles (H4 bias, M15 entries; only candles that had closed at each moment, so no peeking ahead) and shows how those setups ended: win rate with its 95% range, R per trade, profit factor and drawdown. Simulated fills: limit entry, stop counted first if one candle touches both, no spread. History, not a forecast.", 12, DIM, false));
        if (fibBtMarkets[0] == null || fibBtMarkets[0].length != cfg.markets.size()) { fibBtMarkets[0] = new boolean[cfg.markets.size()]; java.util.Arrays.fill(fibBtMarkets[0], true); }
        String[] ml = new String[cfg.markets.size()];
        for (int i = 0; i < ml.length; i++) ml[i] = cfg.markets.get(i).label;
        c.addView(text("Markets to test (all volatility indices are on by default)", 12, DIM, false));
        c.addView(multiChips(ml, fibBtMarkets[0], null));
        final int[] depth = {0};
        c.addView(singleChips(new String[]{"Standard history", "Longer history (slower)"}, depth, null));
        final Button go = button("Run Fib backtest", null);
        final TextView bar = text("", 14, ACCENT, true), st = text("", 12, TXT, false);
        c.addView(go); c.addView(bar); c.addView(st);
        final LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        c.addView(out);
        final Runnable show = () -> {
            labFilter.mode = "FIB"; labFilter.market = "ALL"; labFilter.frame = "ALL"; labFilter.data = "ALL"; labFilter.side = "ALL";
            labRows = null;
            renderLab(out);
        };
        c.addView(button("Show saved Fib results", v -> show.run()));
        go.setOnClickListener(v -> {
            if (labBusy) return;
            LabRunner.Options o = new LabRunner.Options();
            o.smc = false; o.pa = false; o.fib = true; o.replace = true; o.mult = depth[0] == 1 ? 2 : 1;
            for (int i = 0; i < fibBtMarkets[0].length; i++) if (fibBtMarkets[0][i]) o.markets.add(cfg.markets.get(i).symbol);
            if (o.markets.isEmpty()) { st.setText("Pick at least one market."); return; }
            labBusy = true; go.setEnabled(false);
            st.setText("Starting. Keep the app open; this takes a few minutes per market.");
            new Thread(() -> {
                String sum;
                try {
                    sum = LabRunner.run(this, client, o, (done, total, what) -> ui.post(() -> {
                        int filled = Math.min(10, (int) Math.round(10.0 * done / Math.max(1, total)));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 10; i++) sb.append(i < filled ? "▓" : "░");
                        bar.setText(sb + "  " + done + " of " + total);
                        st.setText(what);
                    }));
                } catch (Throwable t) { sum = "Backtest failed: " + t.getMessage(); }
                final String fs = sum;
                ui.post(() -> { labBusy = false; go.setEnabled(true); st.setText(fs); show.run(); });
            }).start();
        });
        return c;
    }

    private void fillFib(final LinearLayout box) {
        liveBinds.clear();
        box.removeAllViews();
        final List<FibRunner.Item> items = FibRunner.load(this);
        if (items.isEmpty()) {
            LinearLayout c = card();
            c.addView(text("Nothing scanned yet. Press Update now. The scanner also checks every few minutes while it is running.", 13, DIM, false));
            box.addView(c);
            return;
        }
        if (!Store.modeFib(this)) box.addView(text("The Fib engine is off, so these are old results.", 12, RED, false));
        // market chips
        if (fibMarket >= items.size()) fibMarket = 0;
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < items.size(); i++) {
            final int k = i;
            int rdy = 0;
            for (com.smcscanner.engine.fib.FibEngine.Setup q : items.get(i).setups) if (q.actionable()) rdy++;
            TextView ch = chip(items.get(i).label + "  " + items.get(i).setups.size() + (rdy > 0 ? "  (" + rdy + " ready)" : ""), i == fibMarket);
            ch.setOnClickListener(v -> { fibMarket = k; fillFib(box); });
            row.addView(ch);
        }
        box.addView(chipScroller(row));
        final FibRunner.Item it = items.get(fibMarket);
        com.smcscanner.engine.fib.FibEngine.Options o = Store.fibOptions(this);
        // summary of this market
        LinearLayout win = card();
        int ready = 0, fexpN = 0;
        for (com.smcscanner.engine.fib.FibEngine.Setup q : it.setups) { if (q.actionable()) ready++; if (q.state.equals(com.smcscanner.engine.fib.FibEngine.S_EXPIRED)) fexpN++; }
        int fact = it.setups.size() - fexpN;
        win.addView(text(it.label, 20, TXT, true));
        win.addView(text(fact + " Fib idea" + (fact == 1 ? "" : "s") + " in progress:  " + ready + " with the order ready,  " + (fact - ready) + " still building" + (fexpN > 0 ? ",  " + fexpN + " expired (at the bottom)" : ""), 14, TXT, true));
        win.addView(text("Read " + FibRunner.formatAge(it.at, System.currentTimeMillis() / 1000) + "  |  " + items.size() + " markets scanned. Tap a market above to see its fibs, breaks of structure and progress.", 12, DIM, false));
        if (!it.dataNote.isEmpty()) win.addView(text(it.dataNote, 12, RED, false));
        if (fact == 0) {
            win.addView(text("No Fib idea on this market right now: H4 has no fresh break of structure with a pullback leg, or an earlier idea was cancelled or reached its target. Fib setups are rare (about one per market per month on test data).", 12, DIM, false));
        } else {
            final int[] view = {fibH4View ? 1 : 0};
            win.addView(singleChips(new String[]{"M15 chart (entry)", "H4 chart (bias)"}, view, () -> { fibH4View = view[0] == 1; fillFib(box); }));
            win.addView(text("Both fibs, the three breaks of structure and the order are drawn for you. Purple = H4 fib, gold = M15 fib. Tap a picture to enlarge it.", 11, DIM, false));
        }
        box.addView(win);
        for (int i = 0; i < it.setups.size(); i++) {
            com.smcscanner.engine.fib.FibEngine.Setup s = it.setups.get(i);
            LinearLayout c = card();
            int col = s.isBuy() ? GREEN : RED;
            final boolean fexp = s.state.equals(com.smcscanner.engine.fib.FibEngine.S_EXPIRED);
            c.addView(text(s.headline, 16, fexp ? DIM : col, true));
            c.addView(text(s.stage + "  |  score " + s.score + "/7" + (s.d1.isEmpty() ? "" : "  |  D1 " + s.d1), 12, DIM, false));
            if (fexp) {
                c.addView(UiKit.pill(this, "  ENTRY EXPIRED  ", RED, Theme.onColor));
                String why = s.conflicts.isEmpty() ? "" : s.conflicts.get(s.conflicts.size() - 1).replace("ENTRY EXPIRED: ", "");
                c.addView(text(why + " Do not place this order. It stays here until you remove it.", 13, RED, false));
                final String rid = s.id;
                c.addView(button("Remove this setup", v -> { Store.setSetupFlag(this, 'R', rid); fillFib(box); }));
            } else {
                TextView pl = text(fibPlan(s, it.label, o), 13, TXT, false);
                pl.setBackground(UiKit.round(BG, dp(10)));
                pl.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.setMargins(0, dp(6), 0, dp(6));
                pl.setLayoutParams(plp);
                c.addView(pl);
            }
            final com.smcscanner.engine.fib.FibEngine.Setup fsc = s;
            final Candle[] fcs = fibH4View ? it.h4 : it.m15;
            if (fcs != null && fcs.length > 5) {
                FibChartView fcv = new FibChartView(this);
                fcv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(480)));
                fcv.setData(s, fcs, fibH4View);
                fcv.setOnClickListener(v -> showBigFib(fsc, fcs));
                c.addView(fcv);
            }
            // progress checklist
            c.addView(text("STRATEGY PROGRESS", 11, DIM, true));
            for (com.smcscanner.engine.fib.FibEngine.Step st : s.steps) {
                String mark = st.status.equals("DONE") ? "✓ " : st.status.equals("NOW") ? "▶ " : "○ ";
                c.addView(text(mark + st.text, 13, st.status.equals("DONE") ? GREEN : st.status.equals("NOW") ? TXT : DIM, false));
            }
            if (s.actionable() || fexp) {
                LinearLayout t1 = new LinearLayout(this);
                t1.setOrientation(LinearLayout.HORIZONTAL);
                t1.setPadding(0, dp(8), 0, 0);
                t1.addView(tile("ENTRY", Fmt.f(s.entry), TXT));
                t1.addView(tile("STOP", Fmt.f(s.stop), RED));
                t1.addView(tile("TP M15", Fmt.f(s.tp1) + String.format(Locale.US, "  %.1fR", s.rr), GREEN));
                t1.addView(tile("TP H4", Fmt.f(s.tp2) + String.format(Locale.US, "  %.1fR", s.rrH4), GREEN));
                c.addView(t1);
                for (String e : s.evidence) c.addView(text("+ " + e, 12, TXT, false));
                for (String e : s.conflicts) c.addView(text("! " + e, 12, RED, false));
                if (i < it.memory.size() && !it.memory.get(i).isEmpty()) c.addView(text("Memory: " + it.memory.get(i), 12, DIM, false));
                c.addView(text("A measured pattern, not a forecast. Target for alerts: " + (o.targetH4 ? "H4 100%" : "M15 100%") + ".", 11, DIM, false));
                final boolean fb = s.isBuy();
                final com.smcscanner.engine.fib.FibEngine.Setup fs = s;
                final boolean h4t = o.targetH4;
                if (!fexp) c.addView(liveLine(it.symbol, px -> com.smcscanner.engine.LiveStatus.describe(fb, true, false, fs.entry, fs.stop, h4t ? fs.tp2 : fs.tp1, px)));
            }
            if (!fexp && s.entry > 0 && s.stop > 0) {
                final boolean fb2 = s.isBuy();
                final com.smcscanner.engine.fib.FibEngine.Setup fs2 = s;
                final boolean h4t2 = o.targetH4;
                c.addView(button("I entered this trade", v -> askManualEntry(it.symbol, it.label, fb2, fs2.entry, fs2.stop, h4t2 ? fs2.tp2 : fs2.tp1,
                    h4t2 ? Double.valueOf(fs2.tp1) : Double.valueOf(fs2.tp2), "M15", 900, "Fib pullback", fs2.actionable() ? "QUALIFIED" : "WATCH",
                    fs2.actionable() ? "Setup status: " + fs2.stage + "." : "Setup status: " + fs2.stage + " (not every step is done). You can log it anyway.", fs2.id)));
            }
            LinearLayout fb3 = new LinearLayout(this);
            fb3.setOrientation(LinearLayout.HORIZONTAL);
            Button lc = button("Live chart (M15)", v -> {
                ChartController.Focus f = new ChartController.Focus();
                f.symbol = it.symbol; f.tf = "15m"; f.buy = fsc.isBuy(); f.ready = fsc.actionable();
                f.entry = fsc.entry; f.stop = fsc.stop; f.target = o.targetH4 ? fsc.tp2 : fsc.tp1; f.target2 = o.targetH4 ? Double.valueOf(fsc.tp1) : Double.valueOf(fsc.tp2);
                f.title = it.label + " M15: Fib pullback " + (fsc.actionable() ? "READY" : "BUILDING");
                f.note = fsc.stage + "  |  invalid if M15 closes " + (fsc.isBuy() ? "below " : "above ") + Fmt.f(fsc.stop);
                if (fsc.mZero != 0 && fsc.mHundred != 0) {
                    double mr = fsc.mHundred - fsc.mZero;
                    f.levels.add(new com.smcscanner.chart.Overlay.Level(fsc.mZero, "M15 fib 0%", 0xFFE3B341, false, 0));
                    f.levels.add(new com.smcscanner.chart.Overlay.Level(fsc.mZero + 0.5 * mr, "M15 fib 50%", 0xFFE3B341, true, 0));
                    f.levels.add(new com.smcscanner.chart.Overlay.Level(fsc.mHundred, "M15 fib 100%", 0xFFE3B341, false, 0));
                }
                openFocusChart(it.symbol, f);
            });
            lc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            fb3.addView(lc);
            Button en = button("Enlarge", v -> showBigFib(fsc, fcs));
            en.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            fb3.addView(en);
            c.addView(fb3);
            box.addView(c);
        }
    }

    // ---------- Strategy Lab (replaces the old Backtest tab) ----------
    private final LabRunner.Options labOpts = new LabRunner.Options();
    private final com.smcscanner.engine.mie.Lab.Filter labFilter = new com.smcscanner.engine.mie.Lab.Filter();
    private List<com.smcscanner.engine.mie.Row> labRows = null;
    private boolean labBusy = false;
    private boolean[] labStrat = {false, true, true}, labFrames = {true, true, true}, labMarkets;
    private int[] labDepth = {0};

    private TextView chip(String label, boolean on) {
        TextView t = text(label, 13, on ? Theme.onColor : TXT, true);
        t.setBackground(UiKit.round(on ? ACCENT : BG, dp(16)));
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void paintChip(TextView t, boolean on) {
        t.setTextColor(on ? Theme.onColor : TXT);
        t.setBackground(UiKit.round(on ? ACCENT : BG, dp(16)));
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
    }

    private View chipScroller(LinearLayout row) {
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        row.setPadding(0, dp(4), 0, dp(6));
        sv.addView(row);
        return sv;
    }

    /** several chips that can each be on or off */
    private View multiChips(String[] labels, final boolean[] state, final Runnable changed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            final TextView c = chip(labels[i], state[i]);
            c.setOnClickListener(v -> {
                state[k] = !state[k];
                paintChip(c, state[k]);
                if (changed != null) changed.run();
            });
            row.addView(c);
        }
        return chipScroller(row);
    }

    /** chips where exactly one is on */
    private View singleChips(String[] labels, final int[] sel, final Runnable changed) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView c = chip(labels[i], sel[0] == i);
            c.setOnClickListener(v -> {
                sel[0] = k;
                for (int j = 0; j < row.getChildCount(); j++) paintChip((TextView) row.getChildAt(j), j == k);
                if (changed != null) changed.run();
            });
            row.addView(c);
        }
        return chipScroller(row);
    }

    private View labTab() {
        final Scan.Config cfg = Store.config(this);
        LinearLayout p = page();
        p.addView(UiKit.brandRow(this, "Strategy Lab", "TEST BOTH ENGINES ON REAL HISTORY"));
        p.addView(text("Replays the SMC engine and the price-action engine over past Deriv candles (only candles that had closed at each moment, so no peeking ahead), then lets you slice the results. Fills are simulated: limit entry, stop counted first if one candle touches both, no spread or slippage.", 12, DIM, false));

        // ---- 1. what to test
        LinearLayout run = card();
        run.addView(text("1  Choose what to test", 16, TXT, true));
        run.addView(text("Strategy", 12, DIM, false));
        run.addView(multiChips(new String[]{"SMC", "Price action", "Fib pullback"}, labStrat, null));
        run.addView(text("Markets", 12, DIM, false));
        if (labMarkets == null || labMarkets.length != cfg.markets.size()) { labMarkets = new boolean[cfg.markets.size()]; java.util.Arrays.fill(labMarkets, true); }
        String[] mlabels = new String[cfg.markets.size()];
        for (int i = 0; i < mlabels.length; i++) mlabels[i] = cfg.markets.get(i).label;
        run.addView(multiChips(mlabels, labMarkets, null));
        run.addView(text("Charts (SMC 1H and 30m cascades; price action on each chart; Fib pullback always uses H4 + M15)", 12, DIM, false));
        run.addView(multiChips(new String[]{"1H", "30m", "15m"}, labFrames, null));
        run.addView(text("History", 12, DIM, false));
        run.addView(singleChips(new String[]{"Standard (about 7 weeks of 15m)", "Longer (about 14 weeks, slower)"}, labDepth, null));
        final CheckBox repl = new CheckBox(this);
        repl.setText("Replace earlier test results for the same selection");
        repl.setTextColor(TXT);
        repl.setChecked(labOpts.replace);
        run.addView(repl);
        run.addView(text("The SMC 15m cascade cannot be replayed (Deriv keeps only a few days of 1m candles); it builds its record from live signals.", 11, DIM, false));
        final Button go = button("Run test", null);
        final TextView status = text("", 12, TXT, false);
        final TextView bar = text("", 14, ACCENT, true);
        run.addView(go);
        run.addView(bar);
        run.addView(status);
        p.addView(run);

        // ---- 2. results
        final LinearLayout res = card();
        res.addView(text("2  Results", 16, TXT, true));
        res.addView(text("Tap a filter to slice what is shown. Nothing is re-run: this reads the saved test results and live trades.", 12, DIM, false));
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final Runnable redraw = () -> renderLab(box);
        final int[] fMode = {0}, fMkt = {0}, fFrame = {0}, fData = {0}, fSide = {0};
        final String[] modeV = {"ALL", "SMC", "PA", "FIB"}, frameV = {"ALL", "1H", "30m", "15m"}, dataV = {"ALL", "UNSEEN", "IN"}, sideV = {"ALL", "BUY", "SELL"};
        final String[] mktV = new String[mlabels.length + 1];
        String[] mktL = new String[mlabels.length + 1];
        mktV[0] = "ALL"; mktL[0] = "All";
        for (int i = 0; i < mlabels.length; i++) { mktV[i + 1] = cfg.markets.get(i).symbol; mktL[i + 1] = mlabels[i]; }
        final Runnable sync = () -> {
            labFilter.mode = modeV[fMode[0]]; labFilter.market = mktV[fMkt[0]]; labFilter.frame = frameV[fFrame[0]];
            labFilter.data = dataV[fData[0]]; labFilter.side = sideV[fSide[0]];
            redraw.run();
        };
        res.addView(text("Mode", 12, DIM, false));
        res.addView(singleChips(new String[]{"All", "SMC", "Price action", "Fib pullback"}, fMode, sync));
        res.addView(text("Market", 12, DIM, false));
        res.addView(singleChips(mktL, fMkt, sync));
        res.addView(text("Chart", 12, DIM, false));
        res.addView(singleChips(new String[]{"All", "1H", "30m", "15m"}, fFrame, sync));
        res.addView(text("Data (unseen = periods the engine was not tuned on: validation, out-of-sample and live)", 12, DIM, false));
        res.addView(singleChips(new String[]{"All periods", "Unseen only", "In-sample only"}, fData, sync));
        res.addView(text("Side", 12, DIM, false));
        res.addView(singleChips(new String[]{"Both", "Buys", "Sells"}, fSide, sync));
        CheckBox live = new CheckBox(this);
        live.setText("Include live trades");
        live.setTextColor(TXT);
        live.setChecked(labFilter.includeLive);
        live.setOnCheckedChangeListener((b, on) -> { labFilter.includeLive = on; redraw.run(); });
        res.addView(live);
        res.addView(box);
        p.addView(res);
        p.addView(button("Classic test (older engine)", v -> { content.removeAllViews(); content.addView(classicBacktestTab()); }));

        go.setOnClickListener(v -> {
            if (labBusy) return;
            LabRunner.Options o = labOpts;
            o.smc = labStrat[0]; o.pa = labStrat[1]; o.fib = labStrat[2];
            o.f1h = labFrames[0]; o.f30 = labFrames[1]; o.f15 = labFrames[2];
            o.mult = labDepth[0] == 1 ? 2 : 1;
            o.replace = repl.isChecked();
            o.markets = new java.util.HashSet<>();
            for (int i = 0; i < labMarkets.length; i++) if (labMarkets[i]) o.markets.add(cfg.markets.get(i).symbol);
            if (!(o.smc || o.pa || o.fib) || !(o.f1h || o.f30 || o.f15) || o.markets.isEmpty()) { status.setText("Pick at least one strategy, one market and one chart."); return; }
            labBusy = true;
            go.setEnabled(false);
            status.setText("Starting. Keep the app open; this takes several minutes.");
            final LabRunner.Options fo = o;
            new Thread(() -> {
                String sum;
                try {
                    sum = LabRunner.run(this, client, fo, (done, total, what) -> ui.post(() -> {
                        int filled = Math.min(10, (int) Math.round(10.0 * done / Math.max(1, total)));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 10; i++) sb.append(i < filled ? "▓" : "░");
                        bar.setText(sb + "  " + done + " of " + total);
                        status.setText(what);
                    }));
                } catch (Throwable t) { sum = "Test failed: " + t.getMessage(); }
                List<com.smcscanner.engine.mie.Row> fresh;
                try { fresh = SqliteResearchStore.get(this).all(); } catch (Throwable t) { fresh = new ArrayList<>(); }
                final List<com.smcscanner.engine.mie.Row> fr = fresh; final String fs = sum;
                ui.post(() -> { labBusy = false; go.setEnabled(true); labRows = fr; status.setText(fs); renderLab(box); });
            }).start();
        });
        renderLab(box);
        return scroll(p);
    }

    private void renderLab(final LinearLayout box) {
        box.removeAllViews();
        if (labRows == null) {
            box.addView(text("Loading saved results...", 12, DIM, false));
            new Thread(() -> {
                List<com.smcscanner.engine.mie.Row> r;
                try { r = SqliteResearchStore.get(this).all(); } catch (Throwable t) { r = new ArrayList<>(); }
                final List<com.smcscanner.engine.mie.Row> rr = r;
                ui.post(() -> { labRows = rr; renderLab(box); });
            }).start();
            return;
        }
        final List<com.smcscanner.engine.mie.Row> rows = com.smcscanner.engine.mie.Lab.apply(labRows, labFilter);
        final com.smcscanner.engine.mie.Lab.Summary s = com.smcscanner.engine.mie.Lab.summarize(rows);
        com.smcscanner.engine.mie.Kpi k = s.kpi;
        TextView verdict = text(s.verdict, 13, s.verdictTone > 0 ? GREEN : s.verdictTone < 0 ? RED : TXT, true);
        verdict.setBackground(UiKit.round(BG, dp(12)));
        verdict.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(0, dp(8), 0, dp(8));
        verdict.setLayoutParams(vlp);
        box.addView(verdict);
        if (k.completed == 0) {
            box.addView(text(labRows.isEmpty() ? "Nothing saved yet. Run a test above." : "Try a wider filter, or run a test for this selection.", 12, DIM, false));
            return;
        }
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(tile("TRADES", String.valueOf(k.completed), TXT));
        r1.addView(tile("WIN RATE", String.format(Locale.US, "%.0f%%", k.winRate), TXT));
        r1.addView(tile("PER TRADE", String.format(Locale.US, "%+.2fR", k.expectancy), k.expectancy > 0 ? GREEN : RED));
        r1.addView(tile("NET", String.format(Locale.US, "%+.0fR", k.netR), k.netR > 0 ? GREEN : RED));
        box.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setPadding(0, dp(6), 0, dp(6));
        r2.addView(tile("PROFIT F.", Double.isInfinite(k.profitFactor) ? "∞" : String.format(Locale.US, "%.2f", k.profitFactor), TXT));
        r2.addView(tile("MAX DRAWDOWN", String.format(Locale.US, "%.0fR", k.maxDrawdownR), RED));
        r2.addView(tile("AVG WIN", String.format(Locale.US, "%.1fR", k.avgWinR), GREEN));
        r2.addView(tile("AVG LOSS", String.format(Locale.US, "%.1fR", k.avgLossR), RED));
        box.addView(r2);
        box.addView(text(String.format(Locale.US, "95%% range for the win rate: %.0f%% to %.0f%%.  Longest winning run %d, longest losing run %d.  Entry reached on %.0f%% of ready setups.", k.ciLow, k.ciHigh, k.longestWin, k.longestLoss, k.entryTriggerRate), 11, DIM, false));
        box.addView(text("Equity curve (cumulative R, one step per trade)", 12, DIM, false));
        EquityView ev = new EquityView(this);
        ev.setCurve(s.curve);
        ev.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        box.addView(ev);

        box.addView(UiKit.section(this, "By strategy and chart", com.smcscanner.engine.mie.Lab.table(rows, r -> com.smcscanner.engine.mie.Lab.modeName(r.mode) + " " + r.frame), true));
        box.addView(UiKit.section(this, "By market", com.smcscanner.engine.mie.Lab.table(rows, r -> r.label.isEmpty() ? r.symbol : r.label), false));
        StringBuilder sc = new StringBuilder();
        java.util.Map<String, com.smcscanner.engine.mie.Kpi> by = com.smcscanner.engine.mie.Kpi.groupBy(rows, r -> com.smcscanner.engine.mie.Lab.modeName(r.mode) + ": " + (r.scenario.isEmpty() ? "(unlabelled)" : r.scenario));
        List<java.util.Map.Entry<String, com.smcscanner.engine.mie.Kpi>> es = new ArrayList<>(by.entrySet());
        java.util.Collections.sort(es, (x, y) -> y.getValue().completed - x.getValue().completed);
        for (java.util.Map.Entry<String, com.smcscanner.engine.mie.Kpi> e : es) sc.append(e.getKey()).append("\n  ").append(e.getValue().line()).append("\n  ").append(typeVerdict(e.getValue())).append("\n\n");
        box.addView(UiKit.section(this, "By scenario: keep or drop", sc.length() == 0 ? "Nothing to show for these filters." : sc.toString().trim(), false));
        java.util.List<com.smcscanner.engine.mie.Lab.Ranked> rk = com.smcscanner.engine.mie.Lab.rank(rows, 10);
        if (!rk.isEmpty()) {
            StringBuilder b = new StringBuilder("Best (10+ trades each)\n");
            for (int i = 0; i < Math.min(3, rk.size()); i++) b.append(rk.get(i).name).append("\n  ").append(rk.get(i).kpi.line()).append("\n");
            b.append("\nWeakest\n");
            for (int i = rk.size() - 1; i >= Math.max(0, rk.size() - 3); i--) b.append(rk.get(i).name).append("\n  ").append(rk.get(i).kpi.line()).append("\n");
            box.addView(UiKit.section(this, "Best and weakest scenarios", b.toString().trim(), false));
        }
        box.addView(UiKit.section(this, "By period (in-sample is where tuning happened; trust the others more)", com.smcscanner.engine.mie.Lab.table(rows, r -> r.sample.isEmpty() ? "(none)" : r.sample), false));
        box.addView(UiKit.section(this, "By side", com.smcscanner.engine.mie.Lab.table(rows, r -> r.side), false));
        box.addView(UiKit.section(this, "By reward label", com.smcscanner.engine.mie.Lab.table(rows, r -> r.band.isEmpty() ? "(none)" : r.band), false));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button share = button("Share results", v -> {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_TEXT, com.smcscanner.engine.mie.Lab.shareText(labFilter, s, rows));
            startActivity(Intent.createChooser(it, "Share results"));
        });
        share.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(share);
        Button clear = button("Clear test results", v -> showTextWith("Clear test results?", "This deletes the saved replay results (every strategy and market). Live trades are kept. Learning will rebuild from live trades and any new test.", "Clear", () -> {
            new Thread(() -> {
                try {
                    SqliteResearchStore st = SqliteResearchStore.get(this);
                    List<String> ids = new ArrayList<>();
                    for (com.smcscanner.engine.mie.Row r : st.bySource("replay")) ids.add(r.id);
                    st.deleteRows(ids);
                    List<com.smcscanner.engine.mie.Row> fresh = st.all();
                    ui.post(() -> { labRows = fresh; renderLab(box); });
                } catch (Throwable ignored) { }
            }).start();
        }));
        clear.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(clear);
        box.addView(btns);
    }

    private View classicBacktestTab() {
        final Scan.Config cfg = Scan.Config.defaults();
        LinearLayout p = page();
        p.addView(button("\u2039 Back to the Strategy Lab", v -> select(8)));
        p.addView(text("Classic test (older engine)", 24, TXT, true));
        p.addView(text("Replays your strategy on past candles, finds the best place to enter inside the zone, measures how often trades like yours won (the confidence figure on alerts) and shows which quality factors helped. Volatility indices are simulated, so treat results as a guide, not a promise.", 13, DIM, false));
        List<String> ms = new ArrayList<>(), ps = new ArrayList<>();
        for (Scan.Market m : cfg.markets) ms.add(m.label);
        for (Scan.Pair q : cfg.pairs) ps.add(q.label());
        final Spinner msp = spinner(ms, 0), psp = spinner(ps, 1);
        p.addView(text("Chain: idea timeframe > entry zone > finer timeframes it zooms into", 12, DIM, false));
        p.addView(msp);
        p.addView(psp);
        final TextView out = text("", 12, TXT, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        final Button run = button("Run backtest + calibrate (a few minutes)", null);
        run.setOnClickListener(v -> {
            run.setEnabled(false);
            out.setText("Downloading history and testing...");
            final Scan.Market m = cfg.markets.get(msp.getSelectedItemPosition());
            final Scan.Pair pair = cfg.pairs.get(psp.getSelectedItemPosition());
            new Thread(() -> {
                String res;
                try { res = runBacktest(cfg, m, pair); } catch (Throwable t) { res = "Backtest failed: " + t.getMessage(); }
                final String r = res;
                ui.post(() -> { run.setEnabled(true); out.setText(r); });
            }).start();
        });
        p.addView(run);
        final Button score = button("Last 30 days: normal vs Sniper, all markets (several minutes)", null);
        score.setOnClickListener(v -> {
            score.setEnabled(false);
            out.setText("Downloading history for every market. This takes a while...");
            new Thread(() -> {
                String res;
                try { res = runSniperScorecard(cfg); } catch (Throwable t) { res = "Scorecard failed: " + t.getMessage(); }
                final String r = res;
                ui.post(() -> { score.setEnabled(true); out.setText(r); });
            }).start();
        });
        p.addView(score);
        p.addView(out);
        return scroll(p);
    }

    private static final class Tally { int n, wins; double r; }

    private String runSniperScorecard(Scan.Config cfg) throws Exception {
        final int days = 30;
        Scan.Config sn = Scan.Config.sniper();
        StringBuilder sb = new StringBuilder("LAST " + days + " DAYS: NORMAL RULES vs SNIPER RULES\n(real Deriv history, entry zoomed to 5m / 1m, 1 unit of risk = 1R)\n\n");
        Tally normal = new Tally(), sniper = new Tally();
        java.util.TreeMap<String, Tally> perDayN = new java.util.TreeMap<>(), perDayS = new java.util.TreeMap<>();
        for (Scan.Market m : cfg.markets) {
            Map<String, Candle[]> cache = new HashMap<>();
            for (int mode = 0; mode < 2; mode++) {
                List<Scan.Pair> pairs = mode == 0 ? cfg.pairs : sn.pairs;
                Tally t = mode == 0 ? normal : sniper;
                for (Scan.Pair pr : pairs) {
                    Calibrator.PeriodResult r = Calibrator.replay(client, cfg, m, pr, cache, mode == 1, days);
                    t.n += r.result.filled; t.wins += r.result.wins; t.r += r.result.totalR;
                    java.util.TreeMap<String, Tally> pd = mode == 0 ? perDayN : perDayS;
                    for (Backtest.Trade tr : r.trades) {
                        if (!tr.decided()) continue;
                        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(tr.setup.triggerEpoch * 1000));
                        Tally d = pd.get(day);
                        if (d == null) { d = new Tally(); pd.put(day, d); }
                        d.n++; if (tr.outcome.equals("win")) d.wins++; d.r += tr.r;
                    }
                }
            }
            cache.clear();
        }
        for (int mode = 0; mode < 2; mode++) {
            Tally t = mode == 0 ? normal : sniper;
            java.util.TreeMap<String, Tally> pd = mode == 0 ? perDayN : perDayS;
            sb.append(mode == 0 ? "NORMAL RULES (all timeframes)\n" : "SNIPER RULES (1D/4H/1H ideas)\n");
            sb.append(t.n).append(" trades, ").append(String.format(Locale.US, "%.1f", t.n / (double) days)).append(" per day on average\n");
            if (t.n > 0) sb.append("Won ").append(t.wins).append(" of ").append(t.n).append(" = ").append(Fmt.d1(100.0 * t.wins / t.n)).append("%, total ").append(String.format(Locale.US, "%.1f", t.r)).append("R\n");
            int daysWith = pd.size(), goodDays = 0, atLeast1Win = 0;
            for (Tally d : pd.values()) { if (d.r > 0) goodDays++; if (d.wins >= 1) atLeast1Win++; }
            sb.append("Days with a trade: ").append(daysWith).append(" of ").append(days).append(". Days with at least one winner: ").append(atLeast1Win).append(". Profitable days: ").append(goodDays).append("\n\n");
        }
        sb.append("HOW TO READ THIS\n");
        sb.append("Only 30 days, so every number here is a small sample and can change a lot next month. A sample under about 30 trades says little.\n");
        sb.append("Nothing can guarantee a winner every day. Even with a high win rate, losing days happen.\n");
        sb.append("Your target was 80% or better. ");
        if (sniper.n >= 30 && 100.0 * sniper.wins / sniper.n >= 80) sb.append("Sniper met it this month; check again next month before trusting it.");
        else if (sniper.n >= 30) sb.append("Sniper did not reach it this month.");
        else sb.append("Sniper had too few trades to judge it.");
        return sb.toString();
    }

    private String runBacktest(Scan.Config cfg, Scan.Market m, Scan.Pair pair) throws Exception {
        Calibrator.Run run = Calibrator.run(client, cfg, m, pair, null);
        Store.putCalibration(this, Calibration.Book.key(m.symbol, pair.setup, pair.entry), run.calibration);
        Calibration cal = run.calibration;
        StringBuilder sb = new StringBuilder();
        sb.append(m.label).append(": ").append(pair.setup).append(" idea > ").append(pair.entry).append(" entry\n");
        sb.append("Tested ").append(run.htfCount).append(" x ").append(pair.setup).append(" and ").append(run.ltfCount).append(" x ").append(pair.entry).append(" candles\n\n");
        sb.append("ZOOMING IN (").append(pair.label()).append(")\n");
        sb.append("Entries found: ").append(run.totalTrades).append(". Of those, ").append(run.refinedTrades).append(" were refined on a finer chart");
        if (run.refinedTrades > 0) sb.append(" and the stop distance shrank by ").append(Math.round(100 * run.avgRiskCut)).append("% on average");
        sb.append(".\n");
        sb.append("Without zooming: ").append(run.withoutZoom.filled).append(" trades, ").append(Fmt.d1(run.withoutZoom.winRate)).append("% won, total ")
            .append(String.format(Locale.US, "%.1f", run.withoutZoom.totalR)).append("R\n");
        sb.append("With zooming:    ").append(run.withZoom.filled).append(" trades, ").append(Fmt.d1(run.withZoom.winRate)).append("% won, total ")
            .append(String.format(Locale.US, "%.1f", run.withZoom.totalR)).append("R\n");
        sb.append("A tighter stop wins more R per trade when it works but gets hit by noise more often. Compare the two lines before trusting it.\n\n");
        sb.append("SNIPER RULES (same data)\n");
        sb.append(run.sniper.filled).append(" trades");
        if (run.days > 0) sb.append(" (").append(String.format(Locale.US, "%.2f", run.sniper.filled / run.days)).append(" per day on this one chart)");
        if (run.sniper.filled > 0) sb.append(", ").append(Fmt.d1(run.sniper.winRate)).append("% won, total ").append(String.format(Locale.US, "%.1f", run.sniper.totalR)).append("R");
        if (run.sniper.filled < 30) sb.append("\nToo few trades to trust the win rate.");
        sb.append("\n\n");
        sb.append("WHERE TO ENTER INSIDE THE ZONE\n(same stop and target, only the entry moves)\n");
        for (int i = 0; i < 3; i++) {
            Calibration.ModeStat ms = cal.modes[i];
            sb.append(i == cal.bestMode ? "> " : "  ").append(Calibration.MODE_NAMES[i]).append("\n");
            sb.append("    ").append(ms.setups).append(" setups, ").append(ms.filled).append(" filled, ").append(ms.noFill).append(" never filled\n");
            sb.append("    wins ").append(ms.wins).append(" of ").append(ms.filled).append(" (").append(Fmt.d1(ms.filled > 0 ? 100.0 * ms.wins / ms.filled : 0)).append("%)   total ")
                .append(String.format(Locale.US, "%.1f", ms.totalR)).append("R\n");
        }
        sb.append("\nBest style on this data: ").append(Calibration.MODE_NAMES[cal.bestMode]).append(cal.modes[cal.bestMode].filled < 10 ? " (too few trades to be sure; middle used)" : "")
            .append(".\nAlerts use it when Settings > Entry style is Auto.\n\n");
        Calibration.ModeStat bs = cal.modes[cal.bestMode];
        sb.append("PAST WIN RATE BY GRADE (this is the confidence figure)\n");
        for (int g = 0; g < 3; g++) {
            sb.append("Grade ").append(Calibration.GRADES[g]).append(": ");
            if (bs.gradeN[g] == 0) sb.append("no trades\n");
            else sb.append(Fmt.d1(100.0 * bs.gradeWins[g] / bs.gradeN[g])).append("% won of ").append(bs.gradeN[g]).append(" trades").append(bs.gradeN[g] < 30 ? " (small sample)" : "").append("\n");
        }
        Factors.Report rep = Factors.report(run.byMode.get(cal.bestMode), 30);
        sb.append("\nDID EACH QUALITY FACTOR HELP?\n(average R with the factor vs without)\n");
        for (Factors.Row f : rep.factors) {
            sb.append("- ").append(f.label).append("\n    with ").append(f.withN).append(" trades: ").append(f.withN > 0 ? String.format(Locale.US, "%.2f", f.withAvgR) : "-")
                .append("R   without ").append(f.withoutN).append(": ").append(f.withoutN > 0 ? String.format(Locale.US, "%.2f", f.withoutAvgR) : "-").append("R");
            if (!f.enough) sb.append("   (too few trades to trust)");
            sb.append("\n");
        }
        sb.append("\nThese are past results on simulated markets. They show what happened, not what will happen. Small samples are noise.");
        return sb.toString();
    }

    private static Candle[] trimForming(Candle[] c, long gran) {
        return Smc.closedOnly(c, gran, System.currentTimeMillis() / 1000);
    }

    // ---------- Settings ----------
    private View settingsTab() {
        final Scan.Config cfg = Scan.Config.defaults();
        LinearLayout p = page();
        p.addView(text("Settings", 24, TXT, true));

        LinearLayout c1 = card();
        c1.addView(text("Background scanning", 16, TXT, true));
        final TextView st = text("", 13, DIM, false);
        c1.addView(st);
        final Button toggle = button("", null);
        Runnable refresh = () -> {
            boolean on = ScannerService.running || Store.scanningWanted(this);
            toggle.setText(on ? "Stop scanning" : "Start scanning");
            st.setText(on ? "ON. Alerts arrive even when the app is closed." : "OFF. You only get alerts while you press Scan now.");
        };
        toggle.setOnClickListener(v -> {
            boolean on = ScannerService.running || Store.scanningWanted(this);
            if (on) ScannerService.stop(this);
            else { askNotificationPermission(); ScannerService.start(this); }
            ui.postDelayed(() -> select(9), 600);
        });
        refresh.run();
        c1.addView(toggle);
        c1.addView(button("Send a test notification", v -> {
            boolean ok = Notifier.alert(this, "Test alert", "If you can read this, notifications work.", "Test alert\nIf you can read this, notifications work.");
            if (!ok) { askNotificationPermission(); showText("Notifications are blocked", "Allow notifications for SMC Scanner in your phone's settings, then try again."); }
        }));
        p.addView(c1);

        LinearLayout c2 = card();
        c2.addView(text("Keep it alive (important)", 16, TXT, true));
        c2.addView(text("Phones stop background apps to save battery. Allow SMC Scanner to run unrestricted, otherwise alerts can stop after a while.", 13, DIM, false));
        c2.addView(button("Allow unrestricted battery use", v -> {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) { showText("Already allowed", "Battery optimisation is already off for this app."); return; }
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }));
        c2.addView(button("Open this app's phone settings", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));
        c2.addView(text("On Xiaomi, Oppo, Vivo, Huawei, Samsung and similar phones, also set Battery to 'No restrictions' / 'Autostart on' in that screen and lock the app in the recent-apps list.", 12, DIM, false));
        p.addView(c2);

        LinearLayout c3 = card();
        c3.addView(text("Markets to watch", 16, TXT, true));
        for (final Scan.Market m : cfg.markets) {
            CheckBox cb = new CheckBox(this);
            cb.setText(m.label);
            cb.setTextColor(TXT);
            cb.setChecked(Store.isMarketOn(this, m.symbol));
            cb.setOnCheckedChangeListener((b, on) -> Store.setMarketOn(this, m.symbol, on));
            c3.addView(cb);
        }
        p.addView(c3);

        LinearLayout ca = card();
        ca.addView(text("Appearance", 16, TXT, true));
        CheckBox dk = new CheckBox(this);
        dk.setText("Dark theme (off = light theme)");
        dk.setTextColor(TXT);
        dk.setChecked(Store.dark(this));
        dk.setOnCheckedChangeListener((bv, on) -> {
            if (on == Store.dark(this)) return;
            Store.setDark(this, on);
            recreate(); // rebuilds every screen in the new colours
        });
        ca.addView(dk);
        p.addView(ca);

        LinearLayout cp = card();
        cp.addView(text("Position size", 16, TXT, true));
        cp.addView(text("Enter your account size and how much of it you are willing to risk on one trade. The app then uses that amount for every Risk and Gain figure. Leave the account size empty to use the fixed amount under Entry and risk.", 13, DIM, false));
        final EditText acc = new EditText(this);
        acc.setHint("Account size in $");
        acc.setTextColor(TXT);
        acc.setHintTextColor(DIM);
        acc.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (Store.accountSize(this) > 0) acc.setText(String.valueOf(Store.accountSize(this)));
        cp.addView(acc);
        final double[] pcts = {0.25, 0.5, 1, 2, 3};
        List<String> pl = new ArrayList<>();
        int pi = 2;
        for (int i = 0; i < pcts.length; i++) { pl.add("Risk " + (pcts[i] % 1 == 0 ? String.valueOf((int) pcts[i]) : String.valueOf(pcts[i])) + "% per trade"); if (Math.abs(pcts[i] - Store.riskPercent(this)) < 1e-9) pi = i; }
        final Spinner pctSp = spinner(pl, pi);
        cp.addView(pctSp);
        final TextView accOut = text("", 13, ACCENT, true);
        cp.addView(accOut);
        final Runnable accRefresh = () -> {
            int rm = Store.riskMoney(this);
            accOut.setText(Store.accountSize(this) > 0 ? "= $" + rm + " risked per trade" : "Using the fixed $" + rm + " per trade");
        };
        accRefresh.run();
        cp.addView(button("Save position size", v -> {
            int a1 = 0;
            try { a1 = Integer.parseInt(acc.getText().toString().trim()); } catch (Exception ignored) { }
            Store.setAccountSize(this, Math.max(0, a1));
            Store.setRiskPercentX10(this, (int) Math.round(pcts[pctSp.getSelectedItemPosition()] * 10));
            accRefresh.run();
        }));
        cp.addView(text("This sets the money at risk. It does not know your broker's lot size, so check the stop distance in points on each alert and size the trade on your platform so a stop-out costs about this amount.", 12, DIM, false));
        p.addView(cp);

        LinearLayout cl = card();
        cl.addView(text("Daily limits", 16, TXT, true));
        cl.addView(text("Stop new-setup alerts for the day after this many losing trades (alerts about trades you are already in keep coming):", 13, DIM, false));
        List<String> ll = new ArrayList<>();
        ll.add("No limit");
        for (int i = 1; i <= 5; i++) ll.add(i + (i == 1 ? " loss" : " losses"));
        final Spinner lossSp = spinner(ll, Store.maxLosses(this));
        cl.addView(lossSp);
        cl.addView(text("Quiet hours (no new-setup alerts). Set both the same to switch off:", 13, DIM, false));
        List<String> hl = new ArrayList<>();
        for (int i = 0; i < 24; i++) hl.add(String.format(Locale.US, "%02d:00", i));
        final Spinner fromSp = spinner(hl, Store.quietFrom(this)), toSp = spinner(hl, Store.quietTo(this));
        LinearLayout qrow = new LinearLayout(this);
        qrow.setOrientation(LinearLayout.HORIZONTAL);
        qrow.addView(text("From ", 13, DIM, false));
        qrow.addView(fromSp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        qrow.addView(text("  to ", 13, DIM, false));
        qrow.addView(toSp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cl.addView(qrow);
        AdapterView.OnItemSelectedListener ll2 = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                Store.setMaxLosses(MainActivity.this, lossSp.getSelectedItemPosition());
                Store.setQuiet(MainActivity.this, fromSp.getSelectedItemPosition(), toSp.getSelectedItemPosition());
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        };
        lossSp.setOnItemSelectedListener(ll2);
        fromSp.setOnItemSelectedListener(ll2);
        toSp.setOnItemSelectedListener(ll2);
        p.addView(cl);

        LinearLayout cs = card();
        cs.addView(text("Master strategy (Sniper)", 16, TXT, true));
        cs.addView(text("One combined strategy. The idea comes from the 1D, 4H or 1H chart: a major liquidity sweep, then a break of structure (continuation) or change of character (reversal) in the direction of the higher-timeframe trend, with strong displacement and a fair-value-gap or order-block zone. The entry is then zoomed in and confirmed on the 5m and 1m, with the stop beyond the sweep and the target on the higher-timeframe liquidity. Strict filters mean far fewer alerts. Use Backtest > Sniper scorecard to see how many per day and the measured win rate.", 13, DIM, false));
        CheckBox sb2 = new CheckBox(this);
        sb2.setText("Master strategy ON");
        sb2.setTextColor(TXT);
        sb2.setChecked(Store.sniper(this));
        sb2.setOnCheckedChangeListener((b, on) -> Store.setSniper(this, on));
        cs.addView(sb2);
        CheckBox topCb = new CheckBox(this);
        topCb.setText("Also need the top timeframe trend to agree (1D for a 1H idea)");
        topCb.setTextColor(TXT);
        topCb.setChecked(Store.requireTop(this));
        topCb.setOnCheckedChangeListener((b, on) -> Store.setRequireTop(this, on));
        cs.addView(topCb);
        CheckBox patCb = new CheckBox(this);
        patCb.setText("Skip trades when a confirmed chart pattern points the other way");
        patCb.setTextColor(TXT);
        patCb.setChecked(Store.requirePatternNotAgainst(this));
        patCb.setOnCheckedChangeListener((b, on) -> Store.setRequirePatternNotAgainst(this, on));
        cs.addView(patCb);
        CheckBox alCb = new CheckBox(this);
        alCb.setText("Advisor: hold a setup back only when the 1H and 4H both clearly point the other way");
        alCb.setTextColor(TXT);
        alCb.setChecked(Store.requireAligned(this));
        alCb.setOnCheckedChangeListener((b, on) -> Store.setRequireAligned(this, on));
        cs.addView(alCb);
        CheckBox obCb = new CheckBox(this);
        obCb.setText("SMC: refine entries with order block inside order block (tighter entry and stop)");
        obCb.setTextColor(TXT);
        obCb.setChecked(Store.refineOb(this));
        obCb.setOnCheckedChangeListener((b, on) -> Store.setRefineOb(this, on));
        cs.addView(obCb);
        CheckBox nbCb = new CheckBox(this);
        nbCb.setText("Advisor: keep on watch when opposing structure is under 1R from the entry");
        nbCb.setTextColor(TXT);
        nbCb.setChecked(Store.blockNearResistance(this));
        nbCb.setOnCheckedChangeListener((b, on) -> Store.setBlockNearResistance(this, on));
        cs.addView(nbCb);
        CheckBox advCb = new CheckBox(this);
        advCb.setText("Advisor: also send a heads-up when price reaches a zone (before the 15m confirmation)");
        advCb.setTextColor(TXT);
        advCb.setChecked(Store.advisorHeadsUp(this));
        advCb.setOnCheckedChangeListener((b, on) -> Store.setAdvisorHeadsUp(this, on));
        cs.addView(advCb);
        p.addView(cs);

        LinearLayout c5 = card();
        c5.addView(text("Entry and risk", 16, TXT, true));
        c5.addView(text("Entry style inside the zone", 13, DIM, false));
        List<String> modes = new ArrayList<>();
        modes.add("Auto (best found by backtest)"); modes.add("Edge of zone (fills most)"); modes.add("Middle of zone"); modes.add("Deep in zone (least risk, fills least)");
        final Spinner es = spinner(modes, Store.entryMode(this) + 1);
        c5.addView(es);
        c5.addView(text("Risk per trade ($), used for the money lines", 13, DIM, false));
        final int[] risks = {1, 2, 5, 10, 20, 50, 100, 200};
        List<String> rl = new ArrayList<>();
        int ri = 3;
        for (int i = 0; i < risks.length; i++) { rl.add("$" + risks[i]); if (risks[i] == Store.riskMoney(this)) ri = i; }
        final Spinner rs = spinner(rl, ri);
        c5.addView(rs);
        c5.addView(text("Wait for a precise entry (1m / 5m) before using the first entry zone", 13, DIM, false));
        final int[] waits = {0, 15, 30, 60, 120, 240};
        List<String> wl = new ArrayList<>();
        int wi = 2;
        for (int i = 0; i < waits.length; i++) { wl.add(waits[i] == 0 ? "Do not wait" : waits[i] + " minutes"); if (waits[i] == Store.refineWaitMin(this)) wi = i; }
        final Spinner ws = spinner(wl, wi);
        c5.addView(ws);
        final TextView calSt = text("", 12, DIM, false);
        int done = Store.book(this).items.size(), total = cfg.markets.size() * cfg.pairs.size();
        calSt.setText("Past win rates measured for " + done + " of " + total + " market/pair combinations. The scanner measures them by itself while it runs; this button does all now (several minutes).");
        c5.addView(calSt);
        final Button calBtn = button("Calibrate everything now", null);
        calBtn.setOnClickListener(v -> {
            calBtn.setEnabled(false);
            calSt.setText("Calibrating... keep the app open.");
            new Thread(() -> {
                int n = Calibrator.refreshStale(this, client, cfg, true);
                ui.post(() -> { calBtn.setEnabled(true); calSt.setText("Done: " + n + " combinations measured."); });
            }).start();
        });
        c5.addView(calBtn);
        AdapterView.OnItemSelectedListener l5 = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                Store.setEntryMode(MainActivity.this, es.getSelectedItemPosition() - 1);
                Store.setRiskMoney(MainActivity.this, risks[rs.getSelectedItemPosition()]);
                Store.setRefineWaitMin(MainActivity.this, waits[ws.getSelectedItemPosition()]);
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        };
        es.setOnItemSelectedListener(l5);
        rs.setOnItemSelectedListener(l5);
        ws.setOnItemSelectedListener(l5);
        p.addView(c5);

        LinearLayout c4 = card();
        c4.addView(text("How strict should it be?", 16, TXT, true));
        c4.addView(text("Minimum confluences (out of 7)", 13, DIM, false));
        List<String> scores = new ArrayList<>();
        for (int i = 3; i <= 7; i++) scores.add(String.valueOf(i));
        final Spinner ss = spinner(scores, Math.max(0, Math.min(4, Store.minScore(this) - 3)));
        c4.addView(ss);
        c4.addView(text("Minimum quality factors met (out of 6, 0 = off)", 13, DIM, false));
        List<String> qs = new ArrayList<>();
        for (int i = 0; i <= 6; i++) qs.add(String.valueOf(i));
        final Spinner qq = spinner(qs, Store.minQuality(this));
        c4.addView(qq);
        c4.addView(text("Check every", 13, DIM, false));
        final int[] secs = {30, 60, 120, 300};
        List<String> iv = new ArrayList<>();
        iv.add("30 seconds"); iv.add("1 minute"); iv.add("2 minutes"); iv.add("5 minutes");
        int cur = 1;
        for (int i = 0; i < secs.length; i++) if (secs[i] == Store.intervalSec(this)) cur = i;
        final Spinner is = spinner(iv, cur);
        c4.addView(is);
        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                Store.setMinScore(MainActivity.this, ss.getSelectedItemPosition() + 3);
                Store.setMinQuality(MainActivity.this, qq.getSelectedItemPosition());
                Store.setIntervalSec(MainActivity.this, secs[is.getSelectedItemPosition()]);
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        };
        ss.setOnItemSelectedListener(l);
        qq.setOnItemSelectedListener(l);
        is.setOnItemSelectedListener(l);
        p.addView(c4);

        p.addView(text("Strategy: liquidity sweep, then a structure break with displacement, then an FVG/order-block entry, with the target at the next liquidity. A 4H idea gives the zone, the 1H gives the entry zone, then the app zooms into the 5m and 1m inside it for the most precise entry, keeping the 4H target. Same chain for 1D, 1H, 15m and 5m ideas. The past win rate shown on alerts is measured from backtests: it is not a forecast. The score counts confluences and is not a win probability. This app does not place trades and is not financial advice.", 12, DIM, false));
        return scroll(p);
    }
}
