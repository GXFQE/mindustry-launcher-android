package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * 启动时清扫「更新 / 测速」留下的半成品。
 *
 * <p>为什么必须在**启动时**扫，而不是只靠 `finally`：用户可能直接
 * `am force-stop` / 划掉后台 / 被系统回收 —— 这些路径都不会走 `onDestroy`，
 * 更不会走下载线程的 `finally`。所以把「清掉上一次的残留」做成启动例程。
 *
 * <p>只清**确定的临时件**：
 * <ul>
 *   <li>`app_hub/mirror-test.lock` —— 测速开始标记；正常结束会自己删；</li>
 *   <li>`app_hub/mirror-*.tmp` —— 测速结果的原子写临时文件；</li>
 *   <li>`hub/update/*.tmp` —— 原子写的临时文件（每次都清）；</li>
 *   <li>`hub/update/*.part` / `*.part.meta` 与 `hub/plugins-update/*` —— **只清超过 7 天的**：
 *       第 378 轮起下载支持续传，启动时把新鲜的半截删了，续传就永远没料（用户 2026-11-14
 *       定案「先把下载折腾好」）；超过一周的必然是死件。</li>
 * </ul>
 *
 * <p>🔴 **已完成的 `*.apk` 一个都不动**：用户可能正要去装它。
 */
final class UpdateCleanup {

    private static final String TAG = "MDTLauncher";

    /** 半截包（`.part` / `.part.meta`）的保留期：一周。到期才清，好让续传有机会接上。 */
    private static final long PART_TTL_MS = 7L * 24 * 3600 * 1000;

    /**
     * 进程内只扫一次。
     *
     * <p>⚠️ 不能每次 `MainActivity.onCreate` 都扫：转屏 / 被系统重建 Activity 时
     * `onCreate` 会再跑一次，而那时可能正有一个下载线程在写 `hub/update/*.part` ——
     * 把活动中的 `.part` 删掉会让下载莫名其妙失败。启动清扫只该发生在新进程第一次
     * 进主界面的时候。
     */
    private static volatile boolean sStartupSwept;

    private UpdateCleanup() {}

    /** 启动时调用：每个进程只清一次。 */
    static synchronized int sweepOnce(Context ctx) {
        if (sStartupSwept) return 0;
        sStartupSwept = true;
        return sweep(ctx);
    }

    /** @return 清掉的件数（只用于日志；0 表示本来就没有残留） */
    static int sweep(Context ctx) {
        if (ctx == null) return 0;
        File appHub = Paths.privateDir(ctx);
        File updateDir = new File(Data.hubDir(ctx), "update");
        // 第 338 轮：插件更新的下载目录（外部 hub）—— 只清半成品，成品留给下一次检查复用
        File pluginDir = new File(Data.hubDir(ctx), "plugins-update");

        int n = 0;
        n += deleteFile(new File(appHub, "mirror-test.lock"));
        n += deleteFile(new File(appHub, "mirror-test.tmp"));
        n += deleteFile(new File(appHub, "mirror-rank.json.tmp"));
        n += deleteSuffix(updateDir, ".tmp");
        n += deleteSuffixOlder(updateDir, ".part", PART_TTL_MS);
        n += deleteSuffixOlder(updateDir, ".part.meta", PART_TTL_MS);
        n += deleteSuffixOlder(pluginDir, ".part", PART_TTL_MS);
        n += deleteSuffixOlder(pluginDir, ".part.meta", PART_TTL_MS);
        if (n > 0) {
            Log.i(TAG, "update cleanup: removed " + n + " leftover(s)");
        }
        return n;
    }

    private static int deleteSuffix(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (f.getName().toLowerCase(Locale.US).endsWith(suffix)) {
                n += deleteFile(f);
            }
        }
        return n;
    }

    /** 只删"最后修改时间早于 {@code ttlMs} 之前"的同后缀文件（续传要留新鲜的半截）。 */
    private static int deleteSuffixOlder(File dir, String suffix, long ttlMs) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long cutoff = System.currentTimeMillis() - ttlMs;
        int n = 0;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (!f.getName().toLowerCase(Locale.US).endsWith(suffix)) continue;
            if (f.lastModified() > cutoff) continue;
            n += deleteFile(f);
        }
        return n;
    }

    private static int deleteFile(File f) {
        if (f == null || !f.isFile()) return 0;
        try {
            return f.delete() ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
