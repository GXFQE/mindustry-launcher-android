package io.mdt.launcher.api;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 插件能用的**全部宿主能力**（唯一入口）。宿主实现、插件调用。
 *
 * <h3>入口类怎么写</h3>
 * <pre>
 * public final class MyPlugin {
 *     public static int api() { return 20; }                       // 我需要的最低宿主接口版本
 *     public static View createView(PluginApi api) { ... }         // 页面：返回一个 View
 *     public static void run(PluginApi api) { ... }                // action：不建页面、当场做事
 *     public static void unload() { ... }                          // 可选：关页 / 换页 / 销毁时收尾
 * }
 * </pre>
 * 入口类由 {@code plugin.json} 的 {@code entry} 指定，方法**全部是 public static**、按名字反射调用。
 * 没有 {@code createView(Context)} 之类的老回落了（v20 起）：只会找 {@code createView(PluginApi)}。
 *
 * <h3>版本</h3>
 * 插件入口的 {@code api()} 报的是**我需要的最低版本**；宿主接受
 * {@code [PluginHost.API_MIN, PluginHost.API]}。用不到新能力的插件别报高版本 ——
 * 报高了只会在老宿主上被拒，换不来任何东西。<b>也不要用 try/catch 去兼容老宿主</b>：
 * 报什么版本就只会跑在什么版本上，这是 v20 起定下的模型。
 *
 * <h3>线程与生命周期</h3>
 * <ul>
 *   <li>{@code createView} / {@code run} 在 UI 线程；**别在里面读大文件**。</li>
 *   <li>{@link #runBackground} 的 body 在后台线程；要更新 UI 用 View 自己的 {@code post}。</li>
 *   <li>回调（{@link Choose} / {@link Confirm} / {@link Prompt} / {@link PickFile} /
 *       {@link PickContent} / {@link After} / {@link Done} / {@link Cancel}）都在 UI 线程。</li>
 *   <li>{@code export} / {@code share} / {@code writeItem} 的 {@link InputStream} **由宿主关闭**；
 *       {@link #pickFile} 给你的是插件 cache 里的文件，自己读。</li>
 * </ul>
 *
 * <h3>边界（先看这几条再动手）</h3>
 * <ul>
 *   <li>**没有沙箱**：靠"约定 + 告知"，不是权限门。能读宿主能读的，写只能写自己声明的地方。</li>
 *   <li>{@link #writeItem} 只许改槽内 maps / saves / mods / blueprints 里**已存在**的文件。</li>
 *   <li>插件的数据住 {@link #dataDir()}，缓存住插件自己的 {@code cache/}。</li>
 *   <li>界面文案走 {@code api.context().getString(...)} 或插件自带资源；宿主的 {@code Trans} 只服务宿主。</li>
 * </ul>
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public interface PluginApi {

    /**
     * 文档里写的接口版本（**必须等于** {@code PluginHost.API}；自检有一条元断言钉着）。
     *
     * <p>为什么要有这个常量：注释里的「当前 = N」运行期读不到，一个写 17、一个写 20 也没人发现；
     * 常量 + 断言才拦得住。</p>
     */
    int DOC_VERSION = 21;

    // ══ 环境 ═══════════════════════════════════════════════════════════════

    /** 宿主这一版支持的插件接口版本（比它新 ⇒ 插件报的版本装不进来）。 */
    int hostApi();

    /**
     * 造 View / 取资源用的 {@link Context}（**带插件自己的资源**）。
     *
     * <p>⚠️ 只拿来做三件事：取插件资源、拿主题、建 View。数据目录用 {@link #dataDir()} /
     * {@link #slotDir()} 这些，别自己拼路径。
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

    // ══ 只读数据 ═══════════════════════════════════════════════════════════

    /**
     * 插件页能用的**配色与尺寸**（跟随宿主深浅色）。
     *
     * <p>为什么给：插件页的 Context 是 {@code ContextWrapper}（主题借宿主的、资源是插件的），
     * 插件自己画卡片 / 文字时没有可查的语义色，深色模式下这一页会变成唯一不跟着变的地方。
     * 返回的是**已经解析好的 ARGB**（见 {@link PluginTheme}）。
     */
    PluginTheme theme();

    /**
     * 所有槽（名称 / 目录 / 是否当前 / 是否远古包本体借住 {@code files/}）。
     *
     * <p>顺序由宿主决定（当前槽在最前，其余按名字）；拿到的是一份快照。
     */
    List<SlotInfo> slots();

    /**
     * **当前槽**的某一类内容（只读快照）。
     *
     * <p>等价于 {@code contents(slot(), kind)}；默认方法，宿主不用各实现一遍。
     *
     * @param kind {@link ItemInfo#KIND_SAVES} / {@link ItemInfo#KIND_MAPS} /
     *             {@link ItemInfo#KIND_MODS} / {@link ItemInfo#KIND_BLUEPRINTS}；
     *             认不出的 kind ⇒ 空表（不崩）
     */
    default List<ItemInfo> contents(String kind) {
        return contents(slot(), kind);
    }

    /**
     * **任意槽**的某一类内容（只读快照）。
     *
     * @param slot 槽名；null / 空 ⇒ 当前槽
     * @param kind 同上
     */
    List<ItemInfo> contents(String slot, String kind);

    /**
     * 当前页面看到的数据（只读快照）。
     *
     * <p>覆盖：四个内容页（各自那一类）、槽列表（槽条目）、地图 / 蓝图详情（当前那一份）、
     * 主界面（版本列表）、设置（设置项）、日志（日志 / 崩溃报告文件）、中转站（条目）。
     * 内容页返回的是**该槽的数据源**，不是搜索 / 筛选后的子集。
     */
    List<ItemInfo> pageItems();

    /**
     * 当前页面选中的条目。
     *
     * <p>⚠️ 只有**详情页**有稳定的"当前选中"：地图详情 / 蓝图详情返回那一份。
     * 四个内容页的勾选是**导出 / 删除弹窗里的临时状态**，页面不保存 ⇒ 那里返回空表
     * （要"勾选后交给插件"用 {@link #pickContent}）。
     */
    List<ItemInfo> selectedItems();

    /**
     * **详情页上当前这一项**（地图详情 / 蓝图详情）；其余页面返回 {@code null}。
     *
     * <p>返回的东西完整到足以交给 {@link #open(ItemInfo)}（含 {@code container} 与 {@code entry}），
     * 所以插件可以在详情页上直接统计「这一张」—— 用户点开地图就看见数字，不用再去列表里挑一遍。
     * 槽内文件、游戏自带 / 模组自带的地图走同一个接口，插件不用自己分辨。
     */
    ItemInfo selectedItem();

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
     */
    boolean modsSkipped();

    /** 游戏现在在跑吗（跑着时别去动存档 / 模组文件）。 */
    boolean gameRunning();

    /**
     * 会话记录的**目录**（只读），里面两条流分开：
     *
     * <ul>
     *   <li>{@code launch.tsv} —— 启动那一刻的事实（{@code L ts ver slot apk}），写在起游戏之前，**永远完整**</li>
     *   <li>{@code run.tsv} —— 运行期事实（{@code S ts} / {@code B ts} 30 秒心跳 / {@code E ts} /
     *       {@code C ts 异常类} / {@code SC off|on ts}），进程被杀就会断</li>
     * </ul>
     *
     * <p>没有 E 的会话按 {@code min(now, lastB + 30s)} 收口（只少不多），单条上限 12 小时。
     * 字段与读取约定见 {@code docs/PLUGIN-API.md} 第 9 节。
     */
    File usageDir();

    /**
     * 打开一个条目的字节流。
     *
     * <p>槽内文件直接开文件；{@code container + entry} 的包内条目由宿主开 zip 并保证
     * 流关闭时把 zip 一起关掉。调用方负责 {@code close()}。
     *
     * @throws IOException 条目为 null、不可读、目录、或包内条目不存在
     */
    InputStream open(ItemInfo item) throws IOException;

    // ══ 目录 ═══════════════════════════════════════════════════════════════

    /** 插件自己的可写目录（宿主建好；删了插件不会崩，重开页面会重建）。 */
    File dataDir();

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

    // ══ 交互 ═══════════════════════════════════════════════════════════════

    /** 弹一句短提示（从后台线程调用也行 —— 宿主会转回主线程）。 */
    void toast(String message);

    /** 写一行宿主日志（前缀固定，方便过滤）。 */
    void log(String message);

    /**
     * 让**宿主**弹一个"选一项"的对话框（标题 + 若干选项）。
     *
     * <p>为什么必须由宿主弹：插件拿到的 ctx 不是 Activity，自己 {@code new AlertDialog} 会
     * {@code BadTokenException}（没有窗口令牌）。
     *
     * <p>回调在主线程；{@code options} 为空或宿主不可用 ⇒ 回调 {@code -1}，插件自己兜底。
     */
    void choose(String title, String[] options, Choose callback);

    /** 是 / 否弹窗（宿主那套），用户点了才回调。 */
    void confirm(String title, String message, String okText, Confirm callback);

    /** 要一段文字（宿主那套输入弹窗）；取消回调 null。 */
    void prompt(String title, String hint, String initial, String okText, Prompt callback);

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
     * <p>扫描在后台、弹窗在主线程；回调也在主线程（见 {@link PickContent}）。
     */
    void pickContent(String kind, PickContent callback);

    /**
     * 让用户挑一个**系统里的任意文件**（SAF），宿主把它复制到**插件缓存**再交给你。
     *
     * <p>取消 ⇒ 回调 {@code (null, null)}。文件在插件自己的 {@code cache/} 里，读完自己删或不管。
     */
    void pickFile(String mime, PickFile callback);

    /**
     * 宿主自己的**进度弹窗**（不可取消的横向进度框），落在当前页上。
     *
     * <p>等价于 {@code progressCard(..., false, null)}。
     */
    default void progressCard(String title, String message, int done, int total) {
        progressCard(title, message, done, total, false, null);
    }

    /**
     * 可取消版进度弹窗：用户按返回 / 点取消 ⇒ {@link Cancel#onCancel()}，插件应尽快收尾。
     *
     * <p>{@code total <= 0} 当不确定进度；同一时刻只有一个框，重复调用是**原地更新**。
     */
    void progressCard(String title, String message, int done, int total,
                      boolean cancelable, Cancel cancel);

    /** 关掉上面那个进度弹窗（保存 / 分享 / 取消之后都要关）。 */
    void progressCardClose();

    // ══ 后台任务 ═══════════════════════════════════════════════════════════

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

    // ══ 出口 ═══════════════════════════════════════════════════════════════

    /**
     * 关掉插件页，回到**打开它的那一页**（与页面左上「返回」同一个去处）。
     *
     * <p>用于插件自己的「完成 / 关闭」按钮。调用后插件页即被回收，别再对它做 UI 操作
     * （后台线程照常可以跑完）。
     */
    void closePage();

    /**
     * 把一份内容交给**系统分享面板**（不经过 SAF，也不落用户目录）。
     *
     * <p>宿主把流写进 {@code cache/share/}（同名自动加序号、24h 后自己清），再把
     * {@code content://} 交给系统；接收方（微信 / QQ / 文件管理器）照常读。
     *
     * <p>⚠️ 流由宿主负责关闭；方法本身**异步**，失败会 Toast 说明原因。
     */
    void share(String name, InputStream content);

    /** 同 {@link #share(String, InputStream)}，但直接给一段文本（UTF-8，不带 BOM）。 */
    void shareText(String name, String text);

    /**
     * 让用户选一个落点**保存**一份内容（系统 SAF，不申请存储权限）。
     *
     * <p>与 {@link #share} 的区别：这个会落进用户自己选的位置（长期保留），分享那份只是临时缓存。
     *
     * <p>等价于 {@code export(..., null)}（不关心结果）。
     */
    default void export(String name, String mime, InputStream content) {
        export(name, mime, content, null);
    }

    /**
     * 同上，但**保存结束后回调**：{@link Result#ok} = 真的写完了；取消 / 失败给 reason
     * （{@code canceled} / {@code io} / {@code busy} / {@code no-page}）。
     *
     * <p>为什么要它：插件想在"用户真的存好了"之后才关掉导出页 —— 早一步关，
     * 宿主会看到 {@code isFinishing()} 直接把这次保存取消掉（真机踩过）。
     *
     * <p>⚠️ <b>回调一定会来</b>（成功 / 取消 / 失败都有），插件据此收尾即可。
     */
    void export(String name, String mime, InputStream content, After after);

    // ══ 写与整理 ═══════════════════════════════════════════════════════════

    /**
     * **v21**：当前槽 {@code settings.bin} 的解析结果（只读快照，**保序**）。
     *
     * <p>值类型就是文件里的六种：{@code Boolean} / {@code Integer} / {@code Long} /
     * {@code Float} / {@code String} / {@code byte[]}；调用方自己按类型判。
     *
     * <p>返回值有三种情况：
     * <ul>
     *   <li>文件不存在 ⇒ **空表**（全部是游戏默认值）；</li>
     *   <li>读不出来（坏文件 / 权限）⇒ **{@code null}**，别当"空设置"处理；</li>
     *   <li>正常 ⇒ 键值表（游戏自己写的未知键也原样返回）。</li>
     * </ul>
     *
     * <p>⚠️ 真实文件 5900+ 个键 / ~500 KB ⇒ **别在主线程调**。键的含义（默认值、成对写）
     * 归插件自己 —— 宿主只负责"解出来"。
     */
    Map<String, Object> gameSettings();

    /**
     * **v21**：改当前槽的游戏设置（**只改给定键**；值为 {@code null} ⇒ 删掉这个键）。
     *
     * <p>宿主负责的四条机制（是安全，不是语义）：
     * 游戏在跑 ⇒ 拒绝（{@code game-running}）；写前备份到
     * {@code <hub>/settings-backups/slot-<槽>/}（留 20 份）；原子写；
     * 写后读回自检，不过 ⇒ 自动还原。未知键与键顺序原样保留。
     *
     * <p>**语义归插件**：比如 {@code mod-<名>-enabled} 不存在 = 启用、
     * 改 {@code -enabled} 时要同时清 {@code -failed}（否则留下永远清不掉的陈旧记录）。
     *
     * <p>异步：读写都在后台线程，{@link Done#onDone(Result)} 回主线程。
     * 失败 reason：{@code game-running} / {@code unreadable} / {@code invalid} / {@code io}。
     */
    void writeGameSettings(Map<String, Object> changes, Done done);

    /**
     * 把一个条目的内容**换掉**（仅限槽内 maps / saves / mods / blueprints 里的**已存在**文件）。
     *
     * <p>同目录写临时文件再原子替换；游戏在跑时拒绝（reason = {@code game-running}）。
     * 内容读完后宿主负责关闭。
     */
    void writeItem(ItemInfo item, InputStream content, Done done);

    /** 把条目挪进中转站（不是真删；与界面删除同一套规则）。 */
    void trashItem(ItemInfo item, Done done);

    /** 跳到某个条目的详情页（地图 / 存档 → 地图详情；蓝图 → 蓝图详情；模组 → 模组页）。 */
    void openItem(ItemInfo item);

    // ══ 回调 ═══════════════════════════════════════════════════════════════

    /** 选完之后的回调（{@code index < 0} = 用户取消 / 弹不出来）。 */
    interface Choose { void onChoose(int index); }

    /** 确认框回调。 */
    interface Confirm { void onConfirm(boolean ok); }

    /** 输入框回调；{@code null} = 用户取消。 */
    interface Prompt { void onPrompt(String text); }

    /** 选文件回调；取消时两个参数都是 null。 */
    interface PickFile { void onPicked(String name, File file); }

    /** 勾选回调；取消 / 空表都给空列表（不为 null）。 */
    interface PickContent { void onPicked(List<ItemInfo> items); }

    /** 进度框被取消。 */
    interface Cancel { void onCancel(); }

    /** 保存结束（成功 / 取消 / 失败都会来）。 */
    interface After { void onAfter(Result result); }

    /** 写 / 挪的结果。 */
    interface Done { void onDone(Result result); }
}
