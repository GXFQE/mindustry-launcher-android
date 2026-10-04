package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * F21 统计的「取数侧」：把纯 Java 的 {@link MapStats} 需要的三样东西凑齐。
 *
 * <pre>
 *   ① 整图瓦片        {@link MapLoad#decodeFull}（**不是预览小地图**）
 *   ② 有效内容定义表   原版表 ⨁ 本槽**已启用**模组的 content JSON ⨁ 地图自带数据补丁
 *   ③ 译名            该槽指向的版本 APK 的 bundle（UTF-8）
 * </pre>
 *
 * <h3>为什么模组那一层要缓存</h3>
 * 一个模组包动辄几百个 `content/**` JSON（VE 一个包就 300+），每次点开地图都重解一遍
 * 要几秒。⇒ **按槽缓存**「原版 ⨁ 模组」那张表，用"`mods/` 目录的条目数 + 修改时间"当戳
 * （模组增删/导入都会改这个戳）。地图自带的补丁**不进缓存**（每张图都不一样，且很便宜）。
 *
 * ⚠️ 只算**已启用**的模组：游戏里没启用的模组不注册内容；声明了 java 却没有 `classes.dex`
 *   的包会被游戏**整个跳过**（{@link Mods.Info#willFailJavaLoad}）⇒ 它的内容也不该算进来。
 * 若 `Scan.skipModLoading` 为真（游戏上次崩过、`modcrashdisable` 生效），游戏**一个模组都不加载**
 * ⇒ 这里同样只按原版算，并在依据里说明。
 */
public final class MapStatsMods {

    /** 一次统计的全套产物 */
    public static final class Built {
        public MapStats.Table table;
        public MapStats.Names names;
        public MapStats.Result result;
        public MsavTiles.Tiles tiles;
        /** 失败原因（人话；成功时为空） */
        public String error = "";
        /** 模组/补丁那一层的一句话依据（技术细节层显示） */
        public String modNote = "";
        /** 译名那一路的来源（技术细节层显示） */
        public String namesNote = "";
        /** 读表 + 统计的总耗时（毫秒） */
        public long millis;
    }

    private static final class Cached {
        String stamp = "";
        SlotContent content;
    }

    /** 一个槽的「内容定义表 + 模组译文」（两者同源、同戳，一起缓存） */
    public static final class SlotContent {
        public MapStats.Table table;
        /** 模组译文（**两层**：语言包 / 基础包）—— 版本 APK 里查不到的名字靠它翻 */
        public MapStats.Bundles bundle = new MapStats.Bundles();
        /** 这一层的一句话依据（技术细节层显示） */
        public String note = "";
        /** 带译文的模组包数 */
        public int bundlesWithText;
    }

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private MapStatsMods() {}

    /** 缓存键：槽名 + 槽目录（换槽不能拿错模组表） */
    private static String key(Context ctx, String slot) {
        File dir = Data.dirOf(ctx, slot);
        return slot + "@" + (dir == null ? "?" : dir.getAbsolutePath());
    }

    /** `mods/` 目录的"戳"：条目数 + 各自的修改时间（增删/导入都会变） */
    private static String stampOf(File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return "none";
        File[] fs = modsDir.listFiles();
        long t = modsDir.lastModified();
        long acc = 0;
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                n++;
                acc += f.lastModified();
            }
        }
        return n + "/" + acc + "/" + t;
    }

    /**
     * 「原版 ⨁ 本槽已启用模组」那张表 + 模组的译文（带缓存）。
     *
     * @param note 出参：这一层的一句话依据（"3 个模组（新增 12 条，覆盖 44 条）"之类）
     */
    public static synchronized SlotContent contentFor(Context ctx, String slot, StringBuilder note) {
        File modsDir = null;
        try {
            modsDir = Mods.scan(ctx, slot).modsDir;
        } catch (Throwable ignored) {
        }
        String k = key(ctx, slot);
        String stamp = stampOf(modsDir);
        Cached c = CACHE.get(k);
        if (c != null && c.stamp.equals(stamp)) {
            if (note != null) note.append(c.content.note);
            return c.content;
        }
        SlotContent out = new SlotContent();
        out.table = MapStats.copyOf(MapStats.vanilla());
        MapStats.Table t = out.table;
        String line;
        try {
            Mods.Scan scan = Mods.scan(ctx, slot);
            if (scan.skipModLoading) {
                line = "游戏这次不加载模组（上次崩过），按原版算";
            } else {
                List<MapStats.Pack> packs = new ArrayList<>();
                for (Mods.Info m : scan.mods) {
                    if (m == null || !m.enabled) continue;
                    if (m.willFailJavaLoad()) continue;      // 游戏整个跳过它 ⇒ 内容也不该算
                    MapStats.Pack p = new MapStats.Pack();
                    p.label = m.title();
                    p.prefix = m.name != null && !m.name.isEmpty() ? m.name : m.internalName;
                    p.internal = m.internalName == null ? "" : m.internalName;
                    if (p.prefix == null || p.prefix.isEmpty()) p.prefix = m.fileName;
                    if (m.directory) {
                        p.dir = m.rootDir != null ? m.rootDir : m.file;
                    } else {
                        p.pkg = m.file;
                        p.rootPrefix = m.rootPrefix == null ? "" : m.rootPrefix;
                    }
                    packs.add(p);
                }
                MapStats.ModResult mr = MapStats.overlayMods(t, packs);
                // ★ 译文：版本 APK 只认识原版内容，模组加的物品/方块得从**模组自己的 bundle** 里捞
                out.bundle = MapStats.readBundles(packs);
                for (MapStats.Pack p : packs) {
                    if (p.bundleKeys > 0) out.bundlesWithText++;
                }
                line = mr.packs + " 个模组（新增 " + mr.added + " 条，覆盖 " + mr.overridden + " 条"
                        + (out.bundlesWithText > 0
                        ? "；" + out.bundlesWithText + " 个带译文，共 " + out.bundle.keys() + " 条" : "")
                        + "）";
            }
        } catch (Throwable ex) {
            line = "模组读不动，只按原版算";
        }
        out.note = line;
        c = new Cached();
        c.stamp = stamp;
        c.content = out;
        CACHE.put(k, c);
        if (note != null) note.append(line);
        return out;
    }

    /** 槽被改动（换模组、导入包）后主动作废缓存 —— 自检与 dev 口用 */
    public static synchronized void invalidate() {
        CACHE.clear();
    }

    /**
     * 跑一次完整统计（**必须在后台线程调**）。
     *
     * @param attrLabels 属性词（`water` → 「含水」）—— 文案来自 `strings.xml`，核心里不写死
     */
    public static Built run(Context ctx, String slot, Maps.Item it, String apkPath,
                            Map<String, String> attrLabels) {
        long t0 = System.currentTimeMillis();
        Built b = new Built();
        b.tiles = MapLoad.decodeFull(it);
        if (b.tiles == null || !b.tiles.ok()) {
            b.error = "这张地图读不出来";
            return b;
        }
        StringBuilder note = new StringBuilder();
        // ★ 缓存里那份表**不能**被改（补丁会改行）⇒ 用前先深拷贝；译文与它同源，一起拿
        SlotContent sc = contentFor(ctx, slot, note);
        b.table = MapStats.copyOf(sc.table);
        b.modNote = note.toString();
        if (!b.tiles.patchEntries.isEmpty()) {
            List<String> logs = new ArrayList<>();
            int n = MapStats.applyPatches(b.table, b.tiles.patchEntries, logs);
            b.modNote = b.modNote + "；地图自带补丁 " + b.tiles.patchEntries.size() + " 条"
                    + (n > 0 ? "（改了 " + n + " 处）" : "");
        }
        b.names = new BundleNames(apkPath == null ? null : new File(apkPath), sc.bundle, attrLabels);
        b.namesNote = ((BundleNames) b.names).note;
        b.result = MapStats.analyze(b.tiles, b.table, b.names);
        if (!b.result.ok) b.error = b.result.error;
        b.millis = System.currentTimeMillis() - t0;
        return b;
    }

    /** 属性词表：键 = 游戏 `Attribute` 的名字（`water`/`oil`/`heat`/`spores`/`steam`/`light`/`sand`） */
    public static Map<String, String> attrLabels(Context ctx) {
        Map<String, String> m = new HashMap<>();
        m.put("water", ctx.getString(R.string.stats_attr_water));
        m.put("oil", ctx.getString(R.string.stats_attr_oil));
        m.put("heat", ctx.getString(R.string.stats_attr_heat));
        m.put("spores", ctx.getString(R.string.stats_attr_spores));
        m.put("steam", ctx.getString(R.string.stats_attr_steam));
        m.put("light", ctx.getString(R.string.stats_attr_light));
        m.put("sand", ctx.getString(R.string.stats_attr_sand));
        return m;
    }

    /** 千分位（格数动辄十万级，不分节读不出来） */
    public static String num(int v) {
        return String.format(Locale.ROOT, "%,d", v);
    }
}
