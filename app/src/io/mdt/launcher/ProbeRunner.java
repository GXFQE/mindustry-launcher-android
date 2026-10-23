package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mdt.launcher.api.Probe;
import io.mdt.launcher.api.ProbeEnv;

/**
 * 探针调度器（v23）：加载插件声明的探针、定时 `sample()`、超时/异常隔离、连续失败停用。
 *
 * <p>同一份实现跑在两种进程里：
 * <ul>
 *   <li>`inProcess=true` ⇒ 由 {@link GameSlot} 在 `:game` 里启动（能反射游戏类）；</li>
 *   <li>`inProcess=false` ⇒ 由 {@link ProbeService} 在 `:probe` 里启动（只能读 `/proc`）。</li>
 * </ul>
 * 数据目录 = `<hub>/usage/telemetry/<session>/<pluginId>/`；插件自己写，宿主不解析。
 * 宿主只额外写 {@code probe-fail.txt}（失败/停用记录）。
 */
final class ProbeRunner {

    /** 单次 sample() 的上限（超过记一次失败）。 */
    private static final long SAMPLE_TIMEOUT_MS = 200;
    /** 连续失败多少次停用该探针。 */
    private static final int MAX_FAILS = 3;

    private final Context ctx;
    private final File sessionDir;
    private final int pid;
    private final boolean inProcess;
    private final String slot;
    private final List<Job> jobs = new ArrayList<Job>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService work = Executors.newCachedThreadPool();
    private volatile boolean stopped;

    private ProbeRunner(Context ctx, File sessionDir, int pid, boolean inProcess, String slot) {
        this.ctx = ctx;
        this.sessionDir = sessionDir;
        this.pid = pid;
        this.inProcess = inProcess;
        this.slot = slot;
    }

    /**
     * 起一轮调度。
     *
     * @param pid      要观察的进程（进程内 = 自己；隔离进程 = 游戏进程）
     * @param inProcess true = 住在 `:game`；false = 住在 `:probe`
     * @param session  本会话的目录名（调用方给，通常是 `yyyyMMdd-HHmmss-<槽>`）
     */
    static ProbeRunner start(Context ctx, int pid, boolean inProcess, String session) {
        File base = new File(new File(Data.hubDir(ctx), "usage/telemetry"), session);
        ProbeRunner r = new ProbeRunner(ctx, base, pid, inProcess, Data.currentSlot(ctx));
        r.boot();
        return r;
    }

