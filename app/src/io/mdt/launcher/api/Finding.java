package io.mdt.launcher.api;

/**
 * 一条**分析结论**（v24；v25 起可以挂到某一项上）。
 *
 * <p>用在 `slot: "analyzer"` 的扩展点上：宿主对页面里的每一项调用插件的
 * {@code static List<Finding> analyze(ItemInfo item, PluginApi api)}，拿回若干条结论，
 * 用现有页面样式渲染。插件**不画 UI**，所以风格天然和宿主一致。
 *
 * <p>`title` 是一句话结论；`detail` 是补充（可为空）；`severity` 用下面的常量；
 * `evidence` 是给"为什么"留的证据（文件名 / 键值等，可为空数组）。
 *
 * <p><b>v25 新增 {@link #anchor(String)}</b>：默认（不挂）结论进页面底部那张「插件」卡；
 * 挂到某一项上 ⇒ 宿主把它画在**那一行**（行上的短提示，进详情能看 {@code detail}）。
 * 判据用 {@code ItemInfo.path}（同一项在整页里唯一）。
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
    /** 挂到哪一项上（{@link ItemInfo#path}）；{@code null} = 进「插件」卡。 */
    public final String anchorPath;

    public Finding(String title, String detail, int severity, String... evidence) {
        this(title, detail, severity, null, evidence);
    }

    private Finding(String title, String detail, int severity, String anchorPath, String... evidence) {
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.severity = severity;
        this.evidence = evidence == null ? new String[0] : evidence;
        this.anchorPath = anchorPath;
    }

    /**
     * **v25**：把这条结论挂到某一项上（返回**新的**一条，原对象不动）——
     * 宿主会画在那一行，而不是底部的「插件」卡。{@code null} / 空串 = 不挂。
     */
    public Finding anchor(String itemPath) {
        if (itemPath == null || itemPath.isEmpty()) return this;
        return new Finding(title, detail, severity, itemPath, evidence);
    }

    @Override public String toString() {
        return title + (detail.isEmpty() ? "" : " - " + detail)
                + (anchorPath == null ? "" : " @" + anchorPath);
    }
}
