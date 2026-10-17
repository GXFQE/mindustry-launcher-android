package io.mdt.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 分享出口的**唯一实现**（第 155 轮起，第 156 轮扩到全部导出）：把一份内容落到
 * `cache/share/<名字>`，再把 {@link ShareProvider} 供的那个 `content://` 交给系统分享面板。
 *
 * <h3>为什么是"落缓存再借 URI"</h3>
 * 导出原本只有一条路：{@link Exporter#createDoc} 拿用户选的 SAF 写句柄，写完就撒手
 * —— 我们**不持有任何能被别的 App 读的 URI**，而 {@code ACTION_SEND} 需要一个。
 * 缓存这一份是"给别人的一份"：**不进用户目录**、不覆盖任何原件、24h 后自己消失。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>写之前先清 24h 前的</b>（{@link #prune}）—— 但不能清刚写完的那份：接收方是
 *       **异步读**的（微信先把消息发出去、文件稍后才上传），立刻删 = 对面收到一个 0 字节。</li>
 *   <li><b>同名不覆盖</b>：`a.msav` 已经在了就写 `a (2).msav` —— 否则"连发两张同名的图"
 *       会让第一份在对面读之前被第二份换掉（那是个**没有症状**的错，只能靠文件名判据防）。</li>
 *   <li><b>名字不合法就洗，不拒</b>：地图/存档的名字来自用户的文件（可能带 `:`、`?`、换行），
 *       拒掉等于"这张图永远分享不出去"。{@link ShareProvider#safeName} 那条判据仍然守着
 *       真正的边界（provider 读的时候还会再验一次）。</li>
 * </ol>
 *
 * <p>⚠️ 失败**不许**悄悄退回"保存到文件"：用户点的是分享，界面要明说失败原因（调用方用
 * {@link Util#ioReason} 那句话）。</p>
 */
final class ShareOut {

    private static final String TAG = "MDTLauncher";

    /** 缓存留多久：24h（够任何接收方读完；再久就是占地方） */
    static final long KEEP_MS = 24L * 3600L * 1000L;

    /** 名字上限（与 {@link ShareProvider#safeName} 同一条线，留一点余量给 `-2` 后缀） */
    private static final int MAX_NAME = 100;

    private ShareOut() {
    }

    // ── 名字 ──────────────────────────────────────────────────────────────

    /**
     * 把"用户给的名字"洗成**一个平铺文件名**：路径分隔符/控制字符/Windows 敏感字符换成 `_`，
     * 保留扩展名地截短，空名兜底成 `file`。
     *
     * ★ 与 {@link ShareProvider#safeName}（**判据**）分工：这个是**生产侧**的清洗，
     *   那个是**读取侧**的边界；两边都要有，缺一个就会出现"能写进去、读不出来"。
     */
    static String fixName(String raw) {
        String n = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean bad = c < 0x20 || c == 0x7f
                    || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|';
            sb.append(bad ? '_' : c);
        }
        n = sb.toString().trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) n = "file";
        if (n.length() > MAX_NAME) {
            int dot = n.lastIndexOf('.');
            String ext = (dot > 0 && n.length() - dot <= 12) ? n.substring(dot) : "";
            int keep = MAX_NAME - ext.length();
            if (keep < 1) keep = 1;
            n = n.substring(0, keep) + ext;
        }
        return n;
    }

    /** 让同名文件不撞：`a.msav` → `a (2).msav`（最多试 99 次，之后加时间戳兜底） */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 100; i++) {
            File g = new File(dir, base + " (" + i + ")" + ext);
            if (!g.exists()) return g;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    // ── 写入 ──────────────────────────────────────────────────────────────

    /** 供内容主体用的"往输出流里写"的函数（{@link Exporter} 那些流式写全是这个形状） */
    interface Body {
        void write(OutputStream out) throws IOException;
    }

    /**
     * 把一份内容写进 `cache/share/`，返回可分享的 URI。
     *
     * @param name 建议文件名（会被 {@link #fixName} 洗；撞名会自动加序号）
     * @throws IOException 写失败（半个文件**会被删掉**，不留垃圾）
     */
    static Uri stage(Context ctx, String name, Body body) throws IOException {
        File dir = ShareProvider.dirOf(ctx);
        prune(dir, System.currentTimeMillis() - KEEP_MS);
        File f = unique(dir, fixName(name));
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            body.write(out);
            out.flush();
        } catch (IOException e) {
            closeQuietly(out);
            out = null;
            // 半截文件比没有更糟：分享出去对面会当成完整文件
            if (f.isFile() && !f.delete()) Log.w(TAG, "share: cannot drop partial " + f.getName());
            throw e;
        } finally {
            closeQuietly(out);
        }
        Log.i(TAG, "share staged: " + f.getName() + " " + f.length() + " B");
        return ShareProvider.uriOf(f.getName());
    }

    /** 落一份**文本**（统计导出那条路；UTF-8，不带 BOM） */
    static Uri stageText(Context ctx, String name, String text) throws IOException {
        final byte[] b = (text == null ? "" : text).getBytes("UTF-8");
        return stage(ctx, name, new Body() {
            @Override public void write(OutputStream out) throws IOException {
                out.write(b);
            }
        });
    }

    /** 落一份**已有的文件**（地图/存档/蓝图都是"槽里已经有这个文件"） */
    static Uri stageCopy(Context ctx, String name, final File src) throws IOException {
        if (src == null || !src.isFile()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_src_missing_fmt, String.valueOf(src)));
        }
        return stage(ctx, name, new Body() {
            @Override public void write(OutputStream out) throws IOException {
                Exporter.copyTo(src, out);
            }
        });
    }

    // ── 清理 ──────────────────────────────────────────────────────────────

    /** 清掉 `dir` 里 `olderThan` 之前写的那几份；返回删掉的个数（自检直接喂一个目录） */
    static int prune(File dir, long olderThan) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles(new FilenameFilter() {
            @Override public boolean accept(File d, String n) {
                return true;
            }
        });
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isFile() && f.lastModified() < olderThan) {
                if (f.delete()) n++;
            }
        }
        if (n > 0) Log.i(TAG, "share prune: " + n);
        return n;
    }

    static int prune(Context ctx) {
        return prune(ShareProvider.dirOf(ctx), System.currentTimeMillis() - KEEP_MS);
    }

    // ── 交给系统 ──────────────────────────────────────────────────────────

    /**
     * 打开系统分享面板。**唯一**的"发出去"入口（四个内容页 + 分享包共用）。
     *
     * ⚠️ `EXTRA_STREAM` 之外还要 `ClipData` + 读授权：部分版本上 `EXTRA_STREAM` 不会被自动
     *    搬进 ClipData，授权就跟着丢 —— 接收方打开时 `SecurityException`（§89.7 那条同族）。
     */
    static boolean launch(Activity a, Uri uri) {
        if (uri == null) return false;
        String name = uri.getLastPathSegment();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(ShareProvider.mimeOf(name));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send,
                    Trans.get(a, R.string.share_chooser)));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "share launch failed", t);
            Toast.makeText(a, R.string.share_no_app, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
