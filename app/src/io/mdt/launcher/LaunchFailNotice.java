package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;

import java.io.File;

/**
 * 「这一局没起来」回到界面时补一次提示（第 419 轮，第 420 轮补上"什么都没写"那一半）。
 *
 * <p><b>为什么需要它</b>（用户 2026-11-23 「补丁全提示不适用（甚至还会出现虚假启动）」）：
 * 主界面在 {@code startActivity} 之前就把进度卡收掉了，而 {@code :game} 那边
 * {@link GameSlot#fail} 的失败弹窗常常落在**没有前台窗口**的进程里 ⇒ 用户只看到
 * "点了没反应"、以为已经启动。
 *
 * <p><b>两个来源，各管一半</b>：
 * <ol>
 *   <li>{@code <hub>/report-launch-failed.txt}：{@code :game} 走完了自己的失败路径、写了记录
 *       （准备 / 补丁 / native / 资产 / Activity 任一步抛了异常）。记录是 v45 起就有的，
 *       给插件 {@code exit=launch-failed} 判据用 —— ⚠️ **不许删、也不许改它的行**。</li>
 *   <li>{@code <hub>/launch-attempt.txt}：主界面点下启动那一刻自己写的"这一局想启动"。
 *       进程在**写任何记录之前**就死了（原生崩溃 / 被系统回收），上面那份文件根本不会出现 ——
 *       判据 = 有点击记录、{@code :game} 已不在、{@code report-launch.txt} 没有变新、
 *       也没有失败记录（{@link #diedBeforeStart}）。</li>
 * </ol>
 *
 * <p>两份都只补报"刚刚"的那一局（失败 15 分钟、点击 3 分钟）：更早的说明用户后来已经重新
 * 玩上了，翻旧账只会吓人 —— 照样记账，不弹。记账文件 {@code notice-launch-failed.txt}
 * 的键带前缀（{@code attempt|槽|at}），两种来源互不覆盖。
 */
final class LaunchFailNotice {

    /** 只补报这么新的失败；更老的只记账（用户可能早就在别处重开过了）。 */
    static final long FRESH_MS = 15 * 60 * 1000L;

    /**
     * 只补报这么新的「点了启动」：正常启动几百 ms 就写 {@code report-launch.txt} 了，
     * 留 3 分钟是给慢设备 + 用户来回切界面的余量。
     */
    static final long ATTEMPT_FRESH_MS = 3 * 60 * 1000L;

    /** "提示过"的标记：内容 = 键（{@code <槽>|<at>} 或 {@code attempt|<槽>|<at>}）。 */
    private static final String MARK = "notice-launch-failed.txt";

    /**
     * 「点了启动却什么都没留下」至少要过这么久才下结论。
     *
     * <p>为什么不能立刻判：主进程在 {@code startActivity} 之前已经写了启动占位，但
     * `:game` 要等 {@link GameSlot#handle} 走到 {@link Instances#publish} 才算登记上来 ——
     * 那几百 ms（慢设备可能更久）里"没登记"和"死了"长得一模一样。所以这条判据只在
     * 回到界面后 **≥10 秒**才成立；不够老就等一次 {@link #RE_CHECK_MS} 之后再看。
     */
    static final long ATTEMPT_MIN_MS = 10 * 1000L;

    /** 回到界面时点击记录还太新 ⇒ 过这么久再看一次（只补一次，不轮询）。 */
    private static final long RE_CHECK_MS = 12 * 1000L;

    /** 主界面点下启动时写的"这一局想启动"。 */
    private static final String ATTEMPT = "launch-attempt.txt";

    /** 标记文件最多留这么多条键（超过就丢最老的）。 */
    private static final int MARK_KEEP = 40;

    private LaunchFailNotice() {
    }

    /**
     * 回到界面时补一次（{@code MainActivity.onResume}，**必须在 UI 线程**）。
     *
     * <p>先看失败记录（那份更具体，带 step / msg），再看"什么都没写"的点击记录。
     * 两边由 {@link #catchAttempt} 里的 {@code failedAt} 串起来：失败记录已经报过这一局，
     * 点击那条就不再重复报。
     */
    static void catchUp(final Activity a) {
        if (Util.dead(a)) return;
        long failedAt = catchFailed(a);
        if (!catchAttempt(a, failedAt)) recheck(a);
    }

