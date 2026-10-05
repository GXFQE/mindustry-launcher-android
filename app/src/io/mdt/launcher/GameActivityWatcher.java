package io.mdt.launcher;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * **游戏 activity 一创建就把资产链补挂上**（2026-10-05 现场修的启动失败）。
 *
 * ── 为什么非要这一手 ────────────────────────────────────────────────────
 *
 * 症状：游戏一起来就崩，两个不同的包各崩一处，但**都是读不到资产**：
 * <pre>
 *   official Mindustry   FileNotFoundException: cursors/cursor.png (internal)
 *                        at mindustry.ClientLauncher.setup(…)
 *   MindustryX           FileNotFoundException: mod.hjson (internal)（这条被容忍了）
 *                        + cursors/cursor.png（这条致命）
 * </pre>
 * 而我们自己的启动报告里 `addAssetPath(app|act) cookie=10` **两份都正常** ——
 * 也就是说"坑位那次挂链"是成功的，问题出在**后来才出现的那个 activity**：
 * `arc` 的 `AndroidFi` 用的是**游戏自己那个 activity**（`mindustry.android.AndroidLauncher`）
 * 的 `getAssets()`，它由 `startActivity` 之后系统创建，挂链时还不存在；
 * 这台 ROM 上 `app.getAssets() != activity.getAssets()`（M2 已实测），
 * 新 activity 拿到**第三份**没挂链的 AssetManager ⇒ 游戏读任何资产都 FileNotFoundException。
 *
 * ⇒ 用 `Application.registerActivityLifecycleCallbacks` 的 **`onActivityPreCreated`**（API 29+，
 *   在 activity 的 `onCreate` **之前**）把链补挂上去 —— 必须早于 `onCreate`，
 *   因为 arc 在游戏 activity 的 `onCreate` 里就把 `AndroidFiles` 建好了。
 *   API 26~28 没有这个回调：那时退回 `Activity.onCreate` 之后的时机（老 ROM 上
 *   `app`/`activity` 两份同源，坑位那次挂链本来就够用——这是**兜底**，不是主路径）。
 *
 * ★ 只在 `:game` 进程里注册（主进程没有游戏 activity，注册了也只是空转）。
 * ★ 链为空时 {@link Injector#applyChainTo} 自己会 no-op ⇒ 这里不必再判一次。
 */
final class GameActivityWatcher implements Application.ActivityLifecycleCallbacks {

    private GameActivityWatcher() {}

    static void install(Application app) {
        if (app == null) return;
        try {
            app.registerActivityLifecycleCallbacks(new GameActivityWatcher());
        } catch (Throwable t) {
            android.util.Log.w(LauncherApp.TAG, "register lifecycle failed: " + t);
        }
    }

    @Override public void onActivityPreCreated(Activity activity, Bundle state) {
        Injector.applyChainTo(activity);          // ★ 主路径：早于 onCreate
    }

    /**
     * API 26~28 的兜底（那时框架不调 `onActivityPreCreated`）。
     * ⚠️ API 29+ **必须跳过** —— 否则同一个 activity 会被补挂两次
     * （`Injector.applyChainTo` 内部虽然有实例去重，但白跑一趟探针 + 多写一段报告没意义）。
     */
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (android.os.Build.VERSION.SDK_INT >= 29) return;
        Injector.applyChainTo(activity);
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
