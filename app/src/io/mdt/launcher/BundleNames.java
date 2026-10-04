package io.mdt.launcher;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 译名解析（{@link MapStats.Names} 的产品实现）：**两层 bundle，模组在各层内覆盖版本 APK**。
 *
 * <pre>
 *   语言层 = 版本 APK 的 assets/bundles/bundle_zh_CN.properties  ⨁ 各模组的 bundles/bundle_zh_CN.properties
 *   基础层 = 版本 APK 的 assets/bundles/bundle.properties        ⨁ 各模组的 bundles/bundle.properties
 *   查找   = 语言层 → 基础层 → （都没有）调用方退回内部名的可读化
 * </pre>
 *
 * 🔴 这个结构**不是我发明的**，是游戏自己的做法（v160.4 `Mods` 的字节码）：
 * <pre>
 *   for(I18NBundle b = Core.bundle; b != null; b = b.getParent()){
 *       for(Fi f : bundles.get("bundle" + b.getLocale()))
 *           PropertiesUtils.load(b.getProperties(), f.reader());
 *   }
 * </pre>
 *   ⇒ 模组的语言包写进**与版本 APK 的语言包同一个 map**，模组后加载 ⇒ **模组覆盖版本 APK**
 *   （实测：`vanilla-expansion2119.zip` 把 `block.phase-conveyor.name` 改成「相位传送带桥」，
 *   而官方 v160 的 zh_CN 是「相织布传送带桥」—— 游戏里显示的是**模组那份**）；
 *   模组的基础包则只盖版本 APK 的**基础包**（盖不动语言层）。
 *   ⚠️ 我第一版写反了两次：先"模组一律优先"（对了一半），再"版本语言包优先"（错），
 *   最后按上面这段字节码定成**两层、层内模组优先**。
 *
 * 🔴 必须按 **UTF-8** 读：`Properties.load(InputStream)` 是 ISO-8859-1 ⇒ 全篇乱码。
 *
 * ★ 本类**不碰 Android**（只用 `java.util.zip` 与 {@link Colors}）—— 第 84 轮从
 *   {@link MapStatsMods} 里搬出来，就是为了能在 PC 上用**产品代码**跑名字验收
 *   （`_lab/msav/f21/NameAudit.java`）。
 */
public final class BundleNames implements MapStats.Names {

    /** 语言层（版本 APK 的 zh_CN 先放，再叠各模组的 zh_CN） */
    private final Map<String, String> locale = new java.util.LinkedHashMap<>();
    /** 基础层（版本 APK 的 base 先放，再叠各模组的 base） */
    private final Map<String, String> base = new java.util.LinkedHashMap<>();
    private final Map<String, String> attrs;
    /** 读到了什么（技术细节层要能看见依据） */
    public String note = "";

    public BundleNames(File apk, MapStats.Bundles modBundles, Map<String, String> attrLabels) {
        this.attrs = attrLabels == null ? Collections.<String, String>emptyMap() : attrLabels;
        int nz = 0, nb = 0;
        ZipFile zf = null;
        try {
            if (apk != null && apk.isFile()) {
                zf = new ZipFile(apk);
                nz = load(zf, "assets/bundles/bundle_zh_CN.properties", locale);
                nb = load(zf, "assets/bundles/bundle.properties", base);
            }
        } catch (Throwable t) {
            note = "译文读不出来，显示内部名";
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        int ml = 0, mb = 0;
        if (modBundles != null) {
            // ★ 模组后叠（同层内覆盖版本 APK），与游戏"模组后加载"一致
            ml = modBundles.locale.size();
            mb = modBundles.base.size();
            locale.putAll(modBundles.locale);
            base.putAll(modBundles.base);
        }
        StringBuilder sb = new StringBuilder();
        if (nz > 0) sb.append(apk == null ? "" : apk.getName()).append(" 语言包 ").append(nz).append(" 条");
        else sb.append("版本里没有译文");
        if (nl(ml)) sb.append(" ＋ 模组语言包 ").append(ml).append(" 条");
        if (nb > 0) sb.append(" ＋ 版本基础包 ").append(nb).append(" 条");
        if (nl(mb)) sb.append(" ＋ 模组基础包 ").append(mb).append(" 条");
        note = sb.toString();
    }

    private static boolean nl(int n) {
        return n > 0;
    }

    /** 从 APK 里读一个 bundle 文件到目标层；返回读到几条（0 = 没有这个文件） */
    private static int load(ZipFile zf, String entry, Map<String, String> into) {
        InputStream in = null;
        try {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) return 0;
            in = zf.getInputStream(e);
            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            Properties p = new Properties();
            p.load(r);
            for (String k : p.stringPropertyNames()) {
                String v = p.getProperty(k);
                if (v != null && !v.trim().isEmpty()) into.put(k, v);
            }
            return into.size();
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 语言层 → 基础层；都没有 ⇒ 返回 {@code def}（调用方再退 pretty） */
    private String pick(String key, String def) {
        String v = locale.get(key);
        if (v != null && !v.isEmpty()) return clean(v);
        v = base.get(key);
        return v == null || v.isEmpty() ? def : clean(v);
    }

    @Override public String block(String internal) {
        return pick("block." + internal + ".name", "");
    }

    @Override public String item(String internal) {
        return pick("item." + internal + ".name", "");
    }

    @Override public String wallSuffix() {
        String v = pick("wallore", "（墙）");
        return v == null || v.isEmpty() ? "（墙）" : v;
    }

    @Override public String attr(String key) {
        String s = attrs.get(key);
        return s == null || s.isEmpty() ? key : s;
    }

    /** 补丁/模组能把 `[accent]` 这类标记写进名字 ⇒ 显示前一律剥掉（判据同 {@link Colors}） */
    private static String clean(String s) {
        return s == null ? "" : Colors.strip(s).trim();
    }
}
