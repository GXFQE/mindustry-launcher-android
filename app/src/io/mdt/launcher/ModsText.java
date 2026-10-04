package io.mdt.launcher;

import android.content.Context;

/**
 * {@link Mods} 里那些**"码"到文案**的映射（Android 侧）。
 *
 * ★ 为什么要有这个类：`Mods.State` 原来**枚举自己带显示文案**（`ENABLED("启用","启用")` …），
 *   `Mods.gates` 原来在 Java 里拼中文 ⇒ 英文界面下会冒出中文。枚举常量名本身就是**稳定的码**
 *   （而且与游戏 `ModState` 同名同序，不许改）⇒ 映射放这里，核心只留码。
 *
 * 🔴 **加一个状态就必须在这里加两条资源 + 两条分支**：`switch` 带 `default` 兜底，
 *   所以漏映射**不崩、不报错**，界面只是**静默退化**（徽标空白）。正因为不崩，
 *   自检里才要**遍历 {@link Mods.State#values()}** 过一遍。
 */
final class ModsText {

    private ModsText() {}

    /**
     * 句子形式（详情弹窗 / 报告）—— **别用在徽标上**：徽标挤在标题右边，太长会把标题挤到折行。
     */
    static String stateLabel(Context c, Mods.State st) {
        if (st == null) return "";
        switch (st) {
            case ENABLED: return c.getString(R.string.mods_state_enabled);
            case CONTENT_ERRORS: return c.getString(R.string.mods_state_content_errors);
            case MISSING_DEPENDENCIES: return c.getString(R.string.mods_state_missing_deps);
            case INCOMPLETE_DEPENDENCIES: return c.getString(R.string.mods_state_incomplete_deps);
            case CIRCULAR_DEPENDENCIES: return c.getString(R.string.mods_state_circular_deps);
            case UNSUPPORTED: return c.getString(R.string.mods_state_unsupported);
            case DISABLED: return c.getString(R.string.mods_state_disabled);
            default: return "";
        }
    }

    /**
     * 徽标短形式（列表行）。与 {@link #stateLabel} **不是同一个东西** —— 只有本来就一样短的
     * 那三个状态（启用 / 内容有错 / 循环依赖）才与句子形式共用同一条资源。
     */
    static String stateBadge(Context c, Mods.State st) {
        if (st == null) return "";
        switch (st) {
            case MISSING_DEPENDENCIES: return c.getString(R.string.mods_badge_missing_deps);
            case INCOMPLETE_DEPENDENCIES: return c.getString(R.string.mods_badge_incomplete_deps);
            case UNSUPPORTED: return c.getString(R.string.mods_badge_unsupported);
            case DISABLED: return c.getString(R.string.mods_badge_disabled);
            default: return stateLabel(c, st);
        }
    }
}
