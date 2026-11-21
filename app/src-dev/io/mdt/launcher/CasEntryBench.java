package io.mdt.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import dalvik.system.DexClassLoader;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * dev 探针：**按条目 CAS（对象池）** 的落地成本与真机可消费性。
 *
 * <p>为什么要这一趟（2026-11-21 定案「按条目 CAS」之后）：
 * 整包 CAS 已实测**收益 0**（7 个包 sha 全不同），方案改成
 * 「对象池按条目去重 + 启动前把条目拼回一个 STORED APK（物化）」。
 * 离线按条目去重已量过（5 包省 61.9%），但有五个数**只有真机能给**：
 * <ol>
 *   <li>逐条目 ingest 一个 78 MB 包（~750 次小文件写 + setReadOnly）到底多久；</li>
 *   <li>物化一个 83 MiB STORED 包首次多久（文档写的 0.2~0.5 s 是**预计**）；</li>
 *   <li>二次物化（缓存命中路径）多久；</li>
 *   <li><b>ART 吃不吃重组包</b> —— 这是最大风险：`DexClassLoader` 能不能从
 *       "没有 lib/、没有 META-INF/、全 STORED" 的包里 loadClass；
 *       `AssetManager.addAssetPath` 能不能读 assets，尤其 `openFd`（图集走它）；</li>
 *   <li>设备上 7 个包 ingest 到同一池后的**真实体积**（不是外推）。</li>
 * </ol>
 *
 * <p>只读 `app_hub/import/*.apk`，池建在**内部**存储的临时目录
 * `app_hub/casentry/`（游戏本体只能住内部存储）。
 *
 * <p>⚠️ page cache：先测 v160 的 ingest 会把源文件读进 cache ⇒ 后面的重复 ingest
 * 与 7 包全量里 v160 那一步是热态。报告里标了。
 */
final class CasEntryBench {

    private CasEntryBench() {}

    private static final int BUF = 65536;
    private static final String DIR = "casentry";

