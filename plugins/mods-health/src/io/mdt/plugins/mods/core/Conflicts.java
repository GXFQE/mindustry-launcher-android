package io.mdt.plugins.mods.core;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组间冲突体检的**判据本体**（第 229 轮从宿主 {@code Mods.findConflicts} 搬出来）。
 *
 * <p>两层，从硬到软：
 * <ol>
 *   <li><b>内置检测（判决）</b>：把每个模组的主类名（{@code main} → {@code pkg/Main}）当**字节串**，
 *       在**别的模组**的 {@code classes.dex} 里找 ⇒ 命中就是"A 把 B 的代码打包进去了"；</li>
 *   <li><b>简介提及（线索）</b>：A 的简介里出现 B 的名字 —— 免费信号，可能只是配套说明。</li>
 * </ol>
 * 另外把**游戏日志里提到这些模组的行**摘出来（那才是"谁真的接管了"的判据）。
 *
 * <p>🔴 第 229 轮删掉了原来那层「共同全局钩子」：它靠一张**写死的类名表**（{@code mindustry/logic/LCanvas}
 * ↔「逻辑编辑器」）去猜"两个模组想接管同一个全局对象"。用户 2026-10-24 的判断是「硬编码还是半成品」——
 * 确实：引用一个类 ≠ 接管它，改了全局对象也不一定打架。**宁可少说**：这一层不做了。
 *
 * <p>★ 本类**纯 Java**：不碰 Android、不碰宿主的任何类 ⇒ 插件工程能独立编译、
 * PC 侧自检（{@code tools/Check.java}）能直接喂夹具验它。
 * ★ 扫描是**流式**的（64 KB 窗口 + 上一块尾巴）：dex 可能十几 MB，别整个读进内存。
 */
public final class Conflicts {

    /** 一个模组（只放判据要用的字段；由插件入口从 {@code ItemInfo} 映射过来）。 */
    public static final class Mod {
        /** 包/目录的绝对路径。 */
        public String path = "";
        /** 显示名（列表上那一行）。 */
        public String title = "";
        /** {@code mod.json} 里的原始 name（可能与显示名不同）。 */
        public String name = "";
        /** 游戏用的内部名（小写、去空格）。 */
        public String internalName = "";
        public String description = "";
        /** {@code meta.main}（点号形态）；空 = 不是 Java 模组。 */
        public String main = "";
        /** zip 形态的根前缀（{@code ""} 或 {@code "X/"}）。 */
        public String rootPrefix = "";
        /** 目录形态的根（下潜一层之后那一层）；空 = 就是 {@link #path}。 */
        public String rootDir = "";
        public boolean directory;
        /** 读到的 classes.dex 字节数（-1 = 没读到）。 */
        public long dexBytes = -1;
    }

    /** 两个模组下标（{@code a} 是"谁在说"：容器 / 提到别人的那个）。 */
    public static final class Pair {
        public final int a, b;

