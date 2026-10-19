package io.mdt.launcher;

import java.util.List;

/**
 * 把一张地图的统计（{@link MapStats.Result}）导成 **JSONL**：**第一行是声明（meta），之后一行一条记录**。
 *
 * <h3>为什么是 JSONL 而不是 CSV</h3>
 * 统计里既有标量（格数）也有小列表（`internals` 合并了哪几个内部名、`floorKinds` 哪几种地板），
 * CSV 只能把它们拍扁成"a|b|c"这种约定，而**约定就得写文档、读的人还得照着拆**；
 * JSONL 一行一个自描述对象，`jq`/pandas/awk 都能直接读，加字段也不会破坏老读者。
 * ⚠️ **一行一条**这件事本身是判据：导出文本里出现的换行只允许是"记录分隔"，
 * 所以名字里的 `\n` / `\r` / `\t` 必须转义（自检有一条专门喂带引号/反斜杠/换行的名字）。
 *
 * <h3>第一行（声明）里放什么</h3>
 * 用户 2026-10-17 点单时提了两个用途：
 * <ol>
 *   <li><b>数据出问题时确定来源</b> —— 启动器版本 + 导出时刻 + 槽名 + 地图身份（名字/来源/大小/sha256）
 *       + 参与统计的内容表（原版行数、模组包数、地图补丁条数）+ 星球；</li>
 *   <li><b>有人发数据时顺便宣传启动器</b> —— {@link #GENERATOR} / {@link #SITE} 与一句
 *       {@link Meta#notice}</li>
 * </ol>
 * ⚠️ 两条**不许**进文件的：
 * <ul>
 *   <li><b>本机路径</b>（`map.where` 对槽内文件是绝对路径）—— 只取文件名 / `包!/条目`；</li>
 *   <li>**设备标识**（序列号 / usid / 邮箱…）—— 这份文件是给人转发的，与分享包同一个纪律。</li>
 * </ul>
 *
 * <p>本类**不碰 Android**（纯 Java）：自检与 PC 探针都能直接喂 {@link MapStats.Result} 断言。</p>
 */
public final class MapStatsExport {

    /** 文件格式版本（**加字段不改它；改口径/改字段含义才升**） */
    public static final String FORMAT = "mdt-mapstats/1";
    /** 生成者（数据字段，**不跟界面语言走** —— 收数据的人要能一眼认出是谁导的） */
    public static final String GENERATOR = "MDT Manager for Android";
    /** 项目地址（宣传位；同时也是"数据有问题往哪报"） */
    public static final String SITE = "https://github.com/GXFQE/mindustry-launcher-android";

    private MapStatsExport() {}

    /**
     * 声明里那些**只有调用方知道**的东西（版本、时间、地图身份、来源）。
     *
     * <p>⚠️ 这里全是**数据**：`map.source` 用 `slot` / `game` / `mod` 这种稳定码，
     * 中文（「本槽」「游戏自带」）留给人看的 `notice` / `name`。</p>
     */
    public static final class Meta {
        /** 导出时刻（ISO-8601，带时区偏移，如 `2026-10-17T16:20:00+08:00`） */
        public String exportedAt = "";
        public String launcherVersion = "";
        public int launcherCode;
        public String launcherPackage = "";
        /** 槽名（**不是**路径） */
        public String slot = "";
        /** 地图名（列表上那一行） */
        public String mapName = "";
        /** `slot` / `game` / `mod` */
        public String mapSource = "";
        /** 槽内文件 = 文件名；包内 = `<容器名>!/<条目>`（**都不含本机路径**） */
        public String mapWhere = "";
        public long mapBytes = -1;
        /** 地图字节的 sha256（**定位它的唯一硬证据**；空 = 没算出来） */
        public String mapSha256 = "";
        /** 存档 `tags.rules` 里的 `planet:`（空 = 没写这个键 = 塞普罗） */
        public String planet = "";
        /** 统计用的版本 APK（文件名）与它的版本名 */
        public String gameApk = "";
        public String gameVersion = "";
        /** 有效内容表行数 */
        public int tableRows = -1;
        /** 参与统计的模组包数（-1 = 没读出来） */
        public int mods = -1;
        /** 游戏这一轮**不加载模组**（上次崩过，`modcrashdisable` 生效） */
        public boolean modsSkipped;
        /** 模组读不出来（只按原版算） */
        public boolean modsFailed;
        /** 声明句（**由调用方按界面语言给**，含项目地址 —— 纯 Java 里不写文案） */
        public String notice = "";

