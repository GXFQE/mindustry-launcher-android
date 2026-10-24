package io.mdt.plugins.conflict;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import io.mdt.launcher.api.Finding;
import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.conflict.core.Conflicts;

/**
 * 冲突检查插件（官方，第 229 轮）：把宿主原来那行「查模组冲突」搬成 <b>analyzer</b>。
 *
 * <p>宿主对模组页的**每一项**调用一次 {@link #analyze}；本插件给的是**整槽**结论，
 * 所以只在「这一页的第一个模组」那一次调用里返回内容，其余返回空表 —— 否则同一份结论
 * 会在卡片里重复 N 遍。
 *
 * <p>★ 为什么"整槽"结论还要按项回调：analyzer 的契约就是逐项（{@code Finding} 没有
 * item 字段，宿主把结论画成**一张卡**）。第一项 = 宿主的 {@code pageItems()} 第一项，
 * 与本插件 {@code api.contents(mods).get(0)} 同源同序 —— 判据不是"回调顺序"。
 *
 * <p>★ 三层判据在 {@link Conflicts}（纯 Java、可 PC 侧自检）；这里只做三件事：
 * 把 {@link ItemInfo} 映射成判据要的事实、把结果拼成资源文案、把结果**按 mods/ 签名缓存**
 * （原来那行是"按需"跑的，搬成 analyzer 后每次进页面都会跑 ⇒ 不缓存就每次重扫几 MB 的 dex）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（与宿主同一个门禁口径）。
 */
public final class ConflictPlugin {

    /** analyzer 是 v24 才有的扩展点。 */
    public static int api() {
        return 24;
    }

    private static final Object LOCK = new Object();

    /** `mods/` 的签名（见 {@link #signature}）：变了才重扫。 */
    private static String cSig;
    /** 这一份清单里**第一个**模组的 path（只在它那一次回调里给结论）。 */
    private static String cOwner;
    private static List<Conflicts.Mod> cMods;
    private static Conflicts.Result cResult;
    /** 只为排障：一页里前几次调用的现场（见 analyze0 的 skip 日志）。 */
    private static int cCalls;

    /**
     * analyzer 入口（v24）：宿主逐项调用，返回这一项的结论。
     *
     * <p>本插件返回的是**整槽**结论，见类注释里的"第一项"约定。
     */
    public static List<Finding> analyze(ItemInfo item, PluginApi api) {
        if (item == null || api == null) return Collections.emptyList();
        if (!ItemInfo.KIND_MODS.equals(item.kind)) return Collections.emptyList();
        try {
            return analyze0(item, api);
        } catch (Throwable t) {
            api.log("conflict: analyze failed: " + t);
            return Collections.emptyList();
        }
    }

    private static List<Finding> analyze0(ItemInfo item, PluginApi api) {
        Context c = api.context();
        List<Conflicts.Hook> hooks = hooks(c);
        String sig = signature(api, c);
        synchronized (LOCK) {
            if (cResult == null || !sig.equals(cSig)) {
                List<ItemInfo> all = api.contents(ItemInfo.KIND_MODS);
                long t0 = System.currentTimeMillis();
                cMods = toMods(all);
                cResult = Conflicts.scan(cMods, hooks);
                cOwner = all.isEmpty() ? null : all.get(0).path;
                cSig = sig;
                api.log("conflict: " + cResult.javaMods + " java mods, "
                        + cResult.scannedBytes + " dex bytes, "
                        + (System.currentTimeMillis() - t0) + " ms");
            }
            if (cResult == null || cOwner == null || item.path == null
                    || !item.path.equals(cOwner)) {
                if (++cCalls <= 4) {
                    api.log("conflict: skip#" + cCalls + " item=" + item.path + " owner=" + cOwner);
                }
                return Collections.emptyList();
            }
            List<Finding> fs = findings(c, api, cResult, cMods, hooks);
            api.log("conflict: emit " + fs.size() + " findings (item=" + item.path + ")");
            return fs;
        }
    }

    // ---- 事实映射 -------------------------------------------------------------

    /** {@code mods/} 的一级条目签名（只 stat，**不打开任何包**）：增删改一个包就换一份结果。 */
    private static String signature(PluginApi api, Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(api.slot()).append('|')
                .append(localeOf(c)).append('|');
        File dir = api.modsDir();
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return sb.append('-').toString();
        Arrays.sort(kids, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : kids) {
            sb.append(f.getName()).append(':').append(f.length()).append(':')
                    .append(f.lastModified()).append(';');
        }
        return sb.toString();
    }

    private static List<Conflicts.Mod> toMods(List<ItemInfo> items) {        List<Conflicts.Mod> out = new ArrayList<Conflicts.Mod>();
        if (items == null) return out;
        for (ItemInfo it : items) {
            if (it == null) continue;
            Conflicts.Mod m = new Conflicts.Mod();
            m.path = it.path == null ? "" : it.path;
            m.title = it.title == null ? "" : it.title;
            m.name = extra(it, "name");
            m.internalName = extra(it, "internalName");
            m.description = extra(it, "description");
            m.main = extra(it, "main");
            m.rootPrefix = extra(it, "rootPrefix");
            m.rootDir = extra(it, "rootDir");
            m.directory = it.directory;
            out.add(m);
        }
        return out;
    }

