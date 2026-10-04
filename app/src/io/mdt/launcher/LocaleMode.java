package io.mdt.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.util.Locale;

/**
 * 界面语言（P2）。模块边界：只依赖 Config + 资源数组 —— 被 BaseActivity / SettingsActivity / SelfTest 用。
 *
 * ── 为什么需要它 ─────────────────────────────────────────────────────────
 *
 * 默认语言是英文（见 `app/res/values/strings.xml`），中文在 `values-zh/`。
 * 但"跟随系统"只能覆盖一类用户：**系统是中文、却想看英文**（或者反过来的）人没有入口。
 * 桌面版启动器本来就有语言设置，Android 这边不能没有。
 *
 * ── 为什么不用 androidx / LocaleManager ───────────────────────────────────
 *
 * 与 {@link ThemeMode} 同因（本工程**纯 framework**，零 AndroidX）：
 *   · `AppCompatDelegate.setApplicationLocales()` 是 AndroidX 的，拿不到；
 *   · `LocaleManager.setApplicationLocales()` 要 API 33+，而本工程 minSdk 26
 *     ⇒ 26~32 还得另写一套，等于两份实现；
 *   · 而且它写的是**系统层面**的 per-app 语言（会落进系统设置）——
 *     我们不想让启动器的偏好渗到系统里去。
 * ⇒ 自己改 `Configuration` 的 locale 位。这恰好也是 AndroidX 内部的做法。
 *
 * ── 🔴 一条绝对不能碰的红线：**不许调用 `Locale.setDefault()`** ──────────
 *
 * `Locale.setDefault()` 改的是**进程**默认 locale，而 {@link GameSlot} 跑在 `:game` 进程、
 * 与游戏 Activity **同进程**。游戏选语言时读的正是它
 * （`Mindustry-master/core/src/mindustry/Vars.java:536-554`）：
 * <pre>
 *   String loc = settings.getString("locale");     // 游戏自己的设置，默认 "default"
 *   if(loc.equals("default")) locale = Locale.getDefault();   // ← 跟随【进程默认 locale】
 * </pre>
 * ⇒ 我们一旦设了进程默认 locale，**"没在游戏里显式选过语言"的玩家会跟着启动器走** ——
 * 那可能是想要的、也可能是事故（"我明明用的是日语怎么变英文了"）。
 * **只改 Configuration，不碰 Locale.setDefault**，游戏那边就完全不受影响。
 *
 * ── 与 ThemeMode 的关系 ──────────────────────────────────────────────────
 *
 * 两者都是"往 {@link Configuration} 上写自己那几位"，由 {@link BaseActivity} 在
 * `attachBaseContext` 里**合成一次** `createConfigurationContext`（不是套两层）。
 */
public final class LocaleMode {
    private static final String TAG = "MDTLauncher";

    /** 跟随系统（默认）：不改 Configuration 的 locale，系统切语言时系统自己的派发照常工作 */
    public static final String SYSTEM = "";

    /**
     * 自检跑在哪个语言下。
     *
     * 🔴 **自检的语言不许跟着用户的设置走** —— 它的期望值是照中文资源写死的
     *   （`SelfTest` 里那些"· 两侧要有空格""中文与插入值之间无多余空格"）。
     *   用户把界面切成英文之后，自检若跟着走就会**红一片**，而那不是回归、是它本来就这么写的。
     *   ⇒ `runM3` 一进来就 `force()` 到这个语言，让自检**确定性**、与用户设置无关。
     *
     * ⚠️ 这是**过渡**：真正的做法是让那些断言与语言无关（或 zh/en 双跑）。
     *   见 `docs/i18n-feasibility.md` §7.3 / §7.7（P1-B）。
     */
    public static final String SELFTEST = "zh";

    private LocaleMode() {}