        /** 导出模式：{@link MapStatsExport#MODE_DETAIL} / {@link MapStatsExport#MODE_BRIEF} */
        public int mode = MODE_DETAIL;
        /** 批次里的位置（1 起）与总份数；单张导出 = 1 / 1（由 {@link #jsonlBatch} 填） */
        public int index = 1;
        public int count = 1;
    }

    /** **详细**：全部数据（格数拆开、来源、内部名、认不出的内容、可建造/核心区） */
    public static final int MODE_DETAIL = 0;
    /**
     * **简洁**：只含「能采到」的那些记录（`reachable > 0`），每条只留
     * `kind` / `name` / `reachable`；另外**两种口径都带** `buildable`（可建造区域）与
     * `core`（核心区域）—— 用户 2026-10-17：「我们统计的可建造区域以及核心区域也算」。
     *
     * <p>用户 2026-10-17：「可以加个详细模式（全部数据）和简洁模式（仅含可采的）」→
     * 「**简洁模式只要可采数据**」→「**把 total 删了啊**」→「**简洁模式只有真实数据的啊**」——
     * `total` 是"这层一共多少格"，不是可采数据；核心区没数（`zone == 0` / `skip`）时那一串 0
     * 也不是数据 ⇒ **这两种都不写**。⚠️ 这条是**判据**：简洁模式里出现 `reachable == 0`、
     * 出现 `total`、或出现"没有核心区却写了 core"，都算错（自检有元断言钉着）。</p>
     */
    public static final int MODE_BRIEF = 1;

    /**
     * **一张地图的完整 JSON**（单张导出 = 整份文件；批量 = JSONL 的**一行**，用户 2026-10-17 定的形态）。
     *
     * <p>结构 = **一层平铺**：`{format, generator, site, notice, mode, index, count, launcher,
     * exportedAt, slot, map, content, engine, records:[...]}` —— 每个事实**只出现一次**。
     * ⚠️ 原来这里是"文档头 + `meta` 各带一份 format/generator/site"（用户当场问
     * 「"format"/"generator"/"site"怎么都是一行两个」）；现在 `meta` 那层删掉了，
     * 与 `records` 重复的 `recordCount` / `meta.records` 也一并删掉。</p>
     *
     * <p>`records` 每条都带 `kind`（`ore`/`oreWall`/`floor`/`bonus`/`buildable`/`core`/`unknownContent`）；
     * 详细模式含 `buildable`/`core`/`unknownContent`，简洁模式只有"能采到的"那几条。</p>
     */
    public static String jsonDoc(MapStats.Result r, Meta m) {
        // ★ 两种口径**不是"同一条文档少写几个字段"**：简洁模式是**另一张表**
        //   （用户 2026-10-17：「不要那堆细节，只要用户在真实体验时可以用到的数据」）
        return m.mode == MODE_BRIEF ? briefDoc(r, m) : detailDoc(r, m);
    }

    /**
     * 声明（**两种口径共用一份**）—— 用户 2026-10-17：「声明可以留着（毕竟可以反馈/宣传），
     * 但是可以稍微简洁一些」。
     *
     * <p>只留"出了事能顺着查回去"的那几项：格式版本 · 谁导的 · 项目地址 · 导出时刻 · 哪个槽 ·
     * 地图名 + sha256 + 游戏版本 + 模组数 + 星球。**其余一律不写**：
     * `mode` / `index` / `count` / 包名 / 字节数 / 宽高格数 / 来源路径 / 内容表规模 / 引擎耗时 /
     * `modsSkipped`·`modsFailed` —— 那些是内部记账，用户拿去反馈也用不到。</p>
     */
    private static void header(Obj o, Meta m) {
        o.str("format", FORMAT)
                .str("generator", GENERATOR)
                .str("site", SITE)
                .str("notice", m.notice);
        o.raw("launcher", "{" + Str.of("version", m.launcherVersion)
                + ",\"code\":" + m.launcherCode + "}");
        o.str("exportedAt", m.exportedAt).str("slot", m.slot);
        o.raw("map", "{" + Str.of("name", m.mapName)
                + ",\"sha256\":" + Str.quote(m.mapSha256)
                + ",\"game\":" + Str.quote(gameOf(m))
                + ",\"mods\":" + (m.mods < 0 ? "null" : String.valueOf(m.mods))
                + ",\"planet\":" + Str.quote(m.planet) + "}");
    }

