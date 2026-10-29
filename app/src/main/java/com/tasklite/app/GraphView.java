package com.tasklite.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Rolling line graph backed by a fixed ring buffer. Nothing is allocated in onDraw(). */
final class GraphView extends View {
    private static final int N = 90;
    private final float[] vals = new float[N];
    private int head, count;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint titleP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stroke = new Path();
    private final Path area = new Path();
    private final String name;
    private final float d;
    private String valueText = "--";

    GraphView(Context c, String name, int color) {
        super(c);
        this.name = name;
        d = c.getResources().getDisplayMetrics().density;
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor((color & 0x00FFFFFF) | 0x30000000);
        fill.setStyle(Paint.Style.FILL);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1f);
        titleP.setColor(Ui.SUB);
        titleP.setTextSize(12f * d);
        valueP.setColor(Ui.TEXT);
        valueP.setTextSize(16f * d);
        valueP.setFakeBoldText(true);
        valueP.setTextAlign(Paint.Align.RIGHT);
    }

    void push(float v, String text) {
        vals[head] = v < 0 ? 0 : (v > 100 ? 100 : v);
        head = (head + 1) % N;
        if (count < N) count++;
        valueText = text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float left = 2 * d, right = w - 2 * d, top = 26 * d, bottom = h - 4 * d;
        c.drawText(name, left, 14 * d, titleP);
        c.drawText(valueText, right, 16 * d, valueP);
        for (int i = 0; i <= 2; i++) {
            float y = bottom - i * 0.5f * (bottom - top);
            c.drawLine(left, y, right, y, grid);
        }
        if (count < 2) return;
        float step = (right - left) / (N - 1);
        stroke.reset();
        area.reset();
        for (int k = 0; k < count; k++) {
            int idx = (head - count + k + N) % N;
            float x = right - (count - 1 - k) * step;
            float y = bottom - vals[idx] / 100f * (bottom - top);
            if (k == 0) {
                stroke.moveTo(x, y);
                area.moveTo(x, bottom);
                area.lineTo(x, y);
            } else {
                stroke.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(right, bottom);
        area.close();
        c.drawPath(area, fill);
        c.drawPath(stroke, line);
    }
}
