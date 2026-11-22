package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * 游戏包的对象池（按条目 CAS，附录 E）。
 *
 * <p>布局：`app_hub/pkgs/objects/<sha 前2>/<sha 后62>` + `app_hub/pkgs/tmp/` +
 * `app_hub/pkgs/override.dex` + `app_hub/pkgs/manifests/&lt;vkey&gt;.json`（后者由
 * {@link PkgManifest} 管）。</p>
 *
 * <p>与存档备份的 {@link Cas} 的区别：这里的对象**必须只读** —— 游戏要把它当 dex / .so
 * 直接加载，Android 14+ 对可写 dex 一律 {@code SecurityException}。</p>
 *
 * <p>线程模型：只在导入（后台线程）与 GC 时写，启动路径只读；所有写都走
 * `tmp/*.part` → rename，所以并发读永远看到完整文件。</p>
 */
public final class Pkgs {
    private static final String TAG = "MDTLauncher";
    private static final int BUF = 65536;

    /** GC 宽限期：刚写进来但清单还没落盘的对象不能被当成垃圾删掉。 */
    static final long GC_GRACE_MS = 10 * 60 * 1000L;

    private final File base;
    private final File objects;
    private final File tmp;

    private Pkgs(File base) {
        this.base = base;
        this.objects = new File(base, "objects");
        this.tmp = new File(base, "tmp");
    }

    public static File root(Context ctx) {
        return new File(Paths.privateDir(ctx), "pkgs");
    }

    public static Pkgs get(Context ctx) {
        Pkgs p = new Pkgs(root(ctx));
        p.ensure();
        return p;
    }

    private void ensure() {
        if (!objects.exists()) objects.mkdirs();
        if (!tmp.exists()) tmp.mkdirs();
    }

    public File objects() {
        return objects;
    }

    public File tmpDir() {
        return tmp;
    }

    public File overrideDex() {
        return new File(base, "override.dex");
    }

    public File manifestDir() {
        File d = new File(base, "manifests");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 对象文件路径（不含是否存在的判断）。 */
    public File object(String sha) {
        if (sha == null || sha.length() != 64) return null;
        return new File(new File(objects, sha.substring(0, 2)), sha.substring(2));
    }

    public boolean has(String sha) {
        File f = object(sha);
        return f != null && f.isFile();
    }

    /**
     * 把一个普通文件的内容按内容寻址写进池（同 sha 已存在则跳过）。
     *
     * @return 该内容的 sha256（小写十六进制）
     */
    public String put(File src) throws IOException {
        if (src == null || !src.isFile()) throw new IOException("put: no such file");
        String sha = sha256Of(src);
        if (has(sha)) return sha;
        File part = new File(tmp, sha + ".part");
        InputStream in = new FileInputStream(src);
        try {
            copyAndHash(in, part, null);
        } finally {
            in.close();
        }
        adopt(part, sha);
        return sha;
    }

    /**
     * 把一个 zip 条目（或任意输入流）写进池：边读边算 sha，命中已有对象时不落盘。
     *
     * @param in  只读输入流（由调用方关闭）
     * @param sha 已知的 sha（十六进制）；为 null 则现算
     */
    public String putStream(InputStream in, String sha) throws IOException {
        return putStream(in, sha, null);
    }

    /**
     * 同上，并通过 `wrote`（长度 ≥1 的数组）回报这次是否真的新写了对象 —— 导入统计要用，
     * 别为了统计去反复扫池。
     */
    public String putStream(InputStream in, String sha, boolean[] wrote) throws IOException {
        if (sha != null && has(sha)) {
            if (wrote != null && wrote.length > 0) wrote[0] = false;
            return sha;
        }
        File part = new File(tmp, "put-" + System.nanoTime() + ".part");
        String got;
        try {
            got = copyAndHash(in, part, sha);
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        boolean created = adopt(part, got);
        if (wrote != null && wrote.length > 0) wrote[0] = created;
        return got;
    }

    /** `part` → `objects/<2>/<62>`，只读。目标已存在时直接丢掉 part。返回是否新增了对象。 */
    private boolean adopt(File part, String sha) throws IOException {
        File dst = object(sha);
        if (dst == null) {
            part.delete();
            throw new IOException("adopt: bad sha");
        }
        File dir = dst.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        if (dst.isFile()) {
            part.delete();
            return false;
        }
        if (!part.renameTo(dst)) {
            // 跨目录 rename 在同一分区应当成功；失败时退化成复制
            copyFile(part, dst);
            part.delete();
        }
        if (!dst.setReadOnly()) {
            // 只读化失败不致命（不是每个文件系统都支持），但要留痕：dex 可写会在启动期炸。
            android.util.Log.w(TAG, "pkgs: setReadOnly failed for " + sha);
        }
        return true;
    }

    private String copyAndHash(InputStream in, File dst, String expected) throws IOException {
        MessageDigest md = digest();
        File dir = dst.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        OutputStream out = new FileOutputStream(dst);
        long total = 0;
        try {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                total += n;
            }
            out.flush();
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        } finally {
            out.close();
        }
        String got = hex(md.digest());
        if (expected != null && !expected.equalsIgnoreCase(got)) {
            dst.delete();
            throw new IOException("sha mismatch: expected " + expected + " got " + got);
        }
        if (total == 0) {
            dst.delete();
            throw new IOException("empty entry");
        }
        return got;
    }

    /** 删除不在 `live` 里的对象（宽限期内不动）。返回删掉的字节数。 */
    public long gc(Set<String> live) {
        File[] shards = objects.listFiles();
        if (shards == null) return 0;
        long freed = 0;
        long now = System.currentTimeMillis();
        for (File shard : shards) {
            if (shard == null || !shard.isDirectory() || shard.getName().length() != 2) continue;
            File[] files = shard.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                String sha = shard.getName() + f.getName();
                if (live.contains(sha)) continue;
                if (now - f.lastModified() < GC_GRACE_MS) continue;
                long len = f.length();
                if (f.setWritable(true) && f.delete()) {
                    freed += len;
                }
            }
            File[] rest = shard.listFiles();
            if (rest != null && rest.length == 0) shard.delete();
        }
        return freed;
    }

    /** {对象数, 字节数}。 */
    public long[] stats() {
        long count = 0, bytes = 0;
        File[] shards = objects.listFiles();
        if (shards != null) {
            for (File shard : shards) {
                File[] files = shard == null ? null : shard.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f != null && f.isFile()) {
                        count++;
                        bytes += f.length();
                    }
                }
            }
        }
        return new long[]{count, bytes};
    }

    // ── 静态工具（Import/Injector 共用） ────────────────────────────────

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    public static String sha256Of(File f) throws IOException {
        MessageDigest md = digest();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
