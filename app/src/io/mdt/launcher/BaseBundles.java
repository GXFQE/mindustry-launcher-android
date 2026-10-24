package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * **v28**（第 234 轮）：这个槽的目标游戏版本里 `assets/bundles/bundle.properties` 的键集合 ——
 * 即"哪些文案键是原版真的有的"。
 *
 * <p>为什么需要它：两个模组都写同一个 bundle 键时，后加载的会盖掉先加载的；但**只有原版键**
 * 才算"覆盖原版文案"。模组自己的键（`logicsugar.*` 之类）被别的包也写一份，往往是翻译包 / fork，
 * 不是覆盖 —— 不拿原版清单过滤，一份翻译包就能把整页刷满（真机上 13 个模组里有 4,437 条同名键，
 * 其中大部分是这种）。
 *
 * <p>★ **读不到就是空集**（没有目标版本 / APK 不给读 / 条目不存在）—— 调用方不许把它当成
 * "原版没有这些键"（那是另一句话，得能算出 false）。
 * ★ 读一次后按**APK 路径 + mtime** 缓存：插件页每次重扫都会问，别每次都开 78 MB 的包。
 */
final class BaseBundles {

    private static String cPath = "";
    private static long cMtime = -1;
    private static Set<String> cKeys = Collections.emptySet();

    private BaseBundles() { }

    /** 槽 → 原版基础包的键集（槽没有目标版本 ⇒ 空集）。 */
    static Set<String> keys(Context ctx, String slot) {
        String apk = null;
        try {
            Mods.Target t = Mods.targetsFor(ctx, slot);
            apk = t == null ? null : t.apkPath;
        } catch (Throwable ignored) {
        }
        if (apk == null || apk.trim().isEmpty()) return Collections.emptySet();
        return keysOf(new File(apk.trim()));
    }

    /**
     * 纯文件版（自检可以用合成 APK 验；不碰 Context）。
     *
     * <p>缓存只有一格：插件页只服务一个槽，来回切槽时才重读（77 MB 的包读一份文本很快，
     * 但没必要每次都开）。
     */
    static Set<String> keysOf(File apk) {
        if (apk == null || !apk.isFile()) return Collections.emptySet();
        final String path = apk.getAbsolutePath();
        final long mtime = apk.lastModified();
        synchronized (BaseBundles.class) {
            if (path.equals(cPath) && mtime == cMtime) return cKeys;
            Set<String> out = load(apk);
            cPath = path;
            cMtime = mtime;
            cKeys = out;
            return out;
        }
    }

    private static Set<String> load(File apk) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            ZipEntry ze = zf.getEntry("assets/bundles/bundle.properties");
            if (ze == null) return Collections.emptySet();
            Properties p = new Properties();
            InputStream in = zf.getInputStream(ze);
            try {
                // ★ 必须 UTF-8：Properties.load(InputStream) 是 ISO-8859-1（与 BundleNames 同一个坑）
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            return Collections.unmodifiableSet(
                    new LinkedHashSet<String>(p.stringPropertyNames()));
        } catch (Throwable t) {
            return Collections.emptySet();
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
