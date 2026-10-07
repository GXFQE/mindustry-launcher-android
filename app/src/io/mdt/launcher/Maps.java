package io.mdt.launcher;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 地图的**只读清点**（F10 的后半：地图与存档一起做）。
 *
 * 三个来源（用户 2026-10-03 点名"别忘了游戏和模组里内置的地图"）：
 * <pre>
 *   ① 本槽 {@code <槽>/maps/}            —— 用户自己导入的
 *   ② 游戏版本 APK 里的 {@code assets/maps/**} —— 游戏自带（官方图 + 战役区块图等）
 *   ③ 槽内各模组包里的 {@code maps/**}     —— 模组自带（标注来自哪个模组）
 * </pre>
 *
 * 元数据一律走 {@link MsavMeta}：zip/APK 里的条目**流式**读（我们的解析器只读第一块 meta），
 * 所以哪怕 APK 里几百张图、模组包几十 MB，也不会把内容解压出来。
 *
 * 🔴 本类**只读**：不 mkdirs、不落盘、不改任何东西（与 {@link Mods#scan} 同一条纪律）。
 */
public final class Maps {
    /** 来源 */
    public static final int FROM_SLOT = 0, FROM_GAME = 1, FROM_MOD = 2;

    /** 一条地图记录 */
    public static final class Item {
        public int from;
        /** 人读的来源：`本槽` / `游戏自带` / 模组名 */
        public String source = "";
        /** 诊断用的一行定位（槽内文件 = 绝对路径；zip 内 = `包!/条目`） */
        public String where = "";
        /** 槽内文件（zip 来源为 null） */
        public File file;
        /** zip/apk 来源：容器与条目名 */
        public File container;
        public String entry;
        public long bytes;
        public MsavMeta meta;
        /** 元数据读不出来时的原因（没 meta 也能列出来，不静默丢） */
        public String error;

        /** 列表行第一行：文件名 / 条目名 */
        public String name() {
            if (file != null) return file.getName();
            String e = entry == null ? "" : entry;
            int i = e.lastIndexOf('/');
            return i >= 0 ? e.substring(i + 1) : e;
        }

        /**
         * 列表行第二行：区分度优先（地图口径：真名 · 尺寸 · 作者）。
         *
         * ★ 2026-10-04：改成**收 Context** —— 文案搬进资源了（P3 第一片，见 {@link MsavText}）。
         *   原来是 Java 里拼中文，默认语言翻成英文之后这一行会中英混排。
         */
        public String line(Context c) {
            if (meta == null || !meta.ok) {
                // ⚠️ 原因可能是"异常类名"，也可能是我们自己的中文（老 `error`）——
                //    前者由 MsavText.userReason 翻成白话，后者原样透传（那批还没做错误码）。
                return Trans.get(c, R.string.msav_unreadable_fmt, MsavText.userReason(c, meta));
            }
            return MsavText.shortLine(c, meta, false);
        }

        /**
         * 来源标签（列表行用）：本槽 / 游戏自带 / 模组「x」。
         *
         * ★ 为什么**不接受** `source` 字段里的现成值：那是个**数据字段**（模组文件名是数据，
         *   但"本槽""游戏自带"原来是写死的中文），而且它还要经 Intent 传出去 ⇒
         *   拿它当界面文案会让"数据"和"文案"搅在一起（与 `MapStats.SRC_*` 是同一类问题）。
         *   ⇒ 从 {@link #from} 这个**码**推文案，`source` 只留模组名。
         */
        public String sourceLabel(Context c) {
            switch (from) {
                case FROM_SLOT: return Trans.get(c, R.string.map_from_slot);
                case FROM_GAME: return Trans.get(c, R.string.map_from_game);
                default: return source == null || source.trim().isEmpty() ? ""
                        : Trans.get(c, R.string.map_from_mod_fmt, source);
            }
        }

        /** 详情第一行用的来源（本槽那句多一个括号说明，所以与列表行不同） */
        private String sourceLabelDetail(Context c) {
            return from == FROM_SLOT ? Trans.get(c, R.string.map_from_slot_detail) : sourceLabel(c);
        }

        /**
         * ★ 点开一项时摊开的详情（第 60 轮，用户：「我们可以加上点开地图查看详细信息」）。
         *
         * 组成：**这是谁带来的 + 文件本身 + 元数据**（元数据交给 {@link MsavText#detail}，
         * 那是面向用户的版本；技术味的 `report()` 只用于落盘报告）。
         * ⚠️ 位置那行要**说人话**：模组/游戏自带的地图不在槽里，用户看不到那个文件，
         *    所以写「在 xxx 里」而不是 `zip!/maps/...` 这种路径。
         */
        public String detail(Context c) {
            StringBuilder sb = new StringBuilder();
            String n = meta != null && meta.ok ? Mods.stripColors(meta.displayName()) : "";
            String file = name();
            // ★ 真名与文件名相同时（游戏自带图常见：文件 `0.msav`、真名 `0`）只显示一遍 ——
            //   否则弹窗标题是 `0.msav`、正文第一行又是 `0`，看着像重复
            String stem = file.toLowerCase(Locale.ROOT).endsWith(".msav")
                    ? file.substring(0, file.length() - 5) : file;
            if (!n.isEmpty() && !n.equalsIgnoreCase(stem)) sb.append(n).append('\n');
            sb.append(Trans.get(c, R.string.map_detail_from_fmt, sourceLabelDetail(c))).append('\n');
            sb.append(Trans.get(c, R.string.map_detail_file_fmt, name(),
                    Util.formatSize(bytes))).append('\n');
            if (from != FROM_SLOT) {
                sb.append(Trans.get(c, R.string.map_detail_in_fmt,
                        container == null ? sourceLabel(c) : container.getName())).append('\n');
            }
            if (meta == null) {
                sb.append(Trans.get(c, R.string.msav_unreadable)).append('\n');
            } else {
                sb.append(MsavText.detail(c, meta));
            }
            return sb.toString();
        }

        /** 排序用的显示名（优先真名） */
        public String sortKey() {
            return displayName().toLowerCase(Locale.ROOT);
        }

        /**
         * ★ 给人看的名字（**去色码、优先真名**，没有就退回文件名/条目名）。
         *   界面（"源图"那一行 / 选源图列表）都用它 —— 别拿 {@link #sortKey}（那是小写、给排序的）。
         */
        public String displayName() {
            String n = meta != null && meta.ok ? Mods.stripColors(meta.displayName()) : "";
            if (n == null || n.trim().isEmpty()) n = name();
            return n == null ? "" : n;
        }
    }

    private Maps() {}

    /**
     * 给**一份存档**找它的"源图"（**三个来源都算**：本槽 / 游戏自带 / 模组自带）。
     *
     * <p>两条判据，都是游戏自己的口径：
     * <ol>
     *   <li><b>自定义图</b>：存档的 {@code mapname} == 图的 {@code name}
     *       —— `SaveMeta` 显示"地图：X"用的就是 {@code maps.all().find(m -> m.name().equals(mapname))}。</li>
     *   <li><b>原版战役（区块）图</b>：存档的 {@code sectorPreset} == 图条目的<b>文件名主干</b><br>
     *       出处：{@code SectorPreset.java:84 → new FileMapGenerator(fileName == null ? this.name : fileName, this)}
     *       ＋ `FileMapGenerator` 的候选路径 {@code <planet>/<mapName>.msav}
     *       ⇒ 图就躺在 APK 的 {@code assets/maps/<星球>/<区块键>.msav}。</li>
     * </ol>
     *
     * 🔴 **为什么不能只靠 `mapname`**（2026-10-05 用户：「**原版战役地图为什么识别不到啊**」）：
     *    战役存档里的 `mapname` 是**存档当时语言的显示名**（「始发地区」/「Fallen Vessel」），
     *    而图文件里的 `name` 是**基础名**（`onset` / `fallenVessel`）⇒ 中文环境下**永远匹配不上**。
     *    语料实测：按 `mapname` 只命中 **6/312**，按 `sectorPreset` 命中 **90/312**。
     * ⚠️ 两条判据都命中时**全都返回**（界面据此提示"候选 N 张、点这里选一张"，
     *    默认取第一条 = `mapname` 那条），不替用户猜。
     */
    public static List<Item> bySave(List<Item> items, String mapname, String sectorPreset) {
        List<Item> out = new ArrayList<>();
        if (items == null) return out;
        if (mapname != null && !mapname.trim().isEmpty()) {
            for (Item it : items) {
                if (it == null || it.meta == null || !it.meta.ok) continue;
                if (mapname.equals(it.meta.get("name", null))) out.add(it);
            }
        }
        if (sectorPreset != null && !sectorPreset.trim().isEmpty()) {
            for (Item it : items) {
                if (it == null || out.contains(it)) continue;
                if (sectorPreset.equalsIgnoreCase(stem(it.name()))) out.add(it);
            }
        }
        return out;
    }

    /** 去掉 `.msav` 后缀（战役图的文件名主干 == 区块键） */
    private static String stem(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".msav") ? name.substring(0, name.length() - 5) : name;
    }

    /**
     * 按 `mapname` 在**已经扫描出来的**清单里找"源图"（只按名字那条判据）。
     * ⚠️ 界面走 {@link #bySave}（它多一条 `sectorPreset`，战役图靠那条）；
     *    这个只留给"只要名字"的调用方与自检。
     */
    public static List<Item> byName(List<Item> items, String mapname) {
        return bySave(items, mapname, null);
    }

    /**
     * 只读清点三个来源。
     *
     * @param apkPath 当前槽指向的**游戏版本 APK**（可为 null ⇒ 跳过"游戏自带"那一组）
     */
    public static List<Item> scan(Context ctx, String slot, String apkPath) {
        List<Item> out = new ArrayList<>();
        File dir = Data.dirOf(ctx, slot);
        if (dir != null) {
            scanSlotMaps(ctx, new File(dir, "maps"), out);
            scanModMaps(ctx, new File(dir, "mods"), out);
        }
        if (apkPath != null && !apkPath.trim().isEmpty()) {
            scanContainer(ctx, new File(apkPath.trim()), "assets/maps/", FROM_GAME, "", out);
        }
        java.util.Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.from != b.from) return a.from - b.from;      // 本槽 → 游戏 → 模组
                return a.sortKey().compareTo(b.sortKey());
            }
        });
        return out;
    }

    /** ① 本槽 `maps/`（只列 `.msav`；`.part` 是"写了一半"，不算）—— 包内可见，供自检直接调 */
    static void scanSlotMaps(Context ctx, File mapsDir, List<Item> out) {
        File[] fs = mapsDir == null ? null : mapsDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isFile()) continue;
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".msav")) continue;
            Item it = new Item();
            it.from = FROM_SLOT;
            // ⚠️ `source` **不放中文标签**（"本槽"）—— 那是文案，由 {@link Item#sourceLabel} 从
            //    `from` 推出来；这个字段只承载**数据**（模组文件名）。它还要经 Intent 传出去。
            it.source = "";
            it.file = f;
            it.where = f.getAbsolutePath();
            it.bytes = f.length();
            it.meta = metaOf(it);
            // 只有"元数据读不出来"才有原因；**不要**塞一句自己的中文进去（那是文案，不是数据）
            if (it.meta != null && !it.meta.ok) it.error = it.meta.error;
            out.add(it);
        }
    }

    /** ③ 槽内模组包里的 `maps/**`（zip/jar + 目录形态的模组） */
    private static void scanModMaps(Context ctx, File modsDir, List<Item> out) {
        File[] fs = modsDir == null ? null : modsDir.listFiles();
        if (fs == null) return;
        for (File m : fs) {
            if (m == null) continue;
            String lower = m.getName().toLowerCase(Locale.ROOT);
            if (m.isFile() && (lower.endsWith(".zip") || lower.endsWith(".jar"))) {
                scanContainer(ctx, m, "maps/", FROM_MOD, m.getName(), out);
            } else if (m.isDirectory()) {
                // 目录形态的模组：走文件系统（下潜几层就够，模组不会把地图埋太深）
                scanDirMaps(ctx, m, 0, m.getName(), out);
            }
        }
    }

    private static void scanDirMaps(Context ctx, File dir, int depth, String modName, List<Item> out) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                scanDirMaps(ctx, f, depth + 1, modName, out);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".msav")
                    && f.getParentFile() != null && "maps".equals(f.getParentFile().getName())) {
                Item it = new Item();
                it.from = FROM_MOD;
                it.source = modName;
                it.file = f;
                it.where = f.getAbsolutePath();
                it.bytes = f.length();
                it.meta = metaOf(it);
                if (it.meta == null || !it.meta.ok) it.error = it.meta == null ? Trans.get(ctx, R.string.maps_err_unreadable) : it.meta.error;
                out.add(it);
            }
        }
    }

    /**
     * ② / ③ 的公共部分：在 zip / APK 容器里找**某个前缀下**的 `.msav`。
     *
     * @param prefix       `assets/maps/`（游戏 APK）或 `maps/`（模组包）；**允许嵌套**（`<子目录>/maps/...` 也算）
     * @param from         {@link #FROM_GAME} / {@link #FROM_MOD}
     * @param sourceLabel  人读来源（`游戏自带` / 模组文件名）
     */
    static void scanContainer(Context ctx, File container, String prefix, int from, String sourceLabel,
                              List<Item> out) {
        if (container == null || !container.isFile()) return;
        ZipFile zf = null;
        try {
            zf = new ZipFile(container);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String n = ze.getName();
                if (!n.toLowerCase(Locale.ROOT).endsWith(".msav")) continue;
                // 前缀匹配：要么以 prefix 开头，要么路径里出现过 "/" + prefix（模组可能整个套一层目录）
                String p = prefix;
                boolean hit = n.startsWith(p) || n.contains("/" + p);
                if (!hit) continue;
                Item it = new Item();
                it.from = from;
                it.source = sourceLabel;
                it.container = container;
                it.entry = n;
                it.where = container.getName() + "!/" + n;
                it.bytes = ze.getSize();
                it.meta = metaOf(it);                     // ★ 只读第一块 ⇒ 不解压整份
                if (it.meta != null && !it.meta.ok) it.error = it.meta.error;
                out.add(it);
            }
        } catch (Throwable t) {
            // 容器打不开（不是 zip / 被占用）：**不抛**，只是这一组为空
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 各来源的条数（界面上分组标题用） */
    public static int count(List<Item> items, int from) {
        int n = 0;
        for (Item i : items) {
            if (i != null && i.from == from) n++;
        }
        return n;
    }

    // ── 记录 → Intent → 记录（地图详情页要用；**只传定位信息**，不传对象） ──────

    /**
     * 把一条记录写进 Intent。
     *
     * ★ 为什么传"定位信息"而不是整个对象：详情页是**另一个 Activity**，
     *   跨页面只能过 `Intent` 的原始类型；而 `Item` 里真正决定"这是哪张图"的
     *   只有 `from/source/file/container/entry/bytes` 这几项，元数据可以重读
     *   （只读第一块，见 {@link MsavMeta}）。
     */
    public static void putExtra(android.content.Intent i, Item it) {
        if (i == null || it == null) return;
        i.putExtra("from", it.from);
        i.putExtra("source", it.source);
        i.putExtra("bytes", it.bytes);
        i.putExtra("file", it.file == null ? null : it.file.getAbsolutePath());
        i.putExtra("container", it.container == null ? null : it.container.getAbsolutePath());
        i.putExtra("entry", it.entry);
    }

    /** 从 Intent 还原一条记录（并重读元数据） */
    public static Item fromExtra(android.content.Context ctx, android.content.Intent i) {
        if (i == null) return null;
        Item it = new Item();
        it.from = i.getIntExtra("from", FROM_SLOT);
        it.source = i.getStringExtra("source");
        if (it.source == null) it.source = "";
        it.bytes = i.getLongExtra("bytes", 0);
        String f = i.getStringExtra("file");
        String c = i.getStringExtra("container");
        it.entry = i.getStringExtra("entry");
        if (f != null) it.file = new File(f);
        if (c != null) it.container = new File(c);
        if (it.file == null && (it.container == null || it.entry == null)) return null;
        it.where = it.file != null ? it.file.getAbsolutePath()
                : (it.container.getName() + "!/" + it.entry);
        it.meta = metaOf(it);
        if (it.meta != null && !it.meta.ok) it.error = it.meta.error;
        return it;
    }

    /**
     * 读一条记录的元数据 —— **清点与"详情页重建"共用这一处**
     * （两条路：槽内文件直接读；zip/APK 条目开流读，只读第一块）。
     */
    static MsavMeta metaOf(Item it) {
        if (it == null) return null;
        if (it.file != null) return MsavMeta.read(it.file);
        if (it.container == null || it.entry == null) return null;
        ZipFile zf = null;
        InputStream in = null;
        try {
            zf = new ZipFile(it.container);
            ZipEntry e = zf.getEntry(it.entry);
            if (e == null) return null;
            in = new BufferedInputStream(zf.getInputStream(e), 8192);
            return MsavMeta.read(in);
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
