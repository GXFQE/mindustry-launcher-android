package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import io.mdt.launcher.api.Finding;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组管理（F13 · **第一阶段：只读扫描**）。
 *
 * ── 这一页回答的三个问题（按价值排序，与 FLOWS F13 的目标一致）─────────────
 *  1. ★ **这个模组要求多高的游戏版本？** —— `minGameVersion` 与"指向本槽的游戏版本"比对。
 *     玩家最容易踩、最难自查的坑，而启动器**同时握着两边**，是唯一能提前警告的人。
 *  2. **游戏自己那个开关现在是开还是关？**（`settings.bin` 的 `mod-<内部名>-enabled`）
 *     —— ⚠️ **键不存在 = 启用**，不是禁用；读不到 `settings.bin` 时必须**说出来**，
 *     否则会把"读失败"显示成"全部启用"（最误导人的一种失败）。
 *  3. **为什么它没生效？** —— 加载门**逐条**报（v27 起判据与文案都在 `mods-health` 插件里，
 *     本页只把插件给的六个门画出来）；外加"元数据解析失败"和"依赖缺失/循环"这两条会
 *     **静默**吞掉模组的路径。
 *
 * ── 为什么跨槽视图做成"换个槽看"而不是另开一页 ──────────────────────────
 *  模组住在**槽内部**（`<数据根>/mods/`，M3 白送按槽隔离）⇒ "跨槽视图"就是
 *  "换一个槽再看一遍"。做成页面里的一行（一个入口），而不是并列第二个界面
 *  —— 本工程栽过"同一件事两个入口"（F15b），用户判定 **重复 > 便利**。
 *
 * ── 本页**一个字节都不写**（这一阶段的硬边界）────────────────────────────
 *  · 不 mkdirs（F6 的教训：只看一眼造出的空目录会被下一轮当成"有内容"）；
 *  · 不改 `settings.bin`（启停要写它，而那有三条硬约束：改前备份 / 原子写 / 写后自检，
 *    见 {@link SettingsBin} 的类注释）—— **等这一阶段验收过、定案再做**。
 */
public class ModsActivity extends BaseActivity implements PluginEntries.FindingsSink {

    /** v24（第 226 轮）：本页的插件挂载点（content / action / section 统一由 BaseActivity 挂）。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_MODS; }

    @Override protected String pluginSlot() { return mSlot; }

    /** 从哪个槽进来（不传 = 当前槽）。跨槽视图就靠它 */
    public static final String EXTRA_SLOT = "slot";
    /** 旋转重建时保住"用户选了哪个槽"（见 {@link #onCreate} 的注释） */
    private static final String STATE_SLOT = "mdt-mods-slot";
    private static final String STATE_QUERY = "mdt-mods-query";
    private static final String STATE_SORT = "mdt-mods-sort";
    private static final String STATE_ONLY = "mdt-mods-only";
    /** 一档⑤：类型筛选也要活过转屏（同 STATE_ONLY 那条教训） */
    private static final String STATE_TYPE = "mdt-mods-type";
    /** SAF 请求码（导入模组包） */
    private static final int REQ_MOD_IMPORT = 61;

    private String mSlot;
    /** 槽是不是**外面给定的**（槽二级页面传进来的 ⇒ 本页不给"换槽"入口） */
    private boolean mSlotFixed;
    private Mods.Scan mScan;
    /**
     * 当前**显示出来的**那批模组（第 157 轮）：删除的勾选框按它取条目。
     * ★ 必须与列表同一次 `filterAndSort` —— 另扫一遍就会"勾了 A、删了 B"。
     */
    private List<Mods.Info> mShown = new ArrayList<>();
    private Mods.Resolved mResolved;

    /**
     * 指向本槽的游戏版本 + 其中**最低**的那个（`minGameVersion` 比对的基准）。
     * ★ 算法只有一处实现（{@link Mods#targetsFor}）—— 模组页与 dev 口共用，
     *   否则"页面上说版本不符、报告里说没问题"这种分叉不会有任何报错。
     */
    private Mods.Target mTarget = new Mods.Target();
    /** 搜索词 / 排序方式 / 只看有问题的 / 类型（**纯前端**，不写任何文件） */
    private String mQuery = "";
    private int mSort = Mods.SORT_NAME;
    private boolean mOnlyProblems;
    /** 一档⑤：类型筛选（{@link Mods#TYPE_ANY} = 不筛）。判据 = `Mods.matchesType` */
    private int mType = Mods.TYPE_ANY;
    private EditText mSearch;
    private TextView mFiltered;

    private LinearLayout mContainer;
    private TextView mEmpty;
    private TextView mSummary;
    private TextView mDetail;
    /** F23：上次崩溃的归因那一行（默认 GONE；只在点名成功时显示） */
    private TextView mCrashLine;
    /** 「上次启动没跑完」这句**全槽级**的话（默认 GONE） */
    private TextView mFailNote;
    /**
     * 归因器点名的那几个模组（内部名，小写）。★ 按行标记**只许标这里面的** ——
     * 游戏自己在 settings 里把所有模组都标成 failed，照那个逐行显示就会冤枉无关模组
     * （2026-10-08 用户真机反馈："为什么无关模组也被判定到了"）。
     */
    private java.util.Set<String> mBlamed = new java.util.HashSet<>();
    /**
     * v25（第 229 轮）：分析器**挂到某一项上**的结论（key = `ItemInfo.path`）。
     *
     * <p>不挂的结论仍然进页面底部那张「插件」卡（`PluginEntries.showFindings`）；
     * 挂了的是"这一行有问题"，画在这一行的 ⚠ 区里，`detail` 进详情弹窗。分析器在**列表渲染之后**
     * 才回来（后台线程），所以拿到时要 `rebuildList()` 一次。
     */
    private final Map<String, List<Finding>> mIssues = new HashMap<>();
    /**
     * v26：插件对每一项的判定（path → token）。**有就以它为准**（徽标/筛选/详情共用同一个判定），
     * 没有就回落到宿主自己的 {@code Mods.stateOf}（插件没装 / 崩了 / 旧版插件）。
     */
    private final Map<String, String> mVerdicts = new HashMap<>();
    /**
     * v27：插件给的**槽级结论**（`SURFACE_SUMMARY`）—— 非空时摘要卡用它，
     * 空时回落到宿主自己的 {@code ModsText.cardNote}（插件没装 / 旧版）。
     */
    private final java.util.List<String> mSlotNotes = new java.util.ArrayList<String>();
    /**
     * v27：插件给的「六道加载门」结论（path → 门）。空表 = 没有插件给结论 ⇒
     * **详情页那一段不显示**（宿主自己那份判据 v27 已删，只有插件这一个来源）。
     */
    private final java.util.Map<String, java.util.List<io.mdt.launcher.api.Gate>> mGates =
            new java.util.HashMap<String, java.util.List<io.mdt.launcher.api.Gate>>();
    /**
     * v25：插件注入的**筛选**结果（item path 集合；null = 没选）。只在选中那一刻算一次，
     * 之后每次 `rebuildList()` 直接用它 —— 别在打字搜索时反复调插件。
     */
    private java.util.Set<String> mPluginFilterPaths;
    /** 当前选中的插件筛选（`pluginId/itemId`，界面打勾用） */
    private String mPluginFilterKey;
    /** v25：插件注入的**排序**结果（按顺序的 item path；null = 没选） */
    private List<String> mPluginSortPaths;
    private String mPluginSortKey;
    private TextView mSlotSub;
    private TextView mCopySub;

    /**
     * 导入中的目标槽 —— 必须跨 `onActivityResult` 存活（用户在系统选择器里待多久都有可能），
     * 且**无论成败先清**：否则用户取消后残留的目标会被下一次选择"接上"（F15 踩过的状态泄漏）。
     *
     * ★★ 2026-10-04 修：改成 **static**。原来只是实例字段 ⇒ 用户在系统选择器里时
     *   一转屏（或被系统回收后回前台），发起方实例被销毁重建，新实例里它是 null
     *   ⇒ 回调**静默 return**，用户看到的是"选完文件什么都没发生"。
     *   ⚠️ 同一件事 {@link SlotIo} 早就是 static（`sMsavTarget` / `sZipSlot`），
     *      类注释里还写明了理由 —— 本类当时漏了（本类甚至已经为转屏存了 4 个 STATE_*）。
     */
    private static String sImportSlot;

