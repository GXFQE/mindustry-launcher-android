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
        return stage(ctx, name, body, -1L);
    }

    /**
     * 同上，带**预期字节数**（`-1` = 未知）：写完后先核对长度，再按名字做 zip 结构校验。
     *
     * <p>★ 第 369 轮（用户：导出/分享一定要能用，别"提示成功实际是坏的"）：分享这一路
     * 也是**先校验、后给面板** —— 缓存里那份不完整/不是合法 zip 就删掉、报错，
     * 绝不让系统面板把一个坏文件发给别人。
     *
     * @param expected 预期字节数（文件拷贝传 {@code src.length()}；文本传字节长度；未知传 -1）
     */
    static Uri stage(Context ctx, String name, Body body, long expected) throws IOException {
        Work.begin(ctx, R.string.work_export);      // 第 177 轮：分享包可能是几十 MB
        try {
            return stage0(ctx, name, body, expected);
        } finally {
            Work.end(ctx);
        }
    }

    private static Uri stage0(Context ctx, String name, Body body, long expected)
            throws IOException {
        File dir = ShareProvider.dirOf(ctx);
        prune(dir, System.currentTimeMillis() - KEEP_MS);
        File f = unique(dir, fixName(name));
        FileOutputStream out = null;
        boolean ok = false;
        try {
            out = new FileOutputStream(f);
            body.write(out);
            out.flush();
            out.close();            // ★ 不吞：close 才把最后一段刷给文件系统
            out = null;
            // 第 369 轮：缓存里这份必须**先证明能用**，再给系统面板。
            //   zip（批量分享 / 分享包 / 资源包）⇒ 整包结构 + 逐条 CRC；
            //   其它（存档 / 地图 / 蓝图 / 文本）⇒ 非空 + 长度与预期一致。
            if (isZipName(f.getName())) ExportCheck.localZip(ctx, f);
            else ExportCheck.localFile(ctx, f, expected);
            ok = true;
        } finally {
            closeQuietly(out);
            // ★ 任何失败（含 Error）都不留半截文件 —— 交给接收方就是"坏文件"
            if (!ok) dropPartial(f);
        }
        Log.i(TAG, "share staged (verified): " + f.getName() + " " + f.length() + " B");
        return ShareProvider.uriOf(f.getName());
    }

    /** 半截文件比没有更糟：分享出去对面会当成完整文件 */
    private static void dropPartial(File f) {
        if (f != null && f.isFile() && !f.delete()) {
            Log.w(TAG, "share: cannot drop partial " + f.getName());
        }
    }

    /** 扩展名判 zip（只用于"要不要做结构校验"，不是内容判据） */
    static boolean isZipName(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".zip");
    }

    /** 落一份**文本**（统计导出那条路；UTF-8，不带 BOM） */
    static Uri stageText(Context ctx, String name, String text) throws IOException {
        final byte[] b = (text == null ? "" : text).getBytes("UTF-8");
        return stage(ctx, name, new Body() {
            @Override public void write(OutputStream out) throws IOException {
                out.write(b);
            }
        }, b.length);
    }

    /** 落一份**已有的文件**（地图/存档/蓝图都是"槽里已经有这个文件"） */
    static Uri stageCopy(Context ctx, String name, final File src) throws IOException {
        if (src == null || !src.isFile()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_src_missing_fmt, String.valueOf(src)));
        }
        long expect = src.length();
        Uri u = stage(ctx, name, new Body() {
            @Override public void write(OutputStream out) throws IOException {
                Exporter.copyTo(src, out);
            }
        }, expect);
        // 拷的时候源在变（游戏正在写）⇒ 缓存里那份不是稳定快照，别把它发出去
        File staged;
        try {
            staged = ShareProvider.fileOf(ctx, u.getLastPathSegment());
        } catch (java.io.FileNotFoundException e) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed), e);
        }
        if (staged.length() != expect) {
            dropPartial(staged);
            throw new IOException(Trans.get(ctx, R.string.exp_err_source_changed));
        }
        return u;
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

    /**
     * 第 174 轮：设置页「存储占用」里的手动清理 —— 清掉**全部**分享/统计临时缓存。
     *
     * <p>与 {@link #prune(Context)} 的区别：那个只清 24h 之前的，这个是用户主动要求
     * "现在就清"；正在被接收方读取的那份可能会失效（确认框里已经写明）。
     * 只动 {@code cache/share/} 这一层，不碰槽、不碰游戏数据。
     */
    static int clear(Context ctx) {
        return prune(ShareProvider.dirOf(ctx), Long.MAX_VALUE);
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
        // ★ 第 369 轮：给面板之前再核一眼缓存里那份（stage 已经深校验过；这里防"临时件被清掉/被换掉"）。
        //   过不了就**不发** —— 宁可报失败，也不让对面收到一个坏文件。
        try {
            File f = ShareProvider.fileOf(a, name);
            if (!f.isFile() || f.length() <= 0) {
                Toast.makeText(a, R.string.share_err_incomplete, Toast.LENGTH_LONG).show();
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "share launch: staged file missing for " + name, t);
            Toast.makeText(a, R.string.share_err_incomplete, Toast.LENGTH_LONG).show();
            return false;
        }
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
