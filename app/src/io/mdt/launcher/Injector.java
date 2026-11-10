package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 游戏加载管线（六步配方）—— M2 核心。
 *
 * ⚠️ **顺序是铁律**，每一步的"为什么"都来自探针实测（归档/MDT-Android-Dev README §二）：
 *
 *   ⓪ 预热（抛弃式 dex 加载）———— 跳过 = 用户导入的私有副本 100% 启动失败
 *   ① 注入游戏 dex 到**本进程** framework CL
 *   ② 解出 libarc.so + System.load   —— 库挂在「调用方类的 CL」上
 *   ③ 给同一份 CL 的 ArcNativesLoader.loaded 置 true，
 *      **并给 SharedLibraryLoader 记上账**（`setLoaded("arc")` / `("arc-freetype")`）
 *      ①②③ 必须同源，否则 UnsatisfiedLinkError
 *
 * ⚠️ ③ 的后半段是 2026-10-25 补的（v0.6 用户报告，Android 10 上必崩）：
 *   游戏侧 `FreeType.initFreeType()` **无条件** `load("arc-freetype")` → `System.loadLibrary(name)`；
 *   我们的 .so 在私有目录、只被 `System.load(路径)` 加载过，**不在**进程的原生库搜索路径里。
 *   老 libcore（Android 10 等）在 `findLibrary()` 为 null 时**直接抛** ⇒
 *   `Couldn't load shared library 'arc-freetype' for target: Linux, 32-bit`
 *   （那句 32-bit 是 arc 在 Android 上把 `OS.is64Bit` 强制置 false 的标签，与进程位数无关）。
 *   新 libcore（Android 16 实测）会再按名字回退、命中已加载的同名 so ⇒ 只在老系统上炸。
 *   框架自己留了钩子：`SharedLibraryLoader.setLoaded(name)`
 *   —— 注释原文 "for when application code wants to handle library loading itself"。
 *   ④ 双 AssetManager 挂资产链（后挂覆盖先挂）
 *   ⑤ startActivity(官方 AndroidLauncher)
 *
 * ⚠️ 必须跑在**游戏将要落地的那个进程**里（本工程的 GameSlot，android:process=":game"）。
 *    在别的进程注入，系统会在干净进程里找不到游戏类。
 */
public final class Injector {
    private static final String TAG = "MDTLauncher";

    /** 游戏主 Activity —— 类体只存在于游戏 APK 的 dex 里 */
    public static final String GAME_ACTIVITY = "mindustry.android.AndroidLauncher";

    /**
     * 需要的 native 库（arc 引擎）。缺失不一定是错误（不同版本可能只有一个）。
     *
     * ★ **公开给 {@link Compat} 用**：「管线认哪些库」这件事只能有**一处实现** ——
     *   兼容性探测（导入前 / 启动前）与这里的实际加载必须同源，否则会出现
     *   "探测说能跑、真加载就失败"。改这个数组就同时改了两边的判据。
     */
    public static final String[] NATIVE_LIBS = {"libarc.so", "libarc-freetype.so"};

    private Injector() {}

    /**
     * 已经挂好的**资产链**（`:game` 进程里由 {@link #mountAssets} 写；新 activity 一创建就照它再挂一遍）。
     *
     * 🔴 为什么需要它（2026-10-05 现场查出来的真问题）：`arc` 的 `AndroidFi` 用的是**游戏自己那个
     * activity**（`mindustry.android.AndroidLauncher`）的 `getAssets()`，而那个 activity 是
     * `startActivity` 之后才由系统创建的 —— 我们挂链时它**还不存在**。M2 的结论只说"要挂两份
     * （Application + 坑位 activity）"，可这台 ROM 上 `app.getAssets() != activity.getAssets()`，
     * 新 activity 完全可能拿到**第三份**未挂链的 AssetManager ⇒ 游戏读任何资产都
     * `FileNotFoundException`（症状：崩在 `Fonts.loadSystemCursors → cursors/cursor.png`，
     * 或 MindustryX 的 `mod.hjson`），而我们自己的启动报告里**两份 cookie 都是正常的** ——
     * 这正是"会往错误方向排查"的那一类。
     * ⇒ 用 {@link LauncherApp} 的 lifecycle 回调在**每个 activity 创建之前**再挂一遍。
     */
    private static volatile List<File> sChain;

