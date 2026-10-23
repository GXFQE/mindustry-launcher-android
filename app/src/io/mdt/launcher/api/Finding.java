package io.mdt.launcher.api;

/**
 * 一条**分析结论**（v24）：插件只给数据，宿主负责画。
 *
 * <p>用在 `slot: "analyzer"` 的扩展点上：宿主对页面里的每一项调用插件的
 * {@code static List<Finding> analyze(ItemInfo item, PluginApi api)}，拿回若干条结论，
 * 用现有页面样式渲染。插件**不画 UI**，所以风格天然和宿主一致。
 *
 * <p>`title` 是一句话结论（列表里单独一行）；`detail` 是补充（可为空）；
 * `severity` 用下面的常量；`evidence` 是给"为什么"留的证据（文件名 / 键值等，可为空数组）。
 */
public final class Finding {

    public static final int INFO = 0;
    public static final int WARN = 1;
    public static final int ERROR = 2;

    /** 一句话结论（非空）。 */
    public final String title;
    /** 补充说明（可为空串）。 */
    public final String detail;
    /** {@link #INFO} / {@link #WARN} / {@link #ERROR}。 */
    public final int severity;
    /** 证据（可为空数组，不许 null）。 */
    public final String[] evidence;

    public Finding(String title, String detail, int severity, String... evidence) {
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.severity = severity;
        this.evidence = evidence == null ? new String[0] : evidence;
    }

    @Override public String toString() {
        return title + (detail.isEmpty() ? "" : " - " + detail);
    }
}
