package com.smcscanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import com.smcscanner.chart.EquityChart;

/** Equity curve in R for the Strategy Lab. */
public class EquityView extends View {
    private double[] curve = new double[0];

    public EquityView(Context c) { super(c); }

    public void setCurve(double[] c) { curve = c == null ? new double[0] : c; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() < 10) return;
        EquityChart.draw(new AndroidGfx(canvas), getWidth(), getHeight(), curve, getResources().getDisplayMetrics().density, Theme.dark);
    }
}
