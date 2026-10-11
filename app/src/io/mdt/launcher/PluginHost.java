package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * 插件加载器 + S0 可行性实验（2026-10-11）。
 *
 * <p><b>要回答的唯一问题</b>：壳能不能在 **product（非 debuggable）** 构建里，
 * 加载一个"我们自己存储里的外部 APK"的**类与资源**，并让某一页由它渲染。
 *
 * <p>四道门，顺序不能换（每一步都在"更贵的那一步"之前先便宜地失败）：
 * <ol>
 *   <li><b>置只读</b> —— ART 拒绝加载可写的 dex（{@code Importer} 里那条老规矩的同族）；</li>
 *   <li><b>预热预检</b> —— 抛一次性 {@link DexClassLoader} 把入口类解出来：缺类/坏包在这里就该被拒；</li>
 *   <li><b>接口版本</b> —— 入口报的 api 不等于 {@link #API} ⇒ 拒（否则后面每个反射调用都是掷骰子）；</li>
 *   <li><b>挂资源</b> —— 优先**新建**一个 {@code AssetManager}（不污染宿主），失败才退回宿主那份，
 *       两条路都记进报告（这本身就是"隐藏 API 还能不能用"的判据）。</li>
 * </ol>
 *
 * <p>★ 报告写 {@code <hub>/report-plugin.txt}（与 report-m3.txt 同一套路，adb 直接取回）。
 * ⚠️ <b>本文件里的诊断字面量一律 ASCII</b>：门禁 SRC-02 数的是"Java 里含中文的字符串字面量"，
 * 而它要求台账**逐文件相等** ⇒ 新文件里加中文会让门禁红。
 * 插件提供的中文（资源字符串）是**数据**，照原样进报告，不受此限。
 */
public final class PluginHost {

    /** 壳认的插件接口版本。插件报别的数 ⇒ 拒绝加载。 */
    public static final int API = 1;

    /** 插件入口类（**只在字符串里出现** —— 壳的源码不编译期依赖插件）。 */
    public static final String ENTRY = "io.mdt.plugtest.PlugPage";

    /** 同名类夹具（壳里也有同名类，见 PluginProbe / PluginProbe2）。 */
    public static final String PROBE = "io.mdt.launcher.PluginProbe";
    public static final String PROBE2 = "io.mdt.launcher.PluginProbe2";

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

    /** 拒绝加载。{@code reason} 是"能说给用户听"的那一层（将来接 Trans），{@code detail} 只进报告/日志。 */
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

        // (1) 尝试置只读 —— ★ 但**不能拿它当门**：外部存储（FUSE/sdcardfs）上 `setReadOnly()`
        //     常常直接返回 false（实测：hub/plugins 下的夹具全部如此）。ART 的规矩是
        //     "**可写的 dex 不许加载**"，所以真正的判据是"试完之后还写不写得动"，
        //     以及**最终 ART 认不认**（那道判据在下面的 DexClassLoader 上）。
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
            // ★ 别吞原因：第一次就是只打了类名，导致"为什么解不出入口类"看不出根因。
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

    // ---- 实验 -----------------------------------------------------------------

    /**
     * 临时入口（S0 实验用，结论落进 REF 后可删）：
     * {@code am start -n io.mdt.launcher/.MainActivity --es plugin_probe <夹具目录>} 就跑一遍实验。
     *
     * <p>★ 刻意**不走 `dev_*` 口**：dev 口在产品版被拒（`DevTools` 是空壳），而本实验要回答的正是
     * "**非 debuggable** 的 product 构建能不能加载外部 APK 的类与资源" ⇒ 必须有一条产品版也认的入口。
     */
    public static void maybeRun(final Context ctx, android.content.Intent intent) {
        if (intent == null) return;
        final String dir = intent.getStringExtra("plugin_probe");
        if (dir == null || dir.length() == 0) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runExperiment(ctx, new File(dir));
                } catch (Throwable ignored) {
                }
            }
        }, "plugin-probe");
        t.setDaemon(true);
        t.start();
    }

    // ---- 给界面用的窄接口（纯函数部分由 SelfTest 钉住） -------------------------

    /** 接口版本认不认（纯函数：自检直接钉它）。 */
    public static boolean apiCompatible(int pluginApi) {
        return pluginApi == API;
    }

    /**
     * 拒绝原因 → "能说给用户听"的那句话（纯函数）。
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

    /**
     * 建"插件"页：上面列候选，下面是被选中的插件渲染出来的内容。
     * 插件加载失败 ⇒ **兜底**（壳自带的那句话）+ **明说原因**（{@link #reasonRes}）。
     *
     * <p>⚠️ 这一版刻意**不新增 Activity、不动清单**（那个文件正被另一个会话改着）：
     * 页面直接铺在调用方的 Activity 上，等结论落定再拆成独立页 + 清单声明。
     */
    public static View buildPage(final Activity a, final File dir) {
        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(16 * a.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(a);
        title.setText(Trans.get(a, R.string.plugin_page_title));
        title.setTextSize(22f);
        root.addView(title);

        TextView sub = new TextView(a);
        sub.setText(Trans.get(a, R.string.plugin_page_sub));
        sub.setTextSize(13f);
        root.addView(sub);

        final LinearLayout host = new LinearLayout(a);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(0, pad, 0, 0);
        root.addView(host);

        java.util.List<File> files = pluginFiles(dir);
        if (files.isEmpty()) {
            TextView none = new TextView(a);
            none.setText(Trans.get(a, R.string.plugin_none));
            host.addView(none);
            return root;
        }
        for (final File f : files) {
            TextView row = new TextView(a);
            row.setText(f.getName());
            row.setPadding(pad, pad, pad, pad);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    render(a, f, host);
                }
            });
            root.addView(row);
        }
        return root;
    }

    /** 把某个插件渲染进 {@code host}；失败就走兜底 + 说明。 */
    public static void render(Activity a, File apk, ViewGroup host) {
        host.removeAllViews();
        try {
            Loaded l = load(a, apk);
            TextView cap = new TextView(a);
            cap.setText(Trans.get(a, R.string.plugin_loaded_fmt, apk.getName(), l.api));
            host.addView(cap);
            Object v = l.entry.getMethod("createView", Context.class).invoke(null, l.ctx);
            if (v instanceof View) host.addView((View) v);
        } catch (PluginException e) {
            TextView t = new TextView(a);
            t.setText(Trans.get(a, R.string.plugin_fallback_title));
            host.addView(t);
            TextView why = new TextView(a);
            if ("plugin_err_api".equals(e.reason)) {
                why.setText(Trans.get(a, reasonRes(e.reason), e.num1, e.num2));
            } else {
                why.setText(Trans.get(a, reasonRes(e.reason)));
            }
            host.addView(why);
        } catch (Throwable t) {
            TextView why = new TextView(a);
            why.setText(Trans.get(a, R.string.plugin_err_other) + "\n" + brief(t));
            host.addView(why);
        }
    }

    /** 跑一遍全部夹具，返回报告文本（同时写 {@code <hub>/report-plugin.txt}）。 */
    public static String runExperiment(Context ctx, File dir) {
        List<String> L = new ArrayList<>();
        L.add("== plugin feasibility (S0) ==");
        L.add("time      = " + new Date());
        L.add("host debuggable = " + debuggable(ctx) + "   (product build must be false)");
        L.add("fixture dir = " + dir);
        L.add("api       = " + API);

        // (1) good plugin: class + resources must both work
        L.add("");
        L.add("-- (1) good plugin plugtest-ok.apk --");
        try {
            Loaded ok = load(ctx, new File(dir, "plugtest-ok.apk"));
            L.add("  load        = OK   [ART accepted this path]");
            L.add("  setReadOnly = " + ok.readOnlySet + "   (false on external FUSE storage - expected)");
            L.add("  stillWritable = " + ok.stillWritable + "   (true = ART still loaded it; judge by ART, not chmod)");
            L.add("  assets from = " + ok.assetFrom + " cookie=" + ok.cookie);
            L.add("  plugin str  = " + ok.entry.getMethod("hello", Context.class).invoke(null, ok.ctx));
            Object v = ok.entry.getMethod("createView", Context.class).invoke(null, ok.ctx);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                L.add("  createView  = text[" + t.getText() + "] color=#" + Integer.toHexString(t.getCurrentTextColor()));
            } else {
                L.add("  createView  = " + (v == null ? "null" : v.getClass().getName()));
            }
        } catch (PluginException e) {
            L.add("  load        = REJECTED reason=" + e.reason + " detail=" + e.detail);
        } catch (Throwable t) {
            L.add("  load        = UNEXPECTED " + brief(t));
        }

        // (2) bad plugins: each must be rejected, with distinguishable reasons
        L.add("");
        L.add("-- (2) bad plugins --");
        reject(ctx, L, new File(dir, "plugtest-badapi.apk"), "api=99 (shell wants 1)");
        reject(ctx, L, new File(dir, "plugtest-noclass.apk"), "entry class missing");
        reject(ctx, L, new File(dir, "plugtest-nothing.apk"), "file does not exist");

        // (3) same-name class shadowing: the core datum for "how far can hot-update go"
        L.add("");
        L.add("-- (3) shadowing --");
        try {
            ClassLoader hostCL = PluginHost.class.getClassLoader();
            L.add("  a) host loads PROBE (pin as loaded)  = " + tagOf(hostCL.loadClass(PROBE)));

            // v1 只走它自己的 DexClassLoader（★ 每个 dex 文件只注册一次，别两边都来）
            Loaded v1 = load(ctx, new File(dir, "plugtest-v1.apk"));
            L.add("  b) plugin CL loads PROBE (parent 1st)= " + tagOf(v1.cl.loadClass(PROBE)));

            // 注入一律用**独立副本**，且只登记给宿主 CL 一次（Reflect.injectDex）
            List<String> notes = new ArrayList<String>();
            File c1 = uniqueCopy(ctx, new File(dir, "plugtest-v1.apk"));
            int n1 = Reflect.injectDex(hostCL, c1, ctx.getCodeCacheDir(), false, notes);
            L.add("  c) inject v1-copy APPEND -> " + String.valueOf(notes.get(notes.size() - 1))
                    + " elems=" + n1);
            L.add("     host gets PROBE                   = " + tagOf(hostCL.loadClass(PROBE))
                    + "   [expect SHELL: host dex still first]");

            File c2 = uniqueCopy(ctx, new File(dir, "plugtest-v2.apk"));
            int n2 = Reflect.injectDex(hostCL, c2, ctx.getCodeCacheDir(), true, notes);
            L.add("  d) inject v2-copy PREPEND -> " + String.valueOf(notes.get(notes.size() - 1))
                    + " elems=" + n2);
            L.add("     host gets PROBE (already loaded)  = " + tagOf(hostCL.loadClass(PROBE))
                    + "   [expect SHELL: an already-loaded class cannot be replaced]");
            L.add("     host gets PROBE2 (never loaded)   = " + tagOf(hostCL.loadClass(PROBE2))
                    + "   [expect PLUGIN-v2-2: an unloaded same-name class CAN be shadowed]");
            L.add("     host gets plugin entry class      = " + hostCL.loadClass(ENTRY).getName()
                    + "   [sanity: v2 dex really is in the host path]");
            L.add("     copies: " + c1.getName() + " / " + c2.getName());
        } catch (Throwable t) {
            L.add("  FAILED " + brief(t));
        }

        String report = join(L);
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), "report-plugin.txt"), report);
        } catch (Throwable ignored) {
        }
        return report;
    }

    /** 拷一份"独立副本"到内部存储并置只读（唯一文件名 ⇒ 不会被上一轮的只读文件挡住）。 */
    private static File uniqueCopy(Context ctx, File from) throws Exception {
        File to = new File(ctx.getCacheDir(), "plugtest-shadow-" + System.currentTimeMillis() + ".apk");
        java.io.InputStream in = new java.io.FileInputStream(from);
        try {
            java.io.OutputStream out = new java.io.FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        to.setReadOnly();
        return to;
    }

    private static void reject(Context ctx, List<String> L, File apk, String what) {
        L.add("  - " + apk.getName() + " (" + what + ")");
        try {
            load(ctx, apk);
            L.add("      !! LOADED ANYWAY - this gate is broken");
        } catch (PluginException e) {
            L.add("      ok REJECTED reason=" + e.reason + " detail=" + e.detail);
        } catch (Throwable t) {
            L.add("      !! UNEXPECTED " + brief(t));
        }
    }

    private static String tagOf(Class<?> c) {
        try {
            return String.valueOf(c.getMethod("tag").invoke(null));
        } catch (Throwable t) {
            return "!" + brief(t);
        }
    }

    private static boolean debuggable(Context ctx) {
        return (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static String join(List<String> L) {
        StringBuilder sb = new StringBuilder();
        for (String s : L) sb.append(s).append('\n');
        return sb.toString();
    }
}
