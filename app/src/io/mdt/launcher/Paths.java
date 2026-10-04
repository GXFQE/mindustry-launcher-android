package io.mdt.launcher;

import android.content.Context;
import java.io.File;

/**
 * 路径唯一来源（模块边界：最底层，只依赖 android.content.Context / Util）。
 *
 * ★★ **F0：三个曾经被混用的角色，现在彻底拆开**（2026-10-02）───────────────────
 *
 *   ① {@link #externalRoot} = `getExternalFilesDir(null)` = `…/Android/data/<pkg>/files`
 *      —— 游戏的**默认**数据根。F0 之后它只剩两个用途：**槽的父目录的父目录**，
 *      以及一个**空壳**（游戏自己会在这里重建 `files/`、留 `files_moved*`）。
 *   ② {@link #slotParent} = ① 的父目录 = `…/Android/data/<pkg>/`
 *      —— **所有**槽目录（含当前槽）都住在这里，名字 `slot-<槽名>`。
 *   ③ {@link #slotDir} = `slotParent/slot-<槽名>` —— 槽的**本体**。
 *
 *   F0 之前：② 里的"当前槽"其实是 ① **本身**（靠启动前改名交换把本体换进换出），
 *   所以"数据根"既不是 ① 也不是 ③，而是"① 或 ③ 视时刻而定" —— 那正是
 *   「同时只能一个槽在线、中途崩溃留半交换」的根源。
 *   F0 之后：当前槽**恒定**住在 ③，游戏靠 `System.setProperty("mindustry.data.dir")`
 *   被指到 ③ ⇒ 不动任何文件、可多版本并行。
 *   ⚠️ 唯一的例外仍要认得：远古包（`build ≤ 146`，dex 里没有该属性）只能写 ①，
 *      ⇒ 那一刻本体被**临时改名**到 ①，由 `Data` 的 `legacyOwner` 标记记录
 *      （`Data.dirOf` 会把它翻译回 `files/`）。
 *
 * ★★ 为什么 HUB 私有数据用 `app_hub` 而**不是** `getFilesDir()`（`files/`）：
 *
 *   Mindustry 的 AndroidLauncher.onCreate 里有这段（源码已核实，v159.7）：
 *
 *       Fi data = Core.files.absolute(getExternalFilesDir(null).getAbsolutePath());
 *       Core.settings.setDataDirectory(data);
 *       if(!Core.files.local("files_moved").exists()){
 *           Fi src = Core.files.absolute(Core.files.getLocalStoragePath());  // = getFilesDir()
 *           for(Fi fi : src.list()) fi.copyTo(data);            // 逐个 copy，不删源
 *           Core.files.local("files_moved").writeString("files moved to " + data);
 *           Core.files.local("files_moved_103").writeString("files moved again");
 *       }
 *
 *   ⇒ 游戏**首次启动**会把 `files/` 下的每个条目复制到它的数据根
 *     （= getExternalFilesDir(null) = 我们要做槽交换的那个目录）。
 *   后果有三条，都是实打实的：
 *     ① HUB 的数据被复制进**存档槽**，污染游戏数据；
 *     ② 占双倍空间（导入的整包 APK 有 70+ MB，复制一份很疼）；
 *     ③ 标记写在 `files/` 里，所以只搬一次 —— 但一旦标记被清（清数据/换槽），会再搬一次。
 *
 *   实测证据：把 config.json / import/ / natives/ 放 files/ 后启动游戏，
 *   它们确实出现在了数据根里（`files/config.json`、`files/import/`、`files/natives/`），
 *   而 `files_moved` 的内容是 "files moved to /storage/emulated/0/Android/data/<pkg>/files"。
 *
 *   `getDir("hub")` → `/data/user/0/<pkg>/app_hub`：**仍在内部存储**
 *   （所以 dex 照样能加载 —— 这是"导入的 APK 必须放内部"的硬约束），
 *   但**不是 `files`**，不在游戏 copy 的范围内。
 *
 * ⚠️ 另一条相关事实：游戏还会在每次启动时**删掉数据根下的 `cache/`**
 *   （`Core.settings.getDataDirectory().child("cache").deleteDirectory()`）——
 *   这正是探针实测到"官方 159.7 跑完把 MindustryX 的 cache/ 整个删掉"的原因。
 */
final class Paths {
    private Paths() {}

    // ── 槽的命名（★ 唯一来源；Data 的那几个常量是别名，别再各写一份字面量）──────

    /** 槽目录名前缀（住在 {@link #slotParent} 下） */
    static final String SLOT_PREFIX = "slot-";

    /** 不隔离：所有版本共用一个数据根（= {@link #externalRoot}）。保留值，不能当普通槽名。 */
    static final String SLOT_SHARED = "shared";

    /** 初始槽名 */
    static final String SLOT_DEFAULT = "default";

