package com.archbox.container;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Terminal look for every Archbox screen: black background, monospace text, one accent per meaning. */
final class Term {
    static final int BG = 0xFF0A0C0A;
    static final int FG = 0xFFC8D3C8;
    static final int DIM = 0xFF6B7A6B;
    static final int GREEN = 0xFF7CFC9A;   /* prompt, success, the action you take */
    static final int AMBER = 0xFFFFC857;   /* warnings and "probably fine" lines */
    static final int RED = 0xFFFF6B6B;     /* real failures */

    private Term() {}

    static TextView line(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** A bordered, prompt-style button: [ label ] in green on black. */
    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText("[ " + label + " ]");
        b.setTextColor(GREEN);
        b.setTypeface(Typeface.MONOSPACE);
        b.setAllCaps(false);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(BG);
        edge.setStroke(2, GREEN);
        b.setBackground(edge);
        return b;
    }

    /** A terminal-styled notice in place of a platform alert: header, body, one [ ok ] action. */
    static void notice(Context c, String header, String body, Runnable onOk) {
        Dialog d = new Dialog(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(48, 48, 48, 32);

        col.addView(line(c, "$ " + header, 14, GREEN));
        TextView body1 = line(c, body, 14, FG);
        body1.setPadding(0, 32, 0, 32);
        col.addView(body1);

        Button ok = button(c, "ok");
        ok.setOnClickListener(v -> { d.dismiss(); if (onOk != null) onOk.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.END;
        col.addView(ok, lp);

        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(col);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        d.show();
    }
}
