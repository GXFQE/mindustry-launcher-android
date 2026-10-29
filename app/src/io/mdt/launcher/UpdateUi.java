package io.mdt.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * 更新器的**界面层**：检查 → 卡片 → 下载 → 安装。
 *
 * <p>分三层（这是第 5 轮的收口）：
 * <ul>
 *   <li>{@link UpdateManifest} / {@link UpdateClient} / {@link UpdateDownload}：纯逻辑，PC 可跑；</li>
 *   <li>{@link UpdateFetcher} / {@link UpdateInstall}：需要 {@code Context}，但**不碰界面**；</li>
 *   <li>本文件：只负责"怎么说、点什么"，不重复任何判据（判据全在下面那两层）。</li>
 * </ul>
 *
 * <p>⚠️ 卡片一律走 `CardStack`（同窗换页）—— `AlertDialog` 叠窗会闪，而"检查中 → 结果"
 * 正好是一次换页。
 *
 * <p>⚠️ 静默检查**不出声**：只有"确实有更新"才弹卡片。网络失败/数据坏掉一律只写日志，
 * 否则用户每次启动都被一个"连不上服务器"打扰。
 */
final class UpdateUi {

    /**
     * 更新清单的地址：**固定 tag 的 release 资产**，不是 `releases/latest`。
     *
     * <p>⚠️ 为什么不能用 `latest`：GitHub 的 `releases/latest` **只指向非 prerelease**，
     * 而 1.0 之前每个版本都是 `--prerelease` ⇒ `latest/download/…` 直接 404
     * （`docs/RELEASING.md` §5 早就记了这一条，只是当时还没有更新器）。
     *
     * <p>⇒ 用一条**固定通道**：发版时把 `update.json` 传到 tag `update-channel` 的那个
     * release（`gh release upload update-channel update.json --clobber`），URL 永远不变。
     */
    static final String MANIFEST_URL =
            "https://github.com/GXFQE/mindustry-launcher-android/releases/download/update-channel/update.json";

    /** dev 夹具用：`dev_update_ui <url>` 覆盖掉上面那个。 */
    private static volatile String sOverride = null;

    /** 本进程是否已经静默查过（避免每次回主界面都读一次文件）。 */
    private static boolean sCheckedThisProcess = false;

    private UpdateUi() {}

    static void setManifestUrlForDev(String url) { sOverride = url; }

    static String manifestUrl() {
        return (sOverride != null && !sOverride.isEmpty()) ? sOverride : MANIFEST_URL;
    }

