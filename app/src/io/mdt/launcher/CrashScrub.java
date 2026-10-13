package io.mdt.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「脱敏日志 / 崩溃报告」的**文本剥离器**（第 142 轮）。★ **纯 Java**（不 import Android）
 * —— 与 {@link SettingsBin} / {@link SharePack} / {@link MsavMeta} 同一条路子：
 * 能在 PC 上单独编译，拿**真实的崩溃报告语料**直接断言，不需要设备。
 *
 * ── 为什么要它（起因）─────────────────────────────────────────────────────
 * 2026-10-13 用户：「说实话应该搞一个脱敏崩溃报告导出（倒是隐私泄露相对还好）」——
 * 这句判断是对的：{@link LogActivity} 那条「导出全部日志」把**游戏日志 + 六份启动器报告 +
 * 全部崩溃报告**拼成**一个文件**交给用户，而那正是报 bug 时会**发出去**的东西。
 * 实测（设备上真实的那一份，只读扫描）：里面 **21 处绝对路径**（其中一条带着用户自己起的槽名
 * `slot-模组演示`）、表头里的**设备型号**、游戏报告头的 **GL / 驱动串**（Adreno 735 + 驱动版本 +
 * GIT 号），以及 `/data/app/...apk` 这类包路径。
 *
 * ── 两类剥离（★ 本类的核心设计）──────────────────────────────────────────
 * <ol>
 *   <li><b>已知值逐字替换</b>（{@link Secrets}）：昵称、服务器地址、登录身份 ——
 *     这些值**我们本来就存着**（本槽的 `settings.bin`：`name` / `ip` / `usid-*` / `uuid`，
 *     外加 `server_list.json` 里的地址）⇒ 拿确切的字符串去替换，**不靠正则猜**
 *     （{@link SharePack} 那一轮"拿真实 usid 反搜 414 份文件"是同一种做法）。
 *     昵称尤其如此 —— 正则永远猜不出"哪个词是人名"。</li>
 *   <li><b>通用模式替换</b>：数据根前缀、其余绝对路径、IPv4、邮箱、GL 驱动串。</li>
 * </ol>
 *
 * ── 三条刻意的取舍（每条都由自检的"反方向"守着）─────────────────────────
 * ① **保留排障要用的结构**：路径不是整条抹掉，而是"前缀换占位符、后面的相对结构留着"
 *    —— `/storage/…/slot-张三/saves/x.msav` 变成 `&lt;ext-data&gt;/slot-&lt;slot&gt;/saves/x.msav`；
 *    IPv4 **只换地址不换端口**（`1.2.3.4:6567` → `&lt;server&gt;:6567`），
 *    否则"连不上服务器"这类问题就没法看了；GL 那一行**留下能力档**（`GLES 3.2.0`）、
 *    只吃掉厂商 / 型号 / 驱动版本 / GIT 号。
 * ② **不碰归因要用的东西**：异常类名、`Caused by` 链、模组清单（`Mods: …`）、包名、
 *    API 级别、ABI、页大小一律原样 —— 用户选的正是"模组清单留下"这一档（归因 F23 全靠它）。
 * ③ **不做"猜"出来的类别**：不匹配手机号、不匹配裸 `主机:端口`
 *    （`github.com` 会被误伤；而真正的服务器地址已经在①里逐字覆盖了）。
 *
 * ⚠️ **占位符一律 ASCII**：它们出现在**导出给别人看的文本**里，不是界面文案
 *   （界面文案照旧走 {@link Trans} 与资源）⇒ 不随用户语言变，
 *   也让本类保持"零中文字面量"（`SRC-02` 台账不必动）。
 */
public final class CrashScrub {

