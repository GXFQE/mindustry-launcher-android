package io.mdt.plugins.usagestats;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.usagestats.core.Playtime;

/**
 * 游玩时长（官方插件，v30）。
 *
 * <p>两个落点，**都在宿主自己的页面里**（插件页不再放入口）：
 * <ul>
 *   <li>`slot: section` + `page: slot` ⇒ 槽页底部一张卡：本槽合计 + 按游戏版本分列；</li>
 *   <li>`playtimeTotal`（plugin.json）⇒ 探针写的 `total.txt`，宿主把它排版进**主界面摘要卡**。</li>
 * </ul>
 * 这个类同时是插件自述里的默认入口：从插件页长按「打开」时，页面显示总计 + 按槽分列。
 */
public final class UsageStats {

    public static int api() {
        return 30;
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
        Map<String, String> names = slotPage ? versionNames(api) : null;
        for (Playtime.Entry e : rows) {
            String name = slotPage ? versionLabel(c, names, e.version) : e.slot;
            box.addView(row(c, th, name, duration(c, e.ms)));
        }

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

    /** 版本 key → 主界面那份显示名（含自定义名）。拿不到就回落 key / 文件名。 */
    private static Map<String, String> versionNames(PluginApi api) {
        Map<String, String> out = new HashMap<String, String>();
        try {
            for (ItemInfo it : api.versions()) {
                if (it == null) continue;
                String key = it.extra == null ? null : it.extra.get("key");
                if (key == null || key.isEmpty() || out.containsKey(key)) continue;
                out.put(key, it.title);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String versionLabel(Context c, Map<String, String> names, String key) {
        if (key == null || key.isEmpty() || Playtime.UNKNOWN_VERSION.equals(key)) {
            return c.getString(R.string.us_unknown_version);
        }
        String name = names == null ? null : names.get(key);
        if (name != null && !name.trim().isEmpty()) return name;
        return key.startsWith("import:") ? key.substring("import:".length()) : key;
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
