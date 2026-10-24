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
        check(!hasPair(r.contained, 0, 2) && !hasPair(r.contained, 2, 0),
                "★反向：无关模组 C 与 A 之间不报「内置」");

        check(hasPair(r.mentioned, 0, 1), "简介提及：A 的简介里写了 Small Mod ⇒ 线索里有 A→B");
        check(!hasPair(r.mentioned, 1, 0), "★反向：B 的简介没提 A ⇒ 不报 B→A");

        // ★ 元断言：把 B 的主类从 A 的 dex 里删掉 ⇒ 必须不再报「内置」（证明上一条不是恒真）
        File a2Dir = new File(work, "a2-big-only");
        dex(a2Dir, "FAKE-DEX big/BigMod only");
        List<Conflicts.Mod> mods2 = new ArrayList<Conflicts.Mod>();
        mods2.add(mod(a2Dir, "Big Mod", "big.BigMod", ""));
        mods2.add(b);
        Conflicts.Result r2 = Conflicts.scan(mods2);
        check(r2.contained.isEmpty(), "★元断言：拿掉 B 的主类 ⇒ 不再报「内置」");

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
