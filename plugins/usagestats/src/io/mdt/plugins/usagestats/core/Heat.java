package io.mdt.plugins.usagestats.core;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 年视图热力网格（第 397 轮）：**一个方块 = 一周**，颜色深浅 = 那一周合计的前台时长。
 *
 * <p>为什么是周块、不是日格：用户 2026-11-19 定「按年的可以以周划块的」—— 一年 52 个方块
 * 排成 13×4（每块约 20dp）比 371 个日格（每格 4dp）可读得多；周内细节（哪天玩）留给以后的
 * 单日视图。
 *
 * <p>状态分开：**数据起点之前 / 今天之后 ⇒ 不画**（{@link Grid#weekKnown} = false）；
 * **在区间内但 0 毫秒 ⇒ 最浅一格**（{@link #level} = 0）—— 把空周和"还没开始记录"画成
 * 一样，用户会以为那几周在玩。
 *
 * <p>纯函数（不碰 Android）：PC 侧 `build.sh check` 直接测。
 */
public final class Heat {

    /** 每行 13 周（≈ 一个季度），4 行 = 52 周。 */
    public static final int COLS = 13;
    public static final int ROWS = 4;
    public static final int WEEKS = COLS * ROWS;
    public static final int LEVELS = 5;

    /** 52 个方块；索引 = 行 * COLS + 列，第 0 格 = 最早那一周。 */
    public static final class Grid {
        /** 每周合计的前台毫秒。 */
        public final long[] weekMs = new long[WEEKS];
        /** 这一周与「有记录区间」有交集（false ⇒ 不画）。 */
        public final boolean[] weekKnown = new boolean[WEEKS];
        /** 这一周的周一日期（yyyy-MM-dd；诊断 / 自检用）。 */
        public final String[] weekKey = new String[WEEKS];
        /** 数据起点（yyyy-MM-dd）；没有任何流水 = 空串。 */
        public final String firstDay;
        /** 今天的日期（yyyy-MM-dd）。 */
        public final String today;
        /** 本周一的日期（yyyy-MM-dd）。 */
        public final String thisMonday;

        Grid(String firstDay, String today, String thisMonday) {
            this.firstDay = firstDay == null ? "" : firstDay;
            this.today = today == null ? "" : today;
            this.thisMonday = thisMonday == null ? "" : thisMonday;
        }
    }

    private Heat() {}

    /** 一周合计 ⇒ 颜色档位（0 = 有记录但没玩；1~5 越久越深）。 */
    public static int level(long ms) {
        if (ms <= 0L) return 0;
        if (ms < 3_600_000L) return 1;                 // < 1 小时/周
        if (ms < 3L * 3_600_000L) return 2;            // < 3 小时
        if (ms < 7L * 3_600_000L) return 3;            // < 7 小时
        if (ms < 15L * 3_600_000L) return 4;           // < 15 小时
        return 5;
    }

    /** 网格里所有「在记录区间内」方块之和（与图上同源，别另算一遍）。 */
    public static long total(Grid g) {
        if (g == null) return 0L;
        long sum = 0L;
        for (int i = 0; i < WEEKS; i++) {
            if (g.weekKnown[i]) sum += g.weekMs[i];
        }
        return sum;
    }

    /**
     * 生成最近 {@link #WEEKS} 周的网格。
     *
     * @param days 按天聚合的时长（{@link Sessions#byDay}；调用方已按槽过滤）
     * @param now  当前时刻（墙钟毫秒；PC 自检传固定值）
     */
    public static Grid grid(List<Sessions.Day> days, long now, TimeZone tz) {
        TimeZone zone = tz == null ? TimeZone.getDefault() : tz;
        Map<String, Long> byDate = new HashMap<String, Long>();
        String first = "";
        if (days != null) {
            for (Sessions.Day d : days) {
                if (d == null || d.date == null || d.date.isEmpty()) continue;
                Long old = byDate.get(d.date);
                byDate.put(d.date, (old == null ? 0L : old.longValue()) + d.ms);
                if (first.isEmpty() || d.date.compareTo(first) < 0) first = d.date;
            }
        }
        String today = Sessions.dayOf(now, zone);
        long monday = mondayStart(now, zone);
        Grid g = new Grid(first, today, Sessions.dayOf(monday, zone));

        // 最左 / 最早那一格的周一 = 本周一往前推 (WEEKS-1) 周
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(monday);
        c.add(Calendar.DAY_OF_YEAR, -(WEEKS - 1) * 7);
        Calendar day = Calendar.getInstance(zone);
        for (int w = 0; w < WEEKS; w++) {
            day.setTimeInMillis(c.getTimeInMillis());
            long sum = 0L;
            boolean known = false;
            for (int k = 0; k < 7; k++) {
                String key = Sessions.dayOf(day.getTimeInMillis(), zone);
                Long v = byDate.get(key);
                if (v != null && v.longValue() > 0L) sum += v.longValue();
                if (!first.isEmpty() && key.compareTo(first) >= 0 && key.compareTo(today) <= 0) {
                    known = true;
                }
                day.add(Calendar.DAY_OF_YEAR, 1);
            }
            g.weekMs[w] = sum;
            g.weekKnown[w] = known;
            g.weekKey[w] = Sessions.dayOf(c.getTimeInMillis(), zone);
            c.add(Calendar.DAY_OF_YEAR, 7);        // 周一 → 下周一（Calendar 处理夏令时）
        }
        return g;
    }

    /** 把某一时刻清到当天 00:00 再回到本周一。 */
    static long mondayStart(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int dow = c.get(Calendar.DAY_OF_WEEK);   // SUNDAY=1 .. SATURDAY=7
        c.add(Calendar.DAY_OF_YEAR, -((dow + 5) % 7));   // MONDAY=2 -> 0
        return c.getTimeInMillis();
    }
}
