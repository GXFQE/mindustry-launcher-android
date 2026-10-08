package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;

/**
 * ★ F23 的"立刻说一声"：游戏崩了**马上**提示，而不是等用户自己想到去翻日志页
 * （2026-10-08 用户点单：「有没有办法让他崩溃以后立马弹窗提示啊」）。
 *
 * <p>两条路都要，缺一条就有"事后才知道"的漏网：
 * <ol>
 *   <li><b>常驻监视</b>（本进程还活着时）：每 {@link #POLL_MS} 毫秒看一眼**当前槽**的
 *       {@code crashes/}，出现新报告就立刻 {@link Toast} —— Toast 是系统浮层，
 *       **游戏还在前台也看得见**（这是"立马"那一步）；</li>
 *   <li><b>回到界面时补一次</b>（本进程被系统杀过、或者用户自己切回来）：
 *       {@code MainActivity.onResume} 调 {@link #catchUp}，判据 = 槽里有游戏写的
 *       {@code launchid.dat}（"上次启动没跑完"的铁证）+ 最新报告不是已经提示过的那一份
 *       ⇒ 弹**对话框**（带 F23 的结论与「看运行日志」）。</li>
 * </ol>
 *
 * <p>★ 三条纪律：
 * <ul>
 *   <li><b>只报新的</b>：监视线程启动时先快照"当前最新的一份"，只报比它新的 ——
 *       否则每次打开应用都会把历史崩溃报一遍；</li>
 *   <li><b>同一份只提示一次</b>：{@link Config#crashAlerted()} 记 {@code <槽>|<文件名>}；</li>
 *   <li>提示里的结论**只有一份来源**（{@link CrashAnalysis#text}）——
 *       不许在弹窗里另写一套话术（否则弹窗与日志页会各说各的）。</li>
 * </ul>
 *
 * <p>⚠️ 判据都是**纯函数**（{@link #newestName} / {@link #pick}）：自检直接喂目录断言，
 * 不用等真崩一次。
 */
public final class CrashAlert {

    /** 轮询间隔：崩溃报告是"写完就死"，700 ms 足够快、又不心疼（一个几十字节的目录列举） */
    static final long POLL_MS = 700;

    private static volatile boolean started;
    /** 当前**前台**的页面（{@link BaseActivity} 挂钩）—— 决定"现在弹对话框"还是"只 Toast" */
    private static java.lang.ref.WeakReference<Activity> sTop;
    /** 已 Toast 但还没弹对话框的那一份（回到界面时补弹） */
    private static volatile File sPending;
    private static volatile String sPendingSlot;

    private CrashAlert() {
    }

    // ── 纯判据（自检直接喂目录） ───────────────────────────────────────────

    /** 某个槽的 {@code crashes/} 目录（不存在也返回路径 —— 调用方自己判 isDirectory） */
    static File crashesDir(Context ctx, String slot) {
        File root = Data.dirOf(ctx, slot);
        return root == null ? null : new File(root, "crashes");
    }

