package io.mdt.launcher;

import android.app.Activity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.mdt.launcher.api.ItemInfo;

/**
 * **v25 结果层**：插件注入的筛选 / 排序，**四个列表页共用这一份实现**（2026-10-27 第 280 轮统一）。
 *
 * <p>背景：v25（第 229 轮）只给模组页做了这一层，而且结果按 {@code ItemInfo.path} 回传 ——
 * 游戏自带图 / 模组自带图在 APK、zip 里，{@code path == null} ⇒ 那类条目会被静默丢掉。
 * 四个列表页（模组 / 存档 / 地图 / 蓝图）的「筛选与排序」是同一件事，所以这里只留一份：
 *
 * <ol>
 *   <li><b>身份</b>（{@link #idOf}）：槽内文件 = 绝对路径；包内条目 = {@code 容器绝对路径!/条目}。
 *       两处算出来必须逐字相同 —— 插件拿到的 {@link ItemInfo} 与页面自己那份（{@code Maps.Item}
 *       之类）各算各的，差一个字就会"筛完一条不剩"。</li>
 *   <li><b>应用</b>（{@link #applyFilter} / {@link #applySort}）：纯函数，不改入参。
 *       排序里**没点到名的条目排最后**（插件只认识一部分条目时不能把它们弄丢）。</li>
 *   <li><b>取数</b>（{@link #pick}）：弹一张「正在问插件…」的卡，后台问一遍，回来在 UI 线程交结果。</li>
 *   <li><b>菜单行</b>（{@link #choices}）：把这一页声明的插件筛选 / 排序变成"标签 + 点下去做什么"。</li>
 * </ol>
 *
 * <p>★ 只在用户选中那一项时算一次，结果由页面**缓存成身份集合 / 顺序**，别塞进每次
 * {@code rebuildList()}（打字搜索会卡）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
final class PluginPicks {

    /** 包内条目的身份分隔符：`<容器绝对路径>!/<条目名>` */
    static final String SEP = "!/";

    private PluginPicks() { }

    /** 一项的稳定身份：槽内文件 = 绝对路径；包内条目 = `容器!/条目`；都没有 = 空串。 */
    static String idOf(ItemInfo it) {
        if (it == null) return "";
        if (it.path != null && !it.path.isEmpty()) return it.path;
        return idOf(null, it.container == null ? null : new File(it.container), it.entry);
    }

    /**
     * 同上，给页面自己的条目类型用（{@code Maps.Item} / {@code Blueprints.Item} 都只有这三样）。
     * ★ 与 {@link #idOf(ItemInfo)} 必须逐字相同（自检钉着这一条）。
     */
    static String idOf(File file, File container, String entry) {
        if (file != null) return file.getAbsolutePath();
        if (container != null && entry != null && !entry.isEmpty()) {
            return container.getAbsolutePath() + SEP + entry;
        }
        return "";
    }

    /** 页面把"我这一项的身份"告诉共用层（每种条目一个）。 */
    interface IdOf<T> {
        String idOf(T item);
    }

    /** 结果回给页面的那一边（在 UI 线程调用）。{@code key} = 选中的那一条 `pluginId/itemId`。 */
    interface Sink {
        /** 插件筛选通过的条目身份（空集 = 一条都不通过）。 */
        void onPluginFilter(String key, Set<String> ids);

        /** 插件给的顺序（同一份清单的身份；空 = 插件没给顺序）。 */
        void onPluginSort(String key, List<String> ids);
    }

    /** 只留 {@code keep} 里点名的条目；{@code keep == null} = 没开筛选（原表返回）。 */
    static <T> List<T> applyFilter(List<T> items, Set<String> keep, IdOf<T> idOf) {
        if (items == null) return new ArrayList<T>();
        if (keep == null) return new ArrayList<T>(items);
        List<T> out = new ArrayList<T>(items.size());
        for (T it : items) {
            if (it == null) continue;
            String id = idOf == null ? "" : idOf.idOf(it);
            if (id != null && !id.isEmpty() && keep.contains(id)) out.add(it);
        }
        return out;
    }

    /**
     * 按插件给的顺序重排（**稳定**：同名次保持原顺序；没点到名的排最后）。
     *
     * <p>{@code order == null} / 空表 / 只有一个元素 ⇒ 原表返回（插件没给顺序 = 不动，
     * 不能因为"它只认识三张图"就把别的图挪到后面再重新排一遍）。
     */
    static <T> List<T> applySort(List<T> items, List<String> order, IdOf<T> idOf) {
        List<T> out = new ArrayList<T>(items == null ? Collections.<T>emptyList() : items);
        if (order == null || order.isEmpty() || out.size() < 2) return out;
        final java.util.Map<String, Integer> rank = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            if (id != null && !id.isEmpty() && !rank.containsKey(id)) rank.put(id, Integer.valueOf(i));
        }
        if (rank.isEmpty()) return out;
        final int missing = order.size();
        final IdOf<T> key = idOf;
        Collections.sort(out, new Comparator<T>() {
            @Override public int compare(T a, T b) {
                Integer ra = key == null ? null : rank.get(key.idOf(a));
                Integer rb = key == null ? null : rank.get(key.idOf(b));
                int va = ra == null ? missing : ra.intValue();
                int vb = rb == null ? missing : rb.intValue();
                return va < vb ? -1 : (va > vb ? 1 : 0);
            }
        });
        return out;
    }

    /** 一行「插件注入的筛选 / 排序」：标签（带勾） + 点下去做什么。 */
    static final class Choice {
        final String label;
        final Runnable run;

        Choice(String label, Runnable run) {
            this.label = label == null ? "" : label;
            this.run = run;
        }
    }

    /**
     * 这一页在「筛选与排序」里该多出哪几行（插件排序 → 插件筛选，与模组页原来的顺序一致）。
     *
     * @param sort       true = 排序那一类，false = 筛选那一类
     * @param currentKey 当前选中的 `pluginId/itemId`（打勾用；null = 没选）
     */
    static List<Choice> choices(final Activity a, String page, String slot, boolean sort,
                                String currentKey, final Sink sink) {
        List<Choice> out = new ArrayList<Choice>();
        if (a == null || page == null) return out;
        final String tick = "✓ ";
        for (final PluginViews.View v : PluginViews.of(a, page,
                sort ? Extensions.SLOT_SORT : Extensions.SLOT_FILTER)) {
            boolean on = v.key().equals(currentKey);
            out.add(new Choice((on ? tick : "") + v.title(), new Runnable() {
                @Override public void run() {
                    pick(a, page, slot, v, sort, sink);
                }
            }));
        }
        return out;
    }

    /**
     * 弹「正在问插件…」→ 后台问一遍 → 回 UI 线程把结果交给 {@code sink}。
     *
     * <p>页面不在 / 插件不在 / 插件抛异常都**不崩**：插件那边返回空集或原顺序，页面照常重画
     * （与 {@link PluginViews} 的约定一致）。
     */
    static void pick(final Activity a, final String page, final String slot,
                     final PluginViews.View v, final boolean sort, final Sink sink) {
        if (a == null || v == null || sink == null) return;
        final CardStack stack = new CardStack(a);
        stack.replace(Trans.get(a, R.string.mods_filter_plugin_wait), "");
        new Thread(new Runnable() {
            @Override public void run() {
                final Set<String> keep = new LinkedHashSet<String>();
                final List<String> order = new ArrayList<String>();
                try {
                    List<ItemInfo> items = PluginData.pageItems(a, page, slot, null);
                    if (sort) order.addAll(PluginViews.sorted(a, v, items, page, slot));
                    else keep.addAll(PluginViews.accepted(a, v, items, page, slot));
                } catch (Throwable ignored) {
                    // 插件坏了 ⇒ 当没选（排序 = 原顺序；筛选 = 空集，页面会显示"筛完一条不剩"）
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            stack.dismiss();
                        } catch (Throwable ignored) { }
                        if (Util.dead(a)) return;
                        if (sort) sink.onPluginSort(v.key(), order);
                        else sink.onPluginFilter(v.key(), keep);
                    }
                });
            }
        }, "plugin-pick").start();
    }

}
