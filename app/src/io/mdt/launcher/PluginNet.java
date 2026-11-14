package io.mdt.launcher;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mdt.launcher.api.GitHubRelease;

/**
 * **插件网络接口的宿主实现（v39，第 373 轮）**。
 *
 * <p>为什么插件要这个：插件与宿主同进程、没有沙箱，本来就能自己开 `HttpURLConnection` ——
 * 所以这不是"给权限"，而是把宿主**已经调好的那条链**开出去，避免每个插件各踩一遍：
 * <ul>
 *   <li>超时（连接 15 s / 读 60 s）与跟随 302（GitHub 的 asset 会跳 `objects.githubusercontent.com`）；</li>
 *   <li>`Accept` 按接口给（列表只认 `application/json`，asset 只认 `application/octet-stream`）；</li>
 *   <li>用户的**更新线路**（镜像优先、直连兜底）—— 只对 GitHub 域名生效；</li>
 *   <li>`.part` + sha256 + 长度校验，失败删半截；</li>
 *   <li>GitHub API 的**配额缓存**：未登录 60 次/小时是**宿主与插件共用**的，插件轮询会把
 *       启动器自更新一起打停 ⇒ 宿主管住：同一 URL 10 分钟只真请求一次 + 每小时上限。</li>
 * </ul>
 *
 * <p>约束（写进 {@code docs/PLUGIN-API.md}）：https only（本机回环除外）、正文 512 KB、
 * 下载 1 GB、落点只能是插件自己的 cache、GitHub API 每小时 40 次（给自更新留余量）。
 *
 * <p>⚠️ 本类**不是纯逻辑**（碰网络 / 文件），但把可判定的判据都抽成了静态函数
 * （{@link #checkUrl} / {@link #safeFileName} / {@link #noteApiRequest} / 配额），自检直接钉它们；
 * 真网络只在 dev 手验。
 */
final class PluginNet {

    /** 文本 / JSON 正文上限（与宿主自更新清单同一个档）。 */
    static final int FETCH_MAX = 512 * 1024;
    /** 插件下载的体积上限。 */
    static final long DOWNLOAD_MAX = 1024L * 1024 * 1024;
    /** v43：文本上限的硬顶（官方模组列表 725 KB 要过这个口）。 */
    static final int FETCH_MAX_HARD = 2 * 1024 * 1024;
    /** v43：二进制小块（图标）上限。 */
    static final int BYTES_MAX = 256 * 1024;
    /** 同一个 GitHub API URL 的缓存时长。 */
    static final long CACHE_TTL_MS = 10L * 60 * 1000;
    /** GitHub API 每小时最多放行多少次**真实请求**（未登录 60/h，给自更新留余量）。 */
    static final int GITHUB_HOURLY_MAX = 40;
    /** 缓存条目上限（LRU）。 */
    static final int CACHE_MAX = 16;
    /** repo 形状：`owner/name`。 */
    static final String REPO_PATTERN = "^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$";

    private static final String GH_API = "https://api.github.com/repos/";

