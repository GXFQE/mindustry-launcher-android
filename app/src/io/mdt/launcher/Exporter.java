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
        public int skipped;      // 打不开而跳过的条目数（见 addTree 里那条判据）
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
        OutputStream out = null;
        long n;
        try {
            out = openOut(ctx, uri);
            n = copyTo(src, out);
        } finally {
            closeQuietly(out);
        }
        Log.i(TAG, "export file ok: " + src.getName() + " -> " + n + " B");
        return n;
    }

    /**
     * 文件 → **任意输出流**（SAF 与分享缓存两条路共用这一份）。
     *
     * ★ 第 156 轮抽出来的：{@code writeFile} 原来自己写着一段 64KB 循环，而分享那条路
     *   也要同一段 —— 再抄一份就会出现"分享出去的文件和保存的差几个字节"这种没有症状的漂。
     */
    static long copyTo(File src, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(src);
        long n = 0;
        try {
            byte[] buf = new byte[BUF];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
            }
        } finally {
            closeQuietly(in);
        }
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
     * 进度回调（**只报数、不做决定**）—— 界面拿它更新进度条；dev 口 / 自检传 `null`。
     * ★ 2026-10-11：原来是转圈的不确定进度（模组几十 MB 时要盯几秒才动），现在按"一份一份"报。
     */
    interface Step {
        void onStep(int done, int total);
    }

    /**
     * 打包时的**逐条目钩子**（第 141 轮「分享包」用它剥身份信息）。
     *
     * <p>两件事，都按**相对槽数据根的路径**（zip 里的条目名）判：
     * <ul>
     *   <li>{@link #drop(String)} true ⇒ 这一条**整份不进包**（也不计入 `Result.files`）；</li>
     *   <li>{@link #bytesOf(String)} 非 null ⇒ 用这份字节**代替**文件内容（null = 原样流式拷贝）。</li>
     * </ul>
     *
     * <p>★ 两个刻意的设计：
     * <ol>
     *   <li>参数是 `rel` 而不是 `File` —— 判据必须**只认根级那个名字**
     *     （`mods/config/settings.bin` 是模组自己的文件，`Data.contentSkipped` 之外
     *     谁都不许动它）。给 `File` 的话调用方很自然会写成 `getName().equals(...)`，
     *     那就是"任意深度都命中"。</li>
     *   <li>**没有默认实现**：本接口是给"有明确剥/丢清单"的场景用的，
     *     `null` 表示"什么都不做"（老行为），别实现一个空壳再传进来。</li>
     * </ol>
     */
    interface Xform {
        boolean drop(String rel);

        byte[] bytesOf(String rel) throws IOException;
    }

    /**
     * 把一批条目打成一个 zip 写进 SAF 目标（**批量导出的唯一出口**）。
     *
     * ★ 与 {@link #zipTo}（整槽备份那条）的分工：那个吃"目录树"，这个吃"一份一份的东西"
     *   —— 而"一份"可能是容器里的一条，也可能是一个目录（目录形态的模组）。
     */
    public static Result zipSources(Context ctx, Uri uri, List<Src> srcs) throws IOException {
        return zipSources(ctx, uri, srcs, null);
    }

    /** 同上，带进度回调（`step` 可为 null） */
    public static Result zipSources(Context ctx, Uri uri, List<Src> srcs, Step step)
            throws IOException {
        Result r = new Result();
        Counting counter = new Counting(openOut(ctx, uri));
        zipSourcesTo(ctx, counter, srcs, r, step);
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
        return zipSourcesTo(ctx, raw, srcs, new Result(), null);
    }

    /** 带计数器的重载（{@link #zipSources} 用它把"压缩后字节数"取回来） */
    static Result zipSourcesTo(Context ctx, OutputStream raw, List<Src> srcs, Result r)
            throws IOException {
        return zipSourcesTo(ctx, raw, srcs, r, null);
    }

    /** 带计数器 + 进度回调的重载（`step` 可为 null） */
    static Result zipSourcesTo(Context ctx, OutputStream raw, List<Src> srcs, Result r, Step step)
            throws IOException {
        if (r == null) r = new Result();
        if (srcs == null || srcs.isEmpty()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_no_src));
        }
        java.util.List<String> used = new java.util.ArrayList<>();
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(raw, BUF));
        int written = 0;
        int seen = 0;
        int total = srcs.size();
        try {
            zos.setLevel(Deflater.BEST_SPEED);      // 大目录（模组几十 MB）速度优先，同 zipTo
            for (Src s : srcs) {
                seen++;
                if (s == null || s.empty()) {
                    if (step != null) step.onStep(seen, total);
                    continue;
                }
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
                if (step != null) step.onStep(seen, total);
            }
            if (written == 0) throw new IOException(Trans.get(ctx, R.string.exp_err_no_src));
        } finally {
            closeQuietly(zos);      // 必须关：zip 的中央目录在 close 时才写
        }
        return r;
    }

    /**
     * 这一份在**目录树**里该叫什么（2026-10-11 第 129 轮 ⑥c）。
     * ★ 与 zip 侧的唯一差别：**目录形态的模组**在目录里没法当条目，只能落成 `<名字>.zip` 一个文档。
     *   纯函数（自检钉住），SAF 的写盘在 {@link #saveToTree} 里。
     */
    static String treeName(Src s) {
        if (s == null) return null;
        String n = s.entryName();
        if (n == null) return null;
        return s.dir != null ? n + ".zip" : n;
    }

    /**
     * 导出到**目录树**：一次落**多个独立文件**（`ACTION_OPEN_DOCUMENT_TREE` 那条路）。
     *
     * <p>与 {@link #zipSources} 的关系：**同一份 `Src` 语义、同一条去重规则**（{@link #uniqueName}），
     * 只是目的地从"一个 zip 里的条目"变成"目录下的一个个文档"。★ 重名判据**不许再造第二条**。
     *
     * <p>`Result.files` 的语义不变 =「这一批有几**份**」（目录模组内部的文件数不计进来，同 zip 侧）。
     */
    public static Result saveToTree(Context ctx, Uri treeUri, List<Src> srcs, Step step)
            throws IOException {
        Result r = new Result();
        if (srcs == null || srcs.isEmpty()) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_no_src));
        }
        android.content.ContentResolver cr = ctx.getContentResolver();
        Uri dir;
        try {
            dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri));
        } catch (Throwable t) {
            throw new IOException(Trans.get(ctx, R.string.exp_err_no_doc), t);
        }
        java.util.List<String> used = new java.util.ArrayList<>();
        int seen = 0;
        int written = 0;
        int total = srcs.size();
        for (Src s : srcs) {
            seen++;
            if (s == null || s.empty()) {
                if (step != null) step.onStep(seen, total);
                continue;
            }
            String name = uniqueName(used, treeName(s));
            used.add(name);
            String mime = name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                    ? "application/zip" : "application/octet-stream";
            Uri doc = android.provider.DocumentsContract.createDocument(cr, dir, mime, name);
            if (doc == null) throw new IOException(Trans.get(ctx, R.string.exp_err_no_doc));
            OutputStream out = cr.openOutputStream(doc, "w");
            if (out == null) throw new IOException(Trans.get(ctx, R.string.exp_err_no_doc));
            try {
                if (s.dir != null) {
                    // 目录形态：落成**嵌套 zip**（内层文件名数不计进 files，同 zip 侧）
                    Result inner = new Result();
                    zipDirFlat(s.dir, out, inner);
                    r.rawBytes += inner.rawBytes;
                } else if (s.file != null) {
                    r.rawBytes += copyInto(s.file, out);
                } else {
                    r.rawBytes += copyEntry(ctx, s.container, s.entry, out);
                }
            } finally {
                closeQuietly(out);      // 必须关：文档的字节这时才落全
            }
            r.files++;
            written++;
            if (step != null) step.onStep(seen, total);
        }
        if (written == 0) throw new IOException(Trans.get(ctx, R.string.exp_err_no_src));
        Log.i(TAG, "export to tree ok: files=" + r.files + " raw=" + r.rawBytes);
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

    /**
     * 把一个**目录平铺**打成一个 zip（条目名 = 相对该目录根的路径，**不是**嵌套 zip）。
     *
     * <p>用处：{@link BatchIo#extractModFolders} —— 用户把好几个模组**文件夹**打成一个 zip，
     * 而模组导入只吃"一个文件"⇒ 每个文件夹先还原成一个"模组包形状"的 zip 再喂进去。
     * （与 {@link #addTreeAsZip} 的区别：那个是往**外层 zip 里**塞一个嵌套条目，这个是自成一体。）
     */
    static void zipDirFlat(File dir, OutputStream raw, Result r) throws IOException {
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("not a directory: " + dir);
        }
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(raw, BUF));
        try {
            zos.setLevel(Deflater.BEST_SPEED);
            addTree(dir, dir, zos, r == null ? new Result() : r);
        } finally {
            closeQuietly(zos);      // 必须关：zip 的中央目录在 close 时才写
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
        OutputStream out = null;
        long n;
        try {
            out = openOut(ctx, uri);
            n = writeTextTo(out, text);
        } finally {
            closeQuietly(out);
        }
        Log.i(TAG, "export text ok: " + n + " B -> " + uri);
        return n;
    }

    /** 文本 → 任意输出流（UTF-8，不带 BOM）；分享缓存那条路用它 */
    static long writeTextTo(OutputStream out, String text) throws IOException {
        byte[] b = (text == null ? "" : text).getBytes("UTF-8");
        out.write(b);
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
        return zipTo(ctx, uri, base, roots, null);
    }

    /**
     * 同上，带**逐条目钩子**（第 141 轮：分享包用它把 `settings.bin` 换成剥过身份信息的那一份、
     * 并把 `server_list.json` 整份丢掉）。
     *
     * <p>★ 为什么不另写一个"分享包专用"的遍历：整槽导出这一条路上有**三处口径**
     * （{@link Data#contentRoots} 打什么 / {@link Data#contentSkipped} 跳过什么 /
     * {@link Util#relOf} 条目名怎么拼），任何一处抄第二遍都会漂 ——
     * 而"分享包比普通包少了几个文件"这种漂是**没有任何症状**的。
     *
     * @param x 逐条目钩子（`null` = 老行为，原样打包）
     */
    public static Result zipTo(Context ctx, Uri uri, File base, List<File> roots, Xform x)
            throws IOException {
        return zipTo(ctx, uri, base, roots, x, Deflater.BEST_SPEED);
    }

    /**
     * 同上，外加**压缩级别**。
     * ★ 为什么要多这一档（第 144 轮）：老口径是「大目录几十 MB，速度优先」（`BEST_SPEED`）——
     *   那对**本地备份**是对的；而**分享包要过网络**（用户：「主要是想着尽可能小」）⇒
     *   那一档改用 `BEST_COMPRESSION`，多花几秒换更小的包。
     */
    public static Result zipTo(Context ctx, Uri uri, File base, List<File> roots, Xform x,
                               int level) throws IOException {
        OutputStream out = null;
        try {
            out = openOut(ctx, uri);
            return zipToOut(ctx, out, base, roots, x, level);
        } finally {
            closeQuietly(out);
        }
    }

    /**
     * 同一份打包逻辑，写进**任意输出流**（第 156 轮：分享缓存那条路要用它）。
     *
     * ⚠️ 调用方负责**关流**：zip 的中央目录必须在 close 时才写完，所以这里**不能**提前关
     *   —— 这也是为什么它不自带 `openOut`（SAF 那条在上面的重载里关）。
     */
    static Result zipToOut(Context ctx, OutputStream out, File base, List<File> roots, Xform x,
                           int level) throws IOException {
        Result r = new Result();
        Counting counter = new Counting(out);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(counter, BUF));
        try {
            // 大目录（mods 几十 MB）速度优先：压缩比换时间，用户等着不烦
            zos.setLevel(level);
            if (roots != null) {
                for (File root : roots) {
                    if (root == null || !root.exists()) continue;
                    addTree(base, root, zos, r, x);
                }
            }
        } finally {
            zos.close();   // 必须关：zip 的中央目录在 close 时才写
        }
        r.outBytes = counter.n;
        Log.i(TAG, "export zip ok: files=" + r.files + " raw=" + r.rawBytes
                + " out=" + r.outBytes);
        return r;
    }

    private static void addTree(File base, File f, ZipOutputStream zos, Result r)
            throws IOException {
        addTree(base, f, zos, r, null);
    }

    private static void addTree(File base, File f, ZipOutputStream zos, Result r, Xform x)
            throws IOException {
        if (skip(f)) return;

        if (f.isDirectory()) {
            // ★ 第 143 轮：目录也问一次钩子 —— ① 空目录（`crashes/` 里一份都没有）不会漏在包里；
            //   ② 整棵子树直接剪掉，连遍历都省了。文件分支那条判据**一模一样**（同一个 drop）。
            if (x != null && x.drop(relOf(base, f))) return;
            File[] kids = f.listFiles();
            if (kids == null || kids.length == 0) {
                // 空目录也留一条：否则解包后整个目录结构消失
                String rel = relOf(base, f);
                zos.putNextEntry(new ZipEntry(rel + "/"));
                zos.closeEntry();
                return;
            }
            for (File k : kids) addTree(base, k, zos, r, x);
            return;
        }

        String rel = relOf(base, f);
        // ★ 第 156 轮（真机撞出来的）：目录里可能有**文件名不是合法 UTF-8** 的条目
        //   （`listFiles()` 解出来是替换字符，再编码回去就是另一个名字 ⇒ 一打开 ENOENT）。
        //   真机实测：一张 2025 年留下的 `\346\262` 让**整槽导出 / 分享包整个失败**。
        //   ⇒ 打不开的条目**跳过**（用户在任何文件管理器里也打不开它），别让它毁掉整包。
        if (!f.isFile()) {
            r.skipped++;
            Log.w(TAG, "zip skip unreadable entry: " + f.getAbsolutePath());
            return;
        }
        // ★ 钩子①：整份不进包。**放在建条目之前** —— 于是它既不出现在 zip 里，
        //   也不会计进 `Result.files`（"已导出 N 个文件"那句报数才是真的）。
        if (x != null && x.drop(rel)) return;
        // ★ 钩子②：要替换内容的（分享包的 settings.bin）—— 走字节数组，不再读原文件
        byte[] repl = x == null ? null : x.bytesOf(rel);

        ZipEntry e = new ZipEntry(rel);
        // ⚠️ ZipEntry.setTime 对 1980 之前 / 2107 之后的时间会抛 IllegalArgumentException
        //    （坏掉的 mtime 在用户导入过的文件上真出现过）；越界就不带时间，不带也能解。
        long t = f.lastModified();
        if (t >= 315532800000L && t <= 4354819199000L) e.setTime(t);

        zos.putNextEntry(e);
        if (repl != null) {
            zos.write(repl);
            r.rawBytes += repl.length;
        } else {
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
