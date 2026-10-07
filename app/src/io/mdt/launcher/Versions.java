package io.mdt.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本发现（模块边界：依赖 Config —— 对齐桌面版 storage 的职责起点）。
 *
 * 两个来源：
 *  1. 已安装 —— getInstalledPackages() 被系统限制（只返回自己），但按精确包名
 *     getApplicationInfo() 任意包都能查到（探针 T 系列已验证）
 *     ⇒ 「内置包名白名单 + 用户手填包名」是唯一可行的发现形态；
 *  2. 已导入 —— Config 登记表 + app_hub/import/ 下的私有只读副本（M1，Importer 产出）。
 *
 * ★ 向上兼容纪律：登记表里有、磁盘上没有的条目**跳过、不自动清理**——
 *   用户数据只增不毁，缺失项留给后续版本做显式的「失效标记 + 用户确认清理」。
 */
public final class Versions {
    private Versions() {}

    /**
     * fork 用来声明"我是从哪个官方版本分出来的"的那个文件 / 字段。
     * ⚠️ 实测：官方本体（v146/v158/v160）、Xenon、MDTHub 的 APK 里**都没有**这个文件
     *   ⇒ "存在即 MindustryX 系"（见 {@link #readUpstreamVersion}）。
     */
    static final String ENTRY_MOD_HJSON = "assets/mod.hjson";
    static final String KEY_MIN_GAME_VERSION = "minGameVersion";

    /**
     * APK 里那层"构建前缀"：Mindustry 系（含 fork）的 versionName 形如
     * `8-official-159.7` / `8-official-2026.09.X37` —— 前半截是构建序号 + 发布渠道，
     * 对用户**没有信息量**。它还有两个实际害处：
     *   ① 把真正想看的版本号挤到行尾，正好落进 ellipsize 的裁剪区（F7 那个
     *      "版本号被折叠了"的 bug，诱因就是它太长）；
     *   ② 「继续上次」按钮只有一行，带着它更容易被截断。
     *
     * ★ 规则刻意收紧：第一段必须**纯数字**、第二段必须**以字母开头**，两段各带一个 `-`。
     *   于是 `159.7` / `2026.09.X37` / `1-2-3` 这类真版本号**永远不会被误剥**
     *   （`1-2-3` 的第二段是 `2`，不以字母开头 ⇒ 不匹配）。
     *   ⚠️ **宁可漏剥也不能误剥** —— 剥错了是"版本号显示错"，比"多一个前缀"严重得多。
     */
    private static final Pattern BUILD_PREFIX =
            Pattern.compile("^\\d+-[A-Za-z][A-Za-z0-9]*-");

