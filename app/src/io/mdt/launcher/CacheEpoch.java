package io.mdt.launcher;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 游戏 cache 的**身份与整目录清**（阶段 0.2，2026-11-15 第 382 轮）。
 *
 * <h3>要解决什么（单实例也会中）</h3>
 *
 * 游戏把可再生的东西写在 `getCacheDir()`：音乐 `<曲名>__<长度>.ogg`、V8 的 Java 模组 jar
 * `mods/&lt;mod&gt;/&lt;mtime&gt;.zip`、Rhino 编译产物、临时件。这些键**都不含版本 / 补丁 / 槽**：
 * <ul>
 *   <li>{@code mods/&lt;名&gt;/&lt;mtime&gt;.zip} 只看 名称+mtime+长度 ⇒ 换一份同名模组可能读到旧 jar；</li>
 *   <li>音乐 key 只看 名称+长度 ⇒ 换版本后同名同长度的曲子会串；</li>
 *   <li>补丁能整类替换 Java 与 assets ⇒ 它的启停 = 版本身份变化（plan §12.27/§12.28）；</li>
 *   <li>模组可以经 {@code Core.files.cache} / 直接 File 写任意东西 ⇒ **不能逐文件分类**，
 *       只能"整个实例 cache 一起清"（§12.28）。</li>
 * </ul>
 *
 * <h3>判据：CacheEpoch = 版本 + 补丁 + 模组</h3>
 *
 * <p>{@link #sig} 把**这一局真正会加载的东西**折成一个哈希：APK 文件身份（有补丁时再加
 * APK 的 sha256 —— 那是补丁唯一匹配键）、按注入顺序的补丁 dex / assets 文件身份、
 * 资产基线 / 增量、以及 `<槽>/mods/` 的 name+size+mtime 排序哈希。</p>
 *
 * <p>换算出来与上一次不同 ⇒ **清空 `getCacheDir()` 下所有条目**（不删根；不跟符号链接）。
 * `files/` / 槽数据 / `app_hub/**` 一个字节都不动 —— 模组的**持久数据**可能住在 `files/`，
 * 那不是缓存，清掉就是用户损失（§12.25/§12.28）。</p>
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>只清"无人用"的 cache</b>：还有**别的**游戏实例在跑就跳过（阶段 0 只有单实例，
 *       但这里不给未来挖坑；占位不算实例，见 {@link Instances}）。</li>
 *   <li><b>写 epoch 只在清成功之后</b>：清不干净就不记账，下次启动继续试 —— 反过来会留下
 *       "身份已经是新的、盘上还是旧的"这种没有症状的坏状态。</li>
 *   <li><b>epoch 文件住 cache 外面</b>（`app_hub/cache-epoch.txt`）：cache 被系统清掉之后
 *       我们仍知道自己是谁；而 cache 里的文件少了并不需要重建 epoch。</li>
 * </ol>
 *
 * <p>⚠️ 阶段 1 会把它搬到 `inst/&lt;key&gt;/epoch`（每实例一份）；阶段 0 是单实例，先放宿主级一个文件。
 *   `Storage` 的口径暂不动（0.5 再拆）。</p>
 */
final class CacheEpoch {
    private static final String TAG = "MDTLauncher";

    /** 记账文件（内部存储；cache 外面） */
    static final String FILE = "cache-epoch.txt";

    private CacheEpoch() {}

    // ── 记账文件 ──────────────────────────────────────────────────────────

    static File file(Context ctx) {
        return new File(Paths.privateDir(ctx), FILE);
    }

    /** 一局的签名（纯函数；输入是"这次真会加载的东西"） */
    static String sig(Injector.Plan plan, String apkSha) {
        StringBuilder sb = new StringBuilder();
        File apk = plan == null ? null : plan.apk;
        sb.append("apk=").append(ident(apk));
        if (apkSha != null && !apkSha.isEmpty()) sb.append("|sha=").append(apkSha);
        if (plan != null) {
            sb.append("\nbase=").append(ident(plan.assetBase));
            for (File d : plan.assetDeltas) sb.append("\ndelta=").append(ident(d));
            for (Injector.Patch p : plan.patches) {
                sb.append("\npatch=").append(p == null ? "" : p.ref)
                        .append('|').append(ident(p == null ? null : p.dex))
                        .append('|').append(ident(p == null ? null : p.assets));
            }
        }
        return Content.sha256(sb.toString());
    }

    /** `<槽>/mods/` 的 name+size+mtime 排序哈希（目录取递归体积；不跟链接） */
    static String modsSig(File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return "none";
        File[] fs = modsDir.listFiles();
        if (fs == null) return "unreadable";
        List<String> lines = new ArrayList<String>();
        for (File f : fs) {
            if (f == null) continue;
            long size = f.isDirectory() ? HubTmp.sizeNoFollow(f) : f.length();
            lines.add(f.getName() + "|" + size + "|" + f.lastModified());
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return Content.sha256(sb.toString());
    }

    /** 一局的身份串（写进记账文件；字段一行一个，读侧不比 JSON） */
    static String format(String vkey, String slot, String sig, String eff, String mods) {
        return "v=1\nvkey=" + clean(vkey) + "\nslot=" + clean(slot)
                + "\nsig=" + clean(sig) + "\neff=" + clean(eff)
                + "\nmods=" + clean(mods) + "\n";
    }

    // ── 主入口 ────────────────────────────────────────────────────────────

    /** 一次判定 / 清理的结果（启动报告与自检用） */
    static final class Result {
        /** 身份变了（= 这次清了）。 */
        boolean changed;
        /** 还有别的实例在跑 ⇒ 跳过（不写 epoch，下次再试）。 */
        boolean skippedOther;
        /** 清掉的**顶层条目**数。 */
        int cleared;
        /** 清掉的字节数。 */
        long bytes;
        /** 这次算出来的签名（短形式写报告）。 */
        String sig = "";

        String shortSig() {
            return sig.isEmpty() ? "?" : sig.substring(0, Math.min(12, sig.length()));
        }

        String brief() {
            if (skippedOther) return "跳过：还有别的实例在跑";
            if (!changed) return "身份未变，保留 cache";
            return "身份变了 ⇒ 清掉 cache 下 " + cleared + " 项 / " + Util.formatSize(bytes);
        }
    }

    /** 正式入口：真实 cache 目录 / 真实 epoch 文件 / 真实实例检查 */
    static Result prepare(Context ctx, String vkey, String slot, Injector.Plan plan, String apkSha) {
        return prepare(ctx, vkey, slot, plan, apkSha, null, null, null);
    }

    /**
     * 可注入版本（自检用）。
     *
     * @param cacheRoot    要清哪个目录（null = {@code ctx.getCacheDir()}）
     * @param epochFile    记账文件（null = {@link #file}）
     * @param otherRunning null = 现查 {@link #otherInstanceRunning}；自检传 FALSE 避开真设备状态
     */
    static Result prepare(Context ctx, String vkey, String slot, Injector.Plan plan, String apkSha,
                          File cacheRoot, File epochFile, Boolean otherRunning) {
        Result r = new Result();
        String eff = sig(plan, apkSha);
        String mods = modsSig(modsDirOf(ctx, slot));
        r.sig = Content.sha256(eff + "|" + mods);
        File epoch = epochFile == null ? file(ctx) : epochFile;
        if (otherRunning == null) otherRunning = otherInstanceRunning(ctx);
        if (otherRunning.booleanValue()) {
            r.skippedOther = true;
            note(ctx, vkey, slot, r);
            return r;
        }
        String want = format(vkey, slot, r.sig, eff, mods);
        String have = "";
        try {
            if (epoch.isFile()) have = Util.readText(epoch);
        } catch (Throwable t) {
            Log.w(TAG, "cache epoch read failed: " + epoch, t);
        }
        if (have != null && have.trim().equals(want.trim())) {
            r.changed = false;
            note(ctx, vkey, slot, r);
            return r;
        }
        File cache = cacheRoot == null ? ctx.getCacheDir() : cacheRoot;
        boolean ok = cleanChildren(cache, r);
        r.changed = true;
        if (ok) {
            try {
                Util.atomicWriteText(epoch, want);
            } catch (Throwable t) {
                // 记账失败 ⇒ 下次会再清一次（方向安全）；但要说出来，否则排查时看不到这层
                Log.w(TAG, "cache epoch write failed: " + epoch, t);
            }
        }
        note(ctx, vkey, slot, r);
        return r;
    }

    // ── 清理 ──────────────────────────────────────────────────────────────

    /** 清空 `cache` 下的**所有条目**（不删根；不跟符号链接；只读先放开）。返回是否全部删掉 */
    private static boolean cleanChildren(File cache, Result r) {
        if (cache == null || !cache.isDirectory()) return true;
        File[] fs = cache.listFiles();
        if (fs == null) return false;
        boolean ok = true;
        for (File f : fs) {
            if (f == null) continue;
            long len = HubTmp.sizeNoFollow(f);
            if (HubTmp.deleteNoFollow(f)) {
                r.cleared++;
                r.bytes += len;
            } else {
                ok = false;
                Log.w(TAG, "cache clean: cannot delete " + f);
            }
        }
        return ok;
    }

    /**
     * 除了本进程以外，还有没有**别的游戏实例**在跑。
     *
     * <p>占位（proc 为空）不算：它是主进程刚点下启动时写的，属于这一局自己。
     * 拿不准时返回 true（宁可这次不清，也不要动别人正在用的 cache —— 与 {@link Instances} 同一方向）。</p>
     */
    static boolean otherInstanceRunning(Context ctx) {
        try {
            List<Instances.Running> list = Instances.live(ctx);
            int me = Process.myPid();
            for (Instances.Running r : list) {
                if (r == null || r.pid == me) continue;
                if (Data.isGameProcessName(ctx, r.proc)) return true;
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "cache epoch: instance check failed", t);
            return true;
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private static String ident(File f) {
        if (f == null) return "none";
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    /** 某个槽的 `mods/`（只算路径，不 mkdirs） */
    static File modsDirOf(Context ctx, String slot) {
        File root = Data.dirOf(ctx, slot);
        return root == null ? null : new File(root, "mods");
    }

    /** 记账文件里不能有换行（字段一行一个） */
    private static String clean(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 给真机取证留一行（`hub/report-cache.txt`，只留最新一次） */
    private static void note(Context ctx, String vkey, String slot, Result r) {
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date())
                + "  " + vkey + " / " + slot
                + "  sig=" + r.shortSig()
                + "  " + r.brief() + "\n";
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), "report-cache.txt"), line);
        } catch (Throwable t) {
            Log.w(TAG, "cache epoch report write failed", t);
        }
        Log.i(TAG, "cache epoch: " + line.trim());
    }
}
