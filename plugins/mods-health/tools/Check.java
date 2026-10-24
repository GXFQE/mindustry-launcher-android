package io.mdt.plugins.mods.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件侧自检（PC 上跑，`bash plugins/mods-health/build.sh check`）。
 *
 * <p>★ 为什么必须有它：判据第 229 轮从宿主搬到了这里，宿主 SelfTest 从此**看不到**它们；
 * 而真机上只有一份现成的模组组合 —— 判不出"反方向"（不该报的有没有误报）。
 * 这个类在夹具上把**两个方向**都钉住。
 *
 * <p>★ 第 229 轮删掉「共同全局钩子」那层后，这里也删掉了它的三条断言（写死的类名表不再存在）。
 */
public final class Check {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        File work = new File(System.getProperty("java.io.tmpdir", "."),
                "mdt-conflict-check-" + System.nanoTime());
        if (!work.mkdirs()) throw new IllegalStateException("cannot mkdir " + work);
        try {
            run(work);
        } finally {
            deleteTree(work);
        }
        System.out.println("---- " + passed + " passed / " + failed + " failed ----");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(File work) throws Exception {
        // ── 夹具：三个带代码的模组 + 一个纯数据模组 ──────────────────────────────
        File aDir = new File(work, "a-neon-like");
        File bDir = new File(work, "b-logicsugar-like");
        File cDir = new File(work, "c-unrelated");
        dex(aDir, "FAKE-DEX-HEADER big/BigMod ... also contains small/SmallMod");
        dex(bDir, "FAKE-DEX small/SmallMod");
        dex(cDir, "FAKE-DEX unrelated only");

        List<Conflicts.Mod> mods = new ArrayList<Conflicts.Mod>();
        Conflicts.Mod a = mod(aDir, "Big Mod", "big.BigMod", "内置了 Small Mod 的功能");
        Conflicts.Mod b = mod(bDir, "Small Mod", "small.SmallMod", "");
        Conflicts.Mod c = mod(cDir, "Unrelated", "un.unRelated", "");
        Conflicts.Mod d = new Conflicts.Mod();
        d.path = new File(work, "d-nomain.zip").getAbsolutePath();
        d.title = "Data Only";
        mods.add(a);
        mods.add(b);
        mods.add(c);
        mods.add(d);

        Conflicts.Result r = Conflicts.scan(mods);

        check(r.javaMods == 3, "javaMods = 带 main 的模组数（3，纯数据模组不算）");
        check(r.scannedDex == 3, "scannedDex = 真的扫了 3 个 dex");
        check(r.scannedBytes == dexLen(aDir) + dexLen(bDir) + dexLen(cDir),
                "scannedBytes = 三个 dex 的字节和（" + r.scannedBytes + "）");

        check(hasPair(r.contained, 0, 1), "★内置检测：A 的 classes.dex 里含 B 的主类 ⇒ A 内置了 B");
        check(!hasPair(r.contained, 1, 0),
                "★反向：B 的 dex 里没有 A 的主类 ⇒ **不许**说「B 内置了 A」（方向别写错）");
        check(!hasPair(r.contained, 0, 2) && !hasPair(r.contained, 2, 0),                "★反向：无关模组 C 与 A 之间不报「内置」");

        // ★ 元断言：把 B 的主类从 A 的 dex 里删掉 ⇒ 必须不再报「内置」（证明上一条不是恒真）
        File a2Dir = new File(work, "a2-big-only");
        dex(a2Dir, "FAKE-DEX big/BigMod only");
        List<Conflicts.Mod> mods2 = new ArrayList<Conflicts.Mod>();
        mods2.add(mod(a2Dir, "Big Mod", "big.BigMod", ""));
        mods2.add(b);
        Conflicts.Result r2 = Conflicts.scan(mods2);
        check(r2.contained.isEmpty(), "★元断言：拿掉 B 的主类 ⇒ 不再报「内置」");

        // ── v25（P2）：结构判据（会不会加载 / 脚本跑不跑）—— 从宿主搬来的两条 ──────────
        List<Conflicts.Mod> modsS = new ArrayList<Conflicts.Mod>();
        Conflicts.Mod noDex = new Conflicts.Mod();
        noDex.path = "/x/nodex.jar"; noDex.title = "No Dex"; noDex.internalName = "nodex";
        noDex.java = true; noDex.hasClassesDex = false;
        Conflicts.Mod okDex = new Conflicts.Mod();
        okDex.path = "/x/ok.jar"; okDex.title = "Ok Dex"; okDex.internalName = "okdex";
        okDex.java = true; okDex.hasClassesDex = true;
        Conflicts.Mod oneJs = new Conflicts.Mod();
        oneJs.path = "/x/one.zip"; oneJs.title = "One Js"; oneJs.internalName = "onejs";
        oneJs.hasScripts = true; oneJs.jsCount = 1; oneJs.hasMainJs = false;
        Conflicts.Mod twoJs = new Conflicts.Mod();
        twoJs.path = "/x/two.zip"; twoJs.title = "Two Js"; twoJs.internalName = "twojs";
        twoJs.hasScripts = true; twoJs.jsCount = 2; twoJs.hasMainJs = false;
        Conflicts.Mod twoJsOk = new Conflicts.Mod();
        twoJsOk.path = "/x/twook.zip"; twoJsOk.title = "Two Js Ok"; twoJsOk.internalName = "twojsok";
        twoJsOk.hasScripts = true; twoJsOk.jsCount = 2; twoJsOk.hasMainJs = true;
        modsS.add(noDex);
        modsS.add(okDex);
        modsS.add(oneJs);
        modsS.add(twoJs);
        modsS.add(twoJsOk);
        Conflicts.Result rS = Conflicts.scan(modsS);
        check(hasIssue(rS.issues, 0, Conflicts.Issue.WILL_FAIL_JAVA_LOAD),
                "结构：声明了 Java 却没有 classes.dex ⇒ 报「会加载失败」");
        check(!hasIssue(rS.issues, 1, Conflicts.Issue.WILL_FAIL_JAVA_LOAD),
                "★反向：有 classes.dex 的不许报");
        check(!hasIssue(rS.issues, 2, Conflicts.Issue.NO_MAIN_SCRIPT),
                "★反向：只有 1 个脚本（没有 main.js）不算问题 —— 单脚本叫什么名字都行");
        check(hasIssue(rS.issues, 3, Conflicts.Issue.NO_MAIN_SCRIPT),
                "结构：2 个脚本却没有 main.js ⇒ 报「脚本不会跑」");
        check(!hasIssue(rS.issues, 4, Conflicts.Issue.NO_MAIN_SCRIPT),
                "★反向：2 个脚本且有 main.js ⇒ 不报");

        // ── v25（P3）：依赖 / 重名 / 黑名单（**状态是宿主的事实**，这里只验"哪一条 + 报不报"）──
        List<Conflicts.Mod> modsD = new ArrayList<Conflicts.Mod>();
        Conflicts.Mod hostMod = new Conflicts.Mod();
        hostMod.path = "/d/host.zip"; hostMod.title = "Host";
        hostMod.internalName = "host"; hostMod.depState = "MISSING_DEPENDENCIES";
        hostMod.dependencies.add("libgone");
        Conflicts.Mod dupA = new Conflicts.Mod();
        dupA.path = "/d/dupa.zip"; dupA.title = "Dup A"; dupA.internalName = "dup"; dupA.duplicated = true;
        Conflicts.Mod dupB = new Conflicts.Mod();
        dupB.path = "/d/dupb.zip"; dupB.title = "Dup B"; dupB.internalName = "dup"; dupB.duplicated = true;
        Conflicts.Mod blMod = new Conflicts.Mod();
        blMod.path = "/d/bl.zip"; blMod.title = "Bad"; blMod.internalName = "bad"; blMod.blacklisted = true;
        Conflicts.Mod okDep = new Conflicts.Mod();
        okDep.path = "/d/ok.zip"; okDep.title = "Ok"; okDep.internalName = "ok"; okDep.depState = "ENABLED";
        modsD.add(hostMod); modsD.add(dupA); modsD.add(dupB); modsD.add(blMod); modsD.add(okDep);
        Conflicts.Result rD = Conflicts.scan(modsD);
        check(hasIssue(rD.issues, 0, Conflicts.Issue.DEP_MISSING), "依赖：宿主说缺依赖 ⇒ 报 DEP_MISSING");
        check("libgone".equals(issueOther(rD.issues, 0, Conflicts.Issue.DEP_MISSING)),
                "依赖：点得出缺的是哪一条（" + issueOther(rD.issues, 0, Conflicts.Issue.DEP_MISSING) + "）");
        check(hasIssue(rD.issues, 1, Conflicts.Issue.DUPLICATED) && hasIssue(rD.issues, 2, Conflicts.Issue.DUPLICATED),
                "重名：两份都要标（只标一份就变成「另一份没问题」了）");
        check("Dup B".equals(issueOther(rD.issues, 1, Conflicts.Issue.DUPLICATED)),
                "重名：说得出另一份是谁（" + issueOther(rD.issues, 1, Conflicts.Issue.DUPLICATED) + "）");
        check(hasIssue(rD.issues, 3, Conflicts.Issue.BLACKLISTED), "黑名单：在名单里就标出来");
        Conflicts.Mod brokenMeta = new Conflicts.Mod();
        brokenMeta.path = "/d/broken.zip"; brokenMeta.title = "Broken";
        brokenMeta.minGameVersion = ""; brokenMeta.targetBuild = 160;
        List<Conflicts.Mod> modsB = new ArrayList<Conflicts.Mod>();
        modsB.add(brokenMeta);
        Conflicts.Result rB = Conflicts.scan(modsB);
        // ── v27：孤儿开关（槽级判据，纯函数）────────────────────
        java.util.List<String> allKeys = java.util.Arrays.asList(
                "mod-alpha-enabled", "mod-root-enabled", "mod-gone-enabled",
                "mod-gone-failed", "mod-gone-repo", "modcrashdisable", "other-key");
        java.util.List<String> liveNames = java.util.Arrays.asList("alpha", "root");
        java.util.List<String> orphans = Conflicts.orphanKeys(allKeys, liveNames);
        check(orphans.size() == 3, "孤儿开关：只报三个残留键（实际 " + orphans.size() + "）");
        check(orphans.contains("mod-gone-enabled") && orphans.contains("mod-gone-failed")
                && orphans.contains("mod-gone-repo"), "孤儿开关：-enabled/-failed/-repo 三种后缀都要认");
        check(!orphans.contains("mod-alpha-enabled") && !orphans.contains("mod-root-enabled"),
                "★反向：还在槽里的模组不能被报成孤儿");
        check(!orphans.contains("modcrashdisable") && !orphans.contains("other-key"),
                "★反向：modcrashdisable 这种全局键不是孤儿");
        check(Conflicts.orphanKeys(null, liveNames).isEmpty()
                && Conflicts.orphanKeys(allKeys, null).size() == 5,
                "孤儿开关：空输入不许抛；槽里一个模组都没有时全都是孤儿");

        check(rB.issues.isEmpty(),
                "★反向：读不出说明文件的包不许报版本门槛（它连 internalName 都没有）");
        // ── v25（P3b）：版本判断（旧的包 / 门槛）—— 照拄游戏自己的判法 ────────────
        check(Conflicts.isAtLeast(160, 1, "159.7"), "isAtLeast：160.1 >= 159.7");
        check(!Conflicts.isAtLeast(159, 0, "159.7"), "isAtLeast 反向：159.0 < 159.7（小版本也要比）");
        check(Conflicts.isAtLeast(160, 0, "159"), "isAtLeast：纯整数形态比 build");
        check(Conflicts.isAtLeast(0, 0, "999"), "isAtLeast：目标版本未知 ⇒ 算通过（不许把不知道当不兼容）");
        check(Conflicts.minMajorOf("159.7") == 159 && Conflicts.minMajorOf("") == 0
                && Conflicts.minMajorOf("abc") == 0, "minMajorOf：取点前一段，取不出 ⇒ 0");

        List<Conflicts.Mod> modsV = new ArrayList<Conflicts.Mod>();
        Conflicts.Mod tooNew = new Conflicts.Mod();
        tooNew.path = "/v/too-new.zip"; tooNew.title = "Too New"; tooNew.internalName = "toonew";
        tooNew.targetBuild = 160; tooNew.targetRevision = 1; tooNew.minGameVersion = "170";
        Conflicts.Mod lowMajor = new Conflicts.Mod();
        lowMajor.path = "/v/low.zip"; lowMajor.title = "Low Major"; lowMajor.internalName = "lowmajor";
        lowMajor.targetBuild = 160; lowMajor.targetRevision = 1; lowMajor.minGameVersion = "146";
        lowMajor.minModMajor = 154; lowMajor.java = false;
        Conflicts.Mod legacy = new Conflicts.Mod();
        legacy.path = "/v/legacy.zip"; legacy.title = "Legacy"; legacy.internalName = "legacy";
        legacy.targetBuild = 160; legacy.targetRevision = 1; legacy.minGameVersion = "";
        legacy.legacyCompatible = true;
        Conflicts.Mod plain = new Conflicts.Mod();
        plain.path = "/v/plain.zip"; plain.title = "Plain"; plain.internalName = "plain";
        plain.targetBuild = 160; plain.targetRevision = 1; plain.minGameVersion = "159.7";
        modsV.add(tooNew); modsV.add(lowMajor); modsV.add(legacy); modsV.add(plain);
        Conflicts.Result rV = Conflicts.scan(modsV);
        check(hasIssue(rV.issues, 0, Conflicts.Issue.NEEDS_NEWER_GAME),
                "版本：包要求 170 而游戏是 160 ⇒ 报 NEEDS_NEWER_GAME");
        check(hasIssue(rV.issues, 1, Conflicts.Issue.MIN_MAJOR_LOW),
                "门槛：minGameVersion 146 < 脚本模组的 154 ⇒ 报 MIN_MAJOR_LOW");
        check(!hasIssue(rV.issues, 2, Conflicts.Issue.MIN_MAJOR_LOW),
                "★反向：legacyCompatible 的包不报门槛（游戏自己给的豁免）");
        // ★ v7 回归：目标是 v146（v7）时，154/136 这个门槛**压根不存在**
        //   （v146 的 Vars.java 里没有那两个常量）—— 写 146 的 Java 模组在 v146 上必须算合格。
        Conflicts.Mod v7Mod = new Conflicts.Mod();
        v7Mod.path = "/v7/legacy.zip"; v7Mod.title = "V7 Java"; v7Mod.internalName = "v7java";
        v7Mod.java = true; v7Mod.targetBuild = 146; v7Mod.targetRevision = 0;
        v7Mod.minGameVersion = "146";
        List<Conflicts.Mod> mods7 = new ArrayList<Conflicts.Mod>();
        mods7.add(v7Mod);
        Conflicts.Result r7 = Conflicts.scan(mods7);
        check(!hasIssue(r7.issues, 0, Conflicts.Issue.MIN_MAJOR_LOW),
                "★v7 回归：v146 上写 146 的 Java 模组不能报门槛太低");

        check(!hasIssue(rV.issues, 3, Conflicts.Issue.NEEDS_NEWER_GAME)
                && !hasIssue(rV.issues, 3, Conflicts.Issue.MIN_MAJOR_LOW),
                "★反向：版本合适的包一条版本结论都不许有");

        check(!hasIssue(rD.issues, 4, Conflicts.Issue.DEP_MISSING)
                && !hasIssue(rD.issues, 4, Conflicts.Issue.DUPLICATED)
                && !hasIssue(rD.issues, 4, Conflicts.Issue.BLACKLISTED),
                "★反向：一切正常的模组一条结构结论都不许有");



        // ── zip 形态：根前缀（下潜一层）里的 classes.dex 也要找得到 ────────────────
        File z = new File(work, "z-nested.zip");
        zip(z, "top/", "FAKE-DEX big/BigMod");
        List<Conflicts.Mod> mods3 = new ArrayList<Conflicts.Mod>();
        Conflicts.Mod zm = new Conflicts.Mod();
        zm.path = z.getAbsolutePath();
        zm.title = "Nested Pack";
        zm.main = "nested.Main";
        zm.rootPrefix = "top/";
        mods3.add(zm);
        mods3.add(a);
        Conflicts.Result r3 = Conflicts.scan(mods3);
        check(hasPair(r3.contained, 0, 1),
                "zip 形态：按 rootPrefix 找到 top/classes.dex，判出「Nested Pack 内置了 Big Mod」");
        check(r3.scannedBytes > 0, "zip 形态：dex 字节数真的读到了（" + r3.scannedBytes + "）");

        // ── 读不了的 dex：要单独说，不许静默当成"没有冲突" ────────────────────────
        File junk = new File(work, "broken.zip");
        writeText(junk, "this is not a zip");
        List<Conflicts.Mod> mods4 = new ArrayList<Conflicts.Mod>();
        Conflicts.Mod jm = new Conflicts.Mod();
        jm.path = junk.getAbsolutePath();
        jm.title = "Broken";
        jm.main = "broken.Main";
        mods4.add(jm);
        mods4.add(a);
        Conflicts.Result r4 = Conflicts.scan(mods4);
        check(r4.dexErrors.contains(Integer.valueOf(0)), "读不了的包 ⇒ 进 dexErrors（界面会点名它）");
        check(!hasPair(r4.contained, 0, 1), "读不了的包 ⇒ 不瞎报内置");

        // ── 游戏日志：只摘提到这些模组的行，上限 40 ────────────────────────────────
        File log = new File(work, "last_log.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Loading mod: Small Mod\n");
        sb.append("nothing here\n");
        sb.append("[I] Small Mod loaded fine\n");
        writeText(log, sb.toString());
        List<Conflicts.Mod> onlyB = new ArrayList<Conflicts.Mod>();
        onlyB.add(b);
        Conflicts.Log l1 = Conflicts.readLog(log, onlyB);
        check(l1.lines.size() == 2 && !l1.unreadable,
                "日志摘录：两行提到 Small Mod（无关那行不进来）");

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append("Small Mod line ").append(i).append('\n');
        writeText(log, many.toString());
        Conflicts.Log l2 = Conflicts.readLog(log, onlyB);
        check(l2.lines.size() == 40, "日志摘录：上限 40 行（" + l2.lines.size() + "）");

        Conflicts.Log l3 = Conflicts.readLog(new File(work, "no-such-log.txt"), onlyB);
        check(l3.lines.isEmpty() && !l3.unreadable, "日志不存在 ⇒ 空表、不算读不了");

        // ── v27 P4（第 230 轮）：六道加载门 —— 判据搬进 core/Gates 后，这里逐道钉住 ──
        //    ★ 为什么补这一批：宿主那 20 条门禁断言第 229 轮随宿主判据一起删了，
        //      而门原来写在入口类里（要 Context 取文案）⇒ 本 check 编不到 ⇒ 一条自动判据都没有。
        checkGates();
        // ── 第 234 轮：覆盖原版（贴图 / 文案）─────────────────────────────────
        overrides(work);
    }

