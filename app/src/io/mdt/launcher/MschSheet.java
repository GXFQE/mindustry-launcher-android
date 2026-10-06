package io.mdt.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 像素级预览的**供图侧**（Android 那一半）：从"这个槽指向的那个版本 APK"里
 * 读图集目录 + 按矩形裁贴图。渲染逻辑在纯 Java 的 {@link MschSprite} 里。
 *
 * <pre>
 *   APK                                    hub/previews/atlas/&lt;apk 指纹&gt;/
 *     assets/sprites/sprites.aatls  ──解目录──▶ 区域名 → 页 + 矩形
 *     assets/sprites/sprites.png    ──按需抽出──▶ sprites.png
 *                                                    │ BitmapRegionDecoder.decodeRegion(矩形)
 *                                                    ▼
 *                                               int[] 像素（喂给 MschSprite）
 * </pre>
 *
 * 🔴 **为什么要抽页图**：`BitmapRegionDecoder` 要能**随机访问**，而 zip 条目流不可 seek
 *   ⇒ 先把那一页 PNG 落盘（原版是 4096×4096 的 4 MB 级 PNG，抽一次就够，按 APK 指纹分目录）。
 * 🔴 **必须用版本 APK 自己的图集**：图集页的排版**逐版本会变**（第 116 轮已实测 content id 都会漂）
 *   ⇒ 区域矩形**不能烘进启动器**，只能现读现解。
 * ⚠️ 拿不到就直接返回 null ⇒ 调用方回落到色块预览（没分配版本的槽走的就是这条路）。
 */
public final class MschSheet implements MschSprite.Sheet {
    /** apkPath → sheet（同一时刻只留一份：槽切换时换掉，旧的关掉） */
    private static MschSheet CURRENT;
    private static String CURRENT_KEY;

    private final File apk;
    private final File pageDir;
    private final Map<String, MschAtlas.Region> regions;
    private final Map<String, BitmapRegionDecoder> decoders = new HashMap<>();
    /** 区域像素缓存（名字 → ARGB；一张 32×32 只有 4 KB，几百个也无所谓） */
    private final Map<String, int[]> pixels = new HashMap<>();
    private int pxBytes;

    private MschSheet(File apk, File pageDir, Map<String, MschAtlas.Region> regions) {
        this.apk = apk;
        this.pageDir = pageDir;
        this.regions = regions;
    }

    /**
     * 打开（带缓存）某个版本 APK 的图集。
     *
     * @return 拿不到（没给路径 / APK 读不了 / 图集格式不对）返回 null —— **不抛**
     */
    public static synchronized MschSheet open(Context ctx, String apkPath) {
        if (apkPath == null || apkPath.trim().isEmpty()) return null;
        String key = apkPath.trim();
        if (CURRENT != null && key.equals(CURRENT_KEY)) return CURRENT;
        closeCurrent();
        MschSheet s = build(ctx, new File(key));
        CURRENT = s;
        CURRENT_KEY = s == null ? null : key;
        return s;
    }

    private static void closeCurrent() {
        if (CURRENT != null) CURRENT.close();
        CURRENT = null;
        CURRENT_KEY = null;
    }

