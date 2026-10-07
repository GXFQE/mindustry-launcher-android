package io.mdt.launcher;

import android.content.Context;

/**
 * {@link SettingsBin.Result} 的**「码 + 参数」→ 文案**（Android 侧）。
 *
 * ★ 为什么要有这个类：`SettingsBin` 是**刻意纯 Java** 的（能在 PC 上单独编译验证，见它的类注释）
 *   ⇒ 不能 `getString`，只说"错在哪、带哪些参数"；文案在这里按码取资源。
 *   ⚠️ 与 {@link MsavText#userReason} **同一套路**（那边是存档，这边是 settings.bin）——
 *     两边都别把核心的中文原样端给用户。
 *
 * 🔴 **加一个码就要在这里加一条**：`switch` 有 `default` 兜底（退回原来的"异常形态"判断），
 *   所以漏掉映射**不崩、不报错**，界面上只是**静默退化**。正因为不崩，
 *   自检里才要**遍历 {@link SettingsBin.Result#ALL_CODES}** 过一遍。
 *
 * ⚠️ 不改 {@link SettingsBin.Result#error} 原文：`report()`（「技术细节」第二层）、dev 落盘报告
 *   与自检看的仍是那个字段（排查要它）。
 */
final class SettingsText {

    private SettingsText() {}

    /** 失败原因（**第一层**给用户看的那句） */
    static String userReason(Context c, SettingsBin.Result r) {
        if (r == null) return Trans.get(c, R.string.settings_reason_unknown);
        switch (r.errCode) {
            case SettingsBin.Result.E_NULL_FILE:
                return Trans.get(c, R.string.settings_err_null_file);
            case SettingsBin.Result.E_NO_KEYS:
                return Trans.get(c, R.string.settings_err_no_keys);
            case SettingsBin.Result.E_BACKUP_MKDIR:
                return Trans.get(c, R.string.settings_err_backup_mkdir_fmt, r.errS1);
            case SettingsBin.Result.E_BACKUP_VERIFY:
                return Trans.get(c, R.string.settings_err_backup_verify);
            case SettingsBin.Result.E_VERIFY_ROLLBACK:
                return Trans.get(c, R.string.settings_err_verify_rollback_fmt, detail(c, r));
            case SettingsBin.Result.E_VERIFY_ROLLBACK_FAIL:
                return Trans.get(c, R.string.settings_err_verify_rollback_fail_fmt, detail(c, r));
            case SettingsBin.Result.E_VERIFY_DELETED:
                return Trans.get(c, R.string.settings_err_verify_deleted_fmt, detail(c, r));
            case SettingsBin.Result.E_NO_SLOT_DIR:
                return Trans.get(c, R.string.settings_err_no_slot_dir_fmt, r.errS1);
            case SettingsBin.Result.E_GAME_RUNNING:
                return Trans.get(c, R.string.settings_err_game_running);
            case SettingsBin.Result.E_SETTINGS_UNREADABLE:
                return Trans.get(c, R.string.settings_err_settings_unreadable);
            case SettingsBin.Result.E_NO_MODS:
                return Trans.get(c, R.string.settings_err_no_mods);
            case SettingsBin.Result.E_NO_INTERNAL_NAME:
                return Trans.get(c, R.string.settings_err_no_internal_name);
            default:
                break;
        }
        // 不是我们写的码 ⇒ 退回"异常形态"判断：`类名: 消息` 对用户是天书
        String e = r.error == null ? "" : r.error.trim();
        if (e.isEmpty()) return Trans.get(c, R.string.settings_reason_unknown);
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            return Trans.get(c, R.string.settings_reason_read_write_error);
        }
        return e;
    }

    /** 写后自检不过的**具体原因**（嵌在上面三条的 `%1$s` 里，所以它也得是整句资源） */
    private static String detail(Context c, SettingsBin.Result r) {
        if (r.subCode == SettingsBin.Result.SUB_UNREADABLE) {
            return Trans.get(c, R.string.settings_selfcheck_unreadable);
        }
        return Trans.get(c, R.string.settings_selfcheck_mismatch);
    }
}
