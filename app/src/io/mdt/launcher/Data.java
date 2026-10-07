package io.mdt.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 数据布局与存档槽（模块边界：依赖 Paths/Util，被 UI / GameSlot 调用）。
 *
 * 关键事实（探针 A 组实测，见探针 README §三）：
 *   · 游戏**默认**数据根 = getExternalFilesDir(null) = /storage/emulated/0/Android/data/&lt;HUB包名&gt;/files
 *     路径由**包名**决定，改不了 ⇒ 若什么都不做，多版本天然共享同一份存档且**会互相破坏**
 *     （官方 159.7 跑完把 MindustryX 的 cache/ 整个删掉，settings.bin 轮流覆盖）。
 *   · 所以 HUB 自己的东西**不能放数据根里** ⇒ 放平级的 hub/（见 {@link Paths}）。
 *
 * ⚠️ 槽名的唯一来源是内部文件 app_hub/mdt-slot.txt（**不是** config.json）：
 *   槽由 :game 进程切换，而 config.json 由主进程维护，两个进程同时写会打架。
 *
 * ── F0（2026-10-02）：从「改名交换」升级为**真隔离** ─────────────────────────
 * · 【旧】当前槽 = 数据根 `files/` 本体，靠启动前 `files ↔ slot-<名>` 改名交换；
 *   代价：同一时刻只能一个槽在线、中途崩溃可能留半交换、交换期间 HUB 自己也读不到。
 * · 【新】**每个槽（含当前槽）恒定住在 `slot-<槽名>`**，启动前只
 *   `System.setProperty("mindustry.data.dir", 该目录)` ⇒ 不动文件、无中间态、可并行。
 *   ⇒ {@link #dirOf} 于是变成一条**无特判**的规则（见其 javadoc）。
 * · 【兜底】`build ≤ 146` 的包 dex 里**没有**那个属性（146 无 / 147 有，已收口）
 *   ⇒ 它们只能写 `files/` ⇒ 那一刻把目标槽**改名**到 `files/`，并用
 *   {@link #legacyOwner} 标记记下"本体现在住 `files/`"，`dirOf` 据此翻译回去。
 *   这条路径**同时**是"改造前的老布局"的自然表示 ⇒ 一次性迁移（{@link #reclaimLegacy}）
 *   失败时也落在它上面（宁可停在老布局，也绝不让用户的存档**看起来**消失）。
 *
 * ── M3 ────────────────────────────────────────────────────────────────────
 * · 槽的显式管理（建/改名/删）与「版本 → 槽」的分配（分配存 config.json）。
 * · 备份/恢复的目录解析 {@link #dirOf}：恒按路径读，**不需要**交换目录。
 * · **数据根体检** {@link #healthScan}：找出游戏 copy 机制留下的 HUB 冗余副本
 *   （见 {@link Paths} 头注释）—— F0 后扫描根是"所有槽 + 旧 `files/`"。
 */
public final class Data {
    private static final String TAG = "MDTLauncher";

    /** 不隔离：所有版本共用一个数据根（调试用）。保留值，不能作为普通槽名。 */
    public static final String SLOT_SHARED = Paths.SLOT_SHARED;

    /** 初始槽名 */
    public static final String SLOT_DEFAULT = Paths.SLOT_DEFAULT;

    /** 槽目录名前缀（★ 唯一来源在 {@link Paths#SLOT_PREFIX}） */
    static final String SLOT_PREFIX = Paths.SLOT_PREFIX;

    /**
     * 数据根体检対象：这三项是 HUB 自己的东西，**正常不该出现在数据根**。
     * 出现 = 游戏首次启动时把 `files/` 复制过来的历史残留（见 Paths 头注释）。
     */
    public static final String[] REDUNDANT_NAMES = {"config.json", "import", "natives"};

    /**
     * ★ 槽内容的排除名单 —— 「**除这些以外全打**」。
     *
     * ★★ 这张表是**「备份 / 恢复」与「整槽导出」共用的唯一口径**
     *   （实现见 {@link #slotExcluded} / {@link #contentRoots}，别再写第二份）。
     *
     *   ── F19（2026-10-02）：备份从「白名单」改成这张表 ──────────────────────
     *   备份原先用的是**白名单**（只打 `saves / settings.bin / settings_backup.bin /
     *   settings_backups / mods`）。用户实测后一句话否掉了它：
     *   **「备份槽不要用白名单，有的模组会乱放配置文件」**。
     *   实证（桌面端真实数据根 2026-10-02）：`logic-tool/`（418 KB / 33 个 JSON）是
     *   逻辑工具 mod 放**数据根顶层**的用户创作 —— 白名单**不认识它、也没法在恢复时
     *   补回来**；同类还有 `random-mod/`、`server_list.json`、`mods/config/` 等。
     *   ⇒ 判据只能是「**凡是我们没法重造的用户数据都要在**」，而这**没法靠枚举实现**
     *     （模组是谁写的、放哪儿，我们事先不可能知道）⇒ 只能反过来枚举"哪些确实不必带"。
     *
     *   ── 为什么"备份"和"导出"可以共用一个口径 ────────────────────────────
     *   两者问的是**同一个问题**："这个槽里哪些东西值得带走"。
     *   反过来（导出全、备份少）会得到一个**没有任何症状**的坏状态：
     *   用户拿备份恢复完，发现地图/蓝图/模组配置没了，而备份本身"看起来是成功的"。
     *
     * 排除三类（都**不是用户数据**）：
     *   · {@link #REDUNDANT_NAMES} —— HUB 自己的残留副本（体检的对象），对游戏毫无意义；
     *   · `tmp` —— 游戏自己声明是临时目录（`Vars.tmpDirectory`），它导入数据时也会先删掉；
     *   · `cache` —— ★ 实测依据（{@link Paths} 头注释）：游戏**每次启动**都会先
     *     `getDataDirectory().child("cache").deleteDirectory()` 把它删掉
     *     ⇒ 打包它等于把"下次启动必然被删的东西"塞进去，白占体积；
     *   · 另有 {@link #contentSkipped} 负责的通用项：`.` 开头、`*.part`、`*.tmp`。
     *
     * ⇒ 打包结果**正好是游戏原生 `exportData` 的超集**（原生只打 settings.bin + maps +
     *   saves + mods + schematics + assetCache；我们多出 previews / server_list.json /
     *   settings_backups / be_builds / screenshots / settings_backup.bin /
     *   last_log.txt / files_moved_103），
     *   所以导出的 zip 游戏自己也能「设置 → 数据 → 导入数据」直接吃。
     *   ⚠️ 与原生唯一的差别就是 `previews/`（地图预览，可再生但重建要花时间）我们**保留** ——
     *     用户选的就是"比原生全"，而预览丢了不会报错、只会让地图列表空一块，最难自己发现。
     *
     * ⚠️ **故意保留、不算"垃圾"的几项**（别顺手加进这张表）：
     *   `crashes/` 与 `last_log.txt` —— 它们是**故障前的唯一证据**：
     *   存档坏掉往往就是先崩几次，把日志备走才有得查；体积又都是 KB 级。
     */
    public static final String[] SLOT_EXCLUDE = {"config.json", "import", "natives", "tmp", "cache"};

    // ⚠️ 这里原来还有一个 `WIPE_DIRS = {saves, maps, schematics, assetCache}`
    //    （F6c 的「先清空」名单）。2026-10-05（第 104 轮）**删掉了**：
    //    "清空"现在只有一处实现 `SlotWrite.wipeSlot`，口径 = `contentRootsOf`（槽的全部内容 − 排除），
    //    而 `WIPE_DIRS` 那份**刻意不删 mods** 的老名单与用户要的「覆盖 = 完全替换」相冲。
    //    ⇒ 留着它就是第二个口径（迟早有人照着它再写一份清空）。

    private Data() {}

    /** 某个顶级条目名要不要从「槽内容」里排除（备份与整槽导出共用） */
    public static boolean slotExcluded(String name) {
        if (name == null || name.isEmpty()) return true;
        for (String n : SLOT_EXCLUDE) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    /**
     * 递归遍历时的"这一项不算槽内容"判据 —— **顶级与任意深度共用同一份**。
     *
     *   ① `.` 开头：`.nomedia` / `.trashed` 之类，是平台或工具的痕迹，不是用户数据；
     *   ② `*.part` / `*.tmp`：**写一半的文件**。
     *      ⚠️ 半成品**绝不能进备份** —— 它代表"正在写"，恢复它等于把一个残文件当成品
     *         写回槽里，而**看起来完全正常**（大小、时间都有）。本工程自己的 `.msav`
     *         导入也是先落 `.part` 再改名（见 {@link Importer}）。
     *
     * ★ 唯一实现：{@link Exporter#addTree} 与 {@link Backup} 的递归遍历都走这里。
     *   以前这是 {@code Exporter.skip} 的私有逻辑，备份那条路**没有它** ⇒
     *   "导出的 zip 里没有、备份里却有一份半成品"这种不一致不会有任何症状。
     */
    public static boolean contentSkipped(File f) {
        if (f == null) return true;
        String n = f.getName();
        if (n.isEmpty()) return true;
        return n.startsWith(".") || n.endsWith(".part") || n.endsWith(".tmp");
    }

    // ── 基本位置 ──────────────────────────────────────────────────────────

    /** HUB 自己的外部目录 —— 与游戏数据根**平级**，游戏不认识它 */
    public static File hubDir(Context ctx) {
        return Paths.externalHub(ctx);
    }

    /**
     * 游戏**实际**会读的那个数据根 = 当前槽的本体（{@link #dirOf} 的当前槽那一支）。
     * ★ 不是 `getExternalFilesDir(null)` —— F0 之后那个只是空壳（`shared` 模式下才是它）。
     */
    public static File dataRoot(Context ctx) {
        return dirOf(ctx, currentSlot(ctx));
    }

    /** 所有 `slot-*` 的父目录（= `externalRoot` 的父目录） */
    static File slotParent(Context ctx) {
        return Paths.slotParent(ctx);
    }

    static File slotDir(Context ctx, String name) {
        return Paths.slotDir(ctx, name);
    }

    // ── 当前槽 ────────────────────────────────────────────────────────────

    /** 当前槽名（无记录 = "default"）。★ 唯一读取实现在 {@link Paths#readSlotName}。 */
    public static String currentSlot(Context ctx) {
        return Paths.readSlotName(ctx);
    }

    private static void rememberSlot(Context ctx, String name) {
        try {
            FileWriter w = new FileWriter(Paths.slotRecord(ctx));
            try {
                w.write(name);
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "remember slot failed: " + t);
        }
    }

    /**
     * 槽 → **实际承载其数据的目录**（唯一的路径解析规则，UI / 备份 / 导出全走它）。
     *
     * 三条分支，加起来就是全部规则：
     *   ① `slot` 为空 ⇒ 当作当前槽（历史行为）；
     *   ② `shared` ⇒ {@link Paths#externalRoot}（= 游戏的默认根，不做任何隔离）；
     *   ③ `slot == legacyOwner(ctx)` ⇒ 也是 {@link Paths#externalRoot} ——
     *      **该槽的本体此刻被改名躺在 `files/` 里**，因为正在跑/刚跑过一个
     *      `build ≤ 146` 的远古包（它不认 `mindustry.data.dir`，只会写默认根）。
     *   其余一律 `slot-<槽名>`。
     *
     * ★★ F0 之前这里是「当前槽 = 数据根本体」的**特判**；现在当前槽和其它槽**同构**，
     *   所以特判消失、只剩"本体住在哪"这一个问题（= ③ 那条标记）。
     * ⚠️ ③ 必须留着：删了它，远古包跑过之后 `dirOf(当前槽)` 会指向一个不存在的
     *   `slot-<名>`（本体在 `files/`）⇒ 用户的存档**看起来凭空消失**（文件其实还在）。
     */
    public static File dirOf(Context ctx, String slot) {
        if (slot == null || slot.isEmpty()) slot = currentSlot(ctx);
        if (SLOT_SHARED.equals(slot)) return Paths.externalRoot(ctx);
        if (slot.equals(legacyOwner(ctx))) return Paths.externalRoot(ctx);
        return slotDir(ctx, slot);
    }

    // ── 远古包的"本体临时借住 files/"标记（F0 兜底路径）──────────────────────

    /**
     * 缓存。⚠️ 跨进程：`:game` 在 {@link #prepareSlot} 里写它，主进程只读
     *   ⇒ 主进程必须靠 {@link #reloadLegacyOwner} 刷新（现在挂在
     *   `MainActivity.onResume()`，与 `Config.reload` 同一处，见 REF 铁律⑦）。
     * `null` = 还没读盘。
     */
    private static String sLegacyOwner;

    /** 哪个槽的本体此刻被改名躺在 `files/`；没有则返回 null */
    public static String legacyOwner(Context ctx) {
        if (sLegacyOwner == null) {
            String s = "";
            try {
                s = Util.readText(Paths.legacyRecord(ctx)).trim();
            } catch (Throwable ignored) {
            }
            sLegacyOwner = s;
        }
        return sLegacyOwner.isEmpty() ? null : sLegacyOwner;
    }

    /** 丢掉缓存，下次 {@link #legacyOwner} 重新读盘（主进程跨进程刷新用） */
    public static void reloadLegacyOwner() {
        sLegacyOwner = null;
    }

    private static void writeLegacyOwner(Context ctx, String slot) {
        try {
            FileWriter w = new FileWriter(Paths.legacyRecord(ctx));
            try {
                w.write(slot == null ? "" : slot);
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "write legacy owner failed: " + t);
        }
        sLegacyOwner = slot == null ? "" : slot;
    }

    // ── 槽名规范化与清单 ──────────────────────────────────────────────────

    /**
     * 槽名安全化。非法返回 null，由调用方给用户报错。
     * 规则：1~40 字符、去掉路径分隔符与控制字符、不许是 `shared`（保留值）、
     * 不许以 `slot-` 开头（会与目录前缀撞）、不许 `.` / `..`。
     */
    public static String sanitizeSlot(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('/', '_').replace('\\', '_');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
        }
        s = sb.toString().trim();
        if (s.isEmpty() || s.length() > 40) return null;
        if (".".equals(s) || "..".equals(s)) return null;
        if (SLOT_SHARED.equals(s)) return null;
        if (s.startsWith(SLOT_PREFIX)) return null;
        return s;
    }

    /**
     * 磁盘上存在的槽名（按名字排序）。
     * ★ F0 后当前槽**也在这个列表里**（它的本体就是 `slot-<当前名>`）。
     *   调用方 {@link #allSlots} 会把"当前槽"排到最前并从本列表里跳过，不重复。
     * ⚠️ 唯一例外：当前槽的本体正被远古包借住 `files/` 时，`slot-<当前名>` 不存在
     *   ⇒ 它不会出现在这里 —— `allSlots` 仍会用 {@link #dirOf} 把它补成第一行（计数正确）。
     */
    public static List<String> slotNames(Context ctx) {
        List<String> out = new ArrayList<>();
        File parent = slotParent(ctx);
        File[] ps = parent == null ? null : parent.listFiles();
        if (ps == null) return out;
        for (File f : ps) {
            if (f.isDirectory() && f.getName().startsWith(SLOT_PREFIX)) {
                String n = f.getName().substring(SLOT_PREFIX.length());
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 一个槽的可展示信息 */
    public static final class Slot {
        public final String name;
        public final File dir;
        public final boolean active;
        public final int files;
        public final long bytes;

        Slot(String name, File dir, boolean active, int files, long bytes) {
            this.name = name;
            this.dir = dir;
            this.active = active;
            this.files = files;
            this.bytes = bytes;
        }
        // ⚠️ 这里原有两个方法 `title()`（`name + "  [当前]"`）与 `subtitle()`
        //    （`N 个文件 · 大小\n路径`），**已于 2026-10-02 删除 —— 它们是死代码**。
        //    F1/F2 之后界面分两路走完了：存档页把槽名、`[当前]` 徽标、统计**拆成三个控件**
        //    （`item_slot.xml` 的 slot_name / slot_badge / slot_meta，统计走
        //    `R.string.slot_entry_fmt`）；选槽对话框与设置页「默认槽」走
        //    `R.string.slot_current_suffix` + `R.string.slot_entry_fmt`。
        //    ⇒ 这两个方法**一个调用者都没有**（全工程的 `.title()` 都属于
        //      `Backup.Snapshot` 与 `Versions.Entry`）。
        //    ⚠️ 留着它们比"多几行"更糟：字符串里写字面空格是**能生效**的
        //      （不经 aapt2），一旦有人用它、而资源那条被 aapt2 剥了前导空格，
        //      同一个槽在两个界面就会长得不一样，且**没有任何症状**（REF §35.11）。
    }

    public static Slot slotInfo(Context ctx, String name) {
        boolean active = name.equals(currentSlot(ctx));
        File d = dirOf(ctx, name);
        return new Slot(name, d, active, countTree(d), sizeTree(d));
    }

    /** 全部槽：当前槽排第一，其余按名字排序 */
    public static List<Slot> allSlots(Context ctx) {
        List<Slot> out = new ArrayList<>();
        String cur = currentSlot(ctx);
        out.add(slotInfo(ctx, cur));
        for (String n : slotNames(ctx)) {
            if (n.equals(cur)) continue;
            out.add(slotInfo(ctx, n));
        }
        return out;
    }

    // ── 槽的增 / 改 / 删 ──────────────────────────────────────────────────

    /** 失败返回错误文本，成功返回 null（文本走资源 —— 见 `slot_err_*`） */
    public static String createSlot(Context ctx, String rawName) {
        String n = sanitizeSlot(rawName);
        if (n == null) {
            return Trans.get(ctx, R.string.slot_err_name_invalid);
        }
        File d = slotDir(ctx, n);
        if (d == null) return Trans.get(ctx, R.string.slot_err_no_external);
        // 当前槽不算"新建"：它的本体就是 slot-<当前名>，由启动时按需 mkdirs。
        // ⚠️ 这里**故意不 mkdirs** —— F6 的教训：只读探测造出的空目录会被下一轮当成"有内容"。
        if (n.equals(currentSlot(ctx))) return null;
        if (d.exists()) return Trans.get(ctx, R.string.slot_err_exists_fmt, n);
        if (!d.mkdirs()) return Trans.get(ctx, R.string.slot_err_mkdir_fmt, d.getAbsolutePath());
        Log.i(TAG, "slot created: " + d);
        return null;
    }

    /** 改名。当前槽在占用中，拒绝（先切走再改）。 */
    public static String renameSlot(Context ctx, String name, String rawNewName) {
        String n = sanitizeSlot(rawNewName);
        if (n == null) {
            return Trans.get(ctx, R.string.slot_err_name_invalid);
        }
        if (n.equals(name)) return null;
        if (name.equals(currentSlot(ctx))) {
            return Trans.get(ctx, R.string.slot_err_rename_current_fmt, name);
        }
        File from = slotDir(ctx, name);
        if (from == null || !from.exists()) return Trans.get(ctx, R.string.slot_err_missing_fmt, name);
        if (n.equals(currentSlot(ctx))) return Trans.get(ctx, R.string.slot_err_target_current_fmt, n);
        File to = slotDir(ctx, n);
        if (to.exists()) return Trans.get(ctx, R.string.slot_err_exists_fmt, n);
        if (!from.renameTo(to)) {
            return Trans.get(ctx, R.string.slot_err_rename_failed_fmt, from.getName(), to.getName());
        }
        Log.i(TAG, "slot renamed: " + name + " -> " + n);
        return null;
    }

    /**
     * **硬删**一个槽（真正的不可逆删除）。当前槽拒绝。
     *
     * 🔴 2026-10-05（第二批）改名 + 收窄：界面上的"删槽"现在走 {@link Trash#trashSlot}
     *   （整槽进中转站、能放回来），这里只剩**自检的清理**与"确实要永久删"的调用点。
     *   名字带 `Forever` 是刻意的 —— 两个入口长得一样才是这类事故的温床（谁都不想哪天
     *   为了图省事在 UI 里调了这一个）。要新增调用点前先问一句：**这个槽真的不该能恢复吗？**
     *
     * ⚠️ 调用方必须先让用户确认，并把受影响文件数/体积摊开。
     */
    public static String deleteSlotForever(Context ctx, String name) {
        if (name.equals(currentSlot(ctx))) {
            return Trans.get(ctx, R.string.slot_err_delete_current_fmt, name);
        }
        File d = slotDir(ctx, name);
        if (d == null || !d.exists()) return Trans.get(ctx, R.string.slot_err_missing_fmt, name);
        int files = countTree(d);
        if (!deleteTree(d)) {
            return Trans.get(ctx, R.string.slot_err_delete_partial_fmt,
                    d.getAbsolutePath(), countTree(d), files);
        }
        Log.i(TAG, "slot deleted forever: " + name + " (" + files + " files)");
        return null;
    }

    /** 槽里的 saves/ 目录（.msav 落点），不存在则创建 */
    public static File savesDirOf(Context ctx, String slot) {
        File d = new File(dirOf(ctx, slot), "saves");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ── 槽内容清单（"全部文件"口径：备份 / 导出共用） ──────────────────────

    /**
     * 槽里要打包的顶级条目 = 数据根下**全部**内容 − {@link #SLOT_EXCLUDE} − 空壳
     * − {@link #contentSkipped}。
     *
     * ★★ 这是「槽内容」这个概念的**唯一实现** —— {@link Backup#create} 与
     *    {@link Exporter#zipTo} 都调它。F19 之前备份自己另写了一份**白名单**遍历，
     *    结果是"模组放在数据根顶层的配置不进备份"（见 {@link #SLOT_EXCLUDE} 注释）。
     *
     * ★ 三条纪律：
     *  1. **只读**：绝不用 {@link #savesDirOf} 那种会 `mkdirs` 的存取器（F6 踩过 ——
     *     "看一眼有没有内容"会把空目录造出来，下一轮就把它当成"有内容"）。
     *     这里只 `listFiles()`，不落任何盘。
     *  2. **空壳不算内容**：空目录没有迁移价值；而空的 `saves/` 往往是只读探测的副产物
     *     ⇒ 把它算进来会让"这个槽什么都没有"的判断失效。
     *     ⚠️ 顺带说明为什么**备份**也能接受"空目录不算内容"：清单里只有文件（没有目录条目），
     *        空目录本来就无法被表示 —— 恢复后的空目录由写文件时的 `mkdirs` 顺带补出来。
     *  3. **排序稳定**（目录在前、名字升序）：打包顺序可复现，出问题时好比对。
     */
    public static List<File> contentRoots(Context ctx, String slot) {
        return contentRootsOf(dirOf(ctx, slot));
    }

    /**
     * {@link #contentRoots} 的**目录版**（唯一实现住在它这里）。
     * ★ 之所以要多一个入口：F0 的一次性迁移要拿它去问**旧 `files/`** 一句
     *   "你到底像不像一个有内容的槽"（见 {@link #reclaimLegacy}）——
     *   判据必须与"备份该打什么"完全同源，否则会出现"迁移说没内容、备份说有一堆"。
     * ⚠️ 同样**只读**，不 mkdirs、不落盘。
     */
    public static List<File> contentRootsOf(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        for (File f : kids) {
            String n = f.getName();
            if (slotExcluded(n) || contentSkipped(f)) continue;
            if (f.isDirectory()) {
                File[] c = f.listFiles();
                if (c == null || c.length == 0) continue;
            } else if (f.length() == 0) {
                continue;
            }
            out.add(f);
        }
        Collections.sort(out, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                boolean da = a.isDirectory(), db = b.isDirectory();
                if (da != db) return da ? -1 : 1;      // 目录在前（zip 观感更整齐）
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    // ── 游戏进程存活（备份/恢复/体检的前置条件） ────────────────────────────

    /** :game 进程是否在跑 */
    public static boolean gameAlive(Context ctx) {
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            String want = ctx.getPackageName() + ":game";
            for (ActivityManager.RunningAppProcessInfo p : ps) {
                if (want.equals(p.processName)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 结束 :game 进程（游戏正常退出会自己 System.exit(0)，这个只处理异常残留） */
    public static void killGame(Context ctx) {
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(ctx.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "killGame failed: " + t);
        }
    }

    // ── 数据根体检（M3） ──────────────────────────────────────────────────

    /** 体检发现的一项 */
    public static final class Finding {
        public final String where;     // "数据根" / "槽 xxx"
        public final File path;
        public final boolean redundant;
        public final int files;
        public final long bytes;
        public final String evidence;

        Finding(String where, File path, boolean redundant, int files, long bytes,
                String evidence) {
            this.where = where;
            this.path = path;
            this.redundant = redundant;
            this.files = files;
            this.bytes = bytes;
            this.evidence = evidence;
        }
    }

    public static final class Health {
        public final List<Finding> redundant = new ArrayList<>();
        public final List<Finding> suspicious = new ArrayList<>();

        /** 按 Finding 自己的判定分派到对应列表 */
        void add(Finding f) {
            if (f.redundant) redundant.add(f);
            else suspicious.add(f);
        }

        public long redundantBytes() {
            long b = 0;
            for (Finding f : redundant) b += f.bytes;
            return b;
        }

        public boolean nothingFound() {
            return redundant.isEmpty() && suspicious.isEmpty();
        }
    }

    /**
     * 扫描**数据根与所有槽**，找 HUB 数据的历史残留副本。
     *
     * ★ 判定纪律：**只认正证据**，光名字像不算。三项各自的证据：
     *   · config.json —— 能解析成 JSON 且含 HUB 的配置键（config_version/imports/…），
     *     且 app_hub/config.json 存在；游戏自己不用 config.json 这个名字。
     *   · import/     —— 里面有 .apk，且 app_hub/import/ 是目录（同名文件存在更好）。
     *   · natives/    —— 里面有 libarc*.so（递归，因为实际布局是 natives/&lt;abi&gt;/libarc.so），
     *     且 app_hub/natives/ 存在。
     * 证据不足的进 {@link Health#suspicious}，**只报告不清理** —— 宁可留着。
     */
    public static Health healthScan(Context ctx) {
        Health h = new Health();
        File priv = Paths.privateDir(ctx);

        List<File> roots = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String cur = currentSlot(ctx);
        String legacy = legacyOwner(ctx);
        for (String n : slotNames(ctx)) {
            File d = slotDir(ctx, n);
            if (d == null || !d.exists()) continue;
            roots.add(d);
            labels.add("槽 " + n);
        }
        // ★ F0：旧 `files/` 必须**单独**扫 —— 改造前游戏把 HUB 的 config.json / import /
        //   natives 复制进的就是它；而一次性迁移只搬走"当前槽本体"，那些残留**原地不动**，
        //   不在任何 `slot-*` 里 ⇒ 不扫它就永远发现不了（体检会假报"干净"）。
        //   ⚠️ 它此刻也可能是"当前槽的本体"（远古包模式，或迁移失败回落）—— 标签要说实话。
        File ext = Paths.externalRoot(ctx);
        if (ext != null && ext.isDirectory()) {
            roots.add(ext);
            labels.add(cur.equals(legacy) ? ("槽 " + cur + "（本体在 files/）") : "旧 files/");
        }

        for (int i = 0; i < roots.size(); i++) {
            File r = roots.get(i);
            String where = labels.get(i);

            // ① config.json
            File cf = new File(r, "config.json");
            if (cf.isFile()) {
                boolean hub = looksLikeHubConfig(cf);
                boolean aref = new File(priv, "config.json").isFile();
                h.add(new Finding(where, cf, hub && aref, 1, cf.length(),
                        hub ? (aref ? "内容含 HUB 配置键，且 app_hub/config.json 在 —— 冗余副本"
                                    : "内容像 HUB 配置，但 app_hub/config.json 不在，不敢判定")
                            : "同名文件，内容不是 HUB 配置 —— 不动"));
            }

            // ② import/
            File im = new File(r, "import");
            if (im.isDirectory()) {
                int apks = 0;
                boolean nameHit = false;
                File privImport = new File(priv, "import");
                File[] fs = im.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        if (!f.isFile()) continue;
                        if (!f.getName().toLowerCase(Locale.US).endsWith(".apk")) continue;
                        apks++;
                        if (new File(privImport, f.getName()).exists()) nameHit = true;
                    }
                }
                boolean ok = apks > 0 && privImport.isDirectory();
                h.add(new Finding(where, im, ok, countTree(im), sizeTree(im),
                        apks == 0 ? "目录里没有 .apk —— 不动"
                                : (ok ? ("含 " + apks + " 个 .apk" + (nameHit ? "（与 app_hub/import 同名命中）" : "")
                                        + "，HUB 导入目录在 —— 冗余副本")
                                       : "含 " + apks + " 个 .apk，但 app_hub/import 不在，不敢判定")));
            }

            // ③ natives/
            File nv = new File(r, "natives");
            if (nv.isDirectory()) {
                boolean hasSo = containsNative(nv);
                boolean aref = new File(priv, "natives").isDirectory();
                h.add(new Finding(where, nv, hasSo && aref, countTree(nv), sizeTree(nv),
                        hasSo ? (aref ? "含 libarc*.so，HUB native 目录在 —— 冗余副本"
                                      : "含 .so 但 app_hub/natives 不在，不敢判定")
                              : "目录里没有 libarc*.so —— 不动"));
            }
        }
        return h;
    }

    /**
     * ★★ 自动清理的结果（第 57 轮。需求原文：「这个数据根体验能不能全自动处理，看着会让用户很莫名其妙」）。
     *
     * 为什么**可以不问用户**就清：{@link Health#redundant} 里每一项都是**有正证据**的启动器残留
     * （能解析出 HUB 配置键的 `config.json` / 装着 `.apk` 的 `import/` / 装着 `libarc*.so` 的
     * `natives/` —— 判定纪律见 {@link #healthScan}）；证据不足的一律进 {@link Health#suspicious}
     * 且**永不自动删**。
     * ⇒ 让用户对"他根本不认识的东西"做决定，才是这里真正的风险来源。
     */
    public static final class AutoClean {
        public int cleaned;
        public long freed;
        public int suspicious;
        /** 游戏在跑 ⇒ 这次什么都没做（下次再说，不是错误） */
        public boolean skippedGameRunning;

    }

    /**
     * 自动清一遍数据根残留。**只删 redundant**；`suspicious` 一个都不碰；游戏在跑就什么都不做。
     * ★ 与手动清理共用 {@link #deleteRedundant} 这一处删除实现（口径只许一份）。
     */
    public static AutoClean autoCleanRedundant(Context ctx) {
        AutoClean a = new AutoClean();
        if (gameAlive(ctx)) {                      // 与手动清理同一条门禁
            a.skippedGameRunning = true;
            return a;
        }
        Health h = healthScan(ctx);
        a.suspicious = h.suspicious.size();
        if (h.redundant.isEmpty()) return a;
        long[] r = deleteRedundant(h, null);
        a.cleaned = (int) r[0];
        a.freed = r[2];
        Log.i(TAG, "auto health clean: cleaned=" + a.cleaned + " freed=" + a.freed
                + " suspicious=" + a.suspicious);
        return a;
    }

    /**
     * 真正的删除动作（**唯一实现**）：只遍历 `redundant`，返回 `{成功数, 失败数, 释放字节}`。
     *
     * @param detail 传了就把"逐项删了什么"写进去（手动清理要给人看）；自动清理传 null
     */
    private static long[] deleteRedundant(Health h, StringBuilder detail) {
        int ok = 0, fail = 0;
        long freed = 0;
        for (Finding f : h.redundant) {
            if (!f.path.exists()) continue;
            if (deleteTree(f.path)) {
                ok++;
                freed += f.bytes;
                if (detail != null) {
                    detail.append("已删 ").append(f.where).append(" / ").append(f.path.getName())
                            .append("（").append(Util.formatSize(f.bytes)).append("）\n");
                }
            } else {
                fail++;
                if (detail != null) {
                    detail.append("⚠ 删不掉：").append(f.path.getAbsolutePath()).append('\n');
                }
            }
        }
        return new long[]{ok, fail, freed};
    }

    /**
     * 清理体检判定的冗余副本（**只删 redundant 列表里的**）。
     * 返回人读报告。★ 界面上的手动入口现在只用来"看详情"——清理本身已经自动做了。
     */
    public static String cleanRedundant(Context ctx, Health h) {
        if (gameAlive(ctx)) {
            return "游戏进程（:game）还在运行 —— 请先退出游戏再清理。\n未删除任何文件。";
        }
        StringBuilder sb = new StringBuilder();
        long[] r = deleteRedundant(h, sb);
        int ok = (int) r[0], fail = (int) r[1];
        long freed = r[2];
        sb.append('\n').append(ok).append(" 项已清理，释放约 ").append(Util.formatSize(freed));
        if (fail > 0) sb.append("；").append(fail).append(" 项失败");
        Log.i(TAG, "health clean: ok=" + ok + " fail=" + fail + " freed=" + freed);
        return sb.toString();
    }

    private static boolean looksLikeHubConfig(File f) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(Util.readText(f));
            return o.has("config_version") || o.has("imports")
                    || o.has("known_packages") || o.has("last_version");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsNative(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (containsNative(f)) return true;
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.startsWith("libarc") && n.endsWith(".so")) return true;
            }
        }
        return false;
    }

    // ── 启动前的槽准备（执行者：:game 进程，游戏起来之前）───────────────────

    /**
     * **把当前槽准备成"游戏立刻就能读到的那个目录"** —— F0 的唯一入口。
     * 由 {@link GameSlot} 在拉起游戏**之前**调用（槽是那一刻才确定的）。
     *
     * 两种模式（`legacy` 由调用方按"该包 dex 里有没有 `mindustry.data.dir`"给出）：
     *
     *   · `legacy == false`（`build ≥ 147`，绝大多数）：**什么都不搬** ——
     *     只写槽记录，随后调用方 `System.setProperty(PROP_DATA_DIR, slot-<名>)`。
     *     耗时 ~0、无中间态、可多版本并行（这就是 F0 的全部收益）。
     *
     *   · `legacy == true`（`build ≤ 146`，远古包）：该版本只认游戏的默认根 `files/`，
     *     所以把目标槽的本体**改名**到 `files/`，并留下 {@link #legacyOwner} 标记
     *     （让 {@link #dirOf} 把它翻译回 `files/`，HUB 侧一切照旧）。
     *     ⚠️ 这条路上**同时只能有一个槽在线**，与改造前的行为一致 —— 这是被迫的。
     *
     * 安全纪律（照抄并强化旧 `swapTo` 的）：**任何一步失败都不做删除**，
     * 宁可退回到"本体住 `files/`"这个**改造前就存在**的布局（数据一个字节不动），
     * 也绝不让用户的存档**看起来**消失。
     */
    public static String prepareSlot(Context ctx, String target, boolean legacy) {
        StringBuilder sb = new StringBuilder();
        if (slotParent(ctx) == null) {
            return "拿不到外部目录（可能没挂载）⇒ 不做任何改动\n";
        }
        String cur = currentSlot(ctx);
        boolean blank = (target == null || target.isEmpty());
        sb.append("槽准备：当前=").append(cur).append("  目标=")
          .append(blank ? "(空)" : target)
          .append("  模式=").append(legacy ? "远古包 ⇒ 改名交换" : "属性注入（真隔离）")
          .append('\n');
        if (blank) target = cur;

        // ① 先回到统一布局：把"借住 files/ 的本体"收回 slot-<名>。
        //    任何模式都要先做 —— 否则下面的路径判断全都建立在错的起点上。
        //    幂等；已是统一布局时返回 null。
        String re = reclaimLegacy(ctx);
        if (re != null) sb.append("① ").append(re);

        if (SLOT_SHARED.equals(target)) {
            rememberSlot(ctx, SLOT_SHARED);
            sb.append("shared 模式：不隔离，游戏写它自己的默认根 files/\n");
            return sb.toString();
        }
        String safe = sanitizeSlot(target);
        if (safe == null) {
            sb.append("目标槽名不合法 ⇒ 不改动（仍用 ").append(currentSlot(ctx)).append("）\n");
            return sb.toString();
        }

        if (!legacy) {
            // ② 真隔离：只需要认下槽名。目录由调用方 mkdirs 后写进系统属性。
            rememberSlot(ctx, safe);
            sb.append("② 槽记为 ").append(safe).append("；游戏数据根 = ")
              .append(slotDir(ctx, safe)).append("（不搬动任何文件）\n");
            return sb.toString();
        }

        // ②' 远古包：本体必须躺进 files/（该版本只会看游戏的默认根）
        return legacyLendToFiles(ctx, sb, safe);
    }

    /**
     * 把槽 `safe` 的本体改名到 `files/`（远古包专用）。
     * ⚠️ 前置：{@link #reclaimLegacy} 刚跑过 ⇒ `files/` 应当是**空壳**。
     *   万一不是（历史残留），先把整个空壳**整体归档**到 `<hub>/files-shell/`，
     *   而不是逐个删 —— 见 {@link #stashShell}。
     */
    private static String legacyLendToFiles(Context ctx, StringBuilder sb, String safe) {
        File ext = Paths.externalRoot(ctx);
        File tgt = slotDir(ctx, safe);
        if (ext == null || tgt == null) {
            sb.append("拿不到外部根 / 槽目录 ⇒ 不改动\n");
            return sb.toString();
        }
        long t0 = System.currentTimeMillis();
        boolean had = tgt.exists();

        // ★ files/ 必须为空才能被 rename 覆盖；且有内容时**绝不**把它当成"可以牺牲"的东西。
        List<File> extContent = contentRootsOf(ext);
        if (!extContent.isEmpty()) {
            // 这不该发生（reclaim 刚跑过）。发生了说明"有个更老的布局"，
            // ⇒ 最安全的是**什么都不动**，并把本体声明为 files/（它确实在那儿）。
            rememberSlot(ctx, safe);
            writeLegacyOwner(ctx, safe);
            sb.append("⚠ files/ 里有 ").append(extContent.size())
              .append(" 项数据 ⇒ 不搬动；本槽本体就地取用 files/\n");
            return sb.toString();
        }
        if (ext.exists()) {
            File[] kids = ext.listFiles();
            if (kids != null && kids.length > 0) {
                if (!stashShell(ctx, ext, "files-shell")) {
                    // 清不掉空壳 ⇒ 只能放弃隔离。**不改任何东西**：游戏仍写 files/，
                    // 本槽本体留在 slot-<安全名>（数据完好，只是这局看不到它）—— 报告里说清。
                    sb.append("⚠ 清不掉 files/ 里的历史残留 ⇒ 本次不做隔离。\n")
                      .append("  本槽数据完好地留在 ").append(tgt.getName())
                      .append("（该远古版本这局将看不到它）\n");
                    rememberSlot(ctx, safe);
                    return sb.toString();
                }
                sb.append("  （files/ 的非内容残留已归档到 hub/files-shell/）\n");
            }
            // 现在应为空；删掉这个空目录好让下面的 rename 能落地
            if (!deleteTree(ext)) {
                sb.append("⚠ files/ 删不掉 ⇒ 本次不做隔离（同上）\n");
                rememberSlot(ctx, safe);
                return sb.toString();
            }
        }
        if (had && !tgt.renameTo(ext)) {
            // 极端情况：rename 失败（被占用？）。回到"不改任何东西"，
            // 但**必须**把标记写成 safe —— 否则 dirOf 会指向 slot-<safe>，
            // 而游戏写的是 files/，用户的存档就"看起来"没了。
            rememberSlot(ctx, safe);
            writeLegacyOwner(ctx, safe);
            sb.append("⚠ rename ").append(tgt.getName())
              .append(" → files/ 失败 ⇒ 未做任何删除；标记本体就在 files/\n");
            return sb.toString();
        }
        rememberSlot(ctx, safe);
        writeLegacyOwner(ctx, safe);
        sb.append("② 远古包模式：本体 = files/（")
          .append(had ? "由 " + tgt.getName() + " 改名而来" : "该槽首次使用，空目录")
          .append("）；耗时 ").append(System.currentTimeMillis() - t0).append(" ms\n");
        return sb.toString();
    }

    /**
     * 把**空壳**目录整体挪到 `<hub>/<label>/`（最多留一份，同名旧档先删）。
     * 只应该在 {@link #contentRootsOf} 确认"这里没有任何槽内容"之后调用 ——
     * 里面那几项（`files_moved*` / `.nomedia` / 空的 `saves/`）都是可再生的标记或
     * **只读探测的副产物**（F6 踩过：看一眼有没有内容就把空目录造出来了），
     * 而不是用户数据。
     *
     * ★ 为什么是「挪」不是「删」：这条路是**兜底里的兜底**，一旦判据有偏差，
     *   归档还能人工取回，`deleteTree` 就没了 —— 而这个工程的核心承诺是"不丢数据"。
     * 返回是否成功（失败则调用方放弃本次隔离，绝不硬删）。
     *
     * @param label 归档目录名（`hub/<label>`）。`files/` 用 `files-shell`，
     *              槽侧空壳用 `slot-shell-<槽名>` —— 分开留档，便于事后判断是谁的残留。
     */
    private static boolean stashShell(Context ctx, File dir, String label) {
        File arch = new File(hubDir(ctx), label);
        if (arch.exists() && !deleteTree(arch)) return false;
        File p = arch.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) return false;
        return dir.renameTo(arch);
    }

    /**
     * **"本体此刻住在 `files/` 的那个槽"** —— {@link #reclaimLegacy} 的**唯一**判定，
     * 抽成纯函数（无 IO、无副作用），这样自检才能单独证伪它（见 `SelfTest#dataDirModel`）。
     *
     * 返回 `null` = "没什么可收回的"（已是统一布局）⇒ 调用方**绝不搬动 `files/`**。
     *
     * 三条规则：
     *   · 无标记 + `files/` 无内容 ⇒ null（统一布局）；
     *   · 有标记 ⇒ 标记里的槽名说了算（远古包刚跑过，即使 `files/` 恰好空）；
     *   · 无标记但 `files/` 有内容 ⇒ 归**当前槽**（改造前的老布局）。
     * ⚠️ `shared` 一律 null：那个模式下 `files/` **本来就是**数据根
     *   （`dirOf("shared")` 直指它），按"某槽借住"搬走就是破坏。
     */
    static String legacyClaim(String marker, boolean extHasContent, String cur) {
        if (marker == null && !extHasContent) return null;
        String owner = (marker != null) ? marker : cur;
        if (SLOT_SHARED.equals(owner)) return null;
        return owner;
    }

    /**
     * **把"本体借住 `files/`"的布局收回成统一布局** —— `files/` 里如果有内容，
     * 整体改名回 `slot-<该槽名>`。幂等；已是统一布局时返回 `null`。
     *
     * 这里同一条代码覆盖了**两种来源**（它们其实是同一件事）：
     *   ① **改造前的老布局**（`files/` 就是当前槽、没有标记）⇒ 槽名取 {@link #currentSlot}；
     *   ② **远古包刚跑过**（有标记）⇒ 槽名取标记里的那个。
     * ⇒ 一次性迁移（升级后第一次启动）与"远古包模式退出"共用一份实现，不会走样。
     *
     * ★ 失败时**一定**写标记：那样 {@link #dirOf} 继续指向 `files/`，
     *   用户看到的就是改造前的行为（功能正常、只是没隔离），**数据一个字节不动**。
     *
     * ⚠️ 这里**不做** `gameAlive` 判断 —— 本方法只负责"该不该搬、怎么搬"，
     *   "此刻能不能搬"由**调用方**判断。两个调用点各自的理由：
     *     · {@link GameSlot} 启动前：`:game` Activity 一开跑，上一个 `:game` 必然已死
     *       （`singleTask` + 拉起前 force-stop，见其类注释）；
     *     · {@link LauncherApp#onCreate} 主进程：**必须**自己判 `!gameAlive`，
     *       因为主进程可能在 `:game` 还活着时被系统重新拉起。
     */
    public static String reclaimLegacy(Context ctx) {
        File ext = Paths.externalRoot(ctx);
        if (ext == null) return null;
        String marker = legacyOwner(ctx);
        boolean extHasContent = !contentRootsOf(ext).isEmpty();

        // ★ 决策交给纯函数（同时也是自检的被试，见 SelfTest#dataDirModel）——
        //   执行（改名）与判定分开，判定才能在自检里被单独证伪。
        String owner = legacyClaim(marker, extHasContent, currentSlot(ctx));
        if (owner == null) return null;   // 已是统一布局（或 shared：files/ 本就是它的根）

        File slot = slotDir(ctx, owner);
        if (slot == null) return "拿不到槽目录 ⇒ 保持 files/ 不动\n";

        if (!extHasContent) {
            // files/ 里没有数据 ⇒ 本体要么已经在槽里（标记过期），要么两边都空。
            if (slot.exists()) {
                writeLegacyOwner(ctx, null);
                return "标记已过期（本体就在 " + slot.getName() + "）⇒ 清标记\n";
            }
            if (ext.exists() && !ext.renameTo(slot)) {
                writeLegacyOwner(ctx, owner);
                return "⚠ 空的 files/ 收不回 " + slot.getName() + " ⇒ 保持现状\n";
            }
            writeLegacyOwner(ctx, null);
            if (!ext.exists()) ext.mkdirs();
            return "空的槽目录已归位 → " + slot.getName() + "（files/ 留空）\n";
        }

        // files/ 里有真数据 ⇒ 它就是本体（老布局 或 远古包刚写过的）
        //
        // 🔴 判据是"槽那边**有没有内容**"，不是"目录在不在" —— F6 同一类坑：
        //   `slot-<当前槽>` 常常只是个**空壳**（只读探测走 `savesDirOf` 的 `mkdirs`
        //   造出来的，或老布局留下的空目录）。若按 `exists()` 判成"两边都有东西"，
        //   就会永久拒绝迁移 ⇒ **F0 的隔离对当前槽永远不生效**，而现象只是
        //   "看起来跟改造前一模一样"（不会有任何报错）。
        if (!contentRootsOf(slot).isEmpty()) {
            // 两边都**真有内容**：不合并、不删除，把本体声明为 files/（它确实在那儿）
            writeLegacyOwner(ctx, owner);
            return "⚠ " + slot.getName() + " 和 files/ 里都有内容 ⇒ 不敢自动合并，继续用 files/\n";
        }
        if (slot.exists() && !stashShell(ctx, slot, "slot-shell-" + owner)) {
            // 槽侧空壳挪不动（还好只是空壳）⇒ 放弃本次迁移，绝不硬删
            writeLegacyOwner(ctx, owner);
            return "⚠ 槽侧空壳 " + slot.getName() + " 挪不动 ⇒ 保持现状（本体继续用 files/）\n";
        }
        if (!ext.renameTo(slot)) {
            writeLegacyOwner(ctx, owner);
            return "⚠ files/ → " + slot.getName() + " 改名失败 ⇒ 保持现状（本体继续用 files/）\n";
        }
        writeLegacyOwner(ctx, null);
        // ★ files/ 必须**存在且为空**：游戏会往这里写自己的标记，而"目录不存在"
        //   会让它认为从没搬过、重新把内部 files/ 复制一遍（见 Paths 头注释）。
        if (!ext.exists()) ext.mkdirs();
        return "已收回 → " + slot.getName() + "（files/ 留空）\n";
    }

    // ── 树统计 / 删除 ─────────────────────────────────────────────────────

    static int countTree(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) n += f.isDirectory() ? countTree(f) : 1;
        return n;
    }

    static long sizeTree(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long n = 0;
        for (File f : fs) n += f.isDirectory() ? sizeTree(f) : f.length();
        return n;
    }

    static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteTree(c);
            }
        }
        return f.delete();
    }
}
