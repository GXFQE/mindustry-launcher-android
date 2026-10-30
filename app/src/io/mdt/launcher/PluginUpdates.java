package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **插件自动更新**的编排（第 338 轮）。
 *
 * <h3>它回答的问题</h3>
 * 每个插件可以在自己的 {@code assets/plugin.json} 里写一个 {@code update} 地址（更新清单）。
 * 宿主定期（24 h）去拉、比对**内容哈希**，有新版就下载 → 校验 → 装成"待生效"。
 * 用户 2026-10-30 定：**自动下载并装好，下次启动生效；只有签名换了才问**。
 *
 * <h3>为什么判据是 sha256 而不是版本号</h3>
 * 用户原话：「插件这东西鬼知道版本号咋写的」。清单里的 {@code sha256} 是**远端那一份的哈希**，
 * 和本机这份比：不同就是有更新。不解析 semver、不比大小、不依赖作者写没写版本号。
 *
 * <h3>安全边界（三件套，缺一不可）</h3>
 * <ol>
 *   <li>清单地址**只认 https**（回环除外，dev 夹具）—— 与启动器自更新同一把尺子；</li>
 *   <li>下载完**校 sha256**（{@link UpdateDownload}，先 `.part` 再改名）＋ 至少是个能解析的 APK；</li>
 *   <li>**签名与当前那一份不同 ⇒ 不自动装**，落 {@code confirm} 让用户点一下
 *       （同一个作者发布的更新不打扰；换作者 / 换钥匙必须问）。</li>
 * </ol>
 *
 * <p>⚠️ 它**不碰界面**（除启动时那一次 Toast）：手动检查的卡片、行内的「有新版」都由
 * {@code PluginsActivity} 按这里的 {@link PluginUpdateState} 渲染。
 */
final class PluginUpdates {

    private static final String TAG = "MDTLauncher";

    /** 清单缓存的目录名（在 `app_hub/plugins/` 下）。 */
    static final String CACHE_DIR = "updates";
    /** 检查结果表（{@link PluginUpdateState} 的落盘文件名）。 */
    static final String STATE = "update-state.txt";
    /** 上次检查时刻（epoch 秒；24 h 去重）。 */
    static final String CHECK_AT = "update-check-at.txt";
    /** 启动报告（内部存储；dev 口会抄进 hub）。 */
    static final String REPORT = "report-plugin-update.txt";

    /** 静默检查间隔（与启动器自更新同一档）。 */
    static final long INTERVAL_SEC = 24L * 3600L;
    /** 一次全量检查的总预算：到点就不再查剩下的（别让一个死站点把检查拖成几分钟）。 */
    static final long CHECK_BUDGET_MS = 150000L;
    /** 拉**一份**清单的总预算（几个镜像候选加起来）。 */
    static final long MANIFEST_BUDGET_MS = 20000L;

    /** 本进程是否已经静默查过（避免每次回主界面都读文件）。 */
    private static boolean sCheckedThisProcess;
    /** 正在检查（手动与自动不叠加；也防止两次写状态表打起来）。 */
    private static volatile boolean sRunning;

    /** 一次检查的汇总（给界面的结果卡用）。 */
    static final class Summary {
        int total, checked, installed, confirm, available, failed;
        /** 上一次检查还没跑完（这次什么都没做）。 */
        boolean busy;
        String report = "";
        final List<String> lines = new ArrayList<String>();

        boolean anyChange() { return installed > 0 || confirm > 0 || available > 0; }
        boolean allOk() { return failed == 0 && installed == 0 && confirm == 0 && available == 0; }
    }

    private PluginUpdates() {}

    // ---- 路径 -----------------------------------------------------------------

    static File dir(Context ctx) { return Plugins.dir(ctx); }

    /** 下载落点：**外部** hub（不参与 dex 加载；装的时候才拷进内部）。 */
    static File downloadDir(Context ctx) { return new File(Data.hubDir(ctx), "plugins-update"); }

    static File downloaded(Context ctx, String id, String sha) {
        String s8 = PluginUpdateManifest.sha8(sha);
        if (id == null || s8 == null) return null;
        return new File(downloadDir(ctx), id + "-" + s8 + ".apk");
    }

    // ---- 开关与状态 -------------------------------------------------------------

    /** 用户开关（设置页「插件自动更新」）；默认开。 */
    static boolean autoOn(Context c) { return Config.get().pluginAutoUpdate(); }

