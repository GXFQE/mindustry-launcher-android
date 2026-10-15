package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件化的**产品侧骨架**：版本登记（active / pending）+ 启动最早点注入 + 安全阀。
 *
 * <h3>为什么注入要放在"进程最早点"（2026-10-11 定案）</h3>
 * S0 实测把热更新的天花板钉死了：**已经加载过的类换不掉**，只有宿主**还没加载过**的同名类
 * 才能被插件顶掉（`prepend` 注入 + 未加载 ⇒ 插件赢）。于是"改插件能改到宿主多少东西"完全
 * 取决于**注入发生在什么时候**：
 * <ul>
 *   <li>打开插件页时才注入（S0 演示那套）⇒ 那时 {@code MainActivity} 一大家子早加载完了
 *       ⇒ 插件只能新增自己的页面，碰不到宿主既有逻辑；</li>
 *   <li>**{@code LauncherApp.attachBaseContext} 就注入**（本类）⇒ 注入之后才第一次被加载的宿主类
 *       全部可以被插件顶掉。</li>
 * </ul>
 * ⇒ 因此"热更新"在这个工程里的正确形态是「**装/换 ⇒ 退出重开生效**」（见 {@link Restart}），
 * 而不是运行期就地替换。
 *
 * <h3>安全阀（早注入是**唯一**能把启动器砖掉的动作，所以它跟早注入是同一件事的两半）</h3>
 * <ol>
 *   <li>注入**之前**先写 {@link #GUARD}（内容 = 本次要注入谁 + 时刻）；</li>
 *   <li>启动真的过了这一关（第一个 Activity {@code onResume}）⇒ {@link #markBootOk} 删掉它；</li>
 *   <li>下次启动若发现 {@code GUARD} 还在 ⇒ 说明**上次带插件启动没走完** ⇒
 *       **这一次一个插件都不注入**，把现场搬进 {@link #LASTFAIL} 并**清空 {@link #ACTIVE}**
 *       （= 插件自动停用，用户可在界面上再打开）。</li>
 * </ol>
 * ★ 与游戏的「启动崩溃后跳过模组」是同一套语义（`launchid.dat` + 整槽跳过）：宁可这次不加载插件，
 * 也要保证启动器起得来。
 *
 * <p>★ 本文件的**字符串字面量一律 ASCII**（门禁 SRC-02：中文只许出现在资源里，或在台账上登记）。
 * 报告文本是给维护者看的证据，与 {@link PluginHost} 同一套规范；用户可见文案走 {@code Trans} + 资源。
 */
final class Plugins {

    private static final String TAG = "MDTLauncher";

    /**
     * `app_hub/plugins/` —— **必须在内部存储**。
     * 外部存储（FUSE）上的 dex 一律被 ART 拒绝（S0 实测 `Writable dex file is not allowed`，
     * 且 {@code setReadOnly()} 在那边恒为 false）⇒ 外部目录只当"投递口"，装进来还得拷进这里。
     */
    static final String DIR = "plugins";

    /** 生效登记：每行 `id=ver` ⇒ 对应 `plugins/<id>/<ver>.apk`。 */
    static final String ACTIVE = "active.txt";

    /** 待生效登记：装了但**这个进程**还用不上（「退出重开」后才搬到 {@link #ACTIVE}）。 */
    static final String PENDING = "pending.txt";

    /**
     * ★ 回滚用：每个 id **最后一次"带着它启动成功"过的版本**（{@link #markBootOk} 时抄下来）。
     * 它是"保留上一份可用插件"这条要求的落点：注入失败被安全阀拦下时，回滚的目标就是它。
     */
    static final String KNOWN = "known.txt";

    /** 本次启动**真的注入进宿主 CL** 的清单（`openInjected` 只能用在这些人身上，见 {@link #injected}）。 */
    static final String INJECTED = "injected.txt";

    /** 结构化记录"上次被安全阀拦下后每个插件怎么了"（界面按它说人话；格式见 {@link #parseSkipLine}）。 */
    static final String LASTSKIP = "lastskip.txt";

    /**
     * 每个版本的**来源**（`id=ver:official|third`，装进来那一刻判一次就记下来）。
     * ★ 为什么记下来而不是每次现算：判据要解析 APK 的签名块，而**启动路径上每一毫秒都要省**
     * （早注入在 `attachBaseContext` 里）。装的时候算一次、之后读登记即可；界面要复核时可以再算。
     */
    static final String TRUST = "trust.txt";

    /** 用户**改过**的处置等级（`id=L1|L2|L3`）；没改过的按来源取默认值（见 {@link PluginTrust}）。 */
    static final String LEVELS = "levels.txt";

    /** L1 的重试计数（制表符 `id \t ver \t 次数`）—— 连败到预算就升档，否则"再试一次"会变成循环。 */
    static final String RETRY = "retry.txt";

    // ---- 内置插件（随本体发放，第 137 轮）--------------------------------------
    //
    // 载体：本体 APK 里的 `assets/plugins/<id>.apk` + **构建期生成**的 `assets/plugins/manifest.txt`
    //       （`id \t 文件名 \t sha256`，见 tools/builtin-manifest.py）。
    // 为什么要释放：ART 只能从**真实文件路径**加载 dex（S0 硬约束）⇒ asset 里的字节必须先落成文件。
    // 「不能卸载」= 界面不给删除入口 + 文件被删/改坏时**下次启动自动放回**。

    /** 内置清单在 assets 里的路径。 */
    static final String BUILTIN_MANIFEST = "plugins/manifest.txt";

    /** 内置插件的登记版本键 = **sha256 前 8 位**（内容变了就是新版本 ⇒ 换内容能自然升级/回滚）。 */
    static String builtinVersion(String sha) {
        if (sha == null || sha.length() < 8) return null;
        return sha.substring(0, 8);
    }

    /**
     * 解析内置清单（**纯函数**）：每行 `id \t 文件名 \t sha256`。
     * 空行 / `#` 注释跳过；段数不对、sha 长度不对的行**整行丢掉**（宁可少一个内置插件，
     * 也不能拿半条记录去动文件）。
     * @return 每条 {id, fileName, sha256}，顺序照文件
     */
    static List<String[]> parseBuiltinManifest(String text) {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : lines(text)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] p = s.split("\t");
            if (p.length != 3) continue;
            String id = p[0].trim(), file = p[1].trim(), sha = p[2].trim();
            if (id.isEmpty() || file.isEmpty() || sha.length() != 64) continue;
            out.add(new String[] { id, file, sha.toLowerCase(java.util.Locale.ROOT) });
        }
        return out;
    }

    /** 读一份 assets 里的文本（没有 ⇒ null）。 */
    static String readAsset(Context ctx, String path) {
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open(path);
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 当前这一版里内置了哪些插件（清单为空 / 没有清单 ⇒ 空表 = 本体没带内置插件）。 */
    static List<String[]> builtins(Context ctx) {
        return parseBuiltinManifest(readAsset(ctx, BUILTIN_MANIFEST));
    }

    /**
     * 这个 id+版本是不是**原生那份内置插件**（清单里有、且文件 sha 逐字相同）。
     * 哈希对不上 ⇒ 不是内置（被换过/写坏），回落签名判据 —— 反正下次启动会被放回原生那份。
     */
    static boolean isBuiltin(Context ctx, String id, String ver) {
        if (id == null || ver == null) return false;
        for (String[] b : builtins(ctx)) {
            if (!b[0].equals(id)) continue;
            if (!ver.equals(builtinVersion(b[2]))) return false;
            return b[2].equalsIgnoreCase(PluginTrust.sha256File(apkOf(ctx, id, ver)));
        }
        return false;
    }

    /** 清单里有这个 id 吗（不管哈希）—— 界面"给不给删除按钮"用它（哈希对不上时会放回，用户也删不掉）。 */
    static boolean isBuiltinId(Context ctx, String id) {
        for (String[] b : builtins(ctx)) if (b[0].equals(id)) return true;
        return false;
    }

    /**
     * 释放 / 放回内置插件（**进程最早点、安全阀之后**调）。
     *
     * <p>每一步：① 文件不在或 sha 不符 ⇒ 从 assets 重写一份只读副本；② 来源记成 `builtin`；
     * ③ **只有从没登记过**（active/pending/known 里都没有）才默认启用 —— 用户手动停用过的内置插件
     * 不能被这次补放悄悄开回来（"不能卸载"不等于"不能停用"）。
     *
     * @return 给启动报告看的一句话（没有内置插件 ⇒ 空串，零成本）
     */
    static String releaseBuiltins(Context ctx) {
        List<String[]> items = builtins(ctx);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String[] b : items) {
            String id = b[0], file = b[1], sha = b[2];
            String ver = builtinVersion(sha);
            if (ver == null) continue;
            File dst = apkOf(ctx, id, ver);
            boolean native_ = sha.equalsIgnoreCase(PluginTrust.sha256File(dst));
            if (!native_) {
                String r = writeBuiltinCopy(ctx, file, id, ver);
                sb.append(id).append(":release=").append(r).append(' ');
                native_ = sha.equalsIgnoreCase(PluginTrust.sha256File(dst));
            }
            if (!native_) {
                sb.append(id).append(":STILL-BAD ");
                continue;
            }
            File tf = new File(dir(ctx), TRUST);
            write(tf, trustPut(readOrNull(tf), id, ver, PluginTrust.BUILTIN));
            File af = new File(dir(ctx), ACTIVE);
            String actText = readOrNull(af);
            String act = builtinRegisterAction(
                    stateGet(actText, id),
                    stateGet(readOrNull(new File(dir(ctx), PENDING)), id),
                    stateGet(readOrNull(new File(dir(ctx), KNOWN)), id),
                    ver);
            if ("enable".equals(act) || "upgrade".equals(act)) {
                write(af, statePut(actText, id, ver));   // 新内置 ⇒ 默认启用；内容换了 ⇒ 升级（本次就生效）
                sb.append(id).append(':').append(act).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 内置补放之后**要不要动 active**（**纯函数**，自检钉）。
     *
     * <p>三种情况要分清楚，否则"不能卸载"会连带把"升级"也堵死：
     * <ul>
     *   <li>全新（active/known 都没见过）⇒ <b>enable</b>（随本体发的能力默认开着）；</li>
     *   <li>active 里的版本与新内容**不同** ⇒ <b>upgrade</b>（本体带了新版内置插件 ⇒ 换过去；
     *       ⚠️ 这正是第 139 轮补的洞：原来只看"有没有见过"，于是新版会被永远挂在旧版上/停用状态）；</li>
     *   <li>否则 <b>leave</b>：用户手动停用过（known 有、active 没）、或正在 pending（用户正要换版本）⇒
     *       **一律别插手**（"不能卸载" ≠ "不能停用"，也 ≠ "可以擅自替你启用"）。</li>
     * </ul>
     * @return "enable" / "upgrade" / "leave"
     */
    static String builtinRegisterAction(String activeVer, String pendingVer, String knownVer, String newVer) {
        if (pendingVer != null) return "leave";
        if (activeVer == null) return knownVer == null ? "enable" : "leave";
        return activeVer.equals(newVer) ? "leave" : "upgrade";
    }
    /** 把 assets 里那份原封不动写成一个**只读**副本（复用 install 的那套约束：先删再写）。 */
    private static String writeBuiltinCopy(Context ctx, String assetName, String id, String ver) {
        java.io.InputStream in = null;
        try {
            File dst = apkOf(ctx, id, ver);
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return "FAILED mkdirs";
            if (dst.exists() && !dst.delete()) return "FAILED cannot replace";
            in = ctx.getAssets().open("plugins/" + assetName);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            boolean ro = dst.setReadOnly();
            return ro ? "ok" : "ok(rw)";
        } catch (Throwable t) {
            return "FAILED " + brief(t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 🔴 「这一程**没有**注入任何插件（安全阀跳过了）」的标记，只活一次启动。
     *
     * <p>为什么必须有它（第 133 轮真机踩出来的连锁 bug）：{@link #markBootOk} 的判据是
     * "第一个 Activity 到了 onResume"，而**被跳过的那些启动同样满足它** ⇒ 于是那一版**刚崩过的**
     * 插件会被抄进 {@link #KNOWN} 当成"已知可用"；后果是下一步要回滚时**已经没有回滚目标**
     * （`known == was`）⇒ L2 只能退化成停用，用户的插件白丢（L1 那轮实测正是这么串起来的）。
     * ⇒ 跳过的那一程只摘安全阀，**不许写 known、也不许 prune**（那两份都是"带插件成功"的产物）。
     */
    static final String NOLATCH = "nolatch.txt";

    /** ★ 安全阀：注入前写、启动过了这一关才删。它在 ⇒ 上次带插件启动没走完 ⇒ 本次不注入。 */
    static final String GUARD = "guard.txt";

    /** 上次被安全阀拦下时的现场（给界面说"上次没起来，这次已跳过"）。 */
    static final String LASTFAIL = "lastfail.txt";

    /** 启动注入报告（有插件时才写；内部存储，dev 口会把它抄进 hub 的报告里）。 */
    static final String BOOT_REPORT = "report-plugin-boot.txt";

    /** 本进程是否已经注入过（{@code attachBaseContext} 只该来一次，加个闸门更省心）。 */
    private static boolean injected;

    private Plugins() {}

    // ---- 位置 -----------------------------------------------------------------

    /** `app_hub/plugins/`（内部存储；不存在就建）。 */
    static File dir(Context ctx) {
        File d = new File(Paths.privateDir(ctx), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * 同上，但**不创建**。
     * ★ 给"只读一眼"的路径用（{@link #markBootOk} / {@link #guardOn}）：没装过插件的机器上
     * 不该因为我们每次启动都看一眼，就凭空多出一个 `plugins/` 目录（"零成本路径"要真的是零）。
     */
    static File dirNoCreate(Context ctx) {
        return new File(Paths.privateDir(ctx), DIR);
    }

    /** 某个版本的落点：`plugins/<id>/<ver>.apk`。 */
    static File apkOf(Context ctx, String id, String ver) {
        return new File(new File(dir(ctx), id), ver + ".apk");
    }

    // ---- 登记表：纯函数（SelfTest 直接钉；不需要设备） --------------------------

    /** 取某个 id 的版本；没有 ⇒ null。 */
    static String stateGet(String text, String id) {
        if (text == null || id == null || id.isEmpty()) return null;
        for (String line : lines(text)) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (line.substring(0, eq).trim().equals(id)) {
                String v = line.substring(eq + 1).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 写入 / 覆盖某个 id（已有那一行就换掉，保序；否则追加到末尾）。 */
    static String statePut(String text, String id, String ver) {
        if (id == null || id.isEmpty() || ver == null || ver.isEmpty()) return text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        boolean done = false;
        for (String line : lines(text)) {
            if (line.isEmpty()) continue;   // ★ 空行不落盘：否则每写一次就多一行，文件会慢慢长胖
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(id)) {
                sb.append(id).append('=').append(ver).append('\n');
                done = true;
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!done) sb.append(id).append('=').append(ver).append('\n');
        return sb.toString();
    }

    /** 删掉某个 id（本就不在 ⇒ 内容等价返回）。 */
    static String stateRemove(String text, String id) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines(text)) {
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(id)) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 登记表里的全部 id（文件顺序、去重、空行/注释行忽略）。 */
    static List<String> stateIds(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : lines(text)) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String id = line.substring(0, eq).trim();
            if (id.isEmpty() || id.startsWith("#")) continue;
            if (line.substring(eq + 1).trim().isEmpty()) continue;
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    private static String[] lines(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        return text.split("\n");
    }

    // ---- 复合键（id + ver）的登记：**制表符**分隔 ---------------------------------
    //
    // 🔴 为什么不复用 statePut/stateGet（第 133 轮真机踩的）：那两个按**第一个** `=` 切键，
    //    所以 `id=ver=值` 这种三段的键会被切成 `id` ⇒ 写的时候每次都当"新键"追加一行
    //    （实测 retry.txt 里出现两条 `tpp=3=1`），读的时候永远读不到（重试预算因此永远用不完）。
    //    ⇒ 凡是"键本身含 `=`"的登记，一律走这三个（制表符三段：a \t b \t 值）。

    /** 取复合键的值；没有 ⇒ null。 */
    static String pairGet(String text, String a, String b) {
        if (text == null || a == null || b == null) return null;
        for (String line : lines(text)) {
            String[] p = line.split("\t");
            if (p.length == 3 && p[0].equals(a) && p[1].equals(b)) return p[2];
        }
        return null;
    }

    /** 写复合键（**同键只留一行** —— 这条正是上面那个 bug 的尺子）。 */
    static String pairPut(String text, String a, String b, String v) {
        if (a == null || b == null || v == null) return text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        boolean done = false;
        for (String line : lines(text)) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length == 3 && p[0].equals(a) && p[1].equals(b)) {
                sb.append(a).append('\t').append(b).append('\t').append(v).append('\n');
                done = true;
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!done) sb.append(a).append('\t').append(b).append('\t').append(v).append('\n');
        return sb.toString();
    }

    /** 删复合键（不存在 ⇒ 内容等价）。 */
    static String pairRemove(String text, String a, String b) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines(text)) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length == 3 && p[0].equals(a) && p[1].equals(b)) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---- 安装 / 生效 -----------------------------------------------------------

    /**
     * 把一份插件 APK **装进来**：拷进内部存储 + 置只读 + 登记。
     *
     * <p>为什么必须"拷进内部存储"：外部存储（FUSE）上的 dex 一律被 ART 拒绝
     * （S0 实测 `Writable dex file is not allowed`，`setReadOnly()` 在那边恒 false）
     * ⇒ 外部目录只当**投递口**，真正加载的永远是 `app_hub/plugins/` 里这份。
     *
     * <p>⚠️ 覆盖旧版本前**必须先删**：置只读的副本改不动（S0 第三条硬约束），
     * 而删除权来自目录 ⇒ "删掉再写"才是可行做法（唯一名只是另一条路，不是必需）。
     *
     * @param now true = 记进 {@link #ACTIVE}（本进程内就换；只有 dev 口会这么用）
     *            false = 记进 {@link #PENDING}（**退出重开生效** —— 界面走的常规路径）
     * @return 给报告看的一句话（失败时以 `FAILED ` 开头）
     */
    static String install(Context ctx, File src, String id, String ver, boolean now) {
        if (src == null || !src.isFile()) return "FAILED missing src " + src;
        if (id == null || id.isEmpty() || ver == null || ver.isEmpty()) return "FAILED bad id/ver";
        try {
            File dst = apkOf(ctx, id, ver);
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return "FAILED mkdirs " + parent;
            }
            if (dst.exists() && !dst.delete()) return "FAILED cannot replace " + dst.getName();
            Util.copyFile(src, dst);
            boolean ro = dst.setReadOnly();
            String key = now ? ACTIVE : PENDING;
            File f = new File(dir(ctx), key);
            write(f, statePut(readOrNull(f), id, ver));
            // ★ 信任模型（第 133 轮）：装进来这一刻判一次来源，记进 trust.txt（启动路径不再解析签名块）
            String trust = PluginTrust.classify(ctx, dst);
            File tf = new File(dir(ctx), TRUST);
            write(tf, trustPut(readOrNull(tf), id, ver, trust));
            return "installed id=" + id + " ver=" + ver + " -> " + key
                    + " bytes=" + dst.length() + " readOnly=" + ro + " stillWritable=" + dst.canWrite()
                    + " trust=" + trust;
        } catch (Throwable t) {
            return "FAILED " + brief(t);
        }
    }

    // ---- 来源与处置等级（第 133 轮） -------------------------------------------

    /** 来源登记的格式是 `id=ver:trust`（**纯函数**，界面/自检共用）。 */
    static String trustPut(String text, String id, String ver, String trust) {
        return pairPut(text, id, ver, trust);
    }

    /** 某个版本的来源；没有记录 ⇒ 现算一次（拿不到签名按第三方）。 */
    static String trustOf(Context ctx, String id, String ver) {
        File apk = apkOf(ctx, id, ver);
        return PluginTrust.resolve(pairGet(trust(ctx), id, ver), isBuiltin(ctx, id, ver),
                PluginTrust.classify(ctx, apk));
    }

    /** 来源登记全文。 */
    static String trust(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), TRUST));
        return s == null ? "" : s;
    }

    /**
     * 这个插件这次该按哪一档处置（**纯函数**，判据由 SelfTest 钉）：
     * 用户改过 ⇒ 用用户的；没改过 ⇒ 按来源的默认值（官方 L2 / 第三方 L3）。
     */
    static String levelOf(String levelsText, String trust, String id) {
        String lv = stateGet(levelsText, id);
        return PluginTrust.capLevel(trust, PluginTrust.validLevel(lv) ? lv : PluginTrust.defaultLevel(trust));
    }

    /** L1 的重试计数（`id=ver:次数`）。 */
    static int retryCount(String retryText, String id, String ver) {
        try {
            String v = pairGet(retryText, id, ver);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 用户**改过**的等级登记全文。 */
    static String levels(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), LEVELS));
        return s == null ? "" : s;
    }

    /** 用户改过等级（写 {@link #LEVELS}）；传空 / 非法值 ⇒ 清掉覆盖、回到按来源的默认值。 */
    static void setLevel(Context ctx, String id, String level) {
        File f = new File(dir(ctx), LEVELS);
        String cur = readOrNull(f);
        String next = PluginTrust.validLevel(level) ? statePut(cur, id, level) : stateRemove(cur, id);
        write(f, next);
        // 改了等级 ⇒ 连败计数清零（否则"改宽"之后下一次启动立刻又因预算用完而升档）
        File rf = new File(dir(ctx), RETRY);
        write(rf, pairRemove(readOrNull(rf), id, stateGet(readOrNull(new File(dir(ctx), ACTIVE)), id)));
    }

    /** 等级在界面上循环：L1 → L2 → L3 → L1（"改成更严一档"，到头回最宽）。 */
    static String cycleLevel(String level) {
        if (PluginTrust.L1.equals(level)) return PluginTrust.L2;
        if (PluginTrust.L2.equals(level)) return PluginTrust.L3;
        return PluginTrust.L1;
    }

    /**
     * 按来源决定循环到哪一档（**纯函数**）。
     * 🔴 内置只在 **L1 ↔ L2** 之间转：它被封顶在 L2（{@link PluginTrust#capLevel}），
     * 界面上若还写「改成 L3」，用户点了会发现**什么都没变** —— 那种自相矛盾正是
     * 2026-10-12 真机当场抓到的（弹窗写着"改成 L3"，实际被夹回 L2）。
     */
    static String cycleLevelFor(String trust, String level) {
        if (PluginTrust.BUILTIN.equals(trust)) {
            return PluginTrust.L1.equals(level) ? PluginTrust.L2 : PluginTrust.L1;
        }
        return cycleLevel(level);
    }

    // ---- 界面上的名字与信息行（第 136 轮） -------------------------------------

    /**
     * 界面上叫它什么：**自述里的名字优先，缺自述才用 id**（纯函数）。
     * ★ 单一来源 —— 列表行、弹窗标题、"上次跳过"横幅都用它，别在几处各写一份
     *   （同一件事有两个名字 = 用户得自己做翻译）。
     */
    static String displayName(String metaName, String id) {
        return (metaName == null || metaName.trim().isEmpty()) ? id : metaName.trim();
    }

    /** 这个插件当前那一版的自述名字（生效中 → 待生效 → 最新装的那一版 → 隔离件）。 */
    static String displayNameOf(Context ctx, String id) {
        String ver = stateGet(readOrNull(new File(dirNoCreate(ctx), ACTIVE)), id);
        if (ver == null) ver = stateGet(readOrNull(new File(dirNoCreate(ctx), PENDING)), id);
        if (ver == null) {
            String[] vs = versionsOf(ctx, id);
            if (vs.length > 0) ver = vs[vs.length - 1];
        }
        if (ver == null) {
            String[] qs = quarantinedOf(ctx, id);
            if (qs.length > 0) ver = qs[qs.length - 1];
        }
        if (ver == null) return id;
        try {
            return displayName(PluginMeta.read(ctx, apkOf(ctx, id, ver)).name, id);
        } catch (Throwable t) {
            return id;
        }
    }

    /**
     * 版本**令牌** → 人读标签：令牌就是"装进来那一刻"的 epoch 秒（见 `PluginsActivity` 装的那一处），
     * 所以这里直接把它当时间格式化。
     *
     * <p>🔴 2026-10-15（第 150 轮）：原来这个令牌**原样上屏**（`v1792090201`），6 处都在用 ——
     * 用户上一轮问的「什么东西是退回上一版」根因就是它（两个十位数比大小，谁也看不出谁旧）。
     * 令牌本身**不能换**（它同时是落点目录名与状态键）⇒ 只把**显示**换掉。
     *
     * <p>⚠️ 认不出（不是 2001~2100 之间的秒）就**原样返回** —— 手写的 / 别处来的令牌不该被吃掉。
     */
    static String versionLabel(String ver) {
        if (ver == null) return "";
        String s = ver.trim();
        if (s.isEmpty()) return "";
        try {
            long sec = Long.parseLong(s);
            if (sec > 1000000000L && sec < 4102444800L) {
                return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(sec * 1000L));
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    /**
     * 把「标签 + 值」里**值为空的整条去掉**（纯函数）。
     * ★ 规矩：空字段不许印成 `null` / 空行 —— 用户看到「作者：null」只会以为坏了；
     *   信息弹窗里少一行，比多一行垃圾强（与 §86.6「pass=false 时原因必须非空」同族）。
     * @return 每条 {@code {label, value}}，顺序照传入
     */
    static List<String[]> dropEmptyPairs(String[] labels, String[] values) {
        List<String[]> out = new ArrayList<String[]>();
        if (labels == null || values == null) return out;
        int n = Math.min(labels.length, values.length);
        for (int i = 0; i < n; i++) {
            String v = values[i];
            if (v == null || v.trim().isEmpty()) continue;
            out.add(new String[] { labels[i], v.trim() });
        }
        return out;
    }

    /**
     * L1 连败之后该升到哪一档（**纯函数**）：预算没用完 ⇒ 还是 L1（再试一次）；用完了 ⇒ 升到 L2。
     * ⚠️ 没有这条，"宽容"就等于"每隔一次启动就死一次"（S0 那条安全阀的初衷会被抵消）。
     */
    static String nextLevelAfterFail(String level, int fails) {
        if (!PluginTrust.L1.equals(level)) return level;
        return fails >= PluginTrust.RETRY_BUDGET ? PluginTrust.L2 : PluginTrust.L1;
    }

    /**
     * 进程一起来就把"待生效"搬成"生效" —— **这就是「退出重开生效」的实现处**。
     * 装/换插件只写 {@link #PENDING}，于是当前这一程继续用它已经加载的旧类；重开之后这次搬家
     * 才把它变成 {@link #ACTIVE} 并注入 ⇒ 新 dex 从头参与类解析。
     */
    private static String promotePending(File d) {
        String pend = readOrNull(new File(d, PENDING));
        List<String> ids = stateIds(pend);
        String act = readOrNull(new File(d, ACTIVE));
        if (ids.isEmpty()) return act;
        for (String id : ids) act = statePut(act, id, stateGet(pend, id));
        write(new File(d, ACTIVE), act);
        write(new File(d, PENDING), "");
        return act;
    }

    // ---- 启动注入 -------------------------------------------------------------

    /**
     * 报告里显示的入口类名：**包名尾段 + 类名**（`io.mdt.plugtest.v1.PlugPage` ⇒ `v1.PlugPage`）。
     * ★ 只打简单名不够用：两个插件完全可以各自住一个子包、类名相同（夹具正是这样）
     * ⇒ 那样报告里两行会一模一样，看不出"入口类一插件一份"到底生效没有。
     */
    private static String shortEntry(String fqn) {
        if (fqn == null) return "(none)";
        int last = fqn.lastIndexOf('.');
        if (last <= 0) return fqn;
        int prev = fqn.lastIndexOf('.', last - 1);
        return prev < 0 ? fqn : fqn.substring(prev + 1);
    }

    /** 报告里那一行注入顺序（带上来源，规则是不是照"官方先、第三方后"一眼看得出来）。 */    private static String orderLine(String trustText, String activeText, List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (String id : order) {
            String ver = stateGet(activeText, id);
            String t = pairGet(trustText, id, ver);
            if (sb.length() > 0) sb.append(' ');
            sb.append(id).append('=').append(ver).append('(').append(t == null ? "third" : t).append(')');
        }
        return sb.toString();
    }

    /**
     * 这一程按什么顺序注入（**纯函数**，判据由 SelfTest 钉）。
     *
     * <p>规则（2026-10-12 用户定）：「**让第三方肯定可以覆盖官方**」——
     * 每次注入都是**前插**（新 dex 拷到 `dexElements` 头部），所以**后注入的排得靠前、同名类它赢**
     * ⇒ 把**官方排在前面、第三方排在后面**，第三方就赢。同一档内部保持登记表的相对顺序（稳定排序）。
     *
     * <p>⚠️ 两条边界：
     * <ul>
     *   <li>**与安装顺序无关** —— 顺序只由"来源 + 登记表"决定（所以用户后装官方也不会把第三方挤下去）。</li>
     *   <li>**缺 trust 记录 ⇒ 当第三方**（默认不信任，与 {@link PluginTrust#classify} 同口径），
     *       也就是说"没判出来源的插件"会排在官方之后、能盖官方。</li>
     * </ul>
     * @param trustText 来源登记（制表符三段 `id \t ver \t official|third`）
     * @param activeText {@link #ACTIVE} 的内容
     */
    static List<String> injectOrder(String trustText, String activeText) {
        List<String> ids = stateIds(activeText);
        List<String> builtin = new ArrayList<String>();
        List<String> official = new ArrayList<String>();
        List<String> third = new ArrayList<String>();
        for (String id : ids) {
            String ver = stateGet(activeText, id);
            String t = pairGet(trustText, id, ver);
            if (PluginTrust.BUILTIN.equals(t)) builtin.add(id);      // 最低：谁都能盖它
            else if (PluginTrust.OFFICIAL.equals(t)) official.add(id);
            else third.add(id);                                       // 第三方 / 没记录 ⇒ 能盖官方与内置
        }
        List<String> out = new ArrayList<String>(ids.size());
        out.addAll(builtin);
        out.addAll(official);
        out.addAll(third);
        return out;
    }

    /**
     * 在**进程最早点**（{@link LauncherApp#attachBaseContext}）按登记把插件注入宿主 ClassLoader。
     *
     * <p>⚠️ 绝不抛：这是启动路径，任何异常都只能记下来、跳过插件继续启动
     * （早注入是唯一能把启动器砖掉的动作 —— 所以这里的容错与 {@link #GUARD} 是配套的）。
     */
    static void injectEarly(Context base) {
        if (base == null || injected) return;
        try {
            // :game 是游戏的进程 —— 插件是给启动器用的，且那边不该承担我们的注入风险
            if (!Util.isMainProcess(base)) return;
            File d = dir(base);
            File guard = new File(d, GUARD);
            if (guard.isFile()) {
                skipOnce(base, d, guard);
                return;
            }
            // ★ 内置插件释放/放回（第 137 轮）：放在**安全阀之后** —— 上一次没走完的那一程
            //   不该顺手改登记（否则刚放回的会被 skipOnce 当成"失败的那个"处理）
            String released = releaseBuiltins(base);
            String activeText = promotePending(d);
            List<String> ids = stateIds(activeText);
            if (ids.isEmpty()) {
                // 内置释放失败（assets 缺文件 / 写盘失败）也要留一句话，否则"内置没生效"只能靠猜
                if (!released.isEmpty()) {
                    write(new File(Data.hubDir(base), BOOT_REPORT),
                            "== plugin early inject ==\n"
                                    + "time    = " + new java.util.Date() + "\n"
                                    + "builtin = " + oneLine(released) + "\n"
                                    + "-> nothing to inject (builtin release did not produce a usable file)\n");
                }
                // 零成本路径**不写盘**；只有"上一次留了清单、这一次没插件了"才要清一下
                // （不清的话「打开」会一直以为某一版还住在宿主 CL 里 ⇒ 走错分支）
                File inj = new File(d, INJECTED);
                if (inj.isFile() && inj.length() > 0) write(inj, "");
                return;
            }
            // ★ 注入顺序 = 官方先、第三方后（后注入的排更前 ⇒ 第三方赢同名类；见 injectOrder）
            List<String> order = injectOrder(readOrNull(new File(d, TRUST)), activeText);

            injected = true;
            String head = "== plugin early inject ==\n"
                    + "time   = " + new java.util.Date() + "\n"
                    + "pid    = " + android.os.Process.myPid() + "\n"
                    + "active = " + oneLine(activeText) + "\n"
                    + "order  = " + orderLine(readOrNull(new File(d, TRUST)), activeText, order) + "\n"
                    + (released.isEmpty() ? "" : "builtin = " + released + "\n");

            // ★ 先落安全阀，再动 dex —— 顺序不能反：反了就是"崩了也不知道上次注入了谁"
            write(new File(d, GUARD), head);

            StringBuilder rep = new StringBuilder(head);
            ClassLoader host = Plugins.class.getClassLoader();
            File cc = base.getCodeCacheDir();
            if (cc != null && !cc.isDirectory()) cc.mkdirs();
            // 🔴 第 138 轮：插件一律插在**宿主自己那一段 dex 之后**（量一次边界，注入前读 = 宿主自己的条数）。
            //    理由：全插件化之后宿主内核必须永远可用、不可被替换 —— 插件再也不许顶掉 `LogActivity` /
            //    设置页这类宿主类。★ 插件之间仍是"后注入的排在这一段的前面"（都在这一段里前插）
            //    ⇒ 内置 → 官方外接 → 第三方的优先级**一点没变**。
            int hostDex = -1;
            try { hostDex = Reflect.dexElementCount(host); } catch (Throwable ignored) { }
            rep.append("hostDex = ").append(hostDex).append('\n');
            int ok = 0;
            String injectedIds = "";   // 本程真的进了宿主 CL 的那些（openInjected 只许对它们用）
            // 这次进程里已经解析过的入口类名 → 第一个用它的 id（同一个 FQN 只会被定义一次 ⇒ 报告要诚实）
            java.util.Map<String, String> entryOwner = new java.util.LinkedHashMap<String, String>();
            for (String id : order) {
                String ver = stateGet(activeText, id);
                File apk = apkOf(base, id, ver);
                rep.append("- ").append(id).append('=').append(ver)
                        .append(" file=").append(apk.getName());
                if (!apk.isFile()) {
                    rep.append(" MISSING\n");
                    continue;
                }
                // 入口类名：插件自述里写的（没有 ⇒ 默认入口，老插件照旧）
                String entryName = PluginMeta.entryOf(base, apk, PluginHost.DEFAULT_ENTRY);
                rep.append(" entry=").append(shortEntry(entryName));
                // ART 只加载**只读** dex。内部存储上 setReadOnly() 生效（外部 FUSE 上恒 false）
                if (apk.canWrite()) {
                    boolean ro = apk.setReadOnly();
                    rep.append(" ro=").append(ro).append(" stillWritable=").append(apk.canWrite());
                }
                try {
                    List<String> notes = new ArrayList<String>();
                    int n = Reflect.injectDex(host, apk, cc, hostDex < 0 ? 0 : hostDex, notes);
                    rep.append(" INJECTED elems=").append(n);
                    if (!notes.isEmpty()) rep.append(" [").append(notes.get(notes.size() - 1)).append(']');
                    // ★★ 注入完**立刻自查**：入口类必须能从**宿主 CL** 取到、且接口版本对得上。
                    //   两条理由：① 取不到 = 这次注入等于白干（报告里必须看得见，否则只能靠猜）；
                    //   ② 版本不匹配的插件**绝不许进宿主命名空间**（在插件页那层拒它已经太晚了 ——
                    //      类已经能被宿主任何代码解析到）。
                    String owner = entryOwner.get(entryName);
                    Class<?> entry = host.loadClass(entryName);
                    int api = ((Integer) entry.getMethod("api").invoke(null)).intValue();
                    rep.append(" entry=OK api=").append(api);
                    if (owner != null) {
                        // 🔴 同一个入口 FQN 被第二个插件用上了：宿主 CL 只会定义一份 ⇒
                        //    这一版的自查其实命中的是**别人的**入口类。报告必须说破（否则"entry=OK"会骗人）
                        rep.append(" SHARED(entry class already defined by ").append(owner).append(')');
                    } else {
                        entryOwner.put(entryName, id);
                    }
                    if (!PluginHost.apiCompatible(api)) {
                        // ★ 拒绝原因要说清"区间"（第 139 轮）：只写"host wants 2"会把"太老"也说成"要 2"
            int av = PluginHost.apiVerdict(api);
            rep.append(" -> API REJECTED (plugin ").append(api)
               .append(av > 0 ? " is newer than " : " is older than ")
               .append("host range ").append(PluginHost.API_MIN).append("..").append(PluginHost.API).append(")\n");
                        dropFromActive(d, id, activeText);
                        activeText = readOrNull(new File(d, ACTIVE));
                        rep.append("   -> removed from active.txt\n");
                        continue;
                    }
                    rep.append('\n');
                    injectedIds = statePut(injectedIds, id, ver);   // 供 openInjected 判"这个 dex 已在宿主 CL 里"
                    ok++;
                } catch (Throwable t) {
                    // 单个插件炸了不该拖死启动；也不留它在 active 里反复失败 ⇒ 就地摘掉它
                    rep.append(" FAILED ").append(brief(t)).append('\n');
                    dropFromActive(d, id, activeText);
                    activeText = readOrNull(new File(d, ACTIVE));
                    rep.append("   -> removed from active.txt (will not be retried)\n");
                }
            }
            rep.append("injected = ").append(ok).append(" / ").append(order.size()).append('\n');
            String text = rep.toString();
            write(new File(d, INJECTED), injectedIds);   // 「打开」那个按钮靠它决定用哪条路（见 openable）
            write(new File(d, BOOT_REPORT), text);
            // ★ 外部 hub 也留一份：产品版是**非 debuggable** 的（`run-as` 用不了），
            //   内部报告拉不出来 ⇒ 想看"开机那几百毫秒到底注入了什么"只能靠这一份。
            write(new File(Data.hubDir(base), BOOT_REPORT), text);
        } catch (Throwable t) {
            try {
                Log.w(TAG, "plugin early inject failed: " + brief(t));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把某个插件从生效登记里摘掉（注入失败 / 版本不匹配时用）。 */
    private static void dropFromActive(File d, String id, String activeText) {
        write(new File(d, ACTIVE), stateRemove(activeText, id));
    }

    /**
     * 启动真的过了这一关 ⇒ 摘掉安全阀。
     * 调点 = 第一个 Activity 的 {@code onResume}（{@link BaseActivity}）—— 比它更早的判据
     * （比如"Application.onCreate 跑完了"）挡不住"插件把主界面搞崩"这一类。
     */
    static void markBootOk(Context ctx) {
        try {
            File d = dirNoCreate(ctx);
            File g = new File(d, GUARD);
            if (g.isFile()) g.delete();
            // 🔴 这一程是"被安全阀跳过的启动" ⇒ 只摘安全阀，**别**把刚崩过的那一版记成已知可用
            File nl = new File(d, NOLATCH);
            if (nl.isFile()) {
                nl.delete();
                return;
            }
            // ★ 启动带着这些插件活下来了 ⇒ 它们就是"已知可用"的那一份（回滚的目标）
            String act = readOrNull(new File(d, ACTIVE));
            if (stateIds(act).isEmpty()) return;
            String known = readOrNull(new File(d, KNOWN));
            if (!oneLine(act).equals(oneLine(known))) {
                write(new File(d, KNOWN), act);
                pruneOldVersions(d, act);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 启动成功之后把**没用的旧版本**删掉，只留三份：**生效中 / 已知可用 / 待生效**。
     *
     * <p>为什么删：每次"装一版试一版"都会留一个 APK；而更新插件本来就会不断换版本 ⇒
     * 不收就会一直涨。留下已知可用那一份就够回滚了（"保留上一份可用插件"这条要求的全部）。
     * ⚠️ 只删**同一 id 下**的 `.apk`，且**只在启动成功之后**删 —— 失败路径上要留着现场。
     */
    private static void pruneOldVersions(File d, String activeText) {
        java.util.Set<String> keep = new java.util.HashSet<String>();
        for (String t : new String[] { activeText, readOrNull(new File(d, KNOWN)),
                                       readOrNull(new File(d, PENDING)) }) {
            for (String id : stateIds(t)) keep.add(id + "=" + stateGet(t, id));
        }
        // ★ 失败过的那一版**也留着**：界面上的「把插件重新启用」要能再试一次，
        //   而且它是"为什么被回滚"的现场（下一次成功升级时才随 prune 一起清掉）。
        for (String[] s : lastSkipFiles(d)) keep.add(s[0] + "=" + s[1]);
        for (String id : stateIds(activeText)) {
            File[] vs = new File(d, id).listFiles();
            if (vs == null) continue;
            for (File v : vs) {
                if (!v.isFile() || !v.getName().endsWith(".apk")) continue;
                String ver = v.getName().substring(0, v.getName().length() - 4);
                if (!keep.contains(id + "=" + ver)) v.delete();
            }
        }
    }

    /** 从盘上的 {@link #LASTSKIP} 解析出跳过记录（给 prune 用；界面那份走 {@link #lastSkip(Context)}）。 */
    private static List<String[]> lastSkipFiles(File d) {
        List<String[]> out = new ArrayList<String[]>();
        String text = readOrNull(new File(d, LASTSKIP));
        if (text == null) return out;
        for (String line : lines(text)) {
            String[] p = parseSkipLine(line);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * 上次带插件启动没走完 ⇒ 本次一个插件都不注入，并按**每个插件的处置等级**分别收拾
     * （第 133 轮：官方默认 L2 回滚 / 第三方默认 L3 严格；用户可改）。
     *
     * <p>三档的落点：
     * <ul>
     *   <li><b>L1 宽容</b> ⇒ 登记**留着**、下次启动再试一次；但 `retry.txt` 记连败次数，
     *       到 {@link PluginTrust#RETRY_BUDGET} 就**升到 L2** —— 否则"宽容"会退化成
     *       "每隔一次启动就死一次"（那正是安全阀当初要防的东西）。</li>
     *   <li><b>L2 回滚</b> ⇒ 退回 {@link #KNOWN}（已知可用）那一版；退不了（没记录 / 文件不在）⇒ 停用。</li>
     *   <li><b>L3 严格</b> ⇒ 先按 L2 回滚（有 known 就滚），**同时把这一版挪进 `quarantine/`**：
     *       它不再进候选（{@link #versionsOf} 只看本层 `.apk`）、也就不会被"再试一次"误伤，
     *       必须用户在插件页手动恢复。</li>
     * </ul>
     */
    private static void skipOnce(Context base, File d, File guard) {
        String g = readOrNull(guard);
        String activeText = readOrNull(new File(d, ACTIVE));
        String knownText = readOrNull(new File(d, KNOWN));
        String levelsText = readOrNull(new File(d, LEVELS));
        String trustText = readOrNull(new File(d, TRUST));
        String retryText = readOrNull(new File(d, RETRY));
        java.util.Set<String> have = availableKeys(d);

        StringBuilder nextActive = new StringBuilder();
        StringBuilder skip = new StringBuilder();
        for (String id : stateIds(activeText)) {
            String was = stateGet(activeText, id);
            String trust = trustFrom(trustText, base, d, id, was);
            String lv = levelOf(levelsText, trust, id);
            int fails = retryCount(retryText, id, was) + 1;
            retryText = pairPut(retryText, id, was, String.valueOf(fails));
            String eff = nextLevelAfterFail(lv, fails);

            String now = null;
            String action;
            boolean quarantined = false;
            if (PluginTrust.L1.equals(eff)) {
                now = was;                       // 留着：下次启动再试一次
                action = "retry";
            } else {
                // L2 / L3 都要"退回已知可用那一版"（退不了就停用）
                if (knownText != null && !was.equals(stateGet(knownText, id))
                        && have.contains(id + "=" + stateGet(knownText, id))) {
                    now = stateGet(knownText, id);
                    action = "rollback";
                } else {
                    action = "disable";
                }
                if (PluginTrust.L3.equals(eff)) {
                    quarantined = quarantine(d, id, was);
                }
            }
            if (now != null) nextActive.append(id).append('=').append(now).append('\n');
            skip.append(id).append('\t').append(was == null ? "" : was).append('\t')
                    .append(now == null ? "" : now).append('\t').append(action).append('\t')
                    .append(eff).append('\t').append(quarantined ? "yes" : "no").append('\n');
        }
        String rolled = nextActive.toString();
        try {
            write(new File(d, LASTFAIL),
                    "== plugin skip (previous launch did not finish) ==\n"
                            + "time      = " + new java.util.Date() + "\n"
                            + "guard     = " + oneLine(g) + "\n"
                            + "active    = " + oneLine(activeText) + "\n"
                            + "known     = " + oneLine(knownText) + "\n"
                            + "levels    = " + oneLine(levelsText) + "\n"
                            + "trust     = " + oneLine(trustText) + "\n"
                            + "next      = " + oneLine(rolled) + "\n"
                            + "skip      = " + oneLine(skip.toString()) + "\n");
        } catch (Throwable ignored) {
        }
        if (guard.isFile()) guard.delete();
        write(new File(d, ACTIVE), rolled);
        write(new File(d, LASTSKIP), skip.toString());
        write(new File(d, RETRY), retryText);
        write(new File(d, INJECTED), "");
        // 🔴 给这一程留个"没注入过"的标记：markBootOk 看到它就不写 known（见 NOLATCH 的注释）
        write(new File(d, NOLATCH), oneLine(activeText) + "\n");
        // ★ 跳过这一程也往**外部 hub** 留一份报告：产品版非 debuggable，`run-as` 拉不到内部那份
        write(new File(Data.hubDir(base), BOOT_REPORT),
                "== plugin early inject ==\n"
                        + "time     = " + new java.util.Date() + "\n"
                        + "SKIPPED: previous launch with plugins did not finish\n"
                        + "active was = " + oneLine(activeText) + "\n"
                        + "skip     = " + oneLine(skip.toString()) + "\n"
                        + "next     = " + oneLine(rolled) + "\n");
        write(new File(d, BOOT_REPORT),
                "== plugin early inject ==\n"
                        + "time     = " + new java.util.Date() + "\n"
                        + "SKIPPED: previous launch with plugins did not finish\n"
                        + "active was = " + oneLine(activeText) + "\n"
                        + "per plugin = " + oneLine(skip.toString()) + "\n"
                        + "-> " + (stateIds(rolled).isEmpty()
                                ? "nothing injected this launch"
                                : "nothing injected; next active = " + oneLine(rolled))
                        + "\n");
    }

    /** 来源登记里取（没有就现算一次）—— 跳过一次处置时要用它决定默认等级。 */
    private static String trustFrom(String trustText, Context ctx, File d, String id, String ver) {
        // ★ 与界面那条路**共用同一处判据**（第 137 轮真机踩过：这里漏认 builtin，
        //   导致安全阀把内置当"官方"⇒ 等级封顶失效 ⇒ 内置被 L3 隔离）
        return PluginTrust.resolve(pairGet(trustText, id, ver), isBuiltin(ctx, id, ver),
                PluginTrust.classify(ctx, new File(new File(d, id), ver + ".apk")));
    }

    /** 把某一版挪进 `plugins/<id>/quarantine/`（挪成功 ⇒ true）。 */
    private static boolean quarantine(File d, String id, String ver) {
        try {
            File from = new File(new File(d, id), ver + ".apk");
            if (!from.isFile()) return false;
            File q = new File(new File(d, id), "quarantine");
            if (!q.isDirectory() && !q.mkdirs()) return false;
            return from.renameTo(new File(q, ver + ".apk"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 被隔离的版本（升序）。 */
    static String[] quarantinedOf(Context ctx, String id) {
        File[] vs = new File(new File(dirNoCreate(ctx), id), "quarantine").listFiles();
        if (vs == null) return new String[0];
        java.util.Arrays.sort(vs);
        List<String> out = new ArrayList<String>();
        for (File v : vs) {
            if (v.isFile() && v.getName().endsWith(".apk")) {
                out.add(v.getName().substring(0, v.getName().length() - 4));
            }
        }
        return out.toArray(new String[0]);
    }

    /** 把被隔离的一版挪回候选目录（用户手动恢复；挪成功 ⇒ true）。 */
    static boolean restoreQuarantined(Context ctx, String id, String ver) {
        try {
            File q = new File(new File(dirNoCreate(ctx), id), "quarantine");
            File from = new File(q, ver + ".apk");
            if (!from.isFile()) return false;
            File to = new File(new File(dirNoCreate(ctx), id), ver + ".apk");
            if (!from.renameTo(to)) return false;
            File rf = new File(dir(ctx), RETRY);
            write(rf, pairRemove(readOrNull(rf), id, ver));   // 恢复 = 连败计数清零
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 回滚计划（**纯函数**，判据由 SelfTest 钉）：拿"已知可用"与"这次失败的"两份登记 +
     * 手上真的还有哪些 `id=ver` 文件，算出**这次该生效什么**。
     *
     * <p>三条判据（每一条都有断言盯着）：
     * <ol>
     *   <li>已知可用版本存在、**和失败的那一版不同**、**文件还在** ⇒ 回滚到它；</li>
     *   <li>已知可用就是失败那一版（或没有记录）⇒ **停用**（回滚到同一版没有意义）；</li>
     *   <li>已知可用的文件**已经不在了** ⇒ 也停用 —— 绝不写一个指向不存在文件的登记
     *       （那会让下次启动多一条 MISSING，白付一次注入尝试）。</li>
     * </ol>
     */
    static String rollbackActive(String knownText, String failedActiveText,
                                 java.util.Set<String> available) {
        StringBuilder out = new StringBuilder();
        for (String id : stateIds(failedActiveText)) {
            String bad = stateGet(failedActiveText, id);
            String good = stateGet(knownText, id);
            if (good == null || good.equals(bad)) continue;
            if (available != null && !available.contains(id + "=" + good)) continue;
            out.append(id).append('=').append(good).append('\n');
        }
        return out.toString();
    }

    /** 手上真的还有哪些 `id=ver`（扫 `plugins/<id>/*.apk`）。 */
    static java.util.Set<String> availableKeys(File d) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        File[] ids = d == null ? null : d.listFiles();
        if (ids == null) return out;
        for (File id : ids) {
            if (!id.isDirectory()) continue;
            File[] vs = id.listFiles();
            if (vs == null) continue;
            for (File v : vs) {
                if (v.isFile() && v.getName().endsWith(".apk")) {
                    out.add(id.getName() + "=" + v.getName().substring(0, v.getName().length() - 4));
                }
            }
        }
        return out;
    }

    /**
     * 解析一行结构化跳过记录：
     * {@code id \t 失败版本 \t 回滚到的版本(空=停用) \t action \t 等级 \t 是否隔离(yes/no)}。
     *
     * <p>★ 字段数**向后兼容**：第 132 轮写的是 4 段（没有等级/隔离），老文件也解析得出来
     * （等级记成空、隔离记成 no）—— 否则用户机器上那份老记录会让界面**整段不说话**。
     * @return {@code {id, was, now, action, level, quarantined}}；不是这种行 ⇒ null（**纯函数**）
     */
    static String[] parseSkipLine(String line) {
        if (line == null) return null;
        String[] p = line.split("\t");
        if (p.length < 4 || p.length > 6) return null;
        String id = p[0].trim();
        String action = p[3].trim();
        if (id.isEmpty()) return null;
        if (!("retry".equals(action) || "rollback".equals(action) || "disable".equals(action))) {
            return null;
        }
        String level = p.length > 4 ? p[4].trim() : "";
        String quar = p.length > 5 ? p[5].trim() : "no";
        return new String[] { id, p[1].trim(), p[2].trim(), action, level,
                              "yes".equals(quar) ? "yes" : "no" };
    }

    /** 上次被拦下之后每个插件怎么了（已解析；界面直接用）。 */
    static List<String[]> lastSkip(Context ctx) {
        List<String[]> out = new ArrayList<String[]>();
        String text = readOrNull(new File(dirNoCreate(ctx), LASTSKIP));
        if (text == null) return out;
        for (String line : lines(text)) {
            String[] p = parseSkipLine(line);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ---- 给界面 / dev 口读的现场 -----------------------------------------------

    /** 启动注入报告全文（没有 ⇒ 空串）。 */
    static String bootReport(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), BOOT_REPORT));
        return s == null ? "" : s;
    }

    /** 上次被安全阀拦下的现场（没有 ⇒ 空串）。 */
    static String lastFail(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), LASTFAIL));
        return s == null ? "" : s;
    }

    /** 安全阀还挂着吗（= 这次的注入还没被"启动成功"确认；界面/dev 口用）。 */
    static boolean guardOn(Context ctx) {
        return new File(dirNoCreate(ctx), GUARD).isFile();
    }

    /** 生效登记全文。 */
    static String active(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), ACTIVE));
        return s == null ? "" : s;
    }

    /** 待生效登记全文。 */
    static String pending(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), PENDING));
        return s == null ? "" : s;
    }

    /** 已知可用登记全文（回滚目标）。 */
    static String known(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), KNOWN));
        return s == null ? "" : s;
    }

    /** 本次启动真的进了宿主 CL 的清单全文。 */
    static String injected(Context ctx) {
        String s = readOrNull(new File(dirNoCreate(ctx), INJECTED));
        return s == null ? "" : s;
    }

    // ---- 界面用的几个动作（判据在纯函数里，动作只负责落盘） ----------------------

    /**
     * 界面上这一行该怎么说（**纯函数**）：{@code {"active"|"pending"|"off", 版本或 ""}}。
     * ★ 顺序有讲究：**待重启生效优先于生效中** —— 装了新版之后，用户最需要知道的是
     *   "现在还没生效、重开才生效"；只说"生效中 v1"会让人以为新版已经用上了。
     */
    static String[] rowState(String activeText, String pendingText, String id) {
        String pend = stateGet(pendingText, id);
        if (pend != null) return new String[] { "pending", pend };
        String act = stateGet(activeText, id);
        if (act != null) return new String[] { "active", act };
        return new String[] { "off", "" };
    }

    /** 装了哪些 id（按名字排序；**有隔离件的也算** —— 否则用户再也看不到、也就恢复不了它）。 */
    static List<String> installedIds(Context ctx) {
        List<String> out = new ArrayList<String>();
        File d = dirNoCreate(ctx);
        File[] ids = d.listFiles();
        if (ids == null) return out;
        java.util.Arrays.sort(ids);
        for (File f : ids) {
            if (!f.isDirectory()) continue;
            if (versionsOf(ctx, f.getName()).length > 0 || quarantinedOf(ctx, f.getName()).length > 0) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** 某个 id 装了哪些版本（升序）。 */
    static String[] versionsOf(Context ctx, String id) {
        File[] vs = new File(dirNoCreate(ctx), id).listFiles();
        if (vs == null) return new String[0];
        java.util.Arrays.sort(vs);
        List<String> out = new ArrayList<String>();
        for (File v : vs) {
            if (v.isFile() && v.getName().endsWith(".apk")) {
                out.add(v.getName().substring(0, v.getName().length() - 4));
            }
        }
        return out.toArray(new String[0]);
    }

    /** 启用某个版本 ⇒ 写 {@link #PENDING}（**退出重开才生效**）。 */
    static void enable(Context ctx, String id, String ver) {
        File f = new File(dir(ctx), PENDING);
        write(f, statePut(readOrNull(f), id, ver));
    }

    /** 停用：从 {@link #ACTIVE} 与 {@link #PENDING} 都摘掉（退出重开之后它就不加载了）。 */
    static void disable(Context ctx, String id) {
        File a = new File(dir(ctx), ACTIVE), p = new File(dir(ctx), PENDING);
        write(a, stateRemove(readOrNull(a), id));
        write(p, stateRemove(readOrNull(p), id));
    }

    /**
     * 卸载：删掉整个 `plugins/<id>/` + 从三份登记里摘掉。
     * 🔴 **内置插件拒绝卸载**（用户 2026-10-12 定：「内置随启动器本体一起发放，不能卸载」）——
     * 就算这里放行，下次启动 releaseBuiltins 也会把它放回来，界面上不如根本不给这个动作。
     * @return true = 真卸了；false = 拒绝了（内置）
     */
    static boolean uninstall(Context ctx, String id) {
        if (isBuiltinId(ctx, id)) return false;
        disable(ctx, id);
        File k = new File(dir(ctx), KNOWN);
        write(k, stateRemove(readOrNull(k), id));
        // ⚠️ 第 148 轮原本想把整台插件搬进中转站（"卸载可恢复"），**查清 API 后撤回了**：
        //   `Trash.remove(path, trashDir)` 不是"搬进去"，而是"把**已经在中转站里**的东西彻底删掉"
        //   （它先查 `isDirectChild(path, trashDir)`，再 `deleteTree`）⇒ 拿它当搬运用会**静默丢掉插件数据**。
        //   而且插件住**内部**存储、中转站目录在**外部** hub ⇒ 真要搬得走"跨卷：复制→校验→删源"那套。
        //   ⇒ 这件事连同"中转站里加插件分类"整块留给下一轮（见 `next/22` 的待办）。
        // ★ 第 148 轮 C1-b：**卸载 = 整台搬进中转站**（APK + 它的数据在同一个 id 目录里 ⇒ 一步收齐），
        //   走 `Trash.move`（跨卷安全：renameTo 失败 ⇒ 复制 + 校验长度 + 才删源）。
        //   🔴 **搬不动就保持"还装着"**（返回 false，界面会说），绝不像原来那样直接 deleteTree：
        //      删了就真没了，而"卸载失败"用户还能再试一次 / 手动看看为什么。
        File home = new File(dir(ctx), id);
        File into = new File(Trash.pluginsDir(ctx), Trash.nameFor("plugins", id));
        if (!Trash.move(home, into)) return false;
        return true;
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** 这个版本的类这程是不是**已经在宿主 CL 里**了（决定「打开」走 openInjected 还是 load）。 */
    static boolean isInjected(Context ctx, String id, String ver) {
        return ver != null && ver.equals(stateGet(injected(ctx), id));
    }

    // ---- 小工具 ---------------------------------------------------------------

    private static String readOrNull(File f) {
        try {
            if (f == null || !f.isFile()) return null;
            return Util.readText(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(File f, String text) {
        try {
            Util.atomicWriteText(f, text);
        } catch (Throwable ignored) {
        }
    }

    /** 多行文本压成一行（报告里引用登记表内容时用）。 */
    static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
