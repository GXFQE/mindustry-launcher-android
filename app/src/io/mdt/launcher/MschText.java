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
        if (e.isEmpty()) return Trans.get(c, R.string.msav_unknown_reason);
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            return Trans.get(c, R.string.msch_reason_unreadable);
        }
        return e;
    }

    /** 按码取文案（自检遍历 {@link Msch#ALL_CODES} 用的就是它） */
    public static String reason(Context c, int code, long n1, long n2) {
        switch (code) {
            case Msch.E_NULL_FILE:
                return Trans.get(c, R.string.msch_reason_no_file);
            case Msch.E_FILE_TOO_BIG:
                return Trans.get(c, R.string.msch_reason_file_too_big_fmt, n1);
            case Msch.E_HEADER:
                return Trans.get(c, R.string.msch_reason_not_blueprint);
            case Msch.E_VERSION:
                return Trans.get(c, R.string.msch_reason_newer_fmt, n1);
            case Msch.E_ZLIB:
                return Trans.get(c, R.string.msch_reason_broken);
            case Msch.E_INFLATE_LIMIT:
                return Trans.get(c, R.string.msch_reason_huge);
            case Msch.E_TOO_LARGE:
                return Trans.get(c, R.string.msch_reason_too_large_fmt, n1, n2);
            case Msch.E_TOO_MANY:
                return Trans.get(c, R.string.msch_reason_too_many_fmt, n1);
            case Msch.E_TRUNC:
                return Trans.get(c, R.string.msch_reason_half);
            case Msch.E_CONFIG:
                return Trans.get(c, R.string.msch_reason_config);
            case Msch.E_ARRAY:
                return Trans.get(c, R.string.msch_reason_array_fmt, n1);
            case Msch.E_NESTED:
                return Trans.get(c, R.string.msch_reason_nested);
            case Msch.E_TAG:
                return Trans.get(c, R.string.msch_reason_unknown_tag);
            case Msch.E_BODY:
                return Trans.get(c, R.string.msch_reason_broken);
            default:
                return Trans.get(c, R.string.msav_unknown_reason);
        }
    }

    // ── 技术细节里那几行"解析出来但一直没显示"的事实（一档⑥，2026-10-06）──────────
    //
    // ★ 为什么放在这里而不是详情页里就地 getString：界面与自检必须走**同一份格式化**
    //   （第 112 轮那次真机崩溃就是因为自检自己挑参数、而调用点传的是另一种类型 —— 见 §77.6）。
    // ★ 三行都是"加分项"：内容为空就**返回空串**，调用方据此**整行不出现**（不摆空行）。

    /** 名字表里写着的方块名（最多列 8 个，其余报个数）。表是空的 ⇒ 空串 */
    public static String techDictNames(Context c, java.util.List<String> dict) {
        if (dict == null || dict.isEmpty()) return "";
        final int cap = 8;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(cap, dict.size()); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(dict.get(i));
        }
        return dict.size() > cap
                ? Trans.get(c, R.string.bp_tech_dict_names_more_fmt, sb.toString(), dict.size() - cap)
                : Trans.get(c, R.string.bp_tech_dict_names_fmt, sb.toString());
    }

    /** 文件里写旧名、被我们换算过的方块（最多 6 条）。没有 ⇒ 空串 */
    public static String techRemapped(Context c, java.util.List<String> pairs) {
        if (pairs == null || pairs.isEmpty()) return "";
        final int cap = 6;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(cap, pairs.size()); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(pairs.get(i));
        }
        return Trans.get(c, R.string.bp_tech_renamed_fmt, sb.toString());
    }

    /** 带朝向的方块格数（0 ⇒ 空串：一行"0 格"没有信息量） */
    public static String techRotated(Context c, int rotated) {
        return rotated <= 0 ? "" : Trans.get(c, R.string.bp_tech_rot_fmt, MapStatsMods.num(rotated));
    }
}
