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
     * **随本体发放的内置插件**（第 137 轮）—— 用户定：**不能卸载**。
     *
     * <p>判据不是签名而是"**本体里那份清单**"：`assets/plugins/manifest.txt` 里有这个 id，
     * 且装到内部存储的那份文件 sha256 与清单逐字相同 ⇒ 内置；哈希对不上（被人换过 / 写坏）
     * ⇒ **不算内置**，回落签名判据（那种情况下允许卸载，反正下次启动会被放回原生那份）。
     * ★ 所以"不能卸载"在实现上是两半：界面不给删除入口 + 文件被删/改坏后**下次启动自动放回**。
     */
    static final String BUILTIN = "builtin";

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
    /**
     * 内置默认 = **L1 最宽**（2026-10-12 用户定：「停用了啥也没了啊」）。
     * 内置不像外部插件那样"卸了再装一个"——它被停用就是彻底没了，所以默认给它最宽的那一档。
     * ⚠️ 但**安全阀的最后一道必须留着**：连败到重试预算会升 L2，退不了（内置只有一份内容）⇒ 停用。
     * 否则一个"死在启动路径上"的内置插件会让启动器永远起不来。
     */
    static final String DEFAULT_BUILTIN = L1;

    /** L1 的重试预算：连着失败这么多次就升档（否则"再试一次"会变成循环）。 */
    static final int RETRY_BUDGET = 2;

    private PluginTrust() {}

    /** 某个来源的默认等级。 */
    static String defaultLevel(String trust) {
        if (BUILTIN.equals(trust)) return DEFAULT_BUILTIN;
        return OFFICIAL.equals(trust) ? DEFAULT_OFFICIAL : DEFAULT_THIRD;
    }

    /**
     * 定来源（**纯函数，唯一一处判据**）。
     *
     * <p>🔴 为什么必须是一处：2026-10-12 真机踩的 —— 这段判据原先在
     * {@code Plugins.trustOf}（界面那条路）和 {@code Plugins.trustFrom}（安全阀那条路）
     * **各写了一遍**，我加内置时只改了前者 ⇒ 安全阀那条路把内置当成了"官方"
     * ⇒ 内置的等级封顶（L2）失效 ⇒ **内置插件被当作 L3 隔离掉了**
     * （`lastskip = probe⇥76bf4ef8⇥⇥disable⇥L3⇥yes`，文件真的被挪进了 `quarantine/`）。
     * ⇒ 与 §88.11「同一件事有两条路时判据必须共用一份实现」同型。
     *
     * @param recorded    {@code trust.txt} 里记的那个值（可能是 null / 脏值）
     * @param builtinHit  清单里有它、且文件 sha 逐字相同（{@code Plugins.isBuiltin}）
     * @param signature   按签名判出来的（{@link #classify}）
     */
    static String resolve(String recorded, boolean builtinHit, String signature) {
        if (BUILTIN.equals(recorded) || OFFICIAL.equals(recorded) || THIRD.equals(recorded)) return recorded;
        if (builtinHit) return BUILTIN;     // 记录丢了也认得出内置（清单 + 哈希）
        return signature;
    }

    /**
     * 按来源**夹取**等级上限（纯函数）：内置封顶 L2。
     * ⚠️ 为什么内置没有 L3：L3 = 停用 + **隔离**（把那版 APK 挪进 `quarantine/`），
     * 而内置那份下次启动会被重新放回 ⇒ 隔离对内置既无意义、又会让"文件在哪"变得难解释。
     */
    static String capLevel(String trust, String level) {
        if (!BUILTIN.equals(trust)) return level;
        return L3.equals(level) ? L2 : level;
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
            return hex(d);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 文件的 SHA-256（**流式**，别整个读进内存）—— 内置插件的"还是不是原生那一份"就靠它。
     * 拿不到（文件不在 / IO 出错）⇒ null，调用方一律当"对不上"。
     */
    static String sha256File(File f) {
        if (f == null || !f.isFile()) return null;
        java.io.InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 界面用的一句话：前 8 位 + 省略号（完整指纹进报告，别塞进列表）。 */
    static String shortFp(String fp) {
        if (fp == null) return "(none)";
        return fp.length() <= 12 ? fp : fp.substring(0, 12) + "...";
    }
}
