package io.mdt.launcher.api;

import android.view.View;

/**
 * **v41**：宿主组件工具箱（{@link PluginApi#ui()}）—— 自绘插件用**宿主的零件**拼界面。
 *
 * <p>为什么要有它：插件自己拿 {@code new TextView(...)} 画出来的东西，字号 / 颜色 / 行高 / 圆角
 * 都跟宿主不一样，用户一眼就看出"这是插件的东西"。这些方法造出来的 View 全部用宿主的布局资源与
 * 主题色，跟宿主自己的卡片 / 行动作长得一样（"装上去是无感的"）。
 *
 * <p>★ 规则：
 * <ul>
 *   <li>**主线程调用**（造 View 本身就要在主线程）；</li>
 *   <li>返回的 View 归插件所有：插件负责 add 到自己的容器里、需要时 setOnClickListener；</li>
 *   <li>不要给它们再套一层自己的背景（宿主零件自带）；竖排就用 {@link #card()} 当容器；</li>
 *   <li>文字仍走插件自己的语言包 / 文案 —— 这里只给形状与颜色。</li>
 * </ul>
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public interface PluginUi {

    /** 竖向卡片容器（宿主卡片底色 / 圆角；内部自己留行距）。 */
    View card();

    /** 一行动作（宿主操作行样式：图标 + 标题 + 副标题 + 右箭头；可点）。 */
    View row(String title, String subtitle);

    /** 一行只读信息（同 {@link #row}，但不带箭头、不可点）。 */
    View rowStatic(String title, String subtitle);

    /** 主按钮（实底 accent、白字、居中）。 */
    View button(String text);

    /** 1dp 分割线（左侧缩进与宿主操作行对齐）。 */
    View divider();

    /** 键值行：左侧标签（次要色）+ 右侧值（可换行）。 */
    View kv(String label, String value);

    /** 小标题（12sp 次要色）。 */
    View sectionTitle(String text);

    /** 普通正文（13sp；{@code muted} = 次要色）。 */
    View text(String text, boolean muted);
}
