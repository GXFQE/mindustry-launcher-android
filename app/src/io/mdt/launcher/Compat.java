package io.mdt.launcher;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 版本兼容性探测（F18）—— **这个包能不能进 MDT 的加载管线**。
 *
 * ★★ 判据是**能力**，不是版本号。原因（2026-10-02 扫了 33 个官方包实测）：
 *   按版本号切会把 v105~v146 整段误伤 —— 它们**真能跑**（arc native 齐、入口类在）；
 *   而真正装不进管线的是「只有 libgdx.so 的 gdx 时代」，且**魔改包同样会踩**
 *   （改包名、加固、抽走某个 so 都可能发生）⇒ 只能现探。
 *
 * 四项**硬**判据（缺一 ⇒ 装进去也跑不起来 ⇒ 拒绝）：
 *   ① 入口类 {@link Injector#GAME_ACTIVITY} 在不在 dex 里
 *      —— pre-v104 用 `io.anuke.mindustry.AndroidLauncher`；加固包 dex 加密后也搜不到。
 *   ② native 库名：`lib/&lt;abi&gt;/` 下有没有 {@link Injector#NATIVE_LIBS}
 *      —— v104.10 及更早只有 `libgdx.so`；我们的管线只解 arc 系。
 *      🔴 这条正是"静默出错"的来源：v104.10 **能通过导入预检**（入口类在），
 *      却在启动时卡在 native 步；若同进程先跑过别的版本还会被掩盖（见 Injector.loadNatives）。
 *   ③ ABI：包内 ABI 与设备 {@link Build#SUPPORTED_ABIS} 有无交集。
 *   ④ 页对齐：`libarc.so` 的 `PT_LOAD` 对齐 ≥ 设备页大小
 *      —— Android 15+ 在 **16 KB 页**设备上会拒载 4 KB 对齐的库；我们是 `System.load(路径)`，
 *      同样受约束（跟"装不装这个 APK"无关）。实测危险窗口恰是 arc 早期 `v105~v146`。
 *
 * 一项**软**判据（不拦，只决定隔离方式）：
 *   ⑤ dex 里有没有 `mindustry.data.dir` —— 没有 ⇒ {@link Probe#needsRename()} = true，
 *      该版本用不了 F0 的属性注入，只能走既有的**改名交换**兜底
 *      （`Data.prepareSlot(..., legacy=true)`，`≤146` 整段如此 —— 146 无 / 147 有，已收口）。
 *
 * ⚠️ 探测**只读**：全程不写盘、不改包，可以放心在导入前与启动前各跑一次（成本 ~10 ms 级）。
 * ⚠️ 保守取向：读不到/解析不出（`maxAlign == 0`）时**不拦** —— 宁可漏拦交给 `System.load` 报错，
 *    也不误伤一个其实能跑的包。
 */
public final class Compat {
    private Compat() {}

    /** dex 里的类描述符形式（`Injector.GAME_ACTIVITY` 是点号形式，这里推导出来，**只有一处来源**） */
    public static final String ENTRY_DESC = Injector.GAME_ACTIVITY.replace('.', '/');

    /** 属性注入入口（F0）—— 软判据，见类注释 ⑤ */
    public static final String PROP_DATA_DIR = "mindustry.data.dir";

    /** ELF 头 + program header 只出现在文件最前面，读这么多足够（省内存、快） */
    private static final int ELF_PROBE_BYTES = 128 * 1024;

    /** 单个 dex 的读取上限（防畸形包把内存吃光；正常 dex 6~8 MB） */
    private static final int DEX_MAX_BYTES = 96 * 1024 * 1024;

    // ── 结果 ──────────────────────────────────────────────────────────────

    public static final class Probe {
        /** ① 入口类在 dex 里 */
        public boolean hasEntry;
        /** ② 命中的 native 库名（null = 一个都没有） */
        public String coreLib;
        /** ③ 与设备匹配的 ABI（null = 无交集） */
        public String abi;
        /** ④ `PT_LOAD` 最大对齐（0 = 读不到 / 非 ELF ⇒ 不拦） */
        public long maxAlign;
        /** 设备页大小（0 = 拿不到 ⇒ 不判） */
        public long pageSize;
        /** ⑤ 支持属性注入 */
        public boolean hasProp;
        /** 取证用：包里提供了哪些 ABI */
        public String[] apkAbis = new String[0];
        /** 取证用：包里有哪些 native 库 */
        public String[] apkLibs = new String[0];
        /** 取证用：读了几个 dex */
        public int dexCount;

        /** 能不能进管线（① ② ③ ④ 全过） */
        public boolean runnable() {
            return hasEntry && abi != null && coreLib != null && alignOk();
        }

        /**
         * 页对齐是否满足设备要求。
         * ⚠️ `maxAlign == 0`（非 ELF / 读不到）与 `pageSize == 0`（拿不到页大小）都**放行** ——
         *    保守取向，见类注释。
         */
        public boolean alignOk() {
            if (maxAlign == 0 || pageSize == 0) return true;
            return maxAlign >= pageSize;
        }

        /** ★ 属性注入不可用 ⇒ 该版本必须回退到**改名交换**（F0 落地后据此分流） */
        public boolean needsRename() {
            return !hasProp;
        }

        /** 一行摘要（日志 / 探针报告用） */
        public String brief() {
            return "entry=" + (hasEntry ? "Y" : "N")
                    + " lib=" + (coreLib == null ? "-" : coreLib)
                    + " abi=" + (abi == null ? "-" : abi)
                    + " align=" + (maxAlign == 0 ? "?" : (maxAlign / 1024) + "K")
                    + "/page=" + (pageSize == 0 ? "?" : (pageSize / 1024) + "K")
                    + " prop=" + (hasProp ? "Y" : "N")
                    + " → " + (runnable() ? "可用" : "拒绝")
                    + (needsRename() ? "（回退改名交换）" : "");
        }
    }

    // ── 入口 ──────────────────────────────────────────────────────────────

    /** 探测一个 APK 文件（自带开关 ZipFile）。IO 失败抛 IOException，调用方按"不拦"处理。 */
    public static Probe probe(File apk) throws IOException {
        ZipFile zf = new ZipFile(apk);
        try {
            return probe(zf);
        } finally {
            try { zf.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * 探测一个**已经打开的** ZipFile —— 导入路径复用它，避免把几 MB 的包再打开一次。
     * 本方法不关闭 zf（所有权归调用方）。
     */
    public static Probe probe(ZipFile zf) {
        Probe p = new Probe();
        p.pageSize = pageSize();

        TreeSet<String> abis = new TreeSet<String>();
        TreeSet<String> libs = new TreeSet<String>();
        List<String> dexs = new ArrayList<String>();

        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (n.startsWith("lib/")) {
                int s = n.indexOf('/', 4);
                if (s > 4 && n.indexOf('/', s + 1) < 0) {   // 只认 lib/<abi>/<file> 这一层
                    abis.add(n.substring(4, s));
                    libs.add(n.substring(s + 1));
                }
            } else if (n.endsWith(".dex") && n.indexOf('/') < 0) {   // classes.dex / classes2.dex…
                dexs.add(n);
            }
        }
        p.apkAbis = abis.toArray(new String[0]);
        p.apkLibs = libs.toArray(new String[0]);
        p.dexCount = dexs.size();

        // ③ ABI：按设备优先级取第一个有交集的（与 Injector.loadNatives 同一实现）
        p.abi = pickAbiFrom(abis);

        // ② native：NATIVE_LIBS 里命中任意一个即算核心库可用（两者缺一都还能跑：v127+ 没有 freetype 的变体不拦）
        for (String lib : Injector.NATIVE_LIBS) {
            if (libs.contains(lib)) { p.coreLib = lib; break; }
        }

        // ④ 页对齐：读匹配 ABI 下那个核心库的 ELF program header
        if (p.abi != null && p.coreLib != null) {
            ZipEntry so = zf.getEntry("lib/" + p.abi + "/" + p.coreLib);
            if (so != null) p.maxAlign = elfMaxAlign(readUpTo(zf, so, ELF_PROBE_BYTES));
        }

        // ①⑤ 扫 dex：两个字符串都只是"有没有"，命中即可提前收工
        byte[] neEntry = ENTRY_DESC.getBytes();
        byte[] neProp = PROP_DATA_DIR.getBytes();
        for (String dn : dexs) {
            byte[] d = readDex(zf, dn);
            if (d == null) continue;
            if (!p.hasEntry && contains(d, neEntry)) p.hasEntry = true;
            if (!p.hasProp && contains(d, neProp)) p.hasProp = true;
            if (p.hasEntry && p.hasProp) break;
        }
        return p;
    }

    /** 拒绝时给用户看的原因（不拦则返回 null）。文案在 strings.xml。 */
    public static String rejectReason(Context ctx, Probe p) {
        if (!p.hasEntry) {
            return Trans.get(ctx, R.string.compat_reject_entry);
        }
        if (p.abi == null) {
            return Trans.get(ctx, R.string.compat_reject_abi,
                    join(Build.SUPPORTED_ABIS), join(p.apkAbis));
        }
        if (p.coreLib == null) {
            return Trans.get(ctx, R.string.compat_reject_gdx);
        }
        return Trans.get(ctx, R.string.compat_reject_align,
                p.maxAlign / 1024, p.pageSize / 1024);
    }

    /** 探针报告（写 report-devtool.txt，**不进 UI**） */
    public static String describe(Probe p) {
        StringBuilder sb = new StringBuilder();
        sb.append("  1 入口类 ").append(ENTRY_DESC).append(" : ").append(p.hasEntry ? "有" : "无").append('\n');
        sb.append("  2 native 库  : ").append(p.coreLib == null ? "无（缺 " + join(Injector.NATIVE_LIBS) + "）" : p.coreLib).append('\n');
        sb.append("  3 匹配 ABI   : ").append(p.abi == null ? "无交集" : p.abi).append('\n');
        sb.append("    设备 ABI   : ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("    包内 ABI   : ").append(join(p.apkAbis)).append('\n');
        sb.append("    包内 so    : ").append(join(p.apkLibs)).append('\n');
        sb.append("  4 页对齐     : ").append(p.maxAlign == 0 ? "?" : (p.maxAlign / 1024) + " KB")
                .append("  /  设备页 ").append(p.pageSize == 0 ? "?" : (p.pageSize / 1024) + " KB")
                .append("  →  ").append(p.alignOk() ? "合格" : "不合格").append('\n');
        sb.append("  5 属性注入   : ").append(p.hasProp ? "支持（F0 可用）" : "不支持 ⇒ 回退改名交换")
                .append('\n');
        sb.append("    dex 数     : ").append(p.dexCount).append('\n');
        sb.append("  结论         : ").append(p.runnable() ? "可以进加载管线" : "拒绝进加载管线").append('\n');
        sb.append("  一行摘要     : ").append(p.brief()).append('\n');
        return sb.toString();
    }

    // ── ABI 选择（单一实现：探测与 Injector.loadNatives 共用）─────────────────

    /**
     * 在包里挑一个本机能用的 ABI（按 {@link Build#SUPPORTED_ABIS} 的优先级）；都没有返回 null。
     * ★ 这是 ABI 判据的**唯一实现** —— {@link #probe} 与 {@link Injector#loadNatives} 都走它，
     *   否则"探测说能跑、加载就失败"。
     */
    public static String pickAbi(ZipFile zf) {
        TreeSet<String> abis = new TreeSet<String>();
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            String n = en.nextElement().getName();
            if (!n.startsWith("lib/")) continue;
            int s = n.indexOf('/', 4);
            if (s > 4 && n.indexOf('/', s + 1) < 0) abis.add(n.substring(4, s));
        }
        return pickAbiFrom(abis);
    }

    /** 从已收集的 ABI 集合里按设备优先级挑一个 */
    static String pickAbiFrom(TreeSet<String> abis) {
        for (String a : Build.SUPPORTED_ABIS) {
            if (abis.contains(a)) return a;
        }
        return null;
    }

    // ── 设备页大小 ─────────────────────────────────────────────────────────

    /** 设备页大小（字节）；拿不到返回 0（= 不判页对齐）。 */
    public static long pageSize() {
        try {
            return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── ELF：PT_LOAD 的 p_align ───────────────────────────────────────────

    /**
     * 从 ELF 前若干字节里读 `PT_LOAD` 段 `p_align` 的**最大值**。
     * 非 ELF / 大端 / 太短 / 解析不出来 ⇒ 0（调用方按"不拦"处理）。
     *
     * 偏移口径（32/64 位不同，最容易写错的地方）：
     *   ELF header：e_phoff @0x1C(32) / @0x20(64)，e_phentsize @0x2A(32) / @0x36(64)，
     *               e_phnum @0x2C(32) / @0x38(64)
     *   program header：p_type @0(+0)，p_align @0x1C(32) / @0x30(64)
     *   PT_LOAD == 1
     */
    public static long elfMaxAlign(byte[] d) {
        if (d == null || d.length < 64) return 0;
        if (d[0] != 0x7f || d[1] != 'E' || d[2] != 'L' || d[3] != 'F') return 0;
        boolean is64 = d[4] == 2;
        if (d[5] != 1) return 0;                       // 只认小端（Android 上不存在大端）

        long phoff, phentsize;
        int phnum;
        if (is64) {
            phoff = u64(d, 0x20);
            phentsize = u16(d, 0x36);
            phnum = (int) u16(d, 0x38);
        } else {
            phoff = u32(d, 0x1C);
            phentsize = u16(d, 0x2A);
            phnum = (int) u16(d, 0x2C);
        }
        if (phentsize < 8 || phnum <= 0) return 0;

        long max = 0;
        for (int i = 0; i < phnum; i++) {
            long p = phoff + (long) i * phentsize;
            // 需要的最大字段偏移：64 位下 p_align 在 +0x30，还要 8 字节
            long need = is64 ? 0x38 : 0x20;
            if (p < 0 || p + need > d.length) break;
            int pi = (int) p;
            if (u32(d, pi) != 1) continue;             // 只要 PT_LOAD
            long al = is64 ? u64(d, pi + 0x30) : u32(d, pi + 0x1C);
            if (al > max) max = al;
        }
        return max;
    }

    private static long u16(byte[] d, int o) {
        return (d[o] & 0xffL) | ((d[o + 1] & 0xffL) << 8);
    }

    private static long u32(byte[] d, int o) {
        return (d[o] & 0xffL) | ((d[o + 1] & 0xffL) << 8)
                | ((d[o + 2] & 0xffL) << 16) | ((d[o + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] d, int o) {
        return u32(d, o) | (u32(d, o + 4) << 32);
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /**
     * 朴素字节搜索（dex 里的类名 / 字符串常量都是 MUTF-8，直接按字节找即可）。
     *
     * ★ 为什么"朴素搜索"够用 —— 两个方向的误差**不对称**：
     *   · **假阴性不可能**：类真在 dex 里定义，它的描述符必然出现在 string/type 表里；
     *     dex 若被加密/加固到搜不出，那本来也加载不了 ⇒ 判"拒绝"是对的。
     *   · **假阳性无害**：只要某个**别的**字符串恰好包含这段文本（例如某个包里
     *     写着 `"...mindustry/android/AndroidLauncher 未找到"`），只会让 ① 变成 true
     *     —— 而"能进管线"要**四项全过**，② ③ ④ 仍然拦得住。最坏结果 = 退回加这道
     *     判据之前的行为（启动时才失败），不会误伤能跑的包。
     *   ⚠️ 但写自检/自测时得知道这条：**把答案字面量写进被试**（比如在自家代码里
     *     写一份 `"mindustry/android/AndroidLauncher"`）就会搜到自己 ⇒ 见 SelfTest ⑦①。
     */
    static boolean contains(byte[] hay, byte[] needle) {
        if (hay == null || needle == null || needle.length == 0) return false;
        int last = hay.length - needle.length;
        if (last < 0) return false;
        outer:
        for (int i = 0; i <= last; i++) {
            if (hay[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static String join(String[] a) {
        if (a == null || a.length == 0) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (String s : a) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** 读 zip 里某个条目的前 limit 字节（ELF 头用） */
    private static byte[] readUpTo(ZipFile zf, ZipEntry e, int limit) {
        InputStream in = null;
        try {
            in = zf.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.min(limit, 1 << 16));
            byte[] buf = new byte[Math.min(limit, 16384)];
            int total = 0;
            while (total < limit) {
                int n = in.read(buf, 0, Math.min(buf.length, limit - total));
                if (n <= 0) break;
                bo.write(buf, 0, n);
                total += n;
            }
            return bo.toByteArray();
        } catch (Exception ex) {
            return null;
        } finally {
            close(in);
        }
    }

    /** 全量读一个 dex（逐个处理，读完即弃 ⇒ 峰值只有一个 dex） */
    private static byte[] readDex(ZipFile zf, String name) {
        InputStream in = null;
        try {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            long sz = e.getSize();
            if (sz > DEX_MAX_BYTES) return null;
            in = zf.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(
                    (sz > 0 && sz < (1 << 24)) ? (int) sz : (1 << 16));
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > DEX_MAX_BYTES) return null;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (Exception ex) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
