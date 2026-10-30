package io.mdt.launcher;

import java.util.Map;

/**
 * 插件更新清单（`plugin-update.json`）的解析与判定。
 *
 * <p>🔴 **纯逻辑：本文件不许 import `android.*`** —— 与 {@link UpdateManifest} 同一条纪律：
 * 这样它能在 PC 上直接 javac + java 跑断言，不必装设备。
 *
 * <h3>为什么**不认版本号**（2026-10-30 用户定）</h3>
 * 「插件这东西鬼知道版本号咋写的」—— 现有插件里有人写 `5`、有人写 `1`、有人（内置那三份）
 * 干脆用 sha256 前 8 位当版本键。⇒ 任何"版本号大小"的比较都会在某个作者那里翻车。
 * ⇒ 本清单只认 **内容哈希**：清单里的 `sha256` 与**本机那一份 APK** 的实际哈希不同 ⇒ 有更新。
 * 谁是"新版"由**插件作者自己**在清单里指定 —— 他要回滚，就把清单指回旧包，这也是合法的更新。
 *
 * <h3>格式</h3>
 * <pre>
 * {
 *   "schema": 1,                       // 可选；缺省按 1，其它值整份不认
 *   "sha256": "&lt;64 位十六进制&gt;",   // 必需：远端 APK 的内容哈希
 *   "url": "https://…/x.apk",          // 必需：只认 https（回环明文只给 dev 夹具）
 *   "size": 123456,                    // 可选：下载时边下边校（缺省按实际长度校 sha256）
 *   "version": "1.2",                  // 可选：**只用来显示**，不参与任何比较
 *   "notes": "这一版改了什么",          // 可选：卡片里那一行
 *   "plugin": "mapstats",              // 可选：声明这是哪个插件（对不上 ⇒ 不认，防串包）
 *   "minHostApi": 36                   // 可选：宿主接口版本低于它就不提示（装了也用不了）
 * }
 * </pre>
 *
 * <p>⚠️ 清单来自**插件自己声明的地址**，是不可信输入：所有文本上屏前过 {@link #gate}，
 * 下载地址过 {@link #allowedUrl}（只认 https + 本机回环）。
 */
final class PluginUpdateManifest {

    /** 认的格式版本。**只增字段、不改语义**。 */
    static final int SCHEMA = 1;

    /** 整份文件的大小上限（插件清单比启动器那份还小；超了就是垃圾）。 */
    static final int MAX_BYTES = 16384;

    static final int NOTES_MAX = 160;
    static final int VERSION_MAX = 40;
    static final int URL_MAX = 512;
    static final int PLUGIN_MAX = 64;

    /** 不可用的原因码（机器码，不上屏 —— 文案归调用方）。 */
    static final String E_EMPTY = "empty";
    static final String E_TOO_BIG = "too-big";
    static final String E_NOT_JSON = "not-json";
    static final String E_SCHEMA = "schema-unsupported";
    static final String E_SHA = "bad-sha256";
    static final String E_URL = "bad-url";
    static final String E_HOST_OLD = "host-too-old";
    static final String E_ID = "plugin-mismatch";

    /** null = 可用；否则是 {@link #E_*} 之一。 */
    final String error;
    final String sha256;
    /** APK 下载地址（≥ schema 1 时必需）。 */
    final String url;
    /** 只用于显示的版本串（可空）。 */
    final String version;
    final String notes;
    /** 清单自称属于哪个插件 id（可空 = 不声明）。 */
    final String plugin;
    /** 字节数；{@code <= 0} = 没声明。 */
    final long size;
    /** 要求的最低宿主接口版本；{@code <= 0} = 没声明。 */
    final int minHostApi;

    private PluginUpdateManifest(String error, String sha256, String url, String version,
                                 String notes, String plugin, long size, int minHostApi) {
        this.error = error;
        this.sha256 = sha256;
        this.url = url;
        this.version = version;
        this.notes = notes;
        this.plugin = plugin;
        this.size = size;
        this.minHostApi = minHostApi;
    }

    boolean ok() { return error == null; }

    private static PluginUpdateManifest bad(String why) {
        return new PluginUpdateManifest(why, null, null, null, null, null, 0, 0);
    }