        public Pair(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    /** 两层判据的结果（纯数据；文案由调用方拼）。 */
    public static final class Result {
        /** A 的 dex 里含 B 的主类。 */
        public final List<Pair> contained = new ArrayList<Pair>();
        /** A 的简介里提到 B。 */
        public final List<Pair> mentioned = new ArrayList<Pair>();
        /** 读不出来的 dex（模组下标）。 */
        public final List<Integer> dexErrors = new ArrayList<Integer>();
        /** 带 {@code main} 的模组数。 */
        public int javaMods;
        /** 真的扫了几个 dex。 */
        public int scannedDex;
        /** 扫过的 dex 字节数。 */
        public long scannedBytes;
    }

    /**
     * 游戏日志里提到这些模组的行。
     *
     * <p>★ 与 {@link #scan} **分开**：两层判据要打开每个 dex（几百毫秒），缓存得住；
     * 日志每次启动游戏都会变（读一遍文本文件很便宜），不该让一份会变的输入把整份缓存作废。
     */
    public static final class Log {
        /** 命中的行（去空白、上限 40 行）。 */
        public final List<String> lines = new ArrayList<String>();
        /** 文件在、但读不出来。 */
        public boolean unreadable;
    }

    private Conflicts() { }

    /**
     * 跑一遍两层判据（不读日志 —— 那份输入会变，见 {@link Log}）。
     *
     * @param mods 这个槽的模组（顺序与界面一致）
     */
    public static Result scan(List<Mod> mods) {
        Result c = new Result();
        if (mods == null) mods = new ArrayList<Mod>();

        // ① 主类模式：meta.main → dex 里的描述符形态（pkg/Main）
        List<Integer> javaIdx = new ArrayList<Integer>();
        for (int i = 0; i < mods.size(); i++) {
            Mod m = mods.get(i);
            if (m == null || m.main == null || m.main.trim().isEmpty()) continue;
            javaIdx.add(Integer.valueOf(i));
        }
        c.javaMods = javaIdx.size();

        for (int p = 0; p < javaIdx.size(); p++) {
            Mod m = mods.get(javaIdx.get(p).intValue());
            List<String> pats = new ArrayList<String>();
            List<Integer> who = new ArrayList<Integer>();   // 与 pats 对齐：java 下标
            for (int q = 0; q < javaIdx.size(); q++) {
                if (q == p) continue;                        // 自己含自己的主类不算冲突
                Mod other = mods.get(javaIdx.get(q).intValue());
                pats.add(other.main.trim().replace('.', '/'));
                who.add(Integer.valueOf(q));
            }
            if (pats.isEmpty()) continue;                    // 只有一个 Java 模组 ⇒ 没得比
            Set<Integer> found;
            try {
                found = scanDex(m, pats);
            } catch (Throwable t) {
                c.dexErrors.add(javaIdx.get(p));
                continue;
            }
            c.scannedDex++;
            if (m.dexBytes > 0) c.scannedBytes += m.dexBytes;
            for (Integer idxObj : found) {
                int q = who.get(idxObj.intValue()).intValue();
                c.contained.add(new Pair(javaIdx.get(p).intValue(),
                        javaIdx.get(q).intValue()));
            }
        }

        // ② 简介提及（免费信号）
        for (int i = 0; i < mods.size(); i++) {
            Mod a = mods.get(i);
            if (a == null || a.description == null || a.description.trim().isEmpty()) continue;
            for (int j = 0; j < mods.size(); j++) {
                if (i == j) continue;
                Mod b = mods.get(j);
                if (b == null) continue;
                String t = b.title == null ? "" : b.title.trim();
                String n = b.name == null ? "" : b.name.trim();
                // 与宿主同一条门槛：名字太短（<3）不算，否则到处误命中
                boolean hit = (t.length() >= 3 && containsIgnoreCase(a.description, t))
                        || (n.length() >= 3 && !n.equals(t) && containsIgnoreCase(a.description, n));
                if (hit) c.mentioned.add(new Pair(i, j));
            }
        }
        return c;
    }

    /**
     * 摘出游戏日志里提到这些模组的行。
     *
     * @param logFile 当前槽的 {@code last_log.txt}（可为 null；超过 4 MB 不读 —— 旧口径一样）
     */
    public static Log readLog(File logFile, List<Mod> mods) {
        Log c = new Log();
        if (logFile == null || !logFile.isFile() || mods == null) return c;
        if (logFile.length() > 4L * 1024 * 1024) return c;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), "UTF-8"), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                for (int i = 0; i < mods.size(); i++) {
                    Mod m = mods.get(i);
                    if (m == null) continue;
                    boolean hit = (m.title != null && m.title.length() >= 3 && containsIgnoreCase(t, m.title))
                            || (m.name != null && m.name.length() >= 3 && containsIgnoreCase(t, m.name))
                            || (m.internalName != null && m.internalName.length() >= 3
                                    && containsIgnoreCase(t, m.internalName));
                    if (hit) {
                        c.lines.add(t);
                        break;
                    }
                }
                if (c.lines.size() >= 40) break;
            }
        } catch (Throwable t) {
            c.unreadable = true;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return c;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return hay != null && needle != null
                && hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * 在一个模组的 {@code classes.dex} 里**流式**找若干 ASCII 模式（返回命中的模式下标）。
     *
     * <p>★ 逐字节比对（不做 UTF-16 转换）：dex 里的字符串是 MUTF-8，ASCII 部分与 UTF-8
     * 逐字节相同 —— 本判据的针一律是类名（ASCII）。
     */
    static Set<Integer> scanDex(Mod m, List<String> pats) throws IOException {
        InputStream in = null;
        try {
            if (m.directory) {
                String root = (m.rootDir == null || m.rootDir.isEmpty()) ? m.path : m.rootDir;
                File dex = new File(root, "classes.dex");
                if (!dex.isFile()) return new HashSet<Integer>();
                m.dexBytes = dex.length();
                in = new BufferedInputStream(new FileInputStream(dex), 1 << 16);
            } else {
                final ZipFile zf = new ZipFile(m.path);
                ZipEntry ze = zf.getEntry(m.rootPrefix + "classes.dex");
                if (ze == null) {
                    zf.close();
                    return new HashSet<Integer>();
                }
                m.dexBytes = ze.getSize();
                in = new BufferedInputStream(zf.getInputStream(ze), 1 << 16) {
                    @Override public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            zf.close();
                        }
                    }
                };
            }
            byte[][] needle = new byte[pats.size()][];
            int maxLen = 0;
            for (int i = 0; i < pats.size(); i++) {
                needle[i] = pats.get(i).getBytes("UTF-8");
                if (needle[i].length > maxLen) maxLen = needle[i].length;
            }
            Set<Integer> hit = new HashSet<Integer>();
            if (maxLen == 0) return hit;
            byte[] buf = new byte[1 << 16];
            // 窗口 = 上一块的尾巴 + 这一块（复用同一个数组，不每块新分配）
            byte[] win = new byte[(1 << 16) + maxLen - 1];
            int carryLen = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                System.arraycopy(buf, 0, win, carryLen, n);
                int len = carryLen + n;
                for (int i = 0; i < needle.length; i++) {
                    if (hit.contains(Integer.valueOf(i))) continue;
                    if (indexOf(win, len, needle[i]) >= 0) hit.add(Integer.valueOf(i));
                }
                carryLen = Math.min(maxLen - 1, len);
                System.arraycopy(win, len - carryLen, win, 0, carryLen);
            }
            return hit;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 在一个字节窗口的前 {@code len} 个字节里找一根针（首次命中即返回）。 */
    private static int indexOf(byte[] hay, int len, byte[] pat) {
        int last = len - pat.length;
        if (last < 0 || pat.length == 0) return -1;
        byte b0 = pat[0];
        outer:
        for (int i = 0; i <= last; i++) {
            if (hay[i] != b0) continue;
            for (int j = 1; j < pat.length; j++) {
                if (hay[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
