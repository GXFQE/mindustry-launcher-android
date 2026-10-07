package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;

/**
 * 某个槽的**存档列表页**（二级页面，2026-10-06 第 115 轮第六批）。
 *
 * <p>★ 起因（用户）：「**存档页面也变成和模组 / 地图 / 蓝图这样的二级页面吧**」——
 * 原来槽页的「存档」只是一层**弹窗菜单**（看每份存档… / 导出存档… / 导入存档…），
 * 而模组 / 地图 / 蓝图那三条早就是**页面**了 ⇒ 存档补齐同一形状：
 * 表头 + 顶上动作行 + 页面自己的列表，返回键直接回槽页。
 *
 * <p>🔴 **两条纪律**（与那三个页面一字不差）：
 * <ul>
 *   <li>**只读**：本页不提供删除 —— 存档是用户的东西，删只走游戏自己的界面
 *       （口径见 {@link SlotIo#showUnreadable} 的注释）；</li>
 *   <li>SAF 的「导入」走 `startActivityForResult` ⇒ **本页必须转发 `onActivityResult`**
 *       （少了它就是"选完文件什么都没发生"）。</li>
 * </ul>
 *
 * <p>⚠️ 名字来历：本类原来指的是**槽列表页**（主页「存档与备份」那一行）；
 *   第六批把它改名成 {@link SlotsActivity}（它列的是**槽**），把这个名字让给了这里。
 */
public class SavesActivity extends BaseActivity {

    /** 进来的槽名。没有 ⇒ 直接退出，**不猜**（与模组页 / 地图页 / 蓝图页同一条） */
    public static final String EXTRA_SLOT = "slot";

    private String mSlot;
    private ListView mList;
    private TextView mHead;
    private TextView mEmpty;
    private View mBadRow;
    private View mBadDiv;
    /** 当前这份列表对应的文件（点行时按位置取；与列表**同一次扫描**，不重扫） */
    private File[] mFiles = new File[0];
    /**
     * **全部**存档（未筛）。`mFiles` 是筛过 / 排过之后要显示的那一批。
     * ★ 分开的理由：表头那句"共几份"说的是全部，而"显示 N / 共 M"那句说的是两者之比。
     */
    private File[] mAll = new File[0];

    // ── 搜索 / 排序（2026-10-07 第 122 轮；**纯前端**，一个文件都不碰）────────────
    /** 转屏要恢复的两个状态（同模组 / 地图 / 蓝图页那条教训） */
    private static final String STATE_QUERY = "mdt-saves-query";
    private static final String STATE_SORT = "mdt-saves-sort";
    private String mQuery = "";
    /** 默认按名称（与 `SlotIo.listSaves` 那个口径一致）；可选：大小 / 最新在前 */
    private int mSort = ListQuery.SORT_NAME;
    private android.widget.EditText mSearch;
    private TextView mFiltered;
    /**
     * 这一趟填列表"还作数吗"（交给 {@link SlotIo#fillSaves}）。
     * ★ 每重建一次就换一个新的、把旧的按掉：否则上一趟后台线程会按旧下标往**新**适配器上写
     *   （见 {@link SlotIo#stillAlive} 的注释）。
     */
    private boolean[] mFillToken;

