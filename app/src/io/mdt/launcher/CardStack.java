package io.mdt.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 同窗换页的卡片（第 259 轮）：**一个 Dialog 里用栈切内容**，push / pop 做交叉淡入。
 *
 * <h3>为什么不用"再开一个 Dialog"</h3>
 * 叠窗时旧窗一关，它那层 dim 跟着一起没；新窗是**另一个窗口**，dim 得由系统的窗口动画重新渐入。
 * 本机实测（60 fps 录屏逐帧量页面空白区亮度）：旧窗关掉后整页**亮 2~3 帧**才被新窗的 dim 压回去，
 * 给了 1 ms 空动画后仍会亮 1 帧 —— 用户读作"闪"。同窗换页时窗口、层、dim 全是同一份，
 * 亮度一个像素都不跳（只有卡片里的内容在变）。这条是 `ref/42`「叠弹窗的入场动画」那条规矩的落地。
 *
 * <h3>用法</h3>
 * <pre>
 * CardStack s = new CardStack(activity);
 * s.root(title, CardStack.list(this, labels, cb), cancelLabel, cancelAction).show();
 * // 某一项要"钻进去"时：
 * s.push(title, CardStack.text(this, body));      // 交叉淡入；返回键 / 「返回」按钮回到上一页
 * </pre>
 *
 * <p>★ 别在 push 出来的页里再 `new AlertDialog`（那就又叠窗口了）—— 要弹就 `push` 一页。
 * <p>★ 尺寸：第一页与后续页高度不同时走 170 ms 的高度过渡（`pumpHeight`），否则卡片会"啪"地跳一下。
 */
public final class CardStack {

    /** 列表类页面点第几项。 */
    public interface ItemClick {
        void onClick(int index);
    }

    /** 一页 = 标题 + 内容 + 最多两个按钮（都可空；按钮顺序 = 左 neg / 右 pos，与框架对话框一致）。 */
    private static final class Page {
        CharSequence title;
        View body;
        CharSequence negLabel;
        Runnable negAction;
        CharSequence posLabel;
        Runnable posAction;
    }

    private final Dialog dlg;
    private final TextView titleView;
    private final FrameLayout bodyView;
    private final LinearLayout buttonBar;
    private final ArrayList<Page> pages = new ArrayList<Page>();

