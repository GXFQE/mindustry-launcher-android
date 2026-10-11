package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 插件化的 **dev 侧**：插件页 + S0 可行性实验。
 *
 * <p>★ 为什么整块住 `app/src-dev`：这两件都是**开发/验收用的**（一个演示页 + 一个实验入口），
 * 产品版不该带着它们 —— 与 `DevTools` / `SelfTest` 同一条裁剪规矩（`build.sh` 的 `[2/4] javac`）。
 * 产品侧只留 {@link PluginHost} 的**加载器与判据函数**；那边一行 UI 都不带。
 *
 * <p>两个入口（都走 dev 白名单，见 {@link DevTools}）：
 * <ul>
 *   <li>{@code --es dev_plugins <插件目录>} ⇒ 用插件渲染一页（失败则兜底 + 明说原因）</li>
 *   <li>{@code --es dev_pluginprobe <插件目录>} ⇒ 跑 S0 实验，报告写 {@code <hub>/report-plugin.txt}</li>
 * </ul>
 */
final class PluginProbeDev {

    /** 同名类夹具的全限定名（壳里也有这两个类，见本包 PluginProbe / PluginProbe2）。 */
    static final String PROBE = "io.mdt.launcher.PluginProbe";
    static final String PROBE2 = "io.mdt.launcher.PluginProbe2";

    private PluginProbeDev() {}

    // ---- 插件页（dev 演示） ----------------------------------------------------

    /** 建"插件"页：上面列候选，下面是被选中的插件渲染出来的内容。 */
    static View buildPage(final Activity a, final File dir) {
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

        List<File> files = PluginHost.pluginFiles(dir);
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
    static void render(Activity a, File apk, ViewGroup host) {
        host.removeAllViews();
        try {
            PluginHost.Loaded l = PluginHost.load(a, apk);
            TextView cap = new TextView(a);
            cap.setText(Trans.get(a, R.string.plugin_loaded_fmt, apk.getName(), l.api));
            host.addView(cap);
            Object v = l.entry.getMethod("createView", Context.class).invoke(null, l.ctx);
            if (v instanceof View) host.addView((View) v);
        } catch (PluginHost.PluginException e) {
            TextView t = new TextView(a);
            t.setText(Trans.get(a, R.string.plugin_fallback_title));
            host.addView(t);
            TextView why = new TextView(a);
            if ("plugin_err_api".equals(e.reason)) {
                why.setText(Trans.get(a, PluginHost.reasonRes(e.reason), e.num1, e.num2));
            } else {
                why.setText(Trans.get(a, PluginHost.reasonRes(e.reason)));
            }
            host.addView(why);
        } catch (Throwable t) {
            TextView why = new TextView(a);
            why.setText(Trans.get(a, R.string.plugin_err_other) + "\n" + brief(t));
            host.addView(why);
        }
    }

    // ---- S0 可行性实验 ---------------------------------------------------------

    /** 后台跑实验（不阻塞 UI），报告落 {@code <hub>/report-plugin.txt}。 */
    static void runExperimentAsync(final Context ctx, final File dir) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runExperiment(ctx, dir);
                } catch (Throwable ignored) {
                }
            }
        }, "plugin-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 跑一遍全部夹具，返回报告文本（同时写盘）。★ 诊断字面量一律 ASCII（门禁 SRC-02 那条）。 */
    static String runExperiment(Context ctx, File dir) {
        List<String> L = new ArrayList<String>();
        L.add("== plugin feasibility (S0) ==");
        L.add("time      = " + new Date());
        L.add("host debuggable = " + debuggable(ctx));
        L.add("fixture dir = " + dir);
        L.add("api       = " + PluginHost.API);

        L.add("");
        L.add("-- (1) good plugin plugtest-ok.apk --");
        try {
            PluginHost.Loaded ok = PluginHost.load(ctx, new File(dir, "plugtest-ok.apk"));
            L.add("  load        = OK   [ART accepted this path]");
            L.add("  setReadOnly = " + ok.readOnlySet + "   (false on external FUSE storage - expected)");
            L.add("  stillWritable = " + ok.stillWritable + "   (judge by ART, not chmod)");
            L.add("  assets from = " + ok.assetFrom + " cookie=" + ok.cookie);
            L.add("  plugin str  = " + ok.entry.getMethod("hello", Context.class).invoke(null, ok.ctx));
            Object v = ok.entry.getMethod("createView", Context.class).invoke(null, ok.ctx);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                L.add("  createView  = text[" + t.getText() + "] color=#"
                        + Integer.toHexString(t.getCurrentTextColor()));
            } else {
                L.add("  createView  = " + (v == null ? "null" : v.getClass().getName()));
            }
        } catch (PluginHost.PluginException e) {
            L.add("  load        = REJECTED reason=" + e.reason + " detail=" + e.detail);
        } catch (Throwable t) {
            L.add("  load        = UNEXPECTED " + brief(t));
        }

        L.add("");
        L.add("-- (2) bad plugins --");
        reject(ctx, L, new File(dir, "plugtest-badapi.apk"), "api=99 (shell wants 1)");
        reject(ctx, L, new File(dir, "plugtest-noclass.apk"), "entry class missing");
        reject(ctx, L, new File(dir, "plugtest-nothing.apk"), "file does not exist");

        L.add("");
        L.add("-- (3) shadowing --");
        try {
            ClassLoader hostCL = PluginHost.class.getClassLoader();
            L.add("  a) host loads PROBE (pin as loaded)  = " + tagOf(hostCL.loadClass(PROBE)));

            PluginHost.Loaded v1 = PluginHost.load(ctx, new File(dir, "plugtest-v1.apk"));
            L.add("  b) plugin CL loads PROBE (parent 1st)= " + tagOf(v1.cl.loadClass(PROBE)));

            List<String> notes = new ArrayList<String>();
            File c1 = uniqueCopy(ctx, new File(dir, "plugtest-v1.apk"));
            int n1 = Reflect.injectDex(hostCL, c1, ctx.getCodeCacheDir(), false, notes);
            L.add("  c) inject v1-copy APPEND -> " + notes.get(notes.size() - 1) + " elems=" + n1);
            L.add("     host gets PROBE                   = " + tagOf(hostCL.loadClass(PROBE))
                    + "   [expect SHELL: host dex still first]");

            File c2 = uniqueCopy(ctx, new File(dir, "plugtest-v2.apk"));
            int n2 = Reflect.injectDex(hostCL, c2, ctx.getCodeCacheDir(), true, notes);
            L.add("  d) inject v2-copy PREPEND -> " + notes.get(notes.size() - 1) + " elems=" + n2);
            L.add("     host gets PROBE (already loaded)  = " + tagOf(hostCL.loadClass(PROBE))
                    + "   [expect SHELL: an already-loaded class cannot be replaced]");
            L.add("     host gets PROBE2 (never loaded)   = " + tagOf(hostCL.loadClass(PROBE2))
                    + "   [expect PLUGIN-v2-2: an unloaded same-name class CAN be shadowed]");
            L.add("     host gets plugin entry class      = " + hostCL.loadClass(PluginHost.ENTRY).getName());
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

    private static void reject(Context ctx, List<String> L, File apk, String what) {
        L.add("  - " + apk.getName() + " (" + what + ")");
        try {
            PluginHost.load(ctx, apk);
            L.add("      !! LOADED ANYWAY - this gate is broken");
        } catch (PluginHost.PluginException e) {
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
        return (ctx.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
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
}
