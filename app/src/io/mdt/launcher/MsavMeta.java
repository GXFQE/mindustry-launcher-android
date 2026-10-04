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
            m.error = "文件为 null";
            return m;
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
                    m.error = "这不是 .msav：开头不是 MSAV";
                    return m;
                }
            }
            m.version = in.readInt();
            if (m.version < MIN_VERSION || m.version > MAX_VERSION) {
                m.error = "格式版本不合理（" + m.version + "）—— 可能不是 .msav，或是我们没见过的版本";
                return m;
            }
            // region 串的第一块永远是 meta（SaveVersion.write 的顺序写死的）
            int len = in.readInt();
            if (len < 0 || len > MAX_REGION) {
                m.error = "meta 块长度不合理（" + len + "）—— 文件可能坏了";
                return m;
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
            m.error = m.tags.isEmpty()
                    ? "文件被截断（像是写了一半）—— 连元数据都没读全"
                    : "文件在元数据之后被截断（像是写了一半）；下面这些是已经读出来的部分";
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
            m.error = "meta 块声明了 " + size + " 项，只读到 " + read + " 项（块被截断或损坏）";
        }
        // 与游戏同款的"用干净没"检查：readRegion 会核对 length 是否恰好等于读掉的字节数
        int leftover = ds.available();
        if (leftover > 0 && m.error == null) {
            m.error = "meta 块读完后还剩 " + leftover + " 字节（与游戏的口径不一致）";
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

    /** 游玩时长文案（毫秒 ⇒ "3 小时 12 分"这种；<1 分钟就不显示） */
    public String playtimeText() {
        if (playtime <= 0) return "";
        long sec = playtime / 1000L;
        long h = sec / 3600L, min = (sec % 3600L) / 60L;
        if (h > 0) return h + " 小时 " + min + " 分";
        if (min > 0) return min + " 分钟";
        return "不到 1 分钟";
    }

    /** 一行摘要（列表行副标题用；没有的项自动省掉） */
    public String summary() {
        List<String> parts = new ArrayList<>();
        String n = displayName();
        if (!n.isEmpty()) parts.add(n);
        String sz = sizeText();
        if (sz.isEmpty()) parts.add("尺寸读不出");
        else parts.add(sz);
        if (wave > 1) parts.add("第 " + wave + " 波");
        String pt = playtimeText();
        if (!pt.isEmpty()) parts.add("玩了 " + pt);
        parts.add("格式 v" + version);
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        if (truncated) sb.append(" · ⚠ 像是写了一半");
        return sb.toString();
    }

    /**
     * ★ 列表行用的**短行**（区分度优先，用户 2026-10-03 要求）：
     *  · 存档：`地图名 · 玩了 X · 存档于 10-03 13:22`  ← 时间与时长才是"哪一份"的判据
     *  · 地图：`真名 · 586 × 586 · 作者：xxx`
     *  ⚠️ **格式版本不进这一行** —— 那是给维护者看的（读不出来时才在别处说），
     *    塞进来只会让长存档名溢出到第三行（真机截图里就溢出了）。
     *  · 缺什么就省什么；都没有时退回尺寸/格式，**不返回空串**（空行会让列表看不出区别）。
     *
     * @param isSave 由调用方按**文件所在目录**决定（`saves/` vs `maps/`）——
     *               ⚠️ 不要靠 tags 猜（老地图也带 `mapname`，见类注释）
     */
    public String shortLine(boolean isSave) {
        List<String> parts = new ArrayList<>();
        // ★ 地图真名里**带色码**（`[gold]Alloy-Sidestory [red]…`）—— 游戏会渲染成颜色，
        //   我们这里是纯文本列表 ⇒ 必须去色，否则一行里全是 `[gold]` 这种噪声（真机截图里就是）。
        //   判据复用 Mods 里那份 arc 等价实现（`Strings.stripColors`），不另写一份。
        String n = Mods.stripColors(displayName());
        if (!n.isEmpty()) parts.add(n);
        if (isSave) {
            String pt = playtimeText();
            if (!pt.isEmpty()) parts.add("玩了 " + pt);
            String t = savedText();
            if (!t.isEmpty()) parts.add("存档于 " + t);
        } else {
            String sz = sizeText();
            if (!sz.isEmpty()) parts.add(sz);
            // ⚠️ 作者字段**同样可能带色码**（真机实测：`[#2E8E05]iq[lime]tik[green]123`）
            //   ⇒ 与真名同一套判据，别只处理名字那一处
            String au = Mods.stripColors(author == null ? "" : author).trim();
            if (!au.isEmpty()) parts.add("作者：" + au);
        }
        if (parts.isEmpty()) {
            String sz = sizeText();
            if (!sz.isEmpty()) parts.add(sz);
            parts.add("格式 v" + version);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        if (truncated) sb.append(" · ⚠ 像是写了一半");
        return sb.toString();
    }

    /**
     * 人读的保存时间：**今年内只写 `MM-DD HH:mm`**，跨年才带年份（本地时区）。
     * ★ 存档列表里"哪一份更新"是最常用的判据 ⇒ 短且能直接比较比"完整但很长"更有用。
     */
    public String savedText() {
        if (saved <= 0) return "";
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(saved);
            int y = c.get(java.util.Calendar.YEAR);
            int nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    y == nowYear ? "MM-dd HH:mm" : "yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            return f.format(new java.util.Date(saved));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 多行报告（详情弹窗 / 落盘报告用） */
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
        String pt = playtimeText();
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

    /**
     * ★ **面向用户**的详情（第 60 轮：点开地图看详细信息）。
     *
     * 与 {@link #report()} 的分工要分清：
     *  · `report()` —— 给维护者的（写着「毫秒时间戳」「元数据项 N 条」这类话），
     *    用在落盘报告与自检里；
     *  · `detail()` —— 给用户看的：**白话、短、没有术语**（用户 2026-10-02 定的文案三条），
     *    色码去掉、时间写成人读的、只列"能用来判断这是哪张图"的字段。
     */
    public String detail() {
        StringBuilder sb = new StringBuilder();
        if (!ok) {
            sb.append("这个文件读不出来");
            if (error != null && !error.isEmpty()) sb.append("：").append(userReason());
            sb.append('\n');
            if (truncated) sb.append("（像是写了一半就中断了）\n");
            return sb.toString();
        }
        if (truncated) sb.append("⚠ 这个文件像是写了一半\n");
        String sz = sizeText();
        if (!sz.isEmpty()) sb.append("尺寸：").append(sz).append('\n');
        String au = Mods.stripColors(author == null ? "" : author).trim();
        if (!au.isEmpty()) sb.append("作者：").append(au).append('\n');
        if (wave > 0) sb.append("波次：").append(wave).append('\n');
        String pt = playtimeText();
        if (!pt.isEmpty()) sb.append("玩了：").append(pt).append('\n');
        String t = savedText();
        if (!t.isEmpty()) sb.append("保存于：").append(t).append('\n');
        sb.append("格式版本：v").append(version).append('\n');
        String d = Mods.stripColors(description == null ? "" : description).trim();
        if (!d.isEmpty()) {
            sb.append("简介：").append(d.length() > 160 ? d.substring(0, 160) + "…" : d).append('\n');
        }
        return sb.toString();
    }

    /**
     * **给用户看**的失败原因（列表行 / 详情页用）。
     *
     * ★ 为什么要有它：{@link #error} 里混着两类东西 ——
     *   ① 我们自己写的中文判断（`这不是 .msav：开头不是 MSAV`）—— 直接能用；
     *   ② Java 异常的 `类名: 消息`（`ZipException: incorrect header check`）—— 对用户是天书，
     *      而它在真实场景里**最常见**（随便找个文件把后缀改成 `.msav`）。
     *   ⇒ 只**翻译**第 ② 类，其余原样返回。
     * ⚠️ 不改判据、不改 {@link #error} 原文：排查时看的仍然是那个字段（`report()` 用的就是它）。
     */
    public String userReason() {
        String e = error == null ? "" : error.trim();
        if (e.isEmpty()) return "原因不明";
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            if (e.startsWith("ZipException") || e.startsWith("EOFException")) {
                return "这个文件不像存档，或者写到一半就断了";
            }
            return "读这个文件的时候出错了";
        }
        return e;
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
