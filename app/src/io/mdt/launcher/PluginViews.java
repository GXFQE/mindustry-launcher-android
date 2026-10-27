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
 * <p>声明写在插件自述里（与 `extensions` 同一个数组），`page` 可以是**四个列表页**
 * （`mods` / `saves` / `maps` / `blueprints`）中的任意一个：
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
 * <p>★ **v33（第 282 轮）加一步可选的"先问用户"**：入口类还可以给
 * {@code static String[] options(PluginApi)} + {@code static void choose(int, PluginApi)} ——
 * 宿主先把 options 显示成一张选择卡，用户点完调 `choose(index)`，**同一个已加载实例**上再跑
 * accept / compare（插件把选择记进静态字段）。没有 options（或没有 choose）就照旧一步跑完。</p>
 *
 * <p>★ **只在用户选中那一项时才算**（不选不跑）。插件坏了 / 被删了 ⇒ 返回"什么都没筛 / 原顺序"，
 * 不把页面弄崩。★ 本类只回答"哪些条目 / 什么顺序"（身份见 {@link PluginPicks#idOf}）；
 * **应用结果与取数**在 {@link PluginPicks}（四个列表页共用那一份）。
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
     * 一次注入跑批的会话：**加载一次 →（可选）问选项 → 跑 accept / compare → 卸载**。
     *
     * <p>🔴 必须是同一份已加载实例：插件的 `options()` / `choose()` / `accept()` 之间靠静态字段传值，
     * 分两次加载会把选择丢掉（症状：选了矿，结果还是"全部"）。</p>
     */
    static final class Session {
        private final View view;
        private final String page;
        private final List<ItemInfo> items;
        private final PluginHost.Loaded loaded;

        private Session(View view, String page, List<ItemInfo> items, PluginHost.Loaded loaded) {
            this.view = view;
            this.page = page;
            this.items = items;
            this.loaded = loaded;
        }

        /** 打不开（插件不在 / 加载失败 / 类缺失）⇒ null。 */
        static Session open(Context ctx, View v, List<ItemInfo> items, String page, String slot) {
            if (ctx == null || v == null) return null;
            PluginHost.Loaded l = null;
            try {
                File apk = Plugins.apkOf(ctx, v.pluginId, v.pluginVer);
                if (apk == null || !apk.isFile()) return null;
                l = Plugins.isInjected(ctx, v.pluginId, v.pluginVer)
                        ? PluginHost.openInjected(ctx, apk, v.item.entry, page, slot, null, null)
                        : PluginHost.load(ctx, apk, v.item.entry, page, slot, null, null);
            } catch (Throwable t) {
                android.util.Log.w("MDTLauncher", "plugin session failed: " + v.key(), t);
                return null;
            }
            if (l == null) return null;
            return new Session(v, page, items == null ? new ArrayList<ItemInfo>() : items, l);
        }

        /**
         * 这一插件**是否要用户先选一项**：返回选项标签（已过闸门）；null = 不用选
         * （没实现 `options` / 返回空 / 没有配 `choose` / 抛异常）。
         */
        String[] options() {
            try {
                Method m = loaded.entry.getMethod("options", PluginApi.class);
                Object r = m.invoke(null, loaded.env);
                if (!(r instanceof String[])) return null;
                String[] gated = gateOptions((String[]) r);
                if (gated == null) return null;
                // 有选项却没有 choose ⇒ 选了也传不回去，宁可不要这一步
                loaded.entry.getMethod("choose", int.class, PluginApi.class);
                return gated;
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** 把用户选的序号告诉插件（**同一个已加载实例**上的静态字段）。 */
        void choose(int index) {
            try {
                Method m = loaded.entry.getMethod("choose", int.class, PluginApi.class);
                m.invoke(null, Integer.valueOf(index), loaded.env);
            } catch (Throwable ignored) {
            }
        }

        /** 跑一遍筛选：返回**通过的身份**集合（{@link PluginPicks#idOf}）。 */
        Set<String> accepted() {
            Set<String> out = new LinkedHashSet<String>();
            try {
                Method m = loaded.entry.getMethod("accept", ItemInfo.class, PluginApi.class);
                for (ItemInfo it : items) {
                    if (it == null) continue;
                    Object r;
                    try {
                        r = m.invoke(null, it, loaded.env);
                    } catch (Throwable one) {
                        continue;                   // 单项失败不影响别的
                    }
                    if (r instanceof Boolean && ((Boolean) r).booleanValue()) {
                        String id = PluginPicks.idOf(it);
                        if (!id.isEmpty()) out.add(id);
                    }
                }
                android.util.Log.i("MDTLauncher", "plugin filter " + view.key() + " @" + page + ": "
                        + out.size() + "/" + items.size());
            } catch (Throwable t) {
                android.util.Log.w("MDTLauncher", "plugin filter failed: " + view.key(), t);
            }
            return out;
        }

        /** 按插件的比较函数排序：返回**身份顺序**。 */
        List<String> sorted() {
            List<ItemInfo> out = new ArrayList<ItemInfo>(items);
            try {
                if (out.size() < 2) return ids(out);
                final Method m = loaded.entry.getMethod("compare",
                        ItemInfo.class, ItemInfo.class, PluginApi.class);
                final PluginApi env = loaded.env;
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
            } catch (Throwable t) {
                android.util.Log.w("MDTLauncher", "plugin sort failed: " + view.key(), t);
            }
            List<String> idsOut = ids(out);
            android.util.Log.i("MDTLauncher", "plugin sort " + view.key() + " @" + page + ": "
                    + idsOut.size() + " ids");
            return idsOut;
        }

        void close() {
            try { PluginHost.unload(loaded); } catch (Throwable ignored) { }
        }
    }

    /** `ItemInfo` 表 ⇒ 身份顺序（空身份跳过）。 */
    private static List<String> ids(List<ItemInfo> items) {
        List<String> out = new ArrayList<String>();
        if (items == null) return out;
        for (ItemInfo it : items) {
            String id = PluginPicks.idOf(it);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /**
     * 插件给的选项过闸门（与"插件给的文字"同一条纪律，见 {@code PluginActions.pickText}）：
     * 去首尾空白、丢空条、**按码点**截长 24、最多 **24 项**。
     *
     * <p>为什么要限：这些标签进 `CardStack` 的列表，一个插件给 500 条或一行一万字会把卡片撑爆。</p>
     *
     * @return 过完闸门的数组；一条都不剩 ⇒ null
     */
    static String[] gateOptions(String[] raw) {
        if (raw == null) return null;
        List<String> out = new ArrayList<String>();
        for (String s : raw) {
            if (out.size() >= 24) break;
            if (s == null) continue;
            String one = s.trim().replace('\n', ' ').replace('\r', ' ');
            if (one.isEmpty()) continue;
            if (one.codePointCount(0, one.length()) > 24) {
                one = one.substring(0, one.offsetByCodePoints(0, 24));
            }
            out.add(one);
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }
}
