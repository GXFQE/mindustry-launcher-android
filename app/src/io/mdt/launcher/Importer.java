package io.mdt.launcher;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK 导入（M1）。模块边界：依赖 Util/Config，被 UI 调用。
 *
 * 流程（每一步都是探针实测出的硬约束，见 MDT-Android-Dev README §1.6 / §六.7/8）：
 *   1. ContentResolver 流式拷贝 —— 全程不碰文件路径、不碰 SELinux 标签
 *      （SAF 的 content:// 是唯一不受 Android 14 「动态代码须内部+只读」限制影响的输入通道）；
 *   2. zip 合法性 + version.properties 读取；
 *   3. ★ F18 兼容性探测 —— 按**能力**（入口类 / native 库名 / ABI / 页对齐）判定这个包
 *      能不能进加载管线，不通过就在这里拒绝（见 {@link Compat}）。判据是能力不是版本号：
 *      按版本号切会误伤 v105~v146 整段"真能跑"的版本，而魔改包又会漏。
 *   4. ★ 置只读 —— 可写的 dex 一律 SecurityException（Writable dex file is not allowed）；
 *   5. ★ 预热预检 —— 抛弃式 DexClassLoader 加载哨兵类，必须能解析才算导入成功
 *      （「首次加载陷阱」：冷状态第一次加载产出的 element 是死的，允许失败一次，第二次必须成）。
 *      导入时验过，用户才不会拿到一个装完打不开的版本；
 *   6. 落盘 + 登记 config.json。
 *
 * 失败时副本已清理，异常信息直接给 UI 展示；用户选中的原文件永不改动。
 */
public final class Importer {
    private static final String TAG = "MDTLauncher";
    private static final String SENTINEL = "mindustry.android.AndroidLauncher";

    private Importer() {}

