package io.mdt.launcher;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 导出 / 分享产物的**写后校验**（唯一实现）。
 *
 * <h3>为什么要有它（用户 2026-11-12 的原话）</h3>
 * 「导出或分享东西时一定要保证可以用（不要出现损坏文件）」—— 原版 Mindustry 无论手机还是电脑
 * 都出过「提示成功、实际文件是坏的」这类问题，用户要求本工程的出口**不许**重演。
 * 所以「成功」不再等于「write() 没抛异常」，而是三层都过：
 * <ol>
 *   <li><b>本地完整件</b>：非空、长度与预期一致；zip 还必须能被 {@link ZipFile} 打开、
 *       每个条目都能**完整读出来**（读出即校验 CRC —— 截断包 / 缺中央目录 / 数据损坏都会抛）；</li>
 *   <li><b>交给系统之后</b>（SAF）：把目标 {@code content://} **读回来**与本地完整件逐字节比对
 *       —— 这一层专门抓 provider 截断、没 truncate 导致尾部留着旧内容、以及"写成功但只写了一部分"；</li>
 *   <li><b>失败处置</b>：任何一层不过 ⇒ 调用方把刚创建的目标删掉（{@link #deleteTarget}），
 *       把失败报给界面 —— **绝不在没验证过的情况下提示成功**。</li>
 * </ol>
 *
 * <h3>为什么读回比对值得多花一遍 IO</h3>
 * 存档几 MB、整槽几十 MB、模组几百 MB：多读一遍是秒级代价，而"用户以为导出成功、换机时才发现
 * 打不开"的代价是丢数据。这套校验对应的失败场景都真实存在（见 {@link Exporter} 类注释里
 * 第 177 轮 force-stop 事故：用户目录里留下 15 MB 的坏 zip）。
 *
 * <p>⚠️ 本类**只管传输完整性**，不解析业务格式：源文件本身坏（例如游戏正在写）由调用方用
 * 「源长度/时间在拷贝期间没变」这条判据兜（见 {@link Exporter#writeFile}）。
 */
final class ExportCheck {

    private static final String TAG = "MDTLauncher";
    private static final int BUF = 65536;

    private ExportCheck() {}

    // ── 本地完整件 ────────────────────────────────────────────────────────

    /**
     * 本地完整件必须存在且非空；`expected >= 0` 时长度还必须**恰好**是它。
     *
     * @throws IOException 文案走 {@code exp_err_verify_failed}（已经是给用户看的那一句）
     */
    static void localFile(Context ctx, File f, long expected) throws IOException {
        long n = f == null || !f.isFile() ? -1 : f.length();
        if (n <= 0 || (expected >= 0 && n != expected)) {
            Log.w(TAG, "local verify failed: " + f + " len=" + n + " expected=" + expected);
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed));
        }
    }

    /**
     * zip 必须是**能完整打开并读通**的：{@link ZipFile} 构造（检查中央目录）+
     * 逐条目读到 EOF 并**显式比对 CRC32**。
     *
     * <p>🔴 第 369 轮实测订正（selftest-scout 的受控实验）：`ZipFile.getInputStream()` 读到 EOF
     * **不会**校验 CRC（会校验的是 `ZipInputStream`）——只靠"能读完"抓不到"数据坏了但结构还在"。
     * 所以这里自己算 {@link java.util.zip.CRC32} 与条目头里的 {@code getCrc()} 比。
     *
     * <p>★ 允许 0 个条目：空槽导出的包结构上就是合法的（"没东西可导"是另一条判据，见
     * {@code exp_err_no_src}），别把空包误判成"坏包"。
     *
     * <p>⚠️ **不递归**验内层 zip：本工程写进外层的嵌套 zip（目录形态的模组）是自己用
     * {@link java.util.zip.ZipOutputStream} 写的，关闭/刷写异常会照常向上抛；外层 CRC 覆盖的
     * 就是内层 zip 的原始字节。而槽里本来就可能有用户放的、已经坏掉的第三方 zip ——
     * 那是"内容坏了"不是"我们传坏了"，不能因此拒绝整个导出。
     *
     * @return 条目数（目录条目也算）
     * @throws IOException 文案走 {@code exp_err_verify_failed}
     */
    static int localZip(Context ctx, File f) throws IOException {
        if (f == null || !f.isFile() || f.length() <= 0) {
            Log.w(TAG, "local zip missing/empty: " + f);
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed));
        }
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            int n = 0;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            byte[] buf = new byte[BUF];
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                n++;
                if (e.isDirectory()) continue;
                long want = e.getCrc();
                crc.reset();
                InputStream in = zf.getInputStream(e);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) crc.update(buf, 0, r);
                } catch (ZipException ze) {
                    Log.w(TAG, "local zip entry corrupt: " + e.getName(), ze);
                    throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed), ze);
                } finally {
                    closeQuietly(in);
                }
                if (want >= 0 && crc.getValue() != want) {
                    Log.w(TAG, "local zip entry CRC mismatch: " + e.getName()
                            + " got=" + crc.getValue() + " want=" + want);
                    throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed));
                }
            }
            return n;
        } catch (ZipException ze) {
            Log.w(TAG, "local zip unreadable: " + f, ze);
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed), ze);
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ── SAF 目标读回比对 ──────────────────────────────────────────────────

    /** 目标 URI 读回来，与本地完整件**逐字节相同**；读不回来/不一致都抛（调用方负责删目标）。 */
    static void targetMatches(Context ctx, Uri uri, File ref) throws IOException {
        FileInputStream fin = new FileInputStream(ref);
        try {
            targetMatches(ctx, uri, fin);
        } finally {
            closeQuietly(fin);
        }
    }

    /**
     * 目标 URI 读回来，与 `expected` 流逐字节相同。
     * ★ `expected` 由本方法关闭。
     */
    static void targetMatches(Context ctx, Uri uri, InputStream expected) throws IOException {
        InputStream got = null;
        try {
            got = openTarget(ctx, uri);
            byte[] a = new byte[BUF];
            byte[] b = new byte[BUF];
            while (true) {
                int na = fill(got, a);
                int nb = fill(expected, b);
                boolean same = na == nb;
                for (int i = 0; same && i < na; i++) {
                    if (a[i] != b[i]) same = false;
                }
                if (!same) {
                    Log.w(TAG, "target verify mismatch: " + uri + " got=" + na + " want=" + nb);
                    throw new IOException(Trans.get(ctx, R.string.exp_err_verify_failed));
                }
                if (na == 0) break;
            }
        } finally {
            closeQuietly(got);
            closeQuietly(expected);
        }
    }

    /**
     * 删掉**刚创建/刚打开过**的导出目标（best-effort）。
     *
     * <p>只用于"这次导出自己刚动过的目标"：SAF 的 {@code ACTION_CREATE_DOCUMENT} /
     * {@code createDocument} 都是**新建**文档，删它不会碰到用户原有的文件。
     * 删不掉只记日志 —— 第一层文案已经说了"文件已删/没导出"，不在这里再抛一次。
     */
    static void deleteTarget(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return;
        try {
            if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath() == null ? "" : uri.getPath());
                // 只删文件：万一有人传进来一个目录 URI，File.delete() 对**空目录**是成功的
                if (f.isFile() && !f.delete()) Log.w(TAG, "verify: cannot delete partial " + f);
                return;
            }
            if (android.provider.DocumentsContract.isDocumentUri(ctx, uri)) {
                if (!android.provider.DocumentsContract.deleteDocument(
                        ctx.getContentResolver(), uri)) {
                    Log.w(TAG, "verify: cannot delete partial doc " + uri);
                }
                return;
            }
            Log.w(TAG, "verify: not a deletable document uri: " + uri);
        } catch (Throwable t) {
            Log.w(TAG, "verify: delete target failed: " + uri, t);
        }
    }

    // ── 底层 ──────────────────────────────────────────────────────────────

    private static InputStream openTarget(Context ctx, Uri uri) throws IOException {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in != null) return in;
        } catch (Throwable t) {
            Log.w(TAG, "verify: open target failed: " + uri, t);
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_unreadable), t);
        }
        throw new IOException(Trans.get(ctx, R.string.exp_err_verify_unreadable));
    }

    /** 尽量把 `buf` 填满；返回读到的字节数（0 = EOF）。 */
    private static int fill(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
