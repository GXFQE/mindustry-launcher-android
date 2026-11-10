package io.mdt.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DEX 里的**装载名（class descriptor）**解析 —— 补丁机制 v1 的冲突检测底座（2026-11-10）。
 *
 * <h3>为什么自己解析，不用「信清单」</h3>
 * 补丁包里的 {@code patch.json} 是**生成器写的**，它可以声明 {@code classes} 字段；但那是
 * 单方面声明 —— 写错 / 故意少写就能让两个补丁同时改同一个类而"看起来不冲突"
 * （后果是后注入的那个静默赢，另一个的一半改动失效，游戏行为不可预测）。
 * 冲突检测的真正判据必须是**补丁 dex 里实际有哪些类** ⇒ 直接读 DEX 的 {@code class_defs}。
 *
 * <p>这层解析只做"列名字"，不验证字节码、不做校验和 —— 它的用途是**冲突检测与展示**，
 * 不是信任边界（同 UID 下本来也没有真隔离）。输入是**不可信数据**，所以每一步都做范围检查：
 * 任何越界都抛 {@link IOException}，绝不把异常数据读成"看起来没问题"。
 *
 * <h3>DEX 头里要用的字段（偏移全部按 little-endian，见 dex-format 官方文档）</h3>
 * <pre>
 *   0x00 magic[8]            "dex\n035\0"
 *   0x28 endian_tag          u4 = 0x12345678（反字节序 0x78563412 不认：真机上不会出现）
 *   0x38 string_ids_size     u4
 *   0x3C string_ids_off      u4
 *   0x40 type_ids_size       u4
 *   0x44 type_ids_off        u4
 *   0x60 class_defs_size     u4
 *   0x64 class_defs_off      u4
 *   class_def   (32 B)  +0  class_idx u4  → type_ids[class_idx] → string_id → MUTF-8
 * </pre>
 *
 * <p>纯 JDK（无 android import）⇒ 设备自检可以直接喂字节，不需要真机 dex。
 */
final class PatchDex {

    /** DEX 文件头长度（0x70）。 */
    static final int HEADER_SIZE = 0x70;
    /** 单个 dex 最多接受多少个 class_def（防坏文件把解析拖死；游戏本体 4 千级）。 */
    static final int MAX_CLASSES = 100000;
    /** 单个类名（descriptor）最长接受多少字节（dex 里字符串本身有界，这里再兜一层）。 */
    private static final int MAX_DESCRIPTOR = 512;

    private PatchDex() {}

    /** 只认 `dex\n0xx` 这四字节 + 长度够头；返回 false ⇒ 调用方按"不是 dex"拒绝。 */
    static boolean looksLikeDex(byte[] dex) {
        return dex != null && dex.length >= HEADER_SIZE
                && dex[0] == 'd' && dex[1] == 'e' && dex[2] == 'x' && dex[3] == '\n';
    }

