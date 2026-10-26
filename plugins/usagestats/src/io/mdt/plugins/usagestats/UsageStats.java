package io.mdt.plugins.usagestats;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.usagestats.core.Playtime;

/**
 * 游玩时长（官方插件，v31）。
 *
 * <p>两个落点，**都在宿主自己的页面里**（插件页不再放入口）：
 * <ul>
 *   <li>`slot: section` + `page: slot` ⇒ 槽页信息卡下面一张卡：本槽合计 + 按游戏版本分列
 *       （已装游戏原地更新后，更新前后是**两行**）；</li>
 *   <li>`playtimeTotal`（plugin.json）⇒ 探针写的 `total.txt`，宿主把它排版进**主界面摘要卡**。</li>
 * </ul>
 * 这个类同时是插件自述里的默认入口：从插件页长按「打开」时，页面显示总计 + 按槽分列。
 */
public final class UsageStats {

    public static int api() {
        return 31;
    }

    public static View createView(PluginApi api) {
        Context c = api.context();
        PluginTheme th = api.theme();
        String ledger = api.dataDir() == null ? "" : read(new File(api.dataDir(), "ledger.tsv"));
        boolean slotPage = "slot".equals(api.page());
        String slot = api.slot() == null ? "" : api.slot();

        long total = slotPage ? Playtime.totalForSlot(ledger, slot) : Playtime.total(ledger);
        if (total <= 0) return null;                 // 没有记录 ⇒ 整张卡不出现（不留空壳）

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int hpad = th.dp(14);
        box.setPadding(hpad, th.dp(4), hpad, th.dp(4));

        TextView head = new TextView(c);
        head.setTextSize(14f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(th.text);
        head.setText(c.getString(slotPage ? R.string.us_slot_total_fmt : R.string.us_total_fmt,
                duration(c, total)));
        box.addView(head);

        List<Playtime.Entry> rows = slotPage
                ? Playtime.forSlot(ledger, slot)
                : Playtime.bySlot(ledger);
        Map<String, ItemInfo> items = slotPage ? versionItems(api) : null;
        List<Playtime.Slice> slices = new ArrayList<Playtime.Slice>();
        for (Playtime.Entry e : rows) {
            String name = slotPage ? versionLabel(c, items, e) : e.slot;
            slices.add(new Playtime.Slice(name, e.ms));
        }
        // 同一槽里「同一个游戏版本」可能有多行（已装包 + 导入的同版本 APK、换过包名等）：
        // 显示名里已含版本号，同名就合成一行（官方 / fork 同号但名字不同 ⇒ 不会误合）。
        final List<Playtime.Slice> merged = Playtime.mergeNamed(slices);
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        final TextView more = new TextView(c);
        more.setTextSize(12f);
        more.setTextColor(th.textMuted);
        more.setPadding(0, th.dp(8), 0, 0);
        more.setVisibility(View.GONE);
        box.addView(more);

        // 版本多了页面会被顶得很高：默认只看前几个，点一下展开 / 收起。
        final boolean[] expanded = {false};
        final Runnable[] paint = new Runnable[1];
        paint[0] = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                // 默认**全折叠**：只留合计，点「展开（N 个版本）」才画版本行（版本多了页面太高）。
                int show = expanded[0] ? merged.size() : 0;
                for (int i = 0; i < show; i++) {
                    Playtime.Slice s = merged.get(i);
                    list.addView(row(c, th, s.name, duration(c, s.ms)));
                }
                if (merged.isEmpty()) {
                    more.setVisibility(View.GONE);
                    more.setOnClickListener(null);
                } else {
                    more.setVisibility(View.VISIBLE);
                    more.setText(expanded[0] ? c.getString(R.string.us_collapse)
                            : c.getString(R.string.us_more_fmt, merged.size()));
                    more.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            expanded[0] = !expanded[0];
                            paint[0].run();
                        }
                    });
                }
            }
        };
        paint[0].run();

        TextView note = new TextView(c);
        note.setTextSize(11f);
        note.setTextColor(th.textMuted);
        note.setPadding(0, th.dp(8), 0, 0);
        note.setText(c.getString(R.string.us_note));
        box.addView(note);
        return box;
    }

    /** 名字 + 时长（时长靠右；名字让位，别把时长挤没）。 */
    private static View row(Context c, PluginTheme th, String name, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, th.dp(7), 0, 0);

        TextView left = new TextView(c);
        left.setTextSize(13f);
        left.setTextColor(th.text);
        left.setSingleLine(true);
        left.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, lp);
        left.setText(name);

        TextView right = new TextView(c);
        right.setTextSize(13f);
        right.setTextColor(th.textMuted);
        right.setSingleLine(true);
        right.setPadding(th.dp(12), 0, 0, 0);
        row.addView(right);
        right.setText(value);
        return row;
    }

    /** 版本 key → 宿主当前的版本条目（含自定义名 / 版本号）。 */
    private static Map<String, ItemInfo> versionItems(PluginApi api) {
        Map<String, ItemInfo> out = new HashMap<String, ItemInfo>();
        try {
            for (ItemInfo it : api.versions()) {
                if (it == null) continue;
                String key = it.extra == null ? null : it.extra.get("key");
                if (key == null || key.isEmpty() || out.containsKey(key)) continue;
                out.put(key, it);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 一行的显示名：**当前版本**用现查的显示名；**老版本行**用账本里的快照。
     *
     * <p>已装游戏原地更新后，同一个 key 会有两行（旧版本 / 新版本）—— 只有 tag 与当前
     * displayVersion 相同（或旧数据没有 tag）时才用现查的 title，否则用写账本时记下的 title，
     * 这样"更新前"那一行不会顶着新版本的名字。再退一步：tag → key / 文件名。
     */
    private static String versionLabel(Context c, Map<String, ItemInfo> items, Playtime.Entry e) {
        String key = e.key;
        if (key == null || key.isEmpty() || Playtime.UNKNOWN_VERSION.equals(key)) {
            return c.getString(R.string.us_unknown_version);
        }
        ItemInfo it = items == null ? null : items.get(key);
        if (it != null && (e.tag.isEmpty() || e.tag.equals(tagOf(it)))) {
            if (it.title != null && !it.title.trim().isEmpty()) return it.title;
        }
        if (e.title != null && !e.title.trim().isEmpty()) return e.title.trim();
        if (!e.tag.isEmpty()) return e.tag;
        return key.startsWith("import:") ? key.substring("import:".length()) : key;
    }

    /** 宿主当前条目的版本号（拿不到 ⇒ 空；旧宿主没有这个 extra）。 */
    private static String tagOf(ItemInfo it) {
        String t = it == null || it.extra == null ? null : it.extra.get("displayVersion");
        return t == null ? "" : t;
    }

    private static String duration(Context c, long ms) {
        int[] hm = Playtime.hm(ms);
        if (hm[0] > 0) return c.getString(R.string.us_hm_fmt, hm[0], hm[1]);
        if (hm[1] > 0) return c.getString(R.string.us_min_fmt, hm[1]);
        return c.getString(R.string.us_sec);
    }

    private static String read(File f) {
        if (f == null || !f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) break;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private UsageStats() {}
}
