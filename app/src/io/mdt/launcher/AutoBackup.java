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
 *      （两者最终备份到的内容确实相同 —— 槽只在游戏运行时被改写 ——
 *        但门槛口径完全不同，照推断做会把"玩 3 分钟就退出"也备上一份。）
 *
 * ── Android 的等价实现（进程模型不同，只能等价、不能照抄） ──────────────────
 *
 *   启动游戏前  → Config.sessionStart(key, slot)  落盘记账
 *   回到启动器  → 若 :game 已死，结算：playtime = now - at
 *                 够门槛 → 后台快照 + 超上限清理 → 清账
 *
 * ★ 记账为什么必须落盘：游戏会带走 :game 进程，主进程也可能被系统回收。
 *   下次打开启动器时那次启动可能已经是几小时前的事，账在盘上才结算得了。
 * ★ 结算为什么放 onResume：它就是"用户回到启动器"这一刻，天然同时覆盖
 *   「游戏退出→回启动器」和「从最近任务划掉游戏→以后再开启动器」两条路径。
 *
 * 已知偏差（有意接受）：playtime 会把"游戏退出后到回到启动器"那段也算进去，
 *   所以只会**偏大**；而门槛判断是 `>=` ⇒ 偏大最多导致多备份一次，不会漏备份。
 *   反向的"启动即崩"那一局 playtime ≈ 0，会正确跳过 —— 这才是门槛真正要挡的。
 */
final class AutoBackup {

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
     * 回到启动器时结算一次。
     * ★ 顺序是定死的：**先清账、再干活** —— 否则每次 onResume 都会重判一遍，
     *   备份会被反复触发。代价是"失败不重试"，与桌面版一致（那边失败也只记日志）。
     */
    static void settle(final Activity act) {
        final String slot = Config.get().sessionSlot();
        final long at = Config.get().sessionStartedAt();
        if (slot.isEmpty() || at <= 0L) return;
        if (Data.gameAlive(act)) return;        // 游戏还在跑，这一局还没结束

        final Config.BackupPolicy p = Config.get().backupPolicy(slot);
        final long minutes = (System.currentTimeMillis() - at) / 60000L;
        Config.get().sessionClear();            // 无论是否备份都清账

        if (!p.enabled) return;
        if (minutes < p.minMinutes) {
            android.util.Log.i("MDTLauncher", "auto backup skipped: played " + minutes
                    + " min < " + p.minMinutes + " min (slot=" + slot + ")");
            return;
        }

        new Thread(new Runnable() {
            @Override public void run() {
                String err = runNow(act, slot, Trans.get(act, R.string.auto_backup_label));
                writeReport(act, slot, minutes,
                        err == null ? "完成" : "跳过/失败：" + err);
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

    /** 超过上限就从最旧的开始删（Backup.list 是新的在前）。 */
    private static void trim(Context ctx, String slot) {
        int max = Config.get().backupPolicy(slot).maxBackups;
        List<Backup.Snapshot> list = Backup.list(ctx, slot);
        for (int i = max; i < list.size(); i++) {
            String err = Backup.delete(ctx, list.get(i));
            if (err != null) android.util.Log.w("MDTLauncher", "trim backup failed: " + err);
        }
    }

    /**
     * 留一份证据到 hub/report-autobackup.txt。
     * ★ 部分 ROM 会滤掉应用自己的 main logcat（`logcat -d | grep` 零命中），
     *   所以运行期证据一律写文件 —— 这条已经是本工程的固定纪律。
     */
    private static void writeReport(Context ctx, String slot, long minutes, String result) {
        try {
            File dir = Data.hubDir(ctx);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) return;
            Config.BackupPolicy p = Config.get().backupPolicy(slot);
            Util.atomicWriteText(new File(dir, "report-autobackup.txt"),
                    "结果: " + result + "\n"
                    + "槽: " + slot + "\n"
                    + "本局运行时长: " + minutes + " 分钟\n"
                    + "策略: enabled=" + p.enabled + " min=" + p.minMinutes
                    + " max=" + p.maxBackups + "\n"
                    + "快照逻辑量: " + Util.formatSize(sLastLogical)
                    + "  实际新增占用: " + Util.formatSize(Math.max(0L, sLastStoredNew))
                    + "（CAS：相同内容只在对象池里存一份）\n"
                    + "时刻: " + System.currentTimeMillis() + "\n");
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "write autobackup report failed: " + t);
        }
    }
}
