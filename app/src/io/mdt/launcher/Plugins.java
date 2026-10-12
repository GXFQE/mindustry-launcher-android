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
            return "installed id=" + id + " ver=" + ver + " -> " + key
                    + " bytes=" + dst.length() + " readOnly=" + ro + " stillWritable=" + dst.canWrite();
        } catch (Throwable t) {
            return "FAILED " + brief(t);
        }
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
            String activeText = promotePending(d);
            List<String> ids = stateIds(activeText);
            if (ids.isEmpty()) return;   // ★ 零成本路径：没装插件 ⇒ 不读 dex、不写盘、不留痕

            injected = true;
            String head = "== plugin early inject ==\n"
                    + "time   = " + new java.util.Date() + "\n"
                    + "pid    = " + android.os.Process.myPid() + "\n"
                    + "active = " + oneLine(activeText) + "\n";

            // ★ 先落安全阀，再动 dex —— 顺序不能反：反了就是"崩了也不知道上次注入了谁"
            write(new File(d, GUARD), head);

            StringBuilder rep = new StringBuilder(head);
            ClassLoader host = Plugins.class.getClassLoader();
            File cc = base.getCodeCacheDir();
            if (cc != null && !cc.isDirectory()) cc.mkdirs();
            int ok = 0;
            for (String id : ids) {
                String ver = stateGet(activeText, id);
                File apk = apkOf(base, id, ver);
                rep.append("- ").append(id).append('=').append(ver)
                        .append(" file=").append(apk.getName());
                if (!apk.isFile()) {
                    rep.append(" MISSING\n");
                    continue;
                }
                // ART 只加载**只读** dex。内部存储上 setReadOnly() 生效（外部 FUSE 上恒 false）
                if (apk.canWrite()) {
                    boolean ro = apk.setReadOnly();
                    rep.append(" ro=").append(ro).append(" stillWritable=").append(apk.canWrite());
                }
                try {
                    List<String> notes = new ArrayList<String>();
                    int n = Reflect.injectDex(host, apk, cc, true, notes);
                    rep.append(" INJECTED elems=").append(n);
                    if (!notes.isEmpty()) rep.append(" [").append(notes.get(notes.size() - 1)).append(']');
                    // ★★ 注入完**立刻自查**：入口类必须能从**宿主 CL** 取到、且接口版本对得上。
                    //   两条理由：① 取不到 = 这次注入等于白干（报告里必须看得见，否则只能靠猜）；
                    //   ② 版本不匹配的插件**绝不许进宿主命名空间**（在插件页那层拒它已经太晚了 ——
                    //      类已经能被宿主任何代码解析到）。
                    Class<?> entry = host.loadClass(PluginHost.ENTRY);
                    int api = ((Integer) entry.getMethod("api").invoke(null)).intValue();
                    rep.append(" entry=OK api=").append(api);
                    if (!PluginHost.apiCompatible(api)) {
                        rep.append(" -> API MISMATCH (host wants ").append(PluginHost.API).append(")\n");
                        dropFromActive(d, id, activeText);
                        activeText = readOrNull(new File(d, ACTIVE));
                        rep.append("   -> removed from active.txt\n");
                        continue;
                    }
                    rep.append('\n');
                    ok++;
                } catch (Throwable t) {
                    // 单个插件炸了不该拖死启动；也不留它在 active 里反复失败 ⇒ 就地摘掉它
                    rep.append(" FAILED ").append(brief(t)).append('\n');
                    dropFromActive(d, id, activeText);
                    activeText = readOrNull(new File(d, ACTIVE));
                    rep.append("   -> removed from active.txt (will not be retried)\n");
                }
            }
            rep.append("injected = ").append(ok).append(" / ").append(ids.size()).append('\n');
            String text = rep.toString();
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
            File g = new File(dirNoCreate(ctx), GUARD);
            if (g.isFile()) g.delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 上次带插件启动没走完 ⇒ 本次一个插件都不注入 + **自动停用**（把现场搬进 {@link #LASTFAIL}、
     * 清空 {@link #ACTIVE}），保证启动器永远起得来。恢复由用户在界面上做（下一轮接 UI）。
     */
    private static void skipOnce(Context base, File d, File guard) {
        String g = readOrNull(guard);
        String activeText = readOrNull(new File(d, ACTIVE));
        try {
            write(new File(d, LASTFAIL),
                    "== plugin skip (previous launch did not finish) ==\n"
                            + "time      = " + new java.util.Date() + "\n"
                            + "guard     = " + oneLine(g) + "\n"
                            + "active    = " + oneLine(activeText) + "\n"
                            + "-> plugins disabled for this launch and removed from active.txt\n");
        } catch (Throwable ignored) {
        }
        if (guard.isFile()) guard.delete();
        write(new File(d, ACTIVE), "");   // 自动停用：不再自动试第二次（否则会变成"每隔一次启动就死一次"）
        write(new File(d, BOOT_REPORT),
                "== plugin early inject ==\n"
                        + "time     = " + new java.util.Date() + "\n"
                        + "SKIPPED: previous launch with plugins did not finish\n"
                        + "active was = " + oneLine(activeText) + "\n"
                        + "-> nothing injected; active.txt cleared\n");
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
