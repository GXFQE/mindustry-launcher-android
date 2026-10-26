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
import android.widget.EditText;
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
 * <h3>两条"别再踩"的实现细节（都是真机抓出来的）</h3>
 * 1. **窗口固定整屏、只让卡片变高**：如果窗口按 wrap_content 跟着内容长，换页时窗口每帧都在 resize，
 *    真机上会闪（用户：「高度有时候还会闪」）。窗口钉死 MATCH_PARENT×MATCH_PARENT 后，
 *    "点外面关闭"改成根 View 的点击（`card_stack_root`），卡片自己吃掉点击。
 * 2. **高度是量出来的确定值，不靠 wrap_content 事后重排**：`measurePage()` 用「EXACT 宽 + AT_MOST 高」
 *    量一次，把页与 body 槽都钉成这个高度；滚动条用 `SCROLLBARS_INSIDE_OVERLAY`
 *    （盖在内容上、不占宽度）⇒ 有没有滚动条都不会把文字挤得重排、也不会在动画末尾"啪"一下换高度。
 *
 * <h3>用法</h3>
 * <pre>
 * CardStack s = new CardStack(activity);
 * s.root(title, CardStack.list(this, labels, cb), cancelLabel, cancelAction).show();
 * s.push(title, CardStack.text(this, body));      // 交叉淡入；返回键 / 「返回」按钮回到上一页
 * </pre>
 *
 * <p>★ 别在 push 出来的页里再 `new AlertDialog`（那就又叠窗口了）—— 要弹就 `push` 一页。
 */
public final class CardStack {

    /** 列表类页面点第几项。 */
    public interface ItemClick {
        void onClick(int index);
    }

    /** 输入页的"确定"：拿到框里的文本。 */
    public interface OnText {
        void run(String text);
    }

    /** 一个按钮 = 文案 + 点下去做什么（顺序即渲染顺序：左 → 右）。 */
    public static final class Btn {
        final CharSequence label;
        final Runnable action;

