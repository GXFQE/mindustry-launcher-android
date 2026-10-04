package io.mdt.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * {@code settings.bin} 读 / 写（F13 模组管理的**唯一**落点）。
 *
 * ── 格式（源码根据：`arc/Settings.java` 159.7/160.4 **去注释后完全相同**）──────
 * <pre>
 * int32 count                       // count &lt;= 0 ⇒ 游戏自己就判「坏文件」
 * 重复 count 次：
 *   readUTF(key)                    // u2 字节长 + modified-UTF-8
 *   u1 type: 0 bool(1B) | 1 int(i32) | 2 long(i64) | 3 float(f32)
 *            | 4 string(readUTF) | 5 binary(i32 长 + 原始字节)
 * 末尾必须**恰好 EOF**（多一个字节 ⇒ "Trailing settings data; expected EOF"）
 * 头两字节 = 78 01/5E/9c/da ⇒ 整份是 zlib（arc 只在 writeCompressed 时这么写）
 * </pre>
 *
 * ★★ 本类是**纯 Java**（**不 import 任何 Android 类**）—— 这是有意的：
 *    这样它能在 PC 上用普通 `javac` 编译、拿**真实的 502 KB 桌面 settings.bin**
 *    做「读→写→逐字节比对」，并用 **arc 自己的 `Settings`** 当神谕读回（见 REF §50.8）。
 *    「这个槽的 settings.bin 在哪」由 {@link Mods#settingsFileOf} 负责（那边本来就要 Context）。
 *
 * ── 🔴 改写的三条硬约束（源码根据，缺一不可）────────────────────────────────
 *  1. **改前备份**（{@link #applyBool} 落到 `<hub>/settings-backups/slot-<槽>/`）；
 *  2. **原子写**（同目录临时文件 + 改名，见 {@link #writeAtomic}）；
 *  3. **写后自检可解码**（读回 + 「恰好 EOF」+ **所有键逐条比对**，不过就**自动还原**）。
 *
 * 原因（`Settings.saveValues()` 的 catch 分支）：
 * <pre>
 *     }catch(Throwable e){
 *         Log.err(e);
 *         file.delete();          // 源码注释原文："file is now corrupt, delete it"
 *     }
 * </pre>
 * ⇒ 我们写出一个坏文件之后，游戏**下一次自己保存时出错会把设置整份删掉**。
 *
 * ⚠️ 另两条同源的纪律：
 *  · **必须原样保留未知键**（解出认识的键就重建文件 = 抹掉游戏后续版本新增的键，
 *    与桌面版 `config.json` 的 `_extras` 是同一条教训）。这里的做法是
 *    「**读出什么就写回什么**」：{@link Values} 保序，改键只做**原位替换 / 末尾追加**。
 *  · **必须在游戏未运行时改**（游戏退出时会整份重写这个文件）⇒ 门禁在
 *    {@link Mods#setEnabled}（`Data.gameAlive`），本类不碰 Context。
 */
public final class SettingsBin {
    private SettingsBin() {}

    public static final int TYPE_BOOL = 0;
    public static final int TYPE_INT = 1;
    public static final int TYPE_LONG = 2;
    public static final int TYPE_FLOAT = 3;
    public static final int TYPE_STRING = 4;
    public static final int TYPE_BINARY = 5;

    /** 备份保留份数（游戏自己留 10 份；我们留 20，超出按文件名时间戳删最旧） */
    public static final int KEEP_BACKUPS = 20;

    /**
     * 解析结果：**保序**的键值表（顺序 = 文件里的顺序 ⇒ 写回时能逐字节一致）。
     * 值类型：`Boolean` / `Integer` / `Long` / `Float` / `String` / `byte[]`。
     */
    public static final class Values {
        private final LinkedHashMap<String, Object> map;

        /** 整份是不是 zlib 压缩的（arc 的 writeCompressed 分支）；写回时**沿用**它 */
        public final boolean compressed;
        /** 文件字节数（报告里对照用） */
        public final long fileBytes;

        Values(LinkedHashMap<String, Object> map, boolean compressed, long fileBytes) {
            this.map = map;
            this.compressed = compressed;
            this.fileBytes = fileBytes;
        }

        public int size() {
            return map.size();
        }

        public boolean has(String key) {
            return map.containsKey(key);
        }

        /** 值的 Java 类型名（诊断报告用；键不存在 ⇒ null） */
        public String typeOf(String key) {
            Object v = map.get(key);
            if (v == null) return null;
            if (v instanceof Boolean) return "bool";
            if (v instanceof Integer) return "int";
            if (v instanceof Long) return "long";
            if (v instanceof Float) return "float";
            if (v instanceof String) return "string";
            if (v instanceof byte[]) return "binary(" + ((byte[]) v).length + ")";
            return v.getClass().getSimpleName();
        }

        /**
         * 布尔读法 —— **与游戏一致**：键不存在 / 类型不对 ⇒ 返回 `def`。
         * ⚠️ 这条是模组启停的核心语义："**键不存在 ≠ 禁用**"（默认 true）。
         */
        public boolean getBool(String key, boolean def) {
            Object v = map.get(key);
            return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : def;
        }

        public int getInt(String key, int def) {
            Object v = map.get(key);
            return (v instanceof Integer) ? ((Integer) v).intValue() : def;
        }

        public String getString(String key, String def) {
            Object v = map.get(key);
            return (v instanceof String) ? (String) v : def;
        }

        /** 只读视图（诊断/自检遍历用） */
        public Map<String, Object> all() {
            return Collections.unmodifiableMap(map);
        }

        /** 所有以 `prefix` 开头的键（按文件顺序）—— 「有哪些 mod-* 键」就靠它 */
        public List<String> keysWithPrefix(String prefix) {
            List<String> out = new ArrayList<>();
            for (String k : map.keySet()) {
                if (k.startsWith(prefix)) out.add(k);
            }
            return out;
        }

        /**
         * 复制一份并替换 / 追加一个键 —— **保序**：键已存在就**原位**换值（位置不动），
         * 不存在就**追加到末尾**。
         *
         * ★ 这两条就是"逐字节一致"的全部秘密：真实文件里键的顺序是游戏自己写出来的，
         *   我们换个值不能把它挪位置，否则同一个键内容不变、文件却整片不同
         *   （长度可能只差几字节，而**没有任何症状**）。
         */
        public Values with(String key, Object value) {
            LinkedHashMap<String, Object> m = copyMap();
            m.put(key, value);
            return new Values(m, compressed, fileBytes);
        }

        /** 深拷贝（`byte[]` 也复制 —— 免得调用方改到"原件"） */
        public Values copy() {
            return new Values(copyMap(), compressed, fileBytes);
        }

        private LinkedHashMap<String, Object> copyMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                m.put(e.getKey(), (v instanceof byte[]) ? ((byte[]) v).clone() : v);
            }
            return m;
        }
    }

    // ══ 读 ═════════════════════════════════════════════════════════════════

    /**
     * 读并解析一个 `settings.bin`。
     *
     * @throws IOException 文件不存在 / 打不开 / 头荒谬 / 类型未知 / 没恰好消费完
     *                     （**读侧的最后一条就是游戏的损坏判据**，我们照抄）
     */
    public static Values read(File f) throws IOException {
        if (f == null) throw new IOException("settings.bin 路径为 null");
        if (!f.isFile()) throw new IOException("不是文件：" + f.getAbsolutePath());
        long fileBytes = f.length();

        InputStream raw = new BufferedInputStream(new FileInputStream(f), 8192);
        try {
            // 压缩嗅探：与 arc 同一条件（先读 2 字节，再回到起点）
            raw.mark(2);
            int b0 = raw.read();
            int b1 = raw.read();
            boolean compressed = Util.isZlibHeader(b0, b1);   // 判据只此一处（见 Util）
            raw.reset();

            InputStream in = compressed ? new InflaterInputStream(raw) : raw;
            DataInputStream s = new DataInputStream(in);
            int amount = s.readInt();
            // 游戏原话：一堆 0 就是损坏（Settings.java:169）
            if (amount <= 0) throw new IOException("count = " + amount + "（游戏自己判为损坏文件）");

            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < amount; i++) {
                String key = s.readUTF();
                int type = s.readByte() & 0xff;
                switch (type) {
                    case TYPE_BOOL:   map.put(key, Boolean.valueOf(s.readBoolean())); break;
                    case TYPE_INT:    map.put(key, Integer.valueOf(s.readInt())); break;
                    case TYPE_LONG:   map.put(key, Long.valueOf(s.readLong())); break;
                    case TYPE_FLOAT:  map.put(key, Float.valueOf(s.readFloat())); break;
                    case TYPE_STRING: map.put(key, s.readUTF()); break;
                    case TYPE_BINARY: {
                        int len = s.readInt();
                        // ★ 硬化（有意偏离 arc 的裸 readFully）：损坏的 count 会让 len 是个天文数字，
                        //   直接 new byte[len] 就是 OOM。合法文件里单个值不可能比整份文件还长。
                        if (len < 0 || len > fileBytes) {
                            throw new IOException("第 " + (i + 1) + " 项「" + key
                                    + "」声明的长度不合理：" + len + " 字节（文件共 " + fileBytes + "）");
                        }
                        byte[] bytes = new byte[len];
                        s.readFully(bytes);
                        map.put(key, bytes);
                        break;
                    }
                    default:
                        throw new IOException("未知的值类型 " + type + "（第 " + (i + 1)
                                + " 项「" + key + "」）");
                }
            }
            // ★ 恰好 EOF —— 这条就是"文件没坏"的判据（读侧唯一的结构性校验）
            int end = s.read();
            if (end != -1) {
                throw new IOException("多出尾部数据（Trailing settings data; expected EOF），"
                        + "首个多余字节 = 0x" + Integer.toHexString(end));
            }
            return new Values(map, compressed, fileBytes);
        } finally {
            try {
                raw.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 宽容读：读不到就返回 `null`，把"为什么读不到"写进 {@code reason[0]}。
     *
     * ★ 为什么留这个口：模组列表是**只读展示**，一个坏掉的/还没有的 `settings.bin`
     *   不该让整个页面失败 —— 但**也不能假装"没有键"**（那会把"读失败"显示成
     *   "所有模组都是启用状态"，是最误导人的一种失败）⇒ 调用方拿到 null 时必须
     *   在界面上**说出来**，见 {@link Mods.Scan#settingsNote}。
     */
    public static Values readSafe(File f, String[] reason) {
        try {
            return read(f);
        } catch (Throwable t) {
            if (reason != null && reason.length > 0) reason[0] = String.valueOf(t.getMessage());
            return null;
        }
    }

    // ══ 写：编码 / 原子落盘 ═════════════════════════════════════════════════

    /**
     * 按 arc 的写侧格式编码成字节（**不落盘**）。
     *
     * ★ 存在的意义：让"我们写出的东西与原件逐字节一致"这件事**可以被直接断言**
     *   （真机上拿真实 `settings.bin` 读一遍再编码，与磁盘原文比字节 —— 全程只读）。
     */
    public static byte[] encode(Values v) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, (int) v.fileBytes + 64));
        OutputStream os = v.compressed ? new DeflaterOutputStream(bo) : bo;
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(os, 8192));
        try {
            out.writeInt(v.size());
            for (Map.Entry<String, Object> e : v.all().entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                out.writeUTF(key);
                if (value instanceof Boolean) {
                    out.writeByte(TYPE_BOOL);
                    out.writeBoolean(((Boolean) value).booleanValue());
                } else if (value instanceof Integer) {
                    out.writeByte(TYPE_INT);
                    out.writeInt(((Integer) value).intValue());
                } else if (value instanceof Long) {
                    out.writeByte(TYPE_LONG);
                    out.writeLong(((Long) value).longValue());
                } else if (value instanceof Float) {
                    out.writeByte(TYPE_FLOAT);
                    out.writeFloat(((Float) value).floatValue());
                } else if (value instanceof String) {
                    out.writeByte(TYPE_STRING);
                    out.writeUTF((String) value);
                } else if (value instanceof byte[]) {
                    byte[] b = (byte[]) value;
                    out.writeByte(TYPE_BINARY);
                    out.writeInt(b.length);
                    out.write(b);
                } else {
                    throw new IOException("不支持的值类型：" + (value == null ? "null"
                            : value.getClass().getName()) + "（键「" + key + "」）");
                }
            }
            out.flush();
        } finally {
            out.close();        // 必须 close：压缩流要在这里收尾（否则 deflate 尾巴不完整）
        }
        return bo.toByteArray();
    }

    /**
     * **原子写**：同目录临时文件 + 改名。
     *
     * ⚠️ 三条细节都是有意的：
     *  · 临时文件放**同一个目录**（跨文件系统 rename 不是原子操作，甚至可能失败）；
     *  · 名字以 `.tmp` 结尾 —— 本工程的「槽内容」口径（{@link Data#contentSkipped}）
     *    本来就把 `*.tmp` 排除在备份/导出之外 ⇒ 万一留下残骸，也不会被当成用户数据；
     *  · 目标已存在时部分文件系统拒绝覆盖式 rename ⇒ 退化成"删了再改名"
     *    （与 {@link Util#atomicWriteText} 同一套写法）。
     */
    public static void writeAtomic(File target, Values v) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("建目录失败：" + dir.getAbsolutePath());
        }
        byte[] data = encode(v);
        File tmp = new File(dir, target.getName() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(data);
            fo.flush();
            fo.getFD().sync();      // 落盘再改名：断电时不会留下"名字已换、内容没写"的窗口
        } finally {
            fo.close();
        }
        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                tmp.delete();
                throw new IOException("无法替换原文件：" + target.getAbsolutePath());
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("改名失败：" + tmp.getName() + " → " + target.getName());
            }
        }
    }

    /**
     * ★ 纯函数：两份键表的**键集合、顺序与值**是否完全一致（`byte[]` 逐字节比）。
     * 写后自检就靠它 —— 它是"我写出去的东西是不是我要写的"这条判据的**唯一实现**。
     */
    public static boolean sameValues(Values a, Values b) {
        if (a == null || b == null) return false;
        List<String> ka = new ArrayList<>(a.all().keySet());
        List<String> kb = new ArrayList<>(b.all().keySet());
        if (!ka.equals(kb)) return false;
        for (String k : ka) {
            Object x = a.all().get(k);
            Object y = b.all().get(k);
            if (x instanceof byte[] && y instanceof byte[]) {
                if (!Arrays.equals((byte[]) x, (byte[]) y)) return false;
            } else if (x == null ? y != null : !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    /** 逐字节差异计数（报告里说"只差 N 字节"用；长度不同就按共同长度比 + 长度差） */
    public static int diffBytes(byte[] a, byte[] b) {
        if (a == null || b == null) return -1;
        int n = Math.min(a.length, b.length);
        int d = Math.abs(a.length - b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) d++;
        }
        return d;
    }

    // ══ 安全改写（F13 第二阶段：模组启停的唯一入口） ═══════════════════════

    /** 记下"错在哪"：**码 + 参数给界面**（`SettingsText` 映射），中文句子留给 `report()` / 自检 */
    private static Result err(Result r, int code, String zh, String s1, String s2) {
        r.errCode = code;
        r.errS1 = s1;
        r.errS2 = s2;
        r.error = zh;
        return r;
    }

    /** 一次安全改写的结果（人读报告 + 机器可判的字段） */
    public static final class Result {
        // ── ★ 错误码（P3，2026-10-05）：给界面用的「码 + 参数」─────────────────────
        // 为什么要有它：本类是**刻意纯 Java** 的（见类注释"能在 PC 上单独编译验证"）⇒ 不能 `getString`。
        // 可 {@link #error} 里**我们自己写的那几句中文**会经 `userReason()` **原样到用户眼前**
        // （模组启停失败的第一层弹窗）⇒ 加这一层：核心只说"错在哪、带哪些参数"，
        // 文案由 Android 侧的 `SettingsText` 映射成资源。
        // ⚠️ {@link #error} **一个字都不动** —— `report()`（「技术细节」第二层）、dev 落盘报告与
        //    自检看的仍是它（工程纪律，见 `MsavText` 那条同款注释）。
        // ⚠️ 新增错误时**必须**同时：加一个码 + 在 `SettingsText` 里加一条映射 + 自检里过一遍
        //    （自检**遍历 `ALL_CODES`**，漏映射会被抓住；否则界面上是**静默空白**）。
        /** 没有码 */
        public static final int E_NONE = 0;
        /** 目标文件为 null */
        public static final int E_NULL_FILE = 1;
        /** 没有要改的键 */
        public static final int E_NO_KEYS = 2;
        /** 建备份目录失败；s1 = 目录 */
        public static final int E_BACKUP_MKDIR = 3;
        /** 备份校验失败；s1 = 读不回的原因 */
        public static final int E_BACKUP_VERIFY = 4;
        /** 写后自检不过 ⇒ 已还原；s1 = 具体原因（见下面的 SUB_*） */
        public static final int E_VERIFY_ROLLBACK = 5;
        /** 写后自检不过**且还原也失败**；s1 = 具体原因，s2 = 还原时的异常 */
        public static final int E_VERIFY_ROLLBACK_FAIL = 6;
        /** 写后自检不过 ⇒ 删掉了刚新建的文件；s1 = 具体原因 */
        public static final int E_VERIFY_DELETED = 7;

        /** 自检失败的"具体原因"：嵌在上面三条的 `%1$s` 里 */
        public static final int SUB_NONE = 0;
        /** 写回后读出的键表与预期不一致 */
        public static final int SUB_MISMATCH = 1;
        /** 写回后读不出来；s1 = 读失败的原因 */
        public static final int SUB_UNREADABLE = 2;

        /** **所有**错误码（给自检**遍历**用：漏一条映射在界面上是静默空白，只有遍历抓得住） */
        public static final int[] ALL_CODES = {
                E_NULL_FILE, E_NO_KEYS, E_BACKUP_MKDIR, E_BACKUP_VERIFY,
                E_VERIFY_ROLLBACK, E_VERIFY_ROLLBACK_FAIL, E_VERIFY_DELETED,
        };

        /** 码 + 参数（界面用）；`errS1` 也给 `SUB_*` 当参数 */
        public int errCode = E_NONE;
        public int subCode = SUB_NONE;
        public String errS1;
        public String errS2;

        /** 造一个"只带错误码"的实例：**给自检遍历所有码用**（自带 SUB_UNREADABLE，参数会流进句子） */
        static Result withCode(int code, String s1, String s2) {
            Result r = new Result();
            r.errCode = code;
            r.subCode = SUB_UNREADABLE;
            r.errS1 = s1;
            r.errS2 = s2;
            return r;
        }

        public boolean ok;
        /** 失败原因（ok=false 时不为 null）—— ⚠️ **给报告/自检看的原文**，别拿它直接显示给用户 */
        public String error;
        /** 什么都没改（例如"键不存在 + 目标就是默认值"） */
        public boolean noop;
        public File file;
        public File backup;
        public String changeDesc = "";
        public long bytesBefore;
        public long bytesAfter;
        public int diffBytes = -1;
        public int keys;
        public boolean verified;

        /** 人读报告（**dev 口落盘 / 「技术细节」第二层**共用一处；第一层不再用它，见下） */
        public String report() {
            StringBuilder sb = new StringBuilder();
            if (!ok) {
                sb.append("❌ 未改动：").append(error).append('\n');
                if (file != null) sb.append("文件：").append(file.getAbsolutePath()).append('\n');
                return sb.toString();
            }
            if (noop) {
                sb.append("（没动文件）").append(changeDesc).append('\n');
                return sb.toString();
            }
            sb.append("✅ ").append(changeDesc).append('\n');
            sb.append("文件：").append(file.getAbsolutePath()).append('\n');
            sb.append("体积：").append(bytesBefore).append(" → ").append(bytesAfter)
              .append(" B（差 ").append(diffBytes).append(" 字节）\n");
            sb.append("键数：").append(keys).append("（未知键**原样保留**）\n");
            sb.append("写后自检：").append(verified ? "通过（恰好 EOF + 所有键逐条比对一致）" : "未通过")
              .append('\n');
            if (backup != null) {
                sb.append("改前备份：").append(backup.getAbsolutePath()).append('\n');
            }
            return sb.toString();
        }

        /**
         * ★★ **给用户看**的失败原因 —— ⚠️ **2026-10-05（P3）搬走了**：原来这个方法里透传我们自己的
         * 中文（"游戏正在运行…"）⇒ 现在核心只出 {@link #errCode} + 参数，文案在 Android 侧的
         * {@link SettingsText#userReason} 里映射成资源（本类要保持**纯 Java**、能在 PC 上单独编译）。
         * 自检会**遍历 {@link #ALL_CODES}** 确认每条都有映射。
         */
    }

    /**
     * ★★ **改一个布尔键**（模组启停）：备份 → 原子写 → 读回自检 → 不过**自动还原**。
     *
     * 五步，每步失败都**不产出坏文件**：
     *  ① 读原件（读不出来 ⇒ **拒绝写入**，绝不覆盖一个我们看不懂的文件）；
     *  ② 备份原件到 {@code backupDir}（并核对 md5）；
     *  ③ 保序替换/追加那个键 → 原子写；
     *  ④ 读回自检：能解码 + 恰好 EOF + **所有键与预期的完全一致**；
     *  ⑤ 自检不过 ⇒ 用备份**还原**，并如实报告（宁可回到原状，也不留一个可疑文件）。
     *
     * @param file      目标 `settings.bin`（不存在 ⇒ 新建一份只含这个键的）
     * @param backupDir 备份目录（不存在则创建；`null` = 不备份 —— **产品路径永远不传 null**）
     */
    public static Result applyBool(File file, File backupDir, String key, boolean value) {
        java.util.LinkedHashMap<String, Boolean> one = new java.util.LinkedHashMap<>();
        one.put(key, Boolean.valueOf(value));
        return applyBools(file, backupDir, one);
    }

    /**
     * ★★ 一次改**多个** bool 键：**一次读 + 一次原子写 + 一次读回自检**（备份、自检不过就还原，照旧）。
     *
     * 🔴 为什么不循环调 {@link #applyBool}：那是 N 次读 + N 份备份 + N 次写**同一个文件** ——
     *   慢，而且每多写一次就多一次把设置写坏的机会（写坏的后果见类注释：游戏会把设置整份删掉）。
     *   「全部启用」在 20 个模组上应该是**一次写**，不是 20 次。
     *
     * ★ **不需要改的键一个字节都不写**：
     *   · 键存在 + 值已经相同 ⇒ 跳过（写一遍恰好一样没有意义，而且每多写一次就多一次风险）；
     *   ⚠️ **不做**「键不存在 + 目标 true ⇒ 跳过」那种判断 —— 那是**模组开关专属**的语义
     *     （`mod-<名>-enabled` 不存在 = 默认启用），对本层其它键"不存在"就是没有，必须写进去。
     *     放在这里会让通用层替调用方猜语义（自检 ⑯ 那条「新键要追加到末尾」当场就判死了这个错误）。
     *   · 一个都不需要改 ⇒ `noop = true`，**连备份都不做、文件不碰**（干净槽上「全部启用」= 零写入）。
     *
     * @param values 要改的键（`mod-<名>-enabled`）→ 目标值；按传入顺序逐个改
     */
    public static Result applyBools(File file, File backupDir, java.util.Map<String, Boolean> values) {
        Result r = new Result();
        r.file = file;
        if (file == null) {
            return err(r, Result.E_NULL_FILE, "目标文件为 null", null, null);
        }
        if (values == null || values.isEmpty()) {
            return err(r, Result.E_NO_KEYS, "没有要改的键", null, null);
        }
        try {
            // ① 读原件（坏文件 ⇒ read() 直接抛，绝不往下走）
            Values before;
            byte[] beforeBytes;
            boolean fresh = !file.isFile();
            if (!fresh) {
                before = read(file);
                beforeBytes = readAllBytes(file);
            } else {
                before = new Values(new LinkedHashMap<String, Object>(), false, 0);
                beforeBytes = new byte[0];
            }
            r.bytesBefore = beforeBytes.length;
            r.keys = before.size();

            // ①b 只改「真的会变」的键 —— changeDesc 也从这里出来（报告里看得出改了什么）
            StringBuilder desc = new StringBuilder();
            Values after = before;
            int changed = 0;
            for (java.util.Map.Entry<String, Boolean> e : values.entrySet()) {
                String key = e.getKey();
                boolean want = e.getValue() != null && e.getValue().booleanValue();
                Object old = after.all().get(key);
                // 注意：这里**不**判"不存在就跳过" —— 见方法注释里那条分层纪律
                if (old instanceof Boolean && ((Boolean) old).booleanValue() == want) continue;
                after = after.with(key, Boolean.valueOf(want));
                changed++;
                if (desc.length() > 0) desc.append("；");
                desc.append(key).append(": ")
                    .append(old == null ? "（未设置⇒默认 true）" : String.valueOf(old))
                    .append(" → ").append(want);
            }
            if (changed == 0) {
                r.ok = true;
                r.noop = true;
                r.changeDesc = "这 " + values.size() + " 个键本来就已经是目标值，文件一个字节都没动";
                return r;
            }
            if (fresh) desc.insert(0, "（新建 settings.bin）");
            r.changeDesc = desc.toString();
            return commit(r, file, backupDir, before, beforeBytes, after);
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    /**
     * 改完之后的**同一套收尾**：② 备份 → ③ 原子写 → ④ 读回自检 → ⑤ 不过就还原。
     * ★ 单键与多键**共用这一处**：安全流水线只许有一份实现 ——
     *   两处实现迟早只改一处，而这里改错的代价是「游戏的设置被删掉」（见类注释）。
     */
    private static Result commit(Result r, File file, File backupDir, Values before,
                                 byte[] beforeBytes, Values after) throws Exception {
        // ② 备份
        if (backupDir != null && beforeBytes.length > 0) {
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                return err(r, Result.E_BACKUP_MKDIR, "建备份目录失败："
                        + backupDir.getAbsolutePath(), backupDir.getAbsolutePath(), null);
            }
            File bk = new File(backupDir, stamp() + ".bin");
            FileOutputStream fo = new FileOutputStream(bk);
            try {
                fo.write(beforeBytes);
                fo.flush();
                fo.getFD().sync();
            } finally {
                fo.close();
            }
            // 备份必须**能被读回且与原件一致**，否则宁可不改（备份是最后一道防线）
            String[] why = new String[1];
            Values bkRead = readSafe(bk, why);
            if (bkRead == null || !sameValues(before, bkRead)) {
                bk.delete();
                return err(r, Result.E_BACKUP_VERIFY,
                        "备份校验失败（写出的备份读不回或与原文件不一致）：" + why[0],
                        why[0], null);
            }
            r.backup = bk;
            pruneBackups(backupDir, KEEP_BACKUPS);
        }

        // ③ 原子写
        writeAtomic(file, after);
        r.bytesAfter = file.length();
        r.diffBytes = diffBytes(beforeBytes, readAllBytes(file));
        r.keys = after.size();

        // ④ 读回自检
        String[] why = new String[1];
        Values back = readSafe(file, why);
        boolean okRead = back != null;
        r.verified = okRead && sameValues(after, back);
        if (!r.verified) {
            // ⑤ 还原（自检不过 = 我写出去的东西不是我要写的 ⇒ 回到原状最安全）
            String detail = okRead ? "写回后读出的键表与预期不一致" : ("写回后读不出来：" + why[0]);
            int sub = okRead ? Result.SUB_MISMATCH : Result.SUB_UNREADABLE;
            if (beforeBytes.length > 0) {
                try {
                    Values orig = new Values(readMapOf(before), before.compressed, before.fileBytes);
                    writeAtomic(file, orig);
                    err(r, Result.E_VERIFY_ROLLBACK, "自检失败（" + detail + "）⇒ 已用备份还原原文件",
                            why[0], null).subCode = sub;
                } catch (Throwable t2) {
                    err(r, Result.E_VERIFY_ROLLBACK_FAIL,
                            "自检失败（" + detail + "）且还原失败：" + t2
                                    + "；备份在 " + (r.backup == null ? "（无）" : r.backup.getAbsolutePath()),
                            why[0], String.valueOf(t2)).subCode = sub;
                }
            } else {
                file.delete();
                err(r, Result.E_VERIFY_DELETED, "自检失败（" + detail + "）⇒ 已删除刚新建的文件",
                        why[0], null).subCode = sub;
            }
            return r;
        }
        r.ok = true;
        return r;
    }

    /** 只保留最近 `keep` 份备份（文件名是可按字典序排序的时间戳） */
    public static int pruneBackups(File dir, int keep) {
        if (dir == null) return 0;
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= keep) return 0;
        List<File> all = new ArrayList<>();
        Collections.addAll(all, fs);
        Collections.sort(all, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        int removed = 0;
        for (int i = 0; i < all.size() - keep; i++) {
            if (all.get(i).delete()) removed++;
        }
        return removed;
    }

    /** `yyyyMMdd-HHmmss`（备份文件名；字典序 = 时间序） */
    static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static LinkedHashMap<String, Object> readMapOf(Values v) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : v.all().entrySet()) {
            Object x = e.getValue();
            m.put(e.getKey(), (x instanceof byte[]) ? ((byte[]) x).clone() : x);
        }
        return m;
    }

    static byte[] readAllBytes(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE - 8) throw new IOException("文件过大：" + len);
        byte[] out = new byte[(int) len];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != out.length) throw new IOException("读到的字节数不符：" + off + "/" + out.length);
        } finally {
            in.close();
        }
        return out;
    }
}
