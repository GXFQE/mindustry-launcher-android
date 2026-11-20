package io.mdt.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整包补丁（**APK 容器**）的读取与校验 —— 补丁机制 v2，2026-11-20。
 *
 * <h3>为什么是 APK 布局</h3>
 * APK 标准布局自带 `classes.dex` / `classes2.dex`（多 dex 由 ART 展开）、`lib/&lt;abi&gt;/*.so`、
 * `assets/**`；而 {@link Injector#injectDex} 本来就吃 APK、{@code addAssetPath} 也吃 APK
 * （基座游戏 APK 现在就同时走这两条）。所以补丁包不需要另发明容器 —— 一个 APK 根上多一个
 * {@code patch.json} 就够了。
 *
 * <pre>
 * mdtx-1607.apk
 * ├── patch.json            ← 根上（不在 assets/ 下，不会进游戏资产）
 * ├── classes.dex           ← 全部游戏代码（可 classes2.dex…）
 * ├── lib/&lt;abi&gt;/*.so        ← 可选；仅 role=game
 * └── assets/**             ← 可选；全量或差分
 * </pre>
 *
 * <p>本类只做"读 + 校验"；存储 / 导入 / 启用状态仍在 {@link Patches}。所有校验失败的码都由
 * {@link PatchException} 给，人话在 {@link PatchText}。</p>
 */
final class PatchPackage {

    /** 补丁库里那份 APK 的文件名（`app_hub/patches/&lt;id&gt;/&lt;ver&gt;/patch.apk`）。 */
    static final String APK = "patch.apk";

    static final long MAX_DEX = 64L * 1024 * 1024;
    static final long MAX_DEX_TOTAL = 128L * 1024 * 1024;
    static final long MAX_ASSETS = 128L * 1024 * 1024;
    static final long MAX_LIB = 32L * 1024 * 1024;
    static final long MAX_LIB_TOTAL = 64L * 1024 * 1024;
    static final long MAX_TOTAL = 256L * 1024 * 1024;
    static final int MAX_DEXES = 8;
    static final int MAX_ASSET_ENTRIES = 4096;
    static final int MAX_LIB_ENTRIES = 256;

    /** 允许出现在补丁包里的 ABI（与 {@link Compat#deviceAbis()} 的取值同族）。 */
    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"};

    private PatchPackage() {}

    // ── 识别 / 清单 ────────────────────────────────────────────────────────

    /**
     * 这是一个**整包补丁**（APK 容器）吗？
     *
     * <p>判据：根上有 `classes.dex` **且** 根上有 `patch.json`。老 `.mdp` 虽然也有 `patch.json`，
     * 但它的 dex 在 `targets/&lt;sha&gt;/` 下、根上没有 `classes.dex` ⇒ 不会被误判。</p>
     */
    static boolean isApkContainer(File zip) {
        if (zip == null || !zip.isFile()) return false;
        ZipFile z = null;
        try {
            z = new ZipFile(zip);
            return z.getEntry("classes.dex") != null && z.getEntry(PatchManifest.FILE) != null;
        } catch (IOException e) {
            return false;
        } finally {
            close(z);
        }
    }

    /** 读根上的 `patch.json`（schema 2）。 */
    static PatchManifest readManifest(File apk) throws PatchException {
        return PatchManifest.parse(readRoot(apk, PatchManifest.FILE, 64 * 1024));
    }

    /** 读根上的一个条目（限长；不存在 ⇒ C_MISSING_ENTRY）。 */
    static byte[] readRoot(File apk, String name, int max) throws PatchException {
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry(name);
            if (e == null) throw new PatchException(PatchException.C_MISSING_ENTRY, name);
            return readEntry(z, e, max);
        } catch (PatchException pe) {
            throw pe;
        } catch (IOException e) {
            throw new PatchException(PatchException.C_BAD_ZIP, e.getMessage());
        } finally {
            close(z);
        }
    }

    // ── 枚举 ────────────────────────────────────────────────────────────────

    /** 包里的 dex 文件名（按 classes.dex / classes2.dex … 的数字序；空 = 一份都没有）。 */
    static List<String> dexNames(File apk) throws IOException {
        ZipFile z = new ZipFile(apk);
        try {
            List<String> out = new ArrayList<String>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (dexIndex(n) > 0) out.add(n);
            }
            Collections.sort(out, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return dexIndex(a) - dexIndex(b);
                }
            });
            return out;
        } finally {
            close(z);
        }
    }

    /** `classes.dex` ⇒ 1，`classes2.dex` ⇒ 2 …；不是这个形状 ⇒ 0。 */
    static int dexIndex(String name) {
        if (name == null || !name.endsWith(".dex") || name.indexOf('/') >= 0) return 0;
        if (!name.startsWith("classes")) return 0;
        String mid = name.substring("classes".length(), name.length() - ".dex".length());
        if (mid.isEmpty()) return 1;
        for (int i = 0; i < mid.length(); i++) if (mid.charAt(i) < '0' || mid.charAt(i) > '9') return 0;
        try {
            int n = Integer.parseInt(mid);
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 校验（导入阶段一次做足）────────────────────────────────────────────

    /**
     * 校验一个整包补丁。**重 IO**（要读完全部 dex 做校验和/类表），调用方放后台线程。
     *
     * <p>判据顺序：清单 → dex（1..8 份、头/Adler-32/至少 1 类）→ 素材（`assets/` 下、安全路径）
     * → native（`lib/&lt;abi&gt;/&lt;name&gt;.so`、仅 role=game）→ 整包体积。</p>
     */
    static void validate(File apk) throws PatchException {
        if (apk == null || !apk.isFile()) {
            throw new PatchException(PatchException.C_IO, apk == null ? "" : apk.getName());
        }
        if (apk.length() > MAX_TOTAL) {
            throw new PatchException(PatchException.C_TOO_BIG, apk.getName());
        }
        PatchManifest m = readManifest(apk);
        if (m.apkSha256 == null) {
            // 根上有 classes.dex 却给了 v1 清单 ⇒ 不是合法的整包补丁
            throw new PatchException(PatchException.C_SCHEMA, String.valueOf(m.schema));
        }
        List<String> dexs;
        try {
            dexs = dexNames(apk);
        } catch (IOException e) {
            throw new PatchException(PatchException.C_BAD_ZIP, e.getMessage());
        }
        if (dexs.isEmpty()) throw new PatchException(PatchException.C_MISSING_ENTRY, "classes.dex");
        if (dexs.size() > MAX_DEXES) throw new PatchException(PatchException.C_FIELD, "dex");

        ZipFile z;
        try {
            z = new ZipFile(apk);
        } catch (IOException e) {
            throw new PatchException(PatchException.C_BAD_ZIP, e.getMessage());
        }
        try {
            long totalDex = 0;
            for (String name : dexs) {
                ZipEntry e = z.getEntry(name);
                if (e == null) throw new PatchException(PatchException.C_MISSING_ENTRY, name);
                if (e.getSize() > MAX_DEX) throw new PatchException(PatchException.C_TOO_BIG, name);
                totalDex += e.getSize();
                if (totalDex > MAX_DEX_TOTAL) throw new PatchException(PatchException.C_TOO_BIG, "dex");
                byte[] bytes = readEntry(z, e, MAX_DEX);
                try {
                    // 导入时真解析一遍：冲突检测靠类表；坏 checksum 必须在导入判死（B2 根因）
                    if (PatchDex.classes(bytes).isEmpty()) {
                        throw new PatchException(PatchException.C_BAD_DEX, name + " (0 classes)");
                    }
                } catch (IOException ioe) {
                    throw new PatchException(PatchException.C_BAD_DEX, name + ": " + ioe.getMessage());
                }
            }

            long totalAssets = 0;
            int assetCount = 0;
            long totalLib = 0;
            int libCount = 0;
            Set<String> seen = new HashSet<String>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                // 安全路径与同名条目要对**全部**条目成立：`sub/../evil.txt` 这类既不进
                // assets/ 也不进 lib/ 的条目，同样会让"清单/dex 取的是哪一个"变得不可预期。
                if (!PatchManifest.safeRel(n)) throw new PatchException(PatchException.C_BAD_PATH, n);
                if (!seen.add(n)) throw new PatchException(PatchException.C_BAD_ZIP, "duplicate entry: " + n);
                if (n.startsWith("assets/")) {
                    if (++assetCount > MAX_ASSET_ENTRIES) {
                        throw new PatchException(PatchException.C_TOO_BIG, "assets entries");
                    }
                    totalAssets += e.getSize();
                    if (totalAssets > MAX_ASSETS) throw new PatchException(PatchException.C_TOO_BIG, "assets");
                } else if (n.startsWith("lib/")) {
                    String[] p = n.split("/", -1);
                    if (p.length != 3 || !isAbi(p[1]) || !p[2].endsWith(".so") || p[2].isEmpty()) {
                        throw new PatchException(PatchException.C_BAD_PATH, n);
                    }
                    if (++libCount > MAX_LIB_ENTRIES) {
                        throw new PatchException(PatchException.C_TOO_BIG, "lib entries");
                    }
                    if (e.getSize() > MAX_LIB) throw new PatchException(PatchException.C_TOO_BIG, n);
                    totalLib += e.getSize();
                    if (totalLib > MAX_LIB_TOTAL) throw new PatchException(PatchException.C_TOO_BIG, "lib");
                }
            }
            if (libCount > 0 && !m.isGame()) {
                throw new PatchException(PatchException.C_FIELD, "libs");
            }
        } catch (PatchException pe) {
            throw pe;
        } catch (IOException ioe) {
            throw new PatchException(PatchException.C_BAD_ZIP, ioe.getMessage());
        } finally {
            close(z);
        }
    }

    // ── 读（供冲突检测 / 展示用；惰性由调用方缓存）────────────────────────────

    /** 全部 dex 定义类的并集（顺序 = dex 顺序、class_defs 顺序）。 */
    static List<String> classes(File apk) throws IOException {
        List<String> all = new ArrayList<String>();
        for (String name : dexNames(apk)) {
            byte[] bytes = readDex(apk, name);
            for (String c : PatchDex.classes(bytes)) {
                if (!all.contains(c)) all.add(c);
            }
        }
        return all;
    }

    /** 全部 dex 的类数之和（只读头，不解析字符串表）。 */
    static int classCount(File apk) throws IOException {
        int n = 0;
        for (String name : dexNames(apk)) {
            n += PatchDex.classCount(readDex(apk, name));
        }
        return n;
    }

    /** 素材键（**已剥掉 `assets/` 前缀**，与 AssetManager 里的键一致）。 */
    static List<String> assetKeys(File apk) throws IOException {
        List<String> out = new ArrayList<String>();
        ZipFile z = new ZipFile(apk);
        try {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (!n.startsWith("assets/")) continue;
                if (!PatchManifest.safeRel(n)) throw new IOException("unsafe asset: " + n);
                out.add(n.substring("assets/".length()));
            }
        } finally {
            close(z);
        }
        return out;
    }

    /** native 键（`abi/文件名`）。 */
    static List<String> libKeys(File apk) throws IOException {
        List<String> out = new ArrayList<String>();
        ZipFile z = new ZipFile(apk);
        try {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (!n.startsWith("lib/")) continue;
                String[] p = n.split("/", -1);
                if (p.length != 3) continue;
                out.add(p[1] + "/" + p[2]);
            }
        } finally {
            close(z);
        }
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private static byte[] readDex(File apk, String name) throws IOException {
        ZipFile z = new ZipFile(apk);
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null) throw new IOException("missing dex: " + name);
            return readEntry(z, e, MAX_DEX);
        } finally {
            close(z);
        }
    }

    private static byte[] readEntry(ZipFile z, ZipEntry e, long max) throws IOException {
        InputStream in = z.getInputStream(e);
        try {
            return readAll(in, max);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > max) throw new IOException("entry too big");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static boolean isAbi(String abi) {
        for (String a : ABIS) if (a.equals(abi)) return true;
        return false;
    }

    private static void close(ZipFile z) {
        if (z != null) {
            try { z.close(); } catch (IOException ignored) { }
        }
    }
}