    public static File importDir(Context ctx) {
        File d = new File(Paths.privateDir(ctx), "import");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 拷贝 + 校验 + 预热预检 + 登记。失败抛 IOException（.part 副本已清理）。 */
    public static JSONObject importApk(Context ctx, Uri uri, String displayName) throws IOException {
        // ★ 第 177 轮：78 MB 的拷贝 + 校验 + 预热都在这一段里，给它一个前台身份
        Work.begin(ctx, R.string.work_import_apk);
        try {
            return importApk0(ctx, uri, displayName);
        } finally {
            Work.end(ctx);
        }
    }

    private static JSONObject importApk0(Context ctx, Uri uri, String displayName) throws IOException {
        File dir = importDir(ctx);
        String base = sanitizeName(displayName);
        if (base.isEmpty()) base = "import-" + System.currentTimeMillis() + ".apk";
        if (!base.toLowerCase(Locale.US).endsWith(".apk")) base = base + ".apk";
        File file = new File(dir, base);
        File tmp = new File(dir, base + ".part");
        // 旧副本的"临时让位"名（只在 rename 不能覆盖目标时用；见下面那段）
        File old = new File(dir, base + ".old");

        // ★ 第 177 轮（杀后台 P0）：**绝不再先删旧副本**。
        //   旧实现是 `file.delete()` 再拷 `.part`（Importer.java:58/64）；真机实测在
        //   414 ms 时 force-stop，旧 APK 已删、`.part` 完整但没就位、config 仍指着旧名
        //   ⇒ 这个版本直接变砖（`apk` 找不到，连启动入口都没有）。
        //   现在旧副本留到**新包校验/预热通过并完成 rename** 为止。
        //   Linux 的 rename 对已存在的目标是**原子替换** ⇒ 顺利时这一步就够了。
        if (old.exists()) { old.setWritable(true); old.delete(); }   // 上次中断的残留
        if (tmp.exists()) { tmp.setWritable(true); tmp.delete(); }

        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException(Trans.get(ctx, R.string.imp_err_open_failed));
        long bytes = 0;
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); bytes += n; }
        } finally {
            out.close();
            in.close();
        }
        if (bytes < 1024) {
            cleanup(tmp);
            throw new IOException(Trans.get(ctx, R.string.imp_err_too_small_fmt, bytes));
        }

        String version;
        try {
            ZipFile zip = new ZipFile(tmp);
            try {
                if (zip.getEntry("classes.dex") == null)
                    throw new IOException(Trans.get(ctx, R.string.imp_err_no_dex));
                version = readVersion(zip);

                // ★ F18 兼容性探测（复用同一个 ZipFile，成本 ~10 ms 级）——
                //   把"装进去也跑不起来"的版本**挡在导入这一步**，而不是让用户点了启动
                //   才在 :game 进程里失败（那种失败既看不懂、又可能被上一次的成功掩盖）。
                //   判据是**能力**而不是版本号，理由见 Compat 类注释。
                Compat.Probe probe = Compat.probe(zip);
                if (!probe.runnable()) {
                    throw new IOException(Compat.rejectReason(ctx, probe));
                }
                Log.i(TAG, "import compat ok: " + probe.brief());
            } finally {
                zip.close();
            }
        } catch (IOException e) {
            cleanup(tmp);
            throw e;
        } catch (Exception e) {
            cleanup(tmp);
            throw new IOException(Trans.get(ctx, R.string.imp_err_bad_zip));
        }

        // ★ dex 载体必须只读
        tmp.setReadOnly();

        // ★ 预热预检（允许第一次失败 —— 首次加载陷阱）
        Class<?> c = tryLoadClass(ctx, tmp);
        if (c == null) c = tryLoadClass(ctx, tmp);
        if (c == null) {
            cleanup(tmp);
            throw new IOException(Trans.get(ctx, R.string.imp_err_no_entry_fmt, SENTINEL));
        }
        Log.i(TAG, "import prewarm ok: " + c.getName() + " from " + tmp.getName());

        // rename 只需要目录写权限，只读文件本身不影响；
        // 顺带覆盖掉同名的旧副本（Linux 语义 = 原子替换）。
        if (!tmp.renameTo(file)) {
            // ★ 个别实现拒绝"覆盖式 rename"：这时才让旧副本临时改名让位。
            //   中途被杀 ⇒ 盘上是 `file.old` + `file.part`、`file` 不在 ——
            //   启动时的 {@link #recover} 会把 `.old` 改回去（旧版本仍然可用）。
            boolean had = file.exists();
            if (had && !file.renameTo(old)) {
                cleanup(tmp);
                throw new IOException(Trans.get(ctx, R.string.imp_err_rename_fmt, file.getName()));
            }
            tmp.setWritable(true);
            if (!tmp.renameTo(file)) {
                // 新包没就位 ⇒ 把旧包原样还回去，绝不让用户变砖
                if (had && old.exists()) {
                    old.setWritable(true);
                    if (!old.renameTo(file)) {
                        Log.w(TAG, "import rollback failed: " + old + " -> " + file);
                    }
                }
                cleanup(tmp);
                throw new IOException(Trans.get(ctx, R.string.imp_err_rename_fmt, tmp.getName()));
            }
            file.setReadOnly();
            if (old.exists()) { old.setWritable(true); old.delete(); }
        }

        JSONObject e = new JSONObject();
        try {
            e.put("file", base);
            e.put("label", stripExt(displayName));
            e.put("version", version);
            e.put("size", bytes);
            e.put("md5", Util.md5(file));
            e.put("added", System.currentTimeMillis());
        } catch (Exception je) {
            Log.w(TAG, "import entry build failed: " + je);
        }
        Config.get().addImport(e);
        Log.i(TAG, "import ok: " + e);
        return e;
    }

    /** 删除导入副本 + 配置登记。只动我们私有目录里的副本。 */
    public static void removeImport(Context ctx, String file) {
        File dir = importDir(ctx);
        File f = new File(dir, file);
        if (f.exists()) {
            f.setWritable(true);
            if (!f.delete()) Log.w(TAG, "import file delete failed: " + f);
        }
        // 顺手清同名残留（杀在导入中途的 `.part` / 让位用的 `.old`）
        deleteQuiet(new File(dir, file + ".part"));
        deleteQuiet(new File(dir, file + ".old"));
        Config.get().removeImport(file);
        Log.i(TAG, "import removed: " + file);
    }

    // ── 删掉整个导入版本：本体进中转站（可放回）────────────────────────────

    /** 中转站里一个"版本"目录里的三件东西 */
    static final String V_APK = "base.apk";
    static final String V_ENTRY = "entry.json";
    static final String V_STATE = "state.json";

    /**
     * 删掉**整个导入版本**，但先把本体挪进中转站（2026-10-25 用户：「导入版删掉最后一行时，
     * 本体进中转站，这个删除时要给个提示」）。
     *
     * <p>落点是一个目录：`base.apk` + `entry.json`（config 里那条登记，放回来时重建用）
     * + `state.json`（显示名与槽绑定）。**存档不在里面** —— 存档住在槽里，删版本不动它。
     *
     * <p>⚠️ 几十 MB 的跨分区搬运（内部存储 → 外部 hub）⇒ 调用方**必须**放在后台线程里，
     *   并配 {@link Work} 的前台服务（否则切走就可能被杀在半路）。
     */
    public static Trash.Result trashVersion(Context ctx, Versions.Entry e) {
        if (ctx == null || e == null || !e.imported || e.importFile == null || e.importFile.isEmpty()) {
            return Trash.fail(Trash.Result.T_NO_KIND, "not an imported version", "");
        }
        File src = new File(importDir(ctx), e.importFile);
        if (!src.isFile()) return Trash.fail(Trash.Result.T_NOT_IN_TRASH, "missing: " + src, e.importFile);
        // ★ 标签只用**名字**（`displayLabel`），不要带"类型 + 版本号"的完整显示名：
        //   那串里有 `  ` 分隔、还会随版本号变化，进目录名既难看又容易在解析时踩到分隔符。
        File dir = new File(Trash.versionsDir(ctx), Trash.nameFor(e.displayLabel(), e.importFile));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return Trash.fail(Trash.Result.T_MKDIR, "mkdir: " + dir, dir.getName());
        }
        File apk = new File(dir, V_APK);
        if (!Trash.move(src, apk)) {
            Data.deleteTree(dir);
            return Trash.fail(Trash.Result.T_MOVE_FAILED, "move failed: " + src, e.importFile);
        }
        // 先写登记与状态，再清 config —— 顺序反了就会在两步之间被杀成"登记没了、东西还在站里"
        String key = e.key();
        writeJson(new File(dir, V_ENTRY), Config.get().importEntry(e.importFile));
        org.json.JSONObject state = new org.json.JSONObject();
        try {
            state.put("name", Config.get().nameOf(key));
            state.put("slot", Config.get().slotOf(key));
            org.json.JSONArray ex = new org.json.JSONArray();
            for (String s : Config.get().extraSlotsOf(key)) ex.put(s);
            state.put("extras", ex);
        } catch (Throwable t) {
            Log.w(TAG, "version state failed: " + t);
        }
        writeJson(new File(dir, V_STATE), state);
        Config.get().removeImport(e.importFile);
        // ★ 2026-10-25 用户：「游戏本体保留个数按照整个槽算」⇒ `keepFor(VERSION)` 走整槽那一套（默认 3），
        //   而且**必须在这里真的执行**：别的种类都是在各自的删除动作里顺手 prune，这里以前漏了 ⇒
        //   站里的旧版本会无限堆积（每个几十 MB，比什么都伤）。
        int pruned = Trash.pruneIn(Trash.versionsDir(ctx));
        if (pruned > 0) Log.i(TAG, "versions-trash pruned: " + pruned);
        Log.i(TAG, "import version trashed: " + e.importFile + " -> " + dir);
        Trash.Result r = new Trash.Result();
        r.ok = true;
        r.dest = dir;
        r.bytes = apk.length();
        r.count = 1;
        return r;
    }

    /**
     * 把中转站里的一个"版本"放回去：APK 回 `app_hub/import`，登记重加，名字与槽绑定一起恢复。
     *
     * <p>槽已经不存在的那条绑定**跳过**（别让版本落进一个不存在的槽）；那几条由用户自己重绑。
     */
    public static Trash.Result restoreVersion(Context ctx, Trash.Item it) {
        if (ctx == null || it == null || it.path == null || it.kind != Trash.Kind.VERSION) {
            return Trash.fail(Trash.Result.T_NO_KIND, "not a version item", "");
        }
        File dir = it.path;
        File apk = new File(dir, V_APK);
        if (!apk.isFile()) return Trash.fail(Trash.Result.T_NOT_IN_TRASH, "no apk: " + apk, dir.getName());
        org.json.JSONObject entry = readJson(new File(dir, V_ENTRY));
        final String file = entry == null ? "" : entry.optString("file", "");
        if (file.isEmpty()) return Trash.fail(Trash.Result.T_BAD_NAME, "entry without file", dir.getName());
        File dest = new File(importDir(ctx), file);
        if (dest.exists()) return Trash.fail(Trash.Result.T_NAME_TAKEN, "exists: " + dest, file);
        if (!Trash.move(apk, dest)) return Trash.fail(Trash.Result.T_MOVE_FAILED, "move back failed", file);
        Config.get().addImport(entry);
        String key = "import:" + file;
        org.json.JSONObject state = readJson(new File(dir, V_STATE));
        if (state != null) {
            String name = state.optString("name", "");
            if (!name.isEmpty()) Config.get().setNameOf(key, name);
            String slot = state.optString("slot", "");
            if (slotExists(ctx, slot)) Config.get().setSlotOf(key, slot);
            org.json.JSONArray ex = state.optJSONArray("extras");
            if (ex != null) {
                for (int i = 0; i < ex.length(); i++) {
                    String s = ex.optString(i, "");
                    if (slotExists(ctx, s)) Config.get().addExtraSlot(key, s);
                }
            }
        }
        Data.deleteTree(dir);
        Log.i(TAG, "import version restored: " + file + " <- " + dir);
        Trash.Result r = new Trash.Result();
        r.ok = true;
        r.dest = dest;
        r.bytes = dest.length();
        r.count = 1;
        return r;
    }

    private static boolean slotExists(Context ctx, String slot) {
        if (slot == null || slot.isEmpty()) return false;
        return Data.dirOf(ctx, slot).isDirectory();
    }

    private static void writeJson(File f, org.json.JSONObject o) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write((o == null ? "{}" : o.toString()).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            Log.w(TAG, "write " + f + ": " + t);
        }
    }

    private static org.json.JSONObject readJson(File f) {
        try {
            return new org.json.JSONObject(Util.readText(f));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 启动时的**导入目录自愈**（第 177 轮，杀后台 P0）。
     *
     * <p>处理的三种残留（都是真机造出来过的）：
     * <ul>
     *   <li>`<base>.part`：拷贝/校验中途被杀。**删掉** —— 配置里还没有它，
     *       而且同名的旧副本此刻要么还在 `<base>`、要么在 `<base>.old`；</li>
     *   <li>`<base>.old` 且 `<base>` 不存在：rename 让位窗口里被杀 ⇒ **改回去**，
     *       旧版本继续可用（这是"版本变砖"那条的兜底）；</li>
     *   <li>`<base>.old` 且 `<base>` 存在：新包已就位 ⇒ 删掉让位副本。</li>
     * </ul>
     *
     * @return 一行人读的处理摘要（没做事返回空串；只进日志/报告，不上界面）
     */
    public static String recover(Context ctx) {
        File dir = new File(Paths.privateDir(ctx), "import");
        File[] fs = dir.isDirectory() ? dir.listFiles() : null;
        if (fs == null || fs.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile()) continue;
            if (n.endsWith(".old")) {
                String base = n.substring(0, n.length() - 4);
                File target = new File(dir, base);
                if (!target.exists()) {
                    long len = f.length();          // rename 之后 f 就没了，先取
                    f.setWritable(true);
                    if (f.renameTo(target)) {
                        sb.append("restored ").append(base).append(" (").append(len)
                          .append(" B)\n");
                        continue;
                    }
                    Log.w(TAG, "import recover: restore failed " + f);
                }
                if (deleteQuiet(f)) sb.append("dropped stale .old ").append(n).append('\n');
            } else if (n.endsWith(".part")) {
                long len = f.length();
                if (deleteQuiet(f)) sb.append("dropped .part ").append(n)
                        .append(" (").append(len).append(" B)\n");
            }
        }
        if (sb.length() > 0) Log.i(TAG, "import recover:\n" + sb);
        return sb.toString();
    }

    private static boolean deleteQuiet(File f) {
        if (f == null || !f.exists()) return false;
        f.setWritable(true);
        if (f.delete()) return true;
        Log.w(TAG, "delete failed: " + f);
        return false;
    }

    private static void cleanup(File tmp) {
        tmp.setWritable(true);
        tmp.delete();
    }

    private static Class<?> tryLoadClass(Context ctx, File apk) {
        try {
            DexClassLoader cl = new DexClassLoader(apk.getAbsolutePath(),
                    ctx.getCodeCacheDir().getAbsolutePath(), null,
                    Importer.class.getClassLoader().getParent());
            return cl.loadClass(SENTINEL);
        } catch (Throwable t) {
            Log.i(TAG, "prewarm attempt failed (expected on cold): " + t);
            return null;
        }
    }

    /**
     * 从 APK 的 assets/version.properties 拼版本串（官方格式
     * number/type/build/revision，如 "8-official-159.7"）。读不到返回 "?"，
     * 不让展示层失败。
     */
    private static String readVersion(ZipFile zip) {
        try {
            ZipEntry e = zip.getEntry("assets/version.properties");
            if (e == null) return "?";
            Properties p = new Properties();
            InputStream in = zip.getInputStream(e);
            try { p.load(in); } finally { in.close(); }
            String num = p.getProperty("number", "").trim();
            String type = p.getProperty("type", "").trim();
            String build = p.getProperty("build", "").trim();
            String rev = p.getProperty("revision", "").trim();
            if (build.isEmpty()) return "?";
            StringBuilder core = new StringBuilder(build);
            if (!rev.isEmpty() && !"0".equals(rev)) core.append('.').append(rev);
            StringBuilder v = new StringBuilder();
            if (!num.isEmpty()) v.append(num);
            if (!type.isEmpty()) v.append(v.length() > 0 ? "-" : "").append(type);
            v.append(v.length() > 0 ? "-" : "").append(core);
            return v.toString();
        } catch (Exception ex) {
            return "?";
        }
    }

    /** 文件名安全化：只留 ASCII 字母数字和 .-_（其余替换为 _），防路径注入。 */
    private static String sanitizeName(String name) {
        if (name == null) return "";
        String n = name.replace("\\", "/");
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (char ch : n.toCharArray()) {
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_';
            sb.append(ok ? ch : '_');
        }
        return sb.toString();
    }

    private static String stripExt(String name) {
        if (name == null) return "?";
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }
}
