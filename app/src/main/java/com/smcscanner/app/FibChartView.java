package com.smcscanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import com.smcscanner.chart.ChartRenderer;
import com.smcscanner.chart.FibChart;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.fib.FibEngine;

/** The Fib chart window: candles with both fibs, the three breaks and the order drawn automatically. */
public class FibChartView extends View {
    private ChartRenderer r;
    private FibEngine.Setup s;
    private Candle[] cs;
    private boolean h4;
    private int builtW = -1;

    public FibChartView(Context c) { super(c); }

    public void setData(FibEngine.Setup s, Candle[] cs, boolean h4View) { this.s = s; this.cs = cs; this.h4 = h4View; builtW = -1; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (s == null || cs == null || cs.length < 5 || getWidth() < 10) return;
        if (r == null || builtW != getWidth()) {
            r = FibChart.build(s, cs, h4, Theme.dark, getResources().getDisplayMetrics().density, getWidth());
            builtW = getWidth();
        }
        r.draw(new AndroidGfx(canvas), getWidth(), getHeight());
    }
}
