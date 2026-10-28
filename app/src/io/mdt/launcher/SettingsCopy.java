package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 7.3c：跨槽复制 {@code settings.bin}（**全黑名单 + 槽导入三档**）。
 *
 * <p>黑名单只放**已核实语义**的键：官方进度 / 身份 / 遥测，以及游戏自己写的
 * {@code mod-*-failed}。模组自定义键**不按前缀或名字过滤**，全搬 —— 见
 * {@code docs/plans/2026-10-28-7.3c-跨槽设置-验证.md} 的定案。
 *
 * <p>三档沿用 {@link SlotWrite}：
 * <ul>
 *   <li>{@link SlotWrite#UPDATE}：两边都有 → 源覆盖；源独有 → 追加；目标独有 → 保留；</li>
 *   <li>{@link SlotWrite#KEEP_OLD}：两边都有 → 目标保留；只追加源独有；</li>
 *   <li>{@link SlotWrite#REPLACE}：只清目标<b>设置范围</b>（非黑名单键），再写源；
 *       目标黑名单键保留，源黑名单键忽略。</li>
 * </ul>
 *
 * <p>⚠️ 覆盖不是清空整个 {@code settings.bin}：目标进度 / 身份 / 遥测必须留下，
 * 否则目标槽会丢“继续上次”的落点。
 *
 * <p>本类**不碰 UI / 不弹窗**；只读、算计划、调 {@link SettingsBin#applyValues}
 * （备份 + 原子写 + 读回自检 + 失败还原都在那一处）。
 */
final class SettingsCopy {

    private SettingsCopy() {}

    /** 一次复制的计划；{@code changes} 里值为 null 表示删除。 */
    static final class Plan {
        final Map<String, Object> changes;
        final int sourceKeys;
        final int targetKeys;
        final int writeCount;
        final int deleteCount;
        final int skipCount;
        File sourceFile;
        File targetFile;
        Set<String> sourceVersions = new TreeSet<String>();
        Set<String> targetVersions = new TreeSet<String>();
        boolean versionUnknown;
        boolean versionConflict;

        Plan(Map<String, Object> changes, int sourceKeys, int targetKeys,
             int writeCount, int deleteCount, int skipCount) {
            this.changes = changes;
            this.sourceKeys = sourceKeys;
            this.targetKeys = targetKeys;
            this.writeCount = writeCount;
            this.deleteCount = deleteCount;
            this.skipCount = skipCount;
        }

        boolean empty() {
            return changes.isEmpty();
        }
    }

    /**
     * 已核实语义的黑名单。**不要**往这里加 `*-next-id` / `posthog` / `tokens.*` /
     * `overlayUI.玩家数据库*` 之类模组键规则 —— 那是在猜模组语义，定案明确禁止。
     */
    static boolean denied(String key) {
        if (key == null || key.isEmpty()) return true;
        if (key.equals("lastBuild") || key.equals("lastBuildString")
                || key.equals("lastplanet") || key.equals("last-sector-save")
                || key.equals("turn") || key.equals("campaignselect")
                || key.equals("uuid") || key.equals("MetricCollector.lastPost")
                || key.equals("mu-last-check-at") || key.equals("joininfo")) {
            return true;
        }
        if (key.endsWith("-last-sector") || key.endsWith("-campaign-stats")) return true;
        if (key.startsWith("save-sector-")) return true;
        // 游戏 mod loader 写的每次启动诊断，不是模组定义的键；搬过去可能让目标误跳过模组。
        return key.startsWith("mod-") && key.endsWith("-failed");
    }

    /** 从两个已读出的表算计划（纯函数，自检直接喂）。 */
    static Plan plan(SettingsBin.Values source, SettingsBin.Values target, int mode) {
        int m = SlotWrite.sane(mode);
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        Set<String> skipped = new LinkedHashSet<String>();
        int write = 0;
        int delete = 0;

        for (Map.Entry<String, Object> e : source.all().entrySet()) {
            String key = e.getKey();
            if (denied(key)) {
                skipped.add(key);
                continue;
            }
            if (m == SlotWrite.KEEP_OLD && target.all().containsKey(key)) continue;
            changes.put(key, e.getValue());
            write++;
        }

        if (m == SlotWrite.REPLACE) {
            for (String key : target.all().keySet()) {
                if (denied(key)) {
                    skipped.add(key);
                    continue;
                }
                if (changes.containsKey(key)) continue;
                changes.put(key, null);       // 目标独有的设置键 → 删除
                delete++;
            }
        }

        return new Plan(changes, source.size(), target.size(), write, delete, skipped.size());
    }

    /**
     * 读两个槽的 settings.bin 并算计划；同时记下版本集。
     *
     * <p>版本门是**软**的：两边都有版本且不相等 ⇒ {@link Plan#versionConflict}；
     * 有一边没有绑定版本 ⇒ {@link Plan#versionUnknown}（UI 警告后仍可继续）。
     * 这样未分配版本的槽也能用，但已知不同版本会被拦住。
     */
    static Plan plan(Context ctx, String sourceSlot, String targetSlot, int mode) throws IOException {
        File sf = Mods.settingsFileOf(ctx, sourceSlot);
        File tf = Mods.settingsFileOf(ctx, targetSlot);
        if (sf == null || !sf.isFile()) {
            throw new IOException("source settings.bin missing");
        }
        SettingsBin.Values src = SettingsBin.read(sf);
        SettingsBin.Values tgt = (tf != null && tf.isFile())
                ? SettingsBin.read(tf)
                : new SettingsBin.Values(new LinkedHashMap<String, Object>(), false, 0);

        Plan p = plan(src, tgt, mode);
        p.sourceFile = sf;
        p.targetFile = tf;
        p.sourceVersions = versionsOfSlot(ctx, sourceSlot);
        p.targetVersions = versionsOfSlot(ctx, targetSlot);
        boolean bothKnown = !p.sourceVersions.isEmpty() && !p.targetVersions.isEmpty();
        p.versionConflict = bothKnown && !p.sourceVersions.equals(p.targetVersions);
        p.versionUnknown = p.sourceVersions.isEmpty() || p.targetVersions.isEmpty();
        return p;
    }

    /** 这个槽目前绑了哪些版本 key（没有绑定就是空集）。 */
    static Set<String> versionsOfSlot(Context ctx, String slot) {
        TreeSet<String> out = new TreeSet<String>();
        try {
            for (Versions.Entry e : Versions.scanAll(ctx)) {
                if (slot != null && slot.equals(Versions.slotFor(e))) out.add(e.key());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 写目标；空计划返回 null（调用方报“没东西可搬”，不要当成失败）。 */
    static SettingsBin.Result apply(Context ctx, String targetSlot, Plan plan) {
        if (plan == null || plan.empty()) return null;
        return SettingsBin.applyValues(plan.targetFile,
                Mods.settingsBackupDirOf(ctx, targetSlot), plan.changes);
    }
}
