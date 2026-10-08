package io.mdt.launcher;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图详情页（F22）：从蓝图列表点进来。
 *
 * <h3>两段 + 技术细节（§59 的两层纪律）</h3>
 * <ol>
 *   <li><b>用到的方块</b>：按用量降序，一行一种（名字用内容表的译名 → 模组 bundle → 可读化）。</li>
 *   <li><b>缺哪些方块</b>：**这一页的价值所在** —— 游戏遇到认不出的方块是**静默**换成空气，
 *       只有在启动器里才看得见。判据 = 本槽已启用模组的内容表里没有这一行。</li>
 *   <li><b>技术细节</b>（依据，默认收起）：解析版本 / 声明尺寸 / 瓦片范围 / 越界 / 字典 /
 *       标签 / 自带名字表 / 末尾多余字节 / 用时 —— **依据不能删**（F4①d），只是收起来。</li>
 * </ol>
 *
 * ⚠️ 这一轮**没有预览图**（像素级预览要自研图集解析，REF §73 判为"大"；色块预览是下一步）
 *   ⇒ 界面上**不摆空框**（用户 2026-10-04 明确要求）。
 *
 * 🔴 必须继承 {@link BaseActivity}（深浅色唯一生效点）+ 根节点 `@+id/root` + insets
 *   （见 {@link BlueprintsActivity} 的类注释；自检 ㊱ / ㉛ 分别盯着这两条）。
 */
public class BlueprintDetailActivity extends BaseActivity {
    /** 目标槽（译文与内容表都按它取） */
    public static final String EXTRA_SLOT = "slot";
    /** 一段里最多列几行（超出的只报"还有 N 种"） */
    private static final int ROW_CAP = 15;

    private String mSlot;
    private Blueprints.Item mItem;
    private TextView mText, mState;
    private LinearLayout mBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        mItem = Blueprints.fromExtra(this, getIntent());
        if (mSlot == null || mSlot.trim().isEmpty() || mItem == null) {
            finish();                       // 没槽名 / 没定位信息就退出，**不猜**
            return;
        }
        mSlot = mSlot.trim();
        setTitle(mItem.displayName());

        View root = getLayoutInflater().inflate(R.layout.activity_blueprint_detail, null);
        Util.applySystemInsets(root);
        setContentView(root);

        mText = (TextView) root.findViewById(R.id.bp_detail_text);
        mText.setText(mItem.detail(this));
        mState = (TextView) root.findViewById(R.id.bp_detail_state);
        mBox = (LinearLayout) root.findViewById(R.id.bp_detail_box);

