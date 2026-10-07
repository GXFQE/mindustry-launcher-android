package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.InputStream;

/**
 * 蓝图的**写**侧（F22 第二步，2026-10-05 第 110 轮）：导入 / 删除（挪进中转站）。
 *
 * <h3>纪律照抄地图那条线（{@link MapFiles} / {@link Mods}importPackage）</h3>
 * <ul>
 *   <li><b>先 `.part` 再就位</b>：流式拷到 `&lt;名&gt;.msch.part`，用 {@link Msch} **验过**才改名
 *       —— 半成品永远不会以正式名字出现（游戏那边是 `walk` 一遍全读，半个文件只会被静默跳过）；</li>
 *   <li><b>同名不硬删</b>：旧的先挪进 `&lt;hub&gt;/schematics-trash/`（带来源槽的 v2 名字），
 *       挪不动就**拒绝这次导入** —— "删了再改名"等于静默丢用户一份蓝图；</li>
 *   <li><b>删除 = 挪进中转站</b>，两条安全判据（是这个目录的直接子项 + 游戏没在跑）都过才动。</li>
 * </ul>
 *
 * 🔴 **判据字段不是文案**：{@link Result#nameTaken} 才是"要不要弹替换框"的权威标志
 *   （拿 `error` 里的字面串当控制流会被 i18n 门禁 `SRC-01` 拦，而且真出过一次）。
 *
 * ⚠️ 只允许删 **`&lt;槽&gt;/schematics/` 的直接子项**：模组自带 / APK 里的蓝图不在槽里，
 *   不允许被"删"（它们属于那个包）。
 */
public final class BlueprintFiles {

    /** 导入结果 */
    public static final class Result {
        public boolean ok;
        /** **给人看**的失败原因（文案走资源；异常原文只拼在 catch 那一支） */
        public String error;
        public File dest;
        public String finalName = "";
        public boolean overwrote;
        public long bytes;
        /**
         * 目标名已经被占了、而调用方没给 overwrite ⇒ 这一趟**什么都没写**，只是回来问一声。
         * 🔴 界面判"要不要弹替换框"**只认这个字段**（不许看 {@link #error} 的字面内容）。
         */
        public boolean nameTaken;
        /** 验过之后读出来的蓝图（界面可以直接显示"导入的是哪一份"） */
        public Msch msch;
        /** 认不出来时的**码 + 参数**（界面走 {@link MschText} 翻白话） */
        public Msch broken;
    }

    private BlueprintFiles() {}

    /** 中转站：`&lt;hub&gt;/schematics-trash/`（实现与其它三类统一在 {@link Trash}） */
    public static File trashDirOf(Context ctx) {
        return Trash.schemsDir(ctx);
    }

