package io.mdt.launcher;

import android.app.Activity;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * `dialog_slot_mode.xml` 的**接线**（2026-10-05，第 104 轮）。
 *
 * ★ 为什么单独一个类：两个入口（整槽 zip 导入 / 快照恢复）要的是同一段逻辑 ——
 *   读选中项、把"这个模式到底做什么"刷成一行白话。抄两份的话，将来加第四个模式
 *   （比如"只同步存档"）必然只改一处。
 *
 * ★ 默认 = {@link SlotWrite#UPDATE}（只覆盖同名、其余原样留着）：
 *   它是**破坏性最小**的那一个，也是本工程从 F6c 起的老行为 ⇒ 默认行为不变。
 * ⚠️ 布局里那份 `dialog_slot_mode` 必须是**已经 inflate 进当前对话框**的那棵树 ——
 *   `findViewById` 从它开始找（工程老坑：`row_action` 被 include 多次 ⇒ 重复 id，见 Util.bindAction）。
 */
final class SlotModes {

    private final Activity a;
    private final RadioGroup group;
    private final TextView hint;
    private final boolean settings;

    private SlotModes(Activity a, RadioGroup group, TextView hint, boolean settings) {
        this.a = a;
        this.group = group;
        this.hint = hint;
        this.settings = settings;
    }

    /**
     * 接上单选组（默认勾"更新"），并立刻刷一次摘要。
     * ⚠️ 找不到控件就返回 null —— 调用方必须容忍（宁可少一个模式选择，也不能崩在弹窗里）。
     */
    /**
     * 把这套控件里的**五处静态文案**填上（标题 / 三个模式名 / 自动备份那句）。
     *
     * ★ 第 169 轮修的 bug：布局里刻意不写 `android:text`（文案要过 `Trans`，布局够不到
     *   用户语言包），而这段绑定原来**只写在快照恢复那条路上**（`SlotOps.confirmRestore`）
     *   ⇒ 整槽 zip 导入那条路整块**没有字**（用户截图：三个单选只剩圆圈、"自动备份"只剩一个空底框）。
     *   ⇒ 绑定搬到这里、由 {@link #bind} 统一调用：**同一个控件的接线只有一份**。
     */
    static void bindTexts(android.content.Context c, View form) {
        bindTexts(c, form, false);
    }

    /**
     * @param settings true = 7.3c 的“复制设置”（标题 / 三条 hint 说“键”，不说“文件”）
     */
    static void bindTexts(android.content.Context c, View form, boolean settings) {
        if (c == null || form == null) return;
        Trans.bind(form, R.id.tx_slot_mode_title,
                settings ? R.string.settings_copy_mode_title : R.string.slot_mode_title);
        Trans.bind(form, R.id.slot_mode_update, R.string.slot_mode_update);
        Trans.bind(form, R.id.slot_mode_keep, R.string.slot_mode_keep);
        Trans.bind(form, R.id.slot_mode_replace, R.string.slot_mode_replace);
        Trans.bind(form, R.id.tx_slot_mode_autobak_note, R.string.slot_mode_autobak_note);
    }

    static SlotModes bind(Activity a, View form) {
        return bind(a, form, false);
    }

    static SlotModes bind(Activity a, View form, boolean settings) {
        if (a == null || form == null) return null;
        bindTexts(a, form, settings);
        RadioGroup g = (RadioGroup) form.findViewById(R.id.slot_mode_group);
        TextView h = (TextView) form.findViewById(R.id.slot_mode_hint);
        if (g == null) return null;
        SlotModes m = new SlotModes(a, g, h, settings);
        g.check(R.id.slot_mode_update);
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup rg, int id) { m.refresh(); }
        });
        m.refresh();
        return m;
    }

    /** 三个模式各自的解释（**整句资源**，不拼前缀 —— aapt2 会剥值里的首尾空白） */
    private void refresh() {
        if (hint == null) return;
        hint.setText(hintRes());
    }

    private int hintRes() {
        if (settings) {
            switch (mode()) {
                case SlotWrite.KEEP_OLD: return R.string.settings_mode_keep_hint;
                case SlotWrite.REPLACE: return R.string.settings_mode_replace_hint;
                default: return R.string.settings_mode_update_hint;
            }
        }
        switch (mode()) {
            case SlotWrite.KEEP_OLD: return R.string.slot_mode_keep_hint;
            case SlotWrite.REPLACE: return R.string.slot_mode_replace_hint;
            default: return R.string.slot_mode_update_hint;
        }
    }

    /** 当前选中的模式（**已经过 {@link SlotWrite#sane}**：控件状态异常时退回"更新"） */
    int mode() {
        int id = group.getCheckedRadioButtonId();
        if (id == R.id.slot_mode_keep) return SlotWrite.KEEP_OLD;
        if (id == R.id.slot_mode_replace) return SlotWrite.REPLACE;
        return SlotWrite.UPDATE;
    }

    /** 摘要里那句"这个模式做什么"（结果弹窗里也要用它说清"这次是怎么合的"） */
    String hintText() {
        String s = a.getString(hintRes());
        return s == null ? "" : s;
    }
}
