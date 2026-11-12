package io.mdt.launcher;

import android.content.Context;

import java.util.List;

/**
 * **写门禁的唯一判据**（多开前置，2026-11-12 第 359 轮）。
 *
 * <p>目标不变式（用户 2026-11-12 原话的拆解，详见
 * `docs/plans/2026-10-31-多实例并行可行性.md` §12）：
 * <ul>
 *   <li>实例 I = (版本 key V, 槽 S) 在跑 ⇒ 写 S 的槽数据 / 写 V 的版本资产 / 写 I 的实例级状态**必须拒绝**；</li>
 *   <li>读 S / V 的数据**一律放行**（本类只管写）；</li>
 *   <li>其它槽 S′、其它版本 V′ 的读写**一律放行** —— 这正是多开要的；</li>
 *   <li>宿主全局数据（config.json / hub / cache / SAF 导出）不算实例数据，不因游戏在跑拒绝。</li>
 * </ul>
 *
 * <p>三条入口按"动作作用域"选：
 * <ul>
 *   <li>{@link #blockedSlot} —— 写某个**槽**（内容文件 / settings.bin / 整槽导入导出 / 删改名）；</li>
 *   <li>{@link #blockedVersion} —— 写某个**版本**的资产（APK / 版本级登记）；</li>
 *   <li>{@link #blockedInstance} —— 写某个**实例**（版本 key + 槽，例如补丁启用状态）。</li>
 * </ul>
 *
 * <p>★ **只在 core 层判**：UI 的预检只是为了"别让用户白选一次文件"，判据必须再落一层
 * （否则 dev 口 / 选择器返回后的时序 / 插件调用都能绕过）。同一条判据不许在两处各写一份。
 *
 * <p>★ 与 {@link Data#gameAlive} 的分工：`gameAlive` 仍是**全局**判据，只留给
 * 迁移 / 回收 / 杀进程这类动全局资源的动作；**写实例数据一律走本类**。
 */
final class WriteGate {

    private WriteGate() {}

    // ── 生产判据（读运行实例登记表） ───────────────────────────────────────

    /** 目标槽 S 有实例在跑吗（槽被占 ⇒ 拒绝写）。 */
    static boolean blockedSlot(Context ctx, String slot) {
        if (slot == null || slot.isEmpty()) return false;
        return runningOnSlot(Instances.live(ctx), slot) != null;
    }

    /** 目标版本 V 有实例在跑吗（版本资产被占 ⇒ 拒绝写）。 */
    static boolean blockedVersion(Context ctx, String vkey) {
        if (vkey == null || vkey.isEmpty()) return false;
        return runningOnVersion(Instances.live(ctx), vkey) != null;
    }

    /** 目标实例 (V,S) 正在跑吗（实例级状态 ⇒ 拒绝写）。 */
    static boolean blockedInstance(Context ctx, String ikey) {
        if (ikey == null || ikey.isEmpty()) return false;
        return runningOnInstance(Instances.live(ctx), ikey) != null;
    }

    /**
     * 目标槽已知就按槽判；**目标槽推不出来**（不该发生在产品路径上）退回全局
     * {@link Data#gameAlive} —— 保守方向：推不出目标时宁可多拦。
     */
    static boolean blockedSlotOrGlobal(Context ctx, String slot) {
        if (slot == null || slot.isEmpty()) return Data.gameAlive(ctx);
        return blockedSlot(ctx, slot);
    }

    // ── 纯判据（自检直接喂；不碰 Context / 文件） ─────────────────────────

    /** 登记表里**占用槽 S** 的那个实例；没有返回 null。 */
    static Instances.Running runningOnSlot(List<Instances.Running> running, String slot) {
        if (running == null || slot == null || slot.isEmpty()) return null;
        for (Instances.Running r : running) {
            if (r != null && slot.equals(r.slot)) return r;
        }
        return null;
    }

    /** 登记表里**占用版本 V** 的那个实例；没有返回 null。 */
    static Instances.Running runningOnVersion(List<Instances.Running> running, String vkey) {
        if (running == null || vkey == null || vkey.isEmpty()) return null;
        for (Instances.Running r : running) {
            if (r != null && vkey.equals(r.vkey)) return r;
        }
        return null;
    }

    /** 登记表里**正好是这个实例 (V,S)** 的那个；没有返回 null。 */
    static Instances.Running runningOnInstance(List<Instances.Running> running, String ikey) {
        if (running == null || ikey == null || ikey.isEmpty()) return null;
        for (Instances.Running r : running) {
            if (r != null && ikey.equals(r.instanceKey())) return r;
        }
        return null;
    }

    static boolean blockedSlot(List<Instances.Running> running, String slot) {
        return runningOnSlot(running, slot) != null;
    }

    static boolean blockedVersion(List<Instances.Running> running, String vkey) {
        return runningOnVersion(running, vkey) != null;
    }

    static boolean blockedInstance(List<Instances.Running> running, String ikey) {
        return runningOnInstance(running, ikey) != null;
    }

    /** 第一个在跑实例的人读描述（日志 / 报告用；没有返回空串）。 */
    static String describe(List<Instances.Running> running) {
        if (running == null || running.isEmpty()) return "";
        return running.get(0).label() + " (pid=" + running.get(0).pid + ")";
    }
}
