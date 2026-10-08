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
            case ENABLED: return Trans.get(c, R.string.mods_state_enabled);
            case CONTENT_ERRORS: return Trans.get(c, R.string.mods_state_content_errors);
            case MISSING_DEPENDENCIES: return Trans.get(c, R.string.mods_state_missing_deps);
            case INCOMPLETE_DEPENDENCIES: return Trans.get(c, R.string.mods_state_incomplete_deps);
            case CIRCULAR_DEPENDENCIES: return Trans.get(c, R.string.mods_state_circular_deps);
            case UNSUPPORTED: return Trans.get(c, R.string.mods_state_unsupported);
            case DISABLED: return Trans.get(c, R.string.mods_state_disabled);
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
            case MISSING_DEPENDENCIES: return Trans.get(c, R.string.mods_badge_missing_deps);
            case INCOMPLETE_DEPENDENCIES: return Trans.get(c, R.string.mods_badge_incomplete_deps);
            case UNSUPPORTED: return Trans.get(c, R.string.mods_badge_unsupported);
            case DISABLED: return Trans.get(c, R.string.mods_badge_disabled);
            default: return stateLabel(c, st);
        }
    }

    /**
     * ★ **这个状态下，徽标是不是已经把话说完了**（2026-10-08 用户真机：「还有现在还是乱的」）。
     *
     * <p>列表行的构成是「标题 + 徽标（短形式）+ 正文（可能含一句「游戏里的状态：…」）」。
     * 当短形式与句子形式**逐字相同**时（如「已关闭」），正文那句就是**同一行里同一个词喊两遍**
     * —— 判据是"徽标说完了没有"，而不是"这是哪个状态"：{@code stateBadge == stateLabel} ⇒ 说完了。
     *
     * <p>⚠️ 反例（必须**保留**正文那句）：`MISSING_DEPENDENCIES` 徽标只写「缺依赖」，
     * 句子形式是「缺少依赖的模组」—— 正文那句才有信息量。
     */
    static boolean badgeSaysIt(Context c, Mods.State st) {
        String b = stateBadge(c, st);
        return !b.isEmpty() && b.equals(stateLabel(c, st));
    }

    /**
     * 模组**说明文件**读不出来时的"人话版"原因（原来在 `Mods.Info.metaReason()` 里）。
     *
     * ★ 为什么搬出来：`Mods.Info` 是纯数据类、拿不到 `Context`，而这两句会经
     *   `mods_warn_meta_fmt` **直接显示在模组详情里** ⇒ 英文界面下会冒中文。
     * ⚠️ 与 `SettingsText.userReason` 同一条纪律：**先看码**（`metaErrCode`，我们自己写的那 8 句
     *   在 `Mods` 里都配了码）；码为 0 时才退回"异常形态"判断；最后才原样透传
     *   （那时 `metaError` 是我们自己的中文诊断，给排查看的）。
     */
    static String infoMetaReason(Context c, Mods.Info m) {
        if (m != null && m.metaErrCode != Mods.Info.M_NONE) {
            switch (m.metaErrCode) {
                case Mods.Info.M_COLON: return Trans.get(c, R.string.mods_meta_colon);
                case Mods.Info.M_NO_META_HERE: return Trans.get(c, R.string.mods_meta_no_meta_here);
                case Mods.Info.M_NO_META_IN_PACK: return Trans.get(c, R.string.mods_meta_no_meta_in_pack);
                case Mods.Info.M_META_TOO_BIG: return Trans.get(c, R.string.mods_meta_too_big);
                case Mods.Info.M_META_UNREADABLE: return Trans.get(c, R.string.mods_meta_unreadable);
                case Mods.Info.M_META_BAD_FORMAT: return Trans.get(c, R.string.mods_meta_bad_format);
                case Mods.Info.M_META_NO_NAME: return Trans.get(c, R.string.mods_meta_no_name);
                case Mods.Info.M_META_BAD_JSON: return Trans.get(c, R.string.mods_meta_bad_json);
                default: break;
            }
        }
        String e = m == null || m.metaError == null ? "" : m.metaError.trim();
        if (e.isEmpty()) return Trans.get(c, R.string.mods_meta_no_file);
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            return Trans.get(c, R.string.mods_meta_bad_format);
        }
        return e;
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
                return Trans.get(c, R.string.pack_err_src_missing_fmt, r.errS1);
            case Mods.PackResult.P_SRC_OPEN:
                return Trans.get(c, R.string.pack_err_src_open);
            case Mods.PackResult.P_NO_MODS_DIR:
                return Trans.get(c, R.string.pack_err_no_mods_dir);
            case Mods.PackResult.P_NAME_EMPTY:
                return Trans.get(c, R.string.pack_err_name_empty);
            case Mods.PackResult.P_NAME_COLON:
                return Trans.get(c, R.string.pack_err_name_colon);
            case Mods.PackResult.P_NAME_DOT:
                return Trans.get(c, R.string.pack_err_name_dot);
            case Mods.PackResult.P_NAME_EXT:
                return Trans.get(c, R.string.pack_err_name_ext);
            case Mods.PackResult.P_MKDIR:
                return Trans.get(c, R.string.pack_err_mkdir_fmt, r.errS1);
            case Mods.PackResult.P_NAME_TAKEN:
                return Trans.get(c, R.string.pack_err_name_taken_fmt, r.errS1);
            case Mods.PackResult.P_NOT_A_MOD:
                return Trans.get(c, R.string.pack_err_not_a_mod);
            case Mods.PackResult.P_IMPORT_FAILED:
                return Trans.get(c, R.string.pack_err_import_failed);
            case Mods.PackResult.P_COPY_NO_SRC:
                return Trans.get(c, R.string.pack_err_copy_no_src);
            case Mods.PackResult.P_COPY_NO_DST:
                return Trans.get(c, R.string.pack_err_copy_no_dst);
            case Mods.PackResult.P_COPY_SAME:
                return Trans.get(c, R.string.pack_err_copy_same);
            case Mods.PackResult.P_COPY_UNREADABLE:
                return Trans.get(c, R.string.pack_err_copy_unreadable);
            case Mods.PackResult.P_COPY_NOTHING:
                return Trans.get(c, R.string.pack_err_copy_nothing);
            default:
                return r.error == null ? "" : r.error;
        }
    }
}
