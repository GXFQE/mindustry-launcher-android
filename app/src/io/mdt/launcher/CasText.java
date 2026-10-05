package io.mdt.launcher;

import android.content.Context;

/**
 * {@link Cas} 的**「码 + 参数」→ 文案**（Android 侧）。
 *
 * ★ 为什么要有它：`Cas` 是**纯 Java** 的对象池（不许 import android）⇒ 它只说"错在哪、带哪个参数"，
 *   文案在这里按码取资源。
 *
 * 🔴 它堵住的那条泄漏：`Cas` 抛的 `IOException` 会经 `Backup` 恢复报告的**每文件行**
 *   （`backup_restore_err_line_fmt` 的 `%2$s`）**直接显示给用户** ⇒ 英文界面下会冒中文。
 *   ⇒ `Backup` 那边现在用 {@link #reason(Context, java.io.IOException)} 取文案。
 *
 * ⚠️ 与 `SettingsText` / `ModsText` 同一条纪律：**中性参数**（路径 / 对象地址）可以进第一层，
 *   **原始原因**（异常原文）不进。
 * 🔴 **加一个码就要在这里加一条**：`switch` 有 `default` 兜底 ⇒ 漏映射**不崩不报错**，
 *   只是**静默退回**异常原文（那正是我们要避免的）⇒ 自检会**遍历 `ALL_CODES`** 过一遍。
 */
final class CasText {

    private CasText() {}

    /** 把异常翻成给用户看的一句话：是 `Cas` 的码化异常就查资源，否则原样返回消息 */
    static String reason(Context c, java.io.IOException e) {
        if (e instanceof Cas.CasException) return reason(c, (Cas.CasException) e);
        return e == null || e.getMessage() == null ? String.valueOf(e) : e.getMessage();
    }

    static String reason(Context c, Cas.CasException e) {
        switch (e.code) {
            case Cas.C_OBJ_BUSY: return c.getString(R.string.cas_err_obj_busy_fmt, e.s1);
            case Cas.C_OBJ_PLACE: return c.getString(R.string.cas_err_obj_place_fmt, e.s1);
            case Cas.C_OBJ_MISSING: return c.getString(R.string.cas_err_obj_missing_fmt, e.s1);
            case Cas.C_OBJ_CORRUPT: return c.getString(R.string.cas_err_obj_corrupt_fmt, e.s1);
            case Cas.C_DST_BUSY: return c.getString(R.string.cas_err_dst_busy_fmt, e.s1);
            case Cas.C_RESTORE_WRITE: return c.getString(R.string.cas_err_restore_write_fmt, e.s1);
            case Cas.C_NO_SHA256: return c.getString(R.string.cas_err_no_sha256);
            case Cas.C_MKDIR: return c.getString(R.string.cas_err_mkdir_fmt, e.s1);
            default:
                // 新加了码却忘了在这里加映射 ⇒ 不崩，但会退回异常原文（中文）
                return e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        }
    }
}
