package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「存储占用」的**数字收集 + 文案拼装**（一档③+⑦，2026-10-06）。
 *
 * <h3>它回答的两个问题</h3>
 * <ol>
 *   <li><b>备份到底省了多少</b> —— 内容去重的价值。在此之前这件事只有 {@code dev_cas_stats}
 *       那个测试口看得见（`Backup.poolStats` / `gcNow` / `migrateLegacy` 全库只有 dev 与自检在调），
 *       而它是这个工程最能说清"为什么要这么存"的一件事。</li>
 *   <li><b>各类数据各占多少</b> —— 各槽 / 备份池 / 四个中转站目录 / 可以清理的残留。
 *       ★ 中转站与备份池原来是**统计盲区**：`Trash.totalBytes` 的注释自己写着
 *       「中转站此前**不参与任何体检数字**」，而 `Data.healthScan` 查的是"数据根里的冗余副本"，
 *       与"谁占了多少空间"根本不是一回事。</li>
 * </ol>
 *
 * <h3>为什么收成一个类（而不是直接写在设置页里）</h3>
 * 分工与 {@link MsavText} 同款：
 * <pre>
 *   collect(ctx) —— 碰文件、必须在**后台线程**跑（遍历各槽 + 整个对象池 + 中转站）
 *   text(ctx, r) —— **纯拼装**：只读 r 里的数字，一个文件都不碰
 * </pre>
 * 于是自检能直接喂一份**合成 Report** 去断言"拼出来的句子"，也能验"该省的行必须省掉"
 * 这类反向判据 —— 塞进 Activity 就只能靠真机上肉眼看。
 *
 * <h3>★ 2026-10-12（第 140 轮）：补上"内部存储"那一半</h3>
 * 在此之前只量了**外部 hub**（{@link Paths#externalHub}：备份池 / 预览 / 报告 / 中转站），
 * 而**导入的游戏包住在内部 `app_hub`**（{@link Importer#importDir}）——
 * 真机实测：一份官方 159 的 APK 就 **74.7 MB**（`report-launch.txt` 里的目标路径），
 * 弹窗却只报「共占 18.4 MB」⇒ 用户看到的总数比系统设置里的小一个数量级。
 * 现在总分 = 外部 hub + 内部 `app_hub` + 两个缓存目录，并在「启动器自身」一节分项列出：
 * 导入的游戏包 / 解出的运行库 / 插件 / 缓存（游戏运行时的音乐与模组、系统生成的 dex 产物）。
 * ★ 真机实测这块加起来 **159 MB**（`app_hub` 85.6 MB + `cache` 73.7 MB），而老口径只报 18.4 MB。
 *
 * ⚠️ 别把导入的游戏包当成 CAS：**只有存档备份走对象池去重**，导入的 APK 是
 *   一份一份的私有副本（同名重导才替换）⇒ 它们必须**原样计入**，不能按"去重后"报。
 *
 * ⚠️ 只读：本类**不写任何东西**（不回收、不迁移、不清理）。删除类动作仍走各自的门禁。
 */
final class Storage {

    private Storage() {}

    /** 一次统计的全部数字（字段 public：自检要能手工造一份） */
    static final class Report {
        /** 启动器数据（**外部** hub）总占用 —— 递归量出来的真实数字 */
        public long hubBytes;
        /**
         * **内部** `app_hub` 总占用 —— 导入的游戏包 / 解出的 native / 插件 / 配置都住这里。
         * ★ 第 140 轮之前这个数字**根本不存在**，而它是真机上最大的一块。
         */
        public long internalBytes;
        /** 其中：导入的游戏包（`app_hub/import/*.apk`） */
        public long importBytes;
        public int importCount;
        /** 其中：从游戏包里解出来的 native（`app_hub/natives/<abi>/*.so`） */
        public long nativesBytes;
        /** 其中：插件（`app_hub/plugins/<id>/<ver>.apk`） */
        public long pluginsBytes;
        public int pluginCount;
        /**
         * 缓存（`getCacheDir()` + `getCodeCacheDir()`）—— 游戏运行时的音乐 / 模组 / 临时文件，
         * 加上系统为那些**动态加载**的包生成的 dex 产物。
         * ★ 真机实测：这份缓存有 **73.7 MB**（其中 45 MB 是游戏解出来的模组贴图），
         *   比导入的 APK 本身还显眼 ⇒ 漏掉它，"总分"照样对不上系统设置里的数字。
         */
        public long cacheBytes;
        /**
         * 上面各段**都没点到**的部分 —— 外部 hub 里的预览缩略图 / 设置备份 / 报告，
         * 加上内部 `app_hub` 里的零碎（配置 / 槽记录）。
         * 不做这一项的话，"总占用"与下面各节之和会对不上，用户只能自己猜差额去哪了。
         */
        public long otherBytes;
        /** 各槽（外部存储里的游戏数据） */
        public List<Data.Slot> slots = new ArrayList<>();
        public long slotBytes;
        /** 备份池（{@link Backup#poolStats} 原样带过来：口径只有一份实现） */
        public Backup.PoolStats pool;
        /** 中转站：按 {@link Trash.Kind} 的序号分组 */
        public int[] trashCounts = new int[Trash.Kind.values().length];
        public long[] trashBytes = new long[Trash.Kind.values().length];
        public int trashItems;
        public long trashTotalBytes;
        /** 数据根里"可以清理的残留"（{@link Data.Health}） */
        public int redundantPlaces;
        public long redundantBytes;

        /**
         * **真实占用** —— 第一行那句话用的就是它（唯一口径）。
         * ★ 别在别处再算一遍：四处的定义（外部 hub / 内部 app_hub / 缓存 / **各槽**）只会漂。
         *
         * <p>🔴 2026-10-15（第 150 轮）**把各槽并了进来**：原来只算前三项，而各槽住在
         * `<…/io.mdt.launcher>/slot-<名>`（与 hub **同级**）⇒ 真机上出现"总占用 429.0 MB"
         * 紧跟着"槽「default」：1.4 GB"这种**第一行比它下面的某一节还小**的自打脸，
         * 而页面标题又是"存储占用"（用户当然按"这软件占了我多少"去读）。
         * ⇒ 口径 = 这份页面上**列出来的东西之和**（下面各节加得起来）。
         * ⚠️ 改这一处要同时改 `SelfTest` 里那两条元断言（已同步）。
         */
        public long totalBytes() {
            return Math.max(0L, hubBytes) + Math.max(0L, internalBytes)
                    + Math.max(0L, cacheBytes) + Math.max(0L, slotBytes);
        }
    }

    /**
     * 收集数字。**只该在后台线程调**（hub 里可能有几万个对象文件，递归很花时间）。
     *
     * ★ 每一块都**复用既有的唯一实现**：各槽走 {@link Data#allSlots}（与存档页同一个口径）、
     *   备份池走 {@link Backup#poolStats}、中转站走 {@link Trash#listAll}、
     *   残留走 {@link Data#healthScan} —— 这里一个数都不自己算。
     */
    static Report collect(Context ctx) {
        Report r = new Report();
        List<Data.Slot> slots = Data.allSlots(ctx);
        if (slots != null) {
            for (Data.Slot s : slots) {
                if (s == null) continue;
                r.slots.add(s);
                r.slotBytes += s.bytes;
            }
        }
        r.pool = Backup.poolStats(ctx);
        List<Trash.Item> items = Trash.listAll(ctx);
        if (items != null) {
            for (Trash.Item it : items) {
                if (it == null || it.kind == null) continue;
                int i = it.kind.ordinal();
                if (i < 0 || i >= r.trashCounts.length) continue;
                r.trashCounts[i]++;
                r.trashBytes[i] += it.bytes;
                r.trashTotalBytes += it.bytes;
                r.trashItems++;
            }
        }
        Data.Health h = Data.healthScan(ctx);
        if (h != null) {
            r.redundantPlaces = h.redundant.size();
            r.redundantBytes = h.redundantBytes();
        }
        File hub = Data.hubDir(ctx);
        r.hubBytes = Data.sizeTree(hub);

        // ── 内部存储（★ 第 140 轮补上；导入的游戏包 = 真机上最大的一块）──────────
        File priv = Paths.privateDir(ctx);
        r.internalBytes = Data.sizeTree(priv);
        File imp = new File(priv, "import");
        r.importBytes = Data.sizeTree(imp);
        r.importCount = countApk(imp);
        r.nativesBytes = Data.sizeTree(new File(priv, "natives"));
        File pl = Plugins.dirNoCreate(ctx);      // ⚠️ 不创建：只读一眼不该多出目录
        r.pluginsBytes = Data.sizeTree(pl);
        r.pluginCount = countApk(pl);
        // 两个缓存目录一起算（游戏解出来的音乐 / 模组贴图 + 系统给动态加载的包生成的 dex 产物）
        r.cacheBytes = Data.sizeTree(ctx.getCacheDir()) + Data.sizeTree(ctx.getCodeCacheDir());

        // 外部 hub 里"备份 / 中转站"之外的那些（预览缩略图 / 设置备份 / 报告），
        // 以及内部 app_hub 里除上面三项之外的零碎（config.json / 槽记录…）。
        // ⚠️ 用减法算，所以要**夹到 0**：份额取自别处的口径（池 / 中转站），
        //    万一将来有个东西两边都算，负数会在界面上变成一句莫名其妙的"-3 MB"。
        long covered = (r.pool == null ? 0L : r.pool.actual + r.pool.legacyBytes) + r.trashTotalBytes
                + r.importBytes + r.nativesBytes + r.pluginsBytes;
        r.otherBytes = Math.max(0L, r.hubBytes + r.internalBytes - covered);
        return r;
    }

    /**
     * 数一数目录里有多少个 `*.apk`（递归 —— 插件是 `plugins/&lt;id&gt;/&lt;ver&gt;.apk` 两层）。
     * ★ 只数 `.apk`：同一个目录下还躺着 `.part` 残留与别的杂项，按"文件数"报会虚高。
     */
    private static int countApk(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isDirectory()) n += countApk(f);
            else if (f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")) n++;
        }
        return n;
    }

    /**
     * 组装给用户看的正文（**纯函数**：不碰文件、不改入参）。
     *
     * ★ 分三层说：总占用 → 分项（启动器自身 / 各槽 / 备份 / 中转站）→ 能清理的残留。
     * ★ 术语纪律：第一层不出现"对象池 / CAS / 去重"这些词，只说"备份之间共用内容"；
     *   依据（对象数、暂时没人用的部分）用白话跟在后面同一段里。
     */
    static String text(Context c, Report r) {
        StringBuilder sb = new StringBuilder();
        if (r == null) return "";
        sb.append(Trans.get(c, R.string.storage_total_fmt,
                Util.formatSize(r.totalBytes()))).append('\n');

        // ① 启动器自身 —— ★ 第 140 轮新开的一节：内部 app_hub 从来没有露过面，
        //   而"导入的游戏包"是真机上最大的一块（用户看到总数从 18 MB 变成 200 MB 才知道）。
        sb.append('\n').append(Trans.get(c, R.string.storage_sec_app)).append('\n');
        boolean appAny = false;
        if (r.importCount > 0 || r.importBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_import_fmt, r.importCount,
                    Util.formatSize(r.importBytes))).append('\n');
            appAny = true;
        }
        if (r.nativesBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_natives_fmt,
                    Util.formatSize(r.nativesBytes))).append('\n');
            appAny = true;
        }
        if (r.pluginCount > 0 || r.pluginsBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_plugins_fmt, r.pluginCount,
                    Util.formatSize(r.pluginsBytes))).append('\n');
            appAny = true;
        }
        if (r.cacheBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_cache_fmt,
                    Util.formatSize(r.cacheBytes))).append('\n');
            appAny = true;
        }
        if (r.otherBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_other_fmt,
                    Util.formatSize(r.otherBytes))).append('\n');
            appAny = true;
        }
        // 全为 0 才出现的兜底句（与"该省的行必须省掉"同一条纪律：空段不摆空行）
        if (!appAny) sb.append(Trans.get(c, R.string.storage_app_none)).append('\n');

        // ② 各槽内容
        sb.append('\n').append(Trans.get(c, R.string.storage_sec_slots)).append('\n');
        if (r.slots == null || r.slots.isEmpty()) {
            sb.append(Trans.get(c, R.string.storage_no_slots)).append('\n');
        } else {
            for (Data.Slot s : r.slots) {
                if (s == null) continue;
                sb.append(Trans.get(c, R.string.storage_slot_line_fmt, s.name, s.files,
                        Util.formatSize(s.bytes))).append('\n');
            }
        }

        // ③ 备份（去重效果就靠这一行说清）
        sb.append('\n').append(Trans.get(c, R.string.storage_sec_backup)).append('\n');
        Backup.PoolStats p = r.pool;
        int snaps = p == null ? 0 : p.snapshots;
        int legacy = p == null ? 0 : p.legacySnapshots;
        if (snaps <= 0 && legacy <= 0) {
            sb.append(Trans.get(c, R.string.storage_backup_none)).append('\n');
        } else if (p != null) {
            int pct = (int) Math.round(p.saved() * 100d);
            sb.append(Trans.get(c, R.string.storage_backup_fmt, snaps,
                    Util.formatSize(p.actual), Util.formatSize(p.logical),
                    Trans.get(c, R.string.storage_pct_fmt, pct))).append('\n');
            if (legacy > 0) {
                sb.append(Trans.get(c, R.string.storage_backup_legacy_fmt, legacy,
                        Util.formatSize(p.legacyBytes))).append('\n');
            }
        }
        // 孤儿（暂时没人用的对象）单独一句：它不属于任何一份备份，但确实占着地方
        if (p != null && p.orphanBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_backup_orphan_fmt,
                    Util.formatSize(p.orphanBytes))).append('\n');
        }

        // ④ 中转站（按类型分，为 0 的类型不出现 —— 空行比"没有"更省事）
        sb.append('\n').append(Trans.get(c, R.string.storage_sec_trash)).append('\n');
        if (r.trashItems <= 0) {
            sb.append(Trans.get(c, R.string.storage_trash_none)).append('\n');
        } else {
            Trash.Kind[] kinds = Trash.Kind.values();
            for (int i = 0; i < r.trashCounts.length && i < kinds.length; i++) {
                if (r.trashCounts[i] <= 0) continue;
                sb.append(Trans.get(c, R.string.storage_trash_line_fmt, trashKindLabel(c, kinds[i]),
                        r.trashCounts[i], Util.formatSize(r.trashBytes[i]))).append('\n');
            }
        }

        // ⑤ 可以清理的残留（体检数字，原来只在设置页那一行里体现"已开/已关"）
        sb.append('\n').append(Trans.get(c, R.string.storage_sec_clean)).append('\n');
        sb.append(r.redundantPlaces > 0
                ? Trans.get(c, R.string.storage_clean_fmt, r.redundantPlaces,
                        Util.formatSize(r.redundantBytes))
                : Trans.get(c, R.string.set_autoclean_none)).append('\n');
        return sb.toString();
    }

    /**
     * 中转站里那一类的名字。
     * ★ 与 {@link TrashActivity} 列表上用的**同一批资源**（`trash_kind_*`）——
     *   同一个东西在两个界面上必须一个叫法。
     */
    static String trashKindLabel(Context c, Trash.Kind k) {
        if (k == null) return Trans.get(c, R.string.trash_kind_other);
        switch (k) {
            case MAP: return Trans.get(c, R.string.trash_kind_map);
            case MOD: return Trans.get(c, R.string.trash_kind_mod);
            case SAVE: return Trans.get(c, R.string.trash_kind_save);
            case SLOT: return Trans.get(c, R.string.trash_kind_slot);
            case SCHEM: return Trans.get(c, R.string.trash_kind_schem);
            default: return Trans.get(c, R.string.trash_kind_other);
        }
    }
}
