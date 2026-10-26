package io.mdt.plugins.usagestats.core;

import java.util.List;

/** PC 侧判据自检：`bash plugins/usagestats/build.sh check`。 */
public final class Check {

    public static void main(String[] args) {
        int fail = 0;

        // ── 解析：8 列新格式 ─────────────────────────────────────────────────
        String text = "default\tpkg\t159.7\t1000\t111\tMindustry  改端  159.7\t8-official-159.7\t160.1\n"
                + "default\t?\t\t2500\t222\t\t\t\n"
                + "bad line\n"
                + "x\ten:2\t159.7\t-5\t333\t\t\t\n"
                + "\ten:3\t159.7\t9000\t444\t\t\t\n"
                + "slot\t\n";
        List<Playtime.Entry> es = Playtime.parse(text);
        fail += ok(es.size() == 2, "parse: 2 good rows / 4 bad");
        fail += ok(es.get(0).slot.equals("default") && es.get(0).key.equals("pkg")
                        && es.get(0).tag.equals("159.7") && es.get(0).ms == 1000
                        && es.get(0).title.equals("Mindustry  改端  159.7")
                        && es.get(0).rawName.equals("8-official-159.7")
                        && es.get(0).upstream.equals("160.1"),
                "parse: 8 列字段全对上（tag / title / rawName / upstream）");
        fail += ok(es.get(1).key.equals("?") && es.get(1).tag.isEmpty()
                        && es.get(1).title.isEmpty(),
                "parse: 空 tag / 空快照照读");

        // ── 旧格式（只读兼容） ────────────────────────────────────────────────
        List<Playtime.Entry> old4 = Playtime.parse("a\ten:1\t100\t1\n");
        fail += ok(old4.size() == 1 && old4.get(0).tag.isEmpty()
                        && Playtime.valueFor("a\ten:1\t100\t1\n", "a", "en:1", "159.7") == 100,
                "旧 4 列：tag 空，带版本号查询也能命中（迁移前的老数据）");
        List<Playtime.Entry> old5 = Playtime.parse("a\ten:1\t700\t2\t159.7\n");
        fail += ok(old5.size() == 1 && old5.get(0).tag.equals("159.7")
                        && Playtime.valueFor("a\ten:1\t700\t2\t159.7\n", "a", "en:1", "159.7") == 700,
                "旧 5 列：末列当 tag（第 255 轮那份账本照读）");

        // ── 写回：同 (槽,key,版本号) 覆盖、不同版本号分行 ─────────────────────
        Playtime.Snap v159 = new Playtime.Snap("159.7", "Mindustry  159.7",
                "8-official-159.7", "160.1");
        Playtime.Snap v160 = new Playtime.Snap("160.1", "Mindustry  160.1",
                "8-official-160.1", "160.1");
        String t1 = Playtime.put("", "default", "pkg", v159, 5000, 1);
        fail += ok(Playtime.valueFor(t1, "default", "pkg", "159.7") == 5000,
                "put: 新行（带版本快照）");
        fail += ok(Playtime.parse(t1).get(0).title.equals("Mindustry  159.7"),
                "put: title 落盘");
        String t2 = Playtime.put(t1, "default", "pkg", v160, 8000, 2);
        fail += ok(Playtime.parse(t2).size() == 2 && Playtime.total(t2) == 13000
                        && Playtime.valueFor(t2, "default", "pkg", "159.7") == 5000
                        && Playtime.valueFor(t2, "default", "pkg", "160.1") == 8000,
                "★已装更新：不同版本号 ⇒ 两行（老版本的时间不并到新版本）");
        String t3 = Playtime.put(t2, "default", "pkg", v160, 9000, 3);
        fail += ok(Playtime.parse(t3).size() == 2 && Playtime.total(t3) == 14000
                        && Playtime.valueFor(t3, "default", "pkg", "160.1") == 9000,
                "put: 同版本号覆盖，不重复");
        String t4 = Playtime.put(t3, "other", "pkg2", v159, 3000, 4);
        fail += ok(Playtime.total(t4) == 17000 && Playtime.parse(t4).size() == 3,
                "put: 另一个槽 / key 追加");
        String two = "a\tk\t159.7\t100\t1\tt1\tr1\tu1\n"
                + "a\tk\t160.1\t300\t2\tt2\tr2\tu2\n";
        String noTag = Playtime.put(two, "a", "k", new Playtime.Snap("", "", "", ""), 500, 3);
        fail += ok(Playtime.parse(noTag).size() == 2
                        && Playtime.valueFor(noTag, "a", "k", "159.7") == 500
                        && Playtime.valueFor(noTag, "a", "k", "160.1") == 300,
                "宿主没给版本号：只更新第一行，另一个版本行必须保留");

        // ── 旧行迁移 / 快照保留 ───────────────────────────────────────────────
        String leg = "a\ten:1\t700\t2\n";                         // 旧 4 列
        String mig = Playtime.put(leg, "a", "en:1", v159, 700, 3);
        fail += ok(Playtime.parse(mig).size() == 1
                        && Playtime.parse(mig).get(0).tag.equals("159.7")
                        && Playtime.parse(mig).get(0).title.equals("Mindustry  159.7")
                        && Playtime.valueFor(mig, "a", "en:1", "159.7") == 700,
                "★迁移：旧行没有 tag ⇒ 第一次写入补上版本号与快照（时间不丢）");
        String keep = Playtime.put(mig, "a", "en:1",
                new Playtime.Snap("", "", "", ""), 800, 4);
        fail += ok(Playtime.parse(keep).size() == 1
                        && Playtime.parse(keep).get(0).tag.equals("159.7")
                        && Playtime.parse(keep).get(0).title.equals("Mindustry  159.7")
                        && Playtime.parse(keep).get(0).ms == 800,
                "宿主这次没给版本信息 ⇒ 保留原 tag / title，只更新毫秒");

        // ── 求和 / 按槽 / 排序 ────────────────────────────────────────────────
        String t5 = "a\tv1\t159.7\t100\t1\t\t\t\n" + "a\tv2\t160.1\t300\t2\t\t\t\n"
                + "b\tv1\t159.7\t700\t3\t\t\t\n";
        fail += ok(Playtime.total(t5) == 1100, "total: 100+300+700");
        fail += ok(Playtime.totalForSlot(t5, "a") == 400, "totalForSlot: a");
        List<Playtime.Entry> a = Playtime.forSlot(t5, "a");
        fail += ok(a.size() == 2 && a.get(0).ms == 300 && a.get(1).ms == 100,
                "forSlot: sorted desc");
        List<Playtime.Entry> by = Playtime.bySlot(t5);
        fail += ok(by.size() == 2 && by.get(0).slot.equals("b") && by.get(0).ms == 700,
                "bySlot: grouped + sorted desc");
        fail += ok(Playtime.valueFor(t5, "a", "nope", "") == 0
                        && Playtime.valueFor(t5, "a", "v1", "999") == 0
                        && Playtime.totalForSlot(t5, "nope") == 0,
                "missing key / 版本号不符 / 槽 -> 0");
        fail += ok(Playtime.total("") == 0 && Playtime.total(null) == 0, "empty/null -> 0");

        // ── 坏行：坏毫秒 / 空 key / 段数不够 ──────────────────────────────────
        String bad = "a\tk\t159.7\txx\t1\tt\tr\tu\n"
                + "a\t\t159.7\t100\t1\tt\tr\tu\n"
                + "a\tk\t159.7\n"
                + "only\tthree\tcols\n";
        fail += ok(Playtime.parse(bad).isEmpty(), "坏行：坏毫秒 / 空 key / 段数不够 ⇒ 全跳过");

        // ── 字段卫生：制表符 / 换行会把一行写成两行 ──────────────────────────
        String t6 = Playtime.put("", "a\tb\nc", "k1\tk2",
                new Playtime.Snap("1\t9", "t\nt", "r\tx", "u\ny"), 100, 1);
        fail += ok(Playtime.parse(t6).size() == 1
                        && Playtime.valueFor(t6, "a b c", "k1 k2", "1 9") == 100
                        && Playtime.parse(t6).get(0).title.equals("t t")
                        && Playtime.parse(t6).get(0).upstream.equals("u y"),
                "clean: 控制字符 -> 空格，仍是一行");

        // ── 显示层合并：同一游戏版本（同名）合成一行 ─────────────────────────
        List<Playtime.Slice> sl = new java.util.ArrayList<Playtime.Slice>();
        sl.add(new Playtime.Slice("A  159.7", 100));
        sl.add(new Playtime.Slice("A  159.7", 300));
        sl.add(new Playtime.Slice("B  159.7", 50));
        sl.add(new Playtime.Slice("", 10));
        sl.add(new Playtime.Slice("", 20));
        List<Playtime.Slice> mg = Playtime.mergeNamed(sl);
        fail += ok(mg.size() == 3 && mg.get(0).name.equals("A  159.7") && mg.get(0).ms == 400
                        && mg.get(1).name.equals("B  159.7") && mg.get(1).ms == 50
                        && mg.get(2).name.isEmpty() && mg.get(2).ms == 30,
                "mergeNamed: 同名合并 / 不同名分开 / 空名也合并 / 按时长降序");
        fail += ok(Playtime.mergeNamed(null).isEmpty(), "mergeNamed: null -> 空");

        // ── 时长换算 ─────────────────────────────────────────────────────────
        int[] hm = Playtime.hm(3_600_000L + 12 * 60_000L);
        fail += ok(hm[0] == 1 && hm[1] == 12, "hm: 1h12");
        int[] neg = Playtime.hm(-5);
        fail += ok(neg[0] == 0 && neg[1] == 0, "hm: negative -> 0/0");

        System.out.println(fail == 0 ? "== USAGESTATS CHECK OK ==" : "!! " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int ok(boolean cond, String what) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        return cond ? 0 : 1;
    }

    private Check() {}
}
