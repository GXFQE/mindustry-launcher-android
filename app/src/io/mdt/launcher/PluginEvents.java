package io.mdt.launcher;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mdt.launcher.api.PluginApi;

/**
 * 插件**事件**派发（v22，第 221 轮）：`boot` / `app-foreground` / `game-start` / `game-exit`。
 *
 * <p>声明写在自述里（不需要新接口方法，也不抬 API 版本）：
 * <pre>
 * "extensions": [
 *   { "id": "ev1", "entry": "com.example.MyPlugin", "event": "boot"       },
 *   { "id": "ev2", "entry": "com.example.MyPlugin", "event": "app-foreground" },
 *   { "id": "ev3", "entry": "com.example.MyPlugin", "event": "game-start" },
 *   { "id": "ev4", "entry": "com.example.MyPlugin", "event": "game-exit"  }
 * ]
 * </pre>
 * 入口方法：
 * <pre>
 * public static void onEvent(PluginApi api, String name, Map&lt;String,String&gt; data);
 * </pre>
 * {@code data} 目前有 {@code name} / {@code slot} / {@code version}（拿不到就不放这个键）；
 * 补报的退出多一个 {@code backfill=1}。
 * ★ 2026-11-17：`game-exit` 多四个观测键 —— `started` / `ended` / `activeMs` / `wallMs`
 *   （毫秒字符串；来自宿主时钟的检查点），外加版本身份 {@code key}（`Versions.Entry.key()`）。
 *   插件用 `key` + {@code PluginApi.versions()} 的 extra 就能把这一局的时长挂到版本行上。
 * ★ 第 388 轮：`game-exit` 再补 `patch` / `patchLabel` / `patchCount` —— 这一局**实际注入的补丁**
 *   （来自 `report-patch-launch.json`；没有补丁就不放这三个键）。统计必须把「本体 + 补丁」和
 *   纯本体分开（用户：「应该算是半个改端」）。
 *
 * <p>⚠️ 交付边界（写进 `docs/PLUGIN-API.md`）：
 * <ul>
 *   <li>`boot` = 每个进程一次（第一次回到界面）；`app-foreground` = 每次从后台回来。</li>
 *   <li>`game-exit`：正常返回走"本进程刚启动过的那几局"（{@link #sSessions}，一槽一条）；
 *       启动器在游戏期间被杀 ⇒ 重启后看落盘标记 {@code event-pending.txt} **逐槽**补报，
 *       但那个槽**还在跑时不补报**（用户只是回管理器看一眼；别的槽照常补）。</li>
 *   <li>回调在 **UI 线程**执行（可以用 {@code confirm} / {@code toast}）；异常被吞进
 *       `MDTPlugin` 日志，一个坏插件不影响别的插件和启动流程。</li>
 *   <li>每次派发都会 {@code load} 一份插件（DexClassLoader）+ 跑完 {@code unload}；
 *       适合"启动前/退出后做一件事"，**不适合**当常驻监听 —— 常驻请用 v22 的
 *       {@code submit()} 任务。</li>
 *   <li>★ v45：`game-start` 是**同步**派发 —— 宿主等 {@code onEvent} 返回之后才写启动占位
 *       （{@code Instances.reserve}）、才 {@code startActivity}。所以回调里不但能做事，
 *       还能用 {@link PluginApi#cancelLaunch(String)} 否决这次启动（多个插件都调时**第一个**生效）。
 *       ⚠️ 回调**不许**等用户 / 等网络：宿主不设超时，UI 线程会一直卡着。</li>
 * </ul>
 */
final class PluginEvents {

    static final String GAME_START = "game-start";
    static final String GAME_EXIT = "game-exit";
    /** 进程起来后第一次回到界面（每个进程一次）。 */
    static final String BOOT = "boot";
    /** 每次从后台回到前台（含 boot 那一次）。 */
    static final String APP_FOREGROUND = "app-foreground";

    /**
     * 落盘标记：游戏开着、退出事件还没报（启动器可能被杀）。
     *
     * <p>★ 第 368 轮（多开）：改成**按槽分账** —— `{"v":2,"pending":{"<槽>":"<版本>"}}`；
     * 旧格式（`slot=… / version=…` 单局）读侧兼容。以前一个文件只记"最后一局"，
     * 第二个实例一启动就把前一个的 pending 盖掉。
     */
    private static final String PENDING = "event-pending.txt";
    private static final String PENDING_V = "v";
    private static final String PENDING_MAP = "pending";

