package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * 地图页（槽的子页面，REF §56）—— **从槽二级页面进**，返回就回那一页。
 *
 * 🔴 为什么不做成弹窗：弹窗属于**发起它的那个 Activity**。以前地图列表挂在存档页上，
 *   用户点"关闭"之后落在存档页（"上一级"），观感像是又开了一个页面（用户 2026-10-03 反馈）。
 *   做成独立页面后：返回键 = 回槽页面，语义就对了。
 *
 * 口径与实现全部复用既有组件：{@link Maps}（三来源清点）· {@link MapLoad}（配色表+渲染+缓存）
 * · {@link MapFiles}（导入/删除，删除走中转站"挪不删"）· {@link MsavListAdapter}（卡片行）。
 *
 * 🔴 **必须继承 {@link BaseActivity}**（2026-10-04 修）：深浅色设置的唯一生效点是它的
 *   `attachBaseContext`。本类原来写的是 `extends Activity` ⇒ 用户设了「浅色」/「深色」之后，
 *   本页仍按系统配色（症状：同一应用两套配色，但不报错）。见 {@link SlotActivity} 类注释。
 */
public class MapsActivity extends BaseActivity {

    /** v24（第 226 轮）：本页的插件挂载点（content / action / section 统一由 BaseActivity 挂）。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_MAPS; }

    @Override protected String pluginSlot() { return mSlot; }
    /** 目标槽（**本页一切以它为准**，不许回落到当前槽） */
    public static final String EXTRA_SLOT = "slot";
    private static final int REQ_MAP = 61;
    /** 导出：SAF 的「保存到哪」回来 */
    private static final int REQ_MAP_EXPORT = 62;
    /** 地图详情页（F21）：回来时若它是"删掉了"就重新清点 */
    private static final int REQ_MAP_DETAIL = 63;
    /**
     * dev 口：`--es dev_map_import &lt;路径&gt;` —— 把"用 SAF 选文件"换成路径。
     *
     * ★ 为什么要它：系统文件选择器（SAF）**自动化不了**（第 104 轮 `dev_zip_confirm` 就是
     *   同一个理由），而"选到一份存档之后要走的那条路"必须能被真机验一遍。
     *   后面的路与界面**逐字相同**（`importFile(...)` 原路进）。
     * ⚠️ 本页 `android:exported="false"` ⇒ 只有我们自己的进程能发这个 Intent。
     */
    public static final String EXTRA_DEV_IMPORT = "dev_map_path";

    private String mSlot;
    private TextView mHead;
    /** 空态（2026-10-04 补）：一张图都没有时把「为什么没有 / 下一步」说出来，见布局注释 */
    private TextView mEmpty;
    /**
     * 列表本身（2026-10-05 补）：**转图/导入成功之后要能自己重扫一遍**。
     * ★ 为什么必须有：`scan(lv)` 原来只接 onCreate 里那个局部变量 ⇒ 做完一次导入除了
     *   `recreate()` 没有别的刷新手段，而转图那条路走的是"结果弹窗"（用户不点关闭就一直是旧列表，
     *   真机上就出现过"已经转好了、表头还写着本槽 0"——用户当场提的："导入之后要自动刷新页面啊"）。
     */
    private ViewGroup mList;
    private MsavListAdapter mAdapter;
    /** 行单击（容器形态没有 `OnItemClickListener`，见 `onCreate` 里的注释） */
    private MsavListAdapter.RowClick mRowClick;
    private List<Maps.Item> mItems;
    private String mApkPath;
    /**
     * ★★ 导入地图的目标槽（2026-10-04 修）：必须是 **static**。
     *
     * 为什么：`startActivityForResult` 的结果**只回到发起它的那个实例**，而本页没声明
     * `configChanges` ⇒ 用户在系统选择器里时一转屏（或被系统回收后回前台），发起方实例
     * 就被销毁重建 —— 实例字段在新实例里是 null，回调于是**静默 return**，
     * 用户看到的就是"选完文件什么都没发生"（无 Toast、无报错）。
     * ⚠️ 这是本工程**踩过一次的坑**：{@link SlotIo} 的 `sMsavTarget` / `sZipSlot`
     *   就是同一件事，那边是 static 且在注释里写明了理由 —— 这里原来漏了。
     * ⚠️ 纪律同 {@link SlotIo}：**每次发起前重设、回调进来先清**（否则用户取消后
     *   残留的目标会被下一次选择"接上"，文件落进上一个槽）。
     */
    private static String sMapTarget;
    /** 导出：SAF 回来时要写出去的那一条（**成败都先清空**，见 onActivityResult）。
     *  ★ static 的理由同 {@link #sMapTarget}（转屏/回收后回调仍要认得它）。 */
    private static Maps.Item sExportItem;

    // ── 第 177 轮（杀后台 P0）：SAF 待办的磁盘表示（进程被杀后靠它恢复）──────────
    private static final String PEND_IMPORT = "maps.import";
    private static final String PEND_EXPORT = "maps.export";
    /** 列表三件套（导出对话框直接复用这一批文本与缩略图，不必重扫、重渲染） */
    private String[] mTitles;
    private String[] mSubs;
    private android.graphics.Bitmap[] mThumbs;

    // ── 搜索 / 排序 / 只看有问题的（2026-10-07 第 122 轮；**纯前端**，一个文件都不碰）──
    /** 转屏要恢复的三个状态 —— 同模组页那条教训：不存就会"一转屏用户打的字没了"，且日志里什么都没有 */
    private static final String STATE_QUERY = "mdt-maps-query";
    private static final String STATE_SORT = "mdt-maps-sort";
    private static final String STATE_ONLY = "mdt-maps-only";
    /** ★ 2026-10-11：新加的**来源筛**（`Maps.FROM_ANY` = 不筛）也要跟着转屏活下来 */
    private static final String STATE_SRC = "mdt-maps-src";
    private String mQuery = "";
    private int mSort = ListQuery.SORT_NAME;
    private boolean mOnlyProblems;
    /** 来源筛：`Maps.FROM_ANY` / `FROM_SLOT` / `FROM_GAME` / `FROM_MOD`（纯前端，一个文件都不碰） */
    private int mSrc = Maps.FROM_ANY;
    /**
     * v25（第 280 轮统一）：插件注入的**筛选**结果（条目身份集合；null = 没选）。
     * ★ 身份见 {@link PluginPicks#idOf} —— 游戏自带 / 模组自带的图在 APK、zip 里，
     *   没有 `path`，所以不能用路径当唯一键（这是原来的漏洞）。
     */
    private java.util.Set<String> mPluginFilterIds;
    private String mPluginFilterKey;
    /** v25：插件注入的**排序**结果（按顺序的条目身份；null = 没选） */
    private java.util.List<String> mPluginSortIds;
    private String mPluginSortKey;

    /** 插件筛选 / 排序算完那一下（四个列表页各一份，形状相同）：结果缓存下来再重画。 */
    private final PluginPicks.Sink mPluginSink = new PluginPicks.Sink() {
        @Override public void onPluginFilter(String key, java.util.Set<String> ids) {
            mPluginFilterIds = ids;
            mPluginFilterKey = key;
            rebuildList();
        }

        @Override public void onPluginSort(String key, java.util.List<String> ids) {
            mPluginSortIds = ids;
            mPluginSortKey = key;
            rebuildList();
        }
    };

