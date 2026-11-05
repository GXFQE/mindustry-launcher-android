package io.mdt.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * `:game` 进程里的**验证台**（dev 专用，产品版是空壳 {@code app/src-release}）。
 *
 * <p>验证四件事：
 * <ol>
 *   <li>游戏 dex 注入后，探针类能不能用 `:game` 的 CL 解析到**游戏类**（`mindustry.Vars`）；</li>
 *   <li>插件的探针 APK 能不能用 `DexClassLoader`（parent = 进程 CL）加载并实例化；</li>
 *   <li>探针能不能往宿主给的 telemetry 目录写文件；</li>
 *   <li><b>补丁实验</b>（2026-10-30）：把一份"同名类的完整替代类"dex **prepend** 到 base CL，
 *       验证它能不能顶掉游戏类、且游戏还能起来（{@code hub/patch-lab.txt} 触发）。</li>
 * </ol>
 *
 * <p>触发：`hub/probe-lab.txt` 里写探针 APK 的路径（adb push 即可），启动游戏，
 * 跑完写 `hub/report-probe-lab.txt` 并删掉标记；补丁实验同理，标记是 `hub/patch-lab.txt`，
 * 报告是 `hub/report-patch-lab.txt`。
 */
final class ProbeLab {

    private ProbeLab() { }

    static void runIfArmed(Context ctx) {
        File armed = new File(Data.hubDir(ctx), "probe-lab.txt");
        if (armed.isFile()) runProbeLab(ctx, armed);
        File patchArmed = new File(Data.hubDir(ctx), "patch-lab.txt");
        if (patchArmed.isFile()) runPatchLab(ctx, patchArmed);
        if (sAssetLab) runAssetLab(ctx);
    }

    // ── 素材（资源补丁）实验 · 2026-11-05 ────────────────────────────────

    private static boolean sAssetLab;
    private static File sAssetLabApk;
    private static final java.util.List<File> sAssetLabFiles = new java.util.ArrayList<File>();
    private static final StringBuilder sAssetLabNotes = new StringBuilder();

    /** 资源实验要看的键（文本 + 二进制各一个；`patch-lab-root/` 是不带 `assets/` 前缀的对照组）。 */
    private static final String[] ASSET_KEYS = {
            "version.properties", "patch-lab/hello.txt", "patch-lab/from-apk.txt",
            "patch-lab/stored.txt", "patch-lab-root/hello.txt", "cursors/cursor.png"
    };

    /**
     * 素材实验的**装填**阶段：在 {@code Injector.launch} 之前把增量资产包塞进 plan。
     *
     * <p>为什么必须早于 launch：{@code addAssetPath} 只对**之后**创建 / 之后补挂的
     * AssetManager 生效（游戏那个 activity 的链由 {@code LauncherApp.preCreated} 再挂一遍）；
     * 等 {@link #runIfArmed} 再挂就晚了 —— 那时游戏 activity 已经带着旧链建好了。
     *
     * <p>触发文件：{@code hub/asset-lab.txt}，一行一个 zip / APK 路径（`#` 开头当注释）。
     * 读一次即删；报告写 {@code hub/report-asset-lab.txt}。
     */
    static void decorate(Context ctx, Injector.Plan plan) {
        // 每次都清一遍：:game 进程被复用时（同一进程连拉两次 GameSlot）不会拿上一轮的数据重写报告
        sAssetLab = false;
        sAssetLabApk = null;
        sAssetLabFiles.clear();
        sAssetLabNotes.setLength(0);
        try {
            File armed = new File(Data.hubDir(ctx), "asset-lab.txt");
            if (!armed.isFile()) return;
            String text = readText(armed);
            armed.delete();
            sAssetLab = true;
            sAssetLabApk = plan.apk;
            for (String line : text.split("\\r?\\n")) {
                String p = line.trim();
                if (p.isEmpty() || p.startsWith("#")) continue;
                File f = new File(p);
                if (f.isFile()) {
                    plan.assetDeltas.add(f);
                    sAssetLabFiles.add(f);
                } else {
                    sAssetLabNotes.append("missing = ").append(p).append('\n');
                }
            }
        } catch (Throwable t) {
            sAssetLab = true;
            sAssetLabNotes.append("decorate FAILED: ").append(t).append('\n');
        }
    }

