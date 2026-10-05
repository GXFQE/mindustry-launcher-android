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
        /** 译名来源那几层（技术细节层显示）—— **多条整句**，由显示侧连起来 */
        public final List<String> namesNotes = new ArrayList<>();
        /** 模组译文那一层（技术细节层显示）—— 单独一行，**不往别的句子尾巴上拼** */
        public String bundleNote = "";
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
        /** 这一层的一句话依据（技术细节层显示）—— ⚠️ 中文，只给**维护者**看；
         *  给用户的那份在 {@link Built#modNote} 等字段里、按界面语言拼 */
        public String note = "";
        /** 带译文的模组包数 */
        public int bundlesWithText;
        /** ★ 依据用**数字**（文案在 UI 侧拼 ⇒ 技术细节也跟着界面语言走） */
        public int packs, added, overridden;
        /** 游戏这次不加载模组（上次崩过） */
        public boolean skipMods;
        /** 模组那一层读不动 */
        public boolean failed;
    }

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private MapStatsMods() {}

    /**
     * 当前**界面语言**对应的 bundle 后缀（`zh_CN` / `en` / `pt_BR`）。
     *
     * ★ 2026-10-04（P4）：矿物/物品/方块名来自游戏的 `bundle_<后缀>.properties`，
     *   原来那个后缀**写死**成 `zh_CN` ⇒ 界面切成英文之后"地图矿物统计还是中文"（用户报的）。
     * ★ 取的是**启动器界面的语言**（而不是游戏里选的语言）：这一页是启动器自己的界面，
     *   同一屏里不该出现两种语言。启动器选"跟随系统"时，它自然等于系统语言，
     *   也就等于游戏在 `locale=default` 时用的那个（游戏读的是**进程**默认 locale）。
     */
    public static String bundleLocaleSuffix(Context ctx) {
        try {
            android.content.res.Configuration cfg = ctx.getResources().getConfiguration();
            Locale l = cfg.getLocales().isEmpty() ? Locale.getDefault() : cfg.getLocales().get(0);
            if (l == null) return "";
            String lang = l.getLanguage();
            if (lang == null || lang.isEmpty()) return "";
            String c = l.getCountry();
            return (c == null || c.isEmpty()) ? lang : (lang + "_" + c);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 缓存键：槽名 + 槽目录（换槽不能拿错模组表）＋ **界面语言**
     *  （★ 语言变了必须重读：译文就是按语言选的，拿旧缓存会继续显示上一门语言的名字） */
    private static String key(Context ctx, String slot) {
        File dir = Data.dirOf(ctx, slot);
        return slot + "@" + (dir == null ? "?" : dir.getAbsolutePath())
                + "@" + bundleLocaleSuffix(ctx);
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
     * ★ 返回的 {@link SlotContent} 里同时有**数字**（{@code packs/added/overridden/…}）与
     *   一句中文 {@code note}：数字是给 UI 按界面语言拼依据用的，`note` 只给维护者。
     */
    public static synchronized SlotContent contentFor(Context ctx, String slot) {
        File modsDir = null;
        try {
            modsDir = Mods.scan(ctx, slot).modsDir;
        } catch (Throwable ignored) {
        }
        String k = key(ctx, slot);
        String stamp = stampOf(modsDir);
        Cached c = CACHE.get(k);
        if (c != null && c.stamp.equals(stamp)) {
            return c.content;
        }
        SlotContent out = new SlotContent();
        out.table = MapStats.copyOf(MapStats.vanilla());
        MapStats.Table t = out.table;
        String line;
        try {
            Mods.Scan scan = Mods.scan(ctx, slot);
            if (scan.skipModLoading) {
                out.skipMods = true;
                line = "游戏这次不加载模组（上次崩过），按原版算";
            } else {
                List<MapStats.Pack> packs = new ArrayList<>();
                for (Mods.Info m : scan.mods) {
                    if (m == null || !m.enabled) continue;
                    if (m.willFailJavaLoad()) continue;      // 游戏整个跳过它 ⇒ 内容也不该算
                    MapStats.Pack p = new MapStats.Pack();
                    p.label = m.title();
                    // ★ 前缀 = **游戏注册用的那个名字**（`Mods.java:1253` 的
                    //   `meta.name.toLowerCase().replace(" ", "-")`，也就是我们的 `internalName`）。
                    //   🔴 第 111 轮前这里取的是 `m.name`（meta 原样，可能带大写）——
                    //   于是 `name: BpCase` 的模组被我们叫 `BpCase-x`，而游戏叫 `bpcase-x`
                    //   ⇒ 查表落空 ⇒ **含该模组方块的蓝图/地图被误报"本槽没有"**。
                    //   判据 = 真机对照实验 + `Mods.java:1253` 源码。
                    p.prefix = m.internalName != null && !m.internalName.isEmpty()
                            ? m.internalName
                            : (m.name != null ? Mods.internalNameOf(m.name) : "");
                    // meta 原样那一种只当**别名**（有作者按它引用，见 MapStats.Pack#alt）
                    p.alt = (m.name != null && !m.name.isEmpty()
                            && !m.name.equals(p.prefix)) ? m.name : "";
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
                out.packs = mr.packs;
                out.added = mr.added;
                out.overridden = mr.overridden;
                // ★ 译文：版本 APK 只认识原版内容，模组加的物品/方块得从**模组自己的 bundle** 里捞
                //   ★ 读哪一层语言包由**界面语言**决定（P4）
                out.bundle = MapStats.readBundles(packs,
                        MapStats.bundleLang(bundleLocaleSuffix(ctx)));
                for (MapStats.Pack p : packs) {
                    if (p.bundleKeys > 0) out.bundlesWithText++;
                }
                line = mr.packs + " 个模组（新增 " + mr.added + " 条，覆盖 " + mr.overridden + " 条"
                        + (out.bundlesWithText > 0
                        ? "；" + out.bundlesWithText + " 个带译文，共 " + out.bundle.keys() + " 条" : "")
                        + "）";
            }
        } catch (Throwable ex) {
            out.failed = true;
            line = "模组读不动，只按原版算";
        }
        out.note = line;
        c = new Cached();
        c.stamp = stamp;
        c.content = out;
        CACHE.put(k, c);
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
        // ★ 缓存里那份表**不能**被改（补丁会改行）⇒ 用前先深拷贝；译文与它同源，一起拿
        SlotContent sc = contentFor(ctx, slot);
        b.table = MapStats.copyOf(sc.table);
        // ★ 依据文案在**这里**按界面语言拼（`SlotContent` 只给数字）——
        //   原来是在 `contentFor` 里拼中文 ⇒ 英文界面下"技术细节"那几行也是中文
        if (sc.skipMods) b.modNote = ctx.getString(R.string.stats_note_skip_mods);
        else if (sc.failed) b.modNote = ctx.getString(R.string.stats_note_mods_failed);
        else b.modNote = ctx.getString(R.string.stats_note_mods_fmt,
                sc.packs, sc.added, sc.overridden);
        if (sc.bundlesWithText > 0) {
            b.bundleNote = ctx.getString(R.string.stats_note_bundles_fmt,
                    sc.bundlesWithText, sc.bundle.keys());
        }
        if (!b.tiles.patchEntries.isEmpty()) {
            List<String> logs = new ArrayList<>();
            MapStats.applyPatches(b.table, b.tiles.patchEntries, logs);
            // ⚠️ 这里**不再**单独拼一句补丁依据：`applyPatches` 自己会把
            //    `Note.PATCH_CHANGED` 记进 `Table.notes`，两处都写会在界面上出现**同一条两遍**
            //    （我 2026-10-04 引入过这个重复，真机截图能看出来）。
        }
        BundleNames bn = new BundleNames(apkPath == null ? null : new File(apkPath), sc.bundle,
                attrLabels, MapStats.bundleLang(bundleLocaleSuffix(ctx)),
                ctx.getString(R.string.stats_wall_name_fmt));
        b.names = bn;
        // ★ 名字来源（依据）：数字在 BundleNames 里，**句子在这里按界面语言拼**
        if (bn.nApkLocale > 0) {
            b.namesNotes.add(ctx.getString(R.string.stats_note_names_apk_fmt,
                    bn.apkName, bn.nApkLocale));
        } else {
            b.namesNotes.add(ctx.getString(R.string.stats_note_names_no_apk));
        }
        if (bn.nModLocale > 0) {
            b.namesNotes.add(ctx.getString(R.string.stats_note_names_mod_fmt, bn.nModLocale));
        }
        if (bn.nApkBase > 0) {
            b.namesNotes.add(ctx.getString(R.string.stats_note_names_apk_base_fmt, bn.nApkBase));
        }
        if (bn.nModBase > 0) {
            b.namesNotes.add(ctx.getString(R.string.stats_note_names_mod_base_fmt, bn.nModBase));
        }
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
