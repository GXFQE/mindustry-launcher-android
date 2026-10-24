package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把插件声明的**页面动作**画成一行（第 159 轮扩展点 v2；第 160 轮扩到全部页面）。
 *
 * <p>三件事，顺序固定：
 * <ol>
 *   <li>取**生效中**的插件（{@link Plugins#active}）—— 待重启生效 / 已停用的不出现，
 *       否则用户会看到"停用了还在"；</li>
 *   <li>读每个 APK 的自述（{@link Extensions#of}，**几 KB，不加载插件代码**）⇒ 只要这一页的条目；</li>
 *   <li>四个内容页插到「删除」之前；其余页面插到统一那块「插件」卡里（没有条目整块 GONE）。</li>
 * </ol>
 *
 * <p>★ 只读声明、不加载插件代码：某台插件坏了最多是它那一行不出现，页面照常打开；
 * 真正的加载推迟到用户点它的那一刻（与插件页同一条规矩）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
final class PluginEntries {

    /** 一条"可渲染的动作" = 插件身份 + 它声明的条目。 */
    static final class Entry {
        final String id, ver, name;
        final Extensions.Item item;

        Entry(String id, String ver, String name, Extensions.Item item) {
            this.id = id;
            this.ver = ver;
            this.name = name;
            this.item = item;
        }
    }

    private PluginEntries() { }

    /**
     * 这一页现在该显示哪些条目（**纯取数**，不碰 View；单元测试 / 报告都能直接调）。
     *
     * <p>顺序 = {@code active.txt} 里的登记顺序（同一份判据给插件页与内容页，别各排各的）。
     */
    static List<Entry> collect(Context ctx, String page) {
        List<Entry> out = new ArrayList<Entry>();
        if (ctx == null || page == null || !Extensions.knownPage(page)) return out;
        String active = Plugins.active(ctx);
        for (String id : Plugins.stateIds(active)) {
            String ver = Plugins.stateGet(active, id);
            if (ver == null || ver.isEmpty()) continue;
            File apk = Plugins.apkOf(ctx, id, ver);
            List<Extensions.Item> items = Extensions.forPage(Extensions.of(apk), page);
            if (items.isEmpty()) continue;
            String name = Plugins.displayNameOf(ctx, id);
            for (Extensions.Item it : items) out.add(new Entry(id, ver, name, it));
        }
        return out;
    }

    /**
     * 往内容页的操作卡里挂插件动作行（**没有条目就一个像素都不动**）。
     *
     * @param beforeRowId 这一页「删除…」那一行的 id；插件行插在它**上面** ——
     *                    四个内容页的顺序因此仍是「导入 → 导出 → 专属动作 → 删除 → 筛选」
     *                    （第 157 轮定下的顺序，插件动作属于"这一个页面专属的事"）
     */
    /** 这一页该画哪些**动作**（`slot: action`）；与 {#collect} 同一套取数。 */
    static List<Entry> collectActions(Context ctx, String page) {
        List<Entry> out = new ArrayList<Entry>();
        if (ctx == null || page == null || !Extensions.knownPage(page)) return out;
        String active = Plugins.active(ctx);
        for (String id : Plugins.stateIds(active)) {
            String ver = Plugins.stateGet(active, id);
            if (ver == null || ver.isEmpty()) continue;
            File apk = Plugins.apkOf(ctx, id, ver);
            List<Extensions.Item> items = Extensions.forAction(Extensions.of(apk), page);
            if (items.isEmpty()) continue;
            String name = Plugins.displayNameOf(ctx, id);
            for (Extensions.Item it : items) out.add(new Entry(id, ver, name, it));
        }
        return out;
    }

    /**
     * 把插件声明的**动作**画成一行（**点了不换页**）：在当前页加载插件并调它的 {@code run(...)}。
     *
     * <p>对话框 / 选择器 / SAF / 进度都落在**原来那一页**上 —— 与搬家前宿主自己那条"导出统计…"
     * 一模一样（用户 2026-10-21：「像原来那样不单开页面能行吗」）。
     */
    static void attachAction(Activity a, ViewGroup box, String page, String slot, int beforeRowId) {
        if (a == null || box == null) return;
        List<Entry> rows = collectActions(a, page);
        if (rows.isEmpty()) return;
        int at = insertIndex(box, beforeRowId);
        for (Entry e : rows) {
            box.addView(divider(a), at++);
            box.addView(actionRow(a, e, page, slot), at++);
        }
    }

    private static View actionRow(final Activity a, final Entry e, final String page,
                                  final String slot) {
        View row = LayoutInflater.from(a).inflate(R.layout.row_action, null, false);
        ((ImageView) row.findViewById(R.id.act_icon)).setImageResource(R.drawable.ic_stats);
        ((TextView) row.findViewById(R.id.act_title)).setText(e.item.label());
        String sub = e.item.subtitle == null ? "" : e.item.subtitle.trim();
        ((TextView) row.findViewById(R.id.act_sub)).setText(sub.isEmpty()
                ? Trans.get(a, R.string.plugin_ext_from_fmt, e.name) : sub);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    File apk = Plugins.apkOf(a, e.id, e.ver);
                    if (apk == null || !apk.isFile()) return;
                    PluginHost.Loaded l = Plugins.isInjected(a, e.id, e.ver)
                            ? PluginHost.openInjected(a, apk, e.item.entry, page, slot, null, null)
                            : PluginHost.load(a, apk, e.item.entry, page, slot, null, null);
                    PluginHost.runEntry(l);
                } catch (PluginHost.PluginException pe) {
                    // 一份坏插件不该影响这一页；但拒绝原因要说清（与插件页同一个 reasonRes）
                    android.widget.Toast.makeText(a, a.getString(PluginHost.reasonRes(pe.reason)),
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    // 一份坏插件不该影响这一页（与段落插槽同一条规矩）
                    android.widget.Toast.makeText(a, a.getString(R.string.plugin_err_other),
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        return row;
    }

    /** 这一页该画哪些**段落**（`slot: section`）；与 {#collect} 同一套取数。 */
    static List<Entry> collectSections(Context ctx, String page) {
        List<Entry> out = new ArrayList<Entry>();
        if (ctx == null || page == null || !Extensions.knownPage(page)) return out;
        String active = Plugins.active(ctx);
        for (String id : Plugins.stateIds(active)) {
            String ver = Plugins.stateGet(active, id);
            if (ver == null || ver.isEmpty()) continue;
            File apk = Plugins.apkOf(ctx, id, ver);
            List<Extensions.Item> items = Extensions.forSection(Extensions.of(apk), page);
            if (items.isEmpty()) continue;
            String name = Plugins.displayNameOf(ctx, id);
            for (Extensions.Item it : items) out.add(new Entry(id, ver, name, it));
        }
        return out;
    }

    /**
     * 把插件声明的**段落**画进宿主页里（第 193 轮 v9）。
     *
     * <p>某一份插件坏掉 ⇒ 跳过它、其余照画（页面绝不变白）。没有段落 ⇒ 容器整块 {@code GONE}。
     */
    static void attachSection(Activity a, ViewGroup box, String page, String slot, File selected,
                              io.mdt.launcher.api.ItemInfo item) {
        if (a == null || box == null) return;
        List<Entry> rows = collectSections(a, page);
        if (rows.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        for (Entry e : rows) {
            try {
                File apk = Plugins.apkOf(a, e.id, e.ver);
                if (apk == null || !apk.isFile()) continue;
                PluginHost.Loaded l = Plugins.isInjected(a, e.id, e.ver)
                        ? PluginHost.openInjected(a, apk, e.item.entry, page, slot, selected, item)
                        : PluginHost.load(a, apk, e.item.entry, page, slot, selected, item);
                View v = PluginHost.createView(l);
                if (v != null) box.addView(v);
            } catch (Throwable ignored) {
                // 一份坏的插件不该把宿主页弄白（与列表那一行同一条规矩）
            }
        }
    }

    static void attach(Activity a, ViewGroup box, String page, String slot, int beforeRowId) {
        attach(a, box, page, slot, beforeRowId, null);
    }

    /** 同 {@link #attach(Activity, ViewGroup, String, String, int)}，额外带上详情页选中项。 */
    static void attach(Activity a, ViewGroup box, String page, String slot, int beforeRowId,
                       File selected) {
        if (a == null || box == null) return;
        List<Entry> rows = collect(a, page);
        if (rows.isEmpty()) return;
        int at = insertIndex(box, beforeRowId);
        for (Entry e : rows) {
            box.addView(divider(a), at++);
            box.addView(row(a, e, page, slot, selected), at++);
        }
    }

    /**
     * 往通用页面的「插件」卡里挂动作行（第 160 轮）。
     *
     * <p>卡片是布局里一段固定 include（{@code plugin_section} + {@code plugin_section_rows}）；
     * 没有条目就整块 {@code GONE} —— **不占高度、不留空卡**。
     * 放的位置由各页布局决定（主界面在首卡下面、设置页在底部……）。
     */
    static void attachPage(Activity a, String page, String slot, File selected) {
        attachPage(a, page, slot, selected, null);
    }

    /**
     * v25：页面想自己画「挂到某一项上」的结论时实现它（模组页就是）。
     *
     * <p>不实现也不丢：`anchorPath` 的结论会退化成卡片里的一条（`showFindings` 只是画不出"画在哪一行"）。
     * 分析器回来时在**主线程**回调，页面可以直接重画列表。
     */
    interface FindingsSink {
        void onPluginFindings(java.util.List<io.mdt.launcher.api.Finding> fs);

        /**
         * v26：插件对每一项的**判定**（path → token）。空表也算一次回调 —— 页面据此决定
         * "用插件的判定"还是"回落到宿主自己的判定"。
         */
        void onPluginStates(java.util.Map<String, String> verdicts);

        /**
         * v27：插件要放在**摘要卡**里的槽级结论（{@code SURFACE_SUMMARY}）。
         * 结论归插件，位置在摘要卡 —— 两件事分开。
         */
        void onPluginSummary(java.util.List<io.mdt.launcher.api.Finding> fs);

        /**
         * **v27**：插件给的「六道加载门」结论（path → 门）。
         * 空表 = 没有插件能算 ⇒ 详情页回落到宿主自己的 {@code Mods.gates}。
         */
        void onPluginGates(java.util.Map<String, java.util.List<io.mdt.launcher.api.Gate>> gates);
    }

    /**
     * 通用页面的「插件」卡：**三种插入一次挂齐**（v24 第 226 轮统一）。
     *
     * <ul>
     *   <li>`section` → 插件自己的 View，画在卡片上半部分（{@code plugin_section_body}）；</li>
     *   <li>`content` → 一行，点开插件页；</li>
     *   <li>`action` → 一行，点了在当前页跑 {@code run()}。</li>
     * </ul>
     * 哪一种都没有 ⇒ 整块 {@code GONE}。没挂载点的页面静默跳过。
     */
    static void attachPage(Activity a, String page, String slot, File selected,
                           io.mdt.launcher.api.ItemInfo item) {
        if (a == null || page == null) return;
        View section = a.findViewById(R.id.plugin_section);
        if (section == null) return;                     // 这一页没有挂载点 ⇒ 静默跳过
        ViewGroup body = (ViewGroup) section.findViewById(R.id.plugin_section_body);
        ViewGroup box = (ViewGroup) section.findViewById(R.id.plugin_section_rows);
        if (box == null) return;
        if (body != null) body.removeAllViews();
        box.removeAllViews();

        // ① 段落（section）：插件自己的 View
        for (Entry e : collectSections(a, page)) {
            try {
                File apk = Plugins.apkOf(a, e.id, e.ver);
                if (apk == null || !apk.isFile()) continue;
                PluginHost.Loaded l = Plugins.isInjected(a, e.id, e.ver)
                        ? PluginHost.openInjected(a, apk, e.item.entry, page, slot, selected, item)
                        : PluginHost.load(a, apk, e.item.entry, page, slot, selected, item);
                View v = PluginHost.createView(l);
                if (v != null && body != null) body.addView(v);
            } catch (Throwable ignored) {
                // 一份坏的插件不该把宿主页弄白
            }
        }

        // ② 内容行（content）：点开插件页
        List<Entry> rows = collect(a, page);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) box.addView(divider(a));
            box.addView(row(a, rows.get(i), page, slot, selected));
        }

        // ③ 动作行（action）：原页跑 run()
        List<Entry> acts = collectActions(a, page);
        for (int i = 0; i < acts.size(); i++) {
            if (box.getChildCount() > 0) box.addView(divider(a));
            box.addView(actionRow(a, acts.get(i), page, slot));
        }

        boolean any = (body != null && body.getChildCount() > 0) || box.getChildCount() > 0;
        if (!any) {
            section.setVisibility(View.GONE);
        } else {
            TextView title = (TextView) section.findViewById(R.id.plugin_section_title);
            if (title != null) title.setText(Trans.get(a, R.string.plugin_section_title));
            section.setVisibility(View.VISIBLE);
        }

        // ④ 分析器（v24 / 第 227 轮起所有页面通用）：后台跑，回来时 token 没变才贴
        if (PluginAnalysis.has(a, page)) {
            final View sec = section;
            final int token = 1 + (sec.getTag() instanceof Integer ? (Integer) sec.getTag() : 0);
            sec.setTag(token);
            final String fp = page;
            final String fslot = slot;
            final File fsel = selected;
            final io.mdt.launcher.api.ItemInfo fitem = item;
            new Thread(new Runnable() {
                @Override public void run() {
                    final java.util.Map<String, String> verdicts =
                            new java.util.HashMap<String, String>();
                    // v27：六道门的插件结论（path → 门）
                    final java.util.Map<String, java.util.List<io.mdt.launcher.api.Gate>> gates =
                            new java.util.HashMap<String, java.util.List<io.mdt.launcher.api.Gate>>();
                    final java.util.List<io.mdt.launcher.api.Finding> all =
                            PluginAnalysis.analyzePage(a, fp, fslot, fsel, fitem, verdicts, gates);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!(sec.getTag() instanceof Integer) || (Integer) sec.getTag() != token) return;
                            // v25：挂到项上的归页面自己画（行 / 详情）；不挂的进卡片
                            java.util.List<io.mdt.launcher.api.Finding> card =
                                    new java.util.ArrayList<io.mdt.launcher.api.Finding>();
                            java.util.List<io.mdt.launcher.api.Finding> anchored =
                                    new java.util.ArrayList<io.mdt.launcher.api.Finding>();
                            // v27：不挂行但要求进摘要卡的（槽级结论）
                            java.util.List<io.mdt.launcher.api.Finding> summary =
                                    new java.util.ArrayList<io.mdt.launcher.api.Finding>();
                            for (io.mdt.launcher.api.Finding f : all) {
                                if (f == null) continue;
                                if (f.anchorPath != null) anchored.add(f);
                                else if (f.surface == io.mdt.launcher.api.Finding.SURFACE_SUMMARY) summary.add(f);
                                else card.add(f);
                            }
                            if (a instanceof FindingsSink) {
                                if (!anchored.isEmpty()) {
                                    ((FindingsSink) a).onPluginFindings(anchored);
                                }
                                ((FindingsSink) a).onPluginStates(verdicts);
                                ((FindingsSink) a).onPluginSummary(summary);
                                ((FindingsSink) a).onPluginGates(gates);
                            }
                            showFindings(a, sec, body, card);
                        }
                    });
                }
            }, "mdt-analysis").start();
        }
    }

    /** 把插件给的结论画成「插件」卡里的一段文字（最多 6 条）。 */
    /** 把插件给的结论画成「插件」卡里的一段（按严重度上色；超过 6 条给「显示全部」）。 */
    private static void showFindings(Activity a, View section, ViewGroup body,
                                     java.util.List<io.mdt.launcher.api.Finding> fs) {
        if (a == null || section == null || body == null || fs == null || fs.isEmpty()) return;
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 10);
        col.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(a);
        head.setText(Trans.get(a, R.string.plugin_analysis_title));
        head.setTextColor(a.getResources().getColor(R.color.fg_muted));
        head.setTextSize(12f);
        col.addView(head);

        final int LIMIT = 6;
        final int shown = Math.min(fs.size(), LIMIT);
        for (int i = 0; i < shown; i++) col.addView(findingRow(a, fs.get(i)));

        if (fs.size() > shown) {
            TextView more = new TextView(a);
            more.setText(Trans.get(a, R.string.plugin_analysis_more_fmt, fs.size() - shown));
            more.setTextColor(a.getResources().getColor(R.color.accent));
            more.setTextSize(13f);
            more.setPadding(0, dp(a, 8), 0, 0);
            more.setClickable(true);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    col.removeView(v);
                    for (int i = shown; i < fs.size(); i++) col.addView(findingRow(a, fs.get(i)));
                }
            });
            col.addView(more);
        }
        body.addView(col);
        section.setVisibility(View.VISIBLE);
    }

    /** 一条结论：`[严重度] 标题` + 灰色明细。 */
    private static View findingRow(Activity a, io.mdt.launcher.api.Finding f) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(a, 8), 0, 0);

        TextView title = new TextView(a);
        title.setText(severityLabel(a, f.severity) + " · " + f.title);
        title.setTextColor(severityColor(a, f.severity));
        title.setTextSize(14f);
        row.addView(title);

        if (!f.detail.isEmpty()) {
            TextView d = new TextView(a);
            d.setText(f.detail);
            d.setTextColor(a.getResources().getColor(R.color.fg_muted));
            d.setTextSize(12f);
            d.setPadding(0, dp(a, 2), 0, 0);
            row.addView(d);
        }
        return row;
    }

    private static String severityLabel(Activity a, int sev) {
        int r = sev == io.mdt.launcher.api.Finding.ERROR ? R.string.plugin_sev_error
                : sev == io.mdt.launcher.api.Finding.WARN ? R.string.plugin_sev_warn
                : R.string.plugin_sev_info;
        return Trans.get(a, r);
    }

    private static int severityColor(Activity a, int sev) {
        int r = sev == io.mdt.launcher.api.Finding.ERROR ? R.color.plugin_sev_error
                : sev == io.mdt.launcher.api.Finding.WARN ? R.color.plugin_sev_warn
                : R.color.plugin_sev_info;
        return a.getResources().getColor(r);
    }

    /** 插到目标行**上面那条分割线**之前；找不到目标行就追加到末尾。 */
    private static int insertIndex(ViewGroup box, int beforeRowId) {
        if (beforeRowId != 0) {
            View before = box.findViewById(beforeRowId);
            if (before != null) {
                int i = box.indexOfChild(before);
                if (i > 0) return i - 1;      // 行前面还有一条分割线
            }
        }
        return box.getChildCount();
    }

    private static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(a.getResources().getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1));
        lp.setMarginStart(dp(a, 62));
        v.setLayoutParams(lp);
        return v;
    }

    private static View row(final Activity a, final Entry e, final String page, final String slot,
                            final File selected) {
        View row = LayoutInflater.from(a).inflate(R.layout.row_action, null, false);
        ((ImageView) row.findViewById(R.id.act_icon)).setImageResource(R.drawable.ic_plugins_bar);
        ((TextView) row.findViewById(R.id.act_title)).setText(e.item.label());
        ((TextView) row.findViewById(R.id.act_sub))
                .setText(Trans.get(a, R.string.plugin_ext_from_fmt, e.name));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PluginsActivity.start(a, e.id, e.ver, e.item.entry, page, slot,
                        e.item.label(), selected);
            }
        });
        return row;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
