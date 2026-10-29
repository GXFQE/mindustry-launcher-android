package io.mdt.launcher;

import java.util.Map;

/**
 * 更新清单（`update.json`）的解析与判定。
 *
 * <p>🔴 **纯逻辑：本文件不许 import `android.*`** —— 这是它能"先在 PC 上跑一遍"的唯一理由：
 * 用 JDK 直接编译 + 跑断言，不必装设备、不必开模拟器、不必等构建。
 * 凡是要 {@code Context} 的事（网络、落盘、写指针、装包）一律不进这里。
 *
 * <p>格式与"缺了怎么办"见 `docs/plans/2026-10-29-更新器设计.md` §2。要点：
 * <ul>
 *   <li>{@code schema} 缺失按 1 处理（老格式兼容）；**不等于 1 ⇒ 整份不认**
 *       —— 将来改格式时，老客户端不能因此失去更新能力（这是"壳冻结"问题的迷你版）。</li>
 *   <li>{@code versionCode} 必须是正整数、{@code sha256} 必须是 64 位小写十六进制，
 *       否则整份视为坏数据 —— **宁可这一次不更新，也不装一个来历不明的包**。</li>
 *   <li>{@code notes} / {@code versionName} 来自网络，是**不可信输入**：
 *       上屏前必须过 {@link #gate}（压空白 + 按码点截断），否则一张卡片能被撑爆。</li>
 * </ul>
 */
final class UpdateManifest {

    /** 认的格式版本。**只增字段、不改语义**。 */
    static final int SCHEMA = 1;

    /** 说明文字上屏上限（按码点，不是 char —— emoji 是代理对）。 */
    static final int NOTES_MAX = 200;
    /** 版本名上屏上限。 */
    static final int NAME_MAX = 40;

    /** 整份文件的大小上限：超过就当垃圾（防止一个 10 MB 的"清单"把内存吃掉）。 */
    static final int MAX_BYTES = 65536;

    /** 不可用的原因码（机器码，不上屏 —— 文案归调用方）。 */
    static final String E_EMPTY = "empty";
    static final String E_TOO_BIG = "too-big";
    static final String E_NOT_JSON = "not-json";
    static final String E_SCHEMA = "schema-unsupported";
    static final String E_VERSION = "bad-version";
    static final String E_SHA = "bad-sha256";

    /** null = 可用；否则是 {@link #E_*} 之一。 */
    final String error;
    final int versionCode;
    final String versionName;
    final long size;
    final String sha256;
    final String notes;
    final String publishedAt;

    private UpdateManifest(String error, int versionCode, String versionName, long size,
                           String sha256, String notes, String publishedAt) {
        this.error = error;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.size = size;
        this.sha256 = sha256;
        this.notes = notes;
        this.publishedAt = publishedAt;
    }

    boolean ok() { return error == null; }

    private static UpdateManifest bad(String why) {
        return new UpdateManifest(why, 0, null, 0, null, null, null);
    }

    /**
     * 解析一份清单。**绝不抛异常** —— 脏输入一律折成 {@link #error}。
     *
     * @param json 从网络 / 资产里读到的字节（已按 UTF-8 解成串）；可为 null
     */
    static UpdateManifest parse(String json) {
        if (json == null || json.trim().isEmpty()) return bad(E_EMPTY);
        if (json.length() > MAX_BYTES) return bad(E_TOO_BIG);

        Map<String, String> m = FlatJson.parse(json);
        if (m == null || m.isEmpty()) return bad(E_NOT_JSON);

        // schema：缺失按 1（老格式）；其它值一律不认
        String schema = m.get("schema");
        if (schema != null && !schema.trim().isEmpty()) {
            int s;
            try {
                s = Integer.parseInt(schema.trim());
            } catch (NumberFormatException e) {
                return bad(E_SCHEMA);
            }
            if (s != SCHEMA) return bad(E_SCHEMA);
        }

        int code;
        try {
            code = Integer.parseInt(nz(m.get("versionCode")).trim());
        } catch (NumberFormatException e) {
            return bad(E_VERSION);
        }
        if (code <= 0) return bad(E_VERSION);

        String sha = nz(m.get("sha256")).trim().toLowerCase(java.util.Locale.US);
        if (!validSha256(sha)) return bad(E_SHA);

        long size = 0;
        try {
            size = Long.parseLong(nz(m.get("size")).trim());
        } catch (NumberFormatException ignored) {
            // size 只是给人看的（也用于进度）；缺了不致命，下载后按实际长度校 sha256
        }
        if (size < 0) size = 0;

        return new UpdateManifest(null, code,
                gate(m.get("versionName"), NAME_MAX),
                size,
                sha,
                gate(m.get("notes"), NOTES_MAX),
                gate(m.get("publishedAt"), NAME_MAX));
    }

    /** 远端比本机新吗。{@code remote <= 0} 一律不算（坏数据不许触发更新）。 */
    static boolean isNewer(int localCode, int remoteCode) {
        return remoteCode > 0 && remoteCode > localCode;
    }

    /** 64 位小写十六进制。大 wrote 前先 {@code toLowerCase} —— 有的工具会写成大写。 */
    static boolean validSha256(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * 该不该再查一次（静默检查的去重）。
     *
     * <p>★ **时钟回拨要当成"该查"**：用户把时间调回去、或换了个时区，不能让更新检查
     * 永远卡在"还不到时候"。这类"靠时间戳省事"的闸门最容易变成砖。
     */
    static boolean shouldCheck(long nowSec, long lastSec, long intervalSec) {
        if (intervalSec <= 0) return true;
        if (lastSec <= 0 || nowSec <= 0) return true;
        if (nowSec < lastSec) return true;
        return nowSec - lastSec >= intervalSec;
    }

    /**
     * 上屏闸门：换行 / 连续空白压成一个空格、去首尾、按**码点**截断。
     *
     * <p>理由与插件那条一样（`PluginHost.pickContent`）：这段文字进的是卡片标题区，
     * 超长会把列表与按钮顶出屏幕；而它是从网上来的，不能假设作者守规矩。
     */
    static String gate(String s, int maxCodePoints) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\u00a0') {
                space = sb.length() > 0;
                continue;
            }
            if (c < 0x20 || c == 0x7f) continue;      // 控制字符一律丢（含 ANSI 转义残留）
            if (space) {
                sb.append(' ');
                space = false;
            }
            sb.append(c);
        }
        String out = sb.toString();
        if (maxCodePoints <= 0) return out;
        int cp = out.codePointCount(0, out.length());
        if (cp <= maxCodePoints) return out;
        int end = out.offsetByCodePoints(0, maxCodePoints);
        return out.substring(0, end);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