    private static MschSheet build(Context ctx, File apk) {
        ZipFile zf = null;
        InputStream in = null;
        try {
            if (apk == null || !apk.isFile()) return null;
            zf = new ZipFile(apk);
            ZipEntry e = zf.getEntry("assets/sprites/sprites.aatls");
            if (e == null) e = zf.getEntry("sprites/sprites.aatls");
            if (e == null) return null;
            in = zf.getInputStream(e);
            Map<String, MschAtlas.Region> regions = MschAtlas.read(in);
            if (regions == null || regions.isEmpty()) return null;
            File dir = new File(MapLoad.cacheDir(ctx), "atlas/"
                    + MapLoad.md5(apk.getAbsolutePath() + "|" + apk.length() + "|" + apk.lastModified()));
            return new MschSheet(apk, dir, regions);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override public MschAtlas.Region find(String region) {
        return region == null ? null : regions.get(region);
    }

    @Override public int[] pixels(MschAtlas.Region r) {
        if (r == null) return null;
        synchronized (this) {
            int[] hit = pixels.get(r.page + "|" + r.x + "," + r.y + "," + r.w + "," + r.h);
            if (hit != null) return hit;
        }
        int[] px = decode(r);
        if (px == null) return null;
        synchronized (this) {
            if (pxBytes > (6 << 20)) {                 // 6 MB 上限：满了整片清（列表滚回来会重解）
                pixels.clear();
                pxBytes = 0;
            }
            pixels.put(r.page + "|" + r.x + "," + r.y + "," + r.w + "," + r.h, px);
            pxBytes += px.length * 4;
        }
        return px;
    }

    /** 裁一块贴图（顺带把页图抽到磁盘上） */
    private int[] decode(MschAtlas.Region r) {
        BitmapRegionDecoder dec;
        synchronized (this) {
            dec = decoders.get(r.page);
        }
        if (dec == null) {
            File page = ensurePage(r.page);
            if (page == null) return null;
            try {
                dec = BitmapRegionDecoder.newInstance(page.getAbsolutePath(), false);
            } catch (Throwable t) {
                return null;
            }
            synchronized (this) {
                decoders.put(r.page, dec);
            }
        }
        Bitmap bmp = null;
        try {
            // 🔴 `inPremultiplied = false`：默认解出来是**预乘 alpha**，而 {@link MschSprite#paste}
            //    按"直通 alpha"混合 ⇒ 贴图边缘会发黑（半透明边一圈脏边）。PC 侧用 ImageIO 拿到的
            //    也是直通值 ⇒ 两边必须一致。
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPremultiplied = false;
            bmp = dec.decodeRegion(new Rect(r.x, r.y, r.x + r.w, r.y + r.h), opt);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] out = new int[w * h];
            bmp.getPixels(out, 0, w, 0, 0, w, h);
            // ⚠️ trim 过的区域（如 `duo` 26×28）要**补回原尺寸**再交给渲染器吗？
            //   不用 —— 游戏 `Draw.rect` 就是拿打包后的尺寸居中画的（见 MschSprite 类注释 ③）
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** 把页图从 APK 里抽到磁盘（已抽过就直接用） */
    private File ensurePage(String page) {
        try {
            File dir = pageDir;
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return null;
            File out = new File(dir, page);
            if (out.isFile() && out.length() > 0) return out;
            ZipFile zf = new ZipFile(apk);
            InputStream in = null;
            FileOutputStream fos = null;
            File part = new File(dir, page + ".part");
            try {
                ZipEntry e = zf.getEntry("assets/sprites/" + page);
                if (e == null) e = zf.getEntry("sprites/" + page);
                if (e == null) return null;
                in = new java.io.BufferedInputStream(zf.getInputStream(e), 1 << 16);
                fos = new FileOutputStream(part);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                fos = null;
                // 原子就位（与工程其它落盘同一条纪律：先 .part 再改名）
                if (!part.renameTo(out)) {
                    part.delete();
                    return null;
                }
                return out;
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
                zf.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 关掉所有解码器（换槽 / 换版本时） */
    private void close() {
        synchronized (this) {
            for (BitmapRegionDecoder d : decoders.values()) {
                try {
                    d.recycle();
                } catch (Throwable ignored) {
                }
            }
            decoders.clear();
            pixels.clear();
            pxBytes = 0;
        }
    }

    /** 自检用：现在手上有没有打开的表（判据要看得见状态） */
    static synchronized boolean isOpen(String apkPath) {
        return CURRENT != null && apkPath != null && apkPath.equals(CURRENT_KEY);
    }

    /** 自检用：这个 APK 的页图缓存目录（抽出来的 PNG 在哪） */
    static File pageDirOf(MschSheet s) {
        return s == null ? null : s.pageDir;
    }

    /** 自检用：`BitmapFactory` 解一张页图（证明抽出来的 PNG 是好的） */
    static int[] probePage(MschSheet s, String page) {
        if (s == null) return null;
        File f = s.ensurePage(page);
        if (f == null) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(),
                new BitmapFactory.Options());
        if (b == null) return null;
        int[] out = {b.getWidth(), b.getHeight()};
        b.recycle();
        return out;
    }
}
