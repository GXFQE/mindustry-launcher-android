package io.mdt.launcher;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * 蓝图预览图的**加载侧**（Android 那一半）：取图 → 整数倍放大 → 缓存。
 * 纯逻辑在 {@link MschSprite}（**像素级**：拿版本 APK 里的**真贴图**按游戏口径拼），
 * 它只吃像素数组 ⇒ 不碰 Android、能在 PC 上单独验。
 *
 * <h3>两条路（第 118 轮起只剩一条 —— 用户否掉了色块档）</h3>
 * <pre>
 *   有版本 APK 且图集解得开 ⇒ **像素级**（和游戏里那张缩略图同构）
 *   没有版本 APK / 图集读不了 ⇒ **没有图**（返回 null ⇒ 界面保持 GONE）
 * </pre>
 * 🔴 用户 2026-10-06（第 118 轮）原话：「**没版本时不用回退（那东西太抽象了毫无意义啊）**」
 *   —— 第 116 轮那个"色块档"（一格画一个方块的颜色）**已被整条删除**，不是禁用：
 *   与其给一张读不出布局的马赛克，不如**什么都不显示**（与地图页 / 存档页同一条口径，
 *   也是用户 2026-10-04 那句「拿不到内容就把那块收掉，不要留空框」）。
 * 🔴 **像素级必须读版本 APK**：图集页的排版逐版本会变 ⇒ 区域矩形不能烘进启动器。
 *   ⚠️ 因此缓存键里**必须带上 APK 身份**（换版本 ⇒ 换 key，否则会显示上一个版本的图）。
 *
 * 「内存 → 磁盘 → 现渲染」那套流程仍然只有一份实现（{@link MapLoad#cached}），
 * 落点同样是 `hub/previews/`。
 */
public final class MschLoad {
    /** 缩略图边长（列表用，与 {@link MapLoad#THUMB} 同值） */
    public static final int THUMB = 128;
    /** 详情大图边长（与 {@link MapLoad#BIG} 同值） */
    public static final int BIG = 512;
    /**
     * 太小的图**整数倍放大**到这个边长再交给 ImageView（按用途取：列表 256 / 详情 512）。
     *
     * ★ 为什么要它：一份 15×15 的蓝图按原始密度渲染是 480×480，但一份 5×5 的只有 160×160，
     *   而列表里的框是 56dp（本机 1224px 宽 ≈ 3.1x ⇒ ≈175px）⇒ 不放大就发虚。
     *   整数倍 + **最近邻**（相邻像素直接复制，不是插值）能保住"一格一个方块"的锐利边界。
     * ⚠️ 放大发生在**落盘之前**（`MapLoad.cached` 拿到的就是放大后的像素）⇒ 缓存里存的就是清晰版。
     */
    private static final int MIN_SIDE_THUMB = 256;
    private static final int MIN_SIDE_BIG = 512;

    private MschLoad() {}

    /**
     * 取蓝图预览（内存 → 磁盘 → 现渲染）。
     *
     * @param apkPath 这个槽指向的版本 APK；**没有（null）⇒ 直接返回 null**（不画任何图 —— 见类注释）
     * @param maxSide {@link #THUMB} / {@link #BIG}
     * @return 出不了图返回 null（界面**保持 GONE**，不留空框）
     */
    public static Bitmap image(final Context ctx, final Blueprints.Item it, final String apkPath,
                               final int maxSide) {
        if (it == null || it.msch == null) return null;
        // ★ 没有版本就没有图：**不回落**（用户 2026-10-06：「那东西太抽象了毫无意义啊」）
        if (apkPath == null || apkPath.trim().isEmpty()) return null;
        return MapLoad.cached(ctx, key(it, apkPath), maxSide, new MapLoad.Renderer() {
            @Override public MapPreview.Img render() {
                int target = maxSide >= BIG ? MIN_SIDE_BIG : MIN_SIDE_THUMB;
                MschSheet sheet = MschSheet.open(ctx, apkPath);
                if (sheet == null) return null;              // 图集读不了 ⇒ 没有图（不回落）
                MapPreview.Img img = MschSprite.render(it.msch, sheet, target);
                return img == null ? null : upscale(img, target);
            }
        });
    }

    /**
     * 缓存身份：**槽内文件用路径+大小+时间**，zip/APK 条目用容器+条目名+大小，
     * **再加版本 APK 的指纹**（像素级图依赖它；不带就会在换版本后显示上一版的图）。
     * ⚠️ 与 {@link MapLoad#cacheKey} 同一套口径（同一个理由：内容变了就必须换 key）。
     */
    static String key(Blueprints.Item it, String apkPath) {
        String base;
        if (it.file != null) {
            base = MapLoad.fileKey(it.file);
        } else {
            base = (it.container == null ? "?" : it.container.getAbsolutePath()) + "|" + it.bytes + "|"
                    + it.entry;
        }
        String apkId = "none";
        if (apkPath != null && !apkPath.trim().isEmpty()) {
            java.io.File f = new java.io.File(apkPath.trim());
            apkId = MapLoad.md5(f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified());
        }
        return base + "|" + apkId;
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
