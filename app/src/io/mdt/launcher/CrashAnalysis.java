package io.mdt.launcher;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★★ **崩溃分析**（F23）：游戏崩了之后，**是哪个模组干的**。
 *
 * ── 为什么要有它 ──────────────────────────────────────────────────────────
 * 在此之前 {@link LogActivity} 只是把游戏写好的崩溃报告**原文**摊出来（异常行染个色），
 * 归因这件事整个交给了游戏自己。实测（2026-10-07 实验台，见 `docs/flows/16-…`）游戏
 * 自己那套 {@code CrashHandler.getModCause} 有**两处硬伤**：
 *   ① **不追 `Caused by` 链** —— 它只遍历顶层栈，而"模组 `init()` 里抛异常"这条最常见的
 *      形态顶层全是 `mindustry.mod.*`，被 `(mindustry|arc|java|…)` 那条正则**整片滤掉**
 *      ⇒ 返回 null（报告里压根没有 `Likely Cause:` 行）；
 *   ② 只看 `meta.main` 的**包前缀** ⇒ 把类注入游戏命名空间的那种模组（Neon 就是）看不见。
 * ⇒ 我们自己做，且做**四层**，把游戏给不出答案的那一大类补上。
 *
 * ── 四层判据（成本从低到高，能出结论就不再往下挖）────────────────────────
 * | 层 | 判据 | 输入 |
 * |---|---|---|
 * | **L1** | 读报告首句 `The mod 'X' (y) has caused …` 与 `Likely Cause: X (y vZ)` | 报告头部 |
 * | **L1b** | 读 `RuntimeException: Error loading mod <internalName>`（载入期路径） | 任一节的消息 |
 * | **L2a** | 异常栈里的类名 vs 模组 `meta.main` 的**包前缀**（照抄游戏，且**追整条链**） | 栈帧 + 模组表 |
 * | **L2b/L3** | 帧类名 / **缺失符号**（`NoSuchFieldError` 那句里的类名、成员名）**在它的 dex 里** | dex 字节搜索 |
 * | **L4** | 追 `Caused by:` 链 —— 它**不是**一层判据，而是 L2/L3 的前提 | 报告正文 |
 *
 * ⚠️ **顺序是刻意的**：L1/L1b 是**免费**的（只读报告），L2a 要模组表，L2b/L3 要
 * **读几 MB 的 dex**（用户那个 `DeepSpace` 一个包就 10 MB）⇒ 只在前面都没结论时才扫，
 * 且**一次把所有针一起传给 {@link Mods#scanDex}**（n 个模组 = n 次流式读，不是 n×k 次）。
 *
 * ── 三条纪律 ─────────────────────────────────────────────────────────────
 *  ① **认不出就说不认识**：宁可不点名，也不瞎点名（"可能是环境/游戏本体/已删掉的模组"）。
 *  ② **界面与自检必须走同一份格式化**（{@link #text}）—— 第 113 轮那条"自检自己挑参数、
 *     与调用点不同源 ⇒ 绿着而线上崩"的教训（REF §77.6）。
 *  ③ 解析器**不做 IO、不碰 Context**（{@link #parse}/{@link #judge} 都是纯函数），
 *     这样自检可以直接喂真语料；只有渲染与 dex 探针才需要外部世界。
 *
 * ── 这个类不做什么 ───────────────────────────────────────────────────────
 *  · 不判"启动器自己崩的"以外的启动器问题：`*_launcher.txt` 只认出**是谁的报告**（{@link Report#launcherOwn}）；
 *  · 不解析 `Patches:` 段的内容（它在正文末尾，归因只需要**标题区 + 异常链**）；
 *  · 不把 `Date:` 当时间（那行随 locale 变，中文 `十月 7, 2026 …`；报告名才是毫秒时间戳）。
 */
final class CrashAnalysis {

    private CrashAnalysis() {}

    // ── 结论的种类 ────────────────────────────────────────────────────────

    /** 认不出（报告里没有指向模组的证据） */
    static final int KIND_NONE = 0;
    /** 点名了模组（一个或多个） */
    static final int KIND_MOD = 1;
    /** 报告自己写着没装模组（`Mods: none (vanilla)`） */
    static final int KIND_VANILLA = 2;
    /** 这是**启动器自己**的崩溃报告（`crash_<毫秒>_launcher.txt`），不做模组归因 */
    static final int KIND_LAUNCHER = 3;
    /** 读不出（空文件 / 根本不是报告） */
    static final int KIND_BROKEN = 4;

    // ── 依据层 ────────────────────────────────────────────────────────────

    static final String LAYER_L1 = "L1";
    static final String LAYER_L1B = "L1b";
    static final String LAYER_FRAME = "L2";
    static final String LAYER_DEX = "L3";

    /**
     * **点名门槛**：针的权重 ≥ 它才算"点名"，否则只当"弱线索"。
     *
     * 权重规则（{@link #weightOfClass} / {@link #weightOfMember}）只有一条思想：
     * **越不像游戏本体的东西，越有分辨力**。所以
     * `stealthpath.StealthPathMod`（模组自己的包）= 3，
     * `mindustry.logic.SugarCanvas$SugarStatementElem`（注入游戏命名空间、但带 `$` 内部类）= 2，
     * `mindustry.core.Logic.update`（游戏本体的类）= 1。
     * ⚠️ 1 分**不能**点名：每个模组的 dex 里都有几千个游戏类名，靠它点名必然瞎点名。
     */
    static final int NAMING_WEIGHT = 2;

    // ── 数据模型 ──────────────────────────────────────────────────────────

    /** 报告里的一条异常节（顶层一条 + 每个 `Caused by:` 一条） */
    static final class Sec {
        /** 异常类（点号全名，如 `java.lang.NoSuchFieldError`） */
        String type = "";
        /** 类名后面的消息（没有则空串） */
        String message = "";
        /** 这一节的栈帧原文（`at pkg.Cls.method(File.java:12)` 整行，已 trim） */
        final List<String> frames = new ArrayList<String>();
    }

    /**
     * 一根"针"：拿去模组的 `classes.dex` 里搜的字节串。
     * ★ 存**两个形态**是有意的：{@link #text} 是搜索用的原始串（类用 `a/b/C` 描述符，
     * 成员用名字），{@link #label} 是给人看的（`a.b.C` / 成员名）—— 报告里出现的是后者。
     */
    static final class Needle {
        final String text;
        final String label;
        final int weight;

        Needle(String text, String label, int weight) {
            this.text = text;
            this.label = label;
            this.weight = weight;
        }
    }

    /** 解析结果（纯数据，无 IO） */
    static final class Report {
        /** 空文件 / 全是空白 ⇒ 没什么可分析的 */
        boolean empty;
        String emptyReason = "";
        /** 首行是启动器自己的报告头（`MDT 安卓启动器 —— 崩溃报告`） */
        boolean launcherOwn;

        String firstLine = "";
        String version = "";
        /** `Version:` 后面紧跟的那行（MindustryX 会加一行），可能是空串 */
        String extraVersion = "";
        String os = "";
        String apiLevel = "";
        String likelyRaw = "";
        /** `Likely Cause: <显示名> (<internalName> v<版本>)` 拆出来的三段 */
        String likelyName = "";
        String likelyInternal = "";
        String likelyVersion = "";
        /** `Mods:` 原文（`none (vanilla)` 或 `a:1.0, b:2.0`） */
        String modsRaw = "";
        boolean hasModsLine;
        boolean vanilla;
        /** `Mods:` 里的 internalName（去版本号，保持报告里的顺序） */
        final List<String> mods = new ArrayList<String>();

        /** 异常链（[0] = 顶层，后面每个 `Caused by` 一条；**L4 就是它**） */
        final List<Sec> chain = new ArrayList<Sec>();
        /** 全部针（按发现顺序去重，同串取最大权重） */
        final List<Needle> needles = new ArrayList<Needle>();
        /** 栈帧总数（报告/自检用） */
        int frameCount;

        String topType() {
            return chain.isEmpty() ? "" : chain.get(0).type;
        }

        String topMessage() {
            return chain.isEmpty() ? "" : chain.get(0).message;
        }
    }

    /** 一个被点到的模组 */
    static final class Hit {
        /** 展示名（报告里写的名字，或在模组表里查到的 `title()`） */
        String name = "";
        String internal = "";
        String layer = "";
        /** 命中的依据原文（类名 / 成员名 / internalName） */
        String evidence = "";
        int weight;
        /** 在当前槽的模组表里找到了对应的包 */
        boolean known;
        /** 出现在这份报告的 `Mods:` 行里（这次启动真的加载了它） */
        boolean inReport;
    }

    /** 结论 */
    static final class Verdict {
        int kind = KIND_NONE;
        /** `KIND_BROKEN` 的原因（**已本地化**，可直接显示） */
        String reason = "";
        /** `KIND_MOD` ⇒ 被点名的；`KIND_NONE` 时可能装着权重不够的"弱线索" */
        final List<Hit> hits = new ArrayList<Hit>();
        /** 扫了几个模组的 dex；-1 = 便宜层就出结论了，没扫 */
        int dexScanned = -1;
    }

    /**
     * **dex 探针** —— 把"这个模组的 dex 里有没有这些串"这件事做成可注入的一格。
     * ★ 为什么不让 {@link CrashAnalysis} 直接调 {@link Mods#scanDex}：自检要能在
     *   **不造真 dex** 的前提下验每一条判据（造个只含几个字节的文件即可，见 SelfTest ㊻），
     *   而且"哪来的类名集合"这件事将来换实现（真解析 dex 字符串表）时不该动判据代码。
     */
    interface DexProbe {
        /** 返回 {@code patterns} 里命中的下标集合；读不了/没有 dex 一律返回空集（不抛） */
        Set<Integer> scan(Mods.Info m, List<String> patterns);
    }

    /** 生产用的探针：走 {@link Mods#scanDex}（流式、Latin-1 逐字节保真） */
    static final DexProbe DEX = new DexProbe() {
        @Override public Set<Integer> scan(Mods.Info m, List<String> patterns) {
            try {
                return Mods.scanDex(m, patterns);
            } catch (Throwable t) {
                return Collections.emptySet();
            }
        }
    };

    // ══ 解析 ═══════════════════════════════════════════════════════════════

    /** 异常行：`pkg.Cls(Something)Exception|Error|Throwable` 后面可跟 `: 消息` */
    private static final Pattern THROWABLE = Pattern.compile(
            "^(?:Caused by: )?(?:Suppressed: )?"
            + "([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)"
            + "(?:: (.*))?$");
    /** 帧：`at pkg.Cls.method(File.java:12)`（`(Unknown Source:47)` / `(Native Method)` 同形） */
    private static final Pattern FRAME = Pattern.compile("^at ([\\w$.]+)\\.([\\w$<>]+)\\((.*)\\)$");
    /** 首句归因：`The mod 'X' (y) has caused Mindustry to crash.` */
    private static final Pattern FIRST_LINE_CAUSE =
            Pattern.compile("^The mod '(.+?)' \\(([^)]+)\\) has caused");
    /** 载入期：`RuntimeException: Error loading mod <internalName>` */
    private static final Pattern ERR_LOADING_MOD =
            Pattern.compile("Error loading mod ([A-Za-z0-9_.\\-]+)");
    /** `Likely Cause: <显示名> (<internalName> v<版本>)` */
    private static final Pattern LIKELY = Pattern.compile("^(.+?)\\s*\\(([^)]+)\\)$");
    /** 消息里的类样 token（用来当针；`$` 内部类算一个 token） */
    private static final Pattern CLASS_TOKEN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    /** 缺失符号的消息形态（见 REF §85 的表）。
     *  ⚠️ **两套运行时的措辞完全不同**，必须都认（2026-10-08 用设备自带 `dalvikvm` 实测，
     *  逐字原文在 `docs/crash-corpus/13-ART-措辞-安卓运行期.md`）：
     *   · **HotSpot（桌面）**：`Class a.b.C does not have member field 'int y'`；
     *   · **ART（安卓）**：`No field y of type I in class La/b/C; or its superclasses (…)`。
     *  只照桌面语料写正则 ⇒ 在手机上**一根针都抽不出来**（L3 整层失效）。 */
    private static final Pattern FIELD_MSG = Pattern.compile(
            "Class ([\\w.$]+) does not have member field '([^']*)'");
    private static final Pattern METHOD_MSG = Pattern.compile("'([^']*\\([^']*\\))'");
    private static final Pattern NOCLASS_MSG = Pattern.compile(
            "(?:NoClassDefFoundError|ClassNotFoundException):\\s*([\\w/$]+)");
    /** ART：`No field definitelyNotAField of type I in class Lmindustry/core/GameState;` */
    private static final Pattern ART_FIELD = Pattern.compile(
            "No (?:static |instance )?field ([\\w$]+) of type (\\S+) in class L([\\w/$]+);");
    /** ART：`No static method definitelyNotAMethod()V in class Lmindustry/core/GameState;` */
    private static final Pattern ART_METHOD = Pattern.compile(
            "No (?:static |virtual |direct |interface )?method ([\\w$<>]+)\\(([^)]*)\\)(\\S+) in class L([\\w/$]+);");
    /** ART：`Failed resolution of: Lmindustry/logic/NotThere;` */
    private static final Pattern ART_NOCLASS = Pattern.compile("Failed resolution of: L([\\w/$]+);");
    /** 两种运行时**同款**的那句：`Field 'a.b.C.member' is inaccessible to class 'x.Y'` */
    private static final Pattern ACCESS_MSG = Pattern.compile(
            "(?:Field|Method) '([\\w.$]+)' is inaccessible to class '([\\w.$]+)'");

    private static final String[] GAME_PREFIXES = {
            "mindustry", "arc", "java", "javax", "sun", "jdk", "android", "dalvik",
            "kotlin", "rhino", "org", "com", "net", "io", "androidx",
    };

    /**
     * 解析一份崩溃报告。**纯函数**（无 IO、无 Context）⇒ 自检可以直接喂真语料。
     * ⚠️ 传进来的应当是**全文**（`Doc#fullText()`），不是页面那份"尾部 400 行" ——
     *   语料里最长的一份报告有 1044 行，头部（`Version`/`Mods`/`Likely Cause`）会被截掉。
     */
    static Report parse(String text) {
        Report r = new Report();
        if (text == null || text.trim().isEmpty()) {
            r.empty = true;
            r.emptyReason = "empty";
            return r;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        r.firstLine = lines.length == 0 ? "" : lines[0].trim();
        r.launcherOwn = r.firstLine.startsWith("MDT");

        // ── 头部：一路读到第一个异常行为止 ──
        int i = 0;
        boolean sawVersion = false;
        for (; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (isThrowableLine(t)) break;
            if (t.startsWith("Version:")) {
                r.version = value(t);
                sawVersion = true;
                continue;
            }
            if (t.startsWith("Likely Cause:")) {
                r.likelyRaw = value(t);
                Matcher m = LIKELY.matcher(r.likelyRaw);
                if (m.matches()) {
                    r.likelyName = m.group(1).trim();
                    String inner = m.group(2).trim();
                    int v = inner.lastIndexOf(" v");
                    if (v > 0) {
                        r.likelyInternal = inner.substring(0, v).trim();
                        r.likelyVersion = inner.substring(v + 2).trim();
                    } else {
                        r.likelyInternal = inner;
                    }
                } else {
                    r.likelyName = r.likelyRaw;
                }
                continue;
            }
            if (t.startsWith("Mods:")) {
                r.modsRaw = value(t);
                r.hasModsLine = true;
                parseMods(r);
                continue;
            }
            if (t.startsWith("OS:")) { r.os = value(t); continue; }
            if (t.startsWith("Android API level:")) { r.apiLevel = value(t); continue; }
            if (isKnownHeader(t)) continue;
            // 未知的头部行：紧跟在 Version 后面的那行是 MindustryX 的版本尾注（如 `MindustryX 2026.07.X36`）
            if (sawVersion && r.extraVersion.isEmpty()) r.extraVersion = t;
        }

        // 首句归因（游戏只在 L1 成立时才这么写；`Likely Cause:` 行同理，二者取其一）
        Matcher fc = FIRST_LINE_CAUSE.matcher(r.firstLine);
        if (fc.find()) {
            if (r.likelyName.isEmpty()) r.likelyName = fc.group(1).trim();
            if (r.likelyInternal.isEmpty()) r.likelyInternal = fc.group(2).trim();
        }

        // ── 异常链（含 `Caused by`）──
        Sec cur = null;
        for (; i < lines.length; i++) {
            String raw = lines[i];
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("Patches:")) break;         // 正文末尾的数据补丁段，不参与归因
            Matcher fm = FRAME.matcher(t);
            if (fm.matches()) {
                if (cur == null) {
                    cur = new Sec();
                    r.chain.add(cur);
                }
                cur.frames.add(t);
                r.frameCount++;
                continue;
            }
            if (t.startsWith("...") || t.startsWith("Suppressed:")) continue;
            if (isThrowableLine(t)) {
                Matcher m = THROWABLE.matcher(t);
                if (m.matches()) {
                    cur = new Sec();
                    cur.type = m.group(1);
                    cur.message = m.group(2) == null ? "" : m.group(2);
                    r.chain.add(cur);
                    continue;
                }
            }
        }

        collectNeedles(r);
        return r;
    }

    /** `Mods: none (vanilla)` / `Mods: kotlin:2.3.20, neon:120006` ⇒ internalName 列表 */
    private static void parseMods(Report r) {
        String v = r.modsRaw.trim();
        if (v.isEmpty()) return;
        if (v.toLowerCase(Locale.ROOT).startsWith("none")) {
            r.vanilla = true;
            return;
        }
        for (String part : v.split(",")) {
            String e = part.trim();
            if (e.isEmpty()) continue;
            int c = e.lastIndexOf(':');
            String name = c > 0 ? e.substring(0, c).trim() : e;
            if (!name.isEmpty()) r.mods.add(name);
        }
    }

    /**
     * 已知的头部键（**只看英文键**）。
     * 🔴 不许拿中文文案当判据（门禁 `SRC-01`）：{@link Crash} 写的那份启动器报告头部是中文
     *   （`时刻：`/`当前槽：`…），但那几行**本类根本不需要** —— 认"是谁的报告"靠首行
     *   （见 {@link Report#launcherOwn}），不靠这些键 ⇒ 万一以后把它们翻译了，这里也不变。
     */
    private static boolean isKnownHeader(String t) {
        return t.startsWith("Date:") || t.startsWith("GL Version:") || t.startsWith("Java Version:")
                || t.startsWith("Runtime Available Memory:") || t.startsWith("Cores:")
                || t.startsWith("Report this at") || t.startsWith("Mindustry has crashed")
                || t.startsWith("The mod ");
    }

    private static String value(String line) {
        int c = line.indexOf(':');
        return c < 0 ? "" : line.substring(c + 1).trim();
    }

    /** 这一行是不是异常首行（Gradle 之外的判据：类名以 Exception/Error/Throwable 结尾） */
    private static boolean isThrowableLine(String t) {
        Matcher m = THROWABLE.matcher(t);
        if (!m.matches()) return false;
        String type = m.group(1);
        int d = type.lastIndexOf('.');
        String simple = d < 0 ? type : type.substring(d + 1);
        return simple.endsWith("Exception") || simple.endsWith("Error") || simple.endsWith("Throwable");
    }

    // ══ 抽针（L2b/L3 的输入）═══════════════════════════════════════════════

    /**
     * 从异常链里抽"针"。四种消息形态各有一条规则（措辞全部来自实测语料）：
     *  · `NoSuchFieldError: Class <owner> does not have member field '<类型> <名字>'` ⇒ 针 = **成员名**；
     *  · `NoSuchMethodError: '<返回类型> <owner>.<方法>(<参数>)'` ⇒ 针 = **方法名**；
     *  · `NoClassDefFoundError` / `ClassNotFoundException: <类>`（前者斜杠、后者点号）⇒ 针 = 类描述符；
     *  · `IllegalAccessError` 及其它 ⇒ 消息里**所有类样 token**（含 `$` 内部类）。
     * ⚠️ 再加一条兜底：消息里嵌着的**内层异常**（`ExecutionException: java.lang.NoSuchFieldError: …`）
     *   也按上面四条走 —— 否则"包装异常"型会一根针都抽不到（真语料里就有这种）。
     */
    private static void collectNeedles(Report r) {
        Map<String, Needle> byText = new LinkedHashMap<String, Needle>();
        for (Sec s : r.chain) addNeedles(s.type + ": " + s.message, byText);
        // 帧也当针（L2b）：类描述符，权重按"像不像模组自己的东西"
        Set<String> frameCls = new LinkedHashSet<String>();
        for (Sec s : r.chain) {
            for (String f : s.frames) {
                Matcher m = FRAME.matcher(f);
                if (!m.matches()) continue;
                frameCls.add(m.group(1));
            }
        }
        for (String cls : frameCls) put(byText, new Needle(cls.replace('.', '/'), cls, weightOfClass(cls)));
        r.needles.clear();
        r.needles.addAll(byText.values());
    }

    private static void addNeedles(String msg, Map<String, Needle> byText) {
        if (msg == null || msg.isEmpty()) return;
        boolean any = false;

        Matcher f = FIELD_MSG.matcher(msg);
        while (f.find()) {
            String member = lastToken(f.group(2));
            if (!member.isEmpty()) {
                put(byText, new Needle(member, member, weightOfMember(member)));
                any = true;
            }
            // owner 类也要进针袋：① 成员名太短时它才有分辨力（`state` 这种名字到处都是）；
            // ② 报告里写的是"哪个类缺了这个成员"，那句话本身就是判据的一部分。
            String owner = f.group(1);
            if (!owner.isEmpty()) {
                put(byText, new Needle(owner.replace('.', '/'), owner, weightOfClass(owner)));
            }
        }
        // `NoSuchMethodError: 'void a.b.C.m(java.lang.String)'` —— 只认**带括号的引号串**
        if (msg.contains("NoSuchMethodError") || msg.contains("NoSuchMethodException")) {
            Matcher mm = METHOD_MSG.matcher(msg);
            while (mm.find()) {
                String sig = mm.group(1);
                int p = sig.indexOf('(');
                String head = p > 0 ? sig.substring(0, p) : sig;
                String member = lastToken(head);
                if (!member.isEmpty() && !member.equals(head)) {   // `head` 里必须有 owner
                    put(byText, new Needle(member, member, weightOfMember(member)));
                    any = true;
                    int sp = head.lastIndexOf(' ');
                    String owner = sp > 0 ? head.substring(sp + 1) : "";
                    int dot = owner.lastIndexOf('.');
                    if (dot > 0) {
                        owner = owner.substring(0, dot);
                        put(byText, new Needle(owner.replace('.', '/'), owner, weightOfClass(owner)));
                    }
                }
            }
        }
        Matcher nc = NOCLASS_MSG.matcher(msg);
        while (nc.find()) {
            String cls = nc.group(1).replace('/', '.');
            // ⚠️ ART 那句 `NoClassDefFoundError: Failed resolution of: L…;` 会让上面的正则
            //   捕到 `Failed` 这个词 —— 那不是类名。判据：真类名里必须有 `.` 或大写开头。
            if (!cls.equals("Failed") && (cls.indexOf('.') > 0 || cls.startsWith("L"))) {
                put(byText, new Needle(cls.replace('.', '/'), cls, weightOfClass(cls)));
                any = true;
            }
        }
        // ── ART（安卓运行期）那几种措辞 —— 与桌面不同款，见本方法头上的注释 ──
        Matcher af = ART_FIELD.matcher(msg);
        while (af.find()) {
            String member = af.group(1);
            put(byText, new Needle(member, member, weightOfMember(member)));
            String owner = af.group(3).replace('/', '.');
            put(byText, new Needle(owner.replace('.', '/'), owner, weightOfClass(owner)));
            any = true;
        }
        Matcher am = ART_METHOD.matcher(msg);
        while (am.find()) {
            String member = am.group(1);
            put(byText, new Needle(member, member, weightOfMember(member)));
            String owner = am.group(4).replace('/', '.');
            put(byText, new Needle(owner.replace('.', '/'), owner, weightOfClass(owner)));
            any = true;
        }
        Matcher an = ART_NOCLASS.matcher(msg);
        while (an.find()) {
            String cls = an.group(1).replace('/', '.');
            put(byText, new Needle(cls.replace('.', '/'), cls, weightOfClass(cls)));
            any = true;
        }
        Matcher ac = ACCESS_MSG.matcher(msg);
        while (ac.find()) {
            // ① **访问方类**（`boom.Boom` / 注入进游戏命名空间的 `mindustry.logic.SugarCanvas$…`）
            //    —— 这是这条消息里最有分辨力的东西；② 目标成员（`LCanvas.privileged`）。
            String who = ac.group(2);
            put(byText, new Needle(who.replace('.', '/'), who, weightOfClass(who)));
            String target = ac.group(1);
            int dot = target.lastIndexOf('.');
            if (dot > 0) {
                String owner = target.substring(0, dot);
                String member = target.substring(dot + 1);
                put(byText, new Needle(member, member, weightOfMember(member)));
                put(byText, new Needle(owner.replace('.', '/'), owner, weightOfClass(owner)));
            }
            any = true;
        }
        // 兜底：消息里的类样 token（IllegalAccessError 的"访问方类"、包装异常的类名、缺依赖的类名都在这里）
        if (!any || msg.contains("IllegalAccessError") || msg.contains("LinkageError")) {
            Matcher ct = CLASS_TOKEN.matcher(msg);
            while (ct.find()) {
                String cls = ct.group();
                // 别把方法签名里的参数类型之外的东西当类：至少要有一个 `.` 且不以数字开头（正则已保证）
                if (cls.length() < 5) continue;
                put(byText, new Needle(cls.replace('.', '/'), cls, weightOfClass(cls)));
            }
        }
        // 内层异常（`ExecutionException: java.lang.NoSuchFieldError: …`）⇒ 递归
        int at = msg.indexOf(": ");
        if (at > 0) {
            String rest = msg.substring(at + 2);
            if (rest.length() < msg.length() && looksLikeThrowableText(rest)) addNeedles(rest, byText);
        }
    }

    /** `int definitelyNotAField` / `mindustry.ai.UnitCommand boostCommand` ⇒ 取最后一个 token */
    private static String lastToken(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (t.endsWith(">")) {                        // 泛型 `java.util.List<java.lang.String>` ⇒ 只要 List
            int g = t.indexOf('<');
            if (g > 0) t = t.substring(0, g);
        }
        int sp = t.lastIndexOf(' ');
        if (sp >= 0) t = t.substring(sp + 1);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return t.trim();
    }

    private static boolean looksLikeThrowableText(String s) {
        int c = s.indexOf(':');
        String head = c < 0 ? s : s.substring(0, c);
        head = head.trim();
        if (head.indexOf(' ') >= 0) return false;
        int d = head.lastIndexOf('.');
        String simple = d < 0 ? head : head.substring(d + 1);
        return simple.endsWith("Exception") || simple.endsWith("Error") || simple.endsWith("Throwable");
    }

    private static void put(Map<String, Needle> byText, Needle n) {
        if (n == null || n.text.length() < 3) return;
        Needle old = byText.get(n.text);
        if (old == null || n.weight > old.weight) byText.put(n.text, n);
    }

    /** 类 token 的权重（见 {@link #NAMING_WEIGHT} 的注释） */
    private static int weightOfClass(String cls) {
        if (cls == null || cls.isEmpty()) return 1;
        String low = cls.toLowerCase(Locale.ROOT);
        for (String p : GAME_PREFIXES) {
            if (low.equals(p) || low.startsWith(p + ".")) {
                // 游戏/系统命名空间：注入进去的模组类几乎都带 `$`（`SugarCanvas$SugarStatementElem`）
                return cls.indexOf('$') >= 0 ? 2 : 1;
            }
        }
        return 3;
    }

    /** 成员名的权重：名字越长越有分辨力（`definitelyNotAField` vs `state`） */
    private static int weightOfMember(String member) {
        if (member == null) return 1;
        if (member.length() >= 10) return 3;
        if (member.length() >= 6) return 2;
        return 1;
    }

    // ══ 判据 ═══════════════════════════════════════════════════════════════

    /**
     * 出结论。{@code mods} 可以为 null（那表示"先跑便宜层"），{@code probe} 可以为 null
     * （不扫 dex）—— 调用方先跑一遍便宜层，没结论再带着模组表与探针跑第二遍（见 LogActivity）。
     */
    static Verdict judge(Report r, List<Mods.Info> mods, DexProbe probe) {
        Verdict v = new Verdict();
        if (r == null || r.empty) {
            v.kind = KIND_BROKEN;
            v.reason = r == null ? "null" : r.emptyReason;
            return v;
        }
        if (r.launcherOwn) {
            v.kind = KIND_LAUNCHER;
            return v;
        }

        // ── L1：游戏自己写明的归因（报告头）──
        if (!r.likelyName.isEmpty() || !r.likelyInternal.isEmpty()) {
            Hit h = new Hit();
            h.name = r.likelyName.isEmpty() ? r.likelyInternal : r.likelyName;
            h.internal = r.likelyInternal;
            h.layer = LAYER_L1;
            h.evidence = h.internal.isEmpty() ? h.name : h.internal;
            h.weight = 3;
            resolve(h, mods, r);
            v.hits.add(h);
            v.kind = KIND_MOD;
            return v;
        }

        // ── L1b：载入期（`Error loading mod <名>`）—— 游戏在这一层**给不出** Likely Cause ──
        String failing = findErrorLoadingMod(r);
        if (failing != null) {
            Hit h = new Hit();
            h.name = failing;
            h.internal = failing;
            h.layer = LAYER_L1B;
            h.evidence = failing;
            h.weight = 3;
            resolve(h, mods, r);
            v.hits.add(h);
            v.kind = KIND_MOD;
            return v;
        }

        // ── 报告自己写着没装模组 ──
        if (r.vanilla) {
            v.kind = KIND_VANILLA;
            return v;
        }

        if (mods == null) return v;      // 便宜层到此为止（调用方会带着模组表再来一次）

        // ── L2a：栈帧落在某个模组的 `meta.main` 包前缀下（照抄游戏，但**追整条链**）──
        for (Sec s : r.chain) {
            for (String f : s.frames) {
                Matcher m = FRAME.matcher(f);
                if (!m.matches()) continue;
                String cls = m.group(1);
                for (Mods.Info info : mods) {
                    if (info == null || info.main == null) continue;
                    String pkg = packageOf(info.main);
                    if (pkg.isEmpty() || !cls.startsWith(pkg + ".")) continue;
                    if (alreadyHit(v.hits, info)) continue;   // 同一个模组的几十个帧只算一条
                    Hit h = hitOf(info, r);
                    h.layer = LAYER_FRAME;
                    h.evidence = cls;
                    h.weight = 3;
                    v.hits.add(h);
                }
            }
        }
        if (!v.hits.isEmpty()) {
            v.kind = KIND_MOD;
            return v;
        }

        // ── L2b/L3：针在它的 dex 里 ──
        if (probe == null || r.needles.isEmpty()) return v;
        List<String> pats = new ArrayList<String>();
        for (Needle n : r.needles) pats.add(n.text);
        int scanned = 0;
        Map<String, Hit> best = new LinkedHashMap<String, Hit>();
        for (Mods.Info info : mods) {
            if (info == null || !info.hasClassesDex) continue;
            Set<Integer> got = probe.scan(info, pats);
            scanned++;
            if (got == null || got.isEmpty()) continue;
            for (Integer idx : got) {
                if (idx == null || idx < 0 || idx >= r.needles.size()) continue;
                Needle n = r.needles.get(idx);
                Hit h = hitOf(info, r);
                h.layer = LAYER_DEX;
                h.evidence = n.label;
                h.weight = n.weight;
                Hit old = best.get(h.name);
                if (old == null || h.weight > old.weight) best.put(h.name, h);
            }
        }
        v.dexScanned = scanned;
        int top = 0;
        for (Hit h : best.values()) top = Math.max(top, h.weight);
        for (Hit h : best.values()) if (h.weight == top) v.hits.add(h);
        if (top >= NAMING_WEIGHT) v.kind = KIND_MOD;   // 否则只当"弱线索"（v.kind 保持 NONE）
        return v;
    }

    /** `Error loading mod <internalName>`（在**整条链**里找，不只看顶层） */
    private static String findErrorLoadingMod(Report r) {
        for (Sec s : r.chain) {
            Matcher m = ERR_LOADING_MOD.matcher(s.message);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String packageOf(String main) {
        int d = main.lastIndexOf('.');
        return d <= 0 ? "" : main.substring(0, d);
    }

    private static boolean alreadyHit(List<Hit> hits, Mods.Info info) {
        for (Hit h : hits) {
            if (!h.internal.isEmpty() && h.internal.equalsIgnoreCase(info.internalName)) return true;
            if (info.name != null && h.internal.equalsIgnoreCase(info.name)) return true;
        }
        return false;
    }

    private static Hit hitOf(Mods.Info info, Report r) {
        Hit h = new Hit();
        h.name = info.title();
        h.internal = info.internalName == null ? "" : info.internalName;
        h.known = true;
        h.inReport = r != null && containsIgnoreCase(r.mods, h.internal);
        return h;
    }

    /** 把报告里那个名字对回当前槽的模组表（对不上也**照样点名** —— 报告是历史事实） */
    private static void resolve(Hit h, List<Mods.Info> mods, Report r) {
        if (mods == null) return;
        for (Mods.Info info : mods) {
            if (info == null) continue;
            boolean byKey = !h.internal.isEmpty() && h.internal.equalsIgnoreCase(info.internalName);
            boolean byName = info.name != null && h.internal.equalsIgnoreCase(info.name);
            boolean byTitle = h.name.equalsIgnoreCase(info.title())
                    || (info.displayName != null && h.name.equalsIgnoreCase(info.displayName));
            if (byKey || byName || byTitle) {
                h.name = info.title();
                if (info.internalName != null && !info.internalName.isEmpty()) h.internal = info.internalName;
                h.known = true;
                break;
            }
        }
        h.inReport = r != null && containsIgnoreCase(r.mods, h.internal);
    }

    private static boolean containsIgnoreCase(List<String> xs, String s) {
        if (s == null || s.isEmpty()) return false;
        for (String x : xs) if (x != null && x.equalsIgnoreCase(s)) return true;
        return false;
    }

    // ══ 渲染（★ 界面与自检共用这一份）═════════════════════════════════════

    /** 结论整串（`\n` 分隔的多行）。传 null 或空结论返回空串 ⇒ 调用方把那一行藏起来。 */
    static String text(Context ctx, Verdict v) {
        if (ctx == null || v == null) return "";
        switch (v.kind) {
            case KIND_LAUNCHER:
                return Trans.get(ctx, R.string.crash_verdict_launcher);
            case KIND_BROKEN: {
                String why = "empty".equals(v.reason) ? Trans.get(ctx, R.string.crash_reason_empty)
                        : "null".equals(v.reason) ? Trans.get(ctx, R.string.crash_reason_null)
                        : v.reason;
                return Trans.get(ctx, R.string.crash_verdict_broken_fmt, why);
            }
            case KIND_VANILLA:
                return Trans.get(ctx, R.string.crash_verdict_vanilla);
            case KIND_MOD: {
                if (v.hits.size() == 1) {
                    Hit h = v.hits.get(0);
                    boolean certain = LAYER_L1.equals(h.layer) || LAYER_L1B.equals(h.layer);
                    return Trans.get(ctx, certain ? R.string.crash_verdict_certain_fmt
                            : R.string.crash_verdict_one_fmt, h.name, evidence(ctx, h));
                }
                StringBuilder names = new StringBuilder();
                for (Hit h : v.hits) {
                    // ⚠️ 分隔符留在 Java：资源里的前后导空白会被 aapt2 剥掉（REF §五 那条）
                    if (names.length() > 0) names.append(", ");
                    names.append(Trans.get(ctx, R.string.crash_verdict_name_fmt, h.name));
                }
                // ★ 并列时**每个**都要给出自己的依据（"是谁"+"凭什么"仍然同一屏）——
                //   只报名字等于把用户丢回原始堆栈里自己找。
                StringBuilder sb = new StringBuilder();
                sb.append(Trans.get(ctx, R.string.crash_verdict_multi_fmt, v.hits.size(), names.toString()));
                for (Hit h : v.hits) {
                    sb.append('\n').append(Trans.get(ctx, R.string.crash_verdict_hit_line_fmt,
                            h.name, evidence(ctx, h)));
                }
                sb.append('\n').append(Trans.get(ctx, R.string.crash_verdict_multi_note));
                return sb.toString();
            }
            default: {
                StringBuilder sb = new StringBuilder();
                for (Hit h : v.hits) {
                    sb.append(Trans.get(ctx, R.string.crash_verdict_weak_fmt, h.name, evidence(ctx, h)))
                      .append('\n');
                }
                sb.append(Trans.get(ctx, R.string.crash_verdict_none));
                if (v.dexScanned == 0) sb.append('\n').append(Trans.get(ctx, R.string.crash_verdict_none_nomods));
                else sb.append('\n').append(Trans.get(ctx, R.string.crash_verdict_none_hint));
                return sb.toString();
            }
        }
    }

    /** 一条依据的人读整串（`很可能是「A」—— 它的 classes.dex 里有 X`） */
    static String evidence(Context ctx, Hit h) {
        if (h == null) return "";
        String base;
        if (LAYER_L1.equals(h.layer)) base = Trans.get(ctx, R.string.crash_ev_l1);
        else if (LAYER_L1B.equals(h.layer)) base = Trans.get(ctx, R.string.crash_ev_l1b_fmt, h.evidence);
        else if (LAYER_FRAME.equals(h.layer)) base = Trans.get(ctx, R.string.crash_ev_frame_fmt, h.evidence);
        else base = Trans.get(ctx, R.string.crash_ev_dex_fmt, h.evidence);
        if (h.known && !h.inReport) base = Trans.get(ctx, R.string.crash_ev_not_loaded_fmt, base);
        return base;
    }

    /**
     * 给 dev 口/自检用的**机器可读**一行摘要（不本地化：它是给维护者看的证据）。
     * 例：`kind=MOD hits=stealth-path(L1/w3) needles=14 dex=0`
     */
    static String debugLine(Report r, Verdict v) {
        StringBuilder sb = new StringBuilder();
        sb.append("kind=").append(kindName(v == null ? KIND_NONE : v.kind));
        if (r != null) {
            sb.append(" version=").append(r.version.isEmpty() ? "?" : r.version);
            sb.append(" mods=").append(r.mods.isEmpty() ? (r.vanilla ? "vanilla" : "-") : r.mods.toString());
            sb.append(" chain=").append(r.chain.size()).append(" frames=").append(r.frameCount);
            sb.append(" needles=").append(r.needles.size());
            if (!r.topType().isEmpty()) {
                sb.append(" top=").append(r.topType());
                if (!r.topMessage().isEmpty()) sb.append(": ").append(shorten(r.topMessage(), 90));
            }
        }
        if (v != null) {
            sb.append(" hits=");
            if (v.hits.isEmpty()) sb.append("-");
            for (int i = 0; i < v.hits.size(); i++) {
                Hit h = v.hits.get(i);
                if (i > 0) sb.append(',');
                sb.append(h.name).append('(').append(h.layer).append("/w").append(h.weight)
                  .append('/').append(h.evidence).append(')');
            }
            sb.append(" dex=").append(v.dexScanned);
        }
        return sb.toString();
    }

    private static String kindName(int kind) {
        switch (kind) {
            case KIND_MOD: return "MOD";
            case KIND_VANILLA: return "VANILLA";
            case KIND_LAUNCHER: return "LAUNCHER";
            case KIND_BROKEN: return "BROKEN";
            default: return "NONE";
        }
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * **完整判据**：先跑便宜层（L1/L1b/L2a），没结论才去扫 dex。
     * ⚠️ **必须在后台线程调用**（认不出时会读每个模组的 `classes.dex`）。
     * @param slot 用哪个槽的模组表来对名字（L2a/L3 全靠它）；null ⇒ 当前槽
     */
    static Verdict judgeFull(Context ctx, Report r, String slot) {
        Verdict v = judge(r, null, null);
        if (r == null || r.empty || r.launcherOwn) return v;
        List<Mods.Info> mods = null;
        try {
            mods = Mods.scan(ctx, slot == null ? Data.currentSlot(ctx) : slot).mods;
        } catch (Throwable t) {
            mods = null;
        }
        if (mods == null) return v;
        if (v.kind == KIND_MOD) {
            // 🔴 便宜层（L1/L1b）已经点名了，但**名字还没跟模组表对上**：
            //   报告里 `Error loading mod <internalName>` 只有内部名，而界面该显示 `displayName`
            //   （真机实测：不补这一步，模组页那行会写「depuser」而不是「MDT Dep Probe」）。
            //   ⚠️ 这一步**只读模组表**（各自的 mod.hjson），**不扫 dex** —— 贵的那层留给下面那条路。
            for (Hit h : v.hits) resolve(h, mods, r);
            return v;
        }
        return judge(r, mods, DEX);
    }

    /**
     * 分析某个槽里**最新一份**崩溃报告（给"模组页那一行结论"这类**只显示一句**的调用点）。
     *
     * ★ 为什么按**文件名**排序：崩溃报告的名字就是写入时的毫秒时间戳 ⇒ 字典序 = 时间序
     *   （与 {@link LogActivity} 的 chips 同一口径）。
     * ⚠️ 必须在**后台线程**调用；没有报告 / 读不出来一律返回 null（调用方把那一行藏起来）。
     */
    static Verdict analyzeNewest(Context ctx, String slot) {
        java.io.File root = Data.dirOf(ctx, slot);
        java.io.File dir = root == null ? null : new java.io.File(root, "crashes");
        java.io.File[] fs = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
        if (fs == null || fs.length == 0) return null;
        java.io.File newest = null;
        for (java.io.File f : fs) {
            if (f == null || !f.isFile()) continue;
            if (newest == null || f.getName().compareTo(newest.getName()) > 0) newest = f;
        }
        return analyzeReport(ctx, newest, slot);
    }

    /**
     * 分析**指定那一份**报告（{@link #analyzeNewest} 与"崩了马上提示"共用这一条路）。
     * ⚠️ 必须在**后台线程**调用；读不出来返回 null。
     */
    static Verdict analyzeReport(Context ctx, java.io.File f, String slot) {
        if (f == null || !f.isFile() || f.length() > 4L * 1024 * 1024) return null;
        String body;
        try {
            body = Util.readText(f);
        } catch (Throwable t) {
            return null;
        }
        return judgeFull(ctx, parse(body), slot);
    }

    /**
     * 结论的**一行版**（Toast / 标题这类"只给一句"的地方用）：取 {@link #text} 的第一行。
     * ★ 与 {@link #text} 同一个来源 ⇒ 不会出现"弹窗说 A、日志页说 B"。
     */
    static String brief(Context ctx, Verdict v) {
        String t = text(ctx, v);
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).trim();
    }

    /**
     * 归因器**点名**的那几个模组的**内部名**（小写）；认不出 / 原版崩 / 启动器自己的报告 ⇒ 空表。
     *
     * ★ 为什么要有它：游戏自己在 `settings` 里写的 `mod-&lt;名字&gt;-failed` 是**全槽级**的
     *   （加载期一崩，它把本槽全部模组都标上，那是它"下次整槽跳过"的机制）⇒ 界面按行显示那句
     *   会被读成"这个模组出过错"（2026-10-08 用户真机反馈：「为什么无关模组也被判定到了」）。
     *   按行只许标**这里返回的人**；全槽级那句话单独说一次。
     *
     * ⚠️ 并列（报告指向多个模组）时**全部返回** —— 那正是"分不出是谁"的诚实表达，
     *   界面上每个都标一句"很可能与它有关"，而不是随便挑一个。
     */
    static List<String> blamedInternals(Verdict v) {
        List<String> out = new ArrayList<String>();
        if (v == null || v.kind != KIND_MOD || v.hits == null) return out;
        for (Hit h : v.hits) {
            if (h == null) continue;
            String s = h.internal == null || h.internal.isEmpty() ? h.name : h.internal;
            if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    // ══ dev 口 ═════════════════════════════════════════════════════════════

    /**
     * **dev 口**（`--es dev_crash_analyze 1`）：把某个槽里每一份崩溃报告都分析一遍，
     * 逐份给出「机器可读摘要 + 结论文案 + 耗时」，落到 `hub/report-devtool.txt`。
     *
     * ★ 为什么要有它：① 归因的判据必须在**真机上的真报告**（ART 的措辞与 HotSpot 未必同款）
     *   上过一遍 —— 报告比弹窗可读、可 diff；② 结论文案的排版（省略号/换行/并列）只有看到
     *   真串才知道好不好看；③ dex 扫描的耗时只有真机量得准。
     * ⚠️ 这是本类里**唯一**碰 IO/Context 的方法（{@link #parse}/{@link #judge} 保持纯函数）。
     */
    static String devReport(Context ctx, String slot) {
        StringBuilder sb = new StringBuilder();
        java.io.File root = Data.dirOf(ctx, slot);
        java.io.File dir = root == null ? null : new java.io.File(root, "crashes");
        // ⚠️ 这份报告是**给维护者看的**（走 dev 口、落 report-devtool.txt），所以标签一律 ASCII：
        //   中文文案要进 res/（或进 tools/i18n-java-budget.txt 的台账），而这里没有翻译价值。
        sb.append("slot = ").append(slot).append('\n');
        sb.append("dataRoot = ").append(root == null ? "?" : root.getAbsolutePath()).append('\n');
        sb.append("crashes  = ").append(dir == null ? "?" : dir.getAbsolutePath()).append('\n');
        java.io.File[] fs = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
        if (fs == null || fs.length == 0) {
            sb.append("\n(no crash report)\n");
            return sb.toString();
        }
        java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        List<Mods.Info> mods = null;
        int dexMods = 0;
        try {
            mods = Mods.scan(ctx, slot).mods;
            for (Mods.Info m : mods) if (m != null && m.hasClassesDex) dexMods++;
        } catch (Throwable t) {
            sb.append("mods scan failed: ").append(t).append('\n');
        }
        sb.append("mods = ").append(mods == null ? "?" : String.valueOf(mods.size()))
          .append(" (").append(dexMods).append(" with classes.dex)\n");
        long t0 = System.currentTimeMillis();
        for (java.io.File f : fs) {
            if (!f.isFile()) continue;
            sb.append("\n-- ").append(f.getName())
              .append(" (").append(f.length()).append(" B) --\n");
            long a = System.currentTimeMillis();
            String body;
            try {
                body = f.length() > 4L * 1024 * 1024 ? null : Util.readText(f);
            } catch (Throwable t) {
                body = null;
            }
            Report parsed = parse(body);
            Verdict v = judge(parsed, mods, DEX);
            long b = System.currentTimeMillis();
            sb.append("  ").append(debugLine(parsed, v)).append('\n');
            sb.append("  verdict: ").append(text(ctx, v).replace("\n", "\n           ")).append('\n');
            sb.append("  took ").append(b - a).append(" ms\n");
        }
        sb.append("\ntotal ").append(System.currentTimeMillis() - t0).append(" ms\n");
        return sb.toString();
    }

    /**
     * **dev 口**（`--es dev_crash_corpus <目录>`）：把一个目录里的语料**逐份跑一遍**。
     *
     * ★ 为什么值得有：语料库（40 条异常签名 + 六十多份报告）是**会长**的，而判据改动
     *   （新措辞、新权重）只靠自检里那几份夹具**覆盖不到全量** —— 这一遍要看三件事：
     *   ① **解析不抛**（任何一份喂进去都不许把分析器打崩）；
     *   ② **该点名的不许认不出**（报告里明明有 `Likely Cause` / `Error loading mod`）；
     *   ③ **不该点名的不许点名**（原版崩、启动器自己的报告、纯日志片段）。
     *   逐份给 `kind / 链节数 / 针数 / 命中`，末尾给分类汇总与耗时 ⇒ 可以直接跟
     *   "我按原文人工判断的期望"对表（那一列在语料片里写着）。
     * ⚠️ 与 {@link #devReport} 一样，这是本类里**碰 IO/Context** 的方法之一。
     */
    static String corpusReport(Context ctx, String dirPath, String slot) {
        StringBuilder sb = new StringBuilder();
        sb.append("dir = ").append(dirPath).append('\n');
        sb.append("slot = ").append(slot == null ? "?" : slot).append('\n');
        java.io.File dir = dirPath == null ? null : new java.io.File(dirPath);
        java.io.File[] fs = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
        if (fs == null || fs.length == 0) {
            sb.append("\n(no fixture; put *.txt here)\n");
            return sb.toString();
        }
        java.util.Arrays.sort(fs, new java.util.Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        List<Mods.Info> mods = null;
        int dexMods = 0;
        try {
            mods = Mods.scan(ctx, slot == null ? Data.currentSlot(ctx) : slot).mods;
            for (Mods.Info m : mods) if (m != null && m.hasClassesDex) dexMods++;
        } catch (Throwable t) {
            sb.append("mods scan failed: ").append(t).append('\n');
        }
        sb.append("mods = ").append(mods == null ? "?" : String.valueOf(mods.size()))
          .append(" (").append(dexMods).append(" with classes.dex)")
          .append("  -- L2a/L3 can only fire when the slot really has that mod\n\n");
        int[] byKind = new int[5];
        List<String> failures = new ArrayList<String>();
        List<String> suspects = new ArrayList<String>();   // 该点名却认不出
        List<String> falsePos = new ArrayList<String>();   // 不该点名却点名
        long t0 = System.currentTimeMillis();
        int n = 0;
        for (java.io.File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".txt")) continue;
            n++;
            String body = null;
            Report parsed = null;
            Verdict v = null;
            try {
                body = f.length() > 4L * 1024 * 1024 ? null : Util.readText(f);
                parsed = parse(body);
                v = judge(parsed, mods, DEX);
            } catch (Throwable t) {
                failures.add(f.getName() + " -> " + t);
                sb.append("!! ").append(f.getName()).append(" THREW ").append(t).append('\n');
                continue;
            }
            byKind[v.kind]++;
            sb.append(String.format("%-26s kind=%-8s chain=%d needles=%-3d %s%n",
                    f.getName(), kindName(v.kind), parsed.chain.size(), parsed.needles.size(),
                    debugLine(null, v).replace(" dex=", " dex=")));
            sb.append("    ").append(text(ctx, v).replace("\n", "\n    ")).append('\n');
            // ② 该点名却认不出：报告头里有游戏自己的归因证据，但结论是 NONE
            boolean hasEvidence = !parsed.likelyName.isEmpty() || !parsed.likelyInternal.isEmpty()
                    || findErrorLoadingMod(parsed) != null;
            if (hasEvidence && v.kind != KIND_MOD) {
                suspects.add(f.getName() + " (report has Likely Cause / Error loading mod but no hit)");
            }
            // ③ 不该点名却点名：模组一行是 none (vanilla)，或本来就是启动器自己的报告
            if (v.kind == KIND_MOD && (parsed.vanilla || parsed.launcherOwn)) {
                falsePos.add(f.getName());
            }
        }
        sb.append("\ntotal ").append(System.currentTimeMillis() - t0).append(" ms for ")
          .append(n).append(" fixtures\n");
        sb.append("by kind: NONE=").append(byKind[KIND_NONE])
          .append(" MOD=").append(byKind[KIND_MOD])
          .append(" VANILLA=").append(byKind[KIND_VANILLA])
          .append(" LAUNCHER=").append(byKind[KIND_LAUNCHER])
          .append(" BROKEN=").append(byKind[KIND_BROKEN]).append('\n');
        sb.append("threw = ").append(failures.size()).append('\n');
        for (String s : failures) sb.append("  !! ").append(s).append('\n');
        sb.append("missed (evidence in report but no hit) = ").append(suspects.size()).append('\n');
        for (String s : suspects) sb.append("  ?? ").append(s).append('\n');
        sb.append("false positive (named a mod it should not) = ").append(falsePos.size()).append('\n');
        for (String s : falsePos) sb.append("  !! ").append(s).append('\n');
        return sb.toString();
    }
}
