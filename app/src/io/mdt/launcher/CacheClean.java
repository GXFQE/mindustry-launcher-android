package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 「清缓存」的**唯一实现**（阶段 0.4，2026-11-15 第 382 轮）。
 *
 * <h3>清什么 / 不清什么</h3>
 *
 * <p>清：`getCacheDir()` 下的所有条目（游戏音乐、V8 的模组 jar 缓存、Rhino 产物、临时件）
 * 与 `getCodeCacheDir()` 下的 dex 产物（下次启动会按需重建）。</p>
 *
 * <p><b>不清</b>：`files/`（模组的持久数据 / temp-save）、槽目录（存档 / 模组 / 地图 / 蓝图）、
 * `app_hub/import`（导入的游戏包）、`app_hub/plugins` / `patches`、以及 `app_hub/tmp`
 * 里**正在用**的件（那一条由 {@link HubTmp#sweep} 按"主人还活着"判，用户主动清缓存时
 * 只顺带剪掉 24h 前的分享件）。</p>
 *
 * <h3>为什么必须有用户入口</h3>
 *
 * HUB 的临时件与游戏 cache 都从"系统 cache"搬进了自管理目录（阶段 0.1 / 1.x）——
 * 代价是**系统"清除缓存"按钮清不到它们**，所以必须由我们自己提供入口（plan §12.30/§12.32）。
 *
 * <p>⚠️ 游戏在跑时**一律拒绝**：清 `getCacheDir()` 会把游戏正在用的音乐 / 模组 jar 抽走
 * （症状是音频静默 / 模组加载失败，而且很难归因）。判据复用
 * {@link CacheEpoch#otherInstanceRunning}（占位不算实例）。</p>
 *
 * <p>⚠️ 只动这两个**根目录的直接子项**，不删根本身：`cache` / `code_cache` 由系统创建，
 * 删了会让下一次 `getCacheDir()` 自己重建（通常没事，但没必要碰）。**不跟符号链接**。</p>
 */
final class CacheClean {
    private static final String TAG = "MDTLauncher";

    private CacheClean() {}

    /** 一次清理的账（顶层条目数 / 字节数；界面只用字节数） */
    static final class Result {
        /** 还有实例在跑 ⇒ 拒绝（什么都没动）。 */
        boolean skipped;
        /** 有删不掉的条目（只读 / 被占用）⇒ false；其余照删。 */
        boolean ok = true;
        /** 顶层条目数（游戏 cache + dex 产物）。 */
        int entries;
        /** 实际删掉的字节数。 */
        long bytes;

        String brief() {
            if (skipped) return "skipped: a game is running";
            return (ok ? "cleared " : "partly cleared ") + entries + " entr(ies) / "
                    + bytes + " B";
        }
    }

    /** 正式入口：真实目录 / 真实实例检查 */
    static Result gameCache(Context ctx) {
        return gameCache(ctx, null, null, null);
    }

    /**
     * 可注入版本（自检用）。
     *
     * @param cacheDir     null = {@code ctx.getCacheDir()}
     * @param codeCacheDir null = {@code ctx.getCodeCacheDir()}
     * @param otherRunning null = 现查 {@link CacheEpoch#otherInstanceRunning}
     */
    static Result gameCache(Context ctx, File cacheDir, File codeCacheDir,
                            Boolean otherRunning) {
        Result r = new Result();
        if (otherRunning == null) otherRunning = CacheEpoch.otherInstanceRunning(ctx);
        if (otherRunning.booleanValue()) {
            r.skipped = true;
            Log.i(TAG, "cache clean skipped: a game is running");
            return r;
        }
        r.ok = clean(cacheDir == null ? ctx.getCacheDir() : cacheDir, r);
        r.ok = clean(codeCacheDir == null ? ctx.getCodeCacheDir() : codeCacheDir, r) && r.ok;
        Log.i(TAG, "cache clean: " + r.brief());
        return r;
    }

    /** 清一个根目录下的所有条目；返回是否全部删掉 */
    private static boolean clean(File dir, Result r) {
        if (dir == null || !dir.isDirectory()) return true;
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        boolean ok = true;
        for (File f : fs) {
            if (f == null) continue;
            long len = HubTmp.sizeNoFollow(f);
            if (HubTmp.deleteNoFollow(f)) {
                r.entries++;
                r.bytes += len;
            } else {
                ok = false;
                Log.w(TAG, "cache clean: cannot delete " + f);
            }
        }
        return ok;
    }
}
