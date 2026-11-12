package io.mdt.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * **运行实例登记表**（多开前置，2026-11-12 第 359 轮）。
 *
 * <p>解决什么问题：多开之前全工程判断"游戏在不在跑"只有 {@link Data#gameAlive}
 * （进程名 `pkg:game`，**不分槽/版本**）—— 它能回答"有没有游戏在跑"，回答不了
 * "**哪个实例**在跑、它用的是哪个槽 / 哪个版本"。多实例下后者才是写门禁的判据
 * （见 {@link WriteGate}）。
 *
 * <p>协议（唯一写入者是 `:game` 进程自己）：
 * <ul>
 *   <li>{@link GameSlot} 在 {@link Data#prepareSlot} **之后**调用 {@link #publish}，
 *       把 `{pid, vkey, slot, started, proc}` 落到 `app_hub/instances/&lt;pid&gt;.json`；</li>
 *   <li>退出时没有任何可靠回调（游戏 `System.exit` / 被 SIGKILL 都没有）⇒ 读侧做
 *       **进程存活校验**，失效条目读到就删（{@link #live}）；</li>
 *   <li>文件名的 pid 同时做幂等键：同一进程重复 {@link #publish} 不会累积；</li>
 *   <li>pid 复用：除了 pid 存活，还要求 `proc` 仍是**游戏进程**（{@link Data#isGameProcessName}）。</li>
 * </ul>
 *
 * <p>⚠️ **保守方向**：拿不到进程列表（ROM 限制）时把登记项当"还在跑"——
 * 宁可多拦一次写，也不能在游戏写盘时插一脚。
 *
 * <p>⚠️ **缓存 200 ms**：一次动作里门禁可能被查好几次（UI 预检 + core 兜底），缓存只为省
 * binder 调用；`:game` 的 {@link #publish} 在另一个进程，靠 TTL 生效。
 *
 * <p>⚠️ 与 {@link Data#gameAlive} 的分工：`gameAlive` 仍是"有没有游戏进程"的**全局**判据，
 * 只留给迁移 / 回收 / 杀进程这类"动全局资源"的动作；**写实例数据一律问 {@link WriteGate}**。
 */
final class Instances {

    private static final String TAG = "MDTLauncher";

    /** `app_hub/instances/`（内部存储：主进程与 `:game` 都能读写） */
    private static final String DIR = "instances";

    /** 门禁查询缓存（见类注释；只影响 binder 次数，不影响判据正确性） */
    private static final long CACHE_TTL_MS = 200L;

    /**
     * 启动占位的有效期（第 366 轮）。publish 一般 <2 s 就发生；给慢机冷启动留足余量，
     * 又不至于把"点了一次但没起来"的槽挡太久。过期后自动失效 + 回到界面时会被清扫。
     */
    static final long RESERVE_TTL_MS = 30000L;

    /** `app_hub/instances/` 下放**启动占位**的子目录（与真实登记分开，扫描互不干扰）。 */
    private static final String RESERVED = "reserved";

    private static volatile List<Running> sCache;
    private static volatile long sCacheAt;

    /** 占位文件名的自增序号（同毫秒连点也不撞名） */
    private static int sReserveSeq;

    /** 自检专用：不为 null 时直接当登记表（见 {@link #testSnapshot}） */
    private static volatile List<Running> sTestSnapshot;

    private Instances() {}

    // ── 登记项 ────────────────────────────────────────────────────────────

    /** 一个正在跑的实例：pid + 版本 key + 槽（实例身份 = 后两者，见 {@link Versions#instanceKey}）。 */
    static final class Running {
        final int pid;
        final String vkey;
        final String slot;
        final long started;
        /** 进程名（校验 pid 复用：必须仍是 `pkg:game` / `pkg:gameN`） */
        final String proc;

        Running(int pid, String vkey, String slot, long started, String proc) {
            this.pid = pid;
            this.vkey = vkey == null ? "" : vkey;
            this.slot = slot == null ? "" : slot;
            this.started = started;
            this.proc = proc == null ? "" : proc;
        }

        /** 实例键（与补丁启用状态等按实例存的状态同源） */
        String instanceKey() {
            return Versions.instanceKey(vkey, slot);
        }

        String label() {
            return (vkey.isEmpty() ? "?" : vkey) + " / " + (slot.isEmpty() ? "?" : slot);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("pid", pid);
                o.put("vkey", vkey);
                o.put("slot", slot);
                o.put("started", started);
                o.put("proc", proc);
            } catch (Throwable ignored) {
                // 字段全是基本类型 / String —— 实际不会失败；失败也只会让这条登记作废（读侧当坏文件删）。
            }
            return o;
        }

        /** 解析失败一律返回 null（坏文件当"没有"，由扫描侧删掉）。 */
        static Running parse(String text) {
            try {
                JSONObject o = new JSONObject(text);
                int pid = o.optInt("pid", -1);
                String vkey = o.optString("vkey", "");
                String slot = o.optString("slot", "");
                String proc = o.optString("proc", "");
                if (pid <= 0 || slot.isEmpty()) return null;
                return new Running(pid, vkey, slot, o.optLong("started", 0L), proc);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** 进程存活校验（抽成接口只为让自检能喂假数据，见 {@link #scan(File, Live)}）。 */
    interface Live {
        boolean alive(int pid, String proc);
    }

    // ── 写（:game 进程） ────────────────────────────────────────────────────

    /**
     * 登记"我（当前进程）正在跑这个实例"。**只该由 `:game` 进程调用**：
     * {@link GameSlot} 在 {@link Data#prepareSlot} 之后、拉起游戏之前调。
     *
     * <p>幂等（文件名 = pid）；失败只记日志 —— 登记不上不该挡住启动。
     */
    static void publish(Context ctx, String vkey, String slot) {
        if (slot == null || slot.trim().isEmpty()) return;
        Running r = new Running(android.os.Process.myPid(), vkey, slot,
                System.currentTimeMillis(), Util.processName());
        try {
            File d = dir(ctx);
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                Log.w(TAG, "instance publish: cannot mkdir " + d);
                return;
            }
            Util.atomicWriteText(new File(d, r.pid + ".json"), r.toJson().toString());
            // 第 366 轮：真实登记接管 ⇒ 主进程那条启动占位立刻收掉（门禁不留双份判据）
            clearReservations(ctx, vkey, slot);
            sCache = null;
            Log.i(TAG, "instance published: " + r.label() + " pid=" + r.pid);
        } catch (Throwable t) {
            Log.w(TAG, "instance publish failed: " + t);
        }
    }

    /** 撤销本进程的登记（启动失败 / 正常退出时兜底；跨进程失效靠存活校验）。 */
    static void withdraw(Context ctx) {
        withdrawPid(ctx);
        sCache = null;
    }

    /**
     * 撤销**这一局**的登记 + 清掉它对应的启动占位（启动失败时由 `:game` 调，第 366 轮）。
     * vkey / slot 不知道时传 null / 空 ⇒ 只销号，不动占位（保守，别误清别人的）。
     */
    static void withdraw(Context ctx, String vkey, String slot) {
        withdrawPid(ctx);
        clearReservations(ctx, vkey, slot);
        sCache = null;
    }

    private static void withdrawPid(Context ctx) {
        try {
            File f = new File(dir(ctx), android.os.Process.myPid() + ".json");
            if (f.isFile()) f.delete();
        } catch (Throwable t) {
            Log.w(TAG, "instance withdraw failed: " + t);
        }
    }

    // ── 读（主进程 / 任何进程） ─────────────────────────────────────────────

    /** 当前所有**还活着**的实例（含未过期的启动占位）；失效条目顺手删掉。 */
    static List<Running> live(final Context ctx) {
        List<Running> test = sTestSnapshot;
        if (test != null) return test;

        long now = System.currentTimeMillis();
        List<Running> cache = sCache;
        if (cache != null && now - sCacheAt <= CACHE_TTL_MS) return cache;

        List<Running> out = scan(dir(ctx), liveAdapter(ctx));
        // 第 366 轮：占位也算"这个槽被占" —— 补上 :game publish 之前门禁看不见的窗口。
        out.addAll(scanReserved(reservedDir(ctx), now));
        sortByStart(out);
        sCache = out;
        sCacheAt = now;
        return out;
    }

    private static Live liveAdapter(final Context ctx) {
        return new Live() {
            @Override public boolean alive(int pid, String proc) {
                return aliveNow(ctx, pid, proc);
            }
        };
    }

    private static void sortByStart(List<Running> out) {
        Collections.sort(out, new Comparator<Running>() {
            @Override public int compare(Running a, Running b) {
                if (a.started != b.started) return a.started < b.started ? -1 : 1;
                return a.pid == b.pid ? 0 : (a.pid < b.pid ? -1 : 1);
            }
        });
    }

    static File dir(Context ctx) {
        return new File(Paths.privateDir(ctx), DIR);
    }

    /**
     * 扫描一个登记目录：坏的 / 失效的条目**删掉**，返回仍活着的（按启动时间升序）。
     *
     * @param live 存活校验（生产用 {@link #aliveNow}；自检传假实现）
     */
    static List<Running> scan(File dir, Live live) {
        List<Running> out = new ArrayList<Running>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String text;
            try {
                text = Util.readText(f);
            } catch (Throwable t) {
                continue;                          // 读不了先留着（可能是别人正在原子写）
            }
            Running r = Running.parse(text);
            if (r == null) {
                f.delete();
                continue;
            }
            if (live == null || !live.alive(r.pid, r.proc)) {
                f.delete();                        // 进程没了 ⇒ 这是陈旧登记
                continue;
            }
            out.add(r);
        }
        sortByStart(out);
        return out;
    }

    // ── 启动占位（第 366 轮，主进程 startActivity 之前的"即将被占"） ─────────

    static File reservedDir(Context ctx) {
        return new File(dir(ctx), RESERVED);
    }

    /**
     * **启动占位**：主进程在 `startActivity` 之前先登记"这个槽即将被占"。
     *
     * <p>为什么需要：`:game` 的 {@link #publish} 在 `Data.prepareSlot`（那时已经改了全局槽指针）
     * 之后才发生 —— 从点下启动到 publish 的几百 ms 里，**槽正在被打开、代码正在读它的资产**，
     * 但登记表里还没有它 ⇒ 只看登记表的写门禁是看不见的。占位让写门禁从点下启动那一刻
     * 就拒写这个槽；真实登记 publish 成功后立刻把占位清掉。
     *
     * <p>不会永久挡住：超过 {@link #RESERVE_TTL_MS} 自动过期；回到界面时
     * {@link #sweepReservations} 还会清掉"明显起不来"的占位。同一槽只留一条（重试 / 连点幂等）。
     */
    static synchronized void reserve(Context ctx, String vkey, String slot) {
        if (slot == null || slot.trim().isEmpty()) return;
        try {
            File d = reservedDir(ctx);
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) return;
            clearReservations(ctx, vkey, slot);           // 同一槽旧的先清（连点 / 重试）
            long now = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("pid", android.os.Process.myPid());
            o.put("vkey", vkey == null ? "" : vkey);
            o.put("slot", slot);
            o.put("started", now);
            o.put("until", now + RESERVE_TTL_MS);
            Util.atomicWriteText(new File(d, "r-" + now + "-" + (sReserveSeq++) + ".json"),
                    o.toString());
            sCache = null;
            Log.i(TAG, "instance reserved: " + slot + " until+" + RESERVE_TTL_MS + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "instance reserve failed: " + t);
        }
    }

    /** 撤掉这一局的启动占位（Activity 没起来 / 启动失败时）。vkey / slot 至少给一个。 */
    static void unreserve(Context ctx, String vkey, String slot) {
        clearReservations(ctx, vkey, slot);
    }

    /**
     * 清掉匹配的占位：vkey / slot 非空才参与过滤；**两个都空 = 什么都不做**
     * （避免"不知道身份时误清别人的占位"）。
     */
    static void clearReservations(Context ctx, String vkey, String slot) {
        boolean byVkey = vkey != null && !vkey.isEmpty();
        boolean bySlot = slot != null && !slot.isEmpty();
        if (!byVkey && !bySlot) return;
        File d = reservedDir(ctx);
        File[] fs = d.isDirectory() ? d.listFiles() : null;
        if (fs == null) return;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".json")) continue;
            JSONObject o = readJson(f);
            if (o == null) {
                f.delete();
                continue;
            }
            if (byVkey && !vkey.equals(o.optString("vkey", ""))) continue;
            if (bySlot && !slot.equals(o.optString("slot", ""))) continue;
            f.delete();
        }
        sCache = null;
    }

    /**
     * 扫描占位目录（**不校验 pid**：占位的 pid 是主进程，活不活由 `until` 决定）。
     * 过期 / 坏条目顺手删；返回仍有效的（当成运行实例参与门禁）。
     */
    static List<Running> scanReserved(File dir, long now) {
        List<Running> out = new ArrayList<Running>();
        File[] fs = dir == null || !dir.isDirectory() ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".json")) continue;
            JSONObject o = readJson(f);
            if (o == null) {
                f.delete();
                continue;
            }
            long until = o.optLong("until", 0L);
            String slot = o.optString("slot", "");
            if (until <= now || slot.isEmpty()) {
                f.delete();
                continue;
            }
            out.add(new Running(o.optInt("pid", 0), o.optString("vkey", ""), slot,
                    o.optLong("started", 0L), ""));
        }
        sortByStart(out);
        return out;
    }

    /**
     * 回到界面时清扫"明显起不来"的占位：`publish` 成功会自己清，所以剩下超过 10 s
     * 还没有对应真实实例的，基本就是启动流程半路死了（复检拒绝 / Activity 没起来）。
     */
    static void sweepReservations(Context ctx) {
        File d = reservedDir(ctx);
        File[] fs = d.isDirectory() ? d.listFiles() : null;
        if (fs == null) return;
        long now = System.currentTimeMillis();
        List<Running> real = scan(dir(ctx), liveAdapter(ctx));
        boolean changed = false;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".json")) continue;
            JSONObject o = readJson(f);
            if (o == null) {
                f.delete();
                changed = true;
                continue;
            }
            long until = o.optLong("until", 0L);
            long started = o.optLong("started", 0L);
            String slot = o.optString("slot", "");
            String vkey = o.optString("vkey", "");
            boolean expired = until <= now;
            boolean likelyFailed = now - started > 10000L && runningOn(real, vkey, slot) == null;
            if (expired || likelyFailed) {
                f.delete();
                changed = true;
            }
        }
        if (changed) sCache = null;
    }

    private static Running runningOn(List<Running> list, String vkey, String slot) {
        if (list == null || slot == null || slot.isEmpty()) return null;
        for (Running r : list) {
            if (r == null || !slot.equals(r.slot)) continue;
            if (vkey == null || vkey.isEmpty() || vkey.equals(r.vkey)) return r;
        }
        return null;
    }

    private static JSONObject readJson(File f) {
        try {
            return new JSONObject(Util.readText(f));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 进程还活着吗？判据（按可靠性递减）：
     * <ol>
     *   <li>`ActivityManager.getRunningAppProcesses()` 里能按 **pid + 进程名** 命中
     *       ⇒ 活（要求名字仍是游戏进程，防 pid 复用成主进程 / `:probe`）；</li>
     *   <li>列表拿得到但没这个 pid ⇒ 死（删登记）；</li>
     *   <li>列表拿不到（ROM 限制 / 系统异常）⇒ **当活**（保守；见类注释）。</li>
     * </ol>
     */
    private static boolean aliveNow(Context ctx, int pid, String proc) {
        if (pid <= 0) return false;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
                if (ps != null) {
                    for (ActivityManager.RunningAppProcessInfo p : ps) {
                        if (p != null && p.pid == pid) {
                            // 登记项没记到进程名（极老版本 / 取进程名失败）时退化成"pid + 仍是游戏进程"；
                            // 记了就必须逐字相等（防 pid 复用成主进程 / :probe）。
                            return Data.isGameProcessName(ctx, p.processName)
                                    && (proc == null || proc.isEmpty() || proc.equals(p.processName));
                        }
                    }
                    return false;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "instance liveness probe failed: " + t);
        }
        return true;                               // 拿不到列表 ⇒ 保守当活
    }

    // ── 自检专用 ──────────────────────────────────────────────────────────

    /**
     * **自检专用**：把登记表换成给定快照（不读写磁盘）。生产代码从不需要它 ——
     * 自检要在"本进程不是 `:game`"的前提下测门禁，只能这样伪造"某槽有实例在跑"。
     * ⚠️ 用完必须 {@link #clearTestSnapshot}（自检侧用 try/finally 兜住）。
     */
    static void testSnapshot(List<Running> snapshot) {
        sTestSnapshot = snapshot;
        sCache = null;
    }

    static void clearTestSnapshot() {
        sTestSnapshot = null;
        sCache = null;
    }
}
