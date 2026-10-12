package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 插件化的 **dev 侧**：只剩 S0 可行性实验（插件页已搬回产品侧 `PluginsActivity`，第 132 轮）。
 *
 * <p>★ 为什么整块住 `app/src-dev`：这两件都是**开发/验收用的**（一个演示页 + 一个实验入口），
 * 产品版不该带着它们 —— 与 `DevTools` / `SelfTest` 同一条裁剪规矩（`build.sh` 的 `[2/4] javac`）。
 * 产品侧只留 {@link PluginHost} 的**加载器与判据函数**；那边一行 UI 都不带。
 *
 * <p>两个入口（都走 dev 白名单，见 {@link DevTools}）：
 * <ul>
 *   <li>{@code --es dev_plugins <插件目录>} ⇒ 用插件渲染一页（失败则兜底 + 明说原因）</li>
 *   <li>{@code --es dev_pluginprobe <插件目录>} ⇒ 跑 S0 实验，报告写 {@code <hub>/report-plugin.txt}</li>
 * </ul>
 */
final class PluginProbeDev {

    /** 同名类夹具的全限定名（壳里也有这两个类，见本包 PluginProbe / PluginProbe2）。 */
    static final String PROBE = "io.mdt.launcher.PluginProbe";
    static final String PROBE2 = "io.mdt.launcher.PluginProbe2";

    private PluginProbeDev() {}

    // ---- S0 可行性实验 ---------------------------------------------------------

