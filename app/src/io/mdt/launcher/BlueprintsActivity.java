package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 蓝图列表页（F22 第一步，2026-10-05 第 109 轮）：从槽二级页面的「蓝图」进来。
 *
 * <h3>这一页解决什么</h3>
 * 蓝图是**槽自己的数据**（`<槽>/schematics/`，游戏 `schematicDirectory`），加上**已启用模组**
 * 自带的那些。在启动器里能列出来之后，才有"开游戏之前先看一眼这蓝图缺不缺件"这件事
 * —— 而缺件在游戏里是**静默**的（认不出的方块直接当空气，贴出来才发现少东西）。
 *
 * <h3>形状</h3>
 * 表头（本槽 N · 模组自带 M）+ 卡片列表（名字 / 尺寸·瓦片·分类，缺件时多一行警告）+ 空态。
 * 点一条进 {@link BlueprintDetailActivity}（**独立页面**：弹窗属于发起它的页面，关掉会落到上一级）。
 *
 * 🔴 两条工程硬规矩：
 *   · **必须继承 {@link BaseActivity}** —— 深浅色的唯一生效点（写成 `extends Activity` 时
 *     用户设的主题对本页完全无效，且不崩不报错，自检 ㊱ 会拦）；
 *   · 根节点带 `@+id/root` + `Util.applySystemInsets(root)` —— 否则整页被状态栏/顶栏盖住（自检 ㉛）。
 *   · 后台任务收尾一律先 `Util.dead(this)`（转屏时 `isFinishing()` 是 false，只判它挡不住）。
 */
public class BlueprintsActivity extends BaseActivity {
    /** 目标槽（本页一切以它为准，**绝不回落当前槽**） */
    public static final String EXTRA_SLOT = "slot";
    /** SAF：选一份 .msch 导进来 */
    private static final int REQ_BP_IMPORT = 72;
    /** SAF：把某一份写出去 */
    private static final int REQ_BP_EXPORT = 73;

    /**
     * SAF 的"待办目标"**必须是 static**（第 86 轮的硬规矩，见 `AGENTS.md` §五）：
     * 用户在系统选择器里时转屏 ⇒ Activity 会被销毁重建，实例字段在新实例里是 null
     * ⇒ 回调静默 return = "选完文件什么都没发生"。
     */
    private static String sImportSlot;
    private static Blueprints.Item sExportItem;
    /** dev 口用：把"选文件"换成路径（SAF 自动化不了） */
    public static final String EXTRA_DEV_IMPORT = "dev_bp_import";

    private String mSlot;
    private List<Blueprints.Item> mItems;
    private ListView mList;
    private MsavListAdapter mAdapter;
    private TextView mHead, mEmpty;
    /** 已经扫过一轮（第一次 `onResume` 紧跟 `onCreate`，靠它避免开局扫两遍） */
    private boolean mScanned;

    // ── 搜索 / 排序 / 只看有问题的（2026-10-07 第 122 轮；**纯前端**，一个文件都不碰）──
    /** 转屏要恢复的三个状态（同模组页 / 地图页那条教训：不存就是"一转屏我打的字没了"） */
    private static final String STATE_QUERY = "mdt-bp-query";
    private static final String STATE_SORT = "mdt-bp-sort";
    private static final String STATE_ONLY = "mdt-bp-only";
    private String mQuery = "";
    private int mSort = ListQuery.SORT_NAME;
    private boolean mOnlyProblems;
    private android.widget.EditText mSearch;
    private TextView mFiltered;
    /** 当前**显示**的那一批（筛过 / 排过）；`mItems` 仍是全部（导出对话框按它列） */
    private List<Blueprints.Item> mShown;
    /** 全部清单的缩略图（与 `mItems` 同下标；列表只显示其中一部分，见 `thumbReady`） */
    private android.graphics.Bitmap[] mThumbs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            finish();                       // 没槽名就退出，**不猜**
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

        View root = getLayoutInflater().inflate(R.layout.activity_blueprints, null);
        Util.applySystemInsets(root);
        setContentView(root);
        setTitle(R.string.bp_title);

        mHead = (TextView) root.findViewById(R.id.bp_head);
        mHead.setText(Trans.get(BlueprintsActivity.this, R.string.bp_reading));
        mEmpty = (TextView) root.findViewById(R.id.bp_empty);

