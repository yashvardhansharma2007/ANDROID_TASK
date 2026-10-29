package com.tasklite.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** One vertical bar per CPU core. */
final class CoreBarsView extends View {
    private float[] load = new float[0];
    private String[] labels = new String[0];
    private String message;
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    CoreBarsView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        back.setColor(0x22FFFFFF);
        txt.setColor(Ui.SUB);
        txt.setTextSize(10f * d);
        txt.setTextAlign(Paint.Align.CENTER);
    }

    void set(float[] l) {
        if (l == null) {
            message = "Per-core data is blocked by Android on this device";
            load = new float[0];
        } else {
            message = null;
            if (labels.length != l.length) {
                labels = new String[l.length];
                for (int i = 0; i < l.length; i++) labels[i] = "C" + i;
            }
            load = l;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(0, w), Math.round(64 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (message != null || load.length == 0) {
            txt.setTextAlign(Paint.Align.LEFT);
            c.drawText(message == null ? "" : message, 0, h / 2f, txt);
            txt.setTextAlign(Paint.Align.CENTER);
            return;
        }
        int n = load.length;
        float gap = 4 * d, barW = (w - gap * (n - 1)) / n, chartH = h - 14 * d, rad = 3 * d;
        for (int i = 0; i < n; i++) {
            float x = i * (barW + gap);
            r.set(x, 0, x + barW, chartH);
            c.drawRoundRect(r, rad, rad, back);
            float v = load[i];
            if (v >= 0) {
                bar.setColor(Ui.heat(v));
                float top = chartH - Math.max(2 * d, v * chartH);
                r.set(x, top, x + barW, chartH);
                c.drawRoundRect(r, rad, rad, bar);
            }
            c.drawText(labels[i], x + barW / 2f, h - 2 * d, txt);
        }
    }
}
