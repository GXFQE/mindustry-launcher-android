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

    private TextView mHead;
    private TextView mEmpty;
    private ListView mList;
    private MsavListAdapter mAdapter;
    /** 当前列表（点第几项 ⇒ 拿哪一份） */
    private List<Trash.Item> mItems;

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
        mList = (ListView) root.findViewById(R.id.trash_list);
        mHead.setText(R.string.trash_scanning);

        Util.bindAction(root, R.id.row_trash_empty_all, R.drawable.ic_trash,
                R.string.trash_empty_all, R.string.trash_empty_all_sub, new Runnable() {
                    @Override public void run() { confirmEmptyAll(); }
                });

        mList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (mItems != null && pos >= 0 && pos < mItems.size()) showActions(mItems.get(pos));
            }
        });
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
                final long bytes;
                try {
                    items = Trash.listAll(TrashActivity.this);
                    bytes = Trash.totalBytes(TrashActivity.this);
                } catch (Throwable ex) {
                    android.util.Log.w("MDTLauncher", "trash scan failed", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(TrashActivity.this)) return;
                            mHead.setText(R.string.trash_scan_failed);
                        }
                    });
                    return;
                }
                final String[] titles = new String[items.size()];
                final String[] subs = new String[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    Trash.Item it = items.get(i);
                    titles[i] = TrashText.title(it);
                    subs[i] = TrashText.line(TrashActivity.this, it);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(TrashActivity.this)) return;
                        mItems = items;
                        mHead.setText(TrashText.head(TrashActivity.this, items.size(), bytes));
                        mAdapter = new MsavListAdapter(TrashActivity.this, titles, subs);
                        mList.setAdapter(mAdapter);
                        // ★ 空态：整块**收掉**不放空框（用户 2026-10-04 的口径），
                        //   而且这句要说清"为什么会空"与"什么动作会把东西挪进来"。
                        if (mEmpty != null) {
                            mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (items.isEmpty()) mEmpty.setText(R.string.trash_empty);
                        }
                    }
                });
            }
        }, "trash-page").start();
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
    private void showActions(final Trash.Item it) {
        String body = TrashText.line(this, it);
        if (it.kind == Trash.Kind.OTHER) {
            body = body + "\n\n" + Trans.get(TrashActivity.this, R.string.trash_reason_no_kind);
        } else {
            body = Trans.get(TrashActivity.this, R.string.trash_actions_msg_fmt, body);
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(TrashText.title(it))
                .setMessage(body)
                .setNegativeButton(R.string.cancel, null);
        if (it.kind == Trash.Kind.SLOT) {
            d.setPositiveButton(R.string.trash_restore, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dlg, int w) { confirmRestoreSlot(it); }
            });
        } else if (it.kind != Trash.Kind.OTHER) {
            d.setPositiveButton(R.string.trash_restore, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dlg, int w) { askSlot(it); }
            });
        }
        d.setNeutralButton(R.string.trash_delete_one, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dlg, int w) { confirmDeleteOne(it); }
        });
        d.show();
    }

    /** 放回哪个槽：默认勾**来源槽**（旧条目认不出槽 ⇒ 不勾，让用户自己选） */
    private void askSlot(final Trash.Item it) {
        SlotOps.pickSlot(this, R.string.trash_restore_pick_title, it.slot, new SlotOps.SlotPick() {
            @Override public void onPicked(String slot) { confirmRestore(it, slot); }
        });
    }

    private void confirmRestore(final Trash.Item it, final String slot) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_restore_confirm_title)
                .setMessage(Trans.get(TrashActivity.this, R.string.trash_restore_confirm_fmt,
                        TrashText.title(it), slot))
                .setPositiveButton(R.string.trash_restore, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doRestore(it, slot, false);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 整槽：放回去 = 回到**它自己那个槽名**（不选槽；名字被占时下一步会问"换个名字"） */
    private void confirmRestoreSlot(final Trash.Item it) {
        final String name = TrashText.title(it);
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_restore_confirm_title)
                .setMessage(Trans.get(TrashActivity.this, R.string.trash_slot_restore_confirm_fmt, name))
                .setPositiveButton(R.string.trash_restore, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doRestore(it, name, false);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 同名回来问一声：**替换 / 取消**（第 86 轮那条"同名存档导入必须先问"的同族）。
     * 替换 = 旧的**再进一次中转站**（不是删掉），所以这一问不会丢东西 —— 但用户仍要知情。
     *
     * ⚠️ 按钮文案复用 `map_overwrite_ok`（「替换」/ "Replace"）：同一个词，不另写一条重复资源。
     */
    private void askOverwrite(final Trash.Item it, final String slot) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_restore_overwrite_title)
                .setMessage(Trans.get(TrashActivity.this, R.string.trash_restore_overwrite_fmt,
                        TrashText.title(it), slot))
                .setPositiveButton(R.string.map_overwrite_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doRestore(it, slot, true);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_slot_rename_title)
                .setMessage(Trans.get(TrashActivity.this, R.string.trash_slot_taken_fmt, taken))
                .setView(box)
                .setPositiveButton(R.string.trash_restore, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String raw = input.getText().toString();
                        String clean = Data.sanitizeSlot(raw);
                        if (clean == null) {
                            Toast.makeText(TrashActivity.this, R.string.trash_slot_bad_name,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        doRestore(it, clean, false);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── 删除 / 清空（都不可逆） ────────────────────────────────────────────

    private void confirmDeleteOne(final Trash.Item it) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_delete_one)
                .setMessage(Trans.get(TrashActivity.this, R.string.trash_delete_one_confirm_fmt,
                        TrashText.title(it)))
                .setPositiveButton(R.string.delete_forever, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doDeleteOne(it); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
        final List<Trash.Item> items = mItems;
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
        final boolean hadSlot = mItems != null && hasSlot(mItems);
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
