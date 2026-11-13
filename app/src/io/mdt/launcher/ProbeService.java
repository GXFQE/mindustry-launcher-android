package io.mdt.launcher;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * `:probe` 隔离进程的**骨架 + 可读性验证**（2026-10-23 第 222 轮）。
 *
 * <p>为什么单独一个进程：探针代码崩了只死探针进程，不连带游戏；代价是拿不到 Java 堆/GC/帧时间
 * （那些只有进程内能做）。它能做的是 `/proc/<游戏 pid>/…` 这类**进程级**指标。
 *
 * <p>本轮只验证"同 UID 的另一个进程能不能读游戏的 `/proc`"：结果写
 * `hub/report-probe.txt`（外部 hub，产品版也能拉）。验证完这个类继续当探针进程的宿主。
 *
 * <p>⚠️ 触发口：dev 直通 `--es dev_probetest 1`（产品版没有这个口）。
 */
public class ProbeService extends Service {

    /** 0 / 不给 ⇒ 自己找 `:game` 进程。 */
    static final String EXTRA_PID = "pid";
    /** true ⇒ 起探针调度器（正式路径）；false ⇒ 只做一次 /proc 可读性报告（验证台）。 */
    static final String EXTRA_RUN = "run";
    /** 会话目录名（两边共用）。 */
    static final String EXTRA_SESSION = "session";
    /** v30：这一局的版本 key（探针按版本分账；拿不到就不放这个 extra）。 */
    static final String EXTRA_VERSION = "version";
    /** v31：这一局的版本信息快照（Bundle；探针写账本用；拿不到就不放）。 */
    static final String EXTRA_VINFO = "vinfo";
    /** 第 371 轮：这一局实际用的槽 —— 探针按实例记账，不许在 :probe 里猜全局当前槽。 */
    static final String EXTRA_SLOT = "slot";

    /**
     * 正在跑的会话（pid → runner）。
     *
     * <p>★ 第 371 轮（多开前置）：一个 `:probe` 服务会先后承载**多个实例**的探针会话
     * （换版本时旧会话退出、新会话起来）。以前 watcher 收尾用无参 `stopSelf()`，
     * 会把刚起的新会话一起端掉；现在按"还有没有别的会话"决定收不收。
     */
    private final java.util.Map<Integer, ProbeRunner> mRunners =
            new java.util.LinkedHashMap<Integer, ProbeRunner>();

    /**
     * 会话目录名 = `yyyyMMdd-HHmmss-<槽>`（进程内 / `:probe` 两边共用同一目录）。
     * 抽出来只为一件事：让自检能钉住"名字里带的是这一局的槽"。
     */
    static String sessionName(Context ctx, String slot) {
        String use = (slot == null || slot.trim().isEmpty())
                ? Data.currentSlot(ctx) : slot.trim();
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + "-" + use;
    }

