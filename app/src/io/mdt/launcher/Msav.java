package io.mdt.launcher;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 桌面版存档（`.msav`）迁移（M3）。模块边界：依赖 Data/Util。
 *
 * 探针实测：把桌面版的 `.msav` 推进数据根的 `saves/` 后，**游戏直接就能列出**，
 * 不需要做任何格式转换 —— 所以这里只做"安全落盘"，不碰内容。
 *
 * 落盘分两段（stage → commit），因为"这个文件到底是不是存档"需要看过内容才能判断，
 * 而判断又要在用户确认之前完成：
 *   stage   —— 流式拷到 `saves/&lt;名&gt;.msav.part`，同时用**魔数判 zlib**（78 9C，见 Util.isZlib）；
 *   commit  —— 改名成 `saves/&lt;名&gt;.msav`（原子）；
 *   discard —— 不要了就把 .part 删掉。
 *
 * ★ 为什么用 zlib 魔数当判据：Mindustry 的 `MapIO`/`SaveIO` 走 `InflaterInputStream`（zlib），
 *   所以 `.msav` / `.map` 一定以 `78 9C`（zlib 头）开头。这是**正证据**，
 *   比看后缀可靠（后缀可以被随便改），比"反序列化一下试试"便宜（不用起游戏类）。
 *   非 gzip 的文件不直接拒绝 —— 交给用户确认（老版本/改过的存档也放行），
 *   但界面必须说清"游戏可能读不出来"。
 */
public final class Msav {

    /** 暂存结果 */
    public static final class Stage {
        public File part;       // *.msav.part
        public File savesDir;
        public String base;     // 目标文件名（已保证 .msav 后缀）
        /** 头是不是 zlib（真的 .msav 都是）；false ⇒ 界面会警告但仍允许导入 */
    public boolean zlib;
        public long bytes;
    }

    private Msav() {}

