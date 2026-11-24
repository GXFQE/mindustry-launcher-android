package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * 槽归档的**状态位**：槽目录里的一个点开头文件 {@code .archived}。
 *
 * <p>为什么状态放在槽目录里，而不是写进 {@code Config}：
 * <ul>
 *   <li>改名 / 删槽**自动跟随目录** —— 不必去 {@link Config#retargetSlots} 里补第六处
 *       （那里已经五处，漏一处就是悬空归档）。</li>
 *   <li>点开头文件本来就不算「槽内容」：{@link Data#contentSkipped} 把它排除在
 *       {@link Data#contentRootsOf} 之外 ⇒ 它不进备份、不进整槽导出，也**不会被
 *       {@link SlotWrite#wipeSlot} 删掉**（清空正好要留着它）。</li>
 *   <li>{@link Backup#cloneSlot} 是「备份源槽 + 恢复进新槽」，恢复只写清单里的文件
 *       ⇒ 标记不会被克隆带过去。</li>
 * </ul>
 *
 * <p>★ 归档之后那个槽「等于没玩过」（用户 2026-11-24 的原话）：它的内容全在归档快照里，
 * 槽本体只剩一个空壳 + 这个标记。因此**读和写都要拦** —— 判据只此一份
 * {@link #isArchived(Context, String)}，宿主界面、导入导出、插件接口、启动链全部调它，
 * 不许各处自己写 {@code if}。
 */
final class SlotArchive {

    /** 标记文件名（点开头 ⇒ 不算槽内容，见类注释）。 */
    static final String MARKER = ".archived";

    /** 拒绝原因码（{@link #denyReason} 的返回值；{@code null} = 可以归档）。 */
    static final String DENY_MISSING = "missing";
    static final String DENY_ARCHIVED = "archived";
    static final String DENY_DEFAULT = "default";
    static final String DENY_RUNNING = "running";

    private SlotArchive() {}

    static File markerOf(Context ctx, String slot) {
        File dir = Paths.slotDir(ctx, slot);
        return dir == null ? null : new File(dir, MARKER);
    }

    /** 槽目录本身在不在（归档标记只在真目录里才有意义）。 */
    static boolean hasSlotDir(Context ctx, String slot) {
        File dir = Paths.slotDir(ctx, slot);
        return dir != null && dir.isDirectory();
    }

    /**
     * 已归档吗。
     *
     * <p>★ 宁可**多让玩一次**也不误锁：目录不在 / 标记读不出来 / JSON 坏 / 槽名非法
     * 一律按「未归档」处理。真正防误锁的兜底还有一层 —— 归档动作本身会校验
     * （{@link #denyReason}），不会把没归档的槽当归档的。
     */
    static boolean isArchived(Context ctx, String slot) {
        return read(ctx, slot) != null;
    }

    /** 归档时那份快照的目录名；未归档 / 坏标记 ⇒ {@code null}。 */
    static String snapshotOf(Context ctx, String slot) {
        JSONObject o = read(ctx, slot);
        return o == null ? null : optString(o, "snapshot");
    }

    /** 归档时刻（毫秒）；未归档 / 坏标记 ⇒ {@code 0}。 */
    static long archivedAt(Context ctx, String slot) {
        JSONObject o = read(ctx, slot);
        return o == null ? 0L : o.optLong("at", 0L);
    }

    /** 写标记。目录不存在 ⇒ 返回 false（不是「归档成功」）。 */
    static boolean write(Context ctx, String slot, String snapshotDir) {
        File f = markerOf(ctx, slot);
        if (f == null) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("snapshot", snapshotDir == null ? "" : snapshotDir);
            Util.atomicWriteText(f, o.toString());
            return true;
        } catch (Throwable t) {
            Log.w("SlotArchive", "write marker failed for '" + slot + "': " + t);
            return false;
        }
    }

    /** 删标记。没有标记也算成功（幂等）。 */
    static boolean clear(Context ctx, String slot) {
        File f = markerOf(ctx, slot);
        if (f == null) return false;
        try {
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            Log.w("SlotArchive", "clear marker failed for '" + slot + "': " + t);
            return false;
        }
    }

    /**
     * 能不能归档 —— **纯判据**（自检直接调，不碰界面）。
     *
     * @return {@code null} = 可以；否则是 {@link #DENY_MISSING} / {@link #DENY_ARCHIVED}
     *         / {@link #DENY_DEFAULT} / {@link #DENY_RUNNING} 之一
     */
    static String denyReason(Context ctx, String slot) {
        if (slot == null || slot.trim().isEmpty()) return DENY_MISSING;
        if (!hasSlotDir(ctx, slot)) return DENY_MISSING;
        if (isArchived(ctx, slot)) return DENY_ARCHIVED;
        // ★ 判据顺序要紧：当前槽恰好是默认槽时先报「默认槽不能归档」——
        //   用户看到的是"为什么不让归档"，而不是一句笼统的"不可用"。
        if (slot.equals(effectiveDefaultSlot())) return DENY_DEFAULT;
        if (WriteGate.blockedSlot(ctx, slot)) return DENY_RUNNING;
        return null;
    }

    /** 拒绝原因 → 文案资源（唯一码表，别在界面里另写一套）。 */
    static int denyText(String reason) {
        if (DENY_DEFAULT.equals(reason)) return R.string.slot_err_default_slot;
        if (DENY_ARCHIVED.equals(reason)) return R.string.slot_err_already_archived;
        if (DENY_RUNNING.equals(reason)) return R.string.slot_err_game_running;
        return R.string.slot_err_archived_missing;
    }

    /** 拒绝原因 → 已经取好文案的字符串（调用方只有 {@code Context}，别各自再写一遍码表）。 */
    static String denyText(Context ctx, String reason) {
        return Trans.get(ctx, denyText(reason));
    }

    /**
     * 设置页「默认槽」实际生效的那一个：{@link Config#defaultSlot()} 空串 ⇒ 回落
     * {@link Data#SLOT_DEFAULT}（与 {@code Config.effectivePrimaryOf} / {@code Versions.slotFor}
     * 同口径）。
     */
    static String effectiveDefaultSlot() {
        String d = Config.get().defaultSlot();
        return d == null || d.trim().isEmpty() ? Data.SLOT_DEFAULT : d.trim();
    }

    /**
     * 插件侧的门：目标槽是不是「拿不到」。
     *
     * <p>★ 为什么连读也拦：槽本体已经没了，任何"看一眼内容"的入口都不许把空壳当成
     * "这个槽是空的"（用户 2026-11-24 的硬要求）。插件拿到 {@code null} 就按 not-found
     * 处理；原始目录 getter 则直接抛异常（不给文件句柄，否则读写全绕过去了）。
     */
    static boolean pluginBlocked(Context ctx, String slot) {
        return isArchived(ctx, slot);
    }

    private static JSONObject read(Context ctx, String slot) {
        if (slot == null || slot.trim().isEmpty()) return null;
        File f = markerOf(ctx, slot);
        if (f == null || !f.isFile()) return null;
        try {
            String s = Util.readText(f).trim();
            if (s.isEmpty()) return null;
            JSONObject o = new JSONObject(s);
            // 至少得认识 snapshot 这个键，否则当成坏标记（别把别人的同名文件当标记）
            if (!o.has("snapshot")) return null;
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String optString(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }
}
