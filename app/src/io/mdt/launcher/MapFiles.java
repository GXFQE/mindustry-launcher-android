package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.InputStream;

/**
 * 地图的**增删**（F10 预览目标的最后一块）。
 *
 * 纪律**照抄模组那条线**（`Mods.importPackage` / `place` / `trashDirOf`）——本工程的老规矩：
 * <pre>
 *   ① 落盘先写 `.part`，**用我们自己的解析器验一遍**（不是"扩展名对就算"），
 *      验不过 ⇒ 删掉 `.part`，一个字节都不留；
 *   ② 就位时**先把旧的挪去中转站**（`&lt;hub&gt;/maps-trash/`），改名成功之后旧的那份还在中转站里
 *      ⇒ **挪不删**（中转站本身现在有页面了，见 {@link Trash} / {@link TrashActivity}）；
 *   ③ 中转站只留最近 {@link Trash#KEEP} 份（按名字里的时间戳排序删最旧）。
 * </pre>
 *
 * ⚠️ 只允许删 **槽的 `maps/` 目录下的直接子项**（{@link #deleteToTrash} 会验）——
 *    游戏自带/模组自带的地图**不在槽里**，不允许被"删"（它们属于那个包）。
 */
public final class MapFiles {
    /** 导入结果 */
    public static final class Result {
        public boolean ok;
        public String error;
        public File dest;
        public String finalName = "";
        public boolean overwrote;
        public long bytes;
        /**
         * 目标名在槽里**已经被占了**、而调用方没给 overwrite ⇒ 这一趟什么都没写，只是回来问一声。
         *
         * 🔴 为什么要这个字段（2026-10-04，i18n P0.1）：界面原来判的是
         *   `r.error.contains("已经有")` —— 拿 {@link #error} 里**文案**的一个子串当控制流判据。
         *   那句话一改措辞（更别说一翻译成英文），"同名替换"确认框就**再也不会弹**，
         *   而且不报错、不崩溃、没有任何自检会红。构建期门禁规则 `SRC-01` 就是钉这一类的。
         *   ⇒ **判"要不要弹替换框"只认这个字段**，不许再去看 {@link #error} 的字面内容。
         */
        public boolean nameTaken;
        /** 验过之后读出来的元数据（界面可以直接拿来显示"导入的是哪张图"） */
        public MsavMeta meta;
        /**
         * ★ **"这其实是一份存档"**（2026-10-05，REF §72）：meta 里没有 `name`。
         *
         * 判据与**游戏自己**同源 —— `SaveMeta.isMap()` 就是 `tags.containsKey("name")`：
         * 有名字 ⇒ 地图；没名字 ⇒ 存档。这一趟**什么都没落位**：`.part` 留在
         * {@link #part} 里等界面问完名字，再交给 {@link #commitSaveAsMap}；
         * 用户取消就交给 {@link #discard}。
         *
         * ⚠️ 界面判"要不要弹命名框"**只认这个字段**，不许去比 {@link #error} 的字面内容
         * （那是给人看的话，一改措辞/翻译就判错；门禁规则 SRC-01）。
         */
        public boolean saveNoName;
        /** 上面那份待办的 `.part`（只有 {@link #saveNoName} 为真时有意义） */
        public File part;
        /** 界面已确认的显示名（`saveNoName` 时 = 原文件名） */
        public String base = "";
        /** 转换失败时，内核给的「码 + 参数」（界面走 {@code MsavText.convertReason} 翻白话） */
        public SaveAsMap.Result conv;
    }

    private MapFiles() {}

    /** 中转站：`<hub>/maps-trash/`（实现与"模组那条线"统一在 {@link Trash}） */
    public static File trashDirOf(Context ctx) {
        return Trash.mapsDir(ctx);
    }