    /** `Mindustry-160.apk 160` 这种一句话的出处（缺哪个就只写另一个） */
    private static String gameOf(Meta m) {
        String apk = m.gameApk == null ? "" : m.gameApk;
        String ver = m.gameVersion == null ? "" : m.gameVersion;
        if (apk.isEmpty()) return ver;
        if (ver.isEmpty()) return apk;
        return apk + " " + ver;
    }

    /** 详细：声明 + **全部字段**的记录（给人核对/存证用） */
    private static String detailDoc(MapStats.Result r, Meta m) {
        Obj o = new Obj();
        header(o, m);
        o.raw("records", recordsJson(r));
        return o.end();
    }

    /**
     * **简洁**：**只要玩家在真实体验里用得上的数字**（用户 2026-10-17：「不要那堆细节，
     * 只要用户在真实体验时可以用到的数据」）。
     *
     * <p>结构 = **瘦身声明**（见 {@link #header}）+ `records`——每种资源**能采到多少格**、
     * **能建多少格**、**核心区多少格 / 最多摆几个核心**。声明用户明确要求留着
     * （「毕竟可以反馈/宣传」），但只留查得回来源的那几项；`records` 里只留玩家真会用到的数。
     * 🔴 记录里不写的（都是"细节"，玩家用不上）：`origin`/`source`/`internals`、`total`、
     * 明露/散落/埋住/优先级、可建造六桶与地板名单、核心区 `usable`/`size`/`basis`/`skip`、
     * 认不出的内容。</p>
     *
     * <p>⚠️ 两条**判据**（自检钉着）：`reachable == 0` 的行不写；核心区没有真实数据
     * （`zone == 0` 或 `skip`）时 `core` 这一条也不写 —— 与界面上"整张卡不出现"同一个判据。</p>
     */
    private static String briefDoc(MapStats.Result r, Meta m) {
        Obj o = new Obj();
        header(o, m);
        StringBuilder sb = new StringBuilder(1024).append('[');
        briefRows(sb, r.ores, "ore");
        briefRows(sb, r.oreWalls, "oreWall");
        briefRows(sb, r.floors, "floor");
        briefRows(sb, r.bonuses, "bonus");
        // 可建造区域：只有**真数**才写（六桶全 0 = 没有结论，别写个 0 冒充数据）
        int bs = buildableSum(r.buildable);
        if (bs > 0) appendJson(sb, new Obj("buildable").num("sum", bs).end());
        if (r.core != null && !r.core.skip && r.core.zone > 0) {
            appendJson(sb, new Obj("core").num("zone", r.core.zone).num("max", r.core.max).end());
        }
        o.raw("records", sb.append(']').toString());
        return o.end();
    }

    /** 简洁模式的一行资源：`kind` / `name` / `reachable`（不可采的不写） */
    private static void briefRows(StringBuilder sb, List<MapStats.Row> rows, String kind) {
        if (rows == null) return;
        for (MapStats.Row x : rows) {
            if (x == null || x.reachable() <= 0) continue;
            appendJson(sb, new Obj(kind).str("name", x.label).num("reachable", x.reachable()).end());
        }
    }

