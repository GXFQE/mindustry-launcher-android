package io.mdt.launcher.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条只读内容快照（第 161 轮，插件接口 v5）。
 *
 * <p>它只是**数据**：文件 пути、大小、时间、来源、可读性与一组 {@link #extra} 元数据。
 * 要拿字节请交给 {@link PluginApi#open(ItemInfo)} —— 槽内文件、APK / 模组 zip 里的条目
 * 都走同一个入口，插件不必自己判断容器。
 *
 * <p>⚠️ {@link #path} 与 {@link #container} 可能为 null：
 * <ul>
 *   <li>槽内文件：{@code path} 非空，{@code container} / {@code entry} 为 null；</li>
 *   <li>包内条目：{@code path} 为 null，{@code container} 是包，{@code entry} 是条目名；</li>
 *   <li>模组目录：{@code path} 是目录，{@link #directory} 为 true。</li>
 * </ul>
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public final class ItemInfo {

    public static final String KIND_SAVES = "saves";
    public static final String KIND_MAPS = "maps";
    public static final String KIND_MODS = "mods";
    public static final String KIND_BLUEPRINTS = "blueprints";
    public static final String KIND_SLOT = "slot";

    /** 内容类别：{@code saves} / {@code maps} / {@code mods} / {@code blueprints} / {@code slot}。 */
    public final String kind;

    /** 列表第一行用的名字（文件名 / 条目名 / 模组显示名）。 */
    public final String title;

    /** 列表第二行用的说明；没有时为空串。 */
    public final String subtitle;

    /** 槽内文件的绝对路径；包内条目为 null。 */
    public final String path;

    /** 包内条目所在的 APK / zip 绝对路径；槽内文件为 null。 */
    public final String container;

    /** 包内条目名；槽内文件为 null。 */
    public final String entry;

    /** 字节数（目录为 0）。 */
    public final long size;

    /** 最后修改时间（毫秒）；拿不到为 0。 */
    public final long modified;

    /** 人读的来源（本槽 / 游戏自带 / 模组名 / 模组目录）；可能是空串。 */
    public final String source;

    /** 读不出来的原因；可读时为 null。 */
    public final String error;

    /** 是不是目录（模组目录）。 */
    public final boolean directory;

    /** 这个条目现在能不能被 {@link PluginApi#open(ItemInfo)} 打开。 */
    public final boolean readable;

    /** 结构化元数据（地图宽高 / 作者，蓝图尺寸 / 方块数，模组版本 / 依赖……），只读。 */
    public final Map<String, String> extra;

    public ItemInfo(String kind, String title, String subtitle, String path, String container,
                    String entry, long size, long modified, String source, String error,
                    boolean directory, boolean readable, Map<String, String> extra) {
        this.kind = kind == null ? "" : kind;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.path = path;
        this.container = container;
        this.entry = entry;
        this.size = size;
        this.modified = modified;
        this.source = source == null ? "" : source;
        this.error = error;
        this.directory = directory;
        this.readable = readable;
        this.extra = extra == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(extra));
    }

    @Override public String toString() {
        return "ItemInfo{" + kind + ", " + title + (path == null ? "" : ", " + path) + "}";
    }
}
