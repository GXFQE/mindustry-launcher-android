package io.mdt.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * 蓝图预览的**共用底座**：那张生成表「方块名 → 配色 / 占地格数 / 会不会转」+ 包围盒几何。
 *
 * <h3>🔄 第 118 轮的转向（用户原话：「没版本时不用回退（那东西太抽象了毫无意义啊）」）</h3>
 * 第 116 轮这里曾经还有一个**色块渲染器**（`render()`：一格里画一个方块的颜色），
 * 它的唯一用处是给"没分配版本的槽"兜底。用户看过之后明确否掉了 —— 那张马赛克既不像游戏、
 * 也读不出布局 ⇒ **整条色块渲染路径已删除**（连同自检里那几条断言）。
 * 现在的规矩很简单：
 * <pre>
 *   有版本 APK ⇒ {@link MschSprite}（真贴图）
 *   没有 / 图集读不了 ⇒ **没有图**（返回 null，界面保持 GONE —— 与地图页 / 存档页同一条口径）
 * </pre>
 * ⚠️ 但**表本身还得留着**：像素级渲染要靠它拿**占地格数**（{@link #bounds}）与
 *   `rotate/rotateDraw`（{@link #rotates}），而某个方块在图集里查不到时还要用**它的地图配色**
 *   补一个色块（那是**逐格**的兜底，不是整张图的兜底 —— 见 {@link MschSprite#render}）。
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

    /**
     * 这个方块**会不会按朝向转**（表里的 `rotate && rotateDraw`）。
     *
     * ★ 判据来自游戏 `Block.drawDefaultPlanRegion`：
     * `Draw.rect(fullIcon, drawx, drawy, (!rotate || !rotateDraw) ? 0 : plan.rotation * 90)`
     * ⇒ 墙 / 地板 / 核心这类转不得（转了就是错的）。像素级预览靠它（{@link MschSprite}）。
     */
    public static boolean rotates(String internal) {
        int[] r = rows().get(internal);
        return r != null && r[2] != 0 && r[3] != 0;
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
                // 第 4/5 列是 rotate / rotateDraw（第 117 轮加的；老表没有这两列时按"不转"处理）
                int rot = c.length > 3 ? ("1".equals(c[3].trim()) ? 1 : 0) : 0;
                int rotDraw = c.length > 4 ? ("1".equals(c[4].trim()) ? 1 : 0) : 0;
                m.put(c[0], new int[]{argb, size, rot, rotDraw});
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
     * 尺寸：认不出当 1 格（与游戏 `content.getByName() == null` 时的后果一致：那一格按 1 格画）
     */
    private static int size(Lookup lk, String internal) {
        int s = lk.size(internal);
        return s <= 0 ? 1 : s;
    }
}
