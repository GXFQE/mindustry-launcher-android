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

    /**
     * 模组包**导入 / 跨槽复制**的失败原因（`Mods.PackResult`）。
     *
     * 🔴 与 `SettingsText.userReason` 同一条纪律：**第一层不许夹带"原因"**
     *   （异常原文 / `metaReason` 诊断）；**中性参数**（路径 / 文件名）可以进。
     * ⚠️ 码为 0 时退回 `error` 原文（那是给维护者的诊断）。
     */
    static String packReason(Context c, Mods.PackResult r) {
        if (r == null) return "";
        switch (r.errCode) {
            case Mods.PackResult.P_SRC_MISSING:
                return c.getString(R.string.pack_err_src_missing_fmt, r.errS1);
            case Mods.PackResult.P_SRC_OPEN:
                return c.getString(R.string.pack_err_src_open);
            case Mods.PackResult.P_NO_MODS_DIR:
                return c.getString(R.string.pack_err_no_mods_dir);
            case Mods.PackResult.P_NAME_EMPTY:
                return c.getString(R.string.pack_err_name_empty);
            case Mods.PackResult.P_NAME_COLON:
                return c.getString(R.string.pack_err_name_colon);
            case Mods.PackResult.P_NAME_DOT:
                return c.getString(R.string.pack_err_name_dot);
            case Mods.PackResult.P_NAME_EXT:
                return c.getString(R.string.pack_err_name_ext);
            case Mods.PackResult.P_MKDIR:
                return c.getString(R.string.pack_err_mkdir_fmt, r.errS1);
            case Mods.PackResult.P_NAME_TAKEN:
                return c.getString(R.string.pack_err_name_taken_fmt, r.errS1);
            case Mods.PackResult.P_NOT_A_MOD:
                return c.getString(R.string.pack_err_not_a_mod);
            case Mods.PackResult.P_IMPORT_FAILED:
                return c.getString(R.string.pack_err_import_failed);
            case Mods.PackResult.P_COPY_NO_SRC:
                return c.getString(R.string.pack_err_copy_no_src);
            case Mods.PackResult.P_COPY_NO_DST:
                return c.getString(R.string.pack_err_copy_no_dst);
            case Mods.PackResult.P_COPY_SAME:
                return c.getString(R.string.pack_err_copy_same);
            case Mods.PackResult.P_COPY_UNREADABLE:
                return c.getString(R.string.pack_err_copy_unreadable);
            case Mods.PackResult.P_COPY_NOTHING:
                return c.getString(R.string.pack_err_copy_nothing);
            default:
                return r.error == null ? "" : r.error;
        }
    }
}
