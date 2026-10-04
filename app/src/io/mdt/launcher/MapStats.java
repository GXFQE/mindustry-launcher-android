package io.mdt.launcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 地图「资源统计」的**纯 Java 核心**（F21，第 83 轮）：算出一张地图里
 * **地矿 / 墙矿 / 可采地板 / 加成地板**各占多少格，并把"被拆不掉的墙压住的格子"从"拿得到"里剔出去。
 *
 * <h3>判据链（每一环都有实测，见 REF §61）</h3>
 * <pre>
 *   ① 瓦片      MsavTiles（F10 已有，386/386 逐格与游戏一致）
 *   ② 定义表    「有效内容定义表」= 原版表 ⨁ 模组 content JSON ⨁ 地图自带数据补丁
 *   ③ 计数      按 content id **预计算标志** + int[] 计数（循环里不许出现字符串/HashMap：
 *                实测朴素写法 15.31 ms/张，这样写 1.64 ms/张，见 §61.5）
 *   ④ 遮挡      露天 / 可拆（计入）/ **永久（排除）** / 未知
 *   ⑤ 译名      该槽版本 APK 的 bundle（UTF-8）；矿照 OreBlock 口径 = 物品名
 * </pre>
 *
 * <h3>★ 什么叫「永久遮挡」（这一条是用户当场纠正过的口径）</h3>
 * 墙**拆不掉** ⇒ 下面的矿**永久拿不到**，必须排除；建筑与**可拆**道具能拆 ⇒ 计入。
 * <pre>
 *   永久遮挡 = 方块层不是 air，且 synthetic/destructible/breakable/alwaysReplace/unitMoveBreakable **全 0**
 *   露天     = 方块层是 air（或该 id 就是 air）
 *   可拆遮挡 = 上面五个标志里**有任意一个**
 * </pre>
 * 旁证（读游戏字节码）：`Drill.canMine(Tile)` 第一句就是 `if(tile.block().isStatic()) return false`；
 * `Tile.drop()` 在墙下**仍返回矿的物品**（物品在、拿不到）⇒ "有矿但采不到"必须自己剔。
 * ⚠️ 编辑器里能删墙，但那是**改地图**，不算"游戏内可得资源"。
 *
 * <h3>为什么这些表要 PC 侧导、不能端上现推</h3>
 * `type:` 没写的字段取的是**类默认值**（不是 false），而"裸 new 一个类拿默认值"需要
 * 游戏运行时环境：实测裸 new 时名字必须唯一（否则 content 注册表报
 * `Two content objects defined with the same name`，13/25 个类拿不到值）。
 * ⇒ 原版表与"类默认值表"都由 PC 侧的 `_lab/msav/f21/GenTable.java` 生成、嵌进
 * {@link MapStatsTable}（zlib + Base64 的文本表）。VE 的松树（`type: StaticTree, breakable: true`
 * **能拆**）与原版 `pine`（同一个类、四个标志全 0、**不能拆**）就是这条的试金石。
 *
 * <h3>本类刻意不碰 Android</h3>
 * 连 JSON 都是自带的极小读取器（见 {@link Json}），这样它能在 PC 上单独编译，
 * 拿 460 个语料与 F21 的既有产物逐格对照（`_lab/msav/f21/StatsCheck.java`）。
 *
 * 🔴 本类**只读**：不 mkdirs、不落盘、不改任何东西。
 */
public final class MapStats {

    /** 定义表的来源（技术细节层显示用） */
    public static final String SRC_VANILLA = "原版";
    public static final String SRC_MOD = "模组";
    public static final String SRC_PATCH = "地图补丁";

    // ══ 一、一张定义表的行 ═══════════════════════════════════════════════════

    /**
     * 一个方块的内容定义（列与 `_lab/msav/f21/GenTable.java` 导出的 18 列一一对应）。
     * 🔴 列序**不能改**：改了就得重新生成 {@link MapStatsTable}。
     */
    public static final class Def {
        public String name = "";
        public boolean isFloor, isOverlay, isOre, isStatic, synthetic, solid, wallOre;
        public boolean playerUnmineable, breakable, destructible, alwaysReplace, unitMoveBreakable;
        public String itemDrop = "", liquidDrop = "", attributes = "";
        public float speed = 1f, damage = 0f;
        /** 这行从哪来（{@link #SRC_VANILLA} 等；技术细节层显示） */
        public String source = SRC_VANILLA;
        /**
         * 这行的**模组内容前缀**（原版行为空）。
         *
         * 🔴 为什么要留着它：模组 JSON 里写的内容引用是**短名**（VE 的矿写 `itemDrop: aluminium`），
         *   而游戏解析引用时是**先试 `<模组名>-<名字>`、再试原样**
         *   （`ContentParser$2` 字节码：`prefix = currentMod.name + "-"` → `getByName(type, prefix+name)`
         *   → 查不到才 `getByName(type, name)`）。⇒ 显示名字时必须照同一条顺序来，
         *   否则模组的物品译文（`item.ve-aluminium.name = 铝`）永远用不上（实测 28/43 个模组矿中招）。
         */
        public String prefix = "";

        /**
         * 名字**直接写在内容 JSON 里**（`"localizedName": "石英"`）—— 优先级**高于** bundle。
         *
         * 🔴 为什么必须有它（用户 2026-10-04 指出"有些游戏里有翻译的"）：
         *   有的模组**根本不带 `bundles/`**，而是把中文写在每个内容文件里
         *   （实测 `frost-industry-mod.zip`：整包没有 `.properties`，
         *   但 `content/items/quartz.hjson` 里是 `{"name":"quartz","localizedName":"石英",…}`）
         *   ⇒ 只认 bundle 的话，这些名字全是 `Quartz` 这种可读化形式。
         *   游戏侧：`ContentParser` 解析 JSON 时把它赋给 `UnlockableContent.localizedName`
         *   （构造器先按 bundle 取名，JSON 再覆盖；v160.4 字节码已核）。
         */
        public String localizedName = "";

        /**
         * 这个方块**能不能清掉** —— 决定它挡住的资源算不算"永久拿不到"。
         * 判据 = 五个标志里**有任意一个**为真（见类注释；与 F21 产物 `f21-occlusion-*.txt` 同一口径）。
         */
        public boolean removable() {
            return synthetic || destructible || breakable || alwaysReplace || unitMoveBreakable;
        }

        public boolean isAir() {
            return "air".equals(name);
        }

        /** 是不是"加成地板"（有属性 / 移速≠1 / 伤害≠0）—— 照 Occ3 的判据 */
        public boolean bonus() {
            return !attributes.isEmpty() || speed != 1f || damage != 0f;
        }

