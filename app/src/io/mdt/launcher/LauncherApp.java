package io.mdt.launcher;

import android.app.Application;
import android.util.Log;

/**
 * Application。onCreate 在主进程与 :game 进程各跑一次 —— 必须保持轻量
 * （只做 Config 加载；M2 的注入流程不在 App 里做，全在 GameSlot）。
 */
public class LauncherApp extends Application {
    public static final String TAG = "MDTLauncher";

    @Override
    public void onCreate() {
        super.onCreate();
        // ★★ 2026-10-04（第 86 轮）：主进程的未捕获异常落盘。放在**最前面** ——
        //   下面那几件事（迁移/回收）本身也可能抛，装晚了就白装。内部自己判"是不是主进程"，
        //   所以在 :game 进程里是空操作（那边是游戏的进程，有自己的崩溃转储）。
        Crash.installIfMainProcess(this);
        // ★★ 2026-10-05：**游戏 activity 一创建就把资产链补挂上**。
        //   起因：`arc` 用的是游戏自己那个 activity 的 AssetManager，而它由 `startActivity`
        //   之后系统创建 —— 坑位挂链时它还不存在。这台 ROM 上 `app.getAssets() != activity.getAssets()`，
        //   新 activity 会拿到第三份没挂链的实例 ⇒ 游戏起来就 `FileNotFoundException: cursors/cursor.png`。
        //   详见 GameActivityWatcher 的类注释（含两个包各自的崩溃现场）。
        //   ⚠️ **无条件注册**：主进程里 `Injector.sChain` 永远是 null ⇒ 回调自己 no-op。
        //      反过来（只在 :game 注册）要靠 `Util.isMainProcess` 判，而它"拿不准就当主进程"
        //      ⇒ 判不准时正好**漏装**，那是这个修复最不该有的失败方式。
        GameActivityWatcher.install(this);
        // 历史版本把私有数据误放 getFilesDir()（会被游戏在首次启动时复制进数据根），
        // 这里幂等地搬到 app_hub。必须在 Config.load 之前做。
        Paths.migrateFromLegacy(this);
        // ★ F0 一次性迁移（2026-10-02）：改造前的布局是「**当前槽 = 数据根本体 `files/`**」，
        //   升级后 `dirOf(当前槽)` 会指向还不存在的 `slot-<当前槽>` ⇒ 界面显示
        //   "当前槽 0 个文件"，用户会以为存档没了（文件其实都在 `files/`）。
        //   所以**在任何人读路径之前**把它收回来 —— 这是 Application.onCreate，比所有
        //   Activity 都早，而且与 :game 启动前的 prepareSlot 调的是**同一份实现**（幂等）。
        //
        //   ⚠️ 必须自己判 `!gameAlive`：主进程可能被系统回收后在 `:game` 还活着时重建，
        //      此时搬动游戏正在写的目录是不允许的。而在 :game 进程里这一句必然为真
        //      （自己就是 `:game`）⇒ 迁移只可能发生在主进程，:game 侧仍由 prepareSlot 负责。
        //   ⚠️ 反过来，若这里**不做**，那个窗口期还有个后果：打开存档页 → `savesDirOf`
        //      的 `mkdirs` 会造出一个空的 `slot-<当前槽>/`，而 reclaimLegacy 的
        //      "槽侧空壳"分支会把它归档走（现在已按**内容**而非 `exists()` 判，不会卡住迁移）。
        if (!Data.gameAlive(this)) {
            String r = Data.reclaimLegacy(this);
            if (r != null) Log.i(TAG, "F0 一次性迁移：\n" + r);
        }
        Config.get().load(this);
        Log.i(TAG, "app up, privateDir=" + Paths.privateDir(this)
                + " pid=" + android.os.Process.myPid());
    }
}
