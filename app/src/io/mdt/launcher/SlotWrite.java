package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * **整槽写入的三种模式**（2026-10-05，第 104 轮）。两条"整槽变更"的路共用这一份判据：
 * <pre>
 *   ① {@link SlotZip#extract}   整槽 zip 导入
 *   ② {@link Backup#restore}   快照恢复
 * </pre>
 *
 * ── 用户要的三个模式（原话 + 最终叫法）──────────────────────────────────
 * <pre>
 *   更新      —— 混合，相同（同名）的用**新**的覆盖旧的
 *   补齐      —— 混合，相同的用**旧**的覆盖新的（只把缺的补上）
 *                ⚠️ 用户原话叫「**补齐**」，界面上线后他说"这个名字有点奇怪"
 *                   ⇒ 中文改「补齐」、英文 "Add missing"（**语义一个字没变**，常量仍是 {@link #KEEP_OLD}）
 *   覆盖      —— 完全替换
 * </pre>
 *
 * ── 为什么单独立一个类 ──────────────────────────────────────────────────
 *
 * 这三条判据（同名谁赢 / 要不要先清空）一旦两条路各写一份，**迟早只改一处**，
 * 而症状是"同一个模式在两个入口下行为不同"—— 没有任何报错，只有用户觉得"它有时听话有时不听话"。
 * 本类只做判据与"清空"：不认识 zip / CAS，也不碰文案（那几条在 `strings.xml` 的 `slot_mode_*`，
 * 由两个调用方的对话框各自渲染）。
 *
 * 🔴 **清空的口径 = {@link Data#contentRootsOf}**（"槽的全部内容 − {@link Data#SLOT_EXCLUDE}
 *    − 空壳 − 半成品"），与"备份打什么 / 整槽导出打什么"**同源**。
 *    ⚠️ 这里与 F6c 时代那份老名单（`saves/maps/schematics/assetCache`，**刻意不删 `mods`**）
 *    有一点**故意的差别**：新口径把 `mods/` 也算进槽内容 ⇒ 包里没有模组时模组会被清掉
 *    （用户要的就是"完全替换"）。这条只在「覆盖」模式下发生，而且动手前一律自动备份
 *    （见 {@link AutoBackup#beforeSlotOp}），所以它是**可撤销**的。
 */
public final class SlotWrite {

    /** 更新：两边都有的用**源**覆盖（＝本工程原来的"只覆盖同名"行为） */
    public static final int UPDATE = 0;
    /** 补齐：两边都有的**保留目标里的**，只把目标缺的补上 */
    public static final int KEEP_OLD = 1;
    /** 覆盖：先把槽内容清空，再整份写入（完全替换） */
    public static final int REPLACE = 2;

    private SlotWrite() {}

    /**
     * 模式合法化：不认识的值一律当 {@link #UPDATE}。
     *
     * ★ 为什么必须有：模式是从**界面控件**读出来的（单选组 / Intent extra / 将来可能的 dev 口），
     *   而"越界的值"如果恰好落进"要不要清空"的判断里，就会**把用户的槽清掉**。
     *   宁可退化成最保守的"只覆盖同名"。
     */
    public static int sane(int mode) {
        return (mode == UPDATE || mode == KEEP_OLD || mode == REPLACE) ? mode : UPDATE;
    }

    /** 两边同名时，源要不要盖掉目标（更新 / 覆盖 = 要；补齐 = 不要） */
    public static boolean sourceWins(int mode) {
        return sane(mode) != KEEP_OLD;
    }

    /** 写入前要不要先清空目标（只有"覆盖"要） */
    public static boolean wipeFirst(int mode) {
        return sane(mode) == REPLACE;
    }

    /**
     * 清空一个槽的**内容**（不动 {@link Data#SLOT_EXCLUDE} 里那些：缓存 / 导入副本 / native 之类）。
     *
     * @return 删掉的顶级条目名（给报告与结果弹窗用；空 = 本来就没内容）
     * @throws IOException 有东西删不掉（多半是被占用）—— 这时**什么都不该继续**，
     *                     因为"清了一半再写"会让用户拿到一个既不是旧槽也不是新槽的东西
     */
    public static List<String> wipeSlot(Context ctx, File root) throws IOException {
        List<String> gone = new ArrayList<>();
        if (root == null || !root.isDirectory()) return gone;
        for (File f : Data.contentRootsOf(root)) {
            if (!Data.deleteTree(f)) {
                throw new IOException(ctx.getString(R.string.slotwrite_err_wipe_failed_fmt,
                        f.getAbsolutePath()));
            }
            gone.add(f.getName());
        }
        return gone;
    }
}
