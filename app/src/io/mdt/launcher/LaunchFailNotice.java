package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;

import java.io.File;

/**
 * 「这一局没起来」回到界面时补一次提示（第 419 轮）。
 *
 * <p><b>为什么需要它</b>（用户 2026-11-23 「补丁全提示不适用（甚至还会出现虚假启动）」）：
 * 主界面在 {@code startActivity} 之前就把进度卡收掉了，而 {@code :game} 那边
 * {@link GameSlot#fail} 的失败弹窗常常落在**没有前台窗口**的进程里 ⇒ 用户只看到
 * "点了没反应"、以为已经启动。CAS 模式下那条必失败的路修掉之后，这条兜底还在：
 * 任何一步（准备 / 补丁 / native / 资产 / Activity）失败都要让用户回到界面时看到一句话。
 *
 * <p><b>判据只有一份</b>：记录本身是 {@code :game} 写的 {@code <hub>/report-launch-failed.txt}
 * （v45 起就有，给插件 {@code exit=launch-failed} 判据用）。⚠️ **不许删那份记录、
 * 也不许改它的三行**——这里只额外写一份"提示过"的标记文件，两者互不干扰。
 *
 * <p>只补报"刚刚"的那一局（{@link #FRESH_MS}）：更早的记录说明用户后来已经重新
 * 玩上了，翻旧账只会吓人 —— 照样记账，不弹。
 */
final class LaunchFailNotice {

    /** 只补报这么新的失败；更老的只记账（用户可能早就在别处重开过了）。 */
    static final long FRESH_MS = 15 * 60 * 1000L;

    /** "提示过"的标记：内容 = {@code <槽>|<at>}（与失败记录一一对应）。 */
    private static final String MARK = "notice-launch-failed.txt";

    private LaunchFailNotice() {
    }

    /**
     * 回到界面时补一次（{@code MainActivity.onResume}，**必须在 UI 线程**）。
     *
     * <p>消息取记录里的 {@code step} + {@code msg}：{@code step} 是"哪一步"、
     * {@code msg} 是给人看的那句原因（旧记录没有 {@code msg} 行 ⇒ 退回"看运行日志"的提示）。
     */
    static void catchUp(final Activity a) {
        if (Util.dead(a)) return;
        File f = PluginEvents.launchFailedFile(a);
        if (f == null || !f.isFile()) return;
        String text;
        try {
            text = Util.readText(f);
        } catch (Throwable t) {
            return;
        }
        String slot = value(text, "slot");
        String step = value(text, "step");
        String msg = value(text, "msg");
        long at;
        try {
            at = Long.parseLong(value(text, "at").trim());
        } catch (Throwable t) {
            return;
        }
        if (at <= 0) return;
        final String key = keyOf(slot, at);
        // 太老的记录只记账不弹 —— 但是**照样记账**，否则下次 onResume 又会来一遍判断
        if (!fresh(at, System.currentTimeMillis())) {
            mark(a, key);
            return;
        }
        if (!claims(handled(a), key)) return;
        // ★ 先记账再弹：与 CrashAlert 的"弹之前必须再 claim 一次"同一条纪律
        //   （这里只有 UI 线程一条路，读写天然串行；记账在前，弹窗崩了也不会反复打扰）
        mark(a, key);
        show(a, step, msg);
    }

    /** 纯判据：这条记录够不够新（允许时钟往回拨一点：{@code at} 在未来也算新）。 */
    static boolean fresh(long at, long now) {
        if (at <= 0) return false;
        long age = now - at;
        return age <= FRESH_MS && age >= -FRESH_MS;
    }

    /** 一条失败记录的键（槽 + 时刻）—— 同一个槽的同一局只提示一次。 */
    static String keyOf(String slot, long at) {
        return (slot == null ? "" : slot.trim()) + "|" + at;
    }

    /** 纯判据：这一局还没提示过吗（{@code handled} = 上次提示过的那条）。 */
    static boolean claims(String handled, String key) {
        return key != null && !key.isEmpty() && !key.equals(handled);
    }

    // ── 标记文件 ─────────────────────────────────────────────────────────

    private static String handled(Context ctx) {
        File hub = Data.hubDir(ctx);
        if (hub == null) return "";
        File f = new File(hub, MARK);
        if (!f.isFile()) return "";
        try {
            return Util.readText(f).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void mark(Context ctx, String key) {
        try {
            File hub = Data.hubDir(ctx);
            if (hub == null) return;
            Util.atomicWriteText(new File(hub, MARK), key + "\n");
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "mark launch-fail notice: " + t);
        }
    }

    // ── 提示 ─────────────────────────────────────────────────────────────

    private static void show(Activity a, String step, String msg) {
        String stepText = (step == null || step.trim().isEmpty()) ? "?" : step.trim();
        String detail = (msg == null || msg.trim().isEmpty())
                ? Trans.get(a, R.string.game_report_hint).trim()
                : msg.trim();
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a,
                Trans.get(a, R.string.game_cannot_start),
                CardStack.text(a, Trans.get(a, R.string.game_step_stuck_fmt, stepText, detail)),
                new CardStack.Btn(Trans.get(a, R.string.close), new Runnable() {
                    @Override public void run() {
                        if (ref[0] != null) ref[0].dismiss();
                    }
                }));
    }

    /** 读 {@code key=value} 那一行；没有就空串（与 PluginEvents 的读法同构）。 */
    private static String value(String text, String key) {
        if (text == null) return "";
        String want = key + "=";
        int i = 0;
        while (i < text.length()) {
            int j = text.indexOf('\n', i);
            String line = j < 0 ? text.substring(i) : text.substring(i, j);
            if (line.startsWith(want)) return line.substring(want.length());
            if (j < 0) break;
            i = j + 1;
        }
        return "";
    }
}
