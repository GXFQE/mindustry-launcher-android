package io.mdt.launcher;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * `arc.util.Strings.stripColors` 的等价实现（`Strings.java:191~215` + `parseColorMarkup:229`）。
 *
 * <p>为什么非要有它：`internalName` 是 **settings 键的一部分**，而游戏是在
 * **去色之后**算 `internalName` 的（`Mods.java:1428` 先 `stripColors(name)`，
 * `:1435` 再算 internalName）⇒ 名字里带 `[red]` 的模组，游戏那边的键是
 * `mod-terraform-enabled`，我们若不去色就会去找 `mod-[red]terraform-enabled`
 * ⇒ **永远读成"默认启用"**（一个没有任何症状的错）。
 *
 * <p>★ 判定规则（照抄 arc）：`[...]` 里必须是**已知颜色名**、或 `#RRGGBB[AA]`（2~9 位十六进制）、
 * 或空（`[]` 弹栈）、或 `[` 本身（`[[` 是转义）；否则那个 `[` **原样保留**。
 * 颜色名表 = arc 预定义名（大写原文 + 小写去下划线两份，`Colors.java:53~103`）
 * ＋ Mindustry 自己注册的 5 个（`UI.java:102~106`）。
 * ⚠️ 这张表是**能不能识别**的判据，不是颜色值 ⇒ 只存名字。
 *
 * <p>★ 2026-10-04 从 {@link Mods} **原样搬出来**（`Mods.stripColors` 现在只是转发）：
 * 这样"取名字/译文"那一层（{@link BundleNames}）不再依赖任何 Android 类，
 * 能在 PC 上**用产品代码**跑验收（`NameAudit` / `StatsOne`）。搬的时候一字未改。
 */
final class Colors {

    private Colors() {}

    /** 去掉颜色标记；`null` 进 `null` 出（与 arc 一致） */
    static String strip(String str) {
        if (str == null) return null;
        StringBuilder out = new StringBuilder(str.length());
        int i = 0;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c == '[') {
                int length = colorMarkupLength(str, i + 1);
                if (length >= 0) {
                    i += length + 2;        // 连同两侧括号一起跳过
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** arc `parseColorMarkup` 的值语义：返回**括号内长度**（`-2` = `[[` 转义），`-1` = 不是颜色标记 */
    private static int colorMarkupLength(String str, int start) {
        int end = str.length();
        if (start >= end) return -1;
        switch (str.charAt(start)) {
            case '#': {
                for (int i = start + 1; i < end; i++) {
                    char ch = str.charAt(i);
                    if (ch == ']') {
                        if (i < start + 2 || i > start + 9) break;   // 十六进制位数不合法
                        return i - start;
                    }
                    boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                            || (ch >= 'A' && ch <= 'F');
                    if (!hex) break;
                }
                return -1;
            }
            case '[': return -2;
            case ']': return 0;
            default: break;
        }
        for (int i = start + 1; i < end; i++) {
            if (str.charAt(i) != ']') continue;
            return isKnownColor(str.substring(start, i)) ? (i - start) : -1;
        }
        return -1;
    }

    /** arc 预定义颜色名 + Mindustry 追加的 5 个（见 {@link #strip}） */
    private static boolean isKnownColor(String name) {
        return COLOR_NAMES.contains(name);
    }

    private static final Set<String> COLOR_NAMES = new LinkedHashSet<String>();

    static {
        // Colors.java:53~99 的**原文**（大写 + 下划线）
        String[] base = {
                "CLEAR", "BLACK", "WHITE", "LIGHT_GRAY", "GRAY", "DARK_GRAY",
                "LIGHT_GREY", "GREY", "DARK_GREY", "BLUE", "NAVY", "ROYAL", "SLATE", "SKY",
                "CYAN", "TEAL", "GREEN", "ACID", "LIME", "FOREST", "OLIVE", "YELLOW", "GOLD",
                "GOLDENROD", "ORANGE", "BROWN", "TAN", "BRICK", "RED", "SCARLET", "CRIMSON",
                "CORAL", "SALMON", "PINK", "MAGENTA", "PURPLE", "VIOLET", "MAROON",
        };
        for (String n : base) {
            COLOR_NAMES.add(n);
            // Colors.java:103 —— 追加小写、去掉下划线的变体（注意：**只**这两个变体）
            COLOR_NAMES.add(n.toLowerCase(Locale.ROOT).replace("_", ""));
        }
        // UI.java:102~106 —— Mindustry 自己注册的（本来就是小写、无下划线）
        String[] extra = {"accent", "unlaunched", "highlight", "stat", "negstat"};
        for (String n : extra) COLOR_NAMES.add(n);
    }
}
