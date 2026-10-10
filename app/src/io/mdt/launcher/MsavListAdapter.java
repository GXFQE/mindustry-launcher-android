package io.mdt.launcher;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

/**
 * 「标题 + 副标题」两行的卡片列表适配器（F10 的存档/地图选择列表用）。
 *
 * ★ 为什么要它（用户 2026-10-03：「区分度依旧很低，要不你加个框吧」）：
 *   `AlertDialog.setItems` 那种纯文本行里，"一条的第二行"和"下一条的第一行"贴着，
 *   几百条读起来是一整片；换成**每条一个卡片**（{@link R.layout#item_msav}）之后，
 *   边界来自视觉而不是靠读。
 *
 * ★ 为什么两个数组而不是一个对象列表：副标题是**异步**补上来的（先"文件名 + 大小"，
 *   后台读完 meta 再补第二行）⇒ 就地改数组 + {@link #notifyDataSetChanged()} 最省事。
 */
final class MsavListAdapter extends BaseAdapter {
    private final LayoutInflater inf;
    private final String[] titles;
    private final String[] subs;
    /** 缩略图（F10 地图预览用；存档列表整列都是 null） */
    private final android.graphics.Bitmap[] thumbs;
    /**
     * **勾选模式**的勾选状态（第 127 轮批量导出）。
     * `null` = 单选模式（点一行直接干活，行里那个复选框整列 GONE）；
     * 非 null = 多选模式（点一行 = 勾 / 取消，复选框跟着状态走）。
     *
     * ★ 为什么状态放适配器而不是用 `ListView.CHOICE_MODE_MULTIPLE`：
     *   那个模式只对实现了 `Checkable` 的 item 视图自动同步勾选状态，
     *   而我们的行是普通布局（还要容纳缩略图 + 两行文字）⇒ 状态必须自己拿着，
     *   而且**只有一个来源**（免得"按钮上写着 3 份、行里勾了 2 个"这种不一致）。
     */
    private final boolean[] checked;

    MsavListAdapter(Context ctx, String[] titles, String[] subs) {
        this(ctx, titles, subs, null);
    }

    MsavListAdapter(Context ctx, String[] titles, String[] subs, android.graphics.Bitmap[] thumbs) {
        this(ctx, titles, subs, thumbs, null);
    }

    MsavListAdapter(Context ctx, String[] titles, String[] subs, android.graphics.Bitmap[] thumbs,
                    boolean[] checked) {
        this.inf = LayoutInflater.from(ctx);
        this.titles = titles;
        this.subs = subs;
        this.thumbs = thumbs;
        this.checked = checked;
    }

    boolean isChecked(int i) {
        return checked != null && i >= 0 && i < checked.length && checked[i];
    }

    void setChecked(int i, boolean on) {
        if (checked != null && i >= 0 && i < checked.length) checked[i] = on;
    }

    void setAll(boolean on) {
        if (checked == null) return;
        for (int i = 0; i < checked.length; i++) checked[i] = on;
    }

    int checkedCount() {
        if (checked == null) return 0;
        int n = 0;
        for (boolean b : checked) if (b) n++;
        return n;
    }

