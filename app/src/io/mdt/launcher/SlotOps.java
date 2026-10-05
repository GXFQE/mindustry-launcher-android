package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 槽操作的**就地实现**（导航重构 REF §56 第 4 步的落地）。
 *
 * 🔴 为什么要有它（2026-10-03 用户报「**一堆 UI 问题**」，其中一条就是这个）：
 *   这些操作原先**只**实现在 {@link SavesActivity} 里，槽二级页面靠"带 extra 派发 +
 *   `FLAG_ACTIVITY_REORDER_TO_FRONT`"去复用 —— 于是用户点「备份此槽…」会
 *   **先跳到存档页、再弹窗**，关掉之后还停在存档页（观感像"被踢回上一级"）。
 *   抽到这里之后，槽页面**就地弹窗**，实现仍然只有一份（不是复制两份）。
 *
 * ⚠️ 只放**纯对话框类**操作（备份 / 恢复 / 克隆 / 备份策略 / 重命名 / 删除）。
 *   走 SAF 的导入导出仍在 {@link SavesActivity}：文件选择器的回调
 *   （`onActivityResult`）天然属于发起它的那个 Activity，搬过来只会多一层跳板。
 *
 * ★ 调用方通过 {@link Host#onSlotChanged()} 收到"槽内容可能变了"的通知
 *   （原来各自调自己的 `refresh()`）。
 */
final class SlotOps {

    /** 调用方页面：操作之后要刷新自己的界面 */
    interface Host {
        void onSlotChanged();
    }

    private SlotOps() {}

    // ── 备份 ──────────────────────────────────────────────────────────────

    static void backup(final Activity a, final Data.Slot s, final Host h) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.backup_label_hint);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), 0, dp(a, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.backup_title_fmt, s.name))
                .setMessage(a.getString(R.string.backup_msg_fmt, excludedNames()))
                .setView(box)
                .setPositiveButton(R.string.backup, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doBackup(a, s, h, input.getText().toString());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void doBackup(final Activity a, final Data.Slot s, final Host h,
                                 final String label) {
        final ProgressDialog pd = ProgressDialog.show(a, a.getString(R.string.backup_progress_title),
                a.getString(R.string.backup_progress_msg_fmt, s.name), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Backup.Snapshot ss = null;
                try {
                    ss = Backup.create(a, s.name, label);
                } catch (Exception e) {
                    err = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    android.util.Log.w("MDTLauncher", "backup failed: " + err, e);
                }
                final String fe = err;
                final Backup.Snapshot fss = ss;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fe == null) {
                            alert(a, a.getString(R.string.backup_done),
                                    a.getString(R.string.backup_done_msg_fmt, fss.title(),
                                            fss.count, Util.formatSize(fss.bytes),
                                            Util.formatSize(fss.storedNew),
                                            fss.dir.getAbsolutePath()));
                        } else {
                            alert(a, a.getString(R.string.backup_failed), fe);
                        }
                        h.onSlotChanged();
                    }
                });
            }
        }).start();
    }

    // ── 恢复 ──────────────────────────────────────────────────────────────

    static void restore(final Activity a, final Data.Slot s, final Host h) {
        final List<Backup.Snapshot> snaps = Backup.list(a, s.name);
        if (snaps.isEmpty()) {
            alert(a, a.getString(R.string.no_backup_title),
                    a.getString(R.string.no_backup_msg_fmt, s.name));
            return;
        }
        // ★ 每条一个**卡片**（用户 2026-10-03：「区分度依旧很低，要不你加个框吧」）：
        //   第一行 = 时间戳标题，第二行 = 份数/大小 +「这是什么存档」
        final String[] titles = new String[snaps.size()];
        final String[] subs = new String[snaps.size()];
        for (int i = 0; i < snaps.size(); i++) {
            Backup.Snapshot ss = snaps.get(i);
            titles[i] = ss.title();
            String base = a.getString(R.string.snapshot_entry_fmt, ss.count, Util.formatSize(ss.bytes));
            String msav = Backup.msavLine(a, ss);
            subs[i] = msav.isEmpty() ? base : (base + " · " + msav);
        }
        View listView = a.getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        final android.widget.ListView lv =
                (android.widget.ListView) listView.findViewById(R.id.msav_list);
        lv.setAdapter(new MsavListAdapter(a, titles, subs));
        final AlertDialog rdlg = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.restore_to_slot_title_fmt, s.name))
                .setView(listView)
                .setNegativeButton(R.string.cancel, null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= snaps.size()) return;
                rdlg.dismiss();
                confirmRestore(a, s, snaps.get(pos), h);
            }
        });
        rdlg.show();
    }

    /**
     * 恢复确认框（2026-10-05 第 104 轮加了**模式选择**）。
     *
     * ★ 为什么必须让用户选：恢复可能是**破坏性**的（「覆盖」会先清空这个槽），
     *   而"这份快照里的东西跟现在的怎么合"，只有用户知道他要的是哪一种。
     *   默认 = {@link SlotWrite#UPDATE}（只覆盖快照里有的，其余原样留着）——
     *   与老行为逐字相同，也是三种里最小的那一个。
     * ★ 布局与判据都与**整槽 zip 导入**共用（`dialog_slot_mode` + {@link SlotModes}）：
     *   同一个问题在两个入口下必须只有一种问法与一种答法。
     */
    private static void confirmRestore(final Activity a, final Data.Slot s,
                                       final Backup.Snapshot ss, final Host h) {
        View form = a.getLayoutInflater().inflate(R.layout.dialog_slot_mode, null);
        final SlotModes modes = SlotModes.bind(a, form);
        new AlertDialog.Builder(a)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(a.getString(R.string.restore_confirm_msg_fmt, ss.title(), ss.count,
                        Util.formatSize(ss.bytes),
                        s.dir == null ? "?" : s.dir.getAbsolutePath()))
                .setView(form)
                .setPositiveButton(R.string.restore, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doRestore(a, s, ss, modes == null ? SlotWrite.UPDATE : modes.mode(), h);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void doRestore(final Activity a, final Data.Slot s,
                                  final Backup.Snapshot ss, final int mode, final Host h) {
        final ProgressDialog pd = ProgressDialog.show(a, a.getString(R.string.restore_progress_title),
                a.getString(R.string.restore_progress_msg), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                // ★★ 槽操作前的自动备份（用户 2026-10-05：「在槽操作前都自动备份吧」）：
                //    在**恢复之前**先留一份"现在的样子" —— 恢复选错模式也退得回来。
                final Backup.Snapshot auto =
                        AutoBackup.beforeSlotOp(a, s.name, a.getString(R.string.backup_auto_restore));
                // ★ 成败读 rr.ok，**不许读报告开头**（那是文案，一本地化就判错；门禁规则 SRC-01）
                Backup.RestoreResult rr = Backup.restore(a, s.name, ss, mode);
                final String report = rr.report + (auto == null ? ""
                        : a.getString(R.string.slot_autobak_done_fmt, auto.title()));
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        alert(a, a.getString(rr.ok ? R.string.restore_done
                                : R.string.restore_not_run), report);
                        h.onSlotChanged();
                    }
                });
            }
        }).start();
    }

    // ── 克隆槽（F4②） ─────────────────────────────────────────────────────

    /**
     * F4②：**把这个槽整份复制成一个新槽**。
     *
     * ★ 实现就是「{@link Backup#create} 源槽 + {@link Backup#restore} 新槽」——
     *   **零新原语**。刻意不另写一份"复制目录树"：那样会出现第二套口径
     *   （排除名单 / 空壳判定 / .part 过滤各写一遍），而它们**不一致时不会有任何症状**。
     *
     * ★ 为什么先问"源槽是不是当前槽 + 游戏是否在跑"：克隆要**读**整个源槽，
     *   读一个游戏正在写的目录，拿到的是**不一致的半成品**。
     *   ⚠️ 这一条 {@link Backup#create} 里也判 —— 这里保留一道是为了**在问用户要槽名之前**
     *      就挡住（否则用户填完名字才拿到"游戏还在跑"，白填一次）。判据只有一份，这里只是提前用。
     *
     * ★ 空槽直接在这里挡住（用列表里现成的 `s.files`，0 次 IO）。
     */
    static void cloneSlot(final Activity a, final Data.Slot s, final Host h) {
        if (s.name.equals(Data.currentSlot(a)) && Data.gameAlive(a)) {
            alert(a, a.getString(R.string.clone_source_live),
                    a.getString(R.string.clone_source_live_msg_fmt, s.name));
            return;
        }
        if (s.files == 0) {
            toast(a, a.getString(R.string.clone_source_empty_fmt, s.name));
            return;
        }

        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.clone_name_hint);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), 0, dp(a, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.clone_title_fmt, s.name))
                .setMessage(a.getString(R.string.clone_msg_fmt, s.name, s.files,
                        Util.formatSize(s.bytes), excludedNames()))
                .setView(box)
                .setPositiveButton(R.string.clone, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doClone(a, s, input.getText().toString(), h);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 只负责**界面**：进度框 → 调 {@link Backup#cloneSlot} → 报数。
     * 真正的"建槽 + 备份源 + 恢复进新槽"全在那一个原语里（UI 与自检共用同一份实现）。
     *
     * ⚠️ 建槽失败（名字不合法 / 已存在 / 目标是当前槽）就**原地停下并报错** ——
     *   不"自动换个名字"：用户填的名字就是他要的名字，替他做主比报错更糟。
     * ⚠️ 不在这里逐个重判失败原因：那些判据全在 `cloneSlot` 里，把它的原话直接给用户。
     */
    private static void doClone(final Activity a, final Data.Slot s, final String rawName,
                                final Host h) {
        // 仅用于**进度框文案与快照标题**；合法性判定不在这里（见上面第 3 条）。
        final String to = Data.sanitizeSlot(rawName);
        final String shown = (to == null) ? rawName : to;
        final String title = a.getString(R.string.clone_title_fmt, shown);

        final ProgressDialog pd = ProgressDialog.show(a,
                a.getString(R.string.clone_progress_title),
                a.getString(R.string.clone_progress_msg_fmt, s.name, shown), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String fail = Backup.cloneSlot(a, s.name, rawName, title);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fail != null) {
                            alert(a, a.getString(R.string.clone_failed), fail);
                        } else {
                            // ⚠️ 直接问盘（`Data.slotInfo`），**不要**从列表快照里找 ——
                            //   它此刻还是克隆前的，新槽压根不在里面（会报 0 个文件）。
                            Data.Slot now = Data.slotInfo(a, to);
                            alert(a, a.getString(R.string.clone_done),
                                    a.getString(R.string.clone_done_msg_fmt, to,
                                            now.files, Util.formatSize(now.bytes)));
                        }
                        h.onSlotChanged();
                    }
                });
            }
        }).start();
    }

    // ── 备份策略（F5） ────────────────────────────────────────────────────

    static void backupPolicy(final Activity a, final Data.Slot s, final Host h) {
        final Config.BackupPolicy cur = Config.get().backupPolicy(s.name);

        // F5b：表单走 XML（res/layout/dialog_policy.xml），不再是"代码拼一堆裸控件"。
        View form = a.getLayoutInflater().inflate(R.layout.dialog_policy, null);
        final CheckBox on = (CheckBox) form.findViewById(R.id.pol_enabled);
        final View minBox = form.findViewById(R.id.pol_min_box);
        final View maxBox = form.findViewById(R.id.pol_max_box);
        final EditText min = (EditText) form.findViewById(R.id.pol_min);
        final EditText max = (EditText) form.findViewById(R.id.pol_max);
        final TextView live = (TextView) form.findViewById(R.id.pol_live);

        on.setChecked(cur.enabled);
        min.setText(String.valueOf(cur.minMinutes));
        max.setText(String.valueOf(cur.maxBackups));

        // 实时摘要：改数字/关开关立刻反映结果；关掉开关时把两个数字项置灰（禁用态一眼可见）
        final Runnable sync = new Runnable() {
            @Override public void run() {
                boolean e = on.isChecked();
                minBox.setAlpha(e ? 1f : 0.4f);
                maxBox.setAlpha(e ? 1f : 0.4f);
                min.setEnabled(e);
                max.setEnabled(e);
                live.setText(e
                        ? a.getString(R.string.policy_live_fmt,
                                parseIntOr(min, cur.minMinutes), parseIntOr(max, cur.maxBackups))
                        : a.getString(R.string.policy_live_off));
            }
        };
        on.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) { sync.run(); }
        });
        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int i, int j, int k) {}
            @Override public void onTextChanged(CharSequence c, int i, int j, int k) {}
            @Override public void afterTextChanged(Editable e) { sync.run(); }
        };
        min.addTextChangedListener(tw);
        max.addTextChangedListener(tw);
        sync.run();

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.policy_title_fmt, s.name))
                .setView(form)
                .setPositiveButton(R.string.policy_save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        // ★ 保存按钮的默认行为是"点了就关窗"，那样填错值会**静默丢弃**（旧版就是这样）。
        //   这里接管它的点击：校验不过就留窗 + 提示，过了才写盘并关闭。
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int mn = parseIntOr(min, -1);
                        int mx = parseIntOr(max, -1);
                        if (mn < 0 || mn > 100000) { toast(a, a.getString(R.string.policy_bad_min)); return; }
                        if (mx < 1 || mx > 1000) { toast(a, a.getString(R.string.policy_bad_max)); return; }
                        Config.get().setBackupPolicy(s.name, on.isChecked(), mn, mx);
                        toast(a, a.getString(R.string.policy_saved));
                        dlg.dismiss();
                        h.onSlotChanged();
                    }
                });
            }
        });
        dlg.show();
    }

    private static int parseIntOr(EditText in, int def) {
        try {
            return Integer.parseInt(in.getText().toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── 重命名 / 删除 ─────────────────────────────────────────────────────

    static void renameSlot(final Activity a, final Data.Slot s, final Host h) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(s.name);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), 0, dp(a, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(R.string.rename_slot_title)
                .setView(box)
                .setPositiveButton(R.string.rename, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String nv = input.getText().toString();
                        String err = Data.renameSlot(a, s.name, nv);
                        if (err != null) { alert(a, a.getString(R.string.rename_failed), err); return; }
                        String clean = Data.sanitizeSlot(nv);
                        if (clean != null) {
                            Backup.renameSlotBackups(a, s.name, clean);
                            int n = Config.get().retargetSlots(s.name, clean);
                            if (n > 0) toast(a, a.getString(R.string.renamed_slots_fmt, n));
                        }
                        h.onSlotChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 删槽 —— **整槽挪进中转站**（2026-10-05 第二批）。
     *
     * ★ 为什么改：以前这里是"先删备份、再 `Data.deleteSlot` 硬删"（注释还写着"Android 没有回收站"）。
     *   中转站那套原语有了之后，一个几十 MB 的槽被误删仍然是**不可逆**的 —— 而用户对
     *   「挪进中转站」的预期已经建立起来了（地图/模组/存档都这样）。所以整槽（连它的备份）
     *   一起进站，能放回来；只有在中转站里再点一次「彻底删除」才真的没了。
     * ★ 顺带：不再调 `Backup.deleteSlotBackups`（备份跟着槽进容器）；也不在这里 `scheduleGc`
     *   —— 那些快照引用的对象**仍然被引用**（`Backup.allReferencedShas` 会扫中转站的容器），
     *   到用户真的彻底删掉那个容器时，{@link TrashActivity} 才会叫 GC 去收。
     */
    static void deleteSlot(final Activity a, final Data.Slot s, final Host h) {
        if (s.active) {
            alert(a, a.getString(R.string.delete_current_slot_title),
                    a.getString(R.string.delete_current_slot_msg_fmt, s.name));
            return;
        }
        final List<Backup.Snapshot> snaps = Backup.list(a, s.name);
        StringBuilder sb = new StringBuilder();
        sb.append(a.getString(R.string.delete_slot_warn));
        sb.append(s.dir == null ? "?" : s.dir.getAbsolutePath()).append('\n');
        sb.append(a.getString(R.string.delete_slot_files_fmt, s.files, Util.formatSize(s.bytes)));
        if (!snaps.isEmpty()) {
            sb.append(a.getString(R.string.delete_slot_with_backups_fmt, snaps.size()));
        } else {
            sb.append(a.getString(R.string.delete_slot_no_backups));
        }
        sb.append(a.getString(R.string.delete_slot_cap_fmt, Trash.KEEP_SLOTS));
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.delete_slot_confirm_title_fmt, s.name))
                .setMessage(sb.toString())
                // ★ 按钮文案**必须与动作一致**：这里已经**不是**永久删除（整槽进中转站了）。
                //   原先复用 `delete_forever`（「永久删除」）—— 正文说"会挪进中转站"、按钮说"永久删除"，
                //   真机验收时一眼就看出来了（这种自相矛盾比措辞难看严重得多）。
                .setPositiveButton(R.string.delete_slot_confirm_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Trash.Result r = Trash.trashSlot(a, s.name, Backup.rootDir(a));
                        if (!r.ok) {
                            alert(a, a.getString(R.string.delete_failed),
                                    TrashText.reason(a, r));
                        } else {
                            int n = Config.get().retargetSlots(s.name, "");
                            Toast.makeText(a,
                                    a.getString(R.string.slot_deleted_fmt, s.name)
                                            + (n > 0 ? a.getString(R.string.slot_deleted_fallback_fmt, n) : ""),
                                    Toast.LENGTH_LONG).show();
                        }
                        h.onSlotChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── 选槽（放东西进哪个槽） ────────────────────────────────────────────

    /** 选完槽的回调 */
    interface SlotPick {
        void onPicked(String slot);
    }

    /**
     * 弹一个"用哪个槽"的单选列表（当前槽带「[当前]」后缀，与设置页「默认槽」同一套文案）。
     *
     * ★ 为什么收在这里（2026-10-05，中转站页面要选目标槽时）：`MainActivity.pickSlot` 与
     *   `ModsActivity.pickSlot` 各有一份私有实现 —— 那边多带了一行"这个槽当前指到哪个版本"的
     *   信息，与这里**不是**同一种列表，所以没有合并；但"选一个槽"这件事在本类里只留这一份，
     *   新页面（中转站）不许再抄第三份。
     *
     * @param titleRes 列表标题（调用方给 —— "放回哪个槽"和"复制到哪个槽"不是同一句话）
     * @param prefer   默认勾选哪个槽（传空 = 不勾）
     */
    static void pickSlot(final Activity a, int titleRes, final String prefer, final SlotPick cb) {
        final List<Data.Slot> slots = Data.allSlots(a);
        if (slots.isEmpty()) return;
        final String[] names = new String[slots.size()];
        int checked = -1;
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            names[i] = s.name + (s.active ? a.getString(R.string.slot_current_suffix) : "");
            if (s.name.equals(prefer)) checked = i;
        }
        new AlertDialog.Builder(a)
                .setTitle(titleRes)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w < 0 || w >= slots.size()) return;
                        d.dismiss();
                        cb.onPicked(slots.get(w).name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /**
     * 备份口径的**排除名单**，`a、b、c` 形式（给备份确认框用）。
     *
     * ★ F19：以前这里列的是**白名单**。口径反过来之后，唯一值得告诉用户的就是
     *   "**哪些不会被备份**"—— 因为"全部内容"是不可能列举的。
     */
    static String excludedNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Data.SLOT_EXCLUDE.length; i++) {
            if (i > 0) sb.append("、");
            sb.append(Data.SLOT_EXCLUDE[i]);
        }
        return sb.toString();
    }

    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"（理由见 {@link Util#dead}：
     *  备份/恢复/克隆都是几十秒的后台任务，收尾弹窗落在转屏销毁的实例上会 BadTokenException 闪退）。 */
    private static void alert(Activity a, String title, String msg) {
        if (Util.dead(a)) return;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
