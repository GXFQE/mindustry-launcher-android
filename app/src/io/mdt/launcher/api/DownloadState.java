package io.mdt.launcher.api;

import java.io.File;

/**
 * **v43**：某个下载 key 的当前状态（{@link PluginApi#downloadState(String)} 的返回值）。
 *
 * <p>只读快照：宿主只 stat + 读小文件，**可以在 UI 线程调**。
 * 判据：{@link #complete} 为真时 {@link #file} 是校验过的成品；{@link #done} 是已落盘字节
 * （含续传偏移）；{@link #total} 为 0 表示服务端还没告诉总长。
 */
public final class DownloadState {

    /** 插件给的稳定键（与调用 {@code download(..., key, ...)} 时同一个）。 */
    public final String key;

    /** 已落盘字节（成品存在时 = 成品长度；否则 = `.part` 长度）。 */
    public final long done;

    /** 总长；0 = 不知道。 */
    public final long total;

    /** 成品已经在、且 sha256 对得上。 */
    public final boolean complete;

    /** 成品文件；{@code complete=false} 时为 null。 */
    public final File file;

    public DownloadState(String key, long done, long total, boolean complete, File file) {
        this.key = key == null ? "" : key;
        this.done = done;
        this.total = total;
        this.complete = complete;
        this.file = file;
    }
}
