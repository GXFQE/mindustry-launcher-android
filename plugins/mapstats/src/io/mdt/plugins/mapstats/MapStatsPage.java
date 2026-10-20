package io.mdt.plugins.mapstats;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PickCallback;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.mapstats.core.BundleNames;
import io.mdt.plugins.mapstats.core.MapStats;
import io.mdt.plugins.mapstats.core.MapStatsExport;
import io.mdt.plugins.mapstats.core.MsavTiles;

/**
 * 官方插件 mapstats 的页面：地图资源统计（第 188 轮，从宿主的 F21 搬出来）。
 *
 * <p>宿主只按名字反射调 {@link #api()} + {@link #createView(PluginApi)}；编译期只依赖
 * {@code io.mdt.launcher.api} 那几个类（壳与插件各一份，运行时父加载器的赢）。
 *
 * <p>数据全走 v7 门面：{@code pick} 选图、{@code open} 读字节、{@code runBackground} 跑计数、
 * {@code progress} 报进度、{@code share} / {@code export} 出结果、{@code theme} 上色。
 * ⚠️ 本文件的字符串字面量一律 ASCII（与宿主同一条纪律）。
 */
public final class MapStatsPage {

    /** 一张卡片里最多列几行（与宿主详情页同一个数） */
    private static final int ROW_CAP = 15;

    /**
     * 接口版本：**v9**（v8 之上要 {@code selectedItem()} 与 `section` 插槽）。
     *
     * <p>为什么要 v9：统计要长回**地图详情页里**（与搬家前一样，点开地图就看见数字）。
     * v4 的 {@code selectedFile()} 只给得出槽内文件，游戏自带 / 模组自带的地图拿不到 ——
     * 所以用 v9 的「当前这一项」+ `section` 插槽，让宿主把这一整段画在详情页里。</p>
     */
    public static int api() {
        return 11;
    }

    /** 当前这一页的统计产物（页面重建就重新算）。 */
    private static final class Built {
        String name = "";
        MapStats.Result result;
        long millis;
        String error = "";
        /** 存档 `tags.rules` 里的 `planet:`（空 = 没写这个键 = 塞普罗） */
        String planet = "";
        /** 列表上那一行的名字 / 来源 / 位置（声明里用；槽内 = 文件名，包内 = `包!/条目`） */
        String mapName = "";
        String where = "";
        String source = "";
        long bytes = -1;
        /** 地图字节的 sha256（投喂失败 = 空串，不编） */
        String sha = "";
        /** 统计用的那套事实（每张图都要进声明） */
        String slot = "";
        String gameApk = "";
        String gameVersion = "";
        int tableRows = -1;
        int packs = -1;
        boolean modsSkipped;
        boolean modsFailed;
    }

    public static View createView(final PluginApi api) {
        // ★ 地图页进来 = **导出**（不是看统计）：拾取 → 问口径 → 直接写盘/分享 → 回上一页。
        //   统计只在详情页那块 section 里（用户 2026-10-21：「不要把导出和统计混一起」）。
        if (!"map-detail".equals(api.page())) return exportOnly(api);
        final Context c = api.context();
        final PluginTheme th = api.theme();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.background);
        // v9（第 193 轮）：从**地图详情页**加载 = 画在详情页里的一段（不是插件自己的整页）
        final boolean inline = "map-detail".equals(api.page());

        final TextView title = new TextView(c);
        title.setText(c.getString(R.string.ms_title));
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(th.text);
        if (!inline) root.addView(title);

        final TextView hint = new TextView(c);
        hint.setText(c.getString(R.string.ms_hint));
        hint.setTextSize(13f);
        hint.setTextColor(th.textMuted);
        hint.setPadding(0, th.dp(6), 0, 0);
        if (!inline) root.addView(hint);

        final TextView status = new TextView(c);
        status.setText(c.getString(inline ? R.string.ms_busy_inline : R.string.ms_nothing));
        status.setTextSize(13f);
        status.setTextColor(th.textMuted);
        status.setPadding(0, th.dp(10), 0, 0);