        public Btn(CharSequence label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    /** 一页 = 标题 + 内容 + 0~3 个按钮。 */
    private static final class Page {
        CharSequence title;
        View body;
        final ArrayList<Btn> buttons = new ArrayList<Btn>();
    }

    private final Dialog dlg;
    private final TextView titleView;
    private final FrameLayout bodyView;
    private final LinearLayout buttonBar;
    private final ArrayList<Page> pages = new ArrayList<Page>();
    private Runnable onDismiss;
    /** 	extPage() 建的那个 TextView（updateText 用）。 */
    private TextView lastText;
    /** 正文槽的高度上限（屏高减去卡片上下留白 + 标题 + 按钮 + 边距，留够余量）。 */
    private final int maxBody;

    public CardStack(Activity a) {
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dialog_card_stack);
        dlg.setCanceledOnTouchOutside(false);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
            // ★ 窗口尺寸钉死：换页时变的是**卡片**高度，窗口一动不动（见类注释 1）。
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            // 这个动画只影响"窗口第一次出现"那一瞬；给 1 ms 空动画是为了让 dim 立刻到位
            // （完全不给动画时，系统会花约 0.5 s 渐入 dim，实测）。
            w.setWindowAnimations(R.style.AppDialogAnim);
        }
        titleView = (TextView) dlg.findViewById(R.id.card_stack_title);
        bodyView = (FrameLayout) dlg.findViewById(R.id.card_stack_body);
        buttonBar = (LinearLayout) dlg.findViewById(R.id.card_stack_buttons);
        View root = dlg.findViewById(R.id.card_stack_root);
        if (root != null) {
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dlg.dismiss(); }
            });
        }
        View card = dlg.findViewById(R.id.card_stack_card);
        if (card != null) {
            // 窗口是整屏 ⇒ "点外面"落在根 View 上；卡片自己吃掉点击，免得点卡片空白处把窗关了
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { }
            });
        }
        maxBody = a.getResources().getDisplayMetrics().heightPixels - dp(150);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (onDismiss != null) onDismiss.run();
            }
        });
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

    /** 第一页（必须是第一页；重复调用会清空栈）。按钮顺序即渲染顺序（左 → 右）。 */
    public CardStack root(CharSequence title, View body, Btn... buttons) {
        Page p = new Page();
        p.title = title;
        p.body = body;
        for (Btn b : buttons) if (b != null) p.buttons.add(b);
        pages.clear();
        pages.add(p);
        render(p, false);
        return this;
    }

    /** 钻进下一页（交叉淡入；不给按钮时自动带一个「返回」）。 */
    public void push(CharSequence title, View body) {
        push(title, body, back());
    }

    public void push(CharSequence title, View body, Btn... buttons) {
        Page p = new Page();
        p.title = title;
        p.body = body;
        for (Btn b : buttons) if (b != null) p.buttons.add(b);
        if (p.buttons.isEmpty()) p.buttons.add(back());
        pages.add(p);
        render(p, true);
    }

    /**
     * 「确认」类的一页：正文 + 「返回」 + 一个动作按钮。
     * ★ 动作按钮点完**不自动关窗** —— 关不关由 `ok` 自己决定（例如先跑动作、再 `dismiss()`）。
     */
    public void pushMessage(CharSequence title, CharSequence message, CharSequence okLabel, Runnable ok) {
        push(title, text(dlg.getContext(), message), back(), new Btn(okLabel, ok));
    }

    /**
     * 输入页：一个单行输入框 + 「返回」 + 动作按钮（动作拿到当前文本）。
     * ★ 用于"另存为 / 命名"这类原来是 `setView(EditText)` 的弹窗 —— 它们同样会换窗、同样会闪。
     */
    public void pushInput(CharSequence title, CharSequence initial, CharSequence hint,
                          CharSequence okLabel, final OnText ok) {
        Context c = dlg.getContext();
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setText(initial == null ? "" : initial);
        if (hint != null) e.setHint(hint);
        e.setTextSize(16);
        e.setTextColor(c.getResources().getColor(R.color.fg));
        e.setHintTextColor(c.getResources().getColor(R.color.fg_muted));
        if (initial != null) e.setSelection(e.getText().length());
        final EditText input = e;
        push(title, wrap(c, input), back(), new Btn(okLabel, new Runnable() {
            @Override public void run() { ok.run(input.getText().toString()); }
        }));
    }

    /** 「返回」按钮（回上一页）。 */
    public Btn back() {
        return new Btn(Trans.get(dlg.getContext(), R.string.back), new Runnable() {
            @Override public void run() { pop(); }
        });
    }

    /** 「关闭」按钮（关掉整张卡）。 */
    public Btn close() {
        return new Btn(Trans.get(dlg.getContext(), R.string.close), new Runnable() {
            @Override public void run() { dlg.dismiss(); }
        });
    }

    /**
     * "既能从页面直接调、也能从卡片里调"的入口用：没有卡片就新开一张（root 这页），
     * 已经有卡片就往里推一页。返回真正承载这一页的那张卡（调用方拿它 dismiss / push）。
     */
    public static CardStack show(CardStack stack, Activity a, CharSequence title, View body, Btn... buttons) {
        if (stack == null) {
            CardStack s = new CardStack(a);
            s.root(title, body, buttons);
            s.show();
            return s;
        }
        stack.push(title, body, buttons);
        return stack;
    }

    /**
     * 用一页正文**顶替当前页**（确认页 → "正在…" → 结果页，三段在同一张卡里换内容）。
     * 没有卡片（或卡片已经关了）就自己开一张；按钮为空 = 这一页不可点（进度页）。
     */
    public void replace(CharSequence title, CharSequence message, Btn... buttons) {
        Page p = new Page();
        p.title = title;
        p.body = textPage(dlg.getContext(), message);
        for (Btn b : buttons) if (b != null) p.buttons.add(b);
        if (!dlg.isShowing()) {
            pages.clear();
            pages.add(p);
            render(p, false);
            dlg.show();
            return;
        }
        if (pages.isEmpty()) pages.add(p); else pages.set(pages.size() - 1, p);
        render(p, true);
    }

    /** 只改这一页的正文（进度数字这种高频刷新）—— 不换页、不播动画。 */
    public void updateText(CharSequence message) {
        if (lastText == null) return;
        lastText.setText(message);
        remeasure();
    }

    private View textPage(Context c, CharSequence body) {
        TextView t = new TextView(c);
        t.setText(body);
        t.setTextSize(15);
        t.setTextColor(c.getResources().getColor(R.color.fg));
        t.setLineSpacing(dp(c, 4), 1f);
        lastText = t;
        return scroller(c, t);
    }

    /**
     * 正文换了以后**重新量一次高度**（"先出壳、后台算完再补正文"这类页面用：存储占用、耗时统计…）。
     * 不调用的话卡片会停在出壳时那个高度上（内容只能在小窗口里滚动）。
     */
    public void remeasure() {
        if (pages.isEmpty()) return;
        Page p = pages.get(pages.size() - 1);
        int h = measurePage(p.body);
        p.body.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, h));
        setBodyHeight(h, true);
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

    /** 关窗时跑一次的钩子（后台补内容的线程靠它停手）。 */
    public void setOnDismiss(Runnable r) {
        onDismiss = r;
    }

    /**
     * 改第 `index` 个按钮的文案（勾选数变化这类"按钮文案跟着状态变"的页面用）。
     * 下标从 0 开始、按渲染顺序（左 → 右）。
     */
    public boolean setButtonLabel(int index, CharSequence label) {
        if (index < 0 || index >= buttonBar.getChildCount()) return false;
        ((TextView) buttonBar.getChildAt(index)).setText(label);
        return true;
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
        final int h = measurePage(p.body);
        p.body.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, h));
        if (!animate) {
            titleView.setText(newTitle);
            bodyView.removeAllViews();
            bodyView.addView(p.body);
            setBodyHeight(h, false);
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
        p.body.animate().alpha(1f).setDuration(150).start();
        if (old != null) old.animate().alpha(0f).setDuration(120).start();
        setBodyHeight(h, true);
        setButtons(p);
        bodyView.postDelayed(new Runnable() {
            @Override public void run() {
                if (old != null) bodyView.removeView(old);
            }
        }, 200);
    }

    /**
     * 量出这一页**确定**的高度：宽用 EXACT（和最终布局一致）、高用 AT_MOST（超过上限就封顶，内部滚动）。
     * ★ 别用 UNSPECIFIED 量完就当目标高度 —— 那样量出来的是"内容自然高"，与真实布局对不上，
     *   动画结束换回 wrap_content 时会"啪"地跳几像素（用户说的"高度还会闪"就是这个）。
     */
    private int measurePage(View page) {
        int w = bodyView.getWidth() - bodyView.getPaddingLeft() - bodyView.getPaddingRight();
        if (w <= 0) {
            int screen = dlg.getContext().getResources().getDisplayMetrics().widthPixels;
            w = screen - dp(16) * 2 - dp(20) * 2;
        }
        page.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxBody, View.MeasureSpec.AT_MOST));
        return Math.min(page.getMeasuredHeight(), maxBody);
    }

    private void setBodyHeight(final int target, boolean animate) {
        final ViewGroup.LayoutParams lp = bodyView.getLayoutParams();
        int start = bodyView.getHeight();
        if (!animate || start <= 0 || start == target) {
            lp.height = target;
            bodyView.setLayoutParams(lp);
            return;
        }
        android.animation.ValueAnimator v = android.animation.ValueAnimator.ofInt(start, target);
        v.setDuration(170);
        v.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                lp.height = (Integer) a.getAnimatedValue();
                bodyView.setLayoutParams(lp);
            }
        });
        v.start();
    }

    private void setButtons(Page p) {
        buttonBar.removeAllViews();
        for (Btn b : p.buttons) buttonBar.addView(button(b.label, b.action));
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

    /** 把任意 View 包成可滚动的一页（自定义表单类页面用）。 */
    public static View wrap(Context c, View child) {
        return scroller(c, child);
    }

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
        return scroller(c, box);
    }

    /**
     * 两行的可点列表（标题 + 副标题）。给"时间戳 + 份数/大小"这类须分两行才看得清的列表用；
     * 单行的那种仍用 {@link #list}。
     */
    public static View list2(Context c, CharSequence[] labels, CharSequence[] subs, final ItemClick click) {
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
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), dp(c, 10));
            int bg = selectableBg(c);
            if (bg != 0) row.setBackgroundResource(bg);
            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setTextSize(16);
            t.setTextColor(c.getResources().getColor(R.color.fg));
            row.addView(t);
            if (subs != null && i < subs.length && subs[i] != null && subs[i].length() > 0) {
                TextView sub = new TextView(c);
                sub.setText(subs[i]);
                sub.setTextSize(12);
                sub.setTextColor(c.getResources().getColor(R.color.fg_muted));
                row.addView(sub);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { click.onClick(idx); }
            });
            box.addView(row);
        }
        return scroller(c, box);
    }

    /** 一段正文（信息类的"只读一屏"）。 */
    public static View text(Context c, CharSequence body) {
        TextView t = new TextView(c);
        t.setText(body);
        t.setTextSize(15);
        t.setTextColor(c.getResources().getColor(R.color.fg));
        t.setLineSpacing(dp(c, 4), 1f);
        return scroller(c, t);
    }

    /**
     * 统一的滚动容器（`res/layout/card_page_scroll.xml`）：**滚动条盖在内容上**。
     * ★ 为什么走 XML：`android:scrollbarStyle` 在框架里**没有公开 setter**（`View.getScrollBarStyle()`
     *   公开、setter 是 @hide，代码里调编译不过）；而滚动条一旦占宽度，内容在"够高/不够高"的临界点
     *   附近切换时文字会重排、卡片高度跟着跳（用户：「高度有时候还会闪」）。
     */
    private static ScrollView scroller(Context c, View child) {
        ScrollView sc = (ScrollView) android.view.LayoutInflater.from(c)
                .inflate(R.layout.card_page_scroll, null, false);
        ViewGroup slot = (ViewGroup) sc.findViewById(R.id.card_page_slot);
        slot.addView(child);
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