    /**
     * 已经补挂过的 AssetManager（**按实例身份**去重）。
     * ★ 为什么要去重：`preCreated`/`created` 两个回调都会走到这里（API 29+），
     *   而 `addAssetPath` 是**追加**语义 —— 不记一下，同一个实例会被反复追加同一条路径，
     *   游戏进程在整个生命周期里创建多少 activity 就追加多少次。
     * ⚠️ 用 `IdentityHashMap`（比对象身份，不比 equals）且持有强引用：AssetManager 数量
     *   等于进程里 activity 的数量级，不会长到需要清理。
     */
    private static final java.util.IdentityHashMap<android.content.res.AssetManager, Boolean> sPatched =
            new java.util.IdentityHashMap<>();

    /** 给**任何一个**后建的 activity 的 AssetManager 补挂资产链（LauncherApp 在 preCreated 里调） */
    static void applyChainTo(Activity a) {
        List<File> chain = sChain;
        if (chain == null || chain.isEmpty() || a == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("资产链补挂 ").append(a.getClass().getName()).append('\n');
        try {
            android.content.res.AssetManager am = a.getAssets();
            boolean known;
            synchronized (sPatched) {
                known = sPatched.containsKey(am);
            }
            sb.append("  probe-before = ").append(probeAsset(am)).append('\n');
            if (known) {
                sb.append("  （这份实例已经补挂过，跳过）\n");
            } else {
                for (File f : chain) {
                    int c = Reflect.addAssetPath(am, f);
                    sb.append("  addAssetPath(").append(f.getName()).append(") cookie=")
                            .append(c).append('\n');
                }
                synchronized (sPatched) {
                    sPatched.put(am, Boolean.TRUE);
                }
            }
            sb.append("  probe-after  = ").append(probeAsset(am)).append('\n');
        } catch (Throwable t) {
            sb.append("  FAILED: ").append(t).append('\n');
        }
        Log.i(TAG, sb.toString());
        appendToReport(a, sb.toString());
    }

    /**
     * 直接复现游戏那次崩溃的判据：读一个**只存在于游戏 APK 里**的资产。
     * ⚠️ 别用 `list("")` 当判据 —— 它在链没挂上时**照样能看到 `cursors` 目录**（M2 踩过），
     *   会让你往"文件是不是没打进包"的方向排查半天。
     */
    static String probeAsset(android.content.res.AssetManager am) {
        if (am == null) return "no-am";
        InputStream in = null;
        try {
            in = am.open("cursors/cursor.png");
            return "ok(" + in.available() + "B)";
        } catch (Throwable t) {
            return "FAIL:" + t.getClass().getSimpleName();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把补挂结果**追加**到 `hub/report-assets.txt`（启动报告那时已经写完了，不能覆盖它） */
    private static void appendToReport(Context ctx, String text) {
        try {
            File hub = Paths.externalHub(ctx);
            if (hub == null) return;
            File f = new File(hub, "report-assets.txt");
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(text.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "append report-assets failed: " + t);
        }
    }

    /** 一次启动的输入。v1（整包）只用 apk；assetBase/deltas 留给 M4 的 CAS 装配。 */
    public static class Plan {
        /** 代码流 + native 来源（整包 APK，或"基线+裸dex"装配后的容器） */
        public File apk;
        /** 资产基线容器；null ⇒ 用 apk 自己 */
        public File assetBase;
        /** 增量包（只含 assets/ 的 zip），按顺序挂在基线之后 */
        public final List<File> assetDeltas = new ArrayList<>();
        /** 补丁（按启用顺序注入；同改一个类/素材的组合在启用时就被拒绝，这里只管执行） */
        public final List<Patch> patches = new ArrayList<>();
        /**
         * 本轮**没应用**的补丁（`code|detail` 原文，人话由 {@link PatchText} 翻）。
         * 注入阶段才发现的问题写这里；{@link GameSlot} 会把它们合进启动报告。
         */
        public final List<String> patchProblems = new ArrayList<>();
    }

    /**
     * 一个要注入的补丁。
     *
     * <p>★ dex 由 {@link Patches} 从**内部存储的只读副本**给过来（ART 只认内部 + 只读，
     * 见 `ref/48`）；assets 是可选素材包，挂到资产链**最后**（后挂覆盖先挂）。
     */
    public static class Patch {
        public File dex;
        /** 可选素材包（条目必须在 assets/ 下；null = 这个补丁没有素材） */
        public File assets;
        /** 显示名（报告与跳过日志点名用） */
        public String label = "";
        /**
         * 这个补丁**真的注入了**吗（第 345 轮独立校验 B2 之后加的）。
         * 素材只在 applied=true 时挂链 —— 否则会出现"代码没打上、素材打上了"的半截补丁，
         * 那是最难排查的形态（界面显示已启用、游戏却一半新一半旧）。
         */
        public boolean applied;
    }

    /** 带步骤名的启动失败，便于 UI 直接展示"卡在哪一步" */
    public static class LaunchError extends Exception {
        public final String step;

        LaunchError(String step, String msg, Throwable cause) {
            super(msg, cause);
            this.step = step;
        }
    }

    /** 各步耗时（ms），键是步骤名；报告与 UI 都会用 */
    public static class Timing {
        public final java.util.LinkedHashMap<String, Long> steps = new java.util.LinkedHashMap<>();
        private long mT0 = System.currentTimeMillis();
        private long mLast = mT0;

        void mark(String name) {
            long now = System.currentTimeMillis();
            steps.put(name, now - mLast);
            mLast = now;
        }

        public long total() {
            return System.currentTimeMillis() - mT0;
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Long> e : steps.entrySet()) {
                sb.append(String.format(java.util.Locale.US, "%-12s %5d ms%n", e.getKey(), e.getValue()));
            }
            sb.append(String.format(java.util.Locale.US, "%-12s %5d ms", "合计", total()));
            return sb.toString();
        }
    }

    /**
     * 跑完六步并拉起游戏。**必须在 UI 线程调用**（结尾要 startActivity）。
     * 失败时抛 LaunchError，调用方按 step 展示；已做的注入不回滚（进程随后会被抛弃）。
     */
    public static Timing launch(Activity activity, Plan plan) throws LaunchError {
        if (plan == null || plan.apk == null || !plan.apk.exists()) {
            throw new LaunchError(Trans.get(activity, R.string.step_prepare),
                    Trans.get(activity, R.string.launch_err_bad_package), null);
        }
        Context app = activity.getApplicationContext();
        ClassLoader baseCl = activity.getClassLoader();
        Timing t = new Timing();
        StringBuilder log = new StringBuilder();
        log.append("=== 启动管线 ").append(new java.util.Date()).append(" ===\n");
        log.append("目标   = ").append(plan.apk.getAbsolutePath()).append('\n');
        log.append("大小   = ").append(plan.apk.length()).append(" B\n");
        log.append("进程 pid = ").append(android.os.Process.myPid()).append('\n');

        // 🔴 Android 14+：动态加载的 dex 载体必须**只读**，否则 DexClassLoader 直接
        //    `SecurityException: Writable dex file ... is not allowed`（预热会连失败三次、启动不了）。
        //    导入时 Importer 已经置过只读；但"中转站放回来 / 手工拷 / 旧版本导入"这些路径可能留下
        //    可写文件 —— 真机实测（2026-10-25）：`official-159.apk` 是 0600，一启动就三次 SecurityException。
        //    这里兜一道：只动我们自己的私有副本（`/data/app` 下的本来就不可写，canWrite=false）。
        if (plan.apk.canWrite()) ensureDexReadOnly(plan.apk, log);

        // ⓪ 预热
        if (!prewarm(app, plan.apk, log)) {
            t.mark("prewarm");
            report(app, log);
            throw new LaunchError(Trans.get(activity, R.string.step_prewarm),
                    Trans.get(activity, R.string.launch_err_no_activity_fmt, GAME_ACTIVITY), null);
        }
        t.mark("prewarm");

        // ① dex 注入
        // ★ 补丁要插在**宿主 dex 之后、游戏 dex 之前**（2026-11-10，补丁机制 v1）：
        //   前插到 0 会让补丁有机会顶掉宿主自己的类（`io.mdt.launcher.*`）—— 那不是补丁该干的事；
        //   插在游戏 dex 之后则永远赢不了（同名类先到的赢）。所以要**先量出宿主那一段的长度**。
        int hostDex = 0;
        try {
            hostDex = Reflect.dexElementCount(baseCl);
        } catch (Throwable e) {
            // ★ 修（第 345 轮独立校验 B3）：量不到宿主段就**不注入补丁** —— 回退到 0 会把补丁
            //   前插到宿主之前（补丁里若定义 io.mdt.launcher.* 就能顶掉宿主自己的类）。
            hostDex = -1;
            log.append("WARN dexElementCount failed (").append(e)
                    .append(") — patches will be SKIPPED (index unknown)\n");
        }
        log.append("宿主 dex elements = ").append(hostDex).append('\n');
        try {
            injectDex(app, baseCl, plan.apk, log);
            injectPatches(app, baseCl, plan, hostDex, log);
        } catch (Throwable e) {
            t.mark("dexInject");
            logThrowable(log, e);
            report(app, log);
            throw new LaunchError(Trans.get(activity, R.string.step_dex),
                    Trans.get(activity, R.string.launch_err_dex), e);
        }
        t.mark("dexInject");

        // ② native
        try {
            loadNatives(app, plan.apk, log);
        } catch (Throwable e) {
            t.mark("nativeLoad");
            logThrowable(log, e);
            report(app, log);
            throw new LaunchError(Trans.get(activity, R.string.step_native),
                    Trans.get(activity, R.string.launch_err_native), e);
        }
        t.mark("nativeLoad");

        // ③ 置 loaded
        try {
            flagNativesLoaded(baseCl, log);
        } catch (Throwable e) {
            t.mark("nativeFlag");
            logThrowable(log, e);
            report(app, log);
            throw new LaunchError(Trans.get(activity, R.string.step_native_flag),
                    Trans.get(activity, R.string.launch_err_native_flag), e);
        }
        t.mark("nativeFlag");

        // ④ 资产
        try {
            mountAssets(app, activity, plan, log);
        } catch (Throwable e) {
            t.mark("assetMount");
            logThrowable(log, e);
            report(app, log);
            throw new LaunchError(Trans.get(activity, R.string.step_assets),
                    Trans.get(activity, R.string.launch_err_assets), e);
        }
        t.mark("assetMount");

        // ⑤ startActivity
        try {
            startGame(activity, baseCl, log);
        } catch (Throwable e) {
            t.mark("startActivity");
            logThrowable(log, e);
            report(app, log);
            throw new LaunchError(Trans.get(activity, R.string.step_start),
                    Trans.get(activity, R.string.launch_err_start), e);
        }
        t.mark("startActivity");

        log.append("\n--- 前置段耗时 ---\n").append(t.format()).append('\n');
        report(app, log);
        Log.i(TAG, "launch ok in " + t.total() + " ms\n" + t.format());
        return t;
    }

    // ── ⓪ 预热 ────────────────────────────────────────────────────────────

    /**
     * dex 载体必须是**只读**的（Android 14+ 的硬要求，否则 `DexClassLoader` 抛
     * `SecurityException: Writable dex file ... is not allowed`）。
     *
     * ★ 为什么在建 CL 之前还要再兜一次：导入路径（{@link Importer}）确实置了只读，但
     *   "中转站放回来 / 手工拷进来 / 更早版本导入的包"都可能留下可写文件 ——
     *   真机实测（2026-10-25）：`official-159.apk` 是 0600 ⇒ 预热连失败三次、整个版本启动不了。
     *   这里只动**我们自己的私有副本**；`/data/app` 下的 APK 不可写（`canWrite()` 为 false）。
     *
     * @return 真的动过（= 之前可写）才返回 true
     */
    static boolean ensureDexReadOnly(File apk, StringBuilder log) {
        if (apk == null || !apk.canWrite()) return false;
        boolean ro = apk.setReadOnly();
        log.append("APK was writable -> setReadOnly: ").append(ro).append('\n');
        return true;
    }

    /**
     * 抛弃式 dex 加载：把一个**未安装** APK 在冷状态下的首次加载失败先消耗掉。
     *
     * 机理未定案（探针已排除压缩方式、optimizedDirectory 取值两个猜想），
     * 但规律是确定的：第一次产出的 element 是"死"的（句柄有、类表空），紧接着
     * 再建一个 DexClassLoader 立刻就能解析。已安装的 APK 因安装期已 dexopt 不受影响。
     */
    private static boolean prewarm(Context ctx, File apk, StringBuilder log) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                DexClassLoader d = new DexClassLoader(apk.getAbsolutePath(),
                        ctx.getCodeCacheDir().getAbsolutePath(), null,
                        Injector.class.getClassLoader().getParent());
                d.loadClass(GAME_ACTIVITY);
                log.append("预热成功（第 ").append(attempt).append(" 次）\n");
                return true;
            } catch (Throwable e) {
                log.append("预热第 ").append(attempt).append(" 次失败: ")
                        .append(e.getClass().getSimpleName()).append('\n');
            }
        }
        return false;
    }

    // ── ① dex 注入 ────────────────────────────────────────────────────────

    /**
     * ★ optimizedDirectory 必须用 getCodeCacheDir() **本身**，不能用它的子目录 ——
     *   探针实测：用子目录时私有副本的 dex 首次拿不到类，用本身正常（受控变量对照）。
     */
    private static void injectDex(Context ctx, ClassLoader baseCl, File apk, StringBuilder log)
            throws Exception {
        File opt = ctx.getCodeCacheDir();
        if (!opt.exists()) opt.mkdirs();
        DexClassLoader extra = new DexClassLoader(apk.getAbsolutePath(),
                opt.getAbsolutePath(), null, baseCl.getParent());
        int total = Reflect.appendDexElements(baseCl, extra);
        log.append("dexElements 合并后 = ").append(total).append('\n');
        // 验证挪到 injectPatches 的末尾：补丁要在这句之前插好，否则 if (补丁替换了入口类)
        // 这里会先把原版加载掉 —— 之后再 prepend 就换不掉了（S0 的「已加载过就换不掉」）。
    }

    /**
     * 补丁注入（补丁机制 v1）：按启用顺序插到**宿主 dex 之后、游戏 dex 之前**。
     *
     * <p>三条纪律：
     * <ol>
     *   <li>每条补丁一个 dex；注入前判文件在不在（不在就**整条跳过**，不阻塞启动）；
     *   <li>单条注入失败 ⇒ 只跳过这一条（"跳过整个补丁"而不是"跳过某个类"，见设计草案 §11 第 5 问），
     *       原因记进 {@link Plan#patchProblems}，由启动报告带给界面；
     *   <li>全部插完之后才做「base CL 能解析游戏入口类」的验证 —— 这样补丁替换入口类也来得及。
     * </ol>
     *
     * <p>🔴 **插入下标是"当前条数"，不是"合并后的总条数"**（第 345 轮独立校验 B1）：下标由
     * {@link #patchInsertIndex} 一处算，第 n 条 = `hostDex + 已插入元素数`；写成总条数会把第 2 条
     * 起追加到游戏 dex 之后 ⇒ 同名类永远被原版覆盖、**静默无效**。
     *
     * <p>🔴 **成功判据 = 元素确实变多了**（B2）：`DexPathList.makeDexElements` 会把加载失败塞进
     * suppressed 列表而**不抛异常**；`after == before` 必须算失败，否则"注入完成 N/N"是假的。
     */
    private static void injectPatches(Context ctx, ClassLoader baseCl, Plan plan, int hostDex,
                                      StringBuilder log) throws Exception {
        File opt = ctx.getCodeCacheDir();
        if (!opt.exists()) opt.mkdirs();
        if (plan.patches.isEmpty()) {
            verifyGameClassLoadable(baseCl, log);
            return;
        }
        log.append("补丁数 = ").append(plan.patches.size()).append('\n');
        if (hostDex < 0) {
            // 量不到宿主段 ⇒ 不知道插哪；宁可不注入（原版照常启动）也不把补丁顶到宿主前面
            for (int i = 0; i < plan.patches.size(); i++) {
                String label = labelOf(plan.patches.get(i), i);
                log.append("补丁[").append(i + 1).append("] ").append(label)
                        .append(" 跳过：宿主 dex 条数未知\n");
                plan.patchProblems.add(PatchException.C_IO + "|" + label);
            }
            verifyGameClassLoadable(baseCl, log);
            return;
        }
        int inserted = 0;
        int okCount = 0;
        for (int i = 0; i < plan.patches.size(); i++) {
            Patch p = plan.patches.get(i);
            String label = labelOf(p, i);
            if (p.dex == null || !p.dex.isFile() || p.dex.length() <= 0) {
                log.append("补丁[").append(i + 1).append("] ").append(label)
                        .append(" 跳过：dex 不存在\n");
                plan.patchProblems.add(PatchException.C_MISSING + "|" + label);
                continue;
            }
            int at = patchInsertIndex(hostDex, inserted);
            try {
                List<String> notes = new ArrayList<>();
                int before = Reflect.dexElementCount(baseCl);
                long t0 = System.nanoTime();
                int after = Reflect.injectDex(baseCl, p.dex, opt, at, notes);
                long ms = (System.nanoTime() - t0) / 1000000L;
                log.append("补丁[").append(i + 1).append("] ").append(label)
                        .append(" 注入 @").append(at).append(" elements ")
                        .append(before).append("→").append(after)
                        .append(" (").append(ms).append(" ms)");
                for (String n : notes) log.append(" | ").append(n);
                log.append('\n');
                if (after <= before) {
                    // 没多出 element = ART 实际没认这份 dex（suppressed 里是原因）
                    log.append("补丁[").append(i + 1).append("] ").append(label)
                            .append(" 判失败：注入 0 个 element\n");
                    plan.patchProblems.add(PatchException.C_BAD_DEX + "|" + label + " (0 elements)");
                    continue;
                }
                inserted += (after - before);
                p.applied = true;
                okCount++;
            } catch (Throwable e) {
                log.append("补丁[").append(i + 1).append("] ").append(label)
                        .append(" 跳过：注入失败 ").append(e).append('\n');
                plan.patchProblems.add(PatchException.C_BAD_DEX + "|" + label);
            }
        }
        log.append("补丁注入完成 ").append(okCount).append('/').append(plan.patches.size()).append('\n');
        verifyGameClassLoadable(baseCl, log);
    }

    private static String labelOf(Patch p, int index) {
        return (p == null || p.label == null || p.label.isEmpty())
                ? ("patch#" + (index + 1)) : p.label;
    }

    /**
     * 第 n 条补丁的**插入下标**（纯函数，让自检能钉住"下标算成总条数"这类错）。
     *
     * <p>语义：`hostDex` = 注入游戏 dex **之前**量到的宿主段长度；`alreadyInserted` = 已经插进去的
     * 元素数。返回值 = 宿主段之后、游戏段之前。
     *
     * @return hostDex &lt; 0（量不到）时返回 -1（调用方据此跳过全部补丁）
     */
    static int patchInsertIndex(int hostDex, int alreadyInserted) {
        if (hostDex < 0) return -1;
        return hostDex + Math.max(0, alreadyInserted);
    }

    /** 注入补丁之后再验一次：base CL 必须能解析游戏入口类（否则后面 startActivity 必崩）。 */
    private static void verifyGameClassLoadable(ClassLoader baseCl, StringBuilder log) throws Exception {
        Class.forName(GAME_ACTIVITY, false, baseCl);
        log.append("base CL 已能看到 ").append(GAME_ACTIVITY).append('\n');
    }

    // ── ② native 库 ───────────────────────────────────────────────────────

    /**
     * 从 APK 里解出 arc 的 .so 到私有目录再 System.load。
     * ★ System.load 把库挂在「调用方类的类加载器」上 —— 本类定义在 base CL 里，
     *   所以库归属于 base CL，与 ① 注入的游戏类同源（这是 ①②③ 必须连做且同源的原因）。
     */
    private static void loadNatives(Context ctx, File apk, StringBuilder log) throws Exception {
        ZipFile zf = new ZipFile(apk);
        int loaded = 0;
        try {
            // ★ ABI 不能写死 Build.SUPPORTED_ABIS[0]：只提供 armeabi-v7a 的包在 32 位进程里
            //   照样能跑，写死 [0] 会找不到库。按**进程**优先级找第一个交集，且**与 Compat
            //   的判据同一实现**（Compat.pickAbi）——否则会出现"探测说能跑、加载就失败"。
            String abi = Compat.pickAbi(zf);
            if (abi == null) {
                log.append("APK 里没有任何匹配本机 ABI 的 native 目录\n");
                throw new IllegalStateException(Trans.get(ctx, R.string.launch_err_no_abi_fmt,
                        Compat.join(Compat.deviceAbis())));
            }
            log.append("选用 ABI = ").append(abi).append('\n');
            File dir = new File(new File(Paths.privateDir(ctx), "natives"), abi);
            if (!dir.exists()) dir.mkdirs();

            for (String name : NATIVE_LIBS) {
                ZipEntry e = zf.getEntry("lib/" + abi + "/" + name);
                if (e == null) {
                    log.append("APK 里没有 lib/").append(abi).append('/').append(name).append('\n');
                    continue;
                }
                File out = new File(dir, name);
                if (out.exists()) {
                    out.setWritable(true);   // 上次置过只读，得先放开
                    out.delete();
                }
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                    in.close();
                }
                out.setReadOnly();
                System.load(out.getAbsolutePath());
                loaded++;
                log.append("System.load(").append(name).append(") OK  ")
                        .append(out.length()).append(" B\n");
            }
        } finally {
            zf.close();
        }
        // 🔴 计数必须是**本次 launch 的局部量**。原来用 static Set 累加、跨 launch 不清空：
        //   :game 进程在同一次生命周期里可能被拉起两次（GameSlot 失败后用户返回列表再点，
        //   进程仍在）⇒ 第二次启动一个 native 缺失的包时这个判断**不会抛**，
        //   于是一路走到 startActivity，最后崩在游戏内部（等价的库从未加载），
        //   用户完全无从判断原因 —— 这是"静默出错"的最坏形态。
        if (loaded == 0) throw new IllegalStateException(Trans.get(ctx, R.string.launch_err_no_native_loaded));
    }

    // ── ③ 置 loaded 标志 ──────────────────────────────────────────────────

    /**
     * arc.backend.android.AndroidApplication 的 **static 块**里就调 ArcNativesLoader.load()，
     * 它会去 nativeLibraryDir 找 libarc.so（我们的在私有目录，找不到）。
     * 所以必须在任何代码触碰 AndroidApplication 之前把标志置上。
     *
     * ★ 必须是「真正会定义游戏类的那个 CL」—— 类按 CL 隔离，给别的副本置 true 没用。
     *
     * ★★ 还要给 `SharedLibraryLoader` 记上账：`ArcNativesLoader.loaded` 只挡得住 `load("arc")`，
     *    挡不住 `FreeType.initFreeType()` 里那句 `load("arc-freetype")`（它不看那个标志）。
     *    不记账的后果见类注释 ③：老系统上游戏起不来。
     */
    private static void flagNativesLoaded(ClassLoader baseCl, StringBuilder log) throws Exception {
        Class<?> anl = Class.forName("arc.util.ArcNativesLoader", true, baseCl);
        if (anl.getClassLoader() != baseCl) {
            throw new IllegalStateException("ArcNativesLoader 来自非预期 CL: " + anl.getClassLoader());
        }
        java.lang.reflect.Field loaded = anl.getField("loaded");
        loaded.setBoolean(null, true);
        try {
            anl.getField("disableNativesLoading").setBoolean(null, true);
        } catch (Throwable ignored) {
            // 该字段在新版 arc 里可能不存在，不是错误
        }
        log.append("ArcNativesLoader.loaded = ").append(loaded.getBoolean(null)).append('\n');

        // 记账失败**不拦启动**：新系统上不记也能跑（会按名字回退），老系统上不记会崩在游戏里。
        // 这里只把它写进启动报告 —— 不把一个"可能没用"的钩子升级成启动阻塞项。
        try {
            int marked = markArcLibrariesLoaded(baseCl, NATIVE_LIBS, log);
            if (marked != NATIVE_LIBS.length) {
                log.append("WARN SharedLibraryLoader marked ").append(marked).append('/')
                        .append(NATIVE_LIBS.length).append('\n');
            }
        } catch (Throwable t) {
            log.append("WARN SharedLibraryLoader setLoaded failed: ").append(t).append('\n');
        }
    }

    /**
     * 把 arc 的 `SharedLibraryLoader.loadedLibraries` 记上账 —— **逻辑名**（`arc` / `arc-freetype`），
     * 不是文件名。它和 {@link #NATIVE_LIBS} 必须同源（{@link #arcLibName(String)} 是唯一推导处）。
     *
     * @return 记账成功的个数（`isLoaded` 回读为 true 才算）
     */
    static int markArcLibrariesLoaded(ClassLoader cl, String[] libs, StringBuilder log) throws Exception {
        Class<?> sll = Class.forName("arc.util.SharedLibraryLoader", true, cl);
        if (sll.getClassLoader() != cl) {
            throw new IllegalStateException("SharedLibraryLoader from unexpected CL: " + sll.getClassLoader());
        }
        return markArcLibrariesLoaded(sll, libs, log);
    }

    /**
     * 可测的那一半：类由调用方给（自检喂替身类，钉住"调的是 setLoaded 且用逻辑名"这条契约）。
     * ⚠️ 真实 `arc.util.SharedLibraryLoader` 那条路只能在真机 / 模拟器启动时验 —— 启动报告里会打
     * `SharedLibraryLoader.isLoaded(arc-freetype) = true`。
     */
    static int markArcLibrariesLoaded(Class<?> sll, String[] libs, StringBuilder log) throws Exception {
        java.lang.reflect.Method setLoaded = sll.getMethod("setLoaded", String.class);
        java.lang.reflect.Method isLoaded = sll.getMethod("isLoaded", String.class);
        int marked = 0;
        for (String file : libs) {
            String name = arcLibName(file);
            setLoaded.invoke(null, name);
            boolean now = Boolean.TRUE.equals(isLoaded.invoke(null, name));
            if (now) marked++;
            log.append("SharedLibraryLoader.isLoaded(").append(name).append(") = ").append(now).append('\n');
        }
        return marked;
    }

    /** `libarc-freetype.so` → `arc-freetype`（SharedLibraryLoader 用的逻辑名，**唯一推导处**） */
    static String arcLibName(String fileName) {
        String n = fileName;
        if (n.startsWith("lib")) n = n.substring(3);
        if (n.endsWith(".so")) n = n.substring(0, n.length() - 3);
        return n;
    }

    // ── ④ 资产挂链 ────────────────────────────────────────────────────────

    /**
     * ★ 必须以"链"的形态挂在**两份** AssetManager 上，后挂的覆盖先挂的。
     *
     * 挂两份的原因（探针最容易误判的坑）：子进程里 activity.getAssets() 与
     * applicationContext.getAssets() 是**两个不同实例**；游戏 arc 的 AndroidFi 用的是
     * activity 那份。只挂 Application 时游戏崩在 Fonts.loadSystemCursors →
     * cursors/cursor.png，而 list("") 里明明看得到 cursors 目录 —— 会往错误方向排查。
     */
    private static void mountAssets(Context app, Activity activity, Plan plan, StringBuilder log)
            throws Exception {
        File base = (plan.assetBase != null && plan.assetBase.exists()) ? plan.assetBase : plan.apk;
        List<File> chain = new ArrayList<>();
        chain.add(base);
        for (File d : plan.assetDeltas) {
            if (d != null && d.exists()) chain.add(d);
        }
        // 补丁的素材包接在**最后**：addAssetPath 的语义是后挂覆盖先挂（真机实测，草案 §5）。
        // 两个补丁改同一个素材在启用时就被拒绝了 ⇒ 这里的顺序不需要额外裁决。
        // ★ 只挂**真正注入成功**的补丁（applied）：注入失败还挂素材 = 半截补丁（B2）。
        for (Patch p : plan.patches) {
            if (p.applied && p.assets != null && p.assets.isFile()) chain.add(p.assets);
        }

        android.content.res.AssetManager amApp = app.getAssets();
        android.content.res.AssetManager amAct = activity.getAssets();
        boolean same = amApp == amAct;
        sChain = chain;                       // ★ 留给后建的 activity 补挂（见 sChain 的注释）
        log.append("资产链 = ");
        for (File f : chain) log.append(f.getName()).append(' ');
        log.append("\n两份 AssetManager 同源 = ").append(same).append('\n');

        for (File f : chain) {
            int c1 = Reflect.addAssetPath(amApp, f);
            log.append("  addAssetPath(app)  ").append(f.getName())
                    .append(" cookie=").append(c1).append('\n');
            if (!same) {
                int c2 = Reflect.addAssetPath(amAct, f);
                log.append("  addAssetPath(act)  ").append(f.getName())
                        .append(" cookie=").append(c2).append('\n');
            }
            if (c1 == 0) throw new IllegalStateException("addAssetPath 未接受 " + f.getName());
        }
        // ★ 这两份挂完之后**各自探一次**：探针能直接回答"这个实例到底读不读得到游戏资产"，
        //   而不是等游戏崩了再猜（M2 那条 `list("")` 误判就是缺这么一个探针）。
        log.append("  probe(app) = ").append(probeAsset(amApp)).append('\n');
        if (!same) log.append("  probe(act) = ").append(probeAsset(amAct)).append('\n');
    }

    // ── ⑤ 启动游戏 ────────────────────────────────────────────────────────

    private static void startGame(Activity activity, ClassLoader baseCl, StringBuilder log)
            throws Exception {
        Class<?> cls = Class.forName(GAME_ACTIVITY, false, baseCl);
        log.append("已从 base CL 解析到 ").append(cls.getName()).append('\n');
        Intent i = new Intent(activity, cls);
        activity.startActivity(i);
        activity.finish();   // 坑位退场，游戏接管
        log.append("startActivity 已返回，坑位 finish\n");
    }

    // ── 报告通道 ──────────────────────────────────────────────────────────

    /**
     * 把异常**原样**记进启动报告（★ 它是"原始诊断"那一层）。
     *
     * <p>🔴 2026-10-15：这些异常原来是 `String.valueOf(e)` 直接进 {@link LaunchError#getMessage()}
     * ⇒ 用户看到的启动失败弹窗里是 `java.lang.NoSuchMethodError: …`。现在第一层只说人话
     * （`launch_err_dex` 那几条），**原文挪到这里**——排查要的东西一个字不少。
     */
    private static void logThrowable(StringBuilder log, Throwable e) {
        if (e == null) return;
        log.append("!! ").append(e).append('\n');
        log.append(Log.getStackTraceString(e)).append('\n');
    }

    /**
     * 启动报告写 hub/（**外部**目录，debuggable=false 下 adb 可直接读）+ logcat。
     * 不写游戏数据根，避免被游戏当自己的数据清掉。
     */
    private static void report(Context ctx, StringBuilder log) {
        String s = log.toString();
        Log.i(TAG, s);
        File hub = Paths.externalHub(ctx);
        if (hub != null) {
            try {
                Util.atomicWriteText(new File(hub, "report-launch.txt"), s);
            } catch (Exception e) {
                Log.w(TAG, "write launch report failed: " + e);
            }
        }
    }
}
