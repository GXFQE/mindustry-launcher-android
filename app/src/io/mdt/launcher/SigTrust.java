package io.mdt.launcher;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 游戏本体 APK 的**受信任签名表**（第 415 轮，用户：「或许默认原版和MDTX，也允许自己导入证书？」）。
 *
 * <p>三条来源：
 * <ol>
 *   <li>内置 {@link #OFFICIAL_FP}（官方 Mindustry，实测 v104.10 / v146 / v160.7 同一张证书）；</li>
 *   <li>内置 {@link #MDTX_FP}（MindustryX，用户自家 fork 线）；</li>
 *   <li>用户自己导入的证书（存在 `config.json` 的 `sig_trust` 数组里，可增可删）。</li>
 * </ol>
 *
 * <p>★ 指纹口径与 {@link PluginTrust#firstOf} 完全一致 = **证书 DER 字节的 SHA-256**
 * （64 位小写 hex）。所以导入 `.pem` / `.cer` / `.der` 时只要把第一张证书解出来、
 * 对它 {@code getEncoded()} 算 SHA-256，就能和 APK 里的指纹逐字比对。
 *
 * <p>⚠️ 内置两条**固定不可删**：它们能删的话，用户误删一次就等于把自家改端判成第三方，
 * 而且没有任何界面能把它们加回来。
 */
final class SigTrust {
    private static final String TAG = "MDTLauncher";

    /** 官方 Mindustry 的证书 SHA-256（CN=Anu Ken）。 */
    static final String OFFICIAL_FP = "87e2cba385052d3f7146b091a9bab1477d4a2a57bfcee4ccb92ca99b6d032bf3";
    /** MindustryX（B509 实测；换签名会重新问，可接受）。 */
    static final String MDTX_FP = "5b79c6d9b795c78e707aa8f8504a8377e63bb7fb780fa121737d8cf072074653";

    /** 信任来源（写进 report-import.txt 的 `trust=`）。 */
    static final int SOURCE_NONE = 0;
    static final int SOURCE_OFFICIAL = 1;
    static final int SOURCE_MDTX = 2;
    static final int SOURCE_CUSTOM = 3;

    /** 判定结果：直接过 / 问用户 / 拒绝。 */
    static final int PASS = 0;
    static final int NEED_ASK = 1;
    static final int BLOCK = 2;

    /** 证书文件上限（正常证书几 KB；1 MB 足够拦住"选错成游戏包"）。 */
    static final int MAX_CERT_BYTES = 1024 * 1024;
    /** 导入记录只留最近这么多字符（避免一个文件无限长）。 */
    static final int MAX_RECORD_CHARS = 48 * 1024;
    static final String RECORD_FILE = "report-import.txt";

    private static final byte[] PEM_BEGIN = "-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PEM_END = "-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);

    private SigTrust() {}

    // ── 指纹 ─────────────────────────────────────────────────────────────

    /** 合法指纹 = 64 位 hex；返回小写形式，非法返回 null。 */
    static String normalize(String fp) {
        if (fp == null) return null;
        String s = fp.trim().toLowerCase(Locale.US);
        return isFingerprint(s) ? s : null;
    }

    static boolean isFingerprint(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** 这个指纹来自哪一类信任（内置 / 自定义 / 根本没有）。 */
    static int sourceOf(Context c, String fp) {
        String n = normalize(fp);
        if (n == null) return SOURCE_NONE;
        if (OFFICIAL_FP.equals(n)) return SOURCE_OFFICIAL;
        if (MDTX_FP.equals(n)) return SOURCE_MDTX;
        JSONArray arr = Config.get().sigTrust();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (n.equals(normalize(o.optString("fp", "")))) return SOURCE_CUSTOM;
        }
        return SOURCE_NONE;
    }

    static boolean trusted(Context c, String fp) {
        return sourceOf(c, fp) != SOURCE_NONE;
    }

    static String sourceName(int source) {
        switch (source) {
            case SOURCE_OFFICIAL: return "official";
            case SOURCE_MDTX: return "mdtx";
            case SOURCE_CUSTOM: return "custom";
            default: return "none";
        }
    }

    // ── 判定（纯函数，自检直接钉真值表） ────────────────────────────────

    /**
     * 该走哪条路。受信任的签名**永远**直接过（三档都只作用于"不在列表里"的包）。
     */
    static int decide(int mode, int source) {
        if (source != SOURCE_NONE) return PASS;
        switch (mode) {
            case SigMode.LOG: return PASS;
            case SigMode.ASK: return NEED_ASK;
            default: return BLOCK;
        }
    }

    // ── 自定义列表 ───────────────────────────────────────────────────────

    /** 自定义条目（指纹 + 显示名）；坏条目跳过。 */
    static List<String[]> custom(Context c) {
        List<String[]> out = new ArrayList<>();
        JSONArray arr = Config.get().sigTrust();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String fp = normalize(o.optString("fp", ""));
            if (fp == null) continue;
            String label = o.optString("label", "").trim();
            if (label.isEmpty()) label = PluginTrust.shortFp(fp);
            out.add(new String[]{fp, label});
        }
        return out;
    }

    static int customCount(Context c) {
        return custom(c).size();
    }

    /** 加进自定义列表；非法 / 已存在（含内置）不写、返回 false。 */
    static boolean add(Context c, String fp, String label) {
        String n = normalize(fp);
        if (n == null) return false;
        if (sourceOf(c, n) != SOURCE_NONE) return false;
        JSONArray next = new JSONArray();
        JSONArray old = Config.get().sigTrust();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null) next.put(o);
        }
        JSONObject e = new JSONObject();
        try {
            e.put("fp", n);
            e.put("label", label == null ? "" : label.trim());
        } catch (Exception ignored) {
        }
        next.put(e);
        Config.get().setSigTrust(next);
        return true;
    }

    /** 删自定义条目（内置删不掉）；返回是否真的删了。 */
    static boolean remove(Context c, String fp) {
        String n = normalize(fp);
        if (n == null) return false;
        JSONArray next = new JSONArray();
        boolean removed = false;
        JSONArray old = Config.get().sigTrust();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (n.equals(normalize(o.optString("fp", "")))) {
                removed = true;
                continue;
            }
            next.put(o);
        }
        if (removed) Config.get().setSigTrust(next);
        return removed;
    }

    // ── 证书文件 → 指纹 ─────────────────────────────────────────────────

    /**
     * 读一张 X.509 证书（PEM / DER 都行），返回 DER 的 SHA-256。
     * 链文件只取**第一张**（第一张就是签名者）。
     */
    static String fingerprintOfCert(byte[] raw) throws Exception {
        if (raw == null || raw.length == 0) throw new IOException("empty certificate file");
        if (raw.length > MAX_CERT_BYTES) throw new IOException("certificate file is too large");
        byte[] der = pemToDer(raw);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(der));
        return PluginTrust.sha256(cert.getEncoded());
    }

    /** PEM → DER；不是 PEM（没有 BEGIN 标记）就原样当 DER 返回。 */
    static byte[] pemToDer(byte[] raw) throws IOException {
        int b = indexOf(raw, PEM_BEGIN, 0);
        if (b < 0) return raw;
        int start = b + PEM_BEGIN.length;
        int e = indexOf(raw, PEM_END, start);
        if (e < 0) throw new IOException("PEM has no END marker");
        StringBuilder sb = new StringBuilder(e - start);
        for (int i = start; i < e; i++) {
            char ch = (char) (raw[i] & 0xFF);
            boolean b64 = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=';
            if (b64) sb.append(ch);
        }
        if (sb.length() == 0) throw new IOException("PEM has no body");
        try {
            return Base64.decode(sb.toString(), Base64.DEFAULT);
        } catch (IllegalArgumentException ex) {
            throw new IOException("PEM body is not base64");
        }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ── 记录 ─────────────────────────────────────────────────────────────

    /**
     * 往 `hub/report-import.txt` 追一行。键名用 ASCII，方便 adb / grep；
     * 文件被截到最近 {@link #MAX_RECORD_CHARS} 个字符。
     */
    static synchronized void record(Context ctx, int mode, String displayName, String fp,
                                    int source, String action) {
        File f = new File(Data.hubDir(ctx), RECORD_FILE);
        String old = "";
        try {
            if (f.isFile()) old = Util.readText(f);
        } catch (Exception e) {
            Log.w(TAG, "read import log failed: " + e);
        }
        StringBuilder sb = new StringBuilder(old);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("  mode=").append(modeName(mode))
                .append("  file=").append(oneLine(displayName))
                .append("  fp=").append(fp == null ? "(none)" : normalizeOrRaw(fp))
                .append("  trust=").append(sourceName(source))
                .append("  action=").append(action == null ? "" : action);
        if (sb.length() > MAX_RECORD_CHARS) sb.delete(0, sb.length() - MAX_RECORD_CHARS);
        try {
            Util.atomicWriteText(f, sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "write import log failed: " + e);
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case SigMode.LOG: return "log";
            case SigMode.ASK: return "ask";
            default: return "reject";
        }
    }

    private static String normalizeOrRaw(String fp) {
        String n = normalize(fp);
        return n == null ? fp.trim() : n;
    }

    /** 记录是**一行一项**：把名字里的换行/回车压成空格，并限长。 */
    private static String oneLine(String s) {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > 120 ? v.substring(0, 120) + "..." : v;
    }
}