    /**
     * 本进程启动过的局（槽 → 版本）。正常返回时用它区分"该报退出"。
     * ★ 第 368 轮：一槽一条（多开下两个实例各自的退出都能报出来）。
     */
    private static final Map<String, String> sSessions = new LinkedHashMap<String, String>();
    /**
     * v45：槽 → 这一局的**开始时刻**（毫秒）。`game-exit` 的 `exit` 判定要它
     * （"这一局之后才有崩溃报告"、"失败记录是不是这一局的"都拿它比）。
     */
    private static final Map<String, Long> sStarted = new LinkedHashMap<String, Long>();
    /**
     * v45：这一局**曾经看到过真实的 `:game` 进程**。
     *
     * <p>为什么必须有：轮询判"退出"不能只看"登记表里没有实例" —— 刚点启动、`:game` 还没
     * publish 时登记表里也没有它。见过一次才说明"它真的起来过"，之后不见了才是退出；
     * 从没见过且超过 {@link #LAUNCH_GRACE_MS} ⇒ 判"没起来"。
     */
    private static final java.util.Set<String> sSeenAlive = new java.util.HashSet<String>();

    /** v45：退出轮询线程（没有待报的局就自己退出；下一局 `gameStart` 再起）。 */
    private static Thread sPollThread;

    /** v45：退出轮询间隔（用户 2026-11-18：「晚到也要通知插件」—— 晚几秒可以，不能不报）。 */
    static final long EXIT_POLL_MS = 5_000L;
    /**
     * v45：启动宽限 —— 从没见过游戏进程时，多久之后判"这局没起来"。
     *
     * <p>2 分钟：大包解压 / native 发布可能花几十秒（`Patches` 那边也是按 30 秒算容差的），
     * 给足余量，宁可晚一点也不误报"没起来"。
     */
    static final long LAUNCH_GRACE_MS = 120_000L;

    /** v45：`exit` 四档（`docs/PLUGIN-API.md` 与自检共用）。 */
    static final String EXIT_CLEAN = "clean";
    static final String EXIT_CRASH = "crash";
    static final String EXIT_UNCLEAN = "unclean";
    static final String EXIT_LAUNCH_FAILED = "launch-failed";

    /**
     * 2026-11-17：settle 先跑时把这一局的观测放这里（**只在内存**，不落文件）。
     * `game-exit` 派发时消费；进程被杀则这条事件本来也会丢（和现状一致）。
     */
    private static final Map<String, SessionTime.Summary> sExitStats =
            new LinkedHashMap<String, SessionTime.Summary>();
    /** 界面是否在前台（{@link BaseActivity} 的 onStart/onStop 维护）。 */
    private static boolean sForeground;
    private static boolean sBooted;

    /**
     * ★ v45：正在派发 `game-start`。
     *
     * <p>{@link #cancelLaunch} 只在这个窗口内生效 —— 别的时候调用会写一条日志后忽略，
     * 这样插件在 `boot` / `app-foreground` 里误调也伤不到任何东西。
     */
    private static boolean sInGameStart;
    /** 这次启动被谁 / 为什么拦下（null = 放行）。取过就清，不跨局残留。 */
    private static Blocked sBlock;
    /** 当前正在派发的那台插件 id（{@link Blocked#by}）。 */
    private static String sDelivering;

    private PluginEvents() { }

    /** 游戏**即将**启动（点"启动"、兼容性复检通过之后，`startActivity` 之前）。 */
    static Blocked gameStart(Context ctx, String vkey, String pkg, String slot, String version) {
        if (slot == null || slot.trim().isEmpty()) return null;
        synchronized (PluginEvents.class) {
            sSessions.put(slot, version == null ? "" : version);
            sStarted.put(slot, Long.valueOf(System.currentTimeMillis()));
            sSeenAlive.remove(slot);
            // 同槽开新局 ⇒ 上一局的观测已经派发或作废，别留给这一局（键按槽）
            sExitStats.remove(slot);
        }
        notePending(ctx, slot, version);
        // v45：这一局开始轮询"游戏进程还在不在" —— 不等用户回界面才报退出
        ensurePolling(ctx);
        // ★ v45：派发窗口。只有这段时间里 `cancelLaunch` 才生效 —— 也正因为如此，
        //   MainActivity 必须**同步**等这里返回，再写启动占位、再 startActivity。
        sBlock = null;
        sInGameStart = true;
        try {
            dispatch(ctx, GAME_START, slot, startData(vkey, pkg, slot, version));
        } finally {
            sInGameStart = false;
            sDelivering = null;
        }
        Blocked b = sBlock;
        sBlock = null;
        // 被拦下 ⇒ 这一局根本不开始：把刚记的账撤掉（否则回前台会补报一条假的 game-exit）
        if (b != null) abortLaunch(ctx, slot);
        return b;
    }

