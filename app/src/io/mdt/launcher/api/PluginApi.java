package io.mdt.launcher.api;

import android.content.Context;

import java.io.File;

/**
 * 插件能用的**宿主能力门面**（第 159 轮，插件接口 v3）。
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
 * "我要的版本"不是同一个数）。用本接口的插件，入口 {@code api()} 应报 3；
 * 报 1/2 的老插件宿主不会走 {@code createView(PluginApi)} 那条路（仍按 v1/v2 的
 * {@code createView(Context)} 加载）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public interface PluginApi {

    /** 宿主这一版支持的插件接口版本（当前 = 3）。 */
    int hostApi();

    /**
     * 造 View / 取资源用的 {@link Context}（**带插件自己的资源**）。
     *
     * <p>⚠️ 别拿它去 {@code getFilesDir()} 之外的地方乱写：数据目录请用
     * {@link #slotDir()} 这些；写自己的东西用 {@code context().getFilesDir()}（= 插件自己的 data 目录）。
     */
    Context context();

    /**
     * 这个页面是从哪来的：{@code plugins}（插件页）或四个内容页之一
     * （{@code saves} / {@code maps} / {@code mods} / {@code blueprints}）。
     */
    String page();

    /** 当前槽名（内容页就是那一页正在看的槽；插件页 = 当前槽）。 */
    String slot();

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
