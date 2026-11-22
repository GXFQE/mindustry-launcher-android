package io.mdt.plugins.usagestats.core;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** PC 侧判据自检：`bash plugins/usagestats/build.sh check`。 */
public final class Check {

    public static void main(String[] args) {
        int fail = 0;

        // ── 解析：10 列新格式（第 388 轮加 patch / patchLabel） ────────────────
        String text = "default\tpkg\t159.7\t1000\t111\tMindustry  改端  159.7\t8-official-159.7\t160.1\t\t\n"
                + "default\t?\t\t2500\t222\t\t\t\t\t\n"
                + "bad line\n"
                + "x\ten:2\t159.7\t-5\t333\t\t\t\t\t\n"
                + "\ten:3\t159.7\t9000\t444\t\t\t\t\t\n"
                + "slot\t\n";
        List<Playtime.Entry> es = Playtime.parse(text);
        fail += ok(es.size() == 2, "parse: 2 good rows / 4 bad");
        fail += ok(es.get(0).slot.equals("default") && es.get(0).key.equals("pkg")
                        && es.get(0).tag.equals("159.7") && es.get(0).ms == 1000
                        && es.get(0).title.equals("Mindustry  改端  159.7")
                        && es.get(0).rawName.equals("8-official-159.7")
                        && es.get(0).upstream.equals("160.1")
                        && es.get(0).patch.isEmpty() && es.get(0).patchLabel.isEmpty(),
                "parse: 10 列字段全对上（tag / title / rawName / upstream / 空 patch）");
        fail += ok(es.get(1).key.equals("?") && es.get(1).tag.isEmpty()
                        && es.get(1).title.isEmpty(),
                "parse: 空 tag / 空快照照读");

        // 第 385 轮前的 8 列（没有 patch）照读；patch 为空
        List<Playtime.Entry> old8 = Playtime.parse(
                "a\ten:1\t159.7\t100\t1\tt\tr\tu\n");
        fail += ok(old8.size() == 1 && old8.get(0).patch.isEmpty()
                        && Playtime.valueFor("a\ten:1\t159.7\t100\t1\tt\tr\tu\n",
                                "a", "en:1", "159.7") == 100,
                "旧 8 列：没有 patch ⇒ 当本体（老账本照读）");

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

        // ── 本体 + 补丁 ≠ 本体（第 388 轮） ──────────────────────────────────
        Playtime.Snap patchA = new Playtime.Snap("159.7", "Mindustry  159.7",
                "8-official-159.7", "160.1", "a@1,b@2", "补丁 A、补丁 B");
        String tp1 = Playtime.put(t1, "default", "pkg", patchA, 6000, 2);
        fail += ok(Playtime.parse(tp1).size() == 2
                        && Playtime.valueFor(tp1, "default", "pkg", "159.7") == 5000
                        && Playtime.valueFor(tp1, "default", "pkg", "159.7", "a@1,b@2") == 6000,
                "★同版本带补丁：本体行与「本体+补丁」行分开，互不覆盖");
        String tp2 = Playtime.put(tp1, "default", "pkg", patchA, 7000, 3);
        fail += ok(Playtime.parse(tp2).size() == 2
                        && Playtime.valueFor(tp2, "default", "pkg", "159.7", "a@1,b@2") == 7000
                        && Playtime.valueFor(tp2, "default", "pkg", "159.7") == 5000,
                "同一补丁身份重复玩 ⇒ 只覆盖补丁行，不动本体");
        Playtime.Snap patchB = new Playtime.Snap("159.7", "Mindustry  159.7",
                "8-official-159.7", "160.1", "a@1,b@3", "补丁 A、补丁 C");
        String tp3 = Playtime.put(tp2, "default", "pkg", patchB, 8000, 4);
        fail += ok(Playtime.parse(tp3).size() == 3
                        && Playtime.valueFor(tp3, "default", "pkg", "159.7", "a@1,b@2") == 7000
                        && Playtime.valueFor(tp3, "default", "pkg", "159.7", "a@1,b@3") == 8000,
                "换了一个补丁 ⇒ 第三条「本体+补丁」行（不同补丁不合并）");
        fail += ok(Playtime.valueFor(tp3, "default", "pkg", "159.7", "nope") == 0
                        && Playtime.valueFor("a\ten:1\t100\t1\n", "a", "en:1", "159.7", "a@1") == 0,
                "★补丁身份不匹配 / 老账本没有 patch ⇒ 不能把补丁时长算进本体");
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

        // ── 每局流水（第 396 轮）：「哪一天」+ 具体时刻 ─────────────────────────
        TimeZone sh = TimeZone.getTimeZone("Asia/Shanghai");
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long s23 = at(sh, 2026, 11, 18, 23, 0);
        long e01 = at(sh, 2026, 11, 19, 1, 0);
        Sessions.Entry cross = new Sessions.Entry(s23, e01, 7_200_000L, 7_200_000L,
                "clean", "default", "pkg", "160.1", "");
        String crossText = Sessions.format(cross);
        List<Sessions.Entry> sb = Sessions.parse(crossText);
        fail += ok(sb.size() == 1 && sb.get(0).started == s23 && sb.get(0).ended == e01
                        && sb.get(0).activeMs == 7_200_000L && sb.get(0).wallMs == 7_200_000L
                        && sb.get(0).exit.equals("clean") && sb.get(0).slot.equals("default")
                        && sb.get(0).key.equals("pkg") && sb.get(0).tag.equals("160.1"),
                "sessions: 一行一局的 format/parse 往返（时刻 / 身份全对上）");

        // 按天：23:00-01:00 跨零点 ⇒ 两天各 1h；会话数按开始日计
        List<Sessions.Day> d1 = Sessions.byDay(crossText, sh);
        fail += ok(d1.size() == 2
                        && d1.get(0).date.equals("2026-11-18") && d1.get(0).ms == 3_600_000L
                        && d1.get(0).sessions == 1
                        && d1.get(1).date.equals("2026-11-19") && d1.get(1).ms == 3_600_000L
                        && d1.get(1).sessions == 0,
                "★sessions: 跨零点按墙钟切成两天（各 1h；会话数按开始日）");

        // 单日分析：按小时切（23 点 / 次日 0 点各 1h）
        List<Sessions.Hour> hh = Sessions.byHour(Collections.singletonList(cross), sh);
        fail += ok(hh.size() == 2
                        && hh.get(0).day.equals("2026-11-18") && hh.get(0).hour == 23
                        && hh.get(0).ms == 3_600_000L
                        && hh.get(1).day.equals("2026-11-19") && hh.get(1).hour == 0
                        && hh.get(1).ms == 3_600_000L,
                "★sessions: 单日按小时拆（23 点 / 0 点各 1h）");

        // 时区：同一时刻按本地时区切日（+08 02:00 = UTC 前一天 18:00）
        long am = at(sh, 2026, 11, 18, 2, 0);
        Sessions.Entry early = new Sessions.Entry(am, am + 3_600_000L, 3_600_000L,
                3_600_000L, "", "default", "pkg", "", "");
        List<Sessions.Day> dz = Sessions.byDay(Collections.singletonList(early), sh);
        List<Sessions.Day> du = Sessions.byDay(Collections.singletonList(early), utc);
        fail += ok(dz.size() == 1 && dz.get(0).date.equals("2026-11-18")
                        && du.size() == 1 && du.get(0).date.equals("2026-11-17"),
                "★sessions: 按本地时区切日（+08 是 11-18，UTC 是 11-17）");

        // 余数：各段之和恒等于 activeMs（999ms 跨零点）
        long r0 = at(sh, 2026, 11, 18, 23, 59) + 59_500L;
        Sessions.Entry odd = new Sessions.Entry(r0, r0 + 1000L, 999L, 1000L,
                "", "d", "k", "", "");
        List<Sessions.Day> od = Sessions.byDay(Collections.singletonList(odd), sh);
        long osum = 0L;
        for (Sessions.Day x : od) osum += x.ms;
        fail += ok(od.size() == 2 && osum == 999L,
                "sessions: 除不尽时余数补到最后一段（各段和 == activeMs）");

        // 改系统时间：ended < started ⇒ 不产生负区间，全落开始那天
        Sessions.Entry rev = new Sessions.Entry(e01, s23, 3_600_000L, 0L,
                "", "d", "k", "", "");
        List<Sessions.Day> rd = Sessions.byDay(Collections.singletonList(rev), sh);
        fail += ok(rd.size() == 1 && rd.get(0).date.equals("2026-11-19")
                        && rd.get(0).ms == 3_600_000L,
                "sessions: ended < started（时钟回拨）⇒ 全落开始那天");

        // 一局跨多天（挂机）：每天各一段
        long m0 = at(sh, 2026, 11, 17, 22, 0);
        long m1 = at(sh, 2026, 11, 19, 2, 0);
        Sessions.Entry multi = new Sessions.Entry(m0, m1, 28 * 3_600_000L, 28 * 3_600_000L,
                "", "d", "k", "", "");
        List<Sessions.Day> md = Sessions.byDay(Collections.singletonList(multi), sh);
        fail += ok(md.size() == 3 && md.get(0).ms == 2 * 3_600_000L
                        && md.get(1).ms == 24 * 3_600_000L && md.get(2).ms == 2 * 3_600_000L,
                "sessions: 一局跨多天按墙钟摊开（2h / 24h / 2h）");

        // 排序 + 累加：两天各一局
        Sessions.Entry e17 = new Sessions.Entry(at(sh, 2026, 11, 17, 9, 0),
                at(sh, 2026, 11, 17, 10, 0), 3_600_000L, 3_600_000L, "", "d", "k2", "", "");
        List<Sessions.Day> ds = Sessions.byDay(java.util.Arrays.asList(cross, e17), sh);
        fail += ok(ds.size() == 3 && ds.get(0).date.equals("2026-11-17")
                        && ds.get(1).date.equals("2026-11-18")
                        && ds.get(2).date.equals("2026-11-19"),
                "sessions: byDay 按日期升序");

        // 坏行：列不够 / 0 时刻 / 0 时长 / 坏数字 ⇒ 全跳过
        String badS = "1\t2\t3\n"
                + "0\t0\t100\t0\t\t\tslot\tk\t\n"
                + "1\t2\t0\t0\t\t\tslot\tk\t\n"
                + "1\t2\tx\t0\t\t\tslot\tk\t\n";
        fail += ok(Sessions.parse(badS).isEmpty(),
                "sessions: 坏行（列不够 / started=0 / active=0 / 坏数字）全跳过");

        // 去重：核心字段相同（exit 不同也算同一局）
        Sessions.Entry dup = new Sessions.Entry(s23, e01, 7_200_000L, 7_200_000L,
                "unclean", "default", "pkg", "160.1", "");
        fail += ok(Sessions.sameRun(cross, dup) && !Sessions.sameRun(cross, early),
                "sessions: sameRun 只看核心字段（exit 不同也判同一局，防重复派发记两次）");

        // 字段卫生：制表符 / 换行不会把一局写成两行
        Sessions.Entry dirty = new Sessions.Entry(s23, e01, 1000L, 1000L,
                "a\tb", "s\nl", "k\t1", "t\tt", "p\nn");
        List<Sessions.Entry> db = Sessions.parse(Sessions.format(dirty));
        fail += ok(db.size() == 1 && db.get(0).exit.equals("a b")
                        && db.get(0).slot.equals("s l") && db.get(0).tag.equals("t t"),
                "sessions: clean 控制字符 -> 空格，仍是一行");

        // ── 热力图（第 400 轮）：范围 × 粒度 / 对齐 / 裁剪 / 上限 ─────────────────
        fail += ok(Heat.level(Heat.Grain.DAY, 0) == 0
                        && Heat.level(Heat.Grain.DAY, 1) == 1
                        && Heat.level(Heat.Grain.DAY, 30 * 60_000L) == 2
                        && Heat.level(Heat.Grain.DAY, 60 * 60_000L) == 3
                        && Heat.level(Heat.Grain.DAY, 2 * 60 * 60_000L) == 4
                        && Heat.level(Heat.Grain.DAY, 4 * 60 * 60_000L) == 5
                        && Heat.level(Heat.Grain.HOUR, 10 * 60_000L) == 2
                        && Heat.level(Heat.Grain.WEEK, 3 * 60 * 60_000L) == 3,
                "heat: 档位按粒度分（日 / 时 / 周各一套阈值）");

        long now = at(sh, 2026, 11, 19, 12, 0);   // 周四
        Sessions.Entry e1 = new Sessions.Entry(at(sh, 2026, 11, 18, 10, 0),
                at(sh, 2026, 11, 18, 12, 0), 7_200_000L, 7_200_000L, "", "d", "k", "", "");
        long from = at(sh, 2026, 11, 16, 0, 0);
        long to = at(sh, 2026, 11, 22, 23, 0) + 3_599_999L;
        List<Sessions.Entry> one = java.util.Arrays.asList(e1);
        Heat.Grid gd = Heat.grid(one, now, sh, Heat.Grain.DAY, from, to);
        fail += ok(gd.cols == 7 && gd.rows == 1
                        && !gd.draw[0] && !gd.draw[1] && gd.draw[2] && gd.draw[3]
                        && gd.ms[2] == 7_200_000L && gd.ms[3] == 0L
                        && Heat.total(gd) == 7_200_000L
                        && "2026-11-16".equals(gd.cellKey[0])
                        && "2026-11-18".equals(gd.cellKey[2]),
                "★heat 日档：周一对齐 / 起点前不画 / 起点后没玩也画 / 合计只算范围内");

        long fromHalf = at(sh, 2026, 11, 18, 11, 0);
        long toHalf = at(sh, 2026, 11, 19, 11, 0) - 1L;   // 11-19 10:59:59.999（范围 = 1 天）
        Heat.Grid gc = Heat.grid(one, now, sh, Heat.Grain.DAY, fromHalf, toHalf);
        fail += ok(!gc.tooSmall && Math.abs(gc.ms[2] - 3_600_000L) <= 1L
                        && Math.abs(Heat.total(gc) - 3_600_000L) <= 1L,
                "★heat: 范围边界按墙钟裁剪（2h 的一局只算范围内那 1h）");

        fail += ok(Heat.level(5 * 60_000L, new int[]{1, 2, 3, 4}) == 5
                        && Heat.level(30_000L, new int[]{1, 2, 3, 4}) == 1
                        && Heat.level(90_000L, new int[]{1, 2, 3, 4}) == 2,
                "heat: 自定义档位阈值按分钟生效（1/2/3/4 分钟）");

        boolean boundsOk = true;
        for (Heat.Grain g : Heat.Grain.values()) {
            int[] b = Heat.defaultBounds(g);
            if (b == null || b.length != 4 || b[3] > Heat.grainMaxMin(g)) boundsOk = false;
        }
        fail += ok(boundsOk, "heat: 默认档位都不超过一格（时档 1 小时不能配 120 分钟）");

        Heat.Grid gh2 = Heat.grid(one, now, sh, Heat.Grain.HOUR,
                at(sh, 2026, 11, 18, 10, 0), at(sh, 2026, 11, 18, 23, 0) - 1L);
        fail += ok(gh2.rows == 1 && "2026-11-18 00".equals(gh2.cellKey[0]),
                "heat 时档：从范围起点当天 0 点开始（1 天 = 1 行，不补周一空行）");

        Heat.Grid gm2 = Heat.grid(one, now, sh, Heat.Grain.MONTH,
                at(sh, 2026, 11, 13, 0, 0), at(sh, 2026, 11, 19, 23, 0) + 3_599_999L);
        fail += ok(gm2.tooSmall, "heat: 月档 + 7 天范围 ⇒ 拒绝（范围小于精度）");
        Heat.Grid gw2 = Heat.grid(one, now, sh, Heat.Grain.WEEK,
                at(sh, 2026, 11, 14, 0, 0), at(sh, 2026, 11, 19, 23, 0) + 3_599_999L);
        fail += ok(gw2.tooSmall, "heat: 周档 + 6 天范围 ⇒ 拒绝");
        Heat.Grid gd2 = Heat.grid(one, now, sh, Heat.Grain.DAY,
                at(sh, 2026, 11, 19, 0, 0), at(sh, 2026, 11, 19, 23, 0) + 3_599_999L);
        fail += ok(!gd2.tooSmall && gd2.rows == 1,
                "heat: 日档 + 1 天 ⇒ 不拒绝（正好一格）");

        Heat.Grid gw = Heat.grid(one, now, sh, Heat.Grain.WEEK,
                at(sh, 2026, 11, 10, 0, 0), at(sh, 2026, 11, 18, 23, 0) + 3_599_999L);
        fail += ok(gw.cols == 13 && gw.rows == 1 && "2026-09-28".equals(gw.cellKey[0]),
                "heat 周档：从季度首周对齐（10-01 所在周从 09-28 起）");

        Heat.Grid gm = Heat.grid(one, now, sh, Heat.Grain.MONTH,
                at(sh, 2026, 10, 1, 0, 0), at(sh, 2026, 11, 18, 23, 0) + 3_599_999L);
        fail += ok(gm.cols == 12 && gm.rows == 1 && "2026-01".equals(gm.cellKey[0])
                        && gm.ms[10] == 7_200_000L,
                "heat 月档：从 1 月对齐、11 月格 = 当月时长");

        Heat.Grid gh = Heat.grid(one, now, sh, Heat.Grain.HOUR,
                at(sh, 2025, 1, 1, 0, 0), at(sh, 2026, 12, 31, 23, 0) + 3_599_999L);
        fail += ok(gh.tooBig,
                "heat: 小时档 2 年 ⇒ 行数超 366（拒绝提示，不自动降档）");

        Heat.Grid ge = Heat.grid(Collections.<Sessions.Entry>emptyList(), now, sh,
                Heat.Grain.DAY, from, to);
        fail += ok(ge.firstDay.isEmpty() && !ge.draw[2] && Heat.total(ge) == 0L,
                "heat: 没有流水 ⇒ 不画、合计 0");

        System.out.println(fail == 0 ? "== USAGESTATS CHECK OK ==" : "!! " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static long at(TimeZone tz, int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(tz);
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int ok(boolean cond, String what) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        return cond ? 0 : 1;
    }

    private Check() {}
}
