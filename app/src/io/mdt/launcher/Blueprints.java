package io.mdt.launcher;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 蓝图的**只读清点**（F22 第一步：列表页要能列出"本槽有哪些蓝图"）。
 *
 * <h3>两个来源 —— 与游戏 `Schematics.load()` 逐一对应</h3>
 * <pre>
 *   Schematics.load():
 *     schematicDirectory.walk(ext=="msch")            ⇒ ① 本槽 {@code <槽>/schematics/**}（**递归**）
 *     mods.listFiles("schematics", (mod, file) -> …)  ⇒ ② **已启用**模组包的 {@code schematics/} 下**直接子文件**
 * </pre>
 *
 * 🔴 **为什么没有"游戏自带"这一组**（地图页有，蓝图页没有）：APK 里确实躺着
 *   {@code assets/baseparts/*.msch}（159.7/160 实测 **210** 个），但**游戏自己的蓝图列表不列它们** ——
 *   那批是 {@code mindustry.ai.BaseRegistry}（AI 的基地模板）读的。
 *   证据：160.4 的 jar 里只有 `ClientLauncher` 与 `ai/BaseRegistry` 两个类提到 `baseparts`，
 *   而 `Schematics.load()` 只走「本槽目录 + 模组包」两条（源码已核）。
 *   ⇒ 列它们等于**凭空造一个"游戏自带"分组**，与"界面 == 游戏"这条纪律冲突。
 *   （另外 4 个"核心蓝图" `Loadouts.basicShard/…` 是**代码里生成的**，不是文件 ⇒ 我们也列不出来。）
 *
 * 🔴 **模组那一侧的三条过滤，逐条照抄游戏**：
 *   ① `eachEnabled` ⇒ **只列已启用**的模组（禁用模组连包都不加载）；
 *   ② `Mods.loadMod` 会**整个跳过**"声明了 java 却没有 `classes.dex`"的包
 *      （见 {@link Mods.Info#willFailJavaLoad()}）⇒ 那种包的蓝图也不该列；
 *   ③ 游戏上次崩过 ⇒ `skipModLoading`（本次不加载任何模组）⇒ 同样不列。
 * ⚠️ 这些都是"**游戏此刻看不到的东西**"，列出来就是误导（与模组页/地图页同一个口径）。
 *
 * 🔴 本类**只读**：不 mkdirs、不落盘、不改任何东西（与 {@link Maps#scan} 同一条纪律）。
 */
public final class Blueprints {
    /** 来源：本槽 / 模组自带 */
    public static final int FROM_SLOT = 0, FROM_MOD = 1;

    /** 一条蓝图记录 */
    public static final class Item {
        public int from;
        /** **数据**（模组显示名），不是文案 —— 「本槽」「模组」由 {@link #sourceLabel} 从 from 推 */
        public String source = "";
        /** 诊断用的一行定位（槽内 = 绝对路径；zip 内 = `包!/条目`） */
        public String where = "";
        /** 槽内文件（zip 来源为 null） */
        public File file;
        /** zip/apk 来源：容器与条目名 */
        public File container;
        public String entry;
        public long bytes;
        /** 解析结果（失败时 {@link Msch#ok} 为 false，原因走 {@link MschText}） */
        public Msch msch;
        /** 读不出来时的原因（**维护者视角**：异常原文或我们的中文句子） */
        public String error = "";

        // ── 后台算出来的派生量（界面只读，别在点击时重算）───────────────────
        /** 用到的方块**种类**数 */
        public int blockKinds;
        /** 其中本槽认不出的**种类**数（游戏会把它们**静默当空气**） */
        public int missingKinds;
        /** 认不出的**格子**数 */
        public int missingTiles;
        /**
         * ★ **这份的"缺件"名单可能认错**（第 112 轮，用户：「这种情况特别标记一下（告诉用户很有可能识别错误）」）。
         *
         * 判据**不是**猜的：{@link MapStats.Pack#hasCode} —— 本槽存在"把方块写在代码或脚本里、
         * 包里又没有方块 JSON"的**已启用非 hidden** 模组时，我们那张表里就缺它的方块
         * ⇒ 名单里的名字可能其实存在（真机实例：`原版瘤液拓展` 自带的 8 份蓝图里有 4 份被误标）。
         * 由 {@link #markSoft} 统一置位；**只影响文案与标记，不改判据本身**。
         */
        public boolean softMissing;

        public boolean ok() {
            return msch != null && msch.ok;
        }

        /** 列表行第一行：文件名 / 条目名（**文件里没有 name 时**也用它兜底） */
        public String name() {
            if (file != null) return file.getName();
            String e = entry == null ? "" : entry;
            int i = e.lastIndexOf('/');
            return i >= 0 ? e.substring(i + 1) : e;
        }

        /** 给人看的名字：蓝图自己的 `name`（去色码）优先，没有就退回文件名 */
        public String displayName() {
            String n = ok() ? Mods.stripColors(msch.displayName()) : "";
            n = n == null ? "" : n.trim();
            return n.isEmpty() ? name() : n;
        }

        public String sortKey() {
            return displayName().toLowerCase(Locale.ROOT);
        }

        /** 来源标签：本槽 / 模组「x」 */
        public String sourceLabel(Context c) {
            return from == FROM_SLOT ? c.getString(R.string.bp_from_slot)
                    : (source == null || source.trim().isEmpty() ? c.getString(R.string.bp_from_mod_unknown)
                    : c.getString(R.string.map_from_mod_fmt, source));
        }

        /**
         * 列表行第二行：**区分度优先**（尺寸/瓦片 · 分类 · 缺件警告）。
         *
         * ★ 缺方块那句**必须进列表行**：这是"开游戏之前就知道这蓝图会缺件"的唯一出口
         *   （游戏那边是静默丢方块，贴出来才发现少了东西）。
         * ★ 拼接方式与 {@link MapDetailActivity} 的 `cat()` 一致：**各句都是完整资源**，
         *   在 Java 侧用 ` · ` 连起来 —— 不许在资源里留前导/尾随空白（aapt2 会剥掉）。
         */
        public String line(Context c) {
            if (!ok()) {
                return c.getString(R.string.msav_unreadable_fmt, MschText.reason(c, msch));
            }
            List<String> parts = new ArrayList<>();
            parts.add(c.getString(R.string.bp_line_size_fmt,
                    msch.declaredWidth, msch.declaredHeight, msch.tileCount()));
            String labels = join(msch.labels);
            if (!labels.isEmpty()) parts.add(labels);
            StringBuilder sb = new StringBuilder(joinParts(parts));
            if (missingKinds > 0) {
                // ★ 两种说法**刻意不同**：拿不准的时候**不许**断言"本槽没有 / 会被当空气丢掉"
                //   （那是给用户一个可能错的结论）—— 改成"我们没认出来" + 说清为什么可能认错。
                sb.append('\n').append(c.getString(softMissing
                        ? R.string.bp_line_missing_soft_fmt
                        : R.string.bp_line_missing_fmt, missingKinds, missingTiles));
            } else if (msch.labelsBad && labels.isEmpty()) {
                sb.append('\n').append(c.getString(R.string.bp_labels_bad));
            }
            return sb.toString();
        }

        /**
         * 详情页顶部几行（**给用户**）：这是谁带来的 + 文件 + 这份蓝图本身。
         * ⚠️ 解析版本/字典/标签这些**维护者**视角的东西在「技术细节」段，不进这里。
         */
        public String detail(Context c) {
            StringBuilder sb = new StringBuilder();
            sb.append(c.getString(R.string.bp_detail_from_fmt, sourceLabel(c))).append('\n');
            sb.append(c.getString(R.string.bp_detail_file_fmt, name(), Util.formatSize(bytes)))
              .append('\n');
            if (from == FROM_MOD) {
                sb.append(c.getString(R.string.bp_detail_in_fmt,
                        container == null ? sourceLabel(c) : container.getName())).append('\n');
            }
            if (!ok()) {
                sb.append(MschText.reason(c, msch)).append('\n');
                return sb.toString();
            }
            sb.append(c.getString(R.string.bp_detail_size_fmt,
                    msch.declaredWidth, msch.declaredHeight, msch.tileCount())).append('\n');
            if (!msch.labels.isEmpty()) {
                sb.append(c.getString(R.string.bp_detail_labels_fmt, join(msch.labels))).append('\n');
            }
            String d = Mods.stripColors(msch.description()).trim();
            if (!d.isEmpty()) {
                sb.append(c.getString(R.string.bp_detail_desc_fmt,
                        d.length() > 200 ? d.substring(0, 200) + "…" : d)).append('\n');
            }
            return sb.toString();
        }
    }

    private Blueprints() {}

    /**
     * 给清点结果打上"**缺件名单可能认错**"的标记（第 112 轮）。
     *
     * @param opaquePacks 「有代码/脚本、又没带方块 JSON」的已启用模组数（{@link MapStatsMods.SlotContent#opaque}）
     *
     * ★ 为什么单独一个函数：它是一条**判据**（列表行与详情页都读同一个标记），
     *   自检要能直接喂它两个方向（有这种模组 / 没有）—— 见自检㊹⑨。
     */
    public static void markSoft(List<Item> items, int opaquePacks) {
        if (items == null) return;
        for (Item it : items) it.softMissing = it.missingKinds > 0 && opaquePacks > 0;
    }

    /**
     * 「缺哪些方块」那一段的标题（`soft` ⇒ 带"可能认错"标记）。
     *
     * 🔴 **为什么把它挪到这里**（第 113 轮，真机崩溃之后）：界面与自检必须走**同一份格式化**。
     *   第 112 轮我在详情页里就地写 `getString(...)`，而自检那条"实拼"断言用的是自己挑的参数
     *   （两个 `int`）—— 真实调用点传的却是 `MapStatsMods.num(tiles)`（**字符串**），
     *   于是资源里的 `%2$d` 在**真机**上抛 `IllegalFormatConversionException`、主进程崩，
     *   而自检**全绿**（判据测的不是真实参数）。⇒ 收口成这两个函数，
     *   自检直接调它们（= 真实调用点的类型），这类漂移就再也藏不住。
     */
    public static String missingTitle(Context c, boolean soft) {
        return c.getString(soft ? R.string.bp_section_missing_soft : R.string.bp_section_missing);
    }

    /**
     * 那一段的摘要（⚠️ 格子数走 {@link MapStatsMods#num(int)}，资源里必须写 `%s` —— **这里踩过崩溃**）。
     */
    public static String missingSummary(Context c, int kinds, int tiles, boolean soft) {
        return c.getString(soft ? R.string.bp_blocks_missing_soft_sum_fmt
                        : R.string.bp_blocks_missing_sum_fmt,
                kinds, MapStatsMods.num(tiles));
    }

    /** 分类连成一行（**分类本身是数据**：作者写的标签，不翻译） */
    static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        if (xs != null) {
            for (String s : xs) {
                if (s == null || s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** 若干**完整句子**用 ` · ` 连起来（拼接只发生在这里，资源里不留空白） */
    static String joinParts(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 只读清点两个来源。
     *
     * @param slot 槽名（蓝图是**槽自己的数据**：`<槽>/schematics/`）
     */
    public static List<Item> scan(Context ctx, String slot) {
        List<Item> out = new ArrayList<>();
        File dir = Data.dirOf(ctx, slot);
        if (dir != null) {
            scanSlot(new File(dir, "schematics"), out);
            scanMods(ctx, slot, out);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.from != b.from) return a.from - b.from;      // 本槽 → 模组
                return a.sortKey().compareTo(b.sortKey());
            }
        });
        return out;
    }

    /** 各来源的条数（页面表头用） */
    public static int count(List<Item> items, int from) {
        int n = 0;
        for (Item i : items) {
            if (i != null && i.from == from) n++;
        }
        return n;
    }

    /**
     * ① 本槽 {@code <槽>/schematics/**} —— **递归**（照游戏 `walk`，不是只扫顶层）。
     * ⚠️ 只认 `.msch`：游戏靠 `file.extEquals("msch")` 过滤，别的文件即使躺在那里也不算蓝图。
     */
    static void scanSlot(File dir, List<Item> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f == null) continue;
            if (f.isDirectory()) {
                scanSlot(f, out);                       // 递归（游戏 walk 到底）
                continue;
            }
            if (!isMsch(f.getName())) continue;
            Item it = new Item();
            it.from = FROM_SLOT;
            it.file = f;
            it.where = f.getAbsolutePath();
            it.bytes = f.length();
            read(it);
            out.add(it);
        }
    }

    /**
     * ② 模组自带的 {@code schematics/}（**直接子文件**，见类注释）。
     *
     * ★ 三条过滤（已启用 / 不会被游戏跳过 / 本次真的加载模组）**与游戏同口径**：
     *   直接复用 {@link MapStatsMods#contentFor} 里那套"哪些模组是活的"判据
     *   —— 换成自己再写一遍，迟早两边不一致。
     */
    private static void scanMods(Context ctx, String slot, List<Item> out) {
        Mods.Scan sc;
        try {
            sc = Mods.scan(ctx, slot);
        } catch (Throwable t) {
            return;                                     // 扫不动就不列模组那一组（不抛）
        }
        if (sc == null || sc.mods == null || sc.skipModLoading) return;
        for (Mods.Info m : sc.mods) {
            if (m == null || !m.enabled) continue;           // ① eachEnabled
            if (m.willFailJavaLoad()) continue;              // ② 游戏会整个跳过它
            String title = m.title();
            if (m.directory) {
                File root = m.rootDir != null ? m.rootDir : m.file;
                scanModDir(root == null ? null : new File(root, "schematics"), title, out);
            } else if (m.file != null) {
                scanModZip(m.file, m.rootPrefix, title, out);
            }
        }
    }

    /** 目录形态的模组：`<模组根>/schematics/` 下的**直接子文件** */
    static void scanModDir(File dir, String modTitle, List<Item> out) {
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f == null || f.isDirectory() || !isMsch(f.getName())) continue;
            Item it = base(FROM_MOD, modTitle);
            it.file = f;
            it.where = f.getAbsolutePath();
            it.bytes = f.length();
            read(it);
            out.add(it);
        }
    }

    /** zip/jar 形态的模组：`<根前缀>schematics/<名>.msch` —— **只有这一层**，不下潜 */
    static void scanModZip(File container, String rootPrefix, String modTitle, List<Item> out) {
        if (container == null || !container.isFile()) return;
        String dir = (rootPrefix == null ? "" : rootPrefix) + "schematics/";
        ZipFile zf = null;
        try {
            zf = new ZipFile(container);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String n = ze.getName();
                if (!n.startsWith(dir)) continue;                 // 必须在 schematics/ 下
                String rest = n.substring(dir.length());
                if (rest.isEmpty() || rest.indexOf('/') >= 0) continue;   // 直接子文件（游戏 file.list()）
                if (!isMsch(rest)) continue;
                Item it = base(FROM_MOD, modTitle);
                it.container = container;
                it.entry = n;
                it.where = container.getName() + "!/" + n;
                it.bytes = ze.getSize();
                read(it);
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

    private static Item base(int from, String source) {
        Item it = new Item();
        it.from = from;
        it.source = source;
        return it;
    }

    private static boolean isMsch(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".msch");
    }

    /** 读一份蓝图（槽内文件直接读；zip 条目开流读 —— 内核会一次性解压，蓝图本身很小） */
    private static void read(Item it) {
        it.msch = parse(it);
        if (it.msch != null && !it.msch.ok) it.error = it.msch.error;
        // ★ 与游戏 `Schematics.read(Fi)` 同口径：标签里没有 `name` ⇒ 用**文件名主干**兜底。
        //   `read(InputStream)` 拿不到文件名，这里补上（zip 条目也一样），否则列表会显示空名。
        if (it.msch != null && it.msch.ok && (it.msch.fileBase == null || it.msch.fileBase.isEmpty())) {
            it.msch.fileBase = Msch.baseName(it.name());
        }
    }

    static Msch parse(Item it) {
        if (it == null) return null;
        if (it.file != null) return Msch.read(it.file);
        if (it.container == null || it.entry == null) return null;
        ZipFile zf = null;
        InputStream in = null;
        try {
            zf = new ZipFile(it.container);
            ZipEntry e = zf.getEntry(it.entry);
            if (e == null) return null;
            in = new BufferedInputStream(zf.getInputStream(e), 8192);
            return Msch.read(in);
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

    // ── 方块构成 / 缺件（**纯逻辑**：喂一张内容表 + 名字来源即可，自检能直接调）──────

    /** 一种方块在这份蓝图里的用量 */
    public static final class Row {
        /** 内部名（**过了旧名映射**之后的，与游戏 `content.getByName` 同口径） */
        public String internal = "";
        /** 显示名（内容表的 `localizedName` → bundle → 可读化） */
        public String label = "";
        public int tiles;
        /** 本槽认不出（**游戏会静默当空气**，贴出来就缺件） */
        public boolean missing;
    }

    /**
     * 按用量降序的方块构成。
     *
     * ★ 判据（"认不出"）= 内容表里**没有这一行**，与游戏 `content.getByName(...) == null`
     *   同口径（游戏随后把它换成 `Blocks.air` ⇒ 那一格凭空消失）。
     * ⚠️ 传进来的 {@code tab} 必须是**含本槽已启用模组**的那张表（{@link MapStatsMods#contentFor}），
     *   否则装了模组也会被误报成"缺方块"。
     */
    public static List<Row> rows(Msch m, MapStats.Table tab, MapStats.Names nm) {
        List<Row> out = new ArrayList<>();
        if (m == null || !m.ok || m.tiles.isEmpty()) return out;
        Map<String, Row> byName = new LinkedHashMap<>();
        for (Msch.Tile t : m.tiles) {
            String name = t.block == null ? "" : t.block;
            if (name.isEmpty()) continue;
            Row r = byName.get(name);
            if (r == null) {
                r = new Row();
                r.internal = name;
                MapStats.Def d = tab == null ? null : tab.row(name);
                r.label = MapStats.blockLabel(nm, name, d, name);
                r.missing = (tab != null && d == null);
                byName.put(name, r);
                out.add(r);
            }
            r.tiles++;
        }
        Collections.sort(out, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                if (a.missing != b.missing) return a.missing ? 1 : -1;   // 缺件排最后
                if (a.tiles != b.tiles) return b.tiles - a.tiles;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /** 把构成里的"缺件"汇总进 {@link Item} 的派生字段（列表行要用，**别在 UI 线程算**） */
    public static void summarize(Item it, List<Row> rows) {
        if (it == null) return;
        int kinds = 0, missKinds = 0, missTiles = 0;
        for (Row r : rows) {
            kinds++;
            if (r.missing) {
                missKinds++;
                missTiles += r.tiles;
            }
        }
        it.blockKinds = kinds;
        it.missingKinds = missKinds;
        it.missingTiles = missTiles;
    }

    // ── 记录 → Intent → 记录（详情页要用；**只传定位信息**，不传对象）──────────

    public static void putExtra(android.content.Intent i, Item it) {
        if (i == null || it == null) return;
        i.putExtra("from", it.from);
        i.putExtra("source", it.source);
        i.putExtra("bytes", it.bytes);
        i.putExtra("file", it.file == null ? null : it.file.getAbsolutePath());
        i.putExtra("container", it.container == null ? null : it.container.getAbsolutePath());
        i.putExtra("entry", it.entry);
    }

    /** 从 Intent 还原一条记录（并重读蓝图） */
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
        read(it);
        return it;
    }
}
