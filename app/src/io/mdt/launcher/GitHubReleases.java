package io.mdt.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub Releases 列表的解析与挑选（第 317 轮）。
 *
 * <p>为什么要它：以前为了绕开"嵌套 JSON 解析"造了一条**固定通道** —— 那是个坏设计：
 * 多一个 release 要维护、还要一条 404 回退。而 GitHub 本来就能查预览版：
 * <ul>
 *   <li>{@code /releases/latest} —— **只**返回非 prerelease（所以查不到预览版）；</li>
 *   <li>{@code /releases?per_page=N} —— **全都返回**，按创建时间倒序。用这个。</li>
 * </ul>
 *
 * <p>★ 本类用 `org.json`（Android 自带）。它**不能在 PC 上跑**（`android.jar` 里那份是
 * stub，方法体全是 `throw new RuntimeException("Stub!")`）—— 判据全部进 {@code SelfTest}，
 * 在设备上验。这是 2026-10-29 用户拍板的：「直接用安卓测试」。
 *
 * <p>⚠️ 纯解析，不联网、不碰文件。调用方负责取 {@link Asset#apiUrl} 去下载。
 */
final class GitHubReleases {

    /** 一个 release 资产（只要名字与两个 URL）。 */
    static final class Asset {
        final String name;
        /**
         * `assets[].url` —— 形如 `https://api.github.com/repos/…/releases/assets/<id>`。
         * ★ 下载它要带 `Accept: application/octet-stream`（{@link UpdateClient} 已经带了），
         * 否则拿到的是一段 JSON 元数据而不是文件。
         */
        final String apiUrl;
        /** `assets[].browser_download_url` —— 走 `github.com` 主站（慢，但人点得动）。 */
        final String browserUrl;

        Asset(String name, String apiUrl, String browserUrl) {
            this.name = name;
            this.apiUrl = apiUrl;
            this.browserUrl = browserUrl;
        }
    }

    /** 一个 release（只要挑版本需要的字段）。 */
    static final class Release {
        final String tag;
        final boolean prerelease;
        /** ISO 时间（`2026-10-29T12:00:00Z`）；可能为空。 */
        final String publishedAt;
        final List<Asset> assets = new ArrayList<Asset>();

        Release(String tag, boolean prerelease, String publishedAt) {
            this.tag = tag;
            this.prerelease = prerelease;
            this.publishedAt = publishedAt;
        }
    }

    private GitHubReleases() {}

    /**
     * 解析 `/releases` 的响应。**绝不抛异常** —— 坏输入返回空表（调用方按"没有版本"处理）。
     *
     * <p>⚠️ 这是**老的宽容口径**，只留给不关心"坏 JSON vs 真的没版本"的调用方。
     * 更新器自己必须用 {@link #parseStrict}：一个镜像/代理返回 HTML 错误页时，
     * 空表会被读成"确实没有可装版本"，界面就永远显示"已是最新"。
     *
     * <p>缺 `tag_name` 的条目直接跳过：那不是我们发的东西（GitHub 上可能有草稿/异常条目）。
     */
    static List<Release> parse(String json) {
        List<Release> out = parseStrict(json);
        return out == null ? new ArrayList<Release>() : out;
    }

    /**
     * 严格解析：**不是 JSON 数组就返回 null**（网络错误页 / 限流对象 / 空正文），
     * 合法空数组 `[]` 仍然返回空表（"真的没有版本"）。
     *
     * <p>调用方据此把"查不了"与"没版本"分开：前者提示网络/数据错误，后者才说"已是最新"。
     */
    static List<Release> parseStrict(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Throwable t) {
            return null;
        }
        List<Release> out = new ArrayList<Release>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String tag = o.optString("tag_name", "");
            if (tag.isEmpty()) continue;
            Release r = new Release(tag, o.optBoolean("prerelease", false),
                    o.optString("published_at", ""));
            JSONArray as = o.optJSONArray("assets");
            if (as != null) {
                for (int j = 0; j < as.length(); j++) {
                    JSONObject a = as.optJSONObject(j);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.isEmpty()) continue;
                    r.assets.add(new Asset(name,
                            a.optString("url", ""), a.optString("browser_download_url", "")));
                }
            }
            out.add(r);
        }
        return out;
    }

    /**
     * 挑一个版本。列表本身已按"最新在前"排好，所以**第一个匹配的就是最新的**。
     *
     * <p>🔴 第 338 轮：**只认 `v*` tag**。本仓库的 release 列表里现在还有一条
     * {@code plugins-latest}（插件更新通道，见 `PLUGIN-API.md` §10）—— 它不是启动器版本，
     * 没有 `update.json`；不跳过的话，默认的"预览通道"会把它当成最新一版，
     * 然后每台机器都报 "no update.json in release plugins-latest"，**自更新等于停摆**。
     * ★ 依据本来就有：`docs/RELEASING.md` 写死"只在 `v*` 命名空间里给发布打 tag"。
     *
     * @param allowPreview 允许预览版吗（= 设置里「稳定版和预览版」）。
     *                     {@code false} 时只认非 prerelease（= 「只查稳定版」）。
     * @return 没有匹配 ⇒ null（"还没有可装的版本"，不是错误）
     */
    static Release pick(List<Release> list, boolean allowPreview) {
        if (list == null) return null;
        for (Release r : list) {
            if (r == null) continue;
            if (!isLauncherTag(r.tag)) continue;
            if (allowPreview || !r.prerelease) return r;
        }
        return null;
    }

    /** 启动器版本的 tag 一律 `v…`（插件通道 / 实验 release 都不算）。 */
    static boolean isLauncherTag(String tag) {
        return tag != null && tag.length() > 1 && tag.charAt(0) == 'v'
                && Character.isDigit(tag.charAt(1));
    }

    /** 按名字找一个资产；没有 ⇒ null。 */
    static Asset asset(Release r, String name) {
        if (r == null || name == null) return null;
        for (Asset a : r.assets) {
            if (name.equals(a.name)) return a;
        }
        return null;
    }
}
