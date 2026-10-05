package io.mdt.launcher;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 存档备份 / 恢复（M3 → F16 改为 CAS 存储）。模块边界：依赖 Data/Util/Cas，被 UI 调用。
 *
 * ── 设计要点 ─────────────────────────────────────────────────────────────
 *
 * ① **口径 = 「槽里除排除项外的全部内容」**（★ F19 改的，以前是白名单）。
 *    排除名单只有一份实现：{@link Data#SLOT_EXCLUDE}（+ {@link Data#contentSkipped}），
 *    与「整槽导出」**共用** —— 两边问的是同一个问题"哪些东西值得带走"。
 *
 *    ⚠️ 为什么**不能**用白名单（F19 需求原文：「备份槽不要用白名单，有的模组会乱放
 *       配置文件」）：模组把用户数据放在数据根的**任意顶层目录**（实证：逻辑工具 mod 的
 *       `logic-tool/history/*.json`，33 个用户创作、丢了不可重建）。这类目录我们事先
 *       **不可能枚举**，白名单只会静默漏掉它们，而**备份看起来完全成功**。
 *       ⇒ 判据必须反过来：凡是我们没法重造的，都要在；只排除"确定不是用户数据"的。
 *
 * ② **非当前槽不需要交换目录**：槽的数据要么在数据根本体、要么在平级的
 *    `slot-&lt;名&gt;`，都是普通路径（{@link Data#dirOf}）。⇒ 给别的槽做备份/恢复
 *    完全不碰数据根，比探针阶段的"换回来再操作"安全得多。
 *
 * ③ **恢复前先证"这份备份没坏"**（不是验目标），再动手覆盖用户数据。
 *    ★ F16 起对象池版是**边写边校验 sha256**（一遍 I/O，见 {@link Cas#getTo}）；
 *    对象缺失 / 内容与地址不符 ⇒ 该项报错，其余照做（不做"全有或全无"的整份拒绝
 *    —— 池是不可变的，坏一项不影响别的项）。
 *
 * ④ **游戏在跑时拒绝对当前槽做备份/恢复**：游戏进程持有数据根，边写边覆盖
 *    结果不可预期。非当前槽不受影响。
 *
 * ⑤ **快照目录名 = `yyyyMMdd-HHmmss`**（可排序、可人读），同秒冲突加后缀。
 *
 * ── F16：从「全量拷贝」改成「CAS 引用」 ────────────────────────────────────
 *
 * 旧格式（{@link #MARK1}）：`&lt;快照&gt;/files/&lt;当时的白名单条目的原样拷贝&gt;` + manifest 记 md5。
 *   ⇒ 每份备份各存一份完整副本。桌面端实测 27 份备份 5.1 GB。
 *   ⚠️ **v1 是白名单口径、v2 现在是"全部 − 排除"** —— 两者**不冲突**：清单里的 rel
 *      一直是"相对槽根的路径"，{@link #restore} 只照着写，不关心它当初是怎么选出来的。
 *      ⇒ 老快照照旧能恢复（只是它里面本来就没有 `logic-tool/` 这类顶层数据）。
 *
 * 新格式（{@link #MARK2}）：`&lt;快照&gt;/manifest.txt` 里记 **sha256 → 相对路径**，
 *   内容进全局对象池 `&lt;hub&gt;/cas/objects/`。⇒ 快照目录只剩几 KB 的清单。
 *   桌面端实测同一批 27 份 **5.1 GB → 328.6 MB（省 93.7%）**，Android 口径
 *   20 份 3.0 GB → 241 MB；3 槽 × 20 份 9.1 GB → **448 MB**。见 BACKLOG 4.5。
 *
 * ★ 收益来自两个反直觉的事实（实测，不是推算）：`mods` 占一份备份 74% 且
 *   **跨备份、跨槽都不变**；`saves` 复用率 96.2%（313 个文件里每次只有正在玩的
 *   那一两个在变）。所以"备份打的是整个目录"这件事本身就值 13~24 倍。
 *   ⚠️ 这两个百分比是**白名单口径**下测的（旧数据）。F19 改成"全部 − 排除"后
 *      分母变大（多了 maps / previews / logic-tool 等），而它们**同样是低频变动**的
 *      ⇒ 复用率只会更高，**省的比例只会更好看**。别拿旧百分比去反推新逻辑对不对。
 *
 * ★ 附带收益：CAS 对象不可变 + 原子落盘 ⇒ 备份过程被打断**不会留半成品**
 *   （旧 `copyRecursive` 直接写目标，中断 = 半个快照；而半个快照看起来是"有备份的"）。
 *
 * ── 目录布局 ──────────────────────────────────────────────────────────────
 *
 *   &lt;hub&gt;/backups/&lt;槽名&gt;/&lt;快照&gt;/manifest.txt     ← 只有清单（几 KB）
 *   &lt;hub&gt;/cas/objects/&lt;sha 前2位&gt;/&lt;sha 后62位&gt;      ← 内容池，**全部槽共享**
 *
 *   ★ 池不放 `backups/` 里面（桌面版是 `Backups/objects`，与 profile 目录平级）：
 *     否则「用户把槽命名成 objects」会撞池。分两棵子树 ⇒
 *     {@link #deleteSlotBackups} / {@link #renameSlotBackups} 天然碰不到池。
 *
 * ── 旧备份怎么办 ─────────────────────────────────────────────────────────
 *
 * 口径（2026-10-01）：**自动处理，不考虑向上兼容**。⇒
 * {@link #migrateLegacy} 把 v1 快照就地转成 v2（先落对象 → 原子写新清单 → 再删
 * `files/`），由存档页进入时后台触发一次。读路径仍保留 v1 分支 —— 那是**迁移的
 * 输入**，不是兼容包袱。
 */
public final class Backup {
    private static final String TAG = "MDTLauncher";

    private static final String MANIFEST = "manifest.txt";
    /** 旧格式（`files/` 全量拷贝 + md5 指纹） */
    private static final String MARK1 = "mdt-backup 1";
    /** ★ F16 CAS 格式（对象池 + sha256 寻址） */
    private static final String MARK2 = "mdt-backup 2";
    /** 本工程写出的格式 */
    private static final String MARK = MARK2;
    private static final String SEP = "---";

    /** 旧格式的快照内容子目录 */
    private static final String LEGACY_FILES = "files";

    private Backup() {}

    // ── 目录 ──────────────────────────────────────────────────────────────

    /** 所有备份的根：&lt;外部 hub&gt;/backups */
    public static File rootDir(Context ctx) {
        File d = new File(Data.hubDir(ctx), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** CAS 基目录：&lt;外部 hub&gt;/cas（对象池 = 其下 objects/） */
    public static File casBaseDir(Context ctx) {
        return new File(Data.hubDir(ctx), "cas");
    }

    /** 本进程用的对象池句柄（轻量，无状态） */
    private static Cas cas(Context ctx) {
        return new Cas(casBaseDir(ctx));
    }

    /** 某个槽的备份目录 */
    public static File slotDir(Context ctx, String slot) {
        return new File(rootDir(ctx), slot);
    }

    // ── 快照 ──────────────────────────────────────────────────────────────

    public static final class Snapshot {
        public File dir;
        public String slot = "";
        public long created;
        public int count;
        public long bytes;
        public String label = "";
        public String source = "";

        /** 1 = 旧格式（`files/` 全量拷贝，md5）；2 = CAS（对象池，sha256） */
        public int version = 2;
        /** 本次真正新增到对象池的字节数（create 时填；列出时无意义） */
        public long storedNew;

        public String title() {
            return stamp(created) + (label.isEmpty() ? "" : "   " + label);
        }

        // ⚠️ 这里原来还有一个 `subtitle()`（`N 个文件 · 体积`）—— 2026-10-05 核实**全库无调用**
        //    （快照行的统计段早已统一走资源，见 SavesActivity 里那条注释）⇒ 当死代码删掉。
        //    留着它只会让 SRC-02 的台账里多一条"其实没人看的中文"。

        /** 还是旧格式（内容自成一份），会被自动迁移 */
        public boolean legacy() {
            return version < 2;
        }
    }

    /** 一次备份里的一个文件。v1 的 hash 是 md5 指纹，v2 是 sha256 地址。 */
    private static final class Entry {
        String hash;
        long size;
        String rel;

        Entry(String hash, long size, String rel) {
            this.hash = hash;
            this.size = size;
            this.rel = rel;
        }
    }

    /**
     * 给某个槽做一份快照。失败抛 IOException（已建好的半截目录会被清掉）。
     *
     * ⚠️ **成本口径（F19 起）**：要把**整个槽**（除 {@link Data#SLOT_EXCLUDE}）读一遍
     *    算 sha256 —— 以前（白名单）只读 `saves` + `mods`。多出来的是 `maps` /
     *    `previews` / `logic-tool` 这类**低频变动**的目录，体积占比小、且第一份之后
     *    全部命中对象池 ⇒ **只有第一次备份变慢**，之后基本只读不写。
     *    {@link AutoBackup} 每局结束会调它，所以这个成本要在"游戏已退出、后台线程"里付。
     *
     * @param label 用户可读备注，可为空
     */
    public static Snapshot create(Context ctx, String slot, String label) throws IOException {
        boolean live = slot.equals(Data.currentSlot(ctx));
        if (live && Data.gameAlive(ctx)) {
            throw new IOException(ctx.getString(R.string.backup_err_game_running));
        }
        File src = Data.dirOf(ctx, slot);
        if (src == null || !src.exists()) {
            throw new IOException(ctx.getString(R.string.backup_err_no_data_fmt, slot));
        }

        File parent = slotDir(ctx, slot);
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException(ctx.getString(R.string.backup_err_mkdir_fmt,
                    parent.getAbsolutePath()));
        }
        long now = System.currentTimeMillis();
        File dest = new File(parent, stamp(now));
        for (int i = 1; dest.exists() && i < 100; i++) {
            dest = new File(parent, stamp(now) + "-" + i);
        }
        if (dest.exists()) throw new IOException(ctx.getString(R.string.backup_err_same_second));

        Snapshot ss = new Snapshot();
        ss.dir = dest;
        ss.slot = slot;
        ss.created = now;
        ss.label = (label == null ? "" : label.replace('\n', ' ').trim());
        ss.source = src.getAbsolutePath();

        Cas pool = cas(ctx);
        List<Entry> entries = new ArrayList<>();
        boolean ok = false;
        try {
            // ★★ 「写对象 → 写清单」整段持批量写锁（照抄桌面端 _BULK_WRITE_LOCK）：
            //    中间任一时刻这些对象都还没有引用者，此刻若 GC 进来会把它们当孤儿
            //    删掉，而清单随后照样写成功 ⇒ 症状是"备份列表正常，一恢复报对象丢失"。
            synchronized (Cas.BULK_LOCK) {
                // ★★ F19：口径改成「数据根全部内容 − Data.SLOT_EXCLUDE」。
                //   清单里的 rel **一律相对槽根**（不是相对某个顶级条目）——
                //   于是"模组把配置放在顶层 `logic-tool/`"与"放在 `saves/` 里"
                //   在清单里长得一样，{@link #restore} 完全不需要知道它们是哪一类。
                //   条目顺序由 {@link Data#contentRoots} 保证稳定（目录在前、名字升序）。
                List<File> roots = Data.contentRoots(ctx, slot);
                for (File s : roots) {
                    List<String> rels = new ArrayList<>();
                    walk(src, s, rels);
                    Collections.sort(rels);
                    for (String r : rels) {
                        File f = new File(src, r);
                        Cas.Stored st = pool.put(f, null);
                        if (st.isNew) ss.storedNew += st.bytes;
                        entries.add(new Entry(st.sha, st.bytes, r));
                    }
                }
                for (Entry e : entries) {
                    ss.count++;
                    ss.bytes += e.size;
                }
                if (ss.count == 0) {
                    throw new IOException(ctx.getString(R.string.backup_err_nothing_fmt,
                            slot, src.getAbsolutePath(),
                            join(Data.SLOT_EXCLUDE, ctx.getString(R.string.list_join_sep))));
                }
                if (!dest.mkdirs() && !dest.isDirectory()) {
                    throw new IOException(ctx.getString(R.string.backup_err_snapshot_mkdir_fmt,
                            dest.getAbsolutePath()));
                }
                writeManifest(dest, ss, entries);
            }
            ok = true;
        } finally {
            if (!ok) Data.deleteTree(dest);     // 半截快照不留（对象留在池里，GC 会收）
        }
        Log.i(TAG, "backup ok: slot=" + slot + " files=" + ss.count
                + " logical=" + ss.bytes + " new=" + ss.storedNew + " -> " + dest);
        return ss;
    }

    /** 某个槽的全部快照，新的在前 */
    public static List<Snapshot> list(Context ctx, String slot) {
        List<Snapshot> out = new ArrayList<>();
        File parent = slotDir(ctx, slot);
        File[] ds = parent.listFiles();
        if (ds == null) return out;
        for (File d : ds) {
            if (!d.isDirectory()) continue;
            Snapshot ss = readManifest(new File(d, MANIFEST));
            if (ss == null) continue;
            ss.dir = d;
            ss.slot = slot;
            out.add(ss);
        }
        Collections.sort(out, new java.util.Comparator<Snapshot>() {
            @Override public int compare(Snapshot a, Snapshot b) {
                return Long.compare(b.created, a.created);
            }
        });
        return out;
    }

    /**
     * 删除一份快照（硬删）。返回错误文本，成功 null。
     * ★ 删完**立刻调度一次 GC** —— 对象池是全局的，不回收的话快照删了空间还在。
     */
    public static String delete(Context ctx, Snapshot ss) {
        if (ss == null || ss.dir == null || !ss.dir.exists()) {
            return ctx.getString(R.string.backup_err_snapshot_missing);
        }
        if (!Data.deleteTree(ss.dir)) {
            return ctx.getString(R.string.backup_err_delete_partial_fmt, ss.dir.getAbsolutePath());
        }
        File p = ss.dir.getParentFile();
        if (p != null && p.listFiles() != null && p.listFiles().length == 0) p.delete();
        scheduleGc(ctx);
        return null;
    }

    /**
     * 恢复：把快照里的条目写回目标槽。**三种模式**（2026-10-05 第 104 轮，用户要的）：
     * <pre>
     *   {@link SlotWrite#UPDATE}    —— 两边都有的用快照里的覆盖（＝老行为，默认）
     *   {@link SlotWrite#KEEP_OLD}  —— 两边都有的保留槽里的，只把缺的补上
     *   {@link SlotWrite#REPLACE}   —— 先按 {@link Data#contentRootsOf} 清空槽内容，再整份写回
     * </pre>
     *
     * ★ **默认（{@link SlotWrite#UPDATE}）与老行为逐字相同**：只覆盖快照里有的文件，
     *   其余原样留着 —— 包括"备份之后新产生的"和"本来就在排除名单里的"。
     *   所以恢复**不是**"把槽还原成快照那一刻"，而是"把快照里记着的东西按原内容放回去"。
     *   ⚠️ 别把默认改成"先清空"：清空是**不可逆**的，而"哪些文件是备份之后加的"我们
     *      **无法判定**（mtime 会被 FUSE / 导入动作改掉，见 REF §33）⇒ 想要完全替换的用户
     *      必须**自己选**「覆盖」，而且那条路动手前一定先自动备份
     *      （见 {@link AutoBackup#beforeSlotOp}）。
     *
     * ★ CAS 版是**边写边校验 sha256**（一遍 I/O）：对象池里的内容与清单里记的地址
     *   不符就报错，宁可不写也不写出坏存档。任何一项失败只记该项，其余继续
     *   （池是不可变的，坏一项不影响别的项）。
     *
     * **返回 {@link RestoreResult}**：成败是一个**布尔值**，不是从报告文案里读出来的。
     */
    public static RestoreResult restore(Context ctx, String slot, Snapshot ss, int mode) {
        mode = SlotWrite.sane(mode);
        if (ss == null || ss.dir == null || !ss.dir.exists()) {
            return fail(ctx.getString(R.string.backup_restore_err_no_snapshot));
        }
        if (gameAliveOn(ctx, slot)) {
            return fail(ctx.getString(R.string.backup_restore_err_game_running));
        }
        List<Entry> entries;
        try {
            entries = readEntries(ctx, new File(ss.dir, MANIFEST));
        } catch (IOException e) {
            return fail(ctx.getString(R.string.backup_restore_err_manifest_fmt, e.getMessage()));
        }
        if (entries.isEmpty()) {
            return fail(ctx.getString(R.string.backup_restore_err_manifest_empty));
        }

        File dstRoot = Data.dirOf(ctx, slot);
        if (dstRoot == null) return fail(ctx.getString(R.string.backup_restore_err_no_dst));
        if (!dstRoot.exists() && !dstRoot.mkdirs()) {
            return fail(ctx.getString(R.string.backup_restore_err_mkdir_fmt,
                    dstRoot.getAbsolutePath()));
        }
        StringBuilder wiped = new StringBuilder();
        if (SlotWrite.wipeFirst(mode)) {
            try {
                for (String n : SlotWrite.wipeSlot(ctx, dstRoot)) {
                    if (wiped.length() > 0) wiped.append("、");
                    wiped.append(n);
                }
            } catch (IOException e) {
                // 清了一半就停：宁可什么都不写（快照还在，用户可以再来一次）
                return fail(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            }
        }

        int ok = 0, kept = 0;
        StringBuilder errs = new StringBuilder();
        if (ss.version >= 2) {
            // ── CAS：从对象池流式还原，边写边验 ──────────────────────────
            Cas pool = cas(ctx);
            for (Entry e : entries) {
                File to = new File(dstRoot, e.rel);
                if (!SlotWrite.sourceWins(mode) && to.exists()) {
                    kept++;
                    continue;
                }
                try {
                    pool.getTo(e.hash, to, true);
                    ok++;
                } catch (IOException ex) {
                    // ★ 走 CasText：Cas 的对象池异常带码 ⇒ 映射成资源（原来直接把 getMessage() 端给用户）；
                    //   v1 那条路上的异常不是 CasException ⇒ 它原样返回消息（行为不变）。
                    errs.append("  ").append(ctx.getString(R.string.backup_restore_err_line_fmt,
                            e.rel, CasText.reason(ctx, ex)));
                }
            }
        } else {
            // ── v1：先自证这份备份本身没坏（md5），再覆盖 ──────────────
            File srcRoot = new File(ss.dir, LEGACY_FILES);
            List<String> bad = new ArrayList<>();
            for (Entry e : entries) {
                File f = new File(srcRoot, e.rel);
                if (!f.isFile()) {
                    bad.add(ctx.getString(R.string.backup_restore_bad_missing_fmt, e.rel));
                    continue;
                }
                if (f.length() != e.size) {
                    bad.add(ctx.getString(R.string.backup_restore_bad_size_fmt,
                            e.rel, f.length(), e.size));
                    continue;
                }
                if (!Util.md5(f).equals(e.hash)) {
                    bad.add(ctx.getString(R.string.backup_restore_bad_md5_fmt, e.rel));
                }
            }
            if (!bad.isEmpty()) {
                StringBuilder sb = new StringBuilder(
                        ctx.getString(R.string.backup_restore_err_selfcheck_fmt, ""));
                for (int i = 0; i < bad.size() && i < 8; i++) {
                    sb.append("  ").append(ctx.getString(R.string.backup_restore_bullet_fmt,
                            bad.get(i)));
                }
                if (bad.size() > 8) {
                    sb.append("  ").append(ctx.getString(R.string.backup_restore_more_fmt,
                            bad.size()));
                }
                return fail(sb.toString());
            }
            for (Entry e : entries) {
                File from = new File(srcRoot, e.rel);
                File to = new File(dstRoot, e.rel);
                if (!SlotWrite.sourceWins(mode) && to.exists()) {
                    kept++;
                    continue;
                }
                try {
                    copyRecursive(from, to);
                    ok++;
                } catch (IOException ex) {
                    // ★ 走 CasText：Cas 的对象池异常带码 ⇒ 映射成资源（原来直接把 getMessage() 端给用户）；
                    //   v1 那条路上的异常不是 CasException ⇒ 它原样返回消息（行为不变）。
                    errs.append("  ").append(ctx.getString(R.string.backup_restore_err_line_fmt,
                            e.rel, CasText.reason(ctx, ex)));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.backup_restore_ok_head_fmt, slot));
        sb.append("    ").append(ctx.getString(R.string.backup_restore_ok_snapshot_fmt, ss.title()));
        sb.append("    ").append(ctx.getString(R.string.backup_restore_ok_files_fmt, ok,
                entries.size(), Util.formatSize(ss.bytes)));
        if (kept > 0) {
            sb.append("    ").append(ctx.getString(R.string.backup_restore_ok_kept_fmt, kept));
        }
        if (wiped.length() > 0) {
            sb.append("    ").append(ctx.getString(R.string.backup_restore_ok_wiped_fmt,
                    wiped.toString()));
        }
        sb.append("    ").append(ctx.getString(R.string.backup_restore_ok_where_fmt,
                dstRoot.getAbsolutePath()));
        if (ok < entries.size()) {
            sb.append(ctx.getString(R.string.backup_restore_partial_fmt, errs.toString()));
        }
        Log.i(TAG, "restore slot=" + slot + " ok=" + ok + "/" + entries.size()
                + " kept=" + kept + " mode=" + mode + " v=" + ss.version);
        return new RestoreResult(true, sb.toString());
    }

    /**
     * 老的入口：**模式 = 更新**（只覆盖同名，其余原样留着）。
     * ★ 留着它是为了让"克隆槽 / 自检"这些**本来就不该换模式**的调用点读起来干净，
     *   同时也把"默认是哪一个"写进签名里。
     */
    public static RestoreResult restore(Context ctx, String slot, Snapshot ss) {
        return restore(ctx, slot, ss, SlotWrite.UPDATE);
    }

    /**
     * {@link #restore} 的结果。
     *
     * 🔴 **成败必须读 {@link #ok}，不许去读 {@link #report} 的开头**（2026-10-04，i18n P0.1）。
     *   原来两处调用方判 `report.startsWith("✅")` —— 而那个 ✅ 是 {@code restore} 自己拼进报告里的
     *   一句中文/符号。它一被本地化，"恢复成功"就会被显示成"未执行"，而且**不报错、不崩溃、没有任何
     *   自检会红**。构建期门禁规则 `SRC-01` 就是钉这一类的（见 docs/i18n-feasibility.md §5.2）。
     *   {@code report} 里仍然带 ✅ / ⚠ 前缀，但那**只是给人看的**，不再承担判据职责。
     */
    public static final class RestoreResult {
        /** 真的执行了恢复（⚠ 开头的几种早退都是 false） */
        public final boolean ok;
        /** 给用户看的报告正文 */
        public final String report;

        RestoreResult(boolean ok, String report) {
            this.ok = ok;
            this.report = report;
        }
    }

    /** `restore` 的失败出口（早退的几种原因） */
    private static RestoreResult fail(String report) {
        return new RestoreResult(false, report);
    }

    // ── 克隆槽（F4②） ─────────────────────────────────────────────────────

    /**
     * **把槽 `src` 整份复制成一个新槽 `rawDst`**。成功返回 null，失败返回给用户看的原因。
     *
     * ★ 实现就是「{@link #create} 源槽 + {@link #restore} 新槽」——**零新原语**。
     *   刻意不另写一份"复制目录树"：那会出现第二套排除口径（排除名单 / 空壳判定 /
     *   `.part` 过滤各写一遍），而它们**不一致时没有任何症状**，只会让
     *   "克隆出来的槽比源槽少几个文件"半年后才被发现。
     *
     * ★ 为什么放在 `Backup` 而**不是** `Data`：`Backup` 已经依赖 `Data`（`dirOf` /
     *   `contentRoots` / `slotDir`…），反向再依赖就成环；而"槽级备份目录"本来就归这里管
     *   （见 {@link #renameSlotBackups} / {@link #deleteSlotBackups}）。
     *
     * ⚠️ **中间快照会留在源槽的备份列表里**（标题 = `label`）。这不是"顺手做个备份"，
     *   而是没有"内存快照"这个原语时，唯一能同时拿到"一致性 + 逐字节可校验"的走法；
     *   附带好处是用户**可以再恢复一次**。别为了"干净"把它删掉 —— 删了就没法复现克隆源。
     *
     * ⚠️ 调用方负责：`src` 此刻不在被写入。{@link #create} 自己就会拦
     *   "`src` 是当前槽且 `:game` 还活着"，这里**不再重复判**（两处判据迟早分岔）。
     *
     * @param label 中间快照的备注（用户会在源槽的备份列表里看到这一行）
     */
    public static String cloneSlot(Context ctx, String src, String rawDst, String label) {
        // 名字不合法 / 已存在 ⇒ 原样把 createSlot 的错给用户（不自动改名：
        // 用户填的名字就是他要的名字，替他做主比报错更糟）。
        String err = Data.createSlot(ctx, rawDst);
        if (err != null) return err;
        String dst = Data.sanitizeSlot(rawDst);
        if (dst == null) return ctx.getString(R.string.backup_err_slot_name_invalid);
        // ⚠️⚠️ 必须自己再判一次"目标 = 当前槽"：`Data.createSlot` 对当前槽是**返回成功**的，
        //   因为它的语义是"当前槽不算新建，本体由启动时按需 mkdirs"。
        //   若放过这一步，`restore` 会把源槽内容**合并写进当前槽**（restore 只增不删）
        //   —— 两份数据混在一起、再也分不开，是本功能能造成的最坏破坏。
        if (dst.equals(Data.currentSlot(ctx))) {
            return ctx.getString(R.string.backup_err_clone_target_current_fmt, dst);
        }
        try {
            Snapshot ss = create(ctx, src, label);
            RestoreResult rr = restore(ctx, dst, ss);
            // ★ 成败读 rr.ok，**不许读报告开头**（见 RestoreResult 的注释）。
            //   restore 不返回 null，所以这里没有"拿不到结果"那条分支了。
            if (!rr.ok) return rr.report;
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        }
    }

    // ── 槽改名 / 删除时跟着搬备份 ─────────────────────────────────────────

    /** 槽改名：把它的备份目录也搬过去（搬不动不算错，只记日志）。对象池不受影响。 */
    public static void renameSlotBackups(Context ctx, String from, String to) {
        File a = slotDir(ctx, from);
        File b = slotDir(ctx, to);
        if (!a.exists() || b.exists()) return;
        if (!a.renameTo(b)) Log.w(TAG, "backup slot rename failed: " + from + " -> " + to);
    }

    /** 槽被删除时一并清掉它的备份（调用方需已确认）。清完调度 GC 回收孤儿对象。 */
    public static String deleteSlotBackups(Context ctx, String slot) {
        File d = slotDir(ctx, slot);
        if (!d.exists()) return null;
        if (!Data.deleteTree(d)) {
            return ctx.getString(R.string.backup_err_slot_backups_delete_fmt, d.getAbsolutePath());
        }
        scheduleGc(ctx);
        return null;
    }

    // ── 对象池：引用统计 / 回收 / 体检 ────────────────────────────────────

    /**
     * 收集**当前仍被引用的全部对象地址** —— 扫所有槽的所有快照清单里的 sha 列。
     *
     * 桌面版对应 `collect_referenced_objects()`。这里是 GC 的唯一"不能删"名单来源。
     * ⚠️ 读不出来的清单**不参与统计也不报错**：它的对象会被当孤儿删掉 —— 这与桌面版
     *   一致（桌面版也是 warning + 跳过）。但代价是"清单损坏 ⇒ 备份内容真没了"，
     *   所以清单一律走 {@link Util#atomicWriteText} 原子写（不会出现半个清单）。
     */
    public static Set<String> allReferencedShas(Context ctx) {
        Set<String> refs = new HashSet<>();
        scanRefs(rootDir(ctx), refs);
        // ★ 中转站里的**整槽**（2026-10-05 第二批）：容器里的 `backups/` 同样是**活引用**。
        //   不扫这一处，下一次 GC 就会把那些快照引用的对象当孤儿删掉 —— 而用户此刻正看着
        //   "删掉的槽还能放回来"，恢复回来却是一份坏快照（静默丢数据，且没有任何症状）。
        //   ⚠️ 与 `Trash.trashSlot` 是**一对**：那边负责"别把备份落下"，这里负责"别把对象回收掉"。
        File containers = Trash.slotsDir(ctx);
        File[] cs = containers.isDirectory() ? containers.listFiles() : null;
        if (cs != null) {
            for (File c : cs) {
                if (!c.isDirectory()) continue;
                scanRefs(new File(c, Trash.INNER_BACKUPS), refs);
            }
        }
        return refs;
    }

    /** 扫一个"备份根"下的 `&lt;槽&gt;/&lt;快照&gt;/manifest`（`hub/backups` 与中转站容器里的 `backups/` 共用） */
    private static void scanRefs(File root, Set<String> refs) {
        File[] slots = root == null ? null : root.listFiles();
        if (slots == null) return;
        for (File sd : slots) {
            if (!sd.isDirectory()) continue;
            File[] snaps = sd.listFiles();
            if (snaps == null) continue;
            for (File snap : snaps) {
                if (!snap.isDirectory()) continue;
                collectShas(new File(snap, MANIFEST), refs);
            }
        }
    }

    /** 某个槽的快照清单里引用到的地址（迁移/自查用） */
    private static void collectShas(File manifest, Set<String> out) {
        try {
            String text = Util.readText(manifest);
            if (text == null || !text.startsWith(MARK2)) return;   // v1 的内容不在池里
            boolean inTable = false;
            for (String line : text.split("\n")) {
                if (!inTable) {
                    if (SEP.equals(line)) inTable = true;
                    continue;
                }
                if (line.trim().isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String sha = line.substring(0, tab);
                if (sha.length() == 64) out.add(sha);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 立刻做一次回收（**同步**，可能花几秒 —— 只该在后台线程调）。
     * @return 删除的对象数
     */
    public static int gcNow(Context ctx) {
        return cas(ctx).garbageCollect(allReferencedShas(ctx));
    }

    private static final AtomicBoolean GC_BUSY = new AtomicBoolean(false);

    /**
     * 后台调度一次回收（去重：同进程内同时只有一次在跑）。
     * ⚠️ 传进来的 Context 一律换成 application context —— 后台线程持有 Activity
     *   会泄漏；这里只用来解析路径，不需要 Activity。
     */
    public static void scheduleGc(final Context ctx) {
        if (ctx == null) return;
        if (!GC_BUSY.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int n = gcNow(app);
                    if (n > 0) Log.i(TAG, "cas gc removed " + n + " object(s)");
                } catch (Throwable e) {
                    Log.w(TAG, "cas gc failed: " + e);
                } finally {
                    GC_BUSY.set(false);
                }
            }
        }, "mdt-cas-gc");
        t.setPriority(Thread.MIN_PRIORITY);   // 回收是后台杂活，别跟 UI/游戏抢 CPU
        t.start();
    }

    /** 对象池体检报告（不删任何东西） */
    public static final class PoolStats {
        public int snapshots;
        public int legacySnapshots;
        /** 所有快照的逻辑总量 —— 没有 CAS 时这些内容会占的空间 */
        public long logical;
        /** 还是旧格式的快照实际占用的字节（迁移后应归零） */
        public long legacyBytes;
        /** 对象池实际占用 */
        public long actual;
        public int objects;
        public int orphans;
        public long orphanBytes;

        /**
         * 节省率 0~1；逻辑量为 0 时返回 0。
         *
         * ★ 口径：`1 - 有效占用 / 逻辑总量`，其中「有效占用」= 对象池实际占用 **减去**
         *   孤儿（孤儿迟早会被 GC 收掉，不该算进"这套备份真实要花的空间"）。
         * ⚠️ 这里曾经写反成 `1 - (logical - actual) / logical`（= 得到了"占比"而不是
         *   "节省率"，真机上 74.4% 被报成 25.6%）—— 一个只会在**有真实数据**时才
         *   看得出来的错误，自检用小数据同样测不出来。
         */
        public double saved() {
            if (logical <= 0) return 0d;
            long effective = Math.max(0L, actual - orphanBytes);
            return Math.max(0d, 1d - (double) effective / logical);
        }
    }

    /**
     * 算一遍体检数字。⚠️ 要遍历所有快照 + 整个对象池 + 旧格式的 `files/` 目录，
     * 只该在后台线程调。
     */
    public static PoolStats poolStats(Context ctx) {
        PoolStats st = new PoolStats();
        File[] slots = rootDir(ctx).listFiles();
        if (slots != null) {
            for (File sd : slots) {
                if (!sd.isDirectory()) continue;
                File[] snaps = sd.listFiles();
                if (snaps == null) continue;
                for (File snap : snaps) {
                    if (!snap.isDirectory()) continue;
                    Snapshot ss = readManifest(new File(snap, MANIFEST));
                    if (ss == null) continue;
                    st.snapshots++;
                    st.logical += ss.bytes;
                    if (ss.legacy()) {
                        st.legacySnapshots++;
                        st.legacyBytes += treeSize(new File(snap, LEGACY_FILES));
                    }
                }
            }
        }
        Cas.Pool p = cas(ctx).scan(allReferencedShas(ctx));
        st.objects = p.objects;
        st.actual = p.bytes;
        st.orphans = p.orphans;
        st.orphanBytes = p.orphanBytes;
        return st;
    }

    // ── 旧格式自动迁移（v1 → v2） ─────────────────────────────────────────

    /**
     * ★★ F10：给一份快照算一行「这是什么存档」（真名 · 尺寸 · 第 N 波 · 格式 vN）。
     *
     * 取法：清单里挑**最大的那份 `saves/*.msav`**（主存档通常最大）。
     *  · v2（CAS）：按 sha 找到对象池里的文件，**直接开流**读 meta —— 我们的解析器只读第一块，
     *    所以不会把整个对象拉出来（几十 MB 的存档也是毫秒级）；
     *  · v1（旧格式）：快照目录里就是真文件，直接读。
     *
     * 读不出来 / 快照里没有存档 / 池里缺对象 ⇒ **一律返回空串**：
     * 这只是"锦上添花"的信息（界面会退回只显示时间戳），**绝不能因此报错或崩**。
     */
    public static String msavLine(Context ctx, Snapshot ss) {
        if (ss == null || ss.dir == null) return "";
        try {
            if (ss.legacy()) {
                File saves = new File(new File(ss.dir, "files"), "saves");
                File[] fs = saves.listFiles();
                File pick = null;
                if (fs != null) {
                    for (File f : fs) {
                        if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".msav")) continue;
                        if (pick == null || f.length() > pick.length()) pick = f;
                    }
                }
                if (pick == null) return "";
                MsavMeta m = MsavMeta.read(pick);
                return m.ok ? MsavText.shortLine(ctx, m, true) : "";   // 快照里的都是存档
            }
            List<Entry> es = readEntries(ctx, new File(ss.dir, MANIFEST));
            Entry best = null;
            for (Entry e : es) {
                if (e.rel == null || !e.rel.startsWith("saves/")) continue;
                if (!e.rel.toLowerCase(Locale.ROOT).endsWith(".msav")) continue;
                if (best == null || e.size > best.size) best = e;
            }
            if (best == null) return "";
            File obj = cas(ctx).objectPath(best.hash);
            if (!obj.isFile()) return "";                 // 池里缺对象：静默退化，不打扰用户
            InputStream in = new java.io.BufferedInputStream(new FileInputStream(obj), 8192);
            try {
                MsavMeta m = MsavMeta.read(in);
                return m.ok ? MsavText.shortLine(ctx, m, true) : "";   // 快照里的都是存档
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";                                     // 见方法注释：这是加分项，不是关键路径
        }
    }

    /** 有没有还没迁移的旧格式快照（便宜：只看 manifest 头） */
    public static boolean hasLegacy(Context ctx) {        File[] slots = rootDir(ctx).listFiles();
        if (slots == null) return false;
        for (File sd : slots) {
            if (!sd.isDirectory()) continue;
            File[] snaps = sd.listFiles();
            if (snaps == null) continue;
            for (File snap : snaps) {
                File mf = new File(snap, MANIFEST);
                if (!mf.isFile()) continue;
                // 只读头部十来字节：本方法每次进存档页都要调，读整个清单（~100 KB）没必要
                String head = readHead(mf);
                if (head != null && head.startsWith(MARK1)) return true;
            }
        }
        return false;
    }

    /**
     * 把旧格式快照就地转成 CAS 格式。**幂等**，可反复调。
     *
     * 单份快照的四步（顺序不可颠倒）：
     *   ① 读 v1 清单 → 得到 (md5, size, rel)
     *   ② 逐文件：`&lt;快照&gt;/files/&lt;rel&gt;` 读一遍，**同时**算 md5 与 sha256
     *      —— md5 与清单不符 ⇒ **整份跳过**（保守：可疑数据不动，也不入库）
     *   ③ 全部落池后**原子写 v2 清单**（此刻新格式已经可用）
     *   ④ 最后才删 `files/`（③失败 ⇒ v1 完好；④失败 ⇒ 只是白占空间，不影响正确性）
     *
     * @return 人读报告；没有任何旧快照时返回 null（调用方据此决定要不要刷新 UI）
     *
     * ⚠️ `static synchronized`（锁 Backup.class）是给「自动迁移 + dev 口手迁」两条路
     *    串行化用的 —— 两个线程同时迁同一份快照会互相踩。锁顺序恒为
     *    Backup.class → {@link Cas#BULK_LOCK}，不存在环形等待。
     */
    public static synchronized String migrateLegacy(Context ctx) {
        File[] slots = rootDir(ctx).listFiles();
        if (slots == null) return null;
        Cas pool = cas(ctx);
        int done = 0;
        int skipped = 0;
        int failed = 0;
        long freedOrRaw = 0;
        long logical = 0;
        StringBuilder notes = new StringBuilder();
        for (File sd : slots) {
            if (!sd.isDirectory()) continue;
            File[] snaps = sd.listFiles();
            if (snaps == null) continue;
            for (File snap : snaps) {
                if (!snap.isDirectory()) continue;
                File mf = new File(snap, MANIFEST);
                if (!mf.isFile()) continue;
                Snapshot ss;
                try {
                    ss = readManifest(mf);
                } catch (Throwable t) {
                    ss = null;
                }
                if (ss == null || !ss.legacy()) continue;

                File filesRoot = new File(snap, LEGACY_FILES);
                if (!filesRoot.isDirectory()) {
                    skipped++;
                    notes.append("  · ").append(sd.getName()).append('/')
                            .append(snap.getName()).append("：内容目录缺失，跳过\n");
                    continue;
                }
                List<Entry> entries;
                try {
                    entries = readEntries(ctx, mf);
                } catch (IOException e) {
                    failed++;
                    notes.append("  · ").append(snap.getName())
                            .append("：清单读不了（").append(e.getMessage()).append("）\n");
                    continue;
                }
                if (entries.isEmpty()) {
                    skipped++;
                    continue;
                }

                try {
                    List<Entry> out = new ArrayList<>(entries.size());
                    synchronized (Cas.BULK_LOCK) {
                        boolean bad = false;
                        for (Entry e : entries) {
                            File f = new File(filesRoot, e.rel);
                            if (!f.isFile() || f.length() != e.size) {
                                bad = true;
                                notes.append("  · ").append(snap.getName())
                                        .append("：").append(e.rel).append(" 缺失或大小不符，整份跳过\n");
                                break;
                            }
                            MessageDigest md5 = MessageDigest.getInstance("MD5");
                            Cas.Stored st = pool.put(f, md5);
                            if (!Cas.hex(md5.digest()).equals(e.hash)) {
                                bad = true;
                                notes.append("  · ").append(snap.getName())
                                        .append("：").append(e.rel).append(" md5 不符，整份跳过\n");
                                break;
                            }
                            out.add(new Entry(st.sha, e.size, e.rel));
                        }
                        if (bad) {
                            skipped++;
                            continue;                 // 对象可能已在池里 ⇒ 交给 GC
                        }
                        Snapshot v2 = new Snapshot();
                        v2.slot = ss.slot;
                        v2.created = ss.created;
                        v2.label = ss.label;
                        v2.source = ss.source;
                        v2.count = out.size();
                        v2.bytes = 0;
                        for (Entry e : out) v2.bytes += e.size;
                        v2.version = 2;
                        // ③ 原子替换清单（内部 tmp + rename）—— 这一步成功即新格式可用
                        writeManifestTo(mf, v2, out);
                    }
                    long raw = treeSize(filesRoot);
                    Data.deleteTree(filesRoot);        // ④ 最后才删原件
                    done++;
                    freedOrRaw += raw;
                    logical += ss.bytes;
                    Log.i(TAG, "cas migrate ok: " + sd.getName() + "/" + snap.getName()
                            + " files=" + out.size() + " raw=" + raw);
                } catch (Throwable t) {
                    failed++;
                    Log.w(TAG, "cas migrate failed: " + snap, t);
                    notes.append("  · ").append(snap.getName()).append("：")
                            .append(t.getMessage() == null ? String.valueOf(t) : t.getMessage())
                            .append('\n');
                }
            }
        }
        if (done == 0 && skipped == 0 && failed == 0) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("CAS 迁移：成功 ").append(done).append(" 份");
        if (skipped > 0) sb.append("、跳过 ").append(skipped).append(" 份");
        if (failed > 0) sb.append("、失败 ").append(failed).append(" 份");
        sb.append('\n').append("逻辑内容：").append(Util.formatSize(logical))
                .append("（原样拷贝占 ").append(Util.formatSize(freedOrRaw))
                .append("，现在进共享对象池）\n");
        if (notes.length() > 0) sb.append(notes);
        Log.i(TAG, "cas migrate done=" + done + " skipped=" + skipped + " failed=" + failed);
        return sb.toString();
    }

    private static final AtomicBoolean MIGRATE_BUSY = new AtomicBoolean(false);

    /**
     * 启动器打开时后台做一次「有没有旧格式备份要转」的自动维护。**静默**：
     * 结果只写 `hub/report-cas-migrate.txt`，不弹任何东西 —— 它是维护动作，
     * 不是用户发起的操作（口径 2026-10-01：旧备份自动处理）。
     *
     * ★ 为什么不在 {@link #list} 里做：`list` 是**读**路径，**读动作不许改状态**
     *   （F6 的教训：`savesDirOf()` 内部 `mkdirs` 让"看一眼"造出了空目录）。
     *   所以迁移是一个显式的、幂等的入口清扫，与 `.msav.part` 的回收同一套纪律。
     *
     * ★ 为什么要去重：迁移要读几百 MB。进程内只试一次；下次打开启动器再试
     *   （{@link #hasLegacy} 只读文件头，几毫秒）。
     */
    public static void autoMigrateAsync(final Context ctx) {
        if (ctx == null) return;
        if (!MIGRATE_BUSY.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 先等自动备份收尾：迁移会持批量写锁，让正在落盘的备份先写完更省事
                    AutoBackup.awaitIdle(15000);
                    if (!hasLegacy(app)) return;
                    String rep = migrateLegacy(app);
                    if (rep == null) return;
                    Util.atomicWriteText(new File(Data.hubDir(app), "report-cas-migrate.txt"),
                            rep + "时刻: " + System.currentTimeMillis() + "\n");
                } catch (Throwable e) {
                    Log.w(TAG, "cas auto-migrate failed: " + e);
                } finally {
                    MIGRATE_BUSY.set(false);
                }
            }
        }, "mdt-cas-migrate");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static boolean gameAliveOn(Context ctx, String slot) {
        return slot.equals(Data.currentSlot(ctx)) && Data.gameAlive(ctx);
    }

    private static void writeManifest(File snapDir, Snapshot ss, List<Entry> entries)
            throws IOException {
        writeManifestTo(new File(snapDir, MANIFEST), ss, entries);
    }

    private static void writeManifestTo(File file, Snapshot ss, List<Entry> entries)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(MARK).append('\n');
        sb.append("slot=").append(ss.slot).append('\n');
        sb.append("created=").append(ss.created).append('\n');
        sb.append("label=").append(ss.label).append('\n');
        sb.append("source=").append(ss.source).append('\n');
        sb.append("count=").append(ss.count).append('\n');
        sb.append("bytes=").append(ss.bytes).append('\n');
        sb.append(SEP).append('\n');
        for (Entry e : entries) {
            sb.append(e.hash).append('\t').append(e.size).append('\t').append(e.rel).append('\n');
        }
        Util.atomicWriteText(file, sb.toString());
    }

    /** 读清单头（不含文件表）；不是本格式（v1/v2 之外的）返回 null */
    private static Snapshot readManifest(File manifest) {
        try {
            String text = Util.readText(manifest);
            if (text == null) return null;
            int version;
            if (text.startsWith(MARK2)) version = 2;
            else if (text.startsWith(MARK1)) version = 1;
            else return null;
            Snapshot ss = new Snapshot();
            ss.version = version;
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    if (SEP.equals(line)) break;
                    continue;
                }
                String k = line.substring(0, eq);
                String v = line.substring(eq + 1);
                if ("slot".equals(k)) ss.slot = v;
                else if ("created".equals(k)) ss.created = parseLong(v);
                else if ("label".equals(k)) ss.label = v;
                else if ("source".equals(k)) ss.source = v;
                else if ("count".equals(k)) ss.count = (int) parseLong(v);
                else if ("bytes".equals(k)) ss.bytes = parseLong(v);
            }
            if (ss.created == 0) {
                // 兜底：老快照没有 created 就拿目录名当时间
                return null;
            }
            return ss;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读文件表（v1 的 hash 列是 md5，v2 是 sha256 —— 由清单头区分，这里只搬字节） */
    private static List<Entry> readEntries(Context ctx, File manifest) throws IOException {
        List<Entry> out = new ArrayList<>();
        String text = Util.readText(manifest);
        if (text == null || !(text.startsWith(MARK2) || text.startsWith(MARK1))) {
            throw new IOException(ctx.getString(R.string.backup_err_not_manifest));
        }
        boolean inTable = false;
        for (String line : text.split("\n")) {
            if (!inTable) {
                if (SEP.equals(line)) inTable = true;
                continue;
            }
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            out.add(new Entry(p[0], parseLong(p[1]), p[2]));
        }
        return out;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    /**
     * 只读文件开头若干字节（判格式用）。
     * ★ 为什么专门写一个：{@link #hasLegacy} 每次进存档页都要调，而 manifest 有
     *   上千行（~100 KB）⇒ 读全会把一次页面刷新变成几 MB I/O。
     */
    private static String readHead(File f) {
        if (!f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[32];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String stamp(long ms) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(ms));
    }

    /**
     * 递归收集 `f` 树下**全部文件**的相对路径（相对槽根 `base`，用 `/` 分隔）。
     *
     * ★ 目录本身**不记条目**（CAS 清单只有"内容 → 路径"一种记录）——
     *   空目录由恢复时写文件的 `mkdirs` 顺带补出来，所以没有信息损失。
     *   ⚠️ 副作用是"槽里的空目录不进备份、恢复后也不会回来"，这是**已知且有意**的
     *   （空目录没有用户数据；且"空壳不算内容"这条纪律见 {@link Data#contentRoots}）。
     *
     * ⚠️ 遍历里必须过滤 {@link Data#contentSkipped} —— 否则 `saves/x.msav.part`
     *   这种**写一半**的文件会被当成正式内容存进对象池（F19 之前就是这样）。
     */
    private static void walk(File base, File f, List<String> out) {
        if (Data.contentSkipped(f)) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs == null) return;
            for (File c : fs) walk(base, c, out);
        } else {
            out.add(Util.relOf(base, f));
        }
    }

    /** 目录树总字节（算不出来跳过该项，绝不在异常里静默返回错值） */
    private static long treeSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        File[] fs = f.listFiles();
        if (fs == null) {
            Log.w(TAG, "treeSize 读不到目录：" + f.getAbsolutePath());
            return 0L;
        }
        long n = 0L;
        for (File c : fs) n += treeSize(c);
        return n;
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdir 失败：" + dst);
            File[] fs = src.listFiles();
            if (fs == null) return;
            for (File f : fs) copyRecursive(f, new File(dst, f.getName()));
            return;
        }
        File p = dst.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) throw new IOException("mkdir 失败：" + p);
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static String join(String[] a, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
