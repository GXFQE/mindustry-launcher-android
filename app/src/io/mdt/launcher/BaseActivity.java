package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/**
 * 所有 Launcher 自有 Activity 的基类（F15 起，P2 扩到语言）。只做一件事：
 * **让「深浅色」与「界面语言」这两个设置真的生效**。
 *
 * ── 两个动作 ─────────────────────────────────────────────────────────────
 *
 * ① `attachBaseContext` 里把 base 换成"配置改过"的版本 ——
 *    这是唯一来得及的时机（Resources 还没按 uiMode / locale 解析）。
 *    ★ **一次包装同时写两位**：{@link ThemeMode#apply} 写 `uiMode` 的 night 位、
 *      {@link LocaleMode#apply} 写 locale 位，**都在同一份 Configuration 上**，
 *      然后只调一次 `createConfigurationContext`。
 *      ⚠️ 写成两次嵌套包装也能跑（第二次读的是第一次的 Configuration），但没必要付两次
 *         Resources 实例化的代价，而且"哪个先"会变成一条隐性依赖。
 *    ★ 两位都"没改"时**原样返回 base** —— 跟随系统时不能把系统自己的派发盖住
 *      （系统切深浅 / 切语言 → Activity 重建，靠的就是那个派发）。
 *
 * ② `onResume` 里比对"建这个实例时用的配置"与"现在配置里的值"：
 *    **不一致就 `recreate()`**。
 *
 * ── 为什么必须比对"建实例时用的值"，而不是比对当前资源 ────────────────────
 *
 * 因为 ① 已经把这个 Activity 的资源**改成符合期望**的了 —— 再拿
 * `getResources().getConfiguration()` 去判断，永远等于期望值，
 * 于是"用户在设置页改完、返回本页"这件事就永远发现不了。
 * 所以记住的是**快照**（{@link #mAppliedTheme} / {@link #mAppliedLang}），不是当前值。
 *
 * 为什么不靠系统自动重建：系统的配置变化派发只在**它自己**的深浅色/语言变了时发生；
 * 我们改的是自己的 `config.json`，系统毫不知情 ⇒ 必须自己发现、自己重建。
 *
 * ⚠️ `recreate()` 是"销毁当前实例 + 重建"，所以：
 *   · 只在 onResume 里做（在 onCreate 里做 = 死循环）；
 *   · 比对的是配置里的值，配置没变就不会重建 ⇒ 也不会循环；
 *   · `isFinishing()` / 已保存状态时不做（用户正在退出，别多此一举）。
 *
 * ⚠️ GameSlot 要 override {@link #syncUiOnResume()} 返回 false ——
 *   它跑的是"解 dex / load native / 挂资产链"六步管线，中途被重建会把启动流程打断。
 *   （它的界面只闪一下就被游戏 Activity 盖住，不值得为它冒这个险。）
 */
public abstract class BaseActivity extends Activity {

    /** 建这个实例时用的深浅色（快照，不是当前值 —— 见类注释） */
    private int mAppliedTheme = ThemeMode.SYSTEM;
    /** 建这个实例时用的界面语言（快照，同上） */
    private String mAppliedLang = LocaleMode.SYSTEM;

    @Override
    protected void attachBaseContext(Context base) {
        int theme = ThemeMode.of(base);
        String lang = LocaleMode.of(base);
        mAppliedTheme = theme;
        mAppliedLang = lang;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        // ⚠️ 用 `|` 而不是 `||`：两个 apply 都必须执行（`||` 会短路，语言就白设了）
        boolean changed = ThemeMode.apply(cfg, theme) | LocaleMode.apply(cfg, lang);
        super.attachBaseContext(changed ? base.createConfigurationContext(cfg) : base);
    }

    /** 子类返回 false 可关掉"回到前台时自查界面配置"（GameSlot 用） */
    protected boolean syncUiOnResume() {
        return true;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyLocalizedTitle();
    }

    /**
     * 把顶栏标题按**应用内语言**重新解析一次。
     *
     * 🔴 为什么必须我们自己动手：`Activity.mTitle` 是**框架**在 `attach()` 里用
     *   `PackageManager.getActivityInfo(...).loadLabel(pm)` 取的 —— 那次解析用的是**系统**语言，
     *   我们在 {@link #attachBaseContext} 里包的那份 Configuration **根本管不到它**
     *   ⇒ 用户把界面切成英文之后，主页顶栏还是「MDT 启动器」（2026-10-04 用户报的
     *   "最上面的大标题没改"）。
     * ★ 其余页面（地图 / 槽 / 设置…）在**子类 `onCreate` 里**显式 `setTitle(getString(...))`，
     *   那是用我们包过的 Context 取的 ⇒ 本来就是对的。这里只补上"清单里那份 label"的兜底，
     *   而且它跑在子类 `onCreate` 之前 ⇒ **子类照样覆盖得了**，不会把已有的标题改坏。
     */
    private void applyLocalizedTitle() {
        try {
            android.content.pm.ActivityInfo ai =
                    getPackageManager().getActivityInfo(getComponentName(), 0);
            if (ai != null && ai.labelRes != 0) setTitle(getString(ai.labelRes));
        } catch (Throwable ignored) {
            // 拿不到就维持框架给的那个（宁可语言不对，也不能因为标题把页面搞崩）
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!syncUiOnResume() || isFinishing()) return;
        // ★ 必须先把盘上的配置读回来：这两项都是在**另一个 Activity**（设置页）里改的，
        //   而 Config 是进程内单例、只在启动时 load 一次 —— 不 reload 就还是旧值。
        //   （同一条坑：REF §28 的跨进程配置同步、Config.reload 的头注释。）
        Config.get().reload(this);
        if (ThemeMode.of(this) != mAppliedTheme
                || !LocaleMode.of(this).equals(mAppliedLang)) recreate();
    }
}
