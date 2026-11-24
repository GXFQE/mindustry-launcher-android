package io.mdt.launcher;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

/**
 * 7.3c 的界面：槽页 ⋮ → 从其他槽复制设置。
 *
 * <p>三档沿用 {@link SlotWrite}（更新 / 补齐 / 覆盖），但**文案是设置版**：
 * 整槽导入那句说的是“文件”，这里说的是“键”，不能直接套。
 *
 * <p>★ 2026-11-23（用户：「复制设置结束不应当可返回（再复制就失败）」）：
 *   所有"结束"屏（成功 / 失败 / 无事可做）一律用 {@link CardStack#finish} ——
 *   它把整摞压成一页，返回键就是关窗。以前用 `pushMessage` 会留一个「返回」，
 *   退回的是**预览页**，那一页的闭包里攥着已经算好的 {@link SettingsCopy.Plan}：
 *   再点一次「复制设置」时 `SettingsCopy.apply` 发现 `changed == 0` ⇒ 判成失败（真机现象）。</p>
 */
final class SettingsCopyUi {

    private SettingsCopyUi() {}

    static void open(final Activity a, final Data.Slot target, final SlotOps.Host host) {
        if (a == null || target == null) return;
        // ★ 第 425 轮：归档槽是空的 —— 目标槽没有设置可写、源槽没有设置可读（settings.bin 在快照里）
        if (SlotArchive.isArchived(a, target.name)) {
            toast(a, R.string.slot_err_already_archived);
            return;
        }
        // 2026-11-12（第 359 轮）：这一步只是**读两个槽的 settings.bin**（不变式②：读一律放行），
        // 写门禁挪到真正落盘的 doCopy（按目标槽判，见 WriteGate）。
        SlotOps.pickSlot(null, a, R.string.settings_copy_pick_source, target.name, true,
                new SlotOps.SlotPick2() {
                    @Override public void onPicked(CardStack stack, String source) {
                        if (source == null || source.equals(target.name)) {
                            toast(a, R.string.settings_copy_same_slot);
                            return;
                        }
                        if (SlotArchive.isArchived(a, source)) {
                            toast(a, R.string.slot_copy_src_archived);
                            return;
                        }
                        showMode(stack, a, target, source, host);
                    }
                });
    }

    private static void showMode(final CardStack stack, final Activity a,
                                 final Data.Slot target, final String source,
                                 final SlotOps.Host host) {
        View form = a.getLayoutInflater().inflate(R.layout.dialog_slot_mode, null);
        final SlotModes modes = SlotModes.bind(a, form, true);

        stack.push(Trans.get(a, R.string.settings_copy_title_fmt, target.name),
                CardStack.wrap(a, form),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.settings_copy_continue), new Runnable() {
                    @Override public void run() {
                        int mode = modes == null ? SlotWrite.UPDATE : modes.mode();
                        showPreview(stack, a, target, source, mode, host);
                    }
                }));
    }

    private static void showPreview(final CardStack stack, final Activity a,
                                    final Data.Slot target, final String source,
                                    final int mode, final SlotOps.Host host) {
        final SettingsCopy.Plan plan;
        try {
            plan = SettingsCopy.plan(a, source, target.name, mode);
        } catch (Throwable t) {
            finish(stack, a, R.string.settings_copy_failed_title,
                    Trans.get(a, R.string.settings_copy_failed_fmt, msg(t)));
            return;
        }

        if (plan.versionConflict) {
            finish(stack, a, R.string.settings_copy_failed_title,
                    Trans.get(a, R.string.settings_copy_version_mismatch));
            return;
        }

        if (plan.empty()) {
            finish(stack, a, R.string.settings_copy_preview_title,
                    Trans.get(a, R.string.settings_copy_no_changes));
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append(Trans.get(a, R.string.settings_copy_preview_fmt,
                plan.writeCount, plan.deleteCount, plan.skipCount));
        if (plan.versionUnknown) {
            body.append("\n\n").append(Trans.get(a, R.string.settings_copy_version_unknown));
        }

        stack.push(Trans.get(a, R.string.settings_copy_preview_title),
                CardStack.text(a, body.toString()),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.settings_copy_confirm), new Runnable() {
                    @Override public void run() {
                        doCopy(stack, a, target, plan, host);
                    }
                }));
    }

    private static void doCopy(final CardStack stack, final Activity a,
                               final Data.Slot target, final SettingsCopy.Plan plan,
                               final SlotOps.Host host) {
        if (WriteGate.blockedSlot(a, target.name)) {
            toast(a, R.string.settings_copy_game_running);
            return;
        }
        SettingsBin.Result r;
        try {
            r = SettingsCopy.apply(a, target.name, plan);
        } catch (Throwable t) {
            finish(stack, a, R.string.settings_copy_failed_title,
                    Trans.get(a, R.string.settings_copy_failed_fmt, msg(t)));
            return;
        }
        // ★ noop 是**合法结果**（这次没有键需要改），而 `SettingsBin` 只在**真写盘**时才置
        //   `verified`（见 SettingsBin 写后校验那一段）⇒ 判据必须排除 noop，
        //   否则"再确认一次"会被判成失败，而且原因取不到（弹一张空白正文的卡）。
        if (r == null || !r.ok || (!r.noop && !r.verified)) {
            String why = (r == null || r.error == null || r.error.isEmpty()) ? "" : r.error;
            if (why.isEmpty()) why = Trans.get(a, R.string.settings_copy_failed_noreason);
            finish(stack, a, R.string.settings_copy_failed_title,
                    Trans.get(a, R.string.settings_copy_failed_fmt, why));
            return;
        }
        if (host != null) host.onSlotChanged();
        String body = r.noop
                ? Trans.get(a, R.string.settings_copy_no_changes)
                : Trans.get(a, R.string.settings_copy_done_fmt, plan.writeCount, plan.deleteCount);
        finish(stack, a, R.string.settings_copy_done_title, body);
    }

    /** 结束屏：一页 + 只有一个「关闭」（{@link CardStack#finish} 已清空历史，返回键也是关窗）。 */
    private static void finish(final CardStack stack, final Activity a,
                               int titleRes, CharSequence body) {
        stack.finish(Trans.get(a, titleRes), body,
                new CardStack.Btn(Trans.get(a, R.string.close), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
    }

    private static String msg(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private static void toast(Activity a, int res) {
        Toast.makeText(a, Trans.get(a, res), Toast.LENGTH_SHORT).show();
    }
}
