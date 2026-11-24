package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.io.File;

/**
 * 槽的**二级页面**（导航重构，REF §56）——点一个槽进这里，而不是弹 11 项菜单。
 *
 * 🔴 本页**全程只认传进来的那个槽名**（`--es slot <名>`），
 *    **绝不"回落到当前槽"** —— 那正是 §52.5 第 3 条那类"残留目标槽"事故的温床。
 *
 * 本轮到位的：槽信息卡 + 「模组」入口（模组功能要合并进来的第一块）+ 其余三段的占位行。
 * 下一轮：把 11 项槽操作搬进来、存档/地图两段各自"点进一层"。
 *
 * 🔴 **必须继承 {@link BaseActivity}**（2026-10-04 修）：它是深浅色设置的**唯一生效点**
 *   （`attachBaseContext` → {@link ThemeMode#wrap}）。本类原来写的是 `extends Activity`
 *   ⇒ 用户把主题设成「浅色」/「深色」之后，主界面/存档/模组/设置/日志都跟着变，
 *   **只有槽页（以及地图页、地图详情）仍按系统配色** —— 同一应用两套配色。
 *   ⚠️ 症状是"看着不对但不报错"，自检当时对 `BaseActivity`/`ThemeMode` **零覆盖**
 *   ⇒ 新页面照抄这三页的类声明就会再次踩进来。
 */
public class SlotActivity extends BaseActivity {

    /** 第 160 轮：槽详情页挂插件动作区；插件看到的就是**这个槽**，不是"当前槽"。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_SLOT; }
    @Override protected String pluginSlot() { return mSlot; }
    /** 传进来的槽名（本页的一切都以它为准） */
    public static final String EXTRA_SLOT = "slot";

