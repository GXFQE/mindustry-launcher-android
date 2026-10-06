package io.mdt.launcher;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 整槽 zip 的**读入**（F6c）。模块边界：依赖 Data/Util，被 UI 调用。
 * 它是 {@link Exporter}（写向）的反向，也是 {@link Msav}（单文件）的规模化版本。
 *
 * ── 为什么需要它 ─────────────────────────────────────────────────────────
 * F6 只解决了"出"，`Android/data/&lt;包名&gt;/` 对文件管理器不可见 ⇒ 换机时用户
 * 拿着一个导出的 zip **没有任何办法放回去**。同时游戏自己的
 * 「设置 → 数据 → 导入数据」（`SettingsMenuDialog.importData`）虽然能吃 zip，
 * 但它是**破坏性**的（先删 saves/assetCache/tmp 再覆盖）且只认自己那份口径。
 * 所以这里做一件事：**把 zip 解包进指定槽**，两种来源都认。
 *
 * ── 兼容游戏原生格式（源码核实：Mindustry 160.4 `SettingsMenuDialog`） ────
 * 原生 `exportData` 打包的是（zip 根 = 数据根）：`settings.bin` + `maps/` +
 * `saves/` + `mods/` + `schematics/` + `assetCache/`；原生 `importData` 要求
 * **zip 根必须有 `settings.bin`**，否则报 "Not valid save data."。
 * ⇒ {@link Info#nativeFormat} 就用这条当判据；但我们**不**像游戏那样硬拒 ——
 *   不全的包（比如只含 saves/）照样允许导入，只是把警告写在确认框里。
 *
 * ── 三条安全纪律（都是"不这么写就会出事"的类型） ──────────────────────────
 * ① **zip-slip 防护**：条目名一律走 {@link #cleanPath} 规范化，`..` 越出根、
 *    绝对路径、盘符、NUL 全部丢弃。少了这一步，一个 `../../../databases/…`
 *    的条目就能写到应用沙箱外面去。
 * ② **先检查、后解包**：{@link #inspect} 只读一遍列出内容，让用户在**看见清单**之后
 *    才决定要不要覆盖/清空。这与 {@link Msav} 的 stage→commit 是同一条思路。
 * ③ **落盘用 `.part` + rename**：解包中途失败不会在 `saves/` 里留下半截文件
 *    （那种文件在游戏里会显示成一个打不开的存档）。
 *
 * ⚠️ 解包是**写操作**：目标槽是当前槽时必须先确认 `:game` 已退出，
 *    这条由调用方（{@link SlotsActivity}）负责，本类不查（同 {@link Backup}）。
 */
public final class SlotZip {

    private static final String TAG = "MDTLauncher";
    private static final int BUF = 65536;

    /** 一次解包最多处理多少条目（防 zip bomb 式的元数据爆炸） */
    private static final int MAX_ENTRIES = 200000;

    /**
     * 已知的数据根顶级名 —— 用来判"这看起来像不像一份游戏数据包"。
     * ⚠️ 只用于**给用户的一句提示**，不作为拒绝依据（用户手打的包也该能导入）。
     */
    private static final String[] KNOWN_TOPS = {
        "saves", "maps", "mods", "schematics", "assetCache", "previews",
        "settings.bin", "settings_backup.bin", "settings_backups", "server_list.json",
    };

    private SlotZip() {}

    // ── 检视结果 ──────────────────────────────────────────────────────────

    /** {@link #inspect} 的结果 —— 全部只读，用来渲染确认框 */
    public static final class Info {
        public int files;              // 文件条目数
        public int dirs;               // 目录条目数
        public long bytes;             // 解压后总字节（压缩流报不出大小时只统计能拿到的）
        public boolean sizeKnown = true;
        public int skipped;            // 因路径不安全 / 越界而丢弃的条目数
        /** 根目录有 `settings.bin` = 游戏自己那份导入会认的格式 */
        public boolean nativeFormat;
        /** 有 `saves/` —— 哪怕不是原生格式，也是"带存档的包" */
        public boolean hasSaves;
        /** 命中了任何一个 {@link #KNOWN_TOPS} */
        public boolean known;
        /** 被剥掉的单层包裹目录（`the-dir/`，含尾斜杠；空串 = 没剥） */
        public String strip = "";
        /** 顶级条目名（已剥包裹目录、去重、排序，最多 8 个） */
        public final List<String> tops = new ArrayList<>();

        public boolean empty() {
            return files == 0 && dirs == 0;
        }
    }

    /** {@link #extract} 的结果 */
    public static final class Result {
        public int files;
        public long bytes;
        public int skipped;
        /** 「补齐」下**保留下来的**同名文件数（它们没有被写） */
        public int kept;
        /** 实际被清空的顶级条目名（只有「覆盖」模式非空） */
        public final List<String> wiped = new ArrayList<>();
    }

    // ── ① staging：把 SAF 的 zip 落到内部临时文件 ──────────────────────────
    //
    // 为什么要落盘而不是直接用流：解包前要先**只读检视**一遍（给用户看清单），
    // 而 SAF 给的 InputStream 通常只能读一次；并且 ZipInputStream 是单遍顺序流，
    // 没有 seek 就没法"先看再解"。内部存储跟 SAF 同机，拷一次几 MB ~ 几十 MB 可接受。

    /** 拷到 `app_hub/zip-import.tmp`；用完必须 {@link #unstage}。 */
    public static File stage(Context ctx, Uri uri) throws IOException {
        if (uri == null) throw new IOException(ctx.getString(R.string.slotzip_err_no_uri));
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException(ctx.getString(R.string.slotzip_err_open_failed));
        File tmp = new File(Paths.privateDir(ctx), "zip-import.tmp");
        if (tmp.exists() && !tmp.delete()) {
            throw new IOException(ctx.getString(R.string.slotzip_err_clean_tmp_fmt, tmp.toString()));
        }
        long n = 0;
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[BUF];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    n += r;
                }
                out.flush();
            } finally {
                closeQuietly(out);
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } finally {
            closeQuietly(in);
        }
        if (n == 0) {
            tmp.delete();
            throw new IOException(ctx.getString(R.string.slotzip_err_empty));
        }
        Log.i(TAG, "zip staged: " + n + " B -> " + tmp);
        return tmp;
    }

    public static void unstage(File tmp) {
        if (tmp != null && tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "zip temp delete failed: " + tmp);
        }
    }

    // ── ② 检视（只读） ────────────────────────────────────────────────────

    /**
     * 只读扫一遍 zip，统计内容并判断格式。
     *
     * ★ **单层包裹目录剥离**：很多人会先把槽目录整个压缩，于是 zip 里多出一层
     *   `mydata/saves/…`。判据刻意收紧 —— 只有那个顶层目录里**确实有**
     *   `<它>/settings.bin` 或 `<它>/saves/` 时才剥（避免把真正的 `saves/` 目录剥掉）。
     */
    public static Info inspect(Context ctx, File zip) throws IOException {
        Info inf = new Info();
        List<String> names = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        ZipInputStream zin = open(ctx, zip);
        try {
            ZipEntry e;
            int guard = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (++guard > MAX_ENTRIES) {
                    throw new IOException(ctx.getString(R.string.slotzip_err_too_many_fmt, MAX_ENTRIES));
                }
                boolean dir = e.isDirectory();
                if (dir) inf.dirs++;
                String n = cleanPath(e.getName());
                if (n == null) {
                    inf.skipped++;
                    continue;
                }
                names.add(n);
                if (!dir) {
                    fileNames.add(n);
                    inf.files++;
                    long sz = e.getSize();
                    if (sz >= 0) inf.bytes += sz;
                    else inf.sizeKnown = false;
                }
            }
        } catch (java.util.zip.ZipException ze) {
            // ★ 真机实测：Android 的 ZipInputStream **自己**就拒收条目名里带 `..` /
            //   以 `/` 开头的包（`Invalid zip entry path: …`），整包直接抛。
            //   我们的 {@link #cleanPath} 是第二层（它管的是盘符 `C:` 这类 JDK 不管的写法）。
            //   把英文异常翻成人话 —— 用户看到 "Invalid zip entry path" 只会一脸茫然。
            throw badZip(ctx, ze);
        } finally {
            closeQuietly(zin);
        }

        // ★ 包裹目录只看**文件**条目：目录条目在 cleanPath 里去掉了尾斜杠，
        //   拿它判"是不是根级文件"会误判（`mydata/` 会看着像根级条目）。
        inf.strip = detectStrip(fileNames);

        List<String> tops = new ArrayList<>();
        for (String n : names) {
            String r = stripOf(inf.strip, n);
            if (r == null) continue;
            int slash = r.indexOf('/');
            String top = slash < 0 ? r : r.substring(0, slash);
            if (top.isEmpty()) continue;
            if (!tops.contains(top)) tops.add(top);
            if ("settings.bin".equals(r)) inf.nativeFormat = true;
            if ("saves".equals(top)) inf.hasSaves = true;
        }
        Collections.sort(tops);
        for (int i = 0; i < tops.size() && i < 8; i++) inf.tops.add(tops.get(i));
        if (tops.size() > 8) inf.tops.add(ctx.getString(R.string.slotzip_more_fmt, tops.size()));

        for (int i = 0; i < tops.size() && i < 8; i++) {
            for (String k : KNOWN_TOPS) {
                if (k.equals(tops.get(i))) inf.known = true;
            }
        }
        Log.i(TAG, "zip inspect: files=" + inf.files + " dirs=" + inf.dirs
                + " native=" + inf.nativeFormat + " strip='" + inf.strip + "'");
        return inf;
    }

    // ── ③ 解包 ────────────────────────────────────────────────────────────

    /**
     * 把 zip 解包进槽目录（zip 根 = 槽目录，与 {@link Exporter} 完全对称）。
     *
     * @param mode {@link SlotWrite#UPDATE}（同名用包里的，其余原样留着）
     *             · {@link SlotWrite#KEEP_OLD}（同名保留槽里的，只补缺的）
     *             · {@link SlotWrite#REPLACE}（先按 {@link Data#contentRootsOf} 清空槽内容，再整份写入）
     */
    public static Result extract(Context ctx, File zip, Info info, String slot, int mode)
            throws IOException {
        mode = SlotWrite.sane(mode);
        File root = Data.dirOf(ctx, slot);
        if (root == null) throw new IOException(ctx.getString(R.string.slotzip_err_no_slot_dir_fmt, slot));
        Result r = new Result();

        // ★ 清空走共享原语（口径 = 槽内容，见 SlotWrite 类注释）——
        //   它**抛异常就不继续**：清了一半再写会得到一个"既不是旧槽也不是新包"的东西。
        if (SlotWrite.wipeFirst(mode) && root.isDirectory()) {
            r.wiped.addAll(SlotWrite.wipeSlot(ctx, root));
        }
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException(ctx.getString(R.string.slotzip_err_mkdir_slot_fmt,
                    root.getAbsolutePath()));
        }

        String strip = info == null ? "" : info.strip;
        // 包裹目录**自身**的条目名（`dir/` → `dir`）：它不在 strip 前缀里，要单独跳过
        String stripDir = strip.isEmpty() ? null : strip.substring(0, strip.length() - 1);
        ZipInputStream zin = open(ctx, zip);
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = cleanPath(e.getName());
                if (n == null) {
                    r.skipped++;
                    continue;
                }
                if (!strip.isEmpty()) {
                    if (stripDir != null && stripDir.equals(n)) continue;   // 包裹目录自身
                    if (!n.startsWith(strip)) {
                        r.skipped++;
                        continue;
                    }
                    n = n.substring(strip.length());
                    if (n.isEmpty()) continue;
                }
                File to = new File(root, n.replace('/', File.separatorChar));
                if (e.isDirectory()) {
                    mkdirs(ctx, to);
                    continue;
                }
                // ★ 「补齐」：槽里已经有的**一个字节都不动**（只把缺的补上）。
                //   判据在 SlotWrite，不是就地写一个 `if` —— 快照恢复那条路用的是同一份。
                if (!SlotWrite.sourceWins(mode) && to.exists()) {
                    r.kept++;
                    continue;
                }
                File parent = to.getParentFile();
                if (parent != null) mkdirs(ctx, parent);

                // .part + rename：中途失败不留半截存档（见类头纪律 ③）
                File part = new File(to.getAbsolutePath() + ".part");
                if (part.exists()) part.delete();
                long n2 = 0;
                try {
                    OutputStream out = new FileOutputStream(part);
                    try {
                        byte[] buf = new byte[BUF];
                        int k;
                        while ((k = zin.read(buf)) > 0) {
                            out.write(buf, 0, k);
                            n2 += k;
                        }
                        out.flush();
                    } finally {
                        closeQuietly(out);
                    }
                } catch (IOException ex) {
                    part.delete();
                    // ★ 2026-10-06：原因走 `Util.ioReason`（认识的 errno 翻白话）——
                    //   这句会经 `zip_import_failed` 弹窗显示给用户。原文在异常链里（cause）不丢。
                    throw new IOException(ctx.getString(R.string.slotzip_err_write_failed_fmt,
                            to.getAbsolutePath(),
                            Util.ioReason(ctx, ex)), ex);
                }
                if (to.exists() && !to.delete()) {
                    part.delete();
                    throw new IOException(ctx.getString(R.string.slotzip_err_delete_failed_fmt,
                            to.getAbsolutePath()));
                }
                if (!part.renameTo(to)) {
                    part.delete();
                    throw new IOException(ctx.getString(R.string.slotzip_err_rename_fmt,
                            part.getName(), to.getName()));
                }
                r.files++;
                r.bytes += n2;
            }
        } catch (java.util.zip.ZipException ze) {
            throw badZip(ctx, ze);
        } finally {
            closeQuietly(zin);
        }
        Log.i(TAG, "zip extract ok: slot=" + slot + " files=" + r.files
                + " bytes=" + r.bytes + " kept=" + r.kept + " mode=" + mode
                + " wiped=" + r.wiped);
        return r;
    }

    // ── 路径安全 ──────────────────────────────────────────────────────────

    /**
     * 规范化 zip 条目名。**不安全就返回 null（整条丢弃）**。
     *
     * 丢弃的情形：空、纯 `/`、NUL、盘符、规范化后越出根（`..` 多于层数）。
     * 保留的情形：非 ASCII（存档/地图名本来就是中文）、空格、`#` 等。
     */
    static String cleanPath(String raw) {
        if (raw == null) return null;
        if (raw.indexOf('\0') >= 0) return null;
        String n = raw.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.length() >= 2 && n.charAt(1) == ':') n = n.substring(2);   // "C:xxx"
        String[] parts = n.split("/");
        List<String> keep = new ArrayList<>();
        int up = 0;                       // 已经退到根之外还继续 .. 的次数
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                // ★ 语义是"弹掉上一层"，不是"跳过下一个" ——
                //   `a/../b` 必须规范成 `b`；写成"跳过下一个"会把它变成 `a`。
                if (!keep.isEmpty()) keep.remove(keep.size() - 1);
                else up++;
                continue;
            }
            keep.add(p);
        }
        if (up > 0) return null;   // 越出根 ⇒ 丢弃
        if (keep.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(keep.get(i));
        }
        return sb.toString();
    }

    /** 单层包裹目录探测；返回 `"dir/"` 或空串。 */
    private static String detectStrip(List<String> names) {
        String first = null;
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash < 0) return "";                 // 有根级文件 ⇒ 不可能是一层包裹
            String top = n.substring(0, slash);
            if (first == null) first = top;
            else if (!first.equals(top)) return "";
        }
        if (first == null) return "";
        boolean rootLike = false;
        for (String n : names) {
            if (n.startsWith(first + "/settings.bin") || n.startsWith(first + "/saves/")) {
                rootLike = true;
                break;
            }
        }
        return rootLike ? first + "/" : "";
    }

    /** 剥掉包裹前缀；返回 null 表示这条属于包裹目录自身（应跳过） */
    private static String stripOf(String strip, String name) {
        if (strip == null || strip.isEmpty()) return name;
        if (name.equals(strip.substring(0, strip.length() - 1))) return null;
        return name.startsWith(strip) ? name.substring(strip.length()) : null;
    }

    // ── 底层 ──────────────────────────────────────────────────────────────

    private static ZipInputStream open(Context ctx, File zip) throws IOException {
        if (zip == null || !zip.isFile()) {
            throw new IOException(ctx.getString(R.string.slotzip_err_temp_gone_fmt,
                    String.valueOf(zip)));
        }
        return new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), BUF));
    }

    /** 把 JDK 的 `Invalid zip entry path` 翻成人话（见 inspect/extract 里的 catch 注释） */
    private static IOException badZip(Context ctx, java.util.zip.ZipException ze) {
        String m = ze.getMessage() == null ? String.valueOf(ze) : ze.getMessage();
        return new IOException(ctx.getString(R.string.slotzip_err_escape_fmt, m), ze);
    }

    private static void mkdirs(Context ctx, File d) throws IOException {
        if (d != null && !d.exists() && !d.mkdirs() && !d.isDirectory()) {
            throw new IOException(ctx.getString(R.string.slotzip_err_dir_failed_fmt,
                    d.getAbsolutePath()));
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
