package io.mdt.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.util.List;
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
     * GitHub Releases **列表**：一次拿到全部版本（含 prerelease），按创建时间倒序。
     *
     * <p>★ 为什么不用 `releases/latest`：那个接口**明确跳过** prerelease，而 1.0 之前每版
     * 都是 prerelease ⇒ 它永远拿不到东西。`/releases` 全都有。
     *
     * <p>★ 为什么把"固定通道"删了（第 310~316 轮那套）：那是为了绕开**嵌套 JSON 解析**而造
     * 出来的额外概念 —— 多一个 release 要维护、还要一条 404 回退。现在直接用 `org.json` 解
     * 列表，这两个东西一起没了（2026-10-29 用户拍板「直接用安卓测试」）。
     */
    static final String RELEASES_URL =
            "https://api.github.com/repos/GXFQE/mindustry-launcher-android/releases?per_page=10";

    /** 清单资产在 release 里的名字（发版脚本按这个名字传）。 */
    static final String MANIFEST_ASSET = "update.json";

    /** APK 资产在 release 里的名字（测速要拿它当目标文件）。 */
    static final String APK_ASSET = "mdt-launcher.apk";

    /** "这个通道还没有可装的版本" —— **不是错误**（调用方据此显示"已是最新"）。 */
    static final String NO_RELEASE = "no-release";

    /** dev 夹具用：覆盖 {@link #RELEASES_URL}（null = 用真地址）。 */
    private static volatile String sOverride = null;

    static void setReleasesUrlForDev(String url) {
        sOverride = (url == null || url.isEmpty()) ? null : url;
    }

    /** dev 覆盖优先；否则用真地址。 */
    static String releasesUrl() {
        return (sOverride != null && !sOverride.isEmpty()) ? sOverride : RELEASES_URL;
    }

    /** 本进程是否已经静默查过（避免每次回主界面都读一次文件）。 */
    private static boolean sCheckedThisProcess = false;

    private UpdateUi() {}

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
        // ★ 模式 = OFF ⇒ **一次请求都不发**（不是"查了但不提示" —— 那已经联网了）。
        //   放在最前面，连"本进程已查"那个标记都不能先占。
        if (!UpdateMode.autoChecks(UpdateMode.of(a))) return;
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
                final boolean preview = UpdateMode.usesPreview(UpdateMode.of(a));
                final String[] err = new String[1];
                UpdateManifest m = loadLatest(preview, err);
                if (m == null) {
                    android.util.Log.i("MDTLauncher", "update check failed: " + err[0]);
                }

                final UpdateManifest fm = m;
                final String ferr = err[0];
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (fm == null) {
                            // ★ 两种"没有"要分开（否则用户会把"还没发版"读成"网坏了"）：
                            //   · NO_RELEASE ⇒ 这个通道确实没有可装的版本
                            //   · 其它       ⇒ 网络 / 数据坏
                            if (!manual) return;
                            if (NO_RELEASE.equals(ferr)) {
                                if (ui[0] != null && ui[0].isShowing()) {
                                    ui[0].replace(Trans.get(a, R.string.set_update_title),
                                            Trans.get(a, R.string.update_latest), closeBtn(a, ui[0]));
                                }
                                return;
                            }
                            boolean net = ferr != null && (ferr.contains("http")
                                    || ferr.contains("IOException") || ferr.contains("timeout"));
                            showError(a, ui[0], net ? R.string.update_err_network : R.string.update_err_data);
                            return;
                        }
                        int cur = UpdateInstall.currentVersionCode(a);
                        if (!UpdateManifest.isNewer(cur, fm.versionCode)) {
                            if (manual && ui[0] != null && ui[0].isShowing()) {
                                ui[0].replace(Trans.get(a, R.string.set_update_title),
                                        Trans.get(a, R.string.update_latest), closeBtn(a, ui[0]));
                            }
                            return;
                        }
                        showFound(a, ui[0], fm);
                    }
                });
            }
        }, "mdt-update-check").start();
    }

    /**
     * 拉 release 列表 → 挑一个 → 拉它的 `update.json` → 解析。
     *
     * @param allowPreview 「稳定版和预览版」模式（否则只认非 prerelease）
     * @return 失败时 null，原因放 {@code err[0]}；{@link #NO_RELEASE} 表示"确实没有可装的版本"
     */
    private static UpdateManifest loadLatest(boolean allowPreview, String[] err) {
        String listJson;
        try {
            // 10 条 release 的 JSON（含 body）大概几十 KB；给 512 KB 足够宽松
            listJson = UpdateClient.fetchText(releasesUrl(), 512 * 1024);
        } catch (Throwable t) {
            err[0] = String.valueOf(t);
            return null;
        }

        // ★ 第 336 轮：严格解析 —— 坏 JSON（镜像/代理的 HTML 错误页、限流对象）
        //   必须与"合法空数组"分开，否则会显示成"已是最新"。
        List<GitHubReleases.Release> list = GitHubReleases.parseStrict(listJson);
        if (list == null) {
            err[0] = "bad-release-json";
            return null;
        }
        GitHubReleases.Release r = GitHubReleases.pick(list, allowPreview);
        if (r == null) {
            err[0] = NO_RELEASE;
            return null;
        }
        GitHubReleases.Asset asset = GitHubReleases.asset(r, MANIFEST_ASSET);
        if (asset == null) {
            err[0] = "no " + MANIFEST_ASSET + " in release " + r.tag;
            return null;
        }
        // ★ 第 336 轮：**优先走 `browser_download_url`**（github.com 主站，302 到
        //   release-assets），不再优先 `api.github.com/.../assets/<id>` —— 后者会
        //   302 到 objects.githubusercontent.com，在本机运营商网络上实测 2 KB range
        //   12 秒超时、773 KB 只下 108 KB/69 秒。旧清单里的 apiUrl 只当兜底。
        String url = (asset.browserUrl != null && !asset.browserUrl.isEmpty())
                ? asset.browserUrl : asset.apiUrl;
        if (url == null || url.isEmpty()) {
            err[0] = "manifest has no url";
            return null;
        }
        List<String> urls = Mirrors.assetUrls(url, Config.get().updateMirror());
        String last = "manifest fetch failed";
        long started = System.currentTimeMillis();
        for (String u : urls) {
            // 清单只有几百字节：给整段一个 20 秒预算，别让 5 个死候选把检查拖到一分多钟。
            if (System.currentTimeMillis() - started > 20000L) {
                last = "timeout";
                break;
            }
            boolean mirror = Mirrors.isMirrorUrl(u);
            try {
                // asset API 必须点名 octet-stream，否则拿回来的是元数据 JSON（见 UpdateClient.open 的注释）
                String json = UpdateClient.fetchText(u, UpdateManifest.MAX_BYTES,
                        "application/octet-stream",
                        mirror ? 4000 : UpdateClient.CONNECT_TIMEOUT_MS,
                        mirror ? 6000 : 30000);
                UpdateManifest m = UpdateManifest.parse(json);
                if (m.ok()) return m;
                last = m.error;
            } catch (Throwable t) {
                last = String.valueOf(t);
            }
        }
        err[0] = last;
        return null;
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
                // 第 336 轮：按「选中线路 → 其余预设 → 直连」的顺序试；
                // 正常路径不测速（测速只在设置页手动触发）。
                final List<String> urls = Mirrors.assetUrls(m.url, Config.get().updateMirror());
                final UpdateDownload.Result r = UpdateDownload.run(
                        urls, UpdateFetcher.dir(a), m.versionCode, m.size, m.sha256,
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
