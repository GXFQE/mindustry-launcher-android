package io.mdt.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * **模组方块的贴图**（第 119 轮）：从本槽 `mods/` 里的包（`.zip` / `.jar` / 目录）按
 * **游戏自己的规则**找出某个方块该画哪张 PNG。
 *
 * <h3>规则（照抄游戏，`Mods.java:382~411`）</h3>
 * <pre>
 *   String baseName = file.nameWithoutExtension();            // 去掉 .png
 *   regionName     = baseName 里第一个 '.' 之前的部分           // `a.b.png` ⇒ `a`
 *   int hyphen     = baseName.indexOf('-');
 *   String fullName = (prefix &amp;&amp; !(hyphen != -1 &amp;&amp; baseName.substring(hyphen+1).startsWith(mod.name + "-")))
 *       ? mod.name + "-" + baseName : baseName;
 * </pre>
 * 人话：`sprites/` 底下**递归**所有 PNG，注册名 = `模组名-文件名主干`；
 * 唯一的例外是"文件名已经被类别前缀了"（注释里举的例子 `block-modname-content-full`）——
 * 那种**不加**模组前缀。
 * ★ 于是「方块内部名 → 贴图」就是一次**精确查表**：方块叫 `vne-bank-silicide`，
 *   就有 `sprites/.../bank-silicide.png`（目录层级随便套，注册名**不含目录**）。
 * ⚠️ `sprites-override/` 是**不加前缀**的（用来覆盖原版贴图）⇒ 单独一轮，排在图集之后。
 *
 * <h3>尺寸：不问 JSON，直接问 PNG</h3>
 * 游戏口径是 **32 px = 1 格**（贴图原始密度）⇒ 贴图自己的宽高就**编码了**方块的 size：
 * 64×64 就是 2 格、160×160 就是 5 格。所以本类只读 PNG 的 IHDR（头 24 字节）拿宽高，
 * **不用**去解模组的 `content/blocks/*.json`（那样还得处理嵌套目录、HJSON 方言、字段缺省）。
 * 🔴 为什么这事要紧：第 116 轮起模组方块一律按 **1 格**算 ⇒ 含模组多格建筑的蓝图
 * **包围盒会算小**（边缘的贴图会被裁掉）。有了它，几何就对了。
 * ⚠️ 未做（如实记）：**朝向**（`rotate/rotateDraw`）拿不到 —— 那是 Java 类里的字段，
 *   数据模组的 JSON 里基本不写、Java 模组更是只在代码里 ⇒ 模组方块一律**不转**
 *   （会和游戏里转过的传送带/炮塔不一致，见 REF §81.6）。
 *
 * <h3>纯 Java</h3>
 * 本类只用 `java.util.zip` + `java.io`（不碰 Android）⇒ 能在 PC 上单独编译、
 * 拿**真的模组包**喂断言；真正的像素解码在 Android 侧（{@link MschSheet} 用 `BitmapFactory`）。
 */
public final class ModSprites {
    /** 命中：哪个包里的哪个条目 */
    public static final class Hit {
        public final File pack;
        public final String entry;
        public final boolean directory;

        Hit(File pack, String entry, boolean directory) {
            this.pack = pack;
            this.entry = entry;
            this.directory = directory;
        }

        @Override public String toString() {
            return pack.getName() + "!" + entry;
        }
    }

    /** 注册名 → 命中 */
    private final Map<String, Hit> index = new HashMap<>();
    /** 注册名 → 方块 JSON 里的 `type`（= 游戏 jar 里的类名；第 120 轮，用来判朝向） */
    private final Map<String, String> types = new HashMap<>();
    /** 参与索引的模组（名字进诊断） */
    private final List<String> packs = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    /** `ClassRotateTable` 解出来的「类名 → 会不会转」（懒解析一次，与 {@link MschPreview#rows} 同一套） */
    private static Map<String, String> CLS;

