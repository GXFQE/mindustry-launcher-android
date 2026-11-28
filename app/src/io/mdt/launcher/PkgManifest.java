package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个版本 APK 的清单（附录 E 的 `manifests/&lt;vkey&gt;.json`）。
 *
 * <p>清单一落盘，这个版本就不再需要原始 APK：dex / .so / assets 都能按 sha 从
 * {@link Pkgs} 取回来，包名与版本也从这里读（不再 `getPackageArchiveInfo`）。</p>
 *
 * <p>字段：`p` = APK 里的原始路径（保留 `assets/` 前缀，和 zip 条目完全一致），
 * `s` = sha256、`n` = 字节数、`c` = CRC32、`t` = 原条目的修改时间。
 * `c`/`t` 是给「关掉 CAS 时把包拼回去」用的 —— 重建的 APK 不保证与原件逐字节相同，
 * 但每个条目的内容、CRC 和时间戳都能对上。</p>
 */
public final class PkgManifest {
    private static final String TAG = "MDTLauncher";
    public static final int FORMAT = 1;

    /** 一条条目。 */
    public static final class Entry {
        public final String path;
        public final String sha;
        public final long size;
        public final long crc;
        public final long time;

        public Entry(String path, String sha, long size, long crc, long time) {
            this.path = path;
            this.sha = sha;
            this.size = size;
            this.crc = crc;
            this.time = time;
        }
    }

    /** arc 的 ABI 判定结果（决定这个版本能不能走同名类覆盖）。 */
    public static final class Arc {
        public final String fingerprint;
        public final boolean eligible;
        public final String reason;

        public Arc(String fingerprint, boolean eligible, String reason) {
            this.fingerprint = fingerprint;
            this.eligible = eligible;
            this.reason = reason;
        }

        static Arc none() {
            return new Arc(null, false, "not-probed");
        }
    }

    public int format = FORMAT;
    public String vkey;
    public String file;
    public String label;
    public String version;
    public long size;
    public long added;
    public String md5;
    public String apkSha256;
    /**
     * 「这份包的**原件**字节」的 sha256（第 421 轮）。
     *
     * <p>为什么需要它：`PkgRestore.rebuild` 从池子里拼回来的 APK 与原件**不是同一份字节**
     * （全部 STORED、丢掉 `META-INF/`，见 {@link PkgRestore} 的类注释）⇒ 重拼一次
     * {@link #apkSha256} 就变一次。而补丁包（`patch.json`）认的是「APK 整份字节的 sha256」，
     * 于是「关一次去重 → 再开」就把所有补丁变成「不适用这个 APK」——
     * 用户看到的现象就是「加了 CAS 以后补丁全炸了」。</p>
     *
     * <p>所以：{@link #apkSha256} 记盘上这份文件的真实哈希（拼回、校验用），
     * 本字段记它**本来是哪份包**（补丁认人用）；重拼前 {@link PkgRestore} 会把旧值写进
     * `import/<file>.origin` 旁证，下次入库时 {@link PkgIngest} 再带回来。
     * 没有重拼过的版本本字段为 null（此时原件就是 {@link #apkSha256}）。</p>
     */
    public String originSha256;
    /**
     * 这个版本是不是「远古包」（build ≤ 146，只认游戏默认根 `files/`，不认
     * `mindustry.data.dir` 属性）。**入库时就判好**——删掉 APK 之后
     * `Compat.probe(apk)` 已经无从下手，而 `GameSlot.needsLegacySlot` 一旦探不到
     * 就会当成远古包，把整个槽改成「改名交换」布局（真机上真的搬空过槽）。
     */
    public boolean legacy;
    public Arc arc = Arc.none();
    public final List<Entry> entries = new ArrayList<Entry>();

    public static File dir(Context ctx) {
        return Pkgs.get(ctx).manifestDir();
    }

    /** vkey 形如 `import:v160.apk` → 文件名 `import_v160.apk.json`（冒号/斜杠都要消掉）。 */
    public static File fileOf(Context ctx, String vkey) {
        String safe = vkey == null ? "unknown" : vkey.replace(':', '_').replace('/', '_').replace('\\', '_');
        return new File(dir(ctx), safe + ".json");
    }

