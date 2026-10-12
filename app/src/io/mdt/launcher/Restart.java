package io.mdt.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/**
 * 「退出重开」—— 插件换版本 / 停用 / 装新的之后，**唯一**能让新 dex 生效的动作。
 *
 * <h3>为什么必须是"杀进程"而不是"重建界面"</h3>
 * 类一旦被加载就钉在**进程**的 ClassLoader 上（S0 实测：`prepend` 注入也换不掉已加载的类）。
 * 所以 `finish()` / `recreate()` / `finishAffinity()` 都换不掉任何东西 —— 必须让进程死掉。
 * 本类做的事：**先挂闹钟、再自杀**，于是系统在 ~1 秒后把 MainActivity 在新进程里拉起来，
 * 用户看到的只是"闪一下"。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li>**先挂闹钟再自杀**：反了就没有人来拉起我们（只能等用户手点图标）。</li>
 *   <li>挂不上闹钟（ROM 拒绝 / 拿不到启动 Intent）⇒ 返回 false，**界面必须如实告诉用户
 *       "请自己退出重开"** —— 不许假装重启成功。</li>
 *   <li>只杀**本进程**（{@code Process.killProcess(myPid())}）：`:game` 是另一个进程，
 *       正在跑的游戏不该被我们掐掉；反过来说，游戏在前台时**不该**有入口走到这里
 *       （重启会把启动器界面盖到游戏上）—— 那是调用侧的门。</li>
 * </ol>
 *
 * <p>★ 与 {@link Plugins} 的分工：那边管"重开之后按登记注入什么"，这边只管"怎么重开"。
 */
final class Restart {

    private static final String TAG = "MDTLauncher";

    /** 闹钟请求码（随便挑一个自己的，避免和工程里别的 PendingIntent 撞）。 */
    private static final int REQ = 0x0D71;

    /**
     * ★★ 重启的**去重窗口**（毫秒）—— 2026-10-11 真机踩出来的，非要害但很致命。
     *
     * <p>反例（实测）：用 `am start --es dev_pluginrestart` 触发重启时，进程自杀后系统把那个**任务
     * 连同原来的 Intent 一起恢复**（system_server 记着 `ActivityRecord.intent`）⇒ 新进程带着同一个
     * `--es dev_pluginrestart` 起来 ⇒ 又重启一次……如此循环，直到系统对精确闹钟限流，最后**应用躺在
     * 那里是死的**（`pidof` 空，marker 里 pid 换过 3 个）。
     *
     * <p>⇒ 规矩：**"重启"必须是幂等的**。窗口内第二次请求一律当成"这就是上一次重启的恢复重放"，
     * 直接不理会（既不再自杀，也不报错）。界面那条路（按钮）本来不会被重放，但这条闸门对两条路都值。
     */
    private static final long COOLDOWN_MS = 15000L;

    /** 已挂上闹钟，调用方接着自杀。 */
    static final int OK = 0;
    /** 挂不上（拿不到启动 Intent / ROM 拒绝）⇒ **界面必须如实说"请自己退出重开"**。 */
    static final int FAILED = 1;
    /** 刚重开过 ⇒ 这是恢复重放，**什么都别做**（也别弹窗）。 */
    static final int THROTTLED = 2;

    private Restart() {}

    /**
     * 挂一个"马上把自己拉回来"的闹钟。
     *
     * @return {@link #OK} / {@link #FAILED} / {@link #THROTTLED}
     */
    static int schedule(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            java.io.File stamp = new java.io.File(Paths.privateDir(ctx), "restart-at.txt");
            long last = 0L;
            try {
                if (stamp.isFile()) last = Long.parseLong(Util.readText(stamp).trim());
            } catch (Throwable ignored) {
            }
            if (last > 0 && now - last < COOLDOWN_MS) return THROTTLED;

            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (i == null) return FAILED;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, REQ, i, flags);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return FAILED;
            long at = now + 900L;
            // API 31+ 起"精确闹钟"要单独授权；没授权就退化成不精确的 set()（差几秒可接受，
            // 而抛 SecurityException 直接不挂 = 用户以为重启了其实没重开，那才是坏结果）
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExact(AlarmManager.RTC, at, pi);
            else am.set(AlarmManager.RTC, at, pi);
            // ★ 时间戳**只在真的挂上之后**才写：挂失败不留冷却，用户重试还能成
            try {
                Util.atomicWriteText(stamp, String.valueOf(now));
            } catch (Throwable ignored) {
            }
            return OK;
        } catch (Throwable t) {
            try {
                Log.w(TAG, "restart alarm failed: " + t);
            } catch (Throwable ignored) {
            }
            return FAILED;
        }
    }

    /**
     * 自杀（调用方必须**已经**拿到 {@link #OK}）。
     * ⚠️ 调用前该落盘的都要落完 —— 这里没有任何收尾钩子。
     */
    static void killSelf() {
        Process.killProcess(Process.myPid());
    }
}
