package io.mdt.launcher;

import android.content.Context;
import android.widget.TextView;

/**
 * 文案取值的**唯一入口**（2026-10-07）。
 *
 * ── 现在它是什么 ─────────────────────────────────────────────────────────
 *
 * **一层薄壳：完全等价于 {@link Context#getString(int)}**，行为零变化。
 * 之所以先立起来，是因为本工程的文案有两套来源，而它们**不能**同时生效：
 *
 *   ① **系统资源**（`app/res/values` 与 `values-zh` 下的 `strings.xml`）—— 编译进 `resources.arsc`，
 *      跟系统语言走（见 {@link LocaleMode}）。**这是今天唯一在用的那条。**
 *   ② **用户自带的翻译文件**（运行期读，尚未实现）—— 用户丢一份文件进来就能改文案，
 *      不用重新发版。见 `docs/i18n-user-bundles.md`（设计稿与成本定案）。
 *
 * 把调用点先收敛到这**一个**函数上，将来接 ② 时只要改这里一处，
 * 而不必再去动散在 42 个文件里的上千处调用点（实测 1452 处 `R.string.*` 引用）。
 *
 * ── 🔴 接入 ② 时必须守住的三条（先写在这里，免得将来忘）─────────────────
 *
 * 1. **顺序 = 用户包优先、系统资源兜底**。用户包只提供它翻了的那些 key，
 *    没翻的必须**逐字回落到** {@code ctx.getString(resId)}（不是回落英文、更不是显示 key 名）。
 *
 * 2. **转义与格式必须在这里统一处理**：带占位符的那 435 条走 {@link #get(Context, int, Object...)}。
 *    ⚠️ **占位符的序号与类型必须与英文包完全一致** —— `%2$d` 收到字符串会**直接崩主进程**
 *    （第 112 轮真踩过，见 §五 第 6 条）。所以将来**装包时必须逐条比对占位符形态，不匹配就拒绝装**。
 *
 * 3. **resId → 键名的反查要缓存**：将来实现 ② 时需要
 *    {@code getResources().getResourceEntryName(resId)}，而这是 1097 处的**热路径**，
 *    且对非 string 资源会抛 ⇒ 必须 `try/catch` + 缓存（现在还不做，因为用不上）。
 *
 * ── 为什么是静态方法而不是 Context 子类 ──────────────────────────────────
 *
 * 与 {@link ThemeMode} / {@link LocaleMode} 同一条理由：本工程**纯 framework、零 AndroidX**。
 * 而 `BaseActivity.attachBaseContext` 已经把"语言"写进了那一份 {@link android.content.res.Configuration}
 * ⇒ 传进来的 `ctx` **本来就带着正确的语言**，这里不需要再管语言，只做"值从哪来"。
 */
public final class Trans {

    private Trans() {}

    /**
     * 取一条文案。**当前 = {@code ctx.getString(resId)}**。
     *
     * ⚠️ 传 null `ctx` 时返回空串而不是崩 —— 本工程有"拿不到内容就把那块收掉"的纪律
     * （用户 2026-10-04 定案），文案取不到也不该把页面搞崩。
     */
    public static String get(Context ctx, int resId) {
        if (ctx == null) return "";
        return ctx.getString(resId);
    }

    /** 带占位符的那 435 条走这个（`%1$s` / `%2$d` …）。同样，当前等价于 `Context#getString`。 */
    public static String get(Context ctx, int resId, Object... args) {
        if (ctx == null) return "";
        return ctx.getString(resId, args);
    }

    /**
     * 把一个控件上的**静态**文案从布局 XML 搬到 Java（本函数只用于"布局里写死的文案"）。
     *
     * 为什么需要它：布局里的 `android:text="@string/xxx"` 由 **Android 自己解析**，
     * 我们（以及将来的用户包）**够不到** ⇒ 那 25 处必须搬进 Java 才能跟随。
     * 用法：`Trans.bind(mTitle, R.string.log_game_title);`（控件已经在布局里，只是文案搬了家）。
     */
    public static void bind(TextView v, int resId) {
        if (v == null) return;
        v.setText(get(v.getContext(), resId));
    }

    /**
     * 从布局里取控件**并**给它绑文案（省掉"先 findViewById 再 setText"两行）。
     * 用于"文案本来就写在 XML 里、控件也没有 id"的那些位置 —— 顺手把 id 加上即可。
     */
    public static void bind(android.view.View root, int viewId, int resId) {
        if (root == null) return;
        TextView v = root.findViewById(viewId);
        bind(v, resId);
    }
}