    public static PkgManifest read(File f) {
        if (f == null || !f.isFile()) return null;
        String text;
        try {
            text = Util.readText(f);
        } catch (IOException e) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(text);
            PkgManifest m = new PkgManifest();
            m.format = o.optInt("format", 0);
            if (m.format != FORMAT) return null;
            m.vkey = o.optString("vkey", null);
            m.file = o.optString("file", null);
            m.label = o.optString("label", null);
            m.version = o.optString("version", null);
            m.size = o.optLong("size", 0);
            m.added = o.optLong("added", 0);
            m.md5 = o.optString("md5", null);
            m.apkSha256 = o.optString("apkSha256", null);
            m.originSha256 = o.optString("originSha256", null);
            m.legacy = o.optBoolean("legacy", false);
            JSONObject a = o.optJSONObject("arc");
            if (a != null) {
                m.arc = new Arc(a.optString("fingerprint", null),
                        a.optBoolean("eligible", false),
                        a.optString("reason", null));
            }
            JSONArray arr = o.optJSONArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray e = arr.optJSONArray(i);
                    if (e == null || e.length() < 5) continue;
                    m.entries.add(new Entry(e.getString(0), e.getString(1),
                            e.getLong(2), e.getLong(3), e.getLong(4)));
                }
            }
            if (m.vkey == null || m.entries.isEmpty()) return null;
            return m;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 原子写：`.part` → rename。 */
    public void write(File f) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        JSONObject o = new JSONObject();
        try {
            o.put("format", FORMAT);
            o.put("vkey", vkey);
            o.put("file", file);
            o.put("label", label);
            o.put("version", version);
            o.put("size", size);
            o.put("added", added);
            o.put("md5", md5);
            o.put("apkSha256", apkSha256);
            o.put("originSha256", originSha256);
            o.put("legacy", legacy);
            JSONObject a = new JSONObject();
            a.put("fingerprint", arc == null ? null : arc.fingerprint);
            a.put("eligible", arc != null && arc.eligible);
            a.put("reason", arc == null ? null : arc.reason);
            o.put("arc", a);
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONArray row = new JSONArray();
                row.put(e.path);
                row.put(e.sha);
                row.put(e.size);
                row.put(e.crc);
                row.put(e.time);
                arr.put(row);
            }
            o.put("entries", arr);
        } catch (JSONException e) {
            throw new IOException("manifest json: " + e.getMessage());
        }
        File part = new File(dir, f.getName() + ".part");
        OutputStream out = new FileOutputStream(part);
        try {
            out.write(o.toString().getBytes("UTF-8"));
            out.flush();
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        } finally {
            out.close();
        }
        if (!part.renameTo(f)) {
            Pkgs.copyFile(part, f);
            part.delete();
        }
    }

    public void delete(File f) {
        if (f == null) return;
        f.setWritable(true);
        f.delete();
    }

    /**
     * 读池里记着的「原始 APK 的 sha256」。
     *
     * <p>★ 第 418 轮：CAS 模式下原始 APK 已经被删（内容全在池里），可补丁的适用判据
     * （{@code PatchManifest} 的 target sha256）仍然要这个哈希。入库时
     * {@code PkgIngest} 就把 {@link #apkSha256} 写进清单了 ⇒ 这里直接读，别再拿
     * "文件不在" 当成 "没有哈希"，否则界面上一整页补丁都会变成"不适用"。
     *
     * @return 小写哈希；没有清单 / 旧清单没这个字段 ⇒ 空串（调用方按"判不出"处理）
     */
    public static String apkShaOf(Context ctx, String vkey) {
        PkgManifest m = read(fileOf(ctx, vkey));
        if (m == null || m.apkSha256 == null) return "";
        return m.apkSha256.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 「补丁该拿哪个哈希去认这个包」（第 421 轮）：有原件哈希就用原件，否则用盘上字节的哈希。
     *
     * <p>纯函数，给 {@link Patches} / {@code PatchesActivity} / 自检共用一份口径 ——
     * 三处各写一遍必然漂（补丁注入用一份、页面判「适用」用另一份，就会出现
     * 「页面说能用、启动时没注入」）。</p>
     */
    public static String patchSha(PkgManifest m) {
        if (m == null) return "";
        if (m.originSha256 != null && !m.originSha256.trim().isEmpty()) {
            return m.originSha256.trim().toLowerCase(Locale.ROOT);
        }
        if (m.apkSha256 != null && !m.apkSha256.trim().isEmpty()) {
            return m.apkSha256.trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /**
     * {@link #patchSha} 的「按 vkey 读清单」版本。
     *
     * <p>★ 第 427 轮：清单可能已经不在了、而旁证还在。{@link PkgAdmin#disable} 重建完 APK
     * 就把池子连同清单一起清掉，只剩 {@link PkgRestore} 写在 {@code import/<file>.origin}
     * 的原件哈希。这时候只认清单，补丁就整页变成「不适用这个 APK」——2026-11-25 真机复现：
     * 关一次去重再打开补丁页，{@code 7217 个类 · 已启用} 直接变成 {@code 0 个类 · 不适用}。
     * ⇒ 清单读不出来时回退到旁证（与 {@link PkgIngest#originOf} 同一套校验），两边都没有才给空串。</p>
     */
    public static String patchShaOf(Context ctx, String vkey) {
        String fromManifest = patchSha(read(fileOf(ctx, vkey)));
        if (!fromManifest.isEmpty()) return fromManifest;
        String file = importNameOf(vkey);
        if (file == null) return "";
        return PkgIngest.originOf(new File(Importer.importDir(ctx), file));
    }

    /**
     * 第 438 轮：删版本时把清单一起删掉。
     *
     * <p>删版本只挪本体、清 config，清单会留下来 ⇒ {@code import_<名>.apk.json} 变成幽灵记录
     * （2026-11-28 真机连删两个测试版本各留过一条）。残留会让 {@link #patchShaOf} /
     * {@link Pkg#open} 读到一个已经不存在的版本名。{@code .part} 是原子写的中途件，一起收。</p>
     */
    static void drop(Context ctx, String vkey) {
        File f = fileOf(ctx, vkey);
        File part = new File(f.getParentFile(), f.getName() + ".part");
        if (part.isFile()) part.delete();
        if (f.isFile() && !f.delete()) Log.w(TAG, "manifest delete failed: " + f);
    }

    /** `import:&lt;file&gt;` → `&lt;file&gt;`；其它键（安装版升级上来的、旧格式）⇒ null。 */
    public static String importNameOf(String vkey) {
        if (vkey == null) return null;
        final String prefix = "import:";
        if (!vkey.startsWith(prefix)) return null;
        String name = vkey.substring(prefix.length()).trim();
        return name.isEmpty() ? null : name;
    }

    // ── 查询辅助 ──────────────────────────────────────────────────────

    public Entry entry(String path) {
        for (Entry e : entries) {
            if (e.path.equals(path)) return e;
        }
        return null;
    }

    /** `assets/` 前缀去掉后的资产路径 → 条目（游戏侧看到的就是这个）。 */
    public Entry asset(String key) {
        return entry("assets/" + key);
    }

    /** APK 根下的 `classes*.dex`（不含 assets/ 里的同名单）。 */
    public boolean isDex(String path) {
        if (path == null || path.indexOf('/') >= 0) return false;
        return path.startsWith("classes") && path.endsWith(".dex");
    }

    /** `lib/<abi>/<name>` 的条目。 */
    public Entry lib(String abi, String name) {
        return entry("lib/" + abi + "/" + name);
    }

    /** 汇总：这个版本在池里占多少字节。 */
    public long bytes() {
        long n = 0;
        for (Entry e : entries) n += e.size;
        return n;
    }

    public String describe() {
        return String.format(Locale.US, "%s (%s) %d entries / %.2f MB%s",
                label, version, entries.size(), bytes() / 1048576.0,
                arc != null && arc.eligible ? " [arc-ok]" : "");
    }
}
