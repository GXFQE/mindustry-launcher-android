package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**排序注入**：按可采资源（多 → 少）。
 *
 * <p>宿主契约（v25）：声明 `slot: "sort"` 的入口类提供
 * {@code public static int compare(ItemInfo, ItemInfo, PluginApi)}，返回 &lt;0 表示前者靠前。
 * 宿主还会在这个类上取 {@code api()} 判接口版本 ⇒ 必须报（与 {@link MapStatsPage#api()} 同源）。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class SortReach {

    private SortReach() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static int compare(ItemInfo a, ItemInfo b, PluginApi api) {
        return StatsPick.compare(StatsPick.SORT_REACH,
                StatsIndex.of(a, api), PickSupport.title(a),
                StatsIndex.of(b, api), PickSupport.title(b));
    }
}
