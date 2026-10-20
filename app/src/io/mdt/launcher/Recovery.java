package io.mdt.launcher;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.util.List;

/**
 * 启动时的**临时文件自愈**（第 177 轮，杀后台 P0）。
 *
 * <p>起因（2026-10-19 真机实测）：长操作被 force-stop / 清理器 / 系统回收打断之后，
 * 盘上留下的是这些"没有主人"的东西 ——
 * <ul>
 *   <li>{@code app_hub/zip-import.tmp}（整槽导入 stage 完、还没解包，34 MB）；</li>
 *   <li>{@code app_hub/import/*.part}（APK 导入拷到一半，78 MB）；</li>
 *   <li>{@code app_hub/import/*.old}（同名重导的"旧包让位"窗口，必须改回去）；</li>
 *   <li>{@code cache/export/*}（整槽导出先落缓存再拷给 SAF 的中间件）；</li>
 *   <li>{@code cache/batch-*}（批量导入的临时目录）；</li>
 *   <li>{@code cas/objects/tmp/obj-*.part}（自动备份被强杀的 90 MB 暂存件，第 179 轮）；</li>
 *   <li>force-stop 留下的 {@code launchid.dat}（无更新的崩溃报告才清，带熔断，第 179 轮）；</li>
 *   <li>原子写的 {@code *.tmp}（`Util.atomicWriteText` 在 rename 前被杀）。</li>
 * </ul>
 *
 * <p>★ 为什么放在 {@code LauncherApp.onCreate} **同步**跑：启动这一刻**不可能**有
 * 我们的长操作在跑（线程随旧进程一起死了；新进程的 UI 还没起来），所以此刻清扫
 * 没有"删到正在写的文件"的竞态 —— 这比"后台线程 + 年龄阈值"更简单、也更彻底。
 * 只有一个例外：批量导入的临时目录可能包含很多文件，递归删除放后台（且按年龄过滤）。
 *
 * <p>⚠️ 与 {@code Data.gameAlive} 的关系：主进程可能在 {@code :game} 还活着时被重建
 * （系统回收主进程、游戏还在跑）。那时**一律不动**——游戏可能正在写槽。
 *
 * <p>清理结果写 {@code hub/report-recovery.txt}（只在真清了东西时写），供排障时对时间线。
 */
final class Recovery {
    private static final String TAG = "MDTLauncher";
    private static final String REPORT = "report-recovery.txt";

    /** 批量临时目录的门槛：超过这么久还没被 {@link BatchIo#cleanup} 删掉的必然是死件 */
    private static final long BATCH_STALE_MS = 60L * 60L * 1000L;

    private Recovery() {}