    static String stateText(Context ctx) {
        try {
            String s = Util.readText(new File(dir(ctx), STATE));
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void saveState(Context ctx, String text) {
        try {
            Util.atomicWriteText(new File(dir(ctx), STATE), text == null ? "" : text);
        } catch (Throwable t) {
            Log.w(TAG, "plugin update state write failed: " + t);
        }
    }

    /** 某个插件上一次检查的结果；没查过 ⇒ null。 */
    static PluginUpdateState.Item itemOf(Context ctx, String id) {
        return PluginUpdateState.get(stateText(ctx), id);
    }

    /** 别人（界面）拿到一条结果后写回（"用户点了更新"要落盘）。 */
    static void putItem(Context ctx, String id, String state, String sha, String ver, String note) {
        String text = PluginUpdateState.put(stateText(ctx), id, state, sha, ver,
                System.currentTimeMillis() / 1000L, note);
        saveState(ctx, text);
    }

    /** 上次检查时刻（epoch 秒；0 = 从没查过）。 */
    static long lastCheckAt(Context ctx) {
        try {
            String s = Util.readText(new File(dir(ctx), CHECK_AT));
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 界面显示用的"这一版叫什么"：清单里的 version 优先，否则 sha 前 8 位。 */
    static String labelOf(PluginUpdateState.Item it) {
        if (it == null) return "";
        if (it.ver != null && !it.ver.trim().isEmpty()) return it.ver.trim();
        String s8 = PluginUpdateManifest.sha8(it.sha);
        return s8 == null ? "" : s8;
    }

    // ---- 启动时的静默检查 -------------------------------------------------------

    /**
     * 启动时那一次：**开关开着、24 小时内只查一次**。
     *
     * <p>时间戳**先写再查**（与 {@code UpdateUi.checkSilentIfDue} 同一条纪律）：
     * 宁可少查一次，也不要在"写不进去"的机器上每次启动都联网。
     * 时钟回拨由 {@link UpdateManifest#shouldCheck} 兜底（回拨 ⇒ 当成该查）。
     */
    static void checkDue(final Activity a) {
        if (a == null || !autoOn(a)) return;
        if (sCheckedThisProcess || sRunning) return;
        sCheckedThisProcess = true;
        long now = System.currentTimeMillis() / 1000L;
        File stamp = new File(dir(a), CHECK_AT);
        long last = lastCheckAt(a);
        if (!UpdateManifest.shouldCheck(now, last, INTERVAL_SEC)) return;
        try {
            Util.atomicWriteText(stamp, String.valueOf(now));
        } catch (Throwable ignored) {
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final Summary sum = checkAll(a);
                if (sum.installed + sum.confirm <= 0) return;
                try {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (a.isFinishing()) return;
                            // 两件事分开说：需要确认的优先（那是要用户动手的），没有才报"已更新"
                            int res = sum.confirm > 0
                                    ? R.string.plugin_update_toast_confirm
                                    : R.string.plugin_update_toast_installed;
                            int n = sum.confirm > 0 ? sum.confirm : sum.installed;
                            Toast.makeText(a, Trans.get(a, res, String.valueOf(n)),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "mdt-plugin-update").start();
    }

    // ---- 全量检查 ---------------------------------------------------------------

    /**
     * 查一遍**所有装了、且声明了更新地址**的插件。**阻塞**，调用方负责线程。
     *
     * @return 汇总（含给报告 / 界面看的文本）
     */
    static Summary checkAll(Context ctx) {
        return checkAll(ctx, null, null);
    }

    /**
     * 同上，但可以**只把某个 id 的清单地址换成别的**。
     *
     * <p>★ 只给 dev 口用（`dev_plugupdate_id` + `dev_plugupdate_url`）：内置插件的
     * `plugin.json` 里写的是**线上通道**，真机想验"在线升级过之后不被本体顶掉"就必须
     * 让它去拉本地夹具 —— 否则只能改已发布的资产（那是更坏的做法）。
     * 产品代码路径（地址从插件自己的 plugin.json 读）不受影响：不传就是 null。
     */
    static Summary checkAll(Context ctx, String forceId, String forceUrl) {
        Summary sum = new Summary();
        if (sRunning) {
            sum.busy = true;
            sum.report = "busy: another check is running";
            return sum;
        }
        sRunning = true;
        try {
            long started = System.currentTimeMillis();
            String state = stateText(ctx);
            List<String> ids = Plugins.installedIds(ctx);
            StringBuilder rep = new StringBuilder();
            rep.append("plugin-update check  api=").append(PluginHost.API)
                    .append(" sdk=").append(Build.VERSION.SDK_INT)
                    .append(" auto=").append(autoOn(ctx)).append('\n');

            for (String id : ids) {
                if (System.currentTimeMillis() - started > CHECK_BUDGET_MS) {
                    rep.append(id).append(" => skipped (budget)\n");
                    sum.failed++;
                    sum.lines.add(id + "=budget");
                    continue;
                }
                String[] cur = current(ctx, id);
                if (cur == null) continue;
                String curVer = cur[0];
                File apk = Plugins.apkOf(ctx, id, curVer);
                String url = (forceId != null && forceId.equals(id) && forceUrl != null)
                        ? forceUrl : PluginMeta.updateUrlOf(apk);
                if (url == null) {
                    // 没声明地址 = 这个插件不参与自动更新（**不写状态**：免得信息页说"检查失败"）
                    continue;
                }
                sum.total++;
                String localSha = PluginTrust.sha256File(apk);
                if (localSha == null) {
                    state = note(ctx, state, id, PluginUpdateState.FAILED, null, null,
                            "local-unreadable");
                    sum.failed++;
                    sum.lines.add(id + "=local-unreadable");
                    rep.append(id).append(" => FAILED local-unreadable\n");
                    continue;
                }
                String[] err = new String[1];
                String json = fetch(url, err);
                if (json == null) {
                    state = note(ctx, state, id, PluginUpdateState.FAILED, null, null,
                            "manifest: " + err[0]);
                    sum.failed++;
                    sum.lines.add(id + "=manifest-failed");
                    rep.append(id).append(" => FAILED manifest: ").append(err[0]).append('\n');
                    continue;
                }
                PluginUpdateManifest m = PluginUpdateManifest.parse(json, PluginHost.API);
                if (!m.ok()) {
                    state = note(ctx, state, id, PluginUpdateState.FAILED, null, null,
                            "manifest: " + m.error);
                    sum.failed++;
                    sum.lines.add(id + "=bad-manifest");
                    rep.append(id).append(" => FAILED manifest: ").append(m.error).append('\n');
                    continue;
                }
                if (!m.matches(id)) {
                    state = note(ctx, state, id, PluginUpdateState.FAILED, null, null,
                            "manifest: plugin-mismatch");
                    sum.failed++;
                    sum.lines.add(id + "=mismatch");
                    rep.append(id).append(" => FAILED plugin-mismatch (")
                            .append(m.plugin).append(" != ").append(id).append(")\n");
                    continue;
                }
                sum.checked++;
                String label = (m.version == null || m.version.isEmpty())
                        ? PluginUpdateManifest.sha8(m.sha256) : m.version;

                if (!PluginUpdateManifest.differs(localSha, m.sha256)) {
                    state = note(ctx, state, id, PluginUpdateState.OK, m.sha256, label, "");
                    rep.append(id).append(" => ok (").append(label).append(")\n");
                    continue;
                }

                rep.append(id).append(" => update ").append(curVer).append(" -> ").append(label)
                        .append(" sha=").append(PluginUpdateManifest.sha8(m.sha256)).append('\n');

                if (!autoOn(ctx)) {
                    // 自动更新关着 ⇒ 只记一笔"有新版"，等用户点
                    state = note(ctx, state, id, PluginUpdateState.AVAILABLE, m.sha256, label, "");
                    sum.available++;
                    sum.lines.add(id + "=available");
                    continue;
                }

                File out = downloaded(ctx, id, m.sha256);
                if (!verified(out, m.sha256)) {
                    UpdateDownload.Result d = download(ctx, id, m);
                    if (d == null || d.status != UpdateDownload.Status.OK
                            || !verified(d.file, m.sha256) || !looksLikeApk(d.file)) {
                        String why = d == null ? "null"
                                : d.status == UpdateDownload.Status.OK ? "bad-apk"
                                : d.status.name().toLowerCase(Locale.US);
                        state = note(ctx, state, id, PluginUpdateState.FAILED, m.sha256, label,
                                "download: " + why);
                        sum.failed++;
                        sum.lines.add(id + "=download-failed");
                        rep.append("    download FAILED ").append(why).append('\n');
                        continue;
                    }
                    out = d.file;
                }

                // 签名：同一个作者发的更新**不打扰**；不同 ⇒ 落 confirm（用户点一下才装）
                String curFp = PluginTrust.apkFingerprint(ctx, apk);
                String newFp = PluginTrust.apkFingerprint(ctx, out);
                if (curFp == null || newFp == null || !curFp.equals(newFp)) {
                    state = note(ctx, state, id, PluginUpdateState.CONFIRM, m.sha256, label,
                            "signature changed");
                    sum.confirm++;
                    sum.lines.add(id + "=confirm");
                    rep.append("    signature changed: cur=").append(PluginTrust.shortFp(curFp))
                            .append(" new=").append(PluginTrust.shortFp(newFp)).append('\n');
                    continue;
                }

                String r = install(ctx, id, out, m);
                if (r.startsWith("FAILED")) {
                    state = note(ctx, state, id, PluginUpdateState.FAILED, m.sha256, label, r);
                    sum.failed++;
                    sum.lines.add(id + "=install-failed");
                    rep.append("    install FAILED: ").append(r).append('\n');
                } else {
                    state = note(ctx, state, id, PluginUpdateState.INSTALLED, m.sha256, label, "");
                    sum.installed++;
                    sum.lines.add(id + "=installed");
                    rep.append("    installed ").append(PluginUpdateManifest.sha8(m.sha256))
                            .append(" (pending)\n");
                }
            }

            saveState(ctx, state);
            cleanupDownloads(ctx, state);
            rep.append("summary: total=").append(sum.total).append(" checked=").append(sum.checked)
                    .append(" installed=").append(sum.installed).append(" confirm=").append(sum.confirm)
                    .append(" available=").append(sum.available).append(" failed=").append(sum.failed)
                    .append(" ms=").append(System.currentTimeMillis() - started).append('\n');
            sum.report = rep.toString();
            writeReport(ctx, sum.report);
            Log.i(TAG, "plugin update check: " + sum.report.replace('\n', '|'));
            return sum;
        } finally {
            sRunning = false;
        }
    }

    /**
     * 用户点「更新」时走这一条：拉清单 → 下载 → 签名比对 →（可选）装。
     *
     * @param force true = 用户已经确认过"签名不同也照装"
     * @return {@code ok} / {@code confirm}（下好了但签名不同，等确认）/ {@code uptodate} /
     *         {@code failed:…}
     */
    static String updateNow(Context ctx, String id, boolean force) {
        String[] cur = current(ctx, id);
        if (cur == null) return "failed:no-current";
        File apk = Plugins.apkOf(ctx, id, cur[0]);
        String url = PluginMeta.updateUrlOf(apk);
        if (url == null) return "failed:no-url";
        String localSha = PluginTrust.sha256File(apk);
        String[] err = new String[1];
        String json = fetch(url, err);
        if (json == null) return "failed:" + err[0];
        PluginUpdateManifest m = PluginUpdateManifest.parse(json, PluginHost.API);
        if (!m.ok()) return "failed:" + m.error;
        if (!m.matches(id)) return "failed:plugin-mismatch";
        String label = (m.version == null || m.version.isEmpty())
                ? PluginUpdateManifest.sha8(m.sha256) : m.version;
        if (!PluginUpdateManifest.differs(localSha, m.sha256)) {
            putItem(ctx, id, PluginUpdateState.OK, m.sha256, label, "");
            return "uptodate";
        }
        File out = downloaded(ctx, id, m.sha256);
        if (!verified(out, m.sha256)) {
            UpdateDownload.Result d = download(ctx, id, m);
            if (d == null || d.status != UpdateDownload.Status.OK
                    || !verified(d.file, m.sha256) || !looksLikeApk(d.file)) {
                putItem(ctx, id, PluginUpdateState.FAILED, m.sha256, label, "download failed");
                return "failed:download";
            }
            out = d.file;
        }
        String curFp = PluginTrust.apkFingerprint(ctx, apk);
        String newFp = PluginTrust.apkFingerprint(ctx, out);
        if (!force && (curFp == null || newFp == null || !curFp.equals(newFp))) {
            putItem(ctx, id, PluginUpdateState.CONFIRM, m.sha256, label, "signature changed");
            return "confirm";
        }
        String r = install(ctx, id, out, m);
        if (r.startsWith("FAILED")) {
            putItem(ctx, id, PluginUpdateState.FAILED, m.sha256, label, r);
            return "failed:" + r;
        }
        putItem(ctx, id, PluginUpdateState.INSTALLED, m.sha256, label, "");
        return "ok";
    }

    /** 给界面：这个插件要不要显示"有新版"一行（以及该说什么）。 */
    static boolean showsUpdate(Context ctx, String id) {
        PluginUpdateState.Item it = itemOf(ctx, id);
        return it != null && PluginUpdateState.pending(it.state);
    }

    // ---- 内部 -------------------------------------------------------------------

    /** 这一程"会用到"的那一版（待生效优先 —— 与插件卡那行同一个口径）。 */
    private static String[] current(Context ctx, String id) {
        String pend = Plugins.stateGet(Plugins.pending(ctx), id);
        String act = Plugins.stateGet(Plugins.active(ctx), id);
        String v = pend != null ? pend : act;
        if (v == null) {
            String[] vs = Plugins.versionsOf(ctx, id);
            if (vs.length == 0) return null;
            v = vs[vs.length - 1];
        }
        return new String[] { v };
    }

    /** 写一条状态（同时更新内存里的整表文本，避免每条都重读文件）。 */
    private static String note(Context ctx, String text, String id, String state, String sha,
                               String ver, String note) {
        return PluginUpdateState.put(text, id, state, sha, ver,
                System.currentTimeMillis() / 1000L, note);
    }

    /**
     * 拉一份清单：按「选中线路 → 其余镜像 → 直连」试，总预算 {@link #MANIFEST_BUDGET_MS}。
     * 插件清单只有几百字节，别让一个死站点把整次检查拖住。
     */
    private static String fetch(String url, String[] err) {
        List<String> urls = Mirrors.assetUrls(url, Config.get().updateMirror());
        long started = System.currentTimeMillis();
        String last = "no candidate";
        for (String u : urls) {
            if (System.currentTimeMillis() - started > MANIFEST_BUDGET_MS) {
                last = "timeout";
                break;
            }
            boolean mirror = Mirrors.isMirrorUrl(u);
            try {
                return UpdateClient.fetchText(u, PluginUpdateManifest.MAX_BYTES, "application/json",
                        mirror ? 4000 : 8000, mirror ? 6000 : 20000);
            } catch (Throwable t) {
                last = String.valueOf(t);
            }
        }
        err[0] = last;
        return null;
    }

    private static UpdateDownload.Result download(Context ctx, String id, PluginUpdateManifest m) {
        List<String> urls = Mirrors.assetUrls(m.url, Config.get().updateMirror());
        String key = id + "-" + PluginUpdateManifest.sha8(m.sha256);
        return UpdateDownload.run(urls, downloadDir(ctx), key, m.size, m.sha256, null);
    }

    /** 文件在、且哈希对得上（缓存复用前的检查）。 */
    private static boolean verified(File f, String sha) {
        if (f == null || !f.isFile() || sha == null) return false;
        return sha.equalsIgnoreCase(PluginTrust.sha256File(f));
    }

    /** 至少像个 APK（zip 里要有清单与 dex）—— 防"镜像错误页被当成包"（那种东西 sha 也会匹配不了，这是第二道）。 */
    static boolean looksLikeApk(File f) {
        if (f == null || !f.isFile()) return false;
        java.util.zip.ZipFile z = null;
        try {
            z = new java.util.zip.ZipFile(f);
            return z.getEntry("AndroidManifest.xml") != null && z.getEntry("classes.dex") != null;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (z != null) z.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 装成"待生效"。
     *
     * <p>★ 插件**本来就停着**时只放文件、不写 pending —— 否则下次启动
     * {@code promotePending} 会把它悄悄启用（用户明明关着）。
     */
    private static String install(Context ctx, String id, File apk, PluginUpdateManifest m) {
        String ver = PluginUpdateManifest.sha8(m.sha256);
        if (ver == null) return "FAILED bad sha";
        boolean enabled = Plugins.stateGet(Plugins.active(ctx), id) != null
                || Plugins.stateGet(Plugins.pending(ctx), id) != null;
        return Plugins.placeUpdate(ctx, apk, id, ver, enabled);
    }

    /** 清掉不再被任何状态引用的下载件（含 `.part`）。 */
    private static void cleanupDownloads(Context ctx, String stateText) {
        File d = downloadDir(ctx);
        File[] files = d.listFiles();
        if (files == null) return;
        List<String> keep = new ArrayList<String>();
        for (PluginUpdateState.Item it : PluginUpdateState.all(stateText)) {
            String s8 = PluginUpdateManifest.sha8(it.sha);
            if (s8 != null) keep.add(it.id + "-" + s8);
        }
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName();
            String base = n.endsWith(".apk") ? n.substring(0, n.length() - 4)
                    : n.endsWith(".part") ? n.substring(0, n.length() - 5) : n;
            if (keep.contains(base)) continue;
            try { f.delete(); } catch (Throwable ignored) { }
        }
    }

    private static void writeReport(Context ctx, String text) {
        try {
            Util.atomicWriteText(new File(dir(ctx), REPORT), text);
        } catch (Throwable t) {
            Log.w(TAG, "plugin update report write failed: " + t);
        }
    }
}
