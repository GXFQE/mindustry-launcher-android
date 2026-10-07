package io.mdt.launcher;

import android.content.Context;

/**
 * 中转站的**显示文案**（P3 之后新增的类，2026-10-05）。
 *
 * ★ 分工与 {@link MsavText} / {@link ModsText} 一模一样：
 * <pre>
 *   Trash     = 纯逻辑 + 「码 + 中性参数」（**一条中文都不写**，好在自检里逐项喂断言）
 *   TrashText = 文案（读资源，本类）
 * </pre>
 * 为什么不让 {@link Trash} 直接 `getString`：它是"能在自检里单独编译/单独跑"的那一层，
 * 而且工程的门禁 `SRC-02` 对 Java 里的中文字面量**只许降**（新文件加中文就要动台账）。
 *
 * ★ 列表行**不放术语**（用户 2026-10-02 定的文案三条：无 markdown / 短 / 少术语）：
 *   这里只出现"地图 / 模组 / 来自槽「x」/ 体积 / 时间"这几样用户自己就认识的东西。
 */
public final class TrashText {

    private TrashText() {}

    /** 这是什么：地图 / 模组 / 存档 / 整个槽 / 蓝图 / 其他文件 */
    static String kindLabel(Context c, Trash.Item it) {
        if (it == null) return "";
        switch (it.kind) {
            case MAP: return Trans.get(c, R.string.trash_kind_map);
            case MOD: return Trans.get(c, R.string.trash_kind_mod);
            case SAVE: return Trans.get(c, R.string.trash_kind_save);
            case SLOT: return Trans.get(c, R.string.trash_kind_slot);
            case SCHEM: return Trans.get(c, R.string.trash_kind_schem);
            default: return Trans.get(c, R.string.trash_kind_other);
        }
    }

    /**
     * 列表标题 / 弹窗标题用的名字。
     * ★ 整槽特殊：文件名里带的是槽目录名（`slot-default`），而用户认识的是**槽名**（`default`）
     *    —— 这里统一由文案层决定，数据层不去猜"要不要剥前缀"。
     */
    public static String title(Trash.Item it) {
        if (it == null) return "";
        if (it.kind == Trash.Kind.SLOT) return it.slot.isEmpty() ? it.name : it.slot;
        return it.name;
    }

    /**
     * 来源槽。旧格式的名字里**没有**槽（那批是 2026-10-05 之前挪进去的）⇒ 如实说"来源不清楚"，
     * 不猜、也不留空（留空会让这一行看起来像排版坏了）。
     */
    static String fromLabel(Context c, Trash.Item it) {
        String s = it == null || it.slot == null ? "" : it.slot.trim();
        if (s.isEmpty()) return Trans.get(c, R.string.trash_source_unknown);
        return Trans.get(c, R.string.trash_from_slot_fmt, s);
    }

    /**
     * 一行摘要（列表副标题 + 动作弹窗正文的第一行）：
     * `地图 · 来自槽「default」 · 586 KB · 10-04 15:30`。
     *
     * ⚠️ 时间由 {@link Trash#timeText} 给（今年内只写月-日），与存档列表同一个口径 ——
     *   两处能直接对比着看。
     */
    public static String line(Context c, Trash.Item it) {
        if (it == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(kindLabel(c, it));
        // ★ 整槽不写"来自槽「x」"：标题**就是**那个槽名（写两遍是废话，还会把这一行挤长）
        if (it.kind != Trash.Kind.SLOT) sb.append(" · ").append(fromLabel(c, it));
        sb.append(" · ").append(Util.formatSize(it.bytes));
        if (it.kind == Trash.Kind.SLOT && it.files > 0) {
            sb.append(" · ").append(Trans.get(c, R.string.trash_files_fmt, it.files));
        }
        String t = Trash.timeText(it.stamp);
        if (!t.isEmpty()) sb.append(" · ").append(t);
        return sb.toString();
    }

    /** 表头：`共 3 份 · 1.2 MB` */
    public static String head(Context c, int count, long bytes) {
        return Trans.get(c, R.string.trash_head_fmt, count, Util.formatSize(bytes));
    }

    /**
     * **给用户看**的失败原因。
     *
     * ★ 为什么按码取话：{@link Trash.Result#error} 是 ASCII 原文（给报告与自检），
     *   直接显示给用户是"看不懂的英文"，而 {@link Trash} 不能 `getString`。
     * 🔴 **加一个码就要在这里加一条**：`switch` 有 `default` 兜底（"原因不明"），
     *   所以漏映射**不崩**、界面上只是静默退化 ⇒ 自检里**遍历 `ALL_CODES`** 过一遍才抓得住。
     */
    public static String reason(Context c, Trash.Result r) {
        int code = r == null ? Trash.Result.T_NONE : r.errCode;
        switch (code) {
            case Trash.Result.T_NOT_IN_TRASH:
                return Trans.get(c, R.string.trash_reason_not_in_trash);
            case Trash.Result.T_NO_TARGET:
                return Trans.get(c, R.string.trash_reason_no_target);
            case Trash.Result.T_NAME_TAKEN:
                return Trans.get(c, R.string.trash_reason_name_taken);
            case Trash.Result.T_NOT_MAP:
                return Trans.get(c, R.string.trash_reason_not_map);
            case Trash.Result.T_STASH_FAILED:
                return Trans.get(c, R.string.trash_reason_stash_failed);
            case Trash.Result.T_MOVE_FAILED:
                return Trans.get(c, R.string.trash_reason_move_failed);
            case Trash.Result.T_GAME_RUNNING:
                // 游戏在跑这条有自己的一句（比"原因不明"具体得多）—— 复用同一句，别写两份
                return Trans.get(c, R.string.trash_busy_msg);
            case Trash.Result.T_NO_KIND:
                return Trans.get(c, R.string.trash_reason_no_kind);
            case Trash.Result.T_MKDIR:
                return Trans.get(c, R.string.trash_reason_mkdir);
            case Trash.Result.T_DELETE_FAILED:
                return Trans.get(c, R.string.trash_reason_delete_failed);
            case Trash.Result.T_CURRENT_SLOT:
                return Trans.get(c, R.string.trash_reason_current_slot);
            case Trash.Result.T_BAD_NAME:
                return Trans.get(c, R.string.trash_reason_bad_name);
            case Trash.Result.T_NOT_SCHEM:
                return Trans.get(c, R.string.trash_reason_not_schem);
            default:
                return Trans.get(c, R.string.trash_reason_unknown);
        }
    }
}