    /**
     * 目录里**最新**那份报告的文件名（按名字排 —— 名字就是写入时的毫秒时间戳，
     * 与 {@link CrashAnalysis#analyzeNewest} 同一口径）；没有就返回空串。
     */
    static String newestName(File dir) {
        File[] fs = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
        if (fs == null) return "";
        String best = "";
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            if (f.getName().compareTo(best) > 0) best = f.getName();
        }
        return best;
    }

    /**
     * 该提示的那一份：**比 `sinceName` 新**（名字序）、且 mtime 不早于 `sinceMs`（0 = 不限）。
     * ★ "只报新的"这条判据就在这里 —— 别在调用点再写一遍。
     */
    static File pick(File dir, String sinceName, long sinceMs) {
        File[] fs = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
        if (fs == null) return null;
        String since = sinceName == null ? "" : sinceName;
        File best = null;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName();
            if (n.compareTo(since) <= 0) continue;
            if (sinceMs > 0 && f.lastModified() < sinceMs) continue;
            if (best == null || n.compareTo(best.getName()) > 0) best = f;
        }
        return best;
    }

    // ── 前台挂钩（BaseActivity 调） ───────────────────────────────────────

    static void activityResumed(Activity a) {
        sTop = new java.lang.ref.WeakReference<Activity>(a);
        flushPending();
    }

    static void activityPaused(Activity a) {
        Activity cur = sTop == null ? null : sTop.get();
        if (cur == null || cur == a) sTop = null;
    }

    // ── 监视（进程活着时的"立马"） ────────────────────────────────────────

    /** 幂等：从 {@code MainActivity.onCreate} 调一次即可（daemon 线程，进程活着就在看） */
    static synchronized void start(final Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String slot = null;
                File dir = null;
                String seen = "";
                while (true) {
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        // ★ 每次重新读"当前槽"：用户换槽之后要跟着换（否则会一直盯旧槽）
                        String cur = Data.currentSlot(app);
                        if (!cur.equals(slot)) {
                            slot = cur;
                            dir = crashesDir(app, slot);
                            seen = newestName(dir);        // 换槽时重新快照 ⇒ 不报旧报告
                        }
                        File f = pick(dir, seen, 0);
                        if (f == null) continue;
                        seen = f.getName();
                        announce(app, slot, f);
                    } catch (Throwable ignored) {
                        // 监视循环绝不能因为一次异常就死掉
                    }
                }
            }
        }, "crash-alert");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 回到界面时补一次（进程被杀过 / 用户自己切回来）。
     * 判据 = 游戏写的 {@code launchid.dat}（"上次启动没跑完"）**且**最新报告不是已提示过的那份。
     * ⚠️ 必须在 UI 线程调（`MainActivity.onResume`）—— 它可能要弹对话框。
     */
    static void catchUp(Activity a) {
        if (Util.dead(a)) return;
        String slot = Data.currentSlot(a);
        File root = Data.dirOf(a, slot);
        if (root == null) return;
        File lid = new File(root, "launchid.dat");
        if (!lid.isFile()) return;                       // 上次是正常退出的 ⇒ 不该烦用户
        File dir = crashesDir(a, slot);
        String name = newestName(dir);
        if (name.isEmpty()) return;
        String key = slot + "|" + name;
        File f = new File(dir, name);
        final CrashAnalysis.Verdict v = CrashAnalysis.analyzeReport(a, f, slot);
        if (v == null) return;
        if (!claim(key)) return;                         // ★ 原子闸门（查+写同锁）
        sPending = null;
        showDialog(a, v);
    }

    // ── 「同一份只提示一次」的原子闸门 ─────────────────────────────────────

    /**
     * ★★ **查与写在同一把锁里**（2026-10-08 真机反馈：「游戏崩溃时怎么弹了两个一模一样的窗啊」）。
     *
     * <p>原来三个路径各自"先查 {@link Config#crashAlerted()}、再写"，而**监视**那条路的
     * *检查在 daemon 线程、弹窗在 UI 线程*，中间隔着一次 {@code Handler.post} 投递 ⇒
     * 回到界面的 {@link #catchUp}（或 {@link #flushPending}）会在这空档里也判定"还没提示过"
     * ⇒ **同一份报告弹两个窗**。现在统一走这里，谁先抢到谁提示。
     *
     * <p>判据抽成纯函数 {@link #claims} 好让自检钉住（同一份 ⇒ false；换了份 ⇒ true）。
     */
    private static synchronized boolean claim(String key) {
        if (!claims(Config.get().crashAlerted(), key)) return false;
        Config.get().setCrashAlerted(key);
        return true;
    }

    /** 纯判据：{@code key} 这一份还没提示过吗（{@code already} = 上次提示过的那份）。 */
    static boolean claims(String already, String key) {
        return key != null && !key.isEmpty() && !key.equals(already);
    }

    // ── 提示 ─────────────────────────────────────────────────────────────

    private static void announce(final Context app, final String slot, final File f) {
        final String key = slot + "|" + f.getName();
        if (!claims(Config.get().crashAlerted(), key)) return;   // 便宜的预检（省一次分析）
        final CrashAnalysis.Verdict v = CrashAnalysis.analyzeReport(app, f, slot);
        if (v == null) return;
        final String line = Trans.get(app, R.string.crash_alert_toast_fmt,
                CrashAnalysis.brief(app, v));
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                // ① 立刻说一声：Toast 是系统浮层 ⇒ 游戏还在前台也看得见
                try {
                    Toast.makeText(app, line, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
                // ② 若此刻有前台页面（崩溃后回到启动器那种），直接弹对话框；否则留着，回到界面时补
                // 🔴 **弹之前必须再过一次原子闸门**：预检在 daemon 线程、这里在 UI 线程，
                //    中间那一跳足够让 catchUp 也判定"还没提示过" ⇒ 两个窗（真机抓到的就是这个）。
                Activity top = sTop == null ? null : sTop.get();
                if (top != null && !Util.dead(top)) {
                    if (!claim(key)) return;
                    showDialog(top, v);
                } else {
                    sPending = f;
                    sPendingSlot = slot;
                }
            }
        });
    }

    /** 回到界面时把"已 Toast 但还没弹窗"的那一份补上 */
    private static void flushPending() {
        final File f = sPending;
        final String slot = sPendingSlot;
        final Activity top = sTop == null ? null : sTop.get();
        if (f == null || top == null || Util.dead(top)) return;
        sPending = null;
        final Context app = top;
        new Thread(new Runnable() {
            @Override public void run() {
                final CrashAnalysis.Verdict v = CrashAnalysis.analyzeReport(app, f, slot);
                if (v == null) return;
                // 🔴 弹之前过原子闸门（原来这里是**无条件**弹 —— 第三条会产生重复窗的路）
                if (!claim(slot + "|" + f.getName())) return;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        Activity a = sTop == null ? null : sTop.get();
                        if (a == null || Util.dead(a)) return;
                        showDialog(a, v);
                    }
                });
            }
        }, "crash-alert-dialog").start();
    }

    /** 结论对话框：正文 = {@link CrashAnalysis#text}（**唯一来源**），按钮 =「看运行日志」/「知道了」 */
    private static void showDialog(final Activity a, CrashAnalysis.Verdict v) {
        if (a == null || Util.dead(a)) return;
        new android.app.AlertDialog.Builder(a)
                .setTitle(R.string.crash_alert_title)
                .setMessage(CrashAnalysis.text(a, v))
                .setPositiveButton(R.string.crash_alert_open, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            a.startActivity(new android.content.Intent(a, LogActivity.class));
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }
}
