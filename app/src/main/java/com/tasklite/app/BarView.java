package com.tasklite.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin rounded progress bar; no allocation while drawing. */
final class BarView extends View {
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint front = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float ratio;

    BarView(Context c) {
        super(c);
        back.setColor(0x22FFFFFF);
        front.setColor(Ui.ACCENT);
    }

    void set(float ratio, int color) {
        float v = ratio < 0 ? 0 : (ratio > 1 ? 1 : ratio);
        if (v != this.ratio || front.getColor() != color) {
            this.ratio = v;
            front.setColor(color);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, back);
        if (ratio > 0) {
            r.set(0, 0, Math.max(h, w * ratio), h);
            c.drawRoundRect(r, rad, rad, front);
        }
    }
}
