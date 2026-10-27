package io.mdt.plugins.mapstats.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 281~282 轮：**按统计结果排序 / 筛选**的纯判据（PC 可测；走宿主 v25（v33 起可先问用户）的注入）。
 *
 * <p>插件只回答"哪张在前 / 要不要这一张"，真正的取数与进度由宿主的 `PluginPicks` 管。</p>
 *
 * <h3>三个数都直接来自 {@link MapStats.Result}，不另算</h3>
 * <ul>
 *   <li>{@link #reachTotal}：**可采资源总数** = 矿石 + 矿墙 + 可采地板三段的"拿得到"格数之和。
 *       加成地板（水 / 热 / 孢子…）**不算**：那类是"能上泵抽"或"能放机器"，不是采到手里的资源；
 *       而且沙地一类同一格既是可采地板又是加成地板 ⇒ 两段都加会重复计数。</li>
 *   <li>{@code build} = {@link MapStats.Buildable#count()}（能放一般建筑的格数，与详情页同一个函数）。</li>
 *   <li>{@code zone} = 核心区地板的格数（{@code Core.skip} ⇒ 0）。</li>
 *   <li>{@code ores} = **每种矿各自**采得到的格数（`内部名:格数;…`）—— 第 282 轮加：用户要
 *       "先选某矿，再按它排 / 筛"，所以不能只存"有没有矿"。</li>
 * </ul>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class StatsPick {

    /** 排序：可采资源（多 → 少） */
    public static final int SORT_REACH = 1;
    /** 排序：能建格数（多 → 少） */
    public static final int SORT_BUILD = 2;

    private StatsPick() { }

    /**
     * 一张图在排序 / 筛选里要用的那几个数。
     * **读不出来**（老格式 / 坏文件）⇒ {@code ok=false}：排序时垫底、筛选时一律不通过。
     */
    public static final class Snap {
        public boolean ok;
        public int reach;
        public int build;
        public int zone;
        /** `ore-thorium:413;ore-wall-thorium:74;…`（只记 &gt; 0 的；见 {@link #oreCount}） */
        public String ores = "";

        private Map<String, Integer> parsed;

        /**
         * 这种矿**采得到**多少格（内部名短名匹配：`thorium` 命中 `ore-thorium` /
         * `ore-wall-thorium`，模组前缀同理）。读不出来 / 没有 ⇒ 0。
         */
        public int oreCount(String ore) {
            if (!ok || ore == null || ore.isEmpty()) return 0;
            Map<String, Integer> m = parsed;
            if (m == null) {
                m = new LinkedHashMap<String, Integer>();
                if (ores != null && !ores.isEmpty()) {
                    for (String pair : ores.split(";")) {
                        int i = pair.lastIndexOf(':');
                        if (i <= 0) continue;
                        try {
                            m.put(pair.substring(0, i),
                                    Integer.valueOf(Integer.parseInt(pair.substring(i + 1))));
                        } catch (Throwable ignored) {
                            // 一行坏了不影响别的
                        }
                    }
                }
                parsed = m;
            }
            int n = 0;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (matches(e.getKey(), ore)) n += e.getValue().intValue();
            }
            return n;
        }
    }

    /** 从统计结果抽快照（结果本身不缓存 —— 太重；只留这几行数）。 */
    public static Snap snap(MapStats.Result r) {
        Snap s = new Snap();
        if (r == null || !r.ok) return s;
        s.ok = true;
        s.reach = reachTotal(r);
        s.build = r.buildable == null ? 0 : r.buildable.count();
        s.zone = r.core == null || r.core.skip ? 0 : r.core.zone;
        s.ores = oreCounts(r);
        return s;
    }

    /** 可采资源总数（矿石 + 矿墙 + 可采地板；**不含**加成地板，理由见类注释）。 */
    public static int reachTotal(MapStats.Result r) {
        if (r == null) return 0;
        return sum(r.ores) + sum(r.oreWalls) + sum(r.floors);
    }

    private static int sum(List<MapStats.Row> rows) {
        int n = 0;
        if (rows == null) return 0;
        for (MapStats.Row row : rows) {
            if (row != null) n += row.reachable();
        }
        return n;
    }

    /** 内部名命中：`thorium` 命中 `thorium` / `ore-thorium` / `ore-wall-thorium`（模组前缀同理）。 */
    private static boolean matches(String internal, String ore) {
        if (internal == null || ore == null || ore.isEmpty()) return false;
        return internal.equals(ore) || internal.endsWith("-" + ore);
    }

    /** 有核心区（`Core.skip` = 这条统计不适用 ⇒ 不算"有"）。 */
    public static boolean hasCore(MapStats.Result r) {
        return r != null && r.ok && r.core != null && !r.core.skip && r.core.zone > 0;
    }

    /** 每种矿各自的可采格数：`内部名:格数;…`（行序 = 统计表顺序；同名的累加；0 格不写）。 */
    public static String oreCounts(MapStats.Result r) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        addOres(m, r == null ? null : r.ores);
        addOres(m, r == null ? null : r.oreWalls);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue().intValue() <= 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private static void addOres(Map<String, Integer> m, List<MapStats.Row> rows) {
        if (rows == null) return;
        for (MapStats.Row row : rows) {
            if (row == null || row.reachable() <= 0) continue;
            for (String internal : row.internals) {
                if (internal == null || internal.isEmpty()) continue;
                Integer old = m.get(internal);
                m.put(internal, Integer.valueOf((old == null ? 0 : old.intValue()) + row.reachable()));
            }
        }
    }

    /**
     * 排序：**读不出来的垫底**；同一档先比选定的那个数（降序），再比另一个数（降序），
     * 最后按标题升序兜底（同数的一批顺序稳定，不然"排序没生效"看着像 bug）。
     */
    public static int compare(int mode, Snap a, String titleA, Snap b, String titleB) {
        Snap x = a == null ? new Snap() : a;
        Snap y = b == null ? new Snap() : b;
        if (x.ok != y.ok) return x.ok ? -1 : 1;
        int c;
        if (mode == SORT_BUILD) {
            c = Integer.compare(y.build, x.build);
            if (c == 0) c = Integer.compare(y.reach, x.reach);
        } else {
            c = Integer.compare(y.reach, x.reach);
            if (c == 0) c = Integer.compare(y.build, x.build);
        }
        if (c != 0) return c;
        String ta = titleA == null ? "" : titleA;
        String tb = titleB == null ? "" : titleB;
        return ta.compareToIgnoreCase(tb);
    }

    /** 按**某一种矿**的可采格数排序（多 → 少；同数按标题兜底；读不出来的垫底）。 */
    public static int compareOre(Snap a, String titleA, Snap b, String titleB, String ore) {
        Snap x = a == null ? new Snap() : a;
        Snap y = b == null ? new Snap() : b;
        if (x.ok != y.ok) return x.ok ? -1 : 1;
        int c = Integer.compare(y.oreCount(ore), x.oreCount(ore));
        if (c != 0) return c;
        String ta = titleA == null ? "" : titleA;
        String tb = titleB == null ? "" : titleB;
        return ta.compareToIgnoreCase(tb);
    }

    // ---- 选项列表（第 282 轮）-------------------------------------------------------

    /** 一组"矿"的选项：`labels[i]` 给人看，`ids[i]` 是判据用的短名（如 `thorium`）。 */
    public static final class OreOptions {
        public final String[] labels;
        public final String[] ids;

        OreOptions(String[] labels, String[] ids) {
            this.labels = labels;
            this.ids = ids;
        }

        public int size() {
            return labels.length;
        }
    }

    /**
     * 从**有效内容表**里列"能选的矿"：只要 `isOre` 的行（地矿；墙矿是同一件物品的另一种形态，
     * 按物品合并）。名字走译名表（矿没有自己的键 ⇒ 用物品名，与统计那四处同一个口径）。
     * 模组加的矿会自然出现 —— 这正是"从表里推"而不是写死清单的理由。
     */
    public static OreOptions oreOptions(MapStats.Table table, MapStats.Names names) {
        Map<String, String> byId = new LinkedHashMap<String, String>();
        if (table != null) {
            for (MapStats.Def d : table.byName.values()) {
                if (d == null || !d.isOre) continue;
                String drop = d.itemDrop == null ? "" : d.itemDrop.trim();
                String id = drop.isEmpty() ? d.name : drop;
                if (id == null || id.isEmpty()) continue;
                if (byId.containsKey(id)) continue;
                String label;
                if (!drop.isEmpty()) label = names == null ? drop : names.item(drop);
                else label = names == null ? d.name : names.block(d.name);
                if (label == null || label.trim().isEmpty()) label = id;
                byId.put(id, label.trim());
            }
        }
        List<Map.Entry<String, String>> all =
                new ArrayList<Map.Entry<String, String>>(byId.entrySet());
        java.util.Collections.sort(all, new java.util.Comparator<Map.Entry<String, String>>() {
            @Override public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                int c = a.getValue().compareToIgnoreCase(b.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        int n = Math.min(all.size(), 24);
        String[] labels = new String[n];
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = all.get(i).getValue();
            ids[i] = all.get(i).getKey();
        }
        return new OreOptions(labels, ids);
    }
}
