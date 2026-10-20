package io.mdt.plugins.mapstats.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件侧自检：**纯 Java，PC 上跑**（`plugins/mapstats/build.sh check`）。
 *
 * <p>为什么在这里：F21 的解析/统计/导出已经从宿主搬进插件（第 188~190 轮），宿主那份
 * 自检测的是宿主的拷贝。插件这一份**必须有自己的尺子** —— 否则改插件里的核心类时，
 * 宿主的自检全绿而插件已经坏了。</p>
 *
 * <p>覆盖面：内容表、统计口径（矿 / 墙矿 / 地板 / 加成 / 可建造 / 核心区 + 星球）、
 * 导出（单张 JSON 文档 / 多张 JSONL / 简洁模式 / 声明瘦身）、模组内容叠加、译名兜底。
 * ⚠️ 不含：真机才有的东西（`Context`、SAF、版本 APK 的 bundle）—— 那些由宿主自检与真机验收管。</p>
 */
public final class Check {

    private static int pass;
    private static final List<String> fails = new ArrayList<>();

    private static void ok(boolean cond, String msg) {
        if (cond) {
            pass++;
        } else {
            fails.add(msg);
        }
    }

    public static void main(String[] args) {
        vanilla();
        coreZone();
        exportDoc();
        exportBatch();
        overlay();
        names();
        System.out.println("== mapstats check: " + pass + " passed, " + fails.size() + " failed ==");
        for (String f : fails) System.out.println("  FAIL  " + f);
        if (!fails.isEmpty()) System.exit(1);
    }

    // ---- ① 原版内容表 ------------------------------------------------------------

    private static void vanilla() {
        MapStats.Table v = MapStats.vanilla();
        ok(v.rowCount() == 447, "原版内容表 447 条（实际 " + v.rowCount() + "）");
        MapStats.Def stoneWall = v.row("stone-wall");
        MapStats.Def boulder = v.row("boulder");
        MapStats.Def copperWall = v.row("copper-wall");
        MapStats.Def sand = v.row("sand-floor");
        ok(stoneWall != null && !stoneWall.removable() && boulder != null && boulder.removable()
                        && copperWall != null && copperWall.removable(),
                "遮挡口径来自游戏字段：stone-wall 拆不掉 / boulder 能搬走 / copper-wall 是玩家墙能拆");
        ok(sand != null && "sand".equals(sand.itemDrop) && sand.isFloor && !sand.isOre
                        && sand.bonus(),
                "沙地：掉落 sand ⇒ 可采地板；带属性 ⇒ 也算加成地板（一格两用）");
    }

    // ---- ② 核心区 + 星球 ----------------------------------------------------------

    private static void coreZone() {
        MapStats.Table v = MapStats.vanilla();
        MapStats.Names nm = fakeNames();
        MapStats.Result cr = MapStats.analyze(coreZoneGrid(), v, nm);
        ok(cr.core.zone == 36 && cr.core.usable == 36 && cr.core.max == 4,
                "6×6 核心区（36 格全能建）⇒ 最多 4 个 3×3（实际 " + cr.core.zone + "/"
                        + cr.core.usable + "/" + cr.core.max + "）");
        MsavTiles.Tiles blocked = coreZoneGrid();
        blocked.blocks[2 * 12 + 2] = 3;                      // (2,2) = stone-wall
        MapStats.Result cr2 = MapStats.analyze(blocked, v, nm);
        ok(cr2.core.usable == 35 && cr2.core.max == 3,
                "核心区里摆一个 stone-wall ⇒ 能建 35、最多 3 个（实际 " + cr2.core.usable
                        + "/" + cr2.core.max + "）");
        MapStats.Result crO = MapStats.analyze(coreZoneGrid(), v, nm, "erekir");
        ok(crO.core.size == 4 && crO.core.max == 1
                        && crO.core.basis == MapStats.Core.BASIS_PLANET,
                "planet:erekir ⇒ 边长 4、6×6 里只摆得下 1 个（实际 " + crO.core.size + "/"
                        + crO.core.max + "/" + crO.core.basis + "）");
        MapStats.Result crS = MapStats.analyze(coreZoneGrid(), v, nm, "serpulo");
        ok(crS.core.size == 3 && crS.core.max == 4
                        && crS.core.basis == MapStats.Core.BASIS_PLANET,
                "planet:serpulo ⇒ 边长 3、最多 4 个");
    }

