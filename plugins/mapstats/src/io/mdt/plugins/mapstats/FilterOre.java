package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**筛选注入**：只看**采得到某一种矿**的图（矿石或矿墙任一段格数 &gt; 0）。
 *
 * <p>v33（第 282 轮）：先选矿（{@link #options} / {@link #choose}），再逐项过 {@link #accept}。
 * 判据按内容内部名匹配（`thorium` 命中 `ore-thorium` / `ore-wall-thorium`）⇒ 不依赖译名。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class FilterOre {

    private static StatsPick.OreOptions opts;
    private static String ore = "";

    private FilterOre() { }

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

    public static boolean accept(ItemInfo it, PluginApi api) {
        return StatsIndex.of(it, api).oreCount(ore) > 0;
    }
}
