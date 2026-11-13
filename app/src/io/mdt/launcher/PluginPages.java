package io.mdt.launcher;

import android.content.Context;
import android.content.Intent;

/**
 * **v40**：`PluginApi.openPage` 的唯一实现 —— 页面名 → 宿主 Activity 的映射。
 *
 * <p>为什么单独一个类：映射写错时编译器不响（真机只表现为"点了没反应 / 进错页"），
 * 抽成纯函数才能让自检直接断言「page → 哪个 Activity + 有没有带 slot」。
 *
 * <p>★ 只开放**不需要额外上下文**的页面：版本页 / 补丁页要版本 key，本接口不带 ⇒ 不开放。
 * 认不出的名字返回 {@code null}（调用方静默跳过，不崩、不弹窗）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
final class PluginPages {

    private PluginPages() { }

    /** 这些页面要"哪一份"（槽）；主界面 / 槽列表 / 设置 / 日志 / 中转站 / 插件页不要。 */
    static boolean needsSlot(String page) {
        return "slot".equals(page) || "saves".equals(page) || "maps".equals(page)
                || "mods".equals(page) || "blueprints".equals(page);
    }

    /**
     * 页面名 + 槽 ⇒ 一个可以直接 {@code startActivity} 的 Intent；不认识 ⇒ {@code null}。
     *
     * <p>槽 extra 统一用 {@code "slot"}（四个内容页与 SlotActivity 的常量都是它，见
     * {@code DeepLink} 里同一条注释）。
     *
     * <p>★ 网络/接口文档承诺「slot 空 = 当前槽」⇒ 这里必须补成当前槽（第 374 轮评审 B1）：
     * 四个内容页与槽页收到空槽会**立刻 finish**，插件看到的是"点了没反应"；
     * 只有模组页自己回落当前槽 —— 同一个接口不能两种行为。
     */
    static Intent intent(Context c, String page, String slot) {
        if (c == null || page == null) return null;
        String p = page.trim();
        String s = Data.sanitizeSlot(slot == null ? "" : slot.trim());
        if (s == null) s = "";
        Class<?> cls;
        if ("main".equals(p)) cls = MainActivity.class;
        else if ("slots".equals(p)) cls = SlotsActivity.class;
        else if ("slot".equals(p)) cls = SlotActivity.class;
        else if ("saves".equals(p)) cls = SavesActivity.class;
        else if ("maps".equals(p)) cls = MapsActivity.class;
        else if ("mods".equals(p)) cls = ModsActivity.class;
        else if ("blueprints".equals(p)) cls = BlueprintsActivity.class;
        else if ("settings".equals(p)) cls = SettingsActivity.class;
        else if ("logs".equals(p)) cls = LogActivity.class;
        else if ("trash".equals(p)) cls = TrashActivity.class;
        else if ("plugins".equals(p)) cls = PluginsActivity.class;
        else return null;
        Intent i = new Intent(c, cls);
        if (needsSlot(p)) {
            if (s.isEmpty()) {
                String cur = Data.currentSlot(c);
                s = cur == null ? "" : cur;
            }
            i.putExtra("slot", s);
        }
        return i;
    }
}