    /** 后台跑实验（不阻塞 UI），报告落 {@code <hub>/report-plugin.txt}。 */
    static void runExperimentAsync(final Context ctx, final File dir) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runExperiment(ctx, dir);
                } catch (Throwable ignored) {
                }
            }
        }, "plugin-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 跑一遍全部夹具，返回报告文本（同时写盘）。★ 诊断字面量一律 ASCII（门禁 SRC-02 那条）。 */
    static String runExperiment(Context ctx, File dir) {
        List<String> L = new ArrayList<String>();
        L.add("== plugin feasibility (S0) ==");
        L.add("time      = " + new Date());
        L.add("host debuggable = " + debuggable(ctx));
        L.add("fixture dir = " + dir);
        L.add("api       = " + PluginHost.API);

        L.add("");
        L.add("-- (1) good plugin plugtest-ok.apk --");
        try {
            PluginHost.Loaded ok = PluginHost.load(ctx, new File(dir, "plugtest-ok.apk"));
            L.add("  load        = OK   [ART accepted this path]");
            L.add("  setReadOnly = " + ok.readOnlySet + "   (false on external FUSE storage - expected)");
            L.add("  stillWritable = " + ok.stillWritable + "   (judge by ART, not chmod)");
            L.add("  assets from = " + ok.assetFrom + " cookie=" + ok.cookie);
            L.add("  plugin str  = " + ok.entry.getMethod("hello", Context.class).invoke(null, ok.ctx));
            Object v = ok.entry.getMethod("createView", Context.class).invoke(null, ok.ctx);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                L.add("  createView  = text[" + t.getText() + "] color=#"
                        + Integer.toHexString(t.getCurrentTextColor()));
            } else {
                L.add("  createView  = " + (v == null ? "null" : v.getClass().getName()));
            }
        } catch (PluginHost.PluginException e) {
            L.add("  load        = REJECTED reason=" + e.reason + " detail=" + e.detail);
        } catch (Throwable t) {
            L.add("  load        = UNEXPECTED " + brief(t));
        }

        L.add("");
        L.add("-- (2) bad plugins --");
        reject(ctx, L, new File(dir, "plugtest-badapi.apk"), "api=99 (shell wants 1)");
        reject(ctx, L, new File(dir, "plugtest-noclass.apk"), "entry class missing");
        reject(ctx, L, new File(dir, "plugtest-nothing.apk"), "file does not exist");

        L.add("");
        L.add("-- (3) shadowing --");
        try {
            ClassLoader hostCL = PluginHost.class.getClassLoader();
            L.add("  a) host loads PROBE (pin as loaded)  = " + tagOf(hostCL.loadClass(PROBE)));

            PluginHost.Loaded v1 = PluginHost.load(ctx, new File(dir, "plugtest-v1.apk"));
            L.add("  b) plugin CL loads PROBE (parent 1st)= " + tagOf(v1.cl.loadClass(PROBE)));

            List<String> notes = new ArrayList<String>();
            File c1 = uniqueCopy(ctx, new File(dir, "plugtest-v1.apk"));
            int n1 = Reflect.injectDex(hostCL, c1, ctx.getCodeCacheDir(), false, notes);
            L.add("  c) inject v1-copy APPEND -> " + notes.get(notes.size() - 1) + " elems=" + n1);
            L.add("     host gets PROBE                   = " + tagOf(hostCL.loadClass(PROBE))
                    + "   [expect SHELL: host dex still first]");

            File c2 = uniqueCopy(ctx, new File(dir, "plugtest-v2.apk"));
            int n2 = Reflect.injectDex(hostCL, c2, ctx.getCodeCacheDir(), true, notes);
            L.add("  d) inject v2-copy PREPEND -> " + notes.get(notes.size() - 1) + " elems=" + n2);
            L.add("     host gets PROBE (already loaded)  = " + tagOf(hostCL.loadClass(PROBE))
                    + "   [expect SHELL: an already-loaded class cannot be replaced]");
            L.add("     host gets PROBE2 (never loaded)   = " + tagOf(hostCL.loadClass(PROBE2))
                    + "   [expect PLUGIN-v2-2: an unloaded same-name class CAN be shadowed]");
            L.add("     host gets plugin entry class      = " + hostCL.loadClass(PluginHost.DEFAULT_ENTRY).getName());
            L.add("     copies: " + c1.getName() + " / " + c2.getName());
        } catch (Throwable t) {
            L.add("  FAILED " + brief(t));
        }

        String report = join(L);
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), "report-plugin.txt"), report);
        } catch (Throwable ignored) {
        }
        return report;
    }

    private static void reject(Context ctx, List<String> L, File apk, String what) {
        L.add("  - " + apk.getName() + " (" + what + ")");
        try {
            PluginHost.load(ctx, apk);
            L.add("      !! LOADED ANYWAY - this gate is broken");
        } catch (PluginHost.PluginException e) {
            L.add("      ok REJECTED reason=" + e.reason + " detail=" + e.detail);
        } catch (Throwable t) {
            L.add("      !! UNEXPECTED " + brief(t));
        }
    }

    private static String tagOf(Class<?> c) {
        try {
            return String.valueOf(c.getMethod("tag").invoke(null));
        } catch (Throwable t) {
            return "!" + brief(t);
        }
    }

    private static boolean debuggable(Context ctx) {
        return (ctx.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static String join(List<String> L) {
        StringBuilder sb = new StringBuilder();
        for (String s : L) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 拷一份"独立副本"到内部存储并置只读（唯一文件名 ⇒ 不会被上一轮的只读文件挡住）。 */
    private static File uniqueCopy(Context ctx, File from) throws Exception {
        File to = new File(ctx.getCacheDir(), "plugtest-shadow-" + System.currentTimeMillis() + ".apk");
        java.io.InputStream in = new java.io.FileInputStream(from);
        try {
            java.io.OutputStream out = new java.io.FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        to.setReadOnly();
        return to;
    }

    // =========================================================================
    // S0.5（2026-10-11）：**"退出重开生效"那套机制的 dev 口**
    //
    // 产品侧的实现只有三个东西（`Plugins`：登记 + 早注入 + 安全阀；`Restart`：重开；
    // `LauncherApp.attachBaseContext`：注入点）。这里只是**驱动与取证**：
    //   --es dev_plugininstall <hub 里的 apk>  装进来（默认写 pending ⇒ 退出重开才生效）
    //   --es dev_pluginstatus                  现在到底谁在生效（宿主 CL 里那两个同名类的 tag）
    //   --es dev_pluginrestart                 挂闹钟 + 自杀（自动拉回）
    //   --es dev_pluginclear                   清掉登记与报告（不动已装的 APK）
    //
    // 🔴 **安全阀（guard）没有 dev 口**，验法是从 adb 侧直接写那个文件：
    //     printf 'fake\n' | adb shell "run-as io.mdt.launcher sh -c \
    //       'cd /data/data/io.mdt.launcher && cat > app_hub/plugins/guard.txt'"
    //     ⇒ 下一次正常启动必须 SKIP（不注入 + 清空 active.txt + 写 lastfail）。
    //   为什么不做成 dev 口（第 131 轮踩了两次，两次都是它自己坏）：
    //     ① 只在 Activity 里写 guard ⇒ 同一个 Activity 的 `onResume` 会把它摘掉（尺子没分辨力）；
    //     ② 写完之后自杀 ⇒ 系统按**原 Intent 恢复那个任务** ⇒ `dev_pluginguard` 被反复重放，
    //        循环里 `skipOnce` 又会把 guard 吃掉 —— 测出来的既不是"跳过"也不是"没跳过"。
    //   ⇒ 凡是"要造一个'上次没走完'的状态"，就不要让那个状态的制造者自己还活着。
    // =========================================================================

    /** 装一份插件。{@code now=true} 才写 active（常规路径写 pending = 退出重开生效）。 */
    static String install(Context ctx, String srcPath, String idArg, String verArg, boolean now) {
        File src = new File(srcPath == null ? "" : srcPath);
        String id = (idArg == null || idArg.trim().isEmpty()) ? baseName(src.getName()) : idArg.trim();
        String ver = (verArg == null || verArg.trim().isEmpty()) ? "1" : verArg.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("== dev_plugininstall ==\n");
        sb.append("src        = ").append(srcPath).append('\n');
        sb.append("id         = ").append(id).append('\n');
        sb.append("ver        = ").append(ver).append('\n');
        sb.append("takeEffect = ").append(now ? "active" : "pending (exit & reopen)").append('\n');
        sb.append("result     = ").append(Plugins.install(ctx, src, id, ver, now)).append('\n');
        sb.append("active     = ").append(Plugins.oneLine(Plugins.active(ctx))).append('\n');
        sb.append("pending    = ").append(Plugins.oneLine(Plugins.pending(ctx))).append('\n');
        appendListing(sb, ctx);
        String text = sb.toString();
        writeReport(ctx, "report-plugin-install.txt", text);
        return text;
    }

    /**
     * 现在的状态与**判据**。
     * ★ 最要紧的两行是 `host tag PluginProbe / PluginProbe2`：壳里那两个类自己的 tag 是
     * {@code SHELL} / {@code SHELL2}，插件夹具里那份是 {@code PLUGIN-*} ⇒ 拿到哪个字符串
     * 就是"这一份类到底是谁在生效"。早注入要证的就是"**插件把宿主尚未加载的类顶掉了**"。
     */
    static String status(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("== plugin status ==\n");
        sb.append("time        = ").append(new Date()).append('\n');
        sb.append("pid         = ").append(android.os.Process.myPid()).append('\n');
        sb.append("api         = ").append(PluginHost.API).append('\n');
        sb.append("entry       = ").append(PluginHost.DEFAULT_ENTRY).append('\n');
        sb.append("pluginsDir  = ").append(Plugins.dirNoCreate(ctx)).append('\n');
        sb.append("active      = ").append(Plugins.oneLine(Plugins.active(ctx))).append('\n');
        sb.append("pending     = ").append(Plugins.oneLine(Plugins.pending(ctx))).append('\n');
        sb.append("guardOn     = ").append(Plugins.guardOn(ctx)).append('\n');
        sb.append('\n');
        ClassLoader host = PluginProbeDev.class.getClassLoader();
        sb.append("host tag PluginProbe  = ").append(tagOf(host, PROBE))
                .append("   (shell's own = SHELL)\n");
        sb.append("host tag PluginProbe2 = ").append(tagOf(host, PROBE2))
                .append("   (shell's own = SHELL2)\n");
        // ★ 插件之间的"谁赢"尺子：这个类**宿主里没有** ⇒ 修完 B（插件排宿主之后）照样量得出来
        sb.append("shared tag (plugins)   = ").append(tagOf(host, "io.mdt.plugtest.SharedTag")).append('\n');
        sb.append('\n');
        String act = Plugins.active(ctx);
        for (String id : Plugins.stateIds(act)) {
            String ver = Plugins.stateGet(act, id);
            File apk = Plugins.apkOf(ctx, id, ver);
            sb.append("-- ").append(id).append('=').append(ver)
                    .append(" file=").append(apk.getName()).append(" bytes=").append(apk.length()).append('\n');
            try {
                PluginHost.Loaded l = PluginHost.openInjected(ctx, apk);
                sb.append("  openInjected = OK api=").append(l.api)
                        .append(" assets=").append(l.assetFrom).append(" cookie=").append(l.cookie).append('\n');
                Object s = l.entry.getMethod("hello", Context.class).invoke(null, l.ctx);
                sb.append("  plugin's own resource = ").append(s).append('\n');
                Object v = l.entry.getMethod("createView", Context.class).invoke(null, l.ctx);
                sb.append("  createView = ").append(v == null ? "null" : v.getClass().getName()).append('\n');
            } catch (PluginHost.PluginException e) {
                sb.append("  openInjected = REJECTED ").append(e.reason)
                        .append(" | ").append(e.detail).append('\n');
            } catch (Throwable t) {
                sb.append("  openInjected = UNEXPECTED ").append(brief(t)).append('\n');
            }
        }
        sb.append('\n').append("-- boot report --\n").append(Plugins.bootReport(ctx)).append('\n');
        sb.append("-- last skip --\n").append(Plugins.lastFail(ctx)).append('\n');
        sb.append("-- restart marker --\n").append(readReport(ctx, "restart-marker.txt")).append('\n');
        String text = sb.toString();
        writeReport(ctx, "report-plugin-status.txt", text);
        return text;
    }

    /**
     * 「立即重启」：先落 marker（重开之后才比得出"pid 真的换了"），再挂闹钟，最后自杀。
     *
     * @return {@link Restart#OK} / {@link Restart#FAILED} / {@link Restart#THROTTLED}
     */
    static int restartNow(Context ctx) {
        int r = Restart.schedule(ctx);
        if (r == Restart.THROTTLED) return r;   // 恢复重放：连 marker 都别写（否则又会误导取证）
        writeReport(ctx, "restart-marker.txt", "pid=" + android.os.Process.myPid()
                + " at=" + System.currentTimeMillis() + " time=" + new Date() + " result=" + r + "\n");
        if (r == Restart.OK) Restart.killSelf();
        return r;
    }

    /**
     * 清掉登记与报告（**不动已装的 APK** —— 卸载是界面那一轮的事）。
     */
    static String clear(Context ctx) {
        File d = Plugins.dir(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("== dev_pluginclear ==\n");
        // ⚠️ 第 133 轮把信任 / 等级 / 重试 / 跳过 / 禁记标记也一起清 —— 否则"清过"的现场还带着上一轮的
        //    读数（实测：retry 与 lastskip 留着旧值，让我误判成"这次没走 skip"）
        for (String n : new String[] { Plugins.ACTIVE, Plugins.PENDING, Plugins.GUARD,
                                        Plugins.LASTFAIL, Plugins.BOOT_REPORT, Plugins.KNOWN,
                                        Plugins.INJECTED, Plugins.LASTSKIP, Plugins.TRUST,
                                        Plugins.LEVELS, Plugins.RETRY, Plugins.NOLATCH }) {
            File f = new File(d, n);
            sb.append(n).append(" exists=").append(f.isFile())
                    .append(" deleted=").append(!f.isFile() || f.delete()).append('\n');
        }
        appendListing(sb, ctx);
        String text = sb.toString();
        writeReport(ctx, "report-plugin-clear.txt", text);
        return text;
    }

    private static void appendListing(StringBuilder sb, Context ctx) {
        sb.append("-- installed --\n");
        File d = Plugins.dirNoCreate(ctx);
        File[] ids = d.listFiles();
        if (ids == null || ids.length == 0) {
            sb.append("(none)\n");
            return;
        }
        java.util.Arrays.sort(ids);
        for (File id : ids) {
            if (id.isDirectory()) {
                File[] vs = id.listFiles();
                int n = vs == null ? 0 : vs.length;
                sb.append("  ").append(id.getName()).append("/ (").append(n).append(" files)\n");
                if (vs != null) {
                    java.util.Arrays.sort(vs);
                    for (File v : vs) {
                        sb.append("      ").append(v.getName()).append("  ").append(v.length())
                                .append(" B  canWrite=").append(v.canWrite()).append('\n');
                    }
                }
            } else {
                sb.append("  ").append(id.getName()).append("  ").append(id.length()).append(" B\n");
            }
        }
    }

    private static String tagOf(ClassLoader cl, String fqn) {
        try {
            return String.valueOf(cl.loadClass(fqn).getMethod("tag").invoke(null));
        } catch (Throwable t) {
            return "!" + brief(t);
        }
    }

    private static String baseName(String fileName) {
        if (fileName == null) return "plugin";
        String n = fileName;
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? "plugin" : n;
    }

    // ---- 第 133 轮：信任模型（官方 / 第三方）的取证口 ---------------------------

    /**
     * 把目录里每个 `.apk` 的**签名指纹**与**本应用自己的**指纹都打出来，并给出来源判定。
     *
     * <p>★ 这是信任模型唯一的判据来源：`official` 只可能出现在"与本应用同一把签名"上；
     * 夹具里必须**两侧都有样本**（同一个插件用另一把密钥再签一份），否则测不出这个判据是不是恒真。
     */
    static String trustReport(Context ctx, File dir) {
        StringBuilder sb = new StringBuilder();
        sb.append("== plugin trust (signature fingerprint) ==\n");
        sb.append("time      = ").append(new Date()).append('\n');
        sb.append("api       = ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("self fp   = ").append(PluginTrust.selfFingerprint(ctx)).append('\n');
        File[] all = dir == null ? null : dir.listFiles();
        if (all == null) {
            sb.append("(no such dir: ").append(dir).append(")\n");
        } else {
            java.util.Arrays.sort(all);
            for (File f : all) {
                if (!f.isFile() || !f.getName().endsWith(".apk")) continue;
                sb.append("- ").append(f.getName())
                        .append("\n    fp      = ").append(PluginTrust.apkFingerprint(ctx, f))
                        .append("\n    verdict = ").append(PluginTrust.classify(ctx, f))
                        .append('\n');
            }
        }
        String text = sb.toString();
        writeReport(ctx, "report-plugin-trust.txt", text);
        return text;
    }

    private static void writeReport(Context ctx, String name, String text) {
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), name), text);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "plugin report write failed: " + t);
        }
    }

    private static String readReport(Context ctx, String name) {
        try {
            File f = new File(Data.hubDir(ctx), name);
            return f.isFile() ? Util.readText(f) : "(none)\n";
        } catch (Throwable t) {
            return "(read failed: " + t + ")\n";
        }
    }
}
