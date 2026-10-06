package io.mdt.launcher;

import java.util.Arrays;
import java.util.Map;

/**
 * **像素级**蓝图预览：拿游戏图集里的**真贴图**按游戏的口径拼（与游戏里那一张缩略图同构）。
 *
 * <h3>规则全部照抄游戏（v160 源码）</h3>
 * <pre>
 *   ① 画哪张图 = `UnlockableContent.loadIcon()` 的 5 级回落：
 *        fullOverride（原版全是空串）→ `block-&lt;名字&gt;-full` → `&lt;名字&gt;-full` → `&lt;名字&gt;`
 *        → `block-&lt;名字&gt;` → `&lt;名字&gt;1`（变体，垃圾墙那种）
 *   ② 画在哪 = `Block.drawDefaultPlanRegion`：
 *        `Draw.rect(fullIcon, plan.drawx(), plan.drawy(), (!rotate || !rotateDraw) ? 0 : rotation * 90)`
 *      而 `BuildPlan.drawx() = x * tilesize + ((size + 1) % 2) * tilesize / 2`
 *      ⇒ 在"格"坐标系里，方块中心 = `x + off + size / 2`（off = -(size-1)/2，多格方块的**中心**）。
 *   ③ 尺寸 = **打包后**的宽高（`Draw.rect` 用 `region.width/height`；
 *      `AtlasRegion.offsetX/offsetY` 那套 trim 偏移**被忽略** —— 见 arc `Draw.java`）。
 *   ④ 旋转方向：`rotation * 90` 在游戏的 **y 向下**投影里 = 图像空间**顺时针** 90°×rot
 *      （Mindustry 的 rotation：0 上 / 1 右 / 2 下 / 3 左）。
 * </pre>
 *
 * <h3>为什么是"纯 Java"</h3>
 * 本类**只吃像素数组**（{@link Sheet}）—— Android 侧用 `BitmapRegionDecoder` 供图、
 * PC 侧用 `ImageIO` 供图 ⇒ 同一份渲染逻辑两边都能验（自检喂假 Sheet 也能钉住规则）。
 *
 * <h3>出不了图怎么办</h3>
 * 没有版本 APK / 图集解不开 / 这个方块名字查不到区域 ⇒ **逐格退化成色块**
 * （{@link MschPreview} 那套配色），所以"没版本"时界面照样有东西可看（见 §79/§117）。
 */
public final class MschSprite {
    /** 一格多少像素（游戏贴图的原始密度：32×32 / 格） */
    static final int TILE_PX = 32;

    /** 供图口：把图集目录与像素来源分开（Android / PC / 自检各有实现） */
    public interface Sheet {
        /**
         * 按**游戏口径的原始名字**精确查一个区域。
         *
         * @return 没有返回 null
         */
        MschAtlas.Region find(String region);

        /**
         * 取这个区域的像素（ARGB，行优先，长度 = w×h）。
         *
         * @return 取不到返回 null（那一格会退化成色块）
         */
        int[] pixels(MschAtlas.Region region);

        /**
         * 这个方块**占几格**（第 119 轮加的：模组方块的多格尺寸只能从贴图自己问出来）。
         *
         * @return 不知道返回 0（渲染时回落到烘在启动器里的原版表、再回落到 1 格）
         */
        int size(String block);

        /**
         * 这个方块**会不会按朝向转**（第 120 轮加的：模组方块走它 JSON 里的 `type` → 类名表）。
         *
         * @return 不知道返回 false（渲染时再问烘好的原版表）
         */
        boolean rotates(String block);
    }

    private MschSprite() {}

