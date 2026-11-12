package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 361 轮：崩溃归因的「注入源」集合 —— 这一局注入进 {@code :game} 的**我们自己的代码**里，
 * 各自定义了哪些类。
 *
 * <p>为什么需要它：崩溃栈里只有类名，ART **不告诉**这个类来自哪个 dex。但注入是我们自己做的 ⇒
 * <ul>
 *   <li>宿主类名前缀固定（{@link CrashAnalysis#HOST_PKG}）—— 不用读文件；</li>
 *   <li>插件：生效中的 APK 就摆在 {@code app_hub/plugins/<id>/<ver>.apk}，读它的
 *       {@code classes*.dex} 的 {@code class_defs}（{@link PatchDex#classes}）；</li>
 *   <li>补丁：第 360 轮的启动记录（{@code report-patch-launch.json}）里存了**这一局实际注入**的
 *       dex 路径，读它的 {@code class_defs}。</li>
 * </ul>
 * 拿"栈帧类名 / 缺失符号的 owner 类"与这三份集合对一遍，就能把来源分出来。
 *
 * <p>★ 只在**便宜层没结论**时才调用（要读几 MB dex）：读不了 / 超限一律当没有 —— 归因失败
 * 只能退回"认不出"，**绝不许**因为读文件失败而崩掉分析。
 */
final class InjectionSources {

    private static final String TAG = "MDTLauncher";

    /** 单份 dex 解析上限：补丁 dex 正常 1~20 MB；超过就不读（归因不值得冒 OOM 风险） */
    private static final long MAX_DEX = 24L * 1024 * 1024;

    private InjectionSources() {}

    /**
     * 收集这一局的注入源。
     *
     * @param slot    崩的是哪个槽（null/空 ⇒ 不按槽过滤，取当前记录）
     * @param crashAt 崩溃报告写入时刻（毫秒；0 = 不按时间过滤）。启动记录的 {@code at} 晚于它
     *                ⇒ 说明这是崩完之后又启动过的一局，**不能**拿来归因。
     */
    static CrashAnalysis.Sources collect(Context ctx, String slot, long crashAt) {
        CrashAnalysis.Sources s = new CrashAnalysis.Sources();
        collectPatches(ctx, slot, crashAt, s);
        collectPlugins(ctx, s);
        return s;
    }

    /**
     * 补丁：只看**崩的那一局**（启动记录 {@code at} ≤ 崩溃报告写入时刻、槽对得上）。
     * 记录里的 dex 是注入时那份（内部存储、只读）；不存在/读不出就跳过。
     */
    private static void collectPatches(Context ctx, String slot, long crashAt,
                                       CrashAnalysis.Sources s) {
        try {
            // 第 365 轮：一局一条、按实例键存 ⇒ 按**崩的那个槽**取它自己那一局
            Patches.LaunchInfo info = Patches.readLaunchInfo(ctx, slot);
            if (info == null || info.at <= 0L || info.patches.isEmpty()) return;
            if (slot != null && !slot.isEmpty() && !info.slot.equals(slot)) return;
            if (crashAt > 0L && info.at > crashAt) return;      // 之后又启动过 ⇒ 不是这一局
            for (Patches.Launched l : info.patches) {
                if (l == null || l.dex.isEmpty()) continue;
                Set<String> classes = classesOfDex(new File(l.dex));
                if (classes.isEmpty()) continue;
                s.addPatch(l.label == null || l.label.isEmpty() ? l.ref : l.label, classes);
            }
        } catch (Throwable t) {
            Log.w(TAG, "injection sources: patch classes failed: " + t);
        }
    }

    /**
     * 插件：**生效中**的那一版（`active.txt` 的版本）。
     * {@code :game} 进程与主进程吃同一份注入（{@link LauncherApp} 的早注入），所以生效列表即候选。
     */
    private static void collectPlugins(Context ctx, CrashAnalysis.Sources s) {
        try {
            for (String id : Plugins.activeIds(ctx)) {
                String ver = Plugins.activeVersion(ctx, id);
                if (ver == null || ver.isEmpty()) continue;
                Set<String> classes = classesOfApk(Plugins.apkOf(ctx, id, ver));
                if (classes.isEmpty()) continue;
                s.addPlugin(Plugins.displayNameOf(ctx, id), classes);
            }
        } catch (Throwable t) {
            Log.w(TAG, "injection sources: plugin classes failed: " + t);
        }
    }

    private static Set<String> classesOfDex(File dex) {
        if (dex == null || !dex.isFile() || dex.length() <= 0 || dex.length() > MAX_DEX) {
            return new LinkedHashSet<String>();
        }
        try {
            return new LinkedHashSet<String>(PatchDex.classes(readAll(dex)));
        } catch (Throwable t) {
            Log.w(TAG, "injection sources: read dex failed: " + dex, t);
            return new LinkedHashSet<String>();
        }
    }

    /** 插件 APK：`classes*.dex` 全读（多 dex 插件也算得上）；单个超限就跳过那一个。 */
    private static Set<String> classesOfApk(File apk) {
        Set<String> out = new LinkedHashSet<String>();
        if (apk == null || !apk.isFile()) return out;
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e == null || e.isDirectory()) continue;
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                if (e.getSize() > MAX_DEX) continue;
                InputStream in = zf.getInputStream(e);
                try {
                    out.addAll(PatchDex.classes(readAll(in, e.getSize())));
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "injection sources: read plugin apk failed: " + apk, t);
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in, f.length());
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static byte[] readAll(InputStream in, long size) throws IOException {
        if (size <= 0 || size > MAX_DEX) throw new IOException("bad size: " + size);
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) size);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