    /**
     * 列出 dex 里**定义**的全部类（含内部类），返回 java 点分名（如 `io.mdt.launcher.Patches`）。
     *
     * <p>排序用 {@link LinkedHashSet} 保插入序（= class_defs 顺序）去重；调用方要稳定比较时
     * 自己再排 —— 这里不替它决定"展示顺序"。
     *
     * <p>★ 解析前先校验文件头（第 345 轮独立校验 B2）：`file_size` 必须等于实际长度、
     * **Adler-32 校验和**必须对得上。理由：ART 会因为坏 checksum 拒绝整份 dex，而
     * `DexPathList.makeDexElements` 把这种失败塞进 suppressed **不抛异常** ⇒ 注入"看起来成功"
     * 但类根本没进去。导入阶段就把它判死，才能让用户看到"dex 无效"而不是游戏莫名其妙没变化。
     *
     * @throws IOException 不是合法 dex / 头字段越界 / 校验和不符 / 字符串表指到文件外
     */
    static List<String> classes(byte[] dex) throws IOException {
        checkHeader(dex);
        int stringSize = u4(dex, 0x38);
        int stringOff = u4(dex, 0x3C);
        int typeSize = u4(dex, 0x40);
        int typeOff = u4(dex, 0x44);
        int classSize = u4(dex, 0x60);
        int classOff = u4(dex, 0x64);

        checkTable(dex, "string_ids", stringOff, stringSize, 4);
        checkTable(dex, "type_ids", typeOff, typeSize, 4);
        checkTable(dex, "class_defs", classOff, classSize, 32);
        if (classSize > MAX_CLASSES) throw new IOException("too many classes: " + classSize);

        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < classSize; i++) {
            int def = classOff + i * 32;
            int classIdx = u4(dex, def);
            if (classIdx >= typeSize) throw new IOException("class_idx out of range at " + i);
            int typeIdx = u4(dex, typeOff + classIdx * 4);
            if (typeIdx >= stringSize) throw new IOException("descriptor_idx out of range at " + i);
            int strData = u4(dex, stringOff + typeIdx * 4);
            String descriptor = mutf8(dex, strData);
            String dotted = dotted(descriptor);
            if (dotted != null) out.add(dotted);
        }
        return new ArrayList<String>(out);
    }

    /** 类的个数（= `class_defs_size`，不做逐条解析；清单展示用）。 */
    static int classCount(byte[] dex) throws IOException {
        checkHeader(dex);
        return u4(dex, 0x60);
    }

    // ── 内部：头校验 / 表范围 / 字符串 ──────────────────────────────────

    /**
     * DEX 文件头校验（比"magic 对不对"强一档）：长度 / endian / file_size / Adler-32。
     * 任一不符 ⇒ 这不是一份能交给 ART 的 dex。
     */
    private static void checkHeader(byte[] dex) throws IOException {
        if (!looksLikeDex(dex)) throw new IOException("not a dex file (bad magic)");
        if (u4(dex, 0x28) != 0x12345678) throw new IOException("dex endian_tag != 0x12345678");
        int fileSize = u4(dex, 0x20);
        if (fileSize != dex.length) {
            throw new IOException("dex file_size mismatch: header=" + fileSize + " actual=" + dex.length);
        }
        int stored = u4(dex, 0x08);
        int computed = adler32(dex, 12, dex.length);
        if (stored != computed) {
            throw new IOException("dex checksum mismatch: header=" + Integer.toHexString(stored)
                    + " computed=" + Integer.toHexString(computed));
        }
    }

    /**
     * DEX 头里的 Adler-32（从 offset 12 到文件尾）。
     *
     * <p>⚠️ 累加器必须用 **long**：经典实现按 5552 字节一块延迟取模，块内 `b` 的最大值约
     * `255*5552*5553/2 ≈ 3.9e9` —— 超出 Java `int`（2^31-1）⇒ 会算出"校验和不符"把好 dex 判死。
     * （自检当场抓到过一次：本 APK 自己的 classes.dex 被误判。）
     */
    private static int adler32(byte[] b, int off, int end) {
        long a = 1, acc = 0;
        final long mod = 65521;
        int i = off;
        while (i < end) {
            int block = Math.min(end, i + 5552);
            while (i < block) {
                a += b[i++] & 0xFF;
                acc += a;
            }
            a %= mod;
            acc %= mod;
        }
        return (int) (((acc << 16) | a) & 0xFFFFFFFFL);
    }

    private static void checkTable(byte[] dex, String name, int off, int count, int itemSize)
            throws IOException {
        if (count < 0 || off < 0) throw new IOException("bad " + name + " offset");
        long end = (long) off + (long) count * (long) itemSize;
        if (end > dex.length) {
            throw new IOException(name + " exceeds file: off=" + off
                    + " count=" + count + " len=" + dex.length);
        }
    }

    /**
     * 读一条 MUTF-8 字符串（dex 的 string_data_item = uleb128(utf16长度) + 字节 + NUL）。
     *
     * <p>类名（descriptor）是纯 ASCII，所以这里**只按 ASCII/UTF-8 解**：遇到非 ASCII 字节时
     * 直接用 UTF-8 解码也能得到"差不多"的名字，但类名不该出现非 ASCII —— 真出现就说明这不是
     * 正常的类表，直接拒绝更安全（宁可报"坏 dex"，也不要拿一个可能被篡改的名字去做冲突判定）。
     */
    private static String mutf8(byte[] dex, int off) throws IOException {
        if (off <= 0 || off >= dex.length) throw new IOException("string data off out of range: " + off);
        int[] pos = {off};
        readUleb(dex, pos);            // utf16 长度：我们不按它截断，靠 NUL 结束（两者应当一致）
        int start = pos[0];
        int end = start;
        while (end < dex.length && dex[end] != 0) end++;
        if (end >= dex.length) throw new IOException("unterminated string at " + start);
        int n = end - start;
        if (n <= 0 || n > MAX_DESCRIPTOR) throw new IOException("bad string length " + n);
        StringBuilder sb = new StringBuilder(n);
        for (int i = start; i < end; i++) {
            int b = dex[i] & 0xFF;
            if (b >= 0x80) throw new IOException("non-ascii descriptor byte at " + i);
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static int readUleb(byte[] dex, int[] pos) throws IOException {
        int result = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (pos[0] >= dex.length) throw new IOException("uleb overrun");
            int b = dex[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("uleb too long");
    }

    /** `Lio/mdt/launcher/Foo;` → `io.mdt.launcher.Foo`；不是对象类型（`[I` / 基本类型）返回 null。 */
    private static String dotted(String descriptor) {
        if (descriptor.length() < 3) return null;
        if (descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') return null;
        String inner = descriptor.substring(1, descriptor.length() - 1);
        if (inner.indexOf('/') < 0 && inner.isEmpty()) return null;
        return inner.replace('/', '.');
    }

    private static int u4(byte[] b, int off) throws IOException {
        if (off < 0 || off + 4 > b.length) throw new IOException("read past end at " + off);
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /** 排序后的类名副本（冲突比较 / 展示前的规范形态；调用方需要稳定顺序时用它）。 */
    static List<String> sorted(List<String> classes) {
        ArrayList<String> out = new ArrayList<String>(classes);
        Collections.sort(out);
        return out;
    }
}