    // ---- ③ 导出：单张 = 一份 JSON 文档 ---------------------------------------------

    private static void exportDoc() {
        MapStats.Table v = MapStats.vanilla();
        MapStats.Names nm = fakeNames();
        MapStats.Result xr = MapStats.analyze(coreZoneGrid(), v, nm, "serpulo");
        MapStatsExport.Meta xm = statsMeta("core-zone-6x6.msav", "0123456789abcdef",
                MapStatsExport.MODE_DETAIL);
        String xdoc = MapStatsExport.jsonDoc(xr, xm);
        Map<String, Object> xd = MapStats.jsonOf(xdoc);
        Object xrec = xd == null ? null : xd.get("records");
        int n = xrec instanceof List ? ((List<?>) xrec).size() : -1;
        ok(xd != null && MapStatsExport.FORMAT.equals(String.valueOf(xd.get("format")))
                        && MapStatsExport.GENERATOR.equals(String.valueOf(xd.get("generator")))
                        && MapStatsExport.SITE.equals(String.valueOf(xd.get("site")))
                        && String.valueOf(xd.get("notice")).contains("https://")
                        && xrec instanceof List && n > 0,
                "单张导出 = 一份 JSON 文档（format/generator/site/notice + records " + n + " 条）");
        ok(keyCount(xdoc, "format") == 1 && keyCount(xdoc, "generator") == 1
                        && keyCount(xdoc, "site") == 1 && keyCount(xdoc, "notice") == 1
                        && keyCount(xdoc, "meta") == 0 && keyCount(xdoc, "recordCount") == 0,
                "每个事实只写一次（format/generator/site/notice 各 1 处；无 meta/recordCount）");
        Map<?, ?> xmap = xd == null || !(xd.get("map") instanceof Map)
                ? null : (Map<?, ?>) xd.get("map");
        Map<?, ?> xlau = xd == null || !(xd.get("launcher") instanceof Map)
                ? null : (Map<?, ?>) xd.get("launcher");
        ok(xmap != null && xlau != null
                        && "core-zone-6x6.msav".equals(String.valueOf(xmap.get("name")))
                        && "0123456789abcdef".equals(String.valueOf(xmap.get("sha256")))
                        && "serpulo".equals(String.valueOf(xmap.get("planet")))
                        && intOf(xmap, "mods") == 2
                        && "0.5-preview".equals(String.valueOf(xlau.get("version")))
                        && intOf(xlau, "code") == 5
                        && xd.get("mode") == null && xd.get("index") == null
                        && xd.get("count") == null && xd.get("content") == null
                        && xd.get("engine") == null
                        && xmap.get("where") == null && xmap.get("bytes") == null
                        && xmap.get("cells") == null && xlau.get("package") == null,
                "声明瘦身：只剩版本·vc / 图名·sha256 / 星球 / 模组数（mode/index/count/包名/字节都不写）");
        Set<String> known = new HashSet<String>(Arrays.asList(
                "ore", "oreWall", "floor", "bonus", "buildable", "core", "unknownContent"));
        boolean all = xrec instanceof List && !((List<?>) xrec).isEmpty();
        Map<?, ?> build = null;
        Map<?, ?> core = null;
        for (Object oo : xrec instanceof List ? (List<?>) xrec : new ArrayList<Object>()) {
            if (!(oo instanceof Map)) {
                all = false;
                break;
            }
            Map<?, ?> o = (Map<?, ?>) oo;
            String k = String.valueOf(o.get("kind"));
            if (!known.contains(k)) {
                all = false;
                break;
            }
            if ("buildable".equals(k)) build = o;
            if ("core".equals(k)) core = o;
        }
        ok(all && build != null && core != null
                        && intOf(build, "sum") == xr.cells
                        && intOf(build, "free") + intOf(build, "cleared") + intOf(build, "walled")
                        + intOf(build, "deep") + intOf(build, "floorBlocked")
                        + intOf(build, "unknown") == xr.cells
                        && intOf(core, "zone") == xr.core.zone
                        && intOf(core, "usable") == xr.core.usable
                        && intOf(core, "max") == xr.core.max
                        && intOf(core, "size") == xr.core.size
                        && "planet".equals(String.valueOf(core.get("basis"))),
                "records 的 kind 都认得；可建造六桶相加 == 总格数（" + xr.cells
                        + "）；核心区与 Result 同源");

        // 简洁模式：整行只有 map + records；不许出现 total、不许出现 reachable==0
        MapStats.Result br = new MapStats.Result();
        br.ok = true;
        br.width = 12;
        br.height = 8;
        br.cells = 96;
        MapStats.Row row = new MapStats.Row();
        row.label = "可采的那种";
        row.total = 10;
        row.open = 4;
        br.ores.add(row);
        MapStats.Row zero = new MapStats.Row();
        zero.label = "采不到的那种";
        zero.total = 7;                                     // 埋着但拿不到
        br.ores.add(zero);
        br.buildable.free = 60;
        br.core.zone = 9;
        br.core.usable = 9;
        br.core.max = 1;
        String bdoc = MapStatsExport.jsonDoc(br,
                statsMeta("b.msav", "bb", MapStatsExport.MODE_BRIEF));
        Map<String, Object> bd = MapStats.jsonOf(bdoc);
        ok(bd != null && bd.size() == 9 && bd.get("map") instanceof Map
                        && bd.get("records") instanceof List
                        && bd.get("launcher") instanceof Map && bd.get("exportedAt") != null
                        && bd.get("mode") == null && keyCount(bdoc, "total") == 0,
                "简洁模式 = 瘦身声明（9 个键）+ records，且不写 total");
        ok(!bdoc.contains("采不到的那种"),
                "简洁模式：reachable==0 的行不写（「采不到的那种」不出现）");
        // 反方向：有真数（可建造 / 核心区）⇒ brief 里那两条必须在，且数与 Result 同源
        Map<String, Object> brd = MapStats.jsonOf(MapStatsExport.jsonDoc(xr,
                statsMeta("d.msav", "dd", MapStatsExport.MODE_BRIEF)));
        List<?> brs = brd != null && brd.get("records") instanceof List
                ? (List<?>) brd.get("records") : null;
        int bSum = Integer.MIN_VALUE, cZone = Integer.MIN_VALUE, cMax = Integer.MIN_VALUE;
        boolean bTotal = false;
        for (Object oo : brs == null ? new ArrayList<Object>() : brs) {
            Map<?, ?> o = (Map<?, ?>) oo;
            if (o.get("total") != null) bTotal = true;
            String k = String.valueOf(o.get("kind"));
            if ("buildable".equals(k)) bSum = intOf(o, "sum");
            if ("core".equals(k)) {
                cZone = intOf(o, "zone");
                cMax = intOf(o, "max");
            }
        }
        ok(!bTotal && bSum == xr.cells && cZone == xr.core.zone && cMax == xr.core.max,
                "简洁模式的反方向：可建造 sum == 总格数、核心区 zone/max 与 Result 同源");
    }

