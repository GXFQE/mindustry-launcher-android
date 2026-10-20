package io.mdt.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 长操作期间的前台服务（第 177 轮，杀后台 P0）—— 见 {@link Work} 的类注释。
 *
 * <p>只做一件事：`startForeground` 挂一条"正在处理文件"的常驻通知，
 * 让主进程在这段时间里是 foreground-service 优先级。真正的活仍在各页面的
 * 后台线程里干（这里不排队、不执行任务）。
 *
 * <p>⚠️ 通知渠道 importance = LOW：这类操作通常几秒；HIGH 会响一声，很烦。
 */
public class WorkService extends Service {
    static final String EXTRA_LABEL = "label";
    /** 第 187 轮：调用方直接给的一句话（插件后台任务的标签）；非空时盖过 {@link #EXTRA_LABEL}。 */
    static final String EXTRA_TEXT = "text";
    private static final String TAG = "MDTLauncher";
    private static final String CHANNEL = "mdt.work";
    private static final int NOTIF_ID = 0x4d4454;   // "MDT"

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        getString(R.string.work_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Log.w(TAG, "notification channel setup failed: " + t);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 🔴 startForeground 必须是这一条路径上**最先做**的事之一：任何一处先抛
        //    （哪怕只是取文案失败）都会让系统在 5 秒后抛 ForegroundServiceDidNotStartInTime
        //    并把整个主进程带走 —— 2026-10-19 真机就炸在这里。
        String text;
        try {
            String given = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
            if (given != null && !given.trim().isEmpty()) {
                text = given.trim();
            } else {
                int label = intent == null ? 0 : intent.getIntExtra(EXTRA_LABEL, 0);
                text = label == 0 ? getString(R.string.work_notif_plain) : Trans.get(this, label);
            }
        } catch (Throwable t) {
            text = "";          // 文案失败也绝不能拖累 startForeground
        }
        boolean foreground = false;
        try {
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getString(R.string.work_notif_title))
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);
            try {
                Intent open = new Intent(this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } catch (Throwable ignored) {
                // 没有 contentIntent 也能当前台服务用
            }
            startForeground(NOTIF_ID, b.build());
            foreground = true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t);
        }
        // 告诉闸门"我起来了"：短操作可能在服务创建前就 end 了（见 Work 类注释的崩溃）
        if (Work.onServiceUp() || !foreground) {
            try {
                stopSelf();
            } catch (Throwable ignored) {
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * 原地更新那条常驻通知上的进度（第 187 轮，{@link Work#progress} 唯一调用方）。
     *
     * <p>⚠️ 只能**更新**：服务没起来 / 通知没挂上时不许在这里新建一条（那会凭空多出一条
     * 没人收的通知）；调用方（{@link Work#progress}）已经用 `sUp` 把这条路闸住了。
     */
    static void update(Context ctx, String text, int done, int total) {
        if (ctx == null) return;
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String progress = Trans.get(ctx, R.string.work_progress_fmt, done, total);
            String body = (text == null || text.trim().isEmpty())
                    ? progress : text.trim() + " · " + progress;
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(ctx.getString(R.string.work_notif_title))
                    .setContentText(body)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);
            try {
                Intent open = new Intent(ctx, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                b.setContentIntent(PendingIntent.getActivity(ctx, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } catch (Throwable ignored) {
                // 没有 contentIntent 也能更新
            }
            if (total > 0) {
                b.setProgress(total, Math.max(0, Math.min(done, total)), false);
            } else {
                b.setProgress(0, 0, true);
            }
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "work progress update failed: " + t);
        }
    }

    @Override public void onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        Work.onServiceDown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
