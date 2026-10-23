package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import io.mdt.launcher.api.PluginApi;

/**
 * 插件**事件**派发（v22，第 221 轮）：`game-start` / `game-exit`。
 *
 * <p>声明写在自述里（不需要新接口方法，也不抬 API 版本）：
 * <pre>
 * "extensions": [
 *   { "id": "ev1", "entry": "com.example.MyPlugin", "event": "game-start" },
 *   { "id": "ev2", "entry": "com.example.MyPlugin", "event": "game-exit"  }
 * ]
 * </pre>
 * 入口方法：
 * <pre>
 * public static void onEvent(PluginApi api, String name, Map&lt;String,String&gt; data);
 * </pre>
 * {@code data} 目前有 {@code name} / {@code slot} / {@code version}（拿不到就不放这个键）。
 *
 * <p>⚠️ 交付边界（写进 `docs/PLUGIN-API.md`）：
 * <ul>
 *   <li>两个事件都**只在启动器进程活着**时交付：游戏期间启动器被系统杀 / 用户在最近任务里划掉
 *       ⇒ `game-exit` **不会**触发（计划中的"补报"还没做）。</li>
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

    private PluginEvents() { }

    /** 游戏**即将**启动（点"启动"、兼容性复检通过之后，`startActivity` 之前）。 */
    static void gameStart(Context ctx, String slot, String version) {
        dispatch(ctx, GAME_START, slot, data(GAME_START, slot, version));
    }

    /** 回到启动器、且上一件事是"启动游戏" ⇒ 游戏这一程结束（正常退出 / 切走都算）。 */
    static void gameExit(Context ctx, String slot, String version) {
        dispatch(ctx, GAME_EXIT, slot, data(GAME_EXIT, slot, version));
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
