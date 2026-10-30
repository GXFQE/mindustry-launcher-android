package io.mdt.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * `PluginUpdateManifest` / `PluginUpdateState` 的判据（**纯逻辑，不许 import `android.*`**）。
 *
 * <p>与 {@code UpdateManifestCheck} 同一个形状：同一批断言在**两处**跑，
 * 但只有一份实现 —— PC 侧由 `PcCheck` 直接打印，设备侧 {@code SelfTest} 并进总报告。
 *
 * <p>⚠️ 本文件产品版会按文件名排除（`build.sh` 的 `SELFTEST_ARGS`），它只服务自检。
 */
final class PluginUpdateCheck {

    private PluginUpdateCheck() {}

    /** 一条断言的结果（"验的是什么"要留在消息里）。 */
    static final class Line {
        final boolean ok;
        final String msg;
        Line(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
    }

    static List<Line> run() {
        List<Line> out = new ArrayList<Line>();
        parseOk(out);
        parseBad(out);
        url(out);
        notes(out);
        hostApi(out);
        pluginId(out);
        compare(out);
        state(out);
        return out;
    }

    private static void ok(List<Line> L, boolean cond, String msg) {
        L.add(new Line(cond, msg));
    }

    private static final String SHA =
            "527341d6ffe5fe3a5bda3dee629b89dd7b6b8f6f3b841ab903ee067e530ad0a8";
    private static final String SHA2 =
            "00000000000000000000000000000000000000000000000000000000000000ff";

    private static String good() {
        return "{\"schema\":1,\"sha256\":\"" + SHA + "\",\"size\":826655,"
                + "\"url\":\"https://github.com/GXFQE/x/releases/download/plugins-latest/a.apk\","
                + "\"version\":\"1.2\",\"notes\":\"修了三个问题\",\"plugin\":\"a\"}";
    }

    private static PluginUpdateManifest p(String json) {
        return PluginUpdateManifest.parse(json);
    }

    // ── 正常 ──────────────────────────────────────────────────────────────

    private static void parseOk(List<Line> L) {
        PluginUpdateManifest m = p(good());
        ok(L, m.ok(), "完整清单应当可用（error=" + m.error + "）");
        ok(L, SHA.equals(m.sha256), "sha256 应保留");
        ok(L, m.size == 826655, "size 应=826655，实际 " + m.size);
        ok(L, "1.2".equals(m.version), "version 应保留（只用于显示）");
        ok(L, "a".equals(m.plugin), "plugin 应保留");

        PluginUpdateManifest legacy = p("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\"}");
        ok(L, legacy.ok(), "缺 schema 应当按 1 收下（老格式兼容）");

        PluginUpdateManifest noSize = p("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\"}");
        ok(L, noSize.ok() && noSize.size == 0, "缺 size 应当可用，且 size=0");

        PluginUpdateManifest upper = p(
                "{\"sha256\":\"" + SHA.toUpperCase(java.util.Locale.US)
                        + "\",\"url\":\"https://x/a.apk\"}");
        ok(L, upper.ok() && SHA.equals(upper.sha256), "大写 sha256 应被正常化成小写");
    }

    // ── 坏数据 ────────────────────────────────────────────────────────────

    private static void parseBad(List<Line> L) {
        ok(L, PluginUpdateManifest.E_EMPTY.equals(p("").error), "空文件 ⇒ empty");
        ok(L, PluginUpdateManifest.E_EMPTY.equals(p(null).error), "null ⇒ empty");
        ok(L, PluginUpdateManifest.E_NOT_JSON.equals(p("hello").error), "不是 JSON ⇒ not-json");
        ok(L, PluginUpdateManifest.E_SCHEMA.equals(
                        p("{\"schema\":2,\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\"}").error),
                "schema=2 ⇒ schema-unsupported");
        ok(L, PluginUpdateManifest.E_SCHEMA.equals(
                        p("{\"schema\":\"abc\",\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\"}").error),
                "schema 不是整数 ⇒ schema-unsupported");
        ok(L, PluginUpdateManifest.E_SHA.equals(
                        p("{\"url\":\"https://x/a.apk\"}").error),
                "缺 sha256 ⇒ bad-sha256（宁可这一次不更新，也不装来历不明的包）");
        ok(L, PluginUpdateManifest.E_SHA.equals(
                        p("{\"sha256\":\"abc\",\"url\":\"https://x/a.apk\"}").error),
                "短 sha256 ⇒ bad-sha256");
        ok(L, PluginUpdateManifest.E_URL.equals(
                        p("{\"sha256\":\"" + SHA + "\",\"url\":\"http://evil.example/a.apk\"}").error),
                "明文 http（非回环）⇒ bad-url");
        ok(L, PluginUpdateManifest.E_URL.equals(
                        p("{\"sha256\":\"" + SHA + "\"}").error),
                "缺 url ⇒ bad-url（不猜默认地址）");

        StringBuilder big = new StringBuilder("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\",\"notes\":\"");
        for (int i = 0; i < PluginUpdateManifest.MAX_BYTES; i++) big.append('x');
        big.append("\"}");
        ok(L, PluginUpdateManifest.E_TOO_BIG.equals(p(big.toString()).error),
                "超过 " + PluginUpdateManifest.MAX_BYTES + " B ⇒ too-big");
    }

