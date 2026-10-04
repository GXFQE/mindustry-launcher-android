package io.mdt.launcher;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * M3 自检（开发用，只在 debuggable 构建里可由 adb 触发）。
 *
 * 为什么要有它：M3 的核心承诺是"**不丢数据**"，而这只能靠"破坏 → 恢复 → 逐字节比对"
 * 来证明 —— 光看界面点得动不算证据。但真去改用户的存档来验证是不可接受的，
 * 所以这里全程在**专用测试槽**（{@link #SLOT}）上做，用假数据当被试，
 * 用户的槽与数据根一个字节都不碰。
 *
 * 用例（每条都是可证伪的断言，不看界面）：
 *   ① 数据根体检（只读）—— 报告冗余副本 / 证据不足项；
 *   ② 槽的建 / 改名 / 删（含目录真的动过、备份目录跟着改名）；
 *   ③ **备份口径 = 「槽内容 − 排除名单」**（F19，以前是白名单）：模组乱放在数据根
 *      **顶层**的目录（`logic-tool/`）必须进对象池；`cache/`、`config.json`、
 *      写一半的 `*.part`、`.` 开头的都必须不进 —— **正反两侧都要断言**，
 *      只验"该进的进了"测不出"该拦的拦不住"；
 *   ④ ★ 破坏 → 恢复 → **md5 与破坏前逐一相同**（核心承诺）；
 *   ⑤ ★★ CAS 去重：内容没变时再备一份**一个新对象都不写**，池实际占用 &lt; 逻辑总量；
 *   ⑥ ★ 宽限期内 GC 不删新对象（防误删的保险）；
 *   ⑦ ★ GC 的**删除**分支：过期的孤儿真被删（含空分片目录），被引用的对象一个不动；
 *   ⑧ 旧格式（v1）快照自动迁移成 v2，**迁移后恢复出来的内容逐字节一致**；
 *   ⑨ `.msav` 管线：gzip 判据命中 / 不命中，落点在 `saves/`；
 *   ⑩ 负例：对象池里的对象被破坏 ⇒ **这一项拒写**、其余照常恢复（无半截恢复）。
 *   ⑪ ★ 兼容性探测（F18）：ELF 的 **32/64 位偏移口径** / 页对齐判据 / 字节搜索 /
 *      真机样本（本应用 APK 必须判为"不可进管线"）。纯函数 + 只读，执行时排在 ⑩ 之前。
 *   ⑫ ★ 日志导出（F20）：三个源**逐段**的独有内容都要出现在导出里、导出**不截断**
 *      （页面只留 400 行、导出走全文）、缺失的源写出「缺失」—— 并带**元断言**
 *      （故意漏一段必须判死），否则那几条 `contains` 可能是恒真的装饰。
 *   ⑬ ★ F0 数据隔离模型：三个路径角色真的分开（槽**不在** `files/` 里）、
 *      `dirOf` 的三条分支真有分辨力、`legacyClaim` 对"`files/` 有没有内容"敏感，
 *      以及一条**核心不变式**（`dirOf` 指向 `files/` ⟺ 该槽是 `shared` 或标记认下的本体）。
 *      这些是"存档不会看起来没了"的防线；全部只读 + 纯函数，只在 `app_hub/` 里开临时目录。
 *   ⑭ ★ F4② 克隆槽：**克隆出来的槽与源槽逐字节相同**（口径必须与备份**同一份**：
 *      只有"该进备份的"进新槽，`cache/`/`config.json`/`*.part`/点文件一个都不许跟着走），
 *      并且**源槽整棵树在克隆后一个字节没变**；另配三条负例（目标已存在 / 目标是当前槽 /
 *      源槽是空的 ⇒ 都必须报错，且前两条**不许写坏任何东西**）。
 *   ⑮ ★ F13 模组扫描（**只读**）：三块 ——
 *      ① **纯函数**：去色 + internalName（settings 键名的由来）、`Version.isAtLeast` 的边界、
 *         HJSON 两档语义（159/160 **必须分家**：注释/括号是否终止无引号值）；
 *      ② **settings.bin 读**：六种类型 + 非 ASCII 键 + zlib 变体，外加三条负例
 *         （尾部多一字节 / count=0 / 未知类型 ⇒ 必须判失败 —— 否则「恰好 EOF」只是装饰）；
 *      ③ **扫描端到端**：测试槽里铺 6 种形态（根级 jar / 套一层目录的 zip / 目录形态 /
 *         坏 meta / 同名冲突 / 无 meta 目录）+ settings.bin + launchid.dat，
 *         断言"坏 meta 被报出来而不是静默消失"、"键不存在 = 启用"、"依赖被关闭要连坐"，
 *         并配一条**元断言**（同一个 launchid.dat 下改 `modcrashdisable` 必须改变结论）。
 *   ⑯ ★ F13 第二阶段**启停**（`settings.bin` 的安全改写，全工程第一条会写游戏数据的路径）：
 *      ① 纯函数往返：手拼夹具「读 → 编码」**逐字节一致**（含 binary / 非 ASCII 键 / zlib 变体）；
 *      ② **真机上任意一个真实 `settings.bin`**：读 → 编码 → 与磁盘原文逐字节比对（**只读**，
 *         这条是"我们的编码器 == 游戏的写侧"最硬的证据，不需要改任何用户数据）；
 *      ③ 改一个键：备份存在且与改前 md5 相同、长度不变、**只差 1 字节**、读回值对、其余键逐条一致；
 *         另验"**新键追加到末尾**"（位置是判据的一部分 —— 挪了位置内容不变但文件整片不同）；
 *      ④ 负例：坏文件 ⇒ **拒绝改写**、文件一个字节没动、**不产生备份**；
 *      ⑤ 元断言：`sameValues` 对"改值 / 少键 / 换类型"必须判死，对"完全相同的两份"必须判活
 *         （否则"写后自检"就是个恒真的装饰）。
 *   ⑰ ★ F13 第三阶段**导入 / 跨槽复制**：合法包能进（落点 md5 与源相同、无 `.part` 残留）；
 *      五个负例必须被判死（无 meta / 坏 meta / 扩展名不对 / 文件名带冒号 / 同名不覆盖）；
 *      同名 + 明确覆盖 ⇒ 替换成功且**旧的那份被挪去 `hub/mods-trash/`**（挪不删）；
 *      跨槽复制：只复制顶层条目、跳过 `.part`、目标已存在则跳过（不覆盖）；
 *      外加一条**元断言**（把合法包的 meta 拿掉 ⇒ 必须判非法，证明第一条不是恒真）。
 *
 * ⚠️ 顺序有讲究：⑩ 会在池里留一个坏对象（CAS 假设"池内不可变"）⇒ 必须排在最后。
 *
 * 报告同时写 `&lt;hub&gt;/report-m3.txt`，便于 adb 直接取回。
 */
public final class SelfTest {
    private static final String TAG = "MDTLauncher";

    /** 测试槽名（不会与用户的槽重名 —— 每次跑之前都会先清干净） */
    public static final String SLOT = "m3-selftest";
    public static final String SLOT_RENAMED = "m3-renamed";
    /** ⑭ 克隆用例的槽：源 / 克隆出来的目标 / 空源（负例）/ 空源那次的目标 */
    public static final String CLONE_SRC = "m3-clone-src";
    public static final String CLONE_DST = "m3-clone-dst";
    public static final String CLONE_EMPTY = "m3-clone-empty";
    public static final String CLONE_EMPTY_DST = "m3-clone-empty-dst";
    /** ⑮ F13：模组扫描用的测试槽（铺 6 种形态的包 + settings.bin + launchid.dat） */
    public static final String SLOT_MODS = "m3-mods";
    /** ⑯ F13 第二阶段：`settings.bin` 安全改写用的测试槽 */
    public static final String SLOT_SET = "m3-settings";
    /** ⑰ F13 第三阶段：模组包导入 / 跨槽复制 —— 源槽与目标槽 */
    public static final String SLOT_PACK = "m3-modpack";
    public static final String SLOT_PACK_DST = "m3-modpack-dst";

    /**
     * 自检会创建并最终删除的**全部**测试槽。
     * ★ 收在两处（跑前清场 / 跑后收尾）**共用这一份** —— 加一个新测试槽时只改这里，
     *   不会再出现"新加的槽忘了清"这种只在真机上慢慢攒垃圾的漏（原来两处各写一份字面量）。
     */
    private static final String[] TEST_SLOTS = {
            SLOT, SLOT_RENAMED, CLONE_SRC, CLONE_DST, CLONE_EMPTY, CLONE_EMPTY_DST, SLOT_MODS,
            SLOT_SET, SLOT_PACK, SLOT_PACK_DST};

    /**
     * 本工程**自己的全部页面**（㊱ 基类检查用）。
     *
     * ⚠️ 新增页面时必须加进来 —— 名单漏了不会有任何症状（这正是 ㊱ 想防的那类漏）。
     * ⚠️ 与 ㉙ 的清单名单（`activityRegistration` 里那份 `String[] want`）**是两件事**：
     *   那边管"清单里注册了没有"，这边管"类继承对了没有"，判据不同，不能合并。
     */
    private static final Class<?>[] PAGES = {
            MainActivity.class, SavesActivity.class, SlotActivity.class, ModsActivity.class,
            MapsActivity.class, MapDetailActivity.class, SettingsActivity.class,
            LogActivity.class, GameSlot.class};

    private SelfTest() {}

    // ── 主入口 ────────────────────────────────────────────────────────────

    public static String runM3(Context passed) {
        // ★★ 自检的语言**必须与用户的界面语言设置解耦**（P2 之后用户能自己切语言）。
        //    本套断言的期望值是照**中文资源**写死的（"· 两侧要有空格"、"中文与插入值之间无多余空格"…），
        //    一旦用户的设置是英文、自检却跟着走，就会红一片 —— 那不是回归，是它本来就这么写的。
        //    ⇒ 一进来就 force() 到 LocaleMode.SELFTEST，让结论**确定性、可复现**。
        //    ⚠️ 这是过渡：真正的做法是让那些断言与语言无关 / zh-en 双跑，见 docs/i18n-feasibility.md §7.3。
        //    ⚠️ 只改 Configuration，不碰 Locale.setDefault（红线，见 LocaleMode 类注释）。
        final Context ctx = LocaleMode.force(passed, LocaleMode.SELFTEST);
        List<String> L = new ArrayList<>();
        int[] stat = {0, 0};   // {pass, fail}
        long t0 = System.currentTimeMillis();
        L.add("MDT M3 自检   " + new java.util.Date().toString());
        L.add("数据根 = " + path(Data.dataRoot(ctx)));
        L.add("HUB 外部 = " + path(Data.hubDir(ctx)));
        L.add("当前槽 = " + Data.currentSlot(ctx));
        // ★ 把"自检跑在哪个语言下"写进报告：它是复现结论的必要信息
        //   （同一份代码在 zh / en 下跑出来**不是**同一个数字，见 §7.3 的实测）。
        L.add("自检语言 = " + LocaleMode.SELFTEST + "（锁定，与界面语言设置无关）");
        L.add("");

        // 先清场：上一轮跑挂了也不能污染这一轮
        try {
            for (String s : TEST_SLOTS) {
                Backup.deleteSlotBackups(ctx, s);
                Data.deleteSlot(ctx, s);
            }
        } catch (Throwable ignored) {
        }

        try {
            health(ctx, L, stat);
            slotLifecycle(ctx, L, stat);
            backupAndRestore(ctx, L, stat);
            casPool(ctx, L, stat);
            savedFormula(L, stat);
            versionLabel(ctx, L, stat);
            conflictLabel(ctx, L, stat);
            conflictGroup(ctx, L, stat);
            upstreamCompat(ctx, L, stat);
            slotLineSpacing(ctx, L, stat);
            logExport(ctx, L, stat);
            casGc(ctx, L, stat);
            legacyMigration(ctx, L, stat);
            msavPipeline(ctx, L, stat);
            // ★ 纯函数 + 只读样本，无副作用 ⇒ 排在 ⑩ 之前（⑩ 必须最后）
            compatProbe(ctx, L, stat);
            // ★ ⑬ F0：路径解析 / "本体住哪"判定（只读现有布局 + app_hub 里一个临时目录）
            dataDirModel(ctx, L, stat);
            // ★ ⑭ F4②：克隆槽 —— 自建一对一次性槽，逐字节比对（自带源槽，不依赖别的用例的残留）
            cloneSlot(ctx, L, stat);
            // ★ ⑮ F13：模组扫描（HJSON 两档 / settings.bin / 扫描与判定）—— **只读**，
            //   只在自己建的测试槽里铺数据（SLOT_MODS 已进 TEST_SLOTS，收尾统一删）
            modsPipeline(ctx, L, stat);
            // ★ ⑯ F13 第二阶段：启停 —— settings.bin 的**安全改写**（备份 / 原子写 / 写后自检 / 还原）
            settingsWrite(ctx, L, stat);
            // ★ ⑰ F13 第三阶段：模组包导入 / 跨槽复制（五个负例 + 一条元断言）
            modsPack(ctx, L, stat);
            // ★ ⑱ F13 第四阶段：模组间冲突体检（内置检测 / 共同全局钩子 / 简介提及 + 元断言）
            modsConflict(ctx, L, stat);
            // ★ ⑲ F13 第二批：搜索/筛选/排序（纯函数，可单独喂断言）
            modsFilter(L, stat);
            modsFilter(L, stat);
            // ★ ⑳ F13 第二批：批量启停的写侧（一次读写 / noop 不动文件 / 坏文件拒绝写）
            modsBatchWrite(ctx, L, stat);
            // ★ ㉑ F13 第二批：反向依赖（禁用前的提醒）
            modsDependents(L, stat);
            // ★ ㉒ F10：.msav 元数据解析（现场造文件 + 两个口径的反向断言）
            msavParse(ctx, L, stat);
            // ★ ㉓ F10：快照列表里的「这是什么存档」（v1 真文件 + 反向）
            msavSnapshot(ctx, L, stat);
            // ㉔ F10：地图清点（三个来源）
            mapsScan(ctx, L, stat);
            // ★ ㉕ 数据根残留：自动清理的**策略边界**（只删有正证据的）
            healthPolicy(ctx, L, stat);
            // ★ ㉖ 点开地图的"详细信息"文案
            mapDetail(ctx, L, stat);
            // ★ ㉗ F10 预览：配色表 / 渲染 / 负例
            mapPreview(ctx, L, stat);
            // ★ ㉙ 清单注册检查（新增页面必须注册，否则 BUILD OK 但真机崩）
            activityRegistration(ctx, L, stat);
            // ★ ㊱ 基类检查：每个页面都必须继承 BaseActivity（否则深浅色设置对它无效）
            activityThemeBase(ctx, L, stat);
            // ★ ㊲ 主进程崩溃落盘（真写一份探针报告再删掉）
            crashDump(ctx, L, stat);
            // ★ ㉘ F10 地图增删：导入（先 part+验）与删除（挪不删）
            mapCrud(ctx, L, stat);
            // ★ ㉚ F10 地图导出：包内条目流式拷出（字节一致 + 两向反向 + 元断言）
            mapExport(ctx, L, stat);
            // ★ ㉛ 页面骨架：根节点必须带 @id/root（补 insets 的落点）+ 地图页两行都在顶上
            pageSkeleton(ctx, L, stat);
            // ★ ㉜ F21 地图资源统计：纯函数 + 现场造的小地图（不碰磁盘、不依赖别的用例）
            mapStats(ctx, L, stat);
            // ★ ㉝ 槽内存档体检：哪几份读不出来（槽页副标题与其落点弹窗共用的那份判据）
            savesHealth(ctx, L, stat);
            // ★ ㉞ 文案边界：资源首尾空白会被 aapt2 剥掉（拼串留空格必粘）—— 纯资源实拼，只读
            textEdgeWhitespace(ctx, L, stat);
            // ★ ㉟ 模组详情首层那句「简介」（纯函数：subtitle 优先 / description 第一行 / 截断 / 空）
            modTagline(L, stat);
            // ★ 篡改对象池的用例放**最后**：它会在池里留下一个内容坏掉的对象，
            //   之后任何"再备份一次"都会因为 `has()` 命中而复用坏对象（CAS 的固有
            //   假设是"池内不可变"）。放在最后就不影响别的用例。
            tamperNegative(ctx, L, stat);
        } catch (Throwable t) {
            stat[1]++;
            L.add("❌ 自检自身异常：" + t);
            android.util.Log.e(TAG, "selftest crashed", t);
        }

        // 收尾：测试槽与它的备份全部清掉
        String[] leftover = TEST_SLOTS;
        int cleaned = 0;
        for (String s : leftover) {
            Backup.deleteSlotBackups(ctx, s);
            if (Data.deleteSlot(ctx, s) == null) cleaned++;
        }
        L.add("");
        L.add(cleaned + " 个测试槽已清理（含其备份）");
        L.add("用时 " + (System.currentTimeMillis() - t0) + " ms");

        // ★★ 汇总必须放在【最前】（F17c，需求原文：「**在开头显示下总体情况，出问题了再翻**」）。
        //   报告有几百行，而弹窗（AlertDialog）一打开只显示开头几屏 ⇒ 汇总原先在**末尾**时，
        //   判读"到底过没过"要往下滚 5 屏（真机实测，正是用户吐槽的点）。
        //   现在结论落在**第一屏**；万一有失败，失败项也一并提到汇总正下方，一眼可见。
        //   ⚠️ 别再把它挪回末尾 —— 那样就退回"要靠滚动才发现失败"。
        String summary = "===== 通过 " + stat[0] + " 项，失败 " + stat[1] + " 项 =====";
        List<String> fails = new ArrayList<>();
        for (String s : L) {
            if (s.trim().startsWith("❌")) fails.add(s);
        }

        List<String> out = new ArrayList<>();
        out.add(L.get(0));            // 「MDT M3 自检   <日期>」
        out.add("");
        out.add(summary);
        if (fails.isEmpty()) {
            out.add("无失败项。下方依次是环境信息、逐项明细、清理与用时。");
        } else {
            out.add("❌ 失败项共 " + fails.size() + " 条（明细里同样可见，不必翻）：");
            out.addAll(fails);
        }
        out.add("");
        out.addAll(L.subList(1, L.size()));

        String report = join(out);
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), "report-m3.txt"), report);
        } catch (Exception e) {
            android.util.Log.w(TAG, "write report failed: " + e);
        }
        android.util.Log.i(TAG, "M3 selftest done: pass=" + stat[0] + " fail=" + stat[1]);
        return report;
    }

    /** 只做体检并落报告（清理由用户/单独一步决定） */
    public static String runHealthReport(Context ctx, boolean alsoClean) {
        List<String> L = new ArrayList<>();
        Data.Health h = Data.healthScan(ctx);
        L.add("数据根体检   " + new java.util.Date().toString());
        L.add("数据根 = " + path(Data.dataRoot(ctx)));
        L.add("当前槽 = " + Data.currentSlot(ctx));
        L.add("");
        // ★ 结论前置（F17c，与自检报告同源问题）：体检报告也走 AlertDialog，一打开只有开头几屏。
        //   把"有多少要处理的"放第一屏，后面的分节明细才是"要细看再往下翻"。
        L.add("结论：冗余副本 " + h.redundant.size() + " 项（" + Util.formatSize(h.redundantBytes())
                + "，可清理）；证据不足 " + h.suspicious.size() + " 项（只报告，不清理）");
        L.add("");
        L.add("【冗余副本（有正证据，可清理）】" + h.redundant.size() + " 项，合计 "
                + Util.formatSize(h.redundantBytes()));
        for (Data.Finding f : h.redundant) {
            L.add("  · " + f.where + " / " + f.path.getName() + "   "
                    + Util.formatSize(f.bytes) + "   " + f.files + " 文件");
            L.add("      证据：" + f.evidence);
            L.add("      路径：" + f.path.getAbsolutePath());
        }
        L.add("");
        L.add("【证据不足（只报告，不清理）】" + h.suspicious.size() + " 项");
        for (Data.Finding f : h.suspicious) {
            L.add("  · " + f.where + " / " + f.path.getName() + "   "
                    + Util.formatSize(f.bytes));
            L.add("      " + f.evidence);
        }
        if (alsoClean) {
            L.add("");
            L.add("【执行清理】");
            L.add(Data.cleanRedundant(ctx, h));
        }
        String report = join(L);
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), "report-health.txt"), report);
        } catch (Exception e) {
            android.util.Log.w(TAG, "write health report failed: " + e);
        }
        return report;
    }

    // ── 各用例 ────────────────────────────────────────────────────────────

    private static void health(Context ctx, List<String> L, int[] stat) {
        L.add("── ① 数据根体检（只读） ──");
        Data.Health h = Data.healthScan(ctx);
        boolean okRun = true;
        L.add("  冗余副本 " + h.redundant.size() + " 项 / 证据不足 " + h.suspicious.size() + " 项"
                + "（合计可释放 " + Util.formatSize(h.redundantBytes()) + "）");
        // 有历史残留时，必须至少有一项被正证据命中，否则说明判据太松或太严
        File root = Data.dataRoot(ctx);
        boolean hasResidue = root != null && (new File(root, "import").exists()
                || new File(root, "natives").exists()
                || new File(root, "config.json").exists());
        if (hasResidue && h.redundant.isEmpty()) {
            okRun = false;
            L.add("  ⚠ 数据根里名字命中的项存在，却没有一项拿到正证据 —— 判据可能过严");
        }
        ok(stat, L, okRun, "体检可跑通且判据自洽（只读，未删任何东西）");
        L.add("");
    }

    private static void slotLifecycle(Context ctx, List<String> L, int[] stat) {
        L.add("── ② 槽的建 / 改名 / 删 ──");
        String e1 = Data.createSlot(ctx, SLOT);
        ok(stat, L, e1 == null, "新建槽「" + SLOT + "」" + (e1 == null ? "" : "：" + e1));
        File d = Data.slotDir(ctx, SLOT);
        ok(stat, L, d != null && d.isDirectory(), "槽目录已落盘：" + path(d));

        // 名字合法性（负例）
        ok(stat, L, Data.createSlot(ctx, "shared") != null, "拒绝保留名 shared");
        ok(stat, L, Data.createSlot(ctx, "slot-x") != null, "拒绝 slot- 前缀");
        ok(stat, L, Data.createSlot(ctx, "") != null, "拒绝空名");

        // 当前槽不可删（负例）—— 用当前槽名去删必须失败
        String cur = Data.currentSlot(ctx);
        String curName = Data.sanitizeSlot(cur);
        if (curName != null) {
            ok(stat, L, Data.deleteSlot(ctx, cur) != null, "拒绝删除当前槽「" + cur + "」");
        }

        // 改名
        String e2 = Data.renameSlot(ctx, SLOT, SLOT_RENAMED);
        ok(stat, L, e2 == null, "改名 " + SLOT + " → " + SLOT_RENAMED + (e2 == null ? "" : "：" + e2));
        File d2 = Data.slotDir(ctx, SLOT_RENAMED);
        ok(stat, L, d2 != null && d2.isDirectory() && (d == null || !d.exists()),
                "改名后旧目录消失、新目录存在");
        // 恢复原名（后面的用例按 SLOT 找），顺便再验一次改名幂等
        ok(stat, L, Data.renameSlot(ctx, SLOT_RENAMED, SLOT) == null, "改回原名");
        L.add("");
    }

    private static void backupAndRestore(Context ctx, List<String> L, int[] stat)
            throws IOException {
        L.add("── ③④ 槽内容口径（F19：全部 − 排除）+ 破坏→恢复（M3 的核心承诺） ──");
        File slot = Data.slotDir(ctx, SLOT);
        // ★ 每次跑都用**当轮唯一**的内容。对象池是**跨轮持久**的（这是它的全部价值），
        //   所以若用固定内容，第二次跑自检时这些对象已经在池里 ⇒
        //   "首次入库写出 0 B" 会**假失败** —— 那其实是 CAS 的正常行为，不是 bug。
        //   （踩过一次：59 通过 / 3 失败，其中 2 项都是池里的历史遗留对象导致的。见 REF §32。）
        String nonce = Long.toHexString(System.currentTimeMillis());
        // 假数据分三类（F19 起"白名单"这个词已经不存在了，这里改用**口径**表述）：
        //   【A】该进备份：常规存档 + 模组乱放在数据根顶层的用户数据
        //   【B】该被排除：顶层排除名单（cache/、config.json）
        //   【C】该被排除：通用跳过项（`.part` 半成品、`.` 开头）
        try {
            // 【A】
            write(new File(slot, "saves/a.msav"), gzip(("fake-save-A-" + nonce).getBytes("UTF-8")));
            write(new File(slot, "saves/b.msav"), gzip(("fake-save-B-" + nonce).getBytes("UTF-8")));
            write(new File(slot, "settings.bin"), ("settings-" + nonce).getBytes("UTF-8"));
            write(new File(slot, "mods/test.jar"), ("not-a-real-jar-" + nonce).getBytes("UTF-8"));
            // ★★ 本轮的主角：模组把**用户创作**放在数据根顶层、且**不在任何白名单里**。
            //    实证来源：逻辑工具 mod 的 `logic-tool/history/*.json`（33 个 JSON、
            //    纯用户创作、丢了不可重建）。F19 前它**不会进备份**，而备份看着是成功的。
            write(new File(slot, "logic-tool/history/project-1.json"),
                    ("{\"logic\":\"" + nonce + "\"}").getBytes("UTF-8"));
            write(new File(slot, "previews/p.png"), ("fake-preview-" + nonce).getBytes("UTF-8"));
            // 【B】
            write(new File(slot, "cache/junk.bin"), ("cache-junk-" + nonce).getBytes("UTF-8"));
            write(new File(slot, "config.json"), ("{\"config_version\":1}").getBytes("UTF-8"));
            // 【C】
            write(new File(slot, "saves/half.msav.part"), ("HALF-" + nonce).getBytes("UTF-8"));
            write(new File(slot, ".hidden/secret.bin"), ("dot-" + nonce).getBytes("UTF-8"));
            L.add("  假数据已铺（带本轮 nonce " + nonce + "）：");
            L.add("    【该进】saves/a.msav, saves/b.msav, settings.bin, mods/test.jar,"
                    + " logic-tool/history/project-1.json, previews/p.png");
            L.add("    【不该进】cache/junk.bin, config.json（顶层排除名单）"
                    + " ; saves/half.msav.part, .hidden/secret.bin（通用跳过）");
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ 铺假数据失败：" + e);
            return;
        }

        String md5a = Util.md5(new File(slot, "saves/a.msav"));
        String md5b = Util.md5(new File(slot, "saves/b.msav"));
        String md5s = Util.md5(new File(slot, "settings.bin"));
        String md5l = Util.md5(new File(slot, "logic-tool/history/project-1.json"));

        // 清单里的 rel 必须**正好**是这些（相对槽根、/ 分隔）。
        // ★ 这条断言是 F19 的结构性守卫：以前 `Backup.rel()` 少了 `Exporter.relOf` 那句
        //   "顶层兜底"，顶层条目的 rel 会变成**空串** ⇒ 拼出 `"settings.bin/"`（带尾斜杠），
        //   靠 `new File(root, "x/")` 丢掉尾部斜杠**侥幸能用**、毫无症状。
        java.util.Set<String> want = new java.util.HashSet<>();
        want.add("saves/a.msav");
        want.add("saves/b.msav");
        want.add("settings.bin");
        want.add("mods/test.jar");
        want.add("logic-tool/history/project-1.json");
        want.add("previews/p.png");

        Backup.Snapshot ss = null;
        try {
            ss = Backup.create(ctx, SLOT, "selftest");
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ 备份失败：" + e);
            return;
        }
        ok(stat, L, ss.count == want.size(),
                "快照收了 " + want.size() + " 个文件，实为 " + ss.count);
        // ★ F16：CAS 格式 —— 快照目录里**不该**再有 files/，内容进共享对象池
        ok(stat, L, ss.version == 2, "清单是 CAS 格式（mdt-backup 2）");
        ok(stat, L, !new File(ss.dir, "files").exists(), "快照里没有 files/（内容已进对象池）");
        ok(stat, L, ss.storedNew == ss.bytes,
                "首次入库写出 " + Util.formatSize(ss.storedNew) + " = 逻辑量 "
                + Util.formatSize(ss.bytes) + "（" + ss.count + " 个文件内容互不相同 ⇒ 全部是新增）");

        List<String> rels = manifestRels(ss.dir);
        java.util.Set<String> relSet = new java.util.HashSet<>(rels);
        L.add("      清单 rel(" + rels.size() + ") = " + rels);
        ok(stat, L, relSet.equals(want),
                "清单条目集合 = 预期（多/少都算失败）；缺=" + minus(want, relSet)
                        + " 多=" + minus(relSet, want));
        ok(stat, L, relSet.contains("settings.bin"),
                "★ 顶层文件 settings.bin 的 rel 就是 \"settings.bin\"（不是空串、不带尾斜杠）");
        boolean shapeOk = true;
        for (String r : rels) {
            if (r.isEmpty() || r.endsWith("/") || r.startsWith("/")
                    || r.contains("//") || r.contains("\\") || r.contains(":")) shapeOk = false;
        }
        ok(stat, L, shapeOk, "每个 rel 都是「非空 · 相对 · / 分隔 · 无尾斜杠」（守卫 rel() 顶层兜底）");

        Cas pool0 = new Cas(Backup.casBaseDir(ctx));
        String shaA = pool0.sha256Of(new File(slot, "saves/a.msav"));
        ok(stat, L, pool0.has(shaA), "saves/a.msav 的对象已在池里（sha256 即地址）");
        // ★★ F19 的核心断言：模组乱放的顶层数据**必须**进池。
        ok(stat, L, pool0.has(pool0.sha256Of(new File(slot, "logic-tool/history/project-1.json"))),
                "★★ 模组乱放在数据根顶层的 logic-tool/history/project-1.json 进池了（F19 之前会漏）");
        ok(stat, L, pool0.has(pool0.sha256Of(new File(slot, "previews/p.png"))),
                "previews/ 也进了池（口径 = 全部 − 排除，不再是白名单）");
        // 反面：三类"不该进"的必须一个都没进（否则断言没有分辨力）
        ok(stat, L, !pool0.has(pool0.sha256Of(new File(slot, "cache/junk.bin")))
                        && !pool0.has(pool0.sha256Of(new File(slot, "config.json"))),
                "顶层排除名单里的 cache/ 与 config.json 都没进池");
        ok(stat, L, !pool0.has(pool0.sha256Of(new File(slot, "saves/half.msav.part")))
                        && !pool0.has(pool0.sha256Of(new File(slot, ".hidden/secret.bin"))),
                "写一半的 .part 与 .hidden/ 下的东西都没进池（通用跳过生效）");

        // ★ 破坏：改内容 + 删文件（含刚加进来的顶层模组数据）
        try {
            write(new File(slot, "saves/a.msav"), gzip("CORRUPTED".getBytes("UTF-8")));
            new File(slot, "settings.bin").delete();
            new File(slot, "mods/test.jar").delete();
            new File(slot, "logic-tool/history/project-1.json").delete();
        } catch (Exception e) {
            L.add("  ⚠ 破坏步骤异常：" + e);
        }
        boolean corrupted = !Util.md5(new File(slot, "saves/a.msav")).equals(md5a)
                && !new File(slot, "settings.bin").exists()
                && !new File(slot, "logic-tool/history/project-1.json").exists();
        ok(stat, L, corrupted,
                "破坏已生效（a.msav 变了、settings.bin 与 logic-tool 的 JSON 都没了）");

        Backup.RestoreResult rr = Backup.restore(ctx, SLOT, ss);
        ok(stat, L, rr.ok, "恢复返回成功");
        ok(stat, L, Util.md5(new File(slot, "saves/a.msav")).equals(md5a),
                "★ 恢复后 a.msav md5 与破坏前相同");
        ok(stat, L, Util.md5(new File(slot, "saves/b.msav")).equals(md5b), "b.msav md5 未变");
        ok(stat, L, new File(slot, "settings.bin").isFile()
                        && Util.md5(new File(slot, "settings.bin")).equals(md5s),
                "★ 被删的 settings.bin 回来了且 md5 相同（顶层文件的 rel 拼得对）");
        ok(stat, L, new File(slot, "mods/test.jar").isFile(), "被删的 mods/test.jar 回来了");
        ok(stat, L, new File(slot, "logic-tool/history/project-1.json").isFile()
                        && Util.md5(new File(slot, "logic-tool/history/project-1.json")).equals(md5l),
                "★★ 模组乱放的那个 JSON 回来了且 md5 相同（两层目录也被自动建出来）");
        ok(stat, L, new File(slot, "cache/junk.bin").isFile()
                        && read(new File(slot, "cache/junk.bin")).endsWith(nonce),
                "被排除的 cache/junk.bin 没被动过（仍在且内容未变）");

        // 备份目录跟着槽改名走
        String e = Data.renameSlot(ctx, SLOT, SLOT_RENAMED);
        if (e == null) {
            Backup.renameSlotBackups(ctx, SLOT, SLOT_RENAMED);
            File moved = new File(Backup.rootDir(ctx), SLOT_RENAMED);
            ok(stat, L, moved.isDirectory()
                            && !Backup.list(ctx, SLOT_RENAMED).isEmpty(),
                    "槽改名后备份目录跟着搬了（" + Backup.list(ctx, SLOT_RENAMED).size() + " 份快照）");
            Data.renameSlot(ctx, SLOT_RENAMED, SLOT);
            Backup.renameSlotBackups(ctx, SLOT_RENAMED, SLOT);
        } else {
            stat[1]++;
            L.add("❌ 备份场景里的槽改名失败：" + e);
        }
        L.add("");
    }

    /** a 里有、b 里没有的项（断言失败时把差集打出来，免得靠猜） */
    private static String minus(java.util.Set<String> a, java.util.Set<String> b) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) {
            if (!b.contains(s)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(s);
            }
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /**
     * 读一份快照清单的文件表，返回 rel 列（第 3 列）。
     * ⚠️ 这里**故意硬编码 "manifest.txt"** —— 它是**磁盘格式的一部分**
     *   （见 {@link Backup} 类头的"目录布局"），不是内部实现细节；
     *   真要改这个名字，这条断言失败正是我们想要的提醒。
     */
    private static List<String> manifestRels(File snapDir) {
        List<String> out = new ArrayList<>();
        String text = read(new File(snapDir, "manifest.txt"));
        boolean inTable = false;
        for (String line : text.split("\n")) {
            if (!inTable) {
                if ("---".equals(line)) inTable = true;
                continue;
            }
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            out.add(p[2]);
        }
        return out;
    }

    private static void tamperNegative(Context ctx, List<String> L, int[] stat)
            throws IOException {
        L.add("── ⑩ 负例：对象池里的内容被破坏 ⇒ 这一项必须拒绝写 ──");
        List<Backup.Snapshot> snaps = Backup.list(ctx, SLOT);
        if (snaps.isEmpty()) {
            stat[1]++;
            L.add("❌ 找不到可用快照");
            return;
        }
        Backup.Snapshot ss = snaps.get(0);
        Cas pool = new Cas(Backup.casBaseDir(ctx));
        File target = new File(Data.slotDir(ctx, SLOT), "saves/b.msav");
        if (!target.isFile()) {
            stat[1]++;
            L.add("❌ 找不到被试文件 " + target.getAbsolutePath());
            return;
        }
        // 池里的地址就是"正常内容"的哈希 —— 照它去改对象本体
        String sha = pool.sha256Of(target);
        File victim = pool.objectPath(sha);
        if (!victim.isFile()) {
            stat[1]++;
            L.add("❌ 槽里的 b.msav 在池里没有对应对象：" + sha);
            return;
        }
        // ★★ 篡改前必须先自证这个对象**此刻是好的**。
        //   本用例的篡改方式是"翻转中间一个字节"（`^= 0x5a`）—— 这是一个**对合运算**：
        //   同一个字节翻两次 = 原值。而对象池是跨轮持久的，如果上一轮自检已经把
        //   **同一个对象**（内容固定 ⇒ 同一个 sha）翻过一次，本轮再翻 = 把它翻回正确内容
        //   ⇒ 恢复反而成功、后面几条断言全部"通过" —— **静默假阳性**，比失败更糟。
        //   （真踩过：一轮 59/3 里的一项就是它。见 REF §32。）
        //   顺带这也再次说明池的假设是"池内不可变"：put() 只查存在性、不校验内容。
        String liveHash = pool.sha256Of(victim);
        ok(stat, L, liveHash.equals(sha),
                "篡改前对象内容与地址一致（对象此刻有效）"
                + (liveHash.equals(sha) ? "" : "，实为 " + liveHash.substring(0, 12) + "…"));
        if (!liveHash.equals(sha)) {
            stat[1]++;
            L.add("❌ 对象本来就是坏的 ⇒ 本次翻转会把它翻回正确内容，负例测不到（跳过）");
            L.add("");
            return;
        }
        // ★ 篡改必须**保持长度不变**：否则池的 `has()` 检查、大小校验都可能先拦下来，
        //    "sha256 与地址不符"这条分支就永远验不到（上一版自检漏过一次同类问题）。
        String md5Before = Util.md5(target);
        String sizeBefore = String.valueOf(victim.length());
        try {
            byte[] data = new byte[(int) victim.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(victim);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            data[data.length / 2] ^= 0x5a;      // 翻转一个字节：长度不变、sha256 必变
            write(victim, data);
        } catch (Exception ex) {
            stat[1]++;
            L.add("❌ 篡改失败：" + ex);
            return;
        }
        ok(stat, L, String.valueOf(victim.length()).equals(sizeBefore), "篡改后长度未变（只翻了一个字节）");

        Backup.RestoreResult rr = Backup.restore(ctx, SLOT, ss);
        // ★ 把被断言的原文打出来：下面还有"报告里必须出现某句话"的断言，
        //   若不显示原文，一旦断言失败就只能靠猜（踩过一次）。
        L.add("      恢复报告：" + oneLine(rr.report));
        // ★ F16 语义（与旧版不同，是有意改的）：对象池是不可变的，**坏一项不影响别的项**
        //   ⇒ 不再"整份拒绝"（那会让用户一个文件都拿不回来），而是**这一项不写出去**、
        //     其余照常恢复，并在报告里点名。
        // ★ P0.1：成败改读 rr.ok（原来读报告开头的 ✅ —— 那是文案，本地化后会把成功显示成"未执行"）
        ok(stat, L, rr.ok, "其余项照常恢复（rr.ok = true，只有坏的那一项失败）");
        ok(stat, L, rr.report.contains("sha256"), "报告点明对象内容与地址（sha256）不符");
        ok(stat, L, Util.md5(target).equals(md5Before),
                "★ 坏对象**没有被写出去**（b.msav 一个字节都没动，没有半截恢复）");
        L.add("");
    }

    /**
     * ⑤ CAS 对象池的核心承诺：**内容重复 ⇒ 不重复占空间**。
     *
     * 这一条是整个改造的价值所在（桌面端实测 27 份备份省 93.7%）——
     * 所以必须能被证伪地测出来，而不是靠"看起来变小了"。
     *
     * ★ 断言一律用**增量**（造第二份快照前后池占用的差），不用绝对量。
     *   绝对量必然被**历史遗留对象**污染：池是跨轮持久的，上一轮自检、
     *   上一批 cas-a/cas-b 实验留下的对象还在池里（未过宽限期时连 `scan` 都不算它们
     *   是孤儿）⇒ "池实占 ＜ 逻辑总量"这种写法会假失败。增量才是真不变量。
     */
    private static void casPool(Context ctx, List<String> L, int[] stat)
            throws IOException {
        L.add("── ⑤ CAS 对象池：去重 / 回收 ──");
        File slot = Data.slotDir(ctx, SLOT);
        Cas pool = new Cas(Backup.casBaseDir(ctx));

        long poolBefore = pool.scan(Backup.allReferencedShas(ctx)).bytes;
        Backup.Snapshot ss2;
        try {
            ss2 = Backup.create(ctx, SLOT, "cas-dedup");
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ 第二份备份失败：" + e);
            return;
        }
        long poolAfter = pool.scan(Backup.allReferencedShas(ctx)).bytes;

        ok(stat, L, !new File(ss2.dir, "files").exists(), "第二份快照同样没有 files/");
        ok(stat, L, ss2.storedNew == 0,
                "★★ 内容没变 ⇒ 第二份**一个新对象都没写**（storedNew=0）"
                + " ⇒ 增量成本 ≈ 0（这份快照逻辑上有 " + Util.formatSize(ss2.bytes) + "）");
        ok(stat, L, poolAfter == poolBefore,
                "★★ 对象池占用**一个字节都没涨**（" + Util.formatSize(poolBefore)
                + " → " + Util.formatSize(poolAfter) + "）—— 这是去重的硬证据");

        Backup.PoolStats st = Backup.poolStats(ctx);
        ok(stat, L, st.objects > 0, "池里 " + st.objects + " 个对象 / " + Util.formatSize(st.actual));
        ok(stat, L, st.legacySnapshots == 0, "此刻没有旧格式快照（都在 CAS 里）");
        // 全局节省率只作信息展示、**不作断言**：池里有历史遗留对象时它必然偏低，
        // 那是正确的（那些对象此刻确实占着空间）。公式本身由 savedFormula() 单独钉住。
        L.add("    （信息）全局体检：逻辑 " + Util.formatSize(st.logical) + " / 池实占 "
                + Util.formatSize(st.actual) + " ⇒ 节省 "
                + String.format(java.util.Locale.US, "%.1f%%", st.saved() * 100)
                + "（含池里未回收的历史遗留对象）");

        // ★ 防误删：宽限期内（600 s）的对象**一个都不许删** —— 这正是
        //   「对象已落盘、清单还没写」那个空档的保险。
        //   ⚠️ 不能断言"本次删除数 == 0"：池是跨轮持久的，里面可能躺着**别的**已经过期的
        //      孤儿（上一轮实验留下的），GC 收掉它们是**正确行为**。
        //      要断言的是"年龄不够的一个没动"。这一条被污染过一次（连跑两轮：第 1 轮
        //      失败/第 2 轮通过，因为过期孤儿只在第一轮存在）。见 REF §32。
        List<File> young = youngObjects(pool);
        int removed = Backup.gcNow(ctx);
        int keptYoung = 0;
        for (File f : young) if (f.isFile()) keptYoung++;
        ok(stat, L, keptYoung == young.size(),
                "★ 宽限期（600 s）内的对象一个都没被删：" + keptYoung + " / " + young.size()
                + "（本次共删 " + removed + " 个，只该是过期孤儿）");
        ok(stat, L, Util.md5(new File(slot, "saves/a.msav")).length() == 32,
                "GC 跑完槽里的存档仍在（没被误伤）");
        L.add("");
    }

    /**
     * ⑥ 体检报告的**节省率公式**（纯算数，不碰盘）。
     *
     * 为什么值得单独立一条：这个公式曾经写反
     * （`1 - (logical - actual)/logical` ⇒ 得到的是"占比"而不是"节省率"），
     * 真机上 74.4% 被报成 25.6%。⚠️ 而**自检用的小数据同样测不出来** ——
     * 因为那个 bug 只有在"池里对象多、逻辑量大"时数值上才明显。
     * 既然真机数据不可复现地构造，就把公式本身钉死：合成数字 + 精确断言。
     */
    private static void savedFormula(List<String> L, int[] stat) {
        L.add("── ⑥ 节省率公式（合成数字，钉死那个写反过的 bug） ──");
        Backup.PoolStats a = new Backup.PoolStats();
        a.logical = 100; a.actual = 25;
        ok(stat, L, a.saved() == 0.75d,
                "逻辑 100 / 实占 25 ⇒ 75%（曾写反成 25%）实为 "
                + String.format(java.util.Locale.US, "%.0f%%", a.saved() * 100));

        Backup.PoolStats b = new Backup.PoolStats();
        b.logical = 100; b.actual = 25; b.orphanBytes = 20;
        ok(stat, L, b.saved() == 0.95d,
                "孤儿不计入实占：逻辑 100 / 实占 25-20=5 ⇒ 95%");

        Backup.PoolStats c = new Backup.PoolStats();
        c.logical = 100; c.actual = 120;
        ok(stat, L, c.saved() == 0d, "实占超过逻辑量时夹到 0（不出现负数）");

        Backup.PoolStats d = new Backup.PoolStats();
        ok(stat, L, d.saved() == 0d, "逻辑量为 0 时不除零");
        L.add("");
    }

    /**
     * ⑥b 版本号显示规则（纯函数）：剥掉 APK 里那层构建前缀。
     *
     * 为什么单独立一条：这是个**正则**，而且它改的是**用户直接看到的字符串** ——
     * 正则最容易在"随手改一点"时变成误伤：把 `1-2-3` 这类真版本号当成前缀剥掉，
     * 界面上版本号就凭空少一截，而**除了这条用例，没有任何机制会发现**（不崩、不报错）。
     * ⇒ 正例（该剥的剥干净）与负例（不该剥的**一个都不许动**）都要钉死。
     *
     * ★ 同时钉住"不许硬编码 `8-official`"：换渠道 / 换构建序号（`9-beta-`）也必须成立，
     *   否则游戏一发新版本就悄悄退回"版本号带前缀"的老样子。
     *
     * ⚠️ 正则规则刻意收紧（第一段纯数字 + 第二段以字母开头），就是为了让下面
     *   `1-2-3` / `2.1.0-rc-3` 这两条负例永远成立 —— **宁可漏剥也不能误剥**。
     *
     * ★ F17b 扩展成三段：
     *   ⑥b-1 构造用例（剥/不剥）；
     *   ⑥b-2 **裸串入口** `Versions.displayVersionOf()` —— 没有 Entry 的地方（导入完成
     *        Toast）走这条，两条入口必须同源；
     *   ⑥b-3 **真机登记数据回归** —— 用设备上 config.json 里的真实 `version` 原文，
     *        断言界面值已剥干净，并**逐字复刻** Toast 的构造方式打出一条真文案。
     *        这一段是"用户报的那个漏网点真的修好了"的直接证据。
     */
    private static void versionLabel(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑥b 版本号显示（剥构建前缀，且不许误剥） ──");

        String[][] cases = {
            // {APK 里的原文,             界面期望显示,    说明}
            {"8-official-159.7",        "159.7",         "官方 Mindustry（真机实测原文）"},
            {"8-official-2026.09.X37",  "2026.09.X37",   "MindustryX fork（真机实测原文）"},
            {"9-beta-160.1",            "160.1",         "★ 换渠道/序号也成立（不许硬编码 8-official）"},
            {"159.7",                   "159.7",         "本来就没前缀 ⇒ 原样"},
            {"2026.09.X37",             "2026.09.X37",   "带点号的版本号不许被当成前缀"},
            {"1-2-3",                   "1-2-3",         "★ 负例：第二段不以字母开头 ⇒ 不许剥"},
            {"2.1.0-rc-3",              "2.1.0-rc-3",    "负例：第一段必须纯数字，`2.1.0` 不是"},
        };
        for (String[] c : cases) {
            Versions.Entry e = new Versions.Entry();
            e.label = "L";
            e.versionName = c[0];
            String got = e.displayVersion();
            ok(stat, L, c[1].equals(got), c[0] + " → " + got + "（" + c[2] + "）");
        }

        Versions.Entry nul = new Versions.Entry();
        nul.label = "L";
        nul.versionName = null;
        ok(stat, L, "?".equals(nul.displayVersion()), "versionName 为 null ⇒ 退回 ?（不崩）");

        // ★ 统一显示名：列表行 / 「继续上次」/ 详情标题**共用同一个串**
        Versions.Entry nm = new Versions.Entry();
        nm.label = "MindustryX";
        nm.versionName = "8-official-2026.09.X37";
        ok(stat, L, "MindustryX  2026.09.X37".equals(nm.displayName()),
                "displayName = `名称  版本号`：" + nm.displayName());
        ok(stat, L, nm.versionTrimmed(), "剥过前缀 ⇒ 详情弹窗要露出原文");

        Versions.Entry im = new Versions.Entry();
        im.label = "official-159";
        im.versionName = "8-official-159.7";
        im.imported = true;
        ok(stat, L, "official-159  159.7".equals(im.displayName()),
                "导入项显示名 = `名称  版本号`，**不带**（导入）：" + im.displayName());

        // ★ F17c：导入身份**不进主标题**（用户：「那个（导入）看起来很奇怪，放下面去」）。
        //   旧实现 `title()=displayName()+"（导入）"` 在竖屏把标题挤成 `…159.7（导` + `入）`，
        //   而副标题本来就有 `已导入 · ` 前缀 ⇒ 上下重复。标记已统一交给副标题。
        //   判据用"同样数据、只差 imported 位"的两个 Entry 比 —— 比断具体文案更本质。
        Versions.Entry imPlain = new Versions.Entry();
        imPlain.label = "official-159";
        imPlain.versionName = "8-official-159.7";
        ok(stat, L, imPlain.displayName().equals(im.displayName()),
                "imported 位**不影响**显示名（身份只在副标题）：" + im.displayName());
        // ⚠️ 断言盯"该消失的那个具体标记"，不要写 !contains("导入") ——
        //   名称里合法带"导入"二字（如 label 就叫「导入版」）会把好数据判成坏（F17b 同型坑）。
        //   这里 label 固定为 `official-159`（不含该串），所以本判据是安全的。
        ok(stat, L, !im.displayName().contains("（导入）"),
                "主标题不含「（导入）」后缀");

        Versions.Entry raw = new Versions.Entry();
        raw.label = "Mindustry";
        raw.versionName = "159.7";
        ok(stat, L, !raw.versionTrimmed(), "没剥过 ⇒ 详情弹窗不出现多余的「原始版本名」行");

        // ── ⑥b-2 裸串入口（F17b）─────────────────────────────────────────
        //   手里**只有原始版本串、没有 Entry** 的地方走这条（导入完成 Toast 就是）。
        //   ⚠️ 它和 Entry.displayVersion() 必须是**同一段实现** ——
        //     否则又会冒出"同一个版本两种叫法"（F17b 修的就是这个）。
        L.add("  裸串入口 Versions.displayVersionOf（无 Entry 时用）：");
        ok(stat, L, "159.7".equals(Versions.displayVersionOf("8-official-159.7")),
                "displayVersionOf(\"8-official-159.7\") = 159.7");
        ok(stat, L, "2026.09.X37".equals(Versions.displayVersionOf("8-official-2026.09.X37")),
                "displayVersionOf(导入项真机原文) = 2026.09.X37");
        ok(stat, L, "?".equals(Versions.displayVersionOf(null)), "null ⇒ ?（不崩）");
        ok(stat, L, "?".equals(Versions.displayVersionOf("")), "空串 ⇒ ?");
        ok(stat, L, "?".equals(Versions.displayVersionOf("?")), "`?` ⇒ `?`（原样，不变成空）");
        ok(stat, L, "1-2-3".equals(Versions.displayVersionOf("1-2-3")), "负例仍不剥");

        // ★ 同源：两条入口逐字一致（薄包装不许走样）
        Versions.Entry same = new Versions.Entry();
        same.versionName = "8-official-2026.09.X37";
        ok(stat, L, same.displayVersion().equals(Versions.displayVersionOf(same.versionName)),
                "两条入口同源：Entry.displayVersion() == displayVersionOf(versionName)");

        // ── ⑥b-3 真机登记数据回归（本轮修的就是这条链）────────────────────
        //   config.json 的 `version` 是 Importer 写进去的**原文**（形如 8-official-159.7），
        //   界面上一个都不许带前缀露出来。
        //   这一段用的是**设备上的真实登记数据**，不是构造用例；它同时就是
        //   「导入完成 Toast 会显示什么」的直接证据 —— Toast 的文案走的是
        //   `import_done_fmt(label, displayVersionOf(version))`，与列表行**同一段实现**。
        org.json.JSONArray imps = Config.get().imports();
        int checked = 0;
        String toastSample = null;
        String toastRaw = null;     // 该条登记项的**原文**（剥掉的那层就是它的一部分）
        for (int i = 0; i < imps.length(); i++) {
            org.json.JSONObject o = imps.optJSONObject(i);
            if (o == null) continue;
            String rv = o.optString("version", "").trim();
            if (rv.isEmpty()) continue;
            checked++;
            String label = o.optString("label", "?");
            String shown = Versions.displayVersionOf(rv);
            L.add("    登记项 " + label + "：version=" + rv + " → 界面 " + shown);
            // 幂等判据：显示值里**不许再有可剥的前缀**
            //（有 = 还带 `N-name-` 的壳；没有 = 剥干净了）
            ok(stat, L, !Versions.versionTrimmedOf(shown),
                    "真实登记项的显示值已剥干净：" + rv + " → " + shown);
            if (toastSample == null) {
                // ★ 逐字复刻 MainActivity 里那条 Toast 的构造方式
                toastSample = ctx.getString(R.string.import_done_fmt, label, shown);
                toastRaw = rv;
            }
        }
        if (checked == 0) {
            L.add("    （设备上暂无导入项 —— 这一段空过，不算失败）");
        } else {
            L.add("    已核对 " + checked + " 条真实登记项");
            // ★ 判据必须精确到"**被剥掉的那层前缀**不许出现在文案里"，
            //   不能写成 `!toast.contains("official-")` —— 会误伤 **label**：
            //   这台设备上的导入项 label 恰好就叫 `official-159`（外来的 APK 文件名），
            //   于是那一版断言把好数据判成了失败（真实踩到，见 REF §35.11）。
            //   ⚠️ 教训：断言要盯**被剥掉的东西**，别盯一个恰好在别处也出现的子串。
            ok(stat, L, toastSample != null && !toastSample.contains(toastRaw),
                    "导入完成 Toast 文案（真机数据实拼，且不含被剥掉的原文）：" + toastSample);
        }
        L.add("");
    }

    /**
     * ⑥c 同槽冲突提示的"对手描述"（F17c，纯函数）。
     *
     * 为什么单独立一条：这段文案的**书名号闭合位置**极易写错 —— 若把书名号留在
     * `row_conflict_fmt` 里，而 desc 又自带一对，"等 N 个版本"就会落到书名号外面，
     * 拼出 `⚠ 与「「A」等 3 个版本」共用此槽…`（多一层书名号）。
     * ⚠️ 这种错**界面照样显示、不崩不报错**，只有人眼能发现 ⇒ 必须用断言钉住。
     *
     * 判据用**结构**（书名号几个、有没有"等 N 个"），不逐字比对整句 ——
     * 文案还会改，但"2 个点一个对手、3 个及以上说数量"这个**行为**不该变。
     *
     * ★ F20 起，"说出来的数量"是 **`total - 1`**（除自己之外的版本数），不是 total。
     *   所以这里除了形态断言，还加了一条**直接抽数字比对**的硬断言
     *   （`等 (N) 个版本` 里的 N 必须 == total-1，且 total 本身不许出现在文案里）——
     *   这一条才是"数量算上自己"这个具体错误的判据，形态断言抓不到它。
     */
    private static void conflictLabel(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑥c 同槽冲突提示（对手描述） ──");

        // ① 纯函数本身：2 个点一个对手；3 个及以上把**数量**说出来
        ok(stat, L, "「A」".equals(Versions.conflictPeerDesc(ctx, "A", 2)),
                "2 个版本 ⇒ 只点一个对手：" + Versions.conflictPeerDesc(ctx, "A", 2));
        ok(stat, L, "「A」等 2 个版本".equals(Versions.conflictPeerDesc(ctx, "A", 3)),
                "3 个版本 ⇒ 说「等 2 个」（除自己之外）：" + Versions.conflictPeerDesc(ctx, "A", 3));
        ok(stat, L, "「A」等 3 个版本".equals(Versions.conflictPeerDesc(ctx, "A", 4)),
                "4 个版本 ⇒ 数量跟着走：" + Versions.conflictPeerDesc(ctx, "A", 4));

        // ② ★★ 硬断言：**说出来的数 == total - 1**，且 total 本身绝不出现。
        //    只验形态（"有没有等 N 个"）测不出"N 到底是几" —— 这正是本轮改的那个错。
        boolean peerOk = true;
        StringBuilder peerSeen = new StringBuilder();
        for (int total = 3; total <= 6; total++) {
            String d = Versions.conflictPeerDesc(ctx, "A", total);
            int got = shownPeerCount(d);
            if (got != total - 1) peerOk = false;
            if (d.contains(String.valueOf(total))) peerOk = false;   // 总数（含自己）不许露面
            peerSeen.append("  total=").append(total).append(" ⇒ 显示 ").append(got);
        }
        ok(stat, L, peerOk, "显示的版本数 = 总数 − 1（不含自己）" + peerSeen);

        // ★ 元断言：把**旧写法**（3 个版本时写"等 3 个版本"，即数量含自己）喂给同一个判据，
        //   它必须判死 —— 否则上面那条"永远通过、无法证伪"（F17d 的教训：看着有自检、
        //   其实没验，比没有更危险）。
        ok(stat, L, shownPeerCount("「A」等 3 个版本") != 3 - 1,
                "元断言：旧写法（3 个版本写「等 3 个」）会被判死 —— 判据不是恒真");

        // ③ 与 fmt 实拼后的形态
        String c2 = ctx.getString(R.string.row_conflict_fmt, Versions.conflictPeerDesc(ctx, "A", 2));
        String c3 = ctx.getString(R.string.row_conflict_fmt, Versions.conflictPeerDesc(ctx, "A", 3));
        L.add("    2 个版本 ⇒ " + c2);
        L.add("    3 个版本 ⇒ " + c3);
        ok(stat, L, c2.contains("「A」") && !c2.contains("等 "),
                "2 个版本的提示里不出现「等 N 个」");
        ok(stat, L, c3.contains("「A」等 2 个版本"),
                "3 个版本的提示形如「「A」等 2 个版本」（除自己之外）");

        // ④ ★ 本段存在的理由：**右书名号恰好一个**。
        //    fmt 与 desc 各带一个 ⇒ 会拼出两层书名号（不崩、只是难看），这条专抓它。
        //    ⚠️ 判据锚定"数量"，不锚定具体位置 —— 别写成 `contains("「「")` 之类，
        //      那只能抓一种错法，而"数量 != 1"能抓全部。
        ok(stat, L, countOf(c2, '」') == 1, "2 个版本：右书名号恰好 1 个");
        ok(stat, L, countOf(c3, '」') == 1, "3 个版本：右书名号恰好 1 个");
        ok(stat, L, countOf(c2, '「') == 1 && countOf(c3, '「') == 1,
                "左书名号也是恰好 1 个（fmt 与 desc 不许各带一对）");

        // ⑤ ★ 中文串接处**不许有多余空格**（F17c 真实踩到）。
        //   把 fmt 写成 `⚠ 与 %1$s 共用…`（%1$s 两侧带空格）在中文里是错的 ——
        //   真机渲染成 `⚠ 与 「X」等 3 个版本 共用此槽…`，"与 「" 和 "版本 共" 两处都空着。
        //   ⚠️ 这种错**界面照样显示、不崩不报错**，纯靠人眼；而且它是我在"给插入值加个
        //      分隔更清楚"的直觉下写出来的 —— 中文没有这种需求。
        ok(stat, L, !hasCjkSpace(c2), "2 个版本：中文与插入值之间无多余空格");
        ok(stat, L, !hasCjkSpace(c3), "3 个版本：「等 N 个版本」两侧紧贴中文");
        // ★ **判据自证** —— 拿"我真实写错过的那一行"喂给它，必须被判死。
        //   ⚠️ 没有这一条，上面两条可能是恒真的装饰（判据写错方向、或永远返回 false），
        //      而那正是"看着有自检、其实什么都没验"的经典形态（见 REF §35.11 的断言踩坑）。
        ok(stat, L, hasCjkSpace("⚠ 与 「A」等 3 个版本 共用此槽，存档会互相覆盖"),
                "元断言：带空格的错误文案会被判死（证明该判据有分辨力）");
        ok(stat, L, !hasCjkSpace("⚠ 与「A」等 3 个版本共用此槽，存档会互相覆盖"),
                "元断言：正确文案不被误杀（判据不是恒真）");
        L.add("");
    }

    /**
     * ⑥d 同槽分组里「谁才算对手」（F4①b，纯函数 + 元断言）。
     *
     * 口径的由来：用户看到"同一个版本号的两个条目共用槽"也被提示 ——
     * 「**同一个版本号共用一个槽挺正常的，没必要提示啊（反正也不会有兼容问题）**」。
     * 判据因此从"组内 ≥2 个条目"改成"**组内存在不同的版本号**"。
     *
     * ★★ 为什么必须有 ②（元断言）：① 验的是"该为空的确实空了"，而**一个恒返回空的
     *   坏实现能让它全过**。所以同时喂一个"只差一位版本号"的对照，要求它**判死** ——
     *   两个输入只差一个字符、结论相反，才能证明这把尺子量得出东西（REF §35.13）。
     * ★ ① 还顺手证伪了"拿原始 `versionName` 比"的实现（`8-official-159.7` ≠ `159.7`
     *   会让同一版本漏判成冲突）—— 一句话钉住两件事。
     */
    private static void conflictGroup(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑥d 同槽冲突：谁才算对手（版本号不同才算） ──");

        // ① 同一个游戏的多个副本（官方 APK 装了一份 + 又导入了一份）⇒ 不算冲突。
        //    两种写法（原始串 / 已剥前缀）必须视为同一个版本 ⇒ 这也钉住了 displayVersion 口径。
        List<Versions.Entry> same = groupOf("8-official-159.7", "159.7");
        ok(stat, L, Versions.conflictPeers(same.get(0), same).isEmpty(),
                "同版本号的副本共用 ⇒ 无对手（装了又导入同一个 APK，不该提示）");
        ok(stat, L, Versions.conflictPeers(same.get(1), same).isEmpty(),
                "两个方向都为空（判据与「谁是组内第几个」无关）");

        // ② ★★ 元断言：只差一位版本号 ⇒ 必须判死
        List<Versions.Entry> diff = groupOf("159.7", "159.8");
        ok(stat, L, Versions.conflictPeers(diff.get(0), diff).size() == 1,
                "★元断言：159.7 vs 159.8（只差一位）⇒ 必须算冲突（证明 ① 不是恒空）");

        // ③ 同版本的多个副本只算**一个**对手 —— 否则文案会把副本数当成版本数虚报
        List<Versions.Entry> mix = groupOf("159.7", "159.7", "146");
        List<Versions.Entry> p0 = Versions.conflictPeers(mix.get(0), mix);
        List<Versions.Entry> p2 = Versions.conflictPeers(mix.get(2), mix);
        ok(stat, L, p0.size() == 1 && "146".equals(p0.get(0).displayVersion()),
                "159.7×2 + 146 ⇒ 对 159.7 而言对手只有 146 一个");
        ok(stat, L, p2.size() == 1,
                "对 146 而言两个 159.7 副本折成 1 个对手");
        String c = ctx.getString(R.string.row_conflict_fmt,
                Versions.conflictPeerDesc(ctx, p2.get(0).displayName(), p2.size() + 1));
        L.add("    实拼 ⇒ " + c);
        ok(stat, L, !c.contains("等 "),
                "★ 副本不虚报：146 那行不写「等 2 个版本」（那是条目数，不是版本数）");
        L.add("");
    }

    /**
     * ⑥e fork 的「基座版本」（第 40 轮）。
     *
     * 需求原文：「**使用MDTX对应版本的MDT也可以不弹提示**」。
     * MindustryX 在 `assets/mod.hjson` 里用 `minGameVersion` 声明自己从哪个官方版本分出来
     * （实测 X37 ⇒ `160.1`、B497 ⇒ `160.4`），于是"官方 160.1 + MindustryX X37"是**同一个
     * 数据格式**，共用槽不该提示；而"官方 159.7 + MindustryX X37"仍然该提示。
     *
     * ★★ 本段的重点是**两处必须判死的地方**（否则整套判据可能是恒真/恒假的装饰）：
     *   ① gradle 默认值 `"custom"` —— 不拦就会被当成一个"版本号"，把两个无关包静默判成同格式；
     *   ② 键名必须**精确**匹配（`minGameVersionX` 不许命中）—— 否则"前缀就抓"也能过 ① 的前两条。
     *   另有元断言：基座不同的组合**必须**算冲突，证明"相等 ⇒ 空"不是恒空。
     */
    private static void upstreamCompat(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑥e fork 基座版本（MDTX 对应版本的 MDT 不提示） ──");

        // ① 纯解析器：真样本 + 不带引号 + 值后有注释
        String real = "displayName: MindustryX Loader\nname: MindustryX\nauthor: WayZer\n"
                + "main: mindustryX.loader.Main\nversion: \"2026.09.X37\"\n"
                + "minGameVersion: \"160.1\"\nhidden: true\ndependencies: []\n";
        ok(stat, L, "160.1".equals(Versions.parseMinGameVersion(real)),
                "真样本 mod.hjson ⇒ 160.1（实得 " + Versions.parseMinGameVersion(real) + "）");
        ok(stat, L, "160.1".equals(Versions.parseMinGameVersion("minGameVersion: 160.1")),
                "不带引号也认（HJSON 允许）");
        ok(stat, L, "160.4".equals(Versions.parseMinGameVersion("minGameVersion: \"160.4\" # 基于160.4")),
                "值后面的注释被剥掉（实得 "
                        + Versions.parseMinGameVersion("minGameVersion: \"160.4\" # 基于160.4") + "）");

        // ② ★ 两个必须 null 的（判据的安全性全在这两条）
        ok(stat, L, Versions.parseMinGameVersion("minGameVersion: custom") == null,
                "★ gradle 默认值 custom ⇒ 当作「没声明」（不是「基座叫 custom」）");
        ok(stat, L, Versions.parseMinGameVersion("name: X\nversion: \"1.0\"\n") == null,
                "没有这个键 ⇒ null（官方本体的 APK 里连这个文件都没有）");

        // ③ ★ 元断言：不许"见字就抓"
        ok(stat, L, Versions.parseMinGameVersion("# minGameVersion: \"146\"\nname: X") == null,
                "★元断言：整行被注释掉的键不算");
        ok(stat, L, Versions.parseMinGameVersion("minGameVersionX: \"146\"") == null,
                "★元断言：近似键名不许命中（证明 ① 不是靠前缀匹配过的）");
        ok(stat, L, Versions.parseMinGameVersion("minGameVersion: \"?\"") == null,
                "★元断言：非版本号值不许透过（首字符非数字即拒）");

        // ④ I/O 路径：自造 zip，不依赖设备上恰好装了 MindustryX
        File tmp = new File(Paths.privateDir(ctx), "selftest-modhjson");
        deleteTree(tmp);
        try {
            tmp.mkdirs();
            File fork = new File(tmp, "fork.apk");
            zipOne(fork, Versions.ENTRY_MOD_HJSON, real);
            ok(stat, L, "160.1".equals(Versions.readUpstreamVersion(fork)),
                    "从 zip 里真读出基座版本（实得 " + Versions.readUpstreamVersion(fork) + "）");

            File plain = new File(tmp, "official.apk");
            zipOne(plain, "classes.dex", "not-a-real-dex");
            ok(stat, L, Versions.readUpstreamVersion(plain) == null,
                    "包里没有 assets/mod.hjson ⇒ null（官方 / Xenon / MDTHub 都是这种）");

            File cust = new File(tmp, "custom.apk");
            zipOne(cust, Versions.ENTRY_MOD_HJSON, "minGameVersion: custom\n");
            ok(stat, L, Versions.readUpstreamVersion(cust) == null,
                    "端到端：custom 一路传到调用方仍是 null");

            ok(stat, L, Versions.readUpstreamVersion(new File(tmp, "nope.apk")) == null,
                    "文件不存在 ⇒ null（不抛）");
        } catch (Exception e) {
            ok(stat, L, false, "自造 zip 失败：" + e);
        } finally {
            deleteTree(tmp);
        }

        // ⑤ ★ 判据：fork 按**基座版本**参与比较，但**显示**仍是它自己的版本号
        Versions.Entry official160 = mkEntry("Mindustry", "8-official-160.1", null);
        Versions.Entry official159 = mkEntry("Mindustry", "8-official-159.7", null);
        Versions.Entry fork = mkEntry("MindustryX", "8-official-2026.09.X37", "160.1");

        ok(stat, L, "2026.09.X37".equals(fork.displayVersion())
                        && "160.1".equals(fork.formatVersion()),
                "★ 显示 = 2026.09.X37、判据 = 160.1（两个属性分工，不许混用）");

        List<Versions.Entry> same = new ArrayList<>();
        same.add(official160);
        same.add(fork);
        ok(stat, L, Versions.conflictPeers(official160, same).isEmpty()
                        && Versions.conflictPeers(fork, same).isEmpty(),
                "★ 官方 160.1 + MindustryX X37（基座 160.1）⇒ 无对手（用户要的就是这条）");

        // ⑥ ★ 元断言：基座不同 ⇒ 必须判死
        List<Versions.Entry> diff = new ArrayList<>();
        diff.add(official159);
        diff.add(fork);
        ok(stat, L, Versions.conflictPeers(official159, diff).size() == 1,
                "★元断言：官方 159.7 + MindustryX X37（基座 160.1）⇒ 仍算冲突"
                        + "（证明上面那条不是恒空）");

        // ⑦ 回落：没声明基座的条目，口径 = 显示版本（= 收窄前的行为）
        ok(stat, L, official159.formatVersion().equals(official159.displayVersion()),
                "没声明基座 ⇒ formatVersion 回落到 displayVersion（行为不变）");

        // ⑧ ★★ **真机样本：本应用自己的 APK**（一个货真价实的 `/data/app/...` 安装包）。
        //   这条闭的是一个**静默失效**的口子：用户真正的场景是"**已安装**的 MindustryX"，
        //   而它的 APK 在 `/data/app/~~xxx==/pkg-yyy==/base.apk` —— 万一这个路径打不开
        //   （权限 / 分区），整条功能会**无声地**退化成"没声明基座"，界面上看不出任何异样。
        //   用"自己这个已安装包"当样本，任何设备上都跑得到，不依赖恰好装了 MindustryX。
        String selfApk = ctx.getApplicationInfo().sourceDir;
        ok(stat, L, selfApk != null && new File(selfApk).isFile(),
                "能拿到本应用的安装包路径：" + selfApk);
        boolean canOpen = false;
        try {
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(new File(selfApk));
            try { canOpen = z.getEntry("classes.dex") != null; } finally { z.close(); }
        } catch (Throwable t) { canOpen = false; }
        ok(stat, L, canOpen,
                "★ 真机样本：已安装的 /data/app/... APK 能被打开并列出 classes.dex"
                        + "（这是「读出已装 fork 的基座版本」的前提；打不开就会静默不生效）");
        ok(stat, L, Versions.readUpstreamVersion(new File(selfApk)) == null,
                "★ 真机样本：本应用没有 mod.hjson ⇒ null（「能打开」与「没这个文件」是两件事）");

        // ⑨ 第 41 轮：真实 **prerelease** 样本。
        //   MindustryX 的预发布渠道 = `build.yml` 产出的 B 系列
        //   （`RELEASE_VERSION=$(date +'%Y.%m.%d').B${GITHUB_RUN_NUMBER}` + `prerelease: true`）。
        //   用户的要求：「**你试下MDTX的Prerelease版本号能不能正常识别**」。
        //   ⇒ 实测两个真实 prerelease 包的 minGameVersion 都是**纯版本号**，正常识别。
        ok(stat, L, "160.5".equals(Versions.parseMinGameVersion(
                        "version: \"2026.10.02.B502\"\nminGameVersion: \"160.5\"\n")),
                "★ 真样本 prerelease B502（build.yml 的 B 系列）⇒ 基座 160.5，正常识别");
        ok(stat, L, "160.4".equals(Versions.parseMinGameVersion(
                        "version: \"2026.09.14.B497\"\nminGameVersion: \"160.4\"\n")),
                "★ 真样本 prerelease B497 ⇒ 基座 160.4");
        ok(stat, L, "159.7".equals(Versions.parseMinGameVersion(
                        "version: \"2026.07.X36\"\nminGameVersion: \"159.7\"\n")),
                "★ 真样本 X36 ⇒ 基座 159.7（= 用户找的那个「159.7 对应的 MDTX」）");
        //   ★ 为什么 prerelease 不会带后缀：构建链拿这个值**拼下载 URL**
        //     （`loaderMod.gradle.kts`: `.../releases/download/v$upstreamBuild/Mindustry.jar`）
        //     ⇒ 它必须是真实 tag；而官方 tag **从不带后缀**（查证 v160.5…v119 全是纯版本号，
        //       beta 只在 `prerelease: true` 与 `name` 字段里）。下面这条把"官方 tag 形态"
        //       钉成断言，将来若上游改了命名法，这里会先红。
        ok(stat, L, Versions.parseMinGameVersion("minGameVersion: \"157\"") != null
                        && Versions.parseMinGameVersion("minGameVersion: \"157.1\"") != null
                        && Versions.parseMinGameVersion("minGameVersion: \"160.5\"") != null,
                "★ 官方 tag 的三种真实形态（无补丁号 / 一位补丁 / 两位补丁）全部识别");
        //   ★ 元断言（决策锁）：`-beta` 形式的**假设**输入不许被"归一化"掉 ——
        //     我们的比较是**字符串精确相等**（定案「就用精确版本号吧」）。
        //     写成"有后缀就剥掉"的话，这条会失败 —— 而那种改动会在**没有任何症状**的情况下
        //     改变判据口径（正是本项目反复栽过的"悄悄放宽"）。
        String beta = Versions.parseMinGameVersion("minGameVersion: \"160.5-beta\"");
        ok(stat, L, "160.5-beta".equals(beta),
                "★元断言：万一带后缀也**原样保留、不归一化**（实得 " + beta
                        + "）—— 若有人加了「剥后缀」逻辑，这条先红");
        //   ★★ 这条才是真正有分辨力的那条：拿"已知会判死的输入"喂给**判据本体**，
        //      要求它给出可预期的结果（只断 `parseMagGameVersion` 的返回值太弱 ——
        //      一个恒返回原字符串的坏实现也能过）。这里断的是**冲突判定**：
        //      基座写 `160.5-beta` 时，它与官方 `160.5` **仍算冲突** —— 这正是
        //      定案的"精确版本号"口径的**可观察后果**。
        Versions.Entry betaFork = mkEntry("MindustryX", "8-official-2026.10.02.B502", "160.5-beta");
        Versions.Entry official1605 = mkEntry("Mindustry", "8-official-160.5", null);
        List<Versions.Entry> betaMix = new ArrayList<>();
        betaMix.add(official1605);
        betaMix.add(betaFork);
        ok(stat, L, Versions.conflictPeers(official1605, betaMix).size() == 1,
                "★元断言：`160.5-beta` 与官方 `160.5` **仍算冲突** ⇒ 精确相等口径真的在生效"
                        + "（改成长度/前缀/数值比较都会让这条变绿 —— 那是本项目的静默放宽老坑）");

        // ⑩ 第 41 轮新增：`upstreamNote()` —— 列表行尾注与详情弹窗**共用**的显示口径。
        Versions.Entry installed = mkEntry("MindustryX", "8-official-2026.09.X37", "160.1");
        ok(stat, L, "160.1".equals(installed.upstreamNote()),
                "★ 已装 fork：显示版本 2026.09.X37 ≠ 基座 160.1 ⇒ 要备注（实得 "
                        + installed.upstreamNote() + "）");
        Versions.Entry noDecl = mkEntry("Mindustry", "8-official-159.7", null);
        ok(stat, L, noDecl.upstreamNote() == null,
                "官方本体没声明 ⇒ 不备注（整串与从前逐字相同）");
        //   ★★ 这条是**导入的 fork**，也是上一轮报告里那个"看着像两个不同东西"的现象：
        //      它的显示版本本来就取自 `version.properties` 的上游 build（`Importer.readVersion`），
        //      与 `minGameVersion` **同源同值** ⇒ 行首已经是 `160.1`，再缀"基于 160.1"是同义反复。
        Versions.Entry impFork = mkEntry("tmp-import-test", "8-official-160.1", "160.1");
        ok(stat, L, "160.1".equals(impFork.displayVersion()) && impFork.upstreamNote() == null,
                "★ 导入的 fork：显示版本恰等于基座 ⇒ **不备注**（否则是「160.1（基于 160.1）」）");
        //   ★ 元断言：证明上一条不是"恒 null"——同一个方法对已装 fork 必须给值。
        ok(stat, L, installed.upstreamNote() != null && impFork.upstreamNote() == null,
                "★元断言：upstreamNote 对「已装 fork / 导入 fork」给出不同答案（判据有分辨力）");

        // ⑪ 列表行实拼：尾注真的接在主标题后面，且**不该显示时逐字等于 displayName()**。
        String withNote = installed.displayName()
                + ctx.getString(R.string.row_upstream_fmt, installed.upstreamNote());
        ok(stat, L, withNote.equals("MindustryX  2026.09.X37（基于 160.1）"),
                "★ 列表行实拼 ⇒ `" + withNote + "`");
        ok(stat, L, noDecl.displayName().equals("Mindustry  159.7"),
                "★ 官方行实拼 ⇒ 无尾注（与加尾注之前逐字相同）");
        L.add("");
    }

    /** ⑥f 用：槽名 / [当前] / 统计 三段之间的边界处是否都有空白（抽出来才能配元断言） */
    private static boolean slotLineSpaced(String line) {
        int a = line.indexOf("[当前]");
        if (a < 0) return false;
        int end = a + "[当前]".length();
        return a > 0 && line.charAt(a - 1) == ' '
                && end < line.length() && line.charAt(end) == ' ';
    }

    /**
     * ⑥f 槽选项串的空白 —— 资源里的**前导空格**会不会被 aapt2 吃掉（第 42 轮）。
     *
     * 起因（用户截图）：选槽对话框渲染成 `default[当前]52 文件 · 208.3 KB` ——
     * 槽名 / 当前标记 / 统计三项粘成一坨。**根因不是拼接代码写错**，而是
     * `strings.xml` 里写字面前导空格、**aapt2 编译时把前导空白剥掉了**
     * （原文写了 2 个，装机后一个不剩）；而 Java 字符串里的空格不受影响、
     * 所以"看着源码没问题、装机就粘住"。
     *
     * ★★ 所以断言**不能停在"源码里有空格"**（那是看代码，不是验行为），
     *    必须把资源**真的取出来**看首字符：
     *    ① `slot_current_suffix` 首字符必须是空格 —— 谁把它改回字面空格（转义丢了），
     *       这条会立刻判死；
     *    ② 用真资源**实拼一条**，要求三段边界都有空白；
     *    ③ ★ **元断言**：把"粘起来"的写法喂给同一个判据 `slotLineSpaced`，必须判死
     *       —— 否则 ② 可能只是一条恒真的装饰（REF §35.13）。
     */
    private static void slotLineSpacing(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑥f 槽选项串：资源前导空格不许被 aapt2 吃掉 ──");

        String cur = ctx.getString(R.string.slot_current_suffix);
        ok(stat, L, cur.startsWith(" "),
                "★ 资源 slot_current_suffix 首字符是空格（U+0020 转义真的生效了；实得「"
                        + cur + "」）");
        ok(stat, L, cur.trim().equals("[当前]"),
                "★ 去掉空白后恰好是 [当前]（转义没多也没少字符，实得「" + cur.trim() + "」）");

        // ② 实拼：当前槽
        String line = "default" + cur
                + "  " + ctx.getString(R.string.slot_entry_fmt, 52, "208.3 KB");
        ok(stat, L, slotLineSpaced(line), "★ 实拼（当前槽）三段边界都有空白 ⇒ `" + line + "`");
        // ② 实拼：非当前槽（没有 [当前] 段，槽名与统计之间仍必须有空白）
        String plain = "test" + "  " + ctx.getString(R.string.slot_entry_fmt, 0, "0 B");
        ok(stat, L, plain.startsWith("test  ") && plain.indexOf("0 个文件") > 5,
                "★ 实拼（非当前槽）⇒ `" + plain + "`");

        // ③ ★ 元断言：判据必须量得出东西
        ok(stat, L, !slotLineSpaced("default[当前]52 文件 · 208.3 KB"),
                "★元断言：就是用户截图里那个粘连形态，必须判死（判据不是恒真）");
        ok(stat, L, !slotLineSpaced("default  [当前]52 文件 · 208.3 KB"),
                "★元断言：只补前半段也不放过 —— [当前] 后面缺空白同样判死");
        L.add("");
    }

    /** ⑥e 用：造一个只填 name/版本名/基座版本的 Entry（`upstreamChecked=true` 免得它去读盘） */
    private static Versions.Entry mkEntry(String label, String ver, String upstream) {
        Versions.Entry e = new Versions.Entry();
        e.label = label;
        e.versionName = ver;
        e.upstreamVersion = upstream;
        e.upstreamChecked = true;
        return e;
    }

    /** ⑥e 用：写一个只含一个条目的最小 zip（当 APK 用；`readUpstreamVersion` 只看条目名） */
    private static void zipOne(File f, String entryName, String text) throws IOException {
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(f));
        try {
            z.putNextEntry(new java.util.zip.ZipEntry(entryName));
            z.write(text.getBytes("UTF-8"));
            z.closeEntry();
        } finally {
            z.close();
        }
    }

    /** 造一组只填了名字 / 版本号的 Entry（⑥d 用；`conflictPeers` 只看 displayVersion） */
    private static List<Versions.Entry> groupOf(String... vers) {
        List<Versions.Entry> g = new ArrayList<>();
        for (String v : vers) {
            Versions.Entry e = new Versions.Entry();
            e.label = "M";
            e.versionName = v;
            g.add(e);
        }
        return g;
    }

    /** 数一个字符在串里出现几次（⑥c 的书名号判据用；纯 ASCII 比较，无编码风险） */
    private static int countOf(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /**
     * 从 `「A」等 N 个版本` 里把 **N** 抽出来；没有"等 N 个"这一段时返回 **1**
     * （因为 2 个版本共用时只点一个对手、不写数字，语义上就是"另外 1 个"）。
     *
     * ★ 抽出来的是**数字本身**，所以 ⑥c 的断言可以直接比对 `N == total - 1` ——
     *   这是"数量算上自己"那个错误的唯一判据，形态断言（有没有"等"字）抓不到。
     * ⚠️ 找不到"等"却又不是 1 时返回 -1（当成不合规），别静默返回 1 掩盖问题。
     */
    private static int shownPeerCount(String desc) {
        if (desc == null) return -1;
        int i = desc.indexOf("等 ");
        if (i < 0) return 1;
        int j = i + 2;
        StringBuilder num = new StringBuilder();
        while (j < desc.length() && Character.isDigit(desc.charAt(j))) {
            num.append(desc.charAt(j));
            j++;
        }
        if (num.length() == 0) return -1;
        if (!desc.startsWith(" 个版本", j)) return -1;
        try {
            return Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 中文与插入值**紧邻处**是否出现了多余空格（⑥c 的排版判据）。
     *
     * ⚠️ 刻意用"**具体紧邻组合**的白名单式判断"，而**不是** `contains(" ")` ——
     *   后者会在 fmt 将来真需要空格时把好文案判死（假阳性比漏报更费时间，
     *   因为**它会让人去改本来正确的代码**，见 REF §35.11）。
     *   目前覆盖四处：`与 「` / `」 ` / ` 共` / `版本 等`。
     */
    private static boolean hasCjkSpace(String s) {
        return s != null && (s.contains(" 「") || s.contains("」 ")
                || s.contains(" 共") || s.contains("版本 等"));
    }

    /**
     * ⑫ 日志导出（F20）：**内容完整性**。
     *
     * ★ 为什么必须验，而且必须"逐段比对内容"：
     *   导出是"把三个源拼成一个文件"。这类代码最典型的错误**不是崩，而是漏** ——
     *   少拼一卡时文件照样生成、字节数也像样、用户拿到手才发现缺东西。
     *   所以断言盯的是**每一段里那句独特的内容**（每份样本都带自己的 nonce），
     *   而不是"文件非空 / 有标题"（后者对"漏一段"完全无感）。
     *
     * ★ 第二条：**导出不许截断**。页面视图只留尾部 {@code MAX_LINES=400} 行，
     *   导出必须走全文 —— 否则最前面那段（往往就是启动阶段的关键信息）被静默丢掉。
     *   这一条拿一份 600 行的样本验：第 0 行与第 599 行都要在导出结果里。
     *
     * ★ 第三条是**元断言**：故意造一份"游戏日志缺失"的快照，要求同一个判据**判死**。
     *   ⚠️ 没有它，上面那些 `out.contains(...)` 可能全是恒真的装饰（判据写错方向、
     *   或 compose 返回了空串以外的任何东西都会通过）—— 那正是"看着有自检、其实没验"
     *   的经典形态（REF §35.11 / §35.13）。
     *
     * ⚠️ 全程只在 {@code cacheDir} 下造临时样本，绝不碰用户的数据根与槽。
     */
    private static void logExport(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑫ 日志导出（内容完整性） ──");
        File dir = new File(ctx.getCacheDir(), "selftest-export");
        deleteTree(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            ok(stat, L, false, "建临时目录失败：" + dir);
            return;
        }
        try {
            // 每份样本带一个**只属于它**的 nonce —— 判据才有分辨力
            File fg = writeTmp(dir, "last_log.txt", "GAME-ALPHA-9f3c\n[I] game line 2\n");
            File f1 = writeTmp(dir, "report-launch.txt", "HUB-LAUNCH-7b21\n");
            File f2 = writeTmp(dir, "report-health.txt", "HUB-HEALTH-44de\n");
            File f3 = writeTmp(dir, "1700000000000.txt", "CRASH-ONE-abc123\nNullPointerException\n");

            LogActivity.Snap s = new LogActivity.Snap();
            s.game = new LogActivity.Doc("last_log.txt", fg);
            s.hub = new LogActivity.Doc[]{
                    new LogActivity.Doc("report-launch.txt", "启动", f1),
                    new LogActivity.Doc("report-health.txt", "体检", f2),
            };
            s.crashes = new ArrayList<>();
            s.crashes.add(new LogActivity.Doc("1700000000000.txt", f3));
            s.game.load();
            for (LogActivity.Doc d : s.hub) d.load();
            for (LogActivity.Doc d : s.crashes) d.load();

            String out = LogActivity.compose(ctx, s, "HDR-F20-test");

            ok(stat, L, out.contains("HDR-F20-test"), "表头原样带出");
            ok(stat, L, out.contains("GAME-ALPHA-9f3c"), "① 游戏日志的正文在导出里");
            ok(stat, L, out.contains("HUB-LAUNCH-7b21"), "② 第 1 份启动器日志的正文在");
            ok(stat, L, out.contains("HUB-HEALTH-44de"), "② 第 2 份启动器日志的正文在");
            ok(stat, L, out.contains("CRASH-ONE-abc123") && out.contains("NullPointerException"),
                    "③ 崩溃报告正文在（含堆栈行）");
            ok(stat, L, out.contains(ctx.getString(R.string.log_export_s1))
                            && out.contains(ctx.getString(R.string.log_export_s2))
                            && out.contains(ctx.getString(R.string.log_export_s3_fmt, 1)),
                    "三段的小标题都在（缺一段即可看出）");
            ok(stat, L, out.contains("启动") && out.contains("report-launch.txt"),
                    "每段自报家门（展示名 + 文件名）—— 离线看的人才知道这是哪份");

            // ★ 不截断：600 行的样本，页面视图只留尾部 400 行，导出必须整份带出
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 600; i++) big.append("LINE-").append(i).append('\n');
            File fb = writeTmp(dir, "big.txt", big.toString());
            LogActivity.Doc bd = new LogActivity.Doc("big.txt", fb);
            bd.load();
            ok(stat, L, bd.truncated,
                    "前置：600 行样本在**页面视图**里确实是截断的（truncated=true）");
            LogActivity.Snap s2 = new LogActivity.Snap();
            s2.game = bd;
            String bo = LogActivity.compose(ctx, s2, "HDR");
            ok(stat, L, bo.contains("LINE-0\n") && bo.contains("LINE-599"),
                    "★ 导出走全文：第 0 行与第 599 行都在（页面只显示尾部 400 行）");

            // 缺失的源要**说成缺失**，不能变成"凭空少一段"（用户会以为导出坏了）
            LogActivity.Snap s3 = new LogActivity.Snap();
            s3.game = new LogActivity.Doc("last_log.txt", new File(dir, "no-such-file.txt"));
            ok(stat, L, LogActivity.compose(ctx, s3, "HDR")
                            .contains(ctx.getString(R.string.log_export_absent)),
                    "源不存在时写出「缺失」而不是静默跳过");

            // ★ 元断言：漏掉游戏日志的拼装，必须被同一判据判死
            LogActivity.Snap bad = new LogActivity.Snap();
            bad.hub = s.hub;
            bad.crashes = s.crashes;
            bad.game = new LogActivity.Doc("last_log.txt", new File(dir, "no-such-file.txt"));
            String badOut = LogActivity.compose(ctx, bad, "HDR-F20-test");
            ok(stat, L, !badOut.contains("GAME-ALPHA-9f3c"),
                    "元断言：游戏日志缺失时该判据判死（证明上面几条有分辨力）");

            // ★ 第一条正路：**页面上真读一遍**再拼（readAll 是 static，dev 口也走它）
            LogActivity.Snap live = LogActivity.readAll(ctx);
            ok(stat, L, !live.dataRootMissing && live.hub.length == 6,
                    "readAll 能独立跑通（hub 六份都在册，dataRoot 可用）");
        } catch (Throwable t) {
            ok(stat, L, false, "日志导出用例异常：" + t);
        } finally {
            deleteTree(dir);
        }
        L.add("");
    }

    private static File writeTmp(File dir, String name, String text) throws IOException {
        // ★ 目录不存在就建：`atomicWriteText` 不会替我们建父目录（踩过：ENOENT 让整组用例挂掉）
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File f = new File(dir, name);
        Util.atomicWriteText(f, text);
        return f;
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteTree(x);
        }
        f.delete();
    }

    /**
     * ⑦ GC 的**删除**分支：过期的孤儿真被删（含空分片目录回收），
     * 仍在宽限期内的不删，**被引用的一个都不动**。
     * 为什么单独立一条：`garbageCollect` 里 `o.delete()` / 空分片目录 `p.delete()`
     * 这两行，在真机上**从来没被执行过** —— 历次 GC 都是"删 0 个"（对象都还在宽限期内）。
     * 一个从不执行的删除分支等于未验证的代码：它完全可能写错成"永远不删"，
     * 那样对象池会随删除/改名备份而无界膨胀，且**没有任何现象会提示你**。
     *
     * ★ 造"过期对象"的办法：**在应用进程内**调 {@link File#setLastModified}。
     *   这一步用 `adb shell touch` 做不到 —— shell 是另一个 UID，对外部存储上属于本应用
     *   的文件只能得到 `Operation not permitted`（踩过一次：9 个对象全失败，
     *   于是"验证老孤儿被删"这件事被卡住）。**进程内改自己的文件没有这个限制**。
     */
    private static void casGc(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑦ CAS 回收：过期孤儿真的会被删 ──");
        Cas pool = new Cas(Backup.casBaseDir(ctx));
        File cache = ctx.getCacheDir();
        long now = System.currentTimeMillis();

        // 内容寻址下"内容即身份" ⇒ 两份不同内容 = 两个不同地址的两个孤儿
        Cas.Stored so, sf;
        try {
            File seedOld = new File(cache, "gc-orphan-old.bin");
            write(seedOld, gzip(("orphan-old-" + now).getBytes("UTF-8")));
            so = pool.put(seedOld, null);

            File seedFresh = new File(cache, "gc-orphan-fresh.bin");
            write(seedFresh, gzip(("orphan-fresh-" + now).getBytes("UTF-8")));
            sf = pool.put(seedFresh, null);
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ 造孤儿对象失败：" + e);
            L.add("");
            return;
        }
        File oldOne = pool.objectPath(so.sha);
        File freshOne = pool.objectPath(sf.sha);
        File shard = oldOne.getParentFile();
        boolean sole = shard.listFiles() != null && shard.listFiles().length == 1;

        ok(stat, L, so.isNew && sf.isNew, "两个孤儿对象已入库（都不被任何清单引用）");
        boolean backdated = oldOne.setLastModified(now - 2 * Cas.GC_GRACE_MS);
        ok(stat, L, backdated, "★ 进程内把老孤儿的 mtime 拨到 "
                + (2 * Cas.GC_GRACE_MS / 60000) + " 分钟前（adb shell 的 touch 做不到这件事）");
        ok(stat, L, oldOne.lastModified() <= now - Cas.GC_GRACE_MS, "老孤儿确实已过宽限期");
        ok(stat, L, freshOne.lastModified() > now - Cas.GC_GRACE_MS, "新孤儿仍在宽限期内");

        java.util.Set<String> refs = Backup.allReferencedShas(ctx);
        ok(stat, L, !refs.isEmpty(), "此刻池里有 " + refs.size() + " 个被清单引用的对象");

        int removed = Backup.gcNow(ctx);
        ok(stat, L, removed >= 1, "★ GC 删掉了过期孤儿（本次删 " + removed + " 个）");
        ok(stat, L, !oldOne.exists(), "★★ 过期孤儿对象文件真的不在了：" + so.sha.substring(0, 12) + "…");
        ok(stat, L, freshOne.isFile(), "★ 宽限期内的孤儿被保住（防误删的那道保险）");

        // ★ 反向断言（比"删了几个"重要得多）：被引用的一律不许动
        boolean allAlive = true;
        String missing = null;
        for (String id : refs) {
            if (!pool.has(id)) {
                allAlive = false;
                missing = id;
                break;
            }
        }
        ok(stat, L, allAlive, "★★ 全部 " + refs.size() + " 个被引用对象完好（GC 没误删）"
                + (allAlive ? "" : "，缺：" + missing));

        ok(stat, L, !sole || !shard.exists(),
                sole ? "空分片目录被顺手收掉"
                     : "该分片还有其他对象 ⇒ 目录必须保留（这次没测到空目录回收）");

        // 收尾：新孤儿手动清掉（它过了宽限期也会被 GC 收走，这里先不留垃圾）
        File fshard = freshOne.getParentFile();
        freshOne.delete();
        if (fshard.list() != null && fshard.list().length == 0) fshard.delete();
        new File(cache, "gc-orphan-old.bin").delete();
        new File(cache, "gc-orphan-fresh.bin").delete();
        Cas.Pool p = pool.scan(Backup.allReferencedShas(ctx));
        ok(stat, L, p.orphans == 0, "清干净后池里孤儿 = " + p.orphans + "（应为 0）");
        L.add("");
    }

    /**
     * ⑧ 旧格式（v1）自动迁移。
     *
     * 这里**手工造一份 v1 快照**（`files/` 全量拷贝 + `mdt-backup 1` 清单），
     * 然后走 {@link Backup#migrateLegacy} 转成 v2，并验证转完后**内容仍能逐字节还原**
     * —— 迁移最容易出的错不是"转不过去"，而是"转过去了但内容错位"。
     */
    private static void legacyMigration(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑧ 旧格式（v1）自动迁移 ──");
        File dir = new File(Backup.slotDir(ctx, SLOT), "20200101-000000-legacy");
        File payload = new File(dir, "files/saves/legacy.msav");
        try {
            write(payload, gzip("legacy-payload".getBytes("UTF-8")));
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ 造 v1 快照失败：" + e);
            return;
        }
        String wantMd5 = Util.md5(payload);
        long created = 1577836800000L;      // 2020-01-01 00:00:00 UTC（固定值，可复现）
        try {
            Util.atomicWriteText(new File(dir, "manifest.txt"),
                    "mdt-backup 1\n"
                    + "slot=" + SLOT + "\n"
                    + "created=" + created + "\n"
                    + "label=legacy\n"
                    + "source=" + dir.getAbsolutePath() + "\n"
                    + "count=1\n"
                    + "bytes=" + payload.length() + "\n"
                    + "---\n"
                    + wantMd5 + "\t" + payload.length() + "\tsaves/legacy.msav\n");
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ 写 v1 清单失败：" + e);
            return;
        }
        ok(stat, L, Backup.hasLegacy(ctx), "认出旧格式快照（mdt-backup 1）");

        String rep = Backup.migrateLegacy(ctx);
        ok(stat, L, rep != null && rep.contains("成功 1 份"), "迁移报告：" + oneLine(rep));
        ok(stat, L, !new File(dir, "files").exists(), "迁移后 files/ 已删除（原件没白占空间）");
        ok(stat, L, !Backup.hasLegacy(ctx), "已无旧格式快照");

        // 迁移后必须**仍能正确恢复** —— 这是整个迁移唯一真正的验收点
        Backup.Snapshot migrated = null;
        for (Backup.Snapshot s : Backup.list(ctx, SLOT)) {
            if (s.created == created) { migrated = s; break; }
        }
        ok(stat, L, migrated != null && migrated.version == 2, "迁移后的快照是 v2 格式");
        if (migrated == null) { L.add(""); return; }
        File live = new File(Data.slotDir(ctx, SLOT), "saves/legacy.msav");
        try {
            write(live, gzip("trashed".getBytes("UTF-8")));
        } catch (Exception ignored) {
        }
        Backup.RestoreResult rr = Backup.restore(ctx, SLOT, migrated);
        ok(stat, L, rr.ok, "从迁移后的快照恢复成功");
        ok(stat, L, live.isFile() && Util.md5(live).equals(wantMd5),
                "★ 迁移后恢复出来的内容与迁移前逐字节一致（md5 相同）");
        L.add("");
    }

    private static void msavPipeline(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑨ .msav 落盘管线 ──");
        try {
            byte[] gz = zlib("desktop-save-payload".getBytes("UTF-8"));   // ★ 真 .msav 是 zlib，不是 gzip
            byte[] raw = "this-is-not-gzip".getBytes("UTF-8");

            Msav.Stage s1 = Msav.stage(ctx, new ByteArrayInputStream(gz), "我的存档 1.msav", SLOT);
            ok(stat, L, s1.zlib, "★zlib 流（78 9C 头）被认出来（原来这里断言的是 gzip 1f8b —— 判据是错的）");
            ok(stat, L, "saves".equals(s1.savesDir.getName()), "落点是 saves/");
            File f1 = Msav.commit(ctx, s1);
            ok(stat, L, "我的存档 1.msav".equals(f1.getName()),
                    "★ 中文存档名被原样保留（游戏列表显示的就是文件名）：" + f1.getName());
            ok(stat, L, Util.md5(f1).length() == 32, "落盘可读且 md5 可算");
            ok(stat, L, !new File(s1.savesDir, f1.getName() + ".part").exists(), "无 .part 残留");

            Msav.Stage s2 = Msav.stage(ctx, new ByteArrayInputStream(raw), "notmsav", SLOT);
            ok(stat, L, !s2.zlib, "非 .msav（不是 zlib 流）未被误判，交用户确认");
            Msav.discard(s2);
            ok(stat, L, !s2.part.exists(), "discard 后 .part 已清理");

            // 路径注入与非法字符
            Msav.Stage sInj = Msav.stage(ctx, new ByteArrayInputStream(gz), "../../evil.msav", SLOT);
            ok(stat, L, sInj.savesDir.equals(s1.savesDir) && !sInj.base.contains("/"),
                    "路径注入被挡住（" + sInj.base + "）");
            Msav.discard(sInj);

            // 同名重导 = 替换
            Msav.Stage s3 = Msav.stage(ctx, new ByteArrayInputStream(gz), "我的存档 1.msav", SLOT);
            File f3 = Msav.commit(ctx, s3);
            ok(stat, L, f3.getName().equals(f1.getName()), "同名重导替换而不新增");
        } catch (Exception e) {
            stat[1]++;
            L.add("❌ .msav 管线异常：" + e);
        }
        L.add("");
    }

    /**
     * ⑦ 兼容性探测（F18）—— 纯函数 + 只读样本，不碰用户数据。
     *
     * ★ 为什么非要有这一节：F18 的整套判据里最容易写错的是 **ELF 的 32/64 位偏移口径**
     *   （`e_phoff`、`p_align` 在两个位宽下位置完全不同），而写错的表现是
     *   **永远返回 0** ⇒ 按"读不到就不拦"的保守策略，**它永远不会拒绝任何包**：
     *   界面一切正常、不崩不报错，只有一个本该被拦的包照旧导入成功。
     *   ⇒ 只能用手工构造的 ELF 字节把偏移钉死，并配**元断言**证明判据有分辨力。
     *
     * ⚠️ 测试用的假 ELF 里**故意多放一个非 PT_LOAD 段、且给它更大的 p_align**——
     *   若实现写成"取所有段的最大值"就会被抓出来（返回值变成 align*4）。
     */
    private static void compatProbe(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑦ 兼容性探测（F18：按能力拒绝） ──");

        // ① 入口类串的**单一来源**：Compat 必须从 Injector 推导。
        //   ⚠️ 这里**刻意不写** "mindustry/android/AndroidLauncher" 这个字面量 ——
        //      写了它就会落进**我们自己的 dex**，而下面 ⑨ 的真实样本探测会搜到自己
        //      （现象：本应用 APK 被判成"有入口类"，看着像探测的 bug，其实是
        //       "测试把答案写在了被试身上"）。⇒ 改用结构断言：既钉住"是描述符形式"，
        //       又让 ⑨ 保持一个干净的负样本。
        ok(stat, L, Compat.ENTRY_DESC.equals(Injector.GAME_ACTIVITY.replace('.', '/')),
                "ENTRY_DESC 由 Injector.GAME_ACTIVITY 推导（不是第二份字面量）：" + Compat.ENTRY_DESC);
        ok(stat, L, Compat.ENTRY_DESC.indexOf('.') < 0
                        && Compat.ENTRY_DESC.startsWith("mindustry/")
                        && Compat.ENTRY_DESC.endsWith("/AndroidLauncher"),
                "ENTRY_DESC 是 dex 类描述符形式（点号全部换成斜杠）");

        // ② ELF64：p_align 读得对（16384 与 4096 各一次）
        ok(stat, L, Compat.elfMaxAlign(fakeElf64(16384)) == 16384,
                "ELF64：p_align=16384（实读 " + Compat.elfMaxAlign(fakeElf64(16384)) + "）");
        ok(stat, L, Compat.elfMaxAlign(fakeElf64(4096)) == 4096,
                "ELF64：p_align=4096（实读 " + Compat.elfMaxAlign(fakeElf64(4096)) + "）");

        // ③ ELF32：**偏移完全不同的那一套**（这条专门抓"只按 64 位写"）
        ok(stat, L, Compat.elfMaxAlign(fakeElf32(16384)) == 16384,
                "ELF32：偏移口径也对（实读 " + Compat.elfMaxAlign(fakeElf32(16384)) + "）");

        // ④ 非 ELF / 太短 / null ⇒ 0（调用方据此"不拦"，属预期行为）
        ok(stat, L, Compat.elfMaxAlign("not an elf at all, just text".getBytes()) == 0,
                "非 ELF ⇒ 0（不拦）");
        ok(stat, L, Compat.elfMaxAlign(new byte[16]) == 0, "太短 ⇒ 0（不拦）");
        ok(stat, L, Compat.elfMaxAlign(null) == 0, "null ⇒ 0（不抛异常）");

        // ⑤ ★ 元断言：把那个 PT_LOAD 的类型改掉 ⇒ 必须读成 0。
        //    没有这一条，②③ 有可能是"随便读了个字段"也能过 —— 判据就失去分辨力。
        byte[] notLoad = fakeElf64(16384);
        notLoad[64] = 2;                     // p_type = PT_DYNAMIC
        ok(stat, L, Compat.elfMaxAlign(notLoad) == 0,
                "元断言：非 PT_LOAD 的 p_align 不被采信（证明②不是恒真）");

        // ⑥ 页对齐判据（纯逻辑，直接摆 pageSize）
        Compat.Probe p = new Compat.Probe();
        p.hasEntry = true;
        p.coreLib = "libarc.so";
        p.abi = "arm64-v8a";
        p.maxAlign = 4096;  p.pageSize = 4096;
        ok(stat, L, p.alignOk(), "4 KB 库 / 4 KB 页 ⇒ 合格");
        p.pageSize = 16384;
        ok(stat, L, !p.alignOk(), "4 KB 库 / 16 KB 页 ⇒ 不合格（Android 15+ 会拒载）");
        p.maxAlign = 16384;
        ok(stat, L, p.alignOk(), "16 KB 库 / 16 KB 页 ⇒ 合格");
        p.maxAlign = 0;
        ok(stat, L, p.alignOk(), "读不到对齐（0）⇒ 合格（保守：不拦）");
        p.pageSize = 4096;   // ⚠️ 必须复位：上面把它改成 16384 了，不复位这条必假（实测栽过）
        p.maxAlign = 4096;
        ok(stat, L, p.runnable(), "四项硬判据齐 ⇒ runnable");
        ok(stat, L, p.needsRename(), "无属性注入 ⇒ needsRename（该回退改名交换）");
        p.hasProp = true;
        ok(stat, L, !p.needsRename(), "有属性注入 ⇒ 不需要回退");
        p.hasEntry = false;
        ok(stat, L, !p.runnable(), "缺入口类 ⇒ 不可进管线（哪怕库和页对齐都合格）");

        // ⑦ 字节搜索的边界（针比干草长时不能越界）
        ok(stat, L, Compat.contains("abcdef".getBytes(), "cde".getBytes()), "字节搜索：命中");
        ok(stat, L, !Compat.contains("abcdef".getBytes(), "xyz".getBytes()), "字节搜索：不命中");
        ok(stat, L, !Compat.contains("abc".getBytes(), "abcd".getBytes()),
                "字节搜索：针比干草长 ⇒ false（不越界）");

        // ⑧ 设备页大小（真机读一次 sysconf）
        long ps = Compat.pageSize();
        ok(stat, L, ps > 0 && (ps & (ps - 1)) == 0, "设备页大小 = " + ps + " B（2 的幂）");

        // ⑨ ★ 真实样本：拿**本应用自己的 APK** 当被试 —— 它既没有游戏入口类、也没有 lib/，
        //    必须被判为"不可进管线"。这条证明探测在**真实的大 APK** 上跑得通
        //    （不抛异常、不因体积放弃），而不是只在手工拼的字节上成立。
        //    ⚠️ 前提：① 那条断言里**不能出现那串字面量**，否则这里会搜到自己的 dex
        //      （见 ① 的注释）—— 这条与 ① 是一对，改一个必须看另一个。
        try {
            File self = new File(ctx.getApplicationInfo().sourceDir);
            Compat.Probe rp = Compat.probe(self);
            L.add("    真机样本（本应用 APK）= " + rp.brief());
            ok(stat, L, rp.dexCount > 0, "读到 dex（" + rp.dexCount + " 个）");
            ok(stat, L, !rp.hasEntry, "本应用 APK 里没有 " + Compat.ENTRY_DESC);
            ok(stat, L, rp.abi == null && rp.coreLib == null,
                    "本应用 APK 里没有 lib/ ⇒ ABI 与 native 库都取不到（abi=" + rp.abi
                            + " lib=" + rp.coreLib + "）");
            ok(stat, L, !rp.runnable(), "本应用 APK 判为不可进管线（它确实不是游戏包）");
        } catch (Throwable t) {
            ok(stat, L, false, "真实样本探测异常：" + t);
        }
        L.add("");
    }

    /**
     * ★ ⑬ F0：真隔离模型的**路径解析**与**"本体住哪"判定**（2026-10-02）。
     *
     * F0 的承诺是两句：运行期切槽不再搬文件（属性注入），远古包仍能跑（回退改名交换）。
     * "游戏真的读到了被注入的目录"只能靠真机跑一局来证明；但它的**前提**可以在这里
     * 用只读 + 纯函数钉死：三个路径角色真的分开了、`dirOf` 的三条分支真的分岔、
     * `legacyClaim` 真的对"files/ 有没有内容"敏感。
     * 这几条一旦失守，真机上的表现是**"用户的存档看起来没了"**（文件其实还在
     * 另一个目录里）—— 代价极高，所以值得用一堆断言守着。
     *
     * ⚠️ 全程不写用户的槽、不搬 `files/`：只读现有布局 + 在 `app_hub/` 里开一个临时目录，
     *    最后一律删掉。
     */
    private static void dataDirModel(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑬ F0 数据隔离模型（真隔离 vs 改名交换） ──");

        File ext = Paths.externalRoot(ctx);
        File parent = Paths.slotParent(ctx);
        String cur = Data.currentSlot(ctx);

        // ① 三个路径角色**真的分开了**（F0 的核心：以前 externalRoot 一个人演三个）
        ok(stat, L, ext != null && parent != null && parent.equals(ext.getParentFile()),
                "slotParent = externalRoot 的父目录（" + path(parent) + "）");
        File sd = Paths.slotDir(ctx, "f0-probe");
        ok(stat, L, sd != null && sd.getParentFile().equals(parent),
                "slotDir 住在 slotParent 下（" + path(sd) + "）");
        ok(stat, L, sd != null && sd.getName().equals(Paths.SLOT_PREFIX + "f0-probe"),
                "slotDir 名 = 「" + Paths.SLOT_PREFIX + "」+ 槽名");

        // ② ★★ 与改造前唯一的**结构性**差别：槽**不在** files/ 里面。
        //    （改造前"当前槽"就是 files/ 本体 ⇒ 同时只能一个槽在线。）
        ok(stat, L, sd != null && !insideOf(sd, ext),
                "槽目录**不在** externalRoot(files/) 内 —— 这正是 F0 与「改名交换」的分界");
        ok(stat, L, insideOf(new File(ext, "saves"), ext),
                "元断言：insideOf 有分辨力（files/saves 判为「在里面」）");

        // ③ 常量同源（防回到"两处各写一份字面量"—— 本项目已多次栽在这类重复实现上）
        ok(stat, L, Data.SLOT_SHARED.equals(Paths.SLOT_SHARED)
                        && Data.SLOT_DEFAULT.equals(Paths.SLOT_DEFAULT)
                        && Data.SLOT_PREFIX.equals(Paths.SLOT_PREFIX),
                "Data 的槽常量是 Paths 的**别名**（同一份字面量）");
        ok(stat, L, cur.equals(Paths.readSlotName(ctx)),
                "槽名唯一来源：Paths.readSlotName == Data.currentSlot（" + cur + "）");

        // ④ dirOf 三条分支（纯读，不碰盘）
        ok(stat, L, Data.dirOf(ctx, Data.SLOT_SHARED).equals(ext),
                "dirOf(shared) = externalRoot");
        ok(stat, L, Data.dirOf(ctx, "f0-probe").equals(sd),
                "dirOf(普通槽) = slotDir(该槽)");
        ok(stat, L, !Data.dirOf(ctx, Data.SLOT_SHARED).equals(Data.dirOf(ctx, "f0-probe")),
                "元断言：shared 与普通槽解析到**不同**路径（分支真有分辨力）");
        ok(stat, L, Data.dirOf(ctx, null).equals(Data.dirOf(ctx, cur)),
                "dirOf(null) = dirOf(当前槽)");

        // ⑤ legacyClaim 纯函数：判定与执行分开，这几条**完全不碰盘**
        ok(stat, L, Data.legacyClaim(null, false, "a") == null,
                "统一布局（无标记 + files/ 无内容）⇒ 没有要收回的本体");
        ok(stat, L, "a".equals(Data.legacyClaim(null, true, "a")),
                "老布局（无标记 + files/ 有内容）⇒ 本体归**当前槽** a");
        ok(stat, L, "b".equals(Data.legacyClaim("b", false, "a")),
                "远古包标记 b ⇒ 即使 files/ 空，本体也算 b（**标记说了算**）");
        ok(stat, L, Data.legacyClaim(Data.SLOT_SHARED, true, "a") == null,
                "shared：files/ 本来就是它的根 ⇒ 绝不搬动");
        ok(stat, L, "shared2".equals(Data.legacyClaim("shared2", true, "a")),
                "元断言：「shared」是**精确**保留值（shared2 不受影响 ⇒ 实现不是 startsWith）");
        ok(stat, L, (Data.legacyClaim(null, true, "a") == null)
                        != (Data.legacyClaim(null, false, "a") == null),
                "元断言：legacyClaim 对「files/ 有没有内容」敏感（不是恒返回同一个）");

        // ⑥ ★★ 核心不变式（对**任意布局**都成立的等价式，四条一起判）：
        //      「`dirOf(某槽)` 指向 `files/`」 ⟺ 「该槽是 shared，或它就是 legacyClaim 认下的本体」
        //    两边任一实现写错，这里立刻红。这才是"存档不会看起来没了"的防线：
        //      · 漏了标记分支 ⇒ 远古包/老布局的存档会被算成空槽（右假左真 → 红）；
        //      · 标记分支写宽 ⇒ 普通槽被误指到 files/（左真右假 → 红）。
        String marker = Data.legacyOwner(ctx);
        boolean extHas = !Data.contentRootsOf(ext).isEmpty();
        String claim = Data.legacyClaim(marker, extHas, cur);
        L.add("    当前布局：marker=" + marker + "  files/有内容=" + extHas
                + "  认定本体=" + (claim == null ? "各槽自己的 slot-*" : claim + "（在 files/）"));
        for (String probe : new String[]{"f0-probe", cur, Data.SLOT_SHARED, Data.SLOT_DEFAULT}) {
            boolean atExt = ext.equals(Data.dirOf(ctx, probe));
            boolean should = Data.SLOT_SHARED.equals(probe) || probe.equals(claim);
            ok(stat, L, atExt == should,
                    "不变式：槽「" + probe + "」→ files/ ？ 实得 " + atExt + "，应为 " + should);
        }

        // ⑦ contentRootsOf 口径（"空壳不算内容"）：**同一个目录里**同时放"该拦的"与"该留的"，
        //    一条断言判两个方向 —— 只验一个方向的话，"排除名单没生效"与"过度过滤"都看不出来。
        //    临时目录开在 app_hub 下，与用户数据无关，用完即删。
        File tmp = new File(Paths.privateDir(ctx), "selftest-contentroot");
        deleteTree(tmp);
        try {
            write(new File(tmp, "cache/x.bin"), new byte[]{1, 2, 3});   // SLOT_EXCLUDE
            write(new File(tmp, "half.msav.part"), new byte[]{1});      // 写一半的
            write(new File(tmp, ".nomedia"), new byte[]{1});            // 点文件
            write(new File(tmp, "empty.bin"), new byte[0]);             // 零字节
            new File(tmp, "emptydir").mkdirs();                         // 空目录
            List<File> before = Data.contentRootsOf(tmp);
            ok(stat, L, before.isEmpty(),
                    "只有该拦的（cache/ *.part 点文件 零字节 空目录）⇒ 零内容（实得 "
                            + before.size() + " 项 [" + namesOf(before) + "]）");

            write(new File(tmp, "keep/mod.json"), new byte[]{'{', '}'});
            List<File> got = Data.contentRootsOf(tmp);
            ok(stat, L, got.size() == 1 && got.get(0).getName().equals("keep"),
                    "同一目录里加入 keep/ 后：只留下它，前面那些仍被拦（实得 "
                            + got.size() + " 项 [" + namesOf(got) + "]）");
        } catch (IOException e) {
            ok(stat, L, false, "contentRootsOf 用例 IO 异常：" + e);
        } finally {
            deleteTree(tmp);
        }
        L.add("");
    }

    /**
     * ★ ⑭ F4② 克隆槽（2026-10-02）。被试用真原语 {@link Backup#cloneSlot} ——
     * 界面（SavesActivity.doClone）调的就是它，所以这里过 = 用户按下去就过。
     *
     * ★★ 为什么必须**逐字节**比对，而不是"文件数对得上"：
     *   克隆走的是 `Backup.create` + `restore`，它的口径（排除名单 / 空壳判定 / `.part` /
     *   点文件）与"能看见几项"完全是两回事。只要有一条口径漏了，克隆出来的槽就是
     *   **少几个文件**，而界面上报的数字（`Data.slotInfo` 的计数）照样对得起 ——
     *   那种缺陷只有逐文件哈希才抓得住。
     *
     * ★ 为什么**自建**源槽、不用 {@link #SLOT}：那个槽被 ②③④⑤… 反复写过，
     *   "里面到底该有哪些文件"只能靠复述别的用例的行为 ⇒ 期望值会随别的用例漂移
     *   （改一个用例就可能让这条断言从"真检测"退化成"恒真"）。自建的这一份
     *   **期望集合是写死的**，谁也动不了它。
     *
     * ★ 负例三条，共同的失败模式都是**静默写坏**，所以除了"必须报错"，
     *   还都断言了"报错就真的什么都没动"（`create` 之前返回 ⇒ 源槽备份数不变）。
     */
    private static void cloneSlot(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑭ F4② 克隆槽（逐字节一致 + 三条负例） ──");
        String nonce = Long.toHexString(System.currentTimeMillis());
        File src = Data.slotDir(ctx, CLONE_SRC);

        String e = Data.createSlot(ctx, CLONE_SRC);
        ok(stat, L, e == null, "建源槽「" + CLONE_SRC + "」" + (e == null ? "" : "：" + e));
        try {
            // 【该进新槽】常规存档 / 顶层文件 / 模组 / 两层目录
            write(new File(src, "saves/a.msav"), gzip(("clone-A-" + nonce).getBytes("UTF-8")));
            write(new File(src, "settings.bin"), ("clone-settings-" + nonce).getBytes("UTF-8"));
            write(new File(src, "mods/x.jar"), ("clone-mod-" + nonce).getBytes("UTF-8"));
            write(new File(src, "nested/deep/y.json"), ("clone-nested-" + nonce).getBytes("UTF-8"));
            // 【不该进新槽】顶层排除名单 / 通用跳过项 / 空目录
            write(new File(src, "cache/skip.bin"), ("clone-cache-" + nonce).getBytes("UTF-8"));
            write(new File(src, "config.json"), ("{\"c\":" + nonce + "}").getBytes("UTF-8"));
            write(new File(src, "saves/half.msav.part"), ("clone-half-" + nonce).getBytes("UTF-8"));
            write(new File(src, ".hidden/h.bin"), ("clone-dot-" + nonce).getBytes("UTF-8"));
            new File(src, "emptydir").mkdirs();
        } catch (Exception ex) {
            ok(stat, L, false, "铺克隆源数据失败：" + ex);
            L.add("");
            return;
        }

        // 期望：只有"该进备份的"4 条（相对槽根、/ 分隔）—— 写死，不复用别的用例的结论
        java.util.TreeMap<String, String> want = new java.util.TreeMap<>();
        want.put("mods/x.jar", Util.md5(new File(src, "mods/x.jar")));
        want.put("nested/deep/y.json", Util.md5(new File(src, "nested/deep/y.json")));
        want.put("saves/a.msav", Util.md5(new File(src, "saves/a.msav")));
        want.put("settings.bin", Util.md5(new File(src, "settings.bin")));

        java.util.TreeMap<String, String> srcBefore = treeMd5(src);
        int srcBeforeCount = srcBefore.size();

        // ★ 元断言**先跑**：先证明这把尺子有分辨力，再拿它去量东西。
        //   否则下面所有"内容相同"都可能只是**恒真** —— 一个对内容不敏感的
        //   `treeMd5` 会让整条用例从"检测"退化成"装饰"，而它照样显示 ✅（REF §35.13）。
        try {
            write(new File(src, "settings.bin"), ("tampered-" + nonce).getBytes("UTF-8"));
            ok(stat, L, !treeMd5(src).equals(srcBefore),
                    "元断言：treeMd5 对内容改动敏感（改一个字节就判不等）");
            // 复原（下面的断言与收尾都指望源槽是它本来那份）
            write(new File(src, "settings.bin"), ("clone-settings-" + nonce).getBytes("UTF-8"));
            ok(stat, L, treeMd5(src).equals(srcBefore), "元断言后源槽已复原（尺子复位）");
        } catch (Exception ex) {
            ok(stat, L, false, "元断言写入失败：" + ex);
        }

        int backupsBefore = Backup.list(ctx, CLONE_SRC).size();
        String err = Backup.cloneSlot(ctx, CLONE_SRC, CLONE_DST, "selftest-clone");
        ok(stat, L, err == null, "克隆 " + CLONE_SRC + " → " + CLONE_DST
                + (err == null ? "" : "：" + oneLine(err)));

        File dst = Data.slotDir(ctx, CLONE_DST);
        java.util.TreeMap<String, String> got = treeMd5(dst);

        // ★ 正例①：目标树 == 期望集合（**两个方向**一起判 ——
        //   "少了"是漏口径，"多了"是过滤失效，只验一个方向看不出另一个）
        ok(stat, L, got.equals(want),
                "★ 新槽内容与期望**逐字节一致**（" + got.size() + " / 应为 " + want.size()
                        + " 个文件；实得 [" + got.keySet() + "]）");
        for (String rel : want.keySet()) {
            String g = got.get(rel);
            ok(stat, L, want.get(rel).equals(g),
                    "  · " + rel + " 内容相同（" + (g == null ? "缺失" : "md5 一致") + "）");
        }
        ok(stat, L, !got.containsKey("cache/skip.bin")
                        && !got.containsKey("config.json")
                        && !got.containsKey("saves/half.msav.part")
                        && !got.containsKey(".hidden/h.bin"),
                "被排除的四项一项都没跟过来（cache/ · config.json · *.part · 点文件）");
        ok(stat, L, !dst.equals(src) && !insideOf(dst, src) && !insideOf(src, dst),
                "新槽是**另一个**目录，不与源槽嵌套");

        // ★ 正例②：源槽**整棵树**（含被排除项）在克隆后一字未改 —— 界面上承诺的"源槽没有被动过"
        ok(stat, L, treeMd5(src).equals(srcBefore),
                "★ 源槽整棵树未被改动（" + srcBeforeCount + " 个文件，md5 全同）");
        // 克隆会在源槽留一份中间快照（有意），所以这里断言的是"**恰好**多了一份"
        ok(stat, L, Backup.list(ctx, CLONE_SRC).size() == backupsBefore + 1,
                "源槽备份数 +1（中间快照，有意保留：可再恢复一次）");

        // ── 负例①：目标名已存在 ⇒ 报错，且**不许碰**已有的那个槽 ──
        java.util.TreeMap<String, String> dstBefore = treeMd5(dst);
        String e1 = Backup.cloneSlot(ctx, CLONE_SRC, CLONE_DST, "selftest-clone");
        ok(stat, L, e1 != null, "负例：目标已存在 ⇒ 报错（" + oneLine(e1) + "）");
        ok(stat, L, treeMd5(dst).equals(dstBefore), "负例：目标槽内容未被覆盖/合并");

        // ── 负例②：目标名 = 当前槽 ⇒ 报错，且**一个字节都不许写** ──
        //   （`Data.createSlot` 对当前槽是**返回成功**的！所以这道判断必须由
        //    `cloneSlot` 自己补 —— 漏了就会把源槽内容**合并进当前槽**，两套数据再也分不开。）
        String cur = Data.currentSlot(ctx);
        int curFiles = Data.countTree(Data.dirOf(ctx, cur));
        int backupsBefore2 = Backup.list(ctx, CLONE_SRC).size();
        String e2 = Backup.cloneSlot(ctx, CLONE_SRC, cur, "selftest-clone");
        ok(stat, L, e2 != null, "负例：目标是当前槽 ⇒ 报错（" + oneLine(e2) + "）");
        ok(stat, L, Data.countTree(Data.dirOf(ctx, cur)) == curFiles,
                "负例：当前槽文件数未变（" + curFiles + "）—— 没有被混入源槽内容");
        ok(stat, L, Backup.list(ctx, CLONE_SRC).size() == backupsBefore2,
                "负例：在**建快照之前**就返回了（源槽备份数未变 ⇒ 真的什么都没写）");

        // ── 负例③：源槽是空的 ⇒ 报错（界面另有一道更早的 `s.files == 0` 提示） ──
        String e3 = Data.createSlot(ctx, CLONE_EMPTY);
        ok(stat, L, e3 == null, "建空源槽「" + CLONE_EMPTY + "」");
        String e4 = Backup.cloneSlot(ctx, CLONE_EMPTY, CLONE_EMPTY_DST, "selftest-clone");
        ok(stat, L, e4 != null,
                "负例：源槽为空 ⇒ 报错，而不是「静默认成功」（" + oneLine(e4) + "）");
        L.add("");
    }

    /**
     * 整棵树里**每个文件**的 rel（`/` 分隔）→ md5。**不做任何排除** ——
     * 它的用途是"这棵树有没有被改动"，而不是"哪些算槽内容"（那是
     * {@link Data#contentRoots} 的职责）。两件事用同一个函数就必然分岔。
     */
    private static java.util.TreeMap<String, String> treeMd5(File root) {
        java.util.TreeMap<String, String> m = new java.util.TreeMap<>();
        collectMd5(root, root, m);
        return m;
    }

    private static void collectMd5(File root, File dir, java.util.TreeMap<String, String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                collectMd5(root, f, out);
            } else {
                out.put(root.toURI().relativize(f.toURI()).getPath(), Util.md5(f));
            }
        }
    }

    /** `child` 是否落在 `ancestor` 目录**里面**（按绝对路径 + 分隔符判，避开 `/a/bc` 这种伪命中） */
    private static boolean insideOf(File child, File ancestor) {
        if (child == null || ancestor == null) return false;
        String c = child.getAbsolutePath();
        String a = ancestor.getAbsolutePath();
        if (a.endsWith(File.separator)) a = a.substring(0, a.length() - 1);
        return c.startsWith(a + File.separator);
    }

    /** 把文件清单压成 `a, b, c` 供断言文案使用 */
    private static String namesOf(List<File> fs) {
        StringBuilder sb = new StringBuilder();
        for (File f : fs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.getName());
        }
        return sb.toString();
    }

    /**
     * 手工拼一个最小 ELF64：**1 个 PT_LOAD**（p_align = align）
     * + **1 个 PT_GNU_STACK**（故意给 align * 4 的更大值）。
     * ⇒ 正确实现只看 PT_LOAD；若写成"所有段取最大"，返回值会变成 align*4 而被断言抓住。
     */
    private static byte[] fakeElf64(long align) {
        int phoff = 64, phentsize = 56, phnum = 2;
        byte[] d = new byte[phoff + phentsize * phnum];
        d[0] = 0x7f; d[1] = 'E'; d[2] = 'L'; d[3] = 'F';
        d[4] = 2;    // ELFCLASS64
        d[5] = 1;    // ELFDATA2LSB（小端）
        d[6] = 1;    // EV_CURRENT
        put(d, 0x20, 8, phoff);        // e_phoff
        put(d, 0x36, 2, phentsize);    // e_phentsize
        put(d, 0x38, 2, phnum);        // e_phnum
        put(d, phoff, 4, 1);                            // ph0.p_type = PT_LOAD
        put(d, phoff + 0x30, 8, align);                 // ph0.p_align
        put(d, phoff + phentsize, 4, 0x6474e551L);      // ph1.p_type = PT_GNU_STACK
        put(d, phoff + phentsize + 0x30, 8, align * 4); // ph1.p_align（更大，必须被忽略）
        return d;
    }

    /** 手工拼一个最小 ELF32（偏移是**另一套**：e_phoff @0x1C，p_align @+0x1C） */
    private static byte[] fakeElf32(long align) {
        int phoff = 52, phentsize = 32, phnum = 1;
        byte[] d = new byte[phoff + phentsize * phnum];
        d[0] = 0x7f; d[1] = 'E'; d[2] = 'L'; d[3] = 'F';
        d[4] = 1;    // ELFCLASS32
        d[5] = 1;
        d[6] = 1;
        put(d, 0x1C, 4, phoff);
        put(d, 0x2A, 2, phentsize);
        put(d, 0x2C, 2, phnum);
        put(d, phoff, 4, 1);            // PT_LOAD
        put(d, phoff + 0x1C, 4, align);
        return d;
    }

    /** 小端写入：把 v 的低 n 字节写到 d[off..] */
    private static void put(byte[] d, int off, int n, long v) {
        for (int i = 0; i < n; i++) d[off + i] = (byte) ((v >>> (8 * i)) & 0xff);
    }

    // ══ ⑮ F13 模组管理（只读扫描）═══════════════════════════════════════════

    /**
     * ⑮ 的三块：**纯函数**（键名 / 版本门 / HJSON 两档语义）、**settings.bin 读**（含三条负例）、
     * **扫描端到端**（自建测试槽里铺 6 种形态的包 + settings.bin + launchid.dat）。
     *
     * ⚠️ 全程**只读扫描**：产品代码一个字节都不写（启停要写 settings.bin，那是下一阶段的事）；
     *    落盘的只有自检自己铺的测试槽，跑完删掉。
     */
    private static void modsPipeline(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑮ F13 模组扫描（HJSON 两档 / settings.bin / 扫描与判定；全部只读）──");
        modsPure(L, stat);
        settingsRoundtrip(ctx, L, stat);
        modsScan(ctx, L, stat);
        L.add("");
    }

    private static void modsPure(List<String> L, int[] stat) {
        L.add("  · 键名判据（去色 → internalName）：模组开关的键就是这两步算出来的");

        ok(stat, L, "Terraform".equals(Mods.stripColors("[red]Terraform")),
                "去色：[red]Terraform → Terraform");
        ok(stat, L, "X".equals(Mods.stripColors("[#ff0000]X")),
                "去色：[#ff0000]X → X（十六进制标记）");
        ok(stat, L, "x".equals(Mods.stripColors("[]x")),
                "去色：[]x → x（弹栈标记）");
        ok(stat, L, "A".equals(Mods.stripColors("[accent]A")),
                "去色：[accent]A → A（Mindustry 自己注册的颜色名也要认，否则键名算错）");
        ok(stat, L, "a[b]c".equals(Mods.stripColors("a[b]c")),
                "★元断言：b 不是颜色名 ⇒ 原样保留（证明不是「见括号就删」）");
        ok(stat, L, "[[x".equals(Mods.stripColors("[[x")),
                "★元断言：[[ 不被吃掉（arc 的 -2 分支在这条路上等价于「不是标记」）");
        ok(stat, L, "terraform--blue".equals(Mods.internalNameOf("Terraform  Blue")),
                "internalName：小写 + 每个空格换 -（两个空格 ⇒ 两个 -，与游戏的 replace 一致）");
        ok(stat, L, "蓝钢拓展".equals(Mods.internalNameOf("蓝钢拓展")),
                "internalName：中文原样保留（键名走 modified-UTF-8）");
        ok(stat, L, "[red]x".equals(Mods.internalNameOf("[red]X")),
                "★元断言：internalNameOf 自己**不去色** —— 去色是上一步的事，"
                        + "两步混起来就看不出「该去色的地方没去」");

        L.add("  · 版本门（照抄 Version.java:64 的 isAtLeast）");
        ok(stat, L, Mods.isAtLeast(160, 4, "160.1"), "160.4 ≥ 160.1");
        ok(stat, L, !Mods.isAtLeast(160, 0, "160.1"), "★160.0 < 160.1（补丁号真的参与比较）");
        ok(stat, L, Mods.isAtLeast(160, 0, "160"), "160.0 ≥ 160");
        ok(stat, L, !Mods.isAtLeast(159, 7, "160"), "159.7 < 160");
        ok(stat, L, Mods.isAtLeast(0, 0, "999"),
                "★边界：build ≤ 0（自定义构建）一律放行（Version.java:65）");
        ok(stat, L, Mods.isAtLeast(160, 0, "abc"),
                "★边界：解析不出的值当 0 ⇒ 放行（Strings.parseInt(x,0) 的语义）");
        int[] br = Mods.parseBuild("159.7");
        ok(stat, L, br[0] == 159 && br[1] == 7, "parseBuild(\"159.7\") = {159,7}");
        ok(stat, L, Mods.minMajorOf("154.1") == 154 && Mods.minMajorOf(null) == 0,
                "minMajorOf：154.1→154；null→0");
        ok(stat, L, Mods.isBlacklisted("schema", "1.0")
                        && Mods.isBlacklisted("scheme-size", "1.0.5"),
                "黑名单：schema / scheme-size:1.0.5 命中");
        ok(stat, L, !Mods.isBlacklisted("Schema", "1.0"),
                "★元断言：黑名单是精确匹配且区分大小写（不是「像就拦」）");

        L.add("  · HJSON 解析器：**替哪个版本解析**是两回事（arc 159 与 160 语义不同）");
        String two = "name: QX4   // ZZC\nversion: 4.6.0";
        String v159 = hjsonName(two, Hjson.ARC_159);
        String v160 = hjsonName(two, Hjson.ARC_160);
        ok(stat, L, "QX4   // ZZC".equals(v159),
                "ARC_159：注释**并进**无引号值（HJSON 规范行为，实得 " + v159 + "）");
        ok(stat, L, "QX4".equals(v160), "ARC_160：注释终止无引号值（实得 " + v160 + "）");
        ok(stat, L, v159 != null && !v159.equals(v160),
                "★两档必须**分家** —— 相同就说明 profile 开关没生效（等于只有一档）");
        ok(stat, L, hjsonName("name: bdc\n\"description\": \"line one\nline two\"\n",
                        Hjson.ARC_160) != null,
                "★引号内裸换行：接受（官方 hjson-java 在这里拒绝 ⇒ 17 个真实模组会误判 1 个）");
        ok(stat, L, hjsonName("{name: QX1}", Hjson.ARC_159) == null,
                "ARC_159：单行对象解析失败（照抄 arc 159.7）");
        ok(stat, L, "QX1".equals(hjsonName("{name: QX1}", Hjson.ARC_160)),
                "ARC_160：单行对象能解析（arc 160 把 } 并进了终止集）");
        String empty = "description: '''\n'''";
        ok(stat, L, failsAsParseError(empty, Hjson.ARC_159)
                        && failsAsParseError(empty, Hjson.ARC_160),
                "★空多行：两档都**判失败**（收敛成 ParseException，不逃逸 StringIndexOutOfBounds）");
        // ★★ BOM 与 `=`：**不是解析错误**，而是"解析成功但拿不到字段"——
        //   实测（本地跑 arc 自己的 Jval 两种档位，真机同一夹具复现）：
        //   `\ufeffname: X` 解成 `{"\ufeffname":"X"}`（BOM 成了**键名的一部分**），
        //   `name = X` 解成**裸字符串**而不是对象。
        //   游戏侧 `json.fromJson(ModMeta.class, …)` 因此拿不到 name ⇒ 静默跳过该模组。
        //   🔴 断言必须**同时**要求"没抛异常"和"取不到 name"：只写 `hjsonName(...) == null`
        //      是不够的 —— 解析失败也会返回 null，那条断言就变成**恒真**（§35.13 同族的假阴性）。
        String bomJson = hjsonText("\ufeffname: X", Hjson.ARC_160);
        ok(stat, L, bomJson != null && bomJson.indexOf("\ufeffname") >= 0
                        && nameOfJson(bomJson) == null,
                "★BOM：解析**成功**、BOM 留在键名里（"
                        + oneLine(bomJson.replace("\ufeff", "\\ufeff"))
                        + "）⇒ 取不到 name（游戏会静默跳过它）");
        ok(stat, L, hjsonName("name = X", Hjson.ARC_160) == null
                        && !failsAsParseError("name = X", Hjson.ARC_160),
                "★`=` 分隔符：解析**成功**但结果是裸字符串（不是对象）⇒ 同样取不到 name");
        ok(stat, L, failsAsParseError("author: [red]Me", Hjson.ARC_160),
                "★无引号的值以 [ 开头 ⇒ 当**数组**解析、随即报错"
                        + "（真实模组的颜色码必须加引号，这条是踩过的坑）");
        ok(stat, L, !failsAsParseError("{\"name\":\"X\",\"version\":\"1\"}", Hjson.ARC_160),
                "严格 JSON 也吃（游戏侧本来就是同一段代码）");
        StringBuilder deep = new StringBuilder("name: ");
        for (int i = 0; i < 4000; i++) deep.append('[');
        ok(stat, L, noEscape(deep.toString(), Hjson.ARC_160),
                "★深嵌套不逃逸：只许成功或 ParseException，不许 StackOverflowError 之类跑出来");
    }

    private static void settingsRoundtrip(Context ctx, List<String> L, int[] stat) {
        L.add("  · settings.bin 读取（格式照抄 arc Settings.java；「恰好 EOF」就是「没坏」的判据）");
        File dir = new File(Paths.privateDir(ctx), "selftest-settings");
        deleteTree(dir);
        dir.mkdirs();
        try {
            Object[][] es = {
                    {"mod-蓝钢拓展-enabled", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.FALSE},
                    {"some-int", Integer.valueOf(SettingsBin.TYPE_INT), Integer.valueOf(42)},
                    {"some-long", Integer.valueOf(SettingsBin.TYPE_LONG), Long.valueOf(1234567890123L)},
                    {"some-float", Integer.valueOf(SettingsBin.TYPE_FLOAT), Float.valueOf(1.5f)},
                    {"some-string", Integer.valueOf(SettingsBin.TYPE_STRING), "值 with spaces 中文"},
                    {"some-binary", Integer.valueOf(SettingsBin.TYPE_BINARY), new byte[]{1, 2, 3, 4, 5}},
                    {"mod-x-repo", Integer.valueOf(SettingsBin.TYPE_STRING), "owner/repo"},
            };
            File f = new File(dir, "settings.bin");
            writeSettings(f, es, false);
            SettingsBin.Values v = SettingsBin.read(f);
            ok(stat, L, v.size() == es.length, "读回 " + v.size() + "/" + es.length + " 个键");
            ok(stat, L, !v.getBool("mod-蓝钢拓展-enabled", true),
                    "非 ASCII 键名 + bool 读回 false（键名走 modified-UTF-8）");
            ok(stat, L, v.getInt("some-int", 0) == 42
                            && "值 with spaces 中文".equals(v.getString("some-string", null)),
                    "int / string 值逐条一致");
            ok(stat, L, "owner/repo".equals(v.getString("mod-x-repo", null)), "string 键读回");
            ok(stat, L, "binary(5)".equals(v.typeOf("some-binary")),
                    "binary 长度正确（" + v.typeOf("some-binary") + "）");
            ok(stat, L, v.getBool("mod-不存在的模组-enabled", true),
                    "★键不存在 ⇒ 回落缺省 true（游戏语义：**键不存在 ≠ 禁用**）");

            File cf = new File(dir, "compressed.bin");
            writeSettings(cf, es, true);
            SettingsBin.Values cv = SettingsBin.read(cf);
            ok(stat, L, cv.compressed && cv.size() == es.length
                            && !cv.getBool("mod-蓝钢拓展-enabled", true),
                    "zlib 压缩变体也能读（compressed=" + cv.compressed + "）");

            File bad = new File(dir, "trailing.bin");
            writeSettings(bad, es, false);
            FileOutputStream app = new FileOutputStream(bad, true);
            try {
                app.write(0x7f);
            } finally {
                app.close();
            }
            ok(stat, L, readFails(bad),
                    "★元断言：尾部多一个字节 ⇒ 判失败（否则「恰好 EOF」只是装饰）");

            File zero = new File(dir, "zero.bin");
            FileOutputStream zo = new FileOutputStream(zero);
            try {
                new java.io.DataOutputStream(zo).writeInt(0);
            } finally {
                zo.close();
            }
            ok(stat, L, readFails(zero), "★元断言：count=0 ⇒ 判失败（游戏自己就把它当损坏文件）");

            File unk = new File(dir, "unknown.bin");
            writeSettings(unk, new Object[][]{{"k", Integer.valueOf(7), "x"}}, false);
            ok(stat, L, readFails(unk), "★元断言：未知类型 ⇒ 判失败");
        } catch (Throwable t) {
            ok(stat, L, false, "settings.bin 用例自身异常：" + t);
        } finally {
            deleteTree(dir);
        }
    }

    private static void modsScan(Context ctx, List<String> L, int[] stat) {
        L.add("  · 扫描端到端（自建测试槽 " + SLOT_MODS + "：6 种形态 + settings.bin + launchid.dat）");
        File slot = Data.slotDir(ctx, SLOT_MODS);
        if (slot == null) {
            ok(stat, L, false, "拿不到测试槽目录");
            return;
        }
        try {
            String e0 = Data.createSlot(ctx, SLOT_MODS);
            ok(stat, L, e0 == null, "建测试槽" + (e0 == null ? "" : "：" + e0));
            if (e0 != null) return;

            File mods = new File(slot, "mods");
            // ① 根级 jar：HJSON meta（裸值 + **带引号**的颜色码作者——不加引号会被当成数组，
            //    实测 arc 两种档位都报 "Name is not closed"）+ classes.dex + scripts/main.js
            zipMany(new File(mods, "steel.jar"),
                    new String[]{"mod.json", "classes.dex", "scripts/main.js"},
                    new String[]{"name: Steel\nversion: 1.2\nminGameVersion: 160.1\nauthor: \"[red]Me\"\njava: true\n",
                            "not-a-real-dex", "print('hi')"});
            // ② 套一层目录的 zip：resolveRoot 必须下潜一层（中文目录名 + 中文内部名）
            zipMany(new File(mods, "蓝钢拓展.zip"),
                    new String[]{"蓝钢/mod.hjson", "蓝钢/classes.dex"},
                    new String[]{"name: 蓝钢拓展\nversion: 3.7\nminGameVersion: 159.7\n", "x"});
            // ③ 目录形态（没有 classes.dex、没有 scripts ⇒ 纯数据模组）+ 一条必需依赖
            write(new File(new File(mods, "dirmod"), "mod.json"),
                    "name: Dir Mod\nversion: \"0.32\"\ndependencies: [steel]\n".getBytes("UTF-8"));
            // ④ 坏 meta（空多行 ⇒ arc 自己也崩）—— 必须被报出来，而不是静默消失
            zipMany(new File(mods, "bad.zip"), new String[]{"mod.json"},
                    new String[]{"description: '''\n'''"});
            // ⑤ 两个包解出同一个 internalName（大小写不同、去色后同名）⇒ 冲突
            zipMany(new File(mods, "dup-a.zip"), new String[]{"mod.json"},
                    new String[]{"name: Twin\nversion: 1"});
            zipMany(new File(mods, "dup-b.zip"), new String[]{"mod.json"},
                    new String[]{"name: twin\nversion: 2"});
            // ⑥ 目录但没有 meta ⇒ 游戏**根本不看它**（不是"加载失败"，单独一类）
            new File(mods, "junkdir").mkdirs();

            writeSettings(new File(slot, "settings.bin"), new Object[][]{
                    {"mod-steel-enabled", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.FALSE},
                    {"mod-dir-mod-failed", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.TRUE},
                    {"mod-蓝钢拓展-repo", Integer.valueOf(SettingsBin.TYPE_STRING), "owner/repo"},
                    {"mod-ghost-enabled", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.FALSE},
                    {"modcrashdisable", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.FALSE},
            }, false);
            write(new File(slot, "launchid.dat"), "go away".getBytes("UTF-8"));

            Mods.Scan s = Mods.scan(ctx, SLOT_MODS);
            Mods.Resolved r = Mods.resolveDependencies(s.mods);

            // ★ 先把手上的事实**列出来**再断言 —— 真机上失败时报告里只有一句"实得 4"，
            //   根本不知道少的是哪一个（第一版就是这么栽的：`author: [red]Me` 没加引号，
            //   被 HJSON 当数组 ⇒ 那个包变成"读不出元数据"，但报告里看不出来）。
            L.add("    扫到的内部名 = " + internalNamesOf(s.mods)
                    + "；读不出元数据的 = " + fileNamesOf(s.broken));
            ok(stat, L, s.mods.size() == 5, "扫到 5 个可加载模组（实得 " + s.mods.size() + "）");
            ok(stat, L, s.broken.size() == 1 && s.broken.get(0).metaError != null,
                    "★坏 meta 被**报出来**（游戏侧是 catch 后静默跳过）："
                            + (s.broken.isEmpty() ? "无" : oneLine(s.broken.get(0).metaError)));
            // ★ 2026-10-04（第 86 轮）：给用户看的原因走 metaReason() —— 异常形态（`readOne` 的 catch
            //   写的是 `类名: 消息`，Hjson 那边还是**全限定名**）必须翻成白话；我们自己写的中文原样透传。
            Mods.Info techInfo = new Mods.Info();
            techInfo.metaError = "ParseException: io.mdt.launcher.Hjson$ParseException: boom";
            Mods.Info plainInfo = new Mods.Info();
            plainInfo.metaError = "包里没有说明文件（mod.json 之类）";
            ok(stat, L, !ModsText.infoMetaReason(ctx, techInfo).contains("Exception")
                            && !ModsText.infoMetaReason(ctx, techInfo).equals(techInfo.metaError)
                            && ModsText.infoMetaReason(ctx, plainInfo).equals(plainInfo.metaError),
                    "★原因翻译（Info.metaReason）：异常形态 ⇒ 「" + ModsText.infoMetaReason(ctx, techInfo)
                            + "」，中文判断原样透传（元断言：不是恒改）");
            ok(stat, L, s.ignored.contains("junkdir"),
                    "没有 meta 的目录进「游戏不加载」清单（与「加载失败」分开）");

            Mods.Info steel = findInfo(s.mods, "steel");
            Mods.Info blue = findInfo(s.mods, "蓝钢拓展");
            Mods.Info dir = findInfo(s.mods, "dir-mod");
            ok(stat, L, steel != null && blue != null && dir != null,
                    "三个模组的 internalName 都按游戏规则算出来了（steel / 蓝钢拓展 / dir-mod）");
            if (steel == null || blue == null || dir == null) return;

            ok(stat, L, steel.hasClassesDex && steel.hasScripts && steel.jsCount == 1 && steel.hasMainJs,
                    "zip 里的 classes.dex 与 scripts/main.js 都认出来了（**按前缀扫条目**，"
                            + "不能要求包里带 `scripts/` 目录条目）");
            ok(stat, L, "Me".equals(steel.author), "作者名里的颜色码被去掉（[red]Me → Me）");
            ok(stat, L, "mod.hjson".equals(blue.metaName),
                    "★套一层目录的 zip：下潜一层后命中 " + blue.metaName);
            ok(stat, L, "160.1".equals(steel.minGameVersion) && "159.7".equals(blue.minGameVersion),
                    "minGameVersion 读出来了（" + steel.minGameVersion + " / " + blue.minGameVersion + "）");

            // ★ 模组**类型**（用户要的那条提示）：判据是包里真实存在的东西
            ok(stat, L, steel.parts() == (Mods.JAVA_DEX | Mods.JS),
                    "★类型：根上既有 classes.dex 又有 scripts/ ⇒ JSON + JS + Java（dex）");
            ok(stat, L, blue.parts() == Mods.JAVA_DEX,
                    "★类型：只有 classes.dex ⇒ JSON + Java（dex）");
            ok(stat, L, dir.parts() == 0,
                    "★类型：两样都没有 ⇒ **只有元数据（JSON）**（不是坏模组）");
            // ★ "声明了 java 但包里没有 classes.dex" ⇒ 安卓会**跳过整个模组**（真会咬人的一条）
            File claim = new File(Paths.privateDir(ctx), "selftest-claim.zip");
            zipMany(claim, new String[]{"mod.json"}, new String[]{"name: Claimed\njava: true\n"});
            Mods.Info ci = Mods.readOne(claim);
            ok(stat, L, ci.parts() == 0 && ci.willFailJavaLoad() && !ci.hasClassesDex,
                    "★类型：只声明 java=true、包里没有 classes.dex ⇒ 类型里**不再冒充** Java，"
                            + "而是打「加载会失败」（安卓 DexClassLoader 找不到类 ⇒ 整个被跳过）");
            claim.delete();
            // ★ 桌面版 `.class` 与资源目录：都要认出来（但 .class 不算"能加载的 Java"）
            File cls = new File(Paths.privateDir(ctx), "selftest-class.zip");
            zipMany(cls, new String[]{"mod.json", "a/B.class", "bundles/x.properties"},
                    new String[]{"name: Cls Mod\njava: true\n", "x", "k=v"});
            Mods.Info clsi = Mods.readOne(cls);
            ok(stat, L, clsi.classFiles == 1 && (clsi.parts() & Mods.JAVA_CLASS) != 0
                            && (clsi.parts() & Mods.DATA) != 0 && clsi.willFailJavaLoad(),
                    "★类型：桌面版 .class + 资源都要认出来，且**仍算会加载失败**"
                            + "（classFiles=" + clsi.classFiles + "）");
            cls.delete();
            // ★ 元断言（反方向）：有 dex 时**不许**说会失败
            File dexed = new File(Paths.privateDir(ctx), "selftest-dexed.zip");
            zipMany(dexed, new String[]{"mod.json", "classes.dex", "bundles/x.properties"},
                    new String[]{"name: Dexed\njava: true\n", "dex", "k=v"});
            Mods.Info di = Mods.readOne(dexed);
            ok(stat, L, (di.parts() & Mods.JAVA_DEX) != 0 && !di.willFailJavaLoad()
                            && (di.parts() & Mods.DATA) != 0,
                    "★反向：同样的声明 + 包里有 classes.dex ⇒ **不**会失败（证明上一条不是恒真）");
            dexed.delete();
            // ★ 脚本入口：**只有一个 js 时用哪个都行**；两个以上才只认 main.js（照抄 loadScripts）
            File js1 = new File(Paths.privateDir(ctx), "selftest-js1.zip");
            zipMany(js1, new String[]{"mod.json", "scripts/only.js"},
                    new String[]{"name: One Script\n", "print(1)"});
            Mods.Info j1 = Mods.readOne(js1);
            ok(stat, L, j1.jsCount == 1 && !j1.hasMainJs && !j1.noMainScript(),
                    "★脚本入口：只有一个 js（哪怕不叫 main.js）⇒ **不算问题**（游戏向后兼容，就用那一个）");
            File js2 = new File(Paths.privateDir(ctx), "selftest-js2.zip");
            zipMany(js2, new String[]{"mod.json", "scripts/a.js", "scripts/b.js"},
                    new String[]{"name: Two Scripts\n", "print(1)", "print(2)"});
            Mods.Info j2 = Mods.readOne(js2);
            ok(stat, L, j2.jsCount == 2 && !j2.hasMainJs && j2.noMainScript(),
                    "★脚本入口：两个 js 却没有 main.js ⇒ 判「不会生效」（游戏只找 main.js）");
            File js3 = new File(Paths.privateDir(ctx), "selftest-js3.zip");
            zipMany(js3, new String[]{"mod.json", "scripts/main.js", "scripts/b.js"},
                    new String[]{"name: Two Scripts OK\n", "print(1)", "print(2)"});
            Mods.Info j3 = Mods.readOne(js3);
            ok(stat, L, j3.jsCount == 2 && j3.hasMainJs && !j3.noMainScript(),
                    "★反向：两个 js 且有 main.js ⇒ 不报（证明上一条不是恒真）");
            js1.delete(); js2.delete(); js3.delete();
            // ★ 多人游戏：hidden 必须解析出来（判据来自 Mods.java:950 / ModsDialog.java:369）
            File mp1 = new File(Paths.privateDir(ctx), "selftest-mp1.zip");
            zipMany(mp1, new String[]{"mod.json"}, new String[]{"name: MP Ok\nhidden: true\n"});
            File mp2 = new File(Paths.privateDir(ctx), "selftest-mp2.zip");
            zipMany(mp2, new String[]{"mod.json"}, new String[]{"name: MP Need\n"});
            Mods.Info mi1 = Mods.readOne(mp1);
            Mods.Info mi2 = Mods.readOne(mp2);
            ok(stat, L, mi1.hidden && mi1.multiSafe() && !mi2.hidden && !mi2.multiSafe(),
                    "★多人：hidden=true ⇒ 支持多人（不参与联机校验）；没写 ⇒ 联机需服务器同款同版本"
                            + "（`Mods.java:950` 只收 !hidden；`ModsDialog.java:369` 把 hidden 标成"
                            + " `mod.multiplayer.compatible`＝支持多人游戏）");
            mp1.delete();
            mp2.delete();
            // ★★ 反斜杠条目：**必须与游戏同判**（游戏当目录看 ⇒ 找不到 main.js），
            //    而且要能**说出原因**（否则用户看着包里的 main.js 一头雾水）。
            File bs = new File(Paths.privateDir(ctx), "selftest-backslash.zip");
            zipMany(bs, new String[]{"mod.json", "scripts\\main.js"},
                    new String[]{"name: BS Mod\nversion: 1\n", "print(1)"});
            Mods.Info bi = Mods.readOne(bs);
            ok(stat, L, bi.backslashEntries == 1 && !bi.hasScripts && bi.parts() == 0,
                    "★反斜杠条目：与游戏同判（当成目录 ⇒ 没有 scripts/、类型=数据模组），"
                            + "并记下 backslashEntries=" + bi.backslashEntries + " 供界面解释原因");
            File bs2 = new File(Paths.privateDir(ctx), "selftest-slash.zip");
            zipMany(bs2, new String[]{"mod.json", "scripts/main.js"},
                    new String[]{"name: SL Mod\nversion: 1\n", "print(1)"});
            Mods.Info bi2 = Mods.readOne(bs2);
            ok(stat, L, bi2.backslashEntries == 0 && bi2.hasScripts
                            && (bi2.parts() & Mods.JS) != 0,
                    "★元断言：同样两个条目、只把反斜杠换成斜杠 ⇒ 立刻认成 JS 脚本"
                            + "（证明上一条不是「永远认不出脚本」）");
            bs.delete();
            bs2.delete();

            ok(stat, L, !steel.enabled && dir.failed,
                    "settings.bin 的三个键族都生效（steel.enabled=false、dir.failed=true）");
            ok(stat, L, "owner/repo".equals(blue.settingsRepo),
                    "mod-<名>-repo 覆盖元数据里的 repo（检查更新的数据源）");
            ok(stat, L, s.orphanKeys.contains("mod-ghost-enabled"),
                    "孤儿设置键被检出（设置里有、包已不在）：" + s.orphanKeys);
            ok(stat, L, s.problems.size() == 1, "同名冲突被检出一次（实得 " + s.problems.size() + "）");
            Mods.Info dupA = findInfo(s.mods, "twin");
            ok(stat, L, dupA != null && dupA.duplicated,
                    "冲突双方都被标上 duplicated（游戏里只有一个会生效）");

            // ★ 元断言：skipModLoading = launchid.dat 存在 **且** modcrashdisable（缺省 true）
            ok(stat, L, s.launchIdExists && !s.skipModLoading,
                    "★元断言：launchid.dat 在、但 modcrashdisable=false ⇒ **不**跳过全部模组"
                            + "（证明它真的读了那个键，而不是只看文件在不在）");
            writeSettings(new File(slot, "settings.bin"), new Object[][]{
                    {"mod-steel-enabled", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.FALSE},
                    {"modcrashdisable", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.TRUE},
            }, false);
            Mods.Scan s2 = Mods.scan(ctx, SLOT_MODS);
            ok(stat, L, s2.skipModLoading,
                    "★同一个 launchid.dat、modcrashdisable 改成 true ⇒ 跳过全部模组（判据有分辨力）");

            // 版本门 + 状态（游戏口径）
            ok(stat, L, Mods.stateOf(steel, r, 159, 7) == Mods.State.UNSUPPORTED,
                    "★要求 160.1 的模组 + 目标 159.7 ⇒ UNSUPPORTED（这一条就是 F13 的头号卖点）");
            ok(stat, L, Mods.stateOf(steel, r, 160, 1) == Mods.State.DISABLED,
                    "版本够了但开关是 false ⇒ DISABLED");

            // ★★ 第 85 轮补的一条**漏判据**（真机上用同一份 JS 探针做对照抓到的，见 Mods.gates 注释）：
            //    非 Java（脚本 / 数据）模组的过期门槛是 **136**（`Vars.java:53`），
            //    而这里原来是 `m.isJava() && …` ⇒ **脚本模组整类漏判**：
            //    没写 `minGameVersion` 的 JS 模组被游戏判 unsupported（脚本永远不跑、
            //    日志里却照样打 `Loading mod: X`），我们这边却显示"启用"。
            Mods.Info scriptMod = new Mods.Info();
            scriptMod.name = "probe";
            scriptMod.internalName = "probe";
            scriptMod.enabled = true;
            scriptMod.minGameVersion = "0";
            Mods.Resolved none = Mods.resolveDependencies(new ArrayList<Mods.Info>());
            ok(stat, L, Mods.stateOf(scriptMod, none, 159, 7) == Mods.State.UNSUPPORTED,
                    "★脚本模组没写 minGameVersion（0 < " + Mods.MIN_MOD_GAME_VERSION
                            + "）⇒ 游戏口径 UNSUPPORTED（脚本永远不会跑）");
            scriptMod.minGameVersion = "136";
            ok(stat, L, Mods.stateOf(scriptMod, none, 159, 7) == Mods.State.ENABLED,
                    "★元断言：同一个模组写上 136（刚好够）⇒ ENABLED（判据不是「脚本模组一律判死」）");
            scriptMod.minGameVersion = "0";
            scriptMod.legacyCompatible = true;
            ok(stat, L, Mods.stateOf(scriptMod, none, 159, 7) == Mods.State.ENABLED,
                    "★legacyCompatible ⇒ 豁免这一条（照游戏那个 `&& !meta.legacyCompatible`）");
            scriptMod.legacyCompatible = false;
            ok(stat, L, !allGatesPass(ctx, scriptMod, 159, 7),
                    "★门禁那一关必须跟着判死 —— 否则徽标写「不支持」而正文一行依据都没有");
            scriptMod.minGameVersion = "136";
            ok(stat, L, allGatesPass(ctx, scriptMod, 159, 7),
                    "★元断言：写上 136 之后门禁**全过**（判据有分辨力，不是恒判死）");

            // 依赖：steel 被关闭 ⇒ 依赖它的 dirmod 连坐（游戏 Mods.java:1064 的 && !getBool）
            ok(stat, L, r.stateOf("dir-mod") == Mods.State.INCOMPLETE_DEPENDENCIES,
                    "必需依赖被关闭 ⇒ 连坐成 incompleteDependencies（实得 "
                            + ModsText.stateLabel(ctx, r.stateOf("dir-mod")) + "）");

            // 依赖解析的另外几条（纯函数，不落盘）
            List<Mods.Info> g = new ArrayList<>();
            g.add(mkMod("a", new String[]{"missing"}, new String[0], true));
            g.add(mkMod("d", new String[0], new String[]{"alsonope"}, true));
            g.add(mkMod("b", new String[]{"c"}, new String[0], true));
            g.add(mkMod("c", new String[]{"b"}, new String[0], true));
            g.add(mkMod("p", new String[0], new String[]{"q"}, true));
            g.add(mkMod("q", new String[]{"p"}, new String[0], true));
            Mods.Resolved gr = Mods.resolveDependencies(g);
            ok(stat, L, gr.stateOf("a") == Mods.State.MISSING_DEPENDENCIES,
                    "必需依赖不存在 ⇒ missingDependencies");
            ok(stat, L, gr.stateOf("d") == Mods.State.ENABLED,
                    "★软依赖不存在**不**算失效（依赖链里最容易写反的一条）");
            // 🔴 必依赖成环：游戏里 `invalid.put(dep, circular)` 随后**被 incomplete 覆盖**
            //    （Mods.java:1065 的 `invalid.put(element, incompleteDependencies)`）⇒ 两边都是
            //    incomplete，CIRCULAR **留不住**。这是照抄的结果，不是缺陷。
            ok(stat, L, gr.stateOf("b") == Mods.State.INCOMPLETE_DEPENDENCIES
                            && gr.stateOf("c") == Mods.State.INCOMPLETE_DEPENDENCIES,
                    "★必需依赖互相成环 ⇒ 双方都是 incomplete（CIRCULAR 被 incomplete 覆盖，"
                            + "实得 " + ModsText.stateLabel(ctx, gr.stateOf("b")) + " / "
                        + ModsText.stateLabel(ctx, gr.stateOf("c")) + "）");
            // ★ 只有**软**依赖那条路上，环才会留在 CIRCULAR（上一句不是「永远 incomplete」）
            ok(stat, L, gr.stateOf("p") == Mods.State.CIRCULAR_DEPENDENCIES,
                    "★元断言：软依赖成环（p -软-> q -必-> p）⇒ p 留在 CIRCULAR"
                            + "（实得 " + ModsText.stateLabel(ctx, gr.stateOf("p")) + "；证明上一条不是恒真）");
            List<Mods.Info> g2 = new ArrayList<>();
            g2.add(mkMod("x", new String[]{"y"}, new String[0], true));
            g2.add(mkMod("y", new String[0], new String[0], true));
            Mods.Resolved r2 = Mods.resolveDependencies(g2);
            ok(stat, L, r2.stateOf("x") == Mods.State.ENABLED
                            && r2.ordered.indexOf("y") < r2.ordered.indexOf("x"),
                    "依赖排在依赖者之前（加载顺序）：" + r2.ordered);
            List<Mods.Info> g3 = new ArrayList<>();
            g3.add(mkMod("x", new String[]{"y"}, new String[0], true));
            g3.add(mkMod("y", new String[0], new String[0], false));
            ok(stat, L, Mods.resolveDependencies(g3).stateOf("x")
                            == Mods.State.INCOMPLETE_DEPENDENCIES,
                    "★元断言：把 y 关掉 ⇒ x 必须连坐（证明上面那条不是恒真）");
        } catch (Throwable t) {
            ok(stat, L, false, "扫描端到端用例自身异常：" + t);
        } finally {
            // 测试槽**不在这里删** —— 由 runM3 的统一收尾（TEST_SLOTS）负责，
            // 否则报告里那句「N 个测试槽已清理」会少一个、看着像漏了。
        }
    }

    // ══ ⑯ F13 第二阶段：settings.bin 的安全改写 ═════════════════════════════

    private static void settingsWrite(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑯ F13 启停：settings.bin 安全改写（备份 / 原子写 / 写后自检 / 还原）──");
        File dir = new File(Paths.privateDir(ctx), "selftest-settings-write");
        deleteTree(dir);
        dir.mkdirs();
        Object[][] es = {
                {"mod-demo-enabled", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.TRUE},
                {"mod-中文模组-enabled", Integer.valueOf(SettingsBin.TYPE_BOOL), Boolean.FALSE},
                {"some-int", Integer.valueOf(SettingsBin.TYPE_INT), Integer.valueOf(42)},
                {"some-long", Integer.valueOf(SettingsBin.TYPE_LONG), Long.valueOf(1234567890123L)},
                {"some-float", Integer.valueOf(SettingsBin.TYPE_FLOAT), Float.valueOf(1.5f)},
                {"some-string", Integer.valueOf(SettingsBin.TYPE_STRING), "值 with spaces 中文"},
                {"some-binary", Integer.valueOf(SettingsBin.TYPE_BINARY), new byte[]{1, 2, 3, 4, 5}},
        };
        try {
            // ── ① 纯函数往返：读 → 编码 必须逐字节一致 ──────────────────────
            File f = new File(dir, "settings.bin");
            writeSettings(f, es, false);
            byte[] orig = SettingsBin.readAllBytes(f);
            SettingsBin.Values v = SettingsBin.read(f);
            ok(stat, L, java.util.Arrays.equals(orig, SettingsBin.encode(v)),
                    "① 读 → 编码**逐字节一致**（" + orig.length + " B / " + v.size() + " 键，"
                            + "含 binary 与非 ASCII 键名）");
            File cf = new File(dir, "compressed.bin");
            writeSettings(cf, es, true);
            SettingsBin.Values cv = SettingsBin.read(cf);
            ok(stat, L, cv.compressed
                            && java.util.Arrays.equals(SettingsBin.readAllBytes(cf), SettingsBin.encode(cv)),
                    "① zlib 变体也能逐字节往返（写回时沿用原件的压缩形态）");

            // ── ② **真机真实文件**：读 → 编码 → 与磁盘原文逐字节比对（只读！）────
            //    这条是"我们的编码器 == 游戏的写侧"最硬的证据，且**不碰任何用户数据**。
            File real = null;
            for (Data.Slot s0 : Data.allSlots(ctx)) {
                File cand = new File(s0.dir, "settings.bin");
                if (cand.isFile() && cand.length() > 0) {
                    real = cand;
                    break;
                }
            }
            if (real == null) {
                L.add("  （跳过②：设备上还没有任何真实 settings.bin —— 没跑过游戏）");
            } else {
                byte[] rb = SettingsBin.readAllBytes(real);
                SettingsBin.Values rv = SettingsBin.read(real);
                ok(stat, L, java.util.Arrays.equals(rb, SettingsBin.encode(rv)),
                        "② ★真实文件逐字节往返：" + real.getParentFile().getName() + "/settings.bin "
                                + rb.length + " B / " + rv.size() + " 键（只读，未改动）");
                // ★ 元断言：真实文件**尾部追加一个字节** ⇒ 必须判失败
                //   （「恰好 EOF」这条判据真的在工作 —— 第一版这里写的是"末尾翻一个 bit"，
                //    **那条断言是错的**：settings.bin **没有校验和**，翻一个 bit 只会把某个
                //    bool/数字的值改掉，结构依然合法、游戏照样能读 —— 是自检把这个
                //    "看着有判据其实没验到东西"的断言自己抓出来的。）
                File broken = new File(dir, "real-broken.bin");
                byte[] rb2 = new byte[rb.length + 1];
                System.arraycopy(rb, 0, rb2, 0, rb.length);
                rb2[rb.length] = 0x7f;
                write(broken, rb2);
                ok(stat, L, SettingsBin.readSafe(broken, null) == null,
                        "② ★元断言：真实文件**尾部追加 1 字节** ⇒ 读取必须判失败"
                                + "（「恰好 EOF」判据在工作）");
                // ★ 另一半：**截断**也必须被发现（最后一个值的字节不够 ⇒ EOFException）
                if (rb.length > 1) {
                    byte[] truncated = new byte[rb.length - 1];
                    System.arraycopy(rb, 0, truncated, 0, truncated.length);
                    write(broken, truncated);
                    ok(stat, L, SettingsBin.readSafe(broken, null) == null,
                            "② ★元断言：真实文件**截断 1 字节** ⇒ 读取必须判失败");
                }
            }

            // ── ③ 改一个键：备份 + 原子写 + 读回自检 ───────────────────────
            String e0 = Data.createSlot(ctx, SLOT_SET);
            ok(stat, L, e0 == null, "③ 建测试槽「" + SLOT_SET + "」" + (e0 == null ? "" : "：" + e0));
            if (e0 == null) {
                File slot = Data.slotDir(ctx, SLOT_SET);
                File sf = new File(slot, "settings.bin");
                writeSettings(sf, es, false);
                long lenBefore = sf.length();
                String md5Before = Util.md5(sf);
                File bdir = new File(dir, "backups");

                SettingsBin.Result r1 = SettingsBin.applyBool(sf, bdir, "mod-demo-enabled", false);
                SettingsBin.Values after = SettingsBin.readSafe(sf, null);
                ok(stat, L, r1.ok && r1.verified && after != null
                                && !after.getBool("mod-demo-enabled", true),
                        "③ 改一个已存在的键：写成功 + 自检通过 + 读回是新值");
                ok(stat, L, sf.length() == lenBefore && r1.diffBytes == 1,
                        "③ ★文件长度不变、**只差 1 个字节**（实得差 " + r1.diffBytes + " 字节）");
                ok(stat, L, r1.backup != null && r1.backup.isFile()
                                && md5Before.equals(Util.md5(r1.backup)),
                        "③ ★改前备份存在，且与改前文件 **md5 相同**");
                ok(stat, L, after != null && after.size() == v.size()
                                && SettingsBin.sameValues(after.with("mod-demo-enabled", Boolean.TRUE), v),
                        "③ ★除了那一个键，**其余键逐条一致**（未知键原样保留、顺序不变）");

                // 新键必须**追加在末尾**（挪位置 = 内容不变但文件整片不同）
                SettingsBin.Result r2 = SettingsBin.applyBool(sf, bdir, "mod-全新的-enabled", true);
                SettingsBin.Values v2 = SettingsBin.readSafe(sf, null);
                ok(stat, L, r2.ok && v2 != null && v2.size() == after.size() + 1
                                && v2.getBool("mod-全新的-enabled", false)
                                && "mod-全新的-enabled".equals(lastKeyOf(v2)),
                        "③ ★新键**追加在末尾**（已存在的键原位换值、新键不插队）");

                // ── ④ 负例：坏文件 ⇒ 拒绝改写、一个字节不动、不留备份 ──────
                File bad = new File(dir, "corrupt.bin");
                writeSettings(bad, es, false);
                byte[] bb = SettingsBin.readAllBytes(bad);
                byte[] bb2 = new byte[bb.length + 1];
                System.arraycopy(bb, 0, bb2, 0, bb.length);
                bb2[bb.length] = 0x7f;
                write(bad, bb2);
                String badMd5 = Util.md5(bad);
                int backupsBefore = countFiles(bdir);
                SettingsBin.Result rb = SettingsBin.applyBool(bad, bdir, "mod-demo-enabled", false);
                ok(stat, L, !rb.ok && rb.error != null,
                        "④ ★坏文件 ⇒ **拒绝改写**（不猜、不覆盖）：" + oneLine(rb.error));
                ok(stat, L, badMd5.equals(Util.md5(bad)),
                        "④ ★拒绝时目标文件**一个字节都没动**");
                ok(stat, L, countFiles(bdir) == backupsBefore,
                        "④ ★拒绝时**不产生备份**（没写就没得备，别在用户盘上留垃圾）");

                // ── ⑤ 元断言：写后自检的判据必须有分辨力 ──────────────────
                ok(stat, L, !SettingsBin.sameValues(v, v.with("mod-demo-enabled", Boolean.FALSE)),
                        "⑤ ★元断言：改一个值 ⇒ sameValues 必须判**不一致**");
                File fewer = new File(dir, "fewer.bin");
                writeSettings(fewer, new Object[][]{
                        es[0], es[1], es[2], es[3], es[4], es[5]}, false);   // 少了 some-binary
                ok(stat, L, !SettingsBin.sameValues(v, SettingsBin.read(fewer)),
                        "⑤ ★元断言：少一个键 ⇒ 必须判**不一致**");
                File retyped = new File(dir, "retyped.bin");
                Object[][] es2 = es.clone();
                es2[2] = new Object[]{"some-int", Integer.valueOf(SettingsBin.TYPE_STRING), "42"};
                writeSettings(retyped, es2, false);
                ok(stat, L, !SettingsBin.sameValues(v, SettingsBin.read(retyped)),
                        "⑤ ★元断言：同一个键换了类型 ⇒ 必须判**不一致**");
                ok(stat, L, SettingsBin.sameValues(v, v.copy()),
                        "⑤ ★反向：完全相同的两份必须判**一致**（否则判据写成恒 false 也能过）");
            }
        } catch (Throwable t) {
            ok(stat, L, false, "settings.bin 改写用例自身异常：" + t);
        } finally {
            deleteTree(dir);        // 这个目录在 app_hub 下，不经 TEST_SLOTS，自己清
        }
        // ★ 2026-10-04（第 86 轮）失败原因的白话翻译；★ 2026-10-05（P3）**改走「码 + 参数」**：
        //   核心（`SettingsBin`，纯 Java）只说"错在哪"，文案在 `SettingsText` 里按码取资源。
        //   🔴 这条断言原来要求"我们自己写的中文**原样透传**" —— 那正是被替换掉的旧设计。
        SettingsBin.Result tech = new SettingsBin.Result();
        tech.error = "IOException: boom";
        SettingsBin.Result plain = new SettingsBin.Result();
        plain.error = "游戏正在运行，现在改会被它覆盖 —— 请先退出游戏再改。";
        Context enSt = LocaleMode.force(ctx, "en");
        ok(stat, L, !SettingsText.userReason(ctx, tech).contains("Exception")
                        && !SettingsText.userReason(ctx, tech).equals(tech.error)
                        && !SettingsText.userReason(enSt, tech).equals(tech.error)
                        && SettingsText.userReason(ctx, plain).equals(plain.error),
                "★原因翻译（SettingsText.userReason）：异常形态 ⇒ 「"
                        + SettingsText.userReason(ctx, tech) + "」（中英都不是核心原文）");
        ok(stat, L, ctx.getString(R.string.settings_reason_unknown)
                        .equals(SettingsText.userReason(ctx, new SettingsBin.Result())),
                "★没有原因（码为 0 且 error 为空）时给「原因不明」");
        // ★★ P3：**遍历 `ALL_CODES`** —— 每个码都必须映射到一条真文案（不是"原因不明"那个兜底）。
        //    漏一条映射**不崩不报错**、界面只是**静默空白**，只有遍历才抓得住。
        StringBuilder missSt = new StringBuilder();
        boolean stRawLeak = false, stLocaleDiff = true;
        String stFallback = ctx.getString(R.string.settings_reason_unknown);
        for (int code : SettingsBin.Result.ALL_CODES) {
            SettingsBin.Result rr = SettingsBin.Result.withCode(code, "WHY", "BOOM");
            String rz = SettingsText.userReason(ctx, rr);
            String re = SettingsText.userReason(enSt, rr);
            if (rz == null || rz.isEmpty() || stFallback.equals(rz)) missSt.append(code).append(' ');
            if (rz.equals(re)) stLocaleDiff = false;
            // ★ **原始原因**（异常原文 / 我们 `read()` 里的中文）一个字都不许进第一层 ——
            //   那是"依据"，按硬规矩走 `report()`（「技术细节」第二层）。
            //   但**中性参数**（路径 / 槽名）可以进：`E_BACKUP_MKDIR` 的备份目录、
            //   `E_NO_SLOT_DIR` 的槽名 —— 它们不是"原因"，是"说的是哪个"。加码时照这条判。
            if (rz.contains("BOOM")) stRawLeak = true;
            boolean neutralParam = code == SettingsBin.Result.E_BACKUP_MKDIR
                    || code == SettingsBin.Result.E_NO_SLOT_DIR;
            if (!neutralParam && rz.contains("WHY")) stRawLeak = true;
        }
        ok(stat, L, missSt.length() == 0,
                "★P3：settings 那 12 个错误码**每一个**都有文案映射（漏映射=界面静默空白），漏的是［"
                        + missSt + "］");
        ok(stat, L, !stRawLeak && stLocaleDiff
                        && SettingsText.userReason(ctx, SettingsBin.Result.withCode(
                                SettingsBin.Result.E_BACKUP_MKDIR, "WHY", null)).contains("WHY")
                        && SettingsText.userReason(ctx, SettingsBin.Result.withCode(
                                SettingsBin.Result.E_NO_SLOT_DIR, "SLOT", null)).contains("SLOT"),
                "★P3：第一层只带**中性参数**（备份目录/槽名那两条进去了），"
                        + "**原始原因一个字都没进**（依据在 report() 里），且两套语言文案不同");
        // ★ 自检失败那条是**嵌套**的（外面一句 + 里面一句"具体原因"）⇒ 两个分支都要过一遍，
        //   否则"另一个分支永远显示同一句"这种错看不出来。
        SettingsBin.Result mSub = SettingsBin.Result.withCode(
                SettingsBin.Result.E_VERIFY_ROLLBACK, "WHY", null);
        mSub.subCode = SettingsBin.Result.SUB_MISMATCH;
        ok(stat, L, SettingsText.userReason(ctx, mSub)
                        .contains(ctx.getString(R.string.settings_selfcheck_mismatch))
                        && !SettingsText.userReason(ctx, mSub).contains("WHY"),
                "★P3：自检失败的**另一个分支**（键表不符）走另一句资源，且不夹带参数："
                        + "「" + SettingsText.userReason(ctx, mSub) + "」");
        L.add("");
    }

    /**
     * ㊲ **主进程崩溃落盘**（2026-10-04 第 86 轮新增的 {@link Crash}）。
     *
     * 为什么要有它：工程原来把"崩溃证据"这件事整个交给了**游戏**（`<数据根>/crashes/*.txt`
     * 是游戏写的），启动器自己崩了就只是"应用消失了"；而在日志页的「崩溃堆栈」里，
     * 这个目录**是要给用户看的**（"把崩溃报告导出来"那条路）—— 所以：
     *  ① 落点必须是 `<数据根>/crashes/`（否则用户按提示去看时什么都没有）；
     *  ② 文件名必须与游戏那份**同构**（`crash_<毫秒>…`）：日志页是按文件名倒序排的
     *     （注释写着"名字就是写入时的毫秒时间戳 ⇒ 字典序 = 时间序"），
     *     时间戳字段等宽 ⇒ 加 `_launcher` 后缀不影响排序。
     *
     * ★ 本用例**故意真的写一份**（拿一个探针异常走完整条落盘路径），然后在 finally 里删掉 ——
     *   只断言"文件存在"而不看内容的话，一个把异常吞掉、写出空文件的实现也能过。
     * ⚠️ 探针文件必须清掉：它落在**当前槽**的 crashes/ 里，用户进日志页会看见它。
     */
    private static void crashDump(Context ctx, List<String> L, int[] stat) {
        L.add("── ㊲ 主进程崩溃落盘（写 <数据根>/crashes/crash_<毫秒>_launcher.txt）──");
        File probe = null;
        try {
            ok(stat, L, Util.isMainProcess(ctx),
                    "★自检自己跑在主进程里（isMainProcess 判据；:game 侧只能在真机另验）");
            RuntimeException fake = new RuntimeException("selftest 探针：这不是真崩溃");
            probe = Crash.write(ctx, Thread.currentThread(), fake);
            ok(stat, L, probe != null && probe.isFile(), "★探针异常真的落了盘：" + path(probe));
            if (probe != null && probe.isFile()) {
                File root = Data.dataRoot(ctx);
                boolean inCrashes = root != null
                        && new File(root, "crashes").getAbsolutePath()
                                .equals(probe.getParentFile().getAbsolutePath());
                ok(stat, L, inCrashes, "★落点在 <数据根>/crashes/（日志页「崩溃堆栈」读的就是它）");
                // 与游戏那份同构：`crash_<13 位毫秒>_launcher.txt` ⇒ 按名倒序仍 = 按时间倒序
                boolean named = probe.getName().matches("^crash_\\d{13}_launcher\\.txt$");
                ok(stat, L, named, "★文件名与游戏那份同构（不影响「按名倒序 = 时间序」）："
                        + probe.getName());
                String text = Util.readText(probe);
                ok(stat, L, text.contains("RuntimeException")
                                && text.contains("selftest 探针：这不是真崩溃")
                                && text.contains("当前槽")
                                && text.contains("版本："),
                        "★内容里有异常原文 + 完整栈 + 现场（版本 / 当前槽 / 数据根）");
            }
        } catch (Throwable t) {
            ok(stat, L, false, "崩溃落盘用例自身异常：" + t);
        } finally {
            if (probe != null) probe.delete();
        }
        ok(stat, L, probe == null || !probe.exists(),
                "★探针文件已清理（别把它留在用户的崩溃列表里）");
        L.add("");
    }

    private static String lastKeyOf(SettingsBin.Values v) {
        String last = null;
        for (String k : v.all().keySet()) last = k;
        return last;
    }

    private static int countFiles(File dir) {
        File[] fs = dir == null ? null : dir.listFiles();
        return fs == null ? 0 : fs.length;
    }

    // ══ ⑰ F13 第三阶段：模组包导入 / 跨槽复制 ══════════════════════════════

    private static void modsPack(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑰ F13 模组包导入 / 跨槽复制 ──");
        File work = new File(Paths.privateDir(ctx), "selftest-modpack");
        deleteTree(work);
        work.mkdirs();
        // ★ 用**产品真正用的那个** trash 目录，而不是随便找个临时目录 ——
        //   自检第一版就是拿 `app_hub/` 下的临时目录当 trash，于是踩出"跨文件系统 rename 失败
        //   ⇒ 旧条目被直接删掉、trash 里 0 份"这个**真问题**（现已在 place() 里加了复制兜底）。
        File trash = Mods.trashDirOf(ctx);
        try {
            String e1 = Data.createSlot(ctx, SLOT_PACK);
            String e2 = Data.createSlot(ctx, SLOT_PACK_DST);
            ok(stat, L, e1 == null && e2 == null,
                    "建测试槽 " + SLOT_PACK + " / " + SLOT_PACK_DST
                            + (e1 == null && e2 == null ? "" : "：" + e1 + e2));
            if (e1 != null || e2 != null) return;

            File srcMods = new File(Data.slotDir(ctx, SLOT_PACK), "mods");
            File dstMods = new File(Data.slotDir(ctx, SLOT_PACK_DST), "mods");

            // 造夹具：一个合法包 / 一个无 meta 的包 / 一个坏 meta 的包
            File good = new File(work, "good-mod.zip");
            zipMany(good, new String[]{"mod.json", "scripts/main.js"},
                    new String[]{"name: Pack Test\nversion: 2.5\nminGameVersion: 140\n",
                            "print('x')"});
            File noMeta = new File(work, "no-meta.zip");
            zipMany(noMeta, new String[]{"readme.txt"}, new String[]{"nothing here"});
            File badMeta = new File(work, "bad-meta.zip");
            zipMany(badMeta, new String[]{"mod.json"}, new String[]{"description: '''\n'''"});

            // ① 合法包：导入成功、落点与源逐字节一致、不留 .part
            Mods.PackResult r1 = Mods.importPackage(srcMods, good, false, trash);
            File placed = new File(srcMods, "good-mod.zip");
            ok(stat, L, r1.ok && placed.isFile()
                            && Util.md5(good).equals(Util.md5(placed))
                            && r1.meta != null && "pack-test".equals(r1.meta.internalName),
                    "① 合法包导入成功且**逐字节一致**（内部名 " 
                            + (r1.meta == null ? "?" : r1.meta.internalName) + "）");
            ok(stat, L, !new File(srcMods, "good-mod.zip.part").exists(),
                    "① 没有 `.part` 残留（导入是「先落半成品再就位」的）");

            // ②③④⑤ 五个负例
            Mods.PackResult r2 = Mods.importPackage(srcMods, noMeta, false, trash);
            ok(stat, L, !r2.ok && r2.error != null && !new File(srcMods, "no-meta.zip").exists()
                            && !new File(srcMods, "no-meta.zip.part").exists(),
                    "② 包里没有 meta ⇒ **拒绝**且不留残骸：" + oneLine(r2.error));
            Mods.PackResult r3 = Mods.importPackage(srcMods, badMeta, false, trash);
            ok(stat, L, !r3.ok && !new File(srcMods, "bad-meta.zip").exists(),
                    "③ 坏 meta（空多行）⇒ 拒绝：" + oneLine(r3.error));
            Mods.PackResult r4 = Mods.importPackage(srcMods, new File(work, "x.txt"), false, trash);
            ok(stat, L, !r4.ok, "④ 扩展名不是 .zip/.jar ⇒ 拒绝：" + oneLine(r4.error));
            Mods.PackResult r5 = Mods.importPackage(srcMods, new File(work, "a:b.zip"), false, trash);
            ok(stat, L, !r5.ok, "⑤ 文件名含冒号 ⇒ 拒绝（安卓会因此 dex 失败）：" + oneLine(r5.error));

            // ⑥ 同名：不覆盖 ⇒ 拒；明确覆盖 ⇒ 成功，且旧的那份被**挪**去 trash
            String oldMd5 = Util.md5(placed);
            Mods.PackResult r6 = Mods.importPackage(srcMods, good, false, trash);
            ok(stat, L, !r6.ok && oldMd5.equals(Util.md5(placed)),
                    "⑥ 同名 + 不允许覆盖 ⇒ 拒绝，且原文件一个字节没动");
            File good2 = new File(work, "good-mod.zip");
            zipMany(good2, new String[]{"mod.json"},
                    new String[]{"name: Pack Test\nversion: 3.0\nminGameVersion: 140\n"});
            Mods.PackResult r7 = Mods.importPackage(srcMods, good2, true, trash);
            int stashed = countFiles(trash);
            ok(stat, L, r7.ok && r7.overwrote
                            && !oldMd5.equals(Util.md5(placed)),
                    "⑥ 同名 + 明确覆盖 ⇒ 替换成功（内容确实换了）");
            ok(stat, L, stashed >= 1,
                    "⑥ ★旧的那份**没被删**，挪去了 hub/mods-trash/（挪不删，共 " + stashed + " 份）");

            // ⑦ 跨槽复制：目录形态 + 配置目录 + 一个 .part 残留
            File dirMod = new File(srcMods, "dirmod");
            write(new File(dirMod, "mod.json"),
                    "name: Dir Mod\nversion: 1\n".getBytes("UTF-8"));
            write(new File(new File(srcMods, "pack-test"), "config.json"),
                    "{\"settings\":1}".getBytes("UTF-8"));     // 游戏的模组配置目录（无 meta）
            write(new File(srcMods, "half.zip.part"), "HALF".getBytes("UTF-8"));
            Mods.PackResult r8 = Mods.copyMods(srcMods, dstMods, false, trash);
            // 源槽此刻有 4 个顶层条目：good-mod.zip / dirmod / pack-test / half.zip.part
            // ⇒ 该复制的是**前 3 个**（`.part` 是"写一半"的东西，必须被跳过）
            ok(stat, L, r8.ok && r8.copied.size() == 3,
                    "⑦ 复制了 3 项（good-mod.zip / dirmod / pack-test；实得 "
                            + r8.copied.size() + "：" + r8.copied + "）");
            ok(stat, L, !new File(dstMods, "half.zip.part").exists(),
                    "⑦ ★`.part` 半成品**没有**被复制过去（它是「写一半」的东西）");
            ok(stat, L, Util.md5(placed).equals(Util.md5(new File(dstMods, "good-mod.zip")))
                            && new File(dstMods, "dirmod/mod.json").isFile()
                            && new File(dstMods, "pack-test/config.json").isFile(),
                    "⑦ ★逐字节一致：zip 包 md5 相同，目录模组与**模组配置目录**都跟过去了");
            Mods.PackResult r9 = Mods.copyMods(srcMods, dstMods, false, trash);
            ok(stat, L, r9.copied.isEmpty() && r9.skipped.size() == 3,
                    "⑦ 再复制一次 ⇒ 全部跳过（不覆盖）：skipped=" + r9.skipped.size());

            // ⑧ 元断言：把"合法包"的 meta 拿掉 ⇒ 必须判非法（证明第 ① 条不是恒真）
            File noMeta2 = new File(work, "good-mod-no-meta.zip");
            zipMany(noMeta2, new String[]{"scripts/main.js"}, new String[]{"print('x')"});
            Mods.PackResult r10 = Mods.importPackage(srcMods, noMeta2, false, trash);
            ok(stat, L, !r10.ok,
                    "⑧ ★元断言：同一个包把 meta 拿掉 ⇒ 必须判非法（否则 ① 的 ok 只是恒真）");
        } catch (Throwable t) {
            ok(stat, L, false, "导入/复制用例自身异常：" + t);
        } finally {
            deleteTree(work);
        }
        L.add("");
    }

    // ══ ⑱ F13 第四阶段：模组间冲突体检 ═════════════════════════════════════

    /**
     * ⑱ 冲突体检：**内置检测**（A 的 dex 里含 B 的主类）必须判死，**反向**（不含）必须判活；
     * 共同全局钩子（`mindustry/logic/LCanvas`）命中才报；简介提及只作线索；
     * 再加一条**元断言**（把主类从 dex 里拿掉 ⇒ 必须不再报"内置"）。
     */
    private static void modsConflict(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑱ F13 模组间冲突体检 ──");
        File work = new File(Paths.privateDir(ctx), "selftest-conflict");
        deleteTree(work);
        work.mkdirs();
        try {
            // A 内置了 B：A 的"dex"里塞进 B 的主类描述符；C 是无关模组
            File a = new File(work, "a-neon-like.zip");
            zipMany(a, new String[]{"mod.json", "classes.dex"},
                    new String[]{"name: Big Mod\nmain: big.BigMod\njava: true\n",
                            "FAKE-DEX-HEADER big/BigMod ... also contains small/SmallMod "
                                    + "and mindustry/logic/LCanvas references"});
            File b = new File(work, "b-logicsugar-like.zip");
            zipMany(b, new String[]{"mod.json", "classes.dex"},
                    new String[]{"name: Small Mod\nmain: small/SmallMod\njava: true\n",
                            "FAKE-DEX small/SmallMod mindustry/logic/LCanvas"});
            File c = new File(work, "c-unrelated.zip");
            zipMany(c, new String[]{"mod.json", "classes.dex"},
                    new String[]{"name: Unrelated\nmain: un.Un Mod\njava: true\n",
                            "FAKE-DEX unrelated only"});
            File bd = new File(work, "bdir");
            write(new File(bd, "mod.json"), "name: Fake Small\nmain: small/SmallMod\njava: true\n"
                    .getBytes("UTF-8"));
            File d = new File(work, "d-nomain.zip");
            zipMany(d, new String[]{"mod.json"}, new String[]{"name: Data Only\n"});

            List<Mods.Info> mods = new ArrayList<>();
            Mods.Info ia = Mods.readOne(a);
            Mods.Info ib = Mods.readOne(b);
            Mods.Info ic = Mods.readOne(c);
            Mods.Info id = Mods.readOne(d);
            mods.add(ia);
            mods.add(ib);
            mods.add(ic);
            mods.add(id);
            // 简介提及：让 A 的简介里出现 B 的名字
            ia.description = "内置了 Small Mod 的功能";

            Mods.Conflict cf = Mods.findConflicts(mods, null);
            boolean containsAB = false;
            for (String s : cf.contained) {
                if (s.contains("Big Mod") && s.contains("Small Mod")) containsAB = true;
            }
            ok(stat, L, containsAB && cf.javaMods == 3 && cf.scannedDex >= 3,
                    "★内置检测：A 的 classes.dex 里含 B 的主类 ⇒ 判为「A 内置了 B」"
                            + "（扫了 " + cf.javaMods + " 个 Java 模组 / " + cf.scannedDex + " 个 dex）");
            boolean bContainsA = false;
            for (String s : cf.contained) {
                if (s.contains("「Small Mod」的 classes.dex")) bContainsA = true;
            }
            ok(stat, L, !bContainsA,
                    "★反向：B 的 dex 里没有 A 的主类 ⇒ **不许**反过来说「B 内置了 A」"
                            + "（⚠️ 判**方向**要看「谁在说」那一句，不能只看两个名字都出现过 —— "
                            + "第一版就是这么写错的，两个名字在同一句里当然都出现）");
            boolean hookAB = false;
            for (String s : cf.sharedHooks) {
                if (s.contains("Big Mod") && s.contains("Small Mod")) hookAB = true;
            }
            ok(stat, L, hookAB,
                    "★共同全局钩子：两个模组的 dex 都引用 mindustry/logic/LCanvas ⇒ 报「可能抢逻辑编辑器」");
            ok(stat, L, !hasPair(cf.sharedHooks, "Unrelated", "Big Mod"),
                    "★反向：无关模组不引用该钩子 ⇒ **不许**把它算进来");
            ok(stat, L, hasPair(cf.mentioned, "Big Mod", "Small Mod"),
                    "★简介提及只作线索（A 的简介里写了 Small Mod ⇒ 出现在线索里）");
            // ★ 元断言：把 B 的主类从 A 的 dex 里删掉 ⇒ 必须不再报内置
            File a2 = new File(work, "a2.zip");
            zipMany(a2, new String[]{"mod.json", "classes.dex"},
                    new String[]{"name: Big Mod\nmain: big.BigMod\njava: true\n",
                            "FAKE-DEX big/BigMod mindustry/logic/LCanvas only"});
            List<Mods.Info> mods2 = new ArrayList<>();
            mods2.add(Mods.readOne(a2));
            mods2.add(ib);
            Mods.Conflict cf2 = Mods.findConflicts(mods2, null);
            ok(stat, L, cf2.contained.isEmpty(),
                    "★元断言：把 B 的主类从 A 的 dex 里拿掉 ⇒ **不再**报「内置」"
                            + "（证明上一条不是恒真）");
        } catch (Throwable t) {
            ok(stat, L, false, "冲突体检用例自身异常：" + t);
        } finally {
            deleteTree(work);
        }
        L.add("");
    }

    /**
     * ⑲ 搜索 / 筛选 / 排序（用户点单第②项）。
     * ★ 判据是**纯函数**（{@link Mods#filterAndSort}）⇒ 不用建 UI 就能钉死，
     *   包括两条最容易写错的**反方向**：搜不到必须返回空、用户自己关掉的不算问题。
     */
    private static void modsFilter(List<String> L, int[] stat) {
        L.add("── ⑲ 模组列表的搜索 / 筛选 / 排序 ──");
        Mods.Info a = new Mods.Info();
        a.name = "Alpha"; a.fileName = "alpha.zip"; a.bytes = 300;
        Mods.Info b = new Mods.Info();
        b.name = "Beta"; b.fileName = "beta.zip"; b.bytes = 100; b.failed = true;
        Mods.Info c = new Mods.Info();
        c.name = "Gamma"; c.fileName = "gamma.jar"; c.bytes = 200;
        List<Mods.Info> ms = new ArrayList<>();
        ms.add(a); ms.add(b); ms.add(c);

        ok(stat, L, Mods.filterAndSort(ms, "", Mods.SORT_NAME, false, null).size() == 3,
                "空关键词 = 不筛（三个都在）");
        List<Mods.Info> q = Mods.filterAndSort(ms, "BET", Mods.SORT_NAME, false, null);
        ok(stat, L, q.size() == 1 && q.get(0) == b, "搜索：忽略大小写按名字命中（BET → Beta）");
        ok(stat, L, Mods.filterAndSort(ms, "gamma", Mods.SORT_NAME, false, null).size() == 1,
                "搜索：换成文件名也能命中");
        ok(stat, L, Mods.filterAndSort(ms, "zzz", Mods.SORT_NAME, false, null).isEmpty(),
                "★元断言：搜不到必须返回**空列表**，不能「筛不动就返回全部」");

        List<Mods.Info> pb = Mods.filterAndSort(ms, "", Mods.SORT_NAME, true, null);
        ok(stat, L, pb.size() == 1 && pb.get(0) == b,
                "只看有问题的：固有毛病（上次出错被标记）会被留下");
        Mods.State[] st = {Mods.State.UNSUPPORTED, Mods.State.DISABLED, Mods.State.ENABLED};
        List<Mods.Info> ps = Mods.filterAndSort(ms, "", Mods.SORT_NAME, true, st);
        ok(stat, L, ps.size() == 2 && ps.contains(a) && !ps.contains(c),
                "★反向：只有「版本不符」算问题，「被用户关闭」**不算**（否则用户自己关掉的模组会一直赖在有问题的里）");

        ok(stat, L, Mods.filterAndSort(ms, "", Mods.SORT_NAME, false, null).get(0) == a,
                "按名称排序：Alpha 在 Beta/Gamma 前");
        ok(stat, L, Mods.filterAndSort(ms, "", Mods.SORT_SIZE, false, null).get(0) == a
                        && Mods.filterAndSort(ms, "", Mods.SORT_SIZE, false, null).get(2) == b,
                "按大小排序：大的在前（300 / 200 / 100）");
        ok(stat, L, Mods.filterAndSort(ms, "", Mods.SORT_STATE, false, null).get(0) == b,
                "有问题的在前：failed 的那个排第一");
        ok(stat, L, ms.size() == 3 && ms.get(0) == a && ms.get(2) == c,
                "★纯函数：入参列表没被改动（长度与顺序都不变）");
        L.add("");
    }

    /**
     * ⑳ 批量启停的写侧（第③项）。★ 这条路径**会写游戏设置** ⇒ 除了正面用例，必须钉住：
     *   ① 一次读写把多个键改完；② 不需要改时**一个字节都不动**（不是"写了一遍恰好一样"）；
     *   ③ 文件是坏的 ⇒ 拒绝写且原文件不被动；④ 一个键都没给 ⇒ 明确拒绝（不静默成功）。
     */
    private static void modsBatchWrite(Context ctx, List<String> L, int[] stat) {
        L.add("── ⑳ 批量启停（写侧）──");
        File dir = new File(Paths.privateDir(ctx), "selftest-batch");
        deleteTree(dir);
        dir.mkdirs();
        try {
            File bak = new File(dir, "backups");
            File f = new File(dir, "settings.bin");
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("mod-alpha-enabled", Boolean.TRUE);
            m.put("mod-beta-enabled", Boolean.TRUE);
            m.put("some-other-key", Integer.valueOf(7));
            SettingsBin.writeAtomic(f, new SettingsBin.Values(m, false, 0));

            java.util.LinkedHashMap<String, Boolean> want = new java.util.LinkedHashMap<>();
            want.put("mod-alpha-enabled", Boolean.FALSE);
            want.put("mod-beta-enabled", Boolean.FALSE);
            SettingsBin.Result r = SettingsBin.applyBools(f, bak, want);
            SettingsBin.Values after = SettingsBin.readSafe(f, new String[1]);
            ok(stat, L, r.ok && r.verified && !r.noop
                            && Boolean.FALSE.equals(after.all().get("mod-alpha-enabled"))
                            && Boolean.FALSE.equals(after.all().get("mod-beta-enabled"))
                            && Integer.valueOf(7).equals(after.all().get("some-other-key")),
                    "★一次读写把两个键都关了，无关键原样保留");
            ok(stat, L, r.backup != null && r.backup.isFile() && r.keys == 3,
                    "改前备份落盘、键数不变（3 个：批量改不该增删键）");

            byte[] b2 = SettingsBin.readAllBytes(f);
            java.util.LinkedHashMap<String, Boolean> same = new java.util.LinkedHashMap<>();
            same.put("mod-alpha-enabled", Boolean.FALSE);      // 已经是 false ⇒ 不需要改
            SettingsBin.Result r2 = SettingsBin.applyBools(f, bak, same);
            ok(stat, L, r2.ok && r2.noop && java.util.Arrays.equals(b2, SettingsBin.readAllBytes(f)),
                    "★元断言：没有键需要改 ⇒ 判 noop 且**文件逐字节没动**（不是「写一遍恰好一样」）");

            // ★反向＋分层纪律：这是**通用设置层**，对任意键"不存在 + true"**必须写进去**
            //   （"不存在 = 默认启用"只是模组开关的语义，属于 Mods 那一层；
            //     我第一版把它塞进了通用层，自检 ⑯ 的「新键追加到末尾」当场判死）
            SettingsBin.Result r2b = SettingsBin.applyBools(f, bak,
                    new java.util.LinkedHashMap<String, Boolean>() {{
                        put("gamma-new-key", Boolean.TRUE);
                    }});
            SettingsBin.Values afterB = SettingsBin.readSafe(f, new String[1]);
            ok(stat, L, r2b.ok && !r2b.noop && afterB != null
                            && Boolean.TRUE.equals(afterB.all().get("gamma-new-key")),
                    "★分层：通用层对「不存在 + true」的新键要**真的写进去**（不是当成默认值跳过）");

            File bad = new File(dir, "bad.bin");
            byte[] badBytes = {0, 0, 0, 5};                   // count=5 却没有键 ⇒ 坏文件
            java.io.FileOutputStream fo = new java.io.FileOutputStream(bad);
            fo.write(badBytes);
            fo.close();
            java.util.LinkedHashMap<String, Boolean> w3 = new java.util.LinkedHashMap<>();
            w3.put("mod-alpha-enabled", Boolean.TRUE);
            SettingsBin.Result r3 = SettingsBin.applyBools(bad, bak, w3);
            ok(stat, L, !r3.ok && java.util.Arrays.equals(badBytes, SettingsBin.readAllBytes(bad)),
                    "★负例：设置文件是坏的 ⇒ 拒绝写，且原文件一个字节没动");

            SettingsBin.Result r4 = SettingsBin.applyBools(f, bak,
                    new java.util.LinkedHashMap<String, Boolean>());
            ok(stat, L, !r4.ok && r4.error != null, "★负例：一个键都没给 ⇒ 明确拒绝（不静默成功）");
        } catch (Throwable t) {
            ok(stat, L, false, "批量启停用例自身异常：" + t);
        } finally {
            deleteTree(dir);
        }
        L.add("");
    }

    /**
     * ㉑ 反向依赖（第④项）：关掉一个模组前，先算出"会连累谁"。
     * ★ 三条最容易写错的都在这里钉住：**软依赖也算** / **大小写无关** / **已关闭的不算**。
     */
    private static void modsDependents(List<String> L, int[] stat) {
        L.add("── ㉑ 反向依赖（禁用前的提醒）──");
        Mods.Info base = new Mods.Info();
        base.internalName = "dep-base"; base.enabled = true; base.name = "Dep Base";
        Mods.Info hard = new Mods.Info();
        hard.internalName = "dep-user"; hard.enabled = true; hard.name = "Dep User";
        hard.dependencies.add("dep-base");
        Mods.Info soft = new Mods.Info();
        soft.internalName = "dep-soft"; soft.enabled = true; soft.name = "Dep Soft";
        soft.softDependencies.add("DEP-BASE");                   // 大小写无关
        Mods.Info off = new Mods.Info();
        off.internalName = "dep-off"; off.enabled = false; off.name = "Dep Off";
        off.dependencies.add("dep-base");
        List<Mods.Info> ms = new ArrayList<>();
        ms.add(base); ms.add(hard); ms.add(soft); ms.add(off);

        List<String> who = Mods.dependents(ms, "dep-base");
        ok(stat, L, who.size() == 2,
                "★反向：**已关闭**的依赖者不算（它自己都没在跑）—— 只数出 2 个：" + who);
        ok(stat, L, who.get(0).startsWith("必需") && who.get(0).contains("Dep User")
                        && who.get(1).startsWith("软依赖") && who.get(1).contains("Dep Soft"),
                "必需与软依赖都算、且必需排前面：" + who);
        ok(stat, L, Mods.dependents(ms, "dep-user").isEmpty(),
                "★反向：没人依赖 dep-user ⇒ 返回空（不是「随便返回点什么」）");
        ok(stat, L, Mods.dependents(ms, "dep-off").isEmpty(),
                "被关闭的模组自己也不该因为「依赖者都关着」而报（它不在启用集合里）");
        L.add("");
    }

    /**
     * ㉒ F10：`.msav` 元数据解析。
     * ★ 现场**自己造**一份合法 .msav（zlib + MSAV + int 版本 + meta 块），不依赖设备上有没有存档；
     *   并钉住两个最容易混淆的口径：**只读 meta** 与 **查完整性**。
     */
    private static void msavParse(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉒ F10 .msav 元数据 ──");
        File dir = new File(Paths.privateDir(ctx), "selftest-msav");
        deleteTree(dir);
        dir.mkdirs();
        try {
            File good = tinyMsav(new File(dir, "good.msav"), "我的地图", 640, 480, 12, false);
            MsavMeta m = MsavMeta.read(good);
            ok(stat, L, m.ok && m.version == 11 && m.width == 640 && m.height == 480
                            && m.wave == 12 && "我的地图".equals(m.name)
                            && m.playtime == 3720000L && m.build == 153 && !m.truncated,
                    "★解析：格式版本/尺寸/波次/真名(中文)/游玩时长/构建号全部读出来");
            // ★ 列表行两个口径（用户要求"区分度大点"）：存档看**时长+时间**，地图看**尺寸+作者**
            // ★ P3 第一片：行文案搬到 MsavText（收 Context）了 ⇒ 这里显式传 ctx。
            //   自检的 ctx 一进来就被 force 成 SELFTEST（zh），所以下面那些中文期望值**照旧成立**，
            //   而且从此**不受用户的界面语言设置影响**（这比原来更稳）。
            String saveLine = MsavText.shortLine(ctx, m, true);
            String mapLine = MsavText.shortLine(ctx, m, false);
            ok(stat, L, saveLine.contains("玩了 1 小时 2 分") && saveLine.contains("存档于 ")
                            && saveLine.contains("我的地图"),
                    "★存档行：地图名 + 玩了多久 + 人读的存档时间 —— " + saveLine);
            ok(stat, L, mapLine.contains("640 × 480") && !mapLine.contains("玩了"),
                    "★地图行：尺寸/作者口径，且**不带**存档那套 —— " + mapLine);
            ok(stat, L, !saveLine.contains("格式 v") && !mapLine.equals(saveLine),
                    "★元断言：格式版本**已移出列表行**（它会把长名字挤到第三行），且两个口径确实不同");

            // ★ 去色：真名里带色码时必须去掉（真机截图里满行 `[gold]`/`[red]` 噪声）
            File colored = tinyMsav(new File(dir, "colored.msav"), "[gold]金色[red]地图[]", 32, 32, 2, false);
            MsavMeta cm = MsavMeta.read(colored);
            String cl = MsavText.shortLine(ctx, cm, false);
            ok(stat, L, "金色地图".equals(Mods.stripColors(cm.name)) && !cl.contains("[gold]")
                            && !cl.contains("[red]") && cl.contains("金色地图"),
                    "★去色：真名 `[gold]金色[red]地图[]` ⇒ 列表行显示「" + cl + "」（无方括号色码）");
            ok(stat, L, "我的地图".equals(m.displayName()) && "640 × 480".equals(m.sizeText())
                            && !MsavText.playtimeText(ctx, m).isEmpty(),
                    "★展示：显示名 / 尺寸 / 时长文案（" + MsavText.summary(ctx, m) + "）");

            // 把最后一块（假的 map 区）砍掉 ⇒ meta 完好、文件其实不完整
            File cut = tinyMsav(new File(dir, "cut.msav"), "半份", 64, 64, 3, true);
            MsavMeta cheap = MsavMeta.read(cut);
            ok(stat, L, cheap.ok && !cheap.truncated && cheap.width == 64,
                    "★只读 meta：meta 之后被截断**看不到**，仍然报 ok（它只承诺「元数据读出来了」）");
            MsavMeta full = MsavMeta.read(cut, true);
            ok(stat, L, full.truncated && full.error != null && full.width == 64,
                    "★元断言：同一份文件用查完整性口径**必须**报截断 ⇒ 两个口径的差别钉死在这里");

            File junk = new File(dir, "junk.msav");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(junk);
            fo.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            fo.close();
            MsavMeta jm = MsavMeta.read(junk);
            ok(stat, L, !jm.ok && jm.error != null && jm.tags.isEmpty(),
                    "★负例：垃圾文件 ⇒ 读不出来、且**不留半截 tags**");

            File gz = new File(dir, "gzip.msav");
            java.util.zip.GZIPOutputStream go = new java.util.zip.GZIPOutputStream(
                    new java.io.FileOutputStream(gz));
            go.write(java.nio.file.Files.readAllBytes(good.toPath()));
            go.close();
            ok(stat, L, !MsavMeta.read(gz).ok,
                    "★负例：gzip 压缩 ⇒ 读不出来（.msav 是 zlib，这条防「随便什么压缩都当 msav」）");

            // ★★ 压缩头判据的两个方向（2026-10-03 修掉的真 bug：导入路径原来按 **gzip** 判 .msav）
            ok(stat, L, Util.isZlib(good) && Msav.isZlib(good),
                    "★zlib 判据：真实 .msav（78 9C 头）必须判**活** —— 旧代码按 gzip（1f 8b）判，"
                            + "结果是每份正常存档都被警告、真正读不了的 gzip 反被放行");
            ok(stat, L, !Util.isZlib(gz) && !Msav.isZlib(junk) && !Util.isZlib(new File(dir, "nope.msav")),
                    "★反向：gzip 文件 / 垃圾文件 / 不存在的文件都必须判**死**（且不抛）");
        } catch (Throwable t) {
            ok(stat, L, false, "msav 用例自身异常：" + t);
        } finally {
            deleteTree(dir);
        }
        L.add("");
    }

    /**
     * ㉝ 槽内存档体检：`MsavMeta.summarize`（份数 / 几份读不出来 / 最近那份）
     *    + 槽页副标题那几句文案的**实拼**。
     *
     * ★ 这一组要钉死的是三件容易"看着没事"的事：
     *   ① **认档判据**（目录 / 别的后缀 / `.msav.part` 都不许算进"份数"，否则用户会看到不存在的存档）；
     *   ② **最近那份读不出来时不许假装没事**（`newest` 按改动时间取，不因为读不出来就跳过）；
     *   ③ **没有坏档时不许冒出 ⚠**（判据不能恒真 —— 元断言）。
     */
    private static void savesHealth(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉝ 槽内存档体检（哪几份读不出来） ──");
        File dir = new File(Paths.privateDir(ctx), "selftest-saveshealth");
        deleteTree(dir);
        dir.mkdirs();
        try {
            File good = tinyMsav(new File(dir, "good.msav"), "好档", 32, 32, 1, false);
            File junk = new File(dir, "junk.msav");
            write(junk, "这根本不是 .msav".getBytes("UTF-8"));
            File notMsav = new File(dir, "note.txt");
            write(notMsav, "x".getBytes("UTF-8"));
            File part = new File(dir, "half.msav.part");
            write(part, "x".getBytes("UTF-8"));
            File dirLike = new File(dir, "somedir.msav");      // 目录，名字却像存档
            dirLike.mkdirs();
            // 让坏的那份成为"最近改动"⇒ 逼出"最近那份读不出来"，副标题不许只说"最近：好档"
            good.setLastModified(1600000000000L);
            junk.setLastModified(1700000000000L);

            MsavMeta.Saves s = MsavMeta.summarize(dir.listFiles());
            ok(stat, L, s.total == 2,
                    "★认档判据：只数 .msav 普通文件（目录 somedir.msav / note.txt / half.msav.part 都不算）"
                            + " ⇒ total=" + s.total);
            ok(stat, L, s.unreadableCount() == 1
                            && "junk.msav".equals(s.unreadable.get(0).file.getName())
                            && s.unreadable.get(0).meta.error != null
                            && !s.unreadable.get(0).meta.error.isEmpty(),
                    "★读不出来要点名到**具体文件**并带上原因："
                            + (s.unreadable.isEmpty() ? "（没点到名 ❌）"
                            : s.unreadable.get(0).file.getName() + " —— " + s.unreadable.get(0).meta.error));
            ok(stat, L, s.newest != null && "junk.msav".equals(s.newest.getName()) && !s.newestMeta.ok,
                    "★`newest` 按改动时间取，**不因为读不出来就跳过**（否则「最近那份坏了」会被静默藏起来）");
            ok(stat, L, MsavMeta.isSaveFile(good) && !MsavMeta.isSaveFile(notMsav)
                            && !MsavMeta.isSaveFile(part) && !MsavMeta.isSaveFile(dirLike)
                            && !MsavMeta.isSaveFile(null),
                    "★`isSaveFile`：.msav 普通文件算；别的后缀 / .msav.part / 同名目录 / null 都不算");

            // ★★ 元断言：同一批评据喂"全是好档"的输入 ⇒ 必须**一份都不报**（证明判据不是恒真）
            File good2 = tinyMsav(new File(dir, "good2.msav"), "好档二", 16, 16, 1, false);
            MsavMeta.Saves allOk = MsavMeta.summarize(new File[]{good, good2, notMsav, part, dirLike});
            ok(stat, L, allOk.total == 2 && allOk.unreadableCount() == 0,
                    "★元断言：全是好档 ⇒ total=2 且一份都不报（判据不是恒真）");
            MsavMeta.Saves none = MsavMeta.summarize(null);
            ok(stat, L, none.total == 0 && none.unreadableCount() == 0 && none.newest == null,
                    "★反向：没有文件（null）⇒ 干净的空结果、不抛");

            // ── 失败原因的"人话版"：异常类名不许直接甩给用户 ──
            MsavMeta tech = MsavMeta.read(junk);
            ok(stat, L, tech.error != null && tech.error.contains("ZipException")
                            && "这个文件不像存档，或者写到一半就断了"
                                    .equals(MsavText.userReason(ctx, tech)),
                    "★原因翻译：`" + tech.error + "` ⇒ 「" + MsavText.userReason(ctx, tech) + "」");
            // ② 我们自己写的中文判断 ⇒ **P3 第七批起不再原样透传**，改走「错误码 → 资源」
            //    （核心是纯 Java，只给 `errCode` + 参数；文案在 `MsavText` 里按码取资源）
            //    🔴 这条断言**原来要求的是"原样返回"** —— 那正是被替换掉的旧设计。
            //       它当时红了一下，正好说明"行为改了，判据会喊"（这正是要的）。
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.util.zip.DeflaterOutputStream dos = new java.util.zip.DeflaterOutputStream(bo);
            dos.write("NOPE-not-msav".getBytes("UTF-8"));
            dos.close();
            File noMagic = new File(dir, "nomagic.msav");
            write(noMagic, bo.toByteArray());
            MsavMeta nm = MsavMeta.read(noMagic);
            // ⚠️ 这段没有 `enCtx`（那个在 ㉞ 段的另一个方法里）⇒ 本地取一个英文 ctx
            Context enCtxNm = LocaleMode.force(ctx, "en");
            ok(stat, L, nm.error != null && nm.error.contains("MSAV")
                            && nm.errCode == MsavMeta.E_MAGIC
                            && !MsavText.userReason(ctx, nm).equals(nm.error)
                            && !MsavText.userReason(enCtxNm, nm).equals(nm.error)
                            && !MsavText.userReason(ctx, nm).equals(MsavText.userReason(ctx, tech)),
                    "★元断言：我们的中文判断走**错误码 → 资源**（核心那句只给报告看；"
                            + "中英两套都**不是**核心原文）⇒ 「"
                            + MsavText.userReason(ctx, nm) + "」");
            ok(stat, L, "原因不明".equals(MsavText.userReason(ctx, MsavMeta.read(good))),
                    "★反向：没出错（error=null）⇒ 兜底「原因不明」，不抛 NPE");

            // ── 文案实拼（★ 顺带钉住 aapt2 剥前导空白那个老坑：分隔符" · "必须还在）──
            String bad = ctx.getString(R.string.slot_page_saves_bad_fmt, 7, 2);
            String badRecent = ctx.getString(R.string.slot_page_saves_bad_recent_fmt, 7, "好档", 2);
            String plain = ctx.getString(R.string.slot_page_saves_count_fmt, 7, "好档");
            String plainOnly = ctx.getString(R.string.slot_page_saves_count_only_fmt, 7);
            ok(stat, L, bad.contains("7") && bad.contains("2") && bad.contains("读不出来")
                            && bad.contains(" · ⚠ "),
                    "★坏档文案：" + bad + "（分隔符没被 aapt2 吃掉）");
            ok(stat, L, badRecent.contains("最近：好档") && badRecent.contains("读不出来"),
                    "★坏档文案（带最近那份）：" + badRecent);
            ok(stat, L, !plain.contains("读不出来") && !plainOnly.contains("读不出来"),
                    "★元断言：没有坏档时**不许**出现那句警告 ⇒ " + plain + " / " + plainOnly);
            ok(stat, L, ctx.getString(R.string.msav_bad_list_title_fmt, 3).contains("3"),
                    "★落点弹窗的标题带上份数："
                            + ctx.getString(R.string.msav_bad_list_title_fmt, 3));
            // ★★ aapt2 会把值里的**裸双引号静默删掉**（2026-10-04 实测：`mod "%1$s"` 装到机器上
            //    显示成 `mod X`）⇒ 要显示引号必须写 `\"`。这条拿**真参数实拼**钉住这个转义
            //    （否则"翻译里写的引号全没了"这件事没有任何环节能发现）。
            //    ⚠️ 必须显式取**英文**资源：自检的 ctx 被钉在 zh，而中文用的是「」、
            //       本来就没有 ASCII 引号 —— 拿它断言等于什么都没验（我第一版就是这么错的）。
            Context enCtx = LocaleMode.force(ctx, "en");
            String quoted = enCtx.getString(R.string.map_from_mod_fmt, "X.zip");
            String dlgTitle = enCtx.getString(R.string.delete_slot_confirm_title_fmt, "test");
            ok(stat, L, quoted.contains("\"X.zip\"") && !quoted.contains("\\")
                            && dlgTitle.contains("\"test\"") && !dlgTitle.contains("\\"),
                    "★转义：英文资源里写 \\\" 才会真的显示双引号（裸引号会被 aapt2 删掉）：「"
                            + quoted + "」/「" + dlgTitle + "」");

            // ★★ P3 第一批：槽的增 / 改 / 删**报错文案**（`Data.createSlot/renameSlot/deleteSlot`
            //    的返回值 ⇒ 弹窗正文）。8 条都带占位符 ⇒ **按真参数实拼**一遍
            //    （工程铁律：`%n$` 与实参个数不匹配会抛 MissingFormatArgumentException **直接崩**，
            //     而它只在走到那一句时才现形）。
            String sExists = ctx.getString(R.string.slot_err_exists_fmt, "x");
            String sMissing = ctx.getString(R.string.slot_err_missing_fmt, "x");
            String sTarget = ctx.getString(R.string.slot_err_target_current_fmt, "x");
            String sRenameCur = ctx.getString(R.string.slot_err_rename_current_fmt, "x");
            String sDelCur = ctx.getString(R.string.slot_err_delete_current_fmt, "x");
            String sMkdir = ctx.getString(R.string.slot_err_mkdir_fmt, "/p");
            String sRenameFail = ctx.getString(R.string.slot_err_rename_failed_fmt, "a", "b");
            String sPartial = ctx.getString(R.string.slot_err_delete_partial_fmt, "/p", 2, 7);
            ok(stat, L, sExists.contains("x") && sMissing.contains("x") && sTarget.contains("x")
                            && sRenameCur.contains("x") && sDelCur.contains("x")
                            && sMkdir.contains("/p") && sRenameFail.contains("a")
                            && sRenameFail.contains("b") && sPartial.contains("/p")
                            && sPartial.contains("2") && sPartial.contains("7"),
                    "★P3：槽操作那 8 条带占位符的报错文案按真参数实拼（不崩、参数都在）");
            // ★ 反斜杠：中文那句里写的是 `\\`，必须**渲染成一个** `\`（写多写少都算错）
            String sInvalid = ctx.getString(R.string.slot_err_name_invalid);
            ok(stat, L, sInvalid.contains("\\") && !sInvalid.contains("\\\\"),
                    "★P3：槽名不合法那句的反斜杠渲染成**一个** `\\`（资源里写的是两个）");
            // ★ 英文侧：双引号必须真的显示（`slot_err_exists_fmt` 写的是 \" —— RES-12 的反面）
            String sExistsEn = enCtx.getString(R.string.slot_err_exists_fmt, "x");
            ok(stat, L, sExistsEn.contains("\"x\"") && !sExistsEn.contains("\\"),
                    "★P3（英文）：槽已存在那句的双引号真的显示了：「" + sExistsEn + "」");

            // ★★ P3 第二批：地图增删的报错（`MapFiles.checkName/importMap/place`）——
            //    5 条带占位符的按真参数实拼；顺带验英文那句的 `\"` 转义。
            String mNameTaken = enCtx.getString(R.string.mapfile_err_name_taken_fmt, "a.msav");
            ok(stat, L, ctx.getString(R.string.mapfile_err_mkdir_fmt, "/p").contains("/p")
                            && ctx.getString(R.string.mapfile_err_not_map_why_fmt, "why").contains("why")
                            && ctx.getString(R.string.mapfile_err_trash_mkdir_fmt, "/t").contains("/t")
                            && ctx.getString(R.string.mapfile_err_rename_fmt, "/x").contains("/x")
                            && mNameTaken.contains("\"a.msav\"") && !mNameTaken.contains("\\"),
                    "★P3：地图那边 5 条带占位符的报错按真参数实拼（英文那句的双引号也在）");

            // ★★ P3 第三批：整槽 zip 导入的报错（`SlotZip.stage/inspect/extract/open/badZip/mkdirs`）——
            //    12 条带占位符的按真参数实拼（其中 3 条是**两个**参数）。`%n$` 与实参不匹配会崩，
            //    而那些句子只在"导入出岔子"时才会走到 ⇒ 不实拼就等于没测。
            String zClean = ctx.getString(R.string.slotzip_err_clean_tmp_fmt, "/t");
            String zMany = ctx.getString(R.string.slotzip_err_too_many_fmt, 5000);
            String zMore = ctx.getString(R.string.slotzip_more_fmt, 9);
            String zNoDir = ctx.getString(R.string.slotzip_err_no_slot_dir_fmt, "s");
            String zWipe = ctx.getString(R.string.slotzip_err_wipe_failed_fmt, "/w");
            String zMkdir = ctx.getString(R.string.slotzip_err_mkdir_slot_fmt, "/m");
            String zWrite = ctx.getString(R.string.slotzip_err_write_failed_fmt, "/x", "why");
            String zDel = ctx.getString(R.string.slotzip_err_delete_failed_fmt, "/d");
            String zRen = ctx.getString(R.string.slotzip_err_rename_fmt, "a", "b");
            String zTemp = ctx.getString(R.string.slotzip_err_temp_gone_fmt, "/z");
            String zDir = ctx.getString(R.string.slotzip_err_dir_failed_fmt, "/e");
            String zEsc = ctx.getString(R.string.slotzip_err_escape_fmt, "boom");
            ok(stat, L, zClean.contains("/t") && zMany.contains("5000") && zMore.contains("9")
                            && zNoDir.contains("s") && zWipe.contains("/w") && zMkdir.contains("/m")
                            && zWrite.contains("/x") && zWrite.contains("why") && zDel.contains("/d")
                            && zRen.contains("a") && zRen.contains("b") && zTemp.contains("/z")
                            && zDir.contains("/e") && zEsc.contains("boom"),
                    "★P3：整槽 zip 那 12 条带占位符的报错按真参数实拼（含 3 条两个参数）");
            String zNoDirEn = enCtx.getString(R.string.slotzip_err_no_slot_dir_fmt, "s");
            ok(stat, L, zNoDirEn.contains("\"s\"") && !zNoDirEn.contains("\\"),
                    "★P3（英文）：zip 那句槽名带引号且不留反斜杠：「" + zNoDirEn + "」");

            // ★★ P3 第四批：备份创建/删除的报错（`Backup.create` 抛的 IOException / `delete` 的返回值）。
            String bNoData = ctx.getString(R.string.backup_err_no_data_fmt, "s");
            String bMkdir = ctx.getString(R.string.backup_err_mkdir_fmt, "/b");
            String bNothing = ctx.getString(R.string.backup_err_nothing_fmt, "s", "/src", "cfg");
            String bSnapMk = ctx.getString(R.string.backup_err_snapshot_mkdir_fmt, "/sm");
            String bDel = ctx.getString(R.string.backup_err_delete_partial_fmt, "/d");
            ok(stat, L, bNoData.contains("s") && bMkdir.contains("/b")
                            && bNothing.contains("s") && bNothing.contains("/src")
                            && bNothing.contains("cfg") && bSnapMk.contains("/sm")
                            && bDel.contains("/d"),
                    "★P3：备份创建/删除那 5 条带占位符的报错按真参数实拼（含 1 条**三个**参数）");
            // ★ **列表分隔符**：中文顿号 / 英文「逗号 + 空格」—— 英文那份写的是 `\u0020`
            //   （白名单 U0020_WHITELIST 里"确有必要"的那一类），这里验它**真的进了句子**。
            String sepZh = ctx.getString(R.string.list_join_sep);
            String sepEn = enCtx.getString(R.string.list_join_sep);
            ok(stat, L, !sepZh.equals(sepEn) && sepEn.endsWith(" ")
                            && enCtx.getString(R.string.backup_err_nothing_fmt, "s", "/src",
                                    "cfg" + sepEn + "natives").contains("cfg, natives"),
                    "★P3：列表分隔符两套语言各一份（中「" + sepZh + "」/ 英「" + sepEn
                            + "」），英文那个**带空格**且真的进了句子");

            // ★★ P3 第五批：备份**恢复**路径（`Backup.restore` 的失败原因 / 自检报告 / 成功报告）。
            //    16 条带占位符的按真参数实拼（含 1 条**三个**参数、2 条两个参数）。
            String rMan = ctx.getString(R.string.backup_restore_err_manifest_fmt, "e");
            String rMk = ctx.getString(R.string.backup_restore_err_mkdir_fmt, "/m");
            String rMissing = ctx.getString(R.string.backup_restore_bad_missing_fmt, "a");
            String rSize = ctx.getString(R.string.backup_restore_bad_size_fmt, "a", 1, 2);
            String rMd5 = ctx.getString(R.string.backup_restore_bad_md5_fmt, "a");
            String rSelf = ctx.getString(R.string.backup_restore_err_selfcheck_fmt, "x");
            String rMore = ctx.getString(R.string.backup_restore_more_fmt, 9);
            String rBullet = ctx.getString(R.string.backup_restore_bullet_fmt, "b");
            String rLine = ctx.getString(R.string.backup_restore_err_line_fmt, "r", "m");
            String rHead = ctx.getString(R.string.backup_restore_ok_head_fmt, "s");
            String rSnap = ctx.getString(R.string.backup_restore_ok_snapshot_fmt, "t");
            String rFiles = ctx.getString(R.string.backup_restore_ok_files_fmt, 1, 2, "1 KB");
            String rWhere = ctx.getString(R.string.backup_restore_ok_where_fmt, "/w");
            String rPart = ctx.getString(R.string.backup_restore_partial_fmt, "z");
            String rClone = ctx.getString(R.string.backup_err_clone_target_current_fmt, "d");
            String rDel = ctx.getString(R.string.backup_err_slot_backups_delete_fmt, "/b");
            ok(stat, L, rMan.contains("e") && rMk.contains("/m") && rMissing.contains("a")
                            && rSize.contains("a") && rSize.contains("1") && rSize.contains("2")
                            && rMd5.contains("a") && rSelf.contains("x") && rMore.contains("9")
                            && rBullet.contains("b") && rLine.contains("r") && rLine.contains("m")
                            && rHead.contains("s") && rSnap.contains("t") && rFiles.contains("1 KB")
                            && rWhere.contains("/w") && rPart.contains("z") && rClone.contains("d")
                            && rDel.contains("/b"),
                    "★P3：备份恢复那 16 条带占位符的文案按真参数实拼（含 1 条三个参数、2 条两个）");
            // ★ `RES-04` 的反面：报告行的**缩进由 Java 侧加**，资源本身**不许**以空格开头
            //   （aapt2 会把前导空白剥掉 —— 这一批我第一版就是把缩进写进了资源，被门禁当场抓住）。
            boolean noLead = true;
            for (String one : new String[]{rSnap, rFiles, rWhere, rBullet, rLine, rMore}) {
                if (one.startsWith(" ")) noLead = false;
            }
            ok(stat, L, noLead,
                    "★P3：报告行资源里**没有前导空格**（缩进是 Java 侧排版 —— aapt2 剥不动它）");

            // ★★ P3 第八批：`Mods.State` 的显示文案（原来**枚举自己带文案**）+ 六道加载门。
            //    ① **遍历所有状态**：label 与 badge 都必须非空 —— `switch` 有 `default` 兜底，
            //       漏映射**不崩不报错**、界面只是**静默空白**，只有遍历抓得住；顺带验两套语言不同；
            //    ② 徽标必须**不比句子长**（真机实测过：太长会把列表标题挤到折行）——
            //       这条把"别顺手把 label 当 badge 用"钉住，而且与语言无关。
            StringBuilder missMd = new StringBuilder();
            boolean mdDiff = true, badgeShorter = true;
            for (Mods.State one : Mods.State.values()) {
                String lb = ModsText.stateLabel(ctx, one);
                String bd = ModsText.stateBadge(ctx, one);
                if (lb.isEmpty() || bd.isEmpty()) missMd.append(one).append(' ');
                if (lb.equals(ModsText.stateLabel(enCtx, one))) mdDiff = false;
                if (bd.length() > lb.length()) badgeShorter = false;
            }
            ok(stat, L, missMd.length() == 0,
                    "★P3：7 个模组状态**每一个**都有 label + badge 文案（漏映射=界面静默空白），漏的是［"
                            + missMd + "］");
            ok(stat, L, mdDiff && badgeShorter,
                    "★P3：状态文案两套语言不同；且**徽标不比句子长**（长了会把列表标题挤到折行）");
            // ② 六道加载门：标签六条都要有；**没通过的**那几关必须给原因（通过的可以只画一行 ✅）
            Mods.Info gm = mkMod("gate-probe", null, null, true);
            StringBuilder gateMiss = new StringBuilder();
            int gateCount = 0;
            for (Mods.Gate g : Mods.gates(ctx, gm, 0, 0)) {
                gateCount++;
                if (g.label == null || g.label.isEmpty()) gateMiss.append("标签 ");
                if (!g.pass && (g.note == null || g.note.isEmpty())) gateMiss.append("原因 ");
            }
            ok(stat, L, gateCount == 6 && gateMiss.length() == 0,
                    "★P3：六道加载门的标签齐全；**没通过的**那几关必须给原因（通过了可只画一行）；"
                            + "门数=" + gateCount + "，缺=" + gateMiss);
            // ★★ P3 第九批：模组页那几条（导入失败 + 详情弹窗补充说明）按真参数实拼。
            //    ★ 其中两条的占位符是"整句 + %1$s/%2$s"（原来是 Java 里拼的**碎片**）。
            String mdTarget = ctx.getString(R.string.mods_detail_target_fmt, "A / B", "1.2");
            String mdRepo = ctx.getString(R.string.mods_detail_repo_override_fmt, "https://x");
            ok(stat, L, mdTarget.contains("A / B") && mdTarget.contains("1.2")
                            && mdRepo.contains("https://x")
                            && !ctx.getString(R.string.mods_detail_depchain).contains("Mods.")
                            && !ctx.getString(R.string.mods_detail_no_target).isEmpty(),
                    "★P3：模组页那两条带占位符的按真参数实拼；详情里**不再出现源码出处**（文案纪律 ⑤）");

            // ★★ P3 第十批：模组包**导入 / 跨槽复制**的失败原因（`Mods.PackResult`）。
            //    它和 `SettingsBin` 一样是"纯 Java 核心 + 码"，所以判据也照抄那一条：
            //    ① 遍历 `ALL_CODES`，每个都必须映射到真文案（漏映射 = 弹窗里**静默空白**）；
            //    ② 两套语言不同；
            //    ③ **原始原因**（异常原文/诊断）不许进，**中性参数**（路径/文件名）必须进。
            StringBuilder missPk = new StringBuilder();
            boolean pkNeutral = true, pkLocale = true, pkLeak = false;
            for (int code : Mods.PackResult.ALL_CODES) {
                Mods.PackResult pr = new Mods.PackResult();
                pr.errCode = code;
                pr.errS1 = "NEUTRAL";
                pr.error = "BOOM: raw";
                String pz = ModsText.packReason(ctx, pr);
                String pe = ModsText.packReason(enCtx, pr);
                if (pz == null || pz.isEmpty() || pz.contains("BOOM")) missPk.append(code).append(' ');
                if (pz.equals(pe)) pkLocale = false;
                boolean neutral = code == Mods.PackResult.P_SRC_MISSING
                        || code == Mods.PackResult.P_MKDIR
                        || code == Mods.PackResult.P_NAME_TAKEN;
                if (neutral && !pz.contains("NEUTRAL")) pkNeutral = false;
                if (!neutral && pz.contains("NEUTRAL")) pkLeak = true;
            }
            ok(stat, L, missPk.length() == 0 && pkNeutral && pkLocale && !pkLeak,
                    "★P3：模组包那 16 个码**每个**都有文案、两套语言不同、"
                            + "中性参数（路径/文件名）进了而原始原因没进；可疑的是［" + missPk + "］");

            // ★★ P3 第十一批：APK 导入 / 导出 / 存档落盘那几条带占位符的按**真参数实拼**
            //    （工程铁律：`%n$` 与实参个数不匹配会抛 `MissingFormatArgumentException` **直接崩**，
            //      而那些句子只在"真出错"时才走到 ⇒ 不实拼就等于没测）。
            String iSmall = ctx.getString(R.string.imp_err_too_small_fmt, 42);
            String iEntry = ctx.getString(R.string.imp_err_no_entry_fmt, "a.b.C");
            String iRen = ctx.getString(R.string.imp_err_rename_fmt, "x.apk");
            String eSrc = enCtx.getString(R.string.exp_err_src_missing_fmt, "/p");
            String ePkg = ctx.getString(R.string.exp_err_pkg_missing_fmt, "/q");
            String eEnt = ctx.getString(R.string.exp_err_entry_missing_fmt, "maps/a.msav");
            String mNo = enCtx.getString(R.string.msav_err_no_saves_fmt, "s");
            String mSame = ctx.getString(R.string.msav_err_same_name_fmt, "a.msav");
            String mRen = ctx.getString(R.string.msav_err_rename_fmt, "a.part", "a.msav");
            ok(stat, L, iSmall.contains("42") && iEntry.contains("a.b.C") && iRen.contains("x.apk")
                            && eSrc.contains("/p") && ePkg.contains("/q")
                            && eEnt.contains("maps/a.msav") && mNo.contains("s")
                            && mSame.contains("a.msav") && mRen.contains("a.part")
                            && mRen.contains("a.msav")
                            && !ctx.getString(R.string.imp_err_bad_zip).isEmpty()
                            && !ctx.getString(R.string.exp_err_dest_readonly).isEmpty(),
                    "★P3：导入/导出/存档落盘那 9 条带占位符的按真参数实拼（含 1 条两个参数）");

            // ★★ P3 第六批：启动管线各步 + 失败原因（`Injector.LaunchError` → 启动失败弹窗）。
            //    ★ 这里只需验两件事：① 两条带占位符的按真参数实拼；② 6 个步骤名**两套语言都有字**
            //      （报告的键是 ASCII 的 `prewarm`/`dexInject`/…，与界面语言无关，**不是**这些资源）。
            String iNoAct = ctx.getString(R.string.launch_err_no_activity_fmt, "a.b.C");
            String iNoAbi = enCtx.getString(R.string.launch_err_no_abi_fmt, "[arm64-v8a]");
            boolean stepsBothLocales = true;
            for (int id : new int[]{R.string.step_prewarm, R.string.step_dex, R.string.step_native,
                    R.string.step_native_flag, R.string.step_assets, R.string.step_start}) {
                if (ctx.getString(id).isEmpty() || enCtx.getString(id).isEmpty()) {
                    stepsBothLocales = false;
                }
            }
            ok(stat, L, iNoAct.contains("a.b.C") && iNoAbi.contains("[arm64-v8a]")
                            && !ctx.getString(R.string.launch_err_bad_package).isEmpty()
                            && !ctx.getString(R.string.launch_err_no_native_loaded).isEmpty()
                            && stepsBothLocales,
                    "★P3：启动管线 6 个步骤名两套语言都有字，两条带占位符的原因按真参数实拼");

            // ★★ P3 第七批：**第一块「码 + 参数」** —— 存档读不出来的原因（`MsavMeta.errCode` →
            //    `MsavText.userReason` → 资源）。核心是纯 Java ⇒ 它只说"错在哪、带哪几个数"。
            //    ① **遍历所有码**：每个都必须映射到一条真文案（不是"原因不明"那个兜底）——
            //       漏一条映射**不崩不报错**、界面只是**静默空白**，只有遍历才抓得住；
            //    ② 参数真的进了句子（meta 声明 5 项、只读到 2 项 ⇒ 文案里得有 5 和 2）；
            //    ③ 两套语言给出**不同**文案（证明走的是资源，而不是把核心那句中文透传出去）。
            StringBuilder missCode = new StringBuilder();
            boolean paramOk = true, localeDiff = true;
            String reasonFallback = ctx.getString(R.string.msav_unknown_reason);
            for (int code : MsavMeta.ALL_CODES) {
                MsavMeta mm = MsavMeta.withCode(code, 5, 2);
                String rz = MsavText.userReason(ctx, mm);
                String re = MsavText.userReason(enCtx, mm);
                if (rz == null || rz.isEmpty() || reasonFallback.equals(rz)) {
                    missCode.append(code).append(' ');
                }
                if (rz.equals(re)) localeDiff = false;
                if (code == MsavMeta.E_META_SHORT
                        && !(rz.contains("5") && rz.contains("2")
                             && re.contains("5") && re.contains("2"))) {
                    paramOk = false;
                }
            }
            ok(stat, L, missCode.length() == 0,
                    "★P3：8 个错误码**每一个**都有文案映射（漏映射=界面静默空白），漏的是［"
                            + missCode + "］");
            ok(stat, L, paramOk && localeDiff,
                    "★P3：错误码的参数真的进了句子（声明 5 项/读到 2 项），且两套语言文案不同");
        } catch (Throwable t) {
            ok(stat, L, false, "存档体检用例自身异常：" + t);
        } finally {
            deleteTree(dir);
        }
        L.add("");
    }

    /**
     * ㉞ 文案边界：**首尾空白会被 aapt2 剥掉**，靠拼串留的空格一定消失。
     *
     * ★ 起因（2026-10-04 真机 dump 抓到的现行）：版本行副标题渲染成
     *   `已导入 ·imported · 74.7 MB` —— 资源 `已导入 · ` 的**尾随空格被剥掉**。
     *   同一批还有三条**以空格开头**的尾巴串（` 还没生成` / ` · 无` / ` · 重名`），
     *   全都会与前面的文字粘在一起。⇒ 一律改成"整句资源 + `%1$s`"，不再拼串。
     *
     * ★ 判据（{@link #sepsOk}）：**每个 `·` 的两侧都必须是空格** —— 这正是被剥掉的那个东西，
     *   而且它对"粘起来"这种病是**有分辨力**的（元断言里拿真机那条坏串喂它，必须判死）。
     */
    private static void textEdgeWhitespace(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉞ 用户可见文案：首尾空白不许靠拼串留 ──");
        String rowImp = ctx.getString(R.string.row_imported_sub_fmt, "official-159.apk", "74.7 MB");
        String rowPlain = ctx.getString(R.string.row_sub_fmt, "io.anuke.mindustry", "74.7 MB");
        String notGen = ctx.getString(R.string.log_not_generated_fmt, "游戏日志");
        String chipAbsent = ctx.getString(R.string.log_chip_absent_fmt, "游戏日志");
        String dup = ctx.getString(R.string.mods_dup_fmt, "tmi");
        ok(stat, L, "已导入 · official-159.apk · 74.7 MB".equals(rowImp),
                "★导入项副标题逐字：" + rowImp);
        ok(stat, L, "io.anuke.mindustry · 74.7 MB".equals(rowPlain),
                "★已装版本副标题逐字：" + rowPlain);
        ok(stat, L, "游戏日志 还没生成".equals(notGen) && "游戏日志 · 无".equals(chipAbsent)
                        && "tmi · 重名".equals(dup),
                "★日志/模组三条整句：" + notGen + " ｜ " + chipAbsent + " ｜ " + dup);
        ok(stat, L, sepsOk(rowImp) && sepsOk(rowPlain) && sepsOk(chipAbsent) && sepsOk(dup),
                "★带 `·` 的四条：分隔符两侧都是空格");
        // 唯一一条**没有 `·`** 的（`%1$s 还没生成`）：它要的是"标签与话之间那个空格"还在
        ok(stat, L, notGen.indexOf("志 ") > 0 && !notGen.equals("游戏日志还没生成"),
                "★没有 `·` 的那条仍留着空格：" + notGen);
        // ★★ 元断言：拿**真机上抓到的那条坏串**喂判据，必须判死 —— 证明它不是在恒真地打勾
        ok(stat, L, !sepsOk("已导入 ·imported · 74.7 MB")
                        && !sepsOk("游戏日志· 无")
                        && sepsOk("已导入 · official-159.apk · 74.7 MB")
                        && sepsOk("游戏日志 · 无"),
                "★元断言：真机坏串（`已导入 ·imported`、`游戏日志· 无`）会被判死，"
                        + "而修好的串通过 ⇒ 判据有分辨力");
        // ★★ 2026-10-04（第 86 轮）：**按真参数实拼一遍**本轮新增/改动的每一条带占位符的资源。
        //   为什么值得单独钉：`getString(id, …)` 的参数个数与资源里的 `%n$` 不匹配会抛
        //   `MissingFormatArgumentException` **直接崩**，而它只在走到那一句时才现形。
        //   本轮的起因就是 `mods_toggle_msg_fmt` 写 `%6$s` 而调用点传 6 个（多传被忽略 ⇒ 不崩，
        //   但按 1 个参数调就炸）—— 下面第一条断言就是把"只传 1 个"这个契约钉死。
        String toggleMsg = ctx.getString(R.string.mods_toggle_msg_fmt,
                ctx.getString(R.string.mods_toggle_game_off));
        ok(stat, L, toggleMsg.contains("游戏没在运行") && toggleMsg.startsWith("改前会自动备份设置"),
                "★启停确认框按**一个**参数实拼（资源原写 %6$s）：" + toggleMsg);
        String okMsg = ctx.getString(R.string.mods_toggle_ok_fmt, "蓝钢拓展", "关闭");
        String failMsg = ctx.getString(R.string.mods_toggle_fail_msg_fmt, "游戏正在运行");
        String noopMsg = ctx.getString(R.string.mods_toggle_noop_msg_fmt, "蓝钢拓展", "启用");
        ok(stat, L, okMsg.equals("已把「蓝钢拓展」关闭。")
                        && failMsg.startsWith("没改成：游戏正在运行")
                        && noopMsg.equals("「蓝钢拓展」本来就是启用的，文件一个字节都没动。"),
                "★启停结果弹窗三条整句实拼：" + okMsg + " ｜ " + noopMsg);
        String ow = ctx.getString(R.string.msav_overwrite_msg_fmt, "default", "困难模式.msav");
        ok(stat, L, ow.contains("default") && ow.contains("困难模式.msav")
                        && ow.contains("找不回来"),
                "★同名存档覆盖确认两句实拼：" + ow.replace('\n', ' '));
        String lp = ctx.getString(R.string.launch_progress_msg_fmt, "MindustryX  2026.09.X37");
        ok(stat, L, lp.contains("正在准备存档槽") && lp.contains("2026.09.X37"),
                "★启动进度提示实拼：" + lp.replace('\n', ' '));
        String ep = ctx.getString(R.string.maps_empty_fmt, "default");
        ok(stat, L, ep.contains("default") && ep.contains("给某个版本分配这个槽"),
                "★地图页空态实拼：" + ep.replace('\n', ' '));
        L.add("");
    }

    /**
     * 判据：串里**每个 `·` 的两侧都是空格**，且至少有一个 `·`。
     * ⚠️ 只管"分隔符有没有被粘住"，不管文案好不好 —— 那是人看的。
     */
    private static boolean sepsOk(String s) {
        if (s == null) return false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '·') continue;
            any = true;
            boolean left = i > 0 && s.charAt(i - 1) == ' ';
            boolean right = i + 1 < s.length() && s.charAt(i + 1) == ' ';
            if (!left || !right) return false;
        }
        return any;
    }

    /**
     * ㉟ 模组详情首层那句「简介」的取值口径（{@link ModsActivity#tagline}）。
     *  · 优先模组自己声明的 `subtitle`（作者本来就当标语写的）；
     *  · 没有才退回 `description` 的**第一行**，并截到 60 字；
     *  · 两头都空 ⇒ 空串 ⇒ 调用方**不显示这一行**（"拿不到就把那块收掉"，别留空框）。
     */
    private static void modTagline(List<String> L, int[] stat) {
        L.add("── ㉟ 模组详情首层的「简介」──");
        Mods.Info a = new Mods.Info();
        a.subtitle = "结构化逻辑积木";
        a.description = "这段不该被用到";
        ok(stat, L, "结构化逻辑积木".equals(ModsActivity.tagline(a)),
                "★有 subtitle ⇒ 就用它（description 不参与）");
        Mods.Info b = new Mods.Info();
        b.description = "第一行说明\n第二行不该出现";
        ok(stat, L, "第一行说明".equals(ModsActivity.tagline(b)),
                "★没有 subtitle ⇒ 退回 description 的**第一行**：「" + ModsActivity.tagline(b) + "」");
        Mods.Info c = new Mods.Info();
        StringBuilder long80 = new StringBuilder();
        for (int i = 0; i < 80; i++) long80.append('说');
        c.description = long80.toString();
        String cut = ModsActivity.tagline(c);
        ok(stat, L, cut.length() == 61 && cut.endsWith("…"),
                "★超长截到 60 字 + 省略号（实际长度 " + cut.length() + "）");
        Mods.Info d = new Mods.Info();
        ok(stat, L, ModsActivity.tagline(d).isEmpty(),
                "★元断言：两头都空 ⇒ **空串**（不是「暂无简介」那种占位），调用方据此整行不收");
        L.add("");
    }

    /** 造一份最小合法 .msav：zlib(MSAV + int 版本 + meta 块 + 一块假 region)；cutTail 砍掉最后 2 字节 */
    private static File tinyMsav(File f, String name, int w, int h, int wave, boolean cutTail)
            throws Exception {
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(plain);
        d.write(MsavMeta.MAGIC);
        d.writeInt(11);
        java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream cd = new java.io.DataOutputStream(chunk);
        java.util.LinkedHashMap<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("name", name);
        tags.put("width", String.valueOf(w));
        tags.put("height", String.valueOf(h));
        tags.put("wave", String.valueOf(wave));
        tags.put("playtime", "3720000");
        tags.put("build", "153");
        tags.put("saved", "1700000000000");       // 存档时间（人读文案要用）
        cd.writeShort(tags.size());
        for (java.util.Map.Entry<String, String> e : tags.entrySet()) {
            cd.writeUTF(e.getKey());
            cd.writeUTF(e.getValue());
        }
        cd.flush();
        byte[] cp = chunk.toByteArray();
        d.writeInt(cp.length);
        d.write(cp);
        d.writeInt(4);
        d.write(new byte[]{1, 2, 3, 4});          // 假的 map 区：只为让"排空校验"有东西可读
        d.flush();
        byte[] all = plain.toByteArray();
        // 🔴 要造"截断的 zlib 文件"必须**先完整压缩、再砍压缩后的字节**：
        //   砍明文再压回会得到一条**合法但更短**的流，那样根本触发不了 EOFException
        //   （我第一版就是这么写的，自检那条元断言当场判死）。
        java.io.ByteArrayOutputStream zbuf = new java.io.ByteArrayOutputStream();
        java.util.zip.DeflaterOutputStream zo = new java.util.zip.DeflaterOutputStream(zbuf);
        zo.write(all);
        zo.close();
        byte[] zbytes = zbuf.toByteArray();
        if (cutTail) zbytes = java.util.Arrays.copyOf(zbytes, Math.max(1, zbytes.length - 2));
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
        fo.write(zbytes);
        fo.close();
        return f;
    }

    /**
     * ㉓ F10：快照列表那半 —— `Backup.msavLine()`（v1 旧格式目录里是真文件；v2 走 CAS 池）。
     * ★ 两条反向断言是关键：**没有存档**、**池里缺对象**都必须静默返回空串（不许抛、不许编一行）。
     */
    private static void msavSnapshot(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉓ F10 快照列表的「这是什么存档」──");
        File root = new File(Paths.privateDir(ctx), "selftest-snap");
        deleteTree(root);
        root.mkdirs();
        try {
            // ① v1（旧格式）：快照目录里就是真文件
            File snap = new File(root, "20260101-000000");
            File saves = new File(new File(snap, "files"), "saves");
            saves.mkdirs();
            tinyMsav(new File(saves, "a.msav"), "快照地图", 320, 320, 7, false);
            Util.atomicWriteText(new File(snap, "manifest.txt"),
                    "mdt-backup 1\n---\nmd5h\t123\tsaves/a.msav\n");
            Backup.Snapshot ss = new Backup.Snapshot();
            ss.dir = snap; ss.slot = "s"; ss.version = 1; ss.count = 1; ss.bytes = 123;
            String line = Backup.msavLine(ctx, ss);
            ok(stat, L, line.contains("快照地图") && line.contains("玩了") && line.contains("存档于"),
                    "★v1 快照（目录里是真文件）⇒ 读出「地图名 · 玩了多久 · 存档时间」：" + line);

            // ② 反向：快照里没有存档 ⇒ 空串（界面退回时间戳那一行）
            File snap2 = new File(root, "empty");
            new File(new File(snap2, "files"), "saves").mkdirs();
            Backup.Snapshot ss2 = new Backup.Snapshot();
            ss2.dir = snap2; ss2.slot = "s"; ss2.version = 1;
            ok(stat, L, Backup.msavLine(ctx, ss2).isEmpty(),
                    "★反向：快照里没有存档 ⇒ 返回空串（不是编一行出来）");

            // ③ 反向：v2 清单指向一个池里不存在的对象 ⇒ 不抛、返回空串
            File snap3 = new File(root, "cas-missing");
            snap3.mkdirs();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 64; i++) hex.append("ab".charAt(i % 2));
            Util.atomicWriteText(new File(snap3, "manifest.txt"),
                    "mdt-backup 2\n---\n" + hex + "\t999\tsaves/x.msav\n");
            Backup.Snapshot ss3 = new Backup.Snapshot();
            ss3.dir = snap3; ss3.slot = "s"; ss3.version = 2;
            ok(stat, L, Backup.msavLine(ctx, ss3).isEmpty(),
                    "★负例：CAS 池里对象缺失 ⇒ 返回空串且**不抛异常**（加分项不该拖垮界面）");
        } catch (Throwable t) {
            ok(stat, L, false, "快照用例自身异常：" + t);
        } finally {
            deleteTree(root);
        }
        L.add("");
    }

    /**
     * ㉔ F10：地图清点 —— 三个来源都要能列出来，且元数据是从 zip/APK 条目里**流式**读的。
     *
     * ★ 用"假的 APK/模组包"（就是普通 zip）来测，不必依赖设备上装了哪个版本；
     *   槽内那一组直接调包内可见的 scanSlotMaps()（不走 Maps.scan()，那会依赖设备上真实的
     *   槽目录布局 ⇒ 断言会随环境飘）。
     */
    private static void mapsScan(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉔ F10 地图清点（本槽 / 游戏 APK / 模组包）──");
        File root = new File(Paths.privateDir(ctx), "selftest-maps");
        deleteTree(root);
        root.mkdirs();
        try {
            // ① 本槽：maps/ 里放一张好图 + 一张垃圾（都要列出来，垃圾那张带原因）
            File maps = new File(root, "maps");
            maps.mkdirs();
            tinyMsav(new File(maps, "myslot.msav"), "我的图", 128, 128, 1, false);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(maps, "junk.msav"));
            fo.write(new byte[]{9, 9, 9, 9});
            fo.close();
            List<Maps.Item> slotItems = new ArrayList<>();
            Maps.scanSlotMaps(ctx, maps, slotItems);
            ok(stat, L, slotItems.size() == 2 && slotItems.get(0).from == Maps.FROM_SLOT,
                    "★本槽来源：两张 .msav 都列出来（" + slotItems.get(0).name() + " / "
                            + slotItems.get(1).name() + "）");
            boolean goodOne = false, badOne = false;
            for (Maps.Item i : slotItems) {
                if (i.meta != null && i.meta.ok && "我的图".equals(i.meta.name)) goodOne = true;
                if (i.meta != null && !i.meta.ok && i.error != null) badOne = true;
            }
            ok(stat, L, goodOne && badOne,
                    "★好的带元数据、坏的带原因（**不静默丢**）");
            java.io.FileOutputStream pf = new java.io.FileOutputStream(new File(maps, "half.msav.part"));
            pf.write(new byte[]{1});
            pf.close();
            List<Maps.Item> slotItems2 = new ArrayList<>();
            Maps.scanSlotMaps(ctx, maps, slotItems2);
            ok(stat, L, slotItems2.size() == 2, "★反向：.msav.part（写了一半）不算地图，不进列表");

            // ② 游戏 APK：assets/maps/ 下的图（用假 APK = 普通 zip）
            File apk = new File(root, "fake.apk");
            zipMsav(apk, "AndroidManifest.xml", "x",
                    "assets/maps/default/archipelago.msav", "群岛", 96, 96);
            List<Maps.Item> fromApk = new ArrayList<>();
            Maps.scanContainer(ctx, apk, "assets/maps/", Maps.FROM_GAME, "游戏自带", fromApk);
            ok(stat, L, fromApk.size() == 1 && fromApk.get(0).meta != null && fromApk.get(0).meta.ok
                            && "群岛".equals(fromApk.get(0).meta.name)
                            && fromApk.get(0).line(ctx).contains("96 × 96"),
                    "★游戏自带：从 APK 的 assets/maps 条目流式读出「真名 · 尺寸」："
                            + fromApk.get(0).line(ctx));

            // ③ 模组包：maps/ 下的图，且要标出来自哪个包
            File mz = new File(root, "real-mod.zip");
            zipMsav(mz, "mod.json", "name: Real Mod\n", "maps/real.msav", "模组地图", 64, 64);
            List<Maps.Item> fromMod = new ArrayList<>();
            Maps.scanContainer(ctx, mz, "maps/", Maps.FROM_MOD, mz.getName(), fromMod);
            ok(stat, L, fromMod.size() == 1 && fromMod.get(0).meta != null && fromMod.get(0).meta.ok
                            && "模组地图".equals(fromMod.get(0).meta.name)
                            && "real-mod.zip".equals(fromMod.get(0).source),
                    "★模组自带：读出真名，并标出来自「" + fromMod.get(0).source + "」");

            // 反向：来源前缀不对 ⇒ 一条都不出（不是"有 zip 就算有地图"）
            List<Maps.Item> none = new ArrayList<>();
            Maps.scanContainer(ctx, mz, "assets/maps/", Maps.FROM_GAME, "游戏自带", none);
            ok(stat, L, none.isEmpty(), "★反向：用错来源前缀 ⇒ 一条都不出");
            // 反向：容器打不开 ⇒ 空列表且不抛
            File notZip = new File(root, "not-a-zip.apk");
            java.io.FileOutputStream nf = new java.io.FileOutputStream(notZip);
            nf.write(new byte[]{1, 2, 3});
            nf.close();
            List<Maps.Item> brokenC = new ArrayList<>();
            Maps.scanContainer(ctx, notZip, "assets/maps/", Maps.FROM_GAME, "x", brokenC);
            ok(stat, L, brokenC.isEmpty(), "★反向：容器打不开 ⇒ 空列表且**不抛异常**");
        } catch (Throwable t) {
            ok(stat, L, false, "地图清点用例自身异常：" + t);
        } finally {
            deleteTree(root);
        }
        L.add("");
    }

    /** 造一个"容器里带一张真 .msav"的 zip（冒充模组包 / 游戏 APK） */
    private static void zipMsav(File zip, String extraName, String extraText,
                                String msavEntry, String mapName, int w, int h) throws Exception {
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmpmsav");
        tinyMsav(tmp, mapName, w, h, 1, false);
        java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zip));
        zo.putNextEntry(new java.util.zip.ZipEntry(extraName));
        zo.write(extraText.getBytes("UTF-8"));
        zo.closeEntry();
        zo.putNextEntry(new java.util.zip.ZipEntry(msavEntry));
        zo.write(java.nio.file.Files.readAllBytes(tmp.toPath()));
        zo.closeEntry();
        zo.close();
        tmp.delete();
    }

    /**
     * ㉕ 数据根残留的**策略边界**（第 57 轮把体检改成全自动，这条就是它的安全前提）。
     * ★ 一句话：**自动删的只有 `redundant`（有正证据的启动器残留）**；
     *   `suspicious`（名字像、证据不足）永远不碰 —— 所以"不问用户"才是安全的。
     * ★ 三条断言：只删 redundant / 明细里说清几项 / 再清一遍是 0 项（不虚报）。
     */
    private static void healthPolicy(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉕ 数据根残留：自动清理的策略边界 ──");
        File root = new File(Paths.privateDir(ctx), "selftest-health");
        deleteTree(root);
        root.mkdirs();
        try {
            File red = new File(root, "config.json");
            write(red, "{}".getBytes("UTF-8"));
            File sus = new File(root, "natives");
            sus.mkdirs();
            write(new File(sus, "libarc.so"), new byte[]{1, 2});
            Data.Health h = new Data.Health();
            h.redundant.add(new Data.Finding("单测", red, true, 1, 2, "假装有正证据"));
            h.suspicious.add(new Data.Finding("单测", sus, false, 1, 2, "假装证据不足"));
            String rep = Data.cleanRedundant(ctx, h);
            ok(stat, L, !red.exists() && sus.isDirectory() && new File(sus, "libarc.so").exists(),
                    "★只删 redundant：证据不足的那项**原样还在**（这是「自动清」敢不问用户的前提）");
            ok(stat, L, rep.contains("1 项已清理"),
                    "报告里说清了几项：" + rep.replace(String.valueOf((char) 10), String.valueOf((char) 32)));
            String rep2 = Data.cleanRedundant(ctx, h);
            ok(stat, L, rep2.contains("0 项已清理"), "★反向：再清一遍是 0 项（不虚报）");
        } catch (Throwable t) {
            ok(stat, L, false, "体检策略用例自身异常：" + t);
        } finally {
            deleteTree(root);
        }
        L.add("");
    }

    /**
     * ㉖ 「点开地图看详情」的文案（第 60 轮）。
     * ★ 判据要点：给**用户**看的详情里必须有人话字段（尺寸/作者/来自），
     *   **不能**混进 `report()` 那种维护者视角的话（「毫秒时间戳」「元数据项 N 条」）；
     *   色码要去掉；读不出来的项**不许**长得像正常地图。
     */
    private static void mapDetail(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉖ 点开一项看到的详细信息 ──");
        File root = new File(Paths.privateDir(ctx), "selftest-mapsdetail");
        deleteTree(root);
        root.mkdirs();
        try {
            File maps = new File(root, "maps");
            maps.mkdirs();
            tinyMsav(new File(maps, "colored.msav"), "[gold]金色[red]地图[]", 320, 240, 5, false);
            java.io.FileOutputStream jo = new java.io.FileOutputStream(new File(maps, "junk.msav"));
            jo.write(new byte[]{7, 7, 7});
            jo.close();
            List<Maps.Item> items = new ArrayList<>();
            Maps.scanSlotMaps(ctx, maps, items);
            Maps.Item good = null, bad = null;
            for (Maps.Item i : items) {
                if (i.meta != null && i.meta.ok) good = i;
                else bad = i;
            }
            String gd = good == null ? "" : good.detail(ctx);
            ok(stat, L, good != null && gd.contains("金色地图") && gd.contains("320 × 240")
                            && gd.contains("来自：本槽") && gd.contains("文件：colored.msav")
                            && !gd.contains("[gold]") && !gd.contains("毫秒") && !gd.contains("元数据项"),
                    "★详情：真名/尺寸/来源/文件都在，且**没有色码、没有维护者视角的话**（"
                            + gd.replace(String.valueOf((char) 10), String.valueOf((char) 32)) + "）");
            ok(stat, L, bad != null && bad.detail(ctx).contains("读不出来")
                            && !bad.detail(ctx).contains("尺寸："),
                    "★反向：读不出来的那一项，详情里**不许**出现正常地图才有的字段（不伪装成功）");
        } catch (Throwable t) {
            ok(stat, L, false, "地图详情用例自身异常：" + t);
        } finally {
            deleteTree(root);
        }
        L.add("");
    }

    /**
     * ㉗ F10 预览：**配色表**（从版本 APK 里读）、**渲染**、以及三条负例。
     * ★ 正面"真地图能解出来"的判据不在这里，而在两处更硬的证据：
     *   · PC 实验室：全语料 **386/386** 解码成功且**载荷刚好读完**（REF §55.15）；
     *   · 真机：`default` 槽 114 张游戏自带地图**全部渲染成缩略图**并落盘缓存（REF §55.17）。
     *   自检这边盯的是"接口在坏输入下不许崩、不许假装成功"。
     */
    private static void mapPreview(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉗ F10 预览：配色表 / 渲染 / 负例 ──");
        try {
            // ① 配色表：从**这个槽指向的版本 APK**里读（这是"不用自己导表"的关键设计）
            String apk = Mods.targetsFor(ctx, Data.currentSlot(ctx)).apkPath;
            int[] pal = MapLoad.palette(ctx, apk);
            int colored = 0;
            if (pal != null) for (int c : pal) if ((c & 0xFFFFFF) != 0) colored++;
            ok(stat, L, pal != null && pal.length > 300 && colored > 100,
                    "★配色表：从版本 APK 的 sprites/block_colors.png 读出 " 
                            + (pal == null ? 0 : pal.length) + " 格（其中 " + colored + " 格有颜色）");
            // ② 负例：垃圾/不存在的输入一律 null，**不抛**
            File junk = writeTmp(new File(Paths.privateDir(ctx), "selftest-preview"), "junk.msav",
                    "not-a-msav-at-all");
            ok(stat, L, MsavTiles.read(junk, true) == null
                            && MsavTiles.read(new File(junk.getParentFile(), "nope.msav"), true) == null,
                    "★负例：垃圾文件 / 不存在的文件 ⇒ 解不出来返回 null（不抛、不崩界面）");
            ok(stat, L, MapPreview.render(null, pal, 128) == null,
                    "★负例：没有瓦片 ⇒ 渲染返回 null（界面显示占位，不画一张假图）");
            // ③ 元断言：渲染器**真的有分辨力** —— 给它一份人造瓦片，必须画出多种颜色
            // ⚠️ 夹具必须尊重真实约定：**id 0（air）在配色表里是"没有颜色"**。
            //   我第一版给每个 id 都塞了颜色 ⇒ 方块色永远盖住地板 ⇒ 只出 2 色，
            //   "元断言"因此变成了在测空气（渲染器其实是对的，是夹具没有分辨力）。
            MsavTiles.Tiles fake = new MsavTiles.Tiles();
            fake.width = 8;
            fake.height = 8;
            fake.floors = new short[64];
            fake.ores = new short[64];
            fake.blocks = new short[64];
            int[] fakePal = new int[8];
            fakePal[0] = 0;                 // air：**没有配色**（真实配色表就是这个约定）
            fakePal[1] = 0xFF804020;        // 一种方块色
            fakePal[2] = 0xFF204080;        // 一种地板色
            fakePal[3] = 0xFF808080;        // 另一种地板色
            fakePal[4] = 0xFF40A040;        // 矿色（要与地板按 alpha 混合）
            for (int i = 0; i < 64; i++) {
                fake.floors[i] = (short) (2 + (i % 2));           // 两种地板轮换
                fake.blocks[i] = (short) (i % 4 == 0 ? 1 : 0);    // 四分之一的格子盖方块
                // ⚠️ 矿必须落在**没有方块**的格子上：`i%8==0` 必然同时满足 `i%4==0`
                //   ⇒ 方块色永远盖住矿 ⇒ 混合那条路根本没被走到（这是第二次被夹具坑）。
                fake.ores[i] = (short) ((i % 8 == 2 || i % 8 == 5) ? 4 : 0);
            }
            MapPreview.Img img = MapPreview.render(fake, fakePal, 8);
            int distinct = 0;
            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            if (img != null) for (int c : img.pixels) seen.add(c);
            distinct = seen.size();
            ok(stat, L, img != null && img.width == 8 && img.height == 8 && distinct >= 4,
                    "★元断言：人造瓦片渲染出 " + distinct + " 种颜色（≥4）⇒ 渲染器不是「画一片纯色」的假货");
            // ④ 缓存键不能撞（撞了就会显示别人的预览图）
            ok(stat, L, !MapLoad.md5("a|1").equals(MapLoad.md5("a|2"))
                            && MapLoad.md5("x").length() == 16,
                    "★缓存键：不同来源给出不同短哈希（撞了会显示错图），长度固定 16");
        } catch (Throwable t) {
            ok(stat, L, false, "预览用例自身异常：" + t);
        } finally {
            // ★ 2026-10-04 第 85 轮补：本用例 ② 写的 `app_hub/selftest-preview/junk.msav`
            //   原来**没人删** —— 每跑一次自检就在用户的私有目录里留一个 17 B 的垃圾文件
            //   （真机上抓到的现行：连跑两轮后它还在）。收尾清单要求"自检不留痕"，补上。
            deleteTree(new File(Paths.privateDir(ctx), "selftest-preview"));
        }
        L.add("");
    }

    /**
     * ㉘ F10 地图**增删**：导入必须"先 .part + 用解析器验 + 再就位"，删除必须"挪去中转站"。
     * 五条：好图导入成功 / 坏文件被拒且**什么都不留** / 同名不覆盖被拒 /
     *       覆盖时旧的**进中转站**（挪不删） / **槽外的地图删不动**。
     */
    private static void mapCrud(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉘ F10 地图增删（导入 / 删除）──");
        File root = new File(Paths.privateDir(ctx), "selftest-mapcrud");
        deleteTree(root);
        root.mkdirs();
        try {
            File maps = new File(root, "maps");
            File trash = new File(root, "trash");
            maps.mkdirs();
            // 造一张"真"地图当输入
            File src = new File(root, "input.msav");
            tinyMsav(src, "导入测试图", 64, 64, 1, false);
            byte[] bytes = java.nio.file.Files.readAllBytes(src.toPath());

            // ① 好图：导入成功，且 .part 不残留、内容与源一致
            MapFiles.Result r1 = MapFiles.importMap(ctx, maps, "导入测试图.msav",
                    new java.io.ByteArrayInputStream(bytes), false, trash);
            File dest = new File(maps, "导入测试图.msav");
            ok(stat, L, r1.ok && dest.isFile() && dest.length() == bytes.length
                            && !new File(maps, "导入测试图.msav.part").exists()
                            && r1.meta != null && "导入测试图".equals(r1.meta.name),
                    "★导入：落盘成功、内容一致、**没有 .part 残留**，且读出真名「导入测试图」");

            // ② 坏文件：被拒，且**什么都不留**（这是"不塞垃圾进槽"的关键）
            MapFiles.Result r2 = MapFiles.importMap(ctx, maps, "垃圾.msav",
                    new java.io.ByteArrayInputStream("not-a-map".getBytes("UTF-8")), false, trash);
            ok(stat, L, !r2.ok && r2.error != null && !new File(maps, "垃圾.msav").exists()
                            && !new File(maps, "垃圾.msav.part").exists(),
                    "★坏文件：拒收（" + r2.error + "）且**一个字节都没留下**");

            // ③ 同名不覆盖：拒绝（宁可不做，也不硬盖）
            MapFiles.Result r3 = MapFiles.importMap(ctx, maps, "导入测试图.msav",
                    new java.io.ByteArrayInputStream(bytes), false, trash);
            ok(stat, L, !r3.ok, "★同名且未确认覆盖 ⇒ 拒绝：" + r3.error);

            // ④ 确认覆盖：旧的那份**进中转站**（挪不删），新的就位
            byte[] bytes2;
            {
                File src2 = new File(root, "input2.msav");
                tinyMsav(src2, "第二版", 80, 80, 2, false);
                bytes2 = java.nio.file.Files.readAllBytes(src2.toPath());
            }
            MapFiles.Result r4 = MapFiles.importMap(ctx, maps, "导入测试图.msav",
                    new java.io.ByteArrayInputStream(bytes2), true, trash);
            File[] trashed = trash.listFiles();
            ok(stat, L, r4.ok && r4.overwrote && trashed != null && trashed.length == 1
                            && trashed[0].isFile() && trashed[0].length() == bytes.length
                            && dest.length() == bytes2.length,
                    "★确认覆盖：新图就位、**旧的那份在中转站里**（大小还是 " + bytes.length + " B）");

            // ⑤ 删除 = 挪不删；且**槽外的东西删不动**
            File outside = new File(root, "outside.msav");
            File dest2 = new File(maps, "将要删除.msav");
            java.nio.file.Files.copy(dest.toPath(), dest2.toPath());
            java.nio.file.Files.copy(dest.toPath(), outside.toPath());
            File moved = MapFiles.deleteToTrash(ctx, maps, dest2, trash);
            File notMoved = MapFiles.deleteToTrash(ctx, maps, outside, trash);
            ok(stat, L, moved != null && !dest2.exists() && moved.isFile()
                            && moved.length() == bytes2.length
                            && notMoved == null && outside.isFile(),
                    "★删除：槽内的挪进中转站（原位置消失、内容还在）；**槽外的动不了**（安全判据）");
        } catch (Throwable t) {
            ok(stat, L, false, "地图增删用例自身异常：" + t);
        } finally {
            deleteTree(root);
        }
        L.add("");
    }

    /**
     * ㉙ **清单里注册了哪些页面**（2026-10-03 事故教训）：
     * `aapt2` 不检查 Activity 引用 ⇒ **漏注册照样 BUILD OK**，真机一点就崩。
     * ⇒ 在自检里把"每个页面都能被 PackageManager 解析"钉死。
     */
    private static void activityRegistration(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉙ 清单注册检查（漏注册 = BUILD OK 但真机崩）──");
        String[] want = {"io.mdt.launcher.MainActivity", "io.mdt.launcher.SavesActivity",
                "io.mdt.launcher.ModsActivity", "io.mdt.launcher.SlotActivity",
                "io.mdt.launcher.MapsActivity", "io.mdt.launcher.MapDetailActivity"};
        int found = 0;
        for (String n : want) {
            if (canResolve(ctx, n)) found++;
        }
        ok(stat, L, found == want.length,
                "★" + want.length + " 个页面都能被解析到（含新加的 MapDetailActivity）：" + found + "/" + want.length);
        // ★ 元断言：这条判据**必须有分辨力** —— 编一个不存在的页面名，必须解析不到
        ok(stat, L, !canResolve(ctx, "io.mdt.launcher.NoSuchActivityForSelfTest"),
                "★元断言：不存在的页面名必须解析不到（否则上面那条等于在测空气）");
        L.add("");
    }

    /**
     * ㊱ **每个页面都必须继承 {@link BaseActivity}**（2026-10-04，第 86 轮）。
     *
     * 为什么单独立一条：深浅色的**唯一生效点**是 {@code BaseActivity.attachBaseContext}
     * （它把 base context 换成 `uiMode` 被改过的版本，见 {@link ThemeMode#wrap}）。
     * 页面只要写成 `extends Activity`，用户在设置里选的「浅色 / 深色」对它**完全无效** ——
     * 而症状是"进这一页配色突然变回系统色"，**不崩、不报错、logcat 一个字都没有**。
     * 本轮就是这么漏的：SlotActivity / MapsActivity / MapDetailActivity 三个页面
     * （2026-10-03 导航重构 + F21 新建）都写着 `extends Activity`，
     * 于是"主界面深色 → 点槽整页变白"，而自检当时对 BaseActivity/ThemeMode **零覆盖**。
     *
     * ★ 判据用 `isAssignableFrom`（继承链上任意一层是 BaseActivity 都算过），
     *   不看源码文本 —— 文本判据会被"中间再插一个基类"这类写法骗过去。
     * ★ 元断言：拿 `android.app.Activity` 这个**不是** BaseActivity 的类喂同一个判据，
     *   必须判否，否则这条可能只是在恒真地打勾。
     */
    private static void activityThemeBase(Context ctx, List<String> L, int[] stat) {
        L.add("── ㊱ 每个页面都必须继承 BaseActivity（否则深浅色设置对它无效）──");
        int n = 0;
        for (Class<?> c : PAGES) {
            boolean isBase = BaseActivity.class.isAssignableFrom(c);
            if (isBase) n++;
            ok(stat, L, isBase, "★ " + c.getSimpleName()
                    + " 继承 BaseActivity（深浅色靠它的 attachBaseContext）");
        }
        ok(stat, L, n == PAGES.length,
                "★ " + PAGES.length + " 个页面全部继承：" + n + "/" + PAGES.length);
        // ★ 元断言：判据必须有分辨力（`android.app.Activity` 本身不是 BaseActivity 的子类）
        ok(stat, L, !BaseActivity.class.isAssignableFrom(android.app.Activity.class),
                "★元断言：android.app.Activity 本身判否（说明这条不是在恒真地打勾）");

        // ★ 顶栏标题：`Activity.mTitle` 是**框架**在 attach() 里用 PackageManager 按**系统语言**
        //   解析 android:label 得到的 —— 我们在 attachBaseContext 里包的 Configuration **管不到它**
        //   ⇒ 必须由 BaseActivity.onCreate 按应用内语言重设一次（2026-10-04 用户报的
        //   "最上面的大标题没改"）。这条钉住"那次重设还在"：删了它，界面只会**静默**退回系统语言。
        boolean hasOnCreate = false;
        try {
            BaseActivity.class.getDeclaredMethod("onCreate", android.os.Bundle.class);
            hasOnCreate = true;
        } catch (NoSuchMethodException ignored) {
        }
        ok(stat, L, hasOnCreate,
                "★顶栏标题：BaseActivity 必须重写 onCreate（框架那份 label 是按系统语言取的）");
        // ★ 元断言：标题文案两套语言确实是两份 —— 否则"改了也看不出来"，上面那条就没有意义
        Context titleEn = LocaleMode.force(ctx, "en");
        ok(stat, L, !titleEn.getString(R.string.app_name).equals(ctx.getString(R.string.app_name)),
                "★元断言：app_name 两套语言是两份（en=「" + titleEn.getString(R.string.app_name)
                        + "」/ zh=「" + ctx.getString(R.string.app_name) + "」）");
        L.add("");
    }

    /**
     * ㉚ F10 地图**导出**（第 81 轮）：游戏自带 / 模组自带的地图在 APK / 模组包**里面**，
     * 所以导出那条路是"从 zip 条目流式拷贝"，不是拷文件。
     *
     * 判据（正 / 反 / 元三条都要有）：
     *   ① 拷出来的字节与源**逐字节相同**（长度相同不算证据）；
     *   ② 条目不存在 ⇒ **必须失败**（不许静默给个空文件让用户以为导出成功了）；
     *   ③ 容器根本不是 zip ⇒ 必须失败；
     *   ④ 元断言：源内容改 1 个字节 ⇒ 同一套比较必须判**不等** —— 否则①可能只是在比长度。
     */
    private static void mapExport(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉚ F10 地图导出（包内条目 → 出去；拷贝必须逐字节一致）──");
        File root = new File(Paths.privateDir(ctx), "selftest-mapexport");
        deleteTree(root);
        root.mkdirs();
        try {
            // 造一张真地图，塞进一个 zip（模拟"游戏自带 / 模组自带"的形态）
            File msav = new File(root, "导出测试图.msav");
            tinyMsav(msav, "导出测试图", 64, 64, 1, false);
            final byte[] want = java.nio.file.Files.readAllBytes(msav.toPath());
            File zip = new File(root, "容器.zip");
            java.util.zip.ZipOutputStream zos =
                    new java.util.zip.ZipOutputStream(new FileOutputStream(zip));
            try {
                zos.putNextEntry(new java.util.zip.ZipEntry("maps/导出测试图.msav"));
                zos.write(want);
                zos.closeEntry();
            } finally {
                zos.close();
            }

            // ① 正例：逐字节一致
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long n = Exporter.copyEntry(ctx, zip, "maps/导出测试图.msav", out);
            final byte[] got = out.toByteArray();
            ok(stat, L, n == want.length && java.util.Arrays.equals(got, want),
                    "★包里条目拷出来：" + n + " B，与源**逐字节一致**（源 " + want.length + " B）");

            // ② 反向：条目不存在
            String e1 = failOfCopy(ctx, zip, "maps/没有这一条.msav");
            ok(stat, L, e1 != null, "★反向：条目不存在 ⇒ 拒绝（" + e1 + "）");

            // ③ 反向：容器不是 zip
            File notZip = new File(root, "不是zip.bin");
            java.nio.file.Files.write(notZip.toPath(), "这不是一个 zip".getBytes("UTF-8"));
            String e2 = failOfCopy(ctx, notZip, "maps/随便.msav");
            ok(stat, L, e2 != null, "★反向：容器不是 zip ⇒ 拒绝（" + e2 + "）");

            // ④ 元断言：源改 1 个字节，同一套比较必须判不等
            want[want.length - 1] ^= 0x01;
            ok(stat, L, !java.util.Arrays.equals(got, want),
                    "★元断言：源改 1 个字节 ⇒ 同一套比较判**不等**（证明①有分辨力）");
        } catch (Throwable t) {
            ok(stat, L, false, "地图导出用例自身异常：" + t);
        } finally {
            deleteTree(root);
        }
        L.add("");
    }

    /** 拷贝**必须**失败时返回异常消息；成功返回 null（调用方据此判死） */
    private static String failOfCopy(Context ctx, File container, String entry) {
        try {
            Exporter.copyEntry(ctx, container, entry, new ByteArrayOutputStream());
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }

    /**
     * ㉛ 页面骨架（第 81 轮）：
     *
     * ① **每个页面布局的根节点都必须带 `@+id/root`** —— 那是 `Util.applySystemInsets(root)`
     *    的落点。缺了它，targetSdk ≥ 35 的 edge-to-edge 会让**整页从 y=0 开始画**，
     *    被状态栏 + 顶栏整块盖住（真机实测 bounds：[0,0][1224,112]：view 在树里、有文字，
     *    就是一个像素都看不见）。地图页和槽页面正是这么栽的。
     * ② 元断言：拿一份**根节点没有 id** 的布局喂同一个检查，必须判不合格
     *    （否则①可能对所有布局都恒真）。
     * ③ 需求断言：地图页「导入 / 导出」两行动作必须排在列表**之前**（用户要的"都放页面顶上"）。
     */
    private static void pageSkeleton(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉛ 页面骨架（根节点 @id/root + 地图页两行动作在顶上）──");
        final int[] layouts = {R.layout.activity_main, R.layout.activity_saves,
                R.layout.activity_mods, R.layout.activity_slot, R.layout.activity_maps,
                R.layout.activity_settings, R.layout.activity_log, R.layout.activity_map_detail};
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(ctx);
        int good = 0;
        StringBuilder bad = new StringBuilder();
        for (int id : layouts) {
            String name;
            try {
                name = ctx.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                name = String.valueOf(id);
            }
            try {
                android.view.View v = inf.inflate(id, null);
                if (v != null && v.getId() == R.id.root) {
                    good++;
                } else {
                    bad.append(name).append(' ');
                }
            } catch (Throwable t) {
                bad.append(name).append('(').append(t.getClass().getSimpleName()).append(") ");
            }
        }
        ok(stat, L, good == layouts.length,
                "★" + layouts.length + " 个页面布局的根节点都是 @id/root：" + good + "/" + layouts.length
                        + (bad.length() == 0 ? "" : "，不合格：" + bad));

        boolean meta;
        try {
            android.view.View v = inf.inflate(R.layout.dialog_msav_list, null);
            meta = !(v != null && v.getId() == R.id.root);
        } catch (Throwable t) {
            meta = true;                  // inflate 不了同样算"这条检查会拦住它"
        }
        ok(stat, L, meta, "★元断言：根节点没有 id 的布局（dialog_msav_list）必须判不合格");

        try {
            android.view.View v = inf.inflate(R.layout.activity_maps, null);
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            android.view.View actions = v.findViewById(R.id.maps_actions);
            android.view.View list = v.findViewById(R.id.maps_list);
            int ia = actions == null ? -1 : g.indexOfChild(actions);
            int il = list == null ? -1 : g.indexOfChild(list);
            boolean rows = v.findViewById(R.id.row_maps_import) != null
                    && v.findViewById(R.id.row_maps_export) != null;
            ok(stat, L, rows && ia >= 0 && il >= 0 && ia < il,
                    "★地图页：导入行 + 导出行都在动作卡里，且动作卡(第 " + ia + " 个) 排在列表(第 "
                            + il + " 个) **之前** ⇒ 两行就在页面顶上");
        } catch (Throwable t) {
            ok(stat, L, false, "地图页布局检查自身异常：" + t);
        }
        L.add("");
    }

    /**
     * ㉜ F21 地图资源统计（第 83 轮）：定义表 / 自带 JSON 读取器 / 模组叠加 / 地图补丁 /
     * 遮挡口径 / 译名与同名合并。
     *
     * 🔴 判据纪律：
     *   · 每条断言都拿**已知是坏/已知是好**的输入喂进去（见"元断言"那几条），
     *     否则"过了"可能只是判据在测空气；
     *   · 遮挡口径的对照来自**游戏自己的字段**（`stone-wall` 拆不掉 / `boulder` 能搬走 /
     *     `copper-wall` 是玩家墙能拆），不是我们给的一张名单。
     */
    private static void mapStats(Context ctx, List<String> L, int[] stat) {
        L.add("── ㉜ F21 地图资源统计（定义表 / JSON / 模组叠加 / 模组译文 / 补丁 / 遮挡 / 译名）──");

        // ① 原版内容表（447 条 = PC 侧从游戏 jar 导出的行数）
        MapStats.Table v = MapStats.vanilla();
        ok(stat, L, v.size() == 447, "原版内容表 447 条：" + v.size());
        MapStats.Def stoneWall = v.row("stone-wall");
        MapStats.Def boulder = v.row("boulder");
        MapStats.Def copperWall = v.row("copper-wall");
        MapStats.Def sand = v.row("sand-floor");
        ok(stat, L, stoneWall != null && !stoneWall.removable()
                        && boulder != null && boulder.removable()
                        && copperWall != null && copperWall.removable(),
                "★遮挡口径（照游戏字段）：石头墙拆不掉 / 石头能搬走 / 玩家墙能拆");
        ok(stat, L, sand != null && "sand".equals(sand.itemDrop) && sand.isFloor && !sand.isOre
                        && sand.bonus(),
                "沙地（内部名 sand-floor）：掉落 sand ⇒ 可采地板；同时带含油的属性 ⇒ 也算加成地板（一格两用）");
        // 元断言：表里**存在**拆不掉的（否则上面那条"能拆"的断言可能只是因为全都 removable）
        int perm = 0;
        for (String n : new String[]{"cliff", "sand-wall", "shale-wall", "dune-wall", "pine"}) {
            MapStats.Def d = v.row(n);
            if (d != null && !d.removable()) perm++;
        }
        ok(stat, L, perm == 5, "★元断言：5 种天然地形墙都判成「拆不掉」：" + perm + "/5");

        // ② 自带的极小 JSON 读取器（PC 可编可跑的代价就是把它写对）
        java.util.Map<String, Object> j = MapStats.jsonOf("{\"a\":{\"b\":[1,2.5,\"x\",true,null]},\"c\":\"\\u4e2d\",\"d\":-3}");
        boolean jok = j != null && j.get("a") instanceof java.util.Map && j.get("c") != null;
        if (jok) {
            @SuppressWarnings("unchecked") java.util.Map<String, Object> a = (java.util.Map<String, Object>) j.get("a");
            List<?> b = (List<?>) a.get("b");
            jok = b != null && b.size() == 5 && Long.valueOf(1L).equals(b.get(0))
                    && Double.valueOf(2.5).equals(b.get(1)) && "x".equals(b.get(2))
                    && Boolean.TRUE.equals(b.get(3)) && b.get(4) == null
                    && "中".equals(j.get("c")) && Long.valueOf(-3L).equals(j.get("d"));
        }
        ok(stat, L, jok, "自带 JSON 读取器：嵌套对象 / 数组 / 小数 / 转义 / 负数都对");
        // 元断言：坏 JSON 必须**判死**（不许"读不动就当空对象"）—— 直接喂自带的读取器
        boolean bad1 = false, bad2 = false;
        try {
            MapStats.Json.parse("{\"a\":");
        } catch (Throwable t) {
            bad1 = true;
        }
        try {
            MapStats.Json.parse("");
        } catch (Throwable t) {
            bad2 = true;
        }
        ok(stat, L, bad1 && bad2, "★元断言：半截 JSON 与空串都必须读失败（" + bad1 + "/" + bad2 + "）");

        // ③ 模组内容叠加：有 type ⇒ 新内容（取**类默认值**）；无 type ⇒ 只改 JSON 里写了的字段
        MapStats.Table t = MapStats.copyOf(v);
        MapStats.ModResult mr = new MapStats.ModResult();
        MapStats.Pack p = new MapStats.Pack();
        p.prefix = "ve";
        p.internal = "ve";
        p.label = "测试模组";
        MapStats.applyModContent(t, p, "content/blocks/environment/cyclant/tree.json",
                "{\n  type: StaticTree\n  breakable: true\n}", mr);
        MapStats.Def veTree = t.row("ve-tree");
        MapStats.Def pine = t.row("pine");
        ok(stat, L, veTree != null && veTree.removable() && pine != null && !pine.removable(),
                "★模组新内容取类默认值：ve 的松树（breakable:true）能拆，原版 pine 不能 —— 同一个类，相反行为");
        MapStats.applyModContent(t, p, "content/blocks/environment/salt.json", "{\n  itemDrop: sand\n}", mr);
        MapStats.Def salt = t.row("salt");
        ok(stat, L, salt != null && "sand".equals(salt.itemDrop) && salt.attributes.contains("water="),
                "★覆盖是「在旧行上改字段」：salt 改出掉落物后，含水的属性仍在");
        // 无 type 但名字谁都不认识 ⇒ 跳过（**不造幽灵行**：幽灵行会被误判成永久墙）
        String st1 = MapStats.applyModContent(t, p, "content/blocks/x/ghost.json", "{\n  solid: true\n}", mr);
        ok(stat, L, t.row("ve-ghost") == null && "目标不认识，跳过".equals(st1) && mr.skipped == 1,
                "★无 type + 目标不认识 ⇒ 跳过且不建行（" + st1 + "）");
        String st2 = MapStats.applyModContent(t, p, "content/blocks/x/y.json",
                "{\n  type: NoSuchBlockClassAtAll\n}", mr);
        ok(stat, L, t.row("ve-y") == null && mr.unknownType == 1 && "认不出的类型".equals(st2),
                "★元断言：认不出的类必须被计数且不建行（否则它会带着全 0 标志混进统计）");

        // ④ 地图自带的数据补丁
        MapStats.Table t2 = MapStats.copyOf(v);
        int changed = MapStats.applyPatch(t2,
                "{\"name\":\"Patch0\",\"block\":{\"dark-metal\":{\"attributes\":{\"scrapmetal\":2}}}}", null);
        MapStats.Def dm = t2.row("dark-metal");
        ok(stat, L, changed == 1 && dm != null && dm.attributes.contains("scrapmetal=2"),
                "数据补丁把 dark-metal 改成有属性（掉废料），改动数 " + changed);

        // ⑤ 补丁区字节格式（造一份真的，再故意破坏）
        byte[] good = patchBytes("patch-x.json", "{\"name\":\"P\"}");
        List<MsavPatches.Entry> pe = MsavPatches.parse(good);
        ok(stat, L, pe != null && pe.size() == 1 && pe.get(0).text.contains("P"),
                "补丁区解析：1 条 patch 条目");
        byte[] tail = new byte[good.length + 1];
        System.arraycopy(good, 0, tail, 0, good.length);
        ok(stat, L, MsavPatches.parse(tail) == null,
                "★元断言：补丁区尾部多 1 个字节就必须判死（「刚好读完」这条判据有分辨力）");

        // ⑥ 统计与遮挡三分：手工造一张 3×3 的图（不依赖任何文件）
        MsavTiles.Tiles gt = grid();
        MapStats.Names nm = fakeNames();
        MapStats.Result r = MapStats.analyze(gt, v, nm);
        MapStats.Row cu = rowOf(r.ores, "铜");
        MapStats.Row th = rowOf(r.ores, "钍");
        MapStats.Row fl = rowOf(r.floors, "sand-floor");
        MapStats.Row bo = rowOf(r.bonuses, "salt");
        ok(stat, L, r.ok && cu != null && cu.reachable() == 2 && cu.buried == 1,
                "矿物：铜 3 格 = 能采 2（露天 1 + 石头下 1） + 墙下 1"
                        + (cu == null ? "" : "（实际 " + cu.reachable() + "/" + cu.buried + "）"));
        ok(stat, L, th != null && th.total == 2 && th.internals.size() == 2,
                "同名合并：ore-thorium 与 ore-crystal-thorium 都叫「钍」⇒ 合成 1 行 2 格"
                        + (th == null ? "" : "（实际 " + th.total + " 格 / " + th.internals.size() + " 个内部名）"));
        ok(stat, L, fl != null && fl.reachable() == 1 && "沙".equals(fl.drop),
                "可采地板：沙子 1 格、掉落物是「沙」");
        ok(stat, L, bo != null && bo.attrs.contains("水") && bo.attrs.contains("油"),
                "加成地板：盐地带的属性词是「水 油」");
        ok(stat, L, r.blockers.size() == 1 && "stone-wall".equals(r.blockers.get(0).label),
                "永久遮挡者名单里只有石头墙"
                        + (r.blockers.isEmpty() ? "" : "（实际 " + r.blockers.get(0).label + "）"));
        // 元断言：把那格永久墙换成**玩家墙**（能拆）⇒ 被埋的那一格必须变成"能采"
        MsavTiles.Tiles gt2 = grid();
        gt2.blockNames.set(4, "copper-wall");
        MapStats.Result r2 = MapStats.analyze(gt2, v, nm);
        MapStats.Row cu2 = rowOf(r2.ores, "铜");
        ok(stat, L, cu2 != null && cu2.reachable() == 3 && cu2.buried == 0
                        && r2.blockers.isEmpty(),
                "★元断言：永久墙换成玩家墙后，铜的「能采」从 2 变 3、「墙下」归 0"
                        + (cu2 == null ? "" : "（实际 " + cu2.reachable() + "/" + cu2.buried + "）"));
        // 反向：认不出的遮挡者算"未知"，**不许**混进"能采"
        MsavTiles.Tiles gt3 = grid();
        gt3.blockNames.set(4, "mystery-mod-wall");
        MapStats.Result r3 = MapStats.analyze(gt3, v, nm);
        MapStats.Row cu3 = rowOf(r3.ores, "铜");
        ok(stat, L, cu3 != null && cu3.unknown == 1 && cu3.reachable() == 2,
                "★反向：认不出的遮挡者记成「未知」，绝不算进「能采」"
                        + (cu3 == null ? "" : "（实际 未知 " + cu3.unknown + " / 能采 " + cu3.reachable() + "）"));

        // ⑦ 译名链：**模组自己的 bundle** ⨁ 版本 APK 的 bundle（模组优先 —— 与游戏叠加顺序一致）
        File tmp = new File(Paths.privateDir(ctx), "selftest-mapstats");
        deleteTree(tmp);
        try {
            File modDir = new File(tmp, "modpkg");
            File bundles = new File(modDir, "bundles");
            if (!bundles.mkdirs()) throw new IOException("建不了测试目录：" + bundles);
            write(new File(bundles, "bundle_zh_CN.properties"),
                    ("item.ve-aluminium.name=铝土\n"
                            + "block.ve-melondirt.name=甜瓜土\n"
                            + "item.copper.name=模组铜\n"
                            + "item.titanium.name=模组钛\n"
                            + "item.ve-copper.name=模组铜2\n").getBytes("UTF-8"));
            // ★ 同一个 `bundles/` 目录里**再放一份繁体**：这是真机实测的情况
            //   （模组同时带 bundle_zh_CN 与 bundle_zh_TW）⇒ 两份都读会互相覆盖，
            //   中文界面显示成「釷/鈹/鎢」那种繁体。每个目录只能取**一份**。
            write(new File(bundles, "bundle_zh_TW.properties"),
                    ("item.copper.name=模組銅\n"
                            + "item.titanium.name=模組鈦\n").getBytes("UTF-8"));
            // 模组的**基础层**（层序判据要用：语言层压得住模组的基础层；基础层里模组压得住版本）
            write(new File(bundles, "bundle.properties"),
                    "item.lead.name=模组铅\nitem.silicon.name=模组硅\n".getBytes("UTF-8"));
            MapStats.Pack mp = new MapStats.Pack();
            mp.dir = modDir;
            mp.label = "测试模组";
            MapStats.Bundles modBundle = MapStats.readBundles(
                    java.util.Collections.singletonList(mp), MapStats.bundleLang("zh_CN"));
            File fakeApk = new File(tmp, "fake.apk");
            zipMany(fakeApk,
                    new String[]{"assets/bundles/bundle_zh_CN.properties",
                            "assets/bundles/bundle.properties"},
                    new String[]{"item.copper.name=铜\nitem.silicon.name=硅\n",
                            "item.copper.name=Copper\nitem.lead.name=Lead\n"
                                    + "item.silicon.name=Silicon\nitem.titanium.name=Titanium\n"});
            BundleNames bn = new BundleNames(fakeApk, modBundle, null,
                    MapStats.bundleLang("zh_CN"), "%1$s（墙）");
            ok(stat, L, "铝土".equals(bn.item("ve-aluminium")) && "甜瓜土".equals(bn.block("ve-melondirt")),
                    "★模组物品/方块的译名从**模组自己的 bundle** 里捞（版本 APK 里没有这些名字）");
            ok(stat, L, "模组铜".equals(bn.item("copper")),
                    "★同一层里**模组覆盖版本 APK**（游戏把两者写进同一个 map，模组后加载）");
            ok(stat, L, "硅".equals(bn.item("silicon")),
                    "★层序：版本 APK 的**语言包**压得住模组的**基础包**");
            ok(stat, L, "模组铅".equals(bn.item("lead")),
                    "★基础层里同样是模组覆盖版本 APK（铅只在两边的 base 里）");
            ok(stat, L, "".equals(bn.item("no-such-item")) && "钍（墙）".equals(bn.wallName("钍")),
                    "★反向：两层都查不到 ⇒ 返回空串（调用方退回内部名的可读化形式）；"
                            + "矿墙名是**整名**（`钍（墙）` 而不是拼一个后缀 —— 英文那句要 `Thorium (wall)`，"
                            + "前导空格写进资源会被 aapt2 剥掉）");
            ok(stat, L, mp.bundleKeys == 7 && modBundle.keys() == 7,
                    "模组 bundle 读到 7 条（语言包 5 + 基础包 2；技术细节层要能报出这个数）："
                            + modBundle.keys());
            // ★★ 同目录里 CN/TW 都在 ⇒ **只能取一份**（取错/取两份会显示成繁体，且条数会变多）
            ok(stat, L, "模组铜".equals(bn.item("copper")) && !"模組銅".equals(bn.item("copper")),
                    "★★同目录 `bundle_zh_CN` + `bundle_zh_TW` ⇒ 只取**分数最高的那一份**："
                            + "want=zh_CN 时显示简体「" + bn.item("copper") + "」（不是繁体「模組銅」）");
            BundleNames tw = new BundleNames(fakeApk,
                    MapStats.readBundles(java.util.Collections.singletonList(mp),
                            MapStats.bundleLang("zh_TW")),
                    null, MapStats.bundleLang("zh_TW"), "%1$s（牆）");
            ok(stat, L, "模組銅".equals(tw.item("copper")) && "模組鈦".equals(tw.item("titanium")),
                    "★反向：want=zh_TW 时取的是**繁体**那份「" + tw.item("copper")
                            + "」—— 证明上面那条不是「恒取 CN」（判据有分辨力）");
            // ★★ P4 的**分辨力**判据：同一份假 APK，只把"界面语言"从 zh_CN 换成 en，
            //    读到的名字必须**真的不一样** —— 否则"矿名跟着界面语言走"就只是句话。
            //    ⚠️ 模组那份 bundle 也要**按同一门语言重读**（`BundleNames` 会无条件合并传进来的
            //       那两层 —— 产品侧靠"缓存键含语言"保证两者同源，见 MapStatsMods.key）。
            MapStats.Bundles modBundleEn = MapStats.readBundles(
                    java.util.Collections.singletonList(mp), MapStats.bundleLang("en"));
            BundleNames en = new BundleNames(fakeApk, modBundleEn, null,
                    MapStats.bundleLang("en"), "%1$s (wall)");
            ok(stat, L, "Copper".equals(en.item("copper")) && "Titanium".equals(en.item("titanium"))
                            && "".equals(en.item("ve-aluminium"))
                            && "模组铅".equals(en.item("lead"))
                            && "Thorium (wall)".equals(en.wallName("Thorium")),
                    "★★界面语言换 en ⇒ 读**基础包**（`bundle.properties` = 官方那份英文）：铜/钛变 Copper/Titanium、"
                            + "只存在于 zh 语言包里的名字查不到、矿墙名用整句模板。"
                            + "铜（zh）=「" + bn.item("copper") + "」/ 铜（en）=「" + en.item("copper") + "」");
            ok(stat, L, !en.item("copper").equals(bn.item("copper"))
                            && !en.wallName("Thorium").equals(bn.wallName("钍")),
                    "★元断言：语言后缀真的改变了读哪一层（否则上面那条会在两种语言下同时成立 = 没有分辨力）");

            // ★★ 回归（用户 2026-10-04 报的第二条："换中文不管用"）：
            //    应用内语言是 **`zh`（没有地区）**，而包里的文件叫 `bundle_zh_CN.properties`
            //    ⇒ 精确匹配会全落空、整层掉回英文（矿名变 Thorium）。必须**按语言前缀兜底**。
            BundleNames bare = new BundleNames(fakeApk,
                    MapStats.readBundles(java.util.Collections.singletonList(mp),
                            MapStats.bundleLang("zh")),
                    null, MapStats.bundleLang("zh"), "%1$s（墙）");
            ok(stat, L, "模组铜".equals(bare.item("copper")) && "铝土".equals(bare.item("ve-aluminium"))
                            && "模组钛".equals(bare.item("titanium")),
                    "★★回归：want=`zh`（**没有地区**）也要认 `bundle_zh_CN.properties` —— 否则中文界面的"
                            + "矿名会掉回英文（铜 =「" + bare.item("copper") + "」）");
            ok(stat, L, bare.item("copper").equals(bn.item("copper"))
                            && !bare.item("copper").equals(en.item("copper")),
                    "★元断言：`zh` 与 `zh_CN` 结果**相同**（前缀兜底生效），且**都不是** en 的结果");
            // 反过来：want 带了地区时，**别的地区**那份要排在后面（同语言多份不能都读、互相覆盖）
            ok(stat, L, MapStats.bundleRank("a/bundles/bundle_zh_CN.properties",
                            MapStats.bundleLang("zh_CN"))
                            > MapStats.bundleRank("a/bundles/bundle_zh_TW.properties",
                            MapStats.bundleLang("zh_CN")),
                    "★元断言：want=`zh_CN` 时 CN 那份的分数**高于** TW 那份（同语言多地区只取最匹配的一份）");
            ok(stat, L, MapStats.bundleRank("a/bundles/bundle.properties",
                            MapStats.bundleLang("zh")) == 2
                            && MapStats.bundleRank("a/bundles/bundle_zh_CN.properties",
                            MapStats.bundleLang("zh")) >= 10
                            && MapStats.bundleRank("a/other/bundle_zh_CN.properties",
                            MapStats.bundleLang("zh")) >= 10
                            && MapStats.bundleRank("a/bundles/bundle_en.properties",
                            MapStats.bundleLang("zh")) == 0,
                    "★判层：基础包=2、同语言语言包≥10、**别的语言=0**（认错语言比认不出更糟）");

            // ★★ 内容引用按**游戏那条顺序**解析：`<模组前缀>-短名` → 短名
            //    （ContentParser$2 字节码：先 getByName(type, mod.name + "-" + name)，再 getByName(type, name)）
            MapStats.Def d1 = new MapStats.Def();
            d1.itemDrop = "aluminium";
            d1.prefix = "ve";
            MapStats.Def d2 = new MapStats.Def();
            d2.itemDrop = "copper";
            d2.prefix = "ve";
            MapStats.Def d3 = new MapStats.Def();
            d3.itemDrop = "silicon";                   // 原版行（无前缀）：语言层有 ⇒ 用语言层的
            MapStats.Def d4 = new MapStats.Def();
            d4.itemDrop = "no-such-item";
            d4.prefix = "ve";
            ok(stat, L, "铝土".equals(MapStats.itemLabel(bn, d1, null)),
                    "★模组矿写 itemDrop: aluminium ⇒ 按 ve-aluminium 查到「铝土」（VE 真实情形）");
            ok(stat, L, "模组铜2".equals(MapStats.itemLabel(bn, d2, null)),
                    "★顺序照游戏：**先**试 <模组前缀>-短名（哪怕原版也有同名物品）");
            ok(stat, L, "硅".equals(MapStats.itemLabel(bn, d3, null)),
                    "原版行（无前缀）走语言层 ⇒ 硅（模组基础层的「模组硅」盖不动它）");
            ok(stat, L, "".equals(MapStats.itemLabel(bn, d4, null)),
                    "★反向：两边都查不到的掉落物 ⇒ 返回空（调用方退回可读化形式）");

            // ★★ 名字**写在内容文件里**的模组（frost-industry 就是：整包没有 bundles/）
            //    游戏侧 `ContentParser` 会把 JSON 的 localizedName 赋给内容 ⇒ 优先级高于 bundle
            File jdir = new File(tmp, "jsonpkg");
            File jb = new File(jdir, "content/blocks");
            File ji = new File(jdir, "content/items");
            if (!jb.mkdirs() || !ji.mkdirs()) throw new IOException("建不了测试目录");
            write(new File(ji, "quartz.hjson"),
                    "{\n  \"name\": \"quartz\"\n  \"localizedName\": \"石英\"\n}\n".getBytes("UTF-8"));
            write(new File(jb, "quartz-deposit.hjson"),
                    ("{\n  \"type\": \"OreBlock\"\n  \"name\": \"quartz-deposit\"\n"
                            + "  \"localizedName\": \"石英矿脉\"\n  \"itemDrop\": \"quartz\"\n}\n")
                            .getBytes("UTF-8"));
            MapStats.Pack jp = new MapStats.Pack();
            jp.dir = jdir;
            jp.prefix = "frost-industry";
            jp.label = "寒霜工业";
            MapStats.Table jt = MapStats.copyOf(MapStats.vanilla());
            MapStats.ModResult jr = new MapStats.ModResult();
            MapStats.applyModContent(jt, jp, "content/items/quartz.hjson",
                    "{\n  \"name\": \"quartz\"\n  \"localizedName\": \"石英\"\n}", jr);
            MapStats.applyModContent(jt, jp, "content/blocks/quartz-deposit.hjson",
                    ("{\n  \"type\": \"OreBlock\"\n  \"name\": \"quartz-deposit\"\n"
                            + "  \"localizedName\": \"石英矿脉\"\n  \"itemDrop\": \"quartz\"\n}"), jr);
            MapStats.Def qd = jt.row("quartz-deposit");
            MapStats.Def qdAlias = jt.row("frost-industry-quartz-deposit");
            ok(stat, L, qd != null && qd == qdAlias,
                    "★JSON 里写了 name 的模组：注册名与 <前缀>-文件名 **两个名字都登记**（地图里哪种都可能出现）");
            ok(stat, L, qd != null && "石英矿脉".equals(qd.localizedName),
                    "★方块名取自内容 JSON 的 localizedName");
            MapStats.Def qore = new MapStats.Def();
            qore.itemDrop = "quartz";
            qore.prefix = "frost-industry";
            ok(stat, L, "石英".equals(MapStats.itemLabel(bn, qore, jt.itemNames)),
                    "★物品名取自物品 JSON 的 localizedName（模组不带 bundles/ 时唯一的译名来源）");
            // 元断言：把物品那条名字抹掉 ⇒ 必须退回"查不到"（证明上面那条不是碰巧过的）
            MapStats.Table jt2 = MapStats.copyOf(MapStats.vanilla());
            MapStats.applyModContent(jt2, jp, "content/blocks/quartz-deposit.hjson",
                    ("{\n  \"type\": \"OreBlock\"\n  \"name\": \"quartz-deposit\"\n"
                            + "  \"itemDrop\": \"quartz\"\n}"), new MapStats.ModResult());
            ok(stat, L, "".equals(MapStats.itemLabel(bn, qore, jt2.itemNames)),
                    "★元断言：物品 JSON 不写 localizedName ⇒ 必须查不到（这条判据有分辨力）");
        } catch (Throwable ex) {
            ok(stat, L, false, "译名链用例自身异常：" + ex);
        } finally {
            deleteTree(tmp);
        }
        L.add("");
    }

    /** 造一份真的补丁区载荷（格式见 {@link MsavPatches}） */
    private static byte[] patchBytes(String path, String text) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bo);
            byte[] body = text.getBytes("UTF-8");
            out.writeInt(2);              // 格式版本
            out.writeInt(1);              // 条数
            out.writeByte(MsavPatches.PATCH);
            out.writeUTF(path);
            out.writeBoolean(true);
            out.writeInt(body.length);
            out.write(body);
            out.flush();
            return bo.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** 3×3 的手工地图：铜矿（露天 / 石头下 / 墙下各一格）+ 两种钍矿 + 沙地 + 盐地 */
    private static MsavTiles.Tiles grid() {
        MsavTiles.Tiles t = new MsavTiles.Tiles();
        t.width = 3;
        t.height = 3;
        String[] names = {"air", "stone", "sand-floor", "salt", "stone-wall", "boulder",
                "ore-copper", "ore-thorium", "ore-crystal-thorium"};
        for (String n : names) t.blockNames.add(n);
        t.floors = new short[9];
        t.ores = new short[9];
        t.blocks = new short[9];
        for (int i = 0; i < 9; i++) t.floors[i] = 1;          // 默认石头地板
        t.floors[0] = 2;                                      // 沙子（可采）
        t.floors[4] = 3;                                      // 盐（加成）
        t.ores[1] = 6;                                        // 铜：墙下
        t.blocks[1] = 4;
        t.ores[2] = 6;                                        // 铜：石头下（可拆）
        t.blocks[2] = 5;
        t.ores[3] = 6;                                        // 铜：露天
        t.ores[6] = 7;                                        // 钍
        t.ores[7] = 8;                                        // 晶钍（同名）
        return t;
    }

    /** 假译名：铜/钍/沙 + 属性词（真译名要从版本 APK 读，这里只验"组装"这一段） */
    private static MapStats.Names fakeNames() {
        return new MapStats.Names() {
            @Override public String block(String i) {
                return i;
            }

            @Override public String item(String i) {
                if ("copper".equals(i)) return "铜";
                if ("thorium".equals(i)) return "钍";
                if ("sand".equals(i)) return "沙";
                return i;
            }

            @Override public String wallName(String base) {
                return base + "（墙）";
            }

            @Override public String attr(String key) {
                if ("water".equals(key)) return "水";
                if ("oil".equals(key)) return "油";
                return key;
            }
        };
    }

    private static MapStats.Row rowOf(List<MapStats.Row> rows, String label) {
        for (MapStats.Row r : rows) {
            if (r.label != null && r.label.startsWith(label)) return r;
        }
        return null;
    }

    private static boolean canResolve(Context ctx, String name) {
        try {
            ctx.getPackageManager().getActivityInfo(
                    new android.content.ComponentName(ctx, name), 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasPair(List<String> xs, String a, String b) {
        for (String s : xs) {
            if (s.contains(a) && s.contains(b)) return true;
        }
        return false;
    }

    /**
     * ⑮ 用：门禁是否**全过**（判据就是 {@link Mods#gates} 的 `pass`）。
     * ★ 存在的意义：`stateOf` 与 `gates` 是**两处入口**，徽标写"不支持"而门禁里没有一关判死，
     *   用户就看不到依据（F4①d 那条纪律：判据要能看见依据）。
     */
    private static boolean allGatesPass(Context ctx, Mods.Info m, int build, int rev) {
        for (Mods.Gate g : Mods.gates(ctx, m, build, rev)) {
            if (!g.pass) return false;
        }
        return true;
    }

    /** ⑮ 用：造一个只填内部名/依赖/开关的模组（`resolveDependencies` 只看这几项） */
    private static Mods.Info mkMod(String internal, String[] req, String[] soft, boolean enabled) {
        Mods.Info m = new Mods.Info();
        m.internalName = internal;
        m.name = internal;
        m.enabled = enabled;
        m.settingsKnown = true;
        if (req != null) for (String d : req) m.dependencies.add(d);
        if (soft != null) for (String d : soft) m.softDependencies.add(d);
        return m;
    }

    private static Mods.Info findInfo(List<Mods.Info> list, String internal) {
        for (Mods.Info m : list) {
            if (internal.equals(m.internalName)) return m;
        }
        return null;
    }

    /** 报告里用：把扫到的模组内部名列出来（失败时能一眼看出"少的是哪一个"） */
    private static String internalNamesOf(List<Mods.Info> list) {
        StringBuilder sb = new StringBuilder("[");
        for (Mods.Info m : list) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(m.internalName);
        }
        return sb.append(']').toString();
    }

    private static String fileNamesOf(List<Mods.Info> list) {
        StringBuilder sb = new StringBuilder("[");
        for (Mods.Info m : list) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(m.fileName);
        }
        return sb.append(']').toString();
    }

    /** 解析成 JSON 文本；失败一律 null（用于"解析成功但取不到字段"这类断言） */
    private static String hjsonText(String src, int profile) {
        try {
            return Hjson.toJsonText(src, profile);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 JSON 文本里取 `name`（取不到 / 不是对象 ⇒ null） */
    private static String nameOfJson(String json) {
        if (json == null) return null;
        try {
            Object v = new org.json.JSONObject(json).opt("name");
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只用名字取 HJSON 里的 `name`；解析失败一律 null（并区分「抛的是 ParseException」见下） */
    private static String hjsonName(String src, int profile) {
        return nameOfJson(hjsonText(src, profile));
    }

    /** 是否**以可判的方式**失败（抛的必须是 {@link Hjson.ParseException}，别的异常类型算不合格） */
    private static boolean failsAsParseError(String src, int profile) {
        try {
            Hjson.toJsonText(src, profile);
            return false;
        } catch (Hjson.ParseException pe) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只要不把「非 ParseException 的 Throwable」放出来就算合格（深嵌套/恶意输入） */
    private static boolean noEscape(String src, int profile) {
        try {
            Hjson.toJsonText(src, profile);
            return true;
        } catch (Hjson.ParseException pe) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readFails(File f) {
        return SettingsBin.readSafe(f, null) == null;
    }

    /** 按 arc 的写侧格式手拼一个 settings.bin（**只给自检用**；产品侧这一阶段只读） */
    private static void writeSettings(File f, Object[][] entries, boolean compressed)
            throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) throw new IOException("mkdir 失败：" + p);
        java.io.OutputStream raw = new FileOutputStream(f);
        java.io.OutputStream os = compressed ? new java.util.zip.DeflaterOutputStream(raw) : raw;
        java.io.DataOutputStream out = new java.io.DataOutputStream(os);
        try {
            out.writeInt(entries.length);
            for (Object[] e : entries) {
                String key = (String) e[0];
                int type = ((Integer) e[1]).intValue();
                out.writeUTF(key);
                out.writeByte(type);
                switch (type) {
                    case SettingsBin.TYPE_BOOL:
                        out.writeBoolean(((Boolean) e[2]).booleanValue());
                        break;
                    case SettingsBin.TYPE_INT:
                        out.writeInt(((Integer) e[2]).intValue());
                        break;
                    case SettingsBin.TYPE_LONG:
                        out.writeLong(((Long) e[2]).longValue());
                        break;
                    case SettingsBin.TYPE_FLOAT:
                        out.writeFloat(((Float) e[2]).floatValue());
                        break;
                    case SettingsBin.TYPE_STRING:
                        out.writeUTF((String) e[2]);
                        break;
                    case SettingsBin.TYPE_BINARY: {
                        byte[] b = (byte[]) e[2];
                        out.writeInt(b.length);
                        out.write(b);
                        break;
                    }
                    default:
                        break;      // 未知类型：故意只写类型字节（负例用）
                }
            }
        } finally {
            out.close();
        }
    }

    /** 写一个多条目 zip（`names[i]` 对应 `texts[i]`；条目名用 `/` 分层） */
    private static void zipMany(File f, String[] names, String[] texts) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) throw new IOException("mkdir 失败：" + p);
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(f));
        try {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new java.util.zip.ZipEntry(names[i]));
                z.write(texts[i].getBytes("UTF-8"));
                z.closeEntry();
            }
        } finally {
            z.close();
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static void ok(int[] stat, List<String> L, boolean cond, String msg) {
        if (cond) stat[0]++;
        else stat[1]++;
        L.add((cond ? "  ✅ " : "  ❌ ") + msg);
    }

    private static String path(File f) {
        return f == null ? "?" : f.getAbsolutePath();
    }

    private static void write(File f, byte[] data) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) throw new IOException("mkdir 失败：" + p);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static String read(File f) {
        try {
            return Util.readText(f);
        } catch (Exception e) {
            return "";
        }
    }

    /** zlib 压一份字节（真 .msav 用的就是这个；判据见 Util.isZlib） */
    private static byte[] zlib(byte[] raw) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(bo);
        try {
            z.write(raw);
        } finally {
            z.close();
        }
        return bo.toByteArray();
    }

    /** 造 gzip 字节（**故意**用错的压缩格式当负例；真 .msav 不是 gzip） */
    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(bo);
        try {
            g.write(raw);
        } finally {
            g.close();
        }
        return bo.toByteArray();
    }

    private static String join(List<String> L) {
        StringBuilder sb = new StringBuilder();
        for (String s : L) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 把多行报告压成一行（塞进断言文案里用） */
    /**
     * 从自检报告里取出**汇总行**，供调用方放进弹窗**标题**。
     *
     * ★ 为什么值得单独做一个（F17c）：AlertDialog 的**标题不随正文滚动** ——
     *   把结论放进标题，用户（和截图判读的人）**滚到报告任何位置都能看到"过没过"**。
     *   正文里那份汇总负责"打开就是第一屏"，标题这份负责"永远可见"，两者互补。
     *
     * 取的是第一行以 `=====` 开头的行（`runM3` 保证它在第 3 行附近），并把 `=` 剥掉。
     * ⚠️ 报告格式一变这里会返回兜底串而**不报错** —— 所以调用方拿它只为显示，
     *   永不据此判断通过与否（真判据只有 `stat`）。
     */
    public static String summaryOf(String report) {
        if (report == null) return "无报告";
        for (String s : report.split("\n")) {
            String t = s.trim();
            if (t.startsWith("=====")) {
                String body = t.replace("=", "").trim();
                if (!body.isEmpty()) return body;
            }
        }
        return "报告格式异常（未找到汇总行）";
    }

    private static String oneLine(String s) {
        if (s == null) return "null";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 140 ? t.substring(0, 140) + "…" : t;
    }

    /**
     * 收集"此刻**明确**还在宽限期内"的对象（用于事后断言它们一个都没被 GC 删）。
     *
     * ★ 刻意留 60 s 余量：判据是 `mtime > now - GRACE + 60s` 而不是 `mtime > now - GRACE`。
     *   因为 GC 自己会在此后几毫秒重算一次 cutoff（比我这里晚），贴着边界的对象
     *   会被它判成"已过期"从而合法删除，导致断言**偶发**假失败。
     *   留一分钟余量后，"我记下来的对象"必然在未来一分钟内都不会过期。
     */
    private static List<File> youngObjects(Cas pool) {
        long cutoff = System.currentTimeMillis() - Cas.GC_GRACE_MS + 60_000L;
        List<File> out = new ArrayList<>();
        File[] ps = pool.objectsDir().listFiles();
        if (ps == null) return out;
        for (File p : ps) {
            if (!p.isDirectory() || p.getName().length() != 2) continue;
            File[] os = p.listFiles();
            if (os == null) continue;
            for (File o : os) {
                if (o.isFile() && o.lastModified() > cutoff) out.add(o);
            }
        }
        return out;
    }
}