    /** 勾选的下标（升序 = 列表顺序，导出 zip 里的顺序也就跟列表一致） */
    java.util.List<Integer> checkedIndexes() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (checked == null) return out;
        for (int i = 0; i < checked.length; i++) if (checked[i]) out.add(Integer.valueOf(i));
        return out;
    }

    @Override public int getCount() {
        return titles.length;
    }

    @Override public Object getItem(int i) {
        return titles[i];
    }

    @Override public long getItemId(int i) {
        return i;
    }

    /** 异步补副标题（要在 UI 线程；刷界面走 {@link #refreshSub}，**别用 notifyDataSetChanged**） */
    void setSub(int i, String sub) {
        if (i >= 0 && i < subs.length) subs[i] = sub;
    }

    /** 异步补缩略图（同上，刷界面走 {@link #refreshThumb}） */
    void setThumb(int i, android.graphics.Bitmap b) {
        if (thumbs != null && i >= 0 && i < thumbs.length) thumbs[i] = b;
    }

    /**
     * ★★ **只刷新某一行**（2026-10-04 第 86 轮）。副标题异步补上来时用它，**不要** `notifyDataSetChanged()`。
     *
     * 为什么：`notifyDataSetChanged()` 会让整张列表重排 —— 用户在填充过程中滚动时，
     * 手感就是"滑不上去 / 一滑就跳"（`MapsActivity` 的缩略图那条注释里记着 114 张时的实测反馈）。
     * 而存档列表**没有份数上限**（`SlotIo.exportSave` 明确去掉了 60 份上限）⇒ 几百份存档就是几百次整片重排。
     *
     * ⚠️ 不在可见区就**什么都不做**：滚回来时 {@link #getView} 自然会用新值渲染。
     */
    void refreshSub(android.widget.ListView lv, int i) {
        if (lv == null || i < 0 || i >= subs.length) return;
        View row = lv.getChildAt(i - lv.getFirstVisiblePosition());
        if (row == null) return;
        TextView s = (TextView) row.findViewById(R.id.msav_sub);
        if (s != null) bindSub(s, i);
    }

    /** 副标题的**唯一渲染实现**（`getView` 与 `refreshSub` 共用，免得两处各判一次可见性而分叉） */
    private void bindSub(TextView s, int i) {
        String sub = subs[i];
        s.setText(sub == null ? "" : sub);
        s.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * ★★ **只刷新某一行的缩略图**（与 {@link #refreshSub} 同一套理由与做法）。
     *
     * 为什么要有它（2026-10-06）：地图 / 存档 / 蓝图三条列表**都要**后台逐张出图，
     * 而 `MapsActivity` 里那段「取第 i 行的 `msav_thumb` 换图」原来是**内联**的 ——
     * 再抄两遍就是三份同样的实现（其中一份改了、另两份不改的时候**不报错**，
     * 症状是"有的页面滑一下缩略图就没了"）⇒ 收在这里做**唯一实现**。
     */
    void refreshThumb(android.widget.ListView lv, int i) {
        if (lv == null || thumbs == null || i < 0 || i >= thumbs.length) return;
        View row = lv.getChildAt(i - lv.getFirstVisiblePosition());
        if (row == null) return;                 // 不在可见区：滚回来时 getView 自然用新值渲染
        android.widget.ImageView iv = (android.widget.ImageView) row.findViewById(R.id.msav_thumb);
        if (iv == null) return;
        android.graphics.Bitmap b = thumbs[i];
        if (b == null || b.isRecycled()) {
            iv.setVisibility(View.GONE);
        } else {
            iv.setImageBitmap(b);
            iv.setVisibility(View.VISIBLE);
        }
    }

    @Override public View getView(int i, View convert, ViewGroup parent) {
        View v = convert != null ? convert : inf.inflate(R.layout.item_msav, parent, false);
        TextView t = (TextView) v.findViewById(R.id.msav_title);
        TextView s = (TextView) v.findViewById(R.id.msav_sub);
        android.widget.ImageView iv = (android.widget.ImageView) v.findViewById(R.id.msav_thumb);
        String title = titles[i];
        t.setText(title == null ? "" : title);
        bindSub(s, i);
        if (iv != null) {
            android.graphics.Bitmap b = thumbs == null ? null : thumbs[i];
            if (b == null || b.isRecycled()) {
                iv.setVisibility(View.GONE);
            } else {
                iv.setImageBitmap(b);
                iv.setVisibility(View.VISIBLE);
            }
        }
        bindCheck(v, i);
        return v;
    }

    /**
     * 复选框的**唯一渲染实现**（`getView` 与 {@link #refreshCheck} 共用）。
     * ⚠️ 那个 `CheckBox` 在布局里是 `clickable=false` / `focusable=false`：
     *   触摸必须整个交给 ListView，否则点复选框本身会"点不动"（第 127 轮）。
     */
    private void bindCheck(View row, int i) {
        android.widget.CheckBox cb =
                (android.widget.CheckBox) row.findViewById(R.id.msav_check);
        if (cb == null) return;
        if (checked == null) {
            cb.setVisibility(View.GONE);
            return;
        }
        cb.setVisibility(View.VISIBLE);
        cb.setChecked(isChecked(i));
    }

    /** ★ 只刷新某一行的复选框（同 {@link #refreshSub}：别用 `notifyDataSetChanged`） */
    void refreshCheck(android.widget.ListView lv, int i) {
        if (checked == null || lv == null || i < 0 || i >= checked.length) return;
        View row = lv.getChildAt(i - lv.getFirstVisiblePosition());
        if (row == null) return;
        bindCheck(row, i);
    }
}
