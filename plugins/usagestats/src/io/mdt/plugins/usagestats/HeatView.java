package io.mdt.plugins.usagestats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.usagestats.core.Heat;

/**
 * 游玩时长热力图（第 401 轮）：**范围 × 粒度**，正方形格 + 行标签 + 每格描边。
 *
 * <p>列数由粒度决定（时 24 / 日 7 / 周 13 / 月 12），行数由 {@link Heat.Grid#rows} 给；
 * 格宽 = 可用宽 / 列数（随屏幕缩放），格高 = 格宽（正方形）。
 *
 * <p>三种「空」按 {@link Heat.Grid#draw}：对齐空位 / 范围外 / 记录起点之前都不画；
 * 统计范围内每格**都画**（没玩过 = 最浅底 + 描边，用户 2026-11-19：「每个方块需要画框」）。
 *
 * <p>轴：**列轴在 {@link HeatAxis}**（固定不滚，因为图区会滚）；本 View 只画行标签
 * （取 {@link Heat.Grid#rowLabel}，行多时隔行标）。格宽 / 左边距公式与 HeatAxis 共用。
 */
public final class HeatView extends View {

    private final PluginTheme th;
    private final Heat.Grid grid;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int gap;
    private final int radius;
    private final int labelW;

    public HeatView(Context c, PluginTheme th, Heat.Grid grid) {
        super(c);
        this.th = th;
        this.grid = grid;
        this.gap = Math.max(1, th.dp(2));
        this.radius = Math.max(1, th.dp(2));
        this.labelW = th.dp(42);
        this.textPaint.setColor(th.textMuted);
        this.textPaint.setTextSize(th.sp(8));
        this.strokePaint.setStyle(Paint.Style.STROKE);
        this.strokePaint.setStrokeWidth(Math.max(1f, th.dp(1)));
        this.strokePaint.setColor(th.cardStroke);
    }

    // ---- 与 HeatAxis 共用的几何 / 列标签 ---------------------------------------

    static int cellFor(int width, int labelW, int gap, int cols) {
        int avail = width - labelW - (Math.max(1, cols) - 1) * gap;
        return Math.max(1, avail / Math.max(1, cols));
    }

    static int leftFor(int width, int labelW, int gap, int cols, int cell) {
        int gridW = cols * cell + (cols - 1) * gap;
        return labelW + (width - labelW - gridW) / 2;
    }

    /** 顶部列标签（按粒度固定；null = 这一列不标）。 */
    static String colLabel(Heat.Grain grain, int col, String[] weekdays) {
        if (grain == Heat.Grain.HOUR) {
            return (col % 6 == 0) ? String.valueOf(col) : null;
        }
        if (grain == Heat.Grain.DAY) {
            return weekdays[col % weekdays.length];
        }
        if (grain == Heat.Grain.WEEK) {
            return (col % 6 == 0) ? String.valueOf(col + 1) : null;   // 1 / 7 / 13
        }
        return (col % 3 == 0) ? String.valueOf(col + 1) : null;      // 1 / 4 / 7 / 10
    }

    static String[] weekdays(Context c) {
        try {
            return c.getResources().getStringArray(R.array.us_weekdays);
        } catch (Throwable t) {
            return new String[]{"M", "T", "W", "T", "F", "S", "S"};
        }
    }

    // ---- 测量 / 绘制 -----------------------------------------------------------

    private int cellFor(int width) {
        return cellFor(width, labelW, gap, Math.max(1, grid.cols));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = th.dp(280);
        int cell = cellFor(width);
        int rows = Math.max(0, grid.rows);
        // 行标签随图滚（列轴在 HeatAxis，固定）；底部留图例
        setMeasuredDimension(width, rows * (cell + gap) + th.dp(14));
    }

    @Override protected void onDraw(Canvas c) {
        int width = getWidth();
        int cell = cellFor(width);
        int cols = Math.max(1, grid.cols);
        int left = leftFor(width, labelW, gap, cols, cell);

        // 行标签（行多时隔行标）
        int stride = Math.max(1, grid.rows / 8);
        for (int row = 0; row < grid.rows; row++) {
            if (stride > 1 && row % stride != 0 && row != grid.rows - 1) continue;
            String lab = grid.rowLabel(row);
            if (lab.isEmpty()) continue;
            float tw = textPaint.measureText(lab);
            float y = row * (cell + gap) + cell / 2f + th.sp(3);
            c.drawText(lab, labelW - th.dp(4) - tw, y, textPaint);
        }

        // 格子：统计范围内每格都画（最浅底 + 描边），范围外 / 起点前不画
        for (int row = 0; row < grid.rows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= grid.draw.length || !grid.draw[idx]) continue;
                int lv = Heat.level(grid.ms[idx], grid.boundsMin);
                float x = left + col * (cell + gap);
                float y = row * (cell + gap);
                rect.set(x, y, x + cell, y + cell);
                cellPaint.setColor(fill(lv));
                c.drawRoundRect(rect, radius, radius, cellPaint);
                c.drawRoundRect(rect, radius, radius, strokePaint);
            }
        }

        // 图例：少 [■][■][■][■][■] 多
        int ly = Math.max(0, grid.rows) * (cell + gap) + th.dp(2);
        String less = getContext().getString(R.string.us_heat_less);
        String more = getContext().getString(R.string.us_heat_more);
        float x = left;
        float baseY = ly + th.sp(8);
        c.drawText(less, x, baseY, textPaint);
        x += textPaint.measureText(less) + th.dp(4);
        int sw = th.dp(7);
        for (int lv = 1; lv <= Heat.LEVELS; lv++) {
            cellPaint.setColor(fill(lv));
            rect.set(x, ly, x + sw, ly + sw);
            c.drawRoundRect(rect, radius, radius, cellPaint);
            c.drawRoundRect(rect, radius, radius, strokePaint);
            x += sw + th.dp(2);
        }
        c.drawText(more, x + th.dp(2), baseY, textPaint);
    }

    /** 档位 ⇒ 颜色（accent 调透明度；深色下最浅档略亮一点）。 */
    private int fill(int level) {
        double a;
        switch (level) {
            case 0: a = th.dark ? 0.14 : 0.08; break;
            case 1: a = 0.24; break;
            case 2: a = 0.42; break;
            case 3: a = 0.60; break;
            case 4: a = 0.78; break;
            default: a = 1.0; break;
        }
        int alpha = (int) Math.round(a * 255.0);
        return Color.argb(alpha, Color.red(th.accent), Color.green(th.accent), Color.blue(th.accent));
    }
}