    /**
     * 素材实验的**读数**阶段（launch 之后）：直接问 AssetManager 要那几个键，
     * 对照游戏 APK 里的原值，看「增量包是否覆盖同名 / 新文件是否可见 / 布局要求」。
     *
     * <p>⚠️ 这里量的是 AM 层；「游戏是否真的按它读」还要看主菜单版本号那条端到端判据
     * （被覆盖的 {@code version.properties} 会让主菜单那行变掉）。
     */
    private static void runAssetLab(Context ctx) {
        StringBuilder sb = new StringBuilder("== asset lab (:game) ==\n");
        sb.append("pid = ").append(android.os.Process.myPid()).append('\n');
        sb.append("apk = ").append(sAssetLabApk == null ? "(null)" : sAssetLabApk.getAbsolutePath()).append('\n');
        sb.append(sAssetLabNotes);

        AssetManager amAct = ctx.getAssets();
        AssetManager amApp = ctx.getApplicationContext().getAssets();
        sb.append("amAct == amApp = ").append(amAct == amApp).append('\n');

        sb.append("deltas (").append(sAssetLabFiles.size()).append("):\n");
        for (File f : sAssetLabFiles) {
            sb.append("  ").append(f.getName()).append(' ').append(f.length()).append(" B\n");
        }

        sb.append("baseline (game apk zip):\n");
        sb.append("  ").append(zipProbe(sAssetLabApk, "assets/version.properties")).append('\n');
        sb.append("  ").append(zipProbe(sAssetLabApk, "assets/patch-lab/hello.txt")).append('\n');

        sb.append("served (activity AM):\n");
        for (String key : ASSET_KEYS) sb.append("  ").append(serveProbe(amAct, key)).append('\n');
        sb.append("served (application AM):\n");
        for (String key : ASSET_KEYS) sb.append("  ").append(serveProbe(amApp, key)).append('\n');

        // openFd 只对 zip 里**不压缩**的条目有效；补丁包如果要被 Fi.length()/map() 用到就得存成 stored
        sb.append("openFd(activity AM):\n");
        sb.append("  ").append(fdProbe(amAct, "version.properties")).append('\n');
        sb.append("  ").append(fdProbe(amAct, "patch-lab/stored.txt")).append('\n');

        try {
            sb.append("list(\"\") = ")
                    .append(java.util.Arrays.toString(amAct.list(""))).append('\n');
        } catch (Throwable t) {
            sb.append("list(\"\") = FAIL ").append(t).append('\n');
        }
        writeText(new File(Data.hubDir(ctx), "report-asset-lab.txt"), sb.toString());
    }

