package io.mdt.launcher.api;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 插件能用的**宿主能力门面**（第 159 轮，插件接口 v3；第 160 轮 v4；第 161 轮 v5；
 * 第 162 轮 v6；第 187 轮 v7；第 188 轮 v8）。
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
 * **v7 起**给"做事"的那一层：{@link #theme()} · {@link #runBackground(String, Runnable)} /
 * {@link #progress(int, int)} · {@link #closePage()} · {@link #share(String, InputStream)} /
 * {@link #shareText(String, String)} / {@link #export(String, String, InputStream)} ·
 * {@link #pick(String, PickCallback)}，用它们的插件报 7。
 * **v8 起**补模组事实：{@link #modsSkipped()}（这一轮游戏会不会加载模组），用它的插件报 8。
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

    /**
     * 文档里写的接口版本（**必须等于**宿主 `PluginHost.API`；自检有一条元断言钉着）。
     *
     * <p>为什么要有这个常量：注释里的「当前 = N」运行期读不到，一个写 17、一个写 18 也没人发现；
     * 常量 + 断言才拦得住。</p>
     */
    int DOC_VERSION = 18;


    /** 宿主这一版支持的插件接口版本（当前 = 14）。 */
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
     * **v9**：详情页上**当前这一项**（{@link #selectedFile()} 只有槽内文件才给得出值，
     * 游戏自带 / 模组自带的地图住在一个 APK / zip 里，没有本机文件）。
     *
     * <p>返回的东西完整到足以交给 {@link #open(ItemInfo)}（含 {@code container} 与 {@code entry}），
     * 所以插件可以在详情页上直接统计「这一张」—— 用户点开地图就看见数字，不用再去列表里挑一遍。
     * 非详情页 / 没选中 ⇒ {@code null}。</p>
     */
    ItemInfo selectedItem();

    /** 选完之后的回调（{@code index < 0} = 用户取消 / 弹不出来）。 */
    interface Choose {
        void onChoose(int index);
    }

    /**
     * **v10**：让**宿主**弹一个"选一项"的对话框（标题 + 若干选项）。
     *
     * <p>为什么必须由宿主弹：插件拿到的 ctx 不是 Activity，自己 {@code new AlertDialog} 会
     * {@code BadTokenException}（没有窗口令牌）。而"选完一堆东西马上问一句怎么导出"这件事
     * 只有真弹窗能做好 —— 把按钮放在结果下面，选 20 张地图就被顶出屏幕（用户 2026-10-21 原话）。</p>
     *
     * <p>回调在主线程；{@code options} 为空或宿主不可用 ⇒ 回调 {@code -1}，插件自己兜底。</p>
     */
    void choose(String title, String[] options, Choose callback);

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
     * 游戏这一轮**会不会加载模组**（{@code false} = 会加载）。
     *
     * <p>为什么要问：上次崩过时游戏会整个跳过模组（`modcrashdisable`），这时"按模组算"的
     * 内容表与用户实际看到的游戏不一致。宿主的判据只有一处（{@code Mods.scan} 的
     * {@code skipModLoading}），插件别自己拼 {@code launchid.dat} + 设置的组合。
     *
     * <p>v8 起。拿不到（槽不存在 / 读挂了）⇒ {@code false}。
     */
    boolean modsSkipped();

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

    // ---- v7：做事 ---------------------------------------------------------------

    /**
     * 插件页能用的**配色与尺寸**（跟随宿主深浅色）。
     *
     * <p>为什么给：插件页的 Context 是 {@code ContextWrapper}（主题借宿主的、资源是插件的），
     * 插件自己画卡片 / 文字时没有可查的语义色，深色模式下这一页会变成唯一不跟着变的地方。
     * 返回的是**已经解析好的 ARGB**（见 {@link PluginTheme}）。
     */
    PluginTheme theme();

    /**
     * 在**后台线程**跑一段活，期间宿主挂一条常驻通知（前台服务优先级）。
     *
     * <p>为什么不自己 {@code new Thread}：长任务（扫描 / 打包 / 批量转换）在用户切走后会被
     * 系统直接回收 —— 宿主那套前台服务门面是包内类，插件够不着，于是这里代劳。
     *
     * <p>约定：{@code body} 在后台线程执行，**异常会被宿主接住并写日志**（不会带走整个进程）；
     * 跑完通知自动撤掉。{@code label} 是通知正文（给用户看的一句话，可为 null ⇒ 用通用文案）。
     * 同一时刻可以有多段，计数是嵌套安全的。
     */
    void runBackground(String label, Runnable body);

    /**
     * 更新那条常驻通知上的进度（{@code done}/{@code total}）。
     *
     * <p>只在 {@link #runBackground} 那段活里、且有通知挂着时有效；其余时候**静默忽略**
     * （没有通知可更新不是错误）。{@code total <= 0} ⇒ 显示成不确定进度。
     * 可以频繁调用（宿主同一份通知原地更新，不会刷屏）。
     */
    void progress(int done, int total);

    /**
     * 关掉插件页，回到**打开它的那一页**（与页面左上「返回」同一个去处）。
     *
     * <p>用于插件自己的「完成 / 关闭」按钮 —— 以前只能等用户按系统返回。
     * 从插件列表点进来的 ⇒ 回列表；从内容页动作行走「直达」进来的 ⇒ 回那个内容页。
     * 调用后插件页即被回收，别再对它做 UI 操作（后台线程照常可以跑完）。
     */
    void closePage();

    /**
     * 把一份内容交给**系统分享面板**（不经过 SAF，也不落用户目录）。
     *
     * <p>宿主把流写进 {@code cache/share/}（同名自动加序号、24h 后自己清），再把
     * {@code content://} 交给系统；接收方（微信 / QQ / 文件管理器）照常读。
     *
     * <p>⚠️ 流由宿主负责关闭（调用后不要再动它）；方法本身**异步**，失败会 Toast 说明原因。
     * 用于"插件算出来的报告 / 改写后的一份文件"这类结果的出口。
     */
    void share(String name, InputStream content);

    /** 同 {@link #share(String, InputStream)}，但直接给一段文本（UTF-8，不带 BOM）。 */
    void shareText(String name, String text);

    /**
     * 让用户选一个落点**保存**一份内容（系统 SAF，不申请存储权限）。
     *
     * <p>与 {@link #share} 的区别：这个会落进用户自己选的位置（长期保留），分享那份只是临时缓存。
     * 异步完成；成功 / 失败都会 Toast。同一时刻只允许一份在等保存（再来一份会被拒绝并提示）。
     *
     * @param name 建议文件名（用户可改）
     * @param mime MIME；null / 空 ⇒ 按扩展名推（认不出就是 {@code application/octet-stream}）
     * @param content 内容（宿主负责关闭）
     */
    void export(String name, String mime, InputStream content);

    /**
     * **v11**：同上，但**保存结束后回调**（成功 = true；取消 / 失败 = false）。
     *
     * <p>为什么要它：插件想在"用户真的存好了"之后才关掉导出页 —— 早一步关，
     * 宿主会看到 {@code isFinishing()} 直接把这次保存取消掉（真机踩过）。</p>
     */
    void export(String name, String mime, InputStream content, After after);

    /**
     * **v13**：宿主自己的**进度弹窗**（搬家前那句「正在统计 N / M…」就是它）。
     *
     * <p>插件 ctx 不是 Activity、自己弹不了窗；这条让插件在**原页面**上要一个
     * 不可取消的横向进度框。`total <= 0` 当不确定进度。</p>
     */
    void progressCard(String title, String message, int done, int total);

    /** **v13**：关掉上面那个进度弹窗（保存 / 分享 / 取消之后都要关）。 */
    void progressCardClose();

    // ---- v14（第 202 轮）：不搞权限门，但把"正常开发者都需要"的东西补齐 ----

    /** **v14**：插件自己的可写目录（`<插件>/data`，宿主建好）。 */
    java.io.File dataDir();

    /** **v14**：游戏现在在跑吗（跑着时别去动存档 / 模组文件）。 */
    boolean gameRunning();

    /** **v14**：宿主图标（按 res 名取，比如 `ic_map`）；拿不到返回 null。 */
    android.graphics.drawable.Drawable hostIcon(String name);

    /** **v14**：是 / 否弹窗（宿主那套），用户点了才回调。 */
    void confirm(String title, String message, String okText, Confirm callback);

    /** **v14**：要一段文字（宿主那套输入弹窗）；取消回调 null。 */
    void prompt(String title, String hint, String initial, String okText, Prompt callback);

    /** 确认框回调。 */
    interface Confirm { void onConfirm(boolean ok); }

    /** 输入框回调；`null` = 用户取消。 */
    interface Prompt { void onPrompt(String text); }

    // ---- v15（第 205 轮）：读写用户选的任意文件 / 跳详情 / 挪中转站 / 可取消进度 ----

    /**
     * **v15**：让用户挑一个**系统里的任意文件**（SAF），宿主把它复制到**插件缓存**再交给你。
     *
     * <p>取消 ⇒ 回调 `(null, null)`。文件在插件自己的 `cache/` 里，读完自己删或不管（会被回收）。</p>
     */
    void pickFile(String mime, PickFile callback);

    /** **v15**：跳到某个条目的详情页（地图 / 存档 → 地图详情；蓝图 → 蓝图详情；模组 → 模组页）。 */
    void openItem(ItemInfo item);

    /** **v15**：把条目挪进中转站（不是真删；与界面删除同一套规则）。失败回调 false。 */
    void trashItem(ItemInfo item, Done callback);

    /** **v15**：可取消的进度弹窗（4 参那条不可取消）。取消时回调，插件应尽快收尾。 */
    void progressCard(String title, String message, int done, int total,
                      boolean cancelable, Cancel cancel);

    /** 选文件回调；取消时两个参数都是 null。 */
    interface PickFile { void onPicked(String name, java.io.File file); }

    /** 结果回调（挪中转站是否成功）。 */
    interface Done { void onDone(boolean ok); }

    /** 进度框被取消。 */
    interface Cancel { void onCancel(); }

    // ---- v16（第 206 轮）：写回条目 ----

    /**
     * **v16**：把一个条目的内容**换掉**（仅限槽内 maps / saves / mods / blueprints 里的**已存在**文件）。
     *
     * <p>同目录写临时文件再原子替换；游戏在跑时拒绝（`false`）。内容读完后宿主负责关闭。</p>
     */
    void writeItem(ItemInfo item, java.io.InputStream content, Done callback);

    /**
     * **v18**：会话记录的**目录**（只读），里面两条流分开：
     *
     * <ul>
     *   <li>`launch.tsv` —— 启动那一刻的事实（`L ts ver slot apk`），写在起游戏之前，**永远完整**</li>
     *   <li>`run.tsv` —— 运行期事实（`S ts slot` / `B ts` 5 秒心跳 / `E ts`），进程被杀就会断</li>
     * </ul>
     *
     * <p>没有 E 的会话按 `min(now, lastB + 5s)` 收口（只少不多），单条上限 12 小时。</p>
     */
    java.io.File usageDir();

        /** 保存结束（成功 / 取消或失败）后的回调；插件据此收尾（比如关掉导出页）。 */
    interface After {
        void onAfter(boolean ok);
    }


    /**
     * 让用户从**当前槽的某一类内容**里勾选，结果回调给插件。
     *
     * <p>为什么不是"插件自己在页面上列一遍再勾"：宿主那一套勾选框（整行可点、正按钮报份数、
     * 全选/清空跟着状态换词）已经踩过坑；插件自己再造一份，用户就会在两个地方见到两种行为。
     * 另一个原因：四个内容页的勾选是导出 / 删除弹窗里的**临时状态**，页面不保存 ⇒
     * {@link #selectedItems()} 在列表页永远是空表。
     *
     * <p>支持的 {@code kind}：{@link ItemInfo#KIND_SAVES} / {@link ItemInfo#KIND_MAPS} /
     * {@link ItemInfo#KIND_MODS} / {@link ItemInfo#KIND_BLUEPRINTS}，以及
     * {@link ItemInfo#KIND_LOGS} / {@link ItemInfo#KIND_TRASH} / {@link ItemInfo#KIND_VERSION}；
     * 其余值 ⇒ 直接回调空表（不弹窗）。
     *
     * <p>扫描在后台、弹窗在主线程；回调也在主线程（见 {@link PickCallback}）。
     */
    void pick(String kind, PickCallback callback);
}