    /**
     * 解析一份清单。**绝不抛异常** —— 脏输入一律折成 {@link #error}。
     *
     * @param json    从网络 / 资产里读到的文本；可为 null
     * @param hostApi 宿主当前支持的接口版本；{@code <= 0} 表示不判 `minHostApi`
     */
    static PluginUpdateManifest parse(String json, int hostApi) {
        if (json == null || json.trim().isEmpty()) return bad(E_EMPTY);
        if (json.length() > MAX_BYTES) return bad(E_TOO_BIG);

        Map<String, String> m = FlatJson.parse(json);
        if (m == null || m.isEmpty()) return bad(E_NOT_JSON);

        // schema：缺失按 1（老格式兼容）；其它值一律不认
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

        String sha = nz(m.get("sha256")).trim().toLowerCase(java.util.Locale.US);
        if (!UpdateManifest.validSha256(sha)) return bad(E_SHA);

        String url = gate(m.get("url"), URL_MAX);
        if (!allowedUrl(url)) return bad(E_URL);

        long size = 0;
        try {
            size = Long.parseLong(nz(m.get("size")).trim());
        } catch (NumberFormatException ignored) {
            // size 只用于早失败与进度；缺了不致命（下载后按实际长度校 sha256）
        }
        if (size < 0) size = 0;

        int minApi = 0;
        String minRaw = m.get("minHostApi");
        if (minRaw != null && !minRaw.trim().isEmpty()) {
            try {
                minApi = Integer.parseInt(minRaw.trim());
            } catch (NumberFormatException e) {
                minApi = 0;   // 写坏了就当没声明：这类字段不值得让整份清单作废
            }
            if (minApi < 0) minApi = 0;
        }
        if (hostApi > 0 && minApi > hostApi) {
            return new PluginUpdateManifest(E_HOST_OLD, sha, url,
                    gate(m.get("version"), VERSION_MAX), gate(m.get("notes"), NOTES_MAX),
                    gate(m.get("plugin"), PLUGIN_MAX), size, minApi);
        }

        return new PluginUpdateManifest(null, sha, url,
                gate(m.get("version"), VERSION_MAX), gate(m.get("notes"), NOTES_MAX),
                gate(m.get("plugin"), PLUGIN_MAX), size, minApi);
    }

    /** 同 {@link #parse(String, int)}，但不判宿主接口版本（PC 断言 / 未知宿主时用）。 */
    static PluginUpdateManifest parse(String json) {
        return parse(json, -1);
    }

    /** 清单自称的插件 id 与本次检查的 id 对不对得上（没声明 ⇒ 不拦）。 */
    boolean matches(String id) {
        return plugin == null || plugin.isEmpty() || (id != null && plugin.equals(id));
    }

    /**
     * **有更新吗**：两边哈希都拿得到、且**不相等**。
     *
     * <p>★ 这就是整套"版本号"问题的答案：不比大小、不解析语义版本，只比内容。
     * 本机那一份读不出来（{@code localSha == null}）⇒ **不算有更新**（没证据不动文件）。
     */
    static boolean differs(String localSha, String remoteSha) {
        if (localSha == null || remoteSha == null) return false;
        return !localSha.equalsIgnoreCase(remoteSha);
    }

    /**
     * 下载地址准不准（**与启动器自更新同一把尺子**，直接复用）。
     *
     * <p>**只认 https** —— 更新包的下载被降级成明文，等于把"换成谁的包"交给链路上的任何人。
     * 例外只有回环：那是本地夹具，dev 口才用。
     */
    static boolean allowedUrl(String url) {
        return UpdateManifest.allowedUrl(url);
    }

    /** 内容哈希的前 8 位（给人看的短名 / 落点版本键）。 */
    static String sha8(String sha) {
        if (sha == null || sha.length() < 8) return null;
        return sha.substring(0, 8).toLowerCase(java.util.Locale.US);
    }

    /** 上屏闸门（与 {@link UpdateManifest#gate} 同一份实现 —— 换行 / 控制字符一律压掉）。 */
    static String gate(String s, int maxCodePoints) {
        return UpdateManifest.gate(s, maxCodePoints);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
