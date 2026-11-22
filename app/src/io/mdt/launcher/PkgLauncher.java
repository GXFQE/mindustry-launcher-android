package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按清单从对象池装配一次启动（附录 E 的「启动」这一步）。
 *
 * <p>只有三个前提同时成立才会启用：</p>
 * <ol>
 *   <li>设置里开了「游戏包去重」（{@link Config#casGameEnabled()}）；</li>
 *   <li>这个版本有清单，且清单里的 ABI 判定是 eligible；</li>
 *   <li>{@link OverrideDex#ensure} 能拿到覆盖 dex。</li>
 * </ol>
 *
 * <p>全成立就把三样东西从池里取：dexPath（覆盖 dex + 所有 `classes*.dex` 对象）、
 * `lib/<abi>/*.so`、以及 `AndroidFi` 的资产映射。有一条不成立就返回 null，
 * 调用方照旧用 APK —— 失败方向是「退回去用原包」，不会变砖。</p>
 */
public final class PkgLauncher {
    private static final String TAG = "MDTLauncher";
    public static final String GAME_FI = "arc.backend.android.AndroidFi";

    private PkgLauncher() {}

    /** 能走池就返回清单，否则 null。 */
    public static PkgManifest lookup(Context ctx, String vkey) {
        if (vkey == null || vkey.isEmpty()) return null;
        if (!Config.get().casGameEnabled()) return null;
        PkgManifest m;
        try {
            m = PkgIngest.manifest(ctx, vkey);
        } catch (Throwable t) {
            return null;
        }
        if (m == null) return null;
        if (m.arc == null || !m.arc.eligible) return null;
        if (OverrideDex.ensure(ctx) == null) return null;
        return m;
    }

    /** 覆盖 dex 在池里（`lookup` 通过后必定存在）。 */
    public static File overrideDex(Context ctx) {
        return Pkgs.get(ctx).overrideDex();
    }

    /** `override.dex:objects/<..>/classes.dex:...`（顺序即优先级，覆盖 dex 必须在最前）。 */
    public static String dexPath(Context ctx, PkgManifest m) {
        Pkgs pool = Pkgs.get(ctx);
        StringBuilder sb = new StringBuilder();
        File ov = pool.overrideDex();
        if (ov != null && ov.isFile()) sb.append(ov.getAbsolutePath());
        for (PkgManifest.Entry e : m.entries) {
            if (!m.isDex(e.path)) continue;
            File f = pool.object(e.sha);
            if (f == null || !f.isFile()) continue;
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(f.getAbsolutePath());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 设备 ABI 列表里第一个在清单中有 `lib/` 条目的 ABI。 */
    public static String pickAbi(PkgManifest m) {
        String[] abis;
        try {
            abis = Compat.deviceAbis();
        } catch (Throwable t) {
            abis = null;
        }
        if (abis == null || abis.length == 0) abis = new String[]{"arm64-v8a", "armeabi-v7a"};
        for (String abi : abis) {
            if (m.lib(abi, "libarc.so") != null) return abi;
        }
        return null;
    }

    /** `lib/<abi>/<name>` 对应的池对象；没有就 null。 */
    public static File nativeFile(Context ctx, PkgManifest m, String abi, String name) {
        PkgManifest.Entry e = m.lib(abi, name);
        if (e == null) return null;
        File f = Pkgs.get(ctx).object(e.sha);
        return f != null && f.isFile() ? f : null;
    }

    /**
     * 资产路径 → 池对象文件，喂给同名 AndroidFi。
     *
     * <p>两类都要进：`assets/<x>` 去掉前缀变成 `<x>`；以及 **APK 根下的非 dex 文件**
     * （`vfxshaders/*.vert|frag`、`res/*`、`resources.arsc` …）—— 它们走的是 arc 的
     * `FileType.classpath`，而同名覆盖把 classpath 也接到池上了。漏掉这类文件的后果是
     * 一进游戏就 `File not found: vfxshaders/screenspace.vert (classpath)`。</p>
     */
    public static Map<String, String> assetMap(Context ctx, PkgManifest m) {
        Pkgs pool = Pkgs.get(ctx);
        Map<String, String> map = new HashMap<String, String>();
        String prefix = "assets/";
        for (PkgManifest.Entry e : m.entries) {
            String key;
            if (e.path.startsWith(prefix)) {
                key = e.path.substring(prefix.length());
            } else if (e.path.startsWith("lib/") || e.path.startsWith("META-INF/")
                    || m.isDex(e.path)) {
                continue;
            } else {
                key = e.path;
            }
            File f = pool.object(e.sha);
            if (f == null || !f.isFile()) continue;
            map.put(key, f.getAbsolutePath());
        }
        return map;
    }

    /**
     * 把资产映射灌进游戏里的 `AndroidFi`（同名类覆盖的入口）。
     *
     * <p>必须在 appendDexElements **之后**、游戏第一次读 internal 之前调用 ——
     * 也就是 `Injector.injectDex` 的尾部。</p>
     */
    public static void attach(Context ctx, ClassLoader baseCl, PkgManifest m) {
        try {
            Map<String, String> map = assetMap(ctx, m);
            Class<?> fi = Class.forName(GAME_FI, true, baseCl);
            Method init = fi.getMethod("init", Map.class);
            init.invoke(null, map);
            android.util.Log.i(TAG, "pkg: AndroidFi pool=" + map.size() + " entries");
        } catch (Throwable t) {
            // 灌不进去 ⇒ 资产读不到，游戏会在第一次读资产时炸；这里只留痕，让启动报告带上原因。
            android.util.Log.w(TAG, "pkg: AndroidFi init failed", t);
        }
    }

    /** 供 `report-launch` 用的一行摘要。 */
    public static String describe(Context ctx, PkgManifest m) {
        long[] st = Pkgs.get(ctx).stats();
        return String.format(java.util.Locale.US,
                "%s  entries=%d  objects=%d (%.1f MB)  arc=%s",
                m.vkey, m.entries.size(), st[0], st[1] / 1048576.0,
                m.arc != null && m.arc.eligible ? "elig" : "no");
    }
}
