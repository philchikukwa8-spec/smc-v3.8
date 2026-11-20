package com.smcscanner.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Small building blocks for the cards: pills, path chips, key-number grid, foldable sections. */
final class UiKit {
    private UiKit() {}

    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static TextView label(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** a small rounded label such as BUY, Grade A or Zooming in */
    /** the Legacy Arc emblem with the app name, used at the top of the main screens */
    static View brandRow(Context c, String title, String subtitle) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int id = c.getResources().getIdentifier("emblem", "drawable", c.getPackageName());
        if (id != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setImageResource(id);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            row.addView(iv, new LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)));
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 12), 0, 0, 0);
        col.addView(label(c, title, 22, Theme.txt, true));
        col.addView(label(c, subtitle, 12, Theme.accent, true));
        row.addView(col);
        return row;
    }

    static TextView pill(Context c, String s, int bg, int fg) {
        TextView t = label(c, s, 12, fg, true);
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        t.setBackground(round(bg, dp(c, 20)));
        return t;
    }

    /** 4H idea > 1H zone > 5m > 1m entry, with the confirmed steps highlighted */
    static View pathChips(Context c, List<String> labels, int confirmed) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                TextView ar = label(c, " › ", 14, Theme.arrow, false);
                row.addView(ar);
            }
            boolean ok = i < confirmed;
            TextView ch = label(c, labels.get(i), 12, ok ? Theme.chipOkTxt : Theme.dim, false);
            ch.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
            ch.setBackground(round(ok ? Theme.chipOk : Theme.chip, dp(c, 12)));
            row.addView(ch);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 10), 0, dp(c, 6));
        row.setLayoutParams(lp);
        return row;
    }

    /** big numbers: values[i] over names[i] */
    static View keyGrid(Context c, String[] values, String[] names) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
            cell.setBackground(round(Theme.tile, dp(c, 10)));
            TextView v = label(c, values[i], 14, Theme.txt, true);
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            v.setGravity(Gravity.CENTER);
            cell.addView(v);
            TextView n = label(c, names[i], 10, Theme.dim, false);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(c, 6), 0, 0, 0);
            row.addView(cell, lp);
        }
        return row;
    }

    /** "Risk $10.00" on the left, "Gain $129.09" on the right */
    static View moneyRow(Context c, String left, String right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = label(c, left, 13, Theme.red, true);
        TextView r = label(c, right, 13, Theme.green, true);
        r.setGravity(Gravity.RIGHT);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(r, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 8), 0, 0);
        row.setLayoutParams(lp);
        return row;
    }

    /** a section that folds open when you tap its title */
    static View section(final Context c, final String title, String body, boolean open) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(Theme.card, dp(c, 12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 8), 0, 0);
        box.setLayoutParams(lp);
        final TextView head = label(c, (open ? "▾  " : "▸  ") + title, 14, Theme.txt, true);
        head.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        final TextView text = label(c, body, 12, Theme.soft, false);
        text.setTypeface(Typeface.MONOSPACE);
        text.setPadding(dp(c, 14), 0, dp(c, 14), dp(c, 12));
        text.setTextIsSelectable(true);
        text.setVisibility(open ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            boolean show = text.getVisibility() != View.VISIBLE;
            text.setVisibility(show ? View.VISIBLE : View.GONE);
            head.setText((show ? "▾  " : "▸  ") + title);
        });
        box.addView(head);
        box.addView(text);
        return box;
    }
}
