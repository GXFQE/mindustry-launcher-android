package io.mdt.launcher;

import android.content.Context;

/**
 * 更新的**三档**（第 313 轮把"自动检查"与"接收预览版"两个开关合并成一个）。
 *
 * <p>为什么合并：那两个开关其实是**同一个问题的两个维度**，而且天然有序 ——
 * 「不查」 &lt; 「只查稳定版」 &lt; 「连预览版也查」。三选一比两个独立开关更难自相矛盾
 * （反例：自动检查关着、却还显示"接收预览版：已开启" —— 两句都真，但读起来像坏了）。
 *
 * <p>★ 值存在 `Config.update_mode`（int）。合并前那两个键（`update_preview` /
 * `update_auto_check`）**从未发布过**，所以直接弃用、不做迁移（迁移代码活不过一次发版）。
 */
final class UpdateMode {

    /** 不自动检查（手动「检查更新」仍然可用）。 */
    static final int OFF = 0;
    /** 自动检查，只收稳定版（走 `releases/latest`，GitHub 只指非 prerelease）。 */
    static final int STABLE = 1;
    /** 自动检查，连预览版也收（走固定通道 `update-channel-preview`）。 */
    static final int PREVIEW = 2;

    /**
     * 出厂默认 = **预览**。
     * ⚠️ 1.0 之前每个版本都是 prerelease —— 默认稳定等于"谁都永远收不到更新"。
     *    1.0 发布时改成 {@link #STABLE}。
     */
    static final int DEFAULT = PREVIEW;

    /** 弹窗里的顺序：从"最安静"到"最积极"，与语义的递进一致。 */
    static final int[] ALL = {OFF, STABLE, PREVIEW};

    private UpdateMode() {}

    static int of(Context c) {
        return Config.get().updateMode();
    }

    static void set(Context c, int m) {
        Config.get().setUpdateMode(m);
    }

    /** 会不会自己联网检查（{@code false} ⇒ 一次请求都不发）。 */
    static boolean autoChecks(int m) {
        return m != OFF;
    }

    /** 走预览通道吗。 */
    static boolean usesPreview(int m) {
        return m == PREVIEW;
    }

    static boolean valid(int m) {
        return m == OFF || m == STABLE || m == PREVIEW;
    }

    /** 三档各自的**人话**（也是设置页那行的副标题）。 */
    static int labelRes(int m) {
        switch (m) {
            case OFF: return R.string.update_mode_off;
            case STABLE: return R.string.update_mode_stable;
            default: return R.string.update_mode_preview;
        }
    }
}
