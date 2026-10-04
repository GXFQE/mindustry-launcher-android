package io.mdt.launcher;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * ★★ 主进程的**未捕获异常落盘**（2026-10-04，第 86 轮）。
 *
 * ── 为什么必须补这一块 ────────────────────────────────────────────────────
 * 本工程主进程原来**一个字节证据都不留**：
 *   · `<数据根>/crashes/*.txt` 是**游戏**写的（见 {@link LogActivity} 的注释）；
 *   · 部分 ROM 还会**滤掉应用自己的 logcat**（工程固定纪律：运行期证据一律落文件，
 *     REF §14）。
 * ⇒ 启动器自己崩了（典型：后台任务收尾把弹窗打在已销毁的实例上，抛
 *   `WindowManager$BadTokenException`）就只是"应用消失了"，用户报"闪退"时手上什么都没有。
 *   第 86 轮已经用 {@link Util#dead} 把**能防住的那一类**防住了，但**防不住的那些**必须留证据。
 *
 * ── 三条纪律 ─────────────────────────────────────────────────────────────
 *  ① **只在主进程装**（`Application.onCreate` 在两个进程都会跑）：`:game` 是游戏的进程，
 *     它有自己的崩溃转储，我们不去抢。
 *  ② 处理完**必须把异常交回默认处理器** —— 否则系统不会弹"应用已停止运行"，
 *     用户与我们都更难判断发生了什么。
 *  ③ **落盘失败一律吞掉**：崩溃路径上再抛异常只会把现场弄得更糟。
 *
 * 落点 = `<数据根>/crashes/crash_<毫秒>_launcher.txt`，**命名刻意与游戏那份同构**
 * （`crash_<毫秒>.txt`）：日志页的「崩溃堆栈」是按**文件名倒序**排的
 * （{@link LogActivity} 的注释写着"名字就是写入时的毫秒时间戳 ⇒ 字典序 = 时间序"），
 * 时间戳字段等宽 ⇒ 加一段 `_launcher` 后缀不影响排序，却能把两份来源分开。
 * 顺带白拿那套既有的「清理崩溃报告」保留策略。
 */
final class Crash {

    private Crash() {}

    /** 装在 `LauncherApp.onCreate`（内部自己判"是不是主进程"）。 */
    static void installIfMainProcess(final Context ctx) {
        if (!Util.isMainProcess(ctx)) return;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    write(app, t, e);
                } catch (Throwable ignored) {
                    // ③ 崩溃路径上绝不二次抛出
                }
                if (def != null) def.uncaughtException(t, e);   // ② 交回默认处理器
            }
        });
    }

    /**
     * 写一份崩溃报告。返回落点；写不出去返回 null（调用方不必处理 —— 现场只有尽力而为）。
     * ⚠️ 报告内容是**给维护者的**（异常栈原文），所以不受"少术语"那条纪律约束；
     *   之所以落进日志页能看见的目录，是为了让用户按提示"把崩溃报告导出来"时真的有东西可导。
     */
    static File write(Context ctx, Thread t, Throwable e) {
        File dir = crashDir(ctx);
        if (dir != null) {
            File out = new File(dir, "crash_" + System.currentTimeMillis() + "_launcher.txt");
            if (dump(ctx, out, t, e)) return out;
        }
        // 兜底：外部存储拿不到时写内部（日志页看不到，但证据还在）
        try {
            File priv = Paths.privateDir(ctx);
            if (priv != null) {
                File out = new File(priv, "crash_" + System.currentTimeMillis() + "_launcher.txt");
                if (dump(ctx, out, t, e)) return out;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** `<数据根>/crashes/`（拿不到就 null；只在需要时 mkdirs） */
    private static File crashDir(Context ctx) {
        try {
            File root = Data.dataRoot(ctx);
            if (root == null) return null;
            File d = new File(root, "crashes");
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 真正落盘：原子写（先 .tmp 再改名），失败返回 false */
    private static boolean dump(Context ctx, File out, Thread t, Throwable e) {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write("MDT 安卓启动器 —— 崩溃报告\n");
            w.write("时刻：" + new java.util.Date().toString()
                    + "（" + System.currentTimeMillis() + "）\n");
            w.write("线程：" + (t == null ? "?" : t.getName())
                    + "（id=" + (t == null ? "?" : String.valueOf(t.getId())) + "）\n");
            w.write("版本：" + versionOf(ctx) + "\n");
            w.write("当前槽：" + safeCurrentSlot(ctx) + "\n");
            w.write("数据根：" + safeDataRoot(ctx) + "\n");
            w.write("\n");
            // 异常原文 + 完整栈（这才是要留的东西）
            w.write(String.valueOf(e));
            w.write("\n");
            if (e != null) {
                java.io.PrintWriter pw = new java.io.PrintWriter(w);
                e.printStackTrace(pw);
                pw.flush();
            }
            w.flush();
            w.close();
            w = null;
            if (out.exists() && !out.delete()) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String versionOf(Context ctx) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + "（vc " + pi.versionCode + "，debuggable="
                    + ((ctx.getApplicationInfo().flags
                        & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) + "）";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeCurrentSlot(Context ctx) {
        try {
            return Data.currentSlot(ctx);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeDataRoot(Context ctx) {
        try {
            File f = Data.dataRoot(ctx);
            return f == null ? "?" : f.getAbsolutePath();
        } catch (Throwable t) {
            return "?";
        }
    }
}