    /**
     * **批量**：一个 JSONL 文件，**一行 = 一张地图的完整 JSON**（与单张导出那份文档**逐字节同构**）。
     *
     * <p>用户 2026-10-17：「我的意思是**每个地图是 jsonl 中的一条**」—— 所以不是"声明块 + 若干行记录"，
     * 而是**一张图一行**：`jq -c` / `grep` 逐行处理时，每行都是一张图的全量数据；
     * `index` / `count` 标出它在批次里的位置（文件少了几行时好定位是哪张）。</p>
     *
     * <p>★ 一行成立的**前提** = 生成的 JSON 里**没有任何裸换行**（名字里的换行已转义成 `\n` 两个字符）——
     * 自检有一条"名字里带换行 ⇒ 行数不变"钉着这个前提。</p>
     *
     * <p>★ `index` / `count` **在这里填**（不交给调用方）—— 批次语义只该有一处实现。</p>
     */
    public static String jsonlBatch(List<MapStats.Result> rs, List<Meta> ms) {
        StringBuilder sb = new StringBuilder(8192);
        int n = rs == null ? 0 : rs.size();
        for (int i = 0; i < n; i++) {
            MapStats.Result r = rs.get(i);
            Meta m = ms == null || i >= ms.size() ? null : ms.get(i);
            if (r == null || m == null) continue;
            m.index = i + 1;
            m.count = n;
            sb.append(jsonDoc(r, m)).append('\n');
        }
        return sb.toString();
    }

