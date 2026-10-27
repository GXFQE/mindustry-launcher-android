package io.mdt.plugins.mapstats;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.mapstats.core.MapStats;
import io.mdt.plugins.mapstats.core.MsavTiles;
import io.mdt.plugins.mapstats.core.StatsPick;

/**
 * 第 281 轮：**按统计排序 / 筛选**（maps 页的 `sort` / `filter` 注入）的取数 + 缓存。
 *
 * <h3>为什么必须缓存</h3>
 * 宿主对排序会调 {@code compare()} O(n log n) 次、对筛选每一项调一次 {@code accept()} ——
 * 每调一次都重新解一张图的话，114 张图会被解上千遍。这里按**身份**缓存：
 * 一次选择里每张图只算一次；算过的写盘，下次换槽/没换模组时直接用。
 *
 * <h3>身份与失效</h3>
 * 身份 = `path`，包内条目 = `容器绝对路径!/条目`（与宿主 {@code PluginPicks.idOf} 同一条规则 ——
 * 插件拿到的 {@link ItemInfo} 就是宿主列表那一份，差一个字就会"一张都对不上"）。
 * 缓存文件按**槽 + 模组戳**分名（统计依赖本槽启用的模组：同一个包开/关，可采与可建都会变），
 * 每条再带 `size` + `mtime` ⇒ 图被换掉/重新导入时那一条自然失效。
 *
 * <p>⚠️ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。</p>
 */
final class StatsIndex {

    /** 缓存格式：改了统计口径（或 {@link StatsPick.Snap} 的字段）就抬这个数 ⇒ 老缓存整份作废。
     *  ★ 第 282 轮 1 → 2：`ores` 从"有没有"改成"每种矿各自多少格"。 */
    private static final int FMT = 2;
    private static final String PREFIX = "pick-";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static boolean inited;
    private static MapStats.Table table;
    private static MapStats.Names names;
    private static File cacheFile;
    private static String slot = "";
    private static String fp = "";

    private static final Map<String, StatsPick.Snap> MEM = new HashMap<String, StatsPick.Snap>();
    private static final Map<String, long[]> KEYS = new HashMap<String, long[]>();

    private StatsIndex() { }

    /** 一张图的快照（读不出来 ⇒ `ok=false`；**不抛** —— 排序/筛选里一个坏文件不该让整页失败）。 */
    static synchronized StatsPick.Snap of(ItemInfo it, PluginApi api) {
        if (it == null) return new StatsPick.Snap();
        if (!inited) init(api);
        String id = idOf(it);
        StatsPick.Snap hit = MEM.get(id);
        long[] key = KEYS.get(id);
        if (hit != null && key != null && key[0] == it.size && key[1] == it.modified) return hit;
        StatsPick.Snap s = compute(it, api);
        MEM.put(id, s);
        KEYS.put(id, new long[]{ it.size, it.modified });
        append(id, it, s);
        return s;
    }

    /** 与宿主 `PluginPicks.idOf` **逐字相同**（见类注释）。 */
    static String idOf(ItemInfo it) {
        if (it == null) return "";
        if (it.path != null && !it.path.isEmpty()) return it.path;
        if (it.container != null && it.entry != null && !it.entry.isEmpty()) {
            return it.container + "!/" + it.entry;
        }
        return "";
    }

    /**
     * 第 282 轮：给宿主那张"先选矿"的卡备选项（走有效内容表 ⇒ 模组加的矿自然出现，
     * 不写死清单；名字用物品名，与统计四处同一个口径）。
     */
    static StatsPick.OreOptions oreOptions(PluginApi api) {
        if (!inited) init(api);
        return StatsPick.oreOptions(table, names);
    }

    // ---- 初始化 ------------------------------------------------------------------