    /**
     * 游戏 `UnlockableContent.loadIcon()` 的 5 级回落（原版 `fullOverride` 全是空串 ⇒ 不用管）。
     *
     * @return 命中的区域；一级都没中返回 null
     */
    public static MschAtlas.Region resolve(Sheet s, String blockName) {
        if (s == null || blockName == null || blockName.isEmpty()) return null;
        String[] chain = {
                "block-" + blockName + "-full",
                blockName + "-full",
                blockName,
                "block-" + blockName,
                blockName + "1",
        };
        for (String cand : chain) {
            MschAtlas.Region r = s.find(cand);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * 渲染（贴图 + 旋转 + 缩放）。
     *
     * @param targetSide 输出最长边大约是它（每格像素 = targetSide / 格数，**上限 32 = 原始密度**）
     * @return 出不了图返回 null
     */
    public static MapPreview.Img render(Msch m, Sheet s, int targetSide) {
        if (m == null || !m.ok || m.tiles.isEmpty() || s == null || targetSide <= 0) return null;
        // ★ 尺寸优先问供图方（模组方块的多格尺寸写在自己的贴图里），问不到才回落到原版表
        MschPreview.Lookup lk = lookupFor(s);
        int[] b = MschPreview.bounds(m, lk);
        if (b == null) return null;
        int tilesW = b[2] - b[0] + 1, tilesH = b[3] - b[1] + 1;
        if (tilesW <= 0 || tilesH <= 0 || tilesW > Msch.MAX_DIM || tilesH > Msch.MAX_DIM) return null;

        // 每格多少像素：最多原始密度 32，最少 1（蓝图越大画得越小，内存因此是有界的）
        int px = Math.max(1, Math.min(TILE_PX, targetSide / Math.max(tilesW, tilesH)));
        int w = tilesW * px, h = tilesH * px;
        int[] out = new int[w * h];
        Arrays.fill(out, MschPreview.EMPTY);

        for (Msch.Tile t : m.tiles) {
            int size = lk.size(t.block);
            if (size <= 0) size = 1;
            int off = -(size - 1) / 2;
            // 方块中心（格坐标 → 输出像素）：见类注释 ②
            int cx = (int) Math.round(((t.x + off + size / 2.0) - b[0]) * px);
            int cy = (int) Math.round(((t.y + off + size / 2.0) - b[1]) * px);

            MschAtlas.Region reg = resolve(s, t.block);
            int[] src = reg == null ? null : s.pixels(reg);
            if (src == null || reg.w <= 0 || reg.h <= 0 || src.length < reg.w * reg.h) {
                // 退化：这一格画成色块（占地 = size × size 格）
                int color = lk.color(t.block);
                fill(out, w, h, cx - size * px / 2, cy - size * px / 2, size * px, size * px,
                        color == 0 ? MschPreview.UNKNOWN : color);
                continue;
            }
            boolean rot = s.rotates(t.block) || MschPreview.rotates(t.block);
            int[] spr = src;
            int sw = reg.w, sh = reg.h;
            if (rot && t.rotation != 0) {
                spr = rotate90(spr, sw, sh, t.rotation & 3);
                if ((t.rotation & 1) != 0) { int tmp = sw; sw = sh; sh = tmp; }   // 转 90/270 后宽高互换
            }
            if (px != TILE_PX) {
                spr = scale(spr, sw, sh, Math.max(1, sw * px / TILE_PX), Math.max(1, sh * px / TILE_PX));
                sw = Math.max(1, sw * px / TILE_PX);
                sh = Math.max(1, sh * px / TILE_PX);
            }
            paste(out, w, h, spr, sw, sh, cx - sw / 2, cy - sh / 2);
        }
        return new MapPreview.Img(w, h, out);
    }

    /** 顺时针 90°×k（图像空间；见类注释 ④） */
    static int[] rotate90(int[] src, int w, int h, int k) {
        int[] cur = src;
        int cw = w, ch = h;
        for (int i = 0; i < (k & 3); i++) {
            int[] dst = new int[cw * ch];
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    // 顺时针：旧的 (x,y) → 新的 (ch-1-y, x)
                    dst[x * ch + (ch - 1 - y)] = cur[y * cw + x];
                }
            }
            cur = dst;
            int t = cw; cw = ch; ch = t;
        }
        return cur;
    }

