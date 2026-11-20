package io.mdt.launcher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 补丁清单 `patch.json` 的**解析 + 校验**。
 *
 * <h3>一种包（schema 2 = 整包 APK）</h3>
 *
 * <pre>
 * 补丁 = 一个 APK（根上多一份清单）：
 *   ├── patch.json                     ← 根上
 *   ├── classes.dex / classes2.dex…    ← 全部代码（多 dex）
 *   ├── lib/&lt;abi&gt;/*.so                ← 可选；仅 role=game
 *   └── assets/**                      ← 可选
 * </pre>
 *
 * <h3>清单</h3>
 * <pre>
 * { "schema": 2,
 *   "id": "mdtx-1607", "name": "MindustryX", "version": "B509",
 *   "role": "game",                 // game = 整端（独占）；缺省/未知 = patch
 *   "apkSha256": "&lt;64 hex&gt;",       // 基座 APK 的 sha256（精确匹配）
 *   "gameVersion": "160.7",
 *   "author": "", "note": "", "tool": "" }
 * </pre>
 *
 * <p>没有 `targets`：**一个包 = 一个基座**；要覆盖多个基座就出多个包（转换器是脚本）。
 * 旧 `.mdp`（schema 1 + `targets/`）已在第 407 轮退役：解析到 schema 1 一律
 * {@link PatchException#C_LEGACY}。</p>
 *
 * <p>纯 JDK + `org.json` —— 不 import `android.*`，设备自检直接喂字节。</p>
 */
final class PatchManifest {

    /** 整包 APK 格式（2026-11-20）。 */
    static final int SCHEMA_APK = 2;
    /** 当前（也是唯一）支持的版本。 */
    static final int SCHEMA = SCHEMA_APK;

    /** 包内清单文件名（必须在 zip 根上）。 */
    static final String FILE = "patch.json";