    // ── 地址闸门 ──────────────────────────────────────────────────────────

    private static void url(List<Line> L) {
        ok(L, PluginUpdateManifest.allowedUrl("https://a/b.apk"), "https 允许");
        ok(L, !PluginUpdateManifest.allowedUrl("http://a/b.apk"), "明文 http 拒绝");
        ok(L, !PluginUpdateManifest.allowedUrl("ftp://a/b.apk"), "ftp 拒绝");
        ok(L, !PluginUpdateManifest.allowedUrl(""), "空地址拒绝");
        ok(L, !PluginUpdateManifest.allowedUrl(null), "null 拒绝");
        ok(L, PluginUpdateManifest.allowedUrl("http://127.0.0.1:8080/a.apk"),
                "回环（本地夹具）允许");
        ok(L, PluginUpdateManifest.allowedUrl("http://localhost:8080/a.apk"),
                "localhost（本地夹具）允许");
    }

    // ── 上屏闸门 ──────────────────────────────────────────────────────────

    private static void notes(List<Line> L) {
        PluginUpdateManifest m = p("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\","
                + "\"notes\":\"第一行\\n第二行\\t带制表符\"}");
        ok(L, m.ok() && "第一行 第二行 带制表符".equals(m.notes),
                "notes 的换行 / 制表符应压成空格，实际 " + (m.ok() ? m.notes : m.error));

        StringBuilder longNotes = new StringBuilder();
        for (int i = 0; i < 400; i++) longNotes.append('x');
        PluginUpdateManifest t = p("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\","
                + "\"notes\":\"" + longNotes + "\"}");
        ok(L, t.ok() && t.notes.length() == PluginUpdateManifest.NOTES_MAX,
                "超长 notes 应截到 " + PluginUpdateManifest.NOTES_MAX + "，实际 "
                        + (t.notes == null ? -1 : t.notes.length()));

        StringBuilder longVer = new StringBuilder();
        for (int i = 0; i < 400; i++) longVer.append('v');
        PluginUpdateManifest v = p("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\","
                + "\"version\":\"" + longVer + "\"}");
        ok(L, v.ok() && v.version.length() == PluginUpdateManifest.VERSION_MAX,
                "超长 version 应截到 " + PluginUpdateManifest.VERSION_MAX);
    }

    // ── 宿主接口版本 ──────────────────────────────────────────────────────

    private static void hostApi(List<Line> L) {
        String json = "{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\",\"minHostApi\":40}";
        ok(L, PluginUpdateManifest.E_HOST_OLD.equals(PluginUpdateManifest.parse(json, 36).error),
                "minHostApi=40 而宿主 36 ⇒ host-too-old（装了也用不了，别提示）");
        ok(L, PluginUpdateManifest.parse(json, 40).ok(), "minHostApi=40 而宿主 40 ⇒ 可用");
        ok(L, PluginUpdateManifest.parse(json, 41).ok(), "minHostApi=40 而宿主 41 ⇒ 可用");
        ok(L, PluginUpdateManifest.parse(json).ok(), "不判宿主（hostApi<=0）⇒ 可用");

        String bad = "{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\",\"minHostApi\":\"abc\"}";
        PluginUpdateManifest m = PluginUpdateManifest.parse(bad, 36);
        ok(L, m.ok() && m.minHostApi == 0, "minHostApi 写坏了 ⇒ 当没声明（不让整份清单作废）");
    }

    // ── 清单自称的插件 id ────────────────────────────────────────────────

    private static void pluginId(List<Line> L) {
        PluginUpdateManifest m = p(good());
        ok(L, m.matches("a"), "plugin=a 对 a ⇒ 认");
        ok(L, !m.matches("b"), "plugin=a 对 b ⇒ 不认（防串包）");
        PluginUpdateManifest none = p("{\"sha256\":\"" + SHA + "\",\"url\":\"https://x/a.apk\"}");
        ok(L, none.matches("whatever"), "没声明 plugin ⇒ 不拦");
    }

    // ── 内容哈希比对（"没有版本号"的那条核心判据）────────────────────────

