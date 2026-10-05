package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
 * ⚠️ **顺序是铁律**，每一步的"为什么"都来自探针实测（MDT-Android-Dev README §二）：
 *
 *   ⓪ 预热（抛弃式 dex 加载）———— 跳过 = 用户导入的私有副本 100% 启动失败
 *   ① 注入游戏 dex 到**本进程** framework CL
 *   ② 解出 libarc.so + System.load   —— 库挂在「调用方类的 CL」上
 *   ③ 给同一份 CL 的 ArcNativesLoader.loaded 置 true
 *      ①②③ 必须同源，否则 UnsatisfiedLinkError
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
            throw new LaunchError(activity.getString(R.string.step_prepare),
                    activity.getString(R.string.launch_err_bad_package), null);
        }
        Context app = activity.getApplicationContext();
        ClassLoader baseCl = activity.getClassLoader();
        Timing t = new Timing();
        StringBuilder log = new StringBuilder();
        log.append("=== 启动管线 ").append(new java.util.Date()).append(" ===\n");
        log.append("目标   = ").append(plan.apk.getAbsolutePath()).append('\n');
        log.append("大小   = ").append(plan.apk.length()).append(" B\n");
        log.append("进程 pid = ").append(android.os.Process.myPid()).append('\n');

        // ⓪ 预热
        if (!prewarm(app, plan.apk, log)) {
            t.mark("prewarm");
            report(app, log);
            throw new LaunchError(activity.getString(R.string.step_prewarm),
                    activity.getString(R.string.launch_err_no_activity_fmt, GAME_ACTIVITY), null);
        }
        t.mark("prewarm");

        // ① dex 注入
        try {
            injectDex(app, baseCl, plan.apk, log);
        } catch (Throwable e) {
            t.mark("dexInject");
            report(app, log);
            throw new LaunchError(activity.getString(R.string.step_dex),
                    String.valueOf(e), e);
        }
        t.mark("dexInject");

        // ② native
        try {
            loadNatives(app, plan.apk, log);
        } catch (Throwable e) {
            t.mark("nativeLoad");
            report(app, log);
            throw new LaunchError(activity.getString(R.string.step_native),
                    String.valueOf(e), e);
        }
        t.mark("nativeLoad");

        // ③ 置 loaded
        try {
            flagNativesLoaded(baseCl, log);
        } catch (Throwable e) {
            t.mark("nativeFlag");
            report(app, log);
            throw new LaunchError(activity.getString(R.string.step_native_flag),
                    String.valueOf(e), e);
        }
        t.mark("nativeFlag");

        // ④ 资产
        try {
            mountAssets(app, activity, plan, log);
        } catch (Throwable e) {
            t.mark("assetMount");
            report(app, log);
            throw new LaunchError(activity.getString(R.string.step_assets),
                    String.valueOf(e), e);
        }
        t.mark("assetMount");

        // ⑤ startActivity
        try {
            startGame(activity, baseCl, log);
        } catch (Throwable e) {
            t.mark("startActivity");
            report(app, log);
            throw new LaunchError(activity.getString(R.string.step_start),
                    String.valueOf(e), e);
        }
        t.mark("startActivity");

        log.append("\n--- 前置段耗时 ---\n").append(t.format()).append('\n');
        report(app, log);
        Log.i(TAG, "launch ok in " + t.total() + " ms\n" + t.format());
        return t;
    }

    // ── ⓪ 预热 ────────────────────────────────────────────────────────────

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
        // 立刻验证：注入的目的是让 base CL 能解析游戏类
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
            // ★ ABI 不能写死 Build.SUPPORTED_ABIS[0]：只提供 armeabi-v7a 的包在 arm64 设备上
            //   照样能跑，写死 [0] 会找不到库。按设备优先级找第一个交集，且**与 Compat
            //   的判据同一实现**（Compat.pickAbi）——否则会出现"探测说能跑、加载就失败"。
            String abi = Compat.pickAbi(zf);
            if (abi == null) {
                log.append("APK 里没有任何匹配本机 ABI 的 native 目录\n");
                throw new IllegalStateException(ctx.getString(R.string.launch_err_no_abi_fmt,
                        joinAbis()));
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
        if (loaded == 0) throw new IllegalStateException(ctx.getString(R.string.launch_err_no_native_loaded));
    }

    /** 设备 ABI 列表，仅供错误文案（`[arm64-v8a, armeabi-v7a]`） */
    private static String joinAbis() {
        StringBuilder sb = new StringBuilder();
        for (String a : Build.SUPPORTED_ABIS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a);
        }
        return sb.toString();
    }

    // ── ③ 置 loaded 标志 ──────────────────────────────────────────────────

    /**
     * arc.backend.android.AndroidApplication 的 **static 块**里就调 ArcNativesLoader.load()，
     * 它会去 nativeLibraryDir 找 libarc.so（我们的在私有目录，找不到）。
     * 所以必须在任何代码触碰 AndroidApplication 之前把标志置上。
     *
     * ★ 必须是「真正会定义游戏类的那个 CL」—— 类按 CL 隔离，给别的副本置 true 没用。
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
