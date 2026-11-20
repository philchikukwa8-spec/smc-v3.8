package com.smcscanner.app;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.smcscanner.chart.Gfx;

/** Draws the chart on a phone canvas. */
public final class AndroidGfx implements Gfx {
    private final Canvas cv;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean clipped;

    public AndroidGfx(Canvas cv) { this.cv = cv; }

    @Override public void clip(float l, float t, float rr, float b) { cv.save(); cv.clipRect(l, t, rr, b); clipped = true; }

    @Override public void unclip() { if (clipped) { cv.restore(); clipped = false; } }

    @Override public void fillRect(float l, float t, float rr, float b, int argb) {
        p.setStyle(Paint.Style.FILL); p.setPathEffect(null); p.setColor(argb);
        cv.drawRect(l, t, rr, b, p);
    }

    @Override public void strokeRect(float l, float t, float rr, float b, float width, int argb) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width); p.setPathEffect(null); p.setColor(argb);
        cv.drawRect(l, t, rr, b, p);
    }

    @Override public void fillRound(float l, float t, float rr, float b, float radius, int argb) {
        p.setStyle(Paint.Style.FILL); p.setPathEffect(null); p.setColor(argb);
        r.set(l, t, rr, b);
        cv.drawRoundRect(r, radius, radius, p);
    }

    @Override public void line(float x1, float y1, float x2, float y2, float width, int argb, boolean dashed) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width); p.setColor(argb);
        p.setPathEffect(dashed ? new DashPathEffect(new float[]{width * 5, width * 4}, 0) : null);
        cv.drawLine(x1, y1, x2, y2, p);
        p.setPathEffect(null);
    }

    @Override public void text(String s, float x, float y, float size, int argb, boolean bold, int align) {
        p.setStyle(Paint.Style.FILL); p.setPathEffect(null); p.setColor(argb);
        p.setTextSize(size); p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        float w = p.measureText(s);
        cv.drawText(s, align == CENTER ? x - w / 2 : align == RIGHT ? x - w : x, y, p);
    }

    @Override public float textWidth(String s, float size, boolean bold) {
        p.setTextSize(size); p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p.measureText(s);
    }
}