    /** 用户看得见的版本号（不是 versionCode —— 那个不上屏）。 */
    static String versionName(Activity a) {
        try {
            return String.valueOf(a.getPackageManager()
                    .getPackageInfo(a.getPackageName(), 0).versionName);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 设置页那一行。 */
    static void checkManual(Activity a) { check(a, true); }

    /** 启动时静默检查（24 h 去重由调用方判）。 */
    static void checkSilent(Activity a) { check(a, false); }

    /**
     * 启动时的那一次静默检查：**24 小时内只查一次**。
     *
     * <p>时间戳先写再查（写失败也无所谓）—— 宁可少查一次，也不要在"写不进去"的机器上
     * 每次启动都联网。这条与 `UpdateManifest.shouldCheck` 的时钟回拨处理是一对：
     * 前者防"频繁"，后者防"永远不查"。
     */
    static void checkSilentIfDue(Activity a) {
        if (sCheckedThisProcess) return;
        sCheckedThisProcess = true;
        long now = System.currentTimeMillis() / 1000L;
        File stamp = new File(Paths.privateDir(a), "update-check-at.txt");
        long last = 0;
        try {
            String s = Util.readText(stamp);
            if (s != null) last = Long.parseLong(s.trim());
        } catch (Throwable ignored) {
        }
        if (!UpdateManifest.shouldCheck(now, last, 24L * 3600L)) return;
        try {
            Util.atomicWriteText(stamp, String.valueOf(now));
        } catch (Throwable ignored) {
        }
        check(a, false);
    }

    // ── 检查 ──────────────────────────────────────────────────────────────

    private static void check(final Activity a, final boolean manual) {
        final CardStack[] ui = new CardStack[1];
        if (manual) {
            ui[0] = CardStack.show(null, a,
                    Trans.get(a, R.string.set_update_title),
                    CardStack.text(a, Trans.get(a, R.string.update_checking)));
        }

        new Thread(new Runnable() {
            @Override public void run() {
                UpdateManifest m = null;
                String err = null;
                try {
                    String json = UpdateClient.fetchText(manifestUrl(), UpdateManifest.MAX_BYTES);
                    m = UpdateManifest.parse(json);
                    if (!m.ok()) err = m.error;
                } catch (Throwable t) {
                    err = String.valueOf(t);
                    android.util.Log.i("MDTLauncher", "update check failed: " + err);
                }

                final UpdateManifest fm = m;
                final String ferr = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (fm == null || !fm.ok()) {
                            if (manual) {
                                boolean net = ferr != null && ferr.contains("http") || ferr != null
                                        && (ferr.contains("IOException") || ferr.contains("timeout"));
                                showError(a, ui[0], net ? R.string.update_err_network : R.string.update_err_data);
                            }
                            return;
                        }
                        int cur = UpdateInstall.currentVersionCode(a);
                        if (!UpdateManifest.isNewer(cur, fm.versionCode)) {
                            if (manual) {
                                if (ui[0] != null && ui[0].isShowing()) {
                                    ui[0].replace(Trans.get(a, R.string.set_update_title),
                                            Trans.get(a, R.string.update_latest), closeBtn(a, ui[0]));
                                }
                            }
                            return;
                        }
                        showFound(a, ui[0], fm);
                    }
                });
            }
        }, "mdt-update-check").start();
    }

    private static CardStack.Btn closeBtn(final Activity a, final CardStack stack) {
        return new CardStack.Btn(Trans.get(a, R.string.close), new Runnable() {
            @Override public void run() { if (stack != null && stack.isShowing()) stack.dismiss(); }
        });
    }

    private static void showError(final Activity a, CardStack stack, int msgRes) {
        if (stack == null || !stack.isShowing()) {
            CardStack.show(null, a, Trans.get(a, R.string.set_update_title),
                    CardStack.text(a, Trans.get(a, msgRes)), closeBtn(a, stack));
            return;
        }
        stack.replace(Trans.get(a, R.string.set_update_title),
                Trans.get(a, msgRes), closeBtn(a, stack));
    }

    // ── 发现新版本 ────────────────────────────────────────────────────────

    private static void showFound(final Activity a, CardStack stack, final UpdateManifest m) {
        String title = Trans.get(a, R.string.update_found_title_fmt,
                m.versionName == null ? String.valueOf(m.versionCode) : m.versionName);

        StringBuilder body = new StringBuilder();
        if (m.size > 0) {
            body.append(Trans.get(a, R.string.update_size_fmt,
                    String.format(Locale.US, "%.1f", m.size / 1048576.0)));
        }
        if (m.notes != null && !m.notes.isEmpty()) {
            if (body.length() > 0) body.append("  ·  ");
            body.append(m.notes);
        }

        CardStack.Btn dl = new CardStack.Btn(Trans.get(a, R.string.update_download), new Runnable() {
            @Override public void run() { startDownload(a, m); }
        });
        CardStack.Btn later = new CardStack.Btn(Trans.get(a, R.string.update_later), new Runnable() {
            @Override public void run() {
                // 「稍后」只关卡片：不记住"这一版不提醒"——那会让用户永远看不到它。
                // （静默检查的 24 h 去重已经够了）
            }
        });

        if (stack != null && stack.isShowing()) {
            stack.replace(title, body.toString(), dl, later);
        } else {
            CardStack.show(null, a, title, CardStack.text(a, body.toString()), dl, later);
        }
    }

    // ── 下载 ──────────────────────────────────────────────────────────────

    private static void startDownload(final Activity a, final UpdateManifest m) {
        final CardStack[] ui = new CardStack[1];
        ui[0] = CardStack.show(null, a, Trans.get(a, R.string.update_downloading),
                CardStack.text(a, ""),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { if (ui[0] != null && ui[0].isShowing()) ui[0].dismiss(); }
                }));

        try {
            Work.begin(a, R.string.update_downloading, Trans.get(a, R.string.update_downloading));
        } catch (Throwable ignored) {
            // 前台服务起不来不影响下载本身（只是没通知），别因此中断
        }

        new Thread(new Runnable() {
            @Override public void run() {
                final UpdateDownload.Result r = UpdateDownload.run(
                        m.url, UpdateFetcher.dir(a), m.versionCode, m.size, m.sha256,
                        new UpdateClient.Progress() {
                            @Override public void onProgress(final long done, final long total) {
                                try { Work.progress(a, (int) done, (int) total); } catch (Throwable ignored) { }
                                a.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (ui[0] != null && ui[0].isShowing()) {
                                            ui[0].updateText(progressText(done, total));
                                        }
                                    }
                                });
                            }
                        });
                try { Work.end(a); } catch (Throwable ignored) { }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing() || ui[0] == null || !ui[0].isShowing()) return;
                        if (r != null && r.status == UpdateDownload.Status.OK) {
                            showReady(a, ui[0], r.file);
                        } else {
                            ui[0].replace(Trans.get(a, R.string.update_download_failed),
                                    detailOf(r), closeBtn(a, ui[0]));
                        }
                    }
                });
            }
        }, "mdt-update-download").start();
    }

    private static String progressText(long done, long total) {
        if (total > 0) {
            long pct = done * 100 / total;
            return pct + "%  ·  " + String.format(Locale.US, "%.1f", done / 1048576.0)
                    + " / " + String.format(Locale.US, "%.1f", total / 1048576.0) + " MB";
        }
        return String.format(Locale.US, "%.1f", done / 1048576.0) + " MB";
    }

    /** 失败原因**只在报告层说细节**（sha/长度），界面给一句人话。 */
    private static String detailOf(UpdateDownload.Result r) {
        if (r == null) return "";
        return r.status == UpdateDownload.Status.SHA || r.status == UpdateDownload.Status.SIZE
                ? "" : (r.detail == null ? "" : r.detail);
    }

    // ── 安装 ──────────────────────────────────────────────────────────────

    private static void showReady(final Activity a, final CardStack ui, final File apk) {
        UpdateInstall.Precheck p = UpdateInstall.precheck(a, apk);
        if (!p.ok) {
            if (UpdateInstall.S_NO_PERMISSION.equals(p.why)) {
                ui.replace(Trans.get(a, R.string.update_no_permission),
                        "",
                        new CardStack.Btn(Trans.get(a, R.string.update_go_settings), new Runnable() {
                            @Override public void run() { openUnknownSources(a); }
                        }),
                        closeBtn(a, ui));
            } else {
                int msg = UpdateInstall.S_BAD_SIGN.equals(p.why) ? R.string.update_bad_sign
                        : UpdateInstall.S_DOWNGRADE.equals(p.why) ? R.string.update_downgrade
                        : R.string.update_bad_package;
                ui.replace(Trans.get(a, R.string.update_download_failed),
                        Trans.get(a, msg), closeBtn(a, ui));
            }
            return;
        }

        String size = Trans.get(a, R.string.update_size_fmt,
                String.format(Locale.US, "%.1f", apk.length() / 1048576.0));
        ui.replace(Trans.get(a, R.string.update_downloaded_title),
                size,
                new CardStack.Btn(Trans.get(a, R.string.update_install), new Runnable() {
                    @Override public void run() { install(a, ui, apk); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.update_delete), new Runnable() {
                    @Override public void run() {
                        apk.delete();
                        if (ui.isShowing()) ui.dismiss();
                    }
                }));
    }

    private static void install(final Activity a, final CardStack ui, File apk) {
        try {
            UpdateInstall.submit(a, apk);
            // 系统会弹确认界面（由 InstallResultReceiver 转发）；本卡片先退场，别挡着
            if (ui.isShowing()) ui.dismiss();
        } catch (Throwable t) {
            Toast.makeText(a, String.valueOf(t), Toast.LENGTH_LONG).show();
        }
    }

    /** 跳到"允许来自此来源的应用"（Android 8+ 是逐应用开关）。 */
    static void openUnknownSources(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(a, Trans.get(a, R.string.update_no_permission), Toast.LENGTH_LONG).show();
        }
    }
}
