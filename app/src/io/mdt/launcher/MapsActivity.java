package io.mdt.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

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
    private ListView mList;
    private MsavListAdapter mAdapter;
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
    /** 列表三件套（导出对话框直接复用这一批文本与缩略图，不必重扫、重渲染） */
    private String[] mTitles;
    private String[] mSubs;
    private android.graphics.Bitmap[] mThumbs;

    // ── 搜索 / 排序 / 只看有问题的（2026-10-07 第 122 轮；**纯前端**，一个文件都不碰）──
    /** 转屏要恢复的三个状态 —— 同模组页那条教训：不存就会"一转屏用户打的字没了"，且日志里什么都没有 */
    private static final String STATE_QUERY = "mdt-maps-query";
    private static final String STATE_SORT = "mdt-maps-sort";
    private static final String STATE_ONLY = "mdt-maps-only";
    private String mQuery = "";
    private int mSort = ListQuery.SORT_NAME;
    private boolean mOnlyProblems;
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

        Util.bindAction(root, R.id.row_maps_import, R.drawable.ic_download,
                R.string.maps_import, R.string.slot_page_maps_sub, new Runnable() {
                    @Override public void run() {
                        // ★ 2026-10-04：**先判游戏在不在跑**（拦在 SAF 选择器之前，别让用户白选一次）。
                        //   导入是往槽的 maps/ 里**写**（还会把同名旧图挪去中转站），
                        //   而工程里凡"写槽"的路径都有这道门禁（导入存档/整槽、备份、恢复、改设置）；
                        //   地图与模组导入原来漏了 —— 游戏本局写的图会被覆盖或被挪走。
                        if (Data.gameAlive(MapsActivity.this)) {
                            alert(Trans.get(MapsActivity.this, R.string.game_busy_title),
                                    Trans.get(MapsActivity.this, R.string.maps_import_busy_msg));
                            return;
                        }
                        sMapTarget = mSlot;
                        // ★ 第 127 轮：**可多选**（`EXTRA_ALLOW_MULTIPLE`）——
                        //   选 1 份 = 老样子（含"这其实是存档"的命名框），选 ≥2 份或选了一个
                        //   zip = 批量（见 onActivityResult / BatchIo.runImport）。
                        BatchIo.pickFiles(MapsActivity.this, REQ_MAP, R.string.maps_import);
                    }
                });

        // ★ 2026-10-05「本槽存档 → 地图」（REF §72.10）：**不过系统文件选择器**。
        //   手机上的存档就在本槽 `saves/` 里，而 SAF 够不到 `Android/data/…`
        //   ⇒ 走「导入地图…」得先导出到 Download 再选回来（用户："我就是不希望手动导入"）。
        //   与导入同一条门禁：这是往槽的 `maps/` 里写（旧图会进中转站）。
        Util.bindAction(root, R.id.row_maps_from_save, R.drawable.ic_save,
                R.string.maps_from_save, R.string.maps_from_save_sub, new Runnable() {
                    @Override public void run() {
                        if (Data.gameAlive(MapsActivity.this)) {
                            alert(Trans.get(MapsActivity.this, R.string.game_busy_title),
                                    Trans.get(MapsActivity.this, R.string.maps_import_busy_msg));
                            return;
                        }
                        pickSlotSave();
                    }
                });

        // ★ 导出与导入成对放在**页面顶上**（用户 2026-10-03 定案）
        Util.bindAction(root, R.id.row_maps_export, R.drawable.ic_upload,
                R.string.maps_export, R.string.maps_export_sub, new Runnable() {
                    @Override public void run() {
                        promptExportMap();
                    }
                });

        // ★ 2026-10-07（第 122 轮）：「筛选与排序」——与模组页同一行、同一个对话框形状
        //   （用户：「把搜索 / 排序推广到地图 / 蓝图 / 存档列表」）。
        //   ⚠️ 对话框里那几条词（按名称 / 有问题的在前 / 按大小 / 只看有问题的 / 清空筛选）
        //   **复用模组页的资源**：四个列表页说的是同一件事，各写一份只会让用户语言包里
        //   多出四条一模一样的翻译 —— 而"模组"两个字并不在那几条词里，不会串味。
        Util.bindAction(root, R.id.row_maps_filter, R.drawable.ic_settings,
                R.string.mods_filter_title, R.string.maps_act_filter_sub, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });

        final ListView lv = (ListView) root.findViewById(R.id.maps_list);
        mList = lv;
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (mShown != null && pos >= 0 && pos < mShown.size()) showDetail(mShown.get(pos));
            }
        });

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
    private void scan(final ListView lv) {
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
        mShown = Maps.filterAndSort(mItems, mQuery, mSort, mOnlyProblems);
        boolean filtering = ListQuery.filtering(mQuery, mSort, mOnlyProblems, ListQuery.SORT_NAME);
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
        mList.setAdapter(mAdapter);

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
     */
    private void pickFilter() {
        final String tick = "✓ ";
        final String[] items = {
                (mSort == ListQuery.SORT_NAME ? tick : "")
                        + Trans.get(MapsActivity.this, R.string.mods_filter_sort_name),
                (mSort == ListQuery.SORT_PROBLEM ? tick : "")
                        + Trans.get(MapsActivity.this, R.string.mods_filter_sort_state),
                (mSort == ListQuery.SORT_SIZE ? tick : "")
                        + Trans.get(MapsActivity.this, R.string.mods_filter_sort_size),
                (mOnlyProblems ? tick : "")
                        + Trans.get(MapsActivity.this, R.string.mods_filter_only),
                Trans.get(MapsActivity.this, R.string.mods_filter_reset)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_filter_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            mSort = ListQuery.SORT_NAME;
                        } else if (which == 1) {
                            mSort = ListQuery.SORT_PROBLEM;
                        } else if (which == 2) {
                            mSort = ListQuery.SORT_SIZE;
                        } else if (which == 3) {
                            mOnlyProblems = !mOnlyProblems;
                        } else {
                            mSort = ListQuery.SORT_NAME;
                            mOnlyProblems = false;
                            mQuery = "";
                            // 清框会经 TextWatcher 走一趟 rebuildList，这里再走一趟也无害（同模组页）
                            if (mSearch != null) mSearch.setText("");
                        }
                        rebuildList();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
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
            final Maps.Item it = sExportItem;
            sExportItem = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || it == null) return;
            exportItem(it, data.getData());
            return;
        }
        if (requestCode != REQ_MAP) return;
        // ★ 与存档那条同一纪律：无论成败**先清目标槽**
        final String slot = sMapTarget;
        sMapTarget = null;
        if (resultCode != RESULT_OK || data == null || slot == null) return;
        // ★ 第 127 轮：一批（多选 / 一个 zip）走批量；**一份普通文件**仍旧走单份那条路
        //   （那条路有「这其实是一份存档 ⇒ 问名字」和逐个同名确认，逐份弹框在批量里是折磨）
        List<BatchIo.Doc> docs = BatchIo.docsOf(this, data);
        if (docs.isEmpty()) return;
        if (docs.size() > 1 || BatchIo.isArchiveName(docs.get(0).name)) {
            BatchIo.runImport(this, slot, docs, BatchIo.KIND_MAPS, new Runnable() {
                @Override public void run() { refreshList(); }
            });
            return;
        }
        Uri uri = docs.get(0).uri;
        importFile(uri, docs.get(0).name, slot, false);
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
                mTitles, mSubs, mThumbs, new BatchIo.OnPick() {
                    @Override public void onPick(List<Integer> idx) {
                        if (idx.size() == 1) {
                            // ★ 文件名用**原始名**：`0.msav` 这种就是游戏里认的名字，
                            //   "安全化"成 ASCII 之后用户在文件管理器里认不出来（同存档导出那条纪律）
                            Maps.Item it = items.get(idx.get(0).intValue());
                            sExportItem = it;
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
                        BatchIo.startExport(MapsActivity.this, srcs,
                                BatchIo.suggestZipName("maps"));
                    }
                });
    }

    /**
     * 把选中的那张图写进 SAF 目标。
     * 两条路：本槽（以及目录形态模组）里的是**真文件**；游戏 APK / 模组包里的要**从 zip 条目流式拷**。
     */
    private void exportItem(final Maps.Item it, final Uri uri) {
        final ProgressDialog pd = ProgressDialog.show(this, Trans.get(MapsActivity.this, R.string.maps_export),
                Trans.get(MapsActivity.this, R.string.maps_export_working), true, false);
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
                            pd.dismiss();
                            // 异常消息多半就是我们自己抛的那几句人话（见 Exporter）⇒ `ioReason`
                            // **认识的系统 errno 才翻**、我们的文案原样透传；没有 message 时也不把类名甩给用户
                            alert(Trans.get(MapsActivity.this, R.string.export_failed),
                                    Util.ioReason(MapsActivity.this, t,
                                            Trans.get(MapsActivity.this, R.string.maps_export_nosrc)));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
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
        final ProgressDialog pd = ProgressDialog.show(this, Trans.get(MapsActivity.this, R.string.maps_import),
                Trans.get(MapsActivity.this, R.string.maps_scanning), true, false);
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
                            pd.dismiss();
                            alert(Trans.get(MapsActivity.this, R.string.maps_import), t.toString());
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
                        pd.dismiss();
                        // ★ 本分支下面会弹 inline 的同名替换框 ⇒ 发起它的实例若已被转屏销毁，
                        //   必须停在这里（否则 BadTokenException 闪退，见 {@link Util#dead}）。
                        //   ⚠️ 成功/失败那两条出口走 alert()/Toast，已由它们各自挡住。
                        if (Util.dead(MapsActivity.this)) return;
                        // ★★ 「这份其实是存档」⇒ 先问名字（2026-10-05，REF §72）。
                        //   判据字段 = r.saveNoName（meta 里没有 `name`，与游戏自己的
                        //   `SaveMeta.isMap()` 同源）；此时 `.part` 已经留在槽里，
                        //   问完名字再 commitSaveAsMap，**不必重下那份文件**。
                        if (r.saveNoName) {
                            // ⚠️ 最后那个 true = "这是我们自己的待办 `.part`"（取消时可以删它）
                            showSaveAsMapNameDialog(r.part, r.meta, Msav.baseName(r.base), true);
                            return;
                        }
                        if (r.ok) {
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
                            new AlertDialog.Builder(MapsActivity.this)
                                    .setTitle(R.string.map_overwrite_title)
                                    .setMessage(Trans.get(MapsActivity.this, R.string.map_overwrite_msg_fmt,
                                            MapFiles.safeName(displayName)))
                                    .setPositiveButton(R.string.map_overwrite_ok,
                                            new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    importFile(uri, displayName, slot, true);
                                                }
                                            })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                            return;
                        }
                        alert(Trans.get(MapsActivity.this, R.string.maps_import),
                                Trans.get(MapsActivity.this, R.string.map_import_fail_fmt, r.error));
                    }
                });
            }
        }, "map-import").start();
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
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.maps_from_save_pick_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= fs.length) return;
                dlg.dismiss();
                MsavMeta m = MsavMeta.read(fs[pos], false);
                String base = Msav.baseName(fs[pos].getName());
                // ⚠️ 最后那个 false = "这不是我们的待办 .part，是用户的存档原件" ⇒ 谁都不许删它
                showSaveAsMapNameDialog(fs[pos], m, base, false);
            }
        });
        dlg.show();
        final boolean[] alive = {true};
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                alive[0] = false;
            }
        });
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
        srcRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSourcePicker(srcRow, mapsDir, chosen, resolved);
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
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.map_save_name_title)
                .setView(form)
                // ★ 接管确定键：名字为空时**不关窗**（否则等于静默丢弃，用户只觉得"点了没反应"）
                .setPositiveButton(R.string.import_btn, null)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (pendingPart) MapFiles.discard(src);
                    }
                })
                .create();
        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) {
                if (pendingPart) MapFiles.discard(src);
            }
        });
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                String raw = name.getText().toString().trim();
                                String err = MapFiles.checkMapName(MapsActivity.this, raw);
                                if (err != null) {
                                    Toast.makeText(MapsActivity.this, err, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                dlg.dismiss();      // dismiss 不触发 onCancel ⇒ 不会误 discard
                                // resolved=false（还在查找）⇒ 交给 MapFiles 自动匹配；否则用用户选的
                                commitSaveAsMap(src, raw, mSlot, false, chosen[0], !resolved[0]);
                            }
                        });
            }
        });
        dlg.show();
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
    private void showSourcePicker(final TextView row, final File mapsDir,
                                  final Maps.Item[] chosen, final boolean[] resolved) {
        if (Util.dead(this)) return;
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
        View box = getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        TextView head = (TextView) box.findViewById(R.id.msav_head);
        head.setVisibility(View.VISIBLE);
        head.setText(Trans.get(MapsActivity.this, R.string.map_save_source_head3_fmt, nSlot, nGame, nMod));
        android.widget.ListView lv = (android.widget.ListView) box.findViewById(R.id.msav_list);
        lv.setAdapter(new MsavListAdapter(this, titles, subs));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.map_save_source_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                dlg.dismiss();
                if (Util.dead(MapsActivity.this)) return;
                resolved[0] = true;
                if (pos == 0) {
                    chosen[0] = null;
                    row.setText(Trans.get(MapsActivity.this, R.string.map_save_source_off));
                    return;
                }
                Maps.Item it = all.get(pos - 1);
                chosen[0] = it;
                row.setText(sourceRowText(it, 1));
            }
        });
        dlg.show();
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
        final ProgressDialog pd = ProgressDialog.show(this, Trans.get(MapsActivity.this, R.string.map_save_name_title),
                Trans.get(MapsActivity.this, R.string.maps_scanning), true, false);
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
                            pd.dismiss();
                            alert(Trans.get(MapsActivity.this, R.string.map_save_name_title), t.toString());
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        // ★ 这些框都是 inline 的，可能属于已被转屏销毁的实例 ⇒ 先判死
                        if (Util.dead(MapsActivity.this)) return;
                        if (r.ok) {
                            showSaveAsMapResult(r, metaName);
                            return;
                        }
                        if (!overwrite && r.nameTaken) {
                            new AlertDialog.Builder(MapsActivity.this)
                                    .setTitle(R.string.map_overwrite_title)
                                    .setMessage(Trans.get(MapsActivity.this, R.string.map_overwrite_msg_fmt,
                                            MapFiles.safeName(metaName)))
                                    .setPositiveButton(R.string.map_overwrite_ok,
                                            new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    commitSaveAsMap(src, metaName, slot, true, sourceItem, autoSource);
                                                }
                                            })
                                    .setNegativeButton(R.string.cancel,
                                            new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    MapFiles.discard(src);
                                                }
                                            })
                                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                        @Override public void onCancel(DialogInterface d) {
                                            MapFiles.discard(src);
                                        }
                                    })
                                    .show();
                            return;
                        }
                        // 失败：用户文案走 MsavText（内核只给「码 + 参数」）
                        String why = r.conv != null
                                ? MsavText.convertReason(MapsActivity.this, r.conv)
                                : String.valueOf(r.error);
                        alert(Trans.get(MapsActivity.this, R.string.map_save_name_title),
                                Trans.get(MapsActivity.this, R.string.map_import_fail_fmt, why));
                    }
                });
            }
        }, "map-save2map").start();
    }

    /** 成功：第一层只说"转好了"，依据（删了哪些键 / 体积 / 源存档没动）走「技术细节」第二层 */
    private void showSaveAsMapResult(final MapFiles.Result r, String metaName) {
        if (Util.dead(this)) return;
        // ★ **先刷新列表、再弹结果**（用户要求"导入之后要自动刷新页面"）——
        //   弹窗还开着的时候，后面的表头/列表就已经是新数据了；「关闭」只负责收掉弹窗。
        refreshList();
        String inName = r.meta == null ? null : r.meta.get("name", null);
        String shown = inName == null ? metaName : Mods.stripColors(inName);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.map_save_name_title)
                .setMessage(Trans.get(MapsActivity.this, R.string.map_save_ok_fmt, shown)
                        + Trans.get(MapsActivity.this, R.string.map_save_ok_note))
                .setPositiveButton(R.string.close, null);
        if (r.conv != null) {
            // ★ 依据不能删（F4①d）：原始报告留在第二层
            b.setNeutralButton(R.string.map_save_detail, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    alert(Trans.get(MapsActivity.this, R.string.map_save_detail_title), r.conv.report());
                }
            });
        }
        b.show();
    }

    /**
     * 从 SAF 的 Uri 问出显示名 —— 第 127 轮起**搬进了 {@link BatchIo#displayNameOf}**：
     * 多选批量走的是同一件事（多选时 `data.getClipData()` 的每一条都要问一次名字），
     * 留两份实现迟早会在"某一家文件管理器只给一部分 URI 名字"时表现不一致。
     */

    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"：
     *  地图导入/导出是后台任务收尾时弹的（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
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