    /**
     * v45：`game-start` 的 data（**包级可见，自检直接钉键集**）。
     *
     * <p>为什么身份要直接给：启动占位挪到了派发**之后** ⇒ 插件在这一刻调 {@code instances()}
     * 看不见这次启动，`versions()` 也只能靠显示名反查。`key` / `instanceKey` 与
     * {@code game-exit} 的 `key` 同源（{@link Versions#instanceKey}），插件不必自己拼。
     */
    static Map<String, String> startData(String vkey, String pkg, String slot, String version) {
        Map<String, String> m = data(GAME_START, slot, version);
        if (vkey != null && !vkey.isEmpty()) {
            m.put("key", vkey);
            m.put("instanceKey", Versions.instanceKey(vkey, slot));
        }
        if (pkg != null && !pkg.isEmpty()) m.put("pkg", pkg);
        return m;
    }

    /**
     * v45：插件否决这次启动（{@link PluginApi#cancelLaunch}）。
     *
     * @return true = 这次启动已被拦（无论是不是本次调用拦的）；false = 不在窗口内，忽略
     */
    static boolean cancelLaunch(Context ctx, String reason) {
        if (!acceptCancel(sInGameStart, sBlock != null)) {
            if (sInGameStart) return true;   // 窗口内、已有人拦过 ⇒ 收下，但先来的原因优先
            Log.w("MDTPlugin", "cancelLaunch ignored: not inside game-start");
            return false;
        }
        sBlock = new Blocked(sDelivering, sane(reason, 200));
        return true;
    }

    /**
     * v45：这一次 `cancelLaunch` 该不该记下来（纯判据，自检直接喂真值表）。
     *
     * <p>两个条件缺一不可：在 `game-start` 窗口内、且还没有人拦过（第一个生效）。
     * 拆出来是因为"窗口内 / 窗口外 × 已拦 / 未拦"四格必须由尺子钉住 ——
     * 原来窗口判定藏在静态字段里，自检碰不到。
     */
    static boolean acceptCancel(boolean inGameStart, boolean alreadyBlocked) {
        return inGameStart && !alreadyBlocked;
    }

    /**
     * v45：这一局**没开始**（插件拦下 / `startActivity` 抛异常）⇒ 撤掉启动记账。
     *
     * <p>不清的话：{@link #sSessions} 里的条目 + {@code event-pending.txt} 会在下一次
     * 回到前台时被当成"这局玩完了"，派发一条游戏根本没起来的 `game-exit`
     * （而且没有检查点 ⇒ 时长键整组缺失，形态与"启动即崩"一样）。
     */
    static void abortLaunch(Context ctx, String slot) {
        if (slot == null || slot.isEmpty()) return;
        synchronized (PluginEvents.class) {
            sSessions.remove(slot);
            sStarted.remove(slot);
            sSeenAlive.remove(slot);
            sExitStats.remove(slot);
        }
        clearPending(ctx, slot);
    }

    /** 一次"被拦下的启动"：谁拦的（插件 id，可能为空）+ 给用户看的原因（可能为空）。 */
    static final class Blocked {
        final String by;
        final String reason;

        Blocked(String by, String reason) {
            this.by = by == null ? "" : by;
            this.reason = reason == null ? "" : reason;
        }

        /** 有没有插件 id（不知道是谁拦的 ⇒ 日志里写 ?）。 */
        boolean named() { return !by.isEmpty(); }
    }

