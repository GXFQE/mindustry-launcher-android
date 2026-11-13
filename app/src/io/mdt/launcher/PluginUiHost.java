package io.mdt.launcher;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.graphics.Typeface;

import io.mdt.launcher.api.PluginUi;

/**
 * **v41**：{@link PluginApi#ui()} 的唯一实现 —— 用**宿主的布局资源与主题色**造零件。
 *
 * <p>★ context 要用宿主那一个（Activity / application），**不能**用插件的 {@code PluginApi.context()}
 * —— 那个带插件自己的资源与主题，造出来又会跟宿主不一样。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
final class PluginUiHost implements PluginUi {

    private final Context c;

    PluginUiHost(Context hostContext) {
        this.c = hostContext;
    }

    private int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private int color(int res) {
        return c.getResources().getColor(res);
    }

    @Override public View card() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        return box;
    }

    @Override public View row(String title, String subtitle) {
        return rowView(title, subtitle, true);
    }

    @Override public View rowStatic(String title, String subtitle) {
        return rowView(title, subtitle, false);
    }

    private View rowView(String title, String subtitle, boolean clickable) {
        View row = LayoutInflater.from(c).inflate(R.layout.row_action, null, false);
        ((ImageView) row.findViewById(R.id.act_icon)).setImageResource(R.drawable.ic_stats);
        TextView t = (TextView) row.findViewById(R.id.act_title);
        TextView s = (TextView) row.findViewById(R.id.act_sub);
        t.setText(title == null ? "" : title);
        s.setText(subtitle == null ? "" : subtitle);
        if (subtitle == null || subtitle.trim().isEmpty()) s.setVisibility(View.GONE);
        ViewGroup g = (ViewGroup) row;
        // row_action 最后一个孩子是右箭头（导航行才有）；只读信息不该假装可点
        if (!clickable) {
            View last = g.getChildCount() > 0 ? g.getChildAt(g.getChildCount() - 1) : null;
            if (last instanceof ImageView) last.setVisibility(View.GONE);
            row.setClickable(false);
            row.setBackground(null);
        }
        return row;
    }

    @Override public View button(String text) {
        TextView b = new TextView(c);
        b.setText(text == null ? "" : text);
        b.setTextSize(15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(color(R.color.on_accent));
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setClickable(true);
        b.setFocusable(false);
        b.setMinHeight(dp(44));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    @Override public View divider() {
        View v = new View(c);
        v.setBackgroundColor(color(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMarginStart(dp(62));
        v.setLayoutParams(lp);
        return v;
    }

    @Override public View kv(String label, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView k = new TextView(c);
        k.setText(label == null ? "" : label);
        k.setTextSize(13f);
        k.setTextColor(color(R.color.fg_muted));
        row.addView(k);
        TextView v = new TextView(c);
        v.setText(value == null ? "" : value);
        v.setTextSize(13f);
        v.setTextColor(color(R.color.fg));
        v.setLineSpacing(2f, 1f);
        v.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(12));
        row.addView(v, lp);
        return row;
    }

    @Override public View sectionTitle(String text) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(12f);
        t.setTextColor(color(R.color.fg_muted));
        t.setPadding(dp(14), dp(8), dp(14), dp(2));
        return t;
    }

    @Override public View text(String text, boolean muted) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(13f);
        t.setTextColor(color(muted ? R.color.fg_muted : R.color.fg));
        t.setLineSpacing(2f, 1f);
        t.setPadding(dp(14), dp(4), dp(14), dp(4));
        return t;
    }
}