    /** **详细**模式的 `records` 数组（全部字段） */
    private static String recordsJson(MapStats.Result r) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('[');
        rowJson(sb, r.ores, "ore");
        rowJson(sb, r.oreWalls, "oreWall");
        rowJson(sb, r.floors, "floor");
        rowJson(sb, r.bonuses, "bonus");
        appendJson(sb, buildableLine(r.buildable));
        // ★ 核心区**只写真实数据**：`zone == 0`（这张图没有 core-zone 地板）或 `skip`（看不见核心 ⇒
        //   算不出来）时不写 —— 写一串 0 / 默认值不是数据（与界面"整张卡不出现"同一判据）。
        if (r.core != null && !r.core.skip && r.core.zone > 0) {
            appendJson(sb, coreLine(r.core));
        }
        if (r.unknowns != null) {
            for (MapStats.Unk u : r.unknowns) {
                if (u == null) continue;
                appendJson(sb, new Obj("unknownContent")
                        .str("name", u.name)
                        .num("ore", u.ore).num("floor", u.floor).num("bonus", u.bonus)
                        .end());
            }
        }
        return sb.append(']').toString();
    }

    /** 往数组里追加一个已拼好的 JSON 对象（第一个不加逗号） */
    private static void appendJson(StringBuilder sb, String json) {
        if (sb.length() > 1) sb.append(',');
        sb.append(json);
    }

    private static String notes(List<MapStats.Note> ns) {
        if (ns == null || ns.isEmpty()) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ns.size(); i++) {
            MapStats.Note n = ns.get(i);
            if (n == null) continue;
            if (b.length() > 1) b.append(',');
            b.append("{\"code\":").append(n.code).append(",\"n\":").append(n.n)
                    .append(",\"n2\":").append(n.n2).append('}');
        }
        return b.append(']').toString();
    }

    // ── 数据行 ────────────────────────────────────────────────────────────

    private static void rowJson(StringBuilder sb, List<MapStats.Row> rows, String kind) {
        if (rows == null) return;
        for (MapStats.Row x : rows) {
            if (x == null) continue;
            Obj o = new Obj(kind);
            o.str("name", x.label)
                    .str("origin", origin(x.origin))
                    .str("source", x.source)
                    .raw("internals", Str.arr(x.internals));
            o.num("total", x.total)
                    .num("reachable", x.reachable())
                    .num("open", x.open)
                    .num("loose", x.loose)
                    .num("buried", x.buried)
                    .num("priority", x.priority)
                    .num("unknown", x.unknown);
            if (x.drop != null && !x.drop.isEmpty()) o.str("drop", x.drop);
            if (x.liquid != null && !x.liquid.isEmpty()) o.str("liquid", x.liquid);
            if (x.attrs != null && !x.attrs.isEmpty()) o.str("attrs", x.attrs);
            appendJson(sb, o.end());
        }
    }

    /** 可建造六桶之和（**只有这一处**实现 —— 简洁模式只用这个数，别在别处再抄一遍） */
    static int buildableSum(MapStats.Buildable b) {
        if (b == null) return 0;
        return b.free + b.cleared + b.walled + b.deep + b.floorBlocked + b.unknown;
    }

    static String buildableLine(MapStats.Buildable b) {
        int sum = buildableSum(b);
        Obj o = new Obj("buildable");
        o.num("free", b.free).num("cleared", b.cleared).num("walled", b.walled)
                .num("deep", b.deep).num("floorBlocked", b.floorBlocked).num("unknown", b.unknown)
                .num("sum", sum);
        StringBuilder k = new StringBuilder("[");
        if (b.floorKinds != null) {
            for (MapStats.Buildable.Kind kind : b.floorKinds) {
                if (kind == null) continue;
                if (k.length() > 1) k.append(',');
                k.append('{').append(Str.of("name", kind.label))
                        .append(",\"tiles\":").append(kind.tiles).append('}');
            }
        }
        o.raw("floorKinds", k.append(']').toString());
        return o.end();
    }

    static String coreLine(MapStats.Core c) {
        Obj o = new Obj("core");
        o.num("zone", c.zone).num("usable", c.usable).num("max", c.max).num("size", c.size)
                .str("basis", c.basis == MapStats.Core.BASIS_PLANET ? "planet" : "default")
                .bool("skip", c.skip);
        return o.end();
    }

    /**
     * 来源码 → 稳定字符串（`vanilla` / `mod` / `patch` / `other`）。
     *
     * <p>🔴 输入是 {@link MapStats#ORIGIN_VANILLA} 那套**枚举码**，不是 {@code Row.source} 那句文案 ——
     * 「原版 / 模组（改）/ 地图补丁」是给人看的，还带后缀；拿它做判据**一改文案就静默失效**
     * （门禁 `SRC-01` 专门抓这个，我第一版就是这么写的，被当场拦下）。</p>
     */
    static String origin(int code) {
        switch (code) {
            case MapStats.ORIGIN_MOD: return "mod";
            case MapStats.ORIGIN_PATCH: return "patch";
            case MapStats.ORIGIN_UNKNOWN: return "other";
            default: return "vanilla";
        }
    }

    // ── 极小的 JSON 拼装（只写不读；读的那份在 MapStats 里） ──────────────

    private static final class Obj {
        private final StringBuilder b = new StringBuilder(192);

        /** 不带 `kind` 的对象（导出文档的顶层就是它 —— 一张图的统计不需要"我是个 meta"这种自我介绍） */
        Obj() {
            b.append('{');
        }

        Obj(String kind) {
            this();
            b.append("\"kind\":").append(Str.quote(kind));
        }

        /** 逗号只在"已经有内容"时加 —— 无 kind 的对象第一个字段不能以逗号开头 */
        private void sep() {
            if (b.length() > 1) b.append(',');
        }

        Obj str(String k, String v) {
            sep();
            b.append(Str.quote(k)).append(':').append(Str.quote(v == null ? "" : v));
            return this;
        }

        Obj num(String k, long v) {
            sep();
            b.append(Str.quote(k)).append(':').append(v);
            return this;
        }

        Obj bool(String k, boolean v) {
            sep();
            b.append(Str.quote(k)).append(':').append(v);
            return this;
        }

        Obj raw(String k, String json) {
            sep();
            b.append(Str.quote(k)).append(':').append(json);
            return this;
        }

        String end() {
            return b.append('}').toString();
        }
    }

    private static final class Str {
        static String of(String k, String v) {
            return quote(k) + ":" + quote(v == null ? "" : v);
        }

        /** JSON 字符串字面量（控制字符一律转义 —— 它们是"一行一条"的判据） */
        static String quote(String s) {
            if (s == null) return "null";
            StringBuilder o = new StringBuilder(s.length() + 2);
            o.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': o.append("\\\""); break;
                    case '\\': o.append("\\\\"); break;
                    case '\n': o.append("\\n"); break;
                    case '\r': o.append("\\r"); break;
                    case '\t': o.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == 0x7f) {
                            o.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            o.append(c);
                        }
                }
            }
            return o.append('"').toString();
        }

        static String arr(List<String> xs) {
            if (xs == null || xs.isEmpty()) return "[]";
            StringBuilder b = new StringBuilder("[");
            for (String x : xs) {
                if (b.length() > 1) b.append(',');
                b.append(quote(x));
            }
            return b.append(']').toString();
        }
    }
}
