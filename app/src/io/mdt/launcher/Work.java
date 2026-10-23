package io.mdt.launcher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    /**
     * 最外层那段活的**通知正文**（第 187 轮：插件跑后台任务时它自带一句话）。
     * 进度更新也用它 —— 否则通知只剩 "3 / 10"，用户不知道是在做什么。
     */
    private static String sText;
    /**
     * 通知权限（第 187 轮）：Android 13 起 `POST_NOTIFICATIONS` 是运行时权限，
     * 没它就**整条通知都不显示**（真机实测：appops = ignore，dumpsys 无记录）。
     * 本进程只问一次；用户拒绝后不反复弹（他随时能在系统设置里打开）。
     */
    private static boolean sAskedNotif = false;
    private static final int REQ_NOTIF = 0x4D44;

    private Work() {}

    /** 开始一段长操作；`labelRes` 是通知正文（资源 id，见 values/strings.xml 的 `work_*`） */
    static void begin(Context ctx, int labelRes) {
        begin(ctx, labelRes, null);
    }

    /**
     * 开始一段长操作，正文用调用方给的一句话（插件传自己的标签）。
     *
     * @param text 非空 ⇒ 覆盖 `labelRes` 那句；null / 空 ⇒ 仍按 `labelRes` 取资源
     */
    static void begin(Context ctx, int labelRes, String text) {
        if (ctx == null) return;
        askNotifications(ctx);
        boolean start;
        synchronized (LOCK) {
            sActive++;
            sStopPending = false;       // 新活来了：原本要停的取消
            if (sActive == 1) sText = text;   // 只有最外层那次决定正文
            start = sActive == 1 && !sUp;
        }
        if (!start) return;
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        try {
            Intent i = new Intent(app, WorkService.class);
            i.putExtra(WorkService.EXTRA_LABEL, labelRes);
            if (text != null && !text.trim().isEmpty()) {
                i.putExtra(WorkService.EXTRA_TEXT, text);
            }
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable t) {
            // 后台启动限制 / 通知被禁 / ROM 裁剪 —— 都只是"没有前台服务"，操作照跑
            Log.w(TAG, "foreground service start failed (continuing without it): " + t);
        }
    }

    /**
     * 通知权限：只在**真的开始一段长操作**时问一次（不在启动时骚扰用户）。
     *
     * <p>为什么必须有：Android 13 起没有 `POST_NOTIFICATIONS` 授权，`startForeground`
     * 的通知**会被系统整个丢掉** —— 前台服务身份照旧生效，但用户看不到任何进度
     * （第 187 轮真机实测：`appops get … POST_NOTIFICATION` = ignore，下拉通知栏空白）。
     *
     * <p>只在拿到 **Activity** 时问（`requestPermissions` 的结果要回到界面）；后台线程
     * 调用会转回主线程。本次进程问过一次就不再问，用户拒绝可以在系统设置里自己打开。
     */
    private static void askNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT < 33 || !(ctx instanceof Activity)) return;
        final Activity a = (Activity) ctx;
        synchronized (LOCK) {
            if (sAskedNotif) return;
            sAskedNotif = true;
        }
        try {
            if (a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) return;
        } catch (Throwable ignored) {
            // 拿不到权限状态 ⇒ 还有一次机会：照常请求（失败也只是"通知不可见"）
        }
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (a.isFinishing()) return;
                    a.requestPermissions(
                            new String[]{ android.Manifest.permission.POST_NOTIFICATIONS },
                            REQ_NOTIF);
                } catch (Throwable t) {
                    Log.w(TAG, "notification permission request failed: " + t);
                }
            }
        });
    }

    /** 结束一段长操作（与 {@link #begin} 成对；异常路径也要走到） */    static void end(Context ctx) {
        boolean stop = false;
        synchronized (LOCK) {
            if (sActive > 0) sActive--;
            if (sActive == 0) {
                sText = null;
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
     * 更新常驻通知上的进度（第 187 轮，插件接口 v7 用）。
     *
     * <p>只在"服务已经起来"时有效 —— 没有通知可更新不是错误，**静默忽略**（短操作就是这样，
     * 插件不必为它分支）。通知正文仍用最外层那段活的标签，进度另起一行。
     *
     * @param done  已完成
     * @param total 总数；{@code <= 0} ⇒ 不确定进度
     */
    static void progress(Context ctx, int done, int total) {
        String text;
        synchronized (LOCK) {
            if (!sUp) return;
            text = sText;
        }
        WorkService.update(ctx, text, done, total);
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

    /**
     * 插件任务的**完成通知**（v22，第 221 轮）：成功 / 失败 / 被取消。
     *
     * <p>和前台服务复用同一个渠道（`mdt.work`，IMPORTANCE_LOW）：任务跑的时候用户已经
     * 在通知栏见过那条进度通知，完成时再响一声反而烦。渠道可能还没建（任务短到服务没起来），
     * 所以这里兜底建一次。
     *
     * <p>点开回到管理器主界面（不是插件页：任务可能来自任意页，宿主不知道用户想回哪）。
     */
    static void notifyDone(Context ctx, String title, String text, int id) {
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(WorkService.CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(WorkService.CHANNEL,
                        app.getString(R.string.work_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(app, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, id, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = new Notification.Builder(app, WorkService.CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title == null ? app.getString(R.string.plugin_task_title) : title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setContentIntent(pi);
            nm.notify(id, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "task notification failed: " + t);
        }
    }
}
