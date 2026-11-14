package io.mdt.launcher;

import android.content.Context;

/**
 * **下载限速**的档位与文案（第 378 轮，2026-11-14）。
 *
 * <p>为什么单独一个类：值域 / 钳位（{@link #clamp}）与"值 → 人话"（{@link #label}）是**两件事**，
 * 但都要能被设置页和自检共用 —— 自检直接喂值断言，界面只负责摆出来。设置页让用户**自己填 KB/s**
 * （0 = 不限），所以这里没有档位表。
 *
 * <p>存的是**字节/秒**（{@link Config#downloadLimit()}），0 = 不限；上限 {@link #MAX}
 * 只是防呆（手改配置写个天文数字，不该把下载线程睡死）。
 *
 * <p>⚠️ 这是**用户设置**，不是每任务参数：所有下载共用一个 {@link Bandwidth} 额度。
 */
final class DownloadLimit {

    static final long NONE = 0L;
    static final long KB = 1024L;
    static final long MB = 1024L * 1024L;

    /** 上限：100 MB/s（手机网络到不了，只是防呆）。 */
    static final long MAX = 100L * MB;

    private DownloadLimit() {}

    /** 负数 / 0 都归 0（不限）；超过 {@link #MAX} 钳到上限。 */
    static long clamp(long bytesPerSec) {
        if (bytesPerSec <= 0L) return NONE;
        return Math.min(bytesPerSec, MAX);
    }

    /**
     * 值 → 人话（`不限` / `512 KB/s` / `2 MB/s`）。
     *
     * <p>能被 1 MB 整除的按 MB 说，其余按 KB 说（四舍五入到最近的 KB，至少 1）。
     */
    static String label(Context c, long bytesPerSec) {
        long v = clamp(bytesPerSec);
        if (v == NONE) return Trans.get(c, R.string.set_download_limit_none);
        if (v % MB == 0L) return Trans.get(c, R.string.set_download_limit_mb_fmt, v / MB);
        long kb = Math.max(1L, (v + KB / 2) / KB);
        return Trans.get(c, R.string.set_download_limit_kb_fmt, kb);
    }
}