    // ── 占位符（**唯一一处**定义；自检按它们断言）────────────────────────────
    /** 普通外部存储路径 */
    public static final String P_PATH = "<path>";
    /** 我们自己的**外部**数据根（`/storage/emulated/0/Android/data/<pkg>`） */
    public static final String P_EXT = "<ext-data>";
    /** 我们自己的**内部**数据根（`/data/user/0/<pkg>`、`/data/data/<pkg>`） */
    public static final String P_INT = "<int-data>";
    /** 槽目录名（`slot-<名字>` 里的那个名字） */
    public static final String P_SLOT = "<slot>";
    /** 服务器地址（IP / 域名；**端口保留**） */
    public static final String P_SERVER = "<server>";
    /** 玩家昵称 */
    public static final String P_PLAYER = "<player>";
    /** 登录身份（`usid-*` / `uuid` 的**值**） */
    public static final String P_ID = "<id>";
    /** 邮箱 */
    public static final String P_EMAIL = "<email>";
    /** 显卡 / 驱动串（游戏报告头那一行里"厂商往右"的部分） */
    public static final String P_GL = "<gl-driver>";
    /** 设备型号（**表头**用；本类不猜，由 {@link #deviceFor} 换） */
    public static final String P_DEVICE = "<device>";

    /** 包名（用来认"这是我们的路径"） */
    private static final String PKG = "io.mdt.launcher";

    // ── 通用模式 ──────────────────────────────────────────────────────────
    //
    // ⚠️ 每一条都要能"算出 false"：自检既喂该命中的，也喂看着像但不该命中的
    //   （`0.1` / `160.5` 这种版本号不许被当成 IP；`V@0762.46` 不许被当成邮箱）。

    /**
     * 我们自己的**外部**数据根。
     * ⚠️ **不吞尾斜杠** —— 吞了它，后面的 `/slot-x` 就会拼成 `<ext-data>slot-x`（少一个斜杠）。
     */
    private static final Pattern RX_EXT_ROOT = Pattern.compile(
            "(?:/storage/[^\\s:/]+/[^\\s:/]+|/sdcard)/Android/(?:data|media)/"
                    + Pattern.quote(PKG));

    /** 我们自己的**内部**数据根（只认我们自己的包名） */
    private static final Pattern RX_INT_ROOT = Pattern.compile(
            "/data/(?:user/\\d+|data)/" + Pattern.quote(PKG));

    /** 槽目录：`slot-<名字>`（名字是用户起的，可能带人名 / 项目名） */
    private static final Pattern RX_SLOT = Pattern.compile("slot-([^/\\\\\\s\"'()\\[\\],;]+)");

    /** 其它绝对路径（`/storage/…` / `/sdcard/…`：用户的下载目录之类 ⇒ **整条**换掉） */
    private static final Pattern RX_ABS = Pattern.compile("(?:/storage|/sdcard)/[^\\s\"'()\\[\\],;]+");

    /** Windows 用户目录（桌面版报告里会有 `C:\\Users\\<账户名>\\…`） */
    private static final Pattern RX_WIN = Pattern.compile(
            "[A-Za-z]:\\\\{1,2}Users\\\\{1,2}[^\\s\"'()\\[\\],;]*");

    /** IPv4 —— 只换地址、**端口留着** */
    private static final Pattern RX_IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /**
     * 邮箱。★ TLD 必须是**字母**（≥2 位）—— 否则 `V@0762.46`（GL 版本串）会被误伤；
     * 这条是照真语料定的（第一版就是在这个样本上翻的车）。
     */
    private static final Pattern RX_EMAIL = Pattern.compile(
            "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)*\\.[A-Za-z]{2,}");

    /**
     * GL / 驱动那一行。★ 分两段抓：`head` = 第一个 `/` 之前（`GLES 3.2.0` 这种**能力档**，
     * 留着有用），其余（厂商 / 型号 / 驱动版本 / GIT 号）吃掉。
     *
     * <p>🔴 **一行两种写法，都是真机产物教的**（第一版只认了前一种 ⇒ 脱敏档里还剩 1 处 `Adreno`）：
     * <pre>
     *   崩溃报告头：GL Version: GLES 3.2.0 / Qualcomm / Adreno (TM) 735 / …
     *   游戏日志行：[I] [GL] Version: GLES 3.2.0 / Qualcomm / Adreno (TM) 735 / …
     * </pre>
     * ⚠️ 仍然只在**行首**匹配（允许一个 `[级别]` 前缀）—— 正文中间出现同名字样不该被吃掉；
     *   而且**不许**退化成裸 `Version:`（报告头里还有 `Java Version:` / `Version: unknown build 0`）。
     */
    private static final Pattern RX_GL = Pattern.compile(
            // ⚠️ 必须是 `GL Version:` 或 `[GL] Version:` 两种之一 ——
            //   两个前缀都写成"可选"会退化成 `^Version:`，把报告头的
            //   `Version: unknown build 0` 也吃掉（自检的反方向夹具当场抓住过）。
            "^(\\[[A-Za-z]\\][ \\t]*)?(\\[GL\\][ \\t]*|GL[ \\t]+)Version:"
                    + "[ \\t]*([^/\\r\\n]*?)[ \\t]*(?:/.*)?$", Pattern.MULTILINE);

