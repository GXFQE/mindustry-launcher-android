package io.mdt.launcher;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

/**
 * 7.3c 的界面：槽页 ⋮ → 从其他槽复制设置。
 *
 * <p>三档沿用 {@link SlotWrite}（更新 / 补齐 / 覆盖），但**文案是设置版**：
 * 整槽导入那句说的是“文件”，这里说的是“键”，不能直接套。
 */
final class SettingsCopyUi {

    private SettingsCopyUi() {}

    static void open(final Activity a, final Data.Slot target, final SlotOps.Host host) {
        if (a == null || target == null) return;
        if (Data.gameAlive(a)) {
            toast(a, R.string.settings_copy_game_running);
            return;
        }
        SlotOps.pickSlot(null, a, R.string.settings_copy_pick_source, target.name,
                new SlotOps.SlotPick2() {
                    @Override public void onPicked(CardStack stack, String source) {
                        if (source == null || source.equals(target.name)) {
                            toast(a, R.string.settings_copy_same_slot);
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
            stack.pushMessage(Trans.get(a, R.string.settings_copy_failed_title),
                    Trans.get(a, R.string.settings_copy_failed_fmt, msg(t)),
                    Trans.get(a, R.string.close), new Runnable() {
                        @Override public void run() { stack.dismiss(); }
                    });
            return;
        }

        if (plan.versionConflict) {
            stack.pushMessage(Trans.get(a, R.string.settings_copy_failed_title),
                    Trans.get(a, R.string.settings_copy_version_mismatch),
                    Trans.get(a, R.string.close), new Runnable() {
                        @Override public void run() { stack.dismiss(); }
                    });
            return;
        }

        if (plan.empty()) {
            stack.pushMessage(Trans.get(a, R.string.settings_copy_preview_title),
                    Trans.get(a, R.string.settings_copy_no_changes),
                    Trans.get(a, R.string.close), new Runnable() {
                        @Override public void run() { stack.dismiss(); }
                    });
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
        if (Data.gameAlive(a)) {
            toast(a, R.string.settings_copy_game_running);
            return;
        }
        SettingsBin.Result r;
        try {
            r = SettingsCopy.apply(a, target.name, plan);
        } catch (Throwable t) {
            r = null;
            stack.pushMessage(Trans.get(a, R.string.settings_copy_failed_title),
                    Trans.get(a, R.string.settings_copy_failed_fmt, msg(t)),
                    Trans.get(a, R.string.close), new Runnable() {
                        @Override public void run() { stack.dismiss(); }
                    });
            return;
        }
        if (r == null || !r.ok || !r.verified) {
            String why = (r == null || r.error == null || r.error.isEmpty()) ? "" : r.error;
            stack.pushMessage(Trans.get(a, R.string.settings_copy_failed_title),
                    Trans.get(a, R.string.settings_copy_failed_fmt, why),
                    Trans.get(a, R.string.close), new Runnable() {
                        @Override public void run() { stack.dismiss(); }
                    });
            return;
        }
        if (host != null) host.onSlotChanged();
        String body = r.noop
                ? a.getString(R.string.settings_copy_no_changes)
                : Trans.get(a, R.string.settings_copy_done_fmt, plan.writeCount, plan.deleteCount);
        stack.pushMessage(Trans.get(a, R.string.settings_copy_done_title), body,
                Trans.get(a, R.string.close), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                });
    }

    private static String msg(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private static void toast(Activity a, int res) {
        Toast.makeText(a, Trans.get(a, res), Toast.LENGTH_SHORT).show();
    }
}
