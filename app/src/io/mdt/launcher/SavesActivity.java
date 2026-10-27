package io.mdt.launcher;

import android.app.AlertDialog;
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
 * 某个槽的**存档列表页**（二级页面，2026-10-06 第 115 轮第六批）。
 *
 * <p>★ 起因（用户）：「**存档页面也变成和模组 / 地图 / 蓝图这样的二级页面吧**」——
 * 原来槽页的「存档」只是一层**弹窗菜单**（看每份存档… / 导出存档… / 导入存档…），
 * 而模组 / 地图 / 蓝图那三条早就是**页面**了 ⇒ 存档补齐同一形状：
 * 表头 + 顶上动作行 + 页面自己的列表，返回键直接回槽页。
 *
 * <p>🔴 **两条纪律**（与那三个页面一字不差）：
 * <ul>
 *   <li>**删除只在"挪进中转站"这个意义上**（第 157 轮起；原来这页是彻底只读）：勾选 → 确认 →
 *       `Trash.savesDir`，随时能从中转站放回去。判据与地图 / 蓝图同一条：
 *       **只允许动 `saves/` 的直接子项**（{@link BatchIo#deletable}）；</li>
 *   <li>SAF 的「导入」走 `startActivityForResult` ⇒ **本页必须转发 `onActivityResult`**
 *       （少了它就是"选完文件什么都没发生"）。</li>
 * </ul>
 *
 * <p>⚠️ 名字来历：本类原来指的是**槽列表页**（主页「存档与备份」那一行）；
 *   第六批把它改名成 {@link SlotsActivity}（它列的是**槽**），把这个名字让给了这里。
 */
public class SavesActivity extends BaseActivity {

    /** v24（第 226 轮）：本页的插件挂载点（content / action / section 统一由 BaseActivity 挂）。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_SAVES; }

    @Override protected String pluginSlot() { return mSlot; }

    /** 进来的槽名。没有 ⇒ 直接退出，**不猜**（与模组页 / 地图页 / 蓝图页同一条） */
    public static final String EXTRA_SLOT = "slot";

    private String mSlot;
    private android.view.ViewGroup mList;
    /** 行单击（容器形态没有 `OnItemClickListener`，见 `onCreate`） */
    private MsavListAdapter.RowClick mRowClick;
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
    /**
     * 存档文件的**游戏内存档名**缓存（绝对路径 → 名字）。第 157 轮加。
     *
     * <p>★ 搜索要能搜到「Ancient Caldera」这种名字 —— 它住在 zlib 头里，得解析才拿得到，
     * 所以不能每次输入都现解：页面打开后由 {@link #warmSaveNames()} 在后台预热一遍，
     * 还没预热到的那几份只按文件名匹配（预热完会自己重筛一次）。</p>
     */
    private final java.util.Map<String, String> mNames = new java.util.HashMap<>();
    /** 预热线程只跑一趟（每次重建列表不该再解一遍全部存档） */
    private boolean mNamesWarmed;

    // ── 搜索 / 排序（2026-10-07 第 122 轮；**纯前端**，一个文件都不碰）────────────
    /** 转屏要恢复的两个状态（同模组 / 地图 / 蓝图页那条教训） */
    private static final String STATE_QUERY = "mdt-saves-query";
    private static final String STATE_SORT = "mdt-saves-sort";
    private String mQuery = "";
    /** 默认按名称（与 `SlotIo.listSaves` 那个口径一致）；可选：大小 / 最新在前 */
    private int mSort = ListQuery.SORT_NAME;
    /**
     * v25（第 280 轮统一）：插件注入的**筛选**结果（条目身份集合；null = 没选）。
     * ★ 身份见 {@link PluginPicks#idOf} —— 存档都是槽里的文件，所以就是绝对路径。
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

    /** 存档条目的身份：绝对路径（与 {@code ItemInfo.path} 同一口径）。 */
    private static final PluginPicks.IdOf<File> SAVE_ID = new PluginPicks.IdOf<File>() {
        @Override public String idOf(File f) {
            return f == null ? "" : f.getAbsolutePath();
        }
    };
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
        // ★★ 2026-10-16（第 151b 轮）：列表改成整页 `ScrollView` 里手工挂载的容器（见 `activity_saves.xml` 头）。
        //   行单击因此挂在每一行上（容器没有 `OnItemClickListener`）——由 `SlotIo.fillSaves` 挂。
        mList = (android.view.ViewGroup) root.findViewById(R.id.save_list);
        mRowClick = new MsavListAdapter.RowClick() {
            @Override public void onRow(int pos) {
                if (pos < 0 || pos >= mFiles.length) return;
                SlotIo.showSaveDetail(SavesActivity.this, mSlot, mFiles[pos]);
            }
        };
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
        Util.bindAction(root, R.id.row_save_del, R.drawable.ic_trash,
                R.string.batch_del_row, R.string.batch_del_sub, new Runnable() {
                    @Override public void run() { promptDeleteSaves(); }
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

        // v24（第 226 轮）：插件卡由 BaseActivity → PluginEntries.attachPage 统一挂

        // F9 深链：本页是被外部"打开方式"（浏览器 / 文件管理器）叫起来的。
        //   ★ 先过与「导入存档」那一行**同一个**写槽门禁（`SlotIo.blockedByGame`：只有
        //     当前槽被游戏占着时才挡），再走与 SAF 那条路**同一份**分流判据
        //     （`SlotIo.importDocs`）—— 两条路各写一遍就会漂。
        final java.util.List<BatchIo.Doc> dl = DeepLink.docsOf(getIntent(), this);
        if (!dl.isEmpty()) {
            Data.Slot s = findSlot();
            if (s == null) {
                gone();
            } else if (!SlotIo.blockedByGame(this, s)) {
                SlotIo.importDocs(this, mSlot, dl, mHost);
            }
        }
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
        warmSaveNames();

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
                false, ListQuery.fileKey(mNames));
        // v25（第 280 轮）：插件注入的筛选 / 排序（结果已缓存成身份集合 / 顺序，纯内存应用）
        shown = PluginPicks.applyFilter(shown, mPluginFilterIds, SAVE_ID);
        shown = PluginPicks.applySort(shown, mPluginSortIds, SAVE_ID);
        mFiles = shown.toArray(new File[0]);
        if (mFillToken != null) mFillToken[0] = false;
        mFillToken = new boolean[]{true};
        SlotIo.fillSaves(this, mSlot, mList, mFillToken, mFiles, mRowClick);

        boolean filtering = ListQuery.filtering(mQuery, mSort, false, ListQuery.SORT_NAME)
                || mPluginFilterIds != null;
        // 第 286 轮：登记"这一屏看得见的"（插件的勾选页拿它当默认勾选；只认筛选，排序不算）
        boolean narrowed = !mQuery.trim().isEmpty() || mPluginFilterIds != null;
        java.util.List<String> viewIds = new java.util.ArrayList<String>(shown.size());
        for (File one : shown) viewIds.add(SAVE_ID.idOf(one));
        PluginPicks.noteView(Extensions.PAGE_SAVES, mSlot, viewIds, narrowed);
        // 表头那句同时承担"有几份"（标题里放不下多行，见 dialog_msav_list.xml 的实测）。
        // ★ 第 165 轮：第二段改成**计数**（「其中备份 N 份」），与另外三页同一个模板
        //   （「本槽 N <量词> · 一条事实」）—— 原来那里是一句操作提示「点一份看它是什么图」，
        //   四页里只有它是提示语。`-backup` 是**游戏自己**写的备份（`X.msav-backup.msav`），
        //   它们也是列表里的行 ⇒ 报出来能解释"为什么份数是存档的两倍"。
        int backups = 0;
        for (File f : mAll) {
            if (f != null && f.getName().contains("-backup")) backups++;
        }
        mHead.setText(Trans.get(SavesActivity.this, R.string.saves_list_head_fmt,
                mAll.length, backups));
        if (mFiltered != null) {
            mFiltered.setVisibility(filtering ? View.VISIBLE : View.GONE);
            if (filtering) {
                mFiltered.setText(Trans.get(SavesActivity.this, R.string.save_filtered_fmt,
                        mFiles.length, mAll.length));
            }
        }
        boolean empty = mFiles.length == 0;
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        // ⚠️ **不要**在空态时把列表容器 `GONE`（第 163 轮删掉的那一行）：
        //   另外三页（模组 / 地图 / 蓝图）都让它留着 —— 它只贡献 8dp 底部内边距，
        //   藏起来会让本页底部比那三页少一截（用户：「顶上和底下也不一样」）。
        if (empty) {
            // 第 164 轮：空态改用**页面专用**的 `saves_empty_fmt`（「槽「X」里还没有存档。」+ 一句提示），
            //   与另外三页同一模板（主句 + 提示行）。★ 仍然**不**复用 `export_no_save_fmt`：
            //   那句是导出失败时弹的，只有主句、不该带"档会出现在这里"。
            mEmpty.setText(filtering
                    ? Trans.get(SavesActivity.this, R.string.save_empty_filtered)
                    : Trans.get(SavesActivity.this, R.string.saves_empty_fmt, mSlot));
        }
    }

    /**
     * 后台把每份存档的**游戏内存档名**解出来存进 {@link #mNames}（第 157 轮）。
     *
     * <p>为什么不在输入时现解：`MsavMeta.read` 要开 zlib 流读头，几十份存档 × 每次按键
     * 会卡住输入；而这件事本来在补副标题那一趟里就要做一次。这里单独跑一趟、只跑一次，
     * 跑完如果用户正在筛（`mQuery` 非空）就**再筛一遍**（否则刚预热到的名字要等下次输入才生效）。</p>
     */
    private void warmSaveNames() {
        if (mNamesWarmed) return;
        mNamesWarmed = true;
        final File[] all = mAll;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean added = false;
                for (File f : all) {
                    if (Util.dead(SavesActivity.this)) return;
                    String k = f.getAbsolutePath();
                    if (mNames.containsKey(k)) continue;
                    String n = null;
                    try {
                        MsavMeta m = MsavMeta.read(f);
                        // ★ 用 `displayName()`（名字 → 地图名兜底），**不是** `name`：
                        //   真机实测这几份存档的 `name` 是空的，副标题里那个名字来自 `mapname`
                        //   ⇒ 拿 `name` 当搜索词等于又白做一遍（界面看得见、搜不到）。
                        // ★ 还要**去色码**：真名带 `[gold]…[red]…`（列表副标题也是这么去的）——
                        //   不去的话「sidestory lavacaster」这种跨色码的词就搜不到。
                        n = m == null ? null : Mods.stripColors(m.displayName());
                    } catch (Throwable ignored) {
                    }
                    mNames.put(k, n == null ? "" : n);
                    if (n != null && !n.isEmpty()) added = true;
                }
                if (!added) return;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SavesActivity.this)) return;
                        if (!mQuery.trim().isEmpty()) rebuildList();   // 名字刚到位 ⇒ 重筛一次
                    }
                });
            }
        }, "save-names").start();
    }

    /**
     * 删除存档（第 157 轮）：与地图 / 蓝图 / 模组**同一套** —— 勾选 → 确认 → **挪进中转站**。
     *
     * <p>★ 为什么现在允许删：原来这一页是"只读"（存档是用户的命根子）；改成删的
     * **落点是中转站**（可原样放回）之后，代价与"游戏里删档"差一个数量级 —— 与地图 / 蓝图
     * 那条出口是同一个道理。判据仍然是"只允许动 `saves/` 的直接子项"。</p>
     */
    private void promptDeleteSaves() {
        if (mAll == null || mAll.length == 0) {
            Toast.makeText(this, Trans.get(this, R.string.export_no_save_fmt, mSlot),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Data.gameAlive(this)) {
            alert(Trans.get(this, R.string.game_busy_title),
                    Trans.get(this, R.string.save_del_busy_msg));
            return;
        }
        final File[] all = mAll;
        String[] titles = new String[all.length];
        String[] subs = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            titles[i] = all[i].getName();
            String n = mNames.get(all[i].getAbsolutePath());
            subs[i] = (n == null || n.isEmpty()) ? Util.formatSize(all[i].length())
                    : Util.formatSize(all[i].length()) + " · 「" + n + "」";
        }
        BatchIo.pickMulti(this, R.string.batch_del_row,
                Trans.get(this, R.string.batch_del_head_fmt, all.length),
                titles, subs, null, null, R.string.batch_del_btn_fmt, new BatchIo.OnPick() {
                    @Override public void onPick(List<Integer> idx, CardStack card) {
                        final File kindDir = new File(Data.dirOf(SavesActivity.this, mSlot), "saves");
                        final java.util.List<File> files = new java.util.ArrayList<>();
                        for (Integer i : idx) files.add(all[i.intValue()]);
                        int can = 0, cant = 0;
                        for (File f : files) {
                            if (BatchIo.deletable(f, kindDir)) can++; else cant++;
                        }
                        if (can == 0) {
                            card.dismiss();
                            alert(Trans.get(SavesActivity.this, R.string.batch_del_row),
                                    Trans.get(SavesActivity.this, R.string.batch_del_skipped_fmt, cant));
                            return;
                        }
                        String msg = Trans.get(SavesActivity.this, R.string.batch_del_msg_fmt, can);
                        if (cant > 0) {
                            msg = msg + "\n\n" + Trans.get(SavesActivity.this,
                                    R.string.batch_del_skipped_fmt, cant);
                        }
                        card.replace(Trans.get(SavesActivity.this, R.string.batch_del_title), msg,
                                new CardStack.Btn(Trans.get(SavesActivity.this, R.string.cancel), new Runnable() {
                                    @Override public void run() { card.dismiss(); }
                                }),
                                new CardStack.Btn(Trans.get(SavesActivity.this, R.string.batch_del_ok), new Runnable() {
                                    @Override public void run() {
                                                BatchIo.DelReport rep = BatchIo.deleteAll(
                                                        SavesActivity.this, files, kindDir,
                                                        Trash.savesDir(SavesActivity.this),
                                                        new BatchIo.Deleter() {
                                                            @Override public File delete(
                                                                    android.content.Context c, File f,
                                                                    File trashDir) {
                                                                return SlotIo.deleteSaveToTrash(c, kindDir,
                                                                        f, trashDir);
                                                            }
                                                        });
                                                refresh();
                                                if (rep.failed == 0) {
                                                    Toast.makeText(SavesActivity.this,
                                                            Trans.get(SavesActivity.this,
                                                                    R.string.batch_del_done_fmt, rep.moved),
                                                            Toast.LENGTH_LONG).show();
                                                } else {
                                                    alert(Trans.get(SavesActivity.this, R.string.batch_del_row),
                                                            Trans.get(SavesActivity.this, R.string.batch_del_done_fmt, rep.moved)
                                                                    + "\n"
                                                                    + Trans.get(SavesActivity.this, R.string.batch_del_none_fmt, rep.failed));
                                                }
                                    }
                                }));
                    }
                });
    }

    /**
     * 「筛选与排序」对话框（与模组 / 地图 / 蓝图页同一个形状，也共用它那几条词）。
     * ★ 排序三档：名称（默认）/ 最新在前 / 大小（大的在前）——
     *   "最新在前"是这一页独有的（槽里的存档是真文件，有修改时间）。
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
                + Trans.get(SavesActivity.this, R.string.mods_filter_sort_name));
        acts.add(new Runnable() { @Override public void run() {
            mSort = ListQuery.SORT_NAME; clearPluginSort(); rebuildList();
        } });
        labels.add((mSort == ListQuery.SORT_TIME && mPluginSortKey == null ? tick : "")
                + Trans.get(SavesActivity.this, R.string.save_filter_sort_time));
        acts.add(new Runnable() { @Override public void run() {
            mSort = ListQuery.SORT_TIME; clearPluginSort(); rebuildList();
        } });
        labels.add((mSort == ListQuery.SORT_SIZE && mPluginSortKey == null ? tick : "")
                + Trans.get(SavesActivity.this, R.string.mods_filter_sort_size));
        acts.add(new Runnable() { @Override public void run() {
            mSort = ListQuery.SORT_SIZE; clearPluginSort(); rebuildList();
        } });
        // v25（第 280 轮）：插件注入的排序 / 筛选（四个列表页同一套；本页原来一行都没有）
        for (PluginPicks.Choice c : PluginPicks.choices(this, Extensions.PAGE_SAVES, mSlot,
                true, mPluginSortKey, mPluginSink)) {
            labels.add(c.label);
            acts.add(c.run);
        }
        // ── 排序到此为止，以下是筛选（Sheet 在这里画一条横向分割线）──
        final int gapAt = labels.size();
        for (PluginPicks.Choice c : PluginPicks.choices(this, Extensions.PAGE_SAVES, mSlot,
                false, mPluginFilterKey, mPluginSink)) {
            labels.add(c.label);
            acts.add(c.run);
        }
        labels.add(Trans.get(SavesActivity.this, R.string.mods_filter_reset));
        acts.add(new Runnable() {
            @Override public void run() {
                mSort = ListQuery.SORT_NAME;
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
    /** 一句话提示（与地图 / 蓝图 / 模组页**同名同形**；本页原来没有它，第 157 轮补） */
    private void alert(String title, String msg) {
        if (CardStack.leaf(title, msg)) return;
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
