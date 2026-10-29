package io.mdt.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把一份**已经过 sha256 + 证书**的 APK 交给系统安装器。
 *
 * <p>四道闸门（`ref/01` §11 实测）在这里落成代码：**包名一致 / 签名一致 / 不比本机旧 /
 * 系统能解析**。任何一条不过就**不提交会话** —— 提交了也只会得到一个
 * `STATUS_FAILURE_*`，而用户看到的是"点了安装没反应"。
 *
 * <p>⚠️ 两条硬约束：
 * <ul>
 *   <li>**结果回调必须用广播**（{@link InstallResultReceiver}）—— 用 Activity 会把
 *       MainActivity 拉到前台、顶掉安装确认界面；</li>
 *   <li>**`PENDING_USER_ACTION` 要转发** —— 那是系统的确认界面，不转就永远等不到用户点。</li>
 * </ul>
 *
 * <p>⚠️ 安装期间进程会死。所有"我提交了"的状态都只写文件，不指望内存。
 */
final class UpdateInstall {

    /** 结果广播的 action（清单里静态注册的 receiver 按它匹配）。 */
    static final String ACTION_RESULT = "io.mdt.launcher.INSTALL_RESULT";

    static final String S_NO_PERMISSION = "no-permission";
    static final String S_BAD_PACKAGE = "bad-package";
    static final String S_DOWNGRADE = "downgrade";
    static final String S_BAD_SIGN = "bad-sign";
    static final String S_IO = "io";
    static final String S_SUBMITTED = "submitted";

    static final class Precheck {
        final boolean ok;
        /** 不过的原因码；通过时为 null。 */
        final String why;
        /** 原始诊断（包名 / 两个指纹 / 两个版本号），只进报告。 */
        final String detail;
        /** APK 自己报的 versionCode（解析不出来时为 -1）。 */
        final int versionCode;

        Precheck(boolean ok, String why, String detail, int versionCode) {
            this.ok = ok;
            this.why = why;
            this.detail = detail;
            this.versionCode = versionCode;
        }
    }

    private UpdateInstall() {}

    /** 本机当前装的 versionCode（拿不到 ⇒ 0，按"任意版本都比它新"处理）。 */
    static int currentVersionCode(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 提交前的四道门。**只读**：不动文件、不建会话。
     */
    static Precheck precheck(Context ctx, File apk) {
        if (apk == null || !apk.isFile()) {
            return new Precheck(false, S_BAD_PACKAGE, "missing file", -1);
        }

        // ① 未知来源授权（Android 8+ 是逐应用的开关，没有就去设置页开）
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                    return new Precheck(false, S_NO_PERMISSION, "canRequestPackageInstalls=false", -1);
                }
            } catch (Throwable ignored) {
                // 个别 ROM 上这个调用会抛；宁可按"有权限"走，让系统自己去拦
            }
        }

        // ② 系统能不能解析 + 包名是不是我们
        android.content.pm.PackageInfo pi = null;
        try {
            pi = ctx.getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        } catch (Throwable ignored) {
        }
        if (pi == null) {
            return new Precheck(false, S_BAD_PACKAGE, "unparseable", -1);
        }
        if (!ctx.getPackageName().equals(pi.packageName)) {
            return new Precheck(false, S_BAD_PACKAGE, "package=" + pi.packageName, pi.versionCode);
        }

        // ③ 不许降级（同版本允许覆盖重装；更低一律拒 —— 系统也会拒，但那时
        //    用户已经点过确认，体验更差）
        int cur = currentVersionCode(ctx);
        if (pi.versionCode < cur) {
            return new Precheck(false, S_DOWNGRADE, pi.versionCode + " < " + cur, pi.versionCode);
        }

        // ④ 签名（sha256 只证明没坏，证明不了是谁签的）
        String self = PluginTrust.selfFingerprint(ctx);
        String got = PluginTrust.apkFingerprint(ctx, apk);
        if (self == null || got == null || !self.equals(got)) {
            return new Precheck(false, S_BAD_SIGN, "self=" + self + " got=" + got, pi.versionCode);
        }

        return new Precheck(true, null, null, pi.versionCode);
    }

    /**
     * 建会话 → 写 APK → 提交。返回后**安装确认由系统弹**（或用户已经点过）。
     *
     * <p>⚠️ 调用方要在**调用之前**把"已提交"写进报告：这个调用返回不代表装完，
     * 而且进程随时可能被杀。
     */
    static void submit(Context ctx, File apk) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());

        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream os = session.openWrite("base.apk", 0, apk.length());
            try {
                InputStream in = new FileInputStream(apk);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
                session.fsync(os);
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }

            Intent result = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
            int flags = PendingIntent_FLAG_UPDATE_CURRENT();
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= android.app.PendingIntent.FLAG_MUTABLE;
            }
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                    ctx, 0, result, flags);
            session.commit(pending.getIntentSender());
        } finally {
            try { session.close(); } catch (Throwable ignored) { }
        }
    }

    /** `FLAG_UPDATE_CURRENT` 的常量值（避免在老 SDK 上引用新字段）。 */
    private static int PendingIntent_FLAG_UPDATE_CURRENT() {
        return android.app.PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
