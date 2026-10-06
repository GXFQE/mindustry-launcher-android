package io.mdt.launcher;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * 蓝图预览图的**加载侧**（Android 那一半）：渲染、整数倍放大、缓存。
 * 纯逻辑在 {@link MschPreview}（不碰 Android，能在 PC 上单独验）。
 *
 * <h3>和地图 / 存档那条的关系</h3>
 * <pre>
 *   「内存 → 磁盘 → 现渲染」这套流程**只有一份实现**（{@link MapLoad#cached}）
 *   ⇒ 三条预览线的缓存键、落盘位置（`hub/previews/`）、内存上限**不会分叉**；
 *   本类只负责"怎么把一份 .msch 变成一张图"。
 * </pre>
 *
 * 🔴 与地图预览的**一处刻意不同**：蓝图**不依赖版本 APK**
 *   （配色烘在 {@link BlockTable} 里，见那个文件的头注释）⇒
 *   **槽还没指定版本时，蓝图照旧有预览图**；而地图那边没版本就没有配色表、只能不出图。
 */
public final class MschLoad {
    /** 缩略图边长（列表用，与 {@link MapLoad#THUMB} 同值） */
    public static final int THUMB = 128;
    /** 详情大图边长（与 {@link MapLoad#BIG} 同值） */
    public static final int BIG = 512;
    /**
     * 太小的图**整数倍放大**到这个边长再交给 ImageView（按用途取：列表 256 / 详情 512）。
     *
     * ★ 为什么要它：一份 15×15 的蓝图渲染出来就是 15×15 像素，而列表里的缩略图框是 56dp
     *   （本机 1224px 宽 ≈ 3.1x ⇒ ≈175px）⇒ 由 ImageView 拉伸出来是一片糊（2026-10-06
     *   真机第一版实测：`MIN_SIDE=96` 出来的图在列表里明显发虚）。
     *   整数倍 + **最近邻**（相邻像素直接复制，不是插值）能保住"一格一个方块"的锐利边界。
     * ⚠️ 放大发生在**落盘之前**（`MapLoad.cached` 拿到的就是放大后的像素）⇒ 缓存里存的就是清晰版。
     */
    private static final int MIN_SIDE_THUMB = 256;
    private static final int MIN_SIDE_BIG = 512;

    private MschLoad() {}

    /**
     * 取蓝图预览（内存 → 磁盘 → 现渲染）。
     *
     * @param maxSide {@link #THUMB} / {@link #BIG}
     * @return 失败返回 null（界面**保持 GONE**，不留空框）
     */
    public static Bitmap image(final Context ctx, final Blueprints.Item it, final int maxSide) {
        if (it == null || it.msch == null) return null;
        return MapLoad.cached(ctx, key(it), maxSide, new MapLoad.Renderer() {
            @Override public MapPreview.Img render() {
                MapPreview.Img img = MschPreview.render(it.msch, MschPreview.table(), maxSide);
                return img == null ? null : upscale(img, maxSide >= BIG ? MIN_SIDE_BIG : MIN_SIDE_THUMB);
            }
        });
    }

    /**
     * 缓存身份：**槽内文件用路径+大小+时间**，zip/APK 条目用容器+条目名+大小。
     * ⚠️ 与 {@link MapLoad#cacheKey} 同一套口径（同一个理由：内容变了就必须换 key）。
     * ⚠️ 这里**不带** maxSide —— 那个由 {@link MapLoad#cached} 自己拼进 key。
     */
    static String key(Blueprints.Item it) {
        if (it.file != null) return MapLoad.fileKey(it.file);
        return (it.container == null ? "?" : it.container.getAbsolutePath()) + "|" + it.bytes + "|"
                + it.entry;
    }

    /** 整数倍最近邻放大（见 {@link #MIN_SIDE_THUMB}）；已经够大就原样返回 */
    static MapPreview.Img upscale(MapPreview.Img img, int minSide) {
        int f = Math.max(1, minSide / Math.max(img.width, img.height));
        if (f <= 1) return img;
        int w = img.width * f, h = img.height * f;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = y / f;
            for (int x = 0; x < w; x++) {
                out[y * w + x] = img.pixels[sy * img.width + x / f];
            }
        }
        return new MapPreview.Img(w, h, out);
    }
}