    // ---- ④ 导出：多张 = JSONL（一行一张图） -----------------------------------------

    private static void exportBatch() {
        MapStats.Table v = MapStats.vanilla();
        MapStats.Names nm = fakeNames();
        MapStats.Result a = MapStats.analyze(coreZoneGrid(), v, nm, "serpulo");
        MapStats.Result b = MapStats.analyze(coreZoneGrid(), v, nm, "erekir");
        MapStatsExport.Meta ma = statsMeta("a.msav", "aa", MapStatsExport.MODE_DETAIL);
        MapStatsExport.Meta mb = statsMeta("b.msav", "bb", MapStatsExport.MODE_DETAIL);
        String batch = MapStatsExport.jsonlBatch(Arrays.asList(a, b), Arrays.asList(ma, mb));
        String[] lines = batch.split("\n");
        ok(lines.length == 2, "两张地图 ⇒ JSONL 恰好 2 行（实际 " + lines.length + "）");
        if (lines.length == 2) {
            Map<String, Object> d0 = MapStats.jsonOf(lines[0]);
            Map<?, ?> m0 = d0 != null && d0.get("map") instanceof Map
                    ? (Map<?, ?>) d0.get("map") : null;
            ok(m0 != null && "a.msav".equals(String.valueOf(m0.get("name"))),
                    "第一行是第一张图（a.msav）");
            ok(lines[1].contains("\"b.msav\""), "第二行是第二张图（b.msav）");
        }
    }

