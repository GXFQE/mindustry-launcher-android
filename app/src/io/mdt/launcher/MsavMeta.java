package io.mdt.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * `.msav` 的**元数据读取器**（F10）。
 *
 * ★ 纯 Java（**无 Android 依赖**）—— 与 {@link SettingsBin} / {@link Hjson} 同一条路子：
 *   能在 PC 上单独编译，并**拿游戏自己的类当对照组**逐字段比对
 *   （对照程序 `_lab/msav/MsavOracle.java`，用 `Mindustry.jar` 里的
 *   `SaveIO.readHeader → SaveVersion.readRegion("meta", readStringMap)`）。
 *
 * 线格式（**全部照抄游戏源码**，别"顺手改好一点"）：
 * <pre>
 *   zlib 流（InflaterInputStream —— **不是** gzip）
 *   ├ 4 字节魔数 "MSAV"                SaveIO.java:21  header = {'M','S','A','V'}
 *   ├ int 格式版本                     MapIO.createMap: stream.readInt()
 *   └ region 串：int 长度 + 载荷        SaveFileReader.readChunk / writeChunk
 *        顺序 meta → patches → content → map → entities → markers → custom（SaveVersion.java:80）
 *        meta 的载荷 = StringMap：short 个数 + 每项（readUTF 键, readUTF 值）
 * </pre>
 *
 * ★ **只想看元数据时，读完第一块就停**（meta 永远是第一块）——
 *   存档几百 KB 到几十 MB，没必要为了一行"这是什么存档"把整份解压完。
 *
 * 🔴 **别用 tags 判"这是地图还是存档"**：实测老地图（如官方 `archipelago.msav`，格式版本 5）
 *   也带 `mapname`。**以文件所在目录为准**（`saves/` vs `maps/`），tags 只用来取字段。
 *   ⇒ 所以本类**刻意不提供** `kind()` 这种"猜一个"的接口。
 *
 * ⚠️ 格式版本实测跨 2/4/5/7/8/9/11（游戏注册到 13）⇒ **一律按"缺键就用默认值"读**，
 *   不许假设某个键一定存在（各代 `SaveVersion` 的差别就是"有哪些键"）。
 */
public final class MsavMeta {
    /** `SaveIO.java:21` 的魔数 */
    public static final byte[] MAGIC = {'M', 'S', 'A', 'V'};

    /** 单块 region 的长度上限（防御坏文件/炸弹：超过就直接判不可信） */
    public static final int MAX_REGION = 64 << 20;

    /** 格式版本合理区间（游戏注册到 13；留点余量给未来，但别让明显是垃圾的值混过去） */
    public static final int MIN_VERSION = 1;
    public static final int MAX_VERSION = 64;

    // ── 直接读出来的原始事实 ──────────────────────────────────────────────
    /** 格式版本（meta 区里没有它，是流里那个 int） */
    public int version;
    /** meta 区的键值（**保序**：文件里的顺序） */
    public final LinkedHashMap<String, String> tags = new LinkedHashMap<>();
    /** 大小（字节） */
    public long fileBytes;

    // ── 常用的那几个（缺键 = 默认值，见类注释） ────────────────────────────
    public int width;
    public int height;
    /** 存档时间（epoch 毫秒；地图是"保存时间"） */
    public long saved;
    /** 游玩时长（毫秒；地图恒 0） */
    public long playtime;
    public int wave;
    /** 游戏构建号（地图常为 -1） */
    public int build;
    /** 地图**真名**（可带色码）—— 只有地图/带 name 的才有 */
    public String name;
    public String author;
    public String description;
    /** 存档所用地图的名字（老地图也可能有 ⇒ 别拿它当"这是存档"的判据） */
    public String mapname;

    // ── 结果 ─────────────────────────────────────────────────────────────
    /** 元数据**读出来了**（🔴 只保证 meta 那一块 —— 它后面的区域我们没读，**不代表整份文件完整**；
     *  要确认完整性见 {@link #read(File, boolean)} 的 verifyWhole 参数） */
    public boolean ok;
    /** 读失败/读了一半的原因（ok=false 或 truncated=true 时非 null） */
    public String error;
    /** 流被截断（`.msav.part` 那种"写了一半"）：**已经读出来的 tags 仍然可用**。
     *  ⚠️ 默认只读 meta ⇒ **meta 之后**的截断看不到，必须用 {@link #read(File, boolean)} 才会查 */
    public boolean truncated;

