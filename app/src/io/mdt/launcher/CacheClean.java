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
 * <p>⚠️ 游戏在跑、或主进程刚点下启动（占位还在）时**一律拒绝**：清 `getCacheDir()` 会把游戏
 * 正在用 / 马上要用的音乐 / 模组 jar 抽走（症状是音频静默 / 模组加载失败，而且很难归因）。
 * 判据见 {@link #anyGameStarting}（运行实例 + 启动占位；拿不准也拒绝）。</p>
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

    /** 自动语义（预览图只删过期的）——自检与旧调用点用这个。 */
    static Result gameCache(Context ctx, File cacheDir, File codeCacheDir,
                            Boolean otherRunning) {
        return gameCache(ctx, cacheDir, codeCacheDir, otherRunning, false);
    }

    /**
     * 可注入版本（自检用）。
     *
     * @param cacheDir     null = {@code ctx.getCacheDir()}
     * @param codeCacheDir null = {@code ctx.getCodeCacheDir()}
     * @param otherRunning null = 现查（运行实例 **或** 启动占位，见 {@link #anyGameStarting}）
     * @param manual       true = 用户**手动**点的清理 ⇒ 预览图不留期限、整棵清掉；
     *                     false = 自动清理 ⇒ 只删过期的（{@link #PREVIEW_KEEP_DAYS} 天）
     */
    static Result gameCache(Context ctx, File cacheDir, File codeCacheDir,
                            Boolean otherRunning, boolean manual) {
        Result r = new Result();
        if (otherRunning == null) otherRunning = Boolean.valueOf(anyGameStarting(ctx));
        if (otherRunning.booleanValue()) {
            r.skipped = true;
            Log.i(TAG, "cache clean skipped: a game is running or starting");
            return r;
        }
        r.ok = clean(cacheDir == null ? ctx.getCacheDir() : cacheDir, r);
        r.ok = clean(codeCacheDir == null ? ctx.getCodeCacheDir() : codeCacheDir, r) && r.ok;
        r.ok = previews(ctx, r, manual) && r.ok;
        Log.i(TAG, "cache clean: " + r.brief());
        return r;
    }

    /** 预览图（地图/蓝图缩略图）保留几天；超过就删——重算一遍是一样的图。 */
    static final int PREVIEW_KEEP_DAYS = 30;

    /**
     * 剪 `hub/previews/`。
     *
     * <p>**自动清理只删过期的**（{@link #PREVIEW_KEEP_DAYS} 天）：这些图是用户点开地图列表才用得上的，
     * 删光之后第一次进列表要重算（大图上要等一会儿）。按天数留一批，既压住体积又不让常用图陪葬。</p>
     *
     * <p>**手动清理整棵清**（用户 2026-11-23：「那是自动清理时限，我都点清理了应该都清掉吧」）：
     * 点了清理就是明确要求腾空间，留 30 天窗口只会让人觉得"清了等于没清"。</p>
     *
     * <p>不碰：`.v&lt;N&gt;` 标记文件（`MapLoad` 用它判断"这个画法版本已经清过一次"）；
     * 当前画法版本目录里的**新**图。旧画法版本的整棵目录由 `MapLoad.purgeOld` 负责，这里不管。</p>
     */
    private static boolean previews(Context ctx, Result r, boolean manual) {
        File root = new File(Data.hubDir(ctx), "previews");
        if (!root.isDirectory()) return true;
        long cut = manual ? Long.MAX_VALUE
                : System.currentTimeMillis() - PREVIEW_KEEP_DAYS * 24L * 60L * 60L * 1000L;
        return prune(root, cut, r);
    }

    /** 递归剪：文件按 mtime 判，剪空的目录也走（只对自己删空的目录动手） */
    private static boolean prune(File dir, long cut, Result r) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        boolean ok = true;
        for (File f : fs) {
            if (f == null) continue;
            if (f.getName().startsWith(".")) continue;   // .v<N> 标记
            if (f.isDirectory()) {
                boolean empty = prune(f, cut, r);
                if (empty && f.delete()) r.entries++;
                else if (!empty) ok = false;
            } else if (f.lastModified() < cut) {
                long len = HubTmp.sizeNoFollow(f);
                if (HubTmp.deleteNoFollow(f)) {
                    r.entries++;
                    r.bytes += len;
                } else {
                    ok = false;
                    Log.w(TAG, "cache clean: cannot delete " + f);
                }
            }
        }
        File[] left = dir.listFiles();
        // 判"是否已空"时**忽略 `.v<N>` 标记**：它本来就该留着（MapLoad 用它记画法版本），
        // 把它算成"没清干净"会让手动清完整棵之后还报"部分清理"（2026-11-23 实测踩到）。
        boolean anyLeft = false;
        if (left != null) {
            for (File f : left) {
                if (f != null && !f.getName().startsWith(".")) {
                    anyLeft = true;
                    break;
                }
            }
        }
        return ok && !anyLeft;
    }

    /**
     * 有没有游戏**正在跑或马上要跑**。
     *
     * <p>为什么比 {@link CacheEpoch#otherInstanceRunning} 更严：那个判据把"启动占位"（proc 为空）
     * 排除在外（对"启动前清 cache"是对的——占位就是这一局自己）；但**清缓存不能和启动并发**：
     * 主进程刚点下启动、游戏进程马上起来读 cache，这时删文件会让游戏缺音乐/模组 jar。
     * ⇒ 这里连占位一起算；拿不准（扫描抛异常）也按"要跑了"处理，宁可不删。</p>
     */
    private static boolean anyGameStarting(Context ctx) {
        if (CacheEpoch.otherInstanceRunning(ctx)) return true;
        try {
            for (Instances.Running r : Instances.live(ctx)) {
                if (r != null && r.proc.isEmpty()) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache clean: cannot check instances", t);
            return true;
        }
        return false;
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
