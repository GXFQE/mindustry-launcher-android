package io.mdt.launcher.api;

/**
 * 一个槽的只读描述（第 161 轮，插件接口 v5；v42 起带统计）。
 *
 * <p>★ 字符串字段都是**数据**，不是界面文案：{@link #name} 就是槽名，
 * {@link #path} 是它的绝对目录。要读里面的内容请用
 * {@link PluginApi#contents(String, String)}，不要自己拼 {@code slot-<name>}：
 * 远古包的槽可能借住在 {@code files/} 下（{@link #legacy} 会标出来）。
 *
 * <p>★ v42 的统计字段（{@link #defaultSlot} / {@link #files} / {@link #bytes} /
 * {@link #mods} / {@link #maps} / {@link #saves} / {@link #blueprints}）是**调用那一刻量出来的**：
 * 宿主会遍历槽目录（文件数、字节数、四类内容数）—— 槽很大时不要在 UI 线程调
 * {@link PluginApi#slots()}，放 {@code runBackground}/{@code submit} 里。拿不到目录时统计为 0。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public final class SlotInfo {

    /** 槽名（例如 {@code default} / {@code test}）。 */
    public final String name;

    /** 槽目录的绝对路径；拿不到时为 null。 */
    public final String path;

    /** 是不是当前槽。 */
    public final boolean current;

    /** 这个槽的数据实际上住在 {@code files/} 下（远古包兼容），不是 {@code slot-<name>}。 */
    public final boolean legacy;

    /** **v42**：是不是**默认槽**（新建启动项 / 没指定槽时落到它；与"当前槽"不是一回事）。 */
    public final boolean defaultSlot;

    /** **v42**：槽目录里的文件数（递归；量不出来为 0）。 */
    public final int files;

    /** **v42**：槽目录的字节数（递归；量不出来为 0）。 */
    public final long bytes;

    /** **v42**：四类内容数 —— 模组 / 地图 / 存档 / 蓝图。 */
    public final int mods, maps, saves, blueprints;

    /** 老构造器（v5）：统计字段归零、{@link #defaultSlot} = false。保留给插件 `new`。 */
    public SlotInfo(String name, String path, boolean current, boolean legacy) {
        this(name, path, current, legacy, false, 0, 0L, 0, 0, 0, 0);
    }

    /** **v42** 构造器。 */
    public SlotInfo(String name, String path, boolean current, boolean legacy,
                    boolean defaultSlot, int files, long bytes,
                    int mods, int maps, int saves, int blueprints) {
        this.name = name == null ? "" : name;
        this.path = path;
        this.current = current;
        this.legacy = legacy;
        this.defaultSlot = defaultSlot;
        this.files = files;
        this.bytes = bytes;
        this.mods = mods;
        this.maps = maps;
        this.saves = saves;
        this.blueprints = blueprints;
    }

    @Override public String toString() {
        return "SlotInfo{" + name + (current ? ", current" : "")
                + (defaultSlot ? ", default" : "")
                + (legacy ? ", legacy" : "") + "}";
    }
}
