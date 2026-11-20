package com.smcscanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import com.smcscanner.chart.ChartRenderer;
import com.smcscanner.chart.PaChart;
import com.smcscanner.engine.Candle;
import com.smcscanner.engine.pa.PaEngine;

/** Read-only picture on each Bird's Eye card. */
public class PaChartView extends View {
    private ChartRenderer r;
    private PaEngine.Signal s;
    private Candle[] cs;
    private int builtW = -1;

    public PaChartView(Context c) { super(c); }

    public void setData(PaEngine.Signal s, Candle[] cs) { this.s = s; this.cs = cs; builtW = -1; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (s == null || cs == null || cs.length < 5 || getWidth() < 10) return;
        if (r == null || builtW != getWidth()) {
            r = PaChart.build(s, cs, Theme.dark, getResources().getDisplayMetrics().density, getWidth());
            builtW = getWidth();
        }
        r.nowSec = System.currentTimeMillis() / 1000;
        r.draw(new AndroidGfx(canvas), getWidth(), getHeight());
    }
}
