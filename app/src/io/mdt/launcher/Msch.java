package io.mdt.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * 蓝图（`.msch`）解析器 —— **纯 Java，无 Android 依赖**（与 {@link MsavMeta} / {@link SettingsBin}
 * 同一条路子：能在 PC 上单独编译，拿游戏自己的读取器当对照组逐字段比对）。
 *
 * <p>线格式（**全部照抄游戏源码，别"顺手改好一点"**）：
 * <pre>
 *   'msch' + version(1B) + zlib(body)                    Schematics.read:571~581
 *   body:
 *     short w, short h                                   声明尺寸（🔴 **不是**瓦片包围盒，见下）
 *     byte tagN + tagN×(UTF key, UTF val)                 标签（readUTF ⇒ modified UTF-8）
 *     byte blockN + blockN×UTF 方块名                      字典（下标 = 瓦片里的 byte）
 *     int tileN + tileN×( byte 字典下标, int Point2.pack(x,y), 配置, byte 旋转 )
 *   配置：ver==0 ⇒ **裸 int**（走 mapConfig）；ver==1 ⇒ TypeIO 对象（tag 1B + 载荷）
 *   上限：w>128||h>128 或 tileN>16384 ⇒ 拒；数组 ≤200；byte[] ≤40000
 * </pre>
 *
 * <p>🔴 **四条"照抄不要改"的细节**（每一条都对应一次真实的踩坑，来源见 REF §73）：
 * <ol>
 *   <li><b>所有字符串都是 modified UTF-8</b>（`Reads.str()` 就是 `DataInputStream.readUTF`，
 *       已核 arc-147 / arc-159.7 / arc-master 三份源码）—— 含 `readUTF` 的**长度是 unsigned short**、
 *       且代理对写成两个 3 字节序列。用普通 UTF-8 解 emoji 会得到替换字符。</li>
 *   <li><b>tag 21 `int[]` 的长度是 short</b>（不是 byte），且游戏在这条**不判**"嵌套数组"；
 *       tag 6 的 `IntSeq` 与它是两种东西 —— 名字别对调。</li>
 *   <li><b>tag 18 `Vec2[]` 的长度是"向量个数"</b>（载荷 2×N 个 float）。</li>
 *   <li><b>tag 22 `Object[]` 里每个元素都要保留 `(tag, 值)`</b>，子层 `allowArrays=false`
 *       （游戏原话 `Nested arrays are not allowed`）。</li>
 * </ol>
 *
 * <p>⚠️ **`width/height` 不可信**：游戏 `create()` 用的是"足迹"，语料里确实有瓦片**原点**越出声明
 * `w/h` 的（3007 份里 3 份）⇒ 包围盒一律**按瓦片自己算**（{@link #minX}…），
 * {@link #declaredWidth} 只作"文件里写着多少"如实报告。越界的瓦片数记在 {@link #outOfBounds}。
 *
 * <p>⚠️ **本类刻意不认识"内容名"**：方块名只是文件里的字符串（过一遍
 * {@link #mapFallback} 的旧名映射），不是游戏内容；配置里的 content 只给
 * `(类型序号, id)`。要显示名字由调用方查 {@link #contentName}（文件自带 contentMap）
 * 或内容表 —— 这样核心才能在没有游戏的情况下单独验收。
 *
 * <p>判据（都在 PC 上跑，工具在 `_lab/msch/`）：
 * ① **游戏自己的读取器当神谕**（`SchOracle`，不启动游戏、不需要图形）：合成样本 36/36、
 *    24 种配置 tag 字节级 25/25；② 4821 份真语料全过 + 与参考实现逐字段互校。
 */
public final class Msch {

    // ── 常量（照抄 Schematics / TypeIO）────────────────────────────────────
    /** `Schematics.java:50` */
    public static final byte[] HEADER = {'m', 's', 'c', 'h'};
    /** `Schematics.version`：本实现认到 1（`ver > 1` 拒，`ver == 0` 收） */
    public static final int MAX_VERSION = 1;
    /** `limitSchematicSize`：声明尺寸上限 */
    public static final int MAX_DIM = 128;
    /** `total > 128 * 128` ⇒ 拒 */
    public static final int MAX_TILES = MAX_DIM * MAX_DIM;
    /** `TypeIO.readObject` 里 `safe=false` 那一档 */
    public static final int MAX_ARRAY = 200;
    /** `TypeIO.maxByteArraySize` */
    public static final int MAX_BYTE_ARRAY = 40000;

    /**
     * 解压后 body 的上限（**我们加的**，游戏没有）。
     *
     * ★ 为什么要有：`InflaterInputStream` 是流式的 ⇒ 游戏内存有界，而我们要一次性拿到 body
     *   才能给出精确的 `leftover`。一个几百字节的 zlib 炸弹可以解出几 GB ⇒ 必须自己设闸。
     *   实测真语料最大 body &lt; 1 MB（见 `_lab/msch/` 报告）⇒ 32 MB 是三十倍余量。
     */
    public static final int MAX_INFLATE = 32 << 20;
    /** 文件本身的上限（防"后缀改成 .msch 的几百 MB 文件"） */
    public static final int MAX_FILE = 8 << 20;

    // ── 错误码（P3 口径：核心只给「码 + 参数」，文案在 MschText）─────────────
    /** 没出错 */
    public static final int E_NONE = 0;
    /** 传进来的是 null */
    public static final int E_NULL_FILE = 1;
    /** 文件太大（n1 = 上限） */
    public static final int E_FILE_TOO_BIG = 2;
    /** 开头不是 `msch`（n1 = 实际读到的头 4 字节） */
    public static final int E_HEADER = 3;
    /** 版本号 &gt; 1；n1 = 读到的版本 */
    public static final int E_VERSION = 4;
    /** zlib 解不开（截断/不是 zlib） */
    public static final int E_ZLIB = 5;
    /** 解压后超过 {@link #MAX_INFLATE}；n1 = 上限 */
    public static final int E_INFLATE_LIMIT = 6;
    /** 声明尺寸超限；n1 = w, n2 = h */
    public static final int E_TOO_LARGE = 7;
    /** 瓦片总数超限；n1 = 声明总数 */
    public static final int E_TOO_MANY = 8;
    /** body 里读空了（写到一半的文件） */
    public static final int E_TRUNC = 9;
    /** 配置解码失败（未知 tag / 形状不符）；n1 = tag，n2 = 位置说明用不到时为 0 */
    public static final int E_CONFIG = 10;
    /** 数组长度不合法；n1 = 长度，n2 = tag */
    public static final int E_ARRAY = 11;
    /** 嵌套数组（游戏原话 Nested arrays are not allowed）；n1 = tag */
    public static final int E_NESTED = 12;
    /** 认不出的 TypeIO 类型；n1 = tag */
    public static final int E_TAG = 13;
    /** body 结构错（字符串长度非法等） */
    public static final int E_BODY = 14;

    /**
     * **所有**错误码（给自检**遍历**用）。
     *
     * 🔴 为什么要有它：`MschText.reason` 的 `switch` 带 `default` 兜底 ⇒ "新加了码却忘了加映射"
     *   **不崩、不报错**，界面上只是静默退化成"原因不明"。只有把所有码逐条过一遍才抓得住。
     */
    public static final int[] ALL_CODES = {
            E_NULL_FILE, E_FILE_TOO_BIG, E_HEADER, E_VERSION, E_ZLIB, E_INFLATE_LIMIT,
            E_TOO_LARGE, E_TOO_MANY, E_TRUNC, E_CONFIG, E_ARRAY, E_NESTED, E_TAG, E_BODY,
    };

    // ── 直接读出来的原始事实 ──────────────────────────────────────────────
    /** 文件里的版本字节（0 或 1） */
    public int version;
    /** 文件里声明的尺寸（🔴 不是包围盒，见类注释） */
    public int declaredWidth, declaredHeight;
    /** 标签（**保序**：文件里的顺序） */
    public final LinkedHashMap<String, String> tags = new LinkedHashMap<>();
    /** `labels` 解析出来的分类（解析失败 = 空表，游戏也是静默忽略） */
    public final List<String> labels = new ArrayList<>();
    /** `labels` 文本存在但解不开（我们如实记下来，不当错误 —— 游戏也是 try/catch 忽略） */
    public boolean labelsBad;
    /** 方块字典（**原样**：文件里的名字，未过 fallback） */
    public final List<String> dict = new ArrayList<>();
    /** 瓦片（**全部**，含游戏会因为"认不出方块"而丢掉的那些 —— 本类没有内容表，丢不掉） */
    public final List<Tile> tiles = new ArrayList<>();
    /** 瓦片用完 body 之后还剩多少字节（🔴 游戏**容忍**尾随垃圾，我们只是标出来） */
    public int leftover;
    /** 解压后 body 的字节数（诊断用） */
    public int bodyBytes;
    /** 文件字节数 */
    public long fileBytes;

    /** 瓦片包围盒（**按瓦片自己算**；没有瓦片时四值都是 0） */
    public int minX, minY, maxX, maxY;
    /** 瓦片**原点**落在声明 `w/h` 之外的个数（诊断用，语料里真出现过） */
    public int outOfBounds;
    /** 有 contentMap（⇒ 配置里的 content 能反查名字） */
    public boolean hasContentMap;

    // ── 结果 ─────────────────────────────────────────────────────────────
    /** 解析成功（**整份文件**都读完了；`leftover > 0` 不算失败，见上） */
    public boolean ok;
    /** 失败原因（**给维护者/报告看**的中文句子；界面一律走 {@link #errCode} + {@link MschText}） */
    public String error;
    /** 失败的错误码（{@link #E_NONE} = 没出错） */
    public int errCode = E_NONE;
    /** 码带的两个整数参数（没用到的是 0） */
    public long errN1, errN2;
    /** 文件名（不含扩展名）—— 给"标签里没有 name"时兜底（与游戏 `read(Fi)` 同口径） */
    public String fileBase;

    private Msch() {}

    // ── 瓦片与配置 ────────────────────────────────────────────────────────

    /** 一格瓦片（坐标是**瓦片原点**，不是中心） */
    public static final class Tile {
        /** 文件里写的方块名（**原样**） */
        public String rawName;
        /** 过了旧名映射（{@link Msch#mapFallback}）之后的名字 —— 游戏就是拿它去查内容 */
        public String block;
        public int x, y;
        public int rotation;
        /** 配置（ver 0 的裸 int 已转成对象形状；具体见 {@link Cfg#tag}） */
        public Cfg config;
    }

    /**
     * 一格配置的**规范化**表示（镜像 `TypeIO.readObject` 解出来的那些类型）。
     *
     * ★ 为什么不用 `Object` 直接装：我们要能在**没有游戏类**的地方（PC 侧自检、Android 界面）
     *   判断"这是什么"——`tag` 就是权威判据，别的字段按 tag 取。
     *   ⚠️ **按方块类型分派**那条纪律在这里落地：`byte[]`（tag 14）既可能是逻辑代码，
     *   也可能是 MindustryX `canvas` 的原始像素 ⇒ 本类**不猜**，只给 `tag` + 原始字节。
     */
    public static final class Cfg {
        /** TypeIO 类型号 0..23（ver 0 的裸 int 记 {@link #TAG_INT}） */
        public int tag;
        public int i;
        public long l;
        public float f;
        public double d;
        public boolean b;
        public String s;
        /** content / technode / unitcmd(23)：内容类型序号 + id（-1 = 该类型不带这两个字段） */
        public int ctype = -1, cid = -1;
        /** point2 的 x/y（tag 7） */
        public int px, py;
        /** intseq(6) / int[](21) / point2[](8，元素是 pack 后的 int) */
        public int[] ints;
        /** vec2(19：2 个) / vec2[](18：2N 个) */
        public float[] floats;
        /** byte[](14) */
        public byte[] bytes;
        /** bool[](16) */
        public boolean[] bools;
        /** obj[](22)：**保留子对象的 (tag, 值)** */
        public Cfg[] objs;

        /** ver 0 的裸 int 借用 tag 1（Integer）—— 与 `mapConfig` 的 `return value` 同形状 */
        public static final int TAG_INT = 1;

        public boolean isNull() {
            return tag == 0 || tag == 15;
        }

        /** 是否是"数组类"（给界面判断"这一格是不是一坨数据"用） */
        public boolean isArray() {
            return tag == 6 || tag == 8 || tag == 14 || tag == 16 || tag == 18 || tag == 21 || tag == 22;
        }
    }

    // ── 入口 ─────────────────────────────────────────────────────────────

    /** 读一个文件（**唯一**会碰磁盘的入口） */
    public static Msch read(File f) {
        Msch m = new Msch();
        if (f == null) return m.fail(E_NULL_FILE, "文件为 null", 0, 0);
        m.fileBytes = f.length();
        m.fileBase = baseName(f.getName());
        if (m.fileBytes > MAX_FILE) {
            return m.fail(E_FILE_TOO_BIG, "文件太大：" + m.fileBytes + " 字节（上限 " + MAX_FILE + "）",
                    MAX_FILE, 0);
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] raw = readAll(in, MAX_FILE);
            m.bodyBytes = -1;
            return parse(raw, m);
        } catch (Throwable t) {
            if (m.error == null) m.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return m;
        } finally {
            close(in);
        }
    }

    /** 读一段字节（自检与 PC 测试台用） */
    public static Msch read(byte[] raw) {
        return parse(raw, new Msch());
    }

    /** 读一个流（**调用方负责关**） */
    public static Msch read(InputStream in) {
        Msch m = new Msch();
        try {
            return parse(readAll(in, MAX_FILE), m);
        } catch (Throwable t) {
            if (m.error == null) m.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return m;
        }
    }

    // ── 单条配置载荷（自检 / PC 测试台用）──────────────────────────────────
    // 为什么单独开一个口：整份文件的判据只能证明"整条链对得上"，抓不住**单个 tag** 的
    // 长度字段错（语料里 24 种 tag 只出现 9 种）。PC 测试台就是拿它去和神谕的 `obj` 模式
    // 做**字节级**对照（消费字节数 + 规范化值），这一条当场抓出过 `int[]` 长度是 short 那类潜伏错误。

    /** 一条配置载荷的解码结果 */
    public static final class Payload {
        public Cfg cfg;
        /** 消费掉的字节数（与神谕的 `used` 对照 —— 长度字段错一位这条就对不上） */
        public int used;
        public boolean ok;
        public String error = "";
        public int errCode = E_NONE;
        public long errN1, errN2;
    }

    /**
     * 解一条配置载荷（**不是**整份蓝图）。
     *
     * ★ 与游戏 `SchOracle obj` 模式同口径：`TypeIO.readObject(read, box=true, null)` ——
     *   `box=true` 是为了绕开 `Vars.world` / `Groups.unit` 为 null（tag 12/17），
     *   在**我们这边** box 没有实际作用（我们本来就不查世界），留着只为与神对照时口径一致。
     */
    public static Payload readPayload(byte[] raw) {
        Payload p = new Payload();
        Msch m = new Msch();
        Cur c = new Cur(raw == null ? new byte[0] : raw, m);
        try {
            p.cfg = c.obj(true, true, 0);
            p.used = c.i;
            p.ok = true;
        } catch (Limit hit) {
            p.errCode = E_TRUNC;
            p.error = "载荷不够长（少 " + hit.missing + " 字节）";
            p.errN1 = hit.missing;
        } catch (Bad bad) {
            p.errCode = bad.code;
            p.error = bad.getMessage();
            p.errN1 = bad.n1;
            p.errN2 = bad.n2;
        } catch (Throwable t) {
            p.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return p;
    }

    // ── 解析主体 ─────────────────────────────────────────────────────────

    private static Msch parse(byte[] raw, Msch m) {
        if (raw == null) return m.fail(E_NULL_FILE, "字节为 null", 0, 0);
        m.fileBytes = raw.length;
        if (raw.length < HEADER.length + 1) {
            return m.fail(E_HEADER, "这不是蓝图：文件比头还短（" + raw.length + " 字节）", head(raw), 0);
        }
        for (int i = 0; i < HEADER.length; i++) {
            if (raw[i] != HEADER[i]) {
                return m.fail(E_HEADER, "这不是蓝图：开头不是 msch", head(raw), 0);
            }
        }
        m.version = raw[4] & 0xff;
        if (m.version > MAX_VERSION) {
            return m.fail(E_VERSION, "蓝图版本太新：v" + m.version + "（本实现认到 v" + MAX_VERSION + "）",
                    m.version, 0);
        }
        byte[] body;
        try {
            body = inflate(raw, 5);
        } catch (Limit hit) {
            return m.fail(E_INFLATE_LIMIT, "解压后超过上限 " + MAX_INFLATE + " 字节（像是压缩炸弹）",
                    MAX_INFLATE, 0);
        } catch (java.io.EOFException e) {
            // zlib 流没写完（`.part` 那种"写到一半"）—— 与"根本不是 zlib"分开报
            return m.fail(E_TRUNC, "蓝图写到一半：zlib 流提前结束", 0, 0);
        } catch (Throwable t) {
            return m.fail(E_ZLIB, "解压失败：" + t.getClass().getSimpleName() + ": " + t.getMessage(), 0, 0);
        }
        m.bodyBytes = body.length;
        Cur c = new Cur(body, m);
        try {
            m.declaredWidth = c.s2();
            m.declaredHeight = c.s2();
            if (m.declaredWidth > MAX_DIM || m.declaredHeight > MAX_DIM) {
                return m.fail(E_TOO_LARGE, "蓝图太大：" + m.declaredWidth + "×" + m.declaredHeight
                        + "（上限 " + MAX_DIM + "×" + MAX_DIM + "）", m.declaredWidth, m.declaredHeight);
            }
            int tagN = c.u1();
            for (int i = 0; i < tagN; i++) {
                String k = c.utf();
                m.tags.put(k, c.utf());
            }
            if (m.tags.containsKey("contentMap")) m.hasContentMap = true;
            String lb = m.tags.get("labels");
            if (lb != null) {
                List<String> parsed = parseLabels(lb);
                if (parsed == null) m.labelsBad = true;
                else m.labels.addAll(parsed);
            }
            int dictN = c.u1();
            for (int i = 0; i < dictN; i++) m.dict.add(c.utf());
            int total = c.i4();
            if (total > MAX_TILES) {
                return m.fail(E_TOO_MANY, "瓦片太多：" + total + "（上限 " + MAX_TILES + "）", total, 0);
            }
            int bminX = Integer.MAX_VALUE, bminY = Integer.MAX_VALUE;
            int bmaxX = Integer.MIN_VALUE, bmaxY = Integer.MIN_VALUE;
            for (int i = 0; i < total; i++) {
                int di = c.s1();
                int pos = c.i4();
                Tile t = new Tile();
                t.rawName = (di >= 0 && di < m.dict.size()) ? m.dict.get(di) : null;
                t.block = mapFallback(t.rawName);
                // Point2.x/y 是**有符号** short（arc 的 unpack 与 pack 互逆；负坐标要能还原）
                t.x = (short) (pos >>> 16);
                t.y = (short) pos;
                t.config = (m.version == 0)
                        ? mapConfig(t.rawName, c.i4(), t.x, t.y)
                        : c.obj(false, true, 0);
                t.rotation = c.s1();
                m.tiles.add(t);
                if (t.x < bminX) bminX = t.x;
                if (t.y < bminY) bminY = t.y;
                if (t.x > bmaxX) bmaxX = t.x;
                if (t.y > bmaxY) bmaxY = t.y;
                if (t.x < 0 || t.y < 0 || t.x >= m.declaredWidth || t.y >= m.declaredHeight) {
                    m.outOfBounds++;
                }
            }
            if (!m.tiles.isEmpty()) {
                m.minX = bminX;
                m.minY = bminY;
                m.maxX = bmaxX;
                m.maxY = bmaxY;
            }
            m.leftover = body.length - c.i;
            m.ok = true;
            return m;
        } catch (Limit hit) {
            return m.fail(E_TRUNC, "蓝图写到一半就断了（少了 " + hit.missing + " 字节）", hit.missing, 0);
        } catch (Bad bad) {
            return m.fail(bad.code, bad.getMessage(), bad.n1, bad.n2);
        } catch (Throwable t) {
            if (m.error == null) {
                m.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            return m;
        }
    }

    private Msch fail(int code, String zh, long n1, long n2) {
        errCode = code;
        errN1 = n1;
        errN2 = n2;
        error = zh;
        return this;
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    /** 声明尺寸（文件里写的） */
    public int width() {
        return declaredWidth;
    }

    public int height() {
        return declaredHeight;
    }

    /** 瓦片包围盒宽（**按瓦片算**，没有瓦片时 0） */
    public int boxWidth() {
        return tiles.isEmpty() ? 0 : maxX - minX + 1;
    }

    public int boxHeight() {
        return tiles.isEmpty() ? 0 : maxY - minY + 1;
    }

    public int tileCount() {
        return tiles.size();
    }

    /** 蓝图名：标签里的 `name`，没有再退回文件名（与游戏 `read(Fi)` 同口径） */
    public String displayName() {
        String n = tags.get("name");
        if (n != null && !n.isEmpty()) return n;
        return fileBase == null ? "" : fileBase;
    }

    public String description() {
        String d = tags.get("description");
        return d == null ? "" : d;
    }

    /** 某个标签（没有则 {@code def}） */
    public String tag(String key, String def) {
        String v = tags.get(key);
        return v == null ? def : v;
    }

    /**
     * 配置里的 content ⇒ 名字（**只查文件自带的 contentMap**）。
     *
     * @return 名字；查不到返回 null（⇒ 调用方可以退回内容表或显示 `#id`）
     */
    public String contentName(int ctype, int cid) {
        return contentName(ctype, cid, null);
    }

    /**
     * 配置里的 content ⇒ 名字。
     *
     * @param fallback 文件里没有 contentMap 时用的外部表（可为 null）
     */
    public String contentName(int ctype, int cid, ContentNames fallback) {
        ensureContentMap();
        Map<Integer, String> byId = contentMap.get(ctype);
        if (byId != null) {
            String n = byId.get(cid);
            if (n != null) return n;
        }
        return fallback == null ? null : fallback.nameOf(ctype, cid);
    }

    /** 外部内容名表（给"老文件没有 contentMap"那条路用；实现方给名字，核心不认识游戏内容） */
    public interface ContentNames {
        String nameOf(int ctype, int cid);
    }

    /** 文件自带的内容映射：内容类型序号 → (id → 名字) */
    public final Map<Integer, Map<Integer, String>> contentMap = new LinkedHashMap<>();

    /** 解析 contentMap 标签文本（arc 宽松 JSON：**键名不带引号**） */
    private void parseContentMap() {
        contentMap.clear();
        String s = tags.get("contentMap");
        if (s == null) return;
        try {
            P p = new P(s);
            p.ws();
            p.expect('{');
            p.ws();
            if (p.peek() == '}') {
                p.next();
                return;
            }
            while (true) {
                p.ws();
                int ctype = Integer.parseInt(p.token());
                p.ws();
                p.expect(':');
                p.ws();
                p.expect('{');
                Map<Integer, String> inner = new LinkedHashMap<>();
                p.ws();
                if (p.peek() != '}') {
                    while (true) {
                        p.ws();
                        String name = p.token();
                        p.ws();
                        p.expect(':');
                        p.ws();
                        inner.put(Integer.parseInt(p.token()), name);
                        p.ws();
                        if (p.peek() == ',') {
                            p.next();
                            continue;
                        }
                        break;
                    }
                }
                p.ws();
                p.expect('}');
                contentMap.put(ctype, inner);
                p.ws();
                if (p.peek() == ',') {
                    p.next();
                    continue;
                }
                break;
            }
            p.ws();
            p.expect('}');
        } catch (Throwable t) {
            // 地图/蓝图的 contentMap 坏掉不该让整份蓝图读不出来（游戏侧也只是解不出映射）
            contentMap.clear();
        }
    }

    /** 惰性解析 contentMap（第一次问名字时才算） */
    private void ensureContentMap() {
        if (!contentMapDone) {
            contentMapDone = true;
            parseContentMap();
        }
    }

    private boolean contentMapDone;

    /**
     * 规范化字符串 —— **与游戏 `SchOracle` 的 `norm()` 逐字符同构**，判据才能直接对差。
     *
     * ⚠️ content 打的是 **id 不是名字**（`c:0:7`）：本类不认识游戏内容，
     *   名字要靠 {@link #contentName} 另行反查。神谕那边打的是名字 ⇒ 对照脚本
     *   用 `oracle/content-ids.tsv` 把名字换回 id（见 `_lab/msch/compare.py`）。
     */
    public static String norm(Cfg g) {
        if (g == null || g.tag == 0 || g.tag == 15) return "null";
        switch (g.tag) {
            case 1: return "i:" + g.i;
            case 2: return "l:" + g.l;
            case 3: return "f:" + g.f;
            case 4: return g.s == null ? "null" : "s:" + g.s;
            case 5: return "c:" + g.ctype + ":" + g.cid;
            case 6: return "is:" + len(g.ints) + joinInts(g.ints);
            case 7: return "p2:" + g.px + "," + g.py;
            case 8: return "p2a:" + len(g.ints) + joinInts(g.ints);
            case 9: return "tn:" + g.ctype + ":" + g.cid;
            case 10: return "b:" + g.b;
            case 11: return "d:" + g.d;
            case 12: return "bd:" + g.i;
            case 13: return "la:" + g.i;
            case 14: return "by:" + len(g.bytes);
            case 16: return "ba:" + len(g.bools) + ":" + Arrays.toString(g.bools == null ? new boolean[0] : g.bools);
            case 17: return "u:" + g.i;
            case 18: {
                int n = g.floats == null ? 0 : g.floats.length / 2;
                StringBuilder sb = new StringBuilder("v2a:").append(n);
                for (int i = 0; i < n; i++) {
                    sb.append(':').append(g.floats[i * 2]).append(',').append(g.floats[i * 2 + 1]);
                }
                return sb.toString();
            }
            case 19: return "v2:" + g.floats[0] + "," + g.floats[1];
            case 20: return "t:" + g.i;
            case 21: return "ia:" + len(g.ints) + ":" + Arrays.toString(g.ints == null ? new int[0] : g.ints);
            case 22: {
                StringBuilder sb = new StringBuilder("oa:").append(len(g.objs));
                if (g.objs != null) for (Cfg o : g.objs) sb.append(":[").append(norm(o)).append(']');
                return sb.toString();
            }
            case 23: return "c:16:" + g.cid;
            default: return "?" + g.tag;
        }
    }

    private static int len(Object a) {
        if (a == null) return 0;
        if (a instanceof int[]) return ((int[]) a).length;
        if (a instanceof byte[]) return ((byte[]) a).length;
        if (a instanceof boolean[]) return ((boolean[]) a).length;
        if (a instanceof Object[]) return ((Object[]) a).length;
        if (a instanceof float[]) return ((float[]) a).length;
        return 0;
    }

    /**
     * 逐项打 `:<值>`（与游戏 `SchOracle.norm` 的拼法一致：
     * `is:3:1:2:3` / `p2a:2:65538:196612` —— 长度后面**每一位都带前导冒号**）。
     */
    private static String joinInts(int[] a) {
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int v : a) sb.append(':').append(v);
        return sb.toString();
    }

    // ── ver 0 的 mapConfig（`Schematics.java:699`）─────────────────────────

    /**
     * 旧格式（`ver 0`）的裸 int 配置 ⇒ 对象形状。
     *
     * ★ 游戏那边判的是 **Java 类**（`block instanceof Sorter || block instanceof Unloader …`），
     *   我们没有类 ⇒ 用一张**从 160.4 生成的名字表**（{@link MschConfigTable}，生成器
     *   `_lab/msch/GenConfigTable.java`）。判据 = 神谕（游戏自己的读取器）读过合成样本
     *   `ver0_legacy.msch` 后逐格比对。
     * ⚠️ 认不出的方块名一律给 null（游戏也是 `return null`）—— **别猜**。
     */
    static Cfg mapConfig(String blockName, int value, int x, int y) {
        Cfg g = new Cfg();
        if (blockName == null) {
            g.tag = 0;
            return g;
        }
        String n = mapFallback(blockName);
        int kind = MschConfigTable.kindOf(n);
        if (kind == MschConfigTable.ITEM) {
            g.tag = 5; g.ctype = 0; g.cid = value;      // ContentType.item
            return g;
        }
        if (kind == MschConfigTable.LIQUID) {
            g.tag = 5; g.ctype = 4; g.cid = value;      // ContentType.liquid
            return g;
        }
        if (kind == MschConfigTable.POINT2) {
            // Point2.unpack(value).sub(Point2.x(position), Point2.y(position))
            g.tag = 7;
            g.px = ((short) (value >>> 16)) - x;
            g.py = ((short) value) - y;
            return g;
        }
        if (kind == MschConfigTable.INT) {
            g.tag = Cfg.TAG_INT;
            g.i = value;
            return g;
        }
        g.tag = 0;
        return g;
    }

    // ── 旧名映射（`SaveFileReader.fallback`）───────────────────────────────
    // 🔴 少了它就会**误报"缺方块"**：老蓝图里的 `alloy-smelter` 在今天的游戏里叫 `surge-smelter`。
    //    这张表**逐条照抄** mindustry/io/SaveFileReader.java 的 `fallback`（62 条）。
    private static final String[] FALLBACK_PAIRS = {
            "dart-mech-pad", "legacy-mech-pad",
            "dart-ship-pad", "legacy-mech-pad",
            "javelin-ship-pad", "legacy-mech-pad",
            "trident-ship-pad", "legacy-mech-pad",
            "glaive-ship-pad", "legacy-mech-pad",
            "alpha-mech-pad", "legacy-mech-pad",
            "tau-mech-pad", "legacy-mech-pad",
            "omega-mech-pad", "legacy-mech-pad",
            "delta-mech-pad", "legacy-mech-pad",
            "draug-factory", "legacy-unit-factory",
            "spirit-factory", "legacy-unit-factory",
            "phantom-factory", "legacy-unit-factory",
            "wraith-factory", "legacy-unit-factory",
            "ghoul-factory", "legacy-unit-factory-air",
            "revenant-factory", "legacy-unit-factory-air",
            "dagger-factory", "legacy-unit-factory",
            "crawler-factory", "legacy-unit-factory",
            "titan-factory", "legacy-unit-factory-ground",
            "fortress-factory", "legacy-unit-factory-ground",
            "mass-conveyor", "payload-conveyor",
            "vestige", "scepter",
            "turbine-generator", "steam-generator",
            "rocks", "stone-wall",
            "sporerocks", "spore-wall",
            "icerocks", "ice-wall",
            "dunerocks", "dune-wall",
            "sandrocks", "sand-wall",
            "shalerocks", "shale-wall",
            "snowrocks", "snow-wall",
            "saltrocks", "salt-wall",
            "dirtwall", "dirt-wall",
            "ignarock", "basalt",
            "holostone", "dacite",
            "holostone-wall", "dacite-wall",
            "rock", "boulder",
            "snowrock", "snow-boulder",
            "cliffs", "stone-wall",
            "craters", "crater-stone",
            "deepwater", "deep-water",
            "water", "shallow-water",
            "sand", "sand-floor",
            "slag", "molten-slag",
            "cryofluidmixer", "cryofluid-mixer",
            "block-forge", "constructor",
            "block-unloader", "payload-unloader",
            "block-loader", "payload-loader",
            "thermal-pump", "impulse-pump",
            "alloy-smelter", "surge-smelter",
            "steam-vent", "rhyolite-vent",
            "fabricator", "tank-fabricator",
            "basic-reconstructor", "refabricator",
    };

    private static Map<String, String> FALLBACK;

    private static synchronized Map<String, String> fallback() {
        if (FALLBACK == null) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < FALLBACK_PAIRS.length; i += 2) {
                m.put(FALLBACK_PAIRS[i], FALLBACK_PAIRS[i + 1]);
            }
            FALLBACK = m;
        }
        return FALLBACK;
    }

    /** `SaveFileReader.mapFallback`：老名字 → 今天的名字（不认识的原样返回） */
    public static String mapFallback(String name) {
        if (name == null) return null;
        String v = fallback().get(name);
        return v == null ? name : v;
    }

    /** 这张表有多少条（自检里钉住条数，防"顺手删两条"） */
    public static int fallbackSize() {
        return fallback().size();
    }

    // ── 读字节的小工具 ─────────────────────────────────────────────────────
    // 为什么不用 DataInputStream：我们要**精确的 leftover**（读完瓦片还剩几个字节），
    // 而 InflaterInputStream.available() 在流上不可靠。⇒ 一次性解压到 byte[]，自己走下标。

    /** body 读取游标（下标 + 越界即抛） */
    private static final class Cur {
        final byte[] b;
        final Msch m;
        int i;

        Cur(byte[] b, Msch m) {
            this.b = b;
            this.m = m;
        }

        int u1() throws IOException {
            need(1);
            return b[i++] & 0xff;
        }

        int s1() throws IOException {
            need(1);
            return b[i++];
        }

        int s2() throws IOException {
            need(2);
            return (short) (((b[i++] & 0xff) << 8) | (b[i++] & 0xff));
        }

        int u2() throws IOException {
            need(2);
            return ((b[i++] & 0xff) << 8) | (b[i++] & 0xff);
        }

        int i4() throws IOException {
            need(4);
            return ((b[i++] & 0xff) << 24) | ((b[i++] & 0xff) << 16)
                    | ((b[i++] & 0xff) << 8) | (b[i++] & 0xff);
        }

        long l8() throws IOException {
            long hi = i4() & 0xffffffffL;
            long lo = i4() & 0xffffffffL;
            return (hi << 32) | lo;
        }

        float f4() throws IOException {
            return Float.intBitsToFloat(i4());
        }

        double d8() throws IOException {
            return Double.longBitsToDouble(l8());
        }

        boolean bool() throws IOException {
            return s1() != 0;
        }

        void need(int n) throws Limit {
            if (i + n > b.length) throw new Limit(i + n - b.length);
        }

        /**
         * `DataInputStream.readUTF` 等价（**modified UTF-8**，长度是 **unsigned short**）。
         * 🔴 三份 arc 源码（147 / 159.7 / master）的 `Reads.str()` 都是 `input.readUTF()` ⇒
         *   **标签、方块名、tag 4 的字符串共用这一种编码**，不是"两种"。
         */
        String utf() throws IOException {
            int len = u2();
            need(len);
            char[] out = new char[len];
            int n = 0;
            int k = 0;
            while (k < len) {
                int c = b[i + k] & 0xff;
                if (c > 127) break;
                k++;
                out[n++] = (char) c;
            }
            while (k < len) {
                int c = b[i + k] & 0xff;
                switch (c >> 4) {
                    case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
                        k++;
                        out[n++] = (char) c;
                        break;
                    case 12: case 13: {
                        need2(k, 2);
                        k += 2;
                        int c2 = b[i + k - 1] & 0xff;
                        out[n++] = (char) (((c & 0x1f) << 6) | (c2 & 0x3f));
                        break;
                    }
                    case 14: {
                        need2(k, 3);
                        k += 3;
                        int c2 = b[i + k - 2] & 0xff;
                        int c3 = b[i + k - 1] & 0xff;
                        out[n++] = (char) (((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f));
                        break;
                    }
                    default:
                        throw new UTFDataFormatException("malformed modified UTF-8 at " + (i + k));
                }
            }
            i += len;
            return new String(out, 0, n);
        }

        private void need2(int k, int n) throws IOException {
            if (i + k + n > b.length) throw new Limit(i + k + n - b.length);
        }

        /** `TypeIO.readObject(read, box, mapper, safe=false, allowArrays)` */
        Cfg obj(boolean box, boolean allowArrays, int depth) throws IOException {
            if (depth > 8) throw new Bad(E_CONFIG, "配置嵌套太深", depth, 0);
            int t = s1();
            return objBody(t, box, allowArrays, depth);
        }

        private Cfg objBody(int t, boolean box, boolean allowArrays, int depth) throws IOException {
            Cfg g = new Cfg();
            g.tag = t;
            switch (t) {
                case 0:
                    return g;
                case 1:
                    g.i = i4();
                    return g;
                case 2:
                    g.l = l8();
                    return g;
                case 3:
                    g.f = f4();
                    return g;
                case 4: {
                    int exists = s1();
                    g.s = exists != 0 ? utf() : null;
                    return g;
                }
                case 5:
                    g.ctype = s1();
                    g.cid = s2();
                    return g;
                case 6: {
                    nested(allowArrays, t);
                    int n = s2();
                    checkArray(n, t, false);
                    g.ints = new int[n];
                    for (int k = 0; k < n; k++) g.ints[k] = i4();
                    return g;
                }
                case 7:
                    g.px = i4();
                    g.py = i4();
                    return g;
                case 8: {
                    nested(allowArrays, t);
                    int n = u1();
                    g.ints = new int[n];
                    for (int k = 0; k < n; k++) g.ints[k] = i4();   // Point2.pack 后的原值
                    return g;
                }
                case 9:
                    g.ctype = s1();
                    g.cid = s2();
                    return g;
                case 10:
                    g.b = bool();
                    return g;
                case 11:
                    g.d = d8();
                    return g;
                case 12:
                    g.i = i4();
                    return g;
                case 13:
                    g.i = s2();
                    return g;
                case 14: {
                    nested(allowArrays, t);
                    int n = i4();
                    checkArray(n, t, true);
                    g.bytes = new byte[n];
                    need(n);
                    System.arraycopy(b, i, g.bytes, 0, n);
                    i += n;
                    return g;
                }
                case 15:
                    s1();
                    g.tag = 15;                 // 旧 unitcmd：只读得出来，写不回去
                    return g;
                case 16: {
                    nested(allowArrays, t);
                    int n = i4();
                    checkArray(n, t, false);
                    g.bools = new boolean[n];
                    for (int k = 0; k < n; k++) g.bools[k] = bool();
                    return g;
                }
                case 17:
                    g.i = i4();
                    return g;
                case 18: {
                    nested(allowArrays, t);
                    int n = s2();
                    checkArray(n, t, false);
                    g.floats = new float[n * 2];
                    for (int k = 0; k < n; k++) {
                        g.floats[k * 2] = f4();
                        g.floats[k * 2 + 1] = f4();
                    }
                    return g;
                }
                case 19:
                    g.floats = new float[]{f4(), f4()};
                    return g;
                case 20:
                    g.i = u1();
                    return g;
                case 21: {
                    // 🔴 长度是 short；游戏这条**不判**嵌套（照抄，别"顺手补上"）
                    int n = s2();
                    checkArray(n, t, false);
                    g.ints = new int[n];
                    for (int k = 0; k < n; k++) g.ints[k] = i4();
                    return g;
                }
                case 22: {
                    nested(allowArrays, t);
                    int n = i4();
                    checkArray(n, t, false);
                    g.objs = new Cfg[n];
                    for (int k = 0; k < n; k++) {
                        int sub = s1();          // ⚠️ 子对象的 tag **要保留**（只留 tag 名会丢值）
                        // 🔴 子层 allowArrays=false：游戏原话 "Nested arrays are not allowed"
                        g.objs[k] = objBody(sub, box, false, depth + 1);
                    }
                    return g;
                }
                case 23:
                    g.cid = u2();
                    return g;
                default:
                    throw new Bad(E_TAG, "认不出的配置类型：" + t, t, 0);
            }
        }

        private void checkArray(int n, int tag, boolean isByteArray) throws Bad {
            if (isByteArray) {
                if (n < 0 || n > MAX_BYTE_ARRAY) throw new Bad(E_ARRAY, "字节数组长度不合法：" + n, n, tag);
            } else {
                if (n < 0 || n > MAX_ARRAY) throw new Bad(E_ARRAY, "数组长度不合法：" + n, n, tag);
            }
        }

        /** 数组类 tag 在"子层"出现 ⇒ 拒（游戏原话 `Nested arrays are not allowed`） */
        private void nested(boolean allowArrays, int tag) throws Bad {
            if (!allowArrays) throw new Bad(E_NESTED, "嵌套数组不允许（标签 " + tag + "）", tag, 0);
        }
    }

    /** 读空了（文件被截断） */
    private static final class Limit extends IOException {
        final int missing;

        Limit(int missing) {
            super("unexpected end of body, missing " + missing);
            this.missing = missing;
        }
    }

    /** 结构性错误（带码） */
    private static final class Bad extends IOException {
        final int code;
        final long n1, n2;

        Bad(int code, String msg, long n1, long n2) {
            super(msg);
            this.code = code;
            this.n1 = n1;
            this.n2 = n2;
        }
    }

    // ── labels ────────────────────────────────────────────────────────────

    /**
     * 解析 `labels` 标签（游戏：`JsonIO.read(String[].class, map.get("labels","[]"))`，
     * **失败就忽略** ⇒ 我们返回 null，调用方记 {@link #labelsBad}）。
     *
     * 🔴 **口径是拿游戏自己试出来的**，不是猜的（探针 35 种写法 + 神谕
     * `Schematic.labels` 当判据，见 `core/gen_labels_probe.py`）。定案：
     * <ul>
     *   <li><b>裸词也是元素</b>（`[T5]` / `[VE蓝图集-杂项]`）—— 语料里 **4516/4821** 份长这样；
     *       只认带引号会把绝大多数蓝图的分类全丢掉。</li>
     *   <li>裸词读到 `,` `]` `}` 为止，**内部空白保留**（`[a b]` ⇒ `a b`），**两端空白 trim**
     *       （`[ a ]` ⇒ `a`）。</li>
     *   <li><b>单引号不是引号</b>（`['a']` ⇒ 元素就是 `'a'`）。</li>
     *   <li>数字/true/null 一律当字符串（`[1,2]` ⇒ `1`,`2`）。</li>
     *   <li>整串必须**只有一个值且到尾**（`[a]junk` / `[a][b]` 都判死）。</li>
     *   <li>嵌套数组判死（`[["a"]]`）；空元素判死（`[,]` / `[a,,b]`）；但
     *       **尾随逗号是合法的**（`[a,]` ⇒ `a`）。</li>
     * </ul>
     *
     * @return 解析出来的列表；**解不开返回 null**
     */
    static List<String> parseLabels(String s) {
        if (s == null) return null;
        try {
            P p = new P(s);
            p.ws();
            p.expect('[');
            List<String> out = new ArrayList<>();
            p.ws();
            if (p.peek() == ']') {
                p.next();
                return p.atEnd() ? out : null;
            }
            while (true) {
                p.ws();
                String v;
                if (p.peek() == '"') {
                    v = p.quoted('"');
                } else {
                    v = p.until("],}");
                    if (v.isEmpty()) return null;      // 空元素
                }
                out.add(v);
                p.ws();
                char c = p.peek();
                if (c == ',') {
                    p.next();
                    p.ws();
                    if (p.peek() == ']') {             // 尾随逗号：游戏认
                        p.next();
                        return p.atEnd() ? out : null;
                    }
                    continue;
                }
                if (c == ']') {
                    p.next();
                    return p.atEnd() ? out : null;
                }
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 两个微型宽松扫描器（contentMap / labels）──────────────────────────
    // 为什么手写而不上 Hjson/JSON 解析器：这两处的形状**固定且极小**（`{int:{name:int}}` 与
    // `["a","b"]`），而 arc 那个 JsonReader 是**宽松**的（键名不带引号）——
    // 拿严格 JSON 解会全落空（§73.9 第 2 条就是这么算错命中率的）。
    // 手写扫描器的判据 = 真语料 4821 份 + 与参考实现逐字段互校。

    /** 通用游标 */
    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            if (i >= s.length()) throw new IllegalStateException("字符串提前结束");
            return s.charAt(i);
        }

        void next() {
            i++;
        }

        void expect(char c) {
            if (peek() != c) throw new IllegalStateException("期望 '" + c + "' 实际 '" + peek() + "'");
            i++;
        }

        /** 一个"裸词"：到 `: , } ]` 或空白为止（arc 的宽松写法里键名/名字都不带引号） */
        String token() {
            if (peek() == '"' || peek() == '\'') return quoted(peek());
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ':' || c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                i++;
            }
            String v = s.substring(st, i).trim();
            if (v.isEmpty()) throw new IllegalStateException("空词 @" + st);
            return v;
        }

        /**
         * 读到 `delims` 里的任意一个字符为止，**两端 trim、内部空白保留**
         * （`[a b]` ⇒ `a b`、`[ a ]` ⇒ `a` —— 这是游戏实测的口径）。
         */
        String until(String delims) {
            int st = i;
            while (i < s.length() && delims.indexOf(s.charAt(i)) < 0) i++;
            return s.substring(st, i).trim();
        }

        /** 跳过尾随空白后是否已到串尾（游戏要求"整串只有一个值"） */
        boolean atEnd() {
            ws();
            return i >= s.length();
        }

        String quoted(char q) {
            expect(q);
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalStateException("字符串没闭合");
                char c = s.charAt(i++);
                if (c == q) return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw new IllegalStateException("转义没写完");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': {
                        if (i + 4 > s.length()) throw new IllegalStateException("\\u 没写完");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    }
                    default: sb.append(e); break;
                }
            }
        }
    }

    // ── IO 小工具 ─────────────────────────────────────────────────────────

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > cap) throw new IOException("超过上限 " + cap + " 字节");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** zlib 解压（`InflaterInputStream` —— **不是** gzip），带炸弹闸 */
    private static byte[] inflate(byte[] raw, int off) throws IOException {
        InflaterInputStream in = new InflaterInputStream(
                new java.io.ByteArrayInputStream(raw, off, raw.length - off));
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, raw.length * 4));
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_INFLATE) throw new Limit(0);
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static long head(byte[] raw) {
        long v = 0;
        for (int i = 0; i < 4 && i < raw.length; i++) v = (v << 8) | (raw[i] & 0xff);
        return v;
    }

    /** 去掉扩展名的文件名（与游戏 `Fi.nameWithoutExtension` 同口径） */
    static String baseName(String n) {
        if (n == null) return "";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** 语言无关的小写化（清单/比较用；别用默认 locale） */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    /** 给报告用的一行摘要（**给维护者**，不是界面文案） */
    public String report() {
        if (!ok) {
            return "读不出来：" + (error == null ? "?" : error) + "（码 " + errCode + "）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("v").append(version)
                .append(" 声明 ").append(declaredWidth).append('×').append(declaredHeight)
                .append(" 瓦片 ").append(tiles.size())
                .append(" 框 ").append(boxWidth()).append('×').append(boxHeight())
                .append(" 字典 ").append(dict.size())
                .append(" 标签 ").append(tags.size());
        if (!labels.isEmpty()) sb.append(" 分类 ").append(labels.size());
        if (labelsBad) sb.append(" 分类坏");
        if (outOfBounds > 0) sb.append(" 越界 ").append(outOfBounds);
        if (leftover > 0) sb.append(" 尾随 ").append(leftover);
        return sb.toString();
    }

    /** 只读视图：字典（不可改） */
    public List<String> dictView() {
        return Collections.unmodifiableList(dict);
    }

    /** 只读视图：瓦片（不可改） */
    public List<Tile> tileView() {
        return Collections.unmodifiableList(tiles);
    }
}
