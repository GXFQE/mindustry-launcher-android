package io.mdt.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
            int label = intent == null ? 0 : intent.getIntExtra(EXTRA_LABEL, 0);
            text = label == 0 ? getString(R.string.work_notif_plain) : Trans.get(this, label);
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