    private static synchronized Map<String, String> classes() {
        if (CLS == null) {
            Map<String, String> m = new HashMap<>(256);
            for (String line : MapStats.inflate(ClassRotateTable.DATA_B64).split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] c = line.split("\t", -1);
                if (c.length >= 2) m.put(c[0].trim(), c[1].trim());
            }
            CLS = m;
        }
        return CLS;
    }

    /**
     * 这个**模组方块**会不会按朝向转（第 120 轮）。
     *
     * <p>原理：数据模组的方块 JSON 用 `type` 指定**游戏 jar 里的类名**（实测蓝钢拓展写着
     * `"type": "MassDriver"`），而 `rotate/rotateDraw` 是那些类**构造函数**里设的
     * （`Turret.java:163` / `Conveyor.java:40` 都在类自己里设 true）⇒ 类名查表即可。
     *
     * @return 没这个方块 / 没读它的 JSON / 类名查不到 ⇒ **false**（不猜）
     */
    public boolean rotates(String block) {
        String cls = types.get(block);
        if (cls == null) return false;
        return "1".equals(classes().get(cls));
    }

    /** 这个方块 JSON 里写的 `type`（诊断/自检用；没有返回 null） */
    public String typeOf(String block) {
        return types.get(block);
    }

    /** 表里有多少个类（自检对账） */
    public static int classCount() {
        return classes().size();
    }

    /** 某个**游戏 jar 里的类名**转不转（自检/渲染用；查不到 = false，不猜） */
    public static boolean classRotates(String cls) {
        return cls != null && "1".equals(classes().get(cls));
    }

    private ModSprites() {}

    /**
     * 给本槽的模组建索引。
     *
     * @param mods {@link Mods#scan} 出来的条目（`name` 已经是游戏口径的内部名）
     * @return 一个都没扫到也返回对象（查表返回 null），**不返回 null**（省得调用方到处判空）
     */
    public static ModSprites of(List<Mods.Info> mods) {
        ModSprites out = new ModSprites();
        if (mods == null) return out;
        for (Mods.Info info : mods) {
            if (info == null || info.file == null) continue;
            String prefix = info.name == null ? "" : info.name.trim();
            if (prefix.isEmpty()) continue;
            try {
                int n = info.directory ? out.indexDir(info.file, prefix) : out.indexZip(info.file, prefix);
                if (n > 0) out.packs.add(prefix + "(" + n + ")");
            } catch (Throwable t) {
                out.notes.add(prefix + ": " + t.getClass().getSimpleName());
            }
        }
        return out;
    }

    private int indexZip(File zip, String prefix) throws Exception {
        ZipFile zf = new ZipFile(zip);
        int n = 0;
        try {
            Enumeration<? extends ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String low = name.toLowerCase(Locale.ROOT);
                if (low.endsWith(".png")) {
                    String region = regionOf(name, prefix);
                    if (region != null && !index.containsKey(region)) {
                        index.put(region, new Hit(zip, name, false));
                        n++;
                    }
                } else if (isBlockJson(name)) {
                    // 方块 JSON：顺手把 `type`（= jar 里的类名）记下来 —— 判朝向要用（第 120 轮）
                    String base = baseOf(name);
                    if (base != null) {
                        String cls = typeIn(readAll(zf.getInputStream(e)));
                        if (cls != null) types.put(prefix + "-" + base, cls);
                    }
                }
            }
        } finally {
            zf.close();
        }
        return n;
    }

    /** `content/blocks/**` 下的 `.json`（数据模组的方块定义） */
    static boolean isBlockJson(String path) {
        String p = path.replace('\\', '/');
        return p.startsWith("content/blocks/") && p.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /** 文件名主干（去目录、去 `.json`）—— 与注册名同一套（`content/blocks/a/b.json` ⇒ `b`） */
    static String baseOf(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String file = slash < 0 ? p : p.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /**
     * 从方块 JSON 里取 `type`（= 游戏 jar 里的类名）。
     *
     * ⚠️ 用**正则**而不是完整 JSON 解析，是因为模组 JSON 允许 HJSON 方言（不带引号的键、
     *   尾随逗号、注释），拿严格 JSON 解会有一批模组解不开；这里只要一个类名，容错写法更稳。
     *   判据见自检 ㊿：拿**真的模组包**（蓝钢拓展 `"type": "MassDriver"`）验。
     */
    static String typeIn(byte[] json) {
        if (json == null || json.length == 0) return null;
        String s = new String(json, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\"']?type[\"']?\\s*[:=]\\s*[\"']?([A-Za-z_$][A-Za-z0-9_$]*)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] readAll(InputStream in) {
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 12);
            byte[] buf = new byte[1 << 12];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private int indexDir(File dir, String prefix) throws Exception {
        int n = 0;
        List<File> stack = new ArrayList<>();
        stack.add(dir);
        while (!stack.isEmpty()) {
            File d = stack.remove(stack.size() - 1);
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isDirectory()) {
                    stack.add(f);
                    continue;
                }
                String low = f.getName().toLowerCase(Locale.ROOT);
                String rel = dir.toURI().relativize(f.toURI()).getPath();
                if (low.endsWith(".png")) {
                    String region = regionOf(rel, prefix);
                    if (region != null && !index.containsKey(region)) {
                        index.put(region, new Hit(dir, rel, true));
                        n++;
                    }
                } else if (isBlockJson(rel)) {
                    String base = baseOf(rel);
                    if (base != null) {
                        String cls = typeIn(readAll(new FileInputStream(f)));
                        if (cls != null) types.put(prefix + "-" + base, cls);
                    }
                }
            }
        }
        return n;
    }

    /**
     * 条目路径 → 游戏注册名（照抄 `Mods.java:387~410`）。
     *
     * @return 不是 `sprites/**` 的 png ⇒ null（模组图标的 `icon.png` 之类不算内容贴图）
     */
    static String regionOf(String path, String prefix) {
        String p = path.replace('\\', '/');
        if (p.startsWith("./")) p = p.substring(2);
        boolean override = p.startsWith("sprites-override/");
        if (!override && !p.startsWith("sprites/")) return null;
        int slash = p.lastIndexOf('/');
        String file = slash < 0 ? p : p.substring(slash + 1);
        String base = file.toLowerCase(Locale.ROOT).endsWith(".png")
                ? file.substring(0, file.length() - 4) : file;
        if (base.isEmpty()) return null;
        // `a.b.png` ⇒ 取第一个 '.' 之前（游戏同款）
        int dot = base.indexOf('.');
        String region = dot >= 0 ? base.substring(0, dot) : base;
        if (region.isEmpty() || override) return region;
        // 已经被类别前缀过（`block-<模组名>-…`）⇒ 不加模组前缀
        int hyphen = base.indexOf('-');
        if (hyphen != -1 && base.substring(hyphen + 1).startsWith(prefix + "-")) return base;
        return prefix + "-" + base;
    }

    /** 查一个方块该画哪张 PNG（方块内部名精确匹配；没有返回 null） */
    public Hit find(String block) {
        return block == null ? null : index.get(block);
    }

    public boolean isEmpty() {
        return index.isEmpty();
    }

    /** 索引里的注册名（自检/诊断用；**只读**） */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(index.keySet());
    }

    /** 诊断串（进报告：哪些模组进了索引、各贡献多少张） */
    public String describe() {
        return index.size() + " 张 / 包 " + packs + (notes.isEmpty() ? "" : " / 失败 " + notes);
    }

    /** 取 PNG 原始字节（纯 Java；解码在调用方） */
    public byte[] bytes(Hit h) {
        if (h == null) return null;
        InputStream in = null;
        ZipFile zf = null;
        try {
            if (h.directory) {
                in = new FileInputStream(new File(h.pack, h.entry));
            } else {
                zf = new ZipFile(h.pack);
                ZipEntry e = zf.getEntry(h.entry);
                if (e == null) return null;
                in = zf.getInputStream(e);
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 14);
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 贴图宽高 → 占几格（32 px = 1 格，四舍五入，至少 1）。
     * ⚠️ trim 过的贴图（26×28 那种）也算 1 格 —— 它本来就是 1 格方块裁掉透明边。
     */
    public static int tilesOf(int w, int h) {
        return Math.max(1, (Math.max(w, h) + MschSprite.TILE_PX / 2) / MschSprite.TILE_PX);
    }

    /**
     * 这张贴图**占几格**（PNG 头里的宽高 ÷ 32）。
     *
     * @return 读不到返回 0（调用方回落到表 / 1 格）
     */
    public int sizeOf(Hit h) {
        int[] wh = dims(h);
        return wh == null ? 0 : tilesOf(wh[0], wh[1]);
    }

    /** PNG 的宽高（只读头 24 字节的 IHDR；不是 PNG / 读不到返回 null） */
    public int[] dims(Hit h) {
        if (h == null) return null;
        InputStream in = null;
        ZipFile zf = null;
        try {
            if (h.directory) {
                in = new FileInputStream(new File(h.pack, h.entry));
            } else {
                zf = new ZipFile(h.pack);
                ZipEntry e = zf.getEntry(h.entry);
                if (e == null) return null;
                in = zf.getInputStream(e);
            }
            byte[] head = new byte[24];
            int got = 0;
            while (got < head.length) {
                int n = in.read(head, got, head.length - got);
                if (n <= 0) break;
                got += n;
            }
            // 签名 8 字节 + 长度 4 + "IHDR" 4 + 宽 4 + 高 4
            if (got < 24) return null;
            if (head[0] != (byte) 0x89 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') return null;
            if (head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') return null;
            int w = ((head[16] & 0xff) << 24) | ((head[17] & 0xff) << 16) | ((head[18] & 0xff) << 8)
                    | (head[19] & 0xff);
            int hh = ((head[20] & 0xff) << 24) | ((head[21] & 0xff) << 16) | ((head[22] & 0xff) << 8)
                    | (head[23] & 0xff);
            if (w <= 0 || hh <= 0 || w > 4096 || hh > 4096) return null;
            return new int[]{w, hh};
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
