package io.mdt.launcher;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

import java.io.File;

/**
 * **`launchid.dat` 的启动自愈**（第 179 轮，杀后台 P2）。
 *
 * <p>背景（2026-10-19 真机排查）：普通上滑 / 任务移除走 finishing pause →
 * `finishLaunch()`，游戏自己会删 `launchid.dat`；但 **force-stop / 清理器 / 系统 LMK**
 * 是 SIGKILL，没有生命周期 ⇒ marker 留着。下次启动 `Vars.checkLaunch()` 判定
 * "上次没正常结束" ⇒ `skipModLoading`，整个槽的模组都不加载，还把模组写成
 * `-enabled=false -failed=true`。
 *
 * <p>本类只处理**最保守的一档**：marker 在、游戏没在跑、**没有比 marker 更新的崩溃报告**
 * ⇒ 判定为"用户/清理器杀掉的"，清掉 marker，让下次启动照常加载。
 *
 * <p>🔴 **有崩溃报告就一个字都不动**：那可能是真崩（或崩了但报告没写成的情况也轮不到
 * 这里猜）—— 留给用户看崩溃页 / 手动"忽略并重试"。这条判据与 {@link CrashAlert} 同源：
 * 报告文件名 = 写入毫秒时间戳，比 marker 新 = 本次启动崩过。
 *
 * <p>🔴 **熔断**：同一个槽最多自动清 {@link #MAX_CLEARS} 次；正常启动（marker 已不在）
 * 会把计数清掉。这样"清了又崩、崩了又清"不会无限循环；真崩时报告会先挡住，
 * 没报告的重复 force-stop 也有上限。
 *
 * <p>⚠️ 只在**主进程启动**、`Data.gameAlive` 为 false 时调用（见
 * {@link Recovery#runNow}）。游戏在跑时绝不碰。
 */
final class LaunchMarker {
    private static final String TAG = "MDTLauncher";
    private static final String STATE = "launchid-autoclear.json";

    /** 同一个槽最多自动清几次（正常启动会重置） */
    static final int MAX_CLEARS = 3;

    /** 计数过期：超过这么久没再清过，视为新的一轮（避免几个月后仍被旧计数挡住） */
    private static final long COUNT_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private LaunchMarker() {}

    /**
     * 纯判据（自检直接喂）：
     * <ul>
     *   <li>marker 不在 / 游戏在跑 / 时间戳读不出来 ⇒ 不清；</li>
     *   <li>有**比 marker 更新**的崩溃报告 ⇒ 不清（真崩，留给用户）；</li>
     *   <li>已经清满 {@link #MAX_CLEARS} 次 ⇒ 不清（熔断）。</li>
     * </ul>
     */
    static boolean canClear(boolean marker, boolean gameAlive, long markerAt,
                            long newestReportAt, int clears) {
        if (!marker || gameAlive) return false;
        if (markerAt <= 0L) return false;
        if (newestReportAt > markerAt) return false;
        return clears >= 0 && clears < MAX_CLEARS;
    }

    /**
     * 看一眼当前槽；该清就清。返回一行人读摘要（没动手返回 null）。
     * 调用点：{@link Recovery#runNow}（主进程启动、且没有游戏在跑）。
     */
    static String maybeClear(Context ctx, String slot) {
        if (slot == null || slot.trim().isEmpty()) return null;
        File lid = Mods.launchIdFileOf(ctx, slot);
        if (lid == null || !lid.isFile()) {
            // 上次正常退出（或根本没跑过）⇒ 这一轮结束，把熔断计数归零
            resetIfSame(ctx, slot);
            return null;
        }
        if (Data.gameAlive(ctx)) return null;      // 游戏在跑：marker 就是它这次启动写的

        long markerAt = lid.lastModified();
        File dir = CrashAlert.crashesDir(ctx, slot);
        String newest = CrashAlert.newestName(dir);
        long reportAt = 0L;
        if (!newest.isEmpty()) {
            File rf = new File(dir, newest);
            reportAt = rf.lastModified();
        }
        int clears = countOf(ctx, slot);
        if (!canClear(true, false, markerAt, reportAt, clears)) {
            if (reportAt > markerAt) {
                Log.i(TAG, "launchid.dat kept: newer crash report " + newest);
            } else if (clears >= MAX_CLEARS) {
                Log.i(TAG, "launchid.dat kept: auto-clear limit reached (" + clears + ")");
            }
            return null;
        }
        if (!lid.delete()) {
            Log.w(TAG, "launchid.dat delete failed: " + lid);
            return null;
        }
        int n = clears + 1;
        saveCount(ctx, slot, n);
        Log.i(TAG, "launchid.dat cleared (slot=" + slot + ", attempt " + n
                + "/" + MAX_CLEARS + ", no newer crash report)");
        return "cleared launchid.dat for slot \"" + slot + "\" (attempt " + n
                + "/" + MAX_CLEARS + "; no newer crash report)\n";
    }

    // ── 熔断计数（`app_hub/launchid-autoclear.json`，只记当前槽一条） ─────

    private static int countOf(Context ctx, String slot) {
        JSONObject o = readState(ctx);
        if (o == null) return 0;
        if (!slot.equals(o.optString("slot", ""))) return 0;
        long at = o.optLong("at", 0L);
        if (at > 0L && System.currentTimeMillis() - at > COUNT_TTL_MS) return 0;
        return Math.max(0, o.optInt("count", 0));
    }

    private static void saveCount(Context ctx, String slot, int count) {
        try {
            JSONObject o = new JSONObject();
            o.put("slot", slot);
            o.put("count", count);
            o.put("at", System.currentTimeMillis());
            Util.atomicWriteText(new File(Paths.privateDir(ctx), STATE), o.toString());
        } catch (Throwable t) {
            Log.w(TAG, "launchid auto-clear state write failed", t);
        }
    }

    private static void resetIfSame(Context ctx, String slot) {
        JSONObject o = readState(ctx);
        if (o == null || !slot.equals(o.optString("slot", ""))) return;
        File f = new File(Paths.privateDir(ctx), STATE);
        if (f.isFile() && !f.delete()) Log.w(TAG, "launchid auto-clear state delete failed");
    }

    private static JSONObject readState(Context ctx) {
        try {
            return new JSONObject(Util.readText(new File(Paths.privateDir(ctx), STATE)));
        } catch (Throwable t) {
            return null;                         // 不存在 / 坏 JSON 都当没有
        }
    }
}
