package io.mdt.plugins.usagestats;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.usagestats.core.Heat;
import io.mdt.plugins.usagestats.core.Playtime;
import io.mdt.plugins.usagestats.core.Sessions;

/**
 * 游玩时长（官方插件，v32）。
 *
 * <p>两个落点，**都在宿主自己的页面里**（插件页不再放入口）：
 * <ul>
 *   <li>`slot: section` + `page: slot` ⇒ 槽页信息卡下面一张卡：本槽合计 + 按游戏版本分列
 *       （已装游戏原地更新后，更新前后是**两行**）；</li>
 *   <li>`playtimeTotal`（plugin.json）⇒ `total.txt`，宿主把它排版进**主界面摘要卡**。</li>
 * </ul>
 * 这个类同时是插件自述里的默认入口：从插件页长按「打开」时，页面显示总计 + 按槽分列。
 *
 * <p>★ 2026-11-17（第 384 轮）：**不再用 in-process 探针计时**。宿主在 `:game` 里用
 * `SessionClock` 记这一局（前台累计 `activeMs`），退出时通过 `game-exit` 事件交一次；
 * 这里只负责把 `activeMs` 追加进账本。少一层 `:game` 里的插件代码（官方内置插件崩了
 * 就是游戏崩，崩溃报告还会标「有插件参与」）。
 */
public final class UsageStats {

    public static int api() {
        // 2026-11-17（第 385 轮）：本插件依赖宿主 44 的 `game-exit` 观测键
        // （started / ended / activeMs / wallMs / key）——报 44 让旧宿主明确拒掉，
        // 而不是收不到键、静默不记时长。
        return 44;
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
            if (!e.patch.isEmpty()) {
                // ★ 第 388 轮：本体 + 补丁 ≠ 本体（用户：「应该算是半个改端」）——
                //   账本按补丁身份分行，行名不能再叠一句「本体+补丁」：宿主标题里已经有
                //   「原端 / 改端」类型词，把它换成「半改端」，补丁名只用短形式（去掉
                //   「(原版 + 补丁)」这类解释）。
                name = patchedLabel(c, name, e);
            } else if (slotPage) {
                name = prettyName(name);
            }
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

        // ── 第 397 轮：近 52 周热力图（列 = 周，行 = 星期一~日；默认收起） ──────────
        // 为什么按槽过滤：这张卡在槽页上是「本槽」的（与上面的合计同口径）；
        // 传 null = 插件页的"总计"，显示所有槽。
        final Heat.Grid heat = heatOf(api, slotPage ? slot : null);
        if (heat != null && !heat.firstDay.isEmpty()) {
            final TextView heatToggle = new TextView(c);
            heatToggle.setTextSize(12f);
            heatToggle.setTextColor(th.accent);
            heatToggle.setPadding(0, th.dp(8), 0, 0);
            heatToggle.setText(c.getString(R.string.us_heat_show));

            final LinearLayout heatBox = new LinearLayout(c);
            heatBox.setOrientation(LinearLayout.VERTICAL);
            heatBox.setVisibility(View.GONE);
            heatBox.addView(new HeatView(c, th, heat), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView heatSum = new TextView(c);
            heatSum.setTextSize(12f);
            heatSum.setTextColor(th.textMuted);
            heatSum.setPadding(0, th.dp(6), 0, 0);
            // 图旁的数字与格子同源（Heat.total 只把 known 的格子加起来）；起点单独说，
            // 别让「近 52 周」和上面的「本槽累计」对撞成"图里怎么少了"。
            heatSum.setText(c.getString(R.string.us_heat_total_fmt, duration(c, Heat.total(heat)))
                    + " · " + c.getString(R.string.us_heat_since_fmt, heat.firstDay));
            heatBox.addView(heatSum);

            heatToggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean show = heatBox.getVisibility() != View.VISIBLE;
                    heatBox.setVisibility(show ? View.VISIBLE : View.GONE);
                    heatToggle.setText(c.getString(show ? R.string.us_heat_hide : R.string.us_heat_show));
                }
            });
            box.addView(heatToggle);
            box.addView(heatBox);
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

    /**
     * 统一行名分隔符：宿主标题是「名称  原端  版本」两个空格，这里换成「名称 · 原端 · 版本」。
     * 所有行共用，避免标题用空格、补丁后缀用 `·` 的混用。
     */
    private static String prettyName(String name) {
        return joinParts(splitName(name));
    }

    private static String[] splitName(String name) {
        String s = name == null ? "" : name.trim();
        return s.isEmpty() ? new String[0] : s.split(" {2,}");
    }