    // ── ★ 错误码（P3，2026-10-05）：给界面用的「码 + 参数」─────────────────────
    // 为什么要有它：本类是**刻意纯 Java** 的（好在 PC 上单独编译验收，见类头）⇒ 不能 `getString`。
    // 可它的 {@link #error} 句子会经 `MsavText.userReason` **直接落到用户眼前**
    // （「⚠ N 份读不出来」弹窗）⇒ 加这一层：核心只说"错在哪、带哪几个数"，
    // 文案由 Android 侧的 `MsavText` 映射成资源。
    // ⚠️ {@link #error} 里那句中**一个字都不动** —— 落盘报告、`report()` 与自检看的是它（维护者视角）。
    // ⚠️ 新增错误时**必须**同时做三件事：加一个码 + 在 `MsavText.userReason` 里加一条映射
    //    + 自检里遍历所有码过一遍（自检会**遍历**，漏映射会被抓住；否则界面上是**静默空白**）。
    /** 没有码（或不是我们写的错：`error` 里是异常原文） */
    public static final int E_NONE = 0;
    /** `read(File)` 传进来的是 null */
    public static final int E_NULL_FILE = 1;
    /** 开头不是 `MSAV` */
    public static final int E_MAGIC = 2;
    /** 格式版本不合理；参数 n1 = 读到的版本号 */
    public static final int E_VERSION = 3;
    /** meta 块长度不合理；参数 n1 = 声明长度 */
    public static final int E_META_LEN = 4;
    /** 被截断，且连元数据都没读全 */
    public static final int E_TRUNC_HEAD = 5;
    /** 元数据之后被截断（已读出的部分仍可用） */
    public static final int E_TRUNC_AFTER = 6;
    /** meta 声明 n1 项、只读到 n2 项 */
    public static final int E_META_SHORT = 7;
    /** meta 读完后还剩 n1 字节（与游戏口径不一致） */
    public static final int E_META_TAIL = 8;

    /** 上面那些码之一（{@link #E_NONE} = 没有） */
    public int errCode = E_NONE;
    /** 码带的两个整数参数（没用到的是 0） */
    public long errN1;
    public long errN2;

    /**
     * **所有**错误码（给自检**遍历**用）。
     *
     * 🔴 为什么要有它：`MsavText.userReason` 的 `switch` 带 `default` 兜底（返回"原因不明"），
     *   所以"新加了码却忘了加映射"**不崩、不报错**，界面上只是**静默退化**。
     *   只有把所有码逐条过一遍才抓得住 ⇒ **加码时这里也要加**（枚举写在一处，漏了看得见）。
     */
    public static final int[] ALL_CODES = {
            E_NULL_FILE, E_MAGIC, E_VERSION, E_META_LEN,
            E_TRUNC_HEAD, E_TRUNC_AFTER, E_META_SHORT, E_META_TAIL,
    };

    /** 造一个"只带错误码"的实例：**给自检遍历所有码用**（正常路径只有 {@link #read} 会造它） */
    static MsavMeta withCode(int code, long n1, long n2) {
        return new MsavMeta().fail(code, "", n1, n2);
    }

    /** 记下"错在哪"：**码 + 参数给界面**，中文句子留给报告与自检 */
    private MsavMeta fail(int code, String zh, long n1, long n2) {
        errCode = code;
        errN1 = n1;
        errN2 = n2;
        error = zh;
        return this;
    }

    private MsavMeta() {}

    /** 读一个文件，**只读 meta**（最快；meta 之后的截断看不到） */
    public static MsavMeta read(File f) {
        return read(f, false);
    }

