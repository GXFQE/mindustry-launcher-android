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
 * 把插件声明的**内容页动作**画成内容页里的一行（第 159 轮，扩展点 v2）。
 *
 * <p>三件事，顺序固定：
 * <ol>
 *   <li>取**生效中**的插件（{@link Plugins#active}）—— 待重启生效 / 已停用的不出现，
 *       否则用户会看到"停用了还在"；</li>
 *   <li>读每个 APK 的自述（{@link Extensions#of}，**几 KB，不加载插件代码**）⇒ 只要这一页的条目；</li>
 *   <li>插到"筛选"那一行**之前**（各行顺序仍是：导入 → 导出 → 专属动作 → 删除 → 筛选）。</li>
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
    static void attach(Activity a, ViewGroup box, String page, String slot, int beforeRowId) {
        if (a == null || box == null) return;
        List<Entry> rows = collect(a, page);
        if (rows.isEmpty()) return;
        int at = insertIndex(box, beforeRowId);
        for (Entry e : rows) {
            box.addView(divider(a), at++);
            box.addView(row(a, e, page, slot), at++);
        }
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

    private static View row(final Activity a, final Entry e, final String page, final String slot) {
        View row = LayoutInflater.from(a).inflate(R.layout.row_action, null, false);
        ((ImageView) row.findViewById(R.id.act_icon)).setImageResource(R.drawable.ic_plugins_bar);
        ((TextView) row.findViewById(R.id.act_title)).setText(e.item.label());
        ((TextView) row.findViewById(R.id.act_sub))
                .setText(Trans.get(a, R.string.plugin_ext_from_fmt, e.name));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PluginsActivity.start(a, e.id, e.ver, e.item.entry, page, slot, e.item.label());
            }
        });
        return row;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
