package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 存档与备份（M3）。
 *
 * 心智模型（和桌面版 MDT 的「分类」对齐）：
 *   「槽」= 一套互相隔离的游戏数据（存档 / 设置 / mods）。一个槽 = 一个数据目录。
 *   多版本共享同一个数据根时**互相破坏**（探针实证），所以给版本分配不同的槽是唯一的隔离手段。
 *   ★ F0（2026-10-02）之后：**每个槽（含当前槽）恒定住在平级的 `slot-&lt;名&gt;`**，
 *     当前槽只是"被记下来、下次游戏启动会读它"的那一个（见 {@link Data#dirOf}）。
 *     改造前当前槽是「数据根本体 `files/`」，靠启动前改名交换换进换出；
 *     唯一的例外是 `build ≤ 146` 的远古包，它只认 `files/`，那一刻本体被临时改名过去。
 *
 * 本界面提供八件事：
 *   ① 槽的增 / 改名 / 删（改名与删除会同步修正「版本 → 槽」的分配）；
 *   ② 备份 / 恢复（口径 =「槽内容 − 排除名单」，与整槽导出共用；CAS 清单记 sha256，
 *      恢复前先边写边验 —— F19 前是白名单，见 {@link Data#SLOT_EXCLUDE}）；
 *   ③ 桌面版 `.msav` 迁移（gzip 流，直接放进目标槽的 `saves/`）；
 *   ④ **数据根体检** —— 找出游戏 copy 机制留下的 HUB 冗余副本并清理（见 Paths 头注释）；
 *   ⑤ **导出到共享存储（F6）** —— 单个 `.msav` 或整槽打包 zip，走 SAF，
 *      不需要任何存储权限（Android 11+ 下 `Android/data/` 对文件管理器不可见，
 *      这是用户唯一能自己把存档拿出来的途径，见 {@link Exporter}）；
 *   ⑥ **整槽 zip 导入（F6c）** —— ⑤ 的反向：把 zip 解包进指定槽，兼容游戏自己那份
 *      「设置 → 数据 → 导出数据」的格式（见 {@link SlotZip}）。
 *   ⑦ **F15：进 / 出入口对称** —— ③⑥ 原先只在存档页、⑤ 只在槽菜单，于是"在这个槽上
 *      导一份存档进来"要退回存档页重选一遍槽。现在**槽菜单里按介质成对**排：
 *      导入存档 / 导出存档 / 导入整槽 / 导出整槽（{@link #pickMsavForSlot} 等），
 *      从槽进来时目标已知、跳过选槽页。
 *   ⑧ **F15b：存档页不再有导入入口** —— ⑦ 之后 ③⑥ 与槽菜单重复，同一界面上两个入口
 *      （需求原文「这里UI重复，解决一下」）⇒ 存档页顶部只留「无槽语境」的两项
 *      （新建槽 / 数据根体检），**导入统一从槽菜单进**（目标已知，还少一次选槽）。
 *   ⑨ **F4②：克隆此槽** —— 把某个槽整份复制成一个新槽。本类只负责**界面**
 *      （{@link #promptClone} 问名字 / 前置挡住"源槽在跑""源槽是空的"；
 *      {@link #doClone} 进度框 + 报数），真正的实现是
 *      {@link Backup#cloneSlot} = 「备份源槽 + 恢复进新槽」，**零新原语**。
 *      刻意不另写一份目录树复制：否则"克隆"与"备份"会各有一套口径，
 *      不一致时不会有任何症状（症状只会是"克隆出来的槽少几个文件"这种半年后才发现的东西）。
 *      ★ 放在 `Backup` 而不是本类，是为了让**自检**与界面走同一份实现（可断言）。
 *
 * ⚠️ 对**当前槽**的写操作要求 `:game` 已退出（Data.gameAlive）。界面上会先提示并给出「结束游戏」。
 */
public class SavesActivity extends BaseActivity {




    private List<Data.Slot> mSlots;

    // F3b：槽列表容器（LinearLayout，条目代码挂载）+ 空态
    private ViewGroup mListContainer;
    private TextView mEmpty;

    // F1b：顶部状态卡（摘要行 + 可展开的完整路径）
    private TextView mSummary;
    private TextView mDetail;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i("MDTLauncher", "SavesActivity onCreate");
        buildUi();
    }
    /**
     * 进页面时清一遍数据根残留。
     *
     * ★ F20（2026-10-03 用户：「我们是不是应该给一些功能加上开关」）：本方法会**删文件**，
     *   而它原本既没有开关、也不说一声（第 57 轮把体检的可见入口删掉了）⇒ 给它加了开关。
     *   关掉后直接返回；想清的时候去设置页「自动清理残留 → 立即清理一次」。
     *   ⚠️ 它只清 {@link Data#REDUNDANT_NAMES} 那三项**自己的残留副本**（自检 ㉕ 守着这条边界）。
     */
    private void autoHealth() {
        if (!Config.get().autoCleanRedundant()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final Data.AutoClean a = Data.autoCleanRedundant(SavesActivity.this);
                if (a.cleaned > 0 && !isFinishing()) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!isFinishing()) refresh();
                        }
                    });
                }
            }
        }, "auto-health").start();
    }


    @Override
    protected void onResume() {
        super.onResume();
        // F0：`:game` 进程可能刚写过 `mdt-legacy-owner.txt`（远古包借住 files/ 或刚收回），
        //   本页 `dirOf` 依赖它 ⇒ 每次回前台先刷一次这个跨进程键，再重建列表。
        Data.reloadLegacyOwner();
        refresh();
            autoHealth();              // 静默清一遍数据根残留（无可见入口）
}

    // ── UI ────────────────────────────────────────────────────────────────

    /**
     * F1b：界面由"代码手搓"改为 inflate 布局（卡片风），与主界面同款骨架。
     * 逻辑（备份 / 恢复 / .msav / 体检）一行未动。
     */
    private void buildUi() {
        View root = getLayoutInflater().inflate(R.layout.activity_saves, null);

        // 顶部状态卡：整卡可点，展开完整路径
        mSummary = (TextView) root.findViewById(R.id.saves_summary);
        mDetail = (TextView) root.findViewById(R.id.saves_detail);
        Util.bindExpandableCard(root, R.id.saves_status_box,
                R.id.saves_detail, R.id.saves_chevron);

        Util.bindAction(root, R.id.row_new_slot, R.drawable.ic_add,
                R.string.saves_act_new_title, R.string.saves_act_new_sub, new Runnable() {
                    @Override public void run() { promptNewSlot(); }
                });
        // F15b：原先这里还有 row_msav / row_zip 两行（导入 .msav / 导入整槽 zip），**已移除** ——
        //   它们与槽菜单里的同两项重复。导入的落点必然是某个槽，从槽进去目标已知、
        //   还能跳过选槽页 ⇒ 统一收进槽菜单（见 slotOps）。原因与影响见 activity_saves.xml。

        // 槽列表：容器是 LinearLayout，条目在 rebuildList() 里全展开挂载
        // ★ 为什么不用 ListView —— 见 MainActivity.rebuildList() 的注释（F3b 根因）。
        mListContainer = (ViewGroup) root.findViewById(R.id.slot_container);
        mEmpty = (TextView) root.findViewById(R.id.slot_empty);
        mEmpty.setText(R.string.saves_empty);

        Util.applySystemInsets(root);
        setContentView(root);
    }

    private void refresh() {
        mSlots = Data.allSlots(this);
        rebuildList();
        if (mSummary != null) {
            File root = Data.dataRoot(this);
            mSummary.setText(getString(R.string.saves_summary_fmt,
                    mSlots.size(),
                    Data.currentSlot(this),
                    getString(Data.gameAlive(this) ? R.string.game_running
                                                   : R.string.game_not_running)));
            mDetail.setText(getString(R.string.saves_detail_fmt,
                    root == null ? "?" : root.getAbsolutePath(),
                    Backup.rootDir(this).getAbsolutePath()));
        }
    }

    /**
     * 重建槽列表（F3b）。
     * ★ 不用 ListView 的原因见 MainActivity.rebuildList()：ScrollView 内的 ListView
     *   拿到 UNSPECIFIED 高度约束，只按「padding + 单行高」估整体高度，
     *   列表后面条目的滚不出来。
     */
    private void rebuildList() {
        if (mListContainer == null) return;
        mListContainer.removeAllViews();
        int n = (mSlots == null) ? 0 : mSlots.size();
        if (mEmpty != null) mEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (n == 0) return;

        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < n; i++) {
            final Data.Slot s = mSlots.get(i);
            View v = inf.inflate(R.layout.item_slot, mListContainer, false);
            ((TextView) v.findViewById(R.id.slot_name)).setText(s.name);
            // 槽行的统计段统一走资源（原 Data.Slot.subtitle() 的硬编码串已删，2026-10-02）。
            // F5：再追加一行自动备份策略摘要 —— 否则用户没法一眼看出"这个槽到底会不会自动备份"。
            String meta = getString(R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
            Config.BackupPolicy bp = Config.get().backupPolicy(s.name);
            meta += bp.enabled
                    ? getString(R.string.policy_summary_fmt, bp.minMinutes, bp.maxBackups)
                    : getString(R.string.policy_off_suffix);
            ((TextView) v.findViewById(R.id.slot_meta)).setText(meta);
            v.findViewById(R.id.slot_badge).setVisibility(s.active ? View.VISIBLE : View.GONE);
            // 整行点击 = 槽操作菜单（原先是 ListView 的 onItemClick）
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { Intent _it = new Intent(SavesActivity.this, SlotActivity.class);
                            _it.putExtra(SlotActivity.EXTRA_SLOT, s.name);
                            startActivity(_it); }
            });
            mListContainer.addView(v);
        }
    }

    // ── 槽操作 ────────────────────────────────────────────────────────────



    private void promptNewSlot() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.new_slot_hint);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_slot_title)
                .setMessage(R.string.new_slot_msg)
                .setView(box)
                .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String err = Data.createSlot(SavesActivity.this, input.getText().toString());
                        if (err != null) alert(getString(R.string.create_failed), err);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }







    // ── 数据根体检 ────────────────────────────────────────────────────────

    // ── 小工具 ────────────────────────────────────────────────────────────




    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
