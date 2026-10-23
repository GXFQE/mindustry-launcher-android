package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
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
 *
 * <p>⚠️ 交付边界（写进 `docs/PLUGIN-API.md`）：
 * <ul>
 *   <li>`boot` = 每个进程一次（第一次回到界面）；`app-foreground` = 每次从后台回来。</li>
 *   <li>`game-exit`：正常返回走"本进程刚启动过游戏"（{@link #sSession}）；
 *       启动器在游戏期间被杀 ⇒ 重启后看落盘标记 {@code event-pending.txt} 补报，
 *       但**游戏还在跑时不补报**（用户只是回管理器看一眼）。</li>
 *   <li>回调在 **UI 线程**执行（可以用 {@code confirm} / {@code toast}）；异常被吞进
 *       `MDTPlugin` 日志，一个坏插件不影响别的插件和启动流程。</li>
 *   <li>每次派发都会 {@code load} 一份插件（DexClassLoader）+ 跑完 {@code unload}；
 *       适合"启动前/退出后做一件事"，**不适合**当常驻监听 —— 常驻请用 v22 的
 *       {@code submit()} 任务。</li>
 * </ul>
 */
final class PluginEvents {

    static final String GAME_START = "game-start";
    static final String GAME_EXIT = "game-exit";
    /** 进程起来后第一次回到界面（每个进程一次）。 */
    static final String BOOT = "boot";
    /** 每次从后台回到前台（含 boot 那一次）。 */
    static final String APP_FOREGROUND = "app-foreground";

    /** 落盘标记：游戏开着、退出事件还没报（启动器可能被杀）。 */
    private static final String PENDING = "event-pending.txt";

    /** 本进程是否刚启动过游戏（正常返回时用它区分"该报退出"）。 */
    private static boolean sSession;
    private static String sSessionSlot;
    private static String sSessionVersion;
    /** 界面是否在前台（{@link BaseActivity} 的 onStart/onStop 维护）。 */
    private static boolean sForeground;
    private static boolean sBooted;

    private PluginEvents() { }

    /** 游戏**即将**启动（点"启动"、兼容性复检通过之后，`startActivity` 之前）。 */
    static void gameStart(Context ctx, String slot, String version) {
        sSession = true;
        sSessionSlot = slot;
        sSessionVersion = version;
        notePending(ctx, slot, version);
        dispatch(ctx, GAME_START, slot, data(GAME_START, slot, version));
    }

    /**
     * 任一界面进入前台：先把"上一局结束了"报掉，再报 {@code boot} / {@code app-foreground}。
     *
     * <p>为什么游戏退出在这里判（而不是 MainActivity.onResume）：
     * 正常返回 ⇒ 本进程 {@link #sSession} 为真 ⇒ 报 {@code game-exit}；
     * 启动器在游戏期间被杀、重启后 {@code sSession} 是假的 ⇒ 看落盘标记 + 游戏进程是否还活着，
     * 还活着就等下一次前台，死了就补报一条（{@code backfill=1}）。
     */
    static void onForeground(Context ctx) {
        if (ctx == null || sForeground) return;
        sForeground = true;
        String slot = Data.currentSlot(ctx);
        if (sSession) {
            sSession = false;
            clearPending(ctx);
            dispatch(ctx, GAME_EXIT, sSessionSlot == null ? slot : sSessionSlot,
                    data(GAME_EXIT, sSessionSlot == null ? slot : sSessionSlot, sSessionVersion));
        } else {
            backfillExit(ctx);
        }
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

    /**
     * 启动器被杀过：`event-pending.txt` 还在且游戏进程已经没了 ⇒ 补报一条 `game-exit`。
     *
     * <p>⚠️ 游戏还在跑（用户只是想回管理器看一眼）⇒ **不报**、标记留着，等下一次前台。
     */
    private static void backfillExit(Context ctx) {
        File f = pendingFile(ctx);
        if (f == null || !f.isFile()) return;
        try {
            if (Data.gameAlive(ctx)) return;
            String text = readText(f);
            String slot = valueOf(text, "slot");
            String version = valueOf(text, "version");
            Map<String, String> m = data(GAME_EXIT, slot, version);
            m.put("backfill", "1");
            dispatch(ctx, GAME_EXIT, slot, m);
            f.delete();
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event backfill failed", t);
        }
    }

    private static File pendingFile(Context ctx) {
        File d = Plugins.dirNoCreate(ctx);
        return d == null ? null : new File(d, PENDING);
    }

    private static void notePending(Context ctx, String slot, String version) {
        try {
            File f = pendingFile(ctx);
            if (f == null) return;
            File d = f.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) return;
            writeText(f, "slot=" + oneLine(slot) + "\nversion=" + oneLine(version) + "\n");
        } catch (Throwable t) {
            Log.w("MDTPlugin", "event pending write failed", t);
        }
    }

    private static void clearPending(Context ctx) {
        try {
            File f = pendingFile(ctx);
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) { }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
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
                    deliver(ctx, apk, it.entry, event, slot, data, id);
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
