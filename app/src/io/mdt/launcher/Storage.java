package io.mdt.launcher;

import android.content.Context;

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
 * ⚠️ 只读：本类**不写任何东西**（不回收、不迁移、不清理）。删除类动作仍走各自的门禁。
 */
final class Storage {

    private Storage() {}

    /** 一次统计的全部数字（字段 public：自检要能手工造一份） */
    static final class Report {
        /** 启动器数据（hub）总占用 —— 递归量出来的真实数字 */
        public long hubBytes;
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
        java.io.File hub = Data.hubDir(ctx);
        r.hubBytes = hub == null ? 0 : Data.sizeTree(hub);
        return r;
    }

    /**
     * 组装给用户看的正文（**纯函数**：不碰文件、不改入参）。
     *
     * ★ 分三层说：总占用 → 分项（各槽 / 备份 / 中转站）→ 能清理的残留。
     * ★ 术语纪律：第一层不出现"对象池 / CAS / 去重"这些词，只说"备份之间共用内容"；
     *   依据（对象数、暂时没人用的部分）用白话跟在后面同一段里。
     */
    static String text(Context c, Report r) {
        StringBuilder sb = new StringBuilder();
        if (r == null) return "";
        sb.append(Trans.get(c, R.string.storage_total_fmt, Util.formatSize(r.hubBytes))).append('\n');

        // ① 各槽内容
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

        // ② 备份（去重效果就靠这一行说清）
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

        // ③ 中转站（按类型分，为 0 的类型不出现 —— 空行比"没有"更省事）
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

        // ④ 可以清理的残留（体检数字，原来只在设置页那一行里体现"已开/已关"）
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
