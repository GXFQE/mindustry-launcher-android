package io.mdt.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 补丁包清单 `patch.json` 的**解析 + 校验**（补丁机制 v1，2026-11-10）。
 *
 * <h3>包格式（对外契约，生成器按这个写）</h3>
 * <pre>
 * my-patch.mdp (zip)
 * ├── patch.json
 * └── targets/&lt;apkSha256&gt;/
 *     ├── patch.dex          必需：完整替代类（prepend 覆盖原类）
 *     └── assets.zip         可选：条目必须都在 assets/ 下（addAssetPath 增量）
 * </pre>
 *
 * <pre>
 * { "schema": 1,
 *   "id": "mindustryy-160.4",          // 唯一身份（小写/点/下划线/横线），删除重建也认它
 *   "name": "MindustryY",              // 显示名
 *   "version": "1.0",                  // 补丁版本（同 id 可并存多版本，但同一版本号只能有一份）
 *   "author": "", "note": "", "tool": "",
 *   "targets": [ { "apkSha256": "&lt;64 hex&gt;",
 *                  "gameVersion": "160.4",              // 只用于显示
 *                  "dex": "targets/&lt;sha&gt;/patch.dex",
 *                  "assets": "targets/&lt;sha&gt;/assets.zip" } ] }
 * </pre>
 *
 * <h3>为什么要"精确到 APK 哈希"</h3>
 * 补丁替换的是**完整类**：方法签名 / 合成类 / 内部类都随每个游戏构建变。拿 160 的类去顶
 * 160.7，实测是 `NoClassDefFoundError: arc.util.OS$$ExternalSyntheticOutline0` 直接崩
 * （见 `docs/plans/2026-10-28-补丁机制-ART路线.md` §8）。所以匹配键只能是**这份 APK 的 sha256**，
 * 像模组那样给 `minGameVersion` 区间是错的。
 *
 * <h3>校验三道（都返回 {@link PatchException}，码 → 人话在 {@link PatchText}）</h3>
 * <ol>
 *   <li>结构：schema / id / name / version / targets 数组；</li>
 *   <li>哈希：64 位小写十六进制（统一转小写，避免同一份包两种写法算两个 target）；</li>
 *   <li>路径：dex / assets 必须是 `targets/&lt;自己的 sha&gt;/xxx` 下的**单层文件名**，
 *       且整条路径过 {@link #safeRel}（无 `..` / 盘符 / 反斜杠 / 前导 `/`）。</li>
 * </ol>
 *
 * <p>纯 JDK + `org.json`（Android 自带）—— 不 import `android.*`，设备自检直接喂字节。
 */
final class PatchManifest {

    /** 第一版格式版本。未知 schema **拒绝**，不猜（"以后能兼容"是最容易埋雷的乐观）。 */
    static final int SCHEMA = 1;

    /** 一份清单里最多接受多少个 target（一个包带多版本，但不该带几十个）。 */
    static final int MAX_TARGETS = 8;

    /** 包内清单文件名（必须在 zip 根上）。 */
    static final String FILE = "patch.json";

    /** target 目录前缀。 */
    static final String TARGETS = "targets/";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,31}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    /** 一个 target = 一份"针对特定 APK"的补丁产物。 */
    static final class Target {
        /** 目标 APK 的 sha256（64 位小写十六进制）—— 唯一匹配键。 */
        final String apkSha256;
        /** 给人看的游戏版本（如 `160.4`）；空串 = 清单没写，界面显示 APK 哈希前缀。 */
        final String gameVersion;
        /** 包内 dex 相对路径（已验证在 `targets/<apkSha256>/` 下）。 */
        final String dex;
        /** 包内素材 zip 相对路径；空串 = 没有素材。 */
        final String assets;

        Target(String apkSha256, String gameVersion, String dex, String assets) {
            this.apkSha256 = apkSha256;
            this.gameVersion = gameVersion == null ? "" : gameVersion;
            this.dex = dex;
            this.assets = assets == null ? "" : assets;
        }

        boolean hasAssets() {
            return !assets.isEmpty();
        }
    }

    final String id;
    final String name;
    final String version;
    final String author;
    final String note;
    final String tool;
    final List<Target> targets;

    PatchManifest(String id, String name, String version, String author, String note,
                  String tool, List<Target> targets) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author == null ? "" : author;
        this.note = note == null ? "" : note;
        this.tool = tool == null ? "" : tool;
        this.targets = Collections.unmodifiableList(new ArrayList<Target>(targets));
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
        if (schema != SCHEMA) {
            throw new PatchException(PatchException.C_SCHEMA, String.valueOf(o.opt("schema")));
        }

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

        JSONArray arr = o.optJSONArray("targets");
        if (arr == null) throw new PatchException(PatchException.C_FIELD, "targets");
        if (arr.length() == 0) throw new PatchException(PatchException.C_NO_TARGETS, "");
        if (arr.length() > MAX_TARGETS) throw new PatchException(PatchException.C_FIELD, "targets");

        List<Target> targets = new ArrayList<Target>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) throw new PatchException(PatchException.C_FIELD, "targets[" + i + "]");
            String sha = t.optString("apkSha256", "").trim().toLowerCase(Locale.ROOT);
            if (!SHA.matcher(sha).matches()) {
                throw new PatchException(PatchException.C_BAD_HASH,
                        t.optString("apkSha256", "targets[" + i + "]"));
            }
            for (Target e : targets) {
                if (e.apkSha256.equals(sha)) {
                    // 同一份 APK 两个 target：静默取一个就是"随机行为"，直接拒绝
                    throw new PatchException(PatchException.C_FIELD, "targets[" + i + "].apkSha256");
                }
            }
            String game = clean(t.optString("gameVersion", ""));
            if (game.length() > 64) throw new PatchException(PatchException.C_FIELD, "gameVersion");
            String dex = t.optString("dex", "").trim();
            checkTargetPath(dex, sha, ".dex");
            String assets = t.optString("assets", "").trim();
            if (!assets.isEmpty()) checkTargetPath(assets, sha, ".zip");
            targets.add(new Target(sha, game, dex, assets));
        }
        return new PatchManifest(id, name, version, author, note, tool, targets);
    }

    /** dex / assets 必须是 `targets/&lt;自己的 sha&gt;/&lt;名字&gt;.dex|.zip` —— 单层文件名。 */
    private static void checkTargetPath(String path, String sha, String suffix)
            throws PatchException {
        if (!safeRel(path)) throw new PatchException(PatchException.C_BAD_PATH, path);
        String prefix = TARGETS + sha + "/";
        if (!path.startsWith(prefix)) throw new PatchException(PatchException.C_BAD_PATH, path);
        String rest = path.substring(prefix.length());
        if (rest.isEmpty() || rest.indexOf('/') >= 0) {
            throw new PatchException(PatchException.C_BAD_PATH, path);
        }
        if (!rest.endsWith(suffix) || rest.length() > 64) {
            throw new PatchException(PatchException.C_BAD_PATH, path);
        }
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
     *
     * <p>为什么 `note` 不能走 {@link #clean(String)}：它是唯一允许换行的清单字段（说明本来就
     * 可能分几行）；但控制字符一样不能原样进 `extra`（插件最终会把它上屏 / 写报告）。
     * ★ 第 372 轮评审发现：这是唯一没清洗的清单字段（`name`/`author`/`tool` 都走 clean）。
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
            o.put("schema", SCHEMA);
            o.put("id", id);
            o.put("name", name);
            o.put("version", version);
            o.put("author", author);
            o.put("note", note);
            o.put("tool", tool);
            JSONArray arr = new JSONArray();
            for (Target t : targets) {
                JSONObject j = new JSONObject();
                j.put("apkSha256", t.apkSha256);
                j.put("gameVersion", t.gameVersion);
                j.put("dex", t.dex);
                if (t.hasAssets()) j.put("assets", t.assets);
                arr.put(j);
            }
            o.put("targets", arr);
        } catch (JSONException e) {
            // org.json 的 put 只在 key 为 null 时抛；这里的 key 全是字面量 ⇒ 理论上到不了
            throw new IllegalStateException("manifest toJson failed", e);
        }
        return o;
    }

    byte[] toBytes() {
        return toJson().toString().getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    /** 清单里 target 个数（界面副标题用）。 */
    int targetCount() {
        return targets.size();
    }
}
