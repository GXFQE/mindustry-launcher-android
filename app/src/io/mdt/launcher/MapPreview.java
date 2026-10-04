package io.mdt.launcher;

/**
 * 把 {@link MsavTiles} 解出来的瓦片画成**预览图**（照抄游戏的口径）。
 *
 * 依据（Mindustry 160.4）：
 * <pre>
 *   MapIO.generatePreview(Tiles)：逐格取色 ——
 *     有方块且不是 air ⇒ block 的颜色；否则（overlay==air && block==air）用 floor 的颜色；
 *     取不到（0）再走 colorFor()：wall 有颜色用 wall、overlay 有颜色按 alpha 128 与 floor 混。
 *   ContentLoader.loadColors()：颜色的**唯一来源**是 `sprites/block_colors.png` 第 i 列
 *     （第 i 个内容的 mapColor），**像素为 0/255 的表示"这个内容没有配色"**
 *     —— 正好就是游戏里 `hasColor` 的语义。
 * </pre>
 *
 * ★ 于是本类只用一条极简规则就能逼近游戏输出：
 * **方块色 → 没有就用矿色（与地板混合）→ 还没有就用地板色**；
 *   非固体方块（传送带/工厂之类）在 PNG 里本来就没有配色 ⇒ 自动落到地板色，
 *   与游戏的 `wall.solid ? wall.mapColor : floor.mapColor` 同效。
 *
 * 纯 Java（不碰 Android）⇒ 可以在 PC 上单独编译、逐像素对照游戏输出。
 */
public final class MapPreview {
    /** 没有任何配色时的兜底色（别让预览出现全透明洞） */
    private static final int FALLBACK = 0xFF404040;

    /** 渲染结果 */
    public static final class Img {
        public final int width, height;
        /** ARGB，行优先，长度 = width*height */
        public final int[] pixels;

        Img(int w, int h, int[] p) {
            this.width = w;
            this.height = h;
            this.pixels = p;
        }
    }

    private MapPreview() {}

    /**
     * 渲染并**顺带缩略**（采样法：每格取 scale×scale 方块的平均色，省掉全尺寸位图）。
     *
     * @param t       解码结果
     * @param palette id → ARGB；**0 表示"这个内容没有配色"**（与游戏一致）
     * @param maxSide 输出最长边上限（比如 512）
     */
    public static Img render(MsavTiles.Tiles t, int[] palette, int maxSide) {
        if (t == null || !t.ok() || palette == null || palette.length == 0) return null;
        int scale = Math.max(1, (int) Math.ceil(Math.max(t.width, t.height) / (double) maxSide));
        int ow = Math.max(1, t.width / scale), oh = Math.max(1, t.height / scale);
        int[] out = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                // 把一个 scale×scale 的小块平均一下（直接采样会在缩略图上丢细节）
                int r = 0, g = 0, b = 0, cnt = 0;
                for (int dy = 0; dy < scale; dy++) {
                    int y = oy * scale + dy;
                    if (y >= t.height) break;
                    for (int dx = 0; dx < scale; dx++) {
                        int x = ox * scale + dx;
                        if (x >= t.width) break;
                        int c = colorAt(t, palette, x, y);
                        r += (c >> 16) & 0xff;
                        g += (c >> 8) & 0xff;
                        b += c & 0xff;
                        cnt++;
                    }
                }
                out[oy * ow + ox] = cnt == 0 ? FALLBACK
                        : 0xFF000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
            }
        }
        return new Img(ow, oh, out);
    }

    /** 单格取色（规则的唯一实现，render 与自检共用） */
    public static int colorAt(MsavTiles.Tiles t, int[] palette, int x, int y) {
        int i = y * t.width + x;
        int wall = pal(palette, t.blocks[i]);
        if (wall != 0) return wall;                       // 有配色 ⇒ 当"固体/有色方块"看待
        int ore = pal(palette, t.ores[i]);
        int floor = pal(palette, t.floors[i]);
        if (ore != 0 && floor != 0) {
            // 游戏：overlay 按 alpha 128 与地板混合（见 MapIO.colorFor 的 Pixmap.blend）
            return blend(0x80000000 | (ore & 0xFFFFFF), floor);
        }
        if (ore != 0) return ore;
        if (floor != 0) return floor;
        return FALLBACK;
    }

    /** id → ARGB；越界或 0 都算"没有配色"（游戏也是拿 0 当"没颜色"） */
    private static int pal(int[] palette, int id) {
        if (id < 0 || id >= palette.length) return 0;
        int c = palette[id];
        // 游戏 loadColors 里 `color == 0 || color == 255` 的会被跳过（连同 alpha 一起）
        if (c == 0 || (c & 0xFFFFFF) == 0xFFFFFF) return 0;
        return 0xFF000000 | (c & 0xFFFFFF);
    }

    private static int blend(int top, int bottom) {
        int ta = (top >>> 24) & 0xff;
        int tr = (top >> 16) & 0xff, tg = (top >> 8) & 0xff, tb = top & 0xff;
        int br = (bottom >> 16) & 0xff, bg = (bottom >> 8) & 0xff, bb = bottom & 0xff;
        int r = (tr * ta + br * (255 - ta)) / 255;
        int g = (tg * ta + bg * (255 - ta)) / 255;
        int b = (tb * ta + bb * (255 - ta)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