    /**
     * 当前生效的语言标签（**已经过名单校验**）。
     *
     * ★ 名单 = `R.array.app_languages`（`translatable="false"`，所以在这里取它不会绕回去）。
     *   不在名单里的值 ⇒ WARNING 点名 + 退回跟随系统（与既有"坏值退默认"纪律一致）。
     * ★ 名单与 `values-*` 目录的一一对应由构建期门禁 `RES-10` 钉着。
     */
    public static String of(Context ctx) {
        Config.get().load(ctx);
        String tag = Config.get().appLanguage();
        if (SYSTEM.equals(tag)) return SYSTEM;
        for (String t : tags(ctx)) {
            if (t.equals(tag)) return tag;
        }
        Log.w(TAG, "config key 'app_language' has unknown tag '" + tag
                + "', falling back to follow-system");
        return SYSTEM;
    }

    /** 支持的语言标签（第 0 个固定是 {@link #SYSTEM}） */
    public static String[] tags(Context ctx) {
        try {
            return ctx.getResources().getStringArray(R.array.app_languages);
        } catch (Throwable t) {
            Log.w(TAG, "read app_languages failed: " + t);
            return new String[]{SYSTEM};
        }
    }

    /**
     * 把「指定语言」写进 `cfg`；**返回是否真的改了**。
     * {@link #SYSTEM} 时**一个字节都不动并返回 false**（调用方据此决定要不要包 Context）——
     * 与 {@link ThemeMode#apply} 同一条理由：跟随系统时不能把系统自己的派发盖住。
     */
    public static boolean apply(Configuration cfg, String tag) {
        if (SYSTEM.equals(tag)) return false;
        Locale loc = toLocale(tag);
        if (loc == null) return false;
        Locale cur = cfg.getLocales().isEmpty() ? null : cfg.getLocales().get(0);
        // ⚠️ setLocale 会顺带按语言把 layoutDirection 设对（AOSP 行为）。
        //    仍然**只改这一份 Configuration**，绝不去动 Locale.setDefault（见类注释的红线）。
        cfg.setLocale(loc);
        return cur == null || !loc.equals(cur);
    }

    /**
     * 强制把上下文包成某个语言（**自检专用**）。
     *
     * 与 {@link #apply} 的差别：这里**无条件**包一层，连 {@link #SYSTEM} / 默认语言也包 ——
     * 自检要的是"钉死在某个语言上"，而不是"跟随用户设置"。
     */
    public static Context force(Context base, String tag) {
        Locale loc = toLocale(tag);
        if (loc == null) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        return base.createConfigurationContext(cfg);
    }

    /** BCP47 标签 → Locale（`zh-TW` 这种写法也认）。认不出来返回 null */
    public static Locale toLocale(String tag) {
        if (tag == null || tag.trim().isEmpty()) return null;
        try {
            return Locale.forLanguageTag(tag.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 语言在设置页里显示成什么。
     *
     * ★ 语言名**用它自己的语言写**（English / 简体中文）—— 这是语言选择器的通行做法：
     *   用户在一个看不懂的界面里，唯一能认出来的就是母语自己的写法。
     *   ⇒ 这些名字住在 `R.array.app_language_names`，标了 `translatable="false"`，
     *     所有语言下都是同一份。
     * ★ 只有第 0 项（跟随系统）是**要翻译**的，所以它是单独一条 string 资源。
     */
    public static CharSequence label(Context ctx, String tag) {
        String[] ts = tags(ctx);
        int idx = -1;
        for (int i = 0; i < ts.length; i++) {
            if (ts[i].equals(tag)) { idx = i; break; }
        }
        if (idx <= 0) return ctx.getString(R.string.lang_system);
        try {
            String[] names = ctx.getResources().getStringArray(R.array.app_language_names);
            if (idx < names.length && names[idx] != null && names[idx].trim().length() > 0) {
                return names[idx];
            }
        } catch (Throwable t) {
            Log.w(TAG, "read app_language_names failed: " + t);
        }
        // 兜底：显示标签本身，总比显示空白强（"拿不到就把那块收掉"在这里不适用 ——
        // 语言选择器少一项等于用户选不了，必须看得见）
        return tag;
    }
}
