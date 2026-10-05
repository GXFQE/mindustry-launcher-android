package io.mdt.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 「**存档视为地图**」的改写器（REF §72）：把一份存档 `.msav` 的**第 0 区（meta）**改写成地图形态，
 * **区 1..n 逐字节原样搬运**（地形 / 建筑 / 单位 / 数据补丁引用都不动）。
 *
 * ★ 纯 Java（**无 Android 依赖**）—— 与 {@link MsavMeta} / {@link SettingsBin} / {@link Hjson}
 *   同一条路子：能在 PC 上单独编译，并**拿游戏自己的类当对照组**逐项比对
 *   （对照与判据程序见探针工程 `MDT-Android-Dev/_lab/msav/save-as-map/`：`SaveAsMap.java`(同源) /
 *   `LabCheck.java` / `RulesKeys.java`）。**产品里这份就是从那边的分析稿逐字搬过来的。**
 *
 * ── 改写口径（每一条都对着**游戏自己的源码**，别自由发挥；细则 REF §72.3） ──
 * <pre>
 *   meta：补 `name`（默认取 `mapname`）；`wave`→1、`playtime`→0、`stats`→`{}`
 *         能找到"源图"（存档的 `mapname` == 那张图的 `name`，与游戏 `SaveMeta` 同源）⇒ 补齐**缺**的
 *         {@link #INHERIT_KEYS}（genfilters / author / description / modeName / steamid）；
 *         `genfilters` 仍然缺 ⇒ 写显式空列表 {@link #EMPTY_FILTERS}
 *         （不写的话编辑器「生成」区**每次打开都现造一份默认**、用户删不掉 —— 真机反馈）
 *   rules：删 sector / researched / allowEditRules / objectiveFlags / limitX limitY limitWidth limitHeight
 *          `limitMapArea`→false（照抄 MapEditorDialog.editInGame()）
 *          `planet` 只在**非原版星球**（模组星球）时删
 * </pre>
 *
 * 🔴 **`rules` 既不是 JSON 也不是 hjson**：它是 arc `JsonWriter` 的紧凑输出（键不带引号、
 *   值靠 `,`/`}` 终止），游戏读它走 `JsonIO.read` → `new JsonReader().parse(...)`。
 *   我们移植的 {@link Hjson} 在语料上 **244/387 解不出**（hjson 的终止规则对不上）
 *   ⇒ 这里**只做"顶层键的字节区间删除 / 替换"**，绝不解析成树再序列化。
 *
 * ★ 区间扫描器的语义**逐条对着游戏 `JsonReader` 实测过**（探针 `JsonProbe*.java`）：
 *   · 字符串**只有双引号**（`'` 是普通字符：`{a:it's}` 值就是 `it's`；`{'k':v}` 键名带引号）
 *   · 裸值到 `,` / 换行 / 结束符为止；数组里以 `]` 结束、对象里以 `}` 结束
 *     （`{a:x] ,b:1}` 游戏报错 ⇒ 对象里的 `]` 判坏；`[一次对话]` 合法 ⇒ 数组里的 `]` 收尾）
 *   · 空裸值只有 `{a:}`（紧贴冒号 + 收尾 `}`）合法；`{a:,b:1}` / `{a: }` 游戏都报错
 *   · 条目分隔 = `,` **或换行**；尾逗号 `{a:1,}` 也吃
 *   ⚠️ 已知的不对称：嵌套内部只做**结构**校验（不像游戏那样逐项查语义）⇒ 若原文件的 `rules`
 *      本身游戏就读不了，我们可能仍写出一个同样读不了的文件。产品侧的前提是"游戏自己写出来的
 *      `rules` 一定是它能读回来的"（语料 387/387 实测成立）；判据侧另有游戏 reader 复验。
 *
 * 线格式（照抄游戏；常量复用 {@link MsavMeta}，别在这里再抄一份）：
 *   zlib 流 → `MSAV` → int 版本 → 一串 (int 长度 + 载荷)；第 0 块 = StringMap
 *   StringMap = short 个数 + 每项 (writeUTF 键, writeUTF 值)
 */
public final class SaveAsMap {

    /**
     * 原版星球（**7 个**，别只记 3 个）。
     *
     * 依据：设备那个版本 159.7 的 `javap mindustry.content.Planets` 与 160.4 源码
     * `Planets.java:29/46/92/100/107/123/164` —— **两份完全一致**；后四个是 `makeAsteroid(...)`
     * 造的小行星。只写常见三个会把 `verilus`/`gier`/`tantros` 这些**冷门原版**误当模组星球删掉。
     */
    public static final String[] VANILLA_PLANETS = {
            "sun", "erekir", "tantros", "serpulo", "gier", "notva", "verilus"};

    /** rules 里**直接删掉**的顶层键（= 取消该字段 ⇒ 回到 `Rules` 里的默认值） */
    public static final String[] DROP_KEYS = {
            "sector", "researched", "allowEditRules", "objectiveFlags",
            "limitX", "limitY", "limitWidth", "limitHeight"};

    /** rules 里**改成 `false`** 的顶层键（照抄 `MapEditorDialog.editInGame()` 的 `limitMapArea = false`） */
    public static final String[] FALSE_KEYS = {"limitMapArea"};

    /**
     * 从"这份存档是玩哪张图存出来的"那张图**补齐**的键（2026-10-05 用户提的第二种机制）。
     *
     * ★ 判据与**游戏自己**同源：`SaveMeta` 显示"地图：X"用的就是
     *   `maps.all().find(m -> m.name().equals(mapname))` —— 即**存档的 `mapname` == 那张图的 `name`**。
     * ★ **规则是通用的**（用户 2026-10-05：「不止 genfilters，其他缺失的也可以补的」）：
     *   **源图有、而这份存档没有的键，一律补**；只有 {@link #NO_INHERIT} 里那批不补。
     *   ⇒ 游戏以后新增的"地图侧"键也会自动跟上，不用再维护一张白名单。
     * ★ 语料实测（312 存档 / 75 地图）"只在图侧出现"的就是：
     *   `name`（我们写自己的）、`author`、`description`、`genfilters`、`modeName`、`steamid`
     *   —— 所以实际补上的主要就是这几样；两边都有的那 19 个键里，只有 `locales`
     *   （地图的多语言名字/简介）真有可能在某份存档里缺（310/312 有它）⇒ 那时也补。
     * 🔴 **只补"缺"的键**：存档自己有的（哪怕值是空串）一律不动 —— 抄来的东西不许盖过原件。
     * 🔴 **空值不补**：源图里是空串的键跳过 —— ① 显示效果与"没有"一样；
     *   ② `genfilters` 是空串时**反而会触发游戏"现造一份默认"**那条支路（见 {@link #EMPTY_FILTERS}）。
     * ⚠️ 复制来的值**逐字节原样**（含色码），不解析、不重排 —— 与 `rules` 那边的纪律一致。
     */
    public static final String[] INHERIT_KEYS_HINT = {
            "genfilters", "author", "description", "modeName", "steamid", "locales"};

    /**
     * **不补**的键（每一条都有理由；规则见 {@link #INHERIT_KEYS_HINT}）。
     *
     * <pre>
     *   name            用户命名的那一个（我们刚写进去）
     *   rules           本存档的世界规则（已清洗；抄别张图会与地图区自相矛盾）
     *   width / height  必须与地图区一致
     *   wave / playtime / stats   归一化成地图形态：1 / 0 / {}
     *   saved / build   这份文件是谁、什么时候写的
     *   tick / wavetime / viewpos           运行态（抄过来会让"继续"落到别人的时刻/视角）
     *   playerteam / controlledType / controlGroups   玩家侧状态（控制组是玩家自己的，不该跟着图走）
     *   nocores / hasExternalAssets         描述**这份世界**的事实（抄"有核心"会让存档列表说谎）
     *   mods            这份世界需要哪些模组：本存档自己的才准
     *   mapname         本来就是源图的名字（自动匹配用的就是它）
     *   sectorPreset    战役来源标记，属于这份存档
     * </pre>
     */
    public static final String[] NO_INHERIT = {
            "name", "rules", "width", "height", "wave", "playtime", "stats",
            "saved", "build", "tick", "wavetime", "viewpos",
            "playerteam", "controlledType", "controlGroups",
            "nocores", "hasExternalAssets", "mods", "mapname", "sectorPreset"};

    /** 这个键该不该从源图补（见 {@link #NO_INHERIT}） */
    public static boolean isNoInherit(String key) {
        for (String k : NO_INHERIT) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /**
     * `genfilters` 缺失时写进去的**显式空列表**（2026-10-05）。
     *
     * 🔴 为什么不是"什么都不写"：`Maps.readFilters(str)` 里 `str == null || str.isEmpty()` 这一支会
     * **现造一份默认过滤器列表**（遍历所有"可装饰地板"造 `ScatterFilter` + `addDefaultOres`）
     * ⇒ 文件里缺这个键（或它是空串）时，编辑器「生成」区**每次打开都会冒出那份默认**，
     * 用户删掉也留不住（真机反馈：「默认参数是有，但我删了又会出来」）。
     * 显式写 `[]`（非空串）⇒ `readFilters` 走解析那一支 ⇒ 空列表 ⇒ **删了就是删了**。
     */
    public static final String EMPTY_FILTERS = "[]";

    // ── 错误码：纯 Java 只出「码 + 参数」，文案在 Android 侧 {@link MsavText} 映射 ──
    public static final int E_NONE = 0;
    /** 开头不是 `MSAV`（不是 .msav / 不是 zlib） */
    public static final int E_MAGIC = 1;
    /** 版本号不合理（errN1 = 版本号） */
    public static final int E_VERSION = 2;
    /** meta 块长度不合理，或块读完还剩字节（errN1 = 长度/剩余） */
    public static final int E_META_LEN = 3;
    /** meta 的键值表读不完整（errN1 = 读到几项） */
    public static final int E_META_SHORT = 4;
    /** `rules` 的结构看不懂（errS1 = 内部标记，errN1 = 位置）—— **宁可拒绝，不猜** */
    public static final int E_RULES_SHAPE = 5;
    /** 某个区声明的长度不合理（errN1 = 长度） */
    public static final int E_CHUNK_LEN = 6;
    /** 流被截断（errN1 = 还差多少字节） */
    public static final int E_TRUNCATED = 7;
    /** 某个键值超过 `writeUTF` 的 65535 字节上限（errS1 = 键名） */
    public static final int E_UTF_TOO_LONG = 8;
    /** 读写异常（errS1 = 异常类名） */
    public static final int E_IO = 9;

    /** 自检要**遍历所有码**确认 `MsavText` 都映射了（漏映射 = 界面静默空白） */
    public static final int[] ALL_CODES = {E_MAGIC, E_VERSION, E_META_LEN, E_META_SHORT,
            E_RULES_SHAPE, E_CHUNK_LEN, E_TRUNCATED, E_UTF_TOO_LONG, E_IO};

    /** 改写结果 */
    public static final class Result {
        public boolean ok;
        public int errCode = E_NONE;
        /** 错误参数（中性：键名 / 内部标记 / 异常类名 —— **不是给人读的句子**） */
        public String errS1;
        public long errN1;

        /** 写进 meta 的地图名 */
        public String name;
        /** 删掉了几条 rules 顶层键 */
        public int dropped;
        /** `limitMapArea` 是否从 true 改成 false */
        public boolean limitFalse;
        /** 原文里的 `planet`（没有这个键 ⇒ null） */
        public String planet;
        /** `planet` 是否因为"不是原版星球"被删掉 */
        public boolean planetDropped;
        /** rules 文本有没有被改动 */
        public boolean rulesChanged;
        /** 从"源图"补齐了几个键（0 = 没找到源图 / 没什么可补） */
        public int inherited;
        /** 补了哪几个键（逗号分隔、截断到 100 字符）—— 只给报告/技术细节看 */
        public String inheritKeys;
        /** 源图的名字（没找到 ⇒ null）—— 只给报告/技术细节看 */
        public String inheritFrom;
        /** `genfilters` 是不是"缺键 ⇒ 补了显式空列表" */
        public boolean genEmpty;
        public long bytesIn;
        public long bytesOut;

        /** 人读报告（**给维护者**：dev 口落盘 / 「技术细节」第二层 / 自检看它） */
        public String report() {
            return "ok=" + ok + " err=" + errCode
                    + (errS1 == null ? "" : " errS1=" + errS1)
                    + " errN1=" + errN1
                    + " name=" + name
                    + " dropped=" + dropped
                    + " limitFalse=" + limitFalse
                    + " planet=" + planet + (planetDropped ? "(dropped)" : "")
                    + " rulesChanged=" + rulesChanged
                    + " inherited=" + inherited + (inheritFrom == null ? "" : "(" + inheritFrom + ")")
                    + (inheritKeys == null ? "" : "[" + inheritKeys + "]")
                    + " genfiltersEmpty=" + genEmpty
                    + " bytes=" + bytesIn + "->" + bytesOut;
        }
    }

    private SaveAsMap() {
    }

    /**
     * 把 `src`（一份**存档**）改写成地图形态写到 `dst`。
     *
     * @param mapName 写进 meta `name` 的名字（调用方已按"默认 mapname + 用户确认"定好）
     */
    public static Result convert(File src, File dst, String mapName) {
        return convert(src, dst, mapName, null);
    }

    /**
     * 同上，外加**按源图补齐缺失的键**（{@link #INHERIT_KEYS}）。
     *
     * @param inherit 从"这份存档玩的那张图"读出来的键值（调用方按 `mapname` == 图的 `name` 找的，
     *                与游戏 `SaveMeta` 同源）；null / 空 = 没找到源图，那就只走 {@link #EMPTY_FILTERS} 兜底。
     */
    public static Result convert(File src, File dst, String mapName, Map<String, String> inherit) {
        Result r = new Result();
        r.name = mapName;
        InputStream fin = null;
        OutputStream fout = null;
        Closeable inCloser = null;
        try {
            r.bytesIn = src.length();
            fin = new BufferedInputStream(new FileInputStream(src), 1 << 16);
            DataInputStream in = new DataInputStream(
                    new InflaterInputStream(fin, new Inflater(), 1 << 16));
            inCloser = in;

            byte[] magic = new byte[MsavMeta.MAGIC.length];
            in.readFully(magic);
            for (int i = 0; i < MsavMeta.MAGIC.length; i++) {
                if (magic[i] != MsavMeta.MAGIC[i]) {
                    r.errCode = E_MAGIC;
                    return r;
                }
            }
            int version = in.readInt();
            r.errN1 = version;
            if (version < MsavMeta.MIN_VERSION || version > MsavMeta.MAX_VERSION) {
                r.errCode = E_VERSION;
                r.errS1 = String.valueOf(version);
                return r;
            }
            int len0 = in.readInt();
            if (len0 < 0 || len0 > MsavMeta.MAX_REGION) {
                r.errCode = E_META_LEN;
                r.errN1 = len0;
                return r;
            }
            byte[] meta0 = new byte[len0];
            in.readFully(meta0);

            LinkedHashMap<String, String> tags = readStringMap(meta0, r);
            if (r.errCode != E_NONE) return r;

            // ── ① rules：只动顶层键的字节区间 ──
            String rules = tags.get("rules");
            if (rules != null && !rules.trim().isEmpty()) {
                RulesEdit e = editRules(rules);
                if (!e.ok) {
                    r.errCode = E_RULES_SHAPE;
                    r.errS1 = e.why;
                    r.errN1 = e.pos;
                    return r;
                }
                r.dropped = e.dropped;
                r.limitFalse = e.limitFalse;
                r.planet = e.planet;
                r.planetDropped = e.planetDropped;
                if (!e.text.equals(rules)) {
                    tags.put("rules", e.text);
                    r.rulesChanged = true;
                }
            }

            // ── ② 元数据：先按"源图"补齐缺的键，再补 name + 三个归零 ──
            // ★ 通用规则（见 INHERIT_KEYS_HINT）：源图有、这份存档没有的 ⇒ 补；NO_INHERIT 里的不补；
            //   空值不补；**只补缺的**（存档自己有的一个字不动）
            if (inherit != null) {
                StringBuilder got = new StringBuilder();
                for (Map.Entry<String, String> en : inherit.entrySet()) {
                    String k = en.getKey();
                    String v = en.getValue();
                    if (k == null || v == null || v.isEmpty()) continue;   // 空值不补
                    if (tags.containsKey(k)) continue;                     // 只补缺的
                    if (isNoInherit(k)) continue;                          // 见 NO_INHERIT
                    tags.put(k, v);
                    r.inherited++;
                    if (got.length() < 100) got.append(got.length() == 0 ? "" : ",").append(k);
                }
                if (got.length() > 0) r.inheritKeys = got.toString();
            }
            tags.put("name", mapName);
            tags.put("wave", "1");
            tags.put("playtime", "0");
            tags.put("stats", "{}");
            // ── ③ `genfilters` 兜底：显式空列表（缺键/空串时游戏每次都会现造一份默认，删不掉）──
            if (!tags.containsKey("genfilters")) {
                tags.put("genfilters", EMPTY_FILTERS);
                r.genEmpty = true;
            }

            byte[] meta1 = writeStringMap(tags, r);
            if (meta1 == null) return r;

            // ── ③ 写出：魔数 + 版本 + 新 meta 块 + 其余块**逐字节搬运** ──
            fout = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16);
            DataOutputStream out = new DataOutputStream(
                    new DeflaterOutputStream(fout, new Deflater(Deflater.DEFAULT_COMPRESSION), 1 << 16));
            out.write(MsavMeta.MAGIC);
            out.writeInt(version);
            out.writeInt(meta1.length);
            out.write(meta1);

            byte[] buf = new byte[1 << 16];
            int chunks = 0;
            while (true) {
                int len;
                try {
                    len = in.readInt();
                } catch (EOFException eof) {
                    break;                                    // 正常收尾：区读完了
                }
                if (len < 0 || len > MsavMeta.MAX_REGION) {
                    r.errCode = E_CHUNK_LEN;
                    r.errN1 = len;
                    return r;
                }
                out.writeInt(len);
                int left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, left));
                    if (n < 0) {
                        r.errCode = E_TRUNCATED;
                        r.errN1 = left;
                        return r;
                    }
                    out.write(buf, 0, n);
                    left -= n;
                }
                chunks++;
            }
            r.errN1 = chunks;
            out.flush();
            out.close();                                      // finish deflater + close fout
            fout = null;
            close(inCloser);                                  // 关掉 inflater + fin
            inCloser = null;
            fin = null;
            r.bytesOut = dst.length();
            r.ok = true;
            return r;
        } catch (Throwable t) {
            r.errCode = E_IO;
            r.errS1 = t.getClass().getSimpleName();
            return r;
        } finally {
            close(inCloser);
            close(fin);
            close(fout);
            if (!r.ok) {
                // ★ 失败 ⇒ **一个字节都不留**（与 MapFiles.importMap 的 `.part` 纪律一致）：
                //   宁可让调用方看到"没写出东西"，也不要留一份半成品冒充地图。
                try {
                    dst.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ════════════════════════ meta（StringMap） ════════════════════════

    static LinkedHashMap<String, String> readStringMap(byte[] payload, Result r) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        DataInputStream ds = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            int size = ds.readShort() & 0xFFFF;
            for (int i = 0; i < size; i++) {
                out.put(ds.readUTF(), ds.readUTF());
            }
            if (ds.available() > 0) {                         // 与游戏 readRegion 的"用干净没"同口径
                r.errCode = E_META_LEN;
                r.errN1 = ds.available();
                return out;
            }
        } catch (EOFException e) {
            r.errCode = E_META_SHORT;
            r.errN1 = out.size();
        } catch (Throwable t) {
            r.errCode = E_IO;
            r.errS1 = t.getClass().getSimpleName();
        }
        return out;
    }

    static byte[] writeStringMap(LinkedHashMap<String, String> tags, Result r) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            DataOutputStream d = new DataOutputStream(bos);
            d.writeShort(tags.size());
            for (Map.Entry<String, String> e : tags.entrySet()) {
                if (utfLen(e.getKey()) > 65535 || utfLen(e.getValue()) > 65535) {
                    r.errCode = E_UTF_TOO_LONG;               // writeUTF 的上限（游戏也是这么写的）
                    r.errS1 = e.getKey();
                    return null;
                }
                d.writeUTF(e.getKey());
                d.writeUTF(e.getValue());
            }
            d.flush();
            return bos.toByteArray();
        } catch (Throwable t) {
            r.errCode = E_IO;
            r.errS1 = t.getClass().getSimpleName();
            return null;
        }
    }

    /** `writeUTF` 会写多少字节（>65535 就该拒） */
    static int utfLen(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) n += 1;
            else if (c <= 0x07FF) n += 2;
            else n += 3;
        }
        return n;
    }

    // ════════════════════════ rules：顶层键区间编辑 ════════════════════════

    static final class RulesEdit {
        boolean ok;
        String text;
        String why;
        long pos;
        int dropped;
        boolean limitFalse;
        String planet;
        boolean planetDropped;
    }

    /** 一条顶层条目（保留 == `keyStart..valueEnd` 这段**原文**，一个字节都不重排） */
    static final class Ent {
        String key;
        int keyStart;
        int valueStart;
        int valueEnd;
    }

    static RulesEdit editRules(String s) {
        RulesEdit e = new RulesEdit();
        ArrayList<Ent> ents = new ArrayList<>();
        int open = skipWs(s, 0);
        int end = (open >= s.length() || s.charAt(open) != '{') ? -1 : scanObject(s, open, ents);
        if (end < 0 || skipWs(s, end) != s.length()) {
            e.why = "rules-shape";
            e.pos = end < 0 ? open : end;
            return e;
        }
        StringBuilder sb = new StringBuilder(s.length());
        sb.append('{');
        boolean first = true;
        for (Ent en : ents) {
            boolean drop = false;
            String repl = null;
            for (String k : DROP_KEYS) {
                if (k.equals(en.key)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                for (String k : FALSE_KEYS) {
                    if (k.equals(en.key)) {
                        if (!"false".equals(strip(s.substring(en.valueStart, en.valueEnd)))) {
                            repl = "false";                   // 本来就是 false ⇒ 一个字都不动
                            e.limitFalse = true;
                        }
                        break;
                    }
                }
            }
            if (!drop && "planet".equals(en.key)) {
                String v = strip(s.substring(en.valueStart, en.valueEnd));
                e.planet = v;
                if (!isVanillaPlanet(v)) {
                    drop = true;
                    e.planetDropped = true;
                }
            }
            if (drop) {
                e.dropped++;
                continue;
            }
            if (!first) sb.append(',');
            first = false;
            if (repl != null) sb.append(s, en.keyStart, en.valueStart).append(repl);
            else sb.append(s, en.keyStart, en.valueEnd);
        }
        sb.append('}');
        e.text = sb.toString();
        e.ok = true;
        return e;
    }

    static String strip(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 这个星球名是不是**原版**（不在 {@link #VANILLA_PLANETS} 里 ⇒ 当模组星球处理） */
    public static boolean isVanillaPlanet(String v) {
        for (String k : VANILLA_PLANETS) {
            if (k.equals(v)) return true;
        }
        return false;
    }

    /**
     * 扫一个**对象**；`i` 指向 `{`，返回收尾 `}` 之后的位置；`-1` = 结构坏了。
     * `topOut != null` 时记录顶层条目（只有 rules 的顶层要记录区间）。
     */
    static int scanObject(String s, int i, List<Ent> topOut) {
        i++;                                                  // 跳过 '{'
        while (true) {
            boolean nl = false;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '\n') nl = true;
                if (isWs(d)) i++;
                else break;
            }
            if (i >= s.length()) return -1;
            char c = s.charAt(i);
            if (c == '}') return i + 1;                       // 空对象 / 正常收尾（含尾逗号后）
            if (c == ',') return -1;                          // 连续逗号：游戏也报错
            int keyStart = i;
            String key;
            if (c == '"') {
                int e = readStrEnd(s, i);
                if (e < 0) return -1;
                key = readStr(s, i);
                i = e;
            } else {
                int st = i;
                while (i < s.length() && s.charAt(i) != ':' && !isWs(s.charAt(i))) {
                    char d = s.charAt(i);
                    if (d == '{' || d == '}' || d == '"') return -1;
                    i++;
                }
                key = s.substring(st, i);
            }
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != ':') return -1;
            int afterColon = i + 1;
            i = skipWs(s, afterColon);
            if (i >= s.length()) return -1;
            char vc = s.charAt(i);
            if (vc == ',' || vc == '}' || vc == ']' || vc == '\n') {
                // 空值只在 `{a:}` 这种"紧贴冒号 + 收尾 }"下合法（游戏实测：`{a:,` `{a: }` 都报错）
                if (vc == '}' && i == afterColon) {
                    if (topOut != null) topOut.add(ent(key, keyStart, i, i));
                    return i + 1;
                }
                return -1;
            }
            int ve = scanValue(s, i, false);
            if (ve < 0) return ve;
            if (topOut != null) topOut.add(ent(key, keyStart, i, ve));
            i = ve;
            nl = false;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '\n') nl = true;
                if (isWs(d)) i++;
                else break;
            }
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == '}') return i + 1;
            if (nl) continue;                                 // 换行也是分隔符（游戏实测）
            return -1;
        }
    }

    /** 扫一个**数组**；`i` 指向 `[`，返回收尾 `]` 之后的位置；`-1` = 结构坏了 */
    static int scanArray(String s, int i) {
        i++;
        while (true) {
            boolean nl = false;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '\n') nl = true;
                if (isWs(d)) i++;
                else break;
            }
            if (i >= s.length()) return -1;
            if (s.charAt(i) == ']') return i + 1;
            if (s.charAt(i) == ',') return -1;
            int ve = scanValue(s, i, true);
            if (ve < 0) return ve;
            i = ve;
            nl = false;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '\n') nl = true;
                if (isWs(d)) i++;
                else break;
            }
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == ']') return i + 1;
            if (nl) continue;
            return -1;
        }
    }

    /**
     * 扫一个**值**；返回结束位置；`-1` = 坏了。
     *
     * @param inArray 在数组里时裸值以 `]` 结束（`[一次对话]`）；在对象里 `]` 是坏值
     *                （游戏实测 `{a:x] ,b:1}` 报错）—— 这一条是跑判据时撞出来的真 bug：
     *                第一版把 `]` 一律判坏，于是 `objectiveFlags:{values:[…]}` /
     *                `payloads:[precept,precept]` 这类**真实内容**全被判死（216/315 份失败）。
     */
    static int scanValue(String s, int i, boolean inArray) {
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        if (c == '"') return readStrEnd(s, i);
        if (c == '{') return scanObject(s, i, null);
        if (c == '[') return scanArray(s, i);
        // 裸值：到 `,` / 换行 / （数组里 `]`、对象里 `}`）结束；其余结构字符 ⇒ 判坏
        char closer = inArray ? ']' : '}';
        char wrong = inArray ? '}' : ']';
        int st = i;
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d == ',' || d == '\n' || d == closer) break;
            if (d == '{' || d == '[' || d == '"' || d == wrong) return -1;
            if (d == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') return -1;
            i++;
        }
        int e2 = i;
        while (e2 > st) {
            char d = s.charAt(e2 - 1);
            if (d == ' ' || d == '\t' || d == '\r') e2--;
            else break;
        }
        return e2;
    }

    static Ent ent(String key, int keyStart, int valueStart, int valueEnd) {
        Ent e = new Ent();
        e.key = key;
        e.keyStart = keyStart;
        e.valueStart = valueStart;
        e.valueEnd = valueEnd;
        return e;
    }

    static int skipWs(String s, int i) {
        while (i < s.length() && isWs(s.charAt(i))) i++;
        return i;
    }

    static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** 双引号字符串的收尾引号之后；`-1` = 没闭合。`'` 不是字符串（与游戏一致） */
    static int readStrEnd(String s, int i) {
        i++;                                                  // 跳过开引号
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') return i + 1;
            i++;
        }
        return -1;
    }

    /** 读出字符串内容（调用方保证 `s.charAt(i) == '"'` 且已用 {@link #readStrEnd} 验过闭合） */
    static String readStr(String s, int i) {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    default: sb.append(n);
                }
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