        // 只有**本槽**的蓝图能删（模组自带 / APK 里的那两份在包里，它们属于那个包）
        if (mItem.from == Blueprints.FROM_SLOT && mItem.file != null) {
            View actions = root.findViewById(R.id.bp_detail_actions);
            actions.setVisibility(View.VISIBLE);
            Util.bindAction(actions, R.id.row_bp_detail_delete, R.drawable.ic_trash,
                    R.string.bp_delete, R.string.bp_delete_sub, new Runnable() {
                        @Override public void run() {
                            confirmDelete();
                        }
                    });
        }
        startPreview();
        startStats();
    }

    /**
     * 预览大图（**色块档**，2026-10-06 第 116 轮）：解码瓦片 → 按 `BlockTable` 上色 → 落缓存。
     *
     * ★ 与「方块清点」**分两趟**：那一段要读 APK / 模组包（慢），而预览只读这一份文件
     *   ⇒ 分开发布，用户先看到图再看到统计（而不是等两件事都做完才出现）。
     * 🔴 出不了图**保持 GONE**（不留空框）；连不上槽 / 蓝图本身读不出来时也**不去猜**。
     */
    private void startPreview() {
        if (!mItem.ok()) return;                 // 读不出来的蓝图没有预览可言（顶部已经说了原因）
        new Thread(new Runnable() {
            @Override public void run() {
                // 像素级预览要**这个槽指向的版本 APK**（图集在里面）；拿不到就回落色块档
                String apk = null;
                try {
                    apk = Mods.targetsFor(BlueprintDetailActivity.this, mSlot).apkPath;
                } catch (Throwable ignored) {
                }
                final android.graphics.Bitmap bm = MschLoad.image(
                        BlueprintDetailActivity.this, mItem, apk, mSlot, MschLoad.BIG);
                if (bm == null) return;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(BlueprintDetailActivity.this)) return;
                        android.widget.ImageView iv = (android.widget.ImageView)
                                findViewById(R.id.bp_detail_image);
                        if (iv == null) return;
                        iv.setImageBitmap(bm);
                        iv.setVisibility(View.VISIBLE);
                    }
                });
            }
        }, "bp-preview").start();
    }

    /**
     * 删除：**挪进中转站，不硬删**（口径与地图那条一字不差）。
     *
     * ★ 先判游戏在不在跑（与 {@link BlueprintFiles#deleteToTrash} 里那道是同一个判据，
     *   这里只是把它提前到"问用户之前"）—— 免得用户点了确认才被告知不行。
     */
    private void confirmDelete() {
        if (Data.gameAlive(this)) {
            alert(Trans.get(BlueprintDetailActivity.this, R.string.game_busy_title), Trans.get(BlueprintDetailActivity.this, R.string.bp_import_busy_msg));
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(Trans.get(BlueprintDetailActivity.this, R.string.bp_delete_confirm_title, mItem.displayName()))
                .setMessage(R.string.bp_delete_confirm_msg)
                .setPositiveButton(R.string.bp_delete,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                File moved = BlueprintFiles.deleteToTrash(BlueprintDetailActivity.this,
                                        mItem.file.getParentFile(), mItem.file,
                                        BlueprintFiles.trashDirOf(BlueprintDetailActivity.this));
                                if (moved == null) {
                                    alert(Trans.get(BlueprintDetailActivity.this, R.string.bp_delete),
                                            Trans.get(BlueprintDetailActivity.this, R.string.bp_delete_fail));
                                    return;
                                }
                                Toast.makeText(BlueprintDetailActivity.this,
                                        Trans.get(BlueprintDetailActivity.this, R.string.bp_delete_ok_fmt, mItem.name()),
                                        Toast.LENGTH_SHORT).show();
                                setResult(RESULT_OK);      // 告诉列表：少了一份，回去重新清点
                                finish();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 方块清点（**整段在后台**）：内容表 + 译文都要读 APK / 模组包，不能放 UI 线程。
     * 界面先给一行「正在数方块…」，完成后换成两段卡片。
     */
    private void startStats() {
        if (!mItem.ok()) {
            // 蓝图本身就读不出来 ⇒ 详情顶部已经说了原因，这里不再摆一段空的
            mState.setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_stats_failed));
            return;
        }
        mState.setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_stats_working));
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Blueprints.Row> rows;
                final long ms;
                final int opaque;
                try {
                    long t0 = System.currentTimeMillis();
                    MapStatsMods.SlotContent sc = MapStatsMods.contentFor(
                            BlueprintDetailActivity.this, mSlot);
                    opaque = sc.opaque;
                    String apk = null;
                    try {
                        apk = Mods.targetsFor(BlueprintDetailActivity.this, mSlot).apkPath;
                    } catch (Throwable ignored) {
                    }
                    // 译名的两层来源与地图统计完全同源（APK 的语言包 + 模组的 *bundles/）
                    BundleNames bn = new BundleNames(
                            apk == null || apk.trim().isEmpty() ? null : new File(apk.trim()),
                            sc.bundle, MapStatsMods.attrLabels(BlueprintDetailActivity.this),
                            MapStats.bundleLang(MapStatsMods.bundleLocaleSuffix(BlueprintDetailActivity.this)),
                            Trans.get(BlueprintDetailActivity.this, R.string.stats_wall_name_fmt));
                    // ★ 与列表页同一个口径：把"bundle 声明过的方块名"当存在证据（真修 §77.5④）
                    rows = Blueprints.rows(mItem.msch, sc.table, bn, sc.bundleBlocks);
                    ms = System.currentTimeMillis() - t0;
                } catch (Throwable t) {
                    android.util.Log.w("MDTLauncher", "blueprint stats failed", t);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(BlueprintDetailActivity.this)) return;
                            mState.setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_stats_failed));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(BlueprintDetailActivity.this)) return;
                        render(rows, ms, opaque);
                    }
                });
            }
        }, "bp-stats").start();
    }

    private void render(List<Blueprints.Row> rows, long ms, int opaque) {
        mState.setVisibility(View.GONE);
        List<Blueprints.Row> used = new ArrayList<>(), missing = new ArrayList<>();
        int usedTiles = 0;
        for (Blueprints.Row r : rows) {
            if (r.missing) missing.add(r);
            else {
                used.add(r);
                usedTiles += r.tiles;
            }
        }
        // ⚠️ 内容表**不会是空的**（`MapStatsMods.contentFor` 至少给原版 447 行）⇒
        //    "全都是缺件"是真信号（典型：这份蓝图来自一个**当前没启用**的模组）——
        //    那种情况下更要说清楚，**不许**把它当成"我们表读不出来"而藏起来。
        if (!used.isEmpty()) {
            addSection(Trans.get(BlueprintDetailActivity.this, R.string.bp_section_blocks),
                    Trans.get(BlueprintDetailActivity.this, R.string.bp_blocks_sum_fmt, used.size(), MapStatsMods.num(usedTiles)),
                    used, false, false);
        }
        // ★ 本槽有"看不见方块"的模组时，这一段**特别标记 + 说清为什么可能认错**
        //   （判据 = `MapStatsMods.SlotContent.opaque`，见 MapStats.Pack#hasCode）
        //   ⚠️ 标题与摘要都走 `Blueprints` 里那两个函数（界面与自检**同一份格式化**，
        //   就地写 getString 会让"参数类型"漂移 —— 第 112 轮就是这么崩的，见 §77.6）
        boolean soft = !missing.isEmpty() && opaque > 0;
        if (!missing.isEmpty()) {
            int tiles = 0;
            for (Blueprints.Row r : missing) tiles += r.tiles;
            addSection(Blueprints.missingTitle(this, soft),
                    Blueprints.missingSummary(this, missing.size(), tiles, soft),
                    missing, true, soft);
        }
        addTech(ms, opaque);
    }

    /** 一段：摘要常驻，明细点开才出来（`card_stat_section` + `Util.bindExpandableCard`） */
    private void addSection(String title, String summary, List<Blueprints.Row> rows,
                            boolean missingKind, boolean soft) {
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title)).setText(title);
        ((TextView) card.findViewById(R.id.stat_card_summary)).setText(summary);
        LinearLayout body = (LinearLayout) card.findViewById(R.id.stat_card_body);
        if (missingKind) {
            TextView note = new TextView(this);
            note.setText(soft ? R.string.bp_blocks_missing_soft_note : R.string.bp_blocks_missing_note);
            note.setTextSize(12f);
            note.setLineSpacing(dp(2), 1f);
            note.setPadding(dp(14), dp(4), dp(14), dp(6));
            note.setTextColor(getResources().getColor(R.color.fg_muted));
            body.addView(note);
        }
        int n = Math.min(rows.size(), ROW_CAP);
        for (int i = 0; i < n; i++) body.addView(rowView(body, rows.get(i)));
        if (rows.size() > n) {
            TextView more = new TextView(this);
            more.setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_more_fmt, rows.size() - n));
            more.setTextSize(11f);
            more.setPadding(dp(14), dp(4), dp(14), dp(8));
            more.setTextColor(getResources().getColor(R.color.fg_muted));
            body.addView(more);
        }
        Util.bindExpandableCard(card, R.id.stat_card, R.id.stat_card_body, R.id.stat_card_chevron);
        mBox.addView(card);
    }

    /** 一行方块：显示名 + 「N 格」 */
    private View rowView(LinearLayout parent, Blueprints.Row row) {
        View v = getLayoutInflater().inflate(R.layout.item_stat, parent, false);
        ((TextView) v.findViewById(R.id.stat_row_title)).setText(row.label);
        ((TextView) v.findViewById(R.id.stat_row_count))
                .setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_row_tiles_fmt, MapStatsMods.num(row.tiles)));
        return v;
    }

    /** 依据（**判据要能看见依据**）：解析出来的原始事实，全部来自内核，一个字都不加工 */
    private void addTech(long ms, int opaque) {
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title)).setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_section_tech));
        Msch m = mItem.msch;
        // ⚠️ 摘要**不能**用 `mItem.blockKinds` —— 那是**列表页**扫的时候填进 Item 的派生字段，
        //   而详情页是**另一个进程内实例**（记录经 Intent 重建）⇒ 那边永远是 0
        //   （真机第一版就是"技术细节 · 0 种，共 79 格"，当场看出来）。这里用解析版本，短且确定。
        ((TextView) card.findViewById(R.id.stat_card_summary))
                .setText(Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_summary_fmt, m == null ? 0 : m.version));
        LinearLayout body = (LinearLayout) card.findViewById(R.id.stat_card_body);
        // ★ **依据**：本槽有几个"看不到方块"的模组（"可能认错"那个标记就是它撑起来的，
        //   依据不能删、但也不进第一层 —— 这里正是技术细节层该待的地方）
        if (opaque > 0) {
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_opaque_fmt, opaque));
        }
        if (m != null && m.ok) {
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_version_fmt, m.version));
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_declared_fmt, m.declaredWidth, m.declaredHeight));
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_box_fmt, m.minX, m.minY, m.maxX, m.maxY));
            if (m.outOfBounds > 0) {
                line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_oob_fmt, m.outOfBounds));
            }
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_dict_fmt, m.dict.size()));
            // ★ 一档⑥（2026-10-06）：把解析出来、界面上一直没显示的字段放出来。
            //   三行都是加分项 ⇒ 内容是空串就整行不出现（不摆空行）。
            //   ⚠️ 格式化在 `MschText` 里（界面与自检同一份），这里只负责"要不要摆这一行"。
            String dictNames = MschText.techDictNames(this, m.dictView());
            if (!dictNames.isEmpty()) line(body, dictNames);
            String renamed = MschText.techRemapped(this, m.remappedPairs());
            if (!renamed.isEmpty()) line(body, renamed);
            String rotated = MschText.techRotated(this, m.rotatedCount());
            if (!rotated.isEmpty()) line(body, rotated);
            StringBuilder keys = new StringBuilder();
            for (String k : m.tags.keySet()) {
                if (keys.length() > 0) keys.append(", ");
                keys.append(k);
            }
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_tags_fmt,
                    keys.length() == 0 ? Trans.get(BlueprintDetailActivity.this, R.string.stats_tech_none) : keys.toString()));
            line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_contentmap_fmt,
                    getString(m.hasContentMap ? R.string.bp_tech_yes : R.string.bp_tech_no)));
            if (m.leftover > 0) {
                line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_leftover_fmt, m.leftover));
            }
        } else if (m != null) {
            // 读不出来时：把**内核给的码 + 参数**翻成人话（`error` 原文只留给报告与自检）
            line(body, MschText.reason(this, m));
        }
        line(body, Trans.get(BlueprintDetailActivity.this, R.string.bp_tech_time_fmt, ms));
        Util.bindExpandableCard(card, R.id.stat_card, R.id.stat_card_body, R.id.stat_card_chevron);
        mBox.addView(card);
    }

    private void line(LinearLayout body, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(14), dp(3), dp(14), dp(3));
        tv.setTextColor(getResources().getColor(R.color.fg_muted));
        body.addView(tv);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 弹窗入口（**唯一**一处）—— ★ 在这里挡"已销毁的 Activity"（理由见 {@link Util#dead}）：
     * 本页的弹窗既可能来自用户点击，也可能来自后台清点任务收尾（统计失败那条）。
     */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
