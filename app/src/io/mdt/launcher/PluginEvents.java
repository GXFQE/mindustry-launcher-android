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
    /** 界面是否在前台（{@link BaseActivity} 的 onStart/onStop 维护）。 */
    private static boolean sForeground;
    private static boolean sBooted;

    private PluginEvents() { }

    /** 游戏**即将**启动（点"启动"、兼容性复检通过之后，`startActivity` 之前）。 */
    static void gameStart(Context ctx, String slot, String version) {
        if (slot == null || slot.trim().isEmpty()) return;
        sSessions.put(slot, version == null ? "" : version);
        notePending(ctx, slot, version);
        dispatch(ctx, GAME_START, slot, data(GAME_START, slot, version));
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
        java.util.Iterator<Map.Entry<String, String>> it = sSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            String s = e.getKey();
            if (WriteGate.blockedSlot(ctx, s)) continue;     // 这局还在跑 ⇒ 等它真退
            it.remove();
            clearPending(ctx, s);
            dispatch(ctx, GAME_EXIT, s, data(GAME_EXIT, s, e.getValue()));
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
