package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * 游戏坑位（`android:process=":game"`）—— 加载流程必须跑在**游戏将要落地的那个进程**里，
 * 否则系统会在干净进程里找不到游戏类（探针 §二 已验证）。
 *
 * 本类只做三件事，加载细节全在 {@link Injector}：
 *   ① 解析目标版本（--es pkg 已装包名 / --es apk 包路径）
 *   ② **启动前**把存档槽准备好 —— F0 起改为「默认什么都不搬，只注入
 *      `mindustry.data.dir` 系统属性」；只有 `build ≤ 146` 的远古包（dex 里没有那个
 *      属性，见 {@link Compat#PROP_DATA_DIR}）才回退到改造前的**改名交换**。
 *      ★ 必须在游戏起来**之前**做完：属性是本进程的静态状态，`files/` 布局也是那一刻定死的。
 *   ③ 跑六步管线；失败时把"卡在哪一步"直接告诉用户
 *
 * ⚠️ 切版本必须先 force-stop：游戏 Activity 是 singleTask，不杀进程直接再 start 时
 *    进程被复用、Activity 不重建，**跑的其实还是旧版本**，只是白做一次注入。
 *    UI 侧（MainActivity）负责在拉起本 Activity 前 force-stop 掉 :game 进程 —— 见其
 *    startVersion()，用的是 ActivityManager.killBackgroundProcesses / 进程自杀式让位。
 *    ★ 这条同时是 {@link Data#reclaimLegacy}（"把借住 files/ 的本体收回"）的**安全前提**：
 *      本 Activity 一旦开始跑，上一个 :game 必然已经死了。
 */
public class GameSlot extends BaseActivity {

    /** v23：进程内探针调度器（null = 没有探针 / 已停）。 */
    private ProbeRunner mProbes;

    /**
     * 入口页透传的"该包要不要走改名交换"（F0）。
     * ★ 为什么要透传：`MainActivity.startVersion` 为了 F18 的兼容性复检**本来就要**
     *   把 APK 打开探一次；再让本进程探一遍等于白读一遍 dex（几十 MB）。
     * ⚠️ **探不出来时不要放这个 extra** —— 让本进程自己探，
     *   这样"探测失败"的兜底判据只有一处（{@link #needsLegacySlot}）。
     */
    static final String EXTRA_LEGACY_SWAP = "legacy_swap";

    private TextView mStatus;

    /**
     * F15：**不要**在回到前台时自查界面配置（深浅色 + 语言）。
     * 本 Activity 的 onCreate 里跑的是六步加载管线（dexdump 注入 / load native / 挂资产链），
     * 中途被 `recreate()` 会把整条启动流程打断。它显示的"正在启动"只闪一下就被游戏盖住，
     * 不值得为它冒险 —— 代价是它固定用"建实例那一刻"的深浅色与语言。
     */
    @Override
    protected boolean syncUiOnResume() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildStatusUi());
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent it) {
        String pkg = it == null ? null : it.getStringExtra("pkg");
        String apkPath = it == null ? null : it.getStringExtra("apk");
        String slot = it == null ? null : it.getStringExtra("slot");
        // v31：这一局的版本信息快照（探针写账本用；身份仍是下面的 vkey）
        Bundle vinfo = it == null ? null : it.getBundleExtra(ProbeService.EXTRA_VINFO);

        // ① 解析目标
        File target = null;
        String label = null;
        if (apkPath != null && !apkPath.isEmpty()) {
            target = new File(apkPath);
            label = target.getName();
        } else if (pkg != null && !pkg.isEmpty()) {
            target = Versions.apkOfPackage(this, pkg);
            label = pkg;
        }
        if (target == null || !target.exists()) {
            fail(Trans.get(GameSlot.this, R.string.step_prepare),
                    Trans.get(GameSlot.this, R.string.game_pkg_missing_fmt), null);
            return;
        }
        final String shown = (label == null ? target.getName() : label);
        // v30：这一局的版本 key（与 Versions.Entry.key() 同一套：已装 = 包名，导入 = import:文件名）
        // ★ 不另加 extra：这两条分支本来就把"是哪个版本"带齐了，再从 intent 塞一份只会多一个漂移源。
        final String vkey = (pkg != null && !pkg.isEmpty())
                ? pkg.trim() : "import:" + target.getName();
        final Map<String, String> vmap = mapOf(vinfo);

        // ② 存档槽：必须在游戏起来之前定下来
        boolean legacy = needsLegacySlot(target, it);
        String rep = Data.prepareSlot(this, slot, legacy);
        if (!legacy) {
            File dir = Data.dataRoot(this);
            if (dir == null) {
                rep += "⚠ 拿不到数据根目录 ⇒ 属性未注入，游戏将写它自己的默认根\n";
            } else {
                if (!dir.exists()) dir.mkdirs();
                System.setProperty(Compat.PROP_DATA_DIR, dir.getAbsolutePath());
                rep += "③ 注入 " + Compat.PROP_DATA_DIR + " = " + dir.getAbsolutePath() + "\n";
            }
        }
        android.util.Log.i("MDTLauncher", "slot prepare:\n" + rep);
        mStatus.setText(Trans.get(GameSlot.this, R.string.game_starting_fmt, shown, Data.currentSlot(this)));

        // ③ 六步管线（同步执行，与探针一致；前置段 ~200-500 ms，游戏自身加载另有 4~5 s）
        Injector.Plan plan = new Injector.Plan();
        plan.apk = target;
        try {
            Injector.Timing t = Injector.launch(this, plan);
            // dev：探针验证台（产品版 ProbeLab 是空壳，见 app/src-release）
            ProbeLab.runIfArmed(this);
            startProbes(vkey, vmap, vinfo);
            // ⚠️ 这句写在 **:game 进程**自己的 Config 实例上并落盘；主进程内存里那份看不到，
            //   必须靠 MainActivity.onResume → Config.reload 才能读到
            //   （否则「继续上次」要重启启动器才更新，2026-10-01 复现）。
            Config.get().setLastVersion(shown);
            android.util.Log.i("MDTLauncher", "launch done in " + t.total() + " ms");
        } catch (Injector.LaunchError e) {
            fail(e.step, e.getMessage(), e);
        } catch (Throwable e) {
            fail(Trans.get(GameSlot.this, R.string.step_unknown),
                    Trans.get(GameSlot.this, R.string.launch_err_unknown), e);
        }
    }

    /**
     * 该包要不要走**改名交换**（= 它不支持 F0 的属性注入）。
     *
     * 判据来源（唯一性很关键，别在两处各判一次）：
     *   · 有 {@link #EXTRA_LEGACY_SWAP} ⇒ 用入口页那次探测的结果（免重复读 dex）；
     *   · 没有 ⇒ 自己探一次（dev 直连 `am start -n .GameSlot`、以及将来任何新入口）。
     *
     * ★★ **探不出来时一律按"要改名交换"处理** —— 这个方向的错**只是慢**（不并行），
     *   反方向的错是**游戏写 files/ 而 HUB 去 slot-<名> 找** ⇒ 用户的存档"看起来没了"。
     *   （后者其实也能自愈：下一次启动的 {@link Data#reclaimLegacy} 会发现 files/ 里有数据
     *   并把本体声明回 files/。但那要等到下一局，用户这一局已经吓一跳了。）
     */
    private boolean needsLegacySlot(File apk, Intent it) {
        if (it != null && it.hasExtra(EXTRA_LEGACY_SWAP)) {
            return it.getBooleanExtra(EXTRA_LEGACY_SWAP, false);
        }
        try {
            return Compat.probe(apk).needsRename();
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "slot-mode probe failed, assume legacy: " + t);
            return true;
        }
    }

    /**
     * 启动失败弹窗。
     *
     * <p>🔴 2026-10-15（第 150 轮）：这条**只给第一层** —— 环节名（人话）+ 一句原因。
     * 原来还追加了 `cause.getClass().getSimpleName()` 与 4 行 `at …` 堆栈，用户拿到的是一屏
     * `LaunchError / at io.mdt.launcher.Injector…(Injector.java:224)`（§78：类名与原始异常
     * **只许出现在"原始诊断"那一层**）。现在原文进 `report-launch.txt`（{@link Injector} 写）。
     * <p>⚠️ "详细记录已写进运行日志"那句**只在真有报告时**才说 —— 找不到游戏包那条路
     * 在 Injector 之前就返回、**根本没写报告**（真机实测 report-launch.txt 一字未动）。
     */
    /**
     * v23：起探针 —— 进程内的自己跑，隔离的交给 `:probe`。
     *
     * <p>会话目录名 = `yyyyMMdd-HHmmss-<槽>`：两边的探针写进**同一个会话目录**，
     * 插件事后按目录读就行（宿主不聚合）。
     *
     * <p>v30：把这一局的**版本 key** 一起给探针 —— 插件要按版本分账（见 {@code ProbeEnv#version()}）。
     * <p>v31：再把**版本信息快照**一起给（见 {@code ProbeEnv#versionInfo()}），插件写账本时连版本号一起存。
     */
    private void startProbes(String versionKey, Map<String, String> info, Bundle infoBundle) {
        try {
            String session = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(new java.util.Date())
                    + "-" + Data.currentSlot(this);
            mProbes = ProbeRunner.start(this, android.os.Process.myPid(), true, session,
                    Data.currentSlot(this), versionKey, info);
            Intent svc = new Intent(this, ProbeService.class);
            svc.putExtra(ProbeService.EXTRA_PID, android.os.Process.myPid());
            svc.putExtra(ProbeService.EXTRA_RUN, true);
            svc.putExtra(ProbeService.EXTRA_SESSION, session);
            svc.putExtra(ProbeService.EXTRA_VERSION, versionKey);
            if (infoBundle != null) svc.putExtra(ProbeService.EXTRA_VINFO, infoBundle);
            startService(svc);
            // v29：把游戏 activity 的前后台变化转给探针（宿主只报事件，记不记由插件定）
            GameActivityWatcher.setForegroundListener(new GameActivityWatcher.Foreground() {
                @Override public void onGameForeground(boolean fg) {
                    ProbeRunner r = mProbes;
                    if (r != null) r.foreground(fg);
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("MDTPlugin", "probe start failed", t);
        }
    }

    /** Bundle → 字符串 map（探针只认字符串；空值 / 缺键就不放）。排序只为日志稳定。 */
    private static Map<String, String> mapOf(Bundle b) {
        Map<String, String> m = new TreeMap<String, String>();
        if (b != null) {
            for (String k : b.keySet()) {
                Object v = b.get(k);
                if (v != null) m.put(k, String.valueOf(v));
            }
        }
        return m;
    }

    private void fail(String step, String msg, Throwable cause) {
        mStatus.setText(Trans.get(GameSlot.this, R.string.game_start_failed_fmt, step));
        StringBuilder sb = new StringBuilder();
        sb.append(Trans.get(GameSlot.this, R.string.game_step_stuck_fmt, step, msg));
        if (cause != null) {
            sb.append(Trans.get(GameSlot.this, R.string.game_report_hint));
            android.util.Log.w("MDTLauncher", "launch failed at " + step, cause);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.game_cannot_start)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private LinearLayout buildStatusUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        mStatus = new TextView(this);
        mStatus.setTextSize(16);
        mStatus.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        mStatus.setPadding(pad, pad, pad, pad);
        box.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }
}
