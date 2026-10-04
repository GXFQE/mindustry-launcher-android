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
 *   语言层 = 版本 APK 的 assets/bundles/bundle_&lt;界面语言&gt;.properties ⨁ 各模组同名的那个
 *   基础层 = 版本 APK 的 assets/bundles/bundle.properties            ⨁ 各模组的 bundle.properties
 *   查找   = 语言层 → 基础层 → （都没有）调用方退回内部名的可读化
 * </pre>
 * ★ `<界面语言>` 是**按语言前缀兜底匹配**的：应用内语言是 `zh` 时，
 *   `bundle_zh_CN.properties` 也算命中（否则整层落空、掉回英文 —— 用户报过这个）。
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
    /** 语言包没读到（或读炸了）—— 调用方据此给"显示内部名"的说明 */
    public boolean noLocaleBundle;
    /** 读到的条数（**给调用方按界面语言拼依据文案**；本类自己不拼用户可见的字） */
    public int nApkLocale, nModLocale, nApkBase, nModBase;
    /** 版本 APK 的文件名（依据文案里要点名） */
    public String apkName = "";
    /** 矿墙整名的兜底模板（`%1$s (wall)` / `%1$s（墙）`；`wallore` 取不到时用） */
    private final String wallNameFmt;

    /**
 * @param want        想要哪门语言的译文（{@link MapStats#bundleLang}）——
 *                     ★ 2026-10-04（P4）：原来这里**写死** `bundle_zh_CN.properties`，
 *                     界面切成英文之后矿物/物品名还是中文。
 *                     ⚠️ 匹配按**语言前缀兜底**（`zh` 也要认 `bundle_zh_CN.properties`），
 *                     判分口径在 {@link MapStats#bundleRank}。`lang` 为空 = 只用基础层
 *                     （官方那份 `bundle.properties` 就是英文）
     * @param attrLabels   属性词表（{@link MapStatsMods#attrLabels}）
     * @param wallNameFmt  矿墙整名模板（**按界面语言**给，别在这里写中文）
     *
     * ⚠️ **`modBundles` 必须是用同一门语言读出来的**（它进来就被无条件并进两层）——
     *   产品侧靠"缓存键里带语言"保证同源（{@link MapStatsMods#contentFor} 的 `key`），
     *   自检里也是按同一门语言各读一份。两处语言不一致 ⇒ 名字会**混着两种语言**。
     */
    public BundleNames(File apk, MapStats.Bundles modBundles, Map<String, String> attrLabels,
                       MapStats.BundleLang want, String wallNameFmt) {
        this.attrs = attrLabels == null ? Collections.<String, String>emptyMap() : attrLabels;
        this.wallNameFmt = wallNameFmt == null ? "%1$s" : wallNameFmt;
        MapStats.BundleLang w = want == null ? new MapStats.BundleLang("", "") : want;
        int nz = 0, nb = 0;
        ZipFile zf = null;
        try {
            if (apk != null && apk.isFile()) {
                zf = new ZipFile(apk);
                nz = loadBestLocale(zf, w, locale);
                nb = load(zf, "assets/bundles/bundle.properties", base);
            }
        } catch (Throwable t) {
            noLocaleBundle = true;
            note = "译文读不出来，显示内部名";
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (nz == 0) noLocaleBundle = true;
        int ml = 0, mb = 0;
        if (modBundles != null) {
            // ★ 模组后叠（同层内覆盖版本 APK），与游戏"模组后加载"一致
            ml = modBundles.locale.size();
            mb = modBundles.base.size();
            locale.putAll(modBundles.locale);
            base.putAll(modBundles.base);
        }
        // ★ 条数交给调用方（它按界面语言拼依据文案）——本类**不拼用户可见的字**
        nApkLocale = nz;
        nModLocale = ml;
        nApkBase = nb;
        nModBase = mb;
        apkName = apk == null ? "" : apk.getName();
        // `note` 只留给**维护者**（PC 验收台 `_lab/msav/f21/NameAudit` 打印它）
        StringBuilder sb = new StringBuilder();
        if (nz > 0) sb.append(apkName).append(" 语言包 ").append(nz).append(" 条");
        else sb.append("版本里没有译文");
        if (nl(ml)) sb.append(" ＋ 模组语言包 ").append(ml).append(" 条");
        if (nb > 0) sb.append(" ＋ 版本基础包 ").append(nb).append(" 条");
        if (nl(mb)) sb.append(" ＋ 模组基础包 ").append(mb).append(" 条");
        note = sb.toString();
    }

    private static boolean nl(int n) {
        return n > 0;
    }

    /**
     * 从 APK 的 `assets/bundles/` 里挑**最匹配界面语言**的那一份语言包。
     *
     * ★ 为什么要"挑最匹配"而不是按候选名一个个试：应用内语言是 `zh`（没有地区），
     *   而游戏发的是 `bundle_zh_CN.properties` ⇒ 精确匹配全落空、整层掉回英文
     *   （用户报的"换中文不管用"）。判分口径与 `MapStats.bundleRank` **同一把尺子**，
     *   不另写一份。
     * ⚠️ 同一门语言可能同时有 `bundle_zh_CN` 与 `bundle_zh_TW`：两份都读会互相覆盖
     *   ⇒ 只取分数最高的一份。
     */
    private static int loadBestLocale(ZipFile zf, MapStats.BundleLang want,
                                      Map<String, String> into) {
        if (want.lang.isEmpty()) return 0;
        String best = null;
        int bestRank = 0;
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            int r = MapStats.bundleRank(n, want);
            if (r < 10) continue;
            int slash = n.lastIndexOf('/');
            if (slash <= 0) continue;
            String dir = n.substring(0, slash);
            int d2 = dir.lastIndexOf('/');
            String dirName = (d2 >= 0 ? dir.substring(d2 + 1) : dir).toLowerCase(java.util.Locale.ROOT);
            if (!dirName.equals("bundles") && !dirName.endsWith("-bundles")) continue;
            if (r > bestRank || (r == bestRank && best != null && n.compareTo(best) < 0)) {
                bestRank = r;
                best = n;
            }
        }
        return best == null ? 0 : load(zf, best, into);
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

    @Override public String wallName(String base) {
        String b = base == null ? "" : base;
        // ★ 游戏 bundle 里那份 `wallore` **优先**：它才是"游戏里到底显示什么"的真源
        //   （zh_CN 上是「（墙）」，基础包里是英文那份）。取不到才用调用方给的整句模板。
        String s = pick("wallore", "");
        if (s != null && !s.isEmpty()) return b + s;
        try {
            return String.format(java.util.Locale.ROOT, wallNameFmt, b);
        } catch (Throwable t) {
            return b;
        }
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
