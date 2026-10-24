package io.mdt.plugins.mods.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件侧自检（PC 上跑，`bash plugins/conflict/build.sh check`）。
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
