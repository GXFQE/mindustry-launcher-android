package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 「游戏包去重」这个开关背后的两件事：**开启**（把现有导入包全部收进对象池）
 * 与**关闭**（把池子和清单丢掉）。
 *
 * <p>为什么单独一个类：这两步都要遍历/删除用户数据，必须能在一处看全，
 * 也必须在后台线程跑（{@code PkgIngest} 一个 78 MB 包的入库就是几百毫秒）。
 * UI 只负责确认与提示，见 {@code SettingsActivity.promptCasGame}。</p>
 *
 * <p>⚠️ 现阶段的边界（P1）：**开启时不会删掉 import 目录里的原始 APK** ——
 * 启动链路上还有几处（界面读地图/蓝图缩略图那 8 个点位）仍直接读 APK，
 * 删包之后那些页面会拿不到数据（P2 的虚拟包层才堵这个口子）。所以此刻
 * 开启只意味着「启动改走池」，省空间要等删包那一步。</p>
 */
public final class PkgAdmin {
    private static final String TAG = "MDTLauncher";

    public static final class Result {
        public int files;
        public int ingested;
        public int skipped;
        public long bytes;
        public long poolBytes;
        public int poolObjects;
        public long ms;
        public String error;

        public String describe() {
            return "files=" + files + " ingested=" + ingested + " skipped=" + skipped
                    + " bytes=" + bytes + " pool=" + poolObjects + " (" + fmt(poolBytes) + ")"
                    + " in " + ms + " ms";
        }
    }

    private PkgAdmin() {
    }

    /** 开启：把 `import/` 里每个 APK 收进池子（已入过且 apkSha 未变的跳过）。 */
    public static Result enable(Context ctx) {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        File dir = Importer.importDir(ctx);
        File[] all = dir == null ? null : dir.listFiles();
        if (all == null) {
            r.error = "no import dir";
            return r;
        }
        File override = OverrideDex.ensure(ctx);
        if (override == null) {
            r.error = "override.dex missing";
            return r;
        }
        for (File apk : all) {
            if (apk == null || !apk.isFile()) continue;
            String name = apk.getName();
            if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
            if (apk.length() < 1024) {   // 与 Importer 的判据一致：壳包不碰
                r.skipped++;
                continue;
            }
            r.files++;
            String vkey = "import:" + name;
            JSONObject entry = entryOf(ctx, name);
            String md5 = entry == null ? null : entry.optString("md5", null);
            try {
                String sha = Pkgs.sha256Of(apk);
                PkgManifest old = PkgIngest.manifest(ctx, vkey);
                if (old != null && sha != null && sha.equals(old.apkSha256)
                        && old.arc != null && old.arc.eligible) {
                    r.skipped++;
                    continue;
                }
                String label = entry == null ? stripExt(name) : entry.optString("label", stripExt(name));
                String version = entry == null ? null : entry.optString("version", null);
                PkgIngest.Stats st = new PkgIngest.Stats();
                PkgIngest.ingest(ctx, apk, vkey, label, version, md5, st);
                r.ingested++;
                r.bytes += apk.length();
            } catch (Throwable t) {
                Log.w(TAG, "cas enable: ingest failed for " + name + ": " + t);
                r.error = String.valueOf(t.getMessage());
            }
        }
        long[] st = Pkgs.get(ctx).stats();
        r.poolObjects = (int) st[0];
        r.poolBytes = st[1];
        r.ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "cas enable: " + r.describe());
        return r;
    }

    /**
     * 关闭：关掉开关 → 删清单 → 删对象。
     *
     * <p>顺序不能反：先让 {@link PkgLauncher#lookup} 开始返回 null，再动文件 ——
     * 否则删到一半正好有新一轮启动读到半死的池子。**只动池子，不碰 `import/`**
     * （原始 APK 是用户的东西，删不删走「删版本」那条路，有中转站可放回）。</p>
     */
    public static long disable(Context ctx) {
        Config.get().setCasGameEnabled(false);
        long freed = 0;
        File mdir = PkgManifest.dir(ctx);
        File[] ms = mdir == null ? null : mdir.listFiles();
        if (ms != null) {
            for (File f : ms) {
                if (f == null || !f.isFile()) continue;
                if (!f.getName().endsWith(".json") && !f.getName().endsWith(".part")) continue;
                freed += f.length();
                if (!f.delete()) Log.w(TAG, "cas disable: manifest delete failed: " + f);
            }
        }
        Pkgs pool = Pkgs.get(ctx);
        File objs = pool.objects();
        File[] shards = objs == null ? null : objs.listFiles();
        if (shards != null) {
            for (File shard : shards) {
                if (shard == null) continue;
                if (shard.isDirectory()) {
                    File[] fs = shard.listFiles();
                    if (fs != null) {
                        for (File f : fs) {
                            if (f == null || !f.isFile()) continue;
                            freed += f.length();
                            f.setWritable(true);
                            if (!f.delete()) Log.w(TAG, "cas disable: object delete failed: " + f);
                        }
                    }
                    shard.delete();
                }
            }
        }
        File od = pool.overrideDex();
        if (od != null && od.isFile()) {
            freed += od.length();
            od.setWritable(true);
            od.delete();
        }
        Log.i(TAG, "cas disable: freed " + freed + " B");
        return freed;
    }

    /** 清一次孤儿对象（引用集 = 全部清单的 sha；正在跑的实例不会用到池里的临时件）。 */
    public static long collect(Context ctx) {
        Set<String> live = new HashSet<String>();
        for (PkgManifest m : PkgIngest.all(ctx)) {
            for (PkgManifest.Entry e : m.entries) live.add(e.sha);
        }
        long freed = Pkgs.get(ctx).gc(live);
        Log.i(TAG, "cas gc: live=" + live.size() + " freed=" + freed + " B");
        return freed;
    }

    /** 池子现在省了多少：所有清单对应 APK 的大小之和 − 池子实际占用。 */
    public static long savedBytes(Context ctx) {
        long raw = 0;
        for (PkgManifest m : PkgIngest.all(ctx)) raw += m.size;
        long[] st = Pkgs.get(ctx).stats();
        long saved = raw - st[1];
        return saved < 0 ? 0 : saved;
    }

    public static int versionCount(Context ctx) {
        return PkgIngest.all(ctx).size();
    }

    private static JSONObject entryOf(Context ctx, String file) {
        JSONArray arr = Config.get().imports();
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && file.equals(e.optString("file", null))) return e;
        }
        return null;
    }

    private static String stripExt(String name) {
        if (name == null) return "";
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
