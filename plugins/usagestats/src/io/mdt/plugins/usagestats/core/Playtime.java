package io.mdt.plugins.usagestats.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台时长**账本**（纯函数，PC 侧 `build.sh check` 直接测；不碰 Android）。
 *
 * <p>为什么是账本、不是"每局一个文件再求和"：主界面摘要卡要在一个 UI 线程里读到一个
 * 总数，逐局求和会随启动次数线性变慢；账本每行是「某槽里某版本已累计多少毫秒」，
 * 读一次就够，顺带能按槽 / 按版本切开。
 *
 * <p>格式（制表符分隔，插件私有；宿主**不解析**）：
 * <pre>slot \t key \t tag \t ms \t updated \t title \t rawName \t upstream</pre>
 * <ul>
 *   <li>{@code slot} = 槽名（控制字符在写入时被换成空格）；</li>
 *   <li>{@code key} = 版本 key（已装 = 包名；导入 = {@code import:<文件名>}）—— **身份**；</li>
 *   <li>{@code tag} = 人读版本号（{@code displayVersion}，如 159.7 / 2026.09.X37）——
 *       **也参与行身份**：已装游戏会原地更新（包名不变、版本号变），靠它把更新前后分成两行；</li>
 *   <li>{@code ms} = 累计前台毫秒；</li>
 *   <li>{@code updated} = 最后一次写入的墙钟毫秒（只为诊断，界面不显示）；</li>
 *   <li>{@code title} = 宿主当时的显示名快照（含自定义名 / 类型 / 版本号）；</li>
 *   <li>{@code rawName} = 原始 versionName（数据层保真，如 8-official-159.7）；</li>
 *   <li>{@code upstream} = 上游版本号（fork 才有，没有 = 空）。</li>
 * </ul>
 *
 * <p>**旧格式照读**（只读兼容，写回一律新格式）：
 * <ul>
 *   <li>5 列 {@code slot key ms updated label} ⇒ {@code tag = label}，其余快照字段为空；</li>
 *   <li>4 列 {@code slot key ms updated} ⇒ {@code tag} 为空。</li>
 * </ul>
 * {@link #put} 遇到「同槽同 key 且旧行 tag 为空」时视为同一行（老数据在下一次写入时迁移到新格式）。
 *
 * <p>坏行一律**跳过**（宁可少一行，也不能拿半条记录算总数）。
 */
public final class Playtime {

    /** 账本里的一行（不可变）。 */
    public static final class Entry {
        public final String slot, key, tag, title, rawName, upstream;
        public final long ms, updated;

        public Entry(String slot, String key, String tag, String title, String rawName,
                     String upstream, long ms, long updated) {
            this.slot = slot == null ? "" : slot;
            this.key = key == null ? "" : key;
            this.tag = tag == null ? "" : tag;
            this.title = title == null ? "" : title;
            this.rawName = rawName == null ? "" : rawName;
            this.upstream = upstream == null ? "" : upstream;
            this.ms = ms;
            this.updated = updated;
        }
    }

    /** 写入时带的版本快照（探针从 {@code ProbeEnv.versionInfo()} 取）。 */
    public static final class Snap {
        public final String tag, title, rawName, upstream;

        public Snap(String tag, String title, String rawName, String upstream) {
            this.tag = tag == null ? "" : tag;
            this.title = title == null ? "" : title;
            this.rawName = rawName == null ? "" : rawName;
            this.upstream = upstream == null ? "" : upstream;
        }

        /** 从宿主给的版本信息 map 取（缺键 / 空串都当空）。 */
        public static Snap of(Map<String, String> info) {
            if (info == null) return new Snap("", "", "", "");
            return new Snap(tagOf(info), info.get("title"), info.get("versionName"),
                    info.get("upstreamVersion"));
        }

        /** 行身份里的版本号：displayVersion → versionName → 空（`?` 不算号）。 */
        static String tagOf(Map<String, String> info) {
            String t = clean(info.get("displayVersion"));
            if (t.isEmpty() || UNKNOWN_VERSION.equals(t)) t = clean(info.get("versionName"));
            return UNKNOWN_VERSION.equals(t) ? "" : t;
        }
    }

    /** 版本 key 缺失时的占位（界面上说「未知版本」）。 */
    public static final String UNKNOWN_VERSION = "?";

    private Playtime() {}

    /** 解析账本（坏行 / 负毫秒 / 空槽名 / 空 key 都跳过；新旧格式都认）。 */
    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<Entry>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String s = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (s.trim().isEmpty()) continue;
            // ★ -1：保留尾部空字段（新格式固定 8 列，空 title/upstream 也占位）
            String[] p = s.split("\t", -1);
            String slot, key, tag, title = "", raw = "", up = "";
            int mi, ui;
            if (p.length >= 8) {
                slot = p[0].trim();
                key = p[1].trim();
                tag = p[2].trim();
                mi = 3;
                ui = 4;
                title = p[5].trim();
                raw = p[6].trim();
                up = p[7].trim();
            } else if (p.length >= 5) {          // 旧 5 列：slot key ms updated label
                slot = p[0].trim();
                key = p[1].trim();
                tag = p[4].trim();
                mi = 2;
                ui = 3;
            } else if (p.length >= 4) {          // 旧 4 列
                slot = p[0].trim();
                key = p[1].trim();
                tag = "";
                mi = 2;
                ui = 3;
            } else {
                continue;
            }
            if (slot.isEmpty() || key.isEmpty()) continue;
            long ms, updated;
            try {
                ms = Long.parseLong(p[mi].trim());
                updated = Long.parseLong(p[ui].trim());
            } catch (Throwable t) {
                continue;
            }
            if (ms <= 0) continue;
            out.add(new Entry(slot, key, tag, title, raw, up, ms, Math.max(0, updated)));
        }
        return out;
    }

    /**
     * 把一行写进账本（**纯函数**：同一个 (槽, key, 版本号) 覆盖毫秒数，其余行原样保留）。
     *
     * @param slot    空 ⇒ 原样返回（没有槽就没法归属，别写半条记录）
     * @param key     空 ⇒ 原样返回（身份都没有就别写）
     * @param snap    版本快照；各字段为空 ⇒ **保留这一行原来的值**（宿主这次没给，不代表以前没有）
     */
    public static String put(String text, String slot, String key, Snap snap, long ms,
                             long updated) {
        String sl = clean(slot);
        String k = clean(key);
        if (sl.isEmpty() || k.isEmpty() || ms <= 0) return text == null ? "" : text;
        String tag = clean(snap == null ? "" : snap.tag);
        String title = clean(snap == null ? "" : snap.title);
        String raw = clean(snap == null ? "" : snap.rawName);
        String up = clean(snap == null ? "" : snap.upstream);
        StringBuilder sb = new StringBuilder();
        boolean hit = false;
        for (Entry e : parse(text)) {
            boolean same;
            if (tag.isEmpty()) {
                // 宿主这次没给版本号：只认「第一行」（与 valueFor 的选行规则一致）——
                // 多版本行必须各自保留，不能因为新行没 tag 就把它们合并掉。
                same = !hit && e.slot.equals(sl) && e.key.equals(k);
            } else {
                // 同一行 = 同槽同 key，且版本号相同 / 旧行没有版本号（允许迁移）
                same = e.slot.equals(sl) && e.key.equals(k)
                        && (e.tag.equals(tag) || e.tag.isEmpty());
            }
            if (same) {
                if (hit) continue;               // 重复行（手改过的文件）合并成一行
                hit = true;
                sb.append(line(sl, k,
                        tag.isEmpty() ? e.tag : tag,
                        ms, updated,
                        title.isEmpty() ? e.title : title,
                        raw.isEmpty() ? e.rawName : raw,
                        up.isEmpty() ? e.upstream : up));
            } else {
                sb.append(line(e.slot, e.key, e.tag, e.ms, e.updated,
                        e.title, e.rawName, e.upstream));
            }
        }
        if (!hit) sb.append(line(sl, k, tag, ms, updated, title, raw, up));
        return sb.toString();
    }

    /** 某个 (槽, key, 版本号) 已累计的毫秒（旧行 tag 空也算命中；没有 ⇒ 0）。 */
    public static long valueFor(String text, String slot, String key, String tag) {
        String sl = clean(slot);
        String k = clean(key);
        String t = clean(tag);
        for (Entry e : parse(text)) {
            if (!e.slot.equals(sl) || !e.key.equals(k)) continue;
            if (e.tag.equals(t) || e.tag.isEmpty() || t.isEmpty()) return e.ms;
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

    /** 某个槽里的各行，**毫秒降序**（同值按 key + 版本号排，保证稳定）。 */
    public static List<Entry> forSlot(String text, String slot) {
        List<Entry> out = new ArrayList<Entry>();
        String sl = clean(slot);
        for (Entry e : parse(text)) if (e.slot.equals(sl)) out.add(e);
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.ms != b.ms) return a.ms > b.ms ? -1 : 1;
                int c = a.key.compareTo(b.key);
                return c != 0 ? c : a.tag.compareTo(b.tag);
            }
        });
        return out;
    }

    /** 按槽分组（合计降序；同值按槽名），给"没有具体槽"的页面用。 */
    public static List<Entry> bySlot(String text) {
        Map<String, Long> sums = new LinkedHashMap<String, Long>();
        Map<String, Long> stamp = new LinkedHashMap<String, Long>();
        for (Entry e : parse(text)) {
            Long old = sums.get(e.slot);
            sums.put(e.slot, (old == null ? 0 : old.longValue()) + e.ms);
            Long t = stamp.get(e.slot);
            if (t == null || e.updated > t.longValue()) stamp.put(e.slot, e.updated);
        }
        List<Entry> out = new ArrayList<Entry>();
        for (String s : sums.keySet()) {
            Long t = stamp.get(s);
            out.add(new Entry(s, "", "", "", "", "", sums.get(s).longValue(),
                    t == null ? 0 : t.longValue()));
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

    private static String line(String slot, String key, String tag, long ms, long updated,
                               String title, String rawName, String upstream) {
        return slot + "\t" + key + "\t" + tag + "\t" + ms + "\t" + updated + "\t"
                + title + "\t" + rawName + "\t" + upstream + "\n";
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
