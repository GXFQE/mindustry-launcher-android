package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PickCallback;

/**
 * 插件接口 v7 里**"做事"那几条**的唯一实现（第 187 轮）。
 *
 * <p>覆盖 {@code share / shareText / export / pick} —— 它们都要一个真实的 {@link Activity}
 * （系统分享面板、SAF 结果、模态对话框），所以单独成一处，{@link PluginHost} 只负责转发。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>流归宿主关</b>：插件交进来的 {@link InputStream} 由这里在 finally 里关掉，
 *       插件不用（也不该）再动它。</li>
 *   <li><b>重活不上主线程</b>：落缓存 / 拷字节 / 扫描都在后台线程，只有"弹面板 / 弹对话框 /
 *       Toast"回主线程 —— 与全工程同一条规矩。</li>
 *   <li><b>失败要说出来</b>：分享 / 保存失败一律 Toast（原因来自 {@link Util#ioReason}），
 *       不许静默 —— 用户点的是"发出去"，没动静会被当成成功。</li>
 * </ol>
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）；给用户看的句子全在
 * {@code res/values/strings-plugin.xml} / {@code values-zh}。
 */
final class PluginActions {

    /** SAF 保存的请求码（{@link PluginsActivity#onActivityResult} 先问这一条）。 */
    static final int REQ_EXPORT = 0x9B10;

    private static final String TAG = "MDTLauncher";
    private static final Object LOCK = new Object();
    private static final int BUF = 65536;

    /** 正在等用户选落点的那一份（同一时刻只允许一份，否则请求码 / 临时文件会串）。 */
    private static Pending sPending;

    static final class Pending {
        final File tmp;
        final String name;
        final String mime;
        /** v11：保存结束后回调插件（可为 null）。 */
        final io.mdt.launcher.api.PluginApi.After after;

        Pending(File tmp, String name, String mime, io.mdt.launcher.api.PluginApi.After after) {
            this.tmp = tmp;
            this.name = name;
            this.mime = mime;
            this.after = after;
        }
    }

    private PluginActions() { }

    // ── 分享 ──────────────────────────────────────────────────────────────

