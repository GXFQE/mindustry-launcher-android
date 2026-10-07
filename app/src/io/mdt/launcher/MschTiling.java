package io.mdt.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * **autotiler 的"形状 0"**（第 121 轮 A 项）—— 也就是**游戏自己的蓝图预览会画的那张图**。
 *
 * <h3>🔴 先说结论：预览路径里根本没有"邻居融合"</h3>
 * 我们本来打算把 `Autotiler.getTiling` + 六套 `blends(...)` 全搬过来（那是在世界里放置时的行为），
 * 但**对照台把这条路否掉了**，证据两条：
 * <ol>
 *   <li><b>字节码</b>：`mindustry.input.InputHandler$QueryEachable.each(Cons)` 的实现就是 <b>`return;`</b>
 *       —— 空方法。而 `Schematics.getBuffer` 传给 `drawPlanRegion` 的正是
 *       `new QueryEachable(null, plans)`（tree 为 null）⇒ `Block.findPlan(list, …)` **一个邻居都找不到**。</li>
 *   <li><b>实测</b>：PC 上直接跑游戏自己的 `getTiling`（`.tmp-prev-lab/TilingOracle.java`），
 *       对 14 份真蓝图里**所有**走 autotiler 的方块（`conveyor` / `titanium-conveyor` / `conduit` /
 *       `pulse-conduit` / `plated-conduit` …）逐块导出 `bits` —— **`bits[0]` 一律是 0**、
 *       `bits[1]=bits[2]=1`、掩码 0。</li>
 * </ol>
 * ⇒ 在预览里"融合形状"**恒等于形状 0**（`num = -1` ⇒ `transformCase` 不动 ⇒ `bits[0] = 0`）。
 * 我第一版按世界内行为写了完整的邻居逻辑，**对照台当场判死**（7/7 不同）——
 * 这也正是"判据红线：别把看着像当通过"的价值。（那条世界内逻辑没白写：它记在 REF §82.2 里，
 * 哪天要做"世界里"的渲染可以直接用。）
 *
 * <h3>那"形状 0"画的是哪张图（逐字抄 `@Load`）</h3>
 * <pre>
 *   Conveyor / ArmoredConveyor   {@code @Load("@-#1-#2", lengths={7,4})}  ⇒ 画 {@code regions[0][0]}
 *                                                                        = `&lt;名字&gt;-0-0`（一张）
 *   Duct / ArmoredDuct           top: {@code @-top-#}    (length 5)
 *                                bot: {@code @-bottom-#} (length 5, fallback `duct-bottom-#`)
 *                                                                        ⇒ `&lt;名字&gt;-bottom-0` + `&lt;名字&gt;-top-0`（**两层**）
 *   Conduit / ArmoredConduit      同上（fallback `conduit-bottom-#`）
 *   StackConveyor                {@code @Load("@-#", length=3)} ⇒ 画 {@code regions[0]} = `&lt;名字&gt;-0`
 *                                （它还会按 `bits[3]` 掩码画四个边的装饰；预览里掩码恒为 0 ⇒ **四个边全画**，
 *                                  本类**没做**这一步，如实记在 §82.3）
 * </pre>
 *
 * <p>⚠️ 于是本类**不再需要任何邻居/属性查表**（`BlendTable` 只用来判断"这个方块是不是 tiling 家族"）。
 */
public final class MschTiling {
    /** 家族号（与 {@link BlendTable} 的列一致） */
    public static final int NONE = 0, CONVEYOR = 1, ARMORED_CONVEYOR = 2, DUCT = 3, ARMORED_DUCT = 4,
            CONDUIT = 5, ARMORED_CONDUIT = 6, STACK_CONVEYOR = 7;

    /** 懒解析 {@link BlendTable}（只取家族号那一列；与 {@link MschPreview#rows} 同一套） */
    private static Map<String, Integer> FAM;

    private static synchronized Map<String, Integer> fams() {
        if (FAM == null) {
            Map<String, Integer> m = new HashMap<>(1024);
            for (String line : MapStats.inflate(BlendTable.DATA_B64).split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] c = line.split("\t", -1);
                if (c.length < 9) continue;
                try {
                    m.put(c[0], Integer.parseInt(c[8].trim()));
                } catch (Throwable ignored) {
                }
            }
            FAM = m;
        }
        return FAM;
    }

    private MschTiling() {}

    /** 这个方块走不走 autotiler（家族 1~7；`9` = 没搬判据的其它 Autotiler，按不走处理） */
    public static int family(String block) {
        Integer f = fams().get(block);
        return f == null ? NONE : f;
    }

    /**
     * 形状 0 要**按顺序贴**的区域名（1 层或 2 层）。
     *
     * @return 不走 autotiler ⇒ null；否则是"先贴哪个、后贴哪个"
     */
    public static String[] shape0Regions(String block) {
        switch (family(block)) {
            case CONVEYOR:
            case ARMORED_CONVEYOR:
                return new String[]{block + "-0-0"};
            case DUCT:
            case ARMORED_DUCT:
                return new String[]{block + "-bottom-0", block + "-top-0"};
            case CONDUIT:
            case ARMORED_CONDUIT:
                return new String[]{block + "-bottom-0", block + "-top-0"};
            case STACK_CONVEYOR:
                return new String[]{block + "-0"};
            default:
                return null;
        }
    }

    /**
     * 兜底区域名（`@Load` 的 `fallback`）：底层贴图在原版里是**共享**的
     * （`botRegions` 的 fallback 是 `duct-bottom-#` / `conduit-bottom-#`）——
     * 模组只画顶层时靠它。
     */
    public static String[] shape0Fallbacks(String block) {
        switch (family(block)) {
            case DUCT:
            case ARMORED_DUCT:
                return new String[]{"duct-bottom-0", null};
            case CONDUIT:
            case ARMORED_CONDUIT:
                return new String[]{"conduit-bottom-0", null};
            default:
                return null;
        }
    }
}
