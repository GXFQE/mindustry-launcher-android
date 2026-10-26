package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;
import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.launcher.api.Result;
import io.mdt.launcher.api.SlotInfo;

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
 *   <li><b>接口版本</b> —— 入口报的 api 不在 [{@link #API_MIN}, {@link #API}] 区间 ⇒ 拒
 *       （否则后面每个反射调用都是掷骰子）；</li>
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

    /**
     * 壳认的插件接口版本。
     *
     * <p>**v20（2026-10-23）= 冻结基线**：把逐年长出来的接口彻底整理了一遍 ——
     * 去掉重复能力（`selectedFile` / `settings` / `hostIcon`）、统一结果类型（{@link Result}）、
     * 重载收成默认方法、只认 {@code createView(PluginApi)} / {@code run(PluginApi)}
     * （不再回落 {@code createView(Context)}）。
     *
     * <p>**v21（2026-10-23 第 220 轮）= 加法**：`gameSettings()` / `writeGameSettings()` ——
     * 直接给 `settings.bin` 的解析结果与安全写，键的语义归插件（见 `docs/PLUGIN-API.md`）。
     *
     * <p>**v22（2026-10-23 第 221 轮）= 加法**：`submit()` / `Task` —— 跨页面存活的后台任务，
     * 完成/失败/取消都通知用户（下载、复杂分析的底座）。
     *
     * <p>**v23~v27（2026-10-23~24）= 加法**：探针（v23）、分析器（v24）、
     * 结论挂行 + 筛选/排序注入 + `parseHjson`（v25）、逐项判定 `state()`（v26）、
     * 详情页的门 `gates()` + 槽级结论进摘要卡 `Finding.summary()`（v27）—— 逐版见 `docs/PLUGIN-API.md`。
     *
     * <p>**v28（2026-10-24 第 234 轮）= 加法**：`baseBundleKeys()` —— 目标版本基础语言包的键集合，
     * 给"两个模组是不是都在改同一句**原版**文案"用（判据在插件，宿主只给事实）。
     *
     * <p>**v29（2026-10-26 第 254 轮）= 加法**：`Probe.onForeground(boolean)` —— 游戏 activity
     * 进/出台事件（只对进程内探针）；宿主只报事件，记不记由插件定。
     *
     * <p>**v30（2026-10-26 第 255 轮）= 加法**：`ProbeEnv.version()` / `ProbeEnv.dataDir()` ——
     * 探针知道"这一局是哪个版本"，并能把跨会话的账本写进插件自己的数据目录；
     * 另加 `playtimeTotal`（plugin.json 声明一个整数文件，宿主把它排版进主界面摘要卡）。
     *
     * <p>**v31（2026-10-26）= 加法**：`ProbeEnv.displayVersion()` —— 这一局的**人读版本号**
     * （已剥构建前缀，如 `159.7` / `2026.09.X37`）；`version()` 仍是身份（包名 / 文件名），
     * 两者一个是 key、一个是给人看的号，插件可把号一起写进自己的账本。
     *
     * <p>v1~v19 的逐版记录留在 `docs/PLUGIN-API.md` 与 git 历史里；**从 v20 起**：
     * 加法变更只抬 {@link #API}（{@link #API_MIN} 不动，老插件照样装得进），
     * 破坏性变更才抬 {@link #API_MIN}。
     */
    public static final int API = 31;
    /**
     * 还认的**最老**契约版本。
     *
     * <p>★ 为什么接口版本必须是**区间**而不是"严格相等"：全插件化的前提是"宿主基本不变、插件独立更新"，
     * 严格相等等于**每加一个插件都得连宿主一起发**。规矩：**加法变更**（多一个可选字段 / 多一个插槽）
     * **不抬下限**；只有**破坏性变更**（改了必填字段的含义 / 拿掉一个插槽的契约）才抬，
     * 抬了就是明确拒绝老插件。
     *
     * <p>2026-10-23 抬到 20：v1~v6 的老加载路径（{@code createView(Context)}）一起删掉 ——
     * 那次整理之前公开的 v0.6-preview 只到 API 6，而没有第三方插件在跑。
     */
    public static final int API_MIN = 20;

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
        /** 同一份 ctx 暴露成插件能力门面（v3）；老插件忽略它即可。 */
        public final PluginApi env;
        public final Class<?> entry;
        public final int api;
        public final boolean readOnlySet;
        public final boolean stillWritable;
        public final String assetFrom;
        public final int cookie;

        Loaded(ClassLoader cl, Context ctx, PluginApi env, Class<?> entry, int api,
               boolean readOnlySet, boolean stillWritable, String assetFrom, int cookie) {
            this.cl = cl; this.ctx = ctx; this.env = env; this.entry = entry; this.api = api;
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
        return open(host, apk, null, DEFAULT_ENTRY, null, null, null, null);
    }

    /** 按插件自述的入口类名加载（见 {@link PluginMeta}）。 */
    public static Loaded load(Context host, File apk, String entry) throws PluginException {
        return open(host, apk, null, entryOr(entry), null, null, null, null);
    }

    /**
     * 第 159 轮：带上"从哪个页面来的 / 哪个槽"加载 —— 内容页的插件动作走这一条。
     *
     * @param page {@link io.mdt.launcher.api.PluginApi#page()}（null ⇒ plugins）
     * @param slot 当前槽（null ⇒ {@link Data#currentSlot(Context)}）
     */
    public static Loaded load(Context host, File apk, String entry, String page, String slot)
            throws PluginException {
        return open(host, apk, null, entryOr(entry), page, slot, null, null);
    }

    /**
     * 第 160 轮：再加"当前选中项"（详情页那一份地图 / 蓝图）。
     *
     * @param selected 详情页当前那一份**槽内文件**（内部快照；插件侧用
     *                 {@link io.mdt.launcher.api.PluginApi#selectedItem()} 取，包内条目也算）；非详情页传 null
     */
    public static Loaded load(Context host, File apk, String entry, String page, String slot,
                              File selected) throws PluginException {
        return open(host, apk, null, entryOr(entry), page, slot, selected, null);
    }

    /**
     * 第 193 轮（v9）：再带上「当前这一项」的完整条目（游戏 / 模组包里的也算）。
     * `section` 插槽靠它把统计画在详情页里。
     */
    public static Loaded load(Context host, File apk, String entry, String page, String slot,
                              File selected, ItemInfo item) throws PluginException {
        return open(host, apk, null, entryOr(entry), page, slot, selected, item);
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
        return open(host, apk, host.getClassLoader(), DEFAULT_ENTRY, null, null, null, null);
    }

    /** 按插件自述的入口类名打开已经注入宿主 CL 的那一份（多插件下"点谁开谁"靠它）。 */
    static Loaded openInjected(Context host, File apk, String entry) throws PluginException {
        return open(host, apk, host.getClassLoader(), entryOr(entry), null, null, null, null);
    }

    /** 同 {@link #load(Context, File, String, String, String)}，但走"已注入宿主 CL"那条路。 */
    static Loaded openInjected(Context host, File apk, String entry, String page, String slot)
            throws PluginException {
        return open(host, apk, host.getClassLoader(), entryOr(entry), page, slot, null, null);
    }

    /** 同 {@link #load(Context, File, String, String, String, File)}，但走「已注入宿主 CL」那条路。 */
    static Loaded openInjected(Context host, File apk, String entry, String page, String slot,
                               File selected) throws PluginException {
        return open(host, apk, host.getClassLoader(), entryOr(entry), page, slot, selected, null);
    }

    /** 同 {@link #load(Context, File, String, String, String, File, ItemInfo)}，走注入那条路。 */
    static Loaded openInjected(Context host, File apk, String entry, String page, String slot,
                               File selected, ItemInfo item) throws PluginException {
        return open(host, apk, host.getClassLoader(), entryOr(entry), page, slot, selected, item);
    }

    /** @param injectedTo 非 null = 这个 dex 已经注册给它了 ⇒ 不再建 DexClassLoader */
    private static Loaded open(Context host, File apk, ClassLoader injectedTo, String entryName,
                               String page, String slot, File selected, ItemInfo item)
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
        // 插件自己的目录：与它的 APK 同住 `<插件>/`（apk 的父目录就是那个 id 目录）
        File home = apk.getParentFile();
        File pdata = home == null ? null : new File(home, "data");
        File pcache = home == null ? null : new File(home, "cache");
        // 第 159 轮：老调用点（插件页 / dev 实验）没有页面信息 ⇒ 一律当"插件页 + 当前槽"
        String usePage = (page == null || page.trim().isEmpty())
                ? Extensions.SLOT_PLUGINS : page.trim();
        String useSlot = (slot == null || slot.trim().isEmpty())
                ? Data.currentSlot(host) : slot.trim();
        PContext pc = new PContext(host, am, res, cl, pdata, pcache, usePage, useSlot, selected,
                item);
        return new Loaded(cl, pc, pc, entry, api, roSet, stillWritable, from, cookie);
    }

    /**
     * 调插件的入口方法（第 159 轮：**一处实现**，别在界面里各写一遍）。
     *
     * <p>v20 起只认 {@code createView(PluginApi)}：老写法 {@code createView(Context)} **不再回落**，
     * 缺这个方法 ⇒ {@link PluginException}（{@code plugin_err_entry}），界面照常给兜底页。
     */
    static View createView(Loaded l) throws Exception {
        if (l == null || l.entry == null) throw new IllegalArgumentException("plugin not loaded");
        try {
            Method m = l.entry.getMethod("createView", PluginApi.class);
            return (View) m.invoke(null, l.env);
        } catch (NoSuchMethodException e) {
            throw new PluginException("plugin_err_entry", "no createView(PluginApi)");
        }
    }

    /**
     * 调插件的**动作**入口（v12：`slot: action`）—— **不建页面、不换页**。
     *
     * <p>v20 起只认 {@code run(PluginApi)}：缺它 ⇒ {@link PluginException}（{@code plugin_err_entry}），
     * 调用方给一句提示（点了没反应 = 最糟的失败方式）。
     */
    static void runEntry(Loaded l) throws Exception {
        if (l == null || l.entry == null) throw new IllegalArgumentException("plugin not loaded");
        try {
            Method m = l.entry.getMethod("run", PluginApi.class);
            m.invoke(null, l.env);
        } catch (NoSuchMethodException e) {
            throw new PluginException("plugin_err_entry", "no run(PluginApi)");
        }
    }

    /** 只带插件资源的 Context —— 插件代码拿到的必须是它（拿宿主的 ctx 就取不到插件资源）。 */
    /**
     * **v17**：叫插件收尾 —— 入口类可选 `static void unload()`（或 `unload(Context)`）。
     *
     * <p>调用点：插件页被关掉 / 换页 / Activity 销毁。插件在里面停线程、关定时器；
     * 抛异常一律吞掉（收尾不该把宿主带崩）。</p>
     */
    static void unload(Loaded l) {
        if (l == null || l.entry == null) return;
        try {
            try {
                l.entry.getMethod("unload").invoke(null);
                return;
            } catch (NoSuchMethodException ignored) { }
            try {
                l.entry.getMethod("unload", Context.class).invoke(null, l.ctx);
            } catch (NoSuchMethodException ignored) { }
        } catch (Throwable t) {
            android.util.Log.i("MDTLauncher", "plugin unload failed: " + t);
        }
    }

    private static final class PContext extends ContextWrapper implements PluginApi {
        private final AssetManager am;
        private final Resources res;
        private final ClassLoader cl;
        /** 这台插件自己的目录（`<插件>/data`、`<插件>/cache`，第 148 轮 C1）。 */
        private final File files, cache;
        /** 从哪个页面来的 / 哪个槽（第 159 轮 v3）。 */
        private final String page, slot;
        /** 详情页当前选中的那一份（第 160 轮 v4）；其余页 null。 */
        private final File selected;
        /** v9：详情页上当前这一项（含容器 / 条目）。 */
        private final ItemInfo item;

        PContext(Context base, AssetManager am, Resources res, ClassLoader cl, File files, File cache,
                 String page, String slot, File selected, ItemInfo item) {
            super(base);
            this.am = am; this.res = res; this.cl = cl;
            this.files = files; this.cache = cache;
            this.page = page; this.slot = slot; this.selected = selected; this.item = item;
        }

        @Override public AssetManager getAssets() { return am; }
        @Override public Resources getResources() { return res; }
        @Override public ClassLoader getClassLoader() { return cl; }

        // ---- PluginApi（v3 / v4）------------------------------------------

        @Override public int hostApi() { return API; }

        @Override public Context context() { return this; }

        @Override public String page() { return page; }

        @Override public String slot() { return slot; }

        @Override public ItemInfo selectedItem() { return item; }

        @Override public void choose(final String title, final String[] options,
                                     final Choose callback) {
            final Context base = getBaseContext();
            if (!(base instanceof Activity) || options == null || options.length == 0) {
                if (callback != null) callback.onChoose(-1);
                return;
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    Activity a = (Activity) base;
                    if (a.isFinishing()) {
                        if (callback != null) callback.onChoose(-1);
                        return;
                    }
                    new android.app.AlertDialog.Builder(a)
                            .setTitle(title)
                            .setItems(options, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    if (callback != null) callback.onChoose(which);
                                }
                            })
                            .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                                @Override public void onCancel(android.content.DialogInterface d) {
                                    if (callback != null) callback.onChoose(-1);
                                }
                            })
                            .show();
                }
            });
        }

        // ---- PluginApi（v5 只读数据面）-------------------------------------

        @Override public List<SlotInfo> slots() {
            return PluginData.slots(getBaseContext());
        }

        @Override public List<ItemInfo> contents(String slot, String kind) {
            return PluginData.contents(getBaseContext(), slot, kind);
        }

        @Override public List<ItemInfo> pageItems() {
            return PluginData.pageItems(getBaseContext(), page, slot, selected);
        }

        @Override public List<ItemInfo> selectedItems() {
            return PluginData.selectedItems(getBaseContext(), page, slot, selected);
        }

        @Override public List<ItemInfo> logs() {
            return PluginData.logs(getBaseContext());
        }

        @Override public List<ItemInfo> trash() {
            return PluginData.trash(getBaseContext());
        }

        @Override public List<ItemInfo> versions() {
            return PluginData.versions(getBaseContext());
        }

        @Override public boolean modsSkipped() {
            return PluginData.modsSkipped(getBaseContext(), slot);
        }

        @Override public java.util.Set<String> baseBundleKeys() {
            return BaseBundles.keys(getBaseContext(), slot);
        }

        @Override public InputStream open(ItemInfo item) throws IOException {
            return PluginData.open(item);
        }

        @Override public File slotDir() { return Data.dirOf(getBaseContext(), slot); }

        @Override public File savesDir() { return new File(slotDir(), "saves"); }

        @Override public File modsDir() { return new File(slotDir(), "mods"); }

        @Override public File mapsDir() { return new File(slotDir(), "maps"); }

        @Override public File blueprintsDir() { return new File(slotDir(), "schematics"); }

        @Override public void toast(final String message) {
            final Context c = getBaseContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    android.widget.Toast.makeText(c, message == null ? "" : message,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override public void log(String message) {
            android.util.Log.i("MDTPlugin", message == null ? "" : message);
        }

        // ---- PluginApi（v7：做事）------------------------------------------

        @Override public PluginTheme theme() {
            Context base = getBaseContext();
            android.content.res.Resources r = base.getResources();
            android.content.res.Configuration cfg = r.getConfiguration();
            android.util.DisplayMetrics dm = r.getDisplayMetrics();
            boolean dark = (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            return new PluginTheme(
                    r.getColor(R.color.bg),
                    r.getColor(R.color.card),
                    r.getColor(R.color.card_stroke),
                    r.getColor(R.color.divider),
                    r.getColor(R.color.fg),
                    r.getColor(R.color.fg_muted),
                    r.getColor(R.color.accent),
                    r.getColor(R.color.on_accent),
                    r.getColor(R.color.appbar),
                    r.getColor(R.color.on_appbar),
                    dark, dm.density, dm.scaledDensity);
        }

        @Override public void runBackground(final String label, final Runnable body) {
            if (body == null) return;
            // 页内长活：同一个任务底座，但不发完成通知（地图统计那种 1 秒的活不该多一条通知）
            PluginTasks.start(getBaseContext(), label, body, false);
        }

        @Override public PluginApi.Task submit(String label, Runnable body) {
            if (body == null) return null;
            return PluginTasks.start(getBaseContext(), label, body, true);
        }

        @Override public void progress(int done, int total) {
            Context base = getBaseContext();
            Work.progress(base.getApplicationContext() == null
                    ? base : base.getApplicationContext(), done, total);
        }

        @Override public void closePage() {
            final Context base = getBaseContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    if (base instanceof PluginsActivity) {
                        ((PluginsActivity) base).closePluginPage();
                    } else if (base instanceof Activity && !((Activity) base).isFinishing()) {
                        ((Activity) base).finish();
                    }
                }
            });
        }

        @Override public void share(String name, InputStream content) {
            Activity a = activityOrNull();
            if (a == null) {
                PluginActions.closeQuietly(content);
                noPage();
                return;
            }
            PluginActions.share(a, name, content);
        }

        @Override public void shareText(String name, String text) {
            Activity a = activityOrNull();
            if (a == null) {
                noPage();
                return;
            }
            PluginActions.shareText(a, name, text);
        }

        /** v13：一个宿主进度弹窗（同一时刻只有一个）；v15 起可取消。 */
        private android.app.ProgressDialog card;
        private boolean cardCancelable;
        private Cancel cardCancel;

        private void showCard(final String title, final String message,
                              final int done, final int total) {
            final Activity a = activityOrNull();
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (card == null) {
                            card = new android.app.ProgressDialog(a);
                            card.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
                            card.setCancelable(cardCancelable);
                            card.setOnCancelListener(
                                    new android.content.DialogInterface.OnCancelListener() {
                                        @Override public void onCancel(
                                                android.content.DialogInterface d) {
                                            final Cancel cb = cardCancel;
                                            card = null;
                                            if (cb != null) cb.onCancel();
                                        }
                                    });
                            card.setTitle(title);
                            card.show();
                        }
                        card.setMessage(message);
                        if (total > 0) {
                            card.setMax(total);
                            card.setProgress(Math.max(0, Math.min(done, total)));
                        }
                    } catch (Throwable ignored) {
                        // 页面在销毁 / 没窗口 ⇒ 不显示而已，别把导出弄挂
                    }
                }
            });
        }

        // ---- v14 ---------------------------------------------------------

        @Override public File dataDir() {
            if (!files.isDirectory()) files.mkdirs();
            return files;
        }

        @Override public boolean gameRunning() { return Data.gameAlive(this); }

        @Override public void confirm(final String title, final String message,
                                      final String okText, final Confirm callback) {
            final Context base = getBaseContext();
            if (!(base instanceof Activity)) {
                if (callback != null) callback.onConfirm(false);
                return;
            }
            final Activity a = (Activity) base;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        new android.app.AlertDialog.Builder(a)
                                .setTitle(title)
                                .setMessage(message)
                                .setPositiveButton(okText,
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(
                                                    android.content.DialogInterface d, int w) {
                                                if (callback != null) callback.onConfirm(true);
                                            }
                                        })
                                .setNegativeButton(android.R.string.cancel,
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(
                                                    android.content.DialogInterface d, int w) {
                                                if (callback != null) callback.onConfirm(false);
                                            }
                                        })
                                .show();
                    } catch (Throwable t) {
                        if (callback != null) callback.onConfirm(false);
                    }
                }
            });
        }

        @Override public void prompt(final String title, final String hint, final String initial,
                                     final String okText, final Prompt callback) {
            final Context base = getBaseContext();
            if (!(base instanceof Activity)) {
                if (callback != null) callback.onPrompt(null);
                return;
            }
            final Activity a = (Activity) base;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        final android.widget.EditText input = new android.widget.EditText(a);
                        input.setHint(hint == null ? "" : hint);
                        if (initial != null) input.setText(initial);
                        new android.app.AlertDialog.Builder(a)
                                .setTitle(title)
                                .setView(input)
                                .setPositiveButton(okText,
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(
                                                    android.content.DialogInterface d, int w) {
                                                if (callback != null) {
                                                    callback.onPrompt(
                                                            input.getText().toString().trim());
                                                }
                                            }
                                        })
                                .setNegativeButton(android.R.string.cancel,
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(
                                                    android.content.DialogInterface d, int w) {
                                                if (callback != null) callback.onPrompt(null);
                                            }
                                        })
                                .show();
                    } catch (Throwable t) {
                        if (callback != null) callback.onPrompt(null);
                    }
                }
            });
        }

        // ---- v15 ---------------------------------------------------------

        @Override public void pickFile(String mime, PickFile callback) {
            PluginActions.pickFile(activityOrNull(), cache, mime, callback);
        }

        @Override public void openItem(ItemInfo it) {
            final Activity a = activityOrNull();
            if (a == null || it == null) return;
            try {
                Intent i;
                if (ItemInfo.KIND_BLUEPRINTS.equals(it.kind)) {
                    i = new Intent(a, BlueprintDetailActivity.class);
                    i.putExtra(BlueprintDetailActivity.EXTRA_SLOT, slot);
                } else if (ItemInfo.KIND_MODS.equals(it.kind)) {
                    i = new Intent(a, ModsActivity.class);
                    i.putExtra("slot", slot);
                } else {
                    i = new Intent(a, MapDetailActivity.class);
                    i.putExtra(MapDetailActivity.EXTRA_SLOT, slot);
                }
                i.putExtra("source", it.source == null ? "" : it.source);
                i.putExtra("bytes", it.size);
                if (it.path != null) i.putExtra("file", it.path);
                if (it.container != null) i.putExtra("container", it.container);
                if (it.entry != null) i.putExtra("entry", it.entry);
                a.startActivity(i);
            } catch (Throwable t) {
                toast(Trans.get(getBaseContext(), R.string.plugin_open_failed_fmt,
                        Util.ioReason(getBaseContext(), t)));
            }
        }

        @Override public void trashItem(ItemInfo it, Done callback) {
            Result r;
            try {
                File f = it == null || it.path == null ? null : new File(it.path);
                Context c = getBaseContext();
                // v16 加固：只动"住在对应目录里"的条目；别的路径一律拒绝（别把任意文件挪进某类中转站）
                if (f == null || !f.exists()) {
                    r = Result.fail("not-found");
                } else if (!insideDir(f, dirForKind(it.kind))) {
                    r = Result.fail("not-in-slot");
                } else {
                    boolean ok;
                    if (ItemInfo.KIND_MODS.equals(it.kind)) {
                        ok = Mods.deleteToTrash(c, modsDir(), f, Trash.modsDir(c)) != null;
                    } else if (ItemInfo.KIND_BLUEPRINTS.equals(it.kind)) {
                        ok = BlueprintFiles.deleteToTrash(c, blueprintsDir(), f,
                                BlueprintFiles.trashDirOf(c)) != null;
                    } else {
                        ok = MapFiles.deleteToTrash(c, mapsDir(), f,
                                MapFiles.trashDirOf(c)) != null;
                    }
                    r = ok ? Result.ok() : Result.fail("io");
                }
            } catch (Throwable t) {
                r = Result.fail("io");
            }
            if (callback != null) callback.onDone(r);
        }

        @Override public void progressCard(final String title, final String message,
                                           final int done, final int total,
                                           boolean cancelable, final Cancel cancel) {
            cardCancel = cancel;
            cardCancelable = cancelable;
            showCard(title, message, done, total);
        }

        // ---- v21：游戏设置面（解析结果 + 安全写；语义归插件）------------------

        @Override public String parseHjson(File file) {
            if (file == null || !file.isFile()) return null;
            String raw;
            try {
                raw = Util.readText(file);
            } catch (Throwable t) {
                return null;
            }
            if (raw == null) return null;
            // 与宿主自己读 mod.hjson 同一条路：先 160 方言、不行再 159（见 Mods.parseMeta）
            try {
                return Hjson.toJsonText(raw, Hjson.ARC_160);
            } catch (Throwable first) {
                try {
                    return Hjson.toJsonText(raw, Hjson.ARC_159);
                } catch (Throwable second) {
                    return null;
                }
            }
        }

        @Override public Map<String, Object> gameSettings() {
            File file = Mods.settingsFileOf(getBaseContext(), slot);
            if (file == null || !file.isFile()) return new java.util.LinkedHashMap<String, Object>();
            String[] why = new String[1];
            SettingsBin.Values v = SettingsBin.readSafe(file, why);
            if (v == null) {
                android.util.Log.i("MDTPlugin", "gameSettings unreadable: " + why[0]);
                return null;
            }
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : v.all().entrySet()) {
                Object x = e.getValue();
                out.put(e.getKey(), x instanceof byte[] ? ((byte[]) x).clone() : x);
            }
            return out;
        }

        @Override public void writeGameSettings(Map<String, Object> changes, final Done done) {
            final Context c = getBaseContext();
            final String useSlot = slot;
            final java.util.LinkedHashMap<String, Object> copy =
                    new java.util.LinkedHashMap<String, Object>();
            if (changes != null) {
                for (Map.Entry<String, Object> e : changes.entrySet()) {
                    Object v = e.getValue();
                    copy.put(e.getKey(), v instanceof byte[] ? ((byte[]) v).clone() : v);
                }
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    final Result r = writeSettingsNow(c, useSlot, copy);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            if (done != null) done.onDone(r);
                        }
                    });
                }
            }, "mdt-plugin-settings").start();
        }

        /** 设置写的门禁与调用（后台线程；把 `SettingsBin.Result` 翻成给插件的 reason）。 */
        private Result writeSettingsNow(Context c, String useSlot,
                                        java.util.LinkedHashMap<String, Object> changes) {
            File file = Mods.settingsFileOf(c, useSlot);
            if (file == null) return Result.fail("not-found");
            if (Data.gameAlive(c)) return Result.fail("game-running");
            if (changes == null || changes.isEmpty()) return Result.fail("invalid");
            for (Map.Entry<String, Object> e : changes.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) return Result.fail("invalid");
                Object v = e.getValue();
                if (v == null) continue;                     // null = 删键
                try {
                    SettingsBin.checkValueType(v);
                } catch (Throwable t) {
                    return Result.fail("invalid");
                }
            }
            if (file.isFile()) {
                String[] why = new String[1];
                if (SettingsBin.readSafe(file, why) == null) return Result.fail("unreadable");
            }
            SettingsBin.Result sr = SettingsBin.applyValues(file,
                    Mods.settingsBackupDirOf(c, useSlot), changes);
            if (sr.ok) return Result.ok();
            if (sr.errCode == SettingsBin.Result.E_GAME_RUNNING) return Result.fail("game-running");
            if (sr.errCode == SettingsBin.Result.E_SETTINGS_UNREADABLE) return Result.fail("unreadable");
            if (sr.errCode == SettingsBin.Result.E_NO_KEYS
                    || sr.errCode == SettingsBin.Result.E_NULL_FILE) return Result.fail("invalid");
            return Result.fail("io");
        }

        /** v16 加固：同一目标路径的写要互斥（两个插件同时写同一份存档）。 */
        private final java.util.concurrent.ConcurrentHashMap<String, Object> writeLocks =
                new java.util.concurrent.ConcurrentHashMap<String, Object>();

        @Override public void writeItem(ItemInfo it, InputStream content, Done callback) {
            Result r = Result.fail("invalid");
            try {
                if (it != null && it.path != null && content != null) {
                    if (Data.gameAlive(getBaseContext())) {
                        r = Result.fail("game-running");
                    } else {
                        File f = new File(it.path);
                        File dir = f.getParentFile();
                        if (f.isFile() && dir != null && insideSlot(dir, f)) {
                            String key = f.getCanonicalPath();
                            Object lock = writeLocks.get(key);
                            if (lock == null) {
                                Object fresh = new Object();
                                lock = writeLocks.putIfAbsent(key, fresh);
                                if (lock == null) lock = fresh;
                            }
                            synchronized (lock) {
                                File tmp = new File(dir, "." + f.getName() + "."
                                        + Long.toHexString(System.nanoTime()) + ".tmp");
                                java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
                                boolean ok;
                                try {
                                    PluginActions.copy(content, os);
                                    os.flush();
                                    os.getFD().sync();
                                } finally {
                                    os.close();
                                    content.close();
                                }
                                ok = tmp.renameTo(f);
                                if (!ok) tmp.delete();
                                r = ok ? Result.ok() : Result.fail("io");
                            }
                        } else {
                            r = Result.fail("not-in-slot");
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.i("MDTLauncher", "writeItem err=" + t);
                r = Result.fail("io");
            } finally {
                try { if (content != null) content.close(); } catch (Throwable ignored) { }
            }
            if (callback != null) callback.onDone(r);
        }

        /** 条目种类 → 它该住的那个目录。 */
        private File dirForKind(String kind) {
            if (ItemInfo.KIND_MODS.equals(kind)) return modsDir();
            if (ItemInfo.KIND_BLUEPRINTS.equals(kind)) return blueprintsDir();
            if (ItemInfo.KIND_SAVES.equals(kind)) return savesDir();
            return mapsDir();
        }

        /** 文件是否就在这个目录里（canonical 前缀）。 */
        private boolean insideDir(File f, File dir) {
            try {
                return f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator);
            } catch (Throwable t) {
                return false;
            }
        }

        /** 只许写在槽内那四个内容目录里（写入面唯一的口子）。 */
        private boolean insideSlot(File dir, File f) {
            try {
                String d = dir.getCanonicalPath();
                String[] roots = {mapsDir().getCanonicalPath(), savesDir().getCanonicalPath(),
                        modsDir().getCanonicalPath(), blueprintsDir().getCanonicalPath()};
                for (String r : roots) {
                    if (d.equals(r) || d.startsWith(r + File.separator)) return true;
                }
            } catch (Throwable ignored) { }
            return false;
        }

        @Override public File usageDir() {
            // 第 252 轮：宿主不再写 usage 流水（注入脚本的数据全由插件自己写）—— 这里只给目录。
            // 旧版本写过 launch.tsv / run.tsv，别依赖它们存在。
            return new File(Data.hubDir(getBaseContext()), "usage");
        }

        @Override public File crashReportsDir() {
            return CrashAlert.crashesDir(getBaseContext(), slot());
        }

        @Override public void progressCardClose() {
            final Activity a = activityOrNull();
            final Runnable close = new Runnable() {
                @Override public void run() {
                    try {
                        if (card != null && card.isShowing()) card.dismiss();
                    } catch (Throwable ignored) { }
                    card = null;
                }
            };
            if (a == null) { close.run(); return; }
            a.runOnUiThread(close);
        }

        @Override public void export(String name, String mime, InputStream content, After after) {
            Activity a = activityOrNull();
            if (a == null) {
                PluginActions.closeQuietly(content);
                noPage();
                return;
            }
            PluginActions.export(a, name, mime, content, after);
        }

        @Override public void pickContent(String kind, PickContent callback) {
            Activity a = activityOrNull();
            PluginActions.pick(a, getBaseContext(), slot, kind, callback);
        }

        /** 这些动作要一个真的 Activity（分享面板 / SAF / 对话框）；dev 页里也成立。 */
        private Activity activityOrNull() {
            Context b = getBaseContext();
            return b instanceof Activity ? (Activity) b : null;
        }

        /** "这个动作只能在插件页里用" —— 只有拿不到 Activity 时才会走到。 */
        private void noPage() {
            toast(Trans.get(getBaseContext(), R.string.plugin_no_page));
        }

        /**
         * ★ 第 148 轮 C1：插件写"自己的数据"要落在**它自己的目录**里（`<插件>/data`），
         * 与它的 APK 同住一个 id 目录 ⇒ 卸载/回滚时一套动作就能连数据一起收拾。
         *
         * <p>⚠️ 说清楚这是什么：宿主与插件**同进程、同 ClassLoader** ⇒ 这是**约定**，不是沙箱 ——
         * 插件真要绕过（自己拼 `/data/data/io.mdt.launcher/...`）我们拦不住。当沙箱用会出事。
         */
        @Override public File getFilesDir() {
            if (files != null) {
                if (!files.isDirectory()) files.mkdirs();
                return files;
            }
            return super.getFilesDir();
        }

        @Override public File getCacheDir() {
            if (cache != null) {
                if (!cache.isDirectory()) cache.mkdirs();
                return cache;
            }
            return super.getCacheDir();
        }
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
