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
import io.mdt.launcher.api.PickCallback;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
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
     * <p>v1 = 只有 {@code api() + createView(Context)}；v2 = 自述 JSON + access；
     * **v3（第 159 轮）= 加上 {@link PluginApi} 与 {@code content} 插槽**；
     * **v4（第 160 轮）= 同一插槽扩到全部页面 + {@link PluginApi#selectedFile()}**；
     * **v5（第 161 轮）= 只读数据面：槽列表 / 四类内容 / 页面快照 / 统一字节流**；
     * **v6（第 162 轮）= 设置 / 日志 / 中转站 / 版本 / selectedItems**；
     * **v7（第 187 轮）= 做事：主题 / 后台任务与进度 / 关页 / 分享·保存 / 勾选**；
     * **v9（第 193 轮）= 详情页的「当前这一项」（{@link PluginApi#selectedItem()}）
     * + `section` 插槽（插件把自己的一段界面直接画进宿主页，统计不再另开一页）；
     * **v10（第 195 轮）= `choose()`：插件要问「怎么导出」时由**宿主**弹原生对话框**；
     * **v11（第 196 轮）= `export(..., after)`：保存真的结束后回调插件**；
     * **v12（第 197 轮）= `action` 插槽 + `run(PluginApi)` 入口：点了就在**当前页**跑，不单开页面**；
     * **v13（第 198 轮）= `progressCard()`：宿主进度弹窗（搬家前「正在统计 N / M…」那个）**；
     * **v14（第 202 轮）= `dataDir()` / `gameRunning()` / `hostIcon()` / `confirm()` / `prompt()`**；
     * **v15（第 205 轮）= `pickFile()` / `openItem()` / `trashItem()` / 可取消 `progressCard()`**；
     * **v16（第 206 轮）= `writeItem()`：同目录临时文件 + 原子替换，游戏在跑时拒绝**；
     * **v17（第 207 轮）= 入口类可选钩子 `static void unload()`：插件页关闭 / 换页 / 销毁时收尾**。
     * 插件报 5/6/7 才意味着"我会用这些能力"，报 1/2/3/4 的照旧；
     * 区间下限不变（{@link #API_MIN} = 1）。
     */
    public static final int API = 17;
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
     * @param selected {@link io.mdt.launcher.api.PluginApi#selectedFile()}；非详情页传 null
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
     * <p>顺序：{@code createView(PluginApi)} 优先，没有才回落 {@code createView(Context)}
     * （v1/v2 老插件就只有后者）。两种都取不到 ⇒ 抛 {@link NoSuchMethodException} 由调用方兜底。
     */
    static View createView(Loaded l) throws Exception {
        if (l == null || l.entry == null) throw new IllegalArgumentException("plugin not loaded");
        try {
            Method m = l.entry.getMethod("createView", PluginApi.class);
            return (View) m.invoke(null, l.env);
        } catch (NoSuchMethodException noApi) {
            Method m = l.entry.getMethod("createView", Context.class);
            return (View) m.invoke(null, l.ctx);
        }
    }

    /**
     * 调插件的**动作**入口（v12：`slot: action`）—— **不建页面、不换页**。
     *
     * <p>顺序同 {@link #createView}：{@code run(PluginApi)} 优先，没有才回落 {@code run(Context)}。
     * 两种都没有 ⇒ 抛 {@link NoSuchMethodException}，由调用方兜底（点了没反应，页面照常）。
     */
    static void runEntry(Loaded l) throws Exception {
        if (l == null || l.entry == null) throw new IllegalArgumentException("plugin not loaded");
        try {
            Method m = l.entry.getMethod("run", PluginApi.class);
            m.invoke(null, l.env);
        } catch (NoSuchMethodException noApi) {
            Method m = l.entry.getMethod("run", Context.class);
            m.invoke(null, l.ctx);
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

        @Override public File selectedFile() { return selected; }

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

        @Override public List<ItemInfo> contents(String kind) {
            return PluginData.contents(getBaseContext(), slot, kind);
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

        @Override public Map<String, String> settings() {
            return PluginData.settings(getBaseContext());
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
            final Context base = getBaseContext();
            final Context app = base.getApplicationContext() == null
                    ? base : base.getApplicationContext();
            // ★ 第 187 轮：把 Activity 交给 Work —— 通知权限只能在界面上申请
            final Context work = base instanceof Activity ? base : app;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    Work.begin(work, R.string.work_notif_plain, label);
                    try {
                        body.run();
                    } catch (Throwable err) {
                        // ★ 插件的异常不许带走整个进程（非主线程的未捕获异常默认会杀进程）
                        android.util.Log.w("MDTPlugin", "background task failed", err);
                    } finally {
                        Work.end(work);
                    }
                }
            }, "mdt-plugin-work");
            t.start();
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

        @Override public void export(String name, String mime, InputStream content) {
            export(name, mime, content, null);
        }

        /** v13：一个宿主进度弹窗（同一时刻只有一个）；v15 起可取消。 */
        private android.app.ProgressDialog card;
        private boolean cardCancelable;
        private Cancel cardCancel;

        @Override public void progressCard(final String title, final String message,
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

        @Override public android.graphics.drawable.Drawable hostIcon(String name) {
            try {
                Context hostCtx = getBaseContext();
                int id = hostCtx.getResources().getIdentifier(name, "drawable",
                        hostCtx.getPackageName());
                return id == 0 ? null : hostCtx.getResources().getDrawable(id, hostCtx.getTheme());
            } catch (Throwable t) {
                return null;
            }
        }

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
            boolean ok = false;
            try {
                File f = it == null || it.path == null ? null : new File(it.path);
                Context c = getBaseContext();
                if (f != null && f.exists()) {
                    if (ItemInfo.KIND_MODS.equals(it.kind)) {
                        ok = Mods.deleteToTrash(c, modsDir(), f, Trash.modsDir(c)) != null;
                    } else if (ItemInfo.KIND_BLUEPRINTS.equals(it.kind)) {
                        ok = BlueprintFiles.deleteToTrash(c, blueprintsDir(), f,
                                BlueprintFiles.trashDirOf(c)) != null;
                    } else {
                        ok = MapFiles.deleteToTrash(c, mapsDir(), f,
                                MapFiles.trashDirOf(c)) != null;
                    }
                }
            } catch (Throwable ignored) { }
            if (callback != null) callback.onDone(ok);
        }

        @Override public void progressCard(String title, String message, int done, int total,
                                           boolean cancelable, final Cancel cancel) {
            cardCancel = cancel;
            cardCancelable = cancelable;
            progressCard(title, message, done, total);
        }

        @Override public void writeItem(ItemInfo it, InputStream content, Done callback) {
            boolean ok = false;
            try {
                if (it != null && it.path != null && content != null
                        && !Data.gameAlive(getBaseContext())) {
                    File f = new File(it.path);
                    File dir = f.getParentFile();
                    if (f.isFile() && dir != null && insideSlot(dir, f)) {
                        File tmp = new File(dir, "." + f.getName() + ".tmp");
                        java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
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
                    }
                }
            } catch (Throwable t) {
                android.util.Log.i("MDTLauncher", "writeItem err=" + t);
            } finally {
                try { if (content != null) content.close(); } catch (Throwable ignored) { }
            }
            if (callback != null) callback.onDone(ok);
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

        @Override public void pick(String kind, PickCallback callback) {
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
