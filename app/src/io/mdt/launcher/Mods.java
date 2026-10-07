package io.mdt.launcher;

import android.content.Context;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组扫描与判定（F13 · **只读**）。
 *
 * ★★ 本类存在的理由：**"为什么这个模组没生效"玩家自己查不出来**，而启动器
 *   同时握着三样东西 —— ① 模组的元数据 ② 游戏自己那个开关（`settings.bin`）
 *   ③ 即将跑的游戏版本。三者一对，就能在**进游戏之前**说出原因。
 *
 * ── 一切的判据都来自游戏源码，本类**逐条照抄**，不发明 ─────────────────────
 * 依据文件（只读参考，官方源码包 `Mindustry-160.4/…/mod/Mods.java`，
 * 159.7 同构）：
 *
 * | 本类的东西 | 游戏里的出处 | 语义 |
 * |---|---|---|
 * | {@link #META_FILES} | `Mods.java:35` | 候选元数据名，**按下标取第一个存在的**（后缀与格式无关） |
 * | {@link #resolveRootName} | `Mods.java:1080` | 该层只有 1 个条目且是目录 ⇒ 下潜一层 |
 * | {@link #internalNameOf} | `Mods.java:1435` | `name` 去色后 `toLowerCase(ROOT)` + 空格→`-` |
 * | {@link #enabledKey} 等 | `Mods.java:1149/1283/1267` | `mod-<internalName>-{enabled,failed,repo}` |
 * | {@link #isAtLeast} | `Version.java:64` | `minGameVersion` 的门 |
 * | {@link #BLACKLISTED} | `Mods.java:37~41` | 硬编码黑名单（`name` 或 `name:version`） |
 * | {@link #resolveDependencies} | `Mods.java:1025~1078` | 依赖排序 + 四种失效状态 |
 * | {@link #gates} | `Mods.java:1156~1165` | 加载门的**逐条**判定 |
 * | {@link #skipModLoading} | `Mods.java:508` + `Vars.java:398` | 上次崩了 + `modcrashdisable`（缺省 true） |
 *
 * ── 三条"看起来该做其实不能做"的事（都写在注释里免得后人踩）────────────────
 *  1. 🔴 **不许按后缀选解析器**：`mod.json` / `mod.hjson` / `plugin.json` / `plugin.hjson`
 *     走**同一段代码**（实测反例：好几个叫 `mod.json` 的包内容其实是 HJSON）。
 *  2. 🔴 **不许用文件名推 settings 键**（实测反例：`eb-wilsontomyitems.zip` → `tmi`、
 *     `tnmspiroctexoprosa1.zip` → `exoprosopa`）⇒ 必须开包读 meta。
 *  3. 🔴 **启停不是改后缀**：改名只对 `.jar`/`.zip` 有效，**对目录形态的模组完全无效**
 *     （目录靠"有 meta 文件"匹配，与名字无关）⇒ 正解是读写 `settings.bin` 里那个键。
 *
 * ⚠️ 静默失败点（游戏侧）：`Mods.java:531 catch(Throwable ignored)` —— 元数据解析失败
 *   **无声**跳过；而类加载失败是**响亮**的（`Failed to load mod file … Skipping.`）。
 *   ⇒ 所以本类**必须把解析失败也报出来**（{@link Info#metaError}），
 *      「日志里没有该模组」既不等于"它没被扫描到"，也不等于"它解析失败"。
 */
public final class Mods {
    private static final String TAG = "MDTLauncher";

    /** `Mods.java:35` —— 顺序即优先级，取第一个存在的 */
    public static final String[] META_FILES = {"mod.json", "mod.hjson", "plugin.json", "plugin.hjson"};

    /**
     * 游戏自己认的**资源目录**（`Mods.java:47` 的 `specialFolders`，去掉 `.git`）。
     * ⚠️ 刻意只用游戏自己那张表：多加一个（比如 `content`/`maps`）就等于我们自己发明判据。
     * 用它们来判"这个包带不带资源"（{@link #DATA}）。
     */
    public static final String[] RESOURCE_DIRS = {"bundles", "sprites", "sprites-override"};

    /** `Vars.java:53` —— 非 Java（脚本）模组的最低 `minGameVersion` 主版本 */
    public static final int MIN_MOD_GAME_VERSION = 136;
    /** `Vars.java:55` —— Java 模组的最低主版本（安卓上带 `classes.dex` 的那种） */
    public static final int MIN_JAVA_MOD_GAME_VERSION = 154;

    /** `Vars.java:61` 附近 + `Mods.java:37~41` 的硬编码黑名单（`name` 或 `name:version`） */
    public static final String[] BLACKLISTED = {
            "ui-lib", "braindustry", "schema",
            "scheme-size:1.0.5", "scheme-size:1.0.4", "scheme-size:1.0.3",
            "scheme-size:1.0.1", "scheme-size:1.0.0", "scheme-size:1.1.0", "scheme-size:1.0.4.1",
            //new patch API as of build 159 breaks older versions of the patch editor
            "patch-editor:1.10.1", "patch-editor:1.10.0", "patch-editor:1.9.5",
            "patch-editor:1.9.4", "patch-editor:1.9.3",
    };

    /** 元数据文本的读取上限 —— 真实样本最大 4.9 KB；超过就是异常包，不猜 */
    private static final int MAX_META_BYTES = 512 * 1024;

    private Mods() {}

    // ══ 纯函数区（自检直接喂字符串验，不碰 Context / 文件系统） ══════════════

    /**
     * ★ `arc.util.Strings.stripColors` 的等价实现 —— **实现已搬到 {@link Colors}**
     * （2026-10-04：为了让"取名字/译文"那一层不依赖任何 Android 类，好在 PC 上用**产品代码**验收）。
     * 这里只留转发，调用方一律不用改；判定规则、颜色名表、那些坑的注释都在 {@link Colors}。
     */
    public static String stripColors(String str) {
        return Colors.strip(str);
    }

    /**
     * ★ `Mods.java:1435` 的 `internalName`。**输入必须是已经去过色的 name**
     *   （游戏在 `:1428` 先 `stripColors`，再由 `:1435` 算），所以这里**不重复去色** ——
     *   两步各有各的判据，混在一起会让"该去色的地方没去"变得看不出来。
     */
    public static String internalNameOf(String cleanedName) {
        if (cleanedName == null) return null;
        return cleanedName.toLowerCase(Locale.ROOT).replace(" ", "-");
    }

    public static String enabledKey(String internalName) {
        return "mod-" + internalName + "-enabled";
    }

    public static String failedKey(String internalName) {
        return "mod-" + internalName + "-failed";
    }

    public static String repoKey(String internalName) {
        return "mod-" + internalName + "-repo";
    }

    /**
     * ★ `Version.java:64` 的 `isAtLeast`：`"159.7"` 读成 `build=159, revision=7`。
     *
     * 🔴 **两条必须照抄的边界**（很容易"顺手改好一点"，那就与游戏分家了）：
     *   ① `build <= 0 || str 为空` ⇒ **返回 true**（"自定义构建"一律放行）；
     *   ② 解析不出数字 ⇒ 当 **0**（`Strings.parseInt(x, 0)`）⇒ `"abc"` 等价于 `"0"`。
     */
    public static boolean isAtLeast(int build, int revision, String str) {
        if (build <= 0 || str == null || str.isEmpty()) return true;
        int dot = str.indexOf('.');
        if (dot != -1) {
            int major = parseInt(str.substring(0, dot));
            int minor = parseInt(str.substring(dot + 1));
            return build > major || (build == major && revision >= minor);
        }
        return build >= parseInt(str);
    }

    /** `Strings.parseInt(str, 0)`：非数字 ⇒ 0（**不抛**，游戏就是这么写的） */
    public static int parseInt(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** `ModMeta.getMinMajor()`（`Mods.java:1438`）：`.` 前那一段，取不出数字 ⇒ 0 */
    public static int minMajorOf(String minGameVersion) {
        String v = (minGameVersion == null || minGameVersion.isEmpty()) ? "0" : minGameVersion;
        int dot = v.indexOf('.');
        return parseInt(dot != -1 ? v.substring(0, dot) : v);
    }

    /**
     * 把一个形如 `159.7` / `2026.09.X37` 的版本串拆成 `{build, revision}`
     * —— 供 {@link #isAtLeast} 用（游戏侧这两个值来自它自己的 `version.properties`）。
     * 拆不出来 ⇒ `{0, 0}`（按上面的边界①，`build<=0` 一律放行）。
     */
    public static int[] parseBuild(String versionLike) {
        String v = versionLike == null ? "" : versionLike.trim();
        int dot = v.indexOf('.');
        if (dot == -1) return new int[]{parseInt(v), 0};
        return new int[]{parseInt(v.substring(0, dot)), parseInt(v.substring(dot + 1))};
    }

    /** 黑名单判定（`Mods.java:1418`）：`name` 或 `name:version` 命中即算 */
    public static boolean isBlacklisted(String name, String version) {
        if (name == null) return false;
        for (String b : BLACKLISTED) {
            if (b.equals(name)) return true;
            if (b.equals(name + ":" + version)) return true;
        }
        return false;
    }

    // ══ 扫描 ═══════════════════════════════════════════════════════════════

    /** 一个模组条目（对齐游戏 `LoadedMod` + `ModMeta` 里**扫描阶段能知道**的那些） */
    public static final class Info {
        /** `mods/` 下的一级条目 */
        public File file;
        public String fileName = "";
        public boolean directory;
        public long bytes;
        /** 命中的元数据文件名（`null` = 一个都没有） */
        public String metaName;
        /** 元数据原文（详情弹窗用；超长会被截断） */
        public String rawMeta;
        /** 解析失败原因（`null` = 成功） */
        public String metaError;

        // ── ★ 说明文件为什么读不出来的**码**（P3，2026-10-05）────────────────────
        //   `MetaInfo.metaReason()` 原来把**我们自己写的中文**原样透传给界面 ⇒ 英文界面下会冒中文，
        //   而"模组缺 mod.json / 格式看不懂"是**很常见**的情况。
        //   ⚠️ `metaError` 里的中文**一个字不动**（它同时是"有没有错"的判据 + 诊断原文）。
        public static final int M_NONE = 0;
        public static final int M_COLON = 1;
        public static final int M_NO_META_HERE = 2;
        public static final int M_NO_META_IN_PACK = 3;
        public static final int M_META_TOO_BIG = 4;
        public static final int M_META_UNREADABLE = 5;
        public static final int M_META_BAD_FORMAT = 6;
        public static final int M_META_NO_NAME = 7;
        public static final int M_META_BAD_JSON = 8;

        /** **所有**码（给自检**遍历**用） */
        public static final int[] ALL_CODES = {M_COLON, M_NO_META_HERE, M_NO_META_IN_PACK,
                M_META_TOO_BIG, M_META_UNREADABLE, M_META_BAD_FORMAT, M_META_NO_NAME,
                M_META_BAD_JSON};

        public int metaErrCode = M_NONE;

        /** 记下"说明文件错在哪"：**码给界面**，中文留给诊断 */
        void metaFail(int code, String zh) {
            metaErrCode = code;
            metaError = zh;
        }

        // —— meta 字段（全部按游戏的 cleanup 规则处理过）——
        public String name;
        public String displayName;
        public String internalName;
        public String version = "0";
        public String minGameVersion = "0";
        public String author;
        public String description;
        public String subtitle;
        public String main;
        public String repo;
        public boolean java;
        public boolean hidden;
        public boolean legacyCompatible;
        public List<String> dependencies = new ArrayList<>();
        public List<String> softDependencies = new ArrayList<>();

        // —— 从包/目录里额外读出来的事实 ——
        /** 安卓上真正被加载的东西：根目录下的 `classes.dex`（`Mods.java:1143`） */
        public boolean hasClassesDex;
        /** `scripts/` 目录存在（JS 模组，`Mods.loadScripts`） */
        public boolean hasScripts;
        /** `scripts/` 下的 `.js` 个数 */
        public int jsCount;
        /** `scripts/main.js` 存在（多个 js 时游戏**只认**它） */
        public boolean hasMainJs;
        /**
         * ★ 包内**条目名含反斜杠**（`scripts\main.js` 这种）的条数。
         *
         * 🔴 为什么要单独统计：游戏侧 `ZipFi` 用**原始条目名**建 `byName` 表，于是归一化后
         *   合成出来的那个 ZipFi 找不到 `ZipEntry`（`entry == null`）⇒ `isDirectory()` 返回 **true**
         *   ⇒ 游戏把 `scripts\main.js` 当**目录**、打 `No main.js found for mod X`。
         *   ⇒ 我们会跟着说"没有 scripts/"（与游戏一致），但用户看到包里明明有 `main.js`
         *   会一头雾水 ⇒ **必须把原因说出来**（Windows 打包工具很常见，`.NET` 的
         *   `ZipFile.CreateFromDirectory` 在 Windows 上产出的就是这种）。
         */
        public int backslashEntries;
        /** 包内 `.class` 个数（桌面版代码 —— 安卓**不认**，见 {@link #willFailJavaLoad()}） */
        public int classFiles;
        /** 有游戏认的资源目录（`bundles` / `sprites` / `sprites-override`，见 {@link #DATA}） */
        public boolean hasResources;
        /** 目录形态的"根"（`resolveRoot` 下潜一层之后那一层）—— 冲突体检按它找 `classes.dex` */
        public File rootDir;
        /** zip 形态的"根前缀"（下潜一层后得到，`""` 或 `"X/"`）—— 同上 */
        public String rootPrefix = "";
        /** `classes.dex` 的字节数（冲突体检报告用；-1 = 没读到） */
        public long dexBytes = -1;

        // —— settings.bin 侧（游戏那个开关）——
        public boolean enabled = true;
        public boolean failed;
        /** `mod-<name>-repo`（覆盖 meta 里的 repo；`Mods.java:1267`） */
        public String settingsRepo;
        public boolean settingsKnown;

        /** 被别的包抢了同一个 internalName（游戏里后写者覆盖前者，见 {@link Scan#problems}） */
        public boolean duplicated;

        /** 目录/包的显示名（不要去色，元数据本身可能带色码） */
        public String title() {
            String t = (displayName != null && !displayName.isEmpty()) ? displayName
                    : (name != null && !name.isEmpty()) ? name : fileName;
            return t;
        }

        /** `显示名  版本` */
        public String titleWithVersion() {
            return title() + "  " + (version == null || version.isEmpty() ? "0" : version);
        }

        /** 供 UI 判断"是不是 Java 类模组"（`Mods.java:1261` isJava） */
        public boolean isJava() {
            return java || main != null || hasClassesDex;
        }

        /**
         * ★ 这个包里**到底有什么**（位掩码）—— 给用户看的"模组类型"。
         *
         * 🔴 判据是**包里真实存在的东西**，不是扩展名、也不是 `mod.json` 里说了什么：
         *   · {@link #JAVA_DEX}：根上有 `classes.dex` —— **安卓唯一能加载的 Java 形态**
         *     （`AndroidLauncher.loadJar` 就是 `new DexClassLoader(jar.getPath(), …)`，
         *     直接拿**整个包**当 dex 容器 ⇒ 没有 `classes.dex` 就没有类可加载）；
         *   · {@link #JAVA_CLASS}：包里有 `.class`（桌面版代码）—— 安卓**不认**，见 {@link #willFailJavaLoad()}；
         *   · {@link #JS}：有 `scripts/`（游戏 `loadScripts` 的触发条件就是这个目录）；
         *   · {@link #DATA}：有游戏自己那三个"特殊目录"（`Mods.java:47` 的 `specialFolders`
         *     去掉 `.git`）= `bundles` / `sprites` / `sprites-override` ⇒ 带资源。
         * ⚠️ **没有任何代码位**（既无 dex 也无 js）时**别叫它"坏模组"** —— 它照样能改内容
         *   （贴图、翻译、地图），只是不带代码。用户嘴里的"json 模组"就是这一类。
         * ★ 为什么值得显示：用户拿到的 `mod.json` / `.js` / `classes.dex` / `.class` 经常混在一个包里，
         *   "为什么这个模组没生效"第一步就是分清它**带了哪几种**（也决定该看哪条门）。
         */
        public int parts() {
            int p = 0;
            if (hasClassesDex) p |= JAVA_DEX;
            if (classFiles > 0) p |= JAVA_CLASS;
            if (hasScripts) p |= JS;
            if (hasResources) p |= DATA;
            return p;
        }

        /**
         * ★★ 这个包**在安卓上会不会加载失败**（判据来自游戏源码链，不是猜）。
         *
         * 游戏侧（`Mods.loadMod`）：门条件是 `(mainFile.exists() || meta.java)`；
         * 其中安卓下 `mainFile = zip.child("classes.dex")`。于是：
         *  · meta 声明了 `java`/`main`，但包里**没有 `classes.dex`** ⇒ 门通过 ⇒ 游戏去
         *    `platform.loadJar()`（安卓 = `DexClassLoader` 吃整个包）⇒ **找不到类** ⇒
         *    异常冒到 `Mods.load()` 的 catch ⇒ **整个模组被跳过**（游戏日志里是
         *    `Failed to load mod file … Skipping.`，模组列表里**根本不出现**）。
         *  · 声明了 java **且**有 `classes.dex` ⇒ 正常。
         *  · 没声明 java ⇒ 门不通过 ⇒ 不会去加载类，**不算失败**（脚本/数据模组走这条）。
         * ⚠️ 实测反例：某个 `逻辑工具v1.1.135.jar`（`java=true` + 150 个 `.class`、无 dex）
         *   正落在这条上 —— 桌面能跑、**安卓会被跳过**（这是真会咬人的一条）。
         */
        public boolean willFailJavaLoad() {
            boolean claimsJava = java || main != null;
            return claimsJava && !hasClassesDex;
        }

        /**
         * ★★ **给用户看**的"读不出说明文件"的原因（2026-10-04 第 86 轮）。
         *
         * ★ 为什么要有它：{@link #metaError} 里混着两类 ——
         *   ① 我们自己写的中文判断（"包里没有说明文件（mod.json 之类）"）—— 直接能用；
         *   ② 异常（`readOne` 的 catch 写成 `类名: 消息`，而 {@link Hjson#failureMessage}
         *      用的还是**全限定名**）⇒ 列表行会渲染成
         *      `⚠ ParseException: io.mdt.launcher.Hjson$ParseException: java.lang.StringIndexOutOfBoundsException: …`。
         *   ⇒ 只翻译第 ② 类（与 {@link MsavMeta#userReason()} / {@link SettingsBin.Result#userReason()} 同一套路）。
         * ⚠️ **不改 {@link #metaError} 原文**：落盘报告与自检看的仍是那个字段。
         */
        // ★ 2026-10-05（P3）：这里的 `metaReason()` **已搬走** —— `Mods.Info` 是纯数据类、
        //   拿不到 `Context`，而这两句会**直接显示在模组详情里**（`mods_warn_meta_fmt`）
        //   ⇒ 现在走 `ModsText.infoMetaReason(ctx, m)`（按"异常形态"翻译，我们自己的中文原样透传）。

        /**
         * 是否"支持多人游戏" —— **用游戏自己的判据与自己的标签**：
         *  · `Mods.java:950` 的 `getModStrings()` = `mods.select(l -> !l.meta.hidden && l.enabled())`
         *    ⇒ 只有 **非 hidden** 的启用模组会被发给服务器做一致性比对（`NetClient.java:108`）；
         *  · `ModsDialog.java:369` 在其它坏状态之后，若 `meta.hidden` 就显示
         *    `@mod.multiplayer.compatible`，中文是「**支持多人游戏**」。
         *  ⇒ `hidden == true`：客户端/服务器**单侧** mod，**不参与**联机校验（支持多人游戏）；
         *     `hidden == false`：联机时服务器必须装**同一个模组且版本一致**（比对的是 `名字:版本`）。
         *  ⚠️ 另一条只能运行期知道的：`Mods.java:1190-1192`「all plugins are hidden implicitly」——
         *    `Plugin` 子类会被游戏**自动**置成 hidden，静态扫描看不出来（详情弹窗里如实说明）。
         */
        public boolean multiSafe() {
            return hidden;
        }

        /**
         * ★ 多脚本却没有 `main.js` ⇒ 游戏的脚本入口找不到，**等于不生效**（又一条"静默"）。
         *
         * 判据**照抄**游戏 `Mods.loadScripts()`：
         * ```java
         * Seq<Fi> allScripts = mod.root.child("scripts").findAll(f -> f.extEquals("js"));
         * Fi main = allScripts.size == 1 ? allScripts.first() : mod.root.child("scripts").child("main.js");
         * ```
         * ⇒ **只有一个 js 时它叫什么名字都行**（向后兼容）；**两个及以上才只认 `main.js`**。
         * ⚠️ 所以这条**必须带 `jsCount >= 2`** —— 只看 `!hasMainJs` 就会把"单脚本模组"整片误报
         * （我第一版正是这么想的，被源码纠正）。
         */
        public boolean noMainScript() {
            return hasScripts && jsCount >= 2 && !hasMainJs;
        }

        public int minMajor() {
            return minMajorOf(minGameVersion);
        }
    }

    /** {@link Info#parts()} 的位：根上有 `classes.dex`（**安卓能加载的 Java**） */
    public static final int JAVA_DEX = 1;
    /** {@link Info#parts()} 的位：包里有 `.class`（桌面版代码，安卓不认） */
    public static final int JAVA_CLASS = 2;
    /** {@link Info#parts()} 的位：有 `scripts/`（JS 脚本） */
    public static final int JS = 4;
    /** {@link Info#parts()} 的位：有游戏认的资源目录（`bundles` / `sprites` / `sprites-override`） */
    public static final int DATA = 8;

    /** 扫描结果 */
    public static final class Scan {
        public final String slot;
        public final File modsDir;
        public final List<Info> mods = new ArrayList<>();
        /** `mods/` 里被游戏**过滤掉的一级条目**（不是 `.jar`/`.zip`，又是没有 meta 的目录） */
        public final List<String> ignored = new ArrayList<>();
        /** `.jar`/`.zip` 形态但读不出 meta 的（游戏的静默失败点，必须报出来） */
        public final List<Info> broken = new ArrayList<>();
        /** 同一个 internalName 被多个包占用 ⇒ 游戏里会互相覆盖 */
        public final List<String> problems = new ArrayList<>();
        /** `settings.bin` 的键表（`null` = 没读到，原因见 {@link #settingsNote}） */
        public SettingsBin.Values settings;
        /** 人读的"设置侧说明"（读成功/不存在/读失败三种，**必须显示给用户**） */
        public String settingsNote = "";
        /** `<数据根>/launchid.dat` 存在 = 上次启动没走到 `finishLaunch`（`Vars.java:398`） */
        public boolean launchIdExists;
        /** `launchid.dat` 存在 **且** `modcrashdisable`（缺省 true）⇒ 游戏会跳过**全部**模组 */
        public boolean skipModLoading;
        /** 设置里有 `mod-*` 键、但对应的包已经不在 `mods/` 里（历史残留，REF §17.4 实测有） */
        public final List<String> orphanKeys = new ArrayList<>();

        Scan(String slot, File modsDir) {
            this.slot = slot;
            this.modsDir = modsDir;
        }

        public String modsDirPath() {
            return modsDir == null ? "?" : modsDir.getAbsolutePath();
        }
    }

    /**
     * 「哪些游戏版本会落到这个槽」+ 其中**最低**的那个 —— `minGameVersion` 比对的基准。
     *
     * ★ 分配规则只有一处（{@link Versions#slotFor}）；取最低是**保守口径**：
     *   槽里只要存在一个更低的版本，这个模组就有可能在它上面跑不起来，
     *   而我们**没法预知用户点哪个**（点哪个版本就按哪个版本跑）。
     * ★ 一个都没有 ⇒ `build = 0` ⇒ 按游戏的边界（`build <= 0` 一律放行）**不误报**。
     */
    public static final class Target {
        /** 去重后的目标版本号（显示用，如 `159.7、160.1`） */
        public final List<String> versions = new ArrayList<>();
        public int build;
        public int revision;
        /** 最低的那个（界面上说"比对的是它"） */
        public String label = "";
        /**
         * ★ 同时也是「**这个槽指向的游戏 APK**」（F10 地图清点要用它列"游戏自带地图"）。
         *
         * 为什么挂在这里、而不让调用方再扫一遍 `Versions.scanAll`：判据是**同一个循环** ——
         * "哪个版本算这个槽的目标"只有一处实现（见 {@link #targetsFor}），
         * 两处各扫一次迟早会分叉成"模组页说 159.7、地图页却去读 160.1 的 APK"。
         * 与 {@link #label} 同源：取 build 最低的那个条目。
         */
        public String apkPath;

        public boolean any() {
            return !versions.isEmpty();
        }
    }

    /** 见 {@link Target}（实现只有这一处：模组页与 dev 口共用） */
    public static Target targetsFor(Context ctx, String slot) {
        Target t = new Target();
        int bestBuild = Integer.MAX_VALUE, bestRev = Integer.MAX_VALUE;
        for (Versions.Entry e : Versions.scanAll(ctx)) {
            if (e == null || !slot.equals(Versions.slotFor(e))) continue;
            // fork 用**基座版本**（数据格式版本）—— 与 F4 冲突判据同源，见 Versions.Entry#formatVersion
            String v = e.formatVersion();
            if (v == null || v.isEmpty() || "?".equals(v)) continue;
            if (!t.versions.contains(v)) t.versions.add(v);
            int[] br = parseBuild(v);
            if (br[0] < bestBuild || (br[0] == bestBuild && br[1] < bestRev)) {
                bestBuild = br[0];
                bestRev = br[1];
                t.label = v;
                t.apkPath = e.apkPath;          // ★ 同一个循环里顺手记下 APK（地图清点要用）
            }
        }
        if (t.label.isEmpty()) {
            t.build = 0;
            t.revision = 0;
        } else {
            t.build = bestBuild;
            t.revision = bestRev;
        }
        return t;
    }

    /**
     * 某个槽的 `settings.bin`（**只算路径**，不 mkdirs、不探测存在性）。
     * ★ 读侧与写侧**共用这一处** —— 两处各拼一次路径，改了槽布局就会一边对一边错（无任何症状）。
     */
    public static File settingsFileOf(Context ctx, String slot) {
        File root = Data.dirOf(ctx, slot);
        return root == null ? null : new File(root, "settings.bin");
    }

    /**
     * 我们改写 `settings.bin` 之前的**备份落点**：`<hub>/settings-backups/slot-<槽名>/`。
     *
     * ★ 为什么**不**写进游戏自己的 `<数据根>/settings_backups/`：那里是游戏的地盘
     *   （它的 `Settings` 会按 `minBackupIntervalMs`/`maxBackups` 自己增删，
     *   我们插进去的份会被它当成自己的备份来回收）。备份是**我们的安全网**，
     *   放 `hub/` 下与游戏数据根平级，两边互不干扰。
     */
    public static File settingsBackupDirOf(Context ctx, String slot) {
        return new File(new File(Data.hubDir(ctx), "settings-backups"), "slot-" + slot);
    }

    /** `<数据根>/launchid.dat`（`Vars.java:398` 的"上次启动没走完"标记） */
    public static File launchIdFileOf(Context ctx, String slot) {
        File root = Data.dirOf(ctx, slot);
        return root == null ? null : new File(root, "launchid.dat");
    }

    /**
     * ★★ F13 第二阶段：**启停一个模组** —— 写游戏自己那个开关（`mod-<内部名>-enabled`）。
     *
     * 🔴 **为什么不是"改文件名"**：改名只对 `.jar`/`.zip` 有效，**对目录形态的模组完全无效**
     *   （目录靠"有 meta 文件"匹配，与名字无关）。游戏自己的开关才是唯一正解。
     *
     * 三道门禁（顺序即优先级）：
     *  ① 游戏进程必须在**没跑**（`Data.gameAlive`）—— 游戏退出时会**整份重写** `settings.bin`；
     *  ② 目标文件读不出来（坏/半截）⇒ **拒绝写入**（绝不覆盖一个我们看不懂的文件）；
     *  ③ 键不存在 + 目标就是默认值（启用）⇒ **不碰文件**（没必要写，写一次就是一次风险）。
     *
     * 之后交给 {@link SettingsBin#applyBool}（备份 → 原子写 → 读回自检 → 不过自动还原）。
     */
    /**
     * ★ 批量启停（「全部启用 / 全部关闭」）。走 {@link SettingsBin#applyBools} ⇒ **一次读写**，
     *   不是循环调单键版（理由见那个方法）。
     *
     * 门禁与单键版**完全一致**，一条都不省：游戏没在跑（`Data.gameAlive`）、
     * `settings.bin` 读得出来（读不出来 ⇒ 拒绝写，绝不覆盖）。
     *
     * @param only 只改这些模组（null = 传进来的整份列表）
     */
    public static SettingsBin.Result setEnabledAll(Context ctx, String slot, boolean on,
                                                   List<Info> only) {
        SettingsBin.Result r = new SettingsBin.Result();
        File file = settingsFileOf(ctx, slot);
        r.file = file;
        if (file == null) {
            r.fail(SettingsBin.Result.E_NO_SLOT_DIR,
                    "拿不到槽「" + slot + "」的目录（外部存储可能没挂载）", slot, null);
            return r;
        }
        if (Data.gameAlive(ctx)) {
            // ★ 2026-10-04：这句现在会**原样出现在用户弹窗的第一层**（`SettingsText.userReason()`），
            //   所以去掉 `:game` / `settings.bin` 这类术语（文案纪律 ③）。
            r.fail(SettingsBin.Result.E_GAME_RUNNING,
                    "游戏正在运行，现在改会被它覆盖 —— 请先退出游戏再改。", null, null);
            return r;
        }
        String[] why = new String[1];
        SettingsBin.Values cur = file.isFile() ? SettingsBin.readSafe(file, why) : null;
        if (file.isFile() && cur == null) {
            r.fail(SettingsBin.Result.E_SETTINGS_UNREADABLE,
                    "这个槽的设置读不出来（" + why[0] + "）⇒ 拒绝改写。\n"
                            + "请先让游戏跑一次（它会重建设置），或从备份恢复后再试。",
                    null, null);
            return r;
        }
        java.util.LinkedHashMap<String, Boolean> map = new java.util.LinkedHashMap<>();
        if (only != null) {
            for (Info m : only) {
                if (m != null && m.internalName != null && !m.internalName.isEmpty()) {
                    map.put(enabledKey(m.internalName), Boolean.valueOf(on));
                }
            }
        }
        if (map.isEmpty()) {
            r.fail(SettingsBin.Result.E_NO_MODS, "没有可改的模组", null, null);
            return r;
        }
        // ★ 「键不存在 = 默认启用」是**模组开关专属**语义 ⇒ 在**这一层**把"本来就对"的键裁掉，
        //   不塞进通用设置层（那里对任意键都必须老实写进去 —— 见 SettingsBin.applyBools 的注释）。
        java.util.LinkedHashMap<String, Boolean> need = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Boolean> e : map.entrySet()) {
            boolean want = e.getValue() != null && e.getValue().booleanValue();
            boolean has = cur != null && cur.has(e.getKey());
            Object old = has ? cur.all().get(e.getKey()) : null;
            if (!has && want) continue;                       // 没写过 + 要启用 = 本来就是启用
            if (old instanceof Boolean && ((Boolean) old).booleanValue() == want) continue;
            need.put(e.getKey(), e.getValue());
        }
        if (need.isEmpty()) {
            r.ok = true;
            r.noop = true;
            r.changeDesc = "这一槽的模组开关本来就是目标值，文件一个字节都没动";
            return r;
        }
        SettingsBin.Result out = SettingsBin.applyBools(file, settingsBackupDirOf(ctx, slot), need);
        if (out.ok && !out.noop) {
            out.changeDesc = (on ? "已全部启用：" : "已全部关闭：") + need.size() + " 个模组 —— "
                    + out.changeDesc;
        }
        return out;
    }

    public static SettingsBin.Result setEnabled(Context ctx, String slot, String internalName,
                                               boolean on) {
        SettingsBin.Result r = new SettingsBin.Result();
        if (internalName == null || internalName.isEmpty()) {
            r.fail(SettingsBin.Result.E_NO_INTERNAL_NAME,
                    "内部名为空 —— 先扫出这个模组再改", null, null);
            return r;
        }
        File file = settingsFileOf(ctx, slot);
        r.file = file;
        if (file == null) {
            r.fail(SettingsBin.Result.E_NO_SLOT_DIR,
                    "拿不到槽「" + slot + "」的目录（外部存储可能没挂载）", slot, null);
            return r;
        }
        if (Data.gameAlive(ctx)) {
            // ★ 同 setEnabledAll：这句会原样进用户弹窗第一层 ⇒ 去术语（2026-10-04）
            r.fail(SettingsBin.Result.E_GAME_RUNNING,
                    "游戏正在运行，现在改会被它覆盖 —— 请先退出游戏再改。", null, null);
            return r;
        }
        String key = enabledKey(internalName);
        String[] why = new String[1];
        SettingsBin.Values cur = file.isFile() ? SettingsBin.readSafe(file, why) : null;
        if (file.isFile() && cur == null) {
            r.fail(SettingsBin.Result.E_SETTINGS_UNREADABLE,
                    "这个槽的设置读不出来（" + why[0] + "）⇒ 拒绝改写。\n"
                            + "请先让游戏跑一次（它会重建设置），或从备份恢复后再试。",
                    null, null);
            return r;
        }
        if (cur != null && !cur.has(key) && on) {
            r.ok = true;
            r.noop = true;
            r.changeDesc = "「" + internalName + "」本来就是启用状态（" + key
                    + " 不存在 ⇒ 默认 true），没有动文件";
            return r;
        }
        SettingsBin.Result out = SettingsBin.applyBool(file, settingsBackupDirOf(ctx, slot), key, on);
        if (out.ok && !out.noop) {
            out.changeDesc = (on ? "已启用" : "已关闭") + "模组「" + internalName + "」 —— "
                    + out.changeDesc;
        }
        return out;
    }

    // ══ F13 第三阶段：模组包导入 / 跨槽复制 ═══════════════════════════════════

    /**
     * 导入 / 复制的结果（人读报告 + 机器可判字段，界面与 dev 口共用一处）。
     */
    public static final class PackResult {
        // ── ★ 错误码（P3，2026-10-05）：`importPackage` / `copyMods` **没有 Context**（12 处调用）
        //   而 `report()` 就是弹窗正文 ⇒ 走「码 + 参数」，文案在 `ModsText.packReason` 里取。
        //   ⚠️ `error` **一个字都不动**（dev 报告 / 自检看它）。
        //   ⚠️ **第一层不许夹带"原因"**（异常原文 / `metaReason` 诊断）；中性参数（路径/文件名）可以。
        public static final int P_NONE = 0;
        public static final int P_SRC_MISSING = 1;        // s1 = 源路径
        public static final int P_SRC_OPEN = 2;
        public static final int P_NO_MODS_DIR = 3;
        public static final int P_NAME_EMPTY = 4;
        public static final int P_NAME_COLON = 5;
        public static final int P_NAME_DOT = 6;
        public static final int P_NAME_EXT = 7;
        public static final int P_MKDIR = 8;              // s1 = 目录
        public static final int P_NAME_TAKEN = 9;         // s1 = 文件名
        public static final int P_NOT_A_MOD = 10;
        public static final int P_IMPORT_FAILED = 11;
        public static final int P_COPY_NO_SRC = 12;
        public static final int P_COPY_NO_DST = 13;
        public static final int P_COPY_SAME = 14;
        public static final int P_COPY_UNREADABLE = 15;
        public static final int P_COPY_NOTHING = 16;

        /** **所有**错误码（给自检**遍历**用：漏映射 = 弹窗里静默空白） */
        public static final int[] ALL_CODES = {
                P_SRC_MISSING, P_SRC_OPEN, P_NO_MODS_DIR, P_NAME_EMPTY, P_NAME_COLON,
                P_NAME_DOT, P_NAME_EXT, P_MKDIR, P_NAME_TAKEN, P_NOT_A_MOD,
                P_IMPORT_FAILED, P_COPY_NO_SRC, P_COPY_NO_DST, P_COPY_SAME,
                P_COPY_UNREADABLE, P_COPY_NOTHING,
        };

        public int errCode = P_NONE;
        public String errS1;

        /** 记下"错在哪"：**码 + 参数给界面**，中文句子留给报告与自检 */
        PackResult fail(int code, String zh, String s1) {
            errCode = code;
            errS1 = s1;
            error = zh;
            return this;
        }

        public boolean ok;
        /** 失败原因（ok=false 时不为 null）—— ⚠️ 给报告/自检看的原文，别直接显示给用户 */
        public String error;
        /** 单包导入：最终落点与文件名 */
        public File dest;
        public String finalName;
        /** 单包导入：校验通过的元数据（界面拿它显示版本与兼容性） */
        public Info meta;
        public long bytes;
        public boolean overwrote;
        /** 批量复制：三份清单 */
        public final List<String> copied = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public final List<String> failedList = new ArrayList<>();
        /** 批量复制：真正复制过去的字节数 */
        public long copiedBytes;

        /**
         * 人读正文。★ P3：**收 `Context`** —— 这是弹窗正文（`ModsActivity` 三处直接塞进 `alert`），
         * 文案全走资源；码为 0 时才退回 {@link #error} 原文（那是异常/诊断，给维护者看的）。
         */
        public String report(Context ctx) {
            StringBuilder sb = new StringBuilder();
            if (!ok && error != null) {
                sb.append(Trans.get(ctx, R.string.pack_report_failed_fmt,
                        ModsText.packReason(ctx, this))).append('\n');
                return sb.toString();
            }
            if (finalName != null) {
                sb.append(Trans.get(ctx, overwrote ? R.string.pack_report_replaced_fmt
                        : R.string.pack_report_imported_fmt, finalName)).append('\n');
                sb.append(Trans.get(ctx, R.string.pack_report_where_fmt,
                        dest == null ? "?" : dest.getAbsolutePath())).append('\n');
                sb.append(Trans.get(ctx, R.string.pack_report_size_fmt,
                        Util.formatSize(bytes))).append('\n');
                if (meta != null) {
                    sb.append(Trans.get(ctx, R.string.pack_report_mod_fmt, meta.title(),
                            meta.version == null ? "0" : meta.version)).append('\n');
                    sb.append(Trans.get(ctx, R.string.pack_report_names_fmt, meta.internalName,
                            meta.minGameVersion)).append('\n');
                }
                return sb.toString();
            }
            sb.append(Trans.get(ctx, R.string.pack_report_copied_fmt, copied.size(),
                    Util.formatSize(copiedBytes))).append('\n');
            if (!skipped.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.pack_report_skipped_fmt, skipped.size(),
                        join(skipped))).append('\n');
            }
            if (!failedList.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.pack_report_failed_list_fmt, failedList.size(),
                        join(failedList))).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 模组包**文件名**检查（不读内容）。
     *  · 游戏只认 `.jar` / `.zip`（`Mods.java:518`）——目录形态另说，这里说的是文件；
     *  · 🔴 **名字里绝不能有冒号**：上游注释 `Mods.java:113` 明说安卓会给文件名加冒号
     *    （`primary:X.jar`）**从而破坏 dexing**。
     */
    /** 文件名检查的结果：**码 + 中文同行**（码给界面、中文给报告）—— 分两处判迟早会漂移 */
    public static final class NameErr {
        public final int code;
        public final String zh;
        NameErr(int code, String zh) { this.code = code; this.zh = zh; }
    }

    public static NameErr checkPackName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new NameErr(PackResult.P_NAME_EMPTY, "文件名为空");
        }
        String n = name.trim();
        if (n.indexOf(':') >= 0) {
            return new NameErr(PackResult.P_NAME_COLON, "文件名里有冒号，游戏加载不了");
        }
        if (n.startsWith(".")) {
            return new NameErr(PackResult.P_NAME_DOT,
                    "文件名以点开头，游戏不会把它当模组");
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) {
            return new NameErr(PackResult.P_NAME_EXT,
                    "游戏只加载 .jar / .zip 形态的模组包，这个文件名两个都不是");
        }
        return null;
    }

    /** 我们挪走旧条目时的**中转站**（`<hub>/mods-trash/`）——「挪不删」是本工程的老规矩。
     *  实现（名字编码 / 修剪 / 列表 / 恢复）统一在 {@link Trash}，这里只是给老调用点留的别名。 */
    public static File trashDirOf(Context ctx) {
        return Trash.modsDir(ctx);
    }

    /** 文件版导入（dev 口 / 自检用） */
    public static PackResult importPackage(Context ctx, File modsDir, File src, boolean overwrite,
                                           File trashDir) {
        if (src == null || !src.isFile()) {
            PackResult r = new PackResult();
            r.fail(PackResult.P_SRC_MISSING, "源文件不存在：" + src, String.valueOf(src));
            return r;
        }
        java.io.FileInputStream in;
        try {
            in = new java.io.FileInputStream(src);
        } catch (Throwable t) {
            PackResult r = new PackResult();
            r.fail(PackResult.P_SRC_OPEN, "打不开源文件：" + t.getMessage(), null);
            return r;
        }
        try {
            return importPackage(ctx, modsDir, src.getName(), in, overwrite, trashDir);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * ★ 把一个模组包**导入到某个槽的 `mods/`**（SAF / 文件都走这里）。
     *
     * 五步（每一步失败都**不留残骸**）：
     *  ① 文件名检查（扩展名 / 冒号 —— 见 {@link #checkPackName}）；
     *  ② 同名先问（`overwrite=false` 时直接拒绝，**绝不静默覆盖**）；
     *  ③ 先落 `.part`（半成品后缀，落在本工程"槽内容"的通用跳过项里）；
     *  ④ ★ **落地之前**按游戏自己的判据验一遍（同一个 {@link #readOne}）——
     *     否则会把一个游戏根本不认的包塞进槽里，用户进游戏才发现"没反应"；
     *  ⑤ 就位（原条目先挪去 `hub/mods-trash/`，改名成功后才删）。
     */
    public static PackResult importPackage(Context ctx, File modsDir, String displayName,
                                           java.io.InputStream in, boolean overwrite,
                                           File trashDir) {
        PackResult r = new PackResult();
        if (modsDir == null) {
            r.fail(PackResult.P_NO_MODS_DIR, "拿不到这个槽的 mods/ 目录", null);
            return r;
        }
        NameErr nameErr = checkPackName(displayName);
        if (nameErr != null) {
            r.fail(nameErr.code, nameErr.zh, null);
            return r;
        }
        final String name = displayName.trim();
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            r.fail(PackResult.P_MKDIR, "建目录失败：" + modsDir.getAbsolutePath(),
                    modsDir.getAbsolutePath());
            return r;
        }
        File dest = new File(modsDir, name);
        if (dest.exists() && !overwrite) {
            r.fail(PackResult.P_NAME_TAKEN,
                    "这个槽的 mods/ 里已经有「" + name + "」了 —— 替换会覆盖原文件，需要先确认", name);
            return r;
        }
        File part = new File(modsDir, name + ".part");
        try {
            Data.deleteTree(part);              // 上一轮中断留下的
            r.bytes = Util.copyStream(in, part);
            Info m = readOne(part);             // ★ 用游戏自己的判据验
            if (m.metaError != null) {
                Data.deleteTree(part);
                // ★ 2026-10-04：走 metaReason()（白话），别把 `类名: 消息` 拼进用户可见的失败原因
                r.fail(PackResult.P_NOT_A_MOD, "这个包不是游戏能加载的模组", null);
                return r;
            }
            m.fileName = name;                  // 报告里说最终名字，而不是 .part
            r.meta = m;
            r.overwrote = dest.exists();
            place(ctx, part, dest, overwrite, trashDir);
            r.dest = dest;
            r.finalName = name;
            r.ok = true;
            return r;
        } catch (Throwable t) {
            Data.deleteTree(part);
            r.fail(PackResult.P_IMPORT_FAILED,
                    t.getClass().getSimpleName() + ": " + t.getMessage(), null);
            return r;
        }
    }

    /**
     * ★ **把一个槽的模组整套复制到另一个槽**（F13 目标里的"跨槽模组视图"那一半）。
     *
     * ⚠️ 两条口径（都是有意的）：
     *  ① **复制 `mods/` 下的全部顶层条目**（跳过 `.part` / `.tmp` / 点开头），
     *     **不是只复制"能加载的模组包"** —— 因为游戏的模组配置就放在
     *     `mods/<模组名>/config.json`（`Mods.getConfigFolder`：`modDirectory.child(load.name)`），
     *     那些目录**没有 meta 文件**、按"候选模组"的口径会被漏掉。这正是 F19 那条教训：
     *     "凡是我们没法重造的用户数据都要带上"，而它没法靠枚举实现。
     *  ② **不复制启停状态**：那是目标槽 `settings.bin` 里的键（本次一个字都不写）⇒
     *     复制过去的模组在目标槽按"键不存在 = 默认启用"生效。要在界面上说清楚。
     */
    public static PackResult copyMods(Context ctx, File fromDir, File toDir, boolean overwrite,
                                      File trashDir) {
        PackResult r = new PackResult();
        if (fromDir == null || !fromDir.isDirectory()) {
            r.fail(PackResult.P_COPY_NO_SRC, "源槽没有 mods/ 目录", null);
            return r;
        }
        if (toDir == null) {
            r.fail(PackResult.P_COPY_NO_DST, "拿不到目标槽的 mods/ 目录", null);
            return r;
        }
        if (fromDir.getAbsolutePath().equals(toDir.getAbsolutePath())) {
            r.fail(PackResult.P_COPY_SAME, "源槽与目标槽是同一个", null);
            return r;
        }
        File[] kids = fromDir.listFiles();
        if (kids == null) {
            r.fail(PackResult.P_COPY_UNREADABLE, "读不到源槽 mods/ 的内容", null);
            return r;
        }
        if (!toDir.exists() && !toDir.mkdirs()) {
            r.fail(PackResult.P_MKDIR, "建目录失败：" + toDir.getAbsolutePath(),
                    toDir.getAbsolutePath());
            return r;
        }
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, kids);
        Collections.sort(sorted, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : sorted) {
            if (Data.contentSkipped(f)) continue;       // .part / .tmp / 点开头
            File dst = new File(toDir, f.getName());
            if (dst.exists() && !overwrite) {
                r.skipped.add(f.getName());
                continue;
            }
            File part = new File(toDir, f.getName() + ".part");
            try {
                Data.deleteTree(part);
                if (f.isDirectory()) {
                    if (!part.mkdirs()) throw new java.io.IOException(Trans.get(ctx, R.string.mods_err_tmp_mkdir));
                    copyTree(ctx, f, part);
                } else {
                    Util.copyFile(f, part);
                    if (part.length() != f.length()) {
                        throw new java.io.IOException(Trans.get(ctx, R.string.mods_err_copy_bytes_fmt,
                                f.getName() + ": " + part.length() + " ≠ " + f.length()));
                    }
                }
                place(ctx, part, dst, overwrite, trashDir);
                r.copied.add(f.getName());
                // ⚠️ 别对**文件**用 `Data.sizeTree` —— 它内部 `listFiles()` 对文件返回 null ⇒ 恒 0
                //   （自检没抓到，是设备上那行"复制完成：20 项，61 B"露的马脚：61 B 只可能是那个目录模组）
                r.copiedBytes += f.isDirectory() ? Data.sizeTree(dst) : dst.length();
            } catch (Throwable t) {
                Data.deleteTree(part);
                r.failedList.add(f.getName() + "：" + t.getMessage());
            }
        }
        r.ok = r.failedList.isEmpty();
        if (!r.ok && r.copied.isEmpty() && r.skipped.isEmpty()) {
            r.fail(PackResult.P_COPY_NOTHING, "一项都没复制成功", null);
        }
        return r;
    }

    /** 递归复制一棵树（只用于跨槽复制；不做 `.part` 命名 —— 那是外层的事） */
    private static void copyTree(Context ctx, File src, File dst) throws java.io.IOException {
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            File to = new File(dst, f.getName());
            if (f.isDirectory()) {
                if (!to.exists() && !to.mkdirs()) throw new java.io.IOException(Trans.get(ctx, R.string.pack_err_mkdir_fmt, to.getAbsolutePath()));
                copyTree(ctx, f, to);
            } else {
                Util.copyFile(f, to);
                if (to.length() != f.length()) {
                    throw new java.io.IOException(Trans.get(ctx, R.string.mods_err_copy_bytes_fmt,
                            f.getName()));
                }
            }
        }
    }

    /**
     * 把已经写好的同目录临时条目 `tmp` 就位成 `dst`。
     *
     * ★★ 原条目**先挪去 trash（挪不删）**，改名成功之后才删 —— 本工程"绝不静默丢数据"的老规矩。
     *
     * 🔴 **rename 跨文件系统会失败**（自检里当场撞到：trash 在内部存储、`mods/` 在外部存储
     *   ⇒ `renameTo` 返回 false）⇒ 那种情况下必须**退化成"复制过去再删原件"**，
     *   绝不能让"挪不删"这句承诺**悄悄降级成"删"**（那正是本项目最忌讳的静默行为）。
     *   复制也失败才允许原地删（并且此时源件通常还在别处）。
     */
    private static void place(Context ctx, File tmp, File dst, boolean overwrite, File trashDir)
            throws java.io.IOException {
        if (!dst.exists()) {
            if (!tmp.renameTo(dst)) {
                throw new java.io.IOException("改名失败：" + tmp.getName() + " → " + dst.getName());
            }
            return;
        }
        if (!overwrite) throw new java.io.IOException("目标已存在");

        File stash = null;
        if (trashDir != null && (trashDir.exists() || trashDir.mkdirs())) {
            // ★ 中转站里的名字带**来源槽**（v2，见 Trash.nameFor）—— 恢复时才能默认放回原槽
            stash = new File(trashDir,
                    Trash.nameFor(Trash.slotLabel(dst.getParentFile()), dst.getName()));
            if (!dst.renameTo(stash)) {
                // 跨文件系统：rename 不动 ⇒ 复制过去（复制不完整就作废这次中转）
                try {
                    if (dst.isDirectory()) {
                        if (!stash.mkdirs()) throw new java.io.IOException("建中转目录失败");
                        copyTree(ctx, dst, stash);
                    } else {
                        Util.copyFile(dst, stash);
                        if (stash.length() != dst.length()) {
                            throw new java.io.IOException("中转拷贝不完整");
                        }
                    }
                } catch (Throwable t) {
                    Data.deleteTree(stash);
                    stash = null;
                }
            }
        }
        if (stash != null) {
            if (!tmp.renameTo(dst)) {
                boolean back = stash.renameTo(dst);
                if (!back) {
                    // 连"放回去"都失败（又是跨文件系统）⇒ 再复制一次
                    try {
                        if (stash.isDirectory()) {
                            if (dst.mkdirs()) copyTree(ctx, stash, dst);
                            back = dst.exists();
                        } else {
                            Util.copyFile(stash, dst);
                            back = dst.length() == stash.length();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                throw new java.io.IOException(back ? "改名失败（原条目已放回）"
                        : "改名失败；原条目留在 " + stash.getAbsolutePath());
            }
            // ★ 成功之后**不删**那份旧条目：它留在 `hub/mods-trash/` 等用户来取
            //   （中转站页面能列出来、能恢复 —— 见 {@link TrashActivity}；只按份数修剪，别让它无限长）
            Trash.prune(trashDir, Trash.KEEP);
            return;
        }
        // 中转站不可用（或中转失败）⇒ 退化成"先删再改名"：源件仍在别处，最坏是用户重来一次
        Data.deleteTree(dst);
        if (!tmp.renameTo(dst)) throw new java.io.IOException("改名失败");
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append("、");
            sb.append(x);
        }
        return sb.toString();
    }

    // ══ F13 第四阶段：模组间冲突体检 ═════════════════════════════════════════

    /**
     * ★★ **"接管型全局钩子"** —— 同一处**全局唯一引用**被多个模组接管时**不能共存**。
     *
     * 🔴 这张表**刻意只有一个条目**（宁可少说，绝不瞎说），来源是实测而不是推测：
     *  · `Vars.ui.logic` 是**全局唯一**的逻辑编辑器引用，多个模组只能显式选一个
     *    （`logicsugar-neon-sync` 技能 `05-editor-conflict-and-coexist.md` 的实测结论，
     *     含"搬浮层面板必然变成僵尸 UI"那条）；
     *  · 在 **dex 层面**，"这个模组会不会去接管逻辑编辑器"的可靠标记是**引用画布类
     *    `mindustry/logic/LCanvas`**（接管者要换画布/读 `canvas.save()`；只加语句卡的模组不碰它）。
     *  · **实测分辨力**（8 个真实包，2026-10-02）：命中者只有
     *    `LogicSugar-v5.2.0.jar` 与 `Neon-vN14.zip`（内含 LogicSugar）；
     *    `blackdeluxecatschematicparse` / `DeepSpace` / `饱和火力` / `PatchEditor` **零命中**。
     * ⚠️ 这条只给**提示**（"可能抢同一个东西"），**判决**要看游戏自己的日志（见 {@link Conflict#logLines}）。
     */
    public static final String[][] GLOBAL_HOOKS = {
            {"逻辑编辑器", "mindustry/logic/LCanvas"},
    };

    /** 冲突体检的结果（人读报告 + 机器可判字段） */
    public static final class Conflict {
        /** "A 内置了 B（B 的主类出现在 A 的 dex 里）" —— **判决性信号** */
        public final List<String> contained = new ArrayList<>();
        /** "A 与 B 都引用了 <全局钩子>" —— **提示性信号**（不排除误报） */
        public final List<String> sharedHooks = new ArrayList<>();
        /** "A 的简介里提到了 B" —— 免费信号（可能只是依赖/配套说明） */
        public final List<String> mentioned = new ArrayList<>();
        /** 游戏自己日志里提到这些模组的行（**决定性**：谁真的接管了、有没有加载失败） */
        public final List<String> logLines = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        /** 统计（报告里给出"扫了多少东西"，免得"没找到"被当成"没扫") */
        public int javaMods;
        public int scannedDex;
        public long scannedBytes;

        public boolean any() {
            return !contained.isEmpty() || !sharedHooks.isEmpty() || !mentioned.isEmpty();
        }

        /**
         * 人读报告。★ P3：**收 `Context`** —— 它同时喂**冲突体检弹窗**（用户可见）与
         * `<hub>/report-mods-conflict.txt`（落盘）；后者会跟着界面语言走（与 `PackResult.report`
         * 同一处置）。行首的 `· ` 项目符号与缩进留在 Java（那是**排版**，写进资源会被 aapt2 剥掉）。
         */
        public String report(Context ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append(Trans.get(ctx, R.string.conflict_scanned_fmt, javaMods,
                    Util.formatSize(scannedBytes))).append('\n');
            if (!contained.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.conflict_head_contained)).append('\n');
                for (String s : contained) sb.append("· ").append(s).append('\n');
                sb.append('\n');
            }
            if (!sharedHooks.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.conflict_head_shared)).append('\n');
                for (String s : sharedHooks) sb.append("· ").append(s).append('\n');
                sb.append('\n');
            }
            if (!mentioned.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.conflict_head_mentioned)).append('\n');
                for (String s : mentioned) sb.append("· ").append(s).append('\n');
                sb.append('\n');
            }
            if (!logLines.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.conflict_head_log)).append('\n');
                for (String s : logLines) sb.append("  ").append(s).append('\n');
                sb.append('\n');
            }
            if (!any() && logLines.isEmpty()) {
                sb.append(Trans.get(ctx, R.string.conflict_none)).append('\n')
                  .append(Trans.get(ctx, R.string.conflict_none_hint)).append('\n');
            }
            for (String n : notes) sb.append(Trans.get(ctx, R.string.conflict_note_fmt, n)).append('\n');
            return sb.toString();
        }
    }

    /**
     * ★★ **模组间冲突体检**（用户问：「有办法检查到 logicsugar 和 neon 同时加载吗」）。
     *
     * 三层判据（从硬到软）：
     *  ① **内置检测（判决）**：把每个模组的主类名（`meta.main` → `pkg/Main`）当**字节串**，
     *     在**别的模组**的 `classes.dex` 里找 ⇒ 命中就是"A 把 B 的代码打包进去了"，
     *     两个都启用 = 同一套实现各抢一次全局引用。
     *  ② **共同全局钩子（提示）**：两个模组都引用 {@link #GLOBAL_HOOKS} 里的类。
     *  ③ **简介提及（线索）**：A 的简介里出现 B 的名字/显示名（免费，但可能只是配套说明）。
     *  ⊙ 另外把**游戏日志里提到这些模组的行**摘出来（`last_log.txt`）—— 那才是**谁真的接管了**的判据。
     *
     * ⚠️ 这是**按需**动作（要读几 MB 的 dex）：界面上挂在「查模组间冲突」一行，不是每次刷新都跑。
     * @param logFile 当前槽的 `last_log.txt`（可为 null；读了就把相关行摘出来）
     */
    public static Conflict findConflicts(Context ctx, List<Info> mods, File logFile) {
        Conflict c = new Conflict();
        // ① 主类模式：meta.main → dex 里的描述符形态（pkg/Main）
        List<Info> javaMods = new ArrayList<>();
        List<String> patterns = new ArrayList<>();     // 与 javaMods 下标对齐的主类模式
        for (Info m : mods) {
            if (m == null || m.main == null || m.main.trim().isEmpty()) continue;
            javaMods.add(m);
            patterns.add(m.main.trim().replace('.', '/'));
        }
        c.javaMods = javaMods.size();

        List<List<Integer>> hits = new ArrayList<>();  // 每个 javaMod 在"自己的 dex"里命中的模式下标
        for (int i = 0; i < javaMods.size(); i++) hits.add(new ArrayList<Integer>());
        List<Boolean> hookHit = new ArrayList<>();     // 每个 javaMod 是否引用各钩子
        List<List<Integer>> hookHits = new ArrayList<>();
        for (int i = 0; i < javaMods.size(); i++) hookHits.add(new ArrayList<Integer>());

        for (int i = 0; i < javaMods.size(); i++) {
            Info m = javaMods.get(i);
            List<byte[]> dummy = null;   // 占位（模式以 Latin-1 字符串比对，见下）
            List<String> pats = new ArrayList<>();
            for (int j = 0; j < patterns.size(); j++) {
                if (j == i) continue;                  // 自己含自己的主类不算冲突
                pats.add(patterns.get(j));
            }
            int ownCount = pats.size();
            for (String[] hook : GLOBAL_HOOKS) pats.add(hook[1]);
            if (pats.isEmpty()) continue;
            java.util.Set<Integer> found;
            try {
                found = scanDex(m, pats);
            } catch (Throwable t) {
                c.notes.add(Trans.get(ctx, R.string.conflict_note_dex_fmt, m.fileName));
                continue;
            }
            c.scannedDex++;
            c.scannedBytes += m.dexBytes;
            for (Integer idx : found) {
                if (idx < ownCount) {
                    int j = otherIndexOf(i, idx, patterns.size());
                    if (j >= 0) {
                        hits.get(i).add(Integer.valueOf(j));
                    }
                } else {
                    hookHits.get(i).add(Integer.valueOf(idx - ownCount));
                }
            }
        }
        // 汇总 ①
        for (int i = 0; i < javaMods.size(); i++) {
            for (Integer jObj : hits.get(i)) {
                int j = jObj.intValue();
                c.contained.add(Trans.get(ctx, R.string.conflict_contained_fmt,
                        javaMods.get(i).title(), javaMods.get(j).title()));
            }
        }
        // 汇总 ②
        for (int h = 0; h < GLOBAL_HOOKS.length; h++) {
            List<String> who = new ArrayList<>();
            for (int i = 0; i < javaMods.size(); i++) {
                if (hookHits.get(i).contains(Integer.valueOf(h))) who.add(javaMods.get(i).title());
            }
            if (who.size() >= 2) {
                c.sharedHooks.add(Trans.get(ctx, R.string.conflict_shared_fmt,
                        hookList(ctx, who), GLOBAL_HOOKS[h][0]));
            }
        }
        // 汇总 ③：简介提及
        for (Info a : mods) {
            if (a == null || a.description == null) continue;
            for (Info b : mods) {
                if (b == null || b == a || b.name == null || b.name.length() < 3) continue;
                if (containsIgnoreCase(a.description, b.name)
                        || (b.displayName != null && b.displayName.length() >= 3
                            && containsIgnoreCase(a.description, b.displayName))) {
                    c.mentioned.add(Trans.get(ctx, R.string.conflict_mentioned_fmt,
                            a.title(), b.title()));
                }            }
        }
        // ⊙ 游戏日志
        if (logFile != null && logFile.isFile() && logFile.length() < 4L * 1024 * 1024) {
            try {
                String[] lines = Util.readText(logFile).split("\n");
                for (String ln : lines) {
                    String t = ln.trim();
                    if (t.isEmpty()) continue;
                    for (Info m : mods) {
                        if (m == null) continue;
                        boolean hit = (m.name != null && m.name.length() >= 3
                                        && containsIgnoreCase(t, m.name))
                                || (m.displayName != null && m.displayName.length() >= 3
                                        && containsIgnoreCase(t, m.displayName));
                        if (hit) {
                            c.logLines.add(t);
                            break;
                        }
                    }
                    if (c.logLines.size() >= 40) break;
                }
            } catch (Throwable t) {
                c.notes.add(Trans.get(ctx, R.string.conflict_note_log));
            }
        }
        // ⚠️ 这里**不再**为每个被内置者加一条"注：" —— 实测 Neon 一个包里有 3 个受害者，
        //    那种"每容器只说一条、还只点名其中一个"的注既啰嗦又容易误读；
        //    上面那行"建议只留一个"已经把该说的说完了（用户要求：短）。
        return c;
    }

    private static int otherIndexOf(int self, int patIndex, int total) {
        // 模式列表里跳过了自己那一个 ⇒ 换算回 javaMods 下标
        return patIndex >= self ? patIndex + 1 : patIndex;
    }

    private static String hookList(Context ctx, List<String> who) {
        StringBuilder sb = new StringBuilder();
        for (String s : who) {
            if (sb.length() > 0) sb.append(Trans.get(ctx, R.string.conflict_join_and));
            sb.append(Trans.get(ctx, R.string.conflict_name_fmt, s));
        }
        return sb.toString();
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return hay != null && needle != null
                && hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * 在一个模组的 `classes.dex` 里**流式**找若干 ASCII 模式（返回命中的模式下标）。
     * ⚠️ 流式 + 小块：dex 可能十几 MB（用户的 `DeepSpace` 就是 10 MB），
     * 一次性读进内存虽然也能跑，但这份代码要在 UI 线程的隔壁跑、且设备内存紧张，别赌。
     */
    private static java.util.Set<Integer> scanDex(Info m, List<String> pats) throws java.io.IOException {
        java.io.InputStream in = null;
        try {
            if (m.directory) {
                File dex = new File(m.rootDir, "classes.dex");
                if (!dex.isFile()) return new java.util.HashSet<>();
                m.dexBytes = dex.length();
                in = new java.io.BufferedInputStream(new java.io.FileInputStream(dex), 1 << 16);
            } else {
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(m.file);
                java.util.zip.ZipEntry ze = zf.getEntry(m.rootPrefix + "classes.dex");
                if (ze == null) {
                    zf.close();
                    return new java.util.HashSet<>();
                }
                m.dexBytes = ze.getSize();
                final java.util.zip.ZipFile fz = zf;
                in = new java.io.BufferedInputStream(zf.getInputStream(ze), 1 << 16) {
                    @Override public void close() throws java.io.IOException {
                        try {
                            super.close();
                        } finally {
                            fz.close();
                        }
                    }
                };
            }
            int maxLen = 0;
            for (String p : pats) maxLen = Math.max(maxLen, p.length());
            byte[] buf = new byte[1 << 16];
            byte[] carry = new byte[Math.max(0, maxLen - 1)];
            int carryLen = 0;
            java.util.Set<Integer> hit = new java.util.HashSet<>();
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] win = new byte[carryLen + n];
                System.arraycopy(carry, 0, win, 0, carryLen);
                System.arraycopy(buf, 0, win, carryLen, n);
                String w = new String(win, "ISO-8859-1");     // 逐字节保真
                for (int i = 0; i < pats.size(); i++) {
                    if (hit.contains(Integer.valueOf(i))) continue;
                    if (w.contains(pats.get(i))) hit.add(Integer.valueOf(i));
                }
                if (hit.size() == pats.size()) break;
                carryLen = Math.min(carry.length, win.length);
                System.arraycopy(win, win.length - carryLen, carry, 0, carryLen);
            }
            return hit;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 扫一个槽的 `mods/`（**只读**：不 mkdirs、不落盘、不改任何东西） */
    // ══ 反向依赖（关掉它会连累谁） ════════════════════════════════════════════

    /**
     * ★★ **谁依赖它**（第④项：禁用前先提醒）。
     *
     * 判据：别的模组的 `dependencies`（必需）或 `softDependencies`（软）里出现了它的**内部名** ——
     * 游戏就是这么比的（`Mods.resolveDependencies` 按 `internalName` 大小写无关地查）。
     *
     * ★ 只算**已启用**的模组：本来就关着的模组不会因为"它依赖的那个被关了"而出问题
     *   （它自己都没在跑）；把关闭的也算进来，用户会看到一堆不存在的「连累」。
     *
     * @return 形如 {"必需：Macro Pro", "软依赖：Logic Helper"} 的清单
     */
    public static List<String> dependents(Context ctx, List<Info> mods, String internalName) {
        List<String> out = new ArrayList<>();
        if (mods == null || internalName == null || internalName.isEmpty()) return out;
        String want = internalName.toLowerCase(Locale.ROOT);
        for (Info m : mods) {
            if (m == null || !m.enabled) continue;
            if (m.internalName != null && m.internalName.equalsIgnoreCase(internalName)) continue;
            if (hasDep(m.dependencies, want)) {
                out.add(Trans.get(ctx, R.string.mods_dep_required_fmt, m.title()));
            } else if (hasDep(m.softDependencies, want)) {
                out.add(Trans.get(ctx, R.string.mods_dep_soft_fmt, m.title()));
            }
        }
        return out;
    }

    private static boolean hasDep(List<String> deps, String lowerName) {
        if (deps == null) return false;
        for (String d : deps) {
            if (d != null && d.trim().toLowerCase(Locale.ROOT).equals(lowerName)) return true;
        }
        return false;
    }

    // ══ 列表的搜索 / 筛选 / 排序（**纯函数**，界面只管显示） ═══════════════════

    /** 排序方式：名称 / 有问题的在前 / 大小 */
    public static final int SORT_NAME = 0, SORT_STATE = 1, SORT_SIZE = 2;

    /**
     * 类型筛选（一档⑤，2026-10-06）：{@link #TYPE_ANY} = 不筛；其余三个是
     * {@link Info#parts()} 那个位掩码的**组合**（见 {@link #TYPE_JAVA}）。
     *
     * ★ 判据只有一句：**"包含"这一类**（`(parts() & want) != 0`）——
     *   混合模组（Java+JS）在"有 Java"与"有脚本"两处都会出现，那是**事实**不是重复。
     * ⚠️ 读不出说明文件的坏包 `parts()` 是 0 ⇒ 选了任一类它都**不出现**
     *   （我们确实不知道它是什么类型，不能替它猜）。
     */
    public static final int TYPE_ANY = 0;
    /** 有 Java 代码：安卓能跑的 `classes.dex`，或只有桌面包那批 `.class` 的（游戏都当"Java 模组"看） */
    public static final int TYPE_JAVA = JAVA_DEX | JAVA_CLASS;
    /** 有脚本（`scripts/`） */
    public static final int TYPE_JS = JS;
    /** 有游戏认的资源目录（bundles / sprites / sprites-override） */
    public static final int TYPE_DATA = DATA;

    /** 这个模组算不算"某一类"（`TYPE_ANY` ⇒ 一律算）。判据的唯一实现。 */
    public static boolean matchesType(Info m, int want) {
        if (want == TYPE_ANY) return true;
        if (m == null) return false;
        return (m.parts() & want) != 0;
    }

    /**
     * ★ "这个模组值不值得看一眼" —— **「只看有问题的」的判据只此一处**。
     *
     *  · 固有毛病（扫描就能看出来，不需要目标版本）：读不出信息 / 安卓会加载失败 /
     *    多脚本却没有 main.js / 和别的包重名 / 上次启动出过错 / 包压得不对；
     *  · 游戏里的状态（要目标版本才算得出来）由调用方通过 {@code st} 传进来：
     *    除「启用」与「被用户关闭」以外**都算**问题（版本不符、缺依赖、依赖失效、内容有错、循环依赖）。
     *    🔴 **「被用户关闭」不算问题** —— 那是用户自己关的，塞进"有问题的"里就是噪声。
     */
    public static boolean isProblem(Info m, State st) {
        if (m == null) return true;
        if (m.metaError != null || m.willFailJavaLoad() || m.noMainScript()
                || m.duplicated || m.failed || m.backslashEntries > 0) {
            return true;
        }
        return st != null && st != State.ENABLED && st != State.DISABLED;
    }

    /** 排序用的小配对 —— 过滤后下标就对不上状态数组了，所以绑在一起走（防"状态串行"这类错） */
    private static final class SortRow {
        final Info m;
        final State st;

        SortRow(Info m, State st) {
            this.m = m;
            this.st = st;
        }
    }

    /**
     * ★ 搜索 + 筛选 + 排序（**纯函数**：不改入参、不碰文件 ⇒ 能单独喂给自检）。
     *
     * @param query        关键词（空 = 不筛）；比 显示名 / 文件名 / 游戏里的名字，忽略大小写
     * @param sort         {@link #SORT_NAME} / {@link #SORT_STATE} / {@link #SORT_SIZE}
     * @param onlyProblems 只看有问题的（见 {@link #isProblem}）
     * @param type         {@link #TYPE_ANY} / {@link #TYPE_JAVA} / {@link #TYPE_JS} / {@link #TYPE_DATA}
     *                     （见 {@link #matchesType}；"包含"语义，不是"只有"）
     * @param states       与 mods 一一对应的状态；可为 null（那就只按固有毛病判）
     */
    public static List<Info> filterAndSort(List<Info> mods, String query, int sort,
                                           boolean onlyProblems, int type, State[] states) {
        List<SortRow> rows = new ArrayList<>();
        if (mods != null) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < mods.size(); i++) {
                Info m = mods.get(i);
                if (m == null) continue;
                State st = (states != null && i < states.length) ? states[i] : null;
                if (onlyProblems && !isProblem(m, st)) continue;
                if (!matchesType(m, type)) continue;
                if (!q.isEmpty() && !matchesQuery(m, q)) continue;
                rows.add(new SortRow(m, st));
            }
        }
        java.util.Collections.sort(rows, new java.util.Comparator<SortRow>() {
            @Override public int compare(SortRow a, SortRow b) {
                if (sort == SORT_SIZE) {
                    return Long.compare(b.m.bytes, a.m.bytes);            // 大的在前
                }
                if (sort == SORT_STATE) {
                    boolean pa = isProblem(a.m, a.st), pb = isProblem(b.m, b.st);
                    if (pa != pb) return pa ? -1 : 1;                     // 有问题的在前
                }
                return a.m.title().compareToIgnoreCase(b.m.title());
            }
        });
        List<Info> out = new ArrayList<>(rows.size());
        for (SortRow r : rows) out.add(r.m);
        return out;
    }

    private static boolean matchesQuery(Info m, String q) {
        String[] fields = {m.title(), m.fileName, m.internalName, m.displayName, m.name};
        for (String s : fields) {
            if (s != null && s.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    public static Scan scan(Context ctx, String slot) {
        File root = Data.dirOf(ctx, slot);
        File dir = root == null ? null : new File(root, "mods");
        Scan s = new Scan(slot, dir);

        // ① 游戏自己的开关表
        File sf = settingsFileOf(ctx, slot);
        if (sf != null && sf.isFile()) {
            String[] why = new String[1];
            s.settings = SettingsBin.readSafe(sf, why);
            if (s.settings != null) {
                // ★ 2026-10-04：这三句会原样出现在模组页的展开卡里（`mods_detail_fmt`），
                //   原来写着 `settings.bin` / `zlib 压缩` / `键不存在 = 默认启用`
                //   —— 违反文案纪律 ③（`settings.bin` 是被点名的禁词）。
                s.settingsNote = Trans.get(ctx, R.string.mods_scan_settings_fmt, s.settings.size());
            } else {
                // ★ 不带 why[0]：那是 readSafe 塞的**裸消息**（异常原文或我们 read() 里的中文）
                //   —— 按「依据不进第一层」的规矩去掉。
                s.settingsNote = Trans.get(ctx, R.string.mods_scan_settings_unreadable);
            }
        } else {
            s.settingsNote = Trans.get(ctx, R.string.mods_scan_settings_none);
        }

        // ② 上次是否崩在启动里（Vars.checkLaunch）
        File lif = launchIdFileOf(ctx, slot);
        s.launchIdExists = lif != null && lif.exists();
        boolean crashDisable = s.settings == null || s.settings.getBool("modcrashdisable", true);
        s.skipModLoading = s.launchIdExists && crashDisable;

        if (dir == null || !dir.isDirectory()) {
            // ⚠️ 只读：**不 mkdirs**（F6 的教训：只看一眼造出的空目录会被下一轮当成"有内容"）
            s.ignored.add(Trans.get(ctx, R.string.mods_scan_no_mods_dir));
            return s;
        }

        File[] kids = dir.listFiles();
        if (kids == null) return s;
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, kids);
        Collections.sort(sorted, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        Map<String, Info> byInternal = new LinkedHashMap<>();
        for (File f : sorted) {
            if (!isCandidate(f)) {
                // 目录且没有 meta ⇒ 游戏**根本不看它**（不是"加载失败"）—— 单独一类，别混
                if (f.isDirectory()) s.ignored.add(f.getName());
                continue;
            }
            Info m = readOne(f);
            if (m.metaError != null) {
                s.broken.add(m);
                continue;       // 解析失败的进 broken；但名字可能已经拿到，下面仍会参与冲突检查
            }
            s.mods.add(m);
            String key = m.internalName == null ? "" : m.internalName;
            Info prev = byInternal.get(key);
            if (prev != null) {
                prev.duplicated = true;
                m.duplicated = true;
                // ★ 2026-10-04：这句会原样出现在模组页「展开卡 → 冲突与问题」里，原来是
                //   `两个包解出同一个 internalName「x」：a.zip 与 b.zip ⇒ 游戏里 `mapping.put()`
                //   后写者覆盖前者（Mods.java:538），只有一个会生效` —— 一句里同时含 camelCase
                //   术语、反引号 markdown、源码文件名+行号（违反文案纪律 ①③⑤）。
                s.problems.add(Trans.get(ctx, R.string.mods_scan_dup_fmt, prev.fileName, m.fileName));
            } else {
                byInternal.put(key, m);
            }
        }

        // ③ 设置里有、包已不在 = 历史残留（用户会看到"设置里明明有它"）
        if (s.settings != null) {
            for (String k : s.settings.keysWithPrefix("mod-")) {
                String nm = modNameOfKey(k);
                if (nm == null) continue;               // modcrashdisable 之类
                if (!byInternal.containsKey(nm)) {
                    s.orphanKeys.add(k);
                }
            }
        }

        // ④ 把设置值贴到每个模组上（**必须在读表之后**，且要能区分"读不到表"）
        for (Info m : s.mods) {
            if (m.internalName == null) continue;
            if (s.settings != null) {
                m.settingsKnown = true;
                m.enabled = s.settings.getBool(enabledKey(m.internalName), true);
                m.failed = s.settings.getBool(failedKey(m.internalName), false);
                m.settingsRepo = s.settings.getString(repoKey(m.internalName), null);
            }
        }
        return s;
    }

    /** 从 `mod-<name>-enabled` 里把 `<name>` 抠出来；不是这三个后缀 ⇒ `null` */
    static String modNameOfKey(String key) {
        if (!key.startsWith("mod-")) return null;
        String[] suffixes = {"-enabled", "-failed", "-repo"};
        for (String suf : suffixes) {
            if (key.length() > ("mod-" + suf).length() && key.endsWith(suf)) {
                return key.substring(4, key.length() - suf.length());
            }
        }
        return null;
    }

    /** `Mods.java:518` 的候选判据（**照抄**）：`.jar` / `.zip` / （目录 且 下潜后有 meta） */
    static boolean isCandidate(File f) {
        if (f == null) return false;
        if (f.isDirectory()) {
            return findMetaInDir(resolveDir(f)) != null;
        }
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") || n.endsWith(".zip");
    }

    /** `Mods.java:1080` 的目录版：该层只有 1 个条目且是目录 ⇒ 下潜一层 */
    static File resolveDir(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null && fs.length == 1 && fs[0].isDirectory()) return fs[0];
        return dir;
    }

    /** 候选元数据名里**第一个存在**的那个（目录形态） */
    static File findMetaInDir(File dir) {
        if (dir == null) return null;
        for (String n : META_FILES) {
            File f = new File(dir, n);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** 读一个候选条目（目录或 zip/jar），**任何异常都收敛成 {@link Info#metaError}** */
    static Info readOne(File f) {
        Info m = new Info();
        m.file = f;
        m.fileName = f.getName();
        m.directory = f.isDirectory();
        m.bytes = m.directory ? Data.sizeTree(f) : f.length();
        if (m.fileName.indexOf(':') >= 0) {
            // Mods.java:113 上游注释：安卓会给文件名加冒号（primary:X.jar）从而**破坏 dexing**
            m.metaFail(Info.M_COLON, "文件名里有冒号，游戏加载不了");
            return m;
        }
        try {
            if (m.directory) {
                File root = resolveDir(f);
                m.rootDir = root;
                File meta = findMetaInDir(root);
                if (meta == null) {
                    m.metaFail(Info.M_NO_META_HERE, "这里没有说明文件（mod.json 之类）");
                    return m;
                }
                m.metaName = meta.getName();
                m.rawMeta = readTextCapped(meta);
                m.hasClassesDex = new File(root, "classes.dex").isFile();
                File scripts = new File(root, "scripts");
                m.hasScripts = scripts.isDirectory();
                if (m.hasScripts) {
                    m.hasMainJs = new File(scripts, "main.js").isFile();
                    File[] js = scripts.listFiles();
                    if (js != null) for (File x : js) {
                        if (x.isFile() && x.getName().toLowerCase(Locale.ROOT).endsWith(".js")) m.jsCount++;
                    }
                }
                m.hasResources = hasResourceDir(root);
                m.classFiles = countClassFiles(root);
            } else {
                readZip(f, m);
            }
            if (m.metaError == null) parseMeta(m);
        } catch (Throwable t) {
            m.metaError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return m;
    }

    /** zip/jar 形态：下潜一层 + 找 meta + 顺手读 classes.dex / scripts */
    private static void readZip(File f, Info m) throws Exception {
        ZipFile zf = new ZipFile(f);
        try {
            String prefix = resolveRootPrefix(zf);
            m.rootPrefix = prefix;
            String metaEntry = null;
            for (String n : META_FILES) {
                if (zf.getEntry(prefix + n) != null) {
                    metaEntry = prefix + n;
                    break;
                }
            }
            if (metaEntry == null) {
                m.metaFail(Info.M_NO_META_IN_PACK, "包里没有说明文件（mod.json 之类）");
                return;
            }
            m.metaName = metaEntry.substring(metaEntry.lastIndexOf('/') + 1);
            ZipEntry ze = zf.getEntry(metaEntry);
            if (ze.getSize() > MAX_META_BYTES) {
                m.metaFail(Info.M_META_TOO_BIG, "说明文件太大，读不了");
                return;
            }
            m.rawMeta = readEntry(zf, ze);
            m.hasClassesDex = zf.getEntry(prefix + "classes.dex") != null;
            // ★ `scripts/` 的存在性**不能**用"有没有 `scripts/` 这个条目"判 ——
            //   真实 zip 大多不写目录条目，而游戏那边（`ZipFi` 构造器 `:37~44`）会为
            //   `scripts/main.js` 这种路径**合成**出中间目录 ⇒ `child("scripts").exists()`
            //   照样为真。所以这里按**前缀扫条目**。
            //   🔴 但**不许**把条目名里的 `\` 归一化成 `/`（本文件这里踩过一次）：
            //   游戏侧对"名字里带反斜杠的条目"有一处**很反直觉**的行为 ——
            //   `byName` 是按**原始条目名**建的，于是归一化后合成的那个 ZipFi 找不到对应
            //   `ZipEntry`（`entry == null`）⇒ `isDirectory()` 返回 **true** ⇒
            //   `main.isDirectory()` 为真 ⇒ 游戏打 `No main.js found for mod X`。
            //   ⇒ 我们若在这里归一化，就会报"有 main.js"、而游戏说"找不到" —— 两份判据分家。
            //   （实测：.NET 的 `ZipFile.CreateFromDirectory` 在 Windows 上产出的正是带 `\` 的条目名。）
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            String sp = prefix + "scripts/";
            while (en.hasMoreElements()) {
                String raw = en.nextElement().getName();
                // ★ 顺手统计"条目名里带反斜杠"的条数（见 Info#backslashEntries 的说明）
                if (raw.indexOf('\\') >= 0) m.backslashEntries++;
                // `.class`（桌面版代码）与资源目录：只做"有没有"的判据、不参与加载判定
                String norm = raw.replace('\\', '/');
                String rel = norm.startsWith(prefix) ? norm.substring(prefix.length()) : norm;
                if (rel.toLowerCase(Locale.ROOT).endsWith(".class")) m.classFiles++;
                int slash = rel.indexOf('/');
                if (slash > 0) {
                    String top = rel.substring(0, slash);
                    for (String res : RESOURCE_DIRS) {
                        if (res.equals(top)) {
                            m.hasResources = true;
                            break;
                        }
                    }
                }
                // 🔴 `scripts/` 这一条**必须用原始条目名**（不许归一化）——
                //   理由见上面那段长注释（游戏会把带 `\` 的条目当成**目录**）。
                //   ⚠️ 我重构这段时就手滑用了 norm，是自检（"反斜杠条目必须与游戏同判"那条）
                //      当场抓住的 —— 别再改回去。
                String n = raw;
                if (!n.startsWith(sp)) continue;
                m.hasScripts = true;
                String rest = n.substring(sp.length());
                if (rest.isEmpty() || rest.endsWith("/")) continue;
                if ("main.js".equals(rest)) m.hasMainJs = true;
                if (rest.toLowerCase(Locale.ROOT).endsWith(".js")) m.jsCount++;
            }
        } finally {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * `Mods.java:1080` 的 zip 版：**该层只有 1 个条目且是目录** ⇒ 下潜一层。
     * 返回要拼在 meta 名字前面的前缀（`""` 或 `"X/"`）。
     */
    static String resolveRootPrefix(ZipFile zf) {
        Set<String> rootNames = new LinkedHashSet<>();
        boolean soleIsDir = false;
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (n.isEmpty() || n.startsWith("__MACOSX/")) continue;
            int slash = n.indexOf('/');
            if (slash < 0) {
                rootNames.add(n);
            } else {
                rootNames.add(n.substring(0, slash));
                if (rootNames.size() == 1) soleIsDir = true;
            }
        }
        if (rootNames.size() == 1) {
            String only = rootNames.iterator().next();
            // 只有它是目录（或以 `/` 结尾）时才下潜；单文件包的"下潜"没有意义
            if (only != null && (soleIsDir || zf.getEntry(only + "/") != null)) {
                return only + "/";
            }
        }
        return "";
    }

    /** 把 meta 文本解析成字段（链路照抄 `Mods.java:1019`：HJSON → 标准 JSON 文本 → 反序列化） */
    static void parseMeta(Info m) {
        if (m.rawMeta == null) {
            m.metaFail(Info.M_META_UNREADABLE, "说明文件读不出来");
            return;
        }
        String json;
        try {
            // ★ 两个 profile 都试：游戏的判定只取决于**目标版本**，而扫描阶段
            //   我们还没有目标版本（同一份 mods 可能被不同版本用）⇒ 先按 160 试，失败再退 159。
            //   ⚠️ 这一步只影响"能不能解出字段"，两侧都失败才算解析失败。
            json = Hjson.toJsonText(m.rawMeta, Hjson.ARC_160);
        } catch (Throwable t160) {
            try {
                json = Hjson.toJsonText(m.rawMeta, Hjson.ARC_159);
                m.metaError = null;
            } catch (Throwable t159) {
                m.metaFail(Info.M_META_BAD_FORMAT, "说明文件的格式看不懂");
                return;
            }
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            m.name = stripColors(stringOf(o, "name"));
            String dn = stringOf(o, "displayName");
            m.displayName = dn == null ? m.name : stripColors(dn);
            m.version = def(stringOf(o, "version"), "0");
            m.minGameVersion = def(stringOf(o, "minGameVersion"), "0");
            m.author = stripColors(stringOf(o, "author"));
            m.description = stripColors(stringOf(o, "description"));
            String sub = stringOf(o, "subtitle");
            m.subtitle = sub == null ? null : stripColors(sub).replace("\n", "");
            m.main = stringOf(o, "main");
            m.repo = stringOf(o, "repo");
            m.java = boolOf(o, "java", false);
            m.hidden = boolOf(o, "hidden", false);
            m.legacyCompatible = boolOf(o, "legacyCompatible", false);
            readStringArray(o, "dependencies", m.dependencies);
            readStringArray(o, "softDependencies", m.softDependencies);
            m.internalName = internalNameOf(m.name);
            if (m.name == null) {
                m.metaFail(Info.M_META_NO_NAME, "说明文件里没写模组名");
            }
        } catch (Throwable t) {
            m.metaFail(Info.M_META_BAD_JSON, "说明文件不是有效的 JSON：" + oneLine(t));
        }
    }

    private static void readStringArray(org.json.JSONObject o, String key, List<String> out) {
        org.json.JSONArray a = o.optJSONArray(key);
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v != null) out.add(String.valueOf(v));
        }
    }

    private static String stringOf(org.json.JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == org.json.JSONObject.NULL) return null;
        return String.valueOf(v);
    }

    private static boolean boolOf(org.json.JSONObject o, String key, boolean def) {
        Object v = o.opt(key);
        return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : def;
    }

    private static String def(String v, String d) {
        return (v == null || v.isEmpty()) ? d : v;
    }

    /** 目录形态：有没有游戏认的资源目录（{@link #RESOURCE_DIRS}） */
    private static boolean hasResourceDir(File root) {
        if (root == null) return false;
        for (String d : RESOURCE_DIRS) {
            if (new File(root, d).isDirectory()) return true;
        }
        return false;
    }

    /** 目录形态：数一数 `.class`（只做"有没有"的判据；不下潜太深，够用即可） */
    private static int countClassFiles(File root) {
        return countClassFiles(root, 0);
    }

    private static int countClassFiles(File dir, int depth) {
        if (dir == null || depth > 8) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isDirectory()) {
                n += countClassFiles(f, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".class")) {
                n++;
            }
        }
        return n;
    }

    private static String readTextCapped(File f) throws Exception {
        long len = f.length();
        if (len > MAX_META_BYTES) throw new IllegalStateException("元数据文件过大（" + len + " B）");
        return Util.readText(f);
    }

    private static String readEntry(ZipFile zf, ZipEntry ze) throws Exception {
        InputStream in = zf.getInputStream(ze);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r, total = 0;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
                total += r;
                if (total > MAX_META_BYTES) throw new IllegalStateException("元数据文件过大");
            }
            return bo.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String oneLine(Throwable t) {
        if (t == null) return "?";
        String s = t.getClass().getSimpleName() + ": " + t.getMessage();
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    // ══ 加载门：逐条说出"为什么它没生效" ═══════════════════════════════════

    /** 一条门条件 */
    public static final class Gate {
        public final String label;
        public final boolean pass;
        /** 补充说明（含"这一条要运行期才知道"这类免责） */
        public final String note;

        Gate(String label, boolean pass, String note) {
            this.label = label;
            this.pass = pass;
            this.note = note;
        }
    }

    /**
     * ★★ 复刻 `Mods.java:1156~1165` 的那条链 —— **逐条**报，别只说"未生效"。
     *
     * 这条链决定的是「**模组类**（`classes.dex`）加不加载」，不是"模组在不在列表里"：
     * 链断了 `mod.main` 就是 null，但 `LoadedMod` 照样进列表（`:1214` 的 return 在 if 之外）
     * ⇒ 纯 JS 模组（只有 `scripts/`）**本来就不需要**这条链，别对它们误报。
     *
     * ⚠️ 有两条**只能运行期知道**（写在这里是因为漏掉它们会让人以为"门都过了却还是没生效"）：
     *   `!skipModCode`（载入过程中的崩溃旗标）与 `initialize`（= 依赖链判定为 enabled）。
     *   前者扫描期恒为 false，后者由 {@link #resolveDependencies} 给出。
     *
     * @param gameBuild    目标游戏 build（如 159）；`<=0` ⇒ 按游戏的边界一律放行
     * @param gameRevision 目标游戏 revision（如 7）
     */
    public static List<Gate> gates(Context ctx, Info m, int gameBuild, int gameRevision) {
        List<Gate> out = new ArrayList<>();
        if (m == null) return out;

        // ★ 这一关的判据就是"安卓到底能不能加载它" —— 必须与行内徽标一致：
        //   只声明了 java、包里却没有 classes.dex 时，游戏会尝试加载然后失败跳过（见 willFailJavaLoad），
        //   所以这时这一关要判**不通过**（早先写成"门通过 ⇒ 绿勾"，与「加载会失败」徽标自相矛盾）。
        boolean canLoad = !m.willFailJavaLoad();
        // ★★ note = **null 表示"通过了、没什么好补充的"** ⇒ 显示层只画一行 ✅。
        //   为什么这么定（2026-10-03 用户：「这个页面也改下」）：原来每关都两行，而第二行
        //   多半是在**复述标题**（`不在游戏的黑名单里` / `不在`）⇒ 六关白占十二行。
        //   label 也不再把数据塞进去（原来是「游戏版本够（它要 160.1，当前 0）」，
        //   没指定版本时那个「当前 0」很怪）—— 数据一律进 note，且只在**失败**时给。
        out.add(new Gate(Trans.get(ctx, R.string.mods_gate_android_load), canLoad,
                canLoad ? null : Trans.get(ctx, R.string.mods_gate_android_load_note)));

        out.add(new Gate(Trans.get(ctx, R.string.mods_gate_enabled), m.enabled,
                m.settingsKnown ? null : Trans.get(ctx, R.string.mods_gate_enabled_note)));

        boolean verOk = isAtLeast(gameBuild, gameRevision, m.minGameVersion);
        final String curVer = gameBuild == 0 ? Trans.get(ctx, R.string.mods_gate_no_version)
                : (gameBuild + (gameRevision == 0 ? "" : "." + gameRevision));
        out.add(new Gate(Trans.get(ctx, R.string.mods_gate_version), verOk,
                verOk ? null : Trans.get(ctx, R.string.mods_gate_version_note_fmt,
                        m.minGameVersion, curVer)));

        // ★ 两个门槛是**两个数**（`Vars.java:53/55`）：Java 模组 154、**脚本 / 数据模组 136**。
        //   都拿"它自己写的 `minGameVersion` 主版本"去比 —— **没写 = 0 = 太老** ⇒ 游戏判 unsupported
        //   （`LoadedMod.enabled()` 为 false ⇒ `loadScripts` / `loadContent` 都不进它），
        //   而日志里**照样**打 `Loading mod: X` ⇒ 症状是"模组明明列出来了，脚本一行没跑"。
        //   🔴 这条原来是 `if(m.isJava() && …)` —— **只判了 Java 那一半**，脚本模组整类漏判。
        //   真机对照（同一份 JS 探针，只改 `minGameVersion` 一个字段，各自在干净槽里跑）：
        //     缺省  ⇒ 日志只有 `Loading mod:`，无脚本输出、无崩溃文件（**静默跳过**）
        //     "140" ⇒ 脚本跑起来、崩溃文件出现
        int minMajor = m.minMajor();
        int need = m.isJava() ? MIN_JAVA_MOD_GAME_VERSION : MIN_MOD_GAME_VERSION;
        boolean majorOk = minMajor >= need || m.legacyCompatible;
        out.add(new Gate(Trans.get(ctx, R.string.mods_gate_minver), majorOk,
                majorOk ? null
                        : (minMajor <= 0
                            ? Trans.get(ctx, R.string.mods_gate_minver_none)
                            : Trans.get(ctx, R.string.mods_gate_minver_old_fmt, minMajor, need))));

        boolean blOk = !isBlacklisted(m.name, m.version);
        out.add(new Gate(Trans.get(ctx, R.string.mods_gate_blacklist), blOk,
                blOk ? null : Trans.get(ctx, R.string.mods_gate_blacklist_note_fmt,
                        m.name + ":" + m.version)));

        out.add(new Gate(Trans.get(ctx, R.string.mods_gate_last_crash), true,
                Trans.get(ctx, R.string.mods_gate_last_crash_note)));
        return out;
    }

    // ══ 依赖解析（`Mods.java:1025~1078` 的复刻） ════════════════════════════

    /** 与游戏 `Mods.ModState` 同名同序（`Mods.java:1472`） */
    public enum State {
        ENABLED, CONTENT_ERRORS, MISSING_DEPENDENCIES, INCOMPLETE_DEPENDENCIES,
        CIRCULAR_DEPENDENCIES, UNSUPPORTED, DISABLED;

        // ★ 2026-10-05（P3）：这里原来**枚举自己带显示文案**（`ENABLED("启用","启用")` …）——
        //   枚举是"码"，文案属于界面 ⇒ 全搬到 `ModsText.stateLabel / stateBadge`（按枚举取资源）。
        //   ⚠️ **常量名与顺序不许改**：它跟游戏 `ModState` 同名同序，而且映射以它为码。
        //   ⚠️ 原来 `label`（句子）/ `badge`（徽标短形式）之分仍然成立：两者**不是同一个东西**
        //      —— 徽标挤在标题右边，太长会把标题挤到折行（真机实测：`与当前游戏版本不兼容` 让
        //      `MI2-Utilities Java  1.16.2` 断成两行）⇒ 只有本来就一样短的那三个才共用一条资源。
    }

    /** 解析结果 */
    public static final class Resolved {
        /** 加载顺序（游戏 `ordered`，先依赖后本体） */
        public final List<String> ordered = new ArrayList<>();
        /** internalName → 状态（`ordered` 里的都是 ENABLED） */
        public final Map<String, State> states = new LinkedHashMap<>();

        public State stateOf(String internalName) {
            State s = states.get(internalName);
            return s == null ? State.ENABLED : s;
        }
    }

    /**
     * ★★ `Mods.resolveDependencies` 的**逐行复刻**（含它那三个容易写错的地方）：
     *   ① 依赖名比对的是 **internalName**（不是显示名）；
     *   ② `softDependencies` 缺了**不**算失效；**存在但被关闭**时只有必需依赖才连坐
     *      —— 而连坐是**递归的**（A 依赖 B、B 依赖 C，C 被关 ⇒ B 与 A 都是 incomplete）；
     *   ③ 循环依赖写进 `invalid` 的是**被再次访问到的那个名字**（不是发起者）；
     *   ④ `visited` 是**共享**的一份、每个顶层键处理完 `clear()` —— 照抄（换成"每次新建"
     *      会让循环依赖的判定点发生位移，报告里的措辞就与游戏不一致了）。
     *
     * ⚠️ 与游戏的一处**有意差异**：游戏遍历 `ObjectMap` 的键序，我们按**传入顺序**
     *   （= 扫描时的文件名序）。顺序只影响"谁被写成循环依赖的名字"，不影响集合；
     *   本方法**保证不抛**（游戏那边是靠调用方吞异常）。
     */
    public static Resolved resolveDependencies(List<Info> mods) {
        Resolved r = new Resolved();
        Map<String, List<String[]>> deps = new LinkedHashMap<>();     // name -> [depName, required]
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        for (Info m : mods) {
            if (m.internalName == null) continue;
            List<String[]> l = new ArrayList<>();
            for (String d : m.dependencies) l.add(new String[]{d, "1"});
            for (String d : m.softDependencies) l.add(new String[]{d, "0"});
            deps.put(m.internalName, l);
            enabled.put(m.internalName, Boolean.valueOf(m.enabled));
        }
        Set<String> ordered = new LinkedHashSet<>();
        Map<String, State> invalid = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String key : deps.keySet()) {
            if (ordered.contains(key)) continue;
            resolve(key, deps, enabled, ordered, invalid, visited);
            visited.clear();
        }
        for (String n : ordered) r.states.put(n, State.ENABLED);
        r.states.putAll(invalid);
        r.ordered.addAll(ordered);
        return r;
    }

    private static boolean resolve(String element, Map<String, List<String[]>> deps,
                                   Map<String, Boolean> enabled, Set<String> ordered,
                                   Map<String, State> invalid, Set<String> visited) {
        visited.add(element);
        List<String[]> mine = deps.get(element);
        if (mine != null) {
            for (String[] d : mine) {
                String name = d[0];
                boolean required = "1".equals(d[1]);
                Boolean on = enabled.get(name);
                boolean depEnabled = (on == null) || on.booleanValue();   // 缺省 true
                if (visited.contains(name) && !ordered.contains(name)) {
                    invalid.put(name, State.CIRCULAR_DEPENDENCIES);
                    return false;
                } else if (deps.containsKey(name)) {
                    boolean sub = ordered.contains(name)
                            || resolve(name, deps, enabled, ordered, invalid, visited);
                    if ((!sub || !depEnabled) && required) {
                        invalid.put(element, State.INCOMPLETE_DEPENDENCIES);
                        return false;
                    }
                } else if (required) {
                    // 依赖不存在：软依赖放过、必需依赖判死
                    invalid.put(element, State.MISSING_DEPENDENCIES);
                    return false;
                }
            }
        }
        ordered.add(element);
        return true;
    }

    /**
     * 把一个模组映射成游戏最终会呈现的状态（`Mods.java:568~578` + `LoadedMod.isSupported()`）。
     * 用于列表徽标：**用户看到的是游戏口径**，不是我们自己的发明。
     */
    public static State stateOf(Info m, Resolved r, int gameBuild, int gameRevision) {
        State s = r.stateOf(m.internalName);
        if (s != State.ENABLED) return s;
        if (!isAtLeast(gameBuild, gameRevision, m.minGameVersion)) return State.UNSUPPORTED;
        if (isBlacklisted(m.name, m.version)) return State.UNSUPPORTED;
        // ★ 门槛分两档（`Vars.java:53/55`）：Java 模组 154、脚本 / 数据模组 136。
        //   ⚠️ 原来这里写的是 `m.isJava() && …` ⇒ **脚本模组整类漏判**：没写 `minGameVersion`
        //      的 JS 模组被游戏判 unsupported（脚本永远不跑），我们这边却显示"启用"。
        //      真机对照见 {@link #gates} 里那段注释。
        int need = m.isJava() ? MIN_JAVA_MOD_GAME_VERSION : MIN_MOD_GAME_VERSION;
        if (m.minMajor() < need && !m.legacyCompatible) return State.UNSUPPORTED;
        if (!m.enabled) return State.DISABLED;
        return State.ENABLED;
    }
}
