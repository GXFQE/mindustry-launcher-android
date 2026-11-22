package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 从对象池把某个版本重新拼回一个可启动的 APK（P3「关掉去重 = 先把包拼回来」那一步）。
 *
 * <p>为什么必须能拼回来：开启去重时原始 APK 被删了，池子里只有条目。用户关掉开关、
 * 或者以后要「导出/迁移」，手上得有一个真正的 APK 文件 —— 否则等于把包弄丢了。</p>
 *
 * <h3>拼出来的包和原件不一样，是设计如此</h3>
 * <ul>
 *   <li><b>不保留 META-INF/</b>：清单里本来就不收它（签名只在"安装"时有意义，
 *       我们是动态加载，ART 不校验签名）。所以拼出来的包是**未签名**的，
 *       字节与原件不同、{@code apkSha256} 也不同。</li>
 *   <li><b>全部 STORED + 4 字节对齐</b>：原件里哪些条目压缩过没记，而
 *       `AssetManager.openFd` 只接受**未压缩且数据 4 字节对齐**的条目（游戏里
 *       图集走 openFd + mmap，压缩了直接抛）。所以统一按 STORED 写，并用
 *       0xFFFF 私有 extra 字段把数据起点垫到 4 字节边界。</li>
 *   <li><b>逐条校验 sha256</b>：写的时候顺手算，跟清单对不上就整体失败
 *       （池子坏了宁可报错，也不产出一个"看起来能启动"的坏包）。</li>
 * </ul>
 *
 * <p>调用方：{@link PkgAdmin#disable(Context)}（先拼回、全成功才删池）。</p>
 */
final class PkgRestore {
    private static final String TAG = "MDTLauncher";
    private static final int BUF = 65536;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private PkgRestore() {
    }

    /**
     * 把清单对应的版本拼回 `import/<file>`。
     *
     * @return 拼出来的文件；池里缺对象 / sha 不符 / 写失败 ⇒ null（调用方据此中止）
     */
    static File rebuild(Context ctx, PkgManifest m) {
        if (m == null || m.file == null || m.file.isEmpty() || m.entries.isEmpty()) {
            Log.w(TAG, "pkg restore: bad manifest (" + (m == null ? "null" : m.vkey) + ")");
            return null;
        }
        File dir = Importer.importDir(ctx);
        if (dir == null) {
            Log.w(TAG, "pkg restore: no import dir");
            return null;
        }
        // 没有 import 目录就先建（用户可能手动清过）
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "pkg restore: cannot create " + dir);
            return null;
        }
        File dst = new File(dir, m.file);
        File part = new File(dir, m.file + ".part");
        part.delete();
        Pkgs pool = Pkgs.get(ctx);
        long t0 = System.currentTimeMillis();
        int done = 0;
        try {
            OutputStream fos = new BufferedOutputStream(new FileOutputStream(part), BUF);
            ZipOutputStream zos = new ZipOutputStream(fos);
            zos.setMethod(ZipOutputStream.STORED);
            byte[] buf = new byte[BUF];
            for (PkgManifest.Entry e : m.entries) {
                File obj = pool.object(e.sha);
                if (obj == null || !obj.isFile()) {
                    Log.w(TAG, "pkg restore: object missing " + e.path);
                    zos.close();
                    part.delete();
                    return null;
                }
                String sha;
                InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(obj), BUF);
                try {
                    sha = putEntry(zos, e, in, buf);
                } finally {
                    closeQuietly(in);
                }
                if (sha == null || !sha.equals(e.sha)) {
                    Log.w(TAG, "pkg restore: sha mismatch " + e.path + " expected=" + e.sha + " got=" + sha);
                    zos.close();
                    part.delete();
                    return null;
                }
                done++;
            }
            zos.close();
            // ★ 只读：Android 14+ 可写的 dex 载体会被 DexClassLoader 拒绝（与 Importer 同一条）
            part.setReadOnly();
            if (!part.renameTo(dst)) {
                // rename 失败（极少数机型跨挂载点）⇒ 退回复制，再删 .part
                Log.w(TAG, "pkg restore: rename failed, falling back to copy");
                if (!copy(part, dst, buf)) {
                    part.delete();
                    return null;
                }
                dst.setReadOnly();
                part.delete();
            }
            // 拼完自检：能被 ZipFile 打开、条目数与清单一致（不必重算每个 sha——上面已经算过）
            ZipFile zf = null;
            try {
                zf = new ZipFile(dst);
                int n = zf.size();
                if (n != m.entries.size()) {
                    Log.w(TAG, "pkg restore: entry count " + n + " != " + m.entries.size());
                    dst.setWritable(true);
                    dst.delete();
                    return null;
                }
            } finally {
                closeQuietly(zf);
            }
            Log.i(TAG, "pkg restore: " + m.file + " entries=" + done + " size=" + dst.length()
                    + " B in " + (System.currentTimeMillis() - t0) + " ms");
            return dst;
        } catch (Throwable t) {
            Log.w(TAG, "pkg restore failed for " + m.file + ": " + t);
            part.delete();
            return null;
        }
    }

    /**
     * 写一个条目并返回它**解出来的字节**的 sha256（顺便校验池对象没坏）。
     *
     * <p>数据起点 4 字节对齐的做法：本地头 30 + 名字长度（UTF-8）+ extra 长度，
     * 三者加起来必须是 4 的倍数。差几字节就塞一个 0xFFFF 私有 extra（5~7 字节）。</p>
     */
    private static String putEntry(ZipOutputStream zos, PkgManifest.Entry e, InputStream in, byte[] buf)
            throws IOException {
        int nameLen = e.path.getBytes(UTF8).length;
        int pad = padLen(nameLen);
        ZipEntry ze = new ZipEntry(e.path);
        ze.setMethod(ZipEntry.STORED);
        ze.setSize(e.size);
        ze.setCompressedSize(e.size);
        ze.setCrc(e.crc);
        ze.setTime(safeTime(e.time));
        if (pad > 0) ze.setExtra(padExtra(pad));
        zos.putNextEntry(ze);
        CRC32 crc = new CRC32();
        java.security.MessageDigest md = Pkgs.digest();
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            zos.write(buf, 0, n);
            crc.update(buf, 0, n);
            md.update(buf, 0, n);
            total += n;
        }
        zos.closeEntry();
        if (total != e.size) {
            throw new IOException("size mismatch " + e.path + " expected=" + e.size + " got=" + total);
        }
        if (crc.getValue() != e.crc) {
            throw new IOException("crc mismatch " + e.path);
        }
        return Pkgs.hex(md.digest());
    }

    /** 需要垫几字节才能让「30 + 名字 + extra」落到 4 的倍数（0 或 5~7）。 */
    private static int padLen(int nameLen) {
        int base = 30 + nameLen;
        if (base % 4 == 0) return 0;
        return (4 - base % 4) + 4;   // 至少 5 字节（extra 头本身 4 字节 + 1 字节内容）
    }

    /** 一个只有填充作用的 extra（headerId 0xFFFF 是私有区，解析方会跳过）。 */
    private static byte[] padExtra(int len) {
        byte[] b = new byte[len];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xFF;
        b[2] = (byte) (len - 4);
        b[3] = 0;
        return b;
    }

    /** ZipEntry 的时间戳合理范围（1980-01-01 ~ 2107-12-31），越界会被 java.util.zip 拒。 */
    private static long safeTime(long t) {
        if (t < 315532800000L || t > 4354819199000L) return System.currentTimeMillis();
        return t;
    }

    private static boolean copy(File src, File dst, byte[] buf) {
        FileOutputStream out = null;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(dst);
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "pkg restore: copy failed: " + t);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 对象池里是否还有这个包的所有条目（关池前的自检）。 */
    static boolean complete(Context ctx, PkgManifest m) {
        if (m == null) return false;
        Pkgs pool = Pkgs.get(ctx);
        for (PkgManifest.Entry e : m.entries) {
            File f = pool.object(e.sha);
            if (f == null || !f.isFile()) return false;
        }
        return true;
    }

    private static void closeQuietly(Object o) {
        try {
            if (o instanceof java.io.Closeable) ((java.io.Closeable) o).close();
            else if (o instanceof ZipFile) ((ZipFile) o).close();
        } catch (Throwable ignored) {
        }
    }

    /** 保留：某些机型上 rename 后需要 fsync 目录，暂不启用（见 {@link #rebuild}）。 */
    @SuppressWarnings("unused")
    private static void fsyncDir(File dir) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(dir, "r");
            raf.getFD().sync();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(raf);
        }
    }
}
