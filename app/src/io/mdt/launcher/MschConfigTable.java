package io.mdt.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * 旧格式蓝图（`ver 0`）的 `mapConfig` **分支表**（自动生成，勿手改）。
 *
 * 生成器：`_lab/msch/GenConfigTable.java`（跑在 **Mindustry-160.4.jar** 上，
 * 逐字照抄 `Schematics.mapConfig` 的四条 `instanceof`）。
 *
 * ★ 为什么需要它：游戏判的是 **Java 类**（`block instanceof Sorter || …`），
 *   而我们这边只有文件里的**方块名字** ⇒ 把那次判定在 PC 上跑一遍、把结果存成名字表。
 *
 * 🔴 原版 160.4 只有 **11 个**方块落在这四条分支里（别照抄别处的"看起来像"名单：
 *   `duct-router` / `directional-unloader` / `unit-cargo-unload-point` / `landing-pad`
 *   在 master 里的父类是 `Block` 本身，**不是** `Sorter`/`Unloader`/`ItemSource`，
 *   所以它们的 ver-0 配置在游戏里就是 `null`）。
 *
 * ⚠️ 认不出的名字一律 {@link #NONE}（游戏也是 `return null`）—— **别猜**。
 * ⚠️ 生成于 2026-10-05；换游戏版本要重跑生成器（ver 0 只出现在 v104 那一代，
 *   而那一代没有模组方块，所以这张表够用）。
 */
final class MschConfigTable {
    private MschConfigTable() {}

    /** 不属于任何分支（游戏 `return null`） */
    static final int NONE = 0;
    /** → `content.item(value)`：item 类型的 Content（ContentType 序号 0） */
    static final int ITEM = 1;
    /** → `content.liquid(value)`：liquid 类型的 Content（序号 4） */
    static final int LIQUID = 2;
    /** → `Point2.unpack(value).sub(瓦片坐标)` */
    static final int POINT2 = 3;
    /** → 就是那个 int（`LightBlock`） */
    static final int INT = 4;

    /** 名字（逗号分隔，**生成器原样输出**） */
    private static final String ITEM_NAMES = "sorter,inverted-sorter,unloader,item-source";
    private static final String LIQUID_NAMES = "liquid-source";
    private static final String POINT2_NAMES = "bridge-conveyor,phase-conveyor,mass-driver,bridge-conduit,phase-conduit";
    private static final String INT_NAMES = "illuminator";

    private static Map<String, Integer> TABLE;

    private static synchronized Map<String, Integer> table() {
        if (TABLE == null) {
            Map<String, Integer> m = new HashMap<>();
            put(m, ITEM_NAMES, ITEM);
            put(m, LIQUID_NAMES, LIQUID);
            put(m, POINT2_NAMES, POINT2);
            put(m, INT_NAMES, INT);
            TABLE = m;
        }
        return TABLE;
    }

    private static void put(Map<String, Integer> m, String names, int kind) {
        if (names.isEmpty()) return;
        for (String n : names.split(",")) {
            n = n.trim();
            if (!n.isEmpty()) m.put(n, kind);
        }
    }

    /** 方块名 ⇒ 分支（{@link #NONE} = 不是这四条里的任何一个） */
    static int kindOf(String blockName) {
        if (blockName == null) return NONE;
        Integer k = table().get(blockName);
        return k == null ? NONE : k;
    }

    /** 这张表总共几条（自检钉住条数，防"顺手改两条"） */
    static int size() {
        return table().size();
    }
}
