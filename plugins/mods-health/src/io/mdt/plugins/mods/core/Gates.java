package io.mdt.plugins.mods.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 详情页「六道加载门」的判据（v27 P4；第 230 轮从入口类搬到这里）。
 *
 * <p>★ 为什么在 core：宿主的 SelfTest 有 20 条断言盯着这六道门，第 229 轮随判据一起删掉；
 * 而门原来写在入口类里（要 Android 的 {@code Context} 取文案）⇒ {@code build.sh check}
 * 根本编不到它。现在这里**只算**「哪道门过 / 不过、用哪条文案 key、要什么参数」，
 * 文案留在插件资源里、入口类只做 key → {@code R.string} 的映射 ⇒ PC 侧能把六道门逐道验。
 *
 * <p>★ 四条判据（都是宿主那些事故攒出来的，改之前先读 {@code ref/29} 与 {@code ref/43}）：
 * <ul>
 *   <li>**每道门单独**不通过一次，其余五道必须仍然通过（判据相互独立、且有分辨力）；</li>
 *   <li>{@code pass == false} 时 {@code noteKey} **必须非空**（「❌ + 没有原因」等于白说）；</li>
 *   <li>通过的标签与不通过的标签必须是**两条不同的 key**（「❌ + 肯定句」读不通，用户 2026-10-21 原话）；</li>
 *   <li>**槽级事实用槽级措辞**（最后一道门）：{@code -failed} 是按项事实，整槽跳过是槽级事实 ——
 *       后者不许写成「它崩了」（§85.16 / §86.7）。</li>
 * </ul>
 *
 * <p>⚠️ 这里**不产生**中文 / 英文：key 名与参数是判据的一部分（可被 {@code tools/Check.java} 钉），
 * 翻译在 {@code res/values{,-zh}/strings.xml}。
 */
public final class Gates {

    /** 门的编号（顺序 = 详情页上的顺序）。 */
    public static final int LOAD = 0, ON = 1, VERSION = 2, MINVER = 3, BLACKLIST = 4, LAST = 5;
    /** 六道（自检里有元断言钉着，少一道就会红）。 */
    public static final int COUNT = 6;

    private Gates() { }

    /**
     * 判据要用的事实 —— **宿主给的东西**（{@code ItemInfo.extra} 的 key 名只在本类的
     * {@link #facts(Map, boolean)} 里出现一次：宿主换名只错一处）。
     */
    public static final class Facts {
        public String name = "";
        public String version = "";
        public String minGameVersion = "";
        /** 声明了 Java（决定门槛用哪一档）。 */
        public boolean java;
        /** 声明了 Java 却没有 {@code classes.dex}（宿主算好的事实）。 */
        public boolean willFailJavaLoad;
        public boolean blacklisted;
        public boolean legacyCompatible;
        /** 读到游戏设置文件了吗（`false` ⇒ 第②道门给的「启用」只是默认值）。 */
        public boolean settingsKnown = true;
        public boolean enabled = true;
        /** 这个模组自己有游戏的 `-failed` 记录（**按项**事实）。 */
        public boolean failed;
        /** 这个槽下次启动会跳过全部模组（`launchid.dat` 在 + `modcrashdisable`）—— **槽级**事实。 */
        public boolean slotSkipping;
        /** `launchid.dat` 在（上次启动没跑完）—— 只用于"通过但值得说一句"。 */
        public boolean launchIdExists;
        public int targetBuild;
        public int targetRevision;
        /** 游戏自己的两个门槛（`Vars.java:53/55`：Java 154 / 脚本·数据 136）。 */
        public int minJavaMajor = 154;
        public int minModMajor = 136;
    }

    /** 一道门的结论：过不过、用哪条文案、原因要什么参数。 */
    public static final class Decision {
        public final int id;
        public final String labelKey;
        public final boolean pass;
        /** 补充说明的 key；空串 = 没有。 */
        public final String noteKey;
        /** `noteKey` 的格式化参数（顺序即 `%1$s` / `%2$s`）。 */
        public final String[] noteArgs;

        Decision(int id, String labelKey, boolean pass, String noteKey, String[] noteArgs) {
            this.id = id;
            this.labelKey = labelKey;
            this.pass = pass;
            this.noteKey = noteKey == null ? "" : noteKey;
            this.noteArgs = noteArgs == null ? new String[0] : noteArgs;
        }
    }