    /** 插件给的一份流 ⇒ 系统分享面板。后台落缓存、主线程起面板。 */
    static void share(final Activity a, final String name, final InputStream content) {
        if (a == null || content == null) {
            closeQuietly(content);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                Uri uri = null;
                String err = null;
                try {
                    uri = ShareOut.stage(a, name, new ShareOut.Body() {
                        @Override public void write(OutputStream out) throws IOException {
                            copy(content, out);
                        }
                    });
                } catch (Throwable t) {
                    err = Util.ioReason(a, t);
                } finally {
                    closeQuietly(content);
                }
                final Uri u = uri;
                final String e = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (u == null) {
                            toast(a, Trans.get(a, R.string.plugin_share_failed_fmt, e));
                            return;
                        }
                        ShareOut.launch(a, u);
                    }
                });
            }
        }, "mdt-plugin-share").start();
    }

    /** 一段文本 ⇒ 系统分享面板（报告类结果的常规出口）。 */
    static void shareText(final Activity a, final String name, final String text) {
        if (a == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Uri uri = null;
                String err = null;
                try {
                    uri = ShareOut.stageText(a, name, text);
                } catch (Throwable t) {
                    err = Util.ioReason(a, t);
                }
                final Uri u = uri;
                final String e = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (u == null) {
                            toast(a, Trans.get(a, R.string.plugin_share_failed_fmt, e));
                            return;
                        }
                        ShareOut.launch(a, u);
                    }
                });
            }
        }, "mdt-plugin-share").start();
    }

    // ── 保存（SAF）─────────────────────────────────────────────────────────

    /**
     * 先把流落到缓存里的临时文件（后台），再让用户选落点；结果回来时才真正写出去。
     *
     * <p>⚠️ 为什么不直接 {@code startActivityForResult} 再写：SAF 的结果要等用户操作，
     * 而这期间插件给的那条流不能一直挂着 —— 先收下字节，落点回来再拷。
     */
    static void export(final Activity a, final String name, final String mime,
                       final InputStream content) {
        export(a, name, mime, content, null);
    }

    /** v11：带"保存结束"回调（成功 / 取消都会回调）。 */
    static void export(final Activity a, final String name, final String mime,
                       final InputStream content,
                       final io.mdt.launcher.api.PluginApi.After after) {
        if (a == null || content == null) {
            closeQuietly(content);
            return;
        }
        synchronized (LOCK) {
            if (sPending != null) {
                closeQuietly(content);
                toast(a, Trans.get(a, R.string.plugin_export_busy));
                return;
            }
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final File tmp = new File(a.getCacheDir(),
                        "plugin-export-" + System.currentTimeMillis() + ".bin");
                String err = null;
                try {
                    OutputStream out = new FileOutputStream(tmp);
                    try {
                        copy(content, out);
                    } finally {
                        closeQuietly(out);
                    }
                } catch (Throwable t) {
                    err = Util.ioReason(a, t);
                    if (tmp.isFile()) tmp.delete();
                } finally {
                    closeQuietly(content);
                }
                final String e = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (e != null) {
                            toast(a, Trans.get(a, R.string.plugin_export_failed_fmt, e));
                            return;
                        }
                        if (a.isFinishing()) {
                            tmp.delete();
                            if (after != null) after.onAfter(false);
                            return;
                        }
                        Pending p = new Pending(tmp, ShareOut.fixName(name), mimeOf(mime, name),
                                after);
                        synchronized (LOCK) {
                            if (sPending != null) {
                                tmp.delete();
                                toast(a, Trans.get(a, R.string.plugin_export_busy));
                                return;
                            }
                            sPending = p;
                        }
                        try {
                            a.startActivityForResult(
                                    Exporter.createDoc(p.name, p.mime), REQ_EXPORT);
                        } catch (Throwable t) {
                            synchronized (LOCK) { sPending = null; }
                            tmp.delete();
                            toast(a, Trans.get(a, R.string.plugin_export_failed_fmt,
                                    Util.ioReason(a, t)));
                        }
                    }
                });
            }
        }, "mdt-plugin-export").start();
    }

    /**
     * 本 Activity 的 {@code onActivityResult} 先问这里；认领了（返回 true）就别再往下走。
     *
     * <p>用户取消 ⇒ 静默删临时件（取消不是错误，不 Toast）。
     */
    static boolean onActivityResult(final Activity a, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) return false;
        final Pending p;
        synchronized (LOCK) {
            p = sPending;
            sPending = null;
        }
        if (p == null) return true;
        android.util.Log.i(TAG, "export result uri=" + (data == null ? null : data.getData())
                + " tmp=" + p.tmp.length());
        final Uri uri = (resultCode == Activity.RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            p.tmp.delete();
            if (p.after != null) p.after.onAfter(false);
            return true;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Exporter.writeFile(a, uri, p.tmp);
                } catch (Throwable t) {
                    err = Util.ioReason(a, t);
                } finally {
                    p.tmp.delete();
                }
                final String e = err;
                android.util.Log.i(TAG, "export write err=" + e);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(a, e == null
                                ? Trans.get(a, R.string.plugin_export_done)
                                : Trans.get(a, R.string.plugin_export_failed_fmt, e));
                        if (p.after != null) p.after.onAfter(e == null);
                    }
                });
            }
        }, "mdt-plugin-export").start();
        return true;
    }

    // ── 勾选 ──────────────────────────────────────────────────────────────

    /**
     * 弹宿主那套多选框，把勾中的条目回调给插件。
     *
     * <p>取数在后台（扫描会读文件头 / 开包），弹窗与回调在主线程；取消 / 空表都回调空表。
     */
    static void pick(final Activity a, final Context ctx, final String slot, final String kind,
                     final PickCallback callback) {
        if (a == null) {
            if (callback != null) callback.onPicked(new ArrayList<ItemInfo>());
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ItemInfo> items = PluginData.pickItems(ctx, slot, kind);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (items.isEmpty()) {
                            toast(a, Trans.get(a, R.string.plugin_pick_empty));
                            if (callback != null) callback.onPicked(new ArrayList<ItemInfo>());
                            return;
                        }
                        if (a.isFinishing()) {
                            if (callback != null) callback.onPicked(new ArrayList<ItemInfo>());
                            return;
                        }
                        String[] titles = new String[items.size()];
                        String[] subs = new String[items.size()];
                        for (int i = 0; i < items.size(); i++) {
                            ItemInfo it = items.get(i);
                            titles[i] = it == null ? "" : it.title;
                            subs[i] = it == null ? "" : it.subtitle;
                        }
                        BatchIo.pickMulti(a, R.string.plugin_pick_title,
                                Trans.get(a, R.string.plugin_pick_head), titles, subs, null, null,
                                R.string.plugin_pick_ok_fmt, new BatchIo.OnPick() {
                                    @Override public void onPick(List<Integer> indexes) {
                                        List<ItemInfo> out = new ArrayList<ItemInfo>();
                                        if (indexes != null) {
                                            for (Integer i : indexes) {
                                                if (i != null && i >= 0 && i < items.size()) {
                                                    out.add(items.get(i));
                                                }
                                            }
                                        }
                                        if (callback != null) callback.onPicked(out);
                                    }
                                });
                    }
                });
            }
        }, "mdt-plugin-pick").start();
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /** MIME 兜底：调用方给了就用，没给按扩展名推（认不出 ⇒ octet-stream）。 */
    static String mimeOf(String mime, String name) {
        if (mime != null && !mime.trim().isEmpty()) return mime.trim();
        return ShareProvider.mimeOf(name);
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) { }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static void toast(final Activity a, final String msg) {
        if (a == null) return;
        // ★ 插件可能从后台线程调用（比如导出排队时）⇒ 一律回主线程再弹，
        //   否则 "Can't toast on a thread that has not called Looper.prepare()"。
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.w(TAG, "plugin action toast failed: " + t);
                }
            }
        });
    }
}
