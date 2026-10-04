package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;

/**
 * 所有 Launcher 自有 Activity 的基类（F15）。只做一件事：**让深浅色设置真的生效**。
 *
 * ── 两个动作 ─────────────────────────────────────────────────────────────
 *
 * ① `attachBaseContext` 里把 base 换成 {@link ThemeMode#wrap} 的版本 ——
 *    这是唯一来得及的时机（Resources 还没按 uiMode 解析）。
 *
 * ② `onResume` 里比对"建这个实例时用的模式"与"现在配置里的模式"：
 *    **不一致就 `recreate()`**。
 *
 * ── 为什么必须比对"建实例时用的模式"，而不是比对当前资源 ────────────────
 *
 * 因为 ① 已经把这个 Activity 的资源**改成符合期望**的了 —— 再拿
 * `getResources().getConfiguration().uiMode` 去判断，永远等于期望值，
 * 于是"用户在设置页改完、返回本页"这件事就永远发现不了。
 * 所以记住的是**快照**（{@link #mAppliedMode}），不是当前值。
 *
 * 为什么不靠系统自动重建：系统的配置变化派发只在**它自己**的深浅色变了时发生；
 * 我们改的是自己的 `config.json`，系统毫不知情 ⇒ 必须自己发现、自己重建。
 *
 * ⚠️ `recreate()` 是"销毁当前实例 + 重建"，所以：
 *   · 只在 onResume 里做（在 onCreate 里做 = 死循环）；
 *   · 比对的是配置里的值，配置没变就不会重建 ⇒ 也不会循环；
 *   · `isFinishing()` / 已保存状态时不做（用户正在退出，别多此一举）。
 *
 * ⚠️ GameSlot 要 override {@link #syncThemeOnResume()} 返回 false ——
 *   它跑的是"解 dex / load native / 挂资产链"六步管线，中途被重建会把启动流程打断。
 *   （它的界面只闪一下就被游戏 Activity 盖住，不值得为它冒这个险。）
 */
public abstract class BaseActivity extends Activity {

    /** 建这个实例时用的模式（快照，不是当前值 —— 见类注释） */
    private int mAppliedMode = ThemeMode.SYSTEM;

    @Override
    protected void attachBaseContext(Context base) {
        int mode = ThemeMode.of(base);
        mAppliedMode = mode;
        super.attachBaseContext(ThemeMode.wrap(base, mode));
    }

    /** 子类返回 false 可关掉"回到前台时自查主题"（GameSlot 用） */
    protected boolean syncThemeOnResume() {
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!syncThemeOnResume() || isFinishing()) return;
        // ★ 必须先把盘上的配置读回来：主题是在**另一个 Activity**（设置页）里改的，
        //   而 Config 是进程内单例、只在启动时 load 一次 —— 不 reload 就还是旧值。
        //   （同一条坑：REF §28 的跨进程配置同步、Config.reload 的头注释。）
        Config.get().reload(this);
        if (ThemeMode.of(this) != mAppliedMode) recreate();
    }
}
