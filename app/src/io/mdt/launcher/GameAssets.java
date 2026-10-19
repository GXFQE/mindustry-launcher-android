package io.mdt.launcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 一个游戏版本 APK 里 {@code assets/} 的**只读清单**（第 171 轮）。
 *
 * <p>它只做三件事，都是纯数据：
 * <ol>
 *   <li>打开 APK 的中央目录，把 {@code assets/**} 逐个记下来（名字 / 解压后大小）；</li>
 *   <li>按资源类型分组（贴图 / 音效 / 音乐 / 地图 / 文本 / 字体 / ...）；</li>
 *   <li>给导出层一份「勾了哪几类 ⇒ 要写哪几个条目」的清单。</li>
 * </ol>
 *
 * <p>★ 这是**读包**，不是启动游戏：不碰 {@code :game} 进程、不碰 {@code Injector}、
 * 不需要 root，也不需要 {@code addAssetPath}。现有地图 / 图集 / 配色表的读取已经证明
 * 同一个载体（版本 APK 的 zip）在启动器进程里够得着。
 *
 * <p>★ 只收 {@code assets/}：{@code classes.dex}、{@code lib/*.so}、{@code res/}
 * 与 {@code resources.arsc} 是代码 / 编译后的资源，不在“游戏资源”这一层；
 * 应用图标那种 png 在 {@code assets/icons/} 里有现成的一份。
 *
 * <p>⚠️ 分类是**按 APK 里真实存在的目录**算的（见 {@link #groupOf}）—— 不拿版本号
 * 猜，也不把「v160 有 114 张图」这类数字烘进来；换版本 / 换 fork 仍然能用。
 * 新出现的、没归类的目录一律进 {@link #GROUP_OTHER}，不会静默丢。
 */
public final class GameAssets {

    public static final String GROUP_SPRITES = "sprites";
    public static final String GROUP_SOUNDS = "sounds";
    public static final String GROUP_MUSIC = "music";
    public static final String GROUP_MAPS = "maps";
    public static final String GROUP_BUNDLES = "bundles";
    public static final String GROUP_FONTS = "fonts";
    public static final String GROUP_ICONS = "icons";
    public static final String GROUP_CUBEMAPS = "cubemaps";
    public static final String GROUP_BASEPARTS = "baseparts";
    public static final String GROUP_SHADERS = "shaders";
    public static final String GROUP_SCRIPTS = "scripts";
    public static final String GROUP_OTHER = "other";

    /** UI 里的固定顺序（贴图在最上面，音频分开；别按大小排 —— 用户找的是类型）。 */
    private static final String[] GROUP_ORDER = {
            GROUP_SPRITES, GROUP_SOUNDS, GROUP_MUSIC, GROUP_MAPS, GROUP_BUNDLES,
            GROUP_FONTS, GROUP_ICONS, GROUP_CUBEMAPS, GROUP_BASEPARTS,
            GROUP_SHADERS, GROUP_SCRIPTS, GROUP_OTHER
    };

    /** APK 里的一条资源（名字是 zip 条目名，含 `assets/` 前缀）。 */
    public static final class Entry {
        public final String name;
        public final long bytes;
        public final long packedBytes;

        Entry(String name, long bytes, long packedBytes) {
            this.name = name;
            this.bytes = bytes;
            this.packedBytes = packedBytes;
        }
    }

    /** 一类资源。 */
    public static final class Group {
        public final String key;
        public final List<Entry> entries;
        public final long bytes;

        Group(String key, List<Entry> entries, long bytes) {
            this.key = key;
            this.entries = Collections.unmodifiableList(entries);
            this.bytes = bytes;
        }

        public int count() {
            return entries.size();
        }
    }

    /** 一次扫描的结果。 */
    public static final class Catalog {
        public final File apk;
        public final List<Group> groups;
        public final int entries;
        public final long bytes;

        Catalog(File apk, List<Group> groups, int entries, long bytes) {
            this.apk = apk;
            this.groups = Collections.unmodifiableList(groups);
            this.entries = entries;
            this.bytes = bytes;
        }

        public Group group(String key) {
            if (key == null) return null;
            for (Group g : groups) {
                if (key.equals(g.key)) return g;
            }
            return null;
        }

        /**
         * 勾选的类型 → 要导出的条目（按 {@link #GROUP_ORDER} 的顺序，不按勾选顺序）。
         * 空 / null = 空表（调用方据此禁用按钮）。
         */
        public List<Entry> selected(List<String> keys) {
            List<Entry> out = new ArrayList<Entry>();
            if (keys == null || keys.isEmpty()) return out;
            Set<String> want = new LinkedHashSet<String>(keys);
            for (Group g : groups) {
                if (want.contains(g.key)) out.addAll(g.entries);
            }
            return out;
        }
    }

    private GameAssets() {}

    /**
     * 扫描一个 APK 的 {@code assets/}。
     *
     * @throws IOException 包不存在 / 不是 zip / 打不开 —— 调用方把它变成「读不了这个包」，
     *                     **不猜**、也不拿别的包顶替。
     */
    public static Catalog scan(File apk) throws IOException {
        if (apk == null || !apk.isFile()) throw new IOException("apk not found");
        ZipFile zf = new ZipFile(apk);
        try {
            // 同名的重复条目只留第一条：ZipFile.getEntry 本来就只认一条，
            // 这里若把重名都列出来，导出 zip 会在 putNextEntry 时炸（且不容易看出为什么）。
            Map<String, Entry> byName = new LinkedHashMap<String, Entry>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze == null || ze.isDirectory()) continue;
                String n = ze.getName();
                if (n == null || !n.startsWith("assets/")) continue;
                if (byName.containsKey(n)) continue;
                long size = ze.getSize();
                if (size < 0) size = 0;
                long packed = ze.getCompressedSize();
                if (packed < 0) packed = 0;
                byName.put(n, new Entry(n, size, packed));
            }
            List<Entry> all = new ArrayList<Entry>(byName.values());
            Collections.sort(all, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    return a.name.compareTo(b.name);
                }
            });
            List<Group> groups = catalog(all);
            long bytes = 0;
            for (Entry e : all) bytes += e.bytes;
            return new Catalog(apk, groups, all.size(), bytes);
        } finally {
            try {
                zf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 纯函数：把条目分组成固定顺序的类。给自检直接喂合成条目用，不依赖真实 APK。
     */
    static List<Group> catalog(List<Entry> all) {
        Map<String, List<Entry>> by = new LinkedHashMap<String, List<Entry>>();
        for (String k : GROUP_ORDER) by.put(k, new ArrayList<Entry>());
        if (all != null) {
            for (Entry e : all) {
                if (e == null || e.name == null) continue;
                String k = groupOf(e.name);
                List<Entry> list = by.get(k);
                if (list == null) list = by.get(GROUP_OTHER);
                list.add(e);
            }
        }
        List<Group> out = new ArrayList<Group>();
        for (String k : GROUP_ORDER) {
            List<Entry> list = by.get(k);
            if (list == null || list.isEmpty()) continue;
            Collections.sort(list, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    return a.name.compareTo(b.name);
                }
            });
            long bytes = 0;
            for (Entry e : list) bytes += e.bytes;
            out.add(new Group(k, list, bytes));
        }
        return out;
    }

    /**
     * 条目 → 类型。**唯一实现**：扫描分组与自检都走这里。
     *
     * <p>只看 `assets/` 下的一级目录；`assets/sprites-override/` 归贴图，
     * `assets/bloomshaders/` 归着色器，`assets/icon.png` 归图标 —— 这三条是 v146~v160
     * 实测存在的特殊落点，不是版本号猜的。
     */
    static String groupOf(String name) {
        if (name == null || !name.startsWith("assets/")) return GROUP_OTHER;
        if (name.startsWith("assets/sprites/") || name.startsWith("assets/sprites-override/")) {
            return GROUP_SPRITES;
        }
        if (name.startsWith("assets/sounds/")) return GROUP_SOUNDS;
        if (name.startsWith("assets/music/")) return GROUP_MUSIC;
        if (name.startsWith("assets/maps/")) return GROUP_MAPS;
        if (name.startsWith("assets/bundles/")) return GROUP_BUNDLES;
        if (name.startsWith("assets/fonts/")) return GROUP_FONTS;
        if (name.startsWith("assets/icons/") || "assets/icon.png".equals(name)) return GROUP_ICONS;
        if (name.startsWith("assets/cubemaps/")) return GROUP_CUBEMAPS;
        if (name.startsWith("assets/baseparts/")) return GROUP_BASEPARTS;
        if (name.startsWith("assets/shaders/") || name.startsWith("assets/bloomshaders/")) {
            return GROUP_SHADERS;
        }
        if (name.startsWith("assets/scripts/")) return GROUP_SCRIPTS;
        return GROUP_OTHER;
    }

    /**
     * 导出的 zip 文件名。**纯函数**（自检直接钉）：
     * 只留 ASCII 字母数字与 `._-`，空值 / 全中文 ⇒ `game`；单类用类名，多类用 `assets`。
     */
    static String zipName(String version, List<String> keys) {
        String v = safePart(version);
        if (v.isEmpty()) v = "game";
        String kind = "assets";
        if (keys != null && keys.size() == 1) {
            String one = safePart(keys.get(0));
            if (!one.isEmpty()) kind = one;
        }
        return "Mindustry-" + v + "-" + kind + ".zip";
    }

    private static String safePart(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (ok) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
            if (sb.length() >= 48) break;
        }
        int a = 0, b = sb.length();
        while (a < b && sb.charAt(a) == '-') a++;
        while (b > a && sb.charAt(b - 1) == '-') b--;
        return sb.substring(a, b);
    }
}