    /**
     * HUB 私有数据目录（内部存储，**不是** files/）。
     * 放：config.json / import（导入的 APK + oat）/ natives（解出的 .so）/ 槽记录。
     */
    static File privateDir(Context ctx) {
        File d = ctx.getDir("hub", Context.MODE_PRIVATE);   // /data/user/0/<pkg>/app_hub
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 旧的（错误的）私有目录 —— 仅用于一次性迁移，迁移完只留空壳 */
    static File legacyPrivateDir(Context ctx) {
        return ctx.getFilesDir();
    }

    /**
     * HUB 的外部目录 —— 与游戏数据根**平级**，游戏不认识它。
     * 放：启动报告、备份、future 的增量包。
     */
    static File externalHub(Context ctx) {
        File root = ctx.getExternalFilesDir(null);
        File parent = root == null ? null : root.getParentFile();
        if (parent == null) {
            File d = new File(privateDir(ctx), "external");
            if (!d.exists()) d.mkdirs();
            return d;
        }
        File h = new File(parent, "hub");
        if (!h.exists()) h.mkdirs();
        return h;
    }

    /**
     * **外部根** —— 游戏默认的数据根（`getExternalFilesDir(null)`）。
     * F0 之后它不再承载任何槽（除非当前槽正被远古包临时占用，见类注释）。
     * ⚠️ 它**同时**是槽目录的父目录的父目录 —— 别再用它当"数据根"或"槽交换区"。
     */
    static File externalRoot(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    /** **槽目录的父目录**（= {@link #externalRoot} 的父目录）。所有 `slot-*` 都住这里。 */
    static File slotParent(Context ctx) {
        File root = externalRoot(ctx);
        return root == null ? null : root.getParentFile();
    }

    /** 某个槽的**本体**目录（不存在 ≠ 不可用：由调用方决定要不要 mkdirs） */
    static File slotDir(Context ctx, String name) {
        File parent = slotParent(ctx);
        return parent == null ? null : new File(parent, SLOT_PREFIX + name);
    }

    /**
     * 槽记录文件（内部，游戏碰不到）—— **槽名的唯一来源**。
     * ⚠️ 不是 `config.json`：槽记录由 `:game` 进程写，而 `config.json` 由主进程维护，
     *   两个进程同时写会打架（见 {@link Data} 头注释）。
     */
    static File slotRecord(Context ctx) {
        return new File(privateDir(ctx), "mdt-slot.txt");
    }

    /**
     * **当前槽名的唯一读取实现**（`Data.currentSlot` 直接转调这里）。
     *
     * ★ 放在 `Paths` 而不是 `Data`：`dirOf()` 要按槽名算路径，若两边各读一次盘，
     *   就会出现"同一个槽名两个来源"（本项目已多次栽在这类重复实现上）。
     * ⚠️ 读不到 / 为空 ⇒ 退回 {@link #SLOT_DEFAULT}（与历史行为一致）。
     */
    static String readSlotName(Context ctx) {
        try {
            String s = Util.readText(slotRecord(ctx)).trim();
            if (!s.isEmpty()) return s;
        } catch (Throwable ignored) {
        }
        return SLOT_DEFAULT;
    }

    /**
     * **远古包临时占用标记**（内部）。内容 = 哪一个槽的本体此刻被改名躺在
     * {@link #externalRoot}（`files/`）里 —— 因为该版本（`build ≤ 146`）不认
     * `mindustry.data.dir`，只能写游戏的默认根。
     * 空 / 不存在 = 正常布局（`files/` 只是空壳）。
     */
    static File legacyRecord(Context ctx) {
        return new File(privateDir(ctx), "mdt-legacy-owner.txt");
    }

    /**
     * 一次性迁移：把历史上误放在 `files/` 里的 HUB 数据改名搬到 `privateDir`。
     * 幂等；只在目标缺失、源存在时搬。**rename 不复制**（同分区，瞬时，不占额外空间）。
     *
     * ⚠️ 搬走之后还会把 `files/` 里的 `files_moved` 标记**保留** ——
     *   那是游戏自己的标记，删了会导致游戏重新复制一遍（见头注释）。
     */
    static void migrateFromLegacy(Context ctx) {
        File legacy = legacyPrivateDir(ctx);
        File dest = privateDir(ctx);
        String[] names = {"config.json", "import", "natives", "mdt-slot.txt"};
        for (String n : names) {
            File from = new File(legacy, n);
            File to = new File(dest, n);
            if (!from.exists() || to.exists()) continue;
            if (!from.renameTo(to)) {
                android.util.Log.w("MDTLauncher", "legacy migrate failed: " + from + " -> " + to);
            } else {
                android.util.Log.i("MDTLauncher", "legacy migrate ok: " + n + " -> " + to);
            }
        }
    }
}
