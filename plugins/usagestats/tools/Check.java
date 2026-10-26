package io.mdt.plugins.usagestats.core;

import java.util.List;

/** PC 侧判据自检：`bash plugins/usagestats/build.sh check`。 */
public final class Check {

    public static void main(String[] args) {
        int fail = 0;

        // ── 解析：坏行跳过、空槽跳过、空版本归到「未知」 ──────────────────────
        String text = "default\ten:1\t1000\t111\n"
                + "default\t?\t2500\t222\n"
                + "bad line\n"
                + "x\ten:2\t-5\t333\n"
                + "\ten:3\t9000\t444\n"
                + "slot\t\n";                       // 段数不够
        List<Playtime.Entry> es = Playtime.parse(text);
        fail += ok(es.size() == 2, "parse: 2 good rows / 3 bad");
        fail += ok(es.get(0).slot.equals("default") && es.get(0).ms == 1000
                        && es.get(0).version.equals("en:1"),
                "parse: first row fields");
        fail += ok(es.get(1).version.equals("?"), "parse: empty version -> ?");

        // ── 写回：新 key 追加、同 key 覆盖（不重复） ──────────────────────────
        String t1 = Playtime.put("", "default", "en:1", 5000, 1);
        fail += ok(Playtime.valueFor(t1, "default", "en:1") == 5000, "put: new row");
        String t2 = Playtime.put(t1, "default", "en:1", 8000, 2);
        fail += ok(Playtime.total(t2) == 8000 && Playtime.parse(t2).size() == 1,
                "put: same key overwrites, no duplicate");
        String t3 = Playtime.put(t2, "other", "en:2", 3000, 3);
        fail += ok(Playtime.total(t3) == 11000 && Playtime.parse(t3).size() == 2,
                "put: second slot adds a row");
        fail += ok(Playtime.put(t3, "", "en:1", 9999, 4).equals(t3),
                "put: empty slot -> unchanged (no half row)");

        // ── 求和 / 按槽 / 排序 ────────────────────────────────────────────────
        String t4 = "a\tv1\t100\t1\n" + "a\tv2\t300\t2\n" + "b\tv1\t700\t3\n";
        fail += ok(Playtime.total(t4) == 1100, "total: 100+300+700");
        fail += ok(Playtime.totalForSlot(t4, "a") == 400, "totalForSlot: a");
        List<Playtime.Entry> a = Playtime.forSlot(t4, "a");
        fail += ok(a.size() == 2 && a.get(0).ms == 300 && a.get(1).ms == 100,
                "forSlot: sorted desc");
        List<Playtime.Entry> by = Playtime.bySlot(t4);
        fail += ok(by.size() == 2 && by.get(0).slot.equals("b") && by.get(0).ms == 700,
                "bySlot: grouped + sorted desc");
        fail += ok(Playtime.valueFor(t4, "a", "nope") == 0
                        && Playtime.totalForSlot(t4, "nope") == 0,
                "missing key / slot -> 0");
        fail += ok(Playtime.total("") == 0 && Playtime.total(null) == 0, "empty/null -> 0");

        // ── 字段卫生：制表符 / 换行会把一行写成两行 ──────────────────────────
        String t5 = Playtime.put("", "a\tb\nc", "v1\tv2", 100, 1);
        fail += ok(Playtime.parse(t5).size() == 1
                        && Playtime.valueFor(t5, "a b c", "v1 v2") == 100,
                "clean: control chars -> spaces, still one row");

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
