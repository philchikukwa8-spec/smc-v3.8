package com.smcscanner.chart;

/** Tiny drawing surface so the chart can be drawn on Android and also checked off-device. Colours are ARGB ints. */
public interface Gfx {
    int LEFT = 0, CENTER = 1, RIGHT = 2;
    void clip(float l, float t, float r, float b);
    void unclip();
    void fillRect(float l, float t, float r, float b, int argb);
    void strokeRect(float l, float t, float r, float b, float width, int argb);
    void fillRound(float l, float t, float r, float b, float radius, int argb);
    void line(float x1, float y1, float x2, float y2, float width, int argb, boolean dashed);
    /** y is the text baseline */
    void text(String s, float x, float y, float size, int argb, boolean bold, int align);
    float textWidth(String s, float size, boolean bold);
}
