package io.mdt.launcher.api;

import java.io.File;

/** 探针的运行环境（v23；v30 加 {@link #version()} / {@link #dataDir()}）。 */
public final class ProbeEnv {

    private final File dir;
    private final File dataDir;
    private final int pid;
    private final boolean inProcess;
    private final String slot;
    private final String version;

    public ProbeEnv(File dir, File dataDir, int pid, boolean inProcess, String slot, String version) {
        this.dir = dir;
        this.dataDir = dataDir;
        this.pid = pid;
        this.inProcess = inProcess;
        this.slot = slot == null ? "" : slot;
        this.version = version == null ? "" : version;
    }

    /** 本会话给这个插件的目录（已创建；插件自己往里写，格式自定）。 */
    public File dir() {
        return dir;
    }

    /**
     * **v30**：插件自己的数据目录（与 {@link PluginApi#dataDir()} 同一个 `&lt;插件&gt;/data`，懒创建）。
     *
     * <p>为什么要它：探针要写"跨会话累计"的东西（比如账本 / 总数）时，`dir()` 是**本会话**目录，
     * 下一次启动就换了；跨会话只能写插件自己的数据目录。多份写同一文件时**由插件自己保证
     * 原子性**（宿主既不知道格式也不参与合并）。
     */
    public File dataDir() {
        return dataDir;
    }

    /** 要观察的进程号：进程内 = 自己；隔离进程 = 游戏进程。 */
    public int pid() {
        return pid;
    }

    /** 自己在不在游戏进程里（决定能不能碰游戏类）。 */
    public boolean inProcess() {
        return inProcess;
    }

    /** 当前槽名。 */
    public String slot() {
        return slot;
    }

    /**
     * **v30**：这一局启动的**版本 key**（已装 = 包名；导入 = `import:&lt;文件名&gt;`；
     * 拿不到 = 空串）。
     *
     * <p>★ 与 {@code PluginApi.versions()} 的 {@code ItemInfo.extra["key"]} **同一个值** ——
     * 探针按它分账，页面就能把数字挂到版本行上。判据只有 {@code Versions.Entry.key()} 一处。
     */
    public String version() {
        return version;
    }
}
