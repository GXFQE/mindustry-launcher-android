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

    private SlotModes(Activity a, RadioGroup group, TextView hint) {
        this.a = a;
        this.group = group;
        this.hint = hint;
    }

    /**
     * 接上单选组（默认勾"更新"），并立刻刷一次摘要。
     * ⚠️ 找不到控件就返回 null —— 调用方必须容忍（宁可少一个模式选择，也不能崩在弹窗里）。
     */
    static SlotModes bind(Activity a, View form) {
        if (a == null || form == null) return null;
        RadioGroup g = (RadioGroup) form.findViewById(R.id.slot_mode_group);
        TextView h = (TextView) form.findViewById(R.id.slot_mode_hint);
        if (g == null) return null;
        SlotModes m = new SlotModes(a, g, h);
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
