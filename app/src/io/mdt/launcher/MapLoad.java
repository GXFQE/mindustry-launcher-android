package io.mdt.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 地图预览的**加载侧**（Android 那一半）：配色表、解瓦片、画 Bitmap、缓存。
 * 纯逻辑在 {@link MsavTiles} + {@link MapPreview}（那两个不碰 Android，能在 PC 上单独验）。
 *
 * 配色表的来源是**关键设计**（见 REF §55.14）：
 * 游戏 `ContentLoader.loadColors()` 读 `sprites/block_colors.png`，
 * **第 i 列第 0 行 = 第 i 个方块的 mapColor** ⇒ 我们**从"这个槽指向的版本 APK"里直接读那张图**，
 * 天然与该版本的内容顺序一致，**不需要自己导表、也不需要往 APK 里塞资源**。
 */
public final class MapLoad {
    /** 缩略图边长（列表用） */
    public static final int THUMB = 128;
    /** 详情大图边长 */
    public static final int BIG = 512;

    /** apkPath → 配色表（一张 PNG 只有几百像素，但没必要反复解） */
    private static final Map<String, int[]> PALETTES = new HashMap<>();
    /** 内存里的缩略图（key = 缓存文件名），避免列表滚动时反复读盘 */
    private static final Map<String, Bitmap> MEM = new HashMap<>();
    private static final int MEM_MAX = 64;

    private MapLoad() {}

