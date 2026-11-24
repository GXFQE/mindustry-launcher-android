package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * **存储上限巡检**（第 426 轮）：中转站按"数量 + 时间"、临时文件按"大小 + 时间"各收一遍。
 *
 * <h3>为什么单独一个类</h3>
 *
 * 中转站的修剪本来就挂在写入路径上（{@link Trash#pruneIn}），临时件的 TTL 清扫挂在冷启动
 * （{@link Recovery#runNow}）。但"用户半年不往中转站挪东西"和"分享缓存两次清扫之间堆到
 * 几百 MB"这两件事光靠写入 / 冷启动都盖不住 ⇒ 每小时补一次巡检。
 *
 * <p>★ 与 {@link Cleanup} 的 `auto_clean_redundant` 开关**相互独立**：那个管"清游戏缓存 /
 * 旧版本重复文件"，用户可以关；这里执行的是用户自己设的保留上限（不执行 = 设置失效）。
 *
 * <p>门禁与 {@link Cleanup} 同一套：主进程（进程名精确等于包名，见那边的注释）、没有游戏
 * 实例在跑、进线程后再判一次。
 */
final class StorageGuard {

    private static final String TAG = "MDTLauncher";
    /** 用户拍的"每小时"（2026-11-24）；不是 24 h —— 上限是安全阀，晚一天生效就白设了。 */
    private static final long INTERVAL_MS = 60L * 60L * 1000L;
    private static final String STAMP = "storage-guard.txt";

    private StorageGuard() {}

    /** 巡检时刻（`app_hub/storage-guard.txt`，放在 cache 外面 —— 系统清缓存不会把它弄丢）。 */
    static File stampFile(Context ctx) {
        return new File(Paths.privateDir(ctx), STAMP);
    }

    /**
     * 冷启动 / 回界面时调一次。**不阻塞**：条件满足才起后台线程。
     */
    static void autoInBackground(Context ctx) {
        if (ctx == null) return;
        if (!Util.isMainProcess(ctx)) return;
        // ★ 必须精确是主进程（理由与 Cleanup.autoCleanInBackground 同）：删东西的动作
        //   不能靠"拿不准就当主进程"的兜底。
        String proc = Util.processName();
        if (proc == null || !proc.equals(ctx.getPackageName())) return;
        if (CacheEpoch.otherInstanceRunning(ctx)) return;
        final File stamp = stampFile(ctx);
        if (!Cleanup.autoDue(stamp, System.currentTimeMillis(), INTERVAL_MS)) return;

        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 进线程后再判一次：等待期间用户可能已经点了启动
                    if (CacheEpoch.otherInstanceRunning(app)) return;
                    int trashed = Trash.trimAll(app);
                    HubTmp.Swept s = HubTmp.sweep(app);
                    mark(app, System.currentTimeMillis());
                    Log.i(TAG, "storage guard: trash " + trashed + ", tmp " + s.brief());
                } catch (Throwable t) {
                    Log.w(TAG, "storage guard failed", t);
                }
            }
        }, "storage-guard").start();
    }

    /** 记下这次巡检的时刻（写失败只记日志：下次还会再试，方向安全）。 */
    private static void mark(Context ctx, long now) {
        try {
            Util.atomicWriteText(stampFile(ctx), String.valueOf(now));
        } catch (Throwable t) {
            Log.w(TAG, "storage guard: cannot write stamp", t);
        }
    }
}
