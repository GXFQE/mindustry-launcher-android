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

    private static volatile List<Running> sCache;
    private static volatile long sCacheAt;

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
            sCache = null;
            Log.i(TAG, "instance published: " + r.label() + " pid=" + r.pid);
        } catch (Throwable t) {
            Log.w(TAG, "instance publish failed: " + t);
        }
    }

    /** 撤销本进程的登记（启动失败 / 正常退出时兜底；跨进程失效靠存活校验）。 */
    static void withdraw(Context ctx) {
        try {
            File f = new File(dir(ctx), android.os.Process.myPid() + ".json");
            if (f.isFile()) f.delete();
            sCache = null;
        } catch (Throwable t) {
            Log.w(TAG, "instance withdraw failed: " + t);
        }
    }

    // ── 读（主进程 / 任何进程） ─────────────────────────────────────────────

    /** 当前所有**还活着**的实例；失效条目顺手删掉。 */
    static List<Running> live(final Context ctx) {
        List<Running> test = sTestSnapshot;
        if (test != null) return test;

        long now = System.currentTimeMillis();
        List<Running> cache = sCache;
        if (cache != null && now - sCacheAt <= CACHE_TTL_MS) return cache;

        List<Running> out = scan(dir(ctx), new Live() {
            @Override public boolean alive(int pid, String proc) {
                return aliveNow(ctx, pid, proc);
            }
        });
        sCache = out;
        sCacheAt = now;
        return out;
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
        Collections.sort(out, new Comparator<Running>() {
            @Override public int compare(Running a, Running b) {
                if (a.started != b.started) return a.started < b.started ? -1 : 1;
                return a.pid == b.pid ? 0 : (a.pid < b.pid ? -1 : 1);
            }
        });
        return out;
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