    /** 缓存目录：`hub/previews/`（放 HUB 自己的目录里，和槽数据分开） */
    public static File cacheDir(Context ctx) {
        File d = new File(Data.hubDir(ctx), "previews");
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) return null;
        return d;
    }

    /**
     * 从版本 APK 里读配色表。
     *
     * @return `id → ARGB`（**0 表示该内容没有配色**，与游戏一致）；读不到返回 null
     */
    public static synchronized int[] palette(Context ctx, String apkPath) {
        if (apkPath == null || apkPath.trim().isEmpty()) return null;
        String key = apkPath.trim();
        int[] cached = PALETTES.get(key);
        if (cached != null) return cached.length == 0 ? null : cached;
        int[] pal = null;
        ZipFile zf = null;
        InputStream in = null;
        try {
            zf = new ZipFile(new File(key));
            ZipEntry e = zf.getEntry("assets/sprites/block_colors.png");
            if (e == null) e = zf.getEntry("sprites/block_colors.png");
            if (e != null) {
                in = new BufferedInputStream(zf.getInputStream(e), 8192);
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    pal = new int[bmp.getWidth()];
                    for (int x = 0; x < bmp.getWidth(); x++) {
                        pal[x] = bmp.getPixel(x, 0);
                    }
                    bmp.recycle();
                }
            }
        } catch (Throwable t) {
            pal = null;
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
        PALETTES.put(key, pal == null ? new int[0] : pal);
        return pal;
    }

    /** 缓存文件名：**槽内文件用路径+大小+时间**，zip 条目用容器+条目名（避免同名互相覆盖） */
    static String cacheKey(Maps.Item it) {
        if (it.file != null) return md5(fileKey(it.file)) + ".png";
        return md5((it.container == null ? "?" : it.container.getAbsolutePath()) + "|" + it.bytes + "|"
                + it.entry) + ".png";
    }

    /**
     * 磁盘上那份 `.msav` 的定位串（路径 + 大小 + 修改时间）。
     * ★ 存档与地图**同一套口径** ⇒ 同一份文件不会出现两张不同的图（改过的图也不会显示旧预览）。
     */
    static String fileKey(File f) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    /**
     * 「现渲染」那一半（谁都能实现）。
     * ★ 抽出来是为了让**地图 / 存档 / 蓝图**共用同一套「内存 → 磁盘 → 现渲染」的取图流程
     *   （{@link #cached}）—— 三条路各写一份的话，缓存键、落盘、内存上限迟早会分叉。
     */
    interface Renderer {
        /** @return 出不了图返回 null（**不许**抛） */
        MapPreview.Img render();
    }

    /**
     * 取缩略图/大图（内存 → 磁盘 → 现渲染）。
     *
     * @param maxSide {@link #THUMB} / {@link #BIG}
     * @return 失败返回 null（界面显示占位，不报错）
     */
    public static Bitmap image(Context ctx, Maps.Item it, String apkPath, int maxSide) {
        if (it == null) return null;
        return image(ctx, it, it.file, apkPath, maxSide);
    }

    /**
     * 一份**存档**（或任何 `.msav` 文件）的预览图。
     *
     * ★ 存档和地图**是同一种文件**（`.msav`；REF §72）⇒ 解码、上色、缩略、缓存全部照用，
     *   唯一的差别是"定位信息"只有那个文件（没有 {@link Maps.Item} 那份来源/容器）。
     * ⚠️ 缓存键与地图那条**同源**（{@link #fileKey}：路径+大小+修改时间）
     *   ⇒ 同一份文件不会出现两张不同的图；游戏里覆盖保存过之后 key 会变，也不会显示旧预览。
     */
    public static Bitmap image(Context ctx, File f, String apkPath, int maxSide) {
        if (f == null) return null;
        return image(ctx, null, f, apkPath, maxSide);
    }

    /**
     * 地图 / 存档的**唯一**取图路径（内存 → 磁盘 → 现渲染）。
     *
     * @param it    zip/APK 条目来源（槽内文件时为 null）
     * @param file  磁盘上的那份（zip/APK 条目来源时为 null）
     */
    private static Bitmap image(final Context ctx, final Maps.Item it, final File file,
                                final String apkPath, final int maxSide) {
        final String keyBase = file != null ? fileKey(file) : cacheKey(it);
        return cached(ctx, keyBase, maxSide, new Renderer() {
            @Override public MapPreview.Img render() {
                int[] palette = palette(ctx, apkPath);
                if (palette == null) return null;
                MsavTiles.Tiles tiles = file != null ? MsavTiles.read(file, true) : decode(it);
                if (tiles == null) return null;
                return MapPreview.render(tiles, palette, maxSide);
            }
        });
    }

    /**
     * 「内存 → 磁盘 → 现渲染」的**唯一实现**（地图 / 存档 / 蓝图三条预览线共用）。
     *
     * @param keyBase  缓存身份（改了内容就得换 key；地图/存档那条 = 路径+大小+时间，
     *                 蓝图那条见 {@link MschLoad}）
     * @param maxSide  输出边长上限（**进 key**：同一份东西的缩略图与大图是两个缓存）
     * @param renderer 现渲染；返回 null = 出不了图，此时**不落盘**（下次还会再试一次，
     *                 但"没版本 / 老格式"这种原因不会自己变好，代价只是重算一遍）
     */
    static Bitmap cached(Context ctx, String keyBase, int maxSide, Renderer renderer) {
        String key = keyBase + "@" + maxSide;
        synchronized (MEM) {
            Bitmap b = MEM.get(key);
            if (b != null && !b.isRecycled()) return b;
        }
        File dir = cacheDir(ctx);
        File disk = dir == null ? null : new File(dir, md5(key) + ".png");
        if (disk != null && disk.isFile()) {
            try {
                Bitmap b = BitmapFactory.decodeFile(disk.getAbsolutePath());
                if (b != null) {
                    putMem(key, b);
                    return b;
                }
            } catch (Throwable ignored) {
            }
        }
        // 现渲染
        MapPreview.Img img = renderer.render();
        if (img == null) return null;
        Bitmap bmp = Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888);
        if (disk != null) {
            java.io.OutputStream out = null;
            try {
                out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(disk), 8192);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Throwable ignored) {
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        putMem(key, bmp);
        return bmp;
    }

    /** 缓存文件名用的短哈希（自带的，别指望 Util.md5 —— 那个只吃 File） */
    static String md5(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] h = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void putMem(String key, Bitmap b) {
        synchronized (MEM) {
            if (MEM.size() >= MEM_MAX) MEM.clear();      // 简单粗暴：满了就清（列表滚回来会重读盘）
            MEM.put(key, b);
        }
    }

    /** 解一个来源的瓦片（槽内文件直接读；zip/APK 条目开流读）—— 有预览小地图就拿小的（列表用） */
    static MsavTiles.Tiles decode(Maps.Item it) {
        return decode(it, true);
    }

    /**
     * 解**完整地图**（F21 统计用）。
     *
     * 🔴 统计**绝不能**用预览小地图：v13 的 .msav 里带一张降采样的 preview_map，
     *   {@link #decode} 会优先要它（列表缩略图要快），拿它统计出来的格数是错的。
     */
    static MsavTiles.Tiles decodeFull(Maps.Item it) {
        return decode(it, false);
    }

    private static MsavTiles.Tiles decode(Maps.Item it, boolean preferPreview) {
        if (it == null) return null;
        if (it.file != null) return MsavTiles.read(it.file, preferPreview);
        if (it.container == null || it.entry == null) return null;
        ZipFile zf = null;
        InputStream in = null;
        try {
            zf = new ZipFile(it.container);
            ZipEntry e = zf.getEntry(it.entry);
            if (e == null) return null;
            in = new java.io.BufferedInputStream(zf.getInputStream(e), 1 << 16);
            return MsavTiles.read(in, preferPreview);
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
}