    /** 带机器码的失败（reason 直接进 {@code Result}）。 */
    static final class Fail extends RuntimeException {
        final String reason;
        Fail(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** 下载中途要中止（取消 / 超限）；{@code UpdateClient.Progress} 不能抛受检异常。 */
    static final class Abort extends RuntimeException {
        final String reason;
        Abort(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    static final class Hit {
        final String text;
        final long at;
        Hit(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    /** LRU：`accessOrder = true` ⇒ 命中会挪到队尾，超 16 条淘汰最老。 */
    private static final LinkedHashMap<String, Hit> CACHE =
            new LinkedHashMap<String, Hit>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Hit> e) {
                    return size() > CACHE_MAX;
                }
            };

    private static long sHourStart;
    private static int sHourCount;

    // ---- 自检注入点（null / 0 = 真网络 / 真时钟）------------------------------

    /** 取文本的唯一入口（真实现 = {@link UpdateClient#fetchText}）；自检换成假的。 */
    interface Fetcher {
        String text(String url, int maxBytes, String accept) throws IOException;
    }

    static volatile Fetcher sTestFetcher;
    static volatile long sTestNow;

    private PluginNet() {}

    /** 自检用完必须清（否则会影响后续自检里的真请求）。 */
    static void resetTestHooks() {
        sTestFetcher = null;
        sTestNow = 0L;
        synchronized (CACHE) {
            CACHE.clear();
        }
        synchronized (PluginNet.class) {
            sHourStart = 0L;
            sHourCount = 0;
        }
    }

    static int testCacheSize() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    static int testQuotaCount() {
        synchronized (PluginNet.class) {
            return sHourCount;
        }
    }

    static long now() {
        long t = sTestNow;
        return t > 0L ? t : System.currentTimeMillis();
    }

    // ---- URL 闸门 ------------------------------------------------------------

    /**
     * URL 是否放行：**必须 https**（本机回环的 `http://127.0.0.1|localhost` 给 dev 用）。
     *
     * @return 失败码（{@code bad-url}）或 null
     */
    static String checkUrl(String url) {
        if (url == null) return "bad-url";
        String u = url.trim();
        if (u.isEmpty() || u.length() > 4096) return "bad-url";
        URI uri;
        try {
            uri = URI.create(u);
        } catch (Throwable t) {
            return "bad-url";
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) return "bad-url";
        if ("https".equalsIgnoreCase(scheme)) return null;
        if ("http".equalsIgnoreCase(scheme)
                && ("127.0.0.1".equals(host) || "localhost".equals(host))) return null;
        return "bad-url";
    }

    static boolean isGithubApi(String url) {
        String host = hostOf(url);
        return "api.github.com".equals(host);
    }

    /** 取小写 host（去掉末尾的点）—— 只有它一处做"这是不是 GitHub API"的判断。 */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return null;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
            return h;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 规范化 URL（scheme / host 小写）—— 让 `HTTPS://API.GitHub.com` 也命中同一份缓存。 */
    private static String normalizeUrl(String url) {
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme();
            String host = hostOf(url);
            if (scheme == null || host == null) return url.trim();
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + host + path + query;
        } catch (Throwable t) {
            return url.trim();
        }
    }

    /**
     * **每一条真的发往 `api.github.com` 的请求都要走这里**（v39 评审 M1）：
     * `fetchText` 与 `download` 共用同一份配额——否则插件用 `download` 就能绕过 40 次/小时，
     * 打光之后启动器自更新拿到 403、表现为"永远查不到新版本"。
     */
    static void noteApiRequest(String url) {
        if (isGithubApi(url)) enforceQuota(now());
    }

    // ---- 文本 ----------------------------------------------------------------

    /** 拉文本（默认上限 512 KB）。失败抛 {@link Fail}。 */
    static String fetchText(String url) {
        return fetchText(url, FETCH_MAX);
    }

    /**
     * 拉文本（v43：{@code maxBytes} 由插件给，宿主钳到 {@link #FETCH_MAX_HARD}）。
     * 会走缓存 / 配额；失败抛 {@link Fail}。
     */
    static String fetchText(String url, int maxBytes) {
        final int cap = capText(maxBytes);
        String bad = checkUrl(url);
        if (bad != null) throw new Fail(bad);
        final String u = url.trim();
        final boolean api = isGithubApi(u);
        String text;
        synchronized (CACHE) {
            Hit h = CACHE.get(cacheKey(u));
            if (h != null && now() - h.at <= CACHE_TTL_MS) {
                return h.text;                      // 命中不算配额
            }
        }
        noteApiRequest(u);
        try {
            Fetcher f = sTestFetcher;
            text = f != null ? f.text(u, cap, "application/json")
                    : UpdateClient.fetchText(u, cap);
        } catch (IOException e) {
            throw new Fail(isRateLimited(e) ? "rate-limited"
                    : (isTooBig(e) ? "size" : "network"));
        }
        if (text == null) text = "";
        if (api) {
            synchronized (CACHE) {
                CACHE.put(cacheKey(u), new Hit(text, now()));
            }
        }
        return text;
    }

    private static String cacheKey(String url) {
        return "json|" + normalizeUrl(url);
    }

    // ---- 大小上限（纯函数，自检直接钉）----------------------------------------

    /** 文本上限：{@code <=0} ⇒ 默认 512 KB；再钳到 2 MB 硬顶。 */
    static int capText(int maxBytes) {
        if (maxBytes <= 0) return FETCH_MAX;
        return Math.min(maxBytes, FETCH_MAX_HARD);
    }

    /** 二进制上限：{@code <=0} ⇒ 256 KB；再钳到 256 KB 硬顶。 */
    static int capBytes(int maxBytes) {
        if (maxBytes <= 0) return BYTES_MAX;
        return Math.min(maxBytes, BYTES_MAX);
    }

    /**
     * v43：拉一小段二进制（图标等）。https only（本机回环除外）、上限 256 KB。
     * 不起任务、不发通知、不落盘 —— 与 {@code download} 的分工在这里。
     */
    static byte[] fetchBytes(String url, int maxBytes) {
        String bad = checkUrl(url);
        if (bad != null) throw new Fail(bad);
        final String u = url.trim();
        noteApiRequest(u);
        try {
            return UpdateClient.fetchBytes(u, capBytes(maxBytes), "application/octet-stream");
        } catch (IOException e) {
            throw new Fail(isRateLimited(e) ? "rate-limited"
                    : (isTooBig(e) ? "size" : "network"));
        }
    }

    // ---- key / 续传状态（v43）-------------------------------------------------

    /** key 规则：`[A-Za-z0-9._-]{1,64}`，不许 `.` 开头（防隐藏文件 / 防 `..`）。 */
    static boolean keyOk(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.isEmpty() || k.length() > 64) return false;
        if (k.startsWith(".")) return false;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** URL → 落点后缀（`.jar` / `.zip`；认不出/太长 ⇒ `.bin`）。 */
    static String suffixOf(String url) {
        String name = safeFileName(url);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return ".bin";
        String ext = name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : ".bin";
    }

    /**
     * v43：下载到 `<cache>/downloads/<key>/f<suffix>`，`resume` 由插件给。
     *
     * <p>与旧入口的区别：旧入口每次一个一次性子目录、失败即删；这里同一 key 的 `.part` 留着，
     * 由 {@link io.mdt.launcher.ResumableDownload} 接上。
     */
    static UpdateDownload.Result download(File cacheDir, String url, String expectedSha256,
                                          String key, boolean resume, UpdateClient.Progress cb) {
        String bad = checkUrl(url);
        if (bad != null) throw new Fail(bad);
        if (!keyOk(key)) throw new Fail("invalid");
        final String sha = expectedSha256 == null ? "" : expectedSha256.trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!sha.isEmpty() && !sha.matches("[0-9a-f]{64}")) throw new Fail("invalid");
        if (resume && sha.isEmpty()) throw new Fail("invalid");
        final String u = url.trim();
        noteApiRequest(u);
        File dir = new File(new File(cacheDir, "downloads"), key);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Fail("io");
        List<String> urls;
        try {
            urls = Mirrors.assetUrls(u, Config.get().updateMirror());
        } catch (Throwable ignored) {
            urls = null;
        }
        if (urls == null || urls.isEmpty()) {
            urls = new ArrayList<String>();
            urls.add(u);
        }
        final UpdateClient.Progress inner = new UpdateClient.Progress() {
            @Override public void onProgress(long done, long total) {
                if (Thread.currentThread().isInterrupted()) throw new Abort("canceled");
                if (done > DOWNLOAD_MAX) throw new Abort("size");
                if (cb != null) cb.onProgress(done, total);
            }
        };
        try {
            return UpdateDownload.run(urls, dir, "f", -1L, sha, inner, suffixOf(u), resume);
        } catch (Abort a) {
            throw new Fail(a.reason);
        }
    }

    /**
     * v43：某个 key 的下载状态（只读）。没有记录 ⇒ null。
     *
     * <p>成品在但 sha 对不上 ⇒ 当没有并删掉（自愈）；`.part` 的长度就是 done。
     */
    static io.mdt.launcher.api.DownloadState downloadState(File cacheDir, String key) {
        if (!keyOk(key)) return null;
        File dir = new File(new File(cacheDir, "downloads"), key);
        if (!dir.isDirectory()) return null;
        File part = new File(dir, "f.part");
        ResumableDownload.Meta m = ResumableDownload.Meta.read(new File(dir, "f.part.meta"));
        long total = m == null ? 0L : m.total;
        String sha = m == null ? null : m.sha;
        File out = null;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                if (!n.startsWith("f.") || n.endsWith(".part") || n.endsWith(".part.meta")) continue;
                out = f;
                break;
            }
        }
        if (out != null && sha != null && !sha.isEmpty()) {
            String got = PluginTrust.sha256File(out);
            if (got == null || !got.equalsIgnoreCase(sha)) {
                out.delete();
                out = null;
            }
        }
        long done = out != null ? out.length() : (part.isFile() ? part.length() : 0L);
        if (out == null && done == 0L && total == 0L) return null;
        return new io.mdt.launcher.api.DownloadState(key, done, total, out != null, out);
    }

