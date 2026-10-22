package io.mdt.launcher;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 游戏会话流水（第 214 轮）：**record in the game process, read from anywhere**。
 *
 * <p>为什么记在 {@code :game} 进程：插件只在页面打开时活着、宿主进程在后台会被回收；
 * 只有游戏进程能一直看到"游戏在前台"。宿主侧只做"记原始事实"，怎么解释交给插件。</p>
 *
 * <p>格式（{@code <hub>/usage.txt}，追加写，一行一条，空格分隔）：</p>
 * <pre>
 * S &lt;startMs&gt; &lt;slot&gt;   会话开始（游戏 Activity resumed）
 * B &lt;ts&gt;                 心跳（5 秒一次；进程被杀就断）
 * E &lt;ts&gt;                 会话结束（游戏 Activity paused）
 * </pre>
 *
 * <p>没有 E 的会话：读方按 {@code min(now, lastB + 5s)} 收口（只少不多），单条上限 12 小时。</p>
 */
final class UsageLog {

    private static final String TAG = "MDTUsage";
    private static final String FILE = "usage.txt";
    private static final long BEAT_MS = 5_000L;
    static final long MAX_SESSION_MS = 12L * 3600_000L;

    private static Thread beat;
    private static volatile boolean beating;

    private UsageLog() { }

    static File file(Context ctx) {
        return new File(Data.hubDir(ctx), FILE);
    }

    /** 在 {@code :game} 进程的 Application 里装旁观者（不改游戏任何代码）。 */
    static void install(final Application app) {
        recover(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) {
                if (isGame(a)) begin(app, a);
            }
            @Override public void onActivityPaused(Activity a) {
                if (isGame(a)) end(app);
            }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** 游戏 Activity：住在这个进程里、又不是我们自己的页。 */
    private static boolean isGame(Activity a) {
        if (a == null) return false;
        String n = a.getClass().getName();
        return !n.startsWith("io.mdt.");
    }

    private static synchronized void begin(Context ctx, Activity a) {
        if (beating) return;
        long now = System.currentTimeMillis();
        append(ctx, "S " + now + " " + clean(Data.currentSlot(ctx)));
        startBeat(ctx.getApplicationContext());
        Log.i(TAG, "session start slot=" + Data.currentSlot(ctx));
    }

    private static synchronized void end(Context ctx) {
        if (!beating) return;
        stopBeat();
        append(ctx, "E " + System.currentTimeMillis());
        Log.i(TAG, "session end");
    }

    /** 上次没结束的会话：end = min(now, lastBeat + 5s)，只少不多。 */
    private static void recover(Context ctx) {
        long start = -1, last = -1;
        for (String l : readLines(ctx)) {
            if (l.startsWith("S ")) {
                start = num(l, 1, -1);
                last = start;
            } else if (l.startsWith("B ")) {
                last = num(l, 1, last);
            } else if (l.startsWith("E ")) {
                start = -1;
            }
        }
        if (start <= 0) return;
        long end = last > 0 ? Math.min(System.currentTimeMillis(), last + BEAT_MS)
                : Math.min(System.currentTimeMillis(), start + BEAT_MS);
        if (end - start > MAX_SESSION_MS) end = start + MAX_SESSION_MS;
        append(ctx, "E " + end);
    }

    private static void startBeat(final Context ctx) {
        beating = true;
        beat = new Thread(new Runnable() {
            @Override public void run() {
                while (beating) {
                    try {
                        Thread.sleep(BEAT_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (beating) append(ctx, "B " + System.currentTimeMillis());
                }
            }
        }, "mdt-usage-beat");
        beat.setDaemon(true);
        beat.start();
    }

    private static void stopBeat() {
        beating = false;
        Thread t = beat;
        beat = null;
        if (t != null) t.interrupt();
    }

    private static void append(Context ctx, String line) {
        try {
            File f = file(ctx);
            File d = f.getParentFile();
            if (d != null && !d.isDirectory()) d.mkdirs();
            FileOutputStream os = new FileOutputStream(f, true);
            try {
                os.write((line + "\n").getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "append failed: " + t);
        }
    }

    private static String[] readLines(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.isFile()) return new String[0];
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                return sb.toString().split("\n");
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static long num(String line, int i, long def) {
        String[] p = line.split(" ");
        try {
            return p.length > i ? Long.parseLong(p[i]) : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String clean(String s) {
        return s == null || s.isEmpty() ? "-" : s.replace(' ', '_');
    }
}
