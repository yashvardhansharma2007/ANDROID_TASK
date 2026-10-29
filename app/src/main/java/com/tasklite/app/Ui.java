package com.tasklite.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Colours, view factories and number formatting. */
final class Ui {
    static final int BG = 0xFF0E131B, NAV = 0xFF121826, CARD = 0xFF1A2231, CARD_ALT = 0xFF263148;
    static final int TEXT = 0xFFE8EDF4, SUB = 0xFF8A97AB;
    static final int ACCENT = 0xFF4DD0E1, GREEN = 0xFF66BB6A, ORANGE = 0xFFFFB74D, RED = 0xFFEF5350,
            PURPLE = 0xFFB388FF;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView tv(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        l.setBackground(round(c, CARD, 16));
        return l;
    }

    /** green -> orange -> red depending on load 0..1 */
    static int heat(float r) {
        if (r < 0.5f) return GREEN;
        if (r < 0.8f) return ORANGE;
        return RED;
    }

    static String bytes(long b) {
        if (b < 0) return "--";
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, mb < 10 ? "%.1f MB" : "%.0f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    static String kb(long kb) {
        return kb < 0 ? "--" : bytes(kb * 1024L);
    }

    static String rate(long bps) {
        return bps < 0 ? "--" : bytes(bps) + "/s";
    }

    static String duration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + (s % 60) + "s";
        return s + "s";
    }

    static String ago(long t) {
        if (t <= 0) return "never";
        long d = System.currentTimeMillis() - t;
        if (d < 60_000) return "just now";
        long m = d / 60_000;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }
}
