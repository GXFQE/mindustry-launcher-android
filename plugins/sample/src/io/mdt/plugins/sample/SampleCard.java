package io.mdt.plugins.sample;

import io.mdt.launcher.api.PluginApi;

/**
 * v40 示例：**宿主卡片 + 跳页**（`place: "actions"` 插进地图页操作卡）。
 *
 * <p>PLUGIN-API.md「插入能力（v40）」的配套例子：用宿主的卡片弹信息（别自己 new AlertDialog），
 * 按钮里用 `openPage` 跳宿主页面。
 */
public final class SampleCard {

    public static int api() {
        return 40;
    }

    public static void run(final PluginApi api) {
        api.showCard("宿主卡片样例",
                "这是宿主画的卡片：按钮最多 3 个、空串跳过、回调只来一次。\n"
                        + "点「打开日志页」会走 api.openPage 跳到宿主的运行日志页。",
                new String[]{"打开日志页", "知道了"}, new PluginApi.Card() {
                    @Override public void onCard(int index) {
                        if (index == 0) {
                            api.openPage("logs", null);
                        } else if (index == 1) {
                            api.log("SAMPLE card dismissed by button");
                        }
                    }
                });
    }
}
