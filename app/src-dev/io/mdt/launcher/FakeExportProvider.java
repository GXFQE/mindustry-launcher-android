package io.mdt.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 第 369 轮：**仅 dev 构建**的假 {@link ContentProvider}（`build.sh` 在 DEBUGGABLE=true 时
 * 才把它注入清单，产品包里只有一行占位注释；本类住在 `app/src-dev/`，产品构建不编译它）。
 *
 * <h3>为什么需要它</h3>
 * 「导出写后校验」的核心风险不在 `file://`（那只是 `FileOutputStream`），而在真实 provider 的
 * 四种行为：
 * <ol>
 *   <li><b>close 才提交</b>：写入经过 pipe，只有调用方 close 之后 provider 才真正落盘；</li>
 *   <li><b>不 truncate</b>：`"wt"` 不生效时，新内容比旧内容短 ⇒ 旧尾巴还在（文件"看起来写成功"）；</li>
 *   <li><b>返回 0 字节 / 丢一截</b>：close 之后目标只剩空文件或少最后几个字节；</li>
 *   <li><b>读回 fd 不支持 fsync</b>（pipe / 云盘）：内容可读，但"拔电源"这一层只能交给 provider。</li>
 * </ol>
 * 只用 `file://` 时这四类一个都测不到。这个类用真 URI + 真 `ContentResolver` 把它们造出来，
 * 于是自检能自动断言「校验必须判死 / 失败必须删（或按规则不删）/ fsync 失败不阻断但会被识别」。
 *
 * <h3>URI 形状</h3>
 * {@code content://io.mdt.launcher.devtest/<mode>/<id>}，内容落在
 * {@code cache/devtest/<id>.bin}。mode = {@link #MODE_OK} / {@link #MODE_DROP_TAIL} /
 * {@link #MODE_NO_TRUNCATE} / {@link #MODE_ZERO} / {@link #MODE_PIPE_READ}。
 *
 * <p>⚠️ 线程模型：write 侧返回 pipe 的写端，另起线程在读端收字节，读到 EOF（= 调用方 close）
 * 才提交，最后 {@code countDown} latch；read/query 侧等 latch，避免"还没提交就去读"的假失败。
 * 每次 open 都新建 pipe，所以同一个 id 可以反复读写。
 */
public final class FakeExportProvider extends ContentProvider {

    private static final String TAG = "MDTLauncher";

    static final String AUTHORITY = "io.mdt.launcher.devtest";

    /** 正常提交（对照/元断言用）。 */
    static final String MODE_OK = "ok";
    /** close 时故意少写最后 10 字节（"返回一截"）。 */
    static final String MODE_DROP_TAIL = "drop-tail";
    /** 不截断：从 offset 0 覆盖，但旧文件更长时尾巴保留（"wt 没生效"）。 */
    static final String MODE_NO_TRUNCATE = "no-truncate";
    /** close 之后什么都不提交（0 字节）。 */
    static final String MODE_ZERO = "zero";
    /** 提交正常，但读回给的是 pipe（fsync 不支持）。 */
    static final String MODE_PIPE_READ = "pipe-read";
    /**
     * 提交正常、`openInputStream` 读得回来，但 `openAssetFile` 返回**带声明长度的 slice**
     * ⇒ `ContentResolver.openFileDescriptor` 直接抛 "Not a whole file"（拿不到整文件 fd）。
     * 用来钉「fsync 降级、不判导出失败」。
     */
    static final String MODE_SLICED_READ = "sliced-read";

    /** read/query 等提交的上限；自检里写入都是几百字节，5 s 足够。 */
    private static final long WAIT_MS = 5000;

    /** id → 提交完成 latch（static：provider 实例可能被系统回收重建）。 */
    private static final Map<String, CountDownLatch> COMMITTED = new HashMap<>();

    /** 已删除的 id（query 查不到；与真 provider 一致，避免二次删除时打得像"还有个半截文件"）。 */
    private static final java.util.Set<String> DELETED = new java.util.HashSet<>();

    // ── 自检用的夹具入口 ─────────────────────────────────────────────────

    static Uri uri(String mode, String id) {
        return Uri.parse("content://" + AUTHORITY + "/" + mode + "/" + id);
    }

    static File dirOf(Context ctx) {
        File d = new File(ctx.getCacheDir(), "devtest");
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            Log.w(TAG, "devtest dir not writable: " + d);
        }
        return d;
    }

    static File backing(Context ctx, String id) {
        return new File(dirOf(ctx), id + ".bin");
    }

    /** 夹具：先塞一份"更长"的旧内容（用于 no-truncate / 覆盖保护）。 */
    static void seed(Context ctx, String id, byte[] data) throws IOException {
        File f = backing(ctx, id);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        synchronized (FakeExportProvider.class) {
            DELETED.remove(id);
        }
        latch(id).countDown();      // 已存在 = 已"提交"，读侧不必等
    }

    /** 清掉全部夹具（自检开始/结束各一次）。 */
    static void reset(Context ctx) {
        synchronized (FakeExportProvider.class) {
            COMMITTED.clear();
            DELETED.clear();
        }
        deleteTree(dirOf(ctx));
    }

    private static synchronized CountDownLatch latch(String id) {
        CountDownLatch l = COMMITTED.get(id);
        if (l == null) {
            l = new CountDownLatch(1);
            COMMITTED.put(id, l);
        }
        return l;
    }

    /** 等这个 id 的写端提交完（EOF 之后 provider 才落盘）。 */
    private static void waitCommitted(String id) {
        try {
            latch(id).await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    // ── ContentProvider ───────────────────────────────────────────────────

    @Override public boolean onCreate() {
        return true;
    }

    /** `/mode/id` → {mode, id}；不合法返回 null。 */
    private static String[] split(Uri uri) {
        if (uri == null) return null;
        List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() < 2) return null;
        return new String[]{seg.get(0), seg.get(1)};
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        String[] p = split(uri);
        if (p == null) throw new java.io.FileNotFoundException("bad uri: " + uri);
        if (mode != null && mode.indexOf('w') >= 0) return openWrite(p[0], p[1]);
        return openRead(p[0], p[1]);
    }

    private ParcelFileDescriptor openWrite(final String m, final String id)
            throws java.io.FileNotFoundException {
        final Context c = getContext();
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final CountDownLatch l = latch(id);
            new Thread(new Runnable() {
                @Override public void run() {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        commit(c, m, id, bo.toByteArray());
                    } catch (Throwable t) {
                        Log.w(TAG, "devtest commit failed: " + id, t);
                    } finally {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                        l.countDown();
                    }
                }
            }, "devtest-commit-" + id).start();
            return pipe[1];
        } catch (IOException e) {
            throw new java.io.FileNotFoundException("pipe: " + e);
        }
    }

    /** 按 mode 提交（在 pipe 读线程里跑；EOF = 调用方 close）。 */
    private static void commit(Context c, String m, String id, byte[] data) throws IOException {
        if (MODE_ZERO.equals(m)) {
            data = new byte[0];
        } else if (MODE_DROP_TAIL.equals(m)) {
            int keep = Math.max(0, data.length - 10);
            byte[] cut = new byte[keep];
            System.arraycopy(data, 0, cut, 0, keep);
            data = cut;
        }
        File f = backing(c, id);
        if (MODE_NO_TRUNCATE.equals(m)) {
            // 模拟"openOutputStream('wt') 不生效、默认模式也不截断"：从头覆盖但不 truncate
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            try {
                raf.seek(0);
                raf.write(data);
            } finally {
                raf.close();
            }
        } else {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
        }
        synchronized (FakeExportProvider.class) {
            DELETED.remove(id);
        }
    }

    /**
     * `sliced-read` 专用：返回**带声明长度**的 {@link android.content.res.AssetFileDescriptor}
     * ⇒ `openInputStream` 能读（LimitedInputStream），而 `ContentResolver.openFileDescriptor`
     * 会抛 `FileNotFoundException("Not a whole file")`。别的 mode 走默认实现。
     */
    @Override public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        String[] p = split(uri);
        if (p != null && MODE_SLICED_READ.equals(p[0])
                && (mode == null || mode.indexOf('w') < 0)) {
            waitCommitted(p[1]);
            File f = backing(getContext(), p[1]);
            if (!f.isFile()) throw new java.io.FileNotFoundException("no committed file: " + p[1]);
            try {
                return new android.content.res.AssetFileDescriptor(
                        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY),
                        0, f.length());
            } catch (IOException e) {
                throw new java.io.FileNotFoundException("sliced: " + e);
            }
        }
        return super.openAssetFile(uri, mode);
    }

    private ParcelFileDescriptor openRead(final String m, final String id)
            throws java.io.FileNotFoundException {
        waitCommitted(id);
        final File f = backing(getContext(), id);
        if (!f.isFile()) throw new java.io.FileNotFoundException("no committed file: " + id);
        if (MODE_PIPE_READ.equals(m)) {
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(new Runnable() {
                    @Override public void run() {
                        InputStream in = null;
                        OutputStream out = null;
                        try {
                            in = new FileInputStream(f);
                            out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } catch (Throwable t) {
                            Log.w(TAG, "devtest pipe read failed: " + id, t);
                        } finally {
                            try {
                                if (in != null) in.close();
                            } catch (Throwable ignored) {
                            }
                            try {
                                if (out != null) out.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }, "devtest-read-" + id).start();
                return pipe[0];
            } catch (IOException e) {
                throw new java.io.FileNotFoundException("pipe: " + e);
            }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] p = split(uri);
        if (p == null) return null;
        // 已删除的 id 查不到（与真 provider 一致）；否则给一行 SIZE（没写过的 = 0，
        // 这样"写入前 SIZE==0 ⇒ 本次新建"的判据在假 provider 上也成立）。
        synchronized (FakeExportProvider.class) {
            if (DELETED.contains(p[1])) return null;
        }
        String[] cols = (projection == null || projection.length == 0)
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        File f = backing(getContext(), p[1]);
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = p[1] + ".bin";
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.isFile() ? f.length() : 0L;
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        String[] p = split(uri);
        if (p == null) return 0;
        File f = backing(getContext(), p[1]);
        boolean ok = f.isFile() && f.delete();
        if (ok) {
            synchronized (FakeExportProvider.class) {
                COMMITTED.remove(p[1]);
                DELETED.add(p[1]);
            }
        }
        return ok ? 1 : 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) Log.w(TAG, "devtest: cannot delete " + f);
    }
}