    /**
     * @param keep true = 跑完保留池与物化包（供外部 `du` 复核块开销）；false = 删净
     */
    static void run(Context ctx, StringBuilder sb, boolean keep) throws Exception {
        File hub = Paths.privateDir(ctx);
        File importDir = new File(hub, "import");
        List<File> apks = listApks(importDir);
        if (apks.isEmpty()) {
            sb.append("import/ 里没有 .apk，先导入一个游戏包再测\n");
            return;
        }

        sb.append("设备 ").append(android.os.Build.MODEL)
          .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
          .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
        sb.append("内部存储可用 ").append(fmt(hub.getUsableSpace()))
          .append(" / 总 ").append(fmt(hub.getTotalSpace())).append('\n');
        sb.append("1 MiB = 1024 KiB，下面全部按 1024 进\n");

        long importBytes = 0;
        sb.append("import/ 共 ").append(apks.size()).append(" 个包：\n");
        for (File f : apks) {
            importBytes += f.length();
            sb.append("  ").append(f.getName()).append("  ").append(fmt(f.length())).append('\n');
        }
        sb.append("  → 合计 ").append(fmt(importBytes)).append('\n');

        File base = new File(hub, DIR);
        deleteTree(base);
        File objects = new File(base, "objects");
        File cache = new File(base, "cache");
        objects.mkdirs();
        cache.mkdirs();

        File src = pick(apks, "v160.apk");
        String stem = stemOf(src);

        // ── ① 逐条目 ingest ────────────────────────────────────────────────
        Ingest r1 = ingest(src, objects, true);
        sb.append("\n① 逐条目 ingest（").append(src.getName()).append("）\n");
        sb.append("   条目 ").append(r1.count).append("  解压字节 ").append(fmt(r1.bytes)).append('\n');
        sb.append("   新写对象 ").append(r1.written).append(" 个 / ").append(fmt(r1.writtenBytes))
          .append("；命中跳过 ").append(r1.skipped).append(" 个 / ").append(fmt(r1.skippedBytes)).append('\n');
        sb.append("   耗时 ").append(r1.ms).append(" ms（其中写对象+只读 ").append(r1.writeMs)
          .append(" ms = ").append(pct(r1.writeMs, r1.ms)).append("）\n");
        sb.append("   读吞吐 ").append(rate(r1.bytes, r1.ms)).append("（含哈希与写）；写吞吐 ")
          .append(rate(r1.writtenBytes, r1.writeMs)).append('\n');
        sb.append("   平均每对象 ").append(String.format(Locale.US, "%.3f ms",
                r1.writeMs / (double) Math.max(1, r1.written)))
          .append("（").append(r1.written).append(" 次小文件创建 + setReadOnly）\n");
        sb.append("   原包条目压缩法：STORED ").append(r1.stored).append(" / DEFLATED ").append(r1.deflated)
          .append("（图集走 openFd 的前提是 STORED）\n");
        sb.append("   ⚠ 对照：整包 put 是「1 次 75 MB 写 + 1 次哈希」= 154 ms；这里拆成 ")
          .append(r1.written).append(" 次\n");

        // ── ①b 优化写法（CRC 取 entry、复用 digest、流式写 tmp 后 rename）────
        deleteTree(objects);
        objects.mkdirs();
        File tmpDir = new File(base, "tmp");
        Ingest r1b = ingestFast(src, objects, tmpDir, true);
        sb.append("\n①b 同一包换「产品写法」再 ingest（池已清，同样 750 次新写）\n");
        sb.append("   条目 ").append(r1b.count).append("  字节 ").append(fmt(r1b.bytes))
          .append("  新写 ").append(r1b.written).append(" 个 / ").append(fmt(r1b.writtenBytes)).append('\n');
        sb.append("   耗时 ").append(r1b.ms).append(" ms（写对象+rename ").append(r1b.writeMs)
          .append(" ms = ").append(pct(r1b.writeMs, r1b.ms)).append("）  ")
          .append(rate(r1b.bytes, r1b.ms)).append('\n');
        sb.append("   → 比 ① 快 ").append(pct(r1.ms - r1b.ms, r1.ms)).append("（与 ① 同为冷池，page cache 已热）\n");
        // 两种写法的条目 sha 必须一一对上（CRC 来源不同的正确性旁证）
        sb.append("   两种写法条目表一致 = ").append(sameEntries(r1, r1b)).append('\n');

        // ── ①c 并行 ingest（4 线程，条目分片）─────────────────────────────
        deleteTree(objects);
        objects.mkdirs();
        final int THREADS = 4;
        Ingest r1c = ingestParallel(src, objects, tmpDir, THREADS, true);
        sb.append("\n①c 同包 4 线程并行 ingest（池再清，同样 750 次新写）\n");
        sb.append("   条目 ").append(r1c.count).append("  字节 ").append(fmt(r1c.bytes))
          .append("  新写 ").append(r1c.written).append(" 个 / ").append(fmt(r1c.writtenBytes)).append('\n');
        sb.append("   耗时 ").append(r1c.ms).append(" ms  ").append(rate(r1c.bytes, r1c.ms))
          .append("  → 比单线程 ").append(pct(r1b.ms - r1c.ms, r1b.ms)).append('\n');
        sb.append("   条目表与单线程一致 = ").append(sameEntries(r1b, r1c)).append('\n');

        // ── ② 物化 ────────────────────────────────────────────────────────
        File mat = new File(cache, stem + "-entry.apk");
        Mat m = materialize(files(r1c), objects, mat);
        sb.append("\n② 物化 STORED 包（跳 lib/** 与 META-INF/**）\n");
        sb.append("   条目 ").append(m.count).append("  解压字节 ").append(fmt(m.bytes))
          .append("  跳过 ").append(m.skipped).append(" 个 / ").append(fmt(m.skippedBytes)).append('\n');
        sb.append("   耗时 ").append(m.ms).append(" ms  ").append(rate(m.bytes, m.ms)).append('\n');
        sb.append("   产物 = ").append(fmt(mat.length())).append('\n');
        sb.append("   ⚠ 文档预计首次 0.2~0.5 s ⇒ 以上是实测\n");

        // ── ③ ART 验收 ────────────────────────────────────────────────────
        sb.append("\n③ ART 验收：重组包能否被真机消费（最大风险）\n");
        artCheck(ctx, mat, r1c, sb);

        // ── ④ 二次物化 ────────────────────────────────────────────────────
        File mat2 = new File(cache, stem + "-entry2.apk");
        Mat m2 = materialize(files(r1c), objects, mat2);
        sb.append("\n④ 二次物化（缓存命中 / 重复构建路径）\n");
        sb.append("   耗时 ").append(m2.ms).append(" ms（首次 ").append(m.ms).append(" ms）\n");
        deleteTree(mat2);

        // ── ⑤ 重复 ingest ────────────────────────────────────────────────
        Ingest r2 = ingest(src, objects, false);
        sb.append("\n⑤ 重复 ingest 同一包（全命中，只读 + 哈希，不写）\n");
        sb.append("   新写 ").append(r2.written).append(" 个（应为 0）  跳过 ").append(r2.skipped)
          .append(" 个 / ").append(fmt(r2.skippedBytes)).append('\n');
        sb.append("   耗时 ").append(r2.ms).append(" ms  ").append(rate(r2.bytes, r2.ms)).append('\n');

        // ── ⑥ 全部包一组 ──────────────────────────────────────────────────
        sb.append("\n⑥ 设备上全部 ").append(apks.size()).append(" 个包 ingest 到同一池\n");
        long t6 = System.nanoTime();
        long newObj = 0, newBytes = 0;
        for (File f : apks) {
            if (f.equals(src)) continue;
            Ingest ri = ingest(f, objects, false);
            newObj += ri.written;
            newBytes += ri.writtenBytes;
            sb.append("   ").append(f.getName()).append("  条目 ").append(ri.count)
              .append("  新写 ").append(ri.written).append(" 个 / ").append(fmt(ri.writtenBytes))
              .append("  耗时 ").append(ri.ms).append(" ms\n");
        }
        long ms6 = ms(t6);
        long poolBytes = treeSize(objects);
        long poolFiles = countFiles(objects);
        sb.append("   其余包新写合计 ").append(newObj).append(" 个 / ").append(fmt(newBytes))
          .append("；耗时 ").append(ms6).append(" ms\n");
        sb.append("   池 = ").append(poolFiles).append(" 个对象文件 / ").append(fmt(poolBytes))
          .append("（sum(文件长度)，未含块开销）\n");
        long after = poolBytes + mat.length();
        sb.append("   原包合计 ").append(fmt(importBytes)).append(" → 池 + 一份物化 = ")
          .append(fmt(after)).append("（").append(pct(after, importBytes)).append("）")
          .append("；省 ").append(fmt(importBytes - after)).append('\n');
        sb.append("   ⚠ 物化只算了 v160 一份；多版本各留一份要按 83 MiB/份 再加\n");

        // ── 收尾 ─────────────────────────────────────────────────────────
        if (keep) {
            sb.append("\n⚠ 保留在 ").append(base.getAbsolutePath())
              .append("（用完 `--es dev_casentry_clean 1` 清）\n");
        } else {
            deleteTree(base);
            sb.append("\n池已删净（").append(base.exists() ? "残留！" : "无残留").append("）\n");
        }
        sb.append("★ ① 之后 v160 已进 page cache ⇒ ⑤ 与 ⑥ 里的 v160 不在其中（已跳过）。\n");
    }

