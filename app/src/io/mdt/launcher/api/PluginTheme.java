package io.mdt.launcher.api;

/**
 * 插件页能用的**配色与尺寸**（第 187 轮，插件接口 v7）。
 *
 * <h3>为什么要有它</h3>
 * 插件页的 Context 是 {@code ContextWrapper}：{@code getTheme()} 借宿主的、{@code getResources()}
 * 却是插件自己的 —— 两个来源拧着。默认控件（{@code TextView} 之类）能蹭到宿主的默认样式，
 * 但插件自己画卡片 / 徽标 / 分割线时没有可查的语义色，只能硬编码；**深浅色一换，这一页就是
 * 唯一不跟着变的地方**。
 *
 * <p>这里给的是宿主**界面语义表**（与 {@code res/values/colors.xml} 同一份）：
 * 按它们是"背景 / 卡片 / 正文 / 次要正文 / 分割线 / 强调色"，插件拿到的已经**解析成 ARGB**，
 * 深色模式下自然就是深色那一套（宿主在 {@code attachBaseContext} 里换过配置）。
 *
 * <h3>怎么用</h3>
 * <pre>
 *   PluginTheme t = api.theme();
 *   view.setBackgroundColor(t.card);
 *   text.setTextColor(t.textMuted);
 *   int pad = t.dp(14);
 * </pre>
 *
 * <p>⚠️ 这是**当时**的快照：宿主换深浅色会重建 Activity，插件页会重新加载，重新取即可。
 * <p>⚠️ 它只覆盖"页面底色 / 卡片 / 文字 / 分割线 / 强调色"这几档；芯片、警告色这类更细的
 * 语义留给插件自己的资源（插件自己的 {@code values-night} 也会跟着宿主的配置生效）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public final class PluginTheme {

    /** 页面底色。 */
    public final int background;
    /** 卡片底色。 */
    public final int card;
    /** 卡片描边（浅色下就是这条把卡片从底色里拉出来）。 */
    public final int cardStroke;
    /** 分割线。 */
    public final int divider;
    /** 正文 / 标题。 */
    public final int text;
    /** 次要正文（副标题、说明）。 */
    public final int textMuted;
    /** 强调色（可点文字、主按钮）。 */
    public final int accent;
    /** 强调色上的文字。 */
    public final int onAccent;
    /** 顶栏底色。 */
    public final int appbar;
    /** 顶栏上的文字 / 图标。 */
    public final int onAppbar;
    /** 当前是不是深色。 */
    public final boolean dark;

    private final float density;
    private final float scaledDensity;

    /**
     * @param density       屏幕密度（dp ⇒ px）
     * @param scaledDensity 字体缩放后的密度（sp ⇒ px）
     */
    public PluginTheme(int background, int card, int cardStroke, int divider, int text,
                       int textMuted, int accent, int onAccent, int appbar, int onAppbar,
                       boolean dark, float density, float scaledDensity) {
        this.background = background;
        this.card = card;
        this.cardStroke = cardStroke;
        this.divider = divider;
        this.text = text;
        this.textMuted = textMuted;
        this.accent = accent;
        this.onAccent = onAccent;
        this.appbar = appbar;
        this.onAppbar = onAppbar;
        this.dark = dark;
        this.density = density;
        this.scaledDensity = scaledDensity;
    }

    /** dp ⇒ px（与宿主界面同一套换算）。 */
    public int dp(float v) {
        return Math.round(v * density);
    }

    /** sp ⇒ px（跟着系统的字体缩放）。 */
    public int sp(float v) {
        return Math.round(v * scaledDensity);
    }

    @Override public String toString() {
        return "PluginTheme{" + (dark ? "dark" : "light")
                + ", bg=" + Integer.toHexString(background)
                + ", text=" + Integer.toHexString(text) + "}";
    }
}
