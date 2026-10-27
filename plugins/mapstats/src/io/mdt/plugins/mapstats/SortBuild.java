package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**排序注入**：按能建格数（多 → 少）。
 *
 * <p>数与详情页「能放一般建筑 N 格」同一个函数（{@code Buildable.count()}）。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class SortBuild {

    private SortBuild() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static int compare(ItemInfo a, ItemInfo b, PluginApi api) {
        return StatsPick.compare(StatsPick.SORT_BUILD,
                StatsIndex.of(a, api), PickSupport.title(a),
                StatsIndex.of(b, api), PickSupport.title(b));
    }
}
