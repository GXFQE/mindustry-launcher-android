package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;

/**
 * 地图页的**筛选注入**：只看有核心区（`core-zone` 地板）的图 —— 也就是"能落地核心的图"。
 *
 * <p>宿主契约（v25）：声明 `slot: "filter"` 的入口类提供
 * {@code public static boolean accept(ItemInfo, PluginApi)}。读不出来的图一律不通过
 * （"数不出来"不能算"有"）。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class FilterCore {

    private FilterCore() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static boolean accept(ItemInfo it, PluginApi api) {
        return StatsIndex.of(it, api).zone > 0;
    }
}