    private CrashScrub() {}

    // ── 已知值 ────────────────────────────────────────────────────────────

    /**
     * 「本来就知道的敏感值」——从本槽的 `settings.bin`（+ `server_list.json`）里取。
     *
     * <p>★ 为什么这比正则靠谱：这些值**我们手里就有确切的字符串**。
     *   {@link SharePack} 那一轮已经证明过这条路（拿真实 `usid-*` 反搜 414 份文件）。
     */
    public static final class Secrets {
        /** 玩家昵称（`name`） */
        public final List<String> playerNames = new ArrayList<>();
        /** 服务器地址（`ip` + `server_list.json` 里的 address） */
        public final List<String> addresses = new ArrayList<>();
        /** 登录身份的值（`usid-*` / `uuid`） */
        public final List<String> ids = new ArrayList<>();

        public boolean isEmpty() {
            return playerNames.isEmpty() && addresses.isEmpty() && ids.isEmpty();
        }

        public int size() {
            return playerNames.size() + addresses.size() + ids.size();
        }

        /**
         * 从解析好的设置里取（键名判据**照抄** {@link SharePack#isSensitive} 那一档，
         * 外加 `name` —— 昵称在"分享包"里是**有意保留**的，但在日志里出现就是泄露）。
         *
         * @param v 本槽的 `settings.bin`（`null` ⇒ 什么都不加）
         */
        public static Secrets from(SettingsBin.Values v) {
            Secrets s = new Secrets();
            if (v == null) return s;
            for (Map.Entry<String, Object> e : v.all().entrySet()) {
                String k = e.getKey();
                Object val = e.getValue();
                if (!(val instanceof String)) continue;
                String str = ((String) val).trim();
                if (str.isEmpty()) continue;
                if ("name".equals(k)) s.playerNames.add(str);
                else if ("ip".equals(k)) s.addresses.add(hostOf(str));
                else if (k.startsWith("usid-") || "uuid".equals(k)) s.ids.add(str);
            }
            s.normalize();
            return s;
        }

        /**
         * 从 `server_list.json` 的**原始文本**里取地址。
         *
         * <p>★ 为什么不解析 JSON：那个文件的结构是固定的
         *   `{"name":…,"address":["host:port",…]}`，而我们只要其中"长得像地址"的那些字符串
         *   ⇒ 抽引号里 `host:port` 形状的值就够了（只为这个引入一个 JSON 解析器，代价大于收益）。
         */
        public void addAddressesFromJson(String raw) {
            if (raw == null || raw.isEmpty()) return;
            Matcher m = Pattern.compile("\"([^\"\\s]{3,}):(\\d{2,5})\"").matcher(raw);
            while (m.find()) {
                addresses.add(m.group(1));
            }
            normalize();
        }

        /**
         * 地址只留**主机**那一半（`frp-ten.com:64934` → `frp-ten.com`）。
         * ★ 这样替换出来的结果是 `&lt;server&gt;:64934` —— 与 IPv4 那条规则**同款**
         *   （端口是排障要用的："连不上服务器"得看得出连的是哪个端口）。
         */
        private static String hostOf(String addr) {
            if (addr == null) return null;
            String a = addr.trim();
            int i = a.lastIndexOf(':');
            if (i > 0 && i < a.length() - 1) {
                String port = a.substring(i + 1);
                boolean digits = true;
                for (int k = 0; k < port.length(); k++) {
                    if (!Character.isDigit(port.charAt(k))) { digits = false; break; }
                }
                if (digits) return a.substring(0, i);
            }
            return a;
        }

        /** 去重 + **按长度倒序**（长的先替换：短的是长的前缀时，先换短的会把长的切坏） */
        private void normalize() {
            dedup(playerNames);
            dedup(addresses);
            dedup(ids);
        }

