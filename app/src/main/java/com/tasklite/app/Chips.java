package com.tasklite.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A row of pill buttons where exactly one is selected. */
final class Chips extends LinearLayout {
    interface OnPick { void pick(int index); }

    private final TextView[] items;
    private int selected;

    Chips(Context c, String[] labels, int selected, final OnPick cb) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        items = new TextView[labels.length];
        this.selected = selected;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = Ui.tv(c, 12, Ui.SUB, false);
            t.setText(labels[i]);
            t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
            t.setOnClickListener(v -> {
                select(idx);
                cb.pick(idx);
            });
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(c, 6);
            addView(t, lp);
            items[i] = t;
        }
        paint();
    }

    void select(int i) {
        selected = i;
        paint();
    }

    private void paint() {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == selected;
            items[i].setTextColor(on ? Ui.BG : Ui.SUB);
            items[i].setBackground(Ui.round(getContext(), on ? Ui.ACCENT : Ui.CARD_ALT, 16));
        }
    }

    /** Standalone pill used for on/off toggles and buttons. */
    static TextView pill(Context c, String text, boolean on) {
        TextView t = Ui.tv(c, 12, on ? Ui.BG : Ui.SUB, false);
        t.setText(text);
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        t.setBackground(Ui.round(c, on ? Ui.ACCENT : Ui.CARD_ALT, 16));
        return t;
    }

    static void setPill(TextView t, boolean on) {
        t.setTextColor(on ? Ui.BG : Ui.SUB);
        t.setBackground(Ui.round(t.getContext(), on ? Ui.ACCENT : Ui.CARD_ALT, 16));
    }
}
