package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 地图详情页（F21）：大图 + 元数据 + **四段可折叠的资源统计**（地矿 / 墙矿 / 可采地板 / 加成地板）
 * + 技术细节（依据）。
 *
 * <h3>口径（用户 2026-10-03 当场定的，见 REF §61.1）</h3>
 * **墙拆不掉 ⇒ 墙下的矿永久拿不到，必须排除**。所以每段的头号数字是"**能采到**"的格数，
 * 被墙压住的那些只在副行里报出来（不是丢掉，是分开说）。
 *
 * <h3>两层结构（§59 的纪律）</h3>
 * 第一层 = 每段一行摘要（"6 种 · 能采到 12,345 格"），点开才是每一种的明细；
 * 依据（用了哪张内容表、译文从哪来、谁挡住的、认不出什么、耗时）全放「技术细节」段。
 * 🔴 依据**不能删**（F4①d：判据要能看见依据），只是收起来。
 *
 * 🔴 根节点带 `@+id/root` + {@link Util#applySystemInsets}：targetSdk ≥ 35 的强制 edge-to-edge 下，
 *   漏了它整页从 y=0 画、被状态栏与顶栏整块盖住，而且**全程不报错**（第 81 轮的坑）。
 *
 * 🔴 **必须继承 {@link BaseActivity}**（2026-10-04 修）：深浅色设置的唯一生效点是它的
 *   `attachBaseContext`。本类原来写的是 `extends Activity` ⇒ 用户设了「浅色」/「深色」之后，
 *   本页仍按系统配色。见 {@link SlotActivity} 类注释。
 */
public class MapDetailActivity extends BaseActivity {
    /** 目标槽（**本页一切以它为准**：模组表、译文都按它取） */
    public static final String EXTRA_SLOT = "slot";
    /** 一页最多列几行（超出的折叠段里只报"还有 N 种"） */
    private static final int ROW_CAP = 15;

    private String mSlot;
    private Maps.Item mItem;
    private String mApkPath;
    private TextView mState;
    private LinearLayout mBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        mItem = Maps.fromExtra(this, getIntent());
        if (mSlot == null || mSlot.trim().isEmpty() || mItem == null) {
            finish();                       // 没槽名/没定位信息就退出，**绝不猜**
            return;
        }
        mSlot = mSlot.trim();
        setTitle(mItem.name());

        View root = getLayoutInflater().inflate(R.layout.activity_map_detail, null);
        Util.applySystemInsets(root);
        setContentView(root);

        ((TextView) root.findViewById(R.id.detail_text)).setText(mItem.detail(this));
        mState = (TextView) root.findViewById(R.id.stats_state);
        mBox = (LinearLayout) root.findViewById(R.id.stats_box);

        // 只有**本槽**的图能删（游戏自带 / 模组自带的在 APK 和模组包里面）
        if (mItem.from == Maps.FROM_SLOT && mItem.file != null) {
            View actions = root.findViewById(R.id.detail_actions);
            actions.setVisibility(View.VISIBLE);
            Util.bindAction(actions, R.id.row_detail_delete, R.drawable.ic_zip,
                    R.string.map_delete, R.string.map_delete_sub, new Runnable() {
                        @Override public void run() {
                            confirmDelete();
                        }
                    });
        }
        loadPreview(root);
        startStats();
    }

    /** 大图 + 版本 APK：两件事都要开 zip，**一起在后台做** */
    private void loadPreview(final View root) {
        new Thread(new Runnable() {
            @Override public void run() {
                String apk = null;
                try {
                    apk = Mods.targetsFor(MapDetailActivity.this, mSlot).apkPath;
                } catch (Throwable ignored) {
                }
                final String apkPath = apk;
                final android.graphics.Bitmap bm =
                        MapLoad.image(MapDetailActivity.this, mItem, apkPath, MapLoad.BIG);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        mApkPath = apkPath;
                        ImageView iv = (ImageView) root.findViewById(R.id.detail_image);
                        // ★ 出不了图就**保持 GONE**，不留空框（槽没指定版本 ⇒ 没配色表，最常见）
                        if (bm != null) {
                            iv.setImageBitmap(bm);
                            iv.setVisibility(android.view.View.VISIBLE);
                        } else {
                            iv.setVisibility(android.view.View.GONE);
                        }
                    }
                });
            }
        }, "map-detail-img").start();
    }

    /** 统计（读定义表 + 解整图 + 计数）——**整段都在后台**，界面先给一行"正在数…" */
    private void startStats() {
        mState.setText(R.string.stats_working);
        new Thread(new Runnable() {
            @Override public void run() {
                final MapStatsMods.Built built;
                try {
                    String apk = mApkPath;
                    if (apk == null) {
                        try {
                            apk = Mods.targetsFor(MapDetailActivity.this, mSlot).apkPath;
                        } catch (Throwable ignored) {
                        }
                    }
                    Map<String, String> labels = MapStatsMods.attrLabels(MapDetailActivity.this);
                    built = MapStatsMods.run(MapDetailActivity.this, mSlot, mItem, apk, labels);
                } catch (final Throwable t) {
                    // ★ 2026-10-04 修：原来把 `oneLine(t)`（异常消息，消息为空时回落成**类名**）
                    //   直接拼进"数不出来：…"给用户看 —— 文案纪律 ③ 明令不许甩类名。
                    //   异常原文归 logcat（本 ROM 滤掉应用 logcat，所以同时进崩溃/报告出口的思路
                    //   见 Util.dead 那段；这里至少不再污染界面）。
                    android.util.Log.w("MDTLauncher", "map stats failed", t);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(MapDetailActivity.this)) return;
                            mState.setText(R.string.stats_failed_plain);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        render(built);
                    }
                });
            }
        }, "map-detail-stats").start();
    }

    private void render(MapStatsMods.Built b) {
        if (b.error != null && !b.error.isEmpty()) {
            mState.setText(Trans.get(MapDetailActivity.this, R.string.stats_failed_fmt, b.error));
            return;
        }
        MapStats.Result r = b.result;
        boolean any = !r.ores.isEmpty() || !r.oreWalls.isEmpty() || !r.floors.isEmpty()
                || !r.bonuses.isEmpty();
        if (!any) {
            mState.setText(R.string.stats_none);
            return;
        }
        mState.setVisibility(View.GONE);
        // 两类矿的口径（用户 2026-10-04 定）：**地矿**（长在地板上，用**地钻**）用"能采到"口径；
        // **墙矿**（长在墙里，要用**墙钻**）只报总格数、不报遮挡（它本身就是墙；行名带「（墙）」不会同名）
        if (!r.ores.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_ore), r.ores, false);
        if (!r.oreWalls.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_orewall), r.oreWalls, true);
        // ★ 可采地板 / 加成地板：**同一批格子可能两段都出现**（沙地既掉沙又含油）
        //   ⇒ 两段口径必须一致（都是「能采 X 格」+ 副行「共 Y 格」），否则用户会看到同名两个数
        if (!r.floors.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_floor), r.floors, false);
        if (!r.bonuses.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_bonus), r.bonuses, false);
        addTech(b);
    }

    /**
     * 一段资源：摘要常驻，明细点开才出来。
     *
     * 🔴 **口径必须三段一致**（用户 2026-10-04 指出："沙子的数量怎么两个值"）：
     *   同一批格子可以**同时**算「可采地板」和「加成地板」（沙地既掉沙又含油），
     *   要是两段的头号数字一个用"能采到"、一个用"总格数"，用户就会看到**同名两个数**
     *   （沙子 13,976 / 沙子（含油）51,314）—— 看着像 bug，其实是两个量。
     *   ⇒ 现在：**每行头号数字一律「能采 X 格」**，副行给「共 Y 格」；摘要给「共 Y 格，其中能采到 X 格」。
     *   ⚠️ 唯一例外是「墙矿」段：它的行名带「（墙）」，与「地矿」段不会同名，
     *   而且墙矿本来就长在墙里（按"能采"算整段是 0），所以那一段头号数字用**总格数**，并在摘要里说明
     *   「长在墙里，需要用墙钻」。
     */
    private void addSection(String title, List<MapStats.Row> rows, boolean wallKind) {
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title)).setText(title);
        int total = MapStats.sum(rows, MapStats.SUM_TOTAL);
        int reach = MapStats.sum(rows, MapStats.SUM_REACH);
        String sum;
        if (wallKind) {
            sum = Trans.get(MapDetailActivity.this, R.string.stats_sum_wall_fmt, rows.size(), MapStatsMods.num(total));
        } else if (reach == total) {
            sum = Trans.get(MapDetailActivity.this, R.string.stats_sum_same_fmt, rows.size(), MapStatsMods.num(total));
        } else {
            sum = Trans.get(MapDetailActivity.this, R.string.stats_sum_fmt, rows.size(),
                    MapStatsMods.num(total), MapStatsMods.num(reach));
        }
        ((TextView) card.findViewById(R.id.stat_card_summary)).setText(sum);

        LinearLayout body = (LinearLayout) card.findViewById(R.id.stat_card_body);
        int n = Math.min(rows.size(), ROW_CAP);
        for (int i = 0; i < n; i++) body.addView(rowView(body, rows.get(i), wallKind));
        if (rows.size() > n) {
            TextView more = new TextView(this);
            more.setText(Trans.get(MapDetailActivity.this, R.string.stats_more_fmt, rows.size() - n));
            more.setTextSize(11f);
            more.setPadding(dp(14), dp(4), dp(14), dp(8));
            more.setTextColor(getResources().getColor(R.color.fg_muted));
            body.addView(more);
        }
        // ★ 点标题行展开/收起（复用 F1b 的既有做法；ScrollView 里**不能**塞 ListView，见 F3b）
        Util.bindExpandableCard(card, R.id.stat_card, R.id.stat_card_body, R.id.stat_card_chevron);
        mBox.addView(card);
    }

    /** 一行：名字 + 「能采 X 格」，副行说"总共多少 / 掉落什么 / 被什么挡住" */
    private View rowView(LinearLayout parent, MapStats.Row row, boolean wallKind) {
        View v = getLayoutInflater().inflate(R.layout.item_stat, parent, false);
        ((TextView) v.findViewById(R.id.stat_row_title)).setText(
                row.attrs == null || row.attrs.isEmpty() ? row.label
                        : Trans.get(MapDetailActivity.this, R.string.stats_row_attr_fmt, row.label, row.attrs));
        if (wallKind) {
            ((TextView) v.findViewById(R.id.stat_row_count))
                    .setText(Trans.get(MapDetailActivity.this, R.string.stats_row_total_fmt, MapStatsMods.num(row.total)));
        } else {
            ((TextView) v.findViewById(R.id.stat_row_count))
                    .setText(Trans.get(MapDetailActivity.this, R.string.stats_row_reach_fmt, MapStatsMods.num(row.reachable())));
        }
        StringBuilder sb = new StringBuilder();
        // ★ 有"拿不到"的部分时才另起一行报总数 —— 数字只有一个来源，用户不会再看到两个值
        if (!wallKind && row.total != row.reachable()) {
            sb.append(Trans.get(MapDetailActivity.this, R.string.stats_row_total_fmt, MapStatsMods.num(row.total)));
        }
        if (row.drop != null && !row.drop.isEmpty()) {
            cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_drop_fmt, row.drop));
        }
        // 🔴 「墙矿」段**不报遮挡**（用户 2026-10-04 问："什么叫做矿墙在墙下拿不到？"）：
        //   墙矿**本身就长在墙里**，说它"在墙下"是自相矛盾的措辞；而它的"能采"按地钻口径几乎恒为 0
        //   （实测全语料 3.2% 例外）。⇒ 那一段只用**总格数**，摘要写"长在墙里，需要用墙钻"
        //   （用户的术语：地矿/墙矿 ↔ 地钻/墙钻；**不要**写"普通钻头采不了"）。
        if (!wallKind) {
            if (row.buried > 0) {
                cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_buried_fmt, MapStatsMods.num(row.buried)));
            }
            if (row.loose > 0) {
                cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_loose_fmt, MapStatsMods.num(row.loose)));
            }
            if (row.unknown > 0) {
                cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_unknown_fmt, MapStatsMods.num(row.unknown)));
            }
        }
        TextView sub = (TextView) v.findViewById(R.id.stat_row_sub);
        if (sb.length() > 0) {
            sub.setText(sb.toString());
            sub.setVisibility(View.VISIBLE);
        }
        return v;
    }

    /**
     * 「依据」条目 → **当前界面语言**的整句（核心只给码与数字，见 {@link MapStats.Note}）。
     *
     * ★ 每一条都是**整句 + `%n$d`**，不拼前缀/尾巴（工程硬规矩）。
     */
    private String noteText(MapStats.Note nt) {
        if (nt == null) return "";
        switch (nt.code) {
            case MapStats.Note.VANILLA_TABLE:
                return Trans.get(MapDetailActivity.this, R.string.stats_note_vanilla_table_fmt, nt.n);
            case MapStats.Note.PATCH_CHANGED:
                return Trans.get(MapDetailActivity.this, R.string.stats_note_patch_changed_fmt, nt.n2, nt.n);
            case MapStats.Note.JSON_NAMES:
                return Trans.get(MapDetailActivity.this, R.string.stats_note_json_names_fmt, nt.n);
            case MapStats.Note.UNKNOWN_TYPE:
                return Trans.get(MapDetailActivity.this, R.string.stats_note_unknown_type_fmt, nt.n);
            default:
                return "";
        }
    }

    /** 依据：用了哪张内容表、译文从哪来、谁挡住的、认不出什么、耗时 —— **判据要能看见依据** */
    private void addTech(MapStatsMods.Built b) {
        MapStats.Result r = b.result;
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title)).setText(R.string.stats_section_tech);
        ((TextView) card.findViewById(R.id.stat_card_summary))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_tech_size_fmt, r.width, r.height,
                        MapStatsMods.num(r.cells)));
        LinearLayout body = (LinearLayout) card.findViewById(R.id.stat_card_body);

        List<String> notes = new ArrayList<>();
        // ★ 定义表那几句「依据」：核心只给**码 + 数字**（见 MapStats.Note），句子在这里按界面语言取 ——
        //   原来是核心直接拼中文，英文界面下会露出中文（2026-10-04 用户报的"还有没翻译的"）。
        for (MapStats.Note nt : r.notes) {
            String s = noteText(nt);
            if (!s.isEmpty()) notes.add(s);
        }
        if (b.modNote != null && !b.modNote.isEmpty()) notes.add(b.modNote);
        // ★ P4：译文那一层**单独一行**（不往上一句尾巴上拼）
        if (b.bundleNote != null && !b.bundleNote.isEmpty()) notes.add(b.bundleNote);
        line(body, Trans.get(MapDetailActivity.this, R.string.stats_tech_table_fmt, join(notes)));
        line(body, Trans.get(MapDetailActivity.this, R.string.stats_tech_names_fmt, join(b.namesNotes)));
        // ⚠️ 地图自带补丁那一条**已经在 `r.notes` 里了**（PATCH_CHANGED）——
        //    这里原来又单独加了一行，等于同一件事在界面上出现**两遍**（我 2026-10-04 引入的重复）。
        if (!r.blockers.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (MapStats.Row row : r.blockers) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(Trans.get(MapDetailActivity.this, R.string.stats_tech_count_fmt, row.label, row.total));
            }
            line(body, Trans.get(MapDetailActivity.this, R.string.stats_tech_blockers_fmt, sb.toString()));
        }
        if (!r.unknowns.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (MapStats.Unk u : r.unknowns) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(Trans.get(MapDetailActivity.this, R.string.stats_tech_count_fmt, u.name, u.total()));
            }
            line(body, Trans.get(MapDetailActivity.this, R.string.stats_tech_unknown_fmt, sb.toString()));
        } else {
            line(body, Trans.get(MapDetailActivity.this, R.string.stats_tech_unknown_fmt, Trans.get(MapDetailActivity.this, R.string.stats_tech_none)));
        }
        line(body, Trans.get(MapDetailActivity.this, R.string.stats_tech_time_fmt, b.millis));
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

    private void confirmDelete() {
        // ★ 2026-10-04：**先判游戏在不在跑**（与 MapFiles.deleteToTrash 里那道是同一个判据，
        //   这里只是把它提前到"问用户之前"）。原来只靠 `moved == null` 事后报错，
        //   而那句 `map_delete_fail` 还写着"或者这张图属于游戏 / 模组包里的"——
        //   本页的删除入口**只对本槽地图出现**（见 onCreate 里的 FROM_SLOT 判断），
        //   那个原因在任何情况下都不成立，等于把唯一可操作的那句稀释掉了。
        if (Data.gameAlive(this)) {
            alert(Trans.get(MapDetailActivity.this, R.string.game_busy_title), Trans.get(MapDetailActivity.this, R.string.map_delete_busy_msg));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.map_delete)
                .setMessage(Trans.get(MapDetailActivity.this, R.string.map_delete_confirm_fmt, mItem.name()))
                .setPositiveButton(R.string.map_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        File moved = MapFiles.deleteToTrash(MapDetailActivity.this,
                                mItem.file.getParentFile(), mItem.file,
                                MapFiles.trashDirOf(MapDetailActivity.this));
                        if (moved == null) {
                            alert(Trans.get(MapDetailActivity.this, R.string.map_delete),
                                    Trans.get(MapDetailActivity.this, R.string.map_delete_fail));
                            return;
                        }
                        Toast.makeText(MapDetailActivity.this,
                                Trans.get(MapDetailActivity.this, R.string.map_deleted_ok_fmt, mItem.name()),
                                Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);   // 告诉列表：图没了，回去重新清点
                        finish();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void cat(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s);
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String s : xs) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    /**
     * 弹窗入口（2026-10-04 第 86 轮补：本类原来没有它，弹窗都是就地 inline 建的）——
     * ★ 在这里挡"已销毁的 Activity"（理由见 {@link Util#dead}）。本类的弹窗既可能来自
     *   用户点击（实例当然活着），也可能来自后台任务收尾（地图统计失败那条），收口在一处最省心。
     */
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
