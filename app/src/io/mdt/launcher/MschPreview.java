package io.mdt.launcher;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 {@link Msch} 解出来的瓦片画成**色块预览图**（蓝图列表缩略图 / 详情大图）。
 *
 * <h3>和地图预览什么关系</h3>
 * <pre>
 *   地图预览（{@link MapPreview}）能成立，是因为 `.msav` 里存的是 **content id**，
 *   而 APK 里那张 `sprites/block_colors.png` 就是**按 id 索引**的；
 *   而 `.msch` 里存的是**方块名字** ⇒ 需要一张「名字 → 颜色 / 占地格数」的表
 *   （{@link BlockTable}，生成配方见那个文件的头注释）。
 *   两张图的口径刻意保持一致（同一套取色、同一套缩略平均），所以蓝图页看起来
 *   和地图页是**同一个东西的两种**（用户 2026-10-06：「让存档和蓝图页面也能像地图一样有预览图片」）。
 * </pre>
 *
 * <h3>几何：照抄游戏自己的预览渲染器</h3>
 * 依据 = `mindustry.game.Schematics#getBuffer`（v160）：
 * <pre>
 *   int size = t.block.size;
 *   int offsetx = -(size - 1) / 2;      // 👈 整数除法（size=2 ⇒ 0，即往右上长）
 *   for(dx = 0..size-1) for(dy = 0..size-1) 格子 = (t.x + dx + offsetx, t.y + dy + offsety)
 * </pre>
 * 也就是说**瓦片坐标是方块的"中心"**（多格方块往外扩 `(size-1)/2`），
 * 与 `Schematics#create` 里算包围盒的那两行（`top = size/2`；奇偶分别取 `-size/2` / `-(size-1)/2`）
 * 逐格等价 —— 自检 ㊽ 拿语料的**声明尺寸**当独立判据把这条钉住。
 *
 * <h3>纯 Java</h3>
 * 本类**不碰 Android**（不引 Bitmap）⇒ 能在 PC 上单独编译、逐像素喂断言；
 * 那一半（Bitmap / 缓存 / 落盘）在 {@link MschLoad}。
 */
public final class MschPreview {
    /** 包围盒里**没有方块**的格子（用很暗的底色，好和"有方块"分开） */
    static final int EMPTY = 0xFF161616;
    /**
     * **查不到配色**的方块（模组方块、或表里没有的名字）画成这个中性紫。
     *
     * ★ 为什么不是"当空格"：说不清楚的地方要**看得出来**（与蓝图页那句「（可能认错）」同一条纪律）。
     * ⚠️ 但它**不等于**「缺件」——真正的缺件判据是内容表（{@link Blueprints#rows}），
     *   这里只是"我们手里没有它的颜色"。
     */
    static final int UNKNOWN = 0xFF7A6A8A;

    /** 查表口（渲染器**只**通过它拿颜色与尺寸 ⇒ 自检可以喂自己的实现） */
    public interface Lookup {
        /** 方块名（`internal`）→ ARGB；**0 = 没有配色 / 认不出** */
        int color(String internal);

        /** 方块名 → 占地格数；**&lt;=0 = 认不出**（当 1 格处理） */
        int size(String internal);
    }

    /** 产品侧那张表（懒解析一次；与 {@link MapStats#VANILLA} 同一个套路） */
    private static Map<String, int[]> ROWS;
    private static final Lookup TABLE = new Lookup() {
        @Override public int color(String internal) {
            int[] r = rows().get(internal);
            return r == null ? 0 : r[0];
        }

        @Override public int size(String internal) {
            int[] r = rows().get(internal);
            return r == null ? 0 : r[1];
        }
    };

    private MschPreview() {}

    /** 产品侧的查表实现（自检要喂假表时**别**用它，自己写一个 {@link Lookup}） */
    public static Lookup table() {
        return TABLE;
    }

