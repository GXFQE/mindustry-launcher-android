package io.mdt.launcher.api;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 探针的运行环境（v23；v30 加 {@link #version()} / {@link #dataDir()}；v31 加版本信息）。 */
public final class ProbeEnv {

    private final File dir;
    private final File dataDir;
    private final int pid;
    private final boolean inProcess;
    private final String slot;
    private final String version;
    private final Map<String, String> versionInfo;

    public ProbeEnv(File dir, File dataDir, int pid, boolean inProcess, String slot, String version,
                    Map<String, String> versionInfo) {
        this.dir = dir;
        this.dataDir = dataDir;
        this.pid = pid;
        this.inProcess = inProcess;
        this.slot = slot == null ? "" : slot;
        this.version = version == null ? "" : version;
        this.versionInfo = versionInfo == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new TreeMap<String, String>(versionInfo));
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
     *
     * <p>⚠️ 它是**身份**，不含版本号：已安装的游戏**更新后包名不变**，所以"哪个版本"要看
     * {@link #versionInfo()}（尤其 `displayVersion` / `versionName`）。
     */
    public String version() {
        return version;
    }

    /**
     * **v31**：这一局的**全部版本信息**（只读副本，键与 {@code PluginApi.versions()} 的
     * {@code ItemInfo.extra} 同名，另加 `title`）。
     *
     * <p>当前会给（宿主以后可能**继续加键**，插件要容忍缺键）：
     * <pre>
     * key            身份（同 {@link #version()}）
     * title          显示名（含自定义名 / 类型 / 版本号，与槽页那一行一致）
     * versionName    原始 versionName（数据层保真，如 8-official-159.7）
     * displayVersion 人读版本号（已剥构建前缀，如 159.7 / 2026.09.X37）
     * upstreamVersion 上游版本号（fork 才有；没有 = 缺键或空串）
     * label          游戏名（如 MindustryX）
     * pkg / realPkg  包名（导入包是真实包名）
     * imported       "true" / "false"
     * importFile     导入文件名（已装 = 缺键或空串）
     * md5            导入包的 md5（已装通常为空）
     * customName     用户自定义名（没改过 = 缺键或空串）
     * slot           这一局的槽
     * </pre>
     *
     * <p>为什么要它：已安装的游戏**会在原地更新**（包名不变、版本号变）⇒ 只记 key 的话，
     * 更新前后就分不开；探针应把 **key 当身份、版本号 + 这些字段当快照**一起记进自己的账本。
     */
    public Map<String, String> versionInfo() {
        return versionInfo;
    }

    /** **v31** 便利方法：人读版本号（= {@link #versionInfo()} 的 `displayVersion`；没有 ⇒ 空串）。 */
    public String displayVersion() {
        String v = versionInfo.get("displayVersion");
        return v == null ? "" : v;
    }
}
