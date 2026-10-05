package io.mdt.launcher;

import android.content.Context;

/**
 * 蓝图（`.msch`）解析失败的**显示文案**（与 {@link MsavText} 同一套路）。
 *
 * 分工：
 * <pre>
 *   Msch      = **纯数据 + 解析**（不认识"蓝图"这两个字，只给「码 + 参数」）
 *   MschText  = 显示文案（读资源，本类）
 * </pre>
 * ★ 为什么要分开：{@link Msch} 刻意不碰 Android（它要能在 PC 上单独编译、拿游戏自己的读取器
 *   逐字段当判据 —— 见 REF §74）⇒ 它只能给码，文案在这里映射。
 * ⚠️ **加一个码就要在这里加一条**：`switch` 有 `default` 兜底（返回"原因不明"），
 *   所以漏掉映射**不崩** —— 正因为不崩，自检里才要**遍历 {@link Msch#ALL_CODES}** 过一遍
 *   （漏映射 = 界面上静默变成"原因不明"）。
 * ★ 文案纪律（用户 2026-10-02 定的三条）：**短、白话、无术语** —— 不写 `zlib` / `inflate` /
 *   `TypeIO`，写"文件内容坏了""解压后太大"。
 */
public final class MschText {

    private MschText() {}

    /** **给用户看**的失败原因（列表行 / 详情页用）；没有码时退回 {@link Msch#error} 原文 */
    public static String reason(Context c, Msch m) {
        if (m != null && m.errCode != Msch.E_NONE) {
            return reason(c, m.errCode, m.errN1, m.errN2);
        }
        String e = m == null || m.error == null ? "" : m.error.trim();
        if (e.isEmpty()) return c.getString(R.string.msav_unknown_reason);
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            return c.getString(R.string.msch_reason_unreadable);
        }
        return e;
    }

    /** 按码取文案（自检遍历 {@link Msch#ALL_CODES} 用的就是它） */
    public static String reason(Context c, int code, long n1, long n2) {
        switch (code) {
            case Msch.E_NULL_FILE:
                return c.getString(R.string.msch_reason_no_file);
            case Msch.E_FILE_TOO_BIG:
                return c.getString(R.string.msch_reason_file_too_big_fmt, n1);
            case Msch.E_HEADER:
                return c.getString(R.string.msch_reason_not_blueprint);
            case Msch.E_VERSION:
                return c.getString(R.string.msch_reason_newer_fmt, n1);
            case Msch.E_ZLIB:
                return c.getString(R.string.msch_reason_broken);
            case Msch.E_INFLATE_LIMIT:
                return c.getString(R.string.msch_reason_huge);
            case Msch.E_TOO_LARGE:
                return c.getString(R.string.msch_reason_too_large_fmt, n1, n2);
            case Msch.E_TOO_MANY:
                return c.getString(R.string.msch_reason_too_many_fmt, n1);
            case Msch.E_TRUNC:
                return c.getString(R.string.msch_reason_half);
            case Msch.E_CONFIG:
                return c.getString(R.string.msch_reason_config);
            case Msch.E_ARRAY:
                return c.getString(R.string.msch_reason_array_fmt, n1);
            case Msch.E_NESTED:
                return c.getString(R.string.msch_reason_nested);
            case Msch.E_TAG:
                return c.getString(R.string.msch_reason_unknown_tag);
            case Msch.E_BODY:
                return c.getString(R.string.msch_reason_broken);
            default:
                return c.getString(R.string.msav_unknown_reason);
        }
    }
}
