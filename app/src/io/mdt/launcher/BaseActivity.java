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

    /**
     * 第 160 轮：**所有页面**统一挂插件动作区（页面布局里有 {@code plugin_section} 才挂）。
     *
     * <p>放在 {@code onPostCreate}：子类 {@code onCreate} 已经 `setContentView`，容器一定在树里；
     * 又比 {@code onResume} 早，用户第一眼就能看到。没有条目的页会整块 `GONE`，不占高度。
     *
     * <p>★ 四个内容页不走这里（它们把动作行插在「删除」上面，见 {@code PluginEntries.attach}）
     * —— 它们的布局里没有 {@code plugin_section}，`pluginPageId()` 也不 override（默认 null）。
     */
    @Override
    protected void onPostCreate(android.os.Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        String page = pluginPageId();
        if (page == null || isFinishing()) return;
        PluginEntries.attachPage(this, page, pluginSlot(), pluginSelectedFile());
    }

    /** 这一页的插件 page id；null = 本页不挂通用插件区（内容页 / 插件页 / 游戏页）。 */
    protected String pluginPageId() {
        return null;
    }

    /** 这一页给插件的槽（默认当前槽；槽详情页 override 成自己那一槽）。 */
    protected String pluginSlot() {
        return Data.currentSlot(this);
    }

    /** 详情页当前选中的那一份；其余页 null。 */
    protected java.io.File pluginSelectedFile() {
        return null;
    }

    /** 子类返回 false 可关掉"回到前台时自查界面配置"（GameSlot 用） */
    protected boolean syncUiOnResume() {
        return true;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyLocalizedTitle();
        applyUpAffordance();
    }

    /**
     * ★★ 二级 / 三级页的「返回」统一成一条（2026-10-16 用户报的问题）：
     * **模组页点返回回主页面、存档页回槽页、地图与蓝图页根本**没有**返回。**
     *
     * ── 原因 ──────────────────────────────────────────────────────────────
     * 平台的"向上"箭头是**清单里声明了 `android:parentActivityName` 才会画**，
     * 而点下去走的是**声明的父级** —— 于是各页的表现取决于清单里那一行，写漏 = 没有返回，
     * 写错 = 回到错的层级（模组页原来声明的是主页，可它平时是从**槽页**进的）。
     *
     * ── 决定 ──────────────────────────────────────────────────────────────
     * ① 有箭头：清单里声明了父级的页面（即所有二级 / 三级页）；
     * ② 点箭头 = **返回我来的那一页**（`finish()`），**不是**跳到声明的父级 ——
     *    同一页可能从多处进（地图页既能从槽页进、也能从批量导入的深链进），而
     *    `parentActivityName` 只能写一个；照父级跳会在另一条路上跳错层，
     *    更糟的是槽页**没有槽名会立刻 `finish()`**（用户会看到闪一下）。
     *    `finish()` 永远等于"上一页"，与系统返回手势一致。
     * ③ 主页面与游戏页（`GameSlot`）没有父级 ⇒ 不画箭头；
     *    ⚠️ 这里**先判父级再取 ActionBar** —— GameSlot 跑的是六步启动管线，
     *    少碰它的窗口一下是一下（它也没有 ActionBar）。
     */
    private void applyUpAffordance() {
        if (getParentActivityIntent() == null) return;
        android.app.ActionBar ab = getActionBar();
        if (ab == null) return;
        ab.setDisplayHomeAsUpEnabled(true);
        // ★ 第 180 轮（真机截图发现）：框架默认的返回箭头取 `textColorPrimary` —— 浅色主题下是
        //   近黑色，压在彩色的 appbar 上几乎看不清（标题走 AppBarTitle 是对的，只有箭头不对）。
        //   ⇒ 换成自带 @color/on_appbar 的那份；深色主题下同样是浅色箭头，两份主题都不用第二份资源。
        ab.setHomeAsUpIndicator(R.drawable.ic_up);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        // 箭头 = 上一页（见 applyUpAffordance 的三条）；其余菜单项照旧交给子类 / 框架。
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        // ★ F23「崩了马上说一声」：记下"谁是当前前台页面"——崩溃提示要按这个决定
        //   "现在弹对话框"还是"只 Toast"（游戏还在前台时弹窗没人看得见）。见 CrashAlert。
        CrashAlert.activityResumed(this);
        // ★ 第 131 轮：启动真的过了这一关 ⇒ 摘掉插件的安全阀（`Plugins.GUARD`）。
        //   位置放在 super.onResume() **之后**：插件要是把主界面搞崩，异常发生在 super 里，
        //   那就走不到这一句、安全阀留着 ⇒ 下次启动不注入插件（这才是要的行为）。
        Plugins.markBootOk(this);
        if (!syncUiOnResume() || isFinishing()) return;
        // ★ 必须先把盘上的配置读回来：这两项都是在**另一个 Activity**（设置页）里改的，
        //   而 Config 是进程内单例、只在启动时 load 一次 —— 不 reload 就还是旧值。
        //   （同一条坑：REF §28 的跨进程配置同步、Config.reload 的头注释。）
        Config.get().reload(this);
        if (ThemeMode.of(this) != mAppliedTheme
                || !LocaleMode.of(this).equals(mAppliedLang)) recreate();
    }

    @Override
    protected void onPause() {
        CrashAlert.activityPaused(this);
        super.onPause();
    }
}