    /**
     * 读一个文件。
     *
     * @param verifyWhole true = 读完 meta 之后**把剩下的流也读干净**，用来发现
     *                    「meta 完好但文件后半被截断」（例如写到一半断电留下的文件）。
     *                    zlib 流截断时 `InflaterInputStream` 会在末尾抛 `EOFException` ⇒ 我们据此报 truncated。
     *                    ★ 代价是**整份解压**（大存档几十 MB）⇒ 只在用户明确要"查完整性"时用。
     */
    public static MsavMeta read(File f, boolean verifyWhole) {
        MsavMeta m = new MsavMeta();
        if (f == null) {
            return m.fail(E_NULL_FILE, "文件为 null", 0, 0);
        }
        m.fileBytes = f.length();
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
            return read(in, m, verifyWhole);
        } catch (Throwable t) {
            if (m.error == null) m.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return m;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读一个流（**调用方负责关**；本方法只读 meta 那一块就返回） */
    public static MsavMeta read(InputStream raw) {
        return read(raw, new MsavMeta(), false);
    }

    private static MsavMeta read(InputStream raw, MsavMeta m, boolean verifyWhole) {
        DataInputStream in = null;
        try {
            in = new DataInputStream(new InflaterInputStream(raw, new java.util.zip.Inflater(), 1 << 16));
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    return m.fail(E_MAGIC, "这不是 .msav：开头不是 MSAV", 0, 0);
                }
            }
            m.version = in.readInt();
            if (m.version < MIN_VERSION || m.version > MAX_VERSION) {
                return m.fail(E_VERSION,
                        "格式版本不合理（" + m.version + "）—— 可能不是 .msav，或是我们没见过的版本",
                        m.version, 0);
            }
            // region 串的第一块永远是 meta（SaveVersion.write 的顺序写死的）
            int len = in.readInt();
            if (len < 0 || len > MAX_REGION) {
                return m.fail(E_META_LEN,
                        "meta 块长度不合理（" + len + "）—— 文件可能坏了",
                        len, 0);
            }
            byte[] payload = new byte[len];
            in.readFully(payload);                    // 截断 ⇒ 这里抛 EOFException
            readMetaInto(payload, m);
            m.ok = true;
            if (verifyWhole) {
                // ★ 排空剩下的流：截断的 zlib 流会在末尾抛 EOFException（这就是"写了一半"的机器判据）
                byte[] buf = new byte[1 << 16];
                while (in.read(buf) > 0) {
                    // 只为了走到流的末尾，不解析后面的区域
                }
            }
            return m;
        } catch (EOFException e) {
            // ★ 写了一半的文件（`.msav.part`）：**已经解析出来的 tags 照样有用**
            //   （游戏侧遇到截断就是读失败；我们比它多给一步"能读多少读多少"）
            m.truncated = true;
            m.fail(m.tags.isEmpty() ? E_TRUNC_HEAD : E_TRUNC_AFTER, m.tags.isEmpty()
                    ? "文件被截断（像是写了一半）—— 连元数据都没读全"
                    : "文件在元数据之后被截断（像是写了一半）；下面这些是已经读出来的部分",
                    0, 0);
            m.ok = !m.tags.isEmpty() || m.version > 0;
            return m;
        } catch (Throwable t) {
            m.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return m;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** meta 区载荷 = StringMap（`SaveFileReader.readStringMap`） */
    private static void readMetaInto(byte[] payload, MsavMeta m) throws IOException {
        DataInputStream ds = new DataInputStream(new ByteArrayInputStream(payload));
        int size = ds.readShort() & 0xFFFF;           // writeShort(map.size)：无符号读法更稳
        int read = 0;
        try {
            for (int i = 0; i < size; i++) {
                String k = ds.readUTF();
                String v = ds.readUTF();
                m.tags.put(k, v);
                read++;
            }
        } catch (EOFException e) {
            // 块长度对不上：保留已读到的，并说明（游戏侧这里是 "read length mismatch" 硬报错）
            m.truncated = true;
            m.fail(E_META_SHORT,
                    "meta 块声明了 " + size + " 项，只读到 " + read + " 项（块被截断或损坏）",
                    size, read);
        }
        // 与游戏同款的"用干净没"检查：readRegion 会核对 length 是否恰好等于读掉的字节数
        int leftover = ds.available();
        if (leftover > 0 && m.error == null) {
            m.fail(E_META_TAIL,
                    "meta 块读完后还剩 " + leftover + " 字节（与游戏的口径不一致）",
                    leftover, 0);
        }
        m.applyTags();
    }

    private void applyTags() {
        width = getInt("width", 0);
        height = getInt("height", 0);
        saved = getLong("saved", 0L);
        playtime = getLong("playtime", 0L);
        wave = getInt("wave", 0);
        build = getInt("build", 0);
        name = tags.get("name");
        author = tags.get("author");
        description = tags.get("description");
        mapname = tags.get("mapname");
    }

    // ── 取值：缺键/解析不出 ⇒ 默认值（照抄游戏 `StringMap.getInt(k, d)` 的容错口径） ──

    public boolean has(String key) {
        return key != null && tags.containsKey(key);
    }

    public String get(String key, String def) {
        String v = tags.get(key);
        return v == null ? def : v;
    }

    public int getInt(String key, int def) {
        return parseInt(tags.get(key), def);
    }

    public long getLong(String key, long def) {
        String v = tags.get(key);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 与游戏 `Strings.parseInt(s, def)` 同口径：解析不出就回默认值 */
    public static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ── 给人看的（**白话、短、无 markdown**，见 REF §53.8） ────────────────

    /** 显示名：地图真名优先，其次存档里记的地图名，最后空 */
    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name;
        if (mapname != null && !mapname.trim().isEmpty()) return mapname;
        return "";
    }

    /** 尺寸文案（读不出尺寸就空串） */
    public String sizeText() {
        return (width > 0 && height > 0) ? (width + " × " + height) : "";
    }

    // ── 显示文案：**不在本类** ────────────────────────────────────────────
    //
    // 原来这里有 playtimeText / summary / shortLine 三个方法，它们**用 Java 拼中文**
    // （`玩了 1 小时 2 分` / `存档于 …` / `作者：…`）。
    // 2026-10-04（P3 第一片）搬到 {@link MsavText} 了，理由：
    //   · 默认语言翻成英文之后，存档行成了「208.3 KB · 我的地图 · 玩了 1 小时 2 分」这种**中英混排**；
    //   · 但本类**刻意不碰 Android**（要能在 PC 上单独编译、拿语料逐项对照）⇒ 不能 getString。
    // ⇒ 分工：**本类只留数据 + 解析；一切给用户看的词都在 {@link MsavText}**。
    //   以后往本类加东西时，别再把文案拼进来。

    /**
     * 人读的保存时间：**今年内只写 `MM-dd HH:mm`**，跨年才带年份（本地时区）。
     * ★ 存档列表里"哪一份更新"是最常用的判据 ⇒ 短且能直接比较比"完整但很长"更有用。
     *
     * ⚠️ 用 `Locale.US`（2026-10-04 收口）：本工程其它 8 处日期/数字格式化一律 `Locale.US`/`Locale.ROOT`，
     *   只有这里原来用的是 `Locale.getDefault()` —— 在阿拉伯语等使用本地数字的设备上，
     *   它会输出本地数字而界面其它数字是西文数字，同一个界面上两套数字格式。
     *   （格式串是纯数字 ⇒ 换 Locale 不改输出，见 docs/i18n-feasibility.md §7.9。）
     */
    public String savedText() {
        if (saved <= 0) return "";
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(saved);
            int y = c.get(java.util.Calendar.YEAR);
            int nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    y == nowYear ? "MM-dd HH:mm" : "yyyy-MM-dd HH:mm", java.util.Locale.US);
            return f.format(new java.util.Date(saved));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 多行报告（**给维护者**：落盘报告 + 自检看它；给用户的那份是 {@link MsavText#detail}） */
    public String report() {
        StringBuilder sb = new StringBuilder();
        if (!ok) {
            sb.append("❌ 读不出来：").append(error == null ? "未知原因" : error).append('\n');
            if (truncated) sb.append("（像是写了一半的文件）\n");
            return sb.toString();
        }
        if (truncated) sb.append("⚠ ").append(error).append('\n');
        sb.append("格式版本：v").append(version).append('\n');
        String n = displayName();
        if (!n.isEmpty()) sb.append("名字：").append(n).append('\n');
        String sz = sizeText();
        if (!sz.isEmpty()) sb.append("尺寸：").append(sz).append('\n');
        if (author != null && !author.trim().isEmpty()) sb.append("作者：").append(author).append('\n');
        if (wave > 0) sb.append("波次：").append(wave).append('\n');
        String pt = reportPlaytime();
        if (!pt.isEmpty()) sb.append("游玩：").append(pt).append('\n');
        if (build > 0) sb.append("游戏构建：").append(build).append('\n');
        if (saved > 0) sb.append("保存时间：").append(saved).append("（毫秒时间戳）\n");
        sb.append("元数据项：").append(tags.size()).append(" 条\n");
        if (description != null && !description.trim().isEmpty()) {
            String d = description.trim();
            sb.append("简介：").append(d.length() > 120 ? d.substring(0, 120) + "…" : d).append('\n');
        }
        return sb.toString();
    }

    // 原来这里还有 detail() 与 userReason()，**同样搬到 {@link MsavText} 了**（P3 第一片）：
    //   · detail()     —— 给用户的详情（"尺寸：… / 作者：… / 格式版本：v…"那一串）
    //   · userReason() —— 给用户的失败原因
    // 两者都是**给用户看的词**，所以按同一个理由离开本类：
    //   **本类只留数据 + 解析；一切给用户看的词都在 MsavText。**
    // ⚠️ report() 与 error 原文**留在本类**：那是给维护者的（落盘报告 + 自检看它），
    //    不跟着界面语言变，别顺手也搬走。

    /**
     * 报告里的时长文案（**只给 {@link #report} 用**，所以仍然是中文硬编码）。
     *
     * ★ 为什么不复用 {@link MsavText#playtimeText}：那个要 Context，而本类刻意不碰 Android
     *   （要能在 PC 上单独编译、拿语料逐项对照）。
     * ★ 为什么它可以留中文：`report()` 是**给维护者**的（落盘报告 + 自检看它），
     *   正是 `res/values/strings.xml` 头注释里那条"写进 report-*.txt 的取证文本不进资源"。
     *   **给用户看的那份在 {@link MsavText}**，跟着界面语言走 —— 两者别混。
     */
    private String reportPlaytime() {
        if (playtime <= 0) return "";
        long sec = playtime / 1000L;
        long h = sec / 3600L, min = (sec % 3600L) / 60L;
        if (h > 0) return h + " 小时 " + min + " 分";
        if (min > 0) return min + " 分钟";
        return "不到 1 分钟";
    }

    /** 全部 tags（诊断/落盘报告用；保序） */
    public List<Map.Entry<String, String>> allTags() {
        return new ArrayList<>(tags.entrySet());
    }

    // ── 一个槽里那批存档的体检（口径只有这一处） ────────────────────────────

    /**
     * 一批 `.msav` 的体检结果（槽页副标题 + 「哪几份读不出来」两处共用）。
     *
     * ★ 为什么要有它：F10 的三条收益里，第 ③ 条是「**开游戏前就告诉你哪个存档坏了**」——
     *   而这件事原来只在**点开导出列表**时逐行显示，用户不进那个列表就永远看不见。
     */
    public static final class Saves {
        /** 认到的存档份数（只算 `.msav` 普通文件，见 {@link #isSaveFile}） */
        public int total;
        /** 读不出来的那些（与入参同序；原因在各条的 {@link MsavMeta#error} 里） */
        public final List<Bad> unreadable = new ArrayList<>();
        /** 最近改动的那个文件（**不管读不读得出来**；没有则为 null） */
        public File newest;
        /** 上面那个文件的元数据（{@link #newest} 为 null 时它也是 null） */
        public MsavMeta newestMeta;

        public int unreadableCount() {
            return unreadable.size();
        }
    }

    /** 一份读不出来的存档：文件 + 它的读取结果（`ok=false`，`error` 是原因） */
    public static final class Bad {
        public final File file;
        public final MsavMeta meta;

        Bad(File file, MsavMeta meta) {
            this.file = file;
            this.meta = meta;
        }
    }

    /**
     * 扫一批文件，给出「多少份 / 几份读不出来 / 最近那份是什么」。
     *
     * ⚠️ **只读元数据**（每条 {@link #read(File)} 都是"读完 meta 那一块就停"）——
     *   几百份存档也就百来毫秒，但仍然**别放 UI 线程**（槽页是在后台线程里调的）。
     * ⚠️ 入参允许是 `listFiles()` 的原始结果：目录、非 `.msav`、`.msav.part` 一律不计入。
     */
    public static Saves summarize(File[] files) {
        Saves s = new Saves();
        if (files == null) return s;
        for (File f : files) {
            if (!isSaveFile(f)) continue;
            s.total++;
            MsavMeta m = read(f);
            if (!m.ok) s.unreadable.add(new Bad(f, m));
            if (s.newest == null || f.lastModified() > s.newest.lastModified()) {
                s.newest = f;
                s.newestMeta = m;
            }
        }
        return s;
    }

    /**
     * 认不认它当存档：**普通文件**且名字以 `.msav` 结尾（大小写不敏感）。
     * ★ 与槽页原来那段手写的过滤条件逐条相同（`isFile()` + 后缀）——
     *   写一半的 `.msav.part` 因此天然不计入（它不以 `.msav` 结尾）。
     */
    public static boolean isSaveFile(File f) {
        return f != null && f.isFile()
                && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".msav");
    }
}
