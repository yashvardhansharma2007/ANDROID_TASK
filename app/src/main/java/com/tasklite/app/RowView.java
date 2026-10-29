package com.tasklite.app;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Recyclable list row used by both lists (built in code: no XML inflation cost). */
final class RowView extends FrameLayout {
    final ImageView icon;
    final TextView title, sub, v1, v2;
    final BarView bar;

    RowView(Context c, boolean withIcon) {
        super(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 12);
        box.setPadding(p, Ui.dp(c, 10), p, Ui.dp(c, 10));
        box.setBackground(Ui.round(c, Ui.CARD, 12));
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(Ui.dp(c, 12), Ui.dp(c, 3), Ui.dp(c, 12), Ui.dp(c, 3));
        addView(box, boxLp);

        if (withIcon) {
            icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
            ilp.rightMargin = Ui.dp(c, 12);
            box.addView(icon, ilp);
        } else {
            icon = null;
        }

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        title = Ui.tv(c, 15, Ui.TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        sub = Ui.tv(c, 12, Ui.SUB, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        bar = new BarView(c);
        mid.addView(title);
        mid.addView(sub);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, Ui.dp(c, 4));
        blp.topMargin = Ui.dp(c, 6);
        mid.addView(bar, blp);
        box.addView(mid, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        v1 = Ui.tv(c, 15, Ui.TEXT, true);
        v2 = Ui.tv(c, 11, Ui.SUB, false);
        right.addView(v1);
        right.addView(v2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = Ui.dp(c, 10);
        box.addView(right, rlp);
    }
}
