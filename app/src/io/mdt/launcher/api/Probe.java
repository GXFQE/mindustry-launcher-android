package io.mdt.launcher.api;

/**
 * 插件**探针**（v23）：在游戏进程或 `:probe` 隔离进程里按固定间隔采样。
 *
 * <p>声明写在 `plugin.json`（**不需要**扩展点条目）：
 * <pre>
 * "telemetry": {
 *   "probes": [
 *     { "class": "com.example.HeapProbe", "sec": 5,  "inProcess": true  },
 *     { "class": "com.example.ProcProbe", "sec": 10, "inProcess": false }
 *   ]
 * }
 * </pre>
 * `inProcess=true` ⇒ 住在 `:game` 里（能反射游戏类、拿 Java 堆/GC/帧时间；崩了是游戏崩）；
 * `inProcess=false` ⇒ 住在 `:probe` 隔离进程（只能读 `/proc/<游戏 pid>`，风险低）。
 *
 * <p>**数据由插件自己写**：{@link #reset(ProbeEnv)} 给的 {@link ProbeEnv#dir()} 是本会话的
 * 插件专属目录，{@link ProbeEnv#dataDir()} 才是**跨会话**的插件数据目录（v30）；宿主不解析
 * 插件的账本、不聚合、不替插件算。唯一的展示口是插件在自述里**显式声明**的
 * {@code playtimeTotal} 那一个整数（主界面摘要卡，见 {@code docs/PLUGIN-API.md}）。
 * 宿主只负责调度、异常/超时隔离与失败停用。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code sample()} 在**后台线程**调用，单次超过 200ms 记一次失败；连续 3 次失败 ⇒
 *       宿主停用该探针（不再调用）并写失败记录。</li>
 *   <li>任何异常都不许影响游戏：宿主全部吞掉并计数。</li>
 *   <li>{@code summary()} 可选，会话结束前调用（进程被杀就不会调用）。</li>
 * </ul>
 */
public interface Probe {

    /** 名字（日志 / 失败记录用）。 */
    String name();

    /** 会话开始：宿主给目录与目标进程号（隔离进程里就是它要观察的游戏进程）。 */
    void reset(ProbeEnv env);

    /** 采样一次（后台线程）。 */
    void sample();

    /**
     * v29：游戏 activity 进了前台（true）/ 离开前台（false）。
     *
     * <p>只对 `inProcess=true` 的探针调用（隔离进程看不到）。默认空实现 —— 不需要的探针别覆写。
     * ★ 这是**机制**：宿主只报事件，怎么记（前台秒数 / 只记下界 / 根本不记）由插件自己定。
     */
    default void onForeground(boolean foreground) {
    }

    /** 会话结束前收尾（可选）。 */
    default void summary() {
    }
}
