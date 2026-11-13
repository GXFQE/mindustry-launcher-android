package io.mdt.launcher.api;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       {@link PickContent} / {@link After} / {@link Done} / {@link Cancel} / {@link Fetch} /
 *       {@link Releases} / {@link Download} / {@link Imported} / {@link Conflicts}）都在 UI 线程。</li>
 *   <li>{@code export} / {@code share} / {@code writeItem} 的 {@link InputStream} **由宿主关闭**；
 *       {@link #pickFile} 给你的是插件 cache 里的文件，自己读。</li>
 * </ul>
 *
 * <h3>边界（先看这几条再动手）</h3>
 * <ul>
 *   <li>**没有沙箱**：靠"约定 + 告知"，不是权限门。能读宿主能读的，写只能写自己声明的地方。</li>
 *   <li>{@link #writeItem} 只许改**任意槽** maps / saves / mods / blueprints 里**已存在**的文件（v34）。</li>
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
    int DOC_VERSION = 41;

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
     * 主界面（版本列表）、设置（设置项）、日志（日志 / 崩溃报告文件）、中转站（条目）；
     * **v40** 起还有版本页（这一行对应的版本条目）与资源页（选中的那份 APK 对应哪个版本）。
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
     * **v40** 起版本页也返回这一行对应的版本条目（`ItemInfo.KIND_VERSION`）。
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

    /**
     * **v28**：这个槽的目标游戏版本里**基础语言包**（{@code assets/bundles/bundle.properties}）
     * 的键集合 —— 也就是"哪些文案键是原版真的有的"。
     *
     * <p>为什么要它：模组的 {@code bundles/*.properties} 与版本 APK 的进**同一个 map**，
     * 两个模组写同一个键时后加载的会盖掉先加载的。可这只有"原版键"才算冲突；
     * 模组自己的键（`logicsugar.*` 之类）被别的包也写一份，往往是翻译包 / fork，不是覆盖原版。
     *
     * <p>★ **空集 = 没有目标版本 / 读不出来**（不是"原版没有这些键"）—— 插件别据此下结论。
     * 第一次调用要打开版本 APK 读一份文本，之后按 APK 路径 + mtime 缓存。
     */
    Set<String> baseBundleKeys();

    /** 游戏现在在跑吗（跑着时别去动存档 / 模组文件）。 */
    boolean gameRunning();

    /**
     * **v37**：目标槽有没有游戏实例在跑 —— 写这个槽之前判断用。
     *
     * <p>与 {@link #gameRunning()} 的区别：那个是**全局**判据（"有没有任一实例在跑"），
     * 多开下会误判 —— 别的槽的游戏不影响你写这个槽。宿主自己的写门禁按**运行实例**判；
     * 插件要判"能不能写这个槽"就该用这个（再配合 {@link #runningSlots()}）。
     *
     * @param slot 槽名；{@code null} / 空 = 当前槽（{@link #slot()}）
     * @return true = 这个槽的目标版本正有一个实例在跑；**只表示调用那一刻的快照** ——
     *         真正的写操作宿主还会再判一次（TOCTOU 由宿主兜底，插件别把它当锁）
     */
    boolean gameRunning(String slot);

    /**
     * **v37**：现在哪些槽上有游戏实例在跑（只读快照；按实例启动顺序、去重）。
     *
     * <p>多开下"有游戏在跑"不等于"这个槽被占" —— 这是把归属说清楚的那一份。
     * 空表 = 没有实例在跑。与文件读写无关：**读一律放行**。
     */
    List<String> runningSlots();

    /**
     * **v38**：当前装着的**全部插件**（含停用 / 待生效 / 已隔离），只读快照。
     *
     * <p>为什么要给：插件之间没法互相发现 —— {@link #versions()} 是**游戏版本**，不是插件。
     * 有了它，"检测某台插件在不在、版本够不够、是不是内置"就不用反射或读文件（那些不是契约，
     * 宿主一改就断）。
     *
     * <p>每一条是 {@link ItemInfo}（{@code kind == }{@link ItemInfo#KIND_PLUGIN}），事实在
     * {@link ItemInfo#extra}：
     * <ul>
     *   <li>{@code id} / {@code name} / {@code state}（{@code active} / {@code pending} / {@code off}）；</li>
     *   <li>{@code version}（这一行代表的那一份）/ {@code activeVersion} / {@code pendingVersion}；</li>
     *   <li>{@code trust}（{@code builtin} / {@code official} / {@code third}）/ {@code level} /
     *       {@code builtin} / {@code quarantined}；</li>
     *   <li>自述：{@code selfVersion} / {@code author} / {@code description} / {@code entry} /
     *       {@code access}，以及声明的能力条目 {@code extCount} + {@code ext.<i>.*}；</li>
     *   <li>文件事实：{@code size} / {@code modified}。</li>
     * </ul>
     * 键的完整表在 {@code docs/PLUGIN-API.md} 第 4 节。**没装 APK / 只剩隔离件时**某些键不存在，
     * 按"缺 = 拿不到"处理，别当空值。顺序 = 插件 id 字典序。
     *
     * <p>⚠️ 会读每个插件的 APK（自述 / 签名指纹）⇒ **别在主线程调**。
     * ⚠️ {@code name} / {@code author} / {@code description} 是**插件自己写的**外部数据
     * （可能含换行 / 超长），上屏前自己做长度与字符清洗。
     */
    List<ItemInfo> plugins();

    /**
     * **v38**：已导入的**游戏补丁**（只读快照；**不判启用状态**）。
     *
     * <p>等价于 {@code patches(null, null)}。每一条是 {@link ItemInfo}
     * （{@code kind == }{@link ItemInfo#KIND_PATCH}），事实在 {@link ItemInfo#extra}：
     * {@code id} / {@code version} / {@code ref}（{@code id@version}）/ {@code name} /
     * {@code author} / {@code note} / {@code tool} / {@code filesOk} / {@code bytes} /
     * {@code targetCount}，以及每个 target 的 {@code target.<i>.sha256} / {@code .gameVersion} /
     * {@code .classes} / {@code .assets} / {@code .dexBytes} / {@code .assetsBytes}
     * （给了版本 key 时再加 {@code .match}）。
     *
     * <p>⚠️ **并不便宜**：每条记录都会读每个 target 的 dex 头（校验 + 数类）与素材 zip 目录
     * ⇒ **别在主线程调**（与下面那个重载同一个纪律）。
     */
    default List<ItemInfo> patches() {
        return patches(null, null);
    }

    /**
     * **v38**：同上，但**启用状态**按某个启动项（版本 key + 槽）算。
     *
     * @param versionKey 版本 key（{@link #versions()} 里 {@code extra["key"]}）；
     *                   {@code null} / 空 ⇒ **不判启动项**（不写 {@code enabled} / {@code applies} 键）
     * @param slot 槽名；{@code null} / 空 = 当前槽（{@link #slot()}，即**这一页**的槽）
     *
     * <p>与无参版的差异：
     * <ul>
     *   <li>{@code enabled}（{@code true} / {@code false}）= 这个启动项**现在启用**着它
     *       （读的是启用记录；版本目前装没装不影响这一条）；</li>
     *   <li>{@code applies}（{@code true} / {@code false}）= 它的某个 target 匹配**这个版本 APK**；
     *       版本找不到 / APK 读不出来 ⇒ **不放这个键**（别当 {@code false}）；</li>
     *   <li>每个 target 多一个 {@code match}（同 {@code applies} 的逐 target 版本，哈希算得出时才出现）。</li>
     * </ul>
     *
     * <p>⚠️ 会算一次版本 APK 的 sha256（几十 MB）⇒ **别在主线程调**。
     * 缓存时注意：`sha256` / `applies` 可以缓存（同版本 APK 不变），**`enabled` 是活配置、每次现读**。
     */
    List<ItemInfo> patches(String versionKey, String slot);

    // ══ v39（2026-11-13 第 373 轮）：运行实例 / 备份 / 网络 / 补丁写 ══════════

    /**
     * **v39**：当前**正在跑**的游戏实例（含启动占位）。每条是 {@link ItemInfo}
     * （{@code kind == }{@link ItemInfo#KIND_INSTANCE}），事实在 {@link ItemInfo#extra}：
     * {@code pid} / {@code vkey}（版本 key）/ {@code slot} / {@code instanceKey}（与补丁启用状态同一个键）/
     * {@code started}（毫秒时间戳）/ {@code starting}（{@code "true"} = 已经点了启动、`:game` 还没起来）。
     *
     * <p>顺序 = 启动时间升序（老的在前）。没有实例时返回空表（不是 null）。
     * 这里说的"在跑"与宿主写门禁用的是**同一份判据**（{@code Instances.live}）——
     * 别自己解析进程名，多开时 `:game` / `:gameN` 都是实例。
     *
     * <p>⚠️ 宿主**内部读取失败也返回空表**（这个接口没有失败码）：空表只能说明"这份快照里没有"，
     * 要断言"一定没在跑"请结合写门禁 / 启动请求记录，别只凭这个快照。
     */
    List<ItemInfo> instances();

    /**
     * **v39**：某个槽的**备份快照**（只读元信息）。每条是 {@link ItemInfo}
     * （{@code kind == }{@link ItemInfo#KIND_BACKUP}），事实在 {@link ItemInfo#extra}：
     * {@code created}（毫秒）/ {@code label} / {@code source} / {@code files}（条数）/
     * {@code bytes} / {@code legacy}（{@code "true"} = 旧格式备份）。
     *
     * <p>顺序 = 创建时间倒序（新的在前）。恢复/删除**不给插件做**（会动用户数据）——
     * 插件只展示、提醒。{@code slot} 空 = 当前槽（{@link #slot()}）。
     *
     * <p>⚠️ 备份清单读不出来时也返回空表（没有失败码）——别把空表当成"这个槽没备份过"的硬证据。
     */
    List<ItemInfo> backups(String slot);

    /**
     * **v39**：拉一段文本（UTF-8）。异步：回调在 UI 线程。
     *
     * <p>只放行 **https**（`http://127.0.0.1` / `http://localhost` 这类本机调试地址除外）；
     * 正文上限 512 KB（超了返回 {@code size}）；连接 15 s / 读 60 s；
     * 跟随 302（GitHub 的 asset 会跳到 `objects.githubusercontent.com`）。
     * GitHub API 的调用由宿主**统一缓存**（同一 URL 10 分钟内只真请求一次），
     * 并且有每小时上限 —— 不要轮询，超了返回 {@code rate-limited}（这是为了不让插件
     * 把启动器自更新的 GitHub 配额一起打光）。
     *
     * <p>失败码见 {@link Result}：{@code bad-url} / {@code network} / {@code size} /
     * {@code rate-limited} / {@code io} / {@code canceled}。
     */
    void fetchText(String url, Fetch callback);

    /**
     * **v39**：列一个 GitHub 仓库的 release（最新的在前，最多 30 条）。异步。
     *
     * @param repo {@code "owner/name"}（其它形状返回 {@code bad-url}）
     *
     * <p>成功时回调里是 {@link GitHubRelease} 列表；每个 {@link GitHubRelease.Asset#url} 可以直接
     * 喂给 {@link #download}。失败码：{@code bad-url} / {@code bad-data}（GitHub 返回的不是
     * JSON 数组，常见于镜像错误页 / 限频）/ {@code network} / {@code io} / {@code rate-limited}。
     *
     * <p>⚠️ 与宿主自更新**共用 GitHub 配额**（未登录 60 次/小时）⇒ 别在页面刷新里反复查。
     */
    void githubReleases(String repo, Releases callback);

    /**
     * **v39**：下载一个文件到**本插件自己的 cache**，异步，返回可取消的 {@link Task}。
     *
     * <p>落点：`<插件 cache>/downloads/<一次性子目录>/<URL 里的文件名>`（文件名会做安全清洗）；
     * 成功回调里给你 {@link File} 与实际 sha256。进度显示在宿主的前台服务通知里
     * （页面关掉也继续），**不要自己再发一条通知**。
     *
     * @param url             只认 https（本机回环除外）
     * @param expectedSha256  期望的 sha256（64 位小写十六进制）；空串 / null = 不校验，只回报实际值。
     *                        **强烈建议给**：不校验的下载等于信任链路中间人
     *
     * <p>行为：先写 `.part`，sha256 校验过才改名；任何一步失败都删掉半截文件。
     * GitHub 的下载地址会自动套用户选择的**更新线路**（镜像优先、直连兜底）。
     * 体积上限 1 GB（边下边查）；超了返回 {@code size}。失败码：{@code bad-url} / {@code network} /
     * {@code size} / {@code sha} / {@code invalid} / {@code io} / {@code canceled}。
     *
     * <p>⚠️ 每次下载一个**独立子目录**（两个 Task 并发下载同名文件不会互相删/覆盖）；
     * GitHub API 资产地址也计入 40 次/小时配额（与 {@link #fetchText} 同一份记账）。
     */
    Task download(String url, String expectedSha256, Download callback);

    /**
     * **v39**：启用一条游戏补丁（写操作）。
     *
     * <p>宿主侧做全部门禁与校验（与补丁页同一份实现）：这个启动项的实例在跑 ⇒ {@code game_alive}；
     * 补丁文件缺失 ⇒ {@code missing}；不适用于这个版本的 APK ⇒ {@code no_match}；
     * 已经启用 ⇒ {@code enabled}；与这条启动项已启用的补丁冲突 ⇒ {@code conflict}
     * （先用 {@link #patchConflicts} 拿冲突名单，再让用户决定）。
     *
     * <p>其余失败码：{@code not-found}（版本 key + 槽不是存在的启动项）/ {@code record_gone}
     * （补丁记录不存在）/ {@code invalid}（参数空）/ {@code io}（补丁库或版本信息读不出来——
     * **不会**折成 not-found / record_gone / no_match）。
     *
     * @param versionKey 版本 key（{@link #versions()} 的 {@code extra["key"]}）
     * @param slot       槽；{@code null} / 空 = 当前槽（{@link #slot()}）
     * @param ref        补丁引用（{@link #patches()} 的 {@code extra["ref"]}，形如 {@code id@version}）
     *
     * <p>⚠️ 这是"改状态"：插件自己负责在动手前问用户（想和宿主风格一致就用 {@code showCard}）。
     * 补丁能不能真的生效由**启动时**决定；启用只写记录，不会去改正在跑的进程。
     */
    void patchEnable(String versionKey, String slot, String ref, Done callback);

    /**
     * **v39**：停用一条游戏补丁。实例在跑 ⇒ {@code game_alive}；本来就没启用 ⇒ 成功（幂等）。
     */
    void patchDisable(String versionKey, String slot, String ref, Done callback);

    /**
     * **v39**：导入一个补丁包（zip 的 {@link InputStream}，**由宿主关闭**）。
     *
     * <p>宿主先落到本插件 cache 的临时文件，再走 {@code Patches.importPackage} 的完整校验
     * （清单 / dex / 素材 / 体积 / 重复），导入完删临时件。成功回调里给新补丁的 {@code ref}。
     *
     * <p>失败码：{@code bad_zip} / {@code bad_json} / {@code schema} / {@code field} /
     * {@code no_targets} / {@code bad_hash} / {@code bad_path} / {@code missing_entry} /
     * {@code bad_dex} / {@code bad_assets} / {@code dup} / {@code too_big} / {@code io}。
     * 体积上限沿用补丁机制自己的 256 MB（不是无限）。
     */
    void patchImport(InputStream zip, Imported callback);

    /**
     * **v39**：删除一条游戏补丁 —— **挪进中转站**（不是裸删），能从宿主的「中转站」放回。
     *
     * <p>还被某个启动项启用着 ⇒ {@code in_use}（先 {@link #patchDisable}）；
     * 记录已不在 ⇒ {@code record_gone}；挪失败 ⇒ {@code io}。
     */
    void patchDelete(String ref, Done callback);

    /**
     * **v39**：启用前的**冲突预检**（只读，但会读 dex / 素材，慢 ⇒ 后台线程，异步回调）。
     *
     * <p>成功时回调里给"已启用且与它冲突"的补丁**显示名列表**（空表 = 没冲突）。
     * 失败码（与 {@link #patchEnable} 的门禁顺序一致：实例在跑 → 文件缺失 → APK 匹配 → 已启用 → 冲突）：
     * {@code not-found} / {@code record_gone} / {@code game_alive} / {@code missing} /
     * {@code no_match} / {@code enabled} / {@code bad_dex} / {@code invalid} / {@code io}。
     */
    void patchConflicts(String versionKey, String slot, String ref, Conflicts callback);

    // ---- v40 新增：插入能力（宿主卡片 / 跳页；`plugin.json` 的 place 不在这层接口）-------

    /**
     * **v40**：用**宿主的卡片**弹一段信息 + 最多 3 个按钮（宿主风格；**不要自己 `new AlertDialog`**，
     * 那会叠两个窗口、主题也不一样）。异步：回调在 UI 线程。
     *
     * <p>按钮按数组顺序渲染（左 → 右）；空串 / null **跳过**（所以"最多 3 个"指前 3 个**非空**的，
     * 回调给的仍是它在原数组里的下标）。用户点某个按钮 ⇒ {@code onCard(原数组下标)}；
     * 返回键 / 点卡片外面 / 点那颗「关闭」⇒ {@code onCard(-1)}。**回调只来一次**
     * （点了按钮就不再补 -1）。同一页已经开着一张插件卡片时，新卡片会先收掉旧的。
     *
     * <p>⚠️ 按钮文案要短（两个字的动作词最好）、依据放 {@code body}：
     * Android 的按钮一长就竖排，卡片会变得很难看。
     *
     * @param title   标题（null / 空 ⇒ 不显示标题）
     * @param body    正文（null / 空 ⇒ 只有标题与按钮）
     * @param buttons 按钮文案；null / 空数组 / 全是空串 ⇒ 只给一个「关闭」
     * @param callback 结果回调（可为 null；没有可用的界面时也回 -1，调用方不会永远等）
     */
    void showCard(String title, String body, String[] buttons, Card callback);

    /**
     * **v40**：跳到宿主的某个页面（插件自己的"入口"点进去后可以接着用宿主界面）。
     *
     * <p>认得的 {@code page}：{@code main} / {@code slots} / {@code slot} / {@code saves} /
     * {@code maps} / {@code mods} / {@code blueprints} / {@code settings} / {@code logs} /
     * {@code trash} / {@code plugins}。其余名字 ⇒ **什么也不做**（不崩、不弹窗、不回调）。
     * 需要"哪一份"的页面（四个内容页与槽页）用 {@code slot}；null / 空 = **当前槽**
     * （宿主会替你补成当前槽，不会把空槽丢给页面）。
     *
     * <p>⚠️ 版本页 / 补丁页要「哪一份」的版本 key，本接口不带 ⇒ 不开放（插件自己的页面里
     * 想给用户看这些，用 {@link #patches()} 等数据画，不要硬跳）。
     */
    void openPage(String page, String slot);

    // ---- v41 新增：宿主组件工具箱 / 打开自己的插件页 --------------------------------

    /**
     * **v41**：宿主组件工具箱（卡片 / 行 / 按钮 / 分割线 / 键值 / 小标题 / 正文）。
     *
     * <p>自绘插件优先用它拼界面 —— 造出来的零件与宿主自己的卡片、操作行**同一套资源与主题色**，
     * 用户看不出"这是插件画的"（用户 2026-11-13：「尽可能让用户装了插件以后是无感的」）。
     *
     * <p>用法：主线程调用；把返回的 View `addView` 到自己的容器里；需要点击就自己
     * `setOnClickListener`。不要在外面再套自定义背景（零件自带）。
     */
    PluginUi ui();

    /**
     * **v41**：打开**插件自己的整页**（就是 `content` 槽点进去的那一页）。
     *
     * <p>给 `section` / `action` 用：插件在宿主页面里画了一段摘要，用户想"看全部"时可以直接
     * 进自己的页，不用绕到「插件」卡里的那一行。没有可用页面（动作跑在后台）⇒ 什么都不做。
     */
    void openPluginPage();

    /**
     * 插件自己的**数据目录**（`<hub>/usage/`）—— **宿主只给目录，不写任何流水、不解析账本**。
     *
     * <p>2026-10-26 定案：注入的数据完全由插件自己写（探针写 {@code ProbeEnv.dir()}），
     * 宿主只负责注入与调度。旧版本（v18~v28 期间）宿主写过 {@code launch.tsv} / {@code run.tsv}，
     * **现在不再产生**，别依赖它们存在。
     *
     * <p>目录里有什么、格式如何，都由插件自己定；宿主不解析。
     * ★ 唯一的例外是**显式声明**：`plugin.json` 里写 {@code "playtimeTotal": "<文件名>"} 时，
     * 宿主只读 `<插件>/data/<文件名>` 里的**一个整数毫秒总数**，排版进主界面摘要卡那一行
     * （v30；措辞归宿主）。它不读账本、不按槽/版本算；插件不声明就一行都不出现。
     * 详见 {@code docs/PLUGIN-API.md} 第 9 节。
     */
    File usageDir();

    /**
     * 当前槽的**崩溃报告目录**（只读，v22）。
     *
     * <p>里面既有游戏自己写的 {@code crash_*.txt}，也有启动器写的
     * {@code crash_*_launcher.txt}；格式与归因方法见 {@code docs/crash-corpus/}。
     * 目录可能不存在（这个槽没崩过）—— **也会返回路径**，自己判 {@code isDirectory()}。
     *
     * <p>给 analyzer / 归因类插件读原料用；宿主不保证文件名以外的任何顺序。
     */
    File crashReportsDir();

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
     * 同上，但**那一屏的两句话由插件自己给**（v32）。
     *
     * <p>2026-10-27 用户原话：「这个页面标题能不能不是『选中后交给插件』啊」⇒「改成插件可以自行设置文本」。
     * 宿主默认那句（「选中后交给插件」）是给"不知道谁在要"的场合兜底的；知道自己在做什么的插件
     * 应该说人话，比如「勾选要统计的地图」。
     *
     * <p>★ 规则（宿主会照做，别指望绕过）：
     * <ul>
     *   <li>{@code title} = 那一屏的**标题**（null / 空 ⇒ 宿主默认「选择条目」），最多 24 个字；</li>
     *   <li>{@code head} = 标题下面那行**说明**（null / 空 ⇒ 宿主默认「选中后交给插件」），最多 80 个字；</li>
     *   <li>换行 / 连续空白会被压成一个空格，超长会**截断**（别把整段说明塞进来，那是插件页面的事）；</li>
     *   <li>正按钮仍然由宿主写（「完成（N）」里的 N 是勾了几份，插件给不了）。</li>
     * </ul>
     *
     * <p>⚠️ 用这个方法就**必须**在 {@code plugin.json} 里报 {@code "api": 32} 以上 ——
     * 老宿主没有它（会抛 {@code NoSuchMethodError}）。
     */
    void pickContent(String kind, String title, String head, PickContent callback);

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
     * 后台任务的句柄（v22）。
     *
     * <p>生命周期 = **本进程**：页面关掉、人切走都照跑；进程被系统杀就没了
     * （没有 WorkManager，本工程纯 framework）⇒ 下载要断点续传、分析要把 checkpoint
     * 落进 {@link #dataDir()}。
     */
    interface Task {
        /** 本次任务 id（日志 / 报告用；同一进程内不重复）。 */
        String id();

        /** 还在跑吗（完成 / 失败 / 取消之后返回 false）。 */
        boolean running();

        /**
         * 请求取消：置标记 + {@link Thread#interrupt()}。
         *
         * <p>任务体要自己配合 —— 阻塞 IO 会收到中断，纯计算请轮询
         * {@code Thread.currentThread().isInterrupted()}。
         */
        void cancel();
    }

    /**
     * **v22**：提交一个后台任务 —— 页面关了照跑，完成 / 失败 / 被取消各发一条通知。
     *
     * <p>与 {@link #runBackground} 的关系：同一个底座（独立线程 + 前台服务），差别只有两点：
     * <ul>
     *   <li>拿到 {@link Task} 句柄，可以 {@link Task#cancel()}、可以问 {@link Task#running()}；</li>
     *   <li>结束时会发一条**完成通知**（成功 / 失败 / 已取消）—— 用户切走之后也知道结果。</li>
     * </ul>
     *
     * <p>⚠️ 页面关闭时宿主会调 {@code unload()}，**但任务不会因此停止**；
     * 想把"页面收尾"和"任务取消"分开的插件，别在 {@code unload} 里取消任务。
     *
     * <p>{@code body} 在后台线程执行，异常被宿主接住并写 `MDTPlugin` 日志（不会带走进程）。
     * {@code label} 是通知标题 / 正文（给用户看的一句话，可为 null）。
     */
    Task submit(String label, Runnable body);

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
     * **v25**：用**宿主的** HJSON 解析器把一个文件转成标准 JSON 文本（插件不用自己实现解析）。
     *
     * <p>宿主先按 ARC 160 的方言解析，失败再按 159 试一次（与宿主自己读 {@code mod.hjson}
     * 完全同一条路）；都失败 ⇒ {@code null}。读不出来（不存在 / 权限）也返回 {@code null}。
     *
     * <p>返回值是**标准 JSON 文本**，插件用 {@code org.json.JSONObject} / {@code JSONArray} 自己取字段 ——
     * 怎么用（判据、阈值）归插件。
     *
     * <p>⚠️ 会读文件 ⇒ **别在主线程对大文件调**（模组包的 meta 只有几 KB，没问题）。
     */
    String parseHjson(File file);

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
     * **v34**：任意槽的 {@code settings.bin} 只读快照（与 {@link #gameSettings()} 同一套解析）。
     *
     * @param slot 槽名；{@code null} / 空 ⇒ 当前槽。槽不存在 / 名字非法 ⇒ {@code null}；
     *             文件不存在 ⇒ 空表；坏文件 ⇒ {@code null}。
     *
     * <p>⚠️ 跨槽读是**只读**，宿主不检查游戏进程；真实文件很大 ⇒ 别在主线程调。
     */
    Map<String, Object> gameSettings(String slot);

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
     * **v34**：改任意槽的游戏设置（合并写入；值为 {@code null} ⇒ 删键）。
     *
     * @param slot 槽名；{@code null} / 空 ⇒ 当前槽；不存在 / 非法 ⇒ {@code not-found}。
     *
     * <p>安全机制与 {@link #writeGameSettings(Map, Done)} 完全相同，备份也落在**目标槽**的
     * {@code settings-backups/slot-<槽>/}。跨槽写让插件能实现"复制设置 / 批量改"这类功能；
     * **目标槽由插件负责确认**（宿主只做槽名合法性与写安全，不判断插件语义）。
     */
    void writeGameSettings(String slot, Map<String, Object> changes, Done done);

    /**
     * **v35**：往任意槽**新建**一个内容文件（不是替换；同名已存在会失败 {@code exists}）。
     *
     * @param slot 槽名；{@code null} / 空 ⇒ 当前槽；不存在 / 非法 ⇒ {@code not-found}
     * @param kind {@code saves} / {@code maps} / {@code mods} / {@code blueprints}；
     *             分别落在 {@code saves/} / {@code maps/} / {@code mods/} / {@code schematics/}
     * @param name 完整文件名（**必须自带合法后缀**）：saves / maps = {@code .msav}，
     *             mods = {@code .jar} / {@code .zip}，blueprints = {@code .msch}；
     *             不允许路径分隔符、控制字符、隐藏名或 {@code .} / {@code ..}
     * @param content 文件字节；宿主读完负责关闭
     * @param done 后台写完后回主线程；失败 reason = {@code exists} / {@code game-running} /
     *             {@code not-found} / {@code invalid} / {@code io}
     *
     * <p>🔴 **宿主只做路径 / 后缀 / 槽名 / 原子写，不做内容校验**：`.msav` 是不是合法存档、
     * mod zip 里有没有 `mod.hjson`、`.msch` 能不能解析，全部归插件。写进去的坏文件游戏可能不认，
     * 宿主不会替插件回滚（新文件没有旧版本可还原）。
     *
     * <p>游戏在跑 ⇒ 拒绝。新文件不备份；要替换已有文件，用 {@link #writeItem(ItemInfo, InputStream, Done)}。
     */
    void addItem(String slot, String kind, String name, InputStream content, Done done);

    /**
     * 把一个条目的内容**换掉**（仅限**任意槽** maps / saves / mods / blueprints 里的**已存在**文件，
     * v34 起跨槽：用 {@code contents(slot, kind)} 取到的 {@link ItemInfo} 即可）。
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

    // ---- v39 新增的回调 -------------------------------------------------------

    /** {@link #fetchText} 的结果：成功时 {@code text} 非 null，失败时 {@code result.ok == false}。 */
    interface Fetch { void onFetch(Result result, String text); }

    /** {@link #githubReleases} 的结果：失败时列表为 null。 */
    interface Releases { void onReleases(Result result, List<GitHubRelease> releases); }

    /**
     * {@link #download} 的结果：成功时 {@code file} 是 cache 里的成品、{@code sha256} 是实际哈希；
     * 失败时 {@code file} 为 null、{@code result.reason} 说明原因。
     */
    interface Download { void onDownload(Result result, File file, String sha256); }

    /**
     * {@link #patchImport} 的结果：成功时 {@code ref} 是新补丁的引用（可直接喂给
     * {@link #patchEnable}）；失败时为 null。
     */
    interface Imported { void onImported(Result result, String ref); }

    /**
     * {@link #patchConflicts} 的结果：成功时 {@code names} 是与它冲突的已启用补丁显示名
     * （空表 = 没冲突）；失败时为 null。
     */
    interface Conflicts { void onConflicts(Result result, List<String> names); }

    /** **v40**：{@link #showCard} 选了哪个按钮；{@code -1} = 没选（返回 / 点外面关掉）。 */
    interface Card { void onCard(int index); }
}
