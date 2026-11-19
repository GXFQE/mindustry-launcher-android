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
 * 游玩时长热力图（第 400 轮）：**范围 × 粒度**，正方形格。
 *
 * <p>列数由粒度决定（时 24 / 日 7 / 周 13 / 月 12），行数由 {@link Heat.Grid#rows} 给；
 * 格宽 = 可用宽 / 列数（随屏幕缩放），格高 = 格宽（正方形）。
 *
 * <p>三种「空」按 {@link Heat.Grid#draw}：对齐空位 / 范围外 / 记录起点之前都不画；
 * 画出来的 0 毫秒格 = 最浅色。图例只标「少 / 多」，数字交给旁边的文字合计。
 */
public final class HeatView extends View {

    private final PluginTheme th;
    private final Heat.Grid grid;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int gap;
    private final int radius;

    public HeatView(Context c, PluginTheme th, Heat.Grid grid) {
        super(c);
        this.th = th;
        this.grid = grid;
        this.gap = Math.max(1, th.dp(2));
        this.radius = Math.max(1, th.dp(2));
        this.textPaint.setColor(th.textMuted);
        this.textPaint.setTextSize(th.sp(8));
    }

    private int cellFor(int width) {
        int cols = Math.max(1, grid.cols);
        int avail = width - (cols - 1) * gap;
        return Math.max(1, avail / cols);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = th.dp(280);
        int cell = cellFor(width);
        int rows = Math.max(0, grid.rows);
        setMeasuredDimension(width, rows * (cell + gap) + th.dp(16));
    }

    @Override protected void onDraw(Canvas c) {
        int width = getWidth();
        int cell = cellFor(width);
        int cols = Math.max(1, grid.cols);
        int left = (width - (cols * cell + (cols - 1) * gap)) / 2;

        for (int row = 0; row < grid.rows; row++) {
            for (int col = 0; col < cols; col++) {
                int idx = row * cols + col;
                if (idx >= grid.draw.length || !grid.draw[idx]) continue;
                int lv = Heat.level(grid.grain, grid.ms[idx]);
                cellPaint.setColor(fill(lv));
                float x = left + col * (cell + gap);
                float y = row * (cell + gap);
                rect.set(x, y, x + cell, y + cell);
                c.drawRoundRect(rect, radius, radius, cellPaint);
            }
        }

        // 图例：少 [■][■][■][■][■] 多
        int ly = Math.max(0, grid.rows) * (cell + gap) + th.dp(3);
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