    /**
     * ★★ 显示层规范化的**唯一实现**（F17 立规矩，F17b 下沉到类级）。
     *
     * 为什么下沉：F17 当时把规则做成了 `Entry.displayVersion()` —— 那是**实例方法**，
     * 只有拿得到 `Entry` 的地方能用。于是「导入完成」的 Toast（手里只有一个裸
     * `JSONObject`）只能写 `optString("version")` ⇒ **又冒出一次"带前缀的原文"**，
     * 跟同一时刻列表行显示的短版本号**两种叫法**（用户：「导入的这个版本号也处理下」）。
     *
     * ⇒ 纪律不是"只能有一个方法"，而是"**只能有一段实现**"：
     *   实现放在这里，凡是有**原始版本串**的地方（有 Entry 的、没 Entry 的）都调它。
     *   ⚠️ 新增任何显示版本号的代码，**先搜这个方法名**，别再手写 substring。
     *
     * ⚠️ 反向纪律：**生产者不许调用它**。`Importer.readVersion()` 拼出的
     *   `8-official-159.7` 是**真实数据**，详情弹窗的「原始版本名」要靠它做对照
     *   —— 数据层保真，显示层才规范，两者不能混。
     *
     * @param raw 原始 versionName（可为 null）
     * @return 显示用版本号；剥不掉就原样返回，**绝不猜**
     */
    public static String displayVersionOf(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || "?".equals(v)) return "?";
        Matcher m = BUILD_PREFIX.matcher(v);
        if (m.find()) {
            String rest = v.substring(m.end()).trim();
            if (!rest.isEmpty()) return rest;
        }
        return v;
    }

    /** 是否剥过前缀 —— 详情弹窗据此决定要不要把原文露出来 */
    public static boolean versionTrimmedOf(String raw) {
        String v = raw == null ? "" : raw.trim();
        return !v.isEmpty() && !v.equals(displayVersionOf(v));
    }

    /**
     * ★★ 同槽冲突里"对手"的描述串（F17c；F20 改了计数的口径）。
     *
     * 为什么需要它：列表行的冲突提示原先**只点名一个对手** —— 组里 3 个版本共用同一个槽时，
     * 每一行都只报"另一个"，用户看到的是**片面**的（以为只有两个人抢，实际是三个）。
     * 需求原文：「**这个共用提示只有一个版本是不是不合适（要是共用的太多也可以用类似
     * XXX等多个版本的样子写）**」。
     *
     * ⇒ 2 个版本：`「A」`（点一个对手就够，也说得清）
     *   3 个及以上：`「A」等 2 个版本` —— 把**数量**说出来。
     *
     * 🔴 **N 是「除自己之外」的版本数，不是总数**（F20 修正）。
     *   这里原来写成"总数（含自己）"，理由是"中文『与张三等三人同行』= 共三人"。
     *   **那个类比是错的**：那句的主语是"我们"（含说话人）；
     *   而 `⚠ 与「A」等 N 个版本共用此槽` 的主语是**本行这一个版本**，
     *   `与` 字已经把"我"和对方分开了 ⇒ N 只能读成"除我之外还有几个"。
     *   需求原文：「**与[XXX]等几个版本共用 这个说法数量算上自己感觉有点奇怪**」。
     *   ⇒ 3 个版本共用时应是 `等 2 个版本`（我 + A + 另一个 = 3，自洽）。
     *
     *   ⚠️ 所以这里的判据是 **`total - 1`**，而不是 `total`。改这个数的时候，
     *      先想清楚"谁在说这句话" —— 这是本条注释存在的全部理由。
     *
     * ⚠️ **引号（中文书名号 / 英文双引号）含在返回值里**（不在 `row_conflict_fmt` 里）—— 否则
     *   "等 N 个版本"会跑到书名号**外面**去，变成 `与「A」等 3 个版本」共用此槽`。
     *   ★ P3 之后这两句是**各自的整句资源**（`versions_peer_one_fmt` / `versions_peer_more_fmt`），
     *     所以"引号跑到外面"这件事在结构上不可能再发生；判据仍在（见 SelfTest ⑥c）。
     *
     * @param firstName 对手的 {@link Entry#displayName()}（组内第一个不是自己的）
     * @param total     该槽里**不同的版本数**（含自己，≥2）；单看"条目数"会把同一版本的
     *                  多个副本算成好几个版本 —— 见 {@link #conflictPeers} 的口径说明
     */
    public static String conflictPeerDesc(Context ctx, String firstName, int total) {
        return total > 2
                ? Trans.get(ctx, R.string.versions_peer_more_fmt, firstName, total - 1)
                : Trans.get(ctx, R.string.versions_peer_one_fmt, firstName);
    }

    /**
     * ★★ 同槽组里，某一版本的**真对手** —— 版本号与自己**不同**的那些（按版本号去重）。
     *
     * 为什么要有这层过滤：分组只按「落在哪个槽」做，于是**同一个游戏的多个副本**
     * （典型：官方 APK 既装了一份、又导入了一份 ⇒ 两个 key、同一个槽、名字还一模一样）
     * 会被算成"互相冲突"，渲染出来是 `⚠ 与「Mindustry  159.7」共用此槽` ——
     * 看着像"你跟你自己抢"。
     * 需求原文：「**同一个版本号共用一个槽挺正常的，没必要提示啊（反正也不会有兼容问题）**」。
     *
     * 那条判断是对的：**共用一个槽会不会出问题，取决于两个条目的数据格式**，
     * 而数据格式由版本号决定 —— 版本号相同 ⇒ 同一个游戏 ⇒ 共用槽就是"存档共享"，
     * 是**特性**不是冲突。所以判据是"**组内存在不同的版本号**"，不是"组内 ≥2 个条目"。
     *
     * ⚠️ 判据用 {@link Entry#formatVersion()}（**数据格式**版本）而不是 `displayVersion()`：
     *   ① 用户说的"同一个**版本号**"，指的是"同一个游戏/同一套存档格式"；
     *   ② `8-official-159.7` 与 `159.7` 本来就该算同一个 —— 那正是 `displayVersionOf`
     *      存在的意义。拿原始串比会让"同一版本的不同写法"漏成冲突（这条有断言钉着）；
     *   ③ ★★ **fork 要用它声明的基座版本** —— MindustryX X37 的显示版本是
     *      `2026.09.X37`，但它基于官方 `160.1`，所以"官方 160.1 + MindustryX X37"是
     *      **同一个格式、不该提示**（需求原文：「使用MDTX对应版本的MDT也可以不弹提示」）。
     *      拿显示版本比会把这一对误判成冲突 —— 这正是本方法改用 `formatVersion()` 的原因。
     *      ⚠️ 两个属性分工：**显示**用 `displayName()`（对方该叫 `MindustryX  2026.09.X37`），
     *      **判据**用 `formatVersion()`。混用任何一边都会出错。
     *
     * 🔴 **比较是【字符串精确相等】，这是用户拍过板的口径，别擅自放宽。**
     *   后果：官方 `160`（`build=160` ⇒ 显示 `160`）与 MindustryX X37（基座 `160.1`）
     *   **仍会提示** —— `160` ≠ `160.1`，只有官方 `160.1` 才匹配。
     *   需求原文：「**就用精确版本号吧**」（当时问的是"要不要放宽到主版本"）。
     *   ★ 这个保守选择有实据支撑：`160.1` 是 `160` 之后的**补丁**，它改没改存档格式
     *     **无法仅凭版本号断定**；而放宽到主版本是"用一个更弱的证据覆盖更强的证据"，
     *     一旦猜错就是**静默的数据互相覆盖**（比多提示一次严重得多）。
     *   ⚠️ 所以这里**故意不做**前缀匹配 / 版本号数值比较 / `startsWith`。改之前先问用户。
     *
     * @param self  组内的一员（自己的格式版本即基准）
     * @param group 落在同一个槽里的**全部**条目（含 self）
     * @return 真对手（同格式版本只留一个代表）；**空 = 不算冲突** ⇒ 调用方不提示、不拦
     */
    public static List<Entry> conflictPeers(Entry self, List<Entry> group) {
        List<Entry> out = new ArrayList<>();
        if (self == null || group == null) return out;
        String mine = self.formatVersion();
        for (Entry p : group) {
            // ★ 不需要单独判"是不是自己" —— 自己的格式版本恒等于 mine，这条已经把它排除了
            if (mine.equals(p.formatVersion())) continue;
            boolean seen = false;
            for (Entry q : out) {
                if (q.formatVersion().equals(p.formatVersion())) { seen = true; break; }
            }
            if (!seen) out.add(p);
        }
        return out;
    }

    /**
     * ★★ 从 APK 里读出它**声明的上游基座版本**（MindustryX 系 fork 才有）。
     *
     * 来源：`assets/mod.hjson` 的 `minGameVersion` 字段。这是 MindustryX 官方构建链
     * 写进去的（`buildPlugins/plugins/mindustryX/buildExt.gradle.kts` 里
     * `minGameVersion: "$upstreamBuild"`），**实测样本**：
     *
     * | APK | `version` | `minGameVersion` |
     * |---|---|---|
     * | `MindustryX-2026.07.X36-Android.apk` | `2026.07.X36` | **`159.7`** |
     * | `MindustryX-2026.09.X37-Android.apk` | `2026.09.X37` | `160.1` |
     * | `MindustryX-2026.09.14.B497-Android.apk` | `2026.09.14.B497` | `160.4` |
     * | `MindustryX-2026.10.02.B502-Android.apk` | `2026.10.02.B502` | `160.5` |
     * | `dexed-MindustryX-2026.04.X31.loader.jar` | `2026.04.X31` | `157` ← **不带补丁号** |
     *
     * ★★ **它一定是"官方 Release 的 tag 名（去掉 `v`）"，因此永远不带 `-beta` 之类的后缀。**
     *   依据（源码级 + 已查证 GitHub）：
     *   ① 构建链拿它**拼下载 URL** —— `loaderMod.gradle.kts`:
     *      `src("https://github.com/Anuken/Mindustry/releases/download/v$upstreamBuild/Mindustry.jar")`
     *      ⇒ 值必须是真实存在的 tag；
     *   ② 而官方 tag **从不带后缀** —— 实测 `v160.5 / v160.4 / … / v159.5 / v121 / v119 / v117.1`
     *      全是纯版本号；beta 只体现在**发布标记**（`prerelease: true`）与 `name`
     *      字段（`"6.0 Build 119 - Beta"`）里。例如 v119 时代 tag 就叫 `v119`。
     *   ⇒ 所以 `minGameVersion` 不可能是 `119-beta` 这种形式，"能不能识别 prerelease"
     *     这个问题在**数据源头**就不成立（不是靠我们的解析兜住的）。
     *   ⚠️ 但解析层仍**不拒绝** `-` 与字母（见 `isVersionLike`）—— 那是为了不把
     *      将来可能的写法误判成"没声明"（宁可认出来、也不静默丢弃）。
     *
     * ★ **MindustryX 自己的 prerelease**（B 系列：`2026.10.02.B502`，由 `build.yml` 的
     *   `RELEASE_VERSION=$(date +'%Y.%m.%d').B${GITHUB_RUN_NUMBER}` + `prerelease: true` 产出）
     *   的 `minGameVersion` 同样是纯版本号（实测 `160.4` / `160.5`）—— **能正常识别**。
     *   注意 B 系列是**它自己的**预发布渠道，与 `minGameVersion` 的取值形式无关。
     *
     * ★ 判据底座可靠：**官方本体（v146/v158/v160）、Xenon、MDTHub 的 APK 里都没有
     *   `assets/mod.hjson`**（实测），所以"有这个文件"本身就等于"这是 MindustryX 系 fork"，
     *   不会把普通 mod 的 `minGameVersion` 语义（"最低要求版本"，不是"基座版本"）误当基座。
     *
     * ⚠️ **失败一律返回 null，绝不抛** —— 调用方会回落到 `displayVersion()`，
     *   也就是退化成"收窄之前"的行为（宁可多提示，不可漏提示）。
     *
     * ⚠️ 代价必须记住：`ZipFile` 只读中央目录 + 一个 ~170 B 的条目 ⇒ **实测 ~2 ms**
     *   （770 条目的包），与 `Compat.probe` 那种"读 9.5 MB dex 再扫"完全不是一个量级
     *   （那个必须后台线程）。
     *   ⇒ 曾经**只在真有共用（同槽 ≥2）时才调用**；第 41 轮改成**全量**，
     *     因为列表行要**逐行**备注基座版本（那是给用户看的信息，不是判据的内部量）。
     *     现状：每次 rescan 读 N 次（N = 条目数，通常 1~10）≈ 2~20 ms。
     */
    public static String readUpstreamVersion(File apk) {
        if (apk == null || !apk.isFile()) return null;
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry ze = zf.getEntry(ENTRY_MOD_HJSON);
            if (ze == null) return null;
            if (ze.getSize() > 64 * 1024) return null;      // 真样本 ~170 B；异常包不猜
            java.io.InputStream in = zf.getInputStream(ze);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int r, total = 0;
                while ((r = in.read(buf)) > 0) {
                    bos.write(buf, 0, r);
                    total += r;
                    if (total > 64 * 1024) return null;
                }
                return parseMinGameVersion(bos.toString("UTF-8"));
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;    // ★ 损坏的 zip / 权限 / 编码：当作"没声明"
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * ★ 纯函数：从 `mod.hjson` 文本里取 `minGameVersion`。**判据本体放在这里**是为了
     * 让自检能直接喂字符串验它（⑥e）—— 塞进 {@link #readUpstreamVersion} 就只能靠
     * "设备上恰好有这个文件"验，而那验不出任何错。
     *
     * 只做**够用**的解析，不引 HJSON 库（也没有可用的：F13 那个解析器还没写，
     * 而官方 hjson-java 在真实数据上 6% 误判 —— 见 REF §36 前的模组解析选型）。
     * 目标文件是我们自己人写的构建产物，格式极简，所以只要覆盖：
     * 行注释（`#` / `//`）、键值分隔的 `:`、值可带引号也可不带、值后可有注释。
     *
     * 🔴 **返回 null 的两种情形（都很重要）**：
     *   ① 没有这个键（官方本体、别的 fork）；
     *   ② 值不**像**版本号 —— 尤其是 gradle 的默认值 **`"custom"`**
     *      （`upstreamBuild ?: "custom"`）：真出现时它是"没设基座"，**不是**"基座叫 custom"。
     *      不拦的话会拿 `custom` 当版本号去比，静默地把两个无关包判成"同格式"。
     *
     * @param hjson `mod.hjson` 全文（可为 null）
     * @return 版本串（如 `160.1`）；不确定时 **null**
     */
    public static String parseMinGameVersion(String hjson) {
        if (hjson == null) return null;
        for (String raw : hjson.split("\n")) {
            String line = raw.indexOf('#') == 0 ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            // ★ 键名**精确**匹配：`minGameVersionX:` / `xminGameVersion:` 都不许命中
            if (!KEY_MIN_GAME_VERSION.equals(line.substring(0, colon).trim())) continue;
            String v = stripComment(line.substring(colon + 1)).trim();
            v = unquote(v);
            return isVersionLike(v) ? v : null;
        }
        return null;
    }

    /**
     * ★ 把"已经能从文件系统拿到的条目"的 `upstreamVersion` 补齐（幂等，读不到也置 `checked`）。
     *
     * 为什么单独一个方法而不是塞进 `scanAll()`：扫描跑在 **UI 线程**（`rescan()`），
     * 而这里要读 APK —— 让"纯扫描"带上 IO 副作用，以后想给 `scanAll` 换线程时会踩到。
     * 分开之后调用点就明确表达"这一步要付 IO 代价"，也便于自检单独驱动。
     *
     * ⚠️ 第 41 轮：调用条件从"同槽 ≥2"放宽到**全量**（列表行要逐行显示基座版本）。
     * 幂等靠 `upstreamChecked`（**读不到也置位** ⇒ 同一批 Entry 内不会重复读，
     * 否则一个没有 `mod.hjson` 的官方包会被读无数次）。
     */
    public static void resolveUpstreamVersions(List<Entry> group) {
        if (group == null) return;
        for (Entry e : group) {
            if (e == null || e.upstreamChecked) continue;
            e.upstreamChecked = true;
            e.upstreamVersion = readUpstreamVersion(e.apkPath == null ? null : new File(e.apkPath));
        }
    }

    /** 值里第一个**引号外**的 `#` 或 `//` 起算注释（`"160.1" # x` ⇒ `"160.1"`） */
    private static String stripComment(String v) {
        boolean inQ = false;
        char q = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (inQ) {
                if (c == q) inQ = false;
                continue;
            }
            if (c == '"' || c == '\'') { inQ = true; q = c; continue; }
            if (c == '#') return v.substring(0, i);
            if (c == '/' && i + 1 < v.length() && v.charAt(i + 1) == '/') return v.substring(0, i);
        }
        return v;
    }

    /** 去掉成对的包裹引号（HJSON 允许 `minGameVersion: 160.1` 不写引号） */
    private static String unquote(String v) {
        if (v.length() >= 2) {
            char a = v.charAt(0), b = v.charAt(v.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    /**
     * ★ "像不像版本号" —— **首字符必须是数字**，其余只允许数字 / 点 / 字母 / `-` / `_`。
     *
     * 这一条专挡 gradle 的默认值 `"custom"`（见 {@link #parseMinGameVersion} 的说明），
     * 顺带把空串、`"?"`、自由文本都挡在外面。**宁可返回 null（退化成多提示）**。
     */
    private static boolean isVersionLike(String v) {
        if (v == null || v.isEmpty()) return false;
        if (!Character.isDigit(v.charAt(0))) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = Character.isDigit(c) || c == '.' || c == '-' || c == '_'
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!ok) return false;
        }
        return true;
    }

    public static class Entry {
        public String pkg;
        public String label;
        public String versionName;
        public String apkPath;
        public long apkSize;
        public boolean imported;
        public String importFile;  // imported: app_hub/import/ 下的文件名
        public String md5 = "";

        /**
         * ★★ APK **自己声明的上游基座版本** —— 只有 MindustryX 系 fork 会声明。
         * 来源 = APK 内 `assets/mod.hjson` 的 `minGameVersion`（见 {@link #readUpstreamVersion}）。
         *
         * ⚠️ 语义是"**这份 fork 是从哪个官方版本分出来的**"，所以它和官方那一版**数据格式相同** ——
         *   这正是"同槽共用要不要提示"该看的那个东西（见 {@link #conflictPeers}）。
         * null = 没声明（官方本体 / 别的 fork / 读失败）⇒ 口径回落到 {@link #displayVersion()}。
         */
        public String upstreamVersion;
        /** 是否**已经尝试读过**（读不到也算试过）—— 防每轮 rescan 重读同一个包 */
        public boolean upstreamChecked;

        /**
         * ★ 版本号显示的三个方法都是**薄包装**，规则本体在
         *   {@link Versions#displayVersionOf}（含"为什么方案要收紧成
         *   `^\d+-[A-Za-z][A-Za-z0-9]*-`"以及"生产者不许调用它"）。
         *   ⚠️ 别在这儿重写一遍正则 —— 实现只能有一处。
         */

        /** 原始 versionName（详情弹窗用它做"界面值从哪来"的对照） */
        public String rawVersion() {
            return versionName == null ? "" : versionName.trim();
        }

        /**
         * 显示用版本号 —— **薄包装**，实现见 {@link Versions#displayVersionOf}。
         */
        public String displayVersion() {
            return displayVersionOf(versionName);
        }

        /** 是否剥过前缀 —— 薄包装，见 {@link Versions#versionTrimmedOf}。 */
        public boolean versionTrimmed() {
            return versionTrimmedOf(versionName);
        }

        /**
         * ★★ **数据格式版本** —— "这个版本写出来的存档是什么格式"的唯一口径。
         *
         * 和 {@link #displayVersion()}（给人看的版本号）**不是一回事**，两者用途不同、
         * 且对 fork 会给出不同的值：
         *
         * | 条目 | displayVersion() | formatVersion() |
         * |---|---|---|
         * | 官方 `8-official-159.7` | `159.7` | `159.7` |
         * | MindustryX `8-official-2026.09.X37` | `2026.09.X37` | **`160.1`**（它声明的基座） |
         *
         * ⇒ 于是"官方 160.1"与"MindustryX X37"算**同一个格式** —— 需求原文：
         *   「**使用MDTX对应版本的MDT也可以不弹提示**」。
         *
         * ⚠️ 显示（`displayName()`）与判据（本方法）**必须分开**：对方在界面上该叫
         *   `MindustryX  2026.09.X37`（用户认这个名字），而不是它基于哪个版本。
         */
        public String formatVersion() {
            String u = upstreamVersion == null ? "" : upstreamVersion.trim();
            return u.isEmpty() ? displayVersion() : u;
        }

        /**
         * ★★ 「基座版本」给**用户看**的那个值 —— 返回 `null` 表示**不该显示**。
         *
         * 列表行的尾注（`MainActivity.buildTitle`）与详情弹窗那一行（`showDetail`）
         * **共用这一处判断** —— 否则两处各判一次"要不要显示"，而不一致时**没有任何症状**
         * （§35.11 的老坑：同一显示规则两处实现）。
         *
         * ⚠️ 两种**不显示**的情形，都是有意的：
         *   ① **没声明** —— 官方本体 / Xenon / MDTHub 的 APK 里根本没有 `assets/mod.hjson`；
         *   ② **声明值 == 显示版本** —— **导入的 fork 就是这种**：它的显示版本本就取自
         *      `version.properties` 的上游 build（`Importer.readVersion`），与 `minGameVersion`
         *      同源同值 ⇒ 行首已经是 `160.1`，再缀一句"基于 160.1"是**同义反复**。
         *      ⚠️ 这也意味着"列表上看不出它是 fork"是**既有取舍**，不是本方法造成的。
         *
         * @return 形如 `160.1`；不该显示时 `null`
         */
        public String upstreamNote() {
            String u = upstreamVersion == null ? "" : upstreamVersion.trim();
            if (u.isEmpty()) return null;
            if (u.equals(displayVersion())) return null;
            return u;
        }

        /**
         * ★ **统一显示名**：`名称  版本号` —— 列表行 / 「继续上次」按钮 /
         *   详情弹窗标题 / 删除确认 / 同槽冲突点名**共用这一处**。
         *
         * 以前「继续上次」只给名称（`继续上次 · MindustryX`），列表行给名称 + **原始**版本名，
         * 同一个版本两种叫法（用户：「全部统一命名规则，继续上次也显示版本号」）。
         * 分隔用**两个空格**：版本号在列表行里被弱化成副信息（见 MainActivity.buildTitle），
         * 留白让"名称 → 版本号"的层级更清楚；按钮里也用同一串，保证逐字一致。
         *
         * 🔴 **导入身份【不】在这个串里**（用户：「那个（导入）看起来很奇怪，放下面去」）。
         *   旧实现有个 `title() = displayName() + "（导入）"`，后果有三：
         *   ① 竖屏折行 —— `official-159  159.7（导入）` 挤不下，断成 `…159.7（导` + `入）`，
         *      而折行后那段正好最刺眼；
         *   ② **纯冗余** —— 列表行副标题本来就有 `已导入 · ` 前缀（见 bindVersionRow），
         *      同一行上下喊两遍；
         *   ③ 每个用到标题的地方都得在"要标记/不要标记"之间选，选错就是新的不一致源。
         *   ⇒ 标记统一由**副标题**承担；弹窗侧另有自带"导入"字样的标题/正文兜底
         *      （`delete_import_title` = 「删除导入版本？」、`subtitle()` = 「导入副本…」）。
         *
         * ⚠️ 纪律（承 F17b）：`title()` 去掉后缀后**与 `displayName()` 完全等价**
         *   ⇒ 属于"两个入口、一段实现"，**已删除**。别再加回来 —— 要标记就去副标题做。
         */
        public String displayName() {
            return label + "  " + displayVersion();
        }

        /**
         * 详情弹窗正文首两行。★ 导入项这里写「导入副本」—— 它是**主标题不再带
         * 「（导入）」之后**弹窗侧唯一还能说明"这是导入的私有副本"的地方
         * （配合 `detail_imported_note`），别把这两行改掉。
         */
        public String subtitle(Context ctx) {
            return (imported ? Trans.get(ctx, R.string.versions_imported_copy) : pkg) + "\n" + apkPath
                    + " (" + Util.formatSize(apkSize) + ")";
        }

        /**
         * 版本标识（M3 的「版本 → 槽」分配表以此为键）。
         * 已装 = 包名；已导入 = "import:" + 文件名。
         * ⚠️ 键一旦被用户分配就必须稳定 —— 别改成路径或带时间戳的东西。
         */
        public String key() {
            return imported ? ("import:" + importFile) : pkg;
        }
    }

    /**
     * ★★ **"这个版本该用哪个槽"的唯一实现**（F13 从 `MainActivity` 私有方法上收口）。
     *
     * 优先级：显式分配（M3 的「版本 → 槽」）→ F3 的全局默认槽 → 内置 `default`。
     * ★ 为什么必须收口：模组页要用**同一条规则**算出"哪些版本会落到本槽"
     *   （模组住在槽里 ⇒ 只有落到本槽的版本才谈得上加载它们）。
     *   两处各写一份的后果是"主界面说这个版本用 A 槽、模组页却按 B 槽算版本要求"
     *   —— 一个不会有任何报错、只会给出错误警告的分叉。
     */
    public static String slotFor(Entry e) {
        if (e == null) return Data.SLOT_DEFAULT;
        String s = Config.get().slotOf(e.key());
        if (!s.isEmpty()) return s;
        String def = Config.get().defaultSlot();
        return def.isEmpty() ? Data.SLOT_DEFAULT : def;
    }

    /** 按精确包名取 base.apk（未安装返回 null） */
    public static File apkOfPackage(Context ctx, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return new File(ai.sourceDir);
        } catch (PackageManager.NameNotFoundException nn) {
            return null;
        }
    }

    /** 已装（白名单 + 用户手填包名）+ 已导入，合并展示。 */
    public static List<Entry> scanAll(Context ctx) {
        List<Entry> out = new ArrayList<>(scanInstalled(ctx));
        out.addAll(scanImported(ctx));
        return out;
    }

    static List<Entry> scanInstalled(Context ctx) {
        Set<String> pkgs = new LinkedHashSet<>();
        for (String p : Config.BUILTIN_PACKAGES) pkgs.add(p);
        pkgs.addAll(Config.get().knownPackages());
        PackageManager pm = ctx.getPackageManager();
        List<Entry> out = new ArrayList<>();
        for (String p : pkgs) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                PackageInfo pi = pm.getPackageInfo(p, 0);
                Entry e = new Entry();
                e.pkg = p;
                e.label = String.valueOf(ai.loadLabel(pm));
                e.versionName = pi.versionName == null ? "?" : pi.versionName;
                e.apkPath = ai.sourceDir;
                e.apkSize = new File(ai.sourceDir).length();
                out.add(e);
            } catch (PackageManager.NameNotFoundException nn) {
                // 未安装：跳过
            }
        }
        return out;
    }

    static List<Entry> scanImported(Context ctx) {
        List<Entry> out = new ArrayList<>();
        org.json.JSONArray a = Config.get().imports();
        File dir = Importer.importDir(ctx);
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String fn = o.optString("file", "");
            if (fn.isEmpty()) continue;
            File f = new File(dir, fn);
            if (!f.exists()) continue; // 缺失：跳过，不自动清配置
            Entry e = new Entry();
            e.imported = true;
            e.importFile = fn;
            e.pkg = "imported";
            e.label = o.optString("label", fn);
            e.versionName = o.optString("version", "?");
            e.apkPath = f.getAbsolutePath();
            e.apkSize = f.length();
            e.md5 = o.optString("md5", "");
            out.add(e);
        }
        return out;
    }
}
