package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

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