        mList = (ListView) root.findViewById(R.id.bp_list);
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (mShown != null && pos >= 0 && pos < mShown.size()) showDetail(mShown.get(pos));
            }
        });

        // 搜索框 + 「显示 N / 共 M」那一行（纯前端过滤，一个文件都不碰）
        mSearch = (android.widget.EditText) root.findViewById(R.id.bp_search);
        mFiltered = (TextView) root.findViewById(R.id.bp_filtered);
        Util.bindSearch(mSearch, mQuery, new Runnable() {
            @Override public void run() {
                mQuery = mSearch == null ? "" : mSearch.getText().toString();
                rebuildList();
            }
        });
        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.bp_search, R.string.bp_search_hint);

        // ── 导入 / 导出（页面顶上两行，与地图页同一条定案）──────────────────────
        Util.bindAction(root, R.id.row_bp_import, R.drawable.ic_download,
                R.string.bp_import, R.string.bp_import_sub, new Runnable() {
                    @Override public void run() {
                        // ★ **先判游戏在不在跑**（拦在 SAF 选择器之前，别让用户白选一次）：
                        //   导入是往槽的 schematics/ 里写（同名旧件还会被挪去中转站），
                        //   而游戏本局存下来的蓝图就在那个目录里 —— 与地图导入同一条门禁。
                        if (Data.gameAlive(BlueprintsActivity.this)) {
                            alert(Trans.get(BlueprintsActivity.this, R.string.game_busy_title),
                                    Trans.get(BlueprintsActivity.this, R.string.bp_import_busy_msg));
                            return;
                        }
                        sImportSlot = mSlot;
                        // ★ 第 127 轮：**可多选**（选 1 份 = 老样子；≥2 份或一个 zip = 批量，
                        //   见 onActivityResult / BatchIo.runImport）
                        BatchIo.pickFiles(BlueprintsActivity.this, REQ_BP_IMPORT, R.string.bp_import);
                    }
                });
        Util.bindAction(root, R.id.row_bp_export, R.drawable.ic_upload,
                R.string.bp_export, R.string.bp_export_sub, new Runnable() {
                    @Override public void run() {
                        promptExport();
                    }
                });
        // ★ 2026-10-07（第 122 轮）：「筛选与排序」——与模组 / 地图页同一行、同一个对话框形状。
        //   ⚠️ 对话框里那几条词复用模组页的资源（名称 / 有问题的在前 / 大小 / 只看有问题的 /
        //   清空筛选）：四个列表页说的是同一件事，各写一份只会让用户语言包多四条重复翻译。
        //   这一页的「有问题的」= **缺件或解析不了**（判据见 Blueprints#isProblem）——
        //   那正是这一页存在的理由：开游戏之前先看一眼这蓝图缺不缺件。
        Util.bindAction(root, R.id.row_bp_filter, R.drawable.ic_settings,
                R.string.mods_filter_title, R.string.bp_act_filter_sub, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });

        scan();

        // dev 口：把"选文件"换成路径（SAF 选择器自动化不了）—— ⚠️ 必须放在 scan() 之后，
        //   因为导入成功后会 refreshList() 再看一次列表。
        final String devPath = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DEV_IMPORT);
        if (devPath != null && !devPath.trim().isEmpty()) {
            File df = new File(devPath.trim());
            importFile(android.net.Uri.fromFile(df), df.getName(), mSlot, false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // 从详情页回来、或用户在游戏里存了新蓝图再切回来 ⇒ 重扫一次
        // （几十份小文件，很便宜；`mScanned` 只为挡掉"开局紧跟 onCreate 的那一次"）
        if (mScanned) scan();
    }

    /** 重扫并刷新（**只重扫列表，不重建页面** —— 与地图页同一个理由：重建会闪） */
    private void scan() {
        if (Util.dead(this) || mList == null) return;
        mHead.setText(Trans.get(BlueprintsActivity.this, R.string.bp_reading));
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Blueprints.Item> items;
                MapStats.Table tab;
                // ★ bundle 里声明过的方块名（真修 §77.5④）—— `sc` 只在 try 里可见，所以拷出来
                java.util.Map<String, String> bundleBlocks;
                int opaque;
                try {
                    items = Blueprints.scan(BlueprintsActivity.this, mSlot);
                    // 缺件判据 = **本槽已启用模组**的那张内容表（与地图统计同源，含缓存）
                    MapStatsMods.SlotContent sc = MapStatsMods.contentFor(BlueprintsActivity.this, mSlot);
                    tab = sc.table;
                    opaque = sc.opaque;
                    bundleBlocks = sc.bundleBlocks;
                } catch (Throwable ex) {
                    android.util.Log.w("MDTLauncher", "blueprint scan failed", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(BlueprintsActivity.this)) return;
                            mHead.setText(Trans.get(BlueprintsActivity.this, R.string.bp_stats_failed));
                        }
                    });
                    return;
                }
                // 每份的派生量（方块种类 / 缺件）在后台算好，界面只渲染 —— 列表行要显示缺件警告
                // ★ 带上 `sc.bundleBlocks`（真修，REF §77.5④）：方块写在代码/脚本里的模组
                //   会在 bundle 里声明 `block.<名字>.name` ⇒ 拿它当"存在"证据，消掉误报缺件
                for (Blueprints.Item it : items) {
                    Blueprints.summarize(it, Blueprints.rows(it.msch, tab, null, bundleBlocks));
                }
                // ★ 再打一道"可能认错"的标记（判据在 MapStats.Pack#hasCode；只影响文案，不改判据）
                Blueprints.markSoft(items, opaque);
                final String[] titles = new String[items.size()];
                final String[] subs = new String[items.size()];
                // ★ 缩略图（第 116 轮）：与地图 / 存档页同一个形状 —— 先摆空数组，后台出来一张刷一张
                final android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    Blueprints.Item it = items.get(i);
                    titles[i] = it.displayName();
                    String src = it.sourceLabel(BlueprintsActivity.this);
                    subs[i] = src + " · " + it.line(BlueprintsActivity.this);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(BlueprintsActivity.this)) return;
                        mItems = items;
                        // ★ 缩略图主数组（与 mItems 同下标）：列表显示的是筛过的那一份，
                        //   所以后台补图要先落到这里（见 thumbReady）
                        mThumbs = thumbs;
                        mHead.setText(Trans.get(BlueprintsActivity.this, R.string.bp_head_fmt,
                                Blueprints.count(items, Blueprints.FROM_SLOT),
                                Blueprints.count(items, Blueprints.FROM_MOD)));
                        // ★ 列表本体（含搜索 / 排序 / 只看有问题的 / 空态）只有一处实现
                        rebuildList();
                        mScanned = true;
                    }
                });
                // ★ 后台逐张渲染（出来一张刷**那一行**）—— 与地图页同一套做法与同一条理由：
                //   几十份蓝图里 `msch` 已经在上面解析过，这里只是把瓦片画成位图 + 落缓存。
                //   ⚠️ 像素级预览要**这个槽指向的版本 APK**（图集在里面）；拿不到就回落色块档。
                String apk = null;
                try {
                    apk = Mods.targetsFor(BlueprintsActivity.this, mSlot).apkPath;
                } catch (Throwable ignored) {
                }
                final String apkPath = apk;
                for (int i = 0; i < items.size(); i++) {
                    final int idx = i;
                    final android.graphics.Bitmap bm = MschLoad.image(
                            BlueprintsActivity.this, items.get(idx), apkPath, mSlot, MschLoad.THUMB);
                    if (bm == null) continue;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(BlueprintsActivity.this)) return;
                            // ★ 只更新**这一行**（`notifyDataSetChanged()` 会让整张列表在滚动时重排）；
                            //   下标要绕一步 —— 图是按**全部清单**算的，列表里可能只是筛过的一部分
                            thumbReady(idx, bm);
                        }
                    });
                }
            }
        }, "bp-scan").start();
    }

    // ── 搜索 / 排序 / 只看有问题的（第 122 轮）───────────────────────────────

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_QUERY, mQuery);
        out.putInt(STATE_SORT, mSort);
        out.putBoolean(STATE_ONLY, mOnlyProblems);
    }

    /**
     * 重建列表 = 搜索 / 筛选 / 排序（判据在 {@link Blueprints#filterAndSort}）+ 挂行 + 那两行提示。
     *
     * <p>★ 与地图页同一份形状（那里注释写得更细）：空态分**两种**（一份蓝图都没有 / 筛完一条不剩），
     * 缩略图从主数组 `mThumbs` 里搬、不重渲染。
     * ⚠️ "只看缺件的"依赖**后台已经算好的** `missingKinds`（`Blueprints.summarize`）——
     * 本方法在扫描回调里、也就是 summarize **之后**才被调（顺序反了会把所有条目都筛掉）。
     */
    private void rebuildList() {
        if (mList == null || mItems == null) return;
        mShown = Blueprints.filterAndSort(mItems, mQuery, mSort, mOnlyProblems);
        boolean filtering = ListQuery.filtering(mQuery, mSort, mOnlyProblems, ListQuery.SORT_NAME);
        String[] titles = new String[mShown.size()];
        String[] subs = new String[mShown.size()];
        android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[mShown.size()];
        for (int i = 0; i < mShown.size(); i++) {
            Blueprints.Item it = mShown.get(i);
            int k = allIndexOf(it);
            titles[i] = it.displayName();
            subs[i] = it.sourceLabel(this) + " · " + it.line(this);
            thumbs[i] = (k < 0 || mThumbs == null) ? null : mThumbs[k];
        }
        mAdapter = new MsavListAdapter(this, titles, subs, thumbs);
        mList.setAdapter(mAdapter);

        if (mFiltered != null) {
            mFiltered.setVisibility(filtering ? View.VISIBLE : View.GONE);
            if (filtering) {
                mFiltered.setText(Trans.get(BlueprintsActivity.this, R.string.bp_filtered_fmt,
                        mShown.size(), mItems.size()));
            }
        }
        if (mEmpty != null) {
            boolean none = mShown.isEmpty();
            mEmpty.setVisibility(none ? View.VISIBLE : View.GONE);
            if (none) {
                mEmpty.setText(filtering
                        ? Trans.get(BlueprintsActivity.this, R.string.bp_empty_filtered)
                        : Trans.get(BlueprintsActivity.this, R.string.bp_empty));
            }
        }
    }

    /** 一份蓝图在**全部清单**里的下标（`Item` 没有 equals ⇒ 比的是同一个对象） */
    private int allIndexOf(Blueprints.Item it) {
        if (mItems == null || it == null) return -1;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i) == it) return i;
        }
        return -1;
    }

    /** 一份蓝图在**当前显示的那一份**里的下标（-1 = 现在被筛掉了） */
    private int shownIndexOf(Blueprints.Item it) {
        if (mShown == null || it == null) return -1;
        for (int i = 0; i < mShown.size(); i++) {
            if (mShown.get(i) == it) return i;
        }
        return -1;
    }

    /** 后台渲染好一张缩略图（按**全部清单**的下标）：写主数组 + 若它此刻在列表里就只刷那一行 */
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
     * 「筛选与排序」对话框（与模组 / 地图页同一个形状，也共用它那几条词）。
     * ★ 蓝图的「有问题的」= 缺件或解析不了 —— 这一页最该被筛出来的就是缺件那些。
     */
    private void pickFilter() {
        final String tick = "✓ ";
        final String[] items = {
                (mSort == ListQuery.SORT_NAME ? tick : "")
                        + Trans.get(BlueprintsActivity.this, R.string.mods_filter_sort_name),
                (mSort == ListQuery.SORT_PROBLEM ? tick : "")
                        + Trans.get(BlueprintsActivity.this, R.string.mods_filter_sort_state),
                (mSort == ListQuery.SORT_SIZE ? tick : "")
                        + Trans.get(BlueprintsActivity.this, R.string.mods_filter_sort_size),
                (mOnlyProblems ? tick : "")
                        + Trans.get(BlueprintsActivity.this, R.string.mods_filter_only),
                Trans.get(BlueprintsActivity.this, R.string.mods_filter_reset)};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mods_filter_title)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
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

    private void showDetail(Blueprints.Item it) {
        Intent i = new Intent(this, BlueprintDetailActivity.class);
        i.putExtra(BlueprintDetailActivity.EXTRA_SLOT, mSlot);
        Blueprints.putExtra(i, it);
        // ⚠️ 用 startActivity（不收回执）：详情页删完之后本页的 `onResume` 就会重扫一次，
        //   再挂一条 RESULT_OK 分支只会**扫两遍**（而重扫本身是几十个小文件，很便宜）。
        startActivity(i);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // ★ 第 127 轮：批量导出的落点（勾了多份时走它；一份仍走下面 REQ_BP_EXPORT 那条老路）
        if (BatchIo.handleExportResult(this, requestCode, resultCode, data)) return;
        if (requestCode == REQ_BP_EXPORT) {
            // ★ 与导入同一条纪律：无论成败**先清掉待写的那一条**（否则下一次导出会认错蓝图）
            final Blueprints.Item it = sExportItem;
            sExportItem = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || it == null) return;
            exportItem(it, data.getData());
            return;
        }
        if (requestCode != REQ_BP_IMPORT) return;
        // ★ 与地图那条同一纪律：无论成败**先清目标槽**
        final String slot = sImportSlot;
        sImportSlot = null;
        if (resultCode != RESULT_OK || data == null || slot == null) return;
        // ★ 第 127 轮：一批（多选 / 一个 zip）走批量；**一份普通文件**仍走单份那条路
        //   （那条路有逐个同名确认，逐份弹框在批量里是折磨）
        List<BatchIo.Doc> docs = BatchIo.docsOf(this, data);
        if (docs.isEmpty()) return;
        if (docs.size() > 1 || BatchIo.isArchiveName(docs.get(0).name)) {
            BatchIo.runImport(this, slot, docs, BatchIo.KIND_BLUEPRINTS, new Runnable() {
                @Override public void run() { scan(); }
            });
            return;
        }
        importFile(docs.get(0).uri, docs.get(0).name, slot, false);
    }

    // ── 导入 ──────────────────────────────────────────────────────────────

    private void importFile(final android.net.Uri uri, final String displayName,
                            final String slot, final boolean overwrite) {
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(this,
                Trans.get(BlueprintsActivity.this, R.string.bp_import), Trans.get(BlueprintsActivity.this, R.string.bp_reading), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final BlueprintFiles.Result r;
                java.io.InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    File dir = BlueprintFiles.dirOf(BlueprintsActivity.this, slot);
                    r = BlueprintFiles.importSchem(BlueprintsActivity.this, dir,
                            BlueprintFiles.safeName(displayName), in, overwrite,
                            BlueprintFiles.trashDirOf(BlueprintsActivity.this));
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            alert(Trans.get(BlueprintsActivity.this, R.string.bp_import), String.valueOf(t));
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
                        // ★ 下面会弹 inline 的同名替换框 ⇒ 转屏销毁后必须停在这里（见 Util.dead）
                        if (Util.dead(BlueprintsActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(BlueprintsActivity.this,
                                    Trans.get(BlueprintsActivity.this, R.string.bp_import_ok_fmt, r.finalName),
                                    Toast.LENGTH_SHORT).show();
                            scan();                       // ★ 与地图页统一：只重扫列表，不重建页面
                            return;
                        }
                        // ★ 判"要不要弹同名替换框"**只认 r.nameTaken**（权威判据字段），
                        //   不许去看 r.error 的字面内容（i18n P0.1；门禁规则 SRC-01）。
                        if (!overwrite && r.nameTaken) {
                            new android.app.AlertDialog.Builder(BlueprintsActivity.this)
                                    .setTitle(R.string.bp_overwrite_title)
                                    .setMessage(Trans.get(BlueprintsActivity.this, R.string.bp_overwrite_msg_fmt,
                                            BlueprintFiles.safeName(displayName)))
                                    .setPositiveButton(R.string.bp_overwrite_ok,
                                            new android.content.DialogInterface.OnClickListener() {
                                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                                    importFile(uri, displayName, slot, true);
                                                }
                                            })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                            return;
                        }
                        // ⚠️ 失败原因走**码 → 文案**（`Msch` 只给码；`error` 原文是给报告与自检的）
                        String why = r.broken != null ? MschText.reason(BlueprintsActivity.this, r.broken)
                                : r.error;
                        alert(Trans.get(BlueprintsActivity.this, R.string.bp_import),
                                Trans.get(BlueprintsActivity.this, R.string.bp_import_fail_fmt, why));
                    }
                });
            }
        }, "bp-import").start();
    }

    // ── 导出 ──────────────────────────────────────────────────────────────

    /**
     * 先挑"导出哪几份"，再让用户选存到哪。
     *
     * ★ 与地图页同一条理由：**导出不限来源** —— 模组自带的蓝图在模组包（zip）里面，
     *   用户在文件管理器里根本看不见它们；只导本槽的话，"把模组带的那份拿出来"永远做不到。
     * ★ 第 127 轮：这张列表**改成多选** —— 勾 1 份仍走单份那条路（原文件名），
     *   勾 ≥2 份打成**一个 zip**（同一条判据见 {@link BatchIo}）。
     */
    private void promptExport() {
        if (mItems == null || mItems.isEmpty()) {
            Toast.makeText(this, R.string.bp_export_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Blueprints.Item> items = mItems;
        String[] titles = new String[items.size()];
        String[] subs = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            titles[i] = items.get(i).displayName();
            subs[i] = items.get(i).sourceLabel(this) + " · " + items.get(i).line(this);
        }
        BatchIo.pickMulti(this, R.string.bp_export,
                Trans.get(BlueprintsActivity.this, R.string.batch_export_head_fmt, items.size()),
                titles, subs, null, new BatchIo.OnPick() {
                    @Override public void onPick(List<Integer> idx) {
                        if (idx.size() == 1) {
                            // ★ 文件名用**原始名**：那才是游戏里认的名字（同地图/存档导出那条纪律）
                            sExportItem = items.get(idx.get(0).intValue());
                            startActivityForResult(Intent.createChooser(
                                    Exporter.createDoc(sExportItem.name(), "application/octet-stream"),
                                    Trans.get(BlueprintsActivity.this, R.string.chooser_export)),
                                    REQ_BP_EXPORT);
                            return;
                        }
                        List<Exporter.Src> srcs = new java.util.ArrayList<>();
                        for (Integer i : idx) {
                            Blueprints.Item it = items.get(i.intValue());
                            if (it.file != null) {
                                srcs.add(Exporter.Src.ofFile(it.file, it.name()));
                            } else if (it.container != null && it.entry != null) {
                                srcs.add(Exporter.Src.ofEntry(it.container, it.entry, it.name()));
                            }
                        }
                        BatchIo.startExport(BlueprintsActivity.this, srcs,
                                BatchIo.suggestZipName("blueprints"));
                    }
                });
    }

    /** 把选中的那份写进 SAF 目标：本槽是真文件、模组自带要从 zip 条目流式拷 */
    private void exportItem(final Blueprints.Item it, final android.net.Uri uri) {
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(this,
                Trans.get(BlueprintsActivity.this, R.string.bp_export), Trans.get(BlueprintsActivity.this, R.string.bp_export_working), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final long n;
                try {
                    if (it.file != null && it.file.isFile()) {
                        n = Exporter.writeFile(BlueprintsActivity.this, uri, it.file);
                    } else if (it.container != null && it.entry != null) {
                        n = Exporter.writeEntry(BlueprintsActivity.this, uri, it.container, it.entry);
                    } else {
                        throw new java.io.IOException(Trans.get(BlueprintsActivity.this, R.string.bp_export_nosrc));
                    }
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            // 同 `MapsActivity` 的导出失败：`ioReason` 只翻认识的系统 errno，
                            // 我们自己的文案原样透传（没 message 时用资源兜底，不甩类名）
                            alert(Trans.get(BlueprintsActivity.this, R.string.export_failed),
                                    Util.ioReason(BlueprintsActivity.this, t,
                                            Trans.get(BlueprintsActivity.this, R.string.bp_export_nosrc)));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        Toast.makeText(BlueprintsActivity.this,
                                Trans.get(BlueprintsActivity.this, R.string.bp_export_ok_fmt, it.name(),
                                        Util.formatSize(n)), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "bp-export").start();
    }

    /**
     * 从 SAF 的 Uri 问出显示名 —— 第 127 轮起**搬进了 {@link BatchIo#displayNameOf}**：
     * 多选批量走的是同一件事（`data.getClipData()` 里每条都要问一次名字），
     * 留两份实现迟早会在"某一家文件管理器只给一部分 URI 名字"时表现不一致。
     */

    /** 全类弹窗的唯一入口（挡"已销毁的 Activity"：导入/导出是后台任务收尾时弹的） */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