        private static void dedup(List<String> xs) {
            LinkedHashSet<String> set = new LinkedHashSet<>(xs);
            xs.clear();
            xs.addAll(set);
            Collections.sort(xs, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
        }
    }

    // ── 结果 ──────────────────────────────────────────────────────────────

    /** 一次剥离的结果（计数给界面与自检；`unchanged()` 就是"两面断言"的那一面） */
    public static final class Result {
        /**
         * 剥完的文本。
         * 🔴 **不是**输入 —— 第一版这里写成 `final` 且照抄了输入，后果有两层：
         *   ① 判据表（该命中的一个都没命中）全红；② 更坏的是「结论不变」那条**白过** ——
         *   它拿**没剥过**的副本去和原件比，当然逐字相同（自检 ③ 的元断言只挡住了 "什么都没做"，
         *   挡不住"返回了原件"）。这条注释留着，别再把 text 改成 final。
         */
        public String text;
        /** 总替换处数 */
        public int hits;
        public int paths;
        public int slots;
        public int servers;
        public int players;
        public int ids;
        public int emails;
        public int gl;

        Result(String text) {
            this.text = text;
        }

        /** 一个字节都没改（= 这段文本里本来就没有敏感内容） */
        public boolean unchanged() {
            return hits == 0;
        }
    }

    // ── 主入口 ────────────────────────────────────────────────────────────

    /** 剥离（`secrets` 可以为 `null` = 只做通用模式那一半） */
    public static Result scrub(String text, Secrets secrets) {
        Result r = new Result(text == null ? "" : text);
        if (text == null || text.isEmpty()) return r;
        String t = text;

        // ① GL 驱动串（整行）—— 放最前面：它里面有"看着像版本号/地址"的片段，
        //    先整行处理掉，后面的规则就不会在它身上乱动。
        t = scrubGl(t, r);

        // ② 已知值：**长的先换**（Secrets 已排好序）
        if (secrets != null) {
            t = replaceKnown(t, secrets.ids, P_ID, r, COUNT_ID);
            t = replaceKnown(t, secrets.addresses, P_SERVER, r, COUNT_SERVER);
            t = replaceKnown(t, secrets.playerNames, P_PLAYER, r, COUNT_PLAYER);
        }

        // ③ 路径：先认"我们自己的数据根"（比通用绝对路径更具体），再认槽名，最后兜底
        t = replaceAll(t, RX_EXT_ROOT, P_EXT, r, COUNT_PATH);
        t = replaceAll(t, RX_INT_ROOT, P_INT, r, COUNT_PATH);
        t = replaceAll(t, RX_WIN, P_PATH, r, COUNT_PATH);
        t = replaceAll(t, RX_SLOT, "slot-" + P_SLOT, r, COUNT_SLOT);
        t = replaceAll(t, RX_ABS, P_PATH, r, COUNT_PATH);

        // ④ 地址与邮箱
        t = replaceAll(t, RX_IPV4, P_SERVER, r, COUNT_SERVER);
        t = replaceAll(t, RX_EMAIL, P_EMAIL, r, COUNT_EMAIL);

        r.text = t;          // 🔴 必须写回（见 Result.text 的注释）
        return r;
    }

    /**
     * 表头里那一行**环境信息**的脱敏（设备型号）。
     * ⚠️ 与文本剥离分开：那是**我们自己的字段**（{@link LogActivity#exportHeader}），
     * 直接换值比事后拿字符串去猜可靠。
     */
    public static String deviceFor(boolean scrub, String manufacturerModel) {
        return scrub ? P_DEVICE : manufacturerModel;
    }

    // ── 内部：替换（**计数与拼接只在这两处**）────────────────────────────────

    private static final int COUNT_PATH = 1, COUNT_SLOT = 2, COUNT_SERVER = 3,
            COUNT_PLAYER = 4, COUNT_ID = 5, COUNT_EMAIL = 6;

    private static void bump(Result r, int which, int n) {
        if (n <= 0) return;
        r.hits += n;
        switch (which) {
            case COUNT_PATH:   r.paths += n; break;
            case COUNT_SLOT:   r.slots += n; break;
            case COUNT_SERVER: r.servers += n; break;
            case COUNT_PLAYER: r.players += n; break;
            case COUNT_ID:     r.ids += n; break;
            case COUNT_EMAIL:  r.emails += n; break;
            default: break;
        }
    }

