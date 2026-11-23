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
     * 2026-11-17：这一局的宿主时钟（自动备份据此判断"这一局跑到什么时候"）。
     * null = 没起来（参数不全 / 异常）——**不许**因此挡住游戏启动。
     */
    private SessionClock mClock;

    /**
     * 入口页透传的"该包要不要走改名交换"（F0）。
     * ★ 为什么要透传：`MainActivity.startVersion` 为了 F18 的兼容性复检**本来就要**
     *   把 APK 打开探一次；再让本进程探一遍等于白读一遍 dex（几十 MB）。
     * ⚠️ **探不出来时不要放这个 extra** —— 让本进程自己探，
     *   这样"探测失败"的兜底判据只有一处（{@link #needsLegacySlot}）。
     */
    static final String EXTRA_LEGACY_SWAP = "legacy_swap";

    /**
     * v49：这一局要带进游戏的**联机地址**（`host:port`，端口可省）。
     *
     * <p>由主进程从 {@code PluginApi.launch(key, slot, hostPort, cb)} 透传下来；
     * 本进程原样塞进 {@link Injector.Plan#hostPort}，由 Injector 拼成
     * `mindustry://host:port` 挂在游戏 Activity 的 Intent data 上。
     * 没有这个 extra = 普通启动。
     */
    static final String EXTRA_HOST_PORT = "host_port";

    /** 第 366 轮：这一局解析出来的实例身份（启动失败时要按它把主进程的占位销掉） */
    private String mVkey;
    private String mLaunchSlot;

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

    /**
     * 第 275 轮：**不吃**「屏幕方向」设置。
     * 这一页只闪一下就被游戏自己的 Activity 盖住，方向该由游戏那张清单
     * （`screenOrientation="user"`）决定；在这里强制一次只会让启动画面多翻一次。
     */
    @Override
    protected boolean honorsOrientation() {
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
        // 同一个 :game 进程被 onNewIntent 再拉一次（坑位重进）⇒ 上一局的时钟先停掉，
        // 否则它会继续往旧文件里写"还在跑"。
        if (mClock != null) {
            mClock.end();
            mClock = null;
        }
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
        final String shown = (label == null ? (target == null ? "" : target.getName()) : label);
        // v30：这一局的版本 key（与 Versions.Entry.key() 同一套：已装 = 包名，导入 = import:文件名）
        // ★ 不另加 extra：这两条分支本来就把"是哪个版本"带齐了，再从 intent 塞一份只会多一个漂移源。
        final String vkey = (pkg != null && !pkg.isEmpty())
                ? pkg.trim() : (target == null ? null : "import:" + target.getName());
        // ★ 第 418 轮：CAS 模式下导入 APK 已经被删掉（内容全在池里，见 PkgAdmin.enable）——
        //   存在性判据要和 Injector.launch 里那条放宽同源：清单还在、能走 CAS，就放行。
        //   （以前只放宽了 Injector，这里先 fail 掉 ⇒ :game 进程一进来就死，"任何游戏无法启动"。）
        final boolean casReady = vkey != null && PkgLauncher.lookup(this, vkey) != null;
        if (target == null || (!target.exists() && !casReady)) {
            fail(Trans.get(GameSlot.this, R.string.step_prepare),
                    Trans.get(GameSlot.this, R.string.game_pkg_missing_fmt), null);
            return;
        }
        final Map<String, String> vmap = mapOf(vinfo);

        // ② 存档槽：必须在游戏起来之前定下来
        // ★ 第 408 轮：整端补丁可以换掉游戏本体（代码 / native），"支不支持属性注入"必须按
        //   **生效的那份 dex** 判，而不是基座。解析补丁要实例键（版本 + 槽），槽名在
        //   prepareSlot 之后才落定 —— 但 prepareSlot 只把目标槽名规范化、不会改出别的名字
        //   （见 Data.slotNameFor 与 prepareSlot 的各分支），所以先用规范化后的名字解析一次。
        final String provSlot = Data.slotNameFor(this, slot);
        final String provKey = Versions.instanceKey(vkey, provSlot);
        Patches.Resolved patchRes = Patches.resolve(this, target, provKey);
        boolean legacy = needsLegacySlot(target, vkey, it, patchRes);
        // ★ 第 412 轮：远古包（build ≤ 146）的槽交接可能**拒绝**启动 ——
        //   files/ 里有无主数据、或游戏还在跑时，宁可停下说清楚，也不猜/不抢。
        String rep;
        try {
            rep = Data.prepareSlot(this, slot, legacy);
        } catch (Data.SlotRefused e) {
            fail(Trans.get(GameSlot.this, R.string.step_prepare), slotRefusedText(e), null);
            return;
        }
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
        // ★ 第 412 轮：游戏自己的崩溃转储还没初始化前，兜住 :game 启动期的未捕获异常
        //   （整端补丁的入口类在 onCreate 就炸时，游戏不会写下任何 crash_*.txt）。
        Crash.installForGame(this);
        mStatus.setText(Trans.get(GameSlot.this, R.string.game_starting_fmt, shown, Data.currentSlot(this)));

        // ★★ 第 353 轮（用户：「游戏补丁的启用状态应该绑定实例吧」）：补丁按**启动项（实例）**解析，
        //   实例键 = 版本 key + NUL + 这一局实际用的槽。槽名取 `prepareSlot` 之后的值
        //   （`slot` extra 可能为空 / 远古包会临时改道）：`currentSlot` 才是这一局真正落地的槽。
        final String launchSlot = Data.currentSlot(this);
        final String ikey = Versions.instanceKey(vkey, launchSlot);
        if (!ikey.equals(provKey)) {
            // 极端情况：prepareSlot 没认下目标槽（名字不合法 / 外部目录不可用）⇒ 用真正落地的
            // 实例键再解析一次，别把补丁算到别的实例头上。
            patchRes = Patches.resolve(this, target, ikey);
            android.util.Log.w("MDTLauncher", "patch resolve: 实例键 " + provKey + " → " + ikey);
        }
        mVkey = vkey;
        mLaunchSlot = launchSlot;

        // ★ 2026-11-12（第 359 轮）：登记"这个实例正在跑"（多开前置，见 Instances）。
        //   时机：槽/版本都定下来之后、注入与拉起游戏之前 —— 门禁从这一刻起就按它拒写。
        //   ⚠️ 退出时没有可靠回调（游戏 System.exit / SIGKILL 都没有）⇒ 读侧靠 pid 存活校验，
        //      这里只负责"报名"，不负责"销号"（启动失败那条路在 fail() 里销）。
        Instances.publish(this, vkey, launchSlot);

        // ★ 2026-11-17：宿主时钟 —— 这一局"最后跑到什么时候"由本进程自己记检查点。
        //   起点在这里（槽 / 版本都定了），终点由 {@link GameActivityWatcher} 的
        //   前后台 / 真结束事件推进；文件活在 `app_hub/instances/<started>-<pid>.time`。
        //   ⚠️ 失败只进日志：时钟不许挡住游戏启动。
        mClock = SessionClock.start(this, launchSlot, vkey);
        installGameListeners();

        // ③ 六步管线（同步执行，与探针一致；前置段 ~200-500 ms，游戏自身加载另有 4~5 s）
        Injector.Plan plan = new Injector.Plan();
        plan.apk = target;
        // v49：联机深链（可选）。原样透传，拼 URI 与校验都在 Injector.deepLinkFor（判据只一处）。
        plan.hostPort = it == null ? null : it.getStringExtra(EXTRA_HOST_PORT);
        // 第 382 轮（阶段 0.3）：native 按**版本 key** 分目录（旧布局所有版本共用一个 .so 路径，
        // 真机实测 159.7 / v146 互相覆盖）。tag 的清洗与兜底在 Natives.tagFor。
        plan.tag = vkey;
        // 补丁机制 v1（2026-11-10）：按**启动项（实例）**解析这次要带的补丁（第 353 轮起）。
        // ★ 只有真有启用记录时才去算 APK 的 sha256（80 MB 一遍）—— 没开补丁的人不付这个代价。
        // ★ 2026-11-17（第 389 轮）：这里必须把补丁引用带进 Injector.Patch ——
        //   启动记录/崩溃归因/游玩时长的补丁身份都读 `ap.ref`；漏了的话注入明明成功，
        //   `report-patch-launch.json` 里 patches 却是空的（第 388 轮的补丁统计就这么全落成本体）。
        // ★ 第 408 轮：patchRes 已在 ② 之前解析过（legacy 决策要用），这里直接用。
        for (Patches.Loaded l : patchRes.loaded) {
            Injector.Patch p = new Injector.Patch();
            p.ref = l.record.ref();
            // 整包补丁：载体是一个 APK（dex/assets/native 都在里面）
            p.apk = l.target.apkFile();
            p.label = l.record.label();
            plan.patches.add(p);
        }
        // dev：素材（资源补丁）实验的增量资产包必须在 launch 之前装填（产品版是空壳）
        ProbeLab.decorate(this, plan);
        // ★ 阶段 0.2（第 382 轮）：游戏 cache 的**身份** = APK + 这一局真注入的补丁 + 槽内模组。
        //   变了就整目录清（换版本 / 补丁启停 / 换模组后不许读旧音乐与旧 V8 jar）；
        //   有别的实例在跑则跳过（阶段 0 只有单实例，但不给未来挖坑）。
        try {
            CacheEpoch.Result ce = CacheEpoch.prepare(this, vkey, launchSlot, plan, patchRes.apkSha);
            android.util.Log.i("MDTLauncher", "cache epoch: " + ce.brief());
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "cache epoch failed", t);
        }
        try {
            // ★ 第 414 轮：拉起游戏之前先落一道"启动护栏"——整端补丁在 onCreate 就炸时，
            //   游戏自己来不及写 `launchid.dat`，回到界面要靠这道护栏才知道"这次没走完"。
            CrashAlert.markLaunched(this, launchSlot);
            Injector.Timing t = Injector.launch(this, plan);
            // ★ 第 360 轮（用户：「在包含游戏补丁的情况下游戏崩溃处理方式和 game 进程注入保持一致」）：
            //   记下这一局**真的注入了谁** —— 崩溃处理要靠它判断"崩的那一局带了哪个补丁"。
            //   判据用注入结果（`p.applied`），不是 config 里"想启用哪些"。
            java.util.List<Patches.Launched> applied = new java.util.ArrayList<Patches.Launched>();
            for (Injector.Patch ap : plan.patches) {
                if (ap == null || !ap.applied) continue;
                // ★ 第 361 轮：连载体的路径一起记 —— 崩溃归因要读它的 class_defs；
                //   整包 APK 可能多 dex，由 InjectionSources 按整包读。
                String carrier = ap.apk == null ? "" : ap.apk.getAbsolutePath();
                applied.add(new Patches.Launched(ap.ref, ap.label, carrier));
            }
            Patches.writeLaunchRecord(this, launchSlot, ikey, applied);
            reportPatches(ikey, patchRes, plan);
            // dev：探针验证台（产品版 ProbeLab 是空壳，见 app/src-release）
            ProbeLab.runIfArmed(this);
            startProbes(vkey, vmap, vinfo, launchSlot);
            // ⚠️ 这句写在 **:game 进程**自己的 Config 实例上并落盘；主进程内存里那份看不到，
            //   必须靠 MainActivity.onResume → Config.reload 才能读到
            //   （否则「继续上次」要重启启动器才更新，2026-10-01 复现）。
            Config.get().setLastVersion(shown);
            android.util.Log.i("MDTLauncher", "launch done in " + t.total() + " ms");
        } catch (Injector.LaunchError e) {
            // 这一局没起来 ⇒ 清掉启动记录（否则上一局带补丁的记录会被算到之后的崩溃上）
            Patches.writeLaunchRecord(this, launchSlot, ikey,
                    new java.util.ArrayList<Patches.Launched>());
            reportPatches(ikey, patchRes, plan);
            fail(e.step, e.getMessage(), e);
        } catch (Throwable e) {
            Patches.writeLaunchRecord(this, launchSlot, ikey,
                    new java.util.ArrayList<Patches.Launched>());
            reportPatches(ikey, patchRes, plan);
            fail(Trans.get(GameSlot.this, R.string.step_unknown),
                    Trans.get(GameSlot.this, R.string.launch_err_unknown), e);
        }
    }

    /**
     * 把"这次启动补丁没应用"的结论落到 `hub/report-patch.txt`（主进程界面读它）。
     *
     * <p>注入阶段才发现的失败在 {@link Injector.Plan#patchProblems}（`code|detail` 原文）；
     * 解析阶段的问题已经在 {@link Patches.Resolved#problems} 里 ⇒ 这里合并成一份。
     * 没有问题时 {@link Patches#writeLaunchReport} 会把旧报告删掉（不让过期结论留在界面上）。
     */
    private void reportPatches(String ikey, Patches.Resolved res, Injector.Plan plan) {
        for (String line : plan.patchProblems) {
            int bar = line.indexOf('|');
            if (bar < 0) res.problems.add(new Patches.Problem(line, ""));
            else res.problems.add(new Patches.Problem(line.substring(0, bar), line.substring(bar + 1)));
        }
        Patches.writeLaunchReport(this, ikey, res);
    }

    /**
     * 该包要不要走**改名交换**（= 它不支持 F0 的属性注入）。
     *
     * 判据来源（唯一性很关键，别在两处各判一次）：
     *   · **整端补丁定义了入口类** ⇒ 按补丁的 dex 判（它才是真正跑起来的游戏本体，第 408 轮）；
     *   · 否则有 {@link #EXTRA_LEGACY_SWAP} ⇒ 用入口页那次探测的结果（免重复读 dex）；
     *   · 都没有 ⇒ 自己探基座一次。
     *
     * ★★ **探不出来时一律按"要改名交换"处理** —— 这个方向的错**只是慢**（不并行），
     *   反方向的错是**游戏写 files/ 而 HUB 去 slot-<名> 找** ⇒ 用户的存档"看起来没了"。
     *   （后者其实也能自愈：下一次启动的 {@link Data#reclaimLegacy} 会发现 files/ 里有数据
     *   并把本体声明回 files/。但那要等到下一局，用户这一局已经吓一跳了。）
     */
    /**
     * 远古包槽交接被拒 ⇒ 启动失败弹窗里那句话（文案只在 strings 里）。
     */
    private String slotRefusedText(Data.SlotRefused e) {
        if ("legacy_busy".equals(e.code)) {
            return Trans.get(this, R.string.legacy_slot_busy);
        }
        if ("legacy_ownerless".equals(e.code)) {
            return Trans.get(this, R.string.legacy_slot_ownerless_fmt,
                    e.arg == null ? Data.currentSlot(this) : e.arg);
        }
        return Trans.get(this, R.string.legacy_slot_move_failed_fmt);
    }

    private boolean needsLegacySlot(File apk, String versionKey, Intent it, Patches.Resolved res) {
        // ① 整端补丁（注入后排在基座之前 ⇒ 它的同名类先被解析到）—— 判它自己支不支持属性
        if (res != null) {
            for (Patches.Loaded l : res.loaded) {
                File carrier = l.target == null ? null : l.target.apkFile();
                if (carrier == null || !carrier.isFile()) continue;
                try {
                    if (PatchPackage.classes(carrier).contains(Injector.GAME_ACTIVITY)) {
                        boolean prop = Compat.probe(carrier).hasProp;
                        android.util.Log.i("MDTLauncher", "slot-mode: 整端补丁 " + l.record.label()
                                + " 定义入口类 ⇒ 属性注入=" + prop);
                        return !prop;
                    }
                } catch (Throwable t) {
                    android.util.Log.w("MDTLauncher", "整端补丁探测失败，继续看基座: " + t);
                }
            }
        }
        // ② 基座
        if (it != null && it.hasExtra(EXTRA_LEGACY_SWAP)) {
            return it.getBooleanExtra(EXTRA_LEGACY_SWAP, false);
        }
        // ②' CAS 版本：入过池的版本，远古包判定在入库时就落进清单了。
        //     APK 可能已经删了，这里不能再靠 Probe 现探（探不到会被当成远古包 ⇒ 搬空槽）。
        PkgManifest cas = PkgManifest.read(PkgManifest.fileOf(this, versionKey));
        if (cas != null) {
            android.util.Log.i("MDTLauncher", "slot-mode: cas manifest legacy=" + cas.legacy);
            return cas.legacy;
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
    private void startProbes(String versionKey, Map<String, String> info, Bundle infoBundle,
                             String slot) {
        try {
            // ★ 第 371 轮（多开前置）：会话名 / 探针身份一律用**这一局实际落地的槽**
            //   （`handle` 里 `prepareSlot` 之后的 launchSlot），不再读全局 `currentSlot` ——
            //   多实例下那是单指针，会让两个实例的探针账目张冠李戴。
            String session = ProbeService.sessionName(this, slot);
            mProbes = ProbeRunner.start(this, android.os.Process.myPid(), true, session,
                    slot, versionKey, info);
            startService(ProbeService.runIntent(this, android.os.Process.myPid(), session,
                    slot, versionKey, infoBundle));
        } catch (Throwable t) {
            android.util.Log.w("MDTPlugin", "probe start failed", t);
        }
    }

    /**
     * 2026-11-17：把游戏 activity 的前后台 / 真结束事件**一次挂好**，同时喂探针与时钟。
     *
     * <p>为什么必须一处挂：{@link GameActivityWatcher#setForegroundListener} 是**单槽**，
     * 时钟若自己再挂一次，会把探针那条顶掉。所以挂一个合并回调，两边都判空。
     *
     * <p>挂的时机 = `Instances.publish` 之后、`Injector.launch` 之前（游戏 activity 还没创建）。
     */
    private void installGameListeners() {
        GameActivityWatcher.setForegroundListener(new GameActivityWatcher.Foreground() {
            @Override public void onGameForeground(boolean fg) {
                ProbeRunner r = mProbes;
                if (r != null) r.foreground(fg);
                SessionClock c = mClock;
                if (c != null) c.foreground(fg);
            }
        });
        GameActivityWatcher.setFinishedListener(new GameActivityWatcher.Finished() {
            @Override public void onGameFinished() {
                SessionClock c = mClock;
                if (c != null) c.end();
            }
        });
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
        // 这一局没起来 ⇒ 立刻销号（别让写门禁一直拦）；同时清掉主进程在 startActivity 前
        // 留下的启动占位（第 366 轮：否则那个槽要被占位挡到过期）
        Instances.withdraw(this, mVkey, mLaunchSlot);
        // ★ v45：落一条"这局没起来"的持久记录 —— 主进程判 `game-exit` 的 `launch-failed` 档用。
        //   为什么不能只看"实例没登记过"：:game 可能 publish 成功之后才失败，那时主进程
        //   已经见过它活着，仅凭观测分不出"起来过又挂了"和"根本没起来"。
        recordLaunchFailed(step);
        // 2026-11-17：时钟停表 —— 失败弹窗可能被用户晾着，不停的话 lastSeen 会一直涨，
        // 下一轮结算会把"看弹窗的时间"算成玩了多久。
        if (mClock != null) {
            mClock.end();
            mClock = null;
        }
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

    /**
     * v45：写「这一局没起来」的记录（`<hub>/report-launch-failed.txt`）。
     *
     * <p>格式刻意是纯文本三行（slot / at / step）：主进程只读 `slot` + `at` 判
     * `launch-failed`，`step` 只用于人看日志。**按槽匹配 + 时刻不早于这一局**才算命中 ——
     * 别让上一局留下的记录冒充这一局（同一份判据在主进程 `PluginEvents` 里）。
     *
     * <p>⚠️ 单文件只留最后一条：多开下两个槽同时失败，后写的会盖掉前一个 —— 那个槽会
     * 降级成 `unclean`（"拿不准就不说"）。这是刻意的取舍，不为此引入并发写协议。
     */
    private void recordLaunchFailed(String step) {
        try {
            File hub = Data.hubDir(this);
            if (hub == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("slot=").append(mLaunchSlot == null ? "" : mLaunchSlot).append('\n');
            sb.append("at=").append(System.currentTimeMillis()).append('\n');
            sb.append("step=").append(step == null ? "" : step).append('\n');
            Util.atomicWriteText(new File(hub, "report-launch-failed.txt"), sb.toString());
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "record launch failed: " + t);
        }
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
