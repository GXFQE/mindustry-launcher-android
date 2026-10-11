package io.mdt.launcher;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

import java.io.File;

import dalvik.system.DexClassLoader;

/**
 * 插件加载器（**产品侧**：只加载、只判据，不带任何 UI）。
 *
 * <p><b>要回答的问题</b>：壳能不能在 **product（非 debuggable）** 构建里，
 * 加载一个"我们自己存储里的外部 APK"的**类与资源**。
 *
 * <p>四道门，顺序不能换（每一步都在"更贵的那一步"之前先便宜地失败）：
 * <ol>
 *   <li><b>置只读</b> —— ART 拒绝加载可写的 dex。⚠️ **但不能拿 chmod 当门**：外部存储（FUSE）上
 *       {@code setReadOnly()} 恒返回 false（实测），而**内部存储**上才生效 ⇒ 判据是"ART 认不认"，
 *       所以这里只**记录**、不拒绝；真正的裁决在下面的 {@link DexClassLoader}。</li>
 *   <li><b>预热预检</b> —— 抛一次性 {@link DexClassLoader} 把入口类解出来：缺类/坏包在这里就该被拒；</li>
 *   <li><b>接口版本</b> —— 入口报的 api 不是 {@link #API} ⇒ 拒（否则后面每个反射调用都是掷骰子）；</li>
 *   <li><b>挂资源</b> —— 优先**新建**一个 {@code AssetManager}（不污染宿主），失败才退回宿主那份。</li>
 * </ol>
 *
 * <p>★ 三条实测硬约束（详见 REF §87.13 / `ref/29`）：
 * 插件 APK **必须住内部存储**（外部一律 `Writable dex file is not allowed`）·
 * 同一个 dex **只能注册给一个 ClassLoader**（注入宿主 CL 要用
 * {@link Reflect#injectDex} 那种"只注册一次"的做法）· 置只读的副本**不能再被覆盖**（改名或用唯一名）。
 *
 * <p>★ 演示页与 S0 实验整块住 `app/src-dev`（{@code PluginProbeDev}）—— 产品版这里一行 UI 都不带。
 */
public final class PluginHost {

    /** 壳认的插件接口版本。插件报别的数 ⇒ 拒绝加载。 */
    public static final int API = 1;

    /** 插件入口类（**只在字符串里出现** —— 壳的源码不编译期依赖插件）。 */
    public static final String ENTRY = "io.mdt.plugtest.PlugPage";

    private PluginHost() {}

    /** 加载成功后的句柄。 */
    public static final class Loaded {
        public final DexClassLoader cl;
        public final Context ctx;
        public final Class<?> entry;
        public final int api;
        public final boolean readOnlySet;
        public final boolean stillWritable;
        public final String assetFrom;
        public final int cookie;

        Loaded(DexClassLoader cl, Context ctx, Class<?> entry, int api,
               boolean readOnlySet, boolean stillWritable, String assetFrom, int cookie) {
            this.cl = cl; this.ctx = ctx; this.entry = entry; this.api = api;
            this.readOnlySet = readOnlySet; this.stillWritable = stillWritable;
            this.assetFrom = assetFrom; this.cookie = cookie;
        }
    }

    /** 拒绝加载。{@code reason} 是"能说给用户听"的那一层（映射见 {@link #reasonRes}），{@code detail} 只进报告/日志。 */
    public static final class PluginException extends Exception {
        public final String reason;
        public final String detail;
        /** 给"接口版本不匹配"这类句子用的两个数（其余原因为 0）。 */
        public final int num1;
        public final int num2;

        PluginException(String reason, String detail) {
            this(reason, detail, 0, 0);
        }

        PluginException(String reason, String detail, int num1, int num2) {
            super(reason + " | " + detail);
            this.reason = reason;
            this.detail = detail;
            this.num1 = num1;
            this.num2 = num2;
        }
    }

    // ---- 加载 -----------------------------------------------------------------

