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
        mPaths = (TextView) root.findViewById(R.id.slot_paths);

        View modsRow = Util.bindActionValue(root, R.id.row_slot_mods, R.drawable.ic_mod,
                R.string.slot_page_mods, new Runnable() {
                    @Override public void run() {
                        // 模组页下一轮改成"接收槽参数"；现在先照旧进去（它会自己问槽）
                        startActivity(new Intent(SlotActivity.this, ModsActivity.class)
                                .putExtra(ModsActivity.EXTRA_SLOT, mSlot));   // 槽已定 ⇒ 不再问槽
                    }
                });
        mModsSub = (TextView) modsRow.findViewById(R.id.act_sub);
        mModsSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_mods_sub));
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
        mMapsSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_maps_sub));
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
        mBpSub.setText(Trans.get(SlotActivity.this, R.string.slot_page_blueprints_sub));
        // ★★ 「备份与恢复」**就地弹窗**（不再跳存档页）—— 实现搬到 SlotOps，两个页面共用一份。
        //   原来靠"派发 + REORDER_TO_FRONT"复用存档页的实现 ⇒ 点了会先**跳到存档页**再弹窗、
        //   关掉后还停在那一页（用户 2026-10-03 报的 UI 问题之一）。
        Util.bindAction(root, R.id.row_slot_ops, R.drawable.ic_backup,
                R.string.slot_page_ops, R.string.slot_page_ops_sub, new Runnable() {
                    @Override public void run() {
                        promptSlotOps();
                    }
                });
        // ★ 整槽导入导出**也就地**（SlotIo）—— 四条 SAF 链路现在都从本页发起
        Util.bindAction(root, R.id.row_slot_export, R.drawable.ic_zip,
                R.string.slot_page_export, R.string.slot_page_export_sub, new Runnable() {
                    @Override public void run() {
                        new android.app.AlertDialog.Builder(SlotActivity.this)
                                .setTitle(R.string.slot_page_export)
                                .setItems(new String[]{
                                        Trans.get(SlotActivity.this, R.string.slot_op_import_slot),
                                        Trans.get(SlotActivity.this, R.string.slot_op_export_slot)},
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                                Data.Slot s = findSlot();
                                                if (s == null) { gone(); return; }
                                                if (w == 0) SlotIo.importSlot(SlotActivity.this, s);
                                                else SlotIo.exportSlot(SlotActivity.this, s);
                                            }
                                        })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                });
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
     * 「备份与恢复」的六项：**直接在本页弹对应的对话框**（实现见 {@link SlotOps}）。
     * ⚠️ `Data.Slot` 要在**点菜单时**现取（`findSlot()`）—— 用户可能刚在别处改过这个槽。
     */
    private void promptSlotOps() {
        final Data.Slot s = findSlot();
        if (s == null) { gone(); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.slot_page_ops)
                .setItems(new String[]{
                        Trans.get(SlotActivity.this, R.string.slot_op_backup),
                        Trans.get(SlotActivity.this, R.string.slot_op_backups),
                        Trans.get(SlotActivity.this, R.string.slot_op_clone),
                        Trans.get(SlotActivity.this, R.string.slot_op_policy),
                        Trans.get(SlotActivity.this, R.string.slot_op_rename),
                        Trans.get(SlotActivity.this, R.string.slot_op_delete)},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                switch (w) {
                                    case 0: SlotOps.backup(SlotActivity.this, s, mHost); break;
                                    case 1: SlotOps.backups(SlotActivity.this, s, mHost); break;
                                    case 2: SlotOps.cloneSlot(SlotActivity.this, s, mHost); break;
                                    case 3: SlotOps.backupPolicy(SlotActivity.this, s, mHost); break;
                                    case 4: SlotOps.renameSlot(SlotActivity.this, s, mHost); break;
                                    case 5: SlotOps.deleteSlot(SlotActivity.this, s, mHost); break;
                                    default: break;
                                }
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ★ 2026-10-03：这里的 `runOp` / `pickOps` / `opLabel` 三个方法**已删** ——
    //   它们是"把槽操作派发回存档页执行"的过渡机制（REF §56 第 4 步）。
    //   六项对话框操作已搬进 {@link SlotOps}、四条 SAF 链路已搬进 {@link SlotIo}，
    //   槽页面现在**全部就地执行** ⇒ 不再有任何派发（`EXTRA_SLOT_OP` 也没人发了）。

    private TextView mInfo, mPaths;
    /** 模组行的副标题（后台扫完把条数写上去） */
    private TextView mModsSub;
    /** 存档 / 地图 / 蓝图三行的副标题（同样后台填） */
    private TextView mSavesSub, mMapsSub, mBpSub;
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
        if (mPaths != null) {
            java.io.File dir = Data.dirOf(this, mSlot);
            mPaths.setText(dir == null ? "" : dir.getAbsolutePath());
        }
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
