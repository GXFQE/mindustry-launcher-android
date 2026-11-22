package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把一个 APK 收进对象池（按条目 CAS）+ 写清单。附录 E 的「导入」这一步。
 *
 * <p>做的三件事：</p>
 * <ol>
 *   <li>逐条目流式读 zip → {@link Pkgs#putStream}（边读边算 sha，命中已有对象就不落盘）；</li>
 *   <li>把每个条目的 sha/大小/CRC/时间记进清单（CRC 与时间给「关掉 CAS 时拼回去」用）；</li>
 *   <li>用 {@link ArcAbi#probe} 判这个版本能不能走同名类覆盖，结果一并写进清单。</li>
 * </ol>
 *
 * <p>源 APK 在这之后就没用了 —— 但**这一步不删它**：删原包是迁移动作（P3），
 * 而且删除前要确认清单已落盘、版本列表已能从清单读出来。</p>
 */
public final class PkgIngest {
    private static final String TAG = "MDTLauncher";

    private PkgIngest() {}

    /** 统计，仅用于日志/报告。 */
    public static final class Stats {
        public int entries;
        public int newObjects;
        public int reused;
        public long bytes;
        public long newBytes;
        public long ms;
        public boolean eligible;
        public String fingerprint;
        public String reason;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "%d entries / %.2f MB; new %d (%.2f MB), reused %d; %d ms; arc=%s%s",
                    entries, bytes / 1048576.0, newObjects, newBytes / 1048576.0, reused, ms,
                    eligible ? "ok" : "no", fingerprint == null ? "" : " (" + fingerprint + ")");
        }
    }

    /**
     * 入库。
     *
     * @param apk     源 APK（`.part` 也行：只读，不会被改）
     * @param vkey    版本 key（`import:v160.apk` / 包名）
     * @param label   显示名（可空）
     * @param version 版本号（可空）
     * @param md5     源 APK 的 md5（可空；读不出就不写）
     */
    public static PkgManifest ingest(Context ctx, File apk, String vkey, String label,
                                     String version, String md5, Stats out) throws IOException {
        if (apk == null || !apk.isFile()) throw new IOException("ingest: no apk");
        Pkgs pool = Pkgs.get(ctx);
        File override = OverrideDex.ensure(ctx);
        long t0 = System.currentTimeMillis();
        PkgManifest m = new PkgManifest();
        m.vkey = vkey;
        m.file = apk.getName();
        m.label = label;
        m.version = version;
        m.size = apk.length();
        m.added = System.currentTimeMillis();
        m.md5 = md5;

        long total = 0;
        long newBytes = 0;
        int newObjects = 0;
        int reused = 0;
        ZipFile zf = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e == null || e.isDirectory()) continue;
                String path = e.getName();
                if (path.startsWith("META-INF/")) continue;   // 签名/清单：拼回去也不可能同签名
                InputStream in = zf.getInputStream(e);
                String sha;
                boolean[] wrote = new boolean[1];
                try {
                    // sha=null ⇒ 现算；命中已有对象时 putStream 会提前返回，不落盘
                    sha = pool.putStream(in, null, wrote);
                } finally {
                    in.close();
                }
                long sz = e.getSize() < 0 ? 0 : e.getSize();
                if (wrote[0]) {
                    newObjects++;
                    newBytes += sz;
                } else {
                    reused++;
                }
                m.entries.add(new PkgManifest.Entry(path, sha, sz,
                        e.getCrc() < 0 ? 0 : e.getCrc(),
                        e.getTime() < 0 ? 0 : e.getTime()));
                total += sz;
            }
        } finally {
            zf.close();
        }

        try {
            m.apkSha256 = Pkgs.sha256Of(apk);
        } catch (IOException e) {
            m.apkSha256 = null;
        }

        ArcAbi.Result r = ArcAbi.probe(ctx, apk, override);
        m.arc = new PkgManifest.Arc(r.fingerprint, r.eligible, r.reason);
        // 远古包判定必须趁 APK 还在就做掉：删包之后 Compat.probe 无从下手，
        // 而「探不到就当远古包」会把整个槽改成改名交换布局（真机搬空过槽）。
        try {
            m.legacy = Compat.probe(apk).needsRename();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "pkg legacy probe failed, assume modern: " + t);
            m.legacy = false;
        }
        m.write(PkgManifest.fileOf(ctx, vkey));

        if (out != null) {
            out.entries = m.entries.size();
            out.newObjects = newObjects;
            out.reused = reused;
            out.bytes = total;
            out.newBytes = newBytes;
            out.ms = System.currentTimeMillis() - t0;
            out.eligible = r.eligible;
            out.fingerprint = r.fingerprint;
            out.reason = r.reason;
        }
        android.util.Log.i(TAG, "pkg ingest " + vkey + ": " + m.describe()
                + " in " + (System.currentTimeMillis() - t0) + " ms");
        return m;
    }

    /** 读清单；没有就返回 null。 */
    public static PkgManifest manifest(Context ctx, String vkey) {
        return PkgManifest.read(PkgManifest.fileOf(ctx, vkey));
    }

    /** 全部清单（GC 的引用集就从这里来）。 */
    public static java.util.List<PkgManifest> all(Context ctx) {
        java.util.List<PkgManifest> out = new java.util.ArrayList<PkgManifest>();
        File[] files = PkgManifest.dir(ctx).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".json")) continue;
            PkgManifest m = PkgManifest.read(f);
            if (m != null) out.add(m);
        }
        return out;
    }
}
