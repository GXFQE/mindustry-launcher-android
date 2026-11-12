package io.mdt.launcher;

import android.content.Context;

/**
 * 补丁的**文案映射**：错误码 → 人话（补丁机制 v1，2026-11-10）。
 *
 * <p>为什么单独一个类（与 `CasText` / `SettingsText` / `ModsText` 同一个理由）：
 * 码在数据层、话在界面层，两边只有这一处对应关系；自检可以拿 {@link #ALL_CODES}
 * 逐一断言"每个码都有人话"（漏一个码的症状是弹窗里出现英文码或只有异常类名 ——
 * 那正是 §78 禁止的"原始诊断进第一层"）。
 *
 * <p>⚠️ 未知码不许静默 return null：退回一句通用的 + 把码带进定位（原始诊断留在那一层）。
 */
final class PatchText {

    /** 数据层可能抛出的全部码（自检的元断言直接遍历这一份）。 */
    static final String[] ALL_CODES = {
            PatchException.C_BAD_ZIP,
            PatchException.C_BAD_JSON,
            PatchException.C_SCHEMA,
            PatchException.C_FIELD,
            PatchException.C_NO_TARGETS,
            PatchException.C_BAD_HASH,
            PatchException.C_BAD_PATH,
            PatchException.C_MISSING_ENTRY,
            PatchException.C_BAD_DEX,
            PatchException.C_BAD_ASSETS,
            PatchException.C_DUP,
            PatchException.C_IO,
            PatchException.C_TOO_BIG,
            PatchException.C_NO_MATCH,
            PatchException.C_CONFLICT,
            PatchException.C_GAME_ALIVE,
            PatchException.C_MISSING,
            PatchException.C_ENABLED,
            PatchException.C_IN_USE,
            PatchException.C_RECORD_GONE,
            PatchException.C_CONFLICT_LAUNCH,
            PatchException.C_CRASH_DISABLED,   // 崩溃后自动停用（第 360 轮）
    };

    private PatchText() {}

    static String reason(Context c, PatchException e) {
        if (e == null) return Trans.get(c, R.string.patch_err_io_fmt, "?");
        return reason(c, e.code, e.detail);
    }

    /** 一个码 + 定位 → 用户看得懂的一句。 */
    static String reason(Context c, String code, String detail) {
        String d = detail == null || detail.isEmpty() ? "-" : detail;
        if (PatchException.C_BAD_ZIP.equals(code)) return Trans.get(c, R.string.patch_err_bad_zip);
        if (PatchException.C_BAD_JSON.equals(code)) return Trans.get(c, R.string.patch_err_json);
        if (PatchException.C_SCHEMA.equals(code)) return Trans.get(c, R.string.patch_err_schema_fmt, d);
        if (PatchException.C_FIELD.equals(code)) return Trans.get(c, R.string.patch_err_field_fmt, d);
        if (PatchException.C_NO_TARGETS.equals(code)) return Trans.get(c, R.string.patch_err_targets);
        if (PatchException.C_BAD_HASH.equals(code)) return Trans.get(c, R.string.patch_err_hash_fmt, d);
        if (PatchException.C_BAD_PATH.equals(code)) return Trans.get(c, R.string.patch_err_path_fmt, d);
        if (PatchException.C_MISSING_ENTRY.equals(code)) return Trans.get(c, R.string.patch_err_entry_fmt, d);
        if (PatchException.C_BAD_DEX.equals(code)) return Trans.get(c, R.string.patch_err_dex_fmt, d);
        if (PatchException.C_BAD_ASSETS.equals(code)) return Trans.get(c, R.string.patch_err_assets_fmt, d);
        if (PatchException.C_DUP.equals(code)) return Trans.get(c, R.string.patch_err_dup_fmt, d);
        if (PatchException.C_IO.equals(code)) return Trans.get(c, R.string.patch_err_io_fmt, d);
        if (PatchException.C_TOO_BIG.equals(code)) return Trans.get(c, R.string.patch_err_too_big_fmt, d);
        if (PatchException.C_NO_MATCH.equals(code)) return Trans.get(c, R.string.patch_err_no_match_fmt, d);
        if (PatchException.C_CONFLICT.equals(code)) return Trans.get(c, R.string.patch_err_conflict_fmt, d);
        if (PatchException.C_GAME_ALIVE.equals(code)) return Trans.get(c, R.string.patch_game_running);
        if (PatchException.C_MISSING.equals(code)) return Trans.get(c, R.string.patch_err_missing_fmt, d);
        if (PatchException.C_ENABLED.equals(code)) return Trans.get(c, R.string.patch_err_enabled);
        if (PatchException.C_IN_USE.equals(code)) return Trans.get(c, R.string.patch_err_in_use_fmt, d);
        if (PatchException.C_RECORD_GONE.equals(code)) return Trans.get(c, R.string.patch_err_record_gone_fmt, d);
        if (PatchException.C_CONFLICT_LAUNCH.equals(code)) {
            return Trans.get(c, R.string.patch_err_conflict_fmt, d);
        }
        if (PatchException.C_CRASH_DISABLED.equals(code)) {
            return Trans.get(c, R.string.patch_err_crash_disabled_fmt, d);
        }
        return Trans.get(c, R.string.patch_err_io_fmt, d);
    }

    /** 启动报告里的一条"没应用" → 人话。 */
    static String problem(Context c, Patches.Problem p) {
        if (p == null) return "";
        return reason(c, p.code, p.detail);
    }

    /** 列表行的状态词（已启用 / 未启用 / 不适用 / 文件缺失）。 */
    static String state(Context c, int state) {
        if (state == PatchesActivity.STATE_ON) return Trans.get(c, R.string.patch_state_on);
        if (state == PatchesActivity.STATE_NA) return Trans.get(c, R.string.patch_state_na);
        if (state == PatchesActivity.STATE_BROKEN) return Trans.get(c, R.string.patch_state_broken);
        return Trans.get(c, R.string.patch_state_off);
    }
}