    // ---- ⑤ 模组内容叠加 -----------------------------------------------------------

    private static void overlay() {
        MapStats.Table t = MapStats.copyOf(MapStats.vanilla());
        MapStats.Pack p = new MapStats.Pack();
        p.prefix = "x";
        MapStats.ModResult mr = new MapStats.ModResult();
        MapStats.applyModContent(t, p, "content/blocks/env/deep-tar.json",
                "{\n  type: Floor\n  drownTime: 100\n}", mr);
        MapStats.applyModContent(t, p, "content/blocks/env/void.json",
                "{\n  type: Floor\n  placeableOn: false\n}", mr);
        MapStats.Def dtar = t.row("x-deep-tar");
        MapStats.Def dvoid = t.row("x-void");
        ok(dtar != null && dtar.deep && dvoid != null && !dvoid.placeableOn,
                "模组 JSON：drownTime>0 ⇒ 深液体；placeableOn:false ⇒ 不让放");
    }

    // ---- ⑥ 译名兜底 ---------------------------------------------------------------

    private static void names() {
        ok("Dark Metal".equals(MapStats.pretty("dark-metal")), "内部名兜底 pretty()");
    }

    // ---- 夹具 --------------------------------------------------------------------

    /** 12×8：地板石头，中间 6×6 是核心区（`blockNames` = air / stone / core-zone / stone-wall） */
    private static MsavTiles.Tiles coreZoneGrid() {
        MsavTiles.Tiles t = new MsavTiles.Tiles();
        t.width = 12;
        t.height = 8;
        for (String s : new String[]{"air", "stone", "core-zone", "stone-wall"}) {
            t.blockNames.add(s);
        }
        int n = 12 * 8;
        t.floors = new short[n];
        t.ores = new short[n];
        t.blocks = new short[n];
        for (int i = 0; i < n; i++) t.floors[i] = 1;
        for (int y = 1; y <= 6; y++) {
            for (int x = 1; x <= 6; x++) t.floors[y * 12 + x] = 2;
        }
        return t;
    }

    /** 假译名（真译名要读版本 APK）。 */
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

            @Override public String liquid(String i) {
                if ("water".equals(i)) return "水";
                if ("oil".equals(i)) return "石油";
                return "";
            }
        };
    }

    private static MapStatsExport.Meta statsMeta(String name, String sha, int mode) {
        MapStatsExport.Meta m = new MapStatsExport.Meta();
        m.exportedAt = "2026-10-17T16:20:00+08:00";
        m.launcherVersion = "0.5-preview";
        m.launcherCode = 5;
        m.launcherPackage = "io.mdt.launcher";
        m.slot = "test";
        m.mapName = name;
        m.mapSource = "slot";
        m.mapWhere = name;
        m.mapBytes = 12345;
        m.mapSha256 = sha;
        m.planet = "serpulo";
        m.gameApk = "Mindustry-160.apk";
        m.gameVersion = "160.7";
        m.tableRows = 447;
        m.mods = 2;
        m.notice = "由 MDT 管理器导出 · " + MapStatsExport.SITE;
        m.mode = mode;
        return m;
    }

    /** JSON 里某个键出现几次（钉"每个事实只写一次"）。 */
    private static int keyCount(String json, String key) {
        if (json == null) return -1;
        String needle = "\"" + key + "\"";
        int c = 0, i = 0;
        while ((i = json.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private static int intOf(Map<?, ?> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return (int) Math.round(((Number) v).doubleValue());
        if (v == null) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private Check() {
    }
}
