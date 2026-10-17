package io.mdt.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
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
 * <h3>口径（用户 2026-10-03 当场定的，见 REF §61；2026-10-16 墙矿那一段改过，见 §95）</h3>
 * **墙拆不掉 ⇒ 墙下的矿永久拿不到，必须排除**（这条是对**地矿 / 地板**说的）。所以每段的头号数字是
 * "**能采到**"的格数，被墙压住的那些只在副行里报出来（不是丢掉，是分开说）。
 * ★ **墙矿不一样**（用户 2026-10-16 定的邻域口径 + 对齐游戏 `Tile.wallDrop()`）：它本来就长在墙里，
 * "能采"按**上下左右有没有空位可摆墙钻**算（宿主方块可拆也算能采）；拿不到的那些叫
 * 「四周都是墙」——**不叫**"在墙下"（那句话对墙矿自相矛盾，见 {@link #rowView}）。
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

        // ★ 只有**本槽**的图能删（游戏自带 / 模组自带的在 APK 和模组包里面）
        // 🔴 2026-10-17 用户定调：「**删掉内部入口**，入口选择一个地图就是 json，多个就是 jsonl」
        //   ⇒ 本页**不再有**「导出统计」那一行 —— 导出统一从地图页进（勾 1 张 = `.json`，多张 = `.jsonl`）。
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
        mState.setText(Trans.get(MapDetailActivity.this, R.string.stats_working));
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
                            mState.setText(Trans.get(MapDetailActivity.this, R.string.stats_failed_plain));
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
        // ★ 2026-10-17：没矿**不再整页退出** —— 「可建造」那一段与矿无关（一张只有墙和空地的图
        //   照样有可建造格数）。那句提示保留在原位，说明为什么上面四段是空的。
        if (any) {
            mState.setVisibility(View.GONE);
        } else {
            mState.setText(Trans.get(MapDetailActivity.this, R.string.stats_none));
        }
        // 两类矿的口径（用户 2026-10-04 定）：**地矿**（长在地板上，用**地钻**）用"能采到"口径；
        // **墙矿**（长在墙里，要用**墙钻**）只报总格数、不报遮挡（它本身就是墙；行名带「（墙）」不会同名）
        if (!r.ores.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_ore), r.ores, false);
        if (!r.oreWalls.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_orewall), r.oreWalls, true);
        // ★ 可采地板 / 加成地板：**同一批格子可能两段都出现**（沙地既掉沙又含油）
        //   ⇒ 两段口径必须一致（都是「能采 X 格」+ 副行「共 Y 格」），否则用户会看到同名两个数
        if (!r.floors.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_floor), r.floors, false);
        if (!r.bonuses.isEmpty()) addSection(Trans.get(MapDetailActivity.this, R.string.stats_section_bonus), r.bonuses, false);
        addBuild(r);
        addCore(r);
        addTech(b);
    }

    /**
     * 「可建造」卡片（2026-10-17 用户点单）：一张图里能放**一般建筑**多少格。
     *
     * <p>数据是**六桶**（空地 / 拆掉后能建 / 墙和树 / 深液体 / 特殊地板 / 认不出）**恰好加满整张图**，
     * 所以这里不另报总格数 —— 技术细节那张卡的「地图 N × M，共 Z 格」已经说过一次。
     * 判据与游戏 `Build.validPlaceIgnoreUnits` 对齐，见 {@link MapStats.Buildable}。</p>
     *
     * <p>界面上是**五行**：「深液体」与「地板不让建」**合成一行**（用户 2026-10-17：「这俩合并下吧」）——
     * 行名是对象「放不了的地板」，副行按种类列（`深液体 2,026 格 · 太空地板 536 格`），
     * 两个桶的数一个不少、只是不各占一行。</p>
     *
     * <p>⚠️ 后两桶（特殊地板 / 认不出）平时是 0（原版只有 space/empty 是那种地板），
     * 有才显示 —— 免得每张图都挂两行 0。</p>
     *
     * <p>🔴 这一段的措辞被用户打回来**三次**（2026-10-17）：「这个地板不让建是啥啊」→「说法有点奇怪」→
     * 「你管单位会沉干嘛啊，我们统计的建筑」→「这俩合并下吧」⇒ 三条纪律：① 行名写**对象**、
     * 能拿到名字就把名字写出来；② 只说"这里放不了东西"，别用它解释单位会怎样（那是另一个统计）；
     * ③ **同一件事别拆成两行**（深液体与特殊地板都是"地板放不了"，合一行 + 副行列种类）。</p>
     */
    private void addBuild(MapStats.Result r) {
        MapStats.Buildable b = r.buildable;
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_section_build));
        ((TextView) card.findViewById(R.id.stat_card_summary))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_build_sum_fmt,
                        MapStatsMods.num(b.count())));
        LinearLayout body = (LinearLayout) card.findViewById(R.id.stat_card_body);
        buildRow(body, Trans.get(MapDetailActivity.this, R.string.stats_build_row_free), b.free, null);
        buildRow(body, Trans.get(MapDetailActivity.this, R.string.stats_build_row_cleared), b.cleared, null);
        buildRow(body, Trans.get(MapDetailActivity.this, R.string.stats_build_row_walled), b.walled, null);
        // ★ 深液体 + 特殊地板 = 一行「放不了的地板」，副行把种类逐个列出来
        //   （用户先后要求：行名说人话、别拿单位行为解释、这两行合并）。
        int noFloor = b.deep + b.floorBlocked;
        if (noFloor > 0) {
            StringBuilder sub = new StringBuilder();
            if (b.deep > 0) {
                sub.append(Trans.get(MapDetailActivity.this, R.string.stats_build_sub_deep_fmt,
                        MapStatsMods.num(b.deep)));
            }
            for (MapStats.Buildable.Kind k : b.floorKinds) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(Trans.get(MapDetailActivity.this, R.string.stats_build_sub_floor_fmt,
                        k.label, MapStatsMods.num(k.tiles)));
            }
            buildRow(body, Trans.get(MapDetailActivity.this, R.string.stats_build_row_nofoor),
                    noFloor, sub.length() == 0 ? null : sub.toString());
        }
        if (b.unknown > 0) {
            buildRow(body, Trans.get(MapDetailActivity.this, R.string.stats_build_row_unknown), b.unknown, null);
        }
        Util.bindExpandableCard(card, R.id.stat_card, R.id.stat_card_body, R.id.stat_card_chevron);
        mBox.addView(card);
    }

    /** 「可建造」卡片里的一行：左边是什么（`item_stat` 的样式），右边「N 格」，`sub` 可空 */
    private void buildRow(LinearLayout parent, String title, int n, String sub) {
        View v = getLayoutInflater().inflate(R.layout.item_stat, parent, false);
        ((TextView) v.findViewById(R.id.stat_row_title)).setText(title);
        ((TextView) v.findViewById(R.id.stat_row_count))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_build_count_fmt,
                        MapStatsMods.num(n)));
        if (sub != null && !sub.isEmpty()) {
            TextView subView = (TextView) v.findViewById(R.id.stat_row_sub);
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        parent.addView(v);
    }

    /**
     * 「核心区」卡片（2026-10-17 用户第二次点单：「再加一个统计可建造的核心区面积，以及实际最多能放多少个核心」，
     * 并当场纠正「**核心区是一个专门的地板啊**」）。
     *
     * <p>核心区 = 游戏里的 `core-zone` 地板（bundle 中文名就叫「核心区」），战役图用它标核心的降落区。
     * 所以卡片就两个数：**能建的面积**、**最多能摆几个核心**（按该星球最小的一种：塞普罗 core-shard 3×3 /
     * 埃里克尔 core-bastion 4×4，见 {@link MapStats.Core#size}）。</p>
     *
     * <p>⚠️ 地图上没有核心区（zone=0）就整张卡不出现 —— 免得大部分自定义图都挂一个 0。</p>
     */
    private void addCore(MapStats.Result r) {
        MapStats.Core c = r.core;
        // zone<=0：这张图没有核心区地板；skip：星球的核心读不到 / 核心不要求放核心区 ⇒ 这条统计不做
        if (c.zone <= 0 || c.skip) return;
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_section_core));
        ((TextView) card.findViewById(R.id.stat_card_summary))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_core_sum_fmt,
                        MapStatsMods.num(c.usable), MapStatsMods.num(c.max), String.valueOf(c.size)));
        LinearLayout body = (LinearLayout) card.findViewById(R.id.stat_card_body);
        // 整片面积只在"有格被占着"时才提（平时两数相等，说了就是重复）
        if (c.usable != c.zone) {
            buildRow(body, Trans.get(MapDetailActivity.this, R.string.stats_core_row_zone), c.zone, null);
        }
        // ★ 边长要写出来（塞普罗 3×3 / 埃里克尔 4×4）—— 2026-10-17 用户：「埃里克尔最小核心不是 3×3 的啊」
        //   括号里的**依据跟着来源换措辞**（存档里写的星球 / 没线索 ⇒ 取最小的一种）。
        int noteRes = c.basis == MapStats.Core.BASIS_PLANET ? R.string.stats_core_note_planet_fmt
                : R.string.stats_core_note_default_fmt;
        line(body, Trans.get(MapDetailActivity.this, noteRes, String.valueOf(c.size)));
        Util.bindExpandableCard(card, R.id.stat_card, R.id.stat_card_body, R.id.stat_card_chevron);
        mBox.addView(card);
    }

    /**
     * 一段资源：摘要常驻，明细点开才出来。
     *
     * 🔴 **口径必须四段一致**（用户 2026-10-04 指出："沙子的数量怎么两个值"）：
     *   同一批格子可以**同时**算「可采地板」和「加成地板」（沙地既掉沙又含油），
     *   要是两段的头号数字一个用"能采到"、一个用"总格数"，用户就会看到**同名两个数**
     *   （沙子 13,976 / 沙子（含油）51,314）—— 看着像 bug，其实是两个量。
     *   ⇒ 现在：**每行头号数字一律「能采 X 格」**，副行给「共 Y 格」；摘要给「共 Y 格，其中能采到 X 格」。
     *   ★ 2026-10-16 起「墙矿」段也并进这一条（原先它是例外、只报总格数）：
     *   用户定的邻域口径（四邻全墙 ⇒ 被墙遮挡）算出来的"能采 / 被墙遮挡"必须上屏，
     *   否则等于没算（首轮审计当场抓的）。它那行副行的措辞是**另一句**（`stats_row_walled_fmt`）——
     *   墙矿本来就长在墙里，套"在墙下，拿不到"是自相矛盾（2026-10-04 用户原话）。
     */
    private void addSection(String title, List<MapStats.Row> rows, boolean wallKind) {
        View card = getLayoutInflater().inflate(R.layout.card_stat_section, mBox, false);
        ((TextView) card.findViewById(R.id.stat_card_title)).setText(title);
        int total = MapStats.sum(rows, MapStats.SUM_TOTAL);
        int reach = MapStats.sum(rows, MapStats.SUM_REACH);
        String sum;
        if (wallKind) {
            sum = reach == total
                    ? Trans.get(MapDetailActivity.this, R.string.stats_sum_wall_fmt, rows.size(), MapStatsMods.num(total))
                    : Trans.get(MapDetailActivity.this, R.string.stats_sum_wall_split_fmt, rows.size(),
                    MapStatsMods.num(total), MapStatsMods.num(reach));
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

    /**
     * 一行：名字 + 「能采 X 格」，副行说"总共多少 / 掉落什么 / 被什么挡住"。
     *
     * <p>★ 2026-10-17 用户：「**括号标注一下液体可采**」—— 加成地板里那几块液体地板（深水 / 石油 / 冷冻液 /
     * 矿渣液 / 芳油）的收益是"上泵能抽"，所以括号里**先写「可抽 X」**、再接属性词
     * （`矿渣液（可抽 矿渣 · 高温）`）。液体名走 {@link MapStats.Names#liquid}（游戏 bundle 的
     * `liquid.&lt;id&gt;.name`），**不在这里写死**。</p>
     */
    private View rowView(LinearLayout parent, MapStats.Row row, boolean wallKind) {
        View v = getLayoutInflater().inflate(R.layout.item_stat, parent, false);
        String bracket = row.attrs == null ? "" : row.attrs;
        if (row.liquid != null && !row.liquid.isEmpty()) {
            // ★ 一律写液体名（`深水（可抽 水）`/`芳油（可抽 芳油）`）。
            //   🔴 2026-10-17 用户：「为什么矿渣是可抽矿渣，但芳油就是可抽液体啊」——
            //   我曾按"行名 == 液体名就省略"去重，结果同一列里两种写法混着出现；
            //   宁可重复一个词，也不要同一列两种格式（判据要**一致**，不是"聪明"）。
            String liq = Trans.get(MapDetailActivity.this, R.string.stats_bonus_liquid_fmt, row.liquid);
            bracket = bracket.isEmpty() ? liq : liq + " · " + bracket;
        }
        ((TextView) v.findViewById(R.id.stat_row_title)).setText(
                bracket.isEmpty() ? row.label
                        : Trans.get(MapDetailActivity.this, R.string.stats_row_attr_fmt, row.label, bracket));
        // ★ 头号数字**四段一律「能采 X 格」**（2026-10-16 起墙矿也一样，见 addSection 的注释）
        ((TextView) v.findViewById(R.id.stat_row_count))
                .setText(Trans.get(MapDetailActivity.this, R.string.stats_row_reach_fmt, MapStatsMods.num(row.reachable())));
        StringBuilder sb = new StringBuilder();
        // ★ 有"拿不到"的部分时才另起一行报总数 —— 数字只有一个来源，用户不会再看到两个值
        if (row.total != row.reachable()) {
            sb.append(Trans.get(MapDetailActivity.this, R.string.stats_row_total_fmt, MapStatsMods.num(row.total)));
        }
        if (row.drop != null && !row.drop.isEmpty()) {
            cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_drop_fmt, row.drop));
        }
        // 🔴 「墙矿」段的"拿不到"是**另一句话**（用户 2026-10-04 问："什么叫做矿墙在墙下拿不到？"）：
        //   墙矿**本身就长在墙里**，说它"在墙下"是自相矛盾的措辞 ⇒ 墙矿只说"四周都是墙，挖不到"。
        if (row.buried > 0) {
            cat(sb, wallKind
                    ? Trans.get(MapDetailActivity.this, R.string.stats_row_walled_fmt, MapStatsMods.num(row.buried))
                    : Trans.get(MapDetailActivity.this, R.string.stats_row_buried_fmt, MapStatsMods.num(row.buried)));
        }
        // ★★ 2026-10-16 用户的**优先级**口径（同族两条，`ref/29` 第 47 条）：只有"按谁算"，**不说**"被压住"——
        //    ① 墙矿那支：底下的墙自己也是墙矿（石墨墙一类）⇒「按底下的墙算」；
        //    ② 可采地板那支：上面有矿（`Tile.drop()` 里矿层压地板）⇒「按上面的矿算」。
        //    ⚠️ 与"拿不到"分开说：原因不同（`ref/29` 第 45/47 条：来因要拆开）。
        if (row.priority > 0) {
            cat(sb, Trans.get(MapDetailActivity.this, wallKind
                    ? R.string.stats_row_wall_priority_fmt : R.string.stats_row_floor_priority_fmt,
                    MapStatsMods.num(row.priority)));
        }
        if (row.loose > 0) {
            cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_loose_fmt, MapStatsMods.num(row.loose)));
        }
        if (row.unknown > 0) {
            cat(sb, Trans.get(MapDetailActivity.this, R.string.stats_row_unknown_fmt, MapStatsMods.num(row.unknown)));
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
        ((TextView) card.findViewById(R.id.stat_card_title)).setText(Trans.get(MapDetailActivity.this, R.string.stats_section_tech));
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
