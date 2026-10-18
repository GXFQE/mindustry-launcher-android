package io.mdt.launcher.api;

/**
 * 一个槽的只读描述（第 161 轮，插件接口 v5）。
 *
 * <p>★ 字符串字段都是**数据**，不是界面文案：{@link #name} 就是槽名，
 * {@link #path} 是它的绝对目录。要读里面的内容请用
 * {@link PluginApi#contents(String, String)}，不要自己拼 {@code slot-<name>}：
 * 远古包的槽可能借住在 {@code files/} 下（{@link #legacy} 会标出来）。
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

    public SlotInfo(String name, String path, boolean current, boolean legacy) {
        this.name = name == null ? "" : name;
        this.path = path;
        this.current = current;
        this.legacy = legacy;
    }

    @Override public String toString() {
        return "SlotInfo{" + name + (current ? ", current" : "")
                + (legacy ? ", legacy" : "") + "}";
    }
}
