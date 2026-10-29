package io.mdt.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;

/**
 * {@link android.content.pm.PackageInstaller} 的**结果广播**。
 *
 * <p>为什么是广播、不是 Activity：`ref/01` §11 的实测教训 —— 用 `getActivity` 指向
 * MainActivity 时，系统会把 MainActivity 拉到前台、**顶掉安装确认界面**，会话被放弃、
 * versionCode 一直不涨。表象是"提交成功了却装不上"，极难查。
 *
 * <p>⚠️ **`PENDING_USER_ACTION` 必须有人把它转成 `startActivity`**：那个分支的 Intent
 * 里带着系统安装确认界面。不转发就永远等不到用户点，广播也不会再来。
 *
 * <p>⚠️ 这个 Receiver 在**清单里静态注册**（`exported=false`）：提交后进程可能被杀，
 * 系统要能在**新进程**里把它拉起来收结果。
 */
public final class InstallResultReceiver extends BroadcastReceiver {

    /** 结果落到这里（与 `report-update.txt` 同一个 hub，adb 可直接取回）。 */
    static final String REPORT = "report-install.txt";

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        // ★ 用户确认这一步：把系统给的那个 Intent 拉起来。不做的话安装会永远卡住。
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            try {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
            } catch (Throwable t) {
                android.util.Log.w("MDTLauncher", "install confirm failed: " + t);
            }
        }

        String text = "status  = " + statusName(status) + "\n"
                + "code    = " + status + "\n"
                + "message = " + msg + "\n"
                + "at      = " + new java.util.Date() + "\n";
        android.util.Log.i("MDTLauncher", "install result:\n" + text);
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), REPORT), text);
        } catch (Throwable ignored) {
        }
    }

    /** 状态码 → 人读名字（只进日志/报告，不上屏）。 */
    static String statusName(int s) {
        switch (s) {
            case PackageInstaller.STATUS_SUCCESS: return "SUCCESS";
            case PackageInstaller.STATUS_FAILURE: return "FAILURE";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "FAILURE_BLOCKED";
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "FAILURE_ABORTED";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "FAILURE_INVALID";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "FAILURE_CONFLICT";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "FAILURE_STORAGE";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "FAILURE_INCOMPATIBLE";
            case PackageInstaller.STATUS_PENDING_USER_ACTION: return "PENDING_USER_ACTION";
            default: return "UNKNOWN(" + s + ")";
        }
    }
}
