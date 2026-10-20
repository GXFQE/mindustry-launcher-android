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

    /** 接口版本：v8（v7 的"做事"层 + `modsSkipped()`）。 */
    public static int api() {
        return 8;
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
        final Context c = api.context();
        final PluginTheme th = api.theme();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.background);

        final TextView title = new TextView(c);
        title.setText(c.getString(R.string.ms_title));
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(th.text);
        root.addView(title);

        final TextView hint = new TextView(c);
        hint.setText(c.getString(R.string.ms_hint));
        hint.setTextSize(13f);
        hint.setTextColor(th.textMuted);
        hint.setPadding(0, th.dp(6), 0, 0);
        root.addView(hint);

        final TextView status = new TextView(c);
        status.setText(c.getString(R.string.ms_nothing));
        status.setTextSize(13f);
        status.setTextColor(th.textMuted);
        status.setPadding(0, th.dp(10), 0, 0);
        root.addView(status);

        final LinearLayout results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        final List<Built> built = new ArrayList<>();

        addButton(root, c, th, c.getString(R.string.ms_pick), new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.pick(ItemInfo.KIND_MAPS, new PickCallback() {
                    @Override public void onPicked(List<ItemInfo> items) {
                        if (items == null || items.isEmpty()) {
                            api.toast(c.getString(R.string.ms_pick_empty));
                            return;
                        }
                        run(api, c, th, status, results, built, items);
                    }
                });
            }
        });

        final View[] actions = new View[2];
        actions[0] = addButton(root, c, th, c.getString(R.string.ms_share), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String js = jsonOf(built, c);
                if (js == null) {
                    api.toast(c.getString(R.string.ms_nothing));
                    return;
                }
                api.shareText(fileName(built), js);
            }
        });
        actions[1] = addButton(root, c, th, c.getString(R.string.ms_export), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String js = jsonOf(built, c);
                if (js == null) {
                    api.toast(c.getString(R.string.ms_nothing));
                    return;
                }
                api.export(fileName(built), mimeOf(built),
                        new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)));
            }
        });
        setEnabled(actions, false);

        addButton(root, c, th, c.getString(R.string.ms_close), new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.closePage();
            }
        });

        // 有结果之后才让"分享 / 保存"可点（与宿主那条"没有结果就别给出口"一致）
        results.setTag(actions);
        return root;
    }

    /** 后台算、主线程画。 */
    private static void run(final PluginApi api, final Context c, final PluginTheme th,
                            final TextView status, final LinearLayout results,
                            final List<Built> built, final List<ItemInfo> items) {
        built.clear();
        results.removeAllViews();
        status.setText(c.getString(R.string.ms_busy_fmt, 1, items.size()));
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
                results.post(new Runnable() {
                    @Override public void run() {
                        built.clear();
                        built.addAll(out);
                        render(c, th, status, results, out);
                        View[] actions = actionsOf(results);
                        setEnabled(actions, jsonOf(built, c) != null);
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
                               LinearLayout results, List<Built> list) {
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
            head.setText(b.planet == null || b.planet.isEmpty()
                    ? c.getString(R.string.ms_map_head_fmt, b.name, r.width, r.height)
                    : c.getString(R.string.ms_map_head_planet_fmt, b.name, r.width, r.height,
                            b.planet));
            head.setTextSize(15f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(th.text);
            head.setPadding(0, th.dp(14), 0, 0);
            results.addView(head);

            section(results, c, th, c.getString(R.string.ms_sec_ores), r.ores);
            section(results, c, th, c.getString(R.string.ms_sec_walls), r.oreWalls);
            section(results, c, th, c.getString(R.string.ms_sec_floors), r.floors);
            section(results, c, th, c.getString(R.string.ms_sec_bonus), r.bonuses);

            addLine(results, c, th, c.getString(R.string.ms_buildable_fmt, r.buildable.count()),
                    th.text, 13f);
            if (!r.core.skip && r.core.zone > 0) {
                addLine(results, c, th,
                        c.getString(R.string.ms_core_fmt, r.core.usable, r.core.max, r.core.size),
                        th.text, 13f);
                // 依据（与宿主详情页同一句话）：按存档里的星球判 / 查不出星球按最小的算
                addLine(results, c, th, c.getString(
                                r.core.basis == MapStats.Core.BASIS_PLANET
                                        ? R.string.ms_core_note_planet_fmt
                                        : R.string.ms_core_note_default_fmt, r.core.size),
                        th.textMuted, 12f);
            }
            int unknown = r.unkOre + r.unkFloor + r.unkBonus;
            if (unknown > 0) {
                addLine(results, c, th, c.getString(R.string.ms_unknown_fmt, unknown), th.textMuted, 13f);
            }
        }
        status.setText(c.getString(R.string.ms_done_fmt, millis));
    }

    private static void section(LinearLayout box, Context c, PluginTheme th, String title,
                                List<MapStats.Row> rows) {
        if (rows == null || rows.isEmpty()) return;
        TextView h = new TextView(c);
        h.setText(title);
        h.setTextSize(14f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(th.accent);
        h.setPadding(0, th.dp(10), 0, 0);
        box.addView(h);
        for (MapStats.Row r : rows) {
            addLine(box, c, th, c.getString(R.string.ms_row_fmt, r.label, r.total), th.text, 13f);
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
    private static String jsonOf(List<Built> list, Context c) {
        List<MapStats.Result> rs = new ArrayList<>();
        List<MapStatsExport.Meta> ms = new ArrayList<>();
        String notice = c.getString(R.string.ms_export_notice_fmt, MapStatsExport.SITE);
        for (Built b : list) {
            if (b.result == null || !b.result.ok) continue;
            rs.add(b.result);
            ms.add(metaOf(b, c, notice));
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
    private static MapStatsExport.Meta metaOf(Built b, Context c, String notice) {
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
        m.mode = MapStatsExport.MODE_DETAIL;
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
