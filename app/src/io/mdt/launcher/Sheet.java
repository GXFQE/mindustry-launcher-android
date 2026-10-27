package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 「筛选与排序」这类**一列可点行**的弹窗（四个列表页共用）。
 *
 * <p>形状与 {@code AlertDialog.setItems} 一致（标题 + 一列行 + 「关闭」），但多了一件事：
 * 能在**两组之间画一条横向分割线** —— 第 283 轮把行序改成"排序一组 / 筛选一组"之后，
 * 两组之间没有任何视觉分隔，读起来还是一长串（用户 2026-10-27：「中间画个横向分割开吧」）。</p>
 *
 * <p>🔴 为什么不用 {@code setItems} + 一条"────"假行：框架的列表是 `ArrayAdapter`，
 * 插进去的字符串**照样可点、点了照样关窗**；而且它是**结构**（排序 vs 筛选）不是装饰，
 * 不该混在可点项里。这里改用 `setView` 自绘：行照样是 16sp、可点、带按压反馈。</p>
 *
 * <p>⚠️ 行样式（16sp / 16dp 左右留白 / 按压背景）要跟框架列表保持一致 —— 改了这里，
 * 四个页面的筛选弹窗会一起变样，别只对着一个页面调。</p>
 */
final class Sheet {

    private Sheet() { }

    /**
     * 弹出一列可点行。
     *
     * @param gapAt 在**第几行之前**画分组线；&lt;= 0 或 &gt;= 行数 ⇒ 不画（没有第二组时）
     */
    static void show(Activity a, int titleRes, List<String> labels, List<Runnable> acts, int gapAt) {
        if (a == null || labels == null || acts == null) return;
        final AlertDialog[] holder = new AlertDialog[1];
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(a, 16);
        int padV = dp(a, 14);
        for (int i = 0; i < labels.size() && i < acts.size(); i++) {
            if (i == gapAt) col.addView(groupGap(a));
            final int idx = i;
            TextView row = new TextView(a);
            row.setText(labels.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setTextColor(a.getResources().getColor(R.color.fg));
            row.setPadding(padH, padV, padH, padV);
            TypedValue tv = new TypedValue();
            if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    && tv.resourceId != 0) {
                row.setBackgroundResource(tv.resourceId);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { holder[0].dismiss(); } catch (Throwable ignored) { }
                    Runnable r = acts.get(idx);
                    if (r != null) r.run();
                }
            });
            col.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        ScrollView sc = new ScrollView(a);
        sc.addView(col, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        holder[0] = new AlertDialog.Builder(a)
                .setTitle(titleRes)
                .setView(sc)
                .setNegativeButton(R.string.close, null)
                .create();
        holder[0].show();
    }

    /** 两组之间那条线：上下各留一点空，别贴着行文字。 */
    private static View groupGap(Activity a) {
        View line = new View(a);
        line.setBackgroundColor(a.getResources().getColor(R.color.card_stroke));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1)));
        lp.topMargin = dp(a, 6);
        lp.bottomMargin = dp(a, 6);
        line.setLayoutParams(lp);
        return line;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