    /** SAF 回调后要刷新本页（`SlotOps.Host` 是 `SlotIo` 那套 SAF 流程的回调口） */
    private final SlotOps.Host mHost = new SlotOps.Host() {
        @Override public void onSlotChanged() {
            if (!Util.dead(SavesActivity.this)) refresh();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            finish();                       // 没槽名就退出，**不猜**
            return;
        }
        mSlot = mSlot.trim();
        // ★ 转屏恢复搜索 / 排序（见 STATE_* 的注释）
        if (savedInstanceState != null) {
            String q = savedInstanceState.getString(STATE_QUERY);
            if (q != null) mQuery = q;
            mSort = savedInstanceState.getInt(STATE_SORT, ListQuery.SORT_NAME);
        }

        View root = getLayoutInflater().inflate(R.layout.activity_saves, null);
        Util.applySystemInsets(root);
        setContentView(root);
        setTitle(Trans.get(SavesActivity.this, R.string.saves_list_title_fmt, mSlot));

        mHead = (TextView) root.findViewById(R.id.save_head);
        mEmpty = (TextView) root.findViewById(R.id.save_empty);
        mList = (ListView) root.findViewById(R.id.save_list);
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= mFiles.length) return;
                SlotIo.showSaveDetail(SavesActivity.this, mSlot, mFiles[pos]);
            }
        });
        mBadRow = root.findViewById(R.id.row_save_bad);
        mBadDiv = root.findViewById(R.id.div_save_bad);

        // 搜索框 + 「显示 N / 共 M」那一行（纯前端过滤，一个文件都不碰）
        mSearch = (android.widget.EditText) root.findViewById(R.id.save_search);
        mFiltered = (TextView) root.findViewById(R.id.save_filtered);
        Util.bindSearch(mSearch, mQuery, new Runnable() {
            @Override public void run() {
                mQuery = mSearch == null ? "" : mSearch.getText().toString();
                rebuildList();
            }
        });
        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.save_search, R.string.save_search_hint);

        // ── 四条动作（顶上）────────────────────────────────────────────────
        Util.bindAction(root, R.id.row_save_import, R.drawable.ic_download,
                R.string.slot_op_import_save, R.string.save_page_import_sub, new Runnable() {
                    @Override public void run() {
                        Data.Slot s = findSlot();
                        if (s == null) { gone(); return; }
                        SlotIo.importSave(SavesActivity.this, s);
                    }
                });
        Util.bindAction(root, R.id.row_save_export, R.drawable.ic_upload,
                R.string.slot_op_export_save, R.string.save_page_export_sub, new Runnable() {
                    @Override public void run() {
                        Data.Slot s = findSlot();
                        if (s == null) { gone(); return; }
                        SlotIo.exportSave(SavesActivity.this, s);
                    }
                });
        Util.bindAction(root, R.id.row_save_bad, R.drawable.ic_health,
                R.string.slot_op_show_bad_saves, R.string.save_page_bad_sub, new Runnable() {
                    @Override public void run() {
                        SlotIo.showUnreadable(SavesActivity.this, mSlot);
                    }
                });
        // ★ 2026-10-07（第 122 轮）：「筛选与排序」——与模组 / 地图 / 蓝图页同一行、同一个形状。
        //   ⚠️ 这一页**没有**"只看有问题的"：存档的"读不出来"已经有专用入口（上面那一行
        //   →「哪几份读不出来」）—— 再来一个开关就是同一件事两个入口（F15b 的教训）。
        //   作为替代，它多一档**按时间**（存档是真文件、有修改时间；"最新保存的在前"最常用）。
        Util.bindAction(root, R.id.row_save_filter, R.drawable.ic_settings,
                R.string.mods_filter_title, R.string.save_act_filter_sub, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_QUERY, mQuery);
        out.putInt(STATE_SORT, mSort);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();                          // 用户可能刚在游戏里存过 / 在别处导入过
    }

    /**
     * ★ SAF 的结果**必然回到发起它的那个 Activity** ⇒ 本页必须转发给 {@link SlotIo}
     *   （少了它就是"选完文件什么都没发生"；先例见 {@link SlotActivity} 的同一条注释）。
     */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SlotIo.onActivityResult(this, requestCode, resultCode, data, mHost);
    }

    /** 重新扫盘（先扫**全部**，再交给 {@link #rebuildList} 筛 / 排并挂行） */
    private void refresh() {
        if (mList == null || Util.dead(this)) return;
        mAll = SlotIo.listSaves(this, mSlot);
        rebuildList();

        // 「看看哪几份读不出来」**只在真有**的时候出现 —— 口径 = `MsavMeta.summarize`
        // （与槽页副标题、那个弹窗**同一处**实现；读 meta 要开流 ⇒ 走后台）
        new Thread(new Runnable() {
            @Override public void run() {
                final MsavMeta.Saves sm;
                try {
                    File dir = new File(Data.dirOf(SavesActivity.this, mSlot), "saves");
                    sm = MsavMeta.summarize(dir.listFiles());
                } catch (Throwable t) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SavesActivity.this)) return;
                        int vis = sm.unreadableCount() > 0 ? View.VISIBLE : View.GONE;
                        if (mBadRow != null) mBadRow.setVisibility(vis);
                        // ⚠️ 线要跟着行一起显隐（只处理行 ⇒ 卡片底下留一条悬空横线，第 115 轮第二批踩过）
                        if (mBadDiv != null) mBadDiv.setVisibility(vis);
                    }
                });
            }
        }, "save-bad-count").start();
    }

    /**
     * 重建列表 = 搜索 / 排序（判据在 {@link ListQuery}）+ 挂行 + 表头 / 空态 / 「已筛选」那一行。
     *
     * <p>★ 筛选是**纯前端**的（`mAll` → `mFiles`），一个文件都不动；点行取的是 `mFiles`，
     * 所以筛过之后点哪一行都还是对的。
     * ⚠️ 空态分两种（一份存档都没有 / 筛完一份不剩）：只有这里同时知道两者，
     * 写死成前一种会让用户以为"存档丢了"。
     * ⚠️ 每次重填都要**按掉上一趟的后台线程**（`mFillToken`）—— 否则它会把上一代的副标题
     * 写到新适配器的同一行上（见 {@link SlotIo#stillAlive}）。
     */
    private void rebuildList() {
        if (mList == null) return;
        java.util.List<File> shown = ListQuery.apply(java.util.Arrays.asList(mAll), mQuery, mSort,
                false, ListQuery.FILE_KEY);
        mFiles = shown.toArray(new File[0]);
        if (mFillToken != null) mFillToken[0] = false;
        mFillToken = new boolean[]{true};
        SlotIo.fillSaves(this, mSlot, mList, mFillToken, mFiles);

        boolean filtering = ListQuery.filtering(mQuery, mSort, false, ListQuery.SORT_NAME);
        // 表头那句同时承担"有几份"（标题里放不下多行，见 dialog_msav_list.xml 的实测）
        mHead.setText(Trans.get(SavesActivity.this, R.string.saves_list_head_fmt, mAll.length));
        if (mFiltered != null) {
            mFiltered.setVisibility(filtering ? View.VISIBLE : View.GONE);
            if (filtering) {
                mFiltered.setText(Trans.get(SavesActivity.this, R.string.save_filtered_fmt,
                        mFiles.length, mAll.length));
            }
        }
        boolean empty = mFiles.length == 0;
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            // ⚠️ 那句"还没有存档"**复用**导入那条资源（「槽「X」里还没有存档。」）—— 同一件事只留一句文案
            mEmpty.setText(filtering
                    ? Trans.get(SavesActivity.this, R.string.save_empty_filtered)
                    : Trans.get(SavesActivity.this, R.string.export_no_save_fmt, mSlot));
        }
    }

    /**
     * 「筛选与排序」对话框（与模组 / 地图 / 蓝图页同一个形状，也共用它那几条词）。
     * ★ 排序三档：名称（默认）/ 最新在前 / 大小（大的在前）——
     *   "最新在前"是这一页独有的（槽里的存档是真文件，有修改时间）。
     */
    private void pickFilter() {
        final String tick = "✓ ";
        final String[] items = {
                (mSort == ListQuery.SORT_NAME ? tick : "")
                        + Trans.get(SavesActivity.this, R.string.mods_filter_sort_name),
                (mSort == ListQuery.SORT_TIME ? tick : "")
                        + Trans.get(SavesActivity.this, R.string.save_filter_sort_time),
                (mSort == ListQuery.SORT_SIZE ? tick : "")
                        + Trans.get(SavesActivity.this, R.string.mods_filter_sort_size),
                Trans.get(SavesActivity.this, R.string.mods_filter_reset)};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mods_filter_title)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            mSort = ListQuery.SORT_NAME;
                        } else if (which == 1) {
                            mSort = ListQuery.SORT_TIME;
                        } else if (which == 2) {
                            mSort = ListQuery.SORT_SIZE;
                        } else {
                            mSort = ListQuery.SORT_NAME;
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

    /** 这个槽已经不在了（被删 / 改名）：提示一句就退出去（文案与槽页那句同一条） */
    private void gone() {
        android.widget.Toast.makeText(this, Trans.get(SavesActivity.this, R.string.slot_page_gone_fmt, mSlot),
                android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 找本页认的那个槽对象（**每次都现扫** —— 槽会被重命名 / 删除，别缓存） */
    private Data.Slot findSlot() {
        for (Data.Slot x : Data.allSlots(this)) {
            if (mSlot.equals(x.name)) return x;
        }
        return null;
    }
}