    /**
     * 任一界面进入前台：先把"上一局结束了"报掉，再报 {@code boot} / {@code app-foreground}。
     *
     * <p>第 368 轮（多开）：**一槽一局** —— 本进程看着起来、并且已经不在跑的局逐槽报退出；
     * 还在跑的（分屏 / 被暂停）先留着，等它真退了下一次前台再报。
     * 启动器在游戏期间被杀、重启后 {@link #sSessions} 是空的 ⇒ 看落盘标记
     * {@code event-pending.txt}，槽没在跑就补报（{@code backfill=1}）。
     */
    static void onForeground(Context ctx) {
        if (ctx == null || sForeground) return;
        sForeground = true;
        String slot = Data.currentSlot(ctx);
        // 先在锁里把"该报的局"摘出来（轮询线程也在改这几张表）
        List<Exited> done = new ArrayList<Exited>();
        synchronized (PluginEvents.class) {
            java.util.Iterator<Map.Entry<String, String>> it = sSessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                String s = e.getKey();
                if (WriteGate.blockedSlot(ctx, s)) continue;     // 这局还在跑 ⇒ 等它真退
                it.remove();
                Long st = sStarted.remove(s);
                sSeenAlive.remove(s);
                done.add(new Exited(s, e.getValue(), st == null ? 0L : st.longValue()));
            }
        }
        for (Exited x : done) {
            clearPending(ctx, x.slot);
            Map<String, String> data = data(GAME_EXIT, x.slot, x.version);
            attachExitStats(ctx, x.slot, data);
            attachOutcome(ctx, x.slot, data, startedOf(x.started, data));
            dispatch(ctx, GAME_EXIT, x.slot, data);
        }
        backfillExit(ctx);
        if (!sBooted) {
            sBooted = true;
            dispatch(ctx, BOOT, slot, data(BOOT, slot, null));
        }
        dispatch(ctx, APP_FOREGROUND, slot, data(APP_FOREGROUND, slot, null));
    }

    /** 整个应用退到后台（{@link BaseActivity#onStop}）。 */
    static void onBackground() {
        sForeground = false;
    }

    // ── v45：退出轮询（不等用户回界面） ───────────────────────────────────

    /**
     * v45：起一个 daemon 线程，每 {@link #EXIT_POLL_MS} 看一次"游戏进程还在不在"。
     *
     * <p>为什么需要它：`game-exit` 原来只挂在"任一界面进前台"上。分屏下游戏在另一半退出、
     * 用户一直停在启动器上 ⇒ 那一刻**没有 onStart 可挂**，事件就一直不报（用户 2026-11-18：
     * 「晚到也要通知插件」）。轮询把触发点从"用户动作"改成"进程消失"。
     *
     * <p>边界：
     * <ul>
     *   <li>没有待报的局（{@link #sSessions} 空）⇒ 线程自己退出；下一局 `gameStart` 再起。</li>
     *   <li>启动器进程被系统冻结 / 杀掉 ⇒ 轮询也停，那时仍靠回前台补一次 + pending 补报。</li>
     *   <li>判定与派发分离：线程只判 + 摘表，**派发切回主线程**（事件契约：回调在 UI 线程）。</li>
     * </ul>
     */
    private static synchronized void ensurePolling(final Context ctx) {
        if (sPollThread != null) return;
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (true) {
                        try { Thread.sleep(EXIT_POLL_MS); } catch (InterruptedException e) { break; }
                        boolean has;
                        synchronized (PluginEvents.class) { has = !sSessions.isEmpty(); }
                        if (!has) break;
                        try {
                            checkExited(app);
                        } catch (Throwable t) {
                            Log.w("MDTPlugin", "exit poll failed: " + t);
                        }
                    }
                } finally {
                    synchronized (PluginEvents.class) {
                        if (sPollThread == Thread.currentThread()) sPollThread = null;
                    }
                }
            }
        }, "plugin-exit-poll");
        t.setDaemon(true);
        sPollThread = t;
        t.start();
    }

    /**
     * 一次轮询：把"已经结束的局"摘出来，post 到主线程派发。
     *
     * <p>判据（顺序即优先级）：
     * <ol>
     *   <li>该槽有**真实** `:game` 进程（pid 不是本进程）⇒ 还活着，记 {@link #sSeenAlive}；</li>
     *   <li>见过它活着、现在不见了 ⇒ 真退出；</li>
     *   <li>从没见过、且超过 {@link #LAUNCH_GRACE_MS} ⇒ 判"这局没起来"（`launch-failed`）。</li>
     * </ol>
     */
    private static void checkExited(Context ctx) {
        final List<Exited> done = new ArrayList<Exited>();
        long now = System.currentTimeMillis();
        synchronized (PluginEvents.class) {
            java.util.Iterator<Map.Entry<String, String>> it = sSessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                String slot = e.getKey();
                if (gameProcessAlive(ctx, slot)) {
                    sSeenAlive.add(slot);
                    continue;
                }
                Long st = sStarted.get(slot);
                boolean seen = sSeenAlive.contains(slot);
                boolean graceOver = st != null && now - st.longValue() >= LAUNCH_GRACE_MS;
                if (!seen && !graceOver) continue;      // 可能还在启动
                it.remove();
                sStarted.remove(slot);
                sSeenAlive.remove(slot);
                done.add(new Exited(slot, e.getValue(), st == null ? 0L : st.longValue()));
            }
        }
        if (done.isEmpty()) return;
        final Context appCtx = ctx;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                for (Exited x : done) deliverExited(appCtx, x, false);
            }
        });
    }

    /** 主线程：把一局结束派发出去（轮询与前台补报共用同一套字段）。 */
    private static void deliverExited(Context ctx, Exited x, boolean backfill) {
        try {
            clearPending(ctx, x.slot);
            Map<String, String> data = data(GAME_EXIT, x.slot, x.version);
            if (backfill) data.put("backfill", "1");
            attachExitStats(ctx, x.slot, data);
            attachOutcome(ctx, x.slot, data, startedOf(x.started, data));
            dispatch(ctx, GAME_EXIT, x.slot, data);
        } catch (Throwable t) {
            Log.w("MDTPlugin", "exit dispatch failed: " + x.slot, t);
        }
    }

    /**
     * 这个槽有**真实**游戏进程吗（启动占位不算）。
     *
     * <p>为什么不直接用 {@link WriteGate#blockedSlot}：它把主进程的启动占位也算"被占"，
     * 而这里问的是"游戏进程还在不在" —— 占位的 pid 是主进程自己，明显不是游戏。
     */
    private static boolean gameProcessAlive(Context ctx, String slot) {
        try {
            for (Instances.Running r : Instances.live(ctx)) {
                if (r == null || !slot.equals(r.slot)) continue;
                if (r.pid != android.os.Process.myPid()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 这一局的开始时刻：优先用检查点（更准），没有再退回内存记录。 */
    private static long startedOf(long recorded, Map<String, String> d) {
        String v = d == null ? null : d.get("started");
        if (v != null) {
            try { return Long.parseLong(v); } catch (Throwable ignored) { }
        }
        return recorded;
    }

    /**
     * v45：把 `exit` 这一档塞进 data。
     *
     * <p>拿不准就**不放这个键**（文档惯例）：读不到槽根 ⇒ 不给。
     */
    private static void attachOutcome(Context ctx, String slot, Map<String, String> d, long started) {
        try {
            Boolean lid = null;
            File lf = Mods.launchIdFileOf(ctx, slot);
            if (lf != null) lid = Boolean.valueOf(lf.isFile());
            boolean crashAfter = false;
            // started 不知道（0）⇒ **不判崩溃**：`pick` 的 sinceMs=0 等于"任何报告都算"，
            // 那会把一份老报告栽到这一局头上。宁可给更保守的 `unclean`。
            if (Boolean.TRUE.equals(lid) && started > 0) {
                File dir = CrashAlert.crashesDir(ctx, slot);
                crashAfter = CrashAlert.pick(dir, "", started) != null;
            }
            long failedAt = readLaunchFailedAt(ctx, slot);
            String v = exitOutcome(failedAt, started, lid, crashAfter);
            if (v != null) d.put("exit", v);
        } catch (Throwable t) {
            Log.w("MDTPlugin", "exit outcome failed: " + t);
        }
    }

    /**
     * v45：`exit` 四档的**纯判据**（自检直接喂真值表）。
     *
     * @param failedAt   `:game` 记的"这局没起来"时刻（0 = 没有记录）
     * @param started    这一局开始时刻（0 = 不知道 ⇒ 不判 launch-failed，避免拿旧记录冒充）
     * @param launchId   `launchid.dat` 在不在（null = 读不到 ⇒ 返回 null，**不放键**）
     * @param crashAfter 这一局之后有没有崩溃报告
     * @return 四档之一；null = 拿不准
     */
    static String exitOutcome(long failedAt, long started, Boolean launchId, boolean crashAfter) {
        if (failedAt > 0 && started > 0 && failedAt >= started) return EXIT_LAUNCH_FAILED;
        if (launchId == null) return null;
        if (!launchId.booleanValue()) return EXIT_CLEAN;
        return crashAfter ? EXIT_CRASH : EXIT_UNCLEAN;
    }

    /** `:game` 写的"这局没起来"记录（v45）。只认**这个槽**的那条（包级可见：自检要读）。 */
    static long readLaunchFailedAt(Context ctx, String slot) {
        File f = launchFailedFile(ctx);
        if (f == null || !f.isFile()) return 0L;
        try {
            String text = readText(f);
            String s0 = valueOf(text, "slot");
            String a0 = valueOf(text, "at");
            if (s0 == null || !s0.trim().equals(slot)) return 0L;
            if (a0 == null) return 0L;
            return Long.parseLong(a0.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** `<hub>/report-launch-failed.txt`（自检也要用，包级可见）。 */
    static File launchFailedFile(Context ctx) {
        File hub = Data.hubDir(ctx);
        return hub == null ? null : new File(hub, "report-launch-failed.txt");
    }

    /** 一局结束的搬运包（槽 / 版本 / 开始时刻）—— 后台判定与前台补报共用。 */
    private static final class Exited {
        final String slot;
        final String version;
        final long started;

        Exited(String slot, String version, long started) {
            this.slot = slot;
            this.version = version == null ? "" : version;
            this.started = started;
        }
    }

    /**
     * 启动器被杀过：`event-pending.txt` 里还留着的局 ⇒ **逐槽**补报 `game-exit`。
     *
     * <p>⚠️ 槽还在跑（用户只是想回管理器看一眼）⇒ 那个槽**不报**、条目留着，等下一次前台；
     * 别的槽照常补报（第 368 轮：多开下一局一条，不再"只要有一局在跑就全都拖着不报"）。
     */
    private static void backfillExit(Context ctx) {
        try {
            JSONObject m = readPendingMap(ctx);
            if (m.length() == 0) return;
            boolean changed = false;
            List<String> slots = new ArrayList<String>();   // JSONObject.keys 的 remove 不保证支持
            java.util.Iterator<String> it = m.keys();
            while (it.hasNext()) slots.add(it.next());
            for (String slot : slots) {
                if (slot == null || slot.isEmpty()) {
                    m.remove(slot);
                    changed = true;
                    continue;
                }
                if (WriteGate.blockedSlot(ctx, slot)) continue;   // 这个槽还在跑
                String version = m.optString(slot, "");
                Map<String, String> data = data(GAME_EXIT, slot, version);
                data.put("backfill", "1");
                attachExitStats(ctx, slot, data);
                long st;
                synchronized (PluginEvents.class) {
                    Long s0 = sStarted.remove(slot);
                    sSeenAlive.remove(slot);
                    st = s0 == null ? 0L : s0.longValue();
                }
                attachOutcome(ctx, slot, data, startedOf(st, data));
                dispatch(ctx, GAME_EXIT, slot, data);
                m.remove(slot);
                changed = true;
            }
            if (changed) writePendingMap(ctx, m);
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event backfill failed", t);
        }
    }

    /** 落盘标记文件（自检要用，包级可见）。 */
    static File pendingFile(Context ctx) {
        File d = Plugins.dirNoCreate(ctx);
        return d == null ? null : new File(d, PENDING);
    }

    /** 读 pending 映射（v2 `{v:2,pending:{…}}`；旧单局格式读侧兼容；坏文件当空）。 */
    private static JSONObject readPendingMap(Context ctx) {
        File f = pendingFile(ctx);
        if (f == null || !f.isFile()) return new JSONObject();
        try {
            String text = readText(f).trim();
            if (text.startsWith("{")) {
                JSONObject m = new JSONObject(text).optJSONObject(PENDING_MAP);
                return m == null ? new JSONObject() : m;
            }
            // 旧格式：整个文件就是一局
            JSONObject m = new JSONObject();
            String slot = valueOf(text, "slot");
            if (slot != null && !slot.isEmpty()) {
                String v = valueOf(text, "version");
                m.put(slot, v == null ? "" : v);
            }
            return m;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void writePendingMap(Context ctx, JSONObject m) {
        File f = pendingFile(ctx);
        if (f == null) return;
        try {
            if (m == null || m.length() == 0) {
                if (f.isFile()) f.delete();
                return;
            }
            File d = f.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) return;
            JSONObject w = new JSONObject();
            w.put(PENDING_V, 2);
            w.put(PENDING_MAP, m);
            writeText(f, w.toString());
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event pending write failed", t);
        }
    }

    /** 一局开始：按槽记 pending（自检也直接调，包级可见）。 */
    static void notePending(Context ctx, String slot, String version) {
        if (slot == null || slot.isEmpty()) return;
        try {
            JSONObject m = readPendingMap(ctx);
            m.put(slot, version == null ? "" : version);
            writePendingMap(ctx, m);
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event pending write failed", t);
        }
    }

    /** 这局报完退出（或正常返回）：只清**这个槽**的 pending。 */
    static void clearPending(Context ctx, String slot) {
        if (slot == null || slot.isEmpty()) return;
        try {
            JSONObject m = readPendingMap(ctx);
            m.remove(slot);
            writePendingMap(ctx, m);
        } catch (Throwable ignored) { }
    }

    private static String valueOf(String text, String key) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && key.equals(line.substring(0, eq).trim())) {
                return line.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static String readText(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void writeText(File f, String text) throws java.io.IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
        }
    }

    private static Map<String, String> data(String name, String slot, String version) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("name", name);
        if (slot != null && !slot.isEmpty()) m.put("slot", slot);
        if (version != null && !version.isEmpty()) m.put("version", version);
        return m;
    }

    /**
     * 结算时把这一局的检查点交给事件层（**只在内存**）。
     *
     * <p>为什么需要：`AutoBackup.settle` 与 `game-exit` 的派发时机**不保证先后** ——
     * 正常是事件先到（还能读文件），但分屏 / 多开下游戏在启动器可见时被杀，settle 会先跑、
     * 检查点已经删了，事件就拿不到这一局的数。把观测放内存里，谁先谁后都对得上。
     */
    static void noteExitStats(String slot, SessionTime.Summary st) {
        if (slot == null || slot.isEmpty()) return;
        if (st == null) {
            sExitStats.remove(slot);      // 自检清理用（生产传 null 没有意义）
            return;
        }
        sExitStats.put(slot, st);
    }

    /**
     * 把这一局的 `started / ended / activeMs / wallMs`（+ 版本身份 `key`）补进事件 data。
     * 优先用内存交接（settle 已经删了文件）；否则现读 `.time`（事件先到，文件还在）。
     * 两处都没有 ⇒ 不补键（老插件忽略多出来的键；这条路说明这一局没量到）。
     */
    static void attachExitStats(Context ctx, String slot, Map<String, String> d) {
        if (ctx == null || slot == null || slot.isEmpty() || d == null) return;
        SessionTime.Summary st = sExitStats.remove(slot);
        if (st == null) st = SessionTime.read(ctx, slot, 0L);
        if (st == null) return;
        d.put("started", String.valueOf(st.started));
        d.put("ended", String.valueOf(st.lastSeen));
        d.put("activeMs", String.valueOf(st.activeMs));
        d.put("wallMs", String.valueOf(Math.max(0L, st.lastSeen - st.started)));
        if (!st.vkey.isEmpty()) d.put("key", st.vkey);
        attachPatches(ctx, slot, d, st.started);
    }

    /**
     * 第 388 轮：把这一局**实际注入的补丁**交给插件（`patch` / `patchLabel` / `patchCount`）。
     *
     * <p>为什么不是查"当前启用了哪些补丁"：启用状态可能在游戏运行期间被改；统计要的是
     * **这一局真的带了什么**。真源 = `:game` 注入完写的 `report-patch-launch.json`
     * （{@link Patches#readLaunchInfo(Context, String)}，按槽取最近一局）。
     *
     * <p>没有补丁 ⇒ 不放这几个键（插件按"本体"记账）；读不到记录也不放（不编"无补丁"）。
     * ⚠️ 记录时间早于这一局开始 ⇒ 是上一局留下的（这一局还没写记录 / 启动即崩），不能拿来充数。
     */
    /**
     * 启动记录与游戏会话开始之间的容许差：`:game` 先写补丁注入记录、再 `startActivity`，
     * 会话时钟在游戏 Activity 里才起；大包解压 / native 发布可能花几十秒 ⇒ 用 30 秒，别把当前局误判成上一局。
     */
    private static final long PATCH_RECORD_SLACK_MS = 30_000L;

    private static void attachPatches(Context ctx, String slot, Map<String, String> d,
                                      long sessionStarted) {
        try {
            Patches.LaunchInfo li = Patches.readLaunchInfo(ctx, slot);
            if (li == null) return;
            Map<String, String> f = patchFields(li.patches, li.at, sessionStarted);
            if (!f.isEmpty()) d.putAll(f);
        } catch (Throwable t) {
            Log.w("MDTPlugin", "patch stats attach failed: " + t);
        }
    }

    /**
     * 纯函数：`game-exit` 的补丁字段（自检直接喂 {@link Patches.Launched}）。
     *
     * @param at            启动记录写入时刻（墙钟；0 = 不知道）
     * @param sessionStarted 这一局开始时刻（墙钟；0 = 不知道）
     * @return 空表 = 这一局没有补丁 / 记录是上一局的 / 读不到；否则 `patch` / `patchLabel` / `patchCount`
     */
    static Map<String, String> patchFields(java.util.List<Patches.Launched> patches, long at,
                                           long sessionStarted) {
        Map<String, String> out = new HashMap<String, String>();
        if (patches == null || patches.isEmpty()) return out;
        // 记录早于这一局开始 ⇒ 是上一局留下的（这一局还没写记录 / 启动即崩），不能拿来充数
        if (at > 0L && sessionStarted > 0L && at + PATCH_RECORD_SLACK_MS < sessionStarted) return out;
        java.util.TreeMap<String, String> byRef = new java.util.TreeMap<String, String>();
        for (Patches.Launched l : patches) {
            if (l == null || l.ref == null || l.ref.isEmpty()) continue;
            String name = l.label == null || l.label.trim().isEmpty() ? l.ref : l.label.trim();
            byRef.put(l.ref, name);
        }
        if (byRef.isEmpty()) return out;
        StringBuilder id = new StringBuilder();
        StringBuilder labels = new StringBuilder();
        for (Map.Entry<String, String> e : byRef.entrySet()) {
            if (id.length() > 0) {
                id.append(',');
                labels.append('、');
            }
            id.append(e.getKey());
            labels.append(e.getValue());
        }
        out.put("patch", sane(id.toString(), 512));
        out.put("patchLabel", sane(labels.toString(), 200));
        out.put("patchCount", String.valueOf(byRef.size()));
        return out;
    }

    /** 事件字段卫生：控制字符 / 超长会毁账本 / 撑爆界面。 */
    private static String sane(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sb.toString().trim();
    }

    /**
     * 找出**所有已启用插件**里声明了这个事件的条目并逐个回调。
     *
     * <p>插件按 {@code active.txt} 的登记顺序；一个插件声明多条同类事件 ⇒ 每条各回调一次
     * （条目自带 id，插件自己按需去重）。
     */
    static void dispatch(Context ctx, String event, String slot, Map<String, String> data) {
        if (ctx == null || event == null) return;
        try {
            String active = Plugins.active(ctx);
            for (String id : Plugins.stateIds(active)) {
                String ver = Plugins.stateGet(active, id);
                if (ver == null || ver.isEmpty()) continue;
                File apk = Plugins.apkOf(ctx, id, ver);
                if (apk == null || !apk.isFile()) continue;
                for (Extensions.Item it : Extensions.of(apk)) {
                    if (!event.equals(it.event)) continue;
                    // v45：让 `cancelLaunch` 知道是谁在拦（只对 game-start 有意义，别的照设无害）
                    sDelivering = id;
                    try {
                        deliver(ctx, apk, it.entry, event, slot, data, id);
                    } finally {
                        sDelivering = null;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event dispatch failed: " + event, t);
        }
    }

    private static void deliver(Context ctx, File apk, String entry, String event, String slot,
                                Map<String, String> data, String id) {
        PluginHost.Loaded l = null;
        try {
            l = PluginHost.load(ctx, apk, entry, "event", slot);
            l.entry.getMethod("onEvent", PluginApi.class, String.class, Map.class)
                    .invoke(null, l.env, event, data);
        } catch (NoSuchMethodException e) {
            Log.w("MDTPlugin", "event plugin " + id + " has no onEvent(PluginApi,String,Map)");
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event handler failed: " + id + " / " + event, t);
        } finally {
            if (l != null) PluginHost.unload(l);
        }
    }
}
