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
 * <p>⚠️ 边界（P1 时的记录，P3 起已变）：当时**开启不删原包**，因为界面还有几处直接读 APK；
 * P2 的虚拟包层（{@link Pkg}）把那些点位堵上之后，P3 改成「入库成功后删掉原包」——
 * 包体本来就在池子里，关掉开关时由 {@link PkgRestore} 拼回来（字节不保证与原件一致，
 * 但能启动、能导出）。删之前一定先确认清单可用，否则等于把用户的包弄丢。</p>
 */
public final class PkgAdmin {
    private static final String TAG = "MDTLauncher";

    public static final class Result {
        public int files;
        public int ingested;
        public int skipped;
        /** 入库后成功删掉的原始 APK 个数（P3 起 > 0） */
        public int deleted;
        public long bytes;
        /** 删原包实际回收的字节（= 被删 APK 大小之和） */
        public long reclaimed;
        public long poolBytes;
        public int poolObjects;
        public long ms;
        public String error;

        public String describe() {
            return "files=" + files + " ingested=" + ingested + " skipped=" + skipped
                    + " deleted=" + deleted + " bytes=" + bytes + " reclaimed=" + reclaimed
                    + " pool=" + poolObjects + " (" + fmt(poolBytes) + ")"
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
                PkgManifest m = PkgIngest.ingest(ctx, apk, vkey, label, version, md5, st);
                r.ingested++;
                r.bytes += apk.length();
                // ★ P3：入库成功才删原包 —— 判据是「清单写出来了 + 池里条目齐全」，
                //   只要有一条不成立就不删（宁可这次不省空间，也不能把包弄丢）。
                if (m != null && m.arc != null && m.arc.eligible && PkgRestore.complete(ctx, m)
                        && Config.get().casGameEnabled()) {
                    long len = apk.length();
                    apk.setWritable(true);
                    if (apk.delete()) {
                        r.deleted++;
                        r.reclaimed += len;
                    } else {
                        Log.w(TAG, "cas enable: cannot delete " + apk);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "cas enable: ingest failed for " + name + ": " + t);
                r.error = String.valueOf(t.getMessage());
            }
        }
        long[] st = Pkgs.get(ctx).stats();
        r.poolObjects = (int) st[0];
        r.poolBytes = st[1];
        // 删完包顺手清掉 oat/profile 残留（否则存储页会出现"0 个游戏包 · 60.6 MB"）
        sweepImportLeftovers(ctx);
        r.ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "cas enable: " + r.describe());
        return r;
    }

    /**
     * 关闭：**先把每个版本拼回 APK**，全部成功之后才关开关、删清单、删对象。
     *
     * <p>顺序：① 拼回（失败就整体中止，池子一个字节不动 —— 否则包就真没了）
     * ② `Config.setCasGameEnabled(false)` 让 {@link PkgLauncher#lookup} 开始返回 null
     * ③ 删清单 ④ 删对象。</p>
     *
     * <p>⚠️ 拼回来的 APK 不是原件（不保签名、全 STORED、带 4 字节对齐填充），
     * 但能启动、能被导出；{@code apkSha256} 会变，见 {@link PkgRestore}。</p>
     *
     * @return 释放的字节数；**-1 表示有包没拼回来、已中止**（池子保留原样）
     */
    public static long disable(Context ctx) {
        // ① 先把池里的东西拼回 import/ —— 少了这步，关掉开关等于把包删了
        java.util.List<PkgManifest> all = PkgIngest.all(ctx);
        for (PkgManifest m : all) {
            File apk = new File(Importer.importDir(ctx), m.file);
            if (apk.isFile() && apk.length() >= 1024) continue;   // 原件还在 ⇒ 不用拼
            File back = PkgRestore.rebuild(ctx, m);
            if (back == null) {
                Log.w(TAG, "cas disable: rebuild failed for " + m.file + ", keep pool");
                return -1L;
            }
        }
        // ② 关开关
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
        // 顺带清 import/ 里的 oat/profile 残留（APK 已删的那些）
        freed += sweepImportLeftovers(ctx);
        Log.i(TAG, "cas gc: live=" + live.size() + " freed=" + freed + " B");
        return freed;
    }

    /**
     * 清 `import/` 里跟着 APK 一起该走的残留：ART 编译出来的 `oat/<abi>/<名字>.{odex,vdex}`、
     * 以及 `<名字>.apk.cur.prof` 这类松散的 profile 文件。
     *
     * <p>为什么需要：删掉 APK 之后这些不会自己消失（真机上量到过 62 MB），
     * 而存储占用页只数 `.apk` ⇒ 会显示「0 个游戏包 · 60.6 MB」这种自相矛盾的数字。
     * 判据很保守：**只删「对应的 .apk 已经不在」的那些**，正在导入的 `.part` 一律跳过。</p>
     *
     * @return 释放的字节数（只用于日志）
     */
    static long sweepImportLeftovers(Context ctx) {
        File dir = Importer.importDir(ctx);
        if (dir == null || !dir.isDirectory()) return 0;
        long freed = 0;
        // ① oat/<abi>/<stem>.{odex,vdex}
        File oat = new File(dir, "oat");
        File[] abis = oat.isDirectory() ? oat.listFiles() : null;
        if (abis != null) {
            for (File abi : abis) {
                if (abi == null || !abi.isDirectory()) continue;
                File[] fs = abi.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (f == null || !f.isFile()) continue;
                    String stem = stemOf(f.getName());
                    if (stem == null) continue;
                    if (new File(dir, stem + ".apk").isFile()) continue;
                    long len = f.length();
                    if (f.delete()) freed += len;
                    else Log.w(TAG, "cas sweep: cannot delete " + f);
                }
                File[] left = abi.listFiles();
                if (left != null && left.length == 0) abi.delete();
            }
        }
        // ② 松散文件：<名字>.apk.cur.prof 之类（绝不碰 .part，那是正在拷的包）
        File[] loose = dir.listFiles();
        if (loose != null) {
            for (File f : loose) {
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                if (n.endsWith(".part")) continue;
                int i = n.indexOf(".apk.");
                if (i <= 0) continue;
                String apk = n.substring(0, i + 4);
                if (new File(dir, apk).isFile()) continue;
                long len = f.length();
                if (f.delete()) freed += len;
                else Log.w(TAG, "cas sweep: cannot delete " + f);
            }
        }
        if (freed > 0) Log.i(TAG, "cas sweep: freed " + freed + " B of stale import leftovers");
        return freed;
    }

    /** `v1607.odex` → `v1607`；不是 odex/vdex 返回 null。 */
    private static String stemOf(String name) {
        if (name.endsWith(".odex")) return name.substring(0, name.length() - 5);
        if (name.endsWith(".vdex")) return name.substring(0, name.length() - 5);
        return null;
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
