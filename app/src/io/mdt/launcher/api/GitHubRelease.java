package io.mdt.launcher.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * **v39**：GitHub 上的一个 release（{@link PluginApi#githubReleases} 的回调里给你）。
 *
 * <p>为什么要有这个类型：宿主自更新走的那条链已经处理好了 `api.github.com` 的
 * Accept / 302 / 限频 / 镜像线路（{@code GitHubReleases} + {@code UpdateClient}）；
 * 插件要读"某个仓库发了哪些版本"，不该各自去拼 URL 与解析 JSON。
 *
 * <p>字段是**快照**，不含任何可写入口；{@link Asset#url} 可以直接喂给
 * {@link PluginApi#download}（宿主会给它套镜像候选并校验 sha256）。
 */
public final class GitHubRelease {

    /** tag 名（形如 {@code v1.2.3}；**不保证**是启动器那种 {@code v*} 规则）。 */
    public final String tag;

    /** 是不是预发布（GitHub 的 {@code prerelease} 标记）。 */
    public final boolean prerelease;

    /** 发布时间（ISO-8601 字符串；可能为空串 —— GitHub 的草稿没有它）。 */
    public final String publishedAt;

    /** 这个 release 挂的资产（可能为空表）。 */
    public final List<Asset> assets;

    public GitHubRelease(String tag, boolean prerelease, String publishedAt, List<Asset> assets) {
        this.tag = tag == null ? "" : tag;
        this.prerelease = prerelease;
        this.publishedAt = publishedAt == null ? "" : publishedAt;
        List<Asset> copy = assets == null ? new ArrayList<Asset>() : new ArrayList<Asset>(assets);
        this.assets = Collections.unmodifiableList(copy);
    }

    /** 一个 release 资产（网页上"Assets"那一栏里的一条）。 */
    public static final class Asset {

        /** 文件名（GitHub 上显示的那个；能直接当本地文件名用）。 */
        public final String name;

        /** 字节数；GitHub 没给就是 {@code -1}（别当 0 用）。 */
        public final long size;

        /**
         * 下载地址（已经是"宿主 {@link PluginApi#download} 能直接吃"的那个）：
         * 优先 `assets[].url`（`api.github.com`，需要 octet-stream，宿主已处理），
         * 否则回落 `browser_download_url`。
         */
        public final String url;

        public Asset(String name, long size, String url) {
            this.name = name == null ? "" : name;
            this.size = size;
            this.url = url == null ? "" : url;
        }
    }
}