    /**
     * 文件名检查：必须是 `.msav`，且不含路径分隔符/冒号等。
     *
     * @return 错误文案（null = 通过）；文案走资源（见 `mapfile_err_name_*`）
     */
    public static String checkName(Context ctx, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return ctx.getString(R.string.mapfile_err_name_empty);
        }
        String n = displayName.trim();
        if (n.startsWith(".")) return ctx.getString(R.string.mapfile_err_name_dot);
        if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) {
            return ctx.getString(R.string.mapfile_err_name_ext);
        }
        if (n.contains("/") || n.contains("\\") || n.contains(":")) {
            return ctx.getString(R.string.mapfile_err_name_path);
        }
        if (n.length() > 120) return ctx.getString(R.string.mapfile_err_name_long);
        return null;
    }

    /**
     * 文件名安全化（**保留非 ASCII** —— 地图名就是用户看到的那个名字，见 {@link Msav} 同类注释）。
     */
    public static String safeName(String displayName) {
        String n = displayName == null ? "" : displayName.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) s = "map.msav";
        if (!s.toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) s = s + ".msav";
        return s;
    }

    /**
     * 导入一张地图（**先 `.part` + 验 + 再就位**）。
     *
     * @param overwrite true ⇒ 同名时把旧的挪去中转站再覆盖
     * @param trashDir  中转站（null ⇒ 那就**不允许覆盖**，宁可不做也不硬删）
     */
    public static Result importMap(Context ctx, File mapsDir, String displayName, InputStream in,
                                   boolean overwrite, File trashDir) {
        Result r = new Result();
        if (mapsDir == null) {
            r.error = ctx.getString(R.string.mapfile_err_no_dir);
            return r;
        }
        String nameErr = checkName(ctx, displayName);
        if (nameErr != null) {
            r.error = nameErr;
            return r;
        }
        final String name = safeName(displayName);
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            r.error = ctx.getString(R.string.mapfile_err_mkdir_fmt, mapsDir.getAbsolutePath());
            return r;
        }
        File dest = new File(mapsDir, name);
        if (dest.exists() && !(overwrite && trashDir != null)) {
            // ★ 判据字段（界面据此决定要不要弹"同名替换"框）；error 只是给人看的话
            r.nameTaken = true;
            r.error = ctx.getString(R.string.mapfile_err_name_taken_fmt, name);
            return r;
        }
        File part = new File(mapsDir, name + ".part");
        try {
            Data.deleteTree(part);                 // 上一轮中断留下的
            r.bytes = Util.copyStream(in, part);
            // ★ 判据：**我们自己的解析器**能不能读出元数据（不是看扩展名）
            MsavMeta meta = MsavMeta.read(part, true);
            if (!meta.ok) {
                Data.deleteTree(part);
                r.error = (meta.error == null || meta.error.isEmpty())
                        ? ctx.getString(R.string.mapfile_err_not_map)
                        : ctx.getString(R.string.mapfile_err_not_map_why_fmt, meta.error);
                return r;
            }
            r.meta = meta;
            // ★★ 缺 `name` ⇒ **这是存档，不是地图**（判据 = 游戏自己的 `SaveMeta.isMap()`）。
            //   ⇒ 这一趟**不落位**：`.part` 留着当"待办"，界面问完名字再走
            //   {@link #commitSaveAsMap}。⚠️ 这里返回时**绝不能**删那个 `.part`。
            if (!meta.has("name")) {
                r.saveNoName = true;
                r.part = part;
                r.base = name;
                r.finalName = name;
                return r;
            }
            r.overwrote = dest.exists();
            place(ctx, part, dest, trashDir);
            r.dest = dest;
            r.finalName = name;
            r.ok = true;
            return r;
        } catch (Throwable t) {
            Data.deleteTree(part);
            // ★ 2026-10-06：`place()` 抛出来的消息**本身就是给用户看的资源文案**
            //   ⇒ 不要把 `IllegalStateException:` 这种类名拼在前面（那是维护者视角）。
            //   ⚠️ 只有拿不到消息时才退回类名（那时的异常多半是 IO 层的，至少给个类别线索）。
            String m = t.getMessage();
            r.error = (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
            return r;
        }
    }

    /**
     * 「选源图」用的一项：本槽 `maps/` 里的一张图（文件 + 读出来的 meta）。
     * ★ 为什么要有：自动匹配（按 `mapname`）**改名或撞名时会失手** —— 用户 2026-10-05：
     *   「有可能会被改过名或不小心撞名，给个接口允许用户打开自行选择」。
     */
    public static final class Candidate {
        public final File file;
        public final MsavMeta meta;

        Candidate(File file, MsavMeta meta) {
            this.file = file;
            this.meta = meta;
        }

        /** 界面用：优先真名（去色码由调用方做），没有就退回文件名 */
        public String label() {
            String n = meta == null ? null : meta.get("name", null);
            if (n != null && !n.trim().isEmpty()) return n;
            return file.getName();
        }
    }

    /** 列出 `mapsDir` 里的地图（`.msav`；`.part` 这种半成品不算）；meta 读不出来也照样列 */
    public static java.util.List<Candidate> listMaps(File mapsDir) {
        java.util.ArrayList<Candidate> out = new java.util.ArrayList<>();
        File[] fs = mapsDir == null ? null : mapsDir.listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : fs) {
            if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) continue;
            out.add(new Candidate(f, MsavMeta.read(f, false)));
        }
        return out;
    }

    /**
     * **自动找源图**：判据与游戏自己同源 —— `SaveMeta` 显示"地图：X"用的就是
     * `maps.all().find(m -> m.name().equals(mapname))`，即**存档的 `mapname` == 那张图的 `name`**。
     *
     * @return 命中的**全部**（撞名时会多于一张 —— 界面据此提示"有几张同名、请自己选"）
     */
    public static java.util.List<Candidate> findSources(File mapsDir, String mapname) {
        java.util.ArrayList<Candidate> out = new java.util.ArrayList<>();
        if (mapname == null || mapname.trim().isEmpty()) return out;
        for (Candidate c : listMaps(mapsDir)) {
            if (c.meta != null && c.meta.ok && mapname.equals(c.meta.get("name", null))) out.add(c);
        }
        return out;
    }

    /**
     * ★★ 「**存档视为地图**」的第二步（2026-10-05，REF §72）：把待办的 `.part` 改写成地图再落位。
     *
     * <pre>
     *   ① SaveAsMap.convert(part → &lt;名&gt;.msav.conv.part)   ← 只改第 0 区（meta），区 1..n 逐字节搬
     *   ② 换掉那份"存档 .part"：conv → &lt;名&gt;.msav.part
     *   ③ 复验（MsavMeta.read(part2, true)）：必须读得出**而且现在有 name 了**
     *   ④ place()：旧图挪去中转站 → 改名就位
     *   ⑤ 成功之后**只删待办 `.part`**（{@link #isPendingPart}）—— 新入口传进来的可能是
     *      **用户的存档原件**，那东西一个字都不能动
     * </pre>
     *
     * ★ `part` 有**两种来源**（见 {@link #isPendingPart}）：
     *   · SAF 导入那条 ⇒ `&lt;名&gt;.msav.part`（我们的副本）
     *   · 「本槽存档 → 地图」那条 ⇒ 用户槽里的 `saves/x.msav`（只读它）
     *
     * ★ 这个 5 参重载 = **自动找源图**（按 `mapname`，见 {@link #findSources}）；
     *   界面里用户**自己选过**之后走下面那个带 `source` 的重载。
     *
     * @param metaName 用户确认的**地图显示名**（原样写进文件 meta，允许带色码 `[gold]…` ——
     *                 真地图 39/73 就是这么写的）；**文件名**另走 {@link #safeName} 消毒。
     * @param overwrite true ⇒ 同名时把旧的挪去中转站再覆盖
     * @param trashDir  中转站（null ⇒ 不允许覆盖）
     */
    public static Result commitSaveAsMap(Context ctx, File mapsDir, File part, String metaName,
                                         boolean overwrite, File trashDir) {
        // 自动：按存档的 mapname 找同名图；**撞名时取第一张**（界面会显示"有几张同名，点这里换"）
        File auto = null;
        try {
            MsavMeta sm = MsavMeta.read(part, false);
            java.util.List<Candidate> hits = findSources(mapsDir, sm.get("mapname", null));
            if (!hits.isEmpty()) auto = hits.get(0).file;
        } catch (Throwable ignored) {
        }
        return commitSaveAsMap(ctx, mapsDir, part, metaName, overwrite, trashDir, auto);
    }

    /**
     * 同上，但**源图由调用方指定**：`source == null` = **不用任何源图**（生成器设置按缺键兜底成 `[]`）。
     *
     * ★ 为什么要这个重载：自动匹配靠 `mapname`，**改名或撞名时会失手**；界面里那一行「源图：…」
     *   可以让用户自己挑（挑好后走这里），这样"图被改过名"也能照样补齐。
     * ⚠️ 只能给**槽里的文件**；游戏自带 / 模组自带的图在 APK / 模组包**里面**，走下面那个
     *   "直接给 tags" 的重载（界面里用的是那个，见 {@link Maps.Item#meta}）。
     */
    public static Result commitSaveAsMap(Context ctx, File mapsDir, File part, String metaName,
                                         boolean overwrite, File trashDir, File source) {
        java.util.Map<String, String> tags = null;
        String label = null;
        try {
            if (source != null && source.isFile()) {
                MsavMeta sm = MsavMeta.read(source, false);
                if (sm.ok) {
                    tags = sm.tags;
                    label = source.getName();
                }
            }
        } catch (Throwable ignored) {
        }
        return commitSaveAsMap(ctx, mapsDir, part, metaName, overwrite, trashDir, tags, label);
    }

    /**
     * ★★ **真正的实现**：源图用**已经读好的 meta 键值**表示 —— 这样"源图"可以是槽里的文件，
     * 也可以是**游戏自带**（APK 的 `assets/maps/**`）或**模组自带**（模组包 `maps/**`）里的一条
     * （由 {@link Maps#scan} 流式读出来，见 {@link Maps.Item#meta}）。
     *
     * @param inherit    源图的**全部** meta 键值（补哪些由 {@link SaveAsMap#NO_INHERIT} 判）；null = 不用源图
     * @param inheritFrom 写进报告/技术细节的短标签（文件名，或 `95.msav(游戏自带)` 这种）
     */
    public static Result commitSaveAsMap(Context ctx, File mapsDir, File part, String metaName,
                                         boolean overwrite, File trashDir,
                                         java.util.Map<String, String> inherit, String inheritFrom) {

        Result r = new Result();
        if (mapsDir == null) {
            r.error = ctx.getString(R.string.mapfile_err_no_dir);
            return r;
        }
        String nameErr = checkMapName(ctx, metaName);
        if (nameErr != null) {
            r.error = nameErr;
            return r;
        }
        if (part == null || !part.isFile()) {
            r.error = ctx.getString(R.string.mapfile_err_part_gone);
            return r;
        }
        // 🔴 `importMap` 那条路进到这里时 maps/ 一定已经建好了（`.part` 就写在里面），
        //   而「本槽存档 → 地图」那条路进来的槽**可能一张图都还没有、maps/ 根本不存在**
        //   ⇒ 不建目录的话 `SaveAsMap.convert` 写不出来，报的却是"读取失败"（真机实测，2026-10-05）。
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            r.error = ctx.getString(R.string.mapfile_err_mkdir_fmt, mapsDir.getAbsolutePath());
            return r;
        }
        final String file = safeName(metaName);
        final String display = metaName.trim();
        r.base = file;
        File dest = new File(mapsDir, file);
        if (dest.exists() && !(overwrite && trashDir != null)) {
            // ★ 判据字段（界面据此决定弹"同名"框）；error 只是给人看的话
            r.nameTaken = true;
            r.error = ctx.getString(R.string.mapfile_err_name_taken_fmt, file);
            return r;
        }
        File conv = new File(mapsDir, file + ".conv.part");   // `.part` 结尾 ⇒ 不算槽内容
        File part2 = new File(mapsDir, file + ".part");
        try {
            Data.deleteTree(conv);
            // ★ 源图（用户可选 / 自动匹配的结果）：把**缺的**那些 meta 键按它补齐。
            //   ⚠️ 必须在写 `conv` **之前**用：目标名可能与源图同名（就是同一张图）。
            //   ⚠️ 补哪些/不补哪些的判据在 `SaveAsMap`（NO_INHERIT 一处），这里只负责把键值递过去 ——
            //      源图可能是槽里的文件、APK 里的条目、模组包里的条目，**这里只看 tags**
            SaveAsMap.Result c = SaveAsMap.convert(part, conv, display,
                    inherit == null || inherit.isEmpty() ? null : inherit);
            if (inherit != null && !inherit.isEmpty() && inheritFrom != null) {
                c.inheritFrom = inheritFrom;   // 报告里要说清是照哪张图补的
            }
            r.conv = c;
            if (!c.ok) {
                Data.deleteTree(conv);
                r.error = c.report();                         // 维护者原文；用户文案走 MsavText
                return r;
            }
            Data.deleteTree(part2);
            if (!conv.renameTo(part2)) {
                Data.deleteTree(conv);
                r.error = ctx.getString(R.string.mapfile_err_rename_fmt, part2.getAbsolutePath());
                return r;
            }
            MsavMeta meta = MsavMeta.read(part2, true);
            if (!meta.ok || !meta.has("name")) {
                Data.deleteTree(part2);
                r.error = (meta.error == null || meta.error.isEmpty())
                        ? ctx.getString(R.string.mapfile_err_not_map)
                        : ctx.getString(R.string.mapfile_err_not_map_why_fmt, meta.error);
                return r;
            }
            r.meta = meta;
            r.overwrote = dest.exists();
            place(ctx, part2, dest, trashDir);
            if (isPendingPart(part)) Data.deleteTree(part);   // 🔴 只删我们自己的待办 `.part`
            r.dest = dest;
            r.finalName = file;
            r.ok = true;
            return r;
        } catch (Throwable t) {
            Data.deleteTree(conv);
            Data.deleteTree(part2);
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    /**
     * **地图显示名**的检查（写进文件 meta 的那个名字）。
     *
     * ⚠️ 与 {@link #checkName} 分工不同：那个查的是**文件名**（必须 `.msav` 结尾、不许路径字符）；
     *   显示名允许空格/色码/中文，只要求非空 + 不超长，文件名另外过 {@link #safeName}。
     */
    public static String checkMapName(Context ctx, String display) {
        if (display == null || display.trim().isEmpty()) {
            return ctx.getString(R.string.mapfile_err_name_empty);
        }
        if (display.trim().length() > 120) return ctx.getString(R.string.mapfile_err_name_long);
        return null;
    }

    /** 放弃一份待办的 `.part`（用户在命名框里取消 / 页面销毁）。 */
    public static void discard(File part) {
        // 🔴 **只删待办 `.part`**：见 {@link #isPendingPart} —— 新入口（本槽存档 → 地图）传进来的
        //   是**用户的存档原件**，这里若照删就是把存档弄丢，而且不报错。
        if (isPendingPart(part)) Data.deleteTree(part);
    }

    /**
     * 🔴 **它是不是"我们自己的待办 `.part`"**（2026-10-05，REF §72.10 的红线）。
     *
     * 「存档视为地图」有**两个**入口，传进 {@link #commitSaveAsMap} 的东西不一样：
     * <pre>
     *   ① 选文件（SAF）那条：传进来的是 {@link #importMap} 落下的 `&lt;名&gt;.msav.part`
     *      —— 那是**我们的副本**，用完该删；
     *   ② 「本槽存档 → 地图」那条：传进来的是 **用户槽里的存档原件**（`&lt;槽&gt;/saves/x.msav`）
     *      —— **一个字都不能删**（源存档是用户的东西，我们只是读它做一份新图）。
     * </pre>
     * ⇒ 判据收在**这一处**、并且只看名字后缀：`.part` = 我们的待办、其它 = 用户的东西。
     *   两条删除路径（{@link #commitSaveAsMap} 收尾 / {@link #discard}）都走它。
     */
    public static boolean isPendingPart(File f) {
        return f != null && f.getName().endsWith(".part");
    }

    /**
     * 就位：**先把旧的挪去中转站**，再把 `.part` 改名成目标名。
     * 改名失败 ⇒ 把旧的从中转站搬回来（宁可回到原状，也不留一个空位）。
     *
     * ⚠️ 异常消息**也走资源**：`importMap` 的 catch 会把它拼成「类名: 消息」直接给用户看
     *   （见 {@link Result#error}），所以它同样是**用户可见文案**。
     */
    static void place(Context ctx, File part, File dest, File trashDir) throws Exception {
        File backup = null;
        if (dest.exists()) {
            if (trashDir == null) {
                throw new IllegalStateException(ctx.getString(R.string.mapfile_err_no_trash));
            }
            if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) {
                throw new IllegalStateException(
                        ctx.getString(R.string.mapfile_err_trash_mkdir_fmt, trashDir.getAbsolutePath()));
            }
            // ★ 中转站里的名字带**来源槽**（v2，见 Trash.nameFor）—— 恢复时才能默认放回原槽
            backup = new File(trashDir,
                    Trash.nameFor(Trash.slotLabel(dest.getParentFile()), dest.getName()));
            // 🔴 用 `Trash.move`，**不要**裸 `renameTo`（2026-10-06 对齐纪律）：
            //   中转站与槽**可能不在一个卷上**（`renameTo` 在不同文件系统上必失败），
            //   而它失败得很安静 —— 用户看到的就是"覆盖不了 / 删不掉"。
            //   `Trash.move` = renameTo 失败即退化成"复制 + 校验长度 + **才**删源"。
            //   ★ 蓝图那条线一直这么做、模组那条线内联了同一套；只有地图这条线原来是裸的。
            if (!Trash.move(dest, backup)) {
                throw new IllegalStateException(ctx.getString(R.string.mapfile_err_trash_move));
            }
        }
        if (!Trash.move(part, dest)) {
            // 🔴 回滚**必须判结果**（原来那句 `backup.renameTo(dest)` 是裸的、返回值没人看）：
            //   回滚失败 ⇒ 旧图此刻躺在中转站里、目标位置是空的 ⇒ 必须**如实告诉用户旧的在哪**，
            //   否则用户以为图丢了（其实在「存档与备份 → 中转站」里能捞回来）。
            if (backup != null && !Trash.move(backup, dest)) {
                throw new IllegalStateException(ctx.getString(R.string.mapfile_err_rename_stash_fmt,
                        dest.getAbsolutePath(), trashDir == null ? "" : trashDir.getAbsolutePath()));
            }
            throw new IllegalStateException(
                    ctx.getString(R.string.mapfile_err_rename_fmt, dest.getAbsolutePath()));
        }
        if (backup != null) Trash.prune(trashDir, Trash.KEEP);
    }

    /**
     * 删除一张地图 —— **挪去中转站，不硬删**。
     *
     * 🔴 安全判据（两条都过才动）：
     *  ① 它必须是 {@code mapsDir} 的**直接子项**（游戏自带/模组自带的地图不在槽里，删不动）；
     *  ② 游戏进程没在跑（与改 `settings.bin` 同一条门禁，避免跟游戏抢文件）。
     *
     * @return 成功返回中转站里的那份；否则 null
     */
    public static File deleteToTrash(Context ctx, File mapsDir, File mapFile, File trashDir) {
        if (mapsDir == null || mapFile == null || trashDir == null) return null;
        if (Data.gameAlive(ctx)) return null;                      // 游戏在跑：不动
        try {
            if (!mapsDir.getCanonicalFile().equals(mapFile.getParentFile().getCanonicalFile())) {
                return null;                                       // 不是这个目录的直接子项
            }
        } catch (Throwable t) {
            return null;
        }
        if (!mapFile.isFile()) return null;
        if (!trashDir.isDirectory() && !trashDir.mkdirs() && !trashDir.isDirectory()) return null;
        File moved = new File(trashDir,
                Trash.nameFor(Trash.slotLabel(mapsDir), mapFile.getName()));
        // 🔴 同样用 `Trash.move`（跨卷兜底）——见 {@link #place} 里的长注释
        if (!Trash.move(mapFile, moved)) return null;
        Trash.prune(trashDir, Trash.KEEP);
        return moved;
    }
}