    /** 缩放到 nw×nh（盒式平均；只在输出比原始密度小时用到） */
    static int[] scale(int[] src, int w, int h, int nw, int nh) {
        if (nw == w && nh == h) return src;
        int[] dst = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int y0 = y * h / nh, y1 = Math.max(y0 + 1, (y + 1) * h / nh);
            for (int x = 0; x < nw; x++) {
                int x0 = x * w / nw, x1 = Math.max(x0 + 1, (x + 1) * w / nw);
                long a = 0, r = 0, g = 0, bl = 0;
                int cnt = 0;
                for (int sy = y0; sy < y1 && sy < h; sy++) {
                    for (int sx = x0; sx < x1 && sx < w; sx++) {
                        int c = src[sy * w + sx];
                        int ca = (c >>> 24) & 0xff;
                        // 按 alpha 加权（贴图边缘是透明的 ⇒ 不能让透明像素把颜色拉黑）
                        a += ca;
                        r += ((c >> 16) & 0xff) * ca;
                        g += ((c >> 8) & 0xff) * ca;
                        bl += (c & 0xff) * ca;
                        cnt++;
                    }
                }
                if (cnt == 0 || a == 0) {
                    dst[y * nw + x] = 0;
                } else {
                    dst[y * nw + x] = ((int) (a / cnt) << 24)
                            | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (bl / a);
                }
            }
        }
        return dst;
    }

    /** 把一张小图**按 alpha 混合**贴到画布上（贴图有透明边 ⇒ 不能直接覆盖） */
    static void paste(int[] dst, int dw, int dh, int[] src, int sw, int sh, int ox, int oy) {
        for (int y = 0; y < sh; y++) {
            int dy = oy + y;
            if (dy < 0 || dy >= dh) continue;
            for (int x = 0; x < sw; x++) {
                int dx = ox + x;
                if (dx < 0 || dx >= dw) continue;
                int c = src[y * sw + x];
                int a = (c >>> 24) & 0xff;
                if (a == 0) continue;
                int i = dy * dw + dx;
                if (a == 255) {
                    dst[i] = c;
                } else {
                    int b = dst[i];
                    int ba = (b >>> 24) & 0xff;
                    int na = a + ba * (255 - a) / 255;
                    dst[i] = (na << 24)
                            | (mix((c >> 16) & 0xff, (b >> 16) & 0xff, a, ba) << 16)
                            | (mix((c >> 8) & 0xff, (b >> 8) & 0xff, a, ba) << 8)
                            | mix(c & 0xff, b & 0xff, a, ba);
                }
            }
        }
    }

    private static int mix(int top, int bottom, int ta, int ba) {
        if (ta == 0) return bottom;
        if (ta == 255) return top;
        int outA = ta + ba * (255 - ta) / 255;
        if (outA == 0) return 0;
        return (top * ta + bottom * ba * (255 - ta) / 255) / outA;
    }

    private static void fill(int[] dst, int dw, int dh, int ox, int oy, int w, int h, int color) {
        for (int y = 0; y < h; y++) {
            int dy = oy + y;
            if (dy < 0 || dy >= dh) continue;
            for (int x = 0; x < w; x++) {
                int dx = ox + x;
                if (dx < 0 || dx >= dw) continue;
                dst[dy * dw + dx] = color;
            }
        }
    }

    /** 给 PC 验收台/自检用：把图集目录包一个 {@link Sheet}（像素来源由调用方给，尺寸问表） */
    public static Sheet sheet(final Map<String, MschAtlas.Region> regions, final PixelSource src) {
        return new Sheet() {
            @Override public MschAtlas.Region find(String region) {
                return regions == null ? null : regions.get(region);
            }

            @Override public int[] pixels(MschAtlas.Region region) {
                return src == null ? null : src.pixels(region);
            }

            @Override public int size(String block) {
                return 0;                     // 问表（原版）；假表/PC 台不需要模组尺寸
            }

            @Override public boolean rotates(String block) {
                return false;                 // 问表（原版）
            }
        };
    }

    /**
     * 把 {@link Sheet} 折成渲染器要的"颜色 + 尺寸"查表：**尺寸优先问供图方**，颜色一律问原版表。
     * （模组方块的颜色表里没有 ⇒ 那一格本来就该是中性紫，见 {@link MschSprite} 类注释）
     */
    static MschPreview.Lookup lookupFor(final Sheet s) {
        return new MschPreview.Lookup() {
            @Override public int color(String internal) {
                return MschPreview.table().color(internal);
            }

            @Override public int size(String internal) {
                int n = s.size(internal);
                return n > 0 ? n : MschPreview.table().size(internal);
            }
        };
    }

    /** 取像素（PC：ImageIO；Android：BitmapRegionDecoder） */
    public interface PixelSource {
        int[] pixels(MschAtlas.Region region);
    }
}
