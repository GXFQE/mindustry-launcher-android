package io.mdt.launcher;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 界面深浅色（F15）。模块边界：只依赖 Config —— 被 BaseActivity / SettingsActivity 用。
 *
 * ── 为什么需要它 ─────────────────────────────────────────────────────────
 *
 * 本工程是**纯 framework**（零 AndroidX / 零 Material Components，见 themes.xml 头注释），
 * 所以拿不到 `AppCompatDelegate.setDefaultNightMode()` —— 那是 AndroidX 的 API。
 * framework 里能覆盖"应用自己的深色模式"的路只有两条：
 *
 *   ① `UiModeManager.setApplicationNightMode()` —— API 31+ 才有，而且它改的是
 *      **系统层面的 per-app 夜间模式**（会写进系统设置、影响该 UID 的 Configuration）。
 *      我们不需要那么重的东西，也不想让启动器的偏好渗到系统里去。
 *   ② **自己包一层 Configuration**：把 `uiMode` 的 night 位改掉再 `createConfigurationContext`。
 *      ⇒ API 17+ 全版本可用、零权限、只影响本 Activity。
 *
 * 选 ②。它恰好也是 AndroidX 内部的做法（`AppCompatDelegateImpl.updateResourcesConfiguration`）。
 *
 * ── 生效原理 ─────────────────────────────────────────────────────────────
 *
 * `values/themes.xml` 与 `values-night/themes.xml` 是靠**资源限定符**选中的，而限定符
 * 的匹配依据就是当前 `Configuration.uiMode` 的 `UI_MODE_NIGHT_MASK` 那两位。
 * 把 base context 换成"night 位被改过"的版本 ⇒ 本 Activity 里所有 `@color/*`、`@style/*`
 * 解析都走另一份 values ⇒ 颜色与主题整套切换，**不需要第二套布局**。
 *
 * ⚠️ 只改 `uiMode` 的 night 位，其余位（屏幕方向 / 尺寸 / 密度）**原样保留** ——
 *   直接 `new Configuration()` 从头造会把横竖屏信息丢掉，那会让布局选错限定符。
 *
 * ⚠️ `SYSTEM` 必须原样返回、**不做任何包装**：跟随系统时如果我们也包一层，
 *   "系统切深浅 → Activity 重建"这条系统自带的派发就看不出来了（因为我们把它盖住了）。
 */
public final class ThemeMode {
    /** 跟随系统（默认）：不改 Configuration，由系统 uimode 决定 */
    public static final int SYSTEM = 0;
    /** 强制浅色 */
    public static final int LIGHT = 1;
    /** 强制深色 */
    public static final int DARK = 2;

    private ThemeMode() {}

    /** 当前配置的模式。会顺手确保 Config 已加载（幂等，见 Config.load）。 */
    public static int of(Context ctx) {
        Config.get().load(ctx);
        return Config.get().themeMode();
    }

    /**
     * 把「强制浅/深」写进 `cfg` 的 `uiMode`；**返回是否真的改了**。
     *
     * ★ `SYSTEM` 时**一个字节都不动并返回 false** —— 调用方据此决定"要不要包 Context"。
     *   跟随系统时如果我们也包一层，"系统切深浅 → Activity 重建"这条系统自带的派发
     *   就看不出来了（因为我们把它盖住了）。
     *
     * ⚠️ 必须在 `Activity.attachBaseContext` 里用（那时 Activity 的 Resources 还没建），
     *   在 onCreate 之后再换就晚了 —— Resources 已经按旧配置解析过一遍。
     *
     * ⚠️ 只改 `uiMode` 的 night 位，**其余位原样保留** —— 直接 `new Configuration()` 从头造
     *   会把横竖屏 / **语言**信息丢掉，那会让布局选错限定符、把语言设置冲掉。
     *   （2026-10-04：这个方法从"自己包一层"改成"只改自己那几位"，
     *     就是为了让 {@link LocaleMode} 能在**同一个** Configuration 上一起改，
     *     而不是套两层 Context。）
     */
    public static boolean apply(Configuration cfg, int mode) {
        if (mode == SYSTEM) return false;
        int night = (mode == DARK) ? Configuration.UI_MODE_NIGHT_YES
                                   : Configuration.UI_MODE_NIGHT_NO;
        int before = cfg.uiMode;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return cfg.uiMode != before;
    }

    /** 模式对应的显示名资源（设置页单选列表与副标题共用，避免两处措辞分叉） */
    public static int labelRes(int mode) {
        switch (mode) {
            case LIGHT: return R.string.theme_light;
            case DARK:  return R.string.theme_dark;
            default:    return R.string.theme_system;
        }
    }
}
