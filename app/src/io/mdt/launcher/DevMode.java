package io.mdt.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「开发者模式」——「关于」页连点版本摘要解锁的那张卡片（第 296 轮）。
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
 * <p>★ 第 300 轮起：**阈值与段子都搬到 {@code assets/content.json}**（见 {@link Content}）——
 *   这个类只负责"点第 N 下该给什么反应"的**判定**，一个字面量文案都不留。
 *   点击计数**不落盘**（离开页面归零），只有「已解锁」进 {@code config.json}。
 */
final class DevMode {

    /** 这里没有反应（空档 / 没内容文件）。 */
    static final int TAP_NONE = 0;
    /** 还差几次（只在**未解锁**且没到阈值时）。 */
    static final int TAP_LEFT = 1;
    /** 正好解锁那一下，且**未解锁** ⇒ 解锁。 */
    static final int TAP_UNLOCK = 2;
    /** 正好解锁那一下，但**已解锁** ⇒ 一句「已经是了」（Android 同款）。 */
    static final int TAP_ALREADY = 3;
    /** 命中了某一档段子。 */
    static final int TAP_MILESTONE = 4;

    /** 一次点击的判定结果：干什么 + 要显示的那句话（可能为空串）。 */
    static final class Tap {
        final int kind;
        final String text;

        Tap(int kind, String text) {
            this.kind = kind;
            this.text = text == null ? "" : text;
        }
    }

    private static final Tap NONE = new Tap(TAP_NONE, "");

    /** 卡片里那枚签名指纹显示几位（完整的是 64 位十六进制，一屏放不下）。 */
    static final int FINGERPRINT_CHARS = 16;

    private DevMode() {}

    // ── 点击判据（内容驱动；自检直接喂 content.json 的副本或自造内容）───────────

    /**
     * 第 {@code taps} 次点击该做什么（taps 从 1 起）。
     *
     * <p>★ 两态都要遍历：**未解锁**时没到阈值给「还差 N 次」，正好解锁那一下解锁；
     * <b>已解锁</b>时前几下**必须安静**（不然用户每次进关于页都被数一遍），到解锁那一下只说
     * 「已经是了」。段子档位**两态都认**（它们只是彩蛋，与解锁状态无关）。
     *
     * <p>内容文件缺失 / 坏掉 ⇒ 一直返回 {@link #TAP_NONE}（不崩、也不假装有彩蛋）。
     */
    static Tap resolve(Context c, int taps, boolean unlocked) {
        Content ct = Content.get(c);
        if (ct == null || ct.eggs == null || taps <= 0) return NONE;
        Content.Eggs e = ct.eggs;
        if (taps == e.unlockAt) {
            return new Tap(unlocked ? TAP_ALREADY : TAP_UNLOCK,
                    ct.text(c, unlocked ? e.already : e.unlocked));
        }
        Content.Milestone m = e.at(taps);
        if (m != null) {
            String s;
            if ("count".equals(m.arg)) {
                s = ct.format(c, m.text, Integer.valueOf(m.at));
            } else if ("date".equals(m.arg)) {
                s = ct.format(c, m.text, installedOn(c));
            } else {
                s = ct.text(c, m.text);
            }
            return new Tap(TAP_MILESTONE, s);
        }
        if (!unlocked && taps < e.unlockAt) {
            return new Tap(TAP_LEFT, ct.format(c, e.tapsLeft,
                    Integer.valueOf(e.unlockAt - taps)));
        }
        return NONE;
    }

    /** 卡片的分区标题（"开发者选项"）；没有内容文件 ⇒ 空串。 */
    static String title(Context c) {
        Content ct = Content.get(c);
        return ct == null || ct.eggs == null ? "" : ct.text(c, ct.eggs.title);
    }

    /**
     * 卡片里的**记账行**：版本摘要被连点的累计次数。
     *
     * <p>★ 单独一行、单独一个函数：这一行**每次点击都会变**，而上面 7 行是"这个进程的事实"。
     *   混在一起的话，50 次连点就得重读 50 遍包信息、重算 50 遍签名指纹（两次 binder 调用/次）。
     */
    static String tapsLine(Context c, int total) {
        Content ct = Content.get(c);
        if (ct == null || ct.eggs == null) return "";
        return ct.format(c, ct.eggs.tapsLine, Integer.valueOf(total));
    }

    /**
     * 卡片全文 = 7 行事实 + 记账行。**组合规则只写在这一处**（自检钉的就是它）。
     *
     * @param base {@link #buildInfo(Context)} 的结果 —— 调用方缓存它（见 {@link #tapsLine} 的理由）
     */
    static String cardText(Context c, String base, int total) {
        String line = tapsLine(c, total);
        if (base == null || base.length() == 0) return line;
        if (line.length() == 0) return base;
        return base + "\n" + line;
    }

    // ── 卡片前 7 行（进程事实）─────────────────────────────────────────────

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

    /** 这个包第一次装到本机的日期（`yyyy-MM-dd`；拿不到 ⇒ "?"）—— 内容文件里带 `date` 的段子用。 */
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
