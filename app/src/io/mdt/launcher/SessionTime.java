package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一局游戏时长的**检查点**（宿主时钟写、结算读，**处理完就删**）。
 *
 * <p>为什么要落盘：游戏会带走 `:game` 进程、主进程也可能被系统回收；
 *    下次回到启动器时要知道"这一局最后跑到什么时候"，否则只能拿"打开启动器的时刻"充数
 *    （那正是 2026-11-16 用户问的「杀后台是不是看第二次打开启动器时间」）。
 *
 * <p>文件住在 `app_hub/instances/`（内部存储，与实例登记同目录）——
 *    {@link Instances#scan} 只认 `*.json`，不会碰 `.time`。文件名 = `<started>-<pid>.time`
 *    （纯数字，安全）；槽 / 版本 / 前后台累计都在 JSON 里。
 *
 * <p>★ 2026-11-17 定案（用户三条）：
 * <ul>
 *   <li>**没有检查点 ⇒ 跳过**（不回退 `now`）：检查点只用来判断"用不用备份"，
 *       不在就说明这一局没量到（启动即崩 / 时钟没起来），宁可不备，也不拿"打开启动器"充数；</li>
 *   <li>**处理完就删**：{@code AutoBackup.settleOne} 读到并算完立即删；不做 7 天过期、
 *       不进 {@link Recovery}。历史账归插件（`game-exit` 的 `activeMs`），宿主不留流水；</li>
 *   <li>**孤儿清扫**只删「进程已死 + 没有对应会话账」的 `.time` —— 那是清垃圾，不是记录。</li>
 * </ul>
 *
 * <p>⚠️ 只给宿主内部用：插件拿不到这个文件（插件要的是 `game-exit` 事件里的数字）。
 * <p>⚠️ `*In(File, …)` 那几个是**目录级实现**：生产只走 `dir(ctx)`，自检喂临时目录 ——
 *    这样自检永远不会扫到用户真实的检查点。
 */
final class SessionTime {

    /** 检查点后缀（与 `instances/` 里的 `*.json` 区分开）。 */
    static final String EXT = ".time";

    /**
     * 原子写的半成品（`<name>.tmp`）多久算「没人要了」（第 422 轮）。
     *
     * <p>{@code Util.atomicWriteText} 是「先写 `.tmp`、再 rename」：进程在两者之间被杀就留下一个
     * `.time.tmp` / `r-….json.tmp`，而原来的清扫只认 `*.time` ⇒ 它会永远躺着。
     * 这里按**年龄**判，不按 pid —— 写 `.tmp` 的可能正是 `:game` 进程，扫它的登记表也看不见这个中间文件；
     * 10 分钟足够任何一次写入跑完（检查点最多几 KB）。</p>
     */
    static final long TMP_STALE_MS = 10 * 60 * 1000L;

    /** 一个检查点（不可变）。 */
    static final class Summary {
        final File file;
        final int pid;
        final String proc;
        final String vkey;
        final String slot;
        final long started;
        final long lastSeen;
        final long activeMs;

        Summary(File file, int pid, String proc, String vkey, String slot,
                long started, long lastSeen, long activeMs) {
            this.file = file;
            this.pid = pid;
            this.proc = proc == null ? "" : proc;
            this.vkey = vkey == null ? "" : vkey;
            this.slot = slot == null ? "" : slot;
            this.started = started;
            this.lastSeen = lastSeen;
            this.activeMs = activeMs;
        }
    }

    private SessionTime() {}

    static File dir(Context ctx) {
        return Instances.dir(ctx);
    }

    /**
     * 写 / 覆盖本进程这一局的检查点（`SessionClock` 每 20 秒一次；dev / 自检也用这一份）。
     *
     * @return false = 没写成（目录 / IO 问题，原因只进日志 —— 时钟失败不许影响游戏）
     */
    static boolean write(Context ctx, int pid, String proc, String vkey, String slot,
                         long started, long lastSeen, long activeMs) {
        return writeIn(dir(ctx), pid, proc, vkey, slot, started, lastSeen, activeMs);
    }

    /** 目录级实现（自检喂临时目录；生产只经 {@link #write}）。 */
    static boolean writeIn(File d, int pid, String proc, String vkey, String slot,
                           long started, long lastSeen, long activeMs) {
        if (d == null || pid <= 0 || started <= 0L || slot == null || slot.isEmpty()) {
            return false;
        }
        try {
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) return false;
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("pid", pid);
            o.put("proc", proc == null ? "" : proc);
            o.put("vkey", vkey == null ? "" : vkey);
            o.put("slot", slot);
            o.put("started", started);
            o.put("lastSeen", Math.max(started, lastSeen));
            o.put("activeMs", Math.max(0L, activeMs));
            Util.atomicWriteText(new File(d, name(started, pid)), o.toString());
            return true;
        } catch (Throwable t) {
            Log.w("MDTLauncher", "session clock write failed: " + t);
            return false;
        }
    }

    /**
     * 读某个槽**最新**的检查点。没有 ⇒ null。
     *
     * @param since 大于 0 时只认 `lastSeen >= since`（防同一槽上一局的旧文件 / 时钟回拨）
     */
    static Summary read(Context ctx, String slot, long since) {
        return readIn(dir(ctx), slot, since);
    }

    /** 纯读（自检直接喂目录）。不删任何文件。 */
    static Summary readIn(File dir, String slot, long since) {
        if (dir == null || slot == null || slot.isEmpty()) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        Summary best = null;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(EXT)) continue;
            Summary one = summaryOf(f, readJson(f));
            if (one == null || !slot.equals(one.slot)) continue;
            if (since > 0L && one.lastSeen < since) continue;
            if (best == null || one.lastSeen > best.lastSeen) best = one;
        }
        return best;
    }

    /** 删掉这个槽**全部** `.time`（结算"用完了"；坏文件也顺手清掉）。返回删了几个。 */
    static int deleteSlot(Context ctx, String slot) {
        return deleteSlotIn(dir(ctx), slot);
    }

    /** 目录级实现（自检喂临时目录；生产只经 {@link #deleteSlot}）。 */
    static int deleteSlotIn(File dir, String slot) {
        if (dir == null || slot == null || slot.isEmpty()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(EXT)) continue;
            Summary one = summaryOf(f, readJson(f));
            if (one != null && !slot.equals(one.slot)) continue;
            if (f.delete()) n++;
        }
        return n;
    }

    /**
     * 启动清扫：删「进程已死 + 没有对应会话账」的 `.time`。
     *
     * <p>正常路径不会产生孤儿（结算就删）；这里兜的是"`config.json` 那一份写竞争真把
     * `sessions` 弄丢"这种情况 —— 那个文件再也不会有人来结算，留着只会永远躺着。
     * ⚠️ 有账的**一律不动**（哪怕进程死了：settle 还要拿它算这一局）；进程还活着的也不动。
     */
    static int sweepOrphans(Context ctx, List<Config.Session> sessions) {
        if (ctx == null) return 0;
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        Instances.Live probe = new Instances.Live() {
            @Override public boolean alive(int pid, String proc) {
                return Instances.alive(app, pid, proc);
            }
        };
        return sweepOrphansIn(dir(ctx), sessions, probe);
    }

    /** 目录级实现（自检喂临时目录 + 假存活表）。 */
    static int sweepOrphansIn(File dir, List<Config.Session> sessions, Instances.Live probe) {
        return sweepOrphansIn(dir, sessions, probe, System.currentTimeMillis());
    }

    /**
     * 目录级实现 + 固定「现在」（自检喂固定时刻，断言才不吃系统时钟）。
     *
     * <p>第 422 轮起同一个清扫也回收**原子写留下的半成品**：`instances/` 与 `instances/reserved/`
     * 里超过 {@link #TMP_STALE_MS} 的 `*.tmp`（判据见该字段的说明）。</p>
     */
    static int sweepOrphansIn(File dir, List<Config.Session> sessions, Instances.Live probe,
                              long now) {
        if (dir == null || probe == null) return 0;
        Set<String> pending = new HashSet<String>();
        if (sessions != null) {
            for (Config.Session s : sessions) {
                if (s != null && s.slot != null && !s.slot.isEmpty()) pending.add(s.slot);
            }
        }
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = sweepTmpIn(dir, now);
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(EXT)) continue;
            Summary one = summaryOf(f, readJson(f));
            if (one == null) {                       // 坏文件：永远不会有人来用
                if (f.delete()) n++;
                continue;
            }
            if (pending.contains(one.slot)) continue;   // 有账 ⇒ 留给 settle
            if (probe.alive(one.pid, one.proc)) continue;  // 进程还活着 ⇒ 别动
            if (f.delete()) n++;
        }
        n += sweepTmpIn(Instances.reservedDir(dir), now);   // 启动占位那一层也要收
        return n;
    }

    /**
     * 清掉太久没人动的 `*.tmp`（原子写的半成品）。返回删了几个。
     * 只按年龄判：刚写的可能另一个进程正在 rename，碰它会把一次写入变成"目标消失"。
     */
    private static int sweepTmpIn(File d, long now) {
        if (d == null) return 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".tmp")) continue;
            if (now - f.lastModified() < TMP_STALE_MS) continue;
            if (f.delete()) n++;
        }
        return n;
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    private static String name(long started, int pid) {
        return started + "-" + pid + EXT;
    }

    private static JSONObject readJson(File f) {
        try {
            return new JSONObject(Util.readText(f));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 坏条目（缺 pid / 槽 / started，或 lastSeen < started）⇒ null。 */
    private static Summary summaryOf(File f, JSONObject o) {
        if (f == null || o == null) return null;
        int pid = o.optInt("pid", 0);
        String slot = o.optString("slot", "");
        long started = o.optLong("started", 0L);
        long lastSeen = o.optLong("lastSeen", 0L);
        if (pid <= 0 || slot.isEmpty() || started <= 0L || lastSeen < started) return null;
        return new Summary(f, pid, o.optString("proc", ""), o.optString("vkey", ""), slot,
                started, lastSeen, Math.max(0L, o.optLong("activeMs", 0L)));
    }
}
