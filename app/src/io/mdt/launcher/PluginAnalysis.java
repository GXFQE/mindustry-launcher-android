package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.mdt.launcher.api.Finding;
import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;

/**
 * 分析器调度（v24）：把「插件给结论、宿主渲染」这条线接起来。
 *
 * <p>插件在自述里声明 `{"slot":"analyzer","page":"mods","entry":"..."}`，入口类提供
 * `public static List<Finding> analyze(ItemInfo item, PluginApi api)`。
 * 宿主按页调用（当前只有模组页），一个插件只 load 一次，逐项调用；
 * 任何异常只影响这一条，不打断页面。
 */
final class PluginAnalysis {

    /** 一次最多分析多少项（防御坏插件把页面拖死；模组页实际远小于它）。 */
    private static final int MAX_ITEMS = 300;

    private PluginAnalysis() { }

    /** 模组页：对每一条模组跑一遍分析器，收集结论。 */
    static List<Finding> analyzeMods(Context ctx, String slot, List<Mods.Info> mods) {
        List<Finding> out = new ArrayList<Finding>();
        if (ctx == null || mods == null || mods.isEmpty()) return out;
        try {
            String active = Plugins.active(ctx);
            for (String id : Plugins.stateIds(active)) {
                String ver = Plugins.stateGet(active, id);
                File apk = Plugins.apkOf(ctx, id, ver);
                if (apk == null || !apk.isFile()) continue;
                List<Extensions.Item> items = Extensions.forSlot(
                        Extensions.of(apk), Extensions.SLOT_ANALYZER);
                for (Extensions.Item it : items) {
                    if (!Extensions.PAGE_MODS.equals(it.page)) continue;
                    runOne(ctx, slot, it, apk, mods, out);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void runOne(Context ctx, String slot, Extensions.Item it, File apk,
                               List<Mods.Info> mods, List<Finding> out) {
        PluginHost.Loaded loaded = null;
        try {
            loaded = PluginHost.load(ctx, apk, it.entry, Extensions.PAGE_MODS, slot);
        } catch (Throwable t) {
            loaded = null;
        }
        if (loaded == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = loaded.entry.getMethod("analyze",
                    ItemInfo.class, PluginApi.class);
            int n = 0;
            for (Mods.Info mi : mods) {
                if (n++ >= MAX_ITEMS) break;
                try {
                    Object r = m.invoke(null, toItem(mi), loaded.env);
                    if (r instanceof List) {
                        for (Object o : (List<?>) r) {
                            if (o instanceof Finding) out.add((Finding) o);
                        }
                    }
                } catch (Throwable ignored) {
                    // 单条失败不影响别的条目
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { PluginHost.unload(loaded); } catch (Throwable ignored) { }
        }
    }

    /** 模组 → 给插件看的只读快照（插件不该依赖这里没有的字段）。 */
    private static ItemInfo toItem(Mods.Info m) {
        String title;
        try {
            title = m.title();
        } catch (Throwable t) {
            title = null;
        }
        if (title == null || title.isEmpty()) {
            title = m.displayName != null && !m.displayName.isEmpty()
                    ? m.displayName : String.valueOf(m.name);
        }
        String sub = "v" + (m.version == null ? "0" : m.version);
        String path = m.rootDir == null ? null : m.rootDir.getAbsolutePath();
        return new ItemInfo(ItemInfo.KIND_MODS, title, sub, path, null, null,
                0, 0, "mods", m.metaError, m.rootDir != null && m.rootDir.isDirectory(),
                m.metaError == null, null);
    }
}
