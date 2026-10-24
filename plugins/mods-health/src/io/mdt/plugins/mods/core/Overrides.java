package io.mdt.plugins.mods.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 234 轮：把模组包（zip / jar）或目录里**覆盖原版**的三个通道读出来。
 *
 * <ol>
 *   <li>{@code sprites-override/**} —— 贴图覆盖（注册名**不加**模组前缀，见宿主 {@code ModSprites}）；</li>
 *   <li>{@code bundles/*.properties} —— 文案覆盖（模组语言包与版本 APK 的语言包进同一个 map）；</li>
 *   <li>{@code content/<类型>/**.json} **不带 `type`** —— **数据覆盖**（最常见的"改数值"）：
 *       游戏 {@code ContentParser} 的源码写得很直白（v159 的 {@code ContentParser.java}）：
 *       名字在已有内容里找得到、又没有 `type` ⇒ 拿到那个**原版对象**、把 JSON 里的字段写上去
 *       （有了 `type` 反而是"新内容"，游戏还会警告一句）。
 *       ⇒ 这就是模组"给原版传送带提速 / 给炮塔加血 / 改造价"的做法。</li>
 * </ol>
 *
 * <p>★ **只读条目名 / 键 / 顶层字段名**：不解压图片、不留值 ⇒ 22 MB 的包也只是读中央目录 + 几份
 * 小 JSON，几十毫秒。
 * ★ 读不出来 ⇒ **空表**（不是"没有覆盖"）—— 调用方不许据此说"没问题"。
 * ★ 纯 Java（不碰 Android / 宿主），PC 侧 {@code build.sh check} 可喂夹具验。
 */
public final class Overrides {

    /** 覆盖原版贴图的目录（相对模组根）。 */
    public static final String SPRITES = "sprites-override/";
    /** 覆盖原版文案的目录（相对模组根）。 */
    public static final String BUNDLES = "bundles/";
    /** 覆盖原版数据的目录（相对模组根）。 */
    public static final String CONTENT = "content/";

    /** 防御坏包：单个模组最多记这么多项。 */
    static final int MAX_SPRITES = 5000;
    static final int MAX_KEYS = 20000;
    static final int MAX_FILES = 64;
    static final int MAX_PATCHES = 2000;
    /** 单份 bundle 超过这个字节数就不读（真实的最多几百 KB）。 */
    static final long MAX_BUNDLE_BYTES = 4L << 20;
    /** content JSON 读这么多字节就够判"顶层有没有 type"（真实内容文件都远小于它）。 */
    static final int MAX_JSON_BYTES = 256 * 1024;

    /**
     * 内容类型 → 游戏认的文件夹名。
     *
     * <p>来源：{@code ContentType} 的 {@code folderName} + {@code Mods.loadContent} 里还兼容的
     * **老写法**（`type.name().toLowerCase()` 再补一个 `s`，所以 status 还认 `statuss`、
     * weather 还认 `weathers`）。两套都要认，否则老包的新补丁会被漏掉。
     */
    private static final String[][] FOLDERS = {
            {"block", "blocks"},
            {"item", "items"},
            {"liquid", "liquids"},
            {"unit", "units"},
            {"status", "statuses", "statuss", "status"},
            {"sector", "sectors"},
            {"planet", "planets"},
            {"weather", "weather", "weathers"},
            {"team", "teams"},
    };

    private static final Map<String, String> BY_FOLDER = new LinkedHashMap<String, String>();
    private static final Set<String> KNOWN_TYPES = new LinkedHashSet<String>();

    static {
        for (String[] row : FOLDERS) {
            KNOWN_TYPES.add(row[0]);
            for (int i = 1; i < row.length; i++) BY_FOLDER.put(row[i], row[0]);
        }
    }

    private Overrides() { }

    /**
     * 读一个模组的三个覆盖通道（就地填进 {@code m}）。
     *
     * <p>目录形态用 {@code rootDir}（模组真正的根；host 已把"下潜一层"算好），
     * 压缩包形态用 {@code path}。读不出来就什么都不加。
     */
    public static void read(Conflicts.Mod m) {
        if (m == null) return;
        try {
            if (m.directory) {
                String root = m.rootDir == null || m.rootDir.trim().isEmpty()
                        ? m.path : m.rootDir.trim();
                File dir = root == null ? null : new File(root);
                if (dir != null && dir.isDirectory()) readDir(dir, m);
            } else {
                File zip = m.path == null ? null : new File(m.path);
                if (zip != null && zip.isFile()) readZip(zip, m);
            }
        } catch (Throwable ignored) {
            // 读不出来就是没有（调用方只把非空当证据）
        }
    }

    private static void readZip(File zip, Conflicts.Mod m) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            // 有的包外面还套一层目录（`MyMod/mod.json`）；host 已经把这层算成 rootPrefix，
            // 三条通道都要先剥掉它，否则"包里明明有"却一条都读不到。
            String prefix = m.rootPrefix == null ? "" : m.rootPrefix;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e == null || e.isDirectory()) continue;
                String n = e.getName();
                if (n == null) continue;
                if (!prefix.isEmpty()) {
                    if (!n.startsWith(prefix)) continue;
                    n = n.substring(prefix.length());
                }
                if (n.startsWith(SPRITES)) {
                    if (m.overrideSprites.size() < MAX_SPRITES) {
                        m.overrideSprites.add(n.substring(SPRITES.length()));
                    }
                } else if (n.startsWith(BUNDLES) && n.endsWith(".properties")) {
                    String file = n.substring(BUNDLES.length());
                    if (file.indexOf('/') >= 0) continue;   // 与游戏一致：只认 bundles/ 下的平铺文件
                    if (m.bundleKeys.size() >= MAX_FILES) continue;
                    Set<String> keys = new LinkedHashSet<String>();
                    long size = e.getSize();
                    if (size < 0 || size <= MAX_BUNDLE_BYTES) {
                        InputStream in = null;
                        try {
                            in = zf.getInputStream(e);
                            readKeys(new InputStreamReader(in, "UTF-8"), keys);
                        } catch (Throwable ignored) {
                        } finally {
                            close(in);
                        }
                    }
                    m.bundleKeys.put(file, keys);
                } else if (n.startsWith(CONTENT) && isContentFile(n)) {
                    String rel = n.substring(CONTENT.length());
                    int slash = rel.indexOf('/');
                    if (slash <= 0) continue;
                    String type = BY_FOLDER.get(rel.substring(0, slash));
                    if (type == null) continue;
                    String name = baseName(rel);
                    if (name.isEmpty()) continue;
                    String text = null;
                    if (e.getSize() < 0 || e.getSize() <= MAX_JSON_BYTES) {
                        InputStream in = null;
                        try {
                            in = zf.getInputStream(e);
                            text = readText(in, MAX_JSON_BYTES);
                        } catch (Throwable ignored) {
                        } finally {
                            close(in);
                        }
                    }
                    if (text != null && !hasTopLevelType(text)) addPatch(m, type, name);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(zf);
        }
    }

    private static void readDir(File root, Conflicts.Mod m) {
        File sprites = new File(root, "sprites-override");
        if (sprites.isDirectory()) collect(sprites, "", m.overrideSprites);
        File bundles = new File(root, "bundles");
        File[] kids = bundles.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f == null || !f.isFile() || !f.getName().endsWith(".properties")) continue;
                if (m.bundleKeys.size() >= MAX_FILES) break;
                Set<String> keys = new LinkedHashSet<String>();
                Reader r = null;
                try {
                    r = new InputStreamReader(new FileInputStream(f), "UTF-8");
                    readKeys(r, keys);
                } catch (Throwable ignored) {
                } finally {
                    close(r);
                }
                m.bundleKeys.put(f.getName(), keys);
            }
        }
        File content = new File(root, "content");
        File[] types = content.listFiles();
        if (types == null) return;
        for (File folder : types) {
            if (folder == null || !folder.isDirectory()) continue;
            String type = BY_FOLDER.get(folder.getName());
            if (type == null) continue;
            collectContent(folder, type, m);
        }
    }

    /** 递归收 {@code content/<类型>/} 下的 JSON（游戏也是递归找的，见 `folder.findAll`）。 */
    private static void collectContent(File dir, String type, Conflicts.Mod m) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collectContent(f, type, m);
                continue;
            }
            String n = f.getName();
            if (!isContentFile(n)) continue;
            String name = n.substring(0, n.lastIndexOf('.'));
            if (name.isEmpty()) continue;
            String text = null;
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                text = readText(in, MAX_JSON_BYTES);
            } catch (Throwable ignored) {
            } finally {
                close(in);
            }
            if (text != null && !hasTopLevelType(text)) addPatch(m, type, name);
        }
    }

    private static void addPatch(Conflicts.Mod m, String type, String name) {
        Set<String> set = m.contentPatches.get(type);
        if (set == null) {
            set = new LinkedHashSet<String>();
            m.contentPatches.put(type, set);
        }
        if (set.size() < MAX_PATCHES) set.add(name);
    }

    /** 递归收 {@code sprites-override/} 下的文件；键 = 相对该目录的路径（`/` 分隔）。 */
    private static void collect(File dir, String prefix, Set<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f == null) continue;
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + "/", out);
            } else if (out.size() < MAX_SPRITES) {
                out.add(prefix + f.getName());
            }
        }
    }

    private static boolean isContentFile(String name) {
        return name.endsWith(".json") || name.endsWith(".hjson");
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        String file = slash < 0 ? path : path.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /**
     * **顶层**有没有 `type` 字段 —— 有 ⇒ 这是"新内容"，不是补丁（游戏自己也这么判：
     * {@code ContentParser} 里 `value.has("type")`，而且会警告一句"重新声明了 type"）。
     *
     * <p>⚠️ 不能用"全文里出现 `type:`"代替：补丁的 `research.objectives`、`consumables.effect`
     * 这些**嵌套对象**里也常有 `type`，全文匹配会把真正的补丁当成新内容漏掉。
     * ⇒ 这里扫一遍 HJSON/JSON 的**顶层键**（认字符串、两种行注释、块注释、`#` 注释、单双引号）。
     */
    static boolean hasTopLevelType(String text) {
        if (text == null) return false;
        int n = text.length();
        int i = skipSpace(text, 0);
        if (i >= n || text.charAt(i) != '{') return false;
        i++;
        int depth = 1;
        boolean expectKey = true;
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\uFEFF' || Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                i = lineEnd(text, i + 2);
                continue;
            }
            if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i = blockEnd(text, i + 2);
                continue;
            }
            if (ch == '#') {
                i = lineEnd(text, i + 1);
                continue;
            }
            if (ch == '"' || ch == '\'') {
                int end = stringEnd(text, i, ch);
                if (depth == 1 && expectKey) {
                    String key = text.substring(i + 1, Math.max(i + 1, end - 1));
                    if ("type".equals(key.trim())) return true;
                    expectKey = false;
                }
                i = end;
                continue;
            }
            if (depth == 1 && ch == '[') {
                i = arrayEnd(text, i + 1);
                expectKey = false;
                continue;
            }
            if (ch == '{') {
                depth++;
                i++;
                continue;
            }
            if (ch == '}') {
                depth--;
                i++;
                if (depth == 0) return false;
                expectKey = false;
                continue;
            }
            if (depth == 1 && expectKey && ch != ',' && ch != ':') {
                int start = i;
                while (i < n) {
                    char c = text.charAt(i);
                    if (c == ':' || c == '{' || c == '}' || c == ',' || Character.isWhitespace(c)) break;
                    i++;
                }
                String key = text.substring(start, i).trim();
                if ("type".equals(key)) return true;
                expectKey = false;
                continue;
            }
            if (depth == 1 && ch == ',') expectKey = true;
            i++;
        }
        return false;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '\uFEFF')) i++;
        return i;
    }

    private static int lineEnd(String s, int i) {
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
        return i;
    }

    private static int blockEnd(String s, int i) {
        while (i + 1 < s.length()) {
            if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') return i + 2;
            i++;
        }
        return s.length();
    }

    /** 跳过从引号 {@code i} 开始的一整个字符串，返回结尾之后的位置。 */
    private static int stringEnd(String s, int i, char quote) {
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) return j + 1;
            j++;
        }
        return s.length();
    }

    /** 跳过从 {@code [} 之后开始的数组（字符串 / 注释都认），返回 {@code ]} 之后的位置。 */
    private static int arrayEnd(String s, int i) {
        int depth = 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = stringEnd(s, i, ch);
                continue;
            }
            if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                i = lineEnd(s, i + 2);
                continue;
            }
            if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i = blockEnd(s, i + 2);
                continue;
            }
            if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return s.length();
    }

    /**
     * 原版内容名（v28 的 bundle 键推出来）：`block.mass-driver.name` → block / mass-driver。
     *
     * <p>★ 只用**基础语言包**的键，所以这些名字保证是原版真的有的（模组内容的名字带前缀，
     * 不会出现在这里）。中文名 / 英文名都行 —— 键是同一套。
     *
     * @return 类型 → 名字集（读不到原版键集 ⇒ 空表 ⇒ 内容覆盖这条不报）
     */
    public static Map<String, Set<String>> vanillaNames(Set<String> keys) {
        Map<String, Set<String>> out = new LinkedHashMap<String, Set<String>>();
        if (keys == null) return out;
        for (String k : keys) {
            if (k == null || !k.endsWith(".name")) continue;
            int dot = k.indexOf('.');
            if (dot <= 0) continue;
            String type = k.substring(0, dot);
            if (!KNOWN_TYPES.contains(type)) continue;
            int last = k.lastIndexOf('.');
            if (last <= dot + 1) continue;
            String name = k.substring(dot + 1, last);
            if (name.isEmpty() || name.indexOf('.') >= 0) continue;
            Set<String> set = out.get(type);
            if (set == null) {
                set = new LinkedHashSet<String>();
                out.put(type, set);
            }
            set.add(name);
        }
        return out;
    }

    /**
     * 两个模组在同一个通道上撞了哪些 —— **各自排好序**，方便文案取前三个。
     *
     * <p>★ 文案只认 {@code vanillaKeys} 里的键；内容只认 {@code vanillaNames} 里的名字
     * （读不到版本包 ⇒ 这两条都不报，贴图不受影响）。
     */
    public static void merge(Conflicts.Mod a, Conflicts.Mod b, Set<String> vanillaKeys,
                             Map<String, Set<String>> vanillaNames,
                             List<String> sprites, List<String> keys, List<String> contents) {
        if (a == null || b == null) return;
        Set<String> as = a.overrideSprites, bs = b.overrideSprites;
        Set<String> small = as.size() <= bs.size() ? as : bs;
        Set<String> big = small == as ? bs : as;
        for (String s : small) {
            if (big.contains(s) && !sprites.contains(s)) sprites.add(s);
        }
        java.util.Collections.sort(sprites);

        if (vanillaKeys != null && !vanillaKeys.isEmpty()) {
            for (Map.Entry<String, Set<String>> e : a.bundleKeys.entrySet()) {
                Set<String> theirs = b.bundleKeys.get(e.getKey());
                if (theirs == null) continue;
                for (String k : e.getValue()) {
                    if (theirs.contains(k) && vanillaKeys.contains(k) && !keys.contains(k)) {
                        keys.add(k);
                    }
                }
            }
            java.util.Collections.sort(keys);
        }

        if (vanillaNames != null && !vanillaNames.isEmpty()) {
            for (Map.Entry<String, Set<String>> e : a.contentPatches.entrySet()) {
                Set<String> van = vanillaNames.get(e.getKey());
                Set<String> theirs = b.contentPatches.get(e.getKey());
                if (van == null || van.isEmpty() || theirs == null) continue;
                for (String name : e.getValue()) {
                    String hit = e.getKey() + ":" + name;
                    if (theirs.contains(name) && van.contains(name) && !contents.contains(hit)) {
                        contents.add(hit);
                    }
                }
            }
            java.util.Collections.sort(contents);
        }
    }

    /**
     * 读 `.properties` 的**键**（够判"两份是不是同一个键"就行，值一个字都不留）。
     *
     * <p>分隔符认第一个 `=` 或 `:`（Mindustry 全用 `=`；`:` 是 Java 允许的另一种写法）。
     */
    private static void readKeys(Reader r, Set<String> out) throws IOException {
        BufferedReader br = new BufferedReader(r);
        String line;
        while ((line = br.readLine()) != null) {
            if (out.size() >= MAX_KEYS) break;
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!') continue;
            int eq = s.indexOf('=');
            int colon = s.indexOf(':');
            int cut = eq < 0 ? colon : (colon < 0 ? eq : Math.min(eq, colon));
            String k = cut > 0 ? s.substring(0, cut).trim() : s;
            if (!k.isEmpty()) out.add(k);
        }
    }

    private static String readText(InputStream in, int cap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
            byte[] buf = new byte[8192];
            int total = 0, n;
            while (total < cap && (n = in.read(buf, 0, Math.min(buf.length, cap - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 关掉任何 Closeable，吞掉异常（读覆盖通道是"尽力而为"）。 */
    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
