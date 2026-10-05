package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * **中转站**（2026-10-05，第 102 轮：地图/模组；同日第二批：存档/整槽）——
 * "挪不删"这件事的**唯一实现与唯一出口**。
 *
 * ── 它原来是什么样（用户 2026-10-05：「这个中转站像是个半成品啊」）────────────────
 *
 * 在此之前，"中转站"只是各个写入方内部的一个约定：
 * 覆盖或删除时把旧件 `renameTo` 进 `&lt;hub&gt;/maps-trash/` / `&lt;hub&gt;/mods-trash/`，
 * 然后**再也没有任何代码读过它们** ——
 * <pre>
 *   · 界面上没有入口，用户看不到里面有什么；
 *   · 没有"恢复"，所以那句「挪进中转站，不会直接删掉」在 App 里是**没有出口的承诺**；
 *   · 没有"清空"，占的空间不计入任何体检数字；
 *   · 文件名里**不带来源槽**（`yyyyMMdd-HHmmss-原名`）⇒ 就算做界面也没法默认放回原槽；
 *   · `stamp()` 只到秒 ⇒ 同一秒内两次同名覆盖会算出同一个路径，而 POSIX 上
 *     `renameTo` 落到已存在文件是**静默替换**（少一份、无痕迹）。
 * </pre>
 *
 * ── 现在它管四类东西 ────────────────────────────────────────────────────
 *
 * <pre>
 *   &lt;hub&gt;/maps-trash/   地图（被同名覆盖 / 被删除）          恢复 → &lt;槽&gt;/maps
 *   &lt;hub&gt;/mods-trash/   模组（被同名覆盖 / 跨槽复制覆盖）    恢复 → &lt;槽&gt;/mods
 *   &lt;hub&gt;/saves-trash/  存档（同名导入覆盖，第二批接上）      恢复 → &lt;槽&gt;/saves
 *   &lt;hub&gt;/slots-trash/  整个槽（删槽，第二批接上）            恢复 → &lt;槽父目录&gt;/slot-&lt;名&gt; + 它的备份
 * </pre>
 * ★ **类型由它所在的目录决定**，不看扩展名 —— 存档与地图是同一个格式（都叫 `.msav`），
 *   猜错就会"放回去放到了错的目录"。
 * ⚠️ **有意不进站的那条**：{@link AutoBackup} 按策略修剪旧快照（`Backup.delete`）——
 *   那是"省空间"的动作，进站等于策略失效、还顺手把站灌满。删槽时它的快照**跟着槽一起进站**
 *   （见 {@link #trashSlot}），所以"手动删快照"这件事本来就不存在入口。
 *
 * ── 本类的分工 ──────────────────────────────────────────────────────────
 *
 * <pre>
 *   纯逻辑（**不碰资源；只在"界面那条路"上碰 Context**）
 *     · 名字编解码（v2 = `戳__槽__原名`，同时**读得懂**旧的 v1 = `戳-原名`）
 *     · 列表 / 占用统计 · 修剪 · 单条彻底删除 · 清空
 *     · 恢复（**验过才放回去**：地图要过 {@link MsavMeta} 的解析器、存档要过 zlib 魔数；
 *       同名先回来问；整槽见 {@link #restoreSlot}）
 *   界面文案
 *     · 全在 {@link TrashText}（本类**一条中文都不写** —— SRC-02 的预算是"只许降"，
 *       新文件加中文会让台账必须跟着改；文案进资源才是 P3 定的方向）
 * </pre>
 *
 * 🔴 **恢复 = 挪回槽里，不是复制**：中转站里那份**离开**中转站（`renameTo`；跨文件系统
 *    才退化成"复制 + 校验 + 删原件"）。任何一步失败都**不许**让原件消失：
 *    · 复制路径只有在**长度校验通过之后**才删源；
 *    · 同名替换时旧的先进中转站，后面搬不动就把它**搬回来**（宁可回到原状）。
 */
public final class Trash {

    /** 中转站保留份数（超出按下标里那个时间戳删最旧）：它是"能取回"的兜底，不是第二份存档 */
    public static final int KEEP = 20;

    /**
     * **整个槽**的保留个数（2026-10-05 第二批）。
     *
     * 为什么与上一条分开：一份地图/存档是 KB 级，一个槽可以是**几十 MB**（设备上「冲突演示」槽
     * 就是 78 MB）⇒ 沿用 20 会让"中转站"悄悄占掉一两个 GB。3 个足够"刚删错就回来"，
     * 而且这个数字**写进了删除确认框的文案**（超出会被静默顶掉，不能不说）。
     */
    public static final int KEEP_SLOTS = 3;

    /** 四个中转站目录名（`&lt;hub&gt;/` 下）—— 前两个字面量与旧代码逐字相同，改名会让老用户的东西失联 */
    static final String DIR_MAPS = "maps-trash";
    static final String DIR_MODS = "mods-trash";
    static final String DIR_SAVES = "saves-trash";
    static final String DIR_SLOTS = "slots-trash";

    /** 槽容器里的两半（整槽进站时把"槽本体"与"它的备份"装在一起，恢复时一起回去） */
    static final String INNER_SLOT = "slot";
    static final String INNER_BACKUPS = "backups";

    /**
     * `<hub>/backups` —— 与 {@link Backup#rootDir} **同一个目录**。
     * ⚠️ 这里只拼路径、**不 import Backup**：{@link Backup#allReferencedShas} 反过来要扫中转站里的
     * 容器（否则整槽进站后，那些快照引用的对象会被 GC 当孤儿删掉，恢复回来就是坏的）
     * ⇒ 两个类互相引用会成环，所以路径常量各留一份、并在两边都写明出处。
     */
    static final String DIR_BACKUPS = "backups";

    /** 名字里的字段分隔符（★ 不是 `-`：槽名与原文件名都可能带 `-`，见 {@link #nameFor}） */
    static final String SEP = "__";

    private Trash() {}

    // ── 位置 ──────────────────────────────────────────────────────────────

    /** 地图的中转站：`&lt;hub&gt;/maps-trash/` */
    public static File mapsDir(Context ctx) {
        return new File(Data.hubDir(ctx), DIR_MAPS);
    }

    /** 模组的中转站：`&lt;hub&gt;/mods-trash/` */
    public static File modsDir(Context ctx) {
        return new File(Data.hubDir(ctx), DIR_MODS);
    }

    /** 存档的中转站：`&lt;hub&gt;/saves-trash/`（同名导入覆盖时旧的那份来这里） */
    public static File savesDir(Context ctx) {
        return new File(Data.hubDir(ctx), DIR_SAVES);
    }

    /** 整槽的中转站：`&lt;hub&gt;/slots-trash/`（每个条目是一个容器：槽本体 + 它的备份） */
    public static File slotsDir(Context ctx) {
        return new File(Data.hubDir(ctx), DIR_SLOTS);
    }

    /** 四个目录（清空 / 统计 / 列表要一起看 —— 用户眼里中转站只有一个） */
    public static File[] allDirs(Context ctx) {
        return new File[]{mapsDir(ctx), modsDir(ctx), savesDir(ctx), slotsDir(ctx)};
    }

    // ── 名字：编码 / 解码 ─────────────────────────────────────────────────

    /** 时间戳：`yyyyMMdd-HHmmss-SSS`（毫秒 —— 见类注释第 5 条，秒级会撞名） */
    public static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /**
     * 中转站里的落点名字：`&lt;戳&gt;__&lt;来源槽&gt;__&lt;原名&gt;`。
     *
     * ★ 为什么槽名要写进文件名（而不是另写一份索引）：一次性、原子、不会与真正的文件失配。
     * ★ 为什么分隔符是 `__`：槽名和原文件名**都**可能含 `-`（`yyyyMMdd-HHmmss-SSS` 那种戳本身就带），
     *   用 `-` 分词会有歧义（"原名以三位数字加横线开头"就会被解析错，且**恢复时落到错的文件名上**）。
     *   `__` 只需要保证**槽名那一格**不含它 —— 见 {@link #labelOf}。
     */
    public static String nameFor(String slot, String origName) {
        String s = labelOf(slot);
        String n = origName == null ? "" : origName;
        return stamp() + SEP + s + SEP + n;
    }

    /** 槽名 ⇒ 合法且不含分隔符的标签（认不出就没有标签 —— 界面据此说"来源不清楚"，**不猜**）。
     *  ⚠️ 空标签会让名字里出现连续两个分隔符（`戳____原名`），解析回来仍是空串，这是有意的。 */
    static String labelOf(String slot) {
        String s = slot == null ? "" : slot.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replace(SEP, "_").trim();
    }

    /**
     * 从"槽里的某个目录"反推槽名（写中转站名字时用）。
     *
     * 传 `…/slot-&lt;名&gt;/maps` 得到 `&lt;名&gt;`；传 `…/files/maps` 得到 `files`
     * （远古包模式本体借住 `files/`，那时槽名与目录名本来就对不上，如实写 `files` 更有用）。
     * 拿不准就退回父目录名 —— **宁可写个近似的标签，也不要写空**（空 = 界面上"来源不清楚"）。
     */
    public static String slotLabel(File dirInsideSlot) {
        File slotDir = dirInsideSlot == null ? null : dirInsideSlot.getParentFile();
        if (slotDir == null) return "";
        String n = slotDir.getName();
        if (n.startsWith(Paths.SLOT_PREFIX)) return n.substring(Paths.SLOT_PREFIX.length());
        return n;
    }

    /** 戳的两个形态（v2 带毫秒 / v1 只到秒）—— v1 必须**一直读得懂**：老用户的中转站里就是它 */
    private static final java.util.regex.Pattern STAMP_MS =
            java.util.regex.Pattern.compile("^\\d{8}-\\d{6}-\\d{3}$");
    private static final java.util.regex.Pattern STAMP_SEC =
            java.util.regex.Pattern.compile("^(\\d{8}-\\d{6})-(.+)$");

    static long parseStamp(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] pats = {"yyyyMMdd-HHmmss-SSS", "yyyyMMdd-HHmmss"};
        for (String p : pats) {
            try {
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat(p, java.util.Locale.US);
                f.setLenient(false);
                java.util.Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    // ── 条目 ──────────────────────────────────────────────────────────────

    /** 认得出是什么：地图 / 模组 / 存档 / 整个槽 / 其它（其它**不能恢复** —— 没有地方放回去） */
    public enum Kind { MAP, MOD, SAVE, SLOT, OTHER }

    /** 中转站里的一项 */
    public static final class Item {
        /** 中转站里那份的路径 */
        public File path;
        public Kind kind = Kind.OTHER;
        /** 来源槽（旧格式认不出来 ⇒ 空串 = 界面上"来源不清楚"） */
        public String slot = "";
        /** 原文件名（剥掉戳与槽；旧格式剥掉戳） */
        public String name = "";
        /** 挪进来的时间（毫秒；认不出戳 = 0） */
        public long stamp;
        public long bytes;
        public int files;
        /** 目录形态（模组可以是目录） */
        public boolean dirForm;
        /** 名字认得出来（v1/v2）；认不出也照样列出来，只是没有来源与时间 */
        public boolean parsed;

        /** 列表排序用：认不出戳就退回文件的修改时间，别把"新挪进来的"排到最后 */
        long sortKey() {
            return stamp > 0 ? stamp : (path == null ? 0 : path.lastModified());
        }
    }

    /**
     * 这一项是什么 —— **由它所在的目录决定**，不是看扩展名。
     *
     * 🔴 为什么不能再按扩展名判（2026-10-05 第二批）：**存档也是 `.msav`**（同一个格式），
     *   于是 `maps-trash/我的图.msav` 与 `saves-trash/我的存档.msav` 光看名字分不开 ——
     *   猜错的后果是"放回去放到了错的目录"（图跑进存档列表、存档跑进地图列表）。
     *   ⇒ 目录就是类型（我们自己写的目录名，稳定可靠）；不认识的目录才退回按名字猜。
     */
    static Kind kindIn(File parentDir, String name, boolean dirForm) {
        String p = parentDir == null ? "" : parentDir.getName();
        if (DIR_MAPS.equals(p)) return Kind.MAP;
        if (DIR_MODS.equals(p)) return Kind.MOD;
        if (DIR_SAVES.equals(p)) return Kind.SAVE;
        if (DIR_SLOTS.equals(p)) return Kind.SLOT;
        if (dirForm) return Kind.MOD;                     // 手工丢进来的目录：多半是目录形态的模组
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".msav")) return Kind.MAP;
        if (n.endsWith(".jar") || n.endsWith(".zip")) return Kind.MOD;
        return Kind.OTHER;
    }

    /** 这一类留几份（只有"整个槽"另有一套 —— 见 {@link #KEEP_SLOTS}） */
    public static int keepFor(Kind k) {
        return k == Kind.SLOT ? KEEP_SLOTS : KEEP;
    }

    /** 按**目录自己的类型**修剪（`maps-trash` 用 20、`slots-trash` 用 3） */
    public static int pruneIn(File dir) {
        return prune(dir, keepFor(kindIn(dir, "", false)));
    }

    /** 解析一项（**永远返回一个 Item**：认不出名字也照样能列出来、能彻底删除） */
    public static Item parse(File f) {
        Item it = new Item();
        it.path = f;
        String raw = f == null ? "" : f.getName();
        it.name = raw;
        it.dirForm = f != null && f.isDirectory();
        it.bytes = f == null ? 0 : (it.dirForm ? Data.sizeTree(f) : f.length());
        it.files = f == null ? 0 : (it.dirForm ? Data.countTree(f) : 1);
        String[] p = raw.split(SEP, 3);
        if (p.length == 3 && STAMP_MS.matcher(p[0]).matches()) {
            it.stamp = parseStamp(p[0]);
            it.slot = p[1];
            it.name = p[2];
            it.parsed = it.stamp > 0;
        } else {
            java.util.regex.Matcher m = STAMP_SEC.matcher(raw);
            if (m.matches()) {
                it.stamp = parseStamp(m.group(1));
                it.name = m.group(2);
                it.parsed = it.stamp > 0;
            }
        }
        it.kind = kindIn(f == null ? null : f.getParentFile(), it.name, it.dirForm);
        return it;
    }

    private static final Comparator<Item> BY_TIME_DESC = new Comparator<Item>() {
        @Override public int compare(Item a, Item b) {
            long x = a.sortKey(), y = b.sortKey();
            if (x != y) return x > y ? -1 : 1;
            return b.path.getName().compareTo(a.path.getName());
        }
    };
    private static final Comparator<Item> BY_TIME_ASC = new Comparator<Item>() {
        @Override public int compare(Item a, Item b) {
            long x = a.sortKey(), y = b.sortKey();
            if (x != y) return x < y ? -1 : 1;
            return a.path.getName().compareTo(b.path.getName());
        }
    };

    /** 某个中转站目录里的一项项（★ 跳过 `.nomedia` / `.part` 这类非用户数据，口径与备份一致） */
    public static List<Item> list(File dir) {
        List<Item> out = new ArrayList<>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (Data.contentSkipped(f)) continue;
            out.add(parse(f));
        }
        Collections.sort(out, BY_TIME_DESC);
        return out;
    }

    /** 两个目录合起来（新的在前）—— 用户眼里的"中转站"只有一个 */
    public static List<Item> listAll(Context ctx) {
        List<Item> out = new ArrayList<>();
        for (File d : allDirs(ctx)) out.addAll(list(d));
        Collections.sort(out, BY_TIME_DESC);
        return out;
    }

    /** 两个目录合起来占多少字节（中转站此前**不参与任何体检数字**） */
    public static long totalBytes(Context ctx) {
        long n = 0;
        for (File d : allDirs(ctx)) n += Data.sizeTree(d);
        return n;
    }

    // ── 修剪 ──────────────────────────────────────────────────────────────

    /**
     * 只保留最近 `keep` 项（**唯一实现** —— {@link MapFiles} / {@link Mods} 原来各有一份逐字相同的复制品）。
     *
     * ★ 排序用解析出来的时间戳（而不是文件名字典序）：v1/v2 两种名字混在一个目录里时，
     *   字典序在同一秒内会把它们交错，而"删最旧的"必须按**真实时间**。
     */
    public static int prune(File dir, int keep) {
        List<Item> all = list(dir);
        if (all.size() <= keep) return 0;
        Collections.sort(all, BY_TIME_ASC);
        int removed = 0;
        for (int i = 0; i < all.size() - keep; i++) {
            if (Data.deleteTree(all.get(i).path)) removed++;
        }
        return removed;
    }

    // ── 结果 ──────────────────────────────────────────────────────────────

    /**
     * 操作结果。`error` 是**给维护者看的 ASCII 原文**（报告 / 自检），用户看到的是
     * {@link TrashText#reason} 按 {@link #errCode} 取的那句话 —— 与 {@link Mods.PackResult} 同一套分工。
     */
    public static final class Result {
        /** 没有错 */
        public static final int T_NONE = 0;
        /** 那一份已经不在中转站里了（或不在这个目录下）；s1 = 路径 */
        public static final int T_NOT_IN_TRASH = 1;
        /** 目标槽不存在 */
        public static final int T_NO_TARGET = 2;
        /** 目标槽里已有同名；s1 = 文件名（**界面据此弹"替换吗"**，不许读文案，SRC-01） */
        public static final int T_NAME_TAKEN = 3;
        /** 地图文件游戏已经读不出来了（**恢复前必须过解析器**）；s1 = 文件名 */
        public static final int T_NOT_MAP = 4;
        /** 挪不开槽里原有的那份（什么都没改）；s1 = 文件名 */
        public static final int T_STASH_FAILED = 5;
        /** 放不回去（原件仍在中转站里）；s1 = 文件名 */
        public static final int T_MOVE_FAILED = 6;
        /** 游戏在跑 */
        public static final int T_GAME_RUNNING = 7;
        /** 既不是地图也不是模组（没有地方放回去）；s1 = 文件名 */
        public static final int T_NO_KIND = 8;
        /** 建不了目标目录；s1 = 目录 */
        public static final int T_MKDIR = 9;
        /** 删不掉（文件被占用之类）；s1 = 文件名 */
        public static final int T_DELETE_FAILED = 10;
        /** 要动的那个槽**正被游戏用着**（当前槽不能删、也不能被"替换"）；s1 = 槽名 */
        public static final int T_CURRENT_SLOT = 11;
        /** 槽名不合法（空、保留名、带路径符号…）；s1 = 用户填的原文 */
        public static final int T_BAD_NAME = 12;

        /** **所有**错误码（自检**遍历**它：`TrashText.reason` 的 switch 有 default 兜底，
         *  漏映射**不崩**、界面上只是静默退化成"原因不明" ⇒ 只有遍历才抓得住） */
        public static final int[] ALL_CODES = {
                T_NOT_IN_TRASH, T_NO_TARGET, T_NAME_TAKEN, T_NOT_MAP, T_STASH_FAILED,
                T_MOVE_FAILED, T_GAME_RUNNING, T_NO_KIND, T_MKDIR, T_DELETE_FAILED,
                T_CURRENT_SLOT, T_BAD_NAME,
        };

        public boolean ok;
        public int errCode = T_NONE;
        /** 码带的**中性参数**（文件名 / 目录名 —— 中性参数可以进第一层，异常原文不行） */
        public String errS1;
        /** 失败原因原文（**ASCII，给报告与自检看**；⚠️ 别直接显示给用户） */
        public String error;
        /** 目标已被占：界面据此弹"替换 / 取消"（★ 判据字段，不许看 {@link #error} 的字面内容） */
        public boolean nameTaken;
        public File dest;
        public int count;
        public long bytes;

        Result fail(int code, String ascii, String s1) {
            errCode = code;
            errS1 = s1;
            error = ascii;
            return this;
        }
    }

    /** 造一个"只带错误码"的结果：**给自检遍历所有码用** */
    static Result withCode(int code, String s1) {
        return new Result().fail(code, "code " + code, s1);
    }

    // ── 单条彻底删除 / 清空 ───────────────────────────────────────────────

    /**
     * 彻底删掉中转站里的一项（**不可逆** —— 只由界面的二次确认调用）。
     *
     * 🔴 安全判据：它必须是 `trashDir` 的**直接子项**。少了这条，一个传错路径的调用
     *   就能删掉中转站外面的东西，而且**没有症状**（与 {@link MapFiles#deleteToTrash} 同一条纪律）。
     */
    public static Result remove(File path, File trashDir) {
        Result r = new Result();
        if (path == null || !path.exists()) {
            return r.fail(Result.T_NOT_IN_TRASH, "gone: " + path, String.valueOf(path));
        }
        if (!isDirectChild(path, trashDir)) {
            return r.fail(Result.T_NOT_IN_TRASH, "not a direct child: " + path, path.getName());
        }
        r.bytes = path.isDirectory() ? Data.sizeTree(path) : path.length();
        r.count = path.isDirectory() ? Data.countTree(path) : 1;
        if (!Data.deleteTree(path)) {
            return r.fail(Result.T_DELETE_FAILED, "delete failed: " + path, path.getName());
        }
        pruneIn(trashDir);
        r.ok = true;
        return r;
    }

    /** 清空（可以一次给四个目录）；返回删掉的份数与释放的字节 */
    public static Result empty(File... dirs) {
        Result r = new Result();
        if (dirs != null) {
            for (File d : dirs) {
                for (Item it : list(d)) {          // 先列出来：删完就算不出份数与体积了
                    r.count++;
                    r.bytes += it.bytes;
                    if (!Data.deleteTree(it.path)) {
                        r.errCode = Result.T_DELETE_FAILED;
                        r.error = "delete failed: " + it.path;
                        r.errS1 = it.path.getName();
                    }
                }
            }
        }
        r.ok = r.errCode == Result.T_NONE;
        return r;
    }

    // ── 恢复 ──────────────────────────────────────────────────────────────

    /**
     * 把中转站里的一份**放回** `targetDir`（纯逻辑，不碰 Context —— 自检直接喂它）。
     *
     * 顺序（每一步失败都**不留半成品**）：
     * <pre>
     *   ① 安全判据：itemPath 必须是 trashDir 的直接子项；
     *   ② 认得出是什么吗（OTHER ⇒ 拒绝：没有地方放回去）；
     *   ③ 地图：**必须过 `MsavMeta.read(f, true)`**（与导入同一把尺子，不是看扩展名）；
     *   ④ 同名：`overwrite=false` ⇒ 只回一个 `nameTaken`，界面去问用户；
     *      `overwrite=true` ⇒ 旧的先挪进中转站（**再进一次中转站，不是删掉**）；
     *   ⑤ 就位：`renameTo`，跨文件系统退化成"复制 + 校验 + 删原件"；
     *      失败 ⇒ 把 ④ 挪走的那份**搬回来**，并且**原件仍留在中转站**。
     * </pre>
     *
     * @param overwrite true ⇒ 同名时替换（旧的进中转站）
     * @param trashDir  同名替换时旧件挪去哪（**必须是这一份所在的那个中转站目录**）
     */
    public static Result restore(File itemPath, File targetDir, boolean overwrite, File trashDir) {
        Result r = new Result();
        if (itemPath == null || !itemPath.exists()) {
            return r.fail(Result.T_NOT_IN_TRASH, "gone: " + itemPath, String.valueOf(itemPath));
        }
        if (!isDirectChild(itemPath, trashDir)) {
            return r.fail(Result.T_NOT_IN_TRASH, "not a direct child: " + itemPath, itemPath.getName());
        }
        Item it = parse(itemPath);
        if (it.kind == Kind.OTHER) {
            return r.fail(Result.T_NO_KIND, "unknown kind: " + it.name, it.name);
        }
        if (it.kind == Kind.SLOT) {
            // 整槽不走这条路（它要动两处：槽本体 + 它的备份）—— 见 restoreSlot
            return r.fail(Result.T_NO_KIND, "slot item needs restoreSlot: " + it.name, it.name);
        }
        if (targetDir == null) {
            return r.fail(Result.T_NO_TARGET, "no target dir", null);
        }
        if (it.kind == Kind.MAP && !MsavMeta.read(itemPath, true).ok) {
            return r.fail(Result.T_NOT_MAP, "map unreadable: " + itemPath, it.name);
        }
        // ★ 存档与地图**同一个格式**（都是 zlib + MSAV），但验收纪律不同：
        //   地图走完整解析器（导入那条路一直是这么做的），存档按**导入时的尺子**只判 zlib 魔数
        //   （`Msav.stage` 判的就是它）—— 几十 MB 的存档没必要为了"放回去"整份解压一遍。
        if (it.kind == Kind.SAVE && !Util.isZlib(itemPath)) {
            return r.fail(Result.T_NOT_MAP, "save not zlib: " + itemPath, it.name);
        }
        if (!targetDir.isDirectory() && !targetDir.mkdirs() && !targetDir.isDirectory()) {
            return r.fail(Result.T_MKDIR, "mkdir failed: " + targetDir, targetDir.getAbsolutePath());
        }
        File dest = new File(targetDir, it.name);
        File stash = null;
        if (dest.exists()) {
            if (!overwrite) {
                // ★ 回来问一声：这一趟**什么都没动**
                r.nameTaken = true;
                return r.fail(Result.T_NAME_TAKEN, "target exists: " + dest, it.name);
            }
            if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) {
                return r.fail(Result.T_STASH_FAILED, "trash unusable: " + trashDir,
                        dest.getName());
            }
            stash = new File(trashDir, nameFor(slotLabel(targetDir), dest.getName()));
            if (!move(dest, stash)) {
                return r.fail(Result.T_STASH_FAILED, "could not stash: " + dest, dest.getName());
            }
        }
        if (!move(itemPath, dest)) {
            if (stash != null) move(stash, dest);      // 回滚：宁可回到原状
            return r.fail(Result.T_MOVE_FAILED, "could not restore: " + itemPath, it.name);
        }
        pruneIn(trashDir);
        r.ok = true;
        r.dest = dest;
        r.bytes = it.bytes;
        r.count = it.files;
        return r;
    }

    // ── 整个槽：进站（容器）/ 出站 ────────────────────────────────────────

    /**
     * **把一个槽整个挪进中转站**（2026-10-05 第二批，把"删槽 = 硬删"这条也接上）。
     *
     * 容器布局（一个条目 = 这个槽的全部）：
     * <pre>
     *   &lt;hub&gt;/slots-trash/&lt;戳&gt;__&lt;槽名&gt;__&lt;槽名&gt;/
     *       ├ slot/                  ← 原来是 &lt;槽父目录&gt;/slot-&lt;槽名&gt;
     *       └ backups/&lt;槽名&gt;/&lt;快照&gt;/  ← 原来是 &lt;hub&gt;/backups/&lt;槽名&gt;/&lt;快照&gt;（没有就不建）
     * </pre>
     * 🔴 `backups/` 下**还要再套一层槽名**（不是直接把 `&lt;hub&gt;/backups/&lt;槽名&gt;` 改名叫 `backups`）：
     *   这样容器里的 `backups/` 本身就是一个"备份根"，与 {@link Backup#allReferencedShas} 扫
     *   `&lt;根&gt;/&lt;槽&gt;/&lt;快照&gt;/manifest` 的层级**逐层对齐** —— 层级差一层，那个方法就会
     *   一个 manifest 都扫不到，而症状是"整槽恢复回来快照是坏的"（自检 ㊴② 就是拿这个抓出来的）。
     * ★ 为什么备份要跟着走：槽与它的快照是**一件事**。只把槽留下一半，恢复回来会发现
     *   快照全没了（而那时用户已经以为"删掉的东西都能找回来"）—— 那就是静默丢数据。
     * ★ 为什么两步里任何一步失败都要**回滚**：宁可回到"什么都没发生"，
     *   也不要留一个"槽没了但备份还在（或反过来）"的中间态。
     * 🔴 调用方（UI）在整槽被**彻底删除**之后要 `Backup.scheduleGc()`：
     *   那些快照引用的对象池对象到那时才真的没人引用（`Backup.allReferencedShas` 会扫本站容器，
     *   所以**只要容器还在，对象就不会被回收** —— 这正是"能恢复"的前提）。
     *
     * @param backupsRoot `&lt;hub&gt;/backups`（由调用方传：{@link Backup#rootDir}）。
     *                    这里刻意不 import Backup —— 那边反过来要扫本站容器，成环不好看（见 DIR_BACKUPS）
     */
    public static Result trashSlot(Context ctx, String slotName, File backupsRoot) {
        Result r = new Result();
        String name = slotName == null ? "" : slotName.trim();
        if (name.isEmpty()) {
            return r.fail(Result.T_NO_TARGET, "empty slot name", null);
        }
        if (name.equals(Data.currentSlot(ctx))) {
            return r.fail(Result.T_CURRENT_SLOT, "current slot: " + name, name);
        }
        File slotDir = Data.slotDir(ctx, name);
        if (slotDir == null || !slotDir.exists()) {
            return r.fail(Result.T_NO_TARGET, "no slot dir: " + slotDir, name);
        }
        File dir = slotsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return r.fail(Result.T_STASH_FAILED, "trash unusable: " + dir, name);
        }
        r.bytes = Data.sizeTree(slotDir);
        r.count = Data.countTree(slotDir);
        File container = new File(dir, nameFor(name, name));
        File inner = new File(container, INNER_SLOT);
        if (!container.isDirectory() && !container.mkdirs() && !container.isDirectory()) {
            return r.fail(Result.T_STASH_FAILED, "container mkdir failed: " + container, name);
        }
        if (!move(slotDir, inner)) {
            Data.deleteTree(container);
            return r.fail(Result.T_STASH_FAILED, "could not stash slot: " + slotDir, name);
        }
        File bk = backupsRoot == null ? null : new File(backupsRoot, name);
        if (bk != null && bk.isDirectory()) {
            File innerBk = new File(new File(container, INNER_BACKUPS), name);
            File bkParent = innerBk.getParentFile();
            if (!bkParent.isDirectory() && !bkParent.mkdirs() && !bkParent.isDirectory()) {
                move(inner, slotDir);
                Data.deleteTree(container);
                return r.fail(Result.T_STASH_FAILED, "backups dir mkdir failed: " + bkParent, name);
            }
            if (!move(bk, innerBk)) {
                move(inner, slotDir);                       // 回滚：槽本体放回去
                Data.deleteTree(container);
                return r.fail(Result.T_STASH_FAILED, "could not stash backups: " + bk, name);
            }
        }
        pruneIn(dir);
        r.ok = true;
        r.dest = container;
        return r;
    }

    /**
     * 把整槽容器**放回去**（纯逻辑）。
     *
     * @param newName 目标槽名（null / 空 = 用容器里记的那个原名）
     * @return 名字被占时 `nameTaken=true`（界面据此去问"换个名字"），**这一趟什么都没动**
     */
    public static Result restoreSlot(File itemPath, String newName, File slotsParent, File backupsRoot) {
        Result r = new Result();
        if (itemPath == null || !itemPath.exists() || !itemPath.isDirectory()) {
            return r.fail(Result.T_NOT_IN_TRASH, "gone: " + itemPath, String.valueOf(itemPath));
        }
        Item it = parse(itemPath);
        String name = newName == null || newName.trim().isEmpty() ? it.slot : newName.trim();
        String clean = Data.sanitizeSlot(name);
        if (clean == null) {
            return r.fail(Result.T_BAD_NAME, "bad slot name: " + name, name);
        }
        name = clean;
        if (slotsParent == null) {
            return r.fail(Result.T_NO_TARGET, "no slots parent", name);
        }
        File dest = new File(slotsParent, Paths.SLOT_PREFIX + name);
        if (dest.exists()) {
            r.nameTaken = true;                            // 回来问一声（"换个名字放回去"）
            return r.fail(Result.T_NAME_TAKEN, "slot exists: " + dest, name);
        }
        File inner = new File(itemPath, INNER_SLOT);
        if (!inner.isDirectory()) {
            return r.fail(Result.T_NO_KIND, "container has no slot/: " + itemPath, name);
        }
        r.bytes = Data.sizeTree(inner);
        r.count = Data.countTree(inner);
        if (!move(inner, dest)) {
            return r.fail(Result.T_MOVE_FAILED, "could not restore slot: " + inner, name);
        }
        // 备份（有就一起回去）——放不回去**不算失败**：槽本体已经就位了，
        // 把整件事判死反而会让用户以为"没恢复"，而槽其实回来了。
        // ⚠️ 容器里那一层用的是**原来的槽名**（`it.slot`），而放回去可能换了名字
        //    ⇒ 读的时候用原名、落点用新名（写错就会"槽回来了、快照没回来"，自检 ㊴③ 抓的就是它）。
        File bk = new File(new File(itemPath, INNER_BACKUPS), it.slot);
        if (bk.isDirectory() && backupsRoot != null) {
            File bkDest = new File(backupsRoot, name);
            if (!bkDest.exists()) {
                if (!move(bk, bkDest)) {
                    android.util.Log.w("MDTLauncher",
                            "restore slot backups failed: " + bk + " -> " + bkDest);
                }
            }
        }
        Data.deleteTree(itemPath);                          // 容器空了就收掉（顺手清掉残留）
        pruneIn(itemPath.getParentFile());
        r.ok = true;
        r.dest = dest;
        return r;
    }

    /**
     * 界面走的那条路：**先判游戏在不在跑**，再按类型解析目标，然后交给
     * {@link #restore}（单份）或 {@link #restoreSlot}（整槽）。
     *
     * ★ 为什么门禁在这里而不是只放界面：地图/模组/存档都在槽里，游戏正在读它们 ——
     *   这一条与 {@link MapFiles#deleteToTrash} 里那道是同一个判据（那边也写在数据层）。
     *
     * @param slot 除整槽之外：**目标槽名**；整槽：**放回去之后叫什么名字**（通常就是它原来的名字）
     */
    public static Result restoreFrom(Context ctx, Item it, String slot, boolean overwrite) {
        Result r = new Result();
        if (it == null || it.path == null) {
            return r.fail(Result.T_NOT_IN_TRASH, "no item", null);
        }
        if (Data.gameAlive(ctx)) {
            return r.fail(Result.T_GAME_RUNNING, "game is running", null);
        }
        if (it.kind == Kind.SLOT) {
            String want = slot == null || slot.trim().isEmpty() ? it.slot : slot.trim();
            if (want.equals(Data.currentSlot(ctx))) {
                return r.fail(Result.T_CURRENT_SLOT, "current slot: " + want, want);
            }
            return restoreSlot(it.path, want, Paths.slotParent(ctx),
                    new File(Data.hubDir(ctx), DIR_BACKUPS));
        }
        File slotDir = Data.dirOf(ctx, slot);
        if (slotDir == null || !slotDir.isDirectory()) {
            return r.fail(Result.T_NO_TARGET, "no slot dir: " + slotDir, slot);
        }
        String sub = it.kind == Kind.MAP ? "maps" : (it.kind == Kind.SAVE ? "saves" : "mods");
        return restore(it.path, new File(slotDir, sub), overwrite, it.path.getParentFile());
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /** 必须是 `dir` 的直接子项（canonical 比较 —— 防 `../` 这类传参） */
    static boolean isDirectChild(File f, File dir) {
        if (f == null || dir == null) return false;
        try {
            File p = f.getCanonicalFile().getParentFile();
            return p != null && p.equals(dir.getCanonicalFile());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 挪一个文件/目录（**失败一定要让源还在**）。
     *
     * `renameTo` 跨文件系统会失败（{@link Mods} 里当场撞过：早期 trash 在内部存储、
     * 槽在外部存储）⇒ 退化成"复制 + 校验 + 删原件"：**只有校验通过之后才删源**，
     * 否则"复制失败 + 删了源"就是**静默丢数据**（本工程最忌讳的那一类）。
     */
    static boolean move(File from, File to) {
        if (from == null || to == null) return false;
        try {
            if (from.renameTo(to)) return true;
            if (from.isDirectory()) {
                if (!copyTree(from, to)) return false;
            } else {
                Util.copyFile(from, to);
                if (to.length() != from.length()) return false;
            }
            return Data.deleteTree(from);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 递归复制（中转站那条路上只有目录形态的模组会走到） */
    static boolean copyTree(File from, File to) {
        if (from == null || to == null) return false;
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs() && !to.isDirectory()) return false;
            File[] fs = from.listFiles();
            if (fs == null) return false;
            for (File f : fs) {
                if (!copyTree(f, new File(to, f.getName()))) return false;
            }
            return true;
        }
        try {
            Util.copyFile(from, to);
            return to.length() == from.length();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 人读的时间：**今年内只写 `MM-dd HH:mm`**，跨年才带年份（本地时区）。
     * 口径与 {@link MsavMeta#savedText} 一致 —— 中转站列表和存档列表要能直接对比着看。
     */
    public static String timeText(long ms) {
        if (ms <= 0) return "";
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(ms);
            int y = c.get(java.util.Calendar.YEAR);
            int nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    y == nowYear ? "MM-dd HH:mm" : "yyyy-MM-dd HH:mm", java.util.Locale.US);
            return f.format(new java.util.Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }
}
