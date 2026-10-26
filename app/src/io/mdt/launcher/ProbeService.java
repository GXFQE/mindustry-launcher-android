package io.mdt.launcher;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

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

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final int pid = intent == null ? 0 : intent.getIntExtra(EXTRA_PID, 0);
        final boolean run = intent != null && intent.getBooleanExtra(EXTRA_RUN, false);
        final String session = intent == null ? null : intent.getStringExtra(EXTRA_SESSION);
        final String version = intent == null ? null : intent.getStringExtra(EXTRA_VERSION);
        if (run) {
            final ProbeRunner runner = ProbeRunner.start(this, pid, false, session, null, version);
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
                    stopSelf();
                }
            }, "mdt-probe-watch").start();
            return START_NOT_STICKY;
        }
        final int hinted = pid;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                probe(hinted);
                stopSelf();
            }
        }, "mdt-probe-lab");
        t.start();
        return START_NOT_STICKY;
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
                if (p.processName.endsWith(":game")) return p.pid;
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
