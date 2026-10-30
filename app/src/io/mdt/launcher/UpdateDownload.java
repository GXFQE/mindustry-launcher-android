package io.mdt.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/**
 * 下载 + sha256 校验 + 落盘。
 *
 * <p>🔴 **纯逻辑：不 import `android.*`** —— `java.io` / `java.security` / {@link UpdateClient}
 * 都是 JDK 的东西，所以整条链能在 PC 上跑（`PcDownloadCheck` 起一个真 HTTP 服务当夹具）。
 *
 * <p>三条纪律（都是"半截包绝不能进安装流程"的变形）：
 * <ol>
 *   <li>**先写 `.part`，校验过了才改名** —— 名字本身就是"这份能用"的凭证；</li>
 *   <li>**任何一步失败都删掉 `.part`**，不留残骸（否则下次启动会看到一个 3 MB 的孤儿文件）；</li>
 *   <li>**sha256 是下载层唯一的根** —— 长度对、名字对都不算数。</li>
 * </ol>
 *
 * <p>⚠️ 证书校验**不在这里**：那要 {@code PackageManager}（`PluginTrust`），需要 Context。
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

        Result(Status status, String detail, File file) {
            this.status = status;
            this.detail = detail;
            this.file = file;
        }
    }

    private UpdateDownload() {}

    /**
     * 下载到 {@code <dir>/<key>.apk} 并校验 sha256（单地址入口，保留给 dev 夹具）。
     *
     * @param key       文件名前缀（用 versionCode —— 同一版本重复下载直接覆盖）
     * @param expectSize 清单里的大小；{@code <= 0} 表示不知道（只校 sha256）
     */
    static Result run(String url, File dir, int key, long expectSize, String sha256,
                      UpdateClient.Progress cb) {
        return run(Collections.singletonList(url), dir, key, expectSize, sha256, cb);
    }

    /**
     * 按候选顺序下载：镜像失败换下一个，直连通常最后。
     *
     * <p>★ 每个候选都从零写 `.part`（本工程的下载不做断点续传；773 KB 的包重下一次
     * 比维护断点状态更简单）。**只有 sha256 + 长度都过的成品才会 rename 成 `.apk`**。
     *
     * <p>★ 镜像用短超时（4 s / 30 s），直连用默认（15 s / 60 s）—— 一个死镜像
     * 不该把整次下载拖到一分钟。
     */
    static Result run(List<String> urls, File dir, int key, long expectSize, String sha256,
                      UpdateClient.Progress cb) {
        return run(urls, dir, String.valueOf(key), expectSize, sha256, cb);
    }

    /**
     * 同上，但落点名字用**字符串键**（第 338 轮：插件更新的键是 `id-<sha8>`，
     * 插件 id 不是数字，版本号也不可靠 —— 只能拿内容哈希当键）。
     */
    static Result run(List<String> urls, File dir, String key, long expectSize, String sha256,
                      UpdateClient.Progress cb) {
        if (urls == null || urls.isEmpty()) {
            return new Result(Status.IO, "no url", null);
        }
        if (key == null || key.isEmpty()) {
            return new Result(Status.IO, "blank key", null);
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return new Result(Status.IO, "mkdirs failed: " + dir, null);
        }
        File part = new File(dir, key + ".part");
        File out = new File(dir, key + ".apk");
        Result last = null;

        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            // 清掉上一次的残留（失败尝试的 .part / 旧版本的同名成品）
            part.delete();
            out.delete();

            boolean mirror = Mirrors.isMirrorUrl(url);
            int connectMs = mirror ? 4000 : UpdateClient.CONNECT_TIMEOUT_MS;
            int readMs = mirror ? 10000 : UpdateClient.READ_TIMEOUT_MS;
            long totalMs = mirror ? 15000L : 120000L;
            try {
                UpdateClient.download(url, part, expectSize, cb, connectMs, readMs, totalMs);
            } catch (IOException e) {
                long got = part.length();
                part.delete();
                // 长度已经明显不对 ⇒ SIZE（"下到一半断了"与"网络不通"要分开，文案不同）
                if (expectSize > 0 && got > 0 && got != expectSize) {
                    last = new Result(Status.SIZE, "got " + got + " / want " + expectSize
                            + " @ " + url, null);
                } else {
                    last = new Result(Status.NETWORK, String.valueOf(e), null);
                }
                continue;
            }

            // UpdateClient 已经按 expectSize 校过一次；这里再按实际长度兜一道（防服务端不给 Content-Length）
            if (expectSize > 0 && part.length() != expectSize) {
                long got = part.length();
                part.delete();
                last = new Result(Status.SIZE, "got " + got + " / want " + expectSize
                        + " @ " + url, null);
                continue;
            }

            String got;
            try {
                got = sha256(part);
            } catch (IOException e) {
                part.delete();
                return new Result(Status.IO, String.valueOf(e), null);
            }
            if (sha256 == null || !got.equalsIgnoreCase(sha256)) {
                part.delete();
                last = new Result(Status.SHA, "got " + got + " @ " + url, null);
                continue;
            }

            if (!part.renameTo(out)) {
                part.delete();
                return new Result(Status.IO, "rename failed", null);
            }
            return new Result(Status.OK, null, out);
        }
        return last != null ? last : new Result(Status.IO, "no usable url", null);
    }

    /** 文件的 sha256（小写十六进制）。 */
    static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no sha-256", e);
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        return hex(md.digest());
    }

    /** 字节 → 小写十六进制（与 {@link UpdateManifest#validSha256} 的格式同一套）。 */
    static String hex(byte[] data) {
        if (data == null) return null;
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