    /** 服务器说"限频"时是 403/429（GitHub 的 message 里也常带 rate limit）。 */
    private static boolean isRateLimited(IOException e) {
        String m = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("http 403") || m.contains("http 429") || m.contains("rate limit");
    }

    /** 正文上限是 `UpdateClient` 抛的 IOException（"body too big (> N)"）⇒ 插件侧要给 `size`。 */
    private static boolean isTooBig(IOException e) {
        String m = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("body too big");
    }

    /** 每小时真实请求上限（只统计 `api.github.com`，缓存命中不计）。 */
    private static void enforceQuota(long now) {
        synchronized (PluginNet.class) {
            if (sHourStart == 0L || now - sHourStart >= 3600_000L) {
                sHourStart = now;
                sHourCount = 0;
            }
            if (sHourCount >= GITHUB_HOURLY_MAX) throw new Fail("rate-limited");
            sHourCount++;
        }
    }

    // ---- GitHub release -------------------------------------------------------

    /**
     * 列 {@code owner/name} 的 release（最新在前，最多 30 条）。
     *
     * <p>走 `api.github.com`（与宿主自更新同一个域名 / 同一份配额），**不走镜像**：
     * 镜像只代理文件流，API 列表直连更稳。
     */
    static List<GitHubRelease> releases(String repo) {
        String r = repo == null ? "" : repo.trim();
        if (!r.matches(REPO_PATTERN)) throw new Fail("bad-url");
        String json = fetchText(GH_API + r + "/releases?per_page=30");
        List<GitHubReleases.Release> list = GitHubReleases.parseStrict(json);
        if (list == null) throw new Fail("bad-data");
        List<GitHubRelease> out = new ArrayList<GitHubRelease>();
        for (GitHubReleases.Release rel : list) {
            if (rel == null) continue;
            List<GitHubRelease.Asset> assets = new ArrayList<GitHubRelease.Asset>();
            for (GitHubReleases.Asset a : rel.assets) {
                if (a == null) continue;
                String url = a.apiUrl != null && !a.apiUrl.isEmpty() ? a.apiUrl : a.browserUrl;
                assets.add(new GitHubRelease.Asset(a.name, a.size, url, a.browserUrl, a.sha256));
            }
            out.add(new GitHubRelease(rel.tag, rel.prerelease, rel.publishedAt, assets));
        }
        return out;
    }

