package com.smcscanner.app;

import android.content.Context;
import android.graphics.Canvas;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.smcscanner.chart.ChartRenderer;

/** The live chart: drag to scroll, pinch to zoom, long-press for the crosshair, double-tap to jump to the latest candle. */
public class ChartView extends View {
    public final ChartRenderer renderer = new ChartRenderer();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private boolean crosshairMode;
    /** called when the chart is tapped (not dragged) */
    public interface TapListener { void onTap(float x, float y); }
    public TapListener tapListener;

    public ChartView(Context c) {
        super(c);
        renderer.density = c.getResources().getDisplayMetrics().density;
        renderer.setDark(Theme.dark);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (crosshairMode) return true;
                renderer.pan(-dx);
                invalidate();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) tapListener.onTap(e.getX(), e.getY());
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { renderer.resetView(); invalidate(); return true; }
            @Override public void onLongPress(MotionEvent e) {
                crosshairMode = true;
                renderer.setCrosshair(e.getX(), e.getY());
                invalidate();
            }
        });
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom(d.getScaleFactor(), d.getFocusX());
                invalidate();
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        if (!scale.isInProgress()) gestures.onTouchEvent(e);
        int a = e.getActionMasked();
        if (crosshairMode && a == MotionEvent.ACTION_MOVE) { renderer.setCrosshair(e.getX(), e.getY()); invalidate(); }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (crosshairMode) { crosshairMode = false; renderer.clearCrosshair(); invalidate(); }
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.nowSec = System.currentTimeMillis() / 1000;
        renderer.draw(new AndroidGfx(canvas), getWidth(), getHeight());
        if (renderer.live) postInvalidateDelayed(1000); // keeps the candle countdown ticking
    }
}
