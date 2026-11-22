package io.mdt.launcher;

import android.app.Application;
import android.util.Log;

/**
 * Application。onCreate 在主进程与 :game 进程各跑一次 —— 必须保持轻量
 * （只做 Config 加载；M2 的注入流程不在 App 里做，全在 GameSlot）。
 */
public class LauncherApp extends Application {
    public static final String TAG = "MDTLauncher";

    /**
     * ★★ 2026-10-11（第 131 轮）：**插件在进程最早点注入** —— 这是"重启生效"的全部实现。
     *
     * <p>为什么必须在这里（而不是像 S0 演示那样"打开插件页时才注入"）：类一旦被加载就换不掉，
     * 只有宿主**还没加载过**的同名类才能被插件顶掉。等到 Activity 起来时，`MainActivity` 一大家子
     * 早就在 CL 里了 ⇒ 插件永远只能新增自己的页面、碰不到宿主逻辑。
     *
     * <p>⚠️ 早注入是**唯一**能把启动器砖掉的动作 ⇒ 它与 {@link Plugins} 里的安全阀（guard）
     * 是同一件事的两半，不能只留一半。这里没有 try/catch 是因为 `Plugins.injectEarly` 自己
     * 绝不抛（它内部把任何异常都记下来并跳过插件）。
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Plugins.injectEarly(this);
    }

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
        // ★★ 第 412 轮：下面这段（F0 迁移 + Recovery）**只允许主进程做** —— Application.onCreate
        //   每个进程都会跑（`:game` / `:probe` 也是），而 Recovery 会写 `config.json`
        //   （崩溃后自动停用补丁）：两个进程各持一份 Config 内存副本，谁后写谁赢 ⇒
        //   真机复现过「report-patch-crash.txt 记了已停用、config 里却还启用着」的复活。
        //   全局记账只留给主进程；`:game` 侧本来就有 prepareSlot 自己那份兜底。
        if (Util.isMainProcess(this)) startupRecovery();
        Config.get().load(this);
        // 第 378 轮：把用户的下载限速交给全局额度（0 = 不限）。改设置时由设置页再调一次。
        Bandwidth.set(Config.get().downloadLimit());
        // ★ 第 383 轮（用户：「不如把所有缓存类的东西都合并给一个自动清理的开关」）：
        //   一个开关管三样（游戏 cache / 分享 cache / 旧版本重复文件）。**每 24h 最多一次**、
        //   只在主进程且游戏没在跑时；自己起后台线程，不阻塞启动。
        //   手动「存储占用 → 清理」不受这个间隔限制。
        try {
            Cleanup.autoCleanInBackground(this);
        } catch (Throwable t) {
            Log.w(TAG, "auto cleanup scheduling failed: " + t);
        }
        // 2026-11-22：游戏包去重的孤儿回收。引用集 = 全部版本的清单，起一次就够
        // （启动不再物化任何包，运行期不产生临时件）。只在主进程、游戏没在跑时做，
        // 后台线程、失败静默 —— 它一项都不能拖慢冷启动。
        if (Config.get().casGameEnabled() && Util.isMainProcess(this)) {
            try {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!WriteGate.blockedSlotOrGlobal(LauncherApp.this, null)) {
                                PkgAdmin.collect(LauncherApp.this);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "pkg gc failed: " + t);
                        }
                    }
                }, "pkg-gc").start();
            } catch (Throwable t) {
                Log.w(TAG, "pkg gc scheduling failed: " + t);
            }
        }
        Log.i(TAG, "app up, privateDir=" + Paths.privateDir(this)
                + " pid=" + android.os.Process.myPid());
    }

    /**
     * F0 迁移 + 启动自愈：**只允许主进程调用**。
     *
     * <p>⚠️ 别把它挪回 `onCreate` 的公共路径：`:probe` 里 `Data.gameAlive` 也是 false，
     * 它一旦跟着跑就会和主进程各写一份 `config.json` —— 补丁"崩溃后自动停用"会被
     * 另一个进程的旧副本盖回去（第 412 轮真机复现）。
     */
    private void startupRecovery() {
        if (!Data.gameAlive(this)) {
            String r = Data.reclaimLegacy(this);
            if (r != null) Log.i(TAG, "F0 一次性迁移：\n" + r);
            // ★ 第 177 轮（杀后台 P0）：长操作被强杀留下的 temp / `.part` / `.old`
            //   在这里清掉（同步 —— 此刻不可能有我们的长操作在跑，没有竞态）。
            //   见 Recovery 的类注释；`Data.gameAlive` 为真时绝不动（游戏可能正在写槽）。
            try {
                Recovery.runNow(this);
            } catch (Throwable t) {
                Log.w(TAG, "startup recovery failed: " + t);
            }
        } else {
            // 第 367 轮（多开）：有实例在跑时，其余自愈都动共享目录 ⇒ 仍不动；
            // 但**逐槽 `launchid.dat` 自愈**照跑 —— 别的槽的残留 marker 不该因为这个
            // 实例在跑而一直不清（每个槽只按它自己的实例判，见 Recovery.healLaunchMarkers）。
            try {
                Recovery.healWhileInstanceRunning(this);
            } catch (Throwable t) {
                Log.w(TAG, "startup slot heal failed: " + t);
            }
        }
    }
}
