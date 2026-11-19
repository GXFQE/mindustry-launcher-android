package io.mdt.plugins.usagestats.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 每局游玩**流水**（第 396 轮）：一行一局，带**具体时刻**。
 *
 * <p>为什么单独一份、而不是塞进 {@link Playtime} 的账本：账本是「某槽某版本累计了多少」，
 * 一行只有一个墙钟（最后一次写入）—— 按天 / 按时段分析必须从「每局的起止时刻」重算，
 * 账本里没有这个信息，事后也无法重建。
 *
 * <p>格式（制表符分隔，插件私有；宿主**不解析**）：
 * <pre>started \t ended \t activeMs \t wallMs \t exit \t slot \t key \t tag \t patch</pre>
 * <ul>
 *   <li>{@code started} / {@code ended} = 这一局的**墙钟**毫秒（宿主 {@code SessionClock} 给的；
 *       ended 最多早 20 秒）；</li>
 *   <li>{@code activeMs} = 游戏 activity 在**前台**的累计（单调时钟，切到别的 App 不算）；</li>
 *   <li>{@code wallMs} = ended - started（冗余存一份，解析时**以 ended - started 为准**）；</li>
 *   <li>{@code exit} = 这一局怎么结束的（clean / crash / unclean / launch-failed；拿不准为空）；</li>
 *   <li>{@code slot} / {@code key} / {@code tag} / {@code patch} = 这一局的身份与版本快照。</li>
 * </ul>
 *
 * <p>**按天 / 按小时切分**：一局可能跨零点（甚至跨好几天），前台毫秒按**墙钟区间**比例摊到
 * 各段，最后一段补余数 ⇒ 各段之和恒等于 {@code activeMs}（自检钉住）。日期 / 小时一律按
 * **设备本地时区**切（时区作为参数传入，PC 自检用固定时区）。
 *
 * <p>坏行一律**跳过**（宁可少一段，也不能拿半条记录算分布）。
 */
public final class Sessions {

    /**
     * 最多切多少段（防坏数据把循环拖死）：按小时切，366 天 = 8784 段；再留一点余量。
     */
    private static final int MAX_SEGMENTS = 24 * 366 + 1;

    /** 流水里的一行（不可变）。 */
    public static final class Entry {
        public final long started, ended, activeMs, wallMs;
        public final String exit, slot, key, tag, patch;

        public Entry(long started, long ended, long activeMs, long wallMs,
                     String exit, String slot, String key, String tag, String patch) {
            this.started = started;
            this.ended = ended <= started ? started : ended;   // 改系统时间 ⇒ 按同一刻算
            this.activeMs = activeMs;
            this.wallMs = Math.max(0L, this.ended - this.started);
            this.exit = nz(exit);
            this.slot = nz(slot);
            this.key = nz(key);
            this.tag = nz(tag);
            this.patch = nz(patch);
        }
    }

    /** 切出来的一段（墙钟区间 + 分摊到的前台毫秒）。 */
    public static final class Segment {
        public final long from, to, ms;

        public Segment(long from, long to, long ms) {
            this.from = from;
            this.to = to;
            this.ms = ms;
        }
    }

    /** 一天：日期（yyyy-MM-dd）+ 累计前台毫秒 + 会话数（按**开始时刻**归日，跨天不重复计）。 */
    public static final class Day {
        public final String date;
        public final long ms;
        public final int sessions;

        public Day(String date, long ms, int sessions) {
            this.date = date == null ? "" : date;
            this.ms = ms;
            this.sessions = sessions;
        }
    }

    /** 单日的一个小时桶（给「单日数据分析」用）。 */
    public static final class Hour {
        public final String day;
        public final int hour;
        public final long ms;

        public Hour(String day, int hour, long ms) {
            this.day = day == null ? "" : day;
            this.hour = hour;
            this.ms = ms;
        }
    }

    private Sessions() {}