        Def copy() {
            Def d = new Def();
            d.name = name;
            d.isFloor = isFloor;
            d.isOverlay = isOverlay;
            d.isOre = isOre;
            d.isStatic = isStatic;
            d.synthetic = synthetic;
            d.solid = solid;
            d.wallOre = wallOre;
            d.playerUnmineable = playerUnmineable;
            d.breakable = breakable;
            d.destructible = destructible;
            d.alwaysReplace = alwaysReplace;
            d.unitMoveBreakable = unitMoveBreakable;
            d.itemDrop = itemDrop;
            d.liquidDrop = liquidDrop;
            d.attributes = attributes;
            d.speed = speed;
            d.damage = damage;
            d.source = source;
            d.prefix = prefix;
            d.localizedName = localizedName;
            return d;
        }

        /** 18 列（name 在 c[0]）—— 与表文件**逐字对应**，自检拿它做往返 */
        static Def of(String[] c) {
            Def d = new Def();
            d.name = c[0];
            d.isFloor = on(c, 1);
            d.isOverlay = on(c, 2);
            d.isOre = on(c, 3);
            d.isStatic = on(c, 4);
            d.synthetic = on(c, 5);
            d.solid = on(c, 6);
            d.wallOre = on(c, 7);
            d.itemDrop = col(c, 8);
            d.playerUnmineable = on(c, 9);
            d.speed = f(col(c, 10), 1f);
            d.damage = f(col(c, 11), 0f);
            d.liquidDrop = col(c, 12);
            d.attributes = col(c, 13);
            d.breakable = on(c, 14);
            d.destructible = on(c, 15);
            d.alwaysReplace = on(c, 16);
            d.unitMoveBreakable = on(c, 17);
            return d;
        }

        String[] cols() {
            return new String[]{name, b(isFloor), b(isOverlay), b(isOre), b(isStatic), b(synthetic),
                    b(solid), b(wallOre), itemDrop, b(playerUnmineable), String.valueOf(speed),
                    String.valueOf(damage), liquidDrop, attributes, b(breakable), b(destructible),
                    b(alwaysReplace), b(unitMoveBreakable), source};
        }

        private static boolean on(String[] c, int i) {
            return i < c.length && "1".equals(c[i]);
        }

        private static String col(String[] c, int i) {
            return i < c.length ? c[i] : "";
        }

        private static String b(boolean v) {
            return v ? "1" : "0";
        }

        private static float f(String s, float def) {
            try {
                return Float.parseFloat(s.trim());
            } catch (Throwable t) {
                return def;
            }
        }
    }

    /** 一张「有效内容定义表」：名字 → 定义 */
    public static final class Table {
        final Map<String, Def> byName = new HashMap<>();
        /**
         * **物品**的名字（来自模组的 `content/items/**`）—— 键 = 注册名，值 = JSON 里写的中文名。
         *
         * ★ 为什么不建行：地图的 content 区只列**方块**，物品只用来给矿/掉落物取名字。
         * ★ 同一件物品会记**两个键**（`name:` 里写的那个、以及 `<模组前缀>-<文件名>`）——
         *   模组把名字写死在 JSON 里时，游戏注册的是哪个名字我们**不猜**，两个都认。
         */
        public final Map<String, String> itemNames = new LinkedHashMap<>();
        /** 从内容 JSON 里读到几个"自带名字"（块 + 物品）—— 技术细节层用 */
        public int jsonNames;
        /** 这张表是怎么拼出来的（技术细节层显示给用户看"依据"） */
        public final List<String> notes = new ArrayList<>();

        public int size() {
            return byName.size();
        }

        public Def row(String name) {
            return name == null ? null : byName.get(name);
        }

        public void put(Def d) {
            byName.put(d.name, d);
        }

        /** 用**另一个名字**也指向同一行（模组把 `name:` 写死在 JSON 里时两种写法都可能出现） */
        public void alias(String name, Def d) {
            if (name == null || name.isEmpty() || byName.containsKey(name)) return;
            byName.put(name, d);
        }

        /** 记一条物品名（供矿/掉落物取名字用） */
        void putItem(String name, String localized) {
            if (name == null || name.isEmpty() || localized == null || localized.isEmpty()) return;
            itemNames.put(name, localized);
        }
    }

    /** 原版表（进程内只解一次；⚠️ 调用方要改表请先 {@link #copyOf}，别动这份） */
    private static Table VANILLA;

    /** 方块类默认值表（模组 content JSON 里没写的字段取它） */
    private static Map<String, Def> TYPES;

    public static synchronized Table vanilla() {
        if (VANILLA == null) {
            Table t = new Table();
            loadRows(t, MapStatsTable.VANILLA_B64, SRC_VANILLA);
            t.notes.add(SRC_VANILLA + "方块表 " + t.size() + " 条");
            VANILLA = t;
        }
        return VANILLA;
    }

    /** 深拷贝一份（补丁/模组都会**改行**，绝不能改到上面那份共享的原版表） */
    public static Table copyOf(Table base) {
        Table t = new Table();
        for (Map.Entry<String, Def> e : base.byName.entrySet()) t.byName.put(e.getKey(), e.getValue().copy());
        t.itemNames.putAll(base.itemNames);
        t.jsonNames = base.jsonNames;
        t.notes.addAll(base.notes);
        return t;
    }

    private static synchronized Map<String, Def> types() {
        if (TYPES == null) {
            Table t = new Table();
            loadRows(t, MapStatsTable.TYPES_B64, SRC_VANILLA);
            TYPES = t.byName;
        }
        return TYPES;
    }

