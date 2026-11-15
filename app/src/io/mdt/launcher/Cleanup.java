package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 「清理可清理项」的**唯一实现**（第 383 轮；用户：「反正这些都是可以自动清理的，不如合并到一起」）。
 *
 * <h3>三样东西，一个动作</h3>
 * <ol>
 *   <li><b>游戏缓存</b> —— `getCacheDir()` + `getCodeCacheDir()`（音乐 / V8 模组 jar / dex 产物）；
 *       有游戏实例在跑时由 {@link CacheClean} 拒绝。</li>
 *   <li><b>分享缓存</b> —— {@link ShareProvider} 借给别的 App 的那一份（`app_hub/tmp/share`）。
 *       用户主动点「清理」才动；平时按 24 h 自动修剪。</li>
 *   <li><b>旧版本重复文件</b> —— {@link Data#autoCleanRedundant}：只删**有正证据**的
 *       旧版本复制进游戏数据目录的启动器文件（`config.json` / `import/` / `natives/`），
 *       证据不足的一个都不碰。</li>
 * </ol>
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

    /** 正式入口：真实目录 / 真实实例检查 / 真实重复文件清理 */
    static Result run(Context ctx) {
        return run(ctx, null, null, null, null, null);
    }

    /**
     * 可注入版本（自检用）。
     *
     * @param cacheDir     null = {@code ctx.getCacheDir()}
     * @param codeCacheDir null = {@code ctx.getCodeCacheDir()}
     * @param gameRunning  null = 现查 {@link CacheEpoch#otherInstanceRunning}
     * @param shareDir     null = {@link ShareProvider#dirOf}
     * @param dup          null = {@link Data#autoCleanRedundant}
     */
    static Result run(Context ctx, File cacheDir, File codeCacheDir, Boolean gameRunning,
                      File shareDir, DupClean dup) {
        Result r = new Result();
        r.cache = CacheClean.gameCache(ctx, cacheDir, codeCacheDir, gameRunning);
        if (r.cache == null || !r.cache.ok) r.ok = false;

        File sd = shareDir == null ? ShareProvider.dirOf(ctx) : shareDir;
        long before = HubTmp.sizeNoFollow(sd);
        try {
            r.shareFiles = ShareOut.prune(sd, Long.MAX_VALUE);
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
}