    /** 解析 {@link BlockTable#DATA_B64}（**唯一**的解码实现；编解码两端同源见生成器） */
    static synchronized Map<String, int[]> rows() {
        if (ROWS == null) {
            Map<String, int[]> m = new HashMap<>(1024);
            for (String line : MapStats.inflate(BlockTable.DATA_B64).split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] c = line.split("\t", -1);
                if (c.length < 3) continue;
                int argb = 0;
                if (!"-".equals(c[1])) {
                    try {
                        argb = (int) Long.parseLong(c[1].trim(), 16);
                    } catch (Throwable ignored) {
                    }
                }
                int size = 1;
                try {
                    size = Integer.parseInt(c[2].trim());
                } catch (Throwable ignored) {
                }
                m.put(c[0], new int[]{argb, size});
            }
            ROWS = m;
        }
        return ROWS;
    }

    /**
     * 瓦片**包围盒**（含多格方块的尺寸），返回 `{minX, minY, maxX, maxY}`。
     *
     * 🔴 与 {@link Msch#boxWidth} 的差别就在这里：那个只按瓦片**原点**算，
     *   多格方块（3×3 的炮塔）在边缘时会**画出界**。
     *
     * @return 没有瓦片 / 解析失败 ⇒ null
     */
    public static int[] bounds(Msch m, Lookup lk) {
        if (m == null || !m.ok || m.tiles.isEmpty()) return null;
        if (lk == null) lk = TABLE;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Msch.Tile t : m.tiles) {
            int s = size(lk, t.block);
            int off = -(s - 1) / 2;                       // 🔴 与游戏一字不差（整数除法）
            minX = Math.min(minX, t.x + off);
            minY = Math.min(minY, t.y + off);
            maxX = Math.max(maxX, t.x + off + s - 1);
            maxY = Math.max(maxY, t.y + off + s - 1);
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 渲染（并**顺带缩略**：每 scale×scale 格取平均色，与 {@link MapPreview#render} 同一套）。
     *
     * @param maxSide 输出最长边上限
     * @return 出不了图返回 null（界面**保持 GONE**，不留空框 —— 用户 2026-10-04 的明确要求）
     */
    public static MapPreview.Img render(Msch m, Lookup lk, int maxSide) {
        if (m == null || !m.ok || m.tiles.isEmpty() || maxSide <= 0) return null;
        if (lk == null) lk = TABLE;
        int[] b = bounds(m, lk);
        if (b == null) return null;
        int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1;
        // 🔴 上限用**游戏自己的**：`Msch.MAX_DIM` = 128（游戏 maxSchematicSize）。
        //   语料里真有"瓦片原点落在声明尺寸之外"的文件（Msch.outOfBounds），
        //   极端的坐标会让包围盒炸成几万格 ⇒ 这里直接放弃出图，**不去分配**那块内存。
        if (w <= 0 || h <= 0 || w > Msch.MAX_DIM || h > Msch.MAX_DIM) return null;

        int[] full = new int[w * h];
        Arrays.fill(full, EMPTY);
        for (Msch.Tile t : m.tiles) {
            int s = size(lk, t.block);
            int off = -(s - 1) / 2;
            int c = lk.color(t.block);
            if (c == 0) c = UNKNOWN;                      // 认不出 ⇒ 中性紫，不当空格
            int y0 = t.y + off - b[1], x0 = t.x + off - b[0];
            for (int dy = 0; dy < s; dy++) {
                int y = y0 + dy;
                if (y < 0 || y >= h) continue;
                for (int dx = 0; dx < s; dx++) {
                    int x = x0 + dx;
                    if (x < 0 || x >= w) continue;
                    full[y * w + x] = c;
                }
            }
        }
        return shrink(full, w, h, maxSide);
    }

    /** 按平均色缩到 maxSide 之内（`scale == 1` 时原样返回） */
    static MapPreview.Img shrink(int[] full, int w, int h, int maxSide) {
        int scale = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) maxSide));
        int ow = Math.max(1, w / scale), oh = Math.max(1, h / scale);
        if (scale == 1) return new MapPreview.Img(w, h, full);
        int[] out = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int r = 0, g = 0, bl = 0, cnt = 0;
                for (int dy = 0; dy < scale; dy++) {
                    int y = oy * scale + dy;
                    if (y >= h) break;
                    for (int dx = 0; dx < scale; dx++) {
                        int x = ox * scale + dx;
                        if (x >= w) break;
                        int c = full[y * w + x];
                        r += (c >> 16) & 0xff;
                        g += (c >> 8) & 0xff;
                        bl += c & 0xff;
                        cnt++;
                    }
                }
                out[oy * ow + ox] = cnt == 0 ? EMPTY
                        : 0xFF000000 | ((r / cnt) << 16) | ((g / cnt) << 8) | (bl / cnt);
            }
        }
        return new MapPreview.Img(ow, oh, out);
    }

    /** 尺寸：认不出当 1 格（与游戏 `content.getByName() == null` 时的后果一致：那一格按 1 格画） */
    private static int size(Lookup lk, String internal) {
        int s = lk.size(internal);
        return s <= 0 ? 1 : s;
    }
}
