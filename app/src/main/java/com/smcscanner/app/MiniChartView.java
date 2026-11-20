package com.smcscanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import com.smcscanner.chart.ChartRenderer;
import com.smcscanner.chart.MieChart;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.mie.Mie;

/** Read-only picture on each Advisor card: candles, zone, 15m trigger, shaded risk and reward. */
public class MiniChartView extends View {
    private ChartRenderer r;
    private Mie.Assessment a;
    private Candle[] cs;
    private int builtW = -1;
    private long gran = 900;

    public MiniChartView(Context c) { super(c); }

    public void setData(Mie.Assessment a, Candle[] cs, long gran) {
        this.a = a; this.cs = cs; this.gran = gran; builtW = -1; invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (a == null || cs == null || cs.length < 5 || getWidth() < 10) return;
        if (r == null || builtW != getWidth()) {
            r = MieChart.build(a, cs, gran, Theme.dark, getResources().getDisplayMetrics().density, getWidth());
            builtW = getWidth();
        }
        r.nowSec = System.currentTimeMillis() / 1000;
        r.draw(new AndroidGfx(canvas), getWidth(), getHeight());
    }
}
