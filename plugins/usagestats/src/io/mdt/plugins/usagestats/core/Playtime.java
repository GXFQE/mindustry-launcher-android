package io.mdt.plugins.usagestats.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 前台时长**账本**（纯函数，PC 侧 `build.sh check` 直接测；不碰 Android）。
 *
 * <p>为什么是账本、不是"每局一个文件再求和"：主界面摘要卡要在一个 UI 线程里读到一个
 * 总数，逐局求和会随启动次数线性变慢；账本每行是「某槽里某版本已累计多少毫秒」，
 * 读一次就够，顺带能按槽 / 按版本切开。
 *
 * <p>格式（制表符分隔，插件私有；宿主**不解析**）：
 * <pre>slot \t version \t ms \t updated</pre>
 * <ul>
 *   <li>{@code slot} = 槽名（控制字符在写入时被换成空格）；</li>
 *   <li>{@code version} = 版本 key（已装 = 包名；导入 = {@code import:<文件名>}）；</li>
 *   <li>{@code ms} = 累计前台毫秒；</li>
 *   <li>{@code updated} = 最后一次写入的墙钟毫秒（只为诊断，界面不显示）。</li>
 * </ul>
 *
 * <p>坏行一律**跳过**（宁可少一行，也不能拿半条记录算总数）。
 */
public final class Playtime {

    /** 账本里的一行（不可变）。 */
    public static final class Entry {
        public final String slot, version;
        public final long ms, updated;

        public Entry(String slot, String version, long ms, long updated) {
            this.slot = slot == null ? "" : slot;
            this.version = version == null ? "" : version;
            this.ms = ms;
            this.updated = updated;
        }
    }

    private Playtime() {}

    /** 版本 key 缺失时的占位（界面上说「未知版本」）。 */
    public static final String UNKNOWN_VERSION = "?";

    /** 解析账本（坏行 / 负毫秒 / 空槽名都跳过）。 */
    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<Entry>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String s = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (s.trim().isEmpty()) continue;
            String[] p = s.split("\t");
            if (p.length < 4) continue;
            String slot = p[0].trim();
            String ver = p[1].trim();
            if (slot.isEmpty()) continue;
            long ms, updated;
            try {
                ms = Long.parseLong(p[2].trim());
                updated = Long.parseLong(p[3].trim());
            } catch (Throwable t) {
                continue;
            }
            if (ms <= 0) continue;
            out.add(new Entry(slot, ver.isEmpty() ? UNKNOWN_VERSION : ver, ms, Math.max(0, updated)));
        }
        return out;
    }

    /**
     * 把一行写进账本（**纯函数**：同一个 (槽, 版本) 覆盖毫秒数，其余行原样保留）。
     *
     * @param slot    空 ⇒ 原样返回（没有槽就没法归属，别写半条记录）
     * @param version 空 ⇒ {@link #UNKNOWN_VERSION}
     */
    public static String put(String text, String slot, String version, long ms, long updated) {
        String sl = clean(slot);
        if (sl.isEmpty() || ms <= 0) return text == null ? "" : text;
        String ver = clean(version);
        if (ver.isEmpty()) ver = UNKNOWN_VERSION;
        StringBuilder sb = new StringBuilder();
        boolean hit = false;
        for (Entry e : parse(text)) {
            if (e.slot.equals(sl) && e.version.equals(ver)) {
                hit = true;
                sb.append(line(sl, ver, ms, updated));
            } else {
                sb.append(line(e.slot, e.version, e.ms, e.updated));
            }
        }
        if (!hit) sb.append(line(sl, ver, ms, updated));
        return sb.toString();
    }

    /** 某个 (槽, 版本) 已累计的毫秒（没有 ⇒ 0）。 */
    public static long valueFor(String text, String slot, String version) {
        String sl = clean(slot);
        String ver = clean(version);
        if (ver.isEmpty()) ver = UNKNOWN_VERSION;
        for (Entry e : parse(text)) {
            if (e.slot.equals(sl) && e.version.equals(ver)) return e.ms;
        }
        return 0;
    }

    /** 账本里所有槽合计（毫秒）。 */
    public static long total(String text) {
        long sum = 0;
        for (Entry e : parse(text)) sum += e.ms;
        return sum;
    }

    /** 某个槽的合计（毫秒）。 */
    public static long totalForSlot(String text, String slot) {
        long sum = 0;
        String sl = clean(slot);
        for (Entry e : parse(text)) if (e.slot.equals(sl)) sum += e.ms;
        return sum;
    }

    /** 某个槽里的各行，**毫秒降序**（同值按版本 key 排，保证稳定）。 */
    public static List<Entry> forSlot(String text, String slot) {
        List<Entry> out = new ArrayList<Entry>();
        String sl = clean(slot);
        for (Entry e : parse(text)) if (e.slot.equals(sl)) out.add(e);
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.ms != b.ms) return a.ms > b.ms ? -1 : 1;
                return a.version.compareTo(b.version);
            }
        });
        return out;
    }

    /** 按槽分组（合计降序；同值按槽名），给"没有具体槽"的页面用。 */
    public static List<Entry> bySlot(String text) {
        java.util.LinkedHashMap<String, Long> sums = new java.util.LinkedHashMap<String, Long>();
        java.util.LinkedHashMap<String, Long> stamp = new java.util.LinkedHashMap<String, Long>();
        for (Entry e : parse(text)) {
            Long old = sums.get(e.slot);
            sums.put(e.slot, (old == null ? 0 : old.longValue()) + e.ms);
            Long t = stamp.get(e.slot);
            if (t == null || e.updated > t.longValue()) stamp.put(e.slot, e.updated);
        }
        List<Entry> out = new ArrayList<Entry>();
        for (String s : sums.keySet()) {
            Long t = stamp.get(s);
            out.add(new Entry(s, "", sums.get(s).longValue(), t == null ? 0 : t.longValue()));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.ms != b.ms) return a.ms > b.ms ? -1 : 1;
                return a.slot.compareTo(b.slot);
            }
        });
        return out;
    }

    /** ms ⇒ `{小时, 分}`（负值按 0）。 */
    public static int[] hm(long ms) {
        long sec = Math.max(0, ms) / 1000L;
        return new int[] { (int) (sec / 3600L), (int) ((sec % 3600L) / 60L) };
    }

    private static String line(String slot, String version, long ms, long updated) {
        return slot + "\t" + version + "\t" + ms + "\t" + updated + "\n";
    }

    /** 字段卫生：制表符 / 换行 / 控制字符换成空格（否则一个字段就能把账本写成两行）。 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sb.toString().trim();
    }
}
