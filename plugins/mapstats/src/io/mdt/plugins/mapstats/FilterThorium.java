package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 地图页的**筛选注入**：只看**采得到钍**的图（矿石或矿墙任一段格数 &gt; 0）。
 *
 * <p>判据按内容内部名匹配（`thorium` 命中 `ore-thorium` / `ore-wall-thorium`，
 * 模组前缀同理）⇒ 不依赖译名，换语言不影响结论。</p>
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
public final class FilterThorium {

    private FilterThorium() { }

    public static int api() {
        return MapStatsPage.api();
    }

    public static boolean accept(ItemInfo it, PluginApi api) {
        return StatsPick.oreIn(StatsIndex.of(it, api).ores, "thorium");
    }
}