    private String mSlot;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            // 没给槽名就别装作有槽：直接退出（宁可回上一层，也不猜）
            finish();
            return;
        }
        mSlot = mSlot.trim();

        View root = getLayoutInflater().inflate(R.layout.activity_slot, null);
        // ★ 必须有：targetSdk ≥ 35 强制 edge-to-edge，不补 insets 的话整页从 y=0 画，
        //   被状态栏 + 顶栏盖住（真机实测：本页大标题完全看不见）。见 activity_slot.xml 注释。
        Util.applySystemInsets(root);
        setContentView(root);
        // ★ 顶栏就写「槽「xxx」」——原来顶栏是裸槽名、内容区又写一遍「槽「xxx」」大标题，
        //   同一句话出现两次（用户 2026-10-03 报的"UI 一堆问题"之一）。现在只留顶栏这一处。
        setTitle(Trans.get(SlotActivity.this, R.string.slot_page_title_fmt, mSlot));

        mInfo = (TextView) root.findViewById(R.id.slot_info);

        View modsRow = Util.bindActionValue(root, R.id.row_slot_mods, R.drawable.ic_mod,
                R.string.slot_page_mods, new Runnable() {
                    @Override public void run() {
                        // 模组页下一轮改成"接收槽参数"；现在先照旧进去（它会自己问槽）
                        startActivity(new Intent(SlotActivity.this, ModsActivity.class)
                                .putExtra(ModsActivity.EXTRA_SLOT, mSlot));   // 槽已定 ⇒ 不再问槽
                    }
                });
        mModsRow = modsRow;
        mModsSub = (TextView) modsRow.findViewById(R.id.act_sub);
        mModsSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_mods_sub));
        oneLineSub(mModsSub);
        mSavesRow = (View) root.findViewById(R.id.row_slot_saves);
        mSavesSub = (TextView) root.findViewById(R.id.row_slot_saves).findViewById(R.id.act_sub);
        // ★ 2026-10-06（第 115 轮第六批）：本行改成**开子页面**（与模组 / 地图 / 蓝图同一条）。
        //   原来是**弹窗菜单**（看每份存档… / 导出存档… / 导入存档…），用户：
        //   「存档页面也变成和模组/地图/蓝图这样的二级页面吧」⇒ 菜单整个搬进新页面，
        //   本行只负责"进那一页"（导入/导出/读不出来的那几份都在页面上）。
        Util.bindActionValue(root, R.id.row_slot_saves, R.drawable.ic_save,
                R.string.slot_page_saves, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(SlotActivity.this, SavesActivity.class)
                                .putExtra(SavesActivity.EXTRA_SLOT, mSlot));
                    }
                });
        View mapsRow = Util.bindActionValue(root, R.id.row_slot_maps, R.drawable.ic_map,
                R.string.slot_page_maps, new Runnable() {
                    @Override public void run() {
                        // ★ 开**子页面**（不是弹窗、也不是派发）：返回键就回到本页
                        //   （弹窗属于发起它的页面 ⇒ 关掉会落在"上一级"，用户反馈过）
                        startActivity(new Intent(SlotActivity.this, MapsActivity.class)
                                .putExtra(MapsActivity.EXTRA_SLOT, mSlot));
                    }
                });
        mMapsSub = (TextView) mapsRow.findViewById(R.id.act_sub);
        mMapsRow = mapsRow;
        mMapsSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_maps_sub));
        oneLineSub(mMapsSub);
        // ★ F22（第 109 轮）：蓝图 —— 与地图同一条路子（开子页面，不弹窗）。
        //   副标题后台填"本槽 N · 模组自带 M"，与地图行同一口径。
        View bpRow = Util.bindActionValue(root, R.id.row_slot_blueprints, R.drawable.ic_schematic,
                R.string.slot_page_blueprints, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(SlotActivity.this, BlueprintsActivity.class)
                                .putExtra(BlueprintsActivity.EXTRA_SLOT, mSlot));
                    }
                });
        mBpSub = (TextView) bpRow.findViewById(R.id.act_sub);
        mBpRow = bpRow;
        mBpSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_blueprints_sub));
        oneLineSub(mBpSub);
        // ★★ 「备份与恢复」**就地弹窗**（不再跳存档页）—— 实现搬到 SlotOps，两个页面共用一份。
        //   原来靠"派发 + REORDER_TO_FRONT"复用存档页的实现 ⇒ 点了会先**跳到存档页**再弹窗、
        //   关掉后还停在那一页（用户 2026-10-03 报的 UI 问题之一）。
        Util.bindAction(root, R.id.row_slot_ops, R.drawable.ic_backup,
                R.string.slot_page_ops, R.string.slot_page_ops_sub, new Runnable() {
                    @Override public void run() {
                        promptSlotOps();
                    }
                });
        mOpsRow = root.findViewById(R.id.row_slot_ops);
        // ★ 整槽导入导出**也就地**（SlotIo）—— 四条 SAF 链路现在都从本页发起
        Util.bindAction(root, R.id.row_slot_export, R.drawable.ic_zip,
                R.string.slot_page_export, R.string.slot_page_export_sub, new Runnable() {
                    @Override public void run() {
                        // ★ 第 425 轮：归档槽上这一行改成「导出归档备份」——
                        //   槽本体已经清空，走原来的整槽导出只会得到一个空包（还会被"空槽"那句拦下来）。
                        if (SlotArchive.isArchived(SlotActivity.this, mSlot)) {
                            Data.Slot s = findSlot();
                            if (s == null) { gone(); return; }
                            SlotIo.exportArchive(new CardStack(SlotActivity.this), SlotActivity.this, s);
                            return;
                        }
                        // ★ 第 265 轮：三选一 → 导出/分享确认原来是两层窗（每换一次 dim 就重播）。
                        //   现在同一张卡：菜单是根页，后面的确认页都往里 push。
                        final CardStack stack = new CardStack(SlotActivity.this);
                        stack.root(Trans.get(SlotActivity.this, R.string.slot_page_export),
                                CardStack.list(SlotActivity.this, new CharSequence[]{
                                        Trans.get(SlotActivity.this, R.string.slot_op_import_slot),
                                        Trans.get(SlotActivity.this, R.string.slot_op_export_slot),
                                        // ★ 第 141 轮：分享用整槽包（脱敏导出）。
                                        //   ⚠️ 新项**加在最后** —— 前两项的下标（0/1）一个都没动，
                                        //     所以任何"按下标点第几项"的老断言/肌肉记忆都不受影响。
                                        Trans.get(SlotActivity.this, R.string.slot_op_share_slot)},
                                        new CardStack.ItemClick() {
                                            @Override public void onClick(int w) {
                                                Data.Slot s = findSlot();
                                                if (s == null) { stack.dismiss(); gone(); return; }
                                                if (w == 0) {
                                                    stack.dismiss();        // 接着走系统文件选择器
                                                    SlotIo.importSlot(SlotActivity.this, s);
                                                } else if (w == 1) {
                                                    SlotIo.exportSlot(stack, SlotActivity.this, s);
                                                } else if (w == 2) {
                                                    SlotIo.shareSlot(stack, SlotActivity.this, s);
                                                }
                                            }
                                        }),
                                new CardStack.Btn(Trans.get(SlotActivity.this, R.string.cancel), new Runnable() {
                                    @Override public void run() { stack.dismiss(); }
                                }));
                        stack.show();
                    }
                });
        mExportRow = root.findViewById(R.id.row_slot_export);

        // F9 深链（第 379/380 轮）：外部递进来的包 —— 两种，全走**与本页菜单同一条**流程：
        //   · **整槽包**（判据 = `settings.bin`，见 `BatchIo.Probe#slotState`）⇒ 读包 → 确认框
        //     + 三个模式 → 解包；
        //   · **混合包**（模组 / 地图 / 蓝图 / 存档混装，`BatchIo.KIND_MIXED`）⇒ 四类各进各的目录。
        //   ⚠️ **靠 `EXTRA_KIND` 分**：两条都是"写这个槽"，但一条是整槽写入、一条是按类型落位，
        //     搞混是丢数据级别的错（键写错编译器不响）。
        final java.util.List<BatchIo.Doc> dl = DeepLink.docsOf(getIntent(), this);
        if (!dl.isEmpty()) {
            final int dk = getIntent() == null
                    ? -1 : getIntent().getIntExtra(DeepLink.EXTRA_KIND, -1);
            if (dk == BatchIo.KIND_MIXED) {
                BatchIo.runMixed(this, mSlot, dl, new Runnable() {
                    @Override public void run() { mHost.onSlotChanged(); }
                });
            } else {
                SlotIo.importSlotDoc(this, mSlot, dl.get(0), mHost);
            }
        }
    }

    /**
     * ★★ 把 SAF 的结果转发给 {@link SlotIo} —— **这个覆写少了，"导入/导出"选完文件就什么都不发生**。
     *
     * 原因：`startActivityForResult` 的结果**必然回到发起它的那个 Activity**；
     *   既然发起方已经从存档页改成槽页面，回调也就只能在这里收。
     *   ⚠️ 症状与 SlotsActivity 少 `onNewIntent` 那次一模一样（用户：「点了没反应」）。
     */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SlotIo.onActivityResult(this, requestCode, resultCode, data, mHost);
    }

    /** 这个槽已经不在了（被删 / 改名）：提示一句就退出去 */
    private void gone() {
        android.widget.Toast.makeText(this, Trans.get(SlotActivity.this, R.string.slot_page_gone_fmt, mSlot),
                android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * 找本页认的那个槽对象（**每次都现扫**：用户可能刚在别处改过它）。
     * ⚠️ 别缓存成字段长期用 —— 槽会被重命名 / 删除，缓存下来就会对着一个不存在的槽操作。
     */
    private Data.Slot findSlot() {
        for (Data.Slot x : Data.allSlots(this)) {
            if (x != null && mSlot.equals(x.name)) return x;
        }
        return null;
    }

    /** 槽内容变了（备份 / 恢复 / 删除之后）⇒ 刷新本页；槽要是没了就直接退出去 */
    private final SlotOps.Host mHost = new SlotOps.Host() {
        @Override public void onSlotChanged() {
            if (findSlot() == null) {
                finish();       // 槽被删 / 改名 ⇒ 停在一个不存在的槽页面上没有意义
                return;
            }
            refresh();
        }
    };

    /**
     * 「备份与恢复」的三项：**直接在本页弹对应的对话框**（实现见 {@link SlotOps}）。
     * ⚠️ `Data.Slot` 要在**点菜单时**现取（`findSlot()`）—— 用户可能刚在别处改过这个槽。
     *
     * ★ 第 158 轮（用户：「为什么对槽本身的操作在备份与恢复中啊」）：这个对话框原来有**六项**，
     *   里面混着「克隆此槽 / 重命名此槽 / 删除此槽」。现在后三项搬去**顶栏 ⋮**
     *   （`menu/slot.xml`）—— 它们动的是**槽本身**（身份 / 生死 / 副本），不是备份。
     *   本对话框只剩「还是一条备份该有的三件事」：备一份 / 看有哪些 / 定策略。
     *   ⚠️ 下标随之变成 0/1/2 —— 老代码里"点第 3 项 = 允许"这种记忆在这儿**不再成立**，
     *     所以这里**不按下标 switch 到不同方法**，而是走 {@link SlotOps#opsMenuItems} 那份唯一清单。
     */
    private void promptSlotOps() {
        final Data.Slot s = findSlot();
        if (s == null) { gone(); return; }
        final String[] labels = SlotOps.opsMenuLabels(this);
        // ★ 第 262 轮：这三级原来层层换窗（菜单弹窗 → 备份列表 → 动作/确认），每换一次 dim 就重播一遍。
        //   现在整条链在同一张卡里：菜单是根页，后面全是 push。
        final CardStack stack = new CardStack(this);
        stack.root(Trans.get(this, R.string.slot_page_ops),
                CardStack.list(this, labels, new CardStack.ItemClick() {
                    @Override public void onClick(int w) {
                        switch (w) {
                            case 0: SlotOps.backup(stack, SlotActivity.this, s, mHost); break;
                            case 1: SlotOps.backups(stack, SlotActivity.this, s, mHost); break;
                            case 2: SlotOps.backupPolicy(stack, SlotActivity.this, s, mHost); break;
                            default: break;
                        }
                    }
                }),
                new CardStack.Btn(Trans.get(SlotActivity.this, R.string.cancel), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
        stack.show();
    }

    /**
     * 槽页顶栏的 ⋮ 菜单（第 158 轮）：**对槽本身的操作** —— 重命名 / 克隆 / 删除。
     *
     * <p>为什么在顶栏而不占一行：见 `ref/29` §84「全局 / 低频入口放顶栏右上角」；
     * 页面每多一行，下面的列表就再往下推一格。三个都是低频 + 前两个不可逆
     * （删除会连带该槽的备份一起进中转站），放溢出菜单里还顺带少一分误触。</p>
     *
     * <p>⚠️ 标题必须用 {@link Trans} 覆盖一遍：`android:title` 只是无障碍 / 溢出菜单的标签，
     * 用户语言包改不到它（与 MainActivity 同一个理由）。</p>
     */
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.slot, menu);
        setMenuTitle(menu, R.id.action_slot_rename, R.string.slot_op_rename);
        setMenuTitle(menu, R.id.action_slot_clone, R.string.slot_op_clone);
        setMenuTitle(menu, R.id.action_slot_copy_settings, R.string.slot_op_copy_settings);
        setMenuTitle(menu, R.id.action_slot_delete, R.string.slot_op_delete);
        // ★ 第 425 轮：归档 / 解除归档。
        //   · 已归档 ⇒ 标题变「解除归档」，其余"动内容"的项留一个可见但点不动（改名 / 克隆 / 删除照旧可用）
        //   · 未归档 ⇒ 标题「归档」；默认槽（设置页那项）与"游戏在跑"时这一项**灰掉**
        //     （用户 2026-11-24：「默认不能归档」；灰掉的原因是点下去才知道，所以下面给 Info 文案兜底）
        android.view.MenuItem arc = menu.findItem(R.id.action_slot_archive);
        if (arc != null) {
            arc.setTitle(Trans.get(SlotActivity.this, mArchived
                    ? R.string.slot_archive_un : R.string.slot_archive));
            String why = mArchived ? null : SlotArchive.denyReason(this, mSlot);
            arc.setEnabled(mArchived || why == null);
            arc.setVisible(mArchived || why == null);
        }
        return true;
    }

    private void setMenuTitle(android.view.Menu menu, int id, int str) {
        android.view.MenuItem it = menu.findItem(id);
        if (it != null) it.setTitle(Trans.get(SlotActivity.this, str));
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id != R.id.action_slot_rename && id != R.id.action_slot_clone
                && id != R.id.action_slot_copy_settings && id != R.id.action_slot_delete
                && id != R.id.action_slot_archive) {
            return super.onOptionsItemSelected(item);
        }
        // 槽可能已经不在（别处删了 / 改了名）⇒ 与点行时同一条判据
        final Data.Slot s = findSlot();
        if (s == null) { gone(); return true; }
        if (id == R.id.action_slot_rename) SlotOps.renameSlot(this, s, mHost);
        else if (id == R.id.action_slot_clone) SlotOps.cloneSlot(this, s, mHost);
        else if (id == R.id.action_slot_copy_settings) {
            // ★ 第 425 轮：归档槽没有设置可复制（settings.bin 在归档快照里）
            if (SlotArchive.isArchived(this, s.name)) {
                android.widget.Toast.makeText(this, Trans.get(this, R.string.slot_copy_src_archived),
                        android.widget.Toast.LENGTH_LONG).show();
                return true;
            }
            SettingsCopyUi.open(this, s, mHost);
        } else if (id == R.id.action_slot_archive) {
            if (mArchived) SlotOps.unarchiveSlot(this, s, mHost);
            else SlotOps.archiveSlot(this, s, mHost);
        } else SlotOps.deleteSlot(this, s, mHost);
        return true;
    }

    // ★ 2026-10-03：这里的 `runOp` / `pickOps` / `opLabel` 三个方法**已删** ——
    //   它们是"把槽操作派发回存档页执行"的过渡机制（REF §56 第 4 步）。
    //   六项对话框操作已搬进 {@link SlotOps}、四条 SAF 链路已搬进 {@link SlotIo}，
    //   槽页面现在**全部就地执行** ⇒ 不再有任何派发（`EXTRA_SLOT_OP` 也没人发了）。

    private TextView mInfo;
    /** 模组行的副标题（后台扫完把条数写上去） */
    private TextView mModsSub;
    /** 存档 / 地图 / 蓝图三行的副标题（同样后台填） */
    private TextView mSavesSub, mMapsSub, mBpSub;
    /**
     * ★ 第 425 轮：归档时要**变灰**的六行（模组 / 存档 / 地图 / 蓝图 / 备份与恢复 / 整槽导出）。
     * 归档之后槽本体是空的，这些入口点进去只会看到"什么都没有" —— 索性灰掉，
     * 只留 ⋮ 的「解除归档 / 删除」与「导出归档备份」。
     */
    private View mModsRow, mSavesRow, mMapsRow, mBpRow, mOpsRow, mExportRow;
    /** 本页认的那个槽当前是不是归档状态（菜单项与灰化都看它） */
    private boolean mArchived;
    /**
     * 上一轮扫出来的「读不出来的存档」份数 —— 副标题与存档行的菜单项**共用这一个数**。
     * ★ 存在的意义是**别在点击时重新扫盘**：槽里几百份存档，扫一遍要百来毫秒，
     *   而那正好是"点一下要等一下"的观感；扫盘本来就在 {@link #refresh()} 的后台线程里做过。
     */
    private int mSavesBad;

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    /** 信息卡：这个槽是谁、多大、是不是当前槽、指向哪个游戏版本 */
    private void refresh() {
        Data.Slot s = null;
        for (Data.Slot x : Data.allSlots(this)) {
            if (x != null && mSlot.equals(x.name)) {
                s = x;
                break;
            }
        }
        boolean current = mSlot.equals(Data.currentSlot(this));
        Mods.Target t = Mods.targetsFor(this, mSlot);
        // ★★ 两行都走资源（2026-10-04 修）：原来这里是**写死的中文字面量**
        //    （`"文件 " + n + " · 占用 " + size`）—— 与存档页/选槽对话框那句
        //    `slot_entry_fmt`（`N 个文件 · X`）措辞不一致，同一个槽在两个界面两个样。
        //    第一行**直接复用 slot_entry_fmt**（显示规则只有一处实现）；
        //    第二行是"当前槽 / 目标游戏"四种组合，每种一句整句资源（不许拼前后缀，
        //    理由见 strings.xml 里那段注释：aapt2 会剥掉首尾空白）。
        StringBuilder sb = new StringBuilder();
        sb.append(s == null
                ? Trans.get(SlotActivity.this, R.string.slot_page_info_missing_fmt, mSlot)
                : Trans.get(SlotActivity.this, R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes)));
        sb.append('\n');
        boolean hasGame = t != null && !t.label.isEmpty();
        if (current) {
            sb.append(hasGame ? Trans.get(SlotActivity.this, R.string.slot_page_info_cur_fmt, t.label)
                              : Trans.get(SlotActivity.this, R.string.slot_page_info_cur_nogame));
        } else {
            sb.append(hasGame ? Trans.get(SlotActivity.this, R.string.slot_page_info_other_fmt, t.label)
                              : Trans.get(SlotActivity.this, R.string.slot_page_info_other_nogame));
        }
        if (mInfo != null) mInfo.setText(sb.toString());

        // ★ 第 425 轮：归档槽的表现 —— 信息卡补一句（槽本体已空、内容都在归档备份里），
        //   六行灰掉，整槽导出行换成「导出归档备份」。判据只认 `SlotArchive` 那一处。
        mArchived = SlotArchive.isArchived(this, mSlot);
        if (mArchived) {
            sb.append('\n').append(Trans.get(SlotActivity.this, R.string.slot_archived_rows));
            if (mInfo != null) mInfo.setText(sb.toString());
        }
        greyRow(mModsRow, mArchived);
        greyRow(mSavesRow, mArchived);
        greyRow(mMapsRow, mArchived);
        greyRow(mBpRow, mArchived);
        greyRow(mOpsRow, mArchived);
        // 整槽导出**不灰**：归档时它是「导出归档备份」，是这个状态下仅剩的两个入口之一。
        if (mExportRow != null) {
            TextView title = (TextView) mExportRow.findViewById(R.id.act_title);
            TextView sub = (TextView) mExportRow.findViewById(R.id.act_sub);
            if (title != null) title.setText(Trans.get(SlotActivity.this, mArchived
                    ? R.string.slot_archive_export : R.string.slot_page_export));
            if (sub != null) sub.setText(Trans.get(SlotActivity.this, mArchived
                    ? R.string.slot_archive_export_sub : R.string.slot_page_export_sub));
        }
        // 菜单项（归档 / 解除归档）随状态变 ⇒ 让系统重建一次
        invalidateOptionsMenu();
        // 三段的条数/摘要都在**后台**算好再写（扫模组要读包、扫地图要开 APK，别放 UI 线程）
        new Thread(new Runnable() {
            @Override public void run() {
                // ① 模组条数
                try {
                    final int n = Mods.scan(SlotActivity.this, mSlot).mods.size();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (mModsSub != null && !isFinishing()) {
                                mModsSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_mods_count_fmt, n));
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
                // ② 存档：份数 + 最近一份"这是什么存档" + **几份读不出来**
                //   口径唯一来源 = `MsavMeta.summarize`（它同时是「哪几份读不出来」那个弹窗的口径）。
                //   ★ 读不出来的份数必须说出来：F10 的第 ③ 条收益就是"开游戏前先知道哪份存档坏了"，
                //     而这件事原来只有**点开导出列表**才看得见（用户不进那个列表就永远不知道）。
                try {
                    File dir = new File(Data.dirOf(SlotActivity.this, mSlot), "saves");
                    final MsavMeta.Saves sm = MsavMeta.summarize(dir.listFiles());
                    final String sub = savesSubtitle(sm);
                    final int bad = sm.unreadableCount();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (mSavesSub == null || isFinishing()) return;
                            mSavesSub.setText(sub);
                            mSavesBad = bad;
                        }
                    });
                } catch (Throwable ignored) {
                }
                // ③ 地图：三个来源各多少（与地图页同一口径 Maps.scan / Maps.count）
                try {
                    Mods.Target t = Mods.targetsFor(SlotActivity.this, mSlot);
                    final java.util.List<Maps.Item> items = Maps.scan(SlotActivity.this, mSlot, t.apkPath);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (mMapsSub == null || isFinishing()) return;
                            mMapsSub.setText(Trans.get(SlotActivity.this, R.string.maps_counts_fmt,
                                    Maps.count(items, Maps.FROM_SLOT),
                                    Maps.count(items, Maps.FROM_GAME),
                                    Maps.count(items, Maps.FROM_MOD)));
                        }
                    });
                } catch (Throwable ignored) {
                }
                // ④ 蓝图：两个来源各多少（与蓝图页同一口径 Blueprints.scan / count）
                try {
                    final java.util.List<Blueprints.Item> bps =
                            Blueprints.scan(SlotActivity.this, mSlot);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (mBpSub == null || isFinishing()) return;
                            mBpSub.setText(Trans.get(SlotActivity.this, R.string.bp_head_fmt,
                                    Blueprints.count(bps, Blueprints.FROM_SLOT),
                                    Blueprints.count(bps, Blueprints.FROM_MOD)));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "slot-counts").start();
    }

    /**
     * 归档状态下把一行「变灰、点不动」。
     *
     * <p>★ 用 `setEnabled(false)` + 半透明，而不是 `setVisibility(GONE)`：
     *   用户要能看见这几件事**存在过**、只是现在不能动（以及为什么不能动 —— 信息卡那句）；
     *   整行消失会让人以为"这个版本没这些功能"。
     */
    private static void greyRow(View row, boolean grey) {
        if (row == null) return;
        row.setEnabled(!grey);
        row.setAlpha(grey ? 0.4f : 1f);
    }

    /**
     * 计数类副标题一律**单行**（模组 / 地图 / 蓝图的「条数」几行）。
     *
     * <p>★ 2026-11-23（第 418 轮，用户「进入某个槽管理页面时蓝图那里会闪一下」）：
     * 这几行的文案是「先占位、后台扫完再换成条数」，两句话长度不一样；
     * 一旦其中一句折成两行，整行高度就变，下面几行跟着上下跳 —— 看着像"闪一下"。
     * 计数句本来就短，单行 + 省略号最稳（存档那行不放这里：它要写"最近一份是什么"，
     * 有可能会自然地折行）。
     */
    private static void oneLineSub(TextView tv) {
        if (tv == null) return;
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    /**
     * 存档行的副标题（**唯一实现**：四种组合全在这里，调用点只贴结果）。
     *
     * ★ 为什么换整句而不是"拼接后缀"：`aapt2` 会剥掉字符串资源的**前导空白**，
     *   用 `" · ⚠ …"` 这种以空格开头的串去拼，真机上必然变成 `存档· ⚠`（本仓踩过同类坑）。
     * ★ 有读不出来的就直接把它说在脸上 —— 这是这一行的**主要用途**（"哪天存的"反而是次要的）。
     */
    private String savesSubtitle(MsavMeta.Saves sm) {
        String line = (sm.newestMeta != null && sm.newestMeta.ok)
                ? MsavText.shortLine(this, sm.newestMeta, true) : "";
        int bad = sm.unreadableCount();
        if (bad > 0) {
            return line.isEmpty()
                    ? Trans.get(SlotActivity.this, R.string.slot_page_saves_bad_fmt, sm.total, bad)
                    : Trans.get(SlotActivity.this, R.string.slot_page_saves_bad_recent_fmt, sm.total, line, bad);
        }
        return line.isEmpty()
                ? Trans.get(SlotActivity.this, R.string.slot_page_saves_count_only_fmt, sm.total)
                : Trans.get(SlotActivity.this, R.string.slot_page_saves_count_fmt, sm.total, line);
    }
}