    /**
     * 正式路径的启动 Intent（抽出来让自检钉住 pid / 会话 / 槽 / 版本都带对了）。
     * 槽为空时也放一个空串 —— 让 `:probe` 走 `ProbeRunner` 的"当前槽"兜底，而不是靠调用方猜。
     */
    static Intent runIntent(Context ctx, int pid, String session, String slot,
                            String version, Bundle vinfo) {
        Intent i = new Intent(ctx, ProbeService.class);
        i.putExtra(EXTRA_PID, pid);
        i.putExtra(EXTRA_RUN, true);
        i.putExtra(EXTRA_SESSION, session);
        i.putExtra(EXTRA_SLOT, slot == null ? "" : slot);
        i.putExtra(EXTRA_VERSION, version);
        if (vinfo != null) i.putExtra(EXTRA_VINFO, vinfo);
        return i;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** Bundle → 字符串 map（与 GameSlot 那份同构；探针只认字符串）。 */
    private static Map<String, String> mapOf(Bundle b) {
        Map<String, String> m = new TreeMap<String, String>();
        if (b != null) {
            for (String k : b.keySet()) {
                Object v = b.get(k);
                if (v != null) m.put(k, String.valueOf(v));
            }
        }
        return m;
    }

    @Override public int onStartCommand(Intent intent, int flags, final int startId) {
        final boolean run = intent != null && intent.getBooleanExtra(EXTRA_RUN, false);
        if (!run) {
            // 验证台 / 系统重启的空 intent：做一次 /proc 可读性报告，只收这一次（startId 记账）
            final int hinted = intent == null ? 0 : intent.getIntExtra(EXTRA_PID, 0);
            new Thread(new Runnable() {
                @Override public void run() {
                    probe(hinted);
                    stopSelf(startId);
                }
            }, "mdt-probe-lab").start();
            return START_NOT_STICKY;
        }

        final int pid = intent.getIntExtra(EXTRA_PID, 0);
        final String session = intent.getStringExtra(EXTRA_SESSION);
        final String slot = intent.getStringExtra(EXTRA_SLOT);
        final String version = intent.getStringExtra(EXTRA_VERSION);
        final Bundle vinfo = intent.getBundleExtra(EXTRA_VINFO);
        final ProbeRunner runner = ProbeRunner.start(this, pid, false, session, slot, version,
                mapOf(vinfo));
        synchronized (mRunners) {
            mRunners.put(pid, runner);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                // 游戏进程没了就收尾（上限 12 小时，防御性）
                for (int i = 0; i < 6 * 60 * 60 * 2; i++) {
                    if (pid <= 0 || !new File("/proc/" + pid).exists()) break;
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                runner.stop();
                boolean last;
                synchronized (mRunners) {
                    mRunners.remove(pid);
                    last = mRunners.isEmpty();
                }
                // ★ 第 371 轮：按"还有没有别的会话"收服务。无参 stopSelf() 会把刚起的新会话端掉
                //   （换版本时旧 watcher 与新 startService 会叠在一起）；startId 在"最后一个会话
                //   先于其它会话起来"时不一定是最新那条，所以这里判空而不是靠 startId 比较。
                if (last) stopSelf();
            }
        }, "mdt-probe-watch-" + pid).start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        java.util.List<ProbeRunner> rs;
        synchronized (mRunners) {
            rs = new java.util.ArrayList<ProbeRunner>(mRunners.values());
            mRunners.clear();
        }
        for (ProbeRunner r : rs) {
            try {
                r.stop();
            } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    private void probe(int hinted) {
        StringBuilder sb = new StringBuilder();
        sb.append("== probe lab ==  ").append(new java.util.Date()).append('\n');
        sb.append("probe pid  = ").append(android.os.Process.myPid()).append('\n');
        int game = hinted > 0 ? hinted : findGamePid(this);
        sb.append("game pid   = ").append(game).append(game > 0 ? "" : " (not found)").append('\n');
        if (game <= 0) {
            write(sb.toString());
            return;
        }
        String[] files = { "stat", "status", "io", "cmdline", "maps" };
        for (String name : files) {
            File f = new File("/proc/" + game + "/" + name);
            try {
                String text = read(f);
                sb.append(pad(name)).append(" = ").append(text == null ? "DENIED" : text.length() + " B")
                        .append('\n');
                if ("status".equals(name) && text != null) {
                    for (String line : text.split("\n")) {
                        if (line.startsWith("VmRSS:") || line.startsWith("Threads:")) {
                            sb.append("  ").append(line.trim()).append('\n');
                        }
                    }
                }
                if ("io".equals(name) && text != null) {
                    int n = 0;
                    for (String line : text.split("\n")) {
                        if (n++ >= 4) break;
                        if (!line.trim().isEmpty()) sb.append("  ").append(line.trim()).append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append(pad(name)).append(" = FAIL ").append(t).append('\n');
            }
        }
        write(sb.toString());
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 9) b.append(' ');
        return b.toString();
    }

    private static int findGamePid(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p == null || p.processName == null) continue;
                // ★ 第 371 轮：用 Data.isGameProcessName（认 :game / :gameN），别写死 ":game" 后缀
                if (Data.isGameProcessName(ctx, p.processName)) return p.pid;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    private static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[4096];
                int n = 0;
                int r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                return new String(buf, 0, n, "UTF-8");
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void write(String text) {
        try {
            File f = new File(Data.hubDir(this), "report-probe.txt");
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }
}
