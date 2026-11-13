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
 * （{@link #checkUrl} / {@link #safeFileName} / {@link #cacheHit} / 配额），自检直接钉它们；
 * 真网络只在 dev 手验。
 */
final class PluginNet {

    /** 文本 / JSON 正文上限（与宿主自更新清单同一个档）。 */
    static final int FETCH_MAX = 512 * 1024;
    /** 插件下载的体积上限。 */
    static final long DOWNLOAD_MAX = 1024L * 1024 * 1024;
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
        try {
            String host = URI.create(url.trim()).getHost();
            return "api.github.com".equals(host);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 文本 ----------------------------------------------------------------

    /** 拉文本（会走缓存 / 配额）。失败抛 {@link Fail}。 */
    static String fetchText(String url) {
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
        if (api) enforceQuota(now());
        try {
            Fetcher f = sTestFetcher;
            text = f != null ? f.text(u, FETCH_MAX, "application/json")
                    : UpdateClient.fetchText(u, FETCH_MAX);
        } catch (IOException e) {
            throw new Fail(isRateLimited(e) ? "rate-limited" : "network");
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
        return "json|" + url;
    }

    /** 服务器说"限频"时是 403/429（GitHub 的 message 里也常带 rate limit）。 */
    private static boolean isRateLimited(IOException e) {
        String m = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("http 403") || m.contains("http 429") || m.contains("rate limit");
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
                assets.add(new GitHubRelease.Asset(a.name, a.size, url));
            }
            out.add(new GitHubRelease(rel.tag, rel.prerelease, rel.publishedAt, assets));
        }
        return out;
    }

    // ---- 下载 ----------------------------------------------------------------

    /**
     * 下载到 {@code <cacheDir>/downloads/}，文件名从 URL 取（{@link #safeFileName}），
     * 走用户更新线路的镜像候选 + 长度 / sha256 校验。
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
        File dir = new File(cacheDir, "downloads");
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
            return UpdateDownload.run(urls, dir, name, -1L, sha, inner, "");
        } catch (Abort a) {
            deleteQuietly(new File(dir, name + ".part"));
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
}
