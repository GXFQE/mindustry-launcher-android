package io.mdt.plugins.mods.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 234 轮：把模组包（zip / jar）或目录里**覆盖原版**的两个通道读出来。
 *
 * <ol>
 *   <li>{@code sprites-override/**} —— 贴图覆盖通道（注册名**不加**模组前缀，见宿主的
 *       {@code ModSprites}；游戏打包时它最后写入，所以能盖掉原版贴图）；</li>
 *   <li>{@code bundles/*.properties} —— 文案覆盖通道（模组语言包与版本 APK 的语言包进
 *       **同一个 map**，后加载的模组覆盖先加载的；宿主 {@code BundleNames} 的注释里有字节码依据）。</li>
 * </ol>
 *
 * <p>★ **只读条目名 / 键**：不解压图片、不读值 ⇒ 22 MB 的包也只是读一遍中央目录，几十毫秒。
 * ★ 读不出来 ⇒ **空表**（不是"没有覆盖"）—— 调用方不许据此说"没问题"。
 * ★ 纯 Java（不碰 Android / 宿主），PC 侧 {@code build.sh check} 可喂夹具验。
 */
public final class Overrides {

    /** 覆盖原版贴图的目录（相对模组根）。 */
    public static final String SPRITES = "sprites-override/";
    /** 覆盖原版文案的目录（相对模组根）。 */
    public static final String BUNDLES = "bundles/";

    /** 防御坏包：单个模组最多记这么多张贴图 / 这么多条文案 / 这么多份 bundle 文件。 */
    static final int MAX_SPRITES = 5000;
    static final int MAX_KEYS = 20000;
    static final int MAX_FILES = 64;
    /** 单份 bundle 超过这个字节数就不读（真实的最多几百 KB）。 */
    static final long MAX_BUNDLE_BYTES = 4L << 20;

    private Overrides() { }

    /**
     * 读一个模组的两个覆盖通道（就地填进 {@code m}）。
     *
     * <p>目录形态用 {@code rootDir}（模组真正的根；host 已把"下潜一层"算好），
     * 压缩包形态用 {@code path}。读不出来就什么都不加。
     */
    public static void read(Conflicts.Mod m) {
        if (m == null) return;
        try {
            if (m.directory) {
                String root = m.rootDir == null || m.rootDir.trim().isEmpty()
                        ? m.path : m.rootDir.trim();
                File dir = root == null ? null : new File(root);
                if (dir != null && dir.isDirectory()) readDir(dir, m);
            } else {
                File zip = m.path == null ? null : new File(m.path);
                if (zip != null && zip.isFile()) readZip(zip, m);
            }
        } catch (Throwable ignored) {
            // 读不出来就是没有（调用方只把非空当证据）
        }
    }

    private static void readZip(File zip, Conflicts.Mod m) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e == null || e.isDirectory()) continue;
                String n = e.getName();
                if (n == null) continue;
                if (n.startsWith(SPRITES)) {
                    if (m.overrideSprites.size() < MAX_SPRITES) {
                        m.overrideSprites.add(n.substring(SPRITES.length()));
                    }
                } else if (n.startsWith(BUNDLES) && n.endsWith(".properties")) {
                    String file = n.substring(BUNDLES.length());
                    if (file.indexOf('/') >= 0) continue;   // 与游戏一致：只认 bundles/ 下的平铺文件
                    if (m.bundleKeys.size() >= MAX_FILES) continue;
                    Set<String> keys = new LinkedHashSet<String>();
                    long size = e.getSize();
                    if (size < 0 || size <= MAX_BUNDLE_BYTES) {
                        InputStream in = null;
                        try {
                            in = zf.getInputStream(e);
                            readKeys(new InputStreamReader(in, "UTF-8"), keys);
                        } catch (Throwable ignored) {
                        } finally {
                            close(in);
                        }
                    }
                    m.bundleKeys.put(file, keys);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(zf);
        }
    }

    private static void readDir(File root, Conflicts.Mod m) {
        File sprites = new File(root, "sprites-override");
        if (sprites.isDirectory()) collect(sprites, "", m.overrideSprites);
        File bundles = new File(root, "bundles");
        File[] kids = bundles.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".properties")) continue;
            if (m.bundleKeys.size() >= MAX_FILES) break;
            Set<String> keys = new LinkedHashSet<String>();
            Reader r = null;
            try {
                r = new InputStreamReader(new FileInputStream(f), "UTF-8");
                readKeys(r, keys);
            } catch (Throwable ignored) {
            } finally {
                close(r);
            }
            m.bundleKeys.put(f.getName(), keys);
        }
    }

    /** 递归收 {@code sprites-override/} 下的文件；键 = 相对该目录的路径（`/` 分隔）。 */
    private static void collect(File dir, String prefix, Set<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + "/", out);
            } else if (out.size() < MAX_SPRITES) {
                out.add(prefix + f.getName());
            }
        }
    }

    /**
     * 读 `.properties` 的**键**（够判"两份是不是同一个键"就行，值一个字都不留）。
     *
     * <p>分隔符认第一个 `=` 或 `:`（Mindustry 全用 `=`；`:` 是 Java 允许的另一种写法）。
     */
    private static void readKeys(Reader r, Set<String> out) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            if (out.size() >= MAX_KEYS) break;
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!') continue;
            int eq = s.indexOf('=');
            int colon = s.indexOf(':');
            int cut = eq < 0 ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            String k = cut > 0 ? s.substring(0, cut).trim() : s;
            if (!k.isEmpty()) out.add(k);
        }
    }

    /**
     * 两个模组在同一个通道上撞了哪些 —— **各自排好序**，方便文案取前三个。
     *
     * <p>★ 文案只认 {@code vanillaKeys} 里的键（那是"原版真的有的那句"；模组自己的键不算冲突，
     * 否则一份翻译包就能把整页刷满）。{@code vanillaKeys} 为空 ⇒ **不报文案**（读不到版本包，
     * 不猜）。
     */
    public static void merge(Conflicts.Mod a, Conflicts.Mod b, Set<String> vanillaKeys,
                             List<String> sprites, List<String> keys) {
        if (a == null || b == null) return;
        Set<String> as = a.overrideSprites, bs = b.overrideSprites;
        Set<String> small = as.size() <= bs.size() ? as : bs;
        Set<String> big = small == as ? bs : as;
        for (String s : small) {
            if (big.contains(s) && !sprites.contains(s)) sprites.add(s);
        }
        java.util.Collections.sort(sprites);
        if (vanillaKeys == null || vanillaKeys.isEmpty()) return;
        for (Map.Entry<String, Set<String>> e : a.bundleKeys.entrySet()) {
            Set<String> theirs = b.bundleKeys.get(e.getKey());
            if (theirs == null) continue;
            for (String k : e.getValue()) {
                if (theirs.contains(k) && vanillaKeys.contains(k) && !keys.contains(k)) {
                    keys.add(k);
                }
            }
        }
        java.util.Collections.sort(keys);
    }

    /** 关掉任何 Closeable，吞掉异常（读覆盖通道是"尽力而为"）。 */
    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
