package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**排序注入**：按**可建核心数**（多 → 少）。
 *
 * <p>这个数 = 核心区里最多能摆几个**该星球最小的那种核心** —— 塞普罗 3×3（`core-shard`）、
 * 埃里克尔 4×4（`core-bastion`），也就是详情页那句「核心区 32 格 · 最多能摆 2 个核心（4×4）」
 * 里的 **2**（同一份 {@code MapStats.Core#max}，不是另算的）。</p>
 *
 * <p>没有核心区 / 这条统计不适用（模组星球读不到核心）⇒ 0 ⇒ 自然排在最后。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class SortCores {

    private SortCores() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static int compare(ItemInfo a, ItemInfo b, PluginApi api) {
        return StatsPick.compare(StatsPick.SORT_CORES, StatsIndex.of(a, api), PickSupport.title(a),
                StatsIndex.of(b, api), PickSupport.title(b));
    }
}
