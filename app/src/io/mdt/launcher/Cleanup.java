package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 「清理缓存类数据」的**唯一实现**（第 383 轮；用户：「不如把所有缓存类的东西都合并给一个自动清理的开关」）。
 *
 * <h3>一个动作 / 一个开关，管三样</h3>
 * <ol>
 *   <li><b>游戏缓存</b> —— `getCacheDir()` + `getCodeCacheDir()`（音乐 / V8 模组 jar / dex 产物）；
 *       有游戏实例在跑、或刚点下启动（占位）时由 {@link CacheClean} 拒绝。</li>
 *   <li><b>分享缓存</b> —— {@link ShareProvider} 借给别的 App 的那一份（`app_hub/tmp/share`）。
 *       手动「清理」时全清；自动清理只清**超过 1 小时**的件（接收方可能还在读）。</li>
 *   <li><b>旧版本重复文件</b> —— {@link Data#autoCleanRedundant}：只删**有正证据**的
 *       旧版本复制进游戏数据目录的启动器文件（`config.json` / `import/` / `natives/`），
 *       证据不足的一个都不碰。</li>
 * </ol>
 *
 * <h3>自动清理什么时候跑</h3>
 * 主进程**冷启动**时（{@link LauncherApp#onCreate}）检查一次：开关开、游戏没在跑、
 * 距上次自动清理 ≥ 24 小时才清；时间戳落 `app_hub/auto-clean.txt`。**不是每次启动都清** ——
 * 否则每次拉起游戏前都把音乐/模组缓存删掉，下次启动变慢。
 * 手动「存储占用 → 清理」不受 24 小时限制。
 *
 * <h3>不碰什么</h3>
 * 存档 / 模组 / 地图 / 蓝图 / 导入的游戏包 / 启动器自己的数据（`app_hub` 里除了 `tmp/share`
 * 之外）—— 它们要么不可再生，要么是用户资产。
 *
 * <p>⚠️ 三样各自的**删除实现**不在这里：本类只做编排与计数，方便自检用注入的目录/清理函数
 * 验"三个都真的跑了"，也避免又抄一份删除逻辑。</p>
 */
final class Cleanup {
    private static final String TAG = "MDTLauncher";

    /** 自动清理最短间隔：24 小时（避免每次冷启动都清，见类注释） */
    static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;
    /** 自动清理分享缓存时只清超过这么久的（接收方可能还在读） */
    static final long AUTO_SHARE_GRACE_MS = 60L * 60 * 1000;

    /** 旧版本重复文件的清理函数（自检可注入；生产 = {@link Data#autoCleanRedundant}） */
    interface DupClean {
        Data.AutoClean clean();
    }

    /** 一次清理的账（界面只报总数，日志报分项） */
    static final class Result {
        CacheClean.Result cache;
        int shareFiles;
        long shareBytes;
        Data.AutoClean dup;
        /** 有任何一处删除失败（其余照常）。 */
        boolean ok = true;

        /** 三样加起来释放的字节（分享按清理前后的差值算，删不掉的不会多报）。 */
        long totalBytes() {
            long n = cache == null ? 0L : cache.bytes;
            n += Math.max(0L, shareBytes);
            n += dup == null ? 0L : dup.freed;
            return n;
        }

        /** 真的清掉了东西（不是"跑了但没东西"）。 */
        boolean any() {
            return (cache != null && cache.entries > 0)
                    || shareFiles > 0
                    || (dup != null && dup.cleaned > 0);
        }

        boolean cacheSkipped() {
            return cache != null && cache.skipped;
        }

        /** 日志用（ASCII，别进界面）。 */
        String brief() {
            return "cache=" + (cache == null ? "null" : cache.brief())
                    + " share=" + shareFiles + "/" + shareBytes + "B"
                    + " dup=" + (dup == null ? "null"
                            : (dup.cleaned + "/" + dup.freed + "B skipped=" + dup.skipped))
                    + " ok=" + ok;
        }
    }

    private Cleanup() {}

    /** 手动入口（存储占用卡上的「清理」）：三样全清，分享缓存不设时限。 */
    static Result run(Context ctx) {
        return runAt(ctx, null, null, null, null, null, Long.MAX_VALUE);
    }

    /** 自动入口：只清超过 1 小时的分享缓存，其余同手动。 */
    static Result runAuto(Context ctx) {
        return runAt(ctx, null, null, null, null, null,
                System.currentTimeMillis() - AUTO_SHARE_GRACE_MS);
    }

    /** 自检用的可注入版本（手动语义：分享缓存全清）。 */
    static Result run(Context ctx, File cacheDir, File codeCacheDir, Boolean gameRunning,
                      File shareDir, DupClean dup) {
        return runAt(ctx, cacheDir, codeCacheDir, gameRunning, shareDir, dup, Long.MAX_VALUE);
    }

    /**
     * 真入口。
     *
     * @param cacheDir     null = {@code ctx.getCacheDir()}
     * @param codeCacheDir null = {@code ctx.getCodeCacheDir()}
     * @param gameRunning  null = 现查（运行实例 **或** 启动占位）
     * @param shareDir     null = {@link ShareProvider#dirOf}
     * @param dup          null = {@link Data#autoCleanRedundant}
     * @param shareCutoff  只删 lastModified ≤ 它的分享件（{@link Long#MAX_VALUE} = 全清）
     */
    static Result runAt(Context ctx, File cacheDir, File codeCacheDir, Boolean gameRunning,
                        File shareDir, DupClean dup, long shareCutoff) {
        Result r = new Result();
        r.cache = CacheClean.gameCache(ctx, cacheDir, codeCacheDir, gameRunning,
                shareCutoff == Long.MAX_VALUE);
        if (r.cache == null || !r.cache.ok) r.ok = false;

        File sd = shareDir == null ? ShareProvider.dirOf(ctx) : shareDir;
        long before = HubTmp.sizeNoFollow(sd);
        try {
            r.shareFiles = ShareOut.prune(sd, shareCutoff);
        } catch (Throwable t) {
            r.ok = false;
            Log.w(TAG, "cleanup: share prune failed", t);
        }
        r.shareBytes = Math.max(0L, before - HubTmp.sizeNoFollow(sd));

        try {
            r.dup = dup == null ? Data.autoCleanRedundant(ctx) : dup.clean();
        } catch (Throwable t) {
            r.ok = false;
            Log.w(TAG, "cleanup: duplicate sweep failed", t);
        }
        if (r.dup == null) r.dup = new Data.AutoClean();

        Log.i(TAG, "cleanup: " + r.brief());
        return r;
    }

    // ── 自动清理的调度 ──────────────────────────────────────────────────────

    /**
     * 主进程冷启动时调一次。**不阻塞启动**：条件满足才起后台线程。
     *
     * <p>条件：进程名**精确等于包名**（`isMainProcess` 拿不准时会当主进程，不能单靠它；
     * `:game` 里 `otherInstanceRunning` 又会排除自己 —— 两道兜底叠起来可能误清正在用的 cache）、
     * 开关开、没有游戏在跑、距上次 ≥ {@link #AUTO_INTERVAL_MS}。</p>
     */
    static void autoCleanInBackground(Context ctx) {
        if (ctx == null) return;
        if (!Util.isMainProcess(ctx)) return;
        // ★ 必须**精确**是主进程：isMainProcess 拿不到进程名时会当主进程，而 :game 里
        //   otherInstanceRunning 会把自己排除 —— 两道兜底叠在一起就可能让 :game 清掉
        //   自己正在用的 cache。拿不准就不清（自动清理不值得冒这个险）。
        String proc = Util.processName();
        if (proc == null || !proc.equals(ctx.getPackageName())) return;
        if (!Config.get().autoCleanRedundant()) return;
        // 不用 Data.gameAlive（构建门禁把它冻结在 9；这里只需要"有没有别的游戏实例"）
        if (CacheEpoch.otherInstanceRunning(ctx)) return;
        long now = System.currentTimeMillis();
        if (!autoDue(stampFile(ctx), now)) return;

        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 进线程后再判一次：等待期间用户可能已经点了启动
                    if (CacheEpoch.otherInstanceRunning(app)) return;
                    Result r = runAuto(app);
                    markAuto(stampFile(app), System.currentTimeMillis());
                    Log.i(TAG, "auto cleanup: " + r.brief());
                } catch (Throwable t) {
                    Log.w(TAG, "auto cleanup failed", t);
                }
            }
        }, "auto-clean").start();
    }

    /** 自动清理的时间戳（`app_hub/auto-clean.txt`，cache 外面 —— 系统清缓存不会把它弄丢）。 */
    static File stampFile(Context ctx) {
        return new File(Paths.privateDir(ctx), "auto-clean.txt");
    }

    /**
     * 距上次自动清理是否已满 {@link #AUTO_INTERVAL_MS}。
     * 没记录 / 读不出来 / 时间戳在未来（用户改过系统时间）一律算"该清了"。
     */
    static boolean autoDue(File stamp, long now) {
        long last = 0L;
        try {
            if (stamp != null && stamp.isFile()) {
                last = Long.parseLong(Util.readText(stamp).trim());
            }
        } catch (Throwable ignored) {
            last = 0L;
        }
        if (last <= 0L) return true;
        if (last > now + 60_000L) return true;      // 时钟回拨：别把"未来"当成刚清过
        return now - last >= AUTO_INTERVAL_MS;
    }

    /** 记下这次自动清理的时刻（写失败只记日志：下次还会再试，方向安全）。 */
    static void markAuto(File stamp, long now) {
        try {
            Util.atomicWriteText(stamp, String.valueOf(now));
        } catch (Throwable t) {
            Log.w(TAG, "auto cleanup: cannot write stamp", t);
        }
    }
}