    private static void init(PluginApi api) {
        inited = true;
        Context c = api.context();
        try {
            MapStatsPage.Prepped prep = MapStatsPage.prepare(api, c);
            table = prep.table;
            names = prep.names;
        } catch (Throwable t) {
            // 备不出来就按原版表 + 内部名算（数与详情页可能差一点，但页面照常出结果）
            table = MapStats.copyOf(MapStats.vanilla());
            names = MapStatsPage.rawNames();
        }
        slot = api.slot() == null ? "" : api.slot();
        fp = fingerprint(api);
        String name = PREFIX + Integer.toHexString(fp.hashCode()) + ".tsv";
        File dir = api.dataDir();
        cacheFile = new File(dir, name);
        cleanSiblings(dir);
        load();
    }

    /** 统计依赖的东西：槽 + 「这次加载不加载模组」+ 每个模组的路径/大小/修改时间/启用状态。 */
    private static String fingerprint(PluginApi api) {
        StringBuilder sb = new StringBuilder();
        sb.append("fmt=").append(FMT).append(';').append(api.slot());
        try {
            sb.append(";skip=").append(api.modsSkipped());
        } catch (Throwable ignored) {
        }
        try {
            for (ItemInfo m : api.contents(ItemInfo.KIND_MODS)) {
                if (m == null) continue;
                sb.append('|').append(m.path).append(':').append(m.size).append(':')
                        .append(m.modified).append(':').append(m.extra.get("enabled"));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 只留当前这一份缓存（换槽/换模组会产生新文件；老的不清会一直涨）。 */
    private static void cleanSiblings(File dir) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            if (!f.getName().startsWith(PREFIX)) continue;
            if (cacheFile != null && f.getName().equals(cacheFile.getName())) continue;
            try { f.delete(); } catch (Throwable ignored) { }
        }
    }

    // ---- 计算 --------------------------------------------------------------------

    private static StatsPick.Snap compute(ItemInfo it, PluginApi api) {
        if (!it.readable) return new StatsPick.Snap();
        InputStream in = null;
        try {
            in = api.open(it);
            MsavTiles.Tiles t = MsavTiles.read(in, false);
            if (t == null || !t.ok()) return new StatsPick.Snap();
            String planet = it.extra.get("planet");
            MapStats.Result r = MapStats.analyze(t, table, names, planet);
            return StatsPick.snap(r);
        } catch (Throwable t) {
            return new StatsPick.Snap();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    // ---- 磁盘缓存（一行一张图；最后一行胜） ---------------------------------------

    private static void load() {
        if (cacheFile == null || !cacheFile.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\t");
                if (p.length < 8) continue;
                try {
                    StatsPick.Snap s = new StatsPick.Snap();
                    s.ok = "1".equals(p[3]);
                    s.reach = Integer.parseInt(p[4]);
                    s.build = Integer.parseInt(p[5]);
                    s.zone = Integer.parseInt(p[6]);
                    s.ores = p[7];
                    MEM.put(p[0], s);
                    KEYS.put(p[0], new long[]{ Long.parseLong(p[1]), Long.parseLong(p[2]) });
                } catch (Throwable ignored) {
                    // 一行坏了不影响别的（缓存只是加速，不是事实来源）
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
    }

    private static void append(String id, ItemInfo it, StatsPick.Snap s) {
        if (cacheFile == null || id.isEmpty()) return;
        BufferedWriter w = null;
        try {
            boolean fresh = !cacheFile.isFile();
            w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(cacheFile, true), UTF8));
            if (fresh) w.write("#fmt=" + FMT + " slot=" + slot + " fp=" + fp + "\n");
            w.write(id);
            w.write('\t');
            w.write(Long.toString(it.size));
            w.write('\t');
            w.write(Long.toString(it.modified));
            w.write('\t');
            w.write(s.ok ? "1" : "0");
            w.write('\t');
            w.write(Integer.toString(s.reach));
            w.write('\t');
            w.write(Integer.toString(s.build));
            w.write('\t');
            w.write(Integer.toString(s.zone));
            w.write('\t');
            w.write(s.ores == null ? "," : s.ores);
            w.write('\n');
        } catch (Throwable ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) { }
        }
    }
}
