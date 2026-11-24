package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;
import java.io.File;
import java.util.List;

/**
 * 自动备份（F5）。
 *
 * ── 语义来源（照桌面版 gui_game.py 源码逐条核过，不是推的） ──────────────────
 *
 *   playtime = (time.time() - start) / 60          # 本次游戏运行时长
 *   if auto_backup and playtime >= min_playtime:   # 默认门槛 20 分钟
 *       create_backup()
 *
 * ⇒ 桌面版是**游戏退出之后**算"这一局玩了多久"，够门槛才备份。
 *   所以「最小备份时间」量的是**上一局玩了多久**，不是"距上次备份多久"。
 *   ⚠️ BACKLOG 早期写的"Android 对应物更直接：启动之前备份"是**推断，与源码不符**。
 *
 * ── Android 的等价实现（进程模型不同，只能等价、不能照抄） ──────────────────
 *
 *   启动游戏前  → Config.sessionStart(key, slot) 落盘记账（**一槽一局**）
 *   :game 进程  → SessionClock 每 20 秒写一拍检查点（`app_hub/instances/<started>-<pid>.time`）
 *   回到启动器  → 逐局结算：哪个槽的实例已经退出，就读它**最后一拍**当结束时刻；
 *                 够门槛 → 后台快照 + 超上限清理 → 清**那一局**的账、**删掉检查点**
 *
 * ★ 第 364 轮（多开）：账按槽存（`Config.sessions()`），结算按**实例**判
 *   （{@link #stillRunning}）：A 槽退了、B 槽还在跑 ⇒ A 照常结算，不再等 B。
 * ★ 2026-11-17 定案（用户三条）：
 *   ① **统一 +20 秒**：检查点最多丢一拍 ⇒ 判定时统一加 {@link SessionClock#FLUSH_MS}
 *      容差（`judgedMs`），门槛附近的局不会因为差一秒而漏备；
 *   ② **没有检查点 ⇒ 跳过**（不回退 `now`）：那说明这一局没量到（启动即崩 / 时钟没起来），
 *      不拿"第二次打开启动器的时刻"充数；
 *   ③ **检查点用完即删**：它只用来判断"用不用备份"；历史账归插件（`game-exit` 的
 *      `activeMs` / `wallMs`），宿主不留流水，也不做 7 天过期。
 *
 * ★ 记账为什么必须落盘：游戏会带走 :game 进程，主进程也可能被系统回收。
 *   下次打开启动器时那次启动可能已经是几小时前的事，账在盘上才结算得了。
 * ★ 结算为什么放 onResume：它就是"用户回到启动器"这一刻，天然同时覆盖
 *   「游戏退出→回启动器」和「从最近任务划掉游戏→以后再开启动器」两条路径。
 *
 * 已知偏差（有意接受）：
 *   · 正常退出 / 划卡 / 系统杀 ⇒ 结束时刻 = 最后一拍（最多早 20 秒）；
 *   · 进程被冻结（切后台放久了）⇒ `lastSeen` 停在冻结前，**冻结那段不算这一局**——
 *     这与"最后一次真正在跑"一致，但和桌面版"进程活着就算"不同；
 *   · 时钟整体没起来 ⇒ 这一局不备份（只写报告）；
 *   · "启动即崩"那一局 lastSeen ≈ started，判定时长 ≈ 20 秒，会正确跳过 ——
 *     这才是门槛真正要挡的。
 */
final class AutoBackup {

    /** 判定结果（纯判据，自检直接喂）。 */
    static final class Verdict {
        /** 要不要备份。 */
        final boolean backup;
        /** 观测时长 = lastSeen - at（毫秒；没有检查点 = 0）。 */
        final long observedMs;
        /** 判定时长 = observed + 20 秒容差（毫秒；没有检查点 = 0）。 */
        final long judgedMs;
        /** 不备份的原因（人话，进报告）；要备份时为 null。 */
        final String reason;

        Verdict(boolean backup, long observedMs, long judgedMs, String reason) {
            this.backup = backup;
            this.observedMs = observedMs;
            this.judgedMs = judgedMs;
            this.reason = reason;
        }
    }

    /** 不备份的三种原因（报告 / 日志共用同一句，别在两处各写一套）。 */
    static final String SKIP_OFF = "跳过：自动备份已关";
    static final String SKIP_NO_CHECKPOINT = "跳过：没有检查点";
    static final String SKIP_THRESHOLD = "跳过：不够门槛";

