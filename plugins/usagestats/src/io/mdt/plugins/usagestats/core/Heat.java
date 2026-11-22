package io.mdt.plugins.usagestats.core;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 游玩时长热力图（第 400 轮）：**范围 × 粒度**。
 *
 * <p>粒度四档（用户 2026-11-19 定）：
 * <ul>
 *   <li>{@link Grain#HOUR}：24 列（0~23 时），一天一行；</li>
 *   <li>{@link Grain#DAY}：7 列（周一~周日），一周一行；</li>
 *   <li>{@link Grain#WEEK}：13 列（一季度 13 周），一行一季度；</li>
 *   <li>{@link Grain#MONTH}：12 列（1~12 月），一行一年。</li>
 * </ul>
 *
 * <p>行数 = ceil(格数 / 列数)，上限 {@link #MAX_ROWS}；超限 ⇒ {@link Grid#tooBig}
 * （UI 拒绝并提示，**不自动降档** —— 用户 2026-11-19：「建议拒绝提示」）。
 *
 * <p>对齐（「第一天不是周一就空着」）：
 * <ul>
 *   <li>日档从**起点所在周的周一**开始；</li>
 *   <li>周档从**起点所在季度的首周**（季度首日所在周的周一）开始；</li>
 *   <li>月档从**起点所在年的 1 月**开始；</li>
 *   <li>时档从起点当天的 0 点开始（按日选范围，天然对齐）。</li>
 * </ul>
 *
 * <p>三种「空」必须分开：
 * <ol>
 *   <li>对齐空位 / 范围外 ⇒ {@code draw = false}（不画）；</li>
 *   <li>在范围内、但在**记录起点之前** ⇒ {@code draw = false}（没记录 ≠ 没玩）；</li>
 *   <li>在范围内、记录起点之后、时长 0 ⇒ {@code draw = true}、{@code level = 0}（最浅一格）。</li>
 * </ol>
 *
 * <p>范围边界按**墙钟比例裁剪**（周档 / 月档的格可能被范围截断）：每条会话先按天 / 小时切，
 * 再把段裁到范围里、按比例分摊毫秒 —— 合计只含范围内的时间。
 *
 * <p>纯函数（不碰 Android）：PC 侧 `build.sh check` 直接测。
 */
public final class Heat {

    /** 粒度。 */
    public enum Grain { HOUR, DAY, WEEK, MONTH }

    /** 最多画多少行（用户 2026-11-19：「366 差不多」）。 */
    public static final int MAX_ROWS = 366;

    /** 颜色档位：0 = 有记录但没玩（最浅），1~5 越来越深。 */
    public static final int LEVELS = 5;

    /** 生成的网格；索引 = `row * cols + col`。 */
    public static final class Grid {
        public final Grain grain;
        public final int cols, rows;
        /** 每格的前台毫秒（只含范围内的部分）。 */
        public final long[] ms;
        /** 这一格要不要画（对齐空位 / 范围外 / 记录起点之前 = false）。 */
        public final boolean[] draw;
        /** 每格起点的日期键（诊断 / 自检：小时档是 "yyyy-MM-dd HH"，月档是 "yyyy-MM"）。 */
        public final String[] cellKey;
        /** 记录起点（yyyy-MM-dd）；没有任何流水 = 空串。 */
        public final String firstDay;
        /** 用户选的范围（本地日 0 点 ~ 结束日 23:59:59.999）。 */
        public final long from, to;
        /** 行数超过 {@link #MAX_ROWS}（UI 只提示、不画）。 */
        public final boolean tooBig;
        /** 范围比粒度还短（用户 2026-11-19：「拒绝统计时长小于精度」）。 */
        public final boolean tooSmall;
        /** 颜色档位阈值（分钟，4 个：第 2 / 3 / 4 / 5 档的下界）。 */
        public final int[] boundsMin;

        Grid(Grain grain, int cols, int rows, long from, long to, String firstDay,
             boolean tooBig, boolean tooSmall, int[] boundsMin) {
            this.grain = grain;
            this.cols = cols;
            this.rows = rows;
            this.from = from;
            this.to = to;
            this.firstDay = firstDay == null ? "" : firstDay;
            this.tooBig = tooBig;
            this.tooSmall = tooSmall;
            this.boundsMin = boundsMin == null ? defaultBounds(grain) : boundsMin;
            int n = Math.max(0, rows * cols);
            this.ms = new long[n];
            this.draw = new boolean[n];
            this.cellKey = new String[n];
        }

        /** 第 row 行的左侧标签（"MM-dd" / 年份）；没有 = 空串。 */
        public String rowLabel(int row) {
            if (row < 0 || row >= rows || cols <= 0) return "";
            int idx = row * cols;
            if (idx >= cellKey.length || cellKey[idx] == null) return "";
            String k = cellKey[idx];
            if (grain == Grain.MONTH) return k.length() >= 4 ? k.substring(0, 4) : k;
            if (grain == Grain.HOUR) return k.length() >= 10 ? k.substring(5, 10) : k;
            return k.length() >= 10 ? k.substring(5, 10) : k;
        }
    }

    private Heat() {}

    /** 每行的列数。 */
    public static int colsOf(Grain g) {
        if (g == Grain.HOUR) return 24;
        if (g == Grain.DAY) return 7;
        if (g == Grain.WEEK) return 13;
        return 12;
    }

    /**
     * 默认档位阈值（分钟，4 个：第 2 / 3 / 4 / 5 档的下界）。**都不超过一格的长度**。
     *
     * <p>口径统一：日 / 周 / 月都按「平均每天 30 分钟 / 1 小时 / 2 小时 / 4 小时」，
     * 所以周 = 日 × 7、月 = 日 × 30（月按 30 天近似）；时档按一小时内的密度
     * （10 / 20 / 40 / 55 分钟）。
     */
    public static int[] defaultBounds(Grain g) {
        if (g == Grain.HOUR) return new int[]{10, 20, 40, 55};
        if (g == Grain.DAY) return new int[]{30, 60, 120, 240};
        if (g == Grain.WEEK) return new int[]{210, 420, 840, 1680};
        return new int[]{900, 1800, 3600, 7200};
    }

    /** 一格最多多少分钟（自定义阈值不能超过它，否则最高档永远到不了）。 */
    public static int grainMaxMin(Grain g) {
        if (g == Grain.HOUR) return 60;
        if (g == Grain.DAY) return 24 * 60;
        if (g == Grain.WEEK) return 7 * 24 * 60;
        return 31 * 24 * 60;   // 最长月份
    }

    /**
     * 一格的前台毫秒 ⇒ 颜色档位（0 = 有记录但没玩）。
     *
     * <p>阈值按粒度分别定（一小时的 10 分钟和一整天的 10 分钟不是一回事）；
     * 用户可自定义（{@link Grid#boundsMin}）。
     */
    public static int level(Grain g, long ms) {
        return level(ms, defaultBounds(g));
    }

    /** 用给定的 4 个分钟阈值算档位（阈值必须递增；null / 长度不对 ⇒ 用日档默认）。 */
    public static int level(long ms, int[] boundsMin) {
        if (ms <= 0L) return 0;
        int[] b = (boundsMin != null && boundsMin.length == 4) ? boundsMin : defaultBounds(Grain.DAY);
        long min = ms / 60_000L;
        if (min < b[0]) return 1;
        if (min < b[1]) return 2;
        if (min < b[2]) return 3;
        if (min < b[3]) return 4;
        return 5;
    }

    /** 网格里所有「要画」的格子之和（与图上的格子同源）。 */
    public static long total(Grid g) {
        if (g == null) return 0L;
        long sum = 0L;
        for (int i = 0; i < g.ms.length && i < g.draw.length; i++) {
            if (g.draw[i]) sum += g.ms[i];
        }
        return sum;
    }

    /**
     * 生成网格。
     *
     * @param es    每局流水（调用方已按槽过滤；范围外的会被裁掉）
     * @param now   当前时刻（PC 自检传固定值；用来判「今天之后」）
     * @param grain 粒度
     * @param from  范围起点（本地日 0 点）
     * @param to    范围终点（本地日 23:59:59.999）
     */
    public static Grid grid(List<Sessions.Entry> es, long now, TimeZone tz, Grain grain,
                            long from, long to) {
        return grid(es, now, tz, grain, from, to, null);
    }

    /**
     * 生成网格（可带自定义档位阈值）。
     *
     * @param boundsMin 4 个分钟阈值（null = 该粒度的默认值）
     */
    public static Grid grid(List<Sessions.Entry> es, long now, TimeZone tz, Grain grain,
                            long from, long to, int[] boundsMin) {
        TimeZone zone = tz == null ? TimeZone.getDefault() : tz;
        if (grain == null) grain = Grain.DAY;
        if (to < from) to = from;
        long fromDay = dayStart(from, zone);
        long toDay = dayStart(to, zone);

        long firstStart = 0L;
        String firstDay = "";
        if (es != null) {
            for (Sessions.Entry e : es) {
                if (e == null || e.started <= 0L) continue;
                if (firstStart == 0L || e.started < firstStart) {
                    firstStart = e.started;
                    firstDay = Sessions.dayOf(e.started, zone);
                }
            }
        }
        long firstDayStart = firstDay.isEmpty() ? 0L : dayStart(dayStartMs(firstDay, zone), zone);

        int cols = colsOf(grain);
        // 先算对齐起点（第一格的起点）与格数
        long alignedFrom;
        int cells;
        if (grain == Grain.HOUR) {
            // 第 401 轮修正：从**范围起点当天** 0 点开始，每天一行（不补周一的空行 ——
            // 用户看到 1 天范围上面多出几天，否掉了按周对齐的版本）。
            alignedFrom = fromDay;
            cells = (int) (daysBetween(fromDay, toDay, zone) + 1) * 24;
        } else if (grain == Grain.DAY) {
            alignedFrom = weekStart(fromDay, zone);
            cells = (int) daysBetween(alignedFrom, weekEnd(toDay, zone), zone);
        } else if (grain == Grain.WEEK) {
            long q = quarterStart(fromDay, zone);
            alignedFrom = weekStart(q, zone);
            cells = (int) (weeksBetween(alignedFrom, toDay, zone) + 1);
        } else {
            alignedFrom = yearStart(fromDay, zone);
            cells = (int) (monthsBetween(alignedFrom, toDay, zone) + 1);
        }
        int rows = (cells + cols - 1) / cols;
        boolean tooBig = rows > MAX_ROWS;
        // 用户 2026-11-19：「拒绝统计时长小于精度」—— 范围比一个格子还短就没意义
        boolean tooSmall = !tooBig && (to - from + 1L) < grainSpanMs(grain, fromDay, zone);
        Grid g = new Grid(grain, cols, rows, from, to, firstDay, tooBig, tooSmall, boundsMin);
        if (tooBig || tooSmall || rows <= 0) return g;

        // 聚合桶（先裁剪到 [from, to]）
        Map<String, Long> dayMap = new HashMap<String, Long>();
        Map<String, Long> hourMap = grain == Grain.HOUR ? new HashMap<String, Long>() : null;
        if (es != null) {
            for (Sessions.Entry e : es) {
                if (e == null || e.activeMs <= 0L || e.started <= 0L) continue;
                if (hourMap != null) {
                    addHourClip(hourMap, e, from, to, zone);
                }
                addDayClip(dayMap, e, from, to, zone);
            }
        }

        // 按粒度生成每格
        for (int i = 0; i < cells; i++) {
            int row = i / cols;
            int col = i % cols;
            long cellStart, cellEnd;
            String key;
            boolean inRange, afterFirst;
            if (grain == Grain.HOUR) {
                Calendar c = Calendar.getInstance(zone);
                c.setTimeInMillis(alignedFrom);
                c.add(Calendar.HOUR_OF_DAY, i);
                cellStart = c.getTimeInMillis();
                c.add(Calendar.HOUR_OF_DAY, 1);
                cellEnd = c.getTimeInMillis();
                key = Sessions.dayOf(cellStart, zone) + " " + hourKey(cellStart, zone);
                inRange = cellStart >= from && cellStart <= to;
                afterFirst = firstDayStart > 0L && cellEnd > firstDayStart;
                Long v = hourMap == null ? null : hourMap.get(key);
                g.ms[i] = v == null ? 0L : v.longValue();
            } else if (grain == Grain.DAY) {
                long day = addDays(alignedFrom, i, zone);
                cellStart = day;
                cellEnd = nextDay(day, zone);
                key = Sessions.dayOf(cellStart, zone);
                inRange = cellStart >= fromDay && cellStart <= toDay;
                afterFirst = firstDayStart > 0L && cellEnd > firstDayStart;
                Long v = dayMap.get(key);
                g.ms[i] = v == null ? 0L : v.longValue();
            } else if (grain == Grain.WEEK) {
                long week = addDays(alignedFrom, i * 7, zone);
                cellStart = week;
                cellEnd = addDays(week, 7, zone);
                key = Sessions.dayOf(cellStart, zone);
                inRange = cellEnd > from && cellStart <= toDay;
                afterFirst = firstDayStart > 0L && cellEnd > firstDayStart;
                g.ms[i] = sumDays(dayMap, cellStart, cellEnd, zone);
            } else {
                long month = addMonths(alignedFrom, i, zone);
                cellStart = month;
                cellEnd = addMonths(month, 1, zone);
                key = Sessions.dayOf(cellStart, zone).substring(0, 7);
                inRange = cellEnd > from && cellStart <= toDay;
                afterFirst = firstDayStart > 0L && cellEnd > firstDayStart;
                g.ms[i] = sumDays(dayMap, cellStart, cellEnd, zone);
            }
            g.cellKey[i] = key;
            g.draw[i] = inRange && afterFirst;
        }
        return g;
    }

    // ---- 裁剪 + 聚合 ---------------------------------------------------------

    private static void addDayClip(Map<String, Long> m, Sessions.Entry e, long from, long to,
                                   TimeZone tz) {
        for (Sessions.Segment s : Sessions.split(e, tz, Calendar.DAY_OF_YEAR)) {
            long a = Math.max(s.from, from);
            long b = Math.min(s.to, to);
            if (b <= a) continue;
            long wall = s.to - s.from;
            long v = wall <= 0L ? s.ms : (long) ((double) s.ms * (b - a) / (double) wall);
            if (v > 0L) add(m, Sessions.dayOf(a, tz), v);
        }
    }

    private static void addHourClip(Map<String, Long> m, Sessions.Entry e, long from, long to,
                                    TimeZone tz) {
        for (Sessions.Segment s : Sessions.split(e, tz, Calendar.HOUR_OF_DAY)) {
            long a = Math.max(s.from, from);
            long b = Math.min(s.to, to);
            if (b <= a) continue;
            long wall = s.to - s.from;
            long v = wall <= 0L ? s.ms : (long) ((double) s.ms * (b - a) / (double) wall);
            if (v > 0L) {
                add(m, Sessions.dayOf(a, tz) + " " + hourKey(a, tz), v);
            }
        }
    }

    private static void add(Map<String, Long> m, String k, long v) {
        Long old = m.get(k);
        m.put(k, old == null ? v : old.longValue() + v);
    }

    /** [start, end) 内所有「天」桶之和。 */
    private static long sumDays(Map<String, Long> m, long start, long end, TimeZone tz) {
        long sum = 0L;
        long d = start;
        int guard = 0;
        while (d < end && guard++ < 400) {
            Long v = m.get(Sessions.dayOf(d, tz));
            if (v != null) sum += v.longValue();
            d = nextDay(d, tz);
        }
        return sum;
    }

    // ---- 日历辅助（全部按设备本地时区） --------------------------------------

    /** 一个格子的时长（毫秒）：时 1h / 日 1d / 周 7d / 月 = 起点所在月的实际长度。 */
    static long grainSpanMs(Grain g, long fromDay, TimeZone tz) {
        if (g == Grain.HOUR) return 3_600_000L;
        if (g == Grain.DAY) return 86_400_000L;
        if (g == Grain.WEEK) return 7L * 86_400_000L;
        long m = addMonths(fromDay, 0, tz);   // 该月 1 日 0 点
        return addMonths(m, 1, tz) - m;
    }

    static long dayStart(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long nextDay(long day, TimeZone tz) {
        return addDays(day, 1, tz);
    }

    static long addDays(long ms, int n, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.add(Calendar.DAY_OF_YEAR, n);
        return c.getTimeInMillis();
    }

    static long addMonths(long ms, int n, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, n);
        return c.getTimeInMillis();
    }

    /** 某一时刻所在周的周一 00:00。 */
    static long weekStart(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(dayStart(ms, tz));
        int dow = c.get(Calendar.DAY_OF_WEEK);   // SUNDAY=1 .. SATURDAY=7
        c.add(Calendar.DAY_OF_YEAR, -((dow + 5) % 7));
        return c.getTimeInMillis();
    }

    /** 某一时刻所在周的周日 23:59:59.999 的次日 0 点（= 下周一）。 */
    static long weekEnd(long day, TimeZone tz) {
        return addDays(weekStart(day, tz), 7, tz);
    }

    /** 某一时刻所在季度的首日（1/4/7/10 月 1 日）0 点。 */
    static long quarterStart(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(dayStart(ms, tz));
        int month = c.get(Calendar.MONTH);       // 0..11
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MONTH, (month / 3) * 3);
        return c.getTimeInMillis();
    }

    /** 某一时刻所在年的 1 月 1 日 0 点。 */
    static long yearStart(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(dayStart(ms, tz));
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MONTH, 0);
        return c.getTimeInMillis();
    }

    /** 两个本地日之间的天数差（含 DST：按日历日算）。 */
    static long daysBetween(long fromDay, long toDay, TimeZone tz) {
        long d = dayStart(fromDay, tz);
        long end = dayStart(toDay, tz);
        long n = 0L;
        int guard = 0;
        while (d < end && guard++ < 4000) {
            d = addDays(d, 1, tz);
            n++;
        }
        return n;
    }

    /** 从 startWeek（周一）到 day 所在周之间隔了几周。 */
    static long weeksBetween(long startWeek, long day, TimeZone tz) {
        long w = weekStart(day, tz);
        long n = 0L;
        long s = startWeek;
        int guard = 0;
        while (s < w && guard++ < 4000) {
            s = addDays(s, 7, tz);
            n++;
        }
        return n;
    }

    /** 从 startMonth（1 日）到 day 所在月之间隔了几个月。 */
    static long monthsBetween(long startMonth, long day, TimeZone tz) {
        Calendar a = Calendar.getInstance(tz);
        Calendar b = Calendar.getInstance(tz);
        a.setTimeInMillis(startMonth);
        b.setTimeInMillis(day);
        int n = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12
                + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH));
        return Math.max(0, n);
    }

    /** "yyyy-MM-dd" ⇒ 该日本地 0 点。 */
    static long dayStartMs(String date, TimeZone tz) {
        try {
            String[] p = date.split("-");
            Calendar c = Calendar.getInstance(tz);
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            return c.getTimeInMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String hourKey(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h < 10 ? "0" + h : String.valueOf(h);
    }
}
