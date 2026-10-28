package io.mdt.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「开发者模式」——「关于」页连点版本摘要 7 次解锁的那张卡片（第 296 轮）。
 *
 * <h3>为什么是这一套</h3>
 * <ol>
 *   <li><b>7 次</b>：照 Android 的「设置 → 关于手机 → 连点版本号」，用户一眼就懂这个梗；</li>
 *   <li><b>产品版也得有真东西</b>：{@code DevTools} / {@code SelfTest} 是**构建期按
 *       {@code DEBUGGABLE} 裁掉**的（见 {@code docs/DEVELOPING.md}「dev 源集」）⇒ 卡片里只放
 *       **产品版本来就拿得到**的事实（版本 / 包名 / ABI / 插件接口 / 进程 / 系统 / 签名指纹），
 *       而不是"解锁一堆产品版根本不存在的入口"；</li>
 *   <li><b>只读</b>：卡片不写配置、不碰槽、不碰游戏数据 —— 这个功能唯一的持久化就是「是否已解锁」。</li>
 * </ol>
 *
 * <p>★ 点击计数**不落盘**（离开页面就归零），只有「已解锁」进 {@code config.json}。
 *   反应判据全在 {@link #tapKind(int, boolean)} 这一个纯函数里 —— 页面只管 switch，
 *   自检直接喂数列；别在 {@code AboutActivity} 里另写一套阈值（改一处忘一处 = 真机行为与断言分家）。
 */
final class DevMode {

    /** 第几次点击解锁（Android 的 7 次）。 */
    static final int UNLOCK_AT = 7;
    /** 第几次点击吐槽一句（解锁之后的段子尾巴）。 */
    static final int SNARK_AT = 20;
    /** 第几次点击给一条「冷知识」。 */
    static final int TRIVIA_AT = 50;

    /** 这里没有反应（没到阈值 / 已经过了 / 未解锁时的前 19 次之外的空档）。 */
    static final int TAP_NONE = 0;
    /** 还差几次（只在**未解锁**且 1..6 次时）。 */
    static final int TAP_REMAINING = 1;
    /** 正好第 7 次且**未解锁** ⇒ 解锁。 */
    static final int TAP_UNLOCK = 2;
    /** 正好第 7 次但**已解锁** ⇒ 给一句「已经是了」（Android 同款）。 */
    static final int TAP_ALREADY = 3;
    /** 第 20 次 ⇒ 吐槽。 */
    static final int TAP_SNARK = 4;
    /** 第 50 次 ⇒ 冷知识。 */
    static final int TAP_TRIVIA = 5;

    /** 卡片里那枚签名指纹显示几位（完整的是 64 位十六进制，一屏放不下）。 */
    static final int FINGERPRINT_CHARS = 16;

    private DevMode() {}

    // ── 点击判据（纯函数，自检直接喂数列）─────────────────────────────────

    /**
     * 第 {@code taps} 次点击该做什么（taps 从 1 起）。
     *
     * <p>★ 两态都要遍历：**未解锁**时 1..6 次给「还差 N 次」，第 7 次解锁；
     * <b>已解锁</b>时前 6 次**必须安静**（不然用户每次进关于页都被数一遍），第 7 次只说一句
     * 「已经是了」。20 / 50 次两条段子尾巴**两态都认**（它们只是彩蛋，与解锁状态无关）。
     */
    static int tapKind(int taps, boolean unlocked) {
        if (taps <= 0) return TAP_NONE;
        if (taps == UNLOCK_AT) return unlocked ? TAP_ALREADY : TAP_UNLOCK;
        if (taps == SNARK_AT) return TAP_SNARK;
        if (taps == TRIVIA_AT) return TAP_TRIVIA;
        if (!unlocked && taps < UNLOCK_AT) return TAP_REMAINING;
        return TAP_NONE;
    }

    /** 还差几次（只在 {@link #TAP_REMAINING} 时有意义，结果 6..1）。 */
    static int tapsLeft(int taps) {
        return UNLOCK_AT - taps;
    }

    // ── 卡片内容 ───────────────────────────────────────────────────────────

    /**
     * 那张卡片里的 7 行事实。**取不到的任何一项都退成 "?"**，绝不整块消失：
     * 这一页存在的意义就是"出问题时给人看"，少一行比崩一条好。
     */
    static String buildInfo(Context c) {
        if (c == null) return "";
        String pkg = c.getPackageName();
        String verName = "?";
        int verCode = 0;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            if (pi.versionName != null && pi.versionName.length() > 0) verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (Throwable ignored) {
            // 包信息都读不到时保留 "?" / 0：卡片照样显示，别把页面带走
        }
        String[] abis = Compat.deviceAbis();
        String abi = (abis != null && abis.length > 0) ? abis[0] : "?";
        String bits = Trans.get(c, Process.is64Bit()
                ? R.string.devmode_abi64 : R.string.devmode_abi32);
        String sys = Build.VERSION.RELEASE == null || Build.VERSION.RELEASE.length() == 0
                ? "?" : Build.VERSION.RELEASE;

        StringBuilder sb = new StringBuilder();
        sb.append(Trans.get(c, R.string.devmode_app_fmt, verName, Integer.valueOf(verCode), pkg));
        sb.append('\n').append(Trans.get(c, R.string.devmode_env_fmt, abi, bits,
                Integer.valueOf(PluginHost.API), Integer.valueOf(Process.myPid())));
        sb.append('\n').append(Trans.get(c, R.string.devmode_sys_fmt, sys,
                Integer.valueOf(Build.VERSION.SDK_INT), deviceName()));
        sb.append('\n').append(Trans.get(c, R.string.devmode_sig_fmt, fingerprint(c)));
        return sb.toString();
    }

    /** 本包签名证书的 SHA-256 前 {@link #FINGERPRINT_CHARS} 位（拿不到 ⇒ "?"）。 */
    static String fingerprint(Context c) {
        String full = c == null ? null : PluginTrust.selfFingerprint(c);
        if (full == null || full.length() < FINGERPRINT_CHARS) return "?";
        return full.substring(0, FINGERPRINT_CHARS);
    }

    /** 这台设备的显示名（`厂商 型号`，重复时不重复拼）。 */
    static String deviceName() {
        return deviceLabel(Build.MANUFACTURER, Build.MODEL);
    }

    /**
     * 厂商 + 型号的拼法（纯函数，自检喂三种情形）。
     *
     * <p>有些 ROM 的 {@code MODEL} 本来就带厂商名（本机如果写成 "HONOR ELP-AN00" 就会拼出
     * "HONOR HONOR ELP-AN00"）⇒ 型号已以厂商开头时只留型号；两边都空 ⇒ 空串。
     */
    static String deviceLabel(String maker, String model) {
        String m = maker == null ? "" : maker.trim();
        String d = model == null ? "" : model.trim();
        if (d.length() == 0) return m;
        if (m.length() == 0) return d;
        return d.toLowerCase(Locale.ROOT).startsWith(m.toLowerCase(Locale.ROOT))
                ? d : m + " " + d;
    }

    /** 这个包第一次装到本机的日期（`yyyy-MM-dd`；拿不到 ⇒ "?"）—— 第 50 击那句冷知识用。 */
    static String installedOn(Context c) {
        if (c == null) return "?";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (pi.firstInstallTime <= 0) return "?";
            return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(new Date(pi.firstInstallTime));
        } catch (Throwable t) {
            return "?";
        }
    }
}
