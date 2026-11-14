package io.mdt.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 下载 + sha256 校验 + 落盘（更新 / 插件更新 / dev 夹具的公共入口）。
 *
 * <p>🔴 **纯逻辑：不 import `android.*`** —— `java.io` / `java.security` / {@link UpdateClient}
 * 都是 JDK 的东西，所以整条链能在 PC 上跑（`.dsh/research/` 里的夹具起真 HTTP 服务）。
 *
 * <p>★ 第 378 轮（2026-11-14）起，真正的传输 / 校验 / 续传在 {@link ResumableDownload}；
 * 本类只剩三件事：<b>候选 → Source（带镜像短超时）</b>、<b>状态映射</b>、<b>对外签名保持不动</b>。
 * 于是老调用点（自检 / dev 口 / 插件）一行不改也能继续用。
 *
 * <p>三条老纪律不变（都是"半截包绝不能进安装流程"的变形）：
 * <ol>
 *   <li>**先写 `.part`，校验过了才改名** —— 名字本身就是"这份能用"的凭证；</li>
 *   <li>**失败默认删掉 `.part`**（{@code resume=false} 的路径），不留残骸；</li>
 *   <li>**sha256 是下载层唯一的根** —— 长度对、名字对都不算数。</li>
 * </ol>
 *
 * <p>⚠️ 证书校验**不在这里**：那要 {@code PackageManager}（{@code PluginTrust}），需要 Context。
 * 调用方拿到 {@link Result#file} 之后再验一次，两道都过才允许安装。
 */
final class UpdateDownload {

    /** 失败分类（给调用方分岔文案用；机器码不上屏）。 */
    enum Status {
        OK, NETWORK, SIZE, SHA, IO;

        boolean ok() { return this == OK; }
    }

    static final class Result {
        final Status status;
        /** 原始诊断（异常原文 / 算出来的 sha）；成功时为 null。 */
        final String detail;
        /** 成功时 = 校验过的成品文件；失败时 null。 */
        final File file;
        /** 实际算出来的 sha256（成功时非 null；sha256 == null/空 的调用也会填）。 */
        final String sha256;

        Result(Status status, String detail, File file, String sha256) {
            this.status = status;
            this.detail = detail;
            this.file = file;
            this.sha256 = sha256;
        }

        Result(Status status, String detail, File file) {
            this(status, detail, file, null);
        }
    }

    private UpdateDownload() {}

    /** 单地址入口（保留给 dev 夹具）。 */
    static Result run(String url, File dir, int key, long expectSize, String sha256,
                      UpdateClient.Progress cb) {
        return run(Collections.singletonList(url), dir, key, expectSize, sha256, cb);
    }

    /** 按候选顺序下载；{@code resume=false}（老行为：每个候选都从零、失败即删）。 */
    static Result run(List<String> urls, File dir, int key, long expectSize, String sha256,
                      UpdateClient.Progress cb) {
        return run(urls, dir, String.valueOf(key), expectSize, sha256, cb, ".apk", false);
    }

    /** 同上，但落点名字用**字符串键**（插件更新的键是 `id-<sha8>`，不是数字）。 */
    static Result run(List<String> urls, File dir, String key, long expectSize, String sha256,
                      UpdateClient.Progress cb) {
        return run(urls, dir, key, expectSize, sha256, cb, ".apk", false);
    }

    /** 同上，但落点后缀可给（插件下载的东西不一定是 `.apk`）。 */
    static Result run(List<String> urls, File dir, String key, long expectSize, String sha256,
                      UpdateClient.Progress cb, String suffix) {
        return run(urls, dir, key, expectSize, sha256, cb, suffix, false);
    }

    /** 数字键 + 续传开关（自更新用：同一个 versionCode 的包失败后接着下）。 */
    static Result run(List<String> urls, File dir, int key, long expectSize, String sha256,
                      UpdateClient.Progress cb, boolean resume) {
        return run(urls, dir, String.valueOf(key), expectSize, sha256, cb, ".apk", resume);
    }

    /** 字符串键 + 续传开关（插件更新用）。 */
    static Result run(List<String> urls, File dir, String key, long expectSize, String sha256,
                      UpdateClient.Progress cb, boolean resume) {
        return run(urls, dir, key, expectSize, sha256, cb, ".apk", resume);
    }

    /**
     * 真正的入口。
     *
     * @param resume false = 从零写、失败 / 取消删 `.part`（老行为）；
     *               true  = 同 key 有半截就接着下、取消 / 断网保留（要求 sha256 非空）
     */
    static Result run(List<String> urls, File dir, String key, long expectSize, String sha256,
                      UpdateClient.Progress cb, String suffix, boolean resume) {
        if (urls == null || urls.isEmpty()) return new Result(Status.IO, "no url", null);
        List<ResumableDownload.Source> sources = new ArrayList<ResumableDownload.Source>();
        for (String u : urls) {
            if (u == null || u.isEmpty()) continue;
            // 镜像只值 4~15 秒等待；直连给足 15 s / 60 s / 120 s（与第 336 轮同一套口径）
            boolean mirror = Mirrors.isMirrorUrl(u);
            sources.add(new ResumableDownload.Source(u,
                    mirror ? 4000 : UpdateClient.CONNECT_TIMEOUT_MS,
                    mirror ? 10000 : UpdateClient.READ_TIMEOUT_MS,
                    mirror ? 15000L : 120000L));
        }
        if (sources.isEmpty()) return new Result(Status.IO, "no url", null);

        final UpdateClient.Progress p = cb;
        ResumableDownload.Progress adapter = p == null ? null : new ResumableDownload.Progress() {
            @Override public void onProgress(long done, long total) {
                p.onProgress(done, total);
            }
        };
        ResumableDownload.Result r = ResumableDownload.run(sources, dir, key, suffix, resume,
                expectSize, sha256, adapter, null);

        Status st;
        switch (r.status) {
            case OK:
                st = Status.OK;
                break;
            case SIZE:
                st = Status.SIZE;
                break;
            case SHA:
                st = Status.SHA;
                break;
            case WRONG:
                // 所有源给的对象都与清单不符：有清单长度就报 SIZE，否则只能算网络失败
                st = expectSize > 0 ? Status.SIZE : Status.NETWORK;
                break;
            case CANCELED:
            case NETWORK:
                st = Status.NETWORK;
                break;
            default:                       // IO / INVALID
                st = Status.IO;
        }
        return new Result(st, r.detail, r.file, r.sha256);
    }
}
