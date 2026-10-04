package io.mdt.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
 * 内容寻址对象池（CAS，F16）。模块边界：只认「目录 + 字节」，**不认清单格式**
 * （清单归 {@link Backup}，引用集由调用方收集后传进来）。
 *
 * 移植自桌面版 `MDT-Dev/launcher/storage.py` 的 {@code CASStore}（已被 27 份真实备份
 * + 26 个版本长期验证）。四条核心设计逐条照抄，另有三条 Android 侧的必要改造。
 *
 * ── 核心设计（照抄桌面版，缺一条就出"对象丢失"） ─────────────────────────────
 *
 * ① **两级分片** `objects/&lt;sha 前 2 位&gt;/&lt;后 62 位&gt;` ⇒ GC 只要两层
 *    `listFiles`，不用递归；也不会出现"一个目录三万条目"。
 *
 * ② **对象不可变 + 原子落盘**：先写 `tmp/` 再 `rename` ⇒ 备份被打断**不会留半成品**
 *    （这是相对旧 `Backup.copyRecursive` 的直接写入的硬改进：旧版中断 = 半个快照）。
 *
 * ③ **{@link #BULK_LOCK} 批量写锁**：写入方在「对象已落盘、清单还没写」的空档里，
 *    这些对象在 GC 眼里全是孤儿 —— 不持锁的话 GC 会把它们整批删掉，而清单随后
 *    照样写成功。症状极难查：备份列表正常、一恢复才报「对象丢失」。
 *
 * ④ **{@link #GC_GRACE_MS} 宽限期**：太新的对象一律不删。锁只管本进程；
 *    上次被强杀留下的半截状态、或将来多进程，只能靠对象年龄兜住。
 *
 * ── Android 侧的三条改造（与桌面版有意不同） ────────────────────────────────
 *
 * ⑤ **一遍 I/O 算哈希**：桌面版 `store_file(data: bytes)` 把整个文件读进内存。
 *    真实 `mods/` 里单个文件可达几十 MB（实测一份备份 18 个 mod 文件占 129.7 MB）
 *    ⇒ 一律**流式**：读源文件的同时 `update` 摘要、写 tmp，最后按算出的 sha
 *    `rename` 到分片目录。读一遍、写一遍，内存峰值 = 缓冲区大小。
 *    （本工程 F6 已定纪律：**绝不把大文件全量读进内存**。）
 *
 * ⑥ **恢复时顺带校验**：`getTo(..., verify=true)` 边写边算 sha256，写完比对
 *    ⇒ 把旧版「恢复前先自证 md5（多读一遍）」和「恢复写入」合成一遍 I/O，
 *    校验强度还更高（sha256 &gt; md5，且是**寻址键本身**）。
 *
 * ⑦ **池的落点在 `&lt;hub&gt;/cas/`，不在 `&lt;hub&gt;/backups/` 里面**：桌面版是
 *    `Backups/objects`，与 profile 目录平级；我们若放 `backups/objects`，
 *    就多出一个「用户把槽命名成 `objects` 会撞池」的隐患（`Data.sanitizeSlot`
 *    允许这个名字）。分成两棵子树后，{@link Backup#deleteSlotBackups} /
 *    {@link Backup#renameSlotBackups} 天然碰不到池，**零回归风险**。
 */
final class Cas {
    private static final String TAG = "MDTLauncher";

    /** 对象池子目录名（相对 CAS 基目录） */
    static final String OBJECTS = "objects";
    /** 写入暂存区（相对 objects 目录）。⚠️ 名字长度 ≠ 2 ⇒ GC 的两层扫描天然跳过它 */
    private static final String TMP = "tmp";

    /**
     * GC 宽限期：比它新的对象一律不删。
     * 必须远大于「一次备份要花的时间」（156 MB 量级实测数秒，留 10 分钟余量）。
     */
    static final long GC_GRACE_MS = 600_000L;

    /** 暂存件清扫门槛：超过这么久还没被 rename 走的 `.part` 必然是死件 */
    private static final long TMP_SWEEP_MS = 3_600_000L;

    private static final int BUF = 65536;

    /**
     * ★★ 批量写锁（**静态** —— 一个进程一个池，锁跟着池走）。
     *
     * 持锁区间 = 「写第一个对象」到「清单原子写完」。GC 必须先拿到这把锁才开扫。
     * 用 `synchronized`（Java 的监视器天然可重入，等价桌面版的 `RLock`：
     * 迁移内部要调 {@code put}，会重复进锁）。
     */
    static final Object BULK_LOCK = new Object();

    private final File objectsDir;
    private final File tmpDir;

    /**
     * @param baseDir CAS 基目录（本工程用 `&lt;hub&gt;/cas`）。对象池 = baseDir/objects
     */
    Cas(File baseDir) {
        this.objectsDir = new File(baseDir, OBJECTS);
        this.tmpDir = new File(objectsDir, TMP);
    }

    File objectsDir() {
        return objectsDir;
    }

    // ── 写入 ──────────────────────────────────────────────────────────────

    /** 一次入库的结果 */
    static final class Stored {
        /** 内容哈希（= 对象在池里的地址） */
        final String sha;
        /** true = 这个对象此前不存在，本次真正占了新空间 */
        final boolean isNew;
        /** 这个对象的字节数（与源文件同长） */
        final long bytes;

        Stored(String sha, boolean isNew, long bytes) {
            this.sha = sha;
            this.isNew = isNew;
            this.bytes = bytes;
        }
    }

    /** 把一个文件收进对象池。返回内容哈希。 */
    String put(File src) throws IOException {
        return put(src, null).sha;
    }

    /**
     * 把一个文件收进对象池；可选地**同时**对另一个摘要做 update。
     *
     * @param extra 额外摘要（迁移旧备份时传 MD5，用来一遍读完就顺带校验旧清单的
     *              md5 列 —— 多算一个摘要的 CPU 成本近似为零，I/O 完全复用）。
     *              调用方在返回后自己 `digest()`，本方法不碰它的生命周期。
     */
    Stored put(File src, MessageDigest extra) throws IOException {
        ensureDir(objectsDir);
        ensureDir(tmpDir);
        sweepTmp();

        MessageDigest sha = newDigest();
        File tmp = File.createTempFile("obj-", ".part", tmpDir);
        long n = 0;
        try {
            InputStream in = new BufferedInputStream(new FileInputStream(src), BUF);
            try {
                OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), BUF);
                try {
                    byte[] buf = new byte[BUF];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        sha.update(buf, 0, k);
                        if (extra != null) extra.update(buf, 0, k);
                        out.write(buf, 0, k);
                        n += k;
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } catch (RuntimeException e) {
            tmp.delete();
            throw e;
        }

        String id = hex(sha.digest());
        File obj = objectPath(id);
        ensureDir(obj.getParentFile());
        if (obj.isFile()) {
            tmp.delete();                     // 内容相同（sha 寻址）⇒ 直接复用
            return new Stored(id, false, n);
        }
        if (obj.exists() && !obj.delete()) {  // 极罕见：同名但类型不是文件
            tmp.delete();
            throw new IOException("对象位置被占且清不掉：" + obj.getAbsolutePath());
        }
        if (!tmp.renameTo(obj)) {
            tmp.delete();
            throw new IOException("对象落盘失败（rename）：" + obj.getAbsolutePath());
        }
        return new Stored(id, true, n);
    }

    /** 只算 sha256，不落盘（探测"这份文件在池里吗"用） */
    String sha256Of(File f) throws IOException {
        MessageDigest md = newDigest();
        InputStream in = new BufferedInputStream(new FileInputStream(f), BUF);
        try {
            byte[] buf = new byte[BUF];
            int k;
            while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    // ── 读取 ──────────────────────────────────────────────────────────────

    /** 对象是否在池里（只查存在性，不校验内容） */
    boolean has(String id) {
        return id != null && id.length() == 64 && objectPath(id).isFile();
    }

    File objectPath(String id) {
        return new File(new File(objectsDir, id.substring(0, 2)), id.substring(2));
    }

    /**
     * 把对象还原成文件（**原子**：先写目标同目录的 `.part`，再 rename）。
     *
     * @param verify true = 边写边算 sha256 并在写完时比对 ⇒ 对象池被外部破坏时
     *               宁可报错也不写出坏文件。恢复用户存档一律传 true。
     * @throws IOException 对象缺失 / 内容损坏 / 写不进去
     */
    void getTo(String id, File dst, boolean verify) throws IOException {
        File obj = objectPath(id);
        if (!obj.isFile()) throw new IOException("对象丢失：" + id);

        File par = dst.getParentFile();
        if (par != null) ensureDir(par);

        File tmp = new File(par, dst.getName() + ".part");
        if (tmp.exists()) tmp.delete();

        MessageDigest md = verify ? newDigest() : null;
        try {
            InputStream in = new BufferedInputStream(new FileInputStream(obj), BUF);
            try {
                OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), BUF);
                try {
                    byte[] buf = new byte[BUF];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        if (md != null) md.update(buf, 0, k);
                        out.write(buf, 0, k);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }

        if (md != null && !hex(md.digest()).equals(id)) {
            tmp.delete();
            throw new IOException("对象内容损坏（sha256 与地址不符）：" + id);
        }
        if (dst.exists() && !dst.delete()) {
            tmp.delete();
            throw new IOException("目标文件清不掉：" + dst.getAbsolutePath());
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("恢复写出失败（rename）：" + dst.getAbsolutePath());
        }
    }

    // ── 回收 ──────────────────────────────────────────────────────────────

    /**
     * 删除未被引用的对象。**只有调用方（{@link Backup}）知道引用集** ——
     * 它扫所有槽的所有清单收集 sha 后传进来。
     *
     * 两道防误删照抄桌面版：整段循环持 {@link #BULK_LOCK}（正在备份就等它写完），
     * 且不删年龄在 {@link #GC_GRACE_MS} 以内的对象。
     *
     * @return 实际删除的对象数
     */
    int garbageCollect(Set<String> referenced) {
        long cutoff = System.currentTimeMillis() - GC_GRACE_MS;
        int removed = 0;
        int kept = 0;
        int skipped = 0;
        synchronized (BULK_LOCK) {
            File[] prefixes = objectsDir.listFiles();
            if (prefixes == null) return 0;
            for (File p : prefixes) {
                if (!p.isDirectory()) continue;
                String pn = p.getName();
                if (pn.length() != 2) continue;          // 跳过 tmp/ 等非分片目录
                File[] objs = p.listFiles();
                if (objs == null) continue;              // 读不到就整目录留着（宁可少删）
                boolean leftover = false;
                for (File o : objs) {
                    if (!o.isFile()) {
                        leftover = true;
                        continue;
                    }
                    if (referenced.contains(pn + o.getName())) {
                        leftover = true;
                        kept++;
                        continue;
                    }
                    if (o.lastModified() > cutoff) {     // 太新 ⇒ 多半是没写完的备份
                        leftover = true;
                        skipped++;
                        continue;
                    }
                    if (o.delete()) removed++;
                    else leftover = true;
                }
                if (!leftover) p.delete();               // 空分片目录顺手收掉
            }
        }
        if (removed > 0 || skipped > 0) {
            android.util.Log.i(TAG, "cas gc: removed=" + removed + " kept=" + kept
                    + " skipped(new)=" + skipped);
        }
        return removed;
    }

    /** 池的体检数字（不删任何东西） */
    static final class Pool {
        public int objects;
        public long bytes;
        public int orphans;
        public long orphanBytes;

        /** 孤儿占比（0~1）；池为空返回 0 */
        public double orphanRatio() {
            return objects == 0 ? 0d : (double) orphans / objects;
        }
    }

    /**
     * 扫一遍池，统计对象数 / 实际占用 / 孤儿。
     * ⚠️ 与 {@link #garbageCollect} 用同一套"可删"判据（未引用 **且** 过了宽限期），
     * 否则体检报告会与实际 GC 行为对不上。
     */
    Pool scan(Set<String> referenced) {
        long cutoff = System.currentTimeMillis() - GC_GRACE_MS;
        Pool s = new Pool();
        File[] prefixes = objectsDir.listFiles();
        if (prefixes == null) return s;
        for (File p : prefixes) {
            if (!p.isDirectory()) continue;
            String pn = p.getName();
            if (pn.length() != 2) continue;
            File[] objs = p.listFiles();
            if (objs == null) continue;
            for (File o : objs) {
                if (!o.isFile()) continue;
                long len = o.length();
                s.objects++;
                s.bytes += len;
                if (!referenced.contains(pn + o.getName()) && o.lastModified() <= cutoff) {
                    s.orphans++;
                    s.orphanBytes += len;
                }
            }
        }
        return s;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 入口清扫：把上次留下的暂存件删掉。
     * ★ 纪律（F6c 的 `.msav.part` 同一条）：**与其穷举"哪些路径会中断"，不如在下次
     *   进入时清掉"此刻不可能有人在用"的东西**。进程内同一时刻只有一次 put，
     *   所以超过 1 小时的 `.part` 必然是死件。幂等清扫 &gt; 穷举清理路径。
     */
    private void sweepTmp() {
        long cutoff = System.currentTimeMillis() - TMP_SWEEP_MS;
        File[] fs = tmpDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile() && f.lastModified() < cutoff) f.delete();
        }
    }

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("本机没有 SHA-256 实现", e);
        }
    }

    private static void ensureDir(File d) throws IOException {
        if (d == null || d.isDirectory()) return;
        if (!d.mkdirs() && !d.isDirectory()) {
            throw new IOException("建目录失败：" + d.getAbsolutePath());
        }
    }

    static String hex(byte[] b) {
        char[] cs = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            cs[i * 2] = HEX[v >>> 4];
            cs[i * 2 + 1] = HEX[v & 0xF];
        }
        return new String(cs);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
