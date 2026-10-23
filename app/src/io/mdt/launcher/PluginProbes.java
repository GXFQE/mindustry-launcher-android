package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 从已启用插件的自述里读探针声明（v23）。
 *
 * <pre>
 * "telemetry": { "probes": [ { "class": "...", "sec": 5, "inProcess": true } ] }
 * </pre>
 *
 * <p>纯解析（自检直接喂 JSON 文本钉它）；坏条目只丢自己，不影响别的插件。
 */
final class PluginProbes {

    /** 一条探针声明。 */
    static final class Spec {
        final String pluginId, ver, cls, pluginName;
        final int sec;
        final boolean inProcess;

        Spec(String pluginId, String ver, String pluginName, String cls, int sec, boolean inProcess) {
            this.pluginId = pluginId;
            this.ver = ver;
            this.pluginName = pluginName;
            this.cls = cls;
            this.sec = sec;
            this.inProcess = inProcess;
        }
    }

    private PluginProbes() { }

    /** 已启用插件里所有探针声明（按 active.txt 顺序）。 */
    static List<Spec> all(Context ctx) {
        List<Spec> out = new ArrayList<Spec>();
        if (ctx == null) return out;
        try {
            String active = Plugins.active(ctx);
            for (String id : Plugins.stateIds(active)) {
                String ver = Plugins.stateGet(active, id);
                if (ver == null || ver.isEmpty()) continue;
                File apk = Plugins.apkOf(ctx, id, ver);
                if (apk == null || !apk.isFile()) continue;
                String json = PluginMeta.zipText(apk, Extensions.ASSET);
                out.addAll(parse(id, ver, Plugins.displayNameOf(ctx, id), json));
            }
        } catch (Throwable ignored) {
            // 读不到就当没有探针（绝不影响启动）
        }
        return out;
    }

    /**
     * 解析一份 plugin.json 里的 {@code telemetry.probes}（**纯函数**）。
     *
     * @param json 整份 plugin.json 文本（不是数组）
     */
    static List<Spec> parse(String pluginId, String ver, String pluginName, String json) {
        List<Spec> out = new ArrayList<Spec>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject tel = root.optJSONObject("telemetry");
            if (tel == null) return out;
            org.json.JSONArray arr = tel.optJSONArray("probes");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String cls = o.optString("class", "").trim();
                if (cls.isEmpty()) continue;                 // 半条记录不进反射
                int sec = o.optInt("sec", 5);
                if (sec < 1) sec = 1;
                if (sec > 3600) sec = 3600;
                boolean inProc = o.optBoolean("inProcess", true);
                out.add(new Spec(pluginId, ver, pluginName, cls, sec, inProc));
            }
        } catch (Throwable ignored) {
            return new ArrayList<Spec>();
        }
        return out;
    }

    /**
     * 启用前要不要弹"进程内探针"风险告知（**纯函数**，自检直接喂两侧）。
     *
     * <p>只对**第三方 + 至少一条 inProcess** 弹：官方插件作者就是我们自己；隔离进程崩了不连带游戏。
     */
    static boolean needsRiskPrompt(String trust, List<Spec> specs) {
        if (!PluginTrust.THIRD.equals(trust) || specs == null) return false;
        for (Spec s : specs) {
            if (s.inProcess) return true;
        }
        return false;
    }
}