    /**
     * 文件名检查：必须是 `.msch`，且不含路径分隔符/冒号等。
     *
     * @return 错误文案（null = 通过）；文案走资源
     */
    public static String checkName(Context ctx, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return Trans.get(ctx, R.string.bpfile_err_name_empty);
        }
        String n = displayName.trim();
        if (n.startsWith(".")) return Trans.get(ctx, R.string.bpfile_err_name_dot);
        if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".msch")) {
            return Trans.get(ctx, R.string.bpfile_err_name_ext);
        }
        if (n.contains("/") || n.contains("\\") || n.contains(":")) {
            return Trans.get(ctx, R.string.bpfile_err_name_path);
        }
        if (n.length() > 120) return Trans.get(ctx, R.string.bpfile_err_name_long);
        return null;
    }

    /** 文件名安全化（**保留非 ASCII** —— 蓝图名就是用户看到的那个名字，与 {@link MapFiles} 同一条） */
    public static String safeName(String displayName) {
        String n = displayName == null ? "" : displayName.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) s = "blueprint.msch";
        if (!s.toLowerCase(java.util.Locale.ROOT).endsWith(".msch")) s = s + ".msch";
        return s;
    }

    /**
     * 导入一份蓝图（**先 `.part` + 验 + 再就位**）。
     *
     * @param overwrite true ⇒ 同名时把旧的挪去中转站再覆盖
     * @param trashDir  中转站（null ⇒ 那就不允许覆盖，宁可不做也不硬删）
     */
    public static Result importSchem(Context ctx, File dir, String displayName, InputStream in,
                                     boolean overwrite, File trashDir) {
        Result r = new Result();
        if (dir == null) {
            r.error = Trans.get(ctx, R.string.bpfile_err_no_dir);
            return r;
        }
        String nameErr = checkName(ctx, displayName);
        if (nameErr != null) {
            r.error = nameErr;
            return r;
        }
        final String name = safeName(displayName);
        if (!dir.exists() && !dir.mkdirs()) {
            r.error = Trans.get(ctx, R.string.bpfile_err_mkdir_fmt, dir.getAbsolutePath());
            return r;
        }
        File dest = new File(dir, name);
        if (dest.exists() && !(overwrite && trashDir != null)) {
            // ★ 判据字段（界面据此决定要不要弹"同名替换"框）；error 只是给人看的话
            r.nameTaken = true;
            r.error = Trans.get(ctx, R.string.bpfile_err_name_taken_fmt, name);
            return r;
        }
        File part = new File(dir, name + ".part");
        try {
            Data.deleteTree(part);                 // 上一轮中断留下的
            r.bytes = Util.copyStream(in, part);
            // ★ 判据：**我们自己的解析器**认不认这份文件（不是看扩展名）。
            //   ⚠️ 我们比游戏严一档：游戏对读不出来的文件只是**静默跳过**（`errored` 集合里躺着），
            //   让它进槽等于给用户一个"看着在、贴不出来"的东西 ⇒ 这里直接拒。
            Msch m = Msch.read(part);
            if (!m.ok) {
                Data.deleteTree(part);
                r.broken = m;
                r.error = (m.error == null || m.error.isEmpty())
                        ? Trans.get(ctx, R.string.bpfile_err_not_schem)
                        : Trans.get(ctx, R.string.bpfile_err_not_schem_fmt, m.error);
                return r;
            }
            r.msch = m;
            r.overwrote = dest.exists();
            place(ctx, part, dest, trashDir);
            r.dest = dest;
            r.finalName = name;
            r.ok = true;
            return r;
        } catch (Throwable t) {
            Data.deleteTree(part);
            // ★ 同 `MapFiles` 的两条 catch（2026-10-06）：`place()` 抛的是**给用户看的资源文案**
            //   ⇒ 别把 `IllegalStateException:` 类名拼进去。
            //   ⚠️ 这条**会直接显示给用户**：`BlueprintsActivity` 只在 `broken != null` 时走
            //   `MschText.reason`（码→白话），而这条路 `broken` 是 null（失败来自 `place`，不是解析）
            //   ⇒ 界面原样显示 `r.error`。
            String m = t.getMessage();
            r.error = (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
            return r;
        }
    }

    /**
     * 就位：**先把旧的挪去中转站**，再把 `.part` 改名成目标名。
     * 改名失败 ⇒ 把旧的从中转站搬回来（宁可回到原状，也不留一个空位）。
     *
     * 🔴 三处移动都用 {@link Trash#move}（**跨文件系统安全**：rename 失败就退化成
     *   "复制 + 校验 + 删源"）。为什么地图那条线没这么做而这里要：
     *   中转站曾经在**内部**存储、槽在**外部**存储（模组那条线当场撞过）——
     *   而蓝图的导入/删除是**会真的搬文件**的路径，绝不能因为"两个目录不在一个卷上"就静默失败。
     *   ⚠️ 这条是被自检㊹**当场抓出来**的（夹具的中转站放在私有目录 = 内部存储，于是 rename 必失败）。
     */
    static void place(Context ctx, File part, File dest, File trashDir) throws Exception {
        File backup = null;
        if (dest.exists()) {
            if (trashDir == null) {
                throw new IllegalStateException(Trans.get(ctx, R.string.bpfile_err_no_trash));
            }
            if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) {
                throw new IllegalStateException(
                        Trans.get(ctx, R.string.bpfile_err_trash_mkdir_fmt, trashDir.getAbsolutePath()));
            }
            backup = new File(trashDir,
                    Trash.nameFor(Trash.slotLabel(dest.getParentFile()), dest.getName()));
            if (!Trash.move(dest, backup)) {
                throw new IllegalStateException(Trans.get(ctx, R.string.bpfile_err_trash_move));
            }
        }
        if (!Trash.move(part, dest)) {
            if (backup != null) Trash.move(backup, dest);   // 回滚
            throw new IllegalStateException(
                    Trans.get(ctx, R.string.bpfile_err_rename_fmt, dest.getAbsolutePath()));
        }
        if (backup != null) Trash.prune(trashDir, Trash.KEEP);
    }

    /**
     * 删除一份蓝图 —— **挪去中转站，不硬删**。
     *
     * 🔴 安全判据（两条都过才动，与地图那条一字不差）：
     *  ① 它必须是 {@code dir} 的**直接子项**（模组自带 / APK 里的蓝图不在槽里，删不动）；
     *  ② 游戏进程没在跑（避免跟游戏抢文件）。
     *
     * @return 成功返回中转站里的那份；否则 null
     */
    public static File deleteToTrash(Context ctx, File dir, File file, File trashDir) {
        if (dir == null || file == null || trashDir == null) return null;
        if (Data.gameAlive(ctx)) return null;
        try {
            if (!dir.getCanonicalFile().equals(file.getParentFile().getCanonicalFile())) {
                return null;                                   // 不是这个目录的直接子项
            }
        } catch (Throwable t) {
            return null;
        }
        if (!file.isFile()) return null;
        if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) return null;
        File moved = new File(trashDir,
                Trash.nameFor(Trash.slotLabel(dir), file.getName()));
        // ★ 跨文件系统安全（见 place 的注释）：rename 失败就"复制 + 校验 + 删源"
        if (!Trash.move(file, moved)) return null;
        Trash.prune(trashDir, Trash.KEEP);
        return moved;
    }

    /** 本槽的蓝图目录（`&lt;槽&gt;/schematics`）—— **只拼路径**，不 mkdirs（读那条路只读） */
    public static File dirOf(Context ctx, String slot) {
        File root = Data.dirOf(ctx, slot);
        return root == null ? null : new File(root, "schematics");
    }
}