    /** 第 177 轮（杀后台 P0）：SAF 待办的磁盘日志名 —— 进程被杀后靠它恢复目标槽 */
    private static final String PEND_IMPORT = "mods.import";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String s = getIntent().getStringExtra(EXTRA_SLOT);
        mSlot = (s == null || s.trim().isEmpty()) ? Data.currentSlot(this) : s.trim();
        // 进页面时**槽是给定的**（槽二级页面传进来的）⇒ 本页不再提供"换槽"
        mSlotFixed = !(s == null || s.trim().isEmpty());
        // ★ 旋转屏幕会重建 Activity（本页没声明 configChanges）⇒ 必须把"用户在本页里
        //   选了哪个槽"记下来，否则一转屏就悄悄跳回进来时的那个槽（默认槽）——
        //   用户看到的是"我选的槽没了"，而日志里什么都不会有。
        if (savedInstanceState != null) {
            String kept = savedInstanceState.getString(STATE_SLOT);
            if (kept != null && !kept.trim().isEmpty()) mSlot = kept;
            // ★ 搜索/筛选/排序也要活过转屏（同 STATE_SLOT 那条教训：一转屏就变回默认，
            //   用户看到的是「我打的字没了」，而日志里什么都不会有）
            String q = savedInstanceState.getString(STATE_QUERY);
            if (q != null) mQuery = q;
            mSort = savedInstanceState.getInt(STATE_SORT, Mods.SORT_NAME);
            mOnlyProblems = savedInstanceState.getBoolean(STATE_ONLY, false);
            mType = savedInstanceState.getInt(STATE_TYPE, Mods.TYPE_ANY);
        }
        buildUi();
        rescan();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SLOT, mSlot);
        outState.putString(STATE_QUERY, mQuery);
        outState.putInt(STATE_SORT, mSort);
        outState.putBoolean(STATE_ONLY, mOnlyProblems);
        outState.putInt(STATE_TYPE, mType);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        // 槽与"版本 → 槽"的分配可能在别处改过（Config 是进程内单例 ⇒ 必须 reload，REF §28）
        Config.get().reload(this);
        rescan();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildUi() {
        View root = getLayoutInflater().inflate(R.layout.activity_mods, null);
        mSummary = (TextView) root.findViewById(R.id.mods_summary);
        mDetail = (TextView) root.findViewById(R.id.mods_detail);
        mCrashLine = (TextView) root.findViewById(R.id.mods_verdict);
        mFailNote = (TextView) root.findViewById(R.id.mods_fail_note);
        Util.bindExpandableCard(root, R.id.mods_status_box, R.id.mods_detail, R.id.mods_chevron);

        View row = Util.bindActionValue(root, R.id.row_mod_slot, R.drawable.ic_folder,
                R.string.mods_row_slot_title, new Runnable() {
                    @Override public void run() { pickSlot(); }
                });
        mSlotSub = (TextView) row.findViewById(R.id.act_sub);
        // ★ 导航重构（REF §56）：从**槽二级页面**进来时槽已经定死 ⇒ 不再给"选槽"这个入口
        //   （用户 2026-10-03：「反正都要选槽」）。没有槽参数时保留旧行为（兼容老入口）。
        // ★ 2026-10-06（三档）：**连同它下面那条分割线一起收掉** —— 只 GONE 行本身的话，
        //   卡片顶上会留下一条**悬空横线**（用户视角就是"这里少了一行"，而编译器无感）。
        //   分割线现在有 id（`div_mod_slot`），两件事必须一起做。
        if (mSlotFixed) {
            row.setVisibility(View.GONE);
            View div = root.findViewById(R.id.div_mod_slot);
            if (div != null) div.setVisibility(View.GONE);
        }

        Util.bindAction(root, R.id.row_mod_import, R.drawable.ic_download,
                R.string.mods_act_import_title, R.string.mods_act_import_sub, new Runnable() {
                    @Override public void run() { pickModPackage(); }
                });
        // ★ 第 127 轮：出口（模组这条线原来只有进口 —— 整槽 zip 拿不出"单独某几个模组"）
        Util.bindAction(root, R.id.row_mod_export, R.drawable.ic_upload,
                R.string.mods_export, R.string.mods_export_sub, new Runnable() {
                    @Override public void run() { pickModExport(); }
                });
        View copyRow = Util.bindActionValue(root, R.id.row_mod_copy, R.drawable.ic_zip,
                R.string.mods_act_copy_title, new Runnable() {
                    @Override public void run() { pickCopyTarget(); }
                });
        mCopySub = (TextView) copyRow.findViewById(R.id.act_sub);
        Util.bindAction(root, R.id.row_mod_filter, R.drawable.ic_settings,
                R.string.mods_act_filter_title, R.string.mods_act_filter_sub, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });
        Util.bindAction(root, R.id.row_mod_batch, R.drawable.ic_add,
                R.string.mods_act_batch_title, R.string.mods_act_batch_sub, new Runnable() {
                    @Override public void run() { pickBatch(); }
                });
        // ★ 第 157 轮：四个内容页都能删（旧件挪进中转站）
        Util.bindAction(root, R.id.row_mod_del, R.drawable.ic_trash,
                R.string.batch_del_row, R.string.batch_del_sub, new Runnable() {
                    @Override public void run() { promptDeleteMods(); }
                });

        // v24（第 226 轮）：插件卡由 BaseActivity → PluginEntries.attachPage 统一挂
        //   （content 行 / action 行 / section 段落都在那张卡里；模组页不再单独 attach）

        mContainer = (LinearLayout) root.findViewById(R.id.mod_container);
        mEmpty = (TextView) root.findViewById(R.id.mod_empty);
        // 第②项：搜索框（纯前端过滤，只在内存里筛，一个文件都不碰）
        mSearch = (EditText) root.findViewById(R.id.mod_search);
        mFiltered = (TextView) root.findViewById(R.id.mod_filtered);
        // ★ 2026-10-07（第 122 轮）：接搜索框那段收进了 `Util.bindSearch` —— 地图 / 蓝图 / 存档
        //   三页也要它（"恢复文字 + 光标到末尾 + 输入即刷新"三步的顺序是有讲究的，见那里的注释）。
        Util.bindSearch(mSearch, mQuery, new Runnable() {
            @Override public void run() {
                mQuery = mSearch == null ? "" : mSearch.getText().toString();
                rebuildList();
            }
        });

        Util.applySystemInsets(root);
        setContentView(root);
        // ★ 第 157 轮：四个内容页的标题统一成「槽「X」的X」（原来只有存档 / 地图带槽名）
        setTitle(Trans.get(this, R.string.mods_title_fmt, mSlot));

        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.mod_search, R.string.mods_search_hint);

        // F9 深链：本页是被外部"打开方式"（浏览器 / 文件管理器）叫起来的
        //   ⇒ 与 SAF 那条路**同一份**分流判据（见 importDocs）
        final java.util.List<BatchIo.Doc> dl = DeepLink.docsOf(getIntent(), this);
        if (!dl.isEmpty()) importDocs(dl, mSlot);
    }

    // ── F13 第三阶段：导入模组包 / 跨槽复制 ────────────────────────────────

    /**
     * 挑模组包（`.zip` / `.jar`）—— **一次可以选多个**（第 127 轮）。
     * 落点就是**当前正在看的那个槽**。
     * ⚠️ MIME 不设限（`setType` 传通配）：各家文件管理器对 zip/jar 报的 MIME 五花八门
     *   （`.msav` 那条路已经栽过一次），限死会让用户选不中自己的文件。
     */
    private void pickModPackage() {
        // ★ 2026-10-04：**先判游戏在不在跑**（拦在 SAF 选择器之前）。导入是往槽的 mods/ 里**写**
        //   （目录形态模组还会把旧件挪去中转站），而工程里凡"写槽"的路径都有这道门禁
        //   （导入存档/整槽、备份、恢复、改设置、跨槽复制）；模组导入原来漏了。
        if (Data.gameAlive(this)) {
            alert(Trans.get(ModsActivity.this, R.string.game_busy_title), Trans.get(ModsActivity.this, R.string.mods_import_busy_msg));
            return;
        }
        sImportSlot = mSlot;
        // ★ 第 177 轮：目标槽同时写进磁盘 —— 系统在选择器打开时把进程杀掉，
        //   静态字段就没了；旧代码静默 no-op（真机 2.7 实测）。
        JSONObject meta = new JSONObject();
        try {
            meta.put("slot", mSlot);
        } catch (Throwable ignored) {
        }
        Pending.put(this, PEND_IMPORT, meta);
        BatchIo.pickFiles(ModsActivity.this, REQ_MOD_IMPORT, R.string.chooser_pick_mod);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // ★ 第 127 轮：批量导出的落点（勾了多份时走它；一份仍走单份那条路，没有 SAF 回调）
        if (BatchIo.handleExportResult(this, requestCode, resultCode, data)) return;
        if (requestCode != REQ_MOD_IMPORT) return;
        // ★ 与 F15/F6 同一条纪律：无论成败**先清目标槽**，否则取消后残留的目标会被下一次接上
        final JSONObject pend = Pending.take(this, PEND_IMPORT);
        String slot = sImportSlot;
        sImportSlot = null;
        if (slot == null && pend != null) slot = pend.optString("slot", null);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (slot == null || slot.trim().isEmpty()) {
            // 进程被杀过、磁盘待办也没了 ⇒ **不能猜**槽；明说一次，别静默丢掉用户选的文件
            Toast.makeText(this, R.string.pending_lost, Toast.LENGTH_LONG).show();
            return;
        }
        importDocs(BatchIo.docsOf(this, data), slot);
    }

    /**
     * 导入入口的**唯一**判据 —— SAF 那条路（{@link #onActivityResult}）与 F9 深链那条路共用。
     *
     * <p>三条：① 游戏在跑 ⇒ 先挡（写槽的门禁；按钮那边也有一道同样的预检，
     * 只是为了让用户别白选一次文件）；② 多份 ⇒ 批量；③ 单份走下面那条带 probe 的路。
     *
     * <p>★ 模组比另外三类**多一层**：一个 `.zip` / `.jar` 有三种可能 —— **一个模组包**
     * （99% 的情况）、**我们自己导出的一整包**（把一槽模组导出成 zip 再导回来）、
     * **一堆模组文件夹**打成的 zip（包里一条 .jar/.zip 都没有）。后两种必须解开逐份导，
     * 而判据只有读过 zip 才知道 ⇒ 单份要先**后台探一下**（`modsBatchWanted` 一看到包根的
     * `mod.hjson` 就立刻返回，常见情况只读前几个条目）。
     * ⇒ 所以这里**不能**用 {@link BatchIo#wantsBatch} 替掉 ②：那会把"一个 .jar"直接当批量，
     *   而"一个 .jar 到底是单个模组还是整包"正是要靠 probe 分的。
     */
    private void importDocs(java.util.List<BatchIo.Doc> docs, final String slot) {
        if (docs == null || docs.isEmpty()) return;
        if (Data.gameAlive(this)) {
            alert(Trans.get(ModsActivity.this, R.string.game_busy_title),
                    Trans.get(ModsActivity.this, R.string.mods_import_busy_msg));
            return;
        }
        // ★ 第 127 轮：多选 ⇒ 批量（按文件名落位，同名只问一次）
        if (docs.size() > 1) {
            BatchIo.runImport(this, slot, docs, BatchIo.KIND_MODS, new Runnable() {
                @Override public void run() { rescan(); }
            });
            return;
        }
        final BatchIo.Doc one = docs.get(0);
        if (!BatchIo.isArchiveName(one.name)) {
            doImport(slot, one.uri, one.name, false);       // 非 .zip/.jar：老路（由它去报"只认这两种"）
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean bundle = BatchIo.modsBatchWanted(ModsActivity.this, one);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(ModsActivity.this)) return;
                        if (bundle) {
                            BatchIo.runImport(ModsActivity.this, slot, docs, BatchIo.KIND_MODS,
                                    new Runnable() {
                                        @Override public void run() { rescan(); }
                                    });
                            return;
                        }
                        // ★ 这里**必须**传 false（2026-10-04 澄清）：原来传的是一个恒为 false 的
                        //   实例字段（`mImportOverwrite`，全工程找不到任何一处写 true ⇒ 死字段，已删）。
                        //   "用户同意替换"这件事**不经过这里** —— 它是 doImport 的结果分支里
                        //   发现同名后弹框、再直接 `doImport(..., true)` 走一遍（见那里）。
                        doImport(slot, one.uri, one.name, false);
                    }
                });
            }
        }, "mod-bundle-probe").start();
    }

    /**
     * 删除模组（第 157 轮）：与地图 / 蓝图 / 存档**同一套** —— 勾选 → 确认 → **挪进中转站**。
     *
     * <p>★ 勾的条目取的是 {@link #mShown}（列表**当前显示的那一批**）—— 与导出那条路
     * 故意的不同：导出允许"导全部（含被筛掉的）"，删除只能删"你眼前看到的"。</p>
     * <p>⚠️ 目录形态的模组也是一份（`BatchIo.deletableItem(..., true)`）；
     * 模组包内部自带的条目根本不在 `mods/` 下，天然进不来。</p>
     */
    private void promptDeleteMods() {
        if (mShown == null || mShown.isEmpty()) {
            Toast.makeText(ModsActivity.this, Trans.get(ModsActivity.this,
                    R.string.mods_export_none_fmt, mSlot), Toast.LENGTH_SHORT).show();
            return;
        }
        if (Data.gameAlive(this)) {
            alert(Trans.get(this, R.string.game_busy_title),
                    Trans.get(this, R.string.mods_del_busy_msg));
            return;
        }
        final List<Mods.Info> shown = new ArrayList<>(mShown);
        String[] titles = new String[shown.size()];
        String[] subs = new String[shown.size()];
        for (int i = 0; i < shown.size(); i++) {
            Mods.Info m = shown.get(i);
            titles[i] = m.title();
            subs[i] = (m.fileName == null ? "" : m.fileName) + " · " + Util.formatSize(m.bytes)
                    + (m.directory ? " · "
                            + Trans.get(ModsActivity.this, R.string.mods_export_dir_hint) : "");
        }
        BatchIo.pickMulti(this, R.string.batch_del_row,
                Trans.get(ModsActivity.this, R.string.batch_del_head_fmt, shown.size()),
                titles, subs, null, null, R.string.batch_del_btn_fmt, new BatchIo.OnPick() {
                    @Override public void onPick(List<Integer> idx, CardStack card) {
                        final File kindDir = mScan == null ? null : mScan.modsDir;
                        if (kindDir == null) return;
                        final List<File> files = new ArrayList<>();
                        for (Integer i : idx) files.add(shown.get(i.intValue()).file);
                        int can = 0, cant = 0;
                        for (File f : files) {
                            if (BatchIo.deletableItem(f, kindDir, true)) can++; else cant++;
                        }
                        if (can == 0) {
                            card.dismiss();
                            alert(Trans.get(ModsActivity.this, R.string.batch_del_row),
                                    Trans.get(ModsActivity.this, R.string.batch_del_skipped_fmt, cant));
                            return;
                        }
                        String msg = Trans.get(ModsActivity.this, R.string.batch_del_msg_fmt, can);
                        if (cant > 0) {
                            msg = msg + "\n\n" + Trans.get(ModsActivity.this,
                                    R.string.batch_del_skipped_fmt, cant);
                        }
                        card.replace(Trans.get(ModsActivity.this, R.string.batch_del_title), msg,
                                new CardStack.Btn(Trans.get(ModsActivity.this, R.string.cancel), new Runnable() {
                                    @Override public void run() { card.dismiss(); }
                                }),
                                new CardStack.Btn(Trans.get(ModsActivity.this, R.string.batch_del_ok), new Runnable() {
                                    @Override public void run() {
                                                BatchIo.DelReport rep = BatchIo.deleteAll(
                                                        ModsActivity.this, files, kindDir,
                                                        Trash.modsDir(ModsActivity.this),
                                                        new BatchIo.Deleter() {
                                                            @Override public File delete(
                                                                    android.content.Context c, File f,
                                                                    File trashDir) {
                                                                return Mods.deleteToTrash(c, kindDir,
                                                                        f, trashDir);
                                                            }
                                                        }, true);
                                                rescan();
                                                if (rep.failed == 0) {
                                                    Toast.makeText(ModsActivity.this,
                                                            Trans.get(ModsActivity.this,
                                                                    R.string.batch_del_done_fmt, rep.moved),
                                                            Toast.LENGTH_LONG).show();
                                                } else {
                                                    alert(Trans.get(ModsActivity.this, R.string.batch_del_row),
                                                            Trans.get(ModsActivity.this, R.string.batch_del_done_fmt, rep.moved)
                                                                    + "\n"
                                                                    + Trans.get(ModsActivity.this, R.string.batch_del_none_fmt, rep.failed));
                                                }
                                    }
                                }));
                    }
                });
    }

    /**
     * 导出模组（第 127 轮）—— 勾 1 个 = 老样子（**文件形态**直接给那个 `.jar`/`.zip`），
     * 勾 ≥2 个 = **一个 zip**。
     *
     * <p>★ **目录形态**的模组没有"一个文件"可给 ⇒ 两种情况下都打成 `<目录名>.zip`
     *   （内容按目录**根**写的，游戏认这个形状）。批量时它在**外层 zip 里是一个嵌套 zip**
     *   —— 这样"外层每一条 = 单份导入时用户会选到的那个文件"这条不变量才成立，
     *   于是批量导入只有一条路（见 {@link BatchIo}）。
     *
     * <p>★ 坏包（读不出说明文件的）也列出来：用户可能正是要把它导出来发给作者看。
     *   ⚠️ 口径 = `mScan.mods` + `mScan.broken`，**不含 `mScan.ignored`** —— 那些是
     *   `mods/` 里既不是包、也没有说明文件的一级目录（实测：`vne/` 里只有一个
     *   `version.txt`，是模组**自己写的数据目录**）。它们不是"能导出去的模组"；
     *   要连这些杂项一起备份，走的是整槽导出（`Data.contentRoots` 那条口径）。
     */
    private void pickModExport() {
        if (mScan == null) return;
        final List<Mods.Info> all = new ArrayList<>();
        if (mScan.mods != null) all.addAll(mScan.mods);
        if (mScan.broken != null) all.addAll(mScan.broken);
        if (all.isEmpty()) {
            Toast.makeText(ModsActivity.this, Trans.get(ModsActivity.this,
                    R.string.mods_export_none_fmt, mSlot), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[all.size()];
        String[] subs = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Mods.Info m = all.get(i);
            // ⚠️ 标题用**文件名**：导出之后用户在文件管理器里看到的就是这个（模组名可能带色码）
            titles[i] = m.fileName == null ? String.valueOf(m.file) : m.fileName;
            subs[i] = m.title() + " · " + Util.formatSize(m.bytes)
                    + (m.directory ? " · "
                            + Trans.get(ModsActivity.this, R.string.mods_export_dir_hint) : "");
        }
        BatchIo.pickMulti(this, R.string.mods_export,
                Trans.get(ModsActivity.this, R.string.batch_export_head_fmt, all.size()),
                titles, subs, null, null, R.string.batch_export_btn_fmt,
                R.string.pick_share_toggle, new BatchIo.OnPickShare() {
                    @Override public void onPick(List<Integer> idx, boolean share, CardStack card) {
                        List<Exporter.Src> srcs = new ArrayList<>();
                        for (Integer i : idx) {
                            Mods.Info m = all.get(i.intValue());
                            String name = m.file.getName();
                            if (m.directory) {
                                srcs.add(Exporter.Src.ofDir(m.file, name + ".zip"));
                            } else {
                                srcs.add(Exporter.Src.ofFile(m.file, name));
                            }
                        }
                        if (share) {
                            BatchIo.startShare(card, ModsActivity.this, srcs,
                                    BatchIo.suggestZipName("mods"));
                        } else {
                            BatchIo.startExport(card, ModsActivity.this, srcs,
                                    BatchIo.suggestZipName("mods"));
                        }
                    }
                });
    }

    /** SAF 的显示名 —— 第 127 轮起**搬进了 {@link BatchIo#displayNameOf}**：
     * 多选批量走的是同一件事（`data.getClipData()` 里每条都要问一次名字），
     * 这里再留一份迟早会出现"单选认得名字、多选认得另一个名字"这种不一致。
     */

    /**
     * 真正导入：流拷进 `<槽>/mods/<名>.part` → 按游戏判据验 → 就位。
     * 放后台线程（模组包可能几十 MB），期间给 ProgressDialog。
     */
    private void doImport(final String slot, final Uri uri, final String displayName,
                          final boolean overwrite) {
        // ★ 第 266 轮：进度与结果都画在同一张卡片里（原来 ProgressDialog + 结果 AlertDialog = 两次换窗）
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.mods_import_progress_title),
                Trans.get(this, R.string.mods_import_progress_fmt, displayName, slot));
        new Thread(new Runnable() {
            @Override public void run() {
                Mods.PackResult pr = null;
                String err = null;
                File destDir = null;
                // 第 233 轮（P5）：导入成功后算一次插件体检（后台线程；插件没装 ⇒ null）
                java.util.Map<String, java.util.List<Finding>> health = null;
                try {
                    destDir = new File(Data.dirOf(ModsActivity.this, slot), "mods");
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException(Trans.get(ModsActivity.this, R.string.mods_import_open_failed));
                    try {
                        pr = Mods.importPackage(ModsActivity.this, destDir, displayName, in, overwrite,
                                Mods.trashDirOf(ModsActivity.this));
                    } finally {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable t) {
                    // ★ 2026-10-06（第 115 轮第五批）：弹窗正文翻白话（系统 errno 才翻），原文进日志
                    android.util.Log.w("MDTLauncher", "mods op failed", t);
                    err = Util.ioReason(ModsActivity.this, t);
                }
                if (pr != null && pr.ok && pr.meta != null) {
                    health = healthForImport(ModsActivity.this, slot);
                }
                final Mods.PackResult fpr = pr;
                final java.util.Map<String, java.util.List<Finding>> fhealth = health;
                final String fe = err;
                final File fdir = destDir;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (fe != null) {
                            stack.replace(Trans.get(ModsActivity.this, R.string.mods_import_failed), fe,
                                    closeBtn(stack));
                            return;
                        }
                        // ★ 下面两条出口弹的都是 inline 窗口（同名替换框 / 导入结果框）⇒
                        //   发起它的实例已经被转屏销毁时必须停在这里，否则 BadTokenException
                        //   直接闪退（`alert` 那条已由它自己挡，见 {@link Util#dead}）。
                        if (Util.dead(ModsActivity.this)) return;
                        if (!fpr.ok) {
                            // 「同名已存在」⇒ 给一次**显式**替换的机会（别让用户以为"点了没反应"）
                            File dest = fdir == null ? null : new File(fdir, displayName);
                            if (dest != null && dest.exists()) {
                                // 同名 ⇒ 同一张卡里换成一页确认（不再另开窗）
                                stack.replace(Trans.get(ModsActivity.this, R.string.mods_import_exists_title),
                                        Trans.get(ModsActivity.this, R.string.mods_import_exists_msg_fmt, displayName),
                                        new CardStack.Btn(Trans.get(ModsActivity.this, R.string.cancel),
                                                new Runnable() {
                                                    @Override public void run() { stack.dismiss(); }
                                                }),
                                        new CardStack.Btn(Trans.get(ModsActivity.this, R.string.mods_import_replace),
                                                new Runnable() {
                                                    @Override public void run() {
                                                        stack.dismiss();
                                                        doImport(slot, uri, displayName, true);
                                                    }
                                                }));
                                return;
                            }
                            stack.replace(Trans.get(ModsActivity.this, R.string.mods_import_failed),
                                    fpr.report(ModsActivity.this), closeBtn(stack));
                            return;
                        }
                        showImportResult(stack, fpr, fhealth);
                        rescan();
                    }
                });
            }
        }, "mod-import").start();
    }

    /**
     * 第 233 轮（P5）：导入成功后的**插件体检**（在导入线程里算，别占 UI 线程）。
     *
     * <p>返回 {@code null} = 插件没装 / 算失败 ⇒ 弹窗**整段不显示**（不是"没问题"——
     * "没查"和"查了没问题"必须能分辨）。只收**挂在这一项上**的结论：
     * 槽级结论（整槽跳过 / 孤儿开关）与"这个包怎么了"无关。
     */
    private static java.util.Map<String, java.util.List<Finding>> healthForImport(
            android.content.Context ctx, String slot) {
        try {
            if (!PluginAnalysis.has(ctx, Extensions.PAGE_MODS)) return null;
            return PluginAnalysis.anchoredByPath(ctx, slot);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "import health failed", t);
            return null;
        }
    }

    /** 导入结果 + **版本兼容性提示**（F13 的头号卖点，在"刚装进去"这一刻就该说） */
    private void showImportResult(final CardStack stack, Mods.PackResult pr,
                                  java.util.Map<String, java.util.List<Finding>> health) {
        StringBuilder sb = new StringBuilder(pr.report(this));
        Mods.Target t = Mods.targetsFor(this, mSlot);
        if (pr.meta != null) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_kind_fmt, kindOf(pr.meta))).append('\n');
            sb.append('\n');
            if (!t.any()) {
                sb.append(Trans.get(ModsActivity.this, R.string.mods_import_nogame)).append('\n');
            } else if (health == null) {
                // ★ 第 233 轮（P5）：插件在时**版本那条由插件说**（它给「要 160 以上的游戏版本」），
                //   别再让同一个结论在同一个弹窗里出现两种说法；插件不在才退回宿主这句。
                if (Mods.isAtLeast(t.build, t.revision, pr.meta.minGameVersion)) {
                    sb.append(Trans.get(ModsActivity.this, R.string.mods_import_compat_ok_fmt,
                            t.label, pr.meta.minGameVersion)).append('\n');
                } else {
                    sb.append(Trans.get(ModsActivity.this, R.string.mods_import_compat_bad_fmt,
                            t.label, pr.meta.minGameVersion)).append('\n');
                }
            }
        }
        if (health != null) {
            String path = pr.dest == null ? null : pr.dest.getAbsolutePath();
            // ★ 只有"扫描里真有这一项"才说体检 —— 扫不到（路径对不上）时说"没问题"等于骗人
            if (path != null && health.containsKey(path)) {
                sb.append('\n').append(Trans.get(ModsActivity.this,
                        R.string.mods_import_health_head)).append('\n');
                sb.append(ModsText.importHealth(this, health.get(path))).append('\n');
            }
        }
        stack.replace(Trans.get(this, R.string.mods_import_ok), sb.toString(), closeBtn(stack));
    }

    /** 结果页的「关闭」（唯一实现；`stack` 必须 final 才能被匿名类用）。 */
    private CardStack.Btn closeBtn(final CardStack stack) {
        return new CardStack.Btn(Trans.get(this, R.string.mods_close), new Runnable() {
            @Override public void run() { stack.dismiss(); }
        });
    }

    /** 挑复制目标槽（排除源槽本身） */
    private void pickCopyTarget() {
        final List<Data.Slot> targets = new ArrayList<>();
        for (Data.Slot s : Data.allSlots(this)) {
            if (!s.name.equals(mSlot)) targets.add(s);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.mods_copy_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            Data.Slot s = targets.get(i);
            names[i] = s.name + (s.active ? Trans.get(ModsActivity.this, R.string.slot_current_suffix) : "")
                    + "  " + Trans.get(ModsActivity.this, R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
        }
        // ★ 第 263 轮：选槽 → 确认框原来是两个弹窗（换窗会闪），现在同一张卡里推页
        final CardStack stack = new CardStack(this);
        stack.root(Trans.get(this, R.string.mods_copy_pick_title),
                CardStack.list(this, names, new CardStack.ItemClick() {
                    @Override public void onClick(int w) {
                        confirmCopy(stack, targets.get(w).name);
                    }
                }),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
        stack.show();
    }

    /**
     * 复制前的确认：把"复制多少项、其中多少会被跳过、复制的是什么"摊开。
     *
     * ★ 这里**没有**"覆盖"选项（`overwrite=false` 写死）：跨槽复制是**批量**动作，
     *   覆盖意味着一次可能替换掉目标槽里十几个模组 —— 那是"批量破坏"，不该做成一个顺手勾的框。
     *   要替换单个模组走「导入模组包」那条路（它会逐个确认）。**故意的不对称。**
     */
    private void confirmCopy(final CardStack stack, final String toSlot) {
        final File from = new File(Data.dirOf(this, mSlot), "mods");
        final File to = new File(Data.dirOf(this, toSlot), "mods");
        int count = 0;
        long bytes = 0;
        int clash = 0;
        File[] kids = from.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (Data.contentSkipped(f)) continue;
                count++;
                bytes += f.isDirectory() ? Data.sizeTree(f) : f.length();
                if (new File(to, f.getName()).exists()) clash++;
            }
        }
        stack.pushMessage(Trans.get(ModsActivity.this, R.string.mods_copy_confirm_title_fmt, toSlot),
                Trans.get(ModsActivity.this, R.string.mods_copy_confirm_msg_fmt,
                        mSlot, toSlot, count, Util.formatSize(bytes), clash),
                Trans.get(ModsActivity.this, R.string.mods_copy_title_ok), new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        doCopy(from, to, toSlot);
                    }
                });
    }

    private void doCopy(final File from, final File to, final String toSlot) {
        // 门禁：**只在动了"当前槽"时才拦** —— 游戏只读当前槽的 mods/，
        // 两个非当前槽之间互拷与它无关（别用一条过宽的规矩挡掉无害的操作）。
        final String cur = Data.currentSlot(this);
        if (Data.gameAlive(this) && (mSlot.equals(cur) || toSlot.equals(cur))) {
            // ★ 2026-10-15：这里原来复用 `mods_copy_done`（「复制完成」）当标题 ——
            //   正文却是"游戏正在运行，请先退出游戏"（同一屏自打脸）。拦下就说拦下。
            alert(Trans.get(ModsActivity.this, R.string.game_busy_title),
                    Trans.get(ModsActivity.this, R.string.mods_copy_blocked));
            return;
        }
        int count = 0;
        File[] kids = from.listFiles();
        if (kids != null) {
            for (File f : kids) if (!Data.contentSkipped(f)) count++;
        }
        // ★ 第 270 轮：复制进度 → 结果 同一张卡
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.mods_copy_progress_title),
                Trans.get(this, R.string.mods_copy_progress_fmt, count, toSlot));
        new Thread(new Runnable() {
            @Override public void run() {
                Mods.PackResult pr = null;
                String err = null;
                try {
                    pr = Mods.copyMods(ModsActivity.this, from, to, false, Mods.trashDirOf(ModsActivity.this));
                } catch (Throwable t) {
                    // ★ 2026-10-06（第 115 轮第五批）：弹窗正文翻白话（系统 errno 才翻），原文进日志
                    android.util.Log.w("MDTLauncher", "mods op failed", t);
                    err = Util.ioReason(ModsActivity.this, t);
                }
                final Mods.PackResult fpr = pr;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // ★ 标题按结局给：这里是**结果**（可能成功、也可能整批失败）
                        stack.replace(Trans.get(ModsActivity.this, R.string.mods_copy_result_title),
                                fe != null ? fe : fpr.report(ModsActivity.this), closeBtn(stack));
                        rescan();
                    }
                });
            }
        }, "mod-copy").start();
    }

    /** 弹窗三件套里的 alert（与 SlotsActivity / MainActivity 同名同形，别各写一份）。
     *  ★ 2026-10-04 起在这里挡"已销毁的 Activity"：导入模组是几十秒的后台任务，
     *    失败弹窗落在转屏销毁的实例上会 BadTokenException 闪退（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
        if (CardStack.leaf(title, msg)) return;
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.mods_close, null)
                .show();
    }

    /** 槽切换（只读视图；不改任何分配） */
    private void pickSlot() {
        final List<Data.Slot> slots = Data.allSlots(this);
        if (slots.isEmpty()) return;
        final String[] names = new String[slots.size()];
        int checked = 0;
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            names[i] = s.name + (s.active ? Trans.get(ModsActivity.this, R.string.slot_current_suffix) : "")
                    + "  " + Trans.get(ModsActivity.this, R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
            if (s.name.equals(mSlot)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_slot_pick_title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        mSlot = slots.get(w).name;
                        rescan();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── 扫描与刷新 ────────────────────────────────────────────────────────

    // ══ 7.3f 兼容矩阵（2026-10-25）════════════════════════════════════════════

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.mods, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.menu_mods_compat) {
            showCompatMatrix();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 「这个模组换成 160.4 还行不行」—— 对所有**已装版本**算一遍。
     *
     * ★ 判据只读声明（`minGameVersion` + 版本 build 号），不改任何文件；
     *   每格只能写「按声明」⇒ 弹窗最后一行带脚注。
     */
    private void showCompatMatrix() {
        final Mods.Scan scan = mScan;
        if (scan == null) {
            alert(Trans.get(this, R.string.mods_compat_title), Trans.get(this, R.string.mods_compat_empty));
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> items = compatRows(scan);
                runOnUiThread(new Runnable() {
                    @Override public void run() { showCompatDialog(items); }
                });
            }
        }, "mods-compat").start();
    }

    /** 后台线程：每个模组一行（+ 最后一行脚注）；没有可比的东西 ⇒ 空表 */
    private List<String> compatRows(Mods.Scan scan) {
        List<String> items = new ArrayList<String>();
        List<String> versions = compatVersions();
        if (versions.isEmpty() || scan.mods.isEmpty()) return items;
        for (Mods.CompatRow row : Mods.compatMatrix(scan.mods, versions)) {
            items.add(ModsText.compatRow(this, row));
        }
        items.add(Trans.get(this, R.string.mods_compat_note));
        return items;
    }

    /** 已装版本的**格式版本**串（去重、按 build 升序；`?` 跳过） */
    private List<String> compatVersions() {
        List<String> out = new ArrayList<String>();
        for (Versions.Entry e : Versions.scanAll(this)) {
            String v = e == null ? null : e.formatVersion();
            if (v == null || v.isEmpty() || "?".equals(v) || out.contains(v)) continue;
            out.add(v);
        }
        Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int[] x = Mods.parseBuild(a), y = Mods.parseBuild(b);
                if (x[0] != y[0]) return x[0] < y[0] ? -1 : 1;
                return x[1] < y[1] ? -1 : (x[1] == y[1] ? 0 : 1);
            }
        });
        return out;
    }

    private void showCompatDialog(List<String> items) {
        if (items.isEmpty()) {
            alert(Trans.get(this, R.string.mods_compat_title), Trans.get(this, R.string.mods_compat_empty));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_compat_title)
                .setItems(items.toArray(new String[0]), null)
                .setNegativeButton(R.string.mods_close, null)
                .show();
    }

    private void rescan() {
        mScan = Mods.scan(this, mSlot);
        mResolved = Mods.resolveDependencies(mScan.mods);
        mTarget = Mods.targetsFor(this, mSlot);
        // 🔴 v26（第 229 轮）：插件给的判定 / 结论是**上一次分析**的快照 ——
        //   启停之后不清掉，行上就还是旧状态（用户报的就是这个：
        //   「点全部启用后必须退出页面重进才有变化」）。
        //   先清掉 ⇒ 立刻回落到宿主自己的判定（它是刚扫出来的），
        //   再重挂一次插件卡（重跑分析，回来时再刷一次）。
        mVerdicts.clear();
        mIssues.clear();
        mSlotNotes.clear();
        rebuildList();
        fillHeader();
        refreshCrashVerdict();
        PluginEntries.attachPage(this, Extensions.PAGE_MODS, mSlot, null);
    }

    /**
     * 这个模组是不是**归因器点名**的那一个（`mBlamed` 里存的内部名，小写）。
     * ⚠️ 判据是"归因器点了名"，**不是**"游戏把它标成 failed" —— 后者是全槽级的（见 mBlamed 的注释）。
     */
    private boolean isBlamed(Mods.Info m) {
        if (m == null || mBlamed.isEmpty()) return false;
        String k = m.internalName == null ? "" : m.internalName.toLowerCase(java.util.Locale.ROOT);
        return !k.isEmpty() && mBlamed.contains(k);
    }

    /**
     * F23：把**上次崩溃很可能是谁干的**显示在摘要卡里（一行）。
     *
     * ★ 为什么模组页也要这一行：这一页是"为什么它没生效"的入口，而"上次崩了、可能是谁"是同一类
     *   问题的另一半答案 —— 只在日志页可见等于要用户先想到去那里翻。
     * ★ 为什么只在**点名成功**时显示：`认不出`/原版崩/启动器自己的报告都**不占位、不说废话**
     *   （与日志页那行的口径一致，只是这里更克制）。
     * ⚠️ **必须在后台线程**：认不出时 `judgeFull` 要扫每个模组的 dex（真实槽里几十 MB）。
     *   回来时两道校验（还停在这个槽 **且** 页面没销毁）—— 与 F7 的"过期结果丢弃"同源。
     */
    private void refreshCrashVerdict() {
        if (mCrashLine == null) return;
        mCrashLine.setVisibility(View.GONE);
        mCrashLine.setText("");
        final String slot = mSlot;
        new Thread(new Runnable() {
            @Override public void run() {
                CrashAnalysis.Verdict v = null;
                try {
                    v = CrashAnalysis.analyzeNewest(ModsActivity.this, slot);
                } catch (Throwable ignored) {
                    v = null;
                }
                // ★ 摘要卡这一行要**短**（2026-10-08 用户：「全部加载失败和跳过所以模组感觉好乱」）：
                //   分得出是哪一个 ⇒ 只写名字一行；并列（报告指向多个）才退回整句（那种情况本来就
                //   说不清，整句反而是诚实的最小表述）。**长依据一律留给运行日志/导出**。
                java.util.List<String> names = CrashAnalysis.blamedNames(v);
                final String line;
                if (names.size() == 1) {
                    // ★ 2026-10-08 用户点单（「可以加个快捷停用」）：单一嫌疑人时这行
                    //   **直接可点** = 忽略它并再试（走同一个确认框与 {@link Mods#ignoreAndRetry}，
                    //   不另造一套）—— 崩溃场景下不用先去列表里找那个模组。
                    line = Trans.get(ModsActivity.this, R.string.mods_blame_one_tap_fmt, names.get(0));
                } else if (v != null && v.kind == CrashAnalysis.KIND_MOD) {
                    line = Trans.get(ModsActivity.this, R.string.mods_verdict_fmt,
                            CrashAnalysis.text(ModsActivity.this, v));
                } else {
                    line = "";
                }
                // ★ 按行标记的那份名单：**只**来自归因器（见 isBlamed）
                final java.util.Set<String> blamed = new java.util.HashSet<>();
                for (String s : CrashAnalysis.blamedInternals(v)) {
                    blamed.add(s.toLowerCase(java.util.Locale.ROOT));
                }
                // ★ 那次崩溃的**后果还在不在**：有模组带着它留下的记录（= 还被关着）
                final boolean anyFailedNow;
                {
                    boolean any = false;
                    for (Mods.Info mm : mScan.mods) {
                        if (mm != null && mm.failed) {
                            any = true;
                            break;
                        }
                    }
                    anyFailedNow = any;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(ModsActivity.this) || !slot.equals(mSlot)) return;
                        // ★★ 2026-10-08 用户：「**已经不崩溃了为什么还显示最可能的原因**」——
                        //    这句只有在**那次崩溃还"活着"**时才该出现：
                        //      · 下次启动还会因它整槽跳过（`launchid.dat` 在 + 跳过开关开），或
                        //      · 它的后果还在（有模组带着那次留下的记录 / 还关着）
                        //    游戏这次跑完了（`launchid.dat` 被 `finishLaunch` 删掉）且模组都开着
                        //    ⇒ 那就是**历史**了，卡片上不该再挂着"最可能的原因"（要考古去运行日志）。
                        boolean live = ModsText.crashStillLive(mScan, anyFailedNow);
                        if (line.isEmpty() || !live) {
                            mCrashLine.setVisibility(View.GONE);
                            mCrashLine.setOnClickListener(null);
                            mCrashLine.setClickable(false);
                        } else {
                            mCrashLine.setText(line);
                            mCrashLine.setVisibility(View.VISIBLE);
                            // 只有“单一嫌疑人”时才可点（并列时点哪一个都不对）
                            final Mods.Info suspect = names.size() == 1
                                    ? findByName(mScan.mods, names.get(0)) : null;
                            if (suspect != null) {
                                mCrashLine.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        confirmToggle(suspect);
                                    }
                                });
                                mCrashLine.setClickable(true);
                            } else {
                                mCrashLine.setOnClickListener(null);
                                mCrashLine.setClickable(false);
                            }
                        }
                        // 名单变了就把列表重画一次（多标/少标一个「很可能与它有关」）
                        if (!blamed.equals(mBlamed)) {
                            mBlamed = blamed;
                            rebuildList();
                        }
                    }
                });
            }
        }, "mods-crash-verdict").start();
    }

    private void fillHeader() {
        if (mSummary != null) {
            // 第 164 轮：这行与另外三页同一模板「本槽 N <量词> · 附加…」——
            //   原来带「槽 X」，而 ActionBar 标题本来就是「槽「X」的模组」⇒ 同屏说了两遍。
            if (!mTarget.any()) {
                mSummary.setText(Trans.get(ModsActivity.this, R.string.mods_summary_nogame_fmt,
                        mScan.mods.size()));
            } else {
                mSummary.setText(Trans.get(ModsActivity.this, R.string.mods_summary_fmt,
                        mScan.mods.size(), join(mTarget.versions)));
            }
        }
        if (mSlotSub != null) {
            mSlotSub.setText(Trans.get(ModsActivity.this, R.string.mods_row_slot_sub_fmt, mSlot));
        }
        // ★ 「上次启动没跑完 ⇒ 游戏把全部模组都标成失败」是**全槽级**的事实，只在这里说一次
        //   （理由见 `mods_warn_failed` / isBlamed 的注释：逐行显示会冤枉无关模组）
        if (mFailNote != null) {
            boolean anyFailed = false;
            for (Mods.Info m : mScan.mods) {
                if (m != null && m.failed) {
                    anyFailed = true;
                    break;
                }
            }
            // ★★ 2026-10-08 修（用户真机：「明明游戏禁用了所有模组但依旧显示会加载」/
            //    「就是目前这个状态，但下次启动是不加载的」）：
            //    **判据搬进 `ModsText.cardNote`**（纯函数、自检逐格钉住），而且
            //    **"下次启动会整槽跳过"不再挂在 `anyFailed` 上** —— 那是两件独立的事：
            //    用户在游戏里点过恢复（`-failed` 被清掉）之后 `launchid.dat` 还在，
            //    下次启动游戏照样会把整槽模组关掉；原来那种写法这时**一句警告都不显示**。
            // v27：**插件说了就用插件的**（结论归插件，位置在摘要卡）；
            //   没说才回落到宿主自己的判据（插件没装 / 旧版 / 隐私模式下被关）。
            if (!mSlotNotes.isEmpty()) {
                StringBuilder nb = new StringBuilder();
                for (String line : mSlotNotes) {
                    if (nb.length() > 0) nb.append("\n\n");
                    nb.append(line);
                }
                mFailNote.setText(nb.toString());
                mFailNote.setVisibility(View.VISIBLE);
            } else {
                int noteRes = ModsText.cardNote(mScan, anyFailed);
                if (noteRes != 0) {
                    mFailNote.setText(Trans.get(ModsActivity.this, noteRes));
                    mFailNote.setVisibility(View.VISIBLE);
                } else {
                    mFailNote.setVisibility(View.GONE);
                }
            }
        }
        if (mCopySub != null) {
            mCopySub.setText(Trans.get(ModsActivity.this, R.string.mods_act_copy_sub_fmt, mSlot));
        }
        if (mDetail != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_fmt,
                    mScan.modsDirPath(),
                    mScan.settingsNote,
                    getString(mScan.launchIdExists ? R.string.mods_crash_yes : R.string.mods_crash_no)));
            if (!mTarget.any()) {
                sb.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_detail_no_target));
            } else {
                sb.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_detail_target_fmt,
                        join(mTarget.versions), mTarget.label));

            }
            if (!mScan.ignored.isEmpty()) {
                sb.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_ignored_fmt, mScan.ignored.size()));
                sb.append("：").append(join(mScan.ignored));
            }
            // v27：孤儿开关的文案搬进 mods-health 插件了（槽级结论，跟其他插件结论一起说）
            if (!mScan.problems.isEmpty()) {
                sb.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_problem_head));
                for (String p : mScan.problems) sb.append("\n· ").append(p);
            }
            // 依赖链的失效项单独说（这是"为什么它没生效"的第二种静默原因）
            List<String> bad = new ArrayList<>();
            for (java.util.Map.Entry<String, Mods.State> en : mResolved.states.entrySet()) {
                if (en.getValue() != Mods.State.ENABLED) {
                    bad.add(en.getKey() + " → " + ModsText.stateLabel(this, en.getValue()));
                }
            }
            if (!bad.isEmpty()) {
                // ★ 原文案里有「（照抄 Mods.resolveDependencies）」—— 那是
                //   **维护者自我说明 / 源码出处**，按文案纪律 ⑤ 不该出现在界面上 ⇒ 去掉。
                sb.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_detail_depchain));
                for (String b : bad) sb.append("\n· ").append(b);
            }
            mDetail.setText(sb.toString());
        }
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append("、");
            sb.append(x);
        }
        return sb.toString();
    }

    /**
     * 重建列表 = 合并（能读出的 + 读不出元数据的）→ 搜索/筛选/排序 → 挂行。
     *
     * ★ 为什么坏包也要并进同一份列表：它们同样要被搜到、被「只看有问题的」筛出来 ——
     *   单独排到最后会让「筛完了看不到坏包」，而那恰恰是最该看到的。
     * ★ 判据（谁算有问题的、怎么排）全在 {@link Mods#filterAndSort} 里，本方法只负责显示。
     */
    private void rebuildList() {
        if (mContainer == null) return;
        mContainer.removeAllViews();
        List<Mods.Info> all = new ArrayList<>(mScan.mods.size() + mScan.broken.size());
        List<Mods.State> sts = new ArrayList<>(all.size() + 1);
        for (Mods.Info m : mScan.mods) {
            all.add(m);
            sts.add(stOf(m));
        }
        for (Mods.Info m : mScan.broken) {
            all.add(m);
            sts.add(null);                                // 读不出元数据 ⇒ 没有「游戏里的状态」
        }
        List<Mods.Info> shown = Mods.filterAndSort(all, mQuery, mSort, mOnlyProblems, mType,
                sts.toArray(new Mods.State[0]));
        // v25：插件注入的筛选 / 排序 —— 结果在「筛选与排序」里选中那一刻就算好了（缓存在字段里），
        // 这里只做纯内存的过滤与排名，别把插件调用塞进每次 rebuild（打字搜索会卡）。
        if (mPluginFilterPaths != null) {
            List<Mods.Info> keep = new ArrayList<>();
            for (Mods.Info m : shown) {
                if (mPluginFilterPaths.contains(pathOf(m))) keep.add(m);
            }
            shown = keep;
        }
        if (mPluginSortPaths != null && !mPluginSortPaths.isEmpty()) {
            final Map<String, Integer> rank = new HashMap<>();
            for (int i = 0; i < mPluginSortPaths.size(); i++) {
                rank.put(mPluginSortPaths.get(i), Integer.valueOf(i));
            }
            final int missing = mPluginSortPaths.size();
            Collections.sort(shown, new Comparator<Mods.Info>() {
                @Override public int compare(Mods.Info a, Mods.Info b) {
                    Integer ra = rank.get(pathOf(a));
                    Integer rb = rank.get(pathOf(b));
                    int va = ra == null ? missing : ra.intValue();
                    int vb = rb == null ? missing : rb.intValue();
                    return va < vb ? -1 : (va > vb ? 1 : 0);
                }
            });
        }
        mShown = shown;
        boolean filtering = mOnlyProblems || mType != Mods.TYPE_ANY || !mQuery.trim().isEmpty()
                || mSort != Mods.SORT_NAME || mPluginFilterPaths != null;
        if (mFiltered != null) {
            mFiltered.setVisibility(filtering ? View.VISIBLE : View.GONE);
            mFiltered.setText(Trans.get(ModsActivity.this, R.string.mods_filtered_fmt, shown.size(), all.size()));
        }
        if (mEmpty != null) {
            mEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            if (shown.isEmpty()) {
                // 第 166 轮：把这句里的**绝对路径**挪走了 —— 它会把空态撑成 4 行，
                //   四页底部的块高就不一样了（路径仍在上面那行展开后的「模组目录」里）。
                mEmpty.setText(!filtering && all.isEmpty()
                        ? Trans.get(ModsActivity.this, R.string.mods_empty_fmt, mSlot)
                        : Trans.get(ModsActivity.this, R.string.mods_empty_filtered));
            }
        }
        LayoutInflater inf = getLayoutInflater();
        for (Mods.Info m : shown) {
            View v = inf.inflate(R.layout.item_mod, mContainer, false);
            // ★ 「坏包」的判据就是 metaError（Mods.Scan 就是这么分 mods / broken 的）
            bindRow(v, m, m.metaError != null);
            mContainer.addView(v);
        }
        // v24（第 227 轮）：分析器统一由 PluginEntries.attachPage 挂进「插件」卡，本页不再单独画卡
    }

    /**
     * v25（第 229 轮）：分析器回来了 —— 收下"挂到项上"的结论，再重画一次列表。
     *
     * <p>分析器在**列表渲染之后**才回来（后台线程，见 {@code PluginEntries.attachPage}），
     * 所以要重画；只重画列表，不重扫、不动设置。
     */
    /** v27：插件要放在**摘要卡**里的槽级结论到了 —— 收下并重画摘要卡。 */
    /** v27：六道门的插件结论到了 —— 收下（下次开详情用）。 */
    @Override public void onPluginGates(java.util.Map<String, java.util.List<io.mdt.launcher.api.Gate>> gates) {
        mGates.clear();
        if (gates != null) mGates.putAll(gates);
    }

    @Override public void onPluginSummary(java.util.List<Finding> fs) {
        mSlotNotes.clear();
        if (fs != null) {
            for (Finding f : fs) {
                if (f == null) continue;
                StringBuilder sb = new StringBuilder(f.title);
                if (f.detail != null && !f.detail.isEmpty()) sb.append("\n").append(f.detail);
                mSlotNotes.add(sb.toString());
            }
        }
        if (mFailNote != null) fillHeader();
    }

    /** v26：插件判定回来了 —— 收下并重画（徽标 / 筛选 / 详情都读它）。 */
    @Override public void onPluginStates(Map<String, String> verdicts) {
        mVerdicts.clear();
        if (verdicts != null) mVerdicts.putAll(verdicts);
        android.util.Log.i("MDTLauncher", "plugin verdicts: " + mVerdicts.size());
        if (mContainer != null) rebuildList();
    }

    /**
     * 这一行的"状态"：插件判定优先，没有才用宿主自己算的。
     *
     * <p>token 是**固定词表**（见 `docs/PLUGIN-API.md`），宿主只做映射、不解释语义：
     * 这样"插件说不支持"和"徽标写不支持"永远指向同一个判定。
     */
    private Mods.State stOf(Mods.Info m) {
        String tok = mVerdicts.get(pathOf(m));
        Mods.State fromPlugin = stateOfToken(tok);
        if (fromPlugin != null) return fromPlugin;
        return Mods.stateOf(m, mResolved, mTarget.build, mTarget.revision);
    }

    /** 把插件给的 token 映射回 {@link Mods.State}；不认识 / 为空 ⇒ null（回落宿主）。 */
    private static Mods.State stateOfToken(String tok) {
        if (tok == null) return null;
        if ("enabled".equals(tok)) return Mods.State.ENABLED;
        if ("disabled".equals(tok)) return Mods.State.DISABLED;
        if ("unsupported".equals(tok)) return Mods.State.UNSUPPORTED;
        if ("missing-dep".equals(tok)) return Mods.State.MISSING_DEPENDENCIES;
        if ("incomplete-dep".equals(tok)) return Mods.State.INCOMPLETE_DEPENDENCIES;
        if ("circular-dep".equals(tok)) return Mods.State.CIRCULAR_DEPENDENCIES;
        if ("content-error".equals(tok)) return Mods.State.CONTENT_ERRORS;
        return null;
    }

    @Override public void onPluginFindings(List<Finding> fs) {
        mIssues.clear();
        if (fs != null) {
            for (Finding f : fs) {
                if (f == null || f.anchorPath == null) continue;
                List<Finding> one = mIssues.get(f.anchorPath);
                if (one == null) {
                    one = new ArrayList<Finding>();
                    mIssues.put(f.anchorPath, one);
                }
                one.add(f);
            }
        }
        if (mContainer != null) rebuildList();
    }

    /**
     * 「筛选与排序」对话框。
     * ★ 不为它新建 layout：排序（单选）+ 只看有问题的（开关）+ 类型（四选一）+ 清空，
     *   用一个列表表达（当前项打勾）—— 少一个 layout 文件就少一处跟主题有关的坑（见 FLOWS F2b）。
     * ★ 一档⑤（2026-10-06）：加了"类型"四行。判据是 `Mods.matchesType`（"包含"语义）——
     *   混合模组会在两类里都出现，那是事实；这一行**只是纯前端过滤**，一个文件都不碰。
     */
    private void pickFilter() {
        final String tick = "✓ ";
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        addChoice(labels, acts, (mSort == Mods.SORT_NAME ? tick : "")
                + Trans.get(ModsActivity.this, R.string.mods_filter_sort_name), new Runnable() {
            @Override public void run() { mSort = Mods.SORT_NAME; rebuildList(); }
        });
        addChoice(labels, acts, (mSort == Mods.SORT_STATE ? tick : "")
                + Trans.get(ModsActivity.this, R.string.mods_filter_sort_state), new Runnable() {
            @Override public void run() { mSort = Mods.SORT_STATE; rebuildList(); }
        });
        addChoice(labels, acts, (mSort == Mods.SORT_SIZE ? tick : "")
                + Trans.get(ModsActivity.this, R.string.mods_filter_sort_size), new Runnable() {
            @Override public void run() { mSort = Mods.SORT_SIZE; rebuildList(); }
        });
        addChoice(labels, acts, (mOnlyProblems ? tick : "")
                + Trans.get(ModsActivity.this, R.string.mods_filter_only), new Runnable() {
            @Override public void run() { mOnlyProblems = !mOnlyProblems; rebuildList(); }
        });
        addTypeChoice(labels, acts, Mods.TYPE_ANY, R.string.mods_filter_type_all);
        addTypeChoice(labels, acts, Mods.TYPE_JAVA, R.string.mods_filter_type_java);
        addTypeChoice(labels, acts, Mods.TYPE_JS, R.string.mods_filter_type_js);
        addTypeChoice(labels, acts, Mods.TYPE_DATA, R.string.mods_filter_type_data);

        // v25：插件注入的排序 / 筛选（**选中才算**：算的时候要加载插件，走后台线程）
        for (final PluginViews.View v : PluginViews.of(this, Extensions.PAGE_MODS, Extensions.SLOT_SORT)) {
            addChoice(labels, acts, (v.key().equals(mPluginSortKey) ? tick : "") + v.title(),
                    new Runnable() {
                        @Override public void run() { pickPluginSort(v); }
                    });
        }
        for (final PluginViews.View v : PluginViews.of(this, Extensions.PAGE_MODS, Extensions.SLOT_FILTER)) {
            addChoice(labels, acts, (v.key().equals(mPluginFilterKey) ? tick : "") + v.title(),
                    new Runnable() {
                        @Override public void run() { pickPluginFilter(v); }
                    });
        }

        addChoice(labels, acts, Trans.get(ModsActivity.this, R.string.mods_filter_reset),
                new Runnable() {
                    @Override public void run() {
                        mSort = Mods.SORT_NAME;
                        mOnlyProblems = false;
                        mType = Mods.TYPE_ANY;
                        mQuery = "";
                        mPluginFilterPaths = null;
                        mPluginFilterKey = null;
                        mPluginSortPaths = null;
                        mPluginSortKey = null;
                        if (mSearch != null) mSearch.setText("");
                        rebuildList();
                    }
                });

        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_filter_title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Runnable r = acts.get(which);
                        if (r != null) r.run();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** v25：给「筛选与排序」列表加一行（label + 点下去做什么）。 */
    private static void addChoice(List<String> labels, List<Runnable> acts, String label, Runnable act) {
        labels.add(label);
        acts.add(act);
    }

    private void addTypeChoice(List<String> labels, List<Runnable> acts,
                               final int type, int textRes) {
        String tick = "✓ ";
        addChoice(labels, acts, (mType == type ? tick : "")
                + Trans.get(ModsActivity.this, textRes), new Runnable() {
            @Override public void run() { mType = type; rebuildList(); }
        });
    }

    /** v25：选中一个插件排序 ⇒ 后台算一遍顺序，缓存下来再重画。 */
    private void pickPluginSort(final PluginViews.View v) {
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.mods_filter_plugin_wait), "");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> paths = new ArrayList<>();
                try {
                    List<io.mdt.launcher.api.ItemInfo> items = PluginData.pageItems(
                            ModsActivity.this, Extensions.PAGE_MODS, mSlot, (File) null);
                    for (io.mdt.launcher.api.ItemInfo it : PluginViews.sorted(ModsActivity.this, v, items, mSlot)) {
                        if (it.path != null) paths.add(it.path);
                    }
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        mPluginSortPaths = paths.isEmpty() ? null : paths;
                        mPluginSortKey = paths.isEmpty() ? null : v.key();
                        rebuildList();
                    }
                });
            }
        }, "plugin-sort").start();
    }

    /** v25：选中一个插件筛选 ⇒ 后台问一遍"这一项算不算"，缓存通过的 path 集合。 */
    private void pickPluginFilter(final PluginViews.View v) {
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.mods_filter_plugin_wait), "");
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.Set<String> ok = null;
                try {
                    List<io.mdt.launcher.api.ItemInfo> items = PluginData.pageItems(
                            ModsActivity.this, Extensions.PAGE_MODS, mSlot, (File) null);
                    ok = PluginViews.accepted(ModsActivity.this, v, items, mSlot);
                } catch (Throwable ignored) {
                }
                final java.util.Set<String> fok = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        mPluginFilterPaths = fok;
                        mPluginFilterKey = fok == null ? null : v.key();
                        rebuildList();
                    }
                });
            }
        }, "plugin-filter").start();
    }

    private void bindRow(View v, final Mods.Info m, boolean broken) {
        TextView title = (TextView) v.findViewById(R.id.mod_title);
        TextView sub = (TextView) v.findViewById(R.id.mod_sub);
        TextView badge = (TextView) v.findViewById(R.id.mod_badge);
        TextView warn = (TextView) v.findViewById(R.id.mod_warn);

        Mods.State st = broken ? Mods.State.UNSUPPORTED : stOf(m);
        // ★ 徽标优先级：游戏自己的状态 > "安卓上会加载失败"（我们推出来的）> 启用。
        //   ★★ 2026-10-08：判据整体搬进 `ModsText.badge`（**可被自检钉住**）—— 两条要点：
        //     ① 没读到设置文件时**不许说「启用」**（那只是默认值，用户分辨不出"真开着"与"我们不知道"）；
        //     ② `willFailJavaLoad` 优先（声明了 java 却没 classes.dex ⇒ 那时说「启用」是骗人）。
        String badgeText = broken ? Trans.get(ModsActivity.this, R.string.mods_badge_broken)
                    : ModsText.badge(this, st, m.settingsKnown, m.willFailJavaLoad(), mScan.skipModLoading);
        boolean warnBadge = broken || ModsText.badgeWarns(st, m.settingsKnown, m.willFailJavaLoad());

        title.setText(broken ? m.fileName : m.titleWithVersion());
        // 徽标用**短**形式（`ModsText.stateBadge`），句子形式（`ModsText.stateLabel`）留给详情弹窗与报告
        // —— 真机实测：`与当前游戏版本不兼容` 会把标题挤到折行。
        badge.setText(badgeText);
        if (!broken && m.duplicated) {
            badge.setText(Trans.get(ModsActivity.this, R.string.mods_dup_fmt, badgeText));
        }
        badge.setBackgroundResource(warnBadge ? R.drawable.badge_slot_warn : R.drawable.badge_slot);

        // ★ 副标题只放「类型 · 大小 · 形式」三样（用户 2026-10-03：「还有点乱」）：
        //   ① 文件名**不再重复** —— 它就在上面一行的标题里；
        //   ② 「联机要服务器同款」几乎每个模组都一样，12 行重复同一句就是纯噪声
        //      ⇒ 挪进详情弹窗（那里才需要判断）。
        sub.setText(kindShortOf(m) + "  ·  "
                + Trans.get(ModsActivity.this, R.string.mods_sub_fmt, Util.formatSize(m.bytes), formOf(m)));

        List<String> warns = new ArrayList<>();
        if (broken || m.metaError != null) {
            // ★ 走 metaReason()（异常类名/全限定名要翻成白话），不是原始 metaError（那是给排查看的）
            warns.add(Trans.get(ModsActivity.this, R.string.mods_warn_meta_fmt, ModsText.infoMetaReason(this, m)));
        } else {
            // v25（P3b）：版本判断（包要求的版本 / 安卓门槛）已整块搬进 mods-health 插件 ——
            //   比较在那边做，宿主这里**不再自己比一遍**（两处判据必漂）。
            // v25（P3）：黑名单 / 重名 / 依赖这几句的**文案**搬到 mods-health 插件了
            //   （它挂在这一行上）；宿主只留徽标那个**结果**（「不支持」/「①重名」/「缺依赖」）。
            // ★ 2026-10-08 改：这里原来写的是 `if (m.failed) warns.add(mods_warn_failed)`
            //   —— 但 `failed` 是**游戏自己**在 settings 里写的 `mod-<名字>-failed`，模组加载期
            //   一崩，游戏会把**本槽全部**模组都标上（它下次"整槽跳过"的机制）⇒ 逐行显示会被读成
            //   "这个模组出过错"（用户真机反馈：「为什么无关模组也被判定到了」）。
            //   现在：全槽级那句话只在摘要卡里说一次（`mFailNote`），按行**只标归因器点名的那一个**。
            if (m.failed && isBlamed(m)) {
                warns.add(Trans.get(ModsActivity.this, R.string.mods_warn_blamed));
            }
            // v25（P2）：「会不会加载 / 脚本跑不跑」这两句的**文案**搬到 mods-health 插件了
            //   （它挂在这一行上，见 `mIssues`）；宿主这边只留徽标那个**结果** ——
            //   徽标报结果、插件给原因，别在一行里把同一件事说两遍。
            if (m.backslashEntries > 0) {
                warns.add(Trans.get(ModsActivity.this, R.string.mods_warn_backslash_fmt, m.backslashEntries));
            }
            // 🔴 2026-10-08（用户真机：「还有现在还是乱的」）：这里原来加一句
            //   `mods_warn_disabled` =「已关闭（游戏里的开关是 false）」—— 而**徽标已经写着「已关闭」**
            //   ⇒ 同一行里同一个词出现两次。**删掉**：徽标说状态就够了。
            //   ⚠️ 而且"是谁关的"这事我们**分不出**（整槽跳过时是游戏关的，也可能是用户手动关的），
            //   真要归因的位置是**摘要卡**（槽级、说一次）与详情页那道门（有 `-failed` 这个真证据），
            //   搁在按行的副标题里就是 §85.16 那类错。
            //   `mods_warn_disabled` 键**保留不再引用**（v0.3 已发出去过 ⇒ 用户语言包可能装着它）。
            if (st == Mods.State.UNSUPPORTED) {
                // ⚠️ 不写「状态：与当前游戏版本不兼容」—— 那个状态**只有三个成因**
                //    （版本不够 / minMajor 太低 / 黑名单），上面三行各自都说过一次了。
                //    徽标已经写着状态，正文再复述一遍就是纯噪声（同一件事两处喊）。
            } else if (st == Mods.State.DISABLED) {
                // ★ 2026-10-08：徽标报**结果**（「不会加载」），这里给**原因** —— 而且用**游戏里的说法**
                //   （用户：「游戏里面显示的是加载失败而不是禁用啊」）：
                //   带游戏的失败记录 ⇒ 「游戏里显示「加载失败」」；用户手动关的 ⇒ 「它现在是关着的」。
                warns.add(Trans.get(ModsActivity.this, m.failed
                        ? R.string.mods_warn_failed_mark : R.string.mods_warn_disabled));
            } else if (st == Mods.State.CONTENT_ERRORS) {
                // 依赖 / 版本那几类已归插件（v25 P2/P3）；只剩"内容有错"这类**宿主扫描出来**的
                // 文件级问题，仍然由宿主补一句"为什么"。
                warns.add(Trans.get(ModsActivity.this, R.string.mods_warn_state_fmt, ModsText.stateLabel(this, st)));
            }
        }
        // v25：分析器挂在这一行上的结论（短标签；`detail` 在详情弹窗里看）
        List<Finding> issues = mIssues.get(pathOf(m));
        if (issues != null) {
            for (Finding f : issues) {
                if (f.title != null && !f.title.isEmpty()) {
                    warns.add(Trans.get(ModsActivity.this, R.string.mods_plugin_warn_fmt, f.title));
                }
            }
        }
        if (warns.isEmpty()) {
            warn.setVisibility(View.GONE);
        } else {
            warn.setVisibility(View.VISIBLE);
            warn.setText(joinLines(warns));
        }

        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showDetail(m); }
        });
    }

    /**
     * v27：详情页那六道门——**插件算过就用插件的**；插件没给 ⇒ 不显示（只有一个来源）。
     */
    private java.util.List<io.mdt.launcher.api.Gate> gatesOf(Mods.Info m) {
        java.util.List<io.mdt.launcher.api.Gate> pg = mGates.get(pathOf(m));
        // 🔴 v27（用户 2026-10-24「删了吧」）：**不再回落到宿主自己那份判据** ——
        //   六道门只有一个来源（插件）。插件没给 ⇒ **一句不说**（宁可少说），
        //   不能拿宿主那份凑数（那就是同一段判据写两处）。
        return pg == null ? new ArrayList<io.mdt.launcher.api.Gate>() : pg;
    }

    /**
     * v25：模组条目的"身份" = 绝对路径（与 {@code ItemInfo.path} **逐字相同**）。
     * 插件挂结论（`Finding.anchor`）与注入的筛选用的是同一个键。
     */
    private static String pathOf(Mods.Info m) {
        return m == null || m.file == null ? "" : m.file.getAbsolutePath();
    }

    private static String joinLines(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        return sb.toString();
    }

    private String formOf(Mods.Info m) {
        if (m.directory) return Trans.get(ModsActivity.this, R.string.mods_form_dir);
        String n = m.fileName.toLowerCase(java.util.Locale.ROOT);
        return getString(n.endsWith(".jar") ? R.string.mods_form_jar : R.string.mods_form_zip);
    }

    /**
     * 行内用的**短**类型名（列表行里长文案会折行；句子形式见 {@link #kindOf}）。
     *
     * ★ 组合式而不是"枚举所有组合"：位掩码有 4 位 ⇒ 16 种组合，
     *   写成 16 条字符串必然漏（"json 混合 js"就是我漏掉的那类）。
     */
    private String kindShortOf(Mods.Info m) {
        int p = m.parts();
        boolean dex = (p & Mods.JAVA_DEX) != 0;
        boolean cls = (p & Mods.JAVA_CLASS) != 0;
        boolean js = (p & Mods.JS) != 0;
        boolean data = (p & Mods.DATA) != 0;
        if (!dex && !cls && !js) {
            // 没有代码 ⇒ 数据模组（有资源 / 只有元数据两种说法）
            return getString(data ? R.string.mods_kind_s_data_res : R.string.mods_kind_s_data);
        }
        StringBuilder sb = new StringBuilder();
        if (js) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_s_js_fmt, m.jsCount));
        }
        if (dex || cls) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(getString(dex ? R.string.mods_kind_s_java_dex
                    : R.string.mods_kind_s_java_nodex));
        }
        if (data) sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_s_plus_res));
        return sb.toString();
    }

    /**
     * 模组**类型**的句子形式（详情弹窗与导入结果用；判据见 {@link Mods.Info#parts()}）。
     * ★ 用户拿到的 `mod.json` / `.js` / `classes.dex` / `.class` 常常混在一个包里，
     *   先分清**带了哪几种**，"为什么没生效"才知道该看哪条门。
     */
    private String kindOf(Mods.Info m) {
        int p = m.parts();
        StringBuilder sb = new StringBuilder();
        if ((p & Mods.JS) != 0) sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_js_fmt, m.jsCount));
        if ((p & Mods.JAVA_DEX) != 0) {
            if (sb.length() > 0) sb.append(" ＋ ");
            sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_java_dex));
        }
        if ((p & Mods.JAVA_CLASS) != 0) {
            if (sb.length() > 0) sb.append(" ＋ ");
            sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_java_class_fmt, m.classFiles));
        }
        if ((p & Mods.DATA) != 0) {
            if (sb.length() > 0) sb.append(" ＋ ");
            sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_res));
        }
        if (sb.length() == 0) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_kind_data));
        }
        return sb.toString();
    }

    /** 多人游戏那一行的短后缀（判据与措辞都来自游戏自己，见 {@link Mods.Info#multiSafe()}） */
    private String multiShortOf(Mods.Info m) {
        return getString(m.multiSafe() ? R.string.mods_mp_ok : R.string.mods_mp_need);
    }

    /**
     * 模组详情的**入口**（两层结构）。
     *
     * 🔴 为什么拆两层（2026-10-03 用户：「模组详情…还有点乱」「你看下正常模组就知道有多长了」）：
     *   原来把**十几行 + 最多 1500 字原始元数据**全堆在一屏 —— 文件名与标题重复、
     *   整条绝对路径占三行、空依赖也显示「必需依赖：无」、还有「代码入口：logicsugar.LogicSugarMod」
     *   这种类名 ⇒ 正常模组要滚好几屏，中间还夹着「当前 0」这种怪值。
     * ★ 但"依据"**不能删**（F4①d 立的规矩：判据要能看见依据）⇒ 挪到第二层「技术细节」，
     *   第一层只留用户真正要判断的那几样 + **一句话结论**。
     */
    private void showDetail(final Mods.Info m) {
        // ★ 第 259 轮：原来是「详情弹窗里再弹技术细节」= 两个窗口 ⇒ 旧窗一关 dim 就重来一遍、整屏闪。
        //   现在同一个卡片里 push 一页（`CardStack`）：窗口与 dim 全程不变，只有内容交叉淡入。
        final CardStack stack = new CardStack(this);
        final CardStack.Btn toggle = m.metaError == null && m.internalName != null
                ? new CardStack.Btn(getString(m.enabled ? R.string.mods_toggle_btn_off
                                                         : R.string.mods_toggle_btn_on),
                        new Runnable() {
                            @Override public void run() {
                                // 开关是"动作型"（还会再弹确认框）⇒ 先把卡片收掉，别让两个窗口叠着
                                stack.dismiss();
                                confirmToggle(m);
                            }
                        })
                : null;
        stack.root(m.title(), CardStack.text(this, userPart(m)),
                toggle,
                new CardStack.Btn(getString(R.string.mods_detail_tech), new Runnable() {
                    @Override public void run() {
                        stack.push(m.title(), CardStack.text(ModsActivity.this, techText(m)));
                    }
                }),
                new CardStack.Btn(getString(R.string.mods_close), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
        stack.show();
    }

    /** 第一层：正常模组 7 行以内 —— 类型 / 简介 / 联机 / 作者 / 大小 · 开关状态 · 一句话结论 */
    private String userPart(final Mods.Info m) {
        StringBuilder sb = new StringBuilder();
        sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_kind_fmt, kindShortOf(m))).append('\n');
        if (m.metaError != null) {
            // 读不出说明文件：能说的只有"读不出来 + 为什么"；门禁那几关**根本没有依据**，别摆
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_size_fmt,
                    Util.formatSize(m.bytes), formOf(m))).append('\n');
            sb.append('\n').append(Trans.get(ModsActivity.this, R.string.mods_warn_meta_fmt,
                    ModsText.infoMetaReason(this, m)));
            return sb.toString();
        }
        // ★ 第 85 轮：模组**自己写的"这是什么"**原来从没上过界面 ——
        //   `description` 原来只被冲突检测拿去当线索用（那层「简介提及」已于第 229 轮删除）。
        //   而"这模组干什么的"恰恰是用户挑模组时第一个想知道的。
        //   ⚠️ 只给一行：优先它声明的 `subtitle`（本来就是标语），没有才截 `description` 的第一行；
        //      全文仍在第二层「技术细节」的说明文件原文里（信息没删，只是分层）。
        String tag = tagline(m);
        if (!tag.isEmpty()) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_desc_fmt, tag)).append('\n');
        }
        sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_mp_fmt, getString(m.multiSafe()
                ? R.string.mods_mp_ok_long : R.string.mods_mp_need_long))).append('\n');
        if (m.author != null && !m.author.isEmpty()) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_author_fmt, m.author)).append('\n');
        }
        sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_size_fmt,
                Util.formatSize(m.bytes), formOf(m))).append('\n');
        sb.append('\n').append(Trans.get(ModsActivity.this, R.string.mods_detail_state_fmt,
                ModsText.outcome(this, stOf(m),
                        m.settingsKnown, m.willFailJavaLoad(), m.failed, mScan.skipModLoading)));
        java.util.List<io.mdt.launcher.api.Gate> gates = gatesOf(m);
        if (!gates.isEmpty()) {
            int fail = 0;
            for (io.mdt.launcher.api.Gate g : gates) {
                if (!g.pass) fail++;
            }
            sb.append('\n').append(fail == 0 ? Trans.get(ModsActivity.this, R.string.mods_detail_allok)
                    : Trans.get(ModsActivity.this, R.string.mods_detail_somefail_fmt, fail));
        }
        // v25：插件挂在这一项上的结论（行上只给短标签，这里给 detail）
        List<Finding> issues = mIssues.get(pathOf(m));
        if (issues != null && !issues.isEmpty()) {
            sb.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_plugin_head));
            for (Finding f : issues) {
                String d = f.detail == null ? "" : f.detail.trim();
                sb.append('\n').append(d.isEmpty()
                        ? Trans.get(ModsActivity.this, R.string.mods_plugin_issue_plain, f.title)
                        : Trans.get(ModsActivity.this, R.string.mods_plugin_issue_fmt, f.title, d));
            }
        }
        return sb.toString();
    }

    /**
     * 模组自己写的那句"这是什么" —— **首层只给一行**。
     *
     * · 优先 `subtitle`（模组作者本来就当标语写的那句）；没有再退回 `description` 的第一行；
     * · 两头都空 ⇒ 返回空串，调用方**不显示这一行**（"没有就收掉"，别留个空框）；
     * · 截到 60 字：首层是"扫一眼"的地方，长文属于第二层（`rawMeta` 原文，上限 1500 字）。
     */
    static String tagline(Mods.Info m) {
        String s = m.subtitle == null ? "" : m.subtitle.trim();
        if (s.isEmpty() && m.description != null) {
            s = m.description.trim();
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(0, nl);
        }
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /**
     * 第二层「技术细节」：原先那一整份依据 ——
     * 文件名 / 游戏里的名字 / 更新地址 / 代码入口 / 依赖 / 位置 / 逐关明细 / 设置里那几个键 / 原始内容。
     * ⚠️ 这一层是**给排查用的**，允许长；但**空的项不显示**（原来「必需依赖：无」白占一行）。
     */
    private CharSequence techText(final Mods.Info m) {
        StringBuilder sb = new StringBuilder();
        sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_file_fmt, m.fileName,
                Util.formatSize(m.bytes), formOf(m))).append('\n');
        if (m.metaName != null) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_key_fmt, String.valueOf(m.internalName)))
              .append('\n');
        }
        if (m.repo != null && !m.repo.isEmpty()) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_repo_fmt, m.repo)).append('\n');
        }
        if (m.settingsRepo != null && !m.settingsRepo.isEmpty()
                && !m.settingsRepo.equals(m.repo)) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_repo_fmt,
                    Trans.get(ModsActivity.this, R.string.mods_detail_repo_override_fmt, m.settingsRepo))).append('\n');
        }
        // ★ 不再显示「代码入口：logicsugar.LogicSugarMod」那种**类名** ——
        //   用户看不懂，而下面「说明文件原文」里的 main 字段就是它。
        if (!m.dependencies.isEmpty()) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_deps_fmt, join(m.dependencies))).append('\n');
        }
        if (!m.softDependencies.isEmpty()) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_softdeps_fmt,
                    join(m.softDependencies))).append('\n');
        }
        if (m.metaError == null) {
            sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_state_fmt,
                    ModsText.outcome(this, stOf(m),
                            m.settingsKnown, m.willFailJavaLoad(), m.failed, mScan.skipModLoading))).append('\n');
        }
        // ★ 只给**相对位置**：整条 `/storage/emulated/0/Android/data/io.mdt.launcher/slot-xxx/mods/...`
        //   要占四行，用户既看不懂也不需要（用户 2026-10-03：「这个页面也改下」）。
        String rel = m.file.getName();
        File slotDir = Data.dirOf(this, mSlot);
        if (slotDir != null) {
            String p = m.file.getAbsolutePath(), d = slotDir.getAbsolutePath();
            if (p.startsWith(d + "/")) rel = p.substring(d.length() + 1);
        }
        sb.append('\n').append(Trans.get(ModsActivity.this, R.string.mods_detail_path_fmt, rel)).append('\n');

        if (m.metaError == null) {
            // ★ 第 230 轮：这一段**只在插件给了门时**才画 —— 宿主那份判据 v27 已删，
            //   没装插件 / 插件没给结论时不能留一个「它要过这几关」的空标题（也不替插件编结论）。
            java.util.List<io.mdt.launcher.api.Gate> gates = gatesOf(m);
            if (!gates.isEmpty()) {
                sb.append('\n').append(Trans.get(ModsActivity.this, R.string.mods_detail_gate_head)).append('\n');
                sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_gate_note_fmt,
                        mTarget.label.isEmpty() ? Trans.get(ModsActivity.this, R.string.mods_detail_gate_nogame)
                                : mTarget.label)).append('\n');
                for (io.mdt.launcher.api.Gate g : gates) {
                    // ★ 通过 + 无补充说明 ⇒ **只画一行**（note 为空就是"没什么好说的"，
                    //   约定见 api.Gate 的注释）；失败才把原因摊在第二行。
                    if (g.pass) {
                        sb.append(g.note.isEmpty()
                                ? Trans.get(ModsActivity.this, R.string.mods_detail_gate_ok_fmt, g.label)
                                : Trans.get(ModsActivity.this, R.string.mods_detail_gate_ok_note_fmt, g.label, g.note));
                    } else {
                        sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_gate_fail_fmt, g.label, g.note));
                    }
                    sb.append('\n');
                }
            }
            // ★★ 2026-10-08：表头带上"读自哪个文件、几点的值" —— 模组的开关**只有游戏会写**，
            //   不写清时间戳，用户没法判断它是不是刚才那次游戏跑完留下的（用户原话：
            //   「游戏没开时用户也不知道模组到底启没启用啊」）。
            sb.append('\n').append(ModsText.settingsHead(this, Mods.settingsFileOf(this, mSlot)))
                    .append('\n');
            if (m.internalName != null) {
                // ★ 大白话 + 是/否：原来显示 `mod-logicsugar-enabled = true` 这种**键名 + 机器值**，
                //   用户看不懂（键名留在 Mods.enabledKey 那边的注释里，排查时再查）
                sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_setting2_fmt,
                        Trans.get(ModsActivity.this, R.string.mods_setting_enabled),
                        getString(m.enabled ? R.string.mods_setting_yes
                                : R.string.mods_setting_no))).append('\n');
                // ★ 2026-10-08 删掉一行「加载失败过：是」—— 它与上面那六道门里
                //   「❌ 上次启动正常结束…」说的是**同一件事**（那道门的判据就是 `m.failed`），
                //   同一屏里说两遍正是用户说的"感觉好乱"。信息没丢：那道门的说明里写着
                //   "游戏把它标成失败"，还多一句能操作的「想再试就点「启用」」。
                //   （开关区只留真正的**开关**：启用 / 更新地址。）
                sb.append(Trans.get(ModsActivity.this, R.string.mods_detail_setting2_fmt,
                        Trans.get(ModsActivity.this, R.string.mods_setting_repo),
                        m.settingsRepo == null ? Trans.get(ModsActivity.this, R.string.mods_setting_repo_default)
                                : m.settingsRepo)).append('\n');
            }
            sb.append(m.settingsKnown ? "" : Trans.get(ModsActivity.this, R.string.mods_detail_settings_unreadable));
        }

        if (m.rawMeta != null && !m.rawMeta.isEmpty()) {
            int cap = 1500;
            String body = m.rawMeta.length() > cap ? m.rawMeta.substring(0, cap) + "…" : m.rawMeta;
            sb.append('\n').append(Trans.get(ModsActivity.this, R.string.mods_detail_meta_head,
                    Integer.valueOf(cap))).append('\n').append(body);
        }

        return sb;
    }

    /** 按**显示名**在扫描结果里找那个模组（归因器给的就是显示名）。 */
    private static Mods.Info findByName(java.util.List<Mods.Info> mods, String name) {
        if (mods == null || name == null) return null;
        for (Mods.Info m : mods) {
            if (m != null && name.equals(m.title())) return m;
        }
        return null;
    }

    /**
     * 启停的**确认**弹窗：说清"会发生什么" —— 关的是哪个模组（在标题里）、会先备份、
     * 改完读回核对、不对自动还原、以及**游戏没在跑才让改**。
     *
     * ★ 为什么每次都要确认（而不是点一下就改）：这是本工程**第一条会写游戏数据的路径**，
     *   本来就该是"想清楚再点"的动作。
     *
     * ★ 2026-10-04（第 86 轮）两处修正：
     *   ① 这句资源只吃**一个**参数（游戏状态）。原来调用点传了 **6** 个，而资源里只有 `%6$s`
     *      ⇒ 前 5 个（键名 / 旧值 / 新值 / 设置文件 / 备份目录）**一个都不显示**。既是死参数，
     *      又是一颗引信：谁按 1 个参数调它就 `MissingFormatArgumentException` **直接崩**。
     *   ② 因此这里不再自己算键名与两条绝对路径（那是给维护者的依据，不是用户要先看的东西）——
     *      键名/路径仍在**结果弹窗的「技术细节」**里可查（见 {@link #showToggleResult}）。
     */
    private void confirmToggle(final Mods.Info m) {
        confirmToggle(null, m);
    }

    /** 卡片里有栈就推一页确认；没有就自己开一张（第 263 轮：不再"关详情再开弹窗"）。 */
    private void confirmToggle(final CardStack parent, final Mods.Info m) {
        final boolean on = !m.enabled;
        String msg = Trans.get(ModsActivity.this, R.string.mods_toggle_msg_fmt,
                getString(Data.gameAlive(this)
                        ? R.string.mods_toggle_game_alive : R.string.mods_toggle_game_off));
        // ★ 第④项：关之前先算「会连累谁」—— 现在游戏要到下次启动才显示缺依赖，
        //   那时用户早忘了自己关过什么（判据在 Mods.dependents 里，一处实现）。
        if (!on) {
            List<String> who = Mods.dependents(this, mScan.mods, m.internalName);
            if (!who.isEmpty()) {
                StringBuilder w = new StringBuilder(msg);
                w.append("\n\n").append(Trans.get(ModsActivity.this, R.string.mods_dep_warn_head)).append('\n');
                for (String s : who) w.append("· ").append(s).append('\n');
                w.append(Trans.get(ModsActivity.this, R.string.mods_dep_warn_tail));
                msg = w.toString();
            }
        }
        // ★ 在“下次启动会整槽跳过”的状态下关模组，顺带会清掉那个标记（否则白关）
        //   —— 这件事必须在确认框里说出来，不能静默做。
        if (!on && mScan != null && mScan.skipModLoading) {
            msg = msg + "\n\n" + Trans.get(ModsActivity.this, R.string.mods_ignore_retry_note);
        }
        final CardStack[] ref = new CardStack[1];
        final String title = getString(on ? R.string.mods_toggle_ask_on_fmt
                                          : R.string.mods_toggle_ask_off_fmt, m.title());
        ref[0] = CardStack.show(parent, this, title, CardStack.text(this, msg),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.mods_toggle_do), new Runnable() {
                    @Override public void run() {
                        doToggle(ref[0], m.internalName, on, m.title());
                    }
                }));
    }

    /**
     * ★ 批量启停（第③项）。⚠️ 这是**会写游戏数据**的操作 ⇒ 确认框里写清三件事：
     *   改多少个、改前会备份、游戏在跑就不许改（门禁在 {@link Mods#setEnabledAll} 里）。
     */
    private void pickBatch() {
        final String[] items = {Trans.get(ModsActivity.this, R.string.mods_batch_on),
                                Trans.get(ModsActivity.this, R.string.mods_batch_off)};
        final CardStack stack = new CardStack(this);
        stack.root(Trans.get(this, R.string.mods_batch_title),
                CardStack.list(this, items, new CardStack.ItemClick() {
                    @Override public void onClick(int which) {
                        confirmBatch(stack, which == 0);
                    }
                }),
                new CardStack.Btn(Trans.get(this, R.string.close), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
        stack.show();
    }

    private void confirmBatch(final CardStack stack, final boolean on) {
        int n = mScan.mods.size();
        stack.pushMessage(Trans.get(this, R.string.mods_batch_title),
                getString(on ? R.string.mods_batch_confirm_on_fmt
                        : R.string.mods_batch_confirm_off_fmt, n),
                Trans.get(this, R.string.mods_toggle_do), new Runnable() {
                    @Override public void run() { doBatch(stack, on); }
                });
    }

    /** 真正落盘：{@link Mods#setEnabledAll} —— **一次读写**改完整槽，然后摊开结果 */
    private void doBatch(final CardStack stack, boolean on) {
        SettingsBin.Result r = Mods.setEnabledAll(this, mSlot, on, mScan.mods);
        rescan();
        showToggleResult(stack, r, null, on, mScan.mods.size());
    }

    /** 真正落盘：{@link Mods#setEnabled}（门禁 + 备份 + 原子写 + 读回自检），然后把结果摊开 */
    private void doToggle(final CardStack stack, String internalName, boolean on, String shownName) {
        // ★★ 「忽略它试试」（2026-10-08）：在“下次启动会整槽跳过”的状态下，
        //    关掉这个模组**必须连 `launchid.dat` 一起清** —— 否则下次启动连它带别的一起被跳过。
        SettingsBin.Result r = (!on && mScan != null && mScan.skipModLoading)
                ? Mods.ignoreAndRetry(this, mSlot, internalName)
                : Mods.setEnabled(this, mSlot, internalName, on);
        rescan();
        showToggleResult(stack, r, shownName, on, 0);
    }

    /**
     * 启停结果的**两层**呈现（2026-10-04 第 86 轮，照 REF §59）。
     *
     * 🔴 为什么必须分两层：这里原来把 {@link SettingsBin.Result#report()} **直接当弹窗正文** ——
     *   那是给维护者的报告，用户看到的是 `键数：25（未知键**原样保留**）`、
     *   `写后自检：通过（恰好 EOF + 所有键逐条比对一致）`，外加两条绝对路径和原始机器键名
     *   `mod-xxx-enabled: true → false` ⇒ 同屏违反文案纪律 ①（星号会原样显示）③（EOF/键数）⑤。
     *   ⇒ 第一层只回答"改好没有"，依据挪到「技术细节」按钮后面（**依据不删**，§59 的硬要求）。
     *
     * @param shownName 单个模组的显示名（批量传 null）
     * @param count     批量改的个数（单个传 0）
     */
    private void showToggleResult(final CardStack parent, final SettingsBin.Result r, String shownName,
                                  boolean on, int count) {
        final String title;
        final String msg;
        if (!r.ok) {
            title = Trans.get(ModsActivity.this, R.string.mods_toggle_failed);
            // ★ 原因走 userReason()（异常类名要翻成白话），不是原始的 error（那是给排查看的）
            msg = Trans.get(ModsActivity.this, R.string.mods_toggle_fail_msg_fmt, SettingsText.userReason(this, r));
        } else if (r.noop) {
            title = Trans.get(ModsActivity.this, R.string.mods_toggle_noop);
            msg = count > 0
                    ? Trans.get(ModsActivity.this, R.string.mods_batch_noop_msg)
                    : Trans.get(ModsActivity.this, R.string.mods_toggle_noop_msg_fmt,
                            shownName == null ? "" : shownName, toggleWord(on));
        } else {
            // 批量与单个的标题分开（"批量启停完成"比"已改动"更说得清这次干了什么）
            title = getString(count > 0 ? R.string.mods_batch_done : R.string.mods_toggle_done);
            // ★ 备份只在"原来就有设置文件"时才存在（新建那份没有原件可备份，见 SettingsBin.commit）
            String note = getString(r.backup != null ? R.string.mods_toggle_backup_note
                                                     : R.string.mods_toggle_verified_note);
            msg = (count > 0 ? Trans.get(ModsActivity.this, R.string.mods_batch_ok_fmt, count)
                             // ★ 第 150 轮：结果句改成**两条整句**（旧模板填「启用」时是病句
                             //   「已把「X」启用。」）—— `mods_toggle_ok_fmt` 因此不再被调用，
                             //   但**不删**（它随正式版发出去过，用户语言包里可能还写着它）。
                             : Trans.get(ModsActivity.this,
                                     on ? R.string.mods_toggle_ok_on_fmt : R.string.mods_toggle_ok_off_fmt,
                                     shownName == null ? "" : shownName))
                    + note;
        }
        // ★ 结果也是一页（第 263 轮）：原来"点确定 → 关确认框 → 开结果框"又换一次窗。
        final CardStack[] ref = new CardStack[1];
        // ★ 依据不能删：原始报告（体积 / 键数 / 写后自检 / 备份路径）留在第二层
        CardStack.Btn detail = r.noop ? null : new CardStack.Btn(Trans.get(this, R.string.mods_toggle_detail),
                new Runnable() {
                    @Override public void run() {
                        ref[0].push(Trans.get(ModsActivity.this, R.string.mods_toggle_detail_title),
                                CardStack.text(ModsActivity.this, r.report()));
                    }
                });
        ref[0] = CardStack.show(parent, this, title, CardStack.text(this, msg),
                detail,
                new CardStack.Btn(Trans.get(this, R.string.mods_close), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
    }

    /** 启停的"开 / 关"用词（唯一实现：结果弹窗的整句资源靠它填 `%2$s`） */
    private String toggleWord(boolean on) {
        return getString(on ? R.string.mods_word_on : R.string.mods_word_off);
    }

    /**
     * dev 口用的"人读报告"（`--es dev_mods_scan <槽>`）。
     *
     * ★ 为什么报告落在**界面之外的静态方法**里：dev 口从 `MainActivity` 进来，
     *   而"扫描 → 依赖 → 目标版本"这三步必须与页面**完全同源**（否则报告说没问题、
     *   页面说版本不符，就成了两份判据）。所以这里自己跑完整条链，页面里的显示
     *   只是把它渲染出来。
     */
    static String report(android.content.Context ctx, String slot) {
        Mods.Scan scan = Mods.scan(ctx, slot);
        Mods.Resolved resolved = Mods.resolveDependencies(scan.mods);
        Mods.Target target = Mods.targetsFor(ctx, slot);
        int build = target.build, rev = target.revision;

        StringBuilder sb = new StringBuilder();
        sb.append("槽 = ").append(scan.slot).append('\n');
        sb.append("mods 目录 = ").append(scan.modsDirPath()).append('\n');
        sb.append("设置 = ").append(scan.settingsNote).append('\n');
        sb.append("launchid.dat = ").append(scan.launchIdExists)
          .append("  skipModLoading = ").append(scan.skipModLoading).append('\n');
        sb.append("指向本槽的版本 = ").append(target.versions.isEmpty() ? "（无）" : join(target.versions))
          .append("  比对基准 = ").append(target.label.isEmpty() ? "（无）" : target.label)
          .append("（build=").append(build).append(", revision=").append(rev).append("）\n");
        sb.append("模组 ").append(scan.mods.size()).append(" 个")
          .append("；读不出元数据 ").append(scan.broken.size()).append(" 个")
          .append("；游戏不看的条目 ").append(scan.ignored.size()).append(" 个")
          .append("；孤儿设置键 ").append(scan.orphanKeys.size()).append(" 个\n");
        sb.append('\n');
        for (Mods.Info m : scan.mods) {
            sb.append("· ").append(m.titleWithVersion())
              .append("  [").append(ModsText.stateLabel(ctx,
                    Mods.stateOf(m, resolved, build, rev))).append("]\n");
            sb.append("    文件 = ").append(m.fileName)
              .append("  ").append(Util.formatSize(m.bytes))
              .append("  ").append(m.directory ? "目录" : "包").append('\n');
            sb.append("    内部名 = ").append(m.internalName)
              .append("  minGameVersion = ").append(m.minGameVersion)
              .append("  enabled = ").append(m.enabled)
              .append("  failed = ").append(m.failed).append('\n');
            sb.append("    classes.dex = ").append(m.hasClassesDex)
              .append("  scripts = ").append(m.hasScripts)
              .append("(").append(m.jsCount).append(" js, main.js=").append(m.hasMainJs).append(")")
              .append("  meta = ").append(m.metaName).append('\n');
            if (!m.dependencies.isEmpty()) {
                sb.append("    依赖 = ").append(m.dependencies).append('\n');
            }
            if (!m.softDependencies.isEmpty()) {
                sb.append("    软依赖 = ").append(m.softDependencies).append('\n');
            }
        }
        for (Mods.Info m : scan.broken) {
            sb.append("· ").append(m.fileName).append("  —— 读不出元数据：")
              .append(m.metaError).append('\n');
        }
        for (String i : scan.ignored) sb.append("· （游戏不加载）").append(i).append('\n');
        for (String p : scan.problems) sb.append("⚠ ").append(p).append('\n');
        if (!scan.orphanKeys.isEmpty()) sb.append("孤儿设置键：").append(scan.orphanKeys).append('\n');
        for (java.util.Map.Entry<String, Mods.State> en : resolved.states.entrySet()) {
            if (en.getValue() != Mods.State.ENABLED) {
                sb.append("依赖链：").append(en.getKey()).append(" → ")
                  .append(ModsText.stateLabel(ctx, en.getValue())).append('\n');
            }
        }
        sb.append("\n（本报告是只读扫描：没有创建、修改、删除任何文件）\n");
        return sb.toString();
    }
}
