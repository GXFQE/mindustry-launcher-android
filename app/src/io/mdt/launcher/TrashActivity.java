package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * **中转站页面**（2026-10-05，第 102 轮）。
 *
 * ── 为什么要有这一页 ────────────────────────────────────────────────────
 *
 * 用户 2026-10-05：「这个中转站像是个半成品啊」。查下来症状是**只有数据层、没有界面层**：
 * <pre>
 *   · 地图被删 / 被同名覆盖、模组被同名覆盖时，旧件都被 `renameTo` 进了
 *     `&lt;hub&gt;/maps-trash/` / `&lt;hub&gt;/mods-trash/`（{@link MapFiles} / {@link Mods}）；
 *   · 但全工程**没有任何界面读过它们** ⇒ 那句「挪进中转站，不会直接删掉」在 App 里
 *     根本没有出口（用户只知道东西没了）；
 *   · 而且那两个目录在 `Android/data/&lt;包名&gt;/` 下面，Android 11+ 起文件管理器进不去 ——
 *     所谓"手工取回"对普通用户是不成立的（英文文案 2026-10-04 已经改成如实说，但没补出口）。
 * </pre>
 * 本页把出口补齐：**列表 / 放回去 / 彻底删除 / 清空 / 占用**，入口在设置页一行
 * （中转站是全局的、不属于任何一个槽，见 {@link SettingsActivity}）。
 *
 * ── 几条纪律 ────────────────────────────────────────────────────────────
 *
 * <pre>
 *   · 逻辑全在 {@link Trash}（纯逻辑），文案全在 {@link TrashText}，本类只做编排与确认；
 *   · 「放回去」**必须选目标槽**，默认勾来源槽（v2 名字里带着它；旧条目认不出就不勾）；
 *   · **同名回来问一声**（判据是 {@link Trash.Result#nameTaken}，不是文案 —— 门禁 SRC-01）；
 *     替换时旧的**再进一次中转站**，不是删掉（挪不删贯穿到底）；
 *   · 「彻底删除」与「清空」都是**不可逆**的 ⇒ 一律二次确认，且正文里写明份数与体积；
 *   · 后台任务收尾前一律 `Util.dead(this)`（转屏销毁旧实例后弹窗会 BadTokenException 闪退）。
 * </pre>
 *
 * 🔴 必须继承 {@link BaseActivity}（深浅色的唯一生效点），且根布局带 `@+id/root` +
 *    `Util.applySystemInsets` —— 两条都有自检钉着（㊱ / ㉛）。
 */
public class TrashActivity extends BaseActivity {

    /** 第 160 轮：中转站页也挂插件动作区。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_TRASH; }

    private TextView mHead;
    private TextView mEmpty;
    private android.view.ViewGroup mList;
    private MsavListAdapter mAdapter;
    /** 行单击（容器形态没有 `OnItemClickListener`，见 `onCreate`） */
    private MsavListAdapter.RowClick mRowClick;
    /** 当前列表（点第几项 ⇒ 拿哪一份） */
    private List<Trash.Item> mItems;
    /** 全部条目（筛选前的底稿；筛选只改显示，不动数据） */
    private List<Trash.Item> mAll;
    /** 「按类型筛选」那一行的副标题（显示当前选了哪一类） */
    private TextView mFilterSub;
    /** 当前分类；null = 全部 */
    private Trash.Kind mKind;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.trash_title);

        View root = getLayoutInflater().inflate(R.layout.activity_trash, null);
        // ★ 必须有（见 activity_trash.xml 的注释）：targetSdk ≥ 35 强制 edge-to-edge，
        //   不补 insets 的话表头与动作卡会被状态栏 + 顶栏整块盖住，而**不报任何错**。
        Util.applySystemInsets(root);
        setContentView(root);

        mHead = (TextView) root.findViewById(R.id.trash_head);
        mEmpty = (TextView) root.findViewById(R.id.trash_empty);
        // ★★ 2026-10-16（第 151b 轮）：列表改成整页 `ScrollView` 里手工挂载的容器（见 `activity_trash.xml` 头）
        mList = (android.view.ViewGroup) root.findViewById(R.id.trash_list);
        mHead.setText(Trans.get(TrashActivity.this, R.string.trash_scanning));

        Util.bindAction(root, R.id.row_trash_empty_all, R.drawable.ic_trash,
                R.string.trash_empty_all, R.string.trash_empty_all_sub, new Runnable() {
                    @Override public void run() { confirmEmptyAll(); }
                });

        // ★ 2026-10-25 用户：「能不能给中转站加一个分类」⇒ 按类型筛选。
        //   形态照抄模组页的「筛选」行（动作卡里一行 + 单选弹窗），不另造控件：
        //   副标题动态显示当前分类与份数，所以用 `bindActionValue`。
        View rowFilter = Util.bindActionValue(root, R.id.row_trash_filter, R.drawable.ic_select_all,
                R.string.trash_filter_title, new Runnable() {
                    @Override public void run() { pickFilter(); }
                });
        mFilterSub = (TextView) rowFilter.findViewById(R.id.act_sub);

        mRowClick = new MsavListAdapter.RowClick() {
            @Override public void onRow(int pos) {
                if (mItems != null && pos >= 0 && pos < mItems.size()) showActions(mItems.get(pos));
            }
        };
    }

    /** 每次回本页重扫一次（用户可能刚从地图页删了一张图过来） */
    @Override protected void onResume() {
        super.onResume();
        scan();
    }

    // ── 扫描 ──────────────────────────────────────────────────────────────

    /** 后台清点（要递归算目录体积 —— 目录形态的模组可以不小），回主线程刷界面 */
    private void scan() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Trash.Item> items;
                try {
                    // ★ 不再单独算 totalBytes：表头的份数与体积按**当前分类**从每项的 `bytes` 求和
                    //   （`render()` 里），两处各算一次必然会漂。
                    items = Trash.listAll(TrashActivity.this);
                } catch (Throwable ex) {
                    android.util.Log.w("MDTLauncher", "trash scan failed", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(TrashActivity.this)) return;
                            mHead.setText(Trans.get(TrashActivity.this, R.string.trash_scan_failed));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        mAll = items;
                        // 表头 / 列表 / 空态 / 筛选行统一由 render() 刷（份数按当前分类算）
                        render();
                    }
                });
            }
        }, "trash-page").start();
    }

    /**
     * 主线程：按当前分类**只改显示**，重画表头 / 列表 / 空态 / 筛选行副标题。
     *
     * <p>★ 分类是纯显示层（`mAll` 才是底稿）：切分类不走后台、不重扫、不改变任何一项的状态。
     *   份数与体积跟着**筛出来的那几份**走（表头说"共 3 份 · 4.2 MB"就得是这 3 份的 4.2 MB）。
     */
    private void render() {
        List<Trash.Item> all = (mAll == null) ? new java.util.ArrayList<Trash.Item>() : mAll;
        List<Trash.Item> shown = new java.util.ArrayList<Trash.Item>();
        long bytes = 0;
        for (Trash.Item it : all) {
            if (mKind == null || it.kind == mKind) {
                shown.add(it);
                bytes += it.bytes;
            }
        }
        mItems = shown;

        String[] titles = new String[shown.size()];
        String[] subs = new String[shown.size()];
        for (int i = 0; i < shown.size(); i++) {
            Trash.Item it = shown.get(i);
            titles[i] = TrashText.title(it);
            subs[i] = TrashText.line(TrashActivity.this, it);
        }
        mHead.setText(TrashText.head(TrashActivity.this, shown.size(), bytes));
        mAdapter = new MsavListAdapter(TrashActivity.this, titles, subs);
        mAdapter.fillInto(mList, mRowClick);

        // ★ 空态：整块**收掉**不放空框（用户 2026-10-04 的口径）。
        //   两种空要说清是哪种：站里本来就没东西 / 这一类里没东西（换个分类还能看到）。
        if (mEmpty != null) {
            boolean empty = shown.isEmpty();
            mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (empty) {
                mEmpty.setText(Trans.get(TrashActivity.this,
                        all.isEmpty() ? R.string.trash_empty : R.string.trash_filter_empty));
            }
        }
        if (mFilterSub != null) {
            mFilterSub.setText(Trans.get(TrashActivity.this, R.string.trash_filter_item_fmt,
                    TrashText.kindLabel(TrashActivity.this, mKind), shown.size()));
        }
    }

    /**
     * 选分类（2026-10-25 用户：「能不能给中转站加一个分类」）。
     *
     * <p>只列**真有东西**的那几类（外加「全部」）—— 列一个点进去空的分类等于骗人；
     * 顺序由 {@link TrashText#filterOrder()} 定（内容类在前、认不出的垫底）。
     * ★ 弹窗只用 `setSingleChoiceItems`、**不配正文**（HONOR 上 `setMessage` + 列表同现
     *   会让列表整块不渲染，`docs/flows/20` §20.5）。
     */
    private void pickFilter() {
        final List<Trash.Item> all = (mAll == null) ? new java.util.ArrayList<Trash.Item>() : mAll;
        final java.util.List<Trash.Kind> kinds = new java.util.ArrayList<Trash.Kind>();
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        labels.add(Trans.get(TrashActivity.this, R.string.trash_filter_item_fmt,
                Trans.get(TrashActivity.this, R.string.trash_filter_all), all.size()));
        kinds.add(null);
        for (Trash.Kind k : TrashText.filterOrder()) {
            int n = 0;
            for (Trash.Item it : all) if (it.kind == k) n++;
            if (n == 0) continue;
            labels.add(Trans.get(TrashActivity.this, R.string.trash_filter_item_fmt,
                    TrashText.kindLabel(TrashActivity.this, k), n));
            kinds.add(k);
        }
        if (labels.size() <= 1) {
            // 站里没东西：不给一个只有「全部」的弹窗
            Toast.makeText(TrashActivity.this, Trans.get(TrashActivity.this, R.string.trash_filter_nothing),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int checked = 0;
        for (int i = 0; i < kinds.size(); i++) {
            if (kinds.get(i) == mKind) { checked = i; break; }
        }
        new AlertDialog.Builder(TrashActivity.this)
                .setTitle(R.string.trash_filter_title)
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                mKind = kinds.get(which);
                                d.dismiss();
                                render();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── 一项的动作 ────────────────────────────────────────────────────────

    /**
     * 点开一项：第一层只给"该怎么判断"的东西（名字 + 类型/来源/体积/时间），
     * 三个出口 = 放回去 / 彻底删除 / 取消。**依据不删**（体积与来源就是依据）。
     *
     * ★ 认不出类型的（`Kind.OTHER`）**不给"放回去"** —— 没有地方放它，
     *   给了只会让用户点一下再吃一句错。
     * ★ **整槽不走"选槽"那一步**（`askSlot`）：回到哪个槽是它自己带着的，
     *   多问一层只会让人以为"要把这个槽塞进另一个槽"。
     */
    /** 当前那张中转站卡片（第 263 轮）：一趟"看一项 → 确认 → 结果"都在这张卡里换页。 */
    private CardStack mCard;

    /** 拿"当前这张卡"：没有就新开一张（并记下来），有就往里推一页。 */
    private CardStack open(CharSequence title, android.view.View body, CardStack.Btn... btns) {
        if (mCard == null || !mCard.isShowing()) {
            mCard = new CardStack(this);
            mCard.root(title, body, btns);
            mCard.show();
            return mCard;
        }
        return CardStack.show(mCard, this, title, body, btns);
    }

    private void showActions(final Trash.Item it) {
        String body = TrashText.line(this, it);
        if (it.kind == Trash.Kind.OTHER) {
            body = body + "\n\n" + Trans.get(TrashActivity.this, R.string.trash_reason_no_kind);
        } else {
            body = Trans.get(TrashActivity.this, R.string.trash_actions_msg_fmt, body);
        }
        // ★ 第 263 轮：动作框 → 确认框 → （同名）改名框原来是三张窗，每换一次 dim 都重播一遍。
        //   现在同一张卡里推页；按钮顺序与原来一致（取消 / 彻底删除 / 放回去）。
        final CardStack[] ref = new CardStack[1];
        java.util.List<CardStack.Btn> btns = new java.util.ArrayList<CardStack.Btn>();
        btns.add(new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
            @Override public void run() { ref[0].dismiss(); }
        }));
        btns.add(new CardStack.Btn(Trans.get(this, R.string.trash_delete_one), new Runnable() {
            @Override public void run() { confirmDeleteOne(it); }
        }));
        if (it.kind != Trash.Kind.OTHER) {
            btns.add(new CardStack.Btn(Trans.get(this, R.string.trash_restore), new Runnable() {
                @Override public void run() {
                    if (it.kind == Trash.Kind.SLOT) confirmRestoreSlot(it);
                    // 插件：放回去 = 回到 app_hub/plugins/<id>（**未启用**，用户自己按启用） —— 第 148 轮
                    else if (it.kind == Trash.Kind.PLUGIN) confirmRestorePlugin(it);
                    // 删掉的导入版本（2026-10-25）：APK 回 app_hub/import + 登记重加 + 名字/槽绑定恢复
                    else if (it.kind == Trash.Kind.VERSION) confirmRestoreVersion(it);
                    else askSlot(it);
                }
            }));
        }
        ref[0] = open(TrashText.title(it), CardStack.text(this, body),
                btns.toArray(new CardStack.Btn[0]));
    }

    /** 放回哪个槽：默认勾**来源槽**（旧条目认不出槽 ⇒ 不勾，让用户自己选） */
    private void askSlot(final Trash.Item it) {
        SlotOps.pickSlot(mCard, this, R.string.trash_restore_pick_title, it.slot, new SlotOps.SlotPick() {
            @Override public void onPicked(String slot) { confirmRestore(it, slot); }
        });
    }

    private void confirmRestore(final Trash.Item it, final String slot) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_restore_confirm_title),
                CardStack.text(this, Trans.get(this, R.string.trash_restore_confirm_fmt,
                        TrashText.title(it), slot)),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.trash_restore), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        doRestore(it, slot, false);
                    }
                }));
    }

    /** 整槽：放回去 = 回到**它自己那个槽名**（不选槽；名字被占时下一步会问"换个名字"） */
    private void confirmRestoreSlot(final Trash.Item it) {
        final String name = TrashText.title(it);
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_restore_confirm_title),
                CardStack.text(this, Trans.get(this, R.string.trash_slot_restore_confirm_fmt, name)),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.trash_restore), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        doRestore(it, name, false);
                    }
                }));
    }

    /**
     * 同名回来问一声：**替换 / 取消**（第 86 轮那条"同名存档导入必须先问"的同族）。
     * 替换 = 旧的**再进一次中转站**（不是删掉），所以这一问不会丢东西 —— 但用户仍要知情。
     *
     * ⚠️ 按钮文案复用 `map_overwrite_ok`（「替换」/ "Replace"）：同一个词，不另写一条重复资源。
     */
    private void askOverwrite(final Trash.Item it, final String slot) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_restore_overwrite_title),
                CardStack.text(this, Trans.get(this, R.string.trash_restore_overwrite_fmt,
                        TrashText.title(it), slot)),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.map_overwrite_ok), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        doRestore(it, slot, true);
                    }
                }));
    }

    /**
     * 整槽的"名字被占"出口：**换个名字放回去**（输入框），而不是"替换掉那个槽"。
     *
     * ★ 为什么不给"替换"（与地图/存档那条刻意不同）：那等于**一次删掉另一个完整的槽**
     *   （几十 MB、里面有用户的存档），而它跟"我正在恢复的这个槽"毫无关系；
     *   用户真想要那个名字，正确做法是先处理掉占名的那个槽。给个改名框既解决问题又不冒险。
     */
    private void askSlotRename(final Trash.Item it, final String taken) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.trash_slot_rename_hint);
        input.setText(Trans.get(TrashActivity.this, R.string.trash_slot_rename_prefill_fmt, taken));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        // 正文 + 输入框合成一页（原来是一个 setView 弹窗）
        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setText(Trans.get(this, R.string.trash_slot_taken_fmt, taken));
        msg.setTextSize(15);
        msg.setTextColor(getResources().getColor(R.color.fg));
        android.widget.LinearLayout page = new android.widget.LinearLayout(this);
        page.setOrientation(android.widget.LinearLayout.VERTICAL);
        page.addView(msg);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        page.addView(box, lp);
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_slot_rename_title), CardStack.wrap(this, page),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.trash_restore), new Runnable() {
                    @Override public void run() {
                        String raw = input.getText().toString();
                        String clean = Data.sanitizeSlot(raw);
                        if (clean == null) {
                            Toast.makeText(TrashActivity.this, R.string.trash_slot_bad_name,
                                    Toast.LENGTH_SHORT).show();
                            return;                    // 名字不合法就留在这一页
                        }
                        ref[0].dismiss();
                        doRestore(it, clean, false);
                    }
                }));
    }

    private void doRestore(final Trash.Item it, final String slot, final boolean overwrite) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Trash.Result r =
                        Trash.restoreFrom(TrashActivity.this, it, slot, overwrite);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(TrashActivity.this,
                                    it.kind == Trash.Kind.SLOT
                                            ? Trans.get(TrashActivity.this, R.string.trash_slot_restored_fmt, slot)
                                            : Trans.get(TrashActivity.this, R.string.trash_restore_ok_fmt,
                                                    TrashText.title(it), slot),
                                    Toast.LENGTH_LONG).show();
                            scan();
                            return;
                        }
                        if (r.nameTaken) {
                            // 整槽 ⇒ 只能换个名字（别去动另一个槽）；其余 ⇒ 问"要不要替换"
                            if (it.kind == Trash.Kind.SLOT) askSlotRename(it, slot);
                            else askOverwrite(it, slot);
                            return;
                        }
                        alert(Trans.get(TrashActivity.this, R.string.trash_restore_confirm_title),
                                TrashText.reason(TrashActivity.this, r));
                    }
                });
            }
        }, "trash-restore").start();
    }

    /** 插件：确认「放回插件列表」（说清"放回去是未启用状态"） */
    private void confirmRestorePlugin(final Trash.Item it) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_restore_confirm_title),
                CardStack.text(this, Trans.get(this, R.string.trash_restore_plugin_fmt, TrashText.title(it))),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.trash_restore), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        doRestorePlugin(it);
                    }
                }));
    }

    private void doRestorePlugin(final Trash.Item it) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Trash.Result r = Trash.restorePlugin(TrashActivity.this, it);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(TrashActivity.this,
                                    Trans.get(TrashActivity.this, R.string.trash_restore_plugin_ok_fmt,
                                            TrashText.title(it)),
                                    Toast.LENGTH_LONG).show();
                            scan();
                            return;
                        }
                        alert(Trans.get(TrashActivity.this, R.string.trash_restore_confirm_title),
                                TrashText.reason(TrashActivity.this, r));
                    }
                });
            }
        }, "trash-restore-plugin").start();
    }

    /** 整个版本：确认「放回去」（说清"名字与槽绑定会一起回来、存档没动过"） */
    private void confirmRestoreVersion(final Trash.Item it) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_restore_confirm_title),
                CardStack.text(this, Trans.get(this, R.string.trash_restore_version_fmt,
                        TrashText.title(it))),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.trash_restore), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        doRestoreVersion(it);
                    }
                }));
    }

    /** 几十 MB 的跨分区搬运 ⇒ 后台线程 + 前台服务（`Work`），与删版本那条对称 */
    private void doRestoreVersion(final Trash.Item it) {
        Work.begin(this, R.string.trash_restore);
        new Thread(new Runnable() {
            @Override public void run() {
                final Trash.Result r = Importer.restoreVersion(TrashActivity.this, it);
                Work.end(TrashActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(TrashActivity.this,
                                    Trans.get(TrashActivity.this, R.string.trash_restore_version_ok_fmt,
                                            TrashText.title(it)),
                                    Toast.LENGTH_LONG).show();
                            scan();
                            return;
                        }
                        alert(Trans.get(TrashActivity.this, R.string.trash_restore_confirm_title),
                                TrashText.reason(TrashActivity.this, r));
                    }
                });
            }
        }, "trash-restore-version").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── 删除 / 清空（都不可逆） ────────────────────────────────────────────

    private void confirmDeleteOne(final Trash.Item it) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = open(Trans.get(this, R.string.trash_delete_one),
                CardStack.text(this, Trans.get(this, R.string.trash_delete_one_confirm_fmt,
                        TrashText.title(it))),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.delete_forever), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        doDeleteOne(it);
                    }
                }));
    }

    private void doDeleteOne(final Trash.Item it) {
        new Thread(new Runnable() {
            @Override public void run() {
                // ⚠️ 目标目录 = 这一份所在的**那个**中转站目录（`remove` 会验"必须它的直接子项"）
                final Trash.Result r = Trash.remove(it.path, it.path.getParentFile());
                // ★ 整槽被彻底删掉 ⇒ 它那些快照引用的对象池对象到这一刻才真的没人引用
                //   （容器还在时 `Backup.allReferencedShas` 会扫到它们，见 Trash.trashSlot 的注释）。
                //   叫一次 GC，空间才是真的还回去 —— 不然"删掉了 78 MB"只是目录没了。
                if (r.ok && it.kind == Trash.Kind.SLOT) Backup.scheduleGc(TrashActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(TrashActivity.this,
                                    Trans.get(TrashActivity.this, R.string.trash_deleted_fmt,
                                            TrashText.title(it)),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            alert(Trans.get(TrashActivity.this, R.string.trash_delete_fail),
                                    TrashText.reason(TrashActivity.this, r));
                        }
                        scan();
                    }
                });
            }
        }, "trash-delete").start();
    }

    /**
     * 清空：**二次确认 + 说清份数与体积**（不可逆的动作不许只给一句"确定吗"）。
     * ★ 份数与体积取**当前列表**（不再扫一遍盘：用户看到的数字与确认框里的必须一致）。
     */
    private void confirmEmptyAll() {
        // ★★ 2026-10-25：**必须用 `mAll` 而不是 `mItems`** —— 「清空」清的是整个中转站
        //   （`Trash.empty(allDirs)`），而 `mItems` 现在是**筛过**的那几份 ⇒ 拿它算份数会出现
        //   "对话框说 2 份、实际删 10 份"。分类只改显示，不改这个动作的范围。
        final List<Trash.Item> items = mAll;
        int n = items == null ? 0 : items.size();
        long bytes = 0;
        for (int i = 0; i < n; i++) bytes += items.get(i).bytes;
        if (n == 0) {
            // 空的时候不给确认框（问一个"要不要删掉 0 份"很傻）—— 复用设置页那句通用的"没有需要清理的"
            Toast.makeText(this, R.string.set_autoclean_none, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_empty_confirm_title)
                .setMessage(Trans.get(TrashActivity.this, R.string.trash_empty_confirm_fmt, n, Util.formatSize(bytes)))
                .setPositiveButton(R.string.delete_forever, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doEmptyAll(); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doEmptyAll() {
        // 清空里如果**有整槽**，那些快照引用的对象池对象同样失去了最后的引用 ⇒ 清完叫一次 GC
        // （理由与 doDeleteOne 里那段相同）
        // ★ 同上：判据用全量（清空清的是整个站）
        final boolean hadSlot = mAll != null && hasSlot(mAll);
        new Thread(new Runnable() {
            @Override public void run() {
                final Trash.Result r = Trash.empty(Trash.allDirs(TrashActivity.this));
                if (r.ok && hadSlot) Backup.scheduleGc(TrashActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(TrashActivity.this,
                                    Trans.get(TrashActivity.this, R.string.trash_emptied_fmt, Util.formatSize(r.bytes)),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            alert(Trans.get(TrashActivity.this, R.string.trash_empty_fail),
                                    TrashText.reason(TrashActivity.this, r));
                        }
                        scan();
                    }
                });
            }
        }, "trash-empty").start();
    }

    /** 列表里有没有"整个槽"（决定彻底删除之后要不要叫 GC —— 见 doDeleteOne 里那段注释） */
    private static boolean hasSlot(List<Trash.Item> items) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == Trash.Kind.SLOT) return true;
        }
        return false;
    }

    /** 全类弹窗的唯一入口 —— 先挡"已销毁的 Activity"（后台任务收尾时旧的实例可能已经没了） */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
