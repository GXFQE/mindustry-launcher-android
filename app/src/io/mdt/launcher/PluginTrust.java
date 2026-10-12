package io.mdt.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;

/**
 * 插件的**信任模型**（第 133 轮）：**官方 = 与本应用同一把签名**，其余一律第三方。
 *
 * <h3>为什么判据是"同一把签名"而不是内置一份指纹</h3>
 * 内置指纹要写死发布密钥，那 dev 构建（debug 签名）里连我们自己造的夹具都成了"第三方" ——
 * 而"官方"这件事的语义本来就是"**这份插件是我们这个发行体系里出来的**"。
 * 「本应用自己的签名」在 dev 与 release 两种构建里都自动成立，且第三方不可能拿到我们的私钥。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li>🔴 **拿不到签名一律按第三方**（默认不信任）—— 老包只有 v1 签名、或者解析失败时，
 *       结果可能是 {@code null}；把它当"官方"就是把默认值当事实说。</li>
 *   <li>⚠️ **签名只回答"谁签的",不回答"安不安全"**：官方插件崩起来一样能把启动器砖掉。
 *       ⇒ 所以"官方 / 第三方"只用来决定**失败后的处置等级**（见 {@link Plugins} 的三档），
 *       不用来决定"允不允许进宿主 CL"（四道门对两类一视同仁）。</li>
 *   <li>判据是**纯读**：不改文件、不装包（`getPackageArchiveInfo` 只解析清单与签名块）。</li>
 * </ol>
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02：中文只许进资源或台账）。
 */
final class PluginTrust {

    /** 与本应用同一把签名。 */
    static final String OFFICIAL = "official";
    /** 别的签名（或拿不到签名）。 */
    static final String THIRD = "third";

    /**
     * 处置等级的三档（**按来源给默认值，用户可改**）：
     * <ul>
     *   <li>{@link #L1} 宽容：只跳过本次注入，登记留着、下次再试（**带重试预算**：连败到上限就升档，
     *       否则会变成"每隔一次启动就死一次"）；</li>
     *   <li>{@link #L2} 回滚：退回 {@code known.txt} 里已知可用的那一版（退不了 ⇒ 转 L3）；</li>
     *   <li>{@link #L3} 严格：停用 + **隔离**（那份 APK 移出候选目录，必须用户手动恢复）。</li>
     * </ul>
     */
    static final String L1 = "L1", L2 = "L2", L3 = "L3";

    /** 官方默认：先宽容、再回滚（我们自己发的东西，值得多给一次机会）。 */
    static final String DEFAULT_OFFICIAL = L2;
    /** 第三方默认：一次失败就停用 + 隔离。 */
    static final String DEFAULT_THIRD = L3;

    /** L1 的重试预算：连着失败这么多次就升档（否则"再试一次"会变成循环）。 */
    static final int RETRY_BUDGET = 2;

    private PluginTrust() {}

    /** 某个来源的默认等级。 */
    static String defaultLevel(String trust) {
        return OFFICIAL.equals(trust) ? DEFAULT_OFFICIAL : DEFAULT_THIRD;
    }

    /** 等级字符串合法吗（登记文件里读到脏值时的闸门）。 */
    static boolean validLevel(String lv) {
        return L1.equals(lv) || L2.equals(lv) || L3.equals(lv);
    }

    // ---- 指纹 -----------------------------------------------------------------

    /** 本应用自己的签名指纹（SHA-256 小写十六进制；拿不到 ⇒ null）。 */
    static String selfFingerprint(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), flags());
            return firstOf(pi);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某个 APK 文件的签名指纹（不装包、只读；拿不到 ⇒ null）。 */
    static String apkFingerprint(Context ctx, File apk) {
        if (apk == null || !apk.isFile()) return null;
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), flags());
            return firstOf(pi);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判据：{@link #OFFICIAL} / {@link #THIRD}。
     * ★ 拿不到任何一边的指纹 ⇒ **第三方**（默认不信任）。
     */
    static String classify(Context ctx, File apk) {
        String self = selfFingerprint(ctx);
        String it = apkFingerprint(ctx, apk);
        return (self != null && self.equals(it)) ? OFFICIAL : THIRD;
    }

    /** 签名块 → 第一份证书的 SHA-256（有多份签名时取第一份：我们只用单签名的包）。 */
    private static String firstOf(PackageInfo pi) {
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28 && pi != null && pi.signingInfo != null) {
                SigningInfo si = pi.signingInfo;
                sigs = si.hasMultipleSigners() ? si.getApkContentsSigners()
                                               : si.getSigningCertificateHistory();
            }
            if ((sigs == null || sigs.length == 0) && pi != null && pi.signatures != null) {
                sigs = pi.signatures;   // 老包（只有 v1 签名）走这条
            }
            if (sigs == null || sigs.length == 0) return null;
            return sha256(sigs[0].toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    private static int flags() {
        // GET_SIGNING_CERTIFICATES 是 API 28+；低版本回落到已废弃的 GET_SIGNATURES（口径一致）
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 界面用的一句话：前 8 位 + 省略号（完整指纹进报告，别塞进列表）。 */
    static String shortFp(String fp) {
        if (fp == null) return "(none)";
        return fp.length() <= 12 ? fp : fp.substring(0, 12) + "...";
    }
}