    // ══ 六道加载门（v27 P4 / 第 230 轮）══════════════════════════════════════════

    /** 干净夹具：六道全过（目标 160.1 / 包要求 159 / Java 门槛 154）。 */
    private static Gates.Facts cleanFacts() {
        Gates.Facts f = new Gates.Facts();
        f.name = "Demo Mod";
        f.version = "1.2";
        f.minGameVersion = "159";
        f.java = true;
        f.targetBuild = 160;
        f.targetRevision = 1;
        return f;
    }

    private static void checkGates() {
        // ① 事实映射：宿主 extra 的 key 名只出现在 Gates.facts 里（宿主 SelfTest 那边钉"这些 key 真的存在"）
        java.util.Map<String, String> extra = new java.util.HashMap<String, String>();
        extra.put("name", "Demo Mod");
        extra.put("version", "1.2");
        extra.put("minGameVersion", "159");
        extra.put("java", "true");
        extra.put("enabled", "false");
        extra.put("settingsKnown", "false");
        extra.put("failed", "true");
        extra.put("launchIdExists", "true");
        extra.put("targetBuild", "160");
        extra.put("targetRevision", "1");
        extra.put("minJavaMajor", "154");
        extra.put("minModMajor", "136");
        Gates.Facts mapped = Gates.facts(extra, true);
        check(mapped.targetBuild == 160 && mapped.targetRevision == 1
                        && !mapped.settingsKnown && !mapped.enabled && mapped.failed
                        && mapped.launchIdExists && mapped.slotSkipping && mapped.java,
                "事实映射：settingsKnown / enabled / failed / launchIdExists / slotSkipping 都读到了");
        Gates.Facts dflt = Gates.facts(new java.util.HashMap<String, String>(), false);
        check(dflt.settingsKnown && dflt.enabled && dflt.minJavaMajor == 154
                        && dflt.minModMajor == 136 && !dflt.launchIdExists,
                "事实映射：缺省值（缺 settingsKnown 的老宿主 ⇒ 不显示「默认值」补丁句；门槛 154/136）");

        Gates.Facts clean = cleanFacts();
        java.util.Map<String, Integer> used = new java.util.LinkedHashMap<String, Integer>();
        java.util.List<java.util.List<Gates.Decision>> all =
                new java.util.ArrayList<java.util.List<Gates.Decision>>();

        java.util.List<Gates.Decision> base = Gates.decide(clean);
        all.add(base);
        check(base.size() == Gates.COUNT, "六道门一次给全（" + base.size() + "）");
        boolean allPass = true;
        for (Gates.Decision d : base) allPass &= d.pass;
        check(allPass, "★干净夹具：六道全过");
        check("cf_gate_last".equals(labelOf(base, Gates.LAST)),
                "干净夹具：最后一道用「正常结束」那条（不是 ❌ 的）");

        // ② 每道门单独不通过一次：其余五道必须仍然通过、标签必须换、原因必须非空
        Gates.Facts fLoad = cleanFacts();
        fLoad.willFailJavaLoad = true;
        all.add(expectOnly(clean, fLoad, Gates.LOAD, "安卓加载不了"));

        Gates.Facts fOff = cleanFacts();
        fOff.enabled = false;
        all.add(expectOnly(clean, fOff, Gates.ON, "游戏里关着"));

        Gates.Facts fVer = cleanFacts();
        fVer.minGameVersion = "170";
        all.add(expectOnly(clean, fVer, Gates.VERSION, "要求 170 而槽是 160.1"));

        Gates.Facts fMajor = cleanFacts();
        fMajor.minGameVersion = "146";                 // 160 >= 146 ⇒ 版本那道过，只有门槛这道该坏
        all.add(expectOnly(clean, fMajor, Gates.MINVER, "门槛 146 < Java 的 154"));

        Gates.Facts fNoMajor = cleanFacts();
        fNoMajor.minGameVersion = "";                  // 没写 = 太老（游戏会静默跳过）
        all.add(expectOnly(clean, fNoMajor, Gates.MINVER, "没写最低版本"));

        Gates.Facts fBl = cleanFacts();
        fBl.blacklisted = true;
        all.add(expectOnly(clean, fBl, Gates.BLACKLIST, "在黑名单里"));

        Gates.Facts fFailed = cleanFacts();
        fFailed.failed = true;
        all.add(expectOnly(clean, fFailed, Gates.LAST, "游戏写下了 -failed"));
        check("cf_gate_last_failed".equals(labelOf(all.get(all.size() - 1), Gates.LAST)),
                "★按项事实：有 -failed 才说「它被标成失败」");

        // ③ 槽级事实用槽级措辞（§85.16 / §86.7）——这是第 229 轮搬丢的那条
        Gates.Facts fSkip = cleanFacts();
        fSkip.slotSkipping = true;
        java.util.List<Gates.Decision> skipDs = Gates.decide(fSkip);
        all.add(skipDs);
        check("cf_gate_last_skip".equals(labelOf(skipDs, Gates.LAST)),
                "★槽级事实：整槽跳过用槽级措辞（不是「它崩了」）");
        Gates.Facts fBoth = cleanFacts();
        fBoth.slotSkipping = true;
        fBoth.failed = true;
        all.add(Gates.decide(fBoth));
        check("cf_gate_last_skip".equals(labelOf(Gates.decide(fBoth), Gates.LAST)),
                "★槽级优先：槽级跳过与 -failed 同时成立时说槽级那句");

        // ④ 通过时允许的两处补充说明
        Gates.Facts fUnknown = cleanFacts();
        fUnknown.settingsKnown = false;
        java.util.List<Gates.Decision> unknownDs = Gates.decide(fUnknown);
        all.add(unknownDs);
        Gates.Decision onD = byId(unknownDs, Gates.ON);
        check(onD.pass && "cf_gate_on_unknown".equals(onD.noteKey),
                "★没读到设置 ⇒ 第②道门通过也要补一句「这是默认值」（默认值不许当事实说）");

        Gates.Facts fPending = cleanFacts();
        fPending.launchIdExists = true;
        java.util.List<Gates.Decision> pendingDs = Gates.decide(fPending);
        all.add(pendingDs);
        Gates.Decision lastD = byId(pendingDs, Gates.LAST);
        check(lastD.pass && "cf_gate_last_pending".equals(lastD.noteKey),
                "★launchid.dat 还在但不会跳过 ⇒ 通过 + 一句机制说明");

        // ⑤ 反向：老包豁免不要再报门槛
        Gates.Facts fLegacy = cleanFacts();
        fLegacy.minGameVersion = "146";
        fLegacy.legacyCompatible = true;
        java.util.List<Gates.Decision> legacyDs = Gates.decide(fLegacy);
        all.add(legacyDs);
        check(allPass(legacyDs), "★反向：legacyCompatible 的包不报门槛");

        // ⑥ 元断言：pass/fail 的说明规则 + 文案 key 真的存在（两种语言、占位符个数对）
        for (java.util.List<Gates.Decision> ds : all) record(used, ds);
        // 第 234 轮：覆盖检测的文案（不经过六道门，单独登记；两种语言、占位符个数照验）
        used.put("cf_ovr_sep", 0);
        used.put("cf_ovr_more", 0);
        used.put("cf_ovr_sprite_title_fmt", 2);
        used.put("cf_ovr_sprite_detail_fmt", 1);
        used.put("cf_ovr_key_title_fmt", 2);
        used.put("cf_ovr_key_detail_fmt", 1);
        used.put("cf_ovr_content_title_fmt", 2);
        used.put("cf_ovr_content_detail_fmt", 1);
        used.put("cf_ovr_target_fmt", 2);
        used.put("cf_ovr_type_block", 0);
        used.put("cf_ovr_type_item", 0);
        used.put("cf_ovr_type_liquid", 0);
        used.put("cf_ovr_type_unit", 0);
        used.put("cf_ovr_type_status", 0);
        used.put("cf_ovr_type_sector", 0);
        used.put("cf_ovr_type_planet", 0);
        used.put("cf_ovr_type_weather", 0);
        used.put("cf_ovr_type_team", 0);
        used.put("cf_ovr_type_other", 0);
        checkRes(used);
    }