    /**
     * 从宿主给的 {@code extra} 映射事实。
     *
     * <p>默认值的方向是"**不知道就往通过 / 不吓人**那边靠"：缺 {@code settingsKnown} ⇒ 当读到过
     * （老宿主不会显示"这是默认值"的补丁句）、缺 {@code launchIdExists} ⇒ 当没有。
     *
     * @param extra         {@code ItemInfo.extra}（可为 null）
     * @param slotSkipping  宿主 {@code api.modsSkipped()} 的结果
     */
    public static Facts facts(Map<String, String> extra, boolean slotSkipping) {
        Facts f = new Facts();
        if (extra == null) return f;
        f.name = str(extra, "name");
        f.version = str(extra, "version");
        f.minGameVersion = str(extra, "minGameVersion");
        f.java = bool(extra, "java", false);
        f.willFailJavaLoad = bool(extra, "willFailJavaLoad", false);
        f.blacklisted = bool(extra, "blacklisted", false);
        f.legacyCompatible = bool(extra, "legacyCompatible", false);
        f.settingsKnown = bool(extra, "settingsKnown", true);
        f.enabled = bool(extra, "enabled", true);
        f.failed = bool(extra, "failed", false);
        f.launchIdExists = bool(extra, "launchIdExists", false);
        f.targetBuild = num(extra, "targetBuild");
        f.targetRevision = num(extra, "targetRevision");
        int javaMajor = num(extra, "minJavaMajor");
        int modMajor = num(extra, "minModMajor");
        if (javaMajor > 0) f.minJavaMajor = javaMajor;
        if (modMajor > 0) f.minModMajor = modMajor;
        f.slotSkipping = slotSkipping;
        return f;
    }

    /** 六道门的结论（顺序固定；永远 {@link #COUNT} 条）。 */
    public static List<Decision> decide(Facts f) {
        List<Decision> out = new ArrayList<Decision>(COUNT);
        if (f == null) f = new Facts();

        // ① 安卓到底能不能加载它
        boolean load = !f.willFailJavaLoad;
        out.add(new Decision(LOAD, load ? "cf_gate_load" : "cf_gate_load_bad", load,
                load ? "" : "cf_gate_load_note", none()));

        // ② 游戏里开没开（★ 没读到设置时**补一句"这是默认值"**，别把默认值当读到的事实）
        boolean on = f.enabled;
        String onNote = on
                ? (f.settingsKnown ? "" : "cf_gate_on_unknown")
                : "cf_gate_on_off";
        out.add(new Decision(ON, on ? "cf_gate_on" : "cf_gate_on_bad", on, onNote, none()));

        // ③ 游戏版本够不够
        boolean verOk = Conflicts.isAtLeast(f.targetBuild, f.targetRevision, f.minGameVersion);
        out.add(new Decision(VERSION, verOk ? "cf_gate_ver" : "cf_gate_ver_bad", verOk,
                verOk ? "" : "cf_gate_ver_note_fmt",
                verOk ? none() : new String[]{f.minGameVersion, versionText(f)}));

        // ④ 门槛（v8+ 才有；目标版本未知 ⇒ 不套门槛，与游戏自己的判断一致）
        int need = f.targetBuild >= f.minJavaMajor
                ? (f.java ? f.minJavaMajor : f.minModMajor) : 0;
        int minMajor = Conflicts.minMajorOf(f.minGameVersion);
        boolean majorOk = need <= 0 || minMajor >= need || f.legacyCompatible;
        out.add(new Decision(MINVER, majorOk ? "cf_gate_minver" : "cf_gate_minver_bad", majorOk,
                majorOk ? "" : (minMajor <= 0 ? "cf_gate_minver_none" : "cf_gate_minver_old_fmt"),
                !majorOk && minMajor > 0
                        ? new String[]{String.valueOf(minMajor), String.valueOf(need)} : none()));

        // ⑤ 黑名单（游戏写死在代码里的名单，不是模组自己的开关）
        boolean blOk = !f.blacklisted;
        out.add(new Decision(BLACKLIST, blOk ? "cf_gate_blacklist" : "cf_gate_blacklist_bad", blOk,
                blOk ? "" : "cf_gate_blacklist_note_fmt",
                blOk ? none() : new String[]{f.name + ":" + f.version}));

        // ⑥ 上次启动：**顺序 = 槽级优先**（槽级跳过是更可行动的那句）
        if (f.slotSkipping) {
            out.add(new Decision(LAST, "cf_gate_last_skip", false,
                    "cf_gate_last_skip_again", none()));
        } else if (f.failed) {
            // ★ 这里说的才是**这个模组自己的**记录（游戏写的 `-failed`），按项事实可以按项说
            out.add(new Decision(LAST, "cf_gate_last_failed", false,
                    "cf_gate_last_retry", none()));
        } else {
            out.add(new Decision(LAST, "cf_gate_last", true,
                    f.launchIdExists ? "cf_gate_last_pending" : "", none()));
        }
        return out;
    }

    private static String[] none() {
        return new String[0];
    }

    /** 失败时给用户看的"当前版本"（与宿主 `gameBuild.revision` 同一个写法）。 */
    private static String versionText(Facts f) {
        return f.targetBuild + (f.targetRevision == 0 ? "" : "." + f.targetRevision);
    }

    private static String str(Map<String, String> m, String k) {
        String v = m == null ? null : m.get(k);
        return v == null ? "" : v;
    }

    private static boolean bool(Map<String, String> m, String k, boolean dflt) {
        String v = str(m, k);
        return v.isEmpty() ? dflt : "true".equals(v);
    }

    private static int num(Map<String, String> m, String k) {
        try {
            return Integer.parseInt(str(m, k).trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
