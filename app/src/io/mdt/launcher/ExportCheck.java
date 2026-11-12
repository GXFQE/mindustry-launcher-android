package io.mdt.launcher;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 导出 / 分享产物的**写后校验**（唯一实现）。
 *
 * <h3>为什么要有它（用户 2026-11-12 的原话）</h3>
 * 「导出或分享东西时一定要保证可以用（不要出现损坏文件）」—— 原版 Mindustry 无论手机还是电脑
 * 都出过「提示成功、实际文件是坏的」这类问题，用户要求本工程的出口**不许**重演。
 * 所以「成功」不再等于「write() 没抛异常」，而是四层都过：
 * <ol>
 *   <li><b>本地完整件</b>：非空、长度与预期一致；zip 还必须能被 {@link ZipFile} 打开、
 *       每个条目都能**完整读出来并比对 CRC32** —— 截断包 / 缺中央目录 / 数据损坏都会抛；</li>
 *   <li><b>交给系统之后</b>（SAF）：把目标 {@code content://} **读回来**与本地完整件逐字节比对
 *       —— 这一层专门抓 provider 截断、没 truncate 导致尾部留着旧内容、以及"写成功但只写了一部分"；</li>
 *   <li><b>刷盘（fsync）</b>：{@link #syncToDisk} 打开目标的底层 fd 调 {@code fsync(2)}，
 *       把"读回来能看到"推进到"已经提交到存储"（拔电源 / 掉电那一层）。个别 provider 的 fd 是
 *       pipe（云盘 / 代理），fsync 不支持 ⇒ 返回 false，只记日志、不阻断导出（内容本身已验过）；</li>
 *   <li><b>失败处置</b>：任何一层不过 ⇒ 删掉**本次新建**的目标并报失败。
 *       🔴 不能无条件删：MT 管理器 / Solid Explorer 这类第三方 provider 允许"覆盖同名文件"，
 *       覆盖时那个文档是用户原有的 —— 所以写入前用 {@link #existingSize} 记下 SIZE，
 *       原本非空（或拿不到 SIZE）就**不删**，只报告失败（{@link #deleteIfNew}）。</li>
 * </ol>
 *
 * <h3>为什么读回比对值得多花一遍 IO</h3>
 * 存档几 MB、整槽几十 MB、模组几百 MB：多读一遍是秒级代价，而"用户以为导出成功、换机时才发现
 * 打不开"的代价是丢数据。这套校验对应的失败场景都真实存在（见 {@link Exporter} 类注释里
 * 第 177 轮 force-stop 事故：用户目录里留下 15 MB 的坏 zip）。
 *
 * <p>⚠️ 本类**只管传输完整性**，不解析业务格式：源文件本身坏（例如游戏正在写）由调用方用
 * 「源长度在拷贝期间没变」这条判据兜（见 {@link Exporter#writeFile}）。
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
            CRC32 crc = new CRC32();
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

    // ── 写入前：目标是不是"原本就有东西"（决定失败时敢不敢删）────────────

    /**
     * 目标文档在**写入之前**的字节数。
     *
     * <p>用途：{@link #deleteIfNew} 的判据。第三方 provider（MT 管理器 / Solid Explorer 等）
     * 允许"保存时覆盖同名文件"，那种情况下这个文档是**用户原有的** —— 校验失败时删掉它
     * 就是"低概率、高损失"的错误。
     *
     * @return ≥0 = 已知大小（新建文档通常是 0）；-1 = 拿不到（provider 不报 SIZE）
     */
    static long existingSize(Context ctx, Uri uri) {
        if (uri == null) return -1;
        try {
            if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath() == null ? "" : uri.getPath());
                return f.isFile() ? f.length() : 0L;     // 不存在 = 本次要新建的，0
            }
        } catch (Throwable t) {
            Log.w(TAG, "pre-write size (file) failed: " + uri, t);
            return -1;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pre-write size query failed: " + uri, t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return -1;
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

    // ── 刷盘（掉电那一层）────────────────────────────────────────────────

    /**
     * 尽力把目标**刷到存储**：打开目标的底层 fd 调 {@code FileDescriptor.sync()}（= fsync(2)）。
     *
     * <p>为什么只靠读回不够：读回只证明"provider 愿意把这份内容给你看"，不证明它已从页缓存
     * 提交到闪存。fsync 之后才真正能说"已落盘"。
     *
     * <p>为什么在这里用**读** fd 而不用写 fd：写那条路走的是 {@code openOutputStream}
     * （内部是 `ParcelFileDescriptor.AutoCloseOutputStream`），我们拿不到它的 fd；自己用
     * `openFileDescriptor(uri,"wt")` 再包一层 `FileOutputStream` 的话，流的 close 与 PFD 的
     * close 会各自关同一个 fd（fd 复用风险）。fsync(2) 不要求写权限，对同一个文件的读 fd
     * 调 fsync 就会把该 inode 的脏页刷下去 —— 效果相同且没有 double-close。
     *
     * @return true = 已 fsync；false = provider 的 fd 不支持 fsync（pipe / 云盘），
     *         内容已通过读回校验，但掉电持久性只能交给 provider
     * @throws IOException 连 fd 都拿不到（这时读回校验也一定会失败，调用方会删目标 + 报错）
     */
    static boolean syncToDisk(Context ctx, Uri uri) throws IOException {
        if (uri == null) throw new IOException(Trans.get(ctx, R.string.exp_err_no_dest));
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        } catch (java.io.FileNotFoundException e) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_unreadable), e);
        } catch (Throwable t) {
            Log.w(TAG, "fsync: open fd failed: " + uri, t);
            throw new IOException(Trans.get(ctx, R.string.exp_err_verify_unreadable), t);
        }
        if (pfd == null) throw new IOException(Trans.get(ctx, R.string.exp_err_verify_unreadable));
        try {
            pfd.getFileDescriptor().sync();
            return true;
        } catch (IOException e) {
            // pipe / 代理 fd：fsync 返回 EINVAL。这是 provider 的能力边界，不是内容有问题。
            Log.w(TAG, "fsync not supported by provider fd (pipe?); "
                    + "content verified but power-loss durability unknown: " + uri, e);
            return false;
        } finally {
            closeQuietly(pfd);
        }
    }

    // ── 失败处置 ──────────────────────────────────────────────────────────

    /**
     * 删除**本次新建**的目标：只在"写入前是空的/不存在"时才删。
     *
     * <p>🔴 为什么不能无条件删：`ACTION_CREATE_DOCUMENT` 在第三方 provider 上允许覆盖同名
     * 文件 —— 覆盖时这份文档是用户原有的，校验失败后 `deleteDocument` 删掉它损失更大。
     * 判据就是写入前记下的 {@link #existingSize}：只有 **0** 才敢删；非空或拿不到 SIZE
     * 都保留原文件（只报告失败，见 {@link Exporter} 的失败文案）。
     */
    static void deleteIfNew(Context ctx, Uri uri, long preSize) {
        if (ctx == null || uri == null) return;
        if (preSize != 0) {
            Log.w(TAG, "verify failed; target was not created by this export (preSize="
                    + preSize + "), leaving it in place: " + uri);
            return;
        }
        deleteForced(ctx, uri);
    }

    /**
     * 无条件删目标（只给"确定是本次新建"的场合：自检夹具、我们自己的缓存文件）。
     *
     * <p>删不掉只记日志 —— 第一层文案已经说了"文件已删/没导出"，不在这里再抛一次。
     */
    static void deleteForced(Context ctx, Uri uri) {
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
            // 非 DocumentsProvider 的 content URI（MediaStore / 自检夹具）：
            // 退回 ContentResolver.delete。只在"本次新建"（preSize==0）时才会走到这里，
            // 删的是这次导出刚创建的那一行；拿掉它会让校验失败时目标里留半截。
            int n = ctx.getContentResolver().delete(uri, null, null);
            if (n <= 0) Log.w(TAG, "verify: cannot delete partial non-document uri " + uri);
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
            if (n == 0) break;      // 防御：某些异常流会返回 0，继续循环就是死等
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