    // ── 第 234 轮：覆盖原版（贴图 / 文案）─────────────────────────────────────

    /**
     * 判据：**两个不同的模组**都在覆盖同一份原版内容才报。
     *
     * <p>★ 文案只认原版键（`vanillaKeys`）—— 模组自己的键不算，否则一份翻译包就刷满整页。
     * ★ 贴图没有"原版清单"可查（版本 APK 里是打好的图集），所以按 `sprites-override/` 的
     * **同一路径**判：同名贴图只有一份能生效，这就是覆盖冲突。
     */
    private static void overrides(File work) throws Exception {
        File a = new File(work, "ovr-a");
        File b = new File(work, "ovr-b");
        File c = new File(work, "ovr-c");
        String zh = "bundle_zh_CN.properties";
        override(a, new String[]{"zz-shared.png", "only-a.png"}, zh,
                "misc.help=帮助甲\nzz.own=自己的甲\n");
        override(b, new String[]{"zz-shared.png", "only-b.png"}, zh,
                "misc.help=帮助乙\nzz.own=自己的乙\n");
        // C 只和 A 撞**模组自己的键**（zz.own），不撞贴图 ⇒ 整条都不许报
        override(c, new String[0], zh, "zz.own=自己的丙\n");
        List<Conflicts.Mod> mods = new ArrayList<Conflicts.Mod>();
        mods.add(mod(a, "Ovr A", "", ""));
        mods.add(mod(b, "Ovr B", "", ""));
        mods.add(mod(c, "Ovr C", "", ""));

        // 先证明读得出来（读不出来时，下面的"没撞"会白过）
        for (Conflicts.Mod m : mods) Overrides.read(m);
        check(mods.get(0).overrideSprites.contains("zz-shared.png")
                        && mods.get(0).bundleKeys.containsKey(zh),
                "★读覆盖通道：目录形态的 sprites-override / bundles 都读到了");

        Set<String> van = new HashSet<String>();
        van.add("misc.help");
        Conflicts.Result r = Conflicts.scan(mods, van);
        check(overlapSprites(r, 0, 1).contains("zz-shared.png")
                        && !overlapSprites(r, 0, 1).contains("only-a.png"),
                "★两个模组都覆盖同一张贴图 ⇒ 报（且只报**撞上**的那张）");
        check(overlapKeys(r, 0, 1).contains("misc.help") && !overlapKeys(r, 0, 1).contains("zz.own"),
                "★两个模组都改同一句**原版**文案 ⇒ 报；模组自己的键（zz.own）不报");
        check(!hasOverlap(r, 0, 2), "★只撞模组自己的键（zz.own）⇒ 整条不报（那不是覆盖原版）");
        check(!hasOverlap(r, 1, 2), "★反向：B 与 C 没有任何共同覆盖 ⇒ 不报");

        // 反方向：没有原版键清单 ⇒ 文案这条整条不报（不猜），贴图照报
        Conflicts.Result noVan = Conflicts.scan(mods, java.util.Collections.<String>emptySet());
        check(overlapKeys(noVan, 0, 1).isEmpty() && !overlapSprites(noVan, 0, 1).isEmpty(),
                "★读不到原版键清单 ⇒ 文案不报（贴图不受影响）");

        // 排除项：同名（重名由 DUPLICATED 说）与「A 里带了 B」（同一份代码两份壳）
        List<Conflicts.Mod> same = new ArrayList<Conflicts.Mod>();
        same.add(mod(a, "Same Title", "", ""));
        same.add(mod(b, "Same Title", "", ""));
        check(!hasOverlap(Conflicts.scan(same, van), 0, 1), "★同名两个包 ⇒ 不报覆盖（那是重名那条）");

        File parent = new File(work, "ovr-parent");
        File child = new File(work, "ovr-child");
        dex(parent, "FAKE-DEX contains em/Child here");
        dex(child, "FAKE-DEX em/Child");
        override(parent, new String[]{"zz-shared.png"}, zh, "misc.help=x\n");
        override(child, new String[]{"zz-shared.png"}, zh, "misc.help=y\n");
        List<Conflicts.Mod> cont = new ArrayList<Conflicts.Mod>();
        cont.add(mod(parent, "Parent", "pa/Parent", ""));
        cont.add(mod(child, "Child", "em/Child", ""));
        check(!hasOverlap(Conflicts.scan(cont, van), 0, 1),
                "★「Parent 里带了 Child」⇒ 不报覆盖（同一份代码两份壳，不是覆盖冲突）");

        // zip 形态：读条目名 + bundle 键（与目录形态同一套判据）
        File z = new File(work, "ovr-z.zip");
        zipOverride(z, "sprites-override/zip-shared.png", "bundle.properties", "misc.help=z\n");
        Conflicts.Mod zm = new Conflicts.Mod();
        zm.path = z.getAbsolutePath();
        zm.title = "Zip Mod";
        zm.internalName = "zip-mod";
        Overrides.read(zm);
        check(zm.overrideSprites.contains("zip-shared.png")
                        && zm.bundleKeys.get("bundle.properties") != null
                        && zm.bundleKeys.get("bundle.properties").contains("misc.help"),
                "★读覆盖通道：zip 形态也读得到（sprites-override 路径 / bundle 键）");

        // ── 内容补丁（第 235 轮补：最常见的"改数值"—— content 里不带 type 的 JSON）──
        //    源码依据：v159 `ContentParser` 在名字找得到原版内容、又没有 `type` 时，
        //    直接拿那个原版对象、把 JSON 字段写上去（有了 type 反而当新内容）。
        check(!Overrides.hasTopLevelType("{\"research\":{\"objectives\":[{\"type\":\"SectorComplete\"}]}}"),
                "★顶层没 type、嵌套有 type ⇒ 仍算补丁（别用全文匹配）");
        check(Overrides.hasTopLevelType("{\"type\":\"GenericCrafter\",\"health\":1}"),
                "★顶层有 type ⇒ 新内容，不算覆盖");
        check(!Overrides.hasTopLevelType("{ // type: fake\n \"health\": 1 }"), "★注释里的 type 不算");
        check(!Overrides.hasTopLevelType("{ \"description\": \"type: looks like a field\" }"),
                "★字符串值里的 type 不算");

        File pa = new File(work, "ovr-patch-a");
        File pb = new File(work, "ovr-patch-b");
        File pc = new File(work, "ovr-patch-c");
        content(pa, "blocks/mass-driver.json", "{\n  \"itemCapacity\": 999,\n  \"health\": 1234\n}\n");
        content(pa, "blocks/launch-pad.json",
                "{\n  \"name\": \"改过的发射台\",\n  \"research\": { \"objectives\": [ { \"type\": \"SectorComplete\" } ] }\n}\n");
        content(pb, "blocks/mass-driver.json", "{\n  \"reload\": 30\n}\n");
        content(pc, "blocks/mass-driver.json", "{\n  \"health\": 1\n}\n");
        content(pc, "blocks/zz-new.json", "{\n  \"type\": \"GenericCrafter\"\n}\n");
        content(pc, "blocks/zz-unknown.json", "{\n  \"health\": 5\n}\n");
        List<Conflicts.Mod> pm = new ArrayList<Conflicts.Mod>();
        pm.add(mod(pa, "Patch A", "", ""));
        pm.add(mod(pb, "Patch B", "", ""));
        pm.add(mod(pc, "Patch C", "", ""));
        Set<String> vk = new HashSet<String>();
        vk.add("block.mass-driver.name");
        vk.add("block.launch-pad.name");
        Conflicts.Result pr = Conflicts.scan(pm, vk);
        check(overlapContents(pr, 0, 1).contains("block:mass-driver"),
                "★两个模组都给原版 mass-driver 打补丁（不带 type 的 JSON）⇒ 报");
        check(!overlapContents(pr, 0, 1).contains("block:launch-pad"),
                "★反向：只有 A 打了 launch-pad ⇒ 不报");
        check(!overlapContents(pr, 0, 2).contains("block:zz-new"),
                "★带 type 的 JSON = 新内容 ⇒ 不算覆盖（A 的嵌套 type 也照样算补丁）");
        check(!overlapContents(pr, 0, 2).contains("block:zz-unknown"),
                "★名字不在原版清单里 ⇒ 不报（那是新内容）");
        check(overlapContents(pr, 0, 2).contains("block:mass-driver"),
                "★C 也打了 mass-driver ⇒ 与 A 报");

        File zp = new File(work, "ovr-patch.zip");
        zipContent(zp, "content/blocks/mass-driver.json", "{ \"health\": 2 }");
        Conflicts.Mod zpm = new Conflicts.Mod();
        zpm.path = zp.getAbsolutePath();
        zpm.title = "Zip Patch";
        zpm.internalName = "zip-patch";
        Overrides.read(zpm);
        check(zpm.contentPatches.get("block") != null
                        && zpm.contentPatches.get("block").contains("mass-driver"),
                "★读内容补丁：zip 形态也读得到（content/blocks/*.json 无 type）");

        // 有的包外面还套一层目录（host 把这层算成 rootPrefix）⇒ 三条通道都要先剥掉它
        File zw = new File(work, "ovr-wrapped.zip");
        zipWrapped(zw);
        Conflicts.Mod zwm = new Conflicts.Mod();
        zwm.path = zw.getAbsolutePath();
        zwm.rootPrefix = "Pack/";
        zwm.title = "Wrapped";
        zwm.internalName = "wrapped";
        Overrides.read(zwm);
        check(zwm.overrideSprites.contains("zz-shared.png")
                        && zwm.bundleKeys.containsKey("bundle.properties")
                        && zwm.contentPatches.get("block") != null
                        && zwm.contentPatches.get("block").contains("mass-driver"),
                "★包外套一层目录（rootPrefix）⇒ 贴图 / 文案 / 内容三条都读得到");
    }

