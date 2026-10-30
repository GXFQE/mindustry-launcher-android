package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「更新线路」设置行 + 手动测速卡。
 *
 * <p>★ 正常下载**不测速**：{@link Mirrors#downloadCandidates} 只是按顺序试；
 * 测速只有用户点「测速」才跑（后台线程），结果让用户选一条。
 *
 * <p>★ 测速本身**不落盘任何样本**（16 KB 读进内存就丢），只写一个
 * `app_hub/mirror-test.lock` 开始标记；正常结束/取消会自己删。进程被
 * `force-stop` 时这个标记会留下 —— 由 {@link UpdateCleanup#sweep} 在下次
 * 启动时清掉，不会让界面卡在「测速中」。
 */
final class UpdateLineUi {

    /** 上次测速结果在内存里留多久（进程活着的范围内；被杀就没了）。 */
    private static final long LAST_TTL_MS = 30L * 60L * 1000L;

    private static volatile boolean sRunning;
    private static volatile boolean sStop;
    private static volatile List<MirrorTest.Result> sLast;
    private static volatile long sLastAt;

    private UpdateLineUi() {}

    static void show(final Activity a, final Runnable changed) {
        if (sRunning) {
            Toast.makeText(a, Trans.get(a, R.string.update_line_busy),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<MirrorTest.Result> last = sLast;
        if (last != null && System.currentTimeMillis() - sLastAt <= LAST_TTL_MS) {
            showResults(a, null, last, changed);
            return;
        }
        intro(a, changed);
    }

    private static void intro(final Activity a, final Runnable changed) {
        String body = Trans.get(a, R.string.update_line_intro)
                + "\n\n" + Trans.get(a, R.string.set_update_line_sub_fmt,
                label(a, Config.get().updateMirror()));
        CardStack card = CardStack.show(null, a,
                Trans.get(a, R.string.set_update_line_title),
                CardStack.text(a, body));
        bindIntroButtons(a, card, changed);
    }

    private static void bindIntroButtons(final Activity a, final CardStack card,
                                         final Runnable changed) {
        if (card == null || !card.isShowing()) return;
        card.replace(Trans.get(a, R.string.set_update_line_title),
                Trans.get(a, R.string.update_line_intro)
                        + "\n\n" + Trans.get(a, R.string.set_update_line_sub_fmt,
                        label(a, Config.get().updateMirror())),
                new CardStack.Btn(Trans.get(a, R.string.update_line_test), new Runnable() {
                    @Override public void run() { startTest(a, card, changed); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.update_line_pick), new Runnable() {
                    @Override public void run() { pickLine(a, card, changed); }
                }),
                closeBtn(a, card));
    }

    private static void startTest(final Activity a, final CardStack card,
                                  final Runnable changed) {
        if (sRunning) {
            Toast.makeText(a, Trans.get(a, R.string.update_line_busy),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        sRunning = true;
        sStop = false;
        sLast = null;
        sLastAt = 0;
        if (card != null && card.isShowing()) {
            card.replace(Trans.get(a, R.string.set_update_line_title),
                    Trans.get(a, R.string.update_line_checking),
                    new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                        @Override public void run() {
                            sStop = true;
                            if (card.isShowing()) {
                                card.updateText(Trans.get(a, R.string.update_line_canceling));
                            }
                        }
                    }));
        }

        new Thread(new Runnable() {
            @Override public void run() {
                final File lock = new File(Paths.privateDir(a.getApplicationContext()),
                        "mirror-test.lock");
                List<MirrorTest.Result> results = null;
                String failure = null;
                try {
                    try {
                        Util.atomicWriteText(lock, String.valueOf(System.currentTimeMillis()));
                    } catch (Throwable ignored) {
                    }
                    String testUrl = latestApkUrl();
                    if (testUrl == null) {
                        failure = Trans.get(a, R.string.update_line_no_test_file);
                    } else {
                        results = MirrorTest.run(testUrl, Mirrors.testCandidates(),
                                new MirrorTest.Progress() {
                                    @Override public void onProgress(final int done,
                                                                     final int total,
                                                                     final String prefix) {
                                        a.runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                if (Util.dead(a) || card == null
                                                        || !card.isShowing()) return;
                                                card.updateText(Trans.get(a,
                                                        R.string.update_line_testing_fmt,
                                                        done, total));
                                            }
                                        });
                                    }
                                },
                                new MirrorTest.Stop() {
                                    @Override public boolean isStopped() { return sStop; }
                                });
                    }
                } catch (Throwable t) {
                    // 原始异常只进日志；界面给一句人话（项目规矩：类名/原始异常不上第一层）。
                    android.util.Log.i("MDTLauncher", "mirror test failed: " + t);
                    failure = Trans.get(a, R.string.update_line_no_test_file);
                } finally {
                    // 正常结束 / 取消都删；被杀时删不掉 —— 交给启动 sweep。
                    try { lock.delete(); } catch (Throwable ignored) { }
                }

                final List<MirrorTest.Result> fr = results;
                final String ff = failure;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        sRunning = false;
                        boolean canceled = sStop;
                        sStop = false;
                        if (canceled) {
                            sLast = null;
                            if (!Util.dead(a) && card != null && card.isShowing()) {
                                card.replace(Trans.get(a, R.string.set_update_line_title),
                                        Trans.get(a, R.string.update_line_canceled),
                                        closeBtn(a, card));
                            }
                            return;
                        }
                        if (fr == null || fr.isEmpty()) {
                            sLast = null;
                            if (!Util.dead(a) && card != null && card.isShowing()) {
                                card.replace(Trans.get(a, R.string.set_update_line_title),
                                        ff == null ? Trans.get(a, R.string.update_line_none) : ff,
                                        closeBtn(a, card));
                            }
                            return;
                        }
                        sLast = fr;
                        sLastAt = System.currentTimeMillis();
                        if (Util.dead(a) || card == null || !card.isShowing()) return;
                        showResults(a, card, fr, changed);
                    }
                });
            }
        }, "mdt-mirror-test").start();
    }

    private static void showResults(final Activity a, CardStack card,
                                    final List<MirrorTest.Result> results,
                                    final Runnable changed) {
        List<MirrorTest.Result> sorted = MirrorTest.sorted(results);
        StringBuilder sb = new StringBuilder();
        for (MirrorTest.Result r : sorted) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(testLabel(a, r.prefix)).append("  ·  ").append(statusText(a, r));
        }
        boolean any = false;
        for (MirrorTest.Result r : sorted) {
            if (r.ok()) { any = true; break; }
        }
        String body = sb.length() == 0 ? Trans.get(a, R.string.update_line_none)
                : sb.toString() + "\n\n" + Trans.get(a, R.string.update_line_result_hint);

        final CardStack view = (card != null && card.isShowing())
                ? card
                : CardStack.show(null, a, Trans.get(a, R.string.update_line_result_title),
                CardStack.text(a, body));
        final CardStack.Btn pick = new CardStack.Btn(Trans.get(a, R.string.update_line_pick),
                new Runnable() {
                    @Override public void run() { pickResult(a, view, results, changed); }
                });
        final CardStack.Btn retest = new CardStack.Btn(Trans.get(a, R.string.update_line_retest),
                new Runnable() {
                    @Override public void run() { startTest(a, view, changed); }
                });
        final CardStack.Btn close = closeBtn(a, view);
        if (any) {
            view.replace(Trans.get(a, R.string.update_line_result_title), body, pick, retest, close);
        } else {
            view.replace(Trans.get(a, R.string.update_line_result_title), body, retest, close);
        }
    }

    private static void pickResult(final Activity a, final CardStack card,
                                   final List<MirrorTest.Result> results,
                                   final Runnable changed) {
        List<MirrorTest.Result> sorted = MirrorTest.sorted(results);
        final List<String> values = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        values.add(Mirrors.AUTO);
        names.add(Trans.get(a, R.string.update_line_auto));
        values.add(Mirrors.DIRECT);
        names.add(Trans.get(a, R.string.update_line_direct));
        final String cur = Config.get().updateMirror();
        int checked = Mirrors.DIRECT.equals(cur) ? 1 : 0;
        for (MirrorTest.Result r : sorted) {
            if (!r.ok()) continue;
            String p = r.prefix == null ? "" : r.prefix;
            // 直连已经在上面列过了（值为 "direct"），这里不再重复一条空前缀。
            if (p.isEmpty()) continue;
            if (p.equals(cur)) checked = values.size();
            values.add(p);
            names.add(label(a, p) + "  ·  " + statusText(a, r));
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.update_line_pick_title)
                .setSingleChoiceItems(names.toArray(new String[0]), checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                String v = w >= 0 && w < values.size() ? values.get(w) : Mirrors.AUTO;
                                Config.get().setUpdateMirror(v);
                                sLast = null;
                                sLastAt = 0;
                                if (card != null && card.isShowing()) card.dismiss();
                                if (changed != null) changed.run();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void pickLine(final Activity a, final CardStack card,
                                 final Runnable changed) {
        final List<String> values = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        values.add(Mirrors.AUTO);
        names.add(Trans.get(a, R.string.update_line_auto));
        values.add(Mirrors.DIRECT);
        names.add(Trans.get(a, R.string.update_line_direct));
        int checked = 0;
        String cur = Config.get().updateMirror();
        if (Mirrors.DIRECT.equals(cur)) checked = 1;
        for (String p : Mirrors.PRESETS) {
            if (p.equals(cur)) checked = values.size();
            values.add(p);
            names.add(label(a, p));
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.update_line_pick_title)
                .setSingleChoiceItems(names.toArray(new String[0]), checked,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                String v = w >= 0 && w < values.size() ? values.get(w) : Mirrors.AUTO;
                                Config.get().setUpdateMirror(v);
                                sLast = null;
                                sLastAt = 0;
                                if (card != null && card.isShowing()) card.dismiss();
                                if (changed != null) changed.run();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 上屏名：自动 / 直连 / 具体域名（域名不翻译）。 */
    static String label(Context c, String prefix) {
        if (prefix == null || Mirrors.AUTO.equals(prefix)) {
            return Trans.get(c, R.string.update_line_auto);
        }
        if (Mirrors.DIRECT.equals(prefix)) {
            return Trans.get(c, R.string.update_line_direct);
        }
        return prefix;
    }

    /** 测速结果里的名字：空前缀代表**直连**，不是「自动」。 */
    private static String testLabel(Context c, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Trans.get(c, R.string.update_line_direct);
        }
        return label(c, prefix);
    }

    private static String statusText(Context c, MirrorTest.Result r) {
        switch (r.status) {
            case OK:
                // 16 KB 的样本里 TLS/首字节占大头，换算成 KB/s 会显得所有镜像都只有个位数。
                // 这里只报“读这一段用了多久”——排序仍是按速度（同样 16 KB 下等价于耗时）。
                return String.format(Locale.US, "%.1f s", r.ms / 1000.0);
            case TIMEOUT:
                return Trans.get(c, R.string.update_line_timeout);
            case HTTP:
                return Trans.get(c, R.string.update_line_http_fmt, r.httpCode);
            case SHORT:
                return Trans.get(c, R.string.update_line_short);
            case NET:
                return Trans.get(c, R.string.update_line_net);
            case CANCELED:
            default:
                return Trans.get(c, R.string.update_line_canceled);
        }
    }

    private static CardStack.Btn closeBtn(final Activity a, final CardStack card) {
        return new CardStack.Btn(Trans.get(a, R.string.close), new Runnable() {
            @Override public void run() {
                if (card != null && card.isShowing()) card.dismiss();
            }
        });
    }

    /** 测速的目标文件：最新一个带 `mdt-launcher.apk` 的 release 的 browser URL。 */
    private static String latestApkUrl() throws Exception {
        // 只用来拿一个地址：给短超时，别让“取消”在拿列表这一层卡住半分钟。
        String json = UpdateClient.fetchText(UpdateUi.releasesUrl(), 512 * 1024,
                "application/json", 5000, 10000);
        List<GitHubReleases.Release> list = GitHubReleases.parseStrict(json);
        if (list == null) return null;
        for (GitHubReleases.Release r : list) {
            GitHubReleases.Asset asset = GitHubReleases.asset(r, UpdateUi.APK_ASSET);
            if (asset != null && asset.browserUrl != null && !asset.browserUrl.isEmpty()) {
                return asset.browserUrl;
            }
        }
        return null;
    }
}