    private static String extra(ItemInfo it, String key) {
        String v = it.extra == null ? null : it.extra.get(key);
        return v == null ? "" : v;
    }

    /** 当前界面语言（缓存的结论里有文案，换语言要重算）。minSdk 26 ⇒ 走 {@code getLocales()}。 */
    private static Locale localeOf(Context c) {
        android.content.res.Configuration cfg = c.getResources().getConfiguration();
        android.os.LocaleList list = cfg.getLocales();
        return list == null || list.isEmpty() ? Locale.getDefault() : list.get(0);
    }

    /**
     * 全局钩子表（**只有一条**，与宿主原来那张表同源：宁可少说，绝不瞎说）。
     * 名字走资源（用户语言包 / 英文界面都能用），类描述符是 dex 里的形态。
     */
    private static List<Conflicts.Hook> hooks(Context c) {
        List<Conflicts.Hook> out = new ArrayList<Conflicts.Hook>();
        out.add(new Conflicts.Hook(c.getString(R.string.cf_hook_logic), "mindustry/logic/LCanvas"));
        return out;
    }

    // ---- 结论 → 文案 -----------------------------------------------------------

    /**
     * 把结论拼成卡片上那几行。
     *
     * <p>🔴 第 229 轮修订（用户：「感觉有点占地方啊」）：**默认每行只占一行** ——
     * 原来每条都带一句解释、外加一行"检查了 N 个"的统计，五条结论就吃掉半屏。
     * ⇒ 现在：① 统计**只在没发现冲突时**出现（发现冲突时，结论本身已经证明扫过了）；
     * ② 解释只留"要用户动手"的那一条（重复实现 ⇒ 建议只留一个）；③ 日志**不再贴原文**
     * （长的依据归运行日志，卡片只给结论 —— 与 §85.16.2 同一条）。
     */
    private static List<Finding> findings(Context c, PluginApi api, Conflicts.Result r,
                                          List<Conflicts.Mod> mods, List<Conflicts.Hook> hooks) {
        List<Finding> out = new ArrayList<Finding>();
        // 日志每次进页面都重读（很便宜）：它每次启动游戏都会变，不该被 dex 扫描那份缓存冻住
        Conflicts.Log log = Conflicts.readLog(new File(api.slotDir(), "last_log.txt"), mods);

        for (Conflicts.Pair p : r.contained) {
            out.add(new Finding(
                    c.getString(R.string.cf_contained_title_fmt, title(mods, p.a), title(mods, p.b)),
                    c.getString(R.string.cf_contained_detail), Finding.ERROR,
                    mods.get(p.a).path, mods.get(p.b).path));
        }
        for (Conflicts.HookHit h : r.sharedHooks) {
            String hook = h.hook >= 0 && h.hook < hooks.size() ? hooks.get(h.hook).name : "";
            out.add(new Finding(
                    c.getString(R.string.cf_shared_title_fmt, joinNames(c, mods, h.mods), hook),
                    "", Finding.WARN));
        }
        for (Conflicts.Pair p : r.mentioned) {
            out.add(new Finding(
                    c.getString(R.string.cf_mentioned_title_fmt, title(mods, p.a), title(mods, p.b)),
                    "", Finding.INFO));
        }
        if (!log.lines.isEmpty()) {
            out.add(new Finding(c.getString(R.string.cf_log_title_fmt, log.lines.size()),
                    "", Finding.INFO));
        }
        if (out.isEmpty()) {
            // 一条结论都没有时才说"没冲突" —— 顺便把"扫了多少"只在这里说一次
            if (r.javaMods > 0) {
                out.add(new Finding(c.getString(R.string.cf_none),
                        c.getString(R.string.cf_none_detail_fmt, r.javaMods, size(r.scannedBytes)),
                        Finding.INFO));
            } else {
                out.add(new Finding(c.getString(R.string.cf_no_code), "", Finding.INFO));
            }
        }
        for (Integer idx : r.dexErrors) {
            out.add(new Finding(c.getString(R.string.cf_note_dex_title_fmt, title(mods, idx.intValue())),
                    "", Finding.WARN));
        }
        if (log.unreadable) {
            out.add(new Finding(c.getString(R.string.cf_note_log), "", Finding.INFO));
        }
        return out;
    }

    private static String joinNames(Context c, List<Conflicts.Mod> mods, List<Integer> idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < idx.size(); i++) {
            if (i > 0) sb.append(c.getString(R.string.cf_join_and));
            sb.append(c.getString(R.string.cf_name_fmt, title(mods, idx.get(i).intValue())));
        }
        return sb.toString();
    }

    private static String title(List<Conflicts.Mod> mods, int i) {
        if (mods == null || i < 0 || i >= mods.size()) return "";
        String t = mods.get(i).title;
        if (t != null && !t.trim().isEmpty()) return t.trim();
        String p = mods.get(i).path;
        if (p == null || p.isEmpty()) return "";
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** 与宿主 {@code Util.formatSize} 同一个口径（1.2 KB / 7.5 MB），别在插件里另造一套。 */
    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format(Locale.US, "%.1f %s", v, units[i]);
    }
}
