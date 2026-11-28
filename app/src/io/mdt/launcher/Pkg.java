package io.mdt.launcher;

import android.content.Context;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 游戏包的**虚拟包**：界面那些「从游戏包里读一个小文件」的地方都走这里。
 *
 * <h3>为什么需要这一层</h3>
 *
 * 开了共用文件（{@link Config#casGameEnabled()}）并删掉原始 APK 之后，
 * `new ZipFile(apk)` 会直接抛 `FileNotFoundException` —— 地图缩略图、蓝图、bundle、
 * 补丁注入源这些页面会一片空白。这里把两种来源统一成同一个读接口：
 *
 * <ul>
 *   <li><b>池</b>：清单里有这个版本 ⇒ 按条目 sha 直接读 `app_hub/pkgs/objects/` 里的文件；</li>
 *   <li><b>zip</b>：清单没有（没开开关 / 补丁 / 插件包 / 自检的合成包）⇒ 照旧开 {@link ZipFile}。</li>
 * </ul>
 *
 * <p>**只读小条目**：这里没有为「流式解大文件」做优化，界面那几处都是几百 KB 以内
 * （bundle 文本、配色 PNG、地图列表里的图标）。要读 dex / so 的是 {@link Injector}，
 * 它走 {@link PkgLauncher}，不经过这里。</p>
 */
final class Pkg implements Closeable {

    /** 一个条目（名字 / 解压后大小 / 打开流）——替代 {@code ZipEntry} + `getInputStream` 那一对 */
    interface Entry {
        String name();

        long size();

        /** 条目时间（导出 zip 时保留；池模式来自清单，可能为 0） */
        long time();

        InputStream open() throws IOException;
    }

    private final ZipFile zip;
    private final Pkgs pool;
    private final PkgManifest man;
    private final List<Entry> entries;

    /**
     * 进程级 app context：只给「这个包该不该走池」这一判断用（读清单要它）。
     *
     * <p>为什么不是每个调用点传：BundleNames / MapStats / GameAssets 这些消费方的签名里没有
     * Context，而它们又都在主进程里跑。这里存 {@code getApplicationContext()}（不是 Activity），
     * 不会漏 Activity；由 {@link LauncherApp#onCreate} 设一次。</p>
     */
    private static Context sApp;

    static void initApp(Context c) {
        if (c != null) sApp = c.getApplicationContext();
    }

    /** 没有 Context 的调用点用这个（走 {@link #sApp}） */
    static Pkg open(File apk) throws IOException {
        return open(sApp, apk);
    }

    private Pkg(ZipFile zip, Pkgs pool, PkgManifest man, List<Entry> entries) {
        this.zip = zip;
        this.pool = pool;
        this.man = man;
        this.entries = entries;
    }

    /** 这个包是不是走池（真机排查用） */
    boolean pooled() {
        return man != null;
    }

    /** 入口：`apkPath` 为空 ⇒ 返回 null（调用方原样处理） */
    static Pkg open(Context ctx, String apkPath) throws IOException {
        if (apkPath == null || apkPath.trim().isEmpty()) return null;
        return open(ctx, new File(apkPath.trim()));
    }

    /**
     * 入口：**先看清单，再看文件**。
     *
     * <p>顺序不能反：删了包之后 `apk.isFile()` 是 false，但清单还在 —— 这时必须能用。</p>
     */
    static Pkg open(Context ctx, File apk) throws IOException {
        PkgManifest m = manifestFor(ctx, apk);
        if (m != null) return fromPool(Pkgs.get(ctx), m);
        if (apk == null || !apk.isFile()) {
            throw new FileNotFoundException("no package: " + apk);
        }
        return fromZip(new ZipFile(apk));
    }

    /** 这个 APK 对应的清单（没开开关 / 不是导入的版本 / 不合规 ⇒ null） */
    private static PkgManifest manifestFor(Context ctx, File apk) {
        if (ctx == null || apk == null) return null;
        try {
            if (!Config.get().casGameEnabled()) return null;
            String name = apk.getName();
            if (name == null || name.isEmpty()) return null;
            // ★ 第 437 轮：只在「导入目录里的这一份」上按名字回落。
            //   别处的同名文件（自检夹具、临时拷贝）不是那个版本。
            java.io.File parent = apk.getAbsoluteFile().getParentFile();
            java.io.File dir = Importer.importDir(ctx).getAbsoluteFile();
            if (parent == null || !parent.equals(dir)) return null;
            PkgManifest m = PkgManifest.read(PkgManifest.fileOf(ctx, "import:" + name));
            return m != null && m.arc != null && m.arc.eligible ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Pkg fromZip(ZipFile zf) {
        final ZipFile z = zf;
        List<Entry> list = new ArrayList<Entry>();
        try {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e == null) continue;
                final ZipEntry ze = e;
                list.add(new Entry() {
                    @Override public String name() {
                        return ze.getName();
                    }

                    @Override public long size() {
                        return ze.getSize();
                    }

                    @Override public long time() {
                        return ze.getTime();
                    }

                    @Override public InputStream open() throws IOException {
                        return z.getInputStream(ze);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        return new Pkg(z, null, null, list);
    }

    private static Pkg fromPool(final Pkgs pool, PkgManifest m) {
        List<Entry> list = new ArrayList<Entry>();
        for (PkgManifest.Entry pe : m.entries) {
            if (pe == null || pe.path == null || pe.sha == null) continue;
            final PkgManifest.Entry e = pe;
            list.add(new Entry() {
                @Override public String name() {
                    return e.path;
                }

                @Override public long size() {
                    return e.size;
                }

                @Override public long time() {
                    return e.time;
                }

                @Override public InputStream open() throws IOException {
                    File o = pool.object(e.sha);
                    if (o == null || !o.isFile()) {
                        throw new FileNotFoundException("pool object missing: " + e.path);
                    }
                    return new FileInputStream(o);
                }
            });
        }
        return new Pkg(null, pool, m, list);
    }

    /** 全部条目（顺序与原包一致） */
    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** 按 zip 里的原路径找条目；没有 ⇒ null */
    Entry entry(String name) {
        if (name == null) return null;
        for (Entry e : entries) {
            if (name.equals(e.name())) return e;
        }
        return null;
    }

    /** 小条目的字节（找不到 ⇒ null） */
    byte[] bytes(String name) throws IOException {
        Entry e = entry(name);
        if (e == null) return null;
        InputStream in = e.open();
        try {
            return drain(in);
        } finally {
            closeQuietly(in);
        }
    }

    /** 读到底（这些条目都很小：bundle 文本 / 配色 PNG） */
    private static byte[] drain(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                Math.max(64, in.available()));
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 条目大小；没有 ⇒ -1 */
    long size(String name) {
        Entry e = entry(name);
        return e == null ? -1L : e.size();
    }

    @Override public void close() {
        if (zip != null) {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
