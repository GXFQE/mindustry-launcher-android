package io.mdt.plugins.mapstats;

import io.mdt.launcher.api.ItemInfo;

/** 排序 / 筛选入口共用的小工具（**不**放 `api()`：宿主要在被加载的那个类上取到它）。 */
final class PickSupport {

    private PickSupport() { }

    static String title(ItemInfo it) {
        return it == null || it.title == null ? "" : it.title;
    }
}
