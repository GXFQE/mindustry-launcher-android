package io.mdt.launcher.api;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 插件能用的**宿主能力门面**（第 159 轮，插件接口 v3；第 160 轮 v4；第 161 轮 v5）。
 *
 * <h3>插件怎么拿到它</h3>
 * 入口类可以写两个静态方法，宿主**优先**用前一个：
 * <ol>
 *   <li>{@code static View createView(PluginApi api)} —— 推荐；</li>
 *   <li>{@code static View createView(Context ctx)} —— v1/v2 的老写法，照旧能用。</li>
 * </ol>
 * 两个都写时宿主走 {@code PluginApi} 那条；只写 {@code Context} 的老插件不受影响。
 *
 * <h3>它是什么 / 不是什么</h3>
 * <ul>
 *   <li>这是**读取入口**：路径都按"当前槽"算好，插件不必再自己拼
 *       {@code /data/data/io.mdt.launcher/...} 或猜 {@code slot-*} 的命名。</li>
 *   <li>⚠️ **不是沙箱**：宿主与插件同进程、同 ClassLoader，插件绕过这层也拦不住。
 *       这里只保证"插件不需要摸宿主内部类也能干活"，不保证"插件碰不到别的东西"。</li>
 *   <li>路径**只保证指向该槽该目录的位置**，不保证目录存在 —— 空槽里
 *       {@code savesDir()} 也可能不存在；插件自己判 {@code isDirectory()}。</li>
 *   <li>返回值都是**当时**算出来的 {@link File}；槽切换后请重新取，别缓存。</li>
 * </ul>
 *
 * <h3>版本</h3>
 * {@link #hostApi()} 报的是**宿主**这一版支持的接口版本（与插件入口 {@code api()} 报的
 * "我要的版本"不是同一个数）。用 {@link #selectedFile()} 或全页动作的插件，入口 {@code api()}
 * 应报 4；只写 {@code createView(PluginApi)} / 只用 v3 能力的报 3 也行。
 * **v5 起**多了只读数据面（{@link #slots()} / {@link #contents(String)} / {@link #pageItems()} /
 * {@link #open(ItemInfo)}），用它们的插件报 5。
 * **v6 起**补齐设置 / 日志 / 中转站 / 版本 / {@link #selectedItems()}，用它们的插件报 6。
 * 报 1/2 的老插件宿主不会走 {@code createView(PluginApi)} 那条路（仍按 v1/v2 的
 * {@code createView(Context)} 加载）。
 *
 * <h3>只读数据（v5）</h3>
 * 数据分成两层：
 * <ul>
 *   <li><b>文件层</b>：{@link #slots()} 列所有槽，{@link #contents(String, String)}
 *       列任意槽的存档 / 地图 / 模组 / 蓝图，条目里带路径、大小、时间、来源与元数据；</li>
 *   <li><b>字节层</b>：{@link #open(ItemInfo)} 统一打开槽内文件或 APK / 模组 zip 里的条目。
 *       插件不用自己判断 {@code container + entry} 怎么拼。</li>
 * </ul>
 * ⚠️ 扫描要读文件头（地图 / 存档 / 模组），**别在主线程调用**；拿到的 {@link ItemInfo}
 * 是**当时**的快照，文件被改后请重新取。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public interface PluginApi {

    /** 宿主这一版支持的插件接口版本（当前 = 6）。 */
    int hostApi();

    /**
     * 造 View / 取资源用的 {@link Context}（**带插件自己的资源**）。
     *
     * <p>⚠️ 别拿它去 {@code getFilesDir()} 之外的地方乱写：数据目录请用
     * {@link #slotDir()} 这些；写自己的东西用 {@code context().getFilesDir()}（= 插件自己的 data 目录）。
     */
    Context context();

    /**
     * 这个页面是从哪来的。取值：
     * {@code plugins}（插件页）· {@code main} · {@code slots} · {@code slot} ·
     * {@code saves} / {@code maps} / {@code mods} / {@code blueprints}（四个内容页）·
     * {@code map-detail} / {@code blueprint-detail}（详情页）·
     * {@code settings} / {@code logs} / {@code trash}。
     */
    String page();

    /** 当前槽名（内容页就是那一页正在看的槽；插件页 = 当前槽）。 */
    String slot();

    /**
     * **详情页当前选中的那一份文件**（地图详情 / 蓝图详情）；其余页面返回 {@code null}。
     *
     * <p>只读约定：想改内容请走 {@link #savesDir()} / {@link #mapsDir()} 那一层自己处理，
     * 不要原地改写宿主正在看的这份文件。
     */
    File selectedFile();

    /**
     * 所有槽（名称 / 目录 / 是否当前 / 是否远古包本体借住 {@code files/}）。
     *
     * <p>顺序由宿主决定（当前槽在最前，其余按名字）；拿到的是一份快照。
     */
    List<SlotInfo> slots();

    /**
     * 当前槽的某一类内容（只读快照）。
     *
     * @param kind {@link ItemInfo#KIND_SAVES} / {@link ItemInfo#KIND_MAPS} /
     *             {@link ItemInfo#KIND_MODS} / {@link ItemInfo#KIND_BLUEPRINTS}；
     *             认不出的 kind ⇒ 空表（不崩）
     */
    List<ItemInfo> contents(String kind);

    /**
     * 任意槽的某一类内容。
     *
     * @param slot 槽名；null / 空 ⇒ 当前槽
     * @param kind 同上
     */
    List<ItemInfo> contents(String slot, String kind);

    /**
     * 当前页面看到的数据（只读快照）。
     *
     * <p>v5~v6 覆盖：四个内容页（各自那一类）、槽列表（槽条目）、地图 / 蓝图详情（当前那一份）、
     * 主界面（版本列表）、设置（设置项）、日志（日志 / 崩溃报告文件）、中转站（条目）。
     * 内容页返回的是**该槽的数据源**，不是搜索 / 筛选后的子集。
     */
    List<ItemInfo> pageItems();

    /**
     * 当前页面选中的条目。
     *
     * <p>⚠️ 只有**详情页**有稳定的"当前选中"：地图详情 / 蓝图详情返回那一份。
     * 四个内容页的勾选是**导出 / 删除弹窗里的临时状态**，页面不保存 ⇒ 那里返回空表
     * （要"勾选后交给插件"得先改成持久多选，见 P2 交接）。需要整页数据请用 {@link #pageItems()}。
     */
    List<ItemInfo> selectedItems();

    /**
     * 设置项的只读快照（白名单，不包含 session / 崩溃记账 / 导入表这类内部键）。
     *
     * <p>键：{@code config_version} · {@code theme_mode} · {@code app_language} · {@code default_slot} ·
     * {@code max_log_files} · {@code auto_clean_redundant} · {@code last_version}，以及每个槽的
     * {@code backup.<槽>.enabled} / {@code .min_minutes} / {@code .max_backups}。
     * 值是**原始值**的字符串形式（语言空串 = 跟随系统）。
     */
    Map<String, String> settings();

    /** 日志与崩溃报告文件（只读快照；用 {@link #open(ItemInfo)} 读正文）。 */
    List<ItemInfo> logs();

    /** 中转站条目（各 trash 目录的一级条目；目录条目不能 open）。 */
    List<ItemInfo> trash();

    /** 主界面版本列表（已安装 + 已导入；APK 路径可用于 open）。 */
    List<ItemInfo> versions();

    /**
     * 打开一个条目的字节流。
     *
     * <p>槽内文件直接开文件；{@code container + entry} 的包内条目由宿主开 zip 并保证
     * 流关闭时把 zip 一起关掉。调用方负责 {@code close()}。
     *
     * @throws IOException 条目为 null、不可读、目录、或包内条目不存在
     */
    InputStream open(ItemInfo item) throws IOException;

    /** 当前槽的数据根（= 游戏会读的那个目录）。 */
    File slotDir();

    /** 当前槽的存档目录（{@code <槽>/saves}）。 */
    File savesDir();

    /** 当前槽的模组目录（{@code <槽>/mods}）。 */
    File modsDir();

    /** 当前槽的地图目录（{@code <槽>/maps}）。 */
    File mapsDir();

    /** 当前槽的蓝图目录（{@code <槽>/schematics}）。 */
    File blueprintsDir();

    /** 弹一句短提示（从后台线程调用也行 —— 宿主会转回主线程）。 */
    void toast(String message);

    /** 写一行宿主日志（前缀固定，方便过滤）。 */
    void log(String message);
}