    private static void loadRows(Table t, String b64, String source) {
        for (String line : inflate(b64).split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] c = line.split("\t", -1);
            if (c.length < 18) continue;
            Def d = Def.of(c);
            d.source = source;
            t.byName.put(d.name, d);
        }
    }

    /** 解 Base64(zlib(UTF-8 文本)) —— 生成侧用 `Deflater` 默认格式，这边 `InflaterInputStream` 对得上 */
    static String inflate(String b64) {
        try {
            byte[] raw = Base64.getDecoder().decode(b64);
            InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(raw));
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, raw.length * 6));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    // ══ 二、译名（名字从哪来） ═══════════════════════════════════════════════

    /**
     * 名字来源。做成接口是为了：**核心保持纯 Java**（PC 可编、可单独喂断言），
     * 而"从哪个 APK 读 bundle、属性词用哪几个中文字"留在外面
     * （{@link MapStatsMods.ApkNames} 是产品侧实现；PC 验收台有自己的实现）。
     */
    public interface Names {
        String block(String internal);

        String item(String internal);

        /** 矿墙后缀（游戏 bundle 的 `wallore`，中文是「（墙）」） */
        String wallSuffix();

        /** 属性词（`water` → 「含水」）；认不出就返回原键 */
        String attr(String key);
    }

    /** 没有译名时的兜底：内部名 → 可读（`dark-metal` → `Dark Metal`） */
    public static String pretty(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : s.split("-")) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // ══ 三、模组 content JSON（有 type ⇒ 新内容；无 type ⇒ 覆盖同一行） ═════════

    /** 一个模组包（zip/jar 或目录形态） */
    public static final class Pack {
        /** zip/jar 形态的包；目录形态时为 null */
        public File pkg;
        /** 目录形态的根（已下潜一层） */
        public File dir;
        /** zip 形态的根前缀（已下潜一层，`""` 或 `"X/"`） */
        public String rootPrefix = "";
        /** 内容前缀 = 模组名（**照游戏口径用原样的 name**，不是小写化的内部名） */
        public String prefix = "";
        /** 小写化的内部名（覆盖查找的第二个候选，见 {@link #applyModContent}） */
        public String internal = "";
        /** 显示名（技术细节层） */
        public String label = "";
        /** 这个包的 bundle 里读到几条（0 = 它没带译文）—— 技术细节层用 */
        public int bundleKeys;
    }

    /**
     * 模组 bundle 的**两层**（见 {@link Bundles}）：`bundle_zh_CN.properties` → 语言层、
     * `bundle.properties` → 基础层，**目录名是 `bundles` 或 `*-bundles`**。
     *
     * ⚠️ 允许带一层包裹前缀（`蓝钢-欢迎您/bundles/…`、`TnmSpiroct-…/bundles/…` 都实测见过）。
     * 🔴 必须按 **UTF-8** 读（`Properties.load(InputStream)` 是 ISO-8859-1 ⇒ 全篇乱码）。
     */
    private static final String BUNDLE_LOCALE_FILE = "bundle_zh_CN.properties";
    private static final String BUNDLE_BASE_FILE = "bundle.properties";

    /**
     * 模组译文的两层（{@link #readBundles} 的产物）。
     *
     * ★ 为什么是**两层**而不是一张合并表：游戏 `Mods` 里是对 `Core.bundle` 的
     *   **每一层各加载一次**（v160.4 字节码）：
     * <pre>
     *   for(I18NBundle b = Core.bundle; b != null; b = b.getParent()){
     *       for(Fi f : bundles.get("bundle" + b.getLocale()))
     *           PropertiesUtils.load(b.getProperties(), f.reader());
     *   }
     * </pre>
     *   ⇒ 模组的 `bundle_zh_CN.properties` 写进**语言层**（与版本 APK 的语言包**同一个 map**）、
     *   模组的 `bundle.properties` 写进**基础层**；查找 = 语言层 → 基础层，
     *   而**每一层里模组都后写** ⇒ 模组覆盖版本 APK 的同层键。
     */
    public static final class Bundles {
        /** 语言层（`bundle_zh_CN.properties`） */
        public final Map<String, String> locale = new LinkedHashMap<>();
        /** 基础层（`bundle.properties`） */
        public final Map<String, String> base = new LinkedHashMap<>();

        public int keys() {
            return locale.size() + base.size();
        }
    }

    /**
     * 从 zip 里读**所有** bundle 文件，按文件名分到两层。
     *
     * 🔴 为什么要"所有"而不是只找 `bundles/bundle_zh_CN.properties`（第 84 轮实测踩到）：
     *   模组可以把译文放在**任意 `*-bundles` 目录**里，由它自己的代码加载 ——
     *   实测 `Neon-vN14.zip` 有 **两个** bundle：`bundles/bundle_zh_CN.properties`（它自己的 UI 键）
     *   与 **`fst-bundles/bundle_zh_CN.properties`**（2754 条，含 `item.tungsten.name = 钨`
     *   这些**原版内容名**）；它的 `classes.dex` 里就有 `/fst-bundles/` 这个字符串
     *   ⇒ 游戏运行时那些名字是有的，我们只读标准目录就会显示成 `Ore Tungsten`。
     *   ⚠️ 这条是"用户说游戏里有翻译、我们这里没有"的直接原因。
     */
    private static void readZipBundles(ZipFile zf, MapStats.Pack p, MapStats.Bundles out) {
        List<String> hits = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (bundleLayer(n) != 0) hits.add(n);
        }
        Collections.sort(hits);            // 顺序固定（后读的覆盖先读的）
        for (String n : hits) {
            try {
                p.bundleKeys += loadBundle(bundleLayer(n) == 1 ? out.locale : out.base,
                        zf.getInputStream(zf.getEntry(n)), 1);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 目录形态：在包根下找一层 `*bundles/` 目录 */
    private static void readDirBundles(File root, MapStats.Pack p, MapStats.Bundles out) {
        File[] fs = root.listFiles();
        if (fs == null) return;
        for (File d : fs) {
            if (d == null || !d.isDirectory() || !isBundleDir(d.getName().toLowerCase(Locale.ROOT))) continue;
            for (String file : new String[]{BUNDLE_BASE_FILE, BUNDLE_LOCALE_FILE}) {
                File f = new File(d, file);
                if (!f.isFile()) continue;
                try {
                    Map<String, String> into = file.equals(BUNDLE_LOCALE_FILE) ? out.locale : out.base;
                    p.bundleKeys += loadBundle(into, new java.io.FileInputStream(f), 0);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** `bundles` 或 `*-bundles`（后者是模组代码自己加载的，见 {@link #readZipBundles}） */
    private static boolean isBundleDir(String dirLower) {
        return dirLower.equals("bundles") || dirLower.endsWith("-bundles");
    }

    /** 这个 zip 条目属于哪一层：1 = 语言包，2 = 基础包，0 = 不是 bundle */
    private static int bundleLayer(String path) {
        String s = path.toLowerCase(Locale.ROOT);
        if (!s.endsWith(".properties")) return 0;
        int slash = s.lastIndexOf('/');
        if (slash <= 0) return 0;
        String file = s.substring(slash + 1);
        String dir = s.substring(0, slash);
        int d2 = dir.lastIndexOf('/');
        String dirName = d2 >= 0 ? dir.substring(d2 + 1) : dir;
        if (!isBundleDir(dirName)) return 0;
        // ⚠️ 这里比的是**小写化后**的条目名（`s` 已经 toLowerCase），所以右侧也要小写；
        //    目录形态走的是真实文件名，**必须保持 `bundle_zh_CN` 的大小写**
        //    （Android/Linux 文件系统区分大小写 —— 我第一版这里写成小写，结果语言包一个都没读到）
        if (file.equals(BUNDLE_LOCALE_FILE.toLowerCase(Locale.ROOT))) return 1;
        if (file.equals(BUNDLE_BASE_FILE)) return 2;
        return 0;
    }

    /**
     * 读一组模组的译文，**分两层**返回（见 {@link Bundles}）。
     *
     * <h3>为什么需要它</h3>
     * 版本 APK 的 bundle **只认识原版内容**；模组加的物品/方块（`ve-aluminium`、`ve-melondirt`…）
     * 在那里查不到 ⇒ 只能显示内部名的可读化形式（「Ve Aluminium」），而模组自己几乎都带译文
     * （实测 18 个真模组里 15 个带 bundle）。
     *
     * @return 两层（**包之间后读的覆盖先读的**，与游戏按 mod 顺序加载一致）
     */
    public static Bundles readBundles(List<Pack> packs) {
        Bundles out = new Bundles();
        if (packs == null) return out;
        for (Pack p : packs) {
            if (p == null) continue;
            try {
                if (p.dir != null && p.dir.isDirectory()) {
                    readDirBundles(p.dir, p, out);
                } else if (p.pkg != null && p.pkg.isFile()) {
                    ZipFile zf = new ZipFile(p.pkg);
                    try {
                        readZipBundles(zf, p, out);
                    } finally {
                        try {
                            zf.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 一个包读不动不影响别的包（坏包本来就会被游戏跳过）
            }
        }
        return out;
    }

    /**
     * `Properties`（UTF-8）→ 目标层；返回这次读到的条数。
     *
     * @param knownMissing 1 = 调用方已经确认这个文件不存在（目录形态则会抛 FileNotFound，
     *                     当成"这个包没带这一层"即可）—— 这个参数只是把意图写清楚，不参与逻辑
     */
    private static int loadBundle(Map<String, String> out, InputStream in, int knownMissing) {
        Properties props = new Properties();
        try {
            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            try {
                props.load(r);
            } finally {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return 0;                       // 文件不存在/读不动 ⇒ 当作"这个包没带这一层"
        }
        int n = 0;
        for (String k : props.stringPropertyNames()) {
            String v = props.getProperty(k);
            if (v == null || v.trim().isEmpty()) continue;      // 空值不算"翻译"（免得盖掉已有的）
            out.put(k, v);
            n++;
        }
        return n;
    }

    /** 应用模组内容的结果（给技术细节层看"依据"） */
    public static final class ModResult {
        public int packs, entries, added, overridden, skipped, unknownType;
        /** 从内容 JSON 里读到的"自带名字"条数（块 + 物品） */
        public int jsonNames;
        public final List<String> unknownTypes = new ArrayList<>();
    }

    /**
     * 把一组模组的 `content/**` 叠到表上。
     *
     * 规则（F21 实测，VE 的松树是试金石）：
     * <pre>
     *   模组前缀 = mod.hjson/json 里的 name（VE = "ve" ⇒ ve-tree / ve-ore-aluminium）
     *   content/** 里**有 `type:`**  ⇒ 新内容，名字 = &lt;前缀&gt;-&lt;文件名&gt;
     *   content/** 里**没有 `type:`** ⇒ 覆盖**同名已有**内容（先试原版名，再试 &lt;前缀&gt;-名字；
     *                                都找不到就跳过 —— **不造幽灵行**，见 §61.6）
     *   JSON 里**没写**的字段 ⇒ 取类的默认值（{@link MapStatsTable#TYPES_B64}）
     * </pre>
     * ⚠️ 覆盖必须是"**在旧行上改字段**"，不是替换整行 —— 第一版替换后 `sand-water` 丢了
     *   `liquidDrop=water`/`speed=0.8`，统计口径当场变味（§61.6 第 2 条）。
     */
    public static ModResult overlayMods(Table t, List<Pack> packs) {
        ModResult r = new ModResult();
        if (packs == null) return r;
        for (Pack p : packs) {
            if (p == null) continue;
            r.packs++;
            try {
                if (p.dir != null && p.dir.isDirectory()) {
                    walkDir(t, p, p.dir, r);
                } else if (p.pkg != null && p.pkg.isFile()) {
                    readZip(t, p, r);
                }
            } catch (Throwable ignored) {
                // 一个包读不动不影响别的包（也与游戏一致：坏包被跳过）
            }
        }
        r.jsonNames = t.jsonNames;
        // ⚠️ 这里**不要**再报"N 个包、新增/覆盖多少条" —— 那是 {@link ModResult} 的事，
        //    调用方（MapStatsMods）已经把它写进依据里了；两处都写会在界面上出现**同一条两遍**
        //    （真机截图上就是这么暴露的）。
        if (r.jsonNames > 0) {
            t.notes.add("名字写在内容文件里的有 " + r.jsonNames + " 条（这些模组不带译文文件）");
        }
        if (r.unknownType > 0) {
            t.notes.add("模组里有 " + r.unknownType + " 处内容用了我们认不出的类型（只算进「认不出」那一桶）");
        }
        return r;
    }

    private static void readZip(Table t, Pack p, ModResult r) throws Exception {
        ZipFile zf = null;
        try {
            zf = new ZipFile(p.pkg);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (!isContentPath(n) && !isItemPath(n)) continue;
                String text = readText(zf.getInputStream(e));
                applyModContent(t, p, n, text, r);
            }
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void walkDir(Table t, Pack p, File dir, ModResult r) {
        walkDir(t, p, dir, "", r);
    }

    /**
     * 目录形态的模组。
     *
     * ⚠️ 判路径要用**相对路径**（`content/blocks/x.json`），不能只用文件名 ——
     * 第一版传 `f.getName()`，于是目录模组的 `content\items\quartz.hjson` 这种
     * 整条都匹配不上（JSON 里那些"自带名字"就全丢了）。
     */
    private static void walkDir(Table t, Pack p, File dir, String rel, ModResult r) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String path = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) {
                walkDir(t, p, f, path, r);
            } else if (isContentPath(path) || isItemPath(path)) {
                try {
                    applyModContent(t, p, path, readText(new java.io.FileInputStream(f)), r);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 只看方块内容：`content/**` 下、`blocks/` 之后的 `.json/.hjson/.json5`。 */
    static boolean isContentPath(String n) {
        return under(n, "blocks");
    }

    /**
     * 只看**物品**内容：`content/**` 下、`items/` 之后的 `.json/.hjson/.json5`。
     *
     * ★ 为什么要读它们：物品**不进地图的 content 区**（那里只有方块），
     *   但矿与掉落物的名字取自物品 ⇒ 物品文件里的 `localizedName` 正是我们要的中文名
     *   （实测 `frost-industry-mod.zip` 整包没有 `bundles/`，中文全写在物品/方块文件里）。
     */
    static boolean isItemPath(String n) {
        return under(n, "items");
    }

    /**
     * 路径判据：`content/**` 下、指定子目录之后的 `.json/.hjson/.json5`。
     *
     * 🔴 判据来自游戏字节码（v160.4 `server-release.jar`）：
     *   `Mods.loadContent()` 是**按 `ContentType.folderName` 逐目录**扫的（`ContentType.all` →
     *   `name().toLowerCase()` → 与目录名比对），方块的目录就是 `blocks`、物品是 `items`
     *   ⇒ **只有 `content/blocks/**` 里的 JSON 会被当成方块**；写在别的子目录里、
     *   靠 `type: StaticWall` "冒充"方块是不成立的。
     * ★ 目录允许**带一层包裹前缀**（`X/content/blocks/…`，Windows 打包工具常见）——
     *   游戏自己也有 `resolveRootPrefix` 处理它，所以我们按"出现过 content/ 且其后出现过该子目录"
     *   判，而不是要求条目名以 `content/` 开头（第 82 轮的探针就是栽在这条上：它漏掉了
     *   `饱和火力` 那种整个包套一层目录的模组，共 297 个方块 JSON）。
     */
    private static boolean under(String n, String dir) {
        String s = n.toLowerCase(Locale.ROOT);
        if (!(s.endsWith(".json") || s.endsWith(".hjson") || s.endsWith(".json5"))) return false;
        int c = s.indexOf("content/");
        if (c < 0) return false;
        return s.indexOf(dir + "/", c) > c;
    }

    private static String readText(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 一条模组内容（包内条目名 / 文件名 + 原文）。
     *
     * 三类处理：
     * <pre>
     *   物品（content/items/**） ⇒ **不建行**，只记"注册名 → JSON 里的中文名"（矿/掉落物取名字用）
     *   方块 + 有 type:          ⇒ 新内容，名字 = JSON 的 name: 优先，否则 &lt;前缀&gt;-&lt;文件名&gt;
     *                              （两种写法都登记成别名 —— 地图里的名字是哪一种我们不猜）
     *   方块 + 没有 type:        ⇒ 在**已有**的同名行上改字段（找不到就跳过，不造幽灵行）
     * </pre>
     */
    public static String applyModContent(Table t, Pack p, String entryName, String text, ModResult r) {
        String base = entryName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        Map<String, Object> json = jsonOf(text);
        if (json == null) return "看不懂";
        r.entries++;

        if (isItemPath(entryName)) {
            String loc = str(json.get("localizedName"), "");
            if (!loc.isEmpty()) {
                String reg = jsonName(json, base, p.prefix);
                t.putItem(reg, loc);
                t.putItem(p.prefix + "-" + base, loc);      // 两种写法都认（见 Table#itemNames）
                t.jsonNames++;
            }
            return "物品名字";
        }

        Object type = json.get("type");
        if (type != null) {
            // ★ 查表是**大小写敏感的精确简单名**，与游戏一致：`mindustry/mod/ClassMap.classes`
            //   是 `ObjectMap<String,Class>`，键就是 `Class.getSimpleName()`，`ContentParser`
            //   直接拿 JSON 里的 type 去查这张表（v160.4 字节码）。⇒ 模组写 `powerTurret` /
            //   `beamDrill` / 全限定名，**游戏自己也解析不出来**（实测 18 个真模组里有 30 处这种），
            //   我们跟着跳过 —— 而不是"猜一个最像的类"（那会把游戏里根本不存在的方块算进统计）。
            Def d = types().get(String.valueOf(type));
            if (d == null) {                       // 认不出的类 ⇒ **不造幽灵行**（会被误判成永久墙）
                r.unknownType++;
                if (r.unknownTypes.size() < 12) r.unknownTypes.add(String.valueOf(type));
                return "认不出的类型";
            }
            Def nd = d.copy();
            String reg = jsonName(json, base, p.prefix);
            nd.name = reg;
            nd.source = SRC_MOD + "（新）";
            nd.prefix = p.prefix;
            nd.localizedName = str(json.get("localizedName"), "");
            if (!nd.localizedName.isEmpty()) t.jsonNames++;
            applyJson(nd, json, p.prefix);
            if (t.row(nd.name) == null) r.added++;
            t.put(nd);
            // ★ 别名：JSON 里写了 `name:` 的模组（frost-industry 就是），注册名**不带前缀**
            //   （作者原话"文件名=内部名，所有引用写这个名字"）⇒ 地图里的内容名两种写法都可能出现，
            //   两个都登记，统计时不会两边都认不出。
            t.alias(p.prefix + "-" + base, nd);
            return "新内容";
        }
        // 无 type：覆盖已有内容（先原版名，再 <前缀>-名字；两个前缀写法都试）
        Def old = t.row(base);
        if (old == null) old = t.row(p.prefix + "-" + base);
        if (old == null && !p.internal.isEmpty()) old = t.row(p.internal + "-" + base);
        if (old == null) {
            r.skipped++;                           // 模组代码/JS 定义的内容我们认不出来 ⇒ 跳过
            return "目标不认识，跳过";
        }
        // ★ 这里原来有一个**空体 if**：`if (!SRC_VANILLA.equals(old.source) && old.source.startsWith(SRC_MOD)) { }`
        //   —— 判据建在 SRC_VANILLA / SRC_MOD 这两个**中文常量**上，而体内只有一句注释、
        //   **什么都没做**（走到这里本来就是"在当前行上继续改"）。
        //   2026-10-04（i18n P0.1）把它删了：留着一条不生效、又拿文案当判据的条件，
        //   只会让下一个人以为它在起作用。它想表达的意思保留在下面这句注释里：
        //   ⇒ 已经被别的包改过的行，**照样在当前行上继续改**（与游戏的叠加顺序一致）。
        String loc = str(json.get("localizedName"), "");
        if (!loc.isEmpty()) {
            old.localizedName = loc;
            t.jsonNames++;
        }
        applyJson(old, json, p.prefix);
        old.source = SRC_MOD + "（改）";
        r.overridden++;
        return "覆盖";
    }

    /**
     * 这条内容**注册名**是什么：JSON 里写了 `name:` 就用它，否则 `&lt;前缀&gt;-&lt;文件名&gt;`。
     *
     * 依据：`ContentParser` 会拿 JSON 的 `name` 去登记 bundle 键并赋 `localizedName`
     * （v160.4 字节码）；frost-industry 的作者注释也写明"文件名=内部名"。
     * ⚠️ 这一条**只在两种写法都登记别名时才稳**（我们就是这么做的），不赌单一答案。
     */
    static String jsonName(Map<String, Object> json, String base, String prefix) {
        Object n = json == null ? null : json.get("name");
        String s = n == null ? "" : String.valueOf(n).trim();
        return s.isEmpty() ? prefix + "-" + base : s;
    }

    /**
     * 把 JSON 里**真的写了**的字段盖到已有行上（没写的一律保留旧值）。
     *
     * @param prefix 这条 JSON 所属模组的内容前缀（`""` = 原版口径）——
     *               只用来标记"这行的内容引用该按 `<前缀>-<名字>` 先解析"（见 {@link Def#prefix}）
     */
    static void applyJson(Def d, Map<String, Object> json, String prefix) {
        for (Map.Entry<String, Object> e : json.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) continue;
            if ("itemDrop".equals(k)) {
                d.itemDrop = str(v, d.itemDrop);
                if (prefix != null && !prefix.isEmpty()) d.prefix = prefix;
            } else if ("liquidDrop".equals(k)) {
                d.liquidDrop = str(v, d.liquidDrop);
                if (prefix != null && !prefix.isEmpty()) d.prefix = prefix;
            } else if ("attributes".equals(k)) d.attributes = attrs(v);
            else if ("solid".equals(k)) d.solid = bool(v, d.solid);
            else if ("breakable".equals(k)) d.breakable = bool(v, d.breakable);
            else if ("destructible".equals(k)) d.destructible = bool(v, d.destructible);
            else if ("alwaysReplace".equals(k)) d.alwaysReplace = bool(v, d.alwaysReplace);
            else if ("unitMoveBreakable".equals(k)) d.unitMoveBreakable = bool(v, d.unitMoveBreakable);
            else if ("playerUnmineable".equals(k)) d.playerUnmineable = bool(v, d.playerUnmineable);
            else if ("wallOre".equals(k)) d.wallOre = bool(v, d.wallOre);
            else if ("speedMultiplier".equals(k)) d.speed = num(v, d.speed);
            else if ("damageTaken".equals(k)) d.damage = num(v, d.damage);
            // 其余字段（requirements / size / health / plans …）与"能不能采"无关，不建模
        }
    }

    /** 属性对象 → `water=1.0;`（与表里的写法一致，便于技术细节层原样显示） */
    static String attrs(Object v) {
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return def;
    }

    private static float num(Object v, float def) {
        if (v instanceof Number) return ((Number) v).floatValue();
        try {
            return Float.parseFloat(String.valueOf(v));
        } catch (Throwable t) {
            return def;
        }
    }

    /** HJSON/JSON5 → 对象（先按 160 的档试，失败退 159 —— 与 {@link Mods#parseMeta} 同一条链） */
    static Map<String, Object> jsonOf(String text) {
        String json = null;
        try {
            json = Hjson.toJsonText(text, Hjson.ARC_160);
        } catch (Throwable t160) {
            try {
                json = Hjson.toJsonText(text, Hjson.ARC_159);
            } catch (Throwable t159) {
                return null;
            }
        }
        try {
            Object o = Json.parse(json);
            if (o instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                return m;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ══ 四、地图自带的数据补丁（v12+ 的 patches 区） ══════════════════════════

    /**
     * 把一个"数据补丁"服务到表上（游戏 `DataPatcher` 的字段覆盖口径）。
     * 补丁原文形如：
     * <pre>{"name":"Patch0","block":{"dark-metal":{"attributes":{"scrapmetal":2}}}}</pre>
     * ⇒ 按 `block.&lt;名字&gt;.&lt;字段&gt;` 覆盖；目标不认识就跳过（可能来自我们没解析出来的模组内容）。
     */
    public static int applyPatch(Table t, String text, List<String> logs) {
        Map<String, Object> json = jsonOf(text);
        if (json == null) return 0;
        Object blocks = json.get("block");
        if (!(blocks instanceof Map)) return 0;
        int n = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) blocks).entrySet()) {
            String name = String.valueOf(e.getKey());
            Def d = t.row(name);
            if (d == null || !(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) e.getValue();
            applyJson(d, fields, "");          // 地图补丁没有模组前缀 ⇒ 按原样解析引用
            d.source = SRC_PATCH;
            n++;
            if (logs != null && logs.size() < 8) logs.add(name);
        }
        return n;
    }

    /**
     * 把地图**自带**的补丁条目服务到表上（{@link MsavPatches} 解出来的那一串）。
     *
     * @param logs 技术细节层要显示"改了哪几个方块"（可传 null；只收前几条）
     * @return 改动的条数
     */
    public static int applyPatches(Table t, List<MsavPatches.Entry> entries, List<String> logs) {
        if (entries == null || entries.isEmpty()) return 0;
        int n = 0;
        for (MsavPatches.Entry e : entries) {
            if (e == null || e.text == null) continue;
            if (e.type == MsavPatches.PATCH) {
                n += applyPatch(t, e.text, logs);
            } else if (e.type == MsavPatches.CONTENT) {
                String base = e.path;
                int slash = base.lastIndexOf('/');
                if (slash >= 0) base = base.substring(slash + 1);
                int dot = base.lastIndexOf('.');
                if (dot >= 0) base = base.substring(0, dot);
                Map<String, Object> json = jsonOf(e.text);
                if (json == null) continue;
                Object type = json.get("type");
                Def d = type == null ? null : types().get(String.valueOf(type));
                if (d == null) continue;           // 认不出的类 ⇒ 不造幽灵行
                Def nd = d.copy();
                // 游戏 `DataPatcher` 给新增内容加 `dp-` 前缀（实测 §61.3）
                nd.name = "dp-" + base;
                nd.source = SRC_PATCH;
                applyJson(nd, json, "");
                t.put(nd);
                n++;
                if (logs != null && logs.size() < 8) logs.add(nd.name);
            }
        }
        if (n > 0) t.notes.add(SRC_PATCH + "：改了 " + n + " 条内容定义");
        return n;
    }

    // ══ 五、统计 ═══════════════════════════════════════════════════════════

    /** 一类资源里的一行（**同名合并**：`ore-thorium` 与 `ore-crystal-thorium` 都是「钍」） */
    public static final class Row {
        public String label = "";
        /** 掉落物名（可采地板才有；空 = 没有） */
        public String drop = "";
        /** 属性词（加成地板才有，如「含水 含油」） */
        public String attrs = "";
        /** 这一行合并了哪些内部名（技术细节层） */
        public final List<String> internals = new ArrayList<>();
        public String source = SRC_VANILLA;
        public int total, open, loose, buried, unknown;

        /** **拿得到**的格数 = 露天 + 可拆遮挡 （永久遮挡**排除**） */
        public int reachable() {
            return open + loose;
        }
    }

    /** 认不出的内容（模组/补丁里我们没解析到的） */
    public static final class Unk {
        public String name = "";
        public int ore, floor, bonus;

        public int total() {
            return ore + floor + bonus;
        }
    }

    /** 统计结果（**只给数据，不给文案** —— 文案在 strings.xml，见文案纪律） */
    public static final class Result {
        public boolean ok;
        public String error = "";
        public int width, height, cells;
        public final List<Row> ores = new ArrayList<>();
        public final List<Row> oreWalls = new ArrayList<>();
        public final List<Row> floors = new ArrayList<>();
        public final List<Row> bonuses = new ArrayList<>();
        /** 永久遮挡者 top（压住矿物/可采地板的，按格数降序） */
        public final List<Row> blockers = new ArrayList<>();
        /** 认不出的内容（按格数降序） */
        public final List<Unk> unknowns = new ArrayList<>();
        public int unkOre, unkFloor, unkBonus;
        /** 定义表的来源（技术细节层） */
        public List<String> notes = new ArrayList<>();
        public long millis;
        /** 计数那一段的微秒数（毫秒粒度在 PC 上量不出来：一张图只要几百微秒） */
        public long micros;
    }

    /** 统计一张地图。{@code tiles} 必须是**完整地图**（不是 preview，见 {@link MapLoad#decodeFull}） */
    public static Result analyze(MsavTiles.Tiles t, Table tab, Names nm) {
        long t0 = System.nanoTime();
        Result r = new Result();
        if (t == null || !t.ok()) {
            r.error = "地图读不出来";
            return r;
        }
        if (tab == null) {
            r.error = "内容定义表读不出来";
            return r;
        }
        r.ok = true;
        r.width = t.width;
        r.height = t.height;
        r.cells = t.width * t.height;
        r.notes = new ArrayList<>(tab.notes);

        final int m = t.blockNames.size();
        final int n = r.cells;
        // ★ 预计算：每个 content id 的类别与遮挡类别。循环里**只有 int 数组**（§61.5：15.31 → 1.64 ms）
        //   category 用**位掩码**：一格可以同时是"可采地板"和"加成地板"
        //   （沙地既掉沙、又含水 —— 第 82 轮的样例输出里它两段都出现，这是**对的**，不是重复计数）
        final int ORE = 1, ORE_WALL = 2, MINE_FLOOR = 4, BONUS_FLOOR = 8;
        byte[] cat = new byte[m];
        byte[] occl = new byte[m];              // 0 露天 1 可拆 2 永久 3 未知
        Def[] defs = new Def[m];
        int airId = t.airId();
        for (int id = 0; id < m; id++) {
            String name = t.blockNames.get(id);
            Def d = tab.row(name);
            defs[id] = d;
            if (d == null) {
                occl[id] = (byte) ("air".equals(name) ? 0 : 3);
                continue;
            }
            if (d.isOre) cat[id] |= (byte) (d.wallOre ? ORE_WALL : ORE);
            if (d.isFloor) {
                // 两条**独立**的判据（照 Occ3）：可采地板看掉落物、加成地板看属性/移速/伤害
                if (!d.itemDrop.isEmpty() && !d.isOre) cat[id] |= MINE_FLOOR;
                if (d.bonus()) cat[id] |= BONUS_FLOOR;
            }
            occl[id] = (byte) (d.isAir() ? 0 : d.removable() ? 1 : 2);
        }

        // 计数：[类别][遮挡][id]
        int[][] cOre = new int[4][m], cWall = new int[4][m], cFloor = new int[4][m], cBonus = new int[4][m];
        int[] blocker = new int[m];
        int[] unkOreId = new int[m], unkFloorId = new int[m];
        final short[] fl = t.floors, ov = t.ores, bl = t.blocks;
        for (int i = 0; i < n; i++) {
            int o = ov[i], f = fl[i], b = bl[i];
            int oc = (b >= 0 && b < m) ? occl[b] : 3;
            boolean ore = false, mine = false;
            if (o >= 0 && o < m && o != airId) {
                int c = cat[o];
                if ((c & ORE) != 0) {
                    cOre[oc][o]++;
                    ore = true;
                } else if ((c & ORE_WALL) != 0) {
                    cWall[oc][o]++;
                    ore = true;
                } else if (defs[o] == null) {
                    unkOreId[o]++;
                }
            }
            if (f >= 0 && f < m && f != airId) {
                int c = cat[f];
                if ((c & MINE_FLOOR) != 0) {
                    cFloor[oc][f]++;
                    mine = true;
                }
                if ((c & BONUS_FLOOR) != 0) {
                    cBonus[oc][f]++;
                }
                if (c == 0 && defs[f] == null) {
                    unkFloorId[f]++;
                }
            }
            // 永久遮挡者：只统计它压住了**矿物或可采地板**的那些格（那才是"拿不到"的损失）
            if (oc == 2 && b >= 0 && b < m && (ore || mine)) blocker[b]++;
        }

        build(r.ores, cOre, t.blockNames, defs, nm, false, false, tab.itemNames);
        build(r.oreWalls, cWall, t.blockNames, defs, nm, false, false, tab.itemNames);
        build(r.floors, cFloor, t.blockNames, defs, nm, true, false, tab.itemNames);
        build(r.bonuses, cBonus, t.blockNames, defs, nm, false, true, tab.itemNames);

        // 永久遮挡者 top
        List<Row> bs = new ArrayList<>();
        for (int id = 0; id < m; id++) {
            if (blocker[id] <= 0) continue;
            Row row = new Row();
            Def d = defs[id];
            row.label = blockLabel(nm, t.blockNames.get(id), d, t.blockNames.get(id));
            row.internals.add(t.blockNames.get(id));
            row.buried = blocker[id];
            row.total = blocker[id];
            row.source = d == null ? "认不出" : d.source;
            bs.add(row);
        }
        sort(bs);
        for (int i = 0; i < bs.size() && i < 8; i++) r.blockers.add(bs.get(i));

        // 认不出的内容
        for (int id = 0; id < m; id++) {
            if (unkOreId[id] + unkFloorId[id] <= 0) continue;
            Unk u = new Unk();
            u.name = t.blockNames.get(id);
            u.ore = unkOreId[id];
            u.floor = unkFloorId[id];
            r.unknowns.add(u);
        }
        r.unkOre = sum(unkOreId);
        r.unkFloor = sum(unkFloorId);
        r.unkBonus = 0;
        sortUnk(r.unknowns);
        r.millis = (System.nanoTime() - t0) / 1000000L;
        r.micros = (System.nanoTime() - t0) / 1000L;
        return r;
    }

    /**
     * **方块**的显示名：① 内容 JSON 里的 `localizedName`（最高）② bundle ③ 内部名的可读化。
     *
     * 依据：`ContentParser` 把 JSON 的 `localizedName` 赋给 `UnlockableContent.localizedName`
     * （构造器先按 bundle 取名、JSON 再覆盖）⇒ 模组把中文写在文件里（frost-industry）时，
     * 只认 bundle 就会显示成 `Quartz Deposit` 这种可读化形式。
     */
    static String blockLabel(Names nm, String internal, Def d, String tableName) {
        if (d != null && d.localizedName != null && !d.localizedName.isEmpty()) return d.localizedName;
        String s = nm == null ? "" : nm.block(tableName == null ? internal : tableName);
        if (s != null && !s.isEmpty()) return s;
        return pretty(internal);
    }

    /**
     * **掉落物/物品的显示名** —— 顺序**照抄游戏**：先 `<模组前缀>-<原样>`，再原样。
     *
     * 依据（v160.4 `ContentParser$2` 字节码）：模组 JSON 里的内容引用先按
     * `currentMod.name + "-" + name` 查，查不到才按原样查。
     * ⇒ 模组矿写 `itemDrop: aluminium`，游戏真正掉的是 **`ve-aluminium`**，
     *   而模组自己的译文键正是 `item.ve-aluminium.name`（实测 VE：76 条 `item.*` 译文）。
     * ★ 每个候选键都**先看内容 JSON 里的 `localizedName`、再看 bundle** —— 因为游戏是
     *   "先按这个键定到某个内容，再用那个内容的名字"，而内容的名字以 JSON 优先
     *   （`frost-industry` 的物品：`item.quartz` 无译文，但 `quartz.hjson` 里写着「石英」）。
     *
     * @return 显示名；两边都查不到返回空串（调用方退回方块名或 {@link #pretty}）
     */
    static String itemLabel(Names nm, Def d, Map<String, String> itemNames) {
        if (d == null) return "";
        String raw = d.itemDrop == null ? "" : d.itemDrop.trim();
        if (raw.isEmpty()) return "";
        String[] keys = d.prefix == null || d.prefix.isEmpty()
                ? new String[]{raw}
                : new String[]{d.prefix + "-" + raw, raw};
        for (String k : keys) {
            if (itemNames != null) {
                String v = itemNames.get(k);
                if (v != null && !v.isEmpty()) return v;
            }
            if (nm != null) {
                String v = nm.item(k);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return "";
    }

    private static void build(List<Row> out, int[][] c, List<String> names, Def[] defs, Names nm,
                             boolean floor, boolean bonus, Map<String, String> itemNames) {
        Map<String, Row> byLabel = new LinkedHashMap<>();
        for (int id = 0; id < c[0].length; id++) {
            int total = c[0][id] + c[1][id] + c[2][id] + c[3][id];
            if (total <= 0) continue;
            String internal = names.get(id);
            Def d = defs[id];
            String label, drop = "", attrs = "";
            if (floor) {
                label = blockLabel(nm, internal, d, internal);
                if (d != null && !d.itemDrop.isEmpty()) {
                    // ★ 掉落物名走游戏那条解析顺序（`<模组前缀>-<短名>` → 短名）
                    drop = itemLabel(nm, d, itemNames);
                    if (drop == null || drop.isEmpty()) drop = pretty(d.itemDrop);
                }
            } else if (bonus) {
                label = blockLabel(nm, internal, d, internal);
                attrs = attrWords(d, nm);
            } else {
                // 矿：照 OreBlock 的口径 = 掉落物名（`OreBlock` 构造器里 `localizedName = item.localizedName`，
                // 字节码已核）；认不出来就退回方块名
                String base;
                if (d == null || d.itemDrop.isEmpty()) {
                    base = blockLabel(nm, internal, d, internal);
                } else {
                    base = itemLabel(nm, d, itemNames);
                    if (base == null || base.isEmpty()) base = blockLabel(nm, internal, d, internal);
                }
                if (base == null || base.isEmpty()) base = pretty(internal);
                label = base;
                if (d != null && d.wallOre && nm != null) label = base + nm.wallSuffix();
            }
            Row row = byLabel.get(label);
            if (row == null) {
                row = new Row();
                row.label = label;
                row.drop = drop;
                row.attrs = attrs;
                row.source = d == null ? "认不出" : d.source;
                byLabel.put(label, row);
            }
            row.internals.add(internal);
            row.total += total;
            row.open += c[0][id];
            row.loose += c[1][id];
            row.buried += c[2][id];
            row.unknown += c[3][id];
        }
        out.addAll(byLabel.values());
        sort(out);
    }

    /** 属性键 → 词（`water=-0.3;oil=0.3;` ⇒ 「含水 含油」） */
    static String attrWords(Def d, Names nm) {
        if (d == null || d.attributes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String kv : d.attributes.split(";")) {
            if (kv.isEmpty()) continue;
            String k = kv.split("=")[0].trim();
            if (k.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(nm == null ? k : nm.attr(k));
        }
        return sb.toString();
    }

    /** 排序：可采格数降序 → 总格数降序 → 名字。**可采**优先，因为那才是用户要的数 */
    static void sort(List<Row> rows) {
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                if (a.reachable() != b.reachable()) return b.reachable() - a.reachable();
                if (a.total != b.total) return b.total - a.total;
                return a.label.compareTo(b.label);
            }
        });
    }

    static void sortUnk(List<Unk> rows) {
        Collections.sort(rows, new Comparator<Unk>() {
            @Override public int compare(Unk a, Unk b) {
                if (a.total() != b.total()) return b.total() - a.total();
                return a.name.compareTo(b.name);
            }
        });
    }

    /** 一类资源的总格数 */
    public static int sum(List<Row> rows, int what) {
        int s = 0;
        for (Row r : rows) {
            s += what == 0 ? r.total : what == 1 ? r.reachable() : what == 2 ? r.buried : r.unknown;
        }
        return s;
    }

    public static final int SUM_TOTAL = 0, SUM_REACH = 1, SUM_BURIED = 2, SUM_UNKNOWN = 3;

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    // ══ 六、极小的 JSON 读取器 ═══════════════════════════════════════════════

    /**
     * 只吃 {@link Hjson#toJsonText} 的产物（**标准 JSON**）的极小读取器。
     *
     * ★ 为什么不用 `org.json`：那会让本类**在 PC 上跑不起来**（android.jar 里是 stub，
     *   一调就抛 `Stub!`）⇒ "拿 460 个语料 + 17 个真模组在 PC 上逐格对照"这条验收就没了。
     *   自带的代价只有这一百行，换的是**同一条代码路径能在 PC 上被验**。
     */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text == null ? "" : text);
            j.ws();
            Object v = j.value();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("JSON 提前结束");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return num();
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                                   // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (i < s.length()) {
                ws();
                String k = s.charAt(i) == '"' ? str() : bare();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("缺冒号");
                i++;
                Object v = value();
                m.put(k, v);
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                throw new IllegalArgumentException("对象没收尾");
            }
            throw new IllegalArgumentException("对象提前结束");
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;                                   // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                throw new IllegalArgumentException("数组没收尾");
            }
            throw new IllegalArgumentException("数组提前结束");
        }

        private String str() {
            StringBuilder sb = new StringBuilder();
            i++;                                   // "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(e); break;
                }
            }
            throw new IllegalArgumentException("字符串没收尾");
        }

        private String bare() {
            int st = i;
            while (i < s.length() && s.charAt(i) != ':' && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
            return s.substring(st, i).trim();
        }

        private Object num() {
            int st = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String v = s.substring(st, i);
            try {
                if (v.indexOf('.') < 0 && v.indexOf('e') < 0 && v.indexOf('E') < 0) {
                    return Long.valueOf(Long.parseLong(v));
                }
                return Double.valueOf(Double.parseDouble(v));
            } catch (Throwable t) {
                return v;
            }
        }
    }
}
