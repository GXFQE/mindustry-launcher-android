package io.mdt.launcher.api;

import java.io.File;

/** 探针的运行环境（v23）：宿主给插件的那点东西，别的都没有。 */
public final class ProbeEnv {

    private final File dir;
    private final int pid;
    private final boolean inProcess;
    private final String slot;

    public ProbeEnv(File dir, int pid, boolean inProcess, String slot) {
        this.dir = dir;
        this.pid = pid;
        this.inProcess = inProcess;
        this.slot = slot;
    }

    /** 本会话给这个插件的目录（已创建；插件自己往里写，格式自定）。 */
    public File dir() {
        return dir;
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
}