    /**
     * 点击记录还太新（不到 {@link #ATTEMPT_MIN_MS}）⇒ 过 {@link #RE_CHECK_MS} 再看一次。
     *
     * <p>只挂一次、挂在当前窗口的 decorView 上（Activity 没了就跟着没）；
     * 第二次判断仍然走 {@link #catchAttempt}，去重靠 {@link #claims}（键相同，不会弹两遍）。
     */
    private static void recheck(final Activity a) {
        try {
            a.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    if (Util.dead(a)) return;
                    long failedAt = catchFailed(a);
                    catchAttempt(a, failedAt);
                }
            }, RE_CHECK_MS);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "schedule launch-fail recheck: " + t);
        }
    }

    /**
     * 补报 {@code report-launch-failed.txt}。
     *
     * @return 那条记录的时刻（0 = 没有文件 / 读不出 / 时刻非法）；**已经提示过也照样返回** ——
     *         调用方要靠它判断"这一局已经有失败记录，别再按点击补一遍"
     */
    private static long catchFailed(final Activity a) {
        File f = PluginEvents.launchFailedFile(a);
        if (f == null || !f.isFile()) return 0L;
        String text;
        try {
            text = Util.readText(f);
        } catch (Throwable t) {
            return 0L;
        }
        String slot = value(text, "slot");
        String step = value(text, "step");
        String msg = value(text, "msg");
        long at;
        try {
            at = Long.parseLong(value(text, "at").trim());
        } catch (Throwable t) {
            return 0L;
        }
        if (at <= 0) return 0L;
        final String key = keyOf(slot, at);
        // 太老的记录只记账不弹 —— 但是**照样记账**，否则下次 onResume 又会来一遍判断
        if (!fresh(at, System.currentTimeMillis())) {
            mark(a, key);
            return at;
        }
        if (!claims(handledKeys(a), key)) return at;
        // ★ 先记账再弹：与 CrashAlert 的"弹之前必须再 claim 一次"同一条纪律
        //   （这里只有 UI 线程一条路，读写天然串行；记账在前，弹窗崩了也不会反复打扰）
        mark(a, key);
        show(a, step, msg);
        return at;
    }

    /**
     * 补报"进程在写记录之前就没了"的那一局（第 420 轮）。
     *
     * <p>判据全在 {@link #diedBeforeStart}；这里只负责取证据 + 记账 + 弹。
     *
     * @return false = 记录还太新、这一刻还不能下结论（调用方安排一次复查）；
     *         其它情况（含"已经判过了 / 不是这一局"）都返 true
     */
    private static boolean catchAttempt(final Activity a, long failedAt) {
        File f = attemptFile(a);
        if (f == null || !f.isFile()) return true;
        String text;
        try {
            text = Util.readText(f);
        } catch (Throwable t) {
            return true;
        }
        String slot = value(text, "slot");
        long at;
        try {
            at = Long.parseLong(value(text, "at").trim());
        } catch (Throwable t) {
            return true;
        }
        if (at <= 0) return true;
        final long now = System.currentTimeMillis();
        if (now - at < ATTEMPT_MIN_MS) return false;
        // 注册表判据：只认已经 publish 的实例（占位不算，见 WriteGate#anyPublished）
        boolean alive = WriteGate.anyPublished(a);
        long report = launchReportMtime(a);
        if (!diedBeforeStart(at, now, alive, report, failedAt)) return true;
        final String key = "attempt|" + keyOf(slot, at);
        if (!claims(handledKeys(a), key)) return true;
        mark(a, key);
        showDied(a);
        return true;
    }

    /**
     * 纯判据：「这一局点了启动、却什么都没留下」。
     *
     * @param at               点击启动的时刻
     * @param now              现在
     * @param instanceAlive    还有**已登记**的实例活着吗（{@link WriteGate#anyPublished}）
     * @param launchReportMtime {@code <hub>/report-launch.txt} 的最后修改时刻（0 = 没有这份文件）
     * @param failedAt         失败记录的时刻（0 = 没有）
     */
    static boolean diedBeforeStart(long at, long now, boolean instanceAlive,
                                   long launchReportMtime, long failedAt) {
        if (!fresh(at, now, ATTEMPT_FRESH_MS)) return false;
        // 太新：`:game` 可能还没走到 publish（占位阶段），这一刻不能判"死了"
        if (now - at < ATTEMPT_MIN_MS) return false;
        if (instanceAlive) return false;
        // 注入跑完 ⇒ 一定写了 report-launch.txt（那一刻起"进没进主界面"就不由我们判定了）
        if (launchReportMtime >= at - 1000L) return false;
        // 有失败记录 ⇒ 由另一条路（带 step / msg）报，别在这里说"什么都没写"
        if (failedAt >= at - 1000L) return false;
        return true;
    }

    /**
     * 记下"这一局想启动"（{@code MainActivity} 在放行插件钩子之后、{@code startActivity} 之前调）。
     *
     * <p>同一槽只留一条（连点幂等）；写失败只记日志，不影响启动。
     */
    static void noteAttempt(Context ctx, String vkey, String slot) {
        try {
            File hub = Data.hubDir(ctx);
            if (hub == null || slot == null || slot.trim().isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            sb.append("slot=").append(slot.trim()).append('\n');
            sb.append("at=").append(System.currentTimeMillis()).append('\n');
            sb.append("vkey=").append(vkey == null ? "" : vkey.trim()).append('\n');
            Util.atomicWriteText(new File(hub, ATTEMPT), sb.toString());
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "note launch attempt: " + t);
        }
    }

    /** 纯判据：这条记录够不够新（允许时钟往回拨一点：{@code at} 在未来也算新）。 */
    static boolean fresh(long at, long now) {
        return fresh(at, now, FRESH_MS);
    }

    /** 同上，窗口由调用方给（点击记录用的是 {@link #ATTEMPT_FRESH_MS}）。 */
    static boolean fresh(long at, long now, long window) {
        if (at <= 0 || window <= 0) return false;
        long age = now - at;
        return age <= window && age >= -window;
    }

    /** 一条失败记录的键（槽 + 时刻）—— 同一个槽的同一局只提示一次。 */
    static String keyOf(String slot, long at) {
        return (slot == null ? "" : slot.trim()) + "|" + at;
    }

    /** 纯判据：这一局还没提示过吗（{@code handled} = 上次提示过的那条）。 */
    static boolean claims(String handled, String key) {
        return key != null && !key.isEmpty() && !key.equals(handled);
    }

    /**
     * 多键版：{@code handled} = 已经提示过的那些键。
     *
     * <p>为什么不是一个字符串：两种来源（失败记录 / 点击记录）**会互相覆盖** —— 一轮
     * onResume 里先记"这条失败记录已提示"，再判点击记录时发现"已提示过"的那条变成了别人，
     * 于是同一张卡在每次回界面都重弹（第 420 轮真机抓到）。标记文件改成一行一个键。
     */
    static boolean claims(java.util.List<String> handled, String key) {
        if (key == null || key.isEmpty()) return false;
        if (handled == null) return true;
        for (int i = 0; i < handled.size(); i++) {
            if (key.equals(handled.get(i))) return false;
        }
        return true;
    }

    // ── 文件位置 ─────────────────────────────────────────────────────────

    private static File attemptFile(Context ctx) {
        File hub = Data.hubDir(ctx);
        return hub == null ? null : new File(hub, ATTEMPT);
    }

    private static long launchReportMtime(Context ctx) {
        File hub = Data.hubDir(ctx);
        if (hub == null) return 0L;
        File f = new File(hub, "report-launch.txt");
        return f.isFile() ? f.lastModified() : 0L;
    }

    // ── 标记文件 ─────────────────────────────────────────────────────────

    private static java.util.List<String> handledKeys(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        File hub = Data.hubDir(ctx);
        if (hub == null) return out;
        File f = new File(hub, MARK);
        if (!f.isFile()) return out;
        String text;
        try {
            text = Util.readText(f);
        } catch (Throwable t) {
            return out;
        }
        int i = 0;
        while (i < text.length()) {
            int j = text.indexOf('\n', i);
            String line = (j < 0 ? text.substring(i) : text.substring(i, j)).trim();
            if (!line.isEmpty() && !out.contains(line)) out.add(line);
            if (j < 0) break;
            i = j + 1;
        }
        return out;
    }

    /** 追加一条"已提示过"（去重、只留最近 {@value #MARK_KEEP} 条，免得文件长个没完）。 */
    private static void mark(Context ctx, String key) {
        try {
            File hub = Data.hubDir(ctx);
            if (hub == null || key == null || key.isEmpty()) return;
            File f = new File(hub, MARK);
            java.util.List<String> keys = handledKeys(ctx);
            if (keys.contains(key)) return;
            keys.add(key);
            int from = Math.max(0, keys.size() - MARK_KEEP);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < keys.size(); i++) sb.append(keys.get(i)).append('\n');
            Util.atomicWriteText(f, sb.toString());
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

    /** 「进程没起来、也没留下记录」那张卡（比失败卡多说一句"再试一次 / 看崩溃堆栈"）。 */
    private static void showDied(Activity a) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a,
                Trans.get(a, R.string.game_launch_died_title),
                CardStack.text(a, Trans.get(a, R.string.game_launch_died_msg)),
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
