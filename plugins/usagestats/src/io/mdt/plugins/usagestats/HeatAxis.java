package io.mdt.plugins.usagestats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.usagestats.core.Heat;

/**
 * 热力图顶部的**列轴**（第 401 轮）：固定在图区上方、不随图滚动。
 *
 * <p>列宽 / 左边距与 {@link HeatView} 共用同一套公式（{@link HeatView#cellFor} /
 * {@link HeatView#leftFor}），所以标签一定对齐格子。
 */
final class HeatAxis extends View {

    private final PluginTheme th;
    private final Heat.Grid grid;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int labelW;
    private final int gap;
    private final String[] weekdays;

    HeatAxis(Context c, PluginTheme th, Heat.Grid grid) {
        super(c);
        this.th = th;
        this.grid = grid;
        this.labelW = th.dp(42);
        this.gap = Math.max(1, th.dp(2));
        this.paint.setColor(th.textMuted);
        this.paint.setTextSize(th.sp(8));
        this.weekdays = HeatView.weekdays(c);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = th.dp(280);
        setMeasuredDimension(width, th.dp(14));
    }

    @Override protected void onDraw(Canvas c) {
        int width = getWidth();
        int cols = Math.max(1, grid.cols);
        int cell = HeatView.cellFor(width, labelW, gap, cols);
        int left = HeatView.leftFor(width, labelW, gap, cols, cell);
        for (int col = 0; col < cols; col++) {
            String lab = HeatView.colLabel(grid.grain, col, weekdays);
            if (lab == null) continue;
            float x = left + col * (cell + gap) + cell / 2f;
            float tw = paint.measureText(lab);
            c.drawText(lab, x - tw / 2f, th.dp(11), paint);
        }
    }
}
