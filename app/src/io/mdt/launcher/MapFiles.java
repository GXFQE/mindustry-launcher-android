package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地图的**增删**（F10 预览目标的最后一块）。
 *
 * 纪律**照抄模组那条线**（`Mods.importPackage` / `place` / `trashDirOf`）——本工程的老规矩：
 * <pre>
 *   ① 落盘先写 `.part`，**用我们自己的解析器验一遍**（不是"扩展名对就算"），
 *      验不过 ⇒ 删掉 `.part`，一个字节都不留；
 *   ② 就位时**先把旧的挪去中转站**（`&lt;hub&gt;/maps-trash/`），改名成功之后旧的那份还在中转站里
 *      ⇒ **挪不删**，用户能手工取回；
 *   ③ 中转站只留最近 {@link #KEEP_TRASH} 份（按名字前缀的时间戳排序删最旧）。
 * </pre>
 *
 * ⚠️ 只允许删 **槽的 `maps/` 目录下的直接子项**（{@link #deleteToTrash} 会验）——
 *    游戏自带/模组自带的地图**不在槽里**，不允许被"删"（它们属于那个包）。
 */
public final class MapFiles {
    /** 中转站保留份数（"可手工取回"的兜底，不是第二份存档） */
    public static final int KEEP_TRASH = 20;

    /** 导入结果 */
    public static final class Result {
        public boolean ok;
        public String error;
        public File dest;
        public String finalName = "";
        public boolean overwrote;
        public long bytes;
        /** 验过之后读出来的元数据（界面可以直接拿来显示"导入的是哪张图"） */
        public MsavMeta meta;
    }

    private MapFiles() {}

    /** 中转站：`<hub>/maps-trash/` */
    public static File trashDirOf(Context ctx) {
        return new File(Data.hubDir(ctx), "maps-trash");
    }

    /** 只保留最近 `keep` 份（文件名前缀是 `yyyyMMdd-HHmmss` ⇒ 字典序 = 时间序） */
    public static int pruneTrash(File dir, int keep) {
        if (dir == null) return 0;
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= keep) return 0;
        List<File> all = new ArrayList<>();
        Collections.addAll(all, fs);
        Collections.sort(all, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        int removed = 0;
        for (int i = 0; i < all.size() - keep; i++) {
            if (Data.deleteTree(all.get(i))) removed++;
        }
        return removed;
    }

    /**
     * 文件名检查：必须是 `.msav`，且不含路径分隔符/冒号等。
     *
     * @return 错误文案（null = 通过）
     */
    public static String checkName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "名字是空的";
        String n = displayName.trim();
        if (n.startsWith(".")) return "名字不能以点开头";
        if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) return "地图文件必须以 .msav 结尾";
        if (n.contains("/") || n.contains("\\") || n.contains(":")) return "名字里不能有路径符号";
        if (n.length() > 120) return "名字太长了";
        return null;
    }

    /**
     * 文件名安全化（**保留非 ASCII** —— 地图名就是用户看到的那个名字，见 {@link Msav} 同类注释）。
     */
    public static String safeName(String displayName) {
        String n = displayName == null ? "" : displayName.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) s = "map.msav";
        if (!s.toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) s = s + ".msav";
        return s;
    }

    /**
     * 导入一张地图（**先 `.part` + 验 + 再就位**）。
     *
     * @param overwrite true ⇒ 同名时把旧的挪去中转站再覆盖
     * @param trashDir  中转站（null ⇒ 那就**不允许覆盖**，宁可不做也不硬删）
     */
    public static Result importMap(File mapsDir, String displayName, InputStream in,
                                   boolean overwrite, File trashDir) {
        Result r = new Result();
        if (mapsDir == null) {
            r.error = "拿不到这个槽的 maps/ 目录";
            return r;
        }
        String nameErr = checkName(displayName);
        if (nameErr != null) {
            r.error = nameErr;
            return r;
        }
        final String name = safeName(displayName);
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            r.error = "建目录失败：" + mapsDir.getAbsolutePath();
            return r;
        }
        File dest = new File(mapsDir, name);
        if (dest.exists() && !(overwrite && trashDir != null)) {
            r.error = "这个槽的 maps/ 里已经有「" + name + "」了 —— 替换会盖掉原文件，需要先确认";
            return r;
        }
        File part = new File(mapsDir, name + ".part");
        try {
            Data.deleteTree(part);                 // 上一轮中断留下的
            r.bytes = Util.copyStream(in, part);
            // ★ 判据：**我们自己的解析器**能不能读出元数据（不是看扩展名）
            MsavMeta meta = MsavMeta.read(part, true);
            if (!meta.ok) {
                Data.deleteTree(part);
                r.error = "这个文件不是游戏能读的地图"
                        + (meta.error == null || meta.error.isEmpty() ? "" : "：" + meta.error);
                return r;
            }
            r.meta = meta;
            r.overwrote = dest.exists();
            place(part, dest, trashDir);
            r.dest = dest;
            r.finalName = name;
            r.ok = true;
            return r;
        } catch (Throwable t) {
            Data.deleteTree(part);
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    /**
     * 就位：**先把旧的挪去中转站**，再把 `.part` 改名成目标名。
     * 改名失败 ⇒ 把旧的从中转站搬回来（宁可回到原状，也不留一个空位）。
     */
    static void place(File part, File dest, File trashDir) throws Exception {
        File backup = null;
        if (dest.exists()) {
            if (trashDir == null) throw new IllegalStateException("同名文件已存在，且没有中转站可用");
            if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) {
                throw new IllegalStateException("建中转站失败：" + trashDir.getAbsolutePath());
            }
            backup = new File(trashDir, stamp() + "-" + dest.getName());
            if (!dest.renameTo(backup)) throw new IllegalStateException("旧文件挪去中转站失败");
        }
        if (!part.renameTo(dest)) {
            if (backup != null) {
                // 回滚：把旧的搬回来
                backup.renameTo(dest);
            }
            throw new IllegalStateException("改名失败：" + dest.getAbsolutePath());
        }
        if (backup != null) pruneTrash(trashDir, KEEP_TRASH);
    }

    /**
     * 删除一张地图 —— **挪去中转站，不硬删**。
     *
     * 🔴 安全判据（两条都过才动）：
     *  ① 它必须是 {@code mapsDir} 的**直接子项**（游戏自带/模组自带的地图不在槽里，删不动）；
     *  ② 游戏进程没在跑（与改 `settings.bin` 同一条门禁，避免跟游戏抢文件）。
     *
     * @return 成功返回中转站里的那份；否则 null
     */
    public static File deleteToTrash(Context ctx, File mapsDir, File mapFile, File trashDir) {
        if (mapsDir == null || mapFile == null || trashDir == null) return null;
        if (Data.gameAlive(ctx)) return null;                      // 游戏在跑：不动
        try {
            if (!mapsDir.getCanonicalFile().equals(mapFile.getParentFile().getCanonicalFile())) {
                return null;                                       // 不是这个目录的直接子项
            }
        } catch (Throwable t) {
            return null;
        }
        if (!mapFile.isFile()) return null;
        if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) return null;
        File moved = new File(trashDir, stamp() + "-" + mapFile.getName());
        if (!mapFile.renameTo(moved)) return null;
        pruneTrash(trashDir, KEEP_TRASH);
        return moved;
    }

    static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
