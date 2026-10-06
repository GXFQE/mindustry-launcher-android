package io.mdt.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
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
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 模组管理（F13 · **第一阶段：只读扫描**）。
 *
 * ── 这一页回答的三个问题（按价值排序，与 FLOWS F13 的目标一致）─────────────
 *  1. ★ **这个模组要求多高的游戏版本？** —— `minGameVersion` 与"指向本槽的游戏版本"比对。
 *     玩家最容易踩、最难自查的坑，而启动器**同时握着两边**，是唯一能提前警告的人。
 *  2. **游戏自己那个开关现在是开还是关？**（`settings.bin` 的 `mod-<内部名>-enabled`）
 *     —— ⚠️ **键不存在 = 启用**，不是禁用；读不到 `settings.bin` 时必须**说出来**，
 *     否则会把"读失败"显示成"全部启用"（最误导人的一种失败）。
 *  3. **为什么它没生效？** —— 照抄 `Mods.java:1156~1165` 的加载门**逐条**报，
 *     外加"元数据解析失败"和"依赖缺失/循环"这两条会**静默**吞掉模组的路径。
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
public class ModsActivity extends BaseActivity {

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
        View copyRow = Util.bindActionValue(root, R.id.row_mod_copy, R.drawable.ic_zip,
                R.string.mods_act_copy_title, new Runnable() {
                    @Override public void run() { pickCopyTarget(); }
                });
        mCopySub = (TextView) copyRow.findViewById(R.id.act_sub);
        Util.bindAction(root, R.id.row_mod_conflict, R.drawable.ic_health,
                R.string.mods_act_conflict_title, R.string.mods_act_conflict_sub, new Runnable() {
                    @Override public void run() { runConflictScan(); }
                });
        Util.bindAction(root, R.id.row_mod_filter, R.drawable.ic_settings,
                R.string.mods_act_filter_title, R.string.mods_act_filter_sub, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });
        Util.bindAction(root, R.id.row_mod_batch, R.drawable.ic_add,
                R.string.mods_act_batch_title, R.string.mods_act_batch_sub, new Runnable() {
                    @Override public void run() { pickBatch(); }
                });

        mContainer = (LinearLayout) root.findViewById(R.id.mod_container);
        mEmpty = (TextView) root.findViewById(R.id.mod_empty);
        // 第②项：搜索框（纯前端过滤，只在内存里筛，一个文件都不碰）
        mSearch = (EditText) root.findViewById(R.id.mod_search);
        mFiltered = (TextView) root.findViewById(R.id.mod_filtered);
        if (mSearch != null) {
            mSearch.setText(mQuery);                      // 转屏恢复：框里也要有那几个字
            mSearch.setSelection(mSearch.getText().length());
            mSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override public void afterTextChanged(android.text.Editable e) {
                    mQuery = e == null ? "" : e.toString();
                    rebuildList();
                }
            });
        }

        Util.applySystemInsets(root);
        setContentView(root);
    }

    // ── F13 第三阶段：导入模组包 / 跨槽复制 ────────────────────────────────

    /**
     * 挑一个模组包（`.zip` / `.jar`）。入口只有这一个 —— 落点就是**当前正在看的那个槽**。
     * ⚠️ MIME 不设限（`setType` 传通配）：各家文件管理器对 zip/jar 报的 MIME 五花八门
     *   （`.msav` 那条路已经栽过一次），限死会让用户选不中自己的文件。
     */
    private void pickModPackage() {
        // ★ 2026-10-04：**先判游戏在不在跑**（拦在 SAF 选择器之前）。导入是往槽的 mods/ 里**写**
        //   （目录形态模组还会把旧件挪去中转站），而工程里凡"写槽"的路径都有这道门禁
        //   （导入存档/整槽、备份、恢复、改设置、跨槽复制）；模组导入原来漏了。
        if (Data.gameAlive(this)) {
            alert(getString(R.string.game_busy_title), getString(R.string.mods_import_busy_msg));
            return;
        }
        sImportSlot = mSlot;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(
                Intent.createChooser(i, getString(R.string.chooser_pick_mod)), REQ_MOD_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MOD_IMPORT) return;
        // ★ 与 F15/F6 同一条纪律：无论成败**先清目标槽**，否则取消后残留的目标会被下一次接上
        final String slot = sImportSlot;
        sImportSlot = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || slot == null) {
            return;
        }
        final Uri uri = data.getData();
        // ★ 这里**必须**传 false（2026-10-04 澄清）：原来传的是一个恒为 false 的实例字段
        //   （`mImportOverwrite`，全工程找不到任何一处写 true ⇒ 死字段，已删）。
        //   "用户同意替换"这件事**不经过这里** —— 它是 doImport 的结果分支里
        //   发现同名后弹框、再直接 `doImport(..., true)` 走一遍（见那里），
        //   所以**不需要**跨 onActivityResult 存活，也就不该留一个恒假的字段。
        doImport(slot, uri, queryDisplayName(uri), false);
    }

    /** SAF 的显示名（拿不到就回落成"mod.zip" —— 至少让后面的扩展名检查说得通） */
    private String queryDisplayName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        String n = c.getString(idx);
                        if (n != null && !n.trim().isEmpty()) return n.trim();
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        return (last == null || last.trim().isEmpty()) ? "mod.zip" : last.trim();
    }

    /**
     * 真正导入：流拷进 `<槽>/mods/<名>.part` → 按游戏判据验 → 就位。
     * 放后台线程（模组包可能几十 MB），期间给 ProgressDialog。
     */
    private void doImport(final String slot, final Uri uri, final String displayName,
                          final boolean overwrite) {
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.mods_import_progress_title),
                getString(R.string.mods_import_progress_fmt, displayName, slot), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                Mods.PackResult pr = null;
                String err = null;
                File destDir = null;
                try {
                    destDir = new File(Data.dirOf(ModsActivity.this, slot), "mods");
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException(getString(R.string.mods_import_open_failed));
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
                    // ★ 2026-10-06（第 115 轮第四批）：弹窗正文翻白话（系统 errno 才翻），原文进日志
                    android.util.Log.w("MDTLauncher", "mods op failed", t);
                    err = Util.ioReason(ModsActivity.this, t);
                }
                final Mods.PackResult fpr = pr;
                final String fe = err;
                final File fdir = destDir;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fe != null) {
                            alert(getString(R.string.mods_import_failed), fe);
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
                                new AlertDialog.Builder(ModsActivity.this)
                                        .setTitle(R.string.mods_import_exists_title)
                                        .setMessage(getString(R.string.mods_import_exists_msg_fmt,
                                                displayName))
                                        .setPositiveButton(R.string.mods_import_replace,
                                                new DialogInterface.OnClickListener() {
                                                    @Override public void onClick(DialogInterface d, int w) {
                                                        doImport(slot, uri, displayName, true);
                                                    }
                                                })
                                        .setNegativeButton(R.string.cancel, null)
                                        .show();
                                return;
                            }
                            alert(getString(R.string.mods_import_failed), fpr.report(ModsActivity.this));
                            return;
                        }
                        showImportResult(fpr);
                        rescan();
                    }
                });
            }
        }, "mod-import").start();
    }

    /** 导入结果 + **版本兼容性提示**（F13 的头号卖点，在"刚装进去"这一刻就该说） */
    private void showImportResult(Mods.PackResult pr) {
        StringBuilder sb = new StringBuilder(pr.report(this));
        Mods.Target t = Mods.targetsFor(this, mSlot);
        if (pr.meta != null) {
            sb.append(getString(R.string.mods_detail_kind_fmt, kindOf(pr.meta))).append('\n');
            sb.append('\n');
            if (!t.any()) {
                sb.append(getString(R.string.mods_import_nogame)).append('\n');
            } else if (Mods.isAtLeast(t.build, t.revision, pr.meta.minGameVersion)) {
                sb.append(getString(R.string.mods_import_compat_ok_fmt,
                        t.label, pr.meta.minGameVersion)).append('\n');
            } else {
                sb.append(getString(R.string.mods_import_compat_bad_fmt,
                        t.label, pr.meta.minGameVersion)).append('\n');
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_import_ok)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.mods_close, null)
                .show();
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
            names[i] = s.name + (s.active ? getString(R.string.slot_current_suffix) : "")
                    + "  " + getString(R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_copy_pick_title)
                .setSingleChoiceItems(names, -1, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        confirmCopy(targets.get(w).name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 复制前的确认：把"复制多少项、其中多少会被跳过、复制的是什么"摊开。
     *
     * ★ 这里**没有**"覆盖"选项（`overwrite=false` 写死）：跨槽复制是**批量**动作，
     *   覆盖意味着一次可能替换掉目标槽里十几个模组 —— 那是"批量破坏"，不该做成一个顺手勾的框。
     *   要替换单个模组走「导入模组包」那条路（它会逐个确认）。**故意的不对称。**
     */
    private void confirmCopy(final String toSlot) {
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
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.mods_copy_confirm_title_fmt, toSlot))
                .setMessage(getString(R.string.mods_copy_confirm_msg_fmt,
                        mSlot, toSlot, count, Util.formatSize(bytes), clash))
                .setPositiveButton(R.string.mods_copy_title_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doCopy(from, to, toSlot);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doCopy(final File from, final File to, final String toSlot) {
        // 门禁：**只在动了"当前槽"时才拦** —— 游戏只读当前槽的 mods/，
        // 两个非当前槽之间互拷与它无关（别用一条过宽的规矩挡掉无害的操作）。
        final String cur = Data.currentSlot(this);
        if (Data.gameAlive(this) && (mSlot.equals(cur) || toSlot.equals(cur))) {
            alert(getString(R.string.mods_copy_done), getString(R.string.mods_copy_blocked));
            return;
        }
        int count = 0;
        File[] kids = from.listFiles();
        if (kids != null) {
            for (File f : kids) if (!Data.contentSkipped(f)) count++;
        }
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.mods_copy_done),
                getString(R.string.mods_copy_progress_fmt, count, toSlot), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                Mods.PackResult pr = null;
                String err = null;
                try {
                    pr = Mods.copyMods(ModsActivity.this, from, to, false, Mods.trashDirOf(ModsActivity.this));
                } catch (Throwable t) {
                    // ★ 2026-10-06（第 115 轮第四批）：弹窗正文翻白话（系统 errno 才翻），原文进日志
                    android.util.Log.w("MDTLauncher", "mods op failed", t);
                    err = Util.ioReason(ModsActivity.this, t);
                }
                final Mods.PackResult fpr = pr;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        alert(getString(R.string.mods_copy_done), fe != null ? fe : fpr.report(ModsActivity.this));
                        rescan();
                    }
                });
            }
        }, "mod-copy").start();
    }

    /** 弹窗三件套里的 alert（与 SavesActivity / MainActivity 同名同形，别各写一份）。
     *  ★ 2026-10-04 起在这里挡"已销毁的 Activity"：导入模组是几十秒的后台任务，
     *    失败弹窗落在转屏销毁的实例上会 BadTokenException 闪退（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.mods_close, null)
                .show();
    }

    /**
     * ★★ **模组间冲突体检**（2026-10-02 用户问：「有办法检查到 logicsugar 和 neon 同时加载吗」）。
     *
     * 判据三层（硬 → 软）：**内置检测**（A 的 dex 里含 B 的主类）/ **共同全局钩子** /
     * **简介提及**；再加上**游戏日志里提到这些模组的行**（那才是"谁真的接管了"的判据）。
     * ⚠️ 要读几 MB 的 dex ⇒ **按需**跑（后台线程 + ProgressDialog），不是每次刷新都跑。
     */
    private void runConflictScan() {
        final ProgressDialog pd = ProgressDialog.show(this,
                getString(R.string.mods_act_conflict_title),
                getString(R.string.mods_conflict_progress), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String text;
                try {
                    Mods.Conflict c = Mods.findConflicts(ModsActivity.this, mScan.mods,
                            new File(Data.dirOf(ModsActivity.this, mSlot), "last_log.txt"));
                    text = c.report(ModsActivity.this);
                    try {
                        Util.atomicWriteText(new File(Data.hubDir(ModsActivity.this),
                                "report-mods-conflict.txt"), text);
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    // ★ 2026-10-04：原来这里把异常直接拼进用户可见的正文
                    //   （`text = "冲突体检失败：" + t`）—— 异常原文归 logcat，
                    //   界面只给白话（文案纪律 ③：别把类名甩给用户）。
                    android.util.Log.w("MDTLauncher", "conflict scan failed", t);
                    text = getString(R.string.mods_conflict_failed);
                }
                final String ft = text;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        alert(getString(R.string.mods_conflict_title), ft);
                    }
                });
            }
        }, "mod-conflict").start();
    }

    /** 槽切换（只读视图；不改任何分配） */
    private void pickSlot() {
        final List<Data.Slot> slots = Data.allSlots(this);
        if (slots.isEmpty()) return;
        final String[] names = new String[slots.size()];
        int checked = 0;
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            names[i] = s.name + (s.active ? getString(R.string.slot_current_suffix) : "")
                    + "  " + getString(R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
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

    private void rescan() {
        mScan = Mods.scan(this, mSlot);
        mResolved = Mods.resolveDependencies(mScan.mods);
        mTarget = Mods.targetsFor(this, mSlot);
        rebuildList();
        fillHeader();
    }

    private void fillHeader() {
        if (mSummary != null) {
            if (!mTarget.any()) {
                mSummary.setText(getString(R.string.mods_summary_nogame_fmt,
                        mScan.mods.size(), mSlot));
            } else {
                mSummary.setText(getString(R.string.mods_summary_fmt,
                        mScan.mods.size(), mSlot, join(mTarget.versions)));
            }
        }
        if (mSlotSub != null) {
            mSlotSub.setText(getString(R.string.mods_row_slot_sub_fmt, mSlot));
        }
        if (mCopySub != null) {
            mCopySub.setText(getString(R.string.mods_act_copy_sub_fmt, mSlot));
        }
        if (mDetail != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.mods_detail_fmt,
                    mScan.modsDirPath(),
                    mScan.settingsNote,
                    getString(mScan.launchIdExists ? R.string.mods_crash_yes : R.string.mods_crash_no)));
            if (!mTarget.any()) {
                sb.append("\n\n").append(getString(R.string.mods_detail_no_target));
            } else {
                sb.append("\n\n").append(getString(R.string.mods_detail_target_fmt,
                        join(mTarget.versions), mTarget.label));

            }
            if (!mScan.ignored.isEmpty()) {
                sb.append("\n\n").append(getString(R.string.mods_ignored_fmt, mScan.ignored.size()));
                sb.append("：").append(join(mScan.ignored));
            }
            if (!mScan.orphanKeys.isEmpty()) {
                sb.append("\n\n").append(getString(R.string.mods_orphan_fmt, mScan.orphanKeys.size()));
                sb.append("：").append(join(mScan.orphanKeys));
            }
            if (!mScan.problems.isEmpty()) {
                sb.append("\n\n").append(getString(R.string.mods_problem_head));
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
                sb.append("\n\n").append(getString(R.string.mods_detail_depchain));
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
            sts.add(Mods.stateOf(m, mResolved, mTarget.build, mTarget.revision));
        }
        for (Mods.Info m : mScan.broken) {
            all.add(m);
            sts.add(null);                                // 读不出元数据 ⇒ 没有「游戏里的状态」
        }
        List<Mods.Info> shown = Mods.filterAndSort(all, mQuery, mSort, mOnlyProblems, mType,
                sts.toArray(new Mods.State[0]));
        boolean filtering = mOnlyProblems || mType != Mods.TYPE_ANY || !mQuery.trim().isEmpty()
                || mSort != Mods.SORT_NAME;
        if (mFiltered != null) {
            mFiltered.setVisibility(filtering ? View.VISIBLE : View.GONE);
            mFiltered.setText(getString(R.string.mods_filtered_fmt, shown.size(), all.size()));
        }
        if (mEmpty != null) {
            mEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            if (shown.isEmpty()) {
                mEmpty.setText(!filtering && all.isEmpty()
                        ? getString(R.string.mods_empty_fmt, mScan.modsDirPath())
                        : getString(R.string.mods_empty_filtered));
            }
        }
        LayoutInflater inf = getLayoutInflater();
        for (Mods.Info m : shown) {
            View v = inf.inflate(R.layout.item_mod, mContainer, false);
            // ★ 「坏包」的判据就是 metaError（Mods.Scan 就是这么分 mods / broken 的）
            bindRow(v, m, m.metaError != null);
            mContainer.addView(v);
        }
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
        final String[] items = {
                (mSort == Mods.SORT_NAME ? tick : "") + getString(R.string.mods_filter_sort_name),
                (mSort == Mods.SORT_STATE ? tick : "") + getString(R.string.mods_filter_sort_state),
                (mSort == Mods.SORT_SIZE ? tick : "") + getString(R.string.mods_filter_sort_size),
                (mOnlyProblems ? tick : "") + getString(R.string.mods_filter_only),
                (mType == Mods.TYPE_ANY ? tick : "") + getString(R.string.mods_filter_type_all),
                (mType == Mods.TYPE_JAVA ? tick : "") + getString(R.string.mods_filter_type_java),
                (mType == Mods.TYPE_JS ? tick : "") + getString(R.string.mods_filter_type_js),
                (mType == Mods.TYPE_DATA ? tick : "") + getString(R.string.mods_filter_type_data),
                getString(R.string.mods_filter_reset)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_filter_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            mSort = Mods.SORT_NAME;
                        } else if (which == 1) {
                            mSort = Mods.SORT_STATE;
                        } else if (which == 2) {
                            mSort = Mods.SORT_SIZE;
                        } else if (which == 3) {
                            mOnlyProblems = !mOnlyProblems;
                        } else if (which == 4) {
                            mType = Mods.TYPE_ANY;
                        } else if (which == 5) {
                            mType = Mods.TYPE_JAVA;
                        } else if (which == 6) {
                            mType = Mods.TYPE_JS;
                        } else if (which == 7) {
                            mType = Mods.TYPE_DATA;
                        } else {
                            mSort = Mods.SORT_NAME;
                            mOnlyProblems = false;
                            mType = Mods.TYPE_ANY;
                            mQuery = "";
                            if (mSearch != null) mSearch.setText("");
                        }
                        rebuildList();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void bindRow(View v, final Mods.Info m, boolean broken) {
        TextView title = (TextView) v.findViewById(R.id.mod_title);
        TextView sub = (TextView) v.findViewById(R.id.mod_sub);
        TextView badge = (TextView) v.findViewById(R.id.mod_badge);
        TextView warn = (TextView) v.findViewById(R.id.mod_warn);

        Mods.State st = broken ? Mods.State.UNSUPPORTED
                : Mods.stateOf(m, mResolved, mTarget.build, mTarget.revision);
        // ★ 徽标优先级：游戏自己的状态 > "安卓上会加载失败"（我们推出来的）> 启用。
        //   `willFailJavaLoad` 是**真会咬人**的一条（声明了 java、包里没有 classes.dex ⇒
        //   游戏抛异常后**整个模组被跳过**）—— 那时还显示绿色「启用」就是在骗人。
        String badgeText = broken ? getString(R.string.mods_badge_broken)
                    : ModsText.stateBadge(this, st);
        boolean warnBadge = broken || st != Mods.State.ENABLED;
        if (!broken && st == Mods.State.ENABLED && m.willFailJavaLoad()) {
            badgeText = getString(R.string.mods_badge_willfail);
            warnBadge = true;
        }

        title.setText(broken ? m.fileName : m.titleWithVersion());
        // 徽标用**短**形式（`ModsText.stateBadge`），句子形式（`ModsText.stateLabel`）留给详情弹窗与报告
        // —— 真机实测：`与当前游戏版本不兼容` 会把标题挤到折行。
        badge.setText(badgeText);
        if (!broken && m.duplicated) {
            badge.setText(getString(R.string.mods_dup_fmt, badgeText));
        }
        badge.setBackgroundResource(warnBadge ? R.drawable.badge_slot_warn : R.drawable.badge_slot);

        // ★ 副标题只放「类型 · 大小 · 形式」三样（用户 2026-10-03：「还有点乱」）：
        //   ① 文件名**不再重复** —— 它就在上面一行的标题里；
        //   ② 「联机要服务器同款」几乎每个模组都一样，12 行重复同一句就是纯噪声
        //      ⇒ 挪进详情弹窗（那里才需要判断）。
        sub.setText(kindShortOf(m) + "  ·  "
                + getString(R.string.mods_sub_fmt, Util.formatSize(m.bytes), formOf(m)));

        List<String> warns = new ArrayList<>();
        if (broken || m.metaError != null) {
            // ★ 走 metaReason()（异常类名/全限定名要翻成白话），不是原始 metaError（那是给排查看的）
            warns.add(getString(R.string.mods_warn_meta_fmt, ModsText.infoMetaReason(this, m)));
        } else {
            if (!Mods.isAtLeast(mTarget.build, mTarget.revision, m.minGameVersion)) {
                warns.add(getString(R.string.mods_warn_version_fmt,
                        m.minGameVersion, mTarget.label));
            }
            // ★ 门槛分两档（`Vars.java:53/55`）：Java 模组 154、**脚本 / 数据模组 136** ——
            //   原来只判了 Java 那一半 ⇒ 没写 `minGameVersion` 的 JS 模组会被游戏静默跳过，
            //   而这里连一句解释都没有（徽标却写着"不支持"）。真机对照见 Mods.gates 的注释。
            int minMajor = m.minMajor();
            int needMajor = m.isJava() ? Mods.MIN_JAVA_MOD_GAME_VERSION : Mods.MIN_MOD_GAME_VERSION;
            if (minMajor < needMajor && !m.legacyCompatible) {
                warns.add(minMajor <= 0
                        ? getString(R.string.mods_warn_noversion_fmt)
                        : getString(R.string.mods_warn_minmajor_fmt, minMajor, needMajor));
            }
            if (Mods.isBlacklisted(m.name, m.version)) {
                warns.add(getString(R.string.mods_warn_blacklist_fmt, m.name + ":" + m.version));
            }
            if (m.duplicated) warns.add(getString(R.string.mods_warn_dup));
            if (m.failed) warns.add(getString(R.string.mods_warn_failed));
            if (m.willFailJavaLoad()) warns.add(getString(R.string.mods_warn_willfail));
            if (m.noMainScript()) warns.add(getString(R.string.mods_warn_no_mainjs_fmt, m.jsCount));
            if (m.backslashEntries > 0) {
                warns.add(getString(R.string.mods_warn_backslash_fmt, m.backslashEntries));
            }
            if (st == Mods.State.DISABLED) {
                warns.add(getString(R.string.mods_warn_disabled));
            } else if (st == Mods.State.UNSUPPORTED) {
                // ⚠️ 不写「状态：与当前游戏版本不兼容」—— 那个状态**只有三个成因**
                //    （版本不够 / minMajor 太低 / 黑名单），上面三行各自都说过一次了。
                //    徽标已经写着状态，正文再复述一遍就是纯噪声（同一件事两处喊）。
            } else if (st != Mods.State.ENABLED) {
                // 依赖类的状态没有别的行会说，必须在正文里点名
                warns.add(getString(R.string.mods_warn_state_fmt, ModsText.stateLabel(this, st)));
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

    private static String joinLines(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        return sb.toString();
    }

    private String formOf(Mods.Info m) {
        if (m.directory) return getString(R.string.mods_form_dir);
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
            sb.append(getString(R.string.mods_kind_s_js_fmt, m.jsCount));
        }
        if (dex || cls) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(getString(dex ? R.string.mods_kind_s_java_dex
                    : R.string.mods_kind_s_java_nodex));
        }
        if (data) sb.append(getString(R.string.mods_kind_s_plus_res));
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
        if ((p & Mods.JS) != 0) sb.append(getString(R.string.mods_kind_js_fmt, m.jsCount));
        if ((p & Mods.JAVA_DEX) != 0) {
            if (sb.length() > 0) sb.append(" ＋ ");
            sb.append(getString(R.string.mods_kind_java_dex));
        }
        if ((p & Mods.JAVA_CLASS) != 0) {
            if (sb.length() > 0) sb.append(" ＋ ");
            sb.append(getString(R.string.mods_kind_java_class_fmt, m.classFiles));
        }
        if ((p & Mods.DATA) != 0) {
            if (sb.length() > 0) sb.append(" ＋ ");
            sb.append(getString(R.string.mods_kind_res));
        }
        if (sb.length() == 0) {
            sb.append(getString(R.string.mods_kind_data));
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
        new AlertDialog.Builder(this)
                .setTitle(m.title())
                .setMessage(userPart(m))
                .setPositiveButton(R.string.mods_close, null)
                .setNeutralButton(R.string.mods_detail_tech, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { showTech(m); }
                })
                .setNegativeButton(m.enabled ? R.string.mods_toggle_btn_off : R.string.mods_toggle_btn_on,
                        m.metaError == null && m.internalName != null
                                ? new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        confirmToggle(m);
                                    }
                                }
                                : null)
                .show();
    }

    /** 第一层：正常模组 7 行以内 —— 类型 / 简介 / 联机 / 作者 / 大小 · 开关状态 · 一句话结论 */
    private String userPart(final Mods.Info m) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.mods_detail_kind_fmt, kindShortOf(m))).append('\n');
        if (m.metaError != null) {
            // 读不出说明文件：能说的只有"读不出来 + 为什么"；门禁那几关**根本没有依据**，别摆
            sb.append(getString(R.string.mods_detail_size_fmt,
                    Util.formatSize(m.bytes), formOf(m))).append('\n');
            sb.append('\n').append(getString(R.string.mods_warn_meta_fmt,
                    ModsText.infoMetaReason(this, m)));
            return sb.toString();
        }
        // ★ 第 85 轮：模组**自己写的"这是什么"**原来从没上过界面 ——
        //   `description` 只被冲突检测拿去当"简介提及"的线索用（见 Mods.findMentions）。
        //   而"这模组干什么的"恰恰是用户挑模组时第一个想知道的。
        //   ⚠️ 只给一行：优先它声明的 `subtitle`（本来就是标语），没有才截 `description` 的第一行；
        //      全文仍在第二层「技术细节」的说明文件原文里（信息没删，只是分层）。
        String tag = tagline(m);
        if (!tag.isEmpty()) {
            sb.append(getString(R.string.mods_detail_desc_fmt, tag)).append('\n');
        }
        sb.append(getString(R.string.mods_detail_mp_fmt, getString(m.multiSafe()
                ? R.string.mods_mp_ok_long : R.string.mods_mp_need_long))).append('\n');
        if (m.author != null && !m.author.isEmpty()) {
            sb.append(getString(R.string.mods_detail_author_fmt, m.author)).append('\n');
        }
        sb.append(getString(R.string.mods_detail_size_fmt,
                Util.formatSize(m.bytes), formOf(m))).append('\n');
        sb.append('\n').append(getString(R.string.mods_detail_state_fmt,
                ModsText.stateLabel(this,
                        Mods.stateOf(m, mResolved, mTarget.build, mTarget.revision))));
        int fail = 0;
        for (Mods.Gate g : Mods.gates(this, m, mTarget.build, mTarget.revision)) {
            if (!g.pass) fail++;
        }
        sb.append('\n').append(fail == 0 ? getString(R.string.mods_detail_allok)
                : getString(R.string.mods_detail_somefail_fmt, fail));
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
    private void showTech(final Mods.Info m) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.mods_detail_file_fmt, m.fileName,
                Util.formatSize(m.bytes), formOf(m))).append('\n');
        if (m.metaName != null) {
            sb.append(getString(R.string.mods_detail_key_fmt, String.valueOf(m.internalName)))
              .append('\n');
        }
        if (m.repo != null && !m.repo.isEmpty()) {
            sb.append(getString(R.string.mods_detail_repo_fmt, m.repo)).append('\n');
        }
        if (m.settingsRepo != null && !m.settingsRepo.isEmpty()
                && !m.settingsRepo.equals(m.repo)) {
            sb.append(getString(R.string.mods_detail_repo_fmt,
                    getString(R.string.mods_detail_repo_override_fmt, m.settingsRepo))).append('\n');
        }
        // ★ 不再显示「代码入口：logicsugar.LogicSugarMod」那种**类名** ——
        //   用户看不懂，而下面「说明文件原文」里的 main 字段就是它。
        if (!m.dependencies.isEmpty()) {
            sb.append(getString(R.string.mods_detail_deps_fmt, join(m.dependencies))).append('\n');
        }
        if (!m.softDependencies.isEmpty()) {
            sb.append(getString(R.string.mods_detail_softdeps_fmt,
                    join(m.softDependencies))).append('\n');
        }
        if (m.metaError == null) {
            sb.append(getString(R.string.mods_detail_state_fmt,
                    ModsText.stateLabel(this,
                            Mods.stateOf(m, mResolved, mTarget.build, mTarget.revision)))).append('\n');
        }
        // ★ 只给**相对位置**：整条 `/storage/emulated/0/Android/data/io.mdt.launcher/slot-xxx/mods/...`
        //   要占四行，用户既看不懂也不需要（用户 2026-10-03：「这个页面也改下」）。
        String rel = m.file.getName();
        File slotDir = Data.dirOf(this, mSlot);
        if (slotDir != null) {
            String p = m.file.getAbsolutePath(), d = slotDir.getAbsolutePath();
            if (p.startsWith(d + "/")) rel = p.substring(d.length() + 1);
        }
        sb.append('\n').append(getString(R.string.mods_detail_path_fmt, rel)).append('\n');

        if (m.metaError == null) {
            sb.append('\n').append(getString(R.string.mods_detail_gate_head)).append('\n');
            sb.append(getString(R.string.mods_detail_gate_note_fmt,
                    mTarget.label.isEmpty() ? getString(R.string.mods_detail_gate_nogame)
                            : mTarget.label)).append('\n');
            for (Mods.Gate g : Mods.gates(this, m, mTarget.build, mTarget.revision)) {
                // ★ 通过 + 无补充说明 ⇒ **只画一行**（note == null 就是"没什么好说的"，
                //   约定见 Mods.gates 的注释）；失败才把原因摊在第二行。
                if (g.pass) {
                    sb.append(g.note == null
                            ? getString(R.string.mods_detail_gate_ok_fmt, g.label)
                            : getString(R.string.mods_detail_gate_ok_note_fmt, g.label, g.note));
                } else {
                    sb.append(getString(R.string.mods_detail_gate_fail_fmt, g.label, g.note));
                }
                sb.append('\n');
            }
            sb.append('\n').append(getString(R.string.mods_detail_settings_head)).append('\n');
            if (m.internalName != null) {
                // ★ 大白话 + 是/否：原来显示 `mod-logicsugar-enabled = true` 这种**键名 + 机器值**，
                //   用户看不懂（键名留在 Mods.enabledKey 那边的注释里，排查时再查）
                sb.append(getString(R.string.mods_detail_setting2_fmt,
                        getString(R.string.mods_setting_enabled),
                        getString(m.enabled ? R.string.mods_setting_yes
                                : R.string.mods_setting_no))).append('\n');
                sb.append(getString(R.string.mods_detail_setting2_fmt,
                        getString(R.string.mods_setting_failed),
                        getString(m.failed ? R.string.mods_setting_yes
                                : R.string.mods_setting_no))).append('\n');
                sb.append(getString(R.string.mods_detail_setting2_fmt,
                        getString(R.string.mods_setting_repo),
                        m.settingsRepo == null ? getString(R.string.mods_setting_repo_default)
                                : m.settingsRepo)).append('\n');
            }
            sb.append(m.settingsKnown ? "" : getString(R.string.mods_detail_settings_unreadable));
        }

        if (m.rawMeta != null && !m.rawMeta.isEmpty()) {
            int cap = 1500;
            String body = m.rawMeta.length() > cap ? m.rawMeta.substring(0, cap) + "…" : m.rawMeta;
            sb.append('\n').append(getString(R.string.mods_detail_meta_head,
                    Integer.valueOf(cap))).append('\n').append(body);
        }

        new AlertDialog.Builder(this)
                .setTitle(m.title())
                .setMessage(sb.toString())
                .setPositiveButton(R.string.mods_close, null)
                .show();
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
        final boolean on = !m.enabled;
        String msg = getString(R.string.mods_toggle_msg_fmt,
                getString(Data.gameAlive(this)
                        ? R.string.mods_toggle_game_alive : R.string.mods_toggle_game_off));
        // ★ 第④项：关之前先算「会连累谁」—— 现在游戏要到下次启动才显示缺依赖，
        //   那时用户早忘了自己关过什么（判据在 Mods.dependents 里，一处实现）。
        if (!on) {
            List<String> who = Mods.dependents(this, mScan.mods, m.internalName);
            if (!who.isEmpty()) {
                StringBuilder w = new StringBuilder(msg);
                w.append("\n\n").append(getString(R.string.mods_dep_warn_head)).append('\n');
                for (String s : who) w.append("· ").append(s).append('\n');
                w.append(getString(R.string.mods_dep_warn_tail));
                msg = w.toString();
            }
        }
        new AlertDialog.Builder(this)
                // 标题带上模组名：原来只有「关闭此模组」，看不出关的是哪一个
                .setTitle(getString(on ? R.string.mods_toggle_ask_on_fmt
                                        : R.string.mods_toggle_ask_off_fmt, m.title()))
                .setMessage(msg)
                .setPositiveButton(R.string.mods_toggle_do, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doToggle(m.internalName, on, m.title());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * ★ 批量启停（第③项）。⚠️ 这是**会写游戏数据**的操作 ⇒ 确认框里写清三件事：
     *   改多少个、改前会备份、游戏在跑就不许改（门禁在 {@link Mods#setEnabledAll} 里）。
     */
    private void pickBatch() {
        final String[] items = {getString(R.string.mods_batch_on), getString(R.string.mods_batch_off)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_batch_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        confirmBatch(which == 0);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void confirmBatch(final boolean on) {
        int n = mScan.mods.size();
        new AlertDialog.Builder(this)
                .setTitle(R.string.mods_batch_title)
                .setMessage(getString(on ? R.string.mods_batch_confirm_on_fmt
                        : R.string.mods_batch_confirm_off_fmt, n))
                .setPositiveButton(R.string.mods_toggle_do, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doBatch(on);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 真正落盘：{@link Mods#setEnabledAll} —— **一次读写**改完整槽，然后摊开结果 */
    private void doBatch(boolean on) {
        SettingsBin.Result r = Mods.setEnabledAll(this, mSlot, on, mScan.mods);
        showToggleResult(r, null, on, mScan.mods.size());
        rescan();
    }

    /** 真正落盘：{@link Mods#setEnabled}（门禁 + 备份 + 原子写 + 读回自检），然后把结果摊开 */
    private void doToggle(String internalName, boolean on, String shownName) {
        SettingsBin.Result r = Mods.setEnabled(this, mSlot, internalName, on);
        showToggleResult(r, shownName, on, 0);
        rescan();
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
    private void showToggleResult(final SettingsBin.Result r, String shownName,
                                  boolean on, int count) {
        final String title;
        final String msg;
        if (!r.ok) {
            title = getString(R.string.mods_toggle_failed);
            // ★ 原因走 userReason()（异常类名要翻成白话），不是原始的 error（那是给排查看的）
            msg = getString(R.string.mods_toggle_fail_msg_fmt, SettingsText.userReason(this, r));
        } else if (r.noop) {
            title = getString(R.string.mods_toggle_noop);
            msg = count > 0
                    ? getString(R.string.mods_batch_noop_msg)
                    : getString(R.string.mods_toggle_noop_msg_fmt,
                            shownName == null ? "" : shownName, toggleWord(on));
        } else {
            // 批量与单个的标题分开（"批量启停完成"比"已改动"更说得清这次干了什么）
            title = getString(count > 0 ? R.string.mods_batch_done : R.string.mods_toggle_done);
            // ★ 备份只在"原来就有设置文件"时才存在（新建那份没有原件可备份，见 SettingsBin.commit）
            String note = getString(r.backup != null ? R.string.mods_toggle_backup_note
                                                     : R.string.mods_toggle_verified_note);
            msg = (count > 0 ? getString(R.string.mods_batch_ok_fmt, count)
                             : getString(R.string.mods_toggle_ok_fmt, shownName, toggleWord(on)))
                    + note;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.mods_close, null);
        if (!r.noop) {
            // ★ 依据不能删：原始报告（体积 / 键数 / 写后自检 / 备份路径）留在第二层
            b.setNeutralButton(R.string.mods_toggle_detail, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    alert(getString(R.string.mods_toggle_detail_title), r.report());
                }
            });
        }
        b.show();
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
            for (Mods.Gate g : Mods.gates(ctx, m, build, rev)) {
                sb.append("      ").append(g.pass ? "[OK] " : "[NG] ").append(g.label)
                  .append("  —— ").append(g.note).append('\n');
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
