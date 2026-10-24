package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;

/**
 * **v25**：插件注入的筛选 / 排序（`slot: "filter"` / `"sort"`）。
 *
 * <p>声明写在插件自述里（与 `extensions` 同一个数组）：
 * <pre>
 * {"id":"conflict","title":"有冲突","slot":"filter","page":"mods","entry":"com.x.Filters"}
 * {"id":"risk","title":"按风险","slot":"sort","page":"mods","entry":"com.x.Sorts"}
 * </pre>
 * 入口类提供**静态**方法：
 * <ul>
 *   <li>filter：{@code public static boolean accept(ItemInfo item, PluginApi api)}</li>
 *   <li>sort：{@code public static int compare(ItemInfo a, ItemInfo b, PluginApi api)}（&lt;0 靠前）</li>
 * </ul>
 *
 * <p>★ **只在用户选中那一项时才算**（不选不跑）：算一遍可能加载插件类、也可能对每一项跑一次判据
 * —— 放进每次 `rebuildList()` 就会在打字搜索时卡。调用方把结果**缓存**下来（模组页就是这么做的）。
 * ★ 插件坏了 / 被删了 ⇒ 返回"什么都没筛 / 原顺序"，不把页面弄崩。
 */
final class PluginViews {

    private PluginViews() { }

    /** 一项被注入的视图（哪个插件、哪个条目）。 */
    static final class View {
        final String pluginId, pluginVer;
        final Extensions.Item item;

        View(String pluginId, String pluginVer, Extensions.Item item) {
            this.pluginId = pluginId;
            this.pluginVer = pluginVer;
            this.item = item;
        }

        /** 界面里的勾选判据（插件 + 条目 id） */
        String key() {
            return pluginId + "/" + item.id;
        }

        String title() {
            return item.label();
        }
    }

    /** 这一页声明了哪些筛选 / 排序（按已启用插件的状态顺序）。 */
    static List<View> of(Context ctx, String page, String slot) {
        List<View> out = new ArrayList<View>();
        if (ctx == null || page == null || slot == null || !Extensions.knownPage(page)) return out;
        try {
            String active = Plugins.active(ctx);
            for (String id : Plugins.stateIds(active)) {
                String ver = Plugins.stateGet(active, id);
                File apk = Plugins.apkOf(ctx, id, ver);
                if (apk == null || !apk.isFile()) continue;
                for (Extensions.Item it : Extensions.forSlot(Extensions.of(apk), slot)) {
                    if (page.equals(it.page)) out.add(new View(id, ver, it));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 跑一遍插件的筛选，返回**通过的** item path 集合。
     * 插件不在 / 方法缺失 / 抛异常 ⇒ 空集（调用方据此提示"没筛出东西"）。
     */
    static Set<String> accepted(Context ctx, View v, List<ItemInfo> items, String slot) {
        Set<String> out = new LinkedHashSet<String>();
        if (v == null || items == null || items.isEmpty()) return out;
        PluginHost.Loaded l = null;
        try {
            File apk = Plugins.apkOf(ctx, v.pluginId, v.pluginVer);
            if (apk == null || !apk.isFile()) return out;
            l = Plugins.isInjected(ctx, v.pluginId, v.pluginVer)
                    ? PluginHost.openInjected(ctx, apk, v.item.entry, "mods", slot, null, null)
                    : PluginHost.load(ctx, apk, v.item.entry, "mods", slot, null, null);
            Method m = l.entry.getMethod("accept", ItemInfo.class, PluginApi.class);
            for (ItemInfo it : items) {
                if (it == null) continue;
                Object r;
                try {
                    r = m.invoke(null, it, l.env);
                } catch (Throwable one) {
                    continue;                   // 单项失败不影响别的
                }
                if (r instanceof Boolean && ((Boolean) r).booleanValue() && it.path != null) {
                    out.add(it.path);
                }
            }
            android.util.Log.i("MDTLauncher", "plugin filter " + v.key() + ": "
                    + out.size() + "/" + items.size());
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "plugin filter failed: " + v.key(), t);
        } finally {
            try { PluginHost.unload(l); } catch (Throwable ignored) { }
        }
        return out;
    }

    /**
     * 按插件的比较函数排序（返回**新表**）。
     * 插件不在 / 方法缺失 / 抛异常 ⇒ 原顺序。
     */
    static List<ItemInfo> sorted(Context ctx, final View v, List<ItemInfo> items, String slot) {
        List<ItemInfo> out = new ArrayList<ItemInfo>(items == null ? Collections.<ItemInfo>emptyList() : items);
        if (v == null || out.size() < 2) return out;
        PluginHost.Loaded l = null;
        try {
            File apk = Plugins.apkOf(ctx, v.pluginId, v.pluginVer);
            if (apk == null || !apk.isFile()) return out;
            l = Plugins.isInjected(ctx, v.pluginId, v.pluginVer)
                    ? PluginHost.openInjected(ctx, apk, v.item.entry, "mods", slot, null, null)
                    : PluginHost.load(ctx, apk, v.item.entry, "mods", slot, null, null);
            final Method m = l.entry.getMethod("compare", ItemInfo.class, ItemInfo.class, PluginApi.class);
            final PluginApi env = l.env;
            Collections.sort(out, new Comparator<ItemInfo>() {
                @Override public int compare(ItemInfo a, ItemInfo b) {
                    try {
                        Object r = m.invoke(null, a, b, env);
                        return r instanceof Integer ? ((Integer) r).intValue() : 0;
                    } catch (Throwable t) {
                        return 0;               // 比不动就维持原序，别崩
                    }
                }
            });
        } catch (Throwable ignored) {
        } finally {
            try { PluginHost.unload(l); } catch (Throwable ignored) { }
        }
        return out;
    }
}
