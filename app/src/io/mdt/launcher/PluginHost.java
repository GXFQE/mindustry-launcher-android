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
    public static final int API = 2;
    /**
     * 还认的**最老**契约版本（第 139 轮）。抬它 = 老插件被拒，界面会说清"太老、该更新插件"。
     *
     * <p>★ 为什么接口版本必须是**区间**而不是"严格相等"：全插件化的前提是"宿主基本不变、插件独立更新"，
     * 严格相等等于**每加一个插件都得连宿主一起发**。规矩：**加法变更**（多一个可选字段 / 多一个插槽）
     * **不抬版本**；只有**破坏性变更**（改了必填字段的含义 / 拿掉一个插槽的契约）才抬，
     * 抬的时候同时决定要不要抬 {@link #API_MIN}（抬了就是明确拒绝老插件）。
     */
    public static final int API_MIN = 1;

    /**
     * **默认**插件入口类（只在字符串里出现 —— 壳的源码不编译期依赖插件）。
     *
     * <p>🔴 第 134 轮真机定案：入口类**必须一插件一份**，因为宿主 CL 对每个类名只定义一次 ——
     * 共用同一个 FQN 时，入口归**第一个注入的**插件，后面那些插件点「打开」渲染的都是它的页。
     * ⇒ 新插件走 {@link PluginMeta} 自述里的 {@code entry}；**没写自述的**（老插件 / S0 夹具）
     * 才回落到这里，并且那种情况下启动报告会把它标成 {@code SHARED}。
     */
    public static final String DEFAULT_ENTRY = "io.mdt.plugtest.PlugPage";

    private PluginHost() {}

    /** 入口名兜底（null / 空 ⇒ {@link #DEFAULT_ENTRY}）。 */
    static String entryOr(String e) {
        return (e == null || e.isEmpty()) ? DEFAULT_ENTRY : e;
    }

    /** 加载成功后的句柄。 */
    public static final class Loaded {
        /**
         * 解析插件类用的那个 ClassLoader。
         * ★ 两条路给的不是同一种东西：{@link #load} 给插件自己的 {@code DexClassLoader}，
         * 而 {@link #openInjected}（早注入那条路）给的是**宿主 CL** —— 那种情况下插件类就住在
         * 宿主 CL 里，再建一个 DexClassLoader 会让 ART 报 "multiple class loaders"（S0 实测）。
         */
        public final ClassLoader cl;
        public final Context ctx;
        public final Class<?> entry;
        public final int api;
        public final boolean readOnlySet;
        public final boolean stillWritable;
        public final String assetFrom;
        public final int cookie;

        Loaded(ClassLoader cl, Context ctx, Class<?> entry, int api,
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
        return open(host, apk, null, DEFAULT_ENTRY);
    }

    /** 按插件自述的入口类名加载（见 {@link PluginMeta}）。 */
    public static Loaded load(Context host, File apk, String entry) throws PluginException {
        return open(host, apk, null, entryOr(entry));
    }

    /**
     * 打开一个**已经注入宿主 CL** 的插件（启动早注入那条路，见 {@link Plugins}）。
     *
     * <p>★ 与 {@link #load} 的唯一区别：**不建 {@code DexClassLoader}**。同一个 dex 注册给两个
     * ClassLoader 会让 ART 直接报 {@code InternalError: Attempt to register dex file ... with
     * multiple class loaders}（S0 实测）—— 而那个 dex 已经由 {@link Reflect#injectDex} 注册给宿主 CL 了。
     * 这里只跑后三道门：按名字取类（预热预检）→ 接口版本 → 挂资源。
     */
    static Loaded openInjected(Context host, File apk) throws PluginException {
        return open(host, apk, host.getClassLoader(), DEFAULT_ENTRY);
    }

    /** 按插件自述的入口类名打开已经注入宿主 CL 的那一份（多插件下"点谁开谁"靠它）。 */
    static Loaded openInjected(Context host, File apk, String entry) throws PluginException {
        return open(host, apk, host.getClassLoader(), entryOr(entry));
    }

    /** @param injectedTo 非 null = 这个 dex 已经注册给它了 ⇒ 不再建 DexClassLoader */
    private static Loaded open(Context host, File apk, ClassLoader injectedTo, String entryName)
            throws PluginException {
        if (apk == null || !apk.isFile()) {
            throw new PluginException("plugin_err_missing", String.valueOf(apk));
        }

        // (1) 尝试置只读（只记录，不当门 —— 见类注释）
        boolean wasWritable = apk.canWrite();
        boolean roSet = false;
        if (wasWritable) roSet = apk.setReadOnly();
        boolean stillWritable = apk.canWrite();

        // (2) 预热预检
        ClassLoader cl;
        if (injectedTo != null) {
            cl = injectedTo;
        } else {
            try {
                cl = new DexClassLoader(apk.getAbsolutePath(),
                        host.getCodeCacheDir().getAbsolutePath(), null, host.getClassLoader());
            } catch (Throwable t) {
                throw new PluginException("plugin_err_dex", brief(t));
            }
        }
        Class<?> entry;
        try {
            entry = cl.loadClass(entryName);
        } catch (Throwable t) {
            // ★ 别吞原因：第一次就是只打了类名，导致"为什么解不出入口类"看不出根因
            throw new PluginException("plugin_err_noclass", entryName + " | " + brief(t));
        }

        // (3) 接口版本
        int api;
        try {
            api = ((Integer) entry.getMethod("api").invoke(null)).intValue();
        } catch (Throwable t) {
            throw new PluginException("plugin_err_entry", brief(t));
        }
        if (!apiCompatible(api)) {
            // num1 = 插件报的版本；num2 = 它越过的那个界（界面按 apiVerdict 分岔用不着它，日志/自检要看）
            int v = apiVerdict(api);
            throw new PluginException("plugin_err_api",
                    "api " + api + (v > 0 ? " > " : " < ") + (v > 0 ? API : API_MIN)
                            + " (accepted " + API_MIN + ".." + API + ")",
                    api, v > 0 ? API : API_MIN);
        }

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
        return apiVerdict(pluginApi) == 0;
    }

    /**
     * 接口版本三态（**纯函数**，自检钉）：0 = 收 · +1 = 插件比本机新（该升级启动器）·
     * -1 = 插件太老（该更新插件）。见 {@link #API} 那段注释里"为什么是区间"。
     */
    public static int apiVerdict(int pluginApi) {
        if (pluginApi > API) return 1;
        if (pluginApi < API_MIN) return -1;
        return 0;
    }

    /**
     * 接口版本那句话按三态**分岔**（一处判据 —— 界面别自己拼，也别只印一句"不匹配"让用户猜）。
     * 太新 ⇒ 该升级启动器；太老 ⇒ 该更新插件。
     */
    public static int apiReasonRes(int pluginApi) {
        int v = apiVerdict(pluginApi);
        if (v > 0) return R.string.plugin_err_api_new_fmt;
        if (v < 0) return R.string.plugin_err_api_old_fmt;
        return R.string.plugin_err_api_fmt;
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