    static final String ROLE_PATCH = "patch";
    static final String ROLE_GAME = "game";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,31}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    /** 一个 target = 一份"针对特定 APK"的补丁产物（只有一个，字段由 APK 内容补全）。 */
    static final class Target {
        /** 目标 APK 的 sha256（64 位小写十六进制）—— 唯一匹配键。 */
        final String apkSha256;
        /** 给人看的游戏版本（如 `160.4`）；空串 = 清单没写，界面显示 APK 哈希前缀。 */
        final String gameVersion;

        Target(String apkSha256, String gameVersion) {
            this.apkSha256 = apkSha256;
            this.gameVersion = gameVersion == null ? "" : gameVersion;
        }
    }

    final int schema;
    final String id;
    final String name;
    final String version;
    final String author;
    final String note;
    final String tool;
    /** `patch` / `game`。 */
    final String role;
    /** 基座 APK 的 sha256。 */
    final String apkSha256;
    /** 给人看的基座版本。 */
    final String gameVersion;
    /** 恒为 1 条（保留列表形状：`Record` / 插件快照 / 界面都按 target 遍历）。 */
    final List<Target> targets;

    PatchManifest(int schema, String id, String name, String version, String author, String note,
                  String tool, String role, String apkSha256, String gameVersion,
                  List<Target> targets) {
        this.schema = schema;
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author == null ? "" : author;
        this.note = note == null ? "" : note;
        this.tool = tool == null ? "" : tool;
        this.role = role == null ? ROLE_PATCH : role;
        this.apkSha256 = apkSha256;
        this.gameVersion = gameVersion == null ? "" : gameVersion;
        this.targets = Collections.unmodifiableList(new ArrayList<Target>(targets));
    }

    /** 整端补丁（独占这个实例；可带 native）。 */
    boolean isGame() {
        return ROLE_GAME.equals(role);
    }

    // ── 读 ────────────────────────────────────────────────────────────────

    /** 解析 + 校验一份清单字节（UTF-8；容忍开头 BOM —— 本机踩过带 BOM 的夹具，见 §85.9）。 */
    static PatchManifest parse(byte[] bytes) throws PatchException {
        if (bytes == null || bytes.length == 0) throw new PatchException(PatchException.C_BAD_JSON, "");
        String text = new String(bytes, java.nio.charset.Charset.forName("UTF-8"));
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (JSONException e) {
            throw new PatchException(PatchException.C_BAD_JSON, e.getMessage());
        }

        int schema = o.optInt("schema", Integer.MIN_VALUE);
        if (schema != SCHEMA_APK) {
            // schema 1（旧 .mdp）已退役；未知版本同样拒绝（detail 里带上原值，导入处据此翻成"已退役"）
            throw new PatchException(PatchException.C_SCHEMA, String.valueOf(o.opt("schema")));
        }
        String[] h = head(o);       // id / name / version / author / note / tool
        if (o.has("targets")) {
            // 整包格式没有 targets：一个包 = 一个基座
            throw new PatchException(PatchException.C_FIELD, "targets");
        }
        String sha = o.optString("apkSha256", "").trim().toLowerCase(Locale.ROOT);
        if (!SHA.matcher(sha).matches()) {
            throw new PatchException(PatchException.C_BAD_HASH, o.optString("apkSha256", "apkSha256"));
        }
        String game = clean(o.optString("gameVersion", ""));
        if (game.length() > 64) throw new PatchException(PatchException.C_FIELD, "gameVersion");
        String role = o.optString("role", ROLE_PATCH).trim().toLowerCase(Locale.ROOT);
        if (!ROLE_GAME.equals(role)) role = ROLE_PATCH;   // 未知值按普通补丁（前向兼容）
        List<Target> one = new ArrayList<Target>(1);
        one.add(new Target(sha, game));
        return new PatchManifest(SCHEMA_APK, h[0], h[1], h[2], h[3], h[4], h[5],
                role, sha, game, one);
    }

    /** 公共头字段。返回 {id, name, version, author, note, tool}。 */
    private static String[] head(JSONObject o) throws PatchException {
        String id = o.optString("id", "").trim();
        if (!ID.matcher(id).matches()) throw new PatchException(PatchException.C_FIELD, "id");
        String name = clean(o.optString("name", ""));
        if (name.isEmpty() || name.length() > 128) {
            throw new PatchException(PatchException.C_FIELD, "name");
        }
        String version = clean(o.optString("version", "").trim());
        if (!VERSION.matcher(version).matches()) {
            throw new PatchException(PatchException.C_FIELD, "version");
        }
        String author = clean(o.optString("author", ""));
        if (author.length() > 128) throw new PatchException(PatchException.C_FIELD, "author");
        String note = o.optString("note", "").trim().replace('\r', '\n');
        note = cleanNote(note);                 // 控制字符（NUL / Tab / DEL…）换成空格；保留换行
        if (note.length() > 1024) throw new PatchException(PatchException.C_FIELD, "note");
        String tool = clean(o.optString("tool", ""));
        if (tool.length() > 64) throw new PatchException(PatchException.C_FIELD, "tool");
        return new String[]{id, name, version, author, note, tool};
    }

    /**
     * 显示用文本的**单行清洗**（B9）：控制字符（换行 / Tab / NUL…）换成空格并压缩连续空白。
     *
     * <p>为什么在**解析**处做而不是显示处：`name` 是外部数据，会被拼进
     * "与已启用的补丁冲突：%1$s"、启动报告（`code|detail` 行格式）、列表行 —— 每处都自己清洗
     * 一定会漏；在入口清洗一次，所有下游都安全。
     */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    /**
     * **多行**说明（`note`）的清洗：保留换行，其余控制字符（NUL / Tab / DEL…）换成空格。
     */
    static String cleanNote(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append('\n');
            } else {
                sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * zip 条目名 / 清单内路径的**安全判据**（唯一实现；补丁包与解包路径都用它）。
     *
     * <p>拒绝：空、过长、含 NUL、反斜杠、冒号（盘符 `C:`）、前导 `/`、空段（`a//b`）、
     * `.` / `..` 段。保留：非 ASCII（中文文件名）、空格、`#`、`+` 等。
     */
    static boolean safeRel(String path) {
        if (path == null) return false;
        int n = path.length();
        if (n == 0 || n > 240) return false;
        if (path.charAt(0) == '/') return false;
        for (int i = 0; i < n; i++) {
            char c = path.charAt(i);
            if (c == '\0' || c == '\\' || c == ':') return false;
        }
        String[] parts = path.split("/", -1);
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p) || "..".equals(p)) return false;
        }
        return true;
    }

    // ── 写（自检造夹具用；导入时存的是**原字节**，不重新序列化）──────────────

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("schema", schema);
            o.put("id", id);
            o.put("name", name);
            o.put("version", version);
            o.put("author", author);
            o.put("note", note);
            o.put("tool", tool);
            o.put("role", role);
            o.put("apkSha256", apkSha256 == null ? "" : apkSha256);
            o.put("gameVersion", gameVersion);
        } catch (JSONException e) {
            // org.json 的 put 只在 key 为 null 时抛；这里的 key 全是字面量 ⇒ 理论上到不了
            throw new IllegalStateException("manifest toJson failed", e);
        }
        return o;
    }

    byte[] toBytes() {
        return toJson().toString().getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    /** 清单里 target 个数（界面副标题用；恒为 1）。 */
    int targetCount() {
        return targets.size();
    }
}
