package io.mdt.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件**扩展点声明**（第 139 轮 v1；第 148 轮搬到 JSON）。
 *
 * <p>声明就写在插件自述里，与"我是谁"同一个文件（{@code assets/plugin.json}）：
 *
 * <pre>
 * {
 *   "entry": "com.x.Main", "name": "存档分析", "version": "1.2",
 *   "access": ["slots"],                                  ← 我要碰什么（第 148 轮 C2）
 *   "extensions": [                                       ← 我能提供什么
 *     { "id": "save-analyze", "title": "存档分析", "subtitle": "看看每个存档里有什么",
 *       "entry": "com.x.SavePage", "slot": "plugins" }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li><b>为什么改回 JSON</b>（第 139 轮选了"每行一条"的 txt）：那个决定的理由是
 *       "极简 JSON 解析器只吃平铺对象" —— 而这条理由**当时就不成立**：{@code org.json} 是
 *       **框架自带**的（`android.jar` 里就有），本工程 {@link Config} 早就在用它解嵌套数组。
 *       ⇒ 拿一个不该存在的限制当理由，就得改回来；扩展点的字段只会越来越多（图标 / 分组 /
 *       多插槽 / 权限），**能长的格式**才是对的。</li>
 *   <li><b>零兼容负担</b>：扩展点这个格式**没有任何发布版用过**（v0.4 在第 139 轮之前就发了）
 *       ⇒ 直接换掉旧格式，**不留双格式**（两套格式并存 = 判据两处必漂，本工程的老账）。</li>
 *   <li><b>坏条目只坑自己</b>：数组里某一条不是对象 / {@code id} 或 {@code entry} 空 ⇒
 *       **只丢那一条**；整个文件坏 / 没有 {@code extensions} ⇒ 空表（**不抛异常**）。</li>
 *   <li><b>认不出的插槽 ⇒ 跳过</b>（宿主这一版没那个地方）。</li>
 * </ul>
 */
final class Extensions {

    /** 自述文件（与入口类名、名字、access 同一个文件）。 */
    static final String ASSET = PluginMeta.ASSET_JSON;

    /**
     * v1 只有这一个插槽：**插件页**（插件能力总入口）。
     * 以后加插槽 = 在这里加常量（加法变更，不抬接口版本）。
     */
    static final String SLOT_PLUGINS = "plugins";

    /** 一条"能力条目"。 */
    static final class Item {
        final String id, title, subtitle, entry, slot;

        Item(String id, String title, String subtitle, String entry, String slot) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.entry = entry;
            this.slot = slot;
        }

        /** 界面上那个名字：标题空就回落 id（与插件名同一策略：**不印空白**）。 */
        String label() {
            return title == null || title.isEmpty() ? id : title;
        }
    }

    private Extensions() { }

    /**
     * 解析自述里的 {@code extensions}（**纯函数**，自检直接喂 JSON 文本钉它）。
     * 见类注释：坏条目只坑自己、整个坏了返回空表。
     */
    static List<Item> parse(String jsonText) {
        List<Item> out = new ArrayList<Item>();
        if (jsonText == null || jsonText.trim().isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(jsonText);
            JSONArray arr = root.optJSONArray("extensions");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;                        // 不是对象 ⇒ 丢这一条
                String id = trim(o.optString("id", ""));
                String entry = trim(o.optString("entry", ""));
                if (id.isEmpty() || entry.isEmpty()) continue;   // 半条记录不许进反射
                String slot = trim(o.optString("slot", ""));
                if (slot.isEmpty()) slot = SLOT_PLUGINS;
                out.add(new Item(id, trim(o.optString("title", "")), trim(o.optString("subtitle", "")),
                        entry, slot));
            }
        } catch (Throwable t) {
            return new ArrayList<Item>();                       // 自述坏了 ⇒ 当"没有扩展"，别崩页面
        }
        return out;
    }

    /** 读某个插件 APK 里的声明（没这个文件 / 没这一节 ⇒ 空表，**不是错误**）。 */
    static List<Item> of(File apk) {
        return parse(PluginMeta.zipText(apk, ASSET));
    }

    /** 只要某个插槽的条目（**纯函数**）。 */
    static List<Item> forSlot(List<Item> all, String slot) {
        List<Item> out = new ArrayList<Item>();
        if (all == null || slot == null) return out;
        for (Item i : all) if (slot.equals(i.slot)) out.add(i);
        return out;
    }

    /** 这一版认得的插槽（别的名字 ⇒ 跳过；启动报告里会记）。 */
    static boolean knownSlot(String slot) {
        return SLOT_PLUGINS.equals(slot);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
