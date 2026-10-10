package io.mdt.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出到共享存储（F6）。模块边界：依赖 Util，被 UI 调用。
 *
 * 存在理由（对称性缺口）：{@link Importer} / {@link Msav} 只解决了"进"，
 * 而 Android 11+ 起 `Android/data/&lt;包名&gt;/` 对文件管理器和 MTP 都**不可见**
 * （实测：只有 adb 进得去）⇒ **在启动器里**没有任何出口。
 * ⚠️ **2026-10-06 订正**：这句话原来写的是"用户没有任何官方途径把存档拿出来"，**是错的** ——
 * 游戏**自己**的存档菜单里就有导出 / 导入（bundle 键 `save.export` / `save.import.fail` /
 * `save.import.invalid`），只是要**先进游戏**、**一次一份**、没有"整个槽"的概念。
 * F6 补的是**启动器这一侧的对称出口** + 整槽打包，不是"从无到有"。
 * SAF 的 `ACTION_CREATE_DOCUMENT` 是这条路上唯一不需要任何存储权限的出口：
 * 用户自己选落点，我们只拿到一个 `content://` 写句柄，全程不碰路径、不碰 SELinux 标签
 * （与 {@link Importer} 的输入侧完全对称）。
 *
 * ★ 三条纪律（都是探针/实测换来的，别"优化"掉）：
 *   ① **绝不全量读进内存** —— 存档几 MB、mods 几十 MB，一律 64KB 缓冲流式；
 *   ② **文件名保留原始非 ASCII** —— 存档名就是玩家在游戏里看到的名字（见 {@link Msav#safeName}），
 *      zip 里的条目名同理（`saves/困难模式.msav` 不能变成 `saves/______.msav`）；
 *   ③ **拿不到可写流要抛明确异常** —— 个别 ROM 对 `ACTION_CREATE_DOCUMENT` 返回的 uri 不可写，
 *      静默失败会让用户以为"导出成功了"。
 */
public final class Exporter {

    private static final String TAG = "MDTLauncher";
    private static final int BUF = 65536;

    /** zip 打包结果（给界面报数用） */
    public static final class Result {
        public int files;        // 写进去的文件条目数（不含目录条目）
        public long rawBytes;    // 原始内容总字节
        public long outBytes;    // 落盘 zip 的字节数（压缩后）
    }

    private Exporter() {}

    // ── 出口 intent ───────────────────────────────────────────────────────

    /**
     * `ACTION_CREATE_DOCUMENT` —— 让用户选"存到哪"。
     * ⚠️ `EXTRA_TITLE` 只是**建议值**（用户可改），但它是唯一能把原始文件名递过去的通道；
     *    不带它的话文件名会变成 `document(1)` 之类，非 ASCII 名就更没法看了。
     */
    public static Intent createDoc(String suggestedName, String mime) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        if (suggestedName != null && !suggestedName.isEmpty()) {
            i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        }
        return i;
    }

    // ── 单文件写出 ────────────────────────────────────────────────────────

    /** 把一个文件流式写进 SAF 目标。返回写入字节数。 */
    public static long writeFile(Context ctx, Uri uri, File src) throws IOException {
        if (src == null || !src.isFile()) throw new IOException(Trans.get(ctx, R.string.exp_err_src_missing_fmt, String.valueOf(src)));
        InputStream in = new FileInputStream(src);
        OutputStream out = null;
        long n = 0;
        try {
            out = openOut(ctx, uri);
            byte[] buf = new byte[BUF];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
            }
            out.flush();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
        Log.i(TAG, "export file ok: " + src.getName() + " -> " + n + " B");
        return n;
    }

    // ── 容器条目写出（F10：地图导出） ─────────────────────────────────────

    /**
     * 把 **zip / APK 容器里的一条条目**流式写进 SAF 目标（返回字节数）。
     *
     * 存在理由：地图有三个来源，其中两个**不是文件**——游戏 APK 里的 `assets/maps/**.msav`
     * 与模组包里的 `maps/**.msav`（见 {@link Maps}）。把它们导出来是"把这张图拿出来"的
     * 唯一途径（用户在文件管理器里根本看不到它们）。
     *
     * ★ 与 {@link #writeFile} 同一条纪律：**绝不全量读进内存** ——
     *   游戏自带的大图也是几百 KB~几 MB，一律 64KB 缓冲流式。
     */
    public static long writeEntry(Context ctx, Uri uri, File container, String entry)
            throws IOException {
        OutputStream out = null;
        long n;
        try {
            out = openOut(ctx, uri);
            n = copyEntry(ctx, container, entry, out);
            out.flush();
        } finally {
            closeQuietly(out);
        }
        Log.i(TAG, "export entry ok: " + (container == null ? "?" : container.getName())
                + "!/" + entry + " -> " + n + " B");
        return n;
    }

    /**
     * 条目 → 输出流的流式复制。
     * ★ 刻意与 SAF 解耦（只吃一个 {@link OutputStream}）⇒ 自检能直接喂一个
     *   `ByteArrayOutputStream` 把"字节真的一样"钉死，不必依赖真机上的文件选择器。
     */
    static long copyEntry(Context ctx, File container, String entry, OutputStream out)
            throws IOException {
        if (container == null || !container.isFile()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_pkg_missing_fmt, String.valueOf(container)));
        }
        if (entry == null || entry.trim().isEmpty()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_no_entry));
        }
        java.util.zip.ZipFile zf = null;
        InputStream in = null;
        long n = 0;
        try {
            zf = new java.util.zip.ZipFile(container);
            java.util.zip.ZipEntry ze = zf.getEntry(entry);
            if (ze == null) throw new IOException(Trans.get(ctx, R.string.exp_err_entry_missing_fmt, entry));
            in = zf.getInputStream(ze);
            byte[] buf = new byte[BUF];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
            }
        } finally {
            closeQuietly(in);
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    // ── 批量导出（多份 → 一个 zip；第 127 轮） ───────────────────────────────

    /**
     * 批量导出里的**一个条目**。三种来源（与单份导出那两条路一一对应）：
     * <pre>
     *   ① {@link #ofFile}   —— 槽里的真文件（存档 / 本槽地图 / 本槽蓝图 / 文件形态的模组）；
     *   ② {@link #ofEntry}  —— **容器里的一条**（游戏 APK 里的 `assets/maps/**`、模组包里的
     *                          `maps/**` / `schematics/**`）—— 用户在文件管理器里根本看不见它们；
     *   ③ {@link #ofDir}    —— **目录形态的模组**：外层 zip 里放一个**嵌套 zip**
     *                          （`<目录名>.zip`，内容是那个目录**根下**的全部文件）。
     * </pre>
     *
     * ★ 为什么目录形态要包一层嵌套 zip（而不是把内容平铺进外层 zip）：
     *   "外层 zip 里每一条 = 单份导入时用户会选到的那个文件" —— 这条不变量让
     *   **批量导入只有一条路**（把外层解开、逐条走原来的单份导入，见 {@link BatchIo}）。
     *   平铺的话，目录模组的那些文件就再也拼不回一个包了。
     *
     * ⚠️ 条目名**由调用方给**（`name`），不是从路径推 —— 存档 / 地图 / 蓝图的名字就是
     *   用户看到的那个名字（见 {@link Msav#safeName}），绝不能"安全化"成 ASCII。
     */
    public static final class Src {
        public final File file;
        public final File container;
        public final String entry;
        public final File dir;
        private final String name;

        private Src(String name, File file, File container, String entry, File dir) {
            this.name = name;
            this.file = file;
            this.container = container;
            this.entry = entry;
            this.dir = dir;
        }

        /** 槽里的真文件（必须存在；不存在时这一条在写入时会被跳过） */
        public static Src ofFile(File f, String name) {
            return new Src(name, f, null, null, null);
        }

        /** 容器（APK / 模组包）里的一条 */
        public static Src ofEntry(File container, String entry, String name) {
            return new Src(name, null, container, entry, null);
        }

        /** 目录形态的模组：外层 zip 里写成 `<name>` 这一个**嵌套 zip**条目 */
        public static Src ofDir(File dir, String name) {
            return new Src(name, null, null, null, dir);
        }

        /** 这条其实没有源（调用方扫到的条目已被删掉之类）—— 写入时**跳过**，不当成错误 */
        public boolean empty() {
            if (dir != null) return false;
            if (file != null) return !file.isFile();
            return container == null || entry == null;
        }

        String entryName() {
            return name;
        }

        long lastModified() {
            if (file != null) return file.lastModified();
            if (dir != null) return dir.lastModified();
            return container == null ? 0 : container.lastModified();
        }
    }

    /**
     * 把一批条目打成一个 zip 写进 SAF 目标（**批量导出的唯一出口**）。
     *
     * ★ 与 {@link #zipTo}（整槽备份那条）的分工：那个吃"目录树"，这个吃"一份一份的东西"
     *   —— 而"一份"可能是容器里的一条，也可能是一个目录（目录形态的模组）。
     */
    public static Result zipSources(Context ctx, Uri uri, List<Src> srcs) throws IOException {
        Result r = new Result();
        Counting counter = new Counting(openOut(ctx, uri));
        zipSourcesTo(ctx, counter, srcs, r);
        r.outBytes = counter.n;
        Log.i(TAG, "export batch zip ok: files=" + r.files + " raw=" + r.rawBytes
                + " out=" + r.outBytes);
        return r;
    }

    /**
     * 同上，但吃**任意输出流** —— 刻意与 SAF 解耦（与 {@link #copyEntry} 同一条理由）：
     * 自检能直接喂一个 `ByteArrayOutputStream` 把"条目名 / 字节 / 重名去重"钉死，
     * 不必依赖真机上的文件选择器。
     */
    static Result zipSourcesTo(Context ctx, OutputStream raw, List<Src> srcs) throws IOException {
        return zipSourcesTo(ctx, raw, srcs, new Result());
    }

    /** 带计数器的重载（{@link #zipSources} 用它把"压缩后字节数"取回来） */
    static Result zipSourcesTo(Context ctx, OutputStream raw, List<Src> srcs, Result r)
            throws IOException {
        if (r == null) r = new Result();
        if (srcs == null || srcs.isEmpty()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_no_src));
        }
        java.util.List<String> used = new java.util.ArrayList<>();
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(raw, BUF));
        int written = 0;
        try {
            zos.setLevel(Deflater.BEST_SPEED);      // 大目录（模组几十 MB）速度优先，同 zipTo
            for (Src s : srcs) {
                if (s == null || s.empty()) continue;
                String name = uniqueName(used, s.entryName());
                used.add(name);
                ZipEntry e = new ZipEntry(name);
                // ⚠️ 同 addTree：1980 之前 / 2107 之后的时间会让 setTime 抛 IllegalArgumentException
                long t = s.lastModified();
                if (t >= 315532800000L && t <= 4354819199000L) e.setTime(t);
                zos.putNextEntry(e);
                if (s.dir != null) {
                    // ★ 目录形态：内层树用**另一个计数器** —— `Result.files` 的语义是
                    //   「这一批有几**份**」（界面那句「已导出 N 份」直接读它），
                    //   而一个目录模组里可能有几十个文件 ⇒ 混进来就会报成"导出了 40 份"。
                    //   内层内容仍然计进 rawBytes（那是"原始字节数"，只进日志）。
                    Result inner = new Result();
                    addTreeAsZip(s.dir, zos, inner);
                    r.rawBytes += inner.rawBytes;
                } else if (s.file != null) {
                    r.rawBytes += copyInto(s.file, zos);
                } else {
                    r.rawBytes += copyEntry(ctx, s.container, s.entry, zos);
                }
                zos.closeEntry();
                r.files++;
                written++;
            }
            if (written == 0) throw new IOException(Trans.get(ctx, R.string.exp_err_no_src));
        } finally {
            closeQuietly(zos);      // 必须关：zip 的中央目录在 close 时才写
        }
        return r;
    }

    /**
     * 把一个**目录**写成外层 zip 里的**一个嵌套 zip**条目。
     *
     * 🔴 关键在 {@link NonClosing}：内层 `ZipOutputStream.close()` 会把中央目录写出来、
     *   然后 `close()` 底层流 —— 底层流是**外层**的 ZipOutputStream，直接关掉就等于把外层
     *   也结束了（后面所有条目都没了，而且不会有任何报错）。包一层"只 flush 不 close"的流，
     *   内层的 close 就退化成"把中央目录刷进外层当前条目"。
     */
    private static void addTreeAsZip(File dir, ZipOutputStream outer, Result r) throws IOException {
        ZipOutputStream inner = new ZipOutputStream(new NonClosing(outer));
        try {
            inner.setLevel(Deflater.BEST_SPEED);
            addTree(dir, dir, inner, r);        // 条目名 = 相对该目录**根**的路径（游戏认这个形状）
        } finally {
            inner.close();                      // 只 flush（见上）
        }
    }

    /** 只 flush、不 close 的包装流（给嵌套 zip 用，见 {@link #addTreeAsZip}） */
    private static final class NonClosing extends java.io.FilterOutputStream {
        NonClosing(OutputStream o) {
            super(o);
        }

        @Override public void close() throws IOException {
            flush();
        }
    }

    /** 文件 → 输出流（返回字节数；不动源文件） */
    private static long copyInto(File f, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[BUF];
            long n = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
            }
            return n;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * zip 条目名**必须唯一** —— 同名条目解出来只剩一条（后面那条盖前面），而且**不报错**
     * （导出的 zip 里少一份、用户要到解包时才发现）。
     *
     * 撞名的真实来源：同一批里选了两份同名地图（一份本槽、一份模组自带），
     * 或者用户一次选了来源不同但名字一样的存档。
     * 去重规则 = `名字 (2).msav`（在**扩展名之前**插入，与桌面系统"复制一份"的习惯一致）。
     */
    static String uniqueName(java.util.List<String> used, String raw) {
        String base = flatName(raw);
        if (used == null || !used.contains(base)) return base;
        int dot = base.lastIndexOf('.');
        String head = dot > 0 ? base.substring(0, dot) : base;
        String tail = dot > 0 ? base.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String c = head + " (" + i + ")" + tail;
            if (!used.contains(c)) return c;
        }
        return head + "-" + System.nanoTime() + tail;
    }

    /**
     * 条目名里不许出现路径分隔符与控制字符。
     * ★ 我们只**写** zip、不写盘，所以这里不是防路径穿越（那是解包侧的事，见
     *   {@link BatchIo} 的展开逻辑），纯粹是"名字要干净、别把一个条目变成目录"。
     */
    static String flatName(String n) {
        String s = n == null ? "" : n.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20) sb.append('_');
            else sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "file" : out;
    }

    // ── 文本写出（F20：日志导出） ─────────────────────────────────────────

    /**
     * 把一段**已经在内存里**的文本写进 SAF 目标（UTF-8，不带 BOM）。
     *
     * ★ 为什么需要它（而不是"先落成临时文件再 {@link #writeFile}"）：
     *   日志导出的内容是**拼出来的**（三部分 + 环境信息），磁盘上根本没有这个文件；
     *   为了它先写一份临时文件，等于把"用户数据只落一处"的纪律反过来用。
     *
     * ⚠️ 与 {@link #writeFile} 一样走 {@link #openOut}（先 `"wt"` 截断）——
     *   用户完全可能在 SAF 里**选中一个已存在的 txt**，不截断就会把新日志
     *   盖在旧内容上面、尾巴留着旧的，而**看起来是一次正常的导出**。
     */
    public static long writeText(Context ctx, Uri uri, String text) throws IOException {
        byte[] b = (text == null ? "" : text).getBytes("UTF-8");
        OutputStream out = null;
        try {
            out = openOut(ctx, uri);
            out.write(b);
            out.flush();
        } finally {
            closeQuietly(out);
        }
        Log.i(TAG, "export text ok: " + b.length + " B -> " + uri);
        return b.length;
    }

    // ── 目录打包 ──────────────────────────────────────────────────────────

    /**
     * 把若干目录/文件树打成一个 zip 写进 SAF 目标。
     *
     * @param base  相对路径的基准（条目名 = 相对 base 的路径），一般传槽目录
     * @param roots 要打包的顶级条目（缺失的自动跳过）
     */
    public static Result zipTo(Context ctx, Uri uri, File base, List<File> roots)
            throws IOException {
        Result r = new Result();
        Counting counter = new Counting(openOut(ctx, uri));
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(counter, BUF));
        try {
            // 大目录（mods 几十 MB）速度优先：压缩比换时间，用户等着不烦
            zos.setLevel(Deflater.BEST_SPEED);
            if (roots != null) {
                for (File root : roots) {
                    if (root == null || !root.exists()) continue;
                    addTree(base, root, zos, r);
                }
            }
        } finally {
            closeQuietly(zos);   // 必须关：zip 的中央目录在 close 时才写
        }
        r.outBytes = counter.n;
        Log.i(TAG, "export zip ok: files=" + r.files + " raw=" + r.rawBytes
                + " out=" + r.outBytes);
        return r;
    }

    private static void addTree(File base, File f, ZipOutputStream zos, Result r)
            throws IOException {
        if (skip(f)) return;

        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null || kids.length == 0) {
                // 空目录也留一条：否则解包后整个目录结构消失
                String rel = relOf(base, f);
                zos.putNextEntry(new ZipEntry(rel + "/"));
                zos.closeEntry();
                return;
            }
            for (File k : kids) addTree(base, k, zos, r);
            return;
        }

        String rel = relOf(base, f);
        ZipEntry e = new ZipEntry(rel);
        // ⚠️ ZipEntry.setTime 对 1980 之前 / 2107 之后的时间会抛 IllegalArgumentException
        //    （坏掉的 mtime 在用户导入过的文件上真出现过）；越界就不带时间，不带也能解。
        long t = f.lastModified();
        if (t >= 315532800000L && t <= 4354819199000L) e.setTime(t);

        zos.putNextEntry(e);
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
                r.rawBytes += n;
            }
        } finally {
            closeQuietly(in);
        }
        zos.closeEntry();
        r.files++;
    }

    /**
     * 不打包的东西：临时件与隐藏件（游戏自己也可能留 `.tmp` / `.nomedia`）。
     *
     * ★ F19：判据**搬到 {@link Data#contentSkipped}** —— 备份那条路也要用同一份，
     *   "导出的 zip 与备份口径不一致"是**没有任何症状**的坏状态（见那条注释）。
     */
    private static boolean skip(File f) {
        return Data.contentSkipped(f);
    }

    /**
     * 条目名 = 相对 base 的路径（统一用 `/`，zip 规范如此）。
     * ★ F19：实现搬到了 {@link Util#relOf} —— {@link Backup} 的清单 rel 也要用同一份
     *   （两份实现里只有这份有"顶层兜底"，见那条注释）。
     */
    private static String relOf(File base, File f) {
        return Util.relOf(base, f);
    }

    // ── 底层 ──────────────────────────────────────────────────────────────

    /**
     * 拿一个可写流。
     * ⚠️ 先用 `"wt"`（truncate）—— 用户完全可能在 SAF 里**选中一个已存在的文件**，
     *    不 truncate 会把新数据覆盖在旧内容上面、尾巴留着旧的（曾经的经典事故）。
     *    个别老 provider 不认 `"wt"` 会抛，这时回落默认模式（总比直接失败强）。
     */
    private static OutputStream openOut(Context ctx, Uri uri) throws IOException {
        if (uri == null) throw new IOException(Trans.get(ctx, R.string.exp_err_no_dest));
        try {
            OutputStream o = ctx.getContentResolver().openOutputStream(uri, "wt");
            if (o != null) return o;
        } catch (Throwable t) {
            Log.w(TAG, "openOutputStream(wt) refused, falling back: " + t);
        }
        OutputStream o = ctx.getContentResolver().openOutputStream(uri);
        if (o == null) throw new IOException(Trans.get(ctx, R.string.exp_err_dest_readonly));
        return o;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** 计数流：zip 的压缩后字节数只有包在流上才拿得到。 */
    private static final class Counting extends OutputStream {
        private final OutputStream o;
        long n;

        Counting(OutputStream o) {
            this.o = o;
        }

        @Override public void write(int b) throws IOException {
            o.write(b);
            n++;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            n += len;
        }

        @Override public void flush() throws IOException {
            o.flush();
        }

        @Override public void close() throws IOException {
            o.close();
        }
    }
}