    /**
     * 同步清一遍。返回一行人读摘要（没做事返回空串）。
     * 调用点：{@link LauncherApp#onCreate}（主进程、且没有游戏在跑时）。
     */
    static String runNow(Context ctx) {
        StringBuilder sb = new StringBuilder();

        try {
            String r = Importer.recover(ctx);
            if (r != null && !r.isEmpty()) sb.append(r);
        } catch (Throwable t) {
            Log.w(TAG, "recovery: importer sweep failed", t);
        }

        File priv = Paths.privateDir(ctx);
        // 1) 整槽导入 stage 的中间件（固定名；新进程里不可能是活的）
        del(sb, new File(priv, "zip-import.tmp"), "zip import temp");
        del(sb, new File(priv, "zip-import.tmp.part"), "zip import temp (.part)");
        // 1b) 被强杀在**解包中途**的整槽导入：标记里有目标槽 ⇒ 清该槽的 `*.part`
        //     （第 178 轮，杀后台 P1；`SlotZip.importSlot` 写标记、成功/失败都清）
        try {
            JSONObject im = Pending.take(ctx, SlotZip.PEND_IMPORT);
            if (im != null) {
                String slot = im.optString("slot", "");
                int n = sweepSlotParts(ctx, slot);
                sb.append("interrupted slot import: \"").append(slot)
                  .append("\" (removed ").append(n).append(" .part files)\n");
            }
        } catch (Throwable t) {
            Log.w(TAG, "recovery: slot import sweep failed", t);
        }
        // 2) 原子写残留（rename 之前被杀）
        File[] pfs = priv.listFiles();
        if (pfs != null) {
            for (File f : pfs) {
                if (f.isFile() && f.getName().endsWith(".tmp")) {
                    del(sb, f, "atomic write temp");
                }
            }
        }
        // 3) 导出缓存（第 177 轮起：zip 先落这里、完成后再拷给 SAF）
        File ex = Exporter.exportCacheDir(ctx);
        File[] exs = ex == null ? null : ex.listFiles();
        if (exs != null) {
            for (File f : exs) {
                del(sb, f, "export cache");
            }
        }
        // 3b) 语言模板的临时件（设置页"导出翻译模板"在选择器打开时被杀）
        del(sb, new File(ctx.getCacheDir(), "lang.properties.tmp"), "language template temp");
        // 3c) CAS 写入暂存区：自动备份被杀留下的 `objects/tmp/obj-*.part`
        //     （第 179 轮，杀后台 P2 —— 原来要等 1 h 或下一次备份才清）
        try {
            int n = new Cas(Backup.casBaseDir(ctx)).sweepTmpNow();
            if (n > 0) {
                sb.append("dropped ").append(n).append(" CAS temp object(s)\n");
            }
        } catch (Throwable t) {
            Log.w(TAG, "recovery: CAS tmp sweep failed", t);
        }
        // 3d) launchid.dat 自愈：force-stop / 清理器留下的 marker（无崩溃报告才清，带熔断）
        try {
            String lm = LaunchMarker.maybeClear(ctx, Data.currentSlot(ctx));
            if (lm != null) sb.append(lm);
        } catch (Throwable t) {
            Log.w(TAG, "recovery: launchid auto-clear failed", t);
        }

        // 4) 批量导入的临时目录：可能几百个文件，按年龄过滤后放后台删
        final File cache = ctx.getCacheDir();
        new Thread(new Runnable() {
            @Override public void run() {
                sweepBatchDirs(cache);
            }
        }, "recovery-batch").start();

        if (sb.length() > 0) {
            Log.i(TAG, "startup recovery:\n" + sb);
            try {
                Util.atomicWriteText(new File(Data.hubDir(ctx), REPORT),
                        "startup recovery " + new java.util.Date() + "\n" + sb);
            } catch (Throwable t) {
                Log.w(TAG, "recovery report write failed", t);
            }
        }
        return sb.toString();
    }

    /**
     * 被中断的整槽导入：只清**那个槽**内容根下的 `*.part`。
     *
     * <p>为什么需要标记而不是全盘扫：`.part` 是 `SlotZip` 逐文件写盘的中间态，
     * 名字可能是任意数据文件（`saves/x.msav.part` / `mods/foo.jar.part`）。
     * 启动时没有我们的长操作在跑，但用户/模组自带一个合法 `.part` 文件也不是不可能
     * ⇒ 只有"确实有导入标记"的槽才动。
     */
    private static int sweepSlotParts(Context ctx, String slot) {
        if (slot == null || slot.trim().isEmpty()) return 0;
        int[] n = {0};
        try {
            List<File> roots = Data.contentRoots(ctx, slot);
            for (File f : roots) sweepParts(f, n);
        } catch (Throwable t) {
            Log.w(TAG, "recovery: slot part sweep failed (" + slot + ")", t);
        }
        return n[0];
    }

    private static void sweepParts(File f, int[] n) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) sweepParts(c, n);
            }
            return;
        }
        if (f.getName().endsWith(".part") && f.delete()) n[0]++;
    }

    private static void sweepBatchDirs(File cache) {
        if (cache == null) return;
        File[] fs = cache.listFiles();
        if (fs == null) return;
        long cutoff = System.currentTimeMillis() - BATCH_STALE_MS;
        for (File f : fs) {
            if (!f.isDirectory() || !f.getName().startsWith("batch-")) continue;
            if (f.lastModified() > cutoff) continue;       // 可能正被用（新起的）
            Data.deleteTree(f);
            Log.i(TAG, "recovery: dropped stale batch dir " + f.getName());
        }
    }

    private static void del(StringBuilder sb, File f, String what) {
        try {
            if (f == null || !f.exists()) return;
            long len = f.length();
            if (Data.deleteTree(f)) {
                sb.append("dropped ").append(what).append(": ").append(f.getName())
                  .append(" (").append(len).append(" B)\n");
            } else {
                sb.append("could not drop ").append(what).append(": ")
                  .append(f.getAbsolutePath()).append('\n');
            }
        } catch (Throwable t) {
            Log.w(TAG, "recovery: drop failed " + f, t);
        }
    }
}