    public CardStack(Activity a) {
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dialog_card_stack);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            // ★ 这个动画只影响"窗口第一次出现"那一瞬（之后换页根本不换窗）。
            //   给 1 ms 空动画是为了让 dim 立刻到位：完全不给动画时，系统会花约 0.5 s 渐入 dim（实测）。
            w.setWindowAnimations(R.style.AppDialogAnim);
        }
        titleView = (TextView) dlg.findViewById(R.id.card_stack_title);
        bodyView = (FrameLayout) dlg.findViewById(R.id.card_stack_body);
        buttonBar = (LinearLayout) dlg.findViewById(R.id.card_stack_buttons);
        dlg.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (pages.size() > 1) {
                        pop();
                    } else {
                        dlg.dismiss();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    /** 第一页（必须是第一页；重复调用会清空栈）。 */
    public CardStack root(CharSequence title, View body, CharSequence cancelLabel, Runnable cancelAction) {
        Page p = new Page();
        p.title = title;
        p.body = body;
        p.negLabel = cancelLabel;
        p.negAction = cancelAction;
        pages.clear();
        pages.add(p);
        render(p, false);
        return this;
    }

    /** 钻进下一页（交叉淡入；自动带一个「返回」）。 */
    public void push(CharSequence title, View body) {
        push(title, body, Trans.get(dlg.getContext(), R.string.back));
    }

    public void push(CharSequence title, View body, CharSequence backLabel) {
        final Page p = new Page();
        p.title = title;
        p.body = body;
        p.negLabel = backLabel;
        p.negAction = new Runnable() {
            @Override public void run() { pop(); }
        };
        pages.add(p);
        render(p, true);
    }

    /** 回上一页；已经在第一页就关掉。 */
    public void pop() {
        if (pages.size() <= 1) {
            dlg.dismiss();
            return;
        }
        pages.remove(pages.size() - 1);
        render(pages.get(pages.size() - 1), true);
    }

    public void show() {
        if (!dlg.isShowing()) dlg.show();
    }

    public void dismiss() {
        if (dlg.isShowing()) dlg.dismiss();
    }

    public boolean isShowing() {
        return dlg.isShowing();
    }

    // ---- 渲染 ------------------------------------------------------------------

    private void render(final Page p, boolean animate) {
        final CharSequence newTitle = p.title == null ? "" : p.title;
        if (!animate) {
            titleView.setText(newTitle);
            bodyView.removeAllViews();
            bodyView.addView(p.body);
            setButtons(p);
            return;
        }
        titleView.animate().alpha(0f).setDuration(70).withEndAction(new Runnable() {
            @Override public void run() {
                titleView.setText(newTitle);
                titleView.animate().alpha(1f).setDuration(110).start();
            }
        }).start();

        final View old = bodyView.getChildCount() == 0 ? null : bodyView.getChildAt(0);
        p.body.setAlpha(0f);
        bodyView.addView(p.body);
        pumpHeight(p.body);
        p.body.animate().alpha(1f).setDuration(150).start();
        if (old != null) old.animate().alpha(0f).setDuration(120).start();
        setButtons(p);
        bodyView.postDelayed(new Runnable() {
            @Override public void run() {
                if (old != null) bodyView.removeView(old);
                resetHeight();
            }
        }, 200);
    }

    /**
     * 高度过渡：新页比旧页高/矮时，卡片不能"啪"一下换尺寸。
     * 做法 = 把 body 槽临时钉成固定高度，从当前值动到新内容的测量高度，动画结束再恢复 wrap_content。
     */
    private void pumpHeight(View added) {
        final ViewGroup.LayoutParams lp = bodyView.getLayoutParams();
        final int start = bodyView.getHeight();
        int w = bodyView.getWidth() - bodyView.getPaddingLeft() - bodyView.getPaddingRight();
        if (w <= 0) {
            int screen = dlg.getContext().getResources().getDisplayMetrics().widthPixels;
            w = screen - dp(16) * 2 - dp(20) * 2;
        }
        added.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int end = added.getMeasuredHeight();
        if (start <= 0 || end <= 0 || start == end) return;
        android.animation.ValueAnimator v = android.animation.ValueAnimator.ofInt(start, end);
        v.setDuration(170);
        v.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                lp.height = (Integer) a.getAnimatedValue();
                bodyView.setLayoutParams(lp);
            }
        });
        v.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                bodyView.setLayoutParams(lp);
            }
        });
        v.start();
    }

    private void resetHeight() {
        ViewGroup.LayoutParams lp = bodyView.getLayoutParams();
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bodyView.setLayoutParams(lp);
        }
    }

    private void setButtons(Page p) {
        buttonBar.removeAllViews();
        if (p.negLabel != null) buttonBar.addView(button(p.negLabel, p.negAction));
        if (p.posLabel != null) buttonBar.addView(button(p.posLabel, p.posAction));
        buttonBar.setVisibility(buttonBar.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private TextView button(CharSequence label, final Runnable action) {
        Context c = dlg.getContext();
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(c.getResources().getColor(R.color.accent));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        int bg = selectableBg(c);
        if (bg != 0) b.setBackgroundResource(bg);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (action != null) action.run();
            }
        });
        return b;
    }

    // ---- 页面素材 ----------------------------------------------------------------

    /** 一列可点的行（替代框架的 `setItems` 列表）。行间有 1dp 分隔线，与卡片描边同色。 */
    public static View list(Context c, CharSequence[] labels, final ItemClick click) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(c.getResources().getColor(R.color.card_stroke));
                box.addView(line, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1)));
            }
            final int idx = i;
            TextView row = new TextView(c);
            row.setText(labels[i]);
            row.setTextSize(16);
            row.setTextColor(c.getResources().getColor(R.color.fg));
            row.setPadding(dp(c, 4), dp(c, 12), dp(c, 4), dp(c, 12));
            int bg = selectableBg(c);
            if (bg != 0) row.setBackgroundResource(bg);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { click.onClick(idx); }
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(c);
        sc.setFillViewport(false);
        sc.addView(box);
        return sc;
    }

    /** 一段正文（信息类的"只读一屏"）。 */
    public static View text(Context c, CharSequence body) {
        TextView t = new TextView(c);
        t.setText(body);
        t.setTextSize(15);
        t.setTextColor(c.getResources().getColor(R.color.fg));
        t.setLineSpacing(dp(c, 4), 1f);
        ScrollView sc = new ScrollView(c);
        sc.setFillViewport(false);
        sc.addView(t);
        return sc;
    }

    private static int selectableBg(Context c) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            return tv.resourceId;
        }
        return 0;
    }

    private int dp(int v) {
        return dp(dlg.getContext(), v);
    }

    private static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }
}