        final LinearLayout results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);

        final List<Built> built = new ArrayList<>();
        // 导出口径：详细（全部数据）/ 简洁（仅可采）
        final int[] mode = {MapStatsExport.MODE_DETAIL};
        final View[] modeViews = new View[2];

        // ★ 动作放**最上面**（§79）：选 20 张地图时按钮不会被结果卡片顶出屏幕
        if (!inline) addButton(root, c, th, c.getString(R.string.ms_pick), new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.pick(ItemInfo.KIND_MAPS, new PickCallback() {
                    @Override public void onPicked(List<ItemInfo> items) {
                        if (items == null || items.isEmpty()) {
                            api.toast(c.getString(R.string.ms_pick_empty));
                            return;
                        }
                        askExport(api, c, th, status, results, built, items, mode, modeViews);
                    }
                });
            }
        });

        if (!inline) {
            LinearLayout modeRow = new LinearLayout(c);
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            modeRow.setPadding(0, th.dp(8), 0, 0);
            root.addView(modeRow);
            for (int i = 0; i < 2; i++) {
                final int pick = i;
                TextView t = new TextView(c);
                t.setText(c.getString(i == 0 ? R.string.ms_mode_detail : R.string.ms_mode_brief));
                t.setTextSize(14f);
                t.setPadding(th.dp(12), th.dp(6), th.dp(12), th.dp(6));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(th.dp(8));
                modeRow.addView(t, lp);
                modeViews[i] = t;
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        mode[0] = pick == 0 ? MapStatsExport.MODE_DETAIL : MapStatsExport.MODE_BRIEF;
                        paintMode(modeViews, th, mode[0]);
                    }
                });
            }
            paintMode(modeViews, th, mode[0]);
        }

        final View[] actions = new View[2];
        if (!inline) actions[0] = addButton(root, c, th, c.getString(R.string.ms_share), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String js = jsonOf(built, c, mode[0]);
                if (js == null) {
                    api.toast(c.getString(R.string.ms_nothing));
                    return;
                }
                api.shareText(fileName(built), js);
            }
        });
        if (!inline) actions[1] = addButton(root, c, th, c.getString(R.string.ms_export), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String js = jsonOf(built, c, mode[0]);
                if (js == null) {
                    api.toast(c.getString(R.string.ms_nothing));
                    return;
                }
                api.export(fileName(built), mimeOf(built),
                        new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)));
            }
        });
        setEnabled(actions, false);

        if (!inline) addButton(root, c, th, c.getString(R.string.ms_close), new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.closePage();
            }
        });

        // 有结果之后才让"分享 / 保存"可点（与宿主那条"没有结果就别给出口"一致）
        results.setTag(actions);

        // 结果是"动作下面的一块"，多长都不影响按钮的位置
        root.addView(status);
        root.addView(results);

        // 详情页里的这一段：**自动**统计当前这一张（不用再点"选择地图"）
        if (inline) {
            final ItemInfo one = inlineItem(api);
            if (one != null) {
                final List<ItemInfo> only = new ArrayList<ItemInfo>();
                only.add(one);
                run(api, c, th, status, results, built, only, -1, false, false);
            } else {
                status.setText(c.getString(R.string.ms_nothing));
            }
        }
        return root;
    }

    /**
     * **导出**（地图页那条入口）：不画统计卡片、不放口径按钮 ——
     * 进来就开选择器 → 选完由宿主弹「怎么导出」→ 后台算 → 直接写盘 / 分享 → 回上一页。
     */
    private static View exportOnly(final PluginApi api) {
        final Context c = api.context();
        final PluginTheme th = api.theme();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.background);

        // 页面标题由宿主顶栏给（扩展声明的 title），这里只留一行状态
        final TextView status = new TextView(c);
        status.setText(c.getString(R.string.ms_exporting));
        status.setTextSize(15f);
        status.setTextColor(th.textMuted);
        status.setPadding(0, th.dp(8), 0, 0);
        root.addView(status);

        final LinearLayout sink = new LinearLayout(c);      // 结果不画（导出不需要看卡片）
        final List<Built> built = new ArrayList<>();

        // 页面挂上之后再开选择器（立刻开会撞着页面还没 attach）
        status.post(new Runnable() {
            @Override public void run() {
                api.pick(ItemInfo.KIND_MAPS, new PickCallback() {
                    @Override public void onPicked(List<ItemInfo> items) {
                        if (items == null || items.isEmpty()) {
                            api.toast(c.getString(R.string.ms_pick_empty));
                            api.closePage();
                            return;
                        }
                        askExport(api, c, th, status, sink, built, items,
                                new int[]{MapStatsExport.MODE_DETAIL}, new View[2]);
                    }
                });
            }
        });
        return root;
    }

    /** 详情页上「当前这一项」：v9 直给；老宿主退回按文件对 / 页面快照只有一个。 */
    private static ItemInfo inlineItem(PluginApi api) {
        try {
            ItemInfo one = api.selectedItem();
            if (one != null) return one;
        } catch (Throwable ignored) {
        }
        File sel;
        try {
            sel = api.selectedFile();
        } catch (Throwable t) {
            return null;
        }
        try {
            List<ItemInfo> all = api.pageItems();
            if (all != null) {
                if (sel == null && all.size() == 1) return all.get(0);
                for (ItemInfo it : all) {
                    if (it == null || it.path == null || sel == null) continue;
                    if (sel.getAbsolutePath().equals(it.path)) return it;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 选完地图 ⇒ **由宿主弹窗问一句「怎么导出」**（第 195 轮；用户 2026-10-21：
     * 「选择一堆会把按钮顶到下面 真的没法做弹窗吗」）。
     *
     * <p>取消 ⇒ 只出卡片、不导出（结果仍然能看）。</p>
     */
    private static void askExport(final PluginApi api, final Context c, final PluginTheme th,
                                  final TextView status, final LinearLayout results,
                                  final List<Built> built, final List<ItemInfo> items,
                                  final int[] mode, final View[] modeViews) {
        api.choose(c.getString(R.string.ms_export_ask), new String[]{
                c.getString(R.string.ms_opt_save_detail),
                c.getString(R.string.ms_opt_save_brief),
                c.getString(R.string.ms_opt_share_detail),
                c.getString(R.string.ms_opt_share_brief)}, new PluginApi.Choose() {
            @Override public void onChoose(int idx) {
                if (idx < 0) {
                    run(api, c, th, status, results, built, items, -1, false, false);
                    return;
                }
                boolean exportFlow = "maps".equals(api.page());
                mode[0] = (idx % 2 == 0)
                        ? MapStatsExport.MODE_DETAIL : MapStatsExport.MODE_BRIEF;
                paintMode(modeViews, th, mode[0]);
                run(api, c, th, status, results, built, items, mode[0], idx >= 2, exportFlow);
            }
        });
    }

    /** 后台算、主线程画。 */
    private static void run(final PluginApi api, final Context c, final PluginTheme th,
                            final TextView status, final LinearLayout results,
                            final List<Built> built, final List<ItemInfo> items,
                            final int exportMode, final boolean exportShare,
                            final boolean closeAfter) {
        built.clear();
        results.removeAllViews();
        final boolean inline = "map-detail".equals(api.page());
        status.setText(inline ? c.getString(R.string.ms_busy_inline)
                : c.getString(R.string.ms_busy_fmt, 1, items.size()));
        api.runBackground("mapstats", new Runnable() {
            @Override public void run() {
                final List<Built> out = new ArrayList<>();
                // 内容表与译名按"当前槽"备一次（原版表 ⨁ 已启用模组 + 版本 APK / 模组 bundle）
                Prepped prep = new Prepped();
                prep.table = MapStats.vanilla();
                prep.names = new RawNames();
                try {
                    prep = prepare(api, c);
                } catch (Throwable ignored) {
                    // 备不出来就退回原版表 + 内部名（页面照常出数）
                }
                final MapStats.Table table = prep.table;
                final MapStats.Names names = prep.names;
                final String prepApkName = prep.gameApk;
                final String prepApkVersion = prep.gameVersion;
                final int tableRows = prep.tableRows;
                final int packs = prep.packs;
                final boolean modsSkipped = prep.modsSkipped;
                final boolean modsFailed = prep.modsFailed;
                for (int i = 0; i < items.size(); i++) {
                    ItemInfo it = items.get(i);
                    api.progress(i + 1, items.size());
                    Built b = new Built();
                    b.name = it.title == null ? "map" : it.title;
                    b.mapName = it.title == null ? "" : it.title;
                    b.slot = api.slot() == null ? "" : api.slot();
                    b.where = whereOf(it);
                    b.source = sourceOf(it);
                    b.bytes = it.size > 0 ? it.size : -1;
                    b.planet = it.extra.get("planet") == null ? "" : it.extra.get("planet");
                    b.gameApk = prepApkName;
                    b.gameVersion = prepApkVersion;
                    b.tableRows = tableRows;
                    b.packs = packs;
                    b.modsSkipped = modsSkipped;
                    b.modsFailed = modsFailed;
                    InputStream in = null;
                    try {
                        in = api.open(it);
                        MsavTiles.Tiles t = MsavTiles.read(in, false);
                        if (t == null || !t.ok()) {
                            b.error = "unreadable";
                        } else {
                            b.result = MapStats.analyze(t, table, names,
                                    b.planet.isEmpty() ? null : b.planet);
                            b.millis = b.result.millis;
                        }
                    } catch (Throwable e) {
                        b.error = String.valueOf(e.getMessage());
                    } finally {
                        closeQuietly(in);
                    }
                    b.sha = shaOf(api, it);
                    out.add(b);
                }
                // ★ 必须挂在**已 attach 的 view** 上 post：导出页里 results 是不进界面的壳，
                //   对未 attach 的 view 调 post ⇒ 进它的 HandlerActionQueue，永远不执行（真机卡"正在统计"）
                status.post(new Runnable() {
                    @Override public void run() {
                        built.clear();
                        built.addAll(out);
                        render(c, th, status, results, out, inline);
                        View[] actions = actionsOf(results);
                        setEnabled(actions, jsonOf(built, c, MapStatsExport.MODE_DETAIL) != null);
                        if (exportMode >= 0) {
                            String js = jsonOf(built, c, exportMode);
                            if (js == null) {
                                api.toast(c.getString(R.string.ms_nothing));
                                if (closeAfter) api.closePage();
                            } else if (exportShare) {
                                api.shareText(fileName(built), js);
                                if (closeAfter) {
                                    api.toast(c.getString(R.string.ms_share_done,
                                            fileName(built)));
                                    status.postDelayed(new Runnable() {
                                        @Override public void run() { api.closePage(); }
                                    }, 600);
                                }
                            } else {
                                // v11：**用户真的存好了**再关页（早一步关会把这次保存取消掉）
                                api.export(fileName(built), mimeOf(built),
                                        new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)),
                                        new PluginApi.After() {
                                            @Override public void onAfter(boolean ok) {
                                                if (!closeAfter) return;
                                                if (ok) api.toast(c.getString(
                                                        R.string.ms_save_done, fileName(built)));
                                                api.closePage();
                                            }
                                        });
                            }
                        }
                    }
                });
            }
        });
    }

    /** 一次统计的"前置事实"：内容表 + 译名 + 这一轮游戏会不会加载模组（进声明）。 */
    private static final class Prepped {
        MapStats.Table table;
        MapStats.Names names;
        boolean modsSkipped;
        /** 模组那一层读不动（只按原版算） */
        boolean modsFailed;
        /** 参与叠加的模组包数 */
        int packs;
        /** 有效内容表行数 */
        int tableRows = -1;
        /** 统计用的版本 APK（文件名）与版本名 */
        String gameApk = "";
        String gameVersion = "";
    }

    /**
     * 备齐 {@link MapStats#analyze} 要的东西：有效内容表、译名、（可选的）模组事实。
     *
     * <p>模组那层**只用宿主给的数字**（`ItemInfo.extra` 的 `enabled` / `willFailJavaLoad` /
     * `rootPrefix`）与 `api.modsSkipped()`，判据仍只有宿主那一处 —— 插件不重算"哪些模组是活的"。
     * 译名与宿主 {@code MapStatsMods} + {@code BundleNames} 同源（同一份 `MapStats.readBundles`）。
     */
    private static Prepped prepare(PluginApi api, Context c) {
        Prepped out = new Prepped();
        out.table = MapStats.copyOf(MapStats.vanilla());
        File apk = versionApkOf(api);
        out.gameApk = apk == null ? "" : apk.getName();
        out.gameVersion = versionNameOf(api);
        List<MapStats.Pack> packs = new ArrayList<>();
        try {
            out.modsSkipped = api.modsSkipped();
        } catch (Throwable ignored) {
        }
        if (!out.modsSkipped) {
            List<ItemInfo> mods;
            try {
                mods = api.contents(ItemInfo.KIND_MODS);
            } catch (Throwable t) {
                mods = new ArrayList<>();
            }
            for (ItemInfo m : mods) {
                if (m == null || m.path == null) continue;
                if (!"true".equals(m.extra.get("enabled"))) continue;
                if ("true".equals(m.extra.get("willFailJavaLoad"))) continue;
                File f = new File(m.path);
                if (!f.exists()) continue;
                MapStats.Pack p = new MapStats.Pack();
                p.label = m.title;
                String internal = m.extra.get("internalName");
                p.internal = internal == null ? "" : internal;
                p.prefix = internal != null && !internal.isEmpty() ? internal : m.title;
                p.rootPrefix = m.extra.get("rootPrefix") == null ? "" : m.extra.get("rootPrefix");
                p.hasCode = "true".equals(m.extra.get("java"))
                        || "true".equals(m.extra.get("hasClassesDex"))
                        || "true".equals(m.extra.get("hasScripts"));
                p.hidden = "true".equals(m.extra.get("hidden"));
                if (f.isDirectory()) {
                    p.dir = f;
                } else {
                    p.pkg = f;
                }
                packs.add(p);
            }
            try {
                out.table = MapStats.copyOf(MapStats.vanilla());
                MapStats.overlayMods(out.table, packs);
                out.packs = packs.size();
            } catch (Throwable t) {
                out.modsFailed = true;
                out.packs = -1;
                out.table = MapStats.copyOf(MapStats.vanilla());
            }
        }
        out.tableRows = out.table.rowCount();
        MapStats.BundleLang lang = MapStats.bundleLang(localeSuffix(c));
        MapStats.Bundles bundles = MapStats.readBundles(packs, lang);
        out.names = new BundleNames(apk, bundles, attrLabels(c), lang,
                c.getString(R.string.ms_wall_name_fmt));
        return out;
    }

    /** **这个槽**指向的版本 APK 的版本名（没有就空串 —— 与 {@link #versionApkOf} 同一处判据）。 */
    private static String versionNameOf(PluginApi api) {
        String slot = api.slot();
        List<ItemInfo> versions;
        try {
            versions = api.versions();
        } catch (Throwable t) {
            return "";
        }
        for (ItemInfo v : versions) {
            if (v == null) continue;
            String s = v.extra.get("slot");
            if (slot == null || !slot.equals(s)) continue;
            String name = v.extra.get("versionName");
            return name == null ? "" : name;
        }
        return "";
    }

    /**
     * **这个槽**指向的版本 APK（没有就 null —— `BundleNames` 会退回模组译文 / 内部名）。
     *
     * <p>🔴 真机对照抓到的口径：**不许拿"随便哪个版本 APK"兜底**。槽没指定版本时
     * （界面写着「不是当前槽 · 还没指定版本」）宿主也只按模组译文 + 内部名显示
     * （`Ore Tungsten`），插件若拿别的版本的 bundle 顶上去会翻成「钨」，还会让
     * 「矿层墙矿」与「地矿」**撞名合并** —— 同一张图宿主 5 种、插件 3 种。
     * 判据与宿主 `MapStatsMods` 里 `Versions.slotFor(e)` 那一处保持一致。</p>
     */
    private static File versionApkOf(PluginApi api) {
        String slot = api.slot();
        List<ItemInfo> versions;
        try {
            versions = api.versions();
        } catch (Throwable t) {
            return null;
        }
        for (ItemInfo v : versions) {
            if (v == null || v.path == null || !v.readable) continue;
            String s = v.extra.get("slot");
            if (slot == null || !slot.equals(s)) continue;
            File f = new File(v.path);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** 属性词表（键 = 游戏 `Attribute` 的名字）。 */
    private static Map<String, String> attrLabels(Context c) {
        Map<String, String> m = new HashMap<>();
        m.put("water", c.getString(R.string.ms_attr_water));
        m.put("oil", c.getString(R.string.ms_attr_oil));
        m.put("heat", c.getString(R.string.ms_attr_heat));
        m.put("spores", c.getString(R.string.ms_attr_spores));
        m.put("steam", c.getString(R.string.ms_attr_steam));
        m.put("light", c.getString(R.string.ms_attr_light));
        m.put("sand", c.getString(R.string.ms_attr_sand));
        return m;
    }

    /** 界面语言 → bundle 后缀（`zh_CN` / `en` / `pt_BR`）。 */
    private static String localeSuffix(Context c) {
        try {
            Locale l = c.getResources().getConfiguration().getLocales().isEmpty()
                    ? Locale.getDefault()
                    : c.getResources().getConfiguration().getLocales().get(0);
            if (l == null) return "";
            String lang = l.getLanguage();
            if (lang == null || lang.isEmpty()) return "";
            String country = l.getCountry();
            return (country == null || country.isEmpty()) ? lang : (lang + "_" + country);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void render(Context c, PluginTheme th, TextView status,
                               LinearLayout results, List<Built> list, boolean inline) {
        results.removeAllViews();
        long millis = 0;
        for (Built b : list) {
            if (b.error != null && !b.error.isEmpty() && b.result == null) {
                addLine(results, c, th, c.getString(R.string.ms_failed_fmt, b.error), th.textMuted, 13f);
                continue;
            }
            MapStats.Result r = b.result;
            millis += b.millis;
            TextView head = new TextView(c);
            if (inline) head.setVisibility(View.GONE);
            head.setText(b.planet == null || b.planet.isEmpty()
                    ? c.getString(R.string.ms_map_head_fmt, b.name, r.width, r.height)
                    : c.getString(R.string.ms_map_head_planet_fmt, b.name, r.width, r.height, b.planet));
            head.setTextSize(15f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(th.text);
            head.setPadding(0, th.dp(14), 0, 0);
            results.addView(head);

            // 与宿主详情页同一套卡片：摘要常驻、明细点开
            addSection(results, c, th, c.getString(R.string.mss_section_ore), r.ores, false);
            addSection(results, c, th, c.getString(R.string.mss_section_orewall), r.oreWalls, true);
            addSection(results, c, th, c.getString(R.string.mss_section_floor), r.floors, false);
            addSection(results, c, th, c.getString(R.string.mss_section_bonus), r.bonuses, false);
            addBuild(results, c, th, r);
            addCore(results, c, th, r);
            addTech(results, c, th, b);
            int unknown = r.unkOre + r.unkFloor + r.unkBonus;
            if (unknown > 0) {
                addLine(results, c, th, c.getString(R.string.ms_unknown_fmt, unknown), th.textMuted, 13f);
            }
        }
        status.setText(c.getString(R.string.ms_done_fmt, millis));
    }

    /** 一张可折叠卡片（标题 + 摘要常驻，明细点开）—— 返回装明细的那一列 */
    private static LinearLayout card(LinearLayout box, Context c, PluginTheme th,
                                     String title, String summary) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(th.card);
        bg.setCornerRadius(th.dp(12));
        bg.setStroke(Math.max(1, th.dp(1)), th.cardStroke);
        card.setBackground(bg);
        card.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = th.dp(8);
        box.addView(card, lp);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(th.text);
        head.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView chev = new TextView(c);
        chev.setText("\u25B8");
        chev.setTextSize(14f);
        chev.setTextColor(th.textMuted);
        head.addView(chev);
        card.addView(head);

        TextView sum = new TextView(c);
        sum.setText(summary);
        sum.setTextSize(12f);
        sum.setTextColor(th.textMuted);
        sum.setPadding(0, th.dp(3), 0, 0);
        card.addView(sum);

        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        card.addView(body);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                chev.setText(show ? "\u25BE" : "\u25B8");
            }
        });
        return body;
    }

    private static void addSection(LinearLayout box, Context c, PluginTheme th, String title,
                                   List<MapStats.Row> rows, boolean wallKind) {
        if (rows == null || rows.isEmpty()) return;
        int total = MapStats.sum(rows, MapStats.SUM_TOTAL);
        int reach = MapStats.sum(rows, MapStats.SUM_REACH);
        String sum;
        if (wallKind) {
            sum = reach == total
                    ? c.getString(R.string.mss_sum_wall_fmt, rows.size(), num(total))
                    : c.getString(R.string.mss_sum_wall_split_fmt, rows.size(), num(total),
                            num(reach));
        } else if (reach == total) {
            sum = c.getString(R.string.mss_sum_same_fmt, rows.size(), num(total));
        } else {
            sum = c.getString(R.string.mss_sum_fmt, rows.size(), num(total), num(reach));
        }
        LinearLayout body = card(box, c, th, title, sum);
        int n = Math.min(rows.size(), ROW_CAP);
        for (int i = 0; i < n; i++) rowView(body, c, th, rows.get(i), wallKind);
        if (rows.size() > n) {
            addLine(body, c, th, c.getString(R.string.mss_more_fmt, rows.size() - n),
                    th.textMuted, 12f);
        }
    }

    /** 一行：名字 + 「能采 X 格」，副行说"总共多少 / 掉落什么 / 被什么挡住" */
    private static void rowView(LinearLayout body, Context c, PluginTheme th,
                                MapStats.Row row, boolean wallKind) {
        String bracket = row.attrs == null ? "" : row.attrs;
        if (row.liquid != null && !row.liquid.isEmpty()) {
            String liq = c.getString(R.string.mss_bonus_liquid_fmt, row.liquid);
            bracket = bracket.isEmpty() ? liq : liq + " · " + bracket;
        }
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, th.dp(8), 0, 0);
        TextView name = new TextView(c);
        name.setText(bracket.isEmpty()
                ? row.label : c.getString(R.string.mss_row_attr_fmt, row.label, bracket));
        name.setTextSize(13f);
        name.setTextColor(th.text);
        line.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView cnt = new TextView(c);
        cnt.setText(c.getString(R.string.mss_row_reach_fmt, num(row.reachable())));
        cnt.setTextSize(13f);
        cnt.setTextColor(th.text);
        line.addView(cnt);
        body.addView(line);

        StringBuilder sb = new StringBuilder();
        if (row.total != row.reachable()) {
            cat(sb, c.getString(R.string.mss_row_total_fmt, num(row.total)));
        }
        if (row.drop != null && !row.drop.isEmpty()) {
            cat(sb, c.getString(R.string.mss_row_drop_fmt, row.drop));
        }
        if (row.buried > 0) {
            cat(sb, wallKind ? c.getString(R.string.mss_row_walled_fmt, num(row.buried))
                    : c.getString(R.string.mss_row_buried_fmt, num(row.buried)));
        }
        if (row.priority > 0) {
            cat(sb, wallKind ? c.getString(R.string.mss_row_wall_priority_fmt, num(row.priority))
                    : c.getString(R.string.mss_row_floor_priority_fmt, num(row.priority)));
        }
        if (row.loose > 0) cat(sb, c.getString(R.string.mss_row_loose_fmt, num(row.loose)));
        if (row.unknown > 0) cat(sb, c.getString(R.string.mss_row_unknown_fmt, num(row.unknown)));
        if (sb.length() > 0) addLine(body, c, th, sb.toString(), th.textMuted, 12f);
    }

    private static void addBuild(LinearLayout box, Context c, PluginTheme th, MapStats.Result r) {
        MapStats.Buildable b = r.buildable;
        LinearLayout body = card(box, c, th, c.getString(R.string.mss_section_build),
                c.getString(R.string.mss_build_sum_fmt, num(b.count())));
        buildRow(body, c, th, c.getString(R.string.mss_build_row_free), b.free, null);
        buildRow(body, c, th, c.getString(R.string.mss_build_row_cleared), b.cleared, null);
        buildRow(body, c, th, c.getString(R.string.mss_build_row_walled), b.walled, null);
        int noFloor = b.deep + b.floorBlocked;
        if (noFloor > 0) {
            StringBuilder sub = new StringBuilder();
            if (b.deep > 0) {
                sub.append(c.getString(R.string.mss_build_sub_deep_fmt, num(b.deep)));
            }
            for (MapStats.Buildable.Kind k : b.floorKinds) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(c.getString(R.string.mss_build_sub_floor_fmt, k.label, num(k.tiles)));
            }
            buildRow(body, c, th, c.getString(R.string.mss_build_row_nofoor), noFloor,
                    sub.length() == 0 ? null : sub.toString());
        }
        if (b.unknown > 0) {
            buildRow(body, c, th, c.getString(R.string.mss_build_row_unknown), b.unknown, null);
        }
    }

    private static void buildRow(LinearLayout body, Context c, PluginTheme th, String title,
                                 int n, String sub) {
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, th.dp(8), 0, 0);
        TextView name = new TextView(c);
        name.setText(title);
        name.setTextSize(13f);
        name.setTextColor(th.text);
        line.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView cnt = new TextView(c);
        cnt.setText(c.getString(R.string.mss_build_count_fmt, num(n)));
        cnt.setTextSize(13f);
        cnt.setTextColor(th.text);
        line.addView(cnt);
        body.addView(line);
        if (sub != null && !sub.isEmpty()) addLine(body, c, th, sub, th.textMuted, 12f);
    }

    private static void addCore(LinearLayout box, Context c, PluginTheme th, MapStats.Result r) {
        MapStats.Core core = r.core;
        if (core.zone <= 0 || core.skip) return;
        LinearLayout body = card(box, c, th, c.getString(R.string.mss_section_core),
                c.getString(R.string.mss_core_sum_fmt, num(core.usable), num(core.max),
                        String.valueOf(core.size)));
        if (core.usable != core.zone) {
            buildRow(body, c, th, c.getString(R.string.mss_core_row_zone), core.zone, null);
        }
        addLine(body, c, th, c.getString(
                        core.basis == MapStats.Core.BASIS_PLANET
                                ? R.string.mss_core_note_planet_fmt
                                : R.string.mss_core_note_default_fmt, String.valueOf(core.size)),
                th.textMuted, 12f);
    }

    private static void addTech(LinearLayout box, Context c, PluginTheme th, Built b) {
        MapStats.Result r = b.result;
        LinearLayout body = card(box, c, th, c.getString(R.string.mss_section_tech),
                c.getString(R.string.mss_tech_size_fmt, r.width, r.height, num(r.cells)));
        List<String> notes = new ArrayList<>();
        for (MapStats.Note nt : r.notes) {
            String s = noteText(c, nt);
            if (!s.isEmpty()) notes.add(s);
        }
        addLine(body, c, th, c.getString(R.string.mss_tech_table_fmt, join(notes)), th.textMuted, 12f);
        addLine(body, c, th, c.getString(R.string.mss_tech_names_fmt,
                c.getString(R.string.mss_tech_names_value_fmt,
                        b.gameApk.isEmpty() ? "—" : b.gameApk, Math.max(b.packs, 0), b.tableRows)),
                th.textMuted, 12f);
        if (!r.blockers.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (MapStats.Row row : r.blockers) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(c.getString(R.string.mss_tech_count_fmt, row.label, row.total));
            }
            addLine(body, c, th, c.getString(R.string.mss_tech_blockers_fmt, sb.toString()),
                    th.textMuted, 12f);
        }
        StringBuilder unk = new StringBuilder();
        for (MapStats.Unk u : r.unknowns) {
            if (unk.length() > 0) unk.append(" · ");
            unk.append(c.getString(R.string.mss_tech_count_fmt, u.name, u.total()));
        }
        addLine(body, c, th, c.getString(R.string.mss_tech_unknown_fmt,
                        unk.length() == 0 ? c.getString(R.string.mss_tech_none) : unk.toString()),
                th.textMuted, 12f);
        addLine(body, c, th, c.getString(R.string.mss_tech_time_fmt, b.millis), th.textMuted, 12f);
    }

    private static String noteText(Context c, MapStats.Note nt) {
        if (nt == null) return "";
        switch (nt.code) {
            case MapStats.Note.VANILLA_TABLE:
                return c.getString(R.string.mss_note_vanilla_table_fmt, nt.n);
            case MapStats.Note.PATCH_CHANGED:
                return c.getString(R.string.mss_note_patch_changed_fmt, nt.n2, nt.n);
            case MapStats.Note.JSON_NAMES:
                return c.getString(R.string.mss_note_json_names_fmt, nt.n);
            case MapStats.Note.UNKNOWN_TYPE:
                return c.getString(R.string.mss_note_unknown_type_fmt, nt.n);
            default:
                return "";
        }
    }

    private static String num(int v) {
        return String.format(Locale.getDefault(), "%,d", v);
    }

    private static void cat(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s);
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String s : xs) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    /** 两个口径按钮：选中的那个描边 + 主色，另一个灰字（一眼看出现在是哪种） */
    private static void paintMode(View[] views, PluginTheme th, int mode) {
        for (int i = 0; i < views.length; i++) {
            if (!(views[i] instanceof TextView)) continue;
            TextView t = (TextView) views[i];
            boolean on = (i == 0) == (mode == MapStatsExport.MODE_DETAIL);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(on ? th.card : 0x00000000);
            bg.setCornerRadius(th.dp(14));
            bg.setStroke(Math.max(1, th.dp(1)), on ? th.accent : th.cardStroke);
            t.setBackground(bg);
            t.setTextColor(on ? th.accent : th.textMuted);
        }
    }

    private static void addLine(LinearLayout box, Context c, PluginTheme th, String text,
                                int color, float size) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, th.dp(4), 0, 0);
        box.addView(t);
    }

    private static TextView addButton(LinearLayout box, Context c, PluginTheme th, String label,
                                      View.OnClickListener l) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextSize(16f);
        btn.setTextColor(th.accent);
        btn.setPadding(0, th.dp(14), 0, 0);
        btn.setOnClickListener(l);
        box.addView(btn);
        return btn;
    }

    private static View[] actionsOf(View any) {
        Object tag = any.getTag();
        return tag instanceof View[] ? (View[]) tag : new View[0];
    }

    private static void setEnabled(View[] views, boolean on) {
        for (View v : views) {
            if (v != null) {
                v.setEnabled(on);
                v.setAlpha(on ? 1f : 0.4f);
            }
        }
    }

    // ---- 出口：JSONL（与宿主 `MapStatsExport` 同一份实现 = 同一个格式） ----------------

    /**
     * 一份或多份统计 → **与宿主相同的导出文本**。
     *
     * <p>口径照抄宿主 `MapsActivity`：**一张 = 一个 JSON 文档**（`jsonDoc`），
     * **多张 = JSONL**（`jsonlBatch`，一行一张图）—— 用的是插件里那份
     * {@link MapStatsExport}（从宿主整块拷来的纯 Java 类），插件不另写一套。</p>
     */
    private static String jsonOf(List<Built> list, Context c, int mode) {
        List<MapStats.Result> rs = new ArrayList<>();
        List<MapStatsExport.Meta> ms = new ArrayList<>();
        String notice = c.getString(R.string.ms_export_notice_fmt, MapStatsExport.SITE);
        for (Built b : list) {
            if (b.result == null || !b.result.ok) continue;
            rs.add(b.result);
            ms.add(metaOf(b, c, notice, mode));
        }
        if (rs.isEmpty()) return null;
        return rs.size() == 1
                ? MapStatsExport.jsonDoc(rs.get(0), ms.get(0))
                : MapStatsExport.jsonlBatch(rs, ms);
    }

    /** 建议文件名（照抄宿主 `StatsExporter.fileName` 的形状：`mdt-stats-<名>-<时间>.json[l]`）。 */
    private static String fileName(List<Built> list) {
        int n = 0;
        String first = "";
        for (Built b : list) {
            if (b.result == null || !b.result.ok) continue;
            n++;
            if (first.isEmpty()) first = b.mapName;
        }
        String base;
        if (n > 1) {
            base = (list.isEmpty() || list.get(0).slot.isEmpty() ? "maps" : list.get(0).slot)
                    + "-" + n + "maps";
        } else {
            base = first.isEmpty() ? "map" : first;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        base = safeName(base);
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        return "mdt-stats-" + (base.isEmpty() ? "" : base + "-") + ts + (n > 1 ? ".jsonl" : ".json");
    }

    /** mime 跟着文件类型走（宿主同一套：单张 `application/json` / 批量 `application/x-ndjson`）。 */
    private static String mimeOf(List<Built> list) {
        int n = 0;
        for (Built b : list) if (b.result != null && b.result.ok) n++;
        return n > 1 ? "application/x-ndjson" : "application/json";
    }

    /** 文件名安全化（去掉路径分隔符与控制字符、空格换连字符、截断）——与宿主同一口径。 */
    private static String safeName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char ch = s.charAt(i);
            if (ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' || ch == '"'
                    || ch == '<' || ch == '>' || ch == '|' || ch < 0x20) continue;
            b.append(ch == ' ' ? '-' : ch);
        }
        return b.toString();
    }

    /** 插件版的声明（字段口径照抄宿主 `StatsExporter.metaOf`；拿不到的就空着，别编）。 */
    private static MapStatsExport.Meta metaOf(Built b, Context c, String notice, int mode) {
        MapStatsExport.Meta m = new MapStatsExport.Meta();
        m.exportedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
                .format(new Date());
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            m.launcherVersion = pi.versionName == null ? "" : pi.versionName;
            m.launcherCode = pi.versionCode;
        } catch (Throwable ignored) {
        }
        m.launcherPackage = c.getPackageName();
        m.slot = b.slot;
        m.mapName = b.mapName;
        m.mapSource = b.source;
        m.mapWhere = b.where;
        m.mapBytes = b.bytes;
        m.mapSha256 = b.sha == null ? "" : b.sha;
        m.planet = b.planet == null ? "" : b.planet;
        m.gameApk = b.gameApk;
        m.gameVersion = b.gameVersion;
        m.tableRows = b.tableRows;
        m.mods = b.packs;
        m.modsSkipped = b.modsSkipped;
        m.modsFailed = b.modsFailed;
        m.notice = notice;
        m.mode = mode;
        return m;
    }

    /** 声明里「这张图在哪」：槽内 = 文件名，包内 = `容器名!/条目`（**不含本机路径**）。 */
    private static String whereOf(ItemInfo it) {
        if (it == null) return "";
        if (it.container != null && it.entry != null) {
            return new File(it.container).getName() + "!/" + it.entry;
        }
        return it.path == null ? "" : new File(it.path).getName();
    }

    /** 声明里的来源码（`slot` / `game` / `mod`，宿主 `StatsExporter` 的同一套词）。 */
    private static String sourceOf(ItemInfo it) {
        if (it == null) return "";
        if (it.container == null) return "slot";
        String n = new File(it.container).getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk") || n.contains("mindustry") || n.contains("base")) return "game";
        return "mod";
    }

    /** 地图字节的 sha256（**定位它最硬的证据**）；算不出来返回空串。 */
    private static String shaOf(PluginApi api, ItemInfo it) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = api.open(it);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    /** 没有译名表时的兜底：内部名 → 可读（`dark-metal` → `Dark Metal`）。 */
    private static final class RawNames implements MapStats.Names {
        @Override public String block(String internal) {
            return MapStats.pretty(internal);
        }

        @Override public String item(String internal) {
            return MapStats.pretty(internal);
        }

        @Override public String wallName(String base) {
            return base + " (wall)";
        }

        @Override public String attr(String key) {
            return MapStats.pretty(key);
        }

        @Override public String liquid(String internal) {
            return MapStats.pretty(internal);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    private MapStatsPage() {}
}