    private static void compare(List<Line> L) {
        ok(L, !PluginUpdateManifest.differs(SHA, SHA), "哈希相同 ⇒ 没有更新");
        ok(L, !PluginUpdateManifest.differs(SHA, SHA.toUpperCase(java.util.Locale.US)),
                "哈希只差大小写 ⇒ 也是同一份（大小写不敏感）");
        ok(L, PluginUpdateManifest.differs(SHA, SHA2), "哈希不同 ⇒ 有更新");
        ok(L, !PluginUpdateManifest.differs(null, SHA2), "本机哈希读不出来 ⇒ 不算有更新（不动文件）");
        ok(L, !PluginUpdateManifest.differs(SHA, null), "远端哈希缺失 ⇒ 不算有更新");
        ok(L, "527341d6".equals(PluginUpdateManifest.sha8(SHA)), "sha8 取前 8 位");
        ok(L, PluginUpdateManifest.sha8("abc") == null, "不足 8 位 ⇒ null");
        ok(L, PluginUpdateManifest.sha8(null) == null, "null ⇒ null");
    }

    // ── 结果表 ────────────────────────────────────────────────────────────

    private static void state(List<Line> L) {
        String t = PluginUpdateState.put("", "a", PluginUpdateState.OK, SHA, "1.2", 100L, "");
        PluginUpdateState.Item a = PluginUpdateState.get(t, "a");
        ok(L, a != null && PluginUpdateState.OK.equals(a.state) && SHA.equals(a.sha)
                        && "1.2".equals(a.ver) && a.at == 100L,
                "put/get 往返（字段都在）");

        String two = PluginUpdateState.put(t, "b", PluginUpdateState.CONFIRM, SHA2, null, 200L,
                "签名不同");
        PluginUpdateState.Item b = PluginUpdateState.get(two, "b");
        ok(L, b != null && PluginUpdateState.CONFIRM.equals(b.state) && "签名不同".equals(b.note),
                "第二条记录（备注保留）");
        ok(L, PluginUpdateState.get(two, "a") != null, "★ 写 b 没碰掉 a");

        String replaced = PluginUpdateState.put(two, "a", PluginUpdateState.FAILED, null, null, 300L,
                "http 404");
        ok(L, PluginUpdateState.all(replaced).size() == 2,
                "★ 覆盖不是追加：还是 2 条（实得 " + PluginUpdateState.all(replaced).size() + "）");
        ok(L, PluginUpdateState.FAILED.equals(PluginUpdateState.get(replaced, "a").state),
                "同 id 覆盖成新状态");

        ok(L, PluginUpdateState.get(PluginUpdateState.remove(replaced, "a"), "a") == null,
                "remove 删掉目标");
        ok(L, PluginUpdateState.get(PluginUpdateState.remove(replaced, "a"), "b") != null,
                "remove 没碰别人");
        ok(L, PluginUpdateState.get(PluginUpdateState.remove(null, "a"), "a") == null,
                "remove(null) 不崩");

        ok(L, PluginUpdateState.parseLine("a\tweird\tsha\tv\t1\t") == null,
                "不认识的状态整行丢掉");
        ok(L, PluginUpdateState.parseLine("a\tok\tsha") == null, "段数不对整行丢掉");
        ok(L, PluginUpdateState.parseLine("# comment") == null, "注释行跳过");
        ok(L, PluginUpdateState.parseLine("   ") == null, "空行跳过");

        PluginUpdateState.Item dirty = PluginUpdateState.parseLine("a\tok\t\t\t0\t");
        ok(L, dirty != null && dirty.sha == null && dirty.ver == null,
                "空字段 ⇒ null（不是空串）");

        ok(L, PluginUpdateState.valid(PluginUpdateState.OK)
                        && PluginUpdateState.valid(PluginUpdateState.AVAILABLE)
                        && PluginUpdateState.valid(PluginUpdateState.INSTALLED)
                        && PluginUpdateState.valid(PluginUpdateState.CONFIRM)
                        && PluginUpdateState.valid(PluginUpdateState.FAILED)
                        && !PluginUpdateState.valid("nope"),
                "valid 只认五个状态");
        ok(L, PluginUpdateState.pending(PluginUpdateState.AVAILABLE)
                        && PluginUpdateState.pending(PluginUpdateState.CONFIRM)
                        && !PluginUpdateState.pending(PluginUpdateState.INSTALLED)
                        && !PluginUpdateState.pending(PluginUpdateState.OK)
                        && !PluginUpdateState.pending(PluginUpdateState.FAILED),
                "pending 只覆盖 available / confirm");

        String note = PluginUpdateState.put("", "a", PluginUpdateState.FAILED, null, null, 1L,
                "line1\nline2\tx").toString();
        PluginUpdateState.Item n = PluginUpdateState.get(note, "a");
        ok(L, n != null && n.note != null && n.note.indexOf('\n') < 0 && n.note.indexOf('\t') < 0,
                "★ 备注里的换行 / 制表符必须压掉（否则整张表错位）");

        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 400; i++) longNote.append('x');
        ok(L, PluginUpdateState.cleanNote(longNote.toString()).length()
                        == PluginUpdateState.NOTE_MAX,
                "备注截到 " + PluginUpdateState.NOTE_MAX);

        ok(L, PluginUpdateState.all(null).isEmpty() && PluginUpdateState.get(null, "a") == null,
                "null 文本 ⇒ 空表（不崩）");
    }
}
