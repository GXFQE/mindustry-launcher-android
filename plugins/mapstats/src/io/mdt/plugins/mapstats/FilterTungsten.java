package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**筛选注入**：只看**采得到钨**的图（埃里克尔的顶级矿；塞普罗的原版图自然全被筛掉）。
 *
 * <p>判据同 {@link FilterThorium}（按内部名匹配，不依赖译名）。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class FilterTungsten {

    private FilterTungsten() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static boolean accept(ItemInfo it, PluginApi api) {
        return StatsPick.oreIn(StatsIndex.of(it, api).ores, "tungsten");
    }
}