    public static Loaded load(Context host, File apk) throws PluginException {
        if (apk == null || !apk.isFile()) {
            throw new PluginException("plugin_err_missing", String.valueOf(apk));
        }

        // (1) 尝试置只读（只记录，不当门 —— 见类注释）
        boolean wasWritable = apk.canWrite();
        boolean roSet = false;
        if (wasWritable) roSet = apk.setReadOnly();
        boolean stillWritable = apk.canWrite();

        // (2) 预热预检
        DexClassLoader cl;
        try {
            cl = new DexClassLoader(apk.getAbsolutePath(),
                    host.getCodeCacheDir().getAbsolutePath(), null, host.getClassLoader());
        } catch (Throwable t) {
            throw new PluginException("plugin_err_dex", brief(t));
        }
        Class<?> entry;
        try {
            entry = cl.loadClass(ENTRY);
        } catch (Throwable t) {
            // ★ 别吞原因：第一次就是只打了类名，导致"为什么解不出入口类"看不出根因
            throw new PluginException("plugin_err_noclass", ENTRY + " | " + brief(t));
        }

        // (3) 接口版本
        int api;
        try {
            api = ((Integer) entry.getMethod("api").invoke(null)).intValue();
        } catch (Throwable t) {
            throw new PluginException("plugin_err_entry", brief(t));
        }
        if (api != API) throw new PluginException("plugin_err_api", api + " != " + API, api, API);

        // (4) 挂资源
        AssetManager am;
        String from = "new";
        try {
            am = AssetManager.class.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            am = host.getAssets();
            from = "host(" + brief(t) + ")";
        }
        int cookie;
        try {
            cookie = Reflect.addAssetPath(am, apk);
        } catch (Throwable t) {
            throw new PluginException("plugin_err_asset", brief(t));
        }
        if (cookie == 0) throw new PluginException("plugin_err_asset", "addAssetPath=0");

        Resources res = new Resources(am, host.getResources().getDisplayMetrics(),
                host.getResources().getConfiguration());
        return new Loaded(cl, new PContext(host, am, res, cl), entry, api,
                roSet, stillWritable, from, cookie);
    }

    /** 只带插件资源的 Context —— 插件代码拿到的必须是它（拿宿主的 ctx 就取不到插件资源）。 */
    private static final class PContext extends ContextWrapper {
        private final AssetManager am;
        private final Resources res;
        private final ClassLoader cl;

        PContext(Context base, AssetManager am, Resources res, ClassLoader cl) {
            super(base);
            this.am = am; this.res = res; this.cl = cl;
        }

        @Override public AssetManager getAssets() { return am; }
        @Override public Resources getResources() { return res; }
        @Override public ClassLoader getClassLoader() { return cl; }
    }

    // ---- 判据函数（纯函数部分由 SelfTest 钉住） ---------------------------------

    /** 接口版本认不认。 */
    public static boolean apiCompatible(int pluginApi) {
        return pluginApi == API;
    }

    /**
     * 拒绝原因 → "能说给用户听"的那句话。
     * ★ 未识别的 code 一律落到 {@code plugin_err_other} —— 界面上**不许出现空白**。
     */
    public static int reasonRes(String reason) {
        if (reason == null) return R.string.plugin_err_other;
        if ("plugin_err_missing".equals(reason)) return R.string.plugin_err_missing;
        if ("plugin_err_dex".equals(reason)) return R.string.plugin_err_dex;
        if ("plugin_err_noclass".equals(reason)) return R.string.plugin_err_noclass;
        if ("plugin_err_entry".equals(reason)) return R.string.plugin_err_entry;
        if ("plugin_err_api".equals(reason)) return R.string.plugin_err_api_fmt;
        if ("plugin_err_asset".equals(reason)) return R.string.plugin_err_asset;
        return R.string.plugin_err_other;
    }

    /** 插件目录里的候选：只认 `.apk`，按名字排序（保证"同一目录两次打开顺序一样"）。 */
    public static java.util.List<File> pluginFiles(File dir) {
        java.util.List<File> out = new java.util.ArrayList<File>();
        File[] all = (dir == null) ? null : dir.listFiles();
        if (all != null) {
            java.util.Arrays.sort(all);
            for (File f : all) {
                if (f.isFile() && f.getName().endsWith(".apk")) out.add(f);
            }
        }
        return out;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
