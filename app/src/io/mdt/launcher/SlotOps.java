package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
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
 *   这些操作原先**只**实现在 {@link SlotsActivity} 里，槽二级页面靠"带 extra 派发 +
 *   `FLAG_ACTIVITY_REORDER_TO_FRONT`"去复用 —— 于是用户点「备份此槽…」会
 *   **先跳到存档页、再弹窗**，关掉之后还停在存档页（观感像"被踢回上一级"）。
 *   抽到这里之后，槽页面**就地弹窗**，实现仍然只有一份（不是复制两份）。
 *
 * ⚠️ 只放**纯对话框类**操作（备份 / 恢复 / 克隆 / 备份策略 / 重命名 / 删除）。
 *   走 SAF 的导入导出仍在 {@link SlotsActivity}：文件选择器的回调
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

    /**
     * 「备份与恢复」对话框的**三个标签 —— 唯一清单**（第 158 轮）。
     *
     * <p>★ 为什么抽成方法：这个数组原来在 `SlotActivity.promptSlotOps()` 里写着**六项**，
     * 混进了「克隆 / 重命名 / 删除此槽」；后三项搬去顶栏 ⋮ 之后，界面与自检要看**同一份**清单
     * （自检才断言得出"备份对话框里不再有改名 / 删除"，而不是靠人去数）。</p>
     *
     * <p>⚠️ 顺序 = 下标：0 备份 / 1 看有哪些 / 2 策略。加项**只许往末尾加**
     * （与 `BatchIo` 那几条清单同一个规矩）。</p>
     */
    static String[] opsMenuLabels(android.content.Context a) {
        return new String[]{
                Trans.get(a, R.string.slot_op_backup),
                Trans.get(a, R.string.slot_op_backups),
                Trans.get(a, R.string.slot_op_policy)};
    }

    // ── 备份 ──────────────────────────────────────────────────────────────

    static void backup(final Activity a, final Data.Slot s, final Host h) {
        backup(null, a, s, h);
    }

    /** 卡片里有栈就推页；没有就自己开一张。 */
    static void backup(final CardStack parent, final Activity a, final Data.Slot s, final Host h) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.backup_label_hint);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(a);
        msg.setText(Trans.get(a, R.string.backup_msg_fmt, excludedNames()));
        msg.setTextSize(15);
        msg.setTextColor(a.getResources().getColor(R.color.fg));
        box.addView(msg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 12);
        box.addView(input, lp);
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(parent, a, Trans.get(a, R.string.backup_title_fmt, s.name),
                CardStack.wrap(a, box),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.backup), new Runnable() {
                    @Override public void run() {
                        // ★ 第 269 轮：进度页顶替这一页（不关窗）
                        doBackup(ref[0], a, s, h, input.getText().toString());
                    }
                }));
    }

    private static void doBackup(final Activity a, final Data.Slot s, final Host h,
                                 final String label) {
        doBackup(null, a, s, h, label);
    }

    /** 卡片版（第 269 轮）：进度 → 结果 同一张卡；没有卡片就自己开一张。 */
    private static void doBackup(final CardStack card, final Activity a, final Data.Slot s,
                                 final Host h, final String label) {
        final CardStack stack = (card != null) ? card : new CardStack(a);
        stack.replace(Trans.get(a, R.string.backup_progress_title),
                Trans.get(a, R.string.backup_progress_msg_fmt, s.name));
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Backup.Snapshot ss = null;
                try {
                    ss = Backup.create(a, s.name, label);
                } catch (Exception e) {
                    // ★ 2026-10-06：弹窗正文翻白话（系统 errno 才翻）；原文留在日志里
                    err = Util.ioReason(a, e);
                    android.util.Log.w("MDTLauncher", "backup failed: " + err, e);
                }
                final String fe = err;
                final Backup.Snapshot fss = ss;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(a)) return;
                        if (fe == null) {
                            stack.replace(Trans.get(a, R.string.backup_done),
                                    Trans.get(a, R.string.backup_done_msg_fmt, fss.title(),
                                            fss.count, Util.formatSize(fss.bytes),
                                            Util.formatSize(fss.storedNew),
                                            fss.dir.getAbsolutePath()),
                                    closeBtn(a, stack));
                        } else {
                            stack.replace(Trans.get(a, R.string.backup_failed), fe,
                                    closeBtn(a, stack));
                        }
                        h.onSlotChanged();
                    }
                });
            }
        }).start();
    }

    /** 卡片结果页的「关闭」（唯一实现）。 */
    private static CardStack.Btn closeBtn(final Activity a, final CardStack stack) {
        return new CardStack.Btn(Trans.get(a, R.string.close), new Runnable() {
            @Override public void run() { stack.dismiss(); }
        });
    }

    // ── 备份列表（看 / 恢复 / 删掉） ───────────────────────────────────────

    /**
     * ★ 2026-10-06（一档②）：这个槽的全部备份 —— 点一份再问"恢复还是删掉"。
     *
     * ★ 为什么把原来的「恢复备份…」改成这一个入口：**恢复本来就得先选一份** ⇒
     *   列表是必经之路；而"只想看一眼有哪些备份、里面是什么"用的是同一个列表。
     *   ⇒ 一个列表服务两种意图，**不做两个入口** —— 两条路各管一种直觉不是保留重复的
     *   理由（F15b 的教训，REF §34）。
     *
     * ★ 每一行仍然是**卡片**（时间戳标题 + 份数/体积 +「这是什么存档」），口径与原来一字不差：
     *   同一件事只留一份显示实现（`Backup.msavLine` + `snapshot_entry_fmt`）。
     */
    static void backups(final Activity a, final Data.Slot s, final Host h) {
        backups(null, a, s, h);
    }

    /** 卡片里有栈就推页；没有就自己开一张（槽页「备份与恢复」菜单点进来就是有栈的那条）。 */
    static void backups(final CardStack parent, final Activity a, final Data.Slot s, final Host h) {
        final List<Backup.Snapshot> snaps = Backup.list(a, s.name);
        if (snaps.isEmpty()) {
            if (parent != null) {
                parent.pushMessage(Trans.get(a, R.string.no_backup_title),
                        Trans.get(a, R.string.no_backup_msg_fmt, s.name),
                        Trans.get(a, R.string.close), new Runnable() {
                            @Override public void run() { parent.dismiss(); }
                        });
                return;
            }
            alert(a, Trans.get(a, R.string.no_backup_title),
                    Trans.get(a, R.string.no_backup_msg_fmt, s.name));
            return;
        }
        // ★ 每条一个**卡片**（用户 2026-10-03：「区分度依旧很低，要不你加个框吧」）：
        //   第一行 = 时间戳标题，第二行 = 份数/大小 +「这是什么存档」
        final String[] titles = new String[snaps.size()];
        final String[] subs = new String[snaps.size()];
        for (int i = 0; i < snaps.size(); i++) {
            Backup.Snapshot ss = snaps.get(i);
            titles[i] = ss.title();
            String base = Trans.get(a, R.string.snapshot_entry_fmt, ss.count, Util.formatSize(ss.bytes));
            String msav = Backup.msavLine(a, ss);
            subs[i] = msav.isEmpty() ? base : (base + " · " + msav);
        }
        // ★ 第 262 轮：列表本身也搬进卡片（原来列表弹窗点一行 → 再开一张卡 = 又换了一次窗）。
        //   同一份列表只画一次：`CardStack.show` 决定"新开一张"还是"推进父卡"。
        final CardStack[] ref = new CardStack[1];
        View body = CardStack.list2(a, titles, subs, new CardStack.ItemClick() {
            @Override public void onClick(int pos) {
                if (pos < 0 || pos >= snaps.size()) return;
                promptBackupActions(ref[0], a, s, snaps.get(pos), h);
            }
        });
        ref[0] = CardStack.show(parent, a, Trans.get(a, R.string.backup_list_title_fmt, s.name), body,
                new CardStack.Btn(Trans.get(a, R.string.close), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
    }

    /**
     * 选中一份备份之后的动作表（**只有这一处问这个问题**）。
     * ⚠️ 标题用那份备份自己的 `title()`（时间戳 + 备注）—— 用户刚点的是哪一行，
     *   这里就得是哪一行，别换成另一句话。
     */
    private static void promptBackupActions(final CardStack parent, final Activity a, final Data.Slot s,
                                            final Backup.Snapshot ss, final Host h) {
        // ★ 第 261 轮：原来这一层是框架弹窗，点「恢复 / 删除」再弹一个确认框 = 两个窗口，
        //   旧窗一关 dim 就重来一遍 ⇒ 整屏闪。改成同一张卡里推页（`CardStack`）。
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(parent, a, ss.title(),
                CardStack.list(a, new CharSequence[]{
                        Trans.get(a, R.string.slot_op_restore),
                        Trans.get(a, R.string.slot_op_backup_delete)},
                        new CardStack.ItemClick() {
                            @Override public void onClick(int w) {
                                if (w == 0) confirmRestore(ref[0], a, s, ss, h);
                                else confirmDelete(ref[0], a, s, ss, h);
                            }
                        }),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
    }

    /**
     * 删掉一份备份。
     *
     * ★ 与自动修剪（超过上限丢最旧）走的是**同一个** {@link Backup#delete} —— 它自己会
     *   调度一次内容回收（不回收的话备份删了、空间还在）。
     * ★ 与恢复**刻意不同**：删备份不加"游戏没在跑"那道门禁（它不碰游戏的数据根），
     *   问一次就动手；而恢复要先留一份自动备份（见 {@link #doRestore}）。
     */
    private static void confirmDelete(final CardStack stack, final Activity a, final Data.Slot s,
                                      final Backup.Snapshot ss, final Host h) {
        stack.pushMessage(Trans.get(a, R.string.backup_delete_title),
                Trans.get(a, R.string.backup_delete_msg_fmt, ss.title(), ss.count,
                        Util.formatSize(ss.bytes)),
                Trans.get(a, R.string.delete), new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        doDelete(a, ss, h);
                    }
                });
    }

    private static void doDelete(final Activity a, final Backup.Snapshot ss, final Host h) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = Backup.delete(a, ss);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // ⚠️ 转屏 / 页面已经没了 ⇒ 什么都不做（`isFinishing()` 挡不住转屏）
                        if (Util.dead(a)) return;
                        if (err == null) {
                            toast(a, Trans.get(a, R.string.backup_deleted_fmt, ss.title()));
                        } else {
                            alert(a, Trans.get(a, R.string.backup_delete_failed), err);
                        }
                        h.onSlotChanged();
                    }
                });
            }
        }, "backup-delete").start();
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
    private static void confirmRestore(final CardStack stack, final Activity a, final Data.Slot s,
                                       final Backup.Snapshot ss, final Host h) {
        View form = a.getLayoutInflater().inflate(R.layout.dialog_slot_mode, null);
        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包。
        // ★ 第 169 轮：这五处绑定搬进 `SlotModes.bindTexts`（由 `SlotModes.bind` 统一调用）——
        //   原先只写在**这一条路**上，整槽 zip 导入那条路整块没有字。
        final SlotModes modes = SlotModes.bind(a, form);
        // 正文（这份备份多大 / 从哪来）+ 模式表单，合成卡片里的一页
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(a);
        msg.setText(Trans.get(a, R.string.restore_confirm_msg_fmt, ss.title(), ss.count,
                Util.formatSize(ss.bytes), s.dir == null ? "?" : s.dir.getAbsolutePath()));
        msg.setTextSize(15);
        msg.setTextColor(a.getResources().getColor(R.color.fg));
        msg.setLineSpacing(Math.round(4 * a.getResources().getDisplayMetrics().density), 1f);
        box.addView(msg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * a.getResources().getDisplayMetrics().density);
        box.addView(form, lp);
        stack.push(Trans.get(a, R.string.restore_confirm_title), CardStack.wrap(a, box),
                stack.back(),
                new CardStack.Btn(Trans.get(a, R.string.restore), new Runnable() {
                    @Override public void run() {
                        // ★ 第 269 轮：进度页顶替确认页（不关窗）
                        doRestore(stack, a, s, ss, modes == null ? SlotWrite.UPDATE : modes.mode(), h);
                    }
                }));
    }

    /** 一次整槽恢复的结果：恢复结果 + 动手前那份快照（可能为 null） */
    static final class Restored {
        final Backup.RestoreResult result;
        final Backup.Snapshot preOp;

        Restored(Backup.RestoreResult result, Backup.Snapshot preOp) {
            this.result = result;
            this.preOp = preOp;
        }
    }

    /**
     * ★ 第 178 轮（杀后台 P1）：整槽恢复的**唯一上层入口**。
     *
     * <p>与 {@link SlotZip#importSlot} 同一条纪律：界面（{@link #doRestore}）与
     * dev 口（`DevTools#devBackupRestore`）原先各走各的 —— 界面先
     * {@link AutoBackup#beforeSlotOp}、dev 直接 {@link Backup#restore}。
     * 现在两条路共用这一处，"恢复选错模式也退得回来"对 dev 同样成立。
     */
    static Restored restoreSlot(Context ctx, String slot, Backup.Snapshot ss, int mode) {
        Backup.Snapshot pre = AutoBackup.beforeSlotOp(ctx, slot,
                Trans.get(ctx, R.string.backup_auto_restore));
        return new Restored(Backup.restore(ctx, slot, ss, mode), pre);
    }

    private static void doRestore(final Activity a, final Data.Slot s,
                                  final Backup.Snapshot ss, final int mode, final Host h) {
        doRestore(null, a, s, ss, mode, h);
    }

    /** 卡片版（第 269 轮）：进度 → 恢复报告 同一张卡。 */
    private static void doRestore(final CardStack card, final Activity a, final Data.Slot s,
                                  final Backup.Snapshot ss, final int mode, final Host h) {
        final CardStack stack = (card != null) ? card : new CardStack(a);
        stack.replace(Trans.get(a, R.string.restore_progress_title),
                Trans.get(a, R.string.restore_progress_msg));
        new Thread(new Runnable() {
            @Override public void run() {
                // ★★ 第 178 轮：pre-op 与恢复是**同一个入口**（UI/dev 共用）。
                final Restored rs = restoreSlot(a, s.name, ss, mode);
                final Backup.Snapshot auto = rs.preOp;
                // ★ 成败读 rr.ok，**不许读报告开头**（那是文案，一本地化就判错；门禁规则 SRC-01）
                final Backup.RestoreResult rr = rs.result;
                final String report = rr.report + (auto == null ? ""
                        : Trans.get(a, R.string.slot_autobak_done_fmt, auto.title()));
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(a)) return;
                        stack.replace(Trans.get(a, rr.ok ? R.string.restore_done
                                : R.string.restore_not_run), report, closeBtn(a, stack));
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
            alert(a, Trans.get(a, R.string.clone_source_live),
                    Trans.get(a, R.string.clone_source_live_msg_fmt, s.name));
            return;
        }
        if (s.files == 0) {
            toast(a, Trans.get(a, R.string.clone_source_empty_fmt, s.name));
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
        // ★ 第 269 轮：输入页 → 进度页 → 结果页 同一张卡（原来是"输入框 → 进度框 → 结果框"三个窗）
        android.widget.TextView msg = new android.widget.TextView(a);
        msg.setText(Trans.get(a, R.string.clone_msg_fmt, s.name, s.files,
                Util.formatSize(s.bytes), excludedNames()));
        msg.setTextSize(15);
        msg.setTextColor(a.getResources().getColor(R.color.fg));
        msg.setLineSpacing(Math.round(4 * a.getResources().getDisplayMetrics().density), 1f);
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(msg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 12);
        page.addView(box, lp);
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a, Trans.get(a, R.string.clone_title_fmt, s.name),
                CardStack.wrap(a, page),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.clone), new Runnable() {
                    @Override public void run() {
                        doClone(ref[0], a, s, input.getText().toString(), h);
                    }
                }));
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
        doClone(null, a, s, rawName, h);
    }

    /** 卡片版（第 269 轮）：进度 → 结果 同一张卡。 */
    private static void doClone(final CardStack card, final Activity a, final Data.Slot s,
                                final String rawName, final Host h) {
        // 仅用于**进度页文案与快照标题**；合法性判定不在这里（见上面第 3 条）。
        final String to = Data.sanitizeSlot(rawName);
        final String shown = (to == null) ? rawName : to;
        final String title = Trans.get(a, R.string.clone_title_fmt, shown);

        final CardStack stack = (card != null) ? card : new CardStack(a);
        stack.replace(Trans.get(a, R.string.clone_progress_title),
                Trans.get(a, R.string.clone_progress_msg_fmt, s.name, shown));
        new Thread(new Runnable() {
            @Override public void run() {
                final String fail = Backup.cloneSlot(a, s.name, rawName, title);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(a)) return;
                        if (fail != null) {
                            stack.replace(Trans.get(a, R.string.clone_failed), fail, closeBtn(a, stack));
                        } else {
                            // ⚠️ 直接问盘（`Data.slotInfo`），**不要**从列表快照里找 ——
                            //   它此刻还是克隆前的，新槽压根不在里面（会报 0 个文件）。
                            Data.Slot now = Data.slotInfo(a, to);
                            stack.replace(Trans.get(a, R.string.clone_done),
                                    Trans.get(a, R.string.clone_done_msg_fmt, to,
                                            now.files, Util.formatSize(now.bytes)),
                                    closeBtn(a, stack));
                        }
                        h.onSlotChanged();
                    }
                });
            }
        }).start();
    }

    // ── 备份策略（F5） ────────────────────────────────────────────────────

    static void backupPolicy(final Activity a, final Data.Slot s, final Host h) {
        backupPolicy(null, a, s, h);
    }

    /** 卡片里有栈就推页；没有就自己开一张。 */
    static void backupPolicy(final CardStack parent, final Activity a, final Data.Slot s, final Host h) {
        final Config.BackupPolicy cur = Config.get().backupPolicy(s.name);

        // F5b：表单走 XML（res/layout/dialog_policy.xml），不再是"代码拼一堆裸控件"。
        View form = a.getLayoutInflater().inflate(R.layout.dialog_policy, null);
        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(form, R.id.pol_enabled, R.string.policy_enabled);
        Trans.bind(form, R.id.tx_policy_enabled_hint, R.string.policy_enabled_hint);
        Trans.bind(form, R.id.tx_policy_min, R.string.policy_min);
        Trans.bind(form, R.id.tx_policy_min_hint, R.string.policy_min_hint);
        Trans.bind(form, R.id.tx_policy_max, R.string.policy_max);
        Trans.bind(form, R.id.tx_policy_max_hint, R.string.policy_max_hint);
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
                        ? Trans.get(a, R.string.policy_live_fmt,
                                parseIntOr(min, cur.minMinutes), parseIntOr(max, cur.maxBackups))
                        : Trans.get(a, R.string.policy_live_off));
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

        // ★ 校验不过就**留在这一页** + 提示（旧对话框也是这个语义：不许"点了就关、错值静默丢弃"）
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(parent, a, Trans.get(a, R.string.policy_title_fmt, s.name),
                CardStack.wrap(a, form),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.policy_save), new Runnable() {
                    @Override public void run() {
                        int mn = parseIntOr(min, -1);
                        int mx = parseIntOr(max, -1);
                        if (mn < 0 || mn > 100000) { toast(a, Trans.get(a, R.string.policy_bad_min)); return; }
                        if (mx < 1 || mx > 1000) { toast(a, Trans.get(a, R.string.policy_bad_max)); return; }
                        Config.get().setBackupPolicy(s.name, on.isChecked(), mn, mx);
                        toast(a, Trans.get(a, R.string.policy_saved));
                        ref[0].dismiss();
                        h.onSlotChanged();
                    }
                }));
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
                        if (err != null) { alert(a, Trans.get(a, R.string.rename_failed), err); return; }
                        String clean = Data.sanitizeSlot(nv);
                        if (clean != null) {
                            Backup.renameSlotBackups(a, s.name, clean);
                            // ★ 返回值是 Retarget（主分配 / 附加绑定分开计数）—— 附加绑定改名后，
                            //   那个版本的另一行仍然指着新名字（一个版本可以绑多个槽，见 Config.K_VEXTRA）
                            Config.Retarget r = Config.get().retargetSlots(s.name, clean);
                            if (r.total() > 0) toast(a, Trans.get(a, R.string.renamed_slots_fmt, r.total()));
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
            alert(a, Trans.get(a, R.string.delete_current_slot_title),
                    Trans.get(a, R.string.delete_current_slot_msg_fmt, s.name));
            return;
        }
        final List<Backup.Snapshot> snaps = Backup.list(a, s.name);
        StringBuilder sb = new StringBuilder();
        sb.append(Trans.get(a, R.string.delete_slot_warn));
        sb.append(s.dir == null ? "?" : s.dir.getAbsolutePath()).append('\n');
        sb.append(Trans.get(a, R.string.delete_slot_files_fmt, s.files, Util.formatSize(s.bytes)));
        if (!snaps.isEmpty()) {
            sb.append(Trans.get(a, R.string.delete_slot_with_backups_fmt, snaps.size()));
        } else {
            sb.append(Trans.get(a, R.string.delete_slot_no_backups));
        }
        sb.append(Trans.get(a, R.string.delete_slot_cap_fmt, Trash.keepSlots()));
        new AlertDialog.Builder(a)
                .setTitle(Trans.get(a, R.string.delete_slot_confirm_title_fmt, s.name))
                .setMessage(sb.toString())
                // ★ 按钮文案**必须与动作一致**：这里已经**不是**永久删除（整槽进中转站了）。
                //   原先复用 `delete_forever`（「永久删除」）—— 正文说"会挪进中转站"、按钮说"永久删除"，
                //   真机验收时一眼就看出来了（这种自相矛盾比措辞难看严重得多）。
                .setPositiveButton(R.string.delete_slot_confirm_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Trash.Result r = Trash.trashSlot(a, s.name, Backup.rootDir(a));
                        if (!r.ok) {
                            alert(a, Trans.get(a, R.string.delete_failed),
                                    TrashText.reason(a, r));
                        } else {
                            // ★ 两种受影响的行**分开说**：主分配是"回落到默认槽"（那一行还在），
                            //   附加绑定是"那一行直接没了"（它存在的理由就是这个槽）。
                            //   混成一句话（"N 个版本回落到默认槽"）会让用户去找一行根本没了的启动项。
                            Config.Retarget rt = Config.get().retargetSlots(s.name, "");
                            String tail = (rt.primary > 0
                                    ? Trans.get(a, R.string.slot_deleted_fallback_fmt, rt.primary) : "")
                                    + (rt.extras > 0
                                    ? Trans.get(a, R.string.slot_deleted_extras_fmt, rt.extras) : "");
                            Toast.makeText(a,
                                    Trans.get(a, R.string.slot_deleted_fmt, s.name) + tail,
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
        pickSlot(null, a, titleRes, prefer, cb);
    }

    /** 卡片里有栈就推一页；没有就自己开一张（第 263 轮：不再"关一个框再开一个框"）。 */
    static void pickSlot(final CardStack parent, final Activity a, int titleRes, final String prefer,
                         final SlotPick cb) {
        final List<Data.Slot> slots = Data.allSlots(a);
        if (slots.isEmpty()) return;
        final String[] names = new String[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            // ⚠️ 原来用单选按钮的 checked 态标出"上次用的那个槽"，卡片列表没有单选态 ⇒ 不再标
            names[i] = s.name + (s.active ? Trans.get(a, R.string.slot_current_suffix) : "");
        }
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(parent, a, Trans.get(a, titleRes),
                CardStack.list(a, names, new CardStack.ItemClick() {
                    @Override public void onClick(int w) {
                        if (w < 0 || w >= slots.size()) return;
                        cb.onPicked(slots.get(w).name);
                    }
                }),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
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
        if (CardStack.leaf(title, msg)) return;
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
