package io.mdt.launcher.api;

/**
 * **v27**：详情页那「六道加载门」的一条（插件给结论，宿主只负责画）。
 *
 * <p>三个字段都是**给人看的**：{@link #pass} 为真时只画一行 ✅，{@link #note} 应为 “”；
 * 不通过时 {@link #label} 必须说**坏了什么**（不能是肯定句），{@link #note} 给原因。
 */
public final class Gate {
    public final String label;
    public final boolean pass;
    /** 补充说明；空串 = “通过了、没什么好补充的”。 */
    public final String note;

    public Gate(String label, boolean pass, String note) {
        this.label = label == null ? "" : label;
        this.pass = pass;
        this.note = note == null ? "" : note;
    }
}
