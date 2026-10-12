package io.mdt.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件**扩展点声明**（第 139 轮 v1）：插件声明"我能往宿主哪些地方加什么条目"。
 *
 * <p>文件在插件 APK 里，路径 {@link #ASSET}；**每行一条**（制表符分段，与内置清单 / 登记表同款）：
 *
 * <pre>
 * id \t 标题 \t 副标题 \t 入口类名 \t 插槽(可省，缺省 plugins)
 * </pre>
 *
 * <ul>
 *   <li><b>为什么不塞进 {@code plugin.json}</b>：那个极简 JSON 解析器只吃**平铺对象**
 *       （第 135 轮踩过"深度 1 遇容器要丢键"），而"一台插件声明多条"天然是数组
 *       ⇒ 与其现造一个小状态机，不如沿用本工程已经解析过无数次的制表符行格式（可写注释、diff 友好）。</li>
 *   <li><b>坏条目只坑自己</b>：段数不对 / id 或入口类名为空 ⇒ **整行丢掉** ——
 *       绝不因为一行写坏就让整个插件页或其它条目消失（自检有元断言钉这条）。</li>
 *   <li><b>认不出的插槽 ⇒ 跳过</b>（宿主这一版没那个地方），但会在启动报告里记一行，
 *       免得"插件说它有这个功能、界面上却没有"变成无头案（第 134 轮"静默跳过"的教训）。</li>
 *   <li><b>v1 先没有图标字段</b>：宿主还没有一套"条目图标"，先别让插件写一个我们画不出来的名字。
 *       以后加字段 = **加法变更 ⇒ 不抬接口版本**（宿主忽略多余的第 6、7 段即可）。</li>
 * </ul>
 */
final class Extensions {

    /** 声明文件在插件 APK 里的路径。 */
    static final String ASSET = "assets/extensions.txt";

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
     * 解析（**纯函数**，自检直接喂文本钉它）。
     * 空行与 `#` 开头的注释行跳过；段数 < 4 或 id / 入口类名为空 ⇒ 丢掉那一行。
     */
    static List<Item> parse(String text) {
        List<Item> out = new ArrayList<Item>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            if (p.length < 4) continue;                       // 半条记录不许进反射
            String id = p[0].trim();
            String title = p[1].trim();
            String sub = p[2].trim();
            String entry = p[3].trim();
            String slot = (p.length >= 5 && !p[4].trim().isEmpty()) ? p[4].trim() : SLOT_PLUGINS;
            if (id.isEmpty() || entry.isEmpty()) continue;
            out.add(new Item(id, title, sub, entry, slot));
        }
        return out;
    }

    /** 读某个插件 APK 里的声明（没这个文件 ⇒ 空表 = 这台插件不提供条目，**不是错误**）。 */
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

    /** 这一版认得的插槽（别的名字 ⇒ 跳过；报告里会记）。 */
    static boolean knownSlot(String slot) {
        return SLOT_PLUGINS.equals(slot);
    }
}
