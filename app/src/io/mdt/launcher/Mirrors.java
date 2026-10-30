package io.mdt.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新线路（GitHub 镜像前缀）与候选顺序。**纯逻辑，不 import android** ——
 * 这样它能在 PC 上直接编跑（与 {@link UpdateManifest} / {@link UpdateClient} 同一条纪律）。
 *
 * <p>四个预设与桌面版 `launcher/config.py` 的 `DEFAULT_MIRROR_PRESETS` 对齐：
 * <ol>
 *   <li>{@link #TINYLAKE} —— 生态默认（桌面版默认；MindustryX 自更新也用这个域名）；</li>
 *   <li>{@link #GHFAST}；</li>
 *   <li>{@link #GH_PROXY}；</li>
 *   <li>{@link #GH_PROXY_NET}。</li>
 * </ol>
 *
 * <p>★ 顺序只是**候选**，不是可用性承诺：2026-10-30 实测里 ghfast / ghproxy.net 都不稳，
 * 所以正常下载只按顺序试、失败换下一个、直连永远最后；要挑最快得靠设置页的手动测速
 * （{@link MirrorTest}）。
 *
 * <p>配置里的三个取值：
 * <ul>
 *   <li>{@link #AUTO}（空串）—— 四个预设按顺序，最后直连；</li>
 *   <li>{@link #DIRECT} —— 只走直连（用户明确不要镜像时用）；</li>
 *   <li>某个预设前缀 —— 它排第一，其余预设 + 直连兜底。</li>
 * </ul>
 */
final class Mirrors {

    static final String AUTO = "";
    static final String DIRECT = "direct";

    static final String TINYLAKE = "https://gh.tinylake.top/";
    static final String GHFAST = "https://ghfast.top/";
    static final String GH_PROXY = "https://gh-proxy.com/";
    static final String GH_PROXY_NET = "https://ghproxy.net/";

    /** 与桌面版一致的四个预设（顺序也一致）。 */
    static final String[] PRESETS = {TINYLAKE, GHFAST, GH_PROXY, GH_PROXY_NET};

    private Mirrors() {}

    /**
     * 把用户/配置里的写法整理成「前缀」：补 scheme、补结尾斜杠、只认四个预设
     * （外加空串与 {@link #DIRECT}）。认不出来返回 null —— 调用方据此退回默认，
     * 别把一个打错的地址拼进下载 URL。
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return AUTO;
        if (DIRECT.equalsIgnoreCase(s)) return DIRECT;
        if (!s.contains("://")) s = "https://" + s;
        if (!s.endsWith("/")) s = s + "/";
        for (String p : PRESETS) {
            if (p.equalsIgnoreCase(s)) return p;
        }
        return null;
    }

    /** 配置读写的口径：认不出来一律当 AUTO（坏值不许让下载没路走）。 */
    static String stored(String raw) {
        String n = normalize(raw);
        return n == null ? AUTO : n;
    }

    static boolean isPreset(String prefix) {
        for (String p : PRESETS) {
            if (p.equals(prefix)) return true;
        }
        return false;
    }

    /** 这个 URL 是不是已经套过某个预设前缀（避免二次拼接）。 */
    static boolean isMirrorUrl(String url) {
        if (url == null) return false;
        for (String p : PRESETS) {
            if (url.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * 正常下载的候选顺序（前缀 → 完整 URL 由 {@link #assetUrls} 拼）。
     * <ul>
     *   <li>{@link #DIRECT} ⇒ 只有直连；</li>
     *   <li>某个预设 ⇒ 它 → 其余预设 → 直连；</li>
     *   <li>{@link #AUTO} ⇒ 四个预设 → 直连。</li>
     * </ul>
     * 空串代表直连。
     */
    static List<String> downloadCandidates(String selected) {
        List<String> out = new ArrayList<String>();
        String s = stored(selected);
        if (DIRECT.equals(s)) {
            out.add("");
            return out;
        }
        if (!AUTO.equals(s)) out.add(s);
        for (String p : PRESETS) {
            if (!out.contains(p)) out.add(p);
        }
        out.add("");
        return out;
    }

    /** 手动测速的候选：四个预设 + 直连（不跟着“当前选中”变，才能横向比较）。 */
    static List<String> testCandidates() {
        List<String> out = new ArrayList<String>();
        for (String p : PRESETS) out.add(p);
        out.add("");
        return out;
    }

    /**
     * 把一个 GitHub 资产 URL 展开成候选 URL 列表。
     *
     * <p>只给 `github.com` / `api.github.com` / `raw.githubusercontent.com` 加前缀：
     * 别的地址（本地夹具、用户自定义源）原样返回，绝不硬拼。
     * 已经是镜像前缀的也原样返回，避免 `https://镜像/https://镜像/...`。
     */
    static List<String> assetUrls(String url, String selected) {
        List<String> out = new ArrayList<String>();
        if (url == null) return out;
        String u = url.trim();
        if (u.isEmpty()) return out;
        if (u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:")) {
            out.add(u);
            return out;
        }
        if (isMirrorUrl(u) || !isGithubUrl(u)) {
            out.add(u);
            return out;
        }
        for (String prefix : downloadCandidates(selected)) {
            String full = prefix.isEmpty() ? u : prefix + u;
            if (!out.contains(full)) out.add(full);
        }
        return out;
    }

    static boolean isGithubUrl(String url) {
        String host = hostOf(url);
        return "github.com".equals(host)
                || "api.github.com".equals(host)
                || "raw.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host)
                || "release-assets.githubusercontent.com".equals(host);
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Throwable t) {
            return null;
        }
    }
}