    /** 流式拷到目标槽的 saves/ 下（先 .part）。失败时 .part 已清理。 */
    public static Stage stage(Context ctx, InputStream in, String displayName, String slot)
            throws IOException {
        if (in == null) throw new IOException(Trans.get(ctx, R.string.msav_err_open_failed));
        File savesDir = Data.savesDirOf(ctx, slot);
        if (savesDir == null || !savesDir.isDirectory()) {
            throw new IOException(Trans.get(ctx, R.string.msav_err_no_saves_fmt, slot));
        }
        String base = safeName(displayName);
        sweepParts(savesDir);       // 清掉上一轮的孤儿 .part（见 sweepParts 注释）
        File part = new File(savesDir, base + ".part");
        if (part.exists()) part.delete();
        long bytes = 0;
        try {
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); bytes += n; }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            in.close();
        }
        Stage s = new Stage();
        s.part = part;
        s.savesDir = savesDir;
        s.base = base;
        s.zlib = isZlib(part);
        s.bytes = bytes;
        return s;
    }

    /**
     * 落定：`<名>.msav.part` → `<名>.msav`。同名已存在则替换（用户选的这份为准）。
     *
     * ★★ 2026-10-05（第二批）：同名那份**先进中转站**，不再硬删。
     *   在此之前这里是 `dest.delete()` —— 界面上那句「替换会删掉旧的」是**如实**的，
     *   也就是"用户几百小时的存档，替换一次就没了"（第 86 轮只补了"先问一声"）。
     *   现在与地图那条线同一个口径：`&lt;hub&gt;/saves-trash/&lt;戳&gt;__&lt;槽&gt;__&lt;原名&gt;`，
     *   中转站页面能看到、能放回来。
     *
     * 🔴 **挪不动就拒绝这次导入**（不是退化成硬删）：旧存档**只存在这一份**，
     *   "删了再改名"就是静默丢数据。宁可报错让用户腾点空间重试 —— 旧的还在，
     *   而 `.part` 由调用方 {@link #discard} 收掉。
     * ★ 挪动成功后改名失败 ⇒ 把旧的那份**搬回来**（宁可回到原状，也不留一个空位）。
     */
    public static File commit(Context ctx, Stage s) throws IOException {
        File dest = new File(s.savesDir, s.base);
        File stash = null;
        if (dest.exists()) {
            File dir = Trash.savesDir(ctx);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException(Trans.get(ctx, R.string.msav_err_trash_failed));
            }
            stash = new File(dir, Trash.nameFor(Trash.slotLabel(s.savesDir), dest.getName()));
            dest.setWritable(true);
            if (!Trash.move(dest, stash)) {
                throw new IOException(Trans.get(ctx, R.string.msav_err_trash_failed));
            }
        }
        s.part.setWritable(true);
        if (!s.part.renameTo(dest)) {
            if (stash != null) Trash.move(stash, dest);        // 回滚：旧的放回去
            throw new IOException(Trans.get(ctx, R.string.msav_err_rename_fmt, s.part.getName(), s.base));
        }
        if (stash != null) Trash.pruneIn(stash.getParentFile());
        return dest;
    }

    public static void discard(Stage s) {
        if (s != null && s.part != null && s.part.exists()) {
            s.part.setWritable(true);
            s.part.delete();
        }
    }

    /**
     * 清掉 `saves/` 里的孤儿 `.msav.part`，返回删掉几个。
     *
     * ★ 为什么需要：`.part` 只在"确认框还开着"的这段时间存在。用户走正常路径
     *   （确认 / 取消按钮 / 返回键）都会被 {@link #commit} 或 {@link #discard} 收掉，
     *   但**弹窗活着的时候 Activity 被重建**（转屏、切深浅色、被系统杀掉后回前台）
     *   会把弹窗连同 `setOnCancelListener` 一起丢掉，`.part` 就成了永久垃圾。
     *   它对游戏不可见（存档列表只认 `*.msav`），但白占空间，且"有东西删不掉"印象很差。
     *
     * ★ 为什么可以无条件删：能走到这里就说明用户**刚选完文件**，而导入是模态的
     *   （同一时刻只可能有一个改名框），所以此刻 `saves/` 下任何 `.msav.part`
     *   都必然是上一轮的死件 —— 不存在"另一个导入正在进行"的可能。
     */
    public static int sweepParts(File savesDir) {
        if (savesDir == null || !savesDir.isDirectory()) return 0;
        File[] kids = savesDir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (!f.isFile()) continue;
            if (!f.getName().toLowerCase(Locale.US).endsWith(".msav.part")) continue;
            f.setWritable(true);
            if (f.delete()) n++;
        }
        return n;
    }

    /**
     * `.msav` 的压缩头判据（**zlib**，不是 gzip）。
     *
     * 🔴 这里原来判的是 gzip 魔数（`1f 8b`）—— **错的**：真实 `.msav` 头是 `78 9C`，
     *   游戏的 `MapIO.createMap` 用的也是 `InflaterInputStream`（zlib）。后果是每份正常存档
     *   都被警告"不是 gzip"，而真正读不了的 gzip 文件反被放行。判据已收进 {@link Util#isZlib}。
     */
    public static boolean isZlib(File f) {
        return Util.isZlib(f);
    }

    /** @deprecated 判据错的旧名（gzip）—— 保留只为不让老调用点编译不过，一律改用 {@link #isZlib} */
    @Deprecated
    public static boolean isGzip(File f) {
        return isZlib(f);
    }

    /**
     * 存档文件名安全化。
     *
     * ★ 这里**必须保留非 ASCII**（与 {@link Importer} 对 APK 文件名的做法刻意不同）：
     *   Mindustry 的存档列表直接列 `saves/` 下的文件名、去掉扩展名当显示名
     *   —— 也就是说**文件名就是玩家看到的存档名**。
     *   把中文一律换成下划线，等于把玩家的存档名改没了（自检里就撞到过：
     *   "桌 面 存档.msav" → "______.msav"）。
     *   所以只剔掉文件系统层面非法的字符：`/ \ : * ? " &lt; &gt; |` 与控制字符。
     *
     * 另外去掉首尾的点（隐藏文件 / Windows 兼容）并限长，避免制造打不开的路径。
     */
    public static String safeName(String display) {
        String n = display == null ? "" : display.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') continue;
            sb.append(c);
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        while (s.startsWith(".")) s = s.substring(1);
        s = s.trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        if (s.isEmpty()) s = "imported";
        if (!s.toLowerCase(Locale.US).endsWith(".msav")) s = s + ".msav";
        return s;
    }

    /**
     * 去掉 `.msav` 后缀 —— 给界面**预填**用。
     *
     * ★ 为什么预填不带后缀：游戏存档列表显示的名字本来就是"文件名去扩展名"，
     *   而改名框的提示写着"后缀会自动补上"；预填成 `xx.msav` 与这两处都对不上。
     *   与 {@link #safeName} 严格配对：`safeName(baseName(x)) == safeName(x)`。
     */
    public static String baseName(String n) {
        if (n == null) return "";
        return n.toLowerCase(Locale.US).endsWith(".msav")
                ? n.substring(0, n.length() - ".msav".length()) : n;
    }
}
