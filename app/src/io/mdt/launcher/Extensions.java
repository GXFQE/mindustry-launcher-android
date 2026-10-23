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

    /**
     * 第 159 轮（接口 v3）：**页面动作** —— 在页面里加一行。
     *
     * <p>第 160 轮（v4）起不再限于四个内容页：条目必须再写一个 {@code page}
     * （{@link #PAGE_MAIN} / {@link #PAGE_SLOT} / {@link #PAGE_MAP_DETAIL} …）；
     * 认不出的 page ⇒ 这一条不渲染（但解析时留着，便于报告 / 以后加页面）。
     */
    static final String SLOT_CONTENT = "content";

    /** 四个内容页（第 159 轮）。 */
    static final String PAGE_SAVES = "saves";
    static final String PAGE_MAPS = "maps";
    static final String PAGE_MODS = "mods";
    static final String PAGE_BLUEPRINTS = "blueprints";

    /** 其余页面（第 160 轮）。 */
    static final String PAGE_MAIN = "main";
    static final String PAGE_SLOTS = "slots";
    static final String PAGE_SLOT = "slot";
    static final String PAGE_SETTINGS = "settings";
    static final String PAGE_LOGS = "logs";
    static final String PAGE_TRASH = "trash";
    static final String PAGE_MAP_DETAIL = "map-detail";
    /**
     * 第 193 轮（v9）：**段落插槽** —— 插件把一段界面直接画进宿主页里。
     *
     * <p>与 {@link #SLOT_CONTENT}（画一行、点开是插件自己的页）的区别正是用户那句「希望能和原来效果一样」：
     * 地图统计原来长在地图详情页里，点开地图就看见；搬成插件后不该变成「再点一行、再跳一页」。</p>
     */
    static final String SLOT_SECTION = "section";
    /**
     * 第 197 轮（v12）：**动作插槽** —— 点了就在**当前页**跑插件，不单开页面。
     *
     * <p>用户 2026-10-21：「像原来那样不单开页面能行吗」—— 导出这种"选完就做事"的流程
     * 不该先跳一个空页；插件只要实现 {@code run(PluginApi)}，对话框 / SAF / 进度都落在原来的页上。</p>
     */
    static final String SLOT_ACTION = "action";
    static final String PAGE_BLUEPRINT_DETAIL = "blueprint-detail";

    /** 一条"能力条目"。 */
    static final class Item {
        final String id, title, subtitle, entry, slot, page;
        /** v23：事件名（`game-start` / `game-exit`）；空 = 不是事件条目。 */
        final String event;

        Item(String id, String title, String subtitle, String entry, String slot, String page) {
            this(id, title, subtitle, entry, slot, page, "");
        }

        Item(String id, String title, String subtitle, String entry, String slot, String page,
             String event) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.entry = entry;
            this.slot = slot;
            this.page = page;
            this.event = event == null ? "" : event;
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
                String page = trim(o.optString("page", ""));   // 只有 content 插槽用得上
                String event = trim(o.optString("event", ""));  // v23：事件扩展
                out.add(new Item(id, trim(o.optString("title", "")), trim(o.optString("subtitle", "")),
                        entry, slot, page, event));
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

    /**
     * 只要**某个内容页**的条目（**纯函数**，第 159 轮）。
     *
     * <p>判据三条缺一不可：插槽 == {@link #SLOT_CONTENT} · page 逐字相等 ·
     * 这个 page 是本版认得的（认不出的 page 不渲染，见 {@link #knownPage}）。
     */
    /** 这一页该画哪些**动作**（`slot: action`）：纯函数，顺序 = 声明顺序。 */
    static List<Item> forAction(List<Item> all, String page) {
        List<Item> out = new ArrayList<Item>();
        if (all == null || page == null || !knownPage(page)) return out;
        for (Item it : all) {
            if (!SLOT_ACTION.equals(it.slot)) continue;
            if (!page.equals(it.page)) continue;
            out.add(it);
        }
        return out;
    }

    /** 这一页该画哪些**段落**（`slot: section`）：纯函数，顺序 = 声明顺序。 */
    static List<Item> forSection(List<Item> all, String page) {
        List<Item> out = new ArrayList<Item>();
        if (all == null || page == null || !knownPage(page)) return out;
        for (Item it : all) {
            if (!SLOT_SECTION.equals(it.slot)) continue;
            if (!page.equals(it.page)) continue;
            out.add(it);
        }
        return out;
    }

    static List<Item> forPage(List<Item> all, String page) {
        List<Item> out = new ArrayList<Item>();
        if (all == null || page == null || !knownPage(page)) return out;
        for (Item i : all) {
            if (SLOT_CONTENT.equals(i.slot) && page.equals(i.page)) out.add(i);
        }
        return out;
    }

    /** 这一版认得的插槽（别的名字 ⇒ 跳过；启动报告里会记）。 */
    static boolean knownSlot(String slot) {
        return SLOT_PLUGINS.equals(slot) || SLOT_CONTENT.equals(slot)
                || SLOT_SECTION.equals(slot) || SLOT_ACTION.equals(slot);
    }

    /** 这一版认得的内容页 / 功能页（只有 content 插槽的条目用得上）。 */
    static boolean knownPage(String page) {
        return PAGE_SAVES.equals(page) || PAGE_MAPS.equals(page)
                || PAGE_MODS.equals(page) || PAGE_BLUEPRINTS.equals(page)
                || PAGE_MAIN.equals(page) || PAGE_SLOTS.equals(page)
                || PAGE_SLOT.equals(page) || PAGE_SETTINGS.equals(page)
                || PAGE_LOGS.equals(page) || PAGE_TRASH.equals(page)
                || PAGE_MAP_DETAIL.equals(page) || PAGE_BLUEPRINT_DETAIL.equals(page);
    }

    /** 这一条现在**真的会被渲染**吗（唯一的"用得上"判据，报告 / 提示都走它）。 */
    static boolean usable(Item i) {
        if (i == null || !knownSlot(i.slot)) return false;
        if (SLOT_CONTENT.equals(i.slot)) return knownPage(i.page);
        return true;
    }

    /**
     * 这个插件**声明了入口，但这一版一个都用不上**（判据：声明非空，而所有条目的
     * 插槽 / page 本版都不认得）。
     *
     * <p>★ 为什么要单独判：插件页原来对这种情况**什么都不显示** —— 插件装上了、启用了、
     * 启动时也照常注入，只是界面里不多出任何条目 ⇒ 用户会以为"没生效 / 装坏了"。
     *
     * <p>⚠️ 与"这个插件没声明任何入口"（完全正常，多数插件就是纯后台）分开 —— 那种不该提示。
     *
     * <p>⚠️ 第 159 轮起判据是"**有没有任何一条能用**"，不再看"在不在某个插槽里"：
     * 一个只声明了内容页动作的插件，在插件页也不该被说成"这一版不支持"。
     * 参数 {@code slot} 只为老调用点保留（**不再参与判断**）。
     */
    static boolean allUnknown(List<Item> all, String slot) {
        if (all == null || all.isEmpty() || slot == null) return false;
        for (Item i : all) if (usable(i)) return false;
        return true;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
