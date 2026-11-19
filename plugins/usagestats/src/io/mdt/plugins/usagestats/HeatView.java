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
 * 年视图热力图（第 397 轮）：**一个方块 = 一周**，52 块排成 13 列 × 4 行。
 *
 * <p>为什么用一个自定义 View、不拼 52 个控件：方块要严格按同一套坐标对齐、
 * 并且统一按主题色画深浅；控件树只会让每个方块多一份 layout。
 *
 * <p>颜色全部来自 {@link PluginTheme#accent}（按档位调透明度）⇒ 深浅色模式自动跟着宿主变；
 * 图例只标「少 / 多」，具体数字交给卡片里的文字合计（图本身不说话）。
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

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = th.dp(280);
        int cell = cellFor(width);
        setMeasuredDimension(width, Heat.ROWS * (cell + gap) + th.dp(16));
    }

    private int cellFor(int width) {
        int avail = width - (Heat.COLS - 1) * gap;
        return Math.max(1, avail / Heat.COLS);
    }

    @Override protected void onDraw(Canvas c) {
        int width = getWidth();
        int cell = cellFor(width);
        int left = (width - (Heat.COLS * cell + (Heat.COLS - 1) * gap)) / 2;

        // 52 个方块：行 * COLS + 列；weekKnown = false 不画（起点之前 / 今天之后）
        for (int row = 0; row < Heat.ROWS; row++) {
            for (int col = 0; col < Heat.COLS; col++) {
                int idx = row * Heat.COLS + col;
                if (!grid.weekKnown[idx]) continue;
                int lv = Heat.level(grid.weekMs[idx]);
                cellPaint.setColor(fill(lv));
                float x = left + col * (cell + gap);
                float y = row * (cell + gap);
                rect.set(x, y, x + cell, y + cell);
                c.drawRoundRect(rect, radius, radius, cellPaint);
            }
        }

        // 图例：少 [■][■][■][■][■] 多
        int ly = Heat.ROWS * (cell + gap) + th.dp(3);
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

    /** 档位 ⇒ 颜色（accent 调透明度；深色下最浅档略亮一点，否则同色）。 */
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
