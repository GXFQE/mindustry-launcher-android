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
 *   <li><b>各类数据各占多少</b> —— 各槽 / 备份池 / 四个中转站目录；HUB 临时件单独一行。
 *       ★ 中转站与备份池原来是**统计盲区**：`Trash.totalBytes` 的注释自己写着
 *       「中转站此前**不参与任何体检数字**」。第 383 轮起不再在这里列"可清理的残留"：
 *       它并进了卡片上的「清理」动作（见 {@link Cleanup}），本类只回答"空间被什么占了"。</li>
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
         * 其中：**游戏包共用文件**（`app_hub/pkgs/objects`，开了「游戏包去重」才有）。
         *
         * <p>它住内部存储、包含在 {@link #internalBytes} 里，所以既要从「其它」里减掉，
         * 也不许再加进 total。版本数不从目录数推，而是数清单（一个版本 = 一份清单）。</p>
         */
        public long gamePoolBytes;
        public int gamePoolVersions;
        /** 这些版本各自的原始包大小之和（“不共用的话要占多少”） */
        public long gamePoolRaw;
        /**
         * 其中：游戏补丁（`app_hub/patches/<id>/<version>/`，含 dex / 素材 / 清单）。
         *
         * <p>★ 第 354 轮用户：「游戏补丁似乎也没统计」—— 之前它混在「其它」里，
         * 用户既看不到补丁占了地方、也没法把各节加起来对账。
         */
        public long patchBytes;
        public int patchCount;
        /**
         * 缓存（`getCacheDir()` + `getCodeCacheDir()`）—— 游戏运行时的音乐 / 模组 / 临时文件，
         * 加上系统为那些**动态加载**的包生成的 dex 产物。
         * ★ 真机实测：这份缓存有 **73.7 MB**（其中 45 MB 是游戏解出来的模组贴图），
         *   比导入的 APK 本身还显眼 ⇒ 漏掉它，"总分"照样对不上系统设置里的数字。
         */
        public long cacheBytes;
        /**
         * HUB 的临时件（`app_hub/tmp`：导出 / 分享 / 批量导入 / 补丁导入的中间件）。
         * ★ 第 382 轮（阶段 0.5）：它们从系统 cache 搬到了内部 `app_hub` ⇒ 必须**单独列一行**，
         *   否则用户只看到「其它」里一团看不出能不能清的体积（§12.30）。
         * ⚠️ 这一项包含在 {@link #internalBytes} 里，**不许**再加进 total。
         */
        public long tmpBytes;
        /**
         * 上面各段**都没点到**的部分 —— 外部 hub 里的预览缩略图 / 设置备份 / 报告，
         * 加上内部 `app_hub` 里的零碎（配置 / 槽记录）。
         * 不做这一项的话，"总占用"与下面各节之和会对不上，用户只能自己猜差额去哪了。
         * ★ 第 382 轮：`tmpBytes` 已作为单独一行露出来，所以这里要把它从"其它"里**减掉**
         *   （否则同一份体积在界面上出现两次）。
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
     *   备份池走 {@link Backup#poolStats}、中转站走 {@link Trash#listAll} —— 这里一个数都不自己算。
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
        File hub = Data.hubDir(ctx);
        // ★ 预览图（地图/蓝图缩略图）是**可再生缓存**，跟系统 cache 同类 ⇒ 单列进 cacheBytes，
        //   并从 hub 里减掉：它住在 hub 里，不减的话 total 会把这一块算两遍。
        long previewsBytes = Data.sizeTree(new File(hub, "previews"));
        r.hubBytes = Math.max(0L, Data.sizeTree(hub) - previewsBytes);

        // ── 内部存储（★ 第 140 轮补上；导入的游戏包 = 真机上最大的一块）──────────
        File priv = Paths.privateDir(ctx);
        r.internalBytes = Data.sizeTree(priv);
        File imp = new File(priv, "import");
        // ★ 2026-11-23：「导入的游戏包」只算**包本身**，不算目录里别的东西 ——
        //   开了游戏包去重之后原包被删、`import/oat/` 里还留着 ART 的 odex/vdex，
        //   整目录量出来会变成「0 个游戏包 · 60.6 MB」这种自相矛盾的数字。
        r.importBytes = apkBytes(imp);
        r.importCount = countApk(imp);
        r.nativesBytes = Data.sizeTree(new File(priv, "natives"));
        // 游戏包共用文件：只量不建（这页是只读的）。版本数与"原始大小"都从清单拿 ——
        // 那样这一行才能和备份一样说清「占多少 / 不共用的话要多少 / 省了多少」。
        r.gamePoolBytes = Data.sizeTree(new File(priv, "pkgs/objects"));
        for (PkgManifest m : PkgIngest.all(ctx)) {
            r.gamePoolVersions++;
            r.gamePoolRaw += m.size;
        }
        File pl = Plugins.dirNoCreate(ctx);      // ⚠️ 不创建：只读一眼不该多出目录
        r.pluginsBytes = Data.sizeTree(pl);
        r.pluginCount = countApk(pl);
        // ★ 第 354 轮：补丁库（内部存储，dex 只能住这里 —— ART 硬约束）
        File pa = new File(priv, "patches");
        r.patchBytes = Data.sizeTree(pa);
        try {
            r.patchCount = Patches.scan(ctx).size();
        } catch (Throwable ignored) {
            r.patchCount = 0;
        }
        // 两个缓存目录 + 预览图一起算（游戏解出来的音乐 / 模组贴图 + 系统给动态加载的包生成的
        // dex 产物 + 地图/蓝图缩略图）。预览图那部分已从 hubBytes 里减掉，见上面。
        r.cacheBytes = Data.sizeTree(ctx.getCacheDir()) + Data.sizeTree(ctx.getCodeCacheDir())
                + previewsBytes;
        // ★ 第 382 轮（阶段 0.5）：HUB 临时件（app_hub/tmp）单独计量 —— 不跟外部 hub 重复算，
        //   也不加进 total（它本来就在 internalBytes 里）。
        r.tmpBytes = HubTmp.sizeNoFollow(HubTmp.rootNoCreate(ctx));

        // 外部 hub 里"备份 / 中转站"之外的那些（预览缩略图 / 设置备份 / 报告），
        // 以及内部 app_hub 里除上面三项之外的零碎（config.json / 槽记录…）。
        // ⚠️ 用减法算，所以要**夹到 0**：份额取自别处的口径（池 / 中转站），
        //    万一将来有个东西两边都算，负数会在界面上变成一句莫名其妙的"-3 MB"。
        long covered = (r.pool == null ? 0L : r.pool.actual + r.pool.legacyBytes) + r.trashTotalBytes
                + r.importBytes + r.nativesBytes + r.pluginsBytes + r.patchBytes + r.tmpBytes
                + r.gamePoolBytes;
        r.otherBytes = Math.max(0L, r.hubBytes + r.internalBytes - covered);
        return r;
    }

    /**
     * 数一数目录里有多少个 `*.apk`（递归 —— 插件是 `plugins/&lt;id&gt;/&lt;ver&gt;.apk` 两层）。
     * ★ 只数 `.apk`：同一个目录下还躺着 `.part` 残留与别的杂项，按"文件数"报会虚高。
     */
    /** 只累加 `*.apk`（含拷到一半的 `*.apk.part`）的字节数；目录里的 oat/profile 不算。 */
    private static long apkBytes(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long n = 0;
        for (File f : fs) {
            if (f == null) continue;
            if (f.isDirectory()) {
                n += apkBytes(f);
            } else {
                String name = f.getName().toLowerCase(java.util.Locale.US);
                if (name.endsWith(".apk") || name.endsWith(".apk.part")) n += f.length();
            }
        }
        return n;
    }

    private static int countApk(File dir) {        if (dir == null || !dir.isDirectory()) return 0;
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
     * ★ 分三层说：总占用 → 分项（启动器自身 / 各槽 / 备份 / 中转站）。
     * ★ 术语纪律：第一层不出现"对象池 / CAS / 去重"这些词，只说"备份之间共用内容"；
     *   依据（对象数、暂时没人用的部分）用白话跟在后面同一段里。
     */
    static String text(Context c, Report r) {
        StringBuilder sb = new StringBuilder();
        if (r == null) return "";
        sb.append(Trans.get(c, R.string.storage_total_fmt,
                Util.formatSize(r.totalBytes()))).append('\n');
        // ★ 第 354 轮用户「有点不准」：系统设置把「数据 / 缓存」分两栏（数据 886 MB + 缓存 67.11 MB），
        //   合计那一个数对不上任何一栏 ⇒ 这里也把两个数摊开写。
        //   数据 = 合计 − 缓存（外部 hub + 内部 app_hub + 各槽），与系统「数据」同一口径。
        long cache = Math.max(0L, r.cacheBytes);
        if (cache > 0) {
            sb.append(Trans.get(c, R.string.storage_total_split_fmt,
                    Util.formatSize(Math.max(0L, r.totalBytes() - cache)),
                    Util.formatSize(cache))).append('\n');
        }
        // 中转站不算进任何一节（它是"删掉的东西先放这儿"）⇒ 不点明的话，各节加起来会差这一块
        if (r.trashTotalBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_total_trash_fmt,
                    Util.formatSize(r.trashTotalBytes))).append('\n');
        }

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
        // 游戏包共用文件（开了去重才有）—— 术语纪律：第一层只说"共用文件"，不出现 CAS/对象池
        if (r.gamePoolBytes > 0 && r.gamePoolVersions > 0) {
            int gpct = r.gamePoolRaw > r.gamePoolBytes && r.gamePoolRaw > 0
                    ? (int) Math.round((r.gamePoolRaw - r.gamePoolBytes) * 100d / r.gamePoolRaw) : 0;
            sb.append(Trans.get(c, R.string.storage_app_gamepool_fmt, r.gamePoolVersions,
                    Util.formatSize(r.gamePoolBytes),
                    Util.formatSize(Math.max(r.gamePoolRaw, r.gamePoolBytes)),
                    Trans.get(c, R.string.storage_pct_fmt, gpct))).append('\n');
            appAny = true;
        }
        if (r.pluginCount > 0 || r.pluginsBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_plugins_fmt, r.pluginCount,
                    Util.formatSize(r.pluginsBytes))).append('\n');
            appAny = true;
        }
        if (r.patchCount > 0 || r.patchBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_patches_fmt, r.patchCount,
                    Util.formatSize(r.patchBytes))).append('\n');
            appAny = true;
        }
        if (r.cacheBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_cache_fmt,
                    Util.formatSize(r.cacheBytes))).append('\n');
            appAny = true;
        }
        // ★ 第 382 轮（阶段 0.5）：HUB 临时件单独一行（系统"清除缓存"清不到它们，见 §12.32）
        if (r.tmpBytes > 0) {
            sb.append(Trans.get(c, R.string.storage_app_tmp_fmt,
                    Util.formatSize(r.tmpBytes))).append('\n');
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
        // ★ 第 383 轮：不再单独列「可以清理的残留」—— 它并进了卡片上的「清理」动作
        //   （用户：「反正这些都是可以自动清理的，不如合并到一起」）。数字进对话框，
        //   存储页只回答"空间被什么占了"。
        return sb.toString();
    }

    /**
     * 中转站里那一类的名字。
     *
     * <p>★ **唯一映射在 {@link TrashText#kindLabel}**（中转站列表 / 筛选弹窗 / 来源说明都用它）——
     *   这里以前自己抄了一份 `switch`，少了 `PLUGIN` / `VERSION` 两个分支 ⇒ 两者都落到「其他文件」，
     *   存储占用里于是出现**两行同名的「其他文件」**（第 355 轮用户：「怎么有两行其他文件」）。
     *   以后加新的中转站种类只改 `TrashText`，别在这里再抄。
     */
    static String trashKindLabel(Context c, Trash.Kind k) {
        return TrashText.kindLabel(c, k);
    }
}
