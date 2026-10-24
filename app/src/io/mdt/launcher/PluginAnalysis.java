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
 * <p>插件在自述里声明 {@code {"slot":"analyzer","page":"<页>","entry":"..."}}，入口类提供
 * {@code public static List<Finding> analyze(ItemInfo item, PluginApi api)}。
 *
 * <p>★ 2026-10-24（第 227 轮）：**不再只认 mods** —— 任何有页面数据的页都能挂：
 * 数据源和插件拿到的 {@code pageItems()} 是**同一个** {@link PluginData#pageItems}，
 * 所以插件看到什么，分析器就分析什么。
 */
final class PluginAnalysis {

    /** 一页最多分析多少项（防御坏插件把页面拖死）。 */
    private static final int MAX_ITEMS = 300;

    private PluginAnalysis() { }

    /** 这一页有没有分析器声明（纯取数；没有就不必起线程）。 */
    static boolean has(Context ctx, String page) {
        return !collect(ctx, page).isEmpty();
    }

    /** 这一页声明的分析器条目（按 active 顺序）。 */
    static List<Extensions.Item> collect(Context ctx, String page) {
        List<Extensions.Item> out = new ArrayList<Extensions.Item>();
        if (ctx == null || page == null || !Extensions.knownPage(page)) return out;
        try {
            String active = Plugins.active(ctx);
            for (String id : Plugins.stateIds(active)) {
                String ver = Plugins.stateGet(active, id);
                File apk = Plugins.apkOf(ctx, id, ver);
                if (apk == null || !apk.isFile()) continue;
                for (Extensions.Item it : Extensions.forSlot(
                        Extensions.of(apk), Extensions.SLOT_ANALYZER)) {
                    if (page.equals(it.page)) out.add(it);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 对这一页的条目跑一遍分析器。
     *
     * @param selected 详情页当前选中的文件（其余页 null）
     * @param item     详情页当前选中条目的完整快照（其余页 null）
     */
    static List<Finding> analyzePage(Context ctx, String page, String slot,
                                     File selected, ItemInfo item) {
        return analyzePage(ctx, page, slot, selected, item, null, null);
    }

    /**
     * 同上，但顺手把插件的**逐项判定**收进 {@code verdicts}（path → token，v26）。
     *
     * <p>token 由插件给（{@code enabled} / {@code disabled} / {@code unsupported} /
     * {@code missing-dep} / {@code incomplete-dep} / {@code circular-dep} / {@code content-error}），
     * 宿主把它映射回自己的 {@code Mods.State} —— 这样徽标、筛选、详情用的是**同一个判定**。
     */
    static List<Finding> analyzePage(Context ctx, String page, String slot,
                                     File selected, ItemInfo item,
                                     java.util.Map<String, String> verdicts,
                                     java.util.Map<String, java.util.List<io.mdt.launcher.api.Gate>> gates) {
        List<Finding> out = new ArrayList<Finding>();
        if (ctx == null || page == null) return out;
        List<ItemInfo> items = PluginData.pageItems(ctx, page, slot, selected);
        if (items.isEmpty()) return out;
        try {
            String active = Plugins.active(ctx);
            for (String id : Plugins.stateIds(active)) {
                String ver = Plugins.stateGet(active, id);
                File apk = Plugins.apkOf(ctx, id, ver);
                if (apk == null || !apk.isFile()) continue;
                for (Extensions.Item it : Extensions.forSlot(
                        Extensions.of(apk), Extensions.SLOT_ANALYZER)) {
                    if (!page.equals(it.page)) continue;
                    runOne(ctx, apk, it, page, slot, selected, item, items, out, verdicts, gates);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 第 233 轮（P5，导入体检）：`path → 挂在这一项上的结论`。
     *
     * <p>★ 复用**整页**那一遍（插件的 analyzer 按"页面第一项"给整槽结论，见 {@link #analyzePage}），
     * 只挑 {@code anchorPath} 精确等于该值的 —— **槽级 / 卡片结论不要**：导入弹窗要回答的是
     * "这个包怎么了"，不是"这个槽怎么了"。
     *
     * <p>表里**每一项都有一条**（没结论的就是空表）—— 这样"扫到了、没问题"与"没扫到这一项"
     * 能分清（导入体检靠它决定是"✅ 没问题"还是干脆不显示）。没有分析器声明时返回空表；
     * 调用方用 {@link #has} 区分"没装插件"与"没发现问题"（没装插件 ⇒ 整段不显示，
     * 绝不说"没问题"）。
     */
    static java.util.Map<String, java.util.List<Finding>> anchoredByPath(Context ctx, String slot) {
        java.util.Map<String, java.util.List<Finding>> out =
                new java.util.LinkedHashMap<String, java.util.List<Finding>>();
        for (ItemInfo it : PluginData.pageItems(ctx, Extensions.PAGE_MODS, slot, null)) {
            if (it == null || it.path == null || out.containsKey(it.path)) continue;
            out.put(it.path, new ArrayList<Finding>());
        }
        for (Finding f : analyzePage(ctx, Extensions.PAGE_MODS, slot, null, null)) {
            if (f == null || f.anchorPath == null) continue;
            java.util.List<Finding> one = out.get(f.anchorPath);
            if (one == null) {
                one = new ArrayList<Finding>();
                out.put(f.anchorPath, one);
            }
            one.add(f);
        }
        return out;
    }

    private static void runOne(Context ctx, File apk, Extensions.Item it, String page,
                               String slot, File selected, ItemInfo item,
                               List<ItemInfo> items, List<Finding> out,
                               java.util.Map<String, String> verdicts,
                               java.util.Map<String, java.util.List<io.mdt.launcher.api.Gate>> gates) {
        PluginHost.Loaded loaded = null;
        try {
            loaded = PluginHost.load(ctx, apk, it.entry, page, slot, selected, item);
        } catch (Throwable t) {
            loaded = null;
        }
        if (loaded == null) return;
        try {
            java.lang.reflect.Method m = loaded.entry.getMethod("analyze",
                    ItemInfo.class, PluginApi.class);
            // v26：可选 `state(ItemInfo, PluginApi)` —— 不实现就当没有（宿主的判定兜底）
            java.lang.reflect.Method st = null;
            try {
                st = loaded.entry.getMethod("state", ItemInfo.class, PluginApi.class);
            } catch (Throwable ignored) {
            }
            // v27：可选 `gates(ItemInfo, PluginApi)` —— 详情页六道门的结论
            java.lang.reflect.Method gt = null;
            try {
                gt = loaded.entry.getMethod("gates", ItemInfo.class, PluginApi.class);
            } catch (Throwable ignored) {
            }
            int n = 0;
            for (ItemInfo one : items) {
                if (n++ >= MAX_ITEMS) break;
                try {
                    if (st != null && verdicts != null && one.path != null) {
                        try {
                            Object v = st.invoke(null, one, loaded.env);
                            if (v instanceof String && !((String) v).isEmpty()) {
                                verdicts.put(one.path, (String) v);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (gt != null && gates != null && one.path != null) {
                        try {
                            Object g = gt.invoke(null, one, loaded.env);
                            if (g instanceof java.util.List) {
                                java.util.List<io.mdt.launcher.api.Gate> gs =
                                        new java.util.ArrayList<io.mdt.launcher.api.Gate>();
                                for (Object o : (java.util.List<?>) g) {
                                    if (o instanceof io.mdt.launcher.api.Gate) {
                                        gs.add((io.mdt.launcher.api.Gate) o);
                                    }
                                }
                                if (!gs.isEmpty()) gates.put(one.path, gs);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    Object r = m.invoke(null, one, loaded.env);
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
}