    /** 一行流水的落盘形式（字段卫生：控制字符换空格，别让一局写成两行）。 */
    public static String format(Entry e) {
        if (e == null) return "";
        return e.started + "\t" + e.ended + "\t" + e.activeMs + "\t" + e.wallMs + "\t"
                + clean(e.exit) + "\t" + clean(e.slot) + "\t" + clean(e.key) + "\t"
                + clean(e.tag) + "\t" + clean(e.patch) + "\n";
    }

    /** 解析流水（坏行跳过；ended < started 按同一刻；只认前 9 列，后面留给将来加列）。 */
    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<Entry>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String s = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (s.trim().isEmpty()) continue;
            String[] p = s.split("\t", -1);
            if (p.length < 9) continue;
            long st, en, active, wall;
            try {
                st = Long.parseLong(p[0].trim());
                en = Long.parseLong(p[1].trim());
                active = Long.parseLong(p[2].trim());
                wall = Long.parseLong(p[3].trim());
            } catch (Throwable t) {
                continue;
            }
            if (st <= 0L || active <= 0L) continue;      // 没有开始时刻 / 没量到前台时间
            String slot = p[5].trim();
            String key = p[6].trim();
            if (slot.isEmpty() || key.isEmpty()) continue;
            out.add(new Entry(st, en, active, wall, p[4].trim(), slot, key,
                    p[7].trim(), p[8].trim()));
        }
        return out;
    }

    /**
     * 是不是**同一局**（去重判据，防宿主重复派发把一局记两次）。
     *
     * <p>只看核心字段（started / ended / activeMs / key）：`exit` 在两次派发里可能一次有、
     * 一次没有；`slot` / `tag` 是同源快照，不必参与。
     */
    public static boolean sameRun(Entry a, Entry b) {
        return a != null && b != null
                && a.started == b.started && a.ended == b.ended
                && a.activeMs == b.activeMs && a.key.equals(b.key);
    }

    /** 所有局的前台毫秒之和。 */
    public static long total(List<Entry> es) {
        long sum = 0L;
        if (es != null) for (Entry e : es) if (e != null) sum += e.activeMs;
        return sum;
    }

    /**
     * 把一局切成若干段（按本地**天**或**小时**）。
     *
     * @param field {@link Calendar#DAY_OF_YEAR} 或 {@link Calendar#HOUR_OF_DAY}
     */
    public static List<Segment> split(Entry e, TimeZone tz, int field) {
        List<Segment> out = new ArrayList<Segment>();
        if (e == null || e.activeMs <= 0L || e.started <= 0L) return out;
        if (e.ended <= e.started || e.wallMs <= 0L) {
            // 零时长 / 改过系统时间：整笔落在开始那一刻，别再猜
            out.add(new Segment(e.started, e.started, e.activeMs));
            return out;
        }
        Calendar c = Calendar.getInstance(zone(tz));
        c.setTimeInMillis(e.started);
        truncate(c, field);
        long segStart = c.getTimeInMillis();
        long allocated = 0L;
        int guard = 0;
        while (segStart < e.ended && guard++ < MAX_SEGMENTS) {
            c.add(field, 1);
            long segEnd = c.getTimeInMillis();
            long from = Math.max(e.started, segStart);
            long to = Math.min(e.ended, segEnd);
            if (to > from) {
                // 按墙钟占比摊前台毫秒；用 double 防「一局挂很多天」时 long 乘积溢出
                long ms = (long) ((double) e.activeMs * (double) (to - from) / (double) e.wallMs);
                if (ms < 0L) ms = 0L;
                allocated += ms;
                out.add(new Segment(from, to, ms));
            }
            segStart = segEnd;
        }
        long rest = e.activeMs - allocated;
        if (rest != 0L) {
            if (out.isEmpty()) {
                out.add(new Segment(e.started, e.ended, rest));
            } else {
                Segment last = out.remove(out.size() - 1);
                out.add(new Segment(last.from, last.to, last.ms + rest));
            }
        }
        return out;
    }

    /** 按天聚合（解析 + 聚合；给界面 / 自检一个入口）。 */
    public static List<Day> byDay(String text, TimeZone tz) {
        return byDay(parse(text), tz);
    }

    /** 按天聚合（日期升序；会话数按开始时刻归日）。 */
    public static List<Day> byDay(List<Entry> es, TimeZone tz) {
        Map<String, long[]> acc = new LinkedHashMap<String, long[]>();   // date -> {ms, sessions}
        if (es != null) {
            for (Entry e : es) {
                if (e == null) continue;
                for (Segment s : split(e, tz, Calendar.DAY_OF_YEAR)) {
                    bump(acc, dayOf(s.from, tz), s.ms, 0);
                }
            }
            for (Entry e : es) {
                if (e == null) continue;
                bump(acc, dayOf(e.started, tz), 0L, 1);
            }
        }
        List<Day> out = new ArrayList<Day>();
        for (Map.Entry<String, long[]> en : acc.entrySet()) {
            out.add(new Day(en.getKey(), en.getValue()[0], (int) en.getValue()[1]));
        }
        Collections.sort(out, new Comparator<Day>() {
            @Override public int compare(Day a, Day b) { return a.date.compareTo(b.date); }
        });
        return out;
    }

    /** 单日小时分布：按本地小时切（给「单日数据分析」用；日期 + 小时升序）。 */
    public static List<Hour> byHour(List<Entry> es, TimeZone tz) {
        Map<String, long[]> acc = new LinkedHashMap<String, long[]>();   // "day hh" -> {ms}
        if (es != null) {
            for (Entry e : es) {
                if (e == null) continue;
                for (Segment s : split(e, tz, Calendar.HOUR_OF_DAY)) {
                    bump(acc, dayOf(s.from, tz) + " " + hourKey(s.from, tz), s.ms, 0);
                }
            }
        }
        List<Hour> out = new ArrayList<Hour>();
        for (Map.Entry<String, long[]> en : acc.entrySet()) {
            String k = en.getKey();
            int sp = k.indexOf(' ');
            String day = sp < 0 ? k : k.substring(0, sp);
            int hour = sp < 0 ? 0 : Integer.parseInt(k.substring(sp + 1));
            out.add(new Hour(day, hour, en.getValue()[0]));
        }
        Collections.sort(out, new Comparator<Hour>() {
            @Override public int compare(Hour a, Hour b) {
                int c = a.day.compareTo(b.day);
                return c != 0 ? c : (a.hour < b.hour ? -1 : (a.hour == b.hour ? 0 : 1));
            }
        });
        return out;
    }

    /** 墙钟毫秒 ⇒ 本地日期（yyyy-MM-dd，ASCII 数字，排序即日期序）。 */
    public static String dayOf(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(zone(tz));
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String hourKey(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(zone(tz));
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h < 10 ? "0" + h : String.valueOf(h);
    }

    private static void bump(Map<String, long[]> acc, String key, long ms, int sessions) {
        long[] a = acc.get(key);
        if (a == null) {
            a = new long[2];
            acc.put(key, a);
        }
        a[0] += ms;
        a[1] += sessions;
    }

    private static TimeZone zone(TimeZone tz) {
        return tz == null ? TimeZone.getDefault() : tz;
    }

    /** 把 calendar 清到「单位」起点（天 ⇒ 0 点；小时 ⇒ 0 分 0 秒 0 毫秒）。 */
    private static void truncate(Calendar c, int field) {
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (field == Calendar.DAY_OF_YEAR) c.set(Calendar.HOUR_OF_DAY, 0);
    }

    /** 字段卫生：制表符 / 换行 / 控制字符换成空格（否则一个字段就能把一局写成两行）。 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sb.toString().trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
