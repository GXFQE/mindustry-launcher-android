package io.mdt.plugins.mapstats.core;

import java.util.List;

/**
 * 第 281 轮：**按统计结果排序 / 筛选**的纯判据（PC 可测；走宿主 v25 的 filter / sort 注入）。
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
        /** `,ore-thorium,ore-wall-tungsten,`（前后带逗号；判"含某矿"用 {@link #oreIn}） */
        public String ores = ",";
    }

    /** 从统计结果抽快照（结果本身不缓存 —— 太重；只留这几行数）。 */
    public static Snap snap(MapStats.Result r) {
        Snap s = new Snap();
        if (r == null || !r.ok) return s;
        s.ok = true;
        s.reach = reachTotal(r);
        s.build = r.buildable == null ? 0 : r.buildable.count();
        s.zone = r.core == null || r.core.skip ? 0 : r.core.zone;
        s.ores = oreList(r);
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

    /** 这张图**采得到**某矿吗（按内容内部名；矿石 + 矿墙两段；格数 0 的行不算）。 */
    public static boolean hasOre(MapStats.Result r, String ore) {
        if (r == null || ore == null || ore.isEmpty()) return false;
        return oreInRows(r.ores, ore) || oreInRows(r.oreWalls, ore);
    }

    private static boolean oreInRows(List<MapStats.Row> rows, String ore) {
        if (rows == null) return false;
        for (MapStats.Row row : rows) {
            if (row == null || row.reachable() <= 0) continue;
            for (String internal : row.internals) {
                if (matches(internal, ore)) return true;
            }
            if (row.drop != null && row.drop.equals(ore)) return true;
        }
        return false;
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

    /** 采得到的矿（矿石 + 矿墙）内部名：`,name,name,`（行序 = 统计表顺序，稳定；去重）。 */
    public static String oreList(MapStats.Result r) {
        StringBuilder sb = new StringBuilder(",");
        addOres(sb, r == null ? null : r.ores);
        addOres(sb, r == null ? null : r.oreWalls);
        return sb.toString();
    }

    private static void addOres(StringBuilder sb, List<MapStats.Row> rows) {
        if (rows == null) return;
        for (MapStats.Row row : rows) {
            if (row == null || row.reachable() <= 0) continue;
            for (String internal : row.internals) {
                if (internal == null || internal.isEmpty()) continue;
                if (sb.indexOf("," + internal + ",") >= 0) continue;
                sb.append(internal).append(',');
            }
        }
    }

    /** `oreList` 里有没有某矿（缓存里存的是内部名 ⇒ 同样走 {@link #matches} 的"短名命中"。 */
    public static boolean oreIn(String oreList, String ore) {
        if (oreList == null || ore == null || ore.isEmpty()) return false;
        for (String one : oreList.split(",")) {
            if (matches(one, ore)) return true;
        }
        return false;
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
}
