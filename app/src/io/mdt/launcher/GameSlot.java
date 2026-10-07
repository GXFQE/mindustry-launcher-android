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
                    Trans.get(GameSlot.this, R.string.game_pkg_missing_fmt,
                            pkg != null ? "pkg=" + pkg : "apk=" + apkPath), null);
            return;
        }
        final String shown = (label == null ? target.getName() : label);

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
            // ⚠️ 这句写在 **:game 进程**自己的 Config 实例上并落盘；主进程内存里那份看不到，
            //   必须靠 MainActivity.onResume → Config.reload 才能读到
            //   （否则「继续上次」要重启启动器才更新，2026-10-01 复现）。
            Config.get().setLastVersion(shown);
            android.util.Log.i("MDTLauncher", "launch done in " + t.total() + " ms");
        } catch (Injector.LaunchError e) {
            fail(e.step, e.getMessage(), e);
        } catch (Throwable e) {
            fail(Trans.get(GameSlot.this, R.string.step_unknown), String.valueOf(e), e);
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

    private void fail(String step, String msg, Throwable cause) {
        mStatus.setText(Trans.get(GameSlot.this, R.string.game_start_failed_fmt, step));
        StringBuilder sb = new StringBuilder();
        sb.append(Trans.get(GameSlot.this, R.string.game_step_stuck_fmt, step, msg));
        if (cause != null) {
            sb.append("\n\n").append(cause.getClass().getSimpleName());
            StackTraceElement[] st = cause.getStackTrace();
            for (int i = 0; i < st.length && i < 4; i++) {
                sb.append("\n  at ").append(st[i]);
            }
        }
        sb.append(Trans.get(GameSlot.this, R.string.game_report_hint));
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