    // ---- 下载 ----------------------------------------------------------------

    /**
     * 下载到 {@code <cacheDir>/downloads/<一次性子目录>/<URL 文件名>}，走用户更新线路的镜像候选 +
     * sha256 校验（大小由"边下边查 1 GB 上限"兜底）。
     *
     * <p>★ 每次下载一个**独立子目录**（v39 评审 M3）：两个 Task 并发下载同名文件时，
     * `UpdateDownload` 的 `.part` / 成品名是固定的，会互相删 / 覆盖 —— 子目录把它们的落点分开。
     * 插件要长期保存，自己把文件挪到自己想放的地方（它本来就能读写自己的 cache）。
     *
     * @param cb 进度回调（在任务线程上；插件自己的回调由调用方转主线程）
     */
    static UpdateDownload.Result download(File cacheDir, String url, String expectedSha256,
                                          UpdateClient.Progress cb) {
        String bad = checkUrl(url);
        if (bad != null) throw new Fail(bad);
        String u = url.trim();
        String sha = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!sha.isEmpty() && !sha.matches("[0-9a-f]{64}")) throw new Fail("invalid");
        // ★ M1：下载 GitHub API 资产也是真实 API 请求，必须计入同一份配额（否则 40/h 形同虚设）
        noteApiRequest(u);
        String id = Long.toHexString(System.nanoTime()) + "-"
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
        File dir = new File(new File(cacheDir, "downloads"), id);
        String name = safeFileName(u);
        List<String> urls = new ArrayList<String>();
        try {
            urls = Mirrors.assetUrls(u, Config.get().updateMirror());
        } catch (Throwable ignored) {
            // 配置读不出来 ⇒ 直连（不因为一个设置项读失败就整个下载不可用）
        }
        if (urls == null || urls.isEmpty()) {
            urls = new ArrayList<String>();
            urls.add(u);
        }
        final UpdateClient.Progress inner = new UpdateClient.Progress() {
            @Override public void onProgress(long done, long total) {
                if (Thread.currentThread().isInterrupted()) throw new Abort("canceled");
                if (done > DOWNLOAD_MAX) throw new Abort("size");
                if (cb != null) cb.onProgress(done, total);
            }
        };
        try {
            UpdateDownload.Result r = UpdateDownload.run(urls, dir, name, -1L, sha, inner, "");
            if (r == null || r.status != UpdateDownload.Status.OK) {
                deleteQuietly(new File(dir, name + ".part"));
                deleteDirQuietly(dir);
            }
            return r;
        } catch (Abort a) {
            deleteQuietly(new File(dir, name + ".part"));
            deleteDirQuietly(dir);
            throw new Fail(a.reason);
        }
    }

    /** URL 里的文件名 → 安全的本地文件名（只留 `A-Za-z0-9._-`，去前导点，截到 80）。 */
    static String safeFileName(String url) {
        String name = "download";
        try {
            String path = URI.create(url.trim()).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                String last = slash >= 0 ? path.substring(slash + 1) : path;
                if (!last.trim().isEmpty()) name = last.trim();
            }
        } catch (Throwable ignored) {
            // URL 不合法 ⇒ 兜底名（调用方其实已经过 checkUrl；这里只求不崩）
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < 80; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        while (out.startsWith(".")) out = out.substring(1);
        if (out.trim().isEmpty()) out = "download";
        return out;
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 只删空目录（下载失败 / 取消后的那个一次性子目录）。 */
    private static void deleteDirQuietly(File dir) {
        try {
            if (dir == null) return;
            File[] fs = dir.listFiles();
            if (fs != null && fs.length > 0) return;
            dir.delete();
        } catch (Throwable ignored) {
        }
    }
}