    // ── ① ingest ──────────────────────────────────────────────────────────

    /** 一个包的逐条目 ingest 结果 */
    private static final class Ingest {
        int count, dirs, written, skipped, stored, deflated;
        long bytes, writtenBytes, skippedBytes;
        long ms, writeMs;
        final List<Entry> entries = new ArrayList<Entry>();
    }

    /** 清单里的一条 */
    private static final class Entry {
        String path;
        long size;
        String sha;
        long crc;
        long time;
    }

    /**
     * 逐条目读 → sha256 → 写对象（同 sha 已在则跳过）。
     *
     * @param collect false 时不留条目清单（7 包全量只要体积，不要清单）
     */
    private static Ingest ingest(File apk, File objects, boolean collect) throws Exception {
        Ingest r = new Ingest();
        long t0 = System.nanoTime();
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String path = e.getName();
                if (e.isDirectory() || path.endsWith("/")) { r.dirs++; continue; }
                long size = e.getSize();
                if (size < 0) size = 0;
                if (e.getMethod() == ZipEntry.STORED) r.stored++; else r.deflated++;

                byte[] data = readEntry(zf, e, (int) size);
                String sha = sha256(data);
                CRC32 crc = new CRC32();
                crc.update(data);

                long tw = System.nanoTime();
                File obj = objectPath(objects, sha);
                if (!obj.isFile()) {
                    writeObject(obj, data);
                    r.written++;
                    r.writtenBytes += data.length;
                } else {
                    r.skipped++;
                    r.skippedBytes += data.length;
                }
                r.writeMs += (System.nanoTime() - tw) / 1000000L;

                r.count++;
                r.bytes += data.length;
                if (collect) {
                    Entry ee = new Entry();
                    ee.path = path;
                    ee.size = data.length;
                    ee.sha = sha;
                    ee.crc = crc.getValue();
                    ee.time = e.getTime();
                    r.entries.add(ee);
                }
            }
        } finally {
            zf.close();
        }
        r.ms = ms(t0);
        return r;
    }

    /**
     * 「产品写法」的 ingest，三处省掉冗余：
     * <ol>
     *   <li>CRC **直接取 {@link ZipEntry#getCrc()}** —— 它就是未压缩数据的 CRC，
     *       而物化时 {@code ZipEntry.setCrc} 要的正是这个值，自己再算一遍纯属白烧；</li>
     *   <li>复用同一个 {@link MessageDigest}（{@code reset()}），别每条目 {@code getInstance}；</li>
     *   <li>流式「读 → 写 tmp + 喂哈希」，算出 sha 后 {@code rename} 到对象路径 ——
     *       不经 {@code ByteArrayOutputStream} 二次拷贝（原版对大条目要多拷一份 8 MB）。</li>
     * </ol>
     * tmp 与 objects 在同一分区 ⇒ rename 原子且与目录深度无关。
     */
    private static Ingest ingestFast(File apk, File objects, File tmpDir, boolean collect) throws Exception {
        Ingest r = new Ingest();
        long t0 = System.nanoTime();
        if (tmpDir != null && !tmpDir.isDirectory()) tmpDir.mkdirs();
        File tmp = new File(tmpDir, "obj.part");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String path = e.getName();
                if (e.isDirectory() || path.endsWith("/")) { r.dirs++; continue; }
                if (e.getMethod() == ZipEntry.STORED) r.stored++; else r.deflated++;

                md.reset();
                if (tmp.exists() && !tmp.delete()) throw new IOException("tmp 删不掉：" + tmp);
                InputStream in = zf.getInputStream(e);
                FileOutputStream fo = new FileOutputStream(tmp);
                long n = 0;
                try {
                    byte[] buf = new byte[BUF];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        md.update(buf, 0, k);
                        fo.write(buf, 0, k);
                        n += k;
                    }
                } finally {
                    in.close();
                    fo.close();
                }
                String sha = Cas.hex(md.digest());
                long crc = e.getCrc();
                if (crc < 0) crc = crcOf(tmp);        // 罕见：中心目录没记 CRC

                long tw = System.nanoTime();
                File obj = objectPath(objects, sha);
                if (!obj.isFile()) {
                    File par = obj.getParentFile();
                    if (par != null && !par.isDirectory()) par.mkdirs();
                    if (!tmp.renameTo(obj)) copyFile(tmp, obj);
                    if (tmp.exists()) tmp.delete();
                    obj.setReadOnly();
                    r.written++;
                    r.writtenBytes += n;
                } else {
                    tmp.delete();
                    r.skipped++;
                    r.skippedBytes += n;
                }
                r.writeMs += (System.nanoTime() - tw) / 1000000L;

                r.count++;
                r.bytes += n;
                if (collect) {
                    Entry ee = new Entry();
                    ee.path = path;
                    ee.size = n;
                    ee.sha = sha;
                    ee.crc = crc;
                    ee.time = e.getTime();
                    r.entries.add(ee);
                }
            }
        } finally {
            zf.close();
        }
        r.ms = ms(t0);
        return r;
    }

    /** 一个线程干的活 */
    private static final class Part {
        int count, written, skipped;
        long bytes, writtenBytes, skippedBytes, writeNs;
        String err;
    }

    /**
     * 并行 ingest：**条目**分片（不是按字节切片 —— 清单天然按条目组织）。
     *
     * <p>为什么要试这一版：单线程 ingest 的 670 ms 里写对象只占 2~40 ms，
     * 时间全在「{@code ZipFile.getInputStream} + inflate + SHA-256」上，这三样都能并行。
     *
     * <p>★ 三条纪律：
     * <ol>
     *   <li>每线程**各开一个 {@link ZipFile}**：共享实例的 native 读是串行化的，
     *       而 inflate/哈希要的是 CPU 并行；</li>
     *   <li>每线程自己的 tmp 文件（{@code obj-<tid>.part}）—— 同分区 rename，不互相踩；</li>
     *   <li>结果按**原条目下标**回填 ⇒ 清单顺序与单线程逐字一致（物化的条目顺序依赖它）。</li>
     * </ol>
     */
    private static Ingest ingestParallel(final File apk, final File objects, File tmpDir,
                                         final int threads, final boolean collect) throws Exception {
        final Ingest r = new Ingest();
        long t0 = System.nanoTime();
        if (tmpDir != null && !tmpDir.isDirectory()) tmpDir.mkdirs();

        final List<String> names = new ArrayList<String>();
        ZipFile zf0 = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf0.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || e.getName().endsWith("/")) { r.dirs++; continue; }
                if (e.getMethod() == ZipEntry.STORED) r.stored++; else r.deflated++;
                names.add(e.getName());
            }
        } finally {
            zf0.close();
        }

        final int n = names.size();
        final Entry[] out = new Entry[n];
        final Part[] parts = new Part[threads];
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(new Runnable() {
                @Override public void run() {
                    Part p = new Part();
                    parts[tid] = p;
                    File tmp = new File(tmpDir, "obj-" + tid + ".part");
                    ZipFile zf = null;
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        zf = new ZipFile(apk);
                        for (int i = tid; i < n; i += threads) {
                            String path = names.get(i);
                            ZipEntry e = zf.getEntry(path);
                            if (e == null) throw new IOException("条目丢失：" + path);
                            md.reset();
                            if (tmp.exists() && !tmp.delete()) throw new IOException("tmp 删不掉：" + tmp);
                            InputStream in = zf.getInputStream(e);
                            FileOutputStream fo = new FileOutputStream(tmp);
                            long len = 0;
                            try {
                                byte[] buf = new byte[BUF];
                                int k;
                                while ((k = in.read(buf)) > 0) {
                                    md.update(buf, 0, k);
                                    fo.write(buf, 0, k);
                                    len += k;
                                }
                            } finally {
                                in.close();
                                fo.close();
                            }
                            String sha = Cas.hex(md.digest());
                            long crc = e.getCrc();
                            if (crc < 0) crc = crcOf(tmp);

                            long tw = System.nanoTime();
                            File obj = objectPath(objects, sha);
                            if (!obj.isFile()) {
                                File par = obj.getParentFile();
                                if (par != null && !par.isDirectory()) par.mkdirs();
                                if (!tmp.renameTo(obj)) copyFile(tmp, obj);
                                if (tmp.exists()) tmp.delete();
                                obj.setReadOnly();
                                p.written++;
                                p.writtenBytes += len;
                            } else {
                                tmp.delete();
                                p.skipped++;
                                p.skippedBytes += len;
                            }
                            p.writeNs += System.nanoTime() - tw;

                            p.count++;
                            p.bytes += len;
                            if (collect) {
                                Entry ee = new Entry();
                                ee.path = path;
                                ee.size = len;
                                ee.sha = sha;
                                ee.crc = crc;
                                ee.time = e.getTime();
                                out[i] = ee;
                            }
                        }
                    } catch (Throwable ex) {
                        p.err = String.valueOf(ex);
                    } finally {
                        try { if (zf != null) zf.close(); } catch (Throwable ignored) { }
                        latch.countDown();
                    }
                }
            }, "casentry-ingest-" + t);
            th.start();
        }
        latch.await();

        StringBuilder err = new StringBuilder();
        for (int t = 0; t < threads; t++) {
            Part p = parts[t];
            if (p == null) { err.append("线程").append(t).append(" 没跑\n"); continue; }
            if (p.err != null) err.append("线程").append(t).append(": ").append(p.err).append('\n');
            r.count += p.count;
            r.bytes += p.bytes;
            r.written += p.written;
            r.writtenBytes += p.writtenBytes;
            r.skipped += p.skipped;
            r.skippedBytes += p.skippedBytes;
            r.writeMs += p.writeNs / 1000000L;
        }
        if (err.length() > 0) throw new IOException("并行 ingest 失败：\n" + err);
        if (collect) for (Entry e : out) if (e != null) r.entries.add(e);
        r.ms = ms(t0);
        return r;
    }

    private static long crcOf(File f) throws IOException {
        CRC32 c = new CRC32();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) c.update(buf, 0, n);
        } finally {
            in.close();
        }
        return c.getValue();
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), BUF);
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

    /** 两种写法产出的条目表必须逐条一致（CRC 来源不同的正确性旁证） */
    private static String sameEntries(Ingest a, Ingest b) {
        if (a.entries.size() != b.entries.size()) {
            return "否（条目数 " + a.entries.size() + " vs " + b.entries.size() + "）";
        }
        int diff = 0;
        String first = null;
        for (int i = 0; i < a.entries.size(); i++) {
            Entry x = a.entries.get(i);
            Entry y = b.entries.get(i);
            if (!x.path.equals(y.path) || !x.sha.equals(y.sha) || x.crc != y.crc || x.size != y.size) {
                diff++;
                if (first == null) first = x.path;
            }
        }
        return diff == 0
                ? "是（" + a.entries.size() + " 条 path/sha/size/crc 全同）"
                : "否（" + diff + " 条不同，首个 " + first + "）";
    }

    private static List<Entry> files(Ingest r) {
        List<Entry> out = new ArrayList<Entry>(r.entries.size());
        for (Entry e : r.entries) if (e.size >= 0) out.add(e);
        return out;
    }

    private static byte[] readEntry(ZipFile zf, ZipEntry e, int size) throws IOException {
        InputStream in = zf.getInputStream(e);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, size));
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeObject(File obj, byte[] data) throws IOException {
        File par = obj.getParentFile();
        if (par != null && !par.isDirectory() && !par.mkdirs() && !par.isDirectory()) {
            throw new IOException("建目录失败：" + par);
        }
        FileOutputStream out = new FileOutputStream(obj);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        // ★ 与 Cas 一致：对象只读不可变（也是喂给 ART 的硬要求）
        obj.setReadOnly();
    }

    // ── ② 物化 ────────────────────────────────────────────────────────────

    private static final class Mat {
        int count, skipped;
        long bytes, skippedBytes, ms;
    }

    /**
     * 把清单里的条目拼回一个 APK：全部 STORED、4 字节对齐（zipalign -f 4 那套），
     * 跳 {@code lib/**}（native 由 Injector 从对象池直出）与 {@code META-INF/**}（不装包，不需要签名）。
     */
    private static Mat materialize(List<Entry> entries, File objects, File dst) throws Exception {
        Mat r = new Mat();
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        if (tmp.exists()) tmp.delete();
        long t0 = System.nanoTime();

        CountingStream counter = new CountingStream(
                new BufferedOutputStream(new FileOutputStream(tmp), BUF));
        ZipOutputStream zout = new ZipOutputStream(counter);
        try {
            for (Entry e : entries) {
                if (e.path.startsWith("lib/") || e.path.startsWith("META-INF/")) {
                    r.skipped++;
                    r.skippedBytes += e.size;
                    continue;
                }
                File obj = objectPath(objects, e.sha);
                if (!obj.isFile()) throw new IOException("对象缺失：" + e.path + " " + e.sha);

                ZipEntry ze = new ZipEntry(e.path);
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(e.size);
                ze.setCompressedSize(e.size);
                ze.setCrc(e.crc);
                ze.setTime(e.time);
                int extraLen = padLen(counter.count, e.path);
                if (extraLen > 0) ze.setExtra(padExtra(extraLen));

                zout.putNextEntry(ze);
                InputStream in = new FileInputStream(obj);
                try {
                    byte[] buf = new byte[BUF];
                    long n = 0;
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        zout.write(buf, 0, k);
                        n += k;
                    }
                    if (n != e.size) {
                        throw new IOException("对象大小不符 " + e.path + "：" + n + " != " + e.size);
                    }
                } finally {
                    in.close();
                }
                zout.closeEntry();
                r.count++;
                r.bytes += e.size;
            }
        } finally {
            zout.close();      // 顺便 flush 到底层
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("rename 失败：" + tmp + " -> " + dst);
        }
        r.ms = ms(t0);
        return r;
    }

    /** data offset 对齐到 4 字节所需的 extra 长度：0（已对齐）或 5~7 */
    private static int padLen(long pos, String name) {
        int base = 30 + name.getBytes(java.nio.charset.Charset.forName("UTF-8")).length;
        int rem = (int) ((pos + base) % 4);
        if (rem == 0) return 0;
        return 4 + (4 - rem) % 4;    // rem=1→7, 2→6, 3→5
    }

    /** 未知 header（0xFFFF）的 extra，里面全是 0；解析器一律跳过 */
    private static byte[] padExtra(int len) {
        byte[] b = new byte[len];
        int d = len - 4;
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xFF;
        b[2] = (byte) (d & 0xFF);
        b[3] = (byte) ((d >> 8) & 0xFF);
        return b;
    }

    /** 自己数写了多少字节 —— ZipOutputStream 的 written 拿不到，用它算对齐 */
    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        long count;

        CountingStream(OutputStream out) { this.out = out; }

        @Override public void write(int b) throws IOException { out.write(b); count++; }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override public void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException { out.close(); }
    }

    // ── ③ ART 验收 ────────────────────────────────────────────────────────

    private static void artCheck(Context ctx, File mat, Ingest r1, StringBuilder sb) {
        boolean ro = mat.setReadOnly();
        sb.append("   setReadOnly = ").append(ro).append("（Android 14+ 加载未安装 dex 的硬要求）\n");

        // 1) 包本身是合法 zip，且 classes.dex 的 magic 在
        String dexHead = "?";
        int zipN = -1;
        try {
            ZipFile zf = new ZipFile(mat);
            try {
                zipN = countZip(zf);
                ZipEntry de = zf.getEntry("classes.dex");
                if (de == null) {
                    dexHead = "(包内没有 classes.dex)";
                } else {
                    byte[] h = new byte[8];
                    InputStream in = zf.getInputStream(de);
                    try {
                        int k = 0;
                        while (k < h.length) {
                            int n = in.read(h, k, h.length - k);
                            if (n <= 0) break;
                            k += n;
                        }
                    } finally {
                        in.close();
                    }
                    dexHead = "magic=" + (h[0] == 'd' && h[1] == 'e' && h[2] == 'x' ? "dex ✓" : "BAD")
                            + " entrySize=" + de.getSize();
                }

                // 全条目读一遍：ZipFile 读到尾会校验 CRC-32，任何一条写坏都会在这里抛异常
                long tCrc = System.nanoTime();
                long crcOk = 0, crcBytes = 0;
                String crcErr = null;
                try {
                    Enumeration<? extends ZipEntry> ce = zf.entries();
                    while (ce.hasMoreElements()) {
                        ZipEntry x = ce.nextElement();
                        if (x.isDirectory()) continue;
                        InputStream xi = zf.getInputStream(x);
                        try {
                            byte[] b = new byte[BUF];
                            int k;
                            while ((k = xi.read(b)) > 0) crcBytes += k;
                        } finally {
                            xi.close();
                        }
                        crcOk++;
                    }
                } catch (Throwable t) {
                    crcErr = t.getClass().getSimpleName() + ": " + brief(t.getMessage());
                }
                sb.append("   zip 逐条读通（CRC 校验） ").append(crcOk).append(" 条 / ")
                  .append(fmt(crcBytes)).append("  ").append(ms(tCrc)).append(" ms  ")
                  .append(crcErr == null ? "全部 OK" : "FAIL " + crcErr).append('\n');
            } finally {
                zf.close();
            }
        } catch (Throwable t) {
            dexHead = "zip 打不开：" + t;
        }
        sb.append("   重组包 zip 条目 ").append(zipN).append("  ").append(dexHead).append('\n');

        // 2) DexClassLoader：ART 到底认不认这个 dex
        Class<?> gameCls = null;
        List<String> fails = new ArrayList<String>();
        for (int i = 1; i <= 3 && gameCls == null; i++) {
            try {
                DexClassLoader d = new DexClassLoader(mat.getAbsolutePath(),
                        ctx.getCodeCacheDir().getAbsolutePath(), null,
                        CasEntryBench.class.getClassLoader().getParent());
                gameCls = d.loadClass(Injector.GAME_ACTIVITY);
            } catch (Throwable t) {
                fails.add("第" + i + "次 " + t.getClass().getSimpleName() + ": " + brief(t.getMessage()));
            }
        }
        sb.append("   DexClassLoader.loadClass(").append(Injector.GAME_ACTIVITY).append(") = ")
          .append(gameCls != null ? "OK（ART 认这个 dex）" : "FAIL").append('\n');
        for (String f : fails) sb.append("     ").append(f).append('\n');

        // 3) AssetManager：assets 能不能读
        AssetManager am = null;
        String how;
        try {
            am = AssetManager.class.getDeclaredConstructor().newInstance();
            how = "新建 AssetManager";
        } catch (Throwable t) {
            am = ctx.getAssets();
            how = "回落 ctx.getAssets()（" + t.getClass().getSimpleName() + "）";
        }
        int cookie = -1;
        String addErr = null;
        try {
            cookie = Reflect.addAssetPath(am, mat);
        } catch (Throwable t) {
            addErr = String.valueOf(t);
        }
        sb.append("   ").append(how).append("  addAssetPath cookie=").append(cookie)
          .append(addErr == null ? "" : "  FAIL: " + brief(addErr)).append('\n');
        sb.append("   probeAsset = ").append(Injector.probeAsset(am)).append('\n');

        String big = biggestAsset(r1);
        String[] paths = {"cursors/cursor.png", big == null ? "sprites/sprites3.png" : big};
        for (String p : paths) {
            if (p == null) continue;
            // openFd：图集走它 ⇒ 同时验证「全 STORED + 4 字节对齐」真的成立
            try {
                AssetFileDescriptor afd = am.openFd(p);
                long len = afd.getLength();
                long off = afd.getStartOffset();
                afd.close();
                sb.append("   openFd(").append(p).append(") = OK  length=").append(fmt(len))
                  .append("  startOffset=").append(off)
                  .append(off % 4 == 0 ? "（4 对齐 ✓）" : "（未对齐 ✗）").append('\n');
            } catch (Throwable t) {
                sb.append("   openFd(").append(p).append(") = FAIL ").append(t.getClass().getSimpleName())
                  .append(": ").append(brief(t.getMessage())).append('\n');
            }
            try {
                InputStream in = am.open(p);
                long n = skipCount(in);
                in.close();
                sb.append("   open(").append(p).append(") = OK  ").append(fmt(n)).append('\n');
            } catch (Throwable t) {
                sb.append("   open(").append(p).append(") = FAIL ").append(t.getClass().getSimpleName())
                  .append(": ").append(brief(t.getMessage())).append('\n');
            }
        }
    }

    /** 原包里最大的 assets 条目（用来验 STORED 大条目；也顺带说明它是不是图集） */
    private static String biggestAsset(Ingest r) {
        String best = null;
        long size = -1;
        for (Entry e : r.entries) {
            if (!e.path.startsWith("assets/")) continue;
            if (e.size > size) { size = e.size; best = e.path.substring("assets/".length()); }
        }
        return best;
    }

    // ── 目录 / 符号链接能不能当资产源（旧计划 4.4b 一直挂着的两个探针）──────

    /**
     * 如果能「重定向」，就不用物化那 83 MB —— 但也就不再需要按条目 CAS 了：
     * 对象池就是解开的目录树，ART 直接读。
     *
     * <p>这条一直没测，是因为 `AssetManager.addAssetPath` 的文档只讲 APK/jar。
     * 现在一次性打掉三种形态：纯目录、symlink→APK、dex 的目录/裸文件。
     */
    static void dirRedirect(Context ctx, StringBuilder sb) throws Exception {
        File hub = Paths.privateDir(ctx);
        File base = new File(hub, "casentry-dir");
        deleteTree(base);
        base.mkdirs();
        File src = pick(listApks(new File(hub, "import")), "v160.apk");
        sb.append("设备 ").append(android.os.Build.MODEL).append(" / Android ")
          .append(android.os.Build.VERSION.RELEASE).append("（API ")
          .append(android.os.Build.VERSION.SDK_INT).append("）\n");
        sb.append("源 ").append(src.getName()).append("  ").append(fmt(src.length())).append('\n');

        ZipFile zf = new ZipFile(src);
        try {
            // A) 纯目录树（解开的 assets 目录）
            File tree = new File(base, "tree");
            extractTo(zf, "cursors/cursor.png", new File(tree, "cursors/cursor.png"));
            extractTo(zf, "sprites/sprites.png", new File(tree, "sprites/sprites.png"));
            extractTo(zf, "resources.arsc", new File(tree, "resources.arsc"));
            sb.append("\nA) addAssetPath(<纯目录>)  目录形态能不能当资产源\n");
            assetProbe(ctx, tree, "cursors/cursor.png", sb);

            // B) symlink → 真 APK（物化包能不能只是个链接，不占空间）
            File link = new File(base, "link.apk");
            String err = null;
            try {
                android.system.Os.symlink(src.getAbsolutePath(), link.getAbsolutePath());
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + brief(t.getMessage());
            }
            sb.append("\nB) addAssetPath(<symlink→APK>)  物化包能不能只是条链接\n");
            if (err != null) {
                sb.append("   symlink 失败: ").append(err).append('\n');
            } else {
                sb.append("   symlink -> ").append(src.getName())
                  .append("  isFile=").append(link.isFile())
                  .append("  length=").append(link.length()).append('\n');
                assetProbe(ctx, link, "cursors/cursor.png", sb);
            }

            // C) dex 的载体形态：目录 / 裸 .dex
            File dexDir = new File(base, "dexdir");
            File dexFile = new File(dexDir, "classes.dex");
            extractTo(zf, "classes.dex", dexFile);
            sb.append("\nC) DexClassLoader 的载体形态（dex 不用打包也能喂）\n");
            dexProbe(ctx, dexDir, "目录 " + dexDir.getName() + "/", sb);
            dexProbe(ctx, dexFile, "裸文件 classes.dex", sb);
        } finally {
            zf.close();
        }
        deleteTree(base);
        sb.append("\n已清理 ").append(base.getName()).append("：").append(base.exists() ? "残留！" : "无残留").append('\n');
    }

    /** 新建 AssetManager（失败回落 ctx.getAssets()）并挂 path，再试读一个 assets */
    private static void assetProbe(Context ctx, File path, String asset, StringBuilder sb) {
        AssetManager am;
        try {
            am = AssetManager.class.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            am = ctx.getAssets();
            sb.append("   （AssetManager 新建失败，回落 ctx.getAssets()：" ).append(t).append("）\n");
        }
        int cookie = -1;
        String err = null;
        try {
            cookie = Reflect.addAssetPath(am, path);
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + brief(t.getMessage());
        }
        sb.append("   addAssetPath = ").append(err != null ? "抛异常" : String.valueOf(cookie))
          .append(err != null ? "（" + err + "）" : (cookie == 0 ? "（0 = 未接受）" : "")).append('\n');
        sb.append("   probeAsset = ").append(Injector.probeAsset(am)).append('\n');
        try {
            InputStream in = am.open(asset);
            long n = skipCount(in);
            in.close();
            sb.append("   open(").append(asset).append(") = OK ").append(fmt(n)).append('\n');
        } catch (Throwable t) {
            sb.append("   open(").append(asset).append(") = FAIL ").append(t.getClass().getSimpleName())
              .append(": ").append(brief(t.getMessage())).append('\n');
        }
    }

    private static void dexProbe(Context ctx, File path, String label, StringBuilder sb) {
        Class<?> c = null;
        String err = null;
        for (int i = 0; i < 2 && c == null; i++) {
            try {
                DexClassLoader d = new DexClassLoader(path.getAbsolutePath(),
                        ctx.getCodeCacheDir().getAbsolutePath(), null,
                        CasEntryBench.class.getClassLoader().getParent());
                c = d.loadClass(Injector.GAME_ACTIVITY);
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + brief(t.getMessage());
            }
        }
        sb.append("   ").append(label).append(" → loadClass = ")
          .append(c != null ? "OK" : "FAIL " + (err == null ? "" : err)).append('\n');
    }

    /** 把 zip 里一个条目解到文件（目录自动建）；`name` 会先按原样找、再补 `assets/` 前缀找 */
    private static void extractTo(ZipFile zf, String name, File dst) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) e = zf.getEntry("assets/" + name);
        if (e == null) throw new IOException("源包没有 " + name + "（也没有 assets/" + name + "）");
        File par = dst.getParentFile();
        if (par != null && !par.isDirectory()) par.mkdirs();
        InputStream in = zf.getInputStream(e);
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), BUF);
            try {
                byte[] buf = new byte[BUF];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static List<File> listApks(File dir) {
        List<File> out = new ArrayList<File>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) out.add(f);
        }
        return out;
    }

    private static File pick(List<File> apks, String want) {
        for (File f : apks) if (want.equalsIgnoreCase(f.getName())) return f;
        File best = null;
        for (File f : apks) if (best == null || f.length() > best.length()) best = f;
        return best;
    }

    private static String stemOf(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static File objectPath(File objects, String sha) {
        return new File(new File(objects, sha.substring(0, 2)), sha.substring(2));
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Cas.hex(md.digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("sha256 不可用", e);
        }
    }

    private static int countZip(ZipFile zf) {
        int n = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) { en.nextElement(); n++; }
        return n;
    }

    private static long skipCount(InputStream in) throws IOException {
        byte[] buf = new byte[BUF];
        long n = 0;
        int k;
        while ((k = in.read(buf)) > 0) n += k;
        return n;
    }

    private static long treeSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) n += treeSize(c);
        return n;
    }

    private static long countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) n += countFiles(c);
        return n;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteTree(c);
        } else {
            f.setWritable(true);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static long ms(long t0) {
        return Math.max(1L, (System.nanoTime() - t0) / 1000000L);
    }

    private static String rate(long bytes, long ms) {
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f MB/s", mb * 1000.0 / ms);
    }

    private static String pct(long part, long all) {
        return all <= 0 ? "-" : String.format(Locale.US, "%.1f%%", part * 100.0 / all);
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
