package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**排序注入**：按**某一种矿**的可采格数（多 → 少）。
 *
 * <p>v33（第 282 轮）：先走宿主那张"选矿"卡 —— {@link #options(PluginApi)} 给标签，
 * 用户点完宿主在**同一份已加载实例**上调用 {@link #choose(int, PluginApi)}，本类把短名记进静态字段，
 * 之后 {@link #compare} 用它。取消 ⇒ 宿主一次都不调 compare。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class SortOre {

    private static StatsPick.OreOptions opts;
    private static String ore = "";

    private SortOre() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static String[] options(PluginApi api) {
        opts = StatsIndex.oreOptions(api);
        ore = "";
        return opts == null ? new String[0] : opts.labels;
    }

    public static void choose(int index, PluginApi api) {
        String[] ids = opts == null ? null : opts.ids;
        ore = (ids == null || index < 0 || index >= ids.length || ids[index] == null)
                ? "" : ids[index];
    }

    public static int compare(ItemInfo a, ItemInfo b, PluginApi api) {
        return StatsPick.compareOre(StatsIndex.of(a, api), PickSupport.title(a),
                StatsIndex.of(b, api), PickSupport.title(b), ore);
    }
}
