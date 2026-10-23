package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * `:game` 进程里的**探针验证台**（dev 专用，产品版是空壳 {@code app/src-release}）。
 *
 * <p>验证三件事（用户 2026-10-23：「先把要验证的都验证下」）：
 * <ol>
 *   <li>游戏 dex 注入后，探针类能不能用 `:game` 的 CL 解析到**游戏类**（`mindustry.Vars`）；</li>
 *   <li>插件的探针 APK 能不能用 `DexClassLoader`（parent = 进程 CL）加载并实例化；</li>
 *   <li>探针能不能往宿主给的 telemetry 目录写文件。</li>
 * </ol>
 *
 * <p>触发：`hub/probe-lab.txt` 里写探针 APK 的路径（adb push 即可），启动游戏，
 * 跑完写 `hub/report-probe-lab.txt` 并删掉标记。
 */
final class ProbeLab {

    private ProbeLab() { }

    static void runIfArmed(Context ctx) {
        File armed = new File(Data.hubDir(ctx), "probe-lab.txt");
        if (!armed.isFile()) return;
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
                c.getMethod("reset", File.class).invoke(p, dir);
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

        // ④ 顺带验 `:probe` 隔离进程：让它读**当前** `:game` 进程的 /proc（同 UID、跨进程）
        try {
            android.content.Intent svc = new android.content.Intent(ctx, ProbeService.class);
            svc.putExtra(ProbeService.EXTRA_PID, android.os.Process.myPid());
            ctx.startService(svc);
        } catch (Throwable t) {
            Log.w("MDTPlugin", "probe service start failed", t);
        }
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