    private static void zipWrapped(File file) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(file));
        try {
            put(z, "Pack/mod.json", "name: wrapped\n");
            put(z, "Pack/sprites-override/zz-shared.png", "png");
            put(z, "Pack/bundles/bundle.properties", "misc.help=x\n");
            put(z, "Pack/content/blocks/mass-driver.json", "{ \"health\": 1 }");
            put(z, "Other/should-be-ignored.png", "png");
        } finally {
            z.close();
        }
    }

    private static void put(ZipOutputStream z, String path, String body) throws Exception {
        z.putNextEntry(new ZipEntry(path));
        z.write(body.getBytes("UTF-8"));
        z.closeEntry();
    }

    private static void content(File dir, String rel, String body) throws Exception {
        File f = new File(dir, "content/" + rel);
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        writeText(f, body);
    }

    private static void zipContent(File file, String path, String body) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(file));
        try {
            z.putNextEntry(new ZipEntry("mod.json"));
            z.write("name: zip-patch\n".getBytes("UTF-8"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(path));
            z.write(body.getBytes("UTF-8"));
            z.closeEntry();
        } finally {
            z.close();
        }
    }

    private static java.util.List<String> overlapContents(Conflicts.Result r, int a, int b) {
        for (Conflicts.Overlap o : r.overlaps) {
            if ((o.a == a && o.b == b) || (o.a == b && o.b == a)) return o.contents;
        }
        return new ArrayList<String>();
    }

    private static void override(File dir, String[] sprites, String bundleName,
                                 String bundleBody) throws Exception {
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("cannot mkdir " + dir);
        }
        for (String s : sprites) {
            File f = new File(dir, "sprites-override/" + s);
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            writeText(f, "png");
        }
        File bd = new File(dir, "bundles");
        bd.mkdirs();
        writeText(new File(bd, bundleName), bundleBody);
    }

    private static void zipOverride(File file, String sprite, String bundleName,
                                    String bundleBody) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(file));
        try {
            z.putNextEntry(new ZipEntry("mod.json"));
            z.write("name: zip-mod\n".getBytes("UTF-8"));
            z.closeEntry();
            if (sprite != null) {
                z.putNextEntry(new ZipEntry(sprite));
                z.write(new byte[]{1});
                z.closeEntry();
            }
            if (bundleName != null) {
                z.putNextEntry(new ZipEntry("bundles/" + bundleName));
                z.write(bundleBody.getBytes("UTF-8"));
                z.closeEntry();
            }
        } finally {
            z.close();
        }
    }

    private static boolean hasOverlap(Conflicts.Result r, int a, int b) {
        for (Conflicts.Overlap o : r.overlaps) {
            if ((o.a == a && o.b == b) || (o.a == b && o.b == a)) return true;
        }
        return false;
    }

    private static java.util.List<String> overlapSprites(Conflicts.Result r, int a, int b) {
        for (Conflicts.Overlap o : r.overlaps) {
            if ((o.a == a && o.b == b) || (o.a == b && o.b == a)) return o.sprites;
        }
        return new ArrayList<String>();
    }

    private static java.util.List<String> overlapKeys(Conflicts.Result r, int a, int b) {
        for (Conflicts.Overlap o : r.overlaps) {
            if ((o.a == a && o.b == b) || (o.a == b && o.b == a)) return o.keys;
        }
        return new ArrayList<String>();
    }

    /** 只有一个事实变坏 ⇒ 只有那一道门不通过、标签必须换、原因必须非空。 */
    private static java.util.List<Gates.Decision> expectOnly(Gates.Facts clean, Gates.Facts broken,
                                                             int id, String what) {
        java.util.List<Gates.Decision> ds = Gates.decide(broken);
        String baseLabel = labelOf(Gates.decide(clean), id);
        int fails = 0;
        String label = "", note = "";
        for (Gates.Decision d : ds) {
            if (!d.pass) fails++;
            if (d.id == id) {
                label = d.labelKey;
                note = d.noteKey;
            }
        }
        check(fails == 1 && !label.equals(baseLabel) && !note.isEmpty(),
                "★" + what + " ⇒ 只有第 " + (id + 1) + " 道不通过、标签换了、原因非空（"
                        + label + " / " + note + "）");
        check(id == Gates.LAST ? label.startsWith("cf_gate_last")
                        : label.equals(baseLabel + "_bad"),
                "★" + what + " ⇒ 用的是 *_bad 那条标签（❌ 后面不许跟肯定句）");
        return ds;
    }

    /** 每个 decision：文案 key + 参数个数；顺带钉住 pass/fail 的说明规则。 */
    private static void record(java.util.Map<String, Integer> used, java.util.List<Gates.Decision> ds) {
        for (Gates.Decision d : ds) {
            putUsed(used, d.labelKey, 0);
            if (d.noteKey.isEmpty()) {
                if (!d.pass) check(false, "★不通过却没有原因（" + d.labelKey + "）");
                continue;
            }
            putUsed(used, d.noteKey, d.noteArgs.length);
            if (d.pass) {
                boolean allowed = (d.id == Gates.ON && "cf_gate_on_unknown".equals(d.noteKey))
                        || (d.id == Gates.LAST && "cf_gate_last_pending".equals(d.noteKey));
                check(allowed, "★通过时只有两处允许带补充说明（" + d.labelKey + " / " + d.noteKey + "）");
            }
        }
    }

    private static void putUsed(java.util.Map<String, Integer> used, String key, int args) {
        Integer old = used.get(key);
        if (old != null && old.intValue() != args) {
            check(false, "★同一个 key 的占位符参数个数前后不一致（" + key + "：" + old + " vs " + args + "）");
        }
        used.put(key, args);
    }

    /** 判据用到的 key 必须在**两种语言**里都存在，且 `%n$` 的最大序号 == 参数个数。 */
    private static void checkRes(java.util.Map<String, Integer> used) {
        File root = resRoot();
        java.util.Map<String, String> zh = readStrings(new File(root, "values-zh/strings.xml"));
        java.util.Map<String, String> en = readStrings(new File(root, "values/strings.xml"));
        if (zh.isEmpty() || en.isEmpty()) {
            check(false, "★找不到插件 res/（用 build.sh check 跑，或 -Dmh.root=<插件目录>）：" + root);
            return;
        }
        StringBuilder bad = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : used.entrySet()) {
            String k = e.getKey();
            String z = zh.get(k), s = en.get(k);
            if (z == null || s == null) {
                bad.append(k).append(z == null ? "(zh缺)" : "(en缺)").append(' ');
            } else if (!phOk(z, e.getValue().intValue()) || !phOk(s, e.getValue().intValue())) {
                bad.append(k).append("(占位符) ");
            }
        }
        check(bad.length() == 0, "★文案：六道门用到的 " + used.size()
                + " 条 key 两种语言都在、%n$ 序号与参数个数一致" + (bad.length() == 0 ? "" : "：" + bad));
    }

    /** 值里的 `%n$` 最大序号必须等于参数个数；裸 `%s` / `%d` 一律算错（同宿主 RES-03）。 */
    private static boolean phOk(String v, int args) {
        if (v == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%(\\d)\\$").matcher(v);
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        if (v.replaceAll("%\\d\\$", "").indexOf('%') >= 0) return false;
        return max == args;
    }

    private static File resRoot() {
        String p = System.getProperty("mh.root");
        File dir = p == null || p.trim().isEmpty() ? new File("plugins/mods-health") : new File(p);
        return new File(dir, "res");
    }

    private static java.util.Map<String, String> readStrings(File f) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        if (f == null || !f.isFile()) return out;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>",
                            java.util.regex.Pattern.DOTALL).matcher(text);
            while (m.find()) out.put(m.group(1), m.group(2));
        } catch (Throwable t) {
            // 读不了就返回空表 ⇒ 上层那条断言会红（不会假通过）
        }
        return out;
    }

    private static Gates.Decision byId(java.util.List<Gates.Decision> ds, int id) {
        for (Gates.Decision d : ds) if (d.id == id) return d;
        return new Gates.Decision(id, "", false, "", null);
    }

    private static String labelOf(java.util.List<Gates.Decision> ds, int id) {
        return byId(ds, id).labelKey;
    }

    private static boolean allPass(java.util.List<Gates.Decision> ds) {
        for (Gates.Decision d : ds) if (!d.pass) return false;
        return true;
    }

    // ---- 小工具 ---------------------------------------------------------------

    private static Conflicts.Mod mod(File dir, String title, String main, String desc) {
        Conflicts.Mod m = new Conflicts.Mod();
        m.path = dir.getAbsolutePath();
        m.title = title;
        m.name = title;
        m.internalName = title.toLowerCase().replace(' ', '-');
        m.description = desc;
        m.main = main;
        m.rootDir = dir.getAbsolutePath();
        m.directory = true;
        return m;
    }

    private static void dex(File dir, String body) throws Exception {
        if (!dir.mkdirs()) throw new IllegalStateException("cannot mkdir " + dir);
        writeText(new File(dir, "classes.dex"), body);
    }

    private static long dexLen(File dir) {
        return new File(dir, "classes.dex").length();
    }

    private static void zip(File file, String prefix, String dexBody) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(file));
        try {
            z.putNextEntry(new ZipEntry(prefix + "mod.json"));
            z.write("name: Nested Pack\nmain: nested.Main\njava: true\n".getBytes("UTF-8"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(prefix + "classes.dex"));
            z.write(dexBody.getBytes("UTF-8"));
            z.closeEntry();
        } finally {
            z.close();
        }
    }

    private static void writeText(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private static String issueOther(List<Conflicts.Issue> xs, int index, int kind) {
        for (Conflicts.Issue it : xs) {
            if (it.index == index && it.kind == kind) return it.other;
        }
        return "";
    }

    private static boolean hasIssue(List<Conflicts.Issue> xs, int index, int kind) {
        for (Conflicts.Issue it : xs) {
            if (it.index == index && it.kind == kind) return true;
        }
        return false;
    }

    private static boolean hasPair(List<Conflicts.Pair> ps, int a, int b) {
        for (Conflicts.Pair p : ps) {
            if (p.a == a && p.b == b) return true;
        }
        return false;
    }

    private static void check(boolean ok, String what) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private Check() { }
}