    private void boot() {
        try {
            if (!sessionDir.isDirectory() && !sessionDir.mkdirs()) {
                Log.w("MDTPlugin", "probe session dir failed: " + sessionDir);
                return;
            }
        } catch (Throwable t) {
            Log.w("MDTPlugin", "probe session dir failed", t);
            return;
        }
        for (final PluginProbes.Spec s : PluginProbes.all(ctx)) {
            if (s.inProcess != inProcess) continue;
            try {
                File apk = Plugins.apkOf(ctx, s.pluginId, s.ver);
                if (apk == null || !apk.isFile()) throw new java.io.FileNotFoundException("apk");
                // 插件 APK 住在内部存储且是只读副本 ⇒ Android 14+ 允许直接 DexClassLoader
                dalvik.system.DexClassLoader dl = new dalvik.system.DexClassLoader(
                        apk.getAbsolutePath(), null, null, ctx.getClassLoader());
                Class<?> c = Class.forName(s.cls, true, dl);
                Object o = c.getDeclaredConstructor().newInstance();
                if (!(o instanceof Probe)) {
                    throw new ClassCastException(s.cls + " does not implement Probe");
                }
                Probe p = (Probe) o;
                File dir = new File(sessionDir, safe(s.pluginId));
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new java.io.IOException("mkdirs: " + dir);
                }
                p.reset(new ProbeEnv(dir, pid, inProcess, slot));
                Job j = new Job(s, p);
                jobs.add(j);
                timer.scheduleAtFixedRate(new Runnable() {
                    @Override public void run() {
                        tick(j);
                    }
                }, 1, s.sec, TimeUnit.SECONDS);
                Log.i("MDTPlugin", "probe start: " + s.pluginId + " " + s.cls
                        + " sec=" + s.sec + " inProcess=" + inProcess);
            } catch (Throwable t) {
                fail(s, "load: " + t);
            }
        }
        writeManifest();
    }

    /** 把这一轮参与会话的探针写进 `probes.txt`（崩溃提示与以后的事后统计都读它）。 */
    private void writeManifest() {
        if (jobs.isEmpty()) return;
        try {
            FileOutputStream out = new FileOutputStream(new File(sessionDir, "probes.txt"), true);
            try {
                for (Job j : jobs) {
                    PluginProbes.Spec s = j.spec;
                    boolean third = PluginTrust.THIRD.equals(
                            Plugins.trustOf(ctx, s.pluginId, s.ver));
                    out.write((s.pluginId + "\t" + s.pluginName + "\t" + (third ? 1 : 0)
                            + "\t" + (s.inProcess ? 1 : 0) + "\n").getBytes("UTF-8"));
                }
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 最近一次**本槽**会话里参与过的探针（给崩溃提示用；没有/太旧 ⇒ null）。
     *
     * <p>读 `telemetry/<会话>/probes.txt`，按插件去重；只在 24 小时内、且会话名以
     * `-<槽>` 结尾时采用（旧会话不该给新崩溃背锅）。
     */
    static String sessionNote(Context ctx, String slot) {
        try {
            File tel = new File(Data.hubDir(ctx), "usage/telemetry");
            File[] dirs = tel.listFiles();
            if (dirs == null) return null;
            File best = null;
            long bestT = 0;
            for (File d : dirs) {
                if (d == null || !d.isDirectory()) continue;
                if (slot != null && !slot.isEmpty() && !d.getName().endsWith("-" + slot)) continue;
                File pf = new File(d, "probes.txt");
                if (!pf.isFile()) continue;
                long t = pf.lastModified();
                if (t > bestT) {
                    bestT = t;
                    best = pf;
                }
            }
            if (best == null || System.currentTimeMillis() - bestT > 24L * 3600 * 1000) return null;
            java.util.LinkedHashMap<String, String> items =
                    new java.util.LinkedHashMap<String, String>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(best), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t");
                    if (p.length < 4) continue;
                    String id = p[0];
                    String name = p[1];
                    boolean third = "1".equals(p[2]);
                    boolean inProc = "1".equals(p[3]);
                    String where = Trans.get(ctx, inProc ? R.string.probe_note_where_in
                            : R.string.probe_note_where_iso);
                    String item = Trans.get(ctx, third ? R.string.probe_note_third_fmt
                            : R.string.probe_note_official_fmt, name, where);
                    items.put(id, item);
                }
            } finally {
                try { r.close(); } catch (Throwable ignored) { }
            }
            if (items.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            String sep = Trans.get(ctx, R.string.list_join_sep);
            for (String item : items.values()) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(item);
            }
            return Trans.get(ctx, R.string.probe_note_fmt, sb.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    private void tick(final Job j) {
        if (stopped || j.disabled) return;
        Future<?> f = work.submit(new Runnable() {
            @Override public void run() {
                j.probe.sample();
            }
        });
        try {
            f.get(SAMPLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            j.fails = 0;
        } catch (TimeoutException e) {
            f.cancel(true);
            failTick(j, "timeout >" + SAMPLE_TIMEOUT_MS + "ms");
        } catch (Throwable t) {
            failTick(j, String.valueOf(t));
        }
    }

    private void failTick(Job j, String why) {
        j.fails++;
        fail(j.spec, why + " (consecutive " + j.fails + ")");
        if (j.fails >= MAX_FAILS) {
            j.disabled = true;
            fail(j.spec, "disabled after " + j.fails + " consecutive failures");
        }
    }

    private void fail(PluginProbes.Spec s, String why) {
        Log.w("MDTPlugin", "probe " + s.pluginId + " " + s.cls + ": " + why);
        try {
            FileOutputStream out = new FileOutputStream(new File(sessionDir, "probe-fail.txt"), true);
            try {
                out.write(("F " + System.currentTimeMillis() + " " + s.pluginId + " "
                        + s.cls + " " + why + "\n").getBytes("UTF-8"));
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 停掉定时器；给还活着的探针一次 summary()（异常全吞）。 */
    void stop() {
        if (stopped) return;
        stopped = true;
        try { timer.shutdownNow(); } catch (Throwable ignored) { }
        for (Job j : jobs) {
            if (j.disabled) continue;
            try { j.probe.summary(); } catch (Throwable ignored) { }
        }
        try { work.shutdownNow(); } catch (Throwable ignored) { }
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "plugin";
        return s.replace('/', '_').replace('\\', '_');
    }

    private static final class Job {
        final PluginProbes.Spec spec;
        final Probe probe;
        int fails;
        boolean disabled;

        Job(PluginProbes.Spec spec, Probe probe) {
            this.spec = spec;
            this.probe = probe;
        }
    }
}
