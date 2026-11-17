package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 一局的**宿主时钟**（住在 `:game`）：给自动备份一个"这一局什么时候结束"的观测点。
 *
 * <p>2026-11-17 定案（用户）：「把插件这个心跳功能给宿主，宿主负责写一个每个实例执行了多长时间
 * 的文件」——但**这个文件只用于判断用不用备份**，结算完就删；历史账仍归插件
 * （宿主把 `game-exit` 的 `started / ended / activeMs / wallMs` 交给插件）。
 *
 * <p>口径（两个数，别混）：
 * <ul>
 *   <li>`lastSeen` = **墙钟**最后一次"进程还在跑"的观测（每 {@link #FLUSH_MS} 更新一次）——
 *       只给自动备份门槛用，对齐桌面版"启动 → 退出"；</li>
 *   <li>`activeMs` = 游戏 activity 在**前台**的累计（由 {@link GameActivityWatcher} 转发）——
 *       给插件展示用。</li>
 * </ul>
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li>**daemon 线程**：不能因为一个计时器让 `:game` 进程在游戏退出后还赖着不走；</li>
 *   <li>**游戏 activity 真结束（isFinishing 的 destroy）就停表**：Mindustry 退出多半直接
 *       `System.exit`（收不到回调，最多丢一拍）；但"只 finish 不退进程"时要停，
 *       否则进程被缓存的那段会算进这一局；</li>
 *   <li>**不解析插件的任何数据**：时钟只写自己的 `.time`，插件账本由插件自己写。</li>
 * </ul>
 *
 * <p>误差：落盘最多丢一拍（≤20 秒）⇒ 判定时统一加 {@link #FLUSH_MS} 容差
 * （见 {@link AutoBackup#judgedMs}）；冻结（进程停摆）期间不写，`lastSeen` 停在冻结前，
 * 那一段**不算**这一局 —— 这是有意的口径（"最后一次真正在跑"更接近"玩了多久"）。
 */
final class SessionClock {

    /**
     * 落盘间隔，同时是判定容差（两个数字必须同一个常量：改了间隔容差自动跟着变）。
     * ★ 20 秒 = 用户 2026-11-17 定案（与 `usagestats` 原来的采样节奏一致）。
     */
    static final long FLUSH_MS = 20_000L;

    private final Context app;
    private final int pid;
    private final String proc;
    private final String vkey;
    private final String slot;
    private final long started;

    private final Object lock = new Object();
    /** 已结束的前台片段之和（`elapsedRealtime` 差值，不怕用户改系统时间）。 */
    private long activeDone;
    /** 当前前台片段起点（`elapsedRealtime`；0 = 不在前台）。 */
    private long fgSince;
    private boolean stopped;

    private final ScheduledExecutorService timer;
    private final Runnable beat;

    private SessionClock(Activity act, String slot, String vkey) {
        Context c = act.getApplicationContext();
        this.app = c == null ? act : c;
        this.pid = android.os.Process.myPid();
        this.proc = Util.processName();
        this.vkey = vkey == null ? "" : vkey;
        this.slot = slot;
        this.started = System.currentTimeMillis();
        this.timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mdt-session-clock");
                t.setDaemon(true);
                return t;
            }
        });
        this.beat = new Runnable() {
            @Override public void run() {
                synchronized (lock) {
                    if (stopped) return;
                }
                flush();
            }
        };
    }

    /**
     * 起这一局的时钟（`GameSlot` 在 `Instances.publish` 之后、拉起游戏之前调）。
     *
     * @return null = 没起来（参数不全 / 异常）；调用方**照常继续**启动游戏
     */
    static SessionClock start(Activity act, String slot, String vkey) {
        if (act == null || slot == null || slot.trim().isEmpty()) return null;
        try {
            SessionClock c = new SessionClock(act, slot.trim(), vkey);
            c.flush();                       // 立刻落第一拍：短命的一局也有 started / lastSeen
            c.timer.scheduleAtFixedRate(c.beat, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
            return c;
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "session clock start failed: " + t);
            return null;
        }
    }

    /**
     * 游戏 activity 进 / 出前台（`GameSlot` 从 {@link GameActivityWatcher} 转发）。
     * 进出都立刻写一拍（pause 那一下 = "用户离开了"，不能等 20 秒）。
     */
    void foreground(boolean fg) {
        synchronized (lock) {
            if (stopped) return;
            long now = SystemClock.elapsedRealtime();
            if (fg) {
                if (fgSince == 0L) fgSince = now;
            } else if (fgSince != 0L) {
                activeDone += Math.max(0L, now - fgSince);
                fgSince = 0L;
            }
        }
        flush();
    }

    /**
     * 这一局结束（游戏 activity 真结束 / 启动失败）：最后一拍 + 停表。
     * 幂等；文件**不删** —— 结算 / 事件还要读它（用完由 {@link AutoBackup} 删）。
     */
    void end() {
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
        }
        flush();                             // 最后一拍：lastSeen = 现在
        try {
            timer.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    /** 写一拍（异常全吞在 {@link SessionTime#write} 里，时钟失败不许影响游戏）。 */
    private void flush() {
        long active;
        long seen = System.currentTimeMillis();
        synchronized (lock) {
            active = activeDone + (fgSince == 0L
                    ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - fgSince));
        }
        SessionTime.write(app, pid, proc, vkey, slot, started, seen, active);
    }
}