    /** 通过 AssetManager 读一个键（游戏读内部资产的同一条路），返回 `key = 长度 B [首行]`。 */
    private static String serveProbe(AssetManager am, String key) {
        if (am == null) return key + " = no-am";
        InputStream in = null;
        try {
            in = am.open(key);
            byte[] buf = new byte[8192];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return key + " = " + n + " B" + snippet(buf, n);
        } catch (Throwable t) {
            return key + " = MISSING / " + t.getClass().getSimpleName();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 直接按 zip 条目读游戏 APK 里的原值（对照组，不经 AssetManager）。 */
    private static String zipProbe(File apk, String entry) {
        if (apk == null || !apk.isFile()) return entry + " = (no apk)";
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry(entry);
            if (e == null) return entry + " = ABSENT";
            InputStream in = z.getInputStream(e);
            byte[] buf = new byte[8192];
            int n = 0, r;
            try {
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            } finally {
                in.close();
            }
            return entry + " = " + n + " B" + snippet(buf, n);
        } catch (Throwable t) {
            return entry + " = FAIL " + t;
        } finally {
            try {
                if (z != null) z.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * `openFd` 探测：只有 zip 里**不压缩**（stored）的条目才能拿到 fd。
     * 补丁包如果要被 `Fi.length()` / `Fi.map()` 用到（素材加载里少数路径），就必须存成 stored。
     */
    private static String fdProbe(AssetManager am, String key) {
        if (am == null) return key + " = no-am";
        android.content.res.AssetFileDescriptor fd = null;
        try {
            fd = am.openFd(key);
            return key + " = OK length=" + fd.getLength() + " declared=" + fd.getDeclaredLength();
        } catch (Throwable t) {
            return key + " = FAIL / " + t.getClass().getSimpleName();
        } finally {
            try {
                if (fd != null) fd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 文本给首行（去 CR、按 `|` 拼行）；二进制或非文本只给长度，别把 png 打进报告。 */
    private static String snippet(byte[] buf, int n) {
        for (int i = 0; i < n; i++) {
            int c = buf[i] & 0xff;
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return "";
        }
        String s = new String(buf, 0, n, java.nio.charset.Charset.forName("UTF-8"));
        s = s.replace("\r", "").trim();
        if (s.isEmpty()) return "";
        if (s.length() > 240) s = s.substring(0, 240) + "…";
        return " [" + s.replace("\n", " | ") + "]";
    }

    /** 原探针验证台（①②③）。 */
    private static void runProbeLab(Context ctx, File armed) {
        String apk = readText(armed);
        armed.delete();
        StringBuilder sb = new StringBuilder("== probe lab (:game) ==\n");
        sb.append("pid = ").append(android.os.Process.myPid()).append('\n');
        sb.append("apk = ").append(apk).append('\n');
        ClassLoader cl = ProbeLab.class.getClassLoader();
        sb.append("host cl = ").append(cl.getClass().getName()).append('\n');

        // ① 游戏类可见性（只用 forName(..., false)：不初始化 Vars，避免副作用）
        try {
            Class<?> vars = Class.forName("mindustry.Vars", false, cl);
            boolean state = false;
            try {
                vars.getDeclaredField("state");
                state = true;
            } catch (Throwable ignored) { }
            sb.append("Vars = OK, fields=").append(vars.getDeclaredFields().length)
                    .append(", state=").append(state).append('\n');
        } catch (Throwable t) {
            sb.append("Vars = FAIL ").append(t).append('\n');
        }

        // ②③ 插件探针加载 + 写 telemetry
        try {
            // 🔴 Android 14+ 不许 DexClassLoader 加载**可写**路径（真机实测：
            //   "Writable dex file ... is not allowed"）⇒ 先复制到内部存储并置只读
            //   （和插件注入那条 S0 规则同源）。
            File internal = new File(ctx.getFilesDir(), "probe-lab");
            if (!internal.isDirectory() && !internal.mkdirs()) {
                throw new java.io.IOException("mkdirs failed: " + internal);
            }
            File copy = new File(internal, "probe.apk");
            // 上一轮置了只读 ⇒ 先删再复制（"置只读的副本不能再被覆盖"是插件注入那条 S0 的同一坑）
            if (copy.exists() && !copy.delete()) {
                sb.append("old copy delete = FAIL\n");
            }
            copyFile(new File(apk), copy);
            copy.setReadOnly();
            sb.append("internal copy = ").append(copy.length()).append(" B\n");
            dalvik.system.DexClassLoader dl = new dalvik.system.DexClassLoader(
                    copy.getAbsolutePath(), null, null, cl);
            Class<?> c = Class.forName("io.mdt.plugtest.content.ProbeFixture", true, dl);
            Object p = c.getDeclaredConstructor().newInstance();
            File dir = new File(Data.hubDir(ctx), "usage/telemetry/lab");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                sb.append("telemetry dir = FAIL (mkdirs)\n");
            } else {
                c.getMethod("reset", io.mdt.launcher.api.ProbeEnv.class).invoke(p,
                        new io.mdt.launcher.api.ProbeEnv(dir,
                                new File(Data.hubDir(ctx), "usage/lab-data"),
                                android.os.Process.myPid(), true, Data.currentSlot(ctx), "lab",
                                java.util.Collections.singletonMap("displayVersion", "lab")));
                c.getMethod("sample").invoke(p);
                File out = new File(dir, "probe.tsv");
                sb.append("probe = OK ").append(c.getName()).append('\n');
                sb.append("telemetry = ").append(out.isFile() ? out.length() + " B" : "MISSING")
                        .append(" @ ").append(out.getAbsolutePath()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("probe = FAIL ").append(t).append('\n');
        }
        writeText(new File(Data.hubDir(ctx), "report-probe-lab.txt"), sb.toString());
    }

    /**
     * 补丁实验（④，2026-10-30）：**同名类覆盖**的实机判据。
     *
     * <p>补丁 APK 只含一个「完整类 + 一个标记字段」；prepend 到 `:game` 的 base CL 之后，
     * `Class.forName(目标类, false, cl)` 拿到的如果是补丁版本，`PATCH_MARKER` 就反射得到；
     * 如果目标类已经被提前加载，拿到的还是原版（没有标记）——这一条正是「覆盖只在类加载前有效」的判据。
     *
     * <p>报告写 `hub/report-patch-lab.txt`；写完之后游戏继续启动（崩不崩看 logcat）。
     */
    private static void runPatchLab(Context ctx, File armed) {
        String text = readText(armed);
        armed.delete();
        StringBuilder sb = new StringBuilder("== patch lab (:game) ==\n");
        sb.append("pid = ").append(android.os.Process.myPid()).append('\n');
        ClassLoader cl = ProbeLab.class.getClassLoader();
        try {
            File internal = new File(ctx.getFilesDir(), "patch-lab");
            if (!internal.isDirectory() && !internal.mkdirs()) {
                throw new java.io.IOException("mkdirs failed: " + internal);
            }
            // 一行一个补丁；后 inject 的排最前（prepend）⇒ 用它验证「两个补丁改同一个类谁赢」
            String[] lines = text.split("\\r?\\n");
            int idx = 0;
            for (String line : lines) {
                String patch = line.trim();
                if (patch.isEmpty()) continue;
                sb.append("patch[").append(idx).append("] = ").append(patch).append('\n');
                File copy = new File(internal, "patch-" + idx + ".apk");
                if (copy.exists() && !copy.delete()) sb.append("old copy delete = FAIL\n");
                copyFile(new File(patch), copy);
                copy.setReadOnly();
                java.util.List<String> notes = new java.util.ArrayList<String>();
                long t0 = System.nanoTime();
                int n = Reflect.injectDex(cl, copy, ctx.getCodeCacheDir(), true, notes);
                long ms = (System.nanoTime() - t0) / 1000000L;
                sb.append("inject[").append(idx).append("] = ").append(n).append(" elements, ")
                        .append(ms).append(" ms");
                for (String s : notes) sb.append(" | ").append(s);
                sb.append('\n');
                idx++;
            }

            // 目标类 = 补丁类；标记字段只在补丁版本里有。用两个类互为对照：
            // 一个偏门（主菜单不该加载）、一个可能被启动流程碰过。
            String[] targets = {"mindustry.Vars", "mindustry.game.Objectives", "mindustry.core.Version"};
            for (String target : targets) {
                try {
                    Class<?> c = Class.forName(target, false, cl);
                    boolean marker = false;
                    try {
                        c.getDeclaredField("PATCH_MARKER");
                        marker = true;
                    } catch (Throwable ignored) { }
                    sb.append("target = ").append(target).append(", marker = ").append(marker);
                    if (marker) {
                        Object v = c.getDeclaredField("PATCH_MARKER").get(null);
                        sb.append(", value = ").append(v);
                    }
                    sb.append('\n');
                } catch (Throwable t) {
                    sb.append("target = ").append(target).append(" = FAIL ").append(t).append('\n');
                }
            }

            // 方法行为：直接反射调用 buildString()/combined()，看返回的是哪个补丁的值
            try {
                Class<?> v = Class.forName("mindustry.core.Version", false, cl);
                Object bs = v.getMethod("buildString").invoke(null);
                sb.append("Version.buildString() = ").append(bs).append('\n');
                Object cb = v.getMethod("combined").invoke(null);
                sb.append("Version.combined() = ").append(cb).append('\n');
            } catch (Throwable t) {
                sb.append("Version.buildString() = FAIL ").append(t).append('\n');
            }

            // d8 产物校验：类不在宿主里，只可能在补丁 dex 里
            try {
                Class<?> pc = Class.forName("io.mdt.launcher.PatchProbe", false, cl);
                Object pv = pc.getMethod("probe").invoke(null);
                sb.append("PatchProbe.probe() = ").append(pv).append('\n');
            } catch (Throwable t) {
                sb.append("PatchProbe = FAIL ").append(t).append('\n');
            }
        } catch (Throwable t) {
            sb.append("patch lab = FAIL ").append(t).append('\n');
        }
        writeText(new File(Data.hubDir(ctx), "report-patch-lab.txt"), sb.toString());
    }

    private static void copyFile(File src, File dst) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readText(File f) {        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 4096)];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                return new String(b, 0, n, "UTF-8").trim();
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.w("MDTPlugin", "probe lab report failed", t);
        }
    }
}
