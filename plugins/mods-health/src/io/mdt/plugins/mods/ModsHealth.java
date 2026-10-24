package io.mdt.plugins.mods;

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
import io.mdt.plugins.mods.core.Conflicts;

/**
 * 模组体检插件（官方，第 229 轮；v25 起叫 mods-health）。
 *
 * <p>宿主对模组页的**每一项**调用一次 {@link #analyze}；本插件给的是**整槽**结论，
 * 所以只在「这一页的第一个模组」那一次调用里返回内容，其余返回空表 —— 否则同一份结论
 * 会在卡片里重复 N 遍。挂到某一项上的结论（{@link Finding#anchor}）例外：宿主画在那一行上。
 *
 * <p>★ 判据在 {@link Conflicts}（纯 Java、可 PC 侧自检）；这里只做三件事：
 * 把 {@link ItemInfo} 映射成判据要的事实、把结果拼成资源文案、把结果**按 mods/ 签名缓存**
 * （原来那行是"按需"跑的，搬成 analyzer 后每次进页面都会跑 ⇒ 不缓存就每次重扫几 MB 的 dex）。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（与宿主同一个门禁口径）。
 */
public final class ModsHealth {

    /** analyzer 是 v24 的扩展点；v25 起还要用 {@code Finding.anchor}。 */
    public static int api() {
        return 25;
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
            api.log("mods-health: analyze failed: " + t);
            return Collections.emptyList();
        }
    }

    /**
     * **v27 P4**：详情页那「六道加载门」。判据全在这边，宿主只负责画。
     *
     * <p>★ 每条的 `label` 都**跟着状态取**（通过 / 不通过两条文案）——
     * 「❌ + 肯定句」读不通（用户 2026-10-21：「怎么还有叉号后面跟肯定词」）。
     * 通过时 {@code note} 一律为空（只画一行 ✅），不通过时必须给原因。
     */
    public static java.util.List<io.mdt.launcher.api.Gate> gates(ItemInfo item, PluginApi api) {
        java.util.List<io.mdt.launcher.api.Gate> out = new java.util.ArrayList<io.mdt.launcher.api.Gate>();
        if (item == null || api == null) return out;
        if (!ItemInfo.KIND_MODS.equals(item.kind)) return out;
        if (extra(item, "internalName").isEmpty()) return out;      // 坏包 ⇒ 宿主自己的
        try {
            Context c = api.context();
            // ① 安卓到底能不能加载它
            boolean canLoad = !"true".equals(extra(item, "willFailJavaLoad"));
            out.add(new io.mdt.launcher.api.Gate(
                    c.getString(canLoad ? R.string.cf_gate_load : R.string.cf_gate_load_bad),
                    canLoad, canLoad ? "" : c.getString(R.string.cf_gate_load_note)));

            // ② 游戏里开没开
            boolean on = !"false".equals(extra(item, "enabled"));
            out.add(new io.mdt.launcher.api.Gate(
                    c.getString(on ? R.string.cf_gate_on : R.string.cf_gate_on_bad),
                    on, on ? "" : c.getString(R.string.cf_gate_on_off)));

            // ③ 游戏版本够不够
            String v = extra(item, "minGameVersion");
            int build = intExtra(item, "targetBuild");
            int rev = intExtra(item, "targetRevision");
            boolean verOk = Conflicts.isAtLeast(build, rev, v);
            String cur = build == 0 ? c.getString(R.string.cf_gate_no_version)
                    : (build + (rev == 0 ? "" : "." + rev));
            out.add(new io.mdt.launcher.api.Gate(
                    c.getString(verOk ? R.string.cf_gate_ver : R.string.cf_gate_ver_bad),
                    verOk, verOk ? "" : c.getString(R.string.cf_gate_ver_note_fmt, v, cur)));

            // ④ 门槛（v8 起才有）
            int need = build >= intExtra(item, "minJavaMajor")
                    ? ("true".equals(extra(item, "java"))
                        ? intExtra(item, "minJavaMajor") : intExtra(item, "minModMajor")) : 0;
            int minMajor = Conflicts.minMajorOf(v);
            boolean majorOk = need <= 0 || minMajor >= need
                    || "true".equals(extra(item, "legacyCompatible"));
            out.add(new io.mdt.launcher.api.Gate(
                    c.getString(majorOk ? R.string.cf_gate_minver : R.string.cf_gate_minver_bad),
                    majorOk, majorOk ? "" : (minMajor <= 0
                            ? c.getString(R.string.cf_gate_minver_none)
                            : c.getString(R.string.cf_gate_minver_old_fmt,
                                    String.valueOf(minMajor), String.valueOf(need)))));

            // ⑤ 黑名单
            boolean blOk = !"true".equals(extra(item, "blacklisted"));
            out.add(new io.mdt.launcher.api.Gate(
                    c.getString(blOk ? R.string.cf_gate_blacklist : R.string.cf_gate_blacklist_bad),
                    blOk, blOk ? "" : c.getString(R.string.cf_gate_blacklist_note_fmt,
                            extra(item, "name") + ":" + extra(item, "version"))));

            // ⑥ 上次启动崩到没崩到整槽跳过
            boolean skipping = api.modsSkipped();
            boolean failed = "true".equals(extra(item, "failed"));
            boolean lastOk = !failed && !skipping;
            out.add(new io.mdt.launcher.api.Gate(
                    c.getString(lastOk ? R.string.cf_gate_last : R.string.cf_gate_last_bad),
                    lastOk, lastOk ? "" : c.getString(skipping
                            ? R.string.cf_gate_last_skip_again : R.string.cf_gate_last_retry)));
            return out;
        } catch (Throwable t) {
            return new java.util.ArrayList<io.mdt.launcher.api.Gate>();
        }
    }

    /**
     * **v26**：这一行"是什么状态"（宿主拿它画徽标 / 筛选 / 详情 —— 与结论同源）。
     *
     * <p>返回**固定词表**里的一个 token，宿主只做映射、不解释语义：
     * {@code enabled} / {@code disabled} / {@code unsupported}（版本或黑名单）/ {@code missing-dep} /
     * {@code incomplete-dep} / {@code circular-dep} / {@code content-error}。返回 {@code null} ⇒
     * 宿主回落到它自己的判定（读不出说明文件的坏包就走这条路 —— 那是宿主的文件级错误）。
     *
     * <p>★ 判定顺序**照抄**宿主的 {@code Mods.stateOf}：依赖优先、其次版本/黑名单、最后才是"关着"。
     * 顺序一变，同一个包在两边就会给出不同状态（真机上就是"徽标说 A、行上写 B"）。
     */
    public static String state(ItemInfo item, PluginApi api) {
        if (item == null || api == null) return null;
        if (!ItemInfo.KIND_MODS.equals(item.kind)) return null;
        try {
            if (extra(item, "internalName").isEmpty()) return null;    // 坏包 ⇒ 宿主管
            String dep = extra(item, "depState");
            if ("MISSING_DEPENDENCIES".equals(dep)) return "missing-dep";
            if ("INCOMPLETE_DEPENDENCIES".equals(dep)) return "incomplete-dep";
            if ("CIRCULAR_DEPENDENCIES".equals(dep)) return "circular-dep";
            if (dep.startsWith("CONTENT")) return "content-error";
            String v = extra(item, "minGameVersion");
            if (!Conflicts.isAtLeast(intExtra(item, "targetBuild"),
                    intExtra(item, "targetRevision"), v)) {
                return "unsupported";
            }
            int floor = intExtra(item, "minJavaMajor");
            int need = intExtra(item, "targetBuild") >= floor
                    ? ("true".equals(extra(item, "java")) ? floor : intExtra(item, "minModMajor"))
                    : 0;
            if (need > 0 && Conflicts.minMajorOf(v) < need
                    && !"true".equals(extra(item, "legacyCompatible"))) {
                return "unsupported";
            }
            if ("true".equals(extra(item, "blacklisted"))) return "unsupported";
            if ("false".equals(extra(item, "enabled"))) return "disabled";
            return "enabled";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一次扫描的快照（筛选 / 排序按 path 查，不依赖回调顺序）。 */
    static final class Snapshot {
        final List<Conflicts.Mod> mods;
        final Conflicts.Result r;
        final String ownerPath;

        Snapshot(List<Conflicts.Mod> mods, Conflicts.Result r, String ownerPath) {
            this.mods = mods;
            this.r = r;
            this.ownerPath = ownerPath;
        }

        int indexOf(String path) {
            if (path == null || mods == null) return -1;
            for (int i = 0; i < mods.size(); i++) {
                Conflicts.Mod m = mods.get(i);
                if (m != null && path.equals(m.path)) return i;
            }
            return -1;
        }

        /** 这个模组参与了任何一条结构结论（内置）。 */
        boolean isInvolved(int i) {
            if (r == null || i < 0) return false;
            for (Conflicts.Pair p : r.contained) {
                if (p.a == i || p.b == i) return true;
            }
            return false;
        }
    }

    /**
     * 取当前槽的扫描快照（按 `mods/` 签名缓存；变了才重扫）。
     *
     * <p>v25：宿主注入的筛选 / 排序（{@code Views}）也走这一份缓存 ——
     * 用户在菜单里点一下，宿主会在后台线程调这里，别每次都重扫 dex。
     */
    static Snapshot snapshot(PluginApi api) {
        if (api == null) return null;
        Context c = api.context();
        String sig = signature(api, c);
        synchronized (LOCK) {
            if (cResult == null || !sig.equals(cSig)) {
                List<ItemInfo> all = api.contents(ItemInfo.KIND_MODS);
                long t0 = System.currentTimeMillis();
                cMods = toMods(all);
                cResult = Conflicts.scan(cMods);
                cOwner = all.isEmpty() ? null : all.get(0).path;
                cSig = sig;
                api.log("mods-health: " + cResult.javaMods + " java mods, "
                        + cResult.scannedBytes + " dex bytes, "
                        + (System.currentTimeMillis() - t0) + " ms");
            }
            return new Snapshot(cMods, cResult, cOwner);
        }
    }

    private static List<Finding> analyze0(ItemInfo item, PluginApi api) {
        Context c = api.context();
        Snapshot s = snapshot(api);
        if (s == null || s.r == null || s.ownerPath == null || item.path == null
                || !item.path.equals(s.ownerPath)) {
            if (++cCalls <= 4) {
                api.log("mods-health: skip#" + cCalls + " item=" + item.path
                        + " owner=" + (s == null ? null : s.ownerPath));
            }
            return Collections.emptyList();
        }
        List<Finding> fs = findings(c, api, s.r, s.mods);
        api.log("mods-health: emit " + fs.size() + " findings (item=" + item.path
                + ", issues=" + s.r.issues.size() + ")");
        return fs;
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

    private static List<Conflicts.Mod> toMods(List<ItemInfo> items) {
        List<Conflicts.Mod> out = new ArrayList<Conflicts.Mod>();
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
            // v25：结构事实（宿主只给"是什么"，"有没有问题"由本插件判）
            m.java = "true".equals(extra(it, "java"));
            m.hasClassesDex = "true".equals(extra(it, "hasClassesDex"));
            m.hasScripts = "true".equals(extra(it, "hasScripts"));
            m.hasMainJs = "true".equals(extra(it, "hasMainJs"));
            m.jsCount = intExtra(it, "jsCount");
            // v25（P3）：宿主算出来的依赖状态 / 重名 / 黑名单（判据与文案在这边）
            m.depState = extra(it, "depState");
            m.duplicated = "true".equals(extra(it, "duplicated"));
            m.blacklisted = "true".equals(extra(it, "blacklisted"));
            m.enabled = !"false".equals(extra(it, "enabled"));
            // v25（P3b）：版本判断的事实（比较在插件里做）
            m.minGameVersion = extra(it, "minGameVersion");
            m.legacyCompatible = "true".equals(extra(it, "legacyCompatible"));
            m.targetBuild = intExtra(it, "targetBuild");
            m.targetRevision = intExtra(it, "targetRevision");
            if (intExtra(it, "minJavaMajor") > 0) m.minJavaMajor = intExtra(it, "minJavaMajor");
            if (intExtra(it, "minModMajor") > 0) m.minModMajor = intExtra(it, "minModMajor");
            m.dependencies.clear();
            for (int i = 0; ; i++) {
                String d = extra(it, "dep." + i);
                if (d.isEmpty()) break;
                m.dependencies.add(d);
            }
            out.add(m);
        }
        return out;
    }

    private static String extra(ItemInfo it, String key) {
        String v = it.extra == null ? null : it.extra.get(key);
        return v == null ? "" : v;
    }

    private static int intExtra(ItemInfo it, String key) {
        try {
            return Integer.parseInt(extra(it, key).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 当前界面语言（缓存的结论里有文案，换语言要重算）。minSdk 26 ⇒ 走 {@code getLocales()}。 */
    private static Locale localeOf(Context c) {
        android.content.res.Configuration cfg = c.getResources().getConfiguration();
        android.os.LocaleList list = cfg.getLocales();
        return list == null || list.isEmpty() ? Locale.getDefault() : list.get(0);
    }

    // ---- v25：注入到「筛选与排序」里的两项（宿主按 slot=filter/sort 调这两个静态方法）----

    /**
     * 筛选：只留下参与冲突（内置 / 被提及）的模组。
     *
     * <p>★ 判据与卡片里的结论**同源**（同一份按签名缓存的 {@link Snapshot}）——
     * 别另写一套，否则"筛出来的"和"卡片里说的"会漂。
     * ⚠️ 入口类必须是**声明了 `api()` 的那个类**（宿主 {@code PluginHost.open} 要拿它判版本），
     * 所以这两个方法放在这里，而不是单独一个 `Views` 类（第 229 轮踩过：`NoSuchMethodException: Views.api`）。
     */
    public static boolean accept(ItemInfo item, PluginApi api) {
        if (item == null || api == null || item.path == null) return false;
        try {
            Snapshot s = snapshot(api);
            if (s == null) return false;
            int i = s.indexOf(item.path);
            return i >= 0 && s.isInvolved(i);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 排序：参与冲突的排前面（同一档内按标题，保证稳定、可解释）。 */
    public static int compare(ItemInfo a, ItemInfo b, PluginApi api) {
        int ra = risk(a, api);
        int rb = risk(b, api);
        if (ra != rb) return ra < rb ? -1 : 1;
        String ta = a == null || a.title == null ? "" : a.title;
        String tb = b == null || b.title == null ? "" : b.title;
        return ta.compareToIgnoreCase(tb);
    }

    private static int risk(ItemInfo it, PluginApi api) {
        try {
            if (it == null || api == null || it.path == null) return 1;
            Snapshot s = snapshot(api);
            if (s == null) return 1;
            int i = s.indexOf(it.path);
            return i >= 0 && s.isInvolved(i) ? 0 : 1;
        } catch (Throwable t) {
            return 1;
        }
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
                                          List<Conflicts.Mod> mods) {
        List<Finding> out = new ArrayList<Finding>();
        // 日志每次进页面都重读（很便宜）：它每次启动游戏都会变，不该被 dex 扫描那份缓存冻住
        Conflicts.Log log = Conflicts.readLog(new File(api.slotDir(), "last_log.txt"), mods);

        for (Conflicts.Pair p : r.contained) {
            // v25：挂到**容器那一行**上（"这个包里已经带了别人"是这个包自己的事）
            out.add(new Finding(
                    c.getString(R.string.cf_contained_title_fmt, title(mods, p.a), title(mods, p.b)),
                    c.getString(R.string.cf_contained_detail), Finding.ERROR,
                    mods.get(p.a).path, mods.get(p.b).path).anchor(mods.get(p.a).path));
        }
        if (!log.lines.isEmpty()) {
            out.add(new Finding(c.getString(R.string.cf_log_title_fmt, log.lines.size()),
                    "", Finding.INFO));
        }
        // v27：**整槽跳过**（槽级，落点 = 摘要卡）—— 上次启动没跑完，游戏下次会把整槽模组关掉。
        try {
            if (api.modsSkipped()) {
                out.add(new Finding(c.getString(R.string.cf_slot_skip_title),
                        c.getString(R.string.cf_slot_skip_detail), Finding.WARN).summary());
            }
        } catch (Throwable ignored) {
        }

        // v27：**孤儿开关**（槽级）—— settings 里还有 `mod-<名字>-enabled`，模组已经不在槽里了。
        // 判据在 core（`Conflicts.orphanKeys`，PC 自检盯着）；这里只负责取事实 + 拼文案。
        try {
            java.util.List<String> keys = new ArrayList<String>();
            java.util.Map<String, Object> gs = api.gameSettings();
            if (gs != null) for (String k : gs.keySet()) keys.add(k);
            java.util.List<String> names = new ArrayList<String>();
            for (ItemInfo it : api.contents(ItemInfo.KIND_MODS)) {
                String nm = extra(it, "internalName");
                if (!nm.isEmpty()) names.add(nm);
            }
            java.util.List<String> orphans = Conflicts.orphanKeys(keys, names);
            if (!orphans.isEmpty()) {
                out.add(new Finding(c.getString(R.string.cf_orphan_title_fmt, orphans.size()),
                        c.getString(R.string.cf_orphan_detail), Finding.WARN));
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            // 一条**冲突/日志**结论都没有时才说"没冲突" —— 顺便把"扫了多少"只在这里说一次
            if (r.javaMods > 0) {
                out.add(new Finding(c.getString(R.string.cf_none),
                        c.getString(R.string.cf_none_detail_fmt, r.javaMods, size(r.scannedBytes)),
                        Finding.INFO));
            } else {
                out.add(new Finding(c.getString(R.string.cf_no_code), "", Finding.INFO));
            }
        }
        // ② 结构（P2 从宿主搬来的两条：会不会加载 / 脚本会不会跑）—— 判据在 core，这里只拼文案
        for (Conflicts.Issue is : r.issues) {
            if (is == null || is.index < 0 || is.index >= mods.size()) continue;
            String path = mods.get(is.index).path;
            if (path == null || path.isEmpty()) continue;
            if (is.kind == Conflicts.Issue.WILL_FAIL_JAVA_LOAD) {
                out.add(new Finding(c.getString(R.string.cf_willfail_title),
                        c.getString(R.string.cf_willfail_detail), Finding.ERROR).anchor(path));
            } else if (is.kind == Conflicts.Issue.NO_MAIN_SCRIPT) {
                out.add(new Finding(c.getString(R.string.cf_nomainjs_title_fmt, is.jsCount),
                        c.getString(R.string.cf_nomainjs_detail), Finding.WARN).anchor(path));
            } else if (is.kind == Conflicts.Issue.DEP_MISSING) {
                out.add(new Finding(c.getString(R.string.cf_dep_missing_title_fmt, is.other),
                        c.getString(R.string.cf_dep_missing_detail), Finding.ERROR).anchor(path));
            } else if (is.kind == Conflicts.Issue.DEP_OFF) {
                out.add(new Finding(c.getString(R.string.cf_dep_off_title_fmt, is.other),
                        c.getString(R.string.cf_dep_off_detail), Finding.ERROR).anchor(path));
            } else if (is.kind == Conflicts.Issue.DEP_CIRCLE) {
                out.add(new Finding(c.getString(R.string.cf_dep_circle_title),
                        c.getString(R.string.cf_dep_circle_detail), Finding.ERROR).anchor(path));
            } else if (is.kind == Conflicts.Issue.DUPLICATED) {
                out.add(new Finding(c.getString(R.string.cf_dup_title_fmt, is.other),
                        c.getString(R.string.cf_dup_detail), Finding.WARN).anchor(path));
            } else if (is.kind == Conflicts.Issue.BLACKLISTED) {
                out.add(new Finding(c.getString(R.string.cf_blacklist_title),
                        c.getString(R.string.cf_blacklist_detail), Finding.ERROR).anchor(path));
            } else if (is.kind == Conflicts.Issue.NEEDS_NEWER_GAME) {
                out.add(new Finding(c.getString(R.string.cf_newer_title_fmt, is.other),
                        c.getString(R.string.cf_newer_detail), Finding.ERROR).anchor(path));
            } else if (is.kind == Conflicts.Issue.MIN_MAJOR_LOW) {
                out.add(new Finding(c.getString(R.string.cf_minmajor_title_fmt,
                        String.valueOf(is.jsCount), is.other),
                        c.getString(R.string.cf_minmajor_detail_fmt, is.other),
                        Finding.ERROR).anchor(path));
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