    /** 地图条目的身份：槽内文件 = 路径；包内条目 = `容器!/条目`（与 {@code ItemInfo} 同一口径）。 */
    private static final PluginPicks.IdOf<Maps.Item> MAP_ID = new PluginPicks.IdOf<Maps.Item>() {
        @Override public String idOf(Maps.Item it) {
            return it == null ? "" : PluginPicks.idOf(it.file, it.container, it.entry);
        }
    };
    private EditText mSearch;
    private TextView mFiltered;
    /**
     * 当前**显示**的那一批（筛过 / 排过）。
     * ⚠️ 与 {@link #mItems}（全部）是两回事：点行、补缩略图都按它取；
     *   而导出对话框、源图匹配走的是**全部**（那是"挑一份"的入口，不该被列表上的搜索框缩窄）。
     */
    private List<Maps.Item> mShown;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            finish();                       // 没槽名就退出，绝不猜
            return;
        }
        mSlot = mSlot.trim();
        // ★ 转屏恢复搜索 / 排序 / 筛选（见 STATE_* 的注释）
        if (b != null) {
            String q = b.getString(STATE_QUERY);
            if (q != null) mQuery = q;
            mSort = b.getInt(STATE_SORT, ListQuery.SORT_NAME);
            mOnlyProblems = b.getBoolean(STATE_ONLY, false);
            mSrc = b.getInt(STATE_SRC, Maps.FROM_ANY);
        }
        setTitle(Trans.get(MapsActivity.this, R.string.maps_title_fmt, mSlot));

        View root = getLayoutInflater().inflate(R.layout.activity_maps, null);
        // ★ 必须有（见 activity_maps.xml 的注释）：targetSdk ≥ 35 强制 edge-to-edge，
        //   不补 insets 的话整页从 y=0 开始画 ⇒ 表头 + 两行动作被状态栏和顶栏整块盖住
        //   （用户 2026-10-03 报的"顶部就有问题"就是这个）。
        Util.applySystemInsets(root);
        setContentView(root);

        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.tx_map_save_name_hint, R.string.map_save_name_hint);
        Trans.bind(root, R.id.map_save_warn, R.string.map_save_nocores_warn);

        mHead = (TextView) root.findViewById(R.id.maps_head);
        mHead.setText(Trans.get(MapsActivity.this, R.string.maps_scanning));
        mEmpty = (TextView) root.findViewById(R.id.maps_empty);

        // ★ 2026-10-17（第 155 轮）：「导入地图…」= **一行两路**（从文件 / 从本槽存档）。
        //   原来是两行（`row_maps_import` + `row_maps_from_save`），而它们是同一件事的两条路
        //   —— 都是往本槽 `maps/` 里加一张图 ⇒ 照主界面「添加游戏」的成例合成一行（REF §84）。
        //   用户原话：「有些页面（比如地图）按钮已经够多了，要不整合下？」
        Util.bindAction(root, R.id.row_maps_import, R.drawable.ic_download,
                R.string.maps_import, R.string.maps_import_sub, new Runnable() {
                    @Override public void run() { promptImportSource(); }
                });

        // ★ 导出与导入成对放在**页面顶上**（用户 2026-10-03 定案）
        Util.bindAction(root, R.id.row_maps_export, R.drawable.ic_upload,
                R.string.maps_export, R.string.maps_export_sub, new Runnable() {
                    @Override public void run() {
                        promptExportMap();
                    }
                });

        // ★ 2026-10-11（第 129 轮）：**批量删除**（勾几份 ⇒ 一次性挪进中转站）。
        //   与导出同一个勾选对话框；判据与单份删除**同一条**（见 `promptDeleteMaps` 的注释）。
        Util.bindAction(root, R.id.row_maps_del, R.drawable.ic_trash,
                R.string.batch_del_row, R.string.batch_del_sub, new Runnable() {
                    @Override public void run() { promptDeleteMaps(); }
                });

        // ★ 2026-10-07（第 122 轮）：「筛选与排序」——与模组页同一行、同一个对话框形状        //   （用户：「把搜索 / 排序推广到地图 / 蓝图 / 存档列表」）。
        //   ⚠️ 对话框里那几条词（按名称 / 有问题的在前 / 按大小 / 只看有问题的 / 清空筛选）
        //   **复用模组页的资源**：四个列表页说的是同一件事，各写一份只会让用户语言包里
        //   多出四条一模一样的翻译 —— 而"模组"两个字并不在那几条词里，不会串味。
        Util.bindAction(root, R.id.row_maps_filter, R.drawable.ic_settings,
                R.string.mods_filter_title, R.string.maps_act_filter_sub, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });

        // v24（第 226 轮）：插件卡由 BaseActivity → PluginEntries.attachPage 统一挂
        //   （content 行 + action 行都在那张卡里，不再插到动作卡里）

        // ★★ 2026-10-16（第 151b 轮）：列表不再是 `ListView`，而是整页 `ScrollView` 里手工挂载的
        //   容器（理由见 `activity_maps.xml` 头：横屏可用高不够时 `weight` 子视图会归零 ⇒
        //   搜索框与列表被挤出屏幕，而且根不能滚）。**单击因此挂在每一行上**，
        //   回调里的下标就是"当前显示的那一份"的下标（与 `getView` 的 i 同一套）。
        final ViewGroup lv = (ViewGroup) root.findViewById(R.id.maps_list);
        mList = lv;
        // 行单击 → 打开详情页（原来由 ListView 的 OnItemClickListener 提供）
        mRowClick = new MsavListAdapter.RowClick() {
            @Override public void onRow(int pos) {
                if (mShown != null && pos >= 0 && pos < mShown.size()) showDetail(mShown.get(pos));
            }
        };

        // 搜索框（纯前端过滤，只在这里的内存里筛）+ 「显示 N / 共 M」那一行
        mSearch = (EditText) root.findViewById(R.id.maps_search);
        mFiltered = (TextView) root.findViewById(R.id.maps_filtered);
        Util.bindSearch(mSearch, mQuery, new Runnable() {
            @Override public void run() {
                mQuery = mSearch == null ? "" : mSearch.getText().toString();
                rebuildList();
            }
        });
        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.maps_search, R.string.maps_search_hint);

        scan(lv);

        // dev 口：把"选文件"换成路径（SAF 自动化不了）。⚠️ 必须放在 scan() 之后，
        //   因为 importFile 成功后会 recreate() 再看一次列表。
        final String devPath = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DEV_IMPORT);
        if (devPath != null && !devPath.trim().isEmpty()) {
            File df = new File(devPath.trim());
            importFile(Uri.fromFile(df), df.getName(), mSlot, false);
        }

        // F9 深链：本页是被外部"打开方式"（浏览器 / 文件管理器）叫起来的
        //   ⇒ 与 SAF 那条路**同一份**分流判据（见 importDocs）
        final List<BatchIo.Doc> dl = DeepLink.docsOf(getIntent(), this);
        if (!dl.isEmpty()) importDocs(dl, mSlot);
    }

    /**
     * 导入入口的**唯一**判据 —— SAF 那条路（{@link #onActivityResult}）与 F9 深链那条路共用。
     *
     * <p>三条：① 游戏在跑 ⇒ 先挡（写槽的门禁，见 {@link Data#gameAlive}；
     * 按钮那边也有一道同样的预检，只是为了让用户别白选一次文件）；
     * ② ≥2 份或一个压缩包 ⇒ 批量（判据在 {@link BatchIo#wantsBatch}）；
     * ③ 否则走单份那条老路（含「这其实是一份存档 ⇒ 问名字」与逐个同名确认）。
     */
    private void importDocs(List<BatchIo.Doc> docs, final String slot) {
        if (docs == null || docs.isEmpty()) return;
        if (Data.gameAlive(this)) {
            alert(Trans.get(MapsActivity.this, R.string.game_busy_title),
                    Trans.get(MapsActivity.this, R.string.maps_import_busy_msg));
            return;
        }
        if (BatchIo.wantsBatch(docs)) {
            BatchIo.runImport(this, slot, docs, BatchIo.KIND_MAPS, new Runnable() {
                @Override public void run() { refreshList(); }
            });
            return;
        }
        importFile(docs.get(0).uri, docs.get(0).name, slot, false);
    }

    /**
     * 重新清点并刷新列表（**导入/转图成功之后立刻用**）。
     *
     * ★ 用户 2026-10-05：「**导入之后要自动刷新页面啊**」—— 原来只有 `recreate()` 一条刷新路，
     *   而"存档 → 地图"走的是结果弹窗（不点「关闭」就一直是旧列表，表头还写着"本槽 0"）。
     * ★ 与 `recreate()` 的分工：这个只重扫列表（不重建 Activity）⇒ 弹窗还开着也不会闪。
     */
    private void refreshList() {
        if (Util.dead(this) || mList == null) return;
        mHead.setText(Trans.get(MapsActivity.this, R.string.maps_scanning));
        scan(mList);
    }

    /** 三来源清点 + 缩略图**后台逐张渲染**（出来一张刷一张） */
    private void scan(final ViewGroup lv) {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Maps.Item> items;
                final String apk;
                try {
                    Mods.Target t = Mods.targetsFor(MapsActivity.this, mSlot);
                    apk = t.apkPath;
                    items = Maps.scan(MapsActivity.this, mSlot, apk);
                } catch (Throwable ex) {
                    android.util.Log.w("MDTLauncher", "map scan failed", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // ⚠️ 这里只改文字，不会崩；但统一走 Util.dead() 是为了
                            //    "一个工程只有一种判据"（转屏后旧实例仍在 post，判死即止）。
                            if (Util.dead(MapsActivity.this)) return;
                            // ★ 原来这里是 `mHead.setText(String.valueOf(ex))` —— 表头直接
                            //   变成 `java.lang.NullPointerException: …`（2026-10-04 修）。
                            //   异常原文归 logcat / 报告，界面只给白话 + 下一步。
                            mHead.setText(Trans.get(MapsActivity.this, R.string.maps_scan_failed));
                        }
                    });
                    return;
                }
                final String[] titles = new String[items.size()];
                final String[] subs = new String[items.size()];
                final android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    Maps.Item it = items.get(i);
                    titles[i] = it.name();
                    subs[i] = it.sourceLabel(MapsActivity.this) + " · " + it.line(MapsActivity.this);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mItems = items;
                        mApkPath = apk;
                        // ★ 三件套存成字段：导出对话框直接复用（缩略图已经在后台渲染过一遍，
                        //   再渲染一次既慢又白费电）；它们**与 mItems 同下标**，
                        //   列表显示的是筛过的那一份（见 rebuildList）
                        mTitles = titles;
                        mSubs = subs;
                        mThumbs = thumbs;
                        mHead.setText(Trans.get(MapsActivity.this, R.string.maps_counts_fmt,
                                Maps.count(items, Maps.FROM_SLOT),
                                Maps.count(items, Maps.FROM_GAME),
                                Maps.count(items, Maps.FROM_MOD)));
                        // ★ 列表本体（含搜索 / 排序 / 只看有问题的 / 空态）只有一处实现
                        rebuildList();
                    }
                });
                for (int i = 0; i < items.size(); i++) {
                    final int idx = i;
                    final android.graphics.Bitmap bm =
                            MapLoad.image(MapsActivity.this, items.get(idx), apk, MapLoad.THUMB);
                    if (bm == null) continue;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(MapsActivity.this)) return;
                            // ★ 只更新**这一行**，绝不 `notifyDataSetChanged()` ——
                            //   后者会让整张列表在用户滚动时不断重排，手感就是"滑不上去 / 一滑就跳"
                            //   （114 张缩略图 = 114 次重排，用户 2026-10-03 实测反馈）。
                            //   ★ 2026-10-06（第 116 轮）：这段"换第 i 行的图"收进了
                            //   `MsavListAdapter#refreshThumb` —— 存档 / 蓝图两条线也要它，
                            //   三份内联实现迟早会有一份改了另两份不改（且**不报错**）。
                            //   ★ 2026-10-07（第 122 轮）：下标要多绕一步 —— 后台这张图是按
                            //   **全部清单**的下标算的，而列表里显示的可能只是筛过的一部分
                            //   ⇒ 收进 thumbReady（那里同时负责写主数组）。
                            thumbReady(idx, bm);
                        }
                    });
                }
            }
        }, "maps-page").start();
    }

    // ── 搜索 / 排序 / 只看有问题的（第 122 轮）───────────────────────────────

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_QUERY, mQuery);
        out.putInt(STATE_SORT, mSort);
        out.putBoolean(STATE_ONLY, mOnlyProblems);
        out.putInt(STATE_SRC, mSrc);
    }

    /**
     * 重建列表 = 搜索 / 筛选 / 排序（判据在 {@link Maps#filterAndSort}）+ 挂行 + 下面那两行提示。
     *
     * <p>★ 为什么"空态"也在这里：空态现在有**两种**（一张图都没有 / 筛完一条不剩），
     * 而只有这里同时知道"总共几张"与"筛完几张" —— 留在扫描回调里就会退化成前一种
     * （症状：搜了个不存在的词，界面说"槽里还没有地图"，用户以为图丢了）。
     *
     * <p>★ 适配器每次重建都新建一个：`MsavListAdapter` 拿的是三个**数组**（标题/副标题/缩略图），
     * 筛完的下标与主清单不同 ⇒ 直接切一份新的最省事，而几百个引用在 UI 线程上可以忽略。
     * 缩略图从**主数组** `mThumbs` 里搬（已经渲染好的不会因为筛一下就要重渲染）。
     */
    private void rebuildList() {
        if (mList == null || mItems == null) return;
        mShown = Maps.filterAndSort(mItems, mQuery, mSort, mOnlyProblems, mSrc);
        // v25（第 280 轮）：插件注入的筛选 / 排序 —— 结果在选中那一刻就算好并缓存（身份集合 / 顺序），
        // 这里只做纯内存应用。判据与应用只有一处（`PluginPicks`），四个列表页共用。
        mShown = PluginPicks.applyFilter(mShown, mPluginFilterIds, MAP_ID);
        mShown = PluginPicks.applySort(mShown, mPluginSortIds, MAP_ID);
        boolean filtering = ListQuery.filtering(mQuery, mSort, mOnlyProblems,
                ListQuery.SORT_NAME, mSrc != Maps.FROM_ANY) || mPluginFilterIds != null;
        String[] titles = new String[mShown.size()];
        String[] subs = new String[mShown.size()];
        android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[mShown.size()];
        for (int i = 0; i < mShown.size(); i++) {
            int k = allIndexOf(mShown.get(i));
            titles[i] = mShown.get(i).name();
            subs[i] = k < 0 ? "" : mSubs[k];
            thumbs[i] = (k < 0 || mThumbs == null) ? null : mThumbs[k];
        }
        mAdapter = new MsavListAdapter(this, titles, subs, thumbs);
        mAdapter.fillInto(mList, mRowClick);

        if (mFiltered != null) {
            mFiltered.setVisibility(filtering ? View.VISIBLE : View.GONE);
            if (filtering) {
                mFiltered.setText(Trans.get(MapsActivity.this, R.string.maps_filtered_fmt,
                        mShown.size(), mItems.size()));
            }
        }
        if (mEmpty != null) {
            boolean none = mShown.isEmpty();
            mEmpty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (none) {
                // ★ 两种空态必须分开说：一张都没有 ≠ 筛掉了（后者用户自己能救回来 —— 清空筛选）
                mEmpty.setText(filtering
                        ? Trans.get(MapsActivity.this, R.string.maps_empty_filtered)
                        : Trans.get(MapsActivity.this, R.string.maps_empty_fmt, mSlot));
            }
        }
    }

    /** 一条地图在**全部清单**里的下标（`Item` 没有 equals ⇒ 比的是同一个对象） */
    private int allIndexOf(Maps.Item it) {
        if (mItems == null || it == null) return -1;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i) == it) return i;
        }
        return -1;
    }

    /** 一条地图在**当前显示的那一份**里的下标（-1 = 现在被筛掉了） */
    private int shownIndexOf(Maps.Item it) {
        if (mShown == null || it == null) return -1;
        for (int i = 0; i < mShown.size(); i++) {
            if (mShown.get(i) == it) return i;
        }
        return -1;
    }

    /**
     * 后台渲染好一张缩略图（**按全部清单的下标**）：写主数组 + 若它此刻在列表里就只刷那一行。
     * ★ 被筛掉的那些照样写主数组 —— 用户清掉搜索词时 `rebuildList` 会把它从主数组里搬出来，
     *   不必再渲染一次。
     */
    private void thumbReady(int allIdx, android.graphics.Bitmap bm) {
        if (mThumbs != null && allIdx >= 0 && allIdx < mThumbs.length) mThumbs[allIdx] = bm;
        if (mAdapter == null || mList == null || mItems == null) return;
        if (allIdx < 0 || allIdx >= mItems.size()) return;
        int k = shownIndexOf(mItems.get(allIdx));
        if (k < 0) return;
        mAdapter.setThumb(k, bm);
        mAdapter.refreshThumb(mList, k);
    }

    /**
     * 「筛选与排序」对话框。
     * ★ 与模组页同一个形状（排序单选 + 「只看有问题的」开关 + 清空），也**共用它那几条词** ——
     *   四个列表页说的是同一件事。地图这一页没有"类型"那一维（那是模组独有的）。
     * ★ 2026-10-11 补上**来源**四行（判据 `Maps.matchesSource`）：那三个来源原来是混在一张表里
     *   靠 ` · ` 区分的，用户没法只看"游戏自带"或"某个模组带的"。**纯前端过滤，一个文件都不碰**。
     */
    /**
     * 排序只能有一个：选了宿主这几档就清掉插件注入的排序。
     * ★ 第 285 轮用户：「为什么宿主排序和插件排序可以同时勾选」—— `mSort` 与 `mPluginSortKey`
     *   原来各管各的，界面上两个 ✓ 同时亮；而实际生效的只有插件那份（`applySort` 在
     *   `ListQuery` 排完之后整体重排）⇒ 宿主那个勾是假的，必须一起去掉。
     */
    private void clearPluginSort() {
        mPluginSortIds = null;
        mPluginSortKey = null;
    }

    private void pickFilter() {
        final String tick = "✓ ";
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<Runnable>();
        labels.add((mSort == ListQuery.SORT_NAME && mPluginSortKey == null ? tick : "")
                + Trans.get(MapsActivity.this, R.string.mods_filter_sort_name));
        acts.add(new Runnable() { @Override public void run() {
            mSort = ListQuery.SORT_NAME; clearPluginSort(); rebuildList();
        } });
        labels.add((mSort == ListQuery.SORT_PROBLEM && mPluginSortKey == null ? tick : "")
                + Trans.get(MapsActivity.this, R.string.mods_filter_sort_state));
        acts.add(new Runnable() { @Override public void run() {
            mSort = ListQuery.SORT_PROBLEM; clearPluginSort(); rebuildList();
        } });
        labels.add((mSort == ListQuery.SORT_SIZE && mPluginSortKey == null ? tick : "")
                + Trans.get(MapsActivity.this, R.string.mods_filter_sort_size));
        acts.add(new Runnable() { @Override public void run() {
            mSort = ListQuery.SORT_SIZE; clearPluginSort(); rebuildList();
        } });
        // ★ 第 283 轮（用户：「按照排序和筛选分开，而不是按照宿主和插件分开」）：
        //   插件那些行**按功能**并进同一组 —— 排序紧跟在宿主的三档排序之后，
        //   筛选跟在本页那几条筛选之后。原来按"宿主 / 插件"分，排序被挤到了筛选下面。
        for (PluginPicks.Choice c : PluginPicks.choices(this, Extensions.PAGE_MAPS, mSlot,
                true, mPluginSortKey, mPluginSink)) {
            labels.add(c.label);
            acts.add(c.run);
        }
        // ── 排序到此为止，以下是筛选（Sheet 在这里画一条横向分割线）──
        final int gapAt = labels.size();
        labels.add((mOnlyProblems ? tick : "")
                + Trans.get(MapsActivity.this, R.string.mods_filter_only));
        acts.add(new Runnable() { @Override public void run() {
            mOnlyProblems = !mOnlyProblems; rebuildList();
        } });
        addSrcChoice(labels, acts, Maps.FROM_ANY, R.string.list_src_any, tick);
        addSrcChoice(labels, acts, Maps.FROM_SLOT, R.string.list_src_slot, tick);
        addSrcChoice(labels, acts, Maps.FROM_GAME, R.string.list_src_game, tick);
        addSrcChoice(labels, acts, Maps.FROM_MOD, R.string.list_src_mod, tick);
        for (PluginPicks.Choice c : PluginPicks.choices(this, Extensions.PAGE_MAPS, mSlot,
                false, mPluginFilterKey, mPluginSink)) {
            labels.add(c.label);
            acts.add(c.run);
        }
        labels.add(Trans.get(MapsActivity.this, R.string.mods_filter_reset));
        acts.add(new Runnable() {
            @Override public void run() {
                mSort = ListQuery.SORT_NAME;
                mOnlyProblems = false;
                mSrc = Maps.FROM_ANY;
                mPluginFilterIds = null;
                mPluginFilterKey = null;
                mPluginSortIds = null;
                mPluginSortKey = null;
                mQuery = "";
                // 清框会经 TextWatcher 走一趟 rebuildList，这里再走一趟也无害（同模组页）
                if (mSearch != null) mSearch.setText("");
                rebuildList();
            }
        });
        Sheet.show(this, R.string.mods_filter_title, labels, acts, gapAt);
    }

    /** 来源那一行（四选一，勾当前那个）。 */
    private void addSrcChoice(java.util.List<String> labels, java.util.List<Runnable> acts,
                              final int src, int textRes, String tick) {
        labels.add((mSrc == src ? tick : "") + Trans.get(MapsActivity.this, textRes));
        acts.add(new Runnable() {
            @Override public void run() { mSrc = src; rebuildList(); }
        });
    }

    /**
     * 点开一项 ⇒ **进详情页**（F21：大图 + 元数据 + 四段资源统计）。
     *
     * ★ 为什么从弹窗改成独立页面：详情里现在有四段可折叠的统计，弹窗装不下；而且弹窗属于
     *   发起它的那个页面 —— 关掉会落在"上一级"（地图列表那一版已经因此改过一次，REF §56）。
     * ★ 用 `startActivityForResult`：详情页里删掉这张图后要回来**重新清点**
     *   （比手工维护列表可靠，与导入/删除原本的做法一致）。
     */
    private void showDetail(final Maps.Item it) {
        Intent i = new Intent(this, MapDetailActivity.class);
        i.putExtra(MapDetailActivity.EXTRA_SLOT, mSlot);
        Maps.putExtra(i, it);
        startActivityForResult(i, REQ_MAP_DETAIL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // ★ 第 127 轮：批量导出的落点（勾了多份时走它；一份仍走下面 REQ_MAP_EXPORT 那条老路）
        if (BatchIo.handleExportResult(this, requestCode, resultCode, data)) return;
        if (requestCode == REQ_MAP_DETAIL) {
            // 详情页里把这张图删了 ⇒ 回来重新清点（与导入/删除原本的做法一致）
            if (resultCode == RESULT_OK) recreate();
            return;
        }
        if (requestCode == REQ_MAP_EXPORT) {
            // ★ 与导入同一条纪律：无论成败**先清掉待写的那一条**（否则下一次导出会认错图）
            final JSONObject pend = Pending.take(this, PEND_EXPORT);
            Maps.Item it = sExportItem;
            sExportItem = null;
            if (it == null && pend != null) it = itemFromJson(pend);
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            if (it == null) {
                Toast.makeText(this, R.string.pending_lost, Toast.LENGTH_LONG).show();
                return;
            }
            exportItem(it, data.getData());
            return;
        }
        if (requestCode != REQ_MAP) return;
        // ★ 与存档那条同一纪律：无论成败**先清目标槽**
        final JSONObject pend = Pending.take(this, PEND_IMPORT);
        String slot = sMapTarget;
        sMapTarget = null;
        if (slot == null && pend != null) slot = pend.optString("slot", null);
        if (resultCode != RESULT_OK || data == null) return;
        if (slot == null || slot.trim().isEmpty()) {
            Toast.makeText(this, R.string.pending_lost, Toast.LENGTH_LONG).show();
            return;
        }
        // ★ 第 127 轮：一批（多选 / 一个 zip）走批量；**一份普通文件**仍旧走单份那条路
        //   （那条路有「这其实是一份存档 ⇒ 问名字」和逐个同名确认，逐份弹框在批量里是折磨）
        importDocs(BatchIo.docsOf(this, data), slot);
    }

    // ── 导出地图（用户 2026-10-03：「导入和导出都放页面顶上吧」） ──────────────

    /**
     * 先挑"导出哪几张"，再让用户选存到哪。
     *
     * ★ 为什么不是"导出本槽全部"：地图有三个来源（本槽 / 游戏自带 / 模组自带，见 {@link Maps}），
     *   后两者**在 APK 和模组包里面**，用户在文件管理器里根本看不见它们 ——
     *   只导本槽的话，"把游戏自带那张图拿出来"这件事永远做不到。
     *   所以这里列**全部**地图，勾哪张导哪张。
     *
     * ★ 第 127 轮：这张列表**改成多选**（勾选 + 「导出（N 份）」）——
     *   勾 1 份仍走**单份那条路**（原文件名 + `REQ_MAP_EXPORT`），勾 ≥2 份打成**一个 zip**。
     *   同一条判据贯穿四个内容页，见 {@link BatchIo} 的类注释。
     */
    private void promptExportMap() {
        if (mItems == null || mItems.isEmpty()) {
            Toast.makeText(this, R.string.maps_export_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Maps.Item> items = mItems;
        BatchIo.pickMulti(this, R.string.maps_export,
                Trans.get(MapsActivity.this, R.string.batch_export_head_fmt, items.size()),
                mTitles, mSubs, mThumbs, null, R.string.batch_export_btn_fmt,
                R.string.pick_share_toggle, new BatchIo.OnPickShare() {
                    @Override public void onPick(List<Integer> idx, boolean share, CardStack card) {
                        if (idx.size() == 1) {
                            // ★ 文件名用**原始名**：`0.msav` 这种就是游戏里认的名字，
                            //   "安全化"成 ASCII 之后用户在文件管理器里认不出来（同存档导出那条纪律）
                            Maps.Item it = items.get(idx.get(0).intValue());
                            if (share) {
                                exportItemShared(it);
                                return;
                            }
                            sExportItem = it;
                            Pending.put(MapsActivity.this, PEND_EXPORT, itemJson(it));
                            startActivityForResult(Intent.createChooser(
                                    Exporter.createDoc(it.name(), "application/octet-stream"),
                                    Trans.get(MapsActivity.this, R.string.chooser_export)),
                                    REQ_MAP_EXPORT);
                            return;
                        }
                        // ★ 多份 = 一个 zip（外层 zip 里每一条 = 单份导出时用户会拿到的那个文件）
                        List<Exporter.Src> srcs = new java.util.ArrayList<>();
                        for (Integer i : idx) {
                            Maps.Item it = items.get(i.intValue());
                            if (it.file != null) {
                                srcs.add(Exporter.Src.ofFile(it.file, it.name()));
                            } else if (it.container != null && it.entry != null) {
                                srcs.add(Exporter.Src.ofEntry(it.container, it.entry, it.name()));
                            }
                        }
                        if (share) {
                            BatchIo.startShare(card, MapsActivity.this, srcs,
                                    BatchIo.suggestZipName("maps"));
                        } else {
                            BatchIo.startExport(card, MapsActivity.this, srcs,
                                    BatchIo.suggestZipName("maps"));
                        }
                    }
                });
    }

    /**
     * 批量删除：勾几份 ⇒ 一次性挪进中转站（2026-10-11 第 129 轮，用户「批量删除」）。
     *
     * <p>★ 与单份删除（{@link MapDetailActivity}）**同一条门禁、同一个助手** ——
     * 游戏在跑就挡住（那道判据在数据层也有一份）、旧件一律走 `Trash.move` 进中转站。
     * 批量**不是**另一套删除，只是"多勾几份"。
     *
     * <p>★ 三个数分开报（{@link BatchIo.DelReport}）：挪走的 · 没挪动的（点名）·
     * **本来就不该删的**（住在游戏 APK / 模组包里、或不在 `<槽>/maps` 下的）——
     * 最后那一类**动手前**就告诉用户，否则他会以为"勾了 5 份删了 3 份是坏了"。
     */
    private void promptDeleteMaps() {
        if (mItems == null || mItems.isEmpty()) {
            Toast.makeText(this, R.string.maps_export_none, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Data.gameAlive(this)) {
            alert(Trans.get(this, R.string.game_busy_title),
                    Trans.get(this, R.string.map_delete_busy_msg));
            return;
        }
        final List<Maps.Item> items = mItems;
        BatchIo.pickMulti(this, R.string.batch_del_row,
                Trans.get(MapsActivity.this, R.string.batch_del_head_fmt, items.size()),
                mTitles, mSubs, mThumbs, null, R.string.batch_del_btn_fmt, new BatchIo.OnPick() {
                    @Override public void onPick(List<Integer> idx, CardStack card) {
                        final File kindDir = new File(Data.dirOf(MapsActivity.this, mSlot), "maps");
                        final List<File> files = new java.util.ArrayList<>();
                        for (Integer i : idx) files.add(items.get(i.intValue()).file);
                        int can = 0, cant = 0;
                        for (File f : files) {
                            if (BatchIo.deletable(f, kindDir)) can++; else cant++;
                        }
                        if (can == 0) {
                            card.dismiss();
                            alert(Trans.get(MapsActivity.this, R.string.batch_del_row),
                                    Trans.get(MapsActivity.this, R.string.batch_del_skipped_fmt, cant));
                            return;
                        }
                        String msg = Trans.get(MapsActivity.this, R.string.batch_del_msg_fmt, can);
                        if (cant > 0) {
                            msg = msg + "\n\n"
                                    + Trans.get(MapsActivity.this, R.string.batch_del_skipped_fmt, cant);
                        }
                        card.replace(Trans.get(MapsActivity.this, R.string.batch_del_title), msg,
                                new CardStack.Btn(Trans.get(MapsActivity.this, R.string.cancel), new Runnable() {
                                    @Override public void run() { card.dismiss(); }
                                }),
                                new CardStack.Btn(Trans.get(MapsActivity.this, R.string.batch_del_ok), new Runnable() {
                                    @Override public void run() {
                                                BatchIo.DelReport rep = BatchIo.deleteAll(
                                                        MapsActivity.this, files, kindDir,
                                                        MapFiles.trashDirOf(MapsActivity.this),
                                                        new BatchIo.Deleter() {
                                                            @Override public File delete(
                                                                    android.content.Context c, File f,
                                                                    File trashDir) {
                                                                return MapFiles.deleteToTrash(c, kindDir,
                                                                        f, trashDir);
                                                            }
                                                        });
                                                refreshList();   // 图上没了 ⇒ 重新清点（不重建页面）
                                                if (rep.failed == 0) {
                                                    Toast.makeText(MapsActivity.this,
                                                            Trans.get(MapsActivity.this,
                                                                    R.string.batch_del_done_fmt, rep.moved),
                                                            Toast.LENGTH_LONG).show();
                                                } else {
                                                    alert(Trans.get(MapsActivity.this, R.string.batch_del_row),
                                                            Trans.get(MapsActivity.this, R.string.batch_del_done_fmt, rep.moved)
                                                                    + "\n"
                                                                    + Trans.get(MapsActivity.this, R.string.batch_del_none_fmt, rep.failed));
                                                }
                                    }
                                }));
                    }
                });
    }

    /**
     * 单份地图的**分享**出口（第 156 轮）：不弹 SAF，落 `cache/share/` 再交给系统面板。
     *
     * <p>文件名与内容跟 {@link #exportItem} 那条**逐字相同**（同一个原始名、同一段流式拷贝），
     * 只是目的地不同 —— 两条路共用 {@link Exporter#copyTo} / {@link Exporter#copyEntry}，
     * 别再写第三份"怎么把这张图弄出来"。</p>
     *
     * <p>⚠️ 失败就明说失败，**不许**悄悄退回"保存到文件"（用户勾的是分享）。</p>
     */
    private void exportItemShared(final Maps.Item it) {
        // ★ 第 270 轮：进度 → 分享 都在卡片里
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.maps_export),
                Trans.get(this, R.string.maps_export_working));
        new Thread(new Runnable() {
            @Override public void run() {
                Uri uri = null;
                String err = null;
                try {
                    uri = ShareOut.stage(MapsActivity.this, it.name(), new ShareOut.Body() {
                        @Override public void write(OutputStream out) throws IOException {
                            if (it.file != null && it.file.isFile()) {
                                Exporter.copyTo(it.file, out);
                            } else if (it.container != null && it.entry != null) {
                                Exporter.copyEntry(MapsActivity.this, it.container, it.entry, out);
                            } else {
                                throw new IOException(Trans.get(MapsActivity.this,
                                        R.string.maps_export_nosrc));
                            }
                        }
                    });
                } catch (Throwable t) {
                    err = Util.ioReason(MapsActivity.this, t,
                            Trans.get(MapsActivity.this, R.string.maps_export_nosrc));
                    android.util.Log.w("MDTLauncher", "map share failed", t);
                }
                final Uri fu = uri;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(MapsActivity.this)) return;
                        if (fe != null) {
                            stack.replace(Trans.get(MapsActivity.this, R.string.export_failed), fe,
                                    closeBtn(stack));
                            return;
                        }
                        stack.dismiss();          // 交给系统分享面板
                        ShareOut.launch(MapsActivity.this, fu);
                    }
                });
            }
        }, "map-share").start();
    }

    // ── 第 177 轮（杀后台 P0）：SAF 待办的磁盘表示 ──────────────────────────

    private static JSONObject slotJson(String slot) {
        JSONObject o = new JSONObject();
        try {
            o.put("slot", slot == null ? "" : slot);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 只记"这一张从哪来"；`meta` 等派生量不落盘（导出只需要源和名字）。 */
    private static JSONObject itemJson(Maps.Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("from", it.from);
            o.put("source", it.source == null ? "" : it.source);
            o.put("where", it.where == null ? "" : it.where);
            o.put("bytes", it.bytes);
            if (it.file != null) o.put("file", it.file.getAbsolutePath());
            if (it.container != null && it.entry != null) {
                o.put("container", it.container.getAbsolutePath());
                o.put("entry", it.entry);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static Maps.Item itemFromJson(JSONObject o) {
        if (o == null) return null;
        String file = o.optString("file", "");
        String container = o.optString("container", "");
        String entry = o.optString("entry", "");
        if (file.isEmpty() && (container.isEmpty() || entry.isEmpty())) return null;
        Maps.Item it = new Maps.Item();
        it.from = o.optInt("from", Maps.FROM_SLOT);
        it.source = o.optString("source", "");
        it.where = o.optString("where", "");
        it.bytes = o.optLong("bytes", 0);
        if (!file.isEmpty()) it.file = new File(file);
        if (!container.isEmpty() && !entry.isEmpty()) {
            it.container = new File(container);
            it.entry = entry;
        }
        return it;
    }

    /**
     * 把选中的那张图写进 SAF 目标。     * 两条路：本槽（以及目录形态模组）里的是**真文件**；游戏 APK / 模组包里的要**从 zip 条目流式拷**。
     */
    private void exportItem(final Maps.Item it, final Uri uri) {
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.maps_export),
                Trans.get(this, R.string.maps_export_working));
        new Thread(new Runnable() {
            @Override public void run() {
                final long n;
                try {
                    if (it.file != null && it.file.isFile()) {
                        n = Exporter.writeFile(MapsActivity.this, uri, it.file);
                    } else if (it.container != null && it.entry != null) {
                        n = Exporter.writeEntry(MapsActivity.this, uri, it.container, it.entry);
                    } else {
                        throw new java.io.IOException(Trans.get(MapsActivity.this, R.string.maps_export_nosrc));
                    }
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // 异常消息多半就是我们自己抛的那几句人话（见 Exporter）⇒ `ioReason`
                            // **认识的系统 errno 才翻**、我们的文案原样透传；没有 message 时也不把类名甩给用户
                            stack.replace(Trans.get(MapsActivity.this, R.string.export_failed),
                                    Util.ioReason(MapsActivity.this, t,
                                            Trans.get(MapsActivity.this, R.string.maps_export_nosrc)),
                                    closeBtn(stack));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        Toast.makeText(MapsActivity.this,
                                Trans.get(MapsActivity.this, R.string.map_export_ok_fmt, it.name(),
                                        Util.formatSize(n)), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "map-export").start();
    }

    private void importFile(final Uri uri, final String displayName, final String slot,
                            final boolean overwrite) {
        importFile(null, uri, displayName, slot, overwrite);
    }

    /** 卡片版（第 270 轮）：进度 → 同名确认 / 命名页 / 结果 全在同一张卡里。 */
    private void importFile(final CardStack card, final Uri uri, final String displayName,
                            final String slot, final boolean overwrite) {
        final CardStack stack = (card != null) ? card : new CardStack(this);
        stack.replace(Trans.get(this, R.string.maps_import),
                Trans.get(this, R.string.maps_scanning));
        new Thread(new Runnable() {
            @Override public void run() {
                final MapFiles.Result r;
                java.io.InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    File dir = new File(Data.dirOf(MapsActivity.this, slot), "maps");
                    r = MapFiles.importMap(MapsActivity.this, dir, MapFiles.safeName(displayName), in,
                            overwrite, MapFiles.trashDirOf(MapsActivity.this));
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // ★ 2026-10-15：这里原来是 `t.toString()`（全工程最后一处把
                            //   `java.io.FileNotFoundException: /data/user/0/…` 当弹窗正文的地方）
                            //   ⇒ 与同页导出那条一致，走 `Util.ioReason`（认识的 errno 才翻，
                            //   我们的中文原样透传，原文进日志）。
                            android.util.Log.w("MDTLauncher", "map import failed", t);
                            stack.replace(Trans.get(MapsActivity.this, R.string.maps_import),
                                    Util.ioReason(MapsActivity.this, t,
                                            Trans.get(MapsActivity.this, R.string.map_import_read_failed)),
                                    closeBtn(stack));
                        }
                    });
                    return;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // ★ 下面这些页都属于可能已被转屏销毁的实例 ⇒ 先判死（见 {@link Util#dead}）。
                        if (Util.dead(MapsActivity.this)) return;
                        // ★★ 「这份其实是存档」⇒ 先问名字（2026-10-05，REF §72）。
                        //   判据字段 = r.saveNoName（meta 里没有 `name`，与游戏自己的
                        //   `SaveMeta.isMap()` 同源）；此时 `.part` 已经留在槽里，
                        //   问完名字再 commitSaveAsMap，**不必重下那份文件**。
                        if (r.saveNoName) {
                            // ⚠️ 最后那个 true = "这是我们自己的待办 `.part`"（取消时可以删它）
                            pushSaveAsMapName(stack, r.part, r.meta, Msav.baseName(r.base), true);
                            return;
                        }
                        if (r.ok) {
                            stack.dismiss();
                            Toast.makeText(MapsActivity.this,
                                    Trans.get(MapsActivity.this, R.string.map_import_ok_fmt, r.finalName),
                                    Toast.LENGTH_SHORT).show();
                            refreshList();          // ★ 与转图那条路统一：只重扫列表，不重建页面
                            return;
                        }
                        // ★ 判"要不要弹同名替换框"**只认 r.nameTaken**（MapFiles 给的权威标志），
                        //   不许去看 r.error 的字面内容 —— 那是给人看的话，一改措辞/一翻译就判错
                        //   （2026-10-04 i18n P0.1；门禁规则 SRC-01）。
                        if (!overwrite && r.nameTaken) {
                            // ★ 同一张卡里换成确认页（原来"关进度框 → 开确认框"）
                            stack.replace(Trans.get(MapsActivity.this, R.string.map_overwrite_title),
                                    Trans.get(MapsActivity.this, R.string.map_overwrite_msg_fmt,
                                            MapFiles.safeName(displayName)),
                                    new CardStack.Btn(Trans.get(MapsActivity.this, R.string.cancel),
                                            new Runnable() {
                                                @Override public void run() { stack.dismiss(); }
                                            }),
                                    new CardStack.Btn(Trans.get(MapsActivity.this, R.string.map_overwrite_ok),
                                            new Runnable() {
                                                @Override public void run() {
                                                    importFile(stack, uri, displayName, slot, true);
                                                }
                                            }));
                            return;
                        }
                        stack.replace(Trans.get(MapsActivity.this, R.string.maps_import),
                                Trans.get(MapsActivity.this, R.string.map_import_fail_fmt, r.error),
                                closeBtn(stack));
                    }
                });
            }
        }, "map-import").start();
    }

    // ── 导入地图：一行两路（第 155 轮） ─────────────────────────────────────

    /**
     * 「导入地图…」的二选一：**从文件** / **从本槽存档**（同一件事的两条路）。
     *
     * <p>★ 门禁放在弹窗**之前**：两条路都是往槽的 `maps/` 里写（旧图会进中转站），
     * 而游戏在跑时写进去会被覆盖 —— 先拦，别让用户答完二选一再说"不行"。</p>
     */
    private void promptImportSource() {
        // ★ 2026-10-04：先判游戏在不在跑（拦在 SAF 选择器 / 二选一之前，别让用户白选一次）。
        //   导入是往槽的 maps/ 里**写**，而工程里凡"写槽"的路径都有这道门禁
        //   （导入存档/整槽、备份、恢复、改设置）；地图与模组导入原来漏了。
        if (Data.gameAlive(this)) {
            alert(Trans.get(this, R.string.game_busy_title),
                    Trans.get(this, R.string.maps_import_busy_msg));
            return;
        }
        String[] items = new String[]{
                Trans.get(this, R.string.maps_import_from_file),
                Trans.get(this, R.string.maps_from_save)};
        // ★ 第 263 轮：二选一 → 存档列表 → 命名页，三段原来各是一张窗；现在同一张卡里推页
        final CardStack stack = new CardStack(this);
        stack.root(Trans.get(this, R.string.maps_import_pick),
                CardStack.list(this, items, new CardStack.ItemClick() {
                    @Override public void onClick(int which) {
                        if (which == 0) {
                            stack.dismiss();      // 接着走系统文件选择器（那是另一个 Activity）
                            importFromFile();
                        } else {
                            pickSlotSave(stack);
                        }
                    }
                }),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
        stack.show();
    }

    /**
     * 从系统文件选择器导入。
     *
     * ★ 第 127 轮：**可多选**（`EXTRA_ALLOW_MULTIPLE`）—— 选 1 份 = 老样子（含"这其实是存档"
     *   的命名框），选 ≥2 份或选了一个 zip = 批量（见 onActivityResult / BatchIo.runImport）。
     */
    private void importFromFile() {
        sMapTarget = mSlot;
        Pending.put(this, PEND_IMPORT, slotJson(mSlot));     // 第 177 轮：磁盘那半
        BatchIo.pickFiles(MapsActivity.this, REQ_MAP, R.string.maps_import);
    }

    // ── 「存档视为地图」（2026-10-05，REF §72） ─────────────────────────────

    /**
     * 「**本槽存档 → 地图**」：列本槽 `saves/` 里的存档，挑一份转成地图（REF §72.10）。
     *
     * ★ 与「导入地图…」那条路的差别**只有"从哪儿拿文件"**：不问系统文件选择器
     *   （SAF 够不到 `Android/data/…`，而存档就在那儿）⇒ 用户不必先导出到 Download 再选回来。
     *   后面走的是**同一个** {@link MapFiles#commitSaveAsMap} 与同一个命名框。
     * ★ 列表写法照抄 {@link SlotIo} 导出存档那个弹窗：标题 = 文件名、副标题先给体积，
     *   元数据**后台渐进填**（几十份存档不许在 UI 线程读完才弹窗）。
     */
    private void pickSlotSave() {
        pickSlotSave(null);
    }

    private void pickSlotSave(final CardStack parent) {
        final File dir = new File(Data.dirOf(this, mSlot), "saves");
        File[] all = dir.listFiles();
        final java.util.List<File> keep = new java.util.ArrayList<>();
        if (all != null) {
            for (File f : all) {
                // 判据：是文件、`.msav`、**不是写了一半的 `.part`**（与 SlotIo 的存档列表同口径）
                if (f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".msav")
                        && !f.getName().endsWith(".part")) {
                    keep.add(f);
                }
            }
        }
        if (keep.isEmpty()) {
            Toast.makeText(this, R.string.maps_from_save_none, Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Collections.sort(keep, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        final File[] fs = keep.toArray(new File[0]);
        final String[] titles = new String[fs.length];
        final String[] subs = new String[fs.length];
        for (int i = 0; i < fs.length; i++) {
            titles[i] = fs[i].getName();
            subs[i] = Util.formatSize(fs[i].length());
        }
        View box = getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        TextView head = (TextView) box.findViewById(R.id.msav_head);
        head.setVisibility(View.VISIBLE);
        head.setText(Trans.get(MapsActivity.this, R.string.maps_from_save_head_fmt, fs.length));
        android.widget.ListView lv = (android.widget.ListView) box.findViewById(R.id.msav_list);
        final MsavListAdapter adapter = new MsavListAdapter(this, titles, subs);
        lv.setAdapter(adapter);
        // ★ 第 262 轮：列表与"命名页"原来是两个窗口（列表弹窗 → 命名弹窗），换窗时 dim 重播会闪；
        //   现在整条链在同一张卡里（列表 = 根页，命名 = push 一页）。
        final CardStack[] ref = new CardStack[1];
        final boolean[] alive = {true};
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= fs.length) return;
                MsavMeta m = MsavMeta.read(fs[pos], false);
                String base = Msav.baseName(fs[pos].getName());
                // ⚠️ 最后那个 false = "这不是我们的待办 .part，是用户的存档原件" ⇒ 谁都不许删它
                pushSaveAsMapName(ref[0], fs[pos], m, base, false);
            }
        });
        ref[0] = CardStack.show(parent, this, Trans.get(this, R.string.maps_from_save_pick_title),
                CardStack.wrap(this, box),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() {
                        alive[0] = false;
                        ref[0].dismiss();
                    }
                }));
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < fs.length && alive[0]; i++) {
                    final int idx = i;
                    final MsavMeta m = MsavMeta.read(fs[idx], false);
                    final String line = MsavText.shortLine(MapsActivity.this, m, true);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!alive[0]) return;
                            String size = Util.formatSize(fs[idx].length());
                            adapter.setSub(idx, line.isEmpty() ? size : (size + " · " + line));
                            adapter.refreshSub(lv, idx);
                        }
                    });
                }
            }
        }, "s2m-meta").start();
    }

    /**
     * 选到的 `.msav` 里**没有 `name`** ⇒ 它其实是一份**存档**（判据与游戏同源：
     * `SaveMeta.isMap()` = `tags.containsKey("name")`）：先问名字，再只改元数据区变成地图。
     *
     * ★ 预填 = 存档里的 `mapname`（语料 312/312 都非空），用户可改。
     * ★ 这个名字会**写进文件**（游戏的地图列表与编辑器显示的就是它），
     *   文件名另外消毒 —— 所以提示语与"存档命名框"**不是同一句**（那个讲的是存档列表）。
     * ★ `nocores=true` 时多显示一行警告：编辑器能开，直接当普通图开可能立刻结束（REF §72.9）。
     *
     * @param src         源文件：**待办 `.part`**（SAF 那条）或**用户的存档原件**（本槽那条）
     * @param fallbackBase 名字兜底（`mapname` 为空时用），已经去掉 `.msav`
     * @param pendingPart `src` 是不是我们自己的待办 `.part` —— 决定"取消时能不能删它"
     *                    （见 {@link MapFiles#isPendingPart}；用户的存档**一个字都不能删**）
     */
    private void showSaveAsMapNameDialog(final File src, final MsavMeta meta,
                                         final String fallbackBase, final boolean pendingPart) {
        pushSaveAsMapName(null, src, meta, fallbackBase, pendingPart);
    }

    /** 同 {@link #showSaveAsMapNameDialog}，但**推到给定卡片里**（有卡片就不另开窗，dim 不重播）。 */
    private void pushSaveAsMapName(final CardStack parent, final File src, final MsavMeta meta,
                                   final String fallbackBase, final boolean pendingPart) {
        if (Util.dead(this)) return;
        if (src == null || !src.isFile()) {
            alert(Trans.get(MapsActivity.this, R.string.map_save_name_title), Trans.get(MapsActivity.this, R.string.mapfile_err_part_gone));
            return;
        }
        View form = getLayoutInflater().inflate(R.layout.dialog_map_name, null);
        final EditText name = (EditText) form.findViewById(R.id.map_save_name);
        String def = meta == null ? null : meta.get("mapname", null);
        if (def == null || def.trim().isEmpty()) def = fallbackBase;
        name.setText(def);
        name.setSelection(name.getText().length());
        if (meta != null && "true".equalsIgnoreCase(String.valueOf(meta.get("nocores", "")))) {
            form.findViewById(R.id.map_save_warn).setVisibility(View.VISIBLE);
        }
        // ★★ 「源图」那一行（2026-10-05 用户要求：「有可能会被改过名或不小心撞名，
        //   给个接口允许用户打开自行选择」）：默认按 `mapname` 自动匹配（判据与游戏 SaveMeta 同源），
        //   但**用户可以点开自己换** —— 图改过名 / 撞名时自动匹配会失手。
        final TextView srcRow = (TextView) form.findViewById(R.id.map_save_source);
        srcRow.setText(Trans.get(MapsActivity.this, R.string.map_save_source_finding));
        final Maps.Item[] chosen = {null};            // null = 不用源图
        final boolean[] resolved = {false};           // 还没解析完就点「导入」⇒ 退回"自动"
        final File mapsDir = new File(Data.dirOf(this, mSlot), "maps");
        final CardStack[] ref = new CardStack[1];
        srcRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pushSourcePicker(ref[0], srcRow, mapsDir, chosen, resolved);
            }
        });
        // ★ 自动匹配：**在已经扫描出来的三来源清单里找**（本槽 / 游戏自带 / 模组自带）。
        //   ⚠️ 只扫 `<槽>/maps/` 是不够的 —— **原版战役地图在 APK 里**
        //   （`assets/maps/<星球>/<区块键>.msav`）；而且战役存档的 `mapname` 是**当时语言的显示名**，
        //   得靠 `sectorPreset` 才对得上（用户 2026-10-05：「原版战役地图为什么识别不到啊」）。
        final String wantName = meta == null ? null : meta.get("mapname", null);
        final String wantSector = meta == null ? null : meta.get("sectorPreset", null);
        final List<Maps.Item> scanned = mItems;
        if (scanned != null) {
            applySourceChoice(srcRow, chosen, resolved, Maps.bySave(scanned, wantName, wantSector));
        } else {
            // 页面还没扫完（罕见）：退回"只找槽内文件"，且放后台，别卡住弹窗
            new Thread(new Runnable() {
                @Override public void run() {
                    final java.util.List<MapFiles.Candidate> hits = MapFiles.findSources(mapsDir, wantName);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(MapsActivity.this)) return;
                            java.util.List<Maps.Item> asItems = new java.util.ArrayList<>();
                            for (MapFiles.Candidate c : hits) {
                                Maps.Item it = new Maps.Item();
                                it.from = Maps.FROM_SLOT;
                                it.file = c.file;
                                it.meta = c.meta;
                                it.where = c.file.getAbsolutePath();
                                asItems.add(it);
                            }
                            applySourceChoice(srcRow, chosen, resolved, asItems);
                        }
                    });
                }
            }, "s2m-source").start();
        }
        // ★ 名字为空 / 非法时**不许关页**（旧版靠接管正按钮做到；卡片里就是"校验不过直接 return"）
        ref[0] = CardStack.show(parent, this, Trans.get(this, R.string.map_save_name_title),
                CardStack.wrap(this, form),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() {
                        if (pendingPart) MapFiles.discard(src);
                        ref[0].dismiss();
                    }
                }),
                new CardStack.Btn(Trans.get(this, R.string.import_btn), new Runnable() {
                    @Override public void run() {
                        String raw = name.getText().toString().trim();
                        String err = MapFiles.checkMapName(MapsActivity.this, raw);
                        if (err != null) {
                            Toast.makeText(MapsActivity.this, err, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // resolved=false（还在查找）⇒ 交给 MapFiles 自动匹配；否则用用户选的
                        // ★ 第 268 轮：**不关卡片**，进度页直接顶替这一页
                        commitSaveAsMap(ref[0], src, raw, mSlot, false, chosen[0], !resolved[0]);
                    }
                }));
    }

    /**
     * 把"自动匹配/用户选择"的结果落到那一行文字上（**一处判据**：自动匹配与手工选择共用）。
     *
     * @param hits 命中的源图（空 = 没找到；>1 = 撞名，明说有几张让用户自己选）
     */
    private void applySourceChoice(final TextView row, final Maps.Item[] chosen,
                                   final boolean[] resolved, List<Maps.Item> hits) {
        if (Util.dead(this)) return;
        resolved[0] = true;
        if (hits == null || hits.isEmpty()) {
            chosen[0] = null;
            row.setText(Trans.get(MapsActivity.this, R.string.map_save_source_missing));
            return;
        }
        chosen[0] = hits.get(0);
        row.setText(sourceRowText(hits.get(0), hits.size()));
    }

    /** 那一行「源图：…」的文字（选中一张之后也用它） */
    private CharSequence sourceRowText(Maps.Item it, int hits) {
        String nm = it == null ? "" : it.displayName();
        // 候选多张 ⇒ 明说有几张（**不写"同名"** —— 战役那条判据是"区块键 == 文件名"，
        // 命中多张时不一定同名）；游戏自带/模组自带的还要点明来源，否则用户不知道它在哪
        if (hits > 1) return Trans.get(MapsActivity.this, R.string.map_save_source_amb_fmt, nm, hits);
        if (it != null && it.from != Maps.FROM_SLOT) {
            return Trans.get(MapsActivity.this, R.string.map_save_source_pick_src_fmt, nm, it.sourceLabel(this));
        }
        return Trans.get(MapsActivity.this, R.string.map_save_source_pick_fmt, nm);
    }

    /**
     * 「源图」那一行点开后的选择框：**第一项 = 不使用源图**，其余 = **三个来源**的每一张
     * （本槽 `maps/` / 游戏自带（APK 的 `assets/maps/**`，含**战役区块图**）/ 模组自带）。
     *
     * ★ 为什么要有它（用户 2026-10-05）：「有可能会被改过名或不小心撞名，给个接口允许用户打开自行选择」
     *   ＋「**选择应该允许系统自带以及模组自带**」—— 自动匹配靠存档的 `mapname`，改名/撞名都会失手，
     *   而**战役地图压根不在槽里**，所以最终决定权给用户、范围给全。
     * ⚠️ 直接复用页面**已经扫描出来的清单**（`mItems`）—— 里面每张图的 meta 都读好了，
     *   选中之后就不用再打开一次 APK/模组包。
     */
    private void pushSourcePicker(final CardStack stack, final TextView row, final File mapsDir,
                                  final Maps.Item[] chosen, final boolean[] resolved) {
        if (Util.dead(this) || stack == null) return;
        final List<Maps.Item> all = new java.util.ArrayList<>();
        if (mItems != null) {
            all.addAll(mItems);
        } else {
            // 退化路径：还没扫完 ⇒ 至少给槽内文件（少见）
            for (MapFiles.Candidate c : MapFiles.listMaps(mapsDir)) {
                Maps.Item it = new Maps.Item();
                it.from = Maps.FROM_SLOT;
                it.file = c.file;
                it.meta = c.meta;
                it.bytes = c.file.length();
                it.where = c.file.getAbsolutePath();
                all.add(it);
            }
        }
        int nSlot = 0, nGame = 0, nMod = 0;
        for (Maps.Item it : all) {
            if (it.from == Maps.FROM_GAME) nGame++;
            else if (it.from == Maps.FROM_MOD) nMod++;
            else nSlot++;
        }
        String[] titles = new String[all.size() + 1];
        String[] subs = new String[all.size() + 1];
        titles[0] = Trans.get(MapsActivity.this, R.string.map_save_source_off_item);
        subs[0] = "";
        for (int i = 0; i < all.size(); i++) {
            Maps.Item it = all.get(i);
            titles[i + 1] = it.displayName();
            // 副标题：来源 + 文件名/条目名（撞名时靠它区分）
            subs[i + 1] = it.sourceLabel(this) + " · " + it.name();
        }
        // ★ 第 262 轮：这张"源图"清单原来是命名框上再叠一个弹窗 ⇒ 换窗会闪。
        //   现在推到同一张卡里（两行列表，选完 pop 回命名页）。
        // ⚠️ 表头那句「本槽 N / 游戏自带 N / 模组自带 N」放不下了 —— 每一行的副标题本来就写着来源。
        stack.push(Trans.get(this, R.string.map_save_source_title),
                CardStack.list2(this, titles, subs, new CardStack.ItemClick() {
                    @Override public void onClick(int pos) {
                        if (Util.dead(MapsActivity.this)) return;
                        resolved[0] = true;
                        if (pos == 0) {
                            chosen[0] = null;
                            row.setText(Trans.get(MapsActivity.this, R.string.map_save_source_off));
                        } else {
                            Maps.Item it = all.get(pos - 1);
                            chosen[0] = it;
                            row.setText(sourceRowText(it, 1));
                        }
                        stack.pop();
                    }
                }));
    }

    /**
     * 第二步：后台改写 + 落位（{@link MapFiles#commitSaveAsMap}）。
     *
     * ★ 同名**先问**（判据是 `Result.nameTaken` 字段，不是文案子串 —— 门禁 `SRC-01`）。
     * ★ 任何一步失败都让源文件留着（`MapFiles` 那边不硬删），用户重试还有据可依。
     * ★ `src` 可能是**用户的存档原件**（本槽那条路）⇒ 取消时的 `discard` 只删 `.part`
     *   （判据收在 {@link MapFiles#isPendingPart} 一处）。
     * ★ `sourceItem` = 用哪张图补齐缺的元数据（**用户可以在「源图」那一行自己挑**，可以是
     *   **本槽文件 / 游戏自带（APK）/ 模组自带**里的任意一张；null = 不用源图）；
     *   `autoSource` = 用户还没挑过（还在自动查找 / 直接点了导入）⇒ 交给 `MapFiles` 自己按 `mapname` 匹配。
     */
    private void commitSaveAsMap(final File src, final String metaName, final String slot,
                                 final boolean overwrite, final Maps.Item sourceItem,
                                 final boolean autoSource) {
        commitSaveAsMap(null, src, metaName, slot, overwrite, sourceItem, autoSource);
    }

    /**
     * 卡片版（第 268 轮）：进度 → 同名确认 → 结果 全在同一张卡里 `replace`。
     * 没有卡片就自己开一张（dev 口 / 旧调用点）。
     */
    private void commitSaveAsMap(final CardStack card, final File src, final String metaName,
                                 final String slot, final boolean overwrite,
                                 final Maps.Item sourceItem, final boolean autoSource) {
        final CardStack stack = (card != null) ? card : new CardStack(this);
        stack.replace(Trans.get(this, R.string.map_save_name_title),
                Trans.get(this, R.string.maps_scanning));
        new Thread(new Runnable() {
            @Override public void run() {
                final MapFiles.Result r;
                try {
                    File dir = new File(Data.dirOf(MapsActivity.this, slot), "maps");
                    File trash = MapFiles.trashDirOf(MapsActivity.this);
                    if (autoSource) {
                        r = MapFiles.commitSaveAsMap(MapsActivity.this, dir, src, metaName, overwrite, trash);
                    } else {
                        // ★ 源图的 meta **已经在扫描时读好了**（APK/模组包里的条目也一样）⇒ 直接递 tags，
                        //   不再打开一次容器（见 Maps.Item.meta / MapFiles 那个收 tags 的重载）
                        java.util.Map<String, String> tags = null;
                        String label = null;
                        if (sourceItem != null && sourceItem.meta != null && sourceItem.meta.ok) {
                            tags = sourceItem.meta.tags;
                            label = sourceItem.name();
                            if (sourceItem.from != Maps.FROM_SLOT) {
                                label = label + "(" + sourceItem.sourceLabel(MapsActivity.this) + ")";
                            }
                        }
                        r = MapFiles.commitSaveAsMap(MapsActivity.this, dir, src, metaName, overwrite, trash,
                                tags, label);
                    }
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // ★ 2026-10-15：原来是 `t.toString()`（异常原文 + 绝对路径当弹窗正文）
                            android.util.Log.w("MDTLauncher", "save-as-map failed", t);
                            stack.replace(Trans.get(MapsActivity.this, R.string.map_save_name_title),
                                    Util.ioReason(MapsActivity.this, t,
                                            Trans.get(MapsActivity.this, R.string.map_save_failed_why)),
                                    closeBtn(stack));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // ★ 这些页都属于可能已被转屏销毁的实例 ⇒ 先判死
                        if (Util.dead(MapsActivity.this)) return;
                        if (r.ok) {
                            showSaveAsMapResult(stack, r, metaName);
                            return;
                        }
                        if (!overwrite && r.nameTaken) {
                            stack.replace(Trans.get(MapsActivity.this, R.string.map_overwrite_title),
                                    Trans.get(MapsActivity.this, R.string.map_overwrite_msg_fmt,
                                            MapFiles.safeName(metaName)),
                                    new CardStack.Btn(Trans.get(MapsActivity.this, R.string.cancel),
                                            new Runnable() {
                                                @Override public void run() {
                                                    MapFiles.discard(src);
                                                    stack.dismiss();
                                                }
                                            }),
                                    new CardStack.Btn(Trans.get(MapsActivity.this, R.string.map_overwrite_ok),
                                            new Runnable() {
                                                @Override public void run() {
                                                    commitSaveAsMap(stack, src, metaName, slot, true,
                                                            sourceItem, autoSource);
                                                }
                                            }));
                            return;
                        }
                        // 失败：用户文案走 MsavText（内核只给「码 + 参数」）
                        String why = r.conv != null
                                ? MsavText.convertReason(MapsActivity.this, r.conv)
                                : String.valueOf(r.error);
                        stack.replace(Trans.get(MapsActivity.this, R.string.map_save_name_title),
                                Trans.get(MapsActivity.this, R.string.map_import_fail_fmt, why),
                                closeBtn(stack));
                    }
                });
            }
        }, "map-save2map").start();
    }

    /** 卡片结果页的「关闭」（唯一实现）。 */
    private CardStack.Btn closeBtn(final CardStack stack) {
        return new CardStack.Btn(Trans.get(this, R.string.close), new Runnable() {
            @Override public void run() { stack.dismiss(); }
        });
    }

    /** 成功：第一层只说"转好了"，依据（删了哪些键 / 体积 / 源存档没动）走「技术细节」第二层 */
    private void showSaveAsMapResult(final CardStack stack, final MapFiles.Result r, String metaName) {
        if (Util.dead(this)) return;
        // ★ **先刷新列表、再出结果页**（用户要求"导入之后要自动刷新页面"）——
        //   卡片还开着的时候，后面的表头/列表就已经是新数据了；「关闭」只负责收掉它。
        refreshList();
        String inName = r.meta == null ? null : r.meta.get("name", null);
        String shown = inName == null ? metaName : Mods.stripColors(inName);
        // ★ 依据不能删（F4①d）：原始报告留在第二层
        CardStack.Btn detail = r.conv == null ? null : new CardStack.Btn(
                Trans.get(this, R.string.map_save_detail), new Runnable() {
            @Override public void run() {
                stack.push(Trans.get(MapsActivity.this, R.string.map_save_detail_title),
                        CardStack.text(MapsActivity.this, r.conv.report()));
            }
        });
        stack.replace(Trans.get(this, R.string.map_save_name_title),
                Trans.get(MapsActivity.this, R.string.map_save_ok_fmt, shown)
                        + Trans.get(MapsActivity.this, R.string.map_save_ok_note),
                detail, closeBtn(stack));
    }

    /**
     * 从 SAF 的 Uri 问出显示名 —— 第 127 轮起**搬进了 {@link BatchIo#displayNameOf}**：
     * 多选批量走的是同一件事（多选时 `data.getClipData()` 的每一条都要问一次名字），
     * 留两份实现迟早会在"某一家文件管理器只给一部分 URI 名字"时表现不一致。
     */

    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"：
     *  地图导入/导出是后台任务收尾时弹的（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
        if (CardStack.leaf(title, msg)) return;
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