    /** 正则替换（`quoteReplacement`：占位符里若出现 `$` / `\` 也不会被当组引用） */
    private static String replaceAll(String t, Pattern p, String to, Result r, int which) {
        Matcher m = p.matcher(t);
        if (!m.find()) return t;
        int n = 0;
        StringBuffer sb = new StringBuffer(t.length());
        do {
            n++;
            m.appendReplacement(sb, Matcher.quoteReplacement(to));
        } while (m.find());
        m.appendTail(sb);
        bump(r, which, n);
        return sb.toString();
    }

    /** GL 那一行：留下能力档（`GLES 3.2.0`），吃掉厂商往右的一切 */
    private static String scrubGl(String t, Result r) {
        Matcher m = RX_GL.matcher(t);
        if (!m.find()) return t;
        int n = 0;
        StringBuffer sb = new StringBuffer(t.length());
        do {
            // ⚠️ 前缀（`[I] ` / `[GL] ` / `GL `）**原样留着** —— 自检抓到过第一版
            //   把它吃掉：`[I] [GL] Version: …` 变成 `GL Version: …`，
            //   读日志时级别与标签都没了。只换 Version 之后那串驱动信息。
            String prefix = m.group(1) == null ? "" : m.group(1);
            String tag = m.group(2) == null ? "GL " : m.group(2);
            String head = m.group(3) == null ? "" : m.group(3).trim();
            String rep = prefix + tag + "Version: "
                    + (head.isEmpty() ? P_GL : head + " " + P_GL);
            n++;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        } while (m.find());
        m.appendTail(sb);
        r.gl += n;
        r.hits += n;
        return sb.toString();
    }

    /**
     * 逐个**已知值**做字面替换。
     * ★ 用 `String.replace`（字面）而不是正则 —— 值里可能有 `.` `-` `+` `/` 这类元字符
     *   （base64 的 usid、`frp-ten.com:64934` 这种地址）。
     *
     * <p>🔴 **加了一道标识符护栏**（自检当场抓出来的）：昵称完全可能撞上正文里本来就有的字符串
     *   —— 夹具里就把昵称设成了 `NullPointerException`，而正文里
     *   `java.lang.NullPointerException` 是**异常类名** ⇒ 上一版把它换成了
     *   `java.lang.&lt;player&gt;`，那是**把归因弄坏**（{@link CrashAnalysis} 的异常链就是靠它）。
     *   于是：命中处**紧邻标识符字符**（`[A-Za-z0-9_$]`）或**紧邻 `.`** 时，这一处**不换**。
     *   ⚠️ 代价如实说：昵称若只出现在类名那种位置里，就**不会**被替换 ——
     *   但那种情况说明这串字符本来就是报告的一部分，不是"泄露"。
     */
    private static String replaceKnown(String t, List<String> values, String to,
                                       Result r, int which) {
        for (String v : values) {
            if (v == null || v.length() < 3) continue;   // 1~2 字符的值替换会误伤一大片
            int from = 0, n = 0;
            StringBuilder sb = null;
            int at;
            while ((at = t.indexOf(v, from)) >= 0) {
                if (bareStanding(t, at, v)) {
                    if (sb == null) sb = new StringBuilder(t.length() + 16).append(t, 0, at);
                    else sb.append(t, from, at);
                    sb.append(to);
                    n++;
                    from = at + v.length();
                } else {
                    if (sb != null) sb.append(t, from, at + v.length());
                    from = at + v.length();
                }
            }
            if (sb != null) {
                sb.append(t, from, t.length());
                t = sb.toString();
            }
            bump(r, which, n);
        }
        return t;
    }

    /**
     * 这一处命中"站得住"吗（= 不粘在更长的标识符里、也不在全限定名里）。
     * ★ 判据只此一处，{@link #replaceKnown} 与自检用的是同一条。
     */
    static boolean bareStanding(String t, int at, String v) {
        char before = at > 0 ? t.charAt(at - 1) : ' ';
        int end = at + v.length();
        char after = end < t.length() ? t.charAt(end) : ' ';
        if (isIdentChar(before) || isIdentChar(after)) return false;
        if (before == '.' || after == '.') return false;
        return true;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