    private AutoBackup() {}

    // ── 与"启动游戏"之间的互斥 ────────────────────────────────────────────
    //
    // 场景：游戏退出 → 我们开始后台快照 → 用户立刻又点了启动。
    // 快照是逐文件读槽目录的，而游戏一起来就会写同一个目录 ⇒ 备份内容可能不一致。
    // 所以备份期间置位，启动路径先等它收尾（见 MainActivity.startVersion）。

    private static final Object LOCK = new Object();
    private static int sBusy = 0;

    private static void beginWork() {
        synchronized (LOCK) { sBusy++; }
    }

    private static void endWork() {
        synchronized (LOCK) {
            if (sBusy > 0) sBusy--;
            LOCK.notifyAll();
        }
    }

    /** 让启动路径等备份收尾。返回 true = 已经空闲（或等到空闲）。 */
    static boolean awaitIdle(long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (LOCK) {
            while (sBusy > 0) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    android.util.Log.w("MDTLauncher", "auto backup still busy after " + ms + "ms");
                    return false;
                }
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    // ── 记账 / 结算 ───────────────────────────────────────────────────────

    /** 启动游戏前落账（在 startActivity 之前调）。 */
    static void noteStart(String key, String slot) {
        Config.get().sessionStart(key, slot);
    }

    /**
     * 这一局的实例**还在跑**吗（第 364 轮：多开按实例判）。
     *
     * <p>多开下"有游戏在跑"不等于"这一局还在跑"：A 槽的游戏退出了、B 槽还在跑，
     * 以前全局 `gameAlive` 会把 A 也一直挂着（结算拖到 B 也退出）。
     * 判据 = **这个槽有没有运行实例**（{@link WriteGate#blockedSlot}）。
     *
     * <p>兜底：登记表**空**但全局还有游戏（老构建启动的实例 / 登记文件被清）⇒ 退回旧口径，
     * 只保守跳过"当前槽"那一局；别的槽照常结算 —— 那个看不见的实例只可能是最近启动的那个。
     * 登记表非空时一律**只信登记表**（否则多开下当前槽永远结算不了）。
     *
     * @param registryEmpty 调用方先取一次 `Instances.live(...).isEmpty()`；这里不重复扫盘
     */
    static boolean stillRunning(Context ctx, String slot, boolean gameAlive, boolean registryEmpty) {
        if (WriteGate.blockedSlot(ctx, slot)) return true;
        return registryEmpty && gameAlive && slot != null && slot.equals(Data.currentSlot(ctx));
    }

    /**
     * 回到启动器时结算一次（**逐局**）。
     * ★ 顺序是定死的：**先清那一局的账、再干活** —— 否则每次 onResume 都会重判一遍，
     *   备份会被反复触发。代价是"失败不重试"，与桌面版一致（那边失败也只记日志）。
     */
    static void settle(final Activity act) {
        final List<Config.Session> all = Config.get().sessions();
        // 顺手清"没有账的死 .time"（config 写竞争真把 sessions 弄丢时的兜底）。
        // ⚠️ 有账的一律不动 —— settle 还要拿它算这一局。
        SessionTime.sweepOrphans(act, all);
        if (all.isEmpty()) return;
        final boolean gameAlive = Data.gameAlive(act);
        final boolean registryEmpty = Instances.live(act).isEmpty();
        for (Config.Session s : all) {
            if (s == null || s.slot == null || s.slot.isEmpty() || s.at <= 0L) {
                Config.get().sessionClear(s == null ? "" : s.slot);   // 坏账清掉，别每次重判
                continue;
            }
            if (stillRunning(act, s.slot, gameAlive, registryEmpty)) continue;
            // ★ 2026-11-24（第 425 轮）：归档槽不结算 —— 它已经被清空了，这时再造一份
            //   普通备份只会得到"空槽的快照"，还会占掉备份名额。账照样清掉（别每次重判）。
            if (SlotArchive.isArchived(act, s.slot)) {
                Config.get().sessionClear(s.slot);
                SessionTime.deleteSlot(act, s.slot);
                continue;
            }
            settleOne(act, s);
        }
    }

    /**
     * 判定：观测时长 + 20 秒容差，够不够门槛。
     * **纯函数**（自检直接喂 at / lastSeen / policy），生产只有 {@link #settleOne} 一个调用方。
     */
    static Verdict judge(Config.Session s, SessionTime.Summary st, Config.BackupPolicy p) {
        if (p == null || !p.enabled) return new Verdict(false, 0L, 0L, SKIP_OFF);
        if (s == null || st == null) return new Verdict(false, 0L, 0L, SKIP_NO_CHECKPOINT);
        long observed = Math.max(0L, st.lastSeen - s.at);
        long judged = judgedMs(s.at, st.lastSeen);
        if (judged < p.minMinutes * 60000L) {
            return new Verdict(false, observed, judged, SKIP_THRESHOLD);
        }
        return new Verdict(true, observed, judged, null);
    }

    /** 判定用毫秒 = `lastSeen - at` **统一 + 一拍容差**（检查点最多丢一拍）。 */
    static long judgedMs(long at, long lastSeen) {
        return Math.max(0L, lastSeen + SessionClock.FLUSH_MS - at);
    }

    /** 结算**一局**：读检查点 → 判 → 清账 → 删检查点 → 够门槛就后台备份。 */
    private static void settleOne(final Activity act, final Config.Session s) {
        final String slot = s.slot;
        final Config.BackupPolicy p = Config.get().backupPolicy(slot);
        final SessionTime.Summary st = SessionTime.read(act, slot, s.at);
        // 读到的这一拍先交给事件层（**只在内存里**）：settle 可能比 `game-exit` 先到
        // （分屏 / 多开下游戏在启动器可见时被杀），文件删了事件就拿不到数了。
        if (st != null) PluginEvents.noteExitStats(slot, st);
        final Verdict v = judge(s, st, p);
        Config.get().sessionClear(slot);          // 无论是否备份都清这一局的账
        SessionTime.deleteSlot(act, slot);        // 用完即删：宿主不留这一局的记录

        if (!v.backup) {
            writeReport(act, slot, s.at, st, v, v.reason, false);
            android.util.Log.i("MDTLauncher", "auto backup skipped: " + v.reason
                    + " (slot=" + slot + ", observed=" + v.observedMs
                    + "ms, judged=" + v.judgedMs + "ms)");
            return;
        }

        new Thread(new Runnable() {
            @Override public void run() {
                String err = runNow(act, slot, Trans.get(act, R.string.auto_backup_label));
                writeReport(act, slot, s.at, st, v,
                        err == null ? "完成" : "跳过/失败：" + err, true);
                if (err != null) return;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(act, Trans.get(act, R.string.auto_backup_done_fmt, slot),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "mdt-autobackup").start();
    }

    // ── 干活 ──────────────────────────────────────────────────────────────

    /**
     * 快照 + 超上限删最旧。返回错误文本，成功 null。
     * **所有异常都吃在这里** —— 自动备份是后台行为，绝不能打断用户。
     * ⚠️ 失败不弹 Toast：最常见的"失败"其实是新槽还没内容（`Backup.create` 会拒绝），
     *   那不是故障，报出来只会吓人。证据写 hub/report-autobackup.txt。
     */
    static String runNow(Context ctx, String slot, String label) {
        try {
            createAndTrim(ctx, slot, label);
            return null;
        } catch (Throwable t) {
            android.util.Log.i("MDTLauncher", "auto backup aborted: " + t);
            String m = t.getMessage();
            return (m == null || m.isEmpty()) ? String.valueOf(t) : m;
        }
    }

    /**
     * **槽操作前的自动备份**（2026-10-05 第 104 轮）。
     *
     * 用户原话：「**以及在槽操作前都自动备份吧（反正 CAS 不占空间）**」——
     * 于是"恢复 / 整槽导入"这类**会改写整个槽**的动作，动手前一律先留一份快照：
     * CAS 按内容寻址 ⇒ 没变的部分不占新空间，用户随时能退回上一步。
     *
     * ★ 与"玩够门槛"那条走**同一处实现**（{@link #createAndTrim}）：busy 标志
     *   （启动路径要等它收尾）、超上限修剪、计数记录 —— 三件都要，抄一份迟早漏一件。
     * ★ **不受 {@link Config.BackupPolicy#enabled} 约束**：那个开关管的是"每局结束要不要
     *   自动备份"；这里是"即将动这个槽"的安全网。关掉自动备份的人，也不该在点了
     *   「覆盖」之后没有退路。
     * ★ 返回 null = 没做成（新槽还没内容 / 游戏在跑 / 出错）—— 调用方**照常继续**：
     *   备份是安全网，不是门禁（把它做成门禁会让"空槽导入"直接不可用）。
     *
     * @return 快照（成功）；失败或没内容 ⇒ null，原因只进日志
     */
    static Backup.Snapshot beforeSlotOp(Context ctx, String slot, String label) {
        try {
            Backup.Snapshot ss = createAndTrim(ctx, slot, label);
            android.util.Log.i("MDTLauncher", "pre-op backup ok: slot=" + slot
                    + " files=" + ss.count + " new=" + ss.storedNew + " -> " + ss.dir);
            return ss;
        } catch (Throwable t) {
            android.util.Log.i("MDTLauncher", "pre-op backup skipped: " + t);
            return null;
        }
    }

    /** 快照 + 计数 + 修剪（**唯一实现**，上面两个入口共用）；失败抛给调用方 */
    private static Backup.Snapshot createAndTrim(Context ctx, String slot, String label)
            throws Exception {
        beginWork();
        try {
            Backup.Snapshot ss = Backup.create(ctx, slot, label);
            // F16：这两个数字是"CAS 到底有没有生效"的直接证据 ——
            // 自动备份是后台的，用户看不到对话框，只能靠 report-autobackup.txt。
            sLastStoredNew = ss.storedNew;
            sLastLogical = ss.bytes;
            trim(ctx, slot);
            return ss;
        } finally {
            endWork();
        }
    }

    /** 最近一次快照实际新增到对象池的字节（-1 = 还没跑过） */
    private static long sLastStoredNew = -1L;
    /** 最近一次快照的逻辑总量 */
    private static long sLastLogical = 0L;

    /**
     * 超过上限就从最旧的开始删（{@link Backup#list} 是新的在前）。
     *
     * <p>★ 归档快照**不占名额**：用户要的是"归档备份不计入备份数量"，
     *   所以这里只数普通备份（{@link Backup#normal}）。
     */
    private static void trim(Context ctx, String slot) {
        int max = Config.get().backupPolicy(slot).maxBackups;
        List<Backup.Snapshot> list = Backup.normal(ctx, slot);
        for (int i = max; i < list.size(); i++) {
            String err = Backup.delete(ctx, list.get(i));
            if (err != null) android.util.Log.w("MDTLauncher", "trim backup failed: " + err);
        }
    }

    /**
     * 留一份证据到 hub/report-autobackup.txt（**每次结算都刷新**，包括"跳过"）。
     * ★ 部分 ROM 会滤掉应用自己的 main logcat（`logcat -d | grep` 零命中），
     *   所以运行期证据一律写文件 —— 这条已经是本工程的固定纪律。
     * ★ 观测值与判定值**都写**：判定含 20 秒容差，只写一个会让报告和"实际观测"对不上。
     */
    private static void writeReport(Context ctx, String slot, long at,
                                    SessionTime.Summary st, Verdict v, String result,
                                    boolean ran) {
        try {
            File dir = Data.hubDir(ctx);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) return;
            Config.BackupPolicy p = Config.get().backupPolicy(slot);
            StringBuilder sb = new StringBuilder();
            sb.append("结果: ").append(result).append('\n');
            sb.append("槽: ").append(slot).append('\n');
            sb.append("本局运行时长（观测）: ")
              .append(st == null ? "—" : (v.observedMs / 60000L) + " 分钟").append('\n');
            sb.append("本局运行时长（判定，含 20 秒容差）: ")
              .append(st == null ? "—" : (v.judgedMs / 60000L) + " 分钟").append('\n');
            sb.append("观测终点: ")
              .append(st == null ? "—" : String.valueOf(st.lastSeen)).append('\n');
            sb.append("前台累计: ")
              .append(st == null ? "—" : String.valueOf(st.activeMs)).append('\n');
            sb.append("策略: enabled=").append(p.enabled).append(" min=").append(p.minMinutes)
              .append(" max=").append(p.maxBackups).append('\n');
            if (ran) {
                sb.append("快照逻辑量: ").append(Util.formatSize(sLastLogical))
                  .append("  实际新增占用: ").append(Util.formatSize(Math.max(0L, sLastStoredNew)))
                  .append("（CAS：相同内容只在对象池里存一份）\n");
            } else {
                sb.append("快照逻辑量: —（这次没生成快照）\n");
            }
            sb.append("时刻: ").append(System.currentTimeMillis()).append('\n');
            Util.atomicWriteText(new File(dir, "report-autobackup.txt"), sb.toString());
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "write autobackup report failed: " + t);
        }
    }
}
