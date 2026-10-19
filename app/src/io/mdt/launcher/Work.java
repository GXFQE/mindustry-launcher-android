package io.mdt.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * **长操作的前台服务闸门**（第 177 轮，杀后台 P0）。
 *
 * <p>起因（2026-10-19 真机实测）：整槽导出 / 导入 / APK 导入 / 备份全是裸
 * {@code new Thread}，没有 {@code <service>}、没有 {@code startForeground}、
 * 没有 WakeLock。用户一切走（或系统回收）就只剩半截 zip / 部分槽 / 78 MB 的
 * {@code .part}。前台服务不能"防止被杀"（force-stop 仍然立刻死），但它把进程
 * 提到 **foreground service** 的优先级，日常的 LMK / 后台清理器会先挑别人。
 *
 * <p>★ 用法只有一对：{@code begin(ctx, labelRes)} / {@code end(ctx)}，
 * 且**必须 try/finally**（否则异常路径会把服务永久留着，通知栏一直挂一条）。
 * 计数是嵌套安全的：{@code Importer} 里 begin 一次、上层调用点再 begin 一次，
 * 最外层 end 时才真正停服务。
 *
 * <p>🔴 **不能"begin 完立刻 end"就马上 stopService**（2026-10-19 真机当场炸过）：
 * 系统对 `startForegroundService()` 有 5 秒契约，服务还没跑到 `startForeground`
 * 就被 `stopService` 掉，会抛
 * `ForegroundServiceDidNotStartInTimeException`（整个主进程崩溃）。
 * 短操作（备份只有几百毫秒）正好落在这个窗口里。
 * ⇒ 用 {@link #sUp} 记住"服务真的起来了"：
 *   · 已经起来 ⇒ `stopService` 立即停；
 *   · 还没起来 ⇒ 只记 `sStopPending`，由 {@link WorkService#onStartCommand}
 *     在 `startForeground` **之后**回调 {@link #onServiceUp()} 决定停不停。
 */
final class Work {
    private static final String TAG = "MDTLauncher";

    private static final Object LOCK = new Object();
    private static int sActive = 0;
    /** 服务是否已经真的跑到 startForeground（跨线程；受 {@link #LOCK} 保护） */
    private static boolean sUp = false;
    /** 计数归零时服务还没起来 ⇒ 等它起来再停 */
    private static boolean sStopPending = false;

    private Work() {}

    /** 开始一段长操作；`labelRes` 是通知正文（资源 id，见 values/strings.xml 的 `work_*`） */
    static void begin(Context ctx, int labelRes) {
        if (ctx == null) return;
        boolean start;
        synchronized (LOCK) {
            sActive++;
            sStopPending = false;       // 新活来了：原本要停的取消
            start = sActive == 1 && !sUp;
        }
        if (!start) return;
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        try {
            Intent i = new Intent(app, WorkService.class);
            i.putExtra(WorkService.EXTRA_LABEL, labelRes);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            // 后台启动限制 / 通知被禁 / ROM 裁剪 —— 都只是"没有前台服务"，操作照跑
            Log.w(TAG, "foreground service start failed (continuing without it): " + t);
        }
    }

    /** 结束一段长操作（与 {@link #begin} 成对；异常路径也要走到） */
    static void end(Context ctx) {
        boolean stop = false;
        synchronized (LOCK) {
            if (sActive > 0) sActive--;
            if (sActive == 0) {
                if (sUp) {
                    sUp = false;        // 交给系统去 Destroy；onDestroy 会再确认一次
                    stop = true;
                } else {
                    sStopPending = true;    // 服务还没起来：等 onServiceUp 再停
                }
            }
        }
        if (!stop || ctx == null) return;
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        try {
            app.stopService(new Intent(app, WorkService.class));
        } catch (Throwable t) {
            Log.w(TAG, "foreground service stop failed: " + t);
        }
    }

    /**
     * 服务已经在 `startForeground` **之后**（或失败后）回调这里。
     *
     * @return true = 调用方应当 `stopSelf()`（这中间活已经干完/begin 已撤销）
     */
    static boolean onServiceUp() {
        synchronized (LOCK) {
            sUp = true;
            if (sActive == 0 && sStopPending) {
                sStopPending = false;
                sUp = false;
                return true;
            }
            return false;
        }
    }

    /** 服务销毁（`stopService` / `stopSelf` / 系统回收）时回调，清掉"已起来"标记 */
    static void onServiceDown() {
        synchronized (LOCK) {
            sUp = false;
        }
    }
}