    private static String joinParts(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    /**
     * 带补丁行的显示名：把标题里的「原端 / 改端」换成「半改端」，再缀一个**短**补丁名。
     *
     * <p>为什么不直接 `name + " · 本体+补丁（...）"`：宿主标题已经写了类型，再叠一句
     * 「本体+补丁」既重复又长（真机被 `ellipsize=END` 截断）；这里只留「半改端 + 短补丁名」。
     */
    private static String patchedLabel(Context c, String name, Playtime.Entry e) {
        String half = c.getString(R.string.us_patch_half);
        String[] parts = splitName(name);
        String base;
        if (parts.length >= 3) {
            parts[1] = half;
            base = joinParts(parts);
        } else if (parts.length == 2) {
            base = parts[0] + " · " + half + " · " + parts[1];
        } else if (parts.length == 1) {
            base = parts[0] + " · " + half;
        } else {
            base = half;
        }
        int count = patchCount(e.patch);
        String what = shortPatch(c, e, count);
        if (what.isEmpty()) return base;
        return c.getString(R.string.us_patch_row_fmt, base, what);
    }

    private static int patchCount(String refs) {
        if (refs == null || refs.trim().isEmpty()) return 0;
        int n = 0;
        for (String s : refs.split(",")) if (!s.trim().isEmpty()) n++;
        return n;
    }

    private static String shortPatch(Context c, Playtime.Entry e, int count) {
        // 补丁的名字用 **ref 的 id**（`mdtx-1607`）—— manifest 的 name 是
        // 「MindustryX (原版 + 补丁)」，那是游戏/改端的描述，不是补丁名；
        // 直接截成 MindustryX 会被误认成"补丁叫 MindustryX"。
        String id = refId(firstRef(e.patch));
        if (count > 1) {
            return id.isEmpty() ? "" : c.getString(R.string.us_patch_more_fmt, id, count - 1);
        }
        if (!id.isEmpty()) return id;
        String label = e.patchLabel == null ? "" : e.patchLabel.trim();
        return label.replaceAll("\\s*[\\(（][^()（）]*[\\)）]", "").trim();
    }

    private static String firstRef(String refs) {
        if (refs == null) return "";
        for (String s : refs.split(",")) if (!s.trim().isEmpty()) return s.trim();
        return "";
    }

    private static String refId(String ref) {
        int at = ref == null ? -1 : ref.indexOf('@');
        return at > 0 ? ref.substring(0, at) : (ref == null ? "" : ref);
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

    /**
     * 第 397 轮：每局流水 ⇒ 近 52 周网格。
     *
     * @param slot 非空 = 只算这个槽（槽页）；null = 所有槽（插件页总计）
     * @return null = 还没有流水（不画空图，别让用户以为坏了）
     */
    private static Heat.Grid heatOf(PluginApi api, String slot) {
        File dir = api == null ? null : api.dataDir();
        if (dir == null) return null;
        List<Sessions.Entry> all = Sessions.parse(readText(new File(dir, "sessions.tsv"), 1024 * 1024));
        if (all.isEmpty()) return null;
        List<Sessions.Entry> mine = new ArrayList<Sessions.Entry>();
        for (Sessions.Entry e : all) {
            if (e == null) continue;
            if (slot == null || e.slot.equals(slot)) mine.add(e);
        }
        if (mine.isEmpty()) return null;
        TimeZone tz = TimeZone.getDefault();
        return Heat.grid(Sessions.byDay(mine, tz), System.currentTimeMillis(), tz);
    }

    /** 读文件（超过 max 字节只读尾部，并丢掉可能被截断的第一行）。 */
    private static String readText(File f, int max) {
        if (f == null || !f.isFile()) return "";
        long len = f.length();
        boolean cut = len > max;
        long skip = cut ? len - max : 0L;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            while (skip > 0L) {
                long n = in.skip(skip);
                if (n <= 0L) break;
                skip -= n;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), "UTF-8");
            if (cut) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 2026-11-17：`game-exit` 事件（宿主时钟算好的一局）⇒ 追加进账本。
     *
     * <p>取数：`activeMs`（本局前台累计）+ `key`（版本身份）；版本号 / 显示名 / 原始版本名
     * 从宿主 {@link PluginApi#versions()} 的 extra 取 —— 与旧探针写账本时的快照同源。
     * 没有 `activeMs`（启动即崩 / 宿主没量到）⇒ 什么都不做，别记一笔 0。
     *
     * <p>⚠️ 一个 `game-exit` 只加一次：宿主在派发后就把这局从 `sSessions` / `event-pending`
     * 里清掉了；这里不再做去重（重复派发 = 宿主 bug）。
     */
    public static void onEvent(PluginApi api, String event, Map<String, String> data) {
        if (api == null || data == null || !"game-exit".equals(event)) return;
        long active = parseLong(data.get("activeMs"));
        if (active <= 0L) return;
        String slot = clean(data.get("slot"));
        if (slot.isEmpty()) slot = clean(api.slot());
        String key = clean(data.get("key"));
        File dir = api.dataDir();
        if (slot.isEmpty() || key.isEmpty() || dir == null) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String ledger = read(new File(dir, "ledger.tsv"));
            Playtime.Snap snap = snapOf(api, key);
            String patch = clean(data.get("patch"));
            String patchLabel = clean(data.get("patchLabel"));
            if (!patch.isEmpty()) {
                snap = new Playtime.Snap(snap.tag, snap.title, snap.rawName, snap.upstream,
                        patch, patchLabel);
            }
            long base = Playtime.valueFor(ledger, slot, key, snap.tag, snap.patch);
            String next = Playtime.put(ledger, slot, key, snap, base + active,
                    System.currentTimeMillis());
            if (next.equals(ledger)) return;       // 没写进去（坏 key / 空槽）⇒ 别动文件
            writeAtomic(new File(dir, "ledger.tsv"), next);
            writeAtomic(new File(dir, "total.txt"), String.valueOf(Playtime.total(next)));
            // 第 396 轮：每局流水（「哪一天」+ 具体时刻；按天 / 单日分析从它现算）。
            // 单独 try：流水写失败不许影响账本 —— 时长总数是主数据，流水是分析用的副本。
            try {
                appendSession(dir, data, slot, key, snap, active);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // 插件写失败不许影响宿主 / 游戏：宿主那边也只记日志
        }
    }

    /**
     * 第 396 轮：把这一局追加进流水（`sessions.tsv`，一行一局）。
     *
     * <p>为什么记「一局」而不是「一天一行」：日 / 小时桶都能从起止时刻现算，反过来不行 ——
     * 只记「每天一把毫秒」就再也算不出单日几点在玩。文件每局一行，一年也就几十 KB。
     *
     * <p>去重：**最后一行的核心字段与本次完全一致** ⇒ 视为宿主重复派发，跳过（不记两次）。
     *
     * @param active 这一局的前台毫秒（宿主时钟给的）
     */
    private static void appendSession(File dir, Map<String, String> data, String slot,
                                      String key, Playtime.Snap snap, long active) {
        try {
            long started = parseLong(data.get("started"));
            if (started <= 0L) return;            // 没有开始时刻 ⇒ 归不到「哪一天」，别编一个
            long ended = parseLong(data.get("ended"));
            if (ended < started) ended = started;
            Sessions.Entry e = new Sessions.Entry(started, ended, active, ended - started,
                    clean(data.get("exit")), slot, key, snap.tag, snap.patch);
            File f = new File(dir, "sessions.tsv");
            String last = lastLine(f);
            if (last != null) {
                List<Sessions.Entry> one = Sessions.parse(last);
                if (one.size() == 1 && Sessions.sameRun(one.get(0), e)) return;
            }
            FileOutputStream os = new FileOutputStream(f, true);
            try {
                os.write(Sessions.format(e).getBytes("UTF-8"));
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 流水是分析用的副本：写失败不影响账本 / 游戏
        }
    }

    /** 读文件最后一行（只读尾部 1 KB；文件没有结尾换行也认）。没有 ⇒ null。 */
    private static String lastLine(File f) {
        if (f == null || !f.isFile()) return null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0L) return null;
            int take = (int) Math.min(len, 1024L);
            raf.seek(len - take);
            byte[] buf = new byte[take];
            int n = 0;
            while (n < take) {
                int r = raf.read(buf, n, take - n);
                if (r < 0) break;
                n += r;
            }
            String s = new String(buf, 0, n, "UTF-8");
            while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
            int nl = s.lastIndexOf('\n');
            return nl < 0 ? s : s.substring(nl + 1);
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 版本 key → 账本行要的快照（宿主 `versions()` 的 extra；找不到就只写 key）。 */
    private static Playtime.Snap snapOf(PluginApi api, String key) {
        try {
            for (ItemInfo it : api.versions()) {
                if (it == null || it.extra == null) continue;
                if (!key.equals(it.extra.get("key"))) continue;
                return new Playtime.Snap(it.extra.get("displayVersion"), it.title,
                        it.extra.get("versionName"), it.extra.get("upstreamVersion"));
            }
        } catch (Throwable ignored) {
        }
        return new Playtime.Snap("", "", "", "");
    }

    /** 临时文件 + 改名（读到的永远是完整文件；与旧探针同一套）。 */
    private static void writeAtomic(File f, String text) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(text.getBytes("UTF-8"));
            os.flush();
            os.close();
            os = null;
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) tmp.delete();
            }
        } catch (Throwable t) {
            if (os != null) {
                try { os.close(); } catch (Throwable ignored) { }
            }
            tmp.delete();
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private UsageStats() {}
}
