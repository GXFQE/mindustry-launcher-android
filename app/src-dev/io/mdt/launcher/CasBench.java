package io.mdt.launcher;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * dev 探针：把**游戏本体**（导入的游戏 APK）收进 CAS 的**效果与速率**。
 *
 * <p>只读现有 `app_hub/import/*.apk`，池建在**内部**存储的临时目录
 * `app_hub/casbench/`（游戏本体只能住内部存储），跑完删净。
 *
 * <p>为什么要跑这一趟：CAS 目前只服务备份（池在**外部** hub），而"游戏本体走 CAS"
 * 是待定的地基工程。动手前要知道三件事：
 * <ol>
 *   <li>一次 put 到底多久（80 MB 量级）、瓶颈在 I/O 还是 sha256；</li>
 *   <li>去重命中（同一份 APK 第二次入库）要多久 —— {@link Cas#put} 的实现是
 *       "照写 tmp、算完 sha 发现对象已在、再把 tmp 删掉"，这一步白写一整份；</li>
 *   <li>三槽共用一份本体能省多少空间（账要按对象池实际占用算）。</li>
 * </ol>
 *
 * <p>⚠️ page cache：跑完第 ③ 步后源文件基本都在 cache 里，后面几步都是热态。
 * 报告自己标注了，别把热态数字当冷态用。
 */
final class CasBench {

    private CasBench() {}

    private static final int BUF = 65536;

    static void run(Context ctx, StringBuilder sb) throws Exception {
        File hub = Paths.privateDir(ctx);
        File importDir = new File(hub, "import");
        File src = pickApk(importDir);
        if (src == null) {
            sb.append("import/ 里没有 .apk，先导入一个游戏包再测\n");
            return;
        }
        long size = src.length();

        sb.append("设备 ").append(android.os.Build.MODEL).append(" / Android ")
          .append(android.os.Build.VERSION.RELEASE).append('\n');
        sb.append("源 \"").append(src.getName()).append("\" = ").append(fmt(size)).append('\n');
        sb.append("内部存储可用 ").append(fmt(hub.getUsableSpace())).append('\n');

        listImports(importDir, sb);

        File base = new File(hub, "casbench");
        deleteTree(base);
        base.mkdirs();
        File dst = new File(base, "dst.bin");
        File copyDst = new File(base, "copy.bin");

        // ① 纯拷贝基线（64K 流、不算哈希）
        long t = System.nanoTime();
        copy(src, copyDst);
        long copyMs = ms(t);
        long copyBytes = copyDst.length();
        copyDst.delete();
        sb.append("① 纯拷贝 ").append(fmt(copyBytes)).append("  ").append(copyMs)
          .append(" ms  ").append(rate(copyBytes, copyMs)).append('\n');

        Cas cas = new Cas(base);

        // ② 只算 sha256（读 + 哈希、不落盘）
        t = System.nanoTime();
        String shaOnly = cas.sha256Of(src);
        long shaMs = ms(t);
        sb.append("② 只算 sha256  ").append(shaMs).append(" ms  ").append(rate(size, shaMs))
          .append("  ").append(shaOnly.substring(0, 12)).append("…\n");

        // ③ 首次入库（读 + 哈希 + 写 tmp + rename）
        t = System.nanoTime();
        Cas.Stored st = cas.put(src, null);
        long putMs = ms(t);
        Cas.Pool pool1 = cas.scan(Collections.singleton(st.sha));
        sb.append("③ 首次入库 new=").append(st.isNew).append("  ").append(fmt(st.bytes))
          .append("  ").append(putMs).append(" ms  ").append(rate(size, putMs)).append('\n');
        sb.append("   池：").append(pool1.objects).append(" 个对象 / ").append(fmt(pool1.bytes))
          .append("（对象文件 ").append(fmt(cas.objectPath(st.sha).length())).append("）\n");

        // ④ 再次入库（去重命中：仍写一整份 tmp，再删掉）
        t = System.nanoTime();
        Cas.Stored again = cas.put(src, null);
        long dedupMs = ms(t);
        Cas.Pool pool2 = cas.scan(Collections.singleton(again.sha));
        sb.append("④ 再次入库 new=").append(again.isNew).append("  ").append(dedupMs)
          .append(" ms  ").append(rate(size, dedupMs))
          .append("（去重路径：tmp 写完再删，白写 ").append(fmt(size)).append("）\n");
        sb.append("   池：").append(pool2.objects).append(" 个对象 / ").append(fmt(pool2.bytes))
          .append("（应与 ③ 相同）\n");

        // ⑤ has(sha) 快查（有清单 sha 时应当先走这条，而不是 put）
        int n = 2000;
        t = System.nanoTime();
        boolean hit = false;
        for (int i = 0; i < n; i++) hit = cas.has(again.sha);
        long hasNs = System.nanoTime() - t;
        sb.append("⑤ has(sha) 命中=").append(hit).append("  ").append(n).append(" 次共 ")
          .append(hasNs / 1000000).append(" ms（单次 ")
          .append(String.format(Locale.US, "%.3f", hasNs / 1e6 / n)).append(" ms）\n");

        // ⑥ 还原 verify=true（读 + 哈希 + 写 + rename）
        t = System.nanoTime();
        cas.getTo(again.sha, dst, true);
        long getMs = ms(t);
        sb.append("⑥ 还原 verify=true  ").append(fmt(dst.length())).append("  ").append(getMs)
          .append(" ms  ").append(rate(size, getMs)).append('\n');

        // ⑦ 还原 verify=false（跳过哈希）
        t = System.nanoTime();
        cas.getTo(again.sha, dst, false);
        long getMs2 = ms(t);
        sb.append("⑦ 还原 verify=false  ").append(getMs2).append(" ms  ")
          .append(rate(size, getMs2)).append('\n');

        // ⑧ GC 扫描
        t = System.nanoTime();
        int removed = cas.garbageCollect(Collections.singleton(again.sha));
        long gcMs = ms(t);
        sb.append("⑧ GC 扫描 removed=").append(removed).append("  ").append(gcMs).append(" ms\n");

        // ⑨ 空间效果：三槽共用一份
        long logical = size * 3;
        sb.append("⑨ 三槽共用一份：逻辑 ").append(fmt(logical)).append(" / 池实际 ")
          .append(fmt(pool1.bytes)).append("，省 ")
          .append(String.format(Locale.US, "%.1f%%", (logical - pool1.bytes) * 100.0 / logical))
          .append("\n");
        sb.append("⑩ 换算：首次入库约等于「纯拷贝 + 只算 sha」的 ")
          .append(String.format(Locale.US, "%.2f 倍（%d ms vs %d+%d ms）",
                  putMs / (double) Math.max(1, copyMs + shaMs), putMs, copyMs, shaMs))
          .append('\n');

        dst.delete();
        deleteTree(base);
        sb.append("池已删净（").append(base.exists() ? "残留！" : "无残留").append("）\n");
        sb.append("★ ③ 之后源文件已进 page cache ⇒ ④~⑦ 是热态；③ 的冷热取决于刚开机还是刚读过。\n");
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static File pickApk(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File best = null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".apk")) continue;
            if ("v160.apk".equals(n)) return f;          // 常用基准：固定一份，便于前后对比
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    private static void listImports(File dir, StringBuilder sb) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        List<File> list = new ArrayList<File>();
        for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) list.add(f);
        long total = 0;
        for (File f : list) total += f.length();
        sb.append("import/ 共 ").append(list.size()).append(" 个包 / ").append(fmt(total)).append("：\n");
        for (File f : list) {
            sb.append("  ").append(f.getName()).append("  ").append(fmt(f.length())).append('\n');
        }
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(src), BUF);
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), BUF);
            try {
                byte[] b = new byte[BUF];
                int k;
                while ((k = in.read(b)) > 0) out.write(b, 0, k);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteTree(c);
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

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
