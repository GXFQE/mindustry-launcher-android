package io.mdt.launcher;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「导出统计」的**唯一落地实现**（第 154 轮，第 155 轮补「直接分享」）：声明拼装 + 地图身份取证
 * + 写出 + 成败提示。
 *
 * <p>调用方只有一条（第 154 轮把详情页那个入口删了）：地图页勾几张批量
 * （{@link MapsActivity}）。**出口有两个**，共用同一份文本与同一个文件名：
 * <ul>
 *   <li>{@link #begin}  —— 保存到用户选的 SAF 落点（{@link Exporter#createDoc}）；</li>
 *   <li>{@link #stage} + {@link #launchShare} —— 写进 `cache/share/`，由
 *       {@link ShareProvider} 以 `content://` 交给系统分享面板（**不落用户目录**）。</li>
 * </ul>
 * 🔴 为什么这两个出口必须共用 {@link #fileName} / {@link #MIME} / 声明拼装：文件名、MIME、
 * 声明字段、隐私口径（不带路径）、失败措辞这五处任何一处抄第二遍都会漂，而"导出文件里
 * 少了/多了什么"**在界面上没有任何症状** —— 等收数据的人发现时已经发出去了
 * （与 `ref/29` 第 88 条同族）。</p>
 *
 * <h3>格式</h3>
 * JSONL（{@link MapStatsExport#FORMAT}）：一行声明 + 一行一条记录；批量 = 每张图一个这样的块，
 * `index` / `count` 标位置。写出去的是 UTF-8（{@link Exporter#writeText}），**不带 BOM**。
 */
final class StatsExporter {

    /**
     * `application/x-ndjson` = JSON Lines 的正式类型。
     *
     * <p>🔴 别改回 `text/plain`：本机真机实测（HONOR / Android 16）用 text/plain 时
     * DocumentsUI 会给建议文件名**再补一个 `.txt`**（`…jsonl.txt`）；这个类型它不认识 ⇒
     * 名字原样保留（同一条路径 14:59 那次验过）。</p>
     */
    static final String MIME = "application/x-ndjson";
    /** SAF 回来时认这个请求码（两个调用方共用一个） */
    static final int REQ = 1542;

    /**
     * 「导出统计」的四个出口 = **模式（详细 / 简洁）× 去处（保存 / 分享）**（第 155 轮）。
     *
     * <p>★ 序号**只由 {@link #optionLabels()} 的顺序决定**；语义一律走 {@link #modeOf(int)} /
     * {@link #shares(int)} —— 对话框里点第几个与"第几个"没有关系，改顺序不会让语义漂。</p>
     */
    static final int OPT_SAVE_DETAIL = 0;
    static final int OPT_SAVE_BRIEF = 1;
    static final int OPT_SHARE_DETAIL = 2;
    static final int OPT_SHARE_BRIEF = 3;

    /** 分享缓存留多久（毫秒）：**给接收方足够的时间读完**，下次分享时顺手清掉更老的 */
    private static final long KEEP_MS = 24L * 3600L * 1000L;

    /** 等 SAF 回来时那几样东西（**成败都先清空**，见 {@link #handle}） */
    private static String sText;
    private static String sName;
    private static int sMaps;
    private static int sSkipped;

    private StatsExporter() {}

    // ── 出口：文本拼好了 → 让用户选落点 ──────────────────────────────────

    /**
     * 把**已经拼好**的文本交出去（本方法只负责 SAF，不统计、不落盘）。
     *
     * @param mime    单张 = `application/json`；批量 = {@link #MIME}（`application/x-ndjson`）
     * @param maps    文件里有几张图（1 = 单张，走单张那句提示）
     * @param skipped 有几张**读不出**被跳过（0 = 不提这茬）
     */
    static void begin(Activity a, String name, String text, String mime, int maps, int skipped) {
        sText = text;
        sName = name;
        sMaps = Math.max(1, maps);
        sSkipped = Math.max(0, skipped);
        a.startActivityForResult(
                Exporter.createDoc(name, mime == null || mime.isEmpty() ? MIME : mime), REQ);
    }

    /** 在 Activity 的 `onActivityResult` 里调；不是我们的请求码就返回 false（别抢别人的码） */
    static boolean handle(final Activity a, int req, int res, Intent data) {
        if (req != REQ) return false;
        // ★ 与地图导出同一条纪律：无论成败**先清掉待写的那一份**（否则下一次导出会写错内容）
        final String text = sText;
        final String name = sName;
        final int maps = sMaps;
        final int skipped = sSkipped;
        sText = null;
        sName = null;
        sMaps = 1;
        sSkipped = 0;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null || text == null) {
            return true;
        }
        final Uri uri = data.getData();
        final ProgressDialog pd = ProgressDialog.show(a,
                Trans.get(a, R.string.stats_export),
                Trans.get(a, R.string.stats_export_working), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                long n = -1;
                Throwable err = null;
                try {
                    n = Exporter.writeText(a, uri, text);
                } catch (Throwable t) {
                    err = t;
                }
                final long fn = n;
                final Throwable fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (Util.dead(a)) return;
                        if (fe != null) {
                            new android.app.AlertDialog.Builder(a)
                                    .setTitle(Trans.get(a, R.string.export_failed))
                                    .setMessage(Util.ioReason(a, fe,
                                            Trans.get(a, R.string.exp_err_dest_readonly)))
                                    .setPositiveButton(R.string.close, null)
                                    .show();
                            return;
                        }
                        String msg;
                        if (maps <= 1) {
                            msg = Trans.get(a, R.string.stats_export_ok_fmt, name, Util.formatSize(fn));
                        } else if (skipped > 0) {
                            msg = Trans.get(a, R.string.stats_export_batch_ok_skipped_fmt,
                                    maps, Util.formatSize(fn), skipped);
                        } else {
                            msg = Trans.get(a, R.string.stats_export_batch_ok_fmt,
                                    maps, Util.formatSize(fn));
                        }
                        Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "stats-export-write").start();
        return true;
    }

    // ── 直接分享（第 155 轮）：写缓存 → content:// → 系统分享面板 ─────────────

    /** 四个出口在对话框里的**顺序**（只在这里定义一次；语义见 {@link #modeOf} / {@link #shares}） */
    static int[] optionLabels() {
        return new int[]{
                R.string.stats_export_opt_save_detail,
                R.string.stats_export_opt_save_brief,
                R.string.stats_export_opt_share_detail,
                R.string.stats_export_opt_share_brief};
    }

    /** 这个出口用哪种口径（详细 / 简洁） */
    static int modeOf(int opt) {
        return opt == OPT_SAVE_BRIEF || opt == OPT_SHARE_BRIEF
                ? MapStatsExport.MODE_BRIEF : MapStatsExport.MODE_DETAIL;
    }

    /** 这个出口是"直接分享"还是"保存到文件" */
    static boolean shares(int opt) {
        return opt == OPT_SHARE_DETAIL || opt == OPT_SHARE_BRIEF;
    }

    /**
     * 把文本写进 `cache/share/<name>`，返回给分享面板用的 `content://`。
     *
     * <p>★ 与 {@link #begin} 的区别只是**去哪**：这条不经过 SAF、不落用户目录；
     * 文件名/MIME/内容与保存那条**逐字相同**（同一个 {@link #fileName}、同一份文本）。</p>
     *
     * <p>⚠️ 写盘的字节是 UTF-8 **不带 BOM**（与 {@link Exporter#writeText} 同一条口径：
     * 带 BOM 的话 JSON 解析器会在第一个字符上翻车）。</p>
     */
    static Uri stage(Context c, String name, String text) throws IOException {
        if (!ShareProvider.safeName(name)) throw new IOException("bad share name");
        File d = ShareProvider.dirOf(c);
        // ★ 顺手清旧：**不能立刻删刚写完的那份**（微信是异步读的），所以只清 24h 之前的。
        prune(d, System.currentTimeMillis() - KEEP_MS);
        File f = new File(d, name);
        OutputStream out = null;
        try {
            out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f), 65536);
            out.write((text == null ? "" : text).getBytes("UTF-8"));
            out.flush();
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) { }
            }
        }
        return ShareProvider.uriOf(name);
    }

    /**
     * 清掉 `dir` 里早于 `cutoff` 的文件，返回删掉几个。
     *
     * <p>★ 纯函数（吃目录 + 时刻）：自检拿一个临时目录就能钉住"老的删、新的留"，
     * 不必等一天。</p>
     */
    static int prune(File dir, long cutoff) {
        File[] fs = dir == null ? null : dir.listFiles();
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.lastModified() < cutoff && f.delete()) n++;
            }
        }
        return n;
    }

    /**
     * 打开系统分享面板（**不自己列 App**：微信/QQ/蓝牙/荣耀分享都靠系统选择器，
     * 自己维护一份名单既要跟着别人改包名，又拿不到系统的"常用联系人"排序）。
     *
     * <p>🔴 两个细节缺一不可：{@code FLAG_GRANT_READ_URI_PERMISSION} 给接收方一次读权限；
     * {@code ClipData} 再显式带一份 —— `EXTRA_STREAM` 在部分版本上不会被自动搬进 ClipData，
     * 授权就跟着丢了（结果是接收方打开时 `SecurityException`）。</p>
     */
    static void launchShare(Activity a, Uri uri, String mime) {
        if (uri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(android.content.ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, Trans.get(a, R.string.share_chooser)));
        } catch (Throwable t) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle(Trans.get(a, R.string.export_failed))
                    .setMessage(Trans.get(a, R.string.share_no_app))
                    .setPositiveButton(R.string.close, null)
                    .show();
        }
    }

    // ── 声明里的那几样东西（单张 / 批量共用） ────────────────────────────

    /**
     * 拼一份声明的数据部分。
     *
     * @param sha 地图字节的 sha256（{@link #shaOfMap}；算不出来传空串 —— **不编**）
     */
    static MapStatsExport.Meta metaOf(Context c, String slot, Maps.Item it,
                                      MapStatsMods.Built built, String apkPath, String sha, int mode) {
        MapStatsExport.Meta m = new MapStatsExport.Meta();
        // ★ 带时区偏移（`XXX`）—— 只写本地时间的话，别人看到"17:20"不知道是哪个时区
        m.exportedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
                .format(new Date());
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            m.launcherVersion = pi.versionName == null ? "" : pi.versionName;
            m.launcherCode = pi.versionCode;
        } catch (Throwable ignored) {
        }
        m.launcherPackage = c.getPackageName();
        m.slot = slot == null ? "" : slot;
        m.mapName = it == null ? "" : it.name();
        m.mapSource = it == null ? "" : it.from == Maps.FROM_SLOT ? "slot"
                : it.from == Maps.FROM_GAME ? "game" : "mod";
        m.mapWhere = whereOf(it);
        m.mapBytes = it == null ? -1 : it.file != null ? it.file.length() : it.bytes;
        m.mapSha256 = sha == null ? "" : sha;
        m.planet = it == null || it.meta == null || it.meta.planet == null ? "" : it.meta.planet;
        m.gameApk = apkPath == null ? "" : new File(apkPath).getName();
        m.gameVersion = apkVersion(c, apkPath);
        m.tableRows = built == null || built.table == null ? -1 : built.table.byName.size();
        m.mods = built == null ? -1 : built.packs;
        m.modsSkipped = built != null && built.modsSkipped;
        m.modsFailed = built != null && built.modsFailed;
        m.notice = Trans.get(c, R.string.stats_export_notice_fmt, MapStatsExport.SITE);
        m.mode = mode;
        return m;
    }

    /**
     * 声明里「这张图在哪」的**唯一实现**：槽内 = **文件名**，包内 = `容器名!/条目`。
     *
     * <p>🔴 为什么不能直接用 {@link Maps.Item#where}：那个字段对槽内地图是**绝对路径**
     * （`/storage/emulated/0/Android/data/…`）—— 导出文件是给人转发用的，本机路径与设备标识
     * 一律不许进（与分享包同一个隐私口径）。自检直接调这个方法来钉，别让它在 UI 里"看运气"。</p>
     */
    static String whereOf(Maps.Item it) {
        if (it == null) return "";
        if (it.file != null) return it.file.getName();
        if (it.container == null) return "";
        return it.container.getName() + "!/" + it.entry;
    }

    /** 统计用的那个版本 APK 的版本名（读不到就空 —— 声明里宁可空着也不编） */
    static String apkVersion(Context c, String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(path, 0);
            return pi == null || pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 地图字节的 sha256（**定位它最硬的证据**：同一张图换个文件名也对得上）。
     *
     * <p>两种载体：槽内是**真文件**（直接流式哈希），游戏自带 / 模组自带是**容器里的一条条目**
     * （借 {@link Exporter#copyEntry} 流式喂给 {@link DigestOutputStream}，不整条读进内存）。</p>
     *
     * @return 十六进制小写；算不出来返回空串（**不编**，声明里就写 `""`）
     */
    static String shaOfMap(Context c, Maps.Item it) {
        if (it == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (it.file != null && it.file.isFile()) {
                InputStream in = new FileInputStream(it.file);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
            } else if (it.container != null && it.entry != null) {
                OutputStream sink = new OutputStream() {
                    @Override public void write(int b) { }
                    @Override public void write(byte[] b, int off, int len) { }
                };
                DigestOutputStream d = new DigestOutputStream(sink, md);
                Exporter.copyEntry(c, it.container, it.entry, d);
                d.flush();
            } else {
                return "";
            }
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "map sha failed", t);
            return "";
        }
    }

    /**
     * 建议文件名：单张 = `mdt-stats-<图名>-<时间>.json`（一个 JSON 文档）；
     * 批量 = `mdt-stats-<槽>-<N>maps-<时间>.jsonl`。
     *
     * ★ 文件名里**只放图名 / 槽名**，不放路径 —— 名字是要给收数据的人看的。
     */
    static String fileName(String slot, Maps.Item it, int maps) {
        String base;
        if (maps > 1) {
            base = (slot == null || slot.isEmpty() ? "maps" : slot) + "-" + maps + "maps";
        } else {
            base = it == null ? "map" : it.name();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        base = safeName(base);
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        return "mdt-stats-" + (base.isEmpty() ? "" : base + "-") + ts
                + (maps > 1 ? ".jsonl" : ".json");
    }

    /** 文件名安全化（去掉路径分隔符与控制字符、空格换连字符、截断） */
    static String safeName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 40; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            if ("\\/:*?\"<>|".indexOf(c) >= 0) continue;
            b.append(c == ' ' ? '-' : c);
        }
        return b.toString().trim();
    }
}
