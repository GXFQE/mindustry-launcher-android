package io.mdt.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * `UpdateManifest` / `FlatJson` 的判据（**纯逻辑，不许 import `android.*`**）。
 *
 * <p>为什么单独一个类：同一批断言要**在两处跑**，而两处都不许各写一份
 * （"同一段判据写两处必漂" —— `ref/43` 第 21 条）：
 * <ul>
 *   <li>**PC 侧**：`javac` + `java` 直接跑，不装设备、不开模拟器
 *       （`PcCheck` 是那侧的 runner；本文件之所以能这么跑，就是因为它不依赖 android）；</li>
 *   <li>**设备侧**：`SelfTest` 调 {@link #run()}，把结果并进那份总报告。</li>
 * </ul>
 *
 * <p>⚠️ 本文件**产品版会按文件名排除**（与 `SelfTest.java` 同一条规则，见 `build.sh` 的
 * `SELFTEST_ARGS`）—— 它只服务自检，不进用户手里的包。
 */
final class UpdateManifestCheck {

    private UpdateManifestCheck() {}

    /** 一条断言的结果（给人看的那句话也要留着 —— 报告里要能读出"验的是什么"）。 */
    static final class Line {
        final boolean ok;
        final String msg;
        Line(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }

    /** 跑完全部断言；{@code lines} 保持书写顺序，方便对照。 */
    static List<Line> run() {
        List<Line> out = new ArrayList<Line>();
        parseOk(out);
        parseBad(out);
        urlAllowed(out);
        compare(out);
        gate(out);
        flatJson(out);
        time(out);
        return out;
    }

    private static void ok(List<Line> L, boolean cond, String msg) {
        L.add(new Line(cond, msg));
    }

    private static final String SHA =
            "527341d6ffe5fe3a5bda3dee629b89dd7b6b8f6f3b841ab903ee067e530ad0a8";

    private static String good(int code) {
        return "{\"schema\":1,\"versionCode\":" + code + ",\"versionName\":\"0.8.0-preview\","
                + "\"size\":826655,\"sha256\":\"" + SHA + "\",\"publishedAt\":\"2026-11-02\","
                + "\"notes\":\"修了三个崩溃\"}";
    }

    private static UpdateManifest p(String json) {
        return UpdateManifest.parse(json);
    }

    // ── 正常 ──────────────────────────────────────────────────────────────
    private static void parseOk(List<Line> L) {
        UpdateManifest m = p(good(8));
        ok(L, m.ok(), "完整清单应当可用（error=" + m.error + "）");
        ok(L, m.versionCode == 8, "versionCode 应=8，实际 " + m.versionCode);
        ok(L, "0.8.0-preview".equals(m.versionName), "versionName 应保留");
        ok(L, m.size == 826655, "size 应=826655，实际 " + m.size);
        ok(L, SHA.equals(m.sha256), "sha256 应保留");
        ok(L, "修了三个崩溃".equals(m.notes), "notes 应保留");

        UpdateManifest legacy = p("{\"versionCode\":9,\"sha256\":\"" + SHA + "\"}");
        ok(L, legacy.ok() && legacy.versionCode == 9, "缺 schema 应当按 1 收下（老格式兼容）");

        UpdateManifest noSize = p("{\"versionCode\":9,\"sha256\":\"" + SHA + "\"}");
        ok(L, noSize.ok() && noSize.size == 0, "缺 size 应当可用，且 size=0");

        UpdateManifest upper = p("{\"versionCode\":9,\"sha256\":\"" + SHA.toUpperCase(java.util.Locale.US) + "\"}");
        ok(L, upper.ok() && SHA.equals(upper.sha256), "大写 sha256 应被正常化成小写");

        UpdateManifest bare = p("{\"versionCode\":8,\"sha256\":\"" + SHA + "\",\"size\":123}");
        ok(L, bare.ok() && bare.size == 123, "裸值（数字不加引号）应能解析");

        UpdateManifest extra = p("{\"versionCode\":8,\"sha256\":\"" + SHA + "\",\"future\":\"x\"}");
        ok(L, extra.ok(), "不认识的键不该影响解析");

        UpdateManifest withUrl = p("{\"versionCode\":9,\"sha256\":\"" + SHA
                + "\",\"url\":\"https://github.com/x/y.apk\"}");
        ok(L, withUrl.ok() && "https://github.com/x/y.apk".equals(withUrl.url),
                "url 应原样保留（下载地址由清单给）");
    }

    // ── 下载地址的准入 ────────────────────────────────────────────────────
    private static void urlAllowed(List<Line> L) {
        ok(L, UpdateManifest.allowedUrl("https://github.com/x/y.apk"), "https 应放行");
        ok(L, !UpdateManifest.allowedUrl("http://evil.example/x.apk"),
                "http 明文必须拒（否则链路上谁都能把包换掉）");
        ok(L, UpdateManifest.allowedUrl("http://127.0.0.1:8080/x.apk"), "回环明文放行（本地夹具）");
        ok(L, UpdateManifest.allowedUrl("http://localhost:8080/x.apk"), "localhost 明文放行");
        ok(L, !UpdateManifest.allowedUrl(null), "null 应拒");
        ok(L, !UpdateManifest.allowedUrl(""), "空串应拒");
        ok(L, !UpdateManifest.allowedUrl("ftp://x/y"), "非 http(s) 应拒");
        ok(L, !UpdateManifest.allowedUrl("http://127.0.0.1.evil.com/x"),
                "别被前缀骗了：127.0.0.1.evil.com 不是回环");
    }

    // ── 坏数据：一律不许抛异常，只许给原因码 ─────────────────────────────
    private static void parseBad(List<Line> L) {
        ok(L, UpdateManifest.E_EMPTY.equals(p(null).error), "null ⇒ empty");
        ok(L, UpdateManifest.E_EMPTY.equals(p("   ").error), "空白 ⇒ empty");
        ok(L, UpdateManifest.E_NOT_JSON.equals(p("不是 json").error), "非对象 ⇒ not-json");
        ok(L, UpdateManifest.E_NOT_JSON.equals(p("{}").error), "空对象 ⇒ not-json");
        ok(L, UpdateManifest.E_TOO_BIG.equals(p("{" + repeat("x", 70000) + "}").error), "超长 ⇒ too-big");

        ok(L, UpdateManifest.E_SCHEMA.equals(
                p("{\"schema\":2,\"versionCode\":8,\"sha256\":\"" + SHA + "\"}").error), "schema=2 ⇒ 拒绝");
        ok(L, UpdateManifest.E_SCHEMA.equals(
                p("{\"schema\":\"x\",\"versionCode\":8,\"sha256\":\"" + SHA + "\"}").error), "schema 非数字 ⇒ 拒绝");

        ok(L, UpdateManifest.E_VERSION.equals(p("{\"sha256\":\"" + SHA + "\"}").error), "缺 versionCode ⇒ 拒绝");
        ok(L, UpdateManifest.E_VERSION.equals(
                p("{\"versionCode\":\"abc\",\"sha256\":\"" + SHA + "\"}").error), "versionCode 非数字 ⇒ 拒绝");
        ok(L, UpdateManifest.E_VERSION.equals(
                p("{\"versionCode\":0,\"sha256\":\"" + SHA + "\"}").error), "versionCode=0 ⇒ 拒绝");
        ok(L, UpdateManifest.E_VERSION.equals(
                p("{\"versionCode\":-1,\"sha256\":\"" + SHA + "\"}").error), "versionCode 负数 ⇒ 拒绝");

        ok(L, UpdateManifest.E_SHA.equals(p("{\"versionCode\":8}").error), "缺 sha256 ⇒ 拒绝");
        ok(L, UpdateManifest.E_SHA.equals(
                p("{\"versionCode\":8,\"sha256\":\"abc\"}").error), "sha256 过短 ⇒ 拒绝");
        ok(L, UpdateManifest.E_SHA.equals(
                p("{\"versionCode\":8,\"sha256\":\"" + repeat("z", 64) + "\"}").error), "sha256 非 hex ⇒ 拒绝");
        ok(L, UpdateManifest.E_SHA.equals(
                p("{\"versionCode\":8,\"sha256\":\"" + SHA.substring(1) + "\"}").error), "sha256 少一位 ⇒ 拒绝");
    }

    // ── 比较 ──────────────────────────────────────────────────────────────
    private static void compare(List<Line> L) {
        ok(L, UpdateManifest.isNewer(7, 8), "8 > 7 ⇒ 新版本");
        ok(L, !UpdateManifest.isNewer(8, 8), "相等 ⇒ 不算新");
        ok(L, !UpdateManifest.isNewer(9, 8), "远端更旧 ⇒ 不算新");
        ok(L, !UpdateManifest.isNewer(7, 0), "远端 0 ⇒ 不算新（坏数据不许触发更新）");
        ok(L, !UpdateManifest.isNewer(7, -5), "远端负数 ⇒ 不算新");
        ok(L, UpdateManifest.isNewer(0, 1), "本机 0 时任何正数都算新");
    }

    // ── 上屏闸门（notes / versionName 来自网络，不可信） ──────────────────
    private static void gate(List<Line> L) {
        ok(L, "a b".equals(UpdateManifest.gate("a\n\nb", 80)), "换行 / 连续空白应压成一个空格");
        ok(L, "a b".equals(UpdateManifest.gate("  a   b  ", 80)), "首尾空白应去掉");
        ok(L, UpdateManifest.gate(null, 80) == null, "null 应保持 null（调用方据此决定整行不显示）");
        ok(L, "".equals(UpdateManifest.gate("", 80)), "空串应保持空串");

        String emoji = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00";   // 😀 x3
        String cut = UpdateManifest.gate(emoji, 2);
        ok(L, cut.codePointCount(0, cut.length()) == 2, "emoji 必须按码点截断（切半个代理对会出乱码）");

        ok(L, UpdateManifest.gate(repeat("x", 500), UpdateManifest.NOTES_MAX).length()
                == UpdateManifest.NOTES_MAX, "长文本应截到 NOTES_MAX");

        ok(L, !UpdateManifest.gate("a\u001b[31mb", 80).contains("\u001b"), "控制字符（ANSI 转义）应被丢掉");
    }

    // ── FlatJson 自己的语义（搬家后仍要钉住） ────────────────────────────
    private static void flatJson(List<Line> L) {
        Map<String, String> m = FlatJson.parse("{\"a\":{\"b\":\"c\"},\"entry\":\"d.E\"}");
        ok(L, m != null && "d.E".equals(m.get("entry")), "嵌套之后那个键不能串味");
        ok(L, m != null && !m.containsKey("a"), "嵌套键（深度 1 上的容器）应被丢掉");
        ok(L, FlatJson.parse("不是 json") == null, "非对象 ⇒ null");
        ok(L, FlatJson.parse(null) == null, "null ⇒ null");
        ok(L, FlatJson.parse("{\"a\":\"没闭合") != null, "引号没闭合时不许抛异常，应返回非 null");
        Map<String, String> t = FlatJson.parse("{\"x\":true,\"n\":3}");
        ok(L, "true".equals(t.get("x")) && "3".equals(t.get("n")), "裸值应原样收下");
    }

    // ── 静默检查去重（靠时间戳省事的闸门最容易变砖） ────────────────────
    private static void time(List<Line> L) {
        long day = 24L * 3600L;
        ok(L, UpdateManifest.shouldCheck(1000, 0, day), "从没查过 ⇒ 该查");
        ok(L, !UpdateManifest.shouldCheck(1000 + 3600, 1000, day), "1 小时内 ⇒ 不查");
        ok(L, UpdateManifest.shouldCheck(1000 + day, 1000, day), "满 24h ⇒ 查");
        ok(L, UpdateManifest.shouldCheck(500, 1000, day), "时钟回拨 ⇒ 该查（不能卡死）");
        ok(L, UpdateManifest.shouldCheck(1000, 1000, 0), "interval=0 ⇒ 每次都查");
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
